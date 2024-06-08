package org.dedira.qrnotas.model;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.PersistentCacheSettings;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import org.dedira.qrnotas.model.entities.Student;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class Database {
    private final FirebaseFirestore db;

    public Database() {
        db = FirebaseFirestore.getInstance();

        FirebaseFirestoreSettings.Builder builder = new FirebaseFirestoreSettings.Builder(db.getFirestoreSettings());
        builder.setLocalCacheSettings(PersistentCacheSettings.newBuilder().build()); // Use persistent disk cache (default)
        FirebaseFirestoreSettings settings = builder.build();

        db.setFirestoreSettings(settings);
    }

    public void deleteStudent(String studentId, final IDatabaseOnDelete<Student> listener) {
        DocumentReference studentRef = db.collection("students").document(studentId);
        studentRef.delete().addOnSuccessListener(aVoid -> listener.onLoadComplete(true, null)).addOnFailureListener(e -> listener.onLoadComplete(false, null));
    }

    public void updateStudentFields(String id, Map<String, Object> updatedFields, final IDatabaseOnUpdate<Student> listener) {
        DocumentReference studentRef = db.collection("students").document(id);

        studentRef.update(updatedFields)
                .addOnSuccessListener(aVoid -> {
                    // Fetch the updated student data after the update succeeds
                    studentRef.get().addOnSuccessListener(documentSnapshot -> {
                        if (documentSnapshot.exists()) {
                            // Convert the updated documentSnapshot to a Student object
                            Student updatedStudent = documentSnapshot.toObject(Student.class);
                            listener.onUpdateComplete(true, updatedStudent);
                        } else {
                            // Handle the case where the document does not exist after update
                            listener.onUpdateComplete(false, null);
                        }
                    }).addOnFailureListener(e -> {
                        // Handle any errors that occurred while fetching the updated document
                        listener.onUpdateComplete(false, null);
                    });
                })
                .addOnFailureListener(e -> {
                    // Handle any errors that occurred during the update operation
                    listener.onUpdateComplete(false, null);
                });
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
            Map<String, Object> fields = new HashMap<>();
            fields.put("name", s.name);
            fields.put("grades", s.grades);
            fields.put("photo", s.photo);

            updateStudentFields(s.id, fields, listener::onSaveComplete);
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
