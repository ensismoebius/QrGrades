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
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Defines the SQLite database schema (table/column names and CREATE TABLE statements) and handles
 * creating/upgrading the database file. {@link SQLiteOpenHelper} is the standard Android base
 * class for this: it lazily creates the database file the first time it's needed, and calls
 * {@link #onUpgrade} automatically whenever {@link #DB_VERSION} is bumped in a new app release, so
 * existing users' data gets migrated instead of silently breaking. All actual reading/writing of
 * rows happens elsewhere (see {@link Database}); this class only owns the schema shape.
 */
public class StudentDbHelper extends SQLiteOpenHelper {
    // Physical filename of the SQLite database inside the app's private storage.
    static final String DB_NAME = "qrgrades.db";
    // Bump this whenever the schema changes (new table/column/etc.) — SQLiteOpenHelper compares
    // this against the version stored in the existing DB file and calls onUpgrade() if it's higher.
    private static final int DB_VERSION = 8;

    public static final String TABLE_STUDENTS = "students";
    public static final String COL_ID = "id";
    public static final String COL_NAME = "name";
    public static final String COL_PHOTO_PATH = "photo_path";

    public static final String TABLE_DISCIPLINES = "disciplines";
    public static final String COL_DISCIPLINE_ID = "id";
    public static final String COL_DISCIPLINE_NAME = "name";

    public static final String TABLE_CLASS_GROUPS = "class_groups";
    public static final String COL_CLASS_GROUP_ID_PK = "id";
    public static final String COL_CLASS_GROUP_DISCIPLINE_ID = "discipline_id";
    public static final String COL_CLASS_GROUP_NAME = "name";
    // NULL while the class group is active; set to the close timestamp by "Fechar período e
    // turma" (see TABLE_PERIODS) once its students have been archived or moved to a new class.
    public static final String COL_CLASS_GROUP_CLOSED_AT = "closed_at";

    public static final String TABLE_GOALS = "goals";
    public static final String COL_GOAL_ID = "id";
    public static final String COL_GOAL_DISCIPLINE_ID = "discipline_id";
    public static final String COL_GOAL_NAME = "name";
    public static final String COL_GOAL_TARGET_POINTS = "target_points";

    // A student's participation in a single class group / discipline, with its own points total.
    // A student can have several enrollments (one per discipline) to support multi-discipline participation.
    public static final String TABLE_ENROLLMENTS = "enrollments";
    public static final String COL_ENROLLMENT_ID = "id";
    public static final String COL_ENROLLMENT_STUDENT_ID = "student_id";
    public static final String COL_ENROLLMENT_CLASS_GROUP_ID = "class_group_id";
    public static final String COL_ENROLLMENT_GRADES = "grades";

    public static final String TABLE_POINTS_HISTORY = "points_history";
    public static final String COL_HISTORY_ID = "id";
    public static final String COL_HISTORY_ENROLLMENT_ID = "enrollment_id";
    public static final String COL_HISTORY_POINTS_DELTA = "points_delta";
    public static final String COL_HISTORY_NOTE = "note";
    public static final String COL_HISTORY_CREATED_AT = "created_at";

    // One row per QR-code scan/identification, regardless of what it was used for — lets the app
    // flag when the same student's code is scanned more than once in a day, e.g. to catch a
    // student re-scanning to exploit a busy teacher's memory.
    public static final String TABLE_QR_SCANS = "qr_scans";
    public static final String COL_QR_SCAN_ID = "id";
    public static final String COL_QR_SCAN_STUDENT_ID = "student_id";
    public static final String COL_QR_SCAN_AT = "scanned_at";

    // One row per "Fechar período" (whole discipline) or "Fechar período e turma" (one class
    // group) action. Consolidating a period never deletes anything — it snapshots every
    // affected enrollment's final total into TABLE_PERIOD_SNAPSHOTS (so past totals stay
    // reportable) and resets those enrollments' running total back to 0 to start the new count.
    public static final String TABLE_PERIODS = "periods";
    public static final String COL_PERIOD_ID = "id";
    public static final String COL_PERIOD_DISCIPLINE_ID = "discipline_id";
    // Optional teacher-given name (e.g. "Bimestre 1"); falls back to showing the close date when blank.
    public static final String COL_PERIOD_LABEL = "label";
    public static final String COL_PERIOD_CLOSED_AT = "closed_at";
    // NULL for a discipline-wide "Fechar período" close; set to the class group's id for a
    // "Fechar período e turma" close (that class group's COL_CLASS_GROUP_CLOSED_AT is set to match).
    public static final String COL_PERIOD_CLOSED_CLASS_GROUP_ID = "closed_class_group_id";
    // NULL unless the closed class group's students were moved into a brand-new class group
    // ("Fechar período e turma" with reassignment, as opposed to just archiving it in place).
    // Editable afterward via Database#changePeriodReassignmentTarget without undoing the close.
    public static final String COL_PERIOD_REASSIGNED_TO_CLASS_GROUP_ID = "reassigned_to_class_group_id";
    // NULL while the closure stands; set to the undo timestamp once Database#reopenPeriod has
    // restored the snapshotted totals and reverted any class-group archive/reassignment.
    public static final String COL_PERIOD_REOPENED_AT = "reopened_at";

    // One row per enrollment affected by a period close, capturing its final point total at
    // that moment (student/class-group names are denormalized so a report or undo still works
    // even if the student, class group, or enrollment itself is later deleted).
    public static final String TABLE_PERIOD_SNAPSHOTS = "period_snapshots";
    public static final String COL_PERIOD_SNAPSHOT_ID = "id";
    public static final String COL_PERIOD_SNAPSHOT_PERIOD_ID = "period_id";
    public static final String COL_PERIOD_SNAPSHOT_ENROLLMENT_ID = "enrollment_id";
    public static final String COL_PERIOD_SNAPSHOT_STUDENT_ID = "student_id";
    public static final String COL_PERIOD_SNAPSHOT_CLASS_GROUP_ID = "class_group_id";
    public static final String COL_PERIOD_SNAPSHOT_POINTS = "points";

    /** Creates the helper. The actual DB file isn't touched yet — SQLiteOpenHelper opens it lazily on first use. */
    public StudentDbHelper(Context context) {
        // Always use the application Context (not an Activity Context) here: the helper may
        // outlive any single screen, and holding an Activity Context would leak memory.
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    /** Called automatically by SQLiteOpenHelper the very first time the database file is opened, to create all tables. */
    @Override
    public void onCreate(SQLiteDatabase db) {
        // Each execSQL call below builds a "CREATE TABLE" statement by string-concatenating the
        // table/column name constants declared above, then runs it. TEXT PRIMARY KEY is used
        // (instead of an auto-incrementing INTEGER id) because ids in this app are generated as
        // UUID strings elsewhere, not assigned by the database.
        db.execSQL("CREATE TABLE " + TABLE_DISCIPLINES + " (" +
                COL_DISCIPLINE_ID + " TEXT PRIMARY KEY, " +
                COL_DISCIPLINE_NAME + " TEXT NOT NULL)");

        db.execSQL("CREATE TABLE " + TABLE_CLASS_GROUPS + " (" +
                COL_CLASS_GROUP_ID_PK + " TEXT PRIMARY KEY, " +
                COL_CLASS_GROUP_DISCIPLINE_ID + " TEXT NOT NULL, " +
                COL_CLASS_GROUP_NAME + " TEXT NOT NULL, " +
                COL_CLASS_GROUP_CLOSED_AT + " INTEGER)");

        db.execSQL("CREATE TABLE " + TABLE_GOALS + " (" +
                COL_GOAL_ID + " TEXT PRIMARY KEY, " +
                COL_GOAL_DISCIPLINE_ID + " TEXT NOT NULL, " +
                COL_GOAL_NAME + " TEXT NOT NULL, " +
                COL_GOAL_TARGET_POINTS + " INTEGER NOT NULL DEFAULT 0)");

        db.execSQL("CREATE TABLE " + TABLE_STUDENTS + " (" +
                COL_ID + " TEXT PRIMARY KEY, " +
                COL_NAME + " TEXT NOT NULL, " +
                COL_PHOTO_PATH + " TEXT)");

        // UNIQUE(student_id, class_group_id) prevents the same student from being enrolled twice
        // in the same class group (the database itself enforces this, not just app logic).
        db.execSQL("CREATE TABLE " + TABLE_ENROLLMENTS + " (" +
                COL_ENROLLMENT_ID + " TEXT PRIMARY KEY, " +
                COL_ENROLLMENT_STUDENT_ID + " TEXT NOT NULL, " +
                COL_ENROLLMENT_CLASS_GROUP_ID + " TEXT NOT NULL, " +
                COL_ENROLLMENT_GRADES + " INTEGER NOT NULL DEFAULT 0, " +
                "UNIQUE(" + COL_ENROLLMENT_STUDENT_ID + ", " + COL_ENROLLMENT_CLASS_GROUP_ID + "))");

        // One row per point award/deduction, so the app can show a full history/audit trail per
        // enrollment rather than just a running total.
        db.execSQL("CREATE TABLE " + TABLE_POINTS_HISTORY + " (" +
                COL_HISTORY_ID + " TEXT PRIMARY KEY, " +
                COL_HISTORY_ENROLLMENT_ID + " TEXT NOT NULL, " +
                COL_HISTORY_POINTS_DELTA + " INTEGER NOT NULL, " +
                COL_HISTORY_NOTE + " TEXT, " +
                COL_HISTORY_CREATED_AT + " INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE " + TABLE_QR_SCANS + " (" +
                COL_QR_SCAN_ID + " TEXT PRIMARY KEY, " +
                COL_QR_SCAN_STUDENT_ID + " TEXT NOT NULL, " +
                COL_QR_SCAN_AT + " INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE " + TABLE_PERIODS + " (" +
                COL_PERIOD_ID + " TEXT PRIMARY KEY, " +
                COL_PERIOD_DISCIPLINE_ID + " TEXT NOT NULL, " +
                COL_PERIOD_LABEL + " TEXT, " +
                COL_PERIOD_CLOSED_AT + " INTEGER NOT NULL, " +
                COL_PERIOD_CLOSED_CLASS_GROUP_ID + " TEXT, " +
                COL_PERIOD_REASSIGNED_TO_CLASS_GROUP_ID + " TEXT, " +
                COL_PERIOD_REOPENED_AT + " INTEGER)");

        db.execSQL("CREATE TABLE " + TABLE_PERIOD_SNAPSHOTS + " (" +
                COL_PERIOD_SNAPSHOT_ID + " TEXT PRIMARY KEY, " +
                COL_PERIOD_SNAPSHOT_PERIOD_ID + " TEXT NOT NULL, " +
                COL_PERIOD_SNAPSHOT_ENROLLMENT_ID + " TEXT NOT NULL, " +
                COL_PERIOD_SNAPSHOT_STUDENT_ID + " TEXT NOT NULL, " +
                COL_PERIOD_SNAPSHOT_CLASS_GROUP_ID + " TEXT NOT NULL, " +
                COL_PERIOD_SNAPSHOT_POINTS + " INTEGER NOT NULL)");
    }

    /** True if {@code column} already exists on {@code table} — lets an additive migration add a column exactly once without erroring on re-run. */
    private static boolean columnExists(SQLiteDatabase db, String table, String column) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int nameIndex = cursor.getColumnIndexOrThrow("name");
            while (cursor.moveToNext()) {
                if (column.equalsIgnoreCase(cursor.getString(nameIndex))) return true;
            }
        }
        return false;
    }

    /**
     * Called automatically by SQLiteOpenHelper when a user upgrades to an app version with a
     * higher {@link #DB_VERSION} than what's stored in their existing database file. Versions up
     * through 5 used a simple (destructive) migration that dropped every table and recreated them
     * from scratch, which was acceptable then because the app also has explicit export/import and
     * local backup features for users who need to preserve data across an upgrade. Version 6 only
     * adds {@link #TABLE_QR_SCANS} — every other table is identical to what {@link #onCreate}
     * already produced for any pre-6 install — so this step is additive instead: no reason to wipe
     * a teacher's students/points/history just to gain a QR-rescan counter. IF NOT EXISTS also
     * makes this safe to run more than once.
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_QR_SCANS + " (" +
                COL_QR_SCAN_ID + " TEXT PRIMARY KEY, " +
                COL_QR_SCAN_STUDENT_ID + " TEXT NOT NULL, " +
                COL_QR_SCAN_AT + " INTEGER NOT NULL)");

        // Version 7 adds "Fechar período"/"Fechar período e turma": two new tables, plus a
        // closed_at column on the pre-existing class_groups table. Every other table is
        // untouched, so — same reasoning as the version 6 step above — this stays additive
        // rather than dropping and recreating anything.
        if (!columnExists(db, TABLE_CLASS_GROUPS, COL_CLASS_GROUP_CLOSED_AT)) {
            db.execSQL("ALTER TABLE " + TABLE_CLASS_GROUPS + " ADD COLUMN " + COL_CLASS_GROUP_CLOSED_AT + " INTEGER");
        }

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_PERIODS + " (" +
                COL_PERIOD_ID + " TEXT PRIMARY KEY, " +
                COL_PERIOD_DISCIPLINE_ID + " TEXT NOT NULL, " +
                COL_PERIOD_LABEL + " TEXT, " +
                COL_PERIOD_CLOSED_AT + " INTEGER NOT NULL, " +
                COL_PERIOD_CLOSED_CLASS_GROUP_ID + " TEXT, " +
                COL_PERIOD_REASSIGNED_TO_CLASS_GROUP_ID + " TEXT, " +
                COL_PERIOD_REOPENED_AT + " INTEGER)");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_PERIOD_SNAPSHOTS + " (" +
                COL_PERIOD_SNAPSHOT_ID + " TEXT PRIMARY KEY, " +
                COL_PERIOD_SNAPSHOT_PERIOD_ID + " TEXT NOT NULL, " +
                COL_PERIOD_SNAPSHOT_ENROLLMENT_ID + " TEXT NOT NULL, " +
                COL_PERIOD_SNAPSHOT_STUDENT_ID + " TEXT NOT NULL, " +
                COL_PERIOD_SNAPSHOT_CLASS_GROUP_ID + " TEXT NOT NULL, " +
                COL_PERIOD_SNAPSHOT_POINTS + " INTEGER NOT NULL)");

        // Version 8 removes the bathroom-visit and indiscipline-tracking features entirely, so
        // unlike every step above, this one is intentionally destructive: the two tables that
        // backed them are no longer read or written anywhere, and the app's other export/backup
        // features were never how that particular data was meant to be preserved. IF EXISTS makes
        // this safe to run more than once.
        db.execSQL("DROP TABLE IF EXISTS bathroom_visits");
        db.execSQL("DROP TABLE IF EXISTS indiscipline_events");
    }
}
