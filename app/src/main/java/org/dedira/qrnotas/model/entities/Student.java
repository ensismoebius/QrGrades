package org.dedira.qrnotas.model.entities;

import com.google.firebase.database.IgnoreExtraProperties;

@IgnoreExtraProperties
public class Student {
    public String id;
    public String name;
    public Integer grades = 0   ;
    public String photo;
}
