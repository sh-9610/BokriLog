---
title: 'Story 4.4 — 모든 광고 실패에서 저장된 결과로 fail-open하기'
type: 'feature'
created: '2026-10-16'
status: 'done'
baseline_commit: 'edac31712e58c1e8d97ead51568ba1f939c16274'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-3-neutral-ad-transition.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 4.3의 adapter는 slot `null`·script 오류·예외만 실패로 안다. GPT가 늦게 뜨거나, 광고를 채우지 못하거나, 화면이 떠난 뒤 callback이 오는 경우를 다루지 않는다. 그래서 중립 화면이 광고를 기다리며 머물 수 있다(`epics.md` Story 4.4 AC-1·2·5, AC-3 앞부분). 범위 분할로 광고 계측(AC-4)과 Web Vitals kill switch(AC-3 뒷부분)는 `deferred-work.md` 156·157로 뗐다.

**Approach:**
- adapter가 감지할 수 있는 모든 실패를 `onFailure` 한 번으로 접는다. 화면은 지금처럼 그때 광고 없이 `/dashboard`로 간다.
- 감지하는 실패: 준비 시간초과 3초, 미채움, 차단, 예외.
- adapter는 취소 handle을 돌려준다. 화면이 떠날 때(닫기·실패) 그 handle로 광고를 멈추고, 늦은 callback을 무시한다.

## Boundaries & Constraints

**Always:**
- **adapter 계약.** `showInterstitial(adUnitPath, onFailure): () => void`이다. 돌려받은 함수가 취소다.
  - 준비는 우리 slot의 `slotOnload`다. 부른 뒤 3초(`AD_READY_TIMEOUT_MS = 3000`) 안에 오지 않으면 실패다.
  - 우리 slot의 `slotRenderEnded`가 `isEmpty: true`면(미채움) 실패다.
  - script `error`(CSP·차단기), slot `null`, command 안 예외도 실패다(4.3 그대로).
  - 준비되면 시한을 해제한다. 그 뒤 광고는 4.3처럼 `결과 보기` 클릭이 띄운다.
- **한 번만 결론.** 결론(준비·실패·취소)은 한 번이다.
  - 실패와 취소는 리스너를 떼고, 시한을 해제하고, slot이 있으면 `googletag.destroySlots([slot])`을 부른다.
  - 결론 뒤의 이벤트·시한·command는 아무것도 부르지 않는다. 준비 뒤의 `isEmpty`만 예외로 실패다.
  - 취소 뒤에는 `onFailure`를 부르지 않는다.
- **화면.** `AdTransitionScreen`의 `leave`가 adapter 취소를 먼저 부른다. 그다음 `replaceLocation('/dashboard')`이다.
  - 실패로 떠나든 닫기로 떠나든 이동은 한 번이다.
  - 나머지 4.3 계약(주소 정리 → 소비 → SHOW만 adapter, 최초 포커스, 401·403·시한)은 그대로다.
- **비식별.** adapter가 공급자에 넘기는 것은 ad unit 경로와 `nonPersonalizedAds`뿐이다. targeting·URL 조작·`console` 출력이 없다.
- **재시도 없음.** 실패 뒤 어느 경로도 다시 load·display·소비하지 않고, 서버로 아무것도 보내지 않는다. 늦은 callback이 서버 데이터를 만들 길이 없다.
- 광고 전용 정지(AC-3 앞부분)는 기존 계약이 지킨다. 새 코드는 없다.
  - `RestrictionEnforcementTest.java:60`이 기능별 독립을, `AdEligibilityTest`·`AdTransitionTest`의 `RESTRICTED`가 광고 관문을 지킨다.

**Ask First:**
- 시한 3초를 바꾸거나 준비 신호를 `slotOnload` 말고 다른 것으로 바꾸는 것.
- 서버에 실패 사유를 보내는 경로를 더하는 것. 그것은 156 몫이다.

**Never:**
- 자동 재요청, 다른 공급자 fallback, cap 우회, 사용자에게 광고 문제 해결을 요구하는 카피.
- 광고 계측과 beacon(156), Web Vitals와 kill switch(157), 백엔드·스키마·CSP·경로 변경.
- 실제 소재의 키보드·스크린리더 검증을 시험으로 대신해 출시 준비 완료로 적는 것(G-4).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 준비 | 3초 안 `slotOnload` | 머문다. 결과 보기 클릭이 광고 → `/dashboard` | N/A |
| 시간초과 | 3초 안에 준비 신호 없음 | `onFailure` 1회 → `/dashboard`, slot 파기 | 재시도 없음 |
| 미채움 | `slotRenderEnded` `isEmpty` | 준비 전후 모두 `/dashboard` | 재시도 없음 |
| 차단 | script `error` | `/dashboard` | N/A |
| 늦은 callback | 실패·닫기 뒤 `slotOnload`·`isEmpty`·시한 | 아무것도 부르지 않음 | N/A |
| 다른 slot | 남의 slot 이벤트 | 무시 | N/A |
| 닫기 | SHOW 뒤 광고 닫기 | 취소 → `/dashboard` 1회, `onFailure` 없음 | N/A |

</frozen-after-approval>

## Code Map

이 스토리의 `{project-root}`와 아래 모든 경로는 worktree `C:\Users\SSAFY\Desktop\2학기\SideProject\BokriLog-4-4`(브랜치 `feature/4-4-ad-fail-open`) 기준이다. 사용자 체크아웃(`...\BokriLog`)의 파일은 고치지 않는다. 명령도 그 worktree에서 실행한다.

- `frontend/src/ads/gptAdapter.ts:25-93` — 타입(`PubAdsService` 25, `GoogleTag` 33)에 `addEventListener`·`removeEventListener`·`destroySlots`를 더한다. `showInterstitial` 53의 `fail` 한 번 관용을 결론 상태로 넓힌다. 머리 주석 14-15가 4.4로 넘긴 목록이다.
- `frontend/src/ads/gptAdapter.test.ts:25-62` — `loadGpt` 대역이다. 이벤트 발사 함수와 `destroySlots` 기록을 더하고 가짜 시계(`vi.useFakeTimers`)로 시한을 본다.
- `frontend/src/screens/AdTransitionScreen.tsx:68-72` — `leave`. 114의 `showInterstitial` 반환을 ref에 두고 `leave`에서 부른다.
- `frontend/src/screens/AdTransitionScreen.test.tsx:28` — adapter mock(반환 함수 기본값 필요). 실패 시험 304, 늦은 SHOW 322, 닫기 349가 확장 자리다.
- 기존 보증(읽기 전용):
  - `backend/src/test/java/com/bokrilog/RestrictionEnforcementTest.java:60` — 기능별 독립 행렬.
  - `AdTransitionTest.java:223` — 삭제 시작 계정 404, 소비 없음.
  - `AdTransitionService` — 로그에 단계·code만 남는다.

## Tasks & Acceptance

**Execution:**
- [x] `frontend/src/ads/gptAdapter.ts` — 준비·시한·미채움·취소·파기를 더한다. 머리 주석을 갱신한다 — matrix의 adapter 행을 한 곳에서 판정하기 위함이다.
- [x] `frontend/src/ads/gptAdapter.test.ts` — matrix 전 행을 시험한다(늦은 callback·다른 slot·준비 뒤 `isEmpty`·취소 뒤 무호출). 실패 뒤 GPT script가 하나뿐이고 `display` 1회이며 fetch·저장소 0건임을 함께 본다.
- [x] `frontend/src/screens/AdTransitionScreen.tsx` — `leave`가 취소를 부른다.
- [x] `frontend/src/screens/AdTransitionScreen.test.tsx` — 닫기가 취소를 부르는지, 실패 뒤 늦은 실패가 두 번째 이동을 만들지 않는지 시험한다.
- [x] `_bmad-output/implementation-artifacts/epic-4-context.md`·`CLAUDE.md` — 4.4가 156·157을 뗀 사실과 adapter 계약 한 줄을 적는다(`CLAUDE.md` 광고 절).

**Acceptance Criteria:**
- Given SHOW 뒤 어떤 실패 경로, when 결론이 날 때, then `replaceLocation('/dashboard')`는 정확히 한 번이고 그 뒤 GPT 호출·fetch가 없다.
- Given 기본 구성의 주입 origin e2e, when 4.3 시험을 다시 돌릴 때, then 결과는 그대로다(제3자 요청 0건).

## Spec Change Log

- 2026-10-16 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap) — intent_gap·bad_spec 0, patch 11, defer 1(`deferred-work.md` 158), 나머지 reject.
  - **defer 1건:** Windows 로컬 Vitest의 운영자 lazy chunk 대기 시간초과. `develop`에서도 같은 실패가 재현된다.
  - **patch — 정리:** `release()`의 단계마다 따로 시도하게 했다. 앞 단계가 던져도 slot 파기는 일어난다. 정리 중 예외와 slot 정의 뒤 예외(`enableServices`·`display`)를 시험으로 고정했다.
  - **patch — 화면:**
    - 준비 전 `결과 보기` 클릭 뒤의 늦은 실패·닫기가 두 번째 이동을 만들지 않는다.
    - bfcache 복원(`pageshow`의 `persisted`)은 취소 뒤 `/dashboard`로 간다. 살아 있는 slot으로 cap을 우회하지 못한다.
    - 언마운트도 취소한다.
  - **patch — 시험·문서:**
    - 3초 고정 단정을 더하고 다른 slot·준비 뒤 취소의 단정을 보강했다.
    - 상태 기계 문구를 "실패·취소가 종단, 준비는 시한 해제"로 맞췄다.
    - `CLAUDE.md` 광고 절에 접근성 경계를 적고 `epic-4-context.md`의 3.7 문장을 고쳤다.
    - Design Notes에 시한·느린 망과 관문 시점을 더했다.
  - **reject:**
    - 브라우저 층 SHOW 검증은 153과 같다.
    - 156·157의 `source_spec: none`은 step-01 분할 형식이다.
    - 156 근거의 adapter 신호 노출은 156이 정할 계약이다. done 전환 판정에 적는다.
    - AC 축소의 기획 재발행은 3.7 분할 선례를 따른다.
    - 122 판정과 `CLAUDE.md` 건수는 done 전환 몫이다.
    - 동기 실패의 취소 순서는 adapter가 이미 종단이라 무해하다.
  - **KEEP:**
    - adapter 하나가 결론을 내고 화면은 `leave` 한 곳에서 떠난다.
    - 실패·취소는 리스너 해제·시한 해제·`destroySlots`를 한다.
    - 재시도·서버 보고가 없다.

## Design Notes

- **준비 신호.** web interstitial은 클릭 때 뜨므로 "보였다"를 기다릴 수 없다. 공급자 예제는 `slotOnload`를 "interstitial이 준비됐다"로 쓴다. 그래서 시한은 준비까지만 센다. 준비 뒤의 미채움 `slotRenderEnded`도 실패로 둔다 — 그때 클릭하면 광고 없이 이동할 뿐이지만, 빈 중립 화면에 머물 이유가 없다.
- **접근성 실패의 경계.** 감지할 수 있는 것은 셋이다 — 이 기기에서 interstitial 미지원(`null` slot), 준비 시간초과, 차단. 소재 iframe 안의 키보드 트랩·레이블은 교차 출처라 볼 수 없다. 그것은 실제 소재로 하는 G-4 검증 몫이다. iframe 밖 네이티브 `광고 닫기`가 클릭 전 항상 첫 조작이라는 4.3 계약이 그 사이의 탈출구다.
- **파기.** 실패 직후 `replaceLocation`과 문서 unload 사이에도 GPT가 interstitial을 띄울 수 있다. `destroySlots`가 그 틈을 닫는다.
- **시한과 느린 망.** 3초 시한은 `gpt.js` 다운로드를 포함한다(부른 순간부터 센다). 느린 망에서는 fail-open이 흔한 결말일 수 있고, 그 실패율은 계측(156) 전에는 알 수 없다.
- **관문 시점.** 관문(동의·`ADVERTISING`·공급자)은 판정·소비 시점에만 본다. 운영자가 `ADVERTISING` 제한을 건 시점에 이미 SHOW를 받은 중립 화면은 그 한 번의 광고를 그대로 시도한다(소비 창 120초 안).

## Verification

**Commands:**
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run test` — expected: 통과.
- `deploy/compose/verify-same-origin.sh --keep-stack` 뒤 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 npm --prefix frontend run test:e2e:run` — expected: 0 종료, 기존 e2e 통과.

## Suggested Review Order

**adapter의 결론 상태 기계 (진입점)**

- 취소 함수를 돌려주는 계약, 상태는 `pending → ready → over`
  [`gptAdapter.ts:97`](../../frontend/src/ads/gptAdapter.ts#L97)

- 준비는 우리 slot의 `slotOnload`뿐, 시한만 해제한다
  [`gptAdapter.ts:105`](../../frontend/src/ads/gptAdapter.ts#L105)

- 미채움은 준비 전후 모두 실패, 남의 slot은 무시
  [`gptAdapter.ts:111`](../../frontend/src/ads/gptAdapter.ts#L111)

- 실패·취소는 종단 — 둘 다 같은 정리를 한 번만
  [`gptAdapter.ts:138`](../../frontend/src/ads/gptAdapter.ts#L138)

- 부른 순간부터 3초, script load가 그 안에 든다
  [`gptAdapter.ts:152`](../../frontend/src/ads/gptAdapter.ts#L152)

- 결론 뒤 처리되는 command는 아무것도 부르지 않는다
  [`gptAdapter.ts:162`](../../frontend/src/ads/gptAdapter.ts#L162)

**정리와 파기**

- 리스너·시한 해제와 `destroySlots`를 단계마다 따로 시도
  [`gptAdapter.ts:120`](../../frontend/src/ads/gptAdapter.ts#L120)

- 단계 하나의 예외가 뒤 단계를 막지 않게 하는 helper
  [`gptAdapter.ts:28`](../../frontend/src/ads/gptAdapter.ts#L28)

**중립 화면의 이동**

- 떠나는 곳은 `leave` 하나, 이동보다 취소가 먼저
  [`AdTransitionScreen.tsx:74`](../../frontend/src/screens/AdTransitionScreen.tsx#L74)

- `결과 보기` 클릭 뒤의 늦은 실패는 두 번째 이동을 만들지 않는다
  [`AdTransitionScreen.tsx:86`](../../frontend/src/screens/AdTransitionScreen.tsx#L86)

- bfcache 복원은 재진입 — 취소하고 결과로 간다(cap 우회 차단)
  [`AdTransitionScreen.tsx:100`](../../frontend/src/screens/AdTransitionScreen.tsx#L100)

- 언마운트도 취소한다
  [`AdTransitionScreen.tsx:118`](../../frontend/src/screens/AdTransitionScreen.tsx#L118)

- adapter가 돌려준 취소를 ref에 둔다
  [`AdTransitionScreen.tsx:158`](../../frontend/src/screens/AdTransitionScreen.tsx#L158)

**문서**

- 여러 스토리에 걸치는 adapter 계약과 접근성 경계
  [`CLAUDE.md:268`](../../CLAUDE.md#L268)

**시험**

- 3초 고정과 준비 경로
  [`gptAdapter.test.ts:164`](../../frontend/src/ads/gptAdapter.test.ts#L164)

- 실패 matrix, slot 정의 뒤 예외의 정리 순서
  [`gptAdapter.test.ts:339`](../../frontend/src/ads/gptAdapter.test.ts#L339)

- 정리 단계가 던져도 실패 1회·파기 시도·취소 무예외
  [`gptAdapter.test.ts:357`](../../frontend/src/ads/gptAdapter.test.ts#L357)

- 결과 보기·복원·언마운트의 화면 시험
  [`AdTransitionScreen.test.tsx:402`](../../frontend/src/screens/AdTransitionScreen.test.tsx#L402)
