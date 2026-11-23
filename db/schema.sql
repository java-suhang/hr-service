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
-- 부서
CREATE SEQUENCE SEQ_DEPARTMENT START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE DEPARTMENT (
    dept_id     NUMBER            CONSTRAINT PK_DEPARTMENT PRIMARY KEY,
    name        VARCHAR2(50 CHAR) NOT NULL,
    created_at  DATE              DEFAULT SYSDATE NOT NULL,
    CONSTRAINT UK_DEPARTMENT_NAME UNIQUE (name)
);

-- 직급: pos_level이 클수록 상위 직급
CREATE SEQUENCE SEQ_JOB_POSITION START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE JOB_POSITION (
    pos_id     NUMBER            CONSTRAINT PK_JOB_POSITION PRIMARY KEY,
    name       VARCHAR2(30 CHAR) NOT NULL,
    pos_level  NUMBER(2)         NOT NULL,
    CONSTRAINT UK_JOB_POSITION_NAME  UNIQUE (name),
    CONSTRAINT CK_JOB_POSITION_LEVEL CHECK (pos_level BETWEEN 1 AND 99)
);

-- 직원 = 로그인 계정. 상사(manager_id)가 결재 1단계와 상사평가자가 된다.
-- 삭제하지 않고 status를 RESIGNED로 바꾼다. password_hash는 PBKDF2 (PasswordUtil 참고).
CREATE SEQUENCE SEQ_EMPLOYEE START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE EMPLOYEE (
    emp_id         NUMBER             CONSTRAINT PK_EMPLOYEE PRIMARY KEY,
    emp_no         VARCHAR2(20)       NOT NULL,
    login_id       VARCHAR2(30)       NOT NULL,
    password_hash  VARCHAR2(200)      NOT NULL,
    name           VARCHAR2(50 CHAR)  NOT NULL,
    email          VARCHAR2(100),
    phone          VARCHAR2(20),
    dept_id        NUMBER             CONSTRAINT FK_EMPLOYEE_DEPT REFERENCES DEPARTMENT (dept_id),
    pos_id         NUMBER             CONSTRAINT FK_EMPLOYEE_POS REFERENCES JOB_POSITION (pos_id),
    manager_id     NUMBER             CONSTRAINT FK_EMPLOYEE_MANAGER REFERENCES EMPLOYEE (emp_id),
    role           VARCHAR2(10)       DEFAULT 'USER' NOT NULL,
    hire_date      DATE               NOT NULL,
    status         VARCHAR2(10)       DEFAULT 'ACTIVE' NOT NULL,
    created_at     DATE               DEFAULT SYSDATE NOT NULL,
    updated_at     DATE               DEFAULT SYSDATE NOT NULL,
    CONSTRAINT UK_EMPLOYEE_EMP_NO   UNIQUE (emp_no),
    CONSTRAINT UK_EMPLOYEE_LOGIN_ID UNIQUE (login_id),
    CONSTRAINT CK_EMPLOYEE_ROLE     CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT CK_EMPLOYEE_STATUS   CHECK (status IN ('ACTIVE', 'LEAVE', 'RESIGNED')),
    CONSTRAINT CK_EMPLOYEE_MANAGER  CHECK (manager_id IS NULL OR manager_id <> emp_id)
);

CREATE INDEX IX_EMPLOYEE_DEPT ON EMPLOYEE (dept_id);
CREATE INDEX IX_EMPLOYEE_MANAGER ON EMPLOYEE (manager_id);

-- 감사 로그: 누가 언제 어떤 데이터를 바꿨는지. before_val/after_val은 JSON 문자열 (비밀번호는 남기지 않는다).
CREATE SEQUENCE SEQ_AUDIT_LOG START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE AUDIT_LOG (
    log_id        NUMBER        CONSTRAINT PK_AUDIT_LOG PRIMARY KEY,
    actor_id      NUMBER        CONSTRAINT FK_AUDIT_LOG_ACTOR REFERENCES EMPLOYEE (emp_id),
    action        VARCHAR2(20)  NOT NULL,
    target_table  VARCHAR2(30)  NOT NULL,
    target_id     NUMBER,
    before_val    CLOB,
    after_val     CLOB,
    created_at    DATE          DEFAULT SYSDATE NOT NULL
);

CREATE INDEX IX_AUDIT_LOG_TARGET ON AUDIT_LOG (target_table, target_id);
CREATE INDEX IX_AUDIT_LOG_CREATED ON AUDIT_LOG (created_at);

-- A 재실행용 (B·C·D 테이블을 먼저 지운 뒤, 필요할 때만 주석을 풀어 실행)
-- DROP TABLE AUDIT_LOG;     DROP SEQUENCE SEQ_AUDIT_LOG;
-- DROP TABLE EMPLOYEE;      DROP SEQUENCE SEQ_EMPLOYEE;
-- DROP TABLE JOB_POSITION;  DROP SEQUENCE SEQ_JOB_POSITION;
-- DROP TABLE DEPARTMENT;    DROP SEQUENCE SEQ_DEPARTMENT;


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
-- 결재 문서: 휴가·경비가 같이 쓴다. ref_id는 doc_type에 따라 LEAVE_REQUEST.req_id 또는 EXPENSE.exp_id를
-- 가리키므로 FK 대신 서비스에서 존재를 확인한다. current_step은 지금 처리할 단계 순서다.
CREATE SEQUENCE SEQ_APPROVAL_DOC START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE APPROVAL_DOC (
    doc_id        NUMBER             CONSTRAINT PK_APPROVAL_DOC PRIMARY KEY,
    doc_type      VARCHAR2(20)       NOT NULL,
    ref_id        NUMBER             NOT NULL,
    drafter_id    NUMBER             NOT NULL CONSTRAINT FK_APPROVAL_DOC_DRAFTER REFERENCES EMPLOYEE (emp_id),
    title         VARCHAR2(200 CHAR) NOT NULL,
    status        VARCHAR2(20)       DEFAULT 'IN_PROGRESS' NOT NULL,
    current_step  NUMBER(2)          DEFAULT 1 NOT NULL,
    created_at    DATE               DEFAULT SYSDATE NOT NULL,
    completed_at  DATE,
    CONSTRAINT UK_APPROVAL_DOC_REF    UNIQUE (doc_type, ref_id),
    CONSTRAINT CK_APPROVAL_DOC_TYPE   CHECK (doc_type IN ('LEAVE', 'EXPENSE')),
    CONSTRAINT CK_APPROVAL_DOC_STATUS CHECK (status IN ('IN_PROGRESS', 'APPROVED', 'REJECTED', 'CANCELED')),
    CONSTRAINT CK_APPROVAL_DOC_DONE   CHECK ((status = 'IN_PROGRESS' AND completed_at IS NULL)
                                          OR (status <> 'IN_PROGRESS' AND completed_at IS NOT NULL))
);

CREATE INDEX IX_APPROVAL_DOC_DRAFTER ON APPROVAL_DOC (drafter_id, status);
CREATE INDEX IX_APPROVAL_DOC_STATUS  ON APPROVAL_DOC (status, created_at);

-- 결재 단계: 1단계 상사(MANAGER) → 2단계 인사 관리자(HR). 상사가 없거나 재직 중이 아니면 HR만 남고,
-- 상사가 관리자 권한이면 상사 단계로 끝난다. HR 단계는 관리자 누구나 처리하며 처리한 사람이 approver_id에 남는다.
-- status: WAITING(차례 전) → PENDING(지금 차례) → APPROVED/REJECTED, 문서가 끝나 처리하지 않은 단계는 CANCELED
CREATE SEQUENCE SEQ_APPROVAL_STEP START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE APPROVAL_STEP (
    step_id       NUMBER             CONSTRAINT PK_APPROVAL_STEP PRIMARY KEY,
    doc_id        NUMBER             NOT NULL CONSTRAINT FK_APPROVAL_STEP_DOC REFERENCES APPROVAL_DOC (doc_id),
    step_order    NUMBER(2)          NOT NULL,
    step_role     VARCHAR2(10)       NOT NULL,
    approver_id   NUMBER             CONSTRAINT FK_APPROVAL_STEP_APPROVER REFERENCES EMPLOYEE (emp_id),
    status        VARCHAR2(10)       DEFAULT 'WAITING' NOT NULL,
    comment_text  VARCHAR2(500 CHAR),
    acted_at      DATE,
    CONSTRAINT UK_APPROVAL_STEP_ORDER  UNIQUE (doc_id, step_order),
    CONSTRAINT CK_APPROVAL_STEP_ORDER  CHECK (step_order BETWEEN 1 AND 9),
    CONSTRAINT CK_APPROVAL_STEP_ROLE   CHECK (step_role IN ('MANAGER', 'HR')),
    CONSTRAINT CK_APPROVAL_STEP_STATUS CHECK (status IN ('WAITING', 'PENDING', 'APPROVED', 'REJECTED', 'CANCELED')),
    CONSTRAINT CK_APPROVAL_STEP_WHO    CHECK (step_role = 'HR' OR approver_id IS NOT NULL),
    CONSTRAINT CK_APPROVAL_STEP_ACTED  CHECK ((status IN ('APPROVED', 'REJECTED') AND acted_at IS NOT NULL AND approver_id IS NOT NULL)
                                           OR (status NOT IN ('APPROVED', 'REJECTED') AND acted_at IS NULL))
);

CREATE INDEX IX_APPROVAL_STEP_APPROVER ON APPROVAL_STEP (approver_id, status);
CREATE INDEX IX_APPROVAL_STEP_ROLE     ON APPROVAL_STEP (step_role, status);

-- 경비 정산: 최종 승인되면 APPROVED(지급 대기), 관리자가 지급하면 PAID
CREATE SEQUENCE SEQ_EXPENSE START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE EXPENSE (
    exp_id       NUMBER             CONSTRAINT PK_EXPENSE PRIMARY KEY,
    emp_id       NUMBER             NOT NULL CONSTRAINT FK_EXPENSE_EMP REFERENCES EMPLOYEE (emp_id),
    category     VARCHAR2(20)       NOT NULL,
    amount       NUMBER(10)         NOT NULL,
    use_date     DATE               NOT NULL,
    description  VARCHAR2(200 CHAR) NOT NULL,
    status       VARCHAR2(10)       DEFAULT 'PENDING' NOT NULL,
    created_at   DATE               DEFAULT SYSDATE NOT NULL,
    paid_at      DATE,
    CONSTRAINT CK_EXPENSE_CATEGORY CHECK (category IN ('MEAL', 'TRANSPORT', 'TRAVEL', 'SUPPLIES', 'BOOK', 'ETC')),
    CONSTRAINT CK_EXPENSE_AMOUNT   CHECK (amount > 0),
    CONSTRAINT CK_EXPENSE_STATUS   CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'PAID', 'CANCELED')),
    CONSTRAINT CK_EXPENSE_PAID     CHECK ((status = 'PAID' AND paid_at IS NOT NULL) OR (status <> 'PAID' AND paid_at IS NULL))
);

CREATE INDEX IX_EXPENSE_EMP    ON EXPENSE (emp_id, use_date);
CREATE INDEX IX_EXPENSE_STATUS ON EXPENSE (status, use_date);

-- 첨부파일 (영수증 EXPENSE, 이력서 APPLICANT). 파일은 upload.dir 폴더에 stored_name(UUID.확장자)으로 저장한다.
-- ref_id는 ref_type에 따라 다른 테이블을 가리키므로 FK 대신 서비스에서 확인한다.
CREATE SEQUENCE SEQ_ATTACHMENT START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE ATTACHMENT (
    file_id      NUMBER             CONSTRAINT PK_ATTACHMENT PRIMARY KEY,
    ref_type     VARCHAR2(20)       NOT NULL,
    ref_id       NUMBER             NOT NULL,
    origin_name  VARCHAR2(200 CHAR) NOT NULL,
    stored_name  VARCHAR2(64)       NOT NULL,
    file_size    NUMBER(10)         NOT NULL,
    uploaded_at  DATE               DEFAULT SYSDATE NOT NULL,
    CONSTRAINT UK_ATTACHMENT_STORED  UNIQUE (stored_name),
    CONSTRAINT CK_ATTACHMENT_REF     CHECK (ref_type IN ('EXPENSE', 'APPLICANT')),
    CONSTRAINT CK_ATTACHMENT_SIZE    CHECK (file_size > 0)
);

CREATE INDEX IX_ATTACHMENT_REF ON ATTACHMENT (ref_type, ref_id);

-- 알림: 결재 요청·진행·결과, 경비 지급, 공지(D)
CREATE SEQUENCE SEQ_NOTIFICATION START WITH 1 INCREMENT BY 1 NOCACHE;

CREATE TABLE NOTIFICATION (
    noti_id     NUMBER             CONSTRAINT PK_NOTIFICATION PRIMARY KEY,
    emp_id      NUMBER             NOT NULL CONSTRAINT FK_NOTIFICATION_EMP REFERENCES EMPLOYEE (emp_id),
    noti_type   VARCHAR2(20)       NOT NULL,
    message     VARCHAR2(300 CHAR) NOT NULL,
    link        VARCHAR2(300),
    is_read     CHAR(1)            DEFAULT 'N' NOT NULL,
    created_at  DATE               DEFAULT SYSDATE NOT NULL,
    CONSTRAINT CK_NOTIFICATION_TYPE CHECK (noti_type IN ('APPROVAL_REQUEST', 'APPROVAL_STEP', 'APPROVED', 'REJECTED',
                                                         'EXPENSE_PAID', 'NOTICE')),
    CONSTRAINT CK_NOTIFICATION_READ CHECK (is_read IN ('Y', 'N'))
);

CREATE INDEX IX_NOTIFICATION_EMP ON NOTIFICATION (emp_id, is_read);

-- C 재실행용 (필요할 때만 주석을 풀어 먼저 실행)
-- DROP TABLE NOTIFICATION;   DROP SEQUENCE SEQ_NOTIFICATION;
-- DROP TABLE ATTACHMENT;     DROP SEQUENCE SEQ_ATTACHMENT;
-- DROP TABLE EXPENSE;        DROP SEQUENCE SEQ_EXPENSE;
-- DROP TABLE APPROVAL_STEP;  DROP SEQUENCE SEQ_APPROVAL_STEP;
-- DROP TABLE APPROVAL_DOC;   DROP SEQUENCE SEQ_APPROVAL_DOC;


-- =====================================================================
-- D · 평가 · 채용 · 대시보드
--   NOTICE, EVAL_PERIOD, EVAL_ITEM, EVALUATION, EVAL_SCORE,
--   JOB_POSTING, APPLICANT, INTERVIEW
-- =====================================================================
-- D domain. Apply after A provides DEPARTMENT, JOB_POSITION and EMPLOYEE.
CREATE TABLE NOTICE (
    notice_id NUMBER PRIMARY KEY,
    title VARCHAR2(200 CHAR) NOT NULL,
    content CLOB NOT NULL,
    is_pinned CHAR(1) DEFAULT 'N' NOT NULL CHECK (is_pinned IN ('Y','N')),
    writer_id NUMBER NOT NULL REFERENCES EMPLOYEE(emp_id),
    created_at DATE DEFAULT SYSDATE NOT NULL,
    updated_at DATE DEFAULT SYSDATE NOT NULL,
    deleted_at DATE
);

CREATE TABLE EVAL_PERIOD (
    period_id NUMBER PRIMARY KEY,
    name VARCHAR2(120 CHAR) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR2(12) DEFAULT 'READY' NOT NULL CHECK (status IN ('READY','SELF','MANAGER','CLOSED')),
    CHECK (end_date >= start_date)
);

CREATE TABLE EVAL_ITEM (
    item_id NUMBER PRIMARY KEY,
    period_id NUMBER NOT NULL REFERENCES EVAL_PERIOD(period_id),
    name VARCHAR2(150 CHAR) NOT NULL,
    description VARCHAR2(1000 CHAR),
    weight NUMBER(5,2) NOT NULL CHECK (weight > 0 AND weight <= 100)
);

CREATE TABLE EVALUATION (
    eval_id NUMBER PRIMARY KEY,
    period_id NUMBER NOT NULL REFERENCES EVAL_PERIOD(period_id),
    target_id NUMBER NOT NULL REFERENCES EMPLOYEE(emp_id),
    evaluator_id NUMBER NOT NULL REFERENCES EMPLOYEE(emp_id),
    eval_type VARCHAR2(10) NOT NULL CHECK (eval_type IN ('SELF','MANAGER')),
    total_score NUMBER(5,4) CHECK (total_score BETWEEN 1 AND 5),
    comment_text VARCHAR2(1000 CHAR),
    status VARCHAR2(12) DEFAULT 'DRAFT' NOT NULL CHECK (status IN ('DRAFT','SUBMITTED')),
    submitted_at DATE,
    CONSTRAINT UK_EVAL_TARGET UNIQUE (period_id, target_id, eval_type)
);

CREATE TABLE EVAL_SCORE (
    eval_id NUMBER NOT NULL REFERENCES EVALUATION(eval_id),
    item_id NUMBER NOT NULL REFERENCES EVAL_ITEM(item_id),
    score NUMBER NOT NULL CHECK (score BETWEEN 1 AND 5 AND score = TRUNC(score)),
    PRIMARY KEY (eval_id, item_id)
);

CREATE TABLE JOB_POSTING (
    posting_id NUMBER PRIMARY KEY,
    title VARCHAR2(200 CHAR) NOT NULL,
    dept_id NUMBER NOT NULL REFERENCES DEPARTMENT(dept_id),
    pos_id NUMBER NOT NULL REFERENCES JOB_POSITION(pos_id),
    description CLOB,
    open_date DATE NOT NULL,
    close_date DATE NOT NULL,
    status VARCHAR2(10) DEFAULT 'OPEN' NOT NULL CHECK (status IN ('OPEN','CLOSED')),
    CHECK (close_date >= open_date)
);

CREATE TABLE APPLICANT (
    app_id NUMBER PRIMARY KEY,
    posting_id NUMBER NOT NULL REFERENCES JOB_POSTING(posting_id),
    name VARCHAR2(100 CHAR) NOT NULL,
    email VARCHAR2(200 CHAR),
    phone VARCHAR2(30 CHAR),
    -- Add the foreign key when C's ATTACHMENT DDL is merged.
    resume_file_id NUMBER,
    stage VARCHAR2(12) DEFAULT 'APPLIED' NOT NULL CHECK (stage IN ('APPLIED','INTERVIEW','OFFER','HIRED','REJECTED')),
    applied_at DATE DEFAULT SYSDATE NOT NULL,
    hired_emp_id NUMBER UNIQUE REFERENCES EMPLOYEE(emp_id),
    CONSTRAINT CK_APPLICANT_HIRED CHECK (
        (stage = 'HIRED' AND hired_emp_id IS NOT NULL) OR (stage <> 'HIRED' AND hired_emp_id IS NULL)
    )
);

CREATE TABLE INTERVIEW (
    itv_id NUMBER PRIMARY KEY,
    app_id NUMBER NOT NULL REFERENCES APPLICANT(app_id),
    interviewer_id NUMBER NOT NULL REFERENCES EMPLOYEE(emp_id),
    scheduled_at DATE NOT NULL,
    score NUMBER CHECK (score BETWEEN 0 AND 100 AND score = TRUNC(score)),
    feedback VARCHAR2(1000 CHAR)
);

CREATE SEQUENCE SEQ_NOTICE;
CREATE SEQUENCE SEQ_EVAL_PERIOD;
CREATE SEQUENCE SEQ_EVAL_ITEM;
CREATE SEQUENCE SEQ_EVALUATION;
CREATE SEQUENCE SEQ_JOB_POSTING;
CREATE SEQUENCE SEQ_APPLICANT;
CREATE SEQUENCE SEQ_INTERVIEW;

CREATE INDEX IDX_NOTICE_PUBLIC ON NOTICE (deleted_at, is_pinned, created_at);
CREATE INDEX IDX_EVAL_ITEM_PERIOD ON EVAL_ITEM (period_id);
CREATE INDEX IDX_EVAL_TARGET ON EVALUATION (target_id, period_id);
CREATE INDEX IDX_APPLICANT_POSTING ON APPLICANT (posting_id, stage);
CREATE INDEX IDX_INTERVIEW_APP ON INTERVIEW (app_id);
CREATE INDEX IDX_INTERVIEW_ASSIGNEE ON INTERVIEW (interviewer_id, scheduled_at);

COMMIT;
