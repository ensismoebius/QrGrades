package org.dedira.qrnotas.util;

import android.content.Context;
import android.net.Uri;

import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.StudentExportData;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reads back the JSON produced by {@link Exporter#exportJson}. */
public class Importer {

    private Importer() {
    }

    public static List<StudentExportData> importJson(Context context, Uri uri) throws IOException, JSONException {
        return parseJsonArray(readAll(context, uri));
    }

    /** Parses the JSON produced by {@link Exporter#exportJson} from an already-read string (e.g. an HTTP upload body, not just a local file). */
    public static List<StudentExportData> parseJsonArray(String content) throws JSONException {
        JSONArray array = new JSONArray(content);

        List<StudentExportData> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);

            StudentExportData s = new StudentExportData();
            s.studentId = obj.optString("studentId", obj.optString("id", null));
            s.enrollmentId = obj.optString("enrollmentId", null);
            s.studentName = obj.optString("name", "");
            s.photoPath = obj.optString("photoPath", null);
            if (s.photoPath != null && s.photoPath.isEmpty()) s.photoPath = null;
            s.disciplineId = obj.optString("disciplineId", null);
            s.disciplineName = obj.optString("discipline", "");
            s.classGroupId = obj.optString("classGroupId", null);
            s.classGroupName = obj.optString("classGroup", "");
            s.points = obj.optInt("points", 0);

            JSONArray goals = obj.optJSONArray("goals");
            if (goals != null) {
                for (int g = 0; g < goals.length(); g++) {
                    JSONObject goalObj = goals.getJSONObject(g);
                    s.goals.add(new GoalProgress(
                            goalObj.optString("name", ""),
                            goalObj.optInt("target", 0),
                            s.points));
                }
            }

            JSONArray history = obj.optJSONArray("history");
            if (history != null) {
                for (int h = 0; h < history.length(); h++) {
                    JSONObject historyObj = history.getJSONObject(h);
                    PointsHistory ph = new PointsHistory();
                    ph.id = historyObj.optString("id", null);
                    ph.pointsDelta = historyObj.optInt("points", 0);
                    ph.note = historyObj.optString("note", "");
                    ph.createdAt = historyObj.optLong("timestamp", 0);
                    s.history.add(ph);
                }
            }

            result.add(s);
        }
        return result;
    }

    private static String readAll(Context context, Uri uri) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Unable to open " + uri);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
