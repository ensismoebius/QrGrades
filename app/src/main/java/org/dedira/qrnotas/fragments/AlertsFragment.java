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

import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.TextViewCompat;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.util.AttendanceReports;
import org.dedira.qrnotas.util.Background;
import org.dedira.qrnotas.util.GoalViews;
import org.dedira.qrnotas.util.Insights;
import org.dedira.qrnotas.util.Ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Alertas e resumo da semana": the week at a glance, and the students who need a look — too many
 * absences, under the discipline's lowest goal, a few points from the next goal, or left out of
 * recent scoring. The same lists as the browser page's Alerts tab, computed by the same code
 * ({@link Insights} and {@link AttendanceReports}); tapping a student opens their history.
 */
public class AlertsFragment extends BaseFragment {

    private static final int[] INACTIVE_CHOICES = {7, 14, 30};
    private static final int DEFAULT_LIMIT = 25;

    private final List<Discipline> disciplines = new ArrayList<>();
    private List<StudentExportData> rows = new ArrayList<>();
    private AttendanceReports.Alerts attendance;
    private String attendanceError;
    private boolean loaded;

    private String disciplineId = "";
    private int inactiveDays = 14;
    private int absenceLimit = DEFAULT_LIMIT;
    // Lists whose "show all" button was pressed.
    private final Set<String> expanded = new HashSet<>();
    // Bumped on every attendance request so a slow, outdated answer can't overwrite a newer one.
    private int attendanceRequest;

    private LinearLayout content;
    private LinearLayout weekBox;
    private LinearLayout attendanceBox;
    private LinearLayout belowBox;
    private LinearLayout nearBox;
    private LinearLayout inactiveBox;

    public AlertsFragment() {
        super(R.layout.screen_scroll);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((MaterialToolbar) view.findViewById(R.id.toolbar)).setTitle(R.string.alerts_title);
        content = view.findViewById(R.id.content);
        // A new view loads once, right here; onResume only refreshes a screen that was already showing.
        loaded = false;
        load();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Points may have been given (or cancelled) while this screen was in the background.
        if (loaded) load();
    }

    /* ------------------------------ loading ------------------------------ */

    private void load() {
        database.loadAllDisciplines((okDisciplines, list) -> {
            if (getView() == null) return;
            if (!okDisciplines || list == null) {
                showMessage(getString(R.string.alerts_load_failed));
                return;
            }
            database.loadExportData(null, (okRows, data) -> {
                if (getView() == null) return;
                if (!okRows || data == null) {
                    showMessage(getString(R.string.alerts_load_failed));
                    return;
                }
                disciplines.clear();
                disciplines.addAll(list);
                rows = data;
                loaded = true;
                render();
            });
        });
    }

    private void loadAttendance() {
        final int request = ++attendanceRequest;
        final double limit = absenceLimit;
        attendance = null;
        attendanceError = null;
        renderAttendance();
        Background.run(() -> AttendanceReports.alerts(database, limit, AttendanceReports.DEFAULT_MIN_DAYS), (result, error) -> {
            if (getView() == null || request != attendanceRequest) return;
            attendance = result;
            attendanceError = (error != null || result == null) ? getString(R.string.alerts_load_failed) : null;
            renderAttendance();
        });
    }

    private void showMessage(String text) {
        content.removeAllViews();
        Ui.section(requireContext(), content, getString(R.string.alerts_title)).message(text);
    }

    /* ------------------------------ screen ------------------------------ */

    private void render() {
        Context context = requireContext();
        content.removeAllViews();
        if (disciplines.isEmpty()) {
            showMessage(getString(R.string.alerts_add_discipline_first));
            return;
        }
        if (!disciplineId.isEmpty() && !hasDiscipline(disciplineId)) disciplineId = "";

        renderFilters(context);
        weekBox = box();
        attendanceBox = box();
        belowBox = box();
        nearBox = box();
        inactiveBox = box();
        renderLists();
        loadAttendance();
    }

    private LinearLayout box() {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        content.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private boolean hasDiscipline(String id) {
        for (Discipline d : disciplines) if (d.id.equals(id)) return true;
        return false;
    }

    private void renderFilters(Context context) {
        Ui.Section filters = Ui.section(context, content, getString(R.string.alerts_filters));

        // Discipline: "all" first, then each one.
        List<String> names = new ArrayList<>();
        names.add(getString(R.string.alerts_all_disciplines));
        int selected = 0;
        for (int i = 0; i < disciplines.size(); i++) {
            names.add(disciplines.get(i).name);
            if (disciplines.get(i).id.equals(disciplineId)) selected = i + 1;
        }
        AutoCompleteTextView discipline = Ui.dropdown(context, filters.header, getString(R.string.alerts_filter_discipline));
        Ui.setDropdown(discipline, names, selected);
        discipline.setOnItemClickListener((parent, v, position, id) -> {
            disciplineId = position == 0 ? "" : disciplines.get(position - 1).id;
            renderLists();
        });

        // How long without points counts as "quiet".
        List<String> days = new ArrayList<>();
        int daysSelected = 0;
        for (int i = 0; i < INACTIVE_CHOICES.length; i++) {
            days.add(getString(R.string.alerts_days_option, INACTIVE_CHOICES[i]));
            if (INACTIVE_CHOICES[i] == inactiveDays) daysSelected = i;
        }
        AutoCompleteTextView quiet = Ui.dropdown(context, filters.header, getString(R.string.alerts_filter_days));
        Ui.setDropdown(quiet, days, daysSelected);
        quiet.setOnItemClickListener((parent, v, position, id) -> {
            inactiveDays = INACTIVE_CHOICES[position];
            renderInactive();
        });

        // Absence limit (%): re-read the attendance when it changes.
        TextInputEditText limit = Ui.numberField(context, filters.header, getString(R.string.alerts_filter_limit), absenceLimit);
        limit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                applyLimit(limit);
                v.clearFocus();
            }
            return false;
        });
        limit.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) applyLimit(limit);
        });
    }

    private void applyLimit(TextInputEditText field) {
        int value = DEFAULT_LIMIT;
        try {
            value = Integer.parseInt(String.valueOf(field.getText()).trim());
        } catch (NumberFormatException ignored) {
            // empty or junk: back to the default below
        }
        if (value < 1 || value > 100) value = DEFAULT_LIMIT;
        field.setText(String.valueOf(value));
        if (value != absenceLimit) {
            absenceLimit = value;
            loadAttendance();
        }
    }

    private List<StudentExportData> rowsInScope() {
        List<StudentExportData> out = new ArrayList<>();
        for (StudentExportData row : rows) {
            if (disciplineId.isEmpty() || disciplineId.equals(row.disciplineId)) out.add(row);
        }
        return out;
    }

    private void renderLists() {
        renderWeek();
        renderBelow();
        renderNear();
        renderInactive();
        renderAttendance();
    }

    private void openHistory(String studentId) {
        Bundle args = new Bundle();
        args.putString("studentId", studentId);
        NavHostFragment.findNavController(this).navigate(R.id.action_alerts_to_studentProgress, args);
    }

    private static String where(StudentExportData row) {
        return row.disciplineName + " · " + row.classGroupName;
    }

    /** "until 30/04/2026 (ends in 5 days)"; a goal that predates dates has no deadline. */
    private String deadline(GoalProgress goal) {
        if (!goal.hasDates()) return getString(R.string.alerts_goal_no_deadline);
        return getString(R.string.alerts_goal_deadline, Ui.day(requireContext(), goal.endDate),
                GoalViews.endsIn(requireContext(), goal.daysLeft));
    }

    private void toggle(String key) {
        if (!expanded.remove(key)) expanded.add(key);
        renderLists();
    }

    /* ------------------------------ weekly summary ------------------------------ */

    private void renderWeek() {
        Context context = requireContext();
        weekBox.removeAllViews();
        Ui.Section card = Ui.section(context, weekBox, getString(R.string.week_title)).describe(getString(R.string.week_description));
        Insights.Week w = Insights.weeklySummary(rowsInScope(), System.currentTimeMillis());

        if (w.entries == 0) {
            card.message(w.prevEntries > 0 ? getString(R.string.week_empty_prev, w.prevEntries) : getString(R.string.week_empty));
            return;
        }

        int change = w.net - w.prevNet;
        List<Ui.Tile> tiles = new ArrayList<>();
        tiles.add(new Ui.Tile("+" + w.given, getString(R.string.week_given), R.attr.colorSuccess));
        if (w.deducted > 0) {
            tiles.add(new Ui.Tile(Ui.signed(-w.deducted), getString(R.string.week_deducted),
                    com.google.android.material.R.attr.colorError));
        }
        tiles.add(new Ui.Tile(String.valueOf(w.students), getString(R.string.week_students), 0));
        tiles.add(new Ui.Tile(String.valueOf(w.entries), getString(R.string.week_entries), 0));
        tiles.add(new Ui.Tile(Ui.signed(change), getString(R.string.week_change),
                change < 0 ? com.google.android.material.R.attr.colorError : (change > 0 ? R.attr.colorSuccess : 0)));
        Ui.tiles(context, card.body, tiles);

        if (!w.top.isEmpty()) {
            TextView heading = card.message(getString(R.string.week_top));
            TextViewCompat.setTextAppearance(heading, R.style.TextAppearance_QrGrades_LabelLarge);
            ((LinearLayout.LayoutParams) heading.getLayoutParams()).topMargin = getResources().getDimensionPixelSize(R.dimen.spacing_md);
            for (Insights.StudentPoints s : w.top) {
                card.body.addView(Ui.row(context, card.body, s.name, null, null, Ui.signed(s.points), R.attr.colorSuccess,
                        v -> openHistory(s.studentId)));
            }
        }
        if (!w.tags.isEmpty()) {
            TextView heading = card.message(getString(R.string.week_tags));
            TextViewCompat.setTextAppearance(heading, R.style.TextAppearance_QrGrades_LabelLarge);
            ((LinearLayout.LayoutParams) heading.getLayoutParams()).topMargin = getResources().getDimensionPixelSize(R.dimen.spacing_md);
            StringBuilder line = new StringBuilder();
            for (Insights.TagCount t : w.tags) {
                if (line.length() > 0) line.append("  ·  ");
                line.append(t.tag).append(" ").append(t.entries).append("×");
            }
            card.message(line.toString());
        }
    }

    /* ------------------------------ points lists ------------------------------ */

    private View goalRow(Insights.GoalFlag item, @androidx.annotation.AttrRes int missingColor) {
        Context context = requireContext();
        GoalProgress goal = item.goal;
        String goalLine = getString(R.string.alerts_goal_line, goal.goalName + " (" + goal.targetPoints + ")", deadline(goal));
        String detail = getString(R.string.alerts_points_of_goal, goal.currentPoints, goal.targetPoints) + "\n" + goalLine;
        return Ui.row(context, content, item.row.studentName, where(item.row), detail,
                getString(R.string.alerts_missing, item.missing), missingColor, v -> openHistory(item.row.studentId));
    }

    private void renderBelow() {
        Context context = requireContext();
        belowBox.removeAllViews();
        List<StudentExportData> scope = rowsInScope();
        List<Insights.GoalFlag> items = Insights.belowMinimum(scope);
        Ui.Section card = Ui.section(context, belowBox, getString(R.string.below_title))
                .describe(getString(R.string.below_description)).count(items.size());
        if (!Insights.hasGoals(scope)) {
            card.message(getString(R.string.below_no_goals));
        } else if (!Insights.hasRunningGoals(scope)) {
            card.message(getString(R.string.below_no_running));
        } else if (items.isEmpty()) {
            card.message(getString(R.string.below_none));
        } else {
            Ui.fillCollapsible(card, items, expanded.contains("below"),
                    (item, parent) -> goalRow(item, com.google.android.material.R.attr.colorError), () -> toggle("below"));
        }
    }

    private void renderNear() {
        Context context = requireContext();
        nearBox.removeAllViews();
        List<Insights.GoalFlag> items = Insights.nearNextGoal(rowsInScope());
        Ui.Section card = Ui.section(context, nearBox, getString(R.string.near_title))
                .describe(getString(R.string.near_description)).count(items.size());
        if (items.isEmpty()) {
            card.message(getString(R.string.near_none));
        } else {
            Ui.fillCollapsible(card, items, expanded.contains("near"),
                    (item, parent) -> goalRow(item, R.attr.colorSuccess), () -> toggle("near"));
        }
    }

    private void renderInactive() {
        Context context = requireContext();
        inactiveBox.removeAllViews();
        final long now = System.currentTimeMillis();
        List<Insights.Quiet> items = Insights.inactive(rowsInScope(), now, inactiveDays);
        Ui.Section card = Ui.section(context, inactiveBox, getString(R.string.inactive_title))
                .describe(getString(R.string.inactive_description, inactiveDays)).count(items.size());
        if (items.isEmpty()) {
            card.message(getString(R.string.inactive_none));
            return;
        }
        Ui.fillCollapsible(card, items, expanded.contains("inactive"), (item, parent) -> {
            String value;
            String detail = null;
            if (item.lastAt == null) {
                value = getString(R.string.inactive_never);
            } else {
                int daysAgo = Insights.daysBetween(item.lastAt, now);
                value = daysAgo == 1 ? getString(R.string.inactive_days_ago_one) : getString(R.string.inactive_days_ago_many, daysAgo);
                detail = getString(R.string.inactive_last_point, Ui.date(context, item.lastAt));
            }
            return Ui.row(context, parent, item.row.studentName, where(item.row), detail, value,
                    item.lastAt == null ? com.google.android.material.R.attr.colorError : 0,
                    v -> openHistory(item.row.studentId));
        }, () -> toggle("inactive"));
    }

    /* ------------------------------ attendance ------------------------------ */

    private void renderAttendance() {
        if (attendanceBox == null) return;
        Context context = requireContext();
        attendanceBox.removeAllViews();
        Ui.Section card = Ui.section(context, attendanceBox, getString(R.string.absences_title))
                .describe(getString(R.string.absences_description, absenceLimit, AttendanceReports.DEFAULT_MIN_DAYS));

        if (attendanceError != null) {
            card.message(attendanceError);
            return;
        }
        if (attendance == null) {
            card.message(getString(R.string.alerts_loading));
            return;
        }

        List<AttendanceReports.Alert> items = new ArrayList<>();
        for (AttendanceReports.Alert a : attendance.alerts) {
            if (disciplineId.isEmpty() || disciplineId.equals(a.group.disciplineId)) items.add(a);
        }
        card.count(items.size());
        if (items.isEmpty()) {
            card.message(getString(R.string.absences_none, absenceLimit));
            return;
        }
        Ui.fillCollapsible(card, items, expanded.contains("attendance"), (a, parent) -> Ui.row(context, parent,
                a.student.name, a.disciplineName + " · " + a.group.name,
                getString(R.string.absences_detail,
                        Ui.plural(context, a.stats.total(), R.string.history_lessons_one, R.string.history_lessons_many),
                        Ui.plural(context, a.stats.absent + a.stats.excused, R.string.history_absences_one, R.string.history_absences_many)),
                Ui.percent(a.stats.absentPercent()), com.google.android.material.R.attr.colorError,
                v -> openHistory(a.student.id)), () -> toggle("attendance"));
    }
}
