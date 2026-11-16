import {api, bar, button, content, dateTime, dialog, fetchAll, field, formValues, h, init, input,
    labels, pages, panel, select, session, table, textarea} from './ui.js';

const nextStatus = {READY: 'SELF', SELF: 'MANAGER', MANAGER: 'CLOSED'};

init(async admin => {
    const root = admin ? 'admin/evaluations' : 'evaluations';
    const periods = h('div');
    const selected = h('div');
    const list = panel('평가 기간', periods);
    if (admin) list.prepend(bar(button('평가 기간 등록', () => editPeriod(), {className: 'primary'})));
    content.append(list, selected);
    let current;
    let requestId = 0;
    let periodPage = 1;

    async function loadPeriods(page = periodPage) {
        const data = await api(`${root}/periods?page=${page}`);
        periodPage = page;
        periods.replaceChildren(table(['평가명', '기간', '단계'], data.items.map(row => [
            button(row.name, () => display(row.periodId)), `${row.startDate} ~ ${row.endDate}`, labels[row.status]
        ])), pages(data, loadPeriods));
        return data.items;
    }

    function editPeriod(period) {
        dialog(period ? '평가 기간 수정' : '평가 기간 등록', [
            field('평가명', input('name', 'text', {required: true, maxLength: 120, value: period?.name ?? ''})),
            h('div', {className: 'fields'},
                field('시작일', input('startDate', 'date', {required: true, value: period?.startDate ?? ''})),
                field('종료일', input('endDate', 'date', {required: true, value: period?.endDate ?? ''})))
        ], form => api(period ? `${root}/periods/${period.periodId}` : `${root}/periods`, period ? 'PUT' : 'POST',
            formValues(form)), '저장', async saved => {
            await loadPeriods(period ? periodPage : 1);
            await display(saved.periodId);
        });
    }

    async function display(id) {
        const token = ++requestId;
        const [period, items] = await Promise.all([api(`${root}/periods/${id}`), fetchAll(`${root}/periods/${id}/items`)]);
        if (token !== requestId) return;
        current = id;
        const section = panel(period.name, h('p', {className: 'muted'},
            `${period.startDate} ~ ${period.endDate} · ${labels[period.status]}`));
        selected.replaceChildren(section);
        if (admin) await adminPeriod(period, items, section);
        else await userPeriod(period, items, section);
    }

    async function showResult(id) {
        const result = await api(`${root}/results/${id}`);
        dialog(`${result.targetName} · ${labels[result.evalType]}`, [
            h('p', {}, `총점 ${result.totalScore} / 5 · ${dateTime(result.submittedAt)} 제출`),
            table(['항목', '가중치', '점수'], result.scores.map(row => [row.name, `${row.weight}%`, row.score])),
            h('p', {className: 'body-text'}, result.comment ?? '')
        ]);
    }

    function itemTable(items, actions) {
        const columns = ['항목', '설명', '가중치'];
        if (actions) columns.push('관리');
        return table(columns, items.map(item => [
            item.name, item.description, `${item.weight}%`, ...(actions ? [actions(item)] : [])
        ]), '등록된 평가 항목이 없습니다.');
    }

    async function adminPeriod(period, items, section) {
        const id = period.periodId;
        const ready = period.status === 'READY';
        const next = nextStatus[period.status];
        section.append(bar(
            ready ? button('기간 수정', () => editPeriod(period)) : null,
            next ? button(`${labels[next]}(으)로 전환`, () => dialog('평가 단계 전환', [
                h('p', {}, `‘${period.name}’을(를) ${labels[next]} 단계로 전환합니다.`)
            ], () => api(`${root}/periods/${id}/status`, 'POST', {status: next}), '전환', async () => {
                await loadPeriods();
                await display(id);
            })) : null
        ));

        const sum = items.reduce((total, item) => total + Math.round(item.weight * 100), 0) / 100;
        section.append(h('h3', {}, '평가 항목'), h('p', {className: 'muted'}, `가중치 합계 ${sum}%`));
        if (ready) section.append(bar(button('항목 추가', () => editItem(id), {className: 'primary'})));
        section.append(itemTable(items, ready ? item => h('div', {},
            button('수정', () => editItem(id, item)),
            button('삭제', () => dialog('항목 삭제', [h('p', {}, `‘${item.name}’ 항목을 삭제합니다.`)],
                () => api(`${root}/items/${item.itemId}`, 'DELETE'), '삭제', () => display(id)), {className: 'danger'})
        ) : null));

        const status = h('div');
        section.append(h('h3', {}, '직원별 제출 현황'), status);
        async function load(page = 1) {
            const data = await api(`${root}/status?periodId=${id}&page=${page}`);
            status.replaceChildren(table(['직원', '사번', '직속 상사', '자기평가', '상사평가'],
                data.items.map(row => [row.name, row.empNo, row.manager,
                    row.selfId ? button(`${row.selfScore}점`, () => showResult(row.selfId)) : '미제출',
                    row.managerEvalId ? button(`${row.managerScore}점`, () => showResult(row.managerEvalId)) : '미제출'
                ])), pages(data, load));
        }
        await load();
    }

    function editItem(period, item) {
        dialog(item ? '평가 항목 수정' : '평가 항목 추가', [
            field('항목명', input('name', 'text', {required: true, maxLength: 150, value: item?.name ?? ''})),
            field('설명', textarea('description', {maxLength: 1000, value: item?.description ?? ''})),
            field('가중치 (%)', input('weight', 'number',
                {required: true, min: '0.01', max: '100', step: '0.01', value: item?.weight ?? ''}))
        ], form => {
            const data = formValues(form);
            return api(item ? `${root}/items/${item.itemId}` : `${root}/periods/${period}/items`,
                item ? 'PUT' : 'POST', {...data, weight: Number(data.weight)});
        }, '저장', () => display(period));
    }

    async function userPeriod(period, items, section) {
        const id = period.periodId;
        const [mine, reviews] = await Promise.all([
            api(`${root}/mine?periodId=${id}`), fetchAll(`${root}/reviews?periodId=${id}`)
        ]);
        section.append(h('h3', {}, '내 평가'), table(['유형', '총점', '제출일', '결과'], mine.items.map(row => [
            labels[row.evalType], row.totalScore, dateTime(row.submittedAt), button('상세 보기', () => showResult(row.evalId))
        ]), '확인할 수 있는 평가 결과가 없습니다.'));
        if (period.status === 'SELF' && period.withinDates && !mine.items.some(row => row.evalType === 'SELF')) {
            section.append(bar(button('자기평가 작성', () => scoreForm(period, items, session.empId, 'SELF'), {className: 'primary'})));
        }
        if (period.status !== 'MANAGER' || !period.withinDates) {
            if (reviews.length) section.append(h('h3', {}, '제출한 상사평가'), table(['직원', '총점', '결과'],
                reviews.map(row => [row.name, row.totalScore, button('상세 보기', () => showResult(row.evalId))])));
            return;
        }
        const submitted = new Map(reviews.map(row => [row.targetId, row]));
        const team = h('div');
        section.append(h('h3', {}, '직속 직원 평가'), team);
        async function load(page = 1) {
            const data = await api(`${root}/team?page=${page}`);
            team.replaceChildren(table(['직원', '사번', '상사평가'], data.items.map(row => {
                const saved = submitted.get(row.empId);
                return [row.name, row.empNo, saved ? button('제출 결과', () => showResult(saved.evalId))
                    : button('상사평가 작성', () => scoreForm(period, items, row.empId, 'MANAGER', row.name))];
            }), '평가할 직속 직원이 없습니다.'), pages(data, load));
        }
        await load();
    }

    function scoreForm(period, items, targetId, evalType, targetName) {
        dialog(targetName ? `${targetName} · 상사평가` : '자기평가 작성', [
            ...items.map(item => field(`${item.name} · ${item.weight}%`,
                select(String(item.itemId), [1, 2, 3, 4, 5].map(score => [score, `${score}점`]), '점수 선택'))),
            field('의견', textarea('comment', {maxLength: 1000})),
            h('p', {className: 'muted'}, '제출한 평가는 수정할 수 없습니다.')
        ], form => {
            const data = formValues(form);
            return api(`${root}/submit`, 'POST', {periodId: period.periodId, targetId, evalType, comment: data.comment,
                scores: items.map(item => ({itemId: item.itemId, score: Number(data[item.itemId])}))});
        }, '제출', () => display(current));
    }

    const items = await loadPeriods();
    if (items.length) await display(items[0].periodId);
});
