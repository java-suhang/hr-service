package com.hr.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** APPROVAL_DOC · APPROVAL_STEP (C) */
public class ApprovalDAO {

    /** 목록·상세에 쓰는 문서 한 건. current*는 지금 차례인 단계 (끝난 문서는 마지막 단계) */
    public record Doc(long docId, String docNo, String docType, long refId, long drafterId, String drafterName,
            String drafterDept, String drafterPos, String title, String status, int currentStep, int totalSteps,
            String currentRole, Long currentApproverId, String currentApproverName, LocalDateTime createdAt,
            LocalDateTime completedAt) {
    }

    public record Step(long stepId, int order, String role, Long approverId, String approverName,
            String approverStatus, String status, String comment, LocalDateTime actedAt) {
    }

    /** 관리자 목록 조건. status가 TODO면 todoFor(관리자 본인) 차례인 문서만 */
    public record Filter(String status, String docType, String keyword, Long todoFor) {
    }

    private static final String SELECT_DOC = """
            SELECT d.doc_id, d.doc_type, d.ref_id, d.drafter_id, e.name AS drafter_name, dp.name AS drafter_dept,
                   jp.name AS drafter_pos, d.title, d.status, d.current_step, d.created_at, d.completed_at,
                   (SELECT COUNT(*) FROM APPROVAL_STEP t WHERE t.doc_id = d.doc_id) AS total_steps,
                   s.step_role AS now_role, s.approver_id AS now_approver_id, a.name AS now_approver_name
            FROM APPROVAL_DOC d
            JOIN EMPLOYEE e ON e.emp_id = d.drafter_id
            LEFT JOIN DEPARTMENT dp ON dp.dept_id = e.dept_id
            LEFT JOIN JOB_POSITION jp ON jp.pos_id = e.pos_id
            LEFT JOIN APPROVAL_STEP s ON s.doc_id = d.doc_id AND s.step_order = d.current_step
            LEFT JOIN EMPLOYEE a ON a.emp_id = s.approver_id
            """;

    /**
     * 지금 차례인 단계를 actor가 처리할 수 있는 조건. 자리표시자 4개: actorId, actorId, isAdmin(1/0), isAdmin(1/0)
     * - 상사 단계: 지정된 상사 본인. 상사가 재직 중이 아니면(휴직·퇴사) 관리자가 대신 처리
     * - 인사 단계: 관리자 누구나
     * - 기안자 본인은 어느 단계도 처리할 수 없다
     */
    private static final String ACTIONABLE = """
            d.status = 'IN_PROGRESS' AND s.status = 'PENDING' AND d.drafter_id <> ?
            AND ((s.step_role = 'MANAGER' AND (s.approver_id = ? OR (? = 1 AND a.status <> 'ACTIVE')))
              OR (s.step_role = 'HR' AND ? = 1))
            """;

    // ================================================================== 쓰기

    public long insertDoc(Connection conn, String docType, long refId, long drafterId, String title)
            throws SQLException {
        long id = DaoSupport.nextVal(conn, "SEQ_APPROVAL_DOC");
        String sql = "INSERT INTO APPROVAL_DOC (doc_id, doc_type, ref_id, drafter_id, title, status, current_step) "
                + "VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', 1)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, docType);
            ps.setLong(3, refId);
            ps.setLong(4, drafterId);
            ps.setString(5, title);
            ps.executeUpdate();
        }
        return id;
    }

    public void insertStep(Connection conn, long docId, int order, String role, Long approverId, String status)
            throws SQLException {
        String sql = "INSERT INTO APPROVAL_STEP (step_id, doc_id, step_order, step_role, approver_id, status) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, DaoSupport.nextVal(conn, "SEQ_APPROVAL_STEP"));
            ps.setLong(2, docId);
            ps.setInt(3, order);
            ps.setString(4, role);
            if (approverId == null) {
                ps.setNull(5, java.sql.Types.NUMERIC);
            } else {
                ps.setLong(5, approverId);
            }
            ps.setString(6, status);
            ps.executeUpdate();
        }
    }

    /** 단계 처리 결과를 남긴다. 처리한 사람을 approver_id에 기록한다 (인사 단계·대리 처리) */
    public void actStep(Connection conn, long stepId, String status, long actorId, String comment) throws SQLException {
        String sql = "UPDATE APPROVAL_STEP SET status = ?, approver_id = ?, comment_text = ?, acted_at = SYSDATE "
                + "WHERE step_id = ? AND status = 'PENDING'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, actorId);
            ps.setString(3, comment);
            ps.setLong(4, stepId);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("결재 단계 상태가 바뀌었습니다: " + stepId);
            }
        }
    }

    /** 다음 단계를 차례로 만든다 */
    public void moveTo(Connection conn, long docId, int order) throws SQLException {
        update(conn, "UPDATE APPROVAL_STEP SET status = 'PENDING' WHERE doc_id = ? AND step_order = ? AND status = 'WAITING'",
                docId, order);
        update(conn, "UPDATE APPROVAL_DOC SET current_step = ? WHERE doc_id = ?", order, docId);
    }

    /** 문서를 끝낸다. 아직 처리하지 않은 단계는 CANCELED */
    public void finish(Connection conn, long docId, String status) throws SQLException {
        update(conn, "UPDATE APPROVAL_STEP SET status = 'CANCELED' WHERE doc_id = ? AND status IN ('WAITING', 'PENDING')",
                docId);
        String sql = "UPDATE APPROVAL_DOC SET status = ?, completed_at = SYSDATE WHERE doc_id = ? AND status = 'IN_PROGRESS'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, docId);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("결재 문서 상태가 바뀌었습니다: " + docId);
            }
        }
    }

    // ================================================================== 잠금 · 단건

    /** 문서 행을 잠근다 (같은 문서를 동시에 처리하지 않도록). 없으면 false */
    public boolean lock(Connection conn, long docId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT doc_id FROM APPROVAL_DOC WHERE doc_id = ? FOR UPDATE")) {
            ps.setLong(1, docId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * 원본 문서(휴가 신청·경비) 행을 잠근다. 취소(원본 → 결재 문서)와 승인·반려가 같은 순서로 잠그도록
     * 결재 처리도 원본을 먼저 잠근다 (교착 방지). 테이블 이름은 문서 종류로 정해지는 상수다.
     */
    public void lockRef(Connection conn, String docType, long refId) throws SQLException {
        String sql = switch (docType) {
            case "LEAVE" -> "SELECT req_id FROM LEAVE_REQUEST WHERE req_id = ? FOR UPDATE";
            case "EXPENSE" -> "SELECT exp_id FROM EXPENSE WHERE exp_id = ? FOR UPDATE";
            default -> throw new IllegalArgumentException("알 수 없는 결재 문서 종류: " + docType);
        };
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, refId);
            ps.executeQuery().close();
        }
    }

    /** 원본 문서(휴가·경비)로 결재 문서를 찾아 잠근다. 없으면 null */
    public Long lockByRef(Connection conn, String docType, long refId) throws SQLException {
        String sql = "SELECT doc_id FROM APPROVAL_DOC WHERE doc_type = ? AND ref_id = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, docType);
            ps.setLong(2, refId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    public Long findIdByRef(Connection conn, String docType, long refId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT doc_id FROM APPROVAL_DOC WHERE doc_type = ? AND ref_id = ?")) {
            ps.setString(1, docType);
            ps.setLong(2, refId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    public Doc find(Connection conn, long docId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_DOC + " WHERE d.doc_id = ?")) {
            ps.setLong(1, docId);
            List<Doc> rows = docs(ps);
            return rows.isEmpty() ? null : rows.get(0);
        }
    }

    public List<Step> steps(Connection conn, long docId) throws SQLException {
        String sql = """
                SELECT s.step_id, s.step_order, s.step_role, s.approver_id, a.name AS approver_name,
                       a.status AS approver_status, s.status, s.comment_text, s.acted_at
                FROM APPROVAL_STEP s LEFT JOIN EMPLOYEE a ON a.emp_id = s.approver_id
                WHERE s.doc_id = ? ORDER BY s.step_order
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, docId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Step> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new Step(rs.getLong("step_id"), rs.getInt("step_order"), rs.getString("step_role"),
                            DaoSupport.getLongOrNull(rs, "approver_id"), rs.getString("approver_name"),
                            rs.getString("approver_status"), rs.getString("status"), rs.getString("comment_text"),
                            DaoSupport.getDateTime(rs, "acted_at")));
                }
                return rows;
            }
        }
    }

    /** 이 직원이 어느 단계의 결재자로 지정됐거나 처리한 적이 있는지 */
    public boolean isApprover(Connection conn, long docId, long empId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM APPROVAL_STEP WHERE doc_id = ? AND approver_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, docId);
            ps.setLong(2, empId);
            return count(ps) > 0;
        }
    }

    /** 재직 중인 관리자 (인사 단계 결재자). exceptId는 빼고 */
    public List<Long> activeAdmins(Connection conn, long exceptId) throws SQLException {
        String sql = "SELECT emp_id FROM EMPLOYEE WHERE role = 'ADMIN' AND status = 'ACTIVE' AND emp_id <> ? ORDER BY emp_id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, exceptId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
                return ids;
            }
        }
    }

    // ================================================================== 결재함 (사용자)

    public int countMine(Connection conn, long empId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM APPROVAL_DOC WHERE drafter_id = ?")) {
            ps.setLong(1, empId);
            return count(ps);
        }
    }

    public List<Doc> findMine(Connection conn, long empId, int offset, int limit) throws SQLException {
        String inner = SELECT_DOC + " WHERE d.drafter_id = ? ORDER BY d.doc_id DESC";
        try (PreparedStatement ps = conn.prepareStatement(paged(inner))) {
            ps.setLong(1, empId);
            ps.setInt(2, offset + limit);
            ps.setInt(3, offset);
            return docs(ps);
        }
    }

    public int countTodo(Connection conn, long actorId, boolean admin) throws SQLException {
        String sql = "SELECT COUNT(*) FROM (" + SELECT_DOC + " WHERE " + ACTIONABLE + ") x";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindActionable(ps, 1, actorId, admin);
            return count(ps);
        }
    }

    public List<Doc> findTodo(Connection conn, long actorId, boolean admin, int offset, int limit) throws SQLException {
        String inner = SELECT_DOC + " WHERE " + ACTIONABLE + " ORDER BY d.doc_id";
        try (PreparedStatement ps = conn.prepareStatement(paged(inner))) {
            int i = bindActionable(ps, 1, actorId, admin);
            ps.setInt(i++, offset + limit);
            ps.setInt(i, offset);
            return docs(ps);
        }
    }

    private static final String DONE = """
            EXISTS (SELECT 1 FROM APPROVAL_STEP t WHERE t.doc_id = d.doc_id AND t.approver_id = ?
                    AND t.status IN ('APPROVED', 'REJECTED'))
            """;

    public int countDone(Connection conn, long actorId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM APPROVAL_DOC d WHERE " + DONE)) {
            ps.setLong(1, actorId);
            return count(ps);
        }
    }

    public List<Doc> findDone(Connection conn, long actorId, int offset, int limit) throws SQLException {
        String inner = SELECT_DOC + " WHERE " + DONE + " ORDER BY d.doc_id DESC";
        try (PreparedStatement ps = conn.prepareStatement(paged(inner))) {
            ps.setLong(1, actorId);
            ps.setInt(2, offset + limit);
            ps.setInt(3, offset);
            return docs(ps);
        }
    }

    // ================================================================== 결재 관리 (관리자)

    public int countAll(Connection conn, Filter f) throws SQLException {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM (" + SELECT_DOC + where(f, params) + ") x";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return count(ps);
        }
    }

    public List<Doc> findAll(Connection conn, Filter f, int offset, int limit) throws SQLException {
        List<Object> params = new ArrayList<>();
        String inner = SELECT_DOC + where(f, params) + ("TODO".equals(f.status()) ? " ORDER BY d.doc_id" : " ORDER BY d.doc_id DESC");
        params.add(offset + limit);
        params.add(offset);
        try (PreparedStatement ps = conn.prepareStatement(paged(inner))) {
            bind(ps, params);
            return docs(ps);
        }
    }

    private static String where(Filter f, List<Object> params) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1");
        if ("TODO".equals(f.status())) {
            sb.append(" AND ").append(ACTIONABLE);
            params.add(f.todoFor());
            params.add(f.todoFor());
            params.add(1);
            params.add(1);
        } else if (f.status() != null) {
            sb.append(" AND d.status = ?");
            params.add(f.status());
        }
        if (f.docType() != null) {
            sb.append(" AND d.doc_type = ?");
            params.add(f.docType());
        }
        if (f.keyword() != null) {
            sb.append(" AND (e.name LIKE ? ESCAPE '\\' OR d.title LIKE ? ESCAPE '\\')");
            String like = DaoSupport.likeContains(f.keyword());
            params.add(like);
            params.add(like);
        }
        return sb.toString();
    }

    // ================================================================== 도우미

    private static String paged(String inner) {
        return "SELECT * FROM (SELECT ROWNUM rn, x.* FROM (" + inner + ") x WHERE ROWNUM <= ?) WHERE rn > ?";
    }

    private static int bindActionable(PreparedStatement ps, int start, long actorId, boolean admin) throws SQLException {
        ps.setLong(start, actorId);
        ps.setLong(start + 1, actorId);
        ps.setInt(start + 2, admin ? 1 : 0);
        ps.setInt(start + 3, admin ? 1 : 0);
        return start + 4;
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

    private static int count(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void update(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, List.of(params));
            ps.executeUpdate();
        }
    }

    private static List<Doc> docs(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<Doc> rows = new ArrayList<>();
            while (rs.next()) {
                long id = rs.getLong("doc_id");
                LocalDateTime created = DaoSupport.getDateTime(rs, "created_at");
                rows.add(new Doc(id, docNo(id, created), rs.getString("doc_type"), rs.getLong("ref_id"),
                        rs.getLong("drafter_id"), rs.getString("drafter_name"), rs.getString("drafter_dept"),
                        rs.getString("drafter_pos"), rs.getString("title"), rs.getString("status"),
                        rs.getInt("current_step"), rs.getInt("total_steps"), rs.getString("now_role"),
                        DaoSupport.getLongOrNull(rs, "now_approver_id"), rs.getString("now_approver_name"),
                        created, DaoSupport.getDateTime(rs, "completed_at")));
            }
            return rows;
        }
    }

    /** 화면에 보이는 문서번호: AP-연도-번호 */
    static String docNo(long docId, LocalDateTime createdAt) {
        int year = createdAt == null ? 0 : createdAt.getYear();
        return String.format("AP-%d-%04d", year, docId);
    }
}
