package com.hr.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 직원 (EMPLOYEE + 부서·직급·상사 이름).
 * 비밀번호 해시는 담지 않는다 (JSON 응답에 나가지 않게).
 */
public class Employee {

    public static final String ACTIVE = "ACTIVE";
    public static final String LEAVE = "LEAVE";
    public static final String RESIGNED = "RESIGNED";

    private long empId;
    private String empNo;
    private String loginId;
    private String name;
    private String email;
    private String phone;
    private Long deptId;
    private String deptName;
    private Long posId;
    private String posName;
    private Long managerId;
    private String managerName;
    private String role;
    private LocalDate hireDate;
    private String status;
    private LocalDateTime updatedAt;

    public long getEmpId() { return empId; }
    public void setEmpId(long empId) { this.empId = empId; }
    public String getEmpNo() { return empNo; }
    public void setEmpNo(String empNo) { this.empNo = empNo; }
    public String getLoginId() { return loginId; }
    public void setLoginId(String loginId) { this.loginId = loginId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }
    public String getDeptName() { return deptName; }
    public void setDeptName(String deptName) { this.deptName = deptName; }
    public Long getPosId() { return posId; }
    public void setPosId(Long posId) { this.posId = posId; }
    public String getPosName() { return posName; }
    public void setPosName(String posName) { this.posName = posName; }
    public Long getManagerId() { return managerId; }
    public void setManagerId(Long managerId) { this.managerId = managerId; }
    public String getManagerName() { return managerName; }
    public void setManagerName(String managerName) { this.managerName = managerName; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public LocalDate getHireDate() { return hireDate; }
    public void setHireDate(LocalDate hireDate) { this.hireDate = hireDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
