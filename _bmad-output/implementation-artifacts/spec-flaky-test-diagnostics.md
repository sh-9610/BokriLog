---
title: 'Epic 4 회고 AI-6 — 간헐 실패 시험의 진단 장치와 대기 관용'
type: 'chore'
created: '2026-10-17'
status: 'done'
baseline_commit: 'd92f569af13962b2a4dcfd41e10dfc4ad46cc97e'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-retro-2026-10-16.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 간헐 실패 셋(`deferred-work.md` 155·158·159)에 주인이 없다(Epic 4 회고 AI-6, P-1·P-2).
- **155:** 주입 origin e2e가 새 스택 첫 실행에서 로그인 직후 `/settings`를 미인증으로 연다. 실패 trace는 다음 실행이 `test-results`를 지워 사라졌다.
- **문서 이동 경합:** PR #111은 같은 계열의 문서 이동 경합을 세 번 증상별로만 막았다(앞 문서 내리기·최종 착지 대기).
- **158:** Windows 로컬 Vitest가 운영자 lazy chunk를 `findBy` 기본 1초 안에 받지 못한다.
- **159:** 전체 백엔드 빌드에서만 계획 종단 시험이 `RESULT_TOO_RECENT`·작업 `FAILED`로 함께 실패한다. 유력한 원인은 fixture가 측정 시각을 JVM 시계로 만들고 판정은 DB(Docker VM) 시각으로 해서, VM 시계가 뒤처지면 결과가 "미래"가 되는 것이다.

**Approach:** 원인을 덮지 않고 드러내는 장치를 둔다.
- 로컬 e2e 실패 trace를 보존한다.
- 문서 이동을 넘는 e2e 대기를 helper 하나로 모아 적용한다.
- lazy chunk 대기는 명시적 시한 상수를 쓴다.
- 백엔드 시험은 시작 때 JVM↔DB 시계 차이를 재서 넘으면 원인을 말하며 실패한다.
- 방법을 `DEVELOPMENT.md`에 적는다.

## Boundaries & Constraints

**Always:**
- **trace 보존(155).** 로컬 실행은 실행마다 새 출력 디렉터리(예: `test-results/<UTC 시각>`)를 써서 앞선 실패의 trace·screenshot을 지우지 않는다. 보존하는 실행 수에는 상한이 있다(예: 최근 5회). CI는 지금 경로·artifact 업로드 그대로다(`ci.yml`·`test-ci-scripts.sh` 불변).
- **e2e 대기 helper.** `frontend/e2e/support/` 아래 하나에 둔다.
  - "앞 문서 내리기"(`about:blank`)와 "최종 착지 대기"(경로가 정해진 목적지가 되고 그 화면의 제목이 보일 때까지)를 함께 담는다.
  - 로그인 helper(`ad-consent.spec.ts`의 `signIn`과 같은 모양의 사본들)는 첫 착지가 아니라 최종 착지를 기다리게 한다.
  - PR #111이 `ad-transition.spec.ts`에 손으로 넣은 두 관용을 이 helper 호출로 바꾼다. 단정 의미는 바꾸지 않는다.
- **158.** 운영자 lazy chunk를 기다리는 `findBy*`는 이름 있는 공용 시한 상수 하나를 쓴다(예: `LAZY_CHUNK_TIMEOUT_MS`, `src/test/` 아래). 전역 `asyncUtilTimeout` 상향은 하지 않는다(`DEVELOPMENT.md` "시험의 대기" 규칙). 대상은 158이 적은 파일들(`App.test.tsx` 운영자 경로·`OperatorScreen`·`OperatorNotice`·`SettingsConnectionRevoke`)의 lazy 대기다.
- **159.** 백엔드 시험 기반(`PostgresTestcontainersConfig` 근처)이 컨테이너 기동 직후 한 번 JVM `Instant.now()`와 DB `clock_timestamp()`의 차이를 잰다. 절댓값이 상한(예: 1초)을 넘으면 그 시각들과 "Docker VM 시계 재동기화(Docker Desktop 재시작)"를 말하며 즉시 실패한다. 상한 안이면 아무 영향이 없다.
- **문서.** `DEVELOPMENT.md`에 간헐 실패를 다루는 절 하나를 둔다. 담을 것은 trace 보존 위치, e2e helper 사용 규칙, lazy chunk 시한, 시계 차이 검사와 대처, 그리고 기존 `stress-test.sh`의 쓰임이다.

**Ask First:** 재시도(`retries`) 도입, 전역 시한 상향, CI 구성 변경, 제품 코드 변경.

**Never:** 실패를 재시도로 덮는 것, 단정을 약하게 바꾸는 것, `deferred-work.md` 기존 항목 수정(판정은 done 전환 몫).

</frozen-after-approval>

## Code Map

모든 경로와 명령은 worktree `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog-ai6`(브랜치 `chore/flaky-test-diagnostics`) 기준이다. 사용자 체크아웃은 고치지 않는다. Node 명령 앞에는 `eval "$(fnm env)" && fnm use 24 >/dev/null &&`를 붙인다. 백엔드 시험은 Docker Desktop이 떠 있어야 한다.

걸린 열린 회고 액션: `epic-4-retro-item-45`(AI-6) — 이 작업이 닫는다. 다른 열린 액션은 이 파일들에 걸리지 않는다.

- `frontend/playwright.config.ts:62-81` — `retries: 0`, `outputDir: 'test-results'`, `trace: 'retain-on-failure'`, `screenshot: 'only-on-failure'`. CI 판별은 기존 `usesInjectedOrigin`·`process.env.CI` 관용을 따른다. `.github/workflows/ci.yml`이 `frontend/test-results`를 업로드하는 경로를 읽어 CI 동작이 그대로인지 확인한다.
- `frontend/.gitignore` — `test-results`가 이미 무시되는지 확인한다.
- `frontend/e2e/support/`(`firstPlan.ts`·`tabStops.ts`) — helper를 둘 곳.
- `frontend/e2e/ad-consent.spec.ts:32-41` — `signIn`이 `pathname !== '/'`인 첫 착지만 기다린다(155가 실패한 시험 `:71`이 이것을 쓴다). 같은 모양의 사본은 `grep -n "dev_subject" frontend/e2e/*.ts`로 찾는다. `auth-entry.spec.ts`의 `signInThroughStub`도 그중 하나다.
- `frontend/e2e/ad-transition.spec.ts` — PR #111 수정 `e40bb22`(`about:blank`)·`8b26c59`(`waitForURL(ONBOARDING_PATH)`). `git show e40bb22 8b26c59 -- frontend/e2e`로 위치를 본다.
- `frontend/src/App.test.tsx:321` 등 운영자 lazy 경로 `findBy*`, `OperatorScreen.test.tsx`·`OperatorNotice.test.tsx`·`SettingsConnectionRevoke.test.tsx`의 첫 `findBy*`.
- `backend/src/test/java/com/bokrilog/PostgresTestcontainersConfig.java:28-64` — JVM당 컨테이너 하나(`CONTAINER.start()`). 시계 검사는 여기서 시작 직후 JDBC로 한 번.
- `backend/src/main/java/com/bokrilog/sync/internal/DashboardTrust.java:224-227` — `resultTooRecent`(DB 시각을 ms로 자른 종료 시각 < 측정 시각). 원인 가설의 근거이고 읽기 전용이다.
- `backend/src/test/java/com/bokrilog/PlanEndTest.java:60` — `Instant.now().minus(…)` fixture.
- `DEVELOPMENT.md:105-153` — `stress-test.sh`·"시험의 대기" 절 옆에 새 절을 둔다.

## Tasks & Acceptance

**Execution:**
- [x] `frontend/playwright.config.ts` — 로컬 실행별 출력 디렉터리와 보존 상한.
- [x] `frontend/e2e/support/navigation.ts`(새) — 앞 문서 내리기·최종 착지 대기·최종 착지까지 기다리는 스텁 로그인.
- [x] 로그인 사본을 쓰는 e2e 파일들과 `ad-transition.spec.ts` — helper로 교체.
- [x] `frontend/src/test/` 시한 상수와 158 대상 시험 — 명시적 lazy chunk 시한.
- [x] `backend/src/test/java/com/bokrilog/PostgresTestcontainersConfig.java`(또는 그 옆 새 클래스) — 시계 차이 검사.
- [x] `DEVELOPMENT.md` — 간헐 실패 절.

**Acceptance Criteria:**
- Given 로컬 e2e 실패가 난 뒤, when 다시 실행할 때, then 앞 실행의 trace가 남아 있다(일부러 실패하는 임시 시험으로 확인한 뒤 지운다).
- Given 시계 검사 상한을 일시적으로 0으로 낮춘 백엔드 시험, when 실행할 때, then 두 시각과 대처 문장으로 실패한다(확인 뒤 되돌린다).
- Given 바뀐 e2e, when 주입 origin 스택에서 전체를 돌릴 때, then 모두 통과한다.

## Spec Change Log

- 2026-10-17 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap) — intent_gap·bad_spec 0, patch 12, defer 0, reject 3.
  - **patch:** 재인증 복귀 대기 네 곳과 firstPlan 재인증의 금지 모양을 화면 대기 helper로, 스텁 동의 사본 넷 제거와 정식 import 경로 하나, `signInThroughStub` 근거, 착지 대기의 이름 있는 시한, 실행 표지는 worker만 물려받음·빈 last-run 변수 처리, 정리 실패는 경고만·`ENOENT`만 첫 실행·`globalSetup` 직접 시험, `DEVELOPMENT.md`의 이력 문장·보고서 덮어씀·옛 디렉터리, 158 상수 이름을 사실(첫 마운트)에 맞춤, 시계 검사의 통과 줄·방향·주입 가능한 시계와 배선 시험·주기 재측정·문서 정정.
  - **reject:** 첫 마운트 시한을 158 밖의 화면 시험으로 넓히는 것(SPEC 범위 밖 — 실패가 관측되면 그때), Code Map의 절대 worktree 경로(기존 SPEC 관례), SPEC에 판정 기준을 미리 적는 것(판정은 done 전환이 실제 검사 결과로 한다).
  - **patch 적용 중 확정:** 재인증 뒤 복귀 목적지는 재인증을 시작한 화면이 아니라 그 세션을 연 로그인의 착지라(`ReauthenticationFlow.arm`) `waitForScreen(page, landing)`으로 기다린다. 같은 모양의 `bybit-reconnect`·`bybit-connection` 재인증 대기도 함께 바꿨다.
  - **관측:** PR #123 CI(run 39039915046)에서 `ad-transition.spec.ts:152`가 155와 같은 증상(로그인 직후 미인증)으로 실패했고, 이 장치 없이도 CI artifact의 trace로 원인(동시 첫 세션 확인의 세션 속성 INSERT 충돌 500)을 찾았다. 원인 수정은 `spec-session-attribute-upsert.md`가 맡는다.
  - **KEEP:** CI 경로·`ci.yml` 불변, 재시도·전역 시한 상향 없음, 단정 의미 불변.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` — expected: 통과.
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run format:check && npm --prefix frontend run test` — expected: 통과.
- `bash frontend/scripts/stress-test.sh --runs 3 -- src/App.test.tsx src/screens/OperatorScreen.test.tsx src/screens/OperatorNotice.test.tsx src/screens/SettingsConnectionRevoke.test.tsx` — expected: 0 종료.
- `npm --prefix frontend run test:e2e`(미리보기) — expected: 통과.
- `deploy/compose/verify-same-origin.sh --keep-stack` 뒤 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 npm --prefix frontend run test:e2e:run`, 스택을 내린다 — expected: 0 종료.
- `bash .github/scripts/test-ci-scripts.sh` — expected: 통과.

## Suggested Review Order

**진단 장치**

- 로컬 실행별 출력 디렉터리와 worker만 물려받는 실행 표지
  [`playwright.config.ts`](../../frontend/playwright.config.ts)

- 최근 5회만 남기고, 지우기 실패는 경고만
  [`runOutput.ts`](../../frontend/e2e/support/runOutput.ts)

- JVM↔DB 시계 차이를 재고 방향과 함께 말하며, 5분마다 다시 잰다
  [`DatabaseClockSkew.java`](../../backend/src/test/java/com/bokrilog/DatabaseClockSkew.java)

**대기 관용**

- 착지·화면 대기 helper 하나와 정식 스텁 동의
  [`navigation.ts`](../../frontend/e2e/support/navigation.ts)

- 첫 마운트 시한 상수(158)
  [`firstMount.ts`](../../frontend/src/test/firstMount.ts)

**문서·시험**

- 간헐 실패 절
  [`DEVELOPMENT.md`](../../DEVELOPMENT.md)

- 설정·정리 시험
  [`playwright.config.test.ts`](../../frontend/playwright.config.test.ts)

- 시계 검사의 배선 시험
  [`DatabaseClockSkewTest.java`](../../backend/src/test/java/com/bokrilog/DatabaseClockSkewTest.java)
