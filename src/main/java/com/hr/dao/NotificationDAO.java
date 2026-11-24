package com.hr.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** NOTIFICATION (C) */
public class NotificationDAO {

    public record Notification(long notiId, String type, String message, String link, boolean read,
            LocalDateTime createdAt) {
    }

    public void insert(Connection conn, long empId, String type, String message, String link) throws SQLException {
        String sql = "INSERT INTO NOTIFICATION (noti_id, emp_id, noti_type, message, link, is_read) VALUES (?, ?, ?, ?, ?, 'N')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, DaoSupport.nextVal(conn, "SEQ_NOTIFICATION"));
            ps.setLong(2, empId);
            ps.setString(3, type);
            ps.setString(4, message);
            ps.setString(5, link);
            ps.executeUpdate();
        }
    }

    public int count(Connection conn, long empId, boolean unreadOnly) throws SQLException {
        String sql = "SELECT COUNT(*) FROM NOTIFICATION WHERE emp_id = ?" + (unreadOnly ? " AND is_read = 'N'" : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, empId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public List<Notification> find(Connection conn, long empId, int offset, int limit) throws SQLException {
        String sql = "SELECT * FROM (SELECT ROWNUM rn, x.* FROM ("
                + "SELECT noti_id, noti_type, message, link, is_read, created_at FROM NOTIFICATION "
                + "WHERE emp_id = ? ORDER BY noti_id DESC) x WHERE ROWNUM <= ?) WHERE rn > ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, empId);
            ps.setInt(2, offset + limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                List<Notification> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new Notification(rs.getLong("noti_id"), rs.getString("noti_type"), rs.getString("message"),
                            rs.getString("link"), "Y".equals(rs.getString("is_read")),
                            DaoSupport.getDateTime(rs, "created_at")));
                }
                return rows;
            }
        }
    }

    /** 본인 알림만 읽음 처리한다. 본인 알림이 아니거나 없으면 0 */
    public int markRead(Connection conn, long empId, long notiId) throws SQLException {
        String sql = "UPDATE NOTIFICATION SET is_read = 'Y' WHERE noti_id = ? AND emp_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, notiId);
            ps.setLong(2, empId);
            return ps.executeUpdate();
        }
    }

    public int markAllRead(Connection conn, long empId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE NOTIFICATION SET is_read = 'Y' WHERE emp_id = ? AND is_read = 'N'")) {
            ps.setLong(1, empId);
            return ps.executeUpdate();
        }
    }
}
