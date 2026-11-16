package com.hr.util;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * DB 연결과 트랜잭션.
 *
 * 접속 정보는 클래스패스의 db.properties에서 읽는다 (db.properties.example 참고).
 * 여러 테이블을 바꾸는 작업은 tx()로 감싸 한 트랜잭션에서 처리하고,
 * 다른 도메인의 Service는 Connection을 인자로 받아 같은 트랜잭션에 합류한다.
 *
 * [임시] A의 공통 모듈이 들어오면 그쪽 DBUtil로 바꾼다.
 */
public final class DBUtil {

    /** Connection을 받아 일을 하는 람다. */
    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection conn) throws SQLException;
    }

    private static final Properties PROPS = loadProperties();

    private DBUtil() {
    }

    private static Properties loadProperties() {
        Properties p = new Properties();
        p.setProperty("db.driver", "oracle.jdbc.OracleDriver");
        p.setProperty("db.url", "jdbc:oracle:thin:@localhost:1521:xe");
        try (InputStream in = DBUtil.class.getClassLoader().getResourceAsStream("db.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("db.properties를 읽을 수 없습니다", e);
        }
        return p;
    }

    public static Connection getConnection() throws SQLException {
        String user = PROPS.getProperty("db.user");
        String password = PROPS.getProperty("db.password");
        if (user == null || password == null) {
            throw new SQLException("db.properties에 db.user와 db.password를 설정하세요");
        }
        try {
            Class.forName(PROPS.getProperty("db.driver"));
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC 드라이버가 없습니다. WEB-INF/lib에 ojdbc jar를 넣으세요", e);
        }
        return DriverManager.getConnection(PROPS.getProperty("db.url"), user, password);
    }

    /** 한 트랜잭션으로 실행한다. 예외가 나면 전부 롤백한다. */
    public static <T> T tx(SqlWork<T> work) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(conn);
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("DB 처리 중 오류가 발생했습니다", e);
        }
    }

    /** 조회 전용. 자동 커밋으로 실행한다. */
    public static <T> T read(SqlWork<T> work) {
        try (Connection conn = getConnection()) {
            return work.run(conn);
        } catch (SQLException e) {
            throw new IllegalStateException("DB 조회 중 오류가 발생했습니다", e);
        }
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // 원래 예외를 살리기 위해 롤백 실패는 무시한다
        }
    }
}
