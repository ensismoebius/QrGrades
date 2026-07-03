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

package org.dedira.qrnotas.util;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;

/**
 * Draws behind the system status/navigation bars and pads the activity's root content view by
 * the system-bar insets, so nothing is obscured without needing per-screen inset wiring.
 * <p>
 * Background for beginners: modern Android lets an app's content extend under the status bar
 * (top, showing clock/battery) and navigation bar (bottom, showing back/home buttons) for a more
 * immersive look ("edge-to-edge"). The trade-off is that the app itself must make sure its own
 * UI elements (toolbars, buttons, etc.) don't end up hidden underneath those system bars. This
 * class solves that once, centrally, instead of every screen doing its own inset math.
 */
public class EdgeToEdge {

    // Static-only helper class; never meant to be instantiated.
    private EdgeToEdge() {
    }

    /** Call once per activity (typically in onCreate) to enable edge-to-edge drawing and auto-padding. */
    public static void apply(Activity activity) {
        // Tells the Android window that the app's content is responsible for drawing behind the
        // system bars itself, instead of the OS automatically reserving space for them.
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);

        // android.R.id.content is the standard Android id for the root FrameLayout that hosts
        // whatever layout was passed to setContentView(). Its first (and normally only) child is
        // the actual screen layout the app defined in XML.
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return;
        View root = content.getChildAt(0);

        // DrawerLayout ignores its own padding when measuring children (it wants the drawer to
        // draw full-bleed under the status bar), so pad its main content child instead — the
        // first child by DrawerLayout convention — or the toolbar ends up under the status bar.
        if (root instanceof DrawerLayout && ((ViewGroup) root).getChildCount() > 0) {
            root = ((ViewGroup) root).getChildAt(0);
        }

        // Remember whatever padding the view already had in XML/code, so the inset padding we
        // add below is additive rather than overwriting designer-intended spacing.
        int initialLeft = root.getPaddingLeft();
        int initialTop = root.getPaddingTop();
        int initialRight = root.getPaddingRight();
        int initialBottom = root.getPaddingBottom();

        // This listener fires whenever the system tells the view how much space the system bars
        // occupy (e.g. on rotation, or when the on-screen keyboard/navigation bar changes size).
        // We add those insets to the original padding so content is pushed clear of the bars.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(initialLeft + bars.left, initialTop + bars.top,
                    initialRight + bars.right, initialBottom + bars.bottom);
            return insets;
        });
    }
}
