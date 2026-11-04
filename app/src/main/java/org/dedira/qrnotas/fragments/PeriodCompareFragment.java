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
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AutoCompleteTextView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.TextViewCompat;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.appbar.MaterialToolbar;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.util.Background;
import org.dedira.qrnotas.util.PeriodComparison;
import org.dedira.qrnotas.util.ReportLoader;
import org.dedira.qrnotas.util.ThemeColors;
import org.dedira.qrnotas.util.Ui;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * "Comparar períodos": one discipline's students side by side across its closed periods and the
 * current one, with the change since the last period and the class's average, lowest and highest
 * score for each. The phone's version of the browser page's Periods tab, on the same
 * {@link PeriodComparison}: the student names stay in place while the scores scroll sideways.
 */
public class PeriodCompareFragment extends BaseFragment {

    private static final int ROW_HEIGHT_DP = 48;
    private static final int HEAD_HEIGHT_DP = 60;
    private static final int NAME_WIDTH_DP = 148;
    private static final int CELL_WIDTH_DP = 96;

    private final List<Discipline> disciplines = new ArrayList<>();
    private String disciplineId = "";
    private String classGroupId = "";
    private String sort = "name"; // name | current | delta
    private ReportLoader.Comparison data;
    private int request;

    private LinearLayout content;
    private LinearLayout resultBox;

    public PeriodCompareFragment() {
        super(R.layout.screen_scroll);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((MaterialToolbar) view.findViewById(R.id.toolbar)).setTitle(R.string.compare_title);
        content = view.findViewById(R.id.content);

        database.loadAllDisciplines((ok, list) -> {
            if (getView() == null || !ok || list == null) return;
            disciplines.clear();
            disciplines.addAll(list);
            render();
        });
    }

    /* ------------------------------ screen ------------------------------ */

    private void render() {
        Context context = requireContext();
        content.removeAllViews();
        if (disciplines.isEmpty()) {
            Ui.section(context, content, getString(R.string.compare_title)).message(getString(R.string.compare_add_discipline_first));
            return;
        }
        boolean known = false;
        for (Discipline d : disciplines) if (d.id.equals(disciplineId)) known = true;
        if (!known) {
            disciplineId = disciplines.get(0).id;
            classGroupId = "";
        }

        Ui.Section filters = Ui.section(context, content, getString(R.string.compare_filters));
        List<String> names = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < disciplines.size(); i++) {
            names.add(disciplines.get(i).name);
            if (disciplines.get(i).id.equals(disciplineId)) selected = i;
        }
        AutoCompleteTextView discipline = Ui.dropdown(context, filters.header, getString(R.string.compare_discipline_hint));
        Ui.setDropdown(discipline, names, selected);
        discipline.setOnItemClickListener((parent, v, position, id) -> {
            disciplineId = disciplines.get(position).id;
            classGroupId = "";
            data = null;
            render();
        });

        // The classes are only known once the comparison arrives; the field is filled then.
        AutoCompleteTextView group = Ui.dropdown(context, filters.header, getString(R.string.compare_group_hint));
        fillGroups(group);

        List<String> sorts = new ArrayList<>();
        sorts.add(getString(R.string.compare_sort_name));
        sorts.add(getString(R.string.compare_sort_current));
        sorts.add(getString(R.string.compare_sort_delta));
        AutoCompleteTextView sortField = Ui.dropdown(context, filters.header, getString(R.string.compare_sort_hint));
        Ui.setDropdown(sortField, sorts, sort.equals("current") ? 1 : sort.equals("delta") ? 2 : 0);
        sortField.setOnItemClickListener((parent, v, position, id) -> {
            sort = position == 1 ? "current" : position == 2 ? "delta" : "name";
            renderResult();
        });

        resultBox = new LinearLayout(context);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(resultBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        load(group);
    }

    private void fillGroups(AutoCompleteTextView field) {
        List<String> names = new ArrayList<>();
        names.add(getString(R.string.compare_all_groups));
        int selected = 0;
        final List<ClassGroup> groups = data == null ? new ArrayList<ClassGroup>() : data.openGroups;
        for (int i = 0; i < groups.size(); i++) {
            names.add(groups.get(i).name);
            if (groups.get(i).id.equals(classGroupId)) selected = i + 1;
        }
        Ui.setDropdown(field, names, selected);
        field.setOnItemClickListener((parent, v, position, id) -> {
            classGroupId = position == 0 ? "" : groups.get(position - 1).id;
            load(field);
        });
    }

    private void load(AutoCompleteTextView groupField) {
        final int mine = ++request;
        final String forDiscipline = disciplineId;
        final String forGroup = classGroupId;
        resultBox.removeAllViews();
        Ui.section(requireContext(), resultBox, getString(R.string.compare_title)).message(getString(R.string.compare_loading));
        Background.run(() -> ReportLoader.comparison(database, forDiscipline, forGroup.isEmpty() ? null : forGroup), (result, error) -> {
            if (getView() == null || mine != request) return;
            if (error != null || result == null) {
                resultBox.removeAllViews();
                Ui.section(requireContext(), resultBox, getString(R.string.compare_title)).message(getString(R.string.compare_failed));
                return;
            }
            data = result;
            fillGroups(groupField);
            renderResult();
        });
    }

    /* ------------------------------ result ------------------------------ */

    private List<PeriodComparison.Row> sortedRows(List<PeriodComparison.Row> rows) {
        List<PeriodComparison.Row> list = new ArrayList<>(rows);
        final Collator collator = Collator.getInstance(new Locale("pt", "BR"));
        Collections.sort(list, new Comparator<PeriodComparison.Row>() {
            @Override
            public int compare(PeriodComparison.Row a, PeriodComparison.Row b) {
                if (sort.equals("current") || sort.equals("delta")) {
                    Integer x = sort.equals("current") ? a.current : a.delta;
                    Integer y = sort.equals("current") ? b.current : b.delta;
                    // Highest first; students without a figure go last.
                    int byValue = Integer.compare(y == null ? Integer.MIN_VALUE : y, x == null ? Integer.MIN_VALUE : x);
                    if (byValue != 0) return byValue;
                }
                return collator.compare(a.name, b.name);
            }
        });
        return list;
    }

    private String columnTitle(Context context, PeriodComparison.Column c) {
        return c.label == null || c.label.isEmpty() ? Ui.date(context, c.closedAt) : c.label;
    }

    private void renderResult() {
        if (resultBox == null || data == null) return;
        Context context = requireContext();
        resultBox.removeAllViews();
        PeriodComparison table = data.table;

        if (table.columns.isEmpty()) {
            Ui.section(context, resultBox, getString(R.string.compare_title)).message(getString(R.string.compare_no_columns));
            return;
        }
        if (table.rows.isEmpty()) {
            Ui.section(context, resultBox, getString(R.string.compare_title)).message(getString(R.string.compare_no_rows));
            return;
        }

        // "Change" always compares the two latest closed periods: the one in progress is still partial, so it isn't a fair match.
        int n = table.columns.size();
        PeriodComparison.Column last = table.columns.get(n - 1);
        PeriodComparison.Column previous = n >= 2 ? table.columns.get(n - 2) : null;
        PeriodComparison.Stats lastStats = table.columnStats.get(last.key);
        PeriodComparison.Stats previousStats = previous == null ? null : table.columnStats.get(previous.key);
        PeriodComparison.Stats currentStats = table.currentStats;
        boolean hasChange = previousStats != null && previousStats.count > 0 && lastStats.count > 0;

        Ui.Section figures = Ui.section(context, resultBox, getString(R.string.compare_title));
        figures.title.setVisibility(View.GONE);
        List<Ui.Tile> tiles = new ArrayList<>();
        tiles.add(new Ui.Tile(Ui.decimal(lastStats.average), getString(R.string.compare_tile_avg_last, columnTitle(context, last)), 0));
        if (previousStats != null && previousStats.count > 0) {
            tiles.add(new Ui.Tile(Ui.decimal(previousStats.average), getString(R.string.compare_tile_avg_prev, columnTitle(context, previous)), 0));
        }
        if (hasChange) {
            double change = lastStats.average - previousStats.average;
            tiles.add(new Ui.Tile((change > 0 ? "+" : "") + Ui.decimal(change), getString(R.string.compare_tile_avg_change),
                    change < 0 ? com.google.android.material.R.attr.colorError : R.attr.colorSuccess));
        }
        if (currentStats.count > 0) {
            tiles.add(new Ui.Tile(Ui.decimal(currentStats.average), getString(R.string.compare_tile_avg_current), 0));
        }
        Ui.tiles(context, figures.header, tiles);

        Ui.Section tableCard = Ui.section(context, resultBox, getString(R.string.compare_title));
        tableCard.title.setVisibility(View.GONE);
        tableCard.body.addView(buildTable(context, table, previous, last, currentStats));
        TextView note = tableCard.message(getString(previous != null ? R.string.compare_note_with_prev : R.string.compare_note_no_prev));
        TextViewCompat.setTextAppearance(note, R.style.TextAppearance_QrGrades_BodySmall);
    }

    /** Names on the left (fixed), figures on the right (scrolling sideways); equal row heights keep the two halves aligned. */
    private View buildTable(Context context, PeriodComparison table, @Nullable PeriodComparison.Column previous,
                            PeriodComparison.Column last, PeriodComparison.Stats currentStats) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        root.addView(names, new LinearLayout.LayoutParams(dp(NAME_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout figures = new LinearLayout(context);
        figures.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(figures);
        root.addView(scroller, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Header.
        names.addView(cell(context, getString(R.string.compare_col_student), dp(NAME_WIDTH_DP), HEAD_HEIGHT_DP, Gravity.START | Gravity.CENTER_VERTICAL, true, 0));
        LinearLayout head = rowOf(context);
        for (PeriodComparison.Column c : table.columns) {
            head.addView(headerCell(context, columnTitle(context, c), c.label == null || c.label.isEmpty() ? null : Ui.date(context, c.closedAt)));
        }
        head.addView(headerCell(context, getString(R.string.compare_col_current), null));
        if (previous != null) {
            head.addView(headerCell(context, getString(R.string.compare_col_delta), columnTitle(context, previous) + " → " + columnTitle(context, last)));
        }
        figures.addView(head);

        // Students.
        for (PeriodComparison.Row r : sortedRows(table.rows)) {
            final String studentId = r.studentId;
            TextView name = cell(context, r.name, dp(NAME_WIDTH_DP), ROW_HEIGHT_DP, Gravity.START | Gravity.CENTER_VERTICAL, false, 0);
            name.setOnClickListener(v -> openHistory(studentId));
            names.addView(name);

            LinearLayout line = rowOf(context);
            for (PeriodComparison.Column c : table.columns) {
                Integer v = r.values.get(c.key);
                line.addView(figureCell(context, v == null ? "—" : String.valueOf(v), false, v == null ? com.google.android.material.R.attr.colorOutline : 0));
            }
            line.addView(figureCell(context, r.current == null ? "—" : String.valueOf(r.current), r.current != null,
                    r.current == null ? com.google.android.material.R.attr.colorOutline : 0));
            if (previous != null) {
                Integer delta = r.delta;
                @AttrRes int color = delta == null || delta == 0 ? com.google.android.material.R.attr.colorOutline
                        : delta < 0 ? com.google.android.material.R.attr.colorError : R.attr.colorSuccess;
                line.addView(figureCell(context, delta == null ? "—" : Ui.signed(delta), false, color));
            }
            line.setOnClickListener(v -> openHistory(studentId));
            figures.addView(line);
        }

        // Class statistics.
        addFootRow(context, names, figures, table, previous != null, currentStats, getString(R.string.compare_foot_avg), 0);
        addFootRow(context, names, figures, table, previous != null, currentStats, getString(R.string.compare_foot_min), 1);
        addFootRow(context, names, figures, table, previous != null, currentStats, getString(R.string.compare_foot_max), 2);
        addFootRow(context, names, figures, table, previous != null, currentStats, getString(R.string.compare_foot_count), 3);
        return root;
    }

    private void addFootRow(Context context, LinearLayout names, LinearLayout figures, PeriodComparison table, boolean hasDelta,
                            PeriodComparison.Stats currentStats, String label, int what) {
        names.addView(cell(context, label, dp(NAME_WIDTH_DP), ROW_HEIGHT_DP, Gravity.START | Gravity.CENTER_VERTICAL, true, 0));
        LinearLayout line = rowOf(context);
        for (PeriodComparison.Column c : table.columns) line.addView(figureCell(context, stat(table.columnStats.get(c.key), what), false, 0));
        line.addView(figureCell(context, stat(currentStats, what), false, 0));
        if (hasDelta) line.addView(figureCell(context, "", false, 0));
        figures.addView(line);
    }

    private String stat(PeriodComparison.Stats s, int what) {
        if (s == null || s.count == 0) return "—";
        switch (what) {
            case 0:
                return Ui.decimal(s.average);
            case 1:
                return String.valueOf(s.min);
            case 2:
                return String.valueOf(s.max);
            default:
                return String.valueOf(s.count);
        }
    }

    private LinearLayout rowOf(Context context) {
        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        return line;
    }

    private TextView cell(Context context, CharSequence text, int widthPx, int heightDp, int gravity, boolean bold, @AttrRes int color) {
        TextView view = new TextView(context);
        TextViewCompat.setTextAppearance(view, R.style.TextAppearance_QrGrades_BodyMedium);
        view.setText(text);
        view.setGravity(gravity);
        view.setMaxLines(2);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setTextColor(ThemeColors.resolve(context, color != 0 ? color : com.google.android.material.R.attr.colorOnSurface));
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        view.setLayoutParams(new LinearLayout.LayoutParams(widthPx, dp(heightDp)));
        view.setPadding(dp(6), 0, dp(6), 0);
        view.setBackgroundResource(R.drawable.bg_table_cell);
        return view;
    }

    private TextView figureCell(Context context, String text, boolean bold, @AttrRes int color) {
        return cell(context, text, dp(CELL_WIDTH_DP), ROW_HEIGHT_DP, Gravity.CENTER, bold, color);
    }

    private View headerCell(Context context, String title, @Nullable String subtitle) {
        TextView view = cell(context, subtitle == null ? title : title + "\n" + subtitle, dp(CELL_WIDTH_DP), HEAD_HEIGHT_DP, Gravity.CENTER, true, 0);
        view.setMaxLines(3);
        view.setTextSize(11);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void openHistory(String studentId) {
        Bundle args = new Bundle();
        args.putString("studentId", studentId);
        NavHostFragment.findNavController(this).navigate(R.id.action_periodCompare_to_studentProgress, args);
    }
}
