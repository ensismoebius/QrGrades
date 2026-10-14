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
import org.dedira.qrnotas.util.adapters.DisciplineAdapter;

/**
 * Bottom-nav "Disciplines" tab: top-level list of every discipline (subject) the teacher
 * manages. Lets the teacher add a new discipline via the floating "+" button; tapping a row
 * (via {@link DisciplineAdapter}) opens {@link DisciplineDetailFragment} for that discipline.
 */
public class DisciplineListFragment extends BaseFragment {
    private DisciplineAdapter adapter;
    private RecyclerView recyclerView;
    private TextView txtEmpty;

    public DisciplineListFragment() {
        super(R.layout.activity_entity_list);
    }

    @Override
    protected boolean showsBackArrow() {
        return false;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.disciplines_title);

        this.txtEmpty = view.findViewById(R.id.txtEmpty);
        this.txtEmpty.setText(R.string.no_disciplines);

        this.adapter = new DisciplineAdapter(requireContext(), database);
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

        loadDisciplines();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadDisciplines();
    }

    private void loadDisciplines() {
        database.loadAllDisciplines((success, disciplines) -> {
            if (getView() == null) return;
            if (success) adapter.submitList(disciplines);
        });
    }

    private void updateEmptyState() {
        boolean isEmpty = adapter.getItemCount() == 0;
        txtEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }
}
