package com.hr.d.service;

import com.hr.d.dao.EvaluationDao;
import com.hr.d.dto.Actor;
import com.hr.d.dto.Page;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import com.hr.util.DBUtil;

public final class EvaluationService {
    private static final Map<String, String> NEXT = Map.of("READY", "SELF", "SELF", "MANAGER", "MANAGER", "CLOSED");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private final EvaluationDao dao = new EvaluationDao();

    public Page periods(Page.Request page, boolean admin) {
        return DBUtil.read(conn -> dao.periods(conn, page, admin));
    }

    public Map<String, Object> period(long period, boolean admin) {
        return DBUtil.read(conn -> dao.period(conn, period, admin));
    }

    public Page items(long period, Page.Request page, boolean admin) {
        return DBUtil.read(conn -> {
            dao.period(conn, period, admin);
            return dao.items(conn, period, page);
        });
    }

    public Map<String, Object> savePeriod(Long period, String name, LocalDate start, LocalDate end) {
        if (end.isBefore(start)) throw Problem.bad("종료일은 시작일 이후여야 합니다.");
        return DBUtil.tx(conn -> {
            long id;
            if (period == null) {
                id = dao.createPeriod(conn, name, start, end);
            } else {
                ready(dao.lockPeriod(conn, period));
                dao.updatePeriod(conn, period, name, start, end);
                id = period;
            }
            return dao.period(conn, id, true);
        });
    }

    public Map<String, Object> changeStatus(long id, String next) {
        if (!NEXT.containsValue(next)) throw Problem.bad("평가 상태 값을 확인해 주세요.");
        return DBUtil.tx(conn -> {
            var period = dao.lockPeriod(conn, id);
            if (!next.equals(NEXT.get(period.get("status")))) {
                throw Problem.conflict("평가 단계는 준비 → 자기평가 → 상사평가 → 마감 순서로 전환합니다.");
            }
            if ("SELF".equals(next)) requireCompleteWeights(dao.weights(conn, id));
            dao.setStatus(conn, id, next);
            return dao.period(conn, id, true);
        });
    }

    public long addItem(long period, String name, String description, BigDecimal weight) {
        return DBUtil.tx(conn -> {
            ready(dao.lockPeriod(conn, period));
            return dao.addItem(conn, period, name, description, weight);
        });
    }

    public void updateItem(long item, String name, String description, BigDecimal weight) {
        DBUtil.tx(conn -> {
            long period = dao.periodForItem(conn, item);
            ready(dao.lockPeriod(conn, period));
            if (dao.updateItem(conn, item, period, name, description, weight) == 0) throw Problem.missing();
            return null;
        });
    }

    public void deleteItem(long item) {
        DBUtil.tx(conn -> {
            long period = dao.periodForItem(conn, item);
            ready(dao.lockPeriod(conn, period));
            if (dao.deleteItem(conn, item, period) == 0) throw Problem.missing();
            return null;
        });
    }

    public Map<String, Object> submit(long actor, long periodId, long target, String type, String comment,
                                      Map<Long, Integer> scores) {
        if (!"SELF".equals(type) && !"MANAGER".equals(type)) throw Problem.bad("평가 종류를 확인해 주세요.");
        if ("SELF".equals(type) && actor != target) throw Problem.forbidden("본인의 자기평가만 제출할 수 있습니다.");
        if ("MANAGER".equals(type) && actor == target) throw Problem.forbidden("본인의 상사평가는 제출할 수 없습니다.");
        return DBUtil.tx(conn -> {
            var period = dao.lockPeriod(conn, periodId);
            var employee = dao.lockEmployee(conn, target);
            var managerId = (Number) employee.get("managerId");
            if ("RESIGNED".equals(employee.get("status"))) throw Problem.conflict("퇴사한 직원은 평가할 수 없습니다.");
            if ("MANAGER".equals(type) && (managerId == null || managerId.longValue() != actor)) {
                throw Problem.forbidden("직속 상사만 상사평가를 제출할 수 있습니다.");
            }
            if (!type.equals(period.get("status")) || number(period, "withinDates") != 1) {
                throw Problem.conflict("현재 이 평가를 제출할 수 있는 기간이 아닙니다.");
            }
            var existing = dao.existing(conn, periodId, target, type);
            if (existing != null && "SUBMITTED".equals(existing.get("status"))) {
                throw Problem.conflict("이미 제출된 평가는 수정할 수 없습니다.");
            }
            var weights = dao.weights(conn, periodId);
            requireCompleteWeights(weights);
            BigDecimal total = total(weights, scores);
            long id = dao.save(conn, existing == null ? null : number(existing, "evalId"),
                    periodId, target, actor, type, total, comment, scores);
            return Map.of("evalId", id, "totalScore", total);
        });
    }

    static BigDecimal total(Map<Long, BigDecimal> weights, Map<Long, Integer> scores) {
        if (!weights.keySet().equals(scores.keySet())) throw Problem.bad("각 평가 항목에 점수를 하나씩 입력해 주세요.");
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : weights.entrySet()) {
            Integer score = scores.get(entry.getKey());
            if (score == null || score < 1 || score > 5) throw Problem.bad("점수는 1~5 사이의 정수여야 합니다.");
            total = total.add(entry.getValue().multiply(BigDecimal.valueOf(score)));
        }
        return total.movePointLeft(2).stripTrailingZeros();
    }

    public Page mine(long id, long period, Page.Request page) {
        return DBUtil.read(conn -> {
            var record = dao.period(conn, period, false);
            return dao.submittedFor(conn, period, id, "CLOSED".equals(record.get("status")), page);
        });
    }

    public Page reviews(long id, long period, Page.Request page) {
        return DBUtil.read(conn -> {
            dao.period(conn, period, false);
            return dao.reviewsBy(conn, period, id, page);
        });
    }

    public Page team(long id, Page.Request page) {
        return DBUtil.read(conn -> dao.team(conn, id, page));
    }

    public Page status(long period, Page.Request page) {
        return DBUtil.read(conn -> {
            dao.period(conn, period, true);
            return dao.status(conn, period, page);
        });
    }

    public Map<String, Object> result(Actor actor, boolean admin, long id) {
        return DBUtil.read(conn -> {
            var row = dao.result(conn, id);
            boolean participant = number(row, "evaluatorId") == actor.id()
                    || number(row, "targetId") == actor.id() && "CLOSED".equals(row.get("periodStatus"));
            if (!admin && !participant) throw Problem.missing();
            row.remove("evaluatorId");
            row.remove("targetId");
            row.put("scores", dao.scores(conn, id));
            return row;
        });
    }

    private static void ready(Map<String, Object> period) {
        if (!"READY".equals(period.get("status"))) throw Problem.conflict("준비 상태에서만 평가 기간과 항목을 수정할 수 있습니다.");
    }

    private static void requireCompleteWeights(Map<Long, BigDecimal> weights) {
        if (weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(HUNDRED) != 0) {
            throw Problem.conflict("평가 항목의 가중치 합이 100이어야 합니다.");
        }
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
}
