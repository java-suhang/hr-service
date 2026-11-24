package com.hr.controller;

import java.io.IOException;
import java.util.Map;

import com.hr.service.NotificationService;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 내 알림 (REQ-20)
 *
 * GET  /api/notifications?page=&size=   → {items, total, page, size, unread}
 * GET  /api/notifications/unread-count  → {count}
 * POST /api/notifications/{id}/read
 * POST /api/notifications/read-all      → {updated}
 */
@WebServlet("/api/notifications/*")
public class NotificationServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    private final transient NotificationService service = new NotificationService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = pathParts(req);
        long me = loginUser(req).getEmpId();
        if (p.length == 0) {
            ok(resp, service.list(me, page(req), size(req)));
        } else if (p.length == 1 && p[0].equals("unread-count")) {
            ok(resp, Map.of("count", service.unreadCount(me)));
        } else {
            notFound();
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = pathParts(req);
        long me = loginUser(req).getEmpId();
        if (p.length == 1 && p[0].equals("read-all")) {
            ok(resp, Map.of("updated", service.markAllRead(me)));
        } else if (p.length == 2 && p[1].equals("read")) {
            service.markRead(me, requireId(p[0], "알림 번호"));
            ok(resp, Map.of("message", "읽음 처리했습니다"));
        } else {
            notFound();
        }
    }
}
