package org.dedira.qrnotas.activities;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.util.ActivityTransitions;

public class DisciplineDetail extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityTransitions.enter(this);
        setContentView(R.layout.activity_discipline_detail);

        String disciplineId = getIntent().getStringExtra("disciplineId");
        String disciplineName = getIntent().getStringExtra("disciplineName");

        MaterialToolbar toolbar = this.findViewById(R.id.toolbar);
        toolbar.setTitle(disciplineName);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishAfterTransition();
            }
        });

        this.findViewById(R.id.cardGroups).setOnClickListener(v -> {
            Intent intent = new Intent(this, ClassGroupList.class);
            intent.putExtra("disciplineId", disciplineId);
            intent.putExtra("disciplineName", disciplineName);
            startActivity(intent);
        });

        this.findViewById(R.id.cardGoals).setOnClickListener(v -> {
            Intent intent = new Intent(this, GoalList.class);
            intent.putExtra("disciplineId", disciplineId);
            intent.putExtra("disciplineName", disciplineName);
            startActivity(intent);
        });
    }
}
