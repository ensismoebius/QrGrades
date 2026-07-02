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
 */
public class EdgeToEdge {

    private EdgeToEdge() {
    }

    public static void apply(Activity activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);

        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return;
        View root = content.getChildAt(0);

        // DrawerLayout ignores its own padding when measuring children (it wants the drawer to
        // draw full-bleed under the status bar), so pad its main content child instead — the
        // first child by DrawerLayout convention — or the toolbar ends up under the status bar.
        if (root instanceof DrawerLayout && ((ViewGroup) root).getChildCount() > 0) {
            root = ((ViewGroup) root).getChildAt(0);
        }

        int initialLeft = root.getPaddingLeft();
        int initialTop = root.getPaddingTop();
        int initialRight = root.getPaddingRight();
        int initialBottom = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(initialLeft + bars.left, initialTop + bars.top,
                    initialRight + bars.right, initialBottom + bars.bottom);
            return insets;
        });
    }
}
