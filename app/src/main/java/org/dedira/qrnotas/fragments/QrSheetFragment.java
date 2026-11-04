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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.util.Background;
import org.dedira.qrnotas.util.Exporter;
import org.dedira.qrnotas.util.PdfPrint;
import org.dedira.qrnotas.util.QrSheet;
import org.dedira.qrnotas.util.Ui;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Códigos QR": picks the students whose codes to print (all, or just one discipline's), shows
 * the short label each code gets, and makes the sheet as a PDF to print or share. A lost code can
 * be replaced on the spot — the old one stops working at once. The phone's version of the browser
 * page's QR codes tab.
 */
public class QrSheetFragment extends BaseFragment {

    private final List<Discipline> disciplines = new ArrayList<>();
    private List<Student> students = new ArrayList<>();
    private List<StudentExportData> enrollments = new ArrayList<>();
    private String disciplineId = "";
    // Kept across discipline changes: a student unticked once stays unticked.
    private final Set<String> excluded = new HashSet<>();

    private LinearLayout content;
    private Ui.Section listCard;
    private TextView txtSelected;

    public QrSheetFragment() {
        super(R.layout.screen_scroll);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((MaterialToolbar) view.findViewById(R.id.toolbar)).setTitle(R.string.qrsheet_title);
        content = view.findViewById(R.id.content);
        load();
    }

    private void load() {
        database.loadAllDisciplines((ok1, ds) -> {
            if (getView() == null || !ok1 || ds == null) return;
            database.loadAllStudents((ok2, list) -> {
                if (getView() == null || !ok2 || list == null) return;
                database.loadExportData(null, (ok3, rows) -> {
                    if (getView() == null || !ok3 || rows == null) return;
                    disciplines.clear();
                    disciplines.addAll(ds);
                    students = list;
                    enrollments = rows;
                    render();
                });
            });
        });
    }

    /** The students the discipline filter lets through, by name. */
    private List<Student> candidates() {
        List<Student> out = new ArrayList<>();
        Set<String> enrolled = new HashSet<>();
        for (StudentExportData row : enrollments) if (row.disciplineId.equals(disciplineId)) enrolled.add(row.studentId);
        for (Student s : students) if (disciplineId.isEmpty() || enrolled.contains(s.id)) out.add(s);
        final Collator collator = Collator.getInstance(new Locale("pt", "BR"));
        Collections.sort(out, new Comparator<Student>() {
            @Override
            public int compare(Student a, Student b) {
                return collator.compare(a.name, b.name);
            }
        });
        return out;
    }

    private List<Student> selected() {
        List<Student> out = new ArrayList<>();
        for (Student s : candidates()) if (!excluded.contains(s.id)) out.add(s);
        return out;
    }

    /* ------------------------------ screen ------------------------------ */

    private void render() {
        Context context = requireContext();
        content.removeAllViews();
        if (students.isEmpty()) {
            Ui.section(context, content, getString(R.string.qrsheet_title)).message(getString(R.string.qrsheet_need_student));
            return;
        }

        Ui.Section top = Ui.section(context, content, getString(R.string.qrsheet_filters)).describe(getString(R.string.qrsheet_hint));
        List<String> names = new ArrayList<>();
        names.add(getString(R.string.qrsheet_all_students));
        int selectedIndex = 0;
        for (int i = 0; i < disciplines.size(); i++) {
            names.add(disciplines.get(i).name);
            if (disciplines.get(i).id.equals(disciplineId)) selectedIndex = i + 1;
        }
        AutoCompleteTextView discipline = Ui.dropdown(context, top.header, getString(R.string.qrsheet_discipline_hint));
        Ui.setDropdown(discipline, names, selectedIndex);
        discipline.setOnItemClickListener((parent, v, position, id) -> {
            disciplineId = position == 0 ? "" : disciplines.get(position - 1).id;
            renderList();
        });

        LinearLayout tools = new LinearLayout(context);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.addView(toolButton(context, R.string.qrsheet_select_all, false, v -> {
            for (Student s : candidates()) excluded.remove(s.id);
            renderList();
        }));
        tools.addView(toolButton(context, R.string.qrsheet_select_none, false, v -> {
            for (Student s : candidates()) excluded.add(s.id);
            renderList();
        }));
        LinearLayout.LayoutParams toolsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toolsParams.topMargin = getResources().getDimensionPixelSize(R.dimen.spacing_sm);
        top.header.addView(tools, toolsParams);

        txtSelected = top.headerMessage("");

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(toolButton(context, R.string.qrsheet_print, true, v -> makePdf(selected(), true)));
        actions.addView(toolButton(context, R.string.qrsheet_share, false, v -> makePdf(selected(), false)));
        top.header.addView(actions, toolsParams);

        LinearLayout listBox = new LinearLayout(context);
        listBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(listBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        listCard = Ui.section(context, listBox, getString(R.string.qrsheet_title));
        listCard.title.setVisibility(View.GONE);
        renderList();
    }

    private MaterialButton toolButton(Context context, int text, boolean filled, View.OnClickListener onClick) {
        MaterialButton button = new MaterialButton(context, null, filled
                ? com.google.android.material.R.attr.materialButtonStyle
                : com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(text);
        button.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(getResources().getDimensionPixelSize(R.dimen.spacing_sm));
        button.setLayoutParams(lp);
        return button;
    }

    private void updateSelected() {
        if (txtSelected != null) txtSelected.setText(getString(R.string.qrsheet_selected, selected().size(), candidates().size()));
    }

    private void renderList() {
        Context context = requireContext();
        listCard.body.removeAllViews();
        List<Student> candidates = candidates();
        if (candidates.isEmpty()) listCard.message(getString(R.string.qrsheet_empty_filter));

        LayoutInflater inflater = LayoutInflater.from(context);
        for (Student s : candidates) {
            View row = inflater.inflate(R.layout.item_qr_student, listCard.body, false);
            MaterialCheckBox box = row.findViewById(R.id.chkQrStudent);
            box.setChecked(!excluded.contains(s.id));
            ((TextView) row.findViewById(R.id.txtQrName)).setText(s.name);
            String preview = QrSheet.label(s.name);
            ((TextView) row.findViewById(R.id.txtQrLabel)).setText(s.qrToken != null
                    ? preview + " · " + getString(R.string.qrsheet_regenerated_marker) : preview);
            row.setOnClickListener(v -> {
                if (excluded.remove(s.id)) box.setChecked(true);
                else {
                    excluded.add(s.id);
                    box.setChecked(false);
                }
                updateSelected();
            });
            row.findViewById(R.id.btnQrRegenerate).setOnClickListener(v -> confirmRegenerate(s));
            listCard.body.addView(row);
        }
        updateSelected();
    }

    /* ------------------------------ PDF ------------------------------ */

    private void snack(String text) {
        View view = getView();
        if (view != null) Snackbar.make(view, text, Snackbar.LENGTH_LONG).show();
    }

    /** Makes the sheet for these students and prints or shares it. */
    private void makePdf(final List<Student> chosen, final boolean print) {
        if (chosen.isEmpty()) {
            snack(getString(R.string.qrsheet_none_selected));
            return;
        }
        final Context app = requireContext().getApplicationContext();
        Background.run(() -> QrSheet.writePdf(app, chosen), (File file, Exception error) -> {
            if (getView() == null) return;
            if (error != null || file == null) {
                snack(getString(R.string.qrsheet_pdf_failed));
                return;
            }
            if (print) PdfPrint.print(requireActivity(), file, getString(R.string.qrsheet_print_job));
            else Exporter.share(requireContext(), file, "application/pdf");
        });
    }

    /* ------------------------------ regenerate ------------------------------ */

    /** Asks first: the printed code stops working the moment the new one is issued. */
    private void confirmRegenerate(Student s) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.regenerate_qr_title)
                .setMessage(getString(R.string.regenerate_qr_message, s.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.regenerate_qr_confirm, (d, w) -> database.regenerateQrToken(s.id, (ok, updated) -> {
                    if (getView() == null) return;
                    if (!ok || updated == null) {
                        snack(getString(R.string.regenerate_qr_failed));
                        return;
                    }
                    s.qrToken = updated.qrToken;
                    renderList();
                    // The new code is the one to hand out: offer to print just it.
                    Snackbar.make(requireView(), getString(R.string.regenerate_qr_done, s.name), 12000)
                            .setAction(R.string.qrsheet_print, v -> makePdf(Collections.singletonList(s), true)).show();
                }))
                .show();
    }
}
