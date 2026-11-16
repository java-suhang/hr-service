import {api, bar, button, content, dateTime, dialog, fetchAll, field, formValues, h, init, input,
    labels, pages, panel, run, select, session, table, textarea} from './ui.js';

const adminRoot = 'admin/recruitment';
const nextStage = {APPLIED: 'INTERVIEW', INTERVIEW: 'OFFER'};
const textField = (label, name, maxLength, value = '', required = true) =>
    field(label, input(name, 'text', {maxLength, value, required}));

function rating(row, admin, refresh) {
    dialog('면접 평가', [
        h('p', {className: 'muted'}, `${row.applicant} · ${dateTime(row.scheduledAt)}`),
        field('점수 (0~100)', input('score', 'number',
            {required: true, min: '0', max: '100', step: '1', value: row.score ?? ''})),
        field('피드백', textarea('feedback', {maxLength: 1000, value: row.feedback ?? ''}))
    ], form => {
        const {score, feedback} = formValues(form);
        return api(`${admin ? adminRoot : 'recruitment'}/interviews/${row.interviewId}`, 'PUT',
            {score: Number(score), feedback});
    }, '저장', refresh);
}

init(async admin => {
    if (!admin) {
        const results = h('div');
        content.append(panel('배정된 면접', results));
        async function load(page = 1) {
            const data = await api(`recruitment/interviews?page=${page}`);
            results.replaceChildren(table(['지원자', '공고', '일시', '점수', '평가'], data.items.map(row => [
                row.applicant, row.posting, dateTime(row.scheduledAt), row.score,
                button('평가 입력', () => rating(row, false, () => load(page)))
            ])), pages(data, load));
        }
        await load();
        return;
    }

    const postings = h('div');
    const applicants = h('div');
    const selected = h('div');
    const filter = h('select', {onchange: () => run(() => loadApplicants(1))});
    const register = button('지원자 등록', () => editApplicant(), {className: 'primary'});
    content.append(panel('채용 공고', bar(button('공고 등록', () => editPosting(), {className: 'primary'})), postings),
        panel('지원자 목록', bar(field('공고별 조회', filter), register), applicants), selected);
    let options = [];
    let postingPage = 1;
    let applicantPage = 1;
    let current;
    let requestId = 0;

    async function refreshPostings(page = postingPage) {
        const [data, all] = await Promise.all([api(`${adminRoot}/postings?page=${page}`), fetchAll(`${adminRoot}/postings`)]);
        postingPage = page;
        options = all;
        const previous = filter.value;
        filter.replaceChildren(h('option', {value: ''}, '전체 공고'),
            ...options.map(row => h('option', {value: row.postingId}, row.title)));
        filter.value = [...filter.options].some(option => option.value === previous) ? previous : '';
        register.disabled = !options.some(row => row.accepting);
        postings.replaceChildren(table(['공고명', '부서 · 직급', '모집 기간', '상태'], data.items.map(row => [
            button(row.title, () => showPosting(row.postingId)), `${row.department} · ${row.position}`,
            `${row.openDate} ~ ${row.closeDate}`, row.accepting ? '모집 중' : row.status === 'CLOSED' ? '마감' : '모집 기간 외'
        ])), pages(data, refreshPostings));
    }

    async function showPosting(id) {
        const row = await api(`${adminRoot}/postings/${id}`);
        const body = [
            h('p', {className: 'muted'}, `${row.department} · ${row.position} · ${row.openDate} ~ ${row.closeDate}`),
            h('p', {className: 'body-text'}, row.description ?? '')
        ];
        if (row.status === 'OPEN') body.push(bar(button('공고 마감', event => {
            event.currentTarget.closest('dialog').close();
            dialog('공고 마감', [h('p', {}, `‘${row.title}’ 공고를 마감합니다.`)],
                () => api(`${adminRoot}/postings/${id}/close`, 'POST'), '마감', () => refreshPostings());
        }, {className: 'danger'})));
        dialog(row.title, body);
    }

    async function editPosting() {
        const [departments, positions] = await Promise.all([
            fetchAll(`${adminRoot}/lookups/departments`), fetchAll(`${adminRoot}/lookups/positions`)
        ]);
        dialog('채용 공고 등록', [
            textField('공고명', 'title', 200),
            h('div', {className: 'fields'},
                field('부서', select('deptId', departments.map(row => [row.id, row.name]))),
                field('직급', select('posId', positions.map(row => [row.id, row.name]))),
                field('모집 시작일', input('openDate', 'date', {required: true})),
                field('모집 마감일', input('closeDate', 'date', {required: true}))),
            field('공고 내용', textarea('description', {maxLength: 50000}))
        ], form => {
            const data = formValues(form);
            return api(`${adminRoot}/postings`, 'POST', {...data, deptId: Number(data.deptId), posId: Number(data.posId)});
        }, '등록', () => refreshPostings(1));
    }

    async function loadApplicants(page = applicantPage) {
        const query = new URLSearchParams({page});
        if (filter.value) query.set('postingId', filter.value);
        const id = ++requestId;
        const data = await api(`${adminRoot}/applicants?${query}`);
        if (id !== requestId) return;
        applicantPage = page;
        applicants.replaceChildren(table(['지원자', '공고', '이메일', '전화번호', '단계'], data.items.map(row => [
            button(row.name, () => displayApplicant(row.applicantId)), row.posting, row.email, row.phone, labels[row.stage]
        ])), pages(data, loadApplicants));
    }

    function editApplicant() {
        const posting = select('postingId', options.filter(row => row.accepting).map(row => [row.postingId, row.title]));
        if (options.some(row => String(row.postingId) === filter.value && row.accepting)) posting.value = filter.value;
        dialog('지원자 등록', [
            field('지원 공고', posting), textField('이름', 'name', 100),
            field('이메일', input('email', 'email', {maxLength: 200})),
            field('전화번호', input('phone', 'tel', {maxLength: 30}))
        ], form => {
            const data = formValues(form);
            return api(`${adminRoot}/applicants`, 'POST', {...data, postingId: Number(data.postingId)});
        }, '등록', async row => {
            await loadApplicants(1);
            await displayApplicant(row.applicantId);
        });
    }

    async function displayApplicant(id) {
        current = id;
        const applicant = await api(`${adminRoot}/applicants/${id}`);
        if (id !== current) return;
        const actions = [];
        const next = nextStage[applicant.stage];
        if (next) actions.push(stageButton(applicant, next));
        if (next || applicant.stage === 'OFFER') actions.push(stageButton(applicant, 'REJECTED'));
        if (applicant.stage === 'OFFER' && session.employeeCreationEnabled) {
            actions.push(button('직원 전환', () => hire(applicant), {className: 'primary'}));
        }
        const list = h('div');
        selected.replaceChildren(panel(applicant.name,
            h('p', {className: 'muted'}, `${applicant.posting} · ${labels[applicant.stage]} · ${dateTime(applicant.appliedAt)} 지원`),
            h('p', {}, [applicant.email, applicant.phone].filter(Boolean).join(' · ')),
            applicant.hiredEmpId ? h('p', {}, `등록된 직원 ID ${applicant.hiredEmpId}`) : null,
            applicant.stage === 'OFFER' && !session.employeeCreationEnabled
                ? h('p', {className: 'muted'}, '직원 전환은 인사 서비스 연동이 필요합니다.') : null,
            actions.length ? bar(...actions) : null,
            h('h3', {}, '면접 일정 및 평가'),
            applicant.stage === 'INTERVIEW' ? bar(button('면접 일정 등록', () => schedule(applicant))) : null,
            list));
        async function load(page = 1) {
            const data = await api(`${adminRoot}/applicants/${id}/interviews?page=${page}`);
            list.replaceChildren(table(['일시', '면접관', '점수', '관리'], data.items.map(row => [
                dateTime(row.scheduledAt), row.interviewer, row.score, h('div', {},
                    button('평가 입력', () => rating(row, true, () => load(page))),
                    applicant.stage === 'INTERVIEW' && row.score == null ? button('일정 수정', () => schedule(applicant, row)) : null)
            ])), pages(data, load));
        }
        await load();
    }

    function stageButton(applicant, stage) {
        return button(`${labels[stage]} 처리`, () => dialog('지원 단계 변경', [
            h('p', {}, `${applicant.name} 지원자를 ${labels[stage]} 단계로 변경합니다.`)
        ], () => api(`${adminRoot}/applicants/${applicant.applicantId}/stage`, 'POST', {stage}), '변경', async () => {
            await loadApplicants();
            await displayApplicant(applicant.applicantId);
        }), {className: stage === 'REJECTED' ? 'danger' : ''});
    }

    async function schedule(applicant, interview) {
        const employees = await fetchAll(`${adminRoot}/lookups/employees`);
        const interviewer = select('interviewerId', employees.map(row => [row.id, `${row.name} · ${row.empNo}`]));
        if (interview) interviewer.value = interview.interviewerId;
        dialog(interview ? '면접 일정 수정' : '면접 일정 등록', [
            field('면접관', interviewer),
            field('면접 일시', input('scheduledAt', 'datetime-local', {required: true, value: interview?.scheduledAt ?? ''}))
        ], form => {
            const data = formValues(form);
            return api(interview ? `${adminRoot}/interviews/${interview.interviewId}/schedule`
                : `${adminRoot}/applicants/${applicant.applicantId}/interviews`, interview ? 'PUT' : 'POST',
            {...data, interviewerId: Number(data.interviewerId)});
        }, '저장', () => displayApplicant(applicant.applicantId));
    }

    async function hire(applicant) {
        const employees = await fetchAll(`${adminRoot}/lookups/employees`);
        const manager = select('managerId', employees.map(row => [row.id, `${row.name} · ${row.empNo}`]), '지정하지 않음');
        manager.required = false;
        dialog('합격자 직원 전환', [
            h('p', {className: 'muted'}, `${applicant.name} · ${applicant.posting}`),
            textField('사번', 'empNo', 30), textField('로그인 ID', 'loginId', 100),
            field('임시 비밀번호', input('initialPassword', 'password',
                {required: true, minLength: 12, maxLength: 128, autocomplete: 'new-password'})),
            h('div', {className: 'fields'}, field('직속 상사', manager),
                field('입사일', input('hireDate', 'date', {required: true})),
                field('올해 부여 연차 (일)', input('annualDays', 'number',
                    {required: true, min: '0', max: '366', step: '0.1', value: '15'})))
        ], form => {
            const data = formValues(form);
            return api(`${adminRoot}/applicants/${applicant.applicantId}/hire`, 'POST',
                {...data, managerId: data.managerId ? Number(data.managerId) : null, annualDays: Number(data.annualDays)});
        }, '직원 전환', async () => {
            await loadApplicants();
            await displayApplicant(applicant.applicantId);
        });
    }

    await refreshPostings();
    await loadApplicants();
});
