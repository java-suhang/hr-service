package com.hr.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hr.dao.AttendanceDAO;
import com.hr.dao.AttendanceDAO.DailyFilter;
import com.hr.dao.WorkPolicyDAO;
import com.hr.dto.Attendance;
import com.hr.dto.AttendanceDailyRow;
import com.hr.dto.WorkPolicy;
import com.hr.util.ApiException;
import com.hr.util.DBUtil;

/**
 * 근태 (REQ-08 출퇴근, REQ-09 조회, REQ-10 관리자 수정, REQ-16 근무 규칙).
 * 시각은 서버 시계(Asia/Seoul) 기준이다.
 */
public class AttendanceService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** 이 시각 전에는 어제 퇴근하지 않은 기록을 오늘 퇴근으로 닫을 수 있다 (자정 넘는 근무) */
    private static final LocalTime OVERNIGHT_CUTOFF = LocalTime.of(6, 0);

    private static final int ORA_UNIQUE_VIOLATION = 1;
    private static final int ORA_PARENT_KEY_NOT_FOUND = 2291;

    /** 관리자가 직접 고를 수 있는 상태 */
    public static final Set<String> STATUSES = Set.of(Attendance.NORMAL, Attendance.LATE, Attendance.EARLY_LEAVE,
            Attendance.ABSENT, Attendance.LEAVE);

    /** 엑셀 내보내기 최대 행 수 */
    private static final int EXPORT_LIMIT = 10_000;

    private final AttendanceDAO attendanceDAO = new AttendanceDAO();
    private final WorkPolicyDAO policyDAO = new WorkPolicyDAO();
    private final AuditService audit = new AuditService();
    private final Clock clock;

    public AttendanceService() {
        this(Clock.system(ZONE));
    }

    public AttendanceService(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    // ================================================================== 근무 규칙

    public WorkPolicy getPolicy() {
        return DBUtil.read(this::policy);
    }

    WorkPolicy policy(Connection conn) throws SQLException {
        WorkPolicy p = policyDAO.find(conn);
        return p != null ? p : WorkPolicy.defaults();
    }

    public WorkPolicy updatePolicy(LocalTime start, LocalTime end, int lateGraceMin, int weeklyLimitHours,
            long actorId) {
        if (start == null || end == null) {
            throw ApiException.badRequest("출근·퇴근 시각을 입력하세요");
        }
        if (!start.isBefore(end)) {
            throw ApiException.badRequest("퇴근 시각은 출근 시각보다 늦어야 합니다");
        }
        if (lateGraceMin < 0 || lateGraceMin > 120) {
            throw ApiException.badRequest("지각 유예는 0~120분이어야 합니다");
        }
        if (weeklyLimitHours < 1 || weeklyLimitHours > 168) {
            throw ApiException.badRequest("주 근무 한도는 1~168시간이어야 합니다");
        }
        WorkPolicy input = new WorkPolicy();
        input.setStartTime(start.format(HH_MM));
        input.setEndTime(end.format(HH_MM));
        input.setLateGraceMin(lateGraceMin);
        input.setWeeklyLimitHours(weeklyLimitHours);

        return DBUtil.tx(conn -> {
            WorkPolicy current = policyDAO.find(conn);
            if (current == null) {
                input.setPolicyId(policyDAO.insert(conn, input));
                audit.log(conn, actorId, AuditService.CREATE, "WORK_POLICY", input.getPolicyId(), null, input);
            } else {
                input.setPolicyId(current.getPolicyId());
                policyDAO.update(conn, input);
                audit.log(conn, actorId, AuditService.UPDATE, "WORK_POLICY", input.getPolicyId(), current, input);
            }
            return input;
        });
    }

    // ================================================================== 출퇴근 (직원)

    /** 오늘 근태 + 이번 주 누적 */
    public Map<String, Object> todayStatus(long empId) {
        LocalDateTime now = now();
        return DBUtil.read(conn -> todayStatus(conn, empId, policy(conn), now));
    }

    public Map<String, Object> checkIn(long empId) {
        LocalDateTime now = now();
        LocalDate today = now.toLocalDate();
        return DBUtil.tx(conn -> {
            WorkPolicy p = policy(conn);
            String status = AttendanceRules.isLate(now, p) ? Attendance.LATE : Attendance.NORMAL;
            Attendance current = attendanceDAO.findByEmpAndDate(conn, empId, today, true);

            if (current == null) {
                Attendance a = new Attendance();
                a.setEmpId(empId);
                a.setWorkDate(today);
                a.setCheckIn(now);
                a.setStatus(status);
                try {
                    attendanceDAO.insert(conn, a);
                } catch (SQLException e) {
                    if (e.getErrorCode() == ORA_UNIQUE_VIOLATION) {
                        throw ApiException.conflict("오늘은 이미 출근했습니다");
                    }
                    throw e;
                }
            } else if (Attendance.LEAVE.equals(current.getStatus())) {
                throw ApiException.conflict("오늘은 휴가일이라 출근을 기록할 수 없습니다");
            } else if (current.getCheckIn() != null) {
                throw ApiException.conflict("오늘은 이미 출근했습니다");
            } else {
                // 관리자가 결근 등으로 먼저 만들어 둔 행
                attendanceDAO.updateTimes(conn, current.getAttId(), now, null, status);
            }
            return todayStatus(conn, empId, p, now);
        });
    }

    public Map<String, Object> checkOut(long empId) {
        LocalDateTime now = now();
        return DBUtil.tx(conn -> {
            WorkPolicy p = policy(conn);
            Attendance open = openRecord(conn, empId, now);

            // 지각은 출근 때 정해졌으므로 유지하고, 그 밖에는 조퇴 여부만 본다
            String status = open.getStatus();
            if (!Attendance.LATE.equals(status) && AttendanceRules.isEarlyLeave(open.getWorkDate(), now, p)) {
                status = Attendance.EARLY_LEAVE;
            }
            if (attendanceDAO.updateCheckOut(conn, open.getAttId(), now, status) == 0) {
                throw ApiException.conflict("이미 퇴근했습니다");
            }
            return todayStatus(conn, empId, p, now);
        });
    }

    /** 퇴근할 기록: 오늘 출근 기록, 없으면 새벽(06시 전)에 한해 어제 퇴근하지 않은 기록 */
    private Attendance openRecord(Connection conn, long empId, LocalDateTime now) throws SQLException {
        LocalDate today = now.toLocalDate();
        Attendance t = attendanceDAO.findByEmpAndDate(conn, empId, today, true);
        if (t != null && t.getCheckIn() != null) {
            if (t.getCheckOut() != null) {
                throw ApiException.conflict("오늘은 이미 퇴근했습니다");
            }
            return t;
        }
        if (now.toLocalTime().isBefore(OVERNIGHT_CUTOFF)) {
            Attendance y = attendanceDAO.findByEmpAndDate(conn, empId, today.minusDays(1), true);
            if (y != null && y.getCheckIn() != null && y.getCheckOut() == null) {
                return y;
            }
        }
        throw ApiException.conflict("출근 기록이 없습니다");
    }

    private Map<String, Object> todayStatus(Connection conn, long empId, WorkPolicy p, LocalDateTime now)
            throws SQLException {
        LocalDate today = now.toLocalDate();
        Attendance record = attendanceDAO.findByEmpAndDate(conn, empId, today, false);
        if (record != null) {
            enrich(record, p);
        }
        LocalDate monday = AttendanceRules.weekStart(today);
        int weekly = 0;
        for (Attendance a : attendanceDAO.findByEmpBetween(conn, empId, monday, monday.plusDays(6))) {
            weekly += workedSoFar(a, now);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", today);
        m.put("now", now);
        m.put("attendance", record);
        m.put("policy", p);
        m.put("weeklyMinutes", weekly);
        m.put("weeklyLevel", AttendanceRules.weeklyLevel(weekly, p));
        return m;
    }

    // ================================================================== 월간 조회 (직원, U03)

    public Map<String, Object> month(long empId, YearMonth month) {
        LocalDateTime now = now();
        return DBUtil.read(conn -> {
            WorkPolicy p = policy(conn);
            List<Attendance> days = attendanceDAO.findByEmpBetween(conn, empId, month.atDay(1), month.atEndOfMonth());
            int workDays = 0;
            int workMinutes = 0;
            int overtimeMinutes = 0;
            int lateCount = 0;
            int earlyLeaveCount = 0;
            int leaveDays = 0;
            for (Attendance a : days) {
                enrich(a, p);
                if (a.getCheckIn() != null) {
                    workDays++;
                }
                workMinutes += nz(a.getWorkMinutes());
                overtimeMinutes += nz(a.getOvertimeMinutes());
                switch (a.getStatus()) {
                    case Attendance.LATE -> lateCount++;
                    case Attendance.EARLY_LEAVE -> earlyLeaveCount++;
                    case Attendance.LEAVE -> leaveDays++;
                    default -> {
                        // NORMAL, ABSENT
                    }
                }
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("workDays", workDays);
            summary.put("workMinutes", workMinutes);
            summary.put("overtimeMinutes", overtimeMinutes);
            summary.put("lateCount", lateCount);
            summary.put("earlyLeaveCount", earlyLeaveCount);
            summary.put("leaveDays", leaveDays);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", month.toString());
            m.put("today", now.toLocalDate());
            m.put("policy", p);
            m.put("summary", summary);
            m.put("days", days);
            return m;
        });
    }

    // ================================================================== 관리자 (A04)

    /** 일자별 전체 근태 + 주간 누적. {items, total, page, size, date, today, policy} */
    public Map<String, Object> daily(DailyFilter filter, int page, int size) {
        LocalDateTime now = now();
        return DBUtil.read(conn -> {
            WorkPolicy p = policy(conn);
            int total = attendanceDAO.countDaily(conn, filter);
            List<AttendanceDailyRow> items = loadDaily(conn, filter, (page - 1) * size, size, p, now);
            Map<String, Object> m = pageResult(items, total, page, size);
            m.put("date", filter.date());
            m.put("today", now.toLocalDate());
            m.put("policy", p);
            return m;
        });
    }

    /** 엑셀(CSV) 내보내기용 전체 행 */
    public List<AttendanceDailyRow> dailyForExport(DailyFilter filter) {
        LocalDateTime now = now();
        return DBUtil.read(conn -> loadDaily(conn, filter, 0, EXPORT_LIMIT, policy(conn), now));
    }

    private List<AttendanceDailyRow> loadDaily(Connection conn, DailyFilter filter, int offset, int limit,
            WorkPolicy p, LocalDateTime now) throws SQLException {
        List<AttendanceDailyRow> rows = attendanceDAO.findDaily(conn, filter, offset, limit);
        List<Long> empIds = rows.stream().map(AttendanceDailyRow::getEmpId).toList();
        LocalDate monday = AttendanceRules.weekStart(filter.date());
        Map<Long, Integer> weekly = new HashMap<>();
        for (Attendance a : attendanceDAO.findByEmpsBetween(conn, empIds, monday, monday.plusDays(6))) {
            weekly.merge(a.getEmpId(), workedSoFar(a, now), Integer::sum);
        }
        for (AttendanceDailyRow r : rows) {
            r.setWorkMinutes(r.getCheckOut() == null ? null : AttendanceRules.workedMinutes(r.getCheckIn(), r.getCheckOut()));
            int w = weekly.getOrDefault(r.getEmpId(), 0);
            r.setWeeklyMinutes(w);
            r.setWeeklyLevel(AttendanceRules.weeklyLevel(w, p));
        }
        return rows;
    }

    /**
     * 관리자 근태 수정 (REQ-10). 기록이 없으면 만든다.
     * status를 비우면 출퇴근 시각으로 자동 판정한다. 퇴근이 출근보다 이르면 다음 날 퇴근으로 본다.
     */
    public Attendance adminSave(long empId, LocalDate date, LocalTime in, LocalTime out, String status,
            long actorId) {
        if (date.isAfter(today())) {
            throw ApiException.badRequest("미래 날짜의 근태는 수정할 수 없습니다");
        }
        if (out != null && in == null) {
            throw ApiException.badRequest("출근 시각 없이 퇴근 시각만 넣을 수 없습니다");
        }
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.badRequest("근태 상태가 올바르지 않습니다");
        }
        LocalDateTime checkIn = in == null ? null : date.atTime(in);
        LocalDateTime checkOut = null;
        if (out != null) {
            if (out.equals(in)) {
                throw ApiException.badRequest("출근과 퇴근 시각이 같습니다");
            }
            checkOut = out.isBefore(in) ? date.plusDays(1).atTime(out) : date.atTime(out);
        }
        LocalDateTime finalOut = checkOut;

        return DBUtil.tx(conn -> {
            WorkPolicy p = policy(conn);
            String finalStatus = status != null ? status : AttendanceRules.judge(date, checkIn, finalOut, p);
            Attendance current = attendanceDAO.findByEmpAndDate(conn, empId, date, true);
            if (current == null) {
                Attendance a = new Attendance();
                a.setEmpId(empId);
                a.setWorkDate(date);
                a.setCheckIn(checkIn);
                a.setCheckOut(finalOut);
                a.setStatus(finalStatus);
                try {
                    attendanceDAO.insert(conn, a);
                } catch (SQLException e) {
                    if (e.getErrorCode() == ORA_PARENT_KEY_NOT_FOUND) {
                        throw ApiException.notFound("직원을 찾을 수 없습니다");
                    }
                    throw e;
                }
            } else {
                attendanceDAO.updateTimes(conn, current.getAttId(), checkIn, finalOut, finalStatus);
            }

            Attendance saved = attendanceDAO.findByEmpAndDate(conn, empId, date, false);
            // 근태 수정과 같은 트랜잭션에서 남긴다 (새로 만들면 CREATE, 고치면 UPDATE)
            audit.log(conn, actorId, current == null ? AuditService.CREATE : AuditService.UPDATE, "ATTENDANCE",
                    saved.getAttId(), current, saved);
            enrich(saved, p);
            return saved;
        });
    }

    // ================================================================== 계산 도우미

    /** 근무시간·초과근무를 채운다. 퇴근 전이면 비워 둔다. */
    private static void enrich(Attendance a, WorkPolicy p) {
        if (a.getCheckOut() == null) {
            a.setWorkMinutes(null);
            a.setOvertimeMinutes(null);
        } else {
            a.setWorkMinutes(AttendanceRules.workedMinutes(a.getCheckIn(), a.getCheckOut()));
            a.setOvertimeMinutes(AttendanceRules.overtimeMinutes(a.getWorkDate(), a.getCheckOut(), p));
        }
    }

    /** 주간 누적용 근무시간. 오늘 근무 중인 기록은 지금까지 일한 시간으로 센다. */
    private static int workedSoFar(Attendance a, LocalDateTime now) {
        LocalDateTime out = a.getCheckOut();
        if (out == null && now.toLocalDate().equals(a.getWorkDate())) {
            out = now;
        }
        return AttendanceRules.workedMinutes(a.getCheckIn(), out);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    static Map<String, Object> pageResult(List<?> items, int total, int page, int size) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("total", total);
        m.put("page", page);
        m.put("size", size);
        return m;
    }
}
