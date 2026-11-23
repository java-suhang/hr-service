# hr-service
사내 인사관리 웹 서비스 (Java Servlet + Oracle)

## 처음 실행하기

1. Oracle에 계정을 만들고 `db/schema.sql`(A → B → C → D 구역 순서) → `db/seed.sql` 순서로 실행합니다.
2. `src/main/java/db.properties.example`을 참고해 `db.properties`를 만들고 Tomcat의 `CATALINA_BASE/lib/`에 둡니다.
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

### 테스트

`mvn test`는 테스트 클래스마다 JVM을 따로 띄웁니다 (`DBUtil`이 `db.properties`를 한 번만 읽기 때문). `TeamHttpTest`는 `db/schema.sql` 전체와 `db/seed.sql`을 H2 Oracle 호환 모드에 그대로 실행해 로그인·권한·직원·부서·감사 로그·D 연동을 HTTP로 검증합니다.

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
