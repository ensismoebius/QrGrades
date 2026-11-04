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

import org.dedira.qrnotas.R;
import org.dedira.qrnotas.model.PointsAward;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.RevertResult;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.DatabaseSync;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

/**
 * Everything that moves points over HTTP: a single award, a batch ("everyone handed in the
 * assignment"), cancelling or correcting an entry, and the note/category suggestion lists. All the
 * actual changes go through {@link Database}'s atomic methods, so the total and the history can't
 * drift apart no matter how requests interleave.
 */
final class PointsApi {

    // Generous enough for any real classroom, small enough to stop a mistyped field from doing real damage.
    private static final int MAX_DELTA = 1000;
    private static final int MAX_BATCH = 500;
    private static final int SUGGESTION_NOTES = 60;
    private static final int SUGGESTION_TAGS = 30;

    private final Context context;
    private final Database database;

    PointsApi(Context context, Database database) {
        this.context = context;
        this.database = database;
    }

    /** Validates the {@code delta} field shared by every endpoint here; returns an error response, or null if it is fine. */
    private static Response checkDelta(int delta) {
        if (delta == 0) return WebServer.jsonError(Response.Status.BAD_REQUEST, "a quantidade de pontos não pode ser zero");
        if (Math.abs(delta) > MAX_DELTA) {
            return WebServer.jsonError(Response.Status.BAD_REQUEST, "a quantidade de pontos é grande demais (máximo de " + MAX_DELTA + ")");
        }
        return null;
    }

    /** POST /api/enrollments/{id}/points — one award or deduction, optionally with a category. */
    Response handleAward(IHTTPSession session, String enrollmentId) throws Exception {
        JSONObject body = WebServer.readJsonBody(session);
        int delta = body.optInt("delta", 0);
        Response invalid = checkDelta(delta);
        if (invalid != null) return invalid;

        PointsAward award = DatabaseSync.awardPoints(database, enrollmentId, delta,
                body.optString("note", "").trim(), body.optString("tag", ""));
        if (award == null) return WebServer.notFound();

        JSONObject result = WebServer.enrollmentJson(award.enrollment);
        result.put("historyId", award.history.id);
        return WebServer.jsonResponse(Response.Status.OK, result);
    }

    /** POST /api/points/batch — the same award for many enrollments at once, all or nothing. */
    Response handleBatch(IHTTPSession session) throws Exception {
        JSONObject body = WebServer.readJsonBody(session);
        int delta = body.optInt("delta", 0);
        Response invalid = checkDelta(delta);
        if (invalid != null) return invalid;

        JSONArray array = body.optJSONArray("enrollmentIds");
        List<String> ids = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String id = array.optString(i, "");
                if (!id.isEmpty()) ids.add(id);
            }
        }
        if (ids.isEmpty()) return WebServer.jsonError(Response.Status.BAD_REQUEST, "selecione ao menos um aluno");
        if (ids.size() > MAX_BATCH) {
            return WebServer.jsonError(Response.Status.BAD_REQUEST, "lote grande demais (máximo de " + MAX_BATCH + " alunos)");
        }

        ArrayList<PointsAward> awards = DatabaseSync.awardPointsBatch(database, ids, delta,
                body.optString("note", "").trim(), body.optString("tag", ""));
        if (awards == null) {
            return WebServer.jsonError(Response.Status.NOT_FOUND, "nenhuma alteração foi feita: alguma matrícula não foi encontrada");
        }

        JSONArray entries = new JSONArray();
        for (PointsAward award : awards) {
            JSONObject entry = new JSONObject();
            entry.put("enrollmentId", award.enrollment.id);
            entry.put("historyId", award.history.id);
            entry.put("points", award.enrollment.grades);
            entries.put(entry);
        }
        JSONObject result = new JSONObject();
        result.put("count", awards.size());
        result.put("entries", entries);
        return WebServer.jsonResponse(Response.Status.OK, result);
    }

    /** Maps a {@link Database#revertPointsHistory} error code to an HTTP response; the 409 codes are the ones the browser turns into messages. */
    private static Response revertError(String code) {
        if ("NOT_FOUND".equals(code)) return WebServer.notFound();
        if ("ALREADY_REVERTED".equals(code) || "IS_REVERSAL".equals(code) || "CLOSED_PERIOD".equals(code)) {
            return WebServer.jsonError(Response.Status.CONFLICT, code);
        }
        return WebServer.jsonError(Response.Status.INTERNAL_ERROR, "falha ao estornar o lançamento");
    }

    /**
     * POST /api/history/{id}/revert, or /correct when {@code correct} is true (the body then
     * carries the corrected {@code delta}, {@code note} and {@code tag}, recorded right after the cancelling entry).
     */
    Response handleRevert(IHTTPSession session, String historyId, boolean correct) throws Exception {
        // Read the body even for a plain cancel, which doesn't need one: see WebServer.readJsonBody.
        JSONObject body = WebServer.readJsonBody(session);
        PointsHistory replacement = null;
        if (correct) {
            int delta = body.optInt("delta", 0);
            Response invalid = checkDelta(delta);
            if (invalid != null) return invalid;
            replacement = new PointsHistory();
            replacement.pointsDelta = delta;
            replacement.note = body.optString("note", "").trim();
            replacement.tag = body.optString("tag", "");
        }

        RevertResult result = DatabaseSync.revertPointsHistory(database, historyId, replacement);
        if (result.error != null) return revertError(result.error);

        JSONObject out = new JSONObject();
        out.put("success", true);
        out.put("reversalId", result.reversal.history.id);
        if (result.replacement != null) out.put("replacementId", result.replacement.history.id);
        PointsAward last = result.replacement != null ? result.replacement : result.reversal;
        out.put("enrollment", WebServer.enrollmentJson(last.enrollment));
        return WebServer.jsonResponse(Response.Status.OK, out);
    }

    /**
     * POST /api/history/revert-batch — cancels several entries (the "undo this batch" button).
     * Each entry is cancelled on its own, so one that can't be (say, its period was closed in the
     * meantime) is reported without holding back the rest.
     */
    Response handleRevertBatch(IHTTPSession session) throws Exception {
        JSONObject body = WebServer.readJsonBody(session);
        JSONArray ids = body.optJSONArray("ids");
        if (ids == null || ids.length() == 0) {
            return WebServer.jsonError(Response.Status.BAD_REQUEST, "nenhum lançamento informado");
        }
        if (ids.length() > MAX_BATCH) {
            return WebServer.jsonError(Response.Status.BAD_REQUEST, "lote grande demais (máximo de " + MAX_BATCH + " lançamentos)");
        }

        int reverted = 0;
        JSONArray failed = new JSONArray();
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.optString(i, "");
            RevertResult result = DatabaseSync.revertPointsHistory(database, id, null);
            if (result.error == null) {
                reverted++;
            } else {
                failed.put(new JSONObject().put("id", id).put("error", result.error));
            }
        }
        JSONObject out = new JSONObject();
        out.put("reverted", reverted);
        out.put("failed", failed);
        return WebServer.jsonResponse(Response.Status.OK, out);
    }

    /** GET /api/suggestions — recently used notes, and categories (recent ones first, then the built-in defaults), for the form autocompletes. */
    Response handleSuggestions() throws JSONException {
        JSONArray notes = new JSONArray();
        ArrayList<String> recentNotes = DatabaseSync.loadRecentNotes(database, SUGGESTION_NOTES);
        if (recentNotes != null) for (String note : recentNotes) notes.put(note);

        // Recent categories first; the defaults fill in after, skipping any the teacher already uses (ignoring case).
        Map<String, String> tags = new LinkedHashMap<>();
        ArrayList<String> recentTags = DatabaseSync.loadRecentTags(database, SUGGESTION_TAGS);
        if (recentTags != null) for (String tag : recentTags) tags.put(tag.toLowerCase(Locale.ROOT), tag);
        for (String tag : context.getResources().getStringArray(R.array.default_tags)) {
            String key = tag.toLowerCase(Locale.ROOT);
            if (!tags.containsKey(key)) tags.put(key, tag);
        }

        JSONArray tagArray = new JSONArray();
        for (String tag : tags.values()) tagArray.put(tag);
        JSONObject out = new JSONObject();
        out.put("notes", notes);
        out.put("tags", tagArray);
        return WebServer.jsonResponse(Response.Status.OK, out);
    }
}
