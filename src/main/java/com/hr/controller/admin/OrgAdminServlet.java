package com.hr.controller.admin;

import java.io.IOException;
import java.util.Map;

import com.hr.controller.BaseServlet;
import com.hr.service.OrgService;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 부서·직급 관리 (REQ-06) — ADMIN 전용
 *
 * GET    /api/admin/departments          → [{deptId, name, employeeCount, totalCount}]
 * POST   /api/admin/departments          {name}
 * PUT    /api/admin/departments/{id}     {name}
 * DELETE /api/admin/departments/{id}
 * GET    /api/admin/positions            → [{posId, name, level, employeeCount, totalCount}]
 * POST   /api/admin/positions            {name, level}
 * PUT    /api/admin/positions/{id}       {name, level}
 * DELETE /api/admin/positions/{id}
 */
@WebServlet({ "/api/admin/departments/*", "/api/admin/positions/*" })
public class OrgAdminServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    private final transient OrgService service = new OrgService();

    private static boolean dept(HttpServletRequest req) {
        return req.getServletPath().equals("/api/admin/departments");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (pathParts(req).length != 0) {
            notFound();
        }
        ok(resp, dept(req) ? service.departments() : service.positions());
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (pathParts(req).length != 0) {
            notFound();
        }
        long actor = loginUser(req).getEmpId();
        Map<String, Object> body = readJson(req);
        created(resp, dept(req) ? service.createDepartment(body, actor) : service.createPosition(body, actor));
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long id = id(req);
        long actor = loginUser(req).getEmpId();
        Map<String, Object> body = readJson(req);
        ok(resp, dept(req) ? service.updateDepartment(id, body, actor) : service.updatePosition(id, body, actor));
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long id = id(req);
        long actor = loginUser(req).getEmpId();
        if (dept(req)) {
            service.deleteDepartment(id, actor);
        } else {
            service.deletePosition(id, actor);
        }
        ok(resp, Map.of("message", "삭제했습니다"));
    }

    private long id(HttpServletRequest req) {
        String[] p = pathParts(req);
        if (p.length != 1) {
            notFound();
        }
        return requireId(p[0], "ID");
    }
}
