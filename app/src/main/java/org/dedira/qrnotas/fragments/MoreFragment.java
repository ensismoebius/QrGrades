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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.navigation.Navigation;

import org.dedira.qrnotas.R;

/**
 * Bottom-nav "More" tab: a small hub (same card-grid pattern as {@link DisciplineDetailFragment})
 * for the two lower-frequency, setup/maintenance destinations that don't deserve their own
 * bottom-nav slot — backups and the local web server.
 */
public class MoreFragment extends BaseFragment {

    public MoreFragment() {
        super(R.layout.fragment_more);
    }

    @Override
    protected boolean showsBackArrow() {
        return false;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.cardBackups).setOnClickListener(v ->
                Navigation.findNavController(view).navigate(R.id.action_more_to_backupList));

        view.findViewById(R.id.cardWebServer).setOnClickListener(v ->
                Navigation.findNavController(view).navigate(R.id.action_more_to_webServer));
    }
}
