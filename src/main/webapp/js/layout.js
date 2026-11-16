/*
 * 공통 레이아웃 (사용자 상단 메뉴, 어드민 사이드바) — [임시] A 담당. A의 레이아웃이 들어오면 교체한다.
 *
 *   <header id="topnav"></header>      → Layout.user('attendance')
 *   <aside id="sidebar"></aside> + <header id="topbar"></header> → Layout.admin('attendance')
 *
 * 메뉴 주소는 피그마 화면 이름을 따른 예상 경로다. 다른 담당자 화면이 생기면 맞춘다.
 */
(function () {
    const USER_MENU = [
        ['home', '홈', 'home.html'],
        ['attendance', '근태', 'attendance.html'],
        ['leave', '휴가', 'leave.html'],
        ['approval', '결재', 'approvals.html'],
        ['expense', '경비', 'expenses.html'],
        ['evaluation', '평가', 'evaluation.html'],
        ['notice', '공지', 'notices.html'],
    ];

    const ADMIN_MENU = [
        [null, [['dashboard', '대시보드', 'dashboard.html']]],
        ['인사', [
            ['employees', '직원 관리', 'employees.html'],
            ['departments', '부서·직급', 'departments.html'],
            ['attendance', '근태 관리', 'attendance.html'],
        ]],
        ['결재', [
            ['approvals', '결재 관리', 'approvals.html'],
            ['approval-lines', '결재선 설정', 'approval-lines.html'],
        ]],
        ['성과·채용', [
            ['evaluations', '성과 평가', 'evaluations.html'],
            ['recruit', '채용 관리', 'recruit.html'],
        ]],
        ['운영', [
            ['notices', '공지', 'notices.html'],
            ['audit', '감사 로그', 'audit.html'],
            ['work-policy', '근무 규칙', 'work-policy.html'],
        ]],
    ];

    function userChip() {
        const chip = document.createElement('div');
        chip.className = 'user-chip';
        chip.innerHTML = '<span class="avatar"></span><span class="name"></span>';
        // 로그인 사용자 정보 API는 A 담당 (예상 경로). 없으면 이름 칸을 비워 둔다.
        api.get('auth/me').then((me) => {
            if (!me) return;
            const dept = me.deptName ? ' · ' + me.deptName : '';
            chip.querySelector('.name').textContent = (me.name || '') + dept;
        }).catch(() => {});
        return chip;
    }

    function user(active) {
        const header = document.getElementById('topnav');
        if (!header) return;
        header.className = 'topnav';
        header.innerHTML = '<a class="logo" href="home.html">HR.</a><nav></nav>';
        const nav = header.querySelector('nav');
        USER_MENU.forEach(([key, label, href]) => {
            const a = document.createElement('a');
            a.href = href;
            a.textContent = label;
            if (key === active) a.className = 'active';
            nav.appendChild(a);
        });
        header.appendChild(userChip());
    }

    function admin(active) {
        const aside = document.getElementById('sidebar');
        if (aside) {
            aside.className = 'sidebar';
            aside.innerHTML = '<div class="logo">HR.<small>Admin</small></div>';
            ADMIN_MENU.forEach(([group, items]) => {
                if (group) {
                    const g = document.createElement('div');
                    g.className = 'group';
                    g.textContent = group;
                    aside.appendChild(g);
                }
                items.forEach(([key, label, href]) => {
                    const a = document.createElement('a');
                    a.href = href;
                    a.textContent = label;
                    if (key === active) a.className = 'active';
                    aside.appendChild(a);
                });
            });
        }
        const topbar = document.getElementById('topbar');
        if (topbar) {
            topbar.className = 'topbar';
            topbar.appendChild(userChip());
        }
    }

    window.Layout = { user, admin };
})();
