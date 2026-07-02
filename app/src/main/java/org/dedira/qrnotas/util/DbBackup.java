package org.dedira.qrnotas.util;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/** File-level management of local DB snapshots used for the backup/restore screen. */
public class DbBackup {

    private static final String MANUAL_PREFIX = "manual_";
    private static final String AUTO_PREFIX = "auto_";

    private DbBackup() {
    }

    public static File backupDir(Context context) {
        File dir = new File(context.getFilesDir(), "backups");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File[] listSnapshots(Context context) {
        File[] files = backupDir(context).listFiles((dir, name) -> name.endsWith(".db"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
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
        return new File(backupDir(context), prefix + timestamp + ".db");
    }

    /**
     * Copies {@code snapshot} back over the live database file, clearing any stray rollback
     * journal first (the app never enables WAL, so only a "-journal" sidecar can exist, and only
     * while a write is mid-flight — none should be present here since this runs after the DB
     * connection has been abandoned by the caller in favor of a full process restart).
     */
    public static void restoreSnapshot(Context context, File snapshot) throws IOException {
        File dbFile = context.getDatabasePath(StudentDbHelper.DB_NAME);
        File journal = new File(dbFile.getPath() + "-journal");
        if (journal.exists()) journal.delete();

        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        try (FileInputStream in = new FileInputStream(snapshot);
             FileOutputStream out = new FileOutputStream(dbFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }
}
