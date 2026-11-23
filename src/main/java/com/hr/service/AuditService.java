package com.hr.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.hr.dao.AuditLogDAO;
import com.hr.util.DBUtil;
import com.hr.util.JsonUtil;

/**
 * 감사 로그 (REQ-21). 누가 언제 어떤 데이터를 바꿨는지 변경 전·후 값을 JSON으로 남긴다.
 *
 * 다른 도메인도 같은 트랜잭션 안에서 부른다:
 *   new AuditService().log(conn, actorId, AuditService.UPDATE, "ATTENDANCE", attId, before, after);
 * 비밀번호·해시 같은 민감한 값은 before/after에 넣지 않는다.
 */
public class AuditService {

    public static final String CREATE = "CREATE";
    public static final String UPDATE = "UPDATE";
    public static final String DELETE = "DELETE";
    public static final String RESIGN = "RESIGN";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    public static final String APPROVE = "APPROVE";
    public static final String REJECT = "REJECT";

    private static final int MAX_JSON = 30_000;

    private final AuditLogDAO dao = new AuditLogDAO();

    /** 호출하는 쪽 트랜잭션에 합류한다. before/after는 Map·DTO·record (null 가능) */
    public void log(Connection conn, Long actorId, String action, String table, Long targetId,
                    Object before, Object after) throws SQLException {
        dao.insert(conn, actorId, action, table, targetId, json(before), json(after));
    }

    public Map<String, Object> search(AuditLogDAO.Filter filter, int page, int size) {
        return DBUtil.read(conn -> {
            int total = dao.count(conn, filter);
            List<AuditLogDAO.Entry> items = dao.search(conn, filter, (page - 1) * size, size);
            return AttendanceService.pageResult(items, total, page, size);
        });
    }

    private static String json(Object value) {
        if (value == null) {
            return null;
        }
        String s = JsonUtil.toJson(value);
        return s.length() > MAX_JSON ? s.substring(0, MAX_JSON) : s;
    }
}
