package com.hr.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.hr.dto.WorkPolicy;

/** WORK_POLICY. 한 행만 쓰므로 가장 작은 policy_id를 현재 규칙으로 본다. */
public class WorkPolicyDAO {

    private static final String COLUMNS = "policy_id, start_time, end_time, late_grace_min, weekly_limit_hours";

    public WorkPolicy find(Connection conn) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM WORK_POLICY"
                + " WHERE policy_id = (SELECT MIN(policy_id) FROM WORK_POLICY)";
        try (PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? map(rs) : null;
        }
    }

    public long insert(Connection conn, WorkPolicy p) throws SQLException {
        long id = DaoSupport.nextVal(conn, "SEQ_WORK_POLICY");
        String sql = "INSERT INTO WORK_POLICY (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, p.getStartTime());
            ps.setString(3, p.getEndTime());
            ps.setInt(4, p.getLateGraceMin());
            ps.setInt(5, p.getWeeklyLimitHours());
            ps.executeUpdate();
        }
        return id;
    }

    public int update(Connection conn, WorkPolicy p) throws SQLException {
        String sql = "UPDATE WORK_POLICY SET start_time = ?, end_time = ?, late_grace_min = ?, weekly_limit_hours = ?"
                + " WHERE policy_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, p.getStartTime());
            ps.setString(2, p.getEndTime());
            ps.setInt(3, p.getLateGraceMin());
            ps.setInt(4, p.getWeeklyLimitHours());
            ps.setLong(5, p.getPolicyId());
            return ps.executeUpdate();
        }
    }

    private static WorkPolicy map(ResultSet rs) throws SQLException {
        WorkPolicy p = new WorkPolicy();
        p.setPolicyId(rs.getLong("policy_id"));
        p.setStartTime(rs.getString("start_time"));
        p.setEndTime(rs.getString("end_time"));
        p.setLateGraceMin(rs.getInt("late_grace_min"));
        p.setWeeklyLimitHours(rs.getInt("weekly_limit_hours"));
        return p;
    }
}
