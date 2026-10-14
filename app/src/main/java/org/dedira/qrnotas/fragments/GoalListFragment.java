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
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.util.adapters.GoalAdapter;

/**
 * Lists the point-total goals (e.g. "R: 10 points", "MB: 30 points") defined for one discipline,
 * reached by tapping the "goals" card on {@link DisciplineDetailFragment}. Lets the teacher add
 * a new goal via the floating "+" button; each row (via {@link GoalAdapter}) supports
 * editing/deleting. These goals are what drives the progress bars shown on
 * {@link StudentProgressFragment} and on the scan screen.
 */
public class GoalListFragment extends BaseFragment {
    private GoalAdapter adapter;
    private RecyclerView recyclerView;
    private TextView txtEmpty;
    private String disciplineId;

    public GoalListFragment() {
        super(R.layout.activity_entity_list);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        this.disciplineId = args != null ? args.getString("disciplineId") : null;
        String disciplineName = args != null ? args.getString("disciplineName") : null;

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setTitle(getString(R.string.goals_title, disciplineName));

        this.txtEmpty = view.findViewById(R.id.txtEmpty);
        this.txtEmpty.setText(R.string.no_goals);

        this.adapter = new GoalAdapter(requireContext(), database, disciplineId);
        this.recyclerView = view.findViewById(R.id.lstEntities);
        this.recyclerView.setAdapter(adapter);

        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                updateEmptyState();
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                updateEmptyState();
            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                updateEmptyState();
            }
        });

        FloatingActionButton btnAdd = view.findViewById(R.id.btnAddEntity);
        btnAdd.setOnClickListener(v -> adapter.showAddDialog());

        loadGoals();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadGoals();
    }

    private void loadGoals() {
        database.loadGoalsForDiscipline(disciplineId, (success, goals) -> {
            if (getView() == null) return;
            if (success) adapter.submitList(goals);
        });
    }

    private void updateEmptyState() {
        boolean isEmpty = adapter.getItemCount() == 0;
        txtEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }
}
