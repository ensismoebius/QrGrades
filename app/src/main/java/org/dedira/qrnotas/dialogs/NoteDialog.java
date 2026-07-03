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
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AutoCompleteTextView;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.adapters.FuzzyNoteAdapter;
import org.dedira.qrnotas.util.KeyboardUtils;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Dialog shown when the user awards or removes points from a student and wants
 * to attach a short text note explaining why (e.g. "extra credit", "late homework").
 * <p>
 * This dialog is a good example of several common Android UI patterns for a
 * beginner: it inflates a custom layout into a {@link Dialog}, pre-fills an
 * auto-complete text field with the student's recent notes (loaded asynchronously
 * from the database), and reports the result back to the caller through a
 * simple callback interface ({@link OnNoteConfirmedListener}) instead of
 * returning a value directly (dialogs are asynchronous/non-blocking by nature).
 */
public class NoteDialog extends Dialog {
    // Maximum number of previously-used notes to fetch for the autocomplete suggestions.
    private static final int NOTE_HISTORY_LIMIT = 200;

    /**
     * Callback used to hand the finished note text back to whoever created this
     * dialog, once the user taps "save". Dialogs can't return values like a
     * normal method call because the user interacts with them over time, so a
     * listener interface is the standard Android way to get a result out.
     */
    public interface OnNoteConfirmedListener {
        void onNoteConfirmed(String note);
    }

    private final Context mContext;
    private final Database database;
    private final int pointsDelta;
    private final String studentName;
    private final OnNoteConfirmedListener listener;

    public NoteDialog(Context context, Database database, int pointsDelta, String studentName, OnNoteConfirmedListener listener) {
        super(context);
        mContext = context;
        this.database = database;
        this.pointsDelta = pointsDelta;
        this.studentName = studentName;
        this.listener = listener;
    }

    /**
     * Called by the Android framework right before the dialog's window is first
     * shown. Builds the whole UI: inflates the layout, wires up the title text,
     * sets up the note autocomplete field, and attaches click listeners to the
     * save/cancel buttons.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Remove the default dialog title bar; the layout supplies its own title view instead.
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Turn the dialog_note.xml layout resource into real View objects.
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View inflateView = inflater.inflate(R.layout.dialog_note, new FrameLayout(mContext), false);
        setContentView(inflateView);

        Window window = getWindow();
        if (window != null) {
            // Transparent background so the layout's own rounded card (if any) is what's visible.
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // Force the dialog to stretch to the full screen width but only take as much
            // height as its content needs, rather than using the platform's default sizing.
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        TextView txtTitle = inflateView.findViewById(R.id.txtNoteDialogTitle);
        // Build a title like "+5 points for John Doe" using a localized string template
        // and formatting the points delta with an explicit sign (+5 or -3).
        txtTitle.setText(mContext.getString(R.string.note_dialog_title,
                String.format(Locale.getDefault(), "%+d", pointsDelta), studentName));

        AutoCompleteTextView txtNote = inflateView.findViewById(R.id.txtNoteDialogNote);
        // A threshold of 0 means the dropdown of suggestions can appear even
        // before the user types anything (normally it requires N characters).
        txtNote.setThreshold(0);
        // Show the suggestion dropdown as soon as the field gains focus, not just when typing.
        txtNote.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) txtNote.showDropDown();
        });
        // Also show suggestions if the user taps the field while it's already focused.
        txtNote.setOnClickListener(v -> txtNote.showDropDown());
        // Utility helper that requests focus on the field and pops up the soft keyboard.
        KeyboardUtils.focusAndShowKeyboard(txtNote);
        // Asynchronously fetch recently-used notes from the database so the user can
        // quickly reuse a past note. The (success, notes) callback runs later, after
        // the query completes, and is posted back to the main/UI thread by Database
        // so it's safe to touch views here.
        database.loadRecentNotes(NOTE_HISTORY_LIMIT, (success, notes) -> {
            txtNote.setAdapter(new FuzzyNoteAdapter(mContext, notes != null ? notes : new ArrayList<>()));
            if (txtNote.hasFocus()) txtNote.showDropDown();
        });

        // Cancel button just closes the dialog without invoking the listener.
        inflateView.findViewById(R.id.btnNoteDialogCancel).setOnClickListener(v -> dismiss());
        inflateView.findViewById(R.id.btnNoteDialogSave).setOnClickListener(v -> {
            // getText() can be null if the field was never touched; fall back to an empty string.
            String note = txtNote.getText() == null ? "" : txtNote.getText().toString().trim();
            dismiss();
            listener.onNoteConfirmed(note);
        });
    }
}
