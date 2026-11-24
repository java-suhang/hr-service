package com.hr.d.filter;

import com.hr.dto.LoginUser;
import com.hr.util.JsonUtil;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Response headers and CSRF checks for D. The shared AuthFilter controls login and access. */
@WebFilter({
        "/api/notices/*", "/api/admin/notices/*", "/api/dashboard/*", "/api/admin/dashboard/*",
        "/api/evaluations/*", "/api/admin/evaluations/*", "/api/recruitment/*", "/api/admin/recruitment/*",
        "/user/home.html", "/user/notices.html", "/user/evaluation.html", "/user/interviews.html",
        "/admin/dashboard.html", "/admin/notices.html", "/admin/evaluations.html", "/admin/recruit.html"
})
public final class DomainRequestFilter implements Filter {
    private static final String CSRF = DomainRequestFilter.class.getName() + ".csrf";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var req = (HttpServletRequest) request;
        var resp = (HttpServletResponse) response;
        String path = req.getServletPath();
        boolean api = path.startsWith("/api/");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        if (!api) {
            resp.setHeader("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
            var session = req.getSession(false);
            if (path.startsWith("/admin/") && session != null
                    && session.getAttribute(LoginUser.SESSION_KEY) instanceof LoginUser user && !user.isAdmin()) {
                resp.sendError(403);
                return;
            }
        } else if (!req.getMethod().equals("GET") && !req.getMethod().equals("HEAD")) {
            var session = req.getSession(false);
            if (session != null && session.getAttribute(LoginUser.SESSION_KEY) instanceof LoginUser
                    && (!(session.getAttribute(CSRF) instanceof String expected)
                        || !Objects.equals(expected, req.getHeader("X-CSRF-Token")))) {
                resp.setStatus(403);
                resp.setContentType("application/json; charset=UTF-8");
                resp.getWriter().write(JsonUtil.toJson(Map.of("message", "보안 토큰을 확인할 수 없습니다.")));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    public static String token(HttpSession session) {
        synchronized (session) {
            if (session.getAttribute(CSRF) instanceof String token) return token;
            String token = UUID.randomUUID().toString();
            session.setAttribute(CSRF, token);
            return token;
        }
    }
}
