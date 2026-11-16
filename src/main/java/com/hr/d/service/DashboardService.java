package com.hr.d.service;

import com.hr.d.dao.DashboardDao;
import com.hr.d.dao.NoticeDao;
import com.hr.d.dto.Page;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import com.hr.util.DBUtil;

public final class DashboardService {
    private final DashboardDao dao = new DashboardDao();

    public Map<String, Object> user(long id, LocalDate today) {
        return DBUtil.read(conn -> {
            var data = new LinkedHashMap<String, Object>();
            var unavailable = new ArrayList<String>();
            data.put("attendance", optional(conn, unavailable, "attendance", c -> dao.attendance(c, id, today)));
            data.put("leave", optional(conn, unavailable, "leave", c -> dao.leave(c, id, today.getYear())));
            data.put("approvals", optional(conn, unavailable, "approvals", c -> dao.approvals(c, id)));
            data.put("unreadNotifications", optional(conn, unavailable, "notifications", c -> dao.unread(c, id)));
            data.put("notices", new NoticeDao().list(conn, new Page.Request(1, 5), null).items());
            data.put("unavailable", unavailable);
            return data;
        });
    }

    public Map<String, Object> admin(LocalDate today) {
        return DBUtil.read(conn -> {
            var data = new LinkedHashMap<String, Object>();
            var unavailable = new ArrayList<String>();
            long active = dao.activeEmployees(conn);
            var departments = dao.departments(conn);
            var attendance = optional(conn, unavailable, "attendance", c -> dao.departmentAttendance(c, today));
            Long checkedIn = null;
            Double rate = null;
            if (attendance != null) {
                var counts = new HashMap<Long, Long>();
                for (var row : attendance) counts.put(number(row, "deptId"), number(row, "checkedIn"));
                for (var department : departments) {
                    department.put("checkedIn", counts.getOrDefault(number(department, "deptId"), 0L));
                }
                checkedIn = counts.values().stream().mapToLong(Long::longValue).sum();
                rate = active == 0 ? 0 : Math.round(1000.0 * checkedIn / active) / 10.0;
            }
            data.put("activeEmployees", active);
            data.put("checkedIn", checkedIn);
            data.put("attendanceRate", rate);
            data.put("departments", departments);
            data.put("pendingApprovals", optional(conn, unavailable, "approvals", dao::pending));
            data.put("recruitment", dao.recruiting(conn));
            data.put("evaluation", dao.evaluation(conn));
            data.put("unavailable", unavailable);
            return data;
        });
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static <T> T optional(Connection conn, List<String> unavailable, String domain, DBUtil.SqlWork<T> query)
            throws SQLException {
        try {
            return query.run(conn);
        } catch (SQLException ex) {
            if (ex.getErrorCode() != 942 && !"42S02".equals(ex.getSQLState())) throw ex;
            unavailable.add(domain);
            return null;
        }
    }
}
