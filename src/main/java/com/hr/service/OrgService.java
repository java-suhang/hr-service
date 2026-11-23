package com.hr.service;

import java.util.List;
import java.util.Map;

import com.hr.dao.OrgDAO;
import com.hr.dao.OrgDAO.Department;
import com.hr.dao.OrgDAO.Position;
import com.hr.util.ApiException;
import com.hr.util.DBUtil;

/** 부서·직급 관리 (REQ-06). 직원이 한 명이라도 속했던(퇴사자 포함) 부서·직급은 지울 수 없다. */
public class OrgService {

    private final OrgDAO dao = new OrgDAO();
    private final AuditService audit = new AuditService();

    // ------------------------------------------------------------------ 부서

    public List<Department> departments() {
        return DBUtil.read(dao::departments);
    }

    public Department createDepartment(Map<String, Object> body, long actorId) {
        String name = deptName(body);
        return DBUtil.tx(conn -> {
            if (dao.nameExists(conn, true, name, null)) {
                throw ApiException.conflict("이미 있는 부서명입니다");
            }
            long id = dao.insertDepartment(conn, name);
            Department d = dao.department(conn, id);
            audit.log(conn, actorId, AuditService.CREATE, "DEPARTMENT", id, null, d);
            return d;
        });
    }

    public Department updateDepartment(long id, Map<String, Object> body, long actorId) {
        String name = deptName(body);
        return DBUtil.tx(conn -> {
            Department before = dao.department(conn, id);
            if (before == null) {
                throw ApiException.notFound("부서를 찾을 수 없습니다");
            }
            if (dao.nameExists(conn, true, name, id)) {
                throw ApiException.conflict("이미 있는 부서명입니다");
            }
            dao.updateDepartment(conn, id, name);
            Department after = dao.department(conn, id);
            audit.log(conn, actorId, AuditService.UPDATE, "DEPARTMENT", id, before, after);
            return after;
        });
    }

    public void deleteDepartment(long id, long actorId) {
        DBUtil.tx(conn -> {
            Department d = dao.department(conn, id);
            if (d == null) {
                throw ApiException.notFound("부서를 찾을 수 없습니다");
            }
            if (d.totalCount() > 0) {
                throw ApiException.conflict("직원 기록(퇴사자 포함)이 있는 부서는 삭제할 수 없습니다");
            }
            dao.deleteDepartment(conn, id);
            audit.log(conn, actorId, AuditService.DELETE, "DEPARTMENT", id, d, null);
            return null;
        });
    }

    // ------------------------------------------------------------------ 직급

    public List<Position> positions() {
        return DBUtil.read(dao::positions);
    }

    public Position createPosition(Map<String, Object> body, long actorId) {
        String name = posName(body);
        int level = level(body);
        return DBUtil.tx(conn -> {
            if (dao.nameExists(conn, false, name, null)) {
                throw ApiException.conflict("이미 있는 직급명입니다");
            }
            long id = dao.insertPosition(conn, name, level);
            Position p = dao.position(conn, id);
            audit.log(conn, actorId, AuditService.CREATE, "JOB_POSITION", id, null, p);
            return p;
        });
    }

    public Position updatePosition(long id, Map<String, Object> body, long actorId) {
        String name = posName(body);
        int level = level(body);
        return DBUtil.tx(conn -> {
            Position before = dao.position(conn, id);
            if (before == null) {
                throw ApiException.notFound("직급을 찾을 수 없습니다");
            }
            if (dao.nameExists(conn, false, name, id)) {
                throw ApiException.conflict("이미 있는 직급명입니다");
            }
            dao.updatePosition(conn, id, name, level);
            Position after = dao.position(conn, id);
            audit.log(conn, actorId, AuditService.UPDATE, "JOB_POSITION", id, before, after);
            return after;
        });
    }

    public void deletePosition(long id, long actorId) {
        DBUtil.tx(conn -> {
            Position p = dao.position(conn, id);
            if (p == null) {
                throw ApiException.notFound("직급을 찾을 수 없습니다");
            }
            if (p.totalCount() > 0) {
                throw ApiException.conflict("직원 기록(퇴사자 포함)이 있는 직급은 삭제할 수 없습니다");
            }
            dao.deletePosition(conn, id);
            audit.log(conn, actorId, AuditService.DELETE, "JOB_POSITION", id, p, null);
            return null;
        });
    }

    // ------------------------------------------------------------------ 입력

    private static String deptName(Map<String, Object> body) {
        String name = BaseInput.requireText(body.get("name"), "부서명");
        if (name.length() > 50) {
            throw ApiException.badRequest("부서명은 50자 이하로 입력하세요");
        }
        return name;
    }

    private static String posName(Map<String, Object> body) {
        String name = BaseInput.requireText(body.get("name"), "직급명");
        if (name.length() > 30) {
            throw ApiException.badRequest("직급명은 30자 이하로 입력하세요");
        }
        return name;
    }

    private static int level(Map<String, Object> body) {
        int level = BaseInput.requireInt(body.get("level"), "직급 레벨");
        if (level < 1 || level > 99) {
            throw ApiException.badRequest("직급 레벨은 1~99 사이로 입력하세요");
        }
        return level;
    }
}
