---
title: 'Story 3.7 — 개인정보를 배제한 관측·경보 계측 기반'
type: 'feature'
created: '2026-10-12'
status: 'done'
review_loop_iteration: 0
baseline_commit: 'c959e6e94ef7ac9aa138ee22a47b48a537606ff1'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-3-strong-signal-auto-stop.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 운영자가 장애·용량·품질 신호를 볼 수 있는 계측이 없다. 메트릭은 0개이고 구조화 로그 몇 줄뿐이다(Epic 2 회고 R-5, Actuator 미승인). 로그에는 필드 allowlist도 redaction도 없다. 비밀값이 telemetry에 새는 강한 신호 3번(`SECRET_EXPOSED`)은 감지 지점이 없다. 경보 규칙도 정의돼 있지 않다(`epics.md` Story 3.7 AC-1·2·3 일부·5·6. 운영 연결·Web Vitals·운영시간/비용·Bybit 계약 재검증은 `deferred-work.md`로 분리).

**Approach:**
- **의존 승인.** 이 SPEC이 `spring-boot-starter-actuator`와 `micrometer-registry-prometheus`를 운영 classpath에 승인한다.
- **메트릭 노출.** 외부에 열리지 않는 관리 포트에 Prometheus 엔드포인트 하나만 연다. 낮은 카디널리티 메트릭만 allowlist로 둔다: 동기화 시도·성공·제외 사유, 작업 queue age, DB 연결, JVM 메모리, 문의 첫 답변 목표.
- **로그.** 로그는 필드 allowlist·redaction을 거치는 구조화 출력 하나로 낸다. 실제 구성된 비밀값이 출력에 나타나면 막고 3.3 `raise(SECRET_EXPOSED)`로 올린다.
- **경보 규칙.** AC-2의 경보를 Prometheus 규칙 파일로 정의하고, promtool 단위시험을 CI(개발 전용 도구)에서 돌린다.

## Boundaries & Constraints

**Always:**
- **환경 분리(AC-1).**
  - Actuator는 `management.server.port`(기본 `18081`, env `BOKRILOG_MANAGEMENT_PORT`)에서만 듣는다. compose는 그 포트를 게시하지 않고, Nginx는 프록시하지 않는다.
  - 노출 엔드포인트는 `prometheus` 하나다. `health`·`info`·`env`·`beans` 등은 끈다.
  - 외부 liveness 검사 대상은 기존 `GET /api/status`(`{"status":"UP"}`만, 의존 상태 비노출 — `StatusController` 계약 유지)다.
  - promtool은 CI·개발에서만 쓰고 운영 이미지·jar에 넣지 않는다.
- **메트릭 allowlist(AC-1·AC-3).**
  - 등록 가능한 메트릭 이름과 각 이름의 허용 tag 키·값 집합은 코드 한 곳(`TelemetryAllowlist`)의 닫힌 표다. 그 밖의 meter는 `MeterFilter`가 거부한다.
  - tag 값은 닫힌 enum 문자열뿐이다(예: 동기화 `trigger`·`outcome`·`reason` 코드). user·plan·request·account·connection ID, URL·경로, 이메일, 금액, Bybit 원문은 tag에도 이름에도 없다.
  - HTTP 서버 meter의 `uri` tag는 경로 템플릿만 쓴다. 템플릿이 없으면 `UNKNOWN`이다. 원시 URL은 쓰지 않는다.
  - 기본 JVM·Hikari meter는 표에 있는 것만 통과한다.
- **제품 계측(AC-3).** 분모·기간·환경을 메트릭 정의(코드 주석과 `DEVELOPMENT.md` 표)에 명시한다.
  - `bokrilog_sync_attempts_total{kind,trigger}`와 `bokrilog_sync_outcomes_total{kind,outcome,reason}`: 유효 시도는 admission을 통과한 작업이다. 제한·검증 거절은 `excluded` outcome과 reason으로 세고, Bybit 오류를 임의로 빼지 않는다.
  - `bokrilog_sync_queue_age_seconds{kind}`: 가장 오래된 대기 작업의 나이. DB 시각 기준이고 주기 측정이다.
  - `bokrilog_support_first_answer_total{met}`, `bokrilog_support_pending_target`
  - 모든 메트릭에 공통 tag `env`(`BOKRILOG_ENV`, 기본 `development`, 허용값 `development|ci|production`)를 붙여 개발·CI 수치가 운영 실적과 섞이지 않게 한다.
- **로그 redaction(AC-1·AC-5).**
  - 운영 로그는 Logback JSON 한 줄 출력이다. 허용 키는 `ts·level·logger·event·code·outcome·reason·kind·correlationId·durationMs·count`이고, 그 밖의 MDC·구조 인자는 버린다.
  - 메시지 본문은 3.6 `InquiryRules`의 SECRET·EMAIL·WALLET 규칙으로 가린다(`[REDACTED:<rule>]`).
  - 운영 프로세스가 아는 실제 비밀값 집합이 메시지·인자·예외 문자열에 나타나면 그 줄을 `[REDACTED:CONFIGURED_SECRET]`로 바꾸고 `raise(SECRET_EXPOSED, {ruleId: "CONFIGURED_SECRET_IN_LOG"})`를 부른다. 비밀값 집합은 OIDC client secret들, `BOKRILOG_AUDIT_DIGEST_KEY`, 자격정보 암호화 키, DB 비밀번호가 있으면 그것이다.
    - 호출은 재귀를 막는 가드 아래 비동기 1회이고, 같은 ruleId는 프로세스당 60초에 1번이다.
    - 신호 판정과 scope는 3.3 seed가 정한다.
  - 일반 로그 이벤트·일반 경보는 `raise`로 가지 않는다.
- **경보 규칙(AC-2·AC-5).** `deploy/observability/alerts.yml`(Prometheus 규칙)과 `deploy/observability/alerts.test.yml`(promtool 단위시험)을 둔다. 규칙은 다음과 같다.
  - 외부 liveness 실패 5분(`probe_success` 기반, synthetic 연결 전에는 미측정)
  - Alloy heartbeat 부재 10분
  - backup age 26시간 초과(`bokrilog_backup_last_success_timestamp_seconds`, 3.9 전에는 `absent()`면 미측정 경보 `BackupAgeUnmeasured`)
  - 동기화 실패율 15분 창 20% 이상(분모 10 이상), queue age 15분 이상
  - Hikari active/max 90% 이상 10분, JVM heap 사용률 90% 이상 10분
  - disk·Grafana 사용량 70%(warning)/85%(critical)
  - 월 비용 30,000원(`bokrilog_monthly_cost_krw`, 운영 장부 전에는 미측정)
  - 각 규칙은 `for:` 지속시간, `severity`, 중복 억제용 `group` label을 가진다. 강한 신호 후보는 `strong_signal="true"` label을 갖고, 일반 경보는 그 label이 없다.
  - promtool 시험이 각 임계값의 경계(직전 미발화·직후 발화)와 복구를 고정한다.
- **CI.** promtool 실행과 판정은 `.github/scripts/check-alert-rules.sh`가 소유한다(bash 3.2, `pipefail` 아래 `| grep -q` 금지). `ci.yml`에는 그 스크립트를 부르는 단계만 둔다. `test-ci-scripts.sh`를 함께 갱신한다. promtool은 고정 버전 바이너리를 checksum으로 받는다.
- **삭제·문구(AC-6).**
  - telemetry에 사용자 귀속 값이 없으므로 계정 삭제 뒤 재식별할 근거가 남지 않는다. 시험이 메트릭 출력 전체와 로그 캡처에서 UUID·이메일·숫자 6자리 이상을 0건으로 단정한다.
  - 운영 등급 문구는 `DEVELOPMENT.md`에 `24×7 실행 목표, best-effort, SLA 없음`으로 적는다.
  - 주 2시간·월 30,000원 달성 같은 선언은 어디에도 쓰지 않는다.
- **모듈.** 계측 등록은 각 모듈이 Micrometer `MeterRegistry`만 주입받아 allowlist 이름으로 한다. Actuator 의존은 루트 설정 한 곳이다. `identity`는 여전히 actuator에 의존하지 않는다(`ModuleBoundaryTest:102` 유지).
- **주석·문서.** 새 주석은 한국어다. `CLAUDE.md`의 의존 승인 문장과 계약 한 줄을 고친다.

**Ask First:**
- Actuator 엔드포인트를 `prometheus` 밖으로 여는 것.
- 관리 포트를 compose·Nginx로 게시하는 것.
- tag에 식별자류를 더하는 것.
- 일반 경보를 자동 정지로 잇는 것.
- Web Vitals·운영시간/비용·계약 재검증을 이번에 하는 것(분리됨).

**Never:**
- 운영 서버에 개발용 관측 스택(Prometheus·Grafana 자체 호스팅·promtool·k6·logcli).
- 원시 URL·식별자·금융값·Bybit payload를 telemetry에 남기는 것.
- 감사 원장을 로그로 대체하는 것.
- SLA·목표 달성 선언.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 메트릭 노출 | 관리 포트 `GET /actuator/prometheus` | allowlist 메트릭만, 모든 줄에 `env` tag, UUID·이메일·숫자 6자리+ 0건 | 공개 포트·Nginx 경유 `/actuator/**`는 404 |
| 비허용 meter | 코드가 표 밖 이름·tag로 등록 | 출력에 없음(거부) | 기동 실패 아님 |
| 동기화 계측 | 수동 동기화 1회 성공 + 제한으로 거절 1회 | attempts 1(통과분), outcomes `success` 1, `excluded{reason=RESTRICTED}` 1 | — |
| URI tag | `/api/dashboard/evidence?planId=…` 요청 | `uri="/api/dashboard/evidence"`, 쿼리 없음 | 템플릿 없음 → `UNKNOWN` |
| 로그 redaction | 로그 인자에 이메일·토큰 모양 | `[REDACTED:EMAIL]` 등, 허용 키 밖 MDC 없음 | — |
| 실제 비밀 노출 | 시험 훅이 구성된 OIDC client secret을 로그에 씀 | 그 줄 `[REDACTED:CONFIGURED_SECRET]`, `SECRET_EXPOSED` 신호 1행·제한, 60초 안 반복은 신호 1개 | 일반 로그는 신호 없음 |
| 경보 경계 | promtool: 실패율 19.9%/20%, queue age 14분/15분, backup 25h59m/26h, 비용 29,999/30,000 | 직전 미발화·직후 발화·복구 시 해제 | backup·비용 시계열 부재 → 미측정 경보 |
| 일반/강한 구분 | 규칙 파일 스캔 | `strong_signal="true"`는 승인된 강한 신호 규칙에만 | — |
| CI | `check-alert-rules.sh` | 규칙 문법·단위시험 통과면 0, 실패면 1, promtool 없음 2 | `test-ci-scripts.sh`가 세 경로 단정 |

</frozen-after-approval>

## Code Map

- `_bmad-output/planning-artifacts/epics.md` Story 3.7, `PORTFOLIO-SOLUTION-DESIGN.md` §12(운영 등급)
- **의존·설정:** `backend/build.gradle.kts`(dependencies 블록, `implementation(...)` 줄 형식), `backend/src/main/resources/application.yml`(설정 키 모음 — logback 설정 파일은 아직 없다)
- **liveness:** `backend/src/main/java/com/bokrilog/StatusController.java`(`/api/status`, Actuator를 쓰지 않는 이유가 javadoc에 있다 — 공개 표면에서는 그 결정을 유지하고 관리 포트만 연다), `AuthSecurityConfig.java:206` `STATUS_PATH`
- **신호:** `operations/SignalType.java:25` `SECRET_EXPOSED(Set.of("ruleId"))`, `OperationalSignalApi.raise`(3.3), sync 쪽 자리 `sync/internal/StrongSignals.java`(이번 감지는 로그 계층이므로 루트 또는 운영 설정에서 `OperationalSignalApi`를 직접 쓴다)
- **동기화 계측 지점:** `sync/internal/PlanSyncService.java`(admission·종료 로그 :1402 근처), `SyncJobService`(첫 스냅샷·초기 수집), `PlanPublicationService`(첫 발행), `FirstSnapshotWorker`(dispatch), lease 30초 `SyncLease`
- **문의 집계:** `operations/internal/SupportInquiryService`·`SupportInquiryRepository`(3.6, 첫 답변·목표 달성)
- **민감값 규칙:** `operations/internal/InquiryRules.java`(3.6, NFKC·SECRET·EMAIL·WALLET)
- **모듈 경계:** `ModuleBoundaryTest.java:97-124`(identity의 actuator 금지)
- **CI:** `.github/workflows/ci.yml`, `.github/scripts/test-ci-scripts.sh`(계약 단정), 기존 스크립트 `aggregate-results.sh`·`classify-changes.sh`
- **compose:** `deploy/compose/compose.yaml`(포트 게시는 Nginx 하나), `deploy/compose/verify-same-origin.sh`(로그 누출 검사 목록)

## Tasks & Acceptance

**Execution:**
- [x] `backend/build.gradle.kts`·`application.yml` -- Actuator·Prometheus registry 의존, 관리 포트·`prometheus`만 노출, `env` 공통 tag.
- [x] 루트 `TelemetryConfig.java`·`TelemetryAllowlist.java` -- `MeterFilter`(이름·tag 닫힌 표, `uri` 템플릿화).
- [x] `backend/src/main/resources/logback-spring.xml` + 루트 `RedactingJsonEncoder`(또는 동등 컴포넌트)·`ConfiguredSecrets` -- JSON 한 줄, 허용 키, 규칙 redaction, 구성 비밀 감지 → `raise(SECRET_EXPOSED)`(가드·60초 억제).
- [x] sync·operations 계측 -- 동기화 attempts·outcomes·queue age, 문의 첫 답변 meter.
- [x] `deploy/observability/alerts.yml`·`alerts.test.yml` -- AC-2 규칙과 promtool 경계 시험.
- [x] `.github/scripts/check-alert-rules.sh`·`ci.yml`·`test-ci-scripts.sh` -- promtool 고정 버전·checksum, 종료 코드 0/1/2.
- [x] 시험
  - `TelemetryAllowlistTest`: 출력 전체 스캔, 비허용 거부, `uri` 템플릿, 공개 포트 404.
  - `SyncMetricsTest`: 분모·reason.
  - `LogRedactionTest`: 허용 키, 규칙 redaction, 구성 비밀 → 신호 1행, 60초 억제, 일반 로그 무신호.
  - `ModuleBoundaryTest` 통과. `verify-same-origin.sh`가 공개 origin의 `/actuator/**` 404를 단정.
- [x] 문서 -- `CLAUDE.md`(의존 승인 문장·계약 한 줄), `DEVELOPMENT.md`(메트릭 정의 표: 이름·분모·기간·환경, 경보 표, 운영 등급 문구, 관리 포트 env).

**Acceptance Criteria:**
- Given 관리 포트의 Prometheus 출력을 훑으면, then 모든 시계열에 `env` tag가 있고 UUID·이메일·숫자 6자리 이상 연속이 0건이다.
- Given 공개 origin(Nginx 18080)에서 `/actuator/prometheus`를 부르면, then 404다.
- Given `check-alert-rules.sh`를 돌리면, then 모든 경보의 경계·복구 시험이 통과한다.
- Given 백엔드 build·프런트엔드 전체 검증·새 스택 `verify-same-origin.sh --keep-stack`·주입 origin e2e·`bash .github/scripts/test-ci-scripts.sh`를 돌리면, then 모두 통과한다.

## Spec Change Log

- 2026-10-12 Matrix Test Audit(step-03): 9행 모두 `TelemetryAllowlistTest`·`SyncMetricsTest`·`LogRedactionTest`·promtool `alerts.test.yml`·`test-ci-scripts.sh`가 덮고 Testcontainers 빌드 1339건·새 스택 1049건·주입 origin e2e 68건·CI 스크립트 161건 통과. 구현이 정한 곳 — 로그가 모든 환경에서 JSON 한 줄(작업 ID 빠짐, 예외 스택 버림), 본문 가림에 UUID·숫자 6자리 이상 추가, 구성 비밀값 감지는 16자 이상만, `strong_signal` 승인 목록은 비어 있음(신호 3은 앱이 직접 올림), 시도·결말 계측은 `plan_sync`·`first_snapshot` 둘, Alloy·probe·Grafana 사용량 시계열 이름은 3.8 연결 때 대조할 가정. SPEC 본문 변경 없음.

- 2026-10-12 리뷰 patch 루프(blind·edge-case·verification-gap): 주요 patch — 로그 진단 키 `exception`(단순 클래스 이름만)과 JVM 기본 `UncaughtExceptionHandler`의 SLF4J 전환, `correlationId` 문자 집합 단일화(`ApiErrorHandler.CORRELATION_ID`)와 숫자 6자리+ 클라이언트 값 대체(Nginx `$request_id`는 유지), code류 UUID 값 키째 버림, 구성 비밀 최소 길이 16→8과 짧은 값 설정 이름 WARN, 연결 해제·정밀 검증 제한 거절의 제외 계수, `eligible` 거절 사유 반환, 주요 카운터 0 선등록, 주기 측정의 가상 스레드·`statement_timeout` 2초·`V23` 부분 색인·`SyncQueueAgeUnmeasured`, `uri` 템플릿의 기동 시 확정, 답변 확정 전 미정 목표 채움, 경보 selector `env="production"`·미측정 `info`/`unmeasured`·값 경계 시험 보강, `check-alert-rules.sh` 형식 검사(labels 구간·flow 표기·들여쓰기)·캐시 재대조와 원자 저장·curl 60초/재시도 2·126/127→2와 단계 timeout 5분, 내려받기 실패 경로의 계약 시험, 관리 포트 127.0.0.1 바인드, actuator 의존 ArchRule, 시험 시작 표지. reject 2건. SPEC 본문 변경 없음.
- 2026-10-12 patch 판단(present 전): Nginx `$request_id`(소문자 hex 32자, Nginx가 클라이언트 헤더를 덮어씀)는 숫자 연속이 있어도 서버 값으로 보고 그대로 쓴다 — 바꾸면 `verify-same-origin.sh`의 "응답 헤더 = 본문 = access log 같은 ID" 계약이 깨진다. 연결 해제가 끝낸 작업의 `excluded{NOT_PERMITTED}` 계수는 매핑 단위 시험만 두고 통합 시험은 더하지 않는다. SPEC 본문 변경 없음.

## Design Notes

- **`/api/status`를 liveness로 쓰는 이유.** 스토리 문구는 `/health/live`지만 동일 출처 규칙상 Nginx는 `/api`만 넘긴다. 그리고 `StatusController`가 이미 의존 상태를 숨긴 `UP` 하나를 계약으로 고정했다. 새 공개 경로를 더하면 같은 일을 두 표면이 하게 된다.
- **구성 비밀값으로 DLP를 하는 이유.** 패턴 탐지는 오탐이 있고, 강한 신호는 전역 정지로 이어진다. 프로세스가 실제로 가진 비밀값의 등장은 오탐이 없는 "DLP rule 1건"이다(판정표 3번). 패턴 탐지는 가리기만 하고 신호로 올리지 않는다.
- **미측정 경보.** backup(3.9)·비용 장부(분리)·synthetic(3.8) 전에는 시계열이 없다. 규칙은 지금 정의하되 `absent()`로 "미측정"을 경보한다. 그래야 연결이 빠진 채 조용히 통과하지 않는다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 통과, 경고 0.
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run build && npm --prefix frontend run test && npm --prefix frontend run test:e2e` -- expected: 통과.
- `bash .github/scripts/test-ci-scripts.sh && bash .github/scripts/check-alert-rules.sh` -- expected: 통과(promtool 없으면 스크립트가 받는다).
- `docker compose --file deploy/compose/compose.yaml down --remove-orphans --volumes && deploy/compose/verify-same-origin.sh --keep-stack && PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 npm --prefix frontend run test:e2e:run` -- expected: 모두 통과(끝나면 스택 down).

## Suggested Review Order

**메트릭 allowlist와 노출 경계**

- 진입점: 닫힌 이름·tag 표와 `MeterFilter`(`env` 공통 tag, `uri` 템플릿화)
  [`TelemetryAllowlist.java:27`](../../backend/src/main/java/com/bokrilog/TelemetryAllowlist.java#L27)
  [`TelemetryConfig.java:31`](../../backend/src/main/java/com/bokrilog/TelemetryConfig.java#L31)

- 동기화 시도·결말·제외·대기 나이 계측(커밋 뒤 계수, 가상 스레드 주기 측정)
  [`SyncMetrics.java:34`](../../backend/src/main/java/com/bokrilog/sync/internal/SyncMetrics.java#L34)

**로그 redaction과 DLP 신호**

- JSON 한 줄·허용 키·본문 가림·`exception` 단순 이름
  [`RedactingJsonEncoder.java:38`](../../backend/src/main/java/com/bokrilog/RedactingJsonEncoder.java#L38)

- 구성 비밀값 등장 → 가림 + `SECRET_EXPOSED`(재귀 가드·60초 억제)
  [`LogSecretGuard.java:28`](../../backend/src/main/java/com/bokrilog/LogSecretGuard.java#L28)
  [`ConfiguredSecrets.java:41`](../../backend/src/main/java/com/bokrilog/ConfiguredSecrets.java#L41)

- stderr 스택을 막는 기본 처리기
  [`UncaughtExceptionLogging.java:16`](../../backend/src/main/java/com/bokrilog/UncaughtExceptionLogging.java#L16)

**경보 규칙과 CI**

- `env="production"` 규칙·미측정 info 경보
  [`alerts.yml:81`](../../deploy/observability/alerts.yml#L81)

- promtool 고정 버전·checksum·형식 계약(0/1/2)
  [`check-alert-rules.sh:29`](../../.github/scripts/check-alert-rules.sh#L29)
