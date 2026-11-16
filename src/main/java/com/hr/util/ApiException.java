package com.hr.util;

/**
 * HTTP 상태 코드와 사용자에게 보여줄 메시지를 함께 던지는 예외.
 * BaseServlet이 잡아서 {"message": "..."} 형식으로 응답한다.
 *
 * [임시] A의 공통 모듈이 들어오면 그쪽 예외로 바꾼다.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }
}
