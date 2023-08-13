package org.dedira.qrnotas;

import android.graphics.Bitmap;

import com.google.firebase.database.IgnoreExtraProperties;
import com.google.firebase.database.PropertyName;

import java.util.ArrayList;

@IgnoreExtraProperties
public class Student {
    public String key;
    public String name;
    public ArrayList<Integer> grades;
    public Bitmap photo;

    @PropertyName("key")
    public String getKey() {
        return key;
    }

    @PropertyName("key")
    public void setKey(String key) {
        this.key = key;
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
    public ArrayList<Integer> getGrades() {
        return grades;
    }

    @PropertyName("grades")
    public void setGrades(ArrayList<Integer> grades) {
        this.grades = grades;
    }

    @PropertyName("photo")
    public Bitmap getPhoto() {
        return photo;
    }

    @PropertyName("photo")
    public void setPhoto(Bitmap photo) {
        this.photo = photo;
    }
}
