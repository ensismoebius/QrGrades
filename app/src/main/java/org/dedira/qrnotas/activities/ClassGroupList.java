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
import org.dedira.qrnotas.util.adapters.ClassGroupAdapter;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.EdgeToEdge;

/**
 * Lists the class groups (e.g. "Morning A", "Evening B") that belong to one discipline, reached
 * by tapping the "class groups" card on {@link DisciplineDetail}. Lets the teacher add a new
 * group via the floating "+" button, and each row (via {@link ClassGroupAdapter}) supports
 * editing/deleting and opening that group's student list.
 */
public class ClassGroupList extends AppCompatActivity {
    private ClassGroupAdapter adapter;
    private RecyclerView recyclerView;
    private TextView txtEmpty;
    private Database database;
    private String disciplineId;

    /**
     * Called once by Android when this screen is created. Reads which discipline's groups to
     * show, sets up the toolbar/back button, wires the RecyclerView to its adapter, and starts
     * the first data load.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityTransitions.enter(this);
        // This layout (activity_entity_list.xml) is shared by every simple "list of X with an
        // add button" screen in the app (disciplines, class groups, goals) — only the adapter
        // and data source differ per screen.
        setContentView(R.layout.activity_entity_list);
        EdgeToEdge.apply(this);

        this.disciplineId = getIntent().getStringExtra("disciplineId");
        String disciplineName = getIntent().getStringExtra("disciplineName");
        this.database = new Database(this);

        MaterialToolbar toolbar = this.findViewById(R.id.toolbar);
        toolbar.setTitle(getString(R.string.groups_title, disciplineName));
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishAfterTransition();
            }
        });

        this.txtEmpty = this.findViewById(R.id.txtEmpty);
        this.txtEmpty.setText(R.string.no_groups);

        this.adapter = new ClassGroupAdapter(this, database, disciplineId);
        this.recyclerView = this.findViewById(R.id.lstEntities);
        this.recyclerView.setAdapter(adapter);

        // Watches the adapter's list for any change (items added/removed/reset) so the "empty
        // state" text can be shown/hidden automatically instead of manually after every edit.
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

        loadGroups();
    }

    /**
     * Called by Android every time this screen becomes visible again. Reloading here (not just
     * in onCreate) keeps the list in sync if a group was added/renamed/deleted elsewhere while
     * this screen was in the background.
     */
    @Override
    protected void onResume() {
        super.onResume();
        loadGroups();
    }

    /** Asks the database for this discipline's class groups and hands the result to the adapter. */
    private void loadGroups() {
        database.loadClassGroupsForDiscipline(disciplineId, (success, groups) -> {
            if (success) adapter.submitList(groups);
        });
    }

    /** Shows the "no groups yet" placeholder text and hides the list, or vice versa, based on whether the list has any rows. */
    private void updateEmptyState() {
        boolean isEmpty = adapter.getItemCount() == 0;
        txtEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }
}
