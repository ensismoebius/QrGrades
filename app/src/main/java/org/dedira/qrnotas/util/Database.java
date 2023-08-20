package org.dedira.qrnotas.util;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import org.dedira.qrnotas.model.Student;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class Database {
    private final FirebaseFirestore db;

    public Database() {
        db = FirebaseFirestore.getInstance();
    }

    public void deleteStudent(String studentId, final IDatabaseOnDelete listener) {
        DocumentReference studentRef = db.collection("students").document(studentId);

        studentRef.delete().addOnSuccessListener(aVoid -> listener.onLoadComplete(true, null)).addOnFailureListener(e -> listener.onLoadComplete(false, null));
    }

    public void updateStudentFields(String id, Map<String, Object> updatedFields, final IDatabaseOnUpdate<Student> listener) {
        DocumentReference studentRef = db.collection("students").document(id);

        studentRef.update(updatedFields).addOnSuccessListener(aVoid -> {
            // Get the updated student document to provide in the callback
            studentRef.get().addOnSuccessListener(documentSnapshot -> {
                if (documentSnapshot.exists()) {
                    Student updatedStudent = documentSnapshot.toObject(Student.class);
                    listener.onUpdateComplete(true, updatedStudent);
                } else {
                    listener.onUpdateComplete(false, null);
                }
            }).addOnFailureListener(e -> listener.onUpdateComplete(false, null));
        }).addOnFailureListener(e -> listener.onUpdateComplete(false, null));
    }

    public void loadAllStudents(final IDatabaseOnLoad<ArrayList<Student>> listener) {
        db.collection("students").get().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                ArrayList<Student> studentList = new ArrayList<>();
                for (QueryDocumentSnapshot document : task.getResult()) {
                    Student s = document.toObject(Student.class);
                    s.id = document.getId();
                    studentList.add(s);
                }
                listener.onLoadComplete(true, studentList);
            } else {
                listener.onLoadComplete(false, null);
            }
        });
    }

    public void saveStudent(Student s, final IDatabaseOnSave<Student> listener) {

        // Is a new student?
        if (s.id == null) {

            // Save new student
            db.collection("students").add(s).addOnSuccessListener(docRef -> {
                s.id = docRef.getId();
                listener.onSaveComplete(true, s);
            }).addOnFailureListener(error -> listener.onSaveComplete(false, s));
        } else {
            //String id, Map<String, Object> updatedFields, final IDatabaseOnUpdate<Student> listener) {

            Map<String, Object> fields = new HashMap<>();
            fields.put("name", s.name);
            fields.put("grades", s.grades);
            fields.put("photo", s.photo);

            updateStudentFields(s.id, fields, new IDatabaseOnUpdate<Student>() {
                @Override
                public void onUpdateComplete(boolean success, Student object) {
                    listener.onSaveComplete(success, object);
                }
            });
        }
    }

    public void loadStudent(String id, final IDatabaseOnLoad<Student> listener) {

        DocumentReference studentRef;

        try {
            studentRef = db.collection("students").document(id);
        } catch (Exception e) {
            listener.onLoadComplete(false, null);
            return;
        }

        studentRef.get().addOnCompleteListener(task -> {
            if (task.getResult().toObject(Student.class) == null) {
                listener.onLoadComplete(false, null);
                return;
            }

            Student s = task.getResult().toObject(Student.class);

            if (s == null) {
                listener.onLoadComplete(true, null);
                return;
            }

            s.id = studentRef.getId();
            listener.onLoadComplete(true, s);
        }).addOnFailureListener(task -> listener.onLoadComplete(false, null));
    }
}
