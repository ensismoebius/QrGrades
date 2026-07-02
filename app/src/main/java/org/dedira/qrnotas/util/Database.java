package org.dedira.qrnotas.util;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;

import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Enrollment;
import org.dedira.qrnotas.model.Goal;
import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.model.IDatabaseOnDelete;
import org.dedira.qrnotas.model.IDatabaseOnLoad;
import org.dedira.qrnotas.model.IDatabaseOnSave;
import org.dedira.qrnotas.model.IDatabaseOnUpdate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Database {
    private final StudentDbHelper dbHelper;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public Database(Context context) {
        dbHelper = new StudentDbHelper(context);
    }

    private void postResult(Runnable r) {
        mainHandler.post(r);
    }

    private static Student studentFromCursor(Cursor cursor) {
        Student s = new Student();
        s.id = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_ID));
        s.name = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_NAME));
        s.photoPath = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_PHOTO_PATH));
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
        return g;
    }

    private static Goal goalFromCursor(Cursor cursor) {
        Goal g = new Goal();
        g.id = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_GOAL_ID));
        g.disciplineId = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_GOAL_DISCIPLINE_ID));
        g.name = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_GOAL_NAME));
        g.targetPoints = cursor.getInt(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_GOAL_TARGET_POINTS));
        return g;
    }

    private static PointsHistory pointsHistoryFromCursor(Cursor cursor) {
        PointsHistory h = new PointsHistory();
        h.id = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_HISTORY_ID));
        h.enrollmentId = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_HISTORY_ENROLLMENT_ID));
        h.pointsDelta = cursor.getInt(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_HISTORY_POINTS_DELTA));
        h.note = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_HISTORY_NOTE));
        h.createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_HISTORY_CREATED_AT));
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

    private static int compareNullable(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.compareToIgnoreCase(b);
    }

    /* ------------------------------ Students ------------------------------ */

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

    public void saveStudent(Student s, final IDatabaseOnSave<Student> listener) {
        executor.execute(() -> {
            if (s.id == null) s.id = UUID.randomUUID().toString();

            ContentValues values = new ContentValues();
            values.put(StudentDbHelper.COL_ID, s.id);
            values.put(StudentDbHelper.COL_NAME, s.name);
            values.put(StudentDbHelper.COL_PHOTO_PATH, s.photoPath);

            SQLiteDatabase db = dbHelper.getWritableDatabase();
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
            postResult(() -> listener.onResult(rows > 0, null));
        });
    }

    /* ------------------------------- Goals ---------------------------------- */

    public void saveGoal(Goal g, final IDatabaseOnSave<Goal> listener) {
        executor.execute(() -> {
            if (g.id == null) g.id = UUID.randomUUID().toString();

            ContentValues values = new ContentValues();
            values.put(StudentDbHelper.COL_GOAL_ID, g.id);
            values.put(StudentDbHelper.COL_GOAL_DISCIPLINE_ID, g.disciplineId);
            values.put(StudentDbHelper.COL_GOAL_NAME, g.name);
            values.put(StudentDbHelper.COL_GOAL_TARGET_POINTS, g.targetPoints);

            SQLiteDatabase db = dbHelper.getWritableDatabase();
            long result = db.insertWithOnConflict(StudentDbHelper.TABLE_GOALS, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
            boolean success = result != -1;

            postResult(() -> listener.onSaveComplete(success, g));
        });
    }

    public void loadGoalsForDiscipline(String disciplineId, final IDatabaseOnLoad<ArrayList<Goal>> listener) {
        executor.execute(() -> {
            ArrayList<Goal> list = new ArrayList<>();
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_GOALS, null,
                    StudentDbHelper.COL_GOAL_DISCIPLINE_ID + "=?", new String[]{disciplineId}, null, null,
                    StudentDbHelper.COL_GOAL_TARGET_POINTS + " ASC")) {
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

    /* ------------------------------ Export ---------------------------------- */

    /**
     * One row per enrollment. Pass null studentIds to export every enrollment; otherwise every
     * enrollment (i.e. every discipline) belonging to the given students is included.
     */
    public void loadExportData(List<String> studentIds, final IDatabaseOnLoad<ArrayList<StudentExportData>> listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getReadableDatabase();

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
            try (Cursor cursor = db.query(StudentDbHelper.TABLE_GOALS, null, null, null, null, null,
                    StudentDbHelper.COL_GOAL_TARGET_POINTS + " ASC")) {
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
                String disciplineId = cg != null ? cg.disciplineId : null;
                data.disciplineId = disciplineId;
                String disciplineName = disciplineId != null ? disciplineNames.get(disciplineId) : null;
                data.disciplineName = disciplineName != null ? disciplineName : "";

                List<Goal> goals = disciplineId != null ? goalsByDiscipline.get(disciplineId) : null;
                if (goals != null) {
                    for (Goal g : goals) {
                        data.goals.add(new GoalProgress(g.name, g.targetPoints, e.grades));
                    }
                }

                List<PointsHistory> history = historyByEnrollment.get(e.id);
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
     * file is idempotent); goals are matched by discipline+name+target since the export does not
     * carry a goal id.
     */
    public void importExportData(List<StudentExportData> data, final IDatabaseOnResult listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
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
                                db.insertWithOnConflict(StudentDbHelper.TABLE_POINTS_HISTORY, null, historyValues,
                                        SQLiteDatabase.CONFLICT_REPLACE);
                            }
                        }
                    }

                    if (s.disciplineId != null && !s.disciplineId.isEmpty() && s.goals != null) {
                        for (GoalProgress g : s.goals) {
                            upsertGoal(db, s.disciplineId, g.goalName, g.targetPoints);
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

    private static void upsertGoal(SQLiteDatabase db, String disciplineId, String name, int targetPoints) {
        try (Cursor cursor = db.query(StudentDbHelper.TABLE_GOALS,
                new String[]{StudentDbHelper.COL_GOAL_ID},
                StudentDbHelper.COL_GOAL_DISCIPLINE_ID + "=? AND " + StudentDbHelper.COL_GOAL_NAME + "=? AND "
                        + StudentDbHelper.COL_GOAL_TARGET_POINTS + "=?",
                new String[]{disciplineId, name, String.valueOf(targetPoints)}, null, null, null)) {
            if (cursor.moveToFirst()) return;
        }

        ContentValues goalValues = new ContentValues();
        goalValues.put(StudentDbHelper.COL_GOAL_ID, UUID.randomUUID().toString());
        goalValues.put(StudentDbHelper.COL_GOAL_DISCIPLINE_ID, disciplineId);
        goalValues.put(StudentDbHelper.COL_GOAL_NAME, name);
        goalValues.put(StudentDbHelper.COL_GOAL_TARGET_POINTS, targetPoints);
        db.insertWithOnConflict(StudentDbHelper.TABLE_GOALS, null, goalValues, SQLiteDatabase.CONFLICT_REPLACE);
    }
}
