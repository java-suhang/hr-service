/*
 * 공통 레이아웃 (사용자 상단 메뉴, 어드민 사이드바)
 *
 *   <header id="topnav"></header>      → Layout.user('attendance')
 *   <aside id="sidebar"></aside> + <header id="topbar"></header> → Layout.admin('attendance')
 *
 * 메뉴 주소는 피그마 화면 이름을 따른다. 오른쪽 위에 알림 종, 내 정보(이름·부서) 링크와 로그아웃 버튼을 둔다.
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
            ['expenses', '경비 지급', 'expenses.html'],
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

    const BELL_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">'
        + '<path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/></svg>';

    /** 알림 시각: 방금 · N분 전 · N시간 전 · MM.DD */
    function when(value) {
        if (!value) return '';
        const t = new Date(value);
        const diff = (Date.now() - t.getTime()) / 60000;
        if (diff < 1) return '방금';
        if (diff < 60) return Math.floor(diff) + '분 전';
        if (diff < 60 * 24) return Math.floor(diff / 60) + '시간 전';
        return value.slice(5, 10).replace('-', '.');
    }

    /** 알림 링크(/user/..., /admin/...)를 지금 화면 기준 주소로 바꾼다 */
    function appUrl(link) {
        if (!link || !/^\/(user|admin)\/[\w-]+\.html(\?[\w=&.-]*)?$/.test(link)) return null;
        return new URL('..' + link, location.href).href;
    }

    /** 알림 종 (REQ-20): 읽지 않은 알림이 있으면 빨간 점, 누르면 최근 알림 목록 */
    function bell() {
        const wrap = document.createElement('div');
        wrap.className = 'bell-wrap';
        wrap.innerHTML = '<button type="button" class="bell" aria-label="알림" aria-expanded="false" aria-haspopup="true">'
            + BELL_ICON + '<span class="dot" hidden></span></button>'
            + '<div class="noti-panel" role="dialog" aria-label="알림" hidden></div>';
        const button = wrap.querySelector('.bell');
        const dot = wrap.querySelector('.dot');
        const panel = wrap.querySelector('.noti-panel');

        const setUnread = (n) => {
            dot.hidden = !(n > 0);
            button.setAttribute('aria-label', n > 0 ? '알림 (읽지 않음 ' + n + '건)' : '알림');
        };
        const refresh = () => api.get('notifications/unread-count').then((r) => setUnread(r.count)).catch(() => {});

        async function open() {
            panel.hidden = false;
            button.setAttribute('aria-expanded', 'true');
            panel.innerHTML = '<div class="noti-empty">불러오는 중…</div>';
            try {
                const data = await api.get('notifications', { size: 10 });
                setUnread(data.unread);
                panel.innerHTML = '<div class="noti-head"><span>알림</span>'
                    + (data.unread > 0 ? '<button type="button" class="link-btn" data-read-all>모두 읽음</button>' : '')
                    + '</div>'
                    + (data.items.length ? data.items.map((n) => '<button type="button" class="noti-item'
                        + (n.read ? '' : ' unread') + '" data-id="' + n.notiId + '" data-link="' + esc(n.link || '') + '">'
                        + esc(n.message) + '<span class="when">' + esc(when(n.createdAt)) + '</span></button>').join('')
                        : '<div class="noti-empty">새 알림이 없습니다</div>');
            } catch (e) {
                panel.innerHTML = '<div class="noti-empty">' + esc(e.message) + '</div>';
            }
        }
        function close() {
            panel.hidden = true;
            button.setAttribute('aria-expanded', 'false');
        }

        button.addEventListener('click', () => (panel.hidden ? open() : close()));
        panel.addEventListener('click', async (ev) => {
            if (ev.target.closest('[data-read-all]')) {
                await api.post('notifications/read-all').catch(() => {});
                open();
                return;
            }
            const item = ev.target.closest('.noti-item');
            if (!item) return;
            await api.post('notifications/' + item.dataset.id + '/read').catch(() => {});
            const url = appUrl(item.dataset.link);
            if (url) location.href = url;
            else open();
        });
        document.addEventListener('click', (ev) => { if (!wrap.contains(ev.target)) close(); });
        document.addEventListener('keydown', (ev) => { if (ev.key === 'Escape' && !panel.hidden) { close(); button.focus(); } });
        refresh();
        return wrap;
    }

    /** 오른쪽 위: 알림 + 이름·부서 (내 정보로 이동) + 로그아웃. inAdmin이면 사용자 화면 경로로 링크한다. */
    function userChip(inAdmin) {
        const chip = document.createElement('div');
        chip.className = 'user-chip';
        chip.innerHTML = '<a class="me-link" href="' + (inAdmin ? '../user/me.html' : 'me.html') + '">'
            + '<span class="avatar"></span><span class="name"></span></a>'
            + (inAdmin ? '<a class="link-btn" href="../user/home.html">사용자 화면</a>' : '')
            + '<button type="button" class="link-btn" data-logout>로그아웃</button>';
        chip.insertBefore(bell(), chip.firstChild);
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
        // 결재 관리 옆에 내가 처리할 문서 수
        const approvals = aside && aside.querySelector('a[href="approvals.html"]');
        if (approvals) {
            api.get('approvals/counts').then((c) => {
                if (c.todo > 0) {
                    const badge = document.createElement('span');
                    badge.className = 'count';
                    badge.textContent = c.todo > 99 ? '99+' : String(c.todo);
                    badge.setAttribute('aria-label', '처리할 문서 ' + c.todo + '건');
                    approvals.appendChild(badge);
                }
            }).catch(() => {});
        }
    }

    window.Layout = { user, admin };
})();
