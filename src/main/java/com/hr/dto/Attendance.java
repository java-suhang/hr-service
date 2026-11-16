package com.hr.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 일별 근태 (ATTENDANCE).
 * workMinutes, overtimeMinutes는 테이블 컬럼이 아니라 Service가 계산해 채우는 응답용 값이다.
 */
public class Attendance {

    public static final String NORMAL = "NORMAL";
    public static final String LATE = "LATE";
    public static final String EARLY_LEAVE = "EARLY_LEAVE";
    public static final String ABSENT = "ABSENT";
    public static final String LEAVE = "LEAVE";

    private long attId;
    private long empId;
    private LocalDate workDate;
    private LocalDateTime checkIn;
    private LocalDateTime checkOut;
    private String status;

    // 계산 값
    private Integer workMinutes;
    private Integer overtimeMinutes;

    public long getAttId() {
        return attId;
    }

    public void setAttId(long attId) {
        this.attId = attId;
    }

    public long getEmpId() {
        return empId;
    }

    public void setEmpId(long empId) {
        this.empId = empId;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public void setWorkDate(LocalDate workDate) {
        this.workDate = workDate;
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

    public Integer getOvertimeMinutes() {
        return overtimeMinutes;
    }

    public void setOvertimeMinutes(Integer overtimeMinutes) {
        this.overtimeMinutes = overtimeMinutes;
    }
}
