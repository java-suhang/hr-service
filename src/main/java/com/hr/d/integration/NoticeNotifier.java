package com.hr.d.integration;

import java.sql.Connection;
import java.sql.SQLException;

/** Bridge to C's NotificationService.send. The caller owns the connection and transaction. */
@FunctionalInterface
public interface NoticeNotifier {
    void send(Connection conn, long empId, String type, String message, String link) throws SQLException;
}
