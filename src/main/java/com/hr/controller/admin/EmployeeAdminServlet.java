package com.hr.controller.admin;

import java.io.IOException;
import java.util.Map;

import com.hr.controller.BaseServlet;
import com.hr.dao.EmployeeDAO;
import com.hr.service.EmployeeService;
import com.hr.util.ApiException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 직원 관리 (REQ-04, REQ-05, REQ-03 초기화) — ADMIN 전용 (AuthFilter)
 *
 * GET  /api/admin/employees?keyword=&deptId=&posId=&status=&page=&size=  → {items, total, page, size}
 * GET  /api/admin/employees/options        → {departments, positions, employees}
 * GET  /api/admin/employees/{id}
 * POST /api/admin/employees                 {empNo, loginId, password, name, email, phone, deptId, posId,
 *                                            managerId, role, hireDate, status, annualDays} → 직원 + 올해 연차
 * PUT  /api/admin/employees/{id}            (loginId·password 제외 같은 필드)
 * PUT  /api/admin/employees/{id}/manager    {managerId} 상사(결재선 1단계)만 변경, null이면 상사 없음
 * POST /api/admin/employees/{id}/resign     퇴사 처리
 * POST /api/admin/employees/{id}/password   {password} 비밀번호 초기화
 */
@WebServlet("/api/admin/employees/*")
public class EmployeeAdminServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    private final transient EmployeeService service = new EmployeeService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = pathParts(req);
        if (p.length == 0) {
            String status = text(req.getParameter("status"));
            if (status != null && !status.matches("ACTIVE|LEAVE|RESIGNED")) {
                throw ApiException.badRequest("상태 값이 올바르지 않습니다");
            }
            EmployeeDAO.Filter filter = new EmployeeDAO.Filter(text(req.getParameter("keyword")),
                    optionalId(req.getParameter("deptId"), "부서"), optionalId(req.getParameter("posId"), "직급"), status);
            ok(resp, service.search(filter, page(req), size(req)));
        } else if (p.length == 1 && p[0].equals("options")) {
            ok(resp, service.options());
        } else if (p.length == 1) {
            ok(resp, service.get(requireId(p[0], "직원 ID")));
        } else {
            notFound();
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = pathParts(req);
        long actor = loginUser(req).getEmpId();
        if (p.length == 0) {
            created(resp, service.create(readJson(req), actor));
        } else if (p.length == 2 && p[1].equals("resign")) {
            ok(resp, service.resign(requireId(p[0], "직원 ID"), actor));
        } else if (p.length == 2 && p[1].equals("password")) {
            Map<String, Object> body = readJson(req);
            service.resetPassword(requireId(p[0], "직원 ID"), raw(body.get("password")), actor);
            ok(resp, Map.of("message", "비밀번호를 초기화했습니다"));
        } else {
            notFound();
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = pathParts(req);
        long actor = loginUser(req).getEmpId();
        if (p.length == 1) {
            ok(resp, service.update(requireId(p[0], "직원 ID"), readJson(req), actor));
        } else if (p.length == 2 && p[1].equals("manager")) {
            Long managerId = optionalId(readJson(req).get("managerId"), "상사");
            ok(resp, service.changeManager(requireId(p[0], "직원 ID"), managerId, actor));
        } else {
            notFound();
        }
    }
}
