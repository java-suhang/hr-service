/* A02 직원 관리: 검색·등록·수정·퇴사·비밀번호 초기화 */
(function () {
    Layout.admin('employees');
    const $ = (id) => document.getElementById(id);
    const STATUS = { ACTIVE: ['재직', 'badge-green'], LEAVE: ['휴직', 'badge-orange'], RESIGNED: ['퇴사', 'badge-gray'] };
    const state = { page: 1, editing: null, options: { departments: [], positions: [], employees: [] } };
    let me = null;

    const opt = (value, label, selected) =>
        `<option value="${esc(value)}"${String(value) === String(selected) ? ' selected' : ''}>${esc(label)}</option>`;
    const maskPhone = (p) => { if (!p) return '-'; const s = p.split('-'); return s.length === 3 ? `${s[0]}-****-${s[2]}` : p; };

    async function loadOptions() {
        state.options = await api.get('admin/employees/options');
        const { departments, positions } = state.options;
        $('fDept').innerHTML = opt('', '부서 전체') + departments.map((d) => opt(d.deptId, d.name)).join('');
        $('fPos').innerHTML = opt('', '직급 전체') + positions.map((p) => opt(p.posId, p.name)).join('');
    }

    async function load() {
        const params = { keyword: $('fKeyword').value.trim(), deptId: $('fDept').value, posId: $('fPos').value,
            status: $('fStatus').value, page: state.page, size: 15 };
        try {
            const data = await api.get('admin/employees', params);
            if (!data.items.length && state.page > 1) { state.page -= 1; return load(); }
            $('subText').textContent = `검색 결과 ${data.total}명`;
            $('rows').innerHTML = data.items.length ? data.items.map(row).join('')
                : '<tr><td class="empty" colspan="11">조건에 맞는 직원이 없습니다</td></tr>';
            renderPager($('pager'), data, (p) => { state.page = p; load(); });
        } catch (e) {
            $('rows').innerHTML = `<tr><td class="empty" colspan="11">${esc(e.message)}</td></tr>`;
        }
    }

    function row(e) {
        const [label, cls] = STATUS[e.status] || [e.status, 'badge-gray'];
        const self = me && me.empId === e.empId;
        return `<tr>
            <td>${esc(e.empNo)}</td><td><strong>${esc(e.name)}</strong></td><td>${esc(e.loginId)}</td>
            <td>${esc(e.deptName || '-')}</td><td>${esc(e.posName || '-')}</td><td>${esc(e.managerName || '-')}</td>
            <td>${e.role === 'ADMIN' ? '<span class="badge badge-purple">ADMIN</span>' : 'USER'}</td>
            <td>${esc(e.hireDate)}</td><td>${esc(maskPhone(e.phone))}</td>
            <td><span class="badge ${cls}">${label}</span></td>
            <td style="white-space:nowrap;text-align:right">
                <button class="link-btn" data-act="edit" data-id="${e.empId}">수정</button>
                <button class="link-btn" data-act="pw" data-id="${e.empId}" data-name="${esc(e.name)}" style="margin-left:8px">비밀번호</button>
                ${e.status !== 'RESIGNED' && !self ? `<button class="link-btn danger" data-act="resign" data-id="${e.empId}" data-name="${esc(e.name)}" style="margin-left:8px">퇴사</button>` : ''}
            </td></tr>`;
    }

    // ------------------------------------------------------------ 등록 · 수정

    function openForm(e) {
        state.editing = e;
        const { departments, positions, employees } = state.options;
        $('empTitle').textContent = e ? `직원 수정 · ${e.name}` : '직원 등록';
        $('accountRow').hidden = !!e;
        $('annualField').hidden = !!e;
        $('eEmpNo').value = e ? e.empNo : '';
        $('eName').value = e ? e.name : '';
        $('eLoginId').value = '';
        $('ePassword').value = '';
        $('eDept').innerHTML = opt('', '선택') + departments.map((d) => opt(d.deptId, d.name, e && e.deptId)).join('');
        $('ePos').innerHTML = opt('', '선택') + positions.map((p) => opt(p.posId, p.name, e && e.posId)).join('');
        $('eManager').innerHTML = opt('', '없음') + employees.filter((m) => !e || m.empId !== e.empId)
            .map((m) => opt(m.empId, `${m.name} · ${m.deptName || '-'} ${m.posName || ''}`, e && e.managerId)).join('');
        $('eHire').value = e ? e.hireDate : new Date().toISOString().slice(0, 10);
        $('eEmail').value = e ? (e.email || '') : '';
        $('ePhone').value = e ? (e.phone || '') : '';
        $('eRole').value = e ? e.role : 'USER';
        $('eStatus').value = e ? e.status : 'ACTIVE';
        $('eAnnual').value = '15';
        $('empError').textContent = '';
        $('empModal').showModal();
    }

    $('empForm').addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const body = {
            empNo: $('eEmpNo').value.trim(), name: $('eName').value.trim(), deptId: $('eDept').value,
            posId: $('ePos').value, managerId: $('eManager').value, hireDate: $('eHire').value,
            email: $('eEmail').value.trim(), phone: $('ePhone').value.trim(), role: $('eRole').value, status: $('eStatus').value,
        };
        try {
            if (state.editing) {
                await api.put(`admin/employees/${state.editing.empId}`, body);
                toast('저장했습니다');
            } else {
                Object.assign(body, { loginId: $('eLoginId').value.trim(), password: $('ePassword').value, annualDays: $('eAnnual').value });
                await api.post('admin/employees', body);
                toast('직원을 등록했습니다');
            }
            $('empModal').close();
            await loadOptions();
            load();
        } catch (err) {
            $('empError').textContent = err.message;
        }
    });

    // ------------------------------------------------------------ 목록 버튼

    $('rows').addEventListener('click', async (ev) => {
        const b = ev.target.closest('button[data-act]');
        if (!b) return;
        const id = b.dataset.id;
        if (b.dataset.act === 'edit') {
            try { openForm(await api.get(`admin/employees/${id}`)); } catch (err) { toast(err.message, 'error'); }
        } else if (b.dataset.act === 'pw') {
            state.pwTarget = id;
            $('pwWho').textContent = `${b.dataset.name}님의 비밀번호를 새로 정합니다`;
            $('pwNew').value = '';
            $('pwError').textContent = '';
            $('pwModal').showModal();
        } else if (b.dataset.act === 'resign') {
            if (!confirm(`${b.dataset.name}님을 퇴사 처리할까요? 로그인이 막히고 기록은 남습니다.`)) return;
            try {
                await api.post(`admin/employees/${id}/resign`);
                toast('퇴사 처리했습니다');
                await loadOptions();
                load();
            } catch (err) { toast(err.message, 'error'); }
        }
    });

    $('pwForm').addEventListener('submit', async (ev) => {
        ev.preventDefault();
        try {
            await api.post(`admin/employees/${state.pwTarget}/password`, { password: $('pwNew').value });
            $('pwModal').close();
            toast('비밀번호를 초기화했습니다');
        } catch (err) { $('pwError').textContent = err.message; }
    });

    document.querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', () => $(b.dataset.close).close()));
    $('openCreate').addEventListener('click', () => openForm(null));
    let timer = null;
    $('fKeyword').addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => { state.page = 1; load(); }, 300); });
    ['fDept', 'fPos', 'fStatus'].forEach((id) => $(id).addEventListener('change', () => { state.page = 1; load(); }));

    api.get('auth/me').then((m) => { me = m; }).catch(() => {})
        .then(loadOptions).catch((e) => toast(e.message, 'error'))
        .then(load);
})();
