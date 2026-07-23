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

import org.dedira.qrnotas.model.BathroomVisit;
import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.CsvImportPlan;
import org.dedira.qrnotas.model.CsvRowError;
import org.dedira.qrnotas.model.CsvStudentRow;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Enrollment;
import org.dedira.qrnotas.model.Goal;
import org.dedira.qrnotas.model.IndisciplineEvent;
import org.dedira.qrnotas.model.PointsHistory;
import org.dedira.qrnotas.model.Student;
import org.dedira.qrnotas.model.StudentExportData;
import org.dedira.qrnotas.util.BitmapConverter;
import org.dedira.qrnotas.util.CsvImporter;
import org.dedira.qrnotas.util.Database;
import org.dedira.qrnotas.util.DatabaseSync;
import org.dedira.qrnotas.util.Exporter;
import org.dedira.qrnotas.util.Importer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
    // Set of currently-valid session tokens (one per logged-in browser). Wrapped with
    // Collections.synchronizedSet because multiple request-handling threads can read/write it concurrently.
    private final Set<String> validTokens = Collections.synchronizedSet(new HashSet<>());
    // Per-IP count of consecutive failed login attempts, used for the lockout logic below.
    private final Map<String, int[]> failCounts = Collections.synchronizedMap(new HashMap<>());
    // Per-IP timestamp (millis since epoch) until which further login attempts are rejected outright.
    private final Map<String, Long> lockUntil = Collections.synchronizedMap(new HashMap<>());

    public WebServer(Context context, Database database, String password) {
        super(PORT);
        // Always store the application Context (not an Activity/Service Context) to avoid
        // accidentally keeping a short-lived component alive/leaking memory.
        this.context = context.getApplicationContext();
        this.database = database;
        this.password = password;
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

            // Everything else must be a JSON API call under /api/; anything else is unknown.
            if (!uri.startsWith("/api/")) return notFound();
            // Login is the only /api/ endpoint reachable without an existing session.
            if (uri.equals("/api/login") && method == Method.POST) return handleLogin(session);

            // Every other API call must present a valid session cookie obtained from /api/login.
            String token = readSessionToken(session);
            if (token == null || !validTokens.contains(token)) {
                return jsonError(Response.Status.UNAUTHORIZED, "unauthorized");
            }

            if (uri.equals("/api/logout") && method == Method.POST) {
                validTokens.remove(token);
                return jsonOk();
            }

            return route(session, uri, method);
        } catch (Exception e) {
            // Catch-all so an unexpected exception in any handler becomes a proper HTTP 500
            // JSON response instead of crashing the worker thread or leaking a raw stack trace.
            return jsonError(Response.Status.INTERNAL_ERROR, e.getMessage() == null ? "server error" : e.getMessage());
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
                if (method == Method.GET) return handleHistory(session);
                break;
            case "bathroom":
                return routeBathroom(session, method, parts);
            case "indiscipline":
                return routeIndiscipline(session, method, parts);
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
            return jsonError(Response.Status.FORBIDDEN, "too many attempts, try again later");
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
        return jsonError(Response.Status.UNAUTHORIZED, "invalid password");
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
        }
        return notFound();
    }

    /** Converts a {@link Student} entity into the small JSON shape the web UI expects. */
    private static JSONObject studentJson(Student s) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", s.id);
        obj.put("name", s.name);
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
        if (name.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "name is required");

        Student student = new Student();
        student.name = name;
        DatabaseSync.Result<Student> result = DatabaseSync.saveStudent(database, student);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "failed to save student");
        return jsonResponse(Response.Status.OK, studentJson(result.value));
    }

    /** Handles PUT /api/students/{id}: renames an existing student. */
    private Response handleUpdateStudent(IHTTPSession session, String id) throws Exception {
        Student existing = DatabaseSync.loadStudent(database, id);
        if (existing == null) return notFound();

        JSONObject body = readJsonBody(session);
        // Falls back to the existing name if the field is missing, so a partial update doesn't wipe it out.
        String name = body.optString("name", existing.name).trim();
        if (name.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "name is required");
        existing.name = name;

        DatabaseSync.Result<Student> result = DatabaseSync.saveStudent(database, existing);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "failed to save student");
        return jsonResponse(Response.Status.OK, studentJson(result.value));
    }

    /** Handles DELETE /api/students/{id}: removes a student. */
    private Response handleDeleteStudent(String id) {
        boolean success = DatabaseSync.deleteStudent(database, id);
        return success ? jsonOk() : jsonError(Response.Status.NOT_FOUND, "student not found");
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
        if (tempPath == null) return jsonError(Response.Status.BAD_REQUEST, "photo file is required");

        Bitmap bitmap = BitmapFactory.decodeFile(tempPath);
        if (bitmap == null) return jsonError(Response.Status.BAD_REQUEST, "invalid image file");

        String savedPath = BitmapConverter.saveStudentPhoto(context, bitmap, id);
        if (savedPath == null) return jsonError(Response.Status.INTERNAL_ERROR, "failed to save photo");

        student.photoPath = savedPath;
        DatabaseSync.Result<Student> result = DatabaseSync.saveStudent(database, student);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "failed to save student");
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
        if (name.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "name is required");

        Discipline discipline = new Discipline();
        discipline.name = name;
        DatabaseSync.Result<Discipline> result = DatabaseSync.saveDiscipline(database, discipline);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "failed to save discipline");
        return jsonResponse(Response.Status.OK, disciplineJson(result.value));
    }

    /** Handles PUT /api/disciplines/{id}: renames an existing discipline. */
    private Response handleUpdateDiscipline(IHTTPSession session, String id) throws Exception {
        JSONObject body = readJsonBody(session);
        String name = body.optString("name", "").trim();
        if (name.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "name is required");

        Discipline discipline = new Discipline();
        discipline.id = id;
        discipline.name = name;
        DatabaseSync.Result<Discipline> result = DatabaseSync.saveDiscipline(database, discipline);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "failed to save discipline");
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
        return jsonError(Response.Status.NOT_FOUND, "discipline not found");
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
            return jsonError(Response.Status.BAD_REQUEST, "disciplineId and name are required");
        }

        ClassGroup group = new ClassGroup();
        group.disciplineId = disciplineId;
        group.name = name;
        DatabaseSync.Result<ClassGroup> result = DatabaseSync.saveClassGroup(database, group);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "failed to save class group");
        return jsonResponse(Response.Status.OK, classGroupJson(result.value));
    }

    /** Handles PUT /api/classgroups/{id}: updates an existing class group's discipline/name. */
    private Response handleUpdateClassGroup(IHTTPSession session, String id) throws Exception {
        JSONObject body = readJsonBody(session);
        String disciplineId = body.optString("disciplineId", "");
        String name = body.optString("name", "").trim();
        if (disciplineId.isEmpty() || name.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "disciplineId and name are required");
        }

        ClassGroup group = new ClassGroup();
        group.id = id;
        group.disciplineId = disciplineId;
        group.name = name;
        DatabaseSync.Result<ClassGroup> result = DatabaseSync.saveClassGroup(database, group);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "failed to save class group");
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
        return jsonError(Response.Status.NOT_FOUND, "class group not found");
    }

    /* ----------------------------------- Goals -------------------------------------- */

    /**
     * Dispatches all /api/goals... requests: list/create on the collection,
     * update/delete on a single goal by id. Same REST shape as {@link #routeStudents}.
     */
    private Response routeGoals(IHTTPSession session, Method method, String[] parts) throws Exception {
        if (parts.length == 1) {
            if (method == Method.GET) return handleListGoals(session);
            if (method == Method.POST) return handleCreateGoal(session);
        } else if (parts.length == 2) {
            String id = parts[1];
            if (method == Method.PUT) return handleUpdateGoal(session, id);
            if (method == Method.DELETE) return handleDeleteGoal(id);
        }
        return notFound();
    }

    /** Converts a {@link Goal} entity (a point target within a discipline) into JSON. */
    private static JSONObject goalJson(Goal g) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", g.id);
        obj.put("disciplineId", g.disciplineId);
        obj.put("name", g.name);
        obj.put("targetPoints", g.targetPoints);
        return obj;
    }

    /** Handles GET /api/goals: returns the goals for a given discipline (required query parameter). */
    private Response handleListGoals(IHTTPSession session) throws JSONException {
        String disciplineId = queryParam(session, "disciplineId");
        if (disciplineId == null) return jsonError(Response.Status.BAD_REQUEST, "disciplineId is required");
        ArrayList<Goal> list = DatabaseSync.loadGoalsForDiscipline(database, disciplineId);
        JSONArray array = new JSONArray();
        if (list != null) for (Goal g : list) array.put(goalJson(g));
        return jsonResponse(Response.Status.OK, array);
    }

    /** Handles POST /api/goals: creates a new goal (name + target points) under a discipline. */
    private Response handleCreateGoal(IHTTPSession session) throws Exception {
        JSONObject body = readJsonBody(session);
        String disciplineId = body.optString("disciplineId", "");
        String name = body.optString("name", "").trim();
        int targetPoints = body.optInt("targetPoints", 0);
        if (disciplineId.isEmpty() || name.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "disciplineId and name are required");
        }

        Goal goal = new Goal();
        goal.disciplineId = disciplineId;
        goal.name = name;
        goal.targetPoints = targetPoints;
        DatabaseSync.Result<Goal> result = DatabaseSync.saveGoal(database, goal);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "failed to save goal");
        return jsonResponse(Response.Status.OK, goalJson(result.value));
    }

    /** Handles PUT /api/goals/{id}: updates an existing goal's discipline/name/target points. */
    private Response handleUpdateGoal(IHTTPSession session, String id) throws Exception {
        JSONObject body = readJsonBody(session);
        String disciplineId = body.optString("disciplineId", "");
        String name = body.optString("name", "").trim();
        int targetPoints = body.optInt("targetPoints", 0);
        if (disciplineId.isEmpty() || name.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "disciplineId and name are required");
        }

        Goal goal = new Goal();
        goal.id = id;
        goal.disciplineId = disciplineId;
        goal.name = name;
        goal.targetPoints = targetPoints;
        DatabaseSync.Result<Goal> result = DatabaseSync.saveGoal(database, goal);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "failed to save goal");
        return jsonResponse(Response.Status.OK, goalJson(result.value));
    }

    /** Handles DELETE /api/goals/{id}: removes a goal. */
    private Response handleDeleteGoal(String id) {
        boolean success = DatabaseSync.deleteGoal(database, id);
        return success ? jsonOk() : jsonError(Response.Status.NOT_FOUND, "goal not found");
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
            return handleAwardPoints(session, parts[1]);
        }
        return notFound();
    }

    /** Converts an {@link Enrollment} entity (a student's membership in a class group, with grades/points) into JSON. */
    private static JSONObject enrollmentJson(Enrollment e) throws JSONException {
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
        if (studentId == null) return jsonError(Response.Status.BAD_REQUEST, "studentId is required");
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
            return jsonError(Response.Status.BAD_REQUEST, "studentId and classGroupId are required");
        }

        Enrollment enrollment = new Enrollment();
        enrollment.studentId = studentId;
        enrollment.classGroupId = classGroupId;
        enrollment.grades = 0;
        DatabaseSync.Result<Enrollment> result = DatabaseSync.saveEnrollment(database, enrollment);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "failed to enroll student");
        return jsonResponse(Response.Status.OK, enrollmentJson(result.value));
    }

    /** Handles DELETE /api/enrollments/{id}: removes a student's enrollment from a class group. */
    private Response handleDeleteEnrollment(String id) {
        boolean success = DatabaseSync.deleteEnrollment(database, id);
        return success ? jsonOk() : jsonError(Response.Status.NOT_FOUND, "enrollment not found");
    }

    /**
     * Handles POST /api/enrollments/{id}/points: applies a point delta (positive or negative) to
     * an enrollment and records it in the points history log with an optional note.
     * Mirrors {@code activities/Main.java#onNoteConfirmed}: delta-then-history, never a raw grades setter.
     */
    private Response handleAwardPoints(IHTTPSession session, String enrollmentId) throws Exception {
        JSONObject body = readJsonBody(session);
        int delta = body.optInt("delta", 0);
        if (delta == 0) return jsonError(Response.Status.BAD_REQUEST, "delta must be non-zero");
        String note = body.optString("note", "");

        Enrollment enrollment = DatabaseSync.loadEnrollmentById(database, enrollmentId);
        if (enrollment == null) return notFound();

        // Points are always applied as a relative change (+N/-N) on top of the current total,
        // never overwritten wholesale, so the history log stays an accurate audit trail.
        int newGrades = enrollment.grades + delta;
        DatabaseSync.Result<Enrollment> updateResult = DatabaseSync.updateEnrollmentGrades(database, enrollmentId, newGrades);
        if (!updateResult.success) return jsonError(Response.Status.INTERNAL_ERROR, "failed to update points");

        // Record this change as a new history entry so it shows up in the enrollment's timeline.
        PointsHistory history = new PointsHistory();
        history.enrollmentId = enrollmentId;
        history.pointsDelta = delta;
        history.note = note;
        history.createdAt = System.currentTimeMillis();
        DatabaseSync.savePointsHistory(database, history);

        return jsonResponse(Response.Status.OK, enrollmentJson(updateResult.value));
    }

    /* ---------------------------------- History -------------------------------------- */

    /** Handles GET /api/history: returns the points-change log for a given enrollment (required query parameter). */
    private Response handleHistory(IHTTPSession session) throws JSONException {
        String enrollmentId = queryParam(session, "enrollmentId");
        if (enrollmentId == null) return jsonError(Response.Status.BAD_REQUEST, "enrollmentId is required");

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
        obj.put("createdAt", h.createdAt);
        return obj;
    }

    /* ------------------------------- Bathroom visits ------------------------------------ */

    /**
     * Dispatches all /api/bathroom... requests: list every visit / start a new one on the
     * collection, and a nested "/return" sub-resource (keyed by student id, not visit id, since
     * a student has at most one open visit at a time) to close it out.
     */
    private Response routeBathroom(IHTTPSession session, Method method, String[] parts) throws Exception {
        if (parts.length == 1) {
            if (method == Method.GET) return handleListBathroomVisits();
            if (method == Method.POST) return handleStartBathroomVisit(session);
        } else if (parts.length == 3 && parts[2].equals("return") && method == Method.POST) {
            // "/api/bathroom/{studentId}/return"
            return handleEndBathroomVisit(parts[1]);
        }
        return notFound();
    }

    /** Converts a {@link BathroomVisit} entity into the JSON shape the web UI expects. */
    private static JSONObject bathroomVisitJson(BathroomVisit v) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", v.id);
        obj.put("studentId", v.studentId);
        obj.put("wentAt", v.wentAt);
        obj.put("returnedAt", v.returnedAt == null ? JSONObject.NULL : v.returnedAt);
        obj.put("evaded", v.evaded);
        return obj;
    }

    /** Handles GET /api/bathroom: returns every bathroom visit ever recorded, most recent first. */
    private Response handleListBathroomVisits() throws JSONException {
        ArrayList<BathroomVisit> list = DatabaseSync.loadAllBathroomVisits(database);
        JSONArray array = new JSONArray();
        if (list != null) for (BathroomVisit v : list) array.put(bathroomVisitJson(v));
        return jsonResponse(Response.Status.OK, array);
    }

    /** Handles POST /api/bathroom: starts a bathroom visit for the student in the JSON body's "studentId" field. */
    private Response handleStartBathroomVisit(IHTTPSession session) throws Exception {
        JSONObject body = readJsonBody(session);
        String studentId = body.optString("studentId", "");
        if (studentId.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "studentId is required");

        DatabaseSync.Result<BathroomVisit> result = DatabaseSync.startBathroomVisit(database, studentId);
        if (!result.success) return jsonError(Response.Status.CONFLICT, "student already has an open bathroom visit");
        return jsonResponse(Response.Status.OK, bathroomVisitJson(result.value));
    }

    /** Handles POST /api/bathroom/{studentId}/return: closes that student's open bathroom visit. */
    private Response handleEndBathroomVisit(String studentId) throws JSONException {
        DatabaseSync.Result<BathroomVisit> result = DatabaseSync.endBathroomVisit(database, studentId);
        if (!result.success) return jsonError(Response.Status.NOT_FOUND, "student has no open bathroom visit");
        return jsonResponse(Response.Status.OK, bathroomVisitJson(result.value));
    }

    /* ------------------------------ Indiscipline events ---------------------------------- */

    /** Dispatches all /api/indiscipline... requests: list/create on the collection. */
    private Response routeIndiscipline(IHTTPSession session, Method method, String[] parts) throws Exception {
        if (parts.length == 1) {
            if (method == Method.GET) return handleListIndisciplineEvents();
            if (method == Method.POST) return handleCreateIndisciplineEvent(session);
        }
        return notFound();
    }

    /** Converts an {@link IndisciplineEvent} entity into the JSON shape the web UI expects. */
    private static JSONObject indisciplineEventJson(IndisciplineEvent e) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", e.id);
        obj.put("studentId", e.studentId);
        obj.put("disciplineId", e.disciplineId == null ? JSONObject.NULL : e.disciplineId);
        obj.put("note", e.note == null ? "" : e.note);
        obj.put("createdAt", e.createdAt);
        return obj;
    }

    /** Handles GET /api/indiscipline: returns every indiscipline record ever registered, most recent first. */
    private Response handleListIndisciplineEvents() throws JSONException {
        ArrayList<IndisciplineEvent> list = DatabaseSync.loadAllIndisciplineEvents(database);
        JSONArray array = new JSONArray();
        if (list != null) for (IndisciplineEvent e : list) array.put(indisciplineEventJson(e));
        return jsonResponse(Response.Status.OK, array);
    }

    /** Handles POST /api/indiscipline: registers a new indiscipline record from the JSON body's "studentId" (required), "disciplineId" and "note" (both optional). */
    private Response handleCreateIndisciplineEvent(IHTTPSession session) throws Exception {
        JSONObject body = readJsonBody(session);
        String studentId = body.optString("studentId", "");
        if (studentId.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "studentId is required");

        IndisciplineEvent event = new IndisciplineEvent();
        event.studentId = studentId;
        event.disciplineId = body.isNull("disciplineId") ? null : body.optString("disciplineId", null);
        event.note = body.optString("note", "");
        event.createdAt = System.currentTimeMillis();

        DatabaseSync.Result<IndisciplineEvent> result = DatabaseSync.saveIndisciplineEvent(database, event);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "failed to save indiscipline record");
        return jsonResponse(Response.Status.OK, indisciplineEventJson(result.value));
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
            return jsonError(Response.Status.INTERNAL_ERROR, "failed to export");
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
        if (tempPath == null) return jsonError(Response.Status.BAD_REQUEST, "file is required");

        // Safety net: back up the whole database before mutating it with imported data.
        boolean snapshotOk = DatabaseSync.createFullSnapshot(context, database, false);

        List<StudentExportData> data;
        try {
            data = Importer.parseJsonArray(readFileToString(new File(tempPath)));
        } catch (JSONException e) {
            return jsonError(Response.Status.BAD_REQUEST, "invalid JSON file: " + e.getMessage());
        }
        if (data.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "file has no data to import");

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
        if (tempPath == null) return jsonError(Response.Status.BAD_REQUEST, "file is required");

        boolean snapshotOk = DatabaseSync.createFullSnapshot(context, database, false);

        List<CsvStudentRow> rows;
        // try-with-resources ensures the FileInputStream is closed automatically once parsing finishes or fails.
        try (FileInputStream in = new FileInputStream(tempPath)) {
            rows = CsvImporter.parse(in);
        } catch (CsvImporter.CsvFormatException e) {
            return jsonError(Response.Status.BAD_REQUEST, "invalid CSV file: " + e.getMessage());
        }
        if (rows.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "no valid rows to import");

        // resolveCsvRows matches each row against existing students/class groups and separates
        // rows that can be imported from ones with problems (collected in plan.errors).
        CsvImportPlan plan = DatabaseSync.resolveCsvRows(database, rows);
        if (plan.resolved.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "no valid rows to import");

        boolean success = DatabaseSync.importCsvRows(database, plan.resolved);

        JSONObject result = new JSONObject();
        result.put("success", success);
        result.put("newCount", plan.newStudentCount());
        result.put("matchedCount", plan.matchedStudentCount());
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
    private static String queryParam(IHTTPSession session, String key) {
        List<String> values = session.getParameters().get(key);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    /** Serves a file bundled in the app's assets/ folder as a static HTTP response (used for the web UI itself). */
    private Response serveAsset(String assetPath, String mimeType) {
        try {
            InputStream in = context.getAssets().open(assetPath);
            return newFixedLengthResponse(Response.Status.OK, mimeType, in, in.available());
        } catch (IOException e) {
            return notFound();
        }
    }

    /**
     * Reads and parses the JSON body of a request, regardless of whether it arrived via POST or PUT.
     * NanoHTTPD only stuffs the raw body into the {@code "postData"} entry for POST requests;
     * for PUT it writes the body to a temp file referenced by the {@code "content"} entry instead.
     * Every JSON-bodied endpoint (both POST and PUT) reads through here so both cases work.
     */
    private JSONObject readJsonBody(IHTTPSession session) throws IOException, ResponseException, JSONException {
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
    private static Response jsonResponse(Response.Status status, Object body) {
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
    private static Response jsonError(Response.Status status, String message) {
        try {
            return jsonResponse(status, new JSONObject().put("error", message));
        } catch (JSONException e) {
            return jsonResponse(status, "{\"error\":\"error\"}");
        }
    }

    /** Shorthand for a 404 Not Found JSON error response, used throughout the routing methods. */
    private static Response notFound() {
        return jsonError(Response.Status.NOT_FOUND, "not found");
    }
}
