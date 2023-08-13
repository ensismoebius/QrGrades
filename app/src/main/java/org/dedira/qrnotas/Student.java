package org.dedira.qrnotas;

import com.google.firebase.database.IgnoreExtraProperties;
import com.google.firebase.database.PropertyName;

@IgnoreExtraProperties
public class Student {
    public String id;

    public String name;
    public Integer grades = 0;
    public String photo;

    @PropertyName("id")
    public String getId() {
        return id;
    }

    @PropertyName("id")
    public void setId(String id) {
        this.id = id;
    }

    @PropertyName("name")
    public String getName() {
        return name;
    }

    @PropertyName("name")
    public void setName(String name) {
        this.name = name;
    }

    @PropertyName("grades")
    public Integer getGrades() {
        return grades;
    }

    @PropertyName("grades")
    public void setGrades(Integer grades) {
        this.grades = grades;
    }

    @PropertyName("photo")
    public String getPhoto() {
        return photo;
    }

    @PropertyName("photo")
    public void setPhoto(String photo) {
        this.photo = photo;
    }
}
