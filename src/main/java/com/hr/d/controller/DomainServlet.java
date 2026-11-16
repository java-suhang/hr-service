package com.hr.d.controller;

import com.hr.controller.BaseServlet;
import com.hr.d.dto.Actor;
import com.hr.d.service.Problem;
import com.hr.util.ApiException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

abstract class DomainServlet extends BaseServlet {
    record Reply(int status, Object payload) {}

    protected abstract Reply route(HttpServletRequest req, Actor actor, String[] parts) throws IOException;

    @Override
    protected final void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            var user = loginUser(req);
            var actor = new Actor(user.getEmpId(), user.isAdmin());
            Reply reply = route(req, actor, pathParts(req));
            sendJson(resp, reply.status(), reply.payload());
        } catch (ApiException problem) {
            sendJson(resp, problem.getStatus(), Map.of("message", problem.getMessage()));
        } catch (RuntimeException ex) {
            if (ex instanceof IllegalStateException && ex.getCause() instanceof SQLException cause) {
                sqlError(resp, cause);
            } else {
                log("D domain error: " + ex.getClass().getName());
                sendJson(resp, 500, Map.of("message", "요청을 처리하지 못했습니다."));
            }
        }
    }

    private void sqlError(HttpServletResponse resp, SQLException ex) throws IOException {
        String state = ex.getSQLState();
        if (ex.getErrorCode() == 1 || "23505".equals(state)) {
            sendJson(resp, 409, Map.of("message", "이미 존재하는 데이터입니다."));
        } else if (ex.getErrorCode() == 2291 || "23506".equals(state)) {
            sendJson(resp, 409, Map.of("message", "연결할 대상이 없거나 변경되었습니다."));
        } else if (state != null && state.startsWith("08")) {
            sendJson(resp, 503, Map.of("message", "데이터베이스에 연결할 수 없습니다."));
        } else {
            log("D domain SQL error: state=" + state + ", code=" + ex.getErrorCode());
            sendJson(resp, 500, Map.of("message", "데이터베이스 요청을 처리하지 못했습니다."));
        }
    }

    protected final boolean adminRoute(HttpServletRequest req, Actor actor) {
        boolean admin = req.getServletPath().startsWith("/api/admin/");
        if (admin && !actor.admin()) throw Problem.forbidden("관리자 권한이 필요합니다.");
        return admin;
    }

    protected final <T> T integration(Class<T> type) {
        Object value = getServletContext().getAttribute(type.getName());
        return type.isInstance(value) ? type.cast(value) : null;
    }

    protected static Reply ok(Object payload) { return new Reply(200, payload); }
    protected static Reply created(Object payload) { return new Reply(201, payload); }
}
