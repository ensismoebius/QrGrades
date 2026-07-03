package org.dedira.qrnotas.services;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.dedira.qrnotas.model.ClassGroup;
import org.dedira.qrnotas.model.CsvImportPlan;
import org.dedira.qrnotas.model.CsvRowError;
import org.dedira.qrnotas.model.CsvStudentRow;
import org.dedira.qrnotas.model.Discipline;
import org.dedira.qrnotas.model.Enrollment;
import org.dedira.qrnotas.model.Goal;
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
 */
public class WebServer extends NanoHTTPD {

    public static final int PORT = 8080;

    private static final String COOKIE_NAME = "qrgrades_session";
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOCKOUT_MS = 60_000L;

    private final Context context;
    private final Database database;
    private final String password;
    private final Set<String> validTokens = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, int[]> failCounts = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, Long> lockUntil = Collections.synchronizedMap(new HashMap<>());

    public WebServer(Context context, Database database, String password) {
        super(PORT);
        this.context = context.getApplicationContext();
        this.database = database;
        this.password = password;
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            String uri = session.getUri();
            Method method = session.getMethod();

            if (uri.equals("/") || uri.equals("/index.html")) return serveAsset("web/index.html", "text/html");
            if (uri.equals("/app.css")) return serveAsset("web/app.css", "text/css");
            if (uri.equals("/app.js")) return serveAsset("web/app.js", "application/javascript");
            if (uri.equals("/favicon.svg")) return serveAsset("web/favicon.svg", "image/svg+xml");

            if (!uri.startsWith("/api/")) return notFound();
            if (uri.equals("/api/login") && method == Method.POST) return handleLogin(session);

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
            return jsonError(Response.Status.INTERNAL_ERROR, e.getMessage() == null ? "server error" : e.getMessage());
        }
    }

    private Response route(IHTTPSession session, String uri, Method method) throws Exception {
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
            case "export":
                if (method == Method.GET) return handleExport(session);
                break;
            case "import":
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

    private Response handleLogin(IHTTPSession session) throws IOException, ResponseException, JSONException {
        String ip = session.getRemoteIpAddress();
        Long locked = lockUntil.get(ip);
        if (locked != null && locked > System.currentTimeMillis()) {
            return jsonError(Response.Status.FORBIDDEN, "too many attempts, try again later");
        }

        JSONObject body = readJsonBody(session);
        String submitted = body.optString("password", "");

        if (password != null && password.equals(submitted)) {
            failCounts.remove(ip);
            lockUntil.remove(ip);
            String token = UUID.randomUUID().toString();
            validTokens.add(token);

            Response response = jsonOk();
            response.addHeader("Set-Cookie", COOKIE_NAME + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
            return response;
        }

        int[] count = failCounts.get(ip);
        if (count == null) {
            count = new int[1];
            failCounts.put(ip, count);
        }
        count[0]++;
        if (count[0] >= MAX_LOGIN_ATTEMPTS) {
            lockUntil.put(ip, System.currentTimeMillis() + LOCKOUT_MS);
            failCounts.remove(ip);
        }
        return jsonError(Response.Status.UNAUTHORIZED, "invalid password");
    }

    private String readSessionToken(IHTTPSession session) {
        String cookieHeader = session.getHeaders().get("cookie");
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(COOKIE_NAME)) return kv[1];
        }
        return null;
    }

    /* -------------------------------- Overview ----------------------------------- */

    private Response handleOverview() throws JSONException {
        List<StudentExportData> data = DatabaseSync.loadExportData(database, null);
        return jsonResponse(Response.Status.OK, Exporter.buildJsonArray(data));
    }

    /* -------------------------------- Students ------------------------------------ */

    private Response routeStudents(IHTTPSession session, Method method, String[] parts) throws Exception {
        if (parts.length == 1) {
            if (method == Method.GET) return handleListStudents();
            if (method == Method.POST) return handleCreateStudent(session);
        } else if (parts.length == 2) {
            String id = parts[1];
            if (method == Method.PUT) return handleUpdateStudent(session, id);
            if (method == Method.DELETE) return handleDeleteStudent(id);
        } else if (parts.length == 3 && parts[2].equals("photo")) {
            String id = parts[1];
            if (method == Method.GET) return handleGetPhoto(id);
            if (method == Method.POST) return handleUploadPhoto(session, id);
        }
        return notFound();
    }

    private static JSONObject studentJson(Student s) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", s.id);
        obj.put("name", s.name);
        obj.put("hasPhoto", s.photoPath != null);
        return obj;
    }

    private Response handleListStudents() throws JSONException {
        ArrayList<Student> students = DatabaseSync.loadAllStudents(database);
        JSONArray array = new JSONArray();
        if (students != null) for (Student s : students) array.put(studentJson(s));
        return jsonResponse(Response.Status.OK, array);
    }

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

    private Response handleUpdateStudent(IHTTPSession session, String id) throws Exception {
        Student existing = DatabaseSync.loadStudent(database, id);
        if (existing == null) return notFound();

        JSONObject body = readJsonBody(session);
        String name = body.optString("name", existing.name).trim();
        if (name.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "name is required");
        existing.name = name;

        DatabaseSync.Result<Student> result = DatabaseSync.saveStudent(database, existing);
        if (!result.success) return jsonError(Response.Status.INTERNAL_ERROR, "failed to save student");
        return jsonResponse(Response.Status.OK, studentJson(result.value));
    }

    private Response handleDeleteStudent(String id) {
        boolean success = DatabaseSync.deleteStudent(database, id);
        return success ? jsonOk() : jsonError(Response.Status.NOT_FOUND, "student not found");
    }

    private Response handleGetPhoto(String id) {
        Student student = DatabaseSync.loadStudent(database, id);
        if (student == null || student.photoPath == null) return notFound();
        File photo = new File(student.photoPath);
        if (!photo.exists()) return notFound();
        try {
            return newFixedLengthResponse(Response.Status.OK, "image/png", new FileInputStream(photo), photo.length());
        } catch (IOException e) {
            return notFound();
        }
    }

    private Response handleUploadPhoto(IHTTPSession session, String id) throws Exception {
        Student student = DatabaseSync.loadStudent(database, id);
        if (student == null) return notFound();

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

    private static JSONObject disciplineJson(Discipline d) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", d.id);
        obj.put("name", d.name);
        return obj;
    }

    private Response handleListDisciplines() throws JSONException {
        ArrayList<Discipline> list = DatabaseSync.loadAllDisciplines(database);
        JSONArray array = new JSONArray();
        if (list != null) for (Discipline d : list) array.put(disciplineJson(d));
        return jsonResponse(Response.Status.OK, array);
    }

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

    private Response handleDeleteDiscipline(String id) {
        DatabaseSync.Result<Void> result = DatabaseSync.deleteDiscipline(database, id);
        if (result.success) return jsonOk();
        if ("HAS_GROUPS".equals(result.error)) return jsonError(Response.Status.CONFLICT, "HAS_GROUPS");
        return jsonError(Response.Status.NOT_FOUND, "discipline not found");
    }

    /* ------------------------------- Class groups ----------------------------------- */

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

    private static JSONObject classGroupJson(ClassGroup g) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", g.id);
        obj.put("disciplineId", g.disciplineId);
        obj.put("name", g.name);
        return obj;
    }

    private Response handleListClassGroups(IHTTPSession session) throws JSONException {
        String disciplineId = queryParam(session, "disciplineId");
        ArrayList<ClassGroup> list = disciplineId != null
                ? DatabaseSync.loadClassGroupsForDiscipline(database, disciplineId)
                : DatabaseSync.loadAllClassGroups(database);
        JSONArray array = new JSONArray();
        if (list != null) for (ClassGroup g : list) array.put(classGroupJson(g));
        return jsonResponse(Response.Status.OK, array);
    }

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

    private Response handleDeleteClassGroup(String id) {
        DatabaseSync.Result<Void> result = DatabaseSync.deleteClassGroup(database, id);
        if (result.success) return jsonOk();
        if ("HAS_STUDENTS".equals(result.error)) return jsonError(Response.Status.CONFLICT, "HAS_STUDENTS");
        return jsonError(Response.Status.NOT_FOUND, "class group not found");
    }

    /* ----------------------------------- Goals -------------------------------------- */

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

    private static JSONObject goalJson(Goal g) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", g.id);
        obj.put("disciplineId", g.disciplineId);
        obj.put("name", g.name);
        obj.put("targetPoints", g.targetPoints);
        return obj;
    }

    private Response handleListGoals(IHTTPSession session) throws JSONException {
        String disciplineId = queryParam(session, "disciplineId");
        if (disciplineId == null) return jsonError(Response.Status.BAD_REQUEST, "disciplineId is required");
        ArrayList<Goal> list = DatabaseSync.loadGoalsForDiscipline(database, disciplineId);
        JSONArray array = new JSONArray();
        if (list != null) for (Goal g : list) array.put(goalJson(g));
        return jsonResponse(Response.Status.OK, array);
    }

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

    private Response handleDeleteGoal(String id) {
        boolean success = DatabaseSync.deleteGoal(database, id);
        return success ? jsonOk() : jsonError(Response.Status.NOT_FOUND, "goal not found");
    }

    /* -------------------------------- Enrollments ------------------------------------ */

    private Response routeEnrollments(IHTTPSession session, Method method, String[] parts) throws Exception {
        if (parts.length == 1) {
            if (method == Method.GET) return handleListEnrollments(session);
            if (method == Method.POST) return handleCreateEnrollment(session);
        } else if (parts.length == 2 && method == Method.DELETE) {
            return handleDeleteEnrollment(parts[1]);
        } else if (parts.length == 3 && parts[2].equals("points") && method == Method.POST) {
            return handleAwardPoints(session, parts[1]);
        }
        return notFound();
    }

    private static JSONObject enrollmentJson(Enrollment e) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", e.id);
        obj.put("studentId", e.studentId);
        obj.put("classGroupId", e.classGroupId);
        obj.put("grades", e.grades);
        return obj;
    }

    private Response handleListEnrollments(IHTTPSession session) throws JSONException {
        String studentId = queryParam(session, "studentId");
        if (studentId == null) return jsonError(Response.Status.BAD_REQUEST, "studentId is required");
        ArrayList<Enrollment> list = DatabaseSync.loadEnrollmentsForStudent(database, studentId);
        JSONArray array = new JSONArray();
        if (list != null) for (Enrollment e : list) array.put(enrollmentJson(e));
        return jsonResponse(Response.Status.OK, array);
    }

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

    private Response handleDeleteEnrollment(String id) {
        boolean success = DatabaseSync.deleteEnrollment(database, id);
        return success ? jsonOk() : jsonError(Response.Status.NOT_FOUND, "enrollment not found");
    }

    /** Mirrors {@code activities/Main.java#onNoteConfirmed}: delta-then-history, never a raw grades setter. */
    private Response handleAwardPoints(IHTTPSession session, String enrollmentId) throws Exception {
        JSONObject body = readJsonBody(session);
        int delta = body.optInt("delta", 0);
        if (delta == 0) return jsonError(Response.Status.BAD_REQUEST, "delta must be non-zero");
        String note = body.optString("note", "");

        Enrollment enrollment = DatabaseSync.loadEnrollmentById(database, enrollmentId);
        if (enrollment == null) return notFound();

        int newGrades = enrollment.grades + delta;
        DatabaseSync.Result<Enrollment> updateResult = DatabaseSync.updateEnrollmentGrades(database, enrollmentId, newGrades);
        if (!updateResult.success) return jsonError(Response.Status.INTERNAL_ERROR, "failed to update points");

        PointsHistory history = new PointsHistory();
        history.enrollmentId = enrollmentId;
        history.pointsDelta = delta;
        history.note = note;
        history.createdAt = System.currentTimeMillis();
        DatabaseSync.savePointsHistory(database, history);

        return jsonResponse(Response.Status.OK, enrollmentJson(updateResult.value));
    }

    /* ---------------------------------- History -------------------------------------- */

    private Response handleHistory(IHTTPSession session) throws JSONException {
        String enrollmentId = queryParam(session, "enrollmentId");
        if (enrollmentId == null) return jsonError(Response.Status.BAD_REQUEST, "enrollmentId is required");

        ArrayList<PointsHistory> list = DatabaseSync.loadHistoryForEnrollment(database, enrollmentId);
        JSONArray array = new JSONArray();
        if (list != null) for (PointsHistory h : list) array.put(historyJson(h));
        return jsonResponse(Response.Status.OK, array);
    }

    private static JSONObject historyJson(PointsHistory h) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", h.id);
        obj.put("enrollmentId", h.enrollmentId);
        obj.put("pointsDelta", h.pointsDelta);
        obj.put("note", h.note == null ? "" : h.note);
        obj.put("createdAt", h.createdAt);
        return obj;
    }

    /* ---------------------------------- Export ---------------------------------------- */

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
            response.addHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
            return response;
        } catch (Exception e) {
            return jsonError(Response.Status.INTERNAL_ERROR, "failed to export");
        }
    }

    /* ---------------------------------- Import ---------------------------------------- */

    private Response handleImportJson(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        String tempPath = files.get("file");
        if (tempPath == null) return jsonError(Response.Status.BAD_REQUEST, "file is required");

        boolean snapshotOk = DatabaseSync.createFullSnapshot(context, database, false);

        List<StudentExportData> data;
        try {
            data = Importer.parseJsonArray(readFileToString(new File(tempPath)));
        } catch (JSONException e) {
            return jsonError(Response.Status.BAD_REQUEST, "invalid JSON file: " + e.getMessage());
        }
        if (data.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "file has no data to import");

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

    private Response handleImportCsv(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        String tempPath = files.get("file");
        if (tempPath == null) return jsonError(Response.Status.BAD_REQUEST, "file is required");

        boolean snapshotOk = DatabaseSync.createFullSnapshot(context, database, false);

        List<CsvStudentRow> rows;
        try (FileInputStream in = new FileInputStream(tempPath)) {
            rows = CsvImporter.parse(in);
        } catch (CsvImporter.CsvFormatException e) {
            return jsonError(Response.Status.BAD_REQUEST, "invalid CSV file: " + e.getMessage());
        }
        if (rows.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "no valid rows to import");

        CsvImportPlan plan = DatabaseSync.resolveCsvRows(database, rows);
        if (plan.resolved.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "no valid rows to import");

        boolean success = DatabaseSync.importCsvRows(database, plan.resolved);

        JSONObject result = new JSONObject();
        result.put("success", success);
        result.put("newCount", plan.newStudentCount());
        result.put("matchedCount", plan.matchedStudentCount());
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

    private static String readFileToString(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /* ---------------------------------- Helpers ---------------------------------------- */

    /** {@code IHTTPSession.getParms()} is deprecated in favor of {@code getParameters()}, which returns one list per key. */
    private static String queryParam(IHTTPSession session, String key) {
        List<String> values = session.getParameters().get(key);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    private Response serveAsset(String assetPath, String mimeType) {
        try {
            InputStream in = context.getAssets().open(assetPath);
            return newFixedLengthResponse(Response.Status.OK, mimeType, in, in.available());
        } catch (IOException e) {
            return notFound();
        }
    }

    /**
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
        return new JSONObject(content == null || content.isEmpty() ? "{}" : content);
    }

    private static Response jsonResponse(Response.Status status, Object body) {
        return newFixedLengthResponse(status, "application/json", body.toString());
    }

    private static Response jsonOk() {
        try {
            return jsonResponse(Response.Status.OK, new JSONObject().put("success", true));
        } catch (JSONException e) {
            return jsonResponse(Response.Status.OK, "{\"success\":true}");
        }
    }

    private static Response jsonError(Response.Status status, String message) {
        try {
            return jsonResponse(status, new JSONObject().put("error", message));
        } catch (JSONException e) {
            return jsonResponse(status, "{\"error\":\"error\"}");
        }
    }

    private static Response notFound() {
        return jsonError(Response.Status.NOT_FOUND, "not found");
    }
}
