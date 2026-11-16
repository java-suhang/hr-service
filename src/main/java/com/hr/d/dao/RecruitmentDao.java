package com.hr.d.dao;

import com.hr.d.dto.Page;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

public final class RecruitmentDao {
    private static final String POSTING = """
            SELECT j.posting_id AS "postingId", j.title AS "title", j.status AS "status",
                   j.dept_id AS "deptId", d.name AS "department", j.pos_id AS "posId", p.name AS "position",
                   TO_CHAR(j.open_date, 'YYYY-MM-DD') AS "openDate",
                   TO_CHAR(j.close_date, 'YYYY-MM-DD') AS "closeDate",
                   CASE WHEN j.status = 'OPEN' AND TRUNC(SYSDATE) BETWEEN j.open_date AND j.close_date
                        THEN 1 ELSE 0 END AS "accepting"
            """;
    private static final String POSTING_FROM = """
            FROM JOB_POSTING j JOIN DEPARTMENT d ON d.dept_id = j.dept_id
                JOIN JOB_POSITION p ON p.pos_id = j.pos_id
            """;
    private static final String INTERVIEWS = """
            SELECT i.itv_id AS "interviewId", i.app_id AS "applicantId", a.name AS "applicant",
                   i.interviewer_id AS "interviewerId", e.name AS "interviewer", j.title AS "posting",
                   TO_CHAR(i.scheduled_at, 'YYYY-MM-DD"T"HH24:MI') AS "scheduledAt",
                   i.score AS "score", i.feedback AS "feedback"
            FROM INTERVIEW i JOIN APPLICANT a ON a.app_id = i.app_id
            JOIN JOB_POSTING j ON j.posting_id = a.posting_id JOIN EMPLOYEE e ON e.emp_id = i.interviewer_id
            """;

    public Page departments(Connection conn, Page.Request page) throws SQLException {
        return Sql.page(conn, page, "SELECT dept_id AS \"id\", name AS \"name\" FROM DEPARTMENT", "name, dept_id");
    }

    public Page positions(Connection conn, Page.Request page) throws SQLException {
        return Sql.page(conn, page, "SELECT pos_id AS \"id\", name AS \"name\" FROM JOB_POSITION", "pos_level, pos_id");
    }

    public Page employees(Connection conn, Page.Request page) throws SQLException {
        return Sql.page(conn, page, """
                SELECT emp_id AS "id", name AS "name", emp_no AS "empNo" FROM EMPLOYEE WHERE status = 'ACTIVE'
                """, "name, emp_id");
    }

    public Page postings(Connection conn, Page.Request page) throws SQLException {
        return Sql.page(conn, page, POSTING + POSTING_FROM, "j.open_date DESC, j.posting_id DESC");
    }

    public Map<String, Object> posting(Connection conn, long id) throws SQLException {
        return Sql.require(conn, POSTING.stripTrailing() + ", j.description AS \"description\" " + POSTING_FROM
                + " WHERE j.posting_id = ?", id);
    }

    public Map<String, Object> lockPosting(Connection conn, long id) throws SQLException {
        Sql.require(conn, "SELECT posting_id FROM JOB_POSTING WHERE posting_id = ? FOR UPDATE", id);
        return posting(conn, id);
    }

    public long createPosting(Connection conn, String title, long dept, long position, String description,
                              LocalDate open, LocalDate close) throws SQLException {
        long id = Sql.scalar(conn, "SELECT SEQ_JOB_POSTING.NEXTVAL FROM DUAL");
        Sql.change(conn, """
                INSERT INTO JOB_POSTING (posting_id, title, dept_id, pos_id, description, open_date, close_date)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, title, dept, position, description == null ? null : new Sql.ClobText(description), open, close);
        return id;
    }

    public int closePosting(Connection conn, long id) throws SQLException {
        return Sql.change(conn, "UPDATE JOB_POSTING SET status = 'CLOSED' WHERE posting_id = ? AND status = 'OPEN'", id);
    }

    public Page applicants(Connection conn, Long posting, Page.Request page) throws SQLException {
        String query = """
                SELECT a.app_id AS "applicantId", a.posting_id AS "postingId", a.name AS "name", j.title AS "posting",
                       CASE WHEN a.email IS NULL THEN NULL WHEN INSTR(a.email, '@') > 1
                            THEN SUBSTR(a.email, 1, 1) || '***' || SUBSTR(a.email, INSTR(a.email, '@'))
                            ELSE '***' END AS "email",
                       CASE WHEN a.phone IS NULL THEN NULL WHEN LENGTH(a.phone) > 7
                            THEN SUBSTR(a.phone, 1, 3) || '****' || SUBSTR(a.phone, -4)
                            ELSE '****' END AS "phone",
                       a.stage AS "stage", TO_CHAR(a.applied_at, 'YYYY-MM-DD"T"HH24:MI') AS "appliedAt"
                FROM APPLICANT a JOIN JOB_POSTING j ON j.posting_id = a.posting_id
                """;
        String order = "a.applied_at DESC, a.app_id DESC";
        return posting == null ? Sql.page(conn, page, query, order)
                : Sql.page(conn, page, query + " WHERE a.posting_id = ?", order, posting);
    }

    public Map<String, Object> applicant(Connection conn, long id) throws SQLException {
        return Sql.require(conn, """
                SELECT a.app_id AS "applicantId", a.posting_id AS "postingId", a.name AS "name", j.title AS "posting",
                       a.email AS "email", a.phone AS "phone", a.stage AS "stage", a.hired_emp_id AS "hiredEmpId",
                       a.resume_file_id AS "resumeFileId", j.dept_id AS "deptId", j.pos_id AS "posId",
                       TO_CHAR(a.applied_at, 'YYYY-MM-DD"T"HH24:MI') AS "appliedAt"
                FROM APPLICANT a JOIN JOB_POSTING j ON j.posting_id = a.posting_id WHERE a.app_id = ?
                """, id);
    }

    public Map<String, Object> lockApplicant(Connection conn, long id) throws SQLException {
        Sql.require(conn, "SELECT app_id FROM APPLICANT WHERE app_id = ? FOR UPDATE", id);
        return applicant(conn, id);
    }

    public long addApplicant(Connection conn, long posting, String name, String email, String phone) throws SQLException {
        long id = Sql.scalar(conn, "SELECT SEQ_APPLICANT.NEXTVAL FROM DUAL");
        Sql.change(conn, "INSERT INTO APPLICANT (app_id, posting_id, name, email, phone) VALUES (?, ?, ?, ?, ?)",
                id, posting, name, email, phone);
        return id;
    }

    public void changeStage(Connection conn, long id, String stage, Long employee) throws SQLException {
        Sql.change(conn, "UPDATE APPLICANT SET stage = ?, hired_emp_id = ? WHERE app_id = ?", stage, employee, id);
    }

    public void requireActiveEmployee(Connection conn, long id) throws SQLException {
        Sql.require(conn, "SELECT emp_id FROM EMPLOYEE WHERE emp_id = ? AND status = 'ACTIVE'", id);
    }

    public Page interviews(Connection conn, Page.Request page, long id, boolean forApplicant) throws SQLException {
        String filter = forApplicant ? " WHERE i.app_id = ?" : " WHERE i.interviewer_id = ?";
        return Sql.page(conn, page, INTERVIEWS + filter, "i.scheduled_at DESC, i.itv_id DESC", id);
    }

    public Map<String, Object> interview(Connection conn, long id) throws SQLException {
        return Sql.require(conn, INTERVIEWS + " WHERE i.itv_id = ?", id);
    }

    public long addInterview(Connection conn, long applicant, long interviewer, LocalDateTime scheduled) throws SQLException {
        long id = Sql.scalar(conn, "SELECT SEQ_INTERVIEW.NEXTVAL FROM DUAL");
        Sql.change(conn, "INSERT INTO INTERVIEW (itv_id, app_id, interviewer_id, scheduled_at) VALUES (?, ?, ?, ?)",
                id, applicant, interviewer, Timestamp.valueOf(scheduled));
        return id;
    }

    public void updateInterview(Connection conn, long id, long interviewer, LocalDateTime scheduled) throws SQLException {
        Sql.change(conn, "UPDATE INTERVIEW SET interviewer_id = ?, scheduled_at = ? WHERE itv_id = ?",
                interviewer, Timestamp.valueOf(scheduled), id);
    }

    public int scoreInterview(Connection conn, long id, Long onlyInterviewer, int score, String feedback) throws SQLException {
        String sql = "UPDATE INTERVIEW SET score = ?, feedback = ? WHERE itv_id = ?";
        return onlyInterviewer == null ? Sql.change(conn, sql, score, feedback, id)
                : Sql.change(conn, sql + " AND interviewer_id = ?", score, feedback, id, onlyInterviewer);
    }
}
