package com.hr.dto;

import java.math.BigDecimal;

/**
 * 연도별 연차 (LEAVE_BALANCE).
 * pendingDays(결재 대기 중인 차감 예정 일수)와 직원 정보는 조회할 때 채우는 응답용 값이다.
 */
public class LeaveBalance {

    private long empId;
    private int year;
    private BigDecimal totalDays = BigDecimal.ZERO;
    private BigDecimal usedDays = BigDecimal.ZERO;

    // 조회 값
    private BigDecimal pendingDays = BigDecimal.ZERO;
    private boolean granted = true;
    private String empNo;
    private String empName;
    private String deptName;

    /** 잔여 = 부여 - 사용 */
    public BigDecimal getRemainingDays() {
        return totalDays.subtract(usedDays);
    }

    /** 새로 신청할 수 있는 일수 = 잔여 - 결재 대기 */
    public BigDecimal getAvailableDays() {
        return getRemainingDays().subtract(pendingDays);
    }

    public long getEmpId() {
        return empId;
    }

    public void setEmpId(long empId) {
        this.empId = empId;
    }

    public int getYear() {
        return year;
    }

    public void setYear(int year) {
        this.year = year;
    }

    public BigDecimal getTotalDays() {
        return totalDays;
    }

    public void setTotalDays(BigDecimal totalDays) {
        this.totalDays = totalDays;
    }

    public BigDecimal getUsedDays() {
        return usedDays;
    }

    public void setUsedDays(BigDecimal usedDays) {
        this.usedDays = usedDays;
    }

    public BigDecimal getPendingDays() {
        return pendingDays;
    }

    public void setPendingDays(BigDecimal pendingDays) {
        this.pendingDays = pendingDays;
    }

    /** 해당 연도 연차 행이 있는지. 없으면 관리자가 부여해야 한다. */
    public boolean isGranted() {
        return granted;
    }

    public void setGranted(boolean granted) {
        this.granted = granted;
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
}
