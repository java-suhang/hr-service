/*
 * 공통 API 호출 (사용자·어드민 화면 공용)
 *
 *   const data = await api.get('attendance', { month: '2026-11' });
 *   await api.post('leaves', { leaveType: 'ANNUAL', ... });
 *
 * 경로는 /api/ 아래 상대 경로로 쓴다 ('admin/attendance'). 화면은 /user/, /admin/ 아래에 있다고 가정한다.
 */
(function () {
    const API_BASE = new URL('../api/', location.href);

    class ApiError extends Error {
        constructor(status, message) {
            super(message);
            this.status = status;
        }
    }

    async function request(method, path, { params, body } = {}) {
        const url = new URL(path, API_BASE);
        if (params) {
            Object.entries(params).forEach(([k, v]) => {
                if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, v);
            });
        }
        const options = { method, credentials: 'same-origin', headers: {} };
        if (body !== undefined) {
            options.headers['Content-Type'] = 'application/json';
            options.body = JSON.stringify(body);
        }

        let res;
        try {
            res = await fetch(url, options);
        } catch (e) {
            throw new ApiError(0, '서버에 연결할 수 없습니다');
        }
        if (res.status === 401) {
            location.href = new URL('../login.html', location.href).href;
            throw new ApiError(401, '로그인이 필요합니다');
        }
        const text = await res.text();
        let data = null;
        if (text) {
            try {
                data = JSON.parse(text);
            } catch (e) {
                data = null;
            }
        }
        if (!res.ok) {
            throw new ApiError(res.status, (data && data.message) || '요청을 처리하지 못했습니다');
        }
        return data;
    }

    /** 화면 출력용 HTML 이스케이프 (NFR-02) */
    function esc(value) {
        if (value === null || value === undefined) return '';
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function toast(message, type) {
        let wrap = document.querySelector('.toast-wrap');
        if (!wrap) {
            wrap = document.createElement('div');
            wrap.className = 'toast-wrap';
            document.body.appendChild(wrap);
        }
        const el = document.createElement('div');
        el.className = 'toast' + (type === 'error' ? ' error' : '');
        el.textContent = message;
        wrap.appendChild(el);
        setTimeout(() => el.remove(), 3000);
    }

    /** 페이지 번호 버튼. onMove(page)로 이동한다. */
    function renderPager(container, { total, page, size }, onMove) {
        const pages = Math.max(1, Math.ceil(total / size));
        container.innerHTML = '';
        if (pages <= 1) return;
        const start = Math.max(1, Math.min(page - 2, pages - 4));
        const end = Math.min(pages, start + 4);
        const add = (label, target, active) => {
            const b = document.createElement('button');
            b.className = 'btn btn-sm' + (active ? ' active' : '');
            b.textContent = label;
            b.disabled = target < 1 || target > pages;
            b.addEventListener('click', () => onMove(target));
            container.appendChild(b);
        };
        add('‹', page - 1, false);
        for (let p = start; p <= end; p++) add(String(p), p, p === page);
        add('›', page + 1, false);
    }

    window.api = {
        get: (path, params) => request('GET', path, { params }),
        post: (path, body) => request('POST', path, { body: body === undefined ? {} : body }),
        put: (path, body) => request('PUT', path, { body }),
        del: (path) => request('DELETE', path),
        url: (path, params) => {
            const url = new URL(path, API_BASE);
            Object.entries(params || {}).forEach(([k, v]) => {
                if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, v);
            });
            return url.href;
        },
        ApiError,
    };
    window.esc = esc;
    window.toast = toast;
    window.renderPager = renderPager;
})();
