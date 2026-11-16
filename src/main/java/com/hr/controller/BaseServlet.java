package com.hr.controller;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.hr.dto.LoginUser;
import com.hr.util.ApiException;
import com.hr.util.JsonUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * API Servlet 공통 부모.
 *  - 요청 UTF-8, 응답 JSON
 *  - ApiException → 해당 상태 코드 + {"message"}, 그 밖의 예외 → 500
 *  - 로그인 사용자, 경로 조각, 입력값 파싱 도우미
 *
 * [임시] A 담당. A의 BaseServlet이 들어오면 그쪽에 맞춘다.
 */
public abstract class BaseServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(BaseServlet.class.getName());

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        try {
            super.service(req, resp);
        } catch (ApiException e) {
            sendError(resp, e.getStatus(), e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "요청 처리 실패: " + req.getMethod() + " " + req.getRequestURI(), e);
            sendError(resp, 500, "서버 오류가 발생했습니다");
        }
    }

    // ------------------------------------------------------------------ 응답

    protected void ok(HttpServletResponse resp, Object body) throws IOException {
        sendJson(resp, HttpServletResponse.SC_OK, body);
    }

    protected void created(HttpServletResponse resp, Object body) throws IOException {
        sendJson(resp, HttpServletResponse.SC_CREATED, body);
    }

    protected void sendJson(HttpServletResponse resp, int status, Object body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(JsonUtil.toJson(body));
    }

    protected void sendError(HttpServletResponse resp, int status, String message) throws IOException {
        if (resp.isCommitted()) {
            return;
        }
        resp.reset();
        sendJson(resp, status, Map.of("message", message == null ? "오류가 발생했습니다" : message));
    }

    // ------------------------------------------------------------------ 요청

    protected LoginUser loginUser(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        LoginUser user = session == null ? null : (LoginUser) session.getAttribute(LoginUser.SESSION_KEY);
        if (user == null) {
            throw new ApiException(401, "로그인이 필요합니다");
        }
        return user;
    }

    /** pathInfo를 "/"로 나눈 조각. "/12/cancel" → ["12", "cancel"], 없으면 빈 배열. */
    protected String[] pathParts(HttpServletRequest req) {
        String path = req.getPathInfo();
        if (path == null || path.equals("/")) {
            return new String[0];
        }
        return path.substring(1).split("/");
    }

    protected Map<String, Object> readJson(HttpServletRequest req) throws IOException {
        String body = req.getReader().lines().collect(Collectors.joining("\n"));
        try {
            return JsonUtil.parseObject(body);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("요청 본문이 올바른 JSON이 아닙니다");
        }
    }

    protected void notFound() {
        throw ApiException.notFound("요청한 주소가 없습니다");
    }

    // ------------------------------------------------------------------ 입력값 파싱 (잘못되면 400)

    protected static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    protected static String requireText(Object value, String field) {
        String s = text(value);
        if (s == null) {
            throw ApiException.badRequest(field + "을(를) 입력하세요");
        }
        return s;
    }

    protected static long requireId(Object value, String field) {
        String s = requireText(value, field);
        try {
            long id = new BigDecimal(s).longValueExact();
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException | ArithmeticException e) {
            throw ApiException.badRequest(field + "이(가) 올바르지 않습니다");
        }
    }

    protected static Long optionalId(Object value, String field) {
        return text(value) == null ? null : requireId(value, field);
    }

    protected static int intValue(Object value, String field, int defaultValue) {
        String s = text(value);
        if (s == null) {
            return defaultValue;
        }
        try {
            return new BigDecimal(s).intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw ApiException.badRequest(field + "이(가) 올바르지 않습니다");
        }
    }

    protected static BigDecimal requireDecimal(Object value, String field) {
        String s = requireText(value, field);
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(field + "이(가) 올바르지 않습니다");
        }
    }

    /** YYYY-MM-DD */
    protected static LocalDate date(Object value, String field) {
        String s = text(value);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + "은(는) YYYY-MM-DD 형식이어야 합니다");
        }
    }

    protected static LocalDate requireDate(Object value, String field) {
        LocalDate d = date(value, field);
        if (d == null) {
            throw ApiException.badRequest(field + "을(를) 입력하세요");
        }
        return d;
    }

    /** HH:mm */
    protected static LocalTime time(Object value, String field) {
        String s = text(value);
        if (s == null) {
            return null;
        }
        try {
            return LocalTime.parse(s);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + "은(는) HH:mm 형식이어야 합니다");
        }
    }

    /** YYYY-MM */
    protected static YearMonth yearMonth(Object value, String field) {
        String s = text(value);
        if (s == null) {
            return null;
        }
        try {
            return YearMonth.parse(s);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + "은(는) YYYY-MM 형식이어야 합니다");
        }
    }

    /** 목록 페이지 번호 (1부터) */
    protected static int page(HttpServletRequest req) {
        return Math.max(1, intValue(req.getParameter("page"), "page", 1));
    }

    /** 목록 페이지 크기 (1~100, 기본 20) */
    protected static int size(HttpServletRequest req) {
        return Math.min(100, Math.max(1, intValue(req.getParameter("size"), "size", 20)));
    }
}
