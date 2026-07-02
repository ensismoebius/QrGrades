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

public class WebServerActivity extends AppCompatActivity {

    private MaterialSwitch switchWebServer;
    private View cardStatus;
    private TextView txtUrl;
    private ImageView imgQr;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private boolean startPending = false;

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

        this.notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (startPending) {
                        startPending = false;
                        promptForPasswordAndStart();
                    }
                });

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

        findViewById(R.id.btnWebServerStop).setOnClickListener(v -> switchWebServer.setChecked(false));

        if (WebServerService.isRunning) {
            switchWebServer.setChecked(true);
            showRunningState();
        }
    }

    private void requestNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            startPending = true;
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        promptForPasswordAndStart();
    }

    private void promptForPasswordAndStart() {
        new WebServerPasswordDialog(this, this::startServer).show();
    }

    private void startServer(String password) {
        Intent intent = new Intent(this, WebServerService.class);
        intent.putExtra(WebServerService.EXTRA_PASSWORD, password);
        ContextCompat.startForegroundService(this, intent);
        showRunningState();
    }

    private void stopServer() {
        stopService(new Intent(this, WebServerService.class));
        cardStatus.setVisibility(View.GONE);
    }

    private void showRunningState() {
        String host = WebServerService.lanAddress();
        if (host == null) {
            Toast.makeText(this, R.string.web_server_no_lan, Toast.LENGTH_LONG).show();
            switchWebServer.setChecked(false);
            return;
        }

        String url = "http://" + host + ":" + WebServer.PORT;
        txtUrl.setText(url);
        imgQr.setImageBitmap(QrCode.generateQRCode(url));
        cardStatus.setVisibility(View.VISIBLE);
    }

    private void copyUrlToClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("QrGrades web server URL", txtUrl.getText()));
        Toast.makeText(this, R.string.web_server_url_copied, Toast.LENGTH_SHORT).show();
    }
}
