package com.hr.d.controller;

import com.hr.d.dto.Actor;
import com.hr.d.service.EvaluationService;
import com.hr.d.service.Problem;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@WebServlet({"/api/evaluations/*", "/api/admin/evaluations/*"})
public final class EvaluationServlet extends DomainServlet {
    @Override
    protected Reply route(HttpServletRequest req, Actor actor, String[] parts) throws IOException {
        boolean admin = adminRoute(req, actor);
        String method = req.getMethod();
        if (parts.length == 0) throw Problem.missing();
        var service = new EvaluationService();
        if (method.equals("GET")) return read(req, actor, admin, parts, service);

        if (!admin && method.equals("POST") && parts.length == 1 && parts[0].equals("submit")) {
            var body = Json.body(req);
            var scores = body.path("scores");
            if (!scores.isArray() || scores.isEmpty()) throw Problem.bad("평가 점수를 입력해 주세요.");
            var values = new LinkedHashMap<Long, Integer>();
            for (var score : scores) {
                long item = Input.id(score, "itemId");
                var value = score.path("score");
                if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > 5) {
                    throw Problem.bad("점수는 1~5 사이의 정수여야 합니다.");
                }
                if (values.put(item, value.intValue()) != null) throw Problem.bad("중복된 평가 항목입니다.");
            }
            return created(service.submit(actor.id(), Input.id(body, "periodId"), Input.id(body, "targetId"),
                    Input.text(body, "evalType", 10), Input.optionalText(body, "comment", 1000), values));
        }
        if (admin && ((method.equals("POST") && parts.length == 1 && parts[0].equals("periods"))
                || (method.equals("PUT") && parts.length == 2 && parts[0].equals("periods")))) {
            var body = Json.body(req);
            var period = service.savePeriod(parts.length == 1 ? null : Input.id(parts[1]),
                    Input.text(body, "name", 120), Input.date(body, "startDate"), Input.date(body, "endDate"));
            return parts.length == 1 ? created(period) : ok(period);
        }
        if (admin && method.equals("POST") && parts.length == 3 && parts[0].equals("periods")
                && parts[2].equals("status")) {
            return ok(service.changeStatus(Input.id(parts[1]), Input.text(Json.body(req), "status", 12)));
        }
        if (admin && ((method.equals("POST") && parts.length == 3 && parts[0].equals("periods") && parts[2].equals("items"))
                || (method.equals("PUT") && parts.length == 2 && parts[0].equals("items")))) {
            var body = Json.body(req);
            String name = Input.text(body, "name", 150);
            String description = Input.optionalText(body, "description", 1000);
            var weight = Input.decimal(body, "weight", new BigDecimal("0.01"), BigDecimal.valueOf(100), 2);
            long id = Input.id(parts[1]);
            if (parts.length == 3) return created(Map.of("itemId", service.addItem(id, name, description, weight)));
            service.updateItem(id, name, description, weight);
            return ok(Map.of("itemId", id));
        }
        if (admin && method.equals("DELETE") && parts.length == 2 && parts[0].equals("items")) {
            service.deleteItem(Input.id(parts[1]));
            return ok(Map.of("deleted", true));
        }
        throw Problem.missing();
    }

    private Reply read(HttpServletRequest req, Actor actor, boolean admin, String[] parts, EvaluationService service) {
        if (parts[0].equals("periods")) {
            if (parts.length == 1) return ok(service.periods(Input.page(req), admin));
            if (parts.length == 2) return ok(service.period(Input.id(parts[1]), admin));
            if (parts.length == 3 && parts[2].equals("items")) {
                return ok(service.items(Input.id(parts[1]), Input.page(req), admin));
            }
        }
        if (parts.length == 2 && parts[0].equals("results")) return ok(service.result(actor, admin, Input.id(parts[1])));
        if (parts.length == 1) {
            if (admin && parts[0].equals("status")) {
                return ok(service.status(Input.id(req.getParameter("periodId")), Input.page(req)));
            }
            if (!admin) {
                return switch (parts[0]) {
                    case "mine" -> ok(service.mine(actor.id(), Input.id(req.getParameter("periodId")), Input.page(req)));
                    case "reviews" -> ok(service.reviews(actor.id(), Input.id(req.getParameter("periodId")), Input.page(req)));
                    case "team" -> ok(service.team(actor.id(), Input.page(req)));
                    default -> throw Problem.missing();
                };
            }
        }
        throw Problem.missing();
    }
}
