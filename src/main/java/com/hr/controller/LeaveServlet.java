package com.hr.controller;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

import com.hr.service.LeaveService;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 직원 휴가 API
 *  GET  /api/leaves?year=&page=&size=                       내 신청 내역 + 잔여 연차
 *  GET  /api/leaves/preview?leaveType=&startDate=&endDate=  신청 전 일수·잔여 미리보기
 *  POST /api/leaves                                         신청 {leaveType, startDate, endDate, reason}
 *  POST /api/leaves/{id}/cancel                             취소 (결재 진행 중일 때만)
 */
@WebServlet("/api/leaves/*")
public class LeaveServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    private final transient LeaveService leaveService = new LeaveService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long empId = loginUser(req).getEmpId();
        String[] parts = pathParts(req);

        if (parts.length == 0) {
            int year = intValue(req.getParameter("year"), "year", LocalDate.now().getYear());
            ok(resp, leaveService.myRequests(empId, year, page(req), size(req)));
        } else if (parts.length == 1 && parts[0].equals("preview")) {
            ok(resp, leaveService.preview(empId,
                    requireText(req.getParameter("leaveType"), "휴가 종류"),
                    requireDate(req.getParameter("startDate"), "시작일"),
                    requireDate(req.getParameter("endDate"), "종료일")));
        } else {
            notFound();
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long empId = loginUser(req).getEmpId();
        String[] parts = pathParts(req);

        if (parts.length == 0) {
            Map<String, Object> body = readJson(req);
            created(resp, leaveService.apply(empId,
                    requireText(body.get("leaveType"), "휴가 종류"),
                    requireDate(body.get("startDate"), "시작일"),
                    requireDate(body.get("endDate"), "종료일"),
                    text(body.get("reason"))));
        } else if (parts.length == 2 && parts[1].equals("cancel")) {
            long reqId = requireId(parts[0], "신청 번호");
            ok(resp, leaveService.cancel(empId, reqId));
        } else {
            notFound();
        }
    }
}
