package org.dedira.qrnotas.util;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;

import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Goal;
import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.model.StudentExportData;

import java.util.ArrayList;
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
        s.grades = cursor.getInt(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_GRADES));
        s.photoPath = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_PHOTO_PATH));
        s.classGroupId = cursor.getString(cursor.getColumnIndexOrThrow(StudentDbHelper.COL_CLASS_GROUP_ID));
        return s;
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

    private static Student queryStudentById(SQLiteDatabase db, String id) {
        try (Cursor cursor = db.query(StudentDbHelper.TABLE_STUDENTS, null,
                StudentDbHelper.COL_ID + "=?", new String[]{id}, null, null, null)) {
            if (cursor.moveToFirst()) return studentFromCursor(cursor);
        }
        return null;
    }

    /* ------------------------------ Students ------------------------------ */

    public void deleteStudent(String studentId, final IDatabaseOnDelete<Student> listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            int rows = db.delete(StudentDbHelper.TABLE_STUDENTS, StudentDbHelper.COL_ID + "=?", new String[]{studentId});
            boolean success = rows > 0;
            postResult(() -> listener.onLoadComplete(success, null));
        });
    }

    public void updateStudentFields(String id, Map<String, Object> updatedFields, final IDatabaseOnUpdate<Student> listener) {
        executor.execute(() -> {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            ContentValues values = new ContentValues();
            for (Map.Entry<String, Object> entry : updatedFields.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Integer) {
                    values.put(entry.getKey(), (Integer) value);
                } else if (value instanceof String) {
                    values.put(entry.getKey(), (String) value);
                }
            }

            int rows = db.update(StudentDbHelper.TABLE_STUDENTS, values, StudentDbHelper.COL_ID + "=?", new String[]{id});
            if (rows <= 0) {
                postResult(() -> listener.onUpdateComplete(false, null));
                return;
            }

            Student updated = queryStudentById(db, id);
            postResult(() -> listener.onUpdateComplete(updated != null, updated));
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
            boolean isNew = s.id == null;
            if (isNew) s.id = UUID.randomUUID().toString();

            ContentValues values = new ContentValues();
            values.put(StudentDbHelper.COL_ID, s.id);
            values.put(StudentDbHelper.COL_NAME, s.name);
            values.put(StudentDbHelper.COL_GRADES, s.grades);
            values.put(StudentDbHelper.COL_PHOTO_PATH, s.photoPath);
            values.put(StudentDbHelper.COL_CLASS_GROUP_ID, s.classGroupId);

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
            long studentCount = android.database.DatabaseUtils.queryNumEntries(db, StudentDbHelper.TABLE_STUDENTS,
                    StudentDbHelper.COL_CLASS_GROUP_ID + "=?", new String[]{id});

            if (studentCount > 0) {
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

    /** Pass null studentIds to export every student. */
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

            List<Student> students = new ArrayList<>();
            if (studentIds == null) {
                try (Cursor cursor = db.query(StudentDbHelper.TABLE_STUDENTS, null, null, null, null, null,
                        StudentDbHelper.COL_NAME + " ASC")) {
                    while (cursor.moveToNext()) students.add(studentFromCursor(cursor));
                }
            } else if (!studentIds.isEmpty()) {
                StringBuilder placeholders = new StringBuilder();
                for (int i = 0; i < studentIds.size(); i++) {
                    if (i > 0) placeholders.append(",");
                    placeholders.append("?");
                }
                try (Cursor cursor = db.query(StudentDbHelper.TABLE_STUDENTS, null,
                        StudentDbHelper.COL_ID + " IN (" + placeholders + ")",
                        studentIds.toArray(new String[0]), null, null, StudentDbHelper.COL_NAME + " ASC")) {
                    while (cursor.moveToNext()) students.add(studentFromCursor(cursor));
                }
            }

            ArrayList<StudentExportData> result = new ArrayList<>();
            for (Student s : students) {
                StudentExportData data = new StudentExportData();
                data.studentId = s.id;
                data.studentName = s.name;
                data.photoPath = s.photoPath;
                data.points = s.grades;

                ClassGroup cg = classGroups.get(s.classGroupId);
                data.classGroupName = cg != null ? cg.name : "";
                String disciplineId = cg != null ? cg.disciplineId : null;
                String disciplineName = disciplineId != null ? disciplineNames.get(disciplineId) : null;
                data.disciplineName = disciplineName != null ? disciplineName : "";

                List<Goal> goals = disciplineId != null ? goalsByDiscipline.get(disciplineId) : null;
                if (goals != null) {
                    for (Goal g : goals) {
                        data.goals.add(new GoalProgress(g.name, g.targetPoints, s.grades));
                    }
                }

                result.add(data);
            }

            postResult(() -> listener.onLoadComplete(true, result));
        });
    }
}
