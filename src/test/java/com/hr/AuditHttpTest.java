package com.hr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static com.hr.HttpTestServer.YEAR;
import static com.hr.HttpTestServer.context;
import static com.hr.HttpTestServer.database;
import static com.hr.HttpTestServer.execute;
import static com.hr.HttpTestServer.get;
import static com.hr.HttpTestServer.idOf;
import static com.hr.HttpTestServer.login;
import static com.hr.HttpTestServer.put;
import static com.hr.HttpTestServer.post;
import static com.hr.HttpTestServer.scalar;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.hr.HttpTestServer.Res;
import com.hr.d.integration.EmployeeCreator;

import jakarta.servlet.http.HttpServlet;

/**
 * 감사 로그(REQ-21)가 빠졌던 다섯 곳 (근태 수정, 근무 규칙, 연차 조정, 연차 일괄 부여, 채용 전환 직원 생성)을
 * 실제 스키마·시드(H2 Oracle 호환)에서 확인한다.
 */
class AuditHttpTest {

    private static final LocalDate SEOUL_TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @BeforeAll
    static void start(@TempDir Path temp) throws Exception {
        HttpTestServer.start(temp, List.of(
                com.hr.controller.AuthServlet.class,
                com.hr.controller.admin.AuditAdminServlet.class,
                com.hr.controller.admin.AttendanceAdminServlet.class,
                com.hr.controller.admin.LeaveBalanceAdminServlet.class,
                com.hr.controller.admin.WorkPolicyAdminServlet.class));
    }

    @AfterAll
    static void stop() throws Exception {
        HttpTestServer.stop();
    }

    // ================================================================== 근태 수정

    @Test
    void adminAttendanceSaveLogsCreateThenUpdate() throws Exception {
        HttpClient admin = login("admin", "admin1234");
        long adminId = idOf("SELECT emp_id FROM EMPLOYEE WHERE login_id = 'admin'");
        long emp = idOf("SELECT emp_id FROM EMPLOYEE WHERE login_id = 'yuchan'");
        LocalDate date = SEOUL_TODAY.minusDays(3);

        Res created = put(admin, "api/admin/attendance",
                Map.of("empId", emp, "workDate", date.toString(), "checkIn", "09:00", "checkOut", "18:00"));
        assertEquals(200, created.status(), created.body());
        long attId = idOf("SELECT att_id FROM ATTENDANCE WHERE emp_id = ? AND work_date = ?", emp, java.sql.Date.valueOf(date));
        assertEquals(1, count("ATTENDANCE", attId, "CREATE", adminId));
        JsonNode createLog = latest(admin, "ATTENDANCE", attId);
        assertEquals("CREATE", createLog.get("action").asText());
        assertTrue(createLog.get("beforeVal").isNull(), "새로 만들 때 before는 null");
        assertEquals("NORMAL", json(createLog.get("afterVal")).get("status").asText());

        Res updated = put(admin, "api/admin/attendance",
                Map.of("empId", emp, "workDate", date.toString(), "checkIn", "10:30", "checkOut", "18:00"));
        assertEquals(200, updated.status(), updated.body());
        assertEquals(1, count("ATTENDANCE", attId, "UPDATE", adminId));
        JsonNode updateLog = latest(admin, "ATTENDANCE", attId);
        assertEquals("UPDATE", updateLog.get("action").asText());
        assertEquals("LATE", json(updateLog.get("afterVal")).get("status").asText());
        assertEquals("NORMAL", json(updateLog.get("beforeVal")).get("status").asText(), "before는 수정 전 값");
        assertTrue(json(updateLog.get("beforeVal")).get("checkIn").asText().contains("09:00"));

        // 실패한 수정은 감사 로그도 남기지 않는다
        int before = total("ATTENDANCE", attId);
        assertEquals(400, put(admin, "api/admin/attendance",
                Map.of("empId", emp, "workDate", date.toString(), "checkOut", "18:00")).status());
        assertEquals(before, total("ATTENDANCE", attId));
    }

    // ================================================================== 근무 규칙

    @Test
    void workPolicyChangeLogsCreateAndUpdate() throws Exception {
        HttpClient admin = login("admin", "admin1234");
        long adminId = idOf("SELECT emp_id FROM EMPLOYEE WHERE login_id = 'admin'");
        execute("DELETE FROM WORK_POLICY");

        Res created = put(admin, "api/admin/work-policy",
                Map.of("startTime", "08:30", "endTime", "17:30", "lateGraceMin", 5, "weeklyLimitHours", 40));
        assertEquals(200, created.status(), created.body());
        long policyId = idOf("SELECT policy_id FROM WORK_POLICY");
        assertEquals(1, count("WORK_POLICY", policyId, "CREATE", adminId));
        JsonNode createLog = latest(admin, "WORK_POLICY", policyId);
        assertTrue(createLog.get("beforeVal").isNull());
        assertEquals("08:30", json(createLog.get("afterVal")).get("startTime").asText());

        Res updated = put(admin, "api/admin/work-policy",
                Map.of("startTime", "09:00", "endTime", "18:00", "lateGraceMin", 10, "weeklyLimitHours", 52));
        assertEquals(200, updated.status(), updated.body());
        assertEquals(1, count("WORK_POLICY", policyId, "UPDATE", adminId));
        JsonNode updateLog = latest(admin, "WORK_POLICY", policyId);
        assertEquals("08:30", json(updateLog.get("beforeVal")).get("startTime").asText());
        assertEquals("09:00", json(updateLog.get("afterVal")).get("startTime").asText());
        assertEquals(52, json(updateLog.get("afterVal")).get("weeklyLimitHours").asInt());
    }

    // ================================================================== 연차 조정

    @Test
    void leaveBalanceAdjustLogsUpdateAndCreate() throws Exception {
        HttpClient admin = login("admin", "admin1234");
        long adminId = idOf("SELECT emp_id FROM EMPLOYEE WHERE login_id = 'admin'");
        long emp = idOf("SELECT emp_id FROM EMPLOYEE WHERE login_id = 'hanjw'");

        // 올해 연차는 시드에 있으므로 UPDATE
        Res updated = put(admin, "api/admin/leave-balances", Map.of("empId", emp, "year", YEAR, "totalDays", 20));
        assertEquals(200, updated.status(), updated.body());
        assertEquals(1, count("LEAVE_BALANCE", emp, "UPDATE", adminId));
        JsonNode updateLog = latest(admin, "LEAVE_BALANCE", emp);
        assertEquals(0, new BigDecimal("15").compareTo(json(updateLog.get("beforeVal")).get("totalDays").decimalValue()));
        assertEquals(0, new BigDecimal("20").compareTo(json(updateLog.get("afterVal")).get("totalDays").decimalValue()));

        // 내년 연차 행이 없으면 CREATE
        Res created = put(admin, "api/admin/leave-balances", Map.of("empId", emp, "year", YEAR + 1, "totalDays", 11));
        assertEquals(200, created.status(), created.body());
        assertEquals(1, count("LEAVE_BALANCE", emp, "CREATE", adminId));
        JsonNode createLog = latest(admin, "LEAVE_BALANCE", emp);
        assertTrue(createLog.get("beforeVal").isNull());
        assertEquals(YEAR + 1, json(createLog.get("afterVal")).get("year").asInt());

        // 이미 쓴 일수보다 적게 줄이면 409, 감사 로그도 남지 않는다
        execute("UPDATE LEAVE_BALANCE SET used_days = 3 WHERE emp_id = ? AND year = ?", emp, YEAR);
        int before = total("LEAVE_BALANCE", emp);
        assertEquals(409, put(admin, "api/admin/leave-balances", Map.of("empId", emp, "year", YEAR, "totalDays", 2)).status());
        assertEquals(before, total("LEAVE_BALANCE", emp));
    }

    // ================================================================== 연차 일괄 부여

    @Test
    void grantAllLogsCreatePerEmployee() throws Exception {
        HttpClient admin = login("admin", "admin1234");
        long adminId = idOf("SELECT emp_id FROM EMPLOYEE WHERE login_id = 'admin'");
        int year = YEAR + 2;
        long expected = idOf("SELECT COUNT(*) FROM EMPLOYEE WHERE status <> 'RESIGNED'");

        Res granted = post(admin, "api/admin/leave-balances/grant", Map.of("year", year, "days", 15));
        assertEquals(200, granted.status(), granted.body());
        assertEquals(expected, granted.json().get("granted").asLong());
        assertEquals(expected, idOf("SELECT COUNT(*) FROM LEAVE_BALANCE WHERE year = ?", year));

        // 부여받은 직원마다 CREATE 1건, 로그의 대상은 그 직원 ID
        String yearLike = "%\"year\":" + year + "%";
        assertEquals(expected, idOf("SELECT COUNT(*) FROM AUDIT_LOG WHERE target_table = 'LEAVE_BALANCE'"
                + " AND action = 'CREATE' AND actor_id = ? AND after_val LIKE ?", adminId, yearLike));
        assertEquals(expected, idOf("SELECT COUNT(DISTINCT l.target_id) FROM AUDIT_LOG l JOIN LEAVE_BALANCE b"
                + " ON b.emp_id = l.target_id AND b.year = ? WHERE l.target_table = 'LEAVE_BALANCE' AND l.after_val LIKE ?",
                year, yearLike));

        // 이미 부여된 해에 다시 부여하면 대상이 없으므로 로그도 늘지 않는다
        long totalBefore = idOf("SELECT COUNT(*) FROM AUDIT_LOG WHERE target_table = 'LEAVE_BALANCE'");
        Res again = post(admin, "api/admin/leave-balances/grant", Map.of("year", year, "days", 15));
        assertEquals(0, again.json().get("granted").asInt());
        assertEquals(totalBefore, idOf("SELECT COUNT(*) FROM AUDIT_LOG WHERE target_table = 'LEAVE_BALANCE'"));
    }

    // ================================================================== D 채용 전환

    @Test
    void hireAdapterLogsEmployeeCreateInSameTransactionWithoutPasswordHash() throws Exception {
        var creator = (EmployeeCreator) context.getServletContext().getAttribute(EmployeeCreator.class.getName());
        long adminId = idOf("SELECT emp_id FROM EMPLOYEE WHERE login_id = 'admin'");
        long dev = idOf("SELECT dept_id FROM DEPARTMENT WHERE name = '개발팀'");
        long staff = idOf("SELECT pos_id FROM JOB_POSITION WHERE name = '사원'");

        // 롤백하면 직원도 감사 로그도 남지 않는다 (같은 트랜잭션)
        try (Connection conn = database.getConnection()) {
            conn.setAutoCommit(false);
            long id = creator.create(conn, new EmployeeCreator.Employee("E3001", "audit-rb", "hired2026a", "롤백",
                    null, null, dev, staff, null, SEOUL_TODAY), new BigDecimal("5"), adminId);
            assertTrue(id > 0);
            conn.rollback();
        }
        assertEquals(0, idOf("SELECT COUNT(*) FROM EMPLOYEE WHERE login_id = 'audit-rb'"));
        assertEquals(0, idOf("SELECT COUNT(*) FROM AUDIT_LOG WHERE target_table = 'EMPLOYEE' AND after_val LIKE '%audit-rb%'"));

        long id;
        try (Connection conn = database.getConnection()) {
            conn.setAutoCommit(false);
            id = creator.create(conn, new EmployeeCreator.Employee("E3002", "audit-ok", "hired2026a", "합격자",
                    null, null, dev, staff, null, SEOUL_TODAY), new BigDecimal("5"), adminId);
            conn.commit();
        }
        assertEquals(1, count("EMPLOYEE", id, "CREATE", adminId));
        HttpClient admin = login("admin", "admin1234");
        JsonNode log = latest(admin, "EMPLOYEE", id);
        assertTrue(log.get("beforeVal").isNull());
        String after = log.get("afterVal").asText();
        assertEquals("audit-ok", json(log.get("afterVal")).get("loginId").asText());
        assertFalse(after.toLowerCase().contains("password"), after);
        assertFalse(after.contains("pbkdf2"), after);
    }

    @Test
    void hireAdapterWithoutActorStillLogsWithoutActor() throws Exception {
        var creator = (EmployeeCreator) context.getServletContext().getAttribute(EmployeeCreator.class.getName());
        long dev = idOf("SELECT dept_id FROM DEPARTMENT WHERE name = '개발팀'");
        long staff = idOf("SELECT pos_id FROM JOB_POSITION WHERE name = '사원'");
        long id;
        try (Connection conn = database.getConnection()) {
            conn.setAutoCommit(false);
            id = creator.create(conn, new EmployeeCreator.Employee("E3003", "audit-old", "hired2026a", "옛 호출",
                    null, null, dev, staff, null, SEOUL_TODAY), new BigDecimal("5"));
            conn.commit();
        }
        assertEquals(1, idOf("SELECT COUNT(*) FROM AUDIT_LOG WHERE target_table = 'EMPLOYEE' AND action = 'CREATE'"
                + " AND target_id = ? AND actor_id IS NULL", id));
    }

    // ================================================================== 공통

    @Test
    void auditViewAndNoPasswordHashAnywhere() throws Exception {
        HttpClient admin = login("admin", "admin1234");
        String page = get(admin, "admin/audit.html").body();
        assertTrue(page.contains("value=\"WORK_POLICY\""), "대상 필터에 근무 규칙이 있어야 한다");
        assertTrue(get(admin, "user/me.html").body().contains("certificate.html?type=career"));

        // 대상별 조회: WORK_POLICY로 거른 결과에는 그 대상만 나온다
        put(admin, "api/admin/work-policy", Map.of("startTime", "09:00", "endTime", "18:00", "lateGraceMin", 10, "weeklyLimitHours", 52));
        JsonNode items = get(admin, "api/admin/audit-logs?table=WORK_POLICY").json().get("items");
        assertTrue(items.size() > 0);
        items.forEach(i -> assertEquals("WORK_POLICY", i.get("targetTable").asText()));

        Object hashed = scalar("SELECT COUNT(*) FROM AUDIT_LOG WHERE LOWER(before_val) LIKE '%pbkdf2%'"
                + " OR LOWER(after_val) LIKE '%pbkdf2%' OR LOWER(before_val) LIKE '%password%' OR LOWER(after_val) LIKE '%password%'");
        assertEquals(0L, ((Number) hashed).longValue(), "감사 로그에 비밀번호 해시·비밀번호 필드가 없어야 한다");
    }

    // ------------------------------------------------------------------ 도우미

    private static int count(String table, long targetId, String action, long actorId) throws Exception {
        return (int) idOf("SELECT COUNT(*) FROM AUDIT_LOG WHERE target_table = ? AND target_id = ? AND action = ? AND actor_id = ?",
                table, targetId, action, actorId);
    }

    private static int total(String table, long targetId) throws Exception {
        return (int) idOf("SELECT COUNT(*) FROM AUDIT_LOG WHERE target_table = ? AND target_id = ?", table, targetId);
    }

    /** 관리자 조회 API로 그 대상의 가장 최근 로그 (대상별 조회가 되는지도 함께 확인) */
    private static JsonNode latest(HttpClient admin, String table, long targetId) throws Exception {
        Res r = get(admin, "api/admin/audit-logs?table=" + table + "&targetId=" + targetId);
        assertEquals(200, r.status(), r.body());
        JsonNode items = r.json().get("items");
        assertTrue(items.size() > 0, table + " #" + targetId + " 로그가 조회돼야 한다");
        return items.get(0);
    }

    private static JsonNode json(JsonNode textNode) throws Exception {
        return HttpTestServer.JSON.readTree(textNode.asText());
    }
}
