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
 * 로그인·권한 검사 (NFR-03: 권한 검사는 서버 필터에서).
 *  - /api/**, /user/**, /admin/** 는 로그인 필요
 *  - /api/admin/**, /admin/** 는 ADMIN만
 *  - /api/auth/** (로그인 API)는 통과
 *
 * [임시] A 담당. A의 AuthFilter가 들어오면 이 파일은 교체된다.
 */
@WebFilter(urlPatterns = { "/api/*", "/user/*", "/admin/*" })
public class AuthFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI().substring(request.getContextPath().length());

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

        chain.doFilter(req, res);
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json; charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
