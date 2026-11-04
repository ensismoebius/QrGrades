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

package org.dedira.qrnotas.services;

import org.dedira.qrnotas.model.Attendance;
import org.dedira.qrnotas.model.AttendanceStats;
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.Period;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.util.AttendanceReports;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.DatabaseSync;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;

/**
 * Roll calls over HTTP: viewing and editing one class day, listing a class's days, per-student
 * totals for a period, and the "too many absences" alert list. A day is identified by class group
 * and a local "yyyy-MM-dd" string; see {@link Attendance} for how records and periods relate.
 */
final class AttendanceApi {

    private final Database database;

    AttendanceApi(Database database) {
        this.database = database;
    }

    /** Routes /api/attendance[/...]; {@code parts[0]} is "attendance". */
    Response handle(IHTTPSession session, Method method, String[] parts) throws Exception {
        if (parts.length == 1) {
            if (method == Method.GET) return handleDay(session);
            if (method == Method.PUT) return handleMark(session);
            if (method == Method.DELETE) return handleDelete(session);
        } else if (parts.length == 2) {
            if (method == Method.POST && parts[1].equals("start")) return handleStart(session);
            if (method == Method.GET && parts[1].equals("days")) return handleDays(session);
            if (method == Method.GET && parts[1].equals("summary")) return handleSummary(session);
            if (method == Method.GET && parts[1].equals("alerts")) return handleAlerts(session);
        }
        return WebServer.notFound();
    }

    /* ------------------------------ single day ------------------------------ */

    private ClassGroup loadGroup(String id) {
        return (id == null || id.isEmpty()) ? null : DatabaseSync.loadClassGroup(database, id);
    }

    private static Response badDay() {
        return WebServer.jsonError(Response.Status.BAD_REQUEST, "data inválida");
    }

    private static Response classClosed() {
        return WebServer.jsonError(Response.Status.CONFLICT, "CLASS_CLOSED");
    }

    /** The class group's day as the browser's roll-call table needs it: every enrolled student with their status (or null if not recorded yet). */
    private JSONObject dayJson(ClassGroup group, String day, List<Attendance> rows) throws JSONException {
        ArrayList<Student> students = DatabaseSync.loadStudentsForClassGroup(database, group.id);
        Map<String, Attendance> byStudent = new HashMap<>();
        for (Attendance a : rows) byStudent.put(a.studentId, a);

        JSONArray list = new JSONArray();
        int present = 0, absent = 0, excused = 0, unmarked = 0;
        if (students != null) {
            for (Student s : students) {
                Attendance a = byStudent.get(s.id);
                JSONObject obj = new JSONObject();
                obj.put("studentId", s.id);
                obj.put("name", s.name);
                obj.put("hasPhoto", s.photoPath != null);
                obj.put("status", a == null ? JSONObject.NULL : a.status);
                list.put(obj);
                if (a == null) unmarked++;
                else if (a.status == Attendance.STATUS_PRESENT) present++;
                else if (a.status == Attendance.STATUS_EXCUSED) excused++;
                else absent++;
            }
        }

        JSONObject counts = new JSONObject();
        counts.put("present", present);
        counts.put("absent", absent);
        counts.put("excused", excused);
        counts.put("unmarked", unmarked);

        JSONObject out = new JSONObject();
        out.put("classGroupId", group.id);
        out.put("classGroup", group.name);
        out.put("day", day);
        out.put("closed", group.isClosed());
        out.put("started", !rows.isEmpty());
        out.put("students", list);
        out.put("counts", counts);
        return out;
    }

    private Response dayResponse(ClassGroup group, String day) throws JSONException {
        ArrayList<Attendance> rows = DatabaseSync.loadAttendanceForDay(database, group.id, day);
        return WebServer.jsonResponse(Response.Status.OK, dayJson(group, day, rows == null ? new ArrayList<Attendance>() : rows));
    }

    /** GET /api/attendance?classGroupId=&day= */
    private Response handleDay(IHTTPSession session) throws JSONException {
        ClassGroup group = loadGroup(WebServer.queryParam(session, "classGroupId"));
        if (group == null) return WebServer.notFound();
        String day = WebServer.queryParam(session, "day");
        if (day == null) day = Attendance.today();
        if (!Attendance.isValidDay(day)) return badDay();
        return dayResponse(group, day);
    }

    /** POST /api/attendance/start — opens the day's roll call: every enrolled student starts out absent. */
    private Response handleStart(IHTTPSession session) throws Exception {
        JSONObject body = WebServer.readJsonBody(session);
        ClassGroup group = loadGroup(body.optString("classGroupId", ""));
        if (group == null) return WebServer.notFound();
        String day = body.optString("day", Attendance.today());
        if (!Attendance.isValidDay(day)) return badDay();
        if (group.isClosed()) return classClosed();

        if (DatabaseSync.startAttendance(database, group.id, day) == null) {
            return WebServer.jsonError(Response.Status.INTERNAL_ERROR, "falha ao abrir a chamada");
        }
        return dayResponse(group, day);
    }

    /** PUT /api/attendance — sets the status of some students on one day: {classGroupId, day, marks:[{studentId, status}]}. */
    private Response handleMark(IHTTPSession session) throws Exception {
        JSONObject body = WebServer.readJsonBody(session);
        ClassGroup group = loadGroup(body.optString("classGroupId", ""));
        if (group == null) return WebServer.notFound();
        String day = body.optString("day", "");
        if (!Attendance.isValidDay(day)) return badDay();
        if (group.isClosed()) return classClosed();

        JSONArray array = body.optJSONArray("marks");
        if (array == null || array.length() == 0) {
            return WebServer.jsonError(Response.Status.BAD_REQUEST, "nenhuma marcação informada");
        }

        Set<String> enrolled = new HashSet<>();
        ArrayList<Student> students = DatabaseSync.loadStudentsForClassGroup(database, group.id);
        if (students != null) for (Student s : students) enrolled.add(s.id);

        List<Attendance> marks = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject m = array.optJSONObject(i);
            if (m == null) return WebServer.jsonError(Response.Status.BAD_REQUEST, "marcação inválida");
            Attendance mark = new Attendance();
            mark.studentId = m.optString("studentId", "");
            mark.status = m.optInt("status", -1);
            if (!enrolled.contains(mark.studentId)) {
                return WebServer.jsonError(Response.Status.BAD_REQUEST, "o aluno não pertence a esta turma");
            }
            if (!Attendance.isValidStatus(mark.status)) {
                return WebServer.jsonError(Response.Status.BAD_REQUEST, "situação de frequência inválida");
            }
            marks.add(mark);
        }

        if (DatabaseSync.setAttendance(database, group.id, day, marks) == null) {
            return WebServer.jsonError(Response.Status.INTERNAL_ERROR, "falha ao salvar a chamada");
        }
        return dayResponse(group, day);
    }

    /** DELETE /api/attendance?classGroupId=&day= — discards a whole day's roll call. */
    private Response handleDelete(IHTTPSession session) throws JSONException {
        ClassGroup group = loadGroup(WebServer.queryParam(session, "classGroupId"));
        if (group == null) return WebServer.notFound();
        String day = WebServer.queryParam(session, "day");
        if (!Attendance.isValidDay(day)) return badDay();
        if (group.isClosed()) return classClosed();
        return WebServer.jsonResponse(Response.Status.OK,
                new JSONObject().put("removed", DatabaseSync.deleteAttendanceDay(database, group.id, day)));
    }

    /* ------------------------------- class days ------------------------------ */

    /** GET /api/attendance/days?classGroupId= — every recorded day of the class, newest first, with its tallies. */
    private Response handleDays(IHTTPSession session) throws JSONException {
        ClassGroup group = loadGroup(WebServer.queryParam(session, "classGroupId"));
        if (group == null) return WebServer.notFound();

        JSONArray days = new JSONArray();
        for (AttendanceReports.Day d : AttendanceReports.days(database, group.id)) {
            days.put(new JSONObject()
                    .put("day", d.day)
                    .put("present", d.stats.present)
                    .put("absent", d.stats.absent)
                    .put("excused", d.stats.excused)
                    .put("total", d.stats.total()));
        }
        return WebServer.jsonResponse(Response.Status.OK, new JSONObject().put("classGroupId", group.id).put("days", days));
    }

    /* --------------------------- per-student totals --------------------------- */

    private JSONObject statsJson(Student s, AttendanceStats stats) throws JSONException {
        return new JSONObject()
                .put("studentId", s.id)
                .put("name", s.name)
                .put("present", stats.present)
                .put("absent", stats.absent)
                .put("excused", stats.excused)
                .put("total", stats.total())
                .put("presentPct", Math.round(stats.presentPercent() * 10) / 10.0)
                .put("absentPct", Math.round(stats.absentPercent() * 10) / 10.0);
    }

    /** GET /api/attendance/summary?classGroupId=&period=(current|periodId) */
    private Response handleSummary(IHTTPSession session) throws JSONException {
        ClassGroup group = loadGroup(WebServer.queryParam(session, "classGroupId"));
        if (group == null) return WebServer.notFound();

        AttendanceReports.Summary summary = AttendanceReports.summary(database, group, WebServer.queryParam(session, "period"));
        if (summary == null) return WebServer.notFound();

        JSONArray students = new JSONArray();
        for (AttendanceReports.StudentTotals t : summary.students) students.put(statsJson(t.student, t.stats));

        JSONArray periods = new JSONArray();
        for (Period p : summary.periods) {
            periods.put(new JSONObject()
                    .put("id", p.id)
                    .put("label", p.label == null ? "" : p.label)
                    .put("closedAt", p.closedAt));
        }

        JSONObject out = new JSONObject();
        out.put("classGroupId", group.id);
        out.put("classGroup", group.name);
        out.put("period", summary.period);
        out.put("periods", periods);
        out.put("days", summary.days);
        out.put("students", students);
        return WebServer.jsonResponse(Response.Status.OK, out);
    }

    /* ----------------------------------- alerts ----------------------------------- */

    private static double parseDouble(String value, double fallback) {
        if (value == null) return fallback;
        try {
            double parsed = Double.parseDouble(value);
            return (parsed < 0 || parsed > 100 || Double.isNaN(parsed)) ? fallback : parsed;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            return parsed < 1 ? fallback : parsed;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * GET /api/attendance/alerts?limit=25&minDays=3 — students of every still-active class group
     * whose absences in the current period exceed {@code limit} percent, worst first. Excused
     * absences count, as they do toward a minimum-attendance rule.
     */
    private Response handleAlerts(IHTTPSession session) throws JSONException {
        double limit = parseDouble(WebServer.queryParam(session, "limit"), AttendanceReports.DEFAULT_ABSENCE_LIMIT_PERCENT);
        int minDays = parseInt(WebServer.queryParam(session, "minDays"), AttendanceReports.DEFAULT_MIN_DAYS);

        AttendanceReports.Alerts found = AttendanceReports.alerts(database, limit, minDays);
        JSONArray array = new JSONArray();
        for (AttendanceReports.Alert a : found.alerts) {
            JSONObject alert = statsJson(a.student, a.stats);
            alert.put("classGroupId", a.group.id);
            alert.put("classGroup", a.group.name);
            alert.put("disciplineId", a.group.disciplineId);
            alert.put("discipline", a.disciplineName);
            array.put(alert);
        }

        JSONObject out = new JSONObject();
        out.put("limit", limit);
        out.put("minDays", minDays);
        out.put("classGroupsChecked", found.classGroupsChecked);
        out.put("alerts", array);
        return WebServer.jsonResponse(Response.Status.OK, out);
    }
}
