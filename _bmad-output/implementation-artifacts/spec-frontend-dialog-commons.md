---
title: '프런트엔드 공통 부품 통합: 확인 대화상자 메커니즘·참조 조회 엔진·isInstant, 그리고 재인증 게이트 안내'
type: 'refactor'
created: '2026-10-06'
status: 'done'
review_loop_iteration: 0
baseline_commit: 'ac61bec5482c0055b2d1460fec0c2623ea6313bb'
context:
  - '{project-root}/DEVELOPMENT.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Epic 2 회고 AI-8의 프런트엔드 묶음(D-4, `deferred-work.md` 104번)이다. 확인 대화상자 셋(`UnlinkDialog`·`PlanEndDialog`·`ConnectionRevokeDialog`)이 호출점 기록·기본 포커스·스크롤 잠금·capture 키 처리·backdrop·passive 초점 복귀를 각자 들고 있고(PlanEnd와 Revoke는 코드가 글자 그대로 같다), CSS 모듈 둘은 199줄 중 keyframes 이름만 다르다. 조회 훅 둘(`useAdjustmentHistory`·`useCalculationEvidence`)은 같은 sequence·abort·`latest()`·상태 코드 매핑을 복사했고 진행 중 요청 처리만 갈렸다. `isInstant`는 엄격판(`useDashboard`)과 느슨판(`PlanConditionPanel`)이 다른 뜻이다. Story 3.1의 운영자 확인 대화상자가 이 위에 얹히면 넷째 사본이 생긴다. 그리고 PR #71이 "민감 command 실행 성공은 재인증 시각을 지운다"를 계약으로 만들었는데 화면은 그 사실을 성공 문구 어디에도 말하지 않아 사용자가 다음 조작에서 처음 거절을 만난다(`deferred-work.md` 105번).

**Approach:** 대화상자의 메커니즘을 훅 하나(`useConfirmationDialog`)로 뽑고 셋이 위임한다 — 의도적으로 다른 것(status 표시 방식, `restoreFocus`의 의미, 확정 요청 수명주기, 대체 포커스 대상)은 매개변수로 남긴다. CSS는 공유 모듈 하나로 합치고 Unlink도 그 모듈을 쓴다. 조회 훅은 상태 기계를 각자 두고 공통 엔진(`useReferencedRequest`)만 뽑되 동시성 정책(교체/무시)을 매개변수로 남겨 동작을 바꾸지 않는다. `isInstant`는 엄격판 하나를 `session/instant.ts`로 옮기고 느슨판을 지운다. 재인증 게이트는 성공 문구와 본인 확인 구역 본문에 "다음 민감 조작 전 본인 확인을 다시 한다"를 넣고, 새 계약과 모순되는 시험을 바로잡고, 성공 → 다음 준비 403 → 안내 흐름을 시험으로 고정한다.

## Boundaries & Constraints

**Always:**
- `DEVELOPMENT.md` `### 구조와 규칙`의 effect 시점 규칙을 지킨다 — 기본 포커스·키 처리기·스크롤 잠금·latest-ref 갱신은 `useLayoutEffect`, `inert` 배경으로 돌려주는 초점 복귀만 근거 주석이 붙은 passive `useEffect` 정리다. 훅으로 옮겨도 각 효과의 시점은 그대로다.
- 호출점은 한 번만 기록(`??=`, StrictMode)하고, 복귀는 포커스를 잃었을 때만 하며(`lost` 판정 셋 동일), `restoreFocus`와 대체 대상은 각 대화상자가 넘긴다.
- ARIA(`role="dialog"`·`aria-modal`·`aria-labelledby`·`aria-describedby`, 버튼 `aria-disabled`), 취소 버튼 문서 순서 우선, backdrop 클릭으로 닫히지 않음, Escape의 pending 가드는 그대로다. `inert`는 화면이 소유한다.
- 조회 훅의 시험(`useAdjustmentHistory.test.tsx`·`useCalculationEvidence.test.tsx`)과 대화상자 시험은 단정을 바꾸지 않고 통과한다. 바뀌는 단정은 `SettingsConnectionRevoke.test.tsx:431`(연결 직후 해제 준비를 성공으로 stub한 것 → 403 `REAUTHENTICATION_REQUIRED`) 하나다.
- 화면 카피는 `frontend/src/content/`가 소유한다. 실시간·정확 보장·축하·공포·스트릭 표현 금지. 고지 인접 배치 유지.
- 디자인 토큰만 쓴다. 공유 CSS 모듈은 PlanEnd 판을 기준으로 하고 keyframes 이름은 하나다.
- 외부 요청 0건·브라우저 저장소 금지 등 브라우저 경계 불변. 런타임 의존 추가 없음.
- 새로 쓰거나 고치는 주석은 한국어다.

**Ask First:**
- `ConnectionRevokeDialog`의 `REAUTHENTICATION_REQUIRED`를 계획 종료처럼 제자리 본인 확인 버튼으로 바꾸는 것(이번에는 하지 않는다 — 일반 거절 문구 유지).
- 조회 훅의 동시성 정책을 한쪽으로 통일하는 것.

**Never:**
- 세 대화상자의 status 표시 방식 통일, `useDashboard`·`useDashboardSync`·`NextPlanBaselinePanel`·`PlanHistoryPanel`·`PlanConditionPanel`의 읽기 경로를 새 엔진으로 옮기는 것(별도 정리), 복귀 목적지 문제(`deferred-work.md` 192), 백엔드 변경, 새 화면 경로, CSP 변경.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 대화상자 열림 | 셋 중 하나 마운트 | 취소에 기본 포커스, body 스크롤 잠금, Tab 가둠, Esc → `onCancel` | pending이면 Esc 무시 |
| 닫힘 뒤 복귀 | `restoreFocus` 참·포커스 잃음·호출점 연결됨 | 호출점으로 복귀 | 호출점 없으면 대체 id → 없으면 복귀 안 함 |
| 같은 커밋의 화면 포커스 | 화면이 결과 문장으로 포커스 이동 | 대화상자가 덮지 않음 | N/A |
| 조회 중 묶음 변경 | key 변경 | in-flight abort, `closed` | 늦은 응답 무시 |
| 같은 tick 두 번 open | Adj·Ev 각각 | Adj 1회·Ev 1회 요청(기존 시험 그대로) | N/A |
| 느슨한 시각 | `PlanConditionPanel` 활성 계획의 `startAt`이 `'yesterday'` | 거절(기존 시험 유지), Z 형식만 통과 | blocked 문구 |
| 성공 → 다음 준비 | 연결·해제·Bybit 연결 성공 직후 다음 준비 요청이 403 | 거절 문구 표시, 본인 확인 버튼 3개 활성, 포커스 이동 없음 | polite region |

</frozen-after-approval>

## Code Map

기준 `frontend/src/`.

- `components/UnlinkDialog.tsx`(latest-ref 69-72, 여는 layout effect 77-152, 복귀 154-188, backdrop 200-208), `components/PlanEndDialog.tsx`(61-64, 71-123, 125-159 대체 id는 reason별, 165-170), `components/ConnectionRevokeDialog.tsx`(44-47, 52-98, 100-118 대체 id `revocationContent.buttonId`, 123-127) -- 메커니즘 사본 셋. `restoreFocus`는 Unlink `notice !== 'unlinkSucceeded'`, Revoke `notice?.kind !== 'revoked'`, PlanEnd `!dialog.confirming`.
- `components/PlanEndDialog.module.css` vs `ConnectionRevokeDialog.module.css` -- 2·8·118·121행(keyframes 이름)만 다름. `UnlinkDialog.module.css` -- 옛 판(center 정렬, 30rem, `.state`·`.marker`·reduced-motion 없음). 공유 모듈은 PlanEnd 판 기준.
- `inert` 소유: `screens/SettingsScreen.tsx:279,333`, `SignedInScreen.tsx:113`, `OnboardingScreen.tsx:107`.
- `session/useAdjustmentHistory.ts` -- key 390-393, 렌더 중 리셋 402-405, layout 정리 409-416, `read()` 430(교체: abort 뒤 새 요청), `latest()` 439·458·469, `open()` 489 `inFlight===null` 합류, 실패 시 `list: held` 444-449.
- `session/useCalculationEvidence.ts` -- 706-709, 718-721, 725-732, `read()` 736(무시: in-flight면 return), 745·758·769, `open()` 784, `endedAt` 780.
- 상태 코드 매핑 공통: 401/403 → `denied{signedOut|refused}` + `onDenied()`, 404 → 재시도 불가, 파싱·catch → retryable, abort → settle 안 함.
- `session/useDashboard.ts:217-223` `isInstant`(export, 엄격) -- 사용처 약 13곳 + `useCalculationEvidence`·`useAdjustmentHistory`·`session/planEnd.ts:178`·`PlanHistoryPanel.tsx:50`·`NextPlanBaselinePanel.tsx:33,41`·`EvidenceParts.tsx:27`. `components/PlanConditionPanel.tsx:68-70` 느슨판 -- `isActivePlan` 76-88만 씀. 시험 `PlanConditionPanel.test.tsx:298-320`.
- 재인증 게이트: 403 코드 분기는 `'REAUTHENTICATION_REQUIRED'`(Bybit 해제 `useBybitConnection.ts:299-304`, 계획 종료 `usePlanEnd.ts:121`)뿐. 연결·해제·Bybit 연결 준비는 코드 없는 403 → `'failed'`/`rejected`. 본인 확인 버튼 3개는 `SettingsScreen.tsx:702-729`에 상시. 문구: `content/auth.ts:466,475,517-518,531-532,837`, `content/connection.ts:97,247,306-307`.
- 시험: `UnlinkDialog.test.tsx`, `PlanEndDialog.test.tsx`(F4 대체 225, StrictMode 236), `screens/SettingsConnectionRevoke.test.tsx`(224·243·263·398·418, **431**), `SettingsScreen.test.tsx:409`, `SettingsConnection.test.tsx:177`, e2e `auth-entry.spec.ts:881-1021`, `plan-end.spec.ts:140-233`. Revoke 단위 시험·e2e 없음.
- `scripts/stress-test.sh` -- CPU×2 부하 12회. `DEVELOPMENT.md:149-151` 규칙.

## Tasks & Acceptance

**Execution:**
- [x] `components/useConfirmationDialog.ts`(신규) -- `{surface, cancelButton, isPending, onCancel, restoreFocus, fallbackTargetId?}`(함수형 getter는 latest-ref로) → 호출점 기록·기본 포커스·스크롤 잠금·capture keydown(Esc·Tab)·`onBackdropMouseDown`·passive 복귀 정리. 효과 시점과 근거 주석은 원본 그대로 옮긴다.
- [x] `UnlinkDialog.tsx`·`PlanEndDialog.tsx`·`ConnectionRevokeDialog.tsx` -- 메커니즘을 훅에 위임, 마크업·status 방식·props 불변.
- [x] `components/ConfirmationDialog.module.css`(신규, PlanEnd 판) -- 셋이 import; `PlanEndDialog.module.css`·`ConnectionRevokeDialog.module.css`·`UnlinkDialog.module.css` 삭제. Unlink의 시각 차이(32rem·상단 정렬·`overflow-wrap`)는 PlanEnd 판을 따른다.
- [x] `session/useReferencedRequest.ts`(신규) -- `{key, onDenied}` → `{start(fetcher, {concurrency:'replace'|'ignore'}), close(), inFlight}`; sequence·keyRef·layout 정리 abort·`latest()` 두 번 검사·401/403/404/파싱/abort 매핑을 소유. `useAdjustmentHistory`(replace)·`useCalculationEvidence`(ignore)가 위임하고 view 상태 기계는 유지.
- [x] `session/instant.ts`(신규) -- 엄격 `isInstant`; `useDashboard.ts`는 re-export 유지, `PlanConditionPanel.tsx` 느슨판 삭제·import.
- [x] `content/auth.ts`(`linkSucceeded`·`unlinkSucceeded`·본인 확인 구역 본문 837)·`content/connection.ts`(`succeeded`·`revoked` 성공 문구) -- "다음 민감 조작 전에는 본인 확인을 다시 한다" 한 문장, 본문은 Bybit 연결·해제를 포함.
- [x] 시험 -- `useConfirmationDialog.test.tsx`(스크롤 잠금·StrictMode·대체 id·복귀 안 함), `ConnectionRevokeDialog.test.tsx`(신규: 기본 포커스·가둠·Esc·스크롤·복귀), `useReferencedRequest.test.tsx`(key 변경 abort·늦은 응답·401/403/404), `SettingsConnectionRevoke.test.tsx:431` 수정, "성공 → 다음 준비 403 → 문구·버튼 활성·포커스 불변" ×3(`SettingsScreen`·`SettingsConnection`·`SettingsConnectionRevoke`), e2e `auth-entry.spec.ts`에 연결 성공 뒤 재인증 없이 해제 준비 → 거절 문구 1단계(기존 재인증 단계 앞에).
- [x] `CLAUDE.md` -- 프런트엔드 절에 공통 부품 셋 이름 한 줄; `deferred-work-resolutions.md`는 done 전환 PR 몫.

**Acceptance Criteria:**
- Given 세 대화상자 시험·e2e가 있을 때, when 전체를 돌리면, then 단정 변경 없이 통과한다(431행 하나 제외).
- Given `grep -rn "document.body.style.overflow\|addEventListener('keydown'" frontend/src/components`, then `useConfirmationDialog.ts` 한 곳이다.
- Given `grep -rn "function isInstant" frontend/src`, then `session/instant.ts` 한 곳이다.
- Given 연결 성공 직후 해제 준비가 403일 때, when 화면이 받으면, then 거절 문구가 polite region에 보이고 본인 확인 버튼 3개가 활성이며 포커스는 움직이지 않는다.
- Given `bash frontend/scripts/stress-test.sh -- src/components src/session`, then 12회 모두 통과한다.

## Design Notes

- 훅이 `onCancel`·`isPending`·`restoreFocus`를 latest-ref로 받는 이유: 원본 셋이 의존성 없는 layout effect 한 번으로 리스너를 걸고 `latest.current`로 최신 props를 읽는다. 훅도 같은 구조여야 리스너 재등록 없이 pending 가드가 최신 값을 본다.
- 복귀 정리가 passive인 근거(호출점이 `inert` 아래, layout 정리는 `inert` 해제보다 먼저 돌아 `focus()`가 무시됨, jsdom은 못 보고 Playwright만 봄)는 훅의 주석으로 한 번만 남긴다. 대화상자 셋의 긴 주석은 훅을 가리키는 한 줄로 줄인다.
- CSS Modules는 `@keyframes`도 스코프하므로 공유 모듈 하나에 이름 하나면 충돌이 없다. Unlink가 PlanEnd 판으로 옮겨가며 바뀌는 시각은 넷이다 — 겹의 상단 정렬(`align-items: flex-start` + `margin-block: auto`, 400% 확대에서 위가 잘리지 않는다), 최대 폭 30rem → 32rem, `.dialog`의 `overflow-wrap: anywhere`(긴 토큰이 320px에서 접힌다), `.destructive`의 굵기 `--type-body-font-weight` → `--type-label-font-weight`(삭제되는 것의 문단이 굵어진다; 셋 통일이 의도이고 PlanEnd 판을 유지한다). e2e의 44px·320px·axe 단정은 그대로 성립해야 한다. `.dialog h2, .dialog p { margin: 0 }`은 `global.css`가 이미 리셋하므로 두지 않는다.
- 조회 엔진의 `concurrency`를 매개변수로 두는 이유: Adj는 filter 변경에 교체가 필요하고(`useAdjustmentHistory.test.tsx:100`), Ev는 같은 tick 두 번 open에 1회 요청을 단정한다(`useCalculationEvidence.test.tsx:70-80`). 통일은 Ask First.
- 재인증 게이트 안내는 "다음 조작에서 처음 거절을 만나는" 것을 없애는 최소 변경이다. Bybit 해제의 전용 처리(계획 종료처럼 제자리 버튼)는 하지 않는다 — 본인 확인 구역이 같은 화면 아래에 상시 있어 "아래에서" 문구가 가리킨다.

## 구현에서 확정한 것

- `useConfirmationDialog`는 void이고 겹 클릭 방어 `onBackdropMouseDown`은 훅 상태와 무관해 모듈에서 직접 내보내며 세 대화상자가 겹에 붙인다. 호출점은 `useRef<Element | null | undefined>(undefined)`에 `=== undefined`일 때만 기록한다 — `??=`는 `activeElement`가 `null`이었던 열기에서 StrictMode 재실행이 다시 기록한다. 스크롤 복원값도 `savedOverflow` ref에 한 번만 적어 호출자가 렌더마다 새 ref 객체를 넘겨 effect가 재실행돼도 `hidden`을 이전 값으로 적지 않는다.
- `useReferencedRequest({key, onDenied, concurrency})`: 동시성 정책은 훅 인자다. 결말은 언제나 `start` 반환 뒤 비동기로 온다 — `fetch`는 `start` 안에서 동기로 부르고(중단 손잡이와 관측된 요청이 `start` 직후 성립해야 두 조회 훅의 기존 단정 `inits[0]?.signal?.aborted`가 그대로 통과한다) 동기 throw만 거부된 약속으로 바꿔 같은 `await` 뒤로 보낸다. 본체 전체를 `Promise.resolve().then`에 거는 안은 요청 자체가 한 마이크로태스크 늦게 나가 그 단정들을 깨므로 택하지 않았다, `settle` 래퍼가 `controller` 정리 뒤 호출자의 `settle`을 try/catch로 감싸 호출자 오류가 두 번째 결말(`failed`)이 되지 않는다(401/403의 `denied`가 뒤집히던 경로). `onDenied`는 결말 시점의 `onDeniedRef.current`다. 두 훅은 `loading`을 세운 뒤 `start`를 부르고(원본 순서), 계산 근거는 `request.inFlight()`를 먼저 본다. 시험은 고정 시간 대기 대신 "뒤 조회의 결말을 기다린 뒤 앞 결말 미호출"로 단정하고, 본문 지연 `ReadableStream` Response로 두 번째 `latest()` 검사를 고정한다.
- `isInstant`의 정본은 `session/instant.ts` 하나이고 `useDashboard`의 re-export는 두지 않는다. 느슨한 시각 판정 사본 넷(`PlanResultPanel.validTime`, `AdjustmentHistory.validTime`, `InitialInputsCollection`의 인라인 둘, `bybitConnection.revokedAt`)도 `isInstant`로 바꿨다 — 기계 값(`dateTime`)은 서버 형식에만 붙고 표시는 형식기의 폴백 문장이 받는다. `instant.test.ts`가 통과·거절 표를 고정한다.
- 재인증 게이트 안내: 성공 문구 넷과 본인 확인 구역 본문(로그인 수단 연결·해제, Bybit 연결·해제, 계획 중도 종료)에 "본인 확인 1회는 민감한 조작 1건" 문장을 넣었다. 화면 시험 셋의 "성공 → 다음 준비 403" 단정은 `src/test/reauthGate.ts`의 `expectReauthGateRejection(message, focused)` 하나이고, 403 fixture는 공통 오류 계약 모양(`code`는 서버가 이유를 접은 `FORBIDDEN`, Bybit 해제만 `REAUTHENTICATION_REQUIRED`)이다. e2e의 새 단계는 주입 origin에서만 돈다.
- 리뷰 patch 21건(2026-10-07): 위 넷과 `ConnectionRevokeDialog.test.tsx`의 메커니즘 시험 제거(훅 시험이 소유), `PlanEndDialog`·`ConnectionRevokeDialog`의 손댄 주석 문단 한국어화, `DEVELOPMENT.md`·`CLAUDE.md`의 범위 문장 보정.

## Verification

**Commands:**
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check` -- expected: 모두 0.
- `npm --prefix frontend run test` -- expected: 통과, 시험 수 ≥ 기준(`ac61bec`).
- `bash frontend/scripts/stress-test.sh -- src/components src/session` -- expected: 종료 코드 0.
- `npm --prefix frontend run test:e2e` -- expected: 통과(자체 preview; 주입 origin 시험은 CI가 돈다).
- `grep -rn "function isInstant" frontend/src` -- expected: 1건. `grep -rln "keyframes" frontend/src/components/*.module.css` -- expected: 대화상자 keyframes는 `ConfirmationDialog.module.css` 1곳.

## Suggested Review Order

**확인 대화상자 메커니즘 한 벌**

- 진입점 — layout(기본 포커스·키·스크롤 잠금)과 passive 복귀(195행)의 시점 분리가 원본 그대로다
  [`useConfirmationDialog.ts:57`](../../frontend/src/components/useConfirmationDialog.ts#L57)
- 세 대화상자가 같은 한 호출로 위임하고 `restoreFocus`·대체 id만 다르다
  [`PlanEndDialog.tsx:54`](../../frontend/src/components/PlanEndDialog.tsx#L54)
- CSS는 PlanEnd 판 하나; Unlink의 시각 차이 넷은 Design Notes 참조
  [`ConfirmationDialog.module.css:12`](../../frontend/src/components/ConfirmationDialog.module.css#L12)

**참조 조회 엔진**

- sequence·abort·`latest()` 두 번·상태 코드 매핑을 소유, 결말은 언제나 `start` 반환 뒤 1회(리뷰 patch)
  [`useReferencedRequest.ts:70`](../../frontend/src/session/useReferencedRequest.ts#L70)
- 동시성 정책은 훅 인자 — Adj `replace`, Ev `ignore`, view 상태 기계는 각자 유지
  [`useAdjustmentHistory.ts:398`](../../frontend/src/session/useAdjustmentHistory.ts#L398)

**시각 판정 한 벌**

- 엄격판 정본; 느슨한 사본 다섯(PlanConditionPanel·PlanResultPanel·AdjustmentHistory·InitialInputsCollection·bybitConnection)이 이것으로
  [`instant.ts:9`](../../frontend/src/session/instant.ts#L9)

**재인증 게이트 안내(`deferred-work.md` 105)**

- 성공 문구가 "다음 민감 조작 전 본인 확인"을 먼저 말한다
  [`connection.ts:254`](../../frontend/src/content/connection.ts#L254)
- 성공 → 다음 준비 403 → 안내·버튼 활성·초점 불변의 공통 단정
  [`reauthGate.ts`](../../frontend/src/test/reauthGate.ts)
- 주입 origin에서만 도는 브라우저 단정
  [`auth-entry.spec.ts`](../../frontend/e2e/auth-entry.spec.ts)

**문서**

- 공통 부품 셋과 읽기 경로 이전의 범위 한정
  [`CLAUDE.md:87`](https://github.com/sh-9610/BokriLog/blob/a75d08e3e0e6f4974f368994bc2825822dd9ec65/CLAUDE.md#L87)
