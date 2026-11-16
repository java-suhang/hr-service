const apiRoot = new URL('../../api/', import.meta.url);
export const content = document.querySelector('#content');
const message = document.querySelector('#message');
export let session;

export function h(tag, props = {}, ...children) {
    const element = document.createElement(tag);
    for (const [key, value] of Object.entries(props)) {
        if (key.startsWith('on')) element.addEventListener(key.slice(2), value);
        else if (key in element) element[key] = value;
        else element.setAttribute(key, value);
    }
    element.append(...children.flat().filter(value => value != null));
    return element;
}

export async function api(path, method = 'GET', body) {
    const headers = {Accept: 'application/json'};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (method !== 'GET') headers['X-CSRF-Token'] = session?.csrf ?? '';
    const response = await fetch(new URL(path, apiRoot), {
        method, headers, body: body === undefined ? undefined : JSON.stringify(body)
    });
    if (response.status === 401) location.assign(new URL('../login.html', apiRoot));
    const result = await response.json().catch(() => null);
    if (!response.ok) throw new Error(result?.message ?? `요청 실패 (HTTP ${response.status})`);
    return result;
}

export async function fetchAll(path) {
    const items = [];
    const delimiter = path.includes('?') ? '&' : '?';
    let data;
    do {
        data = await api(path + delimiter + 'size=100&page=' + ((data?.page ?? 0) + 1));
        items.push(...data.items);
    } while (data.page * data.size < data.total);
    return items;
}

export function notify(text, error = false) {
    message.textContent = text;
    message.classList.toggle('error', error);
}

export async function run(task) {
    notify('');
    try { await task(); } catch (err) { notify(err.message, true); }
}

export async function init(render) {
    content.setAttribute('aria-busy', 'true');
    try {
        session = await api('dashboard/session');
        const view = document.body.dataset.view;
        const admin = location.pathname.split('/').at(-2) === 'admin';
        const links = admin
            ? [['dashboard', '대시보드'], ['attendance', '근태 관리'], ['notices', '공지 관리'],
                ['evaluations', '성과 평가'], ['recruit', '채용 관리']]
            : [['home', '홈'], ['attendance', '근태'], ['leave', '휴가'], ['notices', '공지'],
                ['evaluation', '성과 평가'], ['interviews', '내 면접']];
        const nav = document.querySelector('nav');
        for (const [page, label] of links) {
            const link = h('a', {href: `${page}.html`}, label);
            if (view === page) link.setAttribute('aria-current', 'page');
            nav.append(link);
        }
        if (session.admin) nav.append(h('a', {href: admin ? '../user/home.html' : '../admin/dashboard.html'},
            admin ? '직원 화면' : '관리자 화면'));
        await render(admin);
    } catch (err) {
        notify(err.message, true);
    } finally {
        content.setAttribute('aria-busy', 'false');
    }
}

export const button = (label, action, props = {}) =>
    h('button', {type: 'button', onclick: event => run(() => action(event)), ...props}, label);

export const panel = (title, ...children) => h('section', {className: 'panel'}, h('h2', {}, title), ...children);
export const metric = (title, value) => h('section', {className: 'metric'}, h('h2', {}, title), h('p', {}, value));
export const bar = (...children) => h('div', {className: 'bar'}, ...children);
export const field = (label, control) => h('label', {}, label, control);
export const input = (name, type = 'text', props = {}) => h('input', {name, type, ...props});
export const textarea = (name, props = {}) => h('textarea', {name, ...props});
export const formValues = form => Object.fromEntries(new FormData(form));

export function select(name, options, placeholder = '선택') {
    return h('select', {name, required: true},
        h('option', {value: ''}, placeholder),
        options.map(([value, label]) => h('option', {value}, label)));
}

export function table(headers, rows, empty = '표시할 항목이 없습니다.') {
    if (!rows.length) return h('p', {className: 'muted'}, empty);
    return h('div', {className: 'table-box'}, h('table', {},
        h('thead', {}, h('tr', {}, headers.map(name => h('th', {scope: 'col'}, name)))),
        h('tbody', {}, rows.map(row => h('tr', {}, row.map(cell => h('td', {}, cell ?? '—')))))));
}

export function pages(data, load) {
    return bar(
        button('이전', () => load(data.page - 1), {disabled: data.page <= 1}),
        h('span', {}, `${data.page} / ${Math.max(1, Math.ceil(data.total / data.size))} 페이지 · ${data.total}건`),
        button('다음', () => load(data.page + 1), {disabled: data.page * data.size >= data.total}));
}

export function dialog(title, children, submit, action = '저장', afterSave) {
    const box = h('dialog', {'aria-label': title});
    const close = button('닫기', () => box.close());
    const error = h('p', {className: 'status error', role: 'alert'});
    box.append(h('h2', {}, title));
    if (submit) {
        const save = h('button', {type: 'submit', className: 'primary'}, action);
        const form = h('form', {onsubmit: async event => {
            event.preventDefault();
            error.textContent = '';
            save.disabled = true;
            try {
                const result = await submit(form);
                box.close();
                if (afterSave) await run(() => afterSave(result));
            } catch (err) {
                error.textContent = err.message;
            } finally {
                save.disabled = false;
            }
        }}, children, error, h('div', {className: 'actions'}, save, close));
        box.append(form);
    } else {
        box.append(...children, close);
    }
    document.body.append(box);
    box.addEventListener('close', () => box.remove(), {once: true});
    box.showModal();
}

export const labels = {
    READY: '준비', SELF: '자기평가', MANAGER: '상사평가', CLOSED: '마감', SUBMITTED: '제출 완료', DRAFT: '미제출',
    OPEN: '모집 중', APPLIED: '지원', INTERVIEW: '면접', OFFER: '합격', HIRED: '입사', REJECTED: '불합격',
    NORMAL: '정상', LATE: '지각', EARLY_LEAVE: '조퇴', ABSENT: '결근', LEAVE: '휴가',
    IN_PROGRESS: '진행 중', APPROVED: '승인', CANCELED: '취소'
};
export const dateTime = value => value?.replace('T', ' ') ?? '—';
