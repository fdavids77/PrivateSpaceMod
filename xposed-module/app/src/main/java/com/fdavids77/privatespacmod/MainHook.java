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

    private void hookPrivateSpaceLockUnlock(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> ucClass = XposedHelpers.findClass(
                    "com.android.server.am.UserController", lpparam.classLoader);

            // ── LOCK: stop clone users when PS stops ──────────────────────
            boolean lockHooked = false;
            try {
                XposedHelpers.findAndHookMethod(ucClass, "stopSingleUserLU",
                        int.class, boolean.class, boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if ((int) param.args[0] == PRIVATE_SPACE_USER_ID) {
                                    XposedBridge.log(TAG + ": PS user 10 stopped — cascading stop to 11-15");
                                    stopCloneUsers();
                                }
                            }
                        });
                XposedBridge.log(TAG + ": Hooked stopSingleUserLU (3-arg)");
                lockHooked = true;
            } catch (NoSuchMethodError ignored) {}

            if (!lockHooked) {
                try {
                    XposedHelpers.findAndHookMethod(ucClass, "stopSingleUserLU",
                            int.class, boolean.class,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    if ((int) param.args[0] == PRIVATE_SPACE_USER_ID) {
                                        XposedBridge.log(TAG + ": PS user 10 stopped (2-arg) — cascading stop");
                                        stopCloneUsers();
                                    }
                                }
                            });
                    XposedBridge.log(TAG + ": Hooked stopSingleUserLU (2-arg)");
                } catch (NoSuchMethodError e) {
                    XposedBridge.log(TAG + ": stopSingleUserLU not found: " + e.getMessage());
                }
            }

            // ── UNLOCK: restart clone users when PS unlocks ───────────────
            try {
                XposedHelpers.findAndHookMethod(ucClass, "onUserUnlocked",
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if ((int) param.args[0] == PRIVATE_SPACE_USER_ID) {
                                    XposedBridge.log(TAG + ": PS user 10 unlocked — restarting clones 11-15");
                                    startCloneUsers();
                                }
                            }
                        });
                XposedBridge.log(TAG + ": Hooked UserController.onUserUnlocked");
            } catch (NoSuchMethodError e) {
                XposedBridge.log(TAG + ": onUserUnlocked not found: " + e.getMessage());
            }

        } catch (XposedHelpers.ClassNotFoundError e) {
            XposedBridge.log(TAG + ": UserController not found: " + e.getMessage());
        }
    }

    private void stopCloneUsers() {
        new Thread(() -> {
            for (int uid = WA_USER_MIN; uid <= WA_USER_MAX; uid++) {
                try {
                    Process p = Runtime.getRuntime().exec(
                            new String[]{"sh", "-c", "am stop-user -f " + uid});
                    p.waitFor();
                    XposedBridge.log(TAG + ": Stopped clone user " + uid);
                } catch (Exception e) {
                    XposedBridge.log(TAG + ": Failed to stop user " + uid + ": " + e.getMessage());
                }
            }
        }, "PSMod-StopClones").start();
    }

    private void startCloneUsers() {
        new Thread(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            for (int uid = WA_USER_MIN; uid <= WA_USER_MAX; uid++) {
                try {
                    Process p = Runtime.getRuntime().exec(
                            new String[]{"sh", "-c", "am start-user " + uid});
                    p.waitFor();
                    XposedBridge.log(TAG + ": Started clone user " + uid);
                } catch (Exception e) {
                    XposedBridge.log(TAG + ": Failed to start user " + uid + ": " + e.getMessage());
                }
            }
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
