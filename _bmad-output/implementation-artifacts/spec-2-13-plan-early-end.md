---
title: '활성 계획의 명시적 중도 종료와 읽기 전용 기록 보존'
type: 'feature'
created: '2026-10-03'
status: 'done'
baseline_commit: '72453717fa47f5c812c62ee08c11e7eae2b77198'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics.md'
  - '{project-root}/_bmad-output/planning-artifacts/EPIC-IMPLEMENTATION-READINESS.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-BokriLog-2026-08-23/EXPERIENCE.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-12-precision-reverification.md'
---

<frozen-after-approval reason="승인된 Epic 2 Story 2.13 범위, ERD-1.0.0 §5 SECURITY-ACTION-1.0, AD-16·AD-20·AD-23, UX confirmation-dialog·계획 종료 표면">

## Intent

**Problem:**
- 활성 계획은 끝낼 방법이 없다. 계획 상태는 `ACTIVE` 하나뿐이고(`V10` CHECK) 종료 시각·사유·마지막 정상 근거를 담을 자리가 없다.
- 장기 미동기화(30일 초과)의 `새 계획 시작`은 설명만 있고 실행할 수 없다(2.11·2.12).
- 대시보드·계산 근거·조정 이력은 활성 계획만 읽는다. 그래서 계획이 끝나면 그 기록을 볼 길이 없다.

**Approach:**
- 대시보드의 계획 영역에 `계획 중도 종료`를 둔다. 장기 미동기화 선택지에는 `새 계획 시작` 버튼을 둔다.
- 두 버튼은 같은 흐름이다: 최근 재인증(1.8) → `plan-end` 일회성 허가 발급(1.9) → 확인 대화상자 → 확정.
- 확정은 허가 소비와 같은 트랜잭션 하나에서 다음을 함께 커밋한다:
  - 계획을 `ENDED_EARLY`로 바꾼다. 이것이 활성 계획 포인터 해제다.
  - 종료 기록을 쓴다: 사유, 서버 종료 시각, 마지막 정상 공식 계산·snapshot 참조, 완료 비교 상태와 미수집 구간, 멱등 키.
- 계획이 끝나면 대시보드는 새 상태 `ENDED`로 그 계획의 마지막 공식 결과를 읽기 전용 기록으로 보인다. 근거·이력도 같은 기록을 읽는다.

## Boundaries & Constraints

**Always:**
- **이름.**
  - 계획 상태: `ACTIVE` → `ENDED_EARLY`. 되돌아가는 전이는 없다. `COMPLETED`는 2.14가 더한다.
  - 종료 사유 `reason`은 둘로 닫혀 있다:
    - `USER_REQUEST`: 사용자 중도 종료.
    - `LONG_GAP_NEW_PLAN`: 장기 미동기화 후 새 계획 선택.
  - 완료 비교 `completion`도 둘이다:
    - `NOT_APPLICABLE`: 중도 종료는 완료 비교 대상이 아니다.
    - `UNAVAILABLE_DATA_GAP`: 데이터 공백으로 계산 불가.
    - `LONG_GAP_NEW_PLAN`일 때만, 그리고 반드시 `UNAVAILABLE_DATA_GAP`이다.
  - 허가 command와 운영 제한 명령은 `plan-end`다. 허가 target은 `planId`다.
- **자격**(AC-1). 서버가 판정하며, 순서대로 본다:
  1. 계정 쓰기 접근. 없으면 403.
  2. 대상이 그 사용자의 `ACTIVE` 계획인가. 아니면 404 `PLAN_NOT_FOUND`.
     - 다른 사용자, 없는 ID, 이미 종료된 계획은 모두 같은 404다. 존재를 노출하지 않는다.
  3. 표시 중인 참조 `(planId, pointerVersion, calculationId)`가 현재 공식 포인터와 같은가. 아니면 409 `REFERENCE_STALE`.
  4. 사용 불가. 해당하면 409 `PLAN_END_UNAVAILABLE`이고, 응답 `reason`은 `RESTRICTED`(`plan-end` 제한) 또는 `PLAN_PERIOD_ENDED`(DB 시각 `now >= T`)다.
  5. 사유와 장기 미동기화의 일치.
     - 장기 미동기화는 동기화 admission과 **같은 판정 함수**로 DB 시각에서 본다.
     - `USER_REQUEST`인데 장기 미동기화면 409 `LONG_GAP`.
     - `LONG_GAP_NEW_PLAN`인데 장기 미동기화가 아니면 409 `NOT_LONG_GAP`.
  - 이 판정은 준비 요청에서 한 번, 확정 트랜잭션 안에서 잠금 뒤 다시 한 번 한다.
- **흐름과 허가**(AC-3, SECURITY-ACTION-1.0). 경로는 셋이고 모두 `/api/dashboard/plan-end` 아래다. 기존 `isDashboardPath` 술어가 인증·CSRF·`Origin`을 그대로 건다.
  - `POST /api/dashboard/plan-end`(준비).
    - 본문: `{reason, planId, pointerVersion, calculationId}`.
    - 자격 판정 뒤 허가를 발급한다. 허가는 사용자·세션·command·target·확인 hash에 귀속하고 수명은 5분이다.
    - 확인 문구는 서버가 정본으로 만든다: `plan-end|<reason>|<planId>|<pointerVersion>|<calculationId>`. 그래서 hash가 사유와 본 상태를 함께 판별한다.
    - 세션에 흐름 record를 심는다. record가 담는 것: 사용자, 계획, 사유, 참조, 허가 참조, 확인 문구, 발급 시 세션 id, 무장 시각.
    - 발급이 서지 않으면 403 `REAUTHENTICATION_REQUIRED`다. 앞의 게이트를 모두 지난 뒤의 실패는 재인증뿐이다.
    - 200: `{reason, planId, pointerVersion, calculationId, confirmation}`. 허가 참조는 응답에 싣지 않는다.
  - `POST /api/dashboard/plan-end/confirm`(확정).
    - 본문: 준비 본문에 `confirmation`을 더한다. 헤더 `Idempotency-Key`(16~128자, 대시보드 관용)가 필요하다.
    - 처리 순서:
      1. 계정 쓰기 접근.
      2. 멱등 재전송. 같은 key에 요청 hash(사유·계획·참조)가 같으면 저장된 결과로 200, 다르면 409 `IDEMPOTENCY_CONFLICT`.
      3. 흐름 record와 본문이 일치하는가. 확인 문구는 NFC로 대조한다. 아니면 403 `PLAN_END_NOT_CONFIRMED`.
      4. 허가 소비. 같은 트랜잭션에서 자격을 재판정하고 상태를 전환한다.
      5. 성공하면 흐름을 버리고 세션 id를 회전한다.
    - 거절되는 경우: 만료·재사용·다른 계획·다른 command·확인 뒤 상태 변화. 앞의 넷은 403 `PLAN_END_NOT_CONFIRMED`, 상태 변화는 자격 판정의 409/404다. 어느 경우든 허가는 미소비로 남는다.
  - `POST /api/dashboard/plan-end/cancel`. 흐름을 버리고 허가를 무효화한다. 늘 204다.
  - 확인 화면을 보는 것은 허가를 소비하지 않는다. 재인증 콜백은 종료를 실행하지 않는다.
  - 모든 응답은 `no-store`다. 오류는 공통 `{code, message, correlationId}` 모양이다.
- **원자 전환**(AC-4·AC-5). 확정 트랜잭션 하나에서 순서대로:
  1. 허가 소비.
  2. 계획 쓰기 잠금. 발행자들과 같은 advisory 잠금이다.
  3. 계정 생명주기 행 잠금.
  4. 계획 행 `FOR UPDATE`.
  5. 자격 재판정.
  6. 공식 포인터와 계산을 읽는다(sync 공개 API, 호출자 트랜잭션에 합류).
  7. `UPDATE plan_definition SET status='ENDED_EARLY' … WHERE status='ACTIVE'`. 정확히 1행이어야 한다.
  8. 종료 기록 INSERT.
  - 종료 기록이 담는 것:
    - `ended_at`: DB `statement_timestamp()`를 ms로 자른 값.
    - 마지막 공식 참조: 계산 ID, 포인터 버전, portfolio snapshot ID.
    - 그 계산의 `measured_at`과 `cashflow_verified_through`. 값을 복사해 둘 뿐 다시 계산하지 않는다.
    - 완료 비교 상태.
    - 미수집 구간 `(cashflow_verified_through, ended_at]`. `LONG_GAP_NEW_PLAN`일 때만 둔다.
    - 멱등 키와 요청 hash.
  - 허가의 저장 결과가 곧 멱등 결과이고, 같은 트랜잭션에서 커밋된다.
  - 어느 단계든 실패하면 전체가 롤백된다. 계획은 `ACTIVE`로, 허가는 미소비로 남는다.
  - 계획 입력, 원천 사실, 자금흐름, 계산 버전, 지표, 공식 포인터·이력은 수정하지도 삭제하지도 않는다.
  - 종료 시각의 평가액을 추정하지 않는다. `measured_at`을 종료 시각으로 바꿔 쓰지 않는다.
  - 외부(Bybit) 호출은 0이다.
- **경합**(AC-6).
  - 같은 key의 동시 재전송은 둘 다 같은 결과다. 허가의 `(user_id, operation_id)` 유일성이 가르고, 진 쪽은 저장 결과를 재생한다.
  - 두 탭이 다른 key로 확정하면 하나만 이긴다. 진 쪽은 403이다.
  - 동기화·검증 발행과 종료는 계획 잠금으로 직렬화된다.
    - 발행이 먼저면 포인터가 움직여 종료는 409 `REFERENCE_STALE`이다.
    - 종료가 먼저면 진행 중인 작업은 기존 `PlanSyncGuard.blocked`로 외부 호출 전에 `BLOCKED`/`PLAN_CHANGED`로 끝난다. 새 포인터·계산·snapshot을 만들지 못한다.
  - 종료된 계획에 대한 동기화·검증 admission은 기존대로 409 `NO_ACTIVE_PLAN`이다.
  - 자연 만료(2.14)와는 `now < T`의 잠금 안 재판정으로 갈린다.
- **기록 읽기**(AC-7).
  - "표시할 계획"을 하나로 정의한다: 활성 계획. 없으면 첫 발행이 진행 중일 때 `PREPARING`. 그것도 아니면 가장 최근 계획이 `ENDED_EARLY`일 때 그 계획.
  - `GET /api/dashboard`에 새 상태 `ENDED`를 더한다. 200이고 담는 것:
    - `result`: READY와 같은 모양. `planStatus`는 `ENDED_EARLY`다.
    - 신선도 축: `state:"ENDED"`, `cause:"PLAN_ENDED_EARLY"`, `lastNormalAt=measuredAt`, 영향 지표 여섯, 다음 행동 `NONE`.
    - 입력 신뢰도·계산 성격 축: 그 계산 버전만으로 판정한다. 동기화 사실은 쓰지 않는다.
    - `end`: `reason`, `endedAt`, `scheduledEndAt`(T), `lastNormalMeasuredAt`, `lastNormalDelayMillis`(=`endedAt − measuredAt`), `completion`, `uncollectedFrom`/`uncollectedUntil`.
    - `nextAction: "PREPARE_NEXT_PLAN"`.
    - `sync`·`verification`·`change` 블록은 없다.
  - 일관성: 종료 기록의 참조·시각이 그 계획의 포인터와 계산에 모두 맞아야 한다. 하나라도 어긋나면 503 `DASHBOARD_UNAVAILABLE`이다. portfolio 현재 포인터는 대조하지 않는다. 후속 계획이 옮길 수 있어서다.
  - 근거(2.9)와 조정 이력(2.10)은 같은 "표시할 계획"의 현재 포인터를 참조 대조 기준으로 쓴다.
    - 종료된 계획의 현재 버전은 `appliedUntil = endedAt`이다.
    - 그 밖의 404·503 규칙은 그대로다.
  - READY에 `planEnd` 블록을 더한다. 같은 일관 읽기에서 판정하고 `availability`는 셋이다:
    - `AVAILABLE`.
    - `NEW_PLAN_CHOICE`: 장기 미동기화.
    - `UNAVAILABLE`: `reason`이 `RESTRICTED` 또는 `PLAN_PERIOD_ENDED`.
  - 계획이 끝난 사용자의 새 계획 조건 미리보기·확정은 새 거절 코드 `PLAN_ENDED`다. 후속 계획은 2.15가 연다.
- **화면.** 새 경로는 없다. 카피는 `content/`가 소유하고, 색·간격은 토큰만 쓴다.
  - READY 패널 끝, 계획 조건 다음에 `계획 종료` 영역을 둔다.
    - `AVAILABLE`이면 결과·보존 설명과 보조 버튼 `계획 중도 종료`(44px 이상, `aria-disabled`로 막는다)를 보인다.
    - `NEW_PLAN_CHOICE`이면 "장기 미동기화 선택지의 `새 계획 시작`으로 끝낸다"는 안내만 보인다. 위치를 가리키는 말은 쓰지 않는다.
    - `UNAVAILABLE`이면 사유만 보인다.
  - 장기 미동기화 설명의 `새 계획 시작`에 버튼을 단다. 같은 흐름에 `LONG_GAP_NEW_PLAN`을 싣는다. `기존 계획 계속`과 같은 바쁨 게이트를 쓴다.
  - 재인증이 필요하면 `계획 종료` 영역에 본인 확인 안내와 세 제공자 버튼을 보인다. 재인증 시작은 기존 `useReauthentication`이고, 돌아오면 사용자가 다시 누른다.
  - 확인 대화상자는 `UnlinkDialog` 관용을 따른다.
    - `role=dialog`와 `aria-modal`, 제목·설명 연결, 취소가 DOM 처음이고 기본 초점이다.
    - Tab 가둠. 진행 중이 아니면 Esc가 취소다.
    - 두 인증 화면은 본문을 `inert` 래퍼로 감싸고 대화상자를 형제로 둔다.
  - 대화상자가 말하는 것:
    - 대상 계획의 기간(`t0`~T).
    - 되돌릴 수 없다는 결과.
    - 실제 종료 시각은 확정할 때 서버가 정한다는 것.
    - 마지막 정상값과 측정 시각을 종료 시점 평가액으로 바꾸지 않는다는 것.
    - 입력·조정 이력·계산 근거는 읽기 전용으로 남는다는 것.
    - 종료 뒤에는 동기화하지 않고, 새 계획은 새 현재가치·시각에서 따로 시작한다는 것.
    - 장기 미동기화 선택이면 공백 구간을 수집하지 않고 완료 비교가 `데이터 공백으로 계산 불가`라는 것.
  - 확정에 성공하면:
    - 대화상자를 닫는다. 초점은 호출점으로 돌려주지 않는다.
    - 대시보드를 다시 읽고, 초점을 패널 제목으로 옮긴다.
    - status 문장을 한 번 낸다.
  - 응답을 못 받으면 대화상자를 유지하고 같은 key로 다시 시도하게 한다.
  - `ENDED` 화면은 같은 패널의 기록 모드다. 보이는 것:
    - 제목, `중도 종료` 표지, 사유, 실제 종료 시각, 예정 T, 마지막 정상값·측정 시각·지연.
    - 장기 미동기화 종료면 완료 비교 `데이터 공백으로 계산 불가`와 미수집 구간.
    - 지표 여섯과 입력 신뢰도·계산 성격, 근거·이력 토글, 계획 조건.
    - `새 계획 준비` 영역(설명, 2.13에서는 실행 버튼 없음).
    - 동기화·검증·종료 조작은 없다.
  - 화면 문장 규칙: 중도 종료를 성공·실패·완료로 표현하지 않는다. 대시보드 금지어 목록을 지킨다.
  - 브라우저 저장소 쓰기는 0이다.
- **관측.**
  - 요청마다 한 줄을 남긴다: 단계(prepare/confirm/cancel), 결과 코드, HTTP 상태, 지연.
  - 남기지 않는 것: `user_id`, plan ID, 허가 참조, 확인 문구, 멱등 키, 금융값.
- **삭제**(AC-8). 계정 데이터 삭제가 지우는 것: 종료 기록, 종료된 계획, 그 공식 결과와 근거, 허가 행(cascade). 다른 사용자의 행은 남긴다.

**Ask First:**
- 새 내부 화면 경로나 전역 내비게이션. 계획 기록 목록.
- identity·portfolio·operations·source의 새 공개 API.
- SECURITY-ACTION·ERD·SYNC-UX의 숫자 변경.
- 운영자 HTTP 표면(Epic 3).

**Never:**
- 자연 만료 완료 판정과 종료 뒤 관측(2.14). 새 계획 생성(2.15). 연결 해제(2.16).
- 종료 시점 평가액 추정. 종료를 위한 Bybit 호출. 장기 공백 구간 수집. `PLAN_END_PREPARATION` 검증 접수.
- 과거 계획 입력·조정·계산의 수정이나 삭제. 종료된 계획의 재활성화.
- 클라이언트가 종료 시각·사유 외 판정값을 정하는 것. 허가 참조를 브라우저에 보내는 것.
- 다른 모듈 테이블 직접 접근. 모듈 사이 외래키. `double`·`float` 금융 계산. 성공·실패 판정 문구.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| EARLY_END | 활성 계획, `now < T`, 장기 공백 아님, 최근 재인증 | 준비 200(확인 문구) → 확정 200 `{outcome:"ENDED", planId, reason:"USER_REQUEST", endedAt}`. 계획 `ENDED_EARLY`, 종료 기록 1행, 허가 소비, 세션 회전. 이후 대시보드 `ENDED`, 근거·이력 200 | N/A |
| LONG_GAP_NEW_PLAN | `now − verified > 30일`, `새 계획 시작` | 거래소 호출 0. 기록에 사유·`UNAVAILABLE_DATA_GAP`·`(verified, endedAt]` | `USER_REQUEST`면 409 `LONG_GAP` / 반대는 409 `NOT_LONG_GAP` |
| NO_REAUTH | 재인증 없음 또는 5분 초과 | 준비 403 `REAUTHENTICATION_REQUIRED`. 허가 0. 화면은 본인 확인 버튼 | N/A |
| NOT_ELIGIBLE | 다른 사용자 계획 / 없는 ID / 이미 종료 | 같은 404 `PLAN_NOT_FOUND`. 화면은 대시보드를 다시 읽는다 | N/A |
| UNAVAILABLE | `plan-end` 제한 / `now >= T` | 409 `PLAN_END_UNAVAILABLE` + `RESTRICTED`/`PLAN_PERIOD_ENDED` | 허가 미소비 |
| STALE | 준비 뒤 동기화가 v+1 발행 | 확정 409 `REFERENCE_STALE`. 계획 활성 유지, 허가 미소비 | 화면은 다시 읽고 새 값으로 다시 요청하게 한다 |
| REPLAY | 같은 key·같은 본문 재전송(동시 포함) | 같은 200 결과, 종료 기록 1행 | 같은 key·다른 본문은 409 `IDEMPOTENCY_CONFLICT` |
| GRANT_MISUSE | 만료·재사용·다른 계획·다른 command·흐름 없음·확인 문구 불일치 | 403 `PLAN_END_NOT_CONFIRMED`, 상태 불변 | N/A |
| TWO_TABS | 같은 흐름을 다른 key로 두 번 확정 | 하나만 200, 다른 하나 403. 기록 1행 | N/A |
| LATE_WORKER | 종료 커밋 뒤 진행 중 동기화·검증 작업 | 외부 호출 전에 `BLOCKED`/`PLAN_CHANGED`. 포인터·계산·snapshot 불변 | N/A |
| ROLLBACK | 상태 UPDATE·기록 INSERT·결과 저장 중 실패 주입 | 계획 `ACTIVE`, 기록 0, 허가 미소비, 입력·흐름·계산 불변 | 500 공통 오류 |
| DELETION | 종료된 계획이 있는 계정 삭제 / 삭제 중 확정 | 기록·계획·결과 삭제, 다른 사용자 보존 / 403, 상태 불변 | N/A |

</frozen-after-approval>

## Code Map

**계약**
- `epics.md`
  - 2401-2487: Story 2.13 AC-1~AC-8과 테스트 기준.
  - 305-312: 장기 미동기화와 새 계획 선택.
  - 2501-2651: 2.14·2.15가 기대하는 종단 상태 — 계획 상태와 비교 상태가 따로 있고, 최근 계획이 `중도 종료`·`완료`일 때만 후속 계획을 연다.
- `EPIC-IMPLEMENTATION-READINESS.md`
  - 113-119(§5): SECURITY-ACTION-1.0. 계획 중도 종료는 민감 command다.
  - 33(§2.2): 30일·2년.
  - 75: `serverNow < T`.
- `EXPERIENCE.md`
  - 45·52: 계획 메뉴, 한 단계 overlay.
  - 84·198·203·213·214: confirmation-dialog 규칙.
  - 189-190: 종료 표면의 상태표.
  - 65·70: 카피.
- `deferred-work.md` 207-208: Epic 1 command의 확인 hash가 상수다. 2.13은 참조를 문구에 넣어 이 command에서 해소한다.

**스키마** (`backend/src/main/resources/db/migration/`. 적용된 파일은 읽기만 한다. 새 파일은 `V13`)
- `V10__first_plan_publication.sql`
  - 1-16: 모듈 소유와 "모듈 사이 FK 없음" 규칙, 삭제 순서.
  - 20-46: `plan_definition`.
    - 40: `status IN ('ACTIVE')` 인라인 CHECK. 자동 이름(아마 `plan_definition_status_check`)을 `pg_constraint`로 확인해 교체한다.
    - 41: `UNIQUE(plan_id,user_id)`가 종료 기록 FK 대상이다.
  - 48-49: 부분 유일 `WHERE status='ACTIVE'`. 이것이 활성 포인터다.
  - 279-291: `sync_official_plan_result`.
- `V6__plan_intent.sql:64-75`: `plan_condition_idempotency`. command가 CHECK로 고정돼 재사용하지 않는다. 종료 기록이 자기 멱등 열을 갖는다.
- `V3__action_grant.sql`: 허가 행. 사용자 FK `ON DELETE CASCADE`, `(user_id, operation_id)` 유일.
- `V11__incremental_plan_sync.sql:94-95`: 진행 중 작업 유일 색인은 `(user_id, plan_id)`다.

**백엔드 — 루트** (`backend/src/main/java/com/bokrilog/`)
- `ActionGrant.java`: `canonicalConfirmationHash`(163), `normalize`(177), 상한.
- `ActionGrantService.java`
  - `issue`(213): 게이트 다섯. 빈 값은 사유를 구분하지 않는다.
  - `invalidate`(254).
  - `consume`(310): `attempt`가 같은 TransactionTemplate 안에서 업무 변경을 실행한다. `OperationIdConflict`면 replay한다.
  - `ConsumptionRequest`의 `boundSessionId`.
- `BybitConnectionService.java`: 흐름 관용의 본보기.
  - `start`(197-237): 게이트 → 확인 문구 → `grants.issue` → 무장.
  - `abandon`(247).
  - `complete`(268-310): 문구 NFC 대조 → `consume` → `rotate`(431-437, `changeSessionId`).
- `BybitConnectionFlow.java`: Serializable record, 세션 속성 이름, 5분 수명(49-71).
- `BybitConnectionController.java`: 준비·확정·취소 셋, `no-store`, `ApiError`(209-330).
- `DashboardController.java`: 대시보드 관용.
  - 본문 직접 파싱(`verificationBodyOf` 490-516), 정본 UUID 정규식(101), 버전 정규식(103).
  - `error()`(627), `correlationOf`(868), `currentUserId`(902), 로그 한 줄(241·384).
  - 새 컨트롤러는 따로 둔다. 이 클래스는 `SyncJobApi`에만 의존한다.
- `AuthSecurityConfig.java`
  - 231-240: 대시보드 하위 경로 주석. "핸들러가 있는 넷"을 일곱으로 고친다.
  - 1238 `isDashboardPath`, `isProtectedRequest`(1181-1201), `requiresAuthentication`(1214). 새 술어는 필요 없다.
- `ReauthenticationFlow.java`: `lastAuthenticatedAt`(219). `AuthSessionController.java:110-115`의 `abandonArmedFlows`는 고치지 않는다.

**백엔드 — 모듈**
- `plan/PlanConditionApi.java`: `ActivePlan`(27), `ACTIVE_PLAN_EXISTS`(13), `State`(33), `erasePlanData`.
- `plan/internal/PlanConditionService.java`
  - `PlanPublicationPort` 구현(231-262), `requireCallerTransaction`(295).
  - `state`/`preview`/`confirm`(79·100·129·154): 여기에 `PLAN_ENDED` 거절을 더한다.
  - `eligibleUser`(310-319).
- `plan/internal/PlanConditionRepository.java`
  - `lockUser` advisory(42-46). 발행자의 `lockPlanWrites`와 같은 키다.
  - `erase`(163-168): 종료 기록을 `plan_definition`보다 먼저 지운다.
  - `activePlan`(173-183), `databaseNow`(209), `insertPlan`(213-226).
- `sync/PlanPublicationPort.java`(41-61): sync가 소유하고 plan이 구현한다. sync→plan은 순환이라 금지다. 새 읽기 `displayedPlan`/`latestEndedPlan`을 여기 더한다.
- `sync/SyncJobApi.java`
  - `DashboardResult`(150), `TrustAxis`(129), `Dashboard` sealed(241-256).
  - `OfficialVersion.appliedUntil`(408), `eraseSyncData`(695).
- `sync/internal/PlanPublicationService.java`
  - `dashboard`(574-583), `bundle`(592-640, `"ACTIVE"` 고정 636).
  - 근거 `lineage`(702-712, `activePlan` 703), 버전 목록, `belongs`(1053), `validKey`(1112).
  - 첫 발행 준비(339·418)는 `activePlan`만 본다. 2.13에서는 바꾸지 않는다. 계획 조건 확정이 막혀 새 intent가 없다.
- `sync/internal/AdjustmentHistoryService.java:122-127`: `activePlan` 참조 판정.
- `sync/internal/DashboardTrust.java`
  - 다음 행동 상수(35-50), `freshness`(170-198), `reliability`(206-223), `nature`, `wellFormed`.
  - 기록용 판정 함수를 더한다(동기화 사실 없음, 신선도 `ENDED`).
- `sync/internal/PlanSyncGuard.java`
  - `blocked`(117-132): 활성 계획이 없으면 `PLAN_CHANGED`다. 종료 경합에서 그대로 쓴다.
  - `restricted`(76-81), `unavailableReason`(87-95).
- `sync/internal/PlanSyncService.java`
  - `longGap` 판정은 admission이 쓴다. 대시보드 `planEnd`와 종료 판정이 같은 함수를 쓴다.
  - `run`(264): `enter("TIME")`(456)가 `guard.blocked`를 시간 API 호출 전에 본다.
- `sync/internal/PlanSyncPublisher.java:89-151`: 잠금 순서 = 계획 advisory → 생명주기 → 작업 행. 종료도 앞의 둘을 같은 순서로 잡는다.
- `sync/internal/SyncJobService.java:27-31` + 시험 `SyncCommandNamesTest`: command 상수 사본 관용. `plan-end`를 더한다.
- `identity/AccountAccessApi.java:35`: `lockWriteAccess`. `operations/OperationalRestrictionApi`: `isRestricted`.

**백엔드 시험** (`backend/src/test/java/com/bokrilog/`)
- `AuthFlowTestBase.java`
  - `signIn`(348), `csrfTokenOf`(360-376), `reauthenticate`(446-492), `send`/`sendJson`(682-705).
  - `restrictCommand`(861), `ageReauthentication`(1165), `actionGrant*` 탐침(1352-1444), `databaseNow`, `accountDeletion()`, `jdbc()`.
- `PlanSyncTestBase.java`
  - `publishedPlan`(152-181), `connect`(203), `planRow`(110), `countOf`(116).
  - `holdPlanLock`(223), `awaitBlockedOnPlanLock`(243).
- `IncrementalSyncTest.aChangeBeforeThePublicationWinsOverTheLateWorker`(643-680): 경합 시험 틀. `PLAN_ENDED` 갈래를 더한다.
- `BybitConnectionTest.java`: 흐름 시험 본보기.
  - `signInAndReauthenticate`(616), `grantReferenceOf`(664).
  - 확인 문구 불일치 뒤 허가 미소비(540).
- `FirstSnapshotTest.java:2186-2190, 2515-2518`: 모듈 삭제 순서 호출.
- `ModuleBoundaryTest.java`: 경계, 모듈의 web 금지.

**프런트엔드** (`frontend/src/`)
- `components/UnlinkDialog.tsx`(+`.module.css`): 대화상자 관용.
  - 기본 초점(69-73), 키 처리·Tab 가둠(82-133), 배경(159-169), 초점 복귀(145-147), `aria-disabled`(202·216).
- `screens/SettingsScreen.tsx`
  - `inert` 래퍼(305)와 형제 대화상자(672-680), 성공 초점(207-211).
  - 재인증 버튼 열(630-657).
- `session/useUnlinking.ts`: 준비·확정·취소 훅. 취소는 기다리지 않고, 다음 준비가 그 뒤에 이어진다.
- `session/useBybitConnection.ts`: `inFlight` 같은 tick 가드(90), 401 처리(187-195).
- `session/bybitConnection.ts:181-243`: JSON 요청과 오류 `code` 읽기.
- `session/useReauthentication`·`reauthenticate.ts`: 재인증 시작.
- `screens/SignedInScreen.tsx`, `screens/OnboardingScreen.tsx`: 훅 조립, status 영역(`DashboardStatusText`), 패널 자리. 둘 다 `inert` 래퍼와 대화상자가 필요하다.
- `components/PlanResultPanel.tsx`
  - 제목(498), `Notice`(371-409), `Trust`(236-328), 지표·토글(515-529), 다음 행동(531-551), `Conditions`(330-361).
  - 초점 복구(467-481).
- `components/SyncControl.tsx:349-439`: 장기 미동기화 분기. `newPlanTitle`/`newPlanText`(387-390)는 설명뿐이다. `catchUpRef` 버튼과 초점 사슬(318-334)이 있다.
- `session/useDashboard.ts`
  - `PlanResult`(50), `isPlanResult`(150).
  - `parseDashboard`(694), `NEXT_ACTIONS`(681-685). 모르는 상태는 null이 되어 `lastGood`로 떨어진다.
  - `DashboardView`(741), `supersedes`(719), `bundleOf`(922), `dashboardAnnouncement`(1016), `planPresenceOf`(1062).
- `session/useDashboardSync.ts:747·782`: 자동 트리거는 `view.kind === 'READY'`일 때만 돈다. `ENDED`에서 돌지 않음을 시험으로 고정한다.
- `session/useCalculationEvidence.ts:686·781`, `session/useAdjustmentHistory.ts:386·520`: `bundleOf(...).result`로 참조를 만든다. `ENDED`도 `bundleOf`가 돌려줘야 한다.
- `content/planSync.ts:163-182`: `longGap.newPlan*`와 "범위 밖" 주석. `content/planResult.ts:250·287-321`: `copyFor`, 다음 행동 카피.
- `content/auth.ts`: `CSRF_HEADER`(132), 내부 경로 다섯 곳 주석(145-149). 새 경로를 만들지 않으므로 바꾸지 않는다.
- 시험
  - `test/setup.ts`: `"<METHOD> <URL>"` 스텁(56-70), `stubDashboard`(92), unlink 스텁(214-245), `stubReauthenticationRequest`(180).
  - `test/planResultFixture.ts`: `planStatus:'ACTIVE'`(79), `dashboardFixture`(237), `NO_PLAN_FIXTURE`(286).
  - 금지어: `VerificationControl.test.tsx:23`, `SyncControl.test.tsx:459-498`, `PlanResultPanel.test.tsx:587-597`, `SignedInScreen.test.tsx:1303`.
  - 위치어 금지: `VerificationControl.test.tsx:268`.
- e2e
  - `e2e/support/firstPlan.ts`: `connectAndSavePlan`, `publishAndOpenResult`.
  - `e2e/auth-entry.spec.ts:881-1029`: 대화상자 e2e 본보기.
  - `e2e/incremental-sync.spec.ts:265-279`, `e2e/precision-verification.spec.ts:253-310`: Tab 순서는 다시 불러오기까지다. 새 조작은 그 뒤에 둔다.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/resources/db/migration/V13__plan_early_end.sql` -- expand만 한다.
  - `plan_definition` 상태 CHECK를 `('ACTIVE','ENDED_EARLY')`로 교체한다. 이름으로 지우고 다시 단다.
  - 새 `plan_end`(plan 소유):
    - 열: `plan_id` PK, `user_id`(identity FK RESTRICT), `(plan_id,user_id)`→`plan_definition` FK, `reason`, `ended_at`, `official_calculation_id`, `official_pointer_version`(≥1), `official_portfolio_snapshot_id`, `last_measured_at`, `cashflow_verified_through`, `completion`, `uncollected_from`/`uncollected_until`, `idempotency_key`, `request_hash`, `created_at`.
    - `UNIQUE(user_id, idempotency_key)`.
    - CHECK: 사유와 완료 비교의 동치, 미수집 열의 사유 동치, `uncollected_from < uncollected_until = ended_at`, `last_measured_at <= ended_at`, `cashflow_verified_through <= ended_at`.
    - 모듈 사이 FK는 두지 않는다.
  - V10 주석의 "2.13이 넓힌다"를 머리말에서 이어 설명한다.
- [x] `plan/PlanEndApi.java`(새, 공개) + `plan/internal/PlanEndService.java`, `PlanEndRepository.java` -- 종료의 업무 규칙을 소유한다.
  - `check(user, command)`: 읽기 전용이고 잠금이 없다. 준비 요청이 쓴다.
  - `end(command)`: `MANDATORY`. 잠금 → 재판정 → 상태 UPDATE → 기록 INSERT. 거절은 코드가 붙은 예외로 던져 롤백시킨다.
  - `replay(user, key, requestHash)`: 저장 결과 / 충돌 / 없음.
  - `latestEnded(user)`.
  - 요청 hash와 확인 문구 정본화는 순수 함수 하나가 소유한다.
- [x] `plan/internal/PlanConditionService.java`, `PlanConditionRepository.java`, `PlanConditionApi.java`
  - 종료된 계획이 있으면 `state`/`preview`/`confirm`이 `PLAN_ENDED`로 거절한다.
  - `PlanPublicationPort`의 새 읽기를 구현한다.
  - `erase`에 종료 기록을 더한다.
- [x] `sync/PlanPublicationPort.java`, `sync/SyncJobApi.java`(+`internal` 구현)
  - port: 가장 최근 계획(상태·종료 기록 포함) 읽기를 더한다.
  - `SyncJobApi.planEndBasis(user, planId)`: `MANDATORY`. 공식 포인터·계산(소속 판정)·장기 미동기화 판정을 돌려준다.
  - 새 타입: `Dashboard.Ended`, `PlanEndRecord`, `PlanEndAvailability`.
- [x] `sync/internal/PlanPublicationService.java`, `AdjustmentHistoryService.java`, `DashboardTrust.java`
  - "표시할 계획" 도우미 하나를 bundle·근거·이력이 공유한다.
  - `ENDED` bundle과 일관성 판정. 기록용 신뢰 축.
  - READY의 `planEnd` 판정.
  - 종료된 계획 현재 버전의 `appliedUntil = endedAt`.
  - `"ACTIVE"` 고정을 계획 상태로 바꾼다.
- [x] `sync/internal/SyncJobService.java` + `SyncCommandNamesTest` -- `plan-end` 상수 사본.
- [x] 루트: `PlanEndService.java`, `PlanEndFlow.java`, `PlanEndController.java`(새)
  - 준비·확정·취소. 흐름 record는 Serializable이고 5분 수명이다. 허가 발급·소비, 세션 회전.
  - `DashboardController`의 READY·ENDED view: `planEnd`, `end`, `ENDED` 상태.
  - `AuthSecurityConfig` 주석.
- [x] `DevBybitStub.java` -- 바꾸지 않는다. 종료 경로가 Bybit를 부르지 않음을 시험이 호출 수로 확인한다.
- [x] 백엔드 시험
  - 새 `PlanEndTest`(PlanSyncTestBase)가 I/O matrix 전 행을 덮는다:
    - 준비·확정·취소의 HTTP 모양, CSRF·`Origin`·401.
    - 허가 귀속 다섯과 오용 넷.
    - 장기 미동기화 두 갈래와 Bybit 호출 0.
    - `now >= T` 경계(ms).
    - 같은 key 동시 재전송, 두 탭.
    - 계획 잠금으로 직렬화한 발행 경합 양방향. 늦은 Worker는 `PLAN_CHANGED`이고 쓰기 0.
    - 단계별 실패 주입 롤백(시험 전용 트리거). 입력·흐름·계산·포인터 행 수 불변.
    - `ENDED` 대시보드·근거·이력 200과 일관성 503.
    - 계획 조건 `PLAN_ENDED`.
    - 삭제와 다른 사용자 보존.
    - 로그 캡처로 민감값 부재를 본다.
  - `IncrementalSyncTest`의 경합 틀에 종료 갈래를 더한다.
  - 단위 시험: 정본화·hash, 기록용 신뢰 축, `planEnd` 판정.
  - V13 CHECK 교체: 나머지 CHECK 보존을 `pg_get_constraintdef`로 단정한다.
- [x] 프런트엔드 로직
  - 새 `content/planEnd.ts`: 영역·대화상자·기록·사유·완료 비교·오류 코드·status 카피.
  - `content/planSync.ts`: `새 계획 시작` 버튼 카피, "범위 밖" 주석 제거.
  - `session/useDashboard.ts`: `ENDED` 파싱, `planEnd` 블록 파싱(모르는 값은 `UNAVAILABLE`로 접는다), 기록 `end` 파싱(모르는 사유·완료 비교는 안전한 카피로 접는다).
    - `supersedes`는 같은 계획의 READY→ENDED만 받고, ENDED→READY는 충돌이다.
    - `bundleOf`가 `ENDED`도 돌려준다. 공지 문장을 더한다.
  - 새 `session/usePlanEnd.ts`(+`planEnd.ts` 전송):
    - 상태: 준비, 재인증 필요, 대화상자, 확정 중, 표지.
    - 같은 tick 중복 가드. key는 대화상자를 열 때 한 번 만든다.
    - 취소는 기다리지 않는다. 응답을 못 받았을 때 같은 key로 재시도한다.
    - 거절하면 대시보드를 다시 읽는다. 10초 상한.
- [x] 프런트엔드 화면
  - 새 `components/PlanEndControl.tsx`(+css): 세 availability, 버튼, 재인증 버튼 열, 표지.
  - 새 `components/PlanEndDialog.tsx`(+css): `UnlinkDialog` 관용.
  - 새 `components/PlanEndRecord.tsx`: 기록 사실 묶음.
  - `PlanResultPanel.tsx`: 기록 모드(신선도 축 대신 기록, 동기화·검증·종료 조작 숨김, `새 계획 준비` 영역)와 READY의 종료 영역 자리.
  - `SyncControl.tsx`: `새 계획 시작` 버튼(자기 ref, 초점 사슬 포함).
  - `SignedInScreen.tsx`·`OnboardingScreen.tsx`: `inert` 래퍼, 형제 대화상자, status 문장.
  - `DashboardStatusText.tsx`: 종료 흐름 문장. 같은 영역 안의 span이다.
- [x] 프런트엔드 시험
  - Vitest: 새 세 컴포넌트, 훅, `useDashboard` 파싱·전이, `SyncControl` 새 버튼, 패널 기록 모드, 두 화면.
    - 대화상자: 취소 기본 초점, Tab 가둠, Esc, 진행 중 `aria-disabled`.
    - 성공 뒤 초점은 제목이고, 공지는 한 번이다.
    - `ENDED`에서 동기화 POST 0, 금지어·위치어, 저장소 0, UX-DR23 고지 인접.
  - e2e: 새 `e2e/plan-end.spec.ts`. 첫 계획 발행 → 대시보드 재인증 → 대화상자 → 확정 → 기록.
    - 대화상자 이름과 취소 초점, Esc 복귀, Tab 가둠.
    - 320·1024px 넘침 없음, 44px, axe.
    - 기록 표지·근거·이력 열기, 동기화 버튼 부재.
    - 키보드 순서.
- [x] CI 예산: 로컬 백엔드 `clean build`가 15분을 넘을 때만 `ci.yml`을 올리고 근거를 `DEVELOPMENT.md`에 적는다.

**Acceptance Criteria:**
- Given 최근 재인증한 사용자의 활성 계획, when `계획 중도 종료` → 대화상자 확인을 누르면, then:
  - 1초 안에 진행 표지가 보이고 대화상자는 막힌다.
  - 끝나면 패널이 `중도 종료` 기록으로 한 번에 바뀌고 초점은 패널 제목으로 간다.
  - 기록의 실제 종료 시각은 서버 시각이고, 마지막 정상 측정 시각은 종료 전과 같다.
- Given 재인증이 없는 세션, when `계획 중도 종료`를 누르면, then 대화상자 대신 본인 확인 버튼이 보인다. 돌아와 다시 누르면 대화상자가 열린다.
- Given 장기 미동기화 계획, when `새 계획 시작` → 확인을 누르면, then Bybit 요청이 하나도 없고 기록은 `데이터 공백으로 계산 불가`와 미수집 구간을 보인다.
- Given 종료된 계획, when 근거·조정 이력을 열면, then 종료 직전과 같은 버전·행을 읽기 전용으로 보인다. 동기화·검증 요청은 하나도 나가지 않는다.

## Spec Change Log

## Design Notes

**활성 포인터는 상태 열이다.** V10은 `active_plan` 표를 일부러 두지 않았다. 부분 유일 색인 `WHERE status='ACTIVE'`가 "portfolio당 활성 하나"의 권위다. 그래서 상태를 `ENDED_EARLY`로 바꾸는 UPDATE 한 문장이 포인터 해제다.
- 계획 조건 열(P0·t0·T·주기·목표)은 그대로라 "불변 계획"이 유지된다.
- 종료 사실은 별도 표에 둔다. 그래야 2.14의 `COMPLETED`와 완료 결과가 같은 행을 다시 넓히지 않는다.

**종료 판정은 plan, 기준 읽기는 sync, 허가는 루트.**
- 허가 서비스는 루트 package-private이다. 그래서 흐름·회전은 루트 `PlanEndService`가 한다.
- 계획 행은 plan만 바꾼다. plan→sync는 이미 있는 방향이다. 그래서 plan이 `SyncJobApi.planEndBasis`로 공식 포인터와 장기 공백 판정을 호출자 트랜잭션 안에서 읽는다.
- sync는 plan을 부를 수 없다. 대시보드가 종료 기록을 읽는 길은 sync 소유 `PlanPublicationPort`의 새 읽기뿐이다.

**잠금 순서는 발행자와 같다.** 순서는 계획 advisory → 생명주기 행 → 계획 행이다.
- 허가 행 UPDATE가 그 앞에 오지만, 발행자는 허가 행을 잡지 않으므로 순환이 없다.
- 진행 중인 작업 행은 종료 트랜잭션이 잡지 않는다. 잡으면 단계 트랜잭션(작업 행 → 생명주기)과 교착이 생긴다.
- 종료된 계획의 작업은 Worker가 다음 단계 입구(`enter`)에서 `PLAN_CHANGED`로 스스로 끝낸다. 삭제·제한·연결 변경과 같은 관용이다.

**확인 문구에 상태를 넣는다.** 문구는 `plan-end|reason|planId|pointerVersion|calculationId`다. 그러면 1.9의 다섯 번째 축(확인 hash)이 "사용자가 본 그 상태의 그 사유"를 판별한다.
- 준비 뒤 포인터가 움직였는지는 트랜잭션 안의 재판정이 409로 가른다.
- 흐름 record가 문구를 서버에 쥐고 있다. 클라이언트가 되돌려 보낸 값은 대조에만 쓴다(2.1 관용).

**멱등은 두 층이다.**
- 첫째 층은 종료 기록의 `(user_id, idempotency_key)`다. 흐름이 사라진 뒤에도 같은 key 재전송에 같은 결과를 주고, 다른 본문을 409로 가른다.
- 둘째 층은 허가의 `(user_id, operation_id)`이고 operation id가 곧 그 key다. 첫째 층 조회를 동시에 통과한 두 요청 가운데 하나만 소비하게 하고, 진 쪽은 저장 결과를 재생한다.
- 다른 key로 같은 허가를 쓰는 두 번째 탭은 재생할 결과가 없어 403이다.

**장기 공백에서 사유를 엄격히 가른다.**
- 장기 미동기화일 때 `USER_REQUEST`를 받으면, 공백이 기록되지 않은 채 같은 결과가 생긴다.
- 그래서 장기 미동기화에서는 `LONG_GAP_NEW_PLAN` 하나만 받는다. 그때 화면의 일반 종료 영역은 선택지를 안내만 한다.
- 판정 함수는 동기화 admission과 같다. 대시보드에 보인 장기 공백과 서버 판정이 갈리지 않는다.

**기록은 같은 패널의 다른 모드다.**
- 새 경로를 만들면 다섯 곳 동기화가 따라온다. 첫 로그인 세션은 `/onboarding`으로 돌아오므로 두 화면이 같은 패널을 쓴다.
- `ENDED`는 READY와 같은 `result` 모양을 재사용한다. 그래서 근거·이력 훅이 그대로 돈다.
- 신선도는 기록에서 뜻이 없다. 그래서 `ENDED` 상태 하나로 접고, 그 자리를 종료 사실이 대신한다.

**2.15 전의 공백을 닫는다.** 계획이 끝나면 `PlanConditionService.state`가 다시 열린다. 열린 채면 첫 정상 snapshot(옛 P0·t0)으로 새 계획을 만들 수 있다. 그래서 종료된 계획이 있으면 조건 경로를 `PLAN_ENDED`로 막는다. `새 계획 준비` 영역은 설명만 둔다.

**이 SPEC이 정한 것.**

| 결정 | 값 | 근거 |
|---|---|---|
| 상태 이름 | `ENDED_EARLY` | 문서에 영문 이름이 없다. 2.14 `COMPLETED`와 짝 |
| 사유 | `USER_REQUEST`·`LONG_GAP_NEW_PLAN` | E:2453 |
| 운영 제한 명령 | `plan-end` | 계획 변경 경계의 fail-closed |
| 지연 | `endedAt − lastNormalMeasuredAt` | "지연"의 정의가 문서에 없다 |
| 경로 | `/api/dashboard/plan-end`(+`/confirm`·`/cancel`) | 기존 술어 재사용 |

**응답 요지:**
```json
// 준비 200
{ "reason": "USER_REQUEST", "planId": "…", "pointerVersion": 3, "calculationId": "…",
  "confirmation": "plan-end|USER_REQUEST|…|3|…" }
// 확정 200
{ "outcome": "ENDED", "planId": "…", "reason": "USER_REQUEST", "endedAt": "…" }
// GET /api/dashboard (ENDED)
{ "state": "ENDED", "nextAction": "PREPARE_NEXT_PLAN", "result": { "planStatus": "ENDED_EARLY", … },
  "end": { "reason": "LONG_GAP_NEW_PLAN", "endedAt": "…", "scheduledEndAt": "…", "lastNormalMeasuredAt": "…",
           "lastNormalDelayMillis": 3024000000, "completion": "UNAVAILABLE_DATA_GAP",
           "uncollectedFrom": "…", "uncollectedUntil": "…" } }
// READY에 추가
"planEnd": { "availability": "AVAILABLE|NEW_PLAN_CHOICE|UNAVAILABLE", "reason": null }
```

## Verification

**Commands:**
- `backend/gradlew --project-dir backend --no-parallel --max-workers=1 clean build` -- expected: Testcontainers를 포함해 전체 통과, 실패 0.
- Node 24: `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run test && npm --prefix frontend run build` -- expected: 모두 통과.
- 새 compose 스택(migrate V1~V13)에서 `deploy/compose/verify-same-origin.sh --keep-stack`을 돌린다. 이어서 같은 스택에 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080`으로 전체 e2e를 `--workers=1`로 돌린다 -- expected: 둘 다 통과, 스택 정리.
- `bash .github/scripts/test-ci-scripts.sh`, `git diff --check` -- expected: 통과.

**구현에서 확정한 것 (frozen 블록 밖, 리뷰 뒤 포함):**
- **frozen 보완 1 — 기록의 입력 신뢰도(리뷰 뒤).** frozen은 "그 계산 버전만으로 판정한다. 동기화 사실은 쓰지 않는다"였다.
  - 그대로 두면 문제가 생긴다. 끝내기 직전 대시보드가 `검토 필요`(`VERIFICATION_REVIEW_REQUIRED`)나 `사용 불가`(`HISTORY_OUT_OF_RANGE`)를 보였어도, 영구 기록에는 `확인됨`으로 남는다.
  - 그래서 하나만 예외로 들인다. 그 버전 자신을 기준으로 끝난 정밀 검증·이어가기의 **지속성 검토**(Story 2.12, `PlanSyncRepository.verificationReview`)다. 다음 행동은 `NONE`이다.
  - 최근 동기화 시도의 검토, 가용성, 장기 공백은 그 뒤의 입력에 관한 사실이라 여전히 쓰지 않는다.
- **frozen 보완 2 — 판정 순서와 사유 둘(리뷰 뒤).** `PLAN_END_UNAVAILABLE`의 `reason`에 둘을 더했다.
  - `RESULT_TOO_RECENT`: 공식 결과의 `measured_at`이나 `cashflow_verified_through`가 DB 시각보다 늦다(Bybit 시각은 60초까지 신뢰한다). `ended_at`보다 늦은 측정은 CHECK가 막고, 측정 시각을 종료 시각으로 옮기지도 않는다. 그래서 그 짧은 창에서는 종료를 받지 않는다. 대시보드 `planEnd`도 같은 판정 함수로 `UNAVAILABLE`을 보인다.
  - `RESULT_UNAVAILABLE`: 공식 결과가 READY 묶음과 같은 일관성 판정(소속, AD-23 portfolio 포인터, 지표 여섯, 신뢰도, 계보)을 통과하지 못한다. 그 위에서 끝내면 기록이 영구히 503이 되기 때문이다.
  - 참조 대조(3단계)보다 먼저 본다. 일관된 기준이 없으면 대조할 포인터가 없기 때문이다.
- **DB 짝 강제(리뷰 뒤).**
  - `plan_end.status`는 `'ENDED_EARLY'` 고정이다. `(plan_id,user_id,status)` 복합 FK가 `plan_definition`의 같은 세 열 UNIQUE를 가리킨다.
  - 그래서 활성 계획을 가리키는 종료 기록도, 기록이 남은 계획의 `ACTIVE` 복귀도 SQL이 거절한다.
- **허가 무효화는 참조 단위다(리뷰 뒤).** 취소와 "앞선 무장" 정리는 무장이 가진 허가 참조 하나만 지운다(`ActionGrantService.invalidateReference`). `(user, command, target)` 단위로 지우면, 낡은 탭의 취소가 다른 세션의 살아 있는 허가를 지운다(Story 2.8에서 미룬 결함과 같은 모양).
- **멱등 재생의 마지막 길(리뷰 뒤).** 허가 소비가 `Denied`면 403 전에 종료 기록에서 `(user, key, 요청 hash)` 재생을 한 번 더 찾는다.
- **준비의 판정**은 읽기 전용 `REPEATABLE READ` 한 묶음에서 한다. 두 읽기 사이에 다른 세션의 종료가 커밋되면 500이 아니라 404 `PLAN_NOT_FOUND`다.
- **409 본문**은 공통 `{code, message, correlationId}`에 `reason` 한 필드를 더한 모양이다. frozen의 "응답 `reason`"을 실현한 것이다.
- **취소 204도 `no-store`다.** 준비는 남은 재인증 무장을 버린다(Bybit 연결과 같은 관용).
- **기록 화면.**
  - 신선도 축은 그리지 않는다. 그 자리에 종료 사실(`PlanEndRecord`)을 둔다.
  - 입력 신뢰도·계산 성격의 다음 행동은 `NONE`으로 접는다.
  - `ENDED` 응답은 엄격히 파싱한다. READY는 `planStatus === 'ACTIVE'`를 요구하고, ENDED는 신선도 `ENDED`, 지연 = `endedAt − measuredAt`, `endedAt < T`를 요구한다.
- **화면 흐름.**
  - 동기화·검증·이어가기 작업이 도는 동안 두 종료 버튼을 막는다. 서버는 받지만 화면은 한 번에 한 의도만 보낸다.
  - 막힌 이유는 원인마다 `aria-describedby` 한 줄로 말한다.
  - 확정 200 뒤 `ENDED`가 오기까지 조용한 읽기를 최대 3회 한다. 그래도 오지 않으면 "종료는 확정됐고 기록을 아직 불러오지 못했다" 표지와 함께 한 번 다시 읽는다.
  - 응답을 못 받은 확정 뒤에 취소하면 "확인하지 못했다" 표지와 함께 다시 읽는다.
  - 표시 계획이나 참조가 무장과 달라지면 대화상자를 닫는다. 확정 중일 때는 닫지 않는다.
- **이름.** 루트 흐름 서비스는 `PlanEndCommandService`, plan 모듈은 `PlanEndService`다. plan 쪽 이름(상태·사유·완료 비교·사용 불가 사유)과 sync 사본은 `SyncCommandNamesTest`가 대조한다.

**실행 결과 (리뷰 patch 반영 뒤 최종 트리):**
- 백엔드 `clean build`(`--no-parallel --max-workers=1`): 1044 tests 통과, 실패·skip 0, 5분 11초(독립 재실행에서도 같았다).
  - 리뷰 전 트리는 1029/1029(5분 4초, 독립 재실행)였다.
  - 15분을 넘지 않아 `ci.yml`은 그대로다.
- 프런트엔드(Node 24): `typecheck`·`lint`·`lint:css`·`format:check`·`build` 통과. Vitest 32파일 1031/1031이고 독립 재실행에서도 같았다.
- 새 compose 스택(migrate V1~V13): `verify-same-origin.sh --keep-stack` 959/959. 이어서 같은 스택의 주입 origin e2e가 62/62(2.1분)이다. `plan-end.spec.ts`를 포함하고, 스택은 정리했다.
- `test-ci-scripts.sh` 147/147, `git diff --check` 통과.
- 리뷰: 리뷰어 6명(blind·edge·verification × 백엔드·프런트엔드).
  - patch: 백엔드 13건, 프런트엔드 21건. 전부 반영했다.
  - defer 3건: 재인증 시각의 연쇄 승인에 `plan-end`가 더해진 것(Epic 1 회고 액션), 44×44·진행 표지·대화상자 CSS 사본, 모달 기본 동작의 두 벌.
  - reject: 409 `reason` 필드(SPEC이 정한 모양), 멱등 키 규칙의 HTTP 경계 판정(DB CHECK가 남는다), 미등록 거절 코드의 로그 구분, 두 인증 화면 중복(이미 미룬 항목), 장기 공백·첫 로그인·거절의 e2e(스텁이 30일 공백을 만들 수 없어 백엔드 통합·Vitest가 덮는다), 시험 금지어 정규식 공유, 부재 단정의 고정 대기, status 표지의 지속.

## Suggested Review Order

**종료 흐름 (입구)**

- 준비 → 확정 → 취소. 허가 발급·소비, 멱등 재생, 세션 회전이 여기 모인다.
  [`PlanEndCommandService.java:134`](../../backend/src/main/java/com/bokrilog/PlanEndCommandService.java#L134)

- 준비는 게이트를 지난 뒤에만 무장을 덮어쓰고, 앞선 허가는 참조 하나만 지운다.
  [`PlanEndCommandService.java:92`](../../backend/src/main/java/com/bokrilog/PlanEndCommandService.java#L92)

- 세 경로와 오류 맵. 기존 `isDashboardPath`가 인증·CSRF·Origin을 건다.
  [`PlanEndController.java:120`](../../backend/src/main/java/com/bokrilog/PlanEndController.java#L120)

**업무 규칙과 원자 전환**

- 잠금 → 재판정 → 상태 UPDATE → 기록 INSERT. 허가 소비 트랜잭션에 합류한다.
  [`PlanEndService.java:68`](../../backend/src/main/java/com/bokrilog/plan/internal/PlanEndService.java#L68)

- 자격 판정의 순수 함수. 일관성 → 참조 → 사용 불가 → 장기 공백 순서다.
  [`PlanEndRules.java:50`](../../backend/src/main/java/com/bokrilog/plan/internal/PlanEndRules.java#L50)

- `WHERE status='ACTIVE'` 한 문장이 활성 포인터 해제다.
  [`PlanEndRepository.java:39`](../../backend/src/main/java/com/bokrilog/plan/internal/PlanEndRepository.java#L39)

- 종료 기준은 READY와 같은 일관성 판정을 지나야 한다.
  [`PlanPublicationService.java:766`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L766)

- 상태↔기록 짝과 단조 전진은 복합 FK가 강제한다.
  [`V13__plan_early_end.sql:1`](../../backend/src/main/resources/db/migration/V13__plan_early_end.sql#L1)

**기록 읽기**

- 표시할 계획이 활성 계획이 아니면 종료 기록 묶음을 읽는다.
  [`PlanPublicationService.java:598`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L598)

- 기록 묶음은 종료 기록과 버전이 모두 맞을 때만 답한다.
  [`PlanPublicationService.java:681`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L681)

- 기록의 신뢰 축: 신선도는 `ENDED`, 그 버전의 지속성 검토만 들인다.
  [`DashboardTrust.java:175`](../../backend/src/main/java/com/bokrilog/sync/internal/DashboardTrust.java#L175)

- READY의 `planEnd` 판정. 너무 최근인 결과는 별도 사유다.
  [`DashboardTrust.java:206`](../../backend/src/main/java/com/bokrilog/sync/internal/DashboardTrust.java#L206)

- 조정 이력도 같은 "표시할 계획"을 참조 기준으로 쓴다.
  [`AdjustmentHistoryService.java:126`](../../backend/src/main/java/com/bokrilog/sync/internal/AdjustmentHistoryService.java#L126)

- 계획이 끝나면 조건 경로가 닫힌다. 옛 P0·t0로 새 계획이 생기지 않게 한다.
  [`PlanConditionService.java:85`](../../backend/src/main/java/com/bokrilog/plan/internal/PlanConditionService.java#L85)

**화면**

- 준비·재인증 필요·대화상자·확정·표지의 상태 기계와 확정 뒤 기록 대기.
  [`usePlanEnd.ts:230`](../../frontend/src/session/usePlanEnd.ts#L230)

- `ENDED` 파싱은 엄격하다. 지연·T·신선도·사유를 묶음과 대조한다.
  [`useDashboard.ts:739`](../../frontend/src/session/useDashboard.ts#L739)

- 같은 계획의 READY→ENDED만 받고, 역행은 충돌이다.
  [`useDashboard.ts:873`](../../frontend/src/session/useDashboard.ts#L873)

- 기록 모드: 신선도 축 대신 종료 사실을 두고 조작을 숨긴다.
  [`PlanResultPanel.tsx:582`](../../frontend/src/components/PlanResultPanel.tsx#L582)

- 확인 대화상자. 취소가 기본 초점이고 Tab을 가둔다.
  [`PlanEndDialog.tsx:46`](../../frontend/src/components/PlanEndDialog.tsx#L46)

- 종료 영역: 세 availability, 재인증 버튼 열, 막힌 이유.
  [`PlanEndControl.tsx:50`](../../frontend/src/components/PlanEndControl.tsx#L50)

- 장기 미동기화의 `새 계획 시작` 버튼.
  [`SyncControl.tsx:521`](../../frontend/src/components/SyncControl.tsx#L521)

- 두 화면은 `inert` 래퍼 아래 본문을 두고, 대화상자를 형제로 둔다.
  [`SignedInScreen.tsx:93`](../../frontend/src/screens/SignedInScreen.tsx#L93)

**시험**

- I/O matrix 전 행과 리뷰 뒤 회귀를 실제 스택에서 확인한다.
  [`PlanEndTest.java:1`](../../backend/src/test/java/com/bokrilog/PlanEndTest.java#L1)

- 경합 틀의 종료 갈래: 늦은 Worker는 `PLAN_CHANGED`로 끝난다.
  [`IncrementalSyncTest.java:647`](../../backend/src/test/java/com/bokrilog/IncrementalSyncTest.java#L647)

- 첫 계획 → 대시보드 재인증 → 대화상자 → 기록의 실제 브라우저 왕복.
  [`plan-end.spec.ts:1`](../../frontend/e2e/plan-end.spec.ts#L1)
