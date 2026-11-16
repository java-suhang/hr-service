/*
 * 근태·휴가 화면 공통 (라벨, 배지, 시간 표시). api.js 다음에 불러온다.
 */
(function () {
    const ATTENDANCE_STATUS = {
        NORMAL: ['정상', 'green'],
        LATE: ['지각', 'red'],
        EARLY_LEAVE: ['조퇴', 'orange'],
        ABSENT: ['결근', 'gray'],
        LEAVE: ['휴가', 'purple'],
    };

    const LEAVE_TYPES = {
        ANNUAL: '연차',
        HALF_AM: '오전 반차',
        HALF_PM: '오후 반차',
        SICK: '병가',
        ETC: '기타',
    };

    const LEAVE_STATUS = {
        PENDING: ['결재 중', 'blue'],
        APPROVED: ['승인', 'green'],
        REJECTED: ['반려', 'red'],
        CANCELED: ['취소', 'gray'],
    };

    const DOW = ['일', '월', '화', '수', '목', '금', '토'];

    function badge(label, color) {
        return `<span class="badge badge-${color}">${esc(label)}</span>`;
    }

    /** 분 → "48h", "3.5h" */
    function hours(minutes) {
        if (minutes === null || minutes === undefined) return '-';
        return Math.round((minutes / 60) * 10) / 10 + 'h';
    }

    /** "YYYY-MM-DDTHH:mm" → "HH:mm" */
    function hhmm(dateTime) {
        return dateTime ? dateTime.slice(11, 16) : '-';
    }

    /** "YYYY-MM-DD" → "2026.11.09 (월)" */
    function dateLabel(isoDate) {
        const [y, m, d] = isoDate.split('-').map(Number);
        const dow = DOW[new Date(y, m - 1, d).getDay()];
        return `${y}.${String(m).padStart(2, '0')}.${String(d).padStart(2, '0')} (${dow})`;
    }

    /** "YYYY-MM-DD" → "11.14 (금)" */
    function shortDate(isoDate) {
        const [y, m, d] = isoDate.split('-').map(Number);
        return `${String(m).padStart(2, '0')}.${String(d).padStart(2, '0')} (${DOW[new Date(y, m - 1, d).getDay()]})`;
    }

    /** 근태 상태 배지. 기록이 없으면 미출근, 오늘 출근만 했으면 근무 중. */
    function attendanceBadge(status, checkIn, checkOut, isToday) {
        if (!status) return badge('미출근', 'gray');
        if (status === 'NORMAL' && checkIn && !checkOut && isToday) return badge('근무 중', 'blue');
        const [label, color] = ATTENDANCE_STATUS[status] || [status, 'gray'];
        return badge(label, color);
    }

    /** 주 한도 임박·초과 배지 */
    function weeklyBadge(level, limitHours) {
        if (level === 'WARN') return badge(`${limitHours}h 임박`, 'orange');
        if (level === 'OVER') return badge(`${limitHours}h 초과`, 'red');
        return '';
    }

    function leaveStatusBadge(status) {
        const [label, color] = LEAVE_STATUS[status] || [status, 'gray'];
        return badge(label, color);
    }

    /** 0.5 단위 일수 표시: 11.5 → "11.5일" */
    function days(n) {
        return `${Number(n)}일`;
    }

    window.HR = {
        ATTENDANCE_STATUS,
        LEAVE_TYPES,
        badge,
        hours,
        hhmm,
        dateLabel,
        shortDate,
        attendanceBadge,
        weeklyBadge,
        leaveStatusBadge,
        days,
    };
})();
