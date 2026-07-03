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

package org.dedira.qrnotas.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.KeyboardUtils;
import org.dedira.qrnotas.util.adapters.StudentPickerAdapter;

import java.util.ArrayList;

/**
 * Lets the teacher award points to a student without scanning their QR code.
 * <p>
 * This dialog shows a searchable list of all students (backed by a
 * {@link RecyclerView}) so the teacher can find a student by typing part of
 * their name and tap them directly. It's an alternative entry point to the
 * same "give points" flow that {@link org.dedira.qrnotas.services.QrScanTileService}
 * triggers via QR scanning — useful when a student's QR code isn't available.
 */
public class StudentPickerDialog extends Dialog {

    /**
     * Callback used to report which student the teacher tapped, since a
     * dialog can't return a value directly from a button click — the result
     * has to be delivered asynchronously once the user makes a choice.
     */
    public interface OnStudentPickedListener {
        void onStudentPicked(Student student);
    }

    private final Context mContext;
    private final Database database;
    private final OnStudentPickedListener listener;
    private StudentPickerAdapter adapter;
    private TextView txtEmpty;
    private RecyclerView list;

    public StudentPickerDialog(Context context, Database database, OnStudentPickedListener listener) {
        super(context);
        mContext = context;
        this.database = database;
        this.listener = listener;
    }

    /**
     * Called by the Android framework right before this dialog's window is
     * first shown. Sets up the search box, the RecyclerView list and its
     * adapter, and kicks off an asynchronous load of all students from the
     * database to populate the list.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Remove the default dialog title bar; the layout supplies its own header/search box.
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Turn dialog_student_picker.xml into real View objects for this dialog's window.
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View inflateView = inflater.inflate(R.layout.dialog_student_picker, new FrameLayout(mContext), false);
        setContentView(inflateView);

        Window window = getWindow();
        if (window != null) {
            // Transparent window background so only the layout's own card shape shows.
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // Stretch full width, but only take as much height as the content (list + search) needs.
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        this.txtEmpty = inflateView.findViewById(R.id.txtStudentPickerEmpty);
        this.list = inflateView.findViewById(R.id.lstStudentPicker);
        // RecyclerView needs an explicit LayoutManager to know how to arrange items;
        // LinearLayoutManager arranges them in a simple vertical scrolling list.
        list.setLayoutManager(new LinearLayoutManager(mContext));

        // The adapter's constructor takes a lambda that fires when a student row is tapped.
        this.adapter = new StudentPickerAdapter(student -> {
            dismiss();
            listener.onStudentPicked(student);
        });
        list.setAdapter(adapter);

        TextInputEditText txtSearch = inflateView.findViewById(R.id.txtStudentPickerSearch);
        // Give the search field focus and pop up the soft keyboard immediately,
        // since searching is the primary action of this dialog.
        KeyboardUtils.focusAndShowKeyboard(txtSearch);
        // TextWatcher fires on every keystroke; we only care about onTextChanged, so
        // the other two callbacks are left empty (they're required by the interface).
        txtSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Re-filter the visible list as the user types and refresh the "no results" message.
                adapter.filter(s.toString());
                updateEmptyState();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        inflateView.findViewById(R.id.btnStudentPickerCancel).setOnClickListener(v -> dismiss());

        // Asynchronously load every student from the database. The (success, results)
        // callback runs later, after the query finishes, and is delivered back on the
        // main/UI thread by Database, so it's safe to update the adapter/views here.
        database.loadAllStudents((success, results) -> {
            adapter.submitFullList(results != null ? results : new ArrayList<>());
            updateEmptyState();
        });
    }

    /**
     * Shows the "no students found" message and hides the list when the
     * (possibly filtered) student list is empty, and vice versa.
     */
    private void updateEmptyState() {
        boolean empty = adapter.getStudentCount() == 0;
        // View.VISIBLE / View.GONE are Android's flags for showing a view or
        // removing it entirely from layout (as opposed to INVISIBLE, which keeps
        // its space reserved but hides it).
        txtEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
    }
}
