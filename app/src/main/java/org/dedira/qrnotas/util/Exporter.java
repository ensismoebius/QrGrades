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

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;

import androidx.core.content.FileProvider;

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.AttendanceStats;
import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.model.StudentReport;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds shareable/downloadable files from a list of {@link StudentExportData} (one entry per
 * student per discipline enrollment) in three formats — Markdown table, JSON, and a hand-drawn
 * PDF — plus a helper to hand the finished file off to the Android share sheet. Files are written
 * to the app's cache dir under "exports/" (not app-private "files/", since these are meant to be
 * shared/exported and can be cleaned up by the OS if space is needed).
 */
public class Exporter {

    // PDF page geometry in points (1/72 inch), roughly matching an A4 page.
    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final int MARGIN = 40;
    private static final int ROW_HEIGHT = 22;

    private Exporter() {
    }

    public static File exportDir(Context context) {
        File dir = new File(context.getCacheDir(), "exports");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Strips anything that isn't alphanumeric so a student's name is safe to use as part of a filename. */
    private static String sanitize(String name) {
        if (name == null) return "student";
        return name.replaceAll("[^a-zA-Z0-9]+", "_");
    }

    /** Uses the single student's name for a single-student export, or a generic name for a multi-student bulk export. */
    private static String baseFileName(List<StudentExportData> data) {
        if (data.size() == 1) return sanitize(data.get(0).studentName);
        return "qrgrades_export";
    }

    // One entry per distinct student, each export format's "one row per enrollment" layout never
    // shows these — a student enrolled in several disciplines/classes otherwise only ever appears
    // as several separate partial totals, which reads as "this student barely has any points"
    // even when their combined total is much higher.
    private static class StudentTotal {
        final String studentId;
        final String studentName;
        int points;

        StudentTotal(String studentId, String studentName) {
            this.studentId = studentId;
            this.studentName = studentName;
        }
    }

    /** Sums each student's points across every enrollment row in {@code data}, in first-seen order (which, since {@code data} is sorted by student name, means name order). */
    private static List<StudentTotal> computeTotals(List<StudentExportData> data) {
        Map<String, StudentTotal> totals = new LinkedHashMap<>();
        for (StudentExportData s : data) {
            StudentTotal total = totals.get(s.studentId);
            if (total == null) {
                total = new StudentTotal(s.studentId, s.studentName);
                totals.put(s.studentId, total);
            }
            total.points += s.points;
        }
        return new ArrayList<>(totals.values());
    }

    /**
     * Builds a one-line, comma-separated summary of a student's goal progress, e.g.
     * "R ✓, MB (5 to go), E ✗, X (not started)" — ✓ reached, ✗ finished without reaching it.
     */
    private static String goalsSummary(StudentExportData s) {
        if (s.goals == null || s.goals.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (GoalProgress g : s.goals) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(g.goalName);
            if (g.achieved) sb.append(" ✓");
            else if (g.isMissed()) sb.append(" ✗");
            else if (g.state == GoalProgress.STATE_UPCOMING) sb.append(" (not started)");
            else sb.append(" (").append(g.remaining).append(" to go)");
        }
        return sb.toString();
    }

    /** Writes a Markdown file with a summary table (one row per student) followed by a combined points-history table. */
    public static File exportMarkdown(Context context, List<StudentExportData> data) throws IOException {
        File file = new File(exportDir(context), baseFileName(data) + "_" + System.currentTimeMillis() + ".md");
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());
        SimpleDateFormat historyFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file))) {
            writer.write("# QrGrades Export\n\n");
            writer.write("Generated: " + timestamp + "\n\n");
            writer.write("| Name | Discipline | Class | Points | Goals |\n");
            writer.write("|---|---|---|---|---|\n");
            for (StudentExportData s : data) {
                writer.write("| " + escapeMd(s.studentName)
                        + " | " + escapeMd(s.disciplineName)
                        + " | " + escapeMd(s.classGroupName)
                        + " | " + s.points
                        + " | " + escapeMd(goalsSummary(s))
                        + " |\n");
            }

            writer.write("\n## Totals by Student\n\n");
            writer.write("| Name | Total Points |\n");
            writer.write("|---|---|\n");
            for (StudentTotal total : computeTotals(data)) {
                writer.write("| " + escapeMd(total.studentName) + " | " + total.points + " |\n");
            }

            writer.write("\n## Points History\n\n");
            writer.write("| Name | Date | Points | Category | Note |\n");
            writer.write("|---|---|---|---|---|\n");
            for (StudentExportData s : data) {
                if (s.history == null) continue;
                for (PointsHistory h : s.history) {
                    writer.write("| " + escapeMd(s.studentName)
                            + " | " + historyFormat.format(new Date(h.createdAt))
                            + " | " + String.format(Locale.getDefault(), "%+d", h.pointsDelta)
                            + " | " + escapeMd(h.tag == null ? "" : h.tag)
                            + " | " + escapeMd(h.note == null ? "" : h.note)
                            + " |\n");
                }
            }
        }
        return file;
    }

    /** Escapes the one character ("|") that would otherwise break a Markdown table cell. */
    private static String escapeMd(String value) {
        return value == null ? "" : value.replace("|", "\\|");
    }

    /** Writes {@link #buildJsonArray}'s output to a pretty-printed (2-space indent) JSON file. */
    public static File exportJson(Context context, List<StudentExportData> data) throws IOException, JSONException {
        File file = new File(exportDir(context), baseFileName(data) + "_" + System.currentTimeMillis() + ".json");
        // The file doubles as a full backup, so it carries each student's regenerated QR token: restoring it must not silently revive an old code or retire the new one.
        JSONArray array = buildJsonArray(data, true);

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file))) {
            writer.write(array.toString(2));
        }
        return file;
    }

    /** Same JSON shape as {@link #exportJson}, without writing it to a file — reused by the web server's live "/api/overview" endpoint. */
    public static JSONArray buildJsonArray(List<StudentExportData> data) throws JSONException {
        return buildJsonArray(data, false);
    }

    /**
     * @param includeQrToken whether to include each student's QR secret. Only the downloadable
     *                       backup file wants it; the live overview shown in the browser has no use for it.
     */
    public static JSONArray buildJsonArray(List<StudentExportData> data, boolean includeQrToken) throws JSONException {
        Map<String, Integer> totalsByStudent = new LinkedHashMap<>();
        for (StudentTotal total : computeTotals(data)) totalsByStudent.put(total.studentId, total.points);

        JSONArray array = new JSONArray();
        for (StudentExportData s : data) {
            JSONObject obj = new JSONObject();
            obj.put("id", s.studentId);
            obj.put("studentId", s.studentId);
            obj.put("enrollmentId", s.enrollmentId);
            obj.put("name", s.studentName);
            obj.put("photoPath", s.photoPath == null ? "" : s.photoPath);
            obj.put("disciplineId", s.disciplineId);
            obj.put("discipline", s.disciplineName);
            obj.put("classGroupId", s.classGroupId);
            obj.put("classGroup", s.classGroupName);
            obj.put("classGroupClosed", s.classGroupClosed);
            if (includeQrToken && s.qrToken != null) obj.put("qrToken", s.qrToken);
            obj.put("points", s.points);
            // Combined total across every one of this student's enrollments in this export, not
            // just this one discipline/class — see the "points" field above for the per-row value.
            obj.put("totalPoints", totalsByStudent.get(s.studentId));

            JSONArray goals = new JSONArray();
            if (s.goals != null) {
                for (GoalProgress g : s.goals) {
                    JSONObject goalObj = new JSONObject();
                    goalObj.put("name", g.goalName);
                    goalObj.put("target", g.targetPoints);
                    // The points that count for this goal (those inside its window), which is not always the row's total.
                    goalObj.put("current", g.currentPoints);
                    goalObj.put("achieved", g.achieved);
                    goalObj.put("remaining", g.remaining);
                    if (g.hasDates()) {
                        goalObj.put("startDate", g.startDate);
                        goalObj.put("endDate", g.endDate);
                    }
                    goalObj.put("state", g.stateName());
                    goalObj.put("daysLeft", g.daysLeft);
                    goals.put(goalObj);
                }
            }
            obj.put("goals", goals);

            JSONArray history = new JSONArray();
            if (s.history != null) {
                for (PointsHistory h : s.history) {
                    JSONObject historyObj = new JSONObject();
                    historyObj.put("id", h.id);
                    historyObj.put("points", h.pointsDelta);
                    historyObj.put("note", h.note == null ? "" : h.note);
                    historyObj.put("timestamp", h.createdAt);
                    if (h.tag != null) historyObj.put("tag", h.tag);
                    if (h.revertsId != null) historyObj.put("revertsId", h.revertsId);
                    history.put(historyObj);
                }
            }
            obj.put("history", history);

            array.put(obj);
        }
        return array;
    }

    /**
     * Renders a multi-page PDF report: one page (or more, paginated) listing every student with
     * their points/goals, followed by a second section listing the combined points history.
     * Uses {@link Canvas#drawText} directly (no PDF library) since {@link PdfDocument} is a
     * built-in Android API — each "page" is just a bitmap-like canvas the code draws text onto
     * at manually-tracked x/y coordinates.
     */
    public static File exportPdf(Context context, List<StudentExportData> data) throws IOException {
        File file = new File(exportDir(context), baseFileName(data) + "_" + System.currentTimeMillis() + ".pdf");

        PdfDocument document = new PdfDocument();
        Paint titlePaint = new Paint();
        titlePaint.setTextSize(16);
        titlePaint.setFakeBoldText(true);

        Paint headerPaint = new Paint();
        headerPaint.setTextSize(11);
        headerPaint.setFakeBoldText(true);

        Paint rowPaint = new Paint();
        rowPaint.setTextSize(10);

        // Fixed column x-positions/widths for the "students" table (in PDF points, from MARGIN).
        int[] colX = {MARGIN, MARGIN + 160, MARGIN + 260, MARGIN + 330, MARGIN + 380};
        int[] colWidth = {150, 90, 60, 40, PAGE_WIDTH - MARGIN - (MARGIN + 380)};
        String[] headers = {"Name", "Discipline", "Class", "Points", "Goals"};

        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, document.getPages().size() + 1).create();
        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas canvas = page.getCanvas();

        int y = MARGIN;
        canvas.drawText("QrGrades Export", MARGIN, y, titlePaint);
        y += ROW_HEIGHT;

        y = drawTableHeader(canvas, headerPaint, colX, headers, y);

        for (StudentExportData s : data) {
            // Once the next row would run past the bottom margin, finish this page and start a
            // fresh one (repeating the header) rather than drawing off the visible page area.
            if (y > PAGE_HEIGHT - MARGIN - ROW_HEIGHT) {
                document.finishPage(page);
                PdfDocument.PageInfo nextInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, document.getPages().size() + 1).create();
                page = document.startPage(nextInfo);
                canvas = page.getCanvas();
                y = MARGIN;
                y = drawTableHeader(canvas, headerPaint, colX, headers, y);
            }

            String[] values = {
                    s.studentName == null ? "" : s.studentName,
                    s.disciplineName == null ? "" : s.disciplineName,
                    s.classGroupName == null ? "" : s.classGroupName,
                    String.valueOf(s.points),
                    goalsSummary(s)
            };

            for (int i = 0; i < values.length; i++) {
                String text = truncateToWidth(values[i], rowPaint, colWidth[i]);
                canvas.drawText(text, colX[i], y, rowPaint);
            }
            y += ROW_HEIGHT;
        }

        // Totals-by-student table uses its own column layout and starts on a fresh page.
        int[] totalsColX = {MARGIN, MARGIN + 300};
        int[] totalsColWidth = {290, PAGE_WIDTH - MARGIN - (MARGIN + 300)};
        String[] totalsHeaders = {"Name", "Total Points"};

        document.finishPage(page);
        PdfDocument.PageInfo totalsPageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, document.getPages().size() + 1).create();
        page = document.startPage(totalsPageInfo);
        canvas = page.getCanvas();
        y = MARGIN;
        canvas.drawText("Totals by Student", MARGIN, y, titlePaint);
        y += ROW_HEIGHT;
        y = drawTableHeader(canvas, headerPaint, totalsColX, totalsHeaders, y);

        for (StudentTotal total : computeTotals(data)) {
            if (y > PAGE_HEIGHT - MARGIN - ROW_HEIGHT) {
                document.finishPage(page);
                PdfDocument.PageInfo nextInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, document.getPages().size() + 1).create();
                page = document.startPage(nextInfo);
                canvas = page.getCanvas();
                y = MARGIN;
                y = drawTableHeader(canvas, headerPaint, totalsColX, totalsHeaders, y);
            }

            String[] totalsValues = {total.studentName == null ? "" : total.studentName, String.valueOf(total.points)};
            for (int i = 0; i < totalsValues.length; i++) {
                String text = truncateToWidth(totalsValues[i], rowPaint, totalsColWidth[i]);
                canvas.drawText(text, totalsColX[i], y, rowPaint);
            }
            y += ROW_HEIGHT;
        }

        // Points-history table uses its own column layout and starts on a fresh page.
        int[] historyColX = {MARGIN, MARGIN + 140, MARGIN + 260, MARGIN + 320};
        int[] historyColWidth = {130, 110, 50, PAGE_WIDTH - MARGIN - (MARGIN + 320)};
        String[] historyHeaders = {"Name", "Date", "Points", "Note"};
        SimpleDateFormat historyFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        document.finishPage(page);
        PdfDocument.PageInfo historyPageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, document.getPages().size() + 1).create();
        page = document.startPage(historyPageInfo);
        canvas = page.getCanvas();
        y = MARGIN;
        canvas.drawText("Points History", MARGIN, y, titlePaint);
        y += ROW_HEIGHT;
        y = drawTableHeader(canvas, headerPaint, historyColX, historyHeaders, y);

        for (StudentExportData s : data) {
            if (s.history == null) continue;
            for (PointsHistory h : s.history) {
                if (y > PAGE_HEIGHT - MARGIN - ROW_HEIGHT) {
                    document.finishPage(page);
                    PdfDocument.PageInfo nextInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, document.getPages().size() + 1).create();
                    page = document.startPage(nextInfo);
                    canvas = page.getCanvas();
                    y = MARGIN;
                    y = drawTableHeader(canvas, headerPaint, historyColX, historyHeaders, y);
                }

                String[] historyValues = {
                        s.studentName == null ? "" : s.studentName,
                        historyFormat.format(new Date(h.createdAt)),
                        String.format(Locale.getDefault(), "%+d", h.pointsDelta),
                        (h.tag == null ? "" : "[" + h.tag + "] ") + (h.note == null ? "" : h.note)
                };

                for (int i = 0; i < historyValues.length; i++) {
                    String text = truncateToWidth(historyValues[i], rowPaint, historyColWidth[i]);
                    canvas.drawText(text, historyColX[i], y, rowPaint);
                }
                y += ROW_HEIGHT;
            }
        }

        document.finishPage(page);

        try (FileOutputStream out = new FileOutputStream(file)) {
            document.writeTo(out);
        } finally {
            document.close();
        }
        return file;
    }

    private static Paint paint(float size, boolean bold, int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(size);
        paint.setFakeBoldText(bold);
        paint.setColor(color);
        return paint;
    }

    /**
     * Renders one student's report card as a PDF: the period still open first, then each closed
     * period newest first — with points, goals, attendance, points by category and the full point
     * history of each. The text comes from the string resources in {@code locale} rather than the
     * device language, because the browser UI that asks for it is Brazilian Portuguese whatever
     * the phone happens to be set to.
     */
    public static File exportStudentReport(Context context, StudentReport report, Locale locale) throws IOException {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        Context ctx = context.createConfigurationContext(config);

        Paint title = paint(20, true, 0xFF111111);
        Paint heading = paint(14, true, 0xFF3C2FA8);
        Paint subheading = paint(11.5f, true, 0xFF111111);
        Paint body = paint(10, false, 0xFF222222);
        Paint muted = paint(9, false, 0xFF666666);
        Paint columnHead = paint(9, true, 0xFF444444);
        Paint gain = paint(10, true, 0xFF2E7D32);
        Paint loss = paint(10, true, 0xFFBA1A1A);
        Paint line = new Paint();
        line.setColor(0xFFBBBBBB);
        line.setStrokeWidth(0.7f);

        SimpleDateFormat dateTime = new SimpleDateFormat("dd/MM/yyyy HH:mm", locale);
        SimpleDateFormat dateOnly = new SimpleDateFormat("dd/MM/yyyy", locale);

        PdfLayout pdf = new PdfLayout(ctx.getString(R.string.report_page));
        pdf.text(report.student.name == null ? "" : report.student.name, title);
        pdf.text(ctx.getString(R.string.report_title) + " · "
                + ctx.getString(R.string.report_generated_on, dateTime.format(new Date())), muted);
        pdf.space(8);
        pdf.rule(line);
        pdf.space(6);

        pdf.text(ctx.getString(R.string.report_current_period), heading);
        pdf.space(4);
        if (report.current.isEmpty()) {
            pdf.text(ctx.getString(R.string.report_no_enrollments), body);
            pdf.space(8);
        }
        for (StudentReport.Bucket bucket : report.current) {
            String name = bucket.discipline + " · " + bucket.classGroup
                    + (bucket.classGroupClosed ? ctx.getString(R.string.report_class_closed) : "");
            writeReportBucket(pdf, ctx, bucket, name, dateTime, subheading, body, muted, columnHead, gain, loss, line);
        }

        // Closed periods, most recent first.
        List<StudentReport.Bucket> closedNewestFirst = new ArrayList<>(report.closed);
        java.util.Collections.reverse(closedNewestFirst);
        for (StudentReport.Bucket bucket : closedNewestFirst) {
            String date = dateOnly.format(new Date(bucket.closedAt));
            String when = bucket.label.isEmpty()
                    ? ctx.getString(R.string.report_closed_on, date)
                    : ctx.getString(R.string.report_closed_period_heading, bucket.label, date);
            pdf.ensureSpace(60);
            pdf.text(when, heading);
            pdf.space(4);
            String name = bucket.discipline + " · " + bucket.classGroup;
            writeReportBucket(pdf, ctx, bucket, name, dateTime, subheading, body, muted, columnHead, gain, loss, line);
        }

        File file = new File(exportDir(context), "boletim_" + sanitize(report.student.name) + "_" + System.currentTimeMillis() + ".pdf");
        return pdf.write(file);
    }

    /** A stored "yyyy-MM-dd" day the way the report shows dates, "dd/MM/yyyy". */
    private static String reportDay(String day) {
        return day.substring(8, 10) + "/" + day.substring(5, 7) + "/" + day.substring(0, 4);
    }

    /** One enrollment-in-one-period block of the report card. */
    private static void writeReportBucket(PdfLayout pdf, Context ctx, StudentReport.Bucket bucket, String name,
                                          SimpleDateFormat dateTime, Paint subheading, Paint body, Paint muted,
                                          Paint columnHead, Paint gain, Paint loss, Paint rule) {
        pdf.ensureSpace(70);
        pdf.text(name, subheading);
        pdf.text(ctx.getString(R.string.report_points, bucket.points), body);

        if (!bucket.goals.isEmpty()) {
            pdf.text(ctx.getString(R.string.report_goals) + ":", muted);
            for (GoalProgress goal : bucket.goals) {
                // "MB (01/02/2026 – 30/04/2026)": a goal with a window says which days counted.
                String label = goal.hasDates()
                        ? ctx.getString(R.string.report_goal_window, goal.goalName, reportDay(goal.startDate), reportDay(goal.endDate))
                        : goal.goalName;
                String text;
                if (goal.achieved) {
                    text = ctx.getString(R.string.report_goal_achieved, label, goal.targetPoints);
                } else if (goal.isMissed()) {
                    text = ctx.getString(R.string.report_goal_missed, label, goal.targetPoints, goal.currentPoints);
                } else if (goal.state == GoalProgress.STATE_UPCOMING) {
                    text = ctx.getString(R.string.report_goal_upcoming, label, goal.targetPoints);
                } else {
                    text = ctx.getString(R.string.report_goal_remaining, label, goal.targetPoints, goal.remaining);
                }
                pdf.text("  • " + text, body);
            }
        }

        AttendanceStats attendance = bucket.attendance;
        String attendanceText = attendance.total() == 0
                ? ctx.getString(R.string.report_attendance_none)
                : ctx.getString(R.string.report_attendance_summary, attendance.present, attendance.absent,
                attendance.excused, (int) Math.round(attendance.presentPercent()));
        pdf.text(ctx.getString(R.string.report_attendance) + ": " + attendanceText, body);

        // Worth a line only when some category is in use; a lone "no category" figure would just repeat the total.
        List<StudentReport.Bucket.TagTotal> byTag = bucket.pointsByTag();
        boolean anyCategory = false;
        for (StudentReport.Bucket.TagTotal t : byTag) if (!t.tag.isEmpty()) anyCategory = true;
        if (anyCategory) {
            StringBuilder sb = new StringBuilder(ctx.getString(R.string.report_by_category)).append(": ");
            for (int i = 0; i < byTag.size(); i++) {
                StudentReport.Bucket.TagTotal t = byTag.get(i);
                if (i > 0) sb.append(" · ");
                sb.append(t.tag.isEmpty() ? ctx.getString(R.string.report_no_category) : t.tag)
                        .append(' ').append(String.format(Locale.US, "%+d", t.points));
            }
            pdf.text(sb.toString(), body);
        }

        pdf.space(6);
        pdf.text(ctx.getString(R.string.report_history), columnHead);
        if (bucket.history.isEmpty()) {
            pdf.text(ctx.getString(R.string.report_no_history), muted);
        } else {
            float[] offsets = {0, 100, 140, 245};
            float[] widths = {95, 36, 100, pdf.contentWidth() - 245};
            pdf.row(new String[]{ctx.getString(R.string.report_col_date), ctx.getString(R.string.report_col_points),
                            ctx.getString(R.string.report_col_category), ctx.getString(R.string.report_col_note)},
                    offsets, widths, new Paint[]{columnHead, columnHead, columnHead, columnHead});
            for (PointsHistory h : bucket.history) {
                // A cancelled entry stays visible but greyed out, so the history still adds up on paper.
                boolean cancelled = h.reverted;
                Paint numberPaint = cancelled ? muted : (h.pointsDelta < 0 ? loss : gain);
                Paint textPaint = cancelled ? muted : body;
                String note = (h.note == null ? "" : h.note)
                        + (cancelled ? " " + ctx.getString(R.string.report_cancelled) : "");
                pdf.row(new String[]{dateTime.format(new Date(h.createdAt)),
                                String.format(Locale.US, "%+d", h.pointsDelta), h.tag == null ? "" : h.tag, note.trim()},
                        offsets, widths, new Paint[]{textPaint, numberPaint, textPaint, textPaint});
            }
        }
        pdf.space(8);
        pdf.rule(rule);
        pdf.space(8);
    }

    /** Draws one row of column headers and returns the y-coordinate for the next row below it. */
    private static int drawTableHeader(Canvas canvas, Paint headerPaint, int[] colX, String[] headers, int y) {
        for (int i = 0; i < headers.length; i++) {
            canvas.drawText(headers[i], colX[i], y, headerPaint);
        }
        return y + ROW_HEIGHT;
    }

    /** Shortens {@code text} character-by-character (appending "…") until it fits within {@code maxWidth}, since Canvas won't wrap or clip text for you. */
    private static String truncateToWidth(String text, Paint paint, int maxWidth) {
        if (paint.measureText(text) <= maxWidth) return text;
        String ellipsis = "…";
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (paint.measureText(sb.toString() + c + ellipsis) > maxWidth) break;
            sb.append(c);
        }
        return sb + ellipsis;
    }

    /** Opens the system share sheet for the given file, via a {@link FileProvider} content URI (required since Android forbids sharing raw file:// paths to other apps). */
    public static void share(Context context, File file, String mimeType) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(intent, null));
    }
}
