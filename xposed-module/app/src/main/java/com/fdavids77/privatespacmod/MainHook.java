package com.fdavids77.privatespacmod;

import android.content.res.Resources;
import android.view.View;
import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * PrivateSpaceMod — Unified LSPosed module
 *
 * Hooks into Pixel Launcher (com.google.android.apps.nexuslauncher) to:
 *   1. Hide "Private" label + lock icon (PSLabelHider v1.15 logic)
 *   2. Intercept double-tap home screen gesture → trigger Private Space unlock
 *   3. Show a mini app-picker overlay after unlock
 *
 * Target: Pixel 9 Pro XL, Android 15/16, Magisk + LSPosed (JingMatrix/Vector)
 * Author: fdavids77
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "PSMod";
    private static final String LAUNCHER_PKG = "com.google.android.apps.nexuslauncher";
    private static final String SYSTEMUI_PKG = "com.android.systemui";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(LAUNCHER_PKG)) {
            XposedBridge.log(TAG + ": Hooking Pixel Launcher");
            hookLabelHider(lpparam);
            hookDoubleTapGesture(lpparam);
        } else if (lpparam.packageName.equals(SYSTEMUI_PKG)) {
            XposedBridge.log(TAG + ": Hooking SystemUI for QS tile backup");
            // QS tile hooks handled by the priv-app TileService, no LSPosed hook needed
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
        // Try multiple class names across Android 15/16 Pixel Launcher versions
        String[] classNames = {
                "com.android.launcher3.model.data.PrivateProfileManager",
                "com.android.launcher3.pm.PrivateProfileManager",
                "com.google.android.apps.nexuslauncher.privateprofile.PrivateProfileManager"
        };

        for (String className : classNames) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, lpparam.classLoader);

                // Hook updateView — force hide lock-related views
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

                // Hook bind — same treatment
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
                break; // Found the right class
            } catch (XposedHelpers.ClassNotFoundError ignored) {
            }
        }
    }

    private void hidePrivateSpaceSettingsViews(Object manager) {
        try {
            // Navigate from mPrivateSpaceSettingsButton to parent container
            Object settingsButton = XposedHelpers.getObjectField(manager, "mPrivateSpaceSettingsButton");
            if (settingsButton instanceof View) {
                View btn = (View) settingsButton;
                btn.setVisibility(View.GONE);

                // Walk up to hide the parent group too
                if (btn.getParent() instanceof View) {
                    View parent = (View) btn.getParent();
                    parent.setVisibility(View.GONE);
                }
            }
        } catch (NoSuchFieldError | ClassCastException e) {
            XposedBridge.log(TAG + ": Could not find mPrivateSpaceSettingsButton: " + e.getMessage());
        }
    }

    // =========================================================================
    // PART 2: Double-Tap Gesture → Private Space Unlock + App Picker
    // =========================================================================

    private void hookDoubleTapGesture(XC_LoadPackage.LoadPackageParam lpparam) {
        // Pixel Launcher uses Workspace or DragLayer for gesture handling.
        // The double-tap-to-sleep gesture is handled via a GestureDetector callback.
        // We hook the launcher's onDoubleTap handler and redirect it.

        // Strategy: Hook GestureDetector.OnDoubleTapListener implementations
        // within the launcher's Workspace class.

        // Approach 1: Hook the Workspace class's gesture handler
        hookWorkspaceDoubleTap(lpparam);

        // Approach 2: Hook the launcher Activity's dispatchTouchEvent as fallback
        hookLauncherDoubleTap(lpparam);
    }

    private void hookWorkspaceDoubleTap(XC_LoadPackage.LoadPackageParam lpparam) {
        // Pixel Launcher's Workspace extends from AOSP launcher3 Workspace
        String[] workspaceClasses = {
                "com.android.launcher3.Workspace",
                "com.google.android.apps.nexuslauncher.NexusWorkspace"
        };

        for (String className : workspaceClasses) {
            try {
                Class<?> workspaceClass = XposedHelpers.findClass(className, lpparam.classLoader);

                // Look for the performDoubleTap or onDoubleTap method
                try {
                    XposedHelpers.findAndHookMethod(workspaceClass, "performDoubleTap",
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    XposedBridge.log(TAG + ": Double-tap intercepted on Workspace");
                                    View workspace = (View) param.thisObject;
                                    PrivateSpaceController.getInstance().onDoubleTap(workspace.getContext());
                                    param.setResult(null); // Consume the event
                                }
                            });
                    XposedBridge.log(TAG + ": Hooked performDoubleTap on " + className);
                    return;
                } catch (NoSuchMethodError ignored) {
                }

                break;
            } catch (XposedHelpers.ClassNotFoundError ignored) {
            }
        }
    }

    private void hookLauncherDoubleTap(XC_LoadPackage.LoadPackageParam lpparam) {
        // Fallback: Hook the DoubleTapToSleep / DoubleTapAction handler if present
        // Pixel Launcher uses com.android.launcher3.touch.WorkspaceTouchListener
        String[] touchListenerClasses = {
                "com.android.launcher3.touch.WorkspaceTouchListener",
                "com.google.android.apps.nexuslauncher.touch.WorkspaceTouchListener"
        };

        for (String className : touchListenerClasses) {
            try {
                Class<?> listenerClass = XposedHelpers.findClass(className, lpparam.classLoader);

                // Hook onDoubleTap from GestureDetector.OnDoubleTapListener
                try {
                    XposedHelpers.findAndHookMethod(listenerClass, "onDoubleTap",
                            android.view.MotionEvent.class,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    XposedBridge.log(TAG + ": Double-tap intercepted on TouchListener");
                                    // Get context from the listener's launcher reference
                                    try {
                                        Object launcher = XposedHelpers.getObjectField(param.thisObject, "mLauncher");
                                        if (launcher instanceof android.content.Context) {
                                            PrivateSpaceController.getInstance()
                                                    .onDoubleTap((android.content.Context) launcher);
                                        } else {
                                            // Try mActivity or similar
                                            Object activity = XposedHelpers.callMethod(launcher, "getApplicationContext");
                                            if (activity instanceof android.content.Context) {
                                                PrivateSpaceController.getInstance()
                                                        .onDoubleTap((android.content.Context) activity);
                                            }
                                        }
                                    } catch (Exception e) {
                                        XposedBridge.log(TAG + ": Could not get launcher context: " + e.getMessage());
                                    }
                                    param.setResult(true); // Consume the event
                                }
                            });
                    XposedBridge.log(TAG + ": Hooked onDoubleTap on " + className);
                    return;
                } catch (NoSuchMethodError ignored) {
                }

            } catch (XposedHelpers.ClassNotFoundError ignored) {
            }
        }

        // Final fallback: hook the GestureDetector.SimpleOnGestureListener onDoubleTap
        // within any launcher-package class
        XposedBridge.log(TAG + ": Using GestureDetector global hook fallback");
        hookGestureDetectorFallback(lpparam);
    }

    private void hookGestureDetectorFallback(XC_LoadPackage.LoadPackageParam lpparam) {
        // Hook GestureDetector to intercept double-taps at the framework level
        // but only within the Pixel Launcher process
        try {
            XposedHelpers.findAndHookMethod(
                    "android.view.GestureDetector$SimpleOnGestureListener",
                    lpparam.classLoader,
                    "onDoubleTap",
                    android.view.MotionEvent.class,
                    new XC_MethodHook() {
                        private boolean isLauncherGesture = false;

                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Only intercept if we're in the launcher's Workspace context
                            String callerClass = param.thisObject.getClass().getName();
                            if (callerClass.contains("Workspace") || callerClass.contains("workspace")
                                    || callerClass.contains("DragLayer") || callerClass.contains("Launcher")) {
                                isLauncherGesture = true;
                                XposedBridge.log(TAG + ": GestureDetector double-tap from " + callerClass);

                                android.view.MotionEvent event = (android.view.MotionEvent) param.args[0];
                                try {
                                    // Try getting context from the view hierarchy
                                    if (param.thisObject instanceof View) {
                                        PrivateSpaceController.getInstance()
                                                .onDoubleTap(((View) param.thisObject).getContext());
                                    }
                                } catch (Exception e) {
                                    XposedBridge.log(TAG + ": Fallback context retrieval failed: " + e.getMessage());
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
}
