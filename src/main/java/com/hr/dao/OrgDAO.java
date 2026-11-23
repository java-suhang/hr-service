package com.hr.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** DEPARTMENT, JOB_POSITION */
public class OrgDAO {

    /** 부서. employeeCount = 퇴사자를 뺀 소속 인원, totalCount = 퇴사자 포함 (삭제 가능 여부) */
    public record Department(long deptId, String name, int employeeCount, int totalCount) {}

    /** 직급. level이 클수록 상위 */
    public record Position(long posId, String name, int level, int employeeCount, int totalCount) {}

    private static final String DEPT_SELECT = "SELECT d.dept_id, d.name,"
            + " (SELECT COUNT(*) FROM EMPLOYEE e WHERE e.dept_id = d.dept_id AND e.status <> 'RESIGNED') active_cnt,"
            + " (SELECT COUNT(*) FROM EMPLOYEE e WHERE e.dept_id = d.dept_id) total_cnt"
            + " FROM DEPARTMENT d";

    private static final String POS_SELECT = "SELECT p.pos_id, p.name, p.pos_level,"
            + " (SELECT COUNT(*) FROM EMPLOYEE e WHERE e.pos_id = p.pos_id AND e.status <> 'RESIGNED') active_cnt,"
            + " (SELECT COUNT(*) FROM EMPLOYEE e WHERE e.pos_id = p.pos_id) total_cnt"
            + " FROM JOB_POSITION p";

    // ------------------------------------------------------------------ 부서

    public List<Department> departments(Connection conn) throws SQLException {
        List<Department> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(DEPT_SELECT + " ORDER BY d.name, d.dept_id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(dept(rs));
            }
        }
        return list;
    }

    public Department department(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(DEPT_SELECT + " WHERE d.dept_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? dept(rs) : null;
            }
        }
    }

    public long insertDepartment(Connection conn, String name) throws SQLException {
        long id = DaoSupport.nextVal(conn, "SEQ_DEPARTMENT");
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO DEPARTMENT (dept_id, name) VALUES (?, ?)")) {
            ps.setLong(1, id);
            ps.setString(2, name);
            ps.executeUpdate();
        }
        return id;
    }

    public int updateDepartment(Connection conn, long id, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE DEPARTMENT SET name = ? WHERE dept_id = ?")) {
            ps.setString(1, name);
            ps.setLong(2, id);
            return ps.executeUpdate();
        }
    }

    public int deleteDepartment(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM DEPARTMENT WHERE dept_id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ 직급

    public List<Position> positions(Connection conn) throws SQLException {
        List<Position> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(POS_SELECT + " ORDER BY p.pos_level, p.pos_id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(pos(rs));
            }
        }
        return list;
    }

    public Position position(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(POS_SELECT + " WHERE p.pos_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? pos(rs) : null;
            }
        }
    }

    public long insertPosition(Connection conn, String name, int level) throws SQLException {
        long id = DaoSupport.nextVal(conn, "SEQ_JOB_POSITION");
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO JOB_POSITION (pos_id, name, pos_level) VALUES (?, ?, ?)")) {
            ps.setLong(1, id);
            ps.setString(2, name);
            ps.setInt(3, level);
            ps.executeUpdate();
        }
        return id;
    }

    public int updatePosition(Connection conn, long id, String name, int level) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE JOB_POSITION SET name = ?, pos_level = ? WHERE pos_id = ?")) {
            ps.setString(1, name);
            ps.setInt(2, level);
            ps.setLong(3, id);
            return ps.executeUpdate();
        }
    }

    public int deletePosition(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM JOB_POSITION WHERE pos_id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate();
        }
    }

    /** 같은 이름이 있는지 (exceptId는 수정 중인 자기 자신) */
    public boolean nameExists(Connection conn, boolean department, String name, Long exceptId) throws SQLException {
        String sql = department
                ? "SELECT COUNT(*) FROM DEPARTMENT WHERE name = ?" + (exceptId == null ? "" : " AND dept_id <> ?")
                : "SELECT COUNT(*) FROM JOB_POSITION WHERE name = ?" + (exceptId == null ? "" : " AND pos_id <> ?");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            if (exceptId != null) {
                ps.setLong(2, exceptId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static Department dept(ResultSet rs) throws SQLException {
        return new Department(rs.getLong("dept_id"), rs.getString("name"), rs.getInt("active_cnt"), rs.getInt("total_cnt"));
    }

    private static Position pos(ResultSet rs) throws SQLException {
        return new Position(rs.getLong("pos_id"), rs.getString("name"), rs.getInt("pos_level"),
                rs.getInt("active_cnt"), rs.getInt("total_cnt"));
    }
}
