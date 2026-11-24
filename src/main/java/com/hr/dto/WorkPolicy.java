package com.hr.dto;

import java.time.LocalTime;

/** 근무 규칙 (WORK_POLICY). 시각은 "HH:mm" 문자열로 저장한다. */
public class WorkPolicy {

    private long policyId;
    private String startTime;
    private String endTime;
    private int lateGraceMin;
    private int weeklyLimitHours;

    /** 테이블에 행이 없을 때 쓰는 기본값: 09:00~18:00, 지각 유예 10분, 주 52시간 */
    public static WorkPolicy defaults() {
        WorkPolicy p = new WorkPolicy();
        p.setStartTime("09:00");
        p.setEndTime("18:00");
        p.setLateGraceMin(10);
        p.setWeeklyLimitHours(52);
        return p;
    }

    public LocalTime startLocalTime() {
        return LocalTime.parse(startTime);
    }

    public LocalTime endLocalTime() {
        return LocalTime.parse(endTime);
    }

    public long getPolicyId() {
        return policyId;
    }

    public void setPolicyId(long policyId) {
        this.policyId = policyId;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public int getLateGraceMin() {
        return lateGraceMin;
    }

    public void setLateGraceMin(int lateGraceMin) {
        this.lateGraceMin = lateGraceMin;
    }

    public int getWeeklyLimitHours() {
        return weeklyLimitHours;
    }

    public void setWeeklyLimitHours(int weeklyLimitHours) {
        this.weeklyLimitHours = weeklyLimitHours;
    }
}
