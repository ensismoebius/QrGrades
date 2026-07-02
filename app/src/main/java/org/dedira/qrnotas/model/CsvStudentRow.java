package org.dedira.qrnotas.model;

/** One parsed row from a roster CSV, before matching against the database. */
public class CsvStudentRow {
    public final int lineNumber;
    public final String name;
    public final String disciplineName;
    public final String classGroupName;

    public CsvStudentRow(int lineNumber, String name, String disciplineName, String classGroupName) {
        this.lineNumber = lineNumber;
        this.name = name;
        this.disciplineName = disciplineName;
        this.classGroupName = classGroupName;
    }
}
