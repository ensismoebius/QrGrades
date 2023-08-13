package org.dedira.qrnotas.util;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import org.dedira.qrnotas.model.Student;

import java.util.ArrayList;
import java.util.Map;

public class Database {
    private final FirebaseFirestore db;

    public Database() {
        db = FirebaseFirestore.getInstance();
    }

    public void updateStudentFull(String id, Student updatedStudent, final IDatabaseOnUpdate<Student> listener) {
        DocumentReference studentRef = db.collection("students").document(id);

        studentRef.set(updatedStudent).addOnSuccessListener(aVoid -> {
            updatedStudent.id = id; // Update the id in the updatedStudent object
            listener.onUpdateComplete(true, updatedStudent);
        }).addOnFailureListener(e -> listener.onUpdateComplete(false, null));
    }

    public void updateStudentFields(String id, Map<String, Object> updatedFields, final IDatabaseOnUpdate<Student> listener) {
        DocumentReference studentRef = db.collection("students").document(id);

        studentRef.update(updatedFields).addOnSuccessListener(e -> listener.onUpdateComplete(true, null)).addOnFailureListener(e -> listener.onUpdateComplete(false, null));
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
        db.collection("students").add(s).addOnSuccessListener(docRef -> {
            s.id = docRef.getId();
            listener.onSaveComplete(true, s);
        }).addOnFailureListener(error -> listener.onSaveComplete(false, s));
    }

    public void loadStudent(String id, final IDatabaseOnLoad<Student> listener) {
        DocumentReference studentRef = db.collection("students").document(id);

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
