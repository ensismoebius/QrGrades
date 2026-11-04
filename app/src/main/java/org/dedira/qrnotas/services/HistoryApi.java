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

import android.content.Context;

import org.dedira.qrnotas.model.Attendance;
import org.dedira.qrnotas.model.AttendanceStats;
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.StudentReport;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.Exporter;
import org.dedira.qrnotas.util.PeriodComparison;
import org.dedira.qrnotas.util.ReportLoader;
import org.dedira.qrnotas.util.StudentReportBuilder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

/**
 * Read-side reports: one student's history split by period (and the same data as a PDF report
 * card), and the period-over-period comparison for a discipline. The splitting and aggregation
 * rules live in {@link StudentReportBuilder} and {@link PeriodComparison}; this class only loads
 * the rows they need and turns the results into JSON.
 */
final class HistoryApi {

    private static final Locale PT_BR = new Locale("pt", "BR");

    private final Context context;
    private final Database database;

    HistoryApi(Context context, Database database) {
        this.context = context;
        this.database = database;
    }

    /* --------------------------- student history & report --------------------------- */

    /** Loads everything about one student and splits it into periods; null if there is no such student. */
    private StudentReport buildReport(String studentId) {
        return ReportLoader.studentReport(database, studentId);
    }

    private static JSONObject statsJson(AttendanceStats stats) throws JSONException {
        return new JSONObject()
                .put("present", stats.present)
                .put("absent", stats.absent)
                .put("excused", stats.excused)
                .put("total", stats.total())
                .put("presentPct", Math.round(stats.presentPercent() * 10) / 10.0)
                .put("absentPct", Math.round(stats.absentPercent() * 10) / 10.0);
    }

    private static JSONObject bucketJson(StudentReport.Bucket b) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("enrollmentId", b.enrollmentId);
        obj.put("classGroupId", b.classGroupId == null ? "" : b.classGroupId);
        obj.put("classGroup", b.classGroup);
        obj.put("classGroupClosed", b.classGroupClosed);
        obj.put("disciplineId", b.disciplineId);
        obj.put("discipline", b.discipline);
        obj.put("points", b.points);
        obj.put("closed", b.closed);
        if (b.closed) {
            obj.put("periodId", b.periodId);
            obj.put("label", b.label);
            obj.put("closedAt", b.closedAt);
            obj.put("classClose", b.classClose);
        }

        JSONArray goals = new JSONArray();
        for (GoalProgress g : b.goals) {
            JSONObject goal = new JSONObject()
                    .put("name", g.goalName)
                    .put("target", g.targetPoints)
                    // The points inside the goal's window: not always the block's total.
                    .put("current", g.currentPoints)
                    .put("achieved", g.achieved)
                    .put("remaining", g.remaining)
                    .put("state", g.stateName())
                    .put("daysLeft", g.daysLeft);
            if (g.hasDates()) goal.put("startDate", g.startDate).put("endDate", g.endDate);
            goals.put(goal);
        }
        obj.put("goals", goals);

        JSONArray history = new JSONArray();
        for (PointsHistory h : b.history) {
            JSONObject entry = new JSONObject()
                    .put("id", h.id)
                    .put("pointsDelta", h.pointsDelta)
                    .put("note", h.note == null ? "" : h.note)
                    .put("tag", h.tag == null ? "" : h.tag)
                    .put("createdAt", h.createdAt)
                    .put("isReversal", h.revertsId != null)
                    .put("reverted", h.reverted)
                    .put("canRevert", b.canRevert(h));
            history.put(entry);
        }
        obj.put("history", history);

        JSONArray byTag = new JSONArray();
        for (StudentReport.Bucket.TagTotal t : b.pointsByTag()) {
            byTag.put(new JSONObject().put("tag", t.tag).put("points", t.points));
        }
        obj.put("byTag", byTag);

        obj.put("attendance", statsJson(b.attendance));
        JSONArray days = new JSONArray();
        for (Attendance a : b.attendanceDays) days.put(new JSONObject().put("day", a.day).put("status", a.status));
        obj.put("attendanceDays", days);
        return obj;
    }

    /** GET /api/students/{id}/history — the student's timeline: the open period per enrollment, and every closed period. */
    Response handleStudentHistory(String studentId) throws JSONException {
        StudentReport report = buildReport(studentId);
        if (report == null) return WebServer.notFound();

        JSONArray current = new JSONArray();
        for (StudentReport.Bucket b : report.current) current.put(bucketJson(b));
        JSONArray closed = new JSONArray();
        for (StudentReport.Bucket b : report.closed) closed.put(bucketJson(b));

        JSONObject result = new JSONObject();
        result.put("student", WebServer.studentJson(report.student));
        result.put("current", current);
        result.put("periods", closed);
        return WebServer.jsonResponse(Response.Status.OK, result);
    }

    /** GET /api/students/{id}/report.pdf — the same data as a printable report card. */
    Response handleStudentReportPdf(String studentId) throws IOException {
        StudentReport report = buildReport(studentId);
        if (report == null) return WebServer.notFound();

        File file = Exporter.exportStudentReport(context, report, PT_BR);
        Response response = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/pdf",
                new FileInputStream(file), file.length());
        // inline: the browser shows the PDF straight away, ready to print or save; the name is what "save as" proposes.
        response.addHeader("Content-Disposition", "inline; filename=\"" + file.getName() + "\"");
        return response;
    }

    /* ------------------------------- period comparison ------------------------------- */

    private static JSONObject statsToJson(PeriodComparison.Stats s) throws JSONException {
        return new JSONObject()
                .put("count", s.count)
                .put("total", s.total)
                .put("average", Math.round(s.average * 10) / 10.0)
                .put("min", s.min)
                .put("max", s.max);
    }

    /** GET /api/periods/compare?disciplineId=&classGroupId= — students by closed period, with per-period class statistics. */
    Response handleCompare(IHTTPSession session) throws JSONException {
        String disciplineId = WebServer.queryParam(session, "disciplineId");
        if (disciplineId == null || disciplineId.isEmpty()) {
            return WebServer.jsonError(Response.Status.BAD_REQUEST, "disciplina é obrigatória");
        }
        String classGroupId = WebServer.queryParam(session, "classGroupId");
        if (classGroupId != null && classGroupId.isEmpty()) classGroupId = null;

        ReportLoader.Comparison loaded = ReportLoader.comparison(database, disciplineId, classGroupId);
        PeriodComparison comparison = loaded.table;
        JSONArray groupArray = new JSONArray();
        for (ClassGroup g : loaded.openGroups) groupArray.put(new JSONObject().put("id", g.id).put("name", g.name));

        JSONArray columns = new JSONArray();
        JSONObject stats = new JSONObject();
        for (PeriodComparison.Column c : comparison.columns) {
            columns.put(new JSONObject().put("key", c.key).put("label", c.label).put("closedAt", c.closedAt));
            stats.put(c.key, statsToJson(comparison.columnStats.get(c.key)));
        }

        JSONArray rows = new JSONArray();
        for (PeriodComparison.Row r : comparison.rows) {
            JSONObject values = new JSONObject();
            for (Map.Entry<String, Integer> e : r.values.entrySet()) values.put(e.getKey(), e.getValue());
            rows.put(new JSONObject()
                    .put("studentId", r.studentId)
                    .put("name", r.name)
                    .put("classGroupId", r.classGroupId == null ? "" : r.classGroupId)
                    .put("classGroup", r.classGroupName)
                    .put("values", values)
                    .put("current", r.current == null ? JSONObject.NULL : r.current)
                    .put("delta", r.delta == null ? JSONObject.NULL : r.delta));
        }

        JSONObject out = new JSONObject();
        out.put("disciplineId", disciplineId);
        out.put("classGroups", groupArray);
        out.put("columns", columns);
        out.put("rows", rows);
        out.put("stats", stats);
        out.put("currentStats", statsToJson(comparison.currentStats));
        return WebServer.jsonResponse(Response.Status.OK, out);
    }
}
