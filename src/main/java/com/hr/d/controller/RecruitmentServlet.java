package com.hr.d.controller;

import com.hr.d.dto.Actor;
import com.hr.d.integration.EmployeeCreator;
import com.hr.d.service.Problem;
import com.hr.d.service.RecruitmentService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;

@WebServlet({"/api/recruitment/*", "/api/admin/recruitment/*"})
public final class RecruitmentServlet extends DomainServlet {
    @Override
    protected Reply route(HttpServletRequest req, Actor actor, String[] parts) throws IOException {
        boolean admin = adminRoute(req, actor);
        if (parts.length == 0) throw Problem.missing();
        String method = req.getMethod();
        var service = new RecruitmentService();
        if (method.equals("GET")) return read(req, actor, admin, parts, service);
        if (method.equals("PUT") && parts.length == 2 && parts[0].equals("interviews")) {
            var body = Json.body(req);
            int score = Input.decimal(body, "score", BigDecimal.ZERO, BigDecimal.valueOf(100), 0).intValueExact();
            return ok(service.score(actor, admin, Input.id(parts[1]), score, Input.optionalText(body, "feedback", 1000)));
        }
        if (!admin) throw Problem.missing();
        if (method.equals("PUT") && parts.length == 3 && parts[0].equals("interviews") && parts[2].equals("schedule")) {
            var body = Json.body(req);
            return ok(service.reschedule(Input.id(parts[1]), Input.id(body, "interviewerId"),
                    Input.dateTime(body, "scheduledAt")));
        }
        if (!method.equals("POST")) throw Problem.missing();
        if (parts.length == 1 && parts[0].equals("postings")) {
            var body = Json.body(req);
            long id = service.createPosting(Input.text(body, "title", 200), Input.id(body, "deptId"),
                    Input.id(body, "posId"), Input.optionalText(body, "description", 50_000),
                    Input.date(body, "openDate"), Input.date(body, "closeDate"));
            return created(Map.of("postingId", id));
        }
        if (parts.length == 1 && parts[0].equals("applicants")) {
            var body = Json.body(req);
            String email = Input.optionalText(body, "email", 200);
            if (email != null && !email.matches("[^@\\s]+@[^@\\s]+")) throw Problem.bad("이메일 형식을 확인해 주세요.");
            long id = service.addApplicant(Input.id(body, "postingId"), Input.text(body, "name", 100), email,
                    Input.optionalText(body, "phone", 30));
            return created(Map.of("applicantId", id));
        }
        if (parts.length == 3 && parts[0].equals("postings") && parts[2].equals("close")) {
            return ok(service.closePosting(Input.id(parts[1])));
        }
        if (parts.length == 3 && parts[0].equals("applicants")) {
            long id = Input.id(parts[1]);
            return switch (parts[2]) {
                case "stage" -> ok(service.changeStage(id, Input.text(Json.body(req), "stage", 12)));
                case "interviews" -> {
                    var body = Json.body(req);
                    yield created(Map.of("interviewId", service.addInterview(id, Input.id(body, "interviewerId"),
                            Input.dateTime(body, "scheduledAt"))));
                }
                case "hire" -> hire(req, service, id);
                default -> throw Problem.missing();
            };
        }
        throw Problem.missing();
    }

    private Reply hire(HttpServletRequest req, RecruitmentService service, long id) throws IOException {
        var body = Json.body(req);
        String password = Input.text(body, "initialPassword", 128);
        if (password.length() < 12 || !password.equals(body.get("initialPassword").textValue())) {
            throw Problem.bad("임시 비밀번호는 앞뒤 공백 없이 12~128자로 입력해 주세요.");
        }
        var days = body.has("annualDays")
                ? Input.decimal(body, "annualDays", BigDecimal.ZERO, BigDecimal.valueOf(366), 1) : BigDecimal.valueOf(15);
        long employee = service.hire(id, Input.text(body, "empNo", 30), Input.text(body, "loginId", 100), password,
                Input.optionalId(body, "managerId"), Input.date(body, "hireDate"), days, integration(EmployeeCreator.class));
        return created(Map.of("applicantId", id, "empId", employee, "stage", "HIRED"));
    }

    private Reply read(HttpServletRequest req, Actor actor, boolean admin, String[] parts, RecruitmentService service) {
        if (!admin) {
            if (parts.length == 1 && parts[0].equals("interviews")) {
                return ok(service.interviews(actor.id(), false, Input.page(req)));
            }
            throw Problem.missing();
        }
        if (parts.length == 2 && parts[0].equals("lookups")) return ok(service.lookup(parts[1], Input.page(req)));
        if (parts[0].equals("postings")) {
            if (parts.length == 1) return ok(service.postings(Input.page(req)));
            if (parts.length == 2) return ok(service.posting(Input.id(parts[1])));
        }
        if (parts[0].equals("applicants")) {
            if (parts.length == 1) {
                String posting = req.getParameter("postingId");
                return ok(service.applicants(posting == null ? null : Input.id(posting), Input.page(req)));
            }
            if (parts.length == 2) return ok(service.applicant(Input.id(parts[1])));
            if (parts.length == 3 && parts[2].equals("interviews")) {
                return ok(service.interviews(Input.id(parts[1]), true, Input.page(req)));
            }
        }
        throw Problem.missing();
    }
}
