/* U05 결재함: 내가 올린 문서 · 결재 대기(내가 처리할 문서) · 완료(내가 처리한 문서) */
(function () {
    Layout.user('approval');
    const $ = (id) => document.getElementById(id);
    const query = new URLSearchParams(location.search);
    const BOXES = ['mine', 'todo', 'done'];
    const EMPTY = { mine: '올린 문서가 없습니다', todo: '처리할 문서가 없습니다', done: '처리한 문서가 없습니다' };
    const state = {
        box: BOXES.includes(query.get('box')) ? query.get('box') : 'mine',
        page: 1,
        selected: Number(query.get('id')) || null,
    };

    function syncUrl() {
        const q = new URLSearchParams({ box: state.box });
        if (state.selected) q.set('id', state.selected);
        history.replaceState(null, '', '?' + q);
    }

    function setTabs(counts) {
        document.querySelectorAll('[data-box]').forEach((b) => {
            const active = b.dataset.box === state.box;
            b.classList.toggle('active', active);
            b.setAttribute('aria-selected', String(active));
        });
        if (counts) {
            document.querySelector('[data-count="mine"]').textContent = counts.mine;
            document.querySelector('[data-count="todo"]').textContent = counts.todo;
        }
    }

    async function loadList() {
        setTabs();
        try {
            const data = await api.get('approvals', { box: state.box, page: state.page, size: 15 });
            if (!data.items.length && state.page > 1) { state.page -= 1; return loadList(); }
            setTabs(data.counts);
            $('list').innerHTML = data.items.length ? data.items.map((d) => '<button type="button" class="doc-item'
                + (d.docId === state.selected ? ' active' : '') + '" data-id="' + d.docId + '">'
                + ApprovalView.typeTag(d.docType)
                + '<span class="title">' + esc(d.title) + '</span>'
                + (state.box === 'mine' ? '' : '<span class="sub">' + esc(d.drafterName) + '</span>')
                + ApprovalView.statusBadge(d.status) + '</button>').join('')
                : '<div class="placeholder">' + EMPTY[state.box] + '</div>';
            renderPager($('pager'), data, (p) => { state.page = p; loadList(); });
        } catch (e) {
            $('list').innerHTML = '<div class="placeholder">' + esc(e.message) + '</div>';
        }
    }

    function select(id) {
        state.selected = id;
        syncUrl();
        document.querySelectorAll('.doc-item').forEach((b) => b.classList.toggle('active', Number(b.dataset.id) === id));
        ApprovalView.load($('detail'), id, { onChange: loadList });
    }

    $('list').addEventListener('click', (ev) => {
        const item = ev.target.closest('.doc-item');
        if (item) select(Number(item.dataset.id));
    });
    document.querySelectorAll('[data-box]').forEach((b) => b.addEventListener('click', () => {
        state.box = b.dataset.box;
        state.page = 1;
        syncUrl();
        loadList();
    }));

    syncUrl();
    loadList();
    if (state.selected) select(state.selected);
})();
