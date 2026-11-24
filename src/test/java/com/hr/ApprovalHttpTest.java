package com.hr;

import static com.hr.HttpTestServer.YEAR;
import static com.hr.HttpTestServer.context;
import static com.hr.HttpTestServer.database;
import static com.hr.HttpTestServer.execute;
import static com.hr.HttpTestServer.get;
import static com.hr.HttpTestServer.idOf;
import static com.hr.HttpTestServer.login;
import static com.hr.HttpTestServer.post;
import static com.hr.HttpTestServer.scalar;
import static com.hr.HttpTestServer.upload;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.hr.HttpTestServer.Res;
import com.hr.d.integration.NoticeNotifier;

import jakarta.servlet.http.HttpServlet;

/**
 * 결재·경비·알림·첨부(C) HTTP 테스트. B의 휴가 신청과 실제로 이어 붙여 결재선·후처리·알림·권한을 검증한다.
 * 테스트끼리 같은 DB를 쓰므로 휴가 날짜를 서로 겹치지 않게 고르고, 개수는 "포함 여부"로 확인한다.
 */
class ApprovalHttpTest {

    /** 그해 첫 월요일. 휴가는 과거 날짜도 신청할 수 있으므로 테스트마다 다른 주를 쓴다 */
    private static final LocalDate MONDAY = LocalDate.of(YEAR, 1, 5).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    private static final byte[] PNG = bytes(new int[] {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'});
    private static final byte[] PDF = "%PDF-1.4\n%이력서\n1 0 obj << >> endobj\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

    @BeforeAll
    static void start(@TempDir Path temp) throws Exception {
        HttpTestServer.start(temp, List.of(
                com.hr.controller.AuthServlet.class,
                com.hr.controller.MeServlet.class,
                com.hr.controller.admin.EmployeeAdminServlet.class,
                com.hr.controller.LeaveServlet.class,
                com.hr.controller.ApprovalServlet.class,
                com.hr.controller.admin.ApprovalAdminServlet.class,
                com.hr.controller.admin.ApprovalLineAdminServlet.class,
                com.hr.controller.ExpenseServlet.class,
                com.hr.controller.admin.ExpenseAdminServlet.class,
                com.hr.controller.FileServlet.class,
                com.hr.controller.admin.FileAdminServlet.class,
                com.hr.controller.NotificationServlet.class));
    }

    @AfterAll
    static void stop() throws Exception {
        HttpTestServer.stop();
    }

    // ================================================================== 결재선 · 승인 (REQ-17~19)

    @Test
    void leaveGoesManagerThenHrAndDeductsOnce() throws Exception {
        HttpClient yu = login("yuchan", "user1234");
        HttpClient park = login("parkjh", "user1234");
        HttpClient admin = login("admin", "admin1234");
        HttpClient han = login("hanjw", "user1234");
        long yuId = emp("yuchan");

        long reqId = applyLeave(yu, "ANNUAL", day(0, 0), day(0, 1));
        long docId = docOf("LEAVE", reqId);
        JsonNode d = get(yu, "api/approvals/" + docId).json();
        assertEquals("IN_PROGRESS", d.at("/doc/status").asText());
        assertEquals(2, d.get("steps").size());
        assertEquals("MANAGER", d.at("/steps/0/role").asText());
        assertEquals("박준호", d.at("/steps/0/approverName").asText());
        assertEquals("HR", d.at("/steps/1/role").asText());
        assertTrue(d.at("/steps/1/approverId").isNull(), "인사 단계는 처리한 관리자가 기록된다");
        assertEquals("2", d.at("/detail/days").asText());
        assertFalse(d.get("canAct").asBoolean());
        assertTrue(d.get("canCancel").asBoolean());

        assertEquals(403, approve(yu, docId).status(), "본인 문서");
        assertEquals(403, approve(admin, docId).status(), "상사 단계는 지정된 상사만");
        assertEquals(404, get(han, "api/approvals/" + docId).status(), "관계없는 직원에게는 없는 문서");
        assertTrue(ids(get(park, "api/approvals?box=todo").json()).contains(docId));
        assertFalse(ids(get(admin, "api/approvals?box=todo").json()).contains(docId));
        assertEquals(1, notifications(emp("parkjh"), "APPROVAL_REQUEST", "%id=" + docId));

        Res first = post(park, "api/approvals/" + docId + "/approve", Map.of("comment", "확인했습니다."));
        assertEquals(200, first.status(), first.body());
        assertEquals(2, first.json().at("/doc/currentStep").asInt());
        assertEquals("확인했습니다.", first.json().at("/steps/0/comment").asText());
        assertEquals(403, approve(park, docId).status(), "이미 지나간 단계");
        assertEquals(1, notifications(yuId, "APPROVAL_STEP", "%id=" + docId));
        assertEquals(2, notifications(null, "APPROVAL_REQUEST", "/admin/approvals.html?id=" + docId), "관리자 두 명");
        assertTrue(ids(get(admin, "api/admin/approvals").json()).contains(docId), "관리자 결재 대기(기본)");

        BigDecimal usedBefore = used(yuId);
        Res last = approve(admin, docId);
        assertEquals(200, last.status(), last.body());
        assertEquals("APPROVED", last.json().at("/doc/status").asText());
        assertEquals("이수진", last.json().at("/steps/1/approverName").asText());
        assertEquals("APPROVED", scalar("SELECT status FROM LEAVE_REQUEST WHERE req_id = ?", reqId));
        assertEquals(0, usedBefore.add(new BigDecimal("2")).compareTo(used(yuId)), "연차는 최종 승인 때 한 번만 차감");
        assertEquals(1, notifications(yuId, "APPROVED", "%id=" + docId));
        assertEquals(2L, ((Number) scalar("SELECT COUNT(*) FROM AUDIT_LOG WHERE target_table = 'APPROVAL_DOC' "
                + "AND target_id = ? AND action = 'APPROVE'", docId)).longValue());

        assertEquals(409, approve(admin, docId).status(), "끝난 문서");
        assertEquals(409, post(yu, "api/approvals/" + docId + "/cancel", Map.of()).status());
        assertTrue(ids(get(park, "api/approvals?box=done").json()).contains(docId));
        assertTrue(ids(get(yu, "api/approvals?box=mine").json()).contains(docId));
    }

    @Test
    void rejectNeedsCommentAndEndsImmediately() throws Exception {
        HttpClient han = login("hanjw", "user1234");
        HttpClient admin = login("admin", "admin1234");
        long reqId = applyLeave(han, "SICK", day(1, 0), day(1, 0));
        long docId = docOf("LEAVE", reqId);
        JsonNode d = get(han, "api/approvals/" + docId).json();
        assertEquals(1, d.get("steps").size(), "상사가 없으면 인사 관리자 1단계");

        assertEquals(400, post(admin, "api/approvals/" + docId + "/reject", Map.of()).status());
        assertEquals(400, post(admin, "api/approvals/" + docId + "/reject", Map.of("comment", "   ")).status());
        Res r = post(admin, "api/approvals/" + docId + "/reject", Map.of("comment", "진단서를 첨부해 주세요"));
        assertEquals(200, r.status(), r.body());
        assertEquals("REJECTED", r.json().at("/doc/status").asText());
        assertEquals("REJECTED", scalar("SELECT status FROM LEAVE_REQUEST WHERE req_id = ?", reqId));
        assertTrue(((String) scalar("SELECT message FROM NOTIFICATION WHERE emp_id = ? AND noti_type = 'REJECTED' "
                + "AND link LIKE ?", emp("hanjw"), "%id=" + docId)).contains("진단서"));
    }

    @Test
    void adminsOwnRequestIsApprovedByAnotherAdmin() throws Exception {
        HttpClient admin = login("admin", "admin1234");
        HttpClient choi = login("choiyr", "user1234");
        long docId = docOf("LEAVE", applyLeave(admin, "ANNUAL", day(2, 0), day(2, 0)));
        assertFalse(ids(get(admin, "api/admin/approvals").json()).contains(docId));
        Res own = approve(admin, docId);
        assertEquals(403, own.status());
        assertTrue(own.json().get("message").asText().contains("본인"));
        assertTrue(ids(get(choi, "api/admin/approvals").json()).contains(docId));
        Res ok = approve(choi, docId);
        assertEquals("APPROVED", ok.json().at("/doc/status").asText(), ok.body());
        assertEquals("최예린", ok.json().at("/steps/0/approverName").asText());

        // 다른 관리자가 없으면 신청 자체를 막는다 (휴가 신청도 함께 롤백)
        execute("UPDATE EMPLOYEE SET role = 'USER' WHERE login_id = 'choiyr'");
        try {
            Res blocked = post(admin, "api/leaves", leave("ANNUAL", day(2, 1), day(2, 1)));
            assertEquals(409, blocked.status(), blocked.body());
            assertEquals(0L, ((Number) scalar("SELECT COUNT(*) FROM LEAVE_REQUEST WHERE emp_id = ? AND start_date = ?",
                    emp("admin"), java.sql.Date.valueOf(day(2, 1)))).longValue());
        } finally {
            execute("UPDATE EMPLOYEE SET role = 'ADMIN' WHERE login_id = 'choiyr'");
        }
    }

    @Test
    void inactiveManagerIsSkippedOrHandledByAdmin() throws Exception {
        HttpClient yu = login("yuchan", "user1234");
        HttpClient admin = login("admin", "admin1234");
        long waiting = docOf("LEAVE", applyLeave(yu, "ANNUAL", day(3, 0), day(3, 0)));
        execute("UPDATE EMPLOYEE SET status = 'LEAVE' WHERE login_id = 'parkjh'");
        try {
            assertTrue(ids(get(admin, "api/admin/approvals").json()).contains(waiting), "휴직한 상사 대신 관리자가 처리");
            Res step = approve(admin, waiting);
            assertEquals(200, step.status(), step.body());
            assertEquals("이수진", step.json().at("/steps/0/approverName").asText(), "대신 처리한 사람이 남는다");

            long fresh = docOf("LEAVE", applyLeave(yu, "ANNUAL", day(3, 2), day(3, 2)));
            JsonNode d = get(yu, "api/approvals/" + fresh).json();
            assertEquals(1, d.get("steps").size(), "휴직한 상사는 새 결재선에서 빠진다");
            assertEquals("HR", d.at("/steps/0/role").asText());
        } finally {
            execute("UPDATE EMPLOYEE SET status = 'ACTIVE' WHERE login_id = 'parkjh'");
        }
    }

    @Test
    void drafterCanCancelOnlyWhileInProgress() throws Exception {
        HttpClient yu = login("yuchan", "user1234");
        HttpClient park = login("parkjh", "user1234");
        HttpClient han = login("hanjw", "user1234");

        long req1 = applyLeave(yu, "ANNUAL", day(4, 0), day(4, 0));
        long doc1 = docOf("LEAVE", req1);
        assertEquals(404, post(han, "api/approvals/" + doc1 + "/cancel", Map.of()).status());
        assertEquals(403, post(park, "api/approvals/" + doc1 + "/cancel", Map.of()).status());
        Res canceled = post(yu, "api/approvals/" + doc1 + "/cancel", Map.of());
        assertEquals(200, canceled.status(), canceled.body());
        assertEquals("CANCELED", canceled.json().at("/doc/status").asText());
        assertEquals("CANCELED", scalar("SELECT status FROM LEAVE_REQUEST WHERE req_id = ?", req1));
        assertEquals(0L, ((Number) scalar("SELECT COUNT(*) FROM APPROVAL_STEP WHERE doc_id = ? AND status <> 'CANCELED'",
                doc1)).longValue());
        assertFalse(ids(get(park, "api/approvals?box=todo").json()).contains(doc1));

        long req2 = applyLeave(yu, "ANNUAL", day(4, 1), day(4, 1));
        assertEquals(200, post(yu, "api/leaves/" + req2 + "/cancel", Map.of()).status(), "B의 휴가 취소도 결재를 취소");
        long doc2 = docOf("LEAVE", req2);
        assertEquals("CANCELED", scalar("SELECT status FROM APPROVAL_DOC WHERE doc_id = ?", doc2));
        assertEquals(409, approve(park, doc2).status());
    }

    // ================================================================== 경비 · 첨부 (REQ-23, 24, 31)

    @Test
    void expenseWithReceiptIsApprovedThenPaid() throws Exception {
        HttpClient yu = login("yuchan", "user1234");
        HttpClient park = login("parkjh", "user1234");
        HttpClient admin = login("admin", "admin1234");
        HttpClient han = login("hanjw", "user1234");

        Res created = upload(yu, "api/expenses", expense("MEAL", today(), "48,000", "고객 미팅"), "receipt",
                "영수증 11월.png", "image/png", PNG);
        assertEquals(201, created.status(), created.body());
        JsonNode e = created.json();
        long expId = e.get("expId").asLong();
        long docId = e.get("docId").asLong();
        long fileId = e.get("receiptId").asLong();
        assertEquals("PENDING", e.get("status").asText());
        assertEquals(48000, e.get("amount").asLong());
        assertEquals("식대 48,000원 · 고객 미팅", scalar("SELECT title FROM APPROVAL_DOC WHERE doc_id = ?", docId));
        JsonNode detail = get(yu, "api/approvals/" + docId).json();
        assertEquals("영수증 11월.png", detail.at("/detail/attachments/0/originName").asText());
        assertFalse(detail.at("/detail/attachments/0").has("storedName"));

        Res file = get(yu, "api/files/" + fileId);
        assertEquals(200, file.status());
        assertEquals("image/png", file.header("content-type"));
        assertArrayEquals(PNG, file.bytes());
        assertTrue(file.header("content-disposition").contains("filename*=UTF-8''%EC%98%81%EC%88%98%EC%A6%9D"));
        assertEquals("nosniff", file.header("x-content-type-options"));
        assertEquals(200, get(park, "api/files/" + fileId).status(), "결재자");
        assertEquals(200, get(admin, "api/files/" + fileId).status(), "관리자");
        assertEquals(404, get(han, "api/files/" + fileId).status(), "관계없는 직원");

        assertEquals(409, post(admin, "api/admin/expenses/" + expId + "/pay", Map.of()).status(), "승인 전 지급 불가");
        assertEquals(200, approve(park, docId).status());
        assertEquals(200, approve(admin, docId).status());
        assertEquals("APPROVED", scalar("SELECT status FROM EXPENSE WHERE exp_id = ?", expId), "지급 대기");
        JsonNode waiting = get(admin, "api/admin/expenses").json();
        assertTrue(ids(waiting, "expId").contains(expId));
        assertTrue(waiting.get("totalAmount").asLong() >= 48000);
        assertEquals(409, post(yu, "api/expenses/" + expId + "/cancel", Map.of()).status());
        assertEquals(403, post(yu, "api/admin/expenses/" + expId + "/pay", Map.of()).status());

        Res paid = post(admin, "api/admin/expenses/" + expId + "/pay", Map.of());
        assertEquals(200, paid.status(), paid.body());
        assertEquals("PAID", paid.json().get("status").asText());
        assertFalse(paid.json().get("paidAt").isNull());
        assertEquals(409, post(admin, "api/admin/expenses/" + expId + "/pay", Map.of()).status(), "두 번 지급 불가");
        assertEquals(1, notifications(emp("yuchan"), "EXPENSE_PAID", "/user/expenses.html"));
        assertTrue(ids(get(yu, "api/expenses").json(), "expId").contains(expId));
    }

    @Test
    void expenseInputAndFileAreValidated() throws Exception {
        HttpClient han = login("hanjw", "user1234");
        long before = ((Number) scalar("SELECT COUNT(*) FROM EXPENSE WHERE emp_id = ?", emp("hanjw"))).longValue();
        Map<String, String> ok = expense("TRANSPORT", today(), "23500", "택시비");

        assertEquals(400, upload(han, "api/expenses", ok, "receipt", null, null, null).status(), "영수증 없음");
        assertEquals(400, upload(han, "api/expenses", ok, "receipt", "fake.png", "image/png",
                "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)).status(), "확장자만 바꾼 파일");
        byte[] big = new byte[5 * 1024 * 1024 + 10];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertEquals(413, upload(han, "api/expenses", ok, "receipt", "big.png", "image/png", big).status());
        assertEquals(400, upload(han, "api/expenses", expense("TRANSPORT", today(), "0", "택시비"), "receipt", "r.png", "image/png", PNG).status());
        assertEquals(400, upload(han, "api/expenses", expense("TRANSPORT", today(), "만원", "택시비"), "receipt", "r.png", "image/png", PNG).status());
        assertEquals(400, upload(han, "api/expenses", expense("TRANSPORT", today().plusDays(1), "100", "택시비"), "receipt", "r.png", "image/png", PNG).status());
        assertEquals(400, upload(han, "api/expenses", expense("FOOD", today(), "100", "택시비"), "receipt", "r.png", "image/png", PNG).status());
        assertEquals(400, upload(han, "api/expenses", expense("TRANSPORT", today(), "100", " "), "receipt", "r.png", "image/png", PNG).status());
        assertEquals(400, post(han, "api/expenses", Map.of("category", "MEAL")).status(), "multipart가 아님");
        assertEquals(before, ((Number) scalar("SELECT COUNT(*) FROM EXPENSE WHERE emp_id = ?", emp("hanjw"))).longValue());
        assertEquals(storedFiles(), ((Number) scalar("SELECT COUNT(*) FROM ATTACHMENT")).longValue(), "실패한 업로드는 파일을 남기지 않는다");

        // 반려 → 취소 불가, 진행 중 → 취소 가능
        HttpClient admin = login("admin", "admin1234");
        JsonNode rejected = upload(han, "api/expenses", ok, "receipt", "taxi.jpg", "image/jpeg", jpg()).json();
        assertEquals(200, post(admin, "api/approvals/" + rejected.get("docId").asLong() + "/reject",
                Map.of("comment", "법인카드로 결제한 건입니다")).status());
        assertEquals("REJECTED", scalar("SELECT status FROM EXPENSE WHERE exp_id = ?", rejected.get("expId").asLong()));
        assertEquals(409, post(han, "api/expenses/" + rejected.get("expId").asLong() + "/cancel", Map.of()).status());
        JsonNode pending = upload(han, "api/expenses", ok, "receipt", "receipt.pdf", "application/pdf", PDF).json();
        Res canceled = post(han, "api/expenses/" + pending.get("expId").asLong() + "/cancel", Map.of());
        assertEquals("CANCELED", canceled.json().get("status").asText(), canceled.body());
        assertEquals("CANCELED", scalar("SELECT status FROM APPROVAL_DOC WHERE doc_id = ?", pending.get("docId").asLong()));
    }

    @Test
    void resumeUploadLinksApplicant() throws Exception {
        HttpClient admin = login("admin", "admin1234");
        HttpClient yu = login("yuchan", "user1234");
        execute("INSERT INTO JOB_POSTING (posting_id, title, dept_id, pos_id, open_date, close_date) "
                + "VALUES (900, '백엔드 개발자', (SELECT dept_id FROM DEPARTMENT WHERE name = '개발팀'), "
                + "(SELECT pos_id FROM JOB_POSITION WHERE name = '사원'), DATE '2026-01-01', DATE '2026-12-31')");
        execute("INSERT INTO APPLICANT (app_id, posting_id, name) VALUES (900, 900, '지원자')");

        Res up = upload(admin, "api/admin/files/applicants/900", Map.of(), "file", "이력서.pdf", "application/pdf", PDF);
        assertEquals(201, up.status(), up.body());
        long fileId = up.json().get("fileId").asLong();
        assertFalse(up.json().has("storedName"), "저장 파일 이름은 내보내지 않는다");
        assertEquals(fileId, ((Number) scalar("SELECT resume_file_id FROM APPLICANT WHERE app_id = 900")).longValue());
        Res file = get(admin, "api/files/" + fileId + "?download=1");
        assertEquals("application/pdf", file.header("content-type"));
        assertTrue(file.header("content-disposition").startsWith("attachment"));
        assertEquals(404, get(yu, "api/files/" + fileId).status(), "이력서는 관리자만");
        assertEquals(403, upload(yu, "api/admin/files/applicants/900", Map.of(), "file", "a.pdf", "application/pdf", PDF).status());
        assertEquals(404, upload(admin, "api/admin/files/applicants/999", Map.of(), "file", "a.pdf", "application/pdf", PDF).status());
        assertEquals(storedFiles(), ((Number) scalar("SELECT COUNT(*) FROM ATTACHMENT")).longValue());
    }

    // ================================================================== 알림 (REQ-20)

    @Test
    void notificationsAreReadByOwnerOnly() throws Exception {
        long yuId = emp("yuchan");
        var notifier = (NoticeNotifier) context.getServletContext().getAttribute(NoticeNotifier.class.getName());
        assertNotNull(notifier, "D 공지 알림 어댑터 등록");
        try (Connection conn = database.getConnection()) {
            conn.setAutoCommit(false);
            notifier.send(conn, yuId, "NOTICE", "새 공지: 연말 일정", "/user/notices.html?id=1");
            notifier.send(conn, yuId, "NOTICE", "외부 링크는 버린다", "https://evil.example/");
            assertThrows(IllegalArgumentException.class, () -> notifier.send(conn, yuId, "SPAM", "x", null));
            conn.commit();
        }
        assertEquals(null, scalar("SELECT link FROM NOTIFICATION WHERE message = '외부 링크는 버린다'"));

        HttpClient yu = login("yuchan", "user1234");
        JsonNode list = get(yu, "api/notifications?size=5").json();
        assertEquals("외부 링크는 버린다", list.at("/items/0/message").asText(), "최신순");
        int unread = list.get("unread").asInt();
        assertTrue(unread >= 2);
        assertEquals(unread, get(yu, "api/notifications/unread-count").json().get("count").asInt());

        long notiId = list.at("/items/1/notiId").asLong();
        assertEquals(404, post(login("parkjh", "user1234"), "api/notifications/" + notiId + "/read", Map.of()).status());
        assertEquals(200, post(yu, "api/notifications/" + notiId + "/read", Map.of()).status());
        assertEquals(unread - 1, get(yu, "api/notifications/unread-count").json().get("count").asInt());
        assertEquals(200, post(yu, "api/notifications/read-all", Map.of()).status());
        assertEquals(0, get(yu, "api/notifications/unread-count").json().get("count").asInt());
    }

    // ================================================================== 관리자 화면 API

    @Test
    void approvalLinesAndAdminFilters() throws Exception {
        HttpClient admin = login("admin", "admin1234");
        JsonNode yuLine = get(admin, "api/admin/approval-lines?keyword=%EA%B9%80%EC%9C%A0%EC%B0%AC").json();
        assertEquals(1, yuLine.get("total").asInt());
        assertEquals("박준호", yuLine.at("/items/0/steps/0/approverName").asText());
        assertEquals("HR", yuLine.at("/items/0/steps/1/role").asText());
        assertEquals(2, yuLine.get("admins").size());
        JsonNode adminLine = get(admin, "api/admin/approval-lines?keyword=%EC%9D%B4%EC%88%98%EC%A7%84").json();
        assertEquals(1, adminLine.at("/items/0/steps").size());
        assertFalse(adminLine.at("/items/0/blocked").asBoolean());

        JsonNode expenses = get(admin, "api/admin/approvals?status=ALL&type=EXPENSE&size=100").json();
        expenses.get("items").forEach(i -> assertEquals("EXPENSE", i.get("docType").asText()));
        assertEquals(400, get(admin, "api/admin/approvals?status=DONE").status());
        assertEquals(400, get(admin, "api/admin/approvals?type=MEMO").status());
        HttpClient yu = login("yuchan", "user1234");
        assertEquals(400, get(yu, "api/approvals?box=all").status());
        assertEquals(403, get(yu, "api/admin/approvals").status());
        assertEquals(403, get(yu, "api/admin/approval-lines").status());
        JsonNode counts = get(yu, "api/approvals/counts").json();
        assertTrue(counts.has("mine") && counts.has("todo") && counts.has("done"));
    }

    // ================================================================== 도우미

    private static String day(int week, int weekday) {
        return MONDAY.plusWeeks(week).plusDays(weekday).toString();
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    private static Map<String, Object> leave(String type, String start, String end) {
        return Map.of("leaveType", type, "startDate", start, "endDate", end, "reason", "개인 사유");
    }

    private static long applyLeave(HttpClient c, String type, String start, String end) throws Exception {
        Res r = post(c, "api/leaves", leave(type, start, end));
        assertEquals(201, r.status(), r.body());
        return r.json().get("reqId").asLong();
    }

    private static Map<String, String> expense(String category, LocalDate useDate, String amount, String description) {
        Map<String, String> m = new HashMap<>();
        m.put("category", category);
        m.put("useDate", useDate.toString());
        m.put("amount", amount);
        m.put("description", description);
        return m;
    }

    private static Res approve(HttpClient c, long docId) throws Exception {
        return post(c, "api/approvals/" + docId + "/approve", Map.of());
    }

    private static long docOf(String type, long refId) throws Exception {
        return idOf("SELECT doc_id FROM APPROVAL_DOC WHERE doc_type = ? AND ref_id = ?", type, refId);
    }

    private static long emp(String loginId) throws Exception {
        return idOf("SELECT emp_id FROM EMPLOYEE WHERE login_id = ?", loginId);
    }

    private static BigDecimal used(long empId) throws Exception {
        return (BigDecimal) scalar("SELECT used_days FROM LEAVE_BALANCE WHERE emp_id = ? AND year = ?", empId, YEAR);
    }

    /** empId가 null이면 받는 사람 상관없이 */
    private static int notifications(Long empId, String type, String linkLike) throws Exception {
        String sql = "SELECT COUNT(*) FROM NOTIFICATION WHERE noti_type = ? AND link LIKE ?" + (empId == null ? "" : " AND emp_id = ?");
        Object[] params = empId == null ? new Object[] {type, linkLike} : new Object[] {type, linkLike, empId};
        return ((Number) scalar(sql, params)).intValue();
    }

    private static List<Long> ids(JsonNode page) {
        return ids(page, "docId");
    }

    private static List<Long> ids(JsonNode page, String field) {
        List<Long> ids = new ArrayList<>();
        page.get("items").forEach(i -> ids.add(i.get(field).asLong()));
        return ids;
    }

    private static long storedFiles() throws Exception {
        Path dir = Path.of(System.getProperty("hr.upload.dir"));
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> !p.getFileName().toString().endsWith(".tmp")).count();
        }
    }

    private static byte[] jpg() {
        byte[] b = Arrays.copyOf(bytes(new int[] {0xFF, 0xD8, 0xFF, 0xE0}), 64);
        b[63] = (byte) 0xD9;
        return b;
    }

    private static byte[] bytes(int[] values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }
}
