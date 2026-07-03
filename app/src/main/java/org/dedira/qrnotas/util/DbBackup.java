package org.dedira.qrnotas.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * File-level management of local backup snapshots used for the backup/restore screen. Each
 * snapshot is a zip containing the SQLite file ("qrgrades.db") and every student photo
 * ("photos/&lt;file&gt;"), so restoring one brings photos back too, not just rows.
 */
public class DbBackup {

    private static final String MANUAL_PREFIX = "manual_";
    private static final String AUTO_PREFIX = "auto_";
    private static final String DB_ENTRY = "qrgrades.db";
    private static final String PHOTOS_ENTRY_PREFIX = "photos/";

    private DbBackup() {
    }

    public static File backupDir(Context context) {
        File dir = new File(context.getFilesDir(), "backups");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File[] listSnapshots(Context context) {
        File[] files = backupDir(context).listFiles((dir, name) -> name.endsWith(".zip"));
        if (files == null) return new File[0];
        Arrays.sort(files, (first, second) -> Long.compare(second.lastModified(), first.lastModified()));
        return files;
    }

    public static boolean isManual(File snapshot) {
        return snapshot.getName().startsWith(MANUAL_PREFIX);
    }

    public static boolean deleteSnapshot(File snapshot) {
        return snapshot.delete();
    }

    public static void pruneOldSnapshots(Context context, int keep) {
        File[] snapshots = listSnapshots(context);
        for (int i = keep; i < snapshots.length; i++) snapshots[i].delete();
    }

    public static File newSnapshotFile(Context context, boolean manual) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String prefix = manual ? MANUAL_PREFIX : AUTO_PREFIX;
        return new File(backupDir(context), prefix + timestamp + ".zip");
    }

    /**
     * Creates a full snapshot (database + student photos). The database copy runs on
     * {@link Database}'s own single-thread executor, same guarantee as {@link Database#createSnapshot}
     * — it only runs once any pending write has committed. Zipping happens on a background thread
     * since a class roster's worth of photos is too much to do on the caller's thread.
     */
    public static void createFullSnapshot(Context context, Database database, boolean manual, IDatabaseOnResult listener) {
        File tempDb = new File(backupDir(context), ".tmp_" + System.nanoTime() + ".db");
        database.createSnapshot(tempDb, (success, error) -> {
            if (!success) {
                tempDb.delete();
                listener.onResult(false, error);
                return;
            }

            new Thread(() -> {
                File zipFile = newSnapshotFile(context, manual);
                boolean zipSuccess;
                String zipError = null;
                try {
                    zipDatabaseAndPhotos(context, tempDb, zipFile);
                    zipSuccess = true;
                } catch (IOException e) {
                    zipSuccess = false;
                    zipError = e.getMessage();
                    zipFile.delete();
                } finally {
                    tempDb.delete();
                }

                boolean finalSuccess = zipSuccess;
                String finalError = zipError;
                new Handler(Looper.getMainLooper()).post(() -> listener.onResult(finalSuccess, finalError));
            }).start();
        });
    }

    private static void zipDatabaseAndPhotos(Context context, File dbFile, File destZip) throws IOException {
        File photoDir = BitmapConverter.getPhotoDir(context);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(destZip))) {
            writeZipEntry(zip, dbFile, DB_ENTRY);

            File[] photos = photoDir.listFiles();
            if (photos != null) {
                for (File photo : photos) {
                    if (photo.isFile()) writeZipEntry(zip, photo, PHOTOS_ENTRY_PREFIX + photo.getName());
                }
            }
        }
    }

    private static void writeZipEntry(ZipOutputStream zip, File source, String entryName) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        try (FileInputStream in = new FileInputStream(source)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) zip.write(buffer, 0, read);
        }
        zip.closeEntry();
    }

    /**
     * Restores the database and every photo from {@code snapshot}, replacing whatever is
     * currently there. Clears any stray rollback journal first (the app never enables WAL, so
     * only a "-journal" sidecar can exist, and only while a write is mid-flight — none should be
     * present here since this runs after the DB connection has been abandoned by the caller in
     * favor of a full process restart) and clears the existing photo directory so photos removed
     * since the snapshot don't linger.
     */
    public static void restoreSnapshot(Context context, File snapshot) throws IOException {
        File dbFile = context.getDatabasePath(StudentDbHelper.DB_NAME);
        File journal = new File(dbFile.getPath() + "-journal");
        if (journal.exists()) journal.delete();

        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        File photoDir = BitmapConverter.getPhotoDir(context);
        File[] existingPhotos = photoDir.listFiles();
        if (existingPhotos != null) for (File photo : existingPhotos) photo.delete();

        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(snapshot))) {
            byte[] buffer = new byte[8192];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File target = resolveEntryTarget(entry.getName(), dbFile, photoDir);
                if (target != null) {
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) out.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static File resolveEntryTarget(String entryName, File dbFile, File photoDir) {
        if (entryName.contains("..")) return null;
        if (entryName.equals(DB_ENTRY)) return dbFile;
        if (entryName.startsWith(PHOTOS_ENTRY_PREFIX)) {
            return new File(photoDir, entryName.substring(PHOTOS_ENTRY_PREFIX.length()));
        }
        return null;
    }
}
