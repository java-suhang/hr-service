package com.hr.service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.hr.dao.EmployeeDAO;
import com.hr.dao.OrgDAO;
import com.hr.dto.Employee;
import com.hr.util.ApiException;
import com.hr.util.DBUtil;
import com.hr.util.PasswordUtil;

/**
 * 직원 (REQ-04 등록·수정·퇴사, REQ-05 검색, REQ-07 내 정보·조직도).
 *
 * 다른 도메인이 부르는 메서드 (호출하는 쪽 트랜잭션 conn에 합류):
 *  - create(conn, employee, initialPassword, annualDays) : D의 채용 전환 (EmployeeCreator 어댑터)
 */
public class EmployeeService {

    public static final BigDecimal DEFAULT_ANNUAL_DAYS = new BigDecimal("15");

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Pattern LOGIN_ID = Pattern.compile("^[A-Za-z0-9_.-]{4,30}$");
    private static final Pattern EMP_NO = Pattern.compile("^[A-Za-z0-9-]{1,20}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^[0-9-]{9,20}$");
    private static final int MANAGER_DEPTH_LIMIT = 100;

    private final EmployeeDAO employeeDAO = new EmployeeDAO();
    private final OrgDAO orgDAO = new OrgDAO();
    private final AuditService audit = new AuditService();

    // ================================================================== 조회

    public Map<String, Object> search(EmployeeDAO.Filter filter, int page, int size) {
        return DBUtil.read(conn -> {
            int total = employeeDAO.count(conn, filter);
            List<Employee> items = employeeDAO.search(conn, filter, (page - 1) * size, size);
            return AttendanceService.pageResult(items, total, page, size);
        });
    }

    public Employee get(long empId) {
        Employee e = DBUtil.read(conn -> employeeDAO.findById(conn, empId));
        if (e == null) {
            throw ApiException.notFound("직원을 찾을 수 없습니다");
        }
        return e;
    }

    /** 드롭다운용: 부서, 직급, 재직·휴직 직원 */
    public Map<String, Object> options() {
        return DBUtil.read(conn -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("departments", orgDAO.departments(conn));
            m.put("positions", orgDAO.positions(conn));
            List<Map<String, Object>> people = new ArrayList<>();
            for (Employee e : employeeDAO.findNotResigned(conn)) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("empId", e.getEmpId());
                p.put("name", e.getName());
                p.put("deptName", e.getDeptName());
                p.put("posName", e.getPosName());
                p.put("role", e.getRole());
                people.add(p);
            }
            m.put("employees", people);
            return m;
        });
    }

    /** 조직도: 부서별 재직·휴직 직원. 연락처는 가운데를 가린다 */
    public List<Map<String, Object>> orgChart() {
        return DBUtil.read(conn -> {
            List<Employee> all = employeeDAO.findNotResigned(conn);
            Set<Long> managers = new HashSet<>();
            all.forEach(e -> { if (e.getManagerId() != null) managers.add(e.getManagerId()); });

            Map<String, List<Map<String, Object>>> byDept = new LinkedHashMap<>();
            orgDAO.departments(conn).forEach(d -> byDept.put(d.name(), new ArrayList<>()));
            for (Employee e : all) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("empId", e.getEmpId());
                m.put("name", e.getName());
                m.put("posName", e.getPosName());
                m.put("email", e.getEmail());
                m.put("phone", maskPhone(e.getPhone()));
                m.put("status", e.getStatus());
                m.put("manager", managers.contains(e.getEmpId()));
                byDept.computeIfAbsent(e.getDeptName() == null ? "미배정" : e.getDeptName(), k -> new ArrayList<>()).add(m);
            }
            List<Map<String, Object>> result = new ArrayList<>();
            byDept.forEach((name, members) -> {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("deptName", name);
                d.put("members", members);
                result.add(d);
            });
            return result;
        });
    }

    // ================================================================== 등록

    /** 관리자 화면에서 등록: 직원 + 올해 연차를 한 트랜잭션으로 만든다 */
    public Employee create(Map<String, Object> body, long actorId) {
        Employee e = new Employee();
        e.setLoginId(BaseInput.requireText(body.get("loginId"), "로그인 아이디"));
        fill(e, body);
        String password = BaseInput.text(body.get("password"));
        BigDecimal annualDays = BaseInput.decimalOr(body.get("annualDays"), "올해 연차", DEFAULT_ANNUAL_DAYS);

        return DBUtil.tx(conn -> {
            long id = create(conn, e, password, annualDays);
            Employee created = employeeDAO.findById(conn, id);
            audit.log(conn, actorId, AuditService.CREATE, "EMPLOYEE", id, null, created);
            return created;
        });
    }

    /**
     * 직원 생성 (호출하는 쪽 트랜잭션에 합류). 연차는 올해 기준으로 annualDays만큼 만든다 (퇴사 상태면 만들지 않음).
     * role·status가 비어 있으면 USER·ACTIVE. 검증에 실패하면 ApiException.
     */
    public long create(Connection conn, Employee e, String initialPassword, BigDecimal annualDays) throws SQLException {
        if (e.getLoginId() == null || !LOGIN_ID.matcher(e.getLoginId()).matches()) {
            throw ApiException.badRequest("로그인 아이디는 영문·숫자·_.- 4~30자로 입력하세요");
        }
        PasswordUtil.validatePolicy(initialPassword);
        if (e.getRole() == null) e.setRole("USER");
        if (e.getStatus() == null) e.setStatus(Employee.ACTIVE);
        validate(e);
        if (employeeDAO.exists(conn, "login_id", e.getLoginId(), null)) {
            throw ApiException.conflict("이미 사용 중인 로그인 아이디입니다");
        }
        if (employeeDAO.exists(conn, "emp_no", e.getEmpNo(), null)) {
            throw ApiException.conflict("이미 사용 중인 사번입니다");
        }
        checkReferences(conn, e, null);

        long id = employeeDAO.insert(conn, e, PasswordUtil.hash(initialPassword));
        if (!Employee.RESIGNED.equals(e.getStatus())) {
            new LeaveService().grantAnnual(conn, id, LocalDate.now(ZONE).getYear(),
                    annualDays == null ? DEFAULT_ANNUAL_DAYS : annualDays);
        }
        return id;
    }

    // ================================================================== 수정 · 퇴사

    public Employee update(long empId, Map<String, Object> body, long actorId) {
        return DBUtil.tx(conn -> {
            if (!employeeDAO.lock(conn, empId)) {
                throw ApiException.notFound("직원을 찾을 수 없습니다");
            }
            Employee before = employeeDAO.findById(conn, empId);
            Employee e = employeeDAO.findById(conn, empId);
            fill(e, body);
            validate(e);
            guardChange(conn, before, e, actorId);
            if (employeeDAO.exists(conn, "emp_no", e.getEmpNo(), empId)) {
                throw ApiException.conflict("이미 사용 중인 사번입니다");
            }
            checkReferences(conn, e, empId);
            employeeDAO.update(conn, e);
            Employee after = employeeDAO.findById(conn, empId);
            audit.log(conn, actorId, AuditService.UPDATE, "EMPLOYEE", empId, before, after);
            return after;
        });
    }

    /** 상사(결재선 1단계)만 바꾼다. 결재선 설정 화면에서 쓴다. managerId가 null이면 상사 없음 */
    public Employee changeManager(long empId, Long managerId, long actorId) {
        return DBUtil.tx(conn -> {
            if (!employeeDAO.lock(conn, empId)) {
                throw ApiException.notFound("직원을 찾을 수 없습니다");
            }
            Employee before = employeeDAO.findById(conn, empId);
            if (Employee.RESIGNED.equals(before.getStatus())) {
                throw ApiException.conflict("퇴사한 직원의 상사는 바꿀 수 없습니다");
            }
            Employee e = employeeDAO.findById(conn, empId);
            e.setManagerId(managerId);
            checkReferences(conn, e, empId);
            employeeDAO.update(conn, e);
            Employee after = employeeDAO.findById(conn, empId);
            audit.log(conn, actorId, AuditService.UPDATE, "EMPLOYEE", empId, before, after);
            return after;
        });
    }

    /** 삭제 대신 퇴사 처리 (근태·결재 기록 보존) */
    public Employee resign(long empId, long actorId) {
        return DBUtil.tx(conn -> {
            if (!employeeDAO.lock(conn, empId)) {
                throw ApiException.notFound("직원을 찾을 수 없습니다");
            }
            Employee before = employeeDAO.findById(conn, empId);
            if (Employee.RESIGNED.equals(before.getStatus())) {
                throw ApiException.conflict("이미 퇴사 처리된 직원입니다");
            }
            Employee after = employeeDAO.findById(conn, empId);
            after.setStatus(Employee.RESIGNED);
            guardChange(conn, before, after, actorId);
            employeeDAO.updateStatus(conn, empId, Employee.RESIGNED);
            Employee saved = employeeDAO.findById(conn, empId);
            audit.log(conn, actorId, AuditService.RESIGN, "EMPLOYEE", empId, before, saved);
            return saved;
        });
    }

    public void resetPassword(long empId, String newPassword, long actorId) {
        PasswordUtil.validatePolicy(newPassword);
        DBUtil.tx(conn -> {
            if (!employeeDAO.lock(conn, empId)) {
                throw ApiException.notFound("직원을 찾을 수 없습니다");
            }
            employeeDAO.updatePassword(conn, empId, PasswordUtil.hash(newPassword));
            audit.log(conn, actorId, AuditService.PASSWORD_RESET, "EMPLOYEE", empId, null, Map.of("password", "초기화"));
            return null;
        });
    }

    // ================================================================== 검증

    /** 본인 잠금·마지막 관리자·부하 직원이 있는 퇴사를 막는다 */
    private void guardChange(Connection conn, Employee before, Employee after, long actorId) throws SQLException {
        boolean leavesActiveAdmin = "ADMIN".equals(before.getRole()) && Employee.ACTIVE.equals(before.getStatus())
                && (!"ADMIN".equals(after.getRole()) || !Employee.ACTIVE.equals(after.getStatus()));
        if (before.getEmpId() == actorId && leavesActiveAdmin) {
            throw ApiException.badRequest("본인의 관리자 권한이나 재직 상태는 바꿀 수 없습니다");
        }
        if (leavesActiveAdmin && employeeDAO.countActiveAdmins(conn) <= 1) {
            throw ApiException.conflict("재직 중인 관리자가 최소 한 명은 있어야 합니다");
        }
        if (Employee.RESIGNED.equals(after.getStatus()) && !Employee.RESIGNED.equals(before.getStatus())
                && employeeDAO.countSubordinates(conn, before.getEmpId()) > 0) {
            throw ApiException.conflict("이 직원을 상사로 둔 직원이 있습니다. 먼저 그 직원들의 상사를 바꾸세요");
        }
    }

    private void checkReferences(Connection conn, Employee e, Long selfId) throws SQLException {
        if (orgDAO.department(conn, e.getDeptId()) == null) {
            throw ApiException.badRequest("존재하지 않는 부서입니다");
        }
        if (orgDAO.position(conn, e.getPosId()) == null) {
            throw ApiException.badRequest("존재하지 않는 직급입니다");
        }
        if (e.getManagerId() == null) {
            return;
        }
        if (selfId != null && e.getManagerId().longValue() == selfId) {
            throw ApiException.badRequest("자기 자신을 상사로 지정할 수 없습니다");
        }
        Employee m = employeeDAO.findById(conn, e.getManagerId());
        if (m == null || Employee.RESIGNED.equals(m.getStatus())) {
            throw ApiException.badRequest("상사로 지정할 수 없는 직원입니다");
        }
        if (selfId != null) {
            // 상사의 상사를 따라 올라가다 자기 자신이 나오면 순환
            Long cur = e.getManagerId();
            for (int i = 0; cur != null && i < MANAGER_DEPTH_LIMIT; i++) {
                if (cur.longValue() == selfId) {
                    throw ApiException.badRequest("상사 관계가 순환합니다 (부하 직원을 상사로 지정할 수 없음)");
                }
                cur = employeeDAO.findManagerId(conn, cur);
            }
        }
    }

    /** 공통 입력 필드 (로그인 아이디·비밀번호 제외) */
    private static void fill(Employee e, Map<String, Object> body) {
        e.setEmpNo(BaseInput.requireText(body.get("empNo"), "사번"));
        e.setName(BaseInput.requireText(body.get("name"), "이름"));
        e.setEmail(BaseInput.text(body.get("email")));
        e.setPhone(BaseInput.text(body.get("phone")));
        e.setDeptId(BaseInput.requireId(body.get("deptId"), "부서"));
        e.setPosId(BaseInput.requireId(body.get("posId"), "직급"));
        e.setManagerId(BaseInput.optionalId(body.get("managerId"), "상사"));
        e.setRole(BaseInput.text(body.get("role")) == null ? "USER" : BaseInput.text(body.get("role")));
        e.setHireDate(BaseInput.requireDate(body.get("hireDate"), "입사일"));
        e.setStatus(BaseInput.text(body.get("status")) == null ? Employee.ACTIVE : BaseInput.text(body.get("status")));
    }

    private static void validate(Employee e) {
        if (e.getEmpNo() == null || !EMP_NO.matcher(e.getEmpNo()).matches()) {
            throw ApiException.badRequest("사번은 영문·숫자·- 20자 이하로 입력하세요");
        }
        if (e.getName() == null || e.getName().length() > 50) {
            throw ApiException.badRequest("이름은 1~50자로 입력하세요");
        }
        if (e.getEmail() != null && (e.getEmail().length() > 100 || !EMAIL.matcher(e.getEmail()).matches())) {
            throw ApiException.badRequest("이메일 형식이 올바르지 않습니다");
        }
        if (e.getPhone() != null && !PHONE.matcher(e.getPhone()).matches()) {
            throw ApiException.badRequest("연락처는 숫자와 - 로 9~20자 입력하세요");
        }
        if (!"USER".equals(e.getRole()) && !"ADMIN".equals(e.getRole())) {
            throw ApiException.badRequest("권한은 USER 또는 ADMIN입니다");
        }
        if (!List.of(Employee.ACTIVE, Employee.LEAVE, Employee.RESIGNED).contains(e.getStatus())) {
            throw ApiException.badRequest("상태 값이 올바르지 않습니다");
        }
        if (e.getDeptId() == null || e.getPosId() == null || e.getHireDate() == null) {
            throw ApiException.badRequest("부서·직급·입사일을 입력하세요");
        }
    }

    /** 010-1234-5678 → 010-****-5678 (NFR-06) */
    static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        String[] p = phone.split("-");
        return p.length == 3 ? p[0] + "-****-" + p[2] : "****";
    }
}
