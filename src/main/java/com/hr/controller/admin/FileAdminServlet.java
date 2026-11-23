package com.hr.controller.admin;

import java.io.IOException;

import com.hr.controller.BaseServlet;
import com.hr.controller.Multipart;
import com.hr.service.AttachmentService;

import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 이력서 업로드 (REQ-31, D 채용 연동) — ADMIN 전용
 *
 * POST /api/admin/files/applicants/{appId}   multipart/form-data: file → APPLICANT.resume_file_id를 새 파일로
 */
@WebServlet("/api/admin/files/*")
@MultipartConfig(fileSizeThreshold = 1024 * 1024, maxFileSize = Multipart.MAX_FILE, maxRequestSize = Multipart.MAX_REQUEST)
public class FileAdminServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    private final transient AttachmentService service = new AttachmentService();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = pathParts(req);
        if (p.length != 2 || !p[0].equals("applicants")) {
            notFound();
        }
        long appId = requireId(p[1], "지원자 번호");
        AttachmentService.Upload upload = AttachmentService.read(Multipart.file(req, "file"), true, "이력서");
        created(resp, service.attachResume(appId, upload, loginUser(req).getEmpId()));
    }
}
