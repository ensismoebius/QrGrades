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

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.util.ActivityTransitions;
import org.dedira.qrnotas.util.EdgeToEdge;

/**
 * Small "menu" screen shown after tapping a discipline in {@link DisciplineList}. It doesn't
 * show any data itself — it's just two big tappable cards that route to the discipline's class
 * groups ({@link ClassGroupList}) or its goals ({@link GoalList}), passing along the discipline's
 * id/name so those screens know which discipline to load.
 */
public class DisciplineDetail extends AppCompatActivity {

    /**
     * Called once by Android when this screen is created. Reads which discipline we're showing
     * from the Intent that launched us, sets up the toolbar/back button, and wires the two menu
     * cards to open the class-groups and goals screens for that discipline.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Plays the shared "enter" animation that matches the "forward" animation played by
        // whichever screen navigated to this one, so the transition feels consistent app-wide.
        ActivityTransitions.enter(this);
        setContentView(R.layout.activity_discipline_detail);
        // Lets this screen's content draw behind the system status/navigation bars while still
        // padding it correctly, for the modern edge-to-edge look.
        EdgeToEdge.apply(this);

        // "disciplineId"/"disciplineName" are put into the Intent by whichever screen started
        // this Activity (see DisciplineAdapter) — this is how data is passed between screens
        // in Android, since each Activity is its own separate component.
        String disciplineId = getIntent().getStringExtra("disciplineId");
        String disciplineName = getIntent().getStringExtra("disciplineName");

        MaterialToolbar toolbar = this.findViewById(R.id.toolbar);
        toolbar.setTitle(disciplineName);
        // Tapping the toolbar's back arrow should behave exactly like pressing the system Back
        // button, so both routes go through the same OnBackPressedDispatcher.
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        // Registers what "back" means on this screen: just close it with the matching reverse
        // transition animation (finishAfterTransition), rather than the default behavior.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishAfterTransition();
            }
        });

        // Tapping the "class groups" card opens ClassGroupList, forwarding which discipline it
        // belongs to so that screen only shows groups for this discipline.
        this.findViewById(R.id.cardGroups).setOnClickListener(v -> {
            Intent intent = new Intent(this, ClassGroupList.class);
            intent.putExtra("disciplineId", disciplineId);
            intent.putExtra("disciplineName", disciplineName);
            startActivity(intent);
        });

        // Tapping the "goals" card opens GoalList the same way, scoped to this discipline.
        this.findViewById(R.id.cardGoals).setOnClickListener(v -> {
            Intent intent = new Intent(this, GoalList.class);
            intent.putExtra("disciplineId", disciplineId);
            intent.putExtra("disciplineName", disciplineName);
            startActivity(intent);
        });
    }
}
