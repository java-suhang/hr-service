package com.hr.d.integration;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;

/** Bridge to A's EmployeeService.create. The caller owns the connection and transaction. */
@FunctionalInterface
public interface EmployeeCreator {
    long create(Connection conn, Employee employee, BigDecimal annualDays) throws SQLException;

    /**
     * 감사 로그(REQ-21)에 남길 관리자 ID를 함께 받는다. 기본 구현은 관리자 ID 없이 3개 인자 버전을 부른다.
     * 기존 어댑터·테스트용 가짜 어댑터는 고치지 않아도 된다.
     */
    default long create(Connection conn, Employee employee, BigDecimal annualDays, long actorId) throws SQLException {
        return create(conn, employee, annualDays);
    }

    record Employee(String empNo, String loginId, String initialPassword, String name,
                    String email, String phone, long deptId, long posId, Long managerId,
                    LocalDate hireDate) {
        @Override public String toString() {
            return "Employee[redacted]";
        }
    }
}
