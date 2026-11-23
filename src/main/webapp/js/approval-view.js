/*
 * 결재 문서 상세 (결재함 U05 · 결재 관리 A03 공용)
 *
 *   ApprovalView.load(container, docId, { onChange })   상세를 불러와 그린다
 *
 * 결재선 단계, 문서 내용(휴가·경비), 단계별 의견, 승인·반려(지금 단계 결재자), 신청 취소(기안자)를 보여 준다.
 * onChange(detail)는 승인·반려·취소가 끝난 뒤 호출된다 (목록 새로 고침용).
 */
(function () {
    const STATUS = {
        IN_PROGRESS: ['진행 중', 'badge-yellow'],
        APPROVED: ['승인', 'badge-green'],
        REJECTED: ['반려', 'badge-red'],
        CANCELED: ['취소', 'badge-gray'],
    };
    const TYPE = { LEAVE: ['휴가', 'leave'], EXPENSE: ['경비', 'expense'] };
    const ROLE = { MANAGER: '팀장', HR: 'HR' };
    const EXPENSE_STATUS = { PENDING: '결재 중', APPROVED: '지급 대기', REJECTED: '반려', PAID: '지급 완료', CANCELED: '취소' };
    const DAYS = '일월화수목금토';

    const won = (n) => Number(n).toLocaleString('ko-KR');
    const dotDate = (s) => (s ? s.slice(0, 10).replaceAll('-', '.') : '');
    const withDay = (s) => dotDate(s) + ' (' + DAYS[new Date(s + 'T00:00:00').getDay()] + ')';
    const plain = (n) => String(Number(n));

    function statusBadge(status) {
        const [label, cls] = STATUS[status] || [status, 'badge-gray'];
        return '<span class="badge ' + cls + '">' + label + '</span>';
    }

    function typeTag(type) {
        const [label, cls] = TYPE[type] || [type, ''];
        return '<span class="type-tag ' + cls + '">' + label + '</span>';
    }

    function stepsHtml(doc, steps) {
        const parts = ['<div class="step done"><span class="mark" aria-hidden="true">✓</span>기안 · ' + esc(doc.drafterName) + '</div>'];
        steps.forEach((s, i) => {
            const cls = { APPROVED: 'done', REJECTED: 'rejected', PENDING: 'now' }[s.status] || '';
            const mark = { APPROVED: '✓', REJECTED: '✕' }[s.status] || String(i + 2);
            const state = { APPROVED: '승인', REJECTED: '반려', PENDING: '차례', WAITING: '대기', CANCELED: '처리 안 함' }[s.status];
            const who = s.approverName || (s.role === 'HR' ? '인사 관리자' : '-');
            parts.push('<span class="line" aria-hidden="true"></span>');
            parts.push('<div class="step ' + cls + '" title="' + esc(state) + '"><span class="mark" aria-hidden="true">' + mark + '</span>'
                + esc(ROLE[s.role] || s.role) + ' · ' + esc(who) + '<span class="sr-only"> (' + esc(state) + ')</span></div>');
        });
        return '<div class="steps" role="list" aria-label="결재선">' + parts.join('') + '</div>';
    }

    function row(label, valueHtml) {
        return '<div><dt>' + esc(label) + '</dt><dd>' + valueHtml + '</dd></div>';
    }

    function contentRows(doc, d) {
        const rows = [];
        if (doc.docType === 'LEAVE' && d.leaveType) {
            rows.push(row('종류', esc(d.leaveTypeLabel) + ' ' + esc(plain(d.days)) + '일'));
            rows.push(row('기간', d.startDate === d.endDate ? esc(withDay(d.startDate))
                : esc(withDay(d.startDate)) + ' ~ ' + esc(withDay(d.endDate))));
            rows.push(row('사유', esc(d.reason || '-')));
            if (d.remainingAfter !== undefined) {
                rows.push(row('잔여 연차', esc(plain(d.remainingDays)) + ' → ' + esc(plain(d.remainingAfter)) + '일'));
            } else if (d.remainingDays !== undefined) {
                rows.push(row('잔여 연차', esc(plain(d.remainingDays)) + '일'));
            }
        } else if (doc.docType === 'EXPENSE' && d.expense) {
            const e = d.expense;
            rows.push(row('분류', esc(d.categoryLabel)));
            rows.push(row('사용일', esc(withDay(e.useDate))));
            rows.push(row('금액', '<span class="amount">' + esc(won(e.amount)) + '원</span>'));
            rows.push(row('내용', esc(e.description)));
            rows.push(row('영수증', (d.attachments || []).map((f) => '<a href="' + esc(api.url('files/' + f.fileId))
                + '" target="_blank" rel="noopener">' + esc(f.originName) + '</a>').join('<br>') || '-'));
            rows.push(row('정산 상태', esc(EXPENSE_STATUS[e.status] || e.status)));
        }
        return rows;
    }

    function render(container, data, opts) {
        const { doc, steps, detail } = data;
        const comments = steps.filter((s) => s.comment)
            .map((s) => row((ROLE[s.role] || s.role) + ' 의견', esc(s.comment) + ' <span class="muted">· ' + esc(s.approverName || '') + '</span>'));
        container.innerHTML = '<div class="doc-head">'
            + '<div><h2>' + esc(doc.title) + '</h2>'
            + '<div class="doc-meta">' + typeTag(doc.docType) + ' &nbsp;' + esc(doc.drafterName)
            + (doc.drafterDept ? ' · ' + esc(doc.drafterDept) : '') + ' · 기안 ' + esc(dotDate(doc.createdAt))
            + ' · <span class="nowrap">문서번호 ' + esc(doc.docNo) + '</span></div></div>' + statusBadge(doc.status) + '</div>'
            + stepsHtml(doc, steps)
            + '<dl class="info-list">' + contentRows(doc, detail).concat(comments).join('') + '</dl>'
            + (data.canAct ? '<form class="act-box" data-act-form novalidate>'
                + '<label class="field"><span class="sr-only">의견</span>'
                + '<input class="input" name="comment" maxlength="500" placeholder="의견 입력 (반려 시 필수)" autocomplete="off"></label>'
                + '<div class="form-error" role="alert" data-error></div>'
                + '<div class="buttons"><button class="btn btn-danger" type="button" data-reject>반려</button>'
                + '<button class="btn btn-primary" type="submit">승인</button></div></form>' : '')
            + (data.canCancel ? '<div class="doc-foot"><button class="btn btn-danger" type="button" data-cancel>신청 취소</button></div>' : '');

        const form = container.querySelector('[data-act-form]');
        if (form) {
            const error = form.querySelector('[data-error]');
            const send = async (action) => {
                const comment = form.comment.value.trim();
                if (action === 'reject' && !comment) {
                    error.textContent = '반려할 때는 의견을 입력하세요';
                    form.comment.focus();
                    return;
                }
                form.querySelectorAll('button').forEach((b) => { b.disabled = true; });
                try {
                    const updated = await api.post('approvals/' + doc.docId + '/' + action, { comment });
                    toast(action === 'approve' ? '승인했습니다' : '반려했습니다');
                    render(container, updated, opts);
                    if (opts.onChange) opts.onChange(updated);
                } catch (e) {
                    error.textContent = e.message;
                    form.querySelectorAll('button').forEach((b) => { b.disabled = false; });
                }
            };
            form.addEventListener('submit', (ev) => { ev.preventDefault(); send('approve'); });
            form.querySelector('[data-reject]').addEventListener('click', () => send('reject'));
        }
        const cancel = container.querySelector('[data-cancel]');
        if (cancel) {
            cancel.addEventListener('click', async () => {
                if (!confirm('이 신청을 취소할까요? 결재도 함께 취소됩니다.')) return;
                cancel.disabled = true;
                try {
                    const updated = await api.post('approvals/' + doc.docId + '/cancel');
                    toast('신청을 취소했습니다');
                    render(container, updated, opts);
                    if (opts.onChange) opts.onChange(updated);
                } catch (e) {
                    toast(e.message, 'error');
                    cancel.disabled = false;
                }
            });
        }
    }

    async function load(container, docId, opts = {}) {
        container.innerHTML = '<div class="placeholder">불러오는 중…</div>';
        try {
            render(container, await api.get('approvals/' + docId), opts);
        } catch (e) {
            container.innerHTML = '<div class="placeholder">' + esc(e.message) + '</div>';
        }
    }

    window.ApprovalView = { load, statusBadge, typeTag, dotDate, won };
})();
