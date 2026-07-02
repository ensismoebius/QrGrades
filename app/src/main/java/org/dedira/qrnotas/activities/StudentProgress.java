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

public class StudentProgress extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityTransitions.enter(this);
        setContentView(R.layout.activity_student_progress);
        EdgeToEdge.apply(this);

        String studentId = getIntent().getStringExtra("studentId");

        MaterialToolbar toolbar = this.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishAfterTransition();
            }
        });

        Database database = new Database(this);
        database.loadExportData(Collections.singletonList(studentId), (success, results) -> {
            if (!success || results == null || results.isEmpty()) return;
            bind(results);
        });
    }

    private void bind(List<StudentExportData> enrollments) {
        StudentExportData first = enrollments.get(0);

        ImageView imgPhoto = this.findViewById(R.id.imgPhoto);
        TextView txtName = this.findViewById(R.id.txtName);
        Bitmap photo = BitmapConverter.loadBitmap(first.photoPath);
        if (photo != null) imgPhoto.setImageBitmap(photo);
        txtName.setText(first.studentName);

        bindDisciplines(enrollments);
        bindHistory(enrollments);
    }

    private void bindDisciplines(List<StudentExportData> enrollments) {
        TextView txtEmpty = this.findViewById(R.id.txtEmpty);
        LinearLayout disciplinesContainer = this.findViewById(R.id.disciplinesContainer);

        if (enrollments.isEmpty()) {
            txtEmpty.setVisibility(View.VISIBLE);
            return;
        }

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
                for (GoalProgress goal : new ArrayList<>(data.goals)) {
                    View row = inflater.inflate(R.layout.item_goal_progress, goalsContainer, false);
                    TextView txtGoalName = row.findViewById(R.id.txtGoalName);
                    TextView txtGoalStatus = row.findViewById(R.id.txtGoalStatus);
                    LinearProgressIndicator progress = row.findViewById(R.id.progressGoal);

                    txtGoalName.setText(goal.goalName);

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
        TextView txtHistoryEmpty = this.findViewById(R.id.txtHistoryEmpty);
        LinearLayout historyContainer = this.findViewById(R.id.historyContainer);

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
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }
}
