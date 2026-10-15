---
title: 'Story 4.3 — 금융정보 없는 중립 화면에서 닫을 수 있는 광고 시도하기'
type: 'feature'
created: '2026-10-15'
status: 'done'
baseline_commit: '21345512227748fdc9a71674ec2fdb5db40e2da2'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-2-ad-eligibility.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 4.2가 수동 동기화 성공 결과에 광고 자격(ELIGIBLE·`transition`)을 판정하지만, 그 판정을 쓰는 화면이 없다. 중립 전환 route, `AD-GPT-1.0` adapter, iframe 밖 닫기가 없고, 같은 `transition`을 몇 번이고 다시 쓸 수 있다(`deferred-work.md` 149). `epics.md` Story 4.3 AC-1~5가 요구한다.

**Approach:**
- 대시보드는 자기 화면에서 시작한 수동 동기화가 성공하면 4.2 판정을 부른다. ELIGIBLE일 때만 `/ad-transition?t=<transition>`로 문서 이동하고, 그 밖에는 지금처럼 제자리에 남는다.
- 중립 화면은 `t`를 메모리에 옮기고 주소에서 지운다. 그다음 서버에 `transition`을 **소비(claim)**시키고, `SHOW`일 때만 GPT web interstitial을 load한다. 광고는 `결과 보기` anchor 클릭이 띄운다.
- `광고 닫기`, 그리고 SHOW가 아닌 모든 결과는 광고 없이 `/dashboard`로 간다.
- GPT를 허용하는 CSP는 Nginx가 `/ad-transition` 한 경로에만 낸다. 공급자는 기본 꺼짐이다.

## Boundaries & Constraints

**Always:**
- **판정 호출.** 다음 조건을 모두 만족할 때만 `POST /api/engagement/ad-eligibility {syncJobId}`를 jobId당 한 번 부른다.
  - 따라가던 job이 이 화면이 접수한 `MANUAL` job이다(`origin:'here'`, 끝난 job의 `trigger==='MANUAL'`).
  - `succeeded`로 끝났다. 끝난 job의 jobId를 쓴다.
  - ENTRY·VISIBILITY·JOINED 자동 job·실패·검토 필요는 부르지 않는다.
  - 응답 실패·SKIPPED·404·시간초과는 아무것도 바꾸지 않는다. 제자리 결과·포커스 규칙은 그대로다.
- **이동.** ELIGIBLE이면 `navigateTo('/ad-transition?t=' + transition)`이다.
- **중립 화면 진입.**
  - 마운트 직후 `t`를 읽고, `navigation.ts`의 새 `replaceAddress('/ad-transition')`(`history.replaceState`)로 쿼리를 지운다.
  - `t`가 없거나 형식(`^[A-Za-z0-9_-]{43}$`)이 아니면 요청 없이 `replaceLocation('/dashboard')`이다. 새로고침·뒤로 가기 재진입이 여기에 해당한다.
- **소비 API.** `POST /api/engagement/ad-transition {transition}`이다.
  - 한 트랜잭션에서 사용자 잠금(4.2와 같은 접두사) → `lockWriteAccess`(거짓이면 404) → `(user, transition_ref)` 행 `FOR UPDATE` 순서로 한다. 남의 것·없는 것은 같은 404다.
  - 행을 찾으면 판정한다.
    - 이미 소비됐으면 `CONSUMED`다.
    - 결정 뒤 120초를 넘었으면 `EXPIRED`다(DB 시각).
    - 그 밖에는 4.2 관문을 같은 순서로 다시 보고 `CONSENT_REQUIRED`·`RESTRICTED`·`PROVIDER_DISABLED`다.
  - SKIP이든 SHOW든 `transition_consumed_at = statement_timestamp()`를 쓴다(이미 소비된 행은 그대로 둔다).
  - 응답은 200 `{outcome:'SHOW', adUnitPath}` 또는 `{outcome:'SKIP', reason}`이다. 형식 오류는 400, 판독 오류는 500(fail-closed)이다. 모두 no-store다.
- **공급자 구성.** `bokrilog.engagement.ad-unit-path`(`BOKRILOG_AD_UNIT_PATH`, 기본 빈 값)를 더한다.
  - 공급자 활성은 `ad-provider-enabled && 단위 경로가 ^/[0-9]+(/[A-Za-z0-9_.-]+)+$`이다.
  - 아니면 4.2 판정도 소비도 `PROVIDER_DISABLED`다. 기동은 거부하지 않는다.
- **중립 화면.** 금융값·계획값·신뢰 상태·사용자/result 식별이 DOM·URL·`document.title`·slot·targeting 어디에도 없다.
  - DOM 순서: `광고` 표지와 네이티브 `<button>` `광고 닫기`가 첫 조작이고, 최초 포커스는 layout effect로 준다.
  - 그 뒤에 `결과를 준비했습니다` 제목, "광고를 건너뛰어도 최신 결과를 확인할 수 있습니다." 문장, `<a href="/dashboard">결과 보기</a>`가 온다.
  - `복리 계획 비교`·비추천 고지를 인접 배치한다.
  - 카드 입체 언어는 쓰지 않고, 토큰만 쓴다. 카피는 `content/adTransition.ts`가 소유한다.
  - 결과 보기 외의 모든 anchor(skip link 등)에는 `data-google-interstitial="false"`를 둔다.
  - `광고 닫기`는 `replaceLocation('/dashboard')`다. 소비 응답 전에도 동작한다.
- **adapter.** `frontend/src/ads/gptAdapter.ts`만 `googletag`를 안다. SHOW일 때만 부른다.
  - `https://securepubads.g.doubleclick.net/tag/js/gpt.js`를 script 요소로 load한다.
  - `pubads().setPrivacySettings({ nonPersonalizedAds: true })`를 건다.
  - `defineOutOfPageSlot(adUnitPath, INTERSTITIAL)`를 정의하고, targeting은 없다. 그다음 `enableServices`·`display`를 부른다.
  - slot이 `null`이거나 script 오류·예외가 나면 `replaceLocation('/dashboard')`다.
- **CSP.** `deploy/nginx/bokrilog.conf`에 `map $request_path $content_security_policy`를 두고, 기존 server 수준 `add_header` 한 줄이 그 변수를 낸다. 하위 location에 `add_header`를 두지 않는다.
  - 기본값은 지금 CSP 전문 그대로다.
  - `/ad-transition`만 아래 Design Notes의 값을 쓴다.
  - `Referrer-Policy: no-referrer`와 나머지 보안 헤더는 경로와 무관하게 같다.
- **경로.**
  - `/ad-transition`은 화면 경로지만 복귀 목적지가 아니다(`/plan-conditions` 선례). App.tsx 맵·Nginx 허용 목록·검증 스크립트에 더하고, 서버·세션 목적지 맵·`RETURN_DESTINATIONS`는 바꾸지 않는다.
  - 화면은 `useSessionGate('settings')` 관용으로 CSRF를 얻는다. 401·403이면 `/dashboard`로 replace한다.
- **스키마.** `V28__engagement_ad_transition.sql`이 `transition_consumed_at timestamptz NULL`을 더한다. CHECK는 소비가 ELIGIBLE 행에만 있다는 것이다. 삭제는 기존 `eraseAdAttempts`가 덮는다.
- **로그.** 단계·결과 code만 남긴다. transition 값은 응답 외 어디에도 남기지 않는다.

**Ask First:**
- `/ad-transition` CSP에 Design Notes 목록 밖의 origin·`'unsafe-inline'`·`'unsafe-eval'`을 더하는 것.
- 소비 창 120초나 판정 호출 조건을 바꾸는 것.
- 새 런타임 의존을 더하는 것(`@types/google-publisher-tag` devDependency는 허용).

**Never:**
- 다음은 4.4 몫이다: GPT load 시간초과 3초, 미채움·render·접근성 실패 감지, 늦은 callback 처리, 광고 계측(요청·노출·닫기), Web Vitals kill switch.
- 다음을 만들지 않는다: 자동 재요청, 다른 공급자 fallback, countdown 닫기, 보상형 광고·동기화, 새로고침이나 popup으로 광고를 유도하는 것.
- 대시보드·다른 경로에서 GPT를 load하거나 CSP를 넓히는 것. `transition`을 브라우저 저장소에 두는 것.
- 4.2의 판정 계약(재전송·관문·cap)을 바꾸는 것. sync·plan·dashboard 모듈이 engagement를 import하는 것.
- 실제 소재 접근성·request field·Google 계정 승인을 시험으로 대신해 출시 준비 완료로 적는 것(G-4).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 자격 → 광고 | 수동 성공, ELIGIBLE, 첫 소비 | 이동 → 주소 `/ad-transition` → 200 SHOW → adapter load. 결과 보기 클릭 → `/dashboard` | N/A |
| 자격 없음 | SKIPPED·404·판정 실패 | 이동 없음. 제자리 결과·heading 포커스 | 오류 표시 없음 |
| 광고 닫기 | SHOW 화면에서 닫기 | interstitial 없이 `/dashboard` | N/A |
| 재소비 | 같은 `t`를 다시 소비 | 200 SKIP `CONSUMED` → `/dashboard` | N/A |
| 만료 | 결정 뒤 121초 | SKIP `EXPIRED`, 소비 기록 | N/A |
| 관문 재확인 | 판정 뒤 철회·ADVERTISING 제한·공급자 꺼짐 | 각 사유로 SKIP, adapter 미호출 | 제한 판독 오류는 500 → `/dashboard` |
| 재진입 | `t` 없음·형식 오류 | 요청 0건, `/dashboard`로 replace | N/A |
| 남의 것 | 다른 사용자 `transition` | 404, 소비 없음 | N/A |
| 삭제 경합 | 삭제 시작 계정 | 404 | N/A |
| slot 없음 | adapter가 null slot·script 오류 | `/dashboard`로 replace | 재시도 없음 |

</frozen-after-approval>

## Code Map

**프런트엔드** (`frontend/src/`)
- `App.tsx:63-91` — 경로 if 체인. `/plan-conditions` 분기 선례대로 더한다. 시험 `App.test.tsx:49-300`.
- `session/navigation.ts:18,30` — 유일한 전환 모듈이다. `replaceAddress`를 여기에 더하고, 시험은 `vi.mock`한다.
- `session/useDashboardSync.ts` — 판정 호출 자리.
  - 접수 653-675, `setFollowed` 736-758, `judge` 359-472(성공 458-470, 수동 heading 포커스 465-469)
  - 따라가던 job이 바뀌는 지점 444-455. 끝난 `latest.jobId`를 쓴다.
  - `SyncJob.trigger`는 `useDashboard.ts:249`에 있다.
- `screens/PlanConditionScreen.tsx:17-75` — 가장 단순한 화면 틀(gate·skip link·`main tabIndex=-1`·단일 status). 대시보드 링크는 :36이다.
- `content/adConsent.ts`(경로 상수·카피 선례), `content/copyRules.ts:9`(금지 카피), `content/entry.ts:72-73`(제목·고지).
- `content/auth.ts:132` `CSRF_HEADER`. 공통 POST helper는 없으므로 `useAdConsent.ts:206-217` 관용을 따른다. 시간초과는 `SESSION_REQUEST_TIMEOUT_MS`다.
- `test/setup.ts` — fetch stub(`responders` 613-643)이다. XHR·sendBeacon·저장소를 감시한다. stub 선례는 `stubAdConsent` 425, `stubDashboardSync` 151이다.

**Nginx·검증**
- `deploy/nginx/bokrilog.conf` — `$request_path` map 32-36, add_header 블록 116-158(CSP 142, Referrer 144), 허용 목록 238(주석 190).
- `deploy/compose/verify-same-origin.sh` — `expected_csp` 620, `assert_security_headers` 651-660, 경로 집합 2820-2864(`ALLOWED_PATHS`·`REJECTED_PATHS`).

**백엔드** (`backend/src/main/java/com/bokrilog/`)
- `engagement/internal/AdEligibilityService.java:76-141` — 트랜잭션·`gate`. 소비가 `gate`를 재사용하도록 꺼낸다.
- `engagement/internal/AdAttemptRepository.java` — `lockUser` 35, `now` 64. 소비용 SELECT FOR UPDATE·UPDATE를 더한다.
- `engagement/internal/AdProviderState`·`EngagementProperties` — 공급자 활성 판단(시험 훅 `reinterpret`). `application.yml:243-244`.
- `AdEligibilityController.java` — 루트 컨트롤러 선례(PATH 49, 400 120-138, 404·500 103-113, no-store 140). 새 `AdTransitionController`.
- `AuthSecurityConfig.java:285` `ENGAGEMENT_PATH` — 인가·CSRF·Origin이 이미 덮는다.
- 마이그레이션 V27 CHECK(ref 43자 정규식) → 새 V28.

**시험**
- `AdEligibilityTest`, `engagement/internal/AdProviderTestHook`·`EngagementPropertiesBindingTest`, `ModuleBoundaryTest`(engagement API 의존 제한), `AuthFlowTestBase.truncateStore`.
- e2e: `entry-screen.spec.ts:239`(외부 요청 0건 관용), `ad-consent.spec.ts:71`(저장소 쓰기 0건). `page.route`는 쓰지 않는다.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/resources/db/migration/V28__engagement_ad_transition.sql`, `engagement/AdTransitionApi.java`(공개), `engagement/internal/AdTransitionService.java`, `AdAttemptRepository`·`AdEligibilityService`(관문 공유)·`EngagementProperties`·`AdProviderState`, `application.yml` — 소비 판정과 단위 경로.
- [x] `AdTransitionController.java`(루트) — 경로·400/404/500·no-store.
- [x] 백엔드 시험을 더한다.
  - `AdTransitionTest`: 매트릭스 서버 행 전부. 동시 소비 둘 중 하나만 SHOW.
  - 단위 경로 바인딩 시험, `ModuleBoundaryTest`, V28 CHECK.
- [x] `frontend/src/content/adTransition.ts`, `screens/AdTransitionScreen.tsx`(+`.module.css`), `ads/gptAdapter.ts`, `session/adTransition.ts`(판정·소비 요청), `session/navigation.ts`, `session/useDashboardSync.ts`, `App.tsx` — 판정 호출·중립 화면·adapter.
- [x] 프런트엔드 시험을 더한다. 중립 화면 시험은 adapter를 mock한다.
  - `AdTransitionScreen.test.tsx`: 첫 포커스 닫기, 주소 정리, `t` 없음, SKIP 사유, 401, null slot, anchor 속성, 금지 카피·식별 부재.
  - `useDashboardSync` 시험: 호출 조건 전 행.
  - `App.test.tsx`
- [x] `deploy/nginx/bokrilog.conf`, `deploy/compose/verify-same-origin.sh` — CSP map·허용 목록. 검증은 `/ad-transition` CSP 전문과 다른 모든 응답의 기존 CSP 전문을 단정한다.
- [x] `frontend/e2e/ad-transition.spec.ts` — 주입 origin에서 두 경우를 확인한다(외부 요청 0건·저장소 쓰기 0건).
  - `t` 없음·위조 `t`이면 `/dashboard`에 도착한다.
  - 기본 구성에서 수동 동기화가 성공하면 이동이 없다.

**Acceptance Criteria:**
- Given 기본 구성(공급자 꺼짐), when 수동 동기화가 성공할 때, then 화면은 제자리에서 갱신되고 제3자 요청이 없다.
- Given `/ad-transition`이 아닌 모든 경로의 응답, when 헤더를 읽을 때, then CSP 전문은 4.2 시점과 같다.
- Given 중립 화면 DOM, when 텍스트·속성·title을 훑을 때, then 숫자 금액·`%`·job/user UUID·`transition` 값이 없다.

## Spec Change Log

- 2026-10-15 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap) — intent_gap·bad_spec 0, patch 7, defer 3(`deferred-work.md` 153~155), 나머지 reject.
  - **defer 3건:** `/ad-transition` CSP 후보값 실측과 SHOW 브라우저 왕복(G-4), 광고 단위 경로 형식의 MCM 경로 수용, 검증 중 관측한 `ad-consent.spec.ts:71` 간헐 실패(5회 중 2회, 원인 미확정).
  - **patch — 판정 호출:** 후보를 이 화면의 MANUAL 접수가 ACCEPTED로 받은 job 그 자체로 좁혔다. 이전에는 `origin==='here'`만 봐서 자동 접수의 JOINED와 따라가기 이동이 판정을 일으킬 수 있었다(SPEC 위반). 대화상자가 열렸거나 새 접수가 진행 중이면 이동하지 않는다. csrf 토큰이 늦게 생겨도 한 번 묻는다.
  - **patch — 중립 화면:** `reauth-required`와 세션 확인 지연(`SESSION_REQUEST_TIMEOUT_MS`)에서도 결과로 간다. 닫기 버튼이 운영자 토큰 대신 중립 화면 토큰을 쓴다.
  - **patch — 기타:** 창 계산을 `interval '1 second' * :w`로 바꿨다. `?t=` 요청의 access log에 쿼리가 없다는 단정을 더했다.
  - **reject:** `//ad-transition` 같은 비정규 경로(클라이언트가 만들지 않고 결말은 fail-open), 뒤로 가기 이력(두 번째 뒤로 가기로 이전 화면), 광고 코드 lazy chunk(gpt.js는 SHOW에서만 load), 본문 상한 서술(4.2와 같은 관용), 표시되지 않은 시도의 cap 집계(4.2 계약: reservation이 곧 시도), 정확한 120초 경계 시험(DB 시각이라 같은 순간을 만들 수 없다).
  - **KEEP:** 쿼리 `t` → 주소 정리 → 소비 → adapter 순서, 소비의 잠금·`FOR UPDATE`·조건부 UPDATE 한 트랜잭션, `AdGate` 관문 공유, Nginx CSP map과 상위 `add_header` 한 곳, 기본 구성에서의 무변화.

## Design Notes

- **GPT의 브라우저 저장소 사용.** 저장소 쓰기 금지는 저장소 코드에 대한 규칙이다. GPT가 쓰는 쿠키·저장소는 4.1 동의가 설명한 범주("쿠키/저장소")이며, 동의 + 서버 자격 + 1회 소비를 통과한 `/ad-transition` 문서에서만 생긴다. 이것이 epic-4-context가 SPEC 승인을 요구한 중립 route 한정 예외다. 다른 경로의 저장소 0건 단정은 그대로다.

- **`t`를 쿼리로 넘기는 이유.** CSP를 경로에 묶으려면 중립 화면이 별도 문서여야 한다. 문서 경계 너머로 값을 옮길 수단은 URL뿐이다(저장소 금지).
  - 노출을 막는 장치는 넷이다. 쿼리는 GPT load 전에 지운다. access log는 쿼리를 남기지 않는다. `no-referrer`다. 값은 1회 소비·120초 수명이다.
  - 서버가 "최근 미소비 자격"을 고르는 방식은 4.2가 발급한 결정과의 binding을 잃는다.
- **소비가 149를 닫는다.** 4.2의 ELIGIBLE 재전송은 같은 `t`를 돌려주지만, 두 번째 소비는 `CONSUMED`라 노출이 다시 일어나지 않는다.
  - 150(후보 신선도)은 소비 창 120초로 충분하다. 오래된 job을 수동으로 판정해도 cap을 소비할 뿐이다. 그래서 판정 술어는 바꾸지 않는다.
- **`/ad-transition` CSP 값** — 공급자 문서 기준 후보다. 실제 request field 검사는 G-4다.
  ```
  default-src 'none'; script-src 'self' https://securepubads.g.doubleclick.net https://pagead2.googlesyndication.com https://tpc.googlesyndication.com; style-src 'self'; style-src-attr 'unsafe-inline'; img-src 'self' data: https://*.doubleclick.net https://*.googlesyndication.com https://*.gstatic.com; connect-src 'self' https://securepubads.g.doubleclick.net https://pagead2.googlesyndication.com; frame-src https://*.doubleclick.net https://tpc.googlesyndication.com; base-uri 'none'; form-action 'none'; frame-ancestors 'none'
  ```

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` — expected: 성공(`AdTransitionTest`·`AdEligibilityTest`·`ModuleBoundaryTest`, `-Werror`).
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run test` — expected: 통과.
- `deploy/compose/verify-same-origin.sh --keep-stack` 뒤 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 npm --prefix frontend run test:e2e:run` — expected: 0 종료, e2e 통과.
- `bash .github/scripts/test-ci-scripts.sh` — expected: 통과.

## Suggested Review Order

**소비 트랜잭션 (진입점)**

- 잠금 → 쓰기 접근 → 행 잠금 → CONSUMED·EXPIRED·관문 → 소비 시각, 한 트랜잭션
  [`AdTransitionService.java:46`](../../backend/src/main/java/com/bokrilog/engagement/internal/AdTransitionService.java#L46)

- 창 판정은 DB 시각, 행은 `FOR UPDATE`로 잡아 동시 소비 중 하나만 SHOW
  [`AdAttemptRepository.java:124`](../../backend/src/main/java/com/bokrilog/engagement/internal/AdAttemptRepository.java#L124)

- 4.2 판정과 소비가 같은 관문 한 벌(동의 → ADVERTISING → 공급자)을 쓴다
  [`AdGate.java:22`](../../backend/src/main/java/com/bokrilog/engagement/internal/AdGate.java#L22)

- 공급자 활성은 플래그와 단위 경로 형식이 함께일 때만
  [`AdProviderState.java:18`](../../backend/src/main/java/com/bokrilog/engagement/internal/AdProviderState.java#L18)

**판정 호출과 이동 (대시보드)**

- 이 화면이 ACCEPTED로 받은 수동 SYNC job의 성공만 후보
  [`useDashboardSync.ts:480`](../../frontend/src/session/useDashboardSync.ts#L480)

- 대화상자·새 접수 중이면 이동하지 않는다(fail-open)
  [`useDashboardSync.ts:840`](../../frontend/src/session/useDashboardSync.ts#L840)

**중립 화면과 adapter**

- 참조를 메모리로 옮기고 주소를 지운 뒤 소비, SHOW만 adapter
  [`AdTransitionScreen.tsx:29`](../../frontend/src/screens/AdTransitionScreen.tsx#L29)

- SHOW 뒤에만 GPT — 비개인화, targeting 없는 interstitial slot
  [`gptAdapter.ts:68`](../../frontend/src/ads/gptAdapter.ts#L68)

- 주소 정리도 유일한 전환 모듈에 둔다
  [`navigation.ts:41`](../../frontend/src/session/navigation.ts#L41)

- 판정·소비 요청은 모든 실패를 "광고 없음"으로 접는다
  [`adTransition.ts:73`](../../frontend/src/session/adTransition.ts#L73)

**경계: CSP와 경로**

- 경로별 CSP map — `/ad-transition`만 공급자 origin
  [`bokrilog.conf:101`](../../deploy/nginx/bokrilog.conf#L101)

- CSP 송출은 상위 `add_header` 한 줄 그대로
  [`bokrilog.conf:164`](../../deploy/nginx/bokrilog.conf#L164)

- 화면 경로 맵에 여섯 번째 경로(복귀 목적지 아님)
  [`App.tsx:80`](../../frontend/src/App.tsx#L80)

**스키마·구성·표면**

- 소비 시각 열과 ELIGIBLE 전용 CHECK
  [`V28__engagement_ad_transition.sql:18`](../../backend/src/main/resources/db/migration/V28__engagement_ad_transition.sql#L18)

- 루트 컨트롤러 — SHOW/SKIP 200, 400·404·500, no-store
  [`AdTransitionController.java:71`](../../backend/src/main/java/com/bokrilog/AdTransitionController.java#L71)

- 단위 경로 구성(기본 빈 값 = 꺼짐)
  [`application.yml:249`](../../backend/src/main/resources/application.yml#L249)

**시험**

- 동시 소비 하나만 SHOW, 매트릭스 서버 행 전부
  [`AdTransitionTest.java:252`](../../backend/src/test/java/com/bokrilog/AdTransitionTest.java#L252)

- 중립 화면 DOM에 금액·%·UUID·transition이 없다
  [`AdTransitionScreen.test.tsx:364`](../../frontend/src/screens/AdTransitionScreen.test.tsx#L364)

- 경로별 CSP 전문 단정
  [`verify-same-origin.sh:625`](../../deploy/compose/verify-same-origin.sh#L625)

- 주입 origin: 위조·없는 참조와 기본 구성의 무변화, 제3자 요청 0건
  [`ad-transition.spec.ts:129`](../../frontend/e2e/ad-transition.spec.ts#L129)
