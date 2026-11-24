package com.hr.controller.admin;

import java.io.IOException;

import com.hr.controller.BaseServlet;
import com.hr.dao.AuditLogDAO;
import com.hr.service.AuditService;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 감사 로그 조회 (REQ-21) — ADMIN 전용
 *
 * GET /api/admin/audit-logs?table=&action=&targetId=&from=&to=&page=&size=
 */
@WebServlet("/api/admin/audit-logs")
public class AuditAdminServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    private final transient AuditService service = new AuditService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String table = text(req.getParameter("table"));
        String action = text(req.getParameter("action"));
        if (table != null && !table.matches("[A-Z_]{1,30}")) {
            table = null;
        }
        if (action != null && !action.matches("[A-Z_]{1,20}")) {
            action = null;
        }
        AuditLogDAO.Filter filter = new AuditLogDAO.Filter(table, action,
                optionalId(req.getParameter("targetId"), "대상 ID"),
                date(req.getParameter("from"), "시작일"), date(req.getParameter("to"), "종료일"));
        ok(resp, service.search(filter, page(req), size(req)));
    }
}
