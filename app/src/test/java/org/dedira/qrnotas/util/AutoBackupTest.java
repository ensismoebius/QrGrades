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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Tests for {@link AutoBackup}: when a scheduled backup is due (a pure calendar-day rule, checked
 * in a fixed time zone so the result doesn't depend on where the tests run), that scheduled
 * snapshots live apart from the user's own, and the whole check-then-back-up flow.
 */
@RunWith(RobolectricTestRunner.class)
public class AutoBackupTest {

    // Brazil has no daylight saving time any more, so this zone is a plain UTC-3 all year.
    private static final TimeZone SAO_PAULO = TimeZone.getTimeZone("America/Sao_Paulo");

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(SAO_PAULO);
        c.clear();
        c.set(year, month - 1, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    /* ------------------------------------ isDue ------------------------------------ */

    @Test
    public void off_isNeverDue() {
        assertFalse(AutoBackup.isDue(AutoBackup.OFF, 0, at(2026, 11, 3, 9, 0), SAO_PAULO));
        assertFalse(AutoBackup.isDue(AutoBackup.OFF, at(2026, 1, 1, 9, 0), at(2026, 11, 3, 9, 0), SAO_PAULO));
    }

    @Test
    public void neverBackedUp_isDue() {
        assertTrue(AutoBackup.isDue(AutoBackup.DAILY, 0, at(2026, 11, 3, 9, 0), SAO_PAULO));
        assertTrue(AutoBackup.isDue(AutoBackup.WEEKLY, 0, at(2026, 11, 3, 9, 0), SAO_PAULO));
    }

    @Test
    public void daily_sameCalendarDay_isNotDue() {
        assertFalse(AutoBackup.isDue(AutoBackup.DAILY, at(2026, 11, 3, 7, 50), at(2026, 11, 3, 23, 59), SAO_PAULO));
    }

    @Test
    public void daily_nextCalendarDay_isDueEvenLessThan24HoursLater() {
        // Opening the app a bit earlier each morning must still back up every day.
        assertTrue(AutoBackup.isDue(AutoBackup.DAILY, at(2026, 11, 3, 7, 50), at(2026, 11, 4, 7, 40), SAO_PAULO));
        // And just across midnight counts as a new day.
        assertTrue(AutoBackup.isDue(AutoBackup.DAILY, at(2026, 11, 3, 23, 50), at(2026, 11, 4, 0, 10), SAO_PAULO));
    }

    @Test
    public void weekly_dueOnlyAfterSevenCalendarDays() {
        long last = at(2026, 11, 3, 9, 0);
        assertFalse(AutoBackup.isDue(AutoBackup.WEEKLY, last, at(2026, 11, 9, 23, 0), SAO_PAULO));
        assertTrue(AutoBackup.isDue(AutoBackup.WEEKLY, last, at(2026, 11, 10, 6, 0), SAO_PAULO));
    }

    @Test
    public void clockSetBack_isNotDue() {
        assertFalse(AutoBackup.isDue(AutoBackup.DAILY, at(2026, 11, 3, 9, 0), at(2026, 10, 1, 9, 0), SAO_PAULO));
    }

    @Test
    public void theLocalDayDependsOnTheZone() {
        // 23:30 on 3 Nov in São Paulo is already 4 Nov in UTC: the same two instants are one day apart there, none here.
        long last = at(2026, 11, 3, 22, 0);
        long now = at(2026, 11, 3, 23, 30);
        assertFalse(AutoBackup.isDue(AutoBackup.DAILY, last, now, SAO_PAULO));
        assertTrue(AutoBackup.isDue(AutoBackup.DAILY, last, now + 60 * 60 * 1000L, SAO_PAULO)); // 00:30 next day
    }

    /* ------------------------------ preferences ------------------------------ */

    @Test
    public void interval_defaultsToDaily_andIsStored() {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals(AutoBackup.DAILY, AutoBackup.intervalDays(context));

        AutoBackup.setIntervalDays(context, AutoBackup.WEEKLY);
        assertEquals(AutoBackup.WEEKLY, AutoBackup.intervalDays(context));

        AutoBackup.setIntervalDays(context, AutoBackup.OFF);
        assertEquals(AutoBackup.OFF, AutoBackup.intervalDays(context));
    }

    @Test
    public void interval_ignoresAnUnknownStoredValue() {
        Context context = RuntimeEnvironment.getApplication();
        AutoBackup.setIntervalDays(context, 42);
        assertEquals(AutoBackup.DAILY, AutoBackup.intervalDays(context));
    }

    /* ------------------------ scheduled vs. the user's own snapshots ------------------------ */

    private static File snapshot(Context context, String name, long modified) throws Exception {
        File f = new File(DbBackup.backupDir(context), name);
        assertTrue(f.createNewFile());
        assertTrue(f.setLastModified(modified));
        return f;
    }

    @Test
    public void pruneOldSnapshots_neverTouchesScheduledOnes_andScheduledPruningNeverTouchesTheUsersOwn() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        long base = System.currentTimeMillis() - 100_000L;
        File manual1 = snapshot(context, "manual_1.zip", base + 1000);
        File manual2 = snapshot(context, "manual_2.zip", base + 2000);
        File manual3 = snapshot(context, "manual_3.zip", base + 3000);
        File auto1 = snapshot(context, "auto_1.zip", base + 4000);
        // Newer than every manual one: counting them together would push the manual ones out.
        File sched1 = snapshot(context, "scheduled_1.zip", base + 5000);
        File sched2 = snapshot(context, "scheduled_2.zip", base + 6000);
        File sched3 = snapshot(context, "scheduled_3.zip", base + 7000);

        DbBackup.pruneOldSnapshots(context, 2); // the 2 newest of manual+auto: auto_1 and manual_3
        assertFalse(manual1.exists());
        assertFalse(manual2.exists());
        assertTrue(manual3.exists());
        assertTrue(auto1.exists());
        assertTrue(sched1.exists() && sched2.exists() && sched3.exists());

        DbBackup.pruneScheduledSnapshots(context, 2); // keeps scheduled_3 and scheduled_2
        assertFalse(sched1.exists());
        assertTrue(sched2.exists() && sched3.exists());
        assertTrue(manual3.exists() && auto1.exists());
    }

    @Test
    public void isScheduled_goesByPrefix() {
        assertTrue(DbBackup.isScheduled(new File("scheduled_20260101_000000.zip")));
        assertFalse(DbBackup.isScheduled(new File("manual_20260101_000000.zip")));
        assertFalse(DbBackup.isScheduled(new File("auto_20260101_000000.zip")));
        assertFalse(DbBackup.isManual(new File("scheduled_20260101_000000.zip")));
    }

    /* ------------------------------------ the whole flow ------------------------------------ */

    private static void idleUntil(java.util.function.BooleanSupplier done) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(5);
        }
        assertTrue("timed out", done.getAsBoolean());
    }

    // The zip appears on disk a moment before the completion callback is posted to the main thread; let it run.
    private static void settle() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(15);
        }
    }

    private static int scheduledCount(Context context) {
        int n = 0;
        for (File f : DbBackup.listSnapshots(context)) if (DbBackup.isScheduled(f)) n++;
        return n;
    }

    @Test
    public void runIfDue_backsUpOnce_thenWaitsForTheNextDay_andRespectsOff() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Database database = new Database(context);

        AutoBackup.setIntervalDays(context, AutoBackup.OFF);
        AutoBackup.runIfDue(context, database);
        settle();
        assertEquals("off means no backup", 0, scheduledCount(context));

        AutoBackup.setIntervalDays(context, AutoBackup.DAILY);
        AutoBackup.runIfDue(context, database);
        idleUntil(() -> scheduledCount(context) == 1);
        settle();

        // Called again right away (the app opening and the web server starting): nothing more to do.
        AutoBackup.runIfDue(context, database);
        settle();
        assertEquals(1, scheduledCount(context));

        // A day later it is due again. The file is renamed as well as aged: backups are named to the second,
        // so on a fast machine the next one would otherwise land on the same name and overwrite it.
        for (File f : DbBackup.listSnapshots(context)) {
            if (!DbBackup.isScheduled(f)) continue;
            File aged = new File(f.getParentFile(), "scheduled_20200101_000000.zip");
            assertTrue(f.renameTo(aged));
            assertTrue(aged.setLastModified(System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L));
        }
        AutoBackup.runIfDue(context, database);
        idleUntil(() -> scheduledCount(context) == 2);
        settle();
    }

    @Test
    public void runIfDue_keepsOnlyTheNewestScheduledSnapshots() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Database database = new Database(context);
        long old = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
        for (int i = 0; i < AutoBackup.KEEP_SCHEDULED; i++) snapshot(context, "scheduled_old" + i + ".zip", old + i * 1000L);
        assertEquals(AutoBackup.KEEP_SCHEDULED, scheduledCount(context));

        AutoBackup.runIfDue(context, database); // the newest of them is a month old: due
        idleUntil(() -> {
            File[] all = DbBackup.listSnapshots(context);
            return all.length > 0 && !all[0].getName().startsWith("scheduled_old");
        });
        idleUntil(() -> scheduledCount(context) == AutoBackup.KEEP_SCHEDULED);
        settle();
        assertEquals(AutoBackup.KEEP_SCHEDULED, scheduledCount(context));
        assertFalse("the oldest one made room", new File(DbBackup.backupDir(context), "scheduled_old0.zip").exists());
    }
}
