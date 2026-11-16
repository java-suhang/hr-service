# hr-service
사내 인사관리 웹 서비스 (Java Servlet + Oracle)

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
