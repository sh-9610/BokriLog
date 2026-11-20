---
title: '프런트엔드 동일 출처 POST·JSON 판독 공통 helper'
type: 'chore'
created: '2026-11-04'
status: 'done'
baseline_commit: 'c213b6c7738c2a84e73d547f286543079ad3f617'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 프런트엔드는 동일 출처 POST를 21곳(17개 파일)에서 각자 조립한다. 그래서 `credentials`·`cache`·`Accept` 설정과 CSRF 헤더 표기(상수 또는 `'X-CSRF-TOKEN'` 리터럴)가 사본마다 다르다. JSON 판독 helper도 사본이 여럿이다. `readJson`·`jsonOf`가 5벌, `objectOf`·`fieldsOf`가 5벌이다(Epic 4 회고 D-5, AI-8 `epic-4-retro-item-47`의 ②, deferred 166). Epic 5가 사용자 화면을 더하기 전에 정리하지 않으면 사본이 더 늘어난다.

**Approach:** `session/http.ts` 하나에 `readJson`·`objectOf`, JSON POST `postJson`, 그리고 `unlinkProvider.post`를 옮긴 `postNoContent`를 둔다. 모든 사본과 POST 조립 위치를 이 helper로 바꾼다. 상태 매핑·시한·결과 해석은 지금처럼 각 호출자가 맡는다.

## Boundaries & Constraints

**Always:**
- 요청 init 규칙은 하나다. `method: 'POST'`, `credentials: 'same-origin'`, `cache: 'no-store'`를 쓰고, 헤더는 평범한 객체 `{Accept: 'application/json', [CSRF_HEADER]: token}`에 본문이 있을 때만 `Content-Type: application/json`, 멱등 키를 받았을 때만 `Idempotency-Key`를 더한다. 그 결과 지금 `Accept`·`cache`가 없는 사본(components의 POST 일곱과 본문 없는 204 묶음 넷)에 이 둘이 생긴다. 이것은 의도한 정규화다. 서버 응답은 바뀌지 않는다.
- 본문은 helper가 `JSON.stringify` 한 번으로 만든다. 같은 객체에서 지금과 같은 문자열이 나온다.
- helper는 호출 시점의 전역 `fetch`를 부른다(`vi.stubGlobal` 시험이 있다). 시한(`signal`)은 호출자가 넘기고, 시한이 없는 곳(`useFirstSnapshot`)은 그대로 시한 없이 둔다.
- `postNoContent`의 계약은 `unlinkProvider.post`와 같다. 토큰이 `null`이면 보내지 않고, 시한은 `SESSION_REQUEST_TIMEOUT_MS`이며, 204만 참이고 예외는 거짓이다.
- 시험이 import하는 export(`parseAdConsent`·`parseInquiryList`·`parseReceipt`·`preparedHeadOf`, `useServiceNotices`의 export)는 유지한다. 기존 시험은 고치지 않고 통과해야 한다.
- 새 주석은 한국어로 쓴다.

**Ask First:**
- 합치는 과정에서 상태 매핑·결과 값·화면 문구가 바뀌어야 하는 사본을 만났을 때

**Never:**
- `telemetry/beacon.ts`(`keepalive`, `Accept`·`signal` 없음, 응답을 읽지 않음)는 합치지 않는다. 이 계약은 4.5 시험이 고정했다.
- 배열을 통과시키는 `useDashboard.record()`(68회 사용, `epic-2-retro-item-21` 범위)와 `codeIn`·`codeOf` 변형은 합치지 않는다. GET 16곳도 이 범위가 아니다.
- 시한 구현(`setTimeout`·`window.setTimeout`·race)을 통일하지 않는다.
- 광고 시험 helper와 engagement 바깥 의존 목록(AI-8 ③, deferred 167)은 다루지 않는다.
- 새 의존이나 백엔드 변경을 더하지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| JSON POST | 본문·멱등 키 있음 | 헤더 넷(Accept·CT·CSRF·IK), 본문 문자열 | 호출자가 상태를 매핑한다 |
| 본문 없음 | `body` 미지정 | CT 없음, `body` 없음 | N/A |
| 판독 실패 | 깨진 JSON이나 빈 본문 | `readJson`이 `null`을 돌려준다 | 예외 없음 |
| 비객체 | 배열·`null`·원시값 | `objectOf`가 `null`을 돌려준다 | N/A |
| 토큰 없음 | `postNoContent(path, null)` | 요청 0건, `false` | N/A |
| 204 아님 | 403, 네트워크 오류, 시한 초과 | `false` | 재시도 없음 |

</frozen-after-approval>

## Code Map

경로 접두사는 `frontend/src/`다. CSRF 헤더 이름은 `content/auth.ts:132`의 `CSRF_HEADER`다.

- `session/http.ts`(신규) -- 네 가지(`readJson`·`objectOf`·`postJson`·`postNoContent`)의 단일 정의.
- readJson 사본: `bybitConnection.ts:357`, `operatorApi.ts:1258`, `useAdConsent.ts:86`, `useSupportInquiries.ts:220`, `useDashboardSync.ts:209`(`jsonOf`). 같은 일을 인라인으로 하는 곳: `planEnd.ts:97`, `PlanConditionPanel.tsx:386`, `adTransition.ts:56-60`.
- objectOf 사본: `useAdConsent.ts:60`, `useServiceNotices.ts:73`, `useSupportInquiries.ts:127`, `operatorApi.ts:1266`, `adTransition.ts:28`(`fieldsOf`). 각 파일의 `type Body`·`CommandBody` 별칭은 공용 타입 `JsonObject`로 바꾼다.
- `session/`의 POST: `adTransition.ts:41`, `bybitConnection.ts:323`(`request`), `operatorApi.ts:1235`, `planEnd.ts:88`, `useAdConsent.ts:206`, `useSupportInquiries.ts:361`, `useDashboardSync.ts:685`, `useFirstSnapshot.ts:126`(시한 없음).
- 204 묶음: `unlinkProvider.ts:92`의 `post`(export, `bybitConnection.ts:11`·`:200`·`:273`이 쓴다)를 `postNoContent`로 옮긴다. `signOut.ts:76`, `linkProvider.ts:153`, `reauthenticate.ts:83`은 그것을 부른다.
- `components/`의 POST: `InitialInputsCollection.tsx:384·791·836`(뒤 둘은 리터럴 CSRF), `PlanConditionPanel.tsx:379·421`(리터럴), `PlanPublicationSection.tsx:171`, `NextPlanBaselinePanel.tsx:222`(`request(controller, init)`으로 보내는 본문 없는 POST).
- 낡게 되는 주석: `adTransition.ts:15`("공통 POST helper가 없으므로"), `bybitConnection.ts:288`, `unlinkProvider.ts:82-88`.
- 안전망: `test/setup.ts:677`이 fetch 가드다. 헤더를 평범한 객체로 읽는 시험은 `AdConsentPanel:60`, `SupportInquiryPanel:64`, `AdTransitionScreen:162`, `OnboardingScreen:244`, `InitialInputsCollection`(`toMatchObject`)이다. 본문 문자열을 정확히 비교하는 시험은 `InitialInputsCollection:99`, `useDashboardVerification:533`이다.
- 열린 회고 액션:
  - `epic-4-retro-item-47`: ②만 닫는다. ③은 deferred 167로 남는다(일부).
  - `epic-1-retro-item-12`(signOut 403 화면 처리): `signOut.ts`는 전송만 바꾸고 이 액션은 건드리지 않는다.
  - `epic-2-retro-item-21`(useDashboard 분리): `useDashboard.ts`는 건드리지 않는다.
  - `epic-1-retro-item-3`(백엔드 `cancelUnlink`): 프런트 `unlinkProvider.ts`는 전송 정의만 옮기고 이 액션은 건드리지 않는다.

## Tasks & Acceptance

**Execution:**
- [x] `session/http.ts` -- 다음을 둔다. `type JsonObject = Readonly<Record<string, unknown>>`, `readJson(response)`, `objectOf(value)`, `postJson(path, {csrfToken, body?, idempotencyKey?, signal?}): Promise<Response>`, `postNoContent(path, csrfToken): Promise<boolean>` -- 단일 정의.
- [x] Code Map의 `session/` 파일 아홉과 `components/` 파일 넷 -- 사본과 init 조립을 helper로 바꾸고, 낡은 주석을 고친다. 각 파일의 상태 분기는 그대로 둔다 -- 동작 불변(Always의 헤더 정규화만 예외).
- [x] `session/unlinkProvider.ts`·`signOut.ts`·`linkProvider.ts`·`reauthenticate.ts`·`bybitConnection.ts` -- `post`를 지우고 `postNoContent`를 쓴다 -- 204 묶음의 사본 넷을 하나로.
- [x] `session/http.test.ts`(신규) -- 매트릭스 여섯 행의 단위 시험을 쓴다. 이 시험은 `import.meta.glob(?raw)`로 운영 소스를 훑어 두 가지도 단정한다. `method: 'POST'`는 `http.ts`와 `telemetry/beacon.ts`에만 있다. `readJson`·`jsonOf`·`objectOf`·`fieldsOf` 정의와 `'X-CSRF-TOKEN'` 리터럴은 각각 `http.ts`와 `content/auth.ts`에만 있다 -- 재발 방지.
- [x] `CLAUDE.md` -- 프런트엔드 공통 부품 문단을 넷에서 다섯으로 고치고 `session/http.ts`를 더한다 -- 규칙의 자리.

**Acceptance Criteria:**
- Given 이 변경, when 프런트엔드 typecheck·lint·lint:css·format:check·build·test를 돌리면, then 기존 시험이 수정 없이 통과한다.
- Given 운영 소스를 grep하면, then POST init 조립은 `http.ts`와 `beacon.ts` 두 곳이다.

## Spec Change Log

- 구현에서 확정한 것(2026-11-04): `bybitConnection.request`의 `extraHeaders` 인자는 `idempotencyKey?` 하나가 됐다. `NextPlanBaselinePanel`의 `request(controller, init)`는 `request(controller, write?)`가 됐다 — `write`가 있으면 `postJson`, 없으면 예전 GET이다. `operatorApi`의 `CommandBody`가 `JsonObject`로 바뀌어 Code Map 밖의 `useOperatorCommand.ts`도 타입 import 한 줄을 고쳤다. 성공 경로에서 `response.json()`을 직접 부르는 곳(`useServiceNotices`·`useFirstSnapshot`·`PlanConditionPanel` 확정·`PlanPublicationSection`·`InitialInputsCollection`)은 사본 helper가 아니라서 그대로 두었다. 바꾼 인라인 판독은 Code Map이 지목한 셋뿐이다.
- 1차 리뷰(2026-11-04, blind·edge-case·verification-gap): patch 6 · defer 0 · reject 10.
  - patch:
    - `postJson`이 `body: null`을 문자열 `"null"`로 보냈다(운영자 cancel은 래퍼의 `?? undefined`만이 막고 있었다). `null`도 본문 없음으로 처리하고 시험 행을 더했다.
    - `JSON.stringify` 예외가 동기로 던져졌다. `postJson`을 `async`로 바꿨다.
    - 소스 훑기 셋이 우회 가능했다. 대소문자 무시와 backtick, `new Request(`, `let`·`var` 정의, 인라인 `.json().catch(`, `CSRF_HEADER` import 위치(`http.ts`·`beacon.ts`)까지 넓혔다.
    - `postNoContent` 주석의 사용처 열거와 "같은 진입점" 근거가 일반 helper에 맞지 않아 고쳤다.
    - `NextPlanBaselinePanel`의 `write` 타입을 `PostJsonOptions`에서 파생하게 했다.
    - 이 SPEC에 Change Log 절이 없어서 더했다.
  - reject:
    - `useSupportInquiries` 이중 직렬화: 같은 객체의 `JSON.stringify`라 같은 문자열이다.
    - 헤더 정규화의 서버 확인: SPEC Always가 승인했고, CI same-origin 통합의 주입 origin e2e가 인증 왕복을 돈다.
    - 남은 `response.json()` 직접 호출: 범위 밖이고 위에 판단을 적었다.
    - `http.ts`가 `useSessionState`에서 상수를 import하는 것: 지금 순환이 없다.
    - `CommandBody` 삭제: typecheck가 통과한다.
    - `linkProvider`의 시한 import: GET 시한에 쓴다.
    - import 묶음: lint `--fix` 결과다.
    - 시험 추가 제안 나머지(falsy 본문, 빈 멱등 키, 이중 판독): 이 변경이 만든 위험이 아니다.
    - SPEC 수치 불일치: 조사 시점 수치이고 Intent는 동결이다.
    - `DEVELOPMENT.md` 동기화: 해당 문단이 없다.

## Design Notes

helper는 전송 모양만 소유하고 판정은 갖지 않는다. 시한·상태 매핑·`{ok,payload,code}` 같은 결과 모양은 호출자의 계약이다. `operatorApi.post`와 `bybitConnection.request`는 래퍼로 남되, 안에서 `postJson`과 `readJson`을 쓴다.

```ts
const response = await postJson(AD_CONSENT_PATH, { csrfToken, body: { choice, policyVersion }, idempotencyKey, signal })
const payload = objectOf(await readJson(response))
```

## Verification

**Commands:**
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check` -- expected: 오류 0
- `npm --prefix frontend run test` -- expected: 전부 통과
- `npm --prefix frontend run build` -- expected: 성공

## Suggested Review Order

**전송 모양의 단일 정의**

- init 규칙 하나: POST·same-origin·no-store·Accept·CSRF, 조건부 CT·멱등 키
  [`http.ts:55`](../../frontend/src/session/http.ts#L55)

- `null`도 본문 없음 — 문자열 "null"을 보내지 않는다
  [`http.ts:66`](../../frontend/src/session/http.ts#L66)

- 옮겨 온 204 묶음: 토큰 없으면 미전송, 세션 시한, 204만 참
  [`http.ts:86`](../../frontend/src/session/http.ts#L86)

- 판독 둘: 깨진 본문은 null, 배열·원시값은 null
  [`http.ts:21`](../../frontend/src/session/http.ts#L21)

**결과 모양을 가진 래퍼(판정은 호출자에 남음)**

- 운영자 command 래퍼가 전송만 위임하고 결과 모양을 지킨다
  [`operatorApi.ts:1223`](../../frontend/src/session/operatorApi.ts#L1223)

- Bybit 준비·확인 래퍼, 멱등 키 인자 하나로
  [`bybitConnection.ts:313`](../../frontend/src/session/bybitConnection.ts#L313)

- 키를 고른 문자열과 같은 객체를 본문으로 넘긴다
  [`useSupportInquiries.ts:348`](../../frontend/src/session/useSupportInquiries.ts#L348)

**헤더가 정규화되는 호출자(Accept·no-store가 새로 붙음)**

- 204 묶음: 로그아웃 결과 접기만 남았다
  [`signOut.ts:63`](../../frontend/src/session/signOut.ts#L63)

- 로그인 수단 해제 셋이 같은 helper로
  [`unlinkProvider.ts:47`](../../frontend/src/session/unlinkProvider.ts#L47)

- components POST: 리터럴 CSRF 헤더가 사라진 자리
  [`InitialInputsCollection.tsx:786`](../../frontend/src/components/InitialInputsCollection.tsx#L786)

- 409 거절 본문의 인라인 판독을 readJson으로
  [`PlanConditionPanel.tsx:380`](../../frontend/src/components/PlanConditionPanel.tsx#L380)

- GET과 공유하던 request가 쓰기만 postJson으로 보낸다
  [`NextPlanBaselinePanel.tsx:86`](../../frontend/src/components/NextPlanBaselinePanel.tsx#L86)

**재발 방지와 문서**

- 매트릭스 행과 리뷰가 더한 null 본문 행
  [`http.test.ts:78`](../../frontend/src/session/http.test.ts#L78)

- 운영 소스 훑기: POST·Request·판독 사본·CSRF import 위치
  [`http.test.ts:184`](../../frontend/src/session/http.test.ts#L184)

- 새 상태 변경 요청이 따를 규칙 문장
  [`CLAUDE.md:87`](https://github.com/sh-9610/BokriLog/blob/6869c71f63adbce4a0053b4533467892ea91495f/CLAUDE.md#L87)
