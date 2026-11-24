/* U04 휴가: 잔여 연차, 신청 내역, 신청 모달 */
(function () {
    Layout.user('leave');

    const $ = (id) => document.getElementById(id);
    const thisYear = new Date().getFullYear();
    const state = { year: thisYear, page: 1 };

    let previewTimer = null;
    let previewSeq = 0;

    // ------------------------------------------------------------ 목록

    for (let y = thisYear + 1; y >= thisYear - 2; y--) {
        const opt = document.createElement('option');
        opt.value = String(y);
        opt.textContent = `${y}년`;
        opt.selected = y === thisYear;
        $('yearSelect').appendChild(opt);
    }

    async function load() {
        try {
            const data = await api.get('leaves', { year: state.year, page: state.page, size: 10 });
            renderBalance(data.balance, data.year);
            renderRows(data.items);
            renderPager($('pager'), data, (p) => {
                state.page = p;
                load();
            });
        } catch (e) {
            toast(e.message, 'error');
        }
    }

    function renderBalance(b, year) {
        if (!b.granted) {
            $('balanceText').textContent = `${year}년 연차가 아직 부여되지 않았습니다`;
            return;
        }
        let text = `잔여 ${HR.days(b.remainingDays)} · 사용 ${HR.days(b.usedDays)}`;
        if (Number(b.pendingDays) > 0) text += ` · 결재 대기 ${HR.days(b.pendingDays)}`;
        $('balanceText').textContent = text;
    }

    function period(r) {
        if (r.startDate === r.endDate) return HR.shortDate(r.startDate);
        const md = (iso) => iso.slice(5).replace('-', '.');
        return `${md(r.startDate)} ~ ${md(r.endDate)}`;
    }

    function renderRows(items) {
        if (!items.length) {
            $('rows').innerHTML = '<tr><td class="empty" colspan="7">신청 내역이 없습니다</td></tr>';
            return;
        }
        $('rows').innerHTML = items.map((r) => `
            <tr>
                <td>${esc(HR.LEAVE_TYPES[r.leaveType] || r.leaveType)}</td>
                <td>${esc(period(r))}</td>
                <td class="num">${esc(Number(r.days))}</td>
                <td class="muted">${esc(r.reason || '')}</td>
                <td>${HR.leaveStatusBadge(r.status)}</td>
                <td class="muted">${esc(r.createdAt ? r.createdAt.slice(0, 10) : '')}</td>
                <td>${r.status === 'PENDING'
                    ? `<button class="link-btn danger" type="button" data-cancel="${esc(r.reqId)}">취소</button>`
                    : ''}</td>
            </tr>`).join('');
    }

    $('rows').addEventListener('click', async (e) => {
        const btn = e.target.closest('[data-cancel]');
        if (!btn || !confirm('이 휴가 신청을 취소할까요?')) return;
        btn.disabled = true;
        try {
            await api.post(`leaves/${encodeURIComponent(btn.dataset.cancel)}/cancel`);
            toast('신청을 취소했습니다');
            load();
        } catch (err) {
            toast(err.message, 'error');
            btn.disabled = false;
        }
    });

    $('yearSelect').addEventListener('change', () => {
        state.year = Number($('yearSelect').value);
        state.page = 1;
        load();
    });

    // ------------------------------------------------------------ 신청 모달

    function toIso(d) {
        return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    }

    function isHalfDay() {
        return $('leaveType').value.startsWith('HALF_');
    }

    /** 반차는 하루만: 종료일을 시작일에 묶는다 */
    function syncDates() {
        if (isHalfDay()) $('endDate').value = $('startDate').value;
        $('endDate').disabled = isHalfDay();
        $('endDate').min = $('startDate').value;
        if ($('endDate').value && $('endDate').value < $('startDate').value) $('endDate').value = $('startDate').value;
    }

    function setBox(text, warn) {
        $('previewBox').className = 'notice-box' + (warn ? ' warn' : '');
        $('previewBox').textContent = text;
    }

    function openApply() {
        const today = toIso(new Date());
        $('leaveType').value = 'ANNUAL';
        $('startDate').value = today;
        $('endDate').value = today;
        $('reason').value = '';
        syncDates();
        $('applyModal').showModal();
        preview();
    }

    function schedulePreview() {
        syncDates();
        clearTimeout(previewTimer);
        previewTimer = setTimeout(preview, 250);
    }

    async function preview() {
        const params = { leaveType: $('leaveType').value, startDate: $('startDate').value, endDate: $('endDate').value };
        if (!params.startDate || !params.endDate) {
            setBox('기간을 고르면 차감 일수가 표시됩니다', false);
            return;
        }
        const seq = ++previewSeq;
        try {
            const p = await api.get('leaves/preview', params);
            if (seq !== previewSeq) return;
            const line = `결재선: ${p.managerName ? p.managerName + ' → ' : ''}인사 관리자`;
            if (!p.deductible) {
                setBox(`${line} · ${HR.days(p.days)} · 연차 차감 없음`, false);
            } else if (!p.granted) {
                setBox('올해 연차가 아직 부여되지 않아 연차·반차를 신청할 수 없습니다', true);
            } else if (!p.enough) {
                setBox(`잔여 연차가 부족합니다 (신청 ${HR.days(p.days)}, 신청 가능 ${HR.days(p.availableDays)})`, true);
            } else {
                setBox(`${line} · 차감 ${HR.days(p.days)} → 잔여 ${HR.days(p.remainingAfter)}`, false);
            }
        } catch (e) {
            if (seq === previewSeq) setBox(e.message, true);
        }
    }

    $('openApply').addEventListener('click', openApply);
    $('closeApply').addEventListener('click', () => $('applyModal').close());
    ['leaveType', 'startDate', 'endDate'].forEach((id) => $(id).addEventListener('change', schedulePreview));

    $('applyForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        syncDates();
        $('submitApply').disabled = true;
        try {
            const created = await api.post('leaves', {
                leaveType: $('leaveType').value,
                startDate: $('startDate').value,
                endDate: $('endDate').value,
                reason: $('reason').value.trim(),
            });
            $('applyModal').close();
            toast('휴가를 신청했습니다');
            state.year = Number(created.startDate.slice(0, 4));
            $('yearSelect').value = String(state.year);
            state.page = 1;
            load();
        } catch (err) {
            // 모달이 열려 있으면 토스트가 가려지므로 모달 안에 보여준다
            setBox(err.message, true);
        } finally {
            $('submitApply').disabled = false;
        }
    });

    load();
})();
