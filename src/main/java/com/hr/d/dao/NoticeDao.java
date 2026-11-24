package com.hr.d.dao;

import com.hr.d.dto.Page;
import com.hr.d.service.Problem;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

public final class NoticeDao {
    private static final String SUMMARY = """
            SELECT n.notice_id AS "noticeId", n.title AS "title", n.is_pinned AS "isPinned",
                   e.name AS "writer", TO_CHAR(n.created_at, 'YYYY-MM-DD"T"HH24:MI') AS "createdAt"
            FROM NOTICE n JOIN EMPLOYEE e ON e.emp_id = n.writer_id
            WHERE n.deleted_at IS NULL
            """;
    private static final String ORDER = "n.is_pinned DESC, n.created_at DESC, n.notice_id DESC";

    public Page list(Connection conn, Page.Request page, String search) throws SQLException {
        if (search == null) return Sql.page(conn, page, SUMMARY, ORDER);
        return Sql.page(conn, page, SUMMARY + " AND INSTR(LOWER(n.title), LOWER(?)) > 0", ORDER, search);
    }

    public Map<String, Object> find(Connection conn, long id) throws SQLException {
        return Sql.require(conn, """
                SELECT n.notice_id AS "noticeId", n.title AS "title", n.content AS "content",
                       n.is_pinned AS "isPinned", e.name AS "writer",
                       TO_CHAR(n.created_at, 'YYYY-MM-DD"T"HH24:MI') AS "createdAt",
                       TO_CHAR(n.updated_at, 'YYYY-MM-DD"T"HH24:MI') AS "updatedAt"
                FROM NOTICE n JOIN EMPLOYEE e ON e.emp_id = n.writer_id
                WHERE n.notice_id = ? AND n.deleted_at IS NULL
                """, id);
    }

    public long insert(Connection conn, long writer, String title, String content, boolean pinned) throws SQLException {
        long id = Sql.scalar(conn, "SELECT SEQ_NOTICE.NEXTVAL FROM DUAL");
        Sql.change(conn, "INSERT INTO NOTICE (notice_id, title, content, is_pinned, writer_id) VALUES (?, ?, ?, ?, ?)",
                id, title, new Sql.ClobText(content), pinned ? "Y" : "N", writer);
        return id;
    }

    public void update(Connection conn, long id, String title, String content, boolean pinned) throws SQLException {
        int changed = Sql.change(conn, """
                UPDATE NOTICE SET title = ?, content = ?, is_pinned = ?, updated_at = SYSDATE
                WHERE notice_id = ? AND deleted_at IS NULL
                """, title, new Sql.ClobText(content), pinned ? "Y" : "N", id);
        if (changed == 0) throw Problem.missing();
    }

    public void delete(Connection conn, long id) throws SQLException {
        int changed = Sql.change(conn, """
                UPDATE NOTICE SET deleted_at = SYSDATE, updated_at = SYSDATE
                WHERE notice_id = ? AND deleted_at IS NULL
                """, id);
        if (changed == 0) throw Problem.missing();
    }

    public java.util.List<Long> recipients(Connection conn) throws SQLException {
        return Sql.rows(conn, "SELECT emp_id AS \"id\" FROM EMPLOYEE WHERE status <> 'RESIGNED' ORDER BY emp_id")
                .stream().map(row -> ((Number) row.get("id")).longValue()).toList();
    }
}
