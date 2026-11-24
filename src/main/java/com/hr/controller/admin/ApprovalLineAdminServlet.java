package com.hr.controller.admin;

import java.io.IOException;

import com.hr.controller.BaseServlet;
import com.hr.service.ApprovalService;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 결재선 설정 (REQ-17) — ADMIN 전용. 결재선은 상사 → 인사 관리자로 자동으로 정해지므로
 * 여기서는 재직자별 결재선을 보여 주고, 1단계(상사)는 PUT /api/admin/employees/{id}/manager로 바꾼다.
 *
 * GET /api/admin/approval-lines?keyword=&deptId=&page=&size= → {items, total, page, size, admins}
 */
@WebServlet("/api/admin/approval-lines/*")
public class ApprovalLineAdminServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    private final transient ApprovalService service = new ApprovalService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (pathParts(req).length != 0) {
            notFound();
        }
        ok(resp, service.lines(text(req.getParameter("keyword")), optionalId(req.getParameter("deptId"), "부서"),
                page(req), size(req)));
    }
}
