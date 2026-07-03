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

package org.dedira.qrnotas.activities;

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

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.dialogs.WebServerPasswordDialog;
import org.dedira.qrnotas.services.WebServer;
import org.dedira.qrnotas.services.WebServerService;
import org.dedira.qrnotas.util.ActivityTransitions;
import org.dedira.qrnotas.util.EdgeToEdge;
import org.dedira.qrnotas.util.QrCode;

/**
 * Screen where the teacher turns the LAN web server on/off. When switched on, it asks for a
 * one-time password (via {@link WebServerPasswordDialog}), starts {@link WebServerService} as a
 * foreground service (so Android doesn't kill it while the app is in the background), and shows
 * the URL + a scannable QR code so another device on the same WiFi can connect. Switching off
 * stops the service, which also shuts down the embedded {@link WebServer}.
 */
public class WebServerActivity extends AppCompatActivity {

    private MaterialSwitch switchWebServer;
    private View cardStatus;
    private TextView txtUrl;
    private ImageView imgQr;
    // Launcher for the runtime "allow notifications" permission prompt (Android 13+). Registering
    // it here (rather than inline) is required by the Android API — it must happen before the
    // Activity reaches STARTED, so it's done unconditionally in onCreate even if never used.
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    // Remembers that the user asked to start the server while we're still waiting on the
    // notification-permission prompt's result, so we know to continue starting it once that
    // permission callback comes back (regardless of whether it was granted or denied).
    private boolean startPending = false;

    /**
     * Called once by Android when this screen is created. Wires up the toolbar, the on/off
     * switch, the "copy URL" tap target, and restores the "server is running" UI state if the
     * service was already running before this screen opened (e.g. user navigated away and back).
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityTransitions.enter(this);
        setContentView(R.layout.activity_web_server);
        EdgeToEdge.apply(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishAfterTransition();
            }
        });

        this.switchWebServer = findViewById(R.id.switchWebServer);
        this.cardStatus = findViewById(R.id.cardStatus);
        this.txtUrl = findViewById(R.id.txtWebServerUrl);
        this.imgQr = findViewById(R.id.imgWebServerQr);

        // Must be registered before the Activity starts, even though it may never fire (e.g. on
        // Android versions below 13 where this permission doesn't exist).
        this.notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (startPending) {
                        startPending = false;
                        // Proceed with starting the server either way — a denied notification
                        // permission just means the running-server notification won't show, it
                        // doesn't block the server itself from starting.
                        promptForPasswordAndStart();
                    }
                });

        this.txtUrl.setOnClickListener(v -> copyUrlToClipboard());

        // Reacts to the user flipping the switch: turning it on kicks off the permission-check
        // -> password-dialog -> start-service chain; turning it off stops the service directly.
        this.switchWebServer.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (WebServerService.isRunning) {
                    // Already running (e.g. switch got toggled programmatically below) — just
                    // refresh the status card instead of starting a second instance.
                    showRunningState();
                    return;
                }
                requestNotificationPermissionThenStart();
            } else {
                stopServer();
            }
        });

        // The "Stop" button on the status card just flips the switch off, reusing the same
        // stopServer() logic wired to the switch listener above.
        findViewById(R.id.btnWebServerStop).setOnClickListener(v -> switchWebServer.setChecked(false));

        // If the service was already running when this screen opens (e.g. it survived
        // navigating away and back), reflect that in the UI immediately instead of showing "off".
        if (WebServerService.isRunning) {
            switchWebServer.setChecked(true);
            showRunningState();
        }
    }

    /**
     * On Android 13+, posting the "server is running" notification requires the user to grant
     * the POST_NOTIFICATIONS runtime permission first. If it's already granted (or the OS is
     * older and doesn't need it), skips straight to the password prompt; otherwise requests it
     * and defers starting the server until the permission callback fires (see startPending).
     */
    private void requestNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            startPending = true;
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        promptForPasswordAndStart();
    }

    /** Shows the one-time password dialog; {@link #startServer} runs once the user confirms it. */
    private void promptForPasswordAndStart() {
        new WebServerPasswordDialog(this, this::startServer).show();
    }

    /**
     * Starts {@link WebServerService} as a foreground service, passing the chosen password as an
     * Intent extra. A foreground service (rather than a plain background service) is required
     * here so Android doesn't kill it while this Activity isn't visible, and shows a persistent
     * notification while the server is up.
     */
    private void startServer(String password) {
        Intent intent = new Intent(this, WebServerService.class);
        intent.putExtra(WebServerService.EXTRA_PASSWORD, password);
        ContextCompat.startForegroundService(this, intent);
        showRunningState();
    }

    /** Stops the background service (which tears down the embedded HTTP server) and hides the status card. */
    private void stopServer() {
        stopService(new Intent(this, WebServerService.class));
        cardStatus.setVisibility(View.GONE);
    }

    /**
     * Updates the UI to show the server is running: builds the "http://ip:port" URL from the
     * device's current LAN address, displays it as text, and renders it as a scannable QR code
     * so another device can connect without typing the address by hand.
     */
    private void showRunningState() {
        String host = WebServerService.lanAddress();
        if (host == null) {
            // No WiFi connection (or no usable LAN address) — the server can still be running,
            // but nothing on the network could reach it, so warn the user and revert the switch.
            Toast.makeText(this, R.string.web_server_no_lan, Toast.LENGTH_LONG).show();
            switchWebServer.setChecked(false);
            return;
        }

        String url = "http://" + host + ":" + WebServer.PORT;
        txtUrl.setText(url);
        imgQr.setImageBitmap(QrCode.generateQRCode(url));
        cardStatus.setVisibility(View.VISIBLE);
    }

    /** Copies the currently-shown server URL to the system clipboard, for pasting into a browser on another device. */
    private void copyUrlToClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("QrGrades web server URL", txtUrl.getText()));
        Toast.makeText(this, R.string.web_server_url_copied, Toast.LENGTH_SHORT).show();
    }
}
