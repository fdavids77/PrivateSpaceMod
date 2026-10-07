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
 * PrivateSpaceMod v2.4 — Unified LSPosed module
 *
 * Behaviour (Samsung Secure Folder model):
 *   Screen off  → Private Space (user 10) auto-locks.
 *                 WA clone users 11-15 are also stopped. No notifications. ✓
 *   Launch PS   → Tap Private Space → user 10 unlocks (OS default).
 *                 WA clone users 11-15 restart automatically. ✓
 *   While open  → WA clones receive notifications normally. ✓
 *   Icon order  → Preserved on every PS unlock. ✓
 *
 * Parts:
 *   1. Hide "Private" label + lock icon
 *   2. Double-tap home screen → unlock Private Space
 *   3. system_server: stop clone users 11-15 when PS (user 10) locks
 *   4. system_server: restart clone users 11-15 when PS (user 10) unlocks
 *   5. Launcher: suppress onAppsUpdated in ActivityAllAppsContainerView
 *
 * v2.4 fix: removed static Handler/Runnable field initializers — those ran
 * at class-load time before the main Looper existed, silently crashing the
 * whole module. Now initialized lazily inside armSuppressor().
 *
 * Target: Pixel 9, Android 15/16/17, Magisk + LSPosed (JingMatrix/Vector)
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

    // Initialized lazily in armSuppressor() — NOT as static field initializers,
    // because Looper.getMainLooper() may be null at class-load time under LSPosed.
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

    /**
     * Dumps all UserController methods whose names contain "stop", "user", "lock", or "unlock"
     * to the LSPosed modules log. Run once to discover Android 17 method signatures.
     */
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

    // Resolved once at hook-install time; used by stop/startCloneUsers
    private static java.lang.reflect.Method sStopUserMethod = null;
    private static java.lang.reflect.Method sStartUserMethod = null;
    private static java.lang.reflect.Method sSetQuietModeMethod = null;

    private void hookPrivateSpaceLockUnlock(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> ucClass = XposedHelpers.findClass(
                    "com.android.server.am.UserController", lpparam.classLoader);

            // Dump signatures once so we can verify them in the log
            dumpUserControllerMethods(ucClass);

            // ── Resolve stopUser method via reflection (null-safe, avoids Xposed type matching) ──
            // Android 17: stopUser(int, boolean, IStopUserCallback, UserState$KeyEvictedCallback)
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
            // Fallback: stopUser(int, boolean) — older signature
            if (sStopUserMethod == null) {
                try {
                    sStopUserMethod = ucClass.getDeclaredMethod("stopUser", int.class, boolean.class);
                    sStopUserMethod.setAccessible(true);
                    XposedBridge.log(TAG + ": Resolved stopUser(int,bool) [fallback]");
                } catch (NoSuchMethodException e2) {
                    XposedBridge.log(TAG + ": stopUser fallback not found: " + e2.getMessage());
                }
            }

            // ── Resolve UserManagerService.setQuietModeEnabled ──
            // Call this before startUser to suppress "unpause work apps" prompt.
            // Android 17 signature: setQuietModeEnabled(int, boolean, IntentSender, String)
            // Older signature:      setQuietModeEnabled(int, boolean)
            // Try the 4-arg form first; fall back to 2-arg for older builds.
            try {
                Class<?> umsClass = XposedHelpers.findClassIfExists(
                        "com.android.server.pm.UserManagerService", lpparam.classLoader);
                if (umsClass != null) {
                    // Try 4-arg first (Android 17+)
                    try {
                        sSetQuietModeMethod = umsClass.getDeclaredMethod(
                                "setQuietModeEnabled",
                                int.class, boolean.class,
                                android.content.IntentSender.class, String.class);
                        sSetQuietModeMethod.setAccessible(true);
                        XposedBridge.log(TAG + ": Resolved setQuietModeEnabled(int,bool,IntentSender,String)");
                    } catch (NoSuchMethodException e4) {
                        // Fall back to 2-arg (Android 14/15)
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

            // ── Resolve startUser method via reflection ──
            // Android 17: startUser(int, int) — second arg is startMode (0=background)
            try {
                sStartUserMethod = ucClass.getDeclaredMethod("startUser", int.class, int.class);
                sStartUserMethod.setAccessible(true);
                XposedBridge.log(TAG + ": Resolved startUser(int,int)");
            } catch (NoSuchMethodException e) {
                XposedBridge.log(TAG + ": startUser(int,int) not found: " + e.getMessage());
                // Fallback: startUser(int, boolean) — older signature
                try {
                    sStartUserMethod = ucClass.getDeclaredMethod("startUser", int.class, boolean.class);
                    sStartUserMethod.setAccessible(true);
                    XposedBridge.log(TAG + ": Resolved startUser(int,bool) [fallback]");
                } catch (NoSuchMethodException e2) {
                    XposedBridge.log(TAG + ": startUser fallback not found: " + e2.getMessage());
                }
            }

            // ── LOCK: hook stopSingleUserLU to cascade stop to 11-15 ──────
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
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        if ((int) param.args[0] == PRIVATE_SPACE_USER_ID) {
                                            XposedBridge.log(TAG + ": PS user 10 stopping — cascading to 11-15");
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

            // ── UNLOCK: hook maybeUnlockUser to cascade start to 11-15 ───
            try {
                XposedHelpers.findAndHookMethod(ucClass, "maybeUnlockUser",
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if ((int) param.args[0] == PRIVATE_SPACE_USER_ID) {
                                        XposedBridge.log(TAG + ": PS user 10 maybeUnlockUser — restarting 11-15");
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

        } catch (XposedHelpers.ClassNotFoundError e) {
            XposedBridge.log(TAG + ": UserController not found: " + e.getMessage());
        }
    }

    /**
     * Stop clone users 11-15 by invoking the pre-resolved stopUser Method directly.
     * Using java.lang.reflect.Method.invoke() lets us pass explicit nulls for interface
     * args without Xposed trying to match types at runtime (which crashes on null).
     */
    private static void stopCloneUsers(Object ucInstance) {
        if (sStopUserMethod == null) {
            XposedBridge.log(TAG + ": stopUser method not resolved — cannot stop clones");
            return;
        }
        new Thread(() -> {
            for (int uid = WA_USER_MIN; uid <= WA_USER_MAX; uid++) {
                try {
                    // Invoke with explicit nulls — the method accepts null callbacks fine
                    int paramCount = sStopUserMethod.getParameterCount();
                    if (paramCount == 4) {
                        sStopUserMethod.invoke(ucInstance, uid, true, null, null);
                    } else {
                        // 2-arg fallback: stopUser(int, boolean)
                        sStopUserMethod.invoke(ucInstance, uid, true);
                    }
                    XposedBridge.log(TAG + ": stopUser(" + uid + ") OK");
                } catch (Exception e) {
                    XposedBridge.log(TAG + ": stopUser(" + uid + ") failed: " + e.getMessage());
                }
            }
        }, "PSMod-StopClones").start();
    }

    /**
     * Start clone users 11-15 via the pre-resolved startUser Method.
     * 1.5 s delay gives system_server time to finish unlocking user 10 first.
     * Calls setQuietModeEnabled(uid, false, null, null) before each startUser
     * to suppress the "unpause work apps" dialog Android shows when a profile
     * is started from quiet state.
     */
    private static void startCloneUsers(Object ucInstance) {
        if (sStartUserMethod == null) {
            XposedBridge.log(TAG + ": startUser method not resolved — cannot start clones");
            return;
        }
        new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            for (int uid = WA_USER_MIN; uid <= WA_USER_MAX; uid++) {
                try {
                    // Disable quiet mode before startUser to suppress "unpause work apps" prompt.
                    // Android 17: setQuietModeEnabled(int, boolean, IntentSender, String)
                    // Older:      setQuietModeEnabled(int, boolean)
                    if (sSetQuietModeMethod != null) {
                        try {
                            Class<?> umsClass = sSetQuietModeMethod.getDeclaringClass();
                            java.lang.reflect.Method getInstanceMethod = null;
                            try {
                                getInstanceMethod = umsClass.getDeclaredMethod("getInstance");
                                getInstanceMethod.setAccessible(true);
                            } catch (NoSuchMethodException ignored) {}
                            if (getInstanceMethod != null) {
                                Object umsInstance = getInstanceMethod.invoke(null);
                                int paramCount = sSetQuietModeMethod.getParameterCount();
                                if (paramCount == 4) {
                                    // Android 17+: (int, boolean, IntentSender, String)
                                    sSetQuietModeMethod.invoke(umsInstance, uid, false, null, null);
                                } else {
                                    // Older: (int, boolean)
                                    sSetQuietModeMethod.invoke(umsInstance, uid, false);
                                }
                                XposedBridge.log(TAG + ": quietMode disabled for user " + uid);
                            } else {
                                XposedBridge.log(TAG + ": UMS getInstance not found for user " + uid);
                            }
                        } catch (Exception qe) {
                            XposedBridge.log(TAG + ": quietMode disable skipped for " + uid + ": " + qe.getMessage());
                        }
                    }

                    int paramCount = sStartUserMethod.getParameterCount();
                    if (paramCount == 2 && sStartUserMethod.getParameterTypes()[1] == int.class) {
                        // startUser(int, int startMode)
                        // UserManager.USER_START_MODE_BACKGROUND = 2
                        sStartUserMethod.invoke(ucInstance, uid, 2);
                    } else {
                        // startUser(int, boolean) — background = true
                        sStartUserMethod.invoke(ucInstance, uid, true);
                    }
                    XposedBridge.log(TAG + ": startUser(" + uid + ") OK");
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    XposedBridge.log(TAG + ": startUser(" + uid + ") ITE cause: "
                            + (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "null"));
                } catch (Exception e) {
                    XposedBridge.log(TAG + ": startUser(" + uid + ") failed: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            armSuppressor();
        }, "PSMod-StartClones").start();
    }

    // =========================================================================
    // PART 5: Icon reorder fix
    // =========================================================================

    private void hookIconReorderFix(XC_LoadPackage.LoadPackageParam lpparam) {
        // Primary targets from logcat: ActivityAllAppsContainerView.onAppsUpdated
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
                                        armSuppressor(); // slide the window
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

        // Belt-and-suspenders: AlphabeticalAppsList
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

        // Arm suppressor when LauncherModel sees user 10 profile change
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

    /**
     * Arms the sort suppressor for SUPPRESS_MS.
     * Lazy-initializes Handler/Runnable on first call (after Looper is ready).
     * Cancels any pending disarm and resets the countdown.
     */
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

        // Fallback
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
