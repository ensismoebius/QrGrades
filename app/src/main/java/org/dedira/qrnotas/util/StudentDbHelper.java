package org.dedira.qrnotas.util;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class StudentDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "qrgrades.db";
    private static final int DB_VERSION = 2;

    public static final String TABLE_STUDENTS = "students";
    public static final String COL_ID = "id";
    public static final String COL_NAME = "name";
    public static final String COL_GRADES = "grades";
    public static final String COL_PHOTO_PATH = "photo_path";
    public static final String COL_CLASS_GROUP_ID = "class_group_id";

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
                COL_GRADES + " INTEGER NOT NULL DEFAULT 0, " +
                COL_PHOTO_PATH + " TEXT, " +
                COL_CLASS_GROUP_ID + " TEXT NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_STUDENTS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_GOALS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CLASS_GROUPS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_DISCIPLINES);
        onCreate(db);
    }
}
