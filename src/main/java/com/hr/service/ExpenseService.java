package com.hr.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.hr.dao.AttachmentDAO;
import com.hr.dao.ExpenseDAO;
import com.hr.dao.ExpenseDAO.Expense;
import com.hr.util.ApiException;
import com.hr.util.DBUtil;

/**
 * 경비 정산 (REQ-23, 24). 영수증을 붙여 요청하면 결재(상사 → 인사 관리자)를 거쳐
 * 최종 승인 때 지급 대기(APPROVED), 관리자가 지급하면 지급 완료(PAID)가 된다.
 *
 * 다른 도메인이 부르는 메서드 (호출하는 쪽 트랜잭션 conn에 합류):
 *  - onApproved / onRejected : 결재 엔진이 최종 승인·반려 때 호출
 */
public class ExpenseService {

    public static final String DOC_TYPE = "EXPENSE";
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String PAID = "PAID";
    public static final String CANCELED = "CANCELED";

    /** 분류 코드 → 화면 이름 (순서 유지) */
    public static final Map<String, String> CATEGORIES;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("MEAL", "식대");
        m.put("TRANSPORT", "교통");
        m.put("TRAVEL", "출장·숙박");
        m.put("SUPPLIES", "비품");
        m.put("BOOK", "도서·교육");
        m.put("ETC", "기타");
        CATEGORIES = Collections.unmodifiableMap(m);
    }

    public static final long AMOUNT_MAX = 10_000_000L;
    private static final int DESCRIPTION_MAX = 200;
    private static final Set<String> STATUSES = Set.of(PENDING, APPROVED, REJECTED, PAID, CANCELED);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final ExpenseDAO dao = new ExpenseDAO();
    private final AttachmentDAO attachmentDAO = new AttachmentDAO();
    private final ApprovalService approvalService = new ApprovalService();
    private final NotificationService notifier = new NotificationService();
    private final AuditService audit = new AuditService();

    public static String label(String category) {
        return CATEGORIES.getOrDefault(category, category);
    }

    // ================================================================== 직원

    /** 내 경비 내역. {items, total, page, size} */
    public Map<String, Object> myList(long empId, int page, int size) {
        return DBUtil.read(conn -> AttendanceService.pageResult(
                dao.findMine(conn, empId, (page - 1) * size, size), dao.countMine(conn, empId), page, size));
    }

    /** 정산 요청: 경비 + 영수증 + 결재 상신을 한 트랜잭션으로 */
    public Expense create(long empId, String category, LocalDate useDate, Long amount, String description,
            AttachmentService.Upload receipt) {
        if (category == null || !CATEGORIES.containsKey(category)) {
            throw ApiException.badRequest("분류를 선택하세요");
        }
        if (useDate == null) {
            throw ApiException.badRequest("사용일을 입력하세요");
        }
        LocalDate today = LocalDate.now(ZONE);
        if (useDate.isAfter(today)) {
            throw ApiException.badRequest("사용일은 오늘 이후일 수 없습니다");
        }
        if (useDate.isBefore(today.minusYears(1))) {
            throw ApiException.badRequest("1년이 지난 경비는 정산할 수 없습니다");
        }
        if (amount == null || amount <= 0) {
            throw ApiException.badRequest("금액은 0원보다 커야 합니다");
        }
        if (amount > AMOUNT_MAX) {
            throw ApiException.badRequest("한 건에 " + won(AMOUNT_MAX) + "원까지 요청할 수 있습니다");
        }
        String text = description == null ? "" : description.strip();
        if (text.isEmpty()) {
            throw ApiException.badRequest("사용 내역을 입력하세요");
        }
        if (text.length() > DESCRIPTION_MAX) {
            throw ApiException.badRequest("사용 내역은 " + DESCRIPTION_MAX + "자까지 입력할 수 있습니다");
        }
        if (receipt == null) {
            throw ApiException.badRequest("영수증 파일을 첨부하세요");
        }
        String title = label(category) + " " + won(amount) + "원 · " + text;
        return AttachmentService.withStoredFile(receipt, stored -> DBUtil.tx(conn -> {
            long expId = dao.insert(conn, empId, category, amount, useDate, text);
            attachmentDAO.insert(conn, AttachmentService.REF_EXPENSE, expId, receipt.originName(), stored,
                    receipt.data().length);
            approvalService.submit(conn, DOC_TYPE, expId, empId, title);
            return dao.find(conn, expId);
        }));
    }

    /** 결재가 진행 중인 본인 경비만 취소할 수 있다 */
    public Expense cancel(long empId, long expId) {
        return DBUtil.tx(conn -> {
            Expense e = dao.lockAndFind(conn, expId);
            if (e == null || e.empId() != empId) {
                throw ApiException.notFound("경비 내역을 찾을 수 없습니다");
            }
            if (dao.changeStatus(conn, expId, PENDING, CANCELED) == 0) {
                throw ApiException.conflict("결재가 진행 중인 경비만 취소할 수 있습니다");
            }
            approvalService.cancel(conn, DOC_TYPE, expId, empId);
            return dao.find(conn, expId);
        });
    }

    // ================================================================== 결재 후처리 (결재 엔진이 호출)

    /** 최종 승인 → 지급 대기 */
    public void onApproved(Connection conn, long expId) throws SQLException {
        if (dao.changeStatus(conn, expId, PENDING, APPROVED) == 0) {
            throw ApiException.conflict("이미 처리된 경비입니다");
        }
    }

    public void onRejected(Connection conn, long expId) throws SQLException {
        if (dao.changeStatus(conn, expId, PENDING, REJECTED) == 0) {
            throw ApiException.conflict("이미 처리된 경비입니다");
        }
    }

    // ================================================================== 관리자

    /** {items, total, page, size, totalAmount}. status 기본값은 지급 대기(APPROVED) */
    public Map<String, Object> adminList(ExpenseDAO.Filter filter, int page, int size) {
        if (filter.status() != null && !STATUSES.contains(filter.status())) {
            throw ApiException.badRequest("상태 값이 올바르지 않습니다");
        }
        if (filter.category() != null && !CATEGORIES.containsKey(filter.category())) {
            throw ApiException.badRequest("분류 값이 올바르지 않습니다");
        }
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw ApiException.badRequest("기간을 확인하세요");
        }
        return DBUtil.read(conn -> {
            long[] summary = dao.summarize(conn, filter);
            List<Expense> items = dao.findAll(conn, filter, (page - 1) * size, size);
            Map<String, Object> m = AttendanceService.pageResult(items, (int) summary[0], page, size);
            m.put("totalAmount", summary[1]);
            return m;
        });
    }

    /** 지급 완료 처리 (지급 대기인 경비만) */
    public Expense pay(long expId, long actorId) {
        return DBUtil.tx(conn -> {
            Expense before = dao.lockAndFind(conn, expId);
            if (before == null) {
                throw ApiException.notFound("경비 내역을 찾을 수 없습니다");
            }
            if (dao.changeStatus(conn, expId, APPROVED, PAID) == 0) {
                throw ApiException.conflict(PAID.equals(before.status())
                        ? "이미 지급한 경비입니다" : "최종 승인된(지급 대기) 경비만 지급할 수 있습니다");
            }
            Expense after = dao.find(conn, expId);
            audit.log(conn, actorId, AuditService.UPDATE, "EXPENSE", expId, before, after);
            notifier.send(conn, after.empId(), NotificationService.EXPENSE_PAID,
                    label(after.category()) + " " + won(after.amount()) + "원이 지급되었습니다 · " + after.description(),
                    "/user/expenses.html");
            return after;
        });
    }

    static String won(long amount) {
        return NumberFormat.getIntegerInstance(Locale.KOREA).format(amount);
    }
}
