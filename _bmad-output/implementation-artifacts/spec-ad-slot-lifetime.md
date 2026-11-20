---
title: 'Epic 4 회고 AI-1 — 준비된 광고 slot을 소비 창 120초에 묶기'
type: 'bugfix'
created: '2026-10-16'
status: 'done'
baseline_commit: 'ab687929ec0a3968b52d0ea622f5069adc5e9902'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-4-ad-fail-open.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-retro-2026-10-16.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 중립 화면(`/ad-transition`)에서 SHOW를 받아 준비된 GPT slot에는 수명 상한이 없다. 결정 뒤 119초에 소비하고 탭을 오래 열어 두면, 그사이 동의 철회·`restrict-advertising`·계정 삭제가 일어나도 `결과 보기` 클릭이 interstitial을 띄운다(회고 B-1). 4.4 Design Notes "관문 시점"이 적은 "소비 창 120초 안"이 지켜지지 않는다.

**Approach:** 광고를 띄울 수 있는 기간을 서버 소비 창(결정 뒤 120초)에 묶는다(사용자 결정 2026-10-16). 이 문서가 시작된 시각(결정 응답 뒤의 문서 이동)부터 120초가 지나도록 `결과 보기`를 누르지 않았으면, 화면이 기존 `leave` 경로로 광고를 취소하고(slot 파기) 광고 없이 `/dashboard`로 replace한다. 서버는 바꾸지 않는다.

## Boundaries & Constraints

**Always:** 상한의 기준은 `performance.now()`(문서 시작부터 흐른 단조 시간)이고 벽시계를 쓰지 않는다. 남은 시간이 0 이하면 즉시 떠난다. 상한 도달은 `leave()` 한 경로로만 처리한다. 그래서 이동은 한 번이고, 취소가 먼저이며, 늦게 온 SHOW는 adapter를 부르지 않는다. `결과 보기`를 이미 눌렀으면 상한은 아무것도 하지 않는다. 클릭이 띄운 광고는 창 안에서 시작된 것이므로 표시 중에 파기하지 않는다. 화면 상수와 서버 `AdTransitionApi.CLAIM_WINDOW_SECONDS`는 파일 대조 시험으로 묶는다(`AdConsentPolicyVersionTest` 선례).

**Ask First:** 서버 응답에 기한 필드를 더하거나, adapter 계약(`showInterstitial(adUnitPath, onFailure): () => void`)을 바꿔야 할 때.

**Never:** 서버·DB·마이그레이션·Nginx 변경. 클릭 시점에 관문을 다시 묻는 요청. 브라우저 저장소나 주소로 결정 시각을 넘기는 것. 상한 도달 때 재시도·fallback·서버 보고.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 창 안 클릭 | SHOW·준비 뒤, 문서 시작 후 120초 전에 `결과 보기` | anchor 기본 이동, 상한이 와도 취소·replace 없음 | N/A |
| 준비된 채 머묾 | SHOW, 120초까지 클릭 없음 | 120초에 `cancelAd` 한 번 → `/dashboard` replace 한 번 | N/A |
| 소비 응답이 창 뒤 | 상한 도달 뒤에 SHOW 도착 | adapter를 부르지 않음, 이동 한 번 | N/A |
| 늦게 연 문서 | 마운트 시 `performance.now()` ≥ 120초 | 마운트 커밋에서 바로 결과로 | N/A |
| 다른 결말이 먼저 | 닫기·adapter 실패·skip 뒤 상한 | 두 번째 이동 없음 | N/A |

</frozen-after-approval>

## Code Map

- `frontend/src/screens/AdTransitionScreen.tsx` -- `NeutralAdTransition`. `leave`(74행, 한 번·취소 먼저), `followResult`(86행, `leaving`만 세움), 세션 시한 layout effect(135-142행)가 상한 effect의 본보기다.
- `frontend/src/content/adTransition.ts` -- 화면·경로 상수의 자리(`AD_TRANSITION_PATH` 등). 서버 상수와 같은 값임을 주석으로 적는 관용이 있다(20행).
- `frontend/src/ads/gptAdapter.ts` -- 읽기만 한다. 준비가 3초 준비 시한만 해제한다(105-109행). 수명은 adapter 계약 밖이다.
- `frontend/src/ads/gptAdapter.test.ts:186` -- "준비되면 시한이 지나도 머문다". adapter가 준비 시한 뒤 스스로 실패하지 않는다는 사실만 고정하도록 이름·주석을 고친다.
- `frontend/src/screens/AdTransitionScreen.test.tsx` -- `showing()` helper(365행), 가짜 시계 관용 `vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })`(261행). `performance.now`는 가짜가 아니므로 `vi.spyOn`으로 고정한다.
- `backend/src/main/java/com/bokrilog/engagement/AdTransitionApi.java:25` -- `CLAIM_WINDOW_SECONDS = 120`. 읽기만 한다.
- `backend/src/test/java/com/bokrilog/engagement/AdConsentPolicyVersionTest.java` -- 파일 대조 시험의 선례다(정규식으로 선언 하나를 확인).

## Tasks & Acceptance

**Execution:**
- [x] `frontend/src/content/adTransition.ts` -- `export const AD_CLAIM_WINDOW_SECONDS = 120`을 더하고 서버 상수와 같다는 주석을 단다 -- 대조 시험이 읽을 선언 하나.
- [x] `frontend/src/screens/AdTransitionScreen.tsx` -- 마운트 때 `max(0, 창 − performance.now())`로 setTimeout을 걸고, 시한에 `leaving`이 아니면 `leave()`를 부르는 layout effect를 둔다. 정리 시 해제한다. 컴포넌트 주석에 상한을 한 줄 더한다 -- B-1 수정.
- [x] `frontend/src/screens/AdTransitionScreen.test.tsx` -- I/O 행 다섯을 시험한다 -- 상한 시험.
- [x] `frontend/src/ads/gptAdapter.test.ts` -- 186행 시험의 이름·주석을 "준비는 준비 시한만 해제한다 — 수명 상한은 화면의 취소"로 고친다 -- 무기한 머묾을 계약으로 읽히지 않게.
- [x] `backend/src/test/java/com/bokrilog/engagement/AdClaimWindowTest.java` -- 화면의 `AD_CLAIM_WINDOW_SECONDS` 선언 하나가 `AdTransitionApi.CLAIM_WINDOW_SECONDS`와 같음을 단정한다 -- 두 벌 표류 방지.
- [x] `CLAUDE.md` -- 광고 fail-open(4.4) 계약 줄에 "준비된 slot은 문서 시작 뒤 소비 창 120초까지, 클릭 없으면 화면이 취소하고 결과로" 한 구절을 더한다.

**Acceptance Criteria:**
- Given 상한 effect가 있을 때, when StrictMode 이중 실행·언마운트가 일어나면, then 남는 타이머가 없다.
- Given 서버 상수를 바꿀 때, when 화면 상수를 그대로 두면, then 백엔드 시험이 실패한다.

## Spec Change Log

- 구현 1차(Code Map 밖 변경 하나): 참조가 있을 때의 주소 정리(`replaceAddress`)를 부모 `AdTransitionScreen`에서 `NeutralAdTransition`의 첫 layout effect로 옮겼다. 자식의 layout effect가 부모보다 먼저 돌아, 늦게 연 문서가 마운트 커밋에서 떠나면 이동이 주소 정리보다 앞서기 때문이다. 이렇게 해서 4.3의 "주소 정리가 먼저" 순서를 유지한다. KEEP: 부모는 참조가 없을 때만 주소를 정리하고 떠난다.
- 1차 리뷰 patch 7건(Blind·Edge Case·Verification Gap 세 층, intent_gap·bad_spec 없음, defer 없음):
  - ① `결과 보기`의 보조키·비주 버튼 클릭은 떠남으로 치지 않는다. 새 탭으로 열어도 원래 탭의 상한·닫기가 산다.
  - ② 클릭 시점에 창이 지났으면 `preventDefault` 뒤 `leave`한다. 백그라운드 스로틀링·절전으로 타이머가 늦을 때를 막는 방어이고, GPT 클릭 처리와의 순서는 G-4 몫이다.
  - ③ CLAUDE.md 문장을 "중립 화면 전체가 창에 묶인다"로 바로잡았다.
  - ④ `AdClaimWindowTest` 주석에 근사 한계를 적었다.
  - ⑤ adapter 시험 이름에 원래 조건을 되살렸다.
  - ⑥ 늦은 소비 시험에 상한 직전 무이동 단정을 더했다.
  - ⑦ StrictMode 지난 창·보조키 클릭·늦은 타이머 클릭 시험을 더했다.
  - reject 판단: 대시보드의 이동 지연 우려는 사실이 아니다(이동하거나 건너뛸 뿐, `useDashboardSync.ts`). 문서 시작 근사는 승인된 설계다. 미소비 reservation의 빈도 소진은 4.2 의미 그대로다.

## Design Notes

수명을 adapter가 아니라 화면에 둔다. 상한이 와도 `결과 보기`를 이미 누른 문서(표시 중인 interstitial)는 건드리면 안 되는데, 클릭을 아는 것은 화면(`followResult`)뿐이다. adapter에 두면 계약에 "클릭됨" 신호를 더해야 한다. 화면의 `leave`는 이미 취소 → slot 파기 → replace를 한 번만 한다.

기준을 문서 시작으로 잡는 이유: 대시보드는 ELIGIBLE 응답을 받은 그 자리에서 문서 이동하므로, 문서 시작은 결정 시각 뒤 응답 전송 시간만큼 늦을 뿐이다. SHOW 수신 시점을 기준으로 잡으면 결정 뒤 최대 240초까지 열린다. 서버 변경 없이 얻을 수 있는 가장 촘촘한 근사다.

## Verification

**Commands:**
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run format:check && npm --prefix frontend run test` -- expected: 모두 통과
- `backend/gradlew --project-dir backend test --tests com.bokrilog.engagement.AdClaimWindowTest` -- expected: 통과

## Suggested Review Order

**수명 상한**

- 문서 시작 기준 120초 타이머가 `leave` 한 경로로 취소·이동한다
  [`AdTransitionScreen.tsx:192`](../../frontend/src/screens/AdTransitionScreen.tsx#L192)

- 보조키 클릭은 떠남이 아니고, 창이 지난 클릭은 막고 떠난다
  [`AdTransitionScreen.tsx:107`](../../frontend/src/screens/AdTransitionScreen.tsx#L107)

**주소 정리 순서**

- 참조 있는 주소 정리를 자식 첫 layout effect로 옮겨 이동보다 앞세운다
  [`AdTransitionScreen.tsx:126`](../../frontend/src/screens/AdTransitionScreen.tsx#L126)

- 부모는 참조가 없을 때만 정리하고 떠난다
  [`AdTransitionScreen.tsx:47`](../../frontend/src/screens/AdTransitionScreen.tsx#L47)

**두 벌 상수**

- 화면 상수, 서버 `CLAIM_WINDOW_SECONDS`와 같은 값
  [`adTransition.ts:27`](../../frontend/src/content/adTransition.ts#L27)

- 선언 하나를 서버 상수와 대조한다
  [`AdClaimWindowTest.java:23`](../../backend/src/test/java/com/bokrilog/engagement/AdClaimWindowTest.java#L23)

**시험·문서**

- I/O 행과 보조키·늦은 타이머·StrictMode 시험
  [`AdTransitionScreen.test.tsx:445`](../../frontend/src/screens/AdTransitionScreen.test.tsx#L445)

- adapter는 준비 시한만 해제한다 — 수명은 화면 몫
  [`gptAdapter.test.ts:188`](../../frontend/src/ads/gptAdapter.test.ts#L188)

- 4.4 계약 줄에 상한 구절
  [`CLAUDE.md:271`](https://github.com/sh-9610/BokriLog/blob/d9b72bc1b0b24d69ce8102a8d5f559b933d063f8/CLAUDE.md#L271)
