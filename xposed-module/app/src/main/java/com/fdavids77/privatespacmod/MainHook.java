package com.fdavids77.privatespacmod;

import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * PrivateSpaceMod v3.4 — Unified LSPosed module
 *
 * Behaviour (Samsung Secure Folder model):
 *   Screen off  → Private Space (user 10) auto-locks.
 *                 WA clone users 11-15 are also stopped. No notifications. ✓
 *   Launch PS   → Tap Private Space → user 10 unlocks (OS default).
 *                 WA clone users 11-15 restart automatically. ✓
 *   While open  → WA clones receive notifications normally. ✓
 *   Icon order  → Preserved on every PS unlock. ✓
 *
 * v3.4 changes vs v3.2:
 *   - Fix launcher refresh: Hook UserManagerService.sendProfileBroadcast(Intent,int,int)
 *     in system_server to suppress ACTION_MANAGED_PROFILE_AVAILABLE broadcasts for
 *     users 11-15 during our start window (sSuppressProfileBroadcast flag).
 *     The v3.2 approach of suppressing launcher-side onAppsUpdated was too late —
 *     the suppressor flag was armed AFTER the broadcasts had already fired.
 *     By blocking the broadcast at source, the launcher never knows the profiles
 *     started and never reorders icons.
 *
 * v3.2 changes vs v3.1:
 *   - Block re-quiet: Hook setQuietModeEnabled to intercept and block attempts
 *     to re-enable quiet mode for users 11-15 while PS (user 10) is unlocked.
 *   - Unlock credentials: After startUser(uid), call unlockUser(uid, null) to
 *     move clone profiles from RUNNING_LOCKED → RUNNING_UNLOCKED.
 *   - Guard clone stop: Hook stopSingleUserLU for users 11-15 to block Android
 *     from stopping them via stopExcessRunningUsers / profile timeout.
 *
 * Target: Pixel 9 (tokay), Android 17, Magisk + LSPosed (Vector)
 * Author: fdavids77
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "PSMod";
    private static final String LAUNCHER_PKG = "com.google.android.apps.nexuslauncher";
    private static final String SYSTEM_SERVER_PKG = "android";

    private static final int PRIVATE_SPACE_USER_ID = 10;
    private static final int WA_USER_MIN = 11;
    private static final int WA_USER_MAX = 15;
    private static final long SUPPRESS_MS = 10_000;

    private static boolean isUnlocking = false;
    private static volatile boolean sSuppressNextSort = false;

    // True while our own cascade-stop is running; lets stopSingleUserLU through
    private static volatile boolean sOurCascadeStopRunning = false;
    // True while PS is unlocked (user 10 has been maybeUnlockUser'd but not stopped)
    private static volatile boolean sPsUnlocked = false;
    // True while we are starting clone users — suppresses MANAGED_PROFILE_AVAILABLE
    // broadcasts to the launcher so it never reorders icons.
    private static volatile boolean sSuppressProfileBroadcast = false;

    // Initialized lazily in armSuppressor()
    private static Handler sMainHandler = null;
    private static Runnable sDisarmRunnable = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

        if (lpparam.packageName.equals(SYSTEM_SERVER_PKG)) {
            hookPrivateSpaceLockUnlock(lpparam);
            return;
        }

        if (lpparam.packageName.equals(LAUNCHER_PKG)) {
            XposedBridge.log(TAG + ": Hooking Pixel Launcher");
            hookLabelHider(lpparam);
            hookDoubleTapGesture(lpparam);
            hookIconReorderFix(lpparam);
        }
    }

    // =========================================================================
    // PART 3 + 4: system_server — cascade stop/start of clone users with PS
    // =========================================================================

    private void dumpUserControllerMethods(Class<?> ucClass) {
        XposedBridge.log(TAG + ": === UserController method dump ===");
        for (java.lang.reflect.Method m : ucClass.getDeclaredMethods()) {
            String name = m.getName().toLowerCase();
            if (name.contains("stop") || name.contains("user") ||
                    name.contains("lock") || name.contains("unlock")) {
                StringBuilder sb = new StringBuilder();
                sb.append(TAG).append(": UC> ").append(m.getName()).append("(");
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(params[i].getSimpleName());
                }
                sb.append(") -> ").append(m.getReturnType().getSimpleName());
                XposedBridge.log(sb.toString());
            }
        }
        XposedBridge.log(TAG + ": === UserController dump end ===");
    }

    private static java.lang.reflect.Method sStopUserMethod = null;
    private static java.lang.reflect.Method sStartUserMethod = null;
    private static java.lang.reflect.Method sSetQuietModeMethod = null;
    private static java.lang.reflect.Method sUnlockUserMethod = null;

    private void hookPrivateSpaceLockUnlock(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> ucClass = XposedHelpers.findClass(
                    "com.android.server.am.UserController", lpparam.classLoader);

            dumpUserControllerMethods(ucClass);

            // ── Resolve stopUser ─────────────────────────────────────────────
            try {
                Class<?> iStopCb = XposedHelpers.findClassIfExists(
                        "android.app.IStopUserCallback", lpparam.classLoader);
                Class<?> keyEvictCb = XposedHelpers.findClassIfExists(
                        "com.android.server.am.UserState$KeyEvictedCallback", lpparam.classLoader);
                if (iStopCb != null && keyEvictCb != null) {
                    sStopUserMethod = ucClass.getDeclaredMethod(
                            "stopUser", int.class, boolean.class, iStopCb, keyEvictCb);
                    sStopUserMethod.setAccessible(true);
                    XposedBridge.log(TAG + ": Resolved stopUser(int,bool,IStopUserCallback,KeyEvictedCallback)");
                }
            } catch (NoSuchMethodException e) {
                XposedBridge.log(TAG + ": stopUser 4-arg not found: " + e.getMessage());
            }
            if (sStopUserMethod == null) {
                try {
                    sStopUserMethod = ucClass.getDeclaredMethod("stopUser", int.class, boolean.class);
                    sStopUserMethod.setAccessible(true);
                    XposedBridge.log(TAG + ": Resolved stopUser(int,bool) [fallback]");
                } catch (NoSuchMethodException e2) {
                    XposedBridge.log(TAG + ": stopUser fallback not found: " + e2.getMessage());
                }
            }

            // ── Resolve setQuietModeEnabled ──────────────────────────────────
            Class<?> umsClass = null;
            try {
                umsClass = XposedHelpers.findClassIfExists(
                        "com.android.server.pm.UserManagerService", lpparam.classLoader);
                if (umsClass != null) {
                    try {
                        sSetQuietModeMethod = umsClass.getDeclaredMethod(
                                "setQuietModeEnabled",
                                int.class, boolean.class,
                                android.content.IntentSender.class, String.class);
                        sSetQuietModeMethod.setAccessible(true);
                        XposedBridge.log(TAG + ": Resolved setQuietModeEnabled(int,bool,IntentSender,String)");
                    } catch (NoSuchMethodException e4) {
                        sSetQuietModeMethod = umsClass.getDeclaredMethod(
                                "setQuietModeEnabled", int.class, boolean.class);
                        sSetQuietModeMethod.setAccessible(true);
                        XposedBridge.log(TAG + ": Resolved setQuietModeEnabled(int,bool) [2-arg fallback]");
                    }
                } else {
                    XposedBridge.log(TAG + ": UserManagerService class not found");
                }
            } catch (NoSuchMethodException e) {
                XposedBridge.log(TAG + ": setQuietModeEnabled not found: " + e.getMessage());
            }

            // ── Resolve startUser ────────────────────────────────────────────
            try {
                sStartUserMethod = ucClass.getDeclaredMethod("startUser", int.class, int.class);
                sStartUserMethod.setAccessible(true);
                XposedBridge.log(TAG + ": Resolved startUser(int,int)");
            } catch (NoSuchMethodException e) {
                XposedBridge.log(TAG + ": startUser(int,int) not found: " + e.getMessage());
                try {
                    sStartUserMethod = ucClass.getDeclaredMethod("startUser", int.class, boolean.class);
                    sStartUserMethod.setAccessible(true);
                    XposedBridge.log(TAG + ": Resolved startUser(int,bool) [fallback]");
                } catch (NoSuchMethodException e2) {
                    XposedBridge.log(TAG + ": startUser fallback not found: " + e2.getMessage());
                }
            }

            // ── Resolve unlockUser(int, IProgressListener) ──────────────────
            try {
                Class<?> progressListener = XposedHelpers.findClassIfExists(
                        "android.app.IProgressListener", lpparam.classLoader);
                if (progressListener != null) {
                    sUnlockUserMethod = ucClass.getDeclaredMethod(
                            "unlockUser", int.class, progressListener);
                    sUnlockUserMethod.setAccessible(true);
                    XposedBridge.log(TAG + ": Resolved unlockUser(int,IProgressListener)");
                } else {
                    XposedBridge.log(TAG + ": IProgressListener not found — unlockUser unavailable");
                }
            } catch (NoSuchMethodException e) {
                XposedBridge.log(TAG + ": unlockUser not found: " + e.getMessage());
            }

            // ── LOCK: hook stopSingleUserLU ───────────────────────────────────
            try {
                Class<?> iStopCb = XposedHelpers.findClassIfExists(
                        "android.app.IStopUserCallback", lpparam.classLoader);
                Class<?> keyEvictCb = XposedHelpers.findClassIfExists(
                        "com.android.server.am.UserState$KeyEvictedCallback", lpparam.classLoader);
                if (iStopCb != null && keyEvictCb != null) {
                    XposedHelpers.findAndHookMethod(ucClass, "stopSingleUserLU",
                            int.class, boolean.class, iStopCb, keyEvictCb,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    try {
                                        int uid = (int) param.args[0];
                                        if (uid >= WA_USER_MIN && uid <= WA_USER_MAX
                                                && sPsUnlocked && !sOurCascadeStopRunning) {
                                            XposedBridge.log(TAG + ": BLOCKED stopSingleUserLU(" + uid
                                                    + ") — PS unlocked, not our cascade");
                                            param.setResult(null);
                                        }
                                    } catch (Throwable t) {
                                        XposedBridge.log(TAG + ": stopSingleUserLU guard threw: " + t);
                                    }
                                }

                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        if ((int) param.args[0] == PRIVATE_SPACE_USER_ID) {
                                            XposedBridge.log(TAG + ": PS user 10 stopping — cascading to 11-15");
                                            sPsUnlocked = false;
                                            stopCloneUsers(param.thisObject);
                                        }
                                    } catch (Throwable t) {
                                        XposedBridge.log(TAG + ": stopSingleUserLU hook body threw: " + t);
                                    }
                                }
                            });
                    XposedBridge.log(TAG + ": Hooked stopSingleUserLU(int,bool,IStopCb,KeyEvictCb)");
                } else {
                    XposedBridge.log(TAG + ": stopSingleUserLU — callback classes null, skipping lock hook");
                }
            } catch (NoSuchMethodError e) {
                XposedBridge.log(TAG + ": stopSingleUserLU hook failed: " + e.getMessage());
            }

            // ── UNLOCK: hook maybeUnlockUser ─────────────────────────────────
            try {
                XposedHelpers.findAndHookMethod(ucClass, "maybeUnlockUser",
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if ((int) param.args[0] == PRIVATE_SPACE_USER_ID) {
                                        XposedBridge.log(TAG + ": PS user 10 maybeUnlockUser — restarting 11-15");
                                        sPsUnlocked = true;
                                        startCloneUsers(param.thisObject);
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": maybeUnlockUser hook body threw: " + t);
                                }
                            }
                        });
                XposedBridge.log(TAG + ": Hooked UserController.maybeUnlockUser(int)");
            } catch (NoSuchMethodError e) {
                XposedBridge.log(TAG + ": maybeUnlockUser not found: " + e.getMessage());
            }

            // ── BLOCK RE-QUIET: intercept setQuietModeEnabled ────────────────
            if (umsClass != null && sSetQuietModeMethod != null) {
                try {
                    if (sSetQuietModeMethod.getParameterCount() == 4) {
                        XposedHelpers.findAndHookMethod(umsClass, "setQuietModeEnabled",
                                int.class, boolean.class,
                                android.content.IntentSender.class, String.class,
                                new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) {
                                        interceptSetQuietMode(param);
                                    }
                                });
                    } else {
                        XposedHelpers.findAndHookMethod(umsClass, "setQuietModeEnabled",
                                int.class, boolean.class,
                                new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) {
                                        interceptSetQuietMode(param);
                                    }
                                });
                    }
                    XposedBridge.log(TAG + ": Hooked setQuietModeEnabled (re-quiet blocker)");
                } catch (Exception e) {
                    XposedBridge.log(TAG + ": setQuietModeEnabled hook failed: " + e.getMessage());
                }
            }

            // ── BLOCK LAUNCHER REFRESH: suppress sendProfileBroadcast ────────
            // UserManagerService.sendProfileBroadcast(Intent, int, int) sends
            // ACTION_MANAGED_PROFILE_AVAILABLE to the launcher when a managed profile
            // starts. Hooking it at source (system_server) and suppressing it for
            // users 11-15 while sSuppressProfileBroadcast is true prevents Pixel
            // Launcher from calling onAppsUpdated and reordering icons.
            //
            // Signature confirmed via dexdump on Android 17:
            //   sendProfileBroadcast(Landroid/content/Intent;II)V  (PUBLIC FINAL)
            //   in com.android.server.pm.UserManagerService
            if (umsClass != null) {
                try {
                    XposedHelpers.findAndHookMethod(umsClass, "sendProfileBroadcast",
                            android.content.Intent.class, int.class, int.class,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    try {
                                        // arg[1] = profileUserId (the managed profile user)
                                        // arg[2] = parentUserId (parent of the profile)
                                        int profileUid = (int) param.args[1];
                                        android.content.Intent intent =
                                                (android.content.Intent) param.args[0];
                                        String action = intent != null ? intent.getAction() : null;
                                        if (profileUid >= WA_USER_MIN && profileUid <= WA_USER_MAX
                                                && sSuppressProfileBroadcast) {
                                            XposedBridge.log(TAG
                                                    + ": SUPPRESSED sendProfileBroadcast("
                                                    + action + ", user=" + profileUid
                                                    + ") — launcher refresh blocked");
                                            param.setResult(null);
                                        } else {
                                            XposedBridge.log(TAG
                                                    + ": sendProfileBroadcast("
                                                    + action + ", user=" + profileUid
                                                    + ") — allowed");
                                        }
                                    } catch (Throwable t) {
                                        XposedBridge.log(TAG + ": sendProfileBroadcast hook threw: " + t);
                                    }
                                }
                            });
                    XposedBridge.log(TAG + ": Hooked sendProfileBroadcast (launcher refresh fix)");
                } catch (NoSuchMethodError e) {
                    XposedBridge.log(TAG + ": sendProfileBroadcast hook failed: " + e.getMessage());
                }
            }

        } catch (XposedHelpers.ClassNotFoundError e) {
            XposedBridge.log(TAG + ": UserController not found: " + e.getMessage());
        }
    }

    /**
     * Intercepts setQuietModeEnabled for users 11-15.
     * Blocks re-enable (true) calls while PS is unlocked.
     */
    private static void interceptSetQuietMode(XC_MethodHook.MethodHookParam param) {
        try {
            int uid = (int) param.args[0];
            boolean enable = (boolean) param.args[1];
            if (uid >= WA_USER_MIN && uid <= WA_USER_MAX) {
                if (enable && sPsUnlocked) {
                    XposedBridge.log(TAG + ": BLOCKED setQuietModeEnabled(" + uid
                            + ", true) — PS unlocked");
                    param.setResult(null);
                } else {
                    XposedBridge.log(TAG + ": setQuietModeEnabled(" + uid + ", " + enable + ") — allowed");
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": interceptSetQuietMode threw: " + t);
        }
    }

    /**
     * Stop clone users 11-15 via the pre-resolved stopUser Method.
     */
    private static void stopCloneUsers(Object ucInstance) {
        if (sStopUserMethod == null) {
            XposedBridge.log(TAG + ": stopUser method not resolved — cannot stop clones");
            return;
        }
        sOurCascadeStopRunning = true;
        new Thread(() -> {
            try {
                for (int uid = WA_USER_MIN; uid <= WA_USER_MAX; uid++) {
                    try {
                        int paramCount = sStopUserMethod.getParameterCount();
                        if (paramCount == 4) {
                            sStopUserMethod.invoke(ucInstance, uid, true, null, null);
                        } else {
                            sStopUserMethod.invoke(ucInstance, uid, true);
                        }
                        XposedBridge.log(TAG + ": stopUser(" + uid + ") OK");
                    } catch (Exception e) {
                        XposedBridge.log(TAG + ": stopUser(" + uid + ") failed: " + e.getMessage());
                    }
                }
            } finally {
                sOurCascadeStopRunning = false;
            }
        }, "PSMod-StopClones").start();
    }

    /**
     * Start clone users 11-15.
     *
     * Per user:
     *   1. setQuietModeEnabled(uid, false) — clear QUIET_MODE flag
     *   2. startUser(uid, 2)              — start in background
     *   3. unlockUser(uid, null)           — unlock credentials → RUNNING_UNLOCKED
     *
     * sSuppressProfileBroadcast is set true before any startUser call and cleared
     * 5 seconds after the last one — this prevents sendProfileBroadcast from
     * delivering ACTION_MANAGED_PROFILE_AVAILABLE to Pixel Launcher, which would
     * otherwise trigger onAppsUpdated and reorder icons.
     */
    private static void startCloneUsers(Object ucInstance) {
        if (sStartUserMethod == null) {
            XposedBridge.log(TAG + ": startUser method not resolved — cannot start clones");
            return;
        }
        new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

            // Arm broadcast suppressor BEFORE any startUser call
            sSuppressProfileBroadcast = true;
            XposedBridge.log(TAG + ": sSuppressProfileBroadcast = true");

            // Resolve UMS getInstance once for this batch
            Object umsInstance = null;
            if (sSetQuietModeMethod != null) {
                try {
                    Class<?> umsClass = sSetQuietModeMethod.getDeclaringClass();
                    java.lang.reflect.Method getInstanceMethod =
                            umsClass.getDeclaredMethod("getInstance");
                    getInstanceMethod.setAccessible(true);
                    umsInstance = getInstanceMethod.invoke(null);
                } catch (Exception e) {
                    XposedBridge.log(TAG + ": UMS getInstance failed: " + e.getMessage());
                }
            }

            for (int uid = WA_USER_MIN; uid <= WA_USER_MAX; uid++) {
                try {
                    // Step 1: clear QUIET_MODE flag
                    if (sSetQuietModeMethod != null && umsInstance != null) {
                        try {
                            int paramCount = sSetQuietModeMethod.getParameterCount();
                            if (paramCount == 4) {
                                sSetQuietModeMethod.invoke(umsInstance, uid, false, null, null);
                            } else {
                                sSetQuietModeMethod.invoke(umsInstance, uid, false);
                            }
                            XposedBridge.log(TAG + ": quietMode disabled for user " + uid);
                        } catch (Exception qe) {
                            XposedBridge.log(TAG + ": quietMode disable failed for " + uid
                                    + ": " + qe.getMessage());
                        }
                    }

                    // Step 2: start user in background
                    int paramCount = sStartUserMethod.getParameterCount();
                    if (paramCount == 2 && sStartUserMethod.getParameterTypes()[1] == int.class) {
                        sStartUserMethod.invoke(ucInstance, uid, 2); // USER_START_MODE_BACKGROUND
                    } else {
                        sStartUserMethod.invoke(ucInstance, uid, true);
                    }
                    XposedBridge.log(TAG + ": startUser(" + uid + ") OK");

                    // Step 3: unlock credential storage
                    if (sUnlockUserMethod != null) {
                        try {
                            Thread.sleep(300);
                            sUnlockUserMethod.invoke(ucInstance, uid, null);
                            XposedBridge.log(TAG + ": unlockUser(" + uid + ") OK");
                        } catch (Exception ue) {
                            XposedBridge.log(TAG + ": unlockUser(" + uid + ") failed: "
                                    + ue.getMessage());
                        }
                    }

                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    XposedBridge.log(TAG + ": startUser(" + uid + ") ITE cause: "
                            + (cause != null ? cause.getClass().getSimpleName()
                            + ": " + cause.getMessage() : "null"));
                } catch (Exception e) {
                    XposedBridge.log(TAG + ": startUser(" + uid + ") failed: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }

            // Keep suppressor armed for 5 s after last startUser (broadcasts may be async)
            // then disarm to allow normal profile broadcasts again
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            sSuppressProfileBroadcast = false;
            XposedBridge.log(TAG + ": sSuppressProfileBroadcast = false (disarmed)");

        }, "PSMod-StartClones").start();
    }

    // =========================================================================
    // PART 5: Icon reorder fix (launcher-side safety net, kept as belt-and-suspenders)
    // =========================================================================

    private void hookIconReorderFix(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] containerClasses = {
                "com.android.launcher3.allapps.ActivityAllAppsContainerView",
                "com.google.android.apps.nexuslauncher.allapps.ActivityAllAppsContainerView",
                "com.android.launcher3.allapps.AllAppsContainerView",
                "com.google.android.apps.nexuslauncher.allapps.AllAppsContainerView",
        };

        for (String className : containerClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, lpparam.classLoader);
                try {
                    XposedHelpers.findAndHookMethod(clazz, "onAppsUpdated",
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    if (sSuppressNextSort) {
                                        armSuppressor();
                                        XposedBridge.log(TAG + ": Suppressed "
                                                + param.thisObject.getClass().getSimpleName()
                                                + ".onAppsUpdated");
                                        param.setResult(null);
                                    }
                                }
                            });
                    XposedBridge.log(TAG + ": Hooked " + className + ".onAppsUpdated");
                } catch (NoSuchMethodError e) {
                    XposedBridge.log(TAG + ": onAppsUpdated not found on " + className);
                }
            } catch (XposedHelpers.ClassNotFoundError ignored) {}
        }

        String[] listClasses = {
                "com.android.launcher3.allapps.AlphabeticalAppsList",
                "com.google.android.apps.nexuslauncher.allapps.AlphabeticalAppsList"
        };
        for (String className : listClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, lpparam.classLoader);
                for (String method : new String[]{"onAppsUpdated", "updateItemFilter", "sortAndFilter"}) {
                    try {
                        XposedHelpers.findAndHookMethod(clazz, method,
                                new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) {
                                        if (sSuppressNextSort) {
                                            armSuppressor();
                                            XposedBridge.log(TAG + ": Suppressed AlphabeticalAppsList."
                                                    + param.method.getName());
                                            param.setResult(null);
                                        }
                                    }
                                });
                    } catch (NoSuchMethodError ignored) {}
                }
                XposedBridge.log(TAG + ": Hooked AlphabeticalAppsList on " + className);
                break;
            } catch (XposedHelpers.ClassNotFoundError ignored) {}
        }

        String[] modelClasses = {
                "com.android.launcher3.LauncherModel",
                "com.google.android.apps.nexuslauncher.LauncherModel"
        };
        for (String className : modelClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, lpparam.classLoader);
                for (String method : new String[]{"onUserUnlocked", "onProfileAvailabilityChanged"}) {
                    try {
                        XposedHelpers.findAndHookMethod(clazz, method,
                                UserHandle.class,
                                new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) {
                                        try {
                                            UserHandle uh = (UserHandle) param.args[0];
                                            int uid = (int) XposedHelpers.callMethod(uh, "getIdentifier");
                                            if (uid == PRIVATE_SPACE_USER_ID) {
                                                XposedBridge.log(TAG + ": LauncherModel."
                                                        + param.method.getName()
                                                        + " for user 10 — arming suppressor");
                                                armSuppressor();
                                            }
                                        } catch (Exception e) {
                                            XposedBridge.log(TAG + ": arm error: " + e.getMessage());
                                        }
                                    }
                                });
                        XposedBridge.log(TAG + ": Hooked " + className + "." + method);
                        return;
                    } catch (NoSuchMethodError ignored) {}
                }
            } catch (XposedHelpers.ClassNotFoundError ignored) {}
        }
    }

    private static synchronized void armSuppressor() {
        if (sMainHandler == null) {
            sMainHandler = new Handler(Looper.getMainLooper());
            sDisarmRunnable = () -> {
                sSuppressNextSort = false;
                XposedBridge.log(TAG + ": Sort suppressor disarmed");
            };
        }
        sSuppressNextSort = true;
        sMainHandler.removeCallbacks(sDisarmRunnable);
        sMainHandler.postDelayed(sDisarmRunnable, SUPPRESS_MS);
    }

    // =========================================================================
    // PART 1: PSLabelHider — Hide "Private" label + lock icon
    // =========================================================================

    private void hookLabelHider(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod(
                View.class, "setVisibility", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View view = (View) param.thisObject;
                        int id = view.getId();
                        if (id == View.NO_ID) return;
                        try {
                            String entryName = view.getResources().getResourceEntryName(id);
                            if (shouldHideView(entryName)) {
                                param.args[0] = View.GONE;
                            }
                        } catch (Resources.NotFoundException ignored) {}
                    }
                }
        );

        XposedHelpers.findAndHookMethod(
                TextView.class, "setText",
                CharSequence.class, TextView.BufferType.class, boolean.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        CharSequence text = (CharSequence) param.args[0];
                        if (text != null && "Private".equals(text.toString())) {
                            param.args[0] = "";
                        }
                    }
                }
        );

        hookPrivateProfileManager(lpparam);
        XposedBridge.log(TAG + ": Label hider hooks installed");
    }

    private boolean shouldHideView(String entryName) {
        return "lock_icon".equals(entryName)
                || "ps_lock_unlock_button".equals(entryName)
                || "settingsAndLockGroup".equals(entryName)
                || "private_space_lock_icon".equals(entryName)
                || "ps_settings_button".equals(entryName);
    }

    private void hookPrivateProfileManager(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] classNames = {
                "com.android.launcher3.model.data.PrivateProfileManager",
                "com.android.launcher3.pm.PrivateProfileManager",
                "com.google.android.apps.nexuslauncher.privateprofile.PrivateProfileManager"
        };

        for (String className : classNames) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, lpparam.classLoader);
                for (String method : new String[]{"updateView", "bind"}) {
                    try {
                        XposedHelpers.findAndHookMethod(clazz, method,
                                new XC_MethodHook() {
                                    @Override
                                    protected void afterHookedMethod(MethodHookParam param) {
                                        hidePrivateSpaceSettingsViews(param.thisObject);
                                    }
                                });
                    } catch (NoSuchMethodError ignored) {}
                }
                XposedBridge.log(TAG + ": Hooked PrivateProfileManager: " + className);
                break;
            } catch (XposedHelpers.ClassNotFoundError ignored) {}
        }
    }

    private void hidePrivateSpaceSettingsViews(Object manager) {
        try {
            Object btn = XposedHelpers.getObjectField(manager, "mPrivateSpaceSettingsButton");
            if (btn instanceof View) {
                ((View) btn).setVisibility(View.GONE);
                if (((View) btn).getParent() instanceof View) {
                    ((View) ((View) btn).getParent()).setVisibility(View.GONE);
                }
            }
        } catch (NoSuchFieldError | ClassCastException e) {
            XposedBridge.log(TAG + ": mPrivateSpaceSettingsButton not found: " + e.getMessage());
        }
    }

    // =========================================================================
    // PART 2: Double-Tap → Unlock Private Space
    // =========================================================================

    private void hookDoubleTapGesture(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] touchListenerClasses = {
                "com.android.launcher3.touch.WorkspaceTouchListener",
                "com.google.android.apps.nexuslauncher.touch.WorkspaceTouchListener"
        };

        for (String className : touchListenerClasses) {
            try {
                Class<?> listenerClass = XposedHelpers.findClass(className, lpparam.classLoader);
                XposedHelpers.findAndHookMethod(listenerClass, "onDoubleTap",
                        android.view.MotionEvent.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                XposedBridge.log(TAG + ": Double-tap intercepted");
                                try {
                                    Object launcher = XposedHelpers.getObjectField(
                                            param.thisObject, "mLauncher");
                                    unlockPrivateSpace((Context) launcher);
                                } catch (Exception e) {
                                    XposedBridge.log(TAG + ": Double-tap error: " + e.getMessage());
                                }
                                param.setResult(true);
                            }
                        });
                XposedBridge.log(TAG + ": Hooked onDoubleTap on " + className);
                return;
            } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError ignored) {}
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "android.view.GestureDetector$SimpleOnGestureListener",
                    lpparam.classLoader, "onDoubleTap",
                    android.view.MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String cls = param.thisObject.getClass().getName();
                            if (cls.contains("Workspace") || cls.contains("DragLayer")) {
                                try {
                                    Context ctx = null;
                                    try { ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mLauncher"); }
                                    catch (NoSuchFieldError e1) {
                                        try { ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mActivity"); }
                                        catch (NoSuchFieldError e2) {
                                            if (param.thisObject instanceof View)
                                                ctx = ((View) param.thisObject).getContext();
                                        }
                                    }
                                    if (ctx != null) unlockPrivateSpace(ctx);
                                } catch (Exception e) {
                                    XposedBridge.log(TAG + ": Fallback error: " + e.getMessage());
                                }
                                param.setResult(true);
                            }
                        }
                    });
        } catch (Exception e) {
            XposedBridge.log(TAG + ": GestureDetector fallback failed: " + e.getMessage());
        }
    }

    private void unlockPrivateSpace(Context context) {
        if (isUnlocking) return;
        isUnlocking = true;

        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec("su -c am start-user " + PRIVATE_SPACE_USER_ID);
                p.waitFor();
                Thread.sleep(500);

                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
                        if (um == null) { isUnlocking = false; return; }
                        Method ofMethod = UserHandle.class.getDeclaredMethod("of", int.class);
                        UserHandle psUser = (UserHandle) ofMethod.invoke(null, PRIVATE_SPACE_USER_ID);
                        boolean result = um.requestQuietModeEnabled(false, psUser);
                        XposedBridge.log(TAG + ": requestQuietModeEnabled(false) = " + result);
                    } catch (SecurityException se) {
                        unlockViaActivity(context);
                    } catch (Exception e) {
                        XposedBridge.log(TAG + ": Unlock error: " + e.getMessage());
                        unlockViaActivity(context);
                    } finally {
                        isUnlocking = false;
                    }
                });
            } catch (Exception e) {
                XposedBridge.log(TAG + ": Unlock thread error: " + e.getMessage());
                isUnlocking = false;
            }
        }).start();
    }

    private void unlockViaActivity(Context context) {
        try {
            android.content.Intent intent = new android.content.Intent();
            intent.setComponent(new android.content.ComponentName(
                    "com.fdavids77.privatespacmod",
                    "com.fdavids77.privatespacmod.UnlockActivity"));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            XposedBridge.log(TAG + ": Launched UnlockActivity");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": UnlockActivity failed: " + e.getMessage());
        }
    }
}
