package com.hr.d.service;

import com.hr.d.dao.NoticeDao;
import com.hr.d.dto.Page;
import com.hr.d.integration.NoticeNotifier;
import java.util.Map;
import com.hr.util.DBUtil;

public final class NoticeService {
    private final NoticeDao dao = new NoticeDao();

    public Page list(Page.Request page, String search) {
        return DBUtil.read(conn -> dao.list(conn, page, search));
    }

    public Map<String, Object> find(long id) {
        return DBUtil.read(conn -> dao.find(conn, id));
    }

    public Map<String, Object> create(long writer, String title, String content, boolean pinned,
                                      NoticeNotifier notifier) {
        return DBUtil.tx(conn -> {
            long id = dao.insert(conn, writer, title, content, pinned);
            if (notifier != null) {
                for (long empId : dao.recipients(conn)) {
                    if (empId != writer) {
                        notifier.send(conn, empId, "NOTICE", "새 공지: " + title, "/user/notices.html?id=" + id);
                    }
                }
            }
            return Map.of("noticeId", id, "notificationsEnabled", notifier != null);
        });
    }

    public Map<String, Object> update(long id, String title, String content, boolean pinned) {
        return DBUtil.tx(conn -> {
            dao.update(conn, id, title, content, pinned);
            return dao.find(conn, id);
        });
    }

    public void delete(long id) {
        DBUtil.tx(conn -> {
            dao.delete(conn, id);
            return null;
        });
    }
}
