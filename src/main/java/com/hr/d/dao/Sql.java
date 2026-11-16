package com.hr.d.dao;

import com.hr.d.dto.Page;
import com.hr.d.service.Problem;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

final class Sql {
    private Sql() {}

    static List<Map<String, Object>> rows(Connection conn, String sql, Object... params) throws SQLException {
        try (var statement = prepare(conn, sql, params); var result = statement.executeQuery()) {
            var metadata = result.getMetaData();
            var rows = new ArrayList<Map<String, Object>>();
            while (result.next()) {
                var row = new LinkedHashMap<String, Object>();
                for (int col = 1; col <= metadata.getColumnCount(); col++) {
                    Object value = metadata.getColumnType(col) == Types.CLOB
                            ? result.getString(col) : result.getObject(col);
                    row.put(metadata.getColumnLabel(col), value);
                }
                rows.add(row);
            }
            return rows;
        }
    }

    static Map<String, Object> one(Connection conn, String sql, Object... params) throws SQLException {
        var rows = rows(conn, sql, params);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    static Map<String, Object> require(Connection conn, String sql, Object... params) throws SQLException {
        var row = one(conn, sql, params);
        if (row == null) throw Problem.missing();
        return row;
    }

    static long scalar(Connection conn, String sql, Object... params) throws SQLException {
        try (var statement = prepare(conn, sql, params); var result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    static int change(Connection conn, String sql, Object... params) throws SQLException {
        try (var statement = prepare(conn, sql, params)) {
            return statement.executeUpdate();
        }
    }

    static Page page(Connection conn, Page.Request page, String sql, String order, Object... params)
            throws SQLException {
        long total = scalar(conn, "SELECT COUNT(*) FROM (" + sql + ")", params);
        Object[] bounds = Arrays.copyOf(params, params.length + 2);
        bounds[params.length] = page.offset() + page.size();
        bounds[params.length + 1] = page.offset();
        var items = rows(conn, "SELECT * FROM (SELECT window_rows.*, ROWNUM AS page_row FROM ("
                + sql + " ORDER BY " + order + ") window_rows WHERE ROWNUM <= ?) WHERE page_row > ? ORDER BY page_row",
                bounds);
        for (var item : items) item.remove("PAGE_ROW");
        return new Page(items, total, page.page(), page.size());
    }

    private static PreparedStatement prepare(Connection conn, String sql, Object[] params) throws SQLException {
        var statement = conn.prepareStatement(sql);
        try {
            for (int i = 0; i < params.length; i++) {
                int index = i + 1;
                if (params[i] instanceof ClobText text) {
                    statement.setCharacterStream(index, new java.io.StringReader(text.value()), text.value().length());
                } else if (params[i] instanceof LocalDate date) {
                    statement.setDate(index, java.sql.Date.valueOf(date));
                } else {
                    statement.setObject(index, params[i]);
                }
            }
            return statement;
        } catch (SQLException | RuntimeException ex) {
            try { statement.close(); } catch (SQLException close) { ex.addSuppressed(close); }
            throw ex;
        }
    }

    record ClobText(String value) {}
}
