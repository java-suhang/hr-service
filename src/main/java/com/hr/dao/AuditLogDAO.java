package com.hr.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** AUDIT_LOG (REQ-21) */
public class AuditLogDAO {

    public record Entry(long logId, Long actorId, String actorName, String action, String targetTable,
                        Long targetId, String beforeVal, String afterVal, LocalDateTime createdAt) {}

    /** 검색 조건. null이면 조건 없음. to는 그날 포함 */
    public record Filter(String targetTable, String action, Long targetId, LocalDate from, LocalDate to) {}

    public void insert(Connection conn, Long actorId, String action, String table, Long targetId,
                       String before, String after) throws SQLException {
        long id = DaoSupport.nextVal(conn, "SEQ_AUDIT_LOG");
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO AUDIT_LOG (log_id, actor_id, action, target_table, target_id, before_val, after_val)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, id);
            if (actorId == null) ps.setNull(2, java.sql.Types.NUMERIC); else ps.setLong(2, actorId);
            ps.setString(3, action);
            ps.setString(4, table);
            if (targetId == null) ps.setNull(5, java.sql.Types.NUMERIC); else ps.setLong(5, targetId);
            ps.setString(6, before);
            ps.setString(7, after);
            ps.executeUpdate();
        }
    }

    public int count(Connection conn, Filter f) throws SQLException {
        List<Object> params = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM AUDIT_LOG l" + where(f, params))) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public List<Entry> search(Connection conn, Filter f, int offset, int limit) throws SQLException {
        List<Object> params = new ArrayList<>();
        String inner = "SELECT l.log_id, l.actor_id, e.name actor_name, l.action, l.target_table, l.target_id,"
                + " l.before_val, l.after_val, l.created_at"
                + " FROM AUDIT_LOG l LEFT JOIN EMPLOYEE e ON e.emp_id = l.actor_id"
                + where(f, params) + " ORDER BY l.created_at DESC, l.log_id DESC";
        String sql = "SELECT * FROM (SELECT ROWNUM rn, x.* FROM (" + inner + ") x WHERE ROWNUM <= ?) WHERE rn > ?";
        params.add(offset + limit);
        params.add(offset);
        List<Entry> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Entry(rs.getLong("log_id"), DaoSupport.getLongOrNull(rs, "actor_id"),
                            rs.getString("actor_name"), rs.getString("action"), rs.getString("target_table"),
                            DaoSupport.getLongOrNull(rs, "target_id"), rs.getString("before_val"),
                            rs.getString("after_val"), DaoSupport.getDateTime(rs, "created_at")));
                }
            }
        }
        return list;
    }

    private static String where(Filter f, List<Object> params) {
        StringBuilder w = new StringBuilder(" WHERE 1 = 1");
        if (f.targetTable() != null) {
            w.append(" AND l.target_table = ?");
            params.add(f.targetTable());
        }
        if (f.action() != null) {
            w.append(" AND l.action = ?");
            params.add(f.action());
        }
        if (f.targetId() != null) {
            w.append(" AND l.target_id = ?");
            params.add(f.targetId());
        }
        if (f.from() != null) {
            w.append(" AND l.created_at >= ?");
            params.add(f.from());
        }
        if (f.to() != null) {
            w.append(" AND l.created_at < ?");
            params.add(f.to().plusDays(1));
        }
        return w.toString();
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object v = params.get(i);
            if (v instanceof Long l) ps.setLong(i + 1, l);
            else if (v instanceof Integer n) ps.setInt(i + 1, n);
            else if (v instanceof LocalDate d) DaoSupport.setDate(ps, i + 1, d);
            else ps.setString(i + 1, (String) v);
        }
    }
}
