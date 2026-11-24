package com.hr.d.service;

import com.hr.util.ApiException;

public final class Problem extends ApiException {
    private Problem(int status, String message) {
        super(status, message);
    }

    public static Problem bad(String message) { return new Problem(400, message); }
    public static Problem forbidden(String message) { return new Problem(403, message); }
    public static Problem missing() { return new Problem(404, "요청한 항목을 찾을 수 없습니다."); }
    public static Problem conflict(String message) { return new Problem(409, message); }
    public static Problem unavailable(String message) { return new Problem(503, message); }
}
