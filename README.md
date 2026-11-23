# hr-service
사내 인사관리 웹 서비스 (Java Servlet + Oracle)

## 처음 실행하기

1. Oracle에 계정을 만들고 `db/schema.sql`(A → B → C → D 구역 순서) → `db/seed.sql` 순서로 실행합니다.
2. `src/main/java/db.properties.example`을 참고해 `db.properties`를 만들고 Tomcat의 `CATALINA_BASE/lib/`에 둡니다. 영수증·이력서는 `upload.dir` 폴더(비우면 `~/hr-uploads`)에 저장합니다.
3. `mvn clean verify`로 `target/hr-service.war`를 만들어 Tomcat 10.1에 배포하고 `/hr-service/`로 접속합니다 (첫 화면은 로그인).

| 계정 | 아이디 / 비밀번호 | 비고 |
| --- | --- | --- |
| 관리자 (인사팀) | `admin` / `admin1234` | 로그인하면 관리자 대시보드로 이동 |
| 직원 | `yuchan` / `user1234` | 상사는 `parkjh` (휴가 결재 1단계) |
| 관리자 (재무팀) | `choiyr` / `user1234` | 두 번째 관리자. 관리자 본인의 신청은 다른 관리자가 결재 |
| 그 밖의 직원 | `parkjh`, `hanjw`, `jungms` / `user1234` | |

## A · 공통 · 인사

| 요구사항 | 화면 · API |
| --- | --- |
| REQ-01 로그인 | `login.html`, `POST /api/auth/login`, `/logout`, `GET /api/auth/me`. 같은 아이디로 10분 안에 5번 틀리면 5분 잠금 |
| REQ-02 권한 | `AuthFilter`: `/api/**`·`/user/**`·`/admin/**` 로그인 필요, `/admin/**`·`/api/admin/**`는 ADMIN만 (디코딩된 경로 기준) |
| REQ-03 비밀번호 | 본인 변경 `POST /api/auth/password`, 관리자 초기화 `POST /api/admin/employees/{id}/password`. PBKDF2 해시, 8자 이상 영문+숫자 |
| REQ-04·05 직원 | `admin/employees.html`, `/api/admin/employees` 검색·페이지·등록(+올해 연차)·수정·퇴사 |
| REQ-06 부서·직급 | `admin/departments.html`, `/api/admin/departments`, `/api/admin/positions` |
| REQ-07 내 정보·조직도 | `user/me.html`, `GET /api/me`, `GET /api/org` (연락처 가운데 가림) |
| REQ-21 감사 로그 | `admin/audit.html`, `GET /api/admin/audit-logs`. 다른 도메인은 `AuditService.log(conn, actorId, action, table, id, before, after)` |
| REQ-22 증명서 | `user/certificate.html` (재직증명서, 브라우저 인쇄로 PDF 저장) |

- 공통 모듈(`BaseServlet`, `DBUtil`, `JsonUtil`, `ApiException`, `LoginUser`, `AuthFilter`, `api.js`, `layout.js`, `common.css`)은 B가 만든 형식을 그대로 이어받아 A가 관리합니다. DB 제약 조건 위반(중복·참조·CHECK)은 500 대신 409·400으로 응답합니다.
- D의 채용 전환용 `EmployeeCreator`는 `AppContextListener`가 등록합니다. `EmployeeService.create(conn, employee, initialPassword, annualDays)`를 호출해 직원과 올해 연차를 같은 트랜잭션에서 만듭니다.
- 세션 쿠키는 `HttpOnly`, `SameSite=Lax`이고 로그인할 때 세션을 새로 발급합니다.

## C · 결재 · 경비 · 알림 · 첨부

| 요구사항 | 화면 · API |
| --- | --- |
| REQ-13·17 결재선·처리 | 상사 → 인사 관리자 결재선을 자동으로 만든다. `POST /api/approvals/{id}/approve`, `/reject`(의견 필수), `/cancel`(기안자) |
| REQ-18 결재함 | `user/approvals.html`, `GET /api/approvals?box=mine\|todo\|done`, `GET /api/approvals/{id}`, `GET /api/approvals/counts` |
| REQ-17 결재 관리 | `admin/approvals.html`(기본: 내 차례), `GET /api/admin/approvals?status=&type=&keyword=` |
| REQ-17 결재선 설정 | `admin/approval-lines.html`, `GET /api/admin/approval-lines`. 1단계(상사)는 A의 `PUT /api/admin/employees/{id}/manager`로 바꾼다 |
| REQ-19 후처리 | 최종 승인·반려 때 같은 트랜잭션에서 `LeaveService.onApproved/onRejected`(연차 차감·근태 표시), `ExpenseService.onApproved/onRejected`(지급 대기) |
| REQ-20 알림 | 화면 오른쪽 위 종, `GET /api/notifications`, `/unread-count`, `POST /api/notifications/{id}/read`, `/read-all`. D 공지 알림은 `NoticeNotifier` 어댑터로 연결 |
| REQ-23·24 경비 | `user/expenses.html`(`POST /api/expenses` multipart, 영수증 필수), `admin/expenses.html`(지급 대기 → `POST /api/admin/expenses/{id}/pay`) |
| REQ-31 첨부 | `GET /api/files/{id}`(영수증: 본인·결재자·관리자, 이력서: 관리자), 이력서 업로드 `POST /api/admin/files/applicants/{appId}` |

결재 규칙

- 결재선은 기안자의 상사 → 인사 관리자입니다. 상사가 없거나 재직 중이 아니면 인사 관리자 1단계만, 상사가 관리자면 상사 1단계로 끝납니다. 결재할 다른 관리자가 없으면 신청이 409로 막힙니다.
- 인사 관리자 단계는 재직 중인 관리자 누구나 처리하고, 처리한 사람이 결재자로 남습니다. 상사가 신청 뒤에 휴직·퇴사하면 그 단계도 관리자가 대신 처리할 수 있습니다.
- 기안자 본인은 어느 단계도 처리할 수 없고, 반려에는 의견이 필요합니다. 한 단계라도 반려하면 문서는 바로 끝납니다. 이미 올라간 문서의 결재선은 상사를 바꿔도 그대로입니다.
- 승인·반려는 감사 로그(`APPROVAL_DOC`), 지급은 `EXPENSE` 변경으로 남습니다. 결재 요청·단계 결과·최종 결과·지급은 알림으로 갑니다.

경비 · 첨부

- 금액은 1원~1,000만 원, 사용일은 오늘부터 1년 전까지, 영수증(JPG·PNG·PDF, 5MB 이하)은 필수입니다. 형식은 확장자가 아니라 파일 앞부분으로 확인합니다.
- 파일은 UUID 이름으로 저장하고 원래 이름은 DB에만 둡니다. 저장 폴더는 `-Dhr.upload.dir` → 환경 변수 `HR_UPLOAD_DIR` → `db.properties`의 `upload.dir` → `~/hr-uploads` 순서로 정합니다. DB 저장이 실패하면 방금 쓴 파일을 지웁니다.
- 권한이 없는 문서·파일은 존재 여부를 알리지 않도록 404로 응답합니다.

## 테스트 (A · C)

`mvn test`는 테스트 클래스마다 JVM을 따로 띄웁니다 (`DBUtil`이 `db.properties`를 한 번만 읽기 때문). `HttpTestServer`가 `db/schema.sql` 전체와 `db/seed.sql`을 H2 Oracle 호환 모드에 그대로 실행하고 내장 Tomcat에 서블릿을 올립니다.

- `TeamHttpTest`(A): 로그인·잠금·권한·인코딩 우회, 직원·부서·직급, 상사 변경, 감사 로그, D 채용 전환 어댑터
- `ApprovalHttpTest`(C): B 휴가 신청부터 결재선·승인·반려·취소·연차 차감, 경비·영수증·지급, 첨부 권한·검사, 알림 읽음, 이력서 업로드

## D 도메인 실행

- Java 21과 Maven에서 `mvn clean verify`를 실행합니다. Tomcat 10.1에 `target/hr-service.war`를 배포합니다.
- Oracle에 A의 `DEPARTMENT`, `JOB_POSITION`, `EMPLOYEE`를 만든 뒤 공용 `db/schema.sql`의 B·D 구역을 적용합니다.
- `src/main/java/db.properties.example`에 따라 접속 정보를 작성하고 Tomcat의 `CATALINA_BASE/lib/db.properties`에 둡니다. Maven WAR는 Oracle JDBC 드라이버를 포함합니다.
- D 화면은 공용 `AuthFilter`와 세션의 `LoginUser`를 사용합니다. 로그인 후 직원은 `/user/home.html`, 관리자는 `/admin/dashboard.html`에서 시작합니다. 경로에는 WAR의 컨텍스트 경로를 붙입니다.

## 팀 연동

- A의 `EmployeeService.create(conn, employee, annualDays)`를 호출하는 `com.hr.d.integration.EmployeeCreator`를 같은 이름의 `ServletContext` 속성으로 등록합니다. 어댑터가 임시 비밀번호를 A의 정책에 따라 처리하고, 계정과 올해 연차를 생성한 뒤 직원 ID를 반환합니다. 지원자 상태 변경까지 D 서비스가 커밋합니다. 등록 전에는 직원 전환 API가 503을 반환합니다.
- C의 `NotificationService.send(conn, empId, type, message, link)`를 호출하는 `com.hr.d.integration.NoticeNotifier`를 같은 이름의 `ServletContext` 속성으로 등록합니다. 공지와 알림을 함께 커밋합니다. 등록 전에는 공지만 저장합니다. 두 어댑터는 받은 연결을 사용하고 연결 종료와 커밋은 호출자에게 맡깁니다.
- 대시보드는 B의 `AttendanceService.today()`를 기준으로 근태와 연차를 조회합니다. C의 `APPROVAL_DOC`, `NOTIFICATION`이 없으면 관련 지표에 연동 상태를 표시합니다. 이력서 업로드와 `APPLICANT.resume_file_id`의 외래 키는 C의 `ATTACHMENT` 연동 시 연결합니다.

기본 테스트는 내장 Tomcat 10.1과 H2 Oracle 호환 모드에서 실행합니다. 공용 DDL의 B·D 구역과 A·C 테스트용 테이블을 사용합니다. 실제 Oracle 검증에는 빈 테스트 전용 스키마와 `HR_D_TEST_ORACLE_URL`, `HR_D_TEST_ORACLE_USER`, `HR_D_TEST_ORACLE_PASSWORD` 환경 변수를 설정한 뒤 `mvn test`를 사용합니다.
