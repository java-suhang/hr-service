import {api, content, dateTime, h, init, labels, metric, panel, table} from './ui.js';

const grid = (...children) => h('div', {className: 'grid'}, children);
const status = (data, key, text) => data.unavailable.includes(key) ? '연동 필요' : text;

function userDashboard(data) {
    const attendance = data.attendance;
    const work = attendance
        ? `출근 ${attendance.checkIn ?? '—'} · 퇴근 ${attendance.checkOut ?? '—'}`
        : '기록 없음';
    const approvals = data.approvals?.find(row => row.status === 'IN_PROGRESS')?.count ?? 0;
    content.append(grid(
        metric('오늘 근무', status(data, 'attendance', work)),
        metric('잔여 연차', status(data, 'leave', data.leave ? `${data.leave.remainingDays}일` : '기록 없음')),
        metric('진행 중인 결재', status(data, 'approvals', `${approvals}건`)),
        metric('읽지 않은 알림', status(data, 'notifications', `${data.unreadNotifications ?? 0}건`))
    ));
    if (attendance?.status) content.append(h('p', {className: 'muted'}, `오늘 근태 상태 · ${labels[attendance.status] ?? attendance.status}`));
    if (data.approvals?.length) {
        content.append(panel('결재 현황', table(['상태', '건수'],
            data.approvals.map(row => [row.status === 'REJECTED' ? '반려' : labels[row.status] ?? row.status, row.count]))));
    }
    content.append(panel('최근 공지', table(['제목', '등록일'], data.notices.map(row => [
        h('a', {href: `notices.html?id=${row.noticeId}`}, row.isPinned === 'Y' && h('span', {className: 'badge'}, '필독') || null, row.title),
        dateTime(row.createdAt)
    ])), h('a', {href: 'notices.html'}, '공지 전체 보기')));
}

function adminDashboard(data) {
    const attendance = status(data, 'attendance', `${data.attendanceRate}% · ${data.checkedIn}명`);
    content.append(grid(metric('재직 인원', `${data.activeEmployees}명`),
        metric('오늘 출근율', attendance), metric('결재 대기', status(data, 'approvals', `${data.pendingApprovals}건`))),
    panel('부서별 현황', table(['부서', '재직', '출근'],
        data.departments.map(row => [row.name, row.activeEmployees, status(data, 'attendance', row.checkedIn)]))));
    const recruiting = data.recruitment;
    const evaluation = data.evaluation;
    content.append(panel('채용 진행', grid(metric('모집 공고', recruiting.openPostings),
        metric('면접 단계', recruiting.interviewApplicants), metric('합격 단계', recruiting.offerApplicants)),
    h('a', {href: 'recruit.html'}, '채용 관리')));
    content.append(panel('성과 평가', evaluation
        ? h('div', {}, h('p', {}, `${evaluation.name} · ${labels[evaluation.status]}`),
            grid(metric('자기평가 제출', evaluation.selfSubmitted), metric('상사평가 제출', evaluation.managerSubmitted)))
        : h('p', {className: 'muted'}, '진행 중인 평가 기간이 없습니다.'), h('a', {href: 'evaluations.html'}, '평가 관리')));
}

init(async admin => {
    const data = await api(admin ? 'admin/dashboard' : 'dashboard');
    if (admin) adminDashboard(data);
    else userDashboard(data);
});
