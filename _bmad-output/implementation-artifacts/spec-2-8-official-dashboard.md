---
title: '공식 대시보드: 여섯 지표와 세 신뢰 상태'
type: 'feature'
created: '2026-09-28'
status: 'done'
baseline_commit: 'a251e2b9206916e106257e20d157891ef6c0b352'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics.md'
  - '{project-root}/_bmad-output/planning-artifacts/EPIC-IMPLEMENTATION-READINESS.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-BokriLog-2026-08-23/EXPERIENCE.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-7-first-plan-publication.md'
---

<frozen-after-approval reason="승인된 Epic 2 Story 2.8 범위와 SYNC-UX-1.0·DISPLAY-1.0 계약">

## Intent

**Problem:** 2.7의 결과 패널은 공식 포인터 하나에서 값을 그리지만 대시보드로는 모자라다. 다음 넷이 빠져 있다.
- 조회 상태: `계획 없음`·`결과 준비 중`·`재시도 가능`을 구분하지 못한다(204 하나, 실패하면 패널이 사라진다).
- 세 신뢰 축의 내용: 원인·마지막 정상 시각·영향 지표·다음 행동이 없고, 대부분 클라이언트 고정 문구다.
- 정보 순서: 현재가치 → 세 축 → 지표 → 다음 행동이 아니다. 현재가치가 두 번 보이고 live region이 둘이다.
- 버전 일관·삭제 재확인·운영 관측의 조회 경계.

**Approach:** 서버가 대시보드 조회 모델 하나(`GET /api/dashboard`)를 준다. 다음 셋을 한 번의 일관된 읽기로 판정한다.
- 상태: `NO_PLAN`·`PREPARING`·`READY`.
- 공식 포인터 묶음.
- 세 축의 상태·원인·마지막 정상 시각·영향 지표·다음 행동 코드.

화면은 그 응답 하나를 한 단위로 교체한다. 코드는 닫힌 맵으로 카피에 연결한다. `/dashboard`·`/onboarding`이 같은 대시보드를 모바일 우선 순서로 보인다.

## Boundaries & Constraints

**Always:**
- **조회 범위:** 세션의 내부 `user_id` 하나다.
  - 경로는 식별자를 받지 않는다. 쿼리의 계획·snapshot·계산 ID는 무시하고, 응답은 붙이지 않은 요청과 글자까지 같다.
  - 하위 경로는 대상의 존재와 무관하게 같은 404 공통 오류다.
- **한 묶음:** 계획 → 포인터 → 계산 버전 → 지표 → 신선도를 읽기 전용 `REPEATABLE READ` 트랜잭션 하나에서 읽는다.
  - 포인터의 계산이 없거나, 그 계산의 계획·snapshot이 포인터와 어긋나면 부분 응답을 내지 않는다. 공통 오류 계약 `503` + `code: DASHBOARD_UNAVAILABLE`로 끝낸다.
  - 후보·격리·검토 데이터는 읽지 않는다.
- **상태:**
  - `READY`: 활성 계획이 있다(포인터는 계획과 원자 발행된다).
  - `PREPARING`: 활성 계획이 없고, 그 사용자의 발행 시도가 `QUEUED|RUNNING`이다.
  - `NO_PLAN`: 그 밖의 모든 경우다.
- **세 축(서버 판정):** 축마다 `state`·`cause`·`lastNormalAt`·`affectedMetrics`·`nextAction`을 코드로 준다.
  - 신선도: SYNC-UX-1.0(15분, DB 시계, 진행 중 작업이면 `SYNCING`)이다. `measuredAt`과 `cashflowVerifiedThrough`를 각자 이름으로 함께 준다.
  - 입력 신뢰도: `CONFIRMED|REVIEW_REQUIRED|UNUSABLE`이다.
  - 계산 성격: `OFFICIAL|ESTIMATED|NOT_COMPUTABLE`이고, `character`는 별도로 유지한다.
  - 종합 점수나 단일 배지는 없다. 클라이언트는 시간 기준을 따로 만들지 않는다.
- **표시:**
  - 금액·비율은 DISPLAY-1.0(HALF_UP 2자리, `-0.00`→`0.00`)으로 한 번만 반올림한다.
  - 시각은 `Asia/Seoul`이다.
  - 계산 불가는 값 자리에 문구를 두고 이유를 `aria-describedby`로 잇는다.
  - 음수와 100% 초과는 그대로 보인다. 색·아이콘·모션으로 손익을 평가하지 않는다.
- **원자 교체:**
  - 응답을 전부 검증한 뒤에만 여섯 값과 세 축을 함께 바꾼다.
  - 늦게 도착한 이전 요청의 응답은 버린다.
  - 같은 계획에서 `pointerVersion`이 뒤로 가거나, 같은 `pointerVersion`인데 `calculationId`가 다르면 교체하지 않고 `재시도 가능`으로 둔다.
- **실패:**
  - 조회 실패는 보이던 공식 결과를 `마지막 정상 결과`로 유지하고 다시 불러오기를 준다.
  - 이전 결과가 없으면 금융값 없이 `재시도 가능`이다.
  - 403은 보이던 결과를 메모리에서 지운다.
- **삭제:** 계정 접근(SENSITIVE)을 읽기 트랜잭션 앞뒤로 확인한다. 어느 쪽이든 거부면 403이고 금융 필드가 없다. 다른 사용자 결과는 보존한다.
- **캐시·관측:**
  - 200은 `no-store`다.
  - 조회마다 운영 로그 한 줄을 남긴다: 상태·HTTP 상태·지연 ms·버전 일치 여부만 적고, 사용자 ID·금액·시각·계산 입력은 적지 않는다.
  - 금융값은 URL·브라우저 저장소에 두지 않는다.
- **접근성:**
  - 화면당 `role=status` 하나, 축·카드에는 live region이 없다.
  - 논리적 제목과 초점 순서, 44px 조작 영역, 탭형 숫자를 쓴다.
  - 320px와 200%·400% 확대에서 잘림·겹침·가로 스크롤이 없다. reduced-motion을 지원한다.
- **고지:** `복리 계획 비교` 제목에 투자성과·계좌 수익률·투자 추천이 아니라는 고지를 인접 배치한다.

**Ask First:** 새 마이그레이션(V11), 새 내부 화면 경로, SYNC-UX-1.0·CALC-1.0·DISPLAY-1.0의 경계 변경, 새 운영 의존.

**Never:**
- 동기화 실행·조작, 계산 근거 상세(2.9), 조정 이력 상세(2.10), 계획 종료, 광고는 만들지 않는다.
- 없는 상세 화면으로 가는 링크는 두지 않는다.
- 폴링과 클라이언트 시계 기반 상대 시각은 쓰지 않는다.
- 카드별 추가 조회는 하지 않는다. 표시 문자열을 API로 되돌려 보내지 않는다.
- `double`/`float`를 쓰지 않는다. 계산 불가를 0으로 대체하지 않는다.
- 축하·공포·스트릭·배지·수익 지시 문구와 연출은 쓰지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| READY | 활성 계획·포인터 | 200 `READY` + 한 묶음 + 세 축 | N/A |
| NO_PLAN | 계획·진행 중 발행 없음 | 200 `NO_PLAN`, `nextAction=CREATE_PLAN`, 금융 필드 없음 | N/A |
| PREPARING | 계획 없음 + 발행 `QUEUED/RUNNING` | 200 `PREPARING`, `nextAction=WAIT_FOR_RESULT` | N/A |
| TAMPER | 다른 사용자 ID 쿼리 / 하위 경로 ID | 자기 응답과 동일 / 동일 404 | 존재 비노출 |
| INCONSISTENT | 포인터 계산 없음·계획 불일치 | 부분 응답 없음, 로그 `consistent=false` | 503 `DASHBOARD_UNAVAILABLE` |
| DELETING | 앞·뒤 확인 중 하나라도 삭제 진행·완료 | 403, 금융 필드 없음, 타 사용자 보존 | 클라이언트 결과 삭제 |
| READ_FAIL | 네트워크·5xx(503 포함), 이전 결과 있음/없음 | `마지막 정상 결과`+다시 불러오기 / `재시도 가능` | 0 표시 없음 |
| RACE | 늦은 이전 응답, 버전 역행·충돌 | 교체 안 함 | `재시도 가능` |

</frozen-after-approval>

## Code Map

**계약**
- `epics.md` `### Story 2.8` -- AC-1~AC-13과 시험 기준.
- `EPIC-IMPLEMENTATION-READINESS.md:104-111` -- SYNC-UX-1.0.
- `EXPERIENCE.md`
  - 정보 순서: 40, 299.
  - 전파 표: 131-139.
  - 축별 상시 표시: 158-166.
  - 상태: 170-184.
  - 접근성: 205-217.
  - 금지 문구: 60-70.
- `mockups/key-mobile-dashboard.html:23-46` -- 배치 참고. 그 안의 "2분 전"(클라이언트 시계)과 섹션 제목 "계획 지표"는 따르지 않는다.

**백엔드** (`backend/src/main/java/com/bokrilog/`)
- `PlanResultController.java:20-90` -- `DashboardController`로 바꾼다.
  - 기존 `ResultView` 모양을 `result`로 유지하고 축 필드를 넓힌다.
  - 30-31의 "lastNormal 없음" 결정은 AC-5가 뒤집는다(Design Notes).
- `sync/SyncJobApi.java`
  - `PlanResult`·`ActivePlanResult`·`activePlanResult`(110-154)를 대시보드 결과 타입으로 교체한다.
  - 소비자는 이 컨트롤러 하나다.
- `sync/internal/PlanPublicationService.java`
  - `activeResult`(537-557)를 대시보드 읽기로 바꾼다.
  - 기존 `transactions`(82)는 기본 격리이므로 읽기 전용 `REPEATABLE READ` template을 따로 둔다.
  - `readable`(567-569)을 앞뒤로 부른다.
- `sync/internal/PlanPublicationRepository.java`
  - `pointer`·`calculation`·`metrics`(346-383)를 재사용한다.
  - `freshness`(390-401)를 재사용한다.
  - 발행 진행 여부 조회 하나를 더한다(`sync_plan_publication_job`, `V10:88-147`).
- `AuthSecurityConfig.java`
  - `ACTIVE_PLAN_PATH`(231)와 `isActivePlanPath`(1220-1221)를 대시보드 경로와 술어로 교체한다.
  - 인가 988-991·1200-1203과 CSRF 1185-1186은 같은 술어를 쓴다.
- `ApiErrorHandler.java:88-173` -- 공통 오류 본문과 correlationId.

**프런트엔드** (`frontend/src/`)
- `content/planResult.ts`
  - 경로 상수(11)를 바꾼다.
  - 지표 이름(54-79)과 이유(92-101)는 재사용한다.
  - 축 고정 문구(104-173)는 코드별 닫힌 맵으로 바꾼다.
- `session/usePlanResult.ts`
  - 한 번만 fetch한다(135-165).
  - 실패하면 결과를 덮어쓴다(158). 이것을 고친다.
  - `isPlanResult`(79-112)는 검증의 뼈대로 쓴다.
- `components/PlanResultPanel.tsx`
  - 카드는 30-76, 축은 84-135, 섹션 순서는 175-285다.
  - 순서는 요약 → 축 → 지표 → 다음 행동 → 조건·정책으로 바꾼다.
- `screens/SignedInScreen.tsx`
  - status 영역은 109-133이다.
  - 패널 마운트는 145-152다. `READY`면 `FirstSnapshotPanel`(현재가치 중복, 자체 `aria-live` 54)을 그리지 않는다.
- `screens/OnboardingScreen.tsx:142-149` -- 같은 마운트다. 첫 로그인 세션은 이 화면에 도착한다.
- `components/FirstSnapshotPanel.tsx:78-99` -- `plan` 값에 따른 문장과 링크. `계획 없음` 문장과 조건 링크는 화면에 한 번만 나와야 한다.
- `format/decimal.ts` -- `formatAmount`·`formatPercent`·`formatSeoulTime`을 그대로 쓴다.
- `test/setup.ts:86-88`(`stubPlanResult`), `test/planResultFixture.ts` -- 새 경로와 상태에 맞춘다.
- 스타일
  - `styles/tokens.css`의 trust-status-panel(105-108)과 metric-card(110-115) 토큰, `--space-target-min`.
  - `global.css:72-74`(tabular), `global.css:101-110`(reduced-motion).

**시험 anchor**
- 백엔드 `FirstSnapshotTest.java`
  - 결과 HTTP: 1838-1893.
  - 신선도: 2315-2345.
  - 경계: 2428-2542. 2510의 `lastNormalAt` 부재 단정은 이 스토리가 바꾼다.
- 프런트엔드
  - `PlanResultPanel.test.tsx:44-79`. 74의 "마지막 정상 시각" 부재 단정은 이 스토리가 바꾼다.
  - `SignedInScreen.test.tsx:281-383`, `OnboardingScreen.test.tsx:120-140`.
  - `e2e/first-snapshot.spec.ts`: `publishAndOpenResult` 145-159, `expectFirstResult` 165-204, `expectNoHorizontalScroll` 206-219.

## Tasks & Acceptance

**Execution:**
- [x] `sync/SyncJobApi.java`, `sync/internal/PlanPublicationService.java`, `PlanPublicationRepository.java`, 새 `sync/internal/DashboardTrust.java`
  - 대시보드 읽기: 삭제 확인 → RR 읽기 전용 한 묶음 → 삭제 재확인 → 운영 로그 한 줄.
  - 세 축 판정(원인·마지막 정상 시각·영향 지표·다음 행동)은 순수 함수에 둔다.
- [x] `PlanResultController.java` → `DashboardController.java`, `AuthSecurityConfig.java`
  - `GET /api/dashboard`: 200 `{state, nextAction?, result?}` / 403 / 503 `DASHBOARD_UNAVAILABLE`, `no-store`.
  - 경로와 술어를 교체하고 `/api/plans/active`는 없앤다.
- [x] 백엔드 시험
  - `FirstSnapshotTest`의 결과 단정을 새 경로로 옮긴다(기존 사슬과 도우미를 재사용한다).
  - I/O matrix 전 행을 시험한다.
  - 축 판정 단위 시험: 신선도 3 × 입력 2 × 성격 3의 원인·영향·다음 행동.
  - 읽기 트랜잭션이 read-only `REPEATABLE READ`임을 단정한다.
  - 로그 캡처: 사용자 UUID·decimal 부재.
  - `DELETION_IN_PROGRESS`·`DELETED`를 확인한다.
- [x] `content/planResult.ts`, `session/usePlanResult.ts`(→ 대시보드 훅)
  - 상태 `checking/NO_PLAN/PREPARING/READY/lastGood/retryable/denied`.
  - 다시 불러오기, 요청 순번 가드, 버전 검사, 403에서 삭제.
  - 축 코드 → 카피 닫힌 맵. 모르는 코드는 안전한 카피로 둔다.
- [x] `components/PlanResultPanel.tsx`(+`.module.css`), `SignedInScreen.tsx`, `OnboardingScreen.tsx`, `FirstSnapshotPanel.tsx`
  - AC 순서로 재배치한다.
  - AC-4 인접 안내를 둔다.
  - 계산 불가 `aria-describedby`를 단다.
  - `READY`면 `FirstSnapshotPanel`을 숨긴다.
  - status 한 곳에서 상태 변화를 낭독한다.
- [x] 프런트엔드 시험
  - Vitest: AC-3~AC-11. 100% 초과, 버전 경합과 늦은 응답, 실패 뒤 유지, 403 삭제, 상태 넷, 금지어, 저장소 0.
  - e2e: 순서, 320·640px, 키보드 초점 순서, status 하나, 새 경로.

**Acceptance Criteria:**
- Given 발행을 마친 사용자, when `/dashboard`나 `/onboarding`을 열면, then 다음 순서로 보인다.
  - `마지막 정상 현재가치`와 측정 시각.
  - 세 축(상태·원인·마지막 정상 시각·영향 지표·다음 행동)과 두 시각의 분리 안내.
  - 여섯 카드(이름 → 값 → 주기·기준 → 성격).
  - 다음 행동.
  - 이 모두가 한 응답에서 온다. 현재가치는 화면에 한 번만 나온다.
- Given 결과가 보이는 화면, when 다시 불러오기가 실패하면, then 같은 값이 `마지막 정상 결과`로 남고 0이나 부분 값은 나오지 않는다. when 403이면, then 금융값이 화면과 메모리에서 사라진다.
- Given 포인터 묶음이 어긋난 데이터, when 조회하면, then 어떤 지표도 반환되지 않고 운영 로그에 `consistent=false`가 남는다.

## Design Notes

**`GET /api/plans/active`를 교체한다.** 204는 `NO_PLAN`과 `PREPARING`을 구분할 수 없고, 그 경로의 소비자는 화면 하나다. 두 표면을 두면 삭제·캐시·버전 판정이 두 곳이 된다.

**마지막 정상 시각** — 2.7의 "없음" 결정을 뒤집는다. 포인터가 가리키는 묶음이 곧 마지막 정상 묶음이므로 새 저장 없이 축마다 정의한다.
- 신선도 = `measuredAt`
- 입력 = `cashflowVerifiedThrough`
- 계산 = `basisAt`

**영향 지표**
- 신선도: `FRESH`면 없음, 그 밖에는 여섯 전부.
- 입력: `REVIEW_REQUIRED`(`TARGET_NOT_POSITIVE`)면 `ADJUSTED_TARGET`과 그 이유로 정의되지 않은 지표.
- 계산: `ESTIMATED` 지표 ∪ 정의되지 않은 지표.

**다음 행동 코드**(2.8의 닫힌 집합) -- `CREATE_PLAN`, `WAIT_FOR_RESULT`, `WAIT_FOR_SYNC`, `NONE`.
- 동기화 조작은 2.11이 코드를 더한다. `최신 아님`은 `NONE`이다.
- 카피는 "지금 이 화면에서 새로 확인할 수 없습니다. 표시된 값은 {측정 시각}의 마지막 정상 결과입니다"이다. 자동 복구나 미래 기능을 약속하지 않는다.

**`UNUSABLE`** -- 판정 규칙이 기획에 없다. 응답 계약과 카피는 지원하되 2.8의 서버는 만들지 않는다(생산자는 2.11·2.12). 표시는 Vitest가 검증한다.

**신선도는 입력 검토와 독립이다.** EXPERIENCE는 `최신 + 검토 필요 + 계산 불가` 조합을 예로 든다. `SYNCING`이 `STALE`보다 우선한다(2.7과 같다). 활성 계획 뒤에는 새 작업이 없으므로 발행 15분 뒤부터 `최신 아님`이 정상이다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend --no-parallel --max-workers=1 build` -- expected: Testcontainers 포함 전체 통과, 실패 0.
- Node 24: `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run test && npm --prefix frontend run build` -- expected: 모두 통과.
- 수동 compose 스택(migrate) 뒤 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080` 전체 e2e와 `deploy/compose/verify-same-origin.sh` -- expected: 통과, 스택 정리.
- `bash .github/scripts/test-ci-scripts.sh`, `git diff --check` -- expected: 통과.

**구현에서 확정한 것 (리뷰 patch 반영 후, frozen 블록 밖):**
- 현재가치 한 번 vs 여섯 카드: 현재가치 카드가 맨 앞의 `마지막 정상 현재가치` 블록이 되고 `지표`에는 나머지 다섯이 온다. 카드는 여섯이고 지표 순서이며 현재가치는 화면에 한 번 나온다.
- 한 묶음이 어긋났다(503 `DASHBOARD_UNAVAILABLE`)고 보는 경우: 포인터 없는 활성 계획, 계산이 없는 포인터, 계산의 계획 ID·portfolio·`t0`·`end_at`·주기·원금·목표율이 계획과 다름, portfolio 포인터가 계산의 snapshot·순번을 가리키지 않음(AD-23에 기댄다 — 두 포인터를 따로 전진시키는 스토리, 예컨대 2.11은 이 검사를 다시 봐야 한다), 지표가 여섯이 아니거나 성격이 계산 버전과 어긋남, 입력 신뢰도가 이 스토리가 아는 두 값 밖(순수 함수가 fail-closed로 판정하지 않는다).
- 운영 로그는 컨트롤러가 요청마다 한 줄 쓴다. 실제로 답한 HTTP 상태, 망라 switch(`default` 없음), 필드 넷(`state`·`status`·`latencyMs`·`consistent`)뿐이다. 묶음을 읽지 않았으면(읽기 전 거부·예외) `consistent=unknown`이다. 읽기가 던지면 컨트롤러가 공통 500을 직접 답하고 `state=ERROR status=500`을 남긴다. 서비스 port는 로그를 쓰지 않고 `DashboardRead(dashboard, BundleConsistency)`를 돌려준다.
- 삭제가 끝난 뒤의 요청은 세션 행이 지워져 401 `UNAUTHENTICATED`다(보안 필터가 읽기 전에 답한다).
- 화면 단위 다음 행동은 상태별 닫힌 쌍이다: `NO_PLAN`→`CREATE_PLAN`, `PREPARING`→`WAIT_FOR_RESULT`, `READY`→`NONE`|`WAIT_FOR_SYNC`. 다른 쌍은 클라이언트가 받지 않는다. 축의 다음 행동은 `WAIT_FOR_SYNC`·`NONE`뿐이다.
- `NONE`의 승인 문장("지금 이 화면에서 새로 확인할 수 없습니다. 표시된 값은 {측정 시각}의 마지막 정상 결과입니다.")은 다음 행동 섹션에 한 번만 나온다. 시각을 표시할 수 없으면 시각 없는 변형이다. 축에는 시각 없는 짧은 문장이 온다. `CREATE_PLAN`은 계획을 만들 수 있다고 단정하지 않고 계획 조건 화면을 가리킨다.
- READ_FAIL(이전 결과 있음)은 같은 값을 `마지막 정상 결과`로 둔다. RACE(버전 역행·같은 버전 다른 계산)는 보이던 값을 바꾸지 않고 `재시도 가능`으로 두며, 표시된 값이 마지막 정상 결과라는 문장과 다시 불러오기를 준다. 이전 결과 없는 실패는 금융값 없는 `재시도 가능`이다.
- 403은 묶음을 메모리에서 지우고 중립 문장을 보인다. 401도 같이 지우되 세션 만료 문장(발행 섹션과 같은 문장)과 진입 화면 `/`로 가는 문서 링크를 준다.
- 세션이 ready가 아니게 되면 묶음을 버리고 `checking`으로 돌아간다. 진행 중인 읽기는 모두 `loading`이다(ready false→true가 시작한 읽기 포함).
- 첫 자산 패널은 대시보드가 `NO_PLAN`·`PREPARING`으로 답했을 때만 마운트한다. 묶음이 있으면 대시보드의 첫 카드가 현재가치이고, 결과 없는 실패·거부에서는 금융값이 나오면 안 되며, 답 전에는 아는 것이 없다. `계획 없음` 문장과 계획 조건 링크는 대시보드가 한 번만 보인다.
- 상태 리전은 화면에도 보이므로 `계획 없음`·`결과 준비 중`은 그 리전의 문장이 말하고, 패널은 `마지막 정상 결과`·`재시도 가능` 이름을 값 곁에 둔다.
- 다시 불러오기는 결과가 있는 모든 상태에서 같은 자리에 있어 키보드 포커스가 유지되고, 진행 중에는 `aria-disabled`다. 그 버튼이 사라지는 상태(거부·세션 만료·확인 중)로 바뀌면 포커스가 패널 안에 있었을 때 대시보드 제목(tabIndex -1)으로 옮긴다.
- 클라이언트 검증은 묶음 전체를 받거나 버린다: 축마다 자기 마지막 정상 시각, 신선도의 두 시각, 계산 불가 지표 ⇔ `NOT_COMPUTABLE`, 성격 ⇔ 다섯 파생 지표가 공유하는 한 성격, 현재가치는 값이 있고 `OFFICIAL`.
- 계산 불가 이유는 값 요소에만 `aria-describedby`로 한 번 잇는다.
- 두 화면은 `planPresenceOf`·`statusBusy`·`dashboardAnnouncement`를 함께 쓰고, 상태 리전과 패널은 같은 닫힌 맵 조회(`copyFor`)를 쓴다.
- 로딩 skeleton은 만들지 않았다(확인 중에는 제목과 `aria-busy`만). 200%·400% 확대는 해당 CSS 폭으로 검증한다. `UNUSABLE` 생산자는 없다(카피와 Vitest만).
- 부정합 시험 하나는 replica 역할 세션으로 외래키를 우회해 계산 없는 포인터·다른 계획의 계산을 만든다. Testcontainers 사용자가 superuser라는 사실에 기댄다. 실패 주입 시험은 요청 하나 동안 포인터 테이블 이름을 바꾼다.

**실행 결과 (리뷰 patch 반영 후):**
- 백엔드 `build`(`--no-parallel --max-workers=1`) 통과: 49개 시험 클래스, 722 tests, 실패·skip 0. 그중 `FirstSnapshotTest` 104, `DashboardTrustTest` 30, `PlanPublicationServiceTest` 13, `ModuleBoundaryTest` 20.
- 프런트엔드 (Node 24): `typecheck`·`lint`·`lint:css`·`format:check`·`build` 통과, Vitest 17개 파일 415/415.
- 수동 compose 스택(migrate V1~V10, 런타임 생성 key ring export) 뒤 주입 origin e2e 58/58, 스택 정리. 이어서 `verify-same-origin.sh` 959/959, 스택 정리. 참고로 preview e2e는 38 통과·20 skip(주입 origin 전용)이다.
- `test-ci-scripts.sh` 147/147, `git diff --check` 통과.
- 변이 확인: 백엔드는 발행 진행 여부의 상태 필터, `belongs`의 주기·원금·계획 ID 검사, 읽기 뒤 삭제 재확인, portfolio 포인터 검사, 읽기 전용 플래그를 각각 제거했을 때 의도한 시험이 실패했다. 프런트엔드는 버전 검사, 늦은 응답 가드, 403 삭제, 마지막 정상 유지, 중복 다시 불러오기 가드, 첫 자산 패널 마운트 규칙, 묶음 교차 검사, 포커스 이동, ready false 시 폐기, RACE의 `재시도 가능`, 다음 행동 쌍 검사, `aria-describedby` 한 번을 각각 되돌렸을 때 의도한 시험이 실패했다. 모두 원래대로 복원했다.

## Suggested Review Order

**조회 모델 (입구)**

- 입구: 삭제 확인 → RR 읽기 전용 한 묶음 → 삭제 재확인.
  [`PlanPublicationService.java:552`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L552)

- 계획 → 포인터 → 계산 → 지표 → 신선도를 한 트랜잭션에서, 어긋나면 `Unavailable`.
  [`PlanPublicationService.java:570`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L570)

- portfolio 포인터 일치는 AD-23 전제다. 따로 전진시키는 스토리는 이 판정을 다시 봐야 한다.
  [`PlanPublicationService.java:582`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L582)

- 계산 버전이 계획 조건·portfolio와 같은지 판정한다(표기 차이는 같은 값).
  [`PlanPublicationService.java:611`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L611)

- 읽기 전용 `REPEATABLE READ` template을 따로 둔다.
  [`PlanPublicationService.java:88`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L88)

- `PREPARING`은 진행 중 발행 시도만 본다. 끝난 실패는 `NO_PLAN`이다.
  [`PlanPublicationRepository.java:357`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationRepository.java#L357)

**세 신뢰 축 (서버 판정)**

- 순수 함수 하나가 축마다 상태·원인·마지막 정상 시각·영향·다음 행동을 낸다.
  [`DashboardTrust.java:69`](../../backend/src/main/java/com/bokrilog/sync/internal/DashboardTrust.java#L69)

- 입력 신뢰도는 모르는 값을 확인됨으로 접지 않는다(fail closed).
  [`DashboardTrust.java:118`](../../backend/src/main/java/com/bokrilog/sync/internal/DashboardTrust.java#L118)

- 계산 성격: 추정 ∪ 정의 불가가 영향 지표, `character`는 따로 유지.
  [`DashboardTrust.java:132`](../../backend/src/main/java/com/bokrilog/sync/internal/DashboardTrust.java#L132)

- 응답 계약: 축 record와 대시보드 sealed 타입.
  [`SyncJobApi.java:126`](../../backend/src/main/java/com/bokrilog/sync/SyncJobApi.java#L126)

**HTTP·보안·관측**

- `GET /api/dashboard`: 200/403/503/500, 전부 `no-store`.
  [`DashboardController.java:86`](../../backend/src/main/java/com/bokrilog/DashboardController.java#L86)

- 운영 로그 한 줄은 실제 응답 상태를 아는 컨트롤러가 네 필드만 남긴다.
  [`DashboardController.java:136`](../../backend/src/main/java/com/bokrilog/DashboardController.java#L136)

- 새 경로의 인가와 CSRF·Origin 판정이 같은 술어를 부른다.
  [`AuthSecurityConfig.java:234`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L234)

**화면 상태와 원자 교체**

- 대시보드 훅: 요청 순번 가드, 실패 뒤 유지, 401/403 삭제, ready 해제 시 삭제.
  [`useDashboard.ts:279`](../../frontend/src/session/useDashboard.ts#L279)

- 버전 역행·충돌은 교체하지 않는다(`재시도 가능`으로 둔다).
  [`useDashboard.ts:233`](../../frontend/src/session/useDashboard.ts#L233)

- 응답 전체 검증과 필드 간 교차 검사(상태·다음 행동 짝, 성격·지표).
  [`useDashboard.ts:141`](../../frontend/src/session/useDashboard.ts#L141)

- 화면 status 하나가 상태 변화를 낭독한다.
  [`useDashboard.ts:376`](../../frontend/src/session/useDashboard.ts#L376)

**대시보드 화면**

- 순서: 현재가치 → 세 축 → 다섯 지표 → 다음 행동 → 조건·정책.
  [`PlanResultPanel.tsx:356`](../../frontend/src/components/PlanResultPanel.tsx#L356)

- 세 축과 두 시각의 분리 안내(AC-4).
  [`PlanResultPanel.tsx:201`](../../frontend/src/components/PlanResultPanel.tsx#L201)

- 카드: 계산 불가 문구와 이유를 값 요소 하나에 `aria-describedby`로 잇는다.
  [`PlanResultPanel.tsx:73`](../../frontend/src/components/PlanResultPanel.tsx#L73)

- 다시 불러오기 버튼이 사라지면 초점을 대시보드 제목으로 옮긴다.
  [`PlanResultPanel.tsx:372`](../../frontend/src/components/PlanResultPanel.tsx#L372)

- 다음 행동 문장: 시각을 인용하는 문장은 한 번, 시각을 못 쓰면 다른 문장.
  [`planResult.ts:243`](../../frontend/src/content/planResult.ts#L243)

- 결과가 있으면 첫 공식값 패널을 그리지 않는다.
  [`SignedInScreen.tsx:152`](../../frontend/src/screens/SignedInScreen.tsx#L152)

**시험**

- 상태 셋의 순서(`NO_PLAN` → `PREPARING` → `READY`).
  [`FirstSnapshotTest.java:2626`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L2626)

- 쿼리·하위 경로 변조의 동일 응답.
  [`FirstSnapshotTest.java:2664`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L2664)

- 묶음 손상마다 503과 `consistent=false`.
  [`FirstSnapshotTest.java:2710`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L2710)

- 읽기 앞뒤 삭제와 삭제 완료 뒤 401.
  [`FirstSnapshotTest.java:2836`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L2836)

- 운영 로그 한 줄: 사용자·금액·시각 부재, 실패 경로의 `consistent=unknown`.
  [`FirstSnapshotTest.java:2906`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L2906)

- 끝난 실패 발행 뒤 `NO_PLAN`.
  [`FirstSnapshotTest.java:1926`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L1926)

- 늦은 응답·버전 경합·실패 뒤 유지.
  [`useDashboard.test.tsx:1`](../../frontend/src/session/useDashboard.test.tsx#L1)

- 실 스택에서 순서·대체 문구 부재·320/640px.
  [`first-snapshot.spec.ts:171`](../../frontend/e2e/first-snapshot.spec.ts#L171)
