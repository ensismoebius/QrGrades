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
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

import com.google.android.material.appbar.MaterialToolbar;

import org.dedira.qrnotas.R;

/**
 * Small "menu" screen shown after tapping a discipline in {@link DisciplineListFragment}. It
 * doesn't show any data itself — it's just tappable cards that route to the discipline's class
 * groups ({@link ClassGroupListFragment}), its goals ({@link GoalListFragment}), its points
 * leaderboard ({@link LeaderboardFragment}), or its closed periods ({@link PeriodListFragment}),
 * passing along the discipline's id/name so those screens know which discipline to load.
 */
public class DisciplineDetailFragment extends BaseFragment {

    public DisciplineDetailFragment() {
        super(R.layout.activity_discipline_detail);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        String disciplineId = args != null ? args.getString("disciplineId") : null;
        String disciplineName = args != null ? args.getString("disciplineName") : null;

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setTitle(disciplineName);

        NavController navController = Navigation.findNavController(view);

        view.findViewById(R.id.cardGroups).setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putString("disciplineId", disciplineId);
            bundle.putString("disciplineName", disciplineName);
            navController.navigate(R.id.action_disciplineDetail_to_classGroupList, bundle);
        });

        view.findViewById(R.id.cardGoals).setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putString("disciplineId", disciplineId);
            bundle.putString("disciplineName", disciplineName);
            navController.navigate(R.id.action_disciplineDetail_to_goalList, bundle);
        });

        view.findViewById(R.id.cardLeaderboard).setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putString("disciplineId", disciplineId);
            bundle.putString("disciplineName", disciplineName);
            navController.navigate(R.id.action_disciplineDetail_to_leaderboard, bundle);
        });

        view.findViewById(R.id.cardPeriods).setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putString("disciplineId", disciplineId);
            bundle.putString("disciplineName", disciplineName);
            navController.navigate(R.id.action_disciplineDetail_to_periodList, bundle);
        });
    }
}
