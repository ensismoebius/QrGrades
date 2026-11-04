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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything known about one student, split by period: one {@link Bucket} per enrollment for the
 * period still open, and one per (closed period, enrollment) for each period already closed.
 * Built by {@link org.dedira.qrnotas.util.StudentReportBuilder} and shared by the web history
 * screen and the PDF report card, so both always show the same numbers.
 */
public class StudentReport {
    public Student student;
    public List<Bucket> current = new ArrayList<>();
    // Oldest close first.
    public List<Bucket> closed = new ArrayList<>();

    /** One enrollment's slice of the timeline: its points, goals, point history and attendance for a single period. */
    public static class Bucket {
        public String enrollmentId;
        public String classGroupId;
        public String classGroup = "";
        public boolean classGroupClosed;
        public String disciplineId = "";
        public String discipline = "";
        public int points;
        public List<GoalProgress> goals = new ArrayList<>();
        // Newest first. Includes cancelling entries, which net against the entries they cancel.
        public List<PointsHistory> history = new ArrayList<>();
        public AttendanceStats attendance = new AttendanceStats();
        // Newest first.
        public List<Attendance> attendanceDays = new ArrayList<>();

        // Closed periods only.
        public boolean closed;
        public String periodId;
        public String label = "";
        public long closedAt;
        public boolean classClose;

        /** Net points of one category in this bucket. */
        public static class TagTotal {
            // Empty for entries that were given without a category.
            public final String tag;
            public final int points;

            TagTotal(String tag, int points) {
                this.tag = tag;
                this.points = points;
            }
        }

        /**
         * Net points per category, largest first, categories that net to zero left out. A
         * cancelling entry carries its original's category with the opposite sign, so points that
         * were cancelled drop out of the breakdown on their own.
         */
        public List<TagTotal> pointsByTag() {
            Map<String, Integer> totals = new LinkedHashMap<>();
            for (PointsHistory h : history) {
                String tag = h.tag == null ? "" : h.tag;
                Integer sum = totals.get(tag);
                totals.put(tag, (sum == null ? 0 : sum) + h.pointsDelta);
            }
            List<TagTotal> list = new ArrayList<>();
            for (Map.Entry<String, Integer> e : totals.entrySet()) {
                if (e.getValue() != 0) list.add(new TagTotal(e.getKey(), e.getValue()));
            }
            Collections.sort(list, new Comparator<TagTotal>() {
                @Override
                public int compare(TagTotal a, TagTotal b) {
                    return Integer.compare(b.points, a.points);
                }
            });
            return list;
        }

        /** An entry can still be cancelled while it belongs to the open period and hasn't been cancelled or isn't itself a cancellation. */
        public boolean canRevert(PointsHistory entry) {
            return !closed && !entry.reverted && entry.revertsId == null;
        }
    }
}
