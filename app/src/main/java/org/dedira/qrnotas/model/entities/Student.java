package org.dedira.qrnotas.model;

public class Student {
    public String id;
    public String name;
    public Integer grades = 0;
    public String photoPath;
    public String classGroupId;

    @Override
    public String toString() {
        return name == null ? "" : name;
    }
}
