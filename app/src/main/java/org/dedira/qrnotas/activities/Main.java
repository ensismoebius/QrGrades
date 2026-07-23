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

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.budiyev.android.codescanner.CodeScanner;
import com.budiyev.android.codescanner.CodeScannerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.dialogs.LoadingDialog;
import org.dedira.qrnotas.dialogs.NoteDialog;
import org.dedira.qrnotas.dialogs.StudentPickerDialog;
import org.dedira.qrnotas.model.BathroomVisit;
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Enrollment;
import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.IndisciplineEvent;
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

/**
 * This is the "home screen" of QrGrades — the first thing a teacher sees when opening the app
 * (and also the screen the Quick Settings tile jumps straight into for fast scanning).
 * <p>
 * Its job is: let the teacher pick which discipline (subject/class) they are currently teaching,
 * point the camera at a student's QR code (or pick the student manually) to identify them,
 * choose how many points to add or remove, optionally attach a note, and save that change to the
 * database. It also shows the student's current point total and progress toward any goals defined
 * for that discipline.
 * <p>
 * The same start screen also has three standalone actions — "go to bathroom", "came back", and
 * "indiscipline" — each of which arms the shared camera (or a manual picker) for the next
 * identified student instead of the points flow; see {@link #armPendingAction}.
 * <p>
 * The navigation drawer on this screen is the gateway to the rest of the app: adding/editing
 * students, listing students, managing disciplines, backups, and the local web server.
 */
public class Main extends AppCompatActivity {

    /**
     * What the next identified student (via QR scan or manual pick) should be used for. The
     * camera view is shared by every one of these flows — tapping a start-screen action "arms"
     * it here, and the very next student identified is routed accordingly, then this resets back
     * to {@link #POINTS} so a stray extra scan doesn't repeat the action.
     */
    private enum PendingAction { POINTS, BATHROOM_LEAVE, BATHROOM_RETURN, INDISCIPLINE }

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
    private MaterialButton btnBathroomLeave;
    private MaterialButton btnBathroomReturn;
    private MaterialButton btnIndiscipline;
    private TextView txtBathroomStatus;
    private View pendingActionHintGroup;
    private TextView txtActionHint;
    private MaterialButton btnPickManually;
    private MaterialButton btnCancelPendingAction;
    private PendingAction pendingAction = PendingAction.POINTS;
    private AutoCompleteTextView dropdownDiscipline;
    private View txtDisciplineWarning;
    private DrawerLayout drawerLayout;
    private Student student;
    private Enrollment currentEnrollment;
    private List<Discipline> disciplines = new ArrayList<>();
    private String currentDisciplineId;
    private Integer extraPoints = 1;
    private boolean scannerStarted = false;
    private Database database;
    private MediaPlayer mediaPlayer;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    /**
     * Called once by Android when this Activity (screen) is first created — this is where all
     * one-time setup happens: finding views by id, wiring up click listeners, creating the
     * database helper, and starting the QR code scanner view. Android may call this again later
     * (e.g. after the app was killed in the background), so nothing here should assume it only
     * ever runs once per app lifetime.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyShowOverLockScreen();
        // Plays the shared enter/exit animation used across the app so navigating between
        // screens feels consistent.
        ActivityTransitions.forward(this);
        setContentView(R.layout.activity_main);
        // Makes the app content draw behind the system status/navigation bars (modern
        // "edge-to-edge" look) while still keeping padding so content isn't hidden under them.
        EdgeToEdge.apply(this);

        /********************************************/
        /********** Create media player *************/
        /********************************************/
        // Preloads the "beep" sound played whenever a QR code is successfully scanned.
        mediaPlayer = MediaPlayer.create(this, R.raw.qr_scanned);

        /********************************************/
        /************** Text objects ****************/
        /********************************************/
        // findViewById looks up the widgets declared in activity_main.xml by their id so we can
        // read/change what they show from Java code.
        this.txtName = this.findViewById(R.id.txtName);
        this.imgPhoto = this.findViewById(R.id.imgPhoto);
        this.txtPoints = this.findViewById(R.id.txtPoints);
        this.txtCurrentPoints = this.findViewById(R.id.txtCurrentPoints);
        this.goalsContainer = this.findViewById(R.id.goalsContainer);
        this.addPointsOverlay = this.findViewById(R.id.addPointsOverlay);
        this.cameraPermissionOverlay = this.findViewById(R.id.cameraPermissionOverlay);
        this.dropdownDiscipline = this.findViewById(R.id.dropdownDiscipline);
        this.dropdownDiscipline.requestFocus();
        this.txtDisciplineWarning = this.findViewById(R.id.txtDisciplineWarning);
        this.drawerLayout = this.findViewById(R.id.drawerLayout);
        this.loadingDialog = new LoadingDialog(this);

        // Overrides the system Back button/gesture. Instead of leaving the app immediately, we
        // first close the navigation drawer if it's open, or dismiss the "add points" overlay if
        // it's showing, and only let the normal back behavior happen otherwise.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                    return;
                }
                if (addPointsOverlay.getVisibility() == View.VISIBLE) {
                    resetToStart();
                    return;
                }
                if (pendingAction != PendingAction.POINTS) {
                    cancelPendingAction();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });

        // Registers the callback Android will invoke after the user answers the camera
        // permission prompt (granted or denied). This must be set up in onCreate (before the
        // Activity is fully started) — Android enforces this and will crash if registered later.
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

        MaterialToolbar toolbar = this.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

        NavigationView navView = this.findViewById(R.id.navView);
        navView.setNavigationItemSelectedListener(this::onNavItemSelected);
        // Keeps the drawer's own content from being drawn under the status bar: reads how much
        // inset the system bars need and applies that as top padding to the drawer's contents.
        ViewCompat.setOnApplyWindowInsetsListener(navView, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        this.btnContinue = this.findViewById(R.id.btnContinue);
        this.btnContinue.setOnClickListener(v -> onContinueClick());

        this.txtBathroomStatus = this.findViewById(R.id.txtBathroomStatus);
        this.pendingActionHintGroup = this.findViewById(R.id.pendingActionHintGroup);
        this.txtActionHint = this.findViewById(R.id.txtActionHint);

        this.btnBathroomLeave = this.findViewById(R.id.btnBathroomLeave);
        this.btnBathroomLeave.setOnClickListener(v -> armPendingAction(PendingAction.BATHROOM_LEAVE));
        this.btnBathroomReturn = this.findViewById(R.id.btnBathroomReturn);
        this.btnBathroomReturn.setOnClickListener(v -> armPendingAction(PendingAction.BATHROOM_RETURN));
        this.btnIndiscipline = this.findViewById(R.id.btnIndiscipline);
        this.btnIndiscipline.setOnClickListener(v -> armPendingAction(PendingAction.INDISCIPLINE));

        this.btnPickManually = this.findViewById(R.id.btnPickManually);
        this.btnPickManually.setOnClickListener(v -> pickStudentManually());
        this.btnCancelPendingAction = this.findViewById(R.id.btnCancelPendingAction);
        this.btnCancelPendingAction.setOnClickListener(v -> cancelPendingAction());

        updateExtraPointsLabel();
        loadDisciplines();

        /******************************************************/
        /****************** Code scanner events ***************/
        /******************************************************/
        CodeScannerView scannerView = findViewById(R.id.scnView);
        scannerView.setOnClickListener(view -> requestCameraAndStartScanning());
        this.mCodeScanner = new CodeScanner(this, scannerView);
        // setDecodeCallback fires on a background thread owned by the scanner library whenever a
        // QR code is successfully decoded, so we hop back to the UI thread with runOnUiThread
        // before touching any views or showing dialogs.
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
            // Modern (API 27+) way to ask the system to show this Activity over the lock screen
            // and turn the screen on.
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            // Older devices don't have the methods above, so the same effect is achieved with
            // window flags instead.
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
        // loadAllDisciplines runs the database query off the main thread and delivers the result
        // through this callback; Database is responsible for posting the callback back onto the
        // UI thread, which is why it's safe to touch views directly inside it here.
        this.database.loadAllDisciplines((success, results) -> {
            this.disciplines = results != null ? results : new ArrayList<>();

            List<String> names = new ArrayList<>();
            for (Discipline d : this.disciplines) names.add(d.name);
            // Feeds the dropdown (an AutoCompleteTextView) the list of discipline names to show
            // as suggestions/options.
            dropdownDiscipline.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));

            dropdownDiscipline.setOnItemClickListener((parent, view, position, id) ->
                    selectDiscipline(this.disciplines.get(position)));

            // Reload keeps an already-picked discipline selected (e.g. after a background resume),
            // only dropping the selection if that discipline was renamed/deleted elsewhere meanwhile.
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

    /** Stores which discipline the teacher picked and updates the dropdown/warning UI to match. */
    private void selectDiscipline(Discipline discipline) {
        this.currentDisciplineId = discipline.id;
        // The "false" here means "don't re-trigger the filtering/suggestion popup" — we're just
        // displaying the chosen name, not letting the user type search text.
        this.dropdownDiscipline.setText(discipline.name, false);
        this.txtDisciplineWarning.setVisibility(View.GONE);
    }

    /* --------------------------------- QR scanning ------------------------------------ */

    /**
     * Called once a QR code has been decoded into text (expected to be a student id). The camera
     * view is shared by every "identify a student" flow, so this always routes through
     * {@link #identifyStudent}, which decides what to actually do based on {@link #pendingAction}.
     */
    private void onQrScanned(String studentId) {
        mediaPlayer.start();
        identifyStudent(studentId);
    }

    /** Entry point for "Award points manually" (nav drawer item) — always the points flow, regardless of any armed start-screen action. */
    private void selectStudentManually() {
        this.pendingAction = PendingAction.POINTS;
        updateActionHint();
        pickStudentManually();
    }

    /**
     * Opens the manual student picker for whichever action is currently armed (points, by
     * default). This is the "or pick manually" alternative to scanning, offered both from the
     * nav drawer (always points) and from the start-screen hint shown once a bathroom/indiscipline
     * action has been armed.
     */
    private void pickStudentManually() {
        if (this.pendingAction == PendingAction.POINTS && !requireDisciplineSelected()) return;
        new StudentPickerDialog(this, this.database, picked -> identifyStudent(picked.id)).show();
    }

    /**
     * Routes a just-identified student (by QR scan or manual pick) to whichever action was armed
     * via the start-screen buttons, then immediately resets {@link #pendingAction} back to
     * {@link PendingAction#POINTS} so a second, unrelated scan doesn't repeat it.
     */
    private void identifyStudent(String studentId) {
        PendingAction action = this.pendingAction;
        this.pendingAction = PendingAction.POINTS;
        updateActionHint();

        switch (action) {
            case BATHROOM_LEAVE:
                beginBathroomLeave(studentId);
                break;
            case BATHROOM_RETURN:
                beginBathroomReturn(studentId);
                break;
            case INDISCIPLINE:
                beginIndiscipline(studentId);
                break;
            case POINTS:
            default:
                if (!requireDisciplineSelected()) return;
                loadStudentForPoints(studentId);
        }
    }

    /**
     * Guards the points flow specifically (QR scan or manual pick) so points can never be
     * recorded before a discipline has been chosen. Bathroom/indiscipline actions don't need a
     * discipline selected — a hall pass or a behavior note isn't tied to a subject. Shows a
     * warning and returns false if none is selected yet.
     */
    private boolean requireDisciplineSelected() {
        if (this.currentDisciplineId != null) return true;
        this.txtDisciplineWarning.setVisibility(View.VISIBLE);
        int message = this.disciplines.isEmpty() ? R.string.no_disciplines_for_scan : R.string.select_discipline_before_scan;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        return false;
    }

    /**
     * Looks the student up by id, then checks whether they're enrolled in the currently selected
     * discipline. If they are, their info and progress are shown so the teacher can add points;
     * if not, {@link #warnStudentNotInDiscipline} offers to enroll them on the spot.
     */
    private void loadStudentForPoints(String studentId) {
        this.loadingDialog.show();
        this.loadingDialog.setCancelable(false);
        this.loadingDialog.setCanceledOnTouchOutside(false);

        // Database calls below run asynchronously (off the main thread) and call back later with
        // the result; nested callbacks here just chain "load student" -> "load enrollment".
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

    /** Shows a dialog explaining the scanned/picked student isn't enrolled in this discipline, offering to enroll them. */
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

    /**
     * Looks up which class groups (sections) exist for this discipline so the teacher can pick
     * which one to enroll the student into. If there's only one class group, it's chosen
     * automatically instead of showing a redundant picker.
     */
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

    /** Creates a brand-new Enrollment (student + class group, starting at 0 points) and saves it. */
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

    /** Starts the camera preview if permission is already granted, otherwise asks for it (showing the overlay meanwhile). */
    private void requestCameraAndStartScanning() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showScannerGranted();
        } else {
            cameraPermissionOverlay.setVisibility(View.VISIBLE);
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    /** Hides the "camera permission needed" overlay and starts the live camera preview/scanning. */
    private void showScannerGranted() {
        cameraPermissionOverlay.setVisibility(View.GONE);
        mCodeScanner.startPreview();
        scannerStarted = true;
    }

    /**
     * Handles taps on items in the navigation drawer: closes the drawer, then either triggers the
     * manual point-award flow directly or launches the matching Activity for everything else
     * (add student, list students, disciplines, backups, web server).
     */
    private boolean onNavItemSelected(MenuItem item) {
        drawerLayout.closeDrawer(GravityCompat.START);

        int id = item.getItemId();
        if (id == R.id.navManualPoints) {
            selectStudentManually();
            return true;
        }

        Class<? extends AppCompatActivity> target = null;
        if (id == R.id.navAddStudent) target = AddOrEditStudent.class;
        else if (id == R.id.navListStudents) target = StudentList.class;
        else if (id == R.id.navDisciplines) target = DisciplineList.class;
        else if (id == R.id.navBackups) target = BackupList.class;
        else if (id == R.id.navWebServer) target = WebServerActivity.class;

        // Intent here just means "open this other screen"; no extra data needs to be passed.
        if (target != null) startActivity(new Intent(this, target));
        return true;
    }

    /** Converts a density-independent pixel (dp) value into actual screen pixels for this device. */
    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    /**
     * Fetches the export/progress data (current points + goal progress) for the given enrollment
     * and, once found, hands it to {@link #bindProgress} to update the UI.
     */
    private void loadProgress(Enrollment enrollment) {
        // loadExportData can return data for multiple enrollments at once (it takes a list), even
        // though here we only ever ask for a single one — we scan the results for the matching id.
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

    /** Renders the student's current point total and one progress row per goal defined for the discipline. */
    private void bindProgress(StudentExportData data) {
        this.txtCurrentPoints.setText(String.valueOf(data.points));
        // Clears any goal rows left over from a previous student before adding new ones.
        this.goalsContainer.removeAllViews();

        if (data.goals == null || data.goals.isEmpty()) return;

        LayoutInflater inflater = LayoutInflater.from(this);
        for (GoalProgress goal : new ArrayList<>(data.goals)) {
            // Inflates one copy of the item_goal_progress.xml layout per goal, without attaching
            // it to goalsContainer yet ("false" = attachToRoot false), so we can fill it in first.
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

    /** Resolves a theme attribute (e.g. colorTertiary) to its actual color value for the app's current theme. */
    private int fetchThemeColor(int attr) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    /** Animates the "add points" overlay into view (fade + slide up) once a valid student/enrollment is loaded. */
    private void revealStudentContent() {
        if (addPointsOverlay.getVisibility() == View.VISIBLE) return;

        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
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

    /** Small "pop" animation used to give visual feedback when the point counter changes. */
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

    /**
     * Handles the "Continue" button: validates a student and a non-zero point delta are set, then
     * opens the note dialog so the teacher can optionally attach a reason before saving.
     */
    private void onContinueClick() {
        if (this.student == null || this.currentEnrollment == null) {
            Toast.makeText(this, R.string.scan_first, Toast.LENGTH_SHORT).show();
            return;
        }

        if (this.extraPoints == 0) {
            Toast.makeText(this, R.string.select_points_first, Toast.LENGTH_SHORT).show();
            return;
        }

        new NoteDialog(this, this.database, this.extraPoints, this.student.name, this::onNoteConfirmed).show();
    }

    /**
     * Called after the teacher confirms (or skips) the note dialog. Applies the point delta to the
     * student's enrollment, records the change in the points history log, shows an undo-capable
     * confirmation, and resets the screen for the next scan.
     */
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
            // Fire-and-forget save: we don't need to react to success/failure of the history log
            // itself, so the callback body is intentionally empty.
            this.database.savePointsHistory(history, (histSuccess, savedHistory) -> {
            });

            // Snackbar with an "Undo" action lets the teacher revert an accidental point change
            // within a few seconds without navigating anywhere.
            Snackbar.make(this.btnContinue, getString(R.string.points_added, delta), Snackbar.LENGTH_LONG)
                    .setAction(R.string.undo, v -> undoPoints(enrollmentId, previousGrades))
                    .show();

            resetToStart();
        });
    }

    /** Clears the currently loaded student/enrollment and animates the overlay back out, readying the screen for the next scan. */
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

    /** Restores the enrollment's point total to what it was before the last change, used by the Snackbar's "Undo" action. */
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

    /* --------------------------- Bathroom / indiscipline -------------------------------- */

    /**
     * Arms a start-screen action (bathroom leave/return, indiscipline): the next student
     * identified — by the already-live camera or by tapping "Pick manually" — is routed to it
     * instead of the points flow. Shows a hint explaining what to do next, with a way to cancel.
     */
    private void armPendingAction(PendingAction action) {
        this.pendingAction = action;
        updateActionHint();
    }

    /** Un-arms whatever start-screen action was pending, back to the default (points). */
    private void cancelPendingAction() {
        this.pendingAction = PendingAction.POINTS;
        updateActionHint();
    }

    /** Shows/hides the "scan or pick manually" hint strip depending on whether an action is currently armed. */
    private void updateActionHint() {
        if (this.pendingAction == PendingAction.POINTS) {
            this.pendingActionHintGroup.setVisibility(View.GONE);
            return;
        }

        int hintRes;
        switch (this.pendingAction) {
            case BATHROOM_LEAVE:
                hintRes = R.string.bathroom_leave_hint;
                break;
            case BATHROOM_RETURN:
                hintRes = R.string.bathroom_return_hint;
                break;
            case INDISCIPLINE:
            default:
                hintRes = R.string.indiscipline_hint;
                break;
        }
        this.txtActionHint.setText(hintRes);
        this.pendingActionHintGroup.setVisibility(View.VISIBLE);
    }

    /**
     * Refreshes the "go to bathroom" / "came back" button states from the single current hall
     * pass (at most one student is meant to be out at a time), and shows who that is, if anyone.
     * Called on every resume and after every bathroom action completes.
     */
    private void refreshBathroomAvailability() {
        this.database.loadAnyActiveBathroomVisit((found, visit) -> {
            this.btnBathroomLeave.setEnabled(!found);
            this.btnBathroomReturn.setEnabled(found);

            if (!found) {
                this.txtBathroomStatus.setVisibility(View.GONE);
                return;
            }

            this.database.loadStudent(visit.studentId, (studentFound, outStudent) -> {
                String name = studentFound ? outStudent.name : "?";
                this.txtBathroomStatus.setText(getString(R.string.bathroom_status_out, name));
                this.txtBathroomStatus.setVisibility(View.VISIBLE);
            });
        });
    }

    /** Looks up the identified student, then records them as having left for the bathroom. */
    private void beginBathroomLeave(String studentId) {
        this.loadingDialog.show();
        this.database.loadStudent(studentId, (found, leavingStudent) -> {
            this.loadingDialog.dismiss();
            if (!found) {
                Toast.makeText(this, R.string.student_not_found, Toast.LENGTH_SHORT).show();
                return;
            }

            this.database.startBathroomVisit(leavingStudent.id, (success, visit) -> {
                if (!success) {
                    Toast.makeText(this, getString(R.string.bathroom_already_out, leavingStudent.name), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.bathroom_left, leavingStudent.name), Toast.LENGTH_SHORT).show();
                }
                refreshBathroomAvailability();
            });
        });
    }

    /** Looks up the identified student, then closes their open bathroom visit and shows how long they were out. */
    private void beginBathroomReturn(String studentId) {
        this.loadingDialog.show();
        this.database.loadStudent(studentId, (found, returningStudent) -> {
            this.loadingDialog.dismiss();
            if (!found) {
                Toast.makeText(this, R.string.student_not_found, Toast.LENGTH_SHORT).show();
                return;
            }

            this.database.endBathroomVisit(returningStudent.id, (success, visit) -> {
                if (!success) {
                    Toast.makeText(this, getString(R.string.bathroom_not_out, returningStudent.name), Toast.LENGTH_SHORT).show();
                } else {
                    showBathroomReturnSummary(returningStudent, visit);
                }
                refreshBathroomAvailability();
            });
        });
    }

    /** Shows how long the student was out, flagging it if the trip went past the evasion window. */
    private void showBathroomReturnSummary(Student student, BathroomVisit visit) {
        long durationMs = visit.returnedAt - visit.wentAt;
        String message = getString(R.string.bathroom_return_summary, student.name, formatDuration(durationMs));
        if (visit.evaded) message += "\n\n" + getString(R.string.bathroom_evaded_warning);

        new AlertDialog.Builder(this)
                .setTitle(R.string.bathroom_return_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Formats a millisecond duration as "H:MM:SS". */
    private String formatDuration(long durationMs) {
        long totalSeconds = durationMs / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
    }

    /** Looks up the identified student, then prompts for an optional note and saves a new indiscipline record for them. */
    private void beginIndiscipline(String studentId) {
        this.loadingDialog.show();
        this.database.loadStudent(studentId, (found, targetStudent) -> {
            this.loadingDialog.dismiss();
            if (!found) {
                Toast.makeText(this, R.string.student_not_found, Toast.LENGTH_SHORT).show();
                return;
            }
            showIndisciplineDialog(targetStudent);
        });
    }

    /** Prompts for an optional note describing the indiscipline, then saves it. */
    private void showIndisciplineDialog(Student targetStudent) {
        final String disciplineId = this.currentDisciplineId;

        EditText input = new EditText(this);
        input.setHint(R.string.indiscipline_note_hint);
        int padding = (int) dp(20);
        input.setPadding(padding, padding, padding, 0);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.indiscipline_dialog_title, targetStudent.name))
                .setView(input)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String note = input.getText() == null ? "" : input.getText().toString().trim();
                    saveIndisciplineEvent(targetStudent, disciplineId, note);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Persists a new indiscipline record and reports success/failure to the teacher. */
    private void saveIndisciplineEvent(Student student, String disciplineId, String note) {
        IndisciplineEvent event = new IndisciplineEvent();
        event.studentId = student.id;
        event.disciplineId = disciplineId;
        event.note = note;
        event.createdAt = System.currentTimeMillis();

        this.database.saveIndisciplineEvent(event, (success, saved) -> {
            if (success) {
                Toast.makeText(this, getString(R.string.indiscipline_saved, student.name), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.indiscipline_save_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Called by Android every time this screen comes back to the foreground (including the very
     * first time, right after onCreate). Used here to re-request the camera and restart scanning,
     * recreate the media player (it was released in onStop), and reload disciplines in case they
     * changed while this screen was in the background.
     */
    @Override
    protected void onResume() {
        super.onResume();
        requestCameraAndStartScanning();
        mediaPlayer = MediaPlayer.create(this, R.raw.qr_scanned);
        loadDisciplines();
        refreshBathroomAvailability();
    }

    /**
     * Called by Android when this screen is no longer in the foreground (e.g. another screen is
     * opened on top). The camera preview is stopped and its resources released here so the camera
     * isn't held open (and draining battery) while this screen isn't visible.
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (scannerStarted) {
            mCodeScanner.stopPreview();
            mCodeScanner.releaseResources();
            scannerStarted = false;
        }
    }

    /**
     * Called by Android when this screen is no longer visible at all (e.g. user left the app).
     * The media player is stopped and released here to free its native resources; any exception
     * during that cleanup is intentionally swallowed since there's nothing useful to do about it.
     */
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

    /** Click handler for the "+" floating action button: increases the point delta, up to a cap of +5. */
    private void onClick(View v) {
        if (this.extraPoints >= 5) return;
        this.extraPoints++;
        updateExtraPointsLabel();
        bounce(this.txtPoints);
    }

    /** Refreshes the point-delta label's text (e.g. "+3" or "-2") and color (red for negative, accent for positive/zero). */
    private void updateExtraPointsLabel() {
        this.txtPoints.setText(String.format(Locale.getDefault(), "%+d", this.extraPoints));
        int colorAttr = this.extraPoints < 0
                ? com.google.android.material.R.attr.colorError
                : com.google.android.material.R.attr.colorTertiary;
        this.txtPoints.setTextColor(fetchThemeColor(colorAttr));
    }
}
