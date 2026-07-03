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

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.util.ActivityTransitions;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.EdgeToEdge;
import org.dedira.qrnotas.util.adapters.LeaderboardAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ranks every student enrolled in one discipline by their current points, highest first, reached
 * from the "leaderboard" card on {@link DisciplineDetail}. Mirrors the ranking shown by the LAN
 * web server's overview page, but scoped to a single discipline and read from the on-device
 * database directly rather than an HTTP call. Read-only — no editing happens here.
 */
public class LeaderboardList extends AppCompatActivity {
    private LeaderboardAdapter adapter;
    private RecyclerView recyclerView;
    private TextView txtEmpty;
    private Database database;
    private String disciplineId;

    /**
     * Called once by Android when this screen is created. Reads which discipline to rank, sets
     * up the toolbar/back button, wires the RecyclerView to its adapter, and starts the first
     * data load.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityTransitions.enter(this);
        setContentView(R.layout.activity_leaderboard);
        EdgeToEdge.apply(this);

        this.disciplineId = getIntent().getStringExtra("disciplineId");
        String disciplineName = getIntent().getStringExtra("disciplineName");
        this.database = new Database(this);

        MaterialToolbar toolbar = this.findViewById(R.id.toolbar);
        toolbar.setTitle(getString(R.string.leaderboard_title, disciplineName));
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishAfterTransition();
            }
        });

        this.txtEmpty = this.findViewById(R.id.txtEmpty);
        this.adapter = new LeaderboardAdapter(this);
        this.recyclerView = this.findViewById(R.id.lstLeaderboard);
        this.recyclerView.setAdapter(adapter);

        loadLeaderboard();
    }

    /**
     * Called by Android every time this screen becomes visible again. Reloading here keeps the
     * ranking current if points were awarded elsewhere while this screen was in the background.
     */
    @Override
    protected void onResume() {
        super.onResume();
        loadLeaderboard();
    }

    /**
     * Loads every enrollment (there's no discipline-scoped export query, so this reuses the same
     * "load everything, filter in memory" approach the LAN web server's overview page uses),
     * keeps only rows belonging to this discipline, and sorts them by points, highest first.
     */
    private void loadLeaderboard() {
        database.loadExportData(null, (success, data) -> {
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

    /** Shows the "no data yet" placeholder text and hides the list, or vice versa. */
    private void updateEmptyState() {
        boolean isEmpty = adapter.getRowCount() == 0;
        txtEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }
}
