package com.hr.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.hr.dao.EmployeeDAO;
import com.hr.dao.EmployeeDAO.Credential;
import com.hr.dto.Employee;
import com.hr.dto.LoginUser;
import com.hr.util.ApiException;
import com.hr.util.DBUtil;
import com.hr.util.PasswordUtil;

/**
 * 로그인 (REQ-01) · 비밀번호 변경 (REQ-03).
 *  - 아이디가 없을 때와 비밀번호가 틀릴 때 같은 메시지·비슷한 시간으로 응답한다
 *  - 같은 아이디로 10분 안에 5번 틀리면 5분 동안 로그인을 막는다
 *  - 퇴사자는 로그인할 수 없다
 */
public class AuthService {

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Duration LOCK = Duration.ofMinutes(5);
    /** 없는 아이디도 해시 검증 시간을 쓰게 하는 더미 값 */
    private static final String DUMMY_HASH = PasswordUtil.hash("dummy-password-0");

    private static final Map<String, Attempts> ATTEMPTS = new ConcurrentHashMap<>();

    private record Attempts(int failures, Instant first, Instant lockedUntil) {}

    private final EmployeeDAO employeeDAO = new EmployeeDAO();

    public LoginUser login(String loginId, String password) {
        if (loginId == null || password == null) {
            throw ApiException.badRequest("아이디와 비밀번호를 입력하세요");
        }
        String key = loginId.toLowerCase(Locale.ROOT);
        Attempts a = ATTEMPTS.get(key);
        Instant now = Instant.now();
        if (a != null && a.lockedUntil() != null && now.isBefore(a.lockedUntil())) {
            throw new ApiException(429, "로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요");
        }

        Employee e = DBUtil.read(conn -> {
            Credential c = employeeDAO.findCredential(conn, loginId);
            boolean ok = PasswordUtil.verify(password, c == null ? DUMMY_HASH : c.passwordHash());
            if (!ok || c == null) {
                return null;
            }
            return employeeDAO.findById(conn, c.empId());
        });

        if (e == null) {
            recordFailure(key, now);
            throw new ApiException(401, "아이디 또는 비밀번호가 올바르지 않습니다");
        }
        ATTEMPTS.remove(key);
        if (Employee.RESIGNED.equals(e.getStatus())) {
            throw ApiException.forbidden("퇴사 처리된 계정입니다");
        }
        return toLoginUser(e);
    }

    /** 세션 정보를 DB 기준으로 다시 만든다 (이름·부서가 바뀐 경우). 퇴사했거나 없으면 null */
    public LoginUser refresh(long empId) {
        Employee e = DBUtil.read(conn -> employeeDAO.findById(conn, empId));
        return e == null || Employee.RESIGNED.equals(e.getStatus()) ? null : toLoginUser(e);
    }

    public void changePassword(long empId, String current, String next) {
        if (current == null) {
            throw ApiException.badRequest("현재 비밀번호를 입력하세요");
        }
        PasswordUtil.validatePolicy(next);
        if (current.equals(next)) {
            throw ApiException.badRequest("새 비밀번호가 현재 비밀번호와 같습니다");
        }
        DBUtil.tx(conn -> {
            employeeDAO.lock(conn, empId);
            String hash = employeeDAO.findPasswordHash(conn, empId);
            if (!PasswordUtil.verify(current, hash)) {
                throw ApiException.badRequest("현재 비밀번호가 올바르지 않습니다");
            }
            employeeDAO.updatePassword(conn, empId, PasswordUtil.hash(next));
            new AuditService().log(conn, empId, AuditService.UPDATE, "EMPLOYEE", empId, null,
                    Map.of("password", "본인 변경"));
            return null;
        });
    }

    static LoginUser toLoginUser(Employee e) {
        return new LoginUser(e.getEmpId(), e.getLoginId(), e.getName(), e.getRole(), e.getDeptName(), e.getPosName());
    }

    private static void recordFailure(String key, Instant now) {
        ATTEMPTS.compute(key, (k, a) -> {
            if (a == null || Duration.between(a.first(), now).compareTo(WINDOW) > 0) {
                return new Attempts(1, now, null);
            }
            int failures = a.failures() + 1;
            return new Attempts(failures, a.first(), failures >= MAX_FAILURES ? now.plus(LOCK) : null);
        });
        if (ATTEMPTS.size() > 10_000) {
            // 오래된 기록 정리 (메모리 보호)
            ATTEMPTS.entrySet().removeIf(en -> Duration.between(en.getValue().first(), now).compareTo(WINDOW) > 0);
        }
    }
}
