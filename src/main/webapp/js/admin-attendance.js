/* A04 근태 관리: 일별 근태 표 + 수정, 연차 관리 탭 */
(function () {
    Layout.admin('attendance');

    const $ = (id) => document.getElementById(id);
    const thisYear = new Date().getFullYear();

    const daily = { page: 1, rows: [], date: null, today: null, limitHours: 52 };
    const leave = { page: 1, rows: [] };

    function toIso(d) {
        return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    }

    function debounce(fn, ms) {
        let t = null;
        return () => {
            clearTimeout(t);
            t = setTimeout(fn, ms);
        };
    }

    function setMsg(id, text, warn) {
        $(id).className = 'notice-box' + (warn ? ' warn' : '');
        $(id).textContent = text;
    }

    // ------------------------------------------------------------ 탭

    document.querySelectorAll('.tabs button').forEach((btn) => {
        btn.addEventListener('click', () => {
            const tab = btn.dataset.tab;
            document.querySelectorAll('.tabs button').forEach((b) => b.classList.toggle('active', b === btn));
            $('dailyTab').hidden = tab !== 'daily';
            $('dailyActions').hidden = tab !== 'daily';
            $('leaveTab').hidden = tab !== 'leave';
            $('leaveActions').hidden = tab !== 'leave';
            if (tab === 'daily') {
                loadDaily();
            } else {
                loadLeave();
            }
        });
    });

    document.querySelectorAll('[data-close]').forEach((btn) => {
        btn.addEventListener('click', () => $(btn.dataset.close).close());
    });

    // 부서 목록은 A 담당 API(예상 경로). 없으면 '부서 전체'만 둔다.
    api.get('admin/departments').then((data) => {
        const list = Array.isArray(data) ? data : (data && data.items) || [];
        document.querySelectorAll('.dept-select').forEach((sel) => {
            list.forEach((d) => {
                const opt = document.createElement('option');
                opt.value = d.deptId;
                opt.textContent = d.name;
                sel.appendChild(opt);
            });
        });
    }).catch(() => {});

    // ------------------------------------------------------------ 일별 근태

    function dailyFilters() {
        return {
            date: $('fDate').value,
            deptId: $('fDept').value,
            status: $('fStatus').value,
            q: $('fQuery').value.trim(),
        };
    }

    async function loadDaily() {
        const f = dailyFilters();
        $('exportBtn').href = api.url('admin/attendance/export', f);
        try {
            const data = await api.get('admin/attendance', { ...f, page: daily.page, size: 20 });
            daily.rows = data.items;
            daily.date = data.date;
            daily.today = data.today;
            daily.limitHours = data.policy.weeklyLimitHours;
            if (!$('fDate').value) $('fDate').value = data.date;
            $('subText').textContent = HR.dateLabel(data.date);
            renderDaily();
            renderPager($('dailyPager'), data, (p) => {
                daily.page = p;
                loadDaily();
            });
        } catch (e) {
            toast(e.message, 'error');
        }
    }

    function renderDaily() {
        if (!daily.rows.length) {
            $('dailyRows').innerHTML = '<tr><td class="empty" colspan="8">조건에 맞는 직원이 없습니다</td></tr>';
            return;
        }
        const isToday = daily.date === daily.today;
        $('dailyRows').innerHTML = daily.rows.map((r, i) => {
            const hot = r.weeklyLevel !== 'OK';
            return `
            <tr>
                <td>${esc(r.empName)}</td>
                <td>${esc(r.deptName || '-')}</td>
                <td>${esc(HR.hhmm(r.checkIn))}</td>
                <td>${esc(HR.hhmm(r.checkOut))}</td>
                <td class="num">${esc(HR.hours(r.workMinutes))}</td>
                <td class="num ${hot ? 'weekly-hot' : ''}">${esc(HR.hours(r.weeklyMinutes))}${hot ? ' ▲' : ''}</td>
                <td><div class="status-cell">${HR.attendanceBadge(r.status, r.checkIn, r.checkOut, isToday)}${HR.weeklyBadge(r.weeklyLevel, daily.limitHours)}</div></td>
                <td><button class="link-btn" type="button" data-edit="${i}">수정</button></td>
            </tr>`;
        }).join('');
    }

    $('dailyRows').addEventListener('click', (e) => {
        const btn = e.target.closest('[data-edit]');
        if (btn) openEdit(daily.rows[Number(btn.dataset.edit)]);
    });

    const reloadDailyFromFirst = () => {
        daily.page = 1;
        loadDaily();
    };
    ['fDate', 'fDept', 'fStatus'].forEach((id) => $(id).addEventListener('change', reloadDailyFromFirst));
    $('fQuery').addEventListener('input', debounce(reloadDailyFromFirst, 300));

    // ------------------------------------------------------------ 근태 수정

    let editing = null;

    function openEdit(row) {
        editing = row;
        $('editWho').textContent = `${row.empName} · ${row.deptName || '-'} · ${HR.dateLabel(daily.date)}`;
        $('eIn').value = row.checkIn ? HR.hhmm(row.checkIn) : '';
        $('eOut').value = row.checkOut ? HR.hhmm(row.checkOut) : '';
        // 휴가·결근처럼 시각으로 판정할 수 없는 상태만 그대로 두고, 나머지는 자동 판정
        $('eStatus').value = row.status === 'LEAVE' || row.status === 'ABSENT' ? row.status : '';
        setMsg('editMsg', '퇴근이 출근보다 이르면 다음 날 퇴근으로 저장합니다', false);
        $('editModal').showModal();
    }

    $('editForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        $('editSave').disabled = true;
        try {
            await api.put('admin/attendance', {
                empId: editing.empId,
                workDate: daily.date,
                checkIn: $('eIn').value || null,
                checkOut: $('eOut').value || null,
                status: $('eStatus').value || null,
            });
            $('editModal').close();
            toast('근태를 수정했습니다');
            loadDaily();
        } catch (err) {
            setMsg('editMsg', err.message, true);
        } finally {
            $('editSave').disabled = false;
        }
    });

    // ------------------------------------------------------------ 연차 관리

    for (let y = thisYear + 1; y >= thisYear - 2; y--) {
        const opt = document.createElement('option');
        opt.value = String(y);
        opt.textContent = `${y}년`;
        opt.selected = y === thisYear;
        $('lYear').appendChild(opt);
    }

    function leaveFilters() {
        return { year: $('lYear').value, deptId: $('lDept').value, q: $('lQuery').value.trim() };
    }

    async function loadLeave() {
        const f = leaveFilters();
        $('subText').textContent = `${f.year}년 연차 현황`;
        try {
            const data = await api.get('admin/leave-balances', { ...f, page: leave.page, size: 20 });
            leave.rows = data.items;
            renderLeave();
            renderPager($('leavePager'), data, (p) => {
                leave.page = p;
                loadLeave();
            });
        } catch (e) {
            toast(e.message, 'error');
        }
    }

    function renderLeave() {
        if (!leave.rows.length) {
            $('leaveRows').innerHTML = '<tr><td class="empty" colspan="8">조건에 맞는 직원이 없습니다</td></tr>';
            return;
        }
        $('leaveRows').innerHTML = leave.rows.map((b, i) => `
            <tr>
                <td class="muted">${esc(b.empNo || '')}</td>
                <td>${esc(b.empName)}</td>
                <td>${esc(b.deptName || '-')}</td>
                <td class="num">${b.granted ? esc(Number(b.totalDays)) : HR.badge('미부여', 'gray')}</td>
                <td class="num">${esc(Number(b.usedDays))}</td>
                <td class="num">${esc(Number(b.pendingDays))}</td>
                <td class="num"><strong>${esc(Number(b.remainingDays))}</strong></td>
                <td><button class="link-btn" type="button" data-adjust="${i}">${b.granted ? '조정' : '부여'}</button></td>
            </tr>`).join('');
    }

    $('leaveRows').addEventListener('click', (e) => {
        const btn = e.target.closest('[data-adjust]');
        if (btn) openAdjust(leave.rows[Number(btn.dataset.adjust)]);
    });

    const reloadLeaveFromFirst = () => {
        leave.page = 1;
        loadLeave();
    };
    ['lYear', 'lDept'].forEach((id) => $(id).addEventListener('change', reloadLeaveFromFirst));
    $('lQuery').addEventListener('input', debounce(reloadLeaveFromFirst, 300));

    let adjusting = null;

    function openAdjust(b) {
        adjusting = b;
        $('adjustWho').textContent = `${b.empName} · ${b.year}년 · 사용 ${Number(b.usedDays)}일`;
        $('aTotal').value = b.granted ? Number(b.totalDays) : 15;
        $('aTotal').min = Number(b.usedDays);
        setMsg('adjustMsg', '이미 사용한 일수보다 적게 줄 수 없습니다', false);
        $('adjustModal').showModal();
    }

    $('adjustForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        $('adjustSave').disabled = true;
        try {
            await api.put('admin/leave-balances', {
                empId: adjusting.empId,
                year: adjusting.year,
                totalDays: $('aTotal').value,
            });
            $('adjustModal').close();
            toast('연차를 저장했습니다');
            loadLeave();
        } catch (err) {
            setMsg('adjustMsg', err.message, true);
        } finally {
            $('adjustSave').disabled = false;
        }
    });

    $('openGrant').addEventListener('click', () => {
        $('gYear').value = $('lYear').value;
        $('gDays').value = 15;
        setMsg('grantMsg', '그해 연차가 없는 재직자에게만 부여합니다. 이미 있는 사람은 바뀌지 않습니다.', false);
        $('grantModal').showModal();
    });

    $('grantForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        $('grantSave').disabled = true;
        try {
            const res = await api.post('admin/leave-balances/grant', { year: $('gYear').value, days: $('gDays').value });
            $('grantModal').close();
            toast(`${res.granted}명에게 연차를 부여했습니다`);
            loadLeave();
        } catch (err) {
            setMsg('grantMsg', err.message, true);
        } finally {
            $('grantSave').disabled = false;
        }
    });

    // ------------------------------------------------------------ 시작

    $('fDate').value = toIso(new Date());
    loadDaily();
})();
