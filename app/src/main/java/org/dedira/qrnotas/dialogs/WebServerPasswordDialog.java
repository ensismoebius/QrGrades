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
import android.widget.Toast;

import com.google.android.material.textfield.TextInputEditText;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.util.KeyboardUtils;

public class WebServerPasswordDialog extends Dialog {
    public interface OnPasswordConfirmedListener {
        void onPasswordConfirmed(String password);
    }

    private static final int MIN_PASSWORD_LENGTH = 4;

    private final Context mContext;
    private final OnPasswordConfirmedListener listener;

    public WebServerPasswordDialog(Context context, OnPasswordConfirmedListener listener) {
        super(context);
        mContext = context;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View inflateView = inflater.inflate(R.layout.dialog_web_server_password, null);
        setContentView(inflateView);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        TextInputEditText txtPassword = inflateView.findViewById(R.id.txtWebServerPassword);
        KeyboardUtils.focusAndShowKeyboard(txtPassword);

        inflateView.findViewById(R.id.btnWebServerPasswordCancel).setOnClickListener(v -> dismiss());
        inflateView.findViewById(R.id.btnWebServerPasswordStart).setOnClickListener(v -> {
            String password = txtPassword.getText() == null ? "" : txtPassword.getText().toString().trim();
            if (password.length() < MIN_PASSWORD_LENGTH) {
                Toast.makeText(mContext, R.string.web_server_password_too_short, Toast.LENGTH_SHORT).show();
                return;
            }
            dismiss();
            listener.onPasswordConfirmed(password);
        });
    }
}
