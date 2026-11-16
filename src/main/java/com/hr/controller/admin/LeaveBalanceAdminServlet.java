package com.hr.controller.admin;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

import com.hr.controller.BaseServlet;
import com.hr.service.LeaveService;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 관리자 연차 API (REQ-12)
 *  GET  /api/admin/leave-balances?year=&deptId=&q=&page=&size=   재직자 연차 현황
 *  PUT  /api/admin/leave-balances          부여 일수 조정 {empId, year, totalDays}
 *  POST /api/admin/leave-balances/grant    그해 연차가 없는 재직자 일괄 부여 {year, days}
 */
@WebServlet("/api/admin/leave-balances/*")
public class LeaveBalanceAdminServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    private final transient LeaveService leaveService = new LeaveService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (pathParts(req).length != 0) {
            notFound();
        }
        ok(resp, leaveService.listBalances(
                intValue(req.getParameter("year"), "year", LocalDate.now().getYear()),
                optionalId(req.getParameter("deptId"), "부서"),
                text(req.getParameter("q")),
                page(req), size(req)));
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (pathParts(req).length != 0) {
            notFound();
        }
        Map<String, Object> body = readJson(req);
        ok(resp, leaveService.adjustBalance(
                requireId(body.get("empId"), "직원"),
                intValue(body.get("year"), "연도", LocalDate.now().getYear()),
                requireDecimal(body.get("totalDays"), "부여 일수")));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = pathParts(req);
        if (parts.length != 1 || !parts[0].equals("grant")) {
            notFound();
        }
        Map<String, Object> body = readJson(req);
        int granted = leaveService.grantAll(
                intValue(body.get("year"), "연도", LocalDate.now().getYear()),
                requireDecimal(body.get("days"), "부여 일수"));
        ok(resp, Map.of("granted", granted));
    }
}
