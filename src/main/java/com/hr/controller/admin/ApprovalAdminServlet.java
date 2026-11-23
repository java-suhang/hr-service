package com.hr.controller.admin;

import java.io.IOException;

import com.hr.controller.BaseServlet;
import com.hr.service.ApprovalService;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 결재 관리 (REQ-17) — ADMIN 전용 (AuthFilter). 승인·반려는 /api/approvals/{id}/approve|reject를 같이 쓴다.
 *
 * GET /api/admin/approvals?status=TODO|IN_PROGRESS|APPROVED|REJECTED|CANCELED|ALL&type=LEAVE|EXPENSE&keyword=&page=&size=
 *     status 기본값 TODO(내가 처리할 문서) → {items, total, page, size, counts}
 */
@WebServlet("/api/admin/approvals/*")
public class ApprovalAdminServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    private final transient ApprovalService service = new ApprovalService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (pathParts(req).length != 0) {
            notFound();
        }
        String status = text(req.getParameter("status"));
        if (status == null) {
            status = "TODO";
        } else if (status.equals("ALL")) {
            status = null;
        }
        ok(resp, service.adminList(loginUser(req).getEmpId(), status, text(req.getParameter("type")),
                text(req.getParameter("keyword")), page(req), size(req)));
    }
}
