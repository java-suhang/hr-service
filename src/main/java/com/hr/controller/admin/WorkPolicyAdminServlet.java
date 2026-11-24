package com.hr.controller.admin;

import java.io.IOException;
import java.util.Map;

import com.hr.controller.BaseServlet;
import com.hr.service.AttendanceService;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 근무 규칙 API (REQ-16)
 *  GET /api/admin/work-policy   현재 규칙
 *  PUT /api/admin/work-policy   수정 {startTime, endTime, lateGraceMin, weeklyLimitHours}
 */
@WebServlet("/api/admin/work-policy")
public class WorkPolicyAdminServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    private final transient AttendanceService attendanceService = new AttendanceService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ok(resp, attendanceService.getPolicy());
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = readJson(req);
        ok(resp, attendanceService.updatePolicy(
                time(body.get("startTime"), "출근 시각"),
                time(body.get("endTime"), "퇴근 시각"),
                intValue(body.get("lateGraceMin"), "지각 유예", -1),
                intValue(body.get("weeklyLimitHours"), "주 근무 한도", -1),
                loginUser(req).getEmpId()));
    }
}
