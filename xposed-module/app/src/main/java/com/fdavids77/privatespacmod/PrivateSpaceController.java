package com.fdavids77.privatespacmod;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * PrivateSpaceController — Manages Private Space unlock and app-picker overlay.
 *
 * Triggered by double-tap gesture from MainHook.
 * Uses UserManager.requestQuietModeEnabled() to unlock user 10 (Private Space).
 * Shows a floating mini app-picker with icons of all Private Space apps.
 *
 * The app-picker is a system overlay window (requires SYSTEM_ALERT_WINDOW from priv-app).
 */
public class PrivateSpaceController {

    private static final String TAG = "PSMod";
    private static final int PRIVATE_SPACE_USER_ID = 10;
    private static final int OVERLAY_DISMISS_DELAY_MS = 10000; // Auto-dismiss after 10s

    private static PrivateSpaceController instance;
    private View overlayView;
    private WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isUnlocking = false;

    public static synchronized PrivateSpaceController getInstance() {
        if (instance == null) {
            instance = new PrivateSpaceController();
        }
        return instance;
    }

    /**
     * Called when double-tap gesture is detected on the home screen.
     * Unlocks Private Space, then shows the app-picker overlay.
     */
    public void onDoubleTap(Context context) {
        if (isUnlocking) {
            XposedBridge.log(TAG + ": Already unlocking, ignoring duplicate tap");
            return;
        }
        isUnlocking = true;

        mainHandler.post(() -> {
            try {
                unlockPrivateSpace(context);
                // Show app picker after a short delay to allow unlock to complete
                mainHandler.postDelayed(() -> {
                    showAppPicker(context);
                    isUnlocking = false;
                }, 1200);
            } catch (Exception e) {
                XposedBridge.log(TAG + ": Error on double-tap: " + e.getMessage());
                isUnlocking = false;
            }
        });
    }

    /**
     * Unlock Private Space (user 10) via UserManager API.
     * Requires MANAGE_USERS + INTERACT_ACROSS_USERS permissions (granted via Magisk priv-app).
     */
    private void unlockPrivateSpace(Context context) {
        try {
            // Start user 10 first
            Runtime.getRuntime().exec("su -c am start-user " + PRIVATE_SPACE_USER_ID);
            Thread.sleep(500);

            UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (userManager == null) {
                XposedBridge.log(TAG + ": UserManager is null");
                return;
            }

            // Use reflection to call UserHandle.of(10)
            Method ofMethod = UserHandle.class.getDeclaredMethod("of", int.class);
            UserHandle privateSpaceUser = (UserHandle) ofMethod.invoke(null, PRIVATE_SPACE_USER_ID);

            // Disable quiet mode = unlock Private Space
            boolean result = userManager.requestQuietModeEnabled(false, privateSpaceUser);
            XposedBridge.log(TAG + ": requestQuietModeEnabled(false) returned: " + result);

        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to unlock Private Space: " + e.getMessage());
        }
    }

    /**
     * Show a mini app-picker overlay with Private Space app icons.
     * Appears as a floating pill at the bottom of the screen.
     */
    private void showAppPicker(Context context) {
        // Dismiss any existing overlay first
        dismissOverlay(context);

        try {
            windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) return;

            // Get Private Space apps
            List<AppInfo> apps = getPrivateSpaceApps(context);
            if (apps.isEmpty()) {
                XposedBridge.log(TAG + ": No Private Space apps found");
                Toast.makeText(context, "No Private Space apps found", Toast.LENGTH_SHORT).show();
                return;
            }

            // Build the overlay UI
            overlayView = buildAppPickerView(context, apps);

            // Window params for system overlay
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            params.y = dpToPx(context, 80); // Above nav bar

            windowManager.addView(overlayView, params);

            // Auto-dismiss after timeout
            mainHandler.postDelayed(() -> dismissOverlay(context), OVERLAY_DISMISS_DELAY_MS);

            XposedBridge.log(TAG + ": App picker shown with " + apps.size() + " apps");

        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to show app picker: " + e.getMessage());
        }
    }

    /**
     * Build the floating app-picker UI.
     * A horizontal scrollable pill with app icons and labels.
     */
    private View buildAppPickerView(Context context, List<AppInfo> apps) {
        // Outer container with rounded background
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dpToPx(context, 16), dpToPx(context, 12),
                dpToPx(context, 16), dpToPx(context, 12));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E6202020")); // Dark semi-transparent
        bg.setCornerRadius(dpToPx(context, 28));
        bg.setStroke(dpToPx(context, 1), Color.parseColor("#40FFFFFF"));
        container.setBackground(bg);
        container.setElevation(dpToPx(context, 12));

        // Header row with title and close button
        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(dpToPx(context, 4), 0, dpToPx(context, 4), dpToPx(context, 8));

        TextView title = new TextView(context);
        title.setText("Private Space");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setAlpha(0.7f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleParams);
        headerRow.addView(title);

        // Close button
        TextView closeBtn = new TextView(context);
        closeBtn.setText("✕");
        closeBtn.setTextColor(Color.parseColor("#80FFFFFF"));
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        closeBtn.setPadding(dpToPx(context, 8), 0, 0, 0);
        closeBtn.setOnClickListener(v -> dismissOverlay(context));
        headerRow.addView(closeBtn);

        container.addView(headerRow);

        // Scrollable app grid
        HorizontalScrollView scrollView = new HorizontalScrollView(context);
        scrollView.setHorizontalScrollBarEnabled(false);

        LinearLayout appRow = new LinearLayout(context);
        appRow.setOrientation(LinearLayout.HORIZONTAL);
        appRow.setGravity(Gravity.CENTER_VERTICAL);

        int iconSizeDp = 52;
        int iconPaddingDp = 10;

        for (AppInfo app : apps) {
            LinearLayout appItem = new LinearLayout(context);
            appItem.setOrientation(LinearLayout.VERTICAL);
            appItem.setGravity(Gravity.CENTER_HORIZONTAL);
            appItem.setPadding(dpToPx(context, iconPaddingDp), dpToPx(context, 4),
                    dpToPx(context, iconPaddingDp), dpToPx(context, 4));

            // App icon
            ImageView icon = new ImageView(context);
            int iconSizePx = dpToPx(context, iconSizeDp);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSizePx, iconSizePx);
            icon.setLayoutParams(iconParams);
            icon.setImageDrawable(app.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);

            // Ripple effect on tap
            TypedValue outValue = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,
                    outValue, true);
            icon.setBackgroundResource(outValue.resourceId);

            appItem.addView(icon);

            // App label
            TextView label = new TextView(context);
            label.setText(app.label);
            label.setTextColor(Color.parseColor("#CCFFFFFF"));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            label.setPadding(0, dpToPx(context, 4), 0, 0);
            int maxLabelWidth = dpToPx(context, iconSizeDp + iconPaddingDp);
            label.setMaxWidth(maxLabelWidth);
            appItem.addView(label);

            // Click handler — launch app in Private Space
            appItem.setOnClickListener(v -> {
                launchPrivateSpaceApp(context, app.packageName, app.launchComponent);
                dismissOverlay(context);
            });

            appRow.addView(appItem);
        }

        scrollView.addView(appRow);
        container.addView(scrollView);

        // Touch outside to dismiss
        container.setOnTouchListener((v, event) -> {
            // The container itself handles taps; outside taps dismiss via FLAG_WATCH_OUTSIDE_TOUCH
            return false;
        });

        return container;
    }

    /**
     * Launch an app within Private Space (user 10).
     */
    private void launchPrivateSpaceApp(Context context, String packageName, ComponentName component) {
        try {
            XposedBridge.log(TAG + ": Launching " + packageName + " in user " + PRIVATE_SPACE_USER_ID);

            // Use su to start activity as user 10
            String cmd = String.format("su -c am start --user %d -n %s/%s",
                    PRIVATE_SPACE_USER_ID,
                    component.getPackageName(),
                    component.getClassName());

            Runtime.getRuntime().exec(cmd);

        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to launch " + packageName + ": " + e.getMessage());
            Toast.makeText(context, "Failed to launch " + packageName, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Get list of apps installed in Private Space.
     * Uses `pm list packages --user 10` via root shell since we can't directly query user 10.
     */
    private List<AppInfo> getPrivateSpaceApps(Context context) {
        List<AppInfo> apps = new ArrayList<>();
        PackageManager pm = context.getPackageManager();

        try {
            // Query packages installed for user 10
            Process process = Runtime.getRuntime().exec("su -c pm list packages --user 10 -3");
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                // Format: "package:com.example.app"
                if (line.startsWith("package:")) {
                    String pkg = line.substring(8).trim();

                    // Skip system-like packages and our own module
                    if (pkg.startsWith("com.android.") || pkg.startsWith("com.google.android.") 
                            || pkg.equals("com.fdavids77.privatespacmod")) {
                        continue;
                    }

                    try {
                        // Get app info (icon, label) from user 0 package cache
                        // The APK is shared, only the install is per-user
                        ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                        String label = pm.getApplicationLabel(appInfo).toString();
                        Drawable icon = pm.getApplicationIcon(appInfo);

                        // Get launch intent component
                        Intent launchIntent = pm.getLaunchIntentForPackage(pkg);
                        ComponentName component;
                        if (launchIntent != null && launchIntent.getComponent() != null) {
                            component = launchIntent.getComponent();
                        } else {
                            // Fallback: try common launcher activities
                            component = new ComponentName(pkg, pkg + ".MainActivity");
                        }

                        apps.add(new AppInfo(pkg, label, icon, component));

                    } catch (PackageManager.NameNotFoundException e) {
                        // App only exists in user 10, not user 0
                        // Still add with generic info
                        XposedBridge.log(TAG + ": Package " + pkg + " not in user 0, adding with generic icon");
                        apps.add(new AppInfo(pkg, pkg.substring(pkg.lastIndexOf('.') + 1),
                                context.getDrawable(android.R.drawable.sym_def_app_icon),
                                new ComponentName(pkg, pkg + ".MainActivity")));
                    }
                }
            }

            reader.close();
            process.waitFor();

        } catch (Exception e) {
            XposedBridge.log(TAG + ": Failed to list Private Space packages: " + e.getMessage());
        }

        return apps;
    }

    /**
     * Dismiss the floating overlay.
     */
    private void dismissOverlay(Context context) {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (IllegalArgumentException ignored) {
                // Already removed
            }
            overlayView = null;
        }
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private int dpToPx(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }

    /**
     * Simple data holder for a Private Space app.
     */
    static class AppInfo {
        final String packageName;
        final String label;
        final Drawable icon;
        final ComponentName launchComponent;

        AppInfo(String packageName, String label, Drawable icon, ComponentName launchComponent) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.launchComponent = launchComponent;
        }
    }
}
