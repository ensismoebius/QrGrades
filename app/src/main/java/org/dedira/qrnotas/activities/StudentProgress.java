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

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.util.ActivityTransitions;
import org.dedira.qrnotas.util.BitmapConverter;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.EdgeToEdge;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Read-only "report card" screen for one student: their photo/name, one card per discipline
 * they're enrolled in showing current points and progress toward each goal in that discipline,
 * and a combined points-history log across every discipline. Opened by tapping a student in
 * {@link StudentList}. Reloads every time the screen becomes visible ({@link #onResume}) so it
 * always reflects the latest points, even if they were changed elsewhere while this screen was
 * in the background.
 */
public class StudentProgress extends AppCompatActivity {

    private String studentId;
    private Database database;

    /**
     * Called once by Android when this screen is created. Reads which student to show from the
     * launching Intent, sets up the toolbar/back button, and triggers the first data load.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityTransitions.enter(this);
        setContentView(R.layout.activity_student_progress);
        EdgeToEdge.apply(this);

        // "studentId" is passed in by whichever screen opened this one (see StudentAdapter).
        studentId = getIntent().getStringExtra("studentId");
        database = new Database(this);

        MaterialToolbar toolbar = this.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishAfterTransition();
            }
        });

        loadAndBind();
    }

    /**
     * Called by Android every time this screen becomes visible again (including the first time,
     * right after onCreate, and again after returning from another screen). Reloading here — not
     * just in onCreate — is what keeps the points/goals/history shown up to date if the teacher
     * awarded points elsewhere and then navigated back to this screen.
     */
    @Override
    protected void onResume() {
        super.onResume();
        loadAndBind();
    }

    /**
     * Asks the database for every enrollment (one row per discipline the student is in) plus
     * their goal progress and points history, then updates the UI once that data comes back.
     * Runs asynchronously off the main thread inside Database; the callback here is posted back
     * to the main thread, so it's safe to touch views directly.
     */
    private void loadAndBind() {
        database.loadExportData(Collections.singletonList(studentId), (success, results) -> {
            if (!success || results == null || results.isEmpty()) return;
            bind(results);
        });
    }

    /** Fills in the student's photo/name, then delegates to the discipline-cards and history-list sections. */
    private void bind(List<StudentExportData> enrollments) {
        // Every entry in the list is for the same student (different disciplines), so any one of
        // them has the shared photo/name fields.
        StudentExportData first = enrollments.get(0);

        ImageView imgPhoto = this.findViewById(R.id.imgPhoto);
        TextView txtName = this.findViewById(R.id.txtName);
        Bitmap photo = BitmapConverter.loadBitmap(first.photoPath);
        if (photo != null) imgPhoto.setImageBitmap(photo);
        txtName.setText(first.studentName);

        bindDisciplines(enrollments);
        bindHistory(enrollments);
    }

    /**
     * Builds one card per discipline enrollment, each showing the current point total and a
     * progress bar per goal defined for that discipline. Since onResume() can call this again on
     * an already-populated screen, it first clears out any previously-added cards to avoid
     * duplicating them.
     */
    private void bindDisciplines(List<StudentExportData> enrollments) {
        TextView txtEmpty = this.findViewById(R.id.txtEmpty);
        LinearLayout disciplinesContainer = this.findViewById(R.id.disciplinesContainer);
        // Cards are added programmatically below (there's no fixed number of disciplines), so
        // this container must be emptied before repopulating it on every reload.
        disciplinesContainer.removeAllViews();
        txtEmpty.setVisibility(View.GONE);

        if (enrollments.isEmpty()) {
            txtEmpty.setVisibility(View.VISIBLE);
            return;
        }

        // LayoutInflater turns an XML layout file into an actual View object at runtime — used
        // here since each discipline/goal row is built from a small reusable XML template rather
        // than being declared once in the main layout.
        LayoutInflater inflater = LayoutInflater.from(this);
        for (StudentExportData data : enrollments) {
            View card = inflater.inflate(R.layout.item_discipline_progress, disciplinesContainer, false);
            TextView txtDisciplineClass = card.findViewById(R.id.txtDisciplineClass);
            TextView txtPoints = card.findViewById(R.id.txtPoints);
            TextView txtGoalsEmpty = card.findViewById(R.id.txtGoalsEmpty);
            LinearLayout goalsContainer = card.findViewById(R.id.goalsContainer);

            txtDisciplineClass.setText(getString(R.string.discipline_class_format, data.disciplineName, data.classGroupName));
            txtPoints.setText(String.valueOf(data.points));

            if (data.goals == null || data.goals.isEmpty()) {
                txtGoalsEmpty.setVisibility(View.VISIBLE);
            } else {
                // Copy the list before iterating since this loop also builds/attaches new views,
                // and it's safer not to hold a live reference to the original mutable list.
                for (GoalProgress goal : new ArrayList<>(data.goals)) {
                    View row = inflater.inflate(R.layout.item_goal_progress, goalsContainer, false);
                    TextView txtGoalName = row.findViewById(R.id.txtGoalName);
                    TextView txtGoalStatus = row.findViewById(R.id.txtGoalStatus);
                    LinearProgressIndicator progress = row.findViewById(R.id.progressGoal);

                    txtGoalName.setText(goal.goalName);

                    // Progress bar percentage: current points as a fraction of the goal's
                    // target, capped at 100% (points can exceed the target). A zero-point target
                    // is treated as instantly 100% complete to avoid dividing by zero.
                    int percent = goal.targetPoints > 0
                            ? Math.min(100, Math.round(100f * data.points / goal.targetPoints))
                            : 100;
                    progress.setProgress(percent);

                    if (goal.achieved) {
                        txtGoalStatus.setText(R.string.goal_achieved);
                        txtGoalStatus.setTextColor(ContextCompat.getColor(this, R.color.scan_success));
                        progress.setIndicatorColor(ContextCompat.getColor(this, R.color.scan_success));
                    } else {
                        txtGoalStatus.setText(getString(R.string.goal_remaining, goal.remaining));
                        // Uses the current app theme's colors (light/dark aware) rather than a
                        // hardcoded color, so it matches the rest of the UI in both modes.
                        progress.setIndicatorColor(fetchThemeColor(com.google.android.material.R.attr.colorTertiary));
                        txtGoalStatus.setTextColor(fetchThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
                    }

                    goalsContainer.addView(row);
                }
            }

            disciplinesContainer.addView(card);
        }
    }

    /**
     * Builds the combined points-history list across every discipline the student is enrolled
     * in, newest entry first. Like {@link #bindDisciplines}, clears previously-added rows first
     * so repeated reloads (via onResume) don't duplicate them.
     */
    private void bindHistory(List<StudentExportData> enrollments) {
        TextView txtHistoryEmpty = this.findViewById(R.id.txtHistoryEmpty);
        LinearLayout historyContainer = this.findViewById(R.id.historyContainer);
        historyContainer.removeAllViews();
        txtHistoryEmpty.setVisibility(View.GONE);

        // Only bother labeling each history row with its discipline name if the student has more
        // than one discipline — with just one, it would be redundant clutter.
        boolean showDisciplineLabel = enrollments.size() > 1;
        List<PointsHistory> allHistory = new ArrayList<>();
        for (StudentExportData data : enrollments) {
            if (data.history != null) allHistory.addAll(data.history);
        }

        if (allHistory.isEmpty()) {
            txtHistoryEmpty.setVisibility(View.VISIBLE);
            return;
        }

        // Sort newest-first (descending by timestamp) so the most recent activity is at the top.
        Collections.sort(allHistory, (a, b) -> Long.compare(b.createdAt, a.createdAt));

        SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
        LayoutInflater inflater = LayoutInflater.from(this);
        for (PointsHistory h : allHistory) {
            View row = inflater.inflate(R.layout.item_points_history, historyContainer, false);
            TextView txtDiscipline = row.findViewById(R.id.txtHistoryDiscipline);
            TextView txtDate = row.findViewById(R.id.txtHistoryDate);
            TextView txtPoints = row.findViewById(R.id.txtHistoryPoints);
            TextView txtNote = row.findViewById(R.id.txtHistoryNote);

            if (showDisciplineLabel && h.disciplineName != null && !h.disciplineName.isEmpty()) {
                txtDiscipline.setText(h.disciplineName);
                txtDiscipline.setVisibility(View.VISIBLE);
            }

            txtDate.setText(dateFormat.format(new Date(h.createdAt)));
            // "%+d" always shows the sign (+5 or -3), making it obvious at a glance whether
            // points were added or removed.
            txtPoints.setText(String.format(Locale.getDefault(), "%+d", h.pointsDelta));
            txtPoints.setTextColor(h.pointsDelta < 0
                    ? fetchThemeColor(com.google.android.material.R.attr.colorError)
                    : fetchThemeColor(com.google.android.material.R.attr.colorTertiary));

            if (h.note != null && !h.note.trim().isEmpty()) {
                txtNote.setText(h.note);
                txtNote.setVisibility(View.VISIBLE);
            }

            historyContainer.addView(row);
        }
    }

    /**
     * Resolves a Material theme attribute (e.g. "colorTertiary") to its actual color value for
     * the app's current theme (light or dark). Needed because these attributes aren't plain
     * color resources — they only resolve to a real color once looked up against the active
     * theme at runtime.
     */
    private int fetchThemeColor(int attr) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }
}
