package com.hr.dao;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 근태·휴가 DAO가 같이 쓰는 JDBC 도우미.
 * A의 공통 모듈에 같은 기능이 생기면 그쪽으로 옮긴다.
 */
final class DaoSupport {

    private DaoSupport() {
    }

    /** 시퀀스 다음 값 (Oracle 11g: IDENTITY 대신 시퀀스) */
    static long nextVal(Connection conn, String sequence) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT " + sequence + ".NEXTVAL FROM dual");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** DATE 컬럼에 날짜만 바인딩한다. java.sql.Date로 넣어야 work_date 인덱스를 탄다. */
    static void setDate(PreparedStatement ps, int index, LocalDate date) throws SQLException {
        if (date == null) {
            ps.setNull(index, Types.DATE);
        } else {
            ps.setDate(index, Date.valueOf(date));
        }
    }

    static void setDateTime(PreparedStatement ps, int index, LocalDateTime dateTime) throws SQLException {
        if (dateTime == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            ps.setTimestamp(index, Timestamp.valueOf(dateTime.withNano(0)));
        }
    }

    static LocalDate getDate(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime().toLocalDate();
    }

    static LocalDateTime getDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    static Long getLongOrNull(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    /** LIKE 검색어의 %, _, \ 를 이스케이프한다. SQL에는 ESCAPE '\' 를 붙인다. */
    static String likeContains(String keyword) {
        String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /** IN (?, ?, ...) 자리표시자 */
    static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    static void setLongs(PreparedStatement ps, int startIndex, List<Long> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            ps.setLong(startIndex + i, values.get(i));
        }
    }
}
