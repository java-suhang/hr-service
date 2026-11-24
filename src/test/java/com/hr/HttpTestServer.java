package com.hr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.servlets.DefaultServlet;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.h2.jdbcx.JdbcDataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.filter.AuthFilter;
import com.hr.listener.AppContextListener;

import jakarta.servlet.Filter;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;

/**
 * HTTP 통합 테스트 도우미: 실제 db/schema.sql 전체와 db/seed.sql을 H2(Oracle 호환 모드)에 올리고
 * 내장 Tomcat에 서블릿·AuthFilter·AppContextListener를 등록한다.
 * DBUtil이 db.properties를 한 번만 읽으므로 테스트 클래스마다 JVM을 따로 띄운다 (surefire reuseForks=false).
 */
final class HttpTestServer {

    static final String H2_URL = "jdbc:h2:mem:team-http;MODE=Oracle;NON_KEYWORDS=YEAR;DB_CLOSE_DELAY=-1";
    static final ObjectMapper JSON = new ObjectMapper();
    static final int YEAR = LocalDate.now(ZoneId.of("Asia/Seoul")).getYear();

    static JdbcDataSource database;
    static Context context;
    static URI base;
    private static Tomcat tomcat;
    private static Path dbProperties;

    private HttpTestServer() {
    }

    static void start(Path temp, List<Class<? extends HttpServlet>> servlets) throws Exception {
        System.setProperty("hr.upload.dir", temp.resolve("uploads").toString());
        database = new JdbcDataSource();
        database.setURL(H2_URL);
        database.setUser("sa");
        database.setPassword("");
        writeDbProperties();
        script(Files.readString(Path.of("db/schema.sql")));
        script(Files.readString(Path.of("db/seed.sql")));

        tomcat = new Tomcat();
        tomcat.setBaseDir(temp.toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        context = tomcat.addContext("/hr", Path.of("src/main/webapp").toAbsolutePath().toString());
        context.setParentClassLoader(HttpTestServer.class.getClassLoader());
        Tomcat.addServlet(context, "default", new DefaultServlet());
        context.addServletMappingDecoded("/", "default");
        context.addMimeMapping("html", "text/html");
        context.addApplicationListener(AppContextListener.class.getName());
        for (Class<? extends HttpServlet> c : servlets) {
            register(c);
        }
        registerFilter(new AuthFilter(), AuthFilter.class.getAnnotation(WebFilter.class).urlPatterns());
        tomcat.start();
        base = URI.create("http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/hr/");
    }

    static void stop() throws Exception {
        try {
            if (tomcat != null) {
                tomcat.stop();
                tomcat.destroy();
            }
        } finally {
            if (dbProperties != null) {
                Files.deleteIfExists(dbProperties);
            }
        }
    }

    // ================================================================== HTTP

    record Res(int status, String body, String location, Map<String, List<String>> headers, byte[] bytes) {
        JsonNode json() throws Exception {
            return JSON.readTree(body);
        }

        String header(String name) {
            return headers.getOrDefault(name.toLowerCase(), List.of("")).get(0);
        }
    }

    static HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    static HttpClient login(String loginId, String password) throws Exception {
        HttpClient c = client();
        Res r = post(c, "api/auth/login", Map.of("loginId", loginId, "password", password));
        assertEquals(200, r.status(), loginId + " 로그인: " + r.body());
        return c;
    }

    static Res get(HttpClient c, String path) throws Exception {
        return send(c, HttpRequest.newBuilder(base.resolve(path)).GET());
    }

    static Res delete(HttpClient c, String path) throws Exception {
        return send(c, HttpRequest.newBuilder(base.resolve(path)).DELETE());
    }

    static Res post(HttpClient c, String path, Object body) throws Exception {
        return send(c, HttpRequest.newBuilder(base.resolve(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    static Res put(HttpClient c, String path, Object body) throws Exception {
        return send(c, HttpRequest.newBuilder(base.resolve(path)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    /** multipart/form-data 업로드. fileName이 null이면 파일 없이 필드만 보낸다 */
    static Res upload(HttpClient c, String path, Map<String, String> fields, String fileField, String fileName,
            String contentType, byte[] data) throws Exception {
        String boundary = "----hr" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> f : fields.entrySet()) {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n"
                    + f.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        if (fileName != null) {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField + "\"; filename=\""
                    + fileName + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(c, HttpRequest.newBuilder(base.resolve(path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())));
    }

    static Res send(HttpClient c, HttpRequest.Builder b) throws Exception {
        HttpResponse<byte[]> r = c.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Res(r.statusCode(), new String(r.body(), StandardCharsets.UTF_8),
                r.headers().firstValue("location").orElse(""), r.headers().map(), r.body());
    }

    // ================================================================== DB

    static long idOf(String sql, Object... params) throws SQLException {
        return ((Number) scalar(sql, params)).longValue();
    }

    static Object scalar(String sql, Object... params) throws SQLException {
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        }
    }

    static int execute(String sql, Object... params) throws SQLException {
        try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }

    static void script(String sql) throws SQLException {
        try (Connection conn = database.getConnection(); var st = conn.createStatement()) {
            for (String command : sql.replaceAll("(?m)^\\s*--[^\\r\\n]*", "").split(";")) {
                if (!command.isBlank()) {
                    st.execute(command);
                }
            }
        }
    }

    // ================================================================== 설정

    private static void writeDbProperties() throws Exception {
        Properties props = new Properties();
        props.setProperty("db.driver", "org.h2.Driver");
        props.setProperty("db.url", H2_URL);
        props.setProperty("db.user", "sa");
        props.setProperty("db.password", "");
        Path classes = Path.of(HttpTestServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path path = classes.resolve("db.properties");
        try (var out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
            dbProperties = path;
            props.store(out, "Tests only");
        }
    }

    /** @WebServlet 경로와 @MultipartConfig를 그대로 등록한다 (내장 Tomcat은 애노테이션을 스캔하지 않는다) */
    private static void register(Class<? extends HttpServlet> type) throws Exception {
        WebServlet mapping = type.getAnnotation(WebServlet.class);
        String name = type.getName();
        Wrapper wrapper = Tomcat.addServlet(context, name, type.getDeclaredConstructor().newInstance());
        MultipartConfig multipart = type.getAnnotation(MultipartConfig.class);
        if (multipart != null) {
            wrapper.setMultipartConfigElement(new MultipartConfigElement(multipart));
        }
        String[] patterns = mapping.value().length > 0 ? mapping.value() : mapping.urlPatterns();
        for (String pattern : patterns) {
            context.addServletMappingDecoded(pattern, name);
        }
    }

    private static void registerFilter(Filter filter, String... patterns) {
        FilterDef def = new FilterDef();
        def.setFilterName(filter.getClass().getName());
        def.setFilter(filter);
        context.addFilterDef(def);
        FilterMap map = new FilterMap();
        map.setFilterName(def.getFilterName());
        for (String pattern : patterns) {
            map.addURLPatternDecoded(pattern);
        }
        context.addFilterMap(map);
    }
}
