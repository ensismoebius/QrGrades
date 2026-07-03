/*
 * QrGrades — track student grades/points, scan QR codes to award points, and optionally
 * expose the same data to a browser on the local network.
 * Copyright (C) 2026 André Furlan
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.dedira.qrnotas.services;

import android.annotation.SuppressLint;
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
 * <p>
 * For readers new to Android: a {@link TileService} is a special kind of {@code Service} that
 * lets an app add its own button ("tile") to the system Quick Settings panel (the panel you
 * pull down from the top of the screen alongside Wi-Fi/Bluetooth toggles). Android calls the
 * methods below automatically in response to user interaction with the panel — this class never
 * runs on its own, it just reacts to system callbacks.
 */
@RequiresApi(api = Build.VERSION_CODES.N)
public class QrScanTileService extends TileService {

    /**
     * Called by the system whenever the Quick Settings panel becomes visible and this tile is
     * shown to the user (e.g. every time the user pulls down the notification shade). This is
     * the place to refresh the tile's appearance/label right before it's seen, since the tile
     * doesn't keep updating itself while the panel is closed.
     */
    @Override
    public void onStartListening() {
        super.onStartListening();
        // getQsTile() can return null if the tile isn't currently attached to a panel; guard against that.
        Tile tile = getQsTile();
        if (tile == null) return;
        // STATE_ACTIVE draws the tile as "on"/highlighted, signaling it's ready to be tapped.
        tile.setState(Tile.STATE_ACTIVE);
        tile.setLabel(getString(R.string.qr_scan_tile_label));
        // updateTile() pushes these changes to the actual UI; without it the panel wouldn't refresh.
        tile.updateTile();
    }

    /**
     * Called by the system when the user taps this tile in the Quick Settings panel. Launches
     * the app's Main activity (which opens straight into the QR scanner) and immediately closes
     * ("collapses") the Quick Settings panel so the scanner is visible right away.
     */
    @Override
    @SuppressWarnings("deprecation") // startActivityAndCollapse(Intent): only path below API 34, where the PendingIntent overload doesn't exist yet
    @SuppressLint("StartActivityAndCollapseDeprecated")
    public void onClick() {
        super.onClick();

        Intent intent = new Intent(this, Main.class);
        // NEW_TASK is required because we're launching an Activity from a Service context
        // (there's no existing Activity task to attach to). CLEAR_TOP reuses/clears any
        // existing Main instance on top instead of stacking a duplicate one.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Starting with Android 14 (UPSIDE_DOWN_CAKE), tiles must launch activities via a
            // PendingIntent rather than a raw Intent. FLAG_IMMUTABLE tells the system the
            // receiving app cannot modify this PendingIntent, which is required/recommended
            // for security on modern Android versions.
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pendingIntent);
        } else {
            // On older Android versions the PendingIntent overload doesn't exist yet, so we
            // fall back to the deprecated Intent-based overload (suppressed above).
            startActivityAndCollapse(intent);
        }
    }
}
