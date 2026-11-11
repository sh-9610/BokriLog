---
title: '시험 PostgreSQL 컨테이너의 수명을 Spring 컨텍스트에서 떼기 — 컨텍스트 하나의 종료가 JVM의 공유 DB를 멈추는 연쇄 실패'
type: 'bugfix'
created: '2026-11-11'
status: 'done'
baseline_commit: 'df5a9c60ff5e9142ab8c27ea480e86197a8467ae'
review_loop_iteration: 1
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 시험 JVM의 공유 PostgreSQL 컨테이너(`PostgresTestcontainersConfig`의 static `CONTAINER`)가 `@Bean @ServiceConnection`으로 노출되어 있다. 그래서 그 bean을 가진 Spring 컨텍스트 하나가 닫히면 JVM 전체의 공유 컨테이너가 멈춘다.
- 2026-11-11 로컬 develop 전체 build(Docker 재시작 직후)에서 확인한 순서다.
  1. `AuthDeletionPathTest`의 컨텍스트가 Windows 루프백 bind 오류(`Bad address: listen`)로 로드에 실패하고 닫혔다.
  2. 그 JVM의 컨테이너가 `kill → die(137) → destroy` 됐다(OOM 이벤트 없음, ryuk 로그에 연결 끊김 없음).
  3. 이후 같은 JVM의 시험이 `Connection refused`로 연쇄 실패했다.
- 2026-11-10 로컬 build의 215건 실패도 같은 모양이다.
- Spring Boot 4.1.1의 `TestcontainersLifecycleBeanPostProcessor`는 `Startable` bean이 파기될 때 `close()`를 부른다. 종료 메서드를 비워도(`destroyMethod = ""`) 이 post-processor가 닫으므로, bean 정의만 고쳐서는 막히지 않는다(바이트코드로 확인).
- 같은 경로는 실패한 로드 말고도 정상적인 컨텍스트 종료(컨텍스트 캐시 축출 등)에서도 열린다. 그 뒤 공유 컨테이너가 다시 뜨면 새 포트를 받으므로, 캐시에 남은 다른 컨텍스트는 죽은 포트를 계속 쓸 수 있다(추정). 이는 "전체 빌드에서만" 나는 계열의 다른 경로이며, 159의 증상(409 `RESULT_TOO_RECENT`·작업 `FAILED`)을 직접 설명하지는 않는다(2026-11-11 사용자 재협상).

**Approach:**
- 공유 컨테이너를 Spring bean으로 노출하지 않는다. 컨텍스트는 컨테이너가 아닌 `JdbcConnectionDetails` bean 하나로 그 컨테이너의 접속 정보만 받는다(2026-11-11 사용자 재협상 — 처음의 `DynamicPropertyRegistrar`는 비밀번호를 `spring.datasource.password` 설정값으로 들여 `ConfiguredSecrets` WARN을 내고 `application.yml` 주석과 어긋났다). `JdbcConnectionDetails`는 `Startable`이 아니라 Boot가 닫지 않는다. 컨테이너 수명은 JVM(static)과 ryuk이 갖는다.
- bean을 주입받던 시험은 `PostgresTestcontainersConfig.shared()`를 쓴다.
- 컨텍스트를 닫아도 공유 컨테이너가 같은 id로 살아 있음을 결정적 시험으로 고정한다.

## Boundaries & Constraints

**Always:**
- **불변식 유지.** JVM 하나에 컨테이너 하나, `postgres:18.6-alpine`, 시작 직후 `DatabaseClockSkew.verify`, 자격정보 리터럴 없음, `withReuse` 미사용, 고정 포트·`System.setProperty` 미사용(`spec-ci-test-forks.md`).
- **접속 정보.** `JdbcConnectionDetails` bean은 컨테이너가 준 값(JDBC URL·사용자·비밀번호, 드라이버)을 돌려준다. 기존 `@ServiceConnection`이 맞추던 datasource 연결과 같은 결과여야 하고, `spring.datasource.*` 설정값을 들이지 않는다. Flyway 전략 bean(`testMigrationStrategy`)은 그대로다.
- **주입 제거.** 운영 코드는 바꾸지 않는다. `@Autowired PostgreSQLContainer`를 쓰는 시험 다섯(`PlanCompletionTest`·`PlanEndTest`·`PlanVerificationTest`·`RestrictionMigrationTest`·`DeletionBackupMigrationTest`)은 `shared()`로 바꾸고 단정 의미는 그대로 둔다.
- **결정적 시험.** 시험이 공유 설정을 import한 컨텍스트를 하나 띄우고 닫은 뒤, `shared()`가 같은 컨테이너 id로 `isRunning()`인지와 그 컨테이너에 SQL 한 문장이 성공하는지를 단정한다. 같은 시험이 옛 설정(bean 노출)에서는 실패함을 한 번 확인하고 Spec Change Log에 적는다.
- **문서.**
  - `CLAUDE.md` `## 스토리가 고정한 계약`의 시험 fork 항목("JVM마다 PostgreSQL 컨테이너 하나")에 한 구절을 더한다 — 컨테이너 수명은 Spring 컨텍스트가 갖지 않는다.
  - `DEVELOPMENT.md` `### 간헐 실패`에 한 항목을 더한다 — Windows 로컬의 `Bad address: listen`은 이 PC에서 관측된 소켓 오류이고, 이제 실패가 그 컨텍스트 구성에 머문다.
  - `deferred-work-resolutions.md` 159 절에 이 경로를 같은 계열의 다른 경로로 적는다(상태 불변).
  - `CLAUDE.md` 진행 표에 한 줄을 더한다.

**Ask First:**
- 운영 classpath·`build.gradle.kts`·의존을 바꿔야 할 때.
- `JdbcConnectionDetails` bean으로 접속이 맞춰지지 않아 다른 방식이 필요할 때.

**Never:**
- Windows bind 오류 자체를 고치거나 우회하는 코드, 재시도·시한 상향.
- 컨테이너 재사용(`reuse`)·JVM 안 병렬.
- 159·177의 시험 이전(다음 lease 상실 fix 몫).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 컨텍스트 정상 종료 | 공유 설정을 import한 컨텍스트를 닫음 | 공유 컨테이너는 같은 id로 살아 있고 SQL이 성공한다 | N/A |
| 컨텍스트 로드 실패 | 로드 중 예외로 컨텍스트가 닫힘 | 공유 컨테이너는 살아 있다. 실패는 그 컨텍스트 구성을 쓰는 시험 클래스에 머물고(Spring 실패 임계), 다른 구성의 시험은 `Connection refused` 연쇄 없이 돈다(2026-11-11 사용자 재협상) | 실패 원인은 그대로 보고된다 |
| 여러 컨텍스트 | 같은 JVM의 서로 다른 컨텍스트 | 모두 같은 컨테이너·같은 접속 정보를 쓴다 | N/A |

</frozen-after-approval>

## Code Map

- `backend/src/test/java/com/bokrilog/PostgresTestcontainersConfig.java`
  - `:42-46` — `@Bean @ServiceConnection postgresContainer()`. 고칠 자리다.
  - `:52-64` — `shared()`·`started()`. 그대로 쓴다.
  - `:70` `verifyClock`, `:83-87` `testMigrationStrategy`.
- Spring Boot 4.1.1 `TestcontainersLifecycleBeanPostProcessor`
  - `requiresDestruction`은 `Startable`이면 참이다.
  - `postProcessBeforeDestruction`은 `isDestroyedByFramework`(destroy 이름이 비어 있지 않음)와 `isReusedContainer` 둘 다 아니면 `close()`를 부른다.
  - 그래서 `destroyMethod=""`는 효과가 없다(javap로 확인).
- `@Autowired PostgreSQLContainer` 사용처:
  - `PlanCompletionTest.java:794`
  - `PlanEndTest.java:990-991`
  - `PlanVerificationTest.java:1147`
  - `RestrictionMigrationTest.java:26-27`
  - `DeletionBackupMigrationTest.java:24-25`
- `@Import(PostgresTestcontainersConfig.class)`: `AuthFlowTestBase.java:74`, `BackupRecordTest`, `DeployAuditTest`, `identity/DeletedIdentityKeylessTest`, `identity/IdentityKeyedTestBase` 등. import 쪽은 바꾸지 않는다.
- `shared()` 사용처: `BackupRecordTest`·`DeployAuditTest`·`DeployAuditPendingMigrationTest`·`BackupEventRepositoryDbTest`·`WorkerSingletonTest`. 그대로 둔다.
- `DatabaseClockSkew.java`·`DatabaseClockSkewTest.java` — 바꾸지 않는다.
- `spring-boot-testcontainers` 시험 의존은 남긴다 — 프로브의 `ServiceConnectionAutoConfiguration` import(옛 조건 재현)와 Boot의 Testcontainers lifecycle 초기화기(`TestcontainersLifecycleApplicationContextInitializer`, 옛 설정에서 컨테이너를 닫던 그 경로)가 그 의존에 있다.
- `CLAUDE.md` "시험·CI"의 fork 항목, `DEVELOPMENT.md` `### 간헐 실패`, `deferred-work-resolutions.md` `### 159.`
- 회고 액션: `epic-5-retro-item-51`(done)은 지나간다. 159의 주인은 그대로 다음 lease 상실 fix다. 그 밖에 걸린 열린 회고 액션은 없다.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/test/java/com/bokrilog/PostgresTestcontainersConfig.java` -- 컨테이너 bean을 `JdbcConnectionDetails` bean으로 바꾸고(처음 구현은 `DynamicPropertyRegistrar`였고 재협상으로 바꿨다), 수명을 컨텍스트에서 뗀 이유를 한국어 주석으로 적는다 -- 연쇄 실패 차단
- [x] 시험 다섯(위 Code Map) -- `@Autowired` 컨테이너를 `PostgresTestcontainersConfig.shared()`로 바꾼다 -- 주입 대상 제거
- [x] `backend/src/test/java/com/bokrilog/` 새 시험 -- 컨텍스트 종료 뒤 공유 컨테이너가 같은 id로 살아 있고 SQL이 성공함을 단정한다 -- 회귀 고정
- [x] `CLAUDE.md`·`DEVELOPMENT.md`·`deferred-work-resolutions.md` -- 계약 한 구절, 간헐 실패 항목, 159 원인 후보, 진행 표 -- 문서

**Acceptance Criteria:**
- Given 새 시험, when 옛 설정(bean 노출)으로 돌리면, then 실패한다. when 새 설정으로 돌리면, then 통과한다.
- Given 로컬 전체 `backend/gradlew --project-dir backend build`, when 한 시험 클래스의 컨텍스트가 로드에 실패해도, then 같은 JVM의 다른 시험은 `Connection refused`로 연쇄 실패하지 않는다. 전체 green은 PR CI `ci-required`가 판정한다.

## Spec Change Log

- 2026-11-11 구현: `PostgresTestcontainersConfig`의 `@Bean @ServiceConnection postgresContainer()`를 `DynamicPropertyRegistrar` bean `sharedPostgresProperties()`로 바꿨다(registrar 안에서 `started()` 한 번 → `spring.datasource.url`·`username`·`password`에 컨테이너 getter supplier). `JdbcConnectionDetails`는 이제 Boot의 속성 기반 bean이 준다(`WorkerSingleton` 주입 경로 포함). 시험 다섯은 `private final … postgres = PostgresTestcontainersConfig.shared()`로 바꿨다.
- 2026-11-11 새 시험 `SharedPostgresLifecycleTest`: TestContext 프레임워크(`TestContextManager`)로 `@SpringBootTest(webEnvironment = NONE)` 프로브 컨텍스트(공유 설정 import, `DataSourceAutoConfiguration`·`ServiceConnectionAutoConfiguration`만)를 띄워 `JdbcConnectionDetails` URL과 실제 연결을 확인하고, `markApplicationContextDirty(EXHAUSTIVE)`로 닫은 뒤 붙든 인스턴스의 `isRunning()`·같은 id, 이어서 `shared()`의 같은 id·SQL을 단정한다. 프로브 구성은 평범한 `@Configuration`이다 — `@SpringBootConfiguration`이면 같은 패키지 시험의 부트 구성 탐색이 둘을 찾아 `PlanEndTest` 등이 initializationError로 깨졌고, `@TestConfiguration`이면 Boot가 `BokriLogApplication`을 함께 올려(풀 1개에서 Flyway 연결 대기) 실패했다. 접속 단정은 처음 `spring.datasource.url` 환경값을 봤으나 옛 설정에서 그 자리에서 먼저 실패해 lifecycle 단정에 닿지 않았으므로, 두 설정 모두에서 뜻을 갖는 `JdbcConnectionDetails` URL로 바꿨다.
- 2026-11-11 옛 설정 확인: `PostgresTestcontainersConfig`만 기준(`df5a9c6`)으로 되돌려 `--tests com.bokrilog.SharedPostgresLifecycleTest`를 돌리면 `SharedPostgresLifecycleTest.java:74`(컨텍스트를 닫은 뒤 `container.isRunning()`)에서 `Expecting value to be true but was false`로 실패했다. 새 설정에서는 통과한다.
- 2026-11-11 검증: 새 시험과 시험 다섯(`PlanEndTest`·`RestrictionMigrationTest`·`DeletionBackupMigrationTest`·`PlanCompletionTest`·`PlanVerificationTest`)을 함께 돌려 통과했다. 로컬 Windows 전체 `build`(9분 48초, 1,774건)는 288건이 실패했다. 시험 보고서 전체에 `Connection refused`는 0건이다. 실패 원인은 모두 `Bad address`다. `listen`이 셋(`ActionGrantTest`·`BybitConnectionTransactionTest`·`OperatorRecoveryTest`의 컨텍스트 로드)이고 `connect`가 하나(`FirstSnapshotTest` 1건)다. 나머지는 같은 컨텍스트 구성(`AuthFlowTestBase` 하위)의 뒤 클래스가 Spring 컨텍스트 실패 임계(`failure threshold (1) exceeded`)로 함께 실패한 것이다. 연쇄 `Connection refused`가 없으므로 AC 2의 로컬 쪽은 충족한다. 다만 I/O 표의 "그 시험 클래스만 실패"는 실제로는 "그 컨텍스트 구성을 쓰는 클래스만"이다. 이 차이는 Spring의 실패 임계가 만드는 것이고 Never(재시도·우회 금지) 범위라 바꾸지 않았다. `DEVELOPMENT.md` 항목에 그 사실을 적었다. 전체 green은 PR CI `ci-required`가 판정한다.
- 2026-11-11 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap) → **사용자 재협상(intent).** edge-case-hunter가 지적했다 — `DynamicPropertyRegistrar`는 비밀번호를 `spring.datasource.password` 설정값으로 들여 컨텍스트마다 `ConfiguredSecrets` WARN을 내고, `application.yml:9-10`의 "시험은 `JdbcConnectionDetails`로 접속을 넣는다" 주석을 틀린 말로 만든다. 사용자가 컨테이너가 아닌 `JdbcConnectionDetails` bean 방식을 골랐다. 그래서 frozen Approach·Always(접속 정보)·Ask First의 한 구절씩을 그 방식으로 바꿨다. `review_loop_iteration`을 1로 올린다.
  - KEEP: 시험 다섯의 `shared()` 전환, `SharedPostgresLifecycleTest`의 프로브 구조(평범한 `@Configuration`, `TestContextManager`, `JdbcConnectionDetails` URL 단정, 붙든 인스턴스의 `isRunning()`·id 단정)와 그 구조에 이른 경위.
  - 피할 상태: 시험 컨텍스트의 Environment에 datasource 비밀번호가 들어가는 것.
  - I/O 표의 "그 시험 클래스만 실패한다"는 실제로는 "그 컨텍스트 구성을 쓰는 클래스까지"(Spring 실패 임계)다. frozen 표는 바꾸지 않았고 사용자 확인 안건으로 남긴다.
- 2026-11-11 재협상 반영: `DynamicPropertyRegistrar`를 `JdbcConnectionDetails` bean `sharedPostgresConnectionDetails()` 하나로 바꿨다(`started()`가 준 컨테이너의 `getJdbcUrl`·`getUsername`·`getPassword`·`getDriverClassName`, `spring.datasource.*` 설정값 없음). 함께 바꾼 것은 다섯이다.
  - `started()`는 id가 있는데 돌지 않는 컨테이너를 다시 띄우지 않고 "공유 시험 PostgreSQL이 멈췄습니다(컨테이너 id …)"로 `IllegalStateException`을 던진다.
  - 새 시험에 둘을 더했다. 로드 실패 시나리오는 datasource를 받은 뒤 예외를 던지는 bean을 가진 프로브다. 그 프로브 구성은 stereotype 없는 lite 구성이다 — `DeployAuditPendingMigrationTest`가 시험 프레임워크 밖에서 `BokriLogApplication`을 띄워 `TestTypeExcludeFilter` 없이 스캔하기 때문이다. 다른 하나는 `src/test/java` 소스 스캔으로, `@ServiceConnection`과 컨테이너 `@Autowired`를 막는다(패턴 표본을 든 시험 파일 자신은 뺀다).
  - 정상 종료 시험의 `markApplicationContextDirty`는 `try/finally`로 옮겼다.
  - `DatabaseClockSkew` Javadoc 한 줄을 고쳤다(주석만).
  - 새 방식으로 시험 셋(정상 종료·로드 실패·소스 스캔)이 통과했다. 옛 설정(기준 `df5a9c6`의 그 파일만 되돌림)에서는 셋 다 실패했다. 두 lifecycle 시험은 `SharedPostgresLifecycleTest.java:165`(붙든 인스턴스 `isRunning()`)에서, 소스 스캔은 `:157`(`@ServiceConnection`)에서다(그 실행 당시 줄 번호, 뒤에 주석 한 줄이 늘어 지금은 `:166`·`:158`).
  - 대상 시험 실행 중 `PlanEndTest`의 컨텍스트 로드가 한 번 `Bad address: listen`으로 실패했다. 이 Windows 소켓 문제는 `deferred-work.md` 183으로 append했다.

- 2026-11-11 2차 리뷰 patch(사용자가 Problem의 159 서술·I/O 표 로드 실패 행·Always 문서 두 줄을 재협상):
  - `started()`의 Docker 조회(`isRunning()`) 분기를 없앴다. 처음 시작한 컨테이너 id를 static으로 기억하고, 그 뒤 지금 id가 다르거나 비면 "이 JVM에서 닫혔습니다"로 실패한다. 판정은 package-private `requireSameContainer(처음 id, 지금 id)`다. 바깥에서 죽인 컨테이너(id 그대로)는 잡지 않는다는 한계를 Javadoc·`DEVELOPMENT.md`에 적었다.
  - `SharedPostgresLifecycleTest`가 넷으로 늘었다.
    - 그 판정을 Docker 없이 단정한다(처음·같은 id 통과, 빈·다른 id 실패).
    - 두 프로브 컨텍스트에 `Startable` bean이 0개이고 `spring.datasource.password`가 비었음을 단정한다(실패 프로브는 던지기 직전에 기록한다).
    - 소스 스캔은 주석·문자열을 지운 코드에 네 규칙을 걸고, 규칙마다 거부·통과 표본을 둔다: `@ServiceConnection`(한 줄·한정 이름·import·`service.connection.*`), `@Container`·`@Testcontainers`·`@ImportTestcontainers`, 컨테이너를 돌려주는 `@Bean`, `@Autowired`·`@Inject`·`@Resource` 주입.
  - 문서: `DEVELOPMENT.md`의 `Bad address`는 "이 PC에서 관측된 소켓 오류(원인 미확정)"로 고쳤다. 159 절의 새 포트 서술은 추정으로 밝혔고, 183 잔여에서 이미 넣은 로컬 절차는 뺐다. Code Map에 `spring-boot-testcontainers` 의존을 남기는 이유를 적었다.
- 2026-11-11 2차 리뷰(전체 재실행, blind-hunter·edge-case-hunter·verification-gap) 분류.
  - **사용자 재협상:** Problem의 159 서술을 "같은 계열의 다른 경로"로 낮추고, I/O 표의 로드 실패 행을 실제 동작(그 컨텍스트 구성에 머문다)으로 고쳤다. Always 문서 두 줄도 맞췄다.
  - **patch(반영):** 위 항목대로 멈춤 감지를 id 기억 방식으로 바꿨다. 판정을 Docker 없는 시험으로 고정했고, 프로브 런타임 단정과 소스 스캔 확대를 더했으며, 문서를 정합했다.
  - **defer 0건.**
  - **reject:**
    - 진행 표 상태(PR 번호는 PR을 연 뒤 채운다)
    - 시험 다섯의 `static` 필드화(`started()`가 이제 Docker를 부르지 않아 비용이 없다)
    - CI 증거 칸(PR CI 결과는 이 Change Log에 적는다)
    - Code Map 줄 번호(기준 시점 값)
    - `## 현재 진행 지점` 서술 문단(표 한 줄로 충분, 다음 작업 불변)
    - 외부에서 죽은 컨테이너의 감지(한계로 문서화)
  - 검증: 대상 여섯 클래스 87건 통과(PowerShell, 이번 실행엔 `Bad address` 없음).

## Verification

**Commands:**
- `backend/gradlew --project-dir backend test --tests "com.bokrilog.SharedPostgresLifecycleTest" --tests "com.bokrilog.PlanCompletionTest" --tests "com.bokrilog.PlanEndTest" --tests "com.bokrilog.PlanVerificationTest" --tests "com.bokrilog.RestrictionMigrationTest" --tests "com.bokrilog.DeletionBackupMigrationTest"` -- expected: 통과
- `backend/gradlew --project-dir backend build`(PowerShell) -- expected: 연쇄 `Connection refused` 없음. 남는 실패는 `Bad address: listen` 클래스뿐이어야 한다

## Suggested Review Order

**컨테이너 수명을 컨텍스트에서 떼기**

- 컨테이너 대신 `JdbcConnectionDetails` bean만 낸다 — Boot가 닫을 `Startable`이 없다
  [`PostgresTestcontainersConfig.java:61`](../../backend/src/test/java/com/bokrilog/PostgresTestcontainersConfig.java#L61)

- 처음 id를 기억해 JVM 안의 닫힘을 원인 문장으로 멈춘다, Docker 조회 없음
  [`PostgresTestcontainersConfig.java:104`](../../backend/src/test/java/com/bokrilog/PostgresTestcontainersConfig.java#L104)

- 판정을 컨테이너 없이 부를 수 있게 뺀 자리
  [`PostgresTestcontainersConfig.java:122`](../../backend/src/test/java/com/bokrilog/PostgresTestcontainersConfig.java#L122)

**회귀 고정**

- 정상 종료·로드 실패 프로브, 판정 단위 시험, 소스 스캔
  [`SharedPostgresLifecycleTest.java:135`](../../backend/src/test/java/com/bokrilog/SharedPostgresLifecycleTest.java#L135)

**문서·원장**

- 컨텍스트 실패가 그 구성에 머문다는 항목과 멈춤 감지의 한계
  [`DEVELOPMENT.md:181`](../../DEVELOPMENT.md#L181)

- 시험 fork 계약의 불변식 한 구절
  [`CLAUDE.md:305`](../../CLAUDE.md#L305)

- 159는 같은 계열의 다른 경로, 새 defer 183
  [`deferred-work-resolutions.md:1503`](deferred-work-resolutions.md#L1503)
