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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.dedira.qrnotas.model.Attendance;
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.CsvImportPlan;
import org.dedira.qrnotas.model.CsvRowError;
import org.dedira.qrnotas.model.CsvStudentRow;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Enrollment;
import org.dedira.qrnotas.model.Goal;
import org.dedira.qrnotas.model.GoalProgress;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.util.BitmapConverter;
import org.dedira.qrnotas.util.CsvImporter;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.DatabaseSync;
import org.dedira.qrnotas.util.Exporter;
import org.dedira.qrnotas.util.GoalTracker;
import org.dedira.qrnotas.util.Importer;
import org.dedira.qrnotas.util.QrCode;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import fi.iki.elonen.NanoHTTPD;

/**
 * Local LAN admin server: JSON API under /api/* (session-cookie auth after /api/login) plus a
 * static single-page app served from assets/web/. Every /api/* handler blocks its own NanoHTTPD
 * worker thread on {@link DatabaseSync} — never called from the main thread, see that class.
 * <p>
 * For readers new to Android/server programming: {@link NanoHTTPD} is a tiny, dependency-free
 * HTTP server library. Subclassing it and overriding {@link #serve(IHTTPSession)} is all that's
 * needed to turn this class into a real web server — NanoHTTPD handles opening a socket,
 * accepting connections, and parsing raw HTTP requests into the {@code IHTTPSession} object
 * passed to us. Each incoming request runs on its own background thread (a "worker thread"),
 * which is why the handlers below are allowed to do blocking database work directly instead of
 * needing callbacks — but it also means multiple requests can run this code concurrently, which
 * is why the shared state below (tokens, fail counts, lockouts) uses thread-safe collections.
 * <p>
 * The overall request flow is: {@link #serve} decides whether the URI is a static asset (the
 * web app's HTML/CSS/JS) or an API call; API calls (other than login) require a valid session
 * cookie; then {@link #route} dispatches by the first path segment (e.g. "students", "goals")
 * to a per-entity {@code routeX} method, which in turn picks a {@code handleX} method based on
 * the HTTP method (GET/POST/PUT/DELETE) — the same REST pattern repeats for every entity type
 * (students, disciplines, class groups, goals, enrollments).
 */
public class WebServer extends NanoHTTPD {

    // Port the embedded HTTP server listens on; shown to the user as part of the server's URL.
    public static final int PORT = 8080;

    // Name of the cookie used to carry the session token once a client has logged in successfully.
    private static final String COOKIE_NAME = "qrgrades_session";
    // After this many wrong password attempts from the same IP, further attempts are locked out for a while.
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    // Duration (in milliseconds) an IP stays locked out after too many failed login attempts.
    private static final long LOCKOUT_MS = 60_000L;

    private final Context context;
    private final Database database;
    private final String password;
    // The newer endpoints live in their own classes; this one keeps the session handling, routing and the older CRUD handlers.
    private final PointsApi pointsApi;
    private final AttendanceApi attendanceApi;
    private final HistoryApi historyApi;
    // Set of currently-valid session tokens (one per logged-in browser). Wrapped with
    // Collections.synchronizedSet because multiple request-handling threads can read/write it concurrently.
    private final Set<String> validTokens = Collections.synchronizedSet(new HashSet<>());
    // Per-IP count of consecutive failed login attempts, used for the lockout logic below.
    private final Map<String, int[]> failCounts = Collections.synchronizedMap(new HashMap<>());
    // Per-IP timestamp (millis since epoch) until which further login attempts are rejected outright.
    private final Map<String, Long> lockUntil = Collections.synchronizedMap(new HashMap<>());

    public WebServer(Context context, Database database, String password) {
        this(context, database, password, PORT);
    }

    /** For tests, which ask for port 0 (any free port) instead of the fixed one. */
    WebServer(Context context, Database database, String password, int port) {
        super(port);
        // Always store the application Context (not an Activity/Service Context) to avoid
        // accidentally keeping a short-lived component alive/leaking memory.
        this.context = context.getApplicationContext();
        this.database = database;
        this.password = password;
        this.pointsApi = new PointsApi(this.context, database);
        this.attendanceApi = new AttendanceApi(database);
        this.historyApi = new HistoryApi(this.context, database);
    }

    /**
     * The single entry point NanoHTTPD calls for every incoming HTTP request, on a background
     * worker thread. Serves the static web app files directly, otherwise enforces the
     * session-cookie authentication for everything under /api/* (except the login endpoint
     * itself) before handing off to {@link #route}.
     */
    @Override
    public Response serve(IHTTPSession session) {
        try {
            String uri = session.getUri();
            Method method = session.getMethod();

            // The bundled single-page web app and its assets are served as plain static files.
            if (uri.equals("/") || uri.equals("/index.html")) return serveAsset("web/index.html", "text/html");
            if (uri.equals("/app.css")) return serveAsset("web/app.css", "text/css");
            if (uri.equals("/app.js")) return serveAsset("web/app.js", "application/javascript");
            if (uri.equals("/favicon.svg")) return serveAsset("web/favicon.svg", "image/svg+xml");
            // Feature scripts, one file per screen. Only plain "/js/<name>.js" is accepted — no sub-folders or ".." tricks.
            if (uri.matches("/js/[A-Za-z0-9_-]+\\.js")) return serveAsset("web" + uri, "application/javascript");

            // Everything else must be a JSON API call under /api/; anything else is unknown.
            if (!uri.startsWith("/api/")) return notFound();
            // Login is the only /api/ endpoint reachable without an existing session.
            if (uri.equals("/api/login") && method == Method.POST) return handleLogin(session);

            // Every other API call must present a valid session cookie obtained from /api/login.
            String token = readSessionToken(session);
            if (token == null || !validTokens.contains(token)) {
                return jsonError(Response.Status.UNAUTHORIZED, "não autorizado");
            }

            if (uri.equals("/api/logout") && method == Method.POST) {
                readJsonBody(session); // unused, but it has to be read: see readJsonBody
                validTokens.remove(token);
                return jsonOk();
            }

            return route(session, uri, method);
        } catch (Exception e) {
            // Catch-all so an unexpected exception in any handler becomes a proper HTTP 500
            // JSON response instead of crashing the worker thread or leaking a raw stack trace.
            return jsonError(Response.Status.INTERNAL_ERROR, e.getMessage() == null ? "erro no servidor" : e.getMessage());
        }
    }

    /**
     * Dispatches an authenticated /api/* request to the right per-entity handler, based on the
     * first path segment after "/api/" (e.g. "/api/students/123" routes on "students").
     */
    private Response route(IHTTPSession session, String uri, Method method) throws Exception {
        // Strip the "/api/" prefix, then split the remaining path into segments, e.g.
        // "students/123/photo" -> ["students", "123", "photo"].
        String path = uri.substring("/api/".length());
        String[] parts = path.isEmpty() ? new String[0] : path.split("/");
        if (parts.length == 0) return notFound();

        switch (parts[0]) {
            case "overview":
                if (method == Method.GET) return handleOverview();
                break;
            case "students":
                return routeStudents(session, method, parts);
            case "disciplines":
                return routeDisciplines(session, method, parts);
            case "classgroups":
                return routeClassGroups(session, method, parts);
            case "goals":
                return routeGoals(session, method, parts);
            case "enrollments":
                return routeEnrollments(session, method, parts);
            case "history":
                if (parts.length == 1 && method == Method.GET) return handleHistory(session);
                // "/api/history/revert-batch", "/api/history/{id}/revert", "/api/history/{id}/correct"
                if (parts.length == 2 && parts[1].equals("revert-batch") && method == Method.POST) {
                    return pointsApi.handleRevertBatch(session);
                }
                if (parts.length == 3 && method == Method.POST) {
                    if (parts[2].equals("revert")) return pointsApi.handleRevert(session, parts[1], false);
                    if (parts[2].equals("correct")) return pointsApi.handleRevert(session, parts[1], true);
                }
                break;
            case "points":
                // "/api/points/batch"
                if (parts.length == 2 && parts[1].equals("batch") && method == Method.POST) return pointsApi.handleBatch(session);
                break;
            case "suggestions":
                if (method == Method.GET) return pointsApi.handleSuggestions();
                break;
            case "attendance":
                return attendanceApi.handle(session, method, parts);
            case "periods":
                // "/api/periods/compare"
                if (parts.length == 2 && parts[1].equals("compare") && method == Method.GET) return historyApi.handleCompare(session);
                break;
            case "export":
                if (method == Method.GET) return handleExport(session);
                break;
            case "import":
                // "/api/import/json" or "/api/import/csv"
                if (parts.length == 2 && method == Method.POST) {
                    if (parts[1].equals("json")) return handleImportJson(session);
                    if (parts[1].equals("csv")) return handleImportCsv(session);
                }
                break;
            default:
                break;
        }
        return notFound();
    }

    /* ---------------------------------- Auth ------------------------------------ */

    /**
     * Handles POST /api/login. Checks the submitted password against the server's password,
     * tracking failed attempts per IP address and temporarily locking out IPs that fail too many
     * times in a row (a simple brute-force protection since this server has no other rate limiting).
     * On success, issues a new random session token as an HttpOnly cookie.
     */
    private Response handleLogin(IHTTPSession session) throws IOException, ResponseException, JSONException {
        String ip = session.getRemoteIpAddress();
        Long locked = lockUntil.get(ip);
        if (locked != null && locked > System.currentTimeMillis()) {
            // Still within the lockout window for this IP; reject without even checking the password.
            return jsonError(Response.Status.FORBIDDEN, "muitas tentativas, tente novamente mais tarde");
        }

        JSONObject body = readJsonBody(session);
        String submitted = body.optString("password", "");

        if (password != null && password.equals(submitted)) {
            // Successful login clears any prior failure tracking for this IP.
            failCounts.remove(ip);
            lockUntil.remove(ip);
            // A random UUID is unpredictable and unique enough to serve as a session token.
            String token = UUID.randomUUID().toString();
            validTokens.add(token);

            Response response = jsonOk();
            // HttpOnly prevents JavaScript in the browser from reading the cookie (mitigates
            // XSS token theft); SameSite=Strict prevents the browser from sending it on
            // cross-site requests (mitigates CSRF). Path=/ makes it valid for the whole site.
            response.addHeader("Set-Cookie", COOKIE_NAME + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
            return response;
        }

        // Wrong password: bump this IP's failure counter, using a 1-element int[] as a simple
        // mutable counter box since Integer objects are immutable and Map merge would be more verbose here.
        int[] count = failCounts.get(ip);
        if (count == null) {
            count = new int[1];
            failCounts.put(ip, count);
        }
        count[0]++;
        if (count[0] >= MAX_LOGIN_ATTEMPTS) {
            // Too many failures: lock this IP out for LOCKOUT_MS and reset the counter.
            lockUntil.put(ip, System.currentTimeMillis() + LOCKOUT_MS);
            failCounts.remove(ip);
        }
        return jsonError(Response.Status.UNAUTHORIZED, "senha incorreta");
    }

    /**
     * Extracts the session token from the raw "Cookie" request header, if present. HTTP allows
     * multiple cookies to be sent in one header separated by semicolons (e.g.
     * "a=1; qrgrades_session=abc; b=2"), so this scans each "name=value" pair for the one we want.
     */
    private String readSessionToken(IHTTPSession session) {
        String cookieHeader = session.getHeaders().get("cookie");
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            // Split on the first "=" only (limit 2), since a token value could theoretically contain "=".
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(COOKIE_NAME)) return kv[1];
        }
        return null;
    }

    /* -------------------------------- Overview ----------------------------------- */

    /** Handles GET /api/overview: returns every student's full exportable data as one JSON array. */
    private Response handleOverview() throws JSONException {
        List<StudentExportData> data = DatabaseSync.loadExportData(database, null);
        return jsonResponse(Response.Status.OK, Exporter.buildJsonArray(data));
    }

    /* -------------------------------- Students ------------------------------------ */

    /**
     * Dispatches all /api/students... requests by path shape and HTTP method:
     * list/create on the collection, update/delete on a single student by id, and a nested
     * "/photo" sub-resource for uploading/downloading a student's picture.
     */
    private Response routeStudents(IHTTPSession session, Method method, String[] parts) throws Exception {
        if (parts.length == 1) {
            // "/api/students"
            if (method == Method.GET) return handleListStudents();
            if (method == Method.POST) return handleCreateStudent(session);
        } else if (parts.length == 2) {
            // "/api/students/{id}"
            String id = parts[1];
            if (method == Method.PUT) return handleUpdateStudent(session, id);
            if (method == Method.DELETE) return handleDeleteStudent(id);
        } else if (parts.length == 3 && parts[2].equals("photo")) {
            // "/api/students/{id}/photo"
            String id = parts[1];
            if (method == Method.GET) return handleGetPhoto(id);
            if (method == Method.POST) return handleUploadPhoto(session, id);
        } else if (parts.length == 3 && parts[2].equals("qrcode")) {
            // "/api/students/{id}/qrcode"
            if (method == Method.GET) return handleGetQrCode(parts[1]);
        } else if (parts.length == 4 && parts[2].equals("qrcode") && parts[3].equals("regenerate")) {
            // "/api/students/{id}/qrcode/regenerate"
            if (method == Method.POST) return handleRegenerateQrCode(session, parts[1]);
        } else if (parts.length == 3 && parts[2].equals("history")) {
            // "/api/students/{id}/history"
            if (method == Method.GET) return historyApi.handleStudentHistory(parts[1]);
        } else if (parts.length == 3 && parts[2].equals("report.pdf")) {
            // "/api/students/{id}/report.pdf"
            if (method == Method.GET) return historyApi.handleStudentReportPdf(parts[1]);
        }
        return notFound();
    }

    /** Converts a {@link Student} entity into the small JSON shape the web UI expects. */
    static JSONObject studentJson(Student s) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", s.id);
        obj.put("name", s.name);
        // Whether the printed code was ever replaced (never the token itself) — the UI shows a small marker.
        obj.put("qrRegenerated", s.qrToken != null);
        // Only expose whether a photo exists, not the photo data itself (that's fetched
        // separately via GET /api/students/{id}/photo).
        obj.put("hasPhoto", s.photoPath != null);
        return obj;
    }

    /** Handles GET /api/students: returns every student as a JSON array. */
    private Response handleListStudents() throws JSONException {
        ArrayList<Student> students = DatabaseSync.loadAllStudents(database);
        JSONArray array = new JSONArray();
        if (students != null) for (Student s : students) array.put(studentJson(s));
        return jsonResponse(Response.Status.OK, array);
    }

    /** Handles POST /api/students: creates a new student from the JSON body's "name" field. */
    private Response handleCreateStudent(IHTTPSession session) throws Exception {
        JSONObject body = readJsonBody(session);
        String name = body.optString("name", "").trim();
        if (name.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "o nome é obrigatório");

        Student student = new Student();
        student.name = name;
        DatabaseSync.Result<Student> result = DatabaseSync.saveStudent(database, student);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "falha ao salvar o aluno");
        return jsonResponse(Response.Status.OK, studentJson(result.value));
    }

    /** Handles PUT /api/students/{id}: renames an existing student. */
    private Response handleUpdateStudent(IHTTPSession session, String id) throws Exception {
        Student existing = DatabaseSync.loadStudent(database, id);
        if (existing == null) return notFound();

        JSONObject body = readJsonBody(session);
        // Falls back to the existing name if the field is missing, so a partial update doesn't wipe it out.
        String name = body.optString("name", existing.name).trim();
        if (name.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "o nome é obrigatório");
        existing.name = name;

        DatabaseSync.Result<Student> result = DatabaseSync.saveStudent(database, existing);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "falha ao salvar o aluno");
        return jsonResponse(Response.Status.OK, studentJson(result.value));
    }

    /** Handles DELETE /api/students/{id}: removes a student. */
    private Response handleDeleteStudent(String id) {
        boolean success = DatabaseSync.deleteStudent(database, id);
        return success ? jsonOk() : jsonError(Response.Status.NOT_FOUND, "aluno não encontrado");
    }

    /** Handles GET /api/students/{id}/photo: streams the student's saved photo file, if any. */
    private Response handleGetPhoto(String id) {
        Student student = DatabaseSync.loadStudent(database, id);
        if (student == null || student.photoPath == null) return notFound();
        File photo = new File(student.photoPath);
        if (!photo.exists()) return notFound();
        try {
            // newFixedLengthResponse streams the file directly as the response body without
            // loading it all into memory first, and sets the Content-Length from photo.length().
            return newFixedLengthResponse(Response.Status.OK, "image/png", new FileInputStream(photo), photo.length());
        } catch (IOException e) {
            return notFound();
        }
    }

    /**
     * Handles GET /api/students/{id}/qrcode: generates (on the fly, nothing is cached on disk)
     * and streams a PNG of the student's personal QR code — the same id-encoded image
     * {@link org.dedira.qrnotas.dialogs.QrCodeDialog} shows in-app — so the web UI's "print QR
     * codes" feature can lay it out next to the student's name without needing the phone.
     */
    private Response handleGetQrCode(String id) {
        Student student = DatabaseSync.loadStudent(database, id);
        if (student == null) return notFound();

        // The code to print is the current one: the id until the code is regenerated, the token after.
        Bitmap qrBitmap = QrCode.generateQRCode(student.qrCode());
        if (qrBitmap == null) return jsonError(Response.Status.INTERNAL_ERROR, "falha ao gerar o código QR");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        byte[] png = out.toByteArray();
        Response response = newFixedLengthResponse(Response.Status.OK, "image/png", new ByteArrayInputStream(png), png.length);
        // A browser that kept the old image around would keep showing (and printing) a code that no longer works.
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    /**
     * Handles POST /api/students/{id}/qrcode/regenerate: issues the student a new QR code (they lose
     * theirs often). The previous code stops identifying anyone immediately — that is the point of
     * regenerating rather than just reprinting — so the browser asks for confirmation first.
     */
    private Response handleRegenerateQrCode(IHTTPSession session, String id) throws Exception {
        readJsonBody(session); // nothing in it is needed, but it has to be read: see readJsonBody
        Student updated = DatabaseSync.regenerateQrToken(database, id);
        if (updated == null) return notFound();
        return jsonResponse(Response.Status.OK, new JSONObject().put("success", true).put("student", studentJson(updated)));
    }

    /**
     * Handles POST /api/students/{id}/photo: accepts a multipart/form-data upload (the standard
     * way browsers send file uploads) containing a "photo" file field, decodes it as a bitmap,
     * saves a copy into the app's own storage, and updates the student's photoPath.
     */
    private Response handleUploadPhoto(IHTTPSession session, String id) throws Exception {
        Student student = DatabaseSync.loadStudent(database, id);
        if (student == null) return notFound();

        // NanoHTTPD's parseBody() handles multipart/form-data parsing internally: for file
        // fields, it writes the uploaded bytes to a temp file and puts that file's path into
        // this map keyed by the form field name (here, "photo").
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        String tempPath = files.get("photo");
        if (tempPath == null) return jsonError(Response.Status.BAD_REQUEST, "selecione uma foto");

        Bitmap bitmap = BitmapFactory.decodeFile(tempPath);
        if (bitmap == null) return jsonError(Response.Status.BAD_REQUEST, "arquivo de imagem inválido");

        String savedPath = BitmapConverter.saveStudentPhoto(context, bitmap, id);
        if (savedPath == null) return jsonError(Response.Status.INTERNAL_ERROR, "falha ao salvar a foto");

        student.photoPath = savedPath;
        DatabaseSync.Result<Student> result = DatabaseSync.saveStudent(database, student);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "falha ao salvar o aluno");
        return jsonOk();
    }

    /* ------------------------------- Disciplines ----------------------------------- */

    /**
     * Dispatches all /api/disciplines... requests: list/create on the collection,
     * update/delete on a single discipline by id. Same REST shape as {@link #routeStudents}.
     */
    private Response routeDisciplines(IHTTPSession session, Method method, String[] parts) throws Exception {
        if (parts.length == 1) {
            if (method == Method.GET) return handleListDisciplines();
            if (method == Method.POST) return handleCreateDiscipline(session);
        } else if (parts.length == 2) {
            String id = parts[1];
            if (method == Method.PUT) return handleUpdateDiscipline(session, id);
            if (method == Method.DELETE) return handleDeleteDiscipline(id);
        }
        return notFound();
    }

    /** Converts a {@link Discipline} entity into the JSON shape the web UI expects. */
    private static JSONObject disciplineJson(Discipline d) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", d.id);
        obj.put("name", d.name);
        return obj;
    }

    /** Handles GET /api/disciplines: returns every discipline as a JSON array. */
    private Response handleListDisciplines() throws JSONException {
        ArrayList<Discipline> list = DatabaseSync.loadAllDisciplines(database);
        JSONArray array = new JSONArray();
        if (list != null) for (Discipline d : list) array.put(disciplineJson(d));
        return jsonResponse(Response.Status.OK, array);
    }

    /** Handles POST /api/disciplines: creates a new discipline from the JSON body's "name" field. */
    private Response handleCreateDiscipline(IHTTPSession session) throws Exception {
        JSONObject body = readJsonBody(session);
        String name = body.optString("name", "").trim();
        if (name.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "o nome é obrigatório");

        Discipline discipline = new Discipline();
        discipline.name = name;
        DatabaseSync.Result<Discipline> result = DatabaseSync.saveDiscipline(database, discipline);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "falha ao salvar a disciplina");
        return jsonResponse(Response.Status.OK, disciplineJson(result.value));
    }

    /** Handles PUT /api/disciplines/{id}: renames an existing discipline. */
    private Response handleUpdateDiscipline(IHTTPSession session, String id) throws Exception {
        JSONObject body = readJsonBody(session);
        String name = body.optString("name", "").trim();
        if (name.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "o nome é obrigatório");

        Discipline discipline = new Discipline();
        discipline.id = id;
        discipline.name = name;
        DatabaseSync.Result<Discipline> result = DatabaseSync.saveDiscipline(database, discipline);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "falha ao salvar a disciplina");
        return jsonResponse(Response.Status.OK, disciplineJson(result.value));
    }

    /**
     * Handles DELETE /api/disciplines/{id}: deletes a discipline unless it still has class
     * groups attached, in which case it returns 409 Conflict so the UI can tell the user to
     * remove those first (deleting the discipline out from under them would orphan data).
     */
    private Response handleDeleteDiscipline(String id) {
        DatabaseSync.Result<Void> result = DatabaseSync.deleteDiscipline(database, id);
        if (result.success) return jsonOk();
        if ("HAS_GROUPS".equals(result.error)) return jsonError(Response.Status.CONFLICT, "HAS_GROUPS");
        return jsonError(Response.Status.NOT_FOUND, "disciplina não encontrada");
    }

    /* ------------------------------- Class groups ----------------------------------- */

    /**
     * Dispatches all /api/classgroups... requests: list/create on the collection,
     * update/delete on a single class group by id. Same REST shape as {@link #routeStudents}.
     */
    private Response routeClassGroups(IHTTPSession session, Method method, String[] parts) throws Exception {
        if (parts.length == 1) {
            if (method == Method.GET) return handleListClassGroups(session);
            if (method == Method.POST) return handleCreateClassGroup(session);
        } else if (parts.length == 2) {
            String id = parts[1];
            if (method == Method.PUT) return handleUpdateClassGroup(session, id);
            if (method == Method.DELETE) return handleDeleteClassGroup(id);
        }
        return notFound();
    }

    /** Converts a {@link ClassGroup} entity into the JSON shape the web UI expects. */
    private static JSONObject classGroupJson(ClassGroup g) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", g.id);
        obj.put("disciplineId", g.disciplineId);
        obj.put("name", g.name);
        // A class closed together with its period is archived: it stays listed, but can't take attendance or new enrollments.
        obj.put("closed", g.isClosed());
        return obj;
    }

    /**
     * Handles GET /api/classgroups: returns all class groups, or only those belonging to a
     * specific discipline if a "disciplineId" query parameter is supplied.
     */
    private Response handleListClassGroups(IHTTPSession session) throws JSONException {
        String disciplineId = queryParam(session, "disciplineId");
        ArrayList<ClassGroup> list = disciplineId != null
                ? DatabaseSync.loadClassGroupsForDiscipline(database, disciplineId)
                : DatabaseSync.loadAllClassGroups(database);
        JSONArray array = new JSONArray();
        if (list != null) for (ClassGroup g : list) array.put(classGroupJson(g));
        return jsonResponse(Response.Status.OK, array);
    }

    /** Handles POST /api/classgroups: creates a new class group under the given discipline. */
    private Response handleCreateClassGroup(IHTTPSession session) throws Exception {
        JSONObject body = readJsonBody(session);
        String disciplineId = body.optString("disciplineId", "");
        String name = body.optString("name", "").trim();
        if (disciplineId.isEmpty() || name.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "disciplina e nome são obrigatórios");
        }

        ClassGroup group = new ClassGroup();
        group.disciplineId = disciplineId;
        group.name = name;
        DatabaseSync.Result<ClassGroup> result = DatabaseSync.saveClassGroup(database, group);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "falha ao salvar a turma");
        return jsonResponse(Response.Status.OK, classGroupJson(result.value));
    }

    /** Handles PUT /api/classgroups/{id}: updates an existing class group's discipline/name. */
    private Response handleUpdateClassGroup(IHTTPSession session, String id) throws Exception {
        JSONObject body = readJsonBody(session);
        String disciplineId = body.optString("disciplineId", "");
        String name = body.optString("name", "").trim();
        if (disciplineId.isEmpty() || name.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "disciplina e nome são obrigatórios");
        }

        ClassGroup group = new ClassGroup();
        group.id = id;
        group.disciplineId = disciplineId;
        group.name = name;
        DatabaseSync.Result<ClassGroup> result = DatabaseSync.saveClassGroup(database, group);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "falha ao salvar a turma");
        return jsonResponse(Response.Status.OK, classGroupJson(result.value));
    }

    /**
     * Handles DELETE /api/classgroups/{id}: deletes a class group unless students are still
     * enrolled in it, in which case it returns 409 Conflict (mirrors {@link #handleDeleteDiscipline}).
     */
    private Response handleDeleteClassGroup(String id) {
        DatabaseSync.Result<Void> result = DatabaseSync.deleteClassGroup(database, id);
        if (result.success) return jsonOk();
        if ("HAS_STUDENTS".equals(result.error)) return jsonError(Response.Status.CONFLICT, "HAS_STUDENTS");
        return jsonError(Response.Status.NOT_FOUND, "turma não encontrada");
    }

    /* ----------------------------------- Goals -------------------------------------- */

    /**
     * Dispatches all /api/goals... requests: list/create on the collection,
     * update/delete on a single goal by id. Same REST shape as {@link #routeStudents}.
     */
    private Response routeGoals(IHTTPSession session, Method method, String[] parts) throws Exception {
        if (parts.length == 1) {
            if (method == Method.GET) return handleListGoals(session);
            if (method == Method.POST) return handleSaveGoal(session, null);
        } else if (parts.length == 2) {
            String id = parts[1];
            if (method == Method.PUT) return handleSaveGoal(session, id);
            if (method == Method.DELETE) return handleDeleteGoal(id);
        }
        return notFound();
    }

    /**
     * Converts a {@link Goal} entity (a point target within a discipline and a window of days)
     * into JSON. {@code status} says where today falls in the window: "upcoming", "active",
     * "ended", or "open" for a goal that predates dates and so has none.
     */
    private static JSONObject goalJson(Goal g) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", g.id);
        obj.put("disciplineId", g.disciplineId);
        obj.put("name", g.name);
        obj.put("targetPoints", g.targetPoints);
        obj.put("startDate", g.startDate == null ? JSONObject.NULL : g.startDate);
        obj.put("endDate", g.endDate == null ? JSONObject.NULL : g.endDate);
        obj.put("status", GoalProgress.nameOf(GoalTracker.stateOf(g.startDate, g.endDate, Attendance.today())));
        return obj;
    }

    /** Handles GET /api/goals: returns the goals for a given discipline (required query parameter). */
    private Response handleListGoals(IHTTPSession session) throws JSONException {
        String disciplineId = queryParam(session, "disciplineId");
        if (disciplineId == null) return jsonError(Response.Status.BAD_REQUEST, "disciplina é obrigatória");
        ArrayList<Goal> list = DatabaseSync.loadGoalsForDiscipline(database, disciplineId);
        JSONArray array = new JSONArray();
        if (list != null) for (Goal g : list) array.put(goalJson(g));
        return jsonResponse(Response.Status.OK, array);
    }

    /**
     * Handles POST /api/goals (creates) and PUT /api/goals/{id} (updates): a goal is a discipline,
     * a name, the points to reach and the first and last day (both included, "yyyy-MM-dd") whose
     * points count. The two days are required — a goal without them, or one that ends before it
     * starts, is a 400.
     */
    private Response handleSaveGoal(IHTTPSession session, String id) throws Exception {
        JSONObject body = readJsonBody(session);
        String disciplineId = body.optString("disciplineId", "");
        String name = body.optString("name", "").trim();
        int targetPoints = body.optInt("targetPoints", 0);
        if (disciplineId.isEmpty() || name.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "disciplina e nome são obrigatórios");
        }

        Goal goal = new Goal();
        goal.id = id;
        goal.disciplineId = disciplineId;
        goal.name = name;
        goal.targetPoints = targetPoints;
        goal.startDate = body.optString("startDate", "").trim();
        goal.endDate = body.optString("endDate", "").trim();
        if (goal.startDate.isEmpty() || goal.endDate.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "informe as datas de início e de fim da meta");
        }
        if (!Attendance.isValidDay(goal.startDate) || !Attendance.isValidDay(goal.endDate)) {
            return jsonError(Response.Status.BAD_REQUEST, "as datas da meta devem ser dias válidos (AAAA-MM-DD)");
        }
        if (goal.startDate.compareTo(goal.endDate) > 0) {
            return jsonError(Response.Status.BAD_REQUEST, "o fim da meta não pode ser anterior ao início");
        }

        DatabaseSync.Result<Goal> result = DatabaseSync.saveGoal(database, goal);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "falha ao salvar a meta");
        return jsonResponse(Response.Status.OK, goalJson(result.value));
    }

    /** Handles DELETE /api/goals/{id}: removes a goal. */
    private Response handleDeleteGoal(String id) {
        boolean success = DatabaseSync.deleteGoal(database, id);
        return success ? jsonOk() : jsonError(Response.Status.NOT_FOUND, "meta não encontrada");
    }

    /* -------------------------------- Enrollments ------------------------------------ */

    /**
     * Dispatches all /api/enrollments... requests: list/create on the collection, delete by
     * id, and a nested "/points" sub-resource used to award or deduct points for one enrollment.
     */
    private Response routeEnrollments(IHTTPSession session, Method method, String[] parts) throws Exception {
        if (parts.length == 1) {
            if (method == Method.GET) return handleListEnrollments(session);
            if (method == Method.POST) return handleCreateEnrollment(session);
        } else if (parts.length == 2 && method == Method.DELETE) {
            return handleDeleteEnrollment(parts[1]);
        } else if (parts.length == 3 && parts[2].equals("points") && method == Method.POST) {
            // "/api/enrollments/{id}/points"
            return pointsApi.handleAward(session, parts[1]);
        }
        return notFound();
    }

    /** Converts an {@link Enrollment} entity (a student's membership in a class group, with grades/points) into JSON. */
    static JSONObject enrollmentJson(Enrollment e) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", e.id);
        obj.put("studentId", e.studentId);
        obj.put("classGroupId", e.classGroupId);
        obj.put("grades", e.grades);
        return obj;
    }

    /** Handles GET /api/enrollments: returns all enrollments for a given student (required query parameter). */
    private Response handleListEnrollments(IHTTPSession session) throws JSONException {
        String studentId = queryParam(session, "studentId");
        if (studentId == null) return jsonError(Response.Status.BAD_REQUEST, "aluno é obrigatório");
        ArrayList<Enrollment> list = DatabaseSync.loadEnrollmentsForStudent(database, studentId);
        JSONArray array = new JSONArray();
        if (list != null) for (Enrollment e : list) array.put(enrollmentJson(e));
        return jsonResponse(Response.Status.OK, array);
    }

    /** Handles POST /api/enrollments: enrolls a student into a class group, starting at 0 points. */
    private Response handleCreateEnrollment(IHTTPSession session) throws Exception {
        JSONObject body = readJsonBody(session);
        String studentId = body.optString("studentId", "");
        String classGroupId = body.optString("classGroupId", "");
        if (studentId.isEmpty() || classGroupId.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "aluno e turma são obrigatórios");
        }

        Enrollment enrollment = new Enrollment();
        enrollment.studentId = studentId;
        enrollment.classGroupId = classGroupId;
        enrollment.grades = 0;
        DatabaseSync.Result<Enrollment> result = DatabaseSync.saveEnrollment(database, enrollment);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "falha ao matricular o aluno");
        return jsonResponse(Response.Status.OK, enrollmentJson(result.value));
    }

    /** Handles DELETE /api/enrollments/{id}: removes a student's enrollment from a class group. */
    private Response handleDeleteEnrollment(String id) {
        boolean success = DatabaseSync.deleteEnrollment(database, id);
        return success ? jsonOk() : jsonError(Response.Status.NOT_FOUND, "matrícula não encontrada");
    }

    /* ---------------------------------- History -------------------------------------- */

    /** Handles GET /api/history: returns the points-change log for a given enrollment (required query parameter). */
    private Response handleHistory(IHTTPSession session) throws JSONException {
        String enrollmentId = queryParam(session, "enrollmentId");
        if (enrollmentId == null) return jsonError(Response.Status.BAD_REQUEST, "matrícula é obrigatória");

        ArrayList<PointsHistory> list = DatabaseSync.loadHistoryForEnrollment(database, enrollmentId);
        JSONArray array = new JSONArray();
        if (list != null) for (PointsHistory h : list) array.put(historyJson(h));
        return jsonResponse(Response.Status.OK, array);
    }

    /** Converts a {@link PointsHistory} entry (one point-award event) into JSON. */
    private static JSONObject historyJson(PointsHistory h) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", h.id);
        obj.put("enrollmentId", h.enrollmentId);
        obj.put("pointsDelta", h.pointsDelta);
        obj.put("note", h.note == null ? "" : h.note);
        obj.put("tag", h.tag == null ? "" : h.tag);
        obj.put("revertsId", h.revertsId == null ? "" : h.revertsId);
        obj.put("createdAt", h.createdAt);
        return obj;
    }

    /* ---------------------------------- Export ---------------------------------------- */

    /**
     * Handles GET /api/export: builds and streams a downloadable export file (JSON, Markdown, or
     * PDF, selected via the "format" query parameter) covering either all students or a specific
     * subset (comma-separated ids in the "studentIds" query parameter).
     */
    private Response handleExport(IHTTPSession session) {
        String format = queryParam(session, "format");
        if (format == null) format = "json";
        String studentIdsParam = queryParam(session, "studentIds");
        List<String> studentIds = (studentIdsParam != null && !studentIdsParam.isEmpty())
                ? Arrays.asList(studentIdsParam.split(","))
                : null;

        List<StudentExportData> data = DatabaseSync.loadExportData(database, studentIds);
        try {
            File file;
            String mime;
            switch (format) {
                case "pdf":
                    file = Exporter.exportPdf(context, data);
                    mime = "application/pdf";
                    break;
                case "md":
                    file = Exporter.exportMarkdown(context, data);
                    mime = "text/markdown";
                    break;
                default:
                    file = Exporter.exportJson(context, data);
                    mime = "application/json";
                    break;
            }

            Response response = newFixedLengthResponse(Response.Status.OK, mime, new FileInputStream(file), file.length());
            // Content-Disposition: attachment tells the browser to download the file (with the
            // given filename) instead of trying to display it inline.
            response.addHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
            return response;
        } catch (Exception e) {
            return jsonError(Response.Status.INTERNAL_ERROR, "falha ao exportar");
        }
    }

    /* ---------------------------------- Import ---------------------------------------- */

    /**
     * Handles POST /api/import/json: accepts a multipart file upload containing a previously
     * exported JSON file and merges it into the database. Before importing, takes a full
     * snapshot/backup of the current database so the import can be undone if something goes wrong.
     */
    private Response handleImportJson(IHTTPSession session) throws Exception {
        // As with photo upload, NanoHTTPD writes the uploaded file to a temp path and reports
        // that path back to us keyed by the form field name ("file").
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        String tempPath = files.get("file");
        if (tempPath == null) return jsonError(Response.Status.BAD_REQUEST, "selecione um arquivo");

        // Safety net: back up the whole database before mutating it with imported data.
        boolean snapshotOk = DatabaseSync.createFullSnapshot(context, database, false);

        List<StudentExportData> data;
        try {
            data = Importer.parseJsonArray(readFileToString(new File(tempPath)));
        } catch (JSONException e) {
            return jsonError(Response.Status.BAD_REQUEST, "arquivo JSON inválido: " + e.getMessage());
        }
        if (data.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "o arquivo não tem dados para importar");

        // Figure out, before importing, which incoming students already exist (by id) versus
        // which are brand new, purely to report accurate counts back to the caller.
        Set<String> existingIds = new HashSet<>();
        ArrayList<Student> existingStudents = DatabaseSync.loadAllStudents(database);
        if (existingStudents != null) for (Student s : existingStudents) existingIds.add(s.id);

        Set<String> incomingIds = new HashSet<>();
        for (StudentExportData s : data) if (s.studentId != null) incomingIds.add(s.studentId);
        int existingCount = 0;
        for (String id : incomingIds) if (existingIds.contains(id)) existingCount++;
        int newCount = incomingIds.size() - existingCount;

        boolean success = DatabaseSync.importExportData(database, data);

        JSONObject result = new JSONObject();
        result.put("success", success);
        result.put("newCount", newCount);
        result.put("existingCount", existingCount);
        result.put("snapshotCreated", snapshotOk);
        return jsonResponse(Response.Status.OK, result);
    }

    /**
     * Handles POST /api/import/csv: accepts a multipart file upload containing a CSV of
     * students and imports the rows that can be resolved/matched, reporting any per-row errors.
     * Also takes a database snapshot first, same as {@link #handleImportJson}.
     */
    private Response handleImportCsv(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        String tempPath = files.get("file");
        if (tempPath == null) return jsonError(Response.Status.BAD_REQUEST, "selecione um arquivo");

        boolean snapshotOk = DatabaseSync.createFullSnapshot(context, database, false);

        List<CsvStudentRow> rows;
        // try-with-resources ensures the FileInputStream is closed automatically once parsing finishes or fails.
        try (FileInputStream in = new FileInputStream(tempPath)) {
            rows = CsvImporter.parse(in);
        } catch (CsvImporter.CsvFormatException e) {
            return jsonError(Response.Status.BAD_REQUEST, "arquivo CSV inválido: " + e.getMessage());
        }
        if (rows.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "nenhuma linha válida para importar");

        // resolveCsvRows matches each row against existing students/class groups and separates
        // rows that can be imported from ones with problems (collected in plan.errors).
        CsvImportPlan plan = DatabaseSync.resolveCsvRows(database, rows);
        if (plan.resolved.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "nenhuma linha válida para importar");

        boolean success = DatabaseSync.importCsvRows(database, plan.resolved);

        JSONObject result = new JSONObject();
        result.put("success", success);
        result.put("newCount", plan.newStudentCount());
        result.put("matchedCount", plan.matchedStudentCount());
        result.put("newDisciplineCount", plan.newDisciplineCount());
        result.put("newClassGroupCount", plan.newClassGroupCount());
        // Report every row that couldn't be imported, with its line number and reason, so the
        // teacher can fix the CSV and re-upload just the problem rows.
        JSONArray errors = new JSONArray();
        for (CsvRowError error : plan.errors) {
            JSONObject errObj = new JSONObject();
            errObj.put("line", error.lineNumber);
            errObj.put("reason", error.reason);
            errors.put(errObj);
        }
        result.put("errors", errors);
        result.put("snapshotCreated", snapshotOk);
        return jsonResponse(Response.Status.OK, result);
    }

    /** Reads an entire file into a UTF-8 String, used for JSON bodies that NanoHTTPD stashed on disk. */
    private static String readFileToString(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            // Read in fixed-size chunks until the stream is exhausted (read() returns -1 at EOF).
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /* ---------------------------------- Helpers ---------------------------------------- */

    /**
     * Reads the first value of a URL query-string parameter, or null if it wasn't supplied.
     * {@code IHTTPSession.getParms()} is deprecated in favor of {@code getParameters()}, which returns one list per key.
     */
    static String queryParam(IHTTPSession session, String key) {
        List<String> values = session.getParameters().get(key);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    /** Serves a file bundled in the app's assets/ folder as a static HTTP response (used for the web UI itself). */
    private Response serveAsset(String assetPath, String mimeType) {
        try {
            InputStream in = context.getAssets().open(assetPath);
            Response response = newFixedLengthResponse(Response.Status.OK, mimeType, in, in.available());
            // The app (and so its pages) can be updated under a browser that still has the old ones open; make it re-check.
            response.addHeader("Cache-Control", "no-cache");
            return response;
        } catch (IOException e) {
            return notFound();
        }
    }

    /**
     * Reads and parses the JSON body of a request, regardless of whether it arrived via POST or PUT.
     * NanoHTTPD only stuffs the raw body into the {@code "postData"} entry for POST requests;
     * for PUT it writes the body to a temp file referenced by the {@code "content"} entry instead.
     * Every JSON-bodied endpoint (both POST and PUT) reads through here so both cases work.
     *
     * <p>Every POST/PUT handler must call this, even one that ignores the body. NanoHTTPD keeps the
     * connection open for the next request and only skips the body when it has been read; a body left
     * behind is taken for the start of that next request, which then fails with "400 Bad Request"
     * (the browser's refresh right after a "cancel entry" POST hit exactly that).
     */
    static JSONObject readJsonBody(IHTTPSession session) throws IOException, ResponseException, JSONException {
        // NanoHTTPD decodes a POST body as US-ASCII unless the Content-Type names a charset, which would turn
        // "Participação" into "Participa????o" before this code ever sees it. JSON is UTF-8, and browsers'
        // fetch() doesn't add the charset by itself, so say it here (header names are lower-cased by NanoHTTPD).
        Map<String, String> headers = session.getHeaders();
        String contentType = headers.get("content-type");
        if (contentType != null && !contentType.toLowerCase(Locale.ROOT).contains("charset")) {
            headers.put("content-type", contentType + "; charset=UTF-8");
        }

        Map<String, String> body = new HashMap<>();
        session.parseBody(body);
        String content = body.get("postData");
        if (content == null) {
            String tempPath = body.get("content");
            if (tempPath != null) content = readFileToString(new File(tempPath));
        }
        // An empty/missing body is treated as an empty JSON object rather than a parse error,
        // so handlers can just call optString/optInt with sensible defaults.
        return new JSONObject(content == null || content.isEmpty() ? "{}" : content);
    }

    /** Wraps any JSON-serializable value into a fixed-length "application/json" HTTP response. */
    static Response jsonResponse(Response.Status status, Object body) {
        return newFixedLengthResponse(status, "application/json", body.toString());
    }

    /** Shorthand for a 200 OK response with body {"success": true}. */
    private static Response jsonOk() {
        try {
            return jsonResponse(Response.Status.OK, new JSONObject().put("success", true));
        } catch (JSONException e) {
            // JSONException here would only happen for null keys, which never happens with a
            // literal key like "success"; the fallback just guards against the checked exception.
            return jsonResponse(Response.Status.OK, "{\"success\":true}");
        }
    }

    /** Shorthand for an error response with the given HTTP status and a JSON {"error": message} body. */
    static Response jsonError(Response.Status status, String message) {
        try {
            return jsonResponse(status, new JSONObject().put("error", message));
        } catch (JSONException e) {
            return jsonResponse(status, "{\"error\":\"error\"}");
        }
    }

    /** Shorthand for a 404 Not Found JSON error response, used throughout the routing methods. */
    static Response notFound() {
        return jsonError(Response.Status.NOT_FOUND, "não encontrado");
    }
}
