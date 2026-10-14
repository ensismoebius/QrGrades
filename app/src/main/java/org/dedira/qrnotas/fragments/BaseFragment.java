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

package org.dedira.qrnotas.fragments;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.appbar.MaterialToolbar;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.dialogs.LoadingDialog;
import org.dedira.qrnotas.util.ActivityTransitions;
import org.dedira.qrnotas.util.Database;

/**
 * Shared base for every screen Fragment in the single-Activity Navigation Component shell
 * (see {@link org.dedira.qrnotas.activities.Main}, the host). Centralizes what every converted
 * screen used to repeat as an Activity: wiring the shared-axis transition, creating the
 * {@link Database}/{@link LoadingDialog} helpers, applying edge-to-edge inset padding, and
 * pointing the toolbar's back arrow at the NavController instead of {@code finish()}.
 * <p>
 * Database callbacks that touch views must guard with {@code if (getView() == null) return;} as
 * their first line — a query started just before the Fragment's view is torn down (e.g. the user
 * navigated away while it was in flight) still delivers its result, but there's no view left to
 * update.
 */
public abstract class BaseFragment extends Fragment {

    protected Database database;
    protected LoadingDialog loadingDialog;

    protected BaseFragment(int layoutId) {
        super(layoutId);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityTransitions.setupSharedAxisTransitions(this);
    }

    @Override
    public void onViewCreated(@androidx.annotation.NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        this.database = new Database(requireContext());
        this.loadingDialog = new LoadingDialog(requireContext());
        // No per-fragment edge-to-edge handling here: the host Activity (Main) pads its single
        // root view (which contains both the NavHostFragment and the bottom nav bar) for the
        // system bars once, for every screen — see Main.onCreate.

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        if (toolbar != null) {
            if (showsBackArrow()) {
                toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
                toolbar.setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).popBackStack());
            } else {
                // Several reused layouts (e.g. activity_entity_list.xml) hardcode a back arrow in
                // XML for their original "always pushed" Activity use — clear it here for the
                // bottom-nav tab case, where there's nothing to pop back to.
                toolbar.setNavigationIcon(null);
                toolbar.setNavigationOnClickListener(null);
            }
        }
    }

    /**
     * Whether this screen should show a toolbar back arrow that pops the NavController's back
     * stack. True for every nested/pushed destination; overridden to false by the four top-level
     * bottom-nav destinations, which have nothing to pop back to.
     */
    protected boolean showsBackArrow() {
        return true;
    }
}
