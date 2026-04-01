package com.fdavids77.privatespacmod;

import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * PrivateSpaceMod v2.0 — Unified LSPosed module
 *
 * Single module that does everything:
 *   1. Hide "Private" label + lock icon (PSLabelHider v1.15 logic)
 *   2. Double-tap home screen → unlock Private Space directly
 *      (calls requestQuietModeEnabled inline from Pixel Launcher process,
 *       which is foreground default launcher — no separate app needed)
 *
 * Target: Pixel 9 Pro XL, Android 15/16, Magisk + LSPosed (JingMatrix/Vector)
 * Author: fdavids77
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "PSMod";
    private static final String LAUNCHER_PKG = "com.google.android.apps.nexuslauncher";
    private static final int PRIVATE_SPACE_USER_ID = 10;
    private static boolean isUnlocking = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(LAUNCHER_PKG)) return;

        XposedBridge.log(TAG + ": Hooking Pixel Launcher");
        hookLabelHider(lpparam);
        hookDoubleTapGesture(lpparam);
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

        // Hook PrivateProfileManager methods for deep hiding
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
        // Hook WorkspaceTouchListener.onDoubleTap — confirmed via dexdump:
        //   Class: com.android.launcher3.touch.WorkspaceTouchListener
        //   Method: onDoubleTap(MotionEvent) -> boolean, PUBLIC FINAL
        //   Field: mLauncher (type Launcher extends Activity extends Context)
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
                                    Context ctx = (Context) launcher;
                                    unlockPrivateSpace(ctx);
                                } catch (Exception e) {
                                    XposedBridge.log(TAG + ": Double-tap handler error: "
                                            + e.getMessage());
                                }
                                param.setResult(true); // Consume the event
                            }
                        });
                XposedBridge.log(TAG + ": Hooked onDoubleTap on " + className);
                return;
            } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError ignored) {
            }
        }

        // Fallback: GestureDetector global hook filtered to workspace classes
        XposedBridge.log(TAG + ": Primary hook failed, trying GestureDetector fallback");
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
                            if (callerClass.contains("Workspace")
                                    || callerClass.contains("DragLayer")) {
                                XposedBridge.log(TAG + ": Fallback double-tap from "
                                        + callerClass);
                                try {
                                    if (param.thisObject instanceof View) {
                                        unlockPrivateSpace(
                                                ((View) param.thisObject).getContext());
                                    }
                                } catch (Exception e) {
                                    XposedBridge.log(TAG + ": Fallback error: "
                                            + e.getMessage());
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

    /**
     * Unlock Private Space directly from within the Pixel Launcher process.
     *
     * This works because:
     * - We're running inside Pixel Launcher (foreground default launcher)
     * - The PrivateSpaceMod APK is installed as priv-app with MANAGE_USERS
     *   and INTERACT_ACROSS_USERS permissions
     * - requestQuietModeEnabled requires the caller to be foreground default
     *   launcher OR have MANAGE_USERS — we have both
     *
     * Same logic as com.example.privatespaceunlock.MainActivity but inline.
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

                // Step 2: Call requestQuietModeEnabled(false, UserHandle.of(10))
                // Must run on main thread for foreground check
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        UserManager um = (UserManager) context.getSystemService(
                                Context.USER_SERVICE);
                        if (um == null) {
                            XposedBridge.log(TAG + ": UserManager is null");
                            isUnlocking = false;
                            return;
                        }

                        Method ofMethod = UserHandle.class.getDeclaredMethod(
                                "of", int.class);
                        UserHandle psUser = (UserHandle) ofMethod.invoke(
                                null, PRIVATE_SPACE_USER_ID);

                        boolean result = um.requestQuietModeEnabled(false, psUser);
                        XposedBridge.log(TAG + ": requestQuietModeEnabled(false) = "
                                + result);

                    } catch (SecurityException se) {
                        // Fallback: if permission denied from launcher process,
                        // try via su shell command
                        XposedBridge.log(TAG + ": SecurityException, trying su fallback: "
                                + se.getMessage());
                        unlockViaSu(context);
                    } catch (Exception e) {
                        XposedBridge.log(TAG + ": Unlock error: " + e.getMessage());
                        unlockViaSu(context);
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
     * Fallback: launch the unlock via su + am start of our own priv-app activity.
     * The PrivateSpaceMod APK includes a MainActivity that does the unlock.
     */
    private void unlockViaSu(Context context) {
        try {
            XposedBridge.log(TAG + ": Trying su am start fallback");

            // Try launching our own module's activity first
            try {
                android.content.Intent intent = new android.content.Intent();
                intent.setComponent(new android.content.ComponentName(
                        "com.fdavids77.privatespacmod",
                        "com.fdavids77.privatespacmod.UnlockActivity"));
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                XposedBridge.log(TAG + ": Launched UnlockActivity");
                return;
            } catch (Exception e) {
                XposedBridge.log(TAG + ": UnlockActivity not found: " + e.getMessage());
            }

            // Final fallback: use am to broadcast/simulate
            Runtime.getRuntime().exec(new String[]{
                    "su", "-c",
                    "am start-user 10"
            });
            XposedBridge.log(TAG + ": Executed am start-user 10 via su");

        } catch (Exception e) {
            XposedBridge.log(TAG + ": Su fallback failed: " + e.getMessage());
        }
    }
}
