/*
 * QrGrades — track student grades/points, scan QR codes to award points, and optionally
 * expose the same data to a browser on the local network.
 * Copyright (C) 2026 André Furlan
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.dedira.qrnotas.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A flattened, self-contained bundle of everything about one student's enrollment, meant for
 * exporting (e.g. to CSV/PDF or the local-network web view) rather than for normal in-app use.
 * One row per student enrollment (a student with several enrollments yields several rows),
 * combining data that normally lives across several tables (Student, Enrollment, Discipline,
 * ClassGroup, Goal, PointsHistory) into one convenient object.
 */
public class StudentExportData {
    public String studentId;
    public String enrollmentId;
    public String studentName;
    public String photoPath; // File path to the student's saved photo on device storage; may be null if no photo was taken.
    public String disciplineId;
    public String disciplineName;
    public String classGroupId;
    public String classGroupName;
    public int points; // Accumulated points for this enrollment (NOT a 0-100 grade).
    public List<GoalProgress> goals = new ArrayList<>(); // This student's progress towards each Goal defined for the discipline.
    public List<PointsHistory> history = new ArrayList<>(); // Full point-change audit trail for this enrollment.
}
