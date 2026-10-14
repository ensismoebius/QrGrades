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
import org.dedira.qrnotas.util.adapters.ClassGroupAdapter;

/**
 * Lists the class groups (e.g. "Morning A", "Evening B") that belong to one discipline, reached
 * by tapping the "class groups" card on {@link DisciplineDetailFragment}. Lets the teacher add a
 * new group via the floating "+" button, and each row (via {@link ClassGroupAdapter}) supports
 * editing/deleting and opening that group's student list.
 */
public class ClassGroupListFragment extends BaseFragment {
    private ClassGroupAdapter adapter;
    private RecyclerView recyclerView;
    private TextView txtEmpty;
    private String disciplineId;

    public ClassGroupListFragment() {
        super(R.layout.activity_entity_list);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        this.disciplineId = args != null ? args.getString("disciplineId") : null;
        String disciplineName = args != null ? args.getString("disciplineName") : null;

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setTitle(getString(R.string.groups_title, disciplineName));

        this.txtEmpty = view.findViewById(R.id.txtEmpty);
        this.txtEmpty.setText(R.string.no_groups);

        this.adapter = new ClassGroupAdapter(requireContext(), database, disciplineId, this::loadGroups);
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

        loadGroups();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadGroups();
    }

    private void loadGroups() {
        database.loadClassGroupsForDiscipline(disciplineId, (success, groups) -> {
            if (getView() == null) return;
            if (success) adapter.submitList(groups);
        });
    }

    private void updateEmptyState() {
        boolean isEmpty = adapter.getItemCount() == 0;
        txtEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }
}
