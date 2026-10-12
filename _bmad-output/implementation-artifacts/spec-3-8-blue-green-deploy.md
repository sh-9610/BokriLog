---
title: 'Story 3.8 — ARM64 단일 서버 blue/green 배포와 rollback'
type: 'feature'
created: '2026-10-12'
status: 'done'
review_loop_iteration: 0
baseline_commit: 'd069f7da962ed022d6f6fd497ec115edda643ae0'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-7-observability-foundation.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 배포 절차가 없다. Worker는 API 프로세스 안의 `@Scheduled`로 돌아서 따로 drain할 수 없고, JVM이 종료되면 진행 중이던 증분 동기화가 `SYNC_INTERRUPTED` 실패로 끝난다. 슬롯 전환·rollback·사전 gate·배포 감사도 없다(`epics.md` Story 3.8 AC-1~5. 이미지 빌드·서명·게시 파이프라인, Alloy 연결, 훈련 증거, 비밀 공급 관리, nginx 권한 강화, 키 회전, TLS 종단은 `deferred-work.md`로 분리).

**Approach:**
- **역할 분리.** 같은 jar·이미지를 `BOKRILOG_ROLE=api|worker|all`로 나눠 실행한다. `all`은 개발·시험 기본값이고 production은 `all`을 거부한다.
- **Worker drain·단일성.** Worker에 drain과 단일 실행 잠금을 더한다.
- **production 구성.** production compose 한 벌과 bash 스크립트(사전 gate → drain → expand migration → 대기 슬롯 검증 → Nginx reload → Worker 하나 재개 → rollback)를 둔다.
- **배포 감사.** 각 단계를 기존 감사 원장에 SYSTEM 행으로 추가한다.
- **리허설.** 전 과정을 CI 리허설 job이 돈다. 실제 운영 대상 smoke 전까지는 **운영 배포 미검증**이다.

## Boundaries & Constraints

**Always:**
- **역할(AC-3).**
  - `api`는 dispatch·주기 측정을 하지 않는다.
  - `worker`는 같은 servlet 앱으로 뜬다. 다만 `server.address`가 `127.0.0.1`이고 Nginx upstream·포트 게시 대상이 아니다. 관리 포트 prometheus는 유지한다.
  - `all`은 지금 동작(한 프로세스에 둘 다)이다.
  - `BOKRILOG_ENV=production`에서 `all`이면 기동을 거부한다.
  - 메트릭 공통 tag에 `role`(`api|worker|all`)을 더한다(`TelemetryAllowlist`).
- **Drain.**
  - Worker 종료 신호를 받으면 새 lease claim을 멈춘다. heartbeat는 계속하고 in-flight가 비거나 `bokrilog.sync.worker.drain-timeout`(기본 120초)이 지날 때까지 기다린다.
  - 다 비면 종료 코드 0이고, timeout이면 0이 아닌 코드와 로그 `event=worker.drain.incomplete`를 낸다.
  - compose `stop_grace_period`가 drain-timeout보다 길다.
- **단일 Worker.** Worker는 전용 연결에서 PostgreSQL session advisory lock을 잡은 뒤에만 dispatch한다. 못 잡으면 기동 실패다.
- **배포 입력(AC-1).** `deploy/production/release.env`(`key=value`, jq 불필요)가 다음을 고정한다.
  - `image`(`<repo>@sha256:<64hex>`, tag 금지)
  - `platform=linux/arm64`
  - `migration_version`
  - `migration_kind=expand|contract`
  - `sbom`·`signature` 파일과 각 sha256
  - API·Worker·migrate·감사 역할은 모두 이 `image` 하나를 쓴다.
  - gate는 다음을 확인한다.
    - 형식
    - 실제 이미지 아키텍처
    - 파일 sha256
    - `cosign verify`(없으면 준비 실패)
    - migrate 뒤 `flyway_schema_history` 최대 버전 = `migration_version`
    - api·worker 서비스에 migrate 프로필이 없음
- **자원·비용 gate(AC-2).** 운영자가 채우는 `host-facts.env`가 다음을 선언하고, gate가 하나라도 어긋나면 거부한다.
  - home region, A1 2 OCPU/12GB, reserved IP
  - Always Free 표시, 예상 월 비용 0
  - 서비스 `mem_limit`이 API 768m·Worker 512m·PostgreSQL 3g·Nginx 128m이고 Alloy 256m을 예약으로 계상한다.
  - blue/green peak(API×2+나머지)이 선언 메모리 안이다.
- **순서(AC-3).**
  1. gate
  2. 감사 `REQUESTED`
  3. Worker drain
  4. 단일 migrate 역할
  5. 기존 슬롯(old API+new schema) smoke
  6. 대기 슬롯 기동
  7. readiness(`/api/status`)·smoke(미인증 `GET /api/auth/session`의 401 공통 오류 모양)·telemetry(관리 포트 출력에 `env`·`role` tag) 확인
  8. `nginx -t`와 reload
  9. 전환 뒤 smoke
  10. 새 Worker 하나 시작
  11. 이전 슬롯 정지
  - 활성 슬롯은 상태 파일 하나가 기록한다.
- **Nginx.** upstream 블록만 별도 include 파일로 뺀다(개발은 `backend:8080`, production은 스크립트가 쓰는 활성 슬롯). `bokrilog.conf` 본문·`add_header` 한 곳 규칙은 그대로다.
- **실패·rollback(AC-4).**
  - 전환 전 실패: 대기 슬롯을 지우고 기존 슬롯·이전 Worker digest로 Worker를 재개한다.
  - 전환 뒤 smoke 실패: upstream을 이전 슬롯으로 되돌리고 이전 digest Worker를 재개한다.
  - `migration_kind=contract`는 상태 파일의 rollback 창이 열려 있으면 거부한다. 창은 `deploy.sh close-window`로만 닫힌다.
- **감사(AC-5).**
  - 일회성 `deploy-audit` 프로필(web none, Flyway 검사 없음, INSERT만)이 `operations_audit_event`에 SYSTEM 행을 추가한다.
    - `command=deploy`, `scope=DEPLOY`, target=digest, reason=단계명(`GATE`·`DRAINED`·`MIGRATED`·`INACTIVE_VERIFIED`·`CUTOVER`·`VERIFIED`·`ROLLED_BACK`)
    - outcome은 기존 enum이다.
    - actor는 `BOKRILOG_DEPLOY_ACTOR`의 `deployer:` keyed digest이고, `operation_id`는 배포 하나에 하나다.
    - SQL은 `OperatorAuditRepository` 안에 둔다.
  - 배포는 제한 행을 쓰지 않는다. 배포 전후 활성 제한 수가 같아야 하고, 제한 중인 기능은 smoke 실패로 보지 않는다.
- **비밀.** production 값은 저장소 밖 env 파일(권한 600)에서만 온다. gate가 필수 이름이 비었는지 본다.
- **리허설.**
  - `rehearse-deploy.sh`(0/1/2)가 production compose를 개발 override(trust DB, 이미지 ID 참조, `BOKRILOG_ENV=ci`)로 돈다.
  - 시나리오는 정상 2회, 전환 전 실패, 전환 뒤 실패→rollback, contract 거부다.
  - 아키텍처·서명 검사만 건너뛰고 감사 details에 `rehearsal:true`를 남긴다.
  - 결함 주입 변수는 production에서 거부한다.
- **CI.** 새 job `deploy-rehearsal`(두 품질 job 산출물 소비)을 두고 `ci-required`·`aggregate-results.sh`·`test-ci-scripts.sh`를 함께 갱신한다. gate fixture 시험은 `deploy/production/test-preflight.sh`가 한다. bash 3.2, `| grep -q` 금지.
- **문서.** `DEVELOPMENT.md` 배포 절을 쓰고 blue/green이 VM·volume·DB·리전 장애를 이중화하지 않는다고 명시한다. `CLAUDE.md` 역할 계약 한 줄. 새 주석은 한국어다.

**Ask First:**
- 감사 enum·CHECK를 바꾸는 migration
- Worker를 non-web으로 바꾸는 것
- 개발 compose를 역할 분리로 바꾸는 것
- 무중단·HA를 문서에 약속하는 것

**Never:**
- startup migration
- tag·`latest` 참조
- 운영 서버에서 이미지 빌드
- 레지스트리 게시
- 배포가 제한을 해제하는 것
- Worker 둘 동시 dispatch
- contract와 expand를 한 배포에서 실행하는 것

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 정상 배포 | blue 활성, 새 release | green 활성, Worker 1(새 digest), 감사 단계 순서대로 `COMPLETED` | — |
| gate 거부 | tag 참조·amd64·sha 불일치·비용>0·peak 초과 | 아무 컨테이너도 바꾸지 않음, 감사 `FAILED GATE` | 종료 1 |
| drain timeout | in-flight가 timeout 초과 | 배포 중단, 기존 슬롯·Worker 재개 | `worker.drain.incomplete` |
| 전환 전 실패 | 대기 슬롯 readiness 실패 | upstream 불변, 대기 슬롯 제거, 이전 Worker 재개 | 감사 `FAILED` |
| 전환 뒤 실패 | 전환 뒤 smoke 실패 | 이전 슬롯 복귀, 이전 digest Worker, 감사 `ROLLED_BACK` | 종료 1 |
| 두 번째 Worker | Worker 둘 기동 | 뒤에 뜬 것이 잠금 실패로 종료 | — |
| contract | 창 열림 + `contract` | gate 거부 | 종료 1 |
| production `all` | `BOKRILOG_ENV=production`, role 없음 | 기동 거부 | — |
| 제한 중 배포 | GLOBAL SYNC 제한 활성 | 배포 진행, 제한 수 불변 | — |

</frozen-after-approval>

## Code Map

- **역할·스케줄링:** `backend/src/main/java/com/bokrilog/FirstSnapshotSchedulingConfig.java:8-11`(유일한 `@EnableScheduling`, servlet 조건), `sync/internal/FirstSnapshotWorker.java:15,43-133`(dispatch 여섯, heartbeat 5초 :56, in-flight 맵 다섯 :31-35, classification은 in-flight·heartbeat 없음), `sync/internal/SyncMetrics.java:26,146`·`operations/internal/SupportMetrics.java:26,75`(주기 측정, 스케줄링 켜진 역할에서만 돈다)
- **설정:** `application.yml:88`(graceful), `:93-99`(shutdown phase 5초 — worker 역할에서는 drain-timeout보다 길게 둬야 drain이 끊기지 않는다), `:245-247`(`bokrilog.sync.worker.dispatch-interval`), `:279-297`(관리 포트), `:337-348`(`migrate` 프로필 = web none). 시험 속성은 `@DynamicPropertySource`(`AuthFlowTestBase.java:73-113`, 하위 약 47개 시험이 한 servlet 컨텍스트에서 worker를 함께 돈다 → 기본값 `all` 유지)
- **lease:** `sync/internal/SyncLease.java:22`, claim 여섯 곳(`SyncJobRepository:61` 등), `PlanSyncRepository:258-279`(중단된 동기화가 `SYNC_INTERRUPTED`로 끝나는 이유)
- **non-web 역할 패턴:** `SchemaValidationConfig.java:32-43`, `OperationsConfigurationTest.java:25,52`(ApplicationContextRunner로 빈 유무 단정)
- **감사:** `V17__operations_audit.sql`(CHECK — `actor_kind` OPERATOR|SYSTEM, outcome 여섯, 길이 제한, migration 불필요), `operations/internal/OperatorAuditRepository.java:54-108`(`lockAppends`·`appendSystem`), `OperationalSignalService.java:98-170`(SYSTEM 행 쓰는 선례), `operations/internal/ActorDigest.java:78`(prefix — `deployer:` 추가), `OperationsConfiguration.java:22-26`(ActorDigest servlet 조건 — `deploy-audit`에도 필요), `OperatorAuditTest.java:39-60`(테이블 SQL은 저장소 한 곳)
- **제한 판독:** `operations/OperationalRestrictionApi.java:94`(`active()`)
- **Nginx:** `deploy/nginx/bokrilog.conf:87-93`(upstream 블록 — include로 분리할 유일한 곳), `:113-156`(`add_header` 한 곳), `:278-282`(502·503·504 → `UPSTREAM_UNAVAILABLE`)
- **compose·검증:** `deploy/compose/compose.yaml`(개발 — upstream include 마운트만 추가), `verify-same-origin.sh`(도우미·종료 코드 관용, `:2833-2868` 관리 포트 읽기, 키 실행 시 생성 `:256-276`)
- **CI:** `.github/workflows/ci.yml:344-480`(통합 job·`ci-required` needs), `aggregate-results.sh`(결과 변수), `test-ci-scripts.sh`(`assert_job_steps`·`count_job_lines` 관용)
- **설계 근거:** `PORTFOLIO-SOLUTION-DESIGN.md` §8 L174-216(자원 표·9단계 순서·배포 상태), `ARCHITECTURE-SPINE.md` AD-12·AD-15, `CI-PLAN.md` CI-D4 L148-154·§12 L345-380
- **이미지:** `backend/Dockerfile`(tag 베이스, 주석이 digest 고정을 3.8로 넘김)

## Tasks & Acceptance

**Execution:**
- [x] `backend/.../BokrilogRole.java`(루트)·`FirstSnapshotSchedulingConfig`·`FirstSnapshotWorker`·`application.yml` -- 역할 속성과 조건, production `all` 거부, `role` tag, worker 주소 127.0.0.1.
- [x] `sync/internal/WorkerDrain`·`WorkerSingleton`(가칭) -- 종료 신호 drain(claim 중지·heartbeat 유지·timeout·종료 코드), advisory lock 단일성.
- [x] `operations` 공개 API + `OperatorAuditRepository` + `DeployAuditRunner`(루트, `deploy-audit` 프로필) -- SYSTEM 배포 행 추가, `ActorDigest` 조건 확장.
- [x] `deploy/nginx/bokrilog.conf`·`deploy/nginx/upstream-backend.conf`·개발 compose 마운트 -- upstream include 분리.
- [x] `deploy/production/compose.yaml`·`release.env.example`·`host-facts.env.example`·`deploy.sh`·`preflight.sh`·`test-preflight.sh`·`rehearse-deploy.sh`·`compose.rehearsal.yaml` -- 절차·gate·리허설.
- [x] `ci.yml`·`aggregate-results.sh`·`test-ci-scripts.sh` -- `deploy-rehearsal` job과 집계.
- [x] 시험 -- `BokrilogRoleTest`(역할별 빈·production 거부), `WorkerDrainTest`(claim 중지·in-flight 대기·timeout 코드), `WorkerSingletonTest`, `DeployAuditTest`(행 모양·Flyway 검사 없음·민감값 없음), `TelemetryAllowlistTest`(`role` tag), `ModuleBoundaryTest` 통과, `test-preflight.sh`(gate 거부 각 경로).
- [x] `DEVELOPMENT.md`·`CLAUDE.md`·`deploy/compose/compose.yaml` 머리 주석 -- 배포 절차·HA 한계·운영 배포 미검증·역할 계약.

**Acceptance Criteria:**
- Given 리허설을 돌리면, when 모든 시나리오가 끝나면, then 각 시나리오의 활성 슬롯·Worker 수·감사 행 순서·제한 수가 표와 같고 종료 0이다.
- Given 백엔드 build·프런트엔드 전체 검증·`verify-same-origin.sh --keep-stack`·주입 origin e2e·`test-ci-scripts.sh`·`check-alert-rules.sh`를 돌리면, then 모두 통과한다.
- Given 운영 문서를 읽으면, then blue/green의 이중화 한계와 **운영 배포 미검증** 상태가 적혀 있고 무중단·SLA 약속은 없다.

## Spec Change Log

- 2026-10-12 구현에서 확정한 것(SPEC 본문 변경 없음): (1) smoke — 미인증 `GET /api/auth/session`은 실제로 200 `{"authenticated":false,"destination":"entry"}`라(Story 1.5 계약, `verify-same-origin.sh`가 고정) 401 공통 오류 모양은 미인증 `GET /api/connections/bybit`(401 `UNAUTHENTICATED`)로 본다. smoke는 `/api/status`·`/api/auth/session`·`/api/connections/bybit` 셋이다. (2) 빈 데이터베이스에 대한 첫 배포는 원장 표가 없어 `GATE`·`DRAINED` 행을 줄 세웠다가 첫 migration 직후(`MIGRATED`보다 앞) 같은 순서로 쓴다. (3) 전환 직후 smoke 실패는 `CUTOVER FAILED`, 새 Worker 기동 실패는 `VERIFIED FAILED`이고 둘 다 `ROLLED_BACK`으로 맺는다. gate 거부의 `GATE FAILED`는 거부된 이미지를 띄우지 않으려고 지금 활성 이미지의 감사 역할이 쓴다(첫 배포면 기록할 곳이 없다). (4) Worker drain은 `ContextClosedEvent`의 첫 listener에서 돌아 shutdown phase가 자르지 않고, 종료 코드는 진입점이 컨텍스트를 닫은 뒤 `Runtime.halt`로 낸다(0·75 drain-timeout·76 잠금 연결 상실). drain·단일 잠금은 `worker` 역할에만 있다(`all`은 지금 동작 그대로 — 시험 JVM의 여러 컨텍스트가 잠금을 다투지 않게). (5) contract는 창이 닫혀 있어도 migration을 정확히 하나만 더해야 한다(expand와 섞지 않는다는 Never의 집행), schema 역행은 rollback 창 안의 이전 이미지만 받는다. (6) 인프라 이미지(PostgreSQL·Nginx)와 Dockerfile 베이스도 `tag@sha256:<index digest>`로 고정했고 gate가 운영 compose의 미고정 이미지·`build`를 거부한다. (7) Windows Git Bash는 POSIX 권한을 표현하지 못해 리허설·gate 시험에서만 비밀 파일 권한 검사를 건너뛴다(운영 모드는 언제나 판정). (8) API 슬롯과 Worker에는 compose 프로필을 두지 않았다 — 절차가 언제나 서비스 이름으로 다루므로 이름만으로 조회·정지가 되게.
- 2026-10-12 Matrix Test Audit(step-03): I/O 표 아홉 행 중 drain timeout 행(배포 중단·기존 슬롯·Worker 재개)을 덮는 저장소 시험이 없어(구현 때는 임시 시뮬레이션뿐이었다) 리허설 시나리오 3b와 리허설 전용 결함 `drain-incomplete`(drain 종료 코드를 75로 본다 — JVM이 75를 내는 경로는 `WorkerDrainTest`)를 더했다. 나머지 행은 리허설 1·2·3·4·5·6, `test-preflight.sh`(tag·amd64·sha 불일치·비용·peak), `BokrilogRoleTest`·`WorkerSingletonTest`가 덮는다. 로컬(Windows, Docker 29.7.2) 실행 결과: 백엔드 `clean build` 1374건 통과(17분 4초 — 같은 시각 develop의 `FirstSnapshotTest` 단독이 316초여서 기계 부하로 본다), 리허설 72/72, `test-preflight.sh` 76/76, `test-ci-scripts.sh` 198/198, 프런트엔드 정적 검사·build·Vitest(부하 때 시간 초과 6건은 단독 재실행 154건 통과, 프런트엔드 변경 없음)·preview e2e 40건 통과. `verify-same-origin.sh`는 Bybit 연결 400에서 62건 실패하는데 develop(`d069f7d`)도 같은 62건으로 실패해 이 환경의 문제로 보고 별도 작업으로 넘겼다. 그래서 주입 origin e2e와 `check-alert-rules.sh`(Windows 미지원)는 CI가 첫 실행이다. SPEC 본문 변경 없음.
- 2026-10-12 리뷰 patch 루프(SPEC 본문 변경 없음): (정정) 위 줄의 "JVM이 75를 내는 경로는 `WorkerDrainTest`"는 과장이다 — `WorkerDrainTest`가 고정하는 것은 빈의 `exitCode()`까지이고, 실제 JVM이 신호를 받아 drain 뒤 그 코드로 끝나는 경로(`BokriLogApplication`의 `Runtime.halt`)는 시험·리허설 어느 쪽도 고정하지 않는다(defer). 주요 patch: API 역할에서 NaN으로 남던 주기 측정 gauge를 스케줄링 역할에만 두었다(`SyncQueueAgeMetrics`·`SupportPendingMetrics`, promtool에 role 분리 사례) — 모듈이 루트 `BokrilogRole.Schedules`를 참조하면 Modulith가 루트↔모듈 순환으로 거부해 같은 판정을 `@ConditionalOnExpression("'${bokrilog.role:all}' != 'api'")`로 둔다. `WorkerDrainTest`가 여섯 종류의 진행 중 작업을 모두 본다. `WorkerSingleton`은 풀 밖 전용 연결·`pg_locks` 확인·`pg_advisory_unlock`이고 세션을 `pg_terminate_backend`로 끊으면 76이 한 번 난다. `deploy.sh`: drain 코드 75/76/그 밖 구분, 새 Worker telemetry, migration 직후 schema 버전 저장, `close-window`의 잠금·감사(`WINDOW_CLOSED`, `DeployAuditApi.Step`에 추가 — DB 변경 없음), INT·HUP·TERM의 단계별 정리, 상태 파일과 실제 API 슬롯 대조(`STATE_MISSING`·`STATE_MISMATCH`), `up --no-recreate` PostgreSQL, upstream 쓰기 실패 처리, 값 없는 인자 준비 실패, upstream의 `zone`·`resolver 127.0.0.11`·`resolve`. gate: 리허설은 `BOKRILOG_ENV` ∈ {ci, development} + override, 운영은 env 빈 값·`production` + override 없음, 프로젝트 이름 모양, 서명 신원을 host 사실로 옮기고 SBOM을 `cosign verify-attestation --type spdxjson`으로 확인, 비밀 파일의 덮는 이름(`SPRING_PROFILES_INCLUDE`·`SPRING_APPLICATION_JSON`·`BOKRILOG_SYNC_WORKER_*`·`BOKRILOG_DEPLOY_ACTOR` 포함) 거부, 배포 수행자는 셸이 준다(`DEPLOY_ACTOR_INVALID`), 저장소 180자 상한, `env_file` 거부. 운영 compose는 `--env-file` 치환으로 서비스마다 필요한 이름만 받고, 망을 `edge`·`data`로 나누고, json-file 로그 회전을 둔다. `DeployAuditService`는 `GATE FAILED` 밖의 빈 target을 거절한다. `ConfiguredSecrets`의 DB 비밀번호 감지는 실제 설정(`BOKRILOG_DB_PASSWORD` → `spring.datasource.password`)을 지나는 시험으로 고정했다. `DeployAuditPendingMigrationTest`가 마지막 migration을 남긴 DB에서 감사 역할이 뜨고 행을 쓰는 것을 본다. 리허설: 두 번째 Worker를 떼어 띄워 120초 상한, 시나리오 7(창 닫기), 실패 때 진단 기록 보존과 CI 진단 artifact. 정적 산출물·Nginx 설정이 release·rollback 밖이라는 한계를 문서에 적었다.
- 2026-10-12 CI 수정(PR #89 첫 실행): `changes`의 gitleaks(`generic-api-key`)가 `preflight.sh:166`의 빈 값 대입 두 줄(뒷줄의 변수 이름을 값으로 읽었다)을 잡았다. 규칙은 그대로 두고 변수 이름을 `unknown_entry`·`duplicate_entry`로 바꿨다(`test-preflight.sh` 111/111). PR 커밋 범위가 스캔되므로 커밋을 고쳐 다시 올렸다. SPEC 본문 변경 없음.

## Design Notes

- **Worker를 servlet으로 두는 이유.** worker 경로가 쓰는 `ActorDigest`·`ConfiguredSecrets`·관리 포트가 모두 servlet에 묶여 있다. 그리고 servlet 조건이 붙은 클래스가 약 30개다. non-web으로 가면 조건을 전부 다시 짜야 하고 worker 계측(동기화 결말 카운터)이 긁히지 않는다. 대신 주소를 루프백에 묶고 upstream에서 빼서 "공개 HTTP 없음"을 구성으로 보장한다.
- **drain을 종료 신호에 거는 이유.** 별도 HTTP drain 경로를 만들면 공개 표면이 하나 는다. `docker compose stop`의 SIGTERM이 곧 drain 요청이고, 그 결과는 종료 코드 하나가 스크립트에 전한다.
- **이전 슬롯 정지 뒤의 rollback.** 전환 뒤 smoke는 이전 슬롯을 정지하기 전에 돈다. 그 뒤 rollback 창이 열린 동안의 되돌리기는 이전 `release.env`로 같은 절차를 다시 도는 것이다. 창 안에서는 expand만 허용되므로 이전 digest가 새 schema 위에서 뜬다.
- **`deploy-audit`가 Flyway를 검사하지 않는 이유.** 첫 감사(`REQUESTED`)는 migration 전에 새 이미지로 쓴다. 그때 validate는 미적용 migration 때문에 반드시 실패한다. 이 역할은 V17 표에 INSERT만 하므로 schema 차이와 무관하다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 통과, 경고 0.
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run build && npm --prefix frontend run test && npm --prefix frontend run test:e2e` -- expected: 통과.
- `bash .github/scripts/test-ci-scripts.sh && bash .github/scripts/check-alert-rules.sh && bash deploy/production/test-preflight.sh` -- expected: 통과.
- `deploy/compose/verify-same-origin.sh --keep-stack && PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 npm --prefix frontend run test:e2e:run` 뒤 스택 down, 이어 `deploy/production/rehearse-deploy.sh` -- expected: 모두 0.

## Suggested Review Order

**배포 절차의 골격**

- 진입점: drain→migrate→대기 슬롯 검증→reload→Worker 하나 순서의 본체
  [`deploy.sh:600`](../../deploy/production/deploy.sh#L600)

- 전환 전 실패는 대기 슬롯 제거·이전 Worker 재개, 감사 FAILED로 맺는다
  [`deploy.sh:399`](../../deploy/production/deploy.sh#L399)

- 전환 뒤 실패는 upstream 복귀·이전 digest Worker·ROLLED_BACK
  [`deploy.sh:416`](../../deploy/production/deploy.sh#L416)

- drain 종료 코드 0/75/76/그 밖을 감사 code로 가른다
  [`deploy.sh:614`](../../deploy/production/deploy.sh#L614)

- 중단 신호를 단계별로 정리하는 trap
  [`deploy.sh:161`](../../deploy/production/deploy.sh#L161)

- 상태 파일과 실제 슬롯이 어긋나면 서비스 중 슬롯을 다시 만들지 않고 거부
  [`deploy.sh:519`](../../deploy/production/deploy.sh#L519)

- 활성 슬롯 upstream 파일(zone·resolver·resolve)과 `nginx -t` 뒤 reload
  [`deploy.sh:349`](../../deploy/production/deploy.sh#L349)

- rollback 창을 닫는 유일한 경로 — 잠금과 감사 행
  [`deploy.sh:469`](../../deploy/production/deploy.sh#L469)

**역할 분리와 Worker drain**

- `api|worker|all` 역할, 운영에서 `all` 거부
  [`BokrilogRole.java:27`](../../backend/src/main/java/com/bokrilog/BokrilogRole.java#L27)
  [`BokrilogRoleConfig.java:23`](../../backend/src/main/java/com/bokrilog/BokrilogRoleConfig.java#L23)

- 종료 신호의 첫 listener에서 claim을 닫고 진행 중 작업을 기다린다
  [`WorkerDrain.java:87`](../../backend/src/main/java/com/bokrilog/sync/internal/WorkerDrain.java#L87)

- 여섯 dispatch가 지나는 claim 문과 진행 중 합계
  [`FirstSnapshotWorker.java:16`](../../backend/src/main/java/com/bokrilog/sync/internal/FirstSnapshotWorker.java#L16)

- 풀 밖 전용 연결의 advisory lock과 `pg_locks` 보유 확인
  [`WorkerSingleton.java:73`](../../backend/src/main/java/com/bokrilog/sync/internal/WorkerSingleton.java#L73)
  [`WorkerSingleton.java:109`](../../backend/src/main/java/com/bokrilog/sync/internal/WorkerSingleton.java#L109)

- 컨텍스트가 닫힌 뒤 drain 결과로 프로세스 종료 코드를 낸다
  [`BokriLogApplication.java:28`](../../backend/src/main/java/com/bokrilog/BokriLogApplication.java#L28)

- 대기 나이 gauge는 측정하는 역할에만 — API 슬롯의 상시 미측정 경보를 막는다
  [`SyncQueueAgeMetrics.java:12`](../../backend/src/main/java/com/bokrilog/sync/internal/SyncQueueAgeMetrics.java#L12)

**gate(배포 입력·자원·모드)**

- 리허설 모드는 ci/development + override 파일일 때만, 운영은 결함 주입 거부
  [`preflight.sh:131`](../../deploy/production/preflight.sh#L131)

- 서명 신원은 host 사실에서 — 이미지 서명과 SBOM attestation 검증
  [`preflight.sh:275`](../../deploy/production/preflight.sh#L275)

- contract는 rollback 창이 닫히고 migration 하나일 때만
  [`preflight.sh:306`](../../deploy/production/preflight.sh#L306)

- blue/green peak 메모리 gate
  [`preflight.sh:397`](../../deploy/production/preflight.sh#L397)

**배포 감사**

- SYSTEM `deploy`/`DEPLOY` 행, 대상 없는 행은 GATE FAILED만
  [`DeployAuditService.java:47`](../../backend/src/main/java/com/bokrilog/operations/internal/DeployAuditService.java#L47)
  [`DeployAuditApi.java:46`](../../backend/src/main/java/com/bokrilog/operations/DeployAuditApi.java#L46)

- Flyway 검사 없이 INSERT만 하는 일회성 역할
  [`DeployAuditRunner.java:23`](../../backend/src/main/java/com/bokrilog/DeployAuditRunner.java#L23)

**운영 구성**

- blue/green 슬롯·Worker·역할별 비밀 이름·`edge`/`data` 네트워크·로그 회전
  [`compose.yaml:109`](../../deploy/production/compose.yaml#L109)
  [`compose.yaml:175`](../../deploy/production/compose.yaml#L175)
  [`compose.yaml:59`](../../deploy/production/compose.yaml#L59)

- Nginx 본문은 그대로, upstream 블록만 include로 분리
  [`bokrilog.conf:87`](../../deploy/nginx/bokrilog.conf#L87)

**시험·CI·문서**

- 리허설 시나리오(정상 2·두 번째 Worker·drain 미완료·전환 전후 실패·contract·창 닫기)
  [`rehearse-deploy.sh:19`](../../deploy/production/rehearse-deploy.sh#L19)

- gate 거부 경로 fixture
  [`test-preflight.sh:419`](../../deploy/production/test-preflight.sh#L419)

- 여섯 작업 종류의 drain 대기, 잠금 연결 강제 종료 → 76
  [`WorkerDrainTest.java:39`](../../backend/src/test/java/com/bokrilog/sync/internal/WorkerDrainTest.java#L39)
  [`WorkerSingletonTest.java:119`](../../backend/src/test/java/com/bokrilog/sync/internal/WorkerSingletonTest.java#L119)

- 미적용 migration이 남은 DB에서 감사 역할이 행을 쓴다
  [`DeployAuditPendingMigrationTest.java:31`](../../backend/src/test/java/com/bokrilog/DeployAuditPendingMigrationTest.java#L31)

- `deploy-rehearsal` job과 `ci-required` 집계
  [`ci.yml:456`](../../.github/workflows/ci.yml#L456)
  [`aggregate-results.sh:26`](../../.github/scripts/aggregate-results.sh#L26)

- 운영 배포 절차·HA 한계·운영 배포 미검증
  [`DEVELOPMENT.md:335`](../../DEVELOPMENT.md#L335)
