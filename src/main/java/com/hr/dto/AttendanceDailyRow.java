package com.hr.dto;

import java.time.LocalDateTime;

/**
 * 관리자 일자별 근태 표(A04)의 한 행.
 * 근태 기록이 없는 직원도 나오므로 attId, checkIn, status는 비어 있을 수 있다 (status null = 미출근).
 */
public class AttendanceDailyRow {

    private long empId;
    private String empNo;
    private String empName;
    private String deptName;

    private Long attId;
    private LocalDateTime checkIn;
    private LocalDateTime checkOut;
    private String status;

    // 계산 값
    private Integer workMinutes;
    private int weeklyMinutes;
    /** OK, WARN(주 한도 임박), OVER(주 한도 초과) */
    private String weeklyLevel;

    public long getEmpId() {
        return empId;
    }

    public void setEmpId(long empId) {
        this.empId = empId;
    }

    public String getEmpNo() {
        return empNo;
    }

    public void setEmpNo(String empNo) {
        this.empNo = empNo;
    }

    public String getEmpName() {
        return empName;
    }

    public void setEmpName(String empName) {
        this.empName = empName;
    }

    public String getDeptName() {
        return deptName;
    }

    public void setDeptName(String deptName) {
        this.deptName = deptName;
    }

    public Long getAttId() {
        return attId;
    }

    public void setAttId(Long attId) {
        this.attId = attId;
    }

    public LocalDateTime getCheckIn() {
        return checkIn;
    }

    public void setCheckIn(LocalDateTime checkIn) {
        this.checkIn = checkIn;
    }

    public LocalDateTime getCheckOut() {
        return checkOut;
    }

    public void setCheckOut(LocalDateTime checkOut) {
        this.checkOut = checkOut;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getWorkMinutes() {
        return workMinutes;
    }

    public void setWorkMinutes(Integer workMinutes) {
        this.workMinutes = workMinutes;
    }

    public int getWeeklyMinutes() {
        return weeklyMinutes;
    }

    public void setWeeklyMinutes(int weeklyMinutes) {
        this.weeklyMinutes = weeklyMinutes;
    }

    public String getWeeklyLevel() {
        return weeklyLevel;
    }

    public void setWeeklyLevel(String weeklyLevel) {
        this.weeklyLevel = weeklyLevel;
    }
}
