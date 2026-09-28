---
title: '현재 공식 계산의 입력과 버전 근거'
type: 'feature'
created: '2026-09-29'
status: 'done'
baseline_commit: '3b14e5f2cf6b321d84ad0a17713e059145afc236'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics.md'
  - '{project-root}/_bmad-output/planning-artifacts/EPIC-IMPLEMENTATION-READINESS.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-BokriLog-2026-08-23/EXPERIENCE.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-8-official-dashboard.md'
---

<frozen-after-approval reason="승인된 Epic 2 Story 2.9 범위와 AD-8·UX-DR11·DESIGN 긴 식별자 규칙">

## Intent

**Problem:** 대시보드는 공식 포인터 묶음의 값과 세 신뢰 축을 보이지만, 그 값이 어느 스냅샷·수집 범위·판정·환산·계산 정책에서 나왔는지는 보이지 않는다. 사용자는 표시값이 재현 가능한 공식 결과인지 판단할 수 없다.

**Approach:** 서버가 현재 공식 결과 하나의 계보를 읽기 전용 근거 모델(`GET /api/dashboard/evidence`)로 준다. 클라이언트가 지금 보이는 묶음의 참조를 보내고, 서버는 그것이 그 사용자의 현재 공식 포인터일 때만 답한다. 대시보드 패널 안의 `계산 근거 보기`가 같은 화면에서 근거 영역을 펼친다. 순서는 스냅샷 → 시각 → 입력 버전 → 계산 버전 → 지표 영향 → 버전 목록이다. 근거 읽기의 실패는 대시보드 카드를 건드리지 않는다.

## Boundaries & Constraints

**Always:**
- **참조 검증:** 요청은 `planId`·`pointerVersion`·`calculationId` 셋을 쿼리로 보낸다.
  - 셋이 그 사용자의 현재 공식 포인터와 모두 같을 때만 200이다.
  - 그 밖의 경우는 전부 같은 404 공통 오류이고 존재 여부를 드러내지 않는다: 다른 사용자, 지난 버전, 없는 ID, 활성 계획 없음, 형식 오류, 누락.
  - `/api/dashboard`의 다른 하위 경로는 2.8대로 같은 404를 유지한다.
- **한 묶음:** 포인터 → 계산 버전 → 지표·흐름 → 수집 후보·판정·환산 기록을 읽기 전용 `REPEATABLE READ` 트랜잭션 하나에서 읽는다.
  - 계보가 끊기거나 서로 어긋나면 부분 근거를 내지 않는다: 참조한 행이 없거나, 판정·환산의 계보가 계산 버전과 다르거나, 지표가 여섯이 아닌 경우다.
  - 그때는 503 `EVIDENCE_UNAVAILABLE`로 끝내고 운영 로그에 `consistent=false`를 남긴다.
- **내용:**
  - 스냅샷: portfolio snapshot·sequence, 기준 snapshot, tc source snapshot.
  - 시각: `t0`, `tc`, `measured_at`, `cashflow_verified_through`.
  - 연결 출처: 제공자·connection ID·연결 정책 버전.
  - 원천 범위: 수집 페이지 증거의 최소 시작~최대 끝, 수집 정책, 완전성 hash.
  - 판정: ID·정책·입력 범위 hash.
  - 환산: ID·가격 정책·결과 fingerprint.
  - 자금흐름: 반영 건수와 추정 건수.
  - 계획 버전: plan ID와 조건.
  - 계산 버전: 계산·달력·표시·정밀도 정책, 시간대, 구현 버전, canonical 입력·결과 hash, 생성 시각.
  - 지표별: 성격, 계산 불가 이유, 추정 전파 원인.
  - 버전 목록:
    - 공식 버전은 발행 순서와 적용 시작, 그리고 현재 여부를 보인다.
    - 같은 intent 계보의 발행되지 않은 시도(`FAILED`·`BLOCKED`)는 공식과 분리된 읽기 전용 목록으로 둔다. 상태·이유 코드·종료 시각을 보인다.
- **보이지 않는 것:** 내부 `user_id`, `provider_account_id`, Bybit UID, 자격정보, 원 금융 응답, 개별 자금흐름 사건(2.10)은 응답과 화면에 없다.
- **시각 설명:** 세 시각의 뜻을 따로 설명한다. Bybit가 잔고와 입출금 기록을 한 시점으로 묶는 공통 스냅샷을 주지 않는다는 사실을 말하고, 같거나 다를 때 각각 맞는 문장을 쓴다. 공통 스냅샷 토큰이 있는 것처럼 표현하지 않는다.
- **긴 식별자:** 줄이지 않고 전체값을 mono 토큰으로 보인다. 공백 없는 값도 안전하게 줄바꿈한다. 보조기술도 같은 전체값을 읽는다.
- **화면:**
  - `계산 근거 보기`는 버튼이다(`aria-expanded`·`aria-controls`). 열면 근거 제목으로 초점이 가고, `계산 근거 닫기`는 초점을 버튼으로 돌려준다.
  - 근거는 지금 보이는 묶음의 참조와 일치할 때만 보이고, 묶음이 바뀌면 버린다.
  - 읽기 실패는 대시보드 카드와 상태를 바꾸지 않는다. 메모리의 snapshot ID·calculation ID를 최소 참조로 보이고, 재시도 가능 여부를 말한다.
  - 403은 근거를 메모리에서 지운다.
  - 화면당 `role=status`는 하나이고 근거 영역에는 live region이 없다.
- **삭제·캐시·관측:**
  - 계정 접근을 읽기 앞뒤로 확인하고, 거부면 403이다.
  - 새 테이블을 두지 않으므로 기존 삭제 경로가 근거까지 지운다.
  - 200은 `no-store`다.
  - 운영 로그는 읽기마다 한 줄이고 상태·HTTP 상태·지연·`consistent`만 남긴다.
- **접근성:** 논리적 제목과 읽기 순서를 지키고, 320px·200%·400%에서 가로 스크롤이 없어야 한다. 키보드와 44px 조작 영역을 지원한다. 고지가 인접한 `복리 계획 비교` 화면 안에 둔다.

**Ask First:** 새 마이그레이션, 새 내부 화면 경로, 다른 모듈에 새 공개 API, SYNC-UX·CALC·DISPLAY 정책 변경.

**Never:**
- 새 계산·동기화 실행, 운영자 감사·정지, 조정 사건 상세(2.10), 원문 응답 공개는 하지 않는다.
- 없는 버전이나 상세 화면으로 가는 링크는 두지 않는다.
- 과거·실패 시도를 현재 공식처럼 표시하지 않는다.
- 다른 모듈의 테이블을 직접 읽지 않는다.
- 투자성과·추천으로 오인할 해석, 원천 입력 없는 추측 설명은 쓰지 않는다.
- 폴링은 하지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| CURRENT | 현재 포인터의 세 참조 | 200 근거 한 묶음 | N/A |
| TAMPER | 다른 사용자·지난 버전·없는 ID·형식 오류·누락·계획 없음 | 같은 404 공통 오류 | 존재 비노출 |
| ESTIMATED / NOT_COMPUTABLE | 추정 환산 포함 / 정의 불가 지표 | 지표별 성격·이유·추정 원인(건수·가격 근거 종류) | 0 대체 없음 |
| UNPUBLISHED | 같은 intent의 `FAILED`/`BLOCKED` 시도 | 공식 목록과 분리된 읽기 전용 행 | N/A |
| BROKEN | 참조 행 부재·계보 불일치 | 부분 근거 없음, `consistent=false` | 503 `EVIDENCE_UNAVAILABLE` |
| DELETING | 앞·뒤 확인 중 하나라도 삭제 | 403, 근거 없음, 타 사용자 보존 | 클라이언트 삭제 |
| READ_FAIL | 네트워크·5xx·404 | 대시보드 불변, 최소 참조와 재시도 | 404는 대시보드 다시 불러오기 안내 |

</frozen-after-approval>

## Code Map

**계약**
- `epics.md` `### Story 2.9` -- AC-1~AC-8.
- `EXPERIENCE.md`
  - 42: 진입점.
  - 129: 불투명 ID.
  - 186: 상태.
  - 372-379: Flow 8.
- `DESIGN.md`
  - 66-70: `typography.mono`.
  - 243: 긴 식별자 규칙.
- `PORTFOLIO-SOLUTION-DESIGN.md:88-95`, `ARCHITECTURE-SPINE.md:100`(AD-8) -- 공통 스냅샷 토큰이 없다는 근거.
- `epics.md:1840` -- 2.7이 계산 버전에 기록한 항목 목록.

**백엔드** (`backend/src/main/java/com/bokrilog/`)
- `DashboardController.java`
  - 86: 매핑.
  - 136: 운영 로그.
  - 33-36: 하위 경로 계약 주석.

  같은 컨트롤러에 evidence 핸들러를 두고, 로그 한 줄 관용구를 재사용한다.
- `AuthSecurityConfig.java`
  - 225-234: `DASHBOARD_PATH` 주석.
  - 1188-1189, 1203-1206, 1223-1225: 술어. 하위 경로는 이미 인증·CSRF 술어에 걸린다.
- `sync/internal/PlanPublicationService.java`
  - `dashboard`(552) → `bundle`(570)을 따라 한다: 앞뒤 `readable`, `dashboardReads` RR template(88-90), `belongs`(611).
  - portfolio 포인터 검사(581-586)도 같은 규칙을 쓴다.
- `sync/internal/PlanPublicationRepository.java`
  - `pointer`·`calculation`(379-391)·`metrics`를 재사용한다.
  - 다음 컬럼은 `CalculationView`가 아직 읽지 않는다: source snapshot, candidate·classification·conversion id, classification·source 정책, precision, time_zone. 근거 전용 쿼리를 더한다.
  - `latest`(156-159)가 있다.
- 스키마 (읽기만 한다)
  - `V10__first_plan_publication.sql`
    - 167-221: 계산 버전 컬럼과 lineage FK 181-185.
    - 253-271: 흐름(`estimated`·`evidence_type`).
    - 88-149: 발행 시도(`intent_id`·`status`·`failure_code`·`finished_at`, 실패 시 plan_id NULL).
    - 279-290: 포인터(이력 없음).
  - `V7__*.sql`
    - 108-124: candidate.
    - 82-99: 페이지 증거 `range_start/range_end`.
  - `V8`: 판정 26-43.
  - `V9`: 환산 143-188(`previous_conversion_id`).
- `sync/PlanPublicationPort.java:30-31,44` -- `activePlan`이 `intentId`를 준다. 같은 intent의 시도를 고르는 열쇠다.
- `source`: 새 API가 필요 없다. 계산 버전이 `connection_id`·`source_policy_version`을 이미 갖는다.

**프런트엔드** (`frontend/src/`)
- `components/PlanResultPanel.tsx`
  - 408-409: 지표 `h3`. 목업의 진입 자리이며, 버튼을 여기에 둔다.
  - 365-373: 초점 effect. 지금은 `hasNextAction`만 보므로 `result === null` 전환도 포함하게 넓힌다. 트리거가 사라질 때 초점을 잃지 않게 하기 위해서다.
- `session/useDashboard.ts`
  - 317-321: fetch 옵션.
  - 285·302-305·329: 요청 순번 가드.
  - 79-90: 검증 도우미(export 안 됨).
  - 233-237: `supersedes`.

  근거는 별도 훅과 별도 state로 둔다.
- `content/planResult.ts` -- 새 카피는 `content/`에 둔다(`evidence` 절 또는 새 파일).
- 스타일
  - `styles/tokens.css`: `--type-mono-*`(74-77), `--color-surface-subtle`(17), `--space-target-min`(96).
  - 줄바꿈 선례: `SettingsScreen.module.css:422-429`, `PlanResultPanel.module.css:45-47`.
- 시험
  - `test/setup.ts:87-89`: `stubDashboard` 모양으로 근거 stub을 더한다.
  - `test/planResultFixture.ts:67-131`.
  - `e2e/first-snapshot.spec.ts`: `publishAndOpenResult` 148-165, `expectFirstResult` 171-288, `expectKeyboardOrder` 290-312(새 버튼으로 순서가 바뀐다), `expectNoHorizontalScroll` 314-327.
- 백엔드 시험 `FirstSnapshotTest.java`
  - 흐름: `publish`(3080)·`awaitPublication`(3086)·`dashboardOf`(2387).
  - 추정 시나리오: 1804-1836.
  - 계산 불가: 1838-1907.
  - `FAILED`: 1910. `BLOCKED`: 899.
  - 변조 탐침: 2664-2701.

## Tasks & Acceptance

**Execution:**
- [x] `sync/SyncJobApi.java`, `sync/internal/PlanPublicationService.java`, `PlanPublicationRepository.java`
  - 근거 읽기: 앞뒤 접근 확인, RR 읽기 전용 한 묶음, 참조 일치 판정, 계보 일관성 판정.
  - 지표별 추정 원인을 계산한다.
  - 같은 intent의 미발행 시도 목록을 만든다.
- [x] `DashboardController.java`, `AuthSecurityConfig.java`(주석)
  - `GET /api/dashboard/evidence`: 200 / 404 / 403 / 503 `EVIDENCE_UNAVAILABLE` / 500, 모두 `no-store`.
  - 운영 로그 한 줄.
  - 하위 경로 계약 주석을 갱신한다.
- [x] 백엔드 시험 (`FirstSnapshotTest`의 기존 사슬 재사용)
  - I/O matrix 전 행.
  - 흐름: 공식 흐름(추정 0), 추정(BTC), 계산 불가, 발행 전 `FAILED` 뒤 성공.
  - 404 본문이 모든 변조에서 글자까지 같다.
  - 응답에 `user_id`·`provider_account_id`·자격정보가 없다.
  - 로그 캡처, 삭제 앞뒤.
- [x] `content/`, 새 `session/useCalculationEvidence.ts`
  - 참조를 쿼리로 보낸다.
  - 검증, 순번 가드, 실패와 404·403 구분, 묶음 교체 시 폐기.
- [x] 새 `components/CalculationEvidence.tsx`(+`.module.css`), `PlanResultPanel.tsx`
  - 버튼과 펼침 영역, 여섯 절, 시각 설명, mono 전체값 줄바꿈.
  - 닫기와 초점 복귀, 실패 시 최소 참조.
  - 초점 effect를 넓힌다.
  - 화면 status 문장에 근거 읽기 상태를 합친다.
- [x] 프런트엔드 시험
  - Vitest: 순서·제목, 세 시각의 같음·다름 문장, 지표 영향, 미발행 분리, 실패·404·403, 묶음 교체, 저장소 0, 금지어.
  - e2e: 공식·추정 발행 뒤 키보드로 열고 닫기, 320·640px, `expectKeyboardOrder` 갱신.

**Acceptance Criteria:**
- Given 발행을 마친 사용자, when 대시보드에서 `계산 근거 보기`를 누르면, then 같은 화면에서 스냅샷 → 시각 → 입력 버전 → 계산 버전 → 지표 영향 → 버전 목록이 한 응답으로 펼쳐진다. `계산 근거 닫기`는 초점을 버튼으로 돌려준다.
- Given 근거가 열린 화면, when 근거 읽기가 실패하면, then 대시보드 카드·세 축·상태 문장의 결과 부분은 그대로다. 근거 영역은 최소 참조와 재시도만 보인다.
- Given 어떤 변조 참조, when 근거를 요청하면, then 응답은 404 한 가지이며 본문이 글자까지 같다.

## Design Notes

**새 경로 대신 페이지 안 영역이다.**
- 기획은 "계산 근거 화면"이라 부르지만 route를 정하지 않았다(목업 없음).
- 새 경로는 다섯 곳을 동기화해야 하고, 문서를 이동하면 메모리의 금융값이 사라져 다시 읽어야 한다.
- 두 랜딩 화면이 같은 패널을 마운트하므로 한 번에 적용된다. "돌아가기"는 닫기와 초점 복귀로 만족한다.
- 진입이 링크가 아니라 버튼인 것은 이동이 아니라 상태 변경이어서다(EXPERIENCE:200).

**참조를 받는 이유** -- AC-1은 "요청한 dashboard result reference"를 서버가 검증하라고 한다. 식별자를 받지 않는 `/api/dashboard`와 달리, 이 경로는 받은 참조가 현재 포인터와 같은지만 판정한다. 그래서 받은 값으로 탐색할 수 있는 것이 없다. 지난 버전의 근거는 2.9 범위 밖이다(2.11 이전에는 존재하지도 않는다).

**버전 목록**
- 포인터는 현재 행 하나이고 이력이 없다(V10:276-278). 2.9의 공식 버전은 늘 하나이며, 적용 시작은 포인터의 `published_at`, 끝은 "현재"다.
- 이력 테이블은 포인터를 전진시키는 2.11이 소유한다.
- 운영자 격리(Epic 3)는 아직 없으므로 미발행 목록은 발행 시도의 `FAILED`·`BLOCKED`뿐이다.
- 교체된 intent의 시도는 plan 모듈의 교체 체인이 필요하므로 포함하지 않는다.

**추정 전파 원인** -- 지표별 원인은 저장된 열이 아니라 파생값이다. 추정 흐름이 하나라도 있으면 현재가치 외 다섯 지표의 원인은 "추정 가격 근거(`SPOT_KLINE_CLOSE`)로 환산한 자금흐름 N건"이다. 개별 사건은 2.10이다.

**연결 출처** -- connection ID는 수집 provenance일 뿐 사실의 정체성이 아니다(SPINE:148). `provider_account_id`는 외부 계정 네임스페이스를 가리키므로 내보내지 않는다.

**`tc`와 `measured_at`** -- 기획 문서 일부는 둘을 섞어 쓰지만 AC-2와 ERD는 분리한다. 저장값도 따로다. 2.7 이후 `cashflow_verified_through = tc`는 CHECK로 늘 같다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend --no-parallel --max-workers=1 build` -- expected: Testcontainers 포함 전체 통과, 실패 0.
- Node 24: `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run test && npm --prefix frontend run build` -- expected: 모두 통과.
- 수동 compose 스택(migrate) 뒤 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080` 전체 e2e를 `--workers=1`로 돌리고, 이어서 `deploy/compose/verify-same-origin.sh`를 돌린다 -- expected: 통과, 스택 정리.
- `bash .github/scripts/test-ci-scripts.sh`, `git diff --check` -- expected: 통과.

**구현에서 확정한 것 (리뷰 patch 반영 후, frozen 블록 밖):**
- 판정 순서는 계정 접근 → 참조 형식 → 현재 포인터다.
  - 삭제 중인 계정은 형식이 틀린 참조에도 403이다.
  - 컨테이너가 파싱하지 못하는 쿼리도 형식 오류이며 같은 404다. 잘못된 `%` 이스케이프나 매개변수 수 초과가 여기에 든다. Tomcat 11이 `getParameterValues`에서 던지는 예외를 잡는다.
- 404 본문은 없는 경로의 공통 404와 글자까지 같다(같은 `X-Request-Id` 기준).
  - 근거 경로의 200·403·404·503·500은 모두 `no-store`다.
  - 403은 `sendError`를 쓰지 않고 같은 공통 본문을 직접 낸다.
- 활성 계획에 포인터가 없으면 404다. 대조할 현재 포인터가 없기 때문이다. 참조가 현재 포인터와 같은 뒤의 어긋남은 모두 503 `EVIDENCE_UNAVAILABLE`, `consistent=false`다.
- 계보 판정(하나만 어긋나도 503):
  - 2.8의 묶음 판정: `belongs`, portfolio 포인터, 지표 여섯, 제공자 `BYBIT`.
  - 생성 시도: `PUBLISHED`이고 이 계획·계산을 가리키며, 핀한 입력이 계산 버전과 같다. 비교하는 입력은 다음과 같다.
    - intent·portfolio·membership·connection·provider account
    - 기준 스냅샷 둘, candidate·판정·환산
    - 조건, `tc`, E(tc)와 측정 시각, 정책
    - 흐름 수(환산의 `flow_count`)
    - 발행 전 포인터가 기준 스냅샷이고 순번이 +1인지
  - tc 원천 스냅샷: candidate·연결·계정·측정 시각·정책이 같다.
  - 기준 원천 스냅샷: 첫 스냅샷 작업이 썼고(candidate 아님), 같은 연결·계정이며, `measured_at = t0`다.
  - candidate·판정·환산: 한 계보이고 계산 버전에 기록된 정책과 같다.
  - 수집 페이지 증거: 정확히 `(t0, tc]`, 즉 최소 시작 = `t0`, 최대 끝 = `tc`다.
  - 반영 흐름: 건수·추정 건수가 맞고, 다른 환산을 가리키는 행이 0이며, 추정 가격 근거 종류가 하나다.
- 핀 대조의 한계와 재검토 대상.
  - "발행 전 포인터 = 기준 스냅샷, 순번 +1"은 최초 발행에만 성립한다. 포인터를 따로 전진시키는 2.11과 후속 계획 2.15가 다시 봐야 한다.
  - 핀한 `input_scope_hash`는 재계산하지 않는다. intent의 source·portfolio 정책 버전이 해시에 들어가는데, 그 값은 plan 모듈에만 있고 노출하는 포트가 없다. 새 공개 API는 Ask First 범위다.
- 연결 정책 버전은 기준 원천 스냅샷(`sync_source_snapshot.policy_version`)에서 읽는다.
  - 첫 스냅샷 발행이 연결의 정책과 대조한 뒤 저장한 sync 소유 기록이다(`SyncJobService#publish`). 다른 모듈 테이블은 읽지 않는다.
  - 수집 정책은 candidate의 `policy_version`이며 원천 범위에 `수집 정책`으로 한 번만 나온다.
  - 지금은 두 값이 같은 어댑터 상수(`bybit-readonly-uta-1.0`)라 화면에서 같은 글자다. 두 기록이 각자 쓰일 때 연결의 정책과 대조되기 때문이다.
- 스냅샷 절에는 공식 portfolio snapshot·순번, 기준 snapshot, 기준 원천 스냅샷(t0), tc 원천 스냅샷이 나온다.
- 지표 영향과 추정 원인.
  - 추정 원인은 저장값이 아니라 파생값이다.
  - 추정 버전의 정의되는 파생 지표에만 "추정 가격 근거(`SPOT_KLINE_CLOSE`)로 환산한 입출금 N건"이 붙는다.
  - 계산 불가 지표는 성격과 이유만 보이고 원인 문장이 없다. 현재가치는 "Bybit가 측정한 값"이다.
- 버전 목록.
  - 포인터에 이력이 없어 공식 버전은 하나다. 발행 순서는 `pointer_version`, 적용 시작은 포인터의 `published_at`, 끝은 "현재 공식 버전"이다.
  - 미발행은 같은 intent의 `FAILED`·`BLOCKED`만 보이고, 상태·이유 코드·종료 시각만 보인다. 다른 intent의 시도는 넣지 않는다.
- 운영 로그는 읽기마다 한 줄이다: `calculation evidence read: state=FOUND|NOT_FOUND|INCONSISTENT|DENIED|ERROR status= latencyMs= consistent=true|false|unknown`.
- 화면 구조와 초점.
  - 버튼 이름은 `계산 근거 보기`로 고정된 토글이다(`aria-expanded`·`aria-controls`).
  - 영역은 지표 h3 바로 아래의 디스클로저다. 제목은 h4이고 여섯 절은 h5 group이다. `계산 근거 닫기`는 영역 끝에 있다.
  - 열 때마다 새로 읽고, 닫으면 버린다.
  - 묶음이 바뀌면 영역을 닫고, 초점이 안에 있었으면 버튼으로 돌린다. 결과 자체가 사라지면 패널 제목으로 돌린다.
- 읽기 실패와 재시도.
  - 404: 재시도를 주지 않고 "대시보드를 다시 불러온 뒤 다시 열어 주세요"라고 안내한다.
  - 네트워크·5xx·503·형식 오류: 재시도 가능이다. 재시도 중에도 버튼이 제자리에 `aria-disabled`로 남아 초점을 유지한다.
  - 두 경우 모두 메모리의 snapshot ID·계산 ID를 최소 참조로 보인다.
- 근거 401·403은 근거를 지우고 대시보드에 다시 읽기를 요청한다. 대시보드가 자기 읽기로 금융값을 지울지 정한다. 단순 실패는 대시보드를 건드리지 않는다.
- 읽기 중단과 늦은 응답.
  - 진행 중인 읽기는 닫기·묶음 교체·언마운트에서 `AbortController`로 중단한다. 중단은 실패가 아니다.
  - 순번 가드와 묶음 키 ref는 layout effect에서 커밋과 함께 움직인다. 교체 커밋 직후의 microtask에 도착한 옛 응답도 버린다. 시험은 교체 커밋의 layout effect에서 응답을 풀어 이 순간을 재현한다.
- 상태 리전은 `aria-atomic="false"`이고 대시보드 문장과 근거 문장을 두 요소로 둔다. 근거 상태가 바뀌어도 대시보드 문장을 다시 읽지 않는다. 두 화면이 `DashboardStatusText`와 `useDashboardEvidence`를 공유한다.
- 시각은 시각 절만 초·밀리초까지 보인다(`formatSeoulInstant`, 형식 객체 캐시). 계획 기간·생성·발행·종료 시각은 계획 조건과 같은 형식이다.
- 대시보드 응답 검증에 `portfolioSnapshotId`·`basisPortfolioSnapshotId`를 더했다. 서버는 이미 보내고 있었다.

**실행 결과 (리뷰 patch 반영 후):**
- 백엔드 `build`(`--no-parallel --max-workers=1`) 통과: 49개 시험 클래스, 736 tests, 실패·skip 0.
  - 그중 `FirstSnapshotTest` 111, `PlanPublicationServiceTest` 20, `ModuleBoundaryTest` 20.
- 프런트엔드 (Node 24): `typecheck`·`lint`·`lint:css`·`format:check`·`build` 통과. Vitest 19개 파일 481/481.
- 수동 compose 스택(migrate V1~V10, 런타임 생성 key ring) 뒤 주입 origin e2e `--workers=1`: 58/58, 스택 정리.
- 이어서 `verify-same-origin.sh` 959/959, 스택 정리.
- `test-ci-scripts.sh` 147/147, `git diff --check` 통과.
- 변이 확인(되돌리면 의도한 시험이 실패했고, 모두 원래대로 복원했다):
  - 백엔드: 읽기 뒤 접근 재확인, 포인터 버전 대조, tc 원천 스냅샷 측정 시각 대조, 파싱 실패 404 처리.
  - 프런트엔드:
    - 묶음 교체 시 폐기, 버튼으로 초점 복귀, 열 때 제목으로 초점 이동
    - 참조 대조, 순번 가드, layout effect 가드, 넓힌 초점 effect
    - 상태 문장 합치기, `aria-atomic`
    - 정의 불가 지표의 원인 제거, 조건·생성 시각·수집 범위 교차 검증
    - 근거 거부 시 대시보드 다시 읽기, 닫기 시 중단, 닫힌 영역의 숨은 읽기

## Suggested Review Order

**근거 읽기 (입구)**

- 입구: 앞뒤 접근 확인, RR 한 묶음, 참조가 현재 포인터와 같을 때만 계보를 읽는다.
  [`PlanPublicationService.java:629`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L629)

- 계보 판정: 발행 시도의 고정 입력, 원천 스냅샷 둘, 수집 범위, 판정·환산·흐름이 한 계보여야 한다.
  [`PlanPublicationService.java:665`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L665)

- 기준 원천 스냅샷은 첫 스냅샷 작업이 썼고 `t0`에 측정됐다. 연결 정책의 출처다.
  [`PlanPublicationService.java:713`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L713)

- 수집 범위는 `[t0, tc]`와 정확히 같아야 한다.
  [`PlanPublicationService.java:719`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L719)

- 지표 영향: 추정 원인은 파생값이고, 정의 불가 지표에는 붙이지 않는다.
  [`PlanPublicationService.java:827`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L827)

- 같은 intent의 미발행 시도만 분리해 읽는다.
  [`PlanPublicationRepository.java:547`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationRepository.java#L547)

- 응답 계약: 근거 모델과 결과 sealed 타입.
  [`SyncJobApi.java:222`](../../backend/src/main/java/com/bokrilog/sync/SyncJobApi.java#L222)

**HTTP·보안·관측**

- `GET /api/dashboard/evidence`: 200/403/404/503/500, 전부 `no-store`.
  [`DashboardController.java:201`](../../backend/src/main/java/com/bokrilog/DashboardController.java#L201)

- 파싱할 수 없는 쿼리도 같은 404(접근 확인이 먼저).
  [`DashboardController.java:270`](../../backend/src/main/java/com/bokrilog/DashboardController.java#L270)

- 운영 로그 한 줄, 네 필드만.
  [`DashboardController.java:242`](../../backend/src/main/java/com/bokrilog/DashboardController.java#L242)

**화면**

- 근거 훅: 참조 교차 검증, 커밋에 맞춘 키 ref, 중단, 거부 시 대시보드 다시 읽기.
  [`useCalculationEvidence.ts:322`](../../frontend/src/session/useCalculationEvidence.ts#L322)

- 응답 검증: 보이는 묶음의 참조·조건·시각과 대조한다.
  [`useCalculationEvidence.ts:177`](../../frontend/src/session/useCalculationEvidence.ts#L177)

- 근거 영역: 여섯 절, 세 시각 설명, mono 전체값, 닫기와 초점 복귀.
  [`CalculationEvidence.tsx:495`](../../frontend/src/components/CalculationEvidence.tsx#L495)

- 지표 절 안의 진입점.
  [`PlanResultPanel.tsx:423`](../../frontend/src/components/PlanResultPanel.tsx#L423)

- 화면 status 하나가 `aria-atomic="false"`로 두 문장을 따로 말한다.
  [`DashboardStatusText.tsx:16`](../../frontend/src/components/DashboardStatusText.tsx#L16)

**시험**

- 현재 근거 한 묶음의 전 계보.
  [`FirstSnapshotTest.java:3050`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L3050)

- 변조 참조의 같은 404.
  [`FirstSnapshotTest.java:3244`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L3244)

- 계보 손상마다 503과 `consistent=false`.
  [`FirstSnapshotTest.java:3332`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L3332)

- 조건 하나씩 깨는 단위 시험.
  [`PlanPublicationServiceTest.java:525`](../../backend/src/test/java/com/bokrilog/sync/internal/PlanPublicationServiceTest.java#L525)

- 삭제 앞뒤와 500 경로.
  [`FirstSnapshotTest.java:3487`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L3487)

- 실 스택에서 키보드로 열고 닫기, 여섯 절 순서, 320·640px.
  [`first-snapshot.spec.ts:329`](../../frontend/e2e/first-snapshot.spec.ts#L329)
