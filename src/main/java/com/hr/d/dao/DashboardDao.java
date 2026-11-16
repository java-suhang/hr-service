package com.hr.d.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public final class DashboardDao {
    private static final String TODAY = "a.work_date >= TRUNC(SYSDATE) AND a.work_date < TRUNC(SYSDATE) + 1";
    private static final String CHECKED_IN = "EXISTS (SELECT 1 FROM ATTENDANCE a WHERE a.emp_id = e.emp_id AND "
            + TODAY + " AND a.check_in IS NOT NULL)";

    public Map<String, Object> attendance(Connection conn, long id) throws SQLException {
        return Sql.one(conn, """
                SELECT TO_CHAR(a.check_in, 'HH24:MI') AS "checkIn",
                       TO_CHAR(a.check_out, 'HH24:MI') AS "checkOut", a.status AS "status"
                FROM ATTENDANCE a WHERE a.emp_id = ? AND
                """ + TODAY, id);
    }

    public Map<String, Object> leave(Connection conn, long id) throws SQLException {
        return Sql.one(conn, """
                SELECT total_days AS "totalDays", used_days AS "usedDays",
                       total_days - used_days AS "remainingDays"
                FROM LEAVE_BALANCE WHERE emp_id = ? AND year = EXTRACT(YEAR FROM SYSDATE)
                """, id);
    }

    public List<Map<String, Object>> approvals(Connection conn, long id) throws SQLException {
        return Sql.rows(conn, """
                SELECT status AS "status", COUNT(*) AS "count"
                FROM APPROVAL_DOC WHERE drafter_id = ? GROUP BY status
                """, id);
    }

    public long unread(Connection conn, long id) throws SQLException {
        return Sql.scalar(conn, "SELECT COUNT(*) FROM NOTIFICATION WHERE emp_id = ? AND is_read = 'N'", id);
    }

    public List<Map<String, Object>> departments(Connection conn) throws SQLException {
        return Sql.rows(conn, """
                SELECT d.dept_id AS "deptId", d.name AS "name", COUNT(e.emp_id) AS "activeEmployees"
                FROM DEPARTMENT d LEFT JOIN EMPLOYEE e ON e.dept_id = d.dept_id AND e.status = 'ACTIVE'
                GROUP BY d.dept_id, d.name ORDER BY d.name, d.dept_id
                """);
    }

    public List<Map<String, Object>> departmentAttendance(Connection conn) throws SQLException {
        return Sql.rows(conn, """
                SELECT e.dept_id AS "deptId", COUNT(*) AS "checkedIn" FROM EMPLOYEE e
                WHERE e.status = 'ACTIVE' AND
                """ + CHECKED_IN + " GROUP BY e.dept_id");
    }

    public long activeEmployees(Connection conn) throws SQLException {
        return Sql.scalar(conn, "SELECT COUNT(*) FROM EMPLOYEE WHERE status = 'ACTIVE'");
    }

    public long pending(Connection conn) throws SQLException {
        return Sql.scalar(conn, "SELECT COUNT(*) FROM APPROVAL_DOC WHERE status = 'IN_PROGRESS'");
    }

    public Map<String, Object> recruiting(Connection conn) throws SQLException {
        return Sql.one(conn, """
                SELECT (SELECT COUNT(*) FROM JOB_POSTING WHERE status = 'OPEN'
                    AND TRUNC(SYSDATE) BETWEEN open_date AND close_date) AS "openPostings",
                    (SELECT COUNT(*) FROM APPLICANT WHERE stage = 'INTERVIEW') AS "interviewApplicants",
                    (SELECT COUNT(*) FROM APPLICANT WHERE stage = 'OFFER') AS "offerApplicants"
                FROM DUAL
                """);
    }

    public Map<String, Object> evaluation(Connection conn) throws SQLException {
        return Sql.one(conn, """
                SELECT p.period_id AS "periodId", p.name AS "name", p.status AS "status",
                       (SELECT COUNT(*) FROM EVALUATION v WHERE v.period_id = p.period_id
                        AND v.eval_type = 'SELF' AND v.status = 'SUBMITTED') AS "selfSubmitted",
                       (SELECT COUNT(*) FROM EVALUATION v WHERE v.period_id = p.period_id
                        AND v.eval_type = 'MANAGER' AND v.status = 'SUBMITTED') AS "managerSubmitted"
                FROM (SELECT * FROM EVAL_PERIOD WHERE status <> 'CLOSED' ORDER BY start_date DESC, period_id DESC) p
                WHERE ROWNUM = 1
                """);
    }
}
