---
title: '첫 정상 스냅샷으로 복리 계획 조건을 검증하고 미리보기'
type: 'feature'
created: '2026-09-26'
status: 'done'
baseline_commit: '3e052e23dbe7db3faeca2c9e677c2c2f4e500b81'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics.md'
  - '{project-root}/_bmad-output/planning-artifacts/EPIC-IMPLEMENTATION-READINESS.md'
  - '{project-root}/_bmad-output/planning-artifacts/prds/prd-BokriLog-2026-08-23/prd.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 첫 공식 현재가치를 얻어도 사용자는 조건을 검증하고 확인할 수 없어 계획을 시작할 수 없다.

**Approach:** 첫 정상 portfolio snapshot을 서버에서 고정 기준으로 읽고, KST·CALC-1.0 규칙으로 입력을 검증해 확인된 조건을 불변 intent로 저장한다. Intent는 후속 원자 발행 스토리가 소비하기 전까지 활성 계획이나 공식 계산이 아니다.

## Boundaries & Constraints

**Always:** `CLAUDE.md`와 `epics.md`의 Story 2.3 AC를 따른다. 사용자·portfolio 소유권과 삭제·운영 제한·활성 계획 부재를 서버에서 판정한다. P0/t0와 provenance는 첫 정상 공식 snapshot에서만 읽는다. API 십진값은 canonical string이며 시간은 UTC millisecond로 저장하고 사용자 시간은 `Asia/Seoul`에서 해석한다. plan 모듈은 portfolio/sync 저장소를 직접 읽거나 수정하지 않는다. 저장은 사용자·세션·canonical 입력 hash에 묶인 단기 확인 참조와 idempotency key로 보호한다. 사용자 입력을 수정하지 않고 intent를 교체하며 이전 intent를 대체 상태로 보존한다. 삭제는 다른 사용자 데이터를 건드리지 않는다.

**Ask First:** none; Story 2.3의 정책·범위는 Epic 2 AC, CALC-1.0 및 CALENDAR-SEOUL-1.0에서 승인됐다.

**Never:** 활성 plan, calculation version, 공식 pointer/result 생성 금지. 제품 상한·다른 시간대·금액/율 반올림을 임의로 추가하지 않는다. 클라이언트 제공 P0/t0/user ID를 권위 입력으로 쓰지 않는다. 비동기 재시도나 provider 접근을 계획 입력 요청에서 수행하지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| ELIGIBLE | 유효한 첫 공식 snapshot, 유효 membership, P0>0 | snapshot provenance와 읽기 전용 P0/t0를 포함한 입력 상태 | 개인정보 없는 차단 결과 |
| INVALID_TIME | T가 첫 일 경계보다 이르거나 t0+1년을 넘음, 모호/부존재 KST 시각 | intent 미저장 | 필드 오류와 요약 제공 |
| INVALID_RATE | 공백·음수·비숫자·NaN/Infinity·precision/scale 초과 또는 r>10 | intent 미저장 | canonical decimal 오류 코드 |
| CONFIRM_REPLAY | 같은 사용자·command·key·canonical 입력 재전송 | 저장된 intent 결과 재생 | key 재사용 시 입력이 다르면 충돌 |
| REPLACEMENT_RACE | 서로 다른 탭에서 조건 확인 | 한 intent만 open; 패배 요청은 저장된 결과/새 미리보기 안내 | 패배를 성공처럼 표시하지 않음 |
| STALE_CONFIRM | 세션·만료·입력 hash·snapshot/policy 상태 변경 | 저장하지 않고 새 확인 요구 | 부분 데이터 및 활성 계획 없음 |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/com/bokrilog/portfolio/PortfolioMembershipApi.java` -- 소유 모듈이 공개할 첫 snapshot·현재 membership 판독 경계
- `backend/src/main/java/com/bokrilog/sync/SyncJobApi.java` -- snapshot 신뢰·정책 provenance 조회 경계 확장
- `backend/src/main/java/com/bokrilog/plan/` -- plan intent와 검증 서비스의 신규 모듈
- `backend/src/main/resources/db/migration/V6__plan_intent.sql` -- intent·confirmation·idempotency의 소유 스키마
- `backend/src/main/java/com/bokrilog/PlanConditionController.java` -- same-origin 계획 조건 API
- `frontend/src/screens/PlanConditionScreen.tsx` -- 입력·검토·확인·중간 결과 화면
- `frontend/src/App.tsx` -- 로그인 이후 계획 조건 경로

## Tasks & Acceptance

**Execution:**
- [x] Portfolio/sync 공개 API가 사용자 소유의 첫 공식 정상 snapshot과 source 신뢰·정책 provenance를 반환하도록 확장한다 -- 현재 pointer와 최초 snapshot을 혼동하지 않는다.
- [x] `plan` 모듈·V6 스키마로 불변 intent, 교체 상태, 확인 reference, idempotency 결과와 사용자 범위 삭제 port를 구현한다 -- 활성 plan 테이블을 만들지 않는다.
- [x] `CALENDAR-SEOUL-1.0`과 `CALC-1.0` 입력 범위/정밀도를 검증하고 확인·저장 트랜잭션에서 접근 상태를 재검사한다 -- 날짜·율 경계의 정책 드리프트를 막는다.
- [x] 인증 same-origin API, 명시 확인 UI, 기존 양수 snapshot 화면으로의 연결 및 접근성 상태를 구현한다 -- 계획 조건 확인이 끝에서 시작까지 이어진다.
- [x] 공개 API/모듈 경계/HTTP/Postgres 동시성·삭제 시험과 KST·decimal contract fixtures를 추가한다 -- AC와 CV 정책을 실 계약으로 검증한다.

**Acceptance Criteria:**
- Given 로그인 사용자가 계획을 시작하면, when 자격을 조회할 때, then 본인 USD portfolio·활성 BYBIT membership 하나·제한/삭제 상태·활성 plan 부재·첫 공식 snapshot·입력 신뢰도·현재 허용 정책을 확인한다.
- Given 자격을 통과하면, when 기준을 표시할 때, then 첫 snapshot의 P0/t0와 ID·source provenance·경과 시간·입력 상태를 read-only로 보이며 현재 pointer가 아닐 수도 있다.
- Given 사용자가 T·일/주/월 주기·주기율을 입력하면, when 미리보기를 요청할 때, then canonical decimal, `0<=r<=10`, 허용 정밀도, 승인된 KST 달력·기간·미래 종료 조건을 검증하고 오류를 필드와 요약에 연결한다.
- Given 입력 검증을 통과하면, when 최종 확인을 준비할 때, then 전체 기준·정책 버전 요약과 사용자/세션/hash/만료 귀속 confirmation reference를 발급하고 취소에 기본 포커스를 둔다.
- Given 확인 명령이 오면, when 동일 트랜잭션에서 소유권·CSRF/origin·제한·membership·provenance·정책·확인 hash를 재검사할 때, then canonical 입력과 snapshot provenance를 단일 open intent로 원자 저장하며 활성 plan/calculation/pointer는 생성하지 않는다.
- Given 같은 idempotency key 또는 경쟁 요청이 오면, when 저장 결과를 판정할 때, then 동일 key+입력만 replay하고 다른 payload는 거절하며 losing request를 성공으로 가장하지 않는다.
- Given 기존 intent 또는 정책/계정 상태가 바뀌면, when 대체·무효화·소비 검증을 할 때, then 기존 입력은 update되지 않고 만료/대체/제한 intent는 소비 불가하다.
- Given intent가 저장되면, when 사용자에게 결과를 보일 때, then `계획 조건 검증 완료`와 기준 근거를 표시하고 활성 계획/공식 지표가 아직 없음을 명확히 한다.
- Given 오프라인·DB 오류·삭제 경합이 생기면, when 흐름이 실패할 때, then 부분 intent나 활성 계획이 없고 자동 재시도하지 않으며 같은 key로 결과를 확인할 수 있다. 키보드·320px·200%/400% 확대·reduced motion을 지원한다.
- Given 대상 사용자 데이터 삭제가 실행되면, when plan deletion port를 호출할 때, then 해당 intent·confirmation·idempotency만 제거되고 다른 사용자 기록은 보존되며 재전송으로 재생성되지 않는다.

## Design Notes

preview 표시는 PRD의 Plan/Target 계산 결과가 아니다. 이 스토리는 조건과 기준금액의 확인만 제공한다. 공식 결과는 Story 2.7의 최초 원자 발행 뒤에만 존재한다. 율 입력은 화면 `%`와 API 비율 문자열을 분리한다 (`10` ↔ `"0.1"`). `T`는 CALENDAR-SEOUL-1.0의 첫 완전한 일 경계 이상, `t0`의 1년 뒤 경계 이하이고 확인 시 `serverNow < T`다.

## Verification

**검증 결과 (2026-09-26):** 프런트 lint·CSS lint·format·typecheck·284개 시험·production build, 백엔드 전체 `build`, `ModuleBoundaryTest`, `AuthSessionServiceReauthenticationTest`, same-origin 계약 959/959, 주입 origin Playwright 57/57, CI 스크립트 계약 147/147, `git diff --check`가 통과했다.

## Suggested Review Order

**API와 서버 검증 경계**

- 인증 사용자에서 미리보기·확인 요청이 시작되는 경계다.
  [`PlanConditionController.java:60`](../../backend/src/main/java/com/bokrilog/PlanConditionController.java#L60)

- 입력 범위, 서버 기준 snapshot, 확인 참조를 묶는 서비스 흐름이다.
  [`PlanConditionService.java:82`](../../backend/src/main/java/com/bokrilog/plan/internal/PlanConditionService.java#L82)

- 사용자·세션·정책 provenance를 재확인하고 동시 확인 승자를 판정한다.
  [`PlanConditionService.java:123`](../../backend/src/main/java/com/bokrilog/plan/internal/PlanConditionService.java#L123)

**저장과 provenance**

- intent, 짧은 confirmation, idempotency 결과를 분리한 스키마다.
  [`V6__plan_intent.sql:9`](../../backend/src/main/resources/db/migration/V6__plan_intent.sql#L9)

- 첫 공식 snapshot은 현재 pointer와 분리해 publication sequence로 선택한다.
  [`PortfolioRepository.java:147`](../../backend/src/main/java/com/bokrilog/portfolio/internal/PortfolioRepository.java#L147)

- source snapshot의 신뢰 상태와 정책을 plan 모듈에 공개한다.
  [`SyncJobApi.java:13`](../../backend/src/main/java/com/bokrilog/sync/SyncJobApi.java#L13)

**화면 흐름**

- 첫 snapshot 화면에서 조건 검토 경로로 진입한다.
  [`FirstSnapshotPanel.tsx:92`](../../frontend/src/components/FirstSnapshotPanel.tsx#L92)

- 닫힌 경로 맵에 로그인 이후 조건 화면을 연결한다.
  [`App.tsx:36`](../../frontend/src/App.tsx#L36)

- 미리보기 응답을 확인한 뒤 명시 확인·재전송 키를 사용한다.
  [`PlanConditionPanel.tsx:200`](../../frontend/src/components/PlanConditionPanel.tsx#L200)

**검증과 배포 계약**

- 경쟁 확인과 동일 밀리초 경계에서 한 intent만 남는지 확인한다.
  [`FirstSnapshotTest.java:93`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L93)

- 입력 단위 변환과 API 경계 응답을 프런트에서 검증한다.
  [`PlanConditionPanel.test.tsx:1`](../../frontend/src/components/PlanConditionPanel.test.tsx#L1)

- 내부 경로 허용 목록과 same-origin 왕복을 통합 검증한다.
  [`verify-same-origin.sh:2329`](../../deploy/compose/verify-same-origin.sh#L2329)
