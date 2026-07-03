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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;

import androidx.core.content.FileProvider;

import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.StudentExportData;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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

    private static File exportDir(Context context) {
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

    /** Builds a one-line, comma-separated summary of a student's goal progress, e.g. "R ✓, MB (5 to go)". */
    private static String goalsSummary(StudentExportData s) {
        if (s.goals == null || s.goals.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (GoalProgress g : s.goals) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(g.goalName);
            sb.append(g.achieved ? " ✓" : " (" + g.remaining + " to go)");
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

            writer.write("\n## Points History\n\n");
            writer.write("| Name | Date | Points | Note |\n");
            writer.write("|---|---|---|---|\n");
            for (StudentExportData s : data) {
                if (s.history == null) continue;
                for (PointsHistory h : s.history) {
                    writer.write("| " + escapeMd(s.studentName)
                            + " | " + historyFormat.format(new Date(h.createdAt))
                            + " | " + String.format(Locale.getDefault(), "%+d", h.pointsDelta)
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
        JSONArray array = buildJsonArray(data);

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file))) {
            writer.write(array.toString(2));
        }
        return file;
    }

    /** Same JSON shape as {@link #exportJson}, without writing it to a file — reused by the web server's live "/api/overview" endpoint. */
    public static JSONArray buildJsonArray(List<StudentExportData> data) throws JSONException {
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
            obj.put("points", s.points);

            JSONArray goals = new JSONArray();
            if (s.goals != null) {
                for (GoalProgress g : s.goals) {
                    JSONObject goalObj = new JSONObject();
                    goalObj.put("name", g.goalName);
                    goalObj.put("target", g.targetPoints);
                    goalObj.put("achieved", g.achieved);
                    goalObj.put("remaining", g.remaining);
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
                        h.note == null ? "" : h.note
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
