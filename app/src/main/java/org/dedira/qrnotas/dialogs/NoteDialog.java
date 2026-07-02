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
import android.widget.TextView;

import com.google.android.material.textfield.TextInputEditText;

import org.dedira.qrnotas.R;

import java.util.Locale;

public class NoteDialog extends Dialog {
    public interface OnNoteConfirmedListener {
        void onNoteConfirmed(String note);
    }

    private final Context mContext;
    private final int pointsDelta;
    private final String studentName;
    private final OnNoteConfirmedListener listener;

    public NoteDialog(Context context, int pointsDelta, String studentName, OnNoteConfirmedListener listener) {
        super(context);
        mContext = context;
        this.pointsDelta = pointsDelta;
        this.studentName = studentName;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View inflateView = inflater.inflate(R.layout.dialog_note, null);
        setContentView(inflateView);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        TextView txtTitle = inflateView.findViewById(R.id.txtNoteDialogTitle);
        txtTitle.setText(mContext.getString(R.string.note_dialog_title,
                String.format(Locale.getDefault(), "%+d", pointsDelta), studentName));

        TextInputEditText txtNote = inflateView.findViewById(R.id.txtNoteDialogNote);

        inflateView.findViewById(R.id.btnNoteDialogCancel).setOnClickListener(v -> dismiss());
        inflateView.findViewById(R.id.btnNoteDialogSave).setOnClickListener(v -> {
            String note = txtNote.getText() == null ? "" : txtNote.getText().toString().trim();
            dismiss();
            listener.onNoteConfirmed(note);
        });
    }
}
