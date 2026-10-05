---
title: 'Bybit 연결 해제와 기존 계획 기록의 읽기 전용 보존'
type: 'feature'
created: '2026-10-05'
status: 'done'
baseline_commit: 'b144e9abccc5f4e214f4e1901af906bad7ae8e07'
review_loop_iteration: 0
context:
  - '{project-root}/CLAUDE.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/epics.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-1-bybit-connection.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-13-plan-early-end.md'
---

<frozen-after-approval reason="Story 2.16 AC-1~9의 사용자 소유 범위">

## Intent

**Problem:** 연결은 `ACTIVE`만 만들 수 있고 끊을 방법이 없다. 사용자는 BokriLog가 들고 있는 Bybit 자격정보를 지우고 이후 동기화를 멈출 수 없으며, 끊은 뒤 기존 계획·근거가 어떻게 남는지도 알 수 없다.

**Approach:** 계획 중도 종료(2.13)와 같은 준비→확인→확정·취소 흐름과 `SECURITY-ACTION-1.0` 허가로, 짧은 트랜잭션 하나에서 자격정보 삭제·`ACTIVE → REVOKED`·membership 종료·작업 취소를 원자 커밋한다. 계획·금융 기록은 건드리지 않고 화면은 해제 사실과 읽기 전용 기록을 보여 준다.

## Boundaries & Constraints

**Always:**
- 경로는 `POST /api/connections/bybit/revocation`(준비)·`/confirm`·`/cancel` 셋이다. CSRF·`Origin`·세션 인가는 기존 `/api/connections/` 술어를 쓴다. 대상은 본문의 `connectionId`이며 `GET /api/connections/bybit`가 불투명 `connectionId`와 해제 상태(`revoked`, `revokedAt`)를 함께 돌려준다. 다른 사용자·미존재·이미 해제된 대상의 준비는 같은 단일 거부다.
- 허가는 command `bybit-connection-revoke`, target `connectionId`, 정본 확인 문구 `bybit-connection-revoke|<connectionId>`에 귀속한다. 5분 재인증·일회 소비·세션 귀속을 따르고, 실제로 해제한 요청만 무장을 버리고 세션을 회전한다. 상대 흐름(연결·해제·로그인 수단 연결/해제·재인증)은 게이트 통과 뒤에만 버린다.
- 멱등은 두 층이다. 해제 결과는 연결 행에 `revoked_at`·멱등 키·요청 hash로 저장하고, 같은 키·같은 요청은 무장이 없어도 저장된 결과를 200으로 재생하며 다른 요청은 `IDEMPOTENCY_CONFLICT`다. 허가의 `(user_id, operation_id)`가 동시 확정을 하나로 줄인다.
- 확정 트랜잭션은 허가 소비와 같은 하나이며 잠금 순서는 계획 advisory → 계정 생명주기(WRITE 판정) → 작업 행이다. 그 안에서 연결 행을 잠그고 `ACTIVE`를 재확인한 뒤 `source_credential` 행 삭제, `status='REVOKED'`·`revoked_at=statement_timestamp()`, 해당 연결의 활성 membership `ended_at=revoked_at`, 여섯 작업 표(`sync_job`·초기 수집·판정·환산·발행·`sync_plan_sync_job`)의 그 연결 `QUEUED`·`RUNNING` 행을 각 표의 기존 거부 종단(`NOT_PERMITTED` 계열)으로 끝낸다. 하나라도 실패하면 전부 롤백하고 연결은 `ACTIVE` 그대로다 — 반쯤 지운 상태가 없다.
- 해제 뒤 모든 stage·heartbeat·claim·발행은 이미 있는 `snapshotConnection`(ACTIVE+자격정보 join) 재검사로 막힌다. 모든 공식 쓰기 경로가 생명주기 잠금 **뒤** 연결을 다시 읽는지 확인하고, 아니면 그 순서로 고친다. 늦은 worker는 source event·snapshot·계산·watermark·포인터를 쓰지 못한다.
- 연결 행·제공자 계정·portfolio·과거 membership·계획·완료/종료 기록·자금흐름·snapshot·계산·근거는 지우지 않는다. 해제는 cascade를 일으키지 않는다(연결 행을 지우지 않으므로 membership CASCADE도 돌지 않는다). 계정 삭제(`eraseSourceData` 이하 기존 순서)는 해제 메타데이터까지 지우고 다른 사용자는 남긴다.
- 해제된 연결이 있는 사용자의 새 연결 준비는 단일 거부다(`RECONNECT-1.0` 구현 전 차단). 화면은 동작하지 않는 재연결 조작을 내지 않고 "다시 연결하는 기능은 아직 제공하지 않는다"는 중립 안내만 둔다.
- 대시보드: 계획의 연결이 해제됐으면 `sync.availability=UNAVAILABLE`·`reason=CONNECTION_REVOKED`이고 신선도 축이 해제 원인과 "새 데이터가 갱신되지 않음"을 낸다. 동기화·정밀 검증·후속 기준 준비 조작은 렌더하지 않는다. 공식 결과·근거·조정 이력·과거 기록 조회는 그대로 200이다.
- 확인 대화상자는 자격정보 즉시 삭제·작업 취소·새 동기화 불가·보존 범위(계획·조정 이력·계산 근거·완료 결과)를 구분해 쓰고, BokriLog가 Bybit의 API Key를 폐기하지 못하며 필요하면 Bybit에서 직접 폐기할 수 있다고 중립적으로 안내한다. 기본 초점은 취소다. 결과는 화면 live region 하나의 status 문장, 상태는 글자로도 표시한다.
- 로그·지표는 해제 성공·거부·지연·늦은 발행 차단을 code만으로 남긴다. 자격정보·금융 상세·허가 참조·멱등 키는 URL·로그·브라우저 저장소에 없다.

**Ask First:** 새 런타임 의존, 새 브라우저 경로, 해제 이후 재연결 허용, Bybit 원격 key 폐기 호출, 작업 표 CHECK를 넓히는 새 failure code.

**Never:** 연결 행·계획·금융 기록 삭제, 연결 교체, 백그라운드 정리 작업, 다른 모듈 소유 테이블 직접 접근, 평문·복호값 보존.

## I/O & Edge-Case Matrix

| 상태/입력 | 결과 | 실패 처리 |
|---|---|---|
| 활성 소유 연결·신선한 재인증·확인 | 200 해제, 자격정보 0행, `REVOKED`, membership 종료, 작업 종단 | 세션 회전 |
| 타 사용자·미존재·이미 해제 대상의 준비 | 같은 단일 거부 | 존재 여부 비노출 |
| 같은 키 재전송(해제 뒤) / 다른 요청 같은 키 | 저장 결과 재생 / `IDEMPOTENCY_CONFLICT` | 상태 불변 |
| 만료·재사용·다른 연결·다른 command 허가, 오래된 재인증 | 거부 | 연결 `ACTIVE` 유지 |
| 확정 중 단계 실패 | 전체 롤백, 허가 미소비 | 실패 안내, 기존 상태 표시 |
| 대기·실행·API 응답 후 worker | 발행·포인터·watermark 쓰기 0 | 작업 종단 |
| 활성·중도 종료·완료 계획 보유 | 기록·근거·이력 200, 동기화 조작 없음 | `CONNECTION_REVOKED` 표시 |
| 해제 뒤 새 연결 시도 | 단일 거부, 재연결 UI 없음 | 중립 안내 |
| 해제 뒤 계정 삭제 | 해제 메타데이터 포함 제거, 타 사용자 보존 | 기존 삭제 계약 |

</frozen-after-approval>

## Code Map

Java 경로는 `backend/src/main/java/com/bokrilog/` 기준이다.
- `PlanEndCommandService.java`·`PlanEndFlow.java`·`PlanEndController.java` — 복제할 흐름: 준비(발급 뒤 무장·이전 참조 무효화)→확정(WRITE 판정→재생→무장 대조→`grants.consume`(작업)→Denied 재생)→취소(참조 무효화)·회전.
- `BybitConnectionController.java:142,176,209,303` — 상태 GET·준비·취소. `ConnectionState`에 `connectionId`·`revoked`·`revokedAt` 추가, 상대 흐름 abandon 배치 재사용. `BybitConnectionService.java:200,315` — 활성 연결 사전 확인에 "해제된 연결 있음" 거부 추가.
- `source/SourceConnectionApi.java`·`source/internal/SourceConnectionRepository.java` — 연결 상태 조회(활성/해제), 호출자 트랜잭션 안의 `revoke`(행 잠금·ACTIVE 재확인·자격정보 삭제·상태 전환·멱등 기록), 재생 조회. `eraseOf`는 그대로 해제 행까지 지운다.
- `portfolio/PortfolioMembershipApi.java`·`portfolio/internal/PortfolioRepository.java` — 연결의 활성 membership을 주어진 시각으로 닫는 호출자 트랜잭션 메서드.
- `sync/SyncJobApi.java`·`sync/internal/SyncJobService.java`(`CONNECTION_COMMAND`, 다수 `snapshotConnection` 지점) — 계획 advisory 잠금(`PlanPublicationPort.lockPlanWrites` 경유) + 여섯 작업 표의 연결 작업 종단화 메서드. 각 표의 CHECK(`V5`·`V7`~`V11`)가 허용하는 종단·code만 쓴다.
- `sync/internal/PlanSyncGuard.java:30,96-147` — `CONNECTION_UNAVAILABLE` 판정에 해제 구분(`CONNECTION_REVOKED`) 추가. `DashboardTrust.java`·`DashboardController.java` — 신선도 원인과 sync reason 전달. `PlanPublicationService.java:406,431`, `PlanSyncPublisher.java`, `FirstSnapshotWorker.java` — 생명주기 잠금 뒤 연결 재검사 순서 확인.
- `AuthSecurityConfig.java:250` — `BYBIT_CONNECTION_PATH` 아래 새 경로가 기존 인가·CSRF 술어에 들어가는지 확인.
- `frontend/src/screens/SettingsScreen.tsx:554-620`, `session/bybitConnection.ts:49,253`, `session/useBybitConnection.ts` — 상태 파싱 확장·해제 조작. `components/PlanEndDialog.tsx`·`UnlinkDialog.tsx` — 대화상자·초점 복귀 패턴. `components/SyncControl.tsx`·`VerificationControl.tsx`·`NextPlanBaselinePanel.tsx`·`content/planSync.ts` — `CONNECTION_REVOKED` 카피·조작 제거. `screens/OnboardingScreen.tsx` — 해제된 사용자에게 연결 폼 대신 안내.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/resources/db/migration/V16__connection_revocation.sql` — `source_connection`에 `revoked_at`·`revocation_idempotency_key`·`revocation_request_hash`와 `status`↔`revoked_at` 짝 CHECK, `(user_id, revocation_idempotency_key)` 부분 유일 색인을 expand로 추가 — 해제 시각·멱등의 DB 권위.
- [x] `source/SourceConnectionApi.java`, `source/internal/SourceConnectionService.java`, `SourceConnectionRepository.java` — 상태 조회·`revoke`·재생 구현 — 자격정보 삭제와 상태 전환의 소유 모듈.
- [x] `portfolio/PortfolioMembershipApi.java`, `portfolio/internal/PortfolioMembershipService.java`, `PortfolioRepository.java` — membership 종료 — portfolio 소유.
- [x] `sync/SyncJobApi.java`, `sync/internal/SyncJobService.java`·관련 Repository, `PlanSyncGuard.java`, `DashboardTrust.java`, 발행 경로 — 작업 종단화·`CONNECTION_REVOKED`·잠금 뒤 재검사.
- [x] `BybitRevocationFlow.java`·`BybitRevocationCommandService.java`(신규), `BybitConnectionController.java`, `BybitConnectionService.java`, `AuthSecurityConfig.java` — HTTP·허가·트랜잭션 조율·재연결 차단.
- [x] `frontend/src/content/connection.ts`·`planSync.ts`, `session/bybitConnection.ts`·`useBybitConnection.ts`, 신규 `components/ConnectionRevokeDialog.tsx`(+module.css, 기존 토큰만), `SettingsScreen.tsx`, `OnboardingScreen.tsx`, `SyncControl.tsx`·`VerificationControl.tsx`·`NextPlanBaselinePanel.tsx` — 확인·결과·해제 상태 화면.
- [x] `backend/src/test/java/com/bokrilog/BybitConnectionRevocationTest.java`(신규)와 기존 모듈 경계·삭제 시험, `frontend/src/screens/SettingsConnectionRevoke.test.tsx`(신규)·관련 컴포넌트 시험 — matrix 전 행, 경합(동시 확정·worker·발행·계정 삭제), 비밀 부재, 접근성.

**Acceptance Criteria:**
- Given Story 2.16 AC-1~9, when 테스트 기준을 실행하면, then 해제는 원자적으로 한 번만 일어나고 자격정보는 남지 않으며 늦은 쓰기는 0건이고 기존 기록은 모두 읽기 전용으로 열린다.
- Given Story 2.13~2.16 통합 시험이 통과하면, when 기록할 때, then M2-4 검증 지점을 충족으로 남긴다.

## Spec Change Log

## Design Notes

연결 행을 지우지 않고 `REVOKED`로 남기는 것이 보존의 핵심이다. `portfolio_source_membership.connection_id`가 `ON DELETE CASCADE`라 행을 지우면 membership과 그것을 가리키는 snapshot 구성 체인이 RESTRICT에 부딪히거나 사라진다. 자격정보 행만 지우면 `snapshotConnection`의 join이 비어 모든 기존 재검사 지점이 자동으로 닫힌다 — 새 검사 지점을 흩뿌리지 않는다.

재연결을 막는 이유: 지금의 연결 생성은 새 membership을 만들 뿐 활성 계획과 UID를 대조하지 않는다. 열어 두면 다른 UID가 활성 계획의 portfolio에 붙는다(`RECONNECT-1.0` 위반). 재연결은 별도 정책 스토리가 연다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` — expected: 전체 시험·`ModuleBoundaryTest`·`-Werror` 통과(Docker 필요).
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run test` — expected: 통과.
- `bash .github/scripts/test-ci-scripts.sh` — expected: 통과.

**실행 결과(2026-10-05, 리뷰 patch 반영 뒤):** 백엔드 build 시험 1127건 실패·skip 0. 프런트엔드 Vitest 39파일 1191건 통과. CI 스크립트 시험 147/147. 남은 위험: 큐 상태(QUEUED) 작업 종단과 V16 backfill은 실제 행으로 시험하지 못했다(같은 SQL 조건이 처리). 일부 단계 트랜잭션의 자식→작업 행 잠금 순서와 해제가 겹치면 교착 감지로 해제가 500(전체 롤백)일 수 있다.

## Suggested Review Order

**해제 흐름의 진입점**

- 확정 한 트랜잭션: 재생 → 무장 대조 → 허가 소비 안의 잠금·삭제·전환·종단
  [`BybitRevocationCommandService.java:188`](../../backend/src/main/java/com/bokrilog/BybitRevocationCommandService.java#L188)

- 준비는 아무것도 바꾸지 않고 정본 문구에 귀속한 허가만 발급
  [`BybitRevocationCommandService.java:156`](../../backend/src/main/java/com/bokrilog/BybitRevocationCommandService.java#L156)

- HTTP 표면 셋: 준비·확정·취소와 응답 code 매핑
  [`BybitConnectionController.java:400`](../../backend/src/main/java/com/bokrilog/BybitConnectionController.java#L400)

**자격정보 삭제와 상태 전환(source)**

- 행 잠금 뒤 ACTIVE 재확인, 자격정보 정확히 한 행 삭제
  [`SourceConnectionService.java:274`](../../backend/src/main/java/com/bokrilog/source/internal/SourceConnectionService.java#L274)

- 해제 시각은 DB 시각이되 검증·membership 시작보다 늦게 단조 보장
  [`SourceConnectionRepository.java:207`](../../backend/src/main/java/com/bokrilog/source/internal/SourceConnectionRepository.java#L207)

- 연결 행을 남기는 V16 짝 CHECK와 기존 REVOKED 행 backfill
  [`V16__connection_revocation.sql:1`](../../backend/src/main/resources/db/migration/V16__connection_revocation.sql#L1)

**membership 종료와 작업 차단**

- 해제 시각 하나로 여섯 작업 표의 진행 중 작업을 기존 거부 종단으로
  [`ConnectionRevocationRepository.java:38`](../../backend/src/main/java/com/bokrilog/sync/internal/ConnectionRevocationRepository.java#L38)

- portfolio 공개 API로 활성 membership 시작을 읽어 하한으로 넘김
  [`PortfolioRepository.java:214`](../../backend/src/main/java/com/bokrilog/portfolio/internal/PortfolioRepository.java#L214)

- 해제를 다른 연결 불가와 구분하는 사유
  [`PlanSyncGuard.java:35`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncGuard.java#L35)

- 신선도 축이 해제 원인과 다음 행동 없음을 냄
  [`DashboardTrust.java:284`](../../backend/src/main/java/com/bokrilog/sync/internal/DashboardTrust.java#L284)

- 해제된 사용자의 새 연결 차단(RECONNECT-1.0 구현 전)
  [`BybitConnectionService.java:187`](../../backend/src/main/java/com/bokrilog/BybitConnectionService.java#L187)

**화면**

- 확인 대화상자: 보존 범위·원격 폐기 한계, 취소 기본 초점, 내부 status
  [`ConnectionRevokeDialog.tsx:22`](../../frontend/src/components/ConnectionRevokeDialog.tsx#L22)

- 본문 있는 5xx는 실패, 무응답만 같은 키 재시도
  [`bybitConnection.ts:228`](../../frontend/src/session/bybitConnection.ts#L228)

- 설정의 해제됨 표지와 결과 문장
  [`SettingsScreen.tsx:56`](../../frontend/src/screens/SettingsScreen.tsx#L56)

- 해제 사유일 때 동기화 조작 없이 사유 문장만
  [`SyncControl.tsx:297`](../../frontend/src/components/SyncControl.tsx#L297)

**시험**

- matrix 전 행, 경합·롤백·늦은 worker·계정 삭제
  [`BybitConnectionRevocationTest.java:59`](../../backend/src/test/java/com/bokrilog/BybitConnectionRevocationTest.java#L59)

- 설정 화면 해제 흐름·inert 배경·낭독
  [`SettingsConnectionRevoke.test.tsx:1`](../../frontend/src/screens/SettingsConnectionRevoke.test.tsx#L1)
