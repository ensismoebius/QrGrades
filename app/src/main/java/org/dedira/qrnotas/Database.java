package org.dedira.qrnotas;

import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Map;

public class Database {

    static private FirebaseFirestore db = FirebaseFirestore.getInstance();

    static public void saveStudent(Student s, final OnSaveListener<Student> listener) {
        Task<DocumentReference> students = db.collection("students").add(s).addOnSuccessListener(docRef -> {
            s.id = docRef.getId();
            listener.onSaveComplete(true, s);
        }).addOnFailureListener(error -> {
            listener.onSaveComplete(false, s);
        });
    }

    static public void loadStudent(String id, final OnLoadListener<Student> listener) {
        DocumentReference studentRef = db.collection("students").document(id);

        if(studentRef == null){
            listener.onLoadComplete(false, null);
            return;
        }

        studentRef.get().addOnCompleteListener(task -> {
            if(task.getResult().toObject(Student.class) == null){
                listener.onLoadComplete(false, null);
                return;
            }

            Student s = task.getResult().toObject(Student.class);
            s.id = studentRef.getId();
            listener.onLoadComplete(true, s);
        }).addOnFailureListener(task -> listener.onLoadComplete(false, null));
    }

    static public void updateStudentFull(String id, Student updatedStudent, final OnUpdateListener<Student> listener) {
        DocumentReference studentRef = db.collection("students").document(id);

        studentRef.set(updatedStudent).addOnSuccessListener(aVoid -> {
            updatedStudent.id = id; // Update the id in the updatedStudent object
            listener.onUpdateComplete(true, updatedStudent);
        }).addOnFailureListener(e -> {
            listener.onUpdateComplete(false, null);
        });
    }


    static public void updateStudentFields(String id, Map<String, Object> updatedFields, final OnUpdateListener<Student> listener) {
        DocumentReference studentRef = db.collection("students").document(id);

        studentRef.update(updatedFields)
                .addOnSuccessListener(e -> listener.onUpdateComplete(true, null))
                .addOnFailureListener(e -> listener.onUpdateComplete(false, null));
    }


    interface OnUpdateListener<T> {
        void onUpdateComplete(boolean success, T object);
    }

    interface OnSaveListener<T> {
        void onSaveComplete(boolean success, T object);
    }

    interface OnLoadListener<T> {
        void onLoadComplete(boolean success, T object);
    }

}
