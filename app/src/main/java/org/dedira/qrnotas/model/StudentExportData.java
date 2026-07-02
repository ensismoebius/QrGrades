package org.dedira.qrnotas.model;

import java.util.ArrayList;
import java.util.List;

/** One row per student enrollment (a student with several enrollments yields several rows). */
public class StudentExportData {
    public String studentId;
    public String enrollmentId;
    public String studentName;
    public String photoPath;
    public String disciplineId;
    public String disciplineName;
    public String classGroupId;
    public String classGroupName;
    public int points;
    public List<GoalProgress> goals = new ArrayList<>();
    public List<PointsHistory> history = new ArrayList<>();
}
