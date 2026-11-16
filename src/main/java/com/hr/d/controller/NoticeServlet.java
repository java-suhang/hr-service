package com.hr.d.controller;

import com.hr.d.dto.Actor;
import com.hr.d.integration.NoticeNotifier;
import com.hr.d.service.NoticeService;
import com.hr.d.service.Problem;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;

@WebServlet({"/api/notices/*", "/api/admin/notices/*"})
public final class NoticeServlet extends DomainServlet {
    @Override
    protected Reply route(HttpServletRequest req, Actor actor, String[] parts) throws IOException {
        boolean admin = adminRoute(req, actor);
        String method = req.getMethod();
        if (parts.length == 0 && method.equals("GET")) {
            String search = req.getParameter("q");
            if (search != null && search.length() > 200) throw Problem.bad("검색어는 200자 이하여야 합니다.");
            return ok(new NoticeService().list(Input.page(req), search == null || search.isBlank() ? null : search.strip()));
        }
        if (parts.length == 1 && method.equals("GET")) {
            return ok(new NoticeService().find(Input.id(parts[0])));
        }
        if (admin && parts.length == 1 && method.equals("DELETE")) {
            new NoticeService().delete(Input.id(parts[0]));
            return ok(Map.of("deleted", true));
        }
        if (admin && ((parts.length == 0 && method.equals("POST")) || (parts.length == 1 && method.equals("PUT")))) {
            var body = Json.body(req);
            String title = Input.text(body, "title", 200);
            String content = Input.text(body, "content", 50_000);
            boolean pinned = Input.flag(body, "isPinned");
            var service = new NoticeService();
            return parts.length == 0
                    ? created(service.create(actor.id(), title, content, pinned, integration(NoticeNotifier.class)))
                    : ok(service.update(Input.id(parts[0]), title, content, pinned));
        }
        throw Problem.missing();
    }
}
