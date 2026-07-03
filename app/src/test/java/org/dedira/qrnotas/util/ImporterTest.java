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

package org.dedira.qrnotas.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.StudentExportData;
import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/**
 * Unit tests for {@link Importer#parseJsonArray(String)}, which reads back the JSON export
 * format produced by {@link Exporter} (see {@code ExporterTest}) and reconstructs a list of
 * {@link StudentExportData}, including nested goals ({@link GoalProgress}) and point history
 * ({@link PointsHistory}). Covers full round-trip field reading, legacy field fallbacks, missing
 * optional fields, and malformed input.
 *
 * <p>Robolectric only because org.json is stubbed out by default in plain host JUnit tests.
 */
@RunWith(RobolectricTestRunner.class)
public class ImporterTest {

    // A representative JSON document mirroring the export format: one student with all fields
    // populated, one goal, and one history entry. Reused across several tests below so each
    // test can focus on asserting a different slice of the parsed result.
    private static final String SAMPLE = "["
            + "{\"studentId\":\"s1\",\"name\":\"André\",\"photoPath\":\"\",\"disciplineId\":\"d1\","
            + "\"discipline\":\"BDI\",\"classGroupId\":\"c1\",\"classGroup\":\"A\",\"points\":22,"
            + "\"goals\":[{\"name\":\"R\",\"target\":10,\"achieved\":true,\"remaining\":0}],"
            + "\"history\":[{\"id\":\"h1\",\"points\":5,\"note\":\"helped classmate\",\"timestamp\":1000}]"
            + "}]";

    // Verifies that all top-level student fields are read correctly, and specifically that an
    // empty "photoPath" string in the JSON is converted to a null field (there is no photo).
    @Test
    public void parseJsonArray_readsAllFields() throws JSONException {
        List<StudentExportData> result = Importer.parseJsonArray(SAMPLE);

        assertEquals(1, result.size());
        StudentExportData s = result.get(0);
        assertEquals("s1", s.studentId);
        assertEquals("André", s.studentName);
        assertNull(s.photoPath);
        assertEquals("d1", s.disciplineId);
        assertEquals("BDI", s.disciplineName);
        assertEquals("c1", s.classGroupId);
        assertEquals("A", s.classGroupName);
        assertEquals(22, s.points);
    }

    // Verifies that the nested "goals" array is parsed into GoalProgress objects with the
    // expected name and target points.
    @Test
    public void parseJsonArray_readsGoals() throws JSONException {
        StudentExportData s = Importer.parseJsonArray(SAMPLE).get(0);
        assertEquals(1, s.goals.size());
        GoalProgress g = s.goals.get(0);
        assertEquals("R", g.goalName);
        assertEquals(10, g.targetPoints);
    }

    // Verifies that the nested "history" array is parsed into PointsHistory objects, with the
    // JSON key "points" mapped to the pointsDelta field and "timestamp" mapped to createdAt.
    @Test
    public void parseJsonArray_readsHistory() throws JSONException {
        StudentExportData s = Importer.parseJsonArray(SAMPLE).get(0);
        assertEquals(1, s.history.size());
        PointsHistory h = s.history.get(0);
        assertEquals("h1", h.id);
        assertEquals(5, h.pointsDelta);
        assertEquals("helped classmate", h.note);
        assertEquals(1000L, h.createdAt);
    }

    // Verifies backward compatibility with an older export format: if "studentId" is absent
    // but a legacy "id" field is present, that value is used as the student id.
    @Test
    public void parseJsonArray_fallsBackFromStudentIdToId() throws JSONException {
        String json = "[{\"id\":\"legacy-id\",\"name\":\"Beatriz\"}]";
        StudentExportData s = Importer.parseJsonArray(json).get(0);
        assertEquals("legacy-id", s.studentId);
    }

    // Verifies that an explicit empty-string "photoPath" is normalized to null, consistent
    // with parseJsonArray_readsAllFields above but isolated to just this one field.
    @Test
    public void parseJsonArray_emptyPhotoPathBecomesNull() throws JSONException {
        String json = "[{\"studentId\":\"s1\",\"name\":\"André\",\"photoPath\":\"\"}]";
        StudentExportData s = Importer.parseJsonArray(json).get(0);
        assertNull(s.photoPath);
    }

    // Verifies that when optional fields (discipline, points, goals, history) are absent from
    // the JSON entirely, sensible defaults are used: empty string, zero, and empty lists —
    // rather than throwing or leaving fields uninitialized/null in a way that breaks callers.
    @Test
    public void parseJsonArray_missingOptionalFieldsUseDefaults() throws JSONException {
        String json = "[{\"name\":\"André\"}]";
        StudentExportData s = Importer.parseJsonArray(json).get(0);
        assertEquals("", s.disciplineName);
        assertEquals(0, s.points);
        assertTrue(s.goals.isEmpty());
        assertTrue(s.history.isEmpty());
    }

    // Verifies that an empty JSON array ("[]") parses to an empty list, not null or an error.
    @Test
    public void parseJsonArray_emptyArrayReturnsEmptyList() throws JSONException {
        assertTrue(Importer.parseJsonArray("[]").isEmpty());
    }

    // Verifies that text which isn't valid JSON at all fails with JSONException, rather than
    // being silently ignored or causing some other unchecked failure.
    @Test(expected = JSONException.class)
    public void parseJsonArray_malformedJsonThrows() throws JSONException {
        Importer.parseJsonArray("not json");
    }

    // Verifies that multiple student entries in the array are all parsed, in order, as
    // distinct StudentExportData objects (not merged or overwritten).
    @Test
    public void parseJsonArray_multipleStudents() throws JSONException {
        String json = "[{\"name\":\"André\"},{\"name\":\"Beatriz\"}]";
        List<StudentExportData> result = Importer.parseJsonArray(json);
        assertEquals(2, result.size());
        assertFalse(result.get(0).studentName.equals(result.get(1).studentName));
    }
}
