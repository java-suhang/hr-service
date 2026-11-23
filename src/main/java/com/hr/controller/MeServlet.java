package com.hr.controller;

import java.io.IOException;

import com.hr.service.EmployeeService;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 내 정보 · 조직도 (REQ-07), 증명서 발급용 정보 (REQ-22)
 *
 * GET /api/me    → 내 직원 정보 (사번, 부서, 직급, 상사, 입사일, 연락처…)
 * GET /api/org   → 부서별 직원 (연락처 가운데는 가림)
 */
@WebServlet({ "/api/me", "/api/org" })
public class MeServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    private final transient EmployeeService employeeService = new EmployeeService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long empId = loginUser(req).getEmpId();
        if (req.getServletPath().equals("/api/me")) {
            ok(resp, employeeService.get(empId));
        } else {
            ok(resp, employeeService.orgChart());
        }
    }
}
