package com.fdavids77.privatespacmod;

import android.content.ComponentName;
import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

/**
 * Quick Settings Tile for Private Space unlock.
 * Tapping the tile launches UnlockActivity which handles the unlock.
 */
public class PrivateSpaceTileService extends TileService {

    private static final String TAG = "PSMod";

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();
    }

    @Override
    public void onClick() {
        super.onClick();

        try {
            Log.d(TAG, "QS Tile clicked — launching UnlockActivity");

            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    "com.fdavids77.privatespacmod",
                    "com.fdavids77.privatespacmod.UnlockActivity"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(intent);

            Log.d(TAG, "UnlockActivity launched from QS tile");

        } catch (Exception e) {
            Log.e(TAG, "Error launching from QS tile", e);
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
