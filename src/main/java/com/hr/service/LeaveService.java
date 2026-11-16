package com.hr.service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hr.dao.AttendanceDAO;
import com.hr.dao.LeaveBalanceDAO;
import com.hr.dao.LeaveRequestDAO;
import com.hr.dto.LeaveBalance;
import com.hr.dto.LeaveRequest;
import com.hr.util.ApiException;
import com.hr.util.DBUtil;

/**
 * 휴가·연차 (REQ-11 신청·취소, REQ-12 잔여·부여·조정).
 *
 * 다른 도메인이 부르는 메서드 (호출하는 쪽 트랜잭션 conn에 합류):
 *  - onApproved / onRejected : C의 결재 엔진이 최종 승인·반려 때 호출
 *  - grantAnnual             : A의 직원 등록, D의 채용 전환이 연차를 만들 때 호출
 */
public class LeaveService {

    public static final String DOC_TYPE = "LEAVE";

    private static final int REASON_MAX = 500;
    private static final BigDecimal MAX_DAYS_PER_YEAR = new BigDecimal("365");
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final int ORA_PARENT_KEY_NOT_FOUND = 2291;
    private static final DateTimeFormatter MM_DD = DateTimeFormatter.ofPattern("MM/dd");

    private final LeaveRequestDAO requestDAO = new LeaveRequestDAO();
    private final LeaveBalanceDAO balanceDAO = new LeaveBalanceDAO();
    private final AttendanceDAO attendanceDAO = new AttendanceDAO();
    private final ApprovalService approvalService = new ApprovalService();

    // ================================================================== 직원 (U04)

    /** 내 신청 내역 + 잔여 연차. {items, total, page, size, year, balance} */
    public Map<String, Object> myRequests(long empId, int year, int page, int size) {
        return DBUtil.read(conn -> {
            int total = requestDAO.countByEmp(conn, empId, year);
            List<LeaveRequest> items = requestDAO.findByEmp(conn, empId, year, (page - 1) * size, size);
            Map<String, Object> m = AttendanceService.pageResult(items, total, page, size);
            m.put("year", year);
            m.put("balance", balance(conn, empId, year));
            return m;
        });
    }

    /** 신청 전 미리보기: 일수, 차감 후 잔여, 결재선의 상사 이름 */
    public Map<String, Object> preview(long empId, String type, LocalDate start, LocalDate end) {
        BigDecimal days = validatePeriod(type, start, end);
        return DBUtil.read(conn -> {
            LeaveBalance b = balance(conn, empId, start.getYear());
            boolean deductible = LeaveRules.isDeductible(type);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("leaveType", type);
            m.put("days", days);
            m.put("deductible", deductible);
            m.put("granted", b.isGranted());
            m.put("availableDays", b.getAvailableDays());
            m.put("remainingAfter", deductible ? b.getAvailableDays().subtract(days) : b.getAvailableDays());
            m.put("enough", !deductible || (b.isGranted() && days.compareTo(b.getAvailableDays()) <= 0));
            m.put("managerName", requestDAO.findManagerName(conn, empId));
            return m;
        });
    }

    public LeaveRequest apply(long empId, String type, LocalDate start, LocalDate end, String reason) {
        BigDecimal days = validatePeriod(type, start, end);
        if (reason != null && reason.length() > REASON_MAX) {
            throw ApiException.badRequest("사유는 " + REASON_MAX + "자까지 입력할 수 있습니다");
        }

        return DBUtil.tx(conn -> {
            // 같은 직원의 신청이 동시에 들어와 겹침·잔여 검사를 함께 통과하지 않도록 직원 행을 잠근다
            if (!requestDAO.lockEmployee(conn, empId)) {
                throw ApiException.notFound("직원을 찾을 수 없습니다");
            }
            for (LeaveRequest other : requestDAO.findOverlapping(conn, empId, start, end)) {
                if (!LeaveRules.canCoexist(type, other.getLeaveType())) {
                    throw ApiException.conflict("이미 신청한 휴가와 기간이 겹칩니다 ("
                            + other.getStartDate() + " ~ " + other.getEndDate() + ")");
                }
            }
            if (LeaveRules.isDeductible(type)) {
                LeaveBalance b = balance(conn, empId, start.getYear());
                if (!b.isGranted()) {
                    throw ApiException.conflict(start.getYear() + "년 연차가 아직 부여되지 않았습니다");
                }
                if (days.compareTo(b.getAvailableDays()) > 0) {
                    throw ApiException.conflict("잔여 연차가 부족합니다 (신청 가능 " + plain(b.getAvailableDays()) + "일)");
                }
            }

            LeaveRequest r = new LeaveRequest();
            r.setEmpId(empId);
            r.setLeaveType(type);
            r.setStartDate(start);
            r.setEndDate(end);
            r.setDays(days);
            r.setReason(reason);
            r.setStatus(LeaveRequest.PENDING);
            long reqId = requestDAO.insert(conn, r);

            approvalService.submit(conn, DOC_TYPE, reqId, empId, title(r));
            return requestDAO.findById(conn, reqId, false);
        });
    }

    /** 결재 진행 중인 본인 신청만 취소할 수 있다. */
    public LeaveRequest cancel(long empId, long reqId) {
        return DBUtil.tx(conn -> {
            LeaveRequest r = requestDAO.findById(conn, reqId, true);
            if (r == null || r.getEmpId() != empId) {
                throw ApiException.notFound("휴가 신청을 찾을 수 없습니다");
            }
            if (!LeaveRequest.PENDING.equals(r.getStatus())) {
                throw ApiException.conflict("결재가 진행 중인 신청만 취소할 수 있습니다");
            }
            requestDAO.updateStatus(conn, reqId, LeaveRequest.PENDING, LeaveRequest.CANCELED);
            approvalService.cancel(conn, DOC_TYPE, reqId, empId);
            r.setStatus(LeaveRequest.CANCELED);
            return r;
        });
    }

    // ================================================================== 결재 후처리 (C가 호출)

    /**
     * 최종 승인. 연차·반차는 사용 일수를 늘리고, 하루 단위 휴가는 그 평일들의 근태를 LEAVE로 표시한다.
     * 결재 엔진의 트랜잭션 안에서 실행되며, 실패하면 ApiException을 던져 결재 승인까지 함께 롤백된다.
     */
    public void onApproved(Connection conn, long reqId) throws SQLException {
        LeaveRequest r = pendingForUpdate(conn, reqId);
        if (LeaveRules.isDeductible(r.getLeaveType())) {
            int year = r.getStartDate().getYear();
            if (balanceDAO.find(conn, r.getEmpId(), year, true) == null) {
                throw ApiException.conflict(year + "년 연차가 부여되지 않아 승인할 수 없습니다");
            }
            if (balanceDAO.addUsed(conn, r.getEmpId(), year, r.getDays()) == 0) {
                throw ApiException.conflict("잔여 연차가 부족해 승인할 수 없습니다");
            }
        }
        requestDAO.updateStatus(conn, reqId, LeaveRequest.PENDING, LeaveRequest.APPROVED);
        if (LeaveRules.marksAttendance(r.getLeaveType())) {
            for (LocalDate d : LeaveRules.weekdays(r.getStartDate(), r.getEndDate())) {
                attendanceDAO.markLeave(conn, r.getEmpId(), d);
            }
        }
    }

    /** 반려. 연차는 승인 때만 차감하므로 상태만 바꾼다. */
    public void onRejected(Connection conn, long reqId) throws SQLException {
        pendingForUpdate(conn, reqId);
        requestDAO.updateStatus(conn, reqId, LeaveRequest.PENDING, LeaveRequest.REJECTED);
    }

    private LeaveRequest pendingForUpdate(Connection conn, long reqId) throws SQLException {
        LeaveRequest r = requestDAO.findById(conn, reqId, true);
        if (r == null) {
            throw ApiException.notFound("휴가 신청을 찾을 수 없습니다");
        }
        if (!LeaveRequest.PENDING.equals(r.getStatus())) {
            throw ApiException.conflict("이미 처리된 휴가 신청입니다");
        }
        return r;
    }

    // ================================================================== 연차 생성 (A, D가 호출)

    /** 직원 등록·채용 전환 때 그해 연차를 만든다. 호출하는 쪽 트랜잭션에 합류한다. */
    public void grantAnnual(Connection conn, long empId, int year, BigDecimal days) throws SQLException {
        validateDays(days);
        balanceDAO.insert(conn, empId, year, days);
    }

    // ================================================================== 관리자

    /** 재직자 연차 현황. {items, total, page, size, year} */
    public Map<String, Object> listBalances(int year, Long deptId, String keyword, int page, int size) {
        validateYear(year);
        return DBUtil.read(conn -> {
            int total = balanceDAO.countForAdmin(conn, deptId, keyword);
            List<LeaveBalance> items = balanceDAO.findForAdmin(conn, year, deptId, keyword, (page - 1) * size, size);
            Map<String, Object> m = AttendanceService.pageResult(items, total, page, size);
            m.put("year", year);
            return m;
        });
    }

    /** 부여 일수를 정한다. 그해 연차 행이 없으면 만든다. 이미 쓴 일수보다 적게는 줄 수 없다. */
    public LeaveBalance adjustBalance(long empId, int year, BigDecimal totalDays) {
        validateYear(year);
        validateDays(totalDays);
        return DBUtil.tx(conn -> {
            LeaveBalance current = balanceDAO.find(conn, empId, year, true);
            if (current == null) {
                try {
                    balanceDAO.insert(conn, empId, year, totalDays);
                } catch (SQLException e) {
                    if (e.getErrorCode() == ORA_PARENT_KEY_NOT_FOUND) {
                        throw ApiException.notFound("직원을 찾을 수 없습니다");
                    }
                    throw e;
                }
            } else {
                if (totalDays.compareTo(current.getUsedDays()) < 0) {
                    throw ApiException.conflict("이미 사용한 " + plain(current.getUsedDays()) + "일보다 적게 줄 수 없습니다");
                }
                balanceDAO.updateTotal(conn, empId, year, totalDays);
            }
            // TODO(A): 감사 로그(REQ-21) — AuditService가 정해지면 변경 전·후 값을 남긴다
            return balance(conn, empId, year);
        });
    }

    /** 그해 연차가 없는 재직자 전원에게 일괄 부여. 부여한 인원 수를 돌려준다. */
    public int grantAll(int year, BigDecimal days) {
        validateYear(year);
        validateDays(days);
        return DBUtil.tx(conn -> balanceDAO.grantMissing(conn, year, days));
    }

    // ================================================================== 도우미

    LeaveBalance balance(Connection conn, long empId, int year) throws SQLException {
        LeaveBalance b = balanceDAO.find(conn, empId, year, false);
        if (b == null) {
            b = new LeaveBalance();
            b.setEmpId(empId);
            b.setYear(year);
            b.setGranted(false);
        }
        b.setPendingDays(requestDAO.sumPendingDeductible(conn, empId, year));
        return b;
    }

    /** 종류·기간을 검사하고 신청 일수를 돌려준다. */
    private static BigDecimal validatePeriod(String type, LocalDate start, LocalDate end) {
        if (type == null || !LeaveRules.TYPES.contains(type)) {
            throw ApiException.badRequest("휴가 종류가 올바르지 않습니다");
        }
        if (start == null || end == null) {
            throw ApiException.badRequest("시작일과 종료일을 입력하세요");
        }
        if (end.isBefore(start)) {
            throw ApiException.badRequest("종료일이 시작일보다 빠릅니다");
        }
        if (LeaveRules.isHalfDay(type) && !start.equals(end)) {
            throw ApiException.badRequest("반차는 하루만 신청할 수 있습니다");
        }
        if (start.getYear() != end.getYear()) {
            throw ApiException.badRequest("해를 넘기는 휴가는 연도별로 나눠 신청하세요");
        }
        BigDecimal days = LeaveRules.countDays(type, start, end);
        if (days.signum() == 0) {
            throw ApiException.badRequest("선택한 기간에 평일이 없습니다");
        }
        return days;
    }

    private static void validateDays(BigDecimal days) {
        if (days == null || days.signum() < 0 || days.compareTo(MAX_DAYS_PER_YEAR) > 0) {
            throw ApiException.badRequest("연차 일수는 0~365일이어야 합니다");
        }
        if (days.remainder(HALF).signum() != 0) {
            throw ApiException.badRequest("연차 일수는 0.5일 단위로 입력하세요");
        }
    }

    private static void validateYear(int year) {
        if (year < 2000 || year > 2100) {
            throw ApiException.badRequest("연도가 올바르지 않습니다");
        }
    }

    private static String title(LeaveRequest r) {
        String period = r.getStartDate().equals(r.getEndDate())
                ? r.getStartDate().format(MM_DD)
                : r.getStartDate().format(MM_DD) + "~" + r.getEndDate().format(MM_DD);
        return LeaveRules.label(r.getLeaveType()) + " " + period + " (" + plain(r.getDays()) + "일)";
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
