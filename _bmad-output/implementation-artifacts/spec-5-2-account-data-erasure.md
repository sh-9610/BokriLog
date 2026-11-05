---
title: 'Story 5.2 모든 모듈의 사용자 데이터를 삭제하고 부분 실패 추적하기'
type: 'feature'
created: '2026-11-05'
status: 'done'
baseline_commit: 'c742d4a6578902cac9e05a39f215e6e127dc279b'
review_loop_iteration: 1
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-1-account-deletion-request.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Story 5.1이 삭제 요청을 받아 계정을 `DELETION_IN_PROGRESS`로 막지만 실제 데이터는 그대로다. 지금의 삭제(`AccountErasure`)는 모든 모듈을 트랜잭션 하나로 지워 사용자 경로가 없고, 한 모듈 실패가 전부를 되돌려 어디가 실패했는지 남지 않는다.

**Approach:** Worker가 삭제 요청 계정을 lease로 집어 고정 순서의 단계(자격정보 먼저 → 모듈별 → identity 마지막)를 단계마다 따로 커밋하고, 단계 상태(성공·재시도 필요·건수·실패 code)를 identity 소유 표에 남긴다. 실패하면 거기서 멈추고 `재시도 필요`로 두며, 요청 세션의 진행 화면이 실패 범위와 `다시 시도`를 보여 준다. 마지막 단계가 tombstone 기록·`DELETED` 전환과 함께 `운영 데이터 삭제 완료`를 기록한다(백업 파기와 별개).

## Boundaries & Constraints

**Always:**
- 단계 순서는 하나의 목록이 소유한다: `CREDENTIALS`(Bybit 자격정보·소셜 로그인 연결) → `PORTFOLIO` → `SYNC` → `PLAN` → `SOURCE` → `OPERATIONS`(감사 수행자·제한 target·문의) → `ENGAGEMENT` → `IDENTITY`(요청 행·세션·계정 삭제, tombstone 기록, `DELETED`, 완료 시각 — 한 트랜잭션). 리허설 삭제 표본·복원 적용의 단일 트랜잭션 모드도 같은 목록을 쓴다.
- 각 단계는 같은 트랜잭션 안에서 그 모듈의 잔여 행 수(`remainingRowsOf`)가 0임을 확인해야 성공이다. `IDENTITY` 단계는 앞 단계 전부의 잔여를 다시 세고, 잔여가 있는 모듈의 단계를 `재시도 필요`(`RESIDUAL_FOUND`)로 되돌린다 — 늦은 쓰기를 완료로 숨기지 않는다.
- 실패는 그 단계를 `재시도 필요`로 두고 실행을 멈춘다. 자동 재시도는 없다. 재시도는 실패·미실행 단계만 돌리고 성공 단계는 건너뛴다(모든 port는 멱등이다).
- 단계 결과는 건수·실패 code만 남긴다 — 금융 상세·원 식별자·예외 메시지는 로그·응답·표 어디에도 없다. 다른 사용자·사용자 식별 없는 공유 데이터는 보존한다.
- Worker는 lease·heartbeat·fencing(작업 lease 30초, `SyncLease`)으로 집고 drain 규칙을 따른다. 각 단계 커밋은 fencing을 재검사한다. production(`BOKRILOG_ENV=production`, G-5 전)과 tombstone 키가 없는 프로세스는 삭제를 집지 않는다.
- 요청 세션은 `IDENTITY` 단계 전까지 상태 전용으로 남는다. 재시도는 그 세션의 `POST /api/account/deletion/retry`(CSRF·`Origin`·멱등 키, 재인증 없음 — 삭제 중 계정은 재인증할 수 없다)이고 `재시도 필요`일 때만 받는다.
- 삭제 중 문의 제출은 생명주기 잠금 아래 쓰기 접근을 재검사한다(지금은 잠금 없는 판정뿐이다).

**Ask First:**
- `AccountErasure`의 단일 트랜잭션 모드(리허설 표본·복원 적용)의 관측 가능한 결과가 바뀌어야 할 때.
- 새 단계·새 모듈 port가 위 목록 밖에 필요할 때.

**Never:** 백업 object 파기·tombstone 외부 내보내기 변경(5.3), 완료 안내 화면·재활성화 통합 검증(5.4), 운영자 재시도 command, 자동 재시도·주기 폴링, 법정 분리 보존 이동(정책상 없음), compose·시험 fixture에 키 리터럴, 새 런타임 의존.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 정상 실행 | 요청 행 있는 `DELETION_IN_PROGRESS` 계정 | 여덟 단계 성공, 계정 `DELETED`, tombstone 1행, 운영 완료 시각 기록, 요청 세션 종료 | N/A |
| 중간 실패 | `SYNC` 단계에서 예외 | `PORTFOLIO` 커밋 유지, `SYNC` 재시도 필요·code, 뒤 단계 미실행, 계정 `DELETION_IN_PROGRESS` | 화면에 실패 범위·`다시 시도` |
| 재시도 | 재시도 필요 + 같은 세션 `retry` | 202, 실패 단계부터 다시, 성공 단계 미실행 | 같은 키 재전송은 같은 응답, 재시도 필요가 아니면 409 |
| 늦은 쓰기 | `OPERATIONS` 성공 뒤 문의 행이 생김 | `IDENTITY`가 잔여를 보고 `OPERATIONS`를 재시도 필요로 되돌림 | 계정 `DELETED`로 가지 않음 |
| lease 상실 | 다른 Worker가 fencing을 올림 | 늦은 Worker의 단계 커밋 거절 | 결과 기록 없음 |
| production | `BOKRILOG_ENV=production` | 삭제를 집지 않음, 요청은 진행 중으로 남음 | N/A |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/com/bokrilog/AccountErasure.java` -- 지금 단일 트랜잭션(`run` :103-128). 단계 목록을 여기 하나로 두고 단일 모드(리허설·복원)와 단계 모드가 공유한다. 감사 잠금(`lockAppends`)은 sync 단계 뒤여야 한다(:29-36, 교착 회피).
- `identity/internal/AccountDeletionService.java` -- `beginDeletion` :35-51(IN_PROGRESS 수용), `erasePayload` :55-91(링크·요청 행·`spring_session` raw DELETE·계정·`markDeleted`). 소셜 링크 삭제를 `CREDENTIALS`용 port로 떼어 낸다.
- `source/SourceConnectionApi.java:318` `eraseSourceData`(트랜잭션 없음) -- 자격정보만 지우는 port를 더하고, 단계 모드는 각 port를 트랜잭션으로 감싼다. `portfolio` `erasePortfolioData`도 트랜잭션 없음.
- FK 순서: portfolio → sync(V5 RESTRICT) → source, 모든 모듈 → `identity_account`(RESTRICT)이라 identity가 마지막. operations·support·engagement 표는 identity FK가 없어 잔여 검증만이 지킨다.
- 잔여 판독: 각 모듈 `remainingRowsOf`(portfolio `PortfolioRepository:292`, sync `SyncJobRepository:170`, source `SourceConnectionRepository:306`, operations `DeletionResidueRepository:16`, engagement `EngagementResidueRepository:31`, identity `IdentityAccountRepository:361`, plan `PlanConditionRepository:310`). 새 단계 상태 표는 identity 잔여에서 뺀다(생명주기 행처럼 삭제 기록이다).
- tombstone: `operations/DeletionTombstoneApi.java:137` `record`(호출자 트랜잭션 안, 멱등), 키 bean은 `OperationsConfiguration.java:39-60,93-109` — Worker·`all`에 키가 있으면 bean이 생기게 한다(`application.yml:250-255` 주석 갱신).
- Worker 패턴: `FirstSnapshotSchedulingConfig`, `FirstSnapshotWorker`(dispatch·heartbeat), `SyncLease`, `WorkerDrain`. 상태 표 모양은 `sync_plan_sync_job`(V11), 재시도 필요 상태는 `sync_initial_collection_job`(V7).
- 5.1 표면: `AccountDeletionController`(`RequestView` :109, `state` 고정 :310), `AccountDeletionRequestService`(`STATE_IN_PROGRESS` :82), 프런트 `screens/DeletionProgressScreen.tsx`, `session/accountDeletion.ts`, `content/accountDeletion.ts`·공유 표 `accountDeletionPolicy.fixture.json`.
- 문의 제출: `SupportInquiryController:127`(잠금 없는 판정) → `SupportInquiryService:126` `submitLocked` — 생명주기 잠금 재검사 추가.
- 경계 고정: `ModuleBoundaryTest` :321-331(`beginDeletion`·`erasePayload`·tombstone `record`는 `AccountErasure`만), :352-356(`erase` 호출자 — 새 실행자를 더한다), :398-401·:499-502.
- 새 마이그레이션 `V31__identity_deletion_run.sql` -- 계정당 실행 행(lease·fencing·상태·운영 완료 시각·재시도 멱등 키 digest)과 단계 행(code·상태·건수·실패 code). FK는 `identity_account_lifecycle`.
- 미룬 항목: 67은 단계 목록 하나·단계별 잔여 검증으로 일부 다룬다. 27(identity의 세션 raw DELETE)·56(소비 허가 보존)·162(삭제 뒤 운영자 거절 행)·170(지원 경로)·171(복원 요청 내구성)은 지나간다.
- 걸린 열린 회고 액션: `epic-3-retro-item-28`(비밀 노출 가림을 비밀 받는 모든 역할로) — Worker가 tombstone 키를 받으므로 `LogSecretGuard` 대상에 그 키를 더해 일부 다룬다. `epic-2-retro-item-24`(erase 완전성 시험)·`epic-3-retro-item-32`(일회성 역할 공용 base)·`epic-1-retro-item-7`(확인 hash)은 건드리지 않고 지나간다.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/resources/db/migration/V31__identity_deletion_run.sql` -- 실행·단계 표 -- 부분 실패의 권위 기록.
- [x] `backend/.../identity/**` -- 실행·단계 상태 API(생성·claim·단계 결과·재시도 접수·조회)와 링크만 지우는 port, `erasePayload` 분리, 잔여 판독에서 상태 표 제외 -- identity가 상태 권위.
- [x] `backend/.../source/**` -- 자격정보만 지우는 멱등 port -- `CREDENTIALS` 단계.
- [x] `backend/.../AccountErasure.java` + 새 루트 실행자·Worker 배선 -- 단계 목록 공유, 단계별 트랜잭션·잔여 검증·fencing, production·키 없음이면 집지 않음 -- 핵심.
- [x] `backend/.../operations/**`·`OperationsConfiguration` -- Worker의 tombstone 키 bean, `LogSecretGuard` 대상 -- 마지막 단계.
- [x] `backend/.../support/**` 문의 제출 -- 생명주기 잠금 재검사 -- AC-4.
- [x] `backend/.../AccountDeletionController.java`·`AccountDeletionRequestService.java` -- `RequestView.progress`(상태·실패 단계 code), `POST .../retry` -- 화면 계약.
- [x] `frontend/src/content/accountDeletion.ts`·공유 표·`session/accountDeletion.ts`·`DeletionProgressScreen.tsx` -- 진행·재시도 필요·실패 범위 문장(닫힌 맵)·`다시 시도` -- 화면.
- [x] 시험: 두 사용자 표본(identity·source·portfolio·sync·plan·engagement·operations) 정상 실행 뒤 한 사용자만 0행·다른 사용자 보존, 단계 실패 주입·재시도·성공 단계 미실행, 늦은 문의·Worker 쓰기의 잔여 되돌림, fencing 상실, production·키 없음 미집기, 단일 모드 회귀(`RestoreApplyTest`·리허설), `ModuleBoundaryTest` 갱신, 프런트 진행·재시도 시험.

**Acceptance Criteria:**
- Given 두 사용자에 전 모듈 표본이 있고 한 사용자만 삭제 요청했을 때, when Worker가 실행을 마치면, then 그 사용자의 provider account·connection·membership·source snapshot·portfolio snapshot/component/pointer·계획·작업·문의·광고·감사 수행자 귀속이 모두 0이고 다른 사용자 행은 그대로다.
- Given 실행이 `운영 데이터 삭제 완료`이면, when 상태를 읽으면, then 운영 완료 시각이 있고 백업 파기는 완료로 표시하지 않는다.
- Given 단계가 실패해 재시도 필요이면, when 아무도 재시도하지 않으면, then Worker는 그 실행을 다시 집지 않는다.

## Spec Change Log

- 1차 리뷰(2026-11-05, blind·edge-case·verification-gap) — intent_gap·bad_spec 0, patch 21·defer 2·reject 5. patch: 같은 키 재시도가 다시 실패한 실행을 재대기열에 넣지 않던 것, 실패 기록 실패·단계 밖 예외·null 결과가 기록 없이 lease 만료 재집기(조용한 자동 재시도)로 가던 것(새 트랜잭션 기록 3회, 끝내 못 하면 `UNRECORDED`·error 로그 뒤 lease 만료 복구), 단계 트랜잭션의 실행 행 잠금이 전역 `synchronized` heartbeat를 붙잡던 것(lease별 갱신·`lock_timeout`, heartbeat 주기 상수 하나, Worker당 동시 실행 2), 별도 `ContextClosedEvent` drain(sync `DrainParticipant` SPI로 `WorkerDrain`의 같은 예산·종료 코드 75에 합류, 잠금 상실도 claim 정지), 단일 모드가 남기던 미마감 실행 행(`finishRunBySingleErasure`)·요청 없는 실행 claim, ACTIVE 계정 자격정보 erase 방어, V31 claim 부분 색인, 화면의 재시도 뒤 재조회·낡은 문장·키 폐기·본문 없는 5xx 무응답·모르는 단계 중복 제거, 주석 넷(단계 갱신의 세대 술어, 문의 잠금 순서, `AccountWriteAccess` 구현, 재시도 엔드포인트), 시험 여덟(실제 dispatch·heartbeat·drain, 동시 claim, 실행 도중 fencing 상실, tombstone 키 로그 가림, 실제 `keyAvailable`, 요청 없음 404·claim 전 409·GET `IN_PROGRESS`, 판정 뒤 삭제 시작의 문의 404, 서버 실제 `progress` 모양과 공유 표 상태 값), CLAUDE.md(재시도 엔드포인트·3.10 두 모드·5.2 계약 줄). defer: 삭제 실행의 운영 관측(메트릭·경보), 실행·단계 기록의 보존 기한. reject: 확정·재시도 응답의 진행 판독 실패를 진행 중으로 접는 것(5.1의 "커밋 뒤 오류로 바꾸지 않는다"), SPEC 작업 줄의 AC 번호(epics.md 스토리 AC를 가리킨다), 단계 건수가 지우기 전 잔여 수인 것(지울 대상 수가 의도), 단일 모드의 내부 호출 순서 변경(상태·행 결과가 같아 Ask First의 "관측 가능한 결과"가 아니다 — `AccountErasureTest`의 호출 순서 목록만 바뀌었다), 늦은 쓰기 경합 시험을 순차로 둔 것(잔여 판독이 경합과 무관하게 잡는다).
- 구현에서 확정한 것 — 실행 상태 `QUEUED|RUNNING|RETRY_NEEDED|OPERATIONAL_DATA_ERASED`, 단계 상태 `PENDING|SUCCEEDED|RETRY_NEEDED`, 실패 code `STEP_FAILED|RESIDUAL_FOUND`. 실행 행은 첫 claim이 만들고(5.1 확정 경로 무변경), 요청 행이 있을 때만 집는다. 재시도 응답은 202(같은 키 재전송도 202), 키 없음 422, 재시도 필요가 아니면(claim 전 포함) 409 `ACCOUNT_DELETION_NOT_RETRYABLE`, 요청 없음·접근 불가는 없는 경로와 같은 404다. 단일 모드가 지운 계정의 실행은 미완료 단계를 성공·건수 0으로 마감한다(그 실행이 아니라 단일 모드가 지웠다는 표시). 시험 컨텍스트는 Worker 주기 실행을 끈다(`bokrilog.account-deletion.worker.scheduled=false`) — 켜면 다른 시험의 삭제 요청 계정을 지워 경합한다. 매트릭스 "늦은 쓰기"는 문의 행 직접 삽입 시험이 덮는다(실제 접수는 이제 잠금 재검사로 막힌다). 단계 트랜잭션의 null 결과는 주입하지 못해 코드 경로만 있다. 검증: 프런트엔드 다섯 검사·Vitest 68 파일 1,621건 통과(1 skip), 백엔드 바뀐·지정 클래스 16개 `--rerun` 통과(`-Werror` 경고 없음). 패치 전 첫 구현의 전체 `build`는 1,673건 중 1건이 이 PC의 `Bad address` 소켓 결함으로 실패했다가 그 클래스만 다시 돌려 통과했고, 패치 뒤 전체 판정은 PR CI `backend-quality`가 소유한다.
- 2차 리뷰(2026-11-05, `review_loop_iteration: 1`) — intent_gap·bad_spec 0, patch 22·defer 0·reject 3. patch: 30초 넘는 단계의 lease 상실(`hold`가 세대만 보고 같은 문장에서 lease를 민다), 자격정보 erase의 상태 판정을 생명주기 잠금 판독으로, 단일 모드의 잠금 순서(실행 행 → 생명주기), 확정 응답의 진행을 고정값으로 두어 5.1 같은 키 재전송의 같은 본문 계약 복원, 재시도 접수 뒤 실패 단계 목록 비움, drain 중 lease 반납 실패를 `UNRECORDED`로, 스레드 시작 실패의 진행 중 슬롯 누수, `lockLost`의 참여자 격리, heartbeat 실패 경고 로그, `IDENTITY` 잔여 로그·시도 횟수, 예외 단순 이름 로그, production 재시도 403 `ACCOUNT_DELETION_UNAVAILABLE`, 새 실행 claim의 `SKIP LOCKED`, 화면의 엄격한 `progress` 판독·언마운트 뒤 재시도 갱신 차단·자체 스타일·재시도 카피 정정, 시험 넷(역할별 bean과 실제 `WorkerDrain` 배선, 생성자 경로의 production 판정, 동시 실행 상한, 문의 경합 대기 대상 한정·executor 정리), CLAUDE.md 5.2 계약 줄 보강. reject: Worker 실행 중 `Error`(비검사 오류)가 실행을 `RUNNING`으로 남기는 것(치명 오류는 프로세스 장애와 같고 lease 만료 복구가 그 경로다), 단일 모드 마감이 이전 실패 이력을 덮는 것(단일 모드가 그 계정을 지웠다는 최종 사실이 맞다), 늦은 쓰기 판정을 단일 모드에서 생략하는 것(단일 모드는 한 트랜잭션이라 단계 사이 창이 없다). 스토리 AC-1("세션을 먼저")에서 요청 세션을 `IDENTITY`까지 두는 것은 5.1이 승인한 상태 전용 세션 예외(`epic-5-context.md` "Story 5.1이 확정한 예외")를 잇는 accepted deviation이다 — 다른 세션은 5.1 확정이 이미 폐기했다.
- 2차 patch 뒤 검증(2026-11-05) — 프런트엔드 다섯 검사 통과(직접 재확인), 전체 Vitest 68 파일 1,626건 통과(1 skip, 백엔드와 동시 실행한 한 번의 `SettingsReconnect` 1건은 손대지 않은 파일이고 단독 5/5·부하 없는 전체 재실행 통과). 백엔드 대상 20 클래스 `--rerun` 통과(신규 `AccountErasureWorkerRoleTest` 포함, `-Werror` 경고 없음). 전체 `build`는 1,700건 중 524건이 실패했지만 전부 이 PC의 `Bad address` 소켓 결함과 그 연쇄(컨텍스트 적재 실패·failure threshold)였고 단정 실패는 0건이며, 실패한 36 클래스를 다시 돌려 모두 통과했다. 남은 관찰: 단계 트랜잭션이 실행 행을 쥔 동안 heartbeat는 500ms 잠금 대기 뒤 건너뛰며 warn 한 줄을 남긴다(긴 단계면 5초마다) — 단계 시작의 `hold`가 lease를 밀므로 정확성 문제는 아니다. 백엔드 전체 판정은 PR CI `backend-quality`가 소유한다.

## Design Notes

**단계 모드와 단일 모드.** 리허설 표본과 복원 적용은 한 계정을 한 트랜잭션으로 다시 지워야 한다(복원 적용은 실패하면 전체를 되돌려야 안전하다). 그래서 단계 목록은 하나이고, 단일 모드는 그 목록을 한 트랜잭션에서, 단계 모드는 단계마다 트랜잭션을 열어 돈다. 참여자 나열이 두 곳(실행·잔여)으로 갈라지는 문제(67)를 목록 하나로 줄인다.

**완료의 근거.** FK로 identity에 묶인 모듈은 잔여가 있으면 마지막 단계가 실패하지만 operations·support·engagement는 FK가 없다. 그래서 단계 성공도, 마지막 단계도 잔여 판독 0을 직접 확인한다.

**실행 행의 생성.** 5.1 확정 경로는 바꾸지 않는다. Worker가 "요청 행이 있고 실행 행이 없거나 대기인 `DELETION_IN_PROGRESS` 계정"을 집으며, 실행 행은 첫 claim이 만든다(5.1 머지 뒤 이미 접수된 요청도 같은 경로로 실행된다).

**세션을 먼저 지우는 것(AC-1)과 요청 세션.** 다른 세션은 5.1 확정이 이미 폐기했고 삭제 중 계정은 로그인이 거부된다. 남는 것은 5.1이 승인한 상태 전용 요청 세션 하나이고, 재시도를 누를 자리이므로 `IDENTITY` 단계까지 둔다. `CREDENTIALS`가 첫 단계로 Bybit 자격정보와 소셜 로그인 연결을 지운다.

**요청 세션의 끝.** `IDENTITY` 단계가 세션을 모두 지우므로 진행 화면의 다음 확인은 401이 되고 지금처럼 진입 화면으로 간다. 완료 안내는 5.4 몫이다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 통과(로컬 소켓 결함이면 대상 클래스 `--rerun`과 PR CI `backend-quality`)
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run test` -- expected: 통과

## Suggested Review Order

**단계 실행과 부분 실패**

- 진입점 — 단계 순서대로 돌고, 성공 단계는 건너뛰고, 실패하면 기록하고 멈춘다.
  [`AccountErasureRun.java:114`](../../backend/src/main/java/com/bokrilog/AccountErasureRun.java#L114)
- 단계 하나 = 트랜잭션 하나: fencing·lease 갱신 → 삭제 → 잔여 0 확인.
  [`AccountErasureRun.java:163`](../../backend/src/main/java/com/bokrilog/AccountErasureRun.java#L163)
- 기록 실패도 조용한 재집기로 가지 않게 새 트랜잭션으로 다시 기록한다.
  [`AccountErasureRun.java:155`](../../backend/src/main/java/com/bokrilog/AccountErasureRun.java#L155)
- 단계 순서의 유일한 목록, 자격정보가 먼저·identity가 마지막.
  [`DeletionStep.java:18`](../../backend/src/main/java/com/bokrilog/identity/DeletionStep.java#L18)
- 단계별 port 호출과 잔여 판독, 단일·단계 두 모드가 공유한다.
  [`AccountErasure.java:139`](../../backend/src/main/java/com/bokrilog/AccountErasure.java#L139)
- 단일 모드: 실행 행 → 생명주기 잠금 순서, 남은 실행 마감.
  [`AccountErasure.java:112`](../../backend/src/main/java/com/bokrilog/AccountErasure.java#L112)

**상태 권위와 lease**

- 실행·단계 표, 생명주기 행 FK, claim 부분 색인.
  [`V31__identity_deletion_run.sql:1`](../../backend/src/main/resources/db/migration/V31__identity_deletion_run.sql#L1)
- 세대만 보고 lease를 미는 단계 시작 판정.
  [`IdentityAccountRepository.java:477`](../../backend/src/main/java/com/bokrilog/identity/internal/IdentityAccountRepository.java#L477)
- 새 실행 claim — 요청 행이 있을 때만, `SKIP LOCKED`.
  [`IdentityAccountRepository.java:421`](../../backend/src/main/java/com/bokrilog/identity/internal/IdentityAccountRepository.java#L421)
- 재시도 접수: 재시도 필요면 키와 무관하게 재대기열.
  [`AccountDeletionRunService.java:141`](../../backend/src/main/java/com/bokrilog/identity/internal/AccountDeletionRunService.java#L141)

**Worker 배선**

- production·키 없음·종료 중이면 집지 않는다.
  [`AccountErasureWorker.java:97`](../../backend/src/main/java/com/bokrilog/AccountErasureWorker.java#L97)
- 주기 dispatch, 동시 실행 상한 2, 스레드 시작 실패 정리.
  [`AccountErasureWorker.java:103`](../../backend/src/main/java/com/bokrilog/AccountErasureWorker.java#L103)
- 기존 drain 예산·종료 코드에 참여자로 합류.
  [`WorkerDrain.java:151`](../../backend/src/main/java/com/bokrilog/sync/internal/WorkerDrain.java#L151)

**표면과 늦은 쓰기 방어**

- 재시도 엔드포인트와 production 게이트.
  [`AccountDeletionController.java:311`](../../backend/src/main/java/com/bokrilog/AccountDeletionController.java#L311)
- 확정 응답은 고정 진행 — 5.1 재전송 같은 본문 유지.
  [`AccountDeletionController.java:140`](../../backend/src/main/java/com/bokrilog/AccountDeletionController.java#L140)
- ACTIVE 계정 자격정보 방어(생명주기 잠금 판독).
  [`SourceConnectionService.java:327`](../../backend/src/main/java/com/bokrilog/source/internal/SourceConnectionService.java#L327)
- 문의 접수의 생명주기 잠금 재검사 port.
  [`SupportInquiryService.java:64`](../../backend/src/main/java/com/bokrilog/operations/internal/SupportInquiryService.java#L64)

**진행 화면**

- 재시도 필요·실패 범위·`삭제 다시 시도`, 응답 뒤 재조회.
  [`DeletionProgressScreen.tsx:135`](../../frontend/src/screens/DeletionProgressScreen.tsx#L135)
- 엄격한 `progress` 판독(없거나 모르면 읽지 못함).
  [`accountDeletion.ts:122`](../../frontend/src/session/accountDeletion.ts#L122)
- 무응답 판정을 확정과 공유하는 재시도 요청.
  [`accountDeletion.ts:350`](../../frontend/src/session/accountDeletion.ts#L350)
- 단계 code → 실패 범위 문장 닫힌 맵(공유 표 대조).
  [`accountDeletion.ts:168`](../../frontend/src/content/accountDeletion.ts#L168)

**시험**

- 두 사용자 전 모듈 표본 정상 실행·실패 재시도·늦은 쓰기·fencing.
  [`AccountErasureRunTest.java:91`](../../backend/src/test/java/com/bokrilog/AccountErasureRunTest.java#L91)
