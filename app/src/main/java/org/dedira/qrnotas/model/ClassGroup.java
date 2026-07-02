package org.dedira.qrnotas.model;

public class ClassGroup {
    public String id;
    public String disciplineId;
    public String name;

    @Override
    public String toString() {
        return name == null ? "" : name;
    }
}
