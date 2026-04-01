package com.fdavids77.privatespacmod;

import android.content.Context;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Quick Settings Tile for Private Space unlock.
 * Backup trigger method — double-tap home screen is primary.
 *
 * Tap: Unlock Private Space + show app picker overlay.
 * Requires MANAGE_USERS + INTERACT_ACROSS_USERS permissions (from Magisk priv-app install).
 */
public class PrivateSpaceTileService extends TileService {

    private static final String TAG = "PSMod";
    private static final int PRIVATE_SPACE_USER_ID = 10;

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();
    }

    @Override
    public void onClick() {
        super.onClick();

        try {
            Log.d(TAG, "QS Tile clicked — unlocking Private Space");

            // Start user 10
            Runtime.getRuntime().exec("su -c am start-user " + PRIVATE_SPACE_USER_ID);
            Thread.sleep(500);

            UserManager userManager = (UserManager) getSystemService(Context.USER_SERVICE);
            if (userManager == null) {
                Log.e(TAG, "UserManager is null");
                return;
            }

            Method ofMethod = UserHandle.class.getDeclaredMethod("of", int.class);
            UserHandle privateSpaceUser = (UserHandle) ofMethod.invoke(null, PRIVATE_SPACE_USER_ID);

            userManager.requestQuietModeEnabled(false, privateSpaceUser);
            Log.d(TAG, "Private Space unlocked via QS tile");

            updateTileState();

            // Launch app picker overlay after unlock
            getMainThreadHandler().postDelayed(() -> {
                PrivateSpaceController.getInstance().onDoubleTap(getApplicationContext());
            }, 800);

        } catch (Exception e) {
            Log.e(TAG, "Error unlocking Private Space via tile", e);
        }
    }

    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel("Private Space");
            tile.setSubtitle("Tap to unlock");
            tile.updateTile();
        }
    }
}
