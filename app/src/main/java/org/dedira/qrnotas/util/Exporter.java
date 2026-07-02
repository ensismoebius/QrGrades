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

public class Exporter {

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

    private static String sanitize(String name) {
        if (name == null) return "student";
        return name.replaceAll("[^a-zA-Z0-9]+", "_");
    }

    private static String baseFileName(List<StudentExportData> data) {
        if (data.size() == 1) return sanitize(data.get(0).studentName);
        return "qrgrades_export";
    }

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

    private static String escapeMd(String value) {
        return value == null ? "" : value.replace("|", "\\|");
    }

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

    private static int drawTableHeader(Canvas canvas, Paint headerPaint, int[] colX, String[] headers, int y) {
        for (int i = 0; i < headers.length; i++) {
            canvas.drawText(headers[i], colX[i], y, headerPaint);
        }
        return y + ROW_HEIGHT;
    }

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

    public static void share(Context context, File file, String mimeType) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(intent, null));
    }
}
