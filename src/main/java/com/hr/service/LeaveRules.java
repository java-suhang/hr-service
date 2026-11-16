package com.hr.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 휴가 규칙. DB를 쓰지 않는 계산만 모았다.
 *
 *  - 종류: ANNUAL(연차), HALF_AM(오전 반차), HALF_PM(오후 반차), SICK(병가), ETC(기타)
 *  - 일수: 반차 0.5일, 나머지는 기간 중 평일(월~금) 수. 공휴일은 따로 빼지 않는다.
 *  - 연차 차감: 연차·반차만. 병가·기타는 잔여 연차와 상관없다.
 *  - 근태 표시: 하루 단위 휴가(연차·병가·기타)만 그날 근태를 LEAVE로 바꾼다.
 */
public final class LeaveRules {

    public static final String ANNUAL = "ANNUAL";
    public static final String HALF_AM = "HALF_AM";
    public static final String HALF_PM = "HALF_PM";
    public static final String SICK = "SICK";
    public static final String ETC = "ETC";

    public static final Set<String> TYPES = Set.of(ANNUAL, HALF_AM, HALF_PM, SICK, ETC);

    private static final BigDecimal HALF_DAY = new BigDecimal("0.5");

    private LeaveRules() {
    }

    public static boolean isHalfDay(String type) {
        return HALF_AM.equals(type) || HALF_PM.equals(type);
    }

    public static boolean isDeductible(String type) {
        return ANNUAL.equals(type) || isHalfDay(type);
    }

    public static boolean marksAttendance(String type) {
        return !isHalfDay(type);
    }

    /** 신청 일수. 평일이 하루도 없으면 0. */
    public static BigDecimal countDays(String type, LocalDate start, LocalDate end) {
        if (isHalfDay(type)) {
            return AttendanceRules.isWeekday(start) ? HALF_DAY : BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(weekdays(start, end).size());
    }

    /** start~end 중 평일 목록 */
    public static List<LocalDate> weekdays(LocalDate start, LocalDate end) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (AttendanceRules.isWeekday(d)) {
                days.add(d);
            }
        }
        return days;
    }

    /**
     * 기간이 겹치는 두 신청이 함께 있을 수 있는지.
     * 같은 날의 오전 반차와 오후 반차만 함께 있을 수 있다.
     */
    public static boolean canCoexist(String typeA, String typeB) {
        return isHalfDay(typeA) && isHalfDay(typeB) && !typeA.equals(typeB);
    }

    public static String label(String type) {
        return switch (type) {
            case ANNUAL -> "연차";
            case HALF_AM -> "오전 반차";
            case HALF_PM -> "오후 반차";
            case SICK -> "병가";
            default -> "기타 휴가";
        };
    }
}
