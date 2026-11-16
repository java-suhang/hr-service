package com.hr.dto;

import java.io.Serializable;

/**
 * 세션에 저장하는 로그인 사용자 정보.
 *
 * [임시] 로그인(REQ-01)은 A 담당이다. A가 세션 키와 클래스를 확정하면 그쪽에 맞춘다.
 */
public class LoginUser implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String SESSION_KEY = "loginUser";

    private long empId;
    private String name;
    private String role;

    public LoginUser() {
    }

    public LoginUser(long empId, String name, String role) {
        this.empId = empId;
        this.name = name;
        this.role = role;
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
}
