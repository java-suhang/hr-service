/* 내 정보 · 비밀번호 변경 · 조직도 */
(function () {
    Layout.user(null);
    const $ = (id) => document.getElementById(id);
    const STATUS = { ACTIVE: '재직', LEAVE: '휴직', RESIGNED: '퇴사' };

    async function loadMe() {
        try {
            const me = await api.get('me');
            const rows = [
                ['사번', me.empNo], ['이름', me.name], ['아이디', me.loginId],
                ['부서', me.deptName || '-'], ['직급', me.posName || '-'], ['상사', me.managerName || '-'],
                ['입사일', me.hireDate], ['상태', STATUS[me.status] || me.status],
                ['이메일', me.email || '-'], ['연락처', me.phone || '-'],
                ['권한', me.role === 'ADMIN' ? '관리자' : '사용자'],
            ];
            $('info').innerHTML = rows.map(([k, v]) => `<div><dt>${esc(k)}</dt><dd>${esc(v)}</dd></div>`).join('');
        } catch (e) {
            toast(e.message, 'error');
        }
    }

    async function loadOrg() {
        try {
            const depts = await api.get('org');
            const total = depts.reduce((n, d) => n + d.members.length, 0);
            $('orgSub').textContent = `${depts.length}개 부서 · ${total}명`;
            $('org').innerHTML = depts.map((d) => `
                <div class="card" style="padding:16px">
                    <strong>${esc(d.deptName)}</strong> <span class="sub">${d.members.length}명</span>
                    <div style="margin-top:8px">${d.members.length ? d.members.map((m) => `
                        <div class="org-member">
                            <span class="avatar" style="width:28px;height:28px;border-radius:50%;background:#c7d2fe;display:inline-flex;align-items:center;justify-content:center;font-weight:700;font-size:12px">${esc((m.name || '?').charAt(0))}</span>
                            <div style="flex:1;min-width:0">
                                <div><strong>${esc(m.name)}</strong> <span class="sub">${esc(m.posName || '')}</span>
                                    ${m.manager ? '<span class="badge badge-blue">팀장</span>' : ''}
                                    ${m.status === 'LEAVE' ? '<span class="badge badge-orange">휴직</span>' : ''}</div>
                                <div class="sub">${esc(m.email || '')}${m.phone ? ' · ' + esc(m.phone) : ''}</div>
                            </div>
                        </div>`).join('') : '<div class="sub">소속 직원이 없습니다</div>'}</div>
                </div>`).join('');
        } catch (e) {
            toast(e.message, 'error');
        }
    }

    $('openPw').addEventListener('click', () => {
        $('pwForm').reset();
        $('pwError').textContent = '';
        $('pwModal').showModal();
    });
    document.querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', () => $(b.dataset.close).close()));
    $('pwForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        if ($('pwNew').value !== $('pwConfirm').value) {
            $('pwError').textContent = '새 비밀번호가 서로 다릅니다';
            return;
        }
        try {
            await api.post('auth/password', { currentPassword: $('pwCurrent').value, newPassword: $('pwNew').value });
            $('pwModal').close();
            toast('비밀번호를 바꿨습니다');
        } catch (err) {
            $('pwError').textContent = err.message;
        }
    });

    loadMe();
    loadOrg();
})();
