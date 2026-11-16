/* U03 내 근태: 오늘 출퇴근, 월간 요약, 캘린더 */
(function () {
    Layout.user('attendance');

    const $ = (id) => document.getElementById(id);

    /** 캘린더에 '초과 Nh'로 보여줄 최소 초과근무(분) */
    const OVERTIME_BADGE_MIN = 30;

    let month = null; // 'YYYY-MM'

    // ------------------------------------------------------------ 월간

    async function loadMonth(target) {
        try {
            const data = await api.get('attendance', { month: target });
            month = data.month;
            renderHeader(data);
            renderStats(data.summary);
            renderCalendar(data);
        } catch (e) {
            toast(e.message, 'error');
        }
    }

    function renderHeader(data) {
        const [y, m] = data.month.split('-').map(Number);
        $('monthLabel').textContent = `${y}년 ${m}월`;
        const p = data.policy;
        $('policyText').textContent = `근무 규칙 ${p.startTime}–${p.endTime} · 지각 유예 ${p.lateGraceMin}분`;
    }

    function renderStats(s) {
        $('statDays').textContent = `${s.workDays}일`;
        $('statWork').textContent = HR.hours(s.workMinutes);
        $('statOver').textContent = HR.hours(s.overtimeMinutes);
        $('statLate').textContent = `${s.lateCount} / ${s.earlyLeaveCount}`;
    }

    function renderCalendar(data) {
        const byDate = {};
        data.days.forEach((d) => { byDate[d.workDate] = d; });

        const [y, m] = data.month.split('-').map(Number);
        const firstDow = new Date(y, m - 1, 1).getDay();
        const lastDay = new Date(y, m, 0).getDate();

        const cells = ['일', '월', '화', '수', '목', '금', '토'].map((d) => `<div class="cal-dow">${d}</div>`);
        for (let i = 0; i < firstDow; i++) cells.push('<div class="cal-cell out"></div>');

        for (let day = 1; day <= lastDay; day++) {
            const iso = `${data.month}-${String(day).padStart(2, '0')}`;
            const dow = (firstDow + day - 1) % 7;
            const cls = ['cal-cell'];
            if (iso === data.today) cls.push('today');
            if (dow === 0) cls.push('sun');
            if (dow === 6) cls.push('sat');

            const rec = byDate[iso];
            let body = '';
            if (rec) {
                body += cellBadge(rec, iso === data.today);
                if (rec.checkIn) {
                    body += `<div class="cal-time">${HR.hhmm(rec.checkIn)}–${rec.checkOut ? HR.hhmm(rec.checkOut) : ''}</div>`;
                }
            }
            cells.push(`<div class="${cls.join(' ')}"><div class="cal-day">${day}</div>${body}</div>`);
        }
        $('calendar').innerHTML = cells.join('');
    }

    /** 피그마처럼 초과근무가 있는 정상 근무일은 '초과 Nh'로 표시 */
    function cellBadge(rec, isToday) {
        if (rec.status === 'NORMAL' && rec.overtimeMinutes >= OVERTIME_BADGE_MIN) {
            return HR.badge(`초과 ${HR.hours(rec.overtimeMinutes)}`, 'orange');
        }
        return HR.attendanceBadge(rec.status, rec.checkIn, rec.checkOut, isToday);
    }

    function shiftMonth(delta) {
        if (!month) return;
        const [y, m] = month.split('-').map(Number);
        const d = new Date(y, m - 1 + delta, 1);
        loadMonth(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`);
    }

    // ------------------------------------------------------------ 오늘

    async function loadToday() {
        try {
            renderToday(await api.get('attendance/today'));
        } catch (e) {
            $('todayText').textContent = '오늘 근태를 불러오지 못했습니다';
            toast(e.message, 'error');
        }
    }

    function renderToday(t) {
        const a = t.attendance;
        const onLeave = a && a.status === 'LEAVE';
        let text;
        if (onLeave) {
            text = '오늘은 휴가입니다';
        } else if (!a || !a.checkIn) {
            text = '아직 출근 전입니다';
        } else {
            text = `출근 ${HR.hhmm(a.checkIn)} · 퇴근 ${a.checkOut ? HR.hhmm(a.checkOut) : '-'}`;
        }
        const statusBadge = a && (a.checkIn || onLeave) ? ' ' + HR.attendanceBadge(a.status, a.checkIn, a.checkOut, true) : '';
        $('todayText').innerHTML = esc(text) + statusBadge;

        $('weeklyText').innerHTML = `이번 주 ${esc(HR.hours(t.weeklyMinutes))} / ${esc(t.policy.weeklyLimitHours)}h `
            + HR.weeklyBadge(t.weeklyLevel, t.policy.weeklyLimitHours);

        // 새벽에는 어제 퇴근하지 않은 기록을 닫을 수 있으므로 퇴근 버튼을 열어 둔다
        const earlyMorning = Number(t.now.slice(11, 13)) < 6;
        $('checkInBtn').disabled = Boolean(onLeave || (a && a.checkIn));
        $('checkOutBtn').disabled = !((a && a.checkIn && !a.checkOut) || (!a && earlyMorning));
    }

    async function punch(path, message) {
        $('checkInBtn').disabled = true;
        $('checkOutBtn').disabled = true;
        try {
            renderToday(await api.post(path));
            toast(message);
            loadMonth(month);
        } catch (e) {
            toast(e.message, 'error');
            loadToday();
        }
    }

    // ------------------------------------------------------------ 시작

    $('prevMonth').addEventListener('click', () => shiftMonth(-1));
    $('nextMonth').addEventListener('click', () => shiftMonth(1));
    $('checkInBtn').addEventListener('click', () => punch('attendance/check-in', '출근을 기록했습니다'));
    $('checkOutBtn').addEventListener('click', () => punch('attendance/check-out', '퇴근을 기록했습니다'));

    loadToday();
    loadMonth();
})();
