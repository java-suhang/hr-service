package com.hr.service;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 결재 엔진 — C 담당.
 *
 * [임시] B(휴가)가 컴파일되도록 2장 "도메인 간 연결점"의 시그니처만 맞춘 자리표시자다.
 * 아무 일도 하지 않는다. C의 ApprovalService가 들어오면 이 파일은 통째로 교체된다.
 */
public class ApprovalService {

    /**
     * 결재 상신. 결재 문서와 결재선(상사 → 인사 관리자)을 만든다.
     * 호출하는 쪽 트랜잭션(conn)에 합류한다.
     *
     * @return 생성된 doc_id
     */
    public long submit(Connection conn, String docType, long refId, long drafterId, String title)
            throws SQLException {
        // TODO(C): APPROVAL_DOC, APPROVAL_STEP 생성
        return 0L;
    }

    /**
     * 기안자가 진행 중인 결재 문서를 취소한다.
     * 2장 연결점에는 없는 메서드라 C와 이름·인자를 맞춰야 한다. (휴가 신청 취소 REQ-11에서 사용)
     */
    public void cancel(Connection conn, String docType, long refId, long drafterId) throws SQLException {
        // TODO(C): APPROVAL_DOC.status = CANCELED
    }
}
