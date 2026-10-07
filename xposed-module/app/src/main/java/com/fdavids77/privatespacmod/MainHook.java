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
 * PrivateSpaceMod v2.3 — Unified LSPosed module
 *
 * Behaviour (Samsung Secure Folder model):
 *   Screen off  → Private Space (user 10) auto-locks.
 *                 WA clone users 11-15 are also stopped. No notifications. ✓
 *   Launch PS   → Tap Private Space → user 10 unlocks (OS default).
 *                 WA clone users 11-15 restart automatically. ✓
 *   While open  → WA clones receive notifications normally. ✓
 *   Icon order  → Preserved on every PS unlock — onAppsUpdated suppressed
 *                 in ActivityAllAppsContainerView during the unlock window. ✓
 *
 * Parts:
 *   1. Hide "Private" label + lock icon
 *   2. Double-tap home screen → unlock Private Space
 *   3. system_server: stop clone users 11-15 when PS (user 10) locks
 *   4. system_server: restart clone users 11-15 when PS (user 10) unlocks
 *   5. Launcher: suppress onAppsUpdated in ActivityAllAppsContainerView
 *      for 10 s after PS unlock so icons keep their positions
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

    private static boolean isUnlocking = false;

    // Suppresses onAppsUpdated for SUPPRESS_MS after PS unlocks.
    // Re-armed on each incoming onAppsUpdated call so the window slides
    // forward with each user-start event rather than expiring too early.
    private static volatile boolean sSuppressNextSort = false;
    private static final long SUPPRESS_MS = 10_000; // 10 s covers 5 users starting
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static final Runnable sDisarmRunnable = () -> {
        sSuppressNextSort = false;
        XposedBridge.log(TAG + ": Sort suppressor disarmed");
    };

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
            boolean lockedHooked = false;
            // Try 3-arg form first (Android 14+)
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
                lockedHooked = true;
            } catch (NoSuchMethodError ignored) {}

            if (!lockedHooked) {
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
        // Arm suppressor BEFORE users start — drawer rebuilds fire concurrently
        armSuppressor();

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

    /**
     * The log shows the actual call chain:
     *
     *   ActivityAllAppsContainerView.onAppsUpdated()   ← this is what fires
     *     → AlphabeticalAppsList.onAppsUpdated()
     *       → addAppsWithSections()                    ← this sorts icons
     *
     * We hook ActivityAllAppsContainerView.onAppsUpdated() directly —
     * blocking it prevents the entire sort chain.  Also hook the
     * AllAppsContainerView superclass as a fallback.
     *
     * The suppressor is armed for SUPPRESS_MS.  Every incoming call that
     * is suppressed re-arms the timer (sliding window), so 5 clone users
     * starting sequentially don't expire the window mid-sequence.
     */
    private void hookIconReorderFix(XC_LoadPackage.LoadPackageParam lpparam) {
        // Primary: exact class from the log
        String[] containerClasses = {
                "com.android.launcher3.allapps.ActivityAllAppsContainerView",
                "com.google.android.apps.nexuslauncher.allapps.ActivityAllAppsContainerView",
                "com.android.launcher3.allapps.AllAppsContainerView",
                "com.google.android.apps.nexuslauncher.allapps.AllAppsContainerView",
        };

        boolean hookedContainer = false;
        for (String className : containerClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, lpparam.classLoader);
                try {
                    XposedHelpers.findAndHookMethod(clazz, "onAppsUpdated",
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    if (sSuppressNextSort) {
                                        // Re-arm: slide the window forward
                                        armSuppressor();
                                        XposedBridge.log(TAG + ": Suppressed "
                                                + param.thisObject.getClass().getSimpleName()
                                                + ".onAppsUpdated (PS unlock window)");
                                        param.setResult(null);
                                    }
                                }
                            });
                    XposedBridge.log(TAG + ": Hooked " + className + ".onAppsUpdated");
                    hookedContainer = true;
                } catch (NoSuchMethodError e) {
                    XposedBridge.log(TAG + ": onAppsUpdated not found on " + className);
                }
            } catch (XposedHelpers.ClassNotFoundError ignored) {}
        }

        // Secondary: AlphabeticalAppsList — belt-and-suspenders
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

        if (!hookedContainer) {
            XposedBridge.log(TAG + ": WARNING — no ActivityAllAppsContainerView found; icon sort may still fire");
        }
    }

    /**
     * Arms the suppressor for SUPPRESS_MS.
     * Cancels any pending disarm and resets the countdown — safe to call
     * from any thread because Handler.post is thread-safe.
     */
    private static void armSuppressor() {
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
        hookWorkspaceTouchListenerDoubleTap(lpparam);
    }

    private void hookWorkspaceTouchListenerDoubleTap(XC_LoadPackage.LoadPackageParam lpparam) {
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
                                    XposedBridge.log(TAG + ": Double-tap hook error: " + e.getMessage());
                                }
                                param.setResult(true);
                            }
                        });
                XposedBridge.log(TAG + ": Hooked onDoubleTap on " + className);
                return;
            } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError ignored) {}
        }

        XposedBridge.log(TAG + ": WorkspaceTouchListener not found, trying fallback");
        hookGestureDetectorFallback(lpparam);
    }

    private void hookGestureDetectorFallback(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.view.GestureDetector$SimpleOnGestureListener",
                    lpparam.classLoader,
                    "onDoubleTap",
                    android.view.MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String cls = param.thisObject.getClass().getName();
                            if (cls.contains("Workspace") || cls.contains("DragLayer")) {
                                try {
                                    Context ctx = null;
                                    try {
                                        ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mLauncher");
                                    } catch (NoSuchFieldError e1) {
                                        try {
                                            ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mActivity");
                                        } catch (NoSuchFieldError e2) {
                                            if (param.thisObject instanceof View) {
                                                ctx = ((View) param.thisObject).getContext();
                                            }
                                        }
                                    }
                                    if (ctx != null) unlockPrivateSpace(ctx);
                                } catch (Exception e) {
                                    XposedBridge.log(TAG + ": Fallback error: " + e.getMessage());
                                }
                                param.setResult(true);
                            }
                        }
                    }
            );
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
