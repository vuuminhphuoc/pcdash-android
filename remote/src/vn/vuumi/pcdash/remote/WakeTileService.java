package vn.vuumi.pcdash.remote;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * "Wake PC" Quick Settings tile. It only opens the app with a pending Wake; the tile keeps nothing
 * running, and the tailnet still starts and stops with the app as before.
 */
public class WakeTileService extends TileService {
    @Override
    public void onStartListening() {
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.updateTile();
        }
    }

    @Override
    public void onClick() {
        if (isLocked()) {
            unlockAndRun(new Runnable() {
                @Override
                public void run() {
                    openApp();
                }
            });
        } else {
            openApp();
        }
    }

    @SuppressWarnings("deprecation")
    private void openApp() {
        Intent intent = new Intent(QuickActions.ACTION_WAKE);
        intent.setClass(this, QuickActionActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(intent);
        }
    }
}
