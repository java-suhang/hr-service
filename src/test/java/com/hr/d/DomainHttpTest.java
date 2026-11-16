package com.hr.d;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.d.controller.*;
import com.hr.d.filter.DomainRequestFilter;
import com.hr.d.integration.EmployeeCreator;
import com.hr.d.integration.NoticeNotifier;
import com.hr.dto.LoginUser;
import com.hr.filter.AuthFilter;
import com.hr.service.LeaveService;
import jakarta.servlet.Filter;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import oracle.jdbc.pool.OracleDataSource;
import org.apache.catalina.Context;
import org.apache.catalina.servlets.DefaultServlet;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.sql.DataSource;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class DomainHttpTest {
    private static final String H2_URL = "jdbc:h2:mem:domain-http;MODE=Oracle;NON_KEYWORDS=YEAR;DB_CLOSE_DELAY=-1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<HttpClient, String> TOKENS = new HashMap<>();

    private static DataSource database;
    private static Path dbProperties;
    private static Tomcat tomcat;
    private static Context context;
    private static URI base;
    private HttpClient admin;
    private HttpClient manager;
    private HttpClient user;
    private HttpClient other;

    @BeforeAll
    static void start(@TempDir Path temp) throws Exception {
        database = testDatabase();
        configureSharedDatabase();
        ddl(Files.readString(Path.of("src/test/resources/team-fixtures.sql")));
        ddl(Files.readString(Path.of("db/schema.sql")));

        tomcat = new Tomcat();
        tomcat.setBaseDir(temp.toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        context = tomcat.addContext("/hr", Path.of("src/main/webapp").toAbsolutePath().toString());
        context.setParentClassLoader(DomainHttpTest.class.getClassLoader());
        Tomcat.addServlet(context, "default", new DefaultServlet());
        context.addServletMappingDecoded("/", "default");
        context.addMimeMapping("html", "text/html");
        context.addMimeMapping("js", "text/javascript");
        context.addMimeMapping("css", "text/css");
        register(com.hr.controller.AttendanceServlet.class);
        register(NoticeServlet.class);
        register(DashboardServlet.class);
        register(EvaluationServlet.class);
        register(RecruitmentServlet.class);
        registerFilter(new AuthFilter(), AuthFilter.class.getAnnotation(WebFilter.class).urlPatterns());
        registerFilter(new DomainRequestFilter(), DomainRequestFilter.class.getAnnotation(WebFilter.class).value());

        Tomcat.addServlet(context, "test-session", new HttpServlet() {
            @Override protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
                long id = Long.parseLong(req.getParameter("id"));
                var session = req.getSession(true);
                session.setAttribute(LoginUser.SESSION_KEY, new LoginUser(id, "Fixture", id == 1 ? "ADMIN" : "USER"));
                resp.setStatus(204);
            }
        });
        context.addServletMappingDecoded("/test-session", "test-session");
        tomcat.start();
        base = URI.create("http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/hr/");
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (tomcat != null) {
                tomcat.stop();
                tomcat.destroy();
            }
        } finally {
            if (dbProperties != null) Files.deleteIfExists(dbProperties);
        }
    }

    @BeforeEach
    void reset() throws Exception {
        TOKENS.clear();
        context.getServletContext().removeAttribute(NoticeNotifier.class.getName());
        context.getServletContext().removeAttribute(EmployeeCreator.class.getName());
        for (String table : List.of("EVAL_SCORE", "EVALUATION", "EVAL_ITEM", "EVAL_PERIOD", "INTERVIEW", "APPLICANT",
                "JOB_POSTING", "NOTIFICATION", "NOTICE", "APPROVAL_DOC", "LEAVE_BALANCE", "ATTENDANCE")) {
            execute("DELETE FROM " + table);
        }
        execute("DELETE FROM EMPLOYEE WHERE emp_id > 4");
        admin = login(1);
        manager = login(2);
        user = login(3);
        other = login(4);
    }

    @Test
    void enforcesSessionAdminAndCsrfOnApiAndHtml() throws Exception {
        var guest = HttpClient.newHttpClient();
        assertStatus(401, call(guest, "GET", "api/notices", null, null));
        var home = call(guest, "GET", "user/home.html", null, null);
        assertEquals(302, home.statusCode());
        assertTrue(home.headers().firstValue("location").orElseThrow().endsWith("/hr/login.html"));
        assertStatus(403, call(user, "GET", "api/admin/dashboard", null, null));
        assertEquals(403, call(user, "GET", "admin/notices.html", null, null).statusCode());
        assertEquals(403, call(user, "GET", "%61dmin/notices.html", null, null).statusCode());
        assertEquals(200, call(admin, "GET", "admin/notices.html", null, null).statusCode());
        assertStatus(403, call(admin, "POST", "api/admin/notices", Map.of(), null));
        assertStatus(403, call(admin, "DELETE", "api/admin/notices/1", null, "invalid-token"));
        assertStatus(400, call(user, "GET", "api/notices?size=101", null, null));
    }

    @Test
    void noticesPinPaginateSearchEditAndRetainDeletedHistory() throws Exception {
        String body = "<script>alert(1)</script>\n" + "공지 본문".repeat(3000);
        long pinned = notice("필독 안내", body, true);
        long recent = notice("Quick update", "공지 내용", false);

        var first = get(user, "notices?size=1");
        assertEquals(2, first.get("total").asInt());
        assertEquals(pinned, first.at("/items/0/noticeId").asLong());
        assertTrue(first.at("/items/0/PAGE_ROW").isMissingNode());
        assertEquals(recent, get(user, "notices?page=2&size=1").at("/items/0/noticeId").asLong());
        assertEquals(recent, get(user, "notices?q=quick").at("/items/0/noticeId").asLong());
        assertEquals(0, get(user, "notices?q=%25").get("total").asInt());
        assertEquals(body, get(user, "notices/" + pinned).get("content").asText());

        mutate(admin, "PUT", "admin/notices/" + recent, Map.of("title", "수정", "content", "변경"), 200);
        assertEquals("변경", get(user, "notices/" + recent).get("content").asText());
        mutate(admin, "DELETE", "admin/notices/" + pinned, null, 200);
        assertStatus(404, call(user, "GET", "api/notices/" + pinned, null, null));
        assertEquals(1, scalar("SELECT COUNT(*) FROM NOTICE WHERE notice_id = ? AND deleted_at IS NOT NULL", pinned));
    }

    @Test
    void noticeNotificationUsesSameTransactionAndRollsBackOnFailure() throws Exception {
        context.getServletContext().setAttribute(NoticeNotifier.class.getName(), (NoticeNotifier) (conn, id, type, text, link) ->
                execute(conn, "INSERT INTO NOTIFICATION (emp_id, is_read) VALUES (?, 'N')", id));
        assertTrue(mutate(admin, "POST", "admin/notices", Map.of("title", "배송", "content", "본문"), 201)
                .get("notificationsEnabled").asBoolean());
        assertEquals(3, scalar("SELECT COUNT(*) FROM NOTIFICATION"));
        context.getServletContext().setAttribute(NoticeNotifier.class.getName(), (NoticeNotifier) (conn, id, type, text, link) -> {
            execute(conn, "INSERT INTO NOTIFICATION (emp_id, is_read) VALUES (?, 'N')", id);
            throw new SQLException("expected notification failure");
        });
        mutate(admin, "POST", "admin/notices", Map.of("title", "롤백", "content", "본문"), 500);
        assertEquals(3, scalar("SELECT COUNT(*) FROM NOTIFICATION"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM NOTICE"));
    }

    @Test
    void dashboardsAggregateRealRowsAndScopeEmployeeData() throws Exception {
        execute("INSERT INTO ATTENDANCE VALUES (SEQ_ATTENDANCE.NEXTVAL, 3, TRUNC(SYSDATE), SYSDATE, NULL, 'NORMAL')");
        execute("INSERT INTO LEAVE_BALANCE VALUES (3, EXTRACT(YEAR FROM SYSDATE), 15, 2.5)");
        execute("INSERT INTO APPROVAL_DOC VALUES (3, 'IN_PROGRESS')");
        execute("INSERT INTO APPROVAL_DOC VALUES (4, 'IN_PROGRESS')");
        execute("INSERT INTO APPROVAL_DOC VALUES (3, 'APPROVED')");
        execute("INSERT INTO NOTIFICATION VALUES (3, 'N')");
        notice("홈 공지", "내용", true);
        posting();
        period();

        var personal = get(user, "dashboard");
        assertTrue(get(user, "attendance/today").isObject());
        assertEquals("NORMAL", personal.at("/attendance/status").asText());
        assertEquals(12.5, personal.at("/leave/remainingDays").asDouble());
        assertEquals(2, personal.get("approvals").size());
        assertEquals(1, personal.get("unreadNotifications").asLong());
        assertEquals("홈 공지", personal.at("/notices/0/title").asText());
        assertTrue(get(other, "dashboard").get("leave").isNull());

        var metrics = get(admin, "admin/dashboard");
        assertEquals(4, metrics.get("activeEmployees").asInt());
        assertEquals(25.0, metrics.get("attendanceRate").asDouble());
        assertEquals(2, metrics.get("pendingApprovals").asInt());
        assertEquals(1, metrics.at("/departments/0/checkedIn").asInt());
        assertEquals(1, metrics.at("/recruitment/openPostings").asInt());
        assertEquals("2026 평가", metrics.at("/evaluation/name").asText());
    }

    @Test
    void dashboardKeepsNoticesWhenTeamTablesAreUnavailable() throws Exception {
        execute("ALTER TABLE ATTENDANCE RENAME TO TEST_ATTENDANCE_OFF");
        try {
            var personal = get(user, "dashboard");
            assertTrue(personal.get("attendance").isNull());
            assertEquals("attendance", personal.at("/unavailable/0").asText());
            assertTrue(personal.get("notices").isArray());
            assertTrue(get(admin, "admin/dashboard").get("attendanceRate").isNull());
        } finally {
            execute("ALTER TABLE TEST_ATTENDANCE_OFF RENAME TO ATTENDANCE");
        }
    }

    @Test
    void evaluationValidatesPhasesWeightsAndRolesAndPublishesResults() throws Exception {
        long id = period();
        long first = item(id, "협업", 40.01);
        long second = item(id, "성과", 59.99);
        mutate(admin, "POST", periodPath(id) + "/status", Map.of("status", "MANAGER"), 409);
        mutate(admin, "PUT", "admin/evaluations/items/" + second, Map.of("name", "성과", "weight", 58), 200);
        mutate(admin, "POST", periodPath(id) + "/status", Map.of("status", "SELF"), 409);
        mutate(admin, "PUT", "admin/evaluations/items/" + second, Map.of("name", "성과", "weight", 59.99), 200);
        mutate(admin, "POST", periodPath(id) + "/status", Map.of("status", "SELF"), 200);
        mutate(admin, "DELETE", "admin/evaluations/items/" + second, null, 409);
        var scores = List.of(Map.of("itemId", first, "score", 3), Map.of("itemId", second, "score", 5));
        var self = Map.of("periodId", id, "targetId", 3, "evalType", "SELF", "scores", scores);
        var submit = "evaluations/submit";
        mutate(other, "POST", submit, self, 403);
        mutate(user, "POST", submit, Map.of("periodId", id, "targetId", 3, "evalType", "SELF",
                "scores", List.of(scores.getFirst(), scores.getFirst())), 400);
        mutate(user, "POST", submit, Map.of("periodId", id, "targetId", 3, "evalType", "SELF",
                "scores", List.of(scores.getFirst())), 400);

        // Only one concurrent submission can commit.
        try (var tasks = Executors.newFixedThreadPool(2)) {
            var one = tasks.submit(() -> call(user, "POST", "api/" + submit, self, TOKENS.get(user)));
            var two = tasks.submit(() -> call(user, "POST", "api/" + submit, self, TOKENS.get(user)));
            var responses = List.of(one.get(), two.get());
            assertEquals(List.of(201, 409), responses.stream().map(HttpResponse::statusCode).sorted().toList());
            var accepted = JSON.readTree(responses.stream().filter(r -> r.statusCode() == 201).findFirst().orElseThrow().body());
            assertEquals(4.1998, accepted.get("totalScore").asDouble());
        }
        mutate(admin, "POST", periodPath(id) + "/status", Map.of("status", "MANAGER"), 200);
        var review = Map.of("periodId", id, "targetId", 3, "evalType", "MANAGER", "scores", scores);
        mutate(other, "POST", submit, review, 403);
        long result = mutate(manager, "POST", submit, review, 201).get("evalId").asLong();
        assertEquals(1, get(manager, "evaluations/reviews?periodId=" + id).get("total").asInt());
        assertEquals(1, get(manager, "evaluations/team").get("total").asInt());
        assertStatus(404, call(user, "GET", "api/evaluations/results/" + result, null, null));
        assertEquals(2, get(admin, "admin/evaluations/results/" + result).get("scores").size());
        assertEquals(4, get(admin, "admin/evaluations/status?periodId=" + id).get("total").asInt());
        mutate(admin, "POST", periodPath(id) + "/status", Map.of("status", "CLOSED"), 200);
        assertEquals(2, get(user, "evaluations/mine?periodId=" + id).get("total").asInt());
        assertEquals(4.1998, get(user, "evaluations/results/" + result).get("totalScore").asDouble());
    }

    @Test
    void recruitmentEnforcesPostingAndInterviewRulesAndMasksLists() throws Exception {
        long posting = posting();
        long applicant = applicant(posting);
        assertEquals("010****5678", get(admin, "admin/recruitment/applicants?postingId=" + posting).at("/items/0/phone").asText());
        assertEquals("01012345678", get(admin, applicantPath(applicant)).get("phone").asText());
        stage(applicant, "OFFER", 409);
        stage(applicant, "INTERVIEW", 200);
        String interviewDate = LocalDate.now().plusDays(1) + "T09:30";
        var schedule = Map.of("interviewerId", 2, "scheduledAt", interviewDate);
        long interview = mutate(admin, "POST", applicantPath(applicant) + "/interviews", schedule, 201)
                .get("interviewId").asLong();
        assertEquals(1, get(manager, "recruitment/interviews").get("total").asInt());
        assertEquals(interviewDate, get(manager, "recruitment/interviews").at("/items/0/scheduledAt").asText());
        assertEquals(0, get(other, "recruitment/interviews").get("total").asInt());
        String interviewPath = "recruitment/interviews/" + interview;
        mutate(other, "PUT", interviewPath, Map.of("score", 80), 403);
        mutate(manager, "PUT", interviewPath, Map.of("score", 101), 400);
        mutate(manager, "PUT", interviewPath, Map.of("score", 80, "feedback", "평가"), 200);
        mutate(admin, "PUT", "admin/" + interviewPath + "/schedule", schedule, 409);
        stage(applicant, "OFFER", 200);
        mutate(admin, "POST", "admin/recruitment/postings/" + posting + "/close", null, 200);
        mutate(admin, "POST", "admin/recruitment/applicants", Map.of("postingId", posting, "name", "마감 이후"), 409);
    }

    @Test
    void hireRollsBackEmployeeAndLeaveBalanceAndCommitsOnlyOnce() throws Exception {
        long id = applicant(posting());
        stage(id, "INTERVIEW", 200);
        stage(id, "OFFER", 200);
        String path = applicantPath(id) + "/hire";
        var command = Map.of("empNo", "H-01", "loginId", "new-user", "initialPassword", "test-only-password",
                "hireDate", LocalDate.now().toString(), "annualDays", 15);
        mutate(admin, "POST", path, command, 503);

        context.getServletContext().setAttribute(EmployeeCreator.class.getName(), (EmployeeCreator) (conn, employee, days) -> {
            createEmployee(conn, employee, days, 20);
            throw new SQLException("expected failure after employee and leave insert");
        });
        mutate(admin, "POST", path, command, 500);
        assertEquals("OFFER", get(admin, applicantPath(id)).get("stage").asText());
        assertEquals(0, scalar("SELECT COUNT(*) FROM EMPLOYEE WHERE emp_id = 20"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM LEAVE_BALANCE WHERE emp_id = 20"));

        context.getServletContext().setAttribute(EmployeeCreator.class.getName(), (EmployeeCreator) (conn, employee, days) -> {
            createEmployee(conn, employee, days, 20);
            return 20;
        });
        var hired = mutate(admin, "POST", path, command, 201);
        assertEquals(20, hired.get("empId").asLong());
        assertEquals("HIRED", get(admin, applicantPath(id)).get("stage").asText());
        assertEquals(1, scalar("SELECT COUNT(*) FROM EMPLOYEE WHERE emp_id = 20"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM LEAVE_BALANCE WHERE emp_id = 20 AND total_days = 15"));
        mutate(admin, "POST", path, command, 409);
    }

    private static void createEmployee(Connection conn, EmployeeCreator.Employee employee, java.math.BigDecimal days, long id)
            throws SQLException {
        execute(conn, """
                INSERT INTO EMPLOYEE (emp_id, emp_no, name, dept_id, pos_id, manager_id, status)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, id, employee.empNo(), employee.name(), employee.deptId(), employee.posId(), employee.managerId());
        new LeaveService().grantAnnual(conn, id, LocalDate.now().getYear(), days);
    }

    private long notice(String title, String content, boolean pinned) throws Exception {
        var result = mutate(admin, "POST", "admin/notices", Map.of("title", title, "content", content, "isPinned", pinned), 201);
        assertFalse(result.get("notificationsEnabled").asBoolean());
        return result.get("noticeId").asLong();
    }

    private long period() throws Exception {
        return mutate(admin, "POST", "admin/evaluations/periods", Map.of("name", "2026 평가", "startDate",
                LocalDate.now().minusDays(1).toString(), "endDate", LocalDate.now().plusDays(1).toString()), 201)
                .get("periodId").asLong();
    }

    private long item(long period, String name, double weight) throws Exception {
        return mutate(admin, "POST", periodPath(period) + "/items", Map.of("name", name, "weight", weight), 201)
                .get("itemId").asLong();
    }

    private long posting() throws Exception {
        return mutate(admin, "POST", "admin/recruitment/postings", Map.of("title", "개발자", "deptId", 1, "posId", 1,
                "openDate", LocalDate.now().minusDays(1).toString(), "closeDate", LocalDate.now().plusDays(1).toString()), 201)
                .get("postingId").asLong();
    }

    private long applicant(long posting) throws Exception {
        return mutate(admin, "POST", "admin/recruitment/applicants", Map.of("postingId", posting, "name", "지원자",
                "email", "candidate@example.test", "phone", "01012345678"), 201).get("applicantId").asLong();
    }

    private void stage(long id, String stage, int expected) throws Exception {
        mutate(admin, "POST", applicantPath(id) + "/stage", Map.of("stage", stage), expected);
    }

    private static String periodPath(long id) { return "admin/evaluations/periods/" + id; }
    private static String applicantPath(long id) { return "admin/recruitment/applicants/" + id; }

    private static void register(Class<? extends HttpServlet> type) throws Exception {
        String name = type.getSimpleName();
        Tomcat.addServlet(context, name, type.getConstructor().newInstance());
        for (String path : type.getAnnotation(WebServlet.class).value()) context.addServletMappingDecoded(path, name);
    }

    private static void registerFilter(Filter instance, String[] paths) {
        var filter = new FilterDef();
        filter.setFilterName(instance.getClass().getSimpleName());
        filter.setFilter(instance);
        context.addFilterDef(filter);
        var mappings = new FilterMap();
        mappings.setFilterName(filter.getFilterName());
        for (String path : paths) mappings.addURLPattern(path);
        context.addFilterMap(mappings);
    }

    private static HttpClient login(long id) throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();
        assertEquals(204, call(client, "POST", "test-session?id=" + id, null, null).statusCode());
        String token = get(client, "dashboard/session").path("csrf").asText();
        assertFalse(token.isBlank());
        TOKENS.put(client, token);
        return client;
    }

    private static JsonNode get(HttpClient client, String route) throws Exception {
        return assertStatus(200, call(client, "GET", "api/" + route, null, null));
    }

    private static JsonNode mutate(HttpClient client, String method, String route, Object body, int expected) throws Exception {
        return assertStatus(expected, call(client, method, "api/" + route, body, TOKENS.get(client)));
    }

    private static HttpResponse<String> call(HttpClient client, String method, String route, Object body, String csrf)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(base.resolve(route));
        if (csrf != null) request.header("X-CSRF-Token", csrf);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode assertStatus(int expected, HttpResponse<String> response) throws IOException {
        assertEquals(expected, response.statusCode(), response::body);
        var json = JSON.readTree(response.body());
        if (expected >= 400) assertTrue(json.hasNonNull("message"));
        return json;
    }

    private static DataSource testDatabase() throws SQLException {
        String url = System.getenv("HR_D_TEST_ORACLE_URL");
        if (url != null) {
            var source = new OracleDataSource();
            source.setURL(url);
            source.setUser(System.getenv("HR_D_TEST_ORACLE_USER"));
            source.setPassword(System.getenv("HR_D_TEST_ORACLE_PASSWORD"));
            return source;
        }
        var source = new JdbcDataSource();
        source.setURL(H2_URL);
        source.setUser("sa");
        source.setPassword("");
        return source;
    }

    private static void configureSharedDatabase() throws Exception {
        String oracleUrl = System.getenv("HR_D_TEST_ORACLE_URL");
        var props = new Properties();
        props.setProperty("db.driver", oracleUrl == null ? "org.h2.Driver" : "oracle.jdbc.OracleDriver");
        props.setProperty("db.url", oracleUrl == null ? H2_URL : oracleUrl);
        props.setProperty("db.user", oracleUrl == null ? "sa" : System.getenv("HR_D_TEST_ORACLE_USER"));
        props.setProperty("db.password", oracleUrl == null ? "" : System.getenv("HR_D_TEST_ORACLE_PASSWORD"));
        var testClasses = Path.of(DomainHttpTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var path = testClasses.resolve("db.properties");
        try (var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
            dbProperties = path;
            props.store(output, "Tests only");
        }
    }

    private static void ddl(String script) throws SQLException {
        try (var conn = database.getConnection(); var statement = conn.createStatement()) {
            for (String command : script.replaceAll("(?m)^\\s*--[^\\r\\n]*", "").split(";")) {
                if (!command.isBlank()) statement.execute(command);
            }
        }
    }

    private static void execute(String sql, Object... params) throws SQLException {
        try (var conn = database.getConnection()) {
            execute(conn, sql, params);
        }
    }

    private static void execute(Connection conn, String sql, Object... params) throws SQLException {
        try (var stmt = conn.prepareStatement(sql)) {
            for (int index = 0; index < params.length; index++) stmt.setObject(index + 1, params[index]);
            stmt.executeUpdate();
        }
    }

    private static long scalar(String sql, Object... params) throws SQLException {
        try (var conn = database.getConnection(); var stmt = conn.prepareStatement(sql)) {
            for (int index = 0; index < params.length; index++) stmt.setObject(index + 1, params[index]);
            try (var result = stmt.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }
}
