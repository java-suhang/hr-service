package com.hr.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.hr.dto.Attendance;
import com.hr.dto.AttendanceDailyRow;

/** ATTENDANCE (+ 관리자 일자별 표에서 EMPLOYEE, DEPARTMENT 조인) */
public class AttendanceDAO {

    private static final String COLUMNS = "att_id, emp_id, work_date, check_in, check_out, status";

    /** 관리자 일자별 표 검색 조건. status는 NORMAL/LATE/EARLY_LEAVE/ABSENT/LEAVE 또는 NONE(미출근). */
    public record DailyFilter(LocalDate date, Long deptId, String status, String keyword) {
    }

    // ------------------------------------------------------------------ 조회

    public Attendance findByEmpAndDate(Connection conn, long empId, LocalDate date, boolean forUpdate)
            throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM ATTENDANCE WHERE emp_id = ? AND work_date = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, empId);
            DaoSupport.setDate(ps, 2, date);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    /** 한 직원의 기간 근태 (날짜순) */
    public List<Attendance> findByEmpBetween(Connection conn, long empId, LocalDate from, LocalDate to)
            throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM ATTENDANCE"
                + " WHERE emp_id = ? AND work_date BETWEEN ? AND ? ORDER BY work_date";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, empId);
            DaoSupport.setDate(ps, 2, from);
            DaoSupport.setDate(ps, 3, to);
            return list(ps);
        }
    }

    /** 여러 직원의 기간 근태 (주간 누적 계산용) */
    public List<Attendance> findByEmpsBetween(Connection conn, List<Long> empIds, LocalDate from, LocalDate to)
            throws SQLException {
        if (empIds.isEmpty()) {
            return new ArrayList<>();
        }
        String sql = "SELECT " + COLUMNS + " FROM ATTENDANCE"
                + " WHERE emp_id IN (" + DaoSupport.placeholders(empIds.size()) + ")"
                + " AND work_date BETWEEN ? AND ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            DaoSupport.setLongs(ps, 1, empIds);
            DaoSupport.setDate(ps, empIds.size() + 1, from);
            DaoSupport.setDate(ps, empIds.size() + 2, to);
            return list(ps);
        }
    }

    // ------------------------------------------------------------------ 변경

    public long insert(Connection conn, Attendance a) throws SQLException {
        long id = DaoSupport.nextVal(conn, "SEQ_ATTENDANCE");
        String sql = "INSERT INTO ATTENDANCE (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, a.getEmpId());
            DaoSupport.setDate(ps, 3, a.getWorkDate());
            DaoSupport.setDateTime(ps, 4, a.getCheckIn());
            DaoSupport.setDateTime(ps, 5, a.getCheckOut());
            ps.setString(6, a.getStatus());
            ps.executeUpdate();
        }
        return id;
    }

    /** 출퇴근 시각과 상태를 통째로 바꾼다 (관리자 수정, 기록만 있고 출근 전인 행의 출근) */
    public int updateTimes(Connection conn, long attId, LocalDateTime checkIn, LocalDateTime checkOut,
            String status) throws SQLException {
        String sql = "UPDATE ATTENDANCE SET check_in = ?, check_out = ?, status = ? WHERE att_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            DaoSupport.setDateTime(ps, 1, checkIn);
            DaoSupport.setDateTime(ps, 2, checkOut);
            ps.setString(3, status);
            ps.setLong(4, attId);
            return ps.executeUpdate();
        }
    }

    /** 퇴근 기록. 이미 퇴근한 행은 바꾸지 않는다 (0 반환). */
    public int updateCheckOut(Connection conn, long attId, LocalDateTime checkOut, String status)
            throws SQLException {
        String sql = "UPDATE ATTENDANCE SET check_out = ?, status = ?"
                + " WHERE att_id = ? AND check_in IS NOT NULL AND check_out IS NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            DaoSupport.setDateTime(ps, 1, checkOut);
            ps.setString(2, status);
            ps.setLong(3, attId);
            return ps.executeUpdate();
        }
    }

    /** 승인된 휴가일을 LEAVE로 표시한다. 행이 없으면 만든다. */
    public void markLeave(Connection conn, long empId, LocalDate date) throws SQLException {
        String sql = "MERGE INTO ATTENDANCE a"
                + " USING (SELECT ? AS emp_id, ? AS work_date FROM dual) s"
                + " ON (a.emp_id = s.emp_id AND a.work_date = s.work_date)"
                + " WHEN MATCHED THEN UPDATE SET a.status = 'LEAVE'"
                + " WHEN NOT MATCHED THEN INSERT (att_id, emp_id, work_date, status)"
                + " VALUES (SEQ_ATTENDANCE.NEXTVAL, s.emp_id, s.work_date, 'LEAVE')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, empId);
            DaoSupport.setDate(ps, 2, date);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ 관리자 일자별 표

    public int countDaily(Connection conn, DailyFilter f) throws SQLException {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT COUNT(*)" + dailyFromWhere(f, params);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public List<AttendanceDailyRow> findDaily(Connection conn, DailyFilter f, int offset, int limit)
            throws SQLException {
        List<Object> params = new ArrayList<>();
        String inner = "SELECT e.emp_id, e.emp_no, e.name AS emp_name, d.name AS dept_name,"
                + " a.att_id, a.check_in, a.check_out, a.status"
                + dailyFromWhere(f, params)
                + " ORDER BY d.name NULLS LAST, e.name, e.emp_id";
        String sql = "SELECT * FROM (SELECT ROWNUM rn, x.* FROM (" + inner + ") x WHERE ROWNUM <= ?) WHERE rn > ?";
        params.add((long) offset + limit);
        params.add((long) offset);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<AttendanceDailyRow> rows = new ArrayList<>();
                while (rs.next()) {
                    AttendanceDailyRow r = new AttendanceDailyRow();
                    r.setEmpId(rs.getLong("emp_id"));
                    r.setEmpNo(rs.getString("emp_no"));
                    r.setEmpName(rs.getString("emp_name"));
                    r.setDeptName(rs.getString("dept_name"));
                    r.setAttId(DaoSupport.getLongOrNull(rs, "att_id"));
                    r.setCheckIn(DaoSupport.getDateTime(rs, "check_in"));
                    r.setCheckOut(DaoSupport.getDateTime(rs, "check_out"));
                    r.setStatus(rs.getString("status"));
                    rows.add(r);
                }
                return rows;
            }
        }
    }

    /** 그날 재직 중이던 직원 + 그날 근태. 퇴사자라도 그날 기록이 있으면 나온다. */
    private static String dailyFromWhere(DailyFilter f, List<Object> params) {
        StringBuilder sql = new StringBuilder()
                .append(" FROM EMPLOYEE e")
                .append(" LEFT JOIN DEPARTMENT d ON d.dept_id = e.dept_id")
                .append(" LEFT JOIN ATTENDANCE a ON a.emp_id = e.emp_id AND a.work_date = ?")
                .append(" WHERE (e.status <> 'RESIGNED' OR a.att_id IS NOT NULL)")
                .append(" AND (e.hire_date IS NULL OR e.hire_date <= ?)");
        params.add(f.date());
        params.add(f.date());
        if (f.deptId() != null) {
            sql.append(" AND e.dept_id = ?");
            params.add(f.deptId());
        }
        if (f.status() != null) {
            sql.append(" AND NVL(a.status, 'NONE') = ?");
            params.add(f.status());
        }
        if (f.keyword() != null) {
            sql.append(" AND (e.name LIKE ? ESCAPE '\\' OR e.emp_no LIKE ? ESCAPE '\\')");
            String like = DaoSupport.likeContains(f.keyword());
            params.add(like);
            params.add(like);
        }
        return sql.toString();
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof LocalDate d) {
                DaoSupport.setDate(ps, i + 1, d);
            } else if (p instanceof Long l) {
                ps.setLong(i + 1, l);
            } else {
                ps.setString(i + 1, (String) p);
            }
        }
    }

    // ------------------------------------------------------------------ 매핑

    private static List<Attendance> list(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<Attendance> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(map(rs));
            }
            return rows;
        }
    }

    private static Attendance map(ResultSet rs) throws SQLException {
        Attendance a = new Attendance();
        a.setAttId(rs.getLong("att_id"));
        a.setEmpId(rs.getLong("emp_id"));
        a.setWorkDate(DaoSupport.getDate(rs, "work_date"));
        a.setCheckIn(DaoSupport.getDateTime(rs, "check_in"));
        a.setCheckOut(DaoSupport.getDateTime(rs, "check_out"));
        a.setStatus(rs.getString("status"));
        return a;
    }
}
