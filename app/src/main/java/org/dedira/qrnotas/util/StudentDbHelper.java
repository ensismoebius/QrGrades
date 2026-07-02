package org.dedira.qrnotas.util;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class StudentDbHelper extends SQLiteOpenHelper {
    static final String DB_NAME = "qrgrades.db";
    private static final int DB_VERSION = 4;

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

    public StudentDbHelper(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
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

        db.execSQL("CREATE TABLE " + TABLE_ENROLLMENTS + " (" +
                COL_ENROLLMENT_ID + " TEXT PRIMARY KEY, " +
                COL_ENROLLMENT_STUDENT_ID + " TEXT NOT NULL, " +
                COL_ENROLLMENT_CLASS_GROUP_ID + " TEXT NOT NULL, " +
                COL_ENROLLMENT_GRADES + " INTEGER NOT NULL DEFAULT 0, " +
                "UNIQUE(" + COL_ENROLLMENT_STUDENT_ID + ", " + COL_ENROLLMENT_CLASS_GROUP_ID + "))");

        db.execSQL("CREATE TABLE " + TABLE_POINTS_HISTORY + " (" +
                COL_HISTORY_ID + " TEXT PRIMARY KEY, " +
                COL_HISTORY_ENROLLMENT_ID + " TEXT NOT NULL, " +
                COL_HISTORY_POINTS_DELTA + " INTEGER NOT NULL, " +
                COL_HISTORY_NOTE + " TEXT, " +
                COL_HISTORY_CREATED_AT + " INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_POINTS_HISTORY);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_ENROLLMENTS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_STUDENTS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_GOALS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CLASS_GROUPS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_DISCIPLINES);
        onCreate(db);
    }
}
