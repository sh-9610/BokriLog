---
title: '원천 사건을 외부 자금흐름과 계정가치 변동으로 판정'
type: 'feature'
created: '2026-09-27'
status: 'done'
baseline_commit: 0883c24d43832bdf8684bc5c37ef13f074f49d31
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics.md'
  - '{project-root}/_bmad-output/planning-artifacts/EPIC-IMPLEMENTATION-READINESS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-4-bybit-source-event-collection.md'
---

<frozen-after-approval reason="승인된 Epic 2 Story 2.5 범위와 BYBIT-CASHFLOW-1.0 계약">

## Intent

**Problem:** 완전 수집된 사건의 `cashFlow`를 검증 없이 입출금으로 더하면 거래 손익·수수료나 모순된 신호가 계획 원금에 잘못 반영될 수 있다.

**Approach:** 사용자 소유의 완전한 Story 2.4 후보만 고정해 `BYBIT-CASHFLOW-1.0` 결정표로 사건을 판정하고, provenance가 연결된 불변 자금흐름 후보와 제외 요약을 만든다.

## Boundaries & Constraints

**Always:** UNIFIED 계정·후보 사용자·portfolio·intent·연결·완전성 증거를 시작과 원자 publish에서 재검증한다. 정확한 `TRANSFER_IN` + 양수 `cashFlow`만 입금, `TRANSFER_OUT` + 음수만 출금이며 금액은 원자산 `abs(cashFlow)`다. 필수값 누락·0·부호 충돌·ID 의미 충돌은 `검토 필요`로 fail-closed 처리한다. 결과마다 원천 event ID와 semantic hash, provenance, 판정 rule ID, `BYBIT-CASHFLOW-1.0`, 처리시각을 추가 전용으로 보존한다. 같은 입력과 정책 재실행은 멱등이어야 하며 정책 변경은 기존 판정을 덮지 않는 새 버전이다. 삭제·운영 제한·intent 만료·연결 변경과 늦은 worker를 차단한다. 상태는 건수·안전한 이유·다음 행동만 same-origin `no-store` 화면에 접근성 있게 알린다.

**Ask First:** `BYBIT-CASHFLOW-1.0` 의미·허용 유형·경계나 Story 2.4의 provider 요청 유형을 넓히는 변경.

**Never:** `qty`, `change`, `cashBalance`, 이름 부분 또는 수량 변화로 방향을 추론하지 않는다. Story 2.4 입력 유형/endpoint를 확장하지 않는다. 비-USDT를 USD로 환산하거나 공식 계산·활성 계획·snapshot 포인터를 만들지 않는다. 원천 거래 상세·원수량을 화면이나 진단에 노출하지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| COMPLETE | 완전한 소유 후보, 정확한 type과 일치하는 nonzero 부호 | 불변 판정 묶음, 입금 양수·출금 음수, provenance 보존 | N/A |
| INTERNAL / VALUE_CHANGE | 정책에 등록된 비흐름 type | 흐름 후보에서 제외하고 이유·원천 근거 보존 | 원천 상세는 비공개 |
| REVIEW_REQUIRED | 미등록 type, 필수 근거 누락, 0 또는 type/sign 충돌, 불완전 후보 | 검토 상태, 공식 입력 사용 불가, 계산 차단 | 민감하지 않은 이유 코드 |
| REPLAY / REVISION | 동일 사건·정책 반복 또는 정책 version 변경 | 동일 결과 재사용; 새 판정 version은 과거 결과 보존 | 동일 source ID 의미 충돌은 자동 대체하지 않음 |
| REVOKED / DELETED | publish 전 intent·연결·계정 자격 상실 또는 삭제 경합 | 결과 미발행, 해당 사용자 결과 제거, 다른 사용자 보존 | 늦은 worker의 재생성 차단 |

</frozen-after-approval>

## Code Map

- `_bmad-output/planning-artifacts/EPIC-IMPLEMENTATION-READINESS.md:35-41` — 필수 필드, 방향 부호, 원자산 수량, ID·hash 정책의 정본.
- `_bmad-output/planning-artifacts/epics.md:1531-1640` — Story 2.5 AC와 시험 축.
- `backend/src/main/java/com/bokrilog/sync/SyncJobApi.java:16-30` — sync public boundary; candidate assessment port가 없음.
- `backend/src/main/java/com/bokrilog/source/SourceConnectionApi.java:31-44` — provider-neutral 수집 DTO; Bybit adapter를 분류기에 노출하지 않음.
- `backend/src/main/java/com/bokrilog/sync/internal/InitialCollectionRepository.java:123-211,216-229,265-293` — 안정 ID·semantic hash·provenance 및 complete candidate 저장.
- `backend/src/main/resources/db/migration/V7__initial_source_event_collection.sql:34-51,82-130` — 불변 사건과 candidate schema. 새 V8 migration만 추가.
- `backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java:91-130,143-191,263-268` — user·intent·연결·restriction·lease 재확인 관용구.
- `backend/src/main/java/com/bokrilog/FirstSnapshotController.java:81-108` 및 `frontend/src/components/InitialInputsCollection.tsx:100-` — 기존 same-origin 상태 UI 경계와 확장 지점.
- `backend/src/test/java/com/bokrilog/FirstSnapshotTest.java:96-192,361-463,640-663` — candidate, fail-closed, 삭제·경합 통합시험 패턴.

## Tasks & Acceptance

**Execution:**
- [x] 새 V8 판정 job·결과·flow 후보 schema와 sync public DTO/port를 둔다. V7과 기존 공식 snapshot을 재사용·수정하지 않는다.
- [x] 순수 결정표에 정확한 type/sign, 등록된 제외 type, 미등록 type의 verdict와 안정 rule ID를 구현한다. 후보 원수량과 시각·통화는 환산 없이 고정한다.
- [x] complete candidate admission, idempotency, fenced processing, 재검증 및 flow·제외 요약·provenance의 원자 publish를 연결한다.
- [x] 사용자 범위 조회·삭제, 안전한 상태 요약 UI와 keyboard·screen reader·320px·200/400% 확대 경로를 구현한다.
- [x] 아래 계약 시험과 Docker 기반 backend, frontend, same-origin 회귀를 추가·실행한다.

**Acceptance Criteria:**
- Given 판정 요청이 오면, when 입력을 확정할 때, then 소유된 complete candidate, 유효 intent·연결·계정·제한 및 원천 범위/완전성 증거를 확인하고 불변 입력을 고정한다.
- Given 같은 canonical 사건과 정책 version이면, when 판정표를 적용할 때, then 실행순서와 무관하게 같은 결과를 내고 정확한 UNIFIED `TRANSFER_IN` 양수는 입금, `TRANSFER_OUT` 음수는 출금으로 분류한다. 등록된 나머지 type은 제외하고 미등록 type은 검토로 둔다.
- Given 0, 모순 부호, 누락·충돌 증거가 있으면, when 판정할 때, then 임의 추론 없이 검토로 두고 자금흐름 계산을 막는다.
- Given 확인 또는 제외 사건이 있으면, when 결과를 저장할 때, then 부호 있는 `abs(cashFlow)`, 원자산, 사건시각, source ID/hash, provenance, rule ID와 정책 version을 보존하고 제외 이유도 재현 가능하게 한다. USD 환산은 하지 않는다.
- Given 동일 입력 재처리 또는 정책 변경이면, when 판정을 다시 할 때, then 중복 후보 없이 멱등 반환하거나 이전 판정을 보존한 새 판정 version을 만든다. source ID 의미 충돌은 자동 supersede하지 않는다.
- Given 일부/이상 입력 또는 publish 전 삭제·제한·intent·connection 변경이 있으면, when 처리를 완료하려 할 때, then 전체 결과 묶음을 완료 상태로 발행하지 않는다.
- Given 모든 사건이 판정 가능하면, when publish할 때, then 한 트랜잭션에서 외부 흐름 후보·제외 요약·원천 범위·정책 및 provenance를 불변 저장하고 공식 snapshot·계획·계산 포인터는 건드리지 않는다.
- Given 삭제 또는 connection cleanup이 경합하면, when worker가 늦게 끝나도, then 사용자 범위 결과를 제거하고 다른 사용자 결과를 유지하며 늦은 재생성을 차단한다.
- Given 사용자가 상태를 조회하면, when 완료·보류되었을 때, then 입금·출금·제외·검토 건수와 안전한 이유·다음 행동만 알리고 원수량·거래 상세를 금융 지표처럼 표시하지 않는다. 키보드·확대·보조기술에서도 동작한다.

## Design Notes

Story 2.4 adapter는 Transaction Log의 `TRANSFER_IN`·`TRANSFER_OUT`만 요청하고, 후보 사건도 해당 완전 수집 범위다. 이 스토리는 그 요청을 넓히지 않는다. 판정표의 알려진 비흐름 유형은 정책 단위시험과 해당 유형이 유효 후보 입력으로 주어지는 경계시험에 고정하고, 2.4가 격리한 사건이나 미완전 입력을 이 스토리에서 후보로 승격하지 않는다. 동일 source ID의 의미 변경은 2.4에서 충돌 관측이므로 새 revision으로 조용히 교체하지 않는다; 판정 policy version 변경만 새 불변 결과 version으로 기록한다.

## Verification

- `backend/gradlew --project-dir backend --no-parallel --max-workers=1 test --tests 'com.bokrilog.FirstSnapshotTest.cashflowClassificationPublishesImmutableSignedCandidatesOnceForCompleteInput' --tests 'com.bokrilog.CashflowDecisionTableTest'` — 검토 결함 회귀 통과. 잘못된 `cashFlow`의 검토 저장, 후보 재시도, 교차 사용자 범위와 불변 signed flow를 확인한다.
- `backend/gradlew --project-dir backend --no-parallel --max-workers=1 build` — Docker/Testcontainers 전체 빌드 통과 (512 tests, 실패 0).
- Node 24에서 `typecheck`, `lint`, `lint:css`, `format:check`, `test`, `build` 통과 (14 files / 297 tests). 분류 UI 시험은 빈 204 응답, same-origin POST, 안전한 건수·이유 표시, 원수량·거래 상세 비노출을 확인한다.
- `deploy/compose/verify-same-origin.sh --keep-stack` — 959/959 통과. 이어 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 ./node_modules/.bin/playwright test e2e/first-snapshot.spec.ts --workers=1` — 1/1 통과; keyboard로 판정을 시작하고 상태·건수·상세 비노출 및 320px·200/400% 확대 경로를 확인했다. 검증 compose stack과 volume을 정리했다.
- `bash .github/scripts/test-ci-scripts.sh` — 147/147 통과. `git diff --check` 통과.
- 경합 처리에는 claim과 publish 내부 재검증이 있으나, restriction·connection 변경과 삭제가 publish를 가로지르는 전용 deterministic race test는 아직 없다.

## Suggested Review Order

**요청 경계와 재검증**

- POST/GET 경계부터 읽어 안전한 상태 응답과 멱등 admission을 확인한다.
  [`FirstSnapshotController.java:107`](../../backend/src/main/java/com/bokrilog/FirstSnapshotController.java#L107)
- worker claim부터 원자 처리까지 자격 재확인과 실패 종료 흐름을 따라간다.
  [`SyncJobService.java:186`](../../backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java#L186)
- 후보 hash·provenance·이벤트 범위 검증과 재시도 동작을 확인한다.
  [`CashflowClassificationRepository.java:55`](../../backend/src/main/java/com/bokrilog/sync/internal/CashflowClassificationRepository.java#L55)

**판정과 보존**

- 정확한 type/sign 정책과 fail-closed 사유를 검토한다.
  [`CashflowDecisionTable.java:10`](../../backend/src/main/java/com/bokrilog/sync/internal/CashflowDecisionTable.java#L10)
- 불변 job·bundle·event 및 signed flow 제약을 확인한다.
  [`V8__cashflow_classification.sql:2`](../../backend/src/main/resources/db/migration/V8__cashflow_classification.sql#L2)

**화면과 회귀**

- 빈 상태·재시도·안전한 카운트 표시를 검토한다.
  [`InitialInputsCollection.tsx:326`](../../frontend/src/components/InitialInputsCollection.tsx#L326)
- malformed 입력·재시도·소유 범위 통합 회귀를 확인한다.
  [`FirstSnapshotTest.java:149`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L149)
- keyboard 경로와 사용자에게 노출되는 분류 결과를 확인한다.
  [`first-snapshot.spec.ts:96`](../../frontend/e2e/first-snapshot.spec.ts#L96)
