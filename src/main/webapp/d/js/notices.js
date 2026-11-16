import {api, bar, button, content, dateTime, dialog, field, formValues, h, init, input,
    notify, pages, panel, run, table, textarea} from './ui.js';

init(async admin => {
    const root = admin ? 'admin/notices' : 'notices';
    const results = h('div');
    const search = input('q', 'search', {maxLength: 200});
    const searchForm = h('form', {className: 'bar', onsubmit: event => {
        event.preventDefault();
        run(() => load(1));
    }}, field('공지 제목', search), h('button', {type: 'submit'}, '검색'));
    content.append(panel('공지 목록', bar(searchForm, admin ? button('공지 작성', () => edit(), {className: 'primary'}) : null), results));

    let currentPage = 1;
    let requestId = 0;
    async function load(page) {
        const id = ++requestId;
        const data = await api(`${root}?page=${page}&q=${encodeURIComponent(search.value)}`);
        if (id !== requestId) return;
        if (page > 1 && !data.items.length && data.total) return load(Math.ceil(data.total / data.size));
        currentPage = data.total ? page : 1;
        results.replaceChildren(table(['제목', '작성자', '등록일'], data.items.map(row => [
            h('div', {}, row.isPinned === 'Y' ? h('span', {className: 'badge'}, '필독') : null,
                button(row.title, () => detail(row.noticeId))), row.writer, dateTime(row.createdAt)
        ])), pages(data.total ? data : {...data, page: 1}, load));
    }

    async function detail(id) {
        const row = await api(`${root}/${id}`);
        const actions = admin ? bar(
            button('수정', event => openEditor(event, row)),
            button('삭제', () => {
                dialog('공지 삭제', [h('p', {}, `‘${row.title}’ 공지를 목록에서 삭제합니다.`)],
                    () => api(`${root}/${id}`, 'DELETE'), '삭제', async () => {
                        document.querySelector('dialog[open]')?.close();
                        await load(currentPage);
                    });
            }, {className: 'danger'})
        ) : null;
        dialog(row.title, [h('p', {className: 'muted'}, `${row.writer} · ${dateTime(row.updatedAt)}`),
            h('p', {className: 'body-text'}, row.content), actions].filter(Boolean));
    }

    function openEditor(event, row) {
        event.currentTarget.closest('dialog').close();
        edit(row);
    }

    function edit(row) {
        dialog(row ? '공지 수정' : '공지 작성', [
            field('제목', input('title', 'text', {required: true, maxLength: 200, value: row?.title ?? ''})),
            field('내용', textarea('content', {required: true, maxLength: 50000, value: row?.content ?? ''})),
            field('필독으로 고정', input('isPinned', 'checkbox', {checked: row?.isPinned === 'Y'}))
        ], form => {
            const {title, content, isPinned} = formValues(form);
            return api(row ? `${root}/${row.noticeId}` : root, row ? 'PUT' : 'POST', {title, content, isPinned: Boolean(isPinned)});
        }, '저장', async result => {
            await load(row ? currentPage : 1);
            if (result.notificationsEnabled === false) notify('공지를 등록했습니다. 알림은 연동 후 발송할 수 있습니다.');
        });
    }

    await load(1);
    const id = new URL(location.href).searchParams.get('id');
    if (id && /^\d+$/.test(id)) await detail(id);
});
