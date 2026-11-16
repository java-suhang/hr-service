package com.hr.d.dao;

import com.hr.d.dto.Page;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

public final class EvaluationDao {
    private static final String PERIODS = """
            SELECT period_id AS "periodId", name AS "name", status AS "status",
                   TO_CHAR(start_date, 'YYYY-MM-DD') AS "startDate",
                   TO_CHAR(end_date, 'YYYY-MM-DD') AS "endDate",
                   CASE WHEN TRUNC(SYSDATE) BETWEEN start_date AND end_date THEN 1 ELSE 0 END AS "withinDates"
            FROM EVAL_PERIOD
            """;

    public Page periods(Connection conn, Page.Request page, boolean admin) throws SQLException {
        return Sql.page(conn, page, PERIODS + (admin ? "" : " WHERE status <> 'READY'"), "start_date DESC, period_id DESC");
    }

    public Map<String, Object> period(Connection conn, long id, boolean admin) throws SQLException {
        return Sql.require(conn, PERIODS + " WHERE period_id = ?" + (admin ? "" : " AND status <> 'READY'"), id);
    }

    public Map<String, Object> lockPeriod(Connection conn, long id) throws SQLException {
        Sql.require(conn, "SELECT period_id FROM EVAL_PERIOD WHERE period_id = ? FOR UPDATE", id);
        return period(conn, id, true);
    }

    public long createPeriod(Connection conn, String name, LocalDate start, LocalDate end) throws SQLException {
        long id = Sql.scalar(conn, "SELECT SEQ_EVAL_PERIOD.NEXTVAL FROM DUAL");
        Sql.change(conn, "INSERT INTO EVAL_PERIOD (period_id, name, start_date, end_date) VALUES (?, ?, ?, ?)",
                id, name, start, end);
        return id;
    }

    public void updatePeriod(Connection conn, long id, String name, LocalDate start, LocalDate end) throws SQLException {
        Sql.change(conn, "UPDATE EVAL_PERIOD SET name = ?, start_date = ?, end_date = ? WHERE period_id = ?", name, start, end, id);
    }

    public void setStatus(Connection conn, long id, String status) throws SQLException {
        Sql.change(conn, "UPDATE EVAL_PERIOD SET status = ? WHERE period_id = ?", status, id);
    }

    public Page items(Connection conn, long period, Page.Request page) throws SQLException {
        return Sql.page(conn, page, """
                SELECT item_id AS "itemId", name AS "name", description AS "description", weight AS "weight"
                FROM EVAL_ITEM WHERE period_id = ?
                """, "item_id", period);
    }

    public Map<Long, BigDecimal> weights(Connection conn, long period) throws SQLException {
        var weights = new LinkedHashMap<Long, BigDecimal>();
        try (var stmt = conn.prepareStatement("SELECT item_id, weight FROM EVAL_ITEM WHERE period_id = ?")) {
            stmt.setLong(1, period);
            try (var rows = stmt.executeQuery()) {
                while (rows.next()) weights.put(rows.getLong(1), rows.getBigDecimal(2));
            }
        }
        return weights;
    }

    public long addItem(Connection conn, long period, String name, String description, BigDecimal weight) throws SQLException {
        long id = Sql.scalar(conn, "SELECT SEQ_EVAL_ITEM.NEXTVAL FROM DUAL");
        Sql.change(conn, "INSERT INTO EVAL_ITEM (item_id, period_id, name, description, weight) VALUES (?, ?, ?, ?, ?)",
                id, period, name, description, weight);
        return id;
    }

    public long periodForItem(Connection conn, long item) throws SQLException {
        return ((Number) Sql.require(conn, "SELECT period_id AS \"id\" FROM EVAL_ITEM WHERE item_id = ?", item)
                .get("id")).longValue();
    }

    public int updateItem(Connection conn, long item, long period, String name, String description, BigDecimal weight)
            throws SQLException {
        return Sql.change(conn, """
                UPDATE EVAL_ITEM SET name = ?, description = ?, weight = ?
                WHERE item_id = ? AND period_id = ?
                """, name, description, weight, item, period);
    }

    public int deleteItem(Connection conn, long item, long period) throws SQLException {
        return Sql.change(conn, "DELETE FROM EVAL_ITEM WHERE item_id = ? AND period_id = ?", item, period);
    }

    public Page team(Connection conn, long manager, Page.Request page) throws SQLException {
        return Sql.page(conn, page, """
                SELECT emp_id AS "empId", name AS "name", emp_no AS "empNo" FROM EMPLOYEE
                WHERE manager_id = ? AND status <> 'RESIGNED'
                """, "name, emp_id", manager);
    }

    public Map<String, Object> lockEmployee(Connection conn, long id) throws SQLException {
        Sql.require(conn, "SELECT emp_id FROM EMPLOYEE WHERE emp_id = ? FOR UPDATE", id);
        return Sql.require(conn, "SELECT manager_id AS \"managerId\", status AS \"status\" FROM EMPLOYEE WHERE emp_id = ?", id);
    }

    public Map<String, Object> existing(Connection conn, long period, long target, String type) throws SQLException {
        return Sql.one(conn, """
                SELECT eval_id AS "evalId", evaluator_id AS "evaluatorId", status AS "status"
                FROM EVALUATION WHERE period_id = ? AND target_id = ? AND eval_type = ?
                """, period, target, type);
    }

    public long save(Connection conn, Long existing, long period, long target, long evaluator, String type,
                     BigDecimal total, String comment, Map<Long, Integer> scores) throws SQLException {
        long id;
        if (existing == null) {
            id = Sql.scalar(conn, "SELECT SEQ_EVALUATION.NEXTVAL FROM DUAL");
            Sql.change(conn, """
                    INSERT INTO EVALUATION (eval_id, period_id, target_id, evaluator_id, eval_type, total_score,
                        comment_text, status, submitted_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'SUBMITTED', SYSDATE)
                    """, id, period, target, evaluator, type, total, comment);
        } else {
            id = existing;
            Sql.change(conn, """
                    UPDATE EVALUATION SET evaluator_id = ?, total_score = ?, comment_text = ?,
                        status = 'SUBMITTED', submitted_at = SYSDATE WHERE eval_id = ?
                    """, evaluator, total, comment, id);
            Sql.change(conn, "DELETE FROM EVAL_SCORE WHERE eval_id = ?", id);
        }
        try (var stmt = conn.prepareStatement("INSERT INTO EVAL_SCORE (eval_id, item_id, score) VALUES (?, ?, ?)")) {
            for (var score : scores.entrySet()) {
                stmt.setLong(1, id);
                stmt.setLong(2, score.getKey());
                stmt.setInt(3, score.getValue());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
        return id;
    }

    public Page status(Connection conn, long period, Page.Request page) throws SQLException {
        return Sql.page(conn, page, """
                SELECT e.emp_id AS "empId", e.name AS "name", e.emp_no AS "empNo",
                       m.name AS "manager", s.eval_id AS "selfId", s.total_score AS "selfScore",
                       v.eval_id AS "managerEvalId", v.total_score AS "managerScore"
                FROM EMPLOYEE e LEFT JOIN EMPLOYEE m ON e.manager_id = m.emp_id
                LEFT JOIN EVALUATION s ON s.target_id = e.emp_id AND s.period_id = ?
                    AND s.eval_type = 'SELF' AND s.status = 'SUBMITTED'
                LEFT JOIN EVALUATION v ON v.target_id = e.emp_id AND v.period_id = ?
                    AND v.eval_type = 'MANAGER' AND v.status = 'SUBMITTED'
                WHERE e.status <> 'RESIGNED' OR s.eval_id IS NOT NULL OR v.eval_id IS NOT NULL
                """, "e.name, e.emp_id", period, period);
    }

    public Page submittedFor(Connection conn, long period, long target, boolean showManager, Page.Request page)
            throws SQLException {
        return Sql.page(conn, page, """
                SELECT eval_id AS "evalId", eval_type AS "evalType", total_score AS "totalScore",
                       TO_CHAR(submitted_at, 'YYYY-MM-DD"T"HH24:MI') AS "submittedAt"
                FROM EVALUATION WHERE period_id = ? AND target_id = ? AND status = 'SUBMITTED'
                """ + (showManager ? "" : " AND eval_type = 'SELF'"), "eval_type DESC", period, target);
    }

    public Page reviewsBy(Connection conn, long period, long reviewer, Page.Request page) throws SQLException {
        return Sql.page(conn, page, """
                SELECT v.eval_id AS "evalId", v.target_id AS "targetId", e.name AS "name", v.total_score AS "totalScore",
                       TO_CHAR(v.submitted_at, 'YYYY-MM-DD"T"HH24:MI') AS "submittedAt"
                FROM EVALUATION v JOIN EMPLOYEE e ON e.emp_id = v.target_id
                WHERE v.period_id = ? AND v.evaluator_id = ? AND v.eval_type = 'MANAGER' AND v.status = 'SUBMITTED'
                """, "e.name, v.eval_id", period, reviewer);
    }

    public Map<String, Object> result(Connection conn, long id) throws SQLException {
        var row = Sql.require(conn, """
                SELECT v.eval_id AS "evalId", v.eval_type AS "evalType", v.total_score AS "totalScore",
                       v.target_id AS "targetId", v.evaluator_id AS "evaluatorId",
                       e.name AS "targetName", v.comment_text AS "comment", p.status AS "periodStatus",
                       TO_CHAR(v.submitted_at, 'YYYY-MM-DD"T"HH24:MI') AS "submittedAt"
                FROM EVALUATION v JOIN EVAL_PERIOD p ON p.period_id = v.period_id
                JOIN EMPLOYEE e ON e.emp_id = v.target_id
                WHERE v.eval_id = ? AND v.status = 'SUBMITTED'
                """, id);
        return row;
    }

    public List<Map<String, Object>> scores(Connection conn, long id) throws SQLException {
        return Sql.rows(conn, """
                SELECT i.item_id AS "itemId", i.name AS "name", i.weight AS "weight", s.score AS "score"
                FROM EVAL_SCORE s JOIN EVAL_ITEM i ON i.item_id = s.item_id WHERE s.eval_id = ? ORDER BY i.item_id
                """, id);
    }
}
