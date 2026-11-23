package com.hr.service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hr.dao.ApprovalDAO;
import com.hr.dao.ApprovalDAO.Doc;
import com.hr.dao.ApprovalDAO.Step;
import com.hr.dao.AttachmentDAO;
import com.hr.dao.EmployeeDAO;
import com.hr.dao.ExpenseDAO;
import com.hr.dao.LeaveBalanceDAO;
import com.hr.dao.LeaveRequestDAO;
import com.hr.dto.Employee;
import com.hr.dto.LeaveBalance;
import com.hr.dto.LeaveRequest;
import com.hr.util.ApiException;
import com.hr.util.DBUtil;

/**
 * 결재 엔진 (REQ-13, 17~19).
 *
 * 규칙
 *  - 결재선: 기안자의 상사 → 인사 관리자. 상사가 없거나 재직 중이 아니면 인사 관리자 1단계,
 *    상사가 관리자 권한이면 상사 1단계로 끝난다 (같은 사람이 두 번 결재하지 않게).
 *  - 인사 관리자 단계는 재직 중인 관리자 누구나 처리하고, 처리한 사람이 결재자로 남는다.
 *  - 지금 단계의 결재자만 승인·반려하고, 기안자 본인은 어느 단계도 처리할 수 없다. 반려는 의견이 필수다.
 *  - 한 단계라도 반려하면 문서는 바로 끝난다. 최종 승인 때 문서 종류별 후처리를 같은 트랜잭션에서 한 번 실행한다.
 *  - 각 단계 결과는 기안자에게, 다음 차례는 결재자에게 알림으로 간다.
 *
 * 다른 도메인이 부르는 메서드 (호출하는 쪽 트랜잭션 conn에 합류):
 *  - submit : B의 휴가 신청, C의 경비 정산 요청
 *  - cancel : 기안자가 신청을 취소할 때 (진행 중인 문서만)
 */
public class ApprovalService {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELED = "CANCELED";
    public static final String ROLE_MANAGER = "MANAGER";
    public static final String ROLE_HR = "HR";

    private static final Set<String> DOC_TYPES = Set.of(LeaveService.DOC_TYPE, ExpenseService.DOC_TYPE);
    private static final Set<String> BOXES = Set.of("mine", "todo", "done");
    private static final Set<String> ADMIN_STATUSES = Set.of("TODO", IN_PROGRESS, APPROVED, REJECTED, CANCELED);
    private static final int TITLE_MAX = 200;
    private static final int COMMENT_MAX = 500;

    private final ApprovalDAO dao = new ApprovalDAO();
    private final EmployeeDAO employeeDAO = new EmployeeDAO();
    private final NotificationService notifier = new NotificationService();
    private final AuditService audit = new AuditService();

    /** 결재선의 한 단계. 인사 관리자 단계는 approverId가 없다 */
    public record LineStep(String role, Long approverId, String approverName) {
    }

    /** 요청한 사람 (권한은 세션이 아니라 DB의 현재 값으로 판단한다) */
    record Actor(long empId, String name, boolean active, boolean admin) {
    }

    // ================================================================== 결재선 · 상신 · 취소

    /** 기안자의 결재선. 결재할 사람이 없으면 409 */
    public List<LineStep> line(Connection conn, long drafterId) throws SQLException {
        Employee drafter = employeeDAO.findById(conn, drafterId);
        if (drafter == null) {
            throw ApiException.notFound("직원을 찾을 수 없습니다");
        }
        return line(drafter, drafter.getManagerId() == null ? null : employeeDAO.findById(conn, drafter.getManagerId()),
                dao.activeAdmins(conn, drafterId).size());
    }

    /** 결재선 규칙 (화면 미리보기와 상신이 같은 규칙을 쓴다). otherAdmins: 기안자를 뺀 재직 관리자 수 */
    static List<LineStep> line(Employee drafter, Employee manager, int otherAdmins) {
        List<LineStep> steps = new ArrayList<>();
        boolean managerIsAdmin = false;
        if (manager != null && manager.getEmpId() != drafter.getEmpId() && Employee.ACTIVE.equals(manager.getStatus())) {
            steps.add(new LineStep(ROLE_MANAGER, manager.getEmpId(), manager.getName()));
            managerIsAdmin = "ADMIN".equals(manager.getRole());
        }
        if (!managerIsAdmin) {
            if (otherAdmins == 0) {
                throw ApiException.conflict("결재할 인사 관리자가 없습니다. 다른 관리자를 지정한 뒤 다시 신청하세요");
            }
            steps.add(new LineStep(ROLE_HR, null, null));
        }
        return steps;
    }

    /**
     * 결재 상신. 결재 문서와 결재선을 만들고 첫 결재자에게 알린다.
     * 같은 원본 문서로 두 번 올리면 UK_APPROVAL_DOC_REF 위반(409)이 난다.
     *
     * @return 생성된 doc_id
     */
    public long submit(Connection conn, String docType, long refId, long drafterId, String title)
            throws SQLException {
        if (!DOC_TYPES.contains(docType)) {
            throw new IllegalArgumentException("알 수 없는 결재 문서 종류: " + docType);
        }
        String t = title == null || title.isBlank() ? docType : NotificationService.cut(title.strip(), TITLE_MAX);
        List<LineStep> line = line(conn, drafterId);
        long docId = dao.insertDoc(conn, docType, refId, drafterId, t);
        for (int i = 0; i < line.size(); i++) {
            LineStep s = line.get(i);
            dao.insertStep(conn, docId, i + 1, s.role(), s.approverId(), i == 0 ? "PENDING" : "WAITING");
        }
        Employee drafter = employeeDAO.findById(conn, drafterId);
        notifyTurn(conn, docId, line.get(0).role(), line.get(0).approverId(), drafterId, drafter.getName(), t);
        return docId;
    }

    /**
     * 기안자가 진행 중인 결재 문서를 취소한다 (휴가 신청 취소 REQ-11, 경비 취소).
     * 결재 문서가 없으면(결재 엔진 도입 전 신청) 할 일이 없다.
     */
    public void cancel(Connection conn, String docType, long refId, long drafterId) throws SQLException {
        Long docId = dao.lockByRef(conn, docType, refId);
        if (docId == null) {
            return;
        }
        Doc doc = dao.find(conn, docId);
        if (doc.drafterId() != drafterId) {
            throw ApiException.forbidden("본인이 올린 문서만 취소할 수 있습니다");
        }
        if (!IN_PROGRESS.equals(doc.status())) {
            throw ApiException.conflict("결재가 끝난 문서는 취소할 수 없습니다");
        }
        dao.finish(conn, docId, CANCELED);
    }

    // ================================================================== 승인 · 반려

    public Map<String, Object> approve(long docId, long actorId, String comment) {
        return act(docId, actorId, true, comment);
    }

    public Map<String, Object> reject(long docId, long actorId, String comment) {
        return act(docId, actorId, false, comment);
    }

    private Map<String, Object> act(long docId, long actorId, boolean approve, String comment) {
        String note = comment == null || comment.isBlank() ? null : comment.strip();
        if (!approve && note == null) {
            throw ApiException.badRequest("반려할 때는 의견을 입력해야 합니다");
        }
        if (note != null && note.length() > COMMENT_MAX) {
            throw ApiException.badRequest("의견은 " + COMMENT_MAX + "자까지 입력할 수 있습니다");
        }
        return DBUtil.tx(conn -> {
            // 잠금 순서: 원본(휴가·경비) → 결재 문서. 기안자 취소도 같은 순서라 서로 기다리다 멈추지 않는다
            Doc peek = dao.find(conn, docId);
            if (peek == null) {
                throw ApiException.notFound("결재 문서를 찾을 수 없습니다");
            }
            dao.lockRef(conn, peek.docType(), peek.refId());
            dao.lock(conn, docId);
            Doc doc = dao.find(conn, docId);
            Actor actor = actor(conn, actorId);
            if (!canView(conn, doc, actor)) {
                throw ApiException.notFound("결재 문서를 찾을 수 없습니다");
            }
            if (!IN_PROGRESS.equals(doc.status())) {
                throw ApiException.conflict("이미 처리가 끝난 문서입니다");
            }
            List<Step> steps = dao.steps(conn, docId);
            Step current = current(doc, steps);
            if (current == null || !canAct(doc, current, actor)) {
                throw ApiException.forbidden(actor.empId() == doc.drafterId()
                        ? "본인이 올린 문서는 결재할 수 없습니다" : "지금 단계의 결재자가 아닙니다");
            }

            dao.actStep(conn, current.stepId(), approve ? APPROVED : REJECTED, actorId, note);
            String link = "/user/approvals.html?id=" + docId;
            String status;
            if (!approve) {
                status = REJECTED;
                dao.finish(conn, docId, REJECTED);
                afterRejected(conn, doc);
                notifier.send(conn, doc.drafterId(), NotificationService.REJECTED,
                        actor.name() + "님이 반려했습니다 · " + doc.title() + " (" + note + ")", link);
            } else if (current.order() < steps.size()) {
                status = IN_PROGRESS;
                Step next = steps.get(current.order());
                dao.moveTo(conn, docId, next.order());
                notifier.send(conn, doc.drafterId(), NotificationService.APPROVAL_STEP,
                        actor.name() + "님이 승인했습니다 (" + current.order() + "/" + steps.size() + ") · " + doc.title(), link);
                notifyTurn(conn, docId, next.role(), next.approverId(), doc.drafterId(), doc.drafterName(), doc.title());
            } else {
                status = APPROVED;
                dao.finish(conn, docId, APPROVED);
                afterApproved(conn, doc);
                notifier.send(conn, doc.drafterId(), NotificationService.APPROVED,
                        "최종 승인되었습니다 · " + doc.title(), link);
            }

            Map<String, Object> before = new LinkedHashMap<>();
            before.put("status", doc.status());
            before.put("step", doc.currentStep());
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", status);
            after.put("step", current.order());
            after.put("role", current.role());
            after.put("comment", note);
            audit.log(conn, actorId, approve ? AuditService.APPROVE : AuditService.REJECT, "APPROVAL_DOC", docId,
                    before, after);
            return detail(conn, dao.find(conn, docId), actor);
        });
    }

    /** 문서 종류별 후처리. 서비스끼리 필드로 서로 만들면 생성자가 무한히 돌므로 그때그때 만든다 */
    private static void afterApproved(Connection conn, Doc doc) throws SQLException {
        if (LeaveService.DOC_TYPE.equals(doc.docType())) {
            new LeaveService().onApproved(conn, doc.refId());
        } else {
            new ExpenseService().onApproved(conn, doc.refId());
        }
    }

    private static void afterRejected(Connection conn, Doc doc) throws SQLException {
        if (LeaveService.DOC_TYPE.equals(doc.docType())) {
            new LeaveService().onRejected(conn, doc.refId());
        } else {
            new ExpenseService().onRejected(conn, doc.refId());
        }
    }

    /** 지금 차례인 결재자에게 알린다. 인사 단계는 기안자를 뺀 재직 관리자 전원 */
    private void notifyTurn(Connection conn, long docId, String role, Long approverId, long drafterId,
            String drafterName, String title) throws SQLException {
        String message = drafterName + "님이 결재를 요청했습니다 · " + title;
        if (ROLE_MANAGER.equals(role)) {
            notifier.send(conn, approverId, NotificationService.APPROVAL_REQUEST, message,
                    "/user/approvals.html?box=todo&id=" + docId);
        } else {
            for (long adminId : dao.activeAdmins(conn, drafterId)) {
                notifier.send(conn, adminId, NotificationService.APPROVAL_REQUEST, message,
                        "/admin/approvals.html?id=" + docId);
            }
        }
    }

    // ================================================================== 조회

    /** 결재함. box = mine(내가 올린) | todo(내가 처리할) | done(내가 처리한). {items, total, page, size, counts} */
    public Map<String, Object> box(long actorId, String box, int page, int size) {
        if (!BOXES.contains(box)) {
            throw ApiException.badRequest("결재함 종류가 올바르지 않습니다 (mine, todo, done)");
        }
        return DBUtil.read(conn -> {
            Actor actor = actor(conn, actorId);
            int offset = (page - 1) * size;
            int total;
            List<Doc> items;
            switch (box) {
                case "mine" -> {
                    total = dao.countMine(conn, actorId);
                    items = dao.findMine(conn, actorId, offset, size);
                }
                case "todo" -> {
                    total = actor.active() ? dao.countTodo(conn, actorId, actor.admin()) : 0;
                    items = actor.active() ? dao.findTodo(conn, actorId, actor.admin(), offset, size) : List.of();
                }
                default -> {
                    total = dao.countDone(conn, actorId);
                    items = dao.findDone(conn, actorId, offset, size);
                }
            }
            Map<String, Object> m = AttendanceService.pageResult(items, total, page, size);
            m.put("box", box);
            m.put("counts", counts(conn, actor));
            return m;
        });
    }

    /** {mine, todo, done} — 결재함 탭·사이드바 숫자 */
    public Map<String, Object> counts(long actorId) {
        return DBUtil.read(conn -> counts(conn, actor(conn, actorId)));
    }

    private Map<String, Object> counts(Connection conn, Actor actor) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mine", dao.countMine(conn, actor.empId()));
        m.put("todo", actor.active() ? dao.countTodo(conn, actor.empId(), actor.admin()) : 0);
        m.put("done", dao.countDone(conn, actor.empId()));
        return m;
    }

    /** 결재 관리 (관리자). status = TODO(내 차례, 기본) | IN_PROGRESS | APPROVED | REJECTED | CANCELED | null(전체) */
    public Map<String, Object> adminList(long actorId, String status, String docType, String keyword, int page, int size) {
        if (status != null && !ADMIN_STATUSES.contains(status)) {
            throw ApiException.badRequest("상태 값이 올바르지 않습니다");
        }
        if (docType != null && !DOC_TYPES.contains(docType)) {
            throw ApiException.badRequest("문서 종류가 올바르지 않습니다");
        }
        ApprovalDAO.Filter filter = new ApprovalDAO.Filter(status, docType, keyword, actorId);
        return DBUtil.read(conn -> {
            int total = dao.countAll(conn, filter);
            List<Doc> items = dao.findAll(conn, filter, (page - 1) * size, size);
            Map<String, Object> m = AttendanceService.pageResult(items, total, page, size);
            m.put("counts", counts(conn, actor(conn, actorId)));
            return m;
        });
    }

    /** 문서 상세. 기안자·결재자·관리자만 볼 수 있다 (그 밖에는 404) */
    public Map<String, Object> detail(long docId, long actorId) {
        return DBUtil.read(conn -> {
            Doc doc = dao.find(conn, docId);
            Actor actor = actor(conn, actorId);
            if (doc == null || !canView(conn, doc, actor)) {
                throw ApiException.notFound("결재 문서를 찾을 수 없습니다");
            }
            return detail(conn, doc, actor);
        });
    }

    /** 볼 수 있는 문서 한 건 (없거나 권한이 없으면 404) */
    public Doc doc(long docId, long actorId) {
        return DBUtil.read(conn -> {
            Doc doc = dao.find(conn, docId);
            if (doc == null || !canView(conn, doc, actor(conn, actorId))) {
                throw ApiException.notFound("결재 문서를 찾을 수 없습니다");
            }
            return doc;
        });
    }

    /** 원본 문서로 결재 문서 ID를 찾는다 (휴가·경비 화면에서 결재함으로 이동할 때) */
    public Long findDocId(String docType, long refId) {
        return DBUtil.read(conn -> dao.findIdByRef(conn, docType, refId));
    }

    private Map<String, Object> detail(Connection conn, Doc doc, Actor actor) throws SQLException {
        List<Step> steps = dao.steps(conn, doc.docId());
        Step current = current(doc, steps);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("doc", doc);
        m.put("steps", steps);
        m.put("detail", LeaveService.DOC_TYPE.equals(doc.docType()) ? leaveDetail(conn, doc) : expenseDetail(conn, doc));
        m.put("canAct", current != null && canAct(doc, current, actor));
        m.put("canCancel", doc.drafterId() == actor.empId() && IN_PROGRESS.equals(doc.status()));
        return m;
    }

    private static Map<String, Object> leaveDetail(Connection conn, Doc doc) throws SQLException {
        LeaveRequest r = new LeaveRequestDAO().findById(conn, doc.refId(), false);
        Map<String, Object> m = new LinkedHashMap<>();
        if (r == null) {
            return m;
        }
        m.put("leaveType", r.getLeaveType());
        m.put("leaveTypeLabel", LeaveRules.label(r.getLeaveType()));
        m.put("startDate", r.getStartDate());
        m.put("endDate", r.getEndDate());
        m.put("days", r.getDays());
        m.put("reason", r.getReason());
        m.put("status", r.getStatus());
        if (LeaveRules.isDeductible(r.getLeaveType())) {
            LeaveBalance b = new LeaveBalanceDAO().find(conn, r.getEmpId(), r.getStartDate().getYear(), false);
            if (b != null) {
                BigDecimal remaining = b.getRemainingDays();
                m.put("remainingDays", remaining);
                if (LeaveRequest.PENDING.equals(r.getStatus())) {
                    m.put("remainingAfter", remaining.subtract(r.getDays()));
                }
            }
        }
        return m;
    }

    private static Map<String, Object> expenseDetail(Connection conn, Doc doc) throws SQLException {
        ExpenseDAO.Expense e = new ExpenseDAO().find(conn, doc.refId());
        Map<String, Object> m = new LinkedHashMap<>();
        if (e == null) {
            return m;
        }
        m.put("expense", e);
        m.put("categoryLabel", ExpenseService.label(e.category()));
        m.put("attachments", new AttachmentDAO().findByRef(conn, AttachmentService.REF_EXPENSE, e.expId()).stream()
                .map(AttachmentService.FileInfo::of).toList());
        return m;
    }

    // ================================================================== 권한

    private Actor actor(Connection conn, long empId) throws SQLException {
        Employee e = employeeDAO.findById(conn, empId);
        if (e == null) {
            throw ApiException.forbidden("직원 정보를 찾을 수 없습니다");
        }
        boolean active = Employee.ACTIVE.equals(e.getStatus());
        return new Actor(empId, e.getName(), active, active && "ADMIN".equals(e.getRole()));
    }

    private boolean canView(Connection conn, Doc doc, Actor actor) throws SQLException {
        return doc.drafterId() == actor.empId() || actor.admin() || dao.isApprover(conn, doc.docId(), actor.empId());
    }

    private static Step current(Doc doc, List<Step> steps) {
        for (Step s : steps) {
            if (s.order() == doc.currentStep()) {
                return s;
            }
        }
        return null;
    }

    /** 지금 단계를 처리할 수 있는지 (ApprovalDAO.ACTIONABLE과 같은 규칙) */
    static boolean canAct(Doc doc, Step step, Actor actor) {
        if (!IN_PROGRESS.equals(doc.status()) || !"PENDING".equals(step.status())) {
            return false;
        }
        if (!actor.active() || actor.empId() == doc.drafterId()) {
            return false;
        }
        if (ROLE_HR.equals(step.role())) {
            return actor.admin();
        }
        if (step.approverId() != null && step.approverId() == actor.empId()) {
            return true;
        }
        return actor.admin() && !Employee.ACTIVE.equals(step.approverStatus());
    }

    // ================================================================== 결재선 설정 화면 (관리자)

    /**
     * 재직자의 결재선 목록. {items, total, page, size, admins}
     * items: {empId, empNo, name, deptName, posName, role, managerId, managerName, steps, note, blocked}
     */
    public Map<String, Object> lines(String keyword, Long deptId, int page, int size) {
        EmployeeDAO.Filter filter = new EmployeeDAO.Filter(keyword, deptId, null, Employee.ACTIVE);
        return DBUtil.read(conn -> {
            int total = employeeDAO.count(conn, filter);
            List<Employee> employees = employeeDAO.search(conn, filter, (page - 1) * size, size);
            Map<Long, Employee> everyone = new HashMap<>();
            for (Employee e : employeeDAO.findNotResigned(conn)) {
                everyone.put(e.getEmpId(), e);
            }
            List<Map<String, Object>> admins = new ArrayList<>();
            for (Employee e : everyone.values()) {
                if ("ADMIN".equals(e.getRole()) && Employee.ACTIVE.equals(e.getStatus())) {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("empId", e.getEmpId());
                    a.put("name", e.getName());
                    a.put("deptName", e.getDeptName());
                    admins.add(a);
                }
            }
            admins.sort((x, y) -> Long.compare((Long) x.get("empId"), (Long) y.get("empId")));

            List<Map<String, Object>> items = new ArrayList<>();
            for (Employee e : employees) {
                Employee manager = e.getManagerId() == null ? null : everyone.get(e.getManagerId());
                int otherAdmins = (int) admins.stream().filter(a -> (Long) a.get("empId") != e.getEmpId()).count();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("empId", e.getEmpId());
                row.put("empNo", e.getEmpNo());
                row.put("name", e.getName());
                row.put("deptName", e.getDeptName());
                row.put("posName", e.getPosName());
                row.put("role", e.getRole());
                row.put("managerId", e.getManagerId());
                row.put("managerName", e.getManagerName());
                try {
                    row.put("steps", line(e, manager, otherAdmins));
                    row.put("blocked", false);
                } catch (ApiException blocked) {
                    row.put("steps", List.of());
                    row.put("blocked", true);
                }
                row.put("note", note(e, manager));
                items.add(row);
            }
            Map<String, Object> m = AttendanceService.pageResult(items, total, page, size);
            m.put("admins", admins);
            return m;
        });
    }

    private static String note(Employee e, Employee manager) {
        if (e.getManagerId() == null) {
            return "상사가 없어 인사 관리자 1단계로 결재합니다";
        }
        if (manager == null || !Employee.ACTIVE.equals(manager.getStatus())) {
            return "상사가 재직 중이 아니어서 인사 관리자 1단계로 결재합니다";
        }
        if ("ADMIN".equals(manager.getRole())) {
            return "상사가 관리자라 상사 결재로 끝납니다";
        }
        return null;
    }
}
