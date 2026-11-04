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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.Attendance;
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.CsvImportPlan;
import org.dedira.qrnotas.model.CsvRowError;
import org.dedira.qrnotas.model.CsvStudentRow;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Enrollment;
import org.dedira.qrnotas.model.Goal;
import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.Period;
import org.dedira.qrnotas.model.PeriodSnapshot;
import org.dedira.qrnotas.model.PointsAward;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.ResolvedCsvRow;
import org.dedira.qrnotas.model.RevertResult;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.model.IDatabaseOnDelete;
import org.dedira.qrnotas.model.IDatabaseOnLoad;
import org.dedira.qrnotas.model.IDatabaseOnResult;
import org.dedira.qrnotas.model.IDatabaseOnSave;
import org.dedira.qrnotas.model.IDatabaseOnUpdate;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Central data-access layer for every entity in the app (students, disciplines, class groups,
 * goals, points history), backed by SQLite via {@link StudentDbHelper}. Every public method here
 * follows the same async pattern: the actual database work runs on a single-thread
 * {@link #executor} (so writes never race each other), and the result is posted back to the
 * caller's listener on the main thread via {@link #postResult} — meaning it's always safe to
 * touch UI directly inside a listener's callback. {@link DatabaseSync} builds a synchronous
 * bridge on top of this for use from non-UI threads (e.g. the LAN web server).
 */
public class Database {
    private final Context appContext;
    private final StudentDbHelper dbHelper;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public Database(Context context) {
        this.appContext = context.getApplicationContext();
        dbHelper = new StudentDbHelper(context);
    }

    /** Hands a callback back to the main thread — every listener invocation in this class goes through here. */
    private void postResult(Runnable r) {
        mainHandler.post(r);
    }

    /* --------------------------- Cursor → model mapping ----------------------------- */
    // One small "from cursor" method per table, isolating the column-name lookups so the rest of
    // the class deals in plain model objects instead of raw Cursor indices.

    /** The column's text, or null if the column is missing from the result set or the value is NULL — for columns added by later schema versions. */
    private static String optString(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return (index < 0 || cursor.isNull(index)) ? null : cursor.getString(index);
    }

    private static Student studentFromCursor(Cursor cursor) {
        Student s = new Student();
        s.id = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_ID));
        s.name = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_NAME));
        s.photoPath = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_PHOTO_PATH));
        s.qrToken = optString(cursor, StudentDbHelper.COL_QR_TOKEN);
        return s;
    }

    private static Enrollment enrollmentFromCursor(Cursor cursor) {
        Enrollment e = new Enrollment();
        e.id = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_ENROLLMENT_ID));
        e.studentId = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_ENROLLMENT_STUDENT_ID));
        e.classGroupId = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_ENROLLMENT_CLASS_GROUP_ID));
        e.grades = cursor.getInt(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_ENROLLMENT_GRADES));
        return e;
    }

    private static Discipline disciplineFromCursor(Cursor cursor) {
        Discipline d = new Discipline();
        d.id = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_DISCIPLINE_ID));
        d.name = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_DISCIPLINE_NAME));
        return d;
    }

    private static ClassGroup classGroupFromCursor(Cursor cursor) {
        ClassGroup g = new ClassGroup();
        g.id = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_CLASS_GROUP_ID_PK));
        g.disciplineId = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_CLASS_GROUP_DISCIPLINE_ID));
        g.name = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_CLASS_GROUP_NAME));
        int closedAtIndex = cursor.getColumnIndexOrThrow(StudentDbHelper.COL_CLASS_GROUP_CLOSED_AT);
        g.closedAt = cursor.isNull(closedAtIndex) ? null : cursor.getLong(closedAtIndex);
        return g;
    }

    private static Goal goalFromCursor(Cursor cursor) {
        Goal g = new Goal();
        g.id = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_GOAL_ID));
        g.disciplineId = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_GOAL_DISCIPLINE_ID));
        g.name = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_GOAL_NAME));
        g.targetPoints = cursor.getInt(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_GOAL_TARGET_POINTS));
        g.startDate = optString(cursor, StudentDbHelper.COL_GOAL_START_DATE);
        g.endDate = optString(cursor, StudentDbHelper.COL_GOAL_END_DATE);
        return g;
    }

    private static PointsHistory pointsHistoryFromCursor(Cursor cursor) {
        PointsHistory h = new PointsHistory();
        h.id = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_HISTORY_ID));
        h.enrollmentId = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_HISTORY_ENROLLMENT_ID));
        h.pointsDelta = cursor.getInt(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_HISTORY_POINTS_DELTA));
        h.note = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_HISTORY_NOTE));
        h.createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_HISTORY_CREATED_AT));
        h.tag = optString(cursor, StudentDbHelper.COL_HISTORY_TAG);
        h.revertsId = optString(cursor, StudentDbHelper.COL_HISTORY_REVERTS_ID);
        return h;
    }

    private static Student queryStudentById(SQLiteDatabase db, String id) {
        try (Cursor cursor = db.query(StudentDbHelper.TABLE_STUDENTS, null,
                StudentDbHelper.COL_ID + "=?", new String[]{id}, null, null, null)) {
            if (cursor.moveToFirst()) return studentFromCursor(cursor);
        }
        return null;
    }

    private static Enrollment queryEnrollmentById(SQLiteDatabase db, String id) {
        try (Cursor cursor = db.query(StudentDbHelper.TABLE_ENROLLMENTS, null,
                StudentDbHelper.COL_ENROLLMENT_ID + "=?", new String[]{id}, null, null, null)) {
            if (cursor.moveToFirst()) return enrollmentFromCursor(cursor);
        }
        return null;
    }

    /** Case-insensitive comparison that treats null the same as an empty string, so sorting never throws on missing names. */
    private static int compareNullable(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.compareToIgnoreCase(b);
    }

    /* ------------------------------ Students ------------------------------ */

    /** Deletes a student along with every enrollment (and each enrollment's points history) they had — nothing is left orphaned. */
    public void deleteStudent(String studentId, final IDatabaseOnDelete<Student> listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();

            List<String> enrollmentIds = new ArrayList<>();
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_ENROLLMENTS,
                    new String[]{StudentDbHelper.COL_ENROLLMENT_ID},
                    StudentDbHelper.COL_ENROLLMENT_STUDENT_ID + "=?", new String[]{studentId}, null, null, null)) {
                while (cursor.moveToNext()) enrollmentIds.add(cursor.getString(0));
            }

            for (String enrollmentId : enrollmentIds) {
                db.delete(StudentDbHelper.TABLE_POINTS_HISTORY,
                        StudentDbHelper.COL_HISTORY_ENROLLMENT_ID + "=?", new String[]{enrollmentId});
            }
            db.delete(StudentDbHelper.TABLE_ENROLLMENTS,
                    StudentDbHelper.COL_ENROLLMENT_STUDENT_ID + "=?", new String[]{studentId});
            db.delete(StudentDbHelper.TABLE_QR_SCANS,
                    StudentDbHelper.COL_QR_SCAN_STUDENT_ID + "=?", new String[]{studentId});
            db.delete(StudentDbHelper.TABLE_PERIOD_SNAPSHOTS,
                    StudentDbHelper.COL_PERIOD_SNAPSHOT_STUDENT_ID + "=?", new String[]{studentId});
            db.delete(StudentDbHelper.TABLE_ATTENDANCE,
                    StudentDbHelper.COL_ATTENDANCE_STUDENT_ID + "=?", new String[]{studentId});

            int rows = db.delete(StudentDbHelper.TABLE_STUDENTS, StudentDbHelper.COL_ID + "=?", new String[]{studentId});
            boolean success = rows > 0;
            postResult(() -> listener.onLoadComplete(success, null));
        });
    }

    public void loadAllStudents(final IDatabaseOnLoad<ArrayList<Student>> listener) {
        executor.execute(() -> {
            ArrayList<Student> studentList = new ArrayList<>();
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_STUDENTS, null, null, null, null, null,
                    StudentDbHelper.COL_NAME + " ASC")) {
                while (cursor.moveToNext()) {
                    studentList.add(studentFromCursor(cursor));
                }
            }
            postResult(() -> listener.onLoadComplete(true, studentList));
        });
    }

    /** Loads only the students enrolled in one specific class group, via a join on enrollments. */
    public void loadStudentsForClassGroup(String classGroupId, final IDatabaseOnLoad<ArrayList<Student>> listener) {
        executor.execute(() -> {
            ArrayList<Student> studentList = new ArrayList<>();
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            String sql = "SELECT s.* FROM " + StudentDbHelper.TABLE_STUDENTS + " s"
                    + " JOIN " + StudentDbHelper.TABLE_ENROLLMENTS + " e ON e." + StudentDbHelper.COL_ENROLLMENT_STUDENT_ID
                    + " = s." + StudentDbHelper.COL_ID
                    + " WHERE e." + StudentDbHelper.COL_ENROLLMENT_CLASS_GROUP_ID + "=?"
                    + " ORDER BY s." + StudentDbHelper.COL_NAME + " ASC";
            try (Cursor cursor = db.rawQuery(sql, new String[]{classGroupId})) {
                while (cursor.moveToNext()) studentList.add(studentFromCursor(cursor));
            }
            postResult(() -> listener.onLoadComplete(true, studentList));
        });
    }

    /** Inserts a new student, or replaces the existing row if {@code s.id} is already set (create-or-update in one call). */
    public void saveStudent(Student s, final IDatabaseOnSave<Student> listener) {
        executor.execute(() -> {
            if (s.id == null) s.id = UUID.randomUUID().toString();

            ContentValues values = new ContentValues();
            values.put(StudentDbHelper.COL_ID, s.id);
            values.put(StudentDbHelper.COL_NAME, s.name);
            values.put(StudentDbHelper.COL_PHOTO_PATH, s.photoPath);

            SQLiteDatabase db = dbHelper.getWritableDatabase();
            // CONFLICT_REPLACE rewrites the whole row, so a caller that built the Student from
            // scratch (and never knew about the token) must not silently retire the student's
            // regenerated QR code: carry the stored token over instead. Regeneration itself goes
            // through regenerateQrToken, which always sets a new non-null token.
            if (s.qrToken == null) {
                Student existing = queryStudentById(db, s.id);
                if (existing != null) s.qrToken = existing.qrToken;
            }
            values.put(StudentDbHelper.COL_QR_TOKEN, s.qrToken);
            long result = db.insertWithOnConflict(StudentDbHelper.TABLE_STUDENTS, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
            boolean success = result != -1;

            postResult(() -> listener.onSaveComplete(success, s));
        });
    }

    public void loadStudent(String id, final IDatabaseOnLoad<Student> listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            Student s = queryStudentById(db, id);
            postResult(() -> listener.onLoadComplete(s != null, s));
        });
    }

    /**
     * Resolves whatever a scanned QR code decoded to into its student. A student whose code was
     * regenerated is found only by their token; one who never was is found by their id (their
     * original code). That asymmetry is the whole point: the moment a new token is issued, the
     * old printed code — which encodes the id — stops matching anyone.
     */
    public void loadStudentByQrCode(String code, final IDatabaseOnLoad<Student> listener) {
        executor.execute(() -> {
            Student s = null;
            if (code != null && !code.isEmpty()) {
                SQLiteDatabase db = dbHelper.getReadableDatabase();
                try (Cursor cursor = db.query(StudentDbHelper.TABLE_STUDENTS, null,
                        StudentDbHelper.COL_QR_TOKEN + "=? OR (" + StudentDbHelper.COL_QR_TOKEN + " IS NULL AND "
                                + StudentDbHelper.COL_ID + "=?)",
                        new String[]{code, code}, null, null, null, "1")) {
                    if (cursor.moveToFirst()) s = studentFromCursor(cursor);
                }
            }
            final Student found = s;
            postResult(() -> listener.onLoadComplete(found != null, found));
        });
    }

    /** Retires the student's current QR code by issuing a new random token; the returned student carries it. */
    public void regenerateQrToken(String studentId, final IDatabaseOnLoad<Student> listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(StudentDbHelper.COL_QR_TOKEN, UUID.randomUUID().toString());
            int rows = db.update(StudentDbHelper.TABLE_STUDENTS, values,
                    StudentDbHelper.COL_ID + "=?", new String[]{studentId});
            Student updated = rows > 0 ? queryStudentById(db, studentId) : null;
            postResult(() -> listener.onLoadComplete(updated != null, updated));
        });
    }

    /* ----------------------------- Enrollments ------------------------------ */

    /** Creates a new enrollment, or replaces the row at the given id if one was passed in. */
    public void saveEnrollment(Enrollment e, final IDatabaseOnSave<Enrollment> listener) {
        executor.execute(() -> {
            if (e.id == null) e.id = UUID.randomUUID().toString();

            ContentValues values = new ContentValues();
            values.put(StudentDbHelper.COL_ENROLLMENT_ID, e.id);
            values.put(StudentDbHelper.COL_ENROLLMENT_STUDENT_ID, e.studentId);
            values.put(StudentDbHelper.COL_ENROLLMENT_CLASS_GROUP_ID, e.classGroupId);
            values.put(StudentDbHelper.COL_ENROLLMENT_GRADES, e.grades);

            SQLiteDatabase db = dbHelper.getWritableDatabase();
            long result = db.insertWithOnConflict(StudentDbHelper.TABLE_ENROLLMENTS, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
            boolean success = result != -1;

            postResult(() -> listener.onSaveComplete(success, e));
        });
    }

    /** Deletes an enrollment and its points history — a student's history only makes sense tied to a still-existing enrollment. */
    public void deleteEnrollment(String enrollmentId, final IDatabaseOnDelete<Enrollment> listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.delete(StudentDbHelper.TABLE_POINTS_HISTORY,
                    StudentDbHelper.COL_HISTORY_ENROLLMENT_ID + "=?", new String[]{enrollmentId});
            int rows = db.delete(StudentDbHelper.TABLE_ENROLLMENTS,
                    StudentDbHelper.COL_ENROLLMENT_ID + "=?", new String[]{enrollmentId});
            boolean success = rows > 0;
            postResult(() -> listener.onLoadComplete(success, null));
        });
    }

    /**
     * Overwrites an enrollment's raw point total. Callers are expected to also write a
     * {@link PointsHistory} row (see {@link #savePointsHistory}) alongside this — this method by
     * itself doesn't create an audit trail entry, it's the low-level "set the number" step of the
     * delta-then-history pattern used everywhere points are awarded.
     */
    public void updateEnrollmentGrades(String enrollmentId, int newGrades, final IDatabaseOnUpdate<Enrollment> listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(StudentDbHelper.COL_ENROLLMENT_GRADES, newGrades);

            int rows = db.update(StudentDbHelper.TABLE_ENROLLMENTS, values,
                    StudentDbHelper.COL_ENROLLMENT_ID + "=?", new String[]{enrollmentId});
            if (rows <= 0) {
                postResult(() -> listener.onUpdateComplete(false, null));
                return;
            }

            Enrollment updated = queryEnrollmentById(db, enrollmentId);
            postResult(() -> listener.onUpdateComplete(updated != null, updated));
        });
    }

    public void loadEnrollmentById(String id, final IDatabaseOnLoad<Enrollment> listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            Enrollment result = queryEnrollmentById(db, id);
            postResult(() -> listener.onLoadComplete(result != null, result));
        });
    }

    public void loadEnrollmentsForStudent(String studentId, final IDatabaseOnLoad<ArrayList<Enrollment>> listener) {
        executor.execute(() -> {
            ArrayList<Enrollment> list = new ArrayList<>();
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_ENROLLMENTS, null,
                    StudentDbHelper.COL_ENROLLMENT_STUDENT_ID + "=?", new String[]{studentId}, null, null, null)) {
                while (cursor.moveToNext()) list.add(enrollmentFromCursor(cursor));
            }
            postResult(() -> listener.onLoadComplete(true, list));
        });
    }

    /** Finds the (single) enrollment linking this student to this specific class group, if any. */
    public void loadEnrollment(String studentId, String classGroupId, final IDatabaseOnLoad<Enrollment> listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            Enrollment result = null;
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_ENROLLMENTS, null,
                    StudentDbHelper.COL_ENROLLMENT_STUDENT_ID + "=? AND " + StudentDbHelper.COL_ENROLLMENT_CLASS_GROUP_ID + "=?",
                    new String[]{studentId, classGroupId}, null, null, null)) {
                if (cursor.moveToFirst()) result = enrollmentFromCursor(cursor);
            }
            Enrollment finalResult = result;
            postResult(() -> listener.onLoadComplete(finalResult != null, finalResult));
        });
    }

    /** Finds the student's enrollment in any class group belonging to the given discipline. */
    public void loadEnrollmentForStudentInDiscipline(String studentId, String disciplineId, final IDatabaseOnLoad<Enrollment> listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            // Joins through class_groups since enrollments only store a class group id, not a
            // discipline id directly — a discipline can have several class groups, but a student
            // should only ever be enrolled in one of them (hence LIMIT 1).
            String sql = "SELECT e." + StudentDbHelper.COL_ENROLLMENT_ID
                    + ", e." + StudentDbHelper.COL_ENROLLMENT_STUDENT_ID
                    + ", e." + StudentDbHelper.COL_ENROLLMENT_CLASS_GROUP_ID
                    + ", e." + StudentDbHelper.COL_ENROLLMENT_GRADES
                    + " FROM " + StudentDbHelper.TABLE_ENROLLMENTS + " e"
                    + " JOIN " + StudentDbHelper.TABLE_CLASS_GROUPS + " g ON g." + StudentDbHelper.COL_CLASS_GROUP_ID_PK
                    + " = e." + StudentDbHelper.COL_ENROLLMENT_CLASS_GROUP_ID
                    + " WHERE e." + StudentDbHelper.COL_ENROLLMENT_STUDENT_ID + "=? AND g." + StudentDbHelper.COL_CLASS_GROUP_DISCIPLINE_ID + "=?"
                    + " LIMIT 1";

            Enrollment result = null;
            try (Cursor cursor = db.rawQuery(sql, new String[]{studentId, disciplineId})) {
                if (cursor.moveToFirst()) result = enrollmentFromCursor(cursor);
            }
            Enrollment finalResult = result;
            postResult(() -> listener.onLoadComplete(finalResult != null, finalResult));
        });
    }

    /* --------------------------- Points history ----------------------------- */

    public void savePointsHistory(PointsHistory h, final IDatabaseOnSave<PointsHistory> listener) {
        executor.execute(() -> {
            if (h.id == null) h.id = UUID.randomUUID().toString();

            ContentValues values = new ContentValues();
            values.put(StudentDbHelper.COL_HISTORY_ID, h.id);
            values.put(StudentDbHelper.COL_HISTORY_ENROLLMENT_ID, h.enrollmentId);
            values.put(StudentDbHelper.COL_HISTORY_POINTS_DELTA, h.pointsDelta);
            values.put(StudentDbHelper.COL_HISTORY_NOTE, h.note);
            values.put(StudentDbHelper.COL_HISTORY_CREATED_AT, h.createdAt);
            values.put(StudentDbHelper.COL_HISTORY_TAG, h.tag);
            values.put(StudentDbHelper.COL_HISTORY_REVERTS_ID, h.revertsId);

            SQLiteDatabase db = dbHelper.getWritableDatabase();
            long result = db.insertWithOnConflict(StudentDbHelper.TABLE_POINTS_HISTORY, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
            boolean success = result != -1;

            postResult(() -> listener.onSaveComplete(success, h));
        });
    }

    public void loadHistoryForEnrollment(String enrollmentId, final IDatabaseOnLoad<ArrayList<PointsHistory>> listener) {
        executor.execute(() -> {
            ArrayList<PointsHistory> list = new ArrayList<>();
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_POINTS_HISTORY, null,
                    StudentDbHelper.COL_HISTORY_ENROLLMENT_ID + "=?", new String[]{enrollmentId}, null, null,
                    StudentDbHelper.COL_HISTORY_CREATED_AT + " DESC")) {
                while (cursor.moveToNext()) {
                    list.add(pointsHistoryFromCursor(cursor));
                }
            }
            postResult(() -> listener.onLoadComplete(true, list));
        });
    }

    /** Distinct note texts across all history, most-recently-used first, for the note dialog's suggestion list. */
    public void loadRecentNotes(int limit, final IDatabaseOnLoad<ArrayList<String>> listener) {
        executor.execute(() -> {
            ArrayList<String> notes = new ArrayList<>();
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            // GROUP BY the note text (deduplicating repeated notes) while keeping each group's
            // most recent use time, then order by that so recently-reused notes surface first.
            String sql = "SELECT " + StudentDbHelper.COL_HISTORY_NOTE + ", MAX(" + StudentDbHelper.COL_HISTORY_CREATED_AT + ") AS latest "
                    + "FROM " + StudentDbHelper.TABLE_POINTS_HISTORY + " "
                    + "WHERE " + StudentDbHelper.COL_HISTORY_NOTE + " IS NOT NULL AND TRIM(" + StudentDbHelper.COL_HISTORY_NOTE + ") != '' "
                    + "GROUP BY " + StudentDbHelper.COL_HISTORY_NOTE + " "
                    + "ORDER BY latest DESC LIMIT ?";
            try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(limit)})) {
                while (cursor.moveToNext()) {
                    notes.add(cursor.getString(0));
                }
            }
            postResult(() -> listener.onLoadComplete(true, notes));
        });
    }

    /** Distinct category tags across all history, most-recently-used first, for the suggestion lists in the note dialog and the web forms. */
    public void loadRecentTags(int limit, final IDatabaseOnLoad<ArrayList<String>> listener) {
        executor.execute(() -> {
            ArrayList<String> tags = new ArrayList<>();
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            String sql = "SELECT " + StudentDbHelper.COL_HISTORY_TAG + ", MAX(" + StudentDbHelper.COL_HISTORY_CREATED_AT + ") AS latest "
                    + "FROM " + StudentDbHelper.TABLE_POINTS_HISTORY + " "
                    + "WHERE " + StudentDbHelper.COL_HISTORY_TAG + " IS NOT NULL AND TRIM(" + StudentDbHelper.COL_HISTORY_TAG + ") != '' "
                    + "GROUP BY " + StudentDbHelper.COL_HISTORY_TAG + " "
                    + "ORDER BY latest DESC LIMIT ?";
            try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(limit)})) {
                while (cursor.moveToNext()) tags.add(cursor.getString(0));
            }
            postResult(() -> listener.onLoadComplete(true, tags));
        });
    }

    /* ----------------- Atomic points changes (award, batch, revert) ----------------- */

    // Keeps a free-typed category short enough to read in a table cell and a tag list.
    private static final int MAX_TAG_LENGTH = 40;

    /** Stored form of a free-typed category: trimmed, inner whitespace squeezed, capped in length; blank means "no category" (null). */
    public static String cleanTag(String tag) {
        if (tag == null) return null;
        String cleaned = tag.trim().replaceAll("\\s+", " ");
        if (cleaned.length() > MAX_TAG_LENGTH) cleaned = cleaned.substring(0, MAX_TAG_LENGTH).trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static PointsHistory queryHistoryById(SQLiteDatabase db, String id) {
        try (Cursor cursor = db.query(StudentDbHelper.TABLE_POINTS_HISTORY, null,
                StudentDbHelper.COL_HISTORY_ID + "=?", new String[]{id}, null, null, null)) {
            if (cursor.moveToFirst()) return pointsHistoryFromCursor(cursor);
        }
        return null;
    }

    /** True once a compensating entry pointing at {@code historyId} exists — i.e. the entry has already been cancelled. */
    private static boolean isReverted(SQLiteDatabase db, String historyId) {
        return android.database.DatabaseUtils.queryNumEntries(db, StudentDbHelper.TABLE_POINTS_HISTORY,
                StudentDbHelper.COL_HISTORY_REVERTS_ID + "=?", new String[]{historyId}) > 0;
    }

    /**
     * True if a still-standing (non-reopened) period close of this enrollment happened at or after
     * {@code createdAt} — meaning the entry was counted into that period's snapshot. Changing it
     * now would quietly move points between periods without touching the snapshot, so such
     * entries are locked until the period is reopened.
     */
    private static boolean isInClosedPeriod(SQLiteDatabase db, String enrollmentId, long createdAt) {
        String sql = "SELECT COUNT(*) FROM " + StudentDbHelper.TABLE_PERIOD_SNAPSHOTS + " ps"
                + " JOIN " + StudentDbHelper.TABLE_PERIODS + " p ON p." + StudentDbHelper.COL_PERIOD_ID
                + " = ps." + StudentDbHelper.COL_PERIOD_SNAPSHOT_PERIOD_ID
                + " WHERE ps." + StudentDbHelper.COL_PERIOD_SNAPSHOT_ENROLLMENT_ID + " = ?"
                + " AND p." + StudentDbHelper.COL_PERIOD_REOPENED_AT + " IS NULL"
                + " AND p." + StudentDbHelper.COL_PERIOD_CLOSED_AT + " >= ?";
        try (Cursor cursor = db.rawQuery(sql, new String[]{enrollmentId, String.valueOf(createdAt)})) {
            return cursor.moveToFirst() && cursor.getInt(0) > 0;
        }
    }

    /**
     * The one place an enrollment's total ever moves: shifts it by {@code delta} in SQL — a
     * relative update, so two awards arriving from the phone and the web at the same moment can't
     * overwrite each other's result — and appends the matching history row. Must run inside a
     * transaction the caller began; returns null if the enrollment doesn't exist.
     */
    private static PointsAward applyPoints(SQLiteDatabase db, String enrollmentId, int delta,
                                           String note, String tag, String revertsId) {
        if (queryEnrollmentById(db, enrollmentId) == null) return null;

        db.execSQL("UPDATE " + StudentDbHelper.TABLE_ENROLLMENTS
                        + " SET " + StudentDbHelper.COL_ENROLLMENT_GRADES + " = " + StudentDbHelper.COL_ENROLLMENT_GRADES + " + ?"
                        + " WHERE " + StudentDbHelper.COL_ENROLLMENT_ID + " = ?",
                new Object[]{delta, enrollmentId});

        PointsHistory h = new PointsHistory();
        h.id = UUID.randomUUID().toString();
        h.enrollmentId = enrollmentId;
        h.pointsDelta = delta;
        h.note = note == null ? "" : note;
        h.tag = tag;
        h.revertsId = revertsId;
        h.createdAt = System.currentTimeMillis();

        ContentValues values = new ContentValues();
        values.put(StudentDbHelper.COL_HISTORY_ID, h.id);
        values.put(StudentDbHelper.COL_HISTORY_ENROLLMENT_ID, h.enrollmentId);
        values.put(StudentDbHelper.COL_HISTORY_POINTS_DELTA, h.pointsDelta);
        values.put(StudentDbHelper.COL_HISTORY_NOTE, h.note);
        values.put(StudentDbHelper.COL_HISTORY_CREATED_AT, h.createdAt);
        values.put(StudentDbHelper.COL_HISTORY_TAG, h.tag);
        values.put(StudentDbHelper.COL_HISTORY_REVERTS_ID, h.revertsId);
        db.insertOrThrow(StudentDbHelper.TABLE_POINTS_HISTORY, null, values);

        PointsAward award = new PointsAward();
        award.enrollment = queryEnrollmentById(db, enrollmentId);
        award.history = h;
        return award;
    }

    /**
     * Awards (or deducts, for a negative {@code delta}) points to one enrollment and records the
     * history entry, as a single transaction. Unlike calling {@link #updateEnrollmentGrades} and
     * {@link #savePointsHistory} separately, the total and the audit trail can't drift apart, and
     * the new total is computed from what's stored now rather than from a copy the caller read earlier.
     */
    public void awardPoints(String enrollmentId, int delta, String note, String tag, final IDatabaseOnLoad<PointsAward> listener) {
        executor.execute(() -> {
            PointsAward award = null;
            if (delta != 0) {
                SQLiteDatabase db = dbHelper.getWritableDatabase();
                db.beginTransaction();
                try {
                    award = applyPoints(db, enrollmentId, delta, note, cleanTag(tag), null);
                    if (award != null) db.setTransactionSuccessful();
                } catch (RuntimeException e) {
                    award = null;
                } finally {
                    db.endTransaction();
                }
            }
            final PointsAward result = award;
            postResult(() -> listener.onLoadComplete(result != null, result));
        });
    }

    /** Same change for several enrollments at once ("everyone handed in the assignment"), all-or-nothing: if any enrollment is missing, none is touched. Duplicates are applied once. */
    public void awardPointsBatch(List<String> enrollmentIds, int delta, String note, String tag,
                                 final IDatabaseOnLoad<ArrayList<PointsAward>> listener) {
        executor.execute(() -> {
            ArrayList<PointsAward> awards = new ArrayList<>();
            boolean success = false;
            if (delta != 0 && enrollmentIds != null && !enrollmentIds.isEmpty()) {
                SQLiteDatabase db = dbHelper.getWritableDatabase();
                String cleanedTag = cleanTag(tag);
                db.beginTransaction();
                try {
                    success = true;
                    for (String enrollmentId : new LinkedHashSet<>(enrollmentIds)) {
                        PointsAward award = applyPoints(db, enrollmentId, delta, note, cleanedTag, null);
                        if (award == null) {
                            success = false;
                            break;
                        }
                        awards.add(award);
                    }
                    if (success) db.setTransactionSuccessful();
                } catch (RuntimeException e) {
                    success = false;
                } finally {
                    db.endTransaction();
                }
            }
            final boolean finalSuccess = success;
            if (!finalSuccess) awards.clear();
            postResult(() -> listener.onLoadComplete(finalSuccess, awards));
        });
    }

    /** "Estorno: bom trabalho" — what a compensating entry's note says, so it reads sensibly in the history on its own. */
    private String reversalNote(String originalNote) {
        String text = originalNote == null ? "" : originalNote.trim();
        return text.isEmpty() ? appContext.getString(R.string.reversal_note_blank)
                : appContext.getString(R.string.reversal_note, text);
    }

    /**
     * Cancels a history entry by appending an opposite one (history is append-only, so nothing is
     * rewritten or deleted) and, when {@code replacement} is given, records the corrected entry
     * right after it — "edit" is just cancel + re-enter, in one transaction. Refuses with
     * {@link RevertResult#error} codes: NOT_FOUND, IS_REVERSAL (a cancelling entry can't itself be
     * cancelled), ALREADY_REVERTED, or CLOSED_PERIOD (the entry belongs to a closed period; reopen
     * it first).
     */
    public void revertPointsHistory(String historyId, PointsHistory replacement, final IDatabaseOnLoad<RevertResult> listener) {
        executor.execute(() -> {
            RevertResult result = new RevertResult();
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                PointsHistory original = queryHistoryById(db, historyId);
                if (original == null) {
                    result.error = "NOT_FOUND";
                } else if (original.revertsId != null) {
                    result.error = "IS_REVERSAL";
                } else if (isReverted(db, historyId)) {
                    result.error = "ALREADY_REVERTED";
                } else if (isInClosedPeriod(db, original.enrollmentId, original.createdAt)) {
                    result.error = "CLOSED_PERIOD";
                } else {
                    result.reversal = applyPoints(db, original.enrollmentId, -original.pointsDelta,
                            reversalNote(original.note), original.tag, original.id);
                    if (result.reversal == null) {
                        result.error = "NOT_FOUND";
                    } else {
                        if (replacement != null && replacement.pointsDelta != 0) {
                            result.replacement = applyPoints(db, original.enrollmentId, replacement.pointsDelta,
                                    replacement.note, cleanTag(replacement.tag), null);
                        }
                        db.setTransactionSuccessful();
                    }
                }
            } catch (RuntimeException e) {
                result.error = "FAILED";
                result.reversal = null;
                result.replacement = null;
            } finally {
                db.endTransaction();
            }
            postResult(() -> listener.onLoadComplete(result.error == null, result));
        });
    }

    /**
     * Takes back an award that was just made (the "Desfazer" snackbar): removes the history row
     * outright and subtracts its points, as if it never happened. Unlike {@link #revertPointsHistory}
     * this leaves no trace, so it is only meant for the few seconds right after the award; the
     * same locks apply (error codes ALREADY_REVERTED, IS_REVERSAL, CLOSED_PERIOD, NOT_FOUND).
     */
    public void deletePointsAward(String historyId, final IDatabaseOnResult listener) {
        executor.execute(() -> {
            String error = null;
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                PointsHistory entry = queryHistoryById(db, historyId);
                if (entry == null) {
                    error = "NOT_FOUND";
                } else if (entry.revertsId != null) {
                    error = "IS_REVERSAL";
                } else if (isReverted(db, historyId)) {
                    error = "ALREADY_REVERTED";
                } else if (isInClosedPeriod(db, entry.enrollmentId, entry.createdAt)) {
                    error = "CLOSED_PERIOD";
                } else {
                    db.execSQL("UPDATE " + StudentDbHelper.TABLE_ENROLLMENTS
                                    + " SET " + StudentDbHelper.COL_ENROLLMENT_GRADES + " = " + StudentDbHelper.COL_ENROLLMENT_GRADES + " - ?"
                                    + " WHERE " + StudentDbHelper.COL_ENROLLMENT_ID + " = ?",
                            new Object[]{entry.pointsDelta, entry.enrollmentId});
                    db.delete(StudentDbHelper.TABLE_POINTS_HISTORY, StudentDbHelper.COL_HISTORY_ID + "=?", new String[]{historyId});
                    db.setTransactionSuccessful();
                }
            } catch (RuntimeException e) {
                error = "FAILED";
            } finally {
                db.endTransaction();
            }
            final String finalError = error;
            postResult(() -> listener.onResult(finalError == null, finalError));
        });
    }

    /* ------------------------------- Attendance --------------------------------- */

    private static Attendance attendanceFromCursor(Cursor cursor) {
        Attendance a = new Attendance();
        a.id = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_ATTENDANCE_ID));
        a.classGroupId = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_ATTENDANCE_CLASS_GROUP_ID));
        a.studentId = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_ATTENDANCE_STUDENT_ID));
        a.day = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_ATTENDANCE_DAY));
        a.status = cursor.getInt(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_ATTENDANCE_STATUS));
        a.createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_ATTENDANCE_CREATED_AT));
        a.updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_ATTENDANCE_UPDATED_AT));
        return a;
    }

    private static ArrayList<Attendance> queryAttendance(SQLiteDatabase db, String selection, String[] args) {
        ArrayList<Attendance> list = new ArrayList<>();
        try (Cursor cursor = db.query(StudentDbHelper.TABLE_ATTENDANCE, null, selection, args, null, null,
                StudentDbHelper.COL_ATTENDANCE_DAY + " ASC, " + StudentDbHelper.COL_ATTENDANCE_STUDENT_ID + " ASC")) {
            while (cursor.moveToNext()) list.add(attendanceFromCursor(cursor));
        }
        return list;
    }

    private static final String ATTENDANCE_DAY_SELECTION =
            StudentDbHelper.COL_ATTENDANCE_CLASS_GROUP_ID + "=? AND " + StudentDbHelper.COL_ATTENDANCE_DAY + "=?";

    /**
     * Opens (or re-opens) a roll call for a class group on a day: every student currently enrolled
     * who has no record yet for that day starts out absent, and scanning/marking then flips them
     * to present. Safe to call again — records that already exist, such as students already
     * marked present, are left exactly as they are. Returns the day's full list.
     */
    public void startAttendance(String classGroupId, String day, final IDatabaseOnLoad<ArrayList<Attendance>> listener) {
        executor.execute(() -> {
            ArrayList<Attendance> rows = null;
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                long now = System.currentTimeMillis();
                List<String> studentIds = new ArrayList<>();
                try (Cursor cursor = db.query(StudentDbHelper.TABLE_ENROLLMENTS,
                        new String[]{StudentDbHelper.COL_ENROLLMENT_STUDENT_ID},
                        StudentDbHelper.COL_ENROLLMENT_CLASS_GROUP_ID + "=?", new String[]{classGroupId}, null, null, null)) {
                    while (cursor.moveToNext()) studentIds.add(cursor.getString(0));
                }
                for (String studentId : studentIds) {
                    ContentValues values = new ContentValues();
                    values.put(StudentDbHelper.COL_ATTENDANCE_ID, UUID.randomUUID().toString());
                    values.put(StudentDbHelper.COL_ATTENDANCE_CLASS_GROUP_ID, classGroupId);
                    values.put(StudentDbHelper.COL_ATTENDANCE_STUDENT_ID, studentId);
                    values.put(StudentDbHelper.COL_ATTENDANCE_DAY, day);
                    values.put(StudentDbHelper.COL_ATTENDANCE_STATUS, Attendance.STATUS_ABSENT);
                    values.put(StudentDbHelper.COL_ATTENDANCE_CREATED_AT, now);
                    values.put(StudentDbHelper.COL_ATTENDANCE_UPDATED_AT, now);
                    db.insertWithOnConflict(StudentDbHelper.TABLE_ATTENDANCE, null, values, SQLiteDatabase.CONFLICT_IGNORE);
                }
                rows = queryAttendance(db, ATTENDANCE_DAY_SELECTION, new String[]{classGroupId, day});
                db.setTransactionSuccessful();
            } catch (RuntimeException e) {
                rows = null;
            } finally {
                db.endTransaction();
            }
            final ArrayList<Attendance> result = rows;
            postResult(() -> listener.onLoadComplete(result != null, result));
        });
    }

    /**
     * Sets the status of the given students for one class day (only {@code studentId} and
     * {@code status} of each mark are read), creating the record if the student had none. All
     * marks apply or none do. Returns the day's full list afterwards.
     */
    public void setAttendance(String classGroupId, String day, List<Attendance> marks,
                              final IDatabaseOnLoad<ArrayList<Attendance>> listener) {
        executor.execute(() -> {
            ArrayList<Attendance> rows = null;
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                long now = System.currentTimeMillis();
                for (Attendance mark : marks) {
                    if (mark.studentId == null || !Attendance.isValidStatus(mark.status)) {
                        throw new IllegalArgumentException("invalid attendance mark");
                    }
                    ContentValues update = new ContentValues();
                    update.put(StudentDbHelper.COL_ATTENDANCE_STATUS, mark.status);
                    update.put(StudentDbHelper.COL_ATTENDANCE_UPDATED_AT, now);
                    int changed = db.update(StudentDbHelper.TABLE_ATTENDANCE, update,
                            ATTENDANCE_DAY_SELECTION + " AND " + StudentDbHelper.COL_ATTENDANCE_STUDENT_ID + "=?",
                            new String[]{classGroupId, day, mark.studentId});
                    if (changed == 0) {
                        ContentValues insert = new ContentValues();
                        insert.put(StudentDbHelper.COL_ATTENDANCE_ID, UUID.randomUUID().toString());
                        insert.put(StudentDbHelper.COL_ATTENDANCE_CLASS_GROUP_ID, classGroupId);
                        insert.put(StudentDbHelper.COL_ATTENDANCE_STUDENT_ID, mark.studentId);
                        insert.put(StudentDbHelper.COL_ATTENDANCE_DAY, day);
                        insert.put(StudentDbHelper.COL_ATTENDANCE_STATUS, mark.status);
                        insert.put(StudentDbHelper.COL_ATTENDANCE_CREATED_AT, now);
                        insert.put(StudentDbHelper.COL_ATTENDANCE_UPDATED_AT, now);
                        db.insertOrThrow(StudentDbHelper.TABLE_ATTENDANCE, null, insert);
                    }
                }
                rows = queryAttendance(db, ATTENDANCE_DAY_SELECTION, new String[]{classGroupId, day});
                db.setTransactionSuccessful();
            } catch (RuntimeException e) {
                rows = null;
            } finally {
                db.endTransaction();
            }
            final ArrayList<Attendance> result = rows;
            postResult(() -> listener.onLoadComplete(result != null, result));
        });
    }

    public void loadAttendanceForDay(String classGroupId, String day, final IDatabaseOnLoad<ArrayList<Attendance>> listener) {
        executor.execute(() -> {
            ArrayList<Attendance> rows = queryAttendance(dbHelper.getReadableDatabase(),
                    ATTENDANCE_DAY_SELECTION, new String[]{classGroupId, day});
            postResult(() -> listener.onLoadComplete(true, rows));
        });
    }

    /** Every recorded day of a class group, oldest first. */
    public void loadAttendanceForClassGroup(String classGroupId, final IDatabaseOnLoad<ArrayList<Attendance>> listener) {
        executor.execute(() -> {
            ArrayList<Attendance> rows = queryAttendance(dbHelper.getReadableDatabase(),
                    StudentDbHelper.COL_ATTENDANCE_CLASS_GROUP_ID + "=?", new String[]{classGroupId});
            postResult(() -> listener.onLoadComplete(true, rows));
        });
    }

    /** Every recorded day of one student, across all their class groups, oldest first. */
    public void loadAttendanceForStudent(String studentId, final IDatabaseOnLoad<ArrayList<Attendance>> listener) {
        executor.execute(() -> {
            ArrayList<Attendance> rows = queryAttendance(dbHelper.getReadableDatabase(),
                    StudentDbHelper.COL_ATTENDANCE_STUDENT_ID + "=?", new String[]{studentId});
            postResult(() -> listener.onLoadComplete(true, rows));
        });
    }

    /** Deletes a class day's whole roll call (e.g. one opened by mistake); reports how many records were removed. */
    public void deleteAttendanceDay(String classGroupId, String day, final IDatabaseOnLoad<Integer> listener) {
        executor.execute(() -> {
            int removed = dbHelper.getWritableDatabase().delete(StudentDbHelper.TABLE_ATTENDANCE,
                    ATTENDANCE_DAY_SELECTION, new String[]{classGroupId, day});
            postResult(() -> listener.onLoadComplete(true, removed));
        });
    }

    /* ------------------------------ QR scan log ------------------------------- */

    /**
     * Records that this student's QR code was just identified (scanned, or picked manually —
     * either way the teacher is looking at them right now), then reports back how many times
     * that's happened today, this scan included. A teacher juggling a full class can easily
     * forget having already awarded points to a student earlier the same day; surfacing the
     * count lets the app flag a repeat before the student benefits from that lapse.
     */
    public void logScanAndCountToday(String studentId, final IDatabaseOnLoad<Integer> listener) {
        scansToday(studentId, true, listener);
    }

    /** How many scans of this student were logged today, without logging a new one. */
    public void countScansToday(String studentId, final IDatabaseOnLoad<Integer> listener) {
        scansToday(studentId, false, listener);
    }

    private void scansToday(String studentId, boolean record, final IDatabaseOnLoad<Integer> listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            long now = System.currentTimeMillis();

            if (record) {
                ContentValues values = new ContentValues();
                values.put(StudentDbHelper.COL_QR_SCAN_ID, UUID.randomUUID().toString());
                values.put(StudentDbHelper.COL_QR_SCAN_STUDENT_ID, studentId);
                values.put(StudentDbHelper.COL_QR_SCAN_AT, now);
                db.insert(StudentDbHelper.TABLE_QR_SCANS, null, values);
            }

            Calendar startOfDay = Calendar.getInstance();
            startOfDay.setTimeInMillis(now);
            startOfDay.set(Calendar.HOUR_OF_DAY, 0);
            startOfDay.set(Calendar.MINUTE, 0);
            startOfDay.set(Calendar.SECOND, 0);
            startOfDay.set(Calendar.MILLISECOND, 0);
            long dayStart = startOfDay.getTimeInMillis();
            long dayEnd = dayStart + 24L * 60 * 60 * 1000;

            int count = 0;
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_QR_SCANS, new String[]{"COUNT(*)"},
                    StudentDbHelper.COL_QR_SCAN_STUDENT_ID + "=? AND " + StudentDbHelper.COL_QR_SCAN_AT + ">=? AND " + StudentDbHelper.COL_QR_SCAN_AT + "<?",
                    new String[]{studentId, String.valueOf(dayStart), String.valueOf(dayEnd)}, null, null, null)) {
                if (cursor.moveToFirst()) count = cursor.getInt(0);
            }

            final int finalCount = count;
            postResult(() -> listener.onLoadComplete(true, finalCount));
        });
    }

    /* ---------------------------- Disciplines ------------------------------ */

    public void saveDiscipline(Discipline d, final IDatabaseOnSave<Discipline> listener) {
        executor.execute(() -> {
            if (d.id == null) d.id = UUID.randomUUID().toString();

            ContentValues values = new ContentValues();
            values.put(StudentDbHelper.COL_DISCIPLINE_ID, d.id);
            values.put(StudentDbHelper.COL_DISCIPLINE_NAME, d.name);

            SQLiteDatabase db = dbHelper.getWritableDatabase();
            long result = db.insertWithOnConflict(StudentDbHelper.TABLE_DISCIPLINES, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
            boolean success = result != -1;

            postResult(() -> listener.onSaveComplete(success, d));
        });
    }

    public void loadAllDisciplines(final IDatabaseOnLoad<ArrayList<Discipline>> listener) {
        executor.execute(() -> {
            ArrayList<Discipline> list = new ArrayList<>();
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_DISCIPLINES, null, null, null, null, null,
                    StudentDbHelper.COL_DISCIPLINE_NAME + " ASC")) {
                while (cursor.moveToNext()) {
                    list.add(disciplineFromCursor(cursor));
                }
            }
            postResult(() -> listener.onLoadComplete(true, list));
        });
    }

    /** Refuses to delete a discipline that still has class groups (reported as "HAS_GROUPS") — the teacher must remove those first, since cascading would silently destroy student enrollments. */
    public void deleteDiscipline(String id, final IDatabaseOnResult listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            long groupCount = android.database.DatabaseUtils.queryNumEntries(db, StudentDbHelper.TABLE_CLASS_GROUPS,
                    StudentDbHelper.COL_CLASS_GROUP_DISCIPLINE_ID + "=?", new String[]{id});

            if (groupCount > 0) {
                postResult(() -> listener.onResult(false, "HAS_GROUPS"));
                return;
            }

            db.delete(StudentDbHelper.TABLE_GOALS, StudentDbHelper.COL_GOAL_DISCIPLINE_ID + "=?", new String[]{id});
            int rows = db.delete(StudentDbHelper.TABLE_DISCIPLINES, StudentDbHelper.COL_DISCIPLINE_ID + "=?", new String[]{id});

            postResult(() -> listener.onResult(rows > 0, null));
        });
    }

    /* --------------------------- Class groups ------------------------------ */

    public void saveClassGroup(ClassGroup g, final IDatabaseOnSave<ClassGroup> listener) {
        executor.execute(() -> {
            if (g.id == null) g.id = UUID.randomUUID().toString();

            ContentValues values = new ContentValues();
            values.put(StudentDbHelper.COL_CLASS_GROUP_ID_PK, g.id);
            values.put(StudentDbHelper.COL_CLASS_GROUP_DISCIPLINE_ID, g.disciplineId);
            values.put(StudentDbHelper.COL_CLASS_GROUP_NAME, g.name);
            // CONFLICT_REPLACE re-inserts the whole row on an existing id (e.g. a rename), so
            // closedAt must be carried through explicitly or it would silently reset to active.
            values.put(StudentDbHelper.COL_CLASS_GROUP_CLOSED_AT, g.closedAt);

            SQLiteDatabase db = dbHelper.getWritableDatabase();
            long result = db.insertWithOnConflict(StudentDbHelper.TABLE_CLASS_GROUPS, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
            boolean success = result != -1;

            postResult(() -> listener.onSaveComplete(success, g));
        });
    }

    public void loadAllClassGroups(final IDatabaseOnLoad<ArrayList<ClassGroup>> listener) {
        executor.execute(() -> {
            ArrayList<ClassGroup> list = new ArrayList<>();
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_CLASS_GROUPS, null, null, null, null, null,
                    StudentDbHelper.COL_CLASS_GROUP_NAME + " ASC")) {
                while (cursor.moveToNext()) {
                    list.add(classGroupFromCursor(cursor));
                }
            }
            postResult(() -> listener.onLoadComplete(true, list));
        });
    }

    public void loadClassGroupsForDiscipline(String disciplineId, final IDatabaseOnLoad<ArrayList<ClassGroup>> listener) {
        executor.execute(() -> {
            ArrayList<ClassGroup> list = new ArrayList<>();
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_CLASS_GROUPS, null,
                    StudentDbHelper.COL_CLASS_GROUP_DISCIPLINE_ID + "=?", new String[]{disciplineId}, null, null,
                    StudentDbHelper.COL_CLASS_GROUP_NAME + " ASC")) {
                while (cursor.moveToNext()) {
                    list.add(classGroupFromCursor(cursor));
                }
            }
            postResult(() -> listener.onLoadComplete(true, list));
        });
    }

    /** Loads a single class group by id, e.g. to check {@link ClassGroup#isClosed()} before letting a scan award points into it. */
    public void loadClassGroup(String id, final IDatabaseOnLoad<ClassGroup> listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            ClassGroup group = null;
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_CLASS_GROUPS, null,
                    StudentDbHelper.COL_CLASS_GROUP_ID_PK + "=?", new String[]{id}, null, null, null)) {
                if (cursor.moveToFirst()) group = classGroupFromCursor(cursor);
            }
            final ClassGroup finalGroup = group;
            postResult(() -> listener.onLoadComplete(finalGroup != null, finalGroup));
        });
    }

    /** Refuses to delete a class group that still has enrolled students (reported as "HAS_STUDENTS") — same reasoning as {@link #deleteDiscipline}. */
    public void deleteClassGroup(String id, final IDatabaseOnResult listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            long enrollmentCount = android.database.DatabaseUtils.queryNumEntries(db, StudentDbHelper.TABLE_ENROLLMENTS,
                    StudentDbHelper.COL_ENROLLMENT_CLASS_GROUP_ID + "=?", new String[]{id});

            if (enrollmentCount > 0) {
                postResult(() -> listener.onResult(false, "HAS_STUDENTS"));
                return;
            }

            int rows = db.delete(StudentDbHelper.TABLE_CLASS_GROUPS, StudentDbHelper.COL_CLASS_GROUP_ID_PK + "=?", new String[]{id});
            // Nobody is enrolled any more, so whatever roll calls were recorded for the group have nothing left to hang on to.
            db.delete(StudentDbHelper.TABLE_ATTENDANCE, StudentDbHelper.COL_ATTENDANCE_CLASS_GROUP_ID + "=?", new String[]{id});
            postResult(() -> listener.onResult(rows > 0, null));
        });
    }

    /* ------------------------------- Goals ---------------------------------- */

    /**
     * Saves a goal. Every goal needs a start and an end date (see {@link Goal#hasValidDates}); one
     * without them — or ending before it starts — is refused with {@code success == false} and
     * nothing is written.
     */
    public void saveGoal(Goal g, final IDatabaseOnSave<Goal> listener) {
        executor.execute(() -> {
            if (!g.hasValidDates()) {
                postResult(() -> listener.onSaveComplete(false, g));
                return;
            }
            if (g.id == null) g.id = UUID.randomUUID().toString();

            ContentValues values = new ContentValues();
            values.put(StudentDbHelper.COL_GOAL_ID, g.id);
            values.put(StudentDbHelper.COL_GOAL_DISCIPLINE_ID, g.disciplineId);
            values.put(StudentDbHelper.COL_GOAL_NAME, g.name);
            values.put(StudentDbHelper.COL_GOAL_TARGET_POINTS, g.targetPoints);
            values.put(StudentDbHelper.COL_GOAL_START_DATE, g.startDate);
            values.put(StudentDbHelper.COL_GOAL_END_DATE, g.endDate);

            SQLiteDatabase db = dbHelper.getWritableDatabase();
            long result = db.insertWithOnConflict(StudentDbHelper.TABLE_GOALS, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
            boolean success = result != -1;

            postResult(() -> listener.onSaveComplete(success, g));
        });
    }

    /** Soonest-ending window first (goals without dates come before any), then easiest target first within a window. */
    private static final String GOAL_ORDER = StudentDbHelper.COL_GOAL_END_DATE + " ASC, "
            + StudentDbHelper.COL_GOAL_START_DATE + " ASC, " + StudentDbHelper.COL_GOAL_TARGET_POINTS + " ASC";

    public void loadGoalsForDiscipline(String disciplineId, final IDatabaseOnLoad<ArrayList<Goal>> listener) {
        executor.execute(() -> {
            ArrayList<Goal> list = new ArrayList<>();
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_GOALS, null,
                    StudentDbHelper.COL_GOAL_DISCIPLINE_ID + "=?", new String[]{disciplineId}, null, null,
                    GOAL_ORDER)) {
                while (cursor.moveToNext()) {
                    list.add(goalFromCursor(cursor));
                }
            }
            postResult(() -> listener.onLoadComplete(true, list));
        });
    }

    public void deleteGoal(String id, final IDatabaseOnDelete<Goal> listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            int rows = db.delete(StudentDbHelper.TABLE_GOALS, StudentDbHelper.COL_GOAL_ID + "=?", new String[]{id});
            boolean success = rows > 0;
            postResult(() -> listener.onLoadComplete(success, null));
        });
    }

    /* ------------------------------- Periods ---------------------------------- */

    private static Period periodFromCursor(Cursor cursor) {
        Period p = new Period();
        p.id = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_PERIOD_ID));
        p.disciplineId = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_PERIOD_DISCIPLINE_ID));
        p.label = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_PERIOD_LABEL));
        p.closedAt = cursor.getLong(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_PERIOD_CLOSED_AT));
        int closedGroupIdx = cursor.getColumnIndexOrThrow(StudentDbHelper.COL_PERIOD_CLOSED_CLASS_GROUP_ID);
        p.closedClassGroupId = cursor.isNull(closedGroupIdx) ? null : cursor.getString(closedGroupIdx);
        int reassignedIdx = cursor.getColumnIndexOrThrow(StudentDbHelper.COL_PERIOD_REASSIGNED_TO_CLASS_GROUP_ID);
        p.reassignedToClassGroupId = cursor.isNull(reassignedIdx) ? null : cursor.getString(reassignedIdx);
        int reopenedIdx = cursor.getColumnIndexOrThrow(StudentDbHelper.COL_PERIOD_REOPENED_AT);
        p.reopenedAt = cursor.isNull(reopenedIdx) ? null : cursor.getLong(reopenedIdx);
        return p;
    }

    private static PeriodSnapshot periodSnapshotFromCursor(Cursor cursor) {
        PeriodSnapshot s = new PeriodSnapshot();
        s.id = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_PERIOD_SNAPSHOT_ID));
        s.periodId = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_PERIOD_SNAPSHOT_PERIOD_ID));
        s.enrollmentId = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_PERIOD_SNAPSHOT_ENROLLMENT_ID));
        s.studentId = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_PERIOD_SNAPSHOT_STUDENT_ID));
        s.classGroupId = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_PERIOD_SNAPSHOT_CLASS_GROUP_ID));
        s.points = cursor.getInt(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_PERIOD_SNAPSHOT_POINTS));
        return s;
    }

    /** Every enrollment whose class_group_id is one of {@code classGroupIds} — the common "which rows does a period close affect" lookup shared by both close actions below. */
    private List<Enrollment> loadEnrollmentsForClassGroups(SQLiteDatabase db, List<String> classGroupIds) {
        List<Enrollment> enrollments = new ArrayList<>();
        if (classGroupIds.isEmpty()) return enrollments;

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < classGroupIds.size(); i++) {
            if (i > 0) placeholders.append(",");
            placeholders.append("?");
        }
        try (Cursor cursor = db.query(StudentDbHelper.TABLE_ENROLLMENTS, null,
                StudentDbHelper.COL_ENROLLMENT_CLASS_GROUP_ID + " IN (" + placeholders + ")",
                classGroupIds.toArray(new String[0]), null, null, null)) {
            while (cursor.moveToNext()) enrollments.add(enrollmentFromCursor(cursor));
        }
        return enrollments;
    }

    /**
     * Core of both "Fechar período" and "Fechar período e turma": snapshots every given
     * enrollment's current total into {@code periodId} (so it stays reportable later), then
     * resets each one back to 0 to start that period's fresh count. Must run inside a
     * transaction the caller already began.
     */
    private void consolidateEnrollments(SQLiteDatabase db, String periodId, List<Enrollment> enrollments) {
        for (Enrollment e : enrollments) {
            ContentValues snapshotValues = new ContentValues();
            snapshotValues.put(StudentDbHelper.COL_PERIOD_SNAPSHOT_ID, UUID.randomUUID().toString());
            snapshotValues.put(StudentDbHelper.COL_PERIOD_SNAPSHOT_PERIOD_ID, periodId);
            snapshotValues.put(StudentDbHelper.COL_PERIOD_SNAPSHOT_ENROLLMENT_ID, e.id);
            snapshotValues.put(StudentDbHelper.COL_PERIOD_SNAPSHOT_STUDENT_ID, e.studentId);
            snapshotValues.put(StudentDbHelper.COL_PERIOD_SNAPSHOT_CLASS_GROUP_ID, e.classGroupId);
            snapshotValues.put(StudentDbHelper.COL_PERIOD_SNAPSHOT_POINTS, e.grades);
            db.insert(StudentDbHelper.TABLE_PERIOD_SNAPSHOTS, null, snapshotValues);

            ContentValues resetValues = new ContentValues();
            resetValues.put(StudentDbHelper.COL_ENROLLMENT_GRADES, 0);
            db.update(StudentDbHelper.TABLE_ENROLLMENTS, resetValues,
                    StudentDbHelper.COL_ENROLLMENT_ID + "=?", new String[]{e.id});
        }
    }

    /**
     * "Fechar período": snapshots and resets every enrollment across every class group under
     * this discipline, starting a fresh point count discipline-wide. Doesn't touch any class
     * group's active/closed state — see {@link #closeClassGroupPeriod} for closing one specific
     * class too.
     */
    public void closeDisciplinePeriod(String disciplineId, String label, final IDatabaseOnSave<Period> listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            Period period = new Period();
            db.beginTransaction();
            try {
                List<String> classGroupIds = new ArrayList<>();
                try (Cursor cursor = db.query(StudentDbHelper.TABLE_CLASS_GROUPS,
                        new String[]{StudentDbHelper.COL_CLASS_GROUP_ID_PK},
                        StudentDbHelper.COL_CLASS_GROUP_DISCIPLINE_ID + "=?", new String[]{disciplineId}, null, null, null)) {
                    while (cursor.moveToNext()) classGroupIds.add(cursor.getString(0));
                }
                List<Enrollment> enrollments = loadEnrollmentsForClassGroups(db, classGroupIds);

                period.id = UUID.randomUUID().toString();
                period.disciplineId = disciplineId;
                period.label = label;
                period.closedAt = System.currentTimeMillis();

                ContentValues periodValues = new ContentValues();
                periodValues.put(StudentDbHelper.COL_PERIOD_ID, period.id);
                periodValues.put(StudentDbHelper.COL_PERIOD_DISCIPLINE_ID, period.disciplineId);
                periodValues.put(StudentDbHelper.COL_PERIOD_LABEL, period.label);
                periodValues.put(StudentDbHelper.COL_PERIOD_CLOSED_AT, period.closedAt);
                db.insert(StudentDbHelper.TABLE_PERIODS, null, periodValues);

                consolidateEnrollments(db, period.id, enrollments);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            postResult(() -> listener.onSaveComplete(true, period));
        });
    }

    /**
     * "Fechar período e turma": snapshots and resets every enrollment in this one class group,
     * then marks it closed. If {@code newClassGroupName} is non-blank, a brand-new class group
     * is created under the same discipline and every affected enrollment is moved into it (so
     * those students keep participating, just starting the new period in a fresh class); if
     * blank/null, the class group is simply archived with its (now zeroed) enrollments left in
     * place — e.g. end of year, no continuation.
     */
    public void closeClassGroupPeriod(String classGroupId, String label, String newClassGroupName, final IDatabaseOnSave<Period> listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            Period period = new Period();
            db.beginTransaction();
            try {
                ClassGroup closedGroup;
                try (Cursor cursor = db.query(StudentDbHelper.TABLE_CLASS_GROUPS, null,
                        StudentDbHelper.COL_CLASS_GROUP_ID_PK + "=?", new String[]{classGroupId}, null, null, null)) {
                    cursor.moveToFirst();
                    closedGroup = classGroupFromCursor(cursor);
                }
                List<Enrollment> enrollments = loadEnrollmentsForClassGroups(db, Collections.singletonList(classGroupId));

                period.id = UUID.randomUUID().toString();
                period.disciplineId = closedGroup.disciplineId;
                period.label = label;
                period.closedAt = System.currentTimeMillis();
                period.closedClassGroupId = classGroupId;

                String newClassGroupId = null;
                if (newClassGroupName != null && !newClassGroupName.trim().isEmpty()) {
                    newClassGroupId = UUID.randomUUID().toString();
                    ContentValues newGroupValues = new ContentValues();
                    newGroupValues.put(StudentDbHelper.COL_CLASS_GROUP_ID_PK, newClassGroupId);
                    newGroupValues.put(StudentDbHelper.COL_CLASS_GROUP_DISCIPLINE_ID, closedGroup.disciplineId);
                    newGroupValues.put(StudentDbHelper.COL_CLASS_GROUP_NAME, newClassGroupName.trim());
                    db.insert(StudentDbHelper.TABLE_CLASS_GROUPS, null, newGroupValues);
                    period.reassignedToClassGroupId = newClassGroupId;
                }

                ContentValues periodValues = new ContentValues();
                periodValues.put(StudentDbHelper.COL_PERIOD_ID, period.id);
                periodValues.put(StudentDbHelper.COL_PERIOD_DISCIPLINE_ID, period.disciplineId);
                periodValues.put(StudentDbHelper.COL_PERIOD_LABEL, period.label);
                periodValues.put(StudentDbHelper.COL_PERIOD_CLOSED_AT, period.closedAt);
                periodValues.put(StudentDbHelper.COL_PERIOD_CLOSED_CLASS_GROUP_ID, period.closedClassGroupId);
                periodValues.put(StudentDbHelper.COL_PERIOD_REASSIGNED_TO_CLASS_GROUP_ID, period.reassignedToClassGroupId);
                db.insert(StudentDbHelper.TABLE_PERIODS, null, periodValues);

                // Snapshot/reset while the enrollments still point at the closed group, so the
                // snapshot records where they actually were at close time — then, only once that's
                // done, move them to the new group if there is one.
                consolidateEnrollments(db, period.id, enrollments);

                if (newClassGroupId != null) {
                    ContentValues moveValues = new ContentValues();
                    moveValues.put(StudentDbHelper.COL_ENROLLMENT_CLASS_GROUP_ID, newClassGroupId);
                    db.update(StudentDbHelper.TABLE_ENROLLMENTS, moveValues,
                            StudentDbHelper.COL_ENROLLMENT_CLASS_GROUP_ID + "=?", new String[]{classGroupId});
                }

                ContentValues closeValues = new ContentValues();
                closeValues.put(StudentDbHelper.COL_CLASS_GROUP_CLOSED_AT, period.closedAt);
                db.update(StudentDbHelper.TABLE_CLASS_GROUPS, closeValues,
                        StudentDbHelper.COL_CLASS_GROUP_ID_PK + "=?", new String[]{classGroupId});

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            postResult(() -> listener.onSaveComplete(true, period));
        });
    }

    /**
     * Lets a "Fechar período e turma" close that reassigned students to a new class group be
     * corrected afterward: moves every enrollment this period actually reassigned into a fresh
     * {@code newClassGroupName} class group instead, deletes the old destination if that leaves
     * it empty, and updates the period record to point at the new one. Does not touch point
     * totals or undo the original close — only where the students ended up.
     */
    public void changePeriodReassignmentTarget(String periodId, String newClassGroupName, final IDatabaseOnResult listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();

            Period period = loadPeriodById(db, periodId);
            if (period == null) {
                postResult(() -> listener.onResult(false, "NOT_FOUND"));
                return;
            }
            if (!period.isReassignment()) {
                postResult(() -> listener.onResult(false, "NOT_A_REASSIGNMENT"));
                return;
            }
            if (period.isReopened()) {
                postResult(() -> listener.onResult(false, "ALREADY_REOPENED"));
                return;
            }

            db.beginTransaction();
            try {
                String oldTargetId = period.reassignedToClassGroupId;
                String newTargetId = UUID.randomUUID().toString();

                ContentValues newGroupValues = new ContentValues();
                newGroupValues.put(StudentDbHelper.COL_CLASS_GROUP_ID_PK, newTargetId);
                newGroupValues.put(StudentDbHelper.COL_CLASS_GROUP_DISCIPLINE_ID, period.disciplineId);
                newGroupValues.put(StudentDbHelper.COL_CLASS_GROUP_NAME, newClassGroupName.trim());
                db.insert(StudentDbHelper.TABLE_CLASS_GROUPS, null, newGroupValues);

                // Only the enrollments THIS period moved there get re-pointed — anyone else who
                // was separately, directly enrolled into the old target since the close is left
                // alone, since this period never touched them.
                for (PeriodSnapshot snapshot : loadPeriodSnapshotRows(db, periodId)) {
                    ContentValues moveValues = new ContentValues();
                    moveValues.put(StudentDbHelper.COL_ENROLLMENT_CLASS_GROUP_ID, newTargetId);
                    db.update(StudentDbHelper.TABLE_ENROLLMENTS, moveValues,
                            StudentDbHelper.COL_ENROLLMENT_ID + "=? AND " + StudentDbHelper.COL_ENROLLMENT_CLASS_GROUP_ID + "=?",
                            new String[]{snapshot.enrollmentId, oldTargetId});
                }

                deleteClassGroupIfEmpty(db, oldTargetId);

                ContentValues periodValues = new ContentValues();
                periodValues.put(StudentDbHelper.COL_PERIOD_REASSIGNED_TO_CLASS_GROUP_ID, newTargetId);
                db.update(StudentDbHelper.TABLE_PERIODS, periodValues,
                        StudentDbHelper.COL_PERIOD_ID + "=?", new String[]{periodId});

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            postResult(() -> listener.onResult(true, null));
        });
    }

    /**
     * Undoes a period close: restores every snapshotted enrollment's point total and, for a
     * class-level close, un-archives the class group — deleting the brand-new class group it
     * was reassigned to if undoing this leaves it empty, or just clearing the closed mark if it
     * was only archived. Can only be called once per period; the caller is expected to warn the
     * teacher first if any affected enrollment already has new, non-zero points from the period
     * that's about to be discarded, since those would be overwritten by the restored snapshot.
     */
    public void reopenPeriod(String periodId, final IDatabaseOnResult listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();

            Period period = loadPeriodById(db, periodId);
            if (period == null) {
                postResult(() -> listener.onResult(false, "NOT_FOUND"));
                return;
            }
            if (period.isReopened()) {
                postResult(() -> listener.onResult(false, "ALREADY_REOPENED"));
                return;
            }

            db.beginTransaction();
            try {
                for (PeriodSnapshot snapshot : loadPeriodSnapshotRows(db, periodId)) {
                    ContentValues restoreValues = new ContentValues();
                    restoreValues.put(StudentDbHelper.COL_ENROLLMENT_GRADES, snapshot.points);
                    db.update(StudentDbHelper.TABLE_ENROLLMENTS, restoreValues,
                            StudentDbHelper.COL_ENROLLMENT_ID + "=?", new String[]{snapshot.enrollmentId});

                    if (period.isReassignment()) {
                        ContentValues moveBackValues = new ContentValues();
                        moveBackValues.put(StudentDbHelper.COL_ENROLLMENT_CLASS_GROUP_ID, period.closedClassGroupId);
                        db.update(StudentDbHelper.TABLE_ENROLLMENTS, moveBackValues,
                                StudentDbHelper.COL_ENROLLMENT_ID + "=? AND " + StudentDbHelper.COL_ENROLLMENT_CLASS_GROUP_ID + "=?",
                                new String[]{snapshot.enrollmentId, period.reassignedToClassGroupId});
                    }
                }

                if (period.isClassClose()) {
                    ContentValues reopenGroupValues = new ContentValues();
                    reopenGroupValues.putNull(StudentDbHelper.COL_CLASS_GROUP_CLOSED_AT);
                    db.update(StudentDbHelper.TABLE_CLASS_GROUPS, reopenGroupValues,
                            StudentDbHelper.COL_CLASS_GROUP_ID_PK + "=?", new String[]{period.closedClassGroupId});

                    if (period.isReassignment()) {
                        deleteClassGroupIfEmpty(db, period.reassignedToClassGroupId);
                    }
                }

                ContentValues periodValues = new ContentValues();
                periodValues.put(StudentDbHelper.COL_PERIOD_REOPENED_AT, System.currentTimeMillis());
                db.update(StudentDbHelper.TABLE_PERIODS, periodValues,
                        StudentDbHelper.COL_PERIOD_ID + "=?", new String[]{periodId});

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            postResult(() -> listener.onResult(true, null));
        });
    }

    /** Deletes the class group if it now has zero enrollments — used after undoing/correcting a reassignment to clean up a destination group that was created solely for it. */
    private void deleteClassGroupIfEmpty(SQLiteDatabase db, String classGroupId) {
        long remaining = android.database.DatabaseUtils.queryNumEntries(db, StudentDbHelper.TABLE_ENROLLMENTS,
                StudentDbHelper.COL_ENROLLMENT_CLASS_GROUP_ID + "=?", new String[]{classGroupId});
        if (remaining == 0) {
            db.delete(StudentDbHelper.TABLE_CLASS_GROUPS, StudentDbHelper.COL_CLASS_GROUP_ID_PK + "=?", new String[]{classGroupId});
        }
    }

    private Period loadPeriodById(SQLiteDatabase db, String periodId) {
        try (Cursor cursor = db.query(StudentDbHelper.TABLE_PERIODS, null,
                StudentDbHelper.COL_PERIOD_ID + "=?", new String[]{periodId}, null, null, null)) {
            if (cursor.moveToFirst()) return periodFromCursor(cursor);
        }
        return null;
    }

    private List<PeriodSnapshot> loadPeriodSnapshotRows(SQLiteDatabase db, String periodId) {
        List<PeriodSnapshot> snapshots = new ArrayList<>();
        try (Cursor cursor = db.query(StudentDbHelper.TABLE_PERIOD_SNAPSHOTS, null,
                StudentDbHelper.COL_PERIOD_SNAPSHOT_PERIOD_ID + "=?", new String[]{periodId}, null, null, null)) {
            while (cursor.moveToNext()) snapshots.add(periodSnapshotFromCursor(cursor));
        }
        return snapshots;
    }

    // Shared by loadPeriodsForDiscipline/loadPeriod: joins in discipline/class-group names so
    // callers never need a second round-trip just to render a period's scope.
    private static final String PERIODS_WITH_NAMES_SQL =
            "SELECT p.*, d." + StudentDbHelper.COL_DISCIPLINE_NAME + " AS discipline_name"
                    + ", cg." + StudentDbHelper.COL_CLASS_GROUP_NAME + " AS closed_class_group_name"
                    + ", ng." + StudentDbHelper.COL_CLASS_GROUP_NAME + " AS reassigned_class_group_name"
                    + " FROM " + StudentDbHelper.TABLE_PERIODS + " p"
                    + " LEFT JOIN " + StudentDbHelper.TABLE_DISCIPLINES + " d ON d." + StudentDbHelper.COL_DISCIPLINE_ID
                    + " = p." + StudentDbHelper.COL_PERIOD_DISCIPLINE_ID
                    + " LEFT JOIN " + StudentDbHelper.TABLE_CLASS_GROUPS + " cg ON cg." + StudentDbHelper.COL_CLASS_GROUP_ID_PK
                    + " = p." + StudentDbHelper.COL_PERIOD_CLOSED_CLASS_GROUP_ID
                    + " LEFT JOIN " + StudentDbHelper.TABLE_CLASS_GROUPS + " ng ON ng." + StudentDbHelper.COL_CLASS_GROUP_ID_PK
                    + " = p." + StudentDbHelper.COL_PERIOD_REASSIGNED_TO_CLASS_GROUP_ID;

    private static Period periodWithNamesFromCursor(Cursor cursor) {
        Period p = periodFromCursor(cursor);
        p.disciplineName = cursor.getString(cursor.getColumnIndexOrThrow("discipline_name"));
        p.closedClassGroupName = cursor.getString(cursor.getColumnIndexOrThrow("closed_class_group_name"));
        p.reassignedToClassGroupName = cursor.getString(cursor.getColumnIndexOrThrow("reassigned_class_group_name"));
        return p;
    }

    /** Every period closed for this discipline, newest first — joined with discipline/class-group names so a list screen can render without a second round-trip. */
    public void loadPeriodsForDiscipline(String disciplineId, final IDatabaseOnLoad<ArrayList<Period>> listener) {
        executor.execute(() -> {
            ArrayList<Period> list = new ArrayList<>();
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            String sql = PERIODS_WITH_NAMES_SQL
                    + " WHERE p." + StudentDbHelper.COL_PERIOD_DISCIPLINE_ID + " = ?"
                    + " ORDER BY p." + StudentDbHelper.COL_PERIOD_CLOSED_AT + " DESC";
            try (Cursor cursor = db.rawQuery(sql, new String[]{disciplineId})) {
                while (cursor.moveToNext()) list.add(periodWithNamesFromCursor(cursor));
            }
            postResult(() -> listener.onLoadComplete(true, list));
        });
    }

    /** Loads one period by id, with discipline/class-group names joined in — same row shape as {@link #loadPeriodsForDiscipline}. */
    public void loadPeriod(String periodId, final IDatabaseOnLoad<Period> listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            String sql = PERIODS_WITH_NAMES_SQL + " WHERE p." + StudentDbHelper.COL_PERIOD_ID + " = ?";
            Period period = null;
            try (Cursor cursor = db.rawQuery(sql, new String[]{periodId})) {
                if (cursor.moveToFirst()) period = periodWithNamesFromCursor(cursor);
            }
            final Period finalPeriod = period;
            postResult(() -> listener.onLoadComplete(finalPeriod != null, finalPeriod));
        });
    }

    /** Every enrollment's final point total for one period, with the student's current name joined in, sorted alphabetically for a readable report. */
    public void loadPeriodSnapshots(String periodId, final IDatabaseOnLoad<ArrayList<PeriodSnapshot>> listener) {
        executor.execute(() -> {
            ArrayList<PeriodSnapshot> list = new ArrayList<>();
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            String sql = "SELECT ps.*, s." + StudentDbHelper.COL_NAME + " AS student_name"
                    + " FROM " + StudentDbHelper.TABLE_PERIOD_SNAPSHOTS + " ps"
                    + " LEFT JOIN " + StudentDbHelper.TABLE_STUDENTS + " s ON s." + StudentDbHelper.COL_ID
                    + " = ps." + StudentDbHelper.COL_PERIOD_SNAPSHOT_STUDENT_ID
                    + " WHERE ps." + StudentDbHelper.COL_PERIOD_SNAPSHOT_PERIOD_ID + " = ?"
                    + " ORDER BY s." + StudentDbHelper.COL_NAME + " COLLATE NOCASE ASC";
            try (Cursor cursor = db.rawQuery(sql, new String[]{periodId})) {
                while (cursor.moveToNext()) {
                    PeriodSnapshot s = periodSnapshotFromCursor(cursor);
                    s.studentName = cursor.getString(cursor.getColumnIndexOrThrow("student_name"));
                    list.add(s);
                }
            }
            postResult(() -> listener.onLoadComplete(true, list));
        });
    }

    /** Every period snapshot ever taken of this student's enrollments (across all disciplines), oldest close first — the per-student counterpart of {@link #loadPeriodSnapshots}. */
    public void loadPeriodSnapshotsForStudent(String studentId, final IDatabaseOnLoad<ArrayList<PeriodSnapshot>> listener) {
        executor.execute(() -> {
            ArrayList<PeriodSnapshot> list = new ArrayList<>();
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            String sql = "SELECT ps.* FROM " + StudentDbHelper.TABLE_PERIOD_SNAPSHOTS + " ps"
                    + " JOIN " + StudentDbHelper.TABLE_PERIODS + " p ON p." + StudentDbHelper.COL_PERIOD_ID
                    + " = ps." + StudentDbHelper.COL_PERIOD_SNAPSHOT_PERIOD_ID
                    + " WHERE ps." + StudentDbHelper.COL_PERIOD_SNAPSHOT_STUDENT_ID + " = ?"
                    + " ORDER BY p." + StudentDbHelper.COL_PERIOD_CLOSED_AT + " ASC";
            try (Cursor cursor = db.rawQuery(sql, new String[]{studentId})) {
                while (cursor.moveToNext()) list.add(periodSnapshotFromCursor(cursor));
            }
            postResult(() -> listener.onLoadComplete(true, list));
        });
    }

    /* ------------------------------ Export ---------------------------------- */

    /**
     * One row per enrollment. Pass null studentIds to export every enrollment; otherwise every
     * enrollment (i.e. every discipline) belonging to the given students is included.
     */
    public void loadExportData(List<String> studentIds, final IDatabaseOnLoad<ArrayList<StudentExportData>> listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getReadableDatabase();

            // Goal windows are judged against the local calendar day this export is made on.
            String today = Attendance.today();

            // Every lookup table is loaded up front into an in-memory Map, so the per-enrollment
            // loop below is pure in-memory joining instead of one query per row (which would be
            // very slow for a roster of any real size).
            Map<String, String> disciplineNames = new HashMap<>();
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_DISCIPLINES, null, null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    Discipline d = disciplineFromCursor(cursor);
                    disciplineNames.put(d.id, d.name);
                }
            }

            Map<String, ClassGroup> classGroups = new HashMap<>();
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_CLASS_GROUPS, null, null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    ClassGroup g = classGroupFromCursor(cursor);
                    classGroups.put(g.id, g);
                }
            }

            Map<String, List<Goal>> goalsByDiscipline = new HashMap<>();
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_GOALS, null, null, null, null, null, GOAL_ORDER)) {
                while (cursor.moveToNext()) {
                    Goal g = goalFromCursor(cursor);
                    List<Goal> list = goalsByDiscipline.get(g.disciplineId);
                    if (list == null) {
                        list = new ArrayList<>();
                        goalsByDiscipline.put(g.disciplineId, list);
                    }
                    list.add(g);
                }
            }

            Map<String, List<PointsHistory>> historyByEnrollment = new HashMap<>();
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_POINTS_HISTORY, null, null, null, null, null,
                    StudentDbHelper.COL_HISTORY_CREATED_AT + " DESC")) {
                while (cursor.moveToNext()) {
                    PointsHistory h = pointsHistoryFromCursor(cursor);
                    List<PointsHistory> list = historyByEnrollment.get(h.enrollmentId);
                    if (list == null) {
                        list = new ArrayList<>();
                        historyByEnrollment.put(h.enrollmentId, list);
                    }
                    list.add(h);
                }
            }

            Map<String, Student> studentsById = new HashMap<>();
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_STUDENTS, null, null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    Student s = studentFromCursor(cursor);
                    studentsById.put(s.id, s);
                }
            }

            List<Enrollment> enrollments = new ArrayList<>();
            if (studentIds == null) {
                try (Cursor cursor = db.query(StudentDbHelper.TABLE_ENROLLMENTS, null, null, null, null, null, null)) {
                    while (cursor.moveToNext()) enrollments.add(enrollmentFromCursor(cursor));
                }
            } else if (!studentIds.isEmpty()) {
                // Builds a "?,?,?..." placeholder string matching studentIds.size(), since SQLite
                // has no way to bind a variable-length IN(...) list directly.
                StringBuilder placeholders = new StringBuilder();
                for (int i = 0; i < studentIds.size(); i++) {
                    if (i > 0) placeholders.append(",");
                    placeholders.append("?");
                }
                try (Cursor cursor = db.query(StudentDbHelper.TABLE_ENROLLMENTS, null,
                        StudentDbHelper.COL_ENROLLMENT_STUDENT_ID + " IN (" + placeholders + ")",
                        studentIds.toArray(new String[0]), null, null, null)) {
                    while (cursor.moveToNext()) enrollments.add(enrollmentFromCursor(cursor));
                }
            }

            ArrayList<StudentExportData> result = new ArrayList<>();
            for (Enrollment e : enrollments) {
                Student s = studentsById.get(e.studentId);
                if (s == null) continue;

                StudentExportData data = new StudentExportData();
                data.studentId = s.id;
                data.enrollmentId = e.id;
                data.studentName = s.name;
                data.photoPath = s.photoPath;
                data.points = e.grades;

                ClassGroup cg = classGroups.get(e.classGroupId);
                data.classGroupId = e.classGroupId;
                data.classGroupName = cg != null ? cg.name : "";
                data.classGroupClosed = cg != null && cg.isClosed();
                data.qrToken = s.qrToken;
                String disciplineId = cg != null ? cg.disciplineId : null;
                data.disciplineId = disciplineId;
                String disciplineName = disciplineId != null ? disciplineNames.get(disciplineId) : null;
                data.disciplineName = disciplineName != null ? disciplineName : "";

                List<PointsHistory> history = historyByEnrollment.get(e.id);

                List<Goal> goals = disciplineId != null ? goalsByDiscipline.get(disciplineId) : null;
                if (goals != null) {
                    // A goal counts only the points awarded inside its start/end window (one without
                    // dates still counts the whole period). It's a snapshot as of today, not a live reference.
                    data.goals.addAll(GoalTracker.progressFor(goals, history, e.grades, today));
                }

                if (history != null) {
                    for (PointsHistory h : history) h.disciplineName = data.disciplineName;
                    data.history.addAll(history);
                }

                result.add(data);
            }

            Collections.sort(result, (a, b) -> {
                int byName = compareNullable(a.studentName, b.studentName);
                if (byName != 0) return byName;
                return compareNullable(a.disciplineName, b.disciplineName);
            });

            postResult(() -> listener.onLoadComplete(true, result));
        });
    }

    /* ------------------------------ Import ---------------------------------- */

    /**
     * Restores data previously produced by {@link Exporter#exportJson}. Disciplines, class
     * groups, students, enrollments and points history are matched by id (re-importing the same
     * file is idempotent); goals are matched by discipline+name+target (+dates) since the export
     * does not carry a goal id.
     */
    public void importExportData(List<StudentExportData> data, final IDatabaseOnResult listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            // Wrapped in a single transaction: either the whole import lands, or (if something
            // throws before setTransactionSuccessful()) none of it does — no half-imported state.
            db.beginTransaction();
            try {
                for (StudentExportData s : data) {
                    if (s.disciplineId != null && !s.disciplineId.isEmpty()) {
                        ContentValues disciplineValues = new ContentValues();
                        disciplineValues.put(StudentDbHelper.COL_DISCIPLINE_ID, s.disciplineId);
                        disciplineValues.put(StudentDbHelper.COL_DISCIPLINE_NAME, s.disciplineName);
                        db.insertWithOnConflict(StudentDbHelper.TABLE_DISCIPLINES, null, disciplineValues,
                                SQLiteDatabase.CONFLICT_REPLACE);
                    }

                    if (s.classGroupId != null && !s.classGroupId.isEmpty()) {
                        ContentValues classGroupValues = new ContentValues();
                        classGroupValues.put(StudentDbHelper.COL_CLASS_GROUP_ID_PK, s.classGroupId);
                        classGroupValues.put(StudentDbHelper.COL_CLASS_GROUP_DISCIPLINE_ID, s.disciplineId);
                        classGroupValues.put(StudentDbHelper.COL_CLASS_GROUP_NAME, s.classGroupName);
                        db.insertWithOnConflict(StudentDbHelper.TABLE_CLASS_GROUPS, null, classGroupValues,
                                SQLiteDatabase.CONFLICT_REPLACE);
                    }

                    if (s.studentId == null || s.studentId.isEmpty()) continue;
                    ContentValues studentValues = new ContentValues();
                    studentValues.put(StudentDbHelper.COL_ID, s.studentId);
                    studentValues.put(StudentDbHelper.COL_NAME, s.studentName);
                    studentValues.put(StudentDbHelper.COL_PHOTO_PATH, s.photoPath);
                    // An older export has no token; keep the one already on this device rather than
                    // retiring a regenerated QR code just because the file predates regeneration.
                    String qrToken = s.qrToken;
                    if (qrToken == null) {
                        Student existingStudent = queryStudentById(db, s.studentId);
                        if (existingStudent != null) qrToken = existingStudent.qrToken;
                    }
                    studentValues.put(StudentDbHelper.COL_QR_TOKEN, qrToken);
                    db.insertWithOnConflict(StudentDbHelper.TABLE_STUDENTS, null, studentValues,
                            SQLiteDatabase.CONFLICT_REPLACE);

                    if (s.classGroupId != null && !s.classGroupId.isEmpty()) {
                        String enrollmentId = (s.enrollmentId != null && !s.enrollmentId.isEmpty())
                                ? s.enrollmentId : UUID.randomUUID().toString();

                        ContentValues enrollmentValues = new ContentValues();
                        enrollmentValues.put(StudentDbHelper.COL_ENROLLMENT_ID, enrollmentId);
                        enrollmentValues.put(StudentDbHelper.COL_ENROLLMENT_STUDENT_ID, s.studentId);
                        enrollmentValues.put(StudentDbHelper.COL_ENROLLMENT_CLASS_GROUP_ID, s.classGroupId);
                        enrollmentValues.put(StudentDbHelper.COL_ENROLLMENT_GRADES, s.points);
                        db.insertWithOnConflict(StudentDbHelper.TABLE_ENROLLMENTS, null, enrollmentValues,
                                SQLiteDatabase.CONFLICT_REPLACE);

                        if (s.history != null) {
                            for (PointsHistory h : s.history) {
                                String historyId = (h.id != null && !h.id.isEmpty())
                                        ? h.id : UUID.randomUUID().toString();

                                ContentValues historyValues = new ContentValues();
                                historyValues.put(StudentDbHelper.COL_HISTORY_ID, historyId);
                                historyValues.put(StudentDbHelper.COL_HISTORY_ENROLLMENT_ID, enrollmentId);
                                historyValues.put(StudentDbHelper.COL_HISTORY_POINTS_DELTA, h.pointsDelta);
                                historyValues.put(StudentDbHelper.COL_HISTORY_NOTE, h.note);
                                historyValues.put(StudentDbHelper.COL_HISTORY_CREATED_AT, h.createdAt);
                                historyValues.put(StudentDbHelper.COL_HISTORY_TAG, cleanTag(h.tag));
                                historyValues.put(StudentDbHelper.COL_HISTORY_REVERTS_ID, h.revertsId);
                                db.insertWithOnConflict(StudentDbHelper.TABLE_POINTS_HISTORY, null, historyValues,
                                        SQLiteDatabase.CONFLICT_REPLACE);
                            }
                        }
                    }

                    if (s.disciplineId != null && !s.disciplineId.isEmpty() && s.goals != null) {
                        for (GoalProgress g : s.goals) {
                            upsertGoal(db, s.disciplineId, g.goalName, g.targetPoints, g.startDate, g.endDate);
                        }
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            postResult(() -> listener.onResult(true, null));
        });
    }

    /**
     * Inserts a goal only if it isn't there yet, since the export format has no goal id to match on
     * directly. A goal with dates matches on discipline, name, target and both dates; one from an
     * older file (no dates) matches on discipline, name and target alone, so re-importing such a
     * file after the teacher gave that goal dates doesn't bring back an undated copy.
     */
    private static void upsertGoal(SQLiteDatabase db, String disciplineId, String name, int targetPoints,
                                   String startDate, String endDate) {
        // A file that carries nonsense dates is treated like an older one: the goal comes in without a window.
        boolean dated = Attendance.isValidDay(startDate) && Attendance.isValidDay(endDate) && startDate.compareTo(endDate) <= 0;
        String selection = StudentDbHelper.COL_GOAL_DISCIPLINE_ID + "=? AND " + StudentDbHelper.COL_GOAL_NAME + "=? AND "
                + StudentDbHelper.COL_GOAL_TARGET_POINTS + "=?"
                + (dated ? " AND " + StudentDbHelper.COL_GOAL_START_DATE + "=? AND " + StudentDbHelper.COL_GOAL_END_DATE + "=?" : "");
        String[] args = dated
                ? new String[]{disciplineId, name, String.valueOf(targetPoints), startDate, endDate}
                : new String[]{disciplineId, name, String.valueOf(targetPoints)};
        try (Cursor cursor = db.query(StudentDbHelper.TABLE_GOALS,
                new String[]{StudentDbHelper.COL_GOAL_ID}, selection, args, null, null, null)) {
            if (cursor.moveToFirst()) return;
        }

        ContentValues goalValues = new ContentValues();
        goalValues.put(StudentDbHelper.COL_GOAL_ID, UUID.randomUUID().toString());
        goalValues.put(StudentDbHelper.COL_GOAL_DISCIPLINE_ID, disciplineId);
        goalValues.put(StudentDbHelper.COL_GOAL_NAME, name);
        goalValues.put(StudentDbHelper.COL_GOAL_TARGET_POINTS, targetPoints);
        if (dated) {
            goalValues.put(StudentDbHelper.COL_GOAL_START_DATE, startDate);
            goalValues.put(StudentDbHelper.COL_GOAL_END_DATE, endDate);
        }
        db.insertWithOnConflict(StudentDbHelper.TABLE_GOALS, null, goalValues, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /* ------------------------------ CSV import ------------------------------- */

    /**
     * Folds a name down to a key that's stable across the kind of cosmetic differences a CSV
     * re-export commonly introduces: accents/diacritics (NFKD-decompose, then drop the combining
     * marks — "José" and "Jose" fold to the same key), case, and whitespace (collapses any run of
     * spaces/tabs/non-breaking-space — common when pasted from Excel/Sheets — into one, and trims
     * the ends). Without this, two spellings of the same real discipline/class/student that a
     * human reads as identical would fail to match, silently creating a duplicate with a fresh,
     * zeroed enrollment instead of reusing the existing one (see {@link #resolveCsvRows}).
     */
    private static String normalize(String s) {
        if (s == null) return "";
        String withoutAccents = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        return withoutAccents.replaceAll("[\\s\\p{Zs}]+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Matches parsed CSV rows against existing disciplines/class-groups/students by
     * case-insensitive name. Class groups are matched within their resolved discipline only
     * (names may repeat across disciplines). A discipline or class group named in the CSV but not
     * found in the database is not an error: a new id is generated for it here (reused by every
     * other row that names the same one) and {@link Database#importCsvRows} creates the actual
     * row when the plan is committed — this lets a whole roster (disciplines, class groups, and
     * students together) be bootstrapped from one CSV. Only a row with a blank discipline/class
     * group name becomes a {@link CsvRowError}, since there's nothing to create from nothing.
     */
    public void resolveCsvRows(List<CsvStudentRow> rows, final IDatabaseOnLoad<CsvImportPlan> listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getReadableDatabase();

            Map<String, Discipline> disciplineByName = new HashMap<>();
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_DISCIPLINES, null, null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    Discipline d = disciplineFromCursor(cursor);
                    disciplineByName.put(normalize(d.name), d);
                }
            }

            Map<String, ClassGroup> classGroupByKey = new HashMap<>();
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_CLASS_GROUPS, null, null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    ClassGroup g = classGroupFromCursor(cursor);
                    // Keyed by disciplineId+name (not name alone) since the same class-group name
                    // ("Morning A") could legitimately exist under two different disciplines.
                    classGroupByKey.put(g.disciplineId + "|" + normalize(g.name), g);
                }
            }

            Map<String, Student> studentByName = new HashMap<>();
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_STUDENTS, null, null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    Student s = studentFromCursor(cursor);
                    studentByName.put(normalize(s.name), s);
                }
            }

            CsvImportPlan plan = new CsvImportPlan();
            for (CsvStudentRow row : rows) {
                String disciplineName = row.disciplineName == null ? "" : row.disciplineName.trim();
                if (disciplineName.isEmpty()) {
                    plan.errors.add(new CsvRowError(row.lineNumber, "Missing discipline name"));
                    continue;
                }
                String classGroupName = row.classGroupName == null ? "" : row.classGroupName.trim();
                if (classGroupName.isEmpty()) {
                    plan.errors.add(new CsvRowError(row.lineNumber, "Missing class group name"));
                    continue;
                }

                Discipline discipline = disciplineByName.get(normalize(disciplineName));
                boolean isNewDiscipline = discipline == null;
                if (isNewDiscipline) {
                    // Remember this newly-seen discipline so later rows for it (and its class
                    // groups) in this file reuse the same id instead of creating a duplicate.
                    discipline = new Discipline();
                    discipline.id = UUID.randomUUID().toString();
                    discipline.name = disciplineName;
                    disciplineByName.put(normalize(disciplineName), discipline);
                }

                String classGroupKey = discipline.id + "|" + normalize(classGroupName);
                ClassGroup classGroup = classGroupByKey.get(classGroupKey);
                boolean isNewClassGroup = classGroup == null;
                if (isNewClassGroup) {
                    classGroup = new ClassGroup();
                    classGroup.id = UUID.randomUUID().toString();
                    classGroup.disciplineId = discipline.id;
                    classGroup.name = classGroupName;
                    classGroupByKey.put(classGroupKey, classGroup);
                }

                Student existing = studentByName.get(normalize(row.name));
                boolean isNewStudent = existing == null;
                String studentId = isNewStudent ? UUID.randomUUID().toString() : existing.id;

                if (isNewStudent) {
                    // Remember this newly-seen name so later rows for the same student in this
                    // file reuse the same id instead of creating a duplicate.
                    Student placeholder = new Student();
                    placeholder.id = studentId;
                    placeholder.name = row.name;
                    studentByName.put(normalize(row.name), placeholder);
                }

                plan.resolved.add(new ResolvedCsvRow(studentId, row.name, isNewStudent,
                        discipline.id, discipline.name, isNewDiscipline,
                        classGroup.id, classGroup.name, isNewClassGroup));
            }

            postResult(() -> listener.onLoadComplete(true, plan));
        });
    }

    /**
     * Creates new disciplines, class groups, students and enrollments from a resolved CSV plan.
     * Never resets an existing enrollment's grades: an enrollment is only inserted if the student
     * wasn't already enrolled in that class group.
     */
    public void importCsvRows(List<ResolvedCsvRow> rows, final IDatabaseOnResult listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                // The same new discipline/class-group/student can appear on several CSV rows
                // (e.g. every student in a class names that class's discipline) — these sets make
                // sure each one is only inserted once.
                Set<String> insertedDisciplineIds = new HashSet<>();
                Set<String> insertedClassGroupIds = new HashSet<>();
                Set<String> insertedStudentIds = new HashSet<>();
                for (ResolvedCsvRow row : rows) {
                    if (row.isNewDiscipline && insertedDisciplineIds.add(row.disciplineId)) {
                        ContentValues disciplineValues = new ContentValues();
                        disciplineValues.put(StudentDbHelper.COL_DISCIPLINE_ID, row.disciplineId);
                        disciplineValues.put(StudentDbHelper.COL_DISCIPLINE_NAME, row.disciplineName);
                        db.insertWithOnConflict(StudentDbHelper.TABLE_DISCIPLINES, null, disciplineValues,
                                SQLiteDatabase.CONFLICT_IGNORE);
                    }

                    if (row.isNewClassGroup && insertedClassGroupIds.add(row.classGroupId)) {
                        ContentValues classGroupValues = new ContentValues();
                        classGroupValues.put(StudentDbHelper.COL_CLASS_GROUP_ID_PK, row.classGroupId);
                        classGroupValues.put(StudentDbHelper.COL_CLASS_GROUP_DISCIPLINE_ID, row.disciplineId);
                        classGroupValues.put(StudentDbHelper.COL_CLASS_GROUP_NAME, row.classGroupName);
                        db.insertWithOnConflict(StudentDbHelper.TABLE_CLASS_GROUPS, null, classGroupValues,
                                SQLiteDatabase.CONFLICT_IGNORE);
                    }

                    if (row.isNewStudent && insertedStudentIds.add(row.studentId)) {
                        ContentValues studentValues = new ContentValues();
                        studentValues.put(StudentDbHelper.COL_ID, row.studentId);
                        studentValues.put(StudentDbHelper.COL_NAME, row.name);
                        db.insertWithOnConflict(StudentDbHelper.TABLE_STUDENTS, null, studentValues,
                                SQLiteDatabase.CONFLICT_IGNORE);
                    }

                    boolean enrollmentExists;
                    try (Cursor cursor = db.query(StudentDbHelper.TABLE_ENROLLMENTS,
                            new String[]{StudentDbHelper.COL_ENROLLMENT_ID},
                            StudentDbHelper.COL_ENROLLMENT_STUDENT_ID + "=? AND " + StudentDbHelper.COL_ENROLLMENT_CLASS_GROUP_ID + "=?",
                            new String[]{row.studentId, row.classGroupId}, null, null, null)) {
                        enrollmentExists = cursor.moveToFirst();
                    }

                    // Only create the enrollment if it's genuinely missing — an existing one is
                    // left completely untouched so a re-import never wipes out real points.
                    if (!enrollmentExists) {
                        ContentValues enrollmentValues = new ContentValues();
                        enrollmentValues.put(StudentDbHelper.COL_ENROLLMENT_ID, UUID.randomUUID().toString());
                        enrollmentValues.put(StudentDbHelper.COL_ENROLLMENT_STUDENT_ID, row.studentId);
                        enrollmentValues.put(StudentDbHelper.COL_ENROLLMENT_CLASS_GROUP_ID, row.classGroupId);
                        enrollmentValues.put(StudentDbHelper.COL_ENROLLMENT_GRADES, 0);
                        db.insertWithOnConflict(StudentDbHelper.TABLE_ENROLLMENTS, null, enrollmentValues,
                                SQLiteDatabase.CONFLICT_IGNORE);
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            postResult(() -> listener.onResult(true, null));
        });
    }

    /* ------------------------------ Backups ----------------------------------- */

    /**
     * Copies the live SQLite file to {@code destFile}. Scheduled on the same single-thread
     * executor as every other write, so it naturally runs only once any prior write has fully
     * committed and blocks new writes until the copy finishes — no extra locking needed. The
     * database is never put into WAL mode ({@code enableWriteAheadLogging()} is never called), so
     * when idle there is no separate journal file to reconcile: the .db file alone is a
     * consistent, fully-committed snapshot.
     */
    public void createSnapshot(File destFile, final IDatabaseOnResult listener) {
        executor.execute(() -> {
            // Forces the database file to actually exist on disk before copying it (a brand new
            // Database instance won't have created the file yet without this call).
            dbHelper.getReadableDatabase();
            File dbFile = appContext.getDatabasePath(StudentDbHelper.DB_NAME);

            boolean success;
            String error = null;
            try {
                File parent = destFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();

                try (FileInputStream in = new FileInputStream(dbFile);
                     FileOutputStream out = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                }
                success = true;
            } catch (IOException e) {
                success = false;
                error = e.getMessage();
            }

            final boolean finalSuccess = success;
            final String finalError = error;
            postResult(() -> listener.onResult(finalSuccess, finalError));
        });
    }
}
