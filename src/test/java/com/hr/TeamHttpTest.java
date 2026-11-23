package com.hr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static com.hr.HttpTestServer.YEAR;
import static com.hr.HttpTestServer.client;
import static com.hr.HttpTestServer.context;
import static com.hr.HttpTestServer.database;
import static com.hr.HttpTestServer.delete;
import static com.hr.HttpTestServer.get;
import static com.hr.HttpTestServer.idOf;
import static com.hr.HttpTestServer.login;
import static com.hr.HttpTestServer.post;
import static com.hr.HttpTestServer.put;
import static com.hr.HttpTestServer.scalar;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.hr.HttpTestServer.Res;
import com.hr.d.integration.EmployeeCreator;
import com.hr.util.ApiException;

import jakarta.servlet.http.HttpServlet;

/**
 * 공통·인사(A) HTTP 테스트. 실제 db/schema.sql 전체와 db/seed.sql을 H2 Oracle 호환 모드에 실행해
 * DDL·시드도 함께 검증한다 (HttpTestServer).
 */
class TeamHttpTest {

    @BeforeAll
    static void start(@TempDir Path temp) throws Exception {
        HttpTestServer.start(temp, servlets());
    }

    static List<Class<? extends HttpServlet>> servlets() {
        return List.of(
                com.hr.controller.AuthServlet.class,
                com.hr.controller.MeServlet.class,
                com.hr.controller.admin.EmployeeAdminServlet.class,
                com.hr.controller.admin.OrgAdminServlet.class,
                com.hr.controller.admin.AuditAdminServlet.class,
                com.hr.controller.LeaveServlet.class,
                com.hr.controller.AttendanceServlet.class);
    }

    @AfterAll
    static void stop() throws Exception {
        HttpTestServer.stop();
    }

    // ================================================================== 로그인 · 권한 (REQ-01, 02)

    @Test
    void loginMeLogout() throws Exception {
        HttpClient c = client();
        Res bad = post(c, "api/auth/login", Map.of("loginId", "admin", "password", "wrong-pass1"));
        Res unknown = post(c, "api/auth/login", Map.of("loginId", "nobody", "password", "wrong-pass1"));
        assertEquals(401, bad.status());
        assertEquals(bad.json().get("message"), unknown.json().get("message"), "없는 아이디와 틀린 비밀번호는 같은 메시지");

        Res ok = post(c, "api/auth/login", Map.of("loginId", "admin", "password", "admin1234"));
        assertEquals(200, ok.status());
        assertEquals("ADMIN", ok.json().get("role").asText());
        assertEquals("인사팀", ok.json().get("deptName").asText());
        assertFalse(ok.body().contains("password"), "응답에 비밀번호 해시가 없어야 한다");

        assertEquals("이수진", get(c, "api/auth/me").json().get("name").asText());
        assertEquals(200, post(c, "api/auth/logout", Map.of()).status());
        assertEquals(401, get(c, "api/auth/me").status());
    }

    @Test
    void lockAfterRepeatedFailures() throws Exception {
        HttpClient c = client();
        for (int i = 0; i < 5; i++) {
            assertEquals(401, post(c, "api/auth/login", Map.of("loginId", "choiyr", "password", "nope12345")).status());
        }
        assertEquals(429, post(c, "api/auth/login", Map.of("loginId", "choiyr", "password", "user1234")).status());
    }

    @Test
    void filterBlocksGuestsAndNonAdmins() throws Exception {
        HttpClient guest = client();
        assertEquals(401, get(guest, "api/me").status());
        Res page = get(guest, "user/me.html");
        assertEquals(302, page.status());
        assertTrue(page.location().endsWith("/hr/login.html"));

        HttpClient user = login("yuchan", "user1234");
        assertEquals(403, get(user, "api/admin/employees").status());
        assertEquals(403, get(user, "admin/employees.html").status());
        assertEquals(403, get(user, "%61dmin/employees.html").status(), "인코딩 우회 차단");
        assertEquals(200, get(login("admin", "admin1234"), "admin/employees.html").status());
    }

    // ================================================================== 직원 (REQ-04, 05)

    @Test
    void employeeLifecycle() throws Exception {
        HttpClient admin = login("admin", "admin1234");
        long dev = idOf("SELECT dept_id FROM DEPARTMENT WHERE name = '개발팀'");
        long staff = idOf("SELECT pos_id FROM JOB_POSITION WHERE name = '사원'");
        long park = idOf("SELECT emp_id FROM EMPLOYEE WHERE login_id = 'parkjh'");

        Map<String, Object> body = new java.util.HashMap<>(Map.of("empNo", "E2601", "loginId", "newbie", "password", "short",
                "name", "신입", "deptId", dev, "posId", staff, "managerId", park, "hireDate", "2026-03-02",
                "phone", "010-2601-0001"));
        assertEquals(400, post(admin, "api/admin/employees", body).status(), "약한 비밀번호");
        body.put("password", "welcome2026");
        body.put("annualDays", "11");
        Res created = post(admin, "api/admin/employees", body);
        assertEquals(201, created.status(), created.body());
        long id = created.json().get("empId").asLong();
        assertEquals("박준호", created.json().get("managerName").asText());
        assertEquals(new BigDecimal("11"), scalar("SELECT total_days FROM LEAVE_BALANCE WHERE emp_id = ? AND year = ?", id, YEAR));

        body.put("empNo", "E2602");
        assertEquals(409, post(admin, "api/admin/employees", body).status(), "중복 아이디");

        JsonNode list = get(admin, "api/admin/employees?keyword=%EC%8B%A0%EC%9E%85&status=ACTIVE").json();
        assertEquals(1, list.get("total").asInt());

        // 상사 순환: 박준호의 상사를 신입으로 지정하면 안 된다
        Map<String, Object> parkBody = new java.util.HashMap<>(Map.of("empNo", "E2112", "name", "박준호", "deptId", dev,
                "posId", idOf("SELECT pos_id FROM JOB_POSITION WHERE name = '과장'"), "hireDate", "2021-07-01", "managerId", id));
        assertEquals(400, put(admin, "api/admin/employees/" + park, parkBody).status());

        // 부하 직원이 있는 박준호는 퇴사 처리할 수 없다
        assertEquals(409, post(admin, "api/admin/employees/" + park + "/resign", Map.of()).status());

        HttpClient newbie = login("newbie", "welcome2026");
        assertEquals("신입", get(newbie, "api/me").json().get("name").asText());

        assertEquals(200, post(admin, "api/admin/employees/" + id + "/password", Map.of("password", "reset2026x")).status());
        assertEquals(200, post(admin, "api/admin/employees/" + id + "/resign", Map.of()).status());
        assertEquals(403, post(client(), "api/auth/login", Map.of("loginId", "newbie", "password", "reset2026x")).status());
        assertEquals(401, get(newbie, "api/auth/me").status(), "퇴사하면 남아 있던 세션도 끊긴다");

        // 마지막 관리자는 자기 권한을 내릴 수 없다
        long adminId = idOf("SELECT emp_id FROM EMPLOYEE WHERE login_id = 'admin'");
        Map<String, Object> self = new java.util.HashMap<>(Map.of("empNo", "E2205", "name", "이수진",
                "deptId", idOf("SELECT dept_id FROM DEPARTMENT WHERE name = '인사팀'"),
                "posId", idOf("SELECT pos_id FROM JOB_POSITION WHERE name = '대리'"), "hireDate", "2022-01-10", "role", "USER"));
        assertEquals(400, put(admin, "api/admin/employees/" + adminId, self).status());
    }

    @Test
    void departmentsAndPositions() throws Exception {
        HttpClient admin = login("admin", "admin1234");
        JsonNode depts = get(admin, "api/admin/departments").json();
        assertTrue(depts.isArray() && depts.get(0).has("deptId") && depts.get(0).has("name"), "B 근태 화면이 쓰는 형식");

        Res created = post(admin, "api/admin/departments", Map.of("name", "기획팀"));
        assertEquals(201, created.status());
        assertEquals(409, post(admin, "api/admin/departments", Map.of("name", "기획팀")).status());
        long devId = idOf("SELECT dept_id FROM DEPARTMENT WHERE name = '개발팀'");
        assertEquals(409, delete(admin, "api/admin/departments/" + devId).status(), "직원이 있는 부서");
        assertEquals(200, delete(admin, "api/admin/departments/" + created.json().get("deptId").asLong()).status());

        assertEquals(400, post(admin, "api/admin/positions", Map.of("name", "이사", "level", 120)).status());
        assertEquals(201, post(admin, "api/admin/positions", Map.of("name", "이사", "level", 6)).status());
    }

    @Test
    void changeManagerOnly() throws Exception {
        HttpClient admin = login("admin", "admin1234");
        long han = idOf("SELECT emp_id FROM EMPLOYEE WHERE login_id = 'hanjw'");
        long park = idOf("SELECT emp_id FROM EMPLOYEE WHERE login_id = 'parkjh'");
        Res changed = put(admin, "api/admin/employees/" + han + "/manager", Map.of("managerId", park));
        assertEquals(200, changed.status(), changed.body());
        assertEquals("박준호", changed.json().get("managerName").asText());
        assertEquals("E2318", changed.json().get("empNo").asText(), "다른 필드는 그대로");
        assertEquals(400, put(admin, "api/admin/employees/" + han + "/manager", Map.of("managerId", han)).status());
        assertEquals(403, put(login("yuchan", "user1234"), "api/admin/employees/" + han + "/manager", Map.of()).status());
        Map<String, Object> none = new java.util.HashMap<>();
        none.put("managerId", null);
        assertTrue(put(admin, "api/admin/employees/" + han + "/manager", none).json().get("managerId").isNull());
    }

    // ================================================================== 감사 로그 (REQ-21)

    @Test
    void auditLogRecordsChangesWithoutSecrets() throws Exception {
        HttpClient admin = login("admin", "admin1234");
        post(admin, "api/admin/departments", Map.of("name", "감사확인팀"));
        long jung = idOf("SELECT emp_id FROM EMPLOYEE WHERE login_id = 'jungms'");
        post(admin, "api/admin/employees/" + jung + "/password", Map.of("password", "reset1234a"));

        JsonNode logs = get(admin, "api/admin/audit-logs?size=50").json();
        assertTrue(logs.get("total").asInt() >= 2);
        String all = logs.toString();
        assertTrue(all.contains("감사확인팀"));
        assertTrue(all.contains("PASSWORD_RESET"));
        assertFalse(all.contains("pbkdf2"), "해시는 기록하지 않는다");
        assertEquals(403, get(login("yuchan", "user1234"), "api/admin/audit-logs").status());
    }

    // ================================================================== 내 정보 · 조직도 · 비밀번호 (REQ-03, 07)

    @Test
    void myInfoOrgChartAndPasswordChange() throws Exception {
        HttpClient user = login("hanjw", "user1234");
        assertEquals("E2318", get(user, "api/me").json().get("empNo").asText());
        String org = get(user, "api/org").body();
        assertTrue(org.contains("010-****-3390"), "조직도 연락처는 가린다");
        assertFalse(org.contains("password"));

        assertEquals(400, post(user, "api/auth/password", Map.of("currentPassword", "wrong1234", "newPassword", "next12345")).status());
        assertEquals(400, post(user, "api/auth/password", Map.of("currentPassword", "user1234", "newPassword", "short")).status());
        assertEquals(200, post(user, "api/auth/password", Map.of("currentPassword", "user1234", "newPassword", "next12345")).status());
        login("hanjw", "next12345");
    }

    // ================================================================== D 연동: 채용 전환 어댑터

    @Test
    void employeeCreatorAdapterCreatesEmployeeAndLeave() throws Exception {
        var creator = (EmployeeCreator) context.getServletContext().getAttribute(EmployeeCreator.class.getName());
        assertNotNull(creator, "AppContextListener가 등록해야 한다");
        long dev = idOf("SELECT dept_id FROM DEPARTMENT WHERE name = '개발팀'");
        long staff = idOf("SELECT pos_id FROM JOB_POSITION WHERE name = '사원'");
        try (Connection conn = database.getConnection()) {
            conn.setAutoCommit(false);
            long id = creator.create(conn, new EmployeeCreator.Employee("E2690", "hired01", "hired2026a", "합격자",
                    null, null, dev, staff, null, LocalDate.of(2026, 12, 1)), new BigDecimal("5"));
            conn.commit();
            assertEquals(new BigDecimal("5"), scalar("SELECT total_days FROM LEAVE_BALANCE WHERE emp_id = ? AND year = ?", id, YEAR));
            assertThrows(ApiException.class, () -> creator.create(conn, new EmployeeCreator.Employee("E2691", "hired01",
                    "hired2026a", "중복", null, null, dev, staff, null, LocalDate.of(2026, 12, 1)), BigDecimal.ONE));
            conn.rollback();
        }
        login("hired01", "hired2026a");
    }
}
