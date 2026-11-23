-- =====================================================================
-- 초기 데이터 (schema.sql 다음에 실행)
--
-- 로그인 계정 (처음 로그인한 뒤 비밀번호를 바꾼다)
--   관리자 : admin  / admin1234
--   직원   : yuchan / user1234   (그 외 직원도 user1234)
--
-- 결재선 확인용: 김유찬의 상사는 박준호 → 김유찬 휴가는 박준호 → 인사(이수진) 순서로 결재된다.
-- =====================================================================

INSERT INTO DEPARTMENT (dept_id, name) VALUES (SEQ_DEPARTMENT.NEXTVAL, '개발팀');
INSERT INTO DEPARTMENT (dept_id, name) VALUES (SEQ_DEPARTMENT.NEXTVAL, '디자인팀');
INSERT INTO DEPARTMENT (dept_id, name) VALUES (SEQ_DEPARTMENT.NEXTVAL, '영업팀');
INSERT INTO DEPARTMENT (dept_id, name) VALUES (SEQ_DEPARTMENT.NEXTVAL, '인사팀');
INSERT INTO DEPARTMENT (dept_id, name) VALUES (SEQ_DEPARTMENT.NEXTVAL, '재무팀');

INSERT INTO JOB_POSITION (pos_id, name, pos_level) VALUES (SEQ_JOB_POSITION.NEXTVAL, '사원', 1);
INSERT INTO JOB_POSITION (pos_id, name, pos_level) VALUES (SEQ_JOB_POSITION.NEXTVAL, '대리', 2);
INSERT INTO JOB_POSITION (pos_id, name, pos_level) VALUES (SEQ_JOB_POSITION.NEXTVAL, '과장', 3);
INSERT INTO JOB_POSITION (pos_id, name, pos_level) VALUES (SEQ_JOB_POSITION.NEXTVAL, '차장', 4);
INSERT INTO JOB_POSITION (pos_id, name, pos_level) VALUES (SEQ_JOB_POSITION.NEXTVAL, '부장', 5);

-- 관리자 (인사팀)
INSERT INTO EMPLOYEE (emp_id, emp_no, login_id, password_hash, name, email, phone, dept_id, pos_id, manager_id, role, hire_date, status)
VALUES (SEQ_EMPLOYEE.NEXTVAL, 'E2205', 'admin',
        'pbkdf2_sha256$65536$UgsKZn1LK8hCft9Ew6dF9Q==$qJTioxOtYCAQ0+OuIOPZGrj9KzsdihY9psNBLhwn5us=',
        '이수진', 'sujin@hr.local', '010-2205-8812',
        (SELECT dept_id FROM DEPARTMENT WHERE name = '인사팀'), (SELECT pos_id FROM JOB_POSITION WHERE name = '대리'),
        NULL, 'ADMIN', DATE '2022-01-10', 'ACTIVE');

-- 개발팀장
INSERT INTO EMPLOYEE (emp_id, emp_no, login_id, password_hash, name, email, phone, dept_id, pos_id, manager_id, role, hire_date, status)
VALUES (SEQ_EMPLOYEE.NEXTVAL, 'E2112', 'parkjh',
        'pbkdf2_sha256$65536$2pnFuyIyVBQ6FGQEhX7uIw==$bNaAaNxCE5GlGGJdycrPtnxjVSPPJY8CYcvxjVTu5Fk=',
        '박준호', 'junho@hr.local', '010-2112-5521',
        (SELECT dept_id FROM DEPARTMENT WHERE name = '개발팀'), (SELECT pos_id FROM JOB_POSITION WHERE name = '과장'),
        NULL, 'USER', DATE '2021-07-01', 'ACTIVE');

INSERT INTO EMPLOYEE (emp_id, emp_no, login_id, password_hash, name, email, phone, dept_id, pos_id, manager_id, role, hire_date, status)
VALUES (SEQ_EMPLOYEE.NEXTVAL, 'E2401', 'yuchan',
        'pbkdf2_sha256$65536$2pnFuyIyVBQ6FGQEhX7uIw==$bNaAaNxCE5GlGGJdycrPtnxjVSPPJY8CYcvxjVTu5Fk=',
        '김유찬', 'yuchan@hr.local', '010-2401-1234',
        (SELECT dept_id FROM DEPARTMENT WHERE name = '개발팀'), (SELECT pos_id FROM JOB_POSITION WHERE name = '사원'),
        (SELECT emp_id FROM EMPLOYEE WHERE login_id = 'parkjh'), 'USER', DATE '2024-03-04', 'ACTIVE');

INSERT INTO EMPLOYEE (emp_id, emp_no, login_id, password_hash, name, email, phone, dept_id, pos_id, manager_id, role, hire_date, status)
VALUES (SEQ_EMPLOYEE.NEXTVAL, 'E2318', 'hanjw',
        'pbkdf2_sha256$65536$2pnFuyIyVBQ6FGQEhX7uIw==$bNaAaNxCE5GlGGJdycrPtnxjVSPPJY8CYcvxjVTu5Fk=',
        '한지원', 'jiwon@hr.local', '010-2318-3390',
        (SELECT dept_id FROM DEPARTMENT WHERE name = '디자인팀'), (SELECT pos_id FROM JOB_POSITION WHERE name = '사원'),
        NULL, 'USER', DATE '2023-09-18', 'ACTIVE');

INSERT INTO EMPLOYEE (emp_id, emp_no, login_id, password_hash, name, email, phone, dept_id, pos_id, manager_id, role, hire_date, status)
VALUES (SEQ_EMPLOYEE.NEXTVAL, 'E1903', 'jungms',
        'pbkdf2_sha256$65536$2pnFuyIyVBQ6FGQEhX7uIw==$bNaAaNxCE5GlGGJdycrPtnxjVSPPJY8CYcvxjVTu5Fk=',
        '정민수', 'minsu@hr.local', '010-1903-7741',
        (SELECT dept_id FROM DEPARTMENT WHERE name = '영업팀'), (SELECT pos_id FROM JOB_POSITION WHERE name = '과장'),
        NULL, 'USER', DATE '2019-03-02', 'ACTIVE');

INSERT INTO EMPLOYEE (emp_id, emp_no, login_id, password_hash, name, email, phone, dept_id, pos_id, manager_id, role, hire_date, status)
VALUES (SEQ_EMPLOYEE.NEXTVAL, 'E2010', 'choiyr',
        'pbkdf2_sha256$65536$2pnFuyIyVBQ6FGQEhX7uIw==$bNaAaNxCE5GlGGJdycrPtnxjVSPPJY8CYcvxjVTu5Fk=',
        '최예린', 'yerin@hr.local', '010-2010-2268',
        (SELECT dept_id FROM DEPARTMENT WHERE name = '재무팀'), (SELECT pos_id FROM JOB_POSITION WHERE name = '대리'),
        NULL, 'ADMIN', DATE '2020-10-05', 'ACTIVE');

-- 올해 연차 (재직자 15일). LEAVE_BALANCE는 B 구역 테이블
INSERT INTO LEAVE_BALANCE (emp_id, year, total_days, used_days)
SELECT emp_id, EXTRACT(YEAR FROM SYSDATE), 15, 0 FROM EMPLOYEE WHERE status <> 'RESIGNED';

COMMIT;
