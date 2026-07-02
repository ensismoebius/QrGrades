package org.dedira.qrnotas.activities;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.budiyev.android.codescanner.CodeScanner;
import com.budiyev.android.codescanner.CodeScannerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.dialogs.LoadingDialog;
import org.dedira.qrnotas.dialogs.NoteDialog;
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Enrollment;
import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.util.ActivityTransitions;
import org.dedira.qrnotas.util.BitmapConverter;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.EdgeToEdge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class Main extends AppCompatActivity {
    private LoadingDialog loadingDialog;
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
    private View txtDisciplineWarning;
    private FloatingActionButton btnOptions;
    private ExtendedFloatingActionButton btnAddStudent;
    private ExtendedFloatingActionButton btnListStudent;
    private ExtendedFloatingActionButton btnDisciplines;
    private ExtendedFloatingActionButton btnBackups;
    private ExtendedFloatingActionButton btnWebServer;
    private Student student;
    private Enrollment currentEnrollment;
    private List<Discipline> disciplines = new ArrayList<>();
    private String currentDisciplineId;
    private Integer extraPoints = 1;
    private boolean isExpanded = false;
    private boolean scannerStarted = false;
    private Database database;
    private MediaPlayer mediaPlayer;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyShowOverLockScreen();
        ActivityTransitions.forward(this);
        setContentView(R.layout.activity_main);
        EdgeToEdge.apply(this);

        /********************************************/
        /********** Create media player *************/
        /********************************************/
        mediaPlayer = MediaPlayer.create(this, R.raw.qr_scanned);

        /********************************************/
        /************** Text objects ****************/
        /********************************************/
        this.txtName = this.findViewById(R.id.txtName);
        this.imgPhoto = this.findViewById(R.id.imgPhoto);
        this.txtPoints = this.findViewById(R.id.txtPoints);
        this.txtCurrentPoints = this.findViewById(R.id.txtCurrentPoints);
        this.goalsContainer = this.findViewById(R.id.goalsContainer);
        this.addPointsOverlay = this.findViewById(R.id.addPointsOverlay);
        this.cameraPermissionOverlay = this.findViewById(R.id.cameraPermissionOverlay);
        this.dropdownDiscipline = this.findViewById(R.id.dropdownDiscipline);
        this.txtDisciplineWarning = this.findViewById(R.id.txtDisciplineWarning);
        this.loadingDialog = new LoadingDialog(this);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (addPointsOverlay.getVisibility() == View.VISIBLE) {
                    resetToStart();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });

        this.cameraPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) showScannerGranted();
            else cameraPermissionOverlay.setVisibility(View.VISIBLE);
        });

        this.findViewById(R.id.btnGrantCameraPermission).setOnClickListener(v ->
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA));

        /****************************/
        /******* Database ***********/
        /****************************/
        this.database = new Database(this);

        /******************************************************/
        /**************** Buttons and actions *****************/
        /******************************************************/
        FloatingActionButton btnPlus = this.findViewById(R.id.btnPlus);
        btnPlus.setOnClickListener(this::onClick);

        FloatingActionButton btnLess = this.findViewById(R.id.btnLess);
        btnLess.setOnClickListener(v -> {
            if (this.extraPoints <= -5) return;
            this.extraPoints--;
            updateExtraPointsLabel();
            bounce(this.txtPoints);
        });

        this.btnAddStudent = this.findViewById(R.id.btnAddStudent);
        this.btnListStudent = this.findViewById(R.id.btnListStudents);
        this.btnDisciplines = this.findViewById(R.id.btnDisciplines);
        this.btnBackups = this.findViewById(R.id.btnBackups);
        this.btnWebServer = this.findViewById(R.id.btnWebServer);

        btnAddStudent.setOnClickListener(v -> {
            Intent intent = new Intent(Main.this, AddOrEditStudent.class);
            startActivity(intent);
        });

        btnListStudent.setOnClickListener(v -> {
            Intent intent = new Intent(Main.this, ListStudents.class);
            startActivity(intent);
        });

        btnDisciplines.setOnClickListener(v -> {
            Intent intent = new Intent(Main.this, DisciplineList.class);
            startActivity(intent);
        });

        btnBackups.setOnClickListener(v -> {
            Intent intent = new Intent(Main.this, BackupList.class);
            startActivity(intent);
        });

        btnWebServer.setOnClickListener(v -> {
            Intent intent = new Intent(Main.this, WebServerActivity.class);
            startActivity(intent);
        });

        this.btnContinue = this.findViewById(R.id.btnContinue);
        this.btnContinue.setOnClickListener(v -> onContinueClick());

        updateExtraPointsLabel();

        this.btnOptions = this.findViewById(R.id.btnOptions);
        btnOptions.setOnClickListener(v -> {
            boolean expanding = !isExpanded;
            toggleOptionsMenu(expanding, btnOptions, btnAddStudent, btnListStudent, btnDisciplines, btnBackups, btnWebServer);
            isExpanded = expanding;
        });

        loadDisciplines();

        /******************************************************/
        /****************** Code scanner events ***************/
        /******************************************************/
        CodeScannerView scannerView = findViewById(R.id.scnView);
        scannerView.setOnClickListener(view -> requestCameraAndStartScanning());
        this.mCodeScanner = new CodeScanner(this, scannerView);
        this.mCodeScanner.setDecodeCallback(result -> runOnUiThread(() -> onQrScanned(result.getText())));
    }

    /**
     * Lets the scanner render above the lock screen without dismissing it (same trick camera
     * quick-launch apps use), so a teacher can jump straight to scanning from the Quick Settings
     * tile ({@link org.dedira.qrnotas.services.QrScanTileService}) without entering their PIN.
     */
    @SuppressWarnings("deprecation") // FLAG_SHOW_WHEN_LOCKED/FLAG_TURN_SCREEN_ON: only path below API 27, where setShowWhenLocked/setTurnScreenOn don't exist yet
    private void applyShowOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /* ------------------------------ Discipline selector ------------------------------ */

    /**
     * Deliberately never auto-selects a discipline (not even a previously used one) — the teacher
     * must actively pick one each time the screen opens, so a scan can never be recorded against
     * the wrong discipline by default.
     */
    private void loadDisciplines() {
        this.database.loadAllDisciplines((success, results) -> {
            this.disciplines = results != null ? results : new ArrayList<>();

            List<String> names = new ArrayList<>();
            for (Discipline d : this.disciplines) names.add(d.name);
            dropdownDiscipline.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));

            dropdownDiscipline.setOnItemClickListener((parent, view, position, id) ->
                    selectDiscipline(this.disciplines.get(position)));
        });
    }

    private void selectDiscipline(Discipline discipline) {
        this.currentDisciplineId = discipline.id;
        this.dropdownDiscipline.setText(discipline.name, false);
        this.txtDisciplineWarning.setVisibility(View.GONE);
    }

    /* --------------------------------- QR scanning ------------------------------------ */

    private void onQrScanned(String studentId) {
        if (this.currentDisciplineId == null) {
            this.txtDisciplineWarning.setVisibility(View.VISIBLE);
            int message = this.disciplines.isEmpty() ? R.string.no_disciplines_for_scan : R.string.select_discipline_before_scan;
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }

        mediaPlayer.start();

        this.loadingDialog.show();
        this.loadingDialog.setCancelable(false);
        this.loadingDialog.setCanceledOnTouchOutside(false);

        this.database.loadStudent(studentId, (success, object) -> {
            if (!success) {
                this.loadingDialog.dismiss();
                Toast.makeText(this, R.string.student_not_found, Toast.LENGTH_SHORT).show();
                return;
            }

            final String disciplineId = this.currentDisciplineId;
            this.database.loadEnrollmentForStudentInDiscipline(object.id, disciplineId, (found, enrollment) -> {
                this.loadingDialog.dismiss();

                if (!found) {
                    warnStudentNotInDiscipline(object, disciplineId);
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

        new AlertDialog.Builder(this)
                .setTitle(R.string.not_enrolled_title)
                .setMessage(getString(R.string.student_not_in_discipline, student.name, disciplineName))
                .setPositiveButton(R.string.enroll_action, (dialog, which) -> offerEnrollment(student, disciplineId, finalDisciplineName))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void offerEnrollment(Student student, String disciplineId, String disciplineName) {
        this.loadingDialog.show();
        this.database.loadClassGroupsForDiscipline(disciplineId, (success, groups) -> {
            this.loadingDialog.dismiss();

            if (!success || groups == null || groups.isEmpty()) {
                Toast.makeText(this, R.string.no_class_groups_for_discipline, Toast.LENGTH_LONG).show();
                return;
            }

            if (groups.size() == 1) {
                enrollStudent(student, groups.get(0), disciplineName);
                return;
            }

            String[] names = new String[groups.size()];
            for (int i = 0; i < groups.size(); i++) names[i] = groups.get(i).name;

            new AlertDialog.Builder(this)
                    .setTitle(R.string.choose_class_group_title)
                    .setItems(names, (dialog, which) -> enrollStudent(student, groups.get(which), disciplineName))
                    .show();
        });
    }

    private void enrollStudent(Student student, ClassGroup classGroup, String disciplineName) {
        this.loadingDialog.show();

        Enrollment enrollment = new Enrollment();
        enrollment.studentId = student.id;
        enrollment.classGroupId = classGroup.id;
        enrollment.grades = 0;

        this.database.saveEnrollment(enrollment, (success, saved) -> {
            this.loadingDialog.dismiss();

            if (!success) {
                Toast.makeText(this, R.string.enroll_failed, Toast.LENGTH_SHORT).show();
                return;
            }

            Toast.makeText(this, getString(R.string.enrolled_success, student.name, disciplineName), Toast.LENGTH_SHORT).show();

            this.student = student;
            this.currentEnrollment = saved;
            this.txtName.setText(student.name);
            this.imgPhoto.setImageBitmap(BitmapConverter.loadBitmap(student.photoPath));
            loadProgress(saved);
            revealStudentContent();
        });
    }

    private void requestCameraAndStartScanning() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showScannerGranted();
        } else {
            cameraPermissionOverlay.setVisibility(View.VISIBLE);
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void showScannerGranted() {
        cameraPermissionOverlay.setVisibility(View.GONE);
        mCodeScanner.startPreview();
        scannerStarted = true;
    }

    private void toggleOptionsMenu(boolean expanding, FloatingActionButton btnOptions,
                                    ExtendedFloatingActionButton btnAddStudent, ExtendedFloatingActionButton btnListStudent,
                                    ExtendedFloatingActionButton btnDisciplines, ExtendedFloatingActionButton btnBackups,
                                    ExtendedFloatingActionButton btnWebServer) {
        btnOptions.animate().rotation(expanding ? 135f : 0f).setDuration(200).start();

        if (expanding) {
            animateFabIn(btnWebServer, 160);
            animateFabIn(btnBackups, 120);
            animateFabIn(btnDisciplines, 80);
            animateFabIn(btnAddStudent, 40);
            animateFabIn(btnListStudent, 0);
        } else {
            animateFabOut(btnWebServer, 0);
            animateFabOut(btnBackups, 40);
            animateFabOut(btnDisciplines, 80);
            animateFabOut(btnAddStudent, 120);
            animateFabOut(btnListStudent, 160);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void animateFabIn(View fab, long startDelay) {
        fab.setVisibility(View.VISIBLE);
        fab.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(startDelay)
                .setDuration(200)
                .start();
    }

    private void animateFabOut(View fab, long startDelay) {
        fab.animate()
                .alpha(0f)
                .translationY(dp(24))
                .setStartDelay(startDelay)
                .setDuration(150)
                .withEndAction(() -> fab.setVisibility(View.INVISIBLE))
                .start();
    }

    private void loadProgress(Enrollment enrollment) {
        this.database.loadExportData(Collections.singletonList(enrollment.studentId), (success, results) -> {
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

    private void revealStudentContent() {
        if (addPointsOverlay.getVisibility() == View.VISIBLE) return;

        if (this.isExpanded) {
            toggleOptionsMenu(false, btnOptions, btnAddStudent, btnListStudent, btnDisciplines, btnBackups, btnWebServer);
            this.isExpanded = false;
        }

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
            Toast.makeText(this, R.string.scan_first, Toast.LENGTH_SHORT).show();
            return;
        }

        if (this.extraPoints == 0) {
            Toast.makeText(this, R.string.select_points_first, Toast.LENGTH_SHORT).show();
            return;
        }

        new NoteDialog(this, this.extraPoints, this.student.name, this::onNoteConfirmed).show();
    }

    private void onNoteConfirmed(String note) {
        if (this.student == null || this.currentEnrollment == null) return;

        this.loadingDialog.show();

        final String enrollmentId = this.currentEnrollment.id;
        final int previousGrades = this.currentEnrollment.grades;
        final int delta = this.extraPoints;
        final int newGrades = previousGrades + delta;

        this.database.updateEnrollmentGrades(enrollmentId, newGrades, (success, updatedEnrollment) -> {
            this.loadingDialog.dismiss();

            if (!success) {
                Toast.makeText(this, R.string.points_not_saved, Toast.LENGTH_SHORT).show();
                return;
            }

            this.currentEnrollment = updatedEnrollment;
            this.mediaPlayer.start();

            PointsHistory history = new PointsHistory();
            history.enrollmentId = enrollmentId;
            history.pointsDelta = delta;
            history.note = note;
            history.createdAt = System.currentTimeMillis();
            this.database.savePointsHistory(history, (histSuccess, savedHistory) -> {
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
                    addPointsOverlay.setVisibility(View.GONE);
                    addPointsOverlay.setTranslationY(0f);
                })
                .start();
    }

    private void undoPoints(String enrollmentId, int previousGrades) {
        this.database.updateEnrollmentGrades(enrollmentId, previousGrades, (success, updatedEnrollment) -> {
            if (!success) {
                Toast.makeText(this, R.string.undo_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            if (this.currentEnrollment != null && this.currentEnrollment.id.equals(enrollmentId)) {
                this.currentEnrollment = updatedEnrollment;
            }
            Toast.makeText(this, R.string.undo_done, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestCameraAndStartScanning();
        mediaPlayer = MediaPlayer.create(this, R.raw.qr_scanned);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (scannerStarted) {
            mCodeScanner.stopPreview();
            mCodeScanner.releaseResources();
            scannerStarted = false;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        try {
            if (mediaPlayer != null) {
                mediaPlayer.stop();
                mediaPlayer.release();
            }
        } catch (Exception ignored) {

        }

    }

    private void onClick(View v) {
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
