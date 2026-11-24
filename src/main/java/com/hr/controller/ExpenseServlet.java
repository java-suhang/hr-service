package com.hr.controller;

import java.io.IOException;

import com.hr.service.AttachmentService;
import com.hr.service.ExpenseService;
import com.hr.util.ApiException;

import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

/**
 * 경비 정산 (REQ-23)
 *
 * GET  /api/expenses?page=&size=   → 내 경비 {items, total, page, size}
 * POST /api/expenses               multipart/form-data: category, useDate, amount, description, receipt(파일)
 *                                  → 경비 + 영수증 + 결재 상신
 * POST /api/expenses/{id}/cancel   결재가 진행 중인 본인 경비 취소
 */
@WebServlet("/api/expenses/*")
@MultipartConfig(fileSizeThreshold = 1024 * 1024, maxFileSize = Multipart.MAX_FILE, maxRequestSize = Multipart.MAX_REQUEST)
public class ExpenseServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    private final transient ExpenseService service = new ExpenseService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (pathParts(req).length != 0) {
            notFound();
        }
        ok(resp, service.myList(loginUser(req).getEmpId(), page(req), size(req)));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = pathParts(req);
        long me = loginUser(req).getEmpId();
        if (p.length == 0) {
            Part receipt = Multipart.file(req, "receipt");
            AttachmentService.Upload upload = AttachmentService.read(receipt, true, "영수증");
            created(resp, service.create(me, text(req.getParameter("category")),
                    requireDate(req.getParameter("useDate"), "사용일"), amount(req.getParameter("amount")),
                    req.getParameter("description"), upload));
        } else if (p.length == 2 && p[1].equals("cancel")) {
            ok(resp, service.cancel(me, requireId(p[0], "경비 번호")));
        } else {
            notFound();
        }
    }

    /** 숫자만 (쉼표는 허용). 비어 있으면 null → 서비스에서 400 */
    private static Long amount(String value) {
        String v = text(value);
        if (v == null) {
            return null;
        }
        v = v.replace(",", "");
        if (!v.matches("[0-9]{1,12}")) {
            throw ApiException.badRequest("금액은 숫자로 입력하세요");
        }
        return Long.parseLong(v);
    }
}
