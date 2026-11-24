/* 근무 규칙 설정 (REQ-16) */
(function () {
    Layout.admin('work-policy');

    const $ = (id) => document.getElementById(id);

    function fill(p) {
        $('startTime').value = p.startTime;
        $('endTime').value = p.endTime;
        $('lateGraceMin').value = p.lateGraceMin;
        $('weeklyLimitHours').value = p.weeklyLimitHours;
    }

    api.get('admin/work-policy').then(fill).catch((e) => toast(e.message, 'error'));

    $('policyForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        $('saveBtn').disabled = true;
        try {
            fill(await api.put('admin/work-policy', {
                startTime: $('startTime').value,
                endTime: $('endTime').value,
                lateGraceMin: $('lateGraceMin').value,
                weeklyLimitHours: $('weeklyLimitHours').value,
            }));
            toast('근무 규칙을 저장했습니다');
        } catch (err) {
            toast(err.message, 'error');
        } finally {
            $('saveBtn').disabled = false;
        }
    });
})();
