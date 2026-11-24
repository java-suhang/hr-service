package com.hr.d.dto;

import java.util.List;
import java.util.Map;

public record Page(List<Map<String, Object>> items, long total, int page, int size) {
    public record Request(int page, int size) {
        public Request {
            if (page < 1 || size < 1 || size > 100) {
                throw new IllegalArgumentException("페이지는 1 이상, 페이지 크기는 1~100이어야 합니다.");
            }
        }

        public long offset() {
            return (page - 1L) * size;
        }
    }
}
