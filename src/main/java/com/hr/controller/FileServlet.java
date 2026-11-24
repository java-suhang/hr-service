package com.hr.controller;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.hr.service.AttachmentService;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 첨부파일 다운로드 (REQ-31). 권한이 없거나 없는 파일은 똑같이 404.
 *
 * GET /api/files/{id}             브라우저에서 바로 보기
 * GET /api/files/{id}?download=1  내려받기
 */
@WebServlet("/api/files/*")
public class FileServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    private final transient AttachmentService service = new AttachmentService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = pathParts(req);
        if (p.length != 1) {
            notFound();
        }
        AttachmentService.Download file = service.open(requireId(p[0], "파일 번호"), loginUser(req).getEmpId());
        String name = file.attachment().originName();
        String ascii = name.replaceAll("[^A-Za-z0-9._-]", "_");
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        boolean download = "1".equals(req.getParameter("download"));
        resp.setContentType(file.contentType());
        resp.setContentLengthLong(Files.size(file.path()));
        resp.setHeader("Content-Disposition",
                (download ? "attachment" : "inline") + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded);
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Cache-Control", "private, no-store");
        try (OutputStream out = resp.getOutputStream()) {
            Files.copy(file.path(), out);
        }
    }
}
