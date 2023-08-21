package org.dedira.qrnotas.model;

import com.google.firebase.database.IgnoreExtraProperties;

@IgnoreExtraProperties
public class Student {
    public String id;
    public String name;
    public Integer grades = 0;
    public String photo;
}
