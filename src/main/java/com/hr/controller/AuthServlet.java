package com.hr.controller;

import java.io.IOException;
import java.util.Map;

import com.hr.dto.LoginUser;
import com.hr.service.AuthService;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * 로그인 (REQ-01) · 비밀번호 변경 (REQ-03)
 *
 * POST /api/auth/login     {loginId, password} → 로그인 사용자
 * POST /api/auth/logout
 * GET  /api/auth/me        → 로그인 사용자 (empId, loginId, name, role, deptName, posName, admin)
 * POST /api/auth/password  {currentPassword, newPassword}
 */
@WebServlet("/api/auth/*")
public class AuthServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;
    private static final int SESSION_SECONDS = 60 * 60;

    private final transient AuthService authService = new AuthService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = pathParts(req);
        if (p.length != 1 || !p[0].equals("me")) {
            notFound();
        }
        LoginUser user = loginUser(req);
        // 부서·이름 변경이나 퇴사를 바로 반영한다
        LoginUser fresh = authService.refresh(user.getEmpId());
        if (fresh == null) {
            req.getSession().invalidate();
            sendError(resp, 401, "로그인이 필요합니다");
            return;
        }
        req.getSession().setAttribute(LoginUser.SESSION_KEY, fresh);
        ok(resp, fresh);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = pathParts(req);
        String action = p.length == 1 ? p[0] : "";
        switch (action) {
            case "login" -> {
                Map<String, Object> body = readJson(req);
                LoginUser user = authService.login(text(body.get("loginId")), raw(body.get("password")));
                // 세션 고정 공격 방지: 로그인 전에 쓰던 세션을 버리고 새로 만든다
                HttpSession old = req.getSession(false);
                if (old != null) {
                    old.invalidate();
                }
                HttpSession session = req.getSession(true);
                session.setMaxInactiveInterval(SESSION_SECONDS);
                session.setAttribute(LoginUser.SESSION_KEY, user);
                ok(resp, user);
            }
            case "logout" -> {
                HttpSession session = req.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                ok(resp, Map.of("message", "로그아웃했습니다"));
            }
            case "password" -> {
                LoginUser user = loginUser(req);
                Map<String, Object> body = readJson(req);
                authService.changePassword(user.getEmpId(), raw(body.get("currentPassword")),
                        raw(body.get("newPassword")));
                ok(resp, Map.of("message", "비밀번호를 바꿨습니다"));
            }
            default -> notFound();
        }
    }
}
