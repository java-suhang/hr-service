package com.hr.controller;

import java.io.IOException;
import java.time.YearMonth;

import com.hr.service.AttendanceService;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 직원 근태 API
 *  GET  /api/attendance?month=YYYY-MM   내 월간 근태 (기본: 이번 달)
 *  GET  /api/attendance/today           오늘 근태 + 이번 주 누적
 *  POST /api/attendance/check-in        출근
 *  POST /api/attendance/check-out       퇴근
 */
@WebServlet("/api/attendance/*")
public class AttendanceServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    private final transient AttendanceService attendanceService = new AttendanceService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long empId = loginUser(req).getEmpId();
        String[] parts = pathParts(req);

        if (parts.length == 0) {
            YearMonth month = yearMonth(req.getParameter("month"), "month");
            if (month == null) {
                month = YearMonth.from(attendanceService.today());
            }
            ok(resp, attendanceService.month(empId, month));
        } else if (parts.length == 1 && parts[0].equals("today")) {
            ok(resp, attendanceService.todayStatus(empId));
        } else {
            notFound();
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long empId = loginUser(req).getEmpId();
        String[] parts = pathParts(req);

        if (parts.length == 1 && parts[0].equals("check-in")) {
            ok(resp, attendanceService.checkIn(empId));
        } else if (parts.length == 1 && parts[0].equals("check-out")) {
            ok(resp, attendanceService.checkOut(empId));
        } else {
            notFound();
        }
    }
}
