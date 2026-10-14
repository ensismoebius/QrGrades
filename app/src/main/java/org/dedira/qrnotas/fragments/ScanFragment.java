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

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.budiyev.android.codescanner.CodeScanner;
import com.budiyev.android.codescanner.CodeScannerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.dialogs.NoteDialog;
import org.dedira.qrnotas.dialogs.StudentPickerDialog;
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Enrollment;
import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.util.BitmapConverter;
import org.dedira.qrnotas.util.ThemeColors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Bottom-nav "Scan" tab — the home screen, and also the screen the Quick Settings tile jumps
 * straight into. Lets the teacher pick which discipline they're currently teaching, point the
 * camera at a student's QR code (or pick the student manually via {@link #btnPickManually}) to
 * identify them, choose how many points to add/remove, optionally attach a note, and save that
 * change.
 */
public class ScanFragment extends BaseFragment {

    private CodeScanner mCodeScanner;
    private TextView txtName;
    private TextView txtPoints;
    private TextView txtCurrentPoints;
    private LinearLayout goalsContainer;
    private ImageView imgPhoto;
    private View addPointsOverlay;
    private View cameraPermissionOverlay;
    private MaterialButton btnContinue;
    private AutoCompleteTextView dropdownDiscipline;
    private com.google.android.material.textfield.TextInputLayout disciplineInputLayout;
    private Student student;
    private Enrollment currentEnrollment;
    private List<Discipline> disciplines = new ArrayList<>();
    private String currentDisciplineId;
    private static final String STATE_CURRENT_DISCIPLINE_ID = "currentDisciplineId";
    private Integer extraPoints = 1;
    private boolean scannerStarted = false;
    private MediaPlayer mediaPlayer;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    public ScanFragment() {
        super(R.layout.fragment_scan);
    }

    @Override
    protected boolean showsBackArrow() {
        return false;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            this.currentDisciplineId = savedInstanceState.getString(STATE_CURRENT_DISCIPLINE_ID);
        }
        this.cameraPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) showScannerGranted();
            else if (cameraPermissionOverlay != null) cameraPermissionOverlay.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        this.mediaPlayer = MediaPlayer.create(requireContext(), R.raw.qr_scanned);

        this.txtName = view.findViewById(R.id.txtName);
        this.imgPhoto = view.findViewById(R.id.imgPhoto);
        this.txtPoints = view.findViewById(R.id.txtPoints);
        this.txtCurrentPoints = view.findViewById(R.id.txtCurrentPoints);
        this.goalsContainer = view.findViewById(R.id.goalsContainer);
        this.addPointsOverlay = view.findViewById(R.id.addPointsOverlay);
        this.cameraPermissionOverlay = view.findViewById(R.id.cameraPermissionOverlay);
        this.dropdownDiscipline = view.findViewById(R.id.dropdownDiscipline);
        this.dropdownDiscipline.requestFocus();
        this.disciplineInputLayout = view.findViewById(R.id.disciplineInputLayout);

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (addPointsOverlay.getVisibility() == View.VISIBLE) {
                    resetToStart();
                    return;
                }
                setEnabled(false);
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });

        view.findViewById(R.id.btnGrantCameraPermission).setOnClickListener(v ->
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA));

        FloatingActionButton btnPlus = view.findViewById(R.id.btnPlus);
        btnPlus.setOnClickListener(this::onPlusClick);

        FloatingActionButton btnLess = view.findViewById(R.id.btnLess);
        btnLess.setOnClickListener(v -> {
            if (this.extraPoints <= -5) return;
            this.extraPoints--;
            updateExtraPointsLabel();
            bounce(this.txtPoints);
        });

        view.findViewById(R.id.btnPickManually).setOnClickListener(v -> selectStudentManually());

        this.btnContinue = view.findViewById(R.id.btnContinue);
        this.btnContinue.setOnClickListener(v -> onContinueClick());

        updateExtraPointsLabel();
        loadDisciplines();

        CodeScannerView scannerView = view.findViewById(R.id.scnView);
        scannerView.setOnClickListener(v -> requestCameraAndStartScanning());
        this.mCodeScanner = new CodeScanner(requireContext(), scannerView);
        this.mCodeScanner.setDecodeCallback(result -> requireActivity().runOnUiThread(() -> onQrScanned(result.getText())));
    }

    /* ------------------------------ Discipline selector ------------------------------ */

    private void loadDisciplines() {
        database.loadAllDisciplines((success, results) -> {
            if (getView() == null) return;
            this.disciplines = results != null ? results : new ArrayList<>();

            List<String> names = new ArrayList<>();
            for (Discipline d : this.disciplines) names.add(d.name);
            dropdownDiscipline.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, names));

            dropdownDiscipline.setOnItemClickListener((parent, v, position, id) ->
                    selectDiscipline(this.disciplines.get(position)));

            if (this.currentDisciplineId != null) {
                Discipline stillExists = null;
                for (Discipline d : this.disciplines) {
                    if (d.id.equals(this.currentDisciplineId)) { stillExists = d; break; }
                }
                if (stillExists != null) {
                    dropdownDiscipline.setText(stillExists.name, false);
                } else {
                    this.currentDisciplineId = null;
                    dropdownDiscipline.setText("", false);
                }
            }
        });
    }

    private void selectDiscipline(Discipline discipline) {
        this.currentDisciplineId = discipline.id;
        this.dropdownDiscipline.setText(discipline.name, false);
        this.disciplineInputLayout.setError(null);
    }

    /* --------------------------------- QR scanning ------------------------------------ */

    private void onQrScanned(String studentId) {
        if (getView() == null) return;
        mediaPlayer.start();
        identifyStudent(studentId);
    }

    /** Entry point for "Selecionar manualmente". */
    private void selectStudentManually() {
        pickStudentManually();
    }

    private void pickStudentManually() {
        if (this.currentDisciplineId == null) {
            promptDisciplineSelection(this::pickStudentManually);
            return;
        }
        new StudentPickerDialog(requireContext(), this.database, picked -> identifyStudent(picked.id)).show();
    }

    private void identifyStudent(String studentId) {
        warnIfIdentifiedAgainToday(studentId);
        if (this.currentDisciplineId == null) {
            promptDisciplineSelection(() -> loadStudentForPoints(studentId));
            return;
        }
        loadStudentForPoints(studentId);
    }

    private void warnIfIdentifiedAgainToday(String studentId) {
        database.logScanAndCountToday(studentId, (success, count) -> {
            if (getView() == null) return;
            if (success && count != null && count > 1) {
                Toast.makeText(requireContext(), getString(R.string.repeat_scan_warning, count), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void promptDisciplineSelection(Runnable onSelected) {
        if (this.disciplines.isEmpty()) {
            requireDisciplineSelected();
            return;
        }

        String[] names = new String[this.disciplines.size()];
        for (int i = 0; i < this.disciplines.size(); i++) names[i] = this.disciplines.get(i).name;

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.select_discipline_before_scan)
                .setItems(names, (dialog, which) -> {
                    selectDiscipline(this.disciplines.get(which));
                    onSelected.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private boolean requireDisciplineSelected() {
        if (this.currentDisciplineId != null) return true;
        int message = this.disciplines.isEmpty() ? R.string.no_disciplines_for_scan : R.string.select_discipline_before_scan;
        this.disciplineInputLayout.setError(getString(message));
        return false;
    }

    private void loadStudentForPoints(String studentId) {
        loadingDialog.show();
        loadingDialog.setCancelable(false);
        loadingDialog.setCanceledOnTouchOutside(false);

        database.loadStudent(studentId, (success, object) -> {
            if (getView() == null) return;
            if (!success) {
                loadingDialog.dismiss();
                Toast.makeText(requireContext(), R.string.student_not_found, Toast.LENGTH_SHORT).show();
                return;
            }

            final String disciplineId = this.currentDisciplineId;
            database.loadEnrollmentForStudentInDiscipline(object.id, disciplineId, (found, enrollment) -> {
                if (getView() == null) return;
                if (!found) {
                    loadingDialog.dismiss();
                    warnStudentNotInDiscipline(object, disciplineId);
                    return;
                }

                database.loadClassGroup(enrollment.classGroupId, (groupFound, classGroup) -> {
                    if (getView() == null) return;
                    loadingDialog.dismiss();
                    if (groupFound && classGroup.isClosed()) {
                        Toast.makeText(requireContext(), R.string.enrollment_in_closed_class_warning, Toast.LENGTH_LONG).show();
                        return;
                    }

                    this.student = object;
                    this.currentEnrollment = enrollment;
                    this.txtName.setText(object.name);
                    this.imgPhoto.setImageBitmap(BitmapConverter.loadBitmap(object.photoPath));
                    loadProgress(enrollment);
                    revealStudentContent();
                });
            });
        });
    }

    private void warnStudentNotInDiscipline(Student student, String disciplineId) {
        String disciplineName = "";
        for (Discipline d : this.disciplines) {
            if (d.id.equals(disciplineId)) {
                disciplineName = d.name;
                break;
            }
        }
        final String finalDisciplineName = disciplineName;

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.not_enrolled_title)
                .setMessage(getString(R.string.student_not_in_discipline, student.name, disciplineName))
                .setPositiveButton(R.string.enroll_action, (dialog, which) -> offerEnrollment(student, disciplineId, finalDisciplineName))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void offerEnrollment(Student student, String disciplineId, String disciplineName) {
        loadingDialog.show();
        database.loadClassGroupsForDiscipline(disciplineId, (success, groups) -> {
            if (getView() == null) return;
            loadingDialog.dismiss();

            if (!success || groups == null || groups.isEmpty()) {
                Toast.makeText(requireContext(), R.string.no_class_groups_for_discipline, Toast.LENGTH_LONG).show();
                return;
            }

            if (groups.size() == 1) {
                enrollStudent(student, groups.get(0), disciplineName);
                return;
            }

            String[] names = new String[groups.size()];
            for (int i = 0; i < groups.size(); i++) names[i] = groups.get(i).name;

            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.choose_class_group_title)
                    .setItems(names, (dialog, which) -> enrollStudent(student, groups.get(which), disciplineName))
                    .show();
        });
    }

    private void enrollStudent(Student student, ClassGroup classGroup, String disciplineName) {
        loadingDialog.show();

        Enrollment enrollment = new Enrollment();
        enrollment.studentId = student.id;
        enrollment.classGroupId = classGroup.id;
        enrollment.grades = 0;

        database.saveEnrollment(enrollment, (success, saved) -> {
            if (getView() == null) return;
            loadingDialog.dismiss();

            if (!success) {
                Toast.makeText(requireContext(), R.string.enroll_failed, Toast.LENGTH_SHORT).show();
                return;
            }

            Toast.makeText(requireContext(), getString(R.string.enrolled_success, student.name, disciplineName), Toast.LENGTH_SHORT).show();

            this.student = student;
            this.currentEnrollment = saved;
            this.txtName.setText(student.name);
            this.imgPhoto.setImageBitmap(BitmapConverter.loadBitmap(student.photoPath));
            loadProgress(saved);
            revealStudentContent();
        });
    }

    private void requestCameraAndStartScanning() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showScannerGranted();
        } else {
            if (cameraPermissionOverlay != null) cameraPermissionOverlay.setVisibility(View.VISIBLE);
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void showScannerGranted() {
        if (getView() == null) return;
        cameraPermissionOverlay.setVisibility(View.GONE);
        mCodeScanner.startPreview();
        scannerStarted = true;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void loadProgress(Enrollment enrollment) {
        database.loadExportData(Collections.singletonList(enrollment.studentId), (success, results) -> {
            if (getView() == null) return;
            if (!success || results == null) return;
            for (StudentExportData data : results) {
                if (enrollment.id.equals(data.enrollmentId)) {
                    bindProgress(data);
                    return;
                }
            }
        });
    }

    private void bindProgress(StudentExportData data) {
        this.txtCurrentPoints.setText(String.valueOf(data.points));
        this.goalsContainer.removeAllViews();

        if (data.goals == null || data.goals.isEmpty()) return;

        LayoutInflater inflater = LayoutInflater.from(requireContext());
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

    private int fetchThemeColor(int attr) {
        return ThemeColors.resolve(requireContext(), attr);
    }

    private void revealStudentContent() {
        if (addPointsOverlay.getVisibility() == View.VISIBLE) return;

        addPointsOverlay.setAlpha(0f);
        addPointsOverlay.setTranslationY(dp(24));
        addPointsOverlay.setVisibility(View.VISIBLE);
        addPointsOverlay.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .start();
    }

    private void bounce(View view) {
        view.animate().cancel();
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.animate()
                .scaleX(1.3f)
                .scaleY(1.3f)
                .setDuration(90)
                .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(120).start())
                .start();
    }

    private void onContinueClick() {
        if (this.student == null || this.currentEnrollment == null) {
            Toast.makeText(requireContext(), R.string.scan_first, Toast.LENGTH_SHORT).show();
            return;
        }

        if (this.extraPoints == 0) {
            Toast.makeText(requireContext(), R.string.select_points_first, Toast.LENGTH_SHORT).show();
            return;
        }

        new NoteDialog(requireContext(), this.database, this.extraPoints, this.student.name, this::onNoteConfirmed).show();
    }

    private void onNoteConfirmed(String note) {
        if (this.student == null || this.currentEnrollment == null) return;

        loadingDialog.show();

        final String enrollmentId = this.currentEnrollment.id;
        final int previousGrades = this.currentEnrollment.grades;
        final int delta = this.extraPoints;
        final int newGrades = previousGrades + delta;

        database.updateEnrollmentGrades(enrollmentId, newGrades, (success, updatedEnrollment) -> {
            if (getView() == null) return;
            loadingDialog.dismiss();

            if (!success) {
                Toast.makeText(requireContext(), R.string.points_not_saved, Toast.LENGTH_SHORT).show();
                return;
            }

            this.currentEnrollment = updatedEnrollment;
            this.mediaPlayer.start();

            PointsHistory history = new PointsHistory();
            history.enrollmentId = enrollmentId;
            history.pointsDelta = delta;
            history.note = note;
            history.createdAt = System.currentTimeMillis();
            database.savePointsHistory(history, (histSuccess, savedHistory) -> {
            });

            Snackbar.make(this.btnContinue, getString(R.string.points_added, delta), Snackbar.LENGTH_LONG)
                    .setAction(R.string.undo, v -> undoPoints(enrollmentId, previousGrades))
                    .show();

            resetToStart();
        });
    }

    private void resetToStart() {
        this.student = null;
        this.currentEnrollment = null;
        this.extraPoints = 1;
        updateExtraPointsLabel();
        this.goalsContainer.removeAllViews();

        addPointsOverlay.animate()
                .alpha(0f)
                .translationY(dp(24))
                .setDuration(150)
                .withEndAction(() -> {
                    if (getView() == null) return;
                    addPointsOverlay.setVisibility(View.GONE);
                    addPointsOverlay.setTranslationY(0f);
                })
                .start();
    }

    private void undoPoints(String enrollmentId, int previousGrades) {
        database.updateEnrollmentGrades(enrollmentId, previousGrades, (success, updatedEnrollment) -> {
            if (getView() == null) return;
            if (!success) {
                Toast.makeText(requireContext(), R.string.undo_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            if (this.currentEnrollment != null && this.currentEnrollment.id.equals(enrollmentId)) {
                this.currentEnrollment = updatedEnrollment;
            }
            Toast.makeText(requireContext(), R.string.undo_done, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_CURRENT_DISCIPLINE_ID, this.currentDisciplineId);
    }

    @Override
    public void onResume() {
        super.onResume();
        requestCameraAndStartScanning();
        this.mediaPlayer = MediaPlayer.create(requireContext(), R.raw.qr_scanned);
        loadDisciplines();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (scannerStarted) {
            mCodeScanner.stopPreview();
            mCodeScanner.releaseResources();
            scannerStarted = false;
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        try {
            if (mediaPlayer != null) {
                mediaPlayer.stop();
                mediaPlayer.release();
            }
        } catch (Exception ignored) {
        }
    }

    private void onPlusClick(View v) {
        if (this.extraPoints >= 5) return;
        this.extraPoints++;
        updateExtraPointsLabel();
        bounce(this.txtPoints);
    }

    private void updateExtraPointsLabel() {
        this.txtPoints.setText(String.format(Locale.getDefault(), "%+d", this.extraPoints));
        int colorAttr = this.extraPoints < 0
                ? com.google.android.material.R.attr.colorError
                : com.google.android.material.R.attr.colorTertiary;
        this.txtPoints.setTextColor(fetchThemeColor(colorAttr));
    }
}
