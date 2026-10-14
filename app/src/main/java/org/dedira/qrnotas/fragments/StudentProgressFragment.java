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

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.util.BitmapConverter;
import org.dedira.qrnotas.util.ThemeColors;

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
 * {@link StudentListFragment}. Reloads every time the screen becomes visible so it always
 * reflects the latest points, even if they were changed elsewhere while this screen was in the
 * background.
 */
public class StudentProgressFragment extends BaseFragment {

    private String studentId;

    public StudentProgressFragment() {
        super(R.layout.activity_student_progress);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        this.studentId = args != null ? args.getString("studentId") : null;

        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);

        loadAndBind();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadAndBind();
    }

    private void loadAndBind() {
        database.loadExportData(Collections.singletonList(studentId), (success, results) -> {
            if (getView() == null) return;
            if (!success || results == null || results.isEmpty()) return;
            bind(results);
        });
    }

    private void bind(List<StudentExportData> enrollments) {
        View view = getView();
        if (view == null) return;
        StudentExportData first = enrollments.get(0);

        ImageView imgPhoto = view.findViewById(R.id.imgPhoto);
        TextView txtName = view.findViewById(R.id.txtName);
        Bitmap photo = BitmapConverter.loadBitmap(first.photoPath);
        if (photo != null) imgPhoto.setImageBitmap(photo);
        txtName.setText(first.studentName);

        bindDisciplines(enrollments);
        bindHistory(enrollments);
    }

    private void bindDisciplines(List<StudentExportData> enrollments) {
        View view = getView();
        if (view == null) return;
        TextView txtEmpty = view.findViewById(R.id.txtEmpty);
        LinearLayout disciplinesContainer = view.findViewById(R.id.disciplinesContainer);
        disciplinesContainer.removeAllViews();
        txtEmpty.setVisibility(View.GONE);

        if (enrollments.isEmpty()) {
            txtEmpty.setVisibility(View.VISIBLE);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(requireContext());
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
                for (GoalProgress goal : new ArrayList<>(data.goals)) {
                    View row = inflater.inflate(R.layout.item_goal_progress, goalsContainer, false);
                    TextView txtGoalName = row.findViewById(R.id.txtGoalName);
                    TextView txtGoalStatus = row.findViewById(R.id.txtGoalStatus);
                    View imgGoalAchieved = row.findViewById(R.id.imgGoalAchieved);
                    LinearProgressIndicator progress = row.findViewById(R.id.progressGoal);

                    txtGoalName.setText(goal.goalName);

                    int percent = goal.targetPoints > 0
                            ? Math.min(100, Math.round(100f * data.points / goal.targetPoints))
                            : 100;
                    progress.setProgress(percent);

                    imgGoalAchieved.setVisibility(goal.achieved ? View.VISIBLE : View.GONE);
                    if (goal.achieved) {
                        txtGoalStatus.setText(R.string.goal_achieved);
                        txtGoalStatus.setTextColor(fetchThemeColor(R.attr.colorSuccess));
                        progress.setIndicatorColor(fetchThemeColor(R.attr.colorSuccess));
                    } else {
                        txtGoalStatus.setText(getString(R.string.goal_remaining, goal.remaining));
                        progress.setIndicatorColor(fetchThemeColor(com.google.android.material.R.attr.colorTertiary));
                        txtGoalStatus.setTextColor(fetchThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
                    }

                    goalsContainer.addView(row);
                }
            }

            disciplinesContainer.addView(card);
        }
    }

    private void bindHistory(List<StudentExportData> enrollments) {
        View view = getView();
        if (view == null) return;
        TextView txtHistoryEmpty = view.findViewById(R.id.txtHistoryEmpty);
        LinearLayout historyContainer = view.findViewById(R.id.historyContainer);
        historyContainer.removeAllViews();
        txtHistoryEmpty.setVisibility(View.GONE);

        boolean showDisciplineLabel = enrollments.size() > 1;
        List<PointsHistory> allHistory = new ArrayList<>();
        for (StudentExportData data : enrollments) {
            if (data.history != null) allHistory.addAll(data.history);
        }

        if (allHistory.isEmpty()) {
            txtHistoryEmpty.setVisibility(View.VISIBLE);
            return;
        }

        Collections.sort(allHistory, (a, b) -> Long.compare(b.createdAt, a.createdAt));

        SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
        LayoutInflater inflater = LayoutInflater.from(requireContext());
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

    private int fetchThemeColor(int attr) {
        return ThemeColors.resolve(requireContext(), attr);
    }
}
