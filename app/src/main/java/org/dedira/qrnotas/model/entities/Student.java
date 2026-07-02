package org.dedira.qrnotas.model;

public class Student {
    public String id;
    public String name;
    public String photoPath;

    @Override
    public String toString() {
        return name == null ? "" : name;
    }
}
