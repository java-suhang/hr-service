package com.hr.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.hr.dto.LeaveRequest;

/** LEAVE_REQUEST */
public class LeaveRequestDAO {

    private static final String COLUMNS =
            "req_id, emp_id, leave_type, start_date, end_date, days, reason, status, created_at";

    public long insert(Connection conn, LeaveRequest r) throws SQLException {
        long id = DaoSupport.nextVal(conn, "SEQ_LEAVE_REQUEST");
        String sql = "INSERT INTO LEAVE_REQUEST (req_id, emp_id, leave_type, start_date, end_date, days, reason, status)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, r.getEmpId());
            ps.setString(3, r.getLeaveType());
            DaoSupport.setDate(ps, 4, r.getStartDate());
            DaoSupport.setDate(ps, 5, r.getEndDate());
            ps.setBigDecimal(6, r.getDays());
            ps.setString(7, r.getReason());
            ps.setString(8, r.getStatus());
            ps.executeUpdate();
        }
        return id;
    }

    public LeaveRequest findById(Connection conn, long reqId, boolean forUpdate) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM LEAVE_REQUEST WHERE req_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, reqId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    /** 상태를 바꾼다. 현재 상태가 from이 아니면 바꾸지 않고 0을 돌려준다. */
    public int updateStatus(Connection conn, long reqId, String from, String to) throws SQLException {
        String sql = "UPDATE LEAVE_REQUEST SET status = ? WHERE req_id = ? AND status = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, to);
            ps.setLong(2, reqId);
            ps.setString(3, from);
            return ps.executeUpdate();
        }
    }

    /** 기간이 겹치는 진행 중·승인된 신청 */
    public List<LeaveRequest> findOverlapping(Connection conn, long empId, LocalDate start, LocalDate end)
            throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM LEAVE_REQUEST"
                + " WHERE emp_id = ? AND status IN ('PENDING', 'APPROVED')"
                + " AND start_date <= ? AND end_date >= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, empId);
            DaoSupport.setDate(ps, 2, end);
            DaoSupport.setDate(ps, 3, start);
            return list(ps);
        }
    }

    /** 결재 대기 중인 차감 대상(연차·반차) 일수 합 */
    public BigDecimal sumPendingDeductible(Connection conn, long empId, int year) throws SQLException {
        String sql = "SELECT NVL(SUM(days), 0) FROM LEAVE_REQUEST"
                + " WHERE emp_id = ? AND status = 'PENDING'"
                + " AND leave_type IN ('ANNUAL', 'HALF_AM', 'HALF_PM')"
                + " AND EXTRACT(YEAR FROM start_date) = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, empId);
            ps.setInt(2, year);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    public int countByEmp(Connection conn, long empId, int year) throws SQLException {
        String sql = "SELECT COUNT(*) FROM LEAVE_REQUEST WHERE emp_id = ? AND EXTRACT(YEAR FROM start_date) = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, empId);
            ps.setInt(2, year);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** 내 신청 내역 (시작일 최신순) */
    public List<LeaveRequest> findByEmp(Connection conn, long empId, int year, int offset, int limit)
            throws SQLException {
        String inner = "SELECT " + COLUMNS + " FROM LEAVE_REQUEST"
                + " WHERE emp_id = ? AND EXTRACT(YEAR FROM start_date) = ?"
                + " ORDER BY start_date DESC, req_id DESC";
        String sql = "SELECT * FROM (SELECT ROWNUM rn, x.* FROM (" + inner + ") x WHERE ROWNUM <= ?) WHERE rn > ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, empId);
            ps.setInt(2, year);
            ps.setInt(3, offset + limit);
            ps.setInt(4, offset);
            return list(ps);
        }
    }

    /**
     * 직원 행을 잠근다. 같은 직원의 휴가 신청이 동시에 들어와 겹침·잔여 검사를 함께 통과하는 것을 막는다.
     * @return 직원이 있으면 true
     */
    public boolean lockEmployee(Connection conn, long empId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT emp_id FROM EMPLOYEE WHERE emp_id = ? FOR UPDATE")) {
            ps.setLong(1, empId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 상사 이름 (결재선 미리보기용). 상사가 없으면 null. */
    public String findManagerName(Connection conn, long empId) throws SQLException {
        String sql = "SELECT m.name FROM EMPLOYEE e JOIN EMPLOYEE m ON m.emp_id = e.manager_id WHERE e.emp_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, empId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static List<LeaveRequest> list(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<LeaveRequest> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(map(rs));
            }
            return rows;
        }
    }

    private static LeaveRequest map(ResultSet rs) throws SQLException {
        LeaveRequest r = new LeaveRequest();
        r.setReqId(rs.getLong("req_id"));
        r.setEmpId(rs.getLong("emp_id"));
        r.setLeaveType(rs.getString("leave_type"));
        r.setStartDate(DaoSupport.getDate(rs, "start_date"));
        r.setEndDate(DaoSupport.getDate(rs, "end_date"));
        r.setDays(rs.getBigDecimal("days"));
        r.setReason(rs.getString("reason"));
        r.setStatus(rs.getString("status"));
        r.setCreatedAt(DaoSupport.getDateTime(rs, "created_at"));
        return r;
    }
}
