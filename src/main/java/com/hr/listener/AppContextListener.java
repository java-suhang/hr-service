package com.hr.listener;

import java.math.BigDecimal;
import java.sql.Connection;

import com.hr.d.integration.EmployeeCreator;
import com.hr.dto.Employee;
import com.hr.service.EmployeeService;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

/**
 * 도메인 간 연결 어댑터를 ServletContext에 등록한다.
 *  - EmployeeCreator (D 채용 전환 → A 직원 생성 + B 연차 생성). 등록하지 않으면 D의 전환 API는 503.
 * 어댑터는 받은 Connection(호출하는 쪽 트랜잭션)을 그대로 쓰고, 커밋·종료는 호출자가 한다.
 */
@WebListener
public class AppContextListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent event) {
        event.getServletContext().setAttribute(EmployeeCreator.class.getName(), employeeCreator());
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        event.getServletContext().removeAttribute(EmployeeCreator.class.getName());
    }

    static EmployeeCreator employeeCreator() {
        EmployeeService service = new EmployeeService();
        return (Connection conn, EmployeeCreator.Employee in, BigDecimal annualDays) -> {
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
            return service.create(conn, e, in.initialPassword(), annualDays);
        };
    }
}
