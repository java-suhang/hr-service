package com.hr.dto;

import java.io.Serializable;

/**
 * 세션에 저장하는 로그인 사용자 정보 (REQ-01).
 * 세션 키는 "loginUser". B·C·D는 getEmpId(), getName(), isAdmin()을 쓴다.
 */
public class LoginUser implements Serializable {

    private static final long serialVersionUID = 2L;

    public static final String SESSION_KEY = "loginUser";

    private long empId;
    private String loginId;
    private String name;
    private String role;
    private String deptName;
    private String posName;

    public LoginUser() {
    }

    /** 테스트·다른 도메인 호환용 (이름과 권한만 아는 경우) */
    public LoginUser(long empId, String name, String role) {
        this(empId, null, name, role, null, null);
    }

    public LoginUser(long empId, String loginId, String name, String role, String deptName, String posName) {
        this.empId = empId;
        this.loginId = loginId;
        this.name = name;
        this.role = role;
        this.deptName = deptName;
        this.posName = posName;
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    public long getEmpId() {
        return empId;
    }

    public void setEmpId(long empId) {
        this.empId = empId;
    }

    public String getLoginId() {
        return loginId;
    }

    public void setLoginId(String loginId) {
        this.loginId = loginId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getDeptName() {
        return deptName;
    }

    public void setDeptName(String deptName) {
        this.deptName = deptName;
    }

    public String getPosName() {
        return posName;
    }

    public void setPosName(String posName) {
        this.posName = posName;
    }
}
