package com.hr.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import com.hr.dto.Employee;

/** EMPLOYEE (+ 부서·직급·상사 이름 조인) */
public class EmployeeDAO {

    /** 로그인 검증용 (비밀번호 해시는 이 객체 밖으로 나가지 않는다) */
    public record Credential(long empId, String passwordHash, String status) {}

    /** 목록 검색 조건. null이면 조건 없음 */
    public record Filter(String keyword, Long deptId, Long posId, String status) {}

    private static final String SELECT = "SELECT e.emp_id, e.emp_no, e.login_id, e.name, e.email, e.phone,"
            + " e.dept_id, d.name dept_name, e.pos_id, p.name pos_name, e.manager_id, m.name manager_name,"
            + " e.role, e.hire_date, e.status, e.updated_at"
            + " FROM EMPLOYEE e"
            + " LEFT JOIN DEPARTMENT d ON d.dept_id = e.dept_id"
            + " LEFT JOIN JOB_POSITION p ON p.pos_id = e.pos_id"
            + " LEFT JOIN EMPLOYEE m ON m.emp_id = e.manager_id";

    public Employee findById(Connection conn, long empId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT + " WHERE e.emp_id = ?")) {
            ps.setLong(1, empId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public Credential findCredential(Connection conn, String loginId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT emp_id, password_hash, status FROM EMPLOYEE WHERE login_id = ?")) {
            ps.setString(1, loginId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Credential(rs.getLong(1), rs.getString(2), rs.getString(3)) : null;
            }
        }
    }

    public String findPasswordHash(Connection conn, long empId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT password_hash FROM EMPLOYEE WHERE emp_id = ?")) {
            ps.setLong(1, empId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 동시에 같은 직원을 바꾸지 않도록 행을 잠근다. 없으면 false */
    public boolean lock(Connection conn, long empId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT emp_id FROM EMPLOYEE WHERE emp_id = ? FOR UPDATE")) {
            ps.setLong(1, empId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ------------------------------------------------------------------ 목록

    public int count(Connection conn, Filter f) throws SQLException {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM EMPLOYEE e" + where(f, params);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public List<Employee> search(Connection conn, Filter f, int offset, int limit) throws SQLException {
        List<Object> params = new ArrayList<>();
        String inner = SELECT + where(f, params) + " ORDER BY e.emp_no, e.emp_id";
        String sql = "SELECT * FROM (SELECT ROWNUM rn, x.* FROM (" + inner + ") x WHERE ROWNUM <= ?) WHERE rn > ?";
        params.add(offset + limit);
        params.add(offset);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return list(ps);
        }
    }

    /** 재직·휴직 직원 전체 (드롭다운, 조직도) */
    public List<Employee> findNotResigned(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                SELECT + " WHERE e.status <> 'RESIGNED' ORDER BY d.name NULLS LAST, p.pos_level DESC NULLS LAST, e.name")) {
            return list(ps);
        }
    }

    private static String where(Filter f, List<Object> params) {
        StringBuilder w = new StringBuilder(" WHERE 1 = 1");
        if (f.keyword() != null) {
            w.append(" AND (e.name LIKE ? ESCAPE '\\' OR e.emp_no LIKE ? ESCAPE '\\' OR e.login_id LIKE ? ESCAPE '\\')");
            String like = DaoSupport.likeContains(f.keyword());
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (f.deptId() != null) {
            w.append(" AND e.dept_id = ?");
            params.add(f.deptId());
        }
        if (f.posId() != null) {
            w.append(" AND e.pos_id = ?");
            params.add(f.posId());
        }
        if (f.status() != null) {
            w.append(" AND e.status = ?");
            params.add(f.status());
        }
        return w.toString();
    }

    // ------------------------------------------------------------------ 검사

    public boolean exists(Connection conn, String column, String value, Long exceptEmpId) throws SQLException {
        if (!column.equals("login_id") && !column.equals("emp_no")) {
            throw new IllegalArgumentException(column);
        }
        String sql = "SELECT COUNT(*) FROM EMPLOYEE WHERE " + column + " = ?" + (exceptEmpId == null ? "" : " AND emp_id <> ?");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            if (exceptEmpId != null) {
                ps.setLong(2, exceptEmpId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** 이 직원을 상사로 둔 재직·휴직 직원 수 */
    public int countSubordinates(Connection conn, long empId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM EMPLOYEE WHERE manager_id = ? AND status <> 'RESIGNED'")) {
            ps.setLong(1, empId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** 상사 한 명 (없으면 null) */
    public Long findManagerId(Connection conn, long empId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT manager_id FROM EMPLOYEE WHERE emp_id = ?")) {
            ps.setLong(1, empId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? DaoSupport.getLongOrNull(rs, "manager_id") : null;
            }
        }
    }

    public int countActiveAdmins(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM EMPLOYEE WHERE role = 'ADMIN' AND status = 'ACTIVE'");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ------------------------------------------------------------------ 변경

    public long insert(Connection conn, Employee e, String passwordHash) throws SQLException {
        long id = DaoSupport.nextVal(conn, "SEQ_EMPLOYEE");
        String sql = "INSERT INTO EMPLOYEE (emp_id, emp_no, login_id, password_hash, name, email, phone,"
                + " dept_id, pos_id, manager_id, role, hire_date, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, e.getEmpNo());
            ps.setString(3, e.getLoginId());
            ps.setString(4, passwordHash);
            ps.setString(5, e.getName());
            ps.setString(6, e.getEmail());
            ps.setString(7, e.getPhone());
            setLong(ps, 8, e.getDeptId());
            setLong(ps, 9, e.getPosId());
            setLong(ps, 10, e.getManagerId());
            ps.setString(11, e.getRole());
            DaoSupport.setDate(ps, 12, e.getHireDate());
            ps.setString(13, e.getStatus());
            ps.executeUpdate();
        }
        return id;
    }

    /** 로그인 아이디·비밀번호를 뺀 정보 */
    public int update(Connection conn, Employee e) throws SQLException {
        String sql = "UPDATE EMPLOYEE SET emp_no = ?, name = ?, email = ?, phone = ?, dept_id = ?, pos_id = ?,"
                + " manager_id = ?, role = ?, hire_date = ?, status = ?, updated_at = SYSDATE WHERE emp_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, e.getEmpNo());
            ps.setString(2, e.getName());
            ps.setString(3, e.getEmail());
            ps.setString(4, e.getPhone());
            setLong(ps, 5, e.getDeptId());
            setLong(ps, 6, e.getPosId());
            setLong(ps, 7, e.getManagerId());
            ps.setString(8, e.getRole());
            DaoSupport.setDate(ps, 9, e.getHireDate());
            ps.setString(10, e.getStatus());
            ps.setLong(11, e.getEmpId());
            return ps.executeUpdate();
        }
    }

    public int updatePassword(Connection conn, long empId, String passwordHash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE EMPLOYEE SET password_hash = ?, updated_at = SYSDATE WHERE emp_id = ?")) {
            ps.setString(1, passwordHash);
            ps.setLong(2, empId);
            return ps.executeUpdate();
        }
    }

    public int updateStatus(Connection conn, long empId, String status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE EMPLOYEE SET status = ?, updated_at = SYSDATE WHERE emp_id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, empId);
            return ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ 내부

    private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NUMERIC);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object v = params.get(i);
            if (v instanceof Long l) {
                ps.setLong(i + 1, l);
            } else if (v instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else {
                ps.setString(i + 1, (String) v);
            }
        }
    }

    private static List<Employee> list(PreparedStatement ps) throws SQLException {
        List<Employee> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(map(rs));
            }
        }
        return list;
    }

    private static Employee map(ResultSet rs) throws SQLException {
        Employee e = new Employee();
        e.setEmpId(rs.getLong("emp_id"));
        e.setEmpNo(rs.getString("emp_no"));
        e.setLoginId(rs.getString("login_id"));
        e.setName(rs.getString("name"));
        e.setEmail(rs.getString("email"));
        e.setPhone(rs.getString("phone"));
        e.setDeptId(DaoSupport.getLongOrNull(rs, "dept_id"));
        e.setDeptName(rs.getString("dept_name"));
        e.setPosId(DaoSupport.getLongOrNull(rs, "pos_id"));
        e.setPosName(rs.getString("pos_name"));
        e.setManagerId(DaoSupport.getLongOrNull(rs, "manager_id"));
        e.setManagerName(rs.getString("manager_name"));
        e.setRole(rs.getString("role"));
        e.setHireDate(DaoSupport.getDate(rs, "hire_date"));
        e.setStatus(rs.getString("status"));
        e.setUpdatedAt(DaoSupport.getDateTime(rs, "updated_at"));
        return e;
    }
}
