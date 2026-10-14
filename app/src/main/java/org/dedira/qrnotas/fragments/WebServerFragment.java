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

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.materialswitch.MaterialSwitch;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.dialogs.WebServerPasswordDialog;
import org.dedira.qrnotas.services.WebServer;
import org.dedira.qrnotas.services.WebServerService;
import org.dedira.qrnotas.util.QrCode;

/**
 * Screen where the teacher turns the LAN web server on/off. When switched on, it asks for a
 * one-time password (via {@link WebServerPasswordDialog}), starts {@link WebServerService} as a
 * foreground service, and shows the URL + a scannable QR code so another device on the same WiFi
 * can connect. Switching off stops the service, which also shuts down the embedded
 * {@link WebServer}. Reached from the "More" tab's "Web server" card.
 */
public class WebServerFragment extends BaseFragment {

    private MaterialSwitch switchWebServer;
    private View cardStatus;
    private TextView txtUrl;
    private ImageView imgQr;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private boolean startPending = false;

    public WebServerFragment() {
        super(R.layout.activity_web_server);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Must be registered before the Fragment reaches STARTED, even though it may never fire
        // (e.g. on Android versions below 13 where this permission doesn't exist).
        this.notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (startPending) {
                        startPending = false;
                        promptForPasswordAndStart();
                    }
                });
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        this.switchWebServer = view.findViewById(R.id.switchWebServer);
        this.cardStatus = view.findViewById(R.id.cardStatus);
        this.txtUrl = view.findViewById(R.id.txtWebServerUrl);
        this.imgQr = view.findViewById(R.id.imgWebServerQr);

        this.txtUrl.setOnClickListener(v -> copyUrlToClipboard());

        this.switchWebServer.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (WebServerService.isRunning) {
                    showRunningState();
                    return;
                }
                requestNotificationPermissionThenStart();
            } else {
                stopServer();
            }
        });

        view.findViewById(R.id.btnWebServerStop).setOnClickListener(v -> switchWebServer.setChecked(false));

        if (WebServerService.isRunning) {
            switchWebServer.setChecked(true);
            showRunningState();
        }
    }

    private void requestNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            startPending = true;
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        promptForPasswordAndStart();
    }

    private void promptForPasswordAndStart() {
        new WebServerPasswordDialog(requireContext(), this::startServer).show();
    }

    private void startServer(String password) {
        Intent intent = new Intent(requireContext(), WebServerService.class);
        intent.putExtra(WebServerService.EXTRA_PASSWORD, password);
        ContextCompat.startForegroundService(requireContext(), intent);
        showRunningState();
    }

    private void stopServer() {
        requireContext().stopService(new Intent(requireContext(), WebServerService.class));
        cardStatus.setVisibility(View.GONE);
    }

    private void showRunningState() {
        if (getView() == null) return;
        String host = WebServerService.lanAddress();
        if (host == null) {
            Toast.makeText(requireContext(), R.string.web_server_no_lan, Toast.LENGTH_LONG).show();
            switchWebServer.setChecked(false);
            return;
        }

        String url = "http://" + host + ":" + WebServer.PORT;
        txtUrl.setText(url);
        imgQr.setImageBitmap(QrCode.generateQRCode(url));
        cardStatus.setVisibility(View.VISIBLE);
    }

    private void copyUrlToClipboard() {
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("QrGrades web server URL", txtUrl.getText()));
        Toast.makeText(requireContext(), R.string.web_server_url_copied, Toast.LENGTH_SHORT).show();
    }
}
