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
import org.dedira.qrnotas.util.ClassGroupAdapter;
import org.dedira.qrnotas.util.Database;

public class ClassGroupList extends AppCompatActivity {
    private ClassGroupAdapter adapter;
    private RecyclerView recyclerView;
    private TextView txtEmpty;
    private Database database;
    private String disciplineId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityTransitions.enter(this);
        setContentView(R.layout.activity_entity_list);

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

    @Override
    protected void onResume() {
        super.onResume();
        loadGroups();
    }

    private void loadGroups() {
        database.loadClassGroupsForDiscipline(disciplineId, (success, groups) -> {
            if (success) adapter.submitList(groups);
        });
    }

    private void updateEmptyState() {
        boolean isEmpty = adapter.getItemCount() == 0;
        txtEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }
}
