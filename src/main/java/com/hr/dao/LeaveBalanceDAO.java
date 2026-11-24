package com.hr.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.hr.dto.LeaveBalance;

/** LEAVE_BALANCE (+ 관리자 목록에서 EMPLOYEE, DEPARTMENT 조인) */
public class LeaveBalanceDAO {

    /** 차감 대상 휴가(연차·반차) 중 결재 대기 일수. 신청 연도(start_date) 기준. */
    private static final String PENDING_SUBQUERY = "NVL((SELECT SUM(r.days) FROM LEAVE_REQUEST r"
            + " WHERE r.emp_id = e.emp_id AND r.status = 'PENDING'"
            + " AND r.leave_type IN ('ANNUAL', 'HALF_AM', 'HALF_PM')"
            + " AND EXTRACT(YEAR FROM r.start_date) = ?), 0)";

    public LeaveBalance find(Connection conn, long empId, int year, boolean forUpdate) throws SQLException {
        String sql = "SELECT emp_id, year, total_days, used_days FROM LEAVE_BALANCE WHERE emp_id = ? AND year = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, empId);
            ps.setInt(2, year);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                LeaveBalance b = new LeaveBalance();
                b.setEmpId(rs.getLong("emp_id"));
                b.setYear(rs.getInt("year"));
                b.setTotalDays(rs.getBigDecimal("total_days"));
                b.setUsedDays(rs.getBigDecimal("used_days"));
                return b;
            }
        }
    }

    public void insert(Connection conn, long empId, int year, BigDecimal totalDays) throws SQLException {
        String sql = "INSERT INTO LEAVE_BALANCE (emp_id, year, total_days, used_days) VALUES (?, ?, ?, 0)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, empId);
            ps.setInt(2, year);
            ps.setBigDecimal(3, totalDays);
            ps.executeUpdate();
        }
    }

    public int updateTotal(Connection conn, long empId, int year, BigDecimal totalDays) throws SQLException {
        String sql = "UPDATE LEAVE_BALANCE SET total_days = ? WHERE emp_id = ? AND year = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBigDecimal(1, totalDays);
            ps.setLong(2, empId);
            ps.setInt(3, year);
            return ps.executeUpdate();
        }
    }

    /** 사용 일수를 늘린다. 잔여가 모자라면 바꾸지 않고 0을 돌려준다. */
    public int addUsed(Connection conn, long empId, int year, BigDecimal days) throws SQLException {
        String sql = "UPDATE LEAVE_BALANCE SET used_days = used_days + ?"
                + " WHERE emp_id = ? AND year = ? AND used_days + ? <= total_days";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBigDecimal(1, days);
            ps.setLong(2, empId);
            ps.setInt(3, year);
            ps.setBigDecimal(4, days);
            return ps.executeUpdate();
        }
    }

    /** 해당 연도 연차 행이 없는 재직자 전원에게 일괄 부여. 만든 행 수를 돌려준다. */
    public int grantMissing(Connection conn, int year, BigDecimal totalDays) throws SQLException {
        String sql = "INSERT INTO LEAVE_BALANCE (emp_id, year, total_days, used_days)"
                + " SELECT e.emp_id, ?, ?, 0 FROM EMPLOYEE e"
                + " WHERE e.status <> 'RESIGNED'"
                + " AND NOT EXISTS (SELECT 1 FROM LEAVE_BALANCE b WHERE b.emp_id = e.emp_id AND b.year = ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, year);
            ps.setBigDecimal(2, totalDays);
            ps.setInt(3, year);
            return ps.executeUpdate();
        }
    }

    /** 해당 연도 연차 행이 없는 재직자의 emp_id (일괄 부여 대상) */
    public List<Long> findMissingEmpIds(Connection conn, int year) throws SQLException {
        String sql = "SELECT e.emp_id FROM EMPLOYEE e WHERE e.status <> 'RESIGNED'"
                + " AND NOT EXISTS (SELECT 1 FROM LEAVE_BALANCE b WHERE b.emp_id = e.emp_id AND b.year = ?)"
                + " ORDER BY e.emp_id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
                return ids;
            }
        }
    }

    // ------------------------------------------------------------------ 관리자 목록

    public int countForAdmin(Connection conn, Long deptId, String keyword) throws SQLException {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM EMPLOYEE e WHERE e.status <> 'RESIGNED'" + filter(deptId, keyword, params);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, 1, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** 재직자 전원의 해당 연도 연차. 행이 없는 직원은 granted=false로 나온다. */
    public List<LeaveBalance> findForAdmin(Connection conn, int year, Long deptId, String keyword, int offset,
            int limit) throws SQLException {
        List<Object> params = new ArrayList<>();
        String inner = "SELECT e.emp_id, e.emp_no, e.name AS emp_name, d.name AS dept_name,"
                + " b.year, b.total_days, b.used_days, " + PENDING_SUBQUERY + " AS pending_days"
                + " FROM EMPLOYEE e"
                + " LEFT JOIN DEPARTMENT d ON d.dept_id = e.dept_id"
                + " LEFT JOIN LEAVE_BALANCE b ON b.emp_id = e.emp_id AND b.year = ?"
                + " WHERE e.status <> 'RESIGNED'" + filter(deptId, keyword, params)
                + " ORDER BY d.name NULLS LAST, e.name, e.emp_id";
        String sql = "SELECT * FROM (SELECT ROWNUM rn, x.* FROM (" + inner + ") x WHERE ROWNUM <= ?) WHERE rn > ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setInt(i++, year); // PENDING_SUBQUERY
            ps.setInt(i++, year); // LEFT JOIN LEAVE_BALANCE
            i = bind(ps, i, params);
            ps.setInt(i++, offset + limit);
            ps.setInt(i, offset);
            try (ResultSet rs = ps.executeQuery()) {
                List<LeaveBalance> rows = new ArrayList<>();
                while (rs.next()) {
                    LeaveBalance b = new LeaveBalance();
                    b.setEmpId(rs.getLong("emp_id"));
                    b.setEmpNo(rs.getString("emp_no"));
                    b.setEmpName(rs.getString("emp_name"));
                    b.setDeptName(rs.getString("dept_name"));
                    b.setYear(year);
                    BigDecimal total = rs.getBigDecimal("total_days");
                    b.setGranted(total != null);
                    b.setTotalDays(total == null ? BigDecimal.ZERO : total);
                    BigDecimal used = rs.getBigDecimal("used_days");
                    b.setUsedDays(used == null ? BigDecimal.ZERO : used);
                    b.setPendingDays(rs.getBigDecimal("pending_days"));
                    rows.add(b);
                }
                return rows;
            }
        }
    }

    private static String filter(Long deptId, String keyword, List<Object> params) {
        StringBuilder sql = new StringBuilder();
        if (deptId != null) {
            sql.append(" AND e.dept_id = ?");
            params.add(deptId);
        }
        if (keyword != null) {
            sql.append(" AND (e.name LIKE ? ESCAPE '\\' OR e.emp_no LIKE ? ESCAPE '\\')");
            String like = DaoSupport.likeContains(keyword);
            params.add(like);
            params.add(like);
        }
        return sql.toString();
    }

    /** params를 startIndex부터 바인딩하고 다음 인덱스를 돌려준다. */
    private static int bind(PreparedStatement ps, int startIndex, List<Object> params) throws SQLException {
        int i = startIndex;
        for (Object p : params) {
            if (p instanceof Long l) {
                ps.setLong(i++, l);
            } else {
                ps.setString(i++, (String) p);
            }
        }
        return i;
    }
}
