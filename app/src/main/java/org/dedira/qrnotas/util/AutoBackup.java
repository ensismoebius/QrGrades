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

package org.dedira.qrnotas.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Takes a backup of the database (see {@link DbBackup}) without being asked, once per day or per
 * week.
 * <p>
 * There is no background job: the data only changes while the app is in use (including while the
 * web server is running), so the check simply runs when the app opens and when the web server
 * starts, and takes a backup if the last scheduled one is from an earlier day. That needs no
 * extra permission or library and can't be held back by battery savers. Scheduled snapshots are
 * kept apart from the user's own, and only the newest {@link #KEEP_SCHEDULED} are kept.
 */
public final class AutoBackup {

    public static final int OFF = 0;
    public static final int DAILY = 1;
    public static final int WEEKLY = 7;

    /** How many scheduled snapshots to keep: a week of daily backups, or seven weeks of weekly ones. */
    public static final int KEEP_SCHEDULED = 7;

    private static final String PREFS = "auto_backup";
    private static final String KEY_INTERVAL_DAYS = "interval_days";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    // The app launching and the web server starting can both ask within moments of each other.
    private static final AtomicBoolean running = new AtomicBoolean(false);

    private AutoBackup() {
    }

    /** Days between scheduled backups: {@link #DAILY} unless the user chose otherwise, {@link #OFF} for none. */
    public static int intervalDays(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int days = prefs.getInt(KEY_INTERVAL_DAYS, DAILY);
        return days == OFF || days == DAILY || days == WEEKLY ? days : DAILY;
    }

    public static void setIntervalDays(Context context, int days) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_INTERVAL_DAYS, days).apply();
    }

    /**
     * Whether a backup is due: at least {@code intervalDays} calendar days (in {@code zone}) have
     * passed since the last one, or there never was one. Calendar days, not 24-hour spans, so
     * opening the app a little earlier each morning still gets a backup every day. A clock set back
     * to before the last backup never counts as due.
     */
    public static boolean isDue(int intervalDays, long lastBackupAt, long now, TimeZone zone) {
        if (intervalDays <= 0) return false;
        if (lastBackupAt <= 0) return true;
        return localDay(now, zone) - localDay(lastBackupAt, zone) >= intervalDays;
    }

    private static long localDay(long millis, TimeZone zone) {
        return Math.floorDiv(millis + zone.getOffset(millis), DAY_MS);
    }

    /** When the newest scheduled snapshot was made, or 0 if there is none. */
    static long lastScheduledAt(Context context) {
        for (File snapshot : DbBackup.listSnapshots(context)) { // newest first
            if (DbBackup.isScheduled(snapshot)) return snapshot.lastModified();
        }
        return 0;
    }

    /** Takes a scheduled backup in the background if one is due. Safe to call often and from anywhere on the main thread. */
    public static void runIfDue(Context context, Database database) {
        Context app = context.getApplicationContext();
        if (!isDue(intervalDays(app), lastScheduledAt(app), System.currentTimeMillis(), TimeZone.getDefault())) return;
        if (!running.compareAndSet(false, true)) return;

        DbBackup.createScheduledSnapshot(app, database, (success, error) -> {
            if (success) DbBackup.pruneScheduledSnapshots(app, KEEP_SCHEDULED);
            running.set(false);
        });
    }
}
