package com.hr.filter;

import java.io.IOException;

import com.hr.dto.LoginUser;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * 로그인·권한 검사 (REQ-02, NFR-03: 권한 검사는 서버 필터에서).
 *  - /api/**, /user/**, /admin/** 는 로그인 필요
 *  - /api/admin/**, /admin/** 는 ADMIN만
 *  - /api/auth/** 는 통과 (로그인 API. me·password는 AuthServlet이 직접 로그인을 확인)
 *
 * 경로는 디코딩된 servletPath + pathInfo로 판단한다. "%61dmin/..." 같은 인코딩 우회를 막는다.
 */
@WebFilter(urlPatterns = { "/api/*", "/user/*", "/admin/*" })
public class AuthFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());

        if (path.startsWith("/api/auth/")) {
            chain.doFilter(req, res);
            return;
        }

        HttpSession session = request.getSession(false);
        LoginUser user = session == null ? null : (LoginUser) session.getAttribute(LoginUser.SESSION_KEY);
        boolean api = path.startsWith("/api/");

        if (user == null) {
            if (api) {
                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "로그인이 필요합니다");
            } else {
                response.sendRedirect(request.getContextPath() + "/login.html");
            }
            return;
        }

        boolean adminArea = path.startsWith("/api/admin/") || path.startsWith("/admin/");
        if (adminArea && !user.isAdmin()) {
            if (api) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "권한이 없습니다");
            } else {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
            }
            return;
        }

        if (!api) {
            // 로그아웃 뒤 뒤로가기로 화면이 보이지 않게 한다
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(req, res);
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json; charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
