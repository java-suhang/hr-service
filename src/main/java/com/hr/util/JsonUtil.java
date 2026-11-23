package com.hr.util;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 외부 라이브러리 없이 쓰는 JSON 변환기.
 *
 * toJson: Map, List, 배열, 문자열, 숫자, boolean, 날짜, record, getter가 있는 DTO를 JSON으로 바꾼다.
 *         날짜는 YYYY-MM-DD, 일시는 YYYY-MM-DDTHH:mm, 시각은 HH:mm (API 규칙).
 * parse:  JSON 문자열을 Map / List / String / BigDecimal / Boolean / null로 읽는다.
 */
public final class JsonUtil {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private JsonUtil() {
    }

    // ------------------------------------------------------------------ 쓰기

    public static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof BigDecimal bd) {
            sb.append(bd.stripTrailingZeros().toPlainString());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            sb.append(Double.isNaN(d) || Double.isInfinite(d) ? "null" : String.valueOf(v));
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Character || v instanceof Enum<?>) {
            writeString(sb, v.toString());
        } else if (v instanceof LocalDate d) {
            writeString(sb, d.toString());
        } else if (v instanceof LocalDateTime dt) {
            writeString(sb, dt.format(DATE_TIME));
        } else if (v instanceof LocalTime t) {
            writeString(sb, t.format(TIME));
        } else if (v instanceof Map<?, ?> m) {
            writeMap(sb, m);
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object item : it) {
                if (!first) {
                    sb.append(',');
                }
                write(sb, item);
                first = false;
            }
            sb.append(']');
        } else if (v.getClass().isArray()) {
            sb.append('[');
            int len = Array.getLength(v);
            for (int i = 0; i < len; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, Array.get(v, i));
            }
            sb.append(']');
        } else if (v.getClass().isRecord()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (RecordComponent rc : v.getClass().getRecordComponents()) {
                m.put(rc.getName(), invoke(rc.getAccessor(), v));
            }
            writeMap(sb, m);
        } else {
            writeMap(sb, beanToMap(v));
        }
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> m) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            write(sb, e.getValue());
            first = false;
        }
        sb.append('}');
    }

    private static Map<String, Object> beanToMap(Object bean) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            BeanInfo info = Introspector.getBeanInfo(bean.getClass(), Object.class);
            for (PropertyDescriptor pd : info.getPropertyDescriptors()) {
                Method read = pd.getReadMethod();
                if (read != null) {
                    m.put(pd.getName(), invoke(read, bean));
                }
            }
        } catch (IntrospectionException e) {
            throw new IllegalStateException("JSON으로 바꿀 수 없는 객체: " + bean.getClass().getName(), e);
        }
        return m;
    }

    private static Object invoke(Method method, Object target) {
        try {
            // public 메서드라도 클래스가 public이 아니면(중첩 record 등) 막히므로 접근을 연다
            if (!method.canAccess(target)) {
                method.trySetAccessible();
            }
            return method.invoke(target);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("JSON 변환 중 값을 읽을 수 없습니다: " + method, e);
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ 읽기

    /** JSON 문자열을 읽는다. 형식이 틀리면 IllegalArgumentException. */
    public static Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Parser p = new Parser(json);
        Object value = p.value();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw p.error("JSON 뒤에 불필요한 문자가 있습니다");
        }
        return value;
    }

    /** JSON 객체를 Map으로 읽는다. 빈 본문은 빈 Map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object value = parse(json);
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("JSON 객체가 아닙니다");
        }
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        void skipWhitespace() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    break;
                }
                i++;
            }
        }

        boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        void expect(char c) {
            if (!peek(c)) {
                throw error("'" + c + "'가 필요합니다");
            }
            i++;
        }

        Object value() {
            skipWhitespace();
            if (atEnd()) {
                throw error("값이 없습니다");
            }
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            skipWhitespace();
            if (peek('}')) {
                i++;
                return m;
            }
            while (true) {
                skipWhitespace();
                if (!peek('"')) {
                    throw error("키가 필요합니다");
                }
                String key = string();
                skipWhitespace();
                expect(':');
                m.put(key, value());
                skipWhitespace();
                if (peek(',')) {
                    i++;
                    continue;
                }
                expect('}');
                return m;
            }
        }

        List<Object> array() {
            List<Object> list = new ArrayList<>();
            i++;
            skipWhitespace();
            if (peek(']')) {
                i++;
                return list;
            }
            while (true) {
                list.add(value());
                skipWhitespace();
                if (peek(',')) {
                    i++;
                    continue;
                }
                expect(']');
                return list;
            }
        }

        String string() {
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("문자열이 닫히지 않았습니다");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw error("이스케이프가 끝나지 않았습니다");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw error("잘못된 유니코드 이스케이프");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("잘못된 유니코드 이스케이프");
                        }
                        i += 4;
                    }
                    default -> throw error("잘못된 이스케이프");
                }
            }
        }

        Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (start == i) {
                throw error("알 수 없는 값");
            }
            try {
                return new BigDecimal(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw error("잘못된 숫자");
            }
        }

        Object literal(String word, Object value) {
            if (!s.startsWith(word, i)) {
                throw error("알 수 없는 값");
            }
            i += word.length();
            return value;
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("JSON 형식 오류(위치 " + i + "): " + message);
        }
    }
}
