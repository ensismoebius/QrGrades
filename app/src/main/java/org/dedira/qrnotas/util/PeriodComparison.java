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

import org.dedira.qrnotas.model.Period;
import org.dedira.qrnotas.model.PeriodSnapshot;
import org.dedira.qrnotas.model.StudentExportData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How a discipline's students moved from one closed period to the next: a table of students by
 * period, with per-period class statistics. Pure data in and out, like {@link StudentReportBuilder}.
 *
 * <p>Periods that share a label are one column — closing "Bimestre 1" turma by turma produces
 * several period records that the teacher thinks of as a single bimester. A period with no label
 * is its own column.
 */
public final class PeriodComparison {

    public static final class Column {
        public String key;
        public String label;
        public long closedAt;
        public List<String> periodIds = new ArrayList<>();
    }

    public static final class Row {
        public String studentId;
        public String name;
        // The student's current class group, or null if they are no longer enrolled in the discipline.
        public String classGroupId;
        public String classGroupName = "";
        // Points at the close, by column key; a student with no record in a period has no entry.
        public Map<String, Integer> values = new LinkedHashMap<>();
        // Points in the period still open, or null if not currently enrolled.
        public Integer current;
        // Last closed period minus the one before it, or null with fewer than two closed periods.
        public Integer delta;
    }

    public static final class Stats {
        public int count;
        public int total;
        public double average;
        public int min;
        public int max;
    }

    public List<Column> columns = new ArrayList<>();
    public List<Row> rows = new ArrayList<>();
    // Keyed by column key.
    public Map<String, Stats> columnStats = new LinkedHashMap<>();
    public Stats currentStats = new Stats();

    private PeriodComparison() {
    }

    /**
     * @param periods            the discipline's periods (any order; reopened ones are ignored)
     * @param snapshotsByPeriod  each period's snapshots, with {@code studentName} filled in
     * @param current            the discipline's enrollments right now
     * @param classGroupNames    class group names by id, for students who have since moved
     * @param classGroupFilter   if non-null, keep only students currently in that class group
     */
    public static PeriodComparison build(List<Period> periods,
                                         Map<String, List<PeriodSnapshot>> snapshotsByPeriod,
                                         List<StudentExportData> current,
                                         Map<String, String> classGroupNames,
                                         String classGroupFilter) {
        PeriodComparison result = new PeriodComparison();

        List<Period> standing = new ArrayList<>();
        for (Period p : periods) if (!p.isReopened()) standing.add(p);
        Collections.sort(standing, new Comparator<Period>() {
            @Override
            public int compare(Period a, Period b) {
                return Long.compare(a.closedAt, b.closedAt);
            }
        });

        // Columns, in order of the first close that belongs to each; each column keeps its latest close time and label.
        Map<String, Column> columnsByKey = new LinkedHashMap<>();
        Map<String, String> columnKeyByPeriod = new HashMap<>();
        for (Period p : standing) {
            String label = p.label == null ? "" : p.label.trim();
            String key = label.isEmpty() ? "id:" + p.id : "label:" + label.toLowerCase(Locale.ROOT);
            Column column = columnsByKey.get(key);
            if (column == null) {
                column = new Column();
                column.key = key;
                columnsByKey.put(key, column);
            }
            column.label = label;
            column.closedAt = p.closedAt;
            column.periodIds.add(p.id);
            columnKeyByPeriod.put(p.id, key);
        }
        result.columns.addAll(columnsByKey.values());

        Map<String, Row> rowsByStudent = new LinkedHashMap<>();
        for (Period p : standing) {
            List<PeriodSnapshot> snapshots = snapshotsByPeriod.get(p.id);
            if (snapshots == null) continue;
            for (PeriodSnapshot s : snapshots) {
                Row row = rowsByStudent.get(s.studentId);
                if (row == null) {
                    row = new Row();
                    row.studentId = s.studentId;
                    rowsByStudent.put(s.studentId, row);
                }
                if (row.name == null || row.name.isEmpty()) row.name = s.studentName == null ? "" : s.studentName;
                // Snapshots arrive oldest close first, so a later one within the same column overwrites an earlier one.
                row.values.put(columnKeyByPeriod.get(p.id), s.points);
            }
        }
        for (StudentExportData e : current) {
            Row row = rowsByStudent.get(e.studentId);
            if (row == null) {
                row = new Row();
                row.studentId = e.studentId;
                rowsByStudent.put(e.studentId, row);
            }
            row.name = e.studentName == null ? "" : e.studentName;
            row.classGroupId = e.classGroupId;
            row.classGroupName = e.classGroupName == null ? "" : e.classGroupName;
            row.current = e.points;
        }

        for (Row row : rowsByStudent.values()) {
            if (classGroupFilter != null && !classGroupFilter.equals(row.classGroupId)) continue;
            if (row.classGroupId != null && row.classGroupName.isEmpty() && classGroupNames != null) {
                String name = classGroupNames.get(row.classGroupId);
                if (name != null) row.classGroupName = name;
            }

            Integer last = null;
            Integer previous = null;
            for (Column c : result.columns) {
                Integer v = row.values.get(c.key);
                if (v == null) continue;
                previous = last;
                last = v;
            }
            row.delta = (last != null && previous != null) ? last - previous : null;
            result.rows.add(row);
        }

        Collections.sort(result.rows, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });

        for (Column c : result.columns) {
            List<Integer> values = new ArrayList<>();
            for (Row row : result.rows) {
                Integer v = row.values.get(c.key);
                if (v != null) values.add(v);
            }
            result.columnStats.put(c.key, statsOf(values));
        }
        List<Integer> currentValues = new ArrayList<>();
        for (Row row : result.rows) if (row.current != null) currentValues.add(row.current);
        result.currentStats = statsOf(currentValues);
        return result;
    }

    private static Stats statsOf(List<Integer> values) {
        Stats stats = new Stats();
        stats.count = values.size();
        if (values.isEmpty()) return stats;
        stats.min = Integer.MAX_VALUE;
        stats.max = Integer.MIN_VALUE;
        for (int v : values) {
            stats.total += v;
            stats.min = Math.min(stats.min, v);
            stats.max = Math.max(stats.max, v);
        }
        stats.average = (double) stats.total / values.size();
        return stats;
    }
}
