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
import android.widget.FrameLayout;
import android.widget.Toast;

import com.google.android.material.textfield.TextInputEditText;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.util.KeyboardUtils;

/**
 * Dialog asking the teacher to set a password before starting the embedded
 * web server (see {@link org.dedira.qrnotas.services.WebServerService} and
 * {@link org.dedira.qrnotas.services.WebServer}).
 * <p>
 * The web server exposes the app's data to any browser on the same local
 * network, so a password is required to prevent other people on the network
 * from viewing or editing student data. This dialog only validates the
 * password's minimum length locally; it does not talk to the server itself —
 * it just hands the chosen password back to the caller via
 * {@link OnPasswordConfirmedListener}, which is expected to actually start
 * the server with it.
 */
public class WebServerPasswordDialog extends Dialog {
    /**
     * Callback used to deliver the password the user typed once they tap
     * "start", since a dialog can't return a value directly from a button click.
     */
    public interface OnPasswordConfirmedListener {
        void onPasswordConfirmed(String password);
    }

    // Passwords shorter than this are rejected as too weak/easy to guess.
    private static final int MIN_PASSWORD_LENGTH = 4;

    private final Context mContext;
    private final OnPasswordConfirmedListener listener;

    public WebServerPasswordDialog(Context context, OnPasswordConfirmedListener listener) {
        super(context);
        mContext = context;
        this.listener = listener;
    }

    /**
     * Called by the Android framework right before this dialog's window is
     * first shown. Inflates the layout, focuses the password field, and wires
     * up the cancel/start buttons, including basic password-length validation.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Remove the default dialog title bar; the layout supplies its own header.
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Turn dialog_web_server_password.xml into real View objects for this dialog.
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View inflateView = inflater.inflate(R.layout.dialog_web_server_password, new FrameLayout(mContext), false);
        setContentView(inflateView);

        Window window = getWindow();
        if (window != null) {
            // Transparent window background so only the layout's own card shape shows.
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // Stretch full width, but only take as much height as the content needs.
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        TextInputEditText txtPassword = inflateView.findViewById(R.id.txtWebServerPassword);
        // Focus the password field and pop up the soft keyboard immediately, since
        // typing a password is the only thing the user needs to do in this dialog.
        KeyboardUtils.focusAndShowKeyboard(txtPassword);

        inflateView.findViewById(R.id.btnWebServerPasswordCancel).setOnClickListener(v -> dismiss());
        inflateView.findViewById(R.id.btnWebServerPasswordStart).setOnClickListener(v -> {
            // getText() can be null if the field was never touched; fall back to an empty string.
            String password = txtPassword.getText() == null ? "" : txtPassword.getText().toString().trim();
            if (password.length() < MIN_PASSWORD_LENGTH) {
                // Show a brief on-screen message and keep the dialog open so the user can fix it.
                Toast.makeText(mContext, R.string.web_server_password_too_short, Toast.LENGTH_SHORT).show();
                return;
            }
            dismiss();
            listener.onPasswordConfirmed(password);
        });
    }
}
