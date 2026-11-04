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

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AutoCompleteTextView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.TextViewCompat;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.Attendance;
import org.dedira.qrnotas.model.AttendanceStats;
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Period;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.util.AttendanceReports;
import org.dedira.qrnotas.util.Background;
import org.dedira.qrnotas.util.BitmapConverter;
import org.dedira.qrnotas.util.ThemeColors;
import org.dedira.qrnotas.util.Ui;

import java.text.Collator;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Frequência": takes the roll for a class on a chosen day, lists the days that have a roll call,
 * and totals presences and absences per student for the current period or a closed one. The
 * phone's version of the browser page's Attendance tab (the scan screen's roll-call mode, which
 * marks students present as their QR codes are read, writes to the same records).
 */
public class AttendanceFragment extends BaseFragment {

    // Usual minimum-attendance rule: more than a quarter of the classes missed is a problem.
    // Matches the alerts screen's default.
    private static final int ABSENCE_LIMIT = 25;

    private final List<Discipline> disciplines = new ArrayList<>();
    private final List<ClassGroup> pickable = new ArrayList<>();
    private final Map<String, String> labels = new HashMap<>();

    private String classGroupId = "";
    private String day = "";
    private String period = "current";

    // The open class day.
    private List<Student> students = new ArrayList<>();
    private final Map<String, Attendance> marks = new HashMap<>();
    private boolean started;
    private boolean dayLoaded;

    private AttendanceReports.Summary summary;
    private List<AttendanceReports.Day> days = new ArrayList<>();
    private boolean aroundLoaded;

    // Bumped on every request so a slow, outdated answer can't overwrite a newer one.
    private int dayRequest;
    private int aroundRequest;

    private LinearLayout content;
    private LinearLayout rollBox;
    private LinearLayout daysBox;
    private LinearLayout summaryBox;
    private TextView txtCounts;
    private MaterialButton btnDay;

    public AttendanceFragment() {
        super(R.layout.screen_scroll);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((MaterialToolbar) view.findViewById(R.id.toolbar)).setTitle(R.string.attendance_title);
        content = view.findViewById(R.id.content);
        if (day.isEmpty()) day = Attendance.today();
        loadGroups();
    }

    /* ------------------------------ classes ------------------------------ */

    private void loadGroups() {
        database.loadAllDisciplines((ok1, ds) -> {
            if (getView() == null || !ok1 || ds == null) return;
            database.loadAllClassGroups((ok2, gs) -> {
                if (getView() == null || !ok2 || gs == null) return;
                disciplines.clear();
                disciplines.addAll(ds);
                pickable.clear();
                labels.clear();
                for (Discipline d : disciplines) {
                    for (ClassGroup g : gs) {
                        if (g.disciplineId.equals(d.id) && !g.isClosed()) {
                            pickable.add(g);
                            labels.put(g.id, d.name + " · " + g.name);
                        }
                    }
                }
                render();
            });
        });
    }

    private ClassGroup currentGroup() {
        for (ClassGroup g : pickable) if (g.id.equals(classGroupId)) return g;
        return null;
    }

    /* ------------------------------ screen ------------------------------ */

    private void render() {
        Context context = requireContext();
        content.removeAllViews();
        if (pickable.isEmpty()) {
            Ui.section(context, content, getString(R.string.attendance_roll_title)).message(getString(R.string.attendance_need_class));
            return;
        }
        if (currentGroup() == null) {
            classGroupId = pickable.get(0).id;
            period = "current";
        }

        Ui.Section filters = Ui.section(context, content, getString(R.string.attendance_roll_title));
        List<String> names = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < pickable.size(); i++) {
            names.add(labels.get(pickable.get(i).id));
            if (pickable.get(i).id.equals(classGroupId)) selected = i;
        }
        AutoCompleteTextView group = Ui.dropdown(context, filters.header, getString(R.string.attendance_group_hint));
        Ui.setDropdown(group, names, selected);
        group.setOnItemClickListener((parent, v, position, id) -> {
            classGroupId = pickable.get(position).id;
            period = "current";
            started = false;
            dayLoaded = false;
            aroundLoaded = false;
            renderBoxes();
            loadAll();
        });

        // The day: a button that opens the calendar (the field is read-only, so the keyboard never covers the roll).
        btnDay = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnDay.setIconResource(R.drawable.ic_calendar);
        btnDay.setAllCaps(false);
        btnDay.setOnClickListener(v -> pickDay());
        LinearLayout.LayoutParams dayParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dayParams.topMargin = getResources().getDimensionPixelSize(R.dimen.spacing_sm);
        filters.header.addView(btnDay, dayParams);
        updateDayButton();

        rollBox = box();
        daysBox = box();
        summaryBox = box();
        renderBoxes();
        loadAll();
    }

    private LinearLayout box() {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        content.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void renderBoxes() {
        renderRoll();
        renderDays();
        renderSummary();
    }

    private void updateDayButton() {
        if (btnDay != null) btnDay.setText(Ui.dayWithWeekday(requireContext(), day));
    }

    private void pickDay() {
        Calendar cal = Calendar.getInstance();
        try {
            cal.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day));
        } catch (ParseException ignored) {
            // keep today
        }
        new DatePickerDialog(requireContext(), (picker, year, month, dayOfMonth) -> {
            Calendar chosen = Calendar.getInstance();
            chosen.clear();
            chosen.set(year, month, dayOfMonth);
            selectDay(Attendance.dayOf(chosen.getTimeInMillis()));
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void selectDay(String newDay) {
        day = newDay;
        updateDayButton();
        dayLoaded = false;
        renderRoll();
        renderDays();
        loadDay();
    }

    private void snack(String text) {
        View view = getView();
        if (view != null) Snackbar.make(view, text, Snackbar.LENGTH_LONG).show();
    }

    /** Whatever happened, show the stored state afterwards. */
    private void failed() {
        snack(getString(R.string.attendance_save_failed));
        loadAll();
    }

    /* ------------------------------ loading ------------------------------ */

    private void loadAll() {
        loadDay();
        loadAround();
    }

    private void loadDay() {
        final int request = ++dayRequest;
        final String group = classGroupId;
        final String forDay = day;
        database.loadStudentsForClassGroup(group, (ok1, list) -> {
            if (getView() == null || request != dayRequest) return;
            database.loadAttendanceForDay(group, forDay, (ok2, rows) -> {
                if (getView() == null || request != dayRequest) return;
                if (!ok1 || !ok2 || list == null || rows == null) {
                    rollBox.removeAllViews();
                    Ui.section(requireContext(), rollBox, getString(R.string.attendance_roll_title))
                            .message(getString(R.string.attendance_load_failed));
                    return;
                }
                final Collator collator = Collator.getInstance(new Locale("pt", "BR"));
                students = new ArrayList<>(list);
                Collections.sort(students, new Comparator<Student>() {
                    @Override
                    public int compare(Student a, Student b) {
                        return collator.compare(a.name, b.name);
                    }
                });
                marks.clear();
                for (Attendance a : rows) marks.put(a.studentId, a);
                started = !rows.isEmpty();
                dayLoaded = true;
                renderRoll();
            });
        });
    }

    /** The recorded days and the per-student totals; both change whenever the roll does. */
    private void loadAround() {
        final int request = ++aroundRequest;
        final ClassGroup group = currentGroup();
        final String forPeriod = period;
        if (group == null) return;
        Background.run(() -> {
            Object[] result = new Object[2];
            result[0] = AttendanceReports.days(database, group.id);
            result[1] = AttendanceReports.summary(database, group, forPeriod);
            return result;
        }, (result, error) -> {
            if (getView() == null || request != aroundRequest || error != null || result == null) return;
            @SuppressWarnings("unchecked")
            List<AttendanceReports.Day> loadedDays = (List<AttendanceReports.Day>) result[0];
            days = loadedDays;
            summary = (AttendanceReports.Summary) result[1];
            if (summary == null) { // the chosen closed period no longer exists
                period = "current";
                loadAround();
                return;
            }
            aroundLoaded = true;
            renderDays();
            renderSummary();
        });
    }

    /* ------------------------------ roll ------------------------------ */

    private void renderRoll() {
        if (rollBox == null) return;
        Context context = requireContext();
        rollBox.removeAllViews();
        txtCounts = null;
        if (!dayLoaded) return;

        Ui.Section card = Ui.section(context, rollBox, Ui.dayWithWeekday(context, day));
        if (students.isEmpty()) {
            card.message(getString(R.string.attendance_no_students));
            return;
        }

        if (!started) {
            card.message(getString(R.string.attendance_not_started));
            card.body.addView(actionButton(context, R.string.attendance_start_present, true, v -> start(true)));
            card.body.addView(actionButton(context, R.string.attendance_start_absent, false, v -> start(false)));
            TextView tip = card.message(getString(R.string.attendance_start_tip));
            TextViewCompat.setTextAppearance(tip, R.style.TextAppearance_QrGrades_BodySmall);
            return;
        }

        txtCounts = card.message("");
        updateCounts();

        card.body.addView(actionButton(context, R.string.attendance_all_present, false, v -> markAll(Attendance.STATUS_PRESENT)));
        card.body.addView(actionButton(context, R.string.attendance_all_absent, false, v -> markAll(Attendance.STATUS_ABSENT)));
        MaterialButton delete = actionButton(context, R.string.attendance_delete_day, false, v -> confirmDelete());
        delete.setTextColor(ThemeColors.resolve(context, com.google.android.material.R.attr.colorError));
        card.body.addView(delete);

        LayoutInflater inflater = LayoutInflater.from(context);
        for (Student s : students) card.body.addView(studentRow(inflater, card.body, s));
    }

    private MaterialButton actionButton(Context context, int text, boolean filled, View.OnClickListener onClick) {
        MaterialButton button = new MaterialButton(context, null, filled
                ? com.google.android.material.R.attr.materialButtonStyle
                : com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(text);
        button.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = getResources().getDimensionPixelSize(R.dimen.spacing_sm);
        button.setLayoutParams(lp);
        return button;
    }

    private void updateCounts() {
        if (txtCounts == null) return;
        int present = 0, absent = 0, excused = 0, unmarked = 0;
        for (Student s : students) {
            Attendance a = marks.get(s.id);
            if (a == null) unmarked++;
            else if (a.status == Attendance.STATUS_PRESENT) present++;
            else if (a.status == Attendance.STATUS_EXCUSED) excused++;
            else absent++;
        }
        txtCounts.setText(unmarked > 0 ? getString(R.string.attendance_counts_unmarked, present, absent, excused, unmarked)
                : getString(R.string.attendance_counts, present, absent, excused));
    }

    private View studentRow(LayoutInflater inflater, ViewGroup parent, Student s) {
        View row = inflater.inflate(R.layout.item_roll_student, parent, false);
        ((TextView) row.findViewById(R.id.txtRollName)).setText(s.name);
        android.graphics.Bitmap photo = BitmapConverter.loadBitmap(s.photoPath);
        if (photo != null) ((ImageView) row.findViewById(R.id.imgRollPhoto)).setImageBitmap(photo);

        MaterialButtonToggleGroup toggle = row.findViewById(R.id.rollToggle);
        tint(row.findViewById(R.id.btnRollPresent), R.attr.colorSuccessContainer);
        tint(row.findViewById(R.id.btnRollAbsent), com.google.android.material.R.attr.colorErrorContainer);
        tint(row.findViewById(R.id.btnRollExcused), com.google.android.material.R.attr.colorTertiaryContainer);

        Attendance current = marks.get(s.id);
        if (current != null) toggle.check(buttonFor(current.status));
        // Added after the initial state, so setting it up doesn't count as a tap.
        toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            mark(s, statusFor(checkedId));
        });
        return row;
    }

    private static int buttonFor(int status) {
        if (status == Attendance.STATUS_PRESENT) return R.id.btnRollPresent;
        if (status == Attendance.STATUS_EXCUSED) return R.id.btnRollExcused;
        return R.id.btnRollAbsent;
    }

    private static int statusFor(int buttonId) {
        if (buttonId == R.id.btnRollPresent) return Attendance.STATUS_PRESENT;
        if (buttonId == R.id.btnRollExcused) return Attendance.STATUS_EXCUSED;
        return Attendance.STATUS_ABSENT;
    }

    /** The button's fill when it is the chosen one; unchosen buttons stay see-through. */
    private void tint(View button, int attr) {
        Context context = requireContext();
        int on = ThemeColors.resolve(context, attr);
        int[][] states = {{android.R.attr.state_checked}, {}};
        ((MaterialButton) button).setBackgroundTintList(new ColorStateList(states, new int[]{on, android.graphics.Color.TRANSPARENT}));
        ((MaterialButton) button).setTextColor(new ColorStateList(states, new int[]{
                ThemeColors.resolve(context, com.google.android.material.R.attr.colorOnSurface),
                ThemeColors.resolve(context, com.google.android.material.R.attr.colorOnSurfaceVariant)}));
    }

    private void mark(Student s, int status) {
        Attendance m = new Attendance();
        m.studentId = s.id;
        m.status = status;
        final String group = classGroupId;
        final String forDay = day;
        database.setAttendance(group, forDay, Collections.singletonList(m), (ok, rows) -> {
            if (getView() == null) return;
            if (!ok || rows == null) {
                failed();
                return;
            }
            if (!group.equals(classGroupId) || !forDay.equals(day)) return;
            marks.clear();
            for (Attendance a : rows) marks.put(a.studentId, a);
            updateCounts(); // in place, so the list keeps its scroll position and focus
            loadAround();
        });
    }

    private void markAll(int status) {
        List<Attendance> all = new ArrayList<>();
        for (Student s : students) {
            Attendance m = new Attendance();
            m.studentId = s.id;
            m.status = status;
            all.add(m);
        }
        final String group = classGroupId;
        final String forDay = day;
        database.setAttendance(group, forDay, all, (ok, rows) -> {
            if (getView() == null) return;
            if (!ok || rows == null) {
                failed();
                return;
            }
            if (!group.equals(classGroupId) || !forDay.equals(day)) return;
            marks.clear();
            for (Attendance a : rows) marks.put(a.studentId, a);
            started = !rows.isEmpty();
            renderRoll();
            loadAround();
        });
    }

    private void start(boolean everyonePresent) {
        final String group = classGroupId;
        final String forDay = day;
        database.startAttendance(group, forDay, (ok, rows) -> {
            if (getView() == null) return;
            if (!ok || rows == null) {
                failed();
                return;
            }
            if (!group.equals(classGroupId) || !forDay.equals(day)) return;
            marks.clear();
            for (Attendance a : rows) marks.put(a.studentId, a);
            started = !rows.isEmpty();
            if (everyonePresent) {
                markAll(Attendance.STATUS_PRESENT);
            } else {
                renderRoll();
                loadAround();
            }
        });
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.attendance_delete_confirm, Ui.day(requireContext(), day)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.attendance_delete_day, (d, w) -> {
                    final String group = classGroupId;
                    final String forDay = day;
                    database.deleteAttendanceDay(group, forDay, (ok, removed) -> {
                        if (getView() == null) return;
                        if (!ok) {
                            failed();
                            return;
                        }
                        snack(getString(R.string.attendance_deleted));
                        loadAll();
                    });
                })
                .show();
    }

    /* ------------------------------ recorded days ------------------------------ */

    private void renderDays() {
        if (daysBox == null) return;
        Context context = requireContext();
        daysBox.removeAllViews();
        Ui.Section card = Ui.section(context, daysBox, getString(R.string.attendance_days_title));
        if (!aroundLoaded) return;
        if (days.isEmpty()) {
            card.message(getString(R.string.attendance_days_empty));
            return;
        }
        card.count(days.size());
        for (AttendanceReports.Day d : days) {
            View row = Ui.row(context, card.body, Ui.dayWithWeekday(context, d.day), null,
                    getString(R.string.attendance_counts, d.stats.present, d.stats.absent, d.stats.excused),
                    null, 0, v -> {
                        selectDay(d.day);
                        View scroll = requireView().findViewById(R.id.scroll);
                        if (scroll instanceof androidx.core.widget.NestedScrollView) {
                            ((androidx.core.widget.NestedScrollView) scroll).smoothScrollTo(0, 0);
                        }
                    });
            if (d.day.equals(day)) row.setBackgroundResource(R.drawable.bg_row_selected);
            card.body.addView(row);
        }
    }

    /* ------------------------------ per-student totals ------------------------------ */

    private void renderSummary() {
        if (summaryBox == null) return;
        Context context = requireContext();
        summaryBox.removeAllViews();
        Ui.Section card = Ui.section(context, summaryBox, getString(R.string.attendance_summary_title));
        if (!aroundLoaded || summary == null) return;

        // Period choice: the current one, then each closed one, newest first.
        List<Period> ordered = new ArrayList<>(summary.periods);
        Collections.sort(ordered, new Comparator<Period>() {
            @Override
            public int compare(Period a, Period b) {
                return Long.compare(b.closedAt, a.closedAt);
            }
        });
        List<String> names = new ArrayList<>();
        names.add(getString(R.string.attendance_period_current));
        int selected = 0;
        for (int i = 0; i < ordered.size(); i++) {
            Period p = ordered.get(i);
            names.add(p.label != null && !p.label.trim().isEmpty() ? p.label
                    : getString(R.string.attendance_period_closed_on, Ui.date(context, p.closedAt)));
            if (p.id.equals(period)) selected = i + 1;
        }
        AutoCompleteTextView choice = Ui.dropdown(context, card.header, getString(R.string.attendance_period_hint));
        Ui.setDropdown(choice, names, selected);
        choice.setOnItemClickListener((parent, v, position, id) -> {
            period = position == 0 ? "current" : ordered.get(position - 1).id;
            aroundLoaded = false;
            loadAround();
        });

        String howMany = summary.days == 0 ? getString(R.string.attendance_summary_days_zero)
                : summary.days == 1 ? getString(R.string.attendance_summary_days_one)
                : getString(R.string.attendance_summary_days_many, summary.days);
        card.describe(howMany + " " + getString(R.string.attendance_summary_note, ABSENCE_LIMIT));

        List<AttendanceReports.StudentTotals> sorted = new ArrayList<>(summary.students);
        final Collator collator = Collator.getInstance(new Locale("pt", "BR"));
        Collections.sort(sorted, new Comparator<AttendanceReports.StudentTotals>() {
            @Override
            public int compare(AttendanceReports.StudentTotals a, AttendanceReports.StudentTotals b) {
                int byPct = Double.compare(b.stats.absentPercent(), a.stats.absentPercent());
                return byPct != 0 ? byPct : collator.compare(a.student.name, b.student.name);
            }
        });
        for (AttendanceReports.StudentTotals t : sorted) {
            AttendanceStats stats = t.stats;
            boolean over = stats.total() > 0 && stats.absentPercent() > ABSENCE_LIMIT;
            String value = stats.total() == 0 ? "—" : (over ? "⚠ " : "") + Ui.percent(stats.absentPercent());
            card.body.addView(Ui.row(context, card.body, t.student.name, null,
                    getString(R.string.attendance_student_detail, stats.present, stats.absent, stats.excused),
                    value, over ? com.google.android.material.R.attr.colorError : 0,
                    v -> openHistory(t.student.id)));
        }
    }

    private void openHistory(String studentId) {
        Bundle args = new Bundle();
        args.putString("studentId", studentId);
        NavHostFragment.findNavController(this).navigate(R.id.action_attendance_to_studentProgress, args);
    }
}
