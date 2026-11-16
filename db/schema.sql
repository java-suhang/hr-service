-- =====================================================================
-- HR 서비스 DDL (Oracle 11g XE 이상)
--
-- 규칙
--  * 테이블은 도메인 담당자가 자기 구역에 추가하고 전원에게 공유한다.
--  * PK는 NUMBER + 시퀀스(SEQ_테이블명).
--  * status·type 컬럼은 CHECK로 허용값을 막고, 삭제 대신 상태를 바꾼다.
--  * 실행 순서: A → B → C → D (B 이후 테이블은 EMPLOYEE를 참조한다).
-- =====================================================================


-- =====================================================================
-- A · 공통 · 인사
--   DEPARTMENT, JOB_POSITION, EMPLOYEE, AUDIT_LOG
-- =====================================================================
-- (A 담당 작성 예정)


-- =====================================================================
-- B · 근태 · 휴가
--   WORK_POLICY, ATTENDANCE, LEAVE_BALANCE, LEAVE_REQUEST
-- =====================================================================

-- 근무 규칙: 한 행만 쓴다. 시각은 'HH24:MI' 문자열.
CREATE SEQUENCE SEQ_WORK_POLICY START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE WORK_POLICY (
    policy_id           NUMBER       CONSTRAINT PK_WORK_POLICY PRIMARY KEY,
    start_time          VARCHAR2(5)  DEFAULT '09:00' NOT NULL,
    end_time            VARCHAR2(5)  DEFAULT '18:00' NOT NULL,
    late_grace_min      NUMBER(3)    DEFAULT 10 NOT NULL,
    weekly_limit_hours  NUMBER(3)    DEFAULT 52 NOT NULL,
    CONSTRAINT CK_WORK_POLICY_START CHECK (REGEXP_LIKE(start_time, '^([01][0-9]|2[0-3]):[0-5][0-9]$')),
    CONSTRAINT CK_WORK_POLICY_END   CHECK (REGEXP_LIKE(end_time,   '^([01][0-9]|2[0-3]):[0-5][0-9]$')),
    CONSTRAINT CK_WORK_POLICY_ORDER CHECK (start_time < end_time),
    CONSTRAINT CK_WORK_POLICY_GRACE CHECK (late_grace_min BETWEEN 0 AND 120),
    CONSTRAINT CK_WORK_POLICY_LIMIT CHECK (weekly_limit_hours BETWEEN 1 AND 168)
);

INSERT INTO WORK_POLICY (policy_id, start_time, end_time, late_grace_min, weekly_limit_hours)
VALUES (SEQ_WORK_POLICY.NEXTVAL, '09:00', '18:00', 10, 52);

-- 일별 근태: 직원·날짜당 1행. work_date는 시각 없이 날짜만 저장한다.
CREATE SEQUENCE SEQ_ATTENDANCE START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE ATTENDANCE (
    att_id      NUMBER        CONSTRAINT PK_ATTENDANCE PRIMARY KEY,
    emp_id      NUMBER        NOT NULL CONSTRAINT FK_ATTENDANCE_EMP REFERENCES EMPLOYEE (emp_id),
    work_date   DATE          NOT NULL,
    check_in    DATE,
    check_out   DATE,
    status      VARCHAR2(20)  DEFAULT 'NORMAL' NOT NULL,
    CONSTRAINT UK_ATTENDANCE_EMP_DATE UNIQUE (emp_id, work_date),
    CONSTRAINT CK_ATTENDANCE_STATUS   CHECK (status IN ('NORMAL', 'LATE', 'EARLY_LEAVE', 'ABSENT', 'LEAVE')),
    CONSTRAINT CK_ATTENDANCE_DATE     CHECK (work_date = TRUNC(work_date)),
    CONSTRAINT CK_ATTENDANCE_OUT      CHECK (check_out IS NULL OR (check_in IS NOT NULL AND check_out >= check_in))
);

CREATE INDEX IX_ATTENDANCE_WORK_DATE ON ATTENDANCE (work_date);

-- 연도별 연차: 해마다 새 행. 잔여 = total_days - used_days.
CREATE TABLE LEAVE_BALANCE (
    emp_id      NUMBER        NOT NULL CONSTRAINT FK_LEAVE_BALANCE_EMP REFERENCES EMPLOYEE (emp_id),
    year        NUMBER(4)     NOT NULL,
    total_days  NUMBER(4, 1)  DEFAULT 15 NOT NULL,
    used_days   NUMBER(4, 1)  DEFAULT 0 NOT NULL,
    CONSTRAINT PK_LEAVE_BALANCE PRIMARY KEY (emp_id, year),
    CONSTRAINT CK_LEAVE_BALANCE_DAYS CHECK (total_days >= 0 AND used_days >= 0 AND used_days <= total_days)
);

-- 휴가 신청: 반차는 하루, 0.5일.
CREATE SEQUENCE SEQ_LEAVE_REQUEST START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE LEAVE_REQUEST (
    req_id      NUMBER             CONSTRAINT PK_LEAVE_REQUEST PRIMARY KEY,
    emp_id      NUMBER             NOT NULL CONSTRAINT FK_LEAVE_REQUEST_EMP REFERENCES EMPLOYEE (emp_id),
    leave_type  VARCHAR2(20)       NOT NULL,
    start_date  DATE               NOT NULL,
    end_date    DATE               NOT NULL,
    days        NUMBER(4, 1)       NOT NULL,
    reason      VARCHAR2(500 CHAR),
    status      VARCHAR2(20)       DEFAULT 'PENDING' NOT NULL,
    created_at  DATE               DEFAULT SYSDATE NOT NULL,
    CONSTRAINT CK_LEAVE_REQUEST_TYPE   CHECK (leave_type IN ('ANNUAL', 'HALF_AM', 'HALF_PM', 'SICK', 'ETC')),
    CONSTRAINT CK_LEAVE_REQUEST_STATUS CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELED')),
    CONSTRAINT CK_LEAVE_REQUEST_PERIOD CHECK (end_date >= start_date),
    CONSTRAINT CK_LEAVE_REQUEST_DAYS   CHECK (days > 0),
    CONSTRAINT CK_LEAVE_REQUEST_HALF   CHECK (leave_type NOT IN ('HALF_AM', 'HALF_PM') OR (start_date = end_date AND days = 0.5))
);

CREATE INDEX IX_LEAVE_REQUEST_EMP ON LEAVE_REQUEST (emp_id);

-- B 재실행용 (필요할 때만 주석을 풀어 먼저 실행)
-- DROP TABLE LEAVE_REQUEST;  DROP SEQUENCE SEQ_LEAVE_REQUEST;
-- DROP TABLE LEAVE_BALANCE;
-- DROP TABLE ATTENDANCE;     DROP SEQUENCE SEQ_ATTENDANCE;
-- DROP TABLE WORK_POLICY;    DROP SEQUENCE SEQ_WORK_POLICY;


-- =====================================================================
-- C · 결재 · 경비 · 알림
--   APPROVAL_DOC, APPROVAL_STEP, EXPENSE, ATTACHMENT, NOTIFICATION
-- =====================================================================
-- (C 담당 작성 예정)


-- =====================================================================
-- D · 평가 · 채용 · 대시보드
--   NOTICE, EVAL_PERIOD, EVAL_ITEM, EVALUATION, EVAL_SCORE,
--   JOB_POSTING, APPLICANT, INTERVIEW
-- =====================================================================
-- (D 담당 작성 예정)

COMMIT;
