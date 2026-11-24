package com.hr.d.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.hr.d.dto.Page;
import com.hr.d.service.Problem;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

final class Input {
    private Input() {}

    static long id(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // Invalid IDs and non-numeric route parameters use the same response.
        }
        throw Problem.bad("양의 정수 ID가 필요합니다.");
    }

    static long id(JsonNode body, String key) {
        var value = body.path(key);
        if (value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0) return value.longValue();
        throw Problem.bad(key + " 값은 양의 정수여야 합니다.");
    }

    static Long optionalId(JsonNode body, String key) {
        return body.hasNonNull(key) ? id(body, key) : null;
    }

    static String text(JsonNode body, String key, int max) {
        String value = optionalText(body, key, max);
        if (value == null) throw Problem.bad(key + " 값을 입력해 주세요.");
        return value;
    }

    static String optionalText(JsonNode body, String key, int max) {
        var value = body.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual()) throw Problem.bad(key + " 값은 문자열이어야 합니다.");
        String text = value.textValue().strip();
        if (text.length() > max) throw Problem.bad(key + " 값은 " + max + "자 이하여야 합니다.");
        return text.isEmpty() ? null : text;
    }

    static boolean flag(JsonNode body, String key) {
        if (!body.has(key)) return false;
        var value = body.path(key);
        if (!value.isBoolean()) throw Problem.bad(key + " 값은 true 또는 false여야 합니다.");
        return value.booleanValue();
    }

    static BigDecimal decimal(JsonNode body, String key, BigDecimal min, BigDecimal max, int scale) {
        var value = body.path(key);
        if (value.isNumber()) {
            var number = value.decimalValue();
            if (number.compareTo(min) >= 0 && number.compareTo(max) <= 0
                    && number.stripTrailingZeros().scale() <= scale) {
                return number;
            }
        }
        throw Problem.bad(key + " 값의 범위 또는 소수 자릿수를 확인해 주세요.");
    }

    static LocalDate date(JsonNode body, String key) {
        String value = text(body, key, 10);
        try {
            if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") && !value.startsWith("0000")) {
                return LocalDate.parse(value);
            }
        } catch (DateTimeParseException ignored) {
            // Report the same format for invalid calendar dates.
        }
        throw Problem.bad(key + " 날짜 형식은 YYYY-MM-DD입니다.");
    }

    static LocalDateTime dateTime(JsonNode body, String key) {
        String value = text(body, key, 16);
        try {
            if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}") && !value.startsWith("0000")) {
                return LocalDateTime.parse(value);
            }
        } catch (DateTimeParseException ignored) {
            // Report the same format for invalid calendar dates and times.
        }
        throw Problem.bad(key + " 일시 형식은 YYYY-MM-DDTHH:mm입니다.");
    }

    static Page.Request page(HttpServletRequest req) {
        String page = req.getParameter("page");
        String size = req.getParameter("size");
        try {
            return new Page.Request(page == null ? 1 : Integer.parseInt(page), size == null ? 20 : Integer.parseInt(size));
        } catch (IllegalArgumentException ex) {
            throw Problem.bad("페이지는 1 이상, 페이지 크기는 1~100이어야 합니다.");
        }
    }
}
