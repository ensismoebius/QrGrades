package org.dedira.qrnotas.model;

/** A CSV row whose discipline/class-group/student have all been matched against the database. */
public class ResolvedCsvRow {
    public final String studentId;
    public final String name;
    public final boolean isNewStudent;
    public final String disciplineId;
    public final String classGroupId;

    public ResolvedCsvRow(String studentId, String name, boolean isNewStudent, String disciplineId, String classGroupId) {
        this.studentId = studentId;
        this.name = name;
        this.isNewStudent = isNewStudent;
        this.disciplineId = disciplineId;
        this.classGroupId = classGroupId;
    }
}
