package com.hr.listener;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;

import com.hr.d.integration.EmployeeCreator;
import com.hr.d.integration.NoticeNotifier;
import com.hr.dto.Employee;
import com.hr.service.EmployeeService;
import com.hr.service.NotificationService;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

/**
 * 도메인 간 연결 어댑터를 ServletContext에 등록한다.
 *  - EmployeeCreator (D 채용 전환 → A 직원 생성 + B 연차 생성). 등록하지 않으면 D의 전환 API는 503.
 *  - NoticeNotifier  (D 공지 등록 → C 알림). 등록하지 않으면 D는 공지만 저장한다.
 * 어댑터는 받은 Connection(호출하는 쪽 트랜잭션)을 그대로 쓰고, 커밋·종료는 호출자가 한다.
 */
@WebListener
public class AppContextListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent event) {
        event.getServletContext().setAttribute(EmployeeCreator.class.getName(), employeeCreator());
        event.getServletContext().setAttribute(NoticeNotifier.class.getName(), noticeNotifier());
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        event.getServletContext().removeAttribute(EmployeeCreator.class.getName());
        event.getServletContext().removeAttribute(NoticeNotifier.class.getName());
    }

    static NoticeNotifier noticeNotifier() {
        return new NotificationService()::send;
    }

    static EmployeeCreator employeeCreator() {
        EmployeeService service = new EmployeeService();
        return new EmployeeCreator() {
            @Override
            public long create(Connection conn, EmployeeCreator.Employee in, BigDecimal annualDays)
                    throws SQLException {
                return service.create(conn, toEmployee(in), in.initialPassword(), annualDays, null);
            }

            /** 채용 전환을 한 관리자 ID를 감사 로그(REQ-21)에 남긴다 */
            @Override
            public long create(Connection conn, EmployeeCreator.Employee in, BigDecimal annualDays, long actorId)
                    throws SQLException {
                return service.create(conn, toEmployee(in), in.initialPassword(), annualDays, actorId);
            }
        };
    }

    private static Employee toEmployee(EmployeeCreator.Employee in) {
        Employee e = new Employee();
        e.setEmpNo(in.empNo());
        e.setLoginId(in.loginId());
        e.setName(in.name());
        e.setEmail(in.email());
        e.setPhone(in.phone());
        e.setDeptId(in.deptId());
        e.setPosId(in.posId());
        e.setManagerId(in.managerId());
        e.setHireDate(in.hireDate());
        e.setRole("USER");
        e.setStatus(Employee.ACTIVE);
        return e;
    }
}
