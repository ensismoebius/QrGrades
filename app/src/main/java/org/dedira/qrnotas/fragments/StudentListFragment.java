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

import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.CsvImportPlan;
import org.dedira.qrnotas.model.CsvRowError;
import org.dedira.qrnotas.model.CsvStudentRow;
import org.dedira.qrnotas.model.IDatabaseOnLoad;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.util.CsvImporter;
import org.dedira.qrnotas.util.DbBackup;
import org.dedira.qrnotas.util.Exporter;
import org.dedira.qrnotas.util.Importer;
import org.dedira.qrnotas.util.KeyboardUtils;
import org.dedira.qrnotas.util.adapters.StudentAdapter;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bottom-nav "Students" tab when opened with no arguments (every student in the app); also
 * reused as a nested destination showing just one class group's students when opened with a
 * {@code classGroupId}/{@code groupName} argument (from {@link ClassGroupListFragment}'s row
 * tap) — the toolbar's back arrow only appears in that nested case. Supports live text search,
 * swipe-to-delete, multi-select for bulk export, and JSON/CSV import with a safety backup
 * snapshot taken first.
 */
public class StudentListFragment extends BaseFragment {
    private StudentAdapter arrStudentsAdapter;
    private RecyclerView lstStudents;
    private TextView txtEmpty;
    private MaterialToolbar toolbar;
    private final ExecutorService exportExecutor = Executors.newSingleThreadExecutor();
    private ActivityResultLauncher<String[]> importFileLauncher;
    private ActivityResultLauncher<String[]> importCsvFileLauncher;
    private String classGroupId;
    private String groupName;

    public StudentListFragment() {
        super(R.layout.activity_list_student);
    }

    @Override
    protected boolean showsBackArrow() {
        // Handled manually in onSelectionChanged(), since it depends on both selection mode and
        // whether this instance is nested (classGroupId != null) or the top-level tab.
        return false;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.importFileLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) prepareJsonImport(uri);
        });
        this.importCsvFileLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) prepareCsvImport(uri);
        });
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        this.classGroupId = args != null ? args.getString("classGroupId") : null;
        this.groupName = args != null ? args.getString("groupName") : null;

        this.toolbar = view.findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.menu_student_list);
        toolbar.setOnMenuItemClickListener(this::onMenuItemClick);

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (arrStudentsAdapter.isSelectionMode()) {
                    arrStudentsAdapter.setSelectionMode(false);
                    return;
                }
                setEnabled(false);
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });

        this.txtEmpty = view.findViewById(R.id.txtEmpty);
        this.arrStudentsAdapter = new StudentAdapter(requireContext());
        this.arrStudentsAdapter.setSelectionListener(this::onSelectionChanged);

        this.lstStudents = view.findViewById(R.id.lstStudents);
        this.lstStudents.setAdapter(this.arrStudentsAdapter);

        this.arrStudentsAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                updateEmptyState();
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                updateEmptyState();
            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                updateEmptyState();
            }
        });

        new ItemTouchHelper(new SwipeToDeleteCallback()).attachToRecyclerView(this.lstStudents);

        // Adding a student is only offered from the top-level "all students" tab — a nested,
        // class-group-scoped roster has no single obvious discipline/class to enroll a new
        // student into, so the generic Add Student form (which asks explicitly) is reached from
        // there instead.
        FloatingActionButton btnAddStudent = view.findViewById(R.id.btnAddStudent);
        if (classGroupId == null) {
            btnAddStudent.setOnClickListener(v ->
                    Navigation.findNavController(v).navigate(R.id.action_studentList_to_addOrEditStudent));
        } else {
            btnAddStudent.setVisibility(View.GONE);
        }

        TextInputEditText txtSearch = view.findViewById(R.id.txtSearch);
        txtSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                arrStudentsAdapter.filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        // Only auto-focuses the search box on the top-level "all students" tab — a nested,
        // scoped class-group roster is small enough that forcing the keyboard open immediately
        // would just get in the way.
        if (classGroupId == null) KeyboardUtils.focusAndShowKeyboard(txtSearch);

        onSelectionChanged(false, 0);
        loadStudents();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadStudents();
    }

    private void loadStudents() {
        IDatabaseOnLoad<ArrayList<Student>> callback = (success, students) -> {
            if (getView() == null) return;
            if (success) {
                arrStudentsAdapter.submitFullList(students);
            } else {
                Toast.makeText(requireContext(), R.string.load_students_failed, Toast.LENGTH_SHORT).show();
            }
        };

        if (classGroupId != null) {
            database.loadStudentsForClassGroup(classGroupId, callback);
        } else {
            database.loadAllStudents(callback);
        }
    }

    private boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_select) {
            arrStudentsAdapter.setSelectionMode(true);
            return true;
        } else if (id == R.id.action_select_all) {
            arrStudentsAdapter.selectAll();
            return true;
        } else if (id == R.id.action_export) {
            onExportClicked();
            return true;
        } else if (id == R.id.action_export_all) {
            chooseFormatAndExport(null);
            return true;
        } else if (id == R.id.action_import) {
            importFileLauncher.launch(new String[]{"application/json"});
            return true;
        } else if (id == R.id.action_import_csv) {
            importCsvFileLauncher.launch(new String[]{"text/csv", "text/comma-separated-values",
                    "text/plain", "application/vnd.ms-excel"});
            return true;
        }
        return false;
    }

    private void onSelectionChanged(boolean selectionMode, int count) {
        toolbar.getMenu().findItem(R.id.action_select).setVisible(!selectionMode);
        toolbar.getMenu().findItem(R.id.action_select_all).setVisible(selectionMode);
        toolbar.getMenu().findItem(R.id.action_export).setVisible(selectionMode);
        toolbar.getMenu().findItem(R.id.action_export_all).setVisible(!selectionMode);
        toolbar.getMenu().findItem(R.id.action_import).setVisible(!selectionMode);
        toolbar.getMenu().findItem(R.id.action_import_csv).setVisible(!selectionMode);

        if (selectionMode) {
            toolbar.setTitle(getString(R.string.selected_count_title, count));
            toolbar.setNavigationIcon(R.drawable.ic_close);
            toolbar.setNavigationContentDescription(R.string.exiting_selection);
            toolbar.setNavigationOnClickListener(v -> arrStudentsAdapter.setSelectionMode(false));
        } else {
            toolbar.setTitle(classGroupId != null ? groupName : getString(R.string.show_all_students));
            toolbar.setNavigationContentDescription(null);
            if (classGroupId != null) {
                // Nested entry (from a class group's roster): back arrow pops to it.
                toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
                toolbar.setNavigationOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
            } else {
                // Top-level "Students" tab: nothing to pop back to.
                toolbar.setNavigationIcon(null);
                toolbar.setNavigationOnClickListener(null);
            }
        }
    }

    private void onExportClicked() {
        List<String> selectedIds = arrStudentsAdapter.getSelectedIds();
        if (selectedIds.isEmpty()) {
            Toast.makeText(requireContext(), R.string.select_students_first, Toast.LENGTH_SHORT).show();
            return;
        }

        chooseFormatAndExport(selectedIds);
    }

    /** Asks for the file format, then exports the given students ({@code null}: every student, like the browser page's Export). */
    private void chooseFormatAndExport(@Nullable List<String> studentIds) {
        String[] formats = {
                getString(R.string.export_format_md),
                getString(R.string.export_format_pdf),
                getString(R.string.export_format_json)
        };

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.export_format_title)
                .setItems(formats, (dialog, which) -> runExport(studentIds, which))
                .show();
    }

    private void runExport(List<String> selectedIds, int formatIndex) {
        loadingDialog.show();
        database.loadExportData(selectedIds, (success, data) -> {
            if (getView() == null) return;
            if (!success || data == null) {
                loadingDialog.dismiss();
                Toast.makeText(requireContext(), R.string.export_failed, Toast.LENGTH_SHORT).show();
                return;
            }

            exportExecutor.execute(() -> {
                try {
                    File file;
                    String mime;
                    switch (formatIndex) {
                        case 1:
                            file = Exporter.exportPdf(requireContext(), data);
                            mime = "application/pdf";
                            break;
                        case 2:
                            file = Exporter.exportJson(requireContext(), data);
                            mime = "application/json";
                            break;
                        default:
                            file = Exporter.exportMarkdown(requireContext(), data);
                            mime = "text/markdown";
                            break;
                    }

                    File finalFile = file;
                    String finalMime = mime;
                    requireActivity().runOnUiThread(() -> {
                        if (getView() == null) return;
                        loadingDialog.dismiss();
                        Exporter.share(requireContext(), finalFile, finalMime);
                        arrStudentsAdapter.setSelectionMode(false);
                    });
                } catch (Exception e) {
                    requireActivity().runOnUiThread(() -> {
                        if (getView() == null) return;
                        loadingDialog.dismiss();
                        Toast.makeText(requireContext(), R.string.export_failed, Toast.LENGTH_SHORT).show();
                    });
                }
            });
        });
    }

    /* ------------------------------ JSON import ------------------------------ */

    private void prepareJsonImport(Uri uri) {
        loadingDialog.show();
        exportExecutor.execute(() -> {
            List<StudentExportData> data;
            try {
                data = Importer.importJson(requireContext(), uri);
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    if (getView() == null) return;
                    loadingDialog.dismiss();
                    Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show();
                });
                return;
            }

            if (data.isEmpty()) {
                requireActivity().runOnUiThread(() -> {
                    if (getView() == null) return;
                    loadingDialog.dismiss();
                    Toast.makeText(requireContext(), R.string.import_empty, Toast.LENGTH_SHORT).show();
                });
                return;
            }

            requireActivity().runOnUiThread(() -> {
                if (getView() == null) return;
                loadingDialog.dismiss();
                confirmJsonImport(data);
            });
        });
    }

    private void confirmJsonImport(List<StudentExportData> data) {
        database.loadAllStudents((success, existingStudents) -> {
            if (getView() == null) return;
            Set<String> existingIds = new HashSet<>();
            if (success && existingStudents != null) {
                for (Student s : existingStudents) existingIds.add(s.id);
            }

            Set<String> incomingIds = new HashSet<>();
            for (StudentExportData s : data) if (s.studentId != null) incomingIds.add(s.studentId);

            int existingCount = 0;
            for (String id : incomingIds) if (existingIds.contains(id)) existingCount++;
            int newCount = incomingIds.size() - existingCount;

            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.import_confirm_title)
                    .setMessage(getString(R.string.json_import_summary, newCount, existingCount))
                    .setPositiveButton(R.string.import_action, (dialog, which) -> runJsonImport(data))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
    }

    private void runJsonImport(List<StudentExportData> data) {
        loadingDialog.show();
        snapshotThenRun(() -> database.importExportData(data, (success, errorMessage) -> {
            if (getView() == null) return;
            loadingDialog.dismiss();
            if (success) {
                Toast.makeText(requireContext(), R.string.import_succeeded, Toast.LENGTH_SHORT).show();
                loadStudents();
            } else {
                Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show();
            }
        }));
    }

    /* ------------------------------- CSV import -------------------------------- */

    private void prepareCsvImport(Uri uri) {
        loadingDialog.show();
        exportExecutor.execute(() -> {
            List<CsvStudentRow> rows;
            try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("Unable to open file");
                rows = CsvImporter.parse(in);
            } catch (Exception e) {
                String message = e.getMessage();
                requireActivity().runOnUiThread(() -> {
                    if (getView() == null) return;
                    loadingDialog.dismiss();
                    Toast.makeText(requireContext(), getString(R.string.csv_import_invalid_file, message), Toast.LENGTH_LONG).show();
                });
                return;
            }

            requireActivity().runOnUiThread(() -> {
                if (getView() == null) return;
                loadingDialog.dismiss();
                if (rows.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.csv_import_no_valid_rows, Toast.LENGTH_SHORT).show();
                    return;
                }
                database.resolveCsvRows(rows, (success, plan) -> confirmCsvImport(plan));
            });
        });
    }

    private static final int MAX_CSV_NAMES_SHOWN = 15;

    private void confirmCsvImport(CsvImportPlan plan) {
        if (getView() == null) return;
        if (plan.resolved.isEmpty()) {
            Toast.makeText(requireContext(), R.string.csv_import_no_valid_rows, Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder message = new StringBuilder(getString(R.string.csv_import_summary,
                plan.newStudentCount(), plan.matchedStudentCount(), plan.errors.size()));
        if (plan.newDisciplineCount() > 0 || plan.newClassGroupCount() > 0) {
            message.append("\n").append(getString(R.string.csv_import_new_structure,
                    plan.newDisciplineCount(), plan.newClassGroupCount()));
        }
        appendNameList(message, R.string.csv_import_new_disciplines_list, plan.newDisciplineNames());
        appendNameList(message, R.string.csv_import_new_class_groups_list, plan.newClassGroupLabels());
        appendNameList(message, R.string.csv_import_new_students_list, plan.newStudentNames());

        int shown = 0;
        for (CsvRowError error : plan.errors) {
            if (shown >= 5) break;
            message.append("\n").append(getString(R.string.csv_import_error_line, error.lineNumber, error.reason));
            shown++;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.csv_import_confirm_title)
                .setMessage(message.toString())
                .setPositiveButton(R.string.import_action, (dialog, which) -> runCsvImport(plan))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void appendNameList(StringBuilder message, int labelResId, List<String> names) {
        if (names.isEmpty()) return;
        int shownCount = Math.min(names.size(), MAX_CSV_NAMES_SHOWN);
        String joined = String.join(", ", names.subList(0, shownCount));
        if (names.size() > shownCount) {
            joined += getString(R.string.csv_import_list_more, names.size() - shownCount);
        }
        message.append("\n").append(getString(labelResId, joined));
    }

    private void runCsvImport(CsvImportPlan plan) {
        loadingDialog.show();
        snapshotThenRun(() -> database.importCsvRows(plan.resolved, (success, errorMessage) -> {
            if (getView() == null) return;
            loadingDialog.dismiss();
            if (success) {
                Toast.makeText(requireContext(), R.string.csv_import_succeeded, Toast.LENGTH_SHORT).show();
                loadStudents();
            } else {
                Toast.makeText(requireContext(), R.string.import_failed, Toast.LENGTH_SHORT).show();
            }
        }));
    }

    private void snapshotThenRun(Runnable importAction) {
        DbBackup.createFullSnapshot(requireContext(), database, false, (success, error) -> {
            if (success) DbBackup.pruneOldSnapshots(requireContext(), 15);
            importAction.run();
        });
    }

    private void updateEmptyState() {
        boolean isEmpty = arrStudentsAdapter.getItemCount() == 0;
        txtEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        lstStudents.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    /**
     * Draws the red "delete" background and trash icon that peeks out from behind a row as the
     * user swipes it left/right, and triggers the actual delete once the swipe completes.
     * Disabled while in multi-select mode (swiping would conflict with tap-to-select).
     */
    private class SwipeToDeleteCallback extends ItemTouchHelper.SimpleCallback {
        private final ColorDrawable background = new ColorDrawable(ContextCompat.getColor(requireContext(), R.color.swipe_delete_bg));
        private final Drawable deleteIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_delete);

        SwipeToDeleteCallback() {
            super(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT);
        }

        @Override
        public int getSwipeDirs(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            if (arrStudentsAdapter.isSelectionMode()) return 0;
            return super.getSwipeDirs(recyclerView, viewHolder);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
            return false;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            arrStudentsAdapter.requestDelete(viewHolder.getBindingAdapterPosition());
        }

        @Override
        public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
                                 float dX, float dY, int actionState, boolean isCurrentlyActive) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);

            if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) return;

            View itemView = viewHolder.itemView;
            int iconMargin = (int) dp(16);
            int iconSize = deleteIcon != null ? deleteIcon.getIntrinsicHeight() : 0;
            int iconTop = itemView.getTop() + (itemView.getHeight() - iconSize) / 2;

            if (dX > 0) {
                background.setBounds(itemView.getLeft(), itemView.getTop(), itemView.getLeft() + (int) dX, itemView.getBottom());
                background.draw(c);
                if (deleteIcon != null) {
                    int left = itemView.getLeft() + iconMargin;
                    deleteIcon.setTint(Color.WHITE);
                    deleteIcon.setBounds(left, iconTop, left + iconSize, iconTop + iconSize);
                    deleteIcon.draw(c);
                }
            } else if (dX < 0) {
                background.setBounds(itemView.getRight() + (int) dX, itemView.getTop(), itemView.getRight(), itemView.getBottom());
                background.draw(c);
                if (deleteIcon != null) {
                    int right = itemView.getRight() - iconMargin;
                    deleteIcon.setTint(Color.WHITE);
                    deleteIcon.setBounds(right - iconSize, iconTop, right, iconTop + iconSize);
                    deleteIcon.draw(c);
                }
            }
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }
}
