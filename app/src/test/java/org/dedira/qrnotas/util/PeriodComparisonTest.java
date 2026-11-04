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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.dedira.qrnotas.model.Period;
import org.dedira.qrnotas.model.PeriodSnapshot;
import org.dedira.qrnotas.model.StudentExportData;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PeriodComparisonTest {

    private static Period period(String id, String label, long closedAt, Long reopenedAt) {
        Period p = new Period();
        p.id = id;
        p.disciplineId = "d";
        p.label = label;
        p.closedAt = closedAt;
        p.reopenedAt = reopenedAt;
        return p;
    }

    private static PeriodSnapshot snap(String periodId, String studentId, String name, int points) {
        PeriodSnapshot s = new PeriodSnapshot();
        s.periodId = periodId;
        s.studentId = studentId;
        s.studentName = name;
        s.points = points;
        return s;
    }

    private static StudentExportData current(String studentId, String name, String classGroupId, int points) {
        StudentExportData d = new StudentExportData();
        d.studentId = studentId;
        d.studentName = name;
        d.classGroupId = classGroupId;
        d.classGroupName = "Turma " + classGroupId;
        d.points = points;
        return d;
    }

    private static void put(Map<String, List<PeriodSnapshot>> map, String periodId, PeriodSnapshot... snaps) {
        List<PeriodSnapshot> list = new ArrayList<>();
        for (PeriodSnapshot s : snaps) list.add(s);
        map.put(periodId, list);
    }

    @Test
    public void buildsColumnsInChronologicalOrderWithValuesDeltaAndStats() {
        List<Period> periods = new ArrayList<>();
        periods.add(period("p2", "Bimestre 2", 2000, null));
        periods.add(period("p1", "Bimestre 1", 1000, null));
        Map<String, List<PeriodSnapshot>> snaps = new HashMap<>();
        put(snaps, "p1", snap("p1", "ana", "Ana", 10), snap("p1", "bia", "Bia", 20));
        put(snaps, "p2", snap("p2", "ana", "Ana", 16), snap("p2", "bia", "Bia", 12));
        List<StudentExportData> now = new ArrayList<>();
        now.add(current("ana", "Ana", "A", 3));
        now.add(current("bia", "Bia", "A", 5));

        PeriodComparison result = PeriodComparison.build(periods, snaps, now, new HashMap<String, String>(), null);

        assertEquals(2, result.columns.size());
        assertEquals("Bimestre 1", result.columns.get(0).label);
        assertEquals("Bimestre 2", result.columns.get(1).label);
        assertEquals(2, result.rows.size());
        PeriodComparison.Row ana = result.rows.get(0);
        assertEquals("Ana", ana.name);
        assertEquals(Integer.valueOf(10), ana.values.get(result.columns.get(0).key));
        assertEquals(Integer.valueOf(16), ana.values.get(result.columns.get(1).key));
        assertEquals(Integer.valueOf(6), ana.delta);
        assertEquals(Integer.valueOf(3), ana.current);
        assertEquals(Integer.valueOf(-8), result.rows.get(1).delta);

        PeriodComparison.Stats first = result.columnStats.get(result.columns.get(0).key);
        assertEquals(2, first.count);
        assertEquals(15.0, first.average, 0.0001);
        assertEquals(10, first.min);
        assertEquals(20, first.max);
        assertEquals(4.0, result.currentStats.average, 0.0001);
    }

    @Test
    public void periodsSharingALabelFormOneColumn_blankLabelsStayApart() {
        List<Period> periods = new ArrayList<>();
        periods.add(period("p1a", "Bimestre 1", 1000, null));
        periods.add(period("p1b", " bimestre 1 ", 1100, null));
        periods.add(period("x", "", 1200, null));
        periods.add(period("y", null, 1300, null));
        Map<String, List<PeriodSnapshot>> snaps = new HashMap<>();
        put(snaps, "p1a", snap("p1a", "ana", "Ana", 10));
        put(snaps, "p1b", snap("p1b", "bia", "Bia", 7));

        PeriodComparison result = PeriodComparison.build(periods, snaps, new ArrayList<StudentExportData>(), null, null);

        assertEquals(3, result.columns.size());
        PeriodComparison.Column merged = result.columns.get(0);
        assertEquals(2, merged.periodIds.size());
        assertEquals(1100, merged.closedAt);
        assertEquals(Integer.valueOf(10), result.rows.get(0).values.get(merged.key));
        assertEquals(Integer.valueOf(7), result.rows.get(1).values.get(merged.key));
    }

    @Test
    public void reopenedPeriodsAreLeftOut() {
        List<Period> periods = new ArrayList<>();
        periods.add(period("p1", "Bimestre 1", 1000, 5000L));
        Map<String, List<PeriodSnapshot>> snaps = new HashMap<>();
        put(snaps, "p1", snap("p1", "ana", "Ana", 10));

        PeriodComparison result = PeriodComparison.build(periods, snaps, new ArrayList<StudentExportData>(), null, null);

        assertEquals(0, result.columns.size());
        assertEquals(0, result.rows.size());
    }

    @Test
    public void classGroupFilterKeepsOnlyStudentsCurrentlyInThatGroup_andNoDeltaWithOneClose() {
        List<Period> periods = new ArrayList<>();
        periods.add(period("p1", "Bimestre 1", 1000, null));
        Map<String, List<PeriodSnapshot>> snaps = new HashMap<>();
        put(snaps, "p1", snap("p1", "ana", "Ana", 10), snap("p1", "bia", "Bia", 20), snap("p1", "left", "Saiu", 9));
        List<StudentExportData> now = new ArrayList<>();
        now.add(current("ana", "Ana", "A", 1));
        now.add(current("bia", "Bia", "B", 2));

        PeriodComparison result = PeriodComparison.build(periods, snaps, now, new HashMap<String, String>(), "A");

        assertEquals(1, result.rows.size());
        assertEquals("Ana", result.rows.get(0).name);
        assertNull(result.rows.get(0).delta);

        PeriodComparison all = PeriodComparison.build(periods, snaps, now, new HashMap<String, String>(), null);
        // The student who left the discipline still shows up (past values only) when not filtering.
        assertEquals(3, all.rows.size());
        PeriodComparison.Row left = null;
        for (PeriodComparison.Row r : all.rows) if (r.studentId.equals("left")) left = r;
        assertNotNull(left);
        assertNull(left.current);
    }
}
