---
title: 'Epic 4 회고 AI-2·AI-3 — 문서·주석 정합과 검증 공백'
type: 'chore'
created: '2026-10-17'
status: 'done'
baseline_commit: 'a39e18ef08a79b006228be1f90816d00830af545'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-retro-2026-10-16.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Epic 4 회고가 "지금 고침"으로 분류한 두 묶음이 남았다.
- **AI-2(D-1·D-2·D-4·R-4).** 4.2·4.3이 기존 파일에 영어 주석을 썼고, 500 로그 방식이 Epic 4 안에서 둘로 갈렸다. 주석·문서가 코드와 어긋나고, 광고 출시 게이트 G-4가 `CLAUDE.md` 미검증 목록에 없다.
- **AI-3(D-3·B-4·B-5).** 프런트·백이 따로 든 값(transition 참조 모양·API 경로)에 대조 시험이 없다. `POST /ad-transition` 405의 CSP를 아무도 단정하지 않는다. 정책 버전 상향을 소비 단계에서 보는 시험도 없다.

**Approach:** 동작을 바꾸지 않는 정합 chore다. 주석·로그·문서를 맞추고, 시험 셋을 더한다.

## Boundaries & Constraints

**Always:**
- **D-1.** Epic 4가 더한 영어 주석만 한국어로 바꾼다. 대상은 `PlanSyncRepository.publishedManualSync`의 javadoc(4.2)과 `useDashboardSync.ts`에서 4.3이 더한 주석·javadoc이다. 같은 파일의 다른 영어 주석은 그대로 둔다(AI-10 규칙: 고치는 범위만).
- **D-2.** `AdConsentController`의 500 로그를 `AdEligibilityController`·`AdTransitionController`의 3.7 관용으로 맞춘다. `LOG.atWarn()`, `exception`에는 단순 이름만, 메시지는 영어 고정 문장이다.
- **D-4.**
  - `AdAttemptRepository` 잠금 주석을 실제 직렬화에 맞춘다. 동의 쓰기·판정·소비·계측은 모두 `lockWriteAccess`(생명주기 행)에서 한 줄로 선다.
  - `AuthSecurityConfig.ENGAGEMENT_PATH` Javadoc에 4.3 `ad-transition`과 4.5 `telemetry`를 더한다.
  - `CLAUDE.md`의 세션 시작 3번 "(현재 Epic 4는 …)" 문구를 고친다. 복원(3.10) 계약 줄의 erase 순서에는 engagement를 더한다(`AccountErasure`의 실제 순서를 읽어 그대로 적는다).
  - `spec-4-3-neutral-ad-transition.md` `## Spec Change Log`에 PR #111 CI 세 번째 수정 `8b26c59` 한 줄을 추가한다. 추가만 하고 frozen은 건드리지 않는다.
- **R-4.** `CLAUDE.md` `## 현재 진행 지점`의 미검증 목록에 광고 출시 게이트 G-4를 한 문장으로 더한다. 묶는 것은 실제 계정·ad unit 승인, request field·CSP 실측(153), 단위 경로 형식(154), 소재 접근성, 개인정보 검토·동의 카피, 보존 기한(148·152), 공급자 변수 배포 연결(151)이다.
- **D-3.** `AdConsentPolicyVersionTest` 선례(Spring·DB 없이 파일 읽기)대로 백엔드 시험 하나가 세 값을 대조한다.
  - (a) transition 참조 모양: `AdTransitionController`의 패턴, `V27` CHECK, `frontend/src/content/adTransition.ts`의 `TRANSITION_PATTERN`.
  - (b) API 경로 셋: `AD_CONSENT_PATH`·`AD_ELIGIBILITY_PATH`·`AD_TRANSITION_CLAIM_PATH`를 컨트롤러 상수와 대조한다.
  - 선언은 각각 하나뿐이어야 한다.
  - 시험이 컨트롤러 상수를 읽을 수 있게 하는 범위 안에서만 상수 가시성을 바꾼다.
- **B-4.** `verify-same-origin.sh`의 `POST /ad-transition` 405에 CSP 단정을 더한다(광고 정책 전문 `expected_ad_transition_csp`). 그 근처 주석 "다른 모든 응답은 `expected_csp`"도 사실에 맞춘다. `ci.yml`이 바뀌지 않으면 `test-ci-scripts.sh`는 그대로 통과해야 한다.
- **B-5.** `AdTransitionTest`에 소비 단계 시험을 더한다. 자격 판정 뒤 최신 동의 행이 옛 정책 버전(`AD-CONSENT-0.9`)의 GRANTED로 바뀌면 소비가 `CONSENT_REQUIRED` 건너뜀이고 표본·SHOW가 없어야 한다.

**Ask First:** 제품 동작·응답·스키마를 바꾸는 것, 이 목록 밖의 영어 주석을 일괄 번역하는 것.

**Never:** 새 마이그레이션, 이미 적용된 마이그레이션 수정, AI-8(D-5 공통 부품) 범위의 helper 추출, `deferred-work.md` 기존 항목 수정.

</frozen-after-approval>

## Code Map

모든 경로와 명령은 worktree `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog-ai2`(브랜치 `chore/epic-4-retro-docs-and-verification`) 기준이다. 사용자 체크아웃은 고치지 않는다. Node 명령 앞에는 `eval "$(fnm env)" && fnm use 24 >/dev/null &&`를 붙인다. 백엔드 시험은 Docker Desktop이 떠 있어야 한다.

- `backend/src/main/java/com/bokrilog/sync/internal/PlanSyncRepository.java:141-145` — 4.2 영어 javadoc.
- `frontend/src/session/useDashboardSync.ts` — 4.3 영어 주석. `git diff 61aac2b^1 61aac2b -- frontend/src/session/useDashboardSync.ts`가 더한 주석 줄들이다(예: `:474-479`, 판정 effect의 javadoc, `adAsked` 근처 한 줄 주석 둘, 대화상자·새 접수 중 이동하지 않는 이유).
- `backend/src/main/java/com/bokrilog/AdConsentController.java:201-205` — `internal()`의 `LOG.debug`. 본보기는 `AdEligibilityController.java:95`.
- `backend/src/main/java/com/bokrilog/engagement/internal/AdAttemptRepository.java:24-27` — `USER_LOCK_PREFIX` 주석.
- `backend/src/main/java/com/bokrilog/AuthSecurityConfig.java:278-285` — `ENGAGEMENT_PATH` Javadoc.
- `CLAUDE.md:13`(세션 시작 3번), 복원(3.10) 계약 줄(`portfolio·sync·plan·source·operations erase`), `## 현재 진행 지점`의 "Epic 3의 운영·출시 증거는 셋 다 미검증으로 남는다" 문장 근처.
- `backend/src/main/java/com/bokrilog/AccountErasure.java` — erase 순서의 원천(읽기 전용).
- `_bmad-output/implementation-artifacts/spec-4-3-neutral-ad-transition.md:161-163` — Change Log의 PR #111 CI 줄 둘 뒤.
- `backend/src/test/java/com/bokrilog/engagement/AdConsentPolicyVersionTest.java` — 파일 대조 선례(`Path.of("../frontend/...")`).
- `backend/src/main/java/com/bokrilog/AdTransitionController.java:45,53`, `AdEligibilityController.java:49`, `AdConsentController.java:53` — 경로·패턴 상수(루트 패키지 package-private). 새 시험은 루트 패키지 `com.bokrilog`에 두면 가시성을 바꾸지 않아도 된다.
- `backend/src/main/resources/db/migration/V27__engagement_ad_attempt.sql:49` — CHECK.
- `frontend/src/content/adTransition.ts:18,21,30`, `frontend/src/content/adConsent.ts:17`.
- `deploy/compose/verify-same-origin.sh:2865-2867` — 405 단정. CSP는 `header_value 'Content-Security-Policy'`, 기대값 변수는 `expected_ad_transition_csp`(`:622` 근처). 주석 "다른 모든 응답"의 위치는 `:658` 근처.
- `backend/src/test/java/com/bokrilog/AdTransitionTest.java` — 동의 행 삽입 helper(`:146`·`:187` 근처)와 `AdEligibilityTest.java:238-255`의 옛 버전 시험 모양.

## Tasks & Acceptance

**Execution:**
- [x] `PlanSyncRepository.java`·`useDashboardSync.ts` — D-1 번역.
- [x] `AdConsentController.java` — D-2 로그.
- [x] `AdAttemptRepository.java`·`AuthSecurityConfig.java` — D-4 주석.
- [x] `CLAUDE.md` — D-4 두 곳과 R-4 G-4 문장.
- [x] `spec-4-3-neutral-ad-transition.md` — Change Log 한 줄 추가.
- [x] `backend/src/test/java/com/bokrilog/AdSharedValuesTest.java`(새) — D-3 대조.
- [x] `deploy/compose/verify-same-origin.sh` — B-4.
- [x] `AdTransitionTest.java` — B-5.

**Acceptance Criteria:**
- Given 대조 시험, when 프런트 `TRANSITION_PATTERN`이나 경로 하나를 바꿀 때, then 시험이 실패한다(한 번 바꿔 실패를 확인한 뒤 되돌린다).
- Given `verify-same-origin.sh`, when 동일 출처 스택에서 돌릴 때, then 0으로 끝나고 405 응답의 CSP 단정이 포함된다.

## Spec Change Log

- 2026-10-17 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap) — intent_gap·bad_spec 0, patch 10, defer 1, reject 2.
  - **구현 이탈 기록:** Code Map은 루트 패키지 시험이면 가시성을 바꾸지 않아도 된다고 했으나 `AdTransitionController.TRANSITION`이 `private`이라 package-private으로 바꿨다.
  - **patch:** `AdAttemptRepository` 잠금 주석을 사실대로(각자 advisory → 같은 `lockWriteAccess`), 동의 컨트롤러 실패 로그 메시지의 접두사 분리와 500 WARN 시험, 대조 시험에 `TELEMETRY_PATH`와 발급 바이트 수(43자), `POST /ad-transition` 405를 보안 헤더 전체로 단정, `CLAUDE.md` G-4 문장(network 선정·SHOW 왕복·kill switch 임계 보정·정의 위치)과 실연동 게이트와의 구분·세션 시작 3번의 다음 일 확인처, B-5 시험이 표본 0건을 실제로 단정, 4.3 Change Log 줄의 위치·기록일, passive effect 근거.
  - **defer(159):** 전체 빌드에서만 나는 계획 종단 시험 간헐 실패 둘 — `PlanEndTest` 16건(`RESULT_TOO_RECENT`)과 patch 뒤 `PlanCompletionTest` 1건(작업 `FAILED`). 둘 다 단독·재실행에서 통과했고 이 chore가 닿지 않는 경로다.
  - **reject:** 이후 마이그레이션의 CHECK 재정의 대비(그런 마이그레이션이 없다), 익명 예외 클래스의 빈 단순 이름(형제 컨트롤러와 같은 관용).
  - **KEEP:** 동작 변경 없음, 번역은 Epic 4가 더한 주석만, 파일 대조 시험은 Spring·DB 없이.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` — expected: 통과.
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run format:check && npm --prefix frontend run test` — expected: 통과.
- `bash .github/scripts/test-ci-scripts.sh` — expected: 통과.
- `deploy/compose/verify-same-origin.sh` — expected: 0 종료.

## Suggested Review Order

**검증 공백(AI-3)**

- 프런트·백·SQL이 따로 든 참조 모양과 engagement 경로 넷을 파일로 대조
  [`AdSharedValuesTest.java`](../../backend/src/test/java/com/bokrilog/AdSharedValuesTest.java)

- 소비 단계의 옛 정책 버전 — SKIP·SHOW 표지 없음·표본 0건과 대조군
  [`AdTransitionTest.java`](../../backend/src/test/java/com/bokrilog/AdTransitionTest.java)

- `POST /ad-transition` 405도 광고 CSP와 보안 헤더 전체를 받는다
  [`verify-same-origin.sh`](../../deploy/compose/verify-same-origin.sh)

**문서·주석 정합(AI-2)**

- 500 로그를 WARN·단순 이름으로, 요약 줄과 접두사 분리
  [`AdConsentController.java`](../../backend/src/main/java/com/bokrilog/AdConsentController.java)

- 그 WARN 줄을 지키는 500 시험
  [`AdConsentTest.java`](../../backend/src/test/java/com/bokrilog/AdConsentTest.java)

- 잠금 주석을 실제 직렬화(각자 advisory → 같은 생명주기 행)에 맞춤
  [`AdAttemptRepository.java`](../../backend/src/main/java/com/bokrilog/engagement/internal/AdAttemptRepository.java)

- G-4 미검증 문장, 세션 시작 3번, 복원 erase 순서
  [`CLAUDE.md`](../../CLAUDE.md)

- Epic 4가 더한 영어 주석의 한국어화
  [`useDashboardSync.ts`](../../frontend/src/session/useDashboardSync.ts)
