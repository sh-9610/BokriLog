---
title: 'Story 4.2 — 수동 동기화 성공 결과에 광고 자격과 빈도 제한 적용하기'
type: 'feature'
created: '2026-10-15'
status: 'done'
review_loop_iteration: 0
baseline_commit: 'f88e69264d1c59a285dcb5c1a12189a8c9ac78ea'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-1-ad-consent.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 동의(4.1)는 저장되지만, "이 결과에서 광고를 시도해도 되는가"를 정하는 서버 판정이 없다. 그래서 4.3이 광고를 시도할 근거가 없다.
- 무엇이 자격 후보인지 정해지지 않았다. 공식 결과가 원자 저장된 수동 동기화만이어야 한다.
- 빈도 제한이 없다. 30분 간격과 `Asia/Seoul` 하루 3회를 서버가 세야 한다.
- 동시 탭의 원자 reservation이 없고, `ADVERTISING` 운영 제한과 공급자 활성 상태를 읽는 지점도 없다(`epics.md` Story 4.2 AC-1~5).

**Approach:**
- `engagement`에 광고 자격 판정을 더한다. 결과 참조는 수동 동기화 attempt의 `jobId`다. 클라이언트가 동기화 완료 때 이미 쥐는 식별자다.
- `POST /api/engagement/ad-eligibility`가 판정을 맡는다.
  - 한 트랜잭션에서 후보 판정 → 관문(동의·운영 제한·공급자) → 빈도 제한 → 결정 행 저장 순서로 한다.
  - `(user, jobId)`마다 결정은 한 행이다. 재전송은 새 시도가 아니다.
- 자격을 얻으면 그 행이 곧 reservation이다. 노출 시도로 세고, 4.3의 중립 화면이 쓸 불투명 transition 참조를 함께 발급한다.
- 4.2는 서버 판정뿐이다. 이 API를 부르는 화면, 공급자 호출, 광고 UI는 4.3이다.

## Boundaries & Constraints

**Always:**
- **후보.** 그 사용자의 plan-sync attempt 가운데 다음을 모두 만족하는 것만 후보다.
  - `purpose = 'SYNC'`, `trigger = 'MANUAL'`
  - `status = 'SUCCEEDED'`, `calculation_id IS NOT NULL`
  - 판정은 요청 본문이 아니라 attempt 행으로 한다.
  - 정밀 검증(`VERIFY`)·이어 가기(`CATCH_UP`)·완료(`COMPLETION`·`POST_END_VALUE`)·ENTRY·VISIBILITY·SYSTEM·실패·검토 필요·차단·진행 중은 후보가 아니다.
  - 남의 attempt와 없는 attempt도 후보가 아니다. 셋 다 같은 404이고 행을 쓰지 않는다.
- **sync 조회.** sync는 새 공개 메서드 하나로 이 판정을 준다(`SyncJobApi`의 Story 4.2 절). engagement → sync 의존만 생기고, sync는 engagement를 모른다.
- **관문.** 순서는 다음과 같고, 처음 걸린 것이 사유다.
  1. `CONSENT_REQUIRED` — `AdConsentApi.current(user).granted()`가 아니다.
  2. `RESTRICTED` — `OperationalRestrictionApi.blocks({ADVERTISING}, RestrictionSubject.none())`. 판독 오류는 예외로 올린다(fail-closed).
  3. `PROVIDER_DISABLED` — 구성 `bokrilog.engagement.ad-provider-enabled`(`BOKRILOG_AD_PROVIDER_ENABLED`, 기본 `false`)가 거짓이다.
- **빈도 제한.** 관문을 지나야 센다. 순수 함수가 판정하고, 기준 시각은 DB 시각(`statement_timestamp()`)이다.
  - `INTERVAL` — 마지막 자격 결정 뒤 30분 미만(정확히 30분이면 허용).
  - `DAILY_CAP` — `Asia/Seoul` 같은 날짜의 자격 결정이 이미 3건.
  - 두 사유가 함께면 `DAILY_CAP`이다.
- **결정 행.** 표 `engagement_ad_attempt`를 둔다.
  - 열: `id` bigint identity, `user_id`, `sync_job_id`, `outcome`(`ELIGIBLE`/`SKIPPED`), `skip_reason`, `transition_ref`, `decided_at` DEFAULT `statement_timestamp()`
  - 유일 `(user_id, sync_job_id)`, 유일 `transition_ref`
  - `transition_ref`는 ELIGIBLE에만 있는 서버 생성 128비트 이상 난수이고, 사용자·결과 식별이 없다.
- **재전송.**
  - 같은 `(user, jobId)`의 두 번째 요청은 행을 더하지 않고 cap도 소비하지 않는다.
  - SKIPPED는 처음 판정 그대로 돌려준다.
  - ELIGIBLE이면 관문만 다시 보고, 걸리면 그 사유로 SKIPPED를 돌려준다. 행은 바꾸지 않는다. 철회가 다음 요청부터 바로 적용되게 하려는 것이다.
- **트랜잭션 순서.** 사용자 advisory 잠금(동의와 다른 접두사) → `lockWriteAccess`(거짓이면 404) → 기존 행 → 후보 → 관문 → cap → INSERT. 동시 탭 가운데 하나만 자격을 얻는다.
- **응답.** 200 `{outcome, reason, transition, replayed}`이다.
  - `reason`은 SKIPPED에서만 있고, `transition`은 ELIGIBLE에서만 있다.
  - 사용자·결과·금융 값은 싣지 않는다.
  - CSRF·`Origin`은 기존 `/api/engagement` 술어가 이미 덮는다. 자연 키가 `(user, jobId)`이므로 멱등 키 헤더는 받지 않는다.
- **결과 접근.** 판정·cap은 결과 접근을 막지 않는다. `GET /api/dashboard`와 동기화 경로는 engagement를 부르지 않는다.
- **계측.** 커밋 뒤 카운터 셋을 올린다. 이름과 tag는 `TelemetryAllowlist`에 먼저 더한다.
  - `bokrilog.ad.trigger` — 판정에 닿은 요청
  - `bokrilog.ad.skip{reason}` — 사유 일곱: 위 다섯·`NOT_CANDIDATE`·`REPLAYED`
  - `bokrilog.ad.reservation` — 새 ELIGIBLE
  - tag에 사용자·결과·금융 값을 싣지 않는다. 로그는 단계·결과 code만 남긴다.
- **삭제.** `EngagementDeletionApi.eraseAdAttempts`(멱등, 지운 행 수)를 둔다. `AccountErasure`가 같은 트랜잭션에서 부르고, 호출자는 `AccountErasure` 하나로 고정한다. 잔여 행은 기존 카탈로그 세기가 잡는다.

**Ask First:**
- 정밀 검증·이어 가기 같은 다른 수동 attempt를 후보에 넣는 것.
- 운영 환경에서 `BOKRILOG_AD_PROVIDER_ENABLED=true`를 거부하거나 강제하는 기동 검사.

**Never:**
- 프런트엔드 변경, 공급자 SDK·네트워크 호출, 광고 UI, 중립 route. 모두 4.3이다.
- 다른 사유로 cap을 우회하는 것, 자동 재시도, 다른 공급자 fallback.
- trigger를 클라이언트 본문에서 믿는 것. 대시보드 진입·탭 재활성화가 판정을 일으키는 경로를 만드는 것.
- sync·plan·dashboard가 engagement를 import하는 것.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 자격 | 동의·제한 없음·공급자 켜짐, 성공한 MANUAL SYNC, 기록 없음 | 200 `ELIGIBLE`·`transition`, 행 1·reservation 1 | N/A |
| 비동의 | 동의 `UNDECIDED`·`DENIED` | 200 `SKIPPED`·`CONSENT_REQUIRED`, 행 1, cap 소비 없음 | N/A |
| 운영 제한 | 열린 `ADVERTISING` 제한 | `SKIPPED`·`RESTRICTED` | 판독 오류는 5xx(fail-closed) |
| 공급자 꺼짐 | 기본 구성 | `SKIPPED`·`PROVIDER_DISABLED` | N/A |
| 30분 | 마지막 자격 29분 59초 전 / 30분 전 | `INTERVAL` / `ELIGIBLE` | N/A |
| 하루 3회 | KST 오늘 자격 3건 / 3건이 전날 23:59 KST | `DAILY_CAP` / 날짜 경계 넘어 `ELIGIBLE`(간격 충족 시) | N/A |
| 비후보 | ENTRY·VISIBILITY·VERIFY·CATCH_UP·FAILED·NEEDS_REVIEW·진행 중·남의 job·없는 job | 404 공통 | 행 없음 |
| 재전송 | 같은 jobId 다시 | 처음 결과, `replayed:true`, 행·cap 변화 없음 | N/A |
| 재전송 뒤 철회 | ELIGIBLE 뒤 동의 철회 → 같은 jobId | `SKIPPED`·`CONSENT_REQUIRED`, 행은 ELIGIBLE 그대로 | N/A |
| 동시 탭 | 서로 다른 두 성공 job을 동시에 | 하나 `ELIGIBLE`, 다른 하나 `INTERVAL` | N/A |
| 삭제 경합 | 삭제 시작 계정 | 404 | 행 없음 |
| 형식 오류 | 본문 없음·`syncJobId`가 UUID 아님 | 400 | 행 없음 |

</frozen-after-approval>

## Code Map

**백엔드** (`backend/src/main/java/com/bokrilog/`)
- `sync/SyncJobApi.java:193-274` — Story 2.11 절. `SyncTrigger`(:196), `SyncJob`(:218), `admitPlanSync`(:274). 새 Story 4.2 절을 더한다.
- `sync/internal/PlanSyncRepository.java` — 조회 선례.
  - `byId(user, jobId)`(:136): user를 조건에 건다.
  - `creatingSync(user, jobId)`(:482): status·calculationId·pointer·purpose를 읽는다.
  - 후보 술어는 여기에 쿼리로 둔다.
- `backend/src/main/resources/db/migration/V11__incremental_plan_sync.sql`·`V12`·`V14` — `sync_plan_sync_job`의 열·CHECK.
  - trigger·purpose·status 값
  - SUCCEEDED인데 calculation이 없는 COMPLETION·POST_END_VALUE
- `sync/internal/PlanSyncPublisher.java:85` — 발행 원자성. SUCCEEDED와 `calculation_id`가 같은 트랜잭션에서 쓰인다.
- `engagement/AdConsentApi.java:59` `View.granted()`, `:86` `current` — 동의 관문.
- `engagement/internal/AdConsentService.java:79`·`AdConsentRepository.java:23,43` — 트랜잭션·advisory 잠금 관용구. 새 판정은 다른 잠금 접두사를 쓴다.
- `engagement/internal/AdConsentRepository.java:30-34` `RESIDUE` — `engagement_%`·`user_id` 카탈로그 세기. 새 표는 자동으로 세어진다.
- `engagement/EngagementDeletionApi.java:22,28` — 삭제 port. `eraseAdAttempts`를 더한다.
- `AdConsentController.java` — 루트 컨트롤러 선례(`allowedUser`·공통 404·`noStore`·단계 로그). 새 `AdEligibilityController`.
- `AuthSecurityConfig.java:285` `ENGAGEMENT_PATH` — 접두사가 이미 인가·CSRF·`Origin`에 등록돼 있다. 새 술어는 필요 없다.
- `operations/OperationalRestrictionApi.java:72` `blocks`, `RestrictionSubject.none()`(:24) — 판독 선례는 `BybitConnectionService.java:220-223`.
- `operations/OperatorProperties.java:36`, `operations/internal/OperationsConfiguration.java:24` — `@ConfigurationProperties` record와 `@EnableConfigurationProperties` 선례. `application.yml:163-264`의 `${BOKRILOG_X:기본값}` 형식.
- `TelemetryAllowlist.java:98-136` — meter 행 추가. `operations/internal/SupportMetrics.java:34-54`는 0 미리 등록과 `afterCommit` 증가 선례.
- `operations/internal/BusinessDayCalendar.java`, `calculation/internal/SeoulCalendar.java:24` — DB 시각을 받는 순수 함수와 `Asia/Seoul` 경계 선례.
- `AccountErasure.java:107` — engagement erase 호출 자리.
- 다음 마이그레이션은 V27이다.

**백엔드 시험** (`backend/src/test/java/com/bokrilog/`)
- `PlanSyncTestBase.java` — 시험 helper.
  - `connect(UTA2)`(:260), `publishedPlan`(:200), `awaitSync(jobId)`(:46), `countOf`(:128)
  - `IncrementalSyncTest.java:257-298`: `admitPlanSync(user, MANUAL, key)` → `Accepted.job().jobId()` → `awaitSync`
  - 실패·검토 선례: `:550`(NEEDS_REVIEW), `:588`(FAILED)
- `ModuleBoundaryTest.java:369-376`(동의 API 의존 제한), `:382-390`(`eraseConsents` 호출자 고정), `:398-418`(`engagement_` 표 이름 경계). 새 erase·새 표에 같은 규칙을 더한다.
- `TelemetryAllowlistTest.java:173` — 행별 tag 단정 선례.
- `AuthFlowTestBase.java` `truncateStore` — 새 표를 더한다.
- `AdConsentTest.java:312` — 잠금 선점 동시성 시험 선례.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/resources/db/migration/V27__engagement_ad_attempt.sql` — 결정 표와 색인. 사용자별 최근 자격을 고르는 색인 `(user_id, id DESC) WHERE outcome = 'ELIGIBLE'`를 둔다.
- [x] `sync/SyncJobApi.java`, `sync/internal/SyncJobService.java`, `sync/internal/PlanSyncRepository.java` — `boolean publishedManualSync(UUID userId, UUID jobId)`를 더한다. 위 후보 술어 한 문장이다.
- [x] `engagement/AdEligibilityApi.java`(공개), `engagement/internal/AdFrequencyPolicy.java`(순수), `AdEligibilityService.java`, `AdAttemptRepository.java`, `EngagementProperties.java`·`EngagementConfiguration.java`, `AdMetrics.java` — 판정·저장·구성·계측. `EngagementDeletionApi`에 `eraseAdAttempts`를 더한다.
- [x] `backend/src/main/resources/application.yml` — `bokrilog.engagement.ad-provider-enabled: ${BOKRILOG_AD_PROVIDER_ENABLED:false}`와 주석 표 한 줄.
- [x] `AdEligibilityController.java`(루트), `TelemetryAllowlist.java`, `AccountErasure.java` — 경로, meter 행, erase 호출.
- [x] 시험을 더한다. 기존 시험 넷(`ModuleBoundaryTest`·`AuthFlowTestBase`·`AccountErasureTest`·`TelemetryAllowlistTest`)도 맞춘다.
  - `engagement/internal/AdFrequencyPolicyTest`: 30분 경계, KST 자정 전후, UTC 자정이 KST 날짜를 가르지 않음, 미국 DST 전환일 무관, 3회 상한.
  - `AdEligibilityTest`: I/O 매트릭스 전 행. 공급자 켜짐은 시험 속성으로 준다.
- [x] `DEVELOPMENT.md`·`epic-4-context.md`는 손대지 않는다. `CLAUDE.md`는 머지 단계에서 갱신한다.

**Acceptance Criteria:**
- Given 계정이 판정 기록을 가진 사용자, when 계정 삭제가 실행될 때, then `engagement_ad_attempt` 잔여 행은 0이다. 같은 jobId로 다시 판정하면 404이고 행을 만들지 않는다.
- Given 어떤 판정 결과든, when `GET /api/dashboard`를 읽을 때, then 응답은 판정이 없던 때와 같다.
- Given 판정 요청들, when 관리 포트 `prometheus` 출력을 읽을 때, then `bokrilog_ad_*` 계열이 보인다. 사용자·job·transition 값이나 금융 값은 label에 없다.

## Spec Change Log

- 2026-10-15 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap) — intent_gap·bad_spec 0, patch 12, defer 4(`deferred-work.md` 149~152), 나머지 reject.
  - **defer 4건:** transition 소비·수명(4.3), 후보 신선도(4.3), `BOKRILOG_AD_PROVIDER_ENABLED` 배포 연결(출시 게이트), 결정 행 보존 기한(G-4).
  - **patch — 운영 코드:** 500 경로에 WARN `exception` 단순 이름을 남긴다. 잔여 행 세기를 모듈 수준 `EngagementResidueRepository`로 옮겼다. skip 사유를 enum 한 원천으로 모았다. 시험 훅은 잠금 접두사를 운영 상수로 참조한다. V27 주석·javadoc에 "재전송은 결론을 낮추기만 한다"를 적었다.
  - **patch — 시험 추가:** 500 HTTP 시험, 자격 행 4개 이상에서 최신순 정렬, 기본 구성 공급자 꺼짐 바인딩, V27 CHECK와 enum 대조, SKIPPED 재전송이 관문 해제 뒤에도 그대로, 옛 정책 버전 동의, 인증된 GET·PUT 405, 삭제 실패 주입 두 메서드.
  - **patch — 시험 결정성:** 시각 의존 시험을 DB의 KST 자정 기준과 `pg_locks` 대기 poll로 바꿨다.
  - **reject:** skip 계측에 `REPLAYED`·`NOT_CANDIDATE`를 포함하는 것은 SPEC 계약이라 유지했다. 본문 크기 상한·잠금 timeout은 기존 컨트롤러·잠금 관용과 같아 유지했다.
  - **KEEP:** `(user, jobId)` 한 행, attempt 행 기반 후보 판정, 관문 → cap 순서, 순수 `AdFrequencyPolicy`, sync → engagement 무의존.

## Design Notes

- **jobId를 참조로 쓴다.** 클라이언트는 수동 동기화 접수 응답의 `job.jobId`를 받고, 완료를 그 job으로 따라간다. 그래서 4.3이 결과 보기 클릭 때 보낼 수 있다. 서버는 그 행의 소유·purpose·trigger·발행으로만 판정하므로, 클라이언트가 trigger를 꾸며도 성공한 SYNC 발행이 없으면 후보가 아니다.
  - 남는 위험: 위조한 `MANUAL` 본문으로 만든 실제 수동 SYNC 성공은 후보다. 이 경우 cap과 동기화 cooldown이 횟수를 묶는다.
- **SKIPPED도 저장한다.** 같은 결과의 재전송이 다른 결론을 내지 않게 하려는 것이다. 예를 들어 cap에 걸린 결과가 30분 뒤 재전송으로 자격을 얻으면 안 된다. 관문 재확인은 ELIGIBLE을 낮추기만 한다.
- **cap 판정은 순수 함수다.** 입력은 `(now, 최근 자격 시각들)`이다.

```java
// INTERVAL: now < last + 30m;  DAILY_CAP: count(d in recent : seoulDate(d) == seoulDate(now)) >= 3
```

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` — expected: 성공. 다음이 통과한다.
  - `AdEligibilityTest`·`AdFrequencyPolicyTest`·`ModuleBoundaryTest`·`TelemetryAllowlistTest`·`AccountErasureTest`·`RestoreApplyTest`
  - `-Werror`
- `bash .github/scripts/test-ci-scripts.sh` — expected: 통과.

## Suggested Review Order

**판정 트랜잭션 (진입점)**

- 잠금 → 쓰기 접근 → 기존 행 → 후보 → 관문 → cap → INSERT 한 트랜잭션
  [`AdEligibilityService.java:76`](../../backend/src/main/java/com/bokrilog/engagement/internal/AdEligibilityService.java#L76)

- 재전송은 결론을 낮추기만 한다 — ELIGIBLE만 관문을 다시 본다
  [`AdEligibilityService.java:118`](../../backend/src/main/java/com/bokrilog/engagement/internal/AdEligibilityService.java#L118)

- 관문 순서: 동의 → ADVERTISING 전역 제한 → 공급자
  [`AdEligibilityService.java:131`](../../backend/src/main/java/com/bokrilog/engagement/internal/AdEligibilityService.java#L131)

- 30분·KST 하루 3회를 DB 시각으로 판정하는 순수 함수
  [`AdFrequencyPolicy.java:43`](../../backend/src/main/java/com/bokrilog/engagement/internal/AdFrequencyPolicy.java#L43)

**후보와 경계**

- 후보 술어 한 문장 — SYNC·MANUAL·SUCCEEDED·발행
  [`PlanSyncRepository.java:146`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncRepository.java#L146)

- sync 공개 API의 Story 4.2 절
  [`SyncJobApi.java:285`](../../backend/src/main/java/com/bokrilog/sync/SyncJobApi.java#L285)

- 모듈 공개 API와 결과 sealed 타입
  [`AdEligibilityApi.java:26`](../../backend/src/main/java/com/bokrilog/engagement/AdEligibilityApi.java#L26)

**표면과 스키마**

- 결정 표·유일 제약·CHECK
  [`V27__engagement_ad_attempt.sql:29`](../../backend/src/main/resources/db/migration/V27__engagement_ad_attempt.sql#L29)

- 루트 컨트롤러 — 200 판정, 비후보 404, 실패 500·WARN
  [`AdEligibilityController.java:75`](../../backend/src/main/java/com/bokrilog/AdEligibilityController.java#L75)

- 공급자 활성 구성(기본 꺼짐)
  [`application.yml:244`](../../backend/src/main/resources/application.yml#L244)

**삭제·계측**

- 계정 삭제가 결정 행을 지운다
  [`AccountErasure.java:108`](../../backend/src/main/java/com/bokrilog/AccountErasure.java#L108)

- ad meter 행 allowlist
  [`TelemetryAllowlist.java:98`](../../backend/src/main/java/com/bokrilog/TelemetryAllowlist.java#L98)

**시험**

- 30분·KST 하루 3회·날짜 경계·최신순 통합 시험
  [`AdEligibilityTest.java:259`](../../backend/src/test/java/com/bokrilog/AdEligibilityTest.java#L259)

- 비후보 attempt 전부와 남의 것·없는 것이 같은 404
  [`AdEligibilityTest.java:321`](../../backend/src/test/java/com/bokrilog/AdEligibilityTest.java#L321)

- 동시 탭 하나만 자격, 잠금 대기
  [`AdEligibilityTest.java:376`](../../backend/src/test/java/com/bokrilog/AdEligibilityTest.java#L376)

- 삭제 호출자 고정과 모듈 의존 경계
  [`ModuleBoundaryTest.java:446`](../../backend/src/test/java/com/bokrilog/ModuleBoundaryTest.java#L446)
