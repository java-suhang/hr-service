package com.hr.controller;

import java.io.IOException;
import java.util.Map;

import com.hr.dao.ApprovalDAO;
import com.hr.service.ApprovalService;
import com.hr.service.ExpenseService;
import com.hr.service.LeaveService;
import com.hr.util.ApiException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 결재함 (REQ-17, 18) — 로그인한 직원 누구나. 상사·관리자는 여기서 결재한다.
 *
 * GET  /api/approvals?box=mine|todo|done&page=&size=   → {items, total, page, size, box, counts}
 * GET  /api/approvals/counts                           → {mine, todo, done}
 * GET  /api/approvals/{id}                             → {doc, steps, detail, canAct, canCancel}
 * POST /api/approvals/{id}/approve   {comment}         (의견 선택)
 * POST /api/approvals/{id}/reject    {comment}         (의견 필수)
 * POST /api/approvals/{id}/cancel                      기안자가 진행 중인 문서 취소 (휴가·경비 신청도 함께 취소)
 */
@WebServlet("/api/approvals/*")
public class ApprovalServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    private final transient ApprovalService service = new ApprovalService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = pathParts(req);
        long actor = loginUser(req).getEmpId();
        if (p.length == 0) {
            String box = text(req.getParameter("box"));
            ok(resp, service.box(actor, box == null ? "mine" : box, page(req), size(req)));
        } else if (p.length == 1 && p[0].equals("counts")) {
            ok(resp, service.counts(actor));
        } else if (p.length == 1) {
            ok(resp, service.detail(requireId(p[0], "문서 번호"), actor));
        } else {
            notFound();
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = pathParts(req);
        if (p.length != 2) {
            notFound();
        }
        long actor = loginUser(req).getEmpId();
        long docId = requireId(p[0], "문서 번호");
        switch (p[1]) {
            case "approve" -> ok(resp, service.approve(docId, actor, comment(req)));
            case "reject" -> ok(resp, service.reject(docId, actor, comment(req)));
            case "cancel" -> {
                cancel(docId, actor);
                ok(resp, service.detail(docId, actor));
            }
            default -> notFound();
        }
    }

    private String comment(HttpServletRequest req) throws IOException {
        Map<String, Object> body = readJson(req);
        return raw(body.get("comment"));
    }

    /** 원본 문서 종류에 맞춰 취소한다 (휴가 신청·경비 상태와 결재 문서를 함께) */
    private void cancel(long docId, long actor) {
        ApprovalDAO.Doc doc = service.doc(docId, actor);
        if (doc.drafterId() != actor) {
            throw ApiException.forbidden("본인이 올린 문서만 취소할 수 있습니다");
        }
        if (LeaveService.DOC_TYPE.equals(doc.docType())) {
            new LeaveService().cancel(actor, doc.refId());
        } else {
            new ExpenseService().cancel(actor, doc.refId());
        }
    }
}
