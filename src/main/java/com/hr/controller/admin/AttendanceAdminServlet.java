package com.hr.controller.admin;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hr.controller.BaseServlet;
import com.hr.dao.AttendanceDAO.DailyFilter;
import com.hr.dto.AttendanceDailyRow;
import com.hr.service.AttendanceService;
import com.hr.util.ApiException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 관리자 근태 API (A04)
 *  GET /api/admin/attendance?date=&deptId=&status=&q=&page=&size=   일자별 전체 근태
 *  GET /api/admin/attendance/export?date=&deptId=&status=&q=        같은 조건으로 CSV 내려받기
 *  PUT /api/admin/attendance   근태 수정 {empId, workDate, checkIn, checkOut, status}
 *                              (기록이 없으면 만든다. status를 비우면 자동 판정)
 */
@WebServlet("/api/admin/attendance/*")
public class AttendanceAdminServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    /** 상태 필터 값: 근태 상태 + NONE(미출근) */
    private static final Set<String> FILTER_STATUSES = Set.of("NORMAL", "LATE", "EARLY_LEAVE", "ABSENT", "LEAVE",
            "NONE");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final transient AttendanceService attendanceService = new AttendanceService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = pathParts(req);
        DailyFilter filter = filter(req);

        if (parts.length == 0) {
            ok(resp, attendanceService.daily(filter, page(req), size(req)));
        } else if (parts.length == 1 && parts[0].equals("export")) {
            writeCsv(resp, filter, attendanceService.dailyForExport(filter));
        } else {
            notFound();
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (pathParts(req).length != 0) {
            notFound();
        }
        Map<String, Object> body = readJson(req);
        ok(resp, attendanceService.adminSave(
                requireId(body.get("empId"), "직원"),
                requireDate(body.get("workDate"), "근무일"),
                time(body.get("checkIn"), "출근 시각"),
                time(body.get("checkOut"), "퇴근 시각"),
                text(body.get("status"))));
    }

    private DailyFilter filter(HttpServletRequest req) {
        LocalDate date = date(req.getParameter("date"), "date");
        String status = text(req.getParameter("status"));
        if (status != null && !FILTER_STATUSES.contains(status)) {
            throw ApiException.badRequest("상태 값이 올바르지 않습니다");
        }
        return new DailyFilter(
                date != null ? date : attendanceService.today(),
                optionalId(req.getParameter("deptId"), "부서"),
                status,
                text(req.getParameter("q")));
    }

    /** 엑셀에서 바로 열리도록 UTF-8 BOM을 붙인 CSV */
    private void writeCsv(HttpServletResponse resp, DailyFilter filter, List<AttendanceDailyRow> rows)
            throws IOException {
        String fileName = URLEncoder.encode("근태_" + filter.date() + ".csv", StandardCharsets.UTF_8).replace("+", "%20");
        resp.setContentType("text/csv; charset=UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + fileName);
        PrintWriter out = resp.getWriter();
        out.write('﻿');
        out.println("사번,이름,부서,출근,퇴근,근무시간(분),주간 누적(분),상태,주간 경고");
        for (AttendanceDailyRow r : rows) {
            out.println(String.join(",",
                    csv(r.getEmpNo()),
                    csv(r.getEmpName()),
                    csv(r.getDeptName()),
                    csv(hhmm(r.getCheckIn())),
                    csv(hhmm(r.getCheckOut())),
                    csv(r.getWorkMinutes() == null ? "" : r.getWorkMinutes().toString()),
                    csv(String.valueOf(r.getWeeklyMinutes())),
                    csv(r.getStatus() == null ? "NONE" : r.getStatus()),
                    csv(r.getWeeklyLevel())));
        }
        out.flush();
    }

    private static String hhmm(LocalDateTime t) {
        return t == null ? "" : t.format(HH_MM);
    }

    /** 쉼표·따옴표·줄바꿈을 감싸고, 수식으로 해석될 수 있는 값(=, +, -, @ 시작)은 앞에 '를 붙인다. */
    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
