package com.hr.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.hr.dto.Attendance;
import com.hr.dto.WorkPolicy;

/**
 * 근태 판정 규칙 (REQ-16). DB를 쓰지 않는 계산만 모았다.
 *
 *  - 지각: 출근 시각이 (출근 기준 + 지각 유예)를 넘김. 예) 09:00 + 10분 → 09:10:01부터 지각
 *  - 조퇴: 퇴근 기준 시각 전에 퇴근
 *  - 초과근무: 퇴근 기준 시각 이후 근무한 시간
 *  - 근무시간: 체류시간 - 휴게시간 (체류 4시간 30분 이상 30분, 9시간 이상 1시간)
 *  - 주간 누적: 월~일 근무시간 합. (주 한도 - 4시간) 이상이면 임박, 한도를 넘으면 초과
 */
public final class AttendanceRules {

    /** 주 한도에서 이만큼 남았을 때부터 임박 경고 */
    public static final int WEEKLY_WARN_MARGIN_HOURS = 4;

    public static final String WEEKLY_OK = "OK";
    public static final String WEEKLY_WARN = "WARN";
    public static final String WEEKLY_OVER = "OVER";

    private AttendanceRules() {
    }

    public static boolean isLate(LocalDateTime checkIn, WorkPolicy policy) {
        LocalDateTime limit = checkIn.toLocalDate().atTime(policy.startLocalTime())
                .plusMinutes(policy.getLateGraceMin());
        return checkIn.isAfter(limit);
    }

    public static boolean isEarlyLeave(LocalDate workDate, LocalDateTime checkOut, WorkPolicy policy) {
        return checkOut.isBefore(workDate.atTime(policy.endLocalTime()));
    }

    /** 출퇴근 시각으로 상태를 정한다. 지각과 조퇴가 겹치면 지각. 출근 기록이 없으면 결근. */
    public static String judge(LocalDate workDate, LocalDateTime checkIn, LocalDateTime checkOut, WorkPolicy policy) {
        if (checkIn == null) {
            return Attendance.ABSENT;
        }
        if (isLate(checkIn, policy)) {
            return Attendance.LATE;
        }
        if (checkOut != null && isEarlyLeave(workDate, checkOut, policy)) {
            return Attendance.EARLY_LEAVE;
        }
        return Attendance.NORMAL;
    }

    /** 휴게시간을 뺀 근무시간(분). 출퇴근 중 하나라도 없으면 0. */
    public static int workedMinutes(LocalDateTime checkIn, LocalDateTime checkOut) {
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) {
            return 0;
        }
        long stay = Duration.between(checkIn, checkOut).toMinutes();
        long rest = stay >= 9 * 60 ? 60 : stay >= 4 * 60 + 30 ? 30 : 0;
        return (int) (stay - rest);
    }

    /** 퇴근 기준 시각 이후 근무한 시간(분) */
    public static int overtimeMinutes(LocalDate workDate, LocalDateTime checkOut, WorkPolicy policy) {
        if (checkOut == null) {
            return 0;
        }
        LocalDateTime end = workDate.atTime(policy.endLocalTime());
        return checkOut.isAfter(end) ? (int) Duration.between(end, checkOut).toMinutes() : 0;
    }

    /** 주간 누적이 한도에 얼마나 가까운지: OK / WARN / OVER */
    public static String weeklyLevel(int weeklyMinutes, WorkPolicy policy) {
        int limit = policy.getWeeklyLimitHours() * 60;
        if (weeklyMinutes > limit) {
            return WEEKLY_OVER;
        }
        if (weeklyMinutes >= limit - WEEKLY_WARN_MARGIN_HOURS * 60) {
            return WEEKLY_WARN;
        }
        return WEEKLY_OK;
    }

    /** date가 속한 주의 월요일 */
    public static LocalDate weekStart(LocalDate date) {
        return date.with(DayOfWeek.MONDAY);
    }

    public static boolean isWeekday(LocalDate date) {
        DayOfWeek d = date.getDayOfWeek();
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY;
    }
}
