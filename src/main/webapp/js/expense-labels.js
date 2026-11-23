/* 경비 분류·상태 이름 (사용자·관리자 화면 공용). 서버 ExpenseService.CATEGORIES와 같은 순서 */
window.EXPENSE = {
    CATEGORIES: [['MEAL', '식대'], ['TRANSPORT', '교통'], ['TRAVEL', '출장·숙박'], ['SUPPLIES', '비품'], ['BOOK', '도서·교육'], ['ETC', '기타']],
    STATUS: {
        PENDING: ['진행 중', 'badge-yellow'],
        APPROVED: ['지급 대기', 'badge-blue'],
        PAID: ['지급 완료', 'badge-green'],
        REJECTED: ['반려', 'badge-red'],
        CANCELED: ['취소', 'badge-gray'],
    },
    label(code) {
        const found = this.CATEGORIES.find(([c]) => c === code);
        return found ? found[1] : code;
    },
    badge(status) {
        const [label, cls] = this.STATUS[status] || [status, 'badge-gray'];
        return '<span class="badge ' + cls + '">' + label + '</span>';
    },
    won: (n) => Number(n).toLocaleString('ko-KR'),
    /** 영수증 링크 (클립 아이콘) */
    clip(fileId) {
        return fileId ? '<a class="clip" href="' + esc(api.url('files/' + fileId)) + '" target="_blank" rel="noopener" aria-label="영수증 보기">📎</a>' : '-';
    },
};
