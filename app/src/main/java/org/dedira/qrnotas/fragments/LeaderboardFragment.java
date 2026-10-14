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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.util.adapters.LeaderboardAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ranks every student enrolled in one discipline by their current points, highest first,
 * reached from the "leaderboard" card on {@link DisciplineDetailFragment}. Read-only.
 */
public class LeaderboardFragment extends BaseFragment {
    private LeaderboardAdapter adapter;
    private RecyclerView recyclerView;
    private TextView txtEmpty;
    private String disciplineId;

    public LeaderboardFragment() {
        super(R.layout.activity_leaderboard);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        this.disciplineId = args != null ? args.getString("disciplineId") : null;
        String disciplineName = args != null ? args.getString("disciplineName") : null;

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setTitle(getString(R.string.leaderboard_title, disciplineName));

        this.txtEmpty = view.findViewById(R.id.txtEmpty);
        this.adapter = new LeaderboardAdapter(requireContext());
        this.recyclerView = view.findViewById(R.id.lstLeaderboard);
        this.recyclerView.setAdapter(adapter);

        loadLeaderboard();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadLeaderboard();
    }

    private void loadLeaderboard() {
        database.loadExportData(null, (success, data) -> {
            if (getView() == null) return;
            if (!success || data == null) return;

            List<StudentExportData> scoped = new ArrayList<>();
            for (StudentExportData row : data) {
                if (disciplineId != null && disciplineId.equals(row.disciplineId)) scoped.add(row);
            }
            Collections.sort(scoped, (a, b) -> b.points - a.points);

            adapter.submitList(scoped);
            updateEmptyState();
        });
    }

    private void updateEmptyState() {
        boolean isEmpty = adapter.getRowCount() == 0;
        txtEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }
}
