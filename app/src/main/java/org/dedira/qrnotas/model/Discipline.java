package org.dedira.qrnotas.model;

public class Discipline {
    public String id;
    public String name;

    @Override
    public String toString() {
        return name == null ? "" : name;
    }
}
