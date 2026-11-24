package com.hr.d.service;

import com.hr.d.dao.RecruitmentDao;
import com.hr.d.dto.Actor;
import com.hr.d.dto.Page;
import com.hr.d.integration.EmployeeCreator;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import com.hr.util.DBUtil;

public final class RecruitmentService {
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "APPLIED", Set.of("INTERVIEW", "REJECTED"), "INTERVIEW", Set.of("OFFER", "REJECTED"),
            "OFFER", Set.of("REJECTED"));
    private final RecruitmentDao dao = new RecruitmentDao();

    public Page lookup(String type, Page.Request page) {
        return DBUtil.read(conn -> switch (type) {
            case "departments" -> dao.departments(conn, page);
            case "positions" -> dao.positions(conn, page);
            case "employees" -> dao.employees(conn, page);
            default -> throw Problem.missing();
        });
    }

    public Page postings(Page.Request page) {
        return DBUtil.read(conn -> dao.postings(conn, page));
    }

    public Map<String, Object> posting(long id) {
        return DBUtil.read(conn -> dao.posting(conn, id));
    }

    public long createPosting(String title, long dept, long position, String description, LocalDate open, LocalDate close) {
        if (close.isBefore(open)) throw Problem.bad("마감일은 시작일 이후여야 합니다.");
        return DBUtil.tx(conn -> dao.createPosting(conn, title, dept, position, description, open, close));
    }

    public Map<String, Object> closePosting(long id) {
        return DBUtil.tx(conn -> {
            if (dao.closePosting(conn, id) == 0) {
                dao.posting(conn, id);
                throw Problem.conflict("이미 마감된 공고입니다.");
            }
            return dao.posting(conn, id);
        });
    }

    public Page applicants(Long posting, Page.Request page) {
        return DBUtil.read(conn -> dao.applicants(conn, posting, page));
    }

    public Map<String, Object> applicant(long id) {
        return DBUtil.read(conn -> dao.applicant(conn, id));
    }

    public long addApplicant(long posting, String name, String email, String phone) {
        return DBUtil.tx(conn -> {
            if (number(dao.lockPosting(conn, posting), "accepting") != 1) {
                throw Problem.conflict("현재 지원자를 등록할 수 있는 공고가 아닙니다.");
            }
            return dao.addApplicant(conn, posting, name, email, phone);
        });
    }

    public Map<String, Object> changeStage(long id, String stage) {
        if (!Set.of("INTERVIEW", "OFFER", "REJECTED").contains(stage)) throw Problem.bad("지원자 단계 값을 확인해 주세요.");
        return DBUtil.tx(conn -> {
            var applicant = dao.lockApplicant(conn, id);
            if (!TRANSITIONS.getOrDefault((String) applicant.get("stage"), Set.of()).contains(stage)) {
                throw Problem.conflict("현재 단계에서 요청한 단계로 변경할 수 없습니다.");
            }
            dao.changeStage(conn, id, stage, null);
            return dao.applicant(conn, id);
        });
    }

    public long hire(long id, String empNo, String loginId, String password, Long manager, LocalDate date,
                     BigDecimal days, long actorId, EmployeeCreator creator) {
        if (creator == null) throw Problem.unavailable("직원 등록 서비스 연동이 필요합니다.");
        return DBUtil.tx(conn -> {
            var applicant = dao.lockApplicant(conn, id);
            if (!"OFFER".equals(applicant.get("stage"))) throw Problem.conflict("합격 상태의 지원자만 직원으로 전환할 수 있습니다.");
            if (manager != null) dao.requireActiveEmployee(conn, manager);
            var employee = new EmployeeCreator.Employee(empNo, loginId, password, (String) applicant.get("name"),
                    (String) applicant.get("email"), (String) applicant.get("phone"),
                    number(applicant, "deptId"), number(applicant, "posId"), manager, date);
            long employeeId = creator.create(conn, employee, days, actorId);
            if (employeeId <= 0) throw new SQLException("Employee creator returned an invalid ID");
            dao.changeStage(conn, id, "HIRED", employeeId);
            return employeeId;
        });
    }

    public Page interviews(long id, boolean forApplicant, Page.Request page) {
        return DBUtil.read(conn -> {
            if (forApplicant) dao.applicant(conn, id);
            return dao.interviews(conn, page, id, forApplicant);
        });
    }

    public long addInterview(long applicant, long interviewer, LocalDateTime date) {
        return DBUtil.tx(conn -> {
            requireInterviewStage(dao.lockApplicant(conn, applicant));
            dao.requireActiveEmployee(conn, interviewer);
            return dao.addInterview(conn, applicant, interviewer, date);
        });
    }

    public Map<String, Object> reschedule(long id, long interviewer, LocalDateTime date) {
        return DBUtil.tx(conn -> {
            var interview = dao.interview(conn, id);
            requireInterviewStage(dao.lockApplicant(conn, number(interview, "applicantId")));
            if (dao.interview(conn, id).get("score") != null) throw Problem.conflict("평가한 면접은 일정을 변경할 수 없습니다.");
            dao.requireActiveEmployee(conn, interviewer);
            dao.updateInterview(conn, id, interviewer, date);
            return dao.interview(conn, id);
        });
    }

    public Map<String, Object> score(Actor actor, boolean admin, long id, int score, String feedback) {
        if (score < 0 || score > 100) throw Problem.bad("면접 점수는 0~100점이어야 합니다.");
        return DBUtil.tx(conn -> {
            var interview = dao.interview(conn, id);
            dao.lockApplicant(conn, number(interview, "applicantId"));
            if (dao.scoreInterview(conn, id, admin ? null : actor.id(), score, feedback) == 0) {
                throw Problem.forbidden("배정된 면접만 평가할 수 있습니다.");
            }
            return dao.interview(conn, id);
        });
    }

    private static void requireInterviewStage(Map<String, Object> applicant) {
        if (!"INTERVIEW".equals(applicant.get("stage"))) throw Problem.conflict("면접 단계의 지원자만 일정을 등록·변경할 수 있습니다.");
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
}
