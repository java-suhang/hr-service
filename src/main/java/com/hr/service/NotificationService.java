package com.hr.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.hr.dao.NotificationDAO;
import com.hr.util.ApiException;
import com.hr.util.DBUtil;

/**
 * 알림 (REQ-20). 결재 요청·진행·결과, 경비 지급, 공지 등록을 받는 사람에게 남기고 읽음 처리한다.
 *
 * 다른 도메인이 부르는 메서드 (호출하는 쪽 트랜잭션 conn에 합류):
 *  - send : 결재 엔진, 경비 지급, D의 공지 등록(NoticeNotifier 어댑터)
 */
public class NotificationService {

    public static final String APPROVAL_REQUEST = "APPROVAL_REQUEST";
    public static final String APPROVAL_STEP = "APPROVAL_STEP";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String EXPENSE_PAID = "EXPENSE_PAID";
    public static final String NOTICE = "NOTICE";

    private static final Set<String> TYPES = Set.of(APPROVAL_REQUEST, APPROVAL_STEP, APPROVED, REJECTED, EXPENSE_PAID, NOTICE);
    private static final int MESSAGE_MAX = 300;
    /** 앱 안의 화면 경로만 링크로 남긴다 (예: /user/approvals.html?id=3) */
    private static final Pattern LINK = Pattern.compile("^/(user|admin)/[A-Za-z0-9_-]+\\.html(\\?[A-Za-z0-9=&_.-]{0,200})?$");

    private final NotificationDAO dao = new NotificationDAO();

    public void send(Connection conn, long empId, String type, String message, String link) throws SQLException {
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("알 수 없는 알림 종류: " + type);
        }
        String text = message == null ? "" : message.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("알림 내용이 비어 있습니다");
        }
        String safeLink = link != null && LINK.matcher(link).matches() ? link : null;
        dao.insert(conn, empId, type, cut(text, MESSAGE_MAX), safeLink);
    }

    /** {items, total, page, size, unread} */
    public Map<String, Object> list(long empId, int page, int size) {
        return DBUtil.read(conn -> {
            int total = dao.count(conn, empId, false);
            List<NotificationDAO.Notification> items = dao.find(conn, empId, (page - 1) * size, size);
            Map<String, Object> m = AttendanceService.pageResult(items, total, page, size);
            m.put("unread", dao.count(conn, empId, true));
            return m;
        });
    }

    public int unreadCount(long empId) {
        return DBUtil.read(conn -> dao.count(conn, empId, true));
    }

    public void markRead(long empId, long notiId) {
        DBUtil.tx(conn -> {
            if (dao.markRead(conn, empId, notiId) == 0) {
                throw ApiException.notFound("알림을 찾을 수 없습니다");
            }
            return null;
        });
    }

    public int markAllRead(long empId) {
        return DBUtil.tx(conn -> dao.markAllRead(conn, empId));
    }

    /** 글자 수를 넘으면 자른다. 서로게이트 쌍은 쪼개지 않는다 */
    static String cut(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        int end = max - 1;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end) + "…";
    }
}
