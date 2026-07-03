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
 * <p>
 * For readers new to Android: a {@link Service} is a component that runs in the background
 * without a visible UI of its own. Because this one needs to keep running reliably (accepting
 * HTTP connections) even while the user is doing something else, it is a "foreground service" —
 * Android requires foreground services to show a persistent notification (see
 * {@link #buildNotification()}) so the user always knows it's active and can stop it. Nothing
 * in this class runs on its own initiative; every method here is a callback the Android system
 * invokes at specific points in the service's lifecycle.
 */
public class WebServerService extends Service {

    // Key used to pass the server password into this service via the starting Intent.
    public static final String EXTRA_PASSWORD = "password";
    // Custom Intent action used to tell an already-running instance of this service to stop
    // (sent by the "Stop" action button on the notification, see buildNotification()).
    public static final String ACTION_STOP = "org.dedira.qrnotas.services.WebServerService.STOP";

    // Identifies the notification channel this service's notifications belong to (required on Android 8+).
    private static final String CHANNEL_ID = "web_server_channel";
    // Fixed id for the single ongoing notification this service shows while running.
    private static final int NOTIFICATION_ID = 1001;

    // Simple flag other parts of the app can check to know whether the server is currently up.
    // "volatile" ensures changes made on this service's thread are visible to other threads
    // (e.g. the UI thread) reading this field without needing full synchronization.
    public static volatile boolean isRunning = false;

    private Database database;
    private WebServer webServer;

    /**
     * Called once by the system when this service is first created (before any
     * onStartCommand call). Used here only to fix a quirk of NanoHTTPD on Android.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        // NanoHTTPD's default multipart temp-file manager needs a writable tmpdir, unset by default on Android.
        System.setProperty("java.io.tmpdir", getCacheDir().getAbsolutePath());
    }

    /**
     * Called by the system every time something calls {@code startService()}/{@code startForegroundService()}
     * on this service — e.g. when the user starts the web server from the UI, or when the "Stop"
     * notification action re-delivers an Intent with {@link #ACTION_STOP}. This is where the
     * actual HTTP server is created and started (or, on the stop path, torn down).
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // stopSelf() asks Android to stop this service, which will trigger onDestroy() below.
            stopSelf();
            // START_NOT_STICKY tells Android not to automatically recreate/restart this service
            // if it gets killed for resources — appropriate since there's nothing to resume.
            return START_NOT_STICKY;
        }

        String password = intent != null ? intent.getStringExtra(EXTRA_PASSWORD) : null;
        if (password == null) {
            // No password means we were started incorrectly (e.g. after a process restart with
            // a stale Intent); bail out rather than run an unprotected server.
            stopSelf();
            return START_NOT_STICKY;
        }

        // Foreground services must show a notification within a few seconds of starting, or the
        // system will kill them; startForeground() both promotes this service and posts that notification.
        startForeground(NOTIFICATION_ID, buildNotification());

        database = new Database(getApplicationContext());
        webServer = new WebServer(getApplicationContext(), database, password);
        try {
            webServer.start();
            isRunning = true;
        } catch (IOException e) {
            // Most commonly the port is already in use; give up cleanly rather than leaving a
            // half-started service around.
            isRunning = false;
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    /**
     * Called if some component tries to bind to this service (e.g. via {@code bindService()}).
     * This service is only ever started/stopped, never bound to, so it returns null to indicate
     * binding isn't supported.
     */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Called by the system when the service is being shut down (after stopSelf(), or if the
     * system needs to reclaim resources). This is the right place to release the HTTP server
     * and any other resources this service owns.
     */
    @Override
    public void onDestroy() {
        if (webServer != null) webServer.stop();
        isRunning = false;
        super.onDestroy();
    }

    /**
     * Builds the persistent notification Android requires while this foreground service is
     * running. Shows the server's URL and offers two tap targets: tapping the body opens the
     * in-app web server screen, tapping the "Stop" action sends {@link #ACTION_STOP} back to
     * this same service to shut it down.
     */
    private Notification buildNotification() {
        createChannelIfNeeded();

        // PendingIntent wraps an Intent so another app/component (here, the notification/system
        // UI) can fire it later, on our behalf, as if we had triggered it ourselves.
        Intent stopIntent = new Intent(this, WebServerService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent,
                // FLAG_UPDATE_CURRENT refreshes any existing pending intent's extras instead of
                // creating duplicates; FLAG_IMMUTABLE prevents other apps from altering it (required on modern Android).
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
                // Ongoing notifications can't be swiped away by the user, matching the fact that
                // stopping the server should be a deliberate action (the Stop button), not an accident.
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    /**
     * Creates the notification channel this service's notification belongs to, if it doesn't
     * already exist. Notification channels are required on Android 8 (API 26) and above; on
     * older versions they don't exist, so the method returns immediately.
     */
    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        // IMPORTANCE_LOW means the notification appears without a sound or visual interruption,
        // appropriate for an ongoing status notification rather than an alert.
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.web_server_notification_channel), NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);
    }

    /** First non-loopback IPv4 address found — no runtime permission needed, unlike WifiManager SSID access. */
    public static String lanAddress() {
        try {
            // Enumerate every network interface on the device (Wi-Fi, mobile data, loopback, etc.).
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                // Skip interfaces that are down or are the loopback (127.0.0.1) interface, since
                // neither is reachable from another device on the network.
                if (!iface.isUp() || iface.isLoopback()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address.isLoopbackAddress()) continue;
                    String host = address.getHostAddress();
                    // IPv6 addresses contain ':' characters; skip them and keep looking for a
                    // plain IPv4 address, which is simpler for users to type into a browser.
                    if (host != null && host.indexOf(':') < 0) return host;
                }
            }
        } catch (Exception ignored) {
            // Any failure while enumerating interfaces just means we can't determine an address;
            // fall through and return null below rather than crashing the service.
        }
        return null;
    }
}
