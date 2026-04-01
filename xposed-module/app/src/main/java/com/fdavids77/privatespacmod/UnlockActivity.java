package com.fdavids77.privatespacmod;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Method;

/**
 * UnlockActivity — Minimal activity that unlocks Private Space.
 *
 * Same logic as com.example.privatespaceunlock.MainActivity.
 * Lives inside the PrivateSpaceMod priv-app so it has MANAGE_USERS
 * and INTERACT_ACROSS_USERS permissions.
 *
 * Called by:
 *   - MainHook double-tap fallback (if inline unlock hits SecurityException)
 *   - QS tile via PrivateSpaceTileService
 *   - Direct: am start -n com.fdavids77.privatespacmod/.UnlockActivity
 */
public class UnlockActivity extends Activity {
    private static final String TAG = "PSMod";
    private static final int PRIVATE_SPACE_USER_ID = 10;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            Log.d(TAG, "UnlockActivity: Starting Private Space unlock");

            // Step 1: Start user 10
            Runtime.getRuntime().exec("su -c am start-user " + PRIVATE_SPACE_USER_ID);
            Thread.sleep(500);

            // Step 2: Get UserManager
            UserManager userManager = (UserManager) getSystemService(Context.USER_SERVICE);
            if (userManager == null) {
                Log.e(TAG, "UnlockActivity: UserManager is null");
                Toast.makeText(this, "Error: UserManager unavailable", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            Log.d(TAG, "UnlockActivity: Got UserManager");

            // Step 3: Get UserHandle for user 10 via reflection
            Method ofMethod = UserHandle.class.getDeclaredMethod("of", int.class);
            UserHandle privateSpaceUser = (UserHandle) ofMethod.invoke(null, PRIVATE_SPACE_USER_ID);
            Log.d(TAG, "UnlockActivity: Got UserHandle for user " + PRIVATE_SPACE_USER_ID);

            // Step 4: Unlock Private Space
            boolean result = userManager.requestQuietModeEnabled(false, privateSpaceUser);
            Log.d(TAG, "UnlockActivity: requestQuietModeEnabled(false) returned: " + result);

            Toast.makeText(this, "Private Space unlocking...", Toast.LENGTH_SHORT).show();

        } catch (SecurityException se) {
            Log.e(TAG, "UnlockActivity: SecurityException: " + se.getMessage(), se);
            Toast.makeText(this, "Permission error: " + se.getMessage(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "UnlockActivity: Error: " + e.getMessage(), e);
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        finish(); // Close immediately — no UI needed
    }
}
