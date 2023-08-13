package org.dedira.qrnotas;

import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.ArrayList;
import java.util.List;

public class Database {

    private DatabaseReference databaseReference;
    private FirebaseAuth firebaseAuth;



    public Database() {
        // Initialize Firebase
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        databaseReference = database.getReference("students");

        // Initialize Firebase Authentication
        firebaseAuth = FirebaseAuth.getInstance();
    }

    public void insertStudent(Student student) {
        // Generate a new unique key for the student
        String studentKey = databaseReference.push().getKey();

        // Insert the student into the database using the generated key
        databaseReference.child(studentKey).setValue(student);
    }

    public void updateStudent(String studentKey, Student updatedStudent) {
        // Update the student with the given key
        databaseReference.child(studentKey).setValue(updatedStudent);
    }

    public void deleteStudent(String studentKey) {
        // Delete the student with the given key
        databaseReference.child(studentKey).removeValue();
    }


    public List<Student> getStudentsWithKey(String key) {
        Task<DataSnapshot> dataSnapshot = databaseReference.child(key).get();
        List<Student> students = new ArrayList<>();

        for (DataSnapshot snapshot : dataSnapshot.getResult().getChildren()) {
            Student student = snapshot.getValue(Student.class);
            students.add(student);
        }

        return students;
    }


    public void signIn(String email, String password) {
        firebaseAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = firebaseAuth.getCurrentUser();
                    }
                });
    }

    public void signOut() {
        firebaseAuth.signOut();
    }
}
