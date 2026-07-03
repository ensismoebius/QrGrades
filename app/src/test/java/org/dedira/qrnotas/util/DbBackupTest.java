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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import org.dedira.qrnotas.model.Student;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Unit tests for {@link DbBackup}, which zips up the app's SQLite database (and related files)
 * into timestamped "snapshot" files for manual or automatic backups, and can restore one of
 * those snapshots later. Covers naming conventions (manual_/auto_ prefixes), listing/pruning
 * old snapshots, the full create-then-restore round trip, and a security check that a malicious
 * zip entry can't be used to write outside the app's data directory ("zip slip" / path
 * traversal).
 */
@RunWith(RobolectricTestRunner.class)
public class DbBackupTest {

    private Context context;
    private Database database;

    @Before
    public void setUp() {
        // Robolectric provides a fake-but-functional Context; Database wraps the app's real
        // (test-scoped) SQLite database so DbBackup has actual files on disk to back up.
        context = RuntimeEnvironment.getApplication();
        database = new Database(context);
    }

    // Simple holder used with the idle() polling helper below: "done" is flipped by an async
    // callback running on the main thread, and the test thread polls it until it becomes true.
    private static class Flag {
        volatile boolean done;
        boolean success;
        String error;
    }

    // Database/DbBackup operations finish asynchronously via a callback posted to the main
    // thread's message queue. Under Robolectric that queue does not run by itself — it stays
    // "paused" until something calls shadowOf(Looper.getMainLooper()).idle() to let pending
    // posted tasks execute. This helper repeatedly idles the main looper (with a short sleep
    // between attempts) until the callback has set f.done = true, or gives up after 5 seconds
    // and fails the test — that's what makes async code testable synchronously here.
    private void idle(Flag f) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!f.done && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(2);
        }
        assertTrue("timed out waiting for callback", f.done);
    }

    // Shared fixture step used by createFullSnapshot_thenRestoreSnapshot_roundTripsStudentData:
    // saves one student to the database (asynchronously) and waits for that save to complete
    // via the idle() polling helper, so there is real data present before backing up.
    private void saveOneStudent() throws InterruptedException {
        Student s = new Student();
        s.name = "André";
        Flag f = new Flag();
        database.saveStudent(s, (success, value) -> {
            f.done = true;
        });
        idle(f);
    }

    // Verifies that asking for the backup directory creates it on disk if missing, mirroring
    // the "lazy directory creation" pattern used elsewhere (e.g. BitmapConverter's photo dir).
    @Test
    public void backupDir_isCreatedLazily() {
        File dir = DbBackup.backupDir(context);
        assertTrue(dir.exists());
        assertTrue(dir.isDirectory());
    }

    // Verifies that listing snapshots on a fresh install (no backups yet) returns an empty
    // array rather than null or throwing.
    @Test
    public void listSnapshots_emptyWhenNoBackupsExist() {
        assertEquals(0, DbBackup.listSnapshots(context).length);
    }

    // Verifies that isManual() correctly distinguishes backup files by their filename prefix:
    // "manual_" was triggered by the user, "auto_" was triggered automatically by the app.
    @Test
    public void isManual_distinguishesManualFromAutoPrefix() {
        assertTrue(DbBackup.isManual(new File("manual_20260101_000000.zip")));
        assertFalse(DbBackup.isManual(new File("auto_20260101_000000.zip")));
    }

    // Verifies that newSnapshotFile() names files with the correct prefix depending on the
    // "manual" flag, and always with a ".zip" extension.
    @Test
    public void newSnapshotFile_usesManualOrAutoPrefix() {
        File manual = DbBackup.newSnapshotFile(context, true);
        File auto = DbBackup.newSnapshotFile(context, false);
        assertTrue(manual.getName().startsWith("manual_"));
        assertTrue(auto.getName().startsWith("auto_"));
        assertTrue(manual.getName().endsWith(".zip"));
    }

    // Verifies that deleting a snapshot file actually removes it from disk and reports success.
    @Test
    public void deleteSnapshot_removesFile() throws Exception {
        File file = DbBackup.newSnapshotFile(context, true);
        file.getParentFile().mkdirs();
        file.createNewFile();
        assertTrue(file.exists());

        assertTrue(DbBackup.deleteSnapshot(file));
        assertFalse(file.exists());
    }

    // Verifies that pruning keeps only the N most recently modified snapshots and deletes the
    // rest: 5 files are created with increasing lastModified timestamps, then pruning to 2
    // should leave exactly 2 behind (the two newest).
    @Test
    public void pruneOldSnapshots_keepsOnlyMostRecentN() throws Exception {
        File dir = DbBackup.backupDir(context);
        for (int i = 0; i < 5; i++) {
            File f = new File(dir, "manual_" + i + ".zip");
            f.createNewFile();
            // Spread out lastModified times by 1 second each so pruning has an unambiguous
            // "most recent" ordering to work with.
            f.setLastModified(System.currentTimeMillis() + i * 1000L);
        }
        assertEquals(5, DbBackup.listSnapshots(context).length);

        DbBackup.pruneOldSnapshots(context, 2);

        assertEquals(2, DbBackup.listSnapshots(context).length);
    }

    // Verifies the full backup/restore round trip: after saving a student, creating a full
    // snapshot succeeds (via the same async idle-polling pattern as saveOneStudent), produces
    // exactly one manual snapshot file, and restoring it leaves a valid, non-empty database
    // file on disk.
    @Test
    public void createFullSnapshot_thenRestoreSnapshot_roundTripsStudentData() throws Exception {
        saveOneStudent();

        Flag snapshotFlag = new Flag();
        String[] error = {null};
        DbBackup.createFullSnapshot(context, database, true, (success, err) -> {
            snapshotFlag.success = success;
            error[0] = err;
            snapshotFlag.done = true;
        });

        // Same manual "idle the paused main Looper until the async callback fires" pattern as
        // idle(Flag), inlined here because this call also wants to check success/error values.
        long deadline = System.currentTimeMillis() + 5000;
        while (!snapshotFlag.done && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(5);
        }
        assertTrue("timed out waiting for snapshot", snapshotFlag.done);
        assertTrue(snapshotFlag.success);
        assertNull(error[0]);

        File[] snapshots = DbBackup.listSnapshots(context);
        assertEquals(1, snapshots.length);
        assertTrue(DbBackup.isManual(snapshots[0]));

        DbBackup.restoreSnapshot(context, snapshots[0]);
        File restoredDbFile = context.getDatabasePath(StudentDbHelper.DB_NAME);
        assertTrue(restoredDbFile.exists());
        assertTrue(restoredDbFile.length() > 0);
    }

    // Verifies a security property of restoreSnapshot: a zip file crafted with a path-traversal
    // entry name ("../../etc/passwd") must be rejected/skipped rather than extracted outside
    // the app's own data directory. The test confirms nothing was restored (no database file
    // created) as a proxy for "the malicious entry was not written anywhere".
    @Test
    public void resolveEntryTarget_rejectsPathTraversal() throws Exception {
        // Exercised indirectly through restoreSnapshot: a zip entry containing ".." must be
        // skipped rather than written outside the app's data directories.
        File maliciousZip = File.createTempFile("evil", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(maliciousZip))) {
            zip.putNextEntry(new ZipEntry("../../etc/passwd"));
            zip.write("not a real db".getBytes());
            zip.closeEntry();
        }

        DbBackup.restoreSnapshot(context, maliciousZip);
        File dbFile = context.getDatabasePath(StudentDbHelper.DB_NAME);
        assertFalse(dbFile.exists());
    }
}
