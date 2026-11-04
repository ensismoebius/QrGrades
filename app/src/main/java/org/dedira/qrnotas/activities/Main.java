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

package org.dedira.qrnotas.activities;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.FragmentContainerView;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.util.AutoBackup;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.EdgeToEdge;

import java.util.HashSet;
import java.util.Set;

/**
 * The single host Activity for the whole app (single-Activity + Jetpack Navigation Component
 * architecture). Every screen is a Fragment inside {@code navHostFragment}'s nav graph
 * (res/navigation/nav_graph.xml); this class only owns what's genuinely Activity/Window-scoped:
 * showing over the lock screen for the Quick Settings tile
 * ({@link org.dedira.qrnotas.services.QrScanTileService}), edge-to-edge window setup, and
 * toggling the bottom navigation bar's visibility based on which destination is current.
 */
public class Main extends AppCompatActivity {

    /** The 4 bottom-nav tabs — the bar is shown only while one of these is the current destination. */
    private final Set<Integer> topLevelDestinationIds = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyShowOverLockScreen();
        setContentView(R.layout.activity_main);
        EdgeToEdge.apply(this);

        topLevelDestinationIds.add(R.id.scanFragment);
        topLevelDestinationIds.add(R.id.studentListFragment);
        topLevelDestinationIds.add(R.id.disciplineListFragment);
        topLevelDestinationIds.add(R.id.moreFragment);

        FragmentContainerView navHostView = findViewById(R.id.navHostFragment);
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(navHostView.getId());
        NavController navController = navHostFragment.getNavController();

        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        fixBottomNavHeight(bottomNav);
        NavigationUI.setupWithNavController(bottomNav, navController);

        // Once per launch (not on every rotation): takes the scheduled backup if it is due.
        if (savedInstanceState == null) AutoBackup.runIfDue(this, new Database(this));

        View bottomNavCard = findViewById(R.id.bottomNavCard);
        navController.addOnDestinationChangedListener((controller, destination, arguments) ->
                bottomNavCard.setVisibility(topLevelDestinationIds.contains(destination.getId())
                        ? View.VISIBLE : View.GONE));
    }

    /**
     * Two independent fixes for the bottom nav pill, found by reading BottomNavigationView's own
     * source after its height refused to shrink via any XML attribute:
     * <p>
     * 1. BottomNavigationMenuView (the internal item row) copies its minimumHeight from the outer
     * view's getSuggestedMinimumHeight() inside NavigationBarView's constructor — which runs
     * before BottomNavigationView's own constructor resolves android:minHeight from style/instance
     * XML. That copy is permanently stale, so it has to be set on both views directly, in code,
     * after construction.
     * <p>
     * 2. BottomNavigationView registers its own window-insets listener that pads itself by the
     * system navigation bar's height, so an edge-to-edge app's bar doesn't sit under the gesture
     * area. {@link EdgeToEdge#apply} already reserves that same inset by padding the Activity's
     * root view (mainRoot), which pushes this whole floating pill up clear of the gesture area —
     * so BottomNavigationView's own padding was reserving the same space a second time, inside the
     * pill, below its icons. Replacing its insets listener with a no-op removes that double count.
     */
    private void fixBottomNavHeight(BottomNavigationView bottomNav) {
        int minHeightPx = getResources().getDimensionPixelSize(R.dimen.bottom_nav_min_height);
        bottomNav.setMinimumHeight(minHeightPx);
        if (bottomNav.getChildCount() > 0) {
            bottomNav.getChildAt(0).setMinimumHeight(minHeightPx);
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav, (v, insets) -> insets);
    }

    /**
     * Lets the scanner render above the lock screen without dismissing it (same trick camera
     * quick-launch apps use), so a teacher can jump straight to scanning from the Quick Settings
     * tile ({@link org.dedira.qrnotas.services.QrScanTileService}) without entering their PIN.
     */
    @SuppressWarnings("deprecation") // FLAG_SHOW_WHEN_LOCKED/FLAG_TURN_SCREEN_ON: only path below API 27, where setShowWhenLocked/setTurnScreenOn don't exist yet
    private void applyShowOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }
}
