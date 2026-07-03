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
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.StudentExportData;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@link Exporter}, which turns a list of {@link StudentExportData} into JSON
 * (for backups/re-import) or Markdown (for human-readable sharing), and writes either format
 * to a file. Covers the JSON structure produced by {@link Exporter#buildJsonArray(List)}, that
 * {@link Exporter#exportJson} produces a file {@link Importer} can read back (round trip), and
 * Markdown-specific concerns like escaping the "|" table-delimiter character.
 */
@RunWith(RobolectricTestRunner.class)
public class ExporterTest {

    // Robolectric supplies a fake-but-functional Android Context so Exporter's file-writing
    // methods (which need a directory to write into) can run on the JVM without a device.
    private final Context context = RuntimeEnvironment.getApplication();

    // Builds one fully-populated StudentExportData fixture (student info + two goals + one
    // history entry) reused by every test below, so each test only needs to assert the slice
    // of output it cares about instead of re-building this data each time.
    private static StudentExportData sampleStudent() {
        StudentExportData s = new StudentExportData();
        s.studentId = "s1";
        s.enrollmentId = "e1";
        s.studentName = "André";
        s.photoPath = null;
        s.disciplineId = "d1";
        s.disciplineName = "BDI";
        s.classGroupId = "c1";
        s.classGroupName = "A";
        s.points = 22;
        // Two goals with the same current point total (22) but different targets: one already
        // met (target 10, so achieved/remaining=0) and one not yet met (target 30, remaining=8).
        // This lets buildJsonArray_includesGoalsAndAchievedFlag exercise both outcomes at once.
        s.goals.add(new GoalProgress("R", 10, 22));
        s.goals.add(new GoalProgress("MB", 30, 22));
        PointsHistory h = new PointsHistory();
        h.id = "h1";
        h.pointsDelta = 5;
        // Contains a literal "|" character on purpose, to verify Markdown table escaping later.
        h.note = "helped | classmate";
        h.createdAt = 1700000000000L;
        s.history.add(h);
        return s;
    }

    // Verifies that the top-level fields of a student (id, name, photo path, discipline,
    // class group, points) all appear correctly in the JSON object built for that student.
    @Test
    public void buildJsonArray_containsAllTopLevelFields() throws Exception {
        JSONArray array = Exporter.buildJsonArray(Collections.singletonList(sampleStudent()));
        assertEquals(1, array.length());

        JSONObject obj = array.getJSONObject(0);
        assertEquals("s1", obj.getString("studentId"));
        assertEquals("André", obj.getString("name"));
        // A null photoPath in the Java object is exported as an empty string in the JSON.
        assertEquals("", obj.getString("photoPath"));
        assertEquals("BDI", obj.getString("discipline"));
        assertEquals("A", obj.getString("classGroup"));
        assertEquals(22, obj.getInt("points"));
    }

    // Verifies that both goals are exported and that each one's "achieved"/"remaining" values
    // reflect its own target: the first goal (target 10, already met) is achieved with 0
    // remaining, while the second (target 30, not yet met) is not achieved with 8 remaining.
    @Test
    public void buildJsonArray_includesGoalsAndAchievedFlag() throws Exception {
        JSONArray array = Exporter.buildJsonArray(Collections.singletonList(sampleStudent()));
        JSONArray goals = array.getJSONObject(0).getJSONArray("goals");

        assertEquals(2, goals.length());
        assertTrue(goals.getJSONObject(0).getBoolean("achieved"));
        assertEquals(0, goals.getJSONObject(0).getInt("remaining"));
        assertTrue(!goals.getJSONObject(1).getBoolean("achieved"));
        assertEquals(8, goals.getJSONObject(1).getInt("remaining"));
    }

    // Verifies that the point-history entry is exported with its point delta and note text.
    @Test
    public void buildJsonArray_includesHistory() throws Exception {
        JSONArray array = Exporter.buildJsonArray(Collections.singletonList(sampleStudent()));
        JSONArray history = array.getJSONObject(0).getJSONArray("history");

        assertEquals(1, history.length());
        assertEquals(5, history.getJSONObject(0).getInt("points"));
        assertEquals("helped | classmate", history.getJSONObject(0).getString("note"));
    }

    // Verifies that exporting an empty list of students produces an empty JSON array, not an
    // error or a null result.
    @Test
    public void buildJsonArray_emptyListYieldsEmptyArray() throws Exception {
        assertEquals(0, Exporter.buildJsonArray(new ArrayList<>()).length());
    }

    // Verifies the full JSON export round trip: a file written by exportJson can be read back
    // by Importer.parseJsonArray and yields a student with the same name and points as the
    // original, proving the two formats stay in sync with each other.
    @Test
    public void exportJson_writesFileReadableByImporter() throws Exception {
        File file = Exporter.exportJson(context, Collections.singletonList(sampleStudent()));
        assertTrue(file.exists());

        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) content.append(line);
        }

        List<StudentExportData> reimported = Importer.parseJsonArray(content.toString());
        assertEquals(1, reimported.size());
        assertEquals("André", reimported.get(0).studentName);
        assertEquals(22, reimported.get(0).points);
    }

    // Verifies that the Markdown export contains the student's name and points, and that a
    // literal "|" character inside a note is escaped (as "\|") so it doesn't get misread as a
    // Markdown table column separator.
    @Test
    public void exportMarkdown_escapesPipeCharactersAndIncludesPoints() throws Exception {
        File file = Exporter.exportMarkdown(context, Collections.singletonList(sampleStudent()));
        assertTrue(file.exists());

        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) content.append(line).append('\n');
        }

        String md = content.toString();
        assertTrue(md.contains("André"));
        assertTrue(md.contains("22"));
        assertTrue(md.contains("helped \\| classmate"));
    }

    // Verifies that when exporting more than one student to Markdown, the output file gets a
    // generic name (rather than one based on a single student's name, which wouldn't make
    // sense once there are multiple students in the same file).
    @Test
    public void exportMarkdown_multipleStudentsUseGenericFileName() throws Exception {
        StudentExportData other = sampleStudent();
        other.studentId = "s2";
        other.studentName = "Beatriz";
        File file = Exporter.exportMarkdown(context, java.util.Arrays.asList(sampleStudent(), other));
        assertTrue(file.getName().startsWith("qrgrades_export"));
    }

    // exportPdf() is intentionally not unit-tested: Robolectric's PdfDocument shadow closes its
    // native document between startPage() calls (IllegalStateException: document is closed!),
    // which doesn't reproduce on a real device — this needs an instrumented test instead.
}
