package org.dedira.qrnotas.services;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.annotation.RequiresApi;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.activities.Main;

/**
 * Quick Settings tile that jumps straight to the scanner, skipping the unlock + launcher tap.
 * {@link Main} shows itself over the keyguard (see its onCreate), so this works without the
 * teacher entering their PIN.
 */
@RequiresApi(api = Build.VERSION_CODES.N)
public class QrScanTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(Tile.STATE_ACTIVE);
        tile.setLabel(getString(R.string.qr_scan_tile_label));
        tile.updateTile();
    }

    @Override
    @SuppressWarnings("deprecation") // startActivityAndCollapse(Intent): only path below API 34, where the PendingIntent overload doesn't exist yet
    public void onClick() {
        super.onClick();

        Intent intent = new Intent(this, Main.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pendingIntent);
        } else {
            startActivityAndCollapse(intent);
        }
    }
}
