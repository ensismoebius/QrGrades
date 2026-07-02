package org.dedira.qrnotas.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.activities.WebServerActivity;
import org.dedira.qrnotas.util.Database;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Foreground service hosting {@link WebServer}. Owns exactly one {@link Database} instance for
 * its whole lifetime, matching the rest of the app's single-connection-per-process assumption.
 * Password is only ever kept in memory (Intent extra, never persisted) and discarded on stop.
 */
public class WebServerService extends Service {

    public static final String EXTRA_PASSWORD = "password";
    public static final String ACTION_STOP = "org.dedira.qrnotas.services.WebServerService.STOP";

    private static final String CHANNEL_ID = "web_server_channel";
    private static final int NOTIFICATION_ID = 1001;

    public static volatile boolean isRunning = false;

    private Database database;
    private WebServer webServer;

    @Override
    public void onCreate() {
        super.onCreate();
        // NanoHTTPD's default multipart temp-file manager needs a writable tmpdir, unset by default on Android.
        System.setProperty("java.io.tmpdir", getCacheDir().getAbsolutePath());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String password = intent != null ? intent.getStringExtra(EXTRA_PASSWORD) : null;
        if (password == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());

        database = new Database(getApplicationContext());
        webServer = new WebServer(getApplicationContext(), database, password);
        try {
            webServer.start();
            isRunning = true;
        } catch (IOException e) {
            isRunning = false;
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (webServer != null) webServer.stop();
        isRunning = false;
        super.onDestroy();
    }

    private Notification buildNotification() {
        createChannelIfNeeded();

        Intent stopIntent = new Intent(this, WebServerService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent openIntent = new Intent(this, WebServerActivity.class);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String url = "http://" + lanAddress() + ":" + WebServer.PORT;

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle(getString(R.string.web_server_notification_title))
                .setContentText(url)
                .setContentIntent(contentPendingIntent)
                .addAction(0, getString(R.string.web_server_stop), stopPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.web_server_notification_channel), NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);
    }

    /** First non-loopback IPv4 address found — no runtime permission needed, unlike WifiManager SSID access. */
    public static String lanAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address.isLoopbackAddress()) continue;
                    String host = address.getHostAddress();
                    if (host != null && host.indexOf(':') < 0) return host;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
