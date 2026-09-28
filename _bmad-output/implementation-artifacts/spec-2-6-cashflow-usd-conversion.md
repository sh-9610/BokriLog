---
title: '확인된 외부 자금흐름의 사건 시점 USD 환산'
type: 'feature'
created: '2026-09-28'
status: 'done'
baseline_commit: 'd5fcd5d1092b24c38a461033d1758a99a5b6f053'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics.md'
  - '{project-root}/_bmad-output/planning-artifacts/EPIC-IMPLEMENTATION-READINESS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-5-cashflow-classification.md'
---

<frozen-after-approval reason="승인된 Epic 2 Story 2.6 범위와 BYBIT-PRICE-1.0·CALC-1.0 계약">

## Intent

**Problem:** Story 2.5가 확인한 외부 자금흐름은 원자산 수량만 가진다. 이대로면 Story 2.7이 USD 조정 금액을 얻을 수 없고, 현재가나 임의 환율로 채우면 계획 원금이 왜곡된다.

**Approach:** 사용자 소유의 COMPLETE 판정 묶음을 고정하고 `BYBIT-PRICE-1.0`으로 흐름마다 사건 시점 USD 가치를 정한다. USD·USDT는 직접 정규화하고, 그 밖의 자산은 Bybit 공개 spot 경로의 종료된 1분봉 close를 추정값으로 쓴다. 가격 근거와 provenance를 담은 불변 환산 묶음을 만들고, 모든 흐름이 환산된 묶음만 계산 가능으로 표시한다.

## Boundaries & Constraints

**Always:**
- admission·claim·최종 트랜잭션에서 다음을 재검증한다: 사용자 WRITE 접근과 삭제 상태, 운영 제한(기존 `initialEligible` 게이트), intent 적격성, 연결, 상위 판정 묶음의 COMPLETE 상태, lease/fence. 부분 묶음과 검토 필요 묶음은 받지 않는다.
- 흐름마다 원자산, 부호 있는 수량, 사건시각, 원천 사건·판정 provenance(semantic hash, rule id, 판정 정책)를 불변 입력으로 고정하고, 입력 범위 hash로 다시 검증한다.
- `USD`·`USDT`만 `DIRECT_NORMALIZATION`(1 unit = 1 USD, 비추정)으로 처리한다.
- 그 밖의 자산은 spot Instruments Info에서 `symbol`, `baseCoin=원자산`, `quoteCoin`, `status=Trading`이 정확히 일치하는 경로만 쓴다. 경로 순서는 `ASSETUSDT` → `ASSETUSDC × USDCUSDT`이고, `USDC`는 `USDCUSDT`를 쓴다.
- 가격은 kline `category=spot`·`interval=1`의 종료된 candle close이며 `ESTIMATED`다.
- 산술은 `BigDecimal` 정확 곱 뒤 CALC-1.0 canonical 형식(34 유효숫자, scale 24 이하, HALF_EVEN)으로 한 번만 반올림한다. `|v| >= 1E24`이거나 0이 아닌 `|v| < 1E-24`이면 `CALCULATION_RANGE_EXCEEDED`다.
- provider 호출은 DB 트랜잭션 밖에서 한다. 한 작업 안의 같은 가격 key는 symbol별 병합 구간으로 묶는다. 공개 candle 증거는 사용자 식별자가 없는 공유 저장소에서 재사용하고, 같은 key의 값이 바뀌면 새 revision으로 추가한다.
- 결과와 증거는 추가 전용이다. 같은 입력·정책·증거면 기존 묶음을 멱등 반환한다. 증거나 정책이 다르면 이전 묶음을 보존한 채 새 버전과 변경 이유를 남긴다.
- 사용자 범위 삭제(`eraseSyncData`)는 그 사용자의 환산 작업·결과만 지우고, 다른 사용자의 데이터와 공유 공개 증거는 보존한다. 삭제·제한이 늦은 worker보다 우선한다.
- 상태는 same-origin `no-store` 응답과 접근성 있는 화면으로만 알린다.

**Ask First:** `BYBIT-PRICE-1.0`의 정규화 자산·경로·시간 경계·추정 규칙을 바꾸거나 넓히는 변경, 새 가격 공급원이나 endpoint 추가, 호출 예산(token bucket)이나 자동 재시도 도입.

**Never:**
- 현재 ticker·현재가·파생상품·inverse·제3자·수동 가격을 쓰지 않는다.
- 자산명만 보고 1 USD로 가정하거나 고정 환율을 쓰지 않는다.
- 사건 이후 candle, 5분을 넘는 candle, 미종료 candle, 보간·근사값을 쓰지 않는다.
- `double`/`float`와 중간 표시 반올림을 쓰지 않는다.
- 공식 계획 계산, 활성 계획, 계산 버전, snapshot 포인터, watermark를 만들지 않는다.
- 환산 결과를 수익률·계획금액·목표 달성률로 표시하지 않는다.
- V1~V8 migration과 `CashflowDecisionTable`의 의미를 고치지 않는다.
- provider 원문과 `retMsg`를 응답·로그·화면에 노출하지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| DIRECT | USD·USDT 흐름만 | `CONVERTED`, 부호를 보존한 USD 값, 계산 가능 묶음 | N/A |
| ESTIMATED | 비-USDT 흐름에 적격 경로와 가격 | `CONVERTED_ESTIMATED`, 증거·선택 규칙·추정 표시 | N/A |
| PRICE_MISSING | 경로 없음, Trading 아님, candle 결측·5분 초과, close ≤ 0 | `CONVERSION_REQUIRED`, 원 흐름 보존, 계산 가능 묶음 없음 | 재시도 불가 이유 코드 |
| PROVIDER_ERROR | timeout·429·5xx·`retCode≠0`, 미종료 candle | `CONVERSION_REQUIRED`(재시도 가능) | 자동 재시도 없음 |
| MALFORMED / RANGE | 모양을 알 수 없는 응답, 같은 candle의 모순, 범위 초과 | `REVIEW_REQUIRED`, 계산 가능 묶음 없음 | 안전한 이유 코드 |
| REPLAY / REVISION | 동일 입력·증거 재처리, 새 price revision이나 정책 | 기존 묶음 멱등 반환, 또는 과거를 보존한 새 버전 | N/A |
| INELIGIBLE / RACE | 판정 미완료, 타 사용자, intent·연결·제한·삭제 변경, stale fence | 접수 거절, 또는 미발행·`CANCELLED`와 재생성 차단 | 403/409, 접수 단계 외부 호출 0 |

</frozen-after-approval>

## Code Map

**계약과 요구사항**
- `_bmad-output/planning-artifacts/EPIC-IMPLEMENTATION-READINESS.md:43-58,62-69` -- `BYBIT-PRICE-1.0`, 호출 예산, CALC-1.0 입력 한도(가격·수량 precision 50/scale 24)와 범위 오류.
- `_bmad-output/planning-artifacts/epics.md:1642-1753` -- AC-1~AC-11과 시험 축.

**source 모듈(Bybit 어댑터)**
- `backend/src/main/java/com/bokrilog/source/SourceConnectionApi.java:22-61` -- 공개 DTO와 sealed 결과 관용구(`EquityResult` 49-52). 여기에 provider 중립 시장 조회를 더하고 `source/internal/SourceConnectionService.java`가 구현한다.
- `backend/src/main/java/com/bokrilog/source/internal/BybitAdapter.java` -- 기존 경계와 재사용 지점.
  - `publicCall`(443-447)은 query를 받지 못한다.
  - `send`의 오류 대응은 647-682, `resultOf`/`reasonOf`는 689-722다. `result`가 객체여야 하므로 kline의 `list`(배열의 배열)는 모양 검사를 따로 둔다.
  - `isCanonicalDecimal`(614-616)과 `parseEpochMillis`(455-459)를 재사용한다.
  - `collectInitialInputs`(329-385)는 `call.rejection()`을 보지 않아 TIMEOUT을 잃는다. 새 호출은 `verify`(217-220)처럼 rejection을 먼저 본다.
  - `isConfigured`는 815-817이다. `java.net.http`만 쓴다(ModuleBoundaryTest가 모듈의 Spring web 의존을 금지한다).

**Bybit 스텁**
- `backend/src/main/java/com/bokrilog/DevBybitStub.java` -- 경로 상수는 32-42다. 서버 시간은 `handle`이 key 검사 전에 답한다(215-218). 공개 분기는 그 자리에 둔다. 응답 envelope은 407-420이다.
- `backend/src/main/java/com/bokrilog/DevBybitStubConfig.java:97-103` -- 경로 목록. RouterFunction은 하나를 유지한다.
- `backend/src/test/java/com/bokrilog/BybitStubConfiguration.java` -- 요청 계수는 95-103, 시험용 경로 목록은 139-145다.

**sync 모듈**
- `backend/src/main/java/com/bokrilog/sync/SyncJobApi.java:11-37` -- 공개 DTO와 메서드, `eraseSyncData`의 삭제 순서.
- `backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java` -- 판정 admission·claim·허가 재검증(136-223), provider I/O가 있는 수집 단계 분리(241-283), `initialEligible`(344-354), erase(162-169).
- `backend/src/main/java/com/bokrilog/sync/internal/CashflowClassificationRepository.java` -- 관용구: admit·입력 고정·claim·lock(32-117), `inputHash`(118-158), `publish`·`finish`·`erase`(172-249).
- `backend/src/main/java/com/bokrilog/sync/internal/CashflowDecisionTable.java:9,57-60` -- 순수 정책 클래스 패턴과 `canonicalDecimal`.
- `backend/src/main/java/com/bokrilog/sync/internal/FirstSnapshotWorker.java:23-83` -- tick당 claim 하나, virtual thread, `inFlight` heartbeat, `runInitial`의 단계 추적.
- `backend/src/main/resources/db/migration/V8__cashflow_classification.sql:2-89` -- 입력인 `sync_cashflow_flow_candidate`와 판정 provenance 행. canonical decimal CHECK 정규식을 재사용한다. 새 schema는 V9만 둔다.

**HTTP 표면**
- `backend/src/main/java/com/bokrilog/FirstSnapshotController.java:85-149` -- POST는 202/409, GET은 200/204/403, 모두 `no-store`.
- `backend/src/main/java/com/bokrilog/AuthSecurityConfig.java:1159-1213` -- `/api/snapshots/first/` 하위 경로는 인증·CSRF·Origin 보호를 자동으로 받는다.

**백엔드 시험**
- `backend/src/test/java/com/bokrilog/FirstSnapshotTest.java` -- 판정 시험(148-293), SQL 사건 주입 `insertCandidateEvent`·`refreshCandidateHash`(249-293), `connect`·`initialIntent`·await 도우미(426-436, 819-861).
- `backend/src/test/java/com/bokrilog/source/internal/BybitAdapterTest.java:59-71,306-326` -- 요청 URI 기록기와 경로 switch.
- `backend/src/test/java/com/bokrilog/ModuleBoundaryTest.java:38-142` -- 새 모듈 없이 `sync`→`source` 의존만 쓴다.

**프런트엔드**
- `frontend/src/components/InitialInputsCollection.tsx` -- 판정 GET·폴링·POST·섹션(165-176, 264-273, 326-358, 402-455).
  - 환산 섹션은 455 뒤에 두고 판정 `COMPLETE`일 때만 조회한다. 그래야 mount 시 GET 순서가 유지된다(`InitialInputsCollection.test.tsx:76-116`, `PlanConditionPanel.test.tsx:69-70`).
  - 멱등 key는 `start()`처럼 불확실한 실패 뒤에만 재사용하고 `CSRF_HEADER`(`content/auth.ts:132`)를 쓴다.
- `frontend/src/components/PlanConditionPanel.tsx:336-363` -- 부모가 이미 `role="status"`라서 live region이 중첩된다. `copy.cancel`이 조건을 다시 정하는 경로다.
- `frontend/src/content/planConditions.ts:29-84` -- 판정 카피 구조.
- `frontend/src/content/connection.ts:149-159` -- `formatVerifiedAt`(Asia/Seoul).
- `frontend/src/components/FirstSnapshotPanel.tsx:75-87` -- 원인·영향·행동 `<dl>` 패턴.
- 부재 단정: `InitialInputsCollection.test.tsx:72,231`, `frontend/e2e/first-snapshot.spec.ts:105-107`. 범위를 의도적으로 좁힌다. e2e 확장 지점은 104 뒤다.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/resources/db/migration/V9__cashflow_usd_conversion.sql` -- 테이블과 CHECK를 둔다. 기존 migration은 고치지 않는다.
  - 사용자 범위: 환산 시도(job), 결과 묶음, 흐름별 결과, 가격 leg, 계산 가능 자금흐름(`sync_calculable_cashflow`, 계산 가능 묶음에서만 insert).
  - 사용자 식별자가 없는 공유 증거: candle, instrument observation.
  - 제약: canonical decimal·상태·부호 CHECK, `user_id` RESTRICT, 판정 묶음 CASCADE, 증거 RESTRICT, 묶음 유일성 `(user, classification, policy, outcome fingerprint)`, active 시도 하나.
- [x] `source/SourceConnectionApi.java`, `SourceConnectionService.java`, `source/internal/BybitAdapter.java` -- provider 중립 공개 시장 조회 셋(서버 시간, spot instrument, 1분 kline 구간)을 sealed 결과로 둔다. 조회에는 query를 붙이고, rejection을 먼저 보고, 응답 모양을 닫힌 규칙으로 검사한다. 재시도는 없다.
- [x] `DevBybitStub.java`, `DevBybitStubConfig.java`, `backend/src/test/.../BybitStubConfiguration.java` -- instruments-info·kline 분기를 key 검사 전에 둔다. symbol로 선택하는 결정적 fixture를 둔다: 직접 경로, USDC 교차, candle 공백, 이력 없음, Trading 아님, 모순·모양 오류, 장애.
- [x] `sync/internal/PriceConversionPolicy.java` -- 순수 `BYBIT-PRICE-1.0`. 직접 정규화, 경로 순서, candle 선택, 정밀도·범위, 흐름·묶음 판정 우선순위를 둔다.
- [x] `sync/internal/CashflowConversionRepository.java`, `SyncJobService.java`, `SyncJobApi.java`, `FirstSnapshotWorker.java` -- admission·입력 고정, claim/lease/heartbeat/fence, 캐시 조회와 증거 저장(짧은 트랜잭션), 재검증 원자 publish, 멱등·버전, `eraseSyncData` 연결.
- [x] `FirstSnapshotController.java` -- `POST`·`GET /api/snapshots/first/cashflow-conversion`. 판정 endpoint와 같은 계약을 따른다.
- [x] `frontend/src/components/InitialInputsCollection.tsx`(+`.module.css`, `.test.tsx`), `frontend/src/content/planConditions.ts` -- 환산 상태 섹션, 카피, 단위시험.
- [x] 시험을 추가·실행한다: `PriceConversionPolicyTest`(matrix·경계·정밀도·결정성), `BybitAdapterTest`, `FirstSnapshotTest`(통합 시험. 기존 SQL 주입·await 도우미를 재사용한다), `frontend/e2e/first-snapshot.spec.ts`.

**Acceptance Criteria:**
- Given 사용자의 판정 묶음이 COMPLETE이면, when 환산을 요청할 때, then 202로 시도를 접수하고 같은 key의 재전송과 진행 중 시도는 재사용한다. 판정이 미완료이거나 부적격이면 거절하고 provider를 호출하지 않는다.
- Given 여러 흐름·사용자가 같은 symbol·분을 필요로 하면, when 처리할 때, then symbol마다 병합된 구간(요청당 1000 candle 이하)으로 조회한다. 공유 증거에 사건 분 candle이 있으면 kline을 호출하지 않는다. 이를 요청 수로 단정한다.
- Given 입력 순서나 증거 후보 순서가 바뀌거나 반복 실행되면, when 선택할 때, then 같은 경로·candle·USD 값을 낸다.
- Given 모든 흐름이 환산되면, when 발행할 때, then 한 트랜잭션에서 묶음·흐름·leg·계산 가능 자금흐름을 모두 저장하고 추정 여부와 정책 버전(`BYBIT-PRICE-1.0`, `CALC-1.0`)을 남긴다. 재검증을 잃으면 아무것도 남지 않는다. 계획·snapshot·포인터 테이블은 바뀌지 않는다.
- Given 처리 중 삭제나 제한이 경합하면, when worker가 늦게 끝날 때, then 대상 사용자 행을 제거하고 다른 사용자 행과 공유 증거는 유지하며, 재생성과 계산 가능 승격을 막는다.
- Given 사용자가 상태를 보면, when 완료되거나 보류될 때, then 화면이 다음을 따른다.
  - `환산 완료`·`추정값 포함`·`환산 필요`·`검토 필요` 가운데 하나와 이유·다음 행동을 표시하고, 상태 변화를 보조기술에 한 번만 알린다.
  - 환산 필요 흐름은 자산별 건수로 묶는다. 상세에는 방향, 반올림하지 않은 원수량, 사건시각(Asia/Seoul), "공식 갱신 보류·마지막 정상 결과 유지" 영향을 보인다.
  - 재시도는 재시도 가능한 이유에만 준다. `계산에서 제외` 표현과 USD 금액·수익률은 없다.
  - 키보드, 320px, 200%·400% 확대에서 동작한다.

## Design Notes

**candle 선택**
- 사건 분 candle은 `start = floor(t, 1분)`이다. `start + 60s ≤ 기준시각`이면 종료로 본다. 기준시각은 시도마다 Bybit 서버 시간을 한 번 받아 고정한다.
- 사건 분 candle이 미종료이면 fallback 없이 `PRICE_CANDLE_NOT_CLOSED`(재시도 가능)다. 같은 입력이 시각에 따라 다른 candle로 갈리지 않게 하기 위해서다.
- 종료됐지만 응답에 없으면 `start−1분 … start−5분` 가운데 가장 최근 것을 쓴다.
- 교차 경로는 두 leg가 각각 이 규칙을 만족해야 한다. 경로 우선순위는 흐름마다 모든 leg가 성립하는 첫 경로다.

**출시 증거**
- spot Instruments Info에는 `launchTime`이 없다. 그래서 사건 시점 출시의 증거는 candle의 존재 자체이고, instruments는 현재 `Trading` 여부만 증명한다.
- 사건 뒤 상장 폐지된 자산은 fail-closed로 `환산 필요`가 된다.

**정밀도 예시**
- `-0.5` BTC × `65000.25` = `-32500.125`
- `2` ETH × (`3000.1` × `0.9998` = `2999.49998`) = `5998.99996`
- 정확 곱이 34 유효숫자나 scale 24를 넘을 때만 HALF_EVEN으로 한 번 반올림한다.

**판정 우선순위와 시도**
- 묶음 상태의 우선순위는 `REVIEW_REQUIRED` > `CONVERSION_REQUIRED` > `CONVERTED_ESTIMATED` > `CONVERTED`다.
- job은 시도이고 묶음은 결과 버전이다. 결과가 같으면 새 시도도 기존 묶음을 멱등 반환한다. 다르면 `previous_conversion_id`와 변경 이유(`PRICE_REVISION`·`POLICY_CHANGE`·`EVIDENCE_CHANGE`)를 남긴다.
- Story 2.7은 계산 가능 묶음의 `sync_calculable_cashflow`만 소비한다.

**호출 수와 공유 증거**
- 시도당 호출은 서버 시간 1, distinct symbol마다 instruments 1, symbol별 병합 분 수 ÷ 1000(올림)만큼의 kline이다.
- token bucket과 자동 재시도는 2.4처럼 도입하지 않는다(미룬 결정으로 남긴다).
- 공유 증거는 공개 시장 사실만 담고, 사용자 삭제 뒤에도 남는다.

**다음 행동 경로**
- 지원 경로는 아직 확정되지 않았으므로 링크를 만들지 않는다(EXPERIENCE 신뢰 상세 규칙).
- 계획 전환은 기존 "조건 다시 정하기"와, 새 시작 기준에 대한 안내 문구로 제시한다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend --no-parallel --max-workers=1 build` -- expected: Docker/Testcontainers 전체 통과, 실패 0.
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run test && npm --prefix frontend run build` (Node 24) -- expected: 모두 통과.
- `deploy/compose/verify-same-origin.sh --keep-stack` 뒤 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080` 환경으로 `e2e/first-snapshot.spec.ts` 실행 -- expected: 통과, 이후 스택 정리.
- `bash .github/scripts/test-ci-scripts.sh`, `git diff --check` -- expected: 통과.

**실행 결과 (리뷰 patch 반영 후):**
- 백엔드 build 통과: 586 tests, 실패·skip 0. `FirstSnapshotTest` 66, `PriceConversionPolicyTest` 45.
- 프런트엔드 (Node 24): `typecheck`·`lint`·`lint:css`·`format:check`·`build` 통과, `test` 14 files / 310 tests 통과.
- 주입 origin e2e 58/58 통과: 수동 compose 스택, V9까지 migrate, `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080`. 새 `marketflows` held-list 시험이 포함된다.
- `test-ci-scripts.sh` 147/147, `git diff --check` 통과.
- `verify-same-origin.sh`는 이 Windows 기기에서 902/959로 끝났다. 원인은 Git Bash `curl --data`가 한글 확인 문구를 CP949로 보내는 기존 결함이며 이 변경과 무관하다(`deferred-work.md`). Linux CI의 `same-origin-integration`이 이 스크립트를 판정한다.
- 경합 시험 셋(stale fence, 발행 전 상위 변경, 발행 트랜잭션 안의 차단)과 병합·close 기준 판정·409 시험은 제품 코드를 임시로 되돌려 의도한 단정으로 실패하는 것을 확인한 뒤 복원했다.

## Suggested Review Order

**작업 흐름과 재검증 지점**

- 입구: 고정 기준시각 → 시장 조회(트랜잭션 밖) → 재검증 발행의 세 단계.
  [`SyncJobService.java:279`](../../backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java#L279)

- 접수: COMPLETE 판정·intent·연결·제한을 확인하고 활성 시도를 재사용한다.
  [`SyncJobService.java:170`](../../backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java#L170)

- claim·첫 호출 전·발행 안에서 같은 술어로 다시 판정한다.
  [`SyncJobService.java:346`](../../backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java#L346)

**BYBIT-PRICE-1.0 판정(순수 함수)**

- 직접 정규화, 경로 순서, 사건 분 종료 판정과 5분 fallback.
  [`PriceConversionPolicy.java:237`](../../backend/src/main/java/com/bokrilog/sync/internal/PriceConversionPolicy.java#L237)

- 정확 곱 뒤 34자리·scale 24로 한 번만 반올림하고 범위를 거절한다.
  [`PriceConversionPolicy.java:336`](../../backend/src/main/java/com/bokrilog/sync/internal/PriceConversionPolicy.java#L336)

- 미종료 사건 분은 fallback 없이 재시도 가능 보류다.
  [`PriceConversionPolicy.java:226`](../../backend/src/main/java/com/bokrilog/sync/internal/PriceConversionPolicy.java#L226)

- 묶음 상태 우선순위와 결과 fingerprint(기준시각 제외).
  [`PriceConversionPolicy.java:353`](../../backend/src/main/java/com/bokrilog/sync/internal/PriceConversionPolicy.java#L353)

**시장 조회와 공유 증거**

- round별 지연 조회. symbol마다 가능한 모든 분을 한 번에 병합 요청한다.
  [`CashflowConversionMarket.java:60`](../../backend/src/main/java/com/bokrilog/sync/internal/CashflowConversionMarket.java#L60)

- 캐시 hit이면 kline 생략, 모순은 canonical close 기준, 미종료 행은 저장하지 않는다.
  [`CashflowConversionMarket.java:151`](../../backend/src/main/java/com/bokrilog/sync/internal/CashflowConversionMarket.java#L151)

- 비어 있지 않은데 정확한 symbol이 없는 목록은 미상장이 아니라 형식 오류다.
  [`CashflowConversionMarket.java:124`](../../backend/src/main/java/com/bokrilog/sync/internal/CashflowConversionMarket.java#L124)

- 공유 candle 저장: close가 같으면 기존 revision 재사용, start 순서로 insert.
  [`CashflowConversionRepository.java:303`](../../backend/src/main/java/com/bokrilog/sync/internal/CashflowConversionRepository.java#L303)

**발행·버전·삭제**

- fingerprint 멱등 재사용, 아니면 predecessor와 변경 이유를 단 새 묶음.
  [`CashflowConversionRepository.java:390`](../../backend/src/main/java/com/bokrilog/sync/internal/CashflowConversionRepository.java#L390)

- predecessor는 가장 최근에 끝난 시도의 결과. PRICE_REVISION과 EVIDENCE_CHANGE를 가른다.
  [`CashflowConversionRepository.java:411`](../../backend/src/main/java/com/bokrilog/sync/internal/CashflowConversionRepository.java#L411)

- 사용자 범위 삭제. 공유 공개 증거는 남긴다.
  [`CashflowConversionRepository.java:582`](../../backend/src/main/java/com/bokrilog/sync/internal/CashflowConversionRepository.java#L582)

- 상태 조회: 묶음 없으면 건수 생략, 보류·검토 흐름을 자산별로 묶는다.
  [`CashflowConversionRepository.java:532`](../../backend/src/main/java/com/bokrilog/sync/internal/CashflowConversionRepository.java#L532)

**스키마**

- 사용자 식별자 없는 공유 candle 증거와 요청 구간 provenance.
  [`V9__cashflow_usd_conversion.sql:34`](../../backend/src/main/resources/db/migration/V9__cashflow_usd_conversion.sql#L34)

- 시도 생애주기 CHECK, 닫힌 이유 코드, 활성 시도 하나.
  [`V9__cashflow_usd_conversion.sql:66`](../../backend/src/main/resources/db/migration/V9__cashflow_usd_conversion.sql#L66)

- 계산 가능 행은 복합 FK로 calculable 묶음 아래에만 들어간다.
  [`V9__cashflow_usd_conversion.sql:262`](../../backend/src/main/resources/db/migration/V9__cashflow_usd_conversion.sql#L262)

**source 어댑터와 HTTP 경계**

- 서명 없는 공개 조회 셋. rejection을 먼저 보고 재시도하지 않는다.
  [`BybitAdapter.java:480`](../../backend/src/main/java/com/bokrilog/source/internal/BybitAdapter.java#L480)

- kline 구간·1000개 상한·응답 모양 검사.
  [`BybitAdapter.java:540`](../../backend/src/main/java/com/bokrilog/source/internal/BybitAdapter.java#L540)

- provider 중립 sealed 결과로 sync에 노출한다.
  [`SourceConnectionApi.java:84`](../../backend/src/main/java/com/bokrilog/source/SourceConnectionApi.java#L84)

- POST 202/409, GET 200/204/403, no-store, USD 금액 없는 응답.
  [`FirstSnapshotController.java:146`](../../backend/src/main/java/com/bokrilog/FirstSnapshotController.java#L146)

**화면**

- 판정 COMPLETE 뒤에만 조회하는 환산 섹션. live region은 하나다.
  [`InitialInputsCollection.tsx:277`](../../frontend/src/components/InitialInputsCollection.tsx#L277)

- 자산별 `<details>`로 방향·원수량·서울 시각·영향·이유를 보인다.
  [`InitialInputsCollection.tsx:439`](../../frontend/src/components/InitialInputsCollection.tsx#L439)

- 네 상태·이유·다음 행동 카피. 지원 링크와 금액 표현이 없다.
  [`planConditions.ts:93`](../../frontend/src/content/planConditions.ts#L93)

- 부모의 live region을 확인 문구로 좁혀 중첩을 없앴다.
  [`PlanConditionPanel.tsx:342`](../../frontend/src/components/PlanConditionPanel.tsx#L342)

**worker·스텁**

- 세 heartbeat 루프를 lease 단위로 격리해 환산 lease 갱신이 빠지지 않는다.
  [`FirstSnapshotWorker.java:38`](../../backend/src/main/java/com/bokrilog/sync/internal/FirstSnapshotWorker.java#L38)

- symbol fixture로 고르는 공개 시장 스텁. kline 상한을 제공자처럼 검사한다.
  [`DevBybitStub.java:495`](../../backend/src/main/java/com/bokrilog/DevBybitStub.java#L495)

**시험**

- 멱등·요청 수·공유 증거 재사용의 중심 통합 시험.
  [`FirstSnapshotTest.java:303`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L303)

- USDC 흐름과 교차 경로가 USDCUSDT 요청 하나를 공유한다.
  [`FirstSnapshotTest.java:675`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L675)

- 보류 → 성공의 EVIDENCE_CHANGE 사슬과 predecessor 선택.
  [`FirstSnapshotTest.java:798`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L798)

- 경합: 삭제·제한, stale fence, 발행 트랜잭션 안의 차단.
  [`FirstSnapshotTest.java:564`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L564)

- 정책 matrix·경계·정밀도 벡터·결정성 단위 시험.
  [`PriceConversionPolicyTest.java:33`](../../backend/src/test/java/com/bokrilog/sync/internal/PriceConversionPolicyTest.java#L33)

- 주입 origin에서 held 목록·키보드·320/200/400% 확대.
  [`first-snapshot.spec.ts:179`](../../frontend/e2e/first-snapshot.spec.ts#L179)
