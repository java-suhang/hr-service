/*
 * 공통 레이아웃 (사용자 상단 메뉴, 어드민 사이드바)
 *
 *   <header id="topnav"></header>      → Layout.user('attendance')
 *   <aside id="sidebar"></aside> + <header id="topbar"></header> → Layout.admin('attendance')
 *
 * 메뉴 주소는 피그마 화면 이름을 따른다. 오른쪽 위에 내 정보(이름·부서) 링크와 로그아웃 버튼을 둔다.
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

    /** 오른쪽 위: 이름·부서 (내 정보로 이동) + 로그아웃. inAdmin이면 사용자 화면 경로로 링크한다. */
    function userChip(inAdmin) {
        const chip = document.createElement('div');
        chip.className = 'user-chip';
        chip.innerHTML = '<a class="me-link" href="' + (inAdmin ? '../user/me.html' : 'me.html') + '">'
            + '<span class="avatar"></span><span class="name"></span></a>'
            + (inAdmin ? '<a class="link-btn" href="../user/home.html">사용자 화면</a>' : '')
            + '<button type="button" class="link-btn" data-logout>로그아웃</button>';
        api.get('auth/me').then((me) => {
            if (!me) return;
            if (!inAdmin && me.admin) {
                const a = document.createElement('a');
                a.className = 'link-btn';
                a.href = '../admin/dashboard.html';
                a.textContent = '관리자 화면';
                chip.insertBefore(a, chip.querySelector('[data-logout]'));
            }
            const dept = me.deptName ? ' · ' + me.deptName : '';
            chip.querySelector('.name').textContent = (me.name || '') + dept;
            chip.querySelector('.avatar').textContent = (me.name || '?').charAt(0);
        }).catch(() => {});
        chip.querySelector('[data-logout]').addEventListener('click', async () => {
            try { await api.post('auth/logout'); } catch (e) { /* 이미 로그아웃 */ }
            location.href = new URL('../login.html', location.href).href;
        });
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
        header.appendChild(userChip(false));
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
            topbar.appendChild(userChip(true));
        }
    }

    window.Layout = { user, admin };
})();
