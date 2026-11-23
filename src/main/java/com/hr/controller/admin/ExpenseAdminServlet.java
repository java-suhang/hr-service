package com.hr.controller.admin;

import java.io.IOException;

import com.hr.controller.BaseServlet;
import com.hr.dao.ExpenseDAO;
import com.hr.service.ExpenseService;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 경비 지급 (REQ-24) — ADMIN 전용
 *
 * GET  /api/admin/expenses?status=APPROVED|PAID|PENDING|REJECTED|CANCELED|ALL&category=&keyword=&from=&to=&page=&size=
 *      status 기본값 APPROVED(지급 대기) → {items, total, page, size, totalAmount}
 * POST /api/admin/expenses/{id}/pay   지급 완료 처리
 */
@WebServlet("/api/admin/expenses/*")
public class ExpenseAdminServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    private final transient ExpenseService service = new ExpenseService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (pathParts(req).length != 0) {
            notFound();
        }
        String status = text(req.getParameter("status"));
        if (status == null) {
            status = ExpenseService.APPROVED;
        } else if (status.equals("ALL")) {
            status = null;
        }
        ExpenseDAO.Filter filter = new ExpenseDAO.Filter(status, text(req.getParameter("category")),
                text(req.getParameter("keyword")), date(req.getParameter("from"), "시작일"),
                date(req.getParameter("to"), "종료일"));
        ok(resp, service.adminList(filter, page(req), size(req)));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = pathParts(req);
        if (p.length == 2 && p[1].equals("pay")) {
            ok(resp, service.pay(requireId(p[0], "경비 번호"), loginUser(req).getEmpId()));
        } else {
            notFound();
        }
    }
}
