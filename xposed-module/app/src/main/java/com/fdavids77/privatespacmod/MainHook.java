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
 * PrivateSpaceMod v2.1 — Unified LSPosed module
 *
 * Single module:
 *   1. Hide "Private" label + lock icon (PSLabelHider v1.15 logic)
 *   2. Double-tap home screen → unlock Private Space directly
 *   3. [NEW] Block UserManagerService.requestQuietModeEnabled() for WA clone
 *      profiles (userIds 11–15) so the OS can never auto-pause them.
 *      Runs in system_server (android process).
 *
 * Target: Pixel 9, Android 15/16/17, Magisk + LSPosed (JingMatrix/Vector)
 * Author: fdavids77
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "PSMod";
    private static final String LAUNCHER_PKG = "com.google.android.apps.nexuslauncher";
    private static final String SYSTEM_SERVER_PKG = "android";

    // WA clone profile user IDs to protect from auto-pause
    private static final int WA_USER_MIN = 11;
    private static final int WA_USER_MAX = 15;

    private static final int PRIVATE_SPACE_USER_ID = 10;
    private static boolean isUnlocking = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

        // ── system_server: block quiet-mode re-enablement for clone users ──
        if (lpparam.packageName.equals(SYSTEM_SERVER_PKG)) {
            hookNoQuietMode(lpparam);
            return;
        }

        // ── Pixel Launcher: label hider + double-tap unlock ──
        if (lpparam.packageName.equals(LAUNCHER_PKG)) {
            XposedBridge.log(TAG + ": Hooking Pixel Launcher");
            hookLabelHider(lpparam);
            hookDoubleTapGesture(lpparam);
        }
    }

    // =========================================================================
    // PART 3 (NEW): No-Quiet-Mode hook — system_server / UserManagerService
    // =========================================================================

    /**
     * Hook UserManagerService.requestQuietModeEnabled() running inside
     * system_server.  If the OS tries to enable quiet mode (pause) on any of
     * the WA clone profiles (users 11-15) we simply return false and skip the
     * real method — the profiles stay running.
     *
     * Method signature on Android 13-17:
     *   boolean requestQuietModeEnabled(String callingPackage, boolean enableQuietMode,
     *                                   int userId, IntentSender target, int flags)
     */
    private void hookNoQuietMode(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> ums = XposedHelpers.findClass(
                    "com.android.server.pm.UserManagerService",
                    lpparam.classLoader);

            // Hook the 5-arg form (present on Android 13+)
            XposedHelpers.findAndHookMethod(
                    ums,
                    "requestQuietModeEnabled",
                    String.class,                        // callingPackage
                    boolean.class,                       // enableQuietMode
                    int.class,                           // userId
                    android.content.IntentSender.class,  // target
                    int.class,                           // flags
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean enable = (boolean) param.args[1];
                            int userId   = (int)    param.args[2];

                            if (enable && userId >= WA_USER_MIN && userId <= WA_USER_MAX) {
                                XposedBridge.log(TAG + ": BLOCKED requestQuietModeEnabled("
                                        + "enable=true, userId=" + userId + ") from "
                                        + param.args[0]);
                                // Return false → caller sees "request denied"
                                param.setResult(false);
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": NoQuietMode hook installed for userIds "
                    + WA_USER_MIN + "-" + WA_USER_MAX);

        } catch (XposedHelpers.ClassNotFoundError e) {
            XposedBridge.log(TAG + ": UserManagerService class not found: " + e.getMessage());
        } catch (NoSuchMethodError e) {
            // Android version may have a different signature — try 4-arg form (Android 10-12)
            tryHookNoQuietModeLegacy(lpparam);
        }
    }

    /**
     * Fallback: 4-arg form used on older Android versions (no IntentSender param).
     *   boolean requestQuietModeEnabled(String callingPackage, boolean enableQuietMode,
     *                                   int userId, int flags)
     */
    private void tryHookNoQuietModeLegacy(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> ums = XposedHelpers.findClass(
                    "com.android.server.pm.UserManagerService",
                    lpparam.classLoader);

            XposedHelpers.findAndHookMethod(
                    ums,
                    "requestQuietModeEnabled",
                    String.class,
                    boolean.class,
                    int.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean enable = (boolean) param.args[1];
                            int userId   = (int)    param.args[2];

                            if (enable && userId >= WA_USER_MIN && userId <= WA_USER_MAX) {
                                XposedBridge.log(TAG + ": BLOCKED (legacy) requestQuietModeEnabled("
                                        + "enable=true, userId=" + userId + ")");
                                param.setResult(false);
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": NoQuietMode hook (legacy 4-arg) installed");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": NoQuietMode legacy hook also failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // PART 1: PSLabelHider — Hide "Private" label + lock icon
    // =========================================================================

    private void hookLabelHider(XC_LoadPackage.LoadPackageParam lpparam) {
        // Hook View.setVisibility() — intercept by resource entry name
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
                        } catch (Resources.NotFoundException ignored) {
                        }
                    }
                }
        );

        // Hook setText — blank the "Private" label text
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

                try {
                    XposedHelpers.findAndHookMethod(clazz, "updateView",
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    hidePrivateSpaceSettingsViews(param.thisObject);
                                }
                            });
                } catch (NoSuchMethodError ignored) {
                }

                try {
                    XposedHelpers.findAndHookMethod(clazz, "bind",
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    hidePrivateSpaceSettingsViews(param.thisObject);
                                }
                            });
                } catch (NoSuchMethodError ignored) {
                }

                XposedBridge.log(TAG + ": Hooked PrivateProfileManager: " + className);
                break;
            } catch (XposedHelpers.ClassNotFoundError ignored) {
            }
        }
    }

    private void hidePrivateSpaceSettingsViews(Object manager) {
        try {
            Object settingsButton = XposedHelpers.getObjectField(manager, "mPrivateSpaceSettingsButton");
            if (settingsButton instanceof View) {
                View btn = (View) settingsButton;
                btn.setVisibility(View.GONE);
                if (btn.getParent() instanceof View) {
                    ((View) btn.getParent()).setVisibility(View.GONE);
                }
            }
        } catch (NoSuchFieldError | ClassCastException e) {
            XposedBridge.log(TAG + ": Could not find mPrivateSpaceSettingsButton: " + e.getMessage());
        }
    }

    // =========================================================================
    // PART 2: Double-Tap → Unlock Private Space (inline, no separate app)
    // =========================================================================

    private void hookDoubleTapGesture(XC_LoadPackage.LoadPackageParam lpparam) {
        // Hook WorkspaceTouchListener.onDoubleTap (confirmed on device via dexdump)
        // Fallback: GestureDetector global hook filtered to workspace classes
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
                                XposedBridge.log(TAG + ": Double-tap intercepted on primary hook");
                                try {
                                    Object launcher = XposedHelpers.getObjectField(
                                            param.thisObject, "mLauncher");
                                    Context ctx = (Context) launcher;
                                    unlockPrivateSpace(ctx);
                                } catch (Exception e) {
                                    XposedBridge.log(TAG + ": Primary hook error: " + e.getMessage());
                                }
                                param.setResult(true);
                            }
                        });
                XposedBridge.log(TAG + ": Hooked onDoubleTap on " + className);
                return;
            } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError ignored) {
            }
        }

        XposedBridge.log(TAG + ": WorkspaceTouchListener hook failed, trying fallback");
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
                            String callerClass = param.thisObject.getClass().getName();
                            if (callerClass.contains("Workspace") || callerClass.contains("DragLayer")) {
                                XposedBridge.log(TAG + ": Fallback double-tap from " + callerClass);
                                try {
                                    // WorkspaceTouchListener is NOT a View —
                                    // get context from mLauncher field
                                    Context ctx = null;
                                    try {
                                        Object launcher = XposedHelpers.getObjectField(
                                                param.thisObject, "mLauncher");
                                        ctx = (Context) launcher;
                                    } catch (NoSuchFieldError e1) {
                                        // Try mActivity or other field names
                                        try {
                                            Object activity = XposedHelpers.getObjectField(
                                                    param.thisObject, "mActivity");
                                            ctx = (Context) activity;
                                        } catch (NoSuchFieldError e2) {
                                            // Last resort: if it IS a View
                                            if (param.thisObject instanceof View) {
                                                ctx = ((View) param.thisObject).getContext();
                                            }
                                        }
                                    }

                                    if (ctx != null) {
                                        unlockPrivateSpace(ctx);
                                    } else {
                                        XposedBridge.log(TAG + ": Could not get context from "
                                                + callerClass);
                                    }
                                } catch (Exception e) {
                                    XposedBridge.log(TAG + ": Fallback error: " + e.getMessage());
                                }
                                param.setResult(true);
                            }
                        }
                    }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": GestureDetector fallback hook failed: " + e.getMessage());
        }
    }

    /**
     * Unlock Private Space directly from within the Pixel Launcher process.
     * Calls requestQuietModeEnabled(false, UserHandle.of(10)) — same logic
     * as the old com.example.privatespaceunlock.MainActivity.
     */
    private void unlockPrivateSpace(Context context) {
        if (isUnlocking) {
            XposedBridge.log(TAG + ": Already unlocking, skipping");
            return;
        }
        isUnlocking = true;

        new Thread(() -> {
            try {
                XposedBridge.log(TAG + ": Starting Private Space unlock");

                // Step 1: Start user 10
                Process p = Runtime.getRuntime().exec("su -c am start-user "
                        + PRIVATE_SPACE_USER_ID);
                p.waitFor();
                XposedBridge.log(TAG + ": am start-user completed");

                Thread.sleep(500);

                // Step 2: requestQuietModeEnabled on main thread
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        UserManager um = (UserManager) context.getSystemService(
                                Context.USER_SERVICE);
                        if (um == null) {
                            XposedBridge.log(TAG + ": UserManager is null");
                            isUnlocking = false;
                            return;
                        }

                        Method ofMethod = UserHandle.class.getDeclaredMethod("of", int.class);
                        UserHandle psUser = (UserHandle) ofMethod.invoke(null, PRIVATE_SPACE_USER_ID);

                        boolean result = um.requestQuietModeEnabled(false, psUser);
                        XposedBridge.log(TAG + ": requestQuietModeEnabled(false) = " + result);

                    } catch (SecurityException se) {
                        XposedBridge.log(TAG + ": SecurityException, trying UnlockActivity: "
                                + se.getMessage());
                        unlockViaActivity(context);
                    } catch (Exception e) {
                        XposedBridge.log(TAG + ": Unlock error: " + e.getMessage());
                        unlockViaActivity(context);
                    } finally {
                        isUnlocking = false;
                    }
                });

            } catch (Exception e) {
                XposedBridge.log(TAG + ": Thread error: " + e.getMessage());
                isUnlocking = false;
            }
        }).start();
    }

    /**
     * Fallback: launch our own UnlockActivity from the priv-app.
     */
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
            XposedBridge.log(TAG + ": UnlockActivity launch failed: " + e.getMessage());
        }
    }
}
