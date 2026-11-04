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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import org.dedira.qrnotas.util.Database;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs the real {@link WebServer} on a free local port and talks to it over HTTP the way the
 * browser does (same cookie, same JSON), against the Robolectric-backed database. These are the
 * checks that only a real HTTP round trip can make: accents surviving the request body, the
 * connection staying usable after a request, and the shape of what the web screens read.
 *
 * <p>The server's worker threads hand database work to the main looper and wait for it, and under
 * Robolectric that looper only runs when the test thread idles it. So the HTTP client runs on its
 * own thread while the test thread idles the looper until the client is done.
 */
@RunWith(RobolectricTestRunner.class)
public class WebServerApiTest {

    private WebServer server;
    private ExecutorService client;
    private String base;
    private String cookie;

    @Before
    public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        server = new WebServer(context, new Database(context), "secret", 0);
        server.start();
        base = "http://127.0.0.1:" + server.getListeningPort();
        client = Executors.newSingleThreadExecutor();
        login();
    }

    @After
    public void tearDown() {
        client.shutdownNow();
        server.stop();
    }

    /* ----------------------------------- plumbing ----------------------------------- */

    private static final class Reply {
        final int status;
        final String body;
        final String contentType;

        Reply(int status, String body, String contentType) {
            this.status = status;
            this.body = body;
            this.contentType = contentType;
        }

        JSONObject json() throws Exception {
            return new JSONObject(body);
        }

        JSONArray array() throws Exception {
            return new JSONArray(body);
        }
    }

    /** Runs {@code call} on the client thread while this thread keeps the main looper turning. */
    private <T> T await(Callable<T> call) throws Exception {
        Future<T> future = client.submit(call);
        long deadline = System.currentTimeMillis() + 20000;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(2);
        }
        assertTrue("request timed out", future.isDone());
        return future.get();
    }

    /**
     * One request. {@code contentType} is sent exactly as given, null for none; JSON bodies go out
     * UTF-8 with no charset in the header, as a browser's fetch() does when the page doesn't add one.
     */
    private Reply call(String method, String path, String body, String contentType) throws Exception {
        return await(() -> {
            HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
            c.setRequestMethod(method);
            if (cookie != null) c.setRequestProperty("Cookie", cookie);
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                if (contentType != null) c.setRequestProperty("Content-Type", contentType);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(bytes.length);
                c.getOutputStream().write(bytes);
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                in.close();
            }
            String setCookie = c.getHeaderField("Set-Cookie");
            if (setCookie != null) cookie = setCookie.split(";")[0];
            return new Reply(status, new String(out.toByteArray(), StandardCharsets.UTF_8), c.getContentType());
        });
    }

    private Reply get(String path) throws Exception {
        return call("GET", path, null, null);
    }

    private Reply post(String path, String json) throws Exception {
        return call("POST", path, json, "application/json");
    }

    private Reply put(String path, String json) throws Exception {
        return call("PUT", path, json, "application/json");
    }

    private void login() throws Exception {
        Reply reply = post("/api/login", "{\"password\":\"secret\"}");
        assertEquals(200, reply.status);
        assertTrue("no session cookie", cookie != null && cookie.startsWith("qrgrades_session="));
    }

    /** A class with one enrolled student; returns their ids. */
    private String[] createEnrolledStudent(String name) throws Exception {
        String discipline = post("/api/disciplines", "{\"name\":\"Matemática\"}").json().getString("id");
        String group = post("/api/classgroups", "{\"disciplineId\":\"" + discipline + "\",\"name\":\"Turma Ú\"}").json().getString("id");
        String student = post("/api/students", new JSONObject().put("name", name).toString()).json().getString("id");
        String enrollment = post("/api/enrollments", "{\"studentId\":\"" + student + "\",\"classGroupId\":\"" + group + "\"}").json().getString("id");
        return new String[]{discipline, group, student, enrollment};
    }

    private int pointsOf(String enrollmentId) throws Exception {
        JSONArray rows = get("/api/overview").array();
        for (int i = 0; i < rows.length(); i++) {
            if (enrollmentId.equals(rows.getJSONObject(i).getString("enrollmentId"))) return rows.getJSONObject(i).getInt("points");
        }
        throw new AssertionError("enrollment not in overview");
    }

    /* ------------------------------------- tests ------------------------------------- */

    @Test
    public void requestsWithoutASessionAreRefused() throws Exception {
        cookie = null;
        assertEquals(401, get("/api/students").status);
    }

    @Test
    public void accentsInAPostBodySurvive_evenWithoutACharsetInTheContentType() throws Exception {
        // NanoHTTPD reads a POST body as US-ASCII unless told otherwise, which turned "Participação" into "Participa????o".
        String[] ids = createEnrolledStudent("JOÃO TÊS");
        Reply award = post("/api/enrollments/" + ids[3] + "/points",
                new JSONObject().put("delta", 4).put("note", "ótima participação").put("tag", "Participação").toString());
        assertEquals(200, award.status);

        JSONObject history = get("/api/students/" + ids[2] + "/history").json();
        assertEquals("JOÃO TÊS", history.getJSONObject("student").getString("name"));
        JSONObject entry = history.getJSONArray("current").getJSONObject(0).getJSONArray("history").getJSONObject(0);
        assertEquals("ótima participação", entry.getString("note"));
        assertEquals("Participação", entry.getString("tag"));
    }

    @Test
    public void accentsInAPutBodySurvive() throws Exception {
        String[] ids = createEnrolledStudent("Ana");
        assertEquals(200, put("/api/students/" + ids[2], "{\"name\":\"ANA JOSÉ ÂNGELA\"}").status);
        JSONArray students = get("/api/students").array();
        assertEquals("ANA JOSÉ ÂNGELA", students.getJSONObject(0).getString("name"));
    }

    @Test
    public void aBodyThatIsNotNeeded_doesNotSpoilTheNextRequestOnTheConnection() throws Exception {
        // "Cancel entry" ignores its body ({}); NanoHTTPD took the unread bytes for the start of the next request: 400.
        String[] ids = createEnrolledStudent("Bia");
        JSONObject award = post("/api/enrollments/" + ids[3] + "/points", "{\"delta\":5}").json();
        String historyId = award.getString("historyId");
        assertEquals(5, pointsOf(ids[3]));

        Reply revert = post("/api/history/" + historyId + "/revert", "{}");
        assertEquals(200, revert.status);
        for (String path : new String[]{"/api/disciplines", "/api/classgroups", "/api/students", "/api/overview"}) {
            assertEquals(path + " right after the cancel", 200, get(path).status);
        }
        assertEquals(0, pointsOf(ids[3]));

        // Same for a POST with no body at all, and for logout.
        assertEquals(200, call("POST", "/api/students/" + ids[2] + "/qrcode/regenerate", "", null).status);
        assertEquals(200, get("/api/students").status);
        assertEquals(200, post("/api/logout", "{}").status);
        assertEquals(401, get("/api/students").status);
    }

    @Test
    public void cancellingTwiceIsRefused_andCorrectingReplacesTheEntry() throws Exception {
        String[] ids = createEnrolledStudent("Caio");
        String first = post("/api/enrollments/" + ids[3] + "/points", "{\"delta\":7,\"note\":\"x\"}").json().getString("historyId");

        Reply corrected = post("/api/history/" + first + "/correct", "{\"delta\":8,\"note\":\"y\",\"tag\":\"Prova\"}");
        assertEquals(200, corrected.status);
        assertEquals(8, pointsOf(ids[3]));

        Reply again = post("/api/history/" + first + "/revert", "{}");
        assertEquals(409, again.status);
        assertEquals("ALREADY_REVERTED", again.json().getString("error"));

        Reply zero = post("/api/history/" + first + "/correct", "{\"delta\":0}");
        assertEquals(400, zero.status);
        assertEquals(8, pointsOf(ids[3]));
    }

    @Test
    public void batchAwardIsAllOrNothing_andCanBeUndoneAsABatch() throws Exception {
        String[] a = createEnrolledStudent("Dani");
        String studentB = post("/api/students", "{\"name\":\"Edu\"}").json().getString("id");
        String enrollmentB = post("/api/enrollments", "{\"studentId\":\"" + studentB + "\",\"classGroupId\":\"" + a[1] + "\"}").json().getString("id");

        Reply missing = post("/api/points/batch", "{\"enrollmentIds\":[\"" + a[3] + "\",\"nope\"],\"delta\":3}");
        assertEquals(404, missing.status);
        assertEquals("a missing enrollment leaves everyone untouched", 0, pointsOf(a[3]));

        Reply ok = post("/api/points/batch", "{\"enrollmentIds\":[\"" + a[3] + "\",\"" + enrollmentB + "\"],\"delta\":3,\"note\":\"trabalho\",\"tag\":\"Atividade\"}");
        assertEquals(200, ok.status);
        JSONArray entries = ok.json().getJSONArray("entries");
        assertEquals(2, entries.length());
        assertEquals(3, pointsOf(a[3]));
        assertEquals(3, pointsOf(enrollmentB));

        JSONArray ids = new JSONArray();
        for (int i = 0; i < entries.length(); i++) ids.put(entries.getJSONObject(i).getString("historyId"));
        JSONObject undone = post("/api/history/revert-batch", new JSONObject().put("ids", ids).toString()).json();
        assertEquals(2, undone.getInt("reverted"));
        assertEquals(0, undone.getJSONArray("failed").length());
        assertEquals(0, pointsOf(a[3]));
        assertEquals(0, pointsOf(enrollmentB));

        assertEquals(400, post("/api/points/batch", "{\"enrollmentIds\":[],\"delta\":3}").status);
        assertEquals(400, post("/api/points/batch", "{\"enrollmentIds\":[\"" + a[3] + "\"],\"delta\":5000}").status);
    }

    @Test
    public void suggestions_listUsedNotesAndCategoriesThenTheDefaults() throws Exception {
        String[] ids = createEnrolledStudent("Fabi");
        post("/api/enrollments/" + ids[3] + "/points", "{\"delta\":1,\"note\":\"Entregou o relatório\",\"tag\":\"Relatório\"}");
        JSONObject s = get("/api/suggestions").json();
        assertEquals("Entregou o relatório", s.getJSONArray("notes").getString(0));
        assertEquals("Relatório", s.getJSONArray("tags").getString(0));
        assertTrue("the built-in categories follow", s.getJSONArray("tags").length() > 1);
    }

    @Test
    public void attendance_startMarkSummaryAndDelete() throws Exception {
        String[] ids = createEnrolledStudent("Gabi");
        String group = ids[1];
        String studentB = post("/api/students", "{\"name\":\"Hugo\"}").json().getString("id");
        post("/api/enrollments", "{\"studentId\":\"" + studentB + "\",\"classGroupId\":\"" + group + "\"}");

        JSONObject empty = get("/api/attendance?classGroupId=" + group + "&day=2026-10-27").json();
        assertFalse(empty.getBoolean("started"));
        assertEquals(2, empty.getJSONArray("students").length());

        JSONObject started = post("/api/attendance/start", "{\"classGroupId\":\"" + group + "\",\"day\":\"2026-10-27\"}").json();
        assertTrue(started.getBoolean("started"));
        assertEquals(2, started.getJSONObject("counts").getInt("absent")); // everyone starts out absent

        JSONObject marked = put("/api/attendance", "{\"classGroupId\":\"" + group + "\",\"day\":\"2026-10-27\","
                + "\"marks\":[{\"studentId\":\"" + ids[2] + "\",\"status\":1}]}").json();
        assertEquals(1, marked.getJSONObject("counts").getInt("present"));
        assertEquals(1, marked.getJSONObject("counts").getInt("absent"));

        assertEquals(400, put("/api/attendance", "{\"classGroupId\":\"" + group + "\",\"day\":\"2026-10-27\","
                + "\"marks\":[{\"studentId\":\"" + ids[2] + "\",\"status\":7}]}").status);
        assertEquals(400, get("/api/attendance?classGroupId=" + group + "&day=27/10/2026").status);

        JSONObject summary = get("/api/attendance/summary?classGroupId=" + group + "&period=current").json();
        assertEquals(1, summary.getInt("days"));
        JSONArray students = summary.getJSONArray("students");
        for (int i = 0; i < students.length(); i++) {
            JSONObject st = students.getJSONObject(i);
            boolean isGabi = st.getString("studentId").equals(ids[2]);
            assertEquals(isGabi ? 1 : 0, st.getInt("present"));
            assertEquals(isGabi ? 0 : 1, st.getInt("absent"));
        }

        JSONArray days = get("/api/attendance/days?classGroupId=" + group).json().getJSONArray("days");
        assertEquals(1, days.length());
        assertEquals("2026-10-27", days.getJSONObject(0).getString("day"));

        assertEquals(1, call("DELETE", "/api/attendance?classGroupId=" + group + "&day=2026-10-27", null, null).json().getInt("removed") > 0 ? 1 : 0);
        assertFalse(get("/api/attendance?classGroupId=" + group + "&day=2026-10-27").json().getBoolean("started"));
    }

    @Test
    public void regeneratingAQrCode_isReportedOnTheStudent() throws Exception {
        String[] ids = createEnrolledStudent("Iara");
        assertFalse(get("/api/students").array().getJSONObject(0).getBoolean("qrRegenerated"));

        Reply reply = call("POST", "/api/students/" + ids[2] + "/qrcode/regenerate", "", null);
        assertEquals(200, reply.status);
        assertTrue(reply.json().getJSONObject("student").getBoolean("qrRegenerated"));
        assertTrue(get("/api/students").array().getJSONObject(0).getBoolean("qrRegenerated"));
        assertEquals(404, call("POST", "/api/students/does-not-exist/qrcode/regenerate", "", null).status);
    }

    @Test
    public void periodComparisonNeedsADiscipline_andIsEmptyBeforeAnyPeriodIsClosed() throws Exception {
        assertEquals(400, get("/api/periods/compare").status);
        String[] ids = createEnrolledStudent("Joana");
        JSONObject compare = get("/api/periods/compare?disciplineId=" + ids[0]).json();
        assertEquals(0, compare.getJSONArray("columns").length());
        assertNotEquals(-1, compare.toString().indexOf("classGroups"));
    }

    /* --------------------------------------- goals --------------------------------------- */

    private static String goalBody(String disciplineId, String name, int target, String start, String end) throws Exception {
        JSONObject body = new JSONObject().put("disciplineId", disciplineId).put("name", name).put("targetPoints", target);
        if (start != null) body.put("startDate", start);
        if (end != null) body.put("endDate", end);
        return body.toString();
    }

    @Test
    public void aGoalNeedsRealStartAndEndDates_andIsReportedAsActiveUpcomingOrEnded() throws Exception {
        String d = createEnrolledStudent("Kátia")[0];

        // Without both dates, with a day that doesn't exist, or ending before it starts: refused, nothing stored.
        assertEquals(400, post("/api/goals", goalBody(d, "R", 10, null, null)).status);
        assertEquals(400, post("/api/goals", goalBody(d, "R", 10, "2026-02-01", null)).status);
        assertEquals(400, post("/api/goals", goalBody(d, "R", 10, "2026-02-30", "2026-04-30")).status);
        assertEquals(400, post("/api/goals", goalBody(d, "R", 10, "2026-05-01", "2026-04-30")).status);
        assertEquals(0, get("/api/goals?disciplineId=" + d).array().length());

        JSONObject ended = post("/api/goals", goalBody(d, "Antiga", 10, "2020-02-01", "2020-04-30")).json();
        JSONObject running = post("/api/goals", goalBody(d, "Atual", 10, "2000-01-01", "2099-12-31")).json();
        JSONObject upcoming = post("/api/goals", goalBody(d, "Futura", 10, "2098-01-01", "2098-12-31")).json();
        assertEquals("2020-02-01", ended.getString("startDate"));
        assertEquals("2020-04-30", ended.getString("endDate"));
        assertEquals("ended", ended.getString("status"));
        assertEquals("active", running.getString("status"));
        assertEquals("upcoming", upcoming.getString("status"));

        // Soonest-ending window first.
        JSONArray listed = get("/api/goals?disciplineId=" + d).array();
        assertEquals("Antiga", listed.getJSONObject(0).getString("name"));
        assertEquals("Futura", listed.getJSONObject(1).getString("name"));
        assertEquals("Atual", listed.getJSONObject(2).getString("name"));

        // An edit has to carry the dates as well, and can move the window.
        String id = ended.getString("id");
        assertEquals(400, put("/api/goals/" + id, goalBody(d, "Antiga", 10, null, null)).status);
        JSONObject moved = put("/api/goals/" + id, goalBody(d, "Antiga", 12, "2020-02-01", "2020-05-31")).json();
        assertEquals("2020-05-31", moved.getString("endDate"));
        assertEquals(12, moved.getInt("targetPoints"));
        assertEquals(3, get("/api/goals?disciplineId=" + d).array().length());
    }

    @Test
    public void theOverviewCountsOnlyThePointsInsideEachGoalsWindow() throws Exception {
        String[] ids = createEnrolledStudent("Lia");
        post("/api/goals", goalBody(ids[0], "Atual", 10, "2000-01-01", "2099-12-31"));
        post("/api/goals", goalBody(ids[0], "Antiga", 10, "2020-02-01", "2020-04-30"));
        post("/api/goals", goalBody(ids[0], "Futura", 10, "2098-01-01", "2098-12-31"));
        assertEquals(200, post("/api/enrollments/" + ids[3] + "/points", "{\"delta\":6,\"note\":\"hoje\"}").status);

        JSONObject row = get("/api/overview").array().getJSONObject(0);
        assertEquals(6, row.getInt("points"));
        JSONArray goals = row.getJSONArray("goals");
        assertEquals(3, goals.length());
        // Running first, then the upcoming one, then the finished one.
        JSONObject active = goals.getJSONObject(0);
        assertEquals("Atual", active.getString("name"));
        assertEquals("active", active.getString("state"));
        assertEquals(6, active.getInt("current"));
        assertEquals(4, active.getInt("remaining"));
        assertEquals("2000-01-01", active.getString("startDate"));
        assertEquals("2099-12-31", active.getString("endDate"));
        JSONObject upcoming = goals.getJSONObject(1);
        assertEquals("upcoming", upcoming.getString("state"));
        assertEquals(0, upcoming.getInt("current"));
        // Points given today are far outside a 2020 window: too late to count for it.
        JSONObject finished = goals.getJSONObject(2);
        assertEquals("ended", finished.getString("state"));
        assertEquals(0, finished.getInt("current"));
        assertFalse(finished.getBoolean("achieved"));
    }

    @Test
    public void theStudentHistoryShowsEachGoalWithItsWindow() throws Exception {
        String[] ids = createEnrolledStudent("Mara");
        post("/api/goals", goalBody(ids[0], "Atual", 10, "2000-01-01", "2099-12-31"));
        post("/api/enrollments/" + ids[3] + "/points", "{\"delta\":4,\"note\":\"x\"}");

        JSONObject bucket = get("/api/students/" + ids[2] + "/history").json().getJSONArray("current").getJSONObject(0);
        JSONObject goal = bucket.getJSONArray("goals").getJSONObject(0);
        assertEquals("Atual", goal.getString("name"));
        assertEquals(4, goal.getInt("current"));
        assertEquals("active", goal.getString("state"));
        assertEquals("2099-12-31", goal.getString("endDate"));
        assertTrue(goal.getInt("daysLeft") > 0);
    }
}
