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
import org.dedira.qrnotas.util.StudentPickerAdapter;

import java.util.ArrayList;

/** Lets the teacher award points to a student without scanning their QR code. */
public class StudentPickerDialog extends Dialog {

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View inflateView = inflater.inflate(R.layout.dialog_student_picker, new FrameLayout(mContext), false);
        setContentView(inflateView);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        this.txtEmpty = inflateView.findViewById(R.id.txtStudentPickerEmpty);
        this.list = inflateView.findViewById(R.id.lstStudentPicker);
        list.setLayoutManager(new LinearLayoutManager(mContext));

        this.adapter = new StudentPickerAdapter(student -> {
            dismiss();
            listener.onStudentPicked(student);
        });
        list.setAdapter(adapter);

        TextInputEditText txtSearch = inflateView.findViewById(R.id.txtStudentPickerSearch);
        KeyboardUtils.focusAndShowKeyboard(txtSearch);
        txtSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s.toString());
                updateEmptyState();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        inflateView.findViewById(R.id.btnStudentPickerCancel).setOnClickListener(v -> dismiss());

        database.loadAllStudents((success, results) -> {
            adapter.submitFullList(results != null ? results : new ArrayList<>());
            updateEmptyState();
        });
    }

    private void updateEmptyState() {
        boolean empty = adapter.getStudentCount() == 0;
        txtEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
    }
}
