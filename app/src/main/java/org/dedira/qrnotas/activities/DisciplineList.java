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
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.util.ActivityTransitions;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.adapters.DisciplineAdapter;
import org.dedira.qrnotas.util.EdgeToEdge;

/**
 * Top-level list of every discipline (subject) the teacher manages, reached from the navigation
 * drawer. Lets the teacher add a new discipline via the floating "+" button; tapping a row (via
 * {@link DisciplineAdapter}) opens {@link DisciplineDetail} for that discipline.
 */
public class DisciplineList extends AppCompatActivity {
    private DisciplineAdapter adapter;
    private RecyclerView recyclerView;
    private TextView txtEmpty;
    private Database database;

    /**
     * Called once by Android when this screen is created. Sets up the toolbar/back button, wires
     * the RecyclerView to its adapter, and starts the first data load.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityTransitions.enter(this);
        // Shared "list of X with an add button" layout, reused by DisciplineList/ClassGroupList/GoalList.
        setContentView(R.layout.activity_entity_list);
        EdgeToEdge.apply(this);

        this.database = new Database(this);

        MaterialToolbar toolbar = this.findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.disciplines_title);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishAfterTransition();
            }
        });

        this.txtEmpty = this.findViewById(R.id.txtEmpty);
        this.txtEmpty.setText(R.string.no_disciplines);

        this.adapter = new DisciplineAdapter(this, database);
        this.recyclerView = this.findViewById(R.id.lstEntities);
        this.recyclerView.setAdapter(adapter);

        // Watches the adapter's list for any change so the "empty state" text shows/hides itself
        // automatically instead of needing a manual check after every add/delete.
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

        FloatingActionButton btnAdd = this.findViewById(R.id.btnAddEntity);
        btnAdd.setOnClickListener(v -> adapter.showAddDialog());

        loadDisciplines();
    }

    /**
     * Called by Android every time this screen becomes visible again. Reloading here keeps the
     * list current if a discipline was added/renamed/deleted elsewhere while this screen was in
     * the background.
     */
    @Override
    protected void onResume() {
        super.onResume();
        loadDisciplines();
    }

    /** Asks the database for every discipline and hands the result to the adapter. */
    private void loadDisciplines() {
        database.loadAllDisciplines((success, disciplines) -> {
            if (success) adapter.submitList(disciplines);
        });
    }

    /** Shows the "no disciplines yet" placeholder text and hides the list, or vice versa. */
    private void updateEmptyState() {
        boolean isEmpty = adapter.getItemCount() == 0;
        txtEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }
}
