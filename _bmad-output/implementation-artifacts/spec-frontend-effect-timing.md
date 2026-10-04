---
title: '프런트엔드 효과 시점: 커밋 직후 관측되는 효과를 layout effect로, 비동기 대기 뒤에는 단정할 조건을 기다린다'
type: 'bugfix'
created: '2026-10-04'
status: 'done'
review_loop_iteration: 0
baseline_commit: '72453717fa47f5c812c62ee08c11e7eae2b77198'
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** CI `frontend-quality`의 Vitest가 가끔 실패하고 로컬 8코어에서는 거의 재현되지 않는다. 최근 실패 다섯 중 셋(run 36115198593 develop, 37119393640 PR #54, 37134688361 PR #57)이 같은 원인이다. 초점 이동·`replaceLocation`·폴링 타이머 등록과 해제가 passive `useEffect`에 있는데, 시험은 `findBy*`·`waitFor`로 새 DOM을 본 직후 그 결과를 동기로 단정한다. RTL은 대기 동안 act 환경을 끄고 대기를 마친 뒤 `setTimeout(0)`을 한 번만 기다린다. 부하가 걸리면 Scheduler가 5ms 프레임을 넘겨 yield하고, 그러면 passive effect가 그 `setTimeout`보다 늦게 돈다. 이 세션의 기준선(16개 `yes` 부하, 전체 Vitest 12회)에서도 2회가 실패했다.

**Approach:** 커밋이 화면을 바꾸는 순간 함께 성립해야 하는 효과(초점 이동, 주소 교체, 타이머·리스너 등록과 해제, 그 효과들이 읽는 latest-ref 갱신)를 `useLayoutEffect`로 옮긴다. 요청 시작처럼 의미상 passive인 효과는 그대로 두고 근거를 주석으로 남긴다. 시험은 비동기 대기 직후 효과나 늦게 오는 읽기 결과를 동기로 단정하던 곳을 `waitFor`·`findBy*`로 바꾼다. CI 부하를 로컬에서 재현하는 `frontend/scripts/stress-test.sh`를 더해 `DEVELOPMENT.md`에 적고, 수정 뒤 그 스크립트로 12회 이상 실패 0을 확인한다.

## Boundaries & Constraints

**Always:**
- 효과의 본문·의존성·정리는 그대로다. 바뀌는 것은 훅 종류뿐이다. 서로 순서에 기대는 효과는 함께 옮긴다. 예를 들어 `SyncControl`의 복구는 패널의 제목 초점보다 먼저 돌아야 한다(자식 → 부모 순서).
- layout effect 본문에 새 동기 `setState`를 넣지 않는다.
- 시험의 기대값·대상은 바꾸지 않는다. 바뀌는 것은 대기 방식뿐이다. 시험 수 866이 그대로다.
- 스크립트는 bash 3.2 호환이고 GNU 전용 플래그를 쓰지 않는다. `set -o pipefail` 아래에서 `생산자 | grep -q`를 쓰지 않는다. 부하 프로세스는 trap으로 정리한다. 종료 코드는 0(통과)·1(실패한 실행 있음)·2(준비 실패)다.
- `deferred-work.md`는 고치지 않는다. 해소는 부하 반복이 실제로 성공한 뒤 `deferred-work-resolutions.md`에 적는다.

**Ask First:**
- CI에 부하 반복 단계를 넣는 것. 이번에는 넣지 않으므로 `ci.yml`·`.github/scripts`는 무변경이다. 넣는다면 판정은 `.github/scripts`가 소유하고 `test-ci-scripts.sh`를 함께 갱신한다.

**Never:**
- 재시도(`retry`), `testTimeout`·`asyncUtilTimeout` 상향, 단일 스레드·`sequence` 변경처럼 실패를 덮는 설정.
- 시험 삭제·`skip`, 단정 완화.
- 네트워크 요청 시작을 layout effect로 옮기는 것.

## I/O & Edge-Case Matrix

| 효과 종류 | 위치(develop 기준) | 바뀐 뒤 | 근거 |
|---|---|---|---|
| 초점 이동 | `EntryScreen`·`SettingsScreen` ×2·`UnlinkDialog`·`AdjustmentHistory` ×2·`CalculationEvidence`·`VerificationControl` ×2·`ReauthPanel`·`PlanResultPanel` ×2·`SyncControl` ×2 | layout | 내용을 바꾸는 커밋에서 초점이 따라가야 한 프레임도 `<body>`에 머물지 않는다 |
| 주소 교체 | `useSessionState` 두 `replaceLocation` | layout | 보내야 할 화면을 그린 커밋에서 떠난다 |
| 타이머·리스너 등록과 해제 | `PlanPublicationSection` 폴링, `InitialInputsCollection` 폴링 ×3, `useDashboardSync` 조용한 읽기·예산·쿨다운 ×2·가시성 리스너 | layout | 상태가 바뀐 커밋에서 폴링이 시작되고 멈춘다 |
| latest-ref 갱신 | `UnlinkDialog` `latest`, `useDashboardSync` 네 ref | layout | layout으로 옮긴 효과와 그 콜백이 같은 커밋의 값을 본다 |
| 요청 시작·부모 보고·live 표지 | 요청 11곳(그 요청의 기한 타이머와 `useSessionState`의 `pageshow` 재확인 리스너를 함께 붙이는 것 포함), `InitialInputsCollection` `onCalculable`, `useDashboardSync` `live` | passive 유지, 주석 | 커밋이 보여 주는 결과가 아니고 답은 어차피 뒤 작업에서 온다. 함께 붙는 타이머·리스너는 그 요청의 수명이라 떼지 않는다. layout에서 부모 `setState`를 부르면 그리기 전에 동기 재렌더가 일어난다 |
| 시험: 비동기 대기 뒤 초점·주소 단정 | `SignedInScreen.test` 589·987·1143·1232·1355, `OnboardingScreen.test` 416 | `await waitFor` | 조건 자체를 기다린다 |
| 시험: 읽기 전부터 있는 상태 영역 | `InitialInputsCollection.test` 386·425·528·743, `PlanConditionPanel.test` 315·328 | 텍스트를 기다린다 | 같은 파일 640행이 이미 쓰는 관용이다 |

</frozen-after-approval>

## Code Map

- `frontend/src/session/useSessionState.ts:486-498` -- 두 `replaceLocation` 효과. 466의 가시성 리스너가 이미 layout이고 그 근거 주석(460-462)이 같은 원리다.
- `frontend/src/components/PlanPublicationSection.tsx:143` -- 폴링 interval. 403 시험(`InitialInputsCollection.test.tsx:1071`)의 늦은 해제가 기준선 실패 원인이다(`reads` 7≠2). 127의 첫 읽기는 passive로 둔다.
- `frontend/src/components/InitialInputsCollection.tsx:357,743,753` -- 폴링 셋. 306(`onCalculable`)·340·701(요청)은 passive로 둔다.
- `frontend/src/session/useDashboardSync.ts` -- 568의 ref 갱신은 "같은 커밋의 뒤 효과가 최신 값을 본다"는 전제라 layout으로 옮긴다. 753 리스너, 789 조용한 읽기, 806 예산(정리에서 경과를 뺀다), 832·848 쿨다운도 layout이다. 576(`live`)·746·778(admit 요청)은 passive로 둔다.
- 초점 효과 -- `screens/EntryScreen.tsx:62`, `screens/SettingsScreen.tsx:207,222`, `components/UnlinkDialog.tsx`(`latest` 상자와 여는 effect — 기본 포커스·키 처리기·스크롤 잠금, 호출점은 한 번만 기록 — 는 layout이고, 호출점 복귀만 passive effect의 정리다. 복귀는 포커스를 잃었을 때만 한다. 아래 Design Notes의 `inert` 절), `components/AdjustmentHistory.tsx:555,685`, `components/CalculationEvidence.tsx:426`, `components/VerificationControl.tsx:213,221`, `components/ReauthPanel.tsx:92`, `components/PlanResultPanel.tsx:467,476`, `components/SyncControl.tsx:303,318`(312-316 주석이 자식 → 부모 순서에 기댄다).
- passive로 남는 요청 효과 -- `SettingsScreen:176`, `PlanConditionPanel:199`, `useBybitConnection:99`, `useFirstSnapshot:65`, `useDashboard:813`, `useSessionState:339`.
- 시험 -- `screens/SignedInScreen.test.tsx`, `screens/OnboardingScreen.test.tsx`, `components/InitialInputsCollection.test.tsx`, `components/PlanConditionPanel.test.tsx`. 부정 단정 `not.toHaveBeenCalled()` 15곳은 고치지 않는다. 주소 효과가 layout이 되면 기다린 DOM과 같은 커밋에서 이미 돌았으므로 그 단정이 비로소 의미를 갖는다.
- `DEVELOPMENT.md:86-146` -- 명령 표와 `### 구조와 규칙`.
- `_bmad-output/implementation-artifacts/deferred-work-resolutions.md` -- 원장은 50번까지만 판정이 있다. 50번에는 새 판정을 덧붙이고 83번 절을 새로 둔다.

## Tasks & Acceptance

**Execution:**
- [x] `frontend/src/session/useSessionState.ts` -- 두 주소 효과를 layout으로 옮기고 339 요청 효과에 passive 근거를 적는다
- [x] 초점 효과 파일 아홉 -- 위 Code Map의 효과를 layout으로 옮기고 근거를 주석 한 곳에 적는다
- [x] `PlanPublicationSection.tsx`, `InitialInputsCollection.tsx`, `useDashboardSync.ts` -- 타이머·리스너·ref 효과를 layout으로 옮기고 남는 passive 효과에 근거를 적는다
- [x] 나머지 요청 효과 파일 -- passive 근거 주석
- [x] 시험 네 파일 -- I/O 표의 시험 행을 적용하고 403 시험에 layout 의존을 주석으로 적는다
- [x] `frontend/scripts/stress-test.sh` -- 부하 프로세스(기본 CPU 수 × 2), N회(기본 12) 반복, `--` 뒤 Vitest 인자 전달, Node major를 `.nvmrc`와 대조, 실패한 실행의 시험 이름 요약
- [x] `DEVELOPMENT.md`, `CLAUDE.md` -- 명령 표에 스크립트를 넣고 효과 시점 규칙을 적는다. CLAUDE.md는 프런트엔드 실행 절에 한 줄만 둔다
- [x] `deferred-work-resolutions.md` -- 50·83 해소와 상태 요약 갱신
- [x] 첫 관측 가드(리뷰 추가) -- `frontend/src/test/firstSight.ts`의 `atFirstSight`가 act 환경을 끈 채 응답을 풀고, 새 내용이 처음 보이는 `MutationObserver` 마이크로태스크에서 효과의 결과를 읽는다. 종류마다 대표 하나: `SignedInScreen.test.tsx`의 목적지 이동(`useSessionGate`)과 401 뒤 제목 초점(`PlanResultPanel`), `InitialInputsCollection.test.tsx`의 발행 폴링 interval(`PlanPublicationSection`). 각 effect를 `useEffect`로 되돌리면 매번 실패함을 확인했다
- [x] `UnlinkDialog` StrictMode(리뷰 추가) -- 호출점을 한 번만 기록한다. `UnlinkDialog.test.tsx`에 `StrictMode` 안에서 `Esc` 뒤 호출점 복귀, 화면이 같은 커밋에서 결과 문장으로 옮겨 받은 포커스를 덮지 않음(포커스 상실 조건) 두 시험을 더했다. 이 두 묶음으로 시험 수는 866에서 871이 된다

**Acceptance Criteria:**
- Given 이 변경, when 검증 명령 여섯(typecheck·lint·lint:css·format:check·test·build)을 돌리면, then 모두 통과하고 시험 수는 871이다(기존 866건이 모두 남고 첫 관측 가드 3건과 `UnlinkDialog` 시험 2건이 더해진다).
- Given 16개 이상의 CPU 부하, when `bash frontend/scripts/stress-test.sh`로 전체 Vitest를 12회 이상 돌리면, then 실패한 실행이 0이다. 같은 조건의 기준선은 12회 중 2회 실패였다.
- Given 운영 코드, when 본문의 주된 일이 초점 이동·`replaceLocation`·타이머·리스너 등록인 effect를 찾으면, then 모두 `useLayoutEffect`다. 요청을 시작하는 effect는 passive로 남는다. 그 요청의 기한 타이머나 `pageshow` 재확인을 함께 붙이는 것도 같다. passive로 남은 effect에는 모두 근거 주석이 있다.
- Given 스크립트, when 중간에 Ctrl-C로 끊거나 끝까지 돌리면, then `yes` 부하 프로세스가 남지 않는다.

## Design Notes

**왜 layout인가.** layout effect는 DOM 변경과 같은 작업 안에서 돈다. 시험이 그 DOM을 관측하는 MutationObserver 콜백(마이크로태스크)보다 항상 먼저다. passive effect는 동기 lane 갱신에서만 커밋 끝에 바로 돈다. fetch 응답이 만든 default lane 갱신에서는 Scheduler의 다음 작업으로 미뤄진다. 사용자에게도 같은 차이가 있다. passive 초점 이동은 한 프레임 동안 초점을 `<body>`에 둔다.

**부정 단정은 그대로 둔다.** 늦은 효과가 부정 단정을 실패시킬 수는 없다. 다만 효과가 늦으면 잘못된 호출이 단정 뒤에 와서 조용히 통과한다. 효과를 layout으로 옮기는 쪽이 이 문제를 고친다.

**PR #57과 겹친다.** 열려 있는 PR #57(Story 2.13)이 `PlanResultPanel`의 같은 두 효과를 같은 방식으로 옮겼다. `SyncControl`·두 화면 시험·`useDashboardSync`도 함께 고친다. 이 브랜치의 `PlanResultPanel` 변경은 #57과 같은 줄 모양으로 둔다. 충돌은 나중에 머지하는 쪽이 푼다. #57이 더하는 새 효과에도 같은 규칙을 적용한다.

**`inert` 형제를 둔 대화상자(구현 중 발견).** `UnlinkDialog`의 effect를 layout으로 옮기면 닫힐 때의 호출점 복귀(그 effect의 정리)가 커밋의 DOM 변경 단계에서 돈다. React는 부모의 자식 삭제를 그 부모의 다른 자식 갱신보다 먼저 처리하므로, `SettingsScreen`이 대화상자를 직접 자식으로 두면 복귀가 형제 `<div inert>`의 속성 해제보다 먼저 돌고 Chromium은 비활성 요소의 `focus()`를 무시한다 — `Esc`·취소 뒤 초점이 `<body>`로 떨어진다. jsdom은 `inert`를 모르므로 Vitest는 통과하고, `e2e/auth-entry.spec.ts`의 해제 대화상자 시험(`PLAYWRIGHT_BASE_URL` 주입 실행, CI `same-origin-integration`)에서만 드러난다. 구현 에이전트는 처음에 화면이 대화상자를 늘 있는 `Fragment` 한 겹 아래에 두어 삭제를 형제 갱신 뒤로 보냈다. 실제 Chromium에서 직접 자식이면 닫힌 뒤 초점이 `<body>`, `Fragment` 아래면 호출점이었다. 그러나 이 방법은 React의 삭제 처리 순서에 기대고, 누군가 "쓸모없는" `Fragment`를 걷으면 Playwright에서만 조용히 깨진다. 그래서 대신 효과를 갈랐다. 기본 포커스·키 처리기·스크롤 잠금은 layout이다. 호출점 복귀만 passive effect의 정리이고 커밋이 끝난 뒤(`inert` 해제 뒤) 돈다. 이산 입력(`Esc`·취소)이 닫으면 React가 커밋 끝에서 passive를 바로 비우므로 늦지 않다. 복귀에는 "포커스를 잃었을 때만" 조건을 더했다. 해제 성공 커밋에서 대화상자는 `restoreFocus=true`인 채로 사라지고(마지막으로 열려 있던 렌더의 값), 화면의 layout effect가 이미 결과 문장으로 옮긴 포커스를 뒤늦은 passive 복귀가 덮으면 안 되기 때문이다. 이 조건은 `PlanResultPanel`·`SyncControl`의 복구 관용과 같다. 이것은 SPEC Always의 "본문 그대로"에서 벗어난 유일한 변경이다. PR #57의 `PlanEndDialog`도 `<div inert>` 형제이고 같은 커밋에서 layout으로 옮겨졌다. #57의 CI run 37143229430 `same-origin-integration`이 `e2e/plan-end.spec.ts:232`의 `toBeFocused`(받은 값 `inactive`)에서 실패한 것이 이 결함으로 보인다.

## Verification

**Commands:**
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run test && npm --prefix frontend run build` -- expected: 모두 통과, 871건(기존 866 + 가드 3 + `UnlinkDialog` 2)
- `bash frontend/scripts/stress-test.sh` -- expected: 12/12 통과, 부하 프로세스 잔존 0(`pgrep -x yes` 빈 출력)
- `bash -n frontend/scripts/stress-test.sh` -- expected: 구문 오류 없음

## 측정

| 실행 | 조건 | 결과 |
|---|---|---|
| 기준선(`7245371`) | `yes` 16개, 전체 Vitest 12회 | 2/12 실패. 1회차 `InitialInputsCollection` 403 시험(`reads` 7≠2, 폴링 해제가 늦음), 9회차 같은 파일의 보류 흐름 시험(상태 영역을 텍스트보다 먼저 잡음) |
| 기준선 재측정(`7245371`, `stress-test.sh`) | `yes` 16개, 전체 Vitest 12회 | 1/12 실패. 1회차 `SignedInScreen` AC-13(401 뒤 초점이 `<body>`). 스크립트가 같은 종류의 실패를 재현함을 확인 |
| 첫 구현(`Fragment` 배치, 주석만 다른 트리) | `yes` 16개, 전체 Vitest 24회 | 24/24 통과, 866건. 끝난 뒤 `yes` 잔존 0 |
| 첫 구현(`Fragment` 배치) | `yes` 16개, 전체 Vitest 12회(기본값) | 12/12 통과, 866건. 끝난 뒤 `yes` 잔존 0 |
| `UnlinkDialog` 복귀 분리 뒤(리뷰 전) | `yes` 16개, 전체 Vitest 12회, 리뷰어 셋이 동시에 돎 | 12/12 통과, 866건. 끝난 뒤 `yes` 잔존 0 |
| 중단 | 3회 중 1회차에서 프로세스 그룹에 SIGINT | 종료 코드 130, `yes`·Vitest 잔존 0 |
| 첫 관측 가드 증명(되돌림) | 가드마다 그 effect 하나만 `useEffect`로 되돌리고 가드 파일 10회, 부하 없음 | 목적지 이동 10/10, 401 초점 10/10, 발행 interval 10/10 실패. 매회 실패한 시험은 그 가드 하나 |
| 첫 관측 가드 증명(복원) | 같은 파일 10회씩, 부하 없음 / `stress-test.sh --runs 3 -- <파일>`(`yes` 16개) | 10/10 통과(세 가드 각각) / `SignedInScreen.test.tsx` 3/3, `InitialInputsCollection.test.tsx` 3/3 통과 |
| 최종 트리(리뷰 반영 뒤) | `yes` 16개, 전체 Vitest 36회(`stress-test.sh --runs 36 --load 16`) | 36/36 통과, 871건. 끝난 뒤 `yes` 잔존 0 |
| 스크립트 신호(리뷰 반영 뒤) | 전체 Vitest 도중 프로세스 그룹에 SIGINT / 스크립트 PID에만 SIGTERM | 각각 1초 안에 130·143으로 종료, `yes`·Vitest 잔존 0 |

## Suggested Review Order

**효과 시점 규칙 (진입점)**

- 규칙의 정본. 무엇이 layout이고 무엇이 passive로 남는가
  [`DEVELOPMENT.md:149`](../../DEVELOPMENT.md#L149)

- 서버가 고른 목적지로의 이동. "이동 중"을 그린 커밋에서 떠난다
  [`useSessionState.ts:498`](../../frontend/src/session/useSessionState.ts#L498)

- 요청을 여는 effect는 passive로 남고, 같이 붙는 `pageshow`도 그 요청의 수명이다
  [`useSessionState.ts:345`](../../frontend/src/session/useSessionState.ts#L345)

**`inert` 배경으로 돌아가는 초점 (위험이 가장 큼)**

- 열기는 layout이고 호출점은 한 번만 기록한다(StrictMode 재실행 대비)
  [`UnlinkDialog.tsx:77`](../../frontend/src/components/UnlinkDialog.tsx#L77)

- 복귀만 passive 정리로 둔다. `inert`가 풀린 뒤이고, 초점을 잃었을 때만 복귀한다
  [`UnlinkDialog.tsx:173`](../../frontend/src/components/UnlinkDialog.tsx#L173)

- 왜 이 하나만 예외인가. `Fragment` 배치 대신 순서 의존이 없는 분리를 골랐다
  [`DEVELOPMENT.md:150`](../../DEVELOPMENT.md#L150)

**폴링·타이머**

- 기준선 실패(`reads` 7≠2)의 원인. interval이 상태를 그린 커밋에서 걸리고 풀린다
  [`PlanPublicationSection.tsx:150`](../../frontend/src/components/PlanPublicationSection.tsx#L150)

- 같은 관용의 수집 폴링. 판정·환산 폴링도 같다
  [`InitialInputsCollection.tsx:758`](../../frontend/src/components/InitialInputsCollection.tsx#L758)

- latest-ref를 layout으로 옮겨 뒤의 타이머·리스너가 같은 커밋의 값을 본다
  [`useDashboardSync.ts:577`](../../frontend/src/session/useDashboardSync.ts#L577)

- 가시성 리스너. 세션 게이트의 리스너와 같은 원리다
  [`useDashboardSync.ts:763`](../../frontend/src/session/useDashboardSync.ts#L763)

**초점 이동**

- 401 뒤 제목 초점. PR #57과 같은 줄 모양으로 둬서 충돌을 줄였다
  [`PlanResultPanel.tsx:472`](../../frontend/src/components/PlanResultPanel.tsx#L472)

- 자식 → 부모 순서를 지키도록 두 effect를 함께 옮겼다
  [`SyncControl.tsx:310`](../../frontend/src/components/SyncControl.tsx#L310)

- 부모 갱신을 layout에서 불러도 되는 경우. 일회성 표지만 지운다
  [`AdjustmentHistory.tsx:561`](../../frontend/src/components/AdjustmentHistory.tsx#L561)

**시험**

- 결정적 첫 관측 가드. 부하 없이 layout과 passive를 가른다
  [`firstSight.ts:24`](../../frontend/src/test/firstSight.ts#L24)

- 목적지 이동·401 초점 가드. 되돌리면 10/10 실패한다
  [`SignedInScreen.test.tsx:1848`](../../frontend/src/screens/SignedInScreen.test.tsx#L1848)

- 폴링 interval 가드
  [`InitialInputsCollection.test.tsx:1130`](../../frontend/src/components/InitialInputsCollection.test.tsx#L1130)

- StrictMode에서 `Esc` 뒤 호출점으로 복귀한다
  [`UnlinkDialog.test.tsx:278`](../../frontend/src/components/UnlinkDialog.test.tsx#L278)

- 같은 커밋에서 화면이 옮긴 초점을 덮지 않는다
  [`UnlinkDialog.test.tsx:292`](../../frontend/src/components/UnlinkDialog.test.tsx#L292)

- `waitFor` 전환의 대표. 기대값은 그대로이고 대기 방식만 바뀌었다
  [`SignedInScreen.test.tsx:589`](../../frontend/src/screens/SignedInScreen.test.tsx#L589)

**도구·문서**

- 신호가 오면 도는 Vitest와 부하를 끝낸다. 기록되지 않은 `yes`는 `pkill -P`로 거둔다
  [`stress-test.sh:187`](../../frontend/scripts/stress-test.sh#L187)

- 스크립트를 쓰는 법과 회수 기준(12회는 최소)
  [`DEVELOPMENT.md:111`](../../DEVELOPMENT.md#L111)

- 미룬 항목 50·83의 해소 판정과 근거
  [`deferred-work-resolutions.md:399`](deferred-work-resolutions.md#L399)
