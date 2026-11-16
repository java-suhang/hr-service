package com.hr.d.integration;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;

/** Bridge to A's EmployeeService.create. The caller owns the connection and transaction. */
@FunctionalInterface
public interface EmployeeCreator {
    long create(Connection conn, Employee employee, BigDecimal annualDays) throws SQLException;

    record Employee(String empNo, String loginId, String initialPassword, String name,
                    String email, String phone, long deptId, long posId, Long managerId,
                    LocalDate hireDate) {
        @Override public String toString() {
            return "Employee[redacted]";
        }
    }
}
