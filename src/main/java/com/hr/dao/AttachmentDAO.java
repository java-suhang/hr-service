package com.hr.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** ATTACHMENT (C). 이력서는 D의 APPLICANT.resume_file_id와 함께 연결한다 */
public class AttachmentDAO {

    public record Attachment(long fileId, String refType, long refId, String originName, String storedName,
            long fileSize, LocalDateTime uploadedAt) {
    }

    public long insert(Connection conn, String refType, long refId, String originName, String storedName, long fileSize)
            throws SQLException {
        long id = DaoSupport.nextVal(conn, "SEQ_ATTACHMENT");
        String sql = "INSERT INTO ATTACHMENT (file_id, ref_type, ref_id, origin_name, stored_name, file_size) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, refType);
            ps.setLong(3, refId);
            ps.setString(4, originName);
            ps.setString(5, storedName);
            ps.setLong(6, fileSize);
            ps.executeUpdate();
        }
        return id;
    }

    public Attachment find(Connection conn, long fileId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM ATTACHMENT WHERE file_id = ?")) {
            ps.setLong(1, fileId);
            List<Attachment> rows = rows(ps);
            return rows.isEmpty() ? null : rows.get(0);
        }
    }

    public List<Attachment> findByRef(Connection conn, String refType, long refId) throws SQLException {
        String sql = "SELECT * FROM ATTACHMENT WHERE ref_type = ? AND ref_id = ? ORDER BY file_id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, refType);
            ps.setLong(2, refId);
            return rows(ps);
        }
    }

    /** 경비 주인 (영수증 다운로드 권한 확인용). 없으면 null */
    public Long expenseOwner(Connection conn, long expId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT emp_id FROM EXPENSE WHERE exp_id = ?")) {
            ps.setLong(1, expId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    // ------------------------------------------------------------------ D 연동: 지원자 이력서

    public boolean lockApplicant(Connection conn, long appId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT app_id FROM APPLICANT WHERE app_id = ? FOR UPDATE")) {
            ps.setLong(1, appId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void setResume(Connection conn, long appId, long fileId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE APPLICANT SET resume_file_id = ? WHERE app_id = ?")) {
            ps.setLong(1, fileId);
            ps.setLong(2, appId);
            ps.executeUpdate();
        }
    }

    private static List<Attachment> rows(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<Attachment> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new Attachment(rs.getLong("file_id"), rs.getString("ref_type"), rs.getLong("ref_id"),
                        rs.getString("origin_name"), rs.getString("stored_name"), rs.getLong("file_size"),
                        DaoSupport.getDateTime(rs, "uploaded_at")));
            }
            return rows;
        }
    }
}
