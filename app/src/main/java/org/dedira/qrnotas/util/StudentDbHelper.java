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
    private static final int DB_VERSION = 5;

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

    // One row per bathroom trip. "returned_at" stays NULL while the student is out; "evaded"
    // is set once a trip goes unreturned for longer than Database.BATHROOM_EVASION_MS.
    public static final String TABLE_BATHROOM_VISITS = "bathroom_visits";
    public static final String COL_BATHROOM_ID = "id";
    public static final String COL_BATHROOM_STUDENT_ID = "student_id";
    public static final String COL_BATHROOM_WENT_AT = "went_at";
    public static final String COL_BATHROOM_RETURNED_AT = "returned_at";
    public static final String COL_BATHROOM_EVADED = "evaded";

    // One row per indiscipline record a teacher registers against a student.
    public static final String TABLE_INDISCIPLINE_EVENTS = "indiscipline_events";
    public static final String COL_INDISCIPLINE_ID = "id";
    public static final String COL_INDISCIPLINE_STUDENT_ID = "student_id";
    public static final String COL_INDISCIPLINE_DISCIPLINE_ID = "discipline_id";
    public static final String COL_INDISCIPLINE_NOTE = "note";
    public static final String COL_INDISCIPLINE_CREATED_AT = "created_at";

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
                COL_CLASS_GROUP_NAME + " TEXT NOT NULL)");

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

        db.execSQL("CREATE TABLE " + TABLE_BATHROOM_VISITS + " (" +
                COL_BATHROOM_ID + " TEXT PRIMARY KEY, " +
                COL_BATHROOM_STUDENT_ID + " TEXT NOT NULL, " +
                COL_BATHROOM_WENT_AT + " INTEGER NOT NULL, " +
                COL_BATHROOM_RETURNED_AT + " INTEGER, " +
                COL_BATHROOM_EVADED + " INTEGER NOT NULL DEFAULT 0)");

        db.execSQL("CREATE TABLE " + TABLE_INDISCIPLINE_EVENTS + " (" +
                COL_INDISCIPLINE_ID + " TEXT PRIMARY KEY, " +
                COL_INDISCIPLINE_STUDENT_ID + " TEXT NOT NULL, " +
                COL_INDISCIPLINE_DISCIPLINE_ID + " TEXT, " +
                COL_INDISCIPLINE_NOTE + " TEXT, " +
                COL_INDISCIPLINE_CREATED_AT + " INTEGER NOT NULL)");
    }

    /**
     * Called automatically by SQLiteOpenHelper when a user upgrades to an app version with a
     * higher {@link #DB_VERSION} than what's stored in their existing database file. This simple
     * (destructive) migration just drops every table and recreates them from scratch — acceptable
     * here because the app also has explicit export/import and local backup features for users
     * who need to preserve data across an upgrade, but it does mean any data not backed up is lost.
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Drop in reverse-ish dependency order (tables that reference others first) purely by
        // convention; SQLite here has no foreign-key constraints enabled, so order doesn't
        // actually matter for correctness, just tidiness.
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_INDISCIPLINE_EVENTS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_BATHROOM_VISITS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_POINTS_HISTORY);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_ENROLLMENTS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_STUDENTS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_GOALS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CLASS_GROUPS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_DISCIPLINES);
        onCreate(db);
    }
}
