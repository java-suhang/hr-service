package com.hr.d.controller;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hr.d.service.Problem;
import jakarta.servlet.http.*;
import java.io.IOException;

final class Json {
    private static final int BODY_LIMIT = 512 * 1024;
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private Json() {}

    static ObjectNode body(HttpServletRequest req) throws IOException {
        String type = req.getContentType();
        if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            throw Problem.bad("application/json 요청이 필요합니다.");
        }
        byte[] bytes = req.getInputStream().readNBytes(BODY_LIMIT + 1);
        if (bytes.length > BODY_LIMIT) throw Problem.bad("요청 본문이 너무 큽니다.");
        try {
            if (MAPPER.readTree(bytes) instanceof ObjectNode object) return object;
        } catch (IOException ex) {
            throw Problem.bad("JSON 본문을 확인해 주세요.");
        }
        throw Problem.bad("JSON 객체가 필요합니다.");
    }
}
