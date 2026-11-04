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
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.Attendance;
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.PointsAward;
import org.dedira.qrnotas.model.RevertResult;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.util.Background;
import org.dedira.qrnotas.util.DatabaseSync;
import org.dedira.qrnotas.util.Suggestions;
import org.dedira.qrnotas.util.TextSearch;
import org.dedira.qrnotas.util.Ui;

import java.text.Collator;
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
 * "Lançar pontos em lote": the same points, with the same note and category, for several students
 * of a class at once — "everyone handed in the assignment". The whole batch is saved together or
 * not at all, and can be undone as a batch (each entry is cancelled, not erased, so the students'
 * history keeps the trail). The phone's version of the browser page's "Lançar pontos" tab.
 */
public class BatchPointsFragment extends BaseFragment {

    // Generous enough for any real classroom, small enough to stop a mistyped field from doing real damage
    // (the same limits the browser API applies).
    private static final int MAX_DELTA = 1000;
    private static final int MAX_BATCH = 500;
    private static final int[] PRESETS = {1, 2, 5, 10, -1, -5};

    /** One batch given in this session. */
    private static final class Batch {
        long at;
        String groupLabel;
        int delta;
        String note;
        String tag;
        int count;
        List<String> historyIds;
        boolean undone;
    }

    // Batches survive leaving the screen (like the browser page's), until the app process ends.
    private static final List<Batch> SESSION_BATCHES = new ArrayList<>();

    private final List<Discipline> disciplines = new ArrayList<>();
    private final List<ClassGroup> groups = new ArrayList<>();
    private List<StudentExportData> allRows = new ArrayList<>();

    private final List<ClassGroup> pickableGroups = new ArrayList<>();
    private final Map<String, String> groupLabels = new LinkedHashMap<>();
    private String classGroupId = "";
    private String selectedFor;               // the class `selected` belongs to; another one starts with everybody ticked
    private final Set<String> selected = new HashSet<>(); // enrollment ids
    private String filter = "";
    private boolean busy;
    private boolean bound;

    private AutoCompleteTextView dropdownGroup;
    private TextInputEditText editPoints;
    private AutoCompleteTextView editNote;
    private AutoCompleteTextView editTag;
    private TextInputEditText editFilter;
    private LinearLayout studentList;
    private TextView txtPreview;
    private MaterialButton btnSubmit;
    private LinearLayout sessionBatches;

    public BatchPointsFragment() {
        super(R.layout.fragment_batch_points);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dropdownGroup = view.findViewById(R.id.dropdownGroup);
        editPoints = view.findViewById(R.id.editPoints);
        editNote = view.findViewById(R.id.editNote);
        editTag = view.findViewById(R.id.editTag);
        editFilter = view.findViewById(R.id.editFilter);
        studentList = view.findViewById(R.id.studentList);
        txtPreview = view.findViewById(R.id.txtPreview);
        btnSubmit = view.findViewById(R.id.btnSubmit);
        sessionBatches = view.findViewById(R.id.sessionBatches);

        Suggestions.attachNotes(requireContext(), database, editNote);
        Suggestions.attachTags(requireContext(), database, editTag);

        // The presets: tap one to fill the points field.
        ChipGroup presets = view.findViewById(R.id.presets);
        for (int preset : PRESETS) {
            Chip chip = new Chip(requireContext());
            chip.setText(Ui.signed(preset));
            chip.setCheckable(false);
            chip.setOnClickListener(v -> {
                editPoints.setText(String.valueOf(preset));
                editPoints.setSelection(editPoints.length());
                updatePreview();
            });
            presets.addView(chip);
        }

        editPoints.addTextChangedListener(new SimpleWatcher(this::updatePreview));
        editFilter.addTextChangedListener(new SimpleWatcher(() -> {
            filter = String.valueOf(editFilter.getText());
            renderStudents();
        }));

        view.findViewById(R.id.btnSelectAll).setOnClickListener(v -> applySelection("all"));
        view.findViewById(R.id.btnSelectNone).setOnClickListener(v -> applySelection("none"));
        view.findViewById(R.id.btnSelectInvert).setOnClickListener(v -> applySelection("invert"));
        view.findViewById(R.id.btnSelectPresent).setOnClickListener(v -> applySelection("present"));
        btnSubmit.setOnClickListener(v -> submit());

        // A new view loads once, right here; onResume only refreshes a screen that was already showing.
        bound = false;
        load();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Points may have changed elsewhere (the scan screen, the browser) while this one was hidden.
        if (bound) load();
    }

    /* ------------------------------ loading ------------------------------ */

    private void load() {
        database.loadAllDisciplines((ok1, ds) -> {
            if (getView() == null || !ok1 || ds == null) return;
            database.loadAllClassGroups((ok2, gs) -> {
                if (getView() == null || !ok2 || gs == null) return;
                database.loadExportData(null, (ok3, rows) -> {
                    if (getView() == null || !ok3 || rows == null) return;
                    disciplines.clear();
                    disciplines.addAll(ds);
                    groups.clear();
                    groups.addAll(gs);
                    allRows = rows;
                    bind();
                });
            });
        });
    }

    private void bind() {
        bound = true;
        View view = requireView();

        // "Discipline · Class", so two classes with the same name in different disciplines can be told apart.
        pickableGroups.clear();
        groupLabels.clear();
        for (Discipline d : disciplines) {
            for (ClassGroup g : groups) {
                if (g.disciplineId.equals(d.id) && !g.isClosed()) {
                    pickableGroups.add(g);
                    groupLabels.put(g.id, d.name + " · " + g.name);
                }
            }
        }

        boolean any = !pickableGroups.isEmpty();
        view.findViewById(R.id.txtEmpty).setVisibility(any ? View.GONE : View.VISIBLE);
        view.findViewById(R.id.formCard).setVisibility(any ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.studentsCard).setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any) {
            renderBatches();
            return;
        }

        if (!groupLabels.containsKey(classGroupId)) classGroupId = pickableGroups.get(0).id;
        List<String> labels = new ArrayList<>(groupLabels.values());
        int index = 0;
        for (int i = 0; i < pickableGroups.size(); i++) if (pickableGroups.get(i).id.equals(classGroupId)) index = i;
        Ui.setDropdown(dropdownGroup, labels, index);
        dropdownGroup.setOnItemClickListener((parent, v, position, id) -> {
            classGroupId = pickableGroups.get(position).id;
            filter = "";
            editFilter.setText("");
            renderStudents();
        });

        renderStudents();
        renderBatches();
    }

    /* ------------------------------ students ------------------------------ */

    /** Enrollments of the chosen class, by name. */
    private List<StudentExportData> rowsOfGroup() {
        List<StudentExportData> rows = new ArrayList<>();
        for (StudentExportData r : allRows) {
            if (classGroupId.equals(r.classGroupId) && !r.classGroupClosed) rows.add(r);
        }
        final Collator collator = Collator.getInstance(new Locale("pt", "BR"));
        Collections.sort(rows, new Comparator<StudentExportData>() {
            @Override
            public int compare(StudentExportData a, StudentExportData b) {
                return collator.compare(a.studentName, b.studentName);
            }
        });
        return rows;
    }

    private void renderStudents() {
        List<StudentExportData> rows = rowsOfGroup();
        if (!classGroupId.equals(selectedFor)) {
            selected.clear();
            for (StudentExportData r : rows) selected.add(r.enrollmentId);
            selectedFor = classGroupId;
        }

        studentList.removeAllViews();
        String query = TextSearch.normalize(filter.trim());
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        int visible = 0;
        for (StudentExportData r : rows) {
            if (!query.isEmpty() && !TextSearch.normalize(r.studentName).contains(query)) continue;
            visible++;
            View row = inflater.inflate(R.layout.item_batch_student, studentList, false);
            MaterialCheckBox box = row.findViewById(R.id.chkStudent);
            box.setChecked(selected.contains(r.enrollmentId));
            ((TextView) row.findViewById(R.id.txtStudentName)).setText(r.studentName);
            ((TextView) row.findViewById(R.id.txtStudentPoints)).setText(String.valueOf(r.points));
            row.setOnClickListener(v -> {
                if (selected.remove(r.enrollmentId)) box.setChecked(false);
                else {
                    selected.add(r.enrollmentId);
                    box.setChecked(true);
                }
                updatePreview();
            });
            studentList.addView(row);
        }

        if (rows.isEmpty()) {
            studentList.addView(message(getString(R.string.batch_no_students)));
        } else if (visible == 0) {
            studentList.addView(message(getString(R.string.batch_no_match, filter.trim())));
        }
        updatePreview();
    }

    private TextView message(String text) {
        TextView view = new TextView(requireContext());
        TextViewCompat.setTextAppearance(view, R.style.TextAppearance_QrGrades_BodyMedium);
        view.setText(text);
        view.setPadding(0, getResources().getDimensionPixelSize(R.dimen.spacing_sm), 0, getResources().getDimensionPixelSize(R.dimen.spacing_sm));
        return view;
    }

    private void applySelection(String mode) {
        final List<StudentExportData> rows = rowsOfGroup();
        if (mode.equals("present")) {
            database.loadAttendanceForDay(classGroupId, Attendance.today(), (ok, records) -> {
                if (getView() == null) return;
                if (!ok || records == null || records.isEmpty()) {
                    snack(getString(R.string.batch_no_attendance_today));
                    return;
                }
                Set<String> presentStudents = new HashSet<>();
                for (Attendance a : records) {
                    if (a.status == Attendance.STATUS_PRESENT) presentStudents.add(a.studentId);
                }
                selected.clear();
                for (StudentExportData r : rows) if (presentStudents.contains(r.studentId)) selected.add(r.enrollmentId);
                renderStudents();
            });
            return;
        }
        for (StudentExportData r : rows) {
            switch (mode) {
                case "all":
                    selected.add(r.enrollmentId);
                    break;
                case "none":
                    selected.remove(r.enrollmentId);
                    break;
                default: // invert
                    if (!selected.remove(r.enrollmentId)) selected.add(r.enrollmentId);
            }
        }
        renderStudents();
    }

    private List<String> selectedIds() {
        List<String> ids = new ArrayList<>();
        for (StudentExportData r : rowsOfGroup()) if (selected.contains(r.enrollmentId)) ids.add(r.enrollmentId);
        return ids;
    }

    /** The typed points, or 0 when the field is empty or not a number. */
    private int typedDelta() {
        try {
            return Integer.parseInt(String.valueOf(editPoints.getText()).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String pointsText(int delta) {
        return Math.abs(delta) == 1 ? getString(R.string.batch_points_unit_one, Ui.signed(delta))
                : getString(R.string.batch_points_unit_many, Ui.signed(delta));
    }

    private void updatePreview() {
        if (txtPreview == null || getView() == null) return;
        int total = rowsOfGroup().size();
        String who = getString(R.string.batch_preview_selected, selectedIds().size(), total);
        int delta = typedDelta();
        txtPreview.setText(delta != 0 ? getString(R.string.batch_preview_with_points, pointsText(delta), who) : who);
    }

    /* ------------------------------ saving ------------------------------ */

    private void snack(String text) {
        View view = getView();
        if (view != null) Snackbar.make(view, text, Snackbar.LENGTH_LONG).show();
    }

    private void submit() {
        if (busy) return; // a double tap must not give the points twice
        int delta = typedDelta();
        List<String> ids = selectedIds();
        if (delta == 0) {
            snack(getString(R.string.batch_need_points));
            return;
        }
        if (Math.abs(delta) > MAX_DELTA) {
            snack(getString(R.string.batch_points_too_big, MAX_DELTA));
            return;
        }
        if (ids.isEmpty()) {
            snack(getString(R.string.batch_need_students));
            return;
        }
        if (ids.size() > MAX_BATCH) {
            snack(getString(R.string.batch_too_many, MAX_BATCH));
            return;
        }

        final String note = String.valueOf(editNote.getText()).trim();
        final String tag = String.valueOf(editTag.getText()).trim();
        busy = true;
        btnSubmit.setEnabled(false);

        database.awardPointsBatch(ids, delta, note, tag, (ok, awards) -> {
            busy = false;
            if (getView() == null) return;
            btnSubmit.setEnabled(true);
            if (!ok || awards == null || awards.isEmpty()) {
                snack(getString(R.string.batch_failed));
                return;
            }

            Batch batch = new Batch();
            batch.at = System.currentTimeMillis();
            batch.groupLabel = groupLabels.get(classGroupId);
            batch.delta = delta;
            batch.note = note;
            batch.tag = tag;
            batch.count = awards.size();
            batch.historyIds = new ArrayList<>();
            for (PointsAward award : awards) batch.historyIds.add(award.history.id);
            SESSION_BATCHES.add(0, batch);

            // Ready for the next batch: nothing typed, everybody ticked again.
            editPoints.setText("");
            editNote.setText("");
            editTag.setText("");
            selectedFor = null;

            String done = batch.count == 1 ? getString(R.string.batch_done_one, pointsText(delta))
                    : getString(R.string.batch_done, pointsText(delta), batch.count);
            Snackbar.make(requireView(), done, Snackbar.LENGTH_LONG)
                    .setAction(R.string.batch_undo, v -> undoBatch(batch, true)).show();
            load();
        });
    }

    /* ------------------------------ this session's batches ------------------------------ */

    private void renderBatches() {
        sessionBatches.removeAllViews();
        if (SESSION_BATCHES.isEmpty()) return;
        Context context = requireContext();
        Ui.Section card = Ui.section(context, sessionBatches, getString(R.string.batch_session_title))
                .describe(getString(R.string.batch_session_hint));
        for (Batch b : SESSION_BATCHES) {
            List<String> details = new ArrayList<>();
            details.add(b.count == 1 ? getString(R.string.batch_session_details_one) : getString(R.string.batch_session_details, b.count));
            if (!b.tag.isEmpty()) details.add(b.tag);
            if (!b.note.isEmpty()) details.add("“" + b.note + "”");

            View row = Ui.row(context, card.body, Ui.signed(b.delta) + "  ·  " + b.groupLabel,
                    Ui.dateTime(context, b.at), android.text.TextUtils.join(" · ", details),
                    b.undone ? getString(R.string.batch_chip_undone) : null, 0, null);
            if (!b.undone) {
                MaterialButton undo = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
                undo.setText(R.string.batch_undo_batch);
                undo.setOnClickListener(v -> new MaterialAlertDialogBuilder(context)
                        .setMessage(getString(R.string.batch_undo_confirm, pointsText(b.delta), b.count))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.batch_undo_batch, (d, w) -> undoBatch(b, false))
                        .show());
                LinearLayout wrapper = new LinearLayout(context);
                wrapper.setOrientation(LinearLayout.VERTICAL);
                wrapper.addView(row);
                wrapper.addView(undo);
                card.body.addView(wrapper);
            } else {
                row.setAlpha(0.6f);
                card.body.addView(row);
            }
        }
    }

    /** Cancels each entry of the batch. Entries already cancelled elsewhere count as done; anything else that failed stays in the batch. */
    private void undoBatch(Batch batch, boolean fromSnackbar) {
        if (batch.undone) return;
        final List<String> ids = new ArrayList<>(batch.historyIds);
        Background.run(() -> {
            List<String[]> failed = new ArrayList<>();
            for (String id : ids) {
                RevertResult result = DatabaseSync.revertPointsHistory(database, id, null);
                if (result.error != null && !"ALREADY_REVERTED".equals(result.error)) failed.add(new String[]{id, result.error});
            }
            return failed;
        }, (failed, error) -> {
            if (getView() == null) return;
            if (error != null || failed == null) {
                snack(getString(R.string.revert_error_failed));
                return;
            }
            batch.historyIds = new ArrayList<>();
            for (String[] f : failed) batch.historyIds.add(f[0]);
            batch.undone = failed.isEmpty();
            if (batch.undone) snack(getString(R.string.batch_undone));
            else snack(getString(R.string.batch_undo_partial, failed.size(), revertMessage(failed.get(0)[1])));
            load();
        });
    }

    /** The message for a {@link org.dedira.qrnotas.util.Database#revertPointsHistory} error code. */
    private String revertMessage(String code) {
        switch (code) {
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

    /** A {@link TextWatcher} that only cares that the text changed. */
    private static final class SimpleWatcher implements TextWatcher {
        private final Runnable onChange;

        SimpleWatcher(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            onChange.run();
        }
    }
}
