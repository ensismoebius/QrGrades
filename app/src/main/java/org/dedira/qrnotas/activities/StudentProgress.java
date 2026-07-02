package org.dedira.qrnotas.activities;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.util.ActivityTransitions;
import org.dedira.qrnotas.util.BitmapConverter;
import org.dedira.qrnotas.util.Database;

import java.util.ArrayList;
import java.util.Collections;

public class StudentProgress extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityTransitions.enter(this);
        setContentView(R.layout.activity_student_progress);

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
            bind(results.get(0));
        });
    }

    private void bind(StudentExportData data) {
        ImageView imgPhoto = this.findViewById(R.id.imgPhoto);
        TextView txtName = this.findViewById(R.id.txtName);
        TextView txtDisciplineClass = this.findViewById(R.id.txtDisciplineClass);
        TextView txtPoints = this.findViewById(R.id.txtPoints);
        TextView txtEmpty = this.findViewById(R.id.txtEmpty);
        android.widget.LinearLayout goalsContainer = this.findViewById(R.id.goalsContainer);

        Bitmap photo = BitmapConverter.loadBitmap(data.photoPath);
        if (photo != null) imgPhoto.setImageBitmap(photo);
        txtName.setText(data.studentName);
        txtDisciplineClass.setText(getString(R.string.discipline_class_format, data.disciplineName, data.classGroupName));
        txtPoints.setText(String.valueOf(data.points));

        if (data.goals == null || data.goals.isEmpty()) {
            txtEmpty.setVisibility(View.VISIBLE);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
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

    private int fetchThemeColor(int attr) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }
}
