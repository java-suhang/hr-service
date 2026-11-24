package com.hr.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** EXPENSE (C) */
public class ExpenseDAO {

    /** receiptId: 첫 번째 영수증 파일, docId: 결재 문서 */
    public record Expense(long expId, long empId, String empName, String deptName, String category, long amount,
            LocalDate useDate, String description, String status, LocalDateTime createdAt, LocalDateTime paidAt,
            Long docId, Long receiptId) {
    }

    /** 관리자 목록 조건 (모두 선택) */
    public record Filter(String status, String category, String keyword, LocalDate from, LocalDate to) {
    }

    private static final String SELECT = """
            SELECT x.exp_id, x.emp_id, e.name AS emp_name, dp.name AS dept_name, x.category, x.amount, x.use_date,
                   x.description, x.status, x.created_at, x.paid_at,
                   (SELECT ad.doc_id FROM APPROVAL_DOC ad WHERE ad.doc_type = 'EXPENSE' AND ad.ref_id = x.exp_id) AS doc_id,
                   (SELECT MIN(a.file_id) FROM ATTACHMENT a WHERE a.ref_type = 'EXPENSE' AND a.ref_id = x.exp_id) AS receipt_id
            FROM EXPENSE x
            JOIN EMPLOYEE e ON e.emp_id = x.emp_id
            LEFT JOIN DEPARTMENT dp ON dp.dept_id = e.dept_id
            """;

    public long insert(Connection conn, long empId, String category, long amount, LocalDate useDate, String description)
            throws SQLException {
        long id = DaoSupport.nextVal(conn, "SEQ_EXPENSE");
        String sql = "INSERT INTO EXPENSE (exp_id, emp_id, category, amount, use_date, description, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'PENDING')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, empId);
            ps.setString(3, category);
            ps.setLong(4, amount);
            DaoSupport.setDate(ps, 5, useDate);
            ps.setString(6, description);
            ps.executeUpdate();
        }
        return id;
    }

    /** 잠그고 읽는다. 없으면 null */
    public Expense lockAndFind(Connection conn, long expId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT exp_id FROM EXPENSE WHERE exp_id = ? FOR UPDATE")) {
            ps.setLong(1, expId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
            }
        }
        return find(conn, expId);
    }

    public Expense find(Connection conn, long expId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT + " WHERE x.exp_id = ?")) {
            ps.setLong(1, expId);
            List<Expense> rows = rows(ps);
            return rows.isEmpty() ? null : rows.get(0);
        }
    }

    /** from 상태일 때만 to로 바꾼다. 바뀐 행 수 */
    public int changeStatus(Connection conn, long expId, String from, String to) throws SQLException {
        String sql = "PAID".equals(to)
                ? "UPDATE EXPENSE SET status = ?, paid_at = SYSDATE WHERE exp_id = ? AND status = ?"
                : "UPDATE EXPENSE SET status = ? WHERE exp_id = ? AND status = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, to);
            ps.setLong(2, expId);
            ps.setString(3, from);
            return ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ 내 경비

    public int countMine(Connection conn, long empId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM EXPENSE WHERE emp_id = ?")) {
            ps.setLong(1, empId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public List<Expense> findMine(Connection conn, long empId, int offset, int limit) throws SQLException {
        String inner = SELECT + " WHERE x.emp_id = ? ORDER BY x.use_date DESC, x.exp_id DESC";
        try (PreparedStatement ps = conn.prepareStatement(paged(inner))) {
            ps.setLong(1, empId);
            ps.setInt(2, offset + limit);
            ps.setInt(3, offset);
            return rows(ps);
        }
    }

    // ------------------------------------------------------------------ 관리자

    /** [건수, 금액 합계] */
    public long[] summarize(Connection conn, Filter f) throws SQLException {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT COUNT(*), COALESCE(SUM(x.amount), 0) FROM EXPENSE x JOIN EMPLOYEE e ON e.emp_id = x.emp_id"
                + where(f, params);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new long[] {rs.getLong(1), rs.getLong(2)};
            }
        }
    }

    public List<Expense> findAll(Connection conn, Filter f, int offset, int limit) throws SQLException {
        List<Object> params = new ArrayList<>();
        String inner = SELECT + where(f, params) + " ORDER BY x.use_date DESC, x.exp_id DESC";
        params.add(offset + limit);
        params.add(offset);
        try (PreparedStatement ps = conn.prepareStatement(paged(inner))) {
            bind(ps, params);
            return rows(ps);
        }
    }

    private static String where(Filter f, List<Object> params) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1");
        if (f.status() != null) {
            sb.append(" AND x.status = ?");
            params.add(f.status());
        }
        if (f.category() != null) {
            sb.append(" AND x.category = ?");
            params.add(f.category());
        }
        if (f.keyword() != null) {
            sb.append(" AND (e.name LIKE ? ESCAPE '\\' OR x.description LIKE ? ESCAPE '\\')");
            String like = DaoSupport.likeContains(f.keyword());
            params.add(like);
            params.add(like);
        }
        if (f.from() != null) {
            sb.append(" AND x.use_date >= ?");
            params.add(f.from());
        }
        if (f.to() != null) {
            sb.append(" AND x.use_date <= ?");
            params.add(f.to());
        }
        return sb.toString();
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object v = params.get(i);
            if (v instanceof LocalDate d) {
                DaoSupport.setDate(ps, i + 1, d);
            } else if (v instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else {
                ps.setString(i + 1, (String) v);
            }
        }
    }

    private static String paged(String inner) {
        return "SELECT * FROM (SELECT ROWNUM rn, y.* FROM (" + inner + ") y WHERE ROWNUM <= ?) WHERE rn > ?";
    }

    private static List<Expense> rows(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<Expense> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new Expense(rs.getLong("exp_id"), rs.getLong("emp_id"), rs.getString("emp_name"),
                        rs.getString("dept_name"), rs.getString("category"), rs.getLong("amount"),
                        DaoSupport.getDate(rs, "use_date"), rs.getString("description"), rs.getString("status"),
                        DaoSupport.getDateTime(rs, "created_at"), DaoSupport.getDateTime(rs, "paid_at"),
                        DaoSupport.getLongOrNull(rs, "doc_id"), DaoSupport.getLongOrNull(rs, "receipt_id")));
            }
            return rows;
        }
    }
}
