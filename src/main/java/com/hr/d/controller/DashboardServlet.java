package com.hr.d.controller;

import com.hr.d.dto.Actor;
import com.hr.d.filter.DomainRequestFilter;
import com.hr.d.integration.EmployeeCreator;
import com.hr.d.service.DashboardService;
import com.hr.d.service.Problem;
import com.hr.service.AttendanceService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@WebServlet({"/api/dashboard/*", "/api/admin/dashboard/*"})
public final class DashboardServlet extends DomainServlet {
    private final AttendanceService attendance = new AttendanceService();

    @Override
    protected Reply route(HttpServletRequest req, Actor actor, String[] parts) {
        boolean admin = adminRoute(req, actor);
        if (!req.getMethod().equals("GET")) throw Problem.missing();
        if (!admin && parts.length == 1 && parts[0].equals("session")) {
            String token = DomainRequestFilter.token(req.getSession(false));
            return ok(Map.of("empId", actor.id(), "admin", actor.admin(), "csrf", token,
                    "employeeCreationEnabled", actor.admin() && integration(EmployeeCreator.class) != null));
        }
        if (parts.length != 0) throw Problem.missing();
        var service = new DashboardService();
        var today = attendance.today();
        return ok(admin ? service.admin(today) : service.user(actor.id(), today));
    }
}
