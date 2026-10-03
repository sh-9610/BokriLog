---
title: '필요 구간 정밀 재검증·장기 공백 이어가기와 늦은 사건의 새 버전 반영'
type: 'feature'
created: '2026-09-30'
status: 'done'
baseline_commit: '3824c3cb530e1a09012b4cc8a2fe899d0802c8b6'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics.md'
  - '{project-root}/_bmad-output/planning-artifacts/EPIC-IMPLEMENTATION-READINESS.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-BokriLog-2026-08-23/EXPERIENCE.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-11-incremental-plan-sync.md'
---

<frozen-after-approval reason="승인된 Epic 2 Story 2.12 범위, ERD-1.0.0 §2.2·§2.5·SYNC-UX-1.0, AD-7·AD-8·AD-16·AD-23">

## Intent

**Problem:**
- 2.11의 증분 동기화는 마지막 확인 시각 앞 24시간·7일만 다시 읽는다. 그래서 다음은 모두 `검토 필요`로 멈추고, 다음 행동은 풀 수 없는 재동기화다(미룬 항목 87):
  - 그보다 오래된 구간에 늦게 기록된 입출금.
  - 실패한 시도가 남긴 설명되지 않는 저장 사건.
  - 공식 사건의 소실.
- 30일 넘게 동기화하지 않은 계획은 `기존 계획 계속`을 실행할 수 없다.
- 공식 버전이 바뀌어도 무엇이 왜 바뀌었는지 기록하지도 보이지도 않는다(미룬 항목 86).
- Bybit 호출에는 예산·재시도·회로가 없다(ERD §2.5, 미룬 항목 75).

**Approach:**
- 2.11의 작업 표와 엔진에 목적 둘을 더한다:
  - `정밀 검증`(VERIFY): 사용자나 운영이 요청한다.
  - `기존 계획 계속`(CATCH_UP): 장기 공백에서 사용자가 고른다.
- admission은 공식 계보의 기존 완전성 증거를 합성한다. 이것으로 재사용 구간과 검증 대상 구간을 고정한다.
- Worker는 대상만 7일 창으로 재수집하고 대조한다. 그다음 `(t0, tc]` 전체의 새 계보를 2.11과 같은 판정·환산·계산·원자 발행으로 만든다.
- 발행할 때마다 직전 버전과의 흐름 차이를 추가 전용으로 남긴다.
- 대시보드·계산 근거·조정 이력은 그 차이로 다음을 보인다: 원인 사건, 발견·반영 시각, 영향 시작점, 지표의 이전→새 값.
- source 어댑터에 ERD §2.5의 호출 예산·page 재시도·IP 회로를 넣는다.

## Boundaries & Constraints

**Always:**
- **작업과 사유.**
  - `sync_plan_sync_job`에 두 열을 더한다:
    - `purpose`: `SYNC`·`VERIFY`·`CATCH_UP`.
    - `reason_code`: SYNC는 비어 있고, 나머지는 필수다.
  - 사유는 일곱으로 닫혀 있다:
    - `USER_REQUEST`
    - `GAP_SUSPECTED`: 설명되지 않는 저장 사건, 곧 늦은 사건이나 공백이 의심된다.
    - `UNRECOGNIZED_EVENT`
    - `DUPLICATE_SUSPECTED`: 같은 `id`에 다른 내용.
    - `LONG_GAP_CONTINUATION`
    - `PLAN_END_PREPARATION`
    - `OPERATOR_INVESTIGATION`
  - HTTP는 사용자 사유만 받는다.
    - PRECISION: 설명되지 않는 저장 사건이 있으면 서버가 `GAP_SUSPECTED`를 정한다. 없으면 `USER_REQUEST`다. trigger는 `MANUAL`이다.
    - CATCH_UP: `LONG_GAP_CONTINUATION`, trigger는 `MANUAL`이다.
  - 나머지 넷(종료 준비·미인식·중복·운영 조사)은 `SyncJobApi`의 공개 메서드로만 접수한다. trigger는 `SYSTEM`이다.
    - 호출자는 2.13·2.14와 Epic 3이다.
    - `OPERATOR_INVESTIGATION`만 명시 조사 구간을 받는다.
  - 목적과 무관하게 사용자·계획당 진행 작업은 하나다. 기존 조건부 유일 색인이 권위다. 한 연결의 수집도 그 색인으로 single-flight가 된다.
- **admission.** 요청은 `POST /api/dashboard/verification`, 본문 `{kind: PRECISION|CATCH_UP, planId, pointerVersion, calculationId}`, 헤더 `Idempotency-Key`다. 외부 호출 없는 짧은 트랜잭션 하나에서 다음 순서로 판정한다:
  1. 계정 쓰기 접근. 없으면 403.
  2. key 재전송. 같은 kind면 그 작업을 돌려준다(200 `JOINED`). 다른 kind면 409 `IDEMPOTENCY_CONFLICT`.
  3. 활성 계획과 포인터. 참조가 현재 포인터와 다르면 409 `REFERENCE_STALE`. 이 참조가 사용자의 선택 token이다.
  4. 진행 중인 작업. 같은 목적이면 `JOINED`, 다르면 409 `JOB_IN_PROGRESS`.
  5. 사용 불가: 제한, 연결, membership, `now >= T`. 해당하면 409 `VERIFICATION_UNAVAILABLE`.
  6. 목적별 판정.
     - PRECISION:
       - 장기 미동기화면 409 `LONG_GAP`.
       - 같은 기준 계산의 정밀 검증이 이미 `검토 필요`나 `사용 불가`로 끝났으면 409 `VERIFICATION_NOT_USEFUL`.
     - CATCH_UP: 장기 미동기화가 아니면 409 `NOT_LONG_GAP`.
  7. 쿨다운. 마지막 admission(목적 무관) 뒤 30초 안이면 200 `COOLDOWN`과 다시 가능한 시각.
  8. 접수(202 `ACCEPTED`). 기준(계산·포인터·후보·두 워터마크)과 확인 시각까지의 구간 계획을 함께 저장한다.
  - 공개 메서드(`SYSTEM`)는 현재 포인터를 기준으로 삼는다. 참조 판정, 쓸모없음 판정, 쿨다운은 건너뛴다. 운영이 원인을 고친 뒤 같은 기준을 다시 검증할 수 있어야 하기 때문이다.
  - 모든 응답은 `no-store`다. `/api/dashboard/**`의 기존 인증·CSRF·Origin 술어를 그대로 쓴다.
- **구간 계획.** 순수 함수 `VerificationPlanner`가 소유한다. 시험은 ms 경계로 한다.
  - 재사용 근거: 현재 공식 후보에서 `base_candidate_id`를 따라가는 계보의 증명 구간 `[COALESCE(window_start, t0), tc]`를 모두 합친 것이다. 계보의 각 후보는 완료 시점에 창 완전성이 이미 검증됐다.
  - 대상 구간:
    - `UNCONFIRMED`: `[max(t0, verified − 7일), verified]`.
    - `GAP`: `(t0, verified]` 가운데 재사용 근거가 덮지 않는 부분.
    - `ANOMALY`: 설명되지 않는 저장 사건의 시각마다 `±24시간`. `(t0, verified]`로 자른다.
    - `EXPLICIT`: 운영 조사 구간. `(t0, verified]` 안에 있어야 하고 최대 10개다.
    - `NEW`: `(verified, tc]`. `tc`는 Worker가 고정한다.
    - CATCH_UP의 대상은 둘뿐이다. `UNCONFIRMED`와 `NEW`를 이은 `[max(t0, verified − 7일), tc]`, 그리고 그 뒤의 `FINAL_SWEEP` 하나다.
  - 대상은 합치고 정렬한다. 재사용 구간은 근거에서 대상을 뺀 나머지다.
  - 계획 종료라는 이유만으로 재사용 구간을 다시 읽지 않는다.
  - 조회 가능 범위: 대상 가운데 `tc − 730일`보다 이른 부분이 있으면 외부 인증 호출 전에 끝낸다. 상태는 `NEEDS_REVIEW`/`HISTORY_OUT_OF_RANGE`이고 입력 신뢰도는 `사용 불가`다. 2년을 넘는 재구성은 약속하지 않는다.
- **수집.**
  - 대상마다 그 안에서 연속인 최대 7일 창으로 나누고, 두 유형의 모든 cursor를 읽는다.
  - NORMAL·EXTENDED·CATCH_UP·VERIFY는 같은 어댑터 핵심을 쓴다.
  - 완전성: 각 대상이 자기 창들로 빈틈없이 덮이고, 모든 창의 두 유형이 끝 페이지에 닿아야 한다.
  - CATCH_UP:
    1. 대상 전체를 연속 처리한다. 창별 page evidence가 checkpoint다.
    2. 발행 직전에 새 `tcFinal`을 고정한다. 60초 신뢰 조건과 `< T`를 지켜야 한다.
    3. `[max(t0, min(tc, tcFinal − 7일)), tcFinal]`을 다시 읽고 현재 `totalEquity`를 수집한다.
  - 재개: 다시 claim된 VERIFY·CATCH_UP 작업이 TIME·COLLECTION·FINAL_SWEEP 단계에 있으면 이어 달린다.
    - 자식 lease를 넘겨받고, 이미 완전한 창은 건너뛴다.
    - 재개는 최대 2회다. 넘으면 `SYNC_INTERRUPTED`로 끝난다.
    - 그 뒤 단계와 SYNC는 2.11대로 `SYNC_INTERRUPTED`로 끝난다.
- **대조.** Bybit `id`와 기존 `semantic_hash` 공식으로 대상 안의 사건을 가른다:
  - `SAME`: 관측됐고 기존 공식 후보에 있다.
  - `NEW`: 관측됐으나 공식 후보에 없다. 시각이 직전 확인 시각 이하면 늦은 사건이다.
  - `MISSING`: 공식 후보에 있으나 관측되지 않았다. `검토 필요`(`SOURCE_EVENT_MISSING`)다.
  - `SUPERSEDED`: 공식이 아닌 저장 사건이 관측되지 않았다. 새 후보에서 빼고 제외 목록에 남긴다.
  - `CONFLICT`: 같은 `id`에 다른 hash. `검토 필요`다.
  - `id`가 없으면 대체 ID를 만들지 않고 `검토 필요`다.
  - 대상 밖의 공식 사건은 이어받는다.
  - 원천 사실 행은 고치지도 지우지도 않는다. 대조 결과는 VERIFY·CATCH_UP 작업에 추가 전용으로 남긴다.
  - SYNC도 같은 완료 규칙을 창 `[window_start, tc]` 하나에 적용한다. 그래서 공식 사건의 소실은 이제 `검토 필요`다.
  - 모든 후속 후보는 제외 목록을 이어받는다. 이어받은 제외 사건은 설명된 사건으로 센다.
- **재처리.**
  - 판정·환산·계산은 2.11과 같다. 새 후보 전체를 새 판정·환산 묶음으로 처리하고 `CALC-1.0`으로 계산한다.
  - VERIFY·CATCH_UP은 대상 안 흐름의 사건 분 candle을 캐시가 있어도 다시 조회한다. 종가가 다르면 새 revision이 된다(2.6 규칙).
  - 같은 사건은 계산 버전마다 한 번만 반영한다.
- **발행과 변경 기록.**
  - 2.11의 outer 트랜잭션을 그대로 쓴다.
    - `cashflow_verified_through = tc`. CATCH_UP은 `tcFinal`이다.
    - `last_extended_verification_at`: VERIFY·CATCH_UP이면 `tc`다.
  - 같은 트랜잭션에서 직전 계산과의 흐름 차이를 `source_event_id`별로 저장한다. 행마다 담는 것:
    - 변경 종류: `ADDED`·`REMOVED`·`VALUE_CHANGED`.
    - 늦은 사건인지.
    - 이전·새 USD와 각각의 추정 여부.
    - 근거 유형.
    - 발견 시각: 원천 사실의 `first_observed_at`.
  - SYNC를 포함한 모든 증분 발행이 대상이다.
  - 값이 같아도 검증 run(구간·대조·정책 버전·완료 시각)을 보존한다.
  - 과거의 공식 버전과 격리 버전은 삭제하지도 수정하지도 않는다.
- **미해소.**
  - 다음이 남으면 발행도 워터마크 전진도 없이 끝난다: 대상 불완전, `CONFLICT`, `MISSING`, 미인식 유형, 가격 결측, 조회 범위 불능.
  - 마지막 정상 버전과 현재가치를 보존한다.
  - 대시보드 입력 신뢰도:
    - `검토 필요`(`VERIFICATION_REVIEW_REQUIRED`) 또는 `사용 불가`(`HISTORY_OUT_OF_RANGE`)다.
    - 원인, 영향 구간, 보류 지표(여섯 전부)를 싣는다.
    - 다음 행동은 `NONE`이다. "다시 요청해도 바뀌지 않는다"는 안내를 함께 보인다.
    - 이 상태는 같은 기준 계산 동안 유지되고, 동기화의 검토 상태보다 우선한다.
  - SYNC가 `검토 필요`로 끝나면 신선도와 입력 신뢰도의 다음 행동은 `VERIFY`다. 풀 수 없는 재동기화를 권하지 않는다.
- **호출 예산**(ERD §2.5). source 어댑터에 둔다. 운영 기본값은 ERD 그대로이고, 시험만 속성으로 줄인다.
  - token bucket: 인증 호출은 UID당 5 req/s, 공개 Market은 서비스 전체 10 req/s. 동시에 수집하는 연결은 2개까지다.
  - 응답 헤더 `X-Bapi-Limit`·`X-Bapi-Limit-Status`·`X-Bapi-Limit-Reset-Timestamp`를 읽는다. 잔여가 20% 이하면 다음 page 요청을 reset 시각까지 늦춘다(상한 5초).
  - retCode `10006`이면 reset 시각 뒤에 재시도한다.
  - 수집 page(거래 기록, 수집 끝의 wallet)가 timeout·429·10006·5xx면 같은 page를 최대 3회 재시도한다. 간격은 2초·10초·30초에 jitter 0~20%를 더한다.
  - 인증·권한·형식·의미 오류는 재시도하지 않는다.
  - HTTP 403은 IP 차단으로 본다. 모든 Bybit 호출에 10분 회로를 연다. 열린 동안의 호출은 HTTP 없이 `PROVIDER_UNAVAILABLE`이다.
  - 재시도는 lease·fencing 안에서만 하고, lease를 잃으면 멈춘다. 부분 page는 발행하지 않는다.
- **동시성·삭제**(AD-16).
  - 모든 단계와 발행 직전에 lease·fencing·허용 상태·기준 포인터를 다시 읽는다.
  - 늦은 Worker, 옛 기준, 제한 이후의 작업은 발행하지 못한다.
  - 삭제는 이 사용자의 작업·구간·대조·제외·변경 행을 지운다. 다른 사용자의 행은 남긴다.
- **화면.** 새 경로는 없다. 카피는 `content/`가 소유하고, 색·간격은 토큰만 쓴다.
  - `정밀 검증` 영역을 동기화 바로 뒤에 둔다.
    - 목적("필요한 구간만 다시 읽는다"), 비용(여러 번 조회해 수십 초 이상 걸린다), 예상 범위(검증 시작 시각·이상 건수·재사용 끝)를 설명한다.
    - 보조 버튼이고 44px 이상이며 `aria-disabled`로 막는다.
    - 재요청이 쓸모없으면 버튼 대신 사유를 보인다.
  - 장기 미동기화 설명에 `기존 계획 계속` 버튼과 그 범위·비용을 더한다. `새 계획 시작`은 설명만 둔다.
  - 진행 중 표시는 2.11의 따라가기·폴링·초점 규칙을 그대로 쓴다. 초점은 사용자가 누른 요청에만 옮긴다.
  - 결과 표지:
    - 현재 버전이 늦은 변경을 담으면 "늦게 확인된 입출금 N건 반영, 영향 시작 …"을 보인다.
    - 변경 없는 검증이면 `필요 구간 검증 완료`와 함께 검증 구간, 기존 증거 재사용 범위, 시각을 보인다.
    - 전체 이력을 다시 읽었다고 말하지 않는다.
  - 계산 근거에 `변경 근거` 부분을 둔다. 담는 것:
    - 사유, 검증 구간과 재사용 구간.
    - 직전 버전과 그 기준 시각.
    - 영향 시작 시각.
    - 지표 여섯의 이전→새 값. 기준 시각도 함께 바뀌었음을 밝힌다.
    - 변경 사건마다 Bybit `id`, 시각, 유형, 이전→새 USD, 가격 근거, 추정 여부, 발견·반영 시각.
  - 조정 이력: 이 버전에서 늦게 반영됐거나 값이 바뀐 행에 표지와 이전 값을 붙인다.
- **관측.**
  - admission 한 줄: kind, 결과, HTTP 상태, 지연.
  - 작업 종료 한 줄: job, 목적, 사유, 상태, 단계, 코드, 대상 구간 수, 창, page, 재시도 수, 대조 건수, 늦은 변경 수, 지연, 정책.
  - 금융값, 자격정보, 원 응답, `user_id`, Bybit `id`는 남기지 않는다.

**Ask First:**
- 새 내부 화면 경로나 전역 내비게이션.
- plan·portfolio·identity·operations의 새 공개 API. 새 운영 제한 명령 이름.
- ERD·SYNC-UX의 숫자, CALC·BYBIT 정책 값의 변경.
- 사용자 행동과 무관한 스케줄 작업.
- 운영자 HTTP 표면(Epic 3).

**Never:**
- 사용자가 선택하기 전에 장기 공백 구간의 Bybit를 호출하는 것.
- page 재시도 3회를 넘는 자동 재시도 루프. 전 사용자 배치나 주기 작업.
- 부분 결과나 후보를 공식처럼 표시하는 것.
- 원천 사실이나 과거 버전을 수정·삭제하는 것. `id`가 없는 사건에 대체 ID를 만드는 것.
- 거래소 범위 밖 추정. 사용자 수동 사건·가격 입력.
- 계획 종료와 완료 결과 확정(2.13·2.14). 새 계획(2.15).
- 전체 이력을 무조건 다시 조회하는 것. 계획 조건 변경. 광고.
- 다른 모듈의 테이블 직접 접근. `double`·`float` 금융 계산. 외부 호출 중 DB 트랜잭션.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| PRECISION_NO_CHANGE | 활성 계획, 이상 없음 | 202. 대상 = `UNCONFIRMED`+`NEW`, 재사용 = `(t0, verified−7일)`. 성공하면 v+1이고 표지는 `필요 구간 검증 완료`와 구간·시각 | N/A |
| LATE_EVENT | `verified−7일`보다 이른 사건이 실패한 시도로 저장돼 동기화가 검토로 멈춤 | 다음 행동 `VERIFY`. 사유 `GAP_SUSPECTED`, 대상에 `ANOMALY` ±24시간. 관측되면 `NEW`(늦음) → v+1. 변경은 `ADDED`, 영향 시작은 사건 시각, 지표는 이전→새 | N/A |
| SUPERSEDED | 설명되지 않는 저장 사건이 재수집에서 보이지 않음 | 제외 목록에 남기고 발행. 이후 동기화는 그 사건을 설명된 것으로 본다 | N/A |
| MISSING / CONFLICT | 공식 사건이 대상에서 보이지 않음 / 같은 `id`에 다른 hash | 발행 0, 워터마크 불변, `검토 필요`(`VERIFICATION_REVIEW_REQUIRED`), 다음 행동 `NONE`. 같은 기준의 재요청은 409 `VERIFICATION_NOT_USEFUL` | `NEEDS_REVIEW` + `SOURCE_EVENT_MISSING`/`SOURCE_REQUIRES_REVIEW` |
| PRICE_REVISION | 대상 안 흐름의 캐시 candle 종가가 제공자에서 바뀜 | 새 candle revision. 변경은 `VALUE_CHANGED`, 이전→새 USD | N/A |
| CATCH_UP | `now − verified > 30일`, `기존 계획 계속` | 선택 전 Bybit 호출 0. 202. `[max(t0, verified−7일), tc]`를 7일 창으로 연속 처리하고, 마지막에 `tcFinal`·최근 7일·`totalEquity`. 발행은 한 번, 두 워터마크 = `tcFinal` | 참조 불일치 409, 장기 공백 아님 409 |
| RESUME | CATCH_UP 수집 중 lease 상실 | 다시 claim되면 완전한 창을 건너뛰고 이어서 끝낸다. 늦은 Worker는 아무것도 쓰지 못한다 | 세 번째 중단은 `SYNC_INTERRUPTED` |
| OUT_OF_RANGE | 대상이 `tc−730일`보다 이름 | 인증 호출 0, 발행 0, 입력 신뢰도 `사용 불가` | `NEEDS_REVIEW`/`HISTORY_OUT_OF_RANGE` |
| BUDGET | 거래 기록의 5xx·429·10006·timeout / 403 / 잔여 20% 이하 | 같은 page를 3회까지 2·10·30초(+jitter)로 재시도 / 10분 회로, 이후 HTTP 0 / reset 시각까지 대기 | 소진하면 `FAILED`/`PROVIDER_UNAVAILABLE`. 인증 오류는 재시도 0 |
| RACE | 삭제, 제한, 연결 변경, 포인터 이동, lease 상실 | 발행 0, 늦은 Worker의 재생성 0 | `BLOCKED` + 코드. 삭제는 403 |
| REFUSALS | 다른 목적 진행 중 / 쿨다운 / 사용 불가 | 409 `JOB_IN_PROGRESS` / 200 `COOLDOWN` / 409 `VERIFICATION_UNAVAILABLE`. 모두 외부 호출 0 | N/A |

</frozen-after-approval>

## Code Map

**계약**
- `epics.md`
  - 2302-2400: Story 2.12 AC-1~AC-9와 테스트 기준. 마지막 줄은 M2-3 기록 조건이다.
  - 305-312: 24시간·7일·30일 규칙, `기존 계획 계속`=`CATCH_UP`, 같은 엔진, 계획 종료의 증거 합성.
- `EPIC-IMPLEMENTATION-READINESS.md`
  - 26-34(§2.2): `tc` 고정, 창, 2년 범위와 `복구 불가`.
  - 45(§2.3): `PROVIDER_ID_CONFLICT`.
  - 51(§2.4): price revision은 새 버전.
  - 53-58(§2.5): 호출 예산·재시도·403 회로의 숫자.
  - 104-111(§4): SYNC-UX-1.0.
- `EXPERIENCE.md`
  - 301: 전체 재검증은 종료·이상·정밀 검증에서만.
  - 319-331: UJ-3. 사용자가 해소할 수 없으면 반복을 유도하지 않는다.
  - 372-381: Flow 8. 원인 사건, 발견·반영 시각, 영향, 이전→새.

**스키마** (`backend/src/main/resources/db/migration/`. 이미 적용된 파일은 읽기만 한다. 새 파일은 `V12`)
- `V7__initial_source_event_collection.sql`
  - 수집 작업(2-32): 부분 유일 `(user,intent) WHERE QUEUED|RUNNING`(30-32). 증분 자식도 여기에 걸린다.
  - 사실(34-51): `UNIQUE(provider_account_id, source_id)`(47), `semantic_hash`(44), `first_observed_at`(46). last-observed 열은 없다.
  - provenance(53-60): `(job,event)`마다 한 행, `observed_at`=DB `now()`.
  - 관측 진단(62-80): `MISSING_ID`·`UNKNOWN_EVENT`·`PROVIDER_ID_CONFLICT` 등.
  - page evidence(82-106): `range_start/end`, `has_next_page`, `received_at`(앱 시계). 유일 `(job,type,start,end,cursor_hash)`.
  - 후보(108-124): `job_id` UNIQUE. 후보 사건(126-130).
- `V10__first_plan_publication.sql`
  - 계산(167-221): `CHECK(cashflow_verified_through=basis_at)`(217)은 그대로다. `created_by_job_id`에는 FK가 없다.
  - 지표(235-249): 불변. 흐름(253-271): `(calculation_id, source_event_id)` 키, `usd_value`·`estimated`·`evidence_type`.
- `V11__incremental_plan_sync.sql`
  - `sync_plan_sync_job`(18-98): trigger(27), mode(37), stage(39), status(40), failure 코드와 상태별 허용(62-90).
    - `window_start ≤ base_verified_through < tc`(60-61).
    - SUCCEEDED는 발행을 요구한다(75-78).
    - 진행 중 유일 색인(94-95).
  - 자식 연결 열과 부분 유일(103-122): 작업당 수집 자식 하나.
  - 후보의 `base_candidate_id`·`window_start`(127-133), provenance의 `carried_from_candidate_id`(136-139).
  - 계산 `last_extended_verification_at`(146-150), `previous_calculation_id`(153-158). 이력 표(165-181).

**백엔드** (`backend/src/main/java/com/bokrilog/`)
- `sync/internal/PlanSyncService.java`
  - admission(85-146): 판정 순서, `longGap`(143-146, `longGapObserved` 포함).
  - `state`(152-166). claim과 중단 처리(174-187).
  - `run`(197-232): 단계 스위치(204-212), `lastExtended`(221).
  - `fixTime`(239-258): CAS 뒤 60초 판정.
  - 수집(266-314): `insertIncremental`, 연결·tc 재확인(303-305), `completeIncremental`.
  - 거절 매핑(322-332). 판정(340-369), 환산(378-428), 계산(431-447)과 `prepare`(465-486), 발행(449-457).
  - 종료 로그(569-574), `POLICIES`(51-52).
- `sync/internal/PlanSyncRepository.java`
  - insert(61-74), `byKey`/`active`/`latest`/`lastAdmission`(104-125).
  - `claim`(144-152), `lockOwned`(155-160), `heartbeat`(177-197), `FENCE`(199-200).
  - `fixTime`(209-212), `fixScope`(215-220), `attach*`(222-226).
  - `finish`/`endChildren`(252-286), `finishSucceeded`(262-268), `childResults`(321-337).
- `sync/internal/PlanSyncPublisher.java`
  - `publish`(83-148): 잠금 순서(92-94), 재검사(95-111), 쓰기(115-140), 최종 재검사(146).
  - `ownInputs`(155-165), `inputHash`.
- `sync/internal/PlanSyncGuard.java`
  - 제한 명령 넷(76-81). 새 명령은 만들지 않는다.
  - `unavailableReason`(87-95), `blocked`(117-132), `base`(55-64).
- `sync/internal/SyncWindow.java`
  - `longGap`(44-46), `untrustedTime`(49-51), 모드(54-57), 창 시작(60-63), `scope`(81-88).
  - `wait`(96-106), `availability`(122-129).
- `sync/internal/InitialCollectionRepository.java`
  - claim 자식 제외(79), `insertIncremental`(95-106).
  - `storePage`(123-212): 같은 페이지 충돌(170-174), 저장 사실과의 충돌(211-214, `ON CONFLICT DO NOTHING` 188), provenance(217-228).
  - `complete`(235-243), `completeIncremental`(253-283). 이어받기는 260-266이고 "설명됨" 판정은 267-279이며, 주석 246-252에 2.12가 적혀 있다.
  - `validCoverage`(296-310): 한 작업 안에서 연속 창을 요구하고, 끝 행 수를 센다.
  - `completenessDigest`(335-342), `storeCandidate`(343-375: 후보 사건 = 범위의 **모든** 저장 사건), hash(405-408), `parse`(380-388).
- `sync/internal/CashflowConversionRepository.java`
  - `admitChild`(211-237), `storeCandles`(365-402: 종가가 다르면 revision+1).
  - 이전 버전 연결(482-491), 재사용(457-461), `priceRevised`(565-580).
- `sync/internal/CashflowConversionMarket.java:151-204`: `resolveCandles`는 캐시를 먼저 쓰고 미스만 조회한다. 여기에 강제 재조회 분 집합을 더한다.
- `sync/internal/PlanPublicationRepository.java`
  - `insertCandidateSource`(284-292), `insertCalculation`(304-336), `insertFlow`(346-356).
  - `pointFirst`/`pointNext`/`recordHistory`(362-394), `history`(397-405), `metrics`(457-465: 사용자 범위 없음).
  - 진행 판정(472-478), `lineage`(501-518), `unpublished`(615-621).
- `sync/internal/PlanPublicationService.java`
  - `calculationInput`(484-492), 대시보드 `bundle`(589-633)과 AD-23(600-609), `SyncFacts`(616-622).
  - 근거 `lineage`(689-826): 증분 갈래 733-743, `continuedFrom`(867-911, 확장 워터마크 889-890).
  - 버전 목록(835-854), `validKey`(1029).
- `sync/internal/DashboardTrust.java`: 다음 행동 상수(35-40), 신선도(130-148), 입력 신뢰도(155-165), 화면 행동(101-103).
- `sync/internal/AdjustmentHistoryService.java`
  - 참조 판정(124-137), 과거 버전 사슬(214-264).
  - 행에 버전 변경 표지를 더한다. 사슬 전제는 2.12에서도 성립한다: 버전마다 새 분류다.
- `sync/internal/AdjustmentHistoryRepository.java:210`: `first_observed_at AS discoveredAt`.
- `sync/internal/AdjustmentCursor.java:30-35`, `sync/SyncJobApi.java:472-475`: 2.12 재검토 주석이다. 전제가 유지된다고 고쳐 쓴다.
- `sync/SyncJobApi.java`
  - `admitPlanSync`(221), `SyncJob`(171-172), `SyncState`(185-186), `SyncAdmission`(189-213).
  - `Dashboard.Ready`(241), `CalculationEvidence`(291-364), `OfficialVersion`(359-360), `eraseSyncData`(514).
- `sync/internal/SyncJobService.java`: 삭제 순서 `eraseSyncData`. 새 표를 자식보다 먼저 지운다.
- `sync/internal/FirstSnapshotWorker.java`: `dispatchPlanSync`(96-108), `heartbeat`(42-49). 새 dispatch는 만들지 않는다. 같은 큐가 모든 목적을 집는다.
- `source/SourceConnectionApi.java:60-71`: `collectInitialInputs`·`collectIncrementalInputs`와 레코드(32-44).
- `source/internal/SourceConnectionService.java:109-152`: 접근·복호화·위임. 트랜잭션 밖이다.
- `source/internal/BybitAdapter.java`
  - `collect`(388-484): 창 루프(418-484), 요청(630-634), page 크기 50(85).
  - `send`(828-863): 429→RATE_LIMITED, ≥500→PROVIDER_UNAVAILABLE, 그 밖의 비200→AUTHENTICATION_REJECTED.
  - `RET_CODE_REASONS`는 비어 있다(167). 헤더를 읽지 않는다. `Clock.systemUTC()`는 고정이다(196-206, 주입하지 않는다는 주석).
  - javadoc(45-47): §2.5는 수집 page의 계약이다.
- `source/internal/SourceProperties.java:56-90`: Duration을 문자열로 받는 관용. 새 예산 속성도 같은 모양이다.
- `source/SourceRejectionReason.java:27-75`: 거절 사유 목록.
- `DashboardController.java`
  - sync view(114-120, 214-221), READY view(189), POST sync(227-297), 본문 파서(303-320), 로그(322-325), 409 맵(104-107).
  - 같은 관용으로 POST verification을 둔다.
- `DevBybitStub.java`
  - 시나리오(150-289), 키 파싱(392-400), `transactionLog`(421-487), `eventPage`(552-558).
  - incremental(521-550), `resetScenarioState`(664-668), `MarketFixture`(58-137, CONFLICT).
  - 헤더를 설정할 수 있어야 한다.
- `application.yml:185-193`: `bokrilog.source.bybit.*`.

**백엔드 시험** (`backend/src/test/java/com/bokrilog/`)
- `AuthFlowTestBase.java`
  - 유일한 `@DynamicPropertySource`는 74-83이다. 새로 두지 않는다.
  - `resetStore`(154-172)는 스텁을 리셋하지 않는다.
- `BybitStubConfiguration.java`
  - `registerProperties`(67-74)에 예산 속성(짧은 지연·높은 rate·짧은 회로)을 더한다.
  - 거래 기록 질의 기록(124-133), `resetRequestCount`(115-120).
- `IncrementalSyncTest.java`
  - 도우미: `publishedPlan`(945), `moveVerification`(881), `insertSourceEvent`(887), `postSync`(809), `awaitSync`(825), `assertWindows`(836), `holdPlanLock`(1015), `shiftStart`(983).
  - 장기 공백 시험(292-336), 설명 없는 저장 사건(528), 경합(620), lease 상실(673).
  - 새 시험은 새 클래스에 둔다. 도우미는 최소만 복제하거나 공유 추상 기반으로 옮긴다.
- `ModuleBoundaryTest.java`: 모듈 경계(63-95), web·servlet 금지(136-155). sync→plan은 순환이라 금지다.
- 단위 시험 본보기: `sync/internal/PlanSyncServiceTest.java`, `SyncWindowTest.java`, `DashboardTrustTest.java`. 어댑터 단위 시험은 자기 서버를 쓴다.

**프런트엔드** (`frontend/src/`)
- `components/SyncControl.tsx`
  - 장기 미동기화 분기(131-154): 선택지 `dl`(140-147), `notHere`(`planSync.ts:155`).
  - 초점 복구(112-119)의 `buttonRef`는 이 분기에서 붙지 않는다.
  - 주석 80-82.
- `session/useDashboardSync.ts`
  - `post`(367-475): 경로·본문이 고정이다. `crypto.randomUUID()` key, CSRF `X-CSRF-TOKEN`.
  - `readAdmission`(137-147), `parseAdmission`/`ADMISSION_OUTCOMES`(84-114), `judge`(197-266).
  - 폴링(521-561), `canRequest`(579-586), 요청(590-594).
- `session/useDashboard.ts`
  - `parseSyncJob`(295-317), `SyncTrigger`(212), `NEXT_ACTIONS`.
  - `syncSentence`(608-634), `dashboardAnnouncement`(644-674).
- `content/planSync.ts`: 경로(14), 코드(97), trigger(112-117), longGap(145-156), status(171), `planSyncCopyStrings`(179-216).
- `content/planResult.ts`: 원인(172-173), `nextActionCopy`(265-271), `axisActionCopy`(295-296).
- `components/PlanResultPanel.tsx`: 신뢰 절과 SyncControl 자리, 토글 순서.
- `components/CalculationEvidence.tsx`
  - 부분 순서(581-586), `Part`(109-127), 창 시작(227-236), 버전(414-471).
  - 새 `변경 근거` 부분은 Snapshots 앞(577 뒤)에 둔다.
- `session/useCalculationEvidence.ts`: `parseEvidence`(218-326), 버전 규칙(159-192, 313-324). `content/calculationEvidence.ts`는 닫힌 맵이다.
- `components/AdjustmentHistory.tsx`: `Row`(421-491), `changed`(475), `Revision`(249-286), 영역 순서(689-763).
- `session/useAdjustmentHistory.ts`: `isRow`(141-189), `appendPage`(241-261). `content/adjustmentHistory.ts`.
- `components/DashboardStatusText.tsx:40-54`: status 요소는 셋이다. 새 live region은 두지 않는다.
- 시험
  - `test/setup.ts`: 스텁 키는 method+URL(55, 67-69), 그 밖은 거절(366), `stubDashboardSync`(103-105).
  - `test/planResultFixture.ts`: 근거 fixture(236-323).
  - `SyncControl.test.tsx`: 269·284·443-450은 현재의 "선택 불가"를 단정한다. 금지어는 394-433.
  - 금지어 목록: `CalculationEvidence.test.tsx:806`, `AdjustmentHistory.test.tsx:861`, `PlanResultPanel.test.tsx:573`.
- e2e
  - `e2e/incremental-sync.spec.ts`: 키보드 순서 일곱(257-276), axe(228-232), 320·1024px(233-247), 44px(248-250).
  - `e2e/support/firstPlan.ts`: 발행까지의 도우미. `e2e/first-snapshot.spec.ts`의 키보드 순서.

**CI**
- `.github/workflows/ci.yml`: `backend-quality` 20분(277), 통합 17분(362), 회귀 6분(408).
- `frontend/playwright.config.ts:72`(globalTimeout)과 `playwright.config.test.ts:88-97`의 단정.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/resources/db/migration/V12__plan_verification.sql` (expand만 한다)
  - `sync_plan_sync_job`
    - 새 열: `purpose`(기본 `SYNC`로 백필), `reason_code`, `initial_tc`(CATCH_UP의 1단계 상한), `resume_count`(0~2).
    - trigger에 `SYSTEM`, mode에 `VERIFY`·`CATCH_UP`, stage에 `FINAL_SWEEP`을 더한다.
    - NEEDS_REVIEW 코드에 `SOURCE_EVENT_MISSING`·`HISTORY_OUT_OF_RANGE`를 더한다.
    - 목적↔trigger↔사유↔mode CHECK:
      - SYNC는 사유가 없고 NORMAL·EXTENDED다.
      - CATCH_UP은 `MANUAL`+`LONG_GAP_CONTINUATION`이다.
      - VERIFY+`MANUAL`은 `USER_REQUEST`·`GAP_SUSPECTED`다.
      - VERIFY+`SYSTEM`은 나머지 넷이다.
  - 새 표 넷. 각 표는 삭제 경로가 지운다.
    - `sync_plan_verification_range`: `(job, seq)`. kind는 `UNCONFIRMED·GAP·ANOMALY·EXPLICIT·NEW·FINAL_SWEEP·REUSED`, start<end.
    - `sync_plan_verification_event`: `(job, source_id)`. `event_id`(nullable), outcome은 `NEW·SAME·MISSING·SUPERSEDED·CONFLICT`. late, `occurred_at`, 저장 hash와 관측 hash.
    - `sync_initial_candidate_excluded_event`: `(candidate, event)`. 제외한 작업, 사유 `NOT_REOBSERVED`.
    - `sync_plan_calculation_change`: `(calculation, source_event_id)`. 직전 계산, 변경 종류, late, `occurred_at`, 이전·새 USD와 추정, 근거 유형, `discovered_at`.
  - 기존 증분 계산의 변경 행은 같은 차이 SQL로 백필한다.
- [x] `source/internal/BybitCallBudget.java`(새), `BybitAdapter.java`, `SourceProperties.java`, `application.yml`
  - 새 클래스가 가지는 것: UID(자격정보)별 인증 bucket, 공개 전역 bucket, 수집 동시 허용 2, 헤더 임계 대기, 10분 회로.
  - 시험용 시간원을 주입할 수 있는 package-private 생성자를 둔다. 어댑터의 `Clock`은 그대로다.
  - `send`가 헤더를 읽는다. 403은 회로와 `PROVIDER_UNAVAILABLE`, retCode 10006은 `RATE_LIMITED`다.
  - 수집 page 재시도: 3회, 2·10·30초, jitter 20%. 매번 `alive`를 확인한다.
  - 새 핵심 `collectWindows(creds, ref, t0, tc, windows, includeWallet, consumer)`
    - 두 기존 수집과 VERIFY·CATCH_UP이 공유한다.
    - 결과에 재시도 수와 page 수를 싣는다.
  - 기본값은 ERD 그대로다. `application.yml`에 새 속성(재시도 간격, rate, 회로, 임계, 대기 상한)을 적는다.
- [x] `source/SourceConnectionApi.java`, `source/internal/SourceConnectionService.java`
  - `collectWindows(user, connection, t0, tc, List<Window>, includeWallet, pageConsumer, alive)`를 더한다.
  - 기존 두 메서드는 그 위의 얇은 호출로 둔다.
- [x] 새 `sync/internal/VerificationPlanner.java`: 순수 함수.
  - 입력: 재사용 근거, 대상 규칙, 병합, 재사용 = 근거 − 대상, 730일 판정, 7일 창 분할.
  - CATCH_UP 1단계와 FINAL_SWEEP 구간을 계산한다.
- [x] `sync/internal/InitialCollectionRepository.java`
  - 새 `completeTargets(child, value, targets, base, record)`
    - 대상별 커버리지를 본다. 겹치는 창은 대상 사이에서만 허용한다.
    - 대조 다섯 갈래, 이어받기, 제외 목록 이어받기와 추가.
    - 대조 행 기록은 VERIFY·CATCH_UP에서만 한다.
    - "설명됨"은 관측·이어받음·제외다.
  - SYNC는 대상 `[window_start, tc]` 하나로 같은 함수를 쓴다.
  - 계보 증명 구간 조회(재귀 CTE)와 설명되지 않는 저장 사건 조회를 더한다.
  - 재개할 때 쓰는 "완전한 창" 조회를 더한다.
- [x] `sync/internal/CashflowConversionMarket.java`, `CashflowConversionRepository.java`
  - VERIFY·CATCH_UP이 넘기는 사건 분 집합은 캐시를 건너뛰고 다시 조회한다.
  - 저장은 기존 revision 규칙을 따른다.
- [x] `sync/internal/PlanSyncRepository.java`
  - 목적·사유·구간·`initial_tc`·`resume_count`의 insert와 조회.
  - FINAL_SWEEP의 `tc` 재고정 CAS(`initial_tc IS NULL`).
  - 재개 claim: 자식 fencing+1과 lease 인수.
  - 같은 기준의 정밀 검증 검토 조회, 최신 검증 작업과 대조 건수, 삭제.
- [x] `sync/internal/PlanSyncService.java`, 새 `sync/internal/PlanVerificationService.java`
  - admission:
    - HTTP 두 kind와 공개 메서드.
    - 사유 도출, 구간 계획 저장, 쿨다운(30초), 비대칭 합치기.
  - `run`의 목적별 갈래:
    - VERIFY는 30일 판정을 유지한다. CATCH_UP은 건너뛴다.
    - `NEW` 구간을 추가하고, 730일 판정을 인증 호출 전에 한다.
    - 대상 창을 수집한다. 재개면 완전한 창은 건너뛴다.
    - CATCH_UP은 이어서 FINAL_SWEEP 단계를 돈다.
    - 대조로 완료하고 `NEEDS_REVIEW` 코드를 매핑한다.
    - 환산에 강제 재조회 분 집합을 넘긴다. `lastExtended = tc`.
  - 종료 로그의 새 필드.
  - 쓸모없는 재요청을 판정한다: 같은 기준에서 VERIFY가 `NEEDS_REVIEW`로 끝났는가.
- [x] `sync/internal/PlanSyncPublisher.java`
  - 같은 트랜잭션에서 흐름 차이 행을 쓴다(모든 증분 발행).
  - `ownInputs`가 CATCH_UP의 `tcFinal`과 VERIFY의 창 시작(최소 대상 시작)을 받아들이게 한다.
- [x] `sync/internal/PlanPublicationRepository.java`, `PlanPublicationService.java`
  - 대시보드 READY에 둘을 더한다:
    - `verification` 블록: availability, reason, retry, 제안 사유, 미리보기, catchUpFrom, latest.
    - `change` 블록: `CORRECTED`·`VERIFIED_NO_CHANGE`·`NONE`, 늦은 변경 수, 영향 시작, 검증 시각, 구간.
  - 근거에 `change`를 더한다: 사유, 구간, 직전 버전·기준 시각, 영향 시작, 지표 여섯 이전·새, 변경 사건.
  - `continuedFrom`이 VERIFY·CATCH_UP을 받아들이게 한다. 확장 워터마크는 `tc`다.
  - 2.12 전제를 적은 주석들을 고친다.
- [x] `sync/internal/DashboardTrust.java`
  - 입력 신뢰도 우선순위: 같은 기준의 검증 검토(`HISTORY_OUT_OF_RANGE`는 `UNAVAILABLE`) → 동기화 검토(다음 행동 `VERIFY`).
  - 신선도 새 원인: `LAST_VERIFICATION_NEEDS_REVIEW`(NONE). `LAST_SYNC_NEEDS_REVIEW`의 다음 행동은 `VERIFY`다.
  - 다음 행동 상수 `VERIFY`를 더한다.
- [x] `sync/internal/AdjustmentHistoryService.java`, `AdjustmentHistoryRepository.java`
  - 행에 `versionChange`를 더한다: change, late, previousUsd, discoveredAt, appliedAt.
  - 페이지 계보 판정에 변경 행과 흐름의 일치를 더한다.
- [x] `sync/SyncJobApi.java`, `sync/internal/SyncJobService.java`
  - 새 타입: kind, 사유, 구간, admission 결과, 검증 상태, 버전 변경, 근거 변경.
  - `SyncJob`에 purpose와 reason을 더한다.
  - `eraseSyncData`의 새 표 삭제 순서.
- [x] `DashboardController.java`
  - `POST /api/dashboard/verification`
    - 202 `ACCEPTED`, 200 `JOINED`·`COOLDOWN`.
    - 400(본문·kind·UUID), 403, 422(key).
    - 409: `NO_ACTIVE_PLAN`·`REFERENCE_STALE`·`JOB_IN_PROGRESS`·`VERIFICATION_UNAVAILABLE`·`LONG_GAP`·`NOT_LONG_GAP`·`VERIFICATION_NOT_USEFUL`·`IDEMPOTENCY_CONFLICT`.
    - 모르는 코드는 500.
  - READY·근거·이력 view에 새 필드. admission 로그 한 줄.
  - `AuthSecurityConfig`는 주석만 고친다.
- [x] `DevBybitStub.java`, 시험 `BybitStubConfiguration.java`
  - 시나리오:
    - `lateevent`: UID의 두 번째 TRANSFER_IN 읽기부터 `t0+1초`(첫 지갑 읽기 기준)의 +25 USDT가 보인다.
    - `vanishing`: 첫 읽기에만 사건이 보인다.
    - `logflaky`(503 2회), `logratelimited`(10006과 reset 헤더), `logipban`(403), `loglowlimit`(잔여 헤더), `logdown`(항상 503).
    - `MarketFixture`에 읽을 때마다 종가가 바뀌는 심볼을 더한다.
  - 응답 헤더를 지원한다. 리셋에 새 계수를 더한다. 시험 속성에 짧은 예산을 둔다.
- [x] 백엔드 시험
  - 새 `PlanVerificationTest`(AuthFlowTestBase)가 I/O matrix 전 행을 덮는다.
    - 사유 일곱의 admission. 도출은 HTTP, 넷은 공개 메서드로.
    - HTTP 모양 전부.
    - 대상·재사용 구간 단정: 기록된 거래 기록 질의와 구간 행으로 한다.
    - 대조 다섯 갈래와 원천 불변.
    - SUPERSEDED 뒤 SYNC 성공. MISSING·CONFLICT 뒤 409 쓸모없음과 신뢰 축.
    - 늦은 입금·출금·가격 수정의 영향 시작, 한 번 반영, 지표 이전·새.
    - CATCH_UP: 선택 전 호출 0, `verified−7일` 시작, 7일 창, 재개, FINAL_SWEEP·`tcFinal`, 한 번 발행.
    - 경합·삭제·다른 사용자 보존. 로그 캡처로 민감값 부재를 본다.
    - v+1의 근거 200과 이력 200.
  - 예산:
    - 스텁 시나리오로 재시도 횟수, 소진, 인증 비재시도, 403 회로(HTTP 0, 시험 회로 짧게), 잔여 헤더 대기를 본다.
    - 단위 `BybitCallBudgetTest`는 가짜 시간으로 bucket·동시·회로·임계를 본다.
  - 단위 시험: `VerificationPlannerTest`(ms 경계·병합·730일), `DashboardTrustTest`, `PlanSyncServiceTest`/`PlanVerificationServiceTest`(Mockito).
  - 기존 시험을 갱신한다: SYNC의 공식 사건 소실, 로그 필드, 하위 경로 404.
- [x] 프런트엔드 로직
  - 새 `content/planVerification.ts`: 영역, 결과·코드·사유·구간 종류, 변경 표지, 근거 변경, 이력 표지, 계속하기 카피.
  - `content/planSync.ts`: longGap 계속 버튼과 설명, `notHere` 제거, 새 단계·코드, 검증 단계의 상태 문장.
  - `content/planResult.ts`: 새 원인·다음 행동 `VERIFY`, `CHOOSE_CONTINUATION` 문장.
  - `session/useDashboard.ts`: `verification`·`change`·purpose/reason 파싱(모르는 값은 안전하게 접는다), `NEXT_ACTIONS`, 상태 문장.
  - `session/useDashboardSync.ts`: `requestVerification(kind)`
    - 현재 참조를 싣는다.
    - 새 409 코드를 읽는다.
    - 반환된 작업을 수동 따라가기로 잇는다.
- [x] 프런트엔드 화면
  - 새 `components/VerificationControl.tsx`(+`.module.css`): 목적·비용·예상 범위, 버튼, 진행, 쿨다운, 사유, 쓸모없음 안내, 마지막 검증 결과.
  - 새 `components/VersionChangeNote.tsx`: 결과 표지.
  - 새 `components/EvidenceChange.tsx`: 근거의 `변경 근거` 부분.
  - `SyncControl.tsx`: 장기 미동기화의 `기존 계획 계속` 버튼(자기 ref와 초점 복구).
  - `PlanResultPanel.tsx`: 자리.
  - `CalculationEvidence.tsx`·`useCalculationEvidence.ts`·`content/calculationEvidence.ts`: 파싱과 부분.
  - `AdjustmentHistory.tsx`·`useAdjustmentHistory.ts`·`content/adjustmentHistory.ts`: 행 표지.
  - `DashboardStatusText.tsx`.
- [x] 프런트엔드 시험
  - Vitest
    - 새 컴포넌트 시험. `setup.ts`의 `stubDashboardVerification`. 기존 SyncControl·패널·근거·이력·훅·화면 시험.
    - 확인할 것: 두 kind 요청, 초점, 공지 한 번, 쿨다운·쓸모없음·사용 불가, 변경 표지 두 종류, 이전→새, 금지어, 저장소 0.
  - e2e
    - 새 `e2e/precision-verification.spec.ts`(`lateevent` 키): 정밀 검증 요청 → v2 교체, 변경 표지, 근거 `변경 근거`, 이력 행 표지. 키보드 순서, 320·1024px, axe.
    - 기존 두 spec의 키보드 순서를 갱신한다.
- [x] CI 예산: 로컬 백엔드 `clean build`가 15분을 넘으면 `ci.yml`의 `backend-quality`를 올린다. 근거는 `DEVELOPMENT.md`에 적는다.

**Acceptance Criteria:**
- Given 설명되지 않는 저장 사건으로 동기화가 `검토 필요`인 계획, when 대시보드를 읽으면, then 신선도와 입력 신뢰도의 다음 행동이 `VERIFY`다.
- 이어서 `정밀 검증`을 누르면:
  - 1초 안에 진행 표지가 보이고 기존 값은 그대로다.
  - 끝나면 v+1이 한 번에 교체되고 초점은 대시보드 제목으로 간다.
  - 결과 표지, 근거의 `변경 근거`, 조정 이력의 행 표지가 같은 원인 사건과 영향 시작을 가리킨다.
- Given 장기 미동기화 계획, when `기존 계획 계속`을 누르면, then `verified−7일`부터 `tcFinal`까지 수집해 한 번 발행한다. 두 워터마크는 `tcFinal`이고 이후 대시보드는 장기 미동기화가 아니다.
- Given 정밀 검증이 `검토 필요`로 끝난 기준 계산, when 사용자가 다시 요청하거나 자동 동기화가 검토로 끝나면, then 409 `VERIFICATION_NOT_USEFUL`이다. 입력 신뢰도는 `VERIFICATION_REVIEW_REQUIRED`와 다음 행동 `NONE`을 유지한다.
- Given 이 스토리와 Story 2.11의 통합 시험 전부, when 백엔드 build가 통과하면, then M2-3 `반복 증분 동기화·지연 correction·버전 추적`을 충족한 것으로 기록한다.

## Spec Change Log

## Design Notes

**같은 작업 표에 목적을 더한다.** 새 작업 표를 만들면 세 가지를 두 벌로 둬야 한다: 진행 유일 색인, lease·heartbeat·claim, 대시보드의 "진행 중" 판정. 그러면 "한 연결 single-flight"를 두 표 사이에서 다시 증명해야 한다.
- 목적이 달라도 진행 작업은 하나다.
- 동기화 요청은 진행 중인 검증에 합친다(`JOINED`). 검증도 새 `tc`로 공식 결과를 갱신하므로 동기화 의도가 충족된다.
- 검증 요청은 진행 중인 동기화에 합치지 않는다(409). 동기화는 검증을 대신하지 못한다.

**검증도 언제나 새 `tc`까지 간다.**
- VERIFY는 "EXTENDED + 이상·명시·공백 대상"이다. 성공하면 늘 v+1을 발행한다.
- AC-6·AC-7의 "값이 같아도"·"변경이 없으면"은 흐름 변경이 없다는 뜻이다. 기준 시각과 현재가치는 새로 관측된 것이다.
- 이렇게 하면 발행·근거·이력 계약을 2.11과 한 벌로 둘 수 있다. "성공했지만 새 버전 없음"이라는 두 번째 성공 모양도 생기지 않는다.

**마지막 미확정은 `verified − 7일`로 고정한다.**
- 증거의 관측 시각으로 확정 여부를 판정하는 방법도 있다. 그러나 2.11의 NORMAL·EXTENDED 규칙에서는 확정되지 못한 조각이 계속 쌓인다. 그러면 "정밀 검증"이 사실상 전체 재조회가 된다.
- ERD의 확장 중첩(7일)과 같은 경계를 쓰면 설명과 시험이 한 문장이 된다.
- 재사용 근거는 후보 행의 증명 구간이다. 각 후보는 완료 시점에 `validCoverage`를 통과했다. 그래서 계보의 합집합은 구성상 `(t0, verified]`를 연속으로 덮고, GAP은 방어 규칙이다.

**사용자 사유는 둘뿐이다.**
- 미인식 유형과 같은 `id`의 충돌은 다시 읽어도 바뀌지 않는다. ERD §6.1은 둘을 운영 신호로 둔다.
- 그래서 그 두 사유는 운영 경로(공개 메서드)만 쓴다.
- 사용자에게는 동기화 검토 뒤 정밀 검증 한 번을 권한다. 그것마저 검토로 끝나면 같은 기준 동안 버튼 대신 "다시 요청해도 바뀌지 않는다"를 보인다(AC-1, UJ-3). 한 번은 확인이고 반복이 아니다.

**`SUPERSEDED`와 `MISSING`의 비대칭.**
- 공식이 아니었던 저장 사건이 완전한 재수집에서 보이지 않을 때:
  - 공식 결과는 그 사건 없이 이미 성립해 있었다.
  - 그래서 제외 목록에 남기고 발행한다.
  - 이것이 미룬 항목 87의 루프를 끊는다.
- 공식 사건이 사라지면 과거 공식값의 근거가 흔들린다. 그래서 `검토 필요`다.
- 제외는 삭제가 아니다. 나중에 그 사건이 다시 관측되면 `NEW`로 들어온다.

**SYNC의 완료 규칙이 바뀐다.** 2.11은 창 안에서 다시 관측되지 않은 공식 사건을 조용히 이어받았다(ON CONFLICT DO NOTHING). 같은 엔진·같은 대조 규칙이라는 AC-8에 맞춰, 이제 그 경우는 `검토 필요`(`SOURCE_EVENT_MISSING`)다.

**변경 행은 모든 증분 발행이 쓴다.**
- 늦은 사건은 일상 동기화의 중첩 창에서도 들어온다. 그래서 "무엇이 바뀌었나"는 목적과 무관한 버전 사실이다.
- 표지 판정:
  - `CORRECTED`: 늦은 `ADDED`, 또는 `REMOVED`·`VALUE_CHANGED`가 하나라도 있다.
  - `VERIFIED_NO_CHANGE`: 검증 목적이고 늦은 변경이 없다.
  - `NONE`: 그 밖.
- 지표의 이전→새 값은 두 버전의 불변 지표 행이다. 기준 시각과 현재가치도 바뀐다는 사실을 화면이 함께 말한다. 가상의 세 번째 계산값은 만들지 않는다.
- V12는 기존 증분 계산의 변경 행을 같은 SQL로 백필한다. 입력이 불변 행뿐이라 결정적이다.

**재개는 같은 작업 안에서 한다.**
- 사용자가 다시 요청할 때 이전 시도의 창을 복사해 재사용하는 방법도 있다. 그러면 page evidence와 provenance를 다른 자식으로 옮겨야 하고, 계보 판정도 두 작업에 걸친다.
- 다시 claim한 쪽이 자식 lease를 넘겨받아 완전한 창을 건너뛰면 checkpoint가 한 자식 안에 남는다.
- 판정·환산 뒤의 중단은 2.11대로 끝낸다. 그 단계들은 DB 안의 짧은 작업이라, 다시 하는 비용이 외부 호출보다 작다.
- 재개 상한 2회는 미룬 항목 74의 "재claim 무한"을 이 경로에서 닫는다.

**호출 예산은 어댑터 전체에 건다.**
- 재시도는 수집 page에만 건다(javadoc 45-47). 회로와 bucket은 모든 Bybit 호출에 건다. ERD는 "모든 Bybit HTTP 세션 중단"이라고 쓴다.
- UID bucket의 키는 자격정보다. admission 호출은 UID를 알기 전에 나가고, 자격정보 하나가 main UID 하나에 대응한다.
- 동작이 바뀌는 곳은 둘이다:
  - HTTP 403은 전에는 `AUTHENTICATION_REJECTED`였고, 이제는 IP 회로다. ERD §2.5가 403을 차단으로 정한다. Bybit의 키 오류는 200 + retCode로 온다.
  - retCode `10006`은 전에는 `PROVIDER_UNAVAILABLE`이었고, 이제는 재시도 대상이다. 다른 retCode 표는 여전히 비어 있다(미룬 항목 66).

**캐시된 candle을 다시 본다.** 2.6은 캐시된 사건 분을 다시 조회하지 않는다. 그래서 제공자의 종가 정정이 보이지 않았다(미룬 항목 72). VERIFY·CATCH_UP은 대상 안 흐름의 분만 캐시를 건너뛴다. 공유 증거는 revision을 쌓기만 하므로 다른 사용자에게 안전하다.

**이 SPEC이 정한 운영 상수.** 모두 ERD 문장을 구현 가능한 숫자로 옮긴 것이다.

| 상수 | 값 | 근거 |
|---|---|---|
| 조회 가능 범위 | `tc − 730일` | "최대 2년"을 보수적으로 |
| 이상 구간 폭 | ±24시간 | 한 창 안에서 끝나는 가장 작은 폭 |
| 명시 구간 | 10개 | |
| 헤더 임계 대기 상한 | 5초 | |
| jitter | 0~20% | |
| 재개 | 2회 | |
| 선택 token | 표시 중인 `(planId, pointerVersion, calculationId)` | 2.9의 참조 관용 |

**응답 요지:**
```json
{ "outcome": "ACCEPTED", "job": { "jobId": "…", "purpose": "VERIFY", "reason": "GAP_SUSPECTED",
    "trigger": "MANUAL", "status": "QUEUED", "stage": null, "failureCode": null, "admittedAt": "…", "finishedAt": null },
  "retryAfterSeconds": null }
// READY: …, "verification": { "availability": "AVAILABLE|IN_PROGRESS|COOLDOWN|UNAVAILABLE|NOT_USEFUL|LONG_GAP",
//   "reason": null, "retryAfterSeconds": null, "suggestedReason": "GAP_SUSPECTED",
//   "preview": { "rangeStart": "…", "unconfirmedFrom": "…", "anomalyCount": 1, "reusedUntil": "…" },
//   "catchUpFrom": null, "latest": { …job, "targets": [{ "kind": "ANOMALY", "start": "…", "end": "…" }],
//   "reused": […], "counts": { "new": 1, "same": 3, "missing": 0, "superseded": 0, "conflict": 0 } } },
// "change": { "kind": "CORRECTED", "purpose": "VERIFY", "reason": "GAP_SUSPECTED", "lateChangeCount": 1,
//   "firstAffectedAt": "…", "verifiedAt": "…", "targets": […], "reused": […] }
```

## Verification

**Commands:**
- `backend/gradlew --project-dir backend --no-parallel --max-workers=1 clean build` -- expected: Testcontainers 포함 전체 통과, 실패 0.
- Node 24: `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run test && npm --prefix frontend run build` -- expected: 모두 통과.
- 새 compose 스택(migrate V1~V12)에서 `deploy/compose/verify-same-origin.sh --keep-stack`을 돌리고, 이어서 같은 스택에 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080`으로 전체 e2e를 `--workers=1`로 돌린다 -- expected: 둘 다 통과, 스택 정리.
- `bash .github/scripts/test-ci-scripts.sh`, `git diff --check` -- expected: 통과.

**구현에서 확정한 것 (frozen 블록 밖, 리뷰 뒤 포함):**
- 지속성 검증 검토(리뷰 뒤 확정). 같은 기준 계산에서 VERIFY나 CATCH_UP(사용자·SYSTEM 모두)이 `NEEDS_REVIEW`로 끝났고 다음 중 하나이면 "지속성"이다:
  - 코드가 `SOURCE_EVENT_MISSING`·`HISTORY_OUT_OF_RANGE`·`CLASSIFICATION_REQUIRES_REVIEW`·`CONVERSION_REQUIRES_REVIEW`다.
  - 코드가 `SOURCE_REQUIRES_REVIEW`이고, 그 수집 자식에 진단 행·review 표시된 page(`sync_initial_page_evidence.review_required`)·CONFLICT 대조 행 중 하나가 있다. 표시는 page 행 단위라, 재개가 버린 page의 표시는 함께 사라진다(2차 검토).
  - 형식 오류 같은 일시적 거절만으로 끝난 검토는 지속성이 아니다.
  - admission의 쓸모없음 판정과 대시보드 신뢰 축은 SQL 조각 하나(`PERSISTENT_REVIEW`)를 공유한다.
- 지속성 검토가 있는 기준에서는 SYNC도 시간 API를 부르기 전에 그 코드로 `NEEDS_REVIEW`가 된다(리뷰 뒤 확정). 다음 동기화의 24시간 창이 사라진 공식 사건에 닿지 않으면, 그 사건을 조용히 이어받아 검토를 지워 버리기 때문이다.
- CATCH_UP도 지속성 검토가 있으면 409 `VERIFICATION_NOT_USEFUL`이다.
  - 신선도: 검증 검토가 LONG_GAP보다 우선한다(`LAST_VERIFICATION_NEEDS_REVIEW`/`NONE`).
  - `verification.availability` 순서: IN_PROGRESS → UNAVAILABLE → NOT_USEFUL → LONG_GAP → COOLDOWN → AVAILABLE.
- 대기 상한:
  - 제공자 reset 시각 기반 대기(429·`10006`)는 가장 긴 backoff 간격(기본 30초)으로 자르고 overflow를 막는다.
  - 잔여 헤더 대기는 5초 상한이다.
- 수집 경로와 멈춤:
  - `collectIncrementalInputs`는 호출자가 없어져 지웠다. 최초 수집도 `collectWindows` 위의 얇은 호출이다.
  - 모든 수집은 작업의 lease 확인을 alive로 넘기고, 멈춘 수집(`Stopped`)은 아무것도 끝내지 않는다.
- 재개 경로:
  - 미완료 창의 page evidence를 지우고 다시 읽는다. 완료된 창은 둔다.
  - 버린 창에서만 관측된 사건의 provenance도 함께 지운다(carried가 아니고, 시각이 버린 창 안이며, 이 자식의 완료 창 어디에도 들지 않는 행). 그래야 재독에서 사라진 사건이 `SUPERSEDED`·`MISSING`으로 갈린다(2차 검토).
  - lease 인수는 `enterWith`가 guard를 먼저 확인한다.
  - `tc`·`tcFinal`은 60초 신뢰 판정과 같은 트랜잭션에서 저장한다.
- V12:
  - 바뀌는 CHECK 다섯만 이름으로 교체한다. V11만 적용한 probe DB와 `pg_get_constraintdef`를 비교해 나머지가 보존됨을 시험이 단정한다.
  - 변경 행 backfill은 `-- backfill:begin/end` 사이의 사본이다. 롤백 트랜잭션 안에서 `insertChanges`와 같은 결과를 내는지 시험이 단정한다.
- 변경 행:
  - 차이 SQL(`CHANGE_DIFF`)은 쓰기와 일관성 판정이 공유한다. 전 필드를 `IS DISTINCT FROM`으로 비교한다.
  - "늦은 변경"과 `CORRECTED`·`VERIFIED_NO_CHANGE`·`NONE` 도출은 `VersionChanges` 하나가 소유한다.
  - 대시보드는 변경 행이 흐름 차이와 맞지 않으면 표지를 `NONE`으로 접는다. 근거·이력은 503이다.
- 운영 경로 admission: 같은 사유·같은 EXPLICIT 구간일 때만 합친다. 그 밖에는 `JOB_IN_PROGRESS`·`IDEMPOTENCY_CONFLICT`다.
- 2차 검토(최종 트리 재검토) 뒤 확정:
  - CATCH_UP도 PRECISION과 같은 `ANOMALY` 대상(±24시간, 병합, 상한 50)을 더한다. frozen의 "CATCH_UP 대상은 둘뿐"을 보완한 결정이다. 오래된 설명되지 않는 저장 사건이 있으면 계속하기가 매번 검토로 끝나고, 장기 공백에서는 정밀 검증도 운영 경로도 거절돼 풀 수단이 없었기 때문이다.
  - VERIFY·CATCH_UP의 환산 보류는 사유로 가른다. 하나라도 영구 사유(경로 없음·candle 결측·0 이하 가격)면 `NEEDS_REVIEW`/`CONVERSION_REQUIRES_REVIEW`(지속성)이고, 전부 일시적(아직 닫히지 않은 candle·제공자 불가)이면 `FAILED`/`CONVERSION_REQUIRED`다. frozen "미해소"의 가격 결측 = `검토 필요`를 따른 것이다. SYNC는 2.11 그대로다.
  - 지속성 검토가 있으면 sync availability는 `UNAVAILABLE`/`REVIEW_HELD`이고, sync admission은 작업을 만들지 않고 409 `SYNC_UNAVAILABLE`이다. 그래야 자동 트리거가 5분마다 검토 행을 쌓지 않는다. 신선도 원인은 그 사유일 때 검증 검토를 따른다. Worker의 사전 검사는 방어로 남긴다.
  - 두 kind의 admission 모두 지속성 검토를 LONG_GAP·NOT_LONG_GAP보다 먼저 본다(대시보드 순서와 같다).
  - 동기화 검토의 다음 행동은 verification availability로 정한다. AVAILABLE·COOLDOWN이면 `VERIFY`, LONG_GAP이면 `CHOOSE_CONTINUATION`, 그 밖에는 `NONE`이다.
  - 대시보드·근거 `change`에 `lateAddedCount`·`valueChangedCount`·`removedCount`를 더했다(`lateChangeCount`는 합계). 공유 candle 정정이 다른 사용자의 일상 동기화에 만든 값 변경은 "늦게 확인된 입출금"이 아니라 "값이 바뀐 입출금"으로 보인다.
  - 예산 Duration 상한: 재시도 간격 10분, 회로 1일, 잔여 대기 1분. 넘거나 overflow면 기본값이다. lease가 없는 서명 호출이 대기 중 interrupt되면 `UNAVAILABLE`이고, 인증 거절로 접지 않는다.
  - 화면 원인 문구는 목적 중립이다("같은 기준에서 끝난 검증 작업", "마지막 작업"). 동기화로 만든 버전은 구간 행을 보이지 않는다. 늦지 않은 추가는 "마지막 확인 뒤 새로 반영된 입출금"이다. `busy`는 진행 작업이 사라지면 지워진다.
- 예산 속성은 `BOKRILOG_BYBIT_*` 여덟 개다(`application.yml`, `DEVELOPMENT.md`의 "Bybit 호출 예산"). 값이 비정상이면 기본값으로 돌아간다. 한도와 회로는 프로세스 전용이다(미룬 항목).
- 화면:
  - 발행하지 못한 작업의 구간은 `확인 대상 구간`, 재사용은 `재사용하려던 기존 수집 증거`로 부른다. 다 읽었다고 말하지 않는다.
  - 입력 신뢰도의 구간은 보류를 일으킨 작업일 때만 보인다.
- CI: 한때 `backend-quality`를 20분에서 25분으로 올렸으나(로컬 `clean build` 16분 59초) 되돌렸다. PR #55(`spec-backend-test-speed.md`, 시험의 워커 dispatch 100ms·조건 대기)를 합친 뒤 로컬 `clean build`가 4분 41초(989건)라 20분 그대로 충분하다. 주입 origin 회귀는 2.6분이라 그대로다.
- 이 스토리와 Story 2.11의 통합 시험이 통과했다. 그래서 epics.md의 M2-3 `반복 증분 동기화·지연 correction·버전 추적`을 충족한 것으로 기록한다.

**실행 결과 (2차 검토 patch 반영 뒤 최종 트리):**
- 백엔드 `clean build`(`--no-parallel --max-workers=1`): 987 tests 통과, 실패·skip 0, 15분 57초.
  - 1차 patch 뒤 트리는 968/968(16분 59초)이었고 PR #54 첫 CI에서 `backend-quality` 16분 34초였다. 그 전 트리의 독립 재실행은 939/939(16분 4초)였다.
- 프런트엔드(Node 24): `typecheck`·`lint`·`lint:css`·`format:check`·`build` 통과. Vitest 27파일 866/866이고 독립 재실행에서도 같았다.
- PR #55 병합 뒤(develop `633e768` 합침): 백엔드 `clean build` 989/989, 4분 41초. 탭 재표시 리스너를 layout effect로 옮긴 수정(PR #54 CI의 `OnboardingScreen` 재인증 시험 실패 원인) 뒤 Vitest 866/866.
- 새 compose 스택(migrate V1~V12): `verify-same-origin.sh --keep-stack` 959/959. 이어서 같은 스택의 주입 origin e2e가 61/61(2.6분)이다. 스택은 정리했다.
- `test-ci-scripts.sh` 147/147, `git diff --check` 통과.
- 리뷰: 리뷰어 6명(blind·edge·verification × 백엔드·프런트엔드).
  - patch: 백엔드 33건, 프런트엔드 23건, 해석 확정 4건(C1~C4). 전부 반영했다.
  - defer 4건: CSS 44×44 사본, `measured_at`−`tc` 간격, 프로세스 전용 예산, heartbeat `SKIP LOCKED`.
  - reject: 계획이 730일을 넘는 가정, 운영 제한 명령 신설 요구, 검증 후보의 `window_start` 표기(SPEC 정의대로), e2e 예산(실측 2.1분).
- 2차 검토: PR #54의 CI가 통과한 최종 트리를 리뷰어 3명(동기화 엔진, 예산·마이그레이션·신뢰 축, 프런트엔드)이 다시 봤다.
  - patch: 백엔드 11건, 프런트엔드 8건. 전부 반영했다.
  - defer 1건: V12 backfill과 배포 순서(Story 3.8).
  - 결함 없음으로 확인된 영역: 원자 발행, lease·fencing, 멱등 키, 삭제 순서, 로그 민감값, 예산의 재시도·회로·동시 수집, V12 CHECK 보존.

## Suggested Review Order

**검증 실행 (입구)**

- 목적별 갈래: SYNC는 지속성 검토면 호출 전에 멈추고, 검증은 대상 창을 수집한다.
  [`PlanSyncService.java:264`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L264)

- VERIFY·CATCH_UP 실행: 730일 판정, 재개, FINAL_SWEEP까지 lease 하나로 차례로 돈다.
  [`PlanSyncService.java:339`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L339)

- `tcFinal` 고정은 신뢰 판정·저장·자식 상한 연장을 한 트랜잭션에서 한다.
  [`PlanSyncService.java:479`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L479)

- admission: 쓰기 접근 → key → 참조 → 진행 → 사용 불가 → 목적별 → 쿨다운 → 구간 계획 저장.
  [`PlanVerificationService.java:86`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanVerificationService.java#L86)

- 운영 경로: 같은 사유·구간만 합치고 참조·쓸모없음·쿨다운을 건너뛴다.
  [`PlanVerificationService.java:161`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanVerificationService.java#L161)

**구간 계획과 대조**

- 재사용 근거 − 대상, 이상 병합, CATCH_UP 두 단계, 730일 판정은 순수 함수가 가진다.
  [`VerificationPlanner.java:89`](../../backend/src/main/java/com/bokrilog/sync/internal/VerificationPlanner.java#L89)

- 대조 다섯 갈래와 제외 목록 이어받기. SYNC도 같은 완료 규칙을 쓴다.
  [`InitialCollectionRepository.java:317`](../../backend/src/main/java/com/bokrilog/sync/internal/InitialCollectionRepository.java#L317)

- 계보 증명 구간(재귀 CTE)과 설명되지 않는 저장 사건이 계획의 입력이다.
  [`InitialCollectionRepository.java:495`](../../backend/src/main/java/com/bokrilog/sync/internal/InitialCollectionRepository.java#L495)

- 지속성 검토 술어 하나를 admission과 신뢰 축이 공유한다.
  [`PlanSyncRepository.java:148`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncRepository.java#L148)

**발행과 변경 기록**

- 같은 발행 트랜잭션에서 직전 버전과의 흐름 차이를 쓴다.
  [`PlanSyncPublisher.java:143`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncPublisher.java#L143)

- 차이 SQL 하나를 쓰기와 일관성 판정이 공유한다(`IS DISTINCT FROM`).
  [`PlanPublicationRepository.java:366`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationRepository.java#L366)

- 늦은 변경과 표지 세 종류의 도출을 한 곳이 소유한다.
  [`VersionChanges.java:19`](../../backend/src/main/java/com/bokrilog/sync/internal/VersionChanges.java#L19)

- 근거의 변경 부분: 직전 버전은 이력에서 읽고, 실패하면 503이다.
  [`PlanPublicationService.java:859`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L859)

**신뢰 축과 HTTP**

- 검증 검토가 LONG_GAP보다 우선하고, 동기화 검토의 다음 행동은 `VERIFY`다.
  [`DashboardTrust.java:172`](../../backend/src/main/java/com/bokrilog/sync/internal/DashboardTrust.java#L172)

- `POST /api/dashboard/verification`: 202·200·400·403·409·422, 모르는 코드는 500.
  [`DashboardController.java:413`](../../backend/src/main/java/com/bokrilog/DashboardController.java#L413)

**호출 예산**

- 재시도 간격: 2·10·30초 + jitter, reset 시각 대기는 30초 상한.
  [`BybitCallBudget.java:250`](../../backend/src/main/java/com/bokrilog/source/internal/BybitCallBudget.java#L250)

- 수집 page 재시도는 lease 안에서만, 403은 회로, 획득 뒤 회로를 다시 본다.
  [`BybitAdapter.java:575`](../../backend/src/main/java/com/bokrilog/source/internal/BybitAdapter.java#L575)

- 명시 창 수집 핵심 하나를 최초·증분·검증이 공유한다.
  [`BybitAdapter.java:428`](../../backend/src/main/java/com/bokrilog/source/internal/BybitAdapter.java#L428)

**스키마**

- 작업 목적·사유·`initial_tc`·재개 수와, 바뀌는 CHECK 다섯의 이름 교체.
  [`V12__plan_verification.sql:17`](../../backend/src/main/resources/db/migration/V12__plan_verification.sql#L17)

- 구간·대조·제외·변경 네 표와 기존 증분 버전의 변경 행 backfill.
  [`V12__plan_verification.sql:91`](../../backend/src/main/resources/db/migration/V12__plan_verification.sql#L91)

**화면**

- 목적·비용·예상 범위, 버튼과 마지막 검증 요약(발행하지 못했으면 계획한 구간).
  [`VerificationControl.tsx:197`](../../frontend/src/components/VerificationControl.tsx#L197)

- 두 요청(PRECISION·CATCH_UP)을 2.11의 따라가기·초점 규칙에 잇는다.
  [`useDashboardSync.ts:238`](../../frontend/src/session/useDashboardSync.ts#L238)

- 장기 공백의 `기존 계획 계속`과 그 범위, 지속성 검토면 안내로 바뀐다.
  [`SyncControl.tsx:41`](../../frontend/src/components/SyncControl.tsx#L41)

- 결과 표지: 늦은 변경 N건 또는 `필요 구간 검증 완료`와 구간.
  [`VersionChangeNote.tsx:102`](../../frontend/src/components/VersionChangeNote.tsx#L102)

- 근거의 `변경 근거`: 직전 버전, 지표 이전→새, 변경 사건.
  [`EvidenceChange.tsx:162`](../../frontend/src/components/EvidenceChange.tsx#L162)

- 파서는 모순 조합과 바로 앞이 아닌 직전 버전을 거부한다.
  [`useCalculationEvidence.ts:408`](../../frontend/src/session/useCalculationEvidence.ts#L408)

- 조정 이력 행의 버전 변경 표지.
  [`AdjustmentHistory.tsx:519`](../../frontend/src/components/AdjustmentHistory.tsx#L519)

**시험**

- 늦은 사건을 정밀 검증이 찾아 v+1에 늦은 추가로 반영한다.
  [`PlanVerificationTest.java:161`](../../backend/src/test/java/com/bokrilog/PlanVerificationTest.java#L161)

- 사라진 공식 사건: 발행 0, 재요청 409, 뒤따르는 동기화도 호출 0으로 검토.
  [`PlanVerificationTest.java:338`](../../backend/src/test/java/com/bokrilog/PlanVerificationTest.java#L338)

- CATCH_UP: `verified−7일`부터 FINAL_SWEEP까지, 한 번 발행, 두 워터마크 `tcFinal`.
  [`PlanVerificationTest.java:473`](../../backend/src/test/java/com/bokrilog/PlanVerificationTest.java#L473)

- 실 스택: 정밀 검증 → v2, 표지·변경 근거·이력이 같은 사건을 가리킨다.
  [`precision-verification.spec.ts:99`](../../frontend/e2e/precision-verification.spec.ts#L99)

- CI 예산: `backend-quality` 20분 유지(PR #55 병합 뒤 4분 41초).
  [`ci.yml:282`](../../.github/workflows/ci.yml#L282)
