---
title: '첫 정상 현재가치와 측정 시각 확인'
type: 'feature'
created: '2026-09-25'
status: 'done'
baseline_commit: '3e052e23dbe7db3faeca2c9e677c2c2f4e500b81'
review_loop_iteration: 0
context:
  - '_bmad-output/implementation-artifacts/epic-2-context.md'
  - '_bmad-output/planning-artifacts/epics.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 연결 검증은 끝나도 사용자는 계획의 출발점이 될 공식 현재가치와 그 측정 시각을 볼 수 없다.

**Approach:** 첫 자산 확인을 비동기 작업으로 접수하고 검증된 Bybit 지갑 값으로 source·portfolio의 첫 공식 스냅샷을 원자 발행한다. 진행·결과·실패를 같은 출처의 상태로 표시한다.

## Boundaries & Constraints

**Always:** Story 2.2 AC-1~11을 충족한다. 접수는 202이며 중복은 같은 작업을 돌려준다. Worker는 lease·heartbeat·증가하는 fencing으로 독점하고 외부 호출 중 DB 트랜잭션을 열지 않는다. admission·lease·발행·표시마다 소유권·삭제·연결·운영 제한을 다시 판정한다. `totalEquity`는 canonical 십진 문자열로 보존하고 Bybit 측정 시각을 수신·발행 시각과 분리한다. 정상 0·음수를 보존한다. source snapshot·유일 component·USD portfolio snapshot·공식 포인터·작업 성공은 한 outer `REQUIRED` 트랜잭션이다. 금액은 화면에서만 반올림하고 비밀·원 응답은 노출하지 않는다. 새 데이터의 삭제 경로와 재발행 차단을 함께 만든다.

**Ask First:** 다중 source, 재시도 정책 변경, 별도 화면 경로 또는 새 런타임 의존이 필요해지면 범위·계약을 먼저 협의한다.

**Never:** 외부 자금흐름·계획·여섯 지표·증분 동기화·광고를 구현하지 않는다. 실패를 0으로 대체하거나 source snapshot을 화면·계산의 공식 권위로 삼지 않는다. V4 마이그레이션을 수정하지 않는다. 검증 실패를 자동 재시도하지 않는다.

## I/O & Edge-Case Matrix

| 상황 | 입력·상태 | 결과 | 실패 처리 |
|---|---|---|---|
| 접수 | 활성 연결, 공식값 없음 | 202, 작업 ID·상태 | 중복은 기존 작업 재사용 |
| 성공 | 유효한 `totalEquity`, Bybit 시각 | 두 스냅샷과 component·포인터 발행, 값·시각 표시 | 0·음수도 정상, 계획 생성 불가 이유 표시 |
| 외부 실패 | 인증·권한·UID/IP·timeout·rate limit·응답 오류 | 공식값 없음, 원인·다음 행동 | 자동 재시도·0 대체 금지 |
| 상태 변화 | 삭제·제한·연결 해제 또는 lease 상실 | 발행·표시 거부 | 후보·포인터·성공 상태 전체 롤백 |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/com/bokrilog/source/SourceConnectionApi.java`, `source/internal/SourceConnectionService.java` — 연결 조회·삭제 공개 경계. Worker의 자격정보 사용·중립적 현재가치 읽기 포트를 여기에 더한다.
- `backend/src/main/java/com/bokrilog/source/internal/BybitAdapter.java`, `CredentialCipher.java` — 서명 GET·지갑 십진 검증과 AES-GCM 복호 재사용. 기존 `verify()`는 값·시각을 버리며 admission의 무재시도 계약은 보존한다.
- `backend/src/main/java/com/bokrilog/portfolio/PortfolioMembershipApi.java`, `portfolio/internal/PortfolioRepository.java` — 활성 membership·USD portfolio 조회와 불변 snapshot·component·공식 pointer 발행 경계.
- `backend/src/main/java/com/bokrilog/operations/OperationalRestrictionApi.java` — 단계별 durable restriction 판독.
- `backend/src/main/java/com/bokrilog/BybitConnectionController.java`, `AuthSecurityConfig.java`, `ApiErrorHandler.java` — 루트 HTTP 표면, 인증·CSRF·Origin·오류 관용.
- `backend/src/main/resources/db/migration/V4__source_connection.sql` — 기존 연결·membership 스키마. 변경하지 않고 V5를 추가한다.
- `backend/src/test/java/com/bokrilog/source/internal/BybitAdapterTest.java`, `BybitConnectionConcurrencyTest.java`, `ModuleBoundaryTest.java` — 어댑터·경쟁·경계 시험의 확장 지점.
- `frontend/src/screens/SignedInScreen.tsx`, `frontend/src/screens/OnboardingScreen.tsx` — 기존 dashboard/온보딩 경로. 새 경로 없이 자산 확인과 결과 진입을 연결한다.
- `frontend/src/session/useBybitConnection.ts`, `frontend/src/content/connection.ts`, `frontend/src/content/auth.ts` — 요청 훅·연결 상태·화면 카피와 시각 표시 관용.
- `deploy/compose/verify-same-origin.sh`, `frontend/e2e/` — 스텁을 거친 실제 origin 왕복 검증.

## Tasks & Acceptance

**Execution:**

- [x] `backend/src/main/resources/db/migration/V5__first_snapshot.sql` — 작업·lease·source/portfolio snapshot·component·pointer 테이블과 소유권·유일성·참조 제약을 추가한다.
- [x] `backend/src/main/java/com/bokrilog/source/SourceConnectionApi.java`, `source/internal/SourceConnectionService.java`, `source/internal/BybitAdapter.java` — 저장 자격정보 복호와 provider-neutral 현재가치 DTO 포트를 추가하고 기존 지갑 호출·검증을 재사용한다.
- [x] `backend/src/main/java/com/bokrilog/sync/SyncJobApi.java`, `sync/internal/SyncJobService.java`, `sync/internal/SyncJobRepository.java`, `sync/internal/FirstSnapshotWorker.java` — 접수 멱등성, lease·heartbeat·fencing, 단일 outer 트랜잭션 발행과 삭제 API를 구현한다.
- [x] `backend/src/main/java/com/bokrilog/portfolio/PortfolioMembershipApi.java`, `portfolio/internal/PortfolioRepository.java` — membership 자격 검사, 불변 snapshot·유일 component·CAS pointer 및 사용자 데이터 삭제를 구현한다.
- [x] `backend/src/main/java/com/bokrilog/FirstSnapshotController.java`, `AuthSecurityConfig.java` — 첫 확인·작업 조회·공식값 조회 HTTP와 인증·CSRF·Origin 보호를 추가한다.
- [x] `frontend/src/screens/SignedInScreen.tsx`, `screens/OnboardingScreen.tsx`, `session/useFirstSnapshot.ts`, `content/firstSnapshot.ts` — 요청·상태 갱신·값/Bybit 측정 시각·세 신뢰 축·계획 없음/생성 경로·오류와 접근성을 표시한다. 첫 공식값에서 조건 검토 링크는 Story 2.3의 `/plan-conditions`로 연결한다.
- [x] `backend/src/main/java/com/bokrilog/DevBybitStub.java`, `backend/src/test/java/com/bokrilog/FirstSnapshotTest.java`, `frontend/src/screens/SignedInScreen.test.tsx`, `frontend/e2e/first-snapshot.spec.ts`, `deploy/compose/verify-same-origin.sh` — 유효한 제공자 시각 fixture, matrix, 원자성 실패 주입, 동시성·삭제 경쟁, UI·동일 출처 왕복을 검증한다.

**Acceptance Criteria:**

- Given 연결된 사용자에게 공식값이 없을 때, when 첫 확인을 반복 요청하면, then 202가 같은 작업을 반환하고 외부 호출은 요청 스레드 밖에서 한 번 실행된다.
- Given Worker 둘이 같은 작업을 다툴 때, when lease가 만료·갱신·상실되면, then 최신 fencing 소유자만 발행하고 외부 호출 동안 열린 DB 트랜잭션이 없다.
- Given 검증된 지갑 값과 시각, when 발행하면, then 같은 값·source ID를 참조하는 유일 component와 portfolio snapshot·CAS 포인터·작업 성공이 함께 커밋되며 `measured_at`은 Bybit 시각이다.
- Given 제한·삭제·연결 상태 변화 또는 발행 중 실패, when Worker가 발행을 시도하면, then 새 공식값과 부분 행이 없고 삭제 후에도 재발행되지 않는다.
- Given 성공·실패·대기 상태, when 화면을 열면, then 1초 내 텍스트 상태와 다음 행동을 읽을 수 있고 금액은 공식값에서만, 원 시각과 세 신뢰 축을 함께 보여준다.

## Spec Change Log

## Design Notes

Story 2.1의 연결 admission 조회는 스냅샷이 아니다. 첫 공식값은 Worker가 저장 자격정보로 다시 조회해 만든다. UI는 현재 `/dashboard`와 `/onboarding`을 사용하고 새 경로를 열지 않는다. 연결된 사용자가 첫 확인 조작에 닿을 수 있도록 기존 경로의 이동을 잇는다.

## Verification

**Commands:**

- `backend/gradlew --project-dir backend build` — Docker 기반 통합 시험·모듈 경계 통과
- `npm --prefix frontend run typecheck`, `npm --prefix frontend run lint`, `npm --prefix frontend run lint:css`, `npm --prefix frontend run format:check`, `npm --prefix frontend run test`, `npm --prefix frontend run build` — 전부 통과
- `deploy/compose/verify-same-origin.sh` — 첫 스냅샷 왕복과 기존 계약 통과
- `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 npm --prefix frontend run test:e2e:run` — 동일 origin 성공 화면 통과

**확인 결과 (2026-09-25):** 백엔드 전체 `build`(Docker 통합 시험 포함), 프런트엔드 typecheck·lint·lint:css·format:check·test·build, 동일 출처 942/942, 주입 origin Playwright 57/57, `git diff --check`가 통과했다. 마지막 UI 수정 후에는 `SignedInScreen.test.tsx` 39개와 typecheck·lint·format:check를 다시 통과했다.

**검증 결과 (2026-09-26):** AC-8의 양수 공식값에서 Story 2.3 `/plan-conditions` 화면으로 이어지는 경로를 확인했다. 백엔드 전체 `build`, 프런트 검사·284개 시험·production build, same-origin 계약 959/959, 주입 origin Playwright 57/57, CI 스크립트 계약 147/147, `git diff --check`가 통과했다.

## Suggested Review Order

**비동기 자산 조회와 원자 발행**

- 인증된 첫 확인 요청은 작업을 접수하고 상태 조회를 연다.
  [`FirstSnapshotController.java:38`](../../backend/src/main/java/com/bokrilog/FirstSnapshotController.java#L38)

- Worker가 lease를 얻어 자격정보로 Bybit 자산을 읽는다.
  [`FirstSnapshotWorker.java:13`](../../backend/src/main/java/com/bokrilog/sync/internal/FirstSnapshotWorker.java#L13)

- 작업 소유권·제한·연결을 재확인한 뒤 snapshot과 포인터를 함께 발행한다.
  [`SyncJobService.java:123`](../../backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java#L123)

- Bybit 응답의 자산값과 제공자 측정 시각을 검증한다.
  [`BybitAdapter.java:251`](../../backend/src/main/java/com/bokrilog/source/internal/BybitAdapter.java#L251)

**저장 경계와 사용자 흐름**

- 작업·원천 snapshot·portfolio snapshot·포인터의 소유권과 참조를 고정한다.
  [`V5__first_snapshot.sql:9`](../../backend/src/main/resources/db/migration/V5__first_snapshot.sql#L9)

- 로그인 화면에서 첫 확인 상태와 공식값을 표시한다.
  [`FirstSnapshotPanel.tsx:29`](../../frontend/src/components/FirstSnapshotPanel.tsx#L29)

- 양수 공식값에서 후속 계획 조건 화면으로 연결한다.
  [`FirstSnapshotPanel.tsx:92`](../../frontend/src/components/FirstSnapshotPanel.tsx#L92)

**검증**

- 중복 접수와 단일 원자 발행을 데이터베이스에서 검증한다.
  [`FirstSnapshotTest.java:21`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L21)

- 브라우저에서 연결부터 첫 공식값 표시까지 실제 origin으로 확인한다.
  [`first-snapshot.spec.ts:18`](../../frontend/e2e/first-snapshot.spec.ts#L18)

- 배포 형태의 same-origin 왕복 계약을 확인한다.
  [`verify-same-origin.sh:2329`](../../deploy/compose/verify-same-origin.sh#L2329)
