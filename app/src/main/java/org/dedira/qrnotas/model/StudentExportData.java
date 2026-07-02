package org.dedira.qrnotas.model;

import java.util.ArrayList;
import java.util.List;

public class StudentExportData {
    public String studentId;
    public String studentName;
    public String photoPath;
    public String disciplineName;
    public String classGroupName;
    public int points;
    public List<GoalProgress> goals = new ArrayList<>();
}
