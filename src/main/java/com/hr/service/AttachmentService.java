package com.hr.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

import com.hr.dao.ApprovalDAO;
import com.hr.dao.AttachmentDAO;
import com.hr.dao.AttachmentDAO.Attachment;
import com.hr.dao.EmployeeDAO;
import com.hr.dto.Employee;
import com.hr.util.ApiException;
import com.hr.util.DBUtil;
import com.hr.util.FileStorage;

import jakarta.servlet.http.Part;

/**
 * 첨부파일 업로드·다운로드 (REQ-31). 영수증(EXPENSE)과 이력서(APPLICANT, D 연동).
 *
 *  - JPG·PNG·PDF만, 5MB 이하. 확장자가 아니라 파일 앞부분(매직 바이트)으로 형식을 확인한다.
 *  - 다운로드 권한: 영수증은 본인·그 경비의 결재자·관리자, 이력서는 관리자만.
 */
public class AttachmentService {

    public static final String REF_EXPENSE = "EXPENSE";
    public static final String REF_APPLICANT = "APPLICANT";
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int NAME_MAX = 200;

    private final AttachmentDAO dao = new AttachmentDAO();
    private final ApprovalDAO approvalDAO = new ApprovalDAO();
    private final EmployeeDAO employeeDAO = new EmployeeDAO();
    private final AuditService audit = new AuditService();

    /** 검사를 마친 업로드 파일 */
    public record Upload(String originName, String extension, byte[] data) {
    }

    /** 다운로드할 파일 */
    public record Download(Attachment attachment, Path path, String contentType) {
    }

    /** 화면에 내보내는 첨부 정보 (저장 파일 이름은 감춘다) */
    public record FileInfo(long fileId, String originName, long fileSize, java.time.LocalDateTime uploadedAt) {
        public static FileInfo of(Attachment a) {
            return new FileInfo(a.fileId(), a.originName(), a.fileSize(), a.uploadedAt());
        }
    }

    // ================================================================== 업로드 검사

    /** multipart 파일을 읽고 형식·크기를 확인한다. required가 아니면 빈 파일은 null */
    public static Upload read(Part part, boolean required, String label) throws IOException {
        if (part == null || part.getSize() == 0) {
            if (required) {
                throw ApiException.badRequest(label + " 파일을 첨부하세요");
            }
            return null;
        }
        if (part.getSize() > MAX_BYTES) {
            throw tooLarge();
        }
        byte[] data;
        try (InputStream in = part.getInputStream()) {
            data = in.readNBytes((int) MAX_BYTES + 1);
        }
        if (data.length > MAX_BYTES) {
            throw tooLarge();
        }
        String ext = detect(data);
        if (ext == null) {
            throw ApiException.badRequest("JPG, PNG, PDF 파일만 올릴 수 있습니다");
        }
        return new Upload(cleanName(part.getSubmittedFileName(), ext), ext, data);
    }

    public static ApiException tooLarge() {
        return new ApiException(413, "파일은 5MB까지 올릴 수 있습니다");
    }

    /** 파일 앞부분으로 형식을 판단한다 */
    static String detect(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
                && d[4] == 0x0D && d[5] == 0x0A && d[6] == 0x1A && d[7] == 0x0A) {
            return "png";
        }
        if (d.length >= 5 && d[0] == '%' && d[1] == 'P' && d[2] == 'D' && d[3] == 'F' && d[4] == '-') {
            return "pdf";
        }
        return null;
    }

    /** 화면에 보일 원래 이름. 경로·제어 문자를 빼고, 확장자가 실제 형식과 다르면 맞춘다 */
    static String cleanName(String submitted, String ext) {
        String name = submitted == null ? "" : submitted;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}\"<>|:*?]", "").strip();
        String lower = name.toLowerCase(Locale.ROOT);
        boolean matches = lower.endsWith("." + ext) || ("jpg".equals(ext) && lower.endsWith(".jpeg"));
        if (name.isEmpty() || name.startsWith(".")) {
            name = "file";
            matches = false;
        }
        if (!matches) {
            name = name + "." + ext;
        }
        if (name.length() > NAME_MAX) {
            name = name.substring(0, NAME_MAX - ext.length() - 1) + "." + ext;
        }
        return name;
    }

    // ================================================================== 저장

    /** 파일을 쓰고 DB 작업을 실행한다. DB 작업이 실패하면 방금 쓴 파일을 지운다 */
    public static <T> T withStoredFile(Upload upload, StoredWork<T> work) {
        String stored;
        try {
            stored = FileStorage.save(upload.data(), upload.extension());
        } catch (IOException e) {
            throw new IllegalStateException("파일을 저장하지 못했습니다", e);
        }
        try {
            return work.run(stored);
        } catch (RuntimeException e) {
            FileStorage.deleteQuietly(stored);
            throw e;
        }
    }

    @FunctionalInterface
    public interface StoredWork<T> {
        T run(String storedName);
    }

    /** 지원자 이력서를 올리고 APPLICANT.resume_file_id를 새 파일로 바꾼다 (관리자) */
    public FileInfo attachResume(long appId, Upload upload, long actorId) {
        return withStoredFile(upload, stored -> DBUtil.tx(conn -> {
            if (!dao.lockApplicant(conn, appId)) {
                throw ApiException.notFound("지원자를 찾을 수 없습니다");
            }
            long fileId = dao.insert(conn, REF_APPLICANT, appId, upload.originName(), stored, upload.data().length);
            dao.setResume(conn, appId, fileId);
            Attachment saved = dao.find(conn, fileId);
            FileInfo info = FileInfo.of(saved);
            audit.log(conn, actorId, AuditService.CREATE, "ATTACHMENT", fileId, null, info);
            return info;
        }));
    }

    // ================================================================== 다운로드

    public Download open(long fileId, long actorId) {
        Attachment a = DBUtil.read(conn -> {
            Attachment found = dao.find(conn, fileId);
            if (found == null || !canDownload(conn, found, actorId)) {
                throw ApiException.notFound("파일을 찾을 수 없습니다");
            }
            return found;
        });
        try {
            Path path = FileStorage.path(a.storedName());
            if (!Files.isRegularFile(path)) {
                throw ApiException.notFound("파일이 서버에 없습니다. 관리자에게 문의하세요");
            }
            return new Download(a, path, FileStorage.contentType(a.storedName()));
        } catch (IOException e) {
            throw new IllegalStateException("파일을 열 수 없습니다", e);
        }
    }

    private boolean canDownload(Connection conn, Attachment a, long actorId) throws SQLException {
        Employee actor = employeeDAO.findById(conn, actorId);
        boolean admin = actor != null && "ADMIN".equals(actor.getRole()) && Employee.ACTIVE.equals(actor.getStatus());
        if (admin) {
            return true;
        }
        if (!REF_EXPENSE.equals(a.refType())) {
            return false;
        }
        Long owner = dao.expenseOwner(conn, a.refId());
        if (owner != null && owner == actorId) {
            return true;
        }
        Long docId = approvalDAO.findIdByRef(conn, ExpenseService.DOC_TYPE, a.refId());
        return docId != null && approvalDAO.isApprover(conn, docId, actorId);
    }
}
