---
title: 'Story 1.3 — 계정 소유권과 삭제 상태에 따라 접근을 판정하기'
type: 'feature'
created: '2026-09-07'
status: 'done'
review_loop_iteration: 0
baseline_commit: '9e9ca3b23cd5713f6cb7e298f12cd64e8d1a447a'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/EPIC-IMPLEMENTATION-READINESS.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 백엔드에는 상태 엔드포인트 하나뿐이고 사용자·계정 개념도 저장소도 없다. Epic 2~5의 모든 모듈이 소비할 "이 요청 주체가 이 계정에 접근해도 되는가"와 "이 계정은 삭제 진행 중인가"의 권위 있는 답이 존재하지 않아, 이후 인증 스토리가 각자 판정을 새로 만들 여지가 열려 있다.

**Approach:** `identity`를 첫 Spring Modulith 모듈로 세우고 PostgreSQL 18에 최소 계정 생명주기 스키마를 마이그레이션으로 만든다. 소유권·삭제 상태 판정과 삭제 경로를 모듈 공개 application API로만 노출하고, HTTP 표면은 늘리지 않는다. 실제 PostgreSQL을 쓰는 Testcontainers 통합 시험으로 두 사용자 소유권, 삭제 전환 경합, 멱등 삭제, 모듈 경계, HTTP 미노출을 검증한다.

## Boundaries & Constraints

**Always:**
- `identity`가 계정 생명주기·삭제 상태의 유일한 권위다. 다른 패키지는 공개 application API로만 접근하고 `identity` 내부 패키지·테이블에 직접 닿지 않는다. Modulith 경계 검증이 CI에서 이를 강제한다.
- 접근 거부는 하나의 결과다. 타 사용자 대상, 존재하지 않는 대상, 삭제된 대상이 호출자에게 서로 구분되면 안 된다.
- 계정 상태는 `ACTIVE → DELETION_IN_PROGRESS → DELETED` 단조 전진뿐이다. 반복 요청·실패·재시도로 이전 상태로 돌아가지 않는다.
- 사용자·매핑을 만들거나 바꾸는 트랜잭션은 계정 상태 행 잠금을 잡고 커밋 직전에 상태를 재검사한다.
- identity payload와 인증 매핑이 삭제된 뒤에도 신규 계정·세션 생성을 막는 최소 생명주기 guard 행은 payload와 분리해 남는다. 부분 실패를 삭제 완료로 보고하지 않는다.
- 이메일·표시명은 스키마에 두지 않는다. 내부 식별자는 UUID, 시각은 `timestamptz`(UTC)다.
- 스키마 변경은 expand 우선의 별도 migration 역할이 실행한다. API 기동은 schema 검증만 한다(AD-12).
- 자격정보 모양 리터럴을 `application.yml`·compose·시험 fixture에 넣지 않는다. 로그·지표·오류 응답에 `user_id`를 포함한 사용자 식별자를 남기지 않는다(AD-14).
- 스택은 `ARCHITECTURE-SPINE.md` Stack 표를 따른다. PostgreSQL 18.x, Spring Modulith 2.1.x. Story 1.2가 만든 동일 출처 계약과 Story 1.1 회귀는 시험 본문 수정 없이 계속 통과한다.
- `/api/status` 응답은 `{"status":"UP"}` 그대로다. 데이터베이스 상태·버전·연결 정보를 더하지 않는다.

**Ask First:**
- 고정 버전(PostgreSQL 18.6, Spring Modulith 2.1.1, Flyway·Testcontainers의 BOM 관리 버전)이 해결되지 않을 때. 조용히 낮추지 말고 보고한다.
- `-Werror`가 새 의존의 코드 경로에서 경고를 낼 때. 경고 억제로 통과시키지 않는다.
- `backend-quality` job의 wall-clock이 CI-PLAN §4·§10 예산(백엔드 도입 후 8분)을 넘을 때. 필수 검사를 빼서 맞추지 않는다.
- `verify-same-origin.sh`의 로그 누출 검사가 새 postgres 서비스 로그와 충돌해 검사 범위를 좁혀야 할 때.
- gitleaks가 새 compose·설정 파일에서 무언가를 탐지할 때. 규칙을 완화하지 말고 리터럴을 없앤다.

**Never:** 실제 OIDC·소셜 로그인·제공자 SDK·세션 저장소(Spring Session JDBC). identity의 어떤 기능도 HTTP로 노출하는 것 — 컨트롤러·엔드포인트·Actuator. 이메일·표시명·제공자 토큰 컬럼. 공개 계정 삭제 API·화면, 다른 모듈 데이터 삭제, 백업·tombstone·법정 보존 정책(Epic 5). JPA·Hibernate. production compose 프로필·image digest 고정·자원 제한(Story 3.8). 프런트엔드 애플리케이션 코드 변경.

## I/O & Edge-Case Matrix

판정 API 입력은 `(callerUserId, targetUserId, kind)`이고 출력은 `ALLOWED` 또는 `DENIED` 둘뿐이다.

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| 자기 계정 접근 | caller == target, `ACTIVE` | 모든 kind에 `ALLOWED` | N/A |
| 타 사용자 접근 | caller != target, 둘 다 `ACTIVE` | 모든 kind에 `DENIED` | 대상 존재 여부를 드러내는 정보 없음 |
| 미존재 대상 | target 행 없음 | `DENIED` — 타 사용자 사례와 동일한 값 | 예외를 던지지 않음 |
| 삭제 진행 중 | caller == target, `DELETION_IN_PROGRESS` | `READ`는 `ALLOWED`, `SENSITIVE`·`WRITE`는 `DENIED` | N/A |
| 삭제 완료 | caller == target, `DELETED` | 모든 kind에 `DENIED` | payload 부재를 오류로 보고하지 않음 |
| 세션 생성 판정 | `decideSessionCreation(userId)`, 상태 != `ACTIVE` 또는 행 없음 | `DENIED` | N/A |
| 삭제 전환 반복 | 이미 `DELETION_IN_PROGRESS`인 계정에 재요청 | 상태 유지, 성공 반환(멱등) | 활성으로 되돌리지 않음 |
| 쓰기와 삭제 전환 경합 | 쓰기 트랜잭션 진행 중 삭제 전환 커밋 | 한쪽만 성공. 쓰기가 지면 롤백되고 `DENIED` | 커밋 직전 재검사로 판정 |
| 삭제 재호출 | 이미 payload가 없는 계정 | 성공(멱등), guard 행은 그대로 | 없는 행 삭제를 실패로 보지 않음 |
| 삭제 중 저장 실패 | payload 삭제 트랜잭션 실패 | 실패 보고, 상태는 `DELETION_IN_PROGRESS` 유지 | 완료로 보고하지 않음 |
| 삭제된 계정에 매핑 재연결 | `(issuer, subject)`가 `DELETED` 계정에 연결됨 | 거부 | 어느 계정인지 밝히지 않음 |
| 매핑 중복 | 이미 다른 계정에 있는 `(issuer, subject)` 또는 같은 `(user_id, provider)` | 거부 | DB 유일 인덱스가 최종 방어 |

</frozen-after-approval>

## Code Map

- `backend/build.gradle.kts:9-11` -- 주석이 "Modulith·PostgreSQL은 Story 1.3 이후가 도입하며 그때 SPEC이 승인한다"고 위임한다. 이 SPEC이 그 승인이다. 문단을 갱신한다.
- `backend/build.gradle.kts:32-43` -- 의존 선언 관용. `implementation(platform(SpringBootPlugin.BOM_COORDINATES))`로 BOM을 직접 인용한다. Modulith·Testcontainers BOM도 같은 platform 패턴으로 더한다.
- `backend/build.gradle.kts:45-49` -- `-Xlint:all -Werror`. 새 의존의 경고가 여기서 빌드를 깬다. 실질 위험 지점.
- `backend/build.gradle.kts:51-57` -- 유일한 test 설정. 통합 시험은 별도 source set 없이 `test`에 둔다(아래 Design Notes).
- `backend/build.gradle.kts:63-73` -- `bokrilog-backend.jar` 이름 고정. compose·Dockerfile·CI artifact 3곳이 참조하므로 바꾸지 않는다.
- `backend/settings.gradle.kts:7-10` -- "Modulith와 identity 모듈은 Story 1.3의 범위" 주석. 모듈은 Gradle 서브프로젝트가 아니라 패키지이므로 `include`는 필요 없고 주석만 갱신한다.
- `backend/src/main/java/com/bokrilog/BokriLogApplication.java` -- Modulith 루트. `identity`는 이 클래스 패키지의 직속 하위 패키지여야 모듈로 인식된다.
- `backend/src/test/java/com/bokrilog/StatusApiTest.java` -- **DataSource가 생기는 즉시 깨진다.** `@SpringBootTest`가 컨테이너 없이 뜨지 못한다. 공유 Testcontainers 구성을 import하도록 고쳐야 하며, 시험 단정 본문은 그대로 둔다.
- `backend/src/main/resources/application.yml:6-7` -- "PostgreSQL과 Spring Session JDBC는 Story 1.3이 도입한다" 주석. datasource·flyway 설정을 여기에 넣되 Session JDBC는 넣지 않는다(1.4·1.5 범위).
- `backend/Dockerfile:26` -- `ENTRYPOINT ["java","-jar","/app/app.jar"]`. compose `command:`로 인자를 덧붙일 수 있어 migrate 역할이 같은 이미지를 재사용한다.
- `deploy/compose/compose.yaml:22-46` -- `backend` 서비스. postgres `depends_on`(healthy)과 datasource env를 더한다. `expose`만 쓰고 포트를 게시하지 않는 관용을 postgres에도 적용한다.
- `deploy/compose/compose.yaml:35-41` -- healthcheck 패턴 정본(`interval 3s / timeout 3s / retries 20`). postgres는 `pg_isready` 동형 healthcheck가 **필수**다. 없으면 아래 `--wait`가 무한 대기한다.
- `deploy/compose/verify-same-origin.sh:139-145` -- `up --detach --build --wait --wait-timeout 180`. 새 서비스가 자동으로 대기 대상에 들어간다. 마이그레이션은 이 앞에 별도 호출로 넣는다.
- `deploy/compose/verify-same-origin.sh:508-557` -- `compose logs`를 서비스 구분 없이 읽어 누출 조각 12개를 검사한다. postgres 기동 로그가 새로 검사 대상이 된다. 충돌 여부 확인이 필요하다.
- `deploy/compose/verify-same-origin.sh:453-503` -- `pause/unpause/stop backend`로 502·504를 만드는 절. DB 비가용은 별개 시나리오이며 이 스토리는 추가하지 않는다.
- `.github/workflows/ci.yml:267` -- `timeout-minutes: 10`과 "Testcontainers는 Story 1.3부터 들어온다" 주석. 예산을 올리고 주석을 갱신한다.
- `.github/workflows/ci.yml:292-295` -- `Gradle 빌드` 단계 한 줄. Testcontainers를 `build` 안에서 돌리면 단계 목록이 바뀌지 않아 아래 계약 시험 수정이 최소가 된다.
- `.github/workflows/ci.yml:369,392` -- `same-origin-integration`이 docker 설치 단계 없이 `docker compose`를 호출한다. 러너에 데몬이 있다는 증거이며 Testcontainers도 그대로 돈다. 새 action이 필요 없다.
- `.github/scripts/test-ci-scripts.sh:933-939` -- `backend-quality` 단계 5개 이름을 고정한다. 단계를 **더하면** 여기에도 등록한다(파일 주석 `:899-900`의 규약).
- `.github/scripts/test-ci-scripts.sh:825-827,835-836,852-853,963-965,997-999,1091-1126` -- job 목록·`runs-on` 5회·`ci-required` needs·docs gate 3회·action SHA 6개·artifact 개수와 이름 정규식. **새 job이나 새 artifact·action을 만들면 전부 깨진다.** 만들지 않는 것이 이 스토리의 선택이다.
- `.github/scripts/classify-changes.sh:113-122` -- `*/*` 규칙이 `backend/**`를 이미 제품 변경으로 분류한다. 수정 불필요.
- `.github/scripts/aggregate-results.sh:126-128` -- job 3개를 고정한다. 새 job을 만들지 않으므로 수정 불필요.
- `CLAUDE.md`, `DEVELOPMENT.md` -- "Modulith·PostgreSQL·Testcontainers는 아직 없다", "데이터베이스 없이 기동한다"가 도입 즉시 거짓이 된다. 함께 갱신한다.

## Tasks & Acceptance

**Execution:**
- [x] `backend/build.gradle.kts` -- Modulith 2.1.1 BOM을 platform으로 더하고(Testcontainers 버전은 Boot BOM이 이미 관리한다) `spring-boot-starter-jdbc`, `flyway-core`, `flyway-database-postgresql`, `postgresql` 드라이버(runtimeOnly), 시험용 `spring-modulith-starter-test`·`spring-boot-testcontainers`·`testcontainers-postgresql`·`testcontainers-junit-jupiter`를 선언 -- 이 SPEC이 승인한 의존만 들어간다. JPA는 넣지 않는다. Modulith는 시험 classpath에만 둔다(운영 jar에 ArchUnit을 싣지 않는다)
- [x] `backend/src/main/resources/db/migration/V1__identity_account.sql` -- `identity_account_lifecycle`(guard), `identity_account`(payload), `identity_social_link`(매핑) 세 테이블. `(issuer, subject)`와 `(user_id, provider)` 유일 인덱스, 상태 CHECK, `timestamptz` -- AC-1의 저장 계약과 매핑 유일성의 최종 방어
- [x] `backend/src/main/java/com/bokrilog/identity/` 공개 API -- `AccountLifecycleApi`(계정 생성, 소셜 매핑 연결, 세션 생성 판정)·`AccountAccessApi`(소유권·상태 판정)·`AccountDeletionApi`(삭제 전환, payload 삭제)와 `AccessDecision`·`AccountAccessKind`·`DeletionOutcome` -- 다른 모듈이 보는 유일한 표면. 거부 사유를 값으로 노출하지 않는다
- [x] `backend/src/main/java/com/bokrilog/identity/internal/` -- JdbcClient 기반 저장소와 판정·전환·삭제 구현. 상태 행 `SELECT … FOR UPDATE` 잠금과 커밋 직전 재검사, 단조 전진 UPDATE 술어 -- AC-2·AC-3의 경합 계약
- [x] `backend/src/main/java/com/bokrilog/SchemaValidationConfig.java` -- 기본 프로필은 `FlywayMigrationStrategy`를 `Flyway::validate`로 대체하고 `migrate` 프로필만 `Flyway::migrate` -- AD-12의 "startup은 schema 검증만". 기동 정책은 앱 전역 관심사라 모듈이 아니라 루트 패키지가 소유하고, 프로필별 선택은 `SchemaValidationConfigTest`가 단정한다
- [x] `backend/src/main/resources/application.yml` -- datasource(URL·사용자명을 환경변수에서, 기본값·비밀번호 리터럴 없음)와 flyway 설정, `migrate` 프로필의 `spring.main.web-application-type: none` -- 자격정보 리터럴 금지
- [x] `backend/src/test/java/com/bokrilog/PostgresTestcontainersConfig.java` -- `postgres:18.6-alpine` 단일 컨테이너를 `@ServiceConnection`으로 공유하고 시험에서는 migrate 전략을 쓴다 -- 시험 전체가 컨테이너 하나만 띄운다
- [x] `backend/src/test/java/com/bokrilog/StatusApiTest.java` -- 위 구성을 import만 추가 -- DataSource 도입으로 깨지는 회귀 복구. 단정 본문은 손대지 않는다
- [x] `backend/src/test/java/com/bokrilog/identity/AccountAccessDecisionTest.java` -- I/O 매트릭스 전 행을 실제 PostgreSQL로 시험. 두 사용자 소유권 거부와 다른 계정 데이터 보존 포함 -- AC-2·AC-3
- [x] `backend/src/test/java/com/bokrilog/identity/AccountDeletionTest.java` -- 삭제 경로의 멱등 재호출, guard 행 보존, 다른 계정 보존, 저장 실패 주입 시 완료 미보고 -- AC-4
- [x] `backend/src/test/java/com/bokrilog/identity/AccountConcurrencyTest.java` -- 삭제 전환과 쓰기·세션 생성 판정의 동시 실행, 중복 전환, 롤백 후 상태 불변 -- AC-3의 경합·재시도 계약
- [x] `backend/src/test/java/com/bokrilog/ModuleBoundaryTest.java` -- `ApplicationModules.of(BokriLogApplication.class).verify()`와 "`identity` 밖에서 `identity.internal` 참조 없음", "`identity`에 웹 애노테이션 없음" 규칙 -- AC-5의 경계·미노출 검사
- [x] `deploy/compose/compose.yaml` -- `postgres:18.6-alpine` 서비스(포트 미게시, `pg_isready` healthcheck, dev·CI 전용 `POSTGRES_HOST_AUTH_METHOD: trust`)와 `profiles: ['migrate']`인 일회성 migrate 서비스, backend의 `depends_on`·datasource env -- 동일 출처 스택이 DB와 함께 뜬다
- [x] `deploy/compose/verify-same-origin.sh` -- `up --wait` 앞에 마이그레이션 1회 실행을 넣고, 로그 누출 검사가 postgres 로그와 충돌하지 않는지 확인 -- 로컬·CI 동일 명령 유지
- [x] `.github/workflows/ci.yml` -- `backend-quality` timeout을 Testcontainers 포함 예산으로 올리고 `:267`·`:294` 주석을 현재 사실로 갱신 -- 새 job·새 artifact·새 action은 만들지 않는다
- [x] `.github/scripts/test-ci-scripts.sh` -- 위 변경으로 달라지는 단정만 갱신 -- 계약 시험이 스스로 실패하지 않게 한다
- [x] `CLAUDE.md`, `DEVELOPMENT.md` -- Modulith·PostgreSQL·Testcontainers 도입, Docker가 이제 백엔드 시험의 전제라는 점, 마이그레이션 실행 절차를 반영 -- 문서와 사실을 맞춘다

**Acceptance Criteria:**
- Given 어떤 사용자 데이터도 없는 스키마, when `AccountLifecycleApi`로 계정을 생성하면, then 서버가 만든 UUID `user_id`와 `ACTIVE` guard 행이 저장되고 이메일을 받지도 저장하지도 않는다
- Given Docker가 실행 중인 깨끗한 체크아웃, when `backend/gradlew --project-dir backend build`를 실행하면, then 실제 PostgreSQL 컨테이너로 통합 시험이 통과하고 `-Werror` 아래 경고 없이 jar가 생성된다
- Given `frontend/dist`와 백엔드 jar가 빌드된 상태, when `deploy/compose/verify-same-origin.sh`를 실행하면, then 마이그레이션이 1회 실행된 뒤 Story 1.2의 모든 단언과 Story 1.1 회귀가 통과하고 종료 코드가 0이다
- Given 실행 중인 스택, when 외부에서 `/api` 아래 어떤 경로로도 계정 생성·상태 변경·삭제를 시도하면, then 404 또는 405이며 identity 기능에 닿는 HTTP 경로가 존재하지 않는다
- Given 삭제 진행 상태로 전환된 계정, when 같은 계정에 대한 전환을 반복하거나 전환 트랜잭션이 실패한 뒤 재시도하면, then 상태는 결코 `ACTIVE`로 돌아가지 않는다
- Given identity payload와 인증 매핑이 삭제된 계정, when 생명주기 guard를 조회하면, then guard 행이 남아 신규 계정·세션 생성 판정을 계속 거부한다
- Given 변경된 워크플로, when `bash .github/scripts/test-ci-scripts.sh`를 실행하면, then 모든 계약 단언이 통과한다

## Spec Change Log

- 2026-09-07 리뷰 반영. Intent·Boundaries·I/O 표는 그대로이고 구현 세부만 바뀌었다.
  - 매핑 연결의 "커밋 직전 재검사"를 별도 SELECT가 아니라 INSERT의 술어로 옮기고 유일 인덱스 위반을 `ON CONFLICT DO NOTHING`으로 접었다. 거부가 `setRollbackOnly()`로 호출자 트랜잭션을 오염시키던 경로를 없앤다(`## Design Notes`의 "경합의 모양" 참조).
  - `provider`·`issuer`·`subject`의 공백·길이 검증을 애플리케이션에 넣고 같은 상한을 `V1__identity_account.sql`의 CHECK로 이중화했다. 상한이 없으면 btree 항목 크기 한계가 SQLSTATE 54000 예외로 나타나 위와 같은 오염을 만든다.
  - 저장된 상태 문자열을 `AccountState.valueOf` 대신 fail-closed 파서로 읽는다. 알 수 없는 값은 거부·해당 없음으로 접히며 판정 경로 밖으로 예외가 나가지 않는다.
  - `SchemaValidationConfig`를 `com.bokrilog.identity.internal`에서 루트 패키지 `com.bokrilog`로 옮기고 프로필별 전략 선택을 `SchemaValidationConfigTest`가 단정하게 했다. 통합 시험은 `@Primary` 전략이 이 bean을 가려 아무것도 검증하지 못했다.
  - Spring Modulith를 시험 classpath로 옮겼다. 운영 jar에서 ArchUnit(4.5MB)과 `spring-modulith-apt`가 빠진다.
  - `ModuleBoundaryTest`의 금지 패키지에 `org.springframework.boot.actuate..`·`org.springframework.boot.web..`을 더했다. "Actuator도 없다"가 문장이 아니라 검사가 된다.
  - `AccountConcurrencyTest`가 잠금 대기열로 겹침을 강제하고 두 갈래가 모두 관측됐음을 단정한다. `spring.datasource.hikari.maximum-pool-size`도 시험 구성에 명시했다.
  - `verify-same-origin.sh`가 전 서비스 로그를 다시 검사하고(postgres entrypoint 한 줄만 제외) 성공한 migrate 로그에도 같은 조각 검사를 적용한다. 조각 목록에 `BOKRILOG_DB_URL`·`BOKRILOG_DB_USERNAME`을 더했다.

## Design Notes

**JPA를 쓰지 않는다.** 이 모듈이 하는 일은 행 하나를 잠그고, 상태를 읽고, 술어가 붙은 UPDATE를 던지는 것뿐이다. `JdbcClient`면 SQL이 그대로 보이고, `-Werror` 아래에서 애노테이션 프로세서가 만드는 경고 위험이 없으며, 뒤이어 올 Spring Session JDBC와도 같은 계열이다. Hibernate의 dirty checking·flush 시점은 "커밋 직전 재검사"를 오히려 흐린다.

**거부는 하나의 값이다.** 사유를 enum으로 돌려주면 호출자가 존재 여부를 복원할 수 있다. 사유는 반환하지도, 로그에 남기지도 않는다(로그에 `user_id`를 남기지 않는 규칙과 한 쌍이다).

**경합의 모양.** 잠금 → 검사 → 술어가 붙은 쓰기를 한 트랜잭션 안에 둔다. 재검사는 별도 SELECT가 아니라 쓰기 문장의 술어다.

```java
@Transactional
public AccessDecision linkSocialIdentity(UUID userId, String provider, String issuer, String subject) {
    if (!isAcceptable(provider) || !isAcceptable(issuer) || !isAcceptable(subject)) return DENIED;
    if (repository.lockLifecycleForUpdate(userId) != ACTIVE) return DENIED;   // SELECT … FOR UPDATE
    // INSERT … SELECT … WHERE state = 'ACTIVE' … ON CONFLICT DO NOTHING
    return repository.insertSocialLinkIfActive(userId, provider, issuer, subject) == 1 ? ALLOWED : DENIED;
}
```

초안은 쓰기 뒤에 재검사 SELECT를 한 번 더 두고, 재검사나 유일 인덱스 위반에서 `setRollbackOnly()`로 되돌리는 모양이었다. 그 표시는 트랜잭션 전체에 걸리는 전역 값이라 호출자가 자기 `@Transactional` 안에서 이 메서드를 부르면(Story 1.4의 OIDC 콜백이 그 모양이다) 호출자의 커밋이 `UnexpectedRollbackException`으로 죽고 호출자의 작업까지 사라진다. 그래서 판정을 쓰기 문장 안으로 넣어 거부 경로에서 아무것도 쓰지 않게 했다. 잠금을 든 채로는 재검사가 다른 값을 볼 수 없으므로 별도 SELECT는 관측 가능한 분기가 아니라 방어적 이중화였고, 그 이중화를 유지하는 비용이 곧 위의 롤백이었다.

**단조 전진은 술어가 강제한다.** `UPDATE … SET state = 'DELETED' WHERE user_id = ? AND state = 'DELETION_IN_PROGRESS'`. 0행이면 전환이 일어나지 않은 것이고, 이미 목표 상태면 멱등 성공이다. DB 트리거로 한 번 더 막는 방법은 PL/pgSQL을 처음 들이는 비용이 있어 지금은 쓰지 않는다 — 유일한 writer가 이 모듈이라는 것을 Modulith가 강제하는 동안은 술어로 충분하다.

**세 테이블의 이유.** guard(`identity_account_lifecycle`)는 삭제 뒤에도 남아야 하고 payload(`identity_account`)와 매핑(`identity_social_link`)은 사라져야 한다. 한 테이블에 컬럼으로 섞으면 "payload를 지웠다"가 "행을 지웠다"와 달라져 삭제 완료 판정이 모호해진다.

**dev·CI compose에 비밀번호를 두지 않는다.** `POSTGRES_HOST_AUTH_METHOD: trust`를 쓰고 포트를 게시하지 않는다. 가짜 비밀번호 리터럴을 커밋하면 gitleaks 규칙을 완화하고 싶어지고, 그 완화가 남는다. production 자격정보는 Story 3.8이 소유한다.

**마이그레이션은 `up --wait` 밖에서 1회 실행한다.** compose의 `--wait`는 정상 종료하는 일회성 서비스를 잘 다루지 못한다. `--profile migrate run --rm migrate`로 명시 실행하는 편이 "운영 migration은 별도 단일 실행 역할"(AD-12)과도 같은 모양이다.

**CI에 job을 더하지 않는다.** `test-ci-scripts.sh`가 job 목록·`runs-on` 개수·artifact 개수·action SHA 집합을 전문으로 고정한다. Testcontainers를 기존 `Gradle 빌드` 단계 안에서 돌리면 그 계약 대부분이 그대로 통과하고, 이 스토리가 CI 구조 재설계를 겸하지 않는다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: Modulith 경계 시험과 Testcontainers 통합 시험 통과, 경고 0건, `bokrilog-backend.jar` 생성
- `npm --prefix frontend run build` -- expected: `frontend/dist/` 생성(compose 전제)
- `deploy/compose/verify-same-origin.sh` -- expected: 마이그레이션 1회 실행 후 모든 단언 통과, exit 0
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 갱신된 계약 통과
- `git status --short` -- expected: 빌드·시험 산출물이 미추적 파일로 남지 않음

**Manual checks:**
- `grep -rn "identity" deploy/nginx/bokrilog.conf` -- expected: 0건. identity에 닿는 프록시 경로가 생기지 않았음
- 실행 중인 스택의 `docker compose logs`에서 `user_id` 값과 자격정보 조각 검색 -- expected: 0건

## Suggested Review Order

**진입점 — 판정 규칙 한 곳**

- 상태별 판정의 전부. 거부는 어느 경로로 와도 같은 값 하나다
  [`AccountAccessService.java:43`](../../backend/src/main/java/com/bokrilog/identity/internal/AccountAccessService.java#L43)
- 삭제 진행 중에도 READ만 남기는 유일한 분기. ERD §9가 막는 것은 세션·자격·쓰기다
  [`AccountAccessService.java:46`](../../backend/src/main/java/com/bokrilog/identity/internal/AccountAccessService.java#L46)
- 판정 전 상태 문자열을 접는 지점. 알 수 없는 값이 500이 되지 않는다
  [`AccountState.java:38`](../../backend/src/main/java/com/bokrilog/identity/internal/AccountState.java#L38)

**경합 — 잠금과 단조 전진**

- 트랜잭션 끝까지 행을 붙드는 잠금. 같은 계정의 전환이 여기서 줄을 선다
  [`IdentityAccountRepository.java:37`](../../backend/src/main/java/com/bokrilog/identity/internal/IdentityAccountRepository.java#L37)
- 술어가 단조 전진을 강제한다. 0행은 실패가 아니라 "전이가 없었다"
  [`IdentityAccountRepository.java:121`](../../backend/src/main/java/com/bokrilog/identity/internal/IdentityAccountRepository.java#L121)
- 상태 검사를 쓰기 안으로 옮겨 거부 경로가 아무것도 쓰지 않는다. 롤백이 불필요해진다
  [`IdentityAccountRepository.java:94`](../../backend/src/main/java/com/bokrilog/identity/internal/IdentityAccountRepository.java#L94)
- 잠금을 든 동안 재검사가 관측 가능한 분기가 아닌 이유. 방어적 이중화임을 명시
  [`AccountLifecycleService.java:90`](../../backend/src/main/java/com/bokrilog/identity/internal/AccountLifecycleService.java#L90)
- 계정 생성에만 잠금이 없는 이유. 경합할 상대가 아직 없다
  [`AccountLifecycleService.java:49`](../../backend/src/main/java/com/bokrilog/identity/internal/AccountLifecycleService.java#L49)

**삭제 — guard와 payload의 분리**

- payload를 지우고 guard 행은 남긴다. 이 한 줄이 재생성 차단의 전부다
  [`AccountDeletionService.java:68`](../../backend/src/main/java/com/bokrilog/identity/internal/AccountDeletionService.java#L68)
- 0행을 실패로 보지 않는 멱등 경계
  [`AccountDeletionService.java:63`](../../backend/src/main/java/com/bokrilog/identity/internal/AccountDeletionService.java#L63)

**스키마 기동 정책 — AD-12**

- 기본 기동은 검증만. 적용 안 된 마이그레이션이 있으면 기동이 실패한다
  [`SchemaValidationConfig.java:34`](../../backend/src/main/java/com/bokrilog/SchemaValidationConfig.java#L34)
- 실제 적용은 이 프로필 하나뿐. 운영 migration이 별도 역할인 근거
  [`SchemaValidationConfig.java:39`](../../backend/src/main/java/com/bokrilog/SchemaValidationConfig.java#L39)

**스키마 — 세 테이블과 최종 방어선**

- guard 테이블. 삭제 뒤에도 남는 쪽이다
  [`V1__identity_account.sql:15`](../../backend/src/main/resources/db/migration/V1__identity_account.sql#L15)
- `(issuer, subject)` 전역 유일. 이메일이 식별키가 아닌 이유가 여기 있다
  [`V1__identity_account.sql:62`](../../backend/src/main/resources/db/migration/V1__identity_account.sql#L62)
- 길이 CHECK. 애플리케이션 검증을 우회한 행에 대한 최종 방어
  [`V1__identity_account.sql:53`](../../backend/src/main/resources/db/migration/V1__identity_account.sql#L53)

**모듈 경계 — 첫 Modulith 모듈**

- 밖에서 `internal`에 닿지 못한다. 다른 모듈이 저장소를 직접 보는 경로를 닫는다
  [`ModuleBoundaryTest.java:52`](../../backend/src/test/java/com/bokrilog/ModuleBoundaryTest.java#L52)
- HTTP 표면 금지에 Actuator까지 포함. "엔드포인트도 Actuator도 없다"를 검사로 만든다
  [`ModuleBoundaryTest.java:79`](../../backend/src/test/java/com/bokrilog/ModuleBoundaryTest.java#L79)

**동일 출처 스택 — DB가 들어와도 origin은 하나**

- 초기화 중 임시 서버를 healthy로 오인하지 않도록 실제 질의를 던진다
  [`compose.yaml:55`](../../deploy/compose/compose.yaml#L55)
- migrate는 `profiles`에 가려 기본 `up`에 들어오지 않는다. 별도 역할의 compose 표현
  [`compose.yaml:73`](../../deploy/compose/compose.yaml#L73)
- 비밀번호 항목이 없는 것이 누락이 아닌 이유. 포트 미게시와 한 쌍이다
  [`compose.yaml:107`](../../deploy/compose/compose.yaml#L107)

**검증 스크립트 — 로컬·CI 동일 명령**

- 마이그레이션이 스택 기동보다 먼저 1회. 순서가 계약이다
  [`verify-same-origin.sh:216`](../../deploy/compose/verify-same-origin.sh#L216)
- 스키마를 바꾸는 유일한 프로세스의 로그도 누출 검사를 받는다
  [`verify-same-origin.sh:226`](../../deploy/compose/verify-same-origin.sh#L226)
- identity가 HTTP로 새지 않았는지 확인하는 절
  [`verify-same-origin.sh:522`](../../deploy/compose/verify-same-origin.sh#L522)

**시험 — 지워지면 안 되는 것들**

- 전략 시험. 이게 없으면 AD-12 계약을 지워도 초록불이 유지된다
  [`SchemaValidationConfigTest.java:1`](../../backend/src/test/java/com/bokrilog/SchemaValidationConfigTest.java#L1)
- 실제로 줄을 세운 뒤 두 결과가 모두 관측됨을 단정한다
  [`AccountConcurrencyTest.java:1`](../../backend/src/test/java/com/bokrilog/identity/AccountConcurrencyTest.java#L1)
- 거부가 호출자 트랜잭션을 죽이지 않는다. Story 1.4가 의존할 계약
  [`AccountLinkDenialTest.java:1`](../../backend/src/test/java/com/bokrilog/identity/AccountLinkDenialTest.java#L1)
