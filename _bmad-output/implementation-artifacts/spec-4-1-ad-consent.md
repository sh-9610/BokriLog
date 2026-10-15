---
title: 'Story 4.1 — 문맥형 광고 동의를 선택하고 철회하기'
type: 'feature'
created: '2026-10-15'
status: 'done'
review_loop_iteration: 0
baseline_commit: '381c52c8d50a222b920edad7edfee3e13a0d5121'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 광고 동의를 담을 자리가 없다. 동의가 없으면 Epic 4의 나머지(4.2 자격·4.3 광고 시도)가 "명시적 동의 전 0건"을 판정할 근거가 없다. `ARCHITECTURE-SPINE.md`가 예고한 여덟 번째 모듈 `engagement`도 아직 패키지가 없다(`epics.md` Story 4.1 AC-1~5).

**Approach:**
- 새 모듈 `engagement`가 광고 동의를 소유한다. 동의는 사용자별 추가 전용 결정 행(정책 버전·선택·DB 시각)이고, 현재 상태는 최신 행에서 읽는다.
- 루트 컨트롤러가 `GET`·`POST /api/engagement/ad-consent`를 연다. `POST`는 CSRF·`Origin`·멱등 키를 요구하고, 쓰기 트랜잭션 안에서 `AccountAccessApi.lockWriteAccess`로 삭제 상태를 다시 본다.
- 설정 화면에 광고 동의 영역을 더한다. 데이터 흐름을 설명하고 동의·비동의·철회를 버튼으로 받는다.
- 4.1은 광고 SDK·외부 요청·CSP 변경·브라우저 저장소를 하나도 들이지 않는다. 동의해도 제3자 요청은 0건이다. 광고 시도는 4.3이 맡는다.

## Boundaries & Constraints

**Always:**
- **정책 버전.** 현재 값은 `AD-CONSENT-1.0` 하나이고 서버 상수다.
  - 상태는 셋이다: `UNDECIDED`(행 없음, 또는 최신 행의 정책 버전이 현재와 다름), `GRANTED`, `DENIED`.
  - 최신 행의 버전이 옛 버전이면 `UNDECIDED`다. 정책이 바뀌면 다시 묻고, 자동 재동의는 없다.
  - 4.2가 판정할 "동의 유효"는 `GRANTED`이면서 현재 버전인 것 하나다.
- **철회.** 철회는 `DENIED` 행을 하나 더하는 것이다. 다음 판독부터 즉시 적용된다.
- **변경 요청.** 본문은 `{"choice":"GRANTED"|"DENIED","policyVersion":"AD-CONSENT-1.0"}`이다.
  - `policyVersion`이 현재와 다르면 409 `AD_CONSENT_POLICY_CHANGED`이고 아무것도 쓰지 않는다. 사용자가 본 설명과 저장되는 정책이 어긋나지 않게 하려는 것이다.
  - 현재 상태와 같은 선택이면 새 행 없이 200으로 현재 상태를 돌려준다.
- **멱등.** 키 규칙·헤더·code는 `IdempotencyKey`를 따른다. 키 digest와 요청 digest를 행에 저장한다.
  - 같은 키·같은 본문은 replay 200, 다른 본문은 409 `IDEMPOTENCY_CONFLICT`다.
- **쓰기 순서.** 한 트랜잭션에서 사용자 advisory 잠금 → `lockWriteAccess`(거짓이면 공통 404, 쓰지 않음) → 멱등 판정 → INSERT 순서로 한다. 시각의 권위는 DB 시각이다.
- **접근 판정.** 비인증은 401, 계정 접근 거부·삭제 시작은 3.6과 같은 공통 404다. 판정은 `SENSITIVE`로 한다.
- **삭제.** `EngagementDeletionApi.eraseConsents(userId)`(멱등, 지운 행 수)와 `remainingRowsOf`를 둔다. `AccountErasure`가 같은 트랜잭션에서 부른다.
- **식별 경계.** 4.1의 표는 전부 사용자 귀속이고 식별 없는 집계는 없다.
- **로그.** 로그는 단계·결과 code만 남긴다. 사용자 ID·선택값·키는 싣지 않는다.
- **화면 카피.** `content/`가 소유하고 다음을 설명한다.
  - 공급자 `Google Ad Manager(Google Publisher Tag)`
  - 비개인화 문맥형 범위
  - 전달될 수 있는 것: IP·UA·쿠키/저장소·URL·제목·동의/공급자 식별자
  - 전달하지 않는 것: 금융·계획·신뢰 상태·사용자 데이터
  - 거부해도 연결·동기화·대시보드가 똑같이 동작한다는 문장
- **화면 동작.**
  - 변경 중에는 이 영역의 버튼만 `aria-disabled`로 잠근다.
  - 저장 실패·네트워크 실패·시간 초과면 이전 상태를 그대로 보이고 성공을 공지하지 않는다.
  - 결과 공지는 화면의 기존 live region 하나로 한다.

**Ask First:**
- 감사 원장에 동의 변경을 기록하거나 재인증을 요구하는 것. 지금 결정은 둘 다 하지 않는 것이다 — ERD의 민감 command 목록에 동의가 없다.
- 공급자 상태(`DISABLED`)를 동의 화면이나 API에 드러내는 것.

**Never:**
- 광고 SDK·GPT load, preconnect, 제3자 요청, CSP 확장, 쿠키·브라우저 저장소 쓰기. 예외 범위는 4.3 SPEC이 정한다.
- 동의 여부로 연결·동기화·대시보드 동작을 바꾸는 것.
- 감사 원장 행, 재인증, 빈도 제한·광고 자격 판정(4.2), `ADVERTISING` 운영 제한 판독(4.2·4.4).
- 클라이언트가 보낸 사용자 ID·시각을 믿는 것.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 첫 조회 | 행 없음 | 200 `{state:"UNDECIDED", policyVersion:"AD-CONSENT-1.0", decidedAt:null}` | N/A |
| 동의 | `UNDECIDED` → `GRANTED` | 행 1개, 200 `GRANTED`·`decidedAt` | N/A |
| 철회 | `GRANTED` → `DENIED` | 행 1개 추가, 다음 GET이 `DENIED` | N/A |
| 같은 선택 | `GRANTED`에서 다시 `GRANTED`(새 키) | 행 추가 없음, 200 `GRANTED` | N/A |
| 옛 정책 행 | 최신 행 버전 ≠ 현재 | GET `UNDECIDED` | N/A |
| 정책 불일치 | 본문 `policyVersion` 옛 값 | 409 `AD_CONSENT_POLICY_CHANGED` | 쓰지 않음 |
| 재전송 | 같은 키·같은 본문 | 200, 행 추가 없음 | 다른 본문이면 409 `IDEMPOTENCY_CONFLICT` |
| 형식 오류 | 키 없음·형식 위반 / choice 누락 | 422 `IDEMPOTENCY_KEY_REQUIRED` / 400 | 쓰지 않음 |
| CSRF·Origin | 토큰 없음·교차 출처 | 403 | 쓰지 않음 |
| 삭제 경합 | 접근 판정 뒤 삭제 커밋 | `lockWriteAccess` 거짓 → 404 | 행이 다시 생기지 않음 |
| 동시 변경 | 두 탭이 서로 다른 선택 | 직렬화, 둘 다 200, 최종은 나중 커밋 | N/A |
| 화면 실패 | POST 5xx·네트워크 실패 | 이전 상태 유지, 실패 문장 | 자동 재시도 없음 |

</frozen-after-approval>

## Code Map

**백엔드** (`backend/src/main/java/com/bokrilog/`)
- `operations/SupportInquiryApi.java`, `operations/internal/SupportInquiryService.java`·`SupportInquiryRepository.java`(`lockUser` = `pg_advisory_xact_lock`) -- 공개 API·sealed 결과·멱등 digest의 선례.
  - 3.6은 `lockWriteAccess`를 부르지 않는다. 여기서는 따르지 않는다.
- `SupportInquiryController.java` -- 루트 컨트롤러 선례.
  - `allowedUser()`(:265)·`notFound()`, 거절 code 상수(:58-67), `CacheControl.noStore()`, 단계 로그(:300)
- `identity/AccountAccessApi.java:35` `lockWriteAccess` -- 쓰기 트랜잭션 안 생명주기 잠금.
  - 사용 선례: `plan/internal/PlanConditionService.java:161-162`
- `AuthSecurityConfig.java` -- 상수 `SUPPORT_PATH`(:275)와 술어 `isSupportPath`(:1316)를 본떠 `ENGAGEMENT_PATH`·`isEngagementPath`를 만든다.
  - 그 술어를 `isProtectedRequest`(:1246-1247)와 `requiresAuthentication`(:1263-1264)에 **둘 다** 더한다.
- `sync/IdempotencyKey.java` -- `HEADER`·`isValid`·`REQUIRED_CODE`. 메시지는 `ApiErrorHandler.MESSAGE_IDEMPOTENCY_KEY_REQUIRED`.
- `AccountErasure.java:58-59, 89-113` -- 생성자·`run()`에 erase 호출 추가. javadoc 단계 목록(:19-25)도 맞춘다.
- `RestoreInvariants.java:60-62, 78-80` -- 잔여 행 합산에 engagement 추가.
- `operations/internal/DeletionResidueRepository.java:16-21` -- `remainingRowsOf`의 카탈로그 쿼리 선례.
- `operations/RestrictionFeature.java:23` -- `ADVERTISING`. 4.1은 읽기만 한다(판독은 4.2).
- `backend/src/main/resources/db/migration/` -- 최대 V25. 새 파일은 V26이다.
- 메트릭 `uri`는 `TelemetryConfig`가 자동 수집하므로 allowlist 행은 필요 없다.

**백엔드 시험** (`backend/src/test/java/com/bokrilog/`)
- `ModuleBoundaryTest.java:49-50` -- `MODULES`에 `"engagement"`를 더한다.
- `AuthFlowTestBase.java:214-237` -- `truncateStore`에 새 표를 더한다.
- `AccountErasureTest.java:133` -- `new AccountErasure(...)` 인자를 더한다.
- `RestoreApplyTest.java:277, 334-361` -- 모듈별 합산과 `engagement_` 규칙 행을 더한다.
- `SupportInquiryTest`·`OperatorTestBase` -- 시험 선례와 helper.
  - helper: `signedIn`·`key`·`headers`·`sendJson`·`get`·`accountDeletion()`

**프런트엔드** (`frontend/src/`)
- `screens/SettingsScreen.tsx` -- 섹션 배치(Bybit 연결 :570-753 뒤에 새 영역), live region(:392), `lastFlow`(:167)·`onAction`, `busy`(:313).
- `components/SupportInquiryPanel.tsx`·`session/useSupportInquiries.ts` -- 가장 가까운 선례.
  - 하위 패널은 live region 없음, `locked`(:163)
  - GET: sequence·timeout·`refreshFailed`
  - POST: CSRF·멱등 키·401 만료 이동·`unanswered`
- `content/support.ts`, `content/auth.ts:941` -- 카피 모듈·`settingsCopyStrings` 규약. 금지어는 `content/copyRules.ts`.
- `test/setup.ts:524-604` -- 기본 응답과 responders. 설정 화면이 항상 GET하면 기본 응답이 필요하다.
- `e2e/entry-screen.spec.ts:239`, `e2e/support-inquiry.spec.ts` -- 외부 요청 0건 수집과 axe·320px 선례.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/resources/db/migration/V26__engagement_ad_consent.sql` -- `engagement_ad_consent_decision` 표를 만든다.
  - 열: `id`, `user_id`, `policy_version`, `choice`(CHECK `GRANTED`/`DENIED`), `decided_at` DEFAULT DB 시각, `idempotency_key_digest`, `request_digest`
  - 유일 색인 `(user_id, idempotency_key_digest)`, 조회 색인 `(user_id, decided_at DESC, id DESC)`
- [x] `backend/src/main/java/com/bokrilog/engagement/` -- 모듈을 만든다.
  - `package-info.java`
  - 공개 API `AdConsentApi`: `current(UUID)`, `decide(UUID, String key, Choice, String policyVersion)` → sealed `Decision { Saved(view, replayed) | PolicyChanged | Conflict | Denied }`, `POLICY_VERSION` 상수
  - 공개 API `EngagementDeletionApi`
  - `internal/`에 서비스·리포지토리(`JdbcClient`)
- [x] `backend/src/main/java/com/bokrilog/AdConsentController.java`·`AuthSecurityConfig.java` -- 경로 등록과 응답 매핑. 오류 본문은 공통 모양이다.
- [x] `AccountErasure.java`·`RestoreInvariants.java` -- erase와 잔여 행 검사를 연결한다.
- [x] `backend/src/test/.../AdConsentTest.java`와 기존 시험 넷(위 Code Map) -- I/O 매트릭스 전 행을 시험한다. 추가로 다음을 단정한다.
  - erase는 그 사용자 행만 지우고 멱등이다.
  - 로그(`LogLines`)에 사용자 ID·선택값이 없다.
- [x] `frontend/src/content/adConsent.ts` -- 영역 id·제목, 데이터 흐름 설명, 상태·버튼·결과 문장. 금지어 검사를 통과해야 한다.
- [x] `frontend/src/session/useAdConsent.ts` -- GET·POST 훅. 선례의 timeout·sequence·키 유지·401 이동을 따른다.
- [x] `frontend/src/components/AdConsentPanel.tsx`(+`.module.css`) -- 상태 문장과 상태별 버튼.
  - `UNDECIDED`: 동의·동의하지 않음
  - `GRANTED`: 동의 철회
  - `DENIED`: 광고 동의
- [x] `frontend/src/screens/SettingsScreen.tsx`·`test/setup.ts` -- 영역 배치, `lastFlow: 'adConsent'`, 기본 GET 응답.
- [x] `frontend/src/components/AdConsentPanel.test.tsx`, `frontend/e2e/ad-consent.spec.ts` -- 다음을 시험한다.
  - 상태 전이, 실패 시 이전 상태 유지, 잠금, live region 1개
  - 동의 뒤에도 외부 origin·저장소 요청 0건
  - axe 위반 0건, 키보드만으로 선택·철회, 320px

**Acceptance Criteria:**
- Given 비동의(`UNDECIDED`·`DENIED`) 사용자, when 연결·동기화·대시보드를 쓸 때, then 응답과 화면은 동의 사용자와 같다. 동의 API는 그 경로들에서 호출되지 않는다.
- Given 어떤 동의 상태든, when 설정 화면을 열고 동의를 바꿀 때, then 브라우저의 요청은 동일 출처 `/api/` 뿐이다. 쿠키·`localStorage`·`sessionStorage`·IndexedDB 쓰기는 0건이다.
- Given 동의 행이 있는 사용자, when 계정 삭제가 실행될 때, then `remainingRowsOf`는 0이다. 삭제 시작 뒤의 `POST`는 404이고 행을 만들지 않는다.

## Spec Change Log

- 2026-10-15 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap) — intent_gap·bad_spec 0, patch 15, defer 2(결정 행 빈도 제한·동의 이력 보존 기한 → `deferred-work.md` 147·148), 나머지 reject. patch는 다음을 고쳤다.
  - 백엔드: 최신 판독을 `id DESC`로 바꿨다(DB 시계 역행 내성). `current()`의 null을 예외로 올렸다.
  - 시험 추가: advisory 잠금 선점으로 직렬화를 결정적으로 보는 시험, 같은 선택 동시 요청 1행, engagement 실패 주입 롤백, `engagement_` SQL 경계, `eraseConsents` 호출자 고정, 화면·서버 정책 버전 대조.
  - 프런트엔드: 401은 이동 하나만 한다. 변경 403·404는 상태를 지운다. 다른 탭이 바꾼 재전송은 성공으로 말하지 않는다. 조회 성공 뒤 낡은 키를 비운다. 늦은 성공은 초점을 뺏지 않는다. 409 뒤 초점 착지를 정했다. 다시 읽기 실패 전용 문장을 두었다. 재동의 버튼 이름을 '다시 동의'로 바꿨다. 다시 확인 버튼도 잠근다.
  - 문서: `DEVELOPMENT.md` setup 설명.
  - 미인증 `POST`가 401이 아니라 403인 것은 CSRF 필터가 먼저 거르는 기존 계약(반복 로그아웃 403과 같은 경로)이라 유지했다. 미인증 조회는 401이다.
  - KEEP: 추가 전용 결정 행, 사용자 잠금 → `lockWriteAccess` → 멱등 → INSERT 순서, 정책 버전 409, 화면 단일 live region.

## Design Notes

- **추가 전용 결정 행.** 최신 하나만 갱신하지 않고 결정을 쌓는다. 그래야 "언제 어떤 정책에 동의했나"가 남는다. 멱등 기록도 행 자체가 되어 별도 표가 없다(3.6 선례). 삭제는 비식별이 아니라 행 삭제다.
- **정책 버전 대조.** 옛 설명을 본 탭이 새 정책에 동의를 저장하면 "명시적 동의"가 깨진다. 그래서 요청에 버전을 싣게 하고 409로 거절한다. 화면은 409를 받으면 상태를 다시 읽는다.
- **브라우저 경계.** 4.1은 CSP·저장소 예외를 만들지 않는다. GPT를 load하는 예외는 중립 route 한정으로 4.3이 승인한다.
- **카피 확정.** 동의 문구는 외부 게이트 G-4(개인정보 검토)가 확정한다. 여기서 쓰는 문구는 출시 전 검토 대상이다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 성공. 다음이 통과한다.
  - `AdConsentTest`·`ModuleBoundaryTest`·`RestoreApplyTest`·`AccountErasureTest`
  - `-Werror`
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run test` -- expected: 모두 통과.
- `npm --prefix frontend run test:e2e` -- expected: `ad-consent.spec.ts`를 포함해 통과.
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 통과.

## Suggested Review Order

**결정 쓰기의 원자성 (진입점)**

- 정책 대조 → 사용자 잠금 → 생명주기 잠금 → 멱등 → INSERT 한 트랜잭션
  [`AdConsentService.java:79`](../../backend/src/main/java/com/bokrilog/engagement/internal/AdConsentService.java#L79)

- 삭제 시작 계정은 트랜잭션 안에서 다시 거절해 행이 되살아나지 않는다
  [`AdConsentService.java:88`](../../backend/src/main/java/com/bokrilog/engagement/internal/AdConsentService.java#L88)

- 최신 상태는 잠금 아래 순번으로만 정렬한다(시계 역행 내성)
  [`AdConsentRepository.java:60`](../../backend/src/main/java/com/bokrilog/engagement/internal/AdConsentRepository.java#L60)

- 현재 상태 판독 — 옛 정책 행은 미결정으로 접는다
  [`AdConsentService.java:53`](../../backend/src/main/java/com/bokrilog/engagement/internal/AdConsentService.java#L53)

**공개 표면과 스키마**

- 모듈 공개 API와 정책 버전 상수
  [`AdConsentApi.java:23`](../../backend/src/main/java/com/bokrilog/engagement/AdConsentApi.java#L23)

- 추가 전용 결정 표와 키 유일·순번 색인
  [`V26__engagement_ad_consent.sql:26`](../../backend/src/main/resources/db/migration/V26__engagement_ad_consent.sql#L26)

- 루트 컨트롤러 — 접근 판정·키 검증·응답 매핑
  [`AdConsentController.java:101`](../../backend/src/main/java/com/bokrilog/AdConsentController.java#L101)

- 인가와 CSRF·Origin이 같은 술어를 본다
  [`AuthSecurityConfig.java:1332`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L1332)

**삭제 연결**

- 계정 삭제 트랜잭션의 engagement 단계
  [`AccountErasure.java:107`](../../backend/src/main/java/com/bokrilog/AccountErasure.java#L107)

- 삭제 port와 잔여 행 검사
  [`EngagementDeletionApi.java:15`](../../backend/src/main/java/com/bokrilog/engagement/EngagementDeletionApi.java#L15)

- 복원 불변식 합산에 engagement 포함
  [`RestoreInvariants.java:60`](../../backend/src/main/java/com/bokrilog/RestoreInvariants.java#L60)

**설정 화면**

- 조회·변경 상태 기계 — 실패는 이전 상태 유지, 401은 이동 하나
  [`useAdConsent.ts:105`](../../frontend/src/session/useAdConsent.ts#L105)

- 다른 탭이 바꾼 재전송은 성공으로 말하지 않는다
  [`useAdConsent.ts:237`](../../frontend/src/session/useAdConsent.ts#L237)

- 상태별 버튼과 초점 규칙(늦은 성공은 초점을 뺏지 않음)
  [`AdConsentPanel.tsx:50`](../../frontend/src/components/AdConsentPanel.tsx#L50)

- 설정 화면 배치와 단일 live region 연결
  [`SettingsScreen.tsx:765`](../../frontend/src/screens/SettingsScreen.tsx#L765)

- 화면이 본 설명의 정책 버전 — 서버 상수와 시험이 대조
  [`adConsent.ts:20`](../../frontend/src/content/adConsent.ts#L20)

**시험**

- 잠금 선점으로 직렬화를 결정적으로 본다
  [`AdConsentTest.java:312`](../../backend/src/test/java/com/bokrilog/AdConsentTest.java#L312)

- 접근 판정 뒤 삭제 커밋 경합
  [`AdConsentTest.java:254`](../../backend/src/test/java/com/bokrilog/AdConsentTest.java#L254)

- `eraseConsents` 호출자와 `engagement_` SQL 경계
  [`ModuleBoundaryTest.java:387`](../../backend/src/test/java/com/bokrilog/ModuleBoundaryTest.java#L387)

- 화면·서버 정책 버전 대조
  [`AdConsentPolicyVersionTest.java`](../../backend/src/test/java/com/bokrilog/engagement/AdConsentPolicyVersionTest.java)

- 키보드만으로 동의·철회, 외부 요청·저장소 쓰기 0건(주입 origin 전용)
  [`ad-consent.spec.ts:71`](../../frontend/e2e/ad-consent.spec.ts#L71)
