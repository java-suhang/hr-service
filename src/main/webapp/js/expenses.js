/* U06 경비 정산: 영수증을 붙여 요청하고, 내 요청 내역·결재 진행을 본다 */
(function () {
    Layout.user('expense');
    const $ = (id) => document.getElementById(id);
    const MAX = 5 * 1024 * 1024;
    const today = () => {
        const d = new Date();
        return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
    };
    let page = 1;

    $('category').innerHTML = EXPENSE.CATEGORIES.map(([c, l]) => '<option value="' + c + '">' + l + '</option>').join('');
    $('useDate').value = today();
    $('useDate').max = today();

    // ------------------------------------------------------------ 금액: 숫자만, 천 단위 쉼표
    $('amount').addEventListener('input', () => {
        const digits = $('amount').value.replace(/[^0-9]/g, '').replace(/^0+/, '').slice(0, 10);
        $('amount').value = digits ? Number(digits).toLocaleString('ko-KR') : '';
    });

    // ------------------------------------------------------------ 영수증: 클릭·끌어 놓기
    function showFile(file) {
        $('dropzone').classList.toggle('has-file', !!file);
        $('dropText').textContent = file
            ? file.name + ' (' + Math.max(1, Math.round(file.size / 1024)).toLocaleString('ko-KR') + 'KB)'
            : '파일을 끌어오거나 클릭해서 업로드 (JPG, PNG, PDF · 5MB 이하)';
    }
    $('receipt').addEventListener('change', () => showFile($('receipt').files[0]));
    ['dragenter', 'dragover'].forEach((t) => $('dropzone').addEventListener(t, (e) => {
        e.preventDefault();
        $('dropzone').classList.add('drag');
    }));
    ['dragleave', 'drop'].forEach((t) => $('dropzone').addEventListener(t, () => $('dropzone').classList.remove('drag')));
    $('dropzone').addEventListener('drop', (e) => {
        e.preventDefault();
        if (e.dataTransfer.files.length) {
            $('receipt').files = e.dataTransfer.files;
            showFile($('receipt').files[0]);
        }
    });

    // ------------------------------------------------------------ 요청
    $('expenseForm').addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const file = $('receipt').files[0];
        const amount = $('amount').value.replace(/,/g, '');
        const error = (msg, focus) => { $('formError').textContent = msg; if (focus) focus.focus(); };
        if (!$('useDate').value) return error('사용일을 입력하세요', $('useDate'));
        if (!amount || Number(amount) <= 0) return error('금액은 0원보다 커야 합니다', $('amount'));
        if (!$('description').value.trim()) return error('사용 내역을 입력하세요', $('description'));
        if (!file) return error('영수증 파일을 첨부하세요', $('receipt'));
        if (file.size > MAX) return error('파일은 5MB까지 올릴 수 있습니다', $('receipt'));

        const form = new FormData();
        form.append('category', $('category').value);
        form.append('useDate', $('useDate').value);
        form.append('amount', amount);
        form.append('description', $('description').value.trim());
        form.append('receipt', file);
        $('submitBtn').disabled = true;
        try {
            await api.upload('expenses', form);
            toast('정산을 요청했습니다. 결재가 끝나면 알려 드릴게요');
            $('formError').textContent = '';
            $('amount').value = '';
            $('description').value = '';
            $('receipt').value = '';
            showFile(null);
            page = 1;
            load();
        } catch (e) {
            error(e.message);
        } finally {
            $('submitBtn').disabled = false;
        }
    });

    // ------------------------------------------------------------ 내역
    async function load() {
        try {
            const data = await api.get('expenses', { page, size: 10 });
            if (!data.items.length && page > 1) { page -= 1; return load(); }
            $('totalText').textContent = data.total ? data.total + '건' : '';
            $('rows').innerHTML = data.items.length ? data.items.map((x) => '<tr>'
                + '<td>' + esc(x.useDate.slice(5).replace('-', '.')) + '</td>'
                + '<td>' + esc(EXPENSE.label(x.category)) + '</td>'
                + '<td>' + (x.docId ? '<a href="approvals.html?id=' + x.docId + '">' + esc(x.description) + '</a>' : esc(x.description)) + '</td>'
                + '<td class="num amount">' + EXPENSE.won(x.amount) + '</td>'
                + '<td>' + EXPENSE.clip(x.receiptId) + '</td>'
                + '<td>' + EXPENSE.badge(x.status) + '</td>'
                + '<td>' + (x.status === 'PENDING' ? '<button class="link-btn danger" type="button" data-cancel="' + x.expId + '">취소</button>' : '') + '</td>'
                + '</tr>').join('')
                : '<tr><td class="empty" colspan="7">아직 요청한 경비가 없습니다</td></tr>';
            renderPager($('pager'), data, (p) => { page = p; load(); });
        } catch (e) {
            $('rows').innerHTML = '<tr><td class="empty" colspan="7">' + esc(e.message) + '</td></tr>';
        }
    }

    $('rows').addEventListener('click', async (ev) => {
        const b = ev.target.closest('[data-cancel]');
        if (!b || !confirm('이 경비 요청을 취소할까요? 결재도 함께 취소됩니다.')) return;
        try {
            await api.post('expenses/' + b.dataset.cancel + '/cancel');
            toast('요청을 취소했습니다');
            load();
        } catch (e) { toast(e.message, 'error'); }
    });

    load();
})();
