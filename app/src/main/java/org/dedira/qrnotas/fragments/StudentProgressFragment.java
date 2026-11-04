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
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AutoCompleteTextView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.Attendance;
import org.dedira.qrnotas.model.AttendanceStats;
import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.StudentReport;
import org.dedira.qrnotas.util.Background;
import org.dedira.qrnotas.util.BitmapConverter;
import org.dedira.qrnotas.util.Exporter;
import org.dedira.qrnotas.util.GoalViews;
import org.dedira.qrnotas.util.PdfPrint;
import org.dedira.qrnotas.util.ReportLoader;
import org.dedira.qrnotas.util.Suggestions;
import org.dedira.qrnotas.util.ThemeColors;
import org.dedira.qrnotas.util.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A student's history, period by period: the open period of each enrollment and every closed
 * period, with points, goals, categories, attendance and every points entry. Entries of the open
 * period can be corrected or cancelled here (the original stays in the history, marked
 * cancelled), and the report card (boletim) can be shared or printed as a PDF. The phone's version
 * of the browser page's History tab, built on the same {@link ReportLoader} so the numbers match.
 * Reached from the student list and from the "Mais" hub; reloads whenever it becomes visible so it
 * always reflects the latest points.
 */
public class StudentProgressFragment extends BaseFragment {

    private String studentId;
    private StudentReport report;
    private String disciplineId = "";
    // "all", "current", or the id of a closed period.
    private String period = "all";
    private boolean loading;

    private LinearLayout detail;
    private View reportButtons;

    public StudentProgressFragment() {
        super(R.layout.activity_student_progress);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        this.studentId = args != null ? args.getString("studentId") : null;
        this.detail = view.findViewById(R.id.detail);
        this.reportButtons = view.findViewById(R.id.reportButtons);

        view.findViewById(R.id.btnReportPdf).setOnClickListener(v -> makeReport(false));
        view.findViewById(R.id.btnReportPrint).setOnClickListener(v -> makeReport(true));
    }

    @Override
    public void onResume() {
        super.onResume();
        load();
    }

    /* ------------------------------ loading ------------------------------ */

    private void load() {
        if (studentId == null || loading) return;
        loading = true;
        if (report == null) {
            detail.removeAllViews();
            Ui.section(requireContext(), detail, getString(R.string.history_loading));
        }
        Background.run(() -> ReportLoader.studentReport(database, studentId), (loaded, error) -> {
            loading = false;
            if (getView() == null) return;
            if (error != null || loaded == null) {
                detail.removeAllViews();
                Ui.section(requireContext(), detail, getString(R.string.progress_title)).message(getString(R.string.history_load_failed));
                return;
            }
            report = loaded;
            bindHeader();
            renderDetail();
        });
    }

    private void bindHeader() {
        View view = requireView();
        ((TextView) view.findViewById(R.id.txtName)).setText(report.student.name);
        Bitmap photo = BitmapConverter.loadBitmap(report.student.photoPath);
        if (photo != null) ((ImageView) view.findViewById(R.id.imgPhoto)).setImageBitmap(photo);
        reportButtons.setVisibility(View.VISIBLE);
    }

    private static String periodTitle(Context context, StudentReport.Bucket p) {
        return !TextUtils.isEmpty(p.label) ? p.label : context.getString(R.string.attendance_period_closed_on, Ui.date(context, p.closedAt));
    }

    /* ------------------------------ screen ------------------------------ */

    private void renderDetail() {
        Context context = requireContext();
        detail.removeAllViews();

        List<StudentReport.Bucket> periods = new ArrayList<>(report.closed);
        Collections.sort(periods, new Comparator<StudentReport.Bucket>() {
            @Override
            public int compare(StudentReport.Bucket a, StudentReport.Bucket b) {
                return Long.compare(b.closedAt, a.closedAt);
            }
        });

        // Discipline choices come from both live enrollments and past periods, so a discipline
        // the student has since left is still selectable.
        Map<String, String> disciplines = new LinkedHashMap<>();
        for (StudentReport.Bucket b : report.current) if (!TextUtils.isEmpty(b.disciplineId)) disciplines.put(b.disciplineId, b.discipline);
        for (StudentReport.Bucket b : periods) if (!TextUtils.isEmpty(b.disciplineId)) disciplines.put(b.disciplineId, b.discipline);
        if (!disciplineId.isEmpty() && !disciplines.containsKey(disciplineId)) disciplineId = "";

        List<StudentReport.Bucket> current = new ArrayList<>();
        for (StudentReport.Bucket b : report.current) if (matchesDiscipline(b)) current.add(b);
        List<StudentReport.Bucket> scopedPeriods = new ArrayList<>();
        for (StudentReport.Bucket b : periods) if (matchesDiscipline(b)) scopedPeriods.add(b);

        if (!period.equals("all") && !period.equals("current")) {
            boolean exists = false;
            for (StudentReport.Bucket p : scopedPeriods) if (p.periodId.equals(period)) exists = true;
            if (!exists) period = "all";
        }
        boolean showCurrent = period.equals("all") || period.equals("current");
        List<StudentReport.Bucket> shownPeriods = new ArrayList<>();
        for (StudentReport.Bucket p : scopedPeriods) if (period.equals("all") || p.periodId.equals(period)) shownPeriods.add(p);

        int currentPoints = 0, closedPoints = 0, present = 0, lessons = 0;
        for (StudentReport.Bucket b : current) {
            currentPoints += b.points;
            present += b.attendance.present;
            lessons += b.attendance.total();
        }
        for (StudentReport.Bucket p : scopedPeriods) closedPoints += p.points;

        renderFilters(context, disciplines, scopedPeriods, currentPoints, closedPoints, present, lessons, scopedPeriods.size());

        if (showCurrent) {
            heading(getString(R.string.history_current_title));
            if (current.isEmpty()) {
                // The heading above already names the block; the card only carries the message.
                Ui.Section empty = Ui.section(context, detail, getString(R.string.history_current_title));
                empty.title.setVisibility(View.GONE);
                empty.message(getString(disciplineId.isEmpty() ? R.string.history_not_enrolled : R.string.history_not_enrolled_discipline));
            }
            for (StudentReport.Bucket b : current) {
                detail.addView(blockView(b, b.discipline + " · " + b.classGroup
                        + (b.classGroupClosed ? " " + getString(R.string.history_class_closed) : ""), null));
            }
        }

        if (!period.equals("current")) {
            if (shownPeriods.isEmpty() && period.equals("all")) {
                heading(getString(R.string.history_closed_title));
                Ui.Section empty = Ui.section(context, detail, getString(R.string.history_closed_title));
                empty.title.setVisibility(View.GONE);
                empty.message(getString(R.string.history_no_closed));
            }
            for (StudentReport.Bucket p : shownPeriods) {
                String where = p.discipline + " · " + p.classGroup + (p.classClose ? " " + getString(R.string.history_class_closed_period) : "");
                String when = periodTitle(context, p) + " · " + getString(R.string.history_closed_on, Ui.date(context, p.closedAt));
                detail.addView(blockView(p, where, when));
            }
        }
    }

    private boolean matchesDiscipline(StudentReport.Bucket b) {
        return disciplineId.isEmpty() || disciplineId.equals(b.disciplineId);
    }

    private void heading(String text) {
        TextView view = new TextView(requireContext());
        TextViewCompat.setTextAppearance(view, R.style.TextAppearance_QrGrades_TitleMedium);
        view.setTextColor(ThemeColors.resolve(requireContext(), com.google.android.material.R.attr.colorOnSurface));
        view.setText(text);
        int margin = getResources().getDimensionPixelSize(R.dimen.spacing_md);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(margin, getResources().getDimensionPixelSize(R.dimen.spacing_sm), margin, 0);
        detail.addView(view, lp);
    }

    private void renderFilters(Context context, Map<String, String> disciplines, List<StudentReport.Bucket> scopedPeriods,
                               int currentPoints, int closedPoints, int present, int lessons, int closedCount) {
        Ui.Section card = Ui.section(context, detail, getString(R.string.history_filter_period));
        card.title.setVisibility(View.GONE);

        // Discipline.
        final List<String> disciplineIds = new ArrayList<>(disciplines.keySet());
        List<String> disciplineNames = new ArrayList<>();
        disciplineNames.add(getString(R.string.history_all_disciplines));
        int disciplineSelected = 0;
        for (int i = 0; i < disciplineIds.size(); i++) {
            disciplineNames.add(disciplines.get(disciplineIds.get(i)));
            if (disciplineIds.get(i).equals(disciplineId)) disciplineSelected = i + 1;
        }
        AutoCompleteTextView discipline = Ui.dropdown(context, card.header, getString(R.string.history_filter_discipline));
        Ui.setDropdown(discipline, disciplineNames, disciplineSelected);
        discipline.setOnItemClickListener((parent, v, position, id) -> {
            disciplineId = position == 0 ? "" : disciplineIds.get(position - 1);
            renderDetail();
        });

        // Period: all, the open one, then one option per closed period (a student in two classes of a
        // discipline has two blocks with the same period, so they share the option).
        final List<String> periodIds = new ArrayList<>();
        List<String> periodNames = new ArrayList<>();
        periodNames.add(getString(R.string.history_all_periods));
        periodNames.add(getString(R.string.history_current_period));
        int periodSelected = period.equals("current") ? 1 : 0;
        Set<String> seen = new HashSet<>();
        for (StudentReport.Bucket p : scopedPeriods) {
            if (!seen.add(p.periodId)) continue;
            periodIds.add(p.periodId);
            periodNames.add(periodTitle(context, p) + " — " + p.discipline);
            if (p.periodId.equals(period)) periodSelected = periodIds.size() + 1;
        }
        AutoCompleteTextView periodField = Ui.dropdown(context, card.header, getString(R.string.history_filter_period));
        Ui.setDropdown(periodField, periodNames, periodSelected);
        periodField.setOnItemClickListener((parent, v, position, id) -> {
            period = position == 0 ? "all" : position == 1 ? "current" : periodIds.get(position - 2);
            renderDetail();
        });

        // The figures.
        LinearLayout tilesBox = new LinearLayout(context);
        tilesBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = getResources().getDimensionPixelSize(R.dimen.spacing_md);
        card.header.addView(tilesBox, lp);
        List<Ui.Tile> tiles = new ArrayList<>();
        tiles.add(new Ui.Tile(String.valueOf(currentPoints), getString(R.string.history_tile_current_points), 0));
        if (lessons > 0) {
            tiles.add(new Ui.Tile(Ui.percent(100.0 * present / lessons),
                    lessons == 1 ? getString(R.string.history_tile_attendance_one) : getString(R.string.history_tile_attendance_many, lessons), 0));
        }
        tiles.add(new Ui.Tile(String.valueOf(closedCount), getString(R.string.history_tile_closed_count), 0));
        tiles.add(new Ui.Tile(String.valueOf(closedPoints), getString(R.string.history_tile_closed_points), 0));
        Ui.tiles(context, tilesBox, tiles);
    }

    /* ------------------------------ one enrollment, one period ------------------------------ */

    private View blockView(StudentReport.Bucket b, String title, @Nullable String periodLine) {
        Context context = requireContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        View block = inflater.inflate(R.layout.item_history_block, detail, false);

        TextView txtPeriod = block.findViewById(R.id.txtBlockPeriod);
        if (periodLine != null) {
            txtPeriod.setText(periodLine);
            txtPeriod.setVisibility(View.VISIBLE);
        }
        ((TextView) block.findViewById(R.id.txtBlockTitle)).setText(title);
        ((TextView) block.findViewById(R.id.txtBlockPoints)).setText(getString(R.string.history_points_badge, b.points));

        // Each goal counts only the points given inside its window; the list comes ordered running, upcoming, finished.
        LinearLayout goals = block.findViewById(R.id.blockGoals);
        for (GoalProgress goal : b.goals) {
            View row = inflater.inflate(R.layout.item_goal_progress, goals, false);
            GoalViews.bind(context, row, goal);
            goals.addView(row);
        }
        if (b.goals.isEmpty()) goals.setVisibility(View.GONE);

        bindTags(block, b);
        bindAttendance(block, b);

        LinearLayout entries = block.findViewById(R.id.blockEntries);
        List<PointsHistory> sorted = new ArrayList<>(b.history);
        Collections.sort(sorted, new Comparator<PointsHistory>() {
            @Override
            public int compare(PointsHistory x, PointsHistory y) {
                return Long.compare(y.createdAt, x.createdAt);
            }
        });
        if (sorted.isEmpty()) {
            TextView none = new TextView(context);
            TextViewCompat.setTextAppearance(none, R.style.TextAppearance_QrGrades_BodyMedium);
            none.setTextColor(ThemeColors.resolve(context, com.google.android.material.R.attr.colorOnSurfaceVariant));
            none.setText(R.string.history_no_entries);
            entries.addView(none);
        }
        for (PointsHistory h : sorted) entries.addView(entryRow(inflater, entries, b, h));
        return block;
    }

    /** Only worth a line when at least one category is actually in use; otherwise it would just repeat the total. */
    private void bindTags(View block, StudentReport.Bucket b) {
        List<StudentReport.Bucket.TagTotal> tags = b.pointsByTag();
        boolean anyNamed = false;
        for (StudentReport.Bucket.TagTotal t : tags) if (!TextUtils.isEmpty(t.tag)) anyNamed = true;
        if (!anyNamed) return;
        List<String> parts = new ArrayList<>();
        for (StudentReport.Bucket.TagTotal t : tags) {
            parts.add((TextUtils.isEmpty(t.tag) ? getString(R.string.history_no_category) : t.tag) + " " + Ui.signed(t.points));
        }
        TextView view = block.findViewById(R.id.txtBlockTags);
        view.setText(getString(R.string.history_by_category, TextUtils.join("  ·  ", parts)));
        view.setVisibility(View.VISIBLE);
    }

    private String plural(int n, int one, int many) {
        return Ui.plural(requireContext(), n, one, many);
    }

    private void bindAttendance(View block, StudentReport.Bucket b) {
        AttendanceStats a = b.attendance;
        if (a == null || a.total() == 0) return;
        String line = getString(R.string.history_attendance) + ": "
                + plural(a.total(), R.string.history_lessons_one, R.string.history_lessons_many) + " · "
                + getString(R.string.history_present_in, a.present, Ui.percent(a.presentPercent())) + " · "
                + plural(a.absent, R.string.history_absences_one, R.string.history_absences_many) + " · "
                + plural(a.excused, R.string.history_excused_one, R.string.history_excused_many);
        TextView view = block.findViewById(R.id.txtBlockAttendance);
        view.setText(line);
        view.setVisibility(View.VISIBLE);

        // The days missed, newest first.
        List<Attendance> missed = new ArrayList<>();
        for (Attendance d : b.attendanceDays) if (d.status != Attendance.STATUS_PRESENT) missed.add(d);
        if (missed.isEmpty()) return;
        Collections.sort(missed, new Comparator<Attendance>() {
            @Override
            public int compare(Attendance x, Attendance y) {
                return y.day.compareTo(x.day);
            }
        });
        List<String> days = new ArrayList<>();
        for (Attendance d : missed) {
            days.add(getString(d.status == Attendance.STATUS_EXCUSED ? R.string.history_missed_excused : R.string.history_missed_absent,
                    Ui.day(requireContext(), d.day)));
        }
        TextView missedView = block.findViewById(R.id.txtBlockMissed);
        missedView.setText(TextUtils.join("   ", days));
        missedView.setVisibility(View.VISIBLE);
    }

    private View entryRow(LayoutInflater inflater, ViewGroup parent, StudentReport.Bucket b, PointsHistory h) {
        Context context = requireContext();
        View row = inflater.inflate(R.layout.item_history_entry, parent, false);
        ((TextView) row.findViewById(R.id.txtEntryDate)).setText(Ui.dateTime(context, h.createdAt));

        TextView points = row.findViewById(R.id.txtEntryPoints);
        points.setText(Ui.signed(h.pointsDelta));
        points.setTextColor(ThemeColors.resolve(context, h.pointsDelta < 0
                ? com.google.android.material.R.attr.colorError : com.google.android.material.R.attr.colorTertiary));

        // "Category · note", or whichever of the two there is.
        String tag = h.tag == null ? "" : h.tag.trim();
        String note = h.note == null ? "" : h.note.trim();
        String line = tag.isEmpty() ? note : (note.isEmpty() ? tag : tag + " · " + note);
        TextView detailView = row.findViewById(R.id.txtEntryDetail);
        if (!line.isEmpty()) {
            detailView.setText(line);
            detailView.setVisibility(View.VISIBLE);
        }

        // A cancelled entry stays in the log (the cancelling entry points back at it): struck through, and labelled.
        TextView status = row.findViewById(R.id.txtEntryStatus);
        if (h.revertsId != null) {
            status.setText(R.string.history_entry_reversal);
            status.setVisibility(View.VISIBLE);
        } else if (h.reverted) {
            status.setText(R.string.history_entry_reverted);
            status.setVisibility(View.VISIBLE);
            points.setPaintFlags(points.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            detailView.setPaintFlags(detailView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        }

        if (b.canRevert(h)) {
            row.findViewById(R.id.entryActions).setVisibility(View.VISIBLE);
            row.findViewById(R.id.btnEntryCorrect).setOnClickListener(v -> correctEntry(h));
            row.findViewById(R.id.btnEntryRevert).setOnClickListener(v -> revertEntry(h));
        }
        return row;
    }

    /* ------------------------------ cancel / correct ------------------------------ */

    private String describe(PointsHistory h) {
        String points = Math.abs(h.pointsDelta) == 1 ? getString(R.string.history_describe_points_one, Ui.signed(h.pointsDelta))
                : getString(R.string.history_describe_points_many, Ui.signed(h.pointsDelta));
        String note = h.note == null ? "" : h.note.trim();
        return note.isEmpty() ? points : getString(R.string.history_describe_with_note, points, note);
    }

    private void snack(String text) {
        View view = getView();
        if (view != null) Snackbar.make(view, text, Snackbar.LENGTH_LONG).show();
    }

    private String revertMessage(String code) {
        switch (code == null ? "" : code) {
            case "NOT_FOUND":
                return getString(R.string.revert_error_not_found);
            case "ALREADY_REVERTED":
                return getString(R.string.revert_error_already);
            case "IS_REVERSAL":
                return getString(R.string.revert_error_reversal);
            case "CLOSED_PERIOD":
                return getString(R.string.revert_error_closed);
            default:
                return getString(R.string.revert_error_failed);
        }
    }

    /** Whatever happened, show the stored state afterwards: someone else (or the browser) may have changed it first. */
    private void afterChange(boolean ok, @Nullable String errorCode, int successMessage) {
        if (getView() == null) return;
        snack(ok ? getString(successMessage) : revertMessage(errorCode));
        load();
    }

    private void revertEntry(PointsHistory h) {
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.history_revert_confirm, describe(h)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.history_revert, (d, w) ->
                        database.revertPointsHistory(h.id, null, (ok, result) ->
                                afterChange(ok, result == null ? null : result.error, R.string.history_reverted_ok)))
                .show();
    }

    private void correctEntry(PointsHistory h) {
        Context context = requireContext();
        View form = LayoutInflater.from(context).inflate(R.layout.dialog_correct_entry, null, false);
        TextInputEditText points = form.findViewById(R.id.editCorrectPoints);
        AutoCompleteTextView note = form.findViewById(R.id.editCorrectNote);
        AutoCompleteTextView tag = form.findViewById(R.id.editCorrectTag);
        points.setText(String.valueOf(h.pointsDelta));
        note.setText(h.note == null ? "" : h.note);
        tag.setText(h.tag == null ? "" : h.tag);
        Suggestions.attachNotes(context, database, note);
        Suggestions.attachTags(context, database, tag);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.history_correct_title)
                .setView(form)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.history_correct, null)
                .create();
        // Sit at the top so the suggestion lists have room above the keyboard instead of opening behind it.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setGravity(android.view.Gravity.TOP);
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        // Set after show() so a missing quantity keeps the dialog open instead of closing it.
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int delta;
            try {
                delta = Integer.parseInt(String.valueOf(points.getText()).trim());
            } catch (NumberFormatException e) {
                delta = 0;
            }
            if (delta == 0) {
                points.setError(getString(R.string.history_correct_need_points));
                return;
            }
            PointsHistory replacement = new PointsHistory();
            replacement.pointsDelta = delta;
            replacement.note = String.valueOf(note.getText()).trim();
            replacement.tag = String.valueOf(tag.getText()).trim();
            dialog.dismiss();
            database.revertPointsHistory(h.id, replacement, (ok, result) ->
                    afterChange(ok, result == null ? null : result.error, R.string.history_corrected_ok));
        }));
        dialog.show();
    }

    /* ------------------------------ report card ------------------------------ */

    /** Makes the report card PDF and either shares it or hands it to the print dialog. */
    private void makeReport(boolean print) {
        if (report == null) return;
        final Context app = requireContext().getApplicationContext();
        final StudentReport snapshot = report;
        final Locale locale = Locale.getDefault();
        Background.run(() -> Exporter.exportStudentReport(app, snapshot, locale), (File file, Exception error) -> {
            if (getView() == null) return;
            if (error != null || file == null) {
                snack(getString(R.string.history_report_failed));
                return;
            }
            if (print) PdfPrint.print(requireActivity(), file, getString(R.string.history_print_job, snapshot.student.name));
            else Exporter.share(requireContext(), file, "application/pdf");
        });
    }
}
