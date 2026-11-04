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
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.util.PointsChartView;
import org.dedira.qrnotas.util.TextSearch;
import org.dedira.qrnotas.util.Ui;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * "Visão geral": a discipline's ranking (the ten highest scores), how its points built up over
 * time, and every enrollment with its points. The phone's version of the browser page's Overview
 * tab; tapping a student opens their history.
 */
public class OverviewFragment extends BaseFragment {

    private static final int TOP = 10;

    private final List<Discipline> disciplines = new ArrayList<>();
    private List<StudentExportData> rows = new ArrayList<>();
    private String disciplineId = "";
    private String query = "";
    private boolean allExpanded;

    private LinearLayout content;
    private Ui.Section rankingCard;
    private Ui.Section chartCard;
    private Ui.Section allCard;
    private boolean loaded;

    public OverviewFragment() {
        super(R.layout.screen_scroll);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((MaterialToolbar) view.findViewById(R.id.toolbar)).setTitle(R.string.overview_title);
        content = view.findViewById(R.id.content);
        // The fragment outlives its view (opening a student's history destroys it), so what was
        // drawn into the old one must not be mistaken for the new, empty one.
        loaded = false;
        rankingCard = null;
        chartCard = null;
        allCard = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        database.loadAllDisciplines((ok1, ds) -> {
            if (getView() == null || !ok1 || ds == null) return;
            database.loadExportData(null, (ok2, data) -> {
                if (getView() == null || !ok2 || data == null) return;
                disciplines.clear();
                disciplines.addAll(ds);
                rows = data;
                if (!loaded) render();
                else renderSections();
                loaded = true;
            });
        });
    }

    /* ------------------------------ screen ------------------------------ */

    private void render() {
        Context context = requireContext();
        content.removeAllViews();
        if (disciplines.isEmpty()) {
            Ui.section(context, content, getString(R.string.overview_title)).message(getString(R.string.overview_add_discipline_first));
            return;
        }
        boolean known = false;
        for (Discipline d : disciplines) if (d.id.equals(disciplineId)) known = true;
        if (!known) disciplineId = disciplines.get(0).id;

        // The ranking card carries the discipline choice; the chart below follows it.
        rankingCard = Ui.section(context, content, getString(R.string.overview_ranking));
        List<String> names = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < disciplines.size(); i++) {
            names.add(disciplines.get(i).name);
            if (disciplines.get(i).id.equals(disciplineId)) selected = i;
        }
        AutoCompleteTextView discipline = Ui.dropdown(context, rankingCard.header, getString(R.string.overview_discipline_hint));
        Ui.setDropdown(discipline, names, selected);
        discipline.setOnItemClickListener((parent, v, position, id) -> {
            disciplineId = disciplines.get(position).id;
            renderRanking();
            renderChart();
        });

        chartCard = Ui.section(context, content, getString(R.string.overview_chart_title));

        // Every enrollment, with a search box that stays put while the list under it changes.
        allCard = Ui.section(context, content, getString(R.string.overview_all_title));
        TextInputEditText search = Ui.searchField(context, allCard.header, getString(R.string.overview_search_hint), query);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                renderAll();
            }
        });

        renderSections();
    }

    private void renderSections() {
        renderRanking();
        renderChart();
        renderAll();
    }

    private void openHistory(String studentId) {
        Bundle args = new Bundle();
        args.putString("studentId", studentId);
        NavHostFragment.findNavController(this).navigate(R.id.action_overview_to_studentProgress, args);
    }

    private List<StudentExportData> scoped() {
        List<StudentExportData> out = new ArrayList<>();
        for (StudentExportData r : rows) if (disciplineId.equals(r.disciplineId)) out.add(r);
        return out;
    }

    /* ------------------------------ ranking ------------------------------ */

    private void renderRanking() {
        if (rankingCard == null) return;
        Context context = requireContext();
        LinearLayout box = rankingCard.body;
        box.removeAllViews();

        List<StudentExportData> top = scoped();
        Collections.sort(top, new Comparator<StudentExportData>() {
            @Override
            public int compare(StudentExportData a, StudentExportData b) {
                return Integer.compare(b.points, a.points);
            }
        });
        if (top.size() > TOP) top = new ArrayList<>(top.subList(0, TOP));
        if (top.isEmpty()) {
            rankingCard.message(getString(R.string.overview_no_data));
            return;
        }

        int max = 1;
        for (StudentExportData r : top) max = Math.max(max, r.points);
        LayoutInflater inflater = LayoutInflater.from(context);
        for (StudentExportData r : top) {
            View line = inflater.inflate(R.layout.item_rank_bar, box, false);
            ((TextView) line.findViewById(R.id.txtRankName)).setText(r.studentName);
            ((TextView) line.findViewById(R.id.txtRankPoints)).setText(String.valueOf(r.points));
            LinearProgressIndicator bar = line.findViewById(R.id.barRank);
            bar.setMax(max);
            // A tiny score still shows a sliver of bar, and a negative one shows none.
            bar.setProgressCompat(Math.max(r.points > 0 ? Math.max(1, max / 50) : 0, r.points), false);
            line.setOnClickListener(v -> openHistory(r.studentId));
            box.addView(line);
        }
    }

    /* ------------------------------ points over time ------------------------------ */

    private void renderChart() {
        if (chartCard == null) return;
        Context context = requireContext();
        chartCard.body.removeAllViews();

        List<PointsHistory> entries = new ArrayList<>();
        for (StudentExportData r : scoped()) if (r.history != null) entries.addAll(r.history);
        if (entries.isEmpty()) {
            chartCard.message(getString(R.string.overview_no_history));
            return;
        }
        Collections.sort(entries, new Comparator<PointsHistory>() {
            @Override
            public int compare(PointsHistory a, PointsHistory b) {
                return Long.compare(a.createdAt, b.createdAt);
            }
        });
        long[] times = new long[entries.size()];
        int[] totals = new int[entries.size()];
        int running = 0;
        for (int i = 0; i < entries.size(); i++) {
            running += entries.get(i).pointsDelta;
            times[i] = entries.get(i).createdAt;
            totals[i] = running;
        }
        PointsChartView chart = new PointsChartView(context);
        chart.setSeries(times, totals);
        chart.setContentDescription(getString(R.string.overview_chart_description));
        chartCard.body.addView(chart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /* ------------------------------ all enrollments ------------------------------ */

    private void renderAll() {
        if (allCard == null) return;
        Context context = requireContext();

        String needle = TextSearch.normalize(query.trim());
        List<StudentExportData> matches = new ArrayList<>();
        for (StudentExportData r : rows) {
            if (needle.isEmpty() || TextSearch.normalize(r.studentName).contains(needle)) matches.add(r);
        }
        final Collator collator = Collator.getInstance(new Locale("pt", "BR"));
        Collections.sort(matches, new Comparator<StudentExportData>() {
            @Override
            public int compare(StudentExportData a, StudentExportData b) {
                int byName = collator.compare(a.studentName, b.studentName);
                return byName != 0 ? byName : collator.compare(a.disciplineName, b.disciplineName);
            }
        });

        allCard.body.removeAllViews();
        allCard.more.setVisibility(View.GONE);
        if (matches.isEmpty()) {
            allCard.message(getString(R.string.overview_no_match, query.trim()));
            return;
        }
        allCard.count(matches.size());
        Ui.fillCollapsible(allCard, matches, allExpanded, (r, parent) -> Ui.row(context, parent, r.studentName,
                r.disciplineName + " · " + r.classGroupName, null, String.valueOf(r.points), 0, v -> openHistory(r.studentId)),
                () -> {
                    allExpanded = !allExpanded;
                    renderAll();
                });
    }
}
