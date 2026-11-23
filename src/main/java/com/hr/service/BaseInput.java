package com.hr.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import com.hr.util.ApiException;

/** 요청 JSON 값(Map에서 꺼낸 Object)을 검증하며 바꾼다. 잘못되면 400 */
final class BaseInput {

    private BaseInput() {
    }

    static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    static String requireText(Object value, String field) {
        String s = text(value);
        if (s == null) {
            throw ApiException.badRequest(field + "을(를) 입력하세요");
        }
        return s;
    }

    static Long optionalId(Object value, String field) {
        String s = text(value);
        if (s == null) {
            return null;
        }
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

    static long requireId(Object value, String field) {
        Long id = optionalId(value, field);
        if (id == null) {
            throw ApiException.badRequest(field + "을(를) 선택하세요");
        }
        return id;
    }

    static int requireInt(Object value, String field) {
        String s = requireText(value, field);
        try {
            return new BigDecimal(s).intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw ApiException.badRequest(field + "이(가) 올바르지 않습니다");
        }
    }

    static BigDecimal decimalOr(Object value, String field, BigDecimal defaultValue) {
        String s = text(value);
        if (s == null) {
            return defaultValue;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(field + "이(가) 올바르지 않습니다");
        }
    }

    static LocalDate requireDate(Object value, String field) {
        String s = requireText(value, field);
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + "은(는) YYYY-MM-DD 형식이어야 합니다");
        }
    }
}
