---
title: 'Epic 5 회고 AI-4 — CI 설치 단계 시한·비-green 실행 판정 원장·간헐 실패 계열의 주인'
type: 'chore'
created: '2026-11-10'
status: 'done'
baseline_commit: '721c3e62dbe59ad25e73fae503b087cbaa7bf398'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-retro-2026-11-09.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Epic 5 회고 P-2·P-3(`epic-5-retro-item-51`)이 남긴 문제가 셋이다.
- **설치 단계에 시한이 없다.** `Chromium headless shell 설치`(`frontend-quality`·`same-origin-integration`)는 정상이면 14~89초 걸린다. 그런데 apt가 멈춘 두 번(run 42197758258 att1, 6분 23초 · run 42231959937 att1, 5분 44초)은 job 상한에 걸려 `cancelled`로 끝났다.
- **비-green 실행을 적는 자리가 없다.** 위 둘은 재실행으로 풀렸지만 어디에도 기록되지 않았다. develop push 실패 두 건도 원장에 없고 재실행되지도 않았다 — run 42134981077의 e2e `first-snapshot`, run 42549803177의 백업 리허설 `catalog` 단계 37분 정지다.
- **간헐 실패 계열에 주인이 없다.** 159·177이 그렇다.

**Approach:** 고치는 것도 셋이다.
- **시한.** 두 설치 단계에 3분 시한을 둔다. 멈추면 `cancelled`가 아니라 이름 있는 단계의 실패로 끝난다.
- **판정 원장.** 비-green 실행을 한 행씩 적는 추가 전용 원장을 새로 두고, 위 다섯 건을 소급해 적는다.
- **주인 지정.** 159·177의 주인을 다음 fix chore(lease 상실 시험을 잠금 문 방식으로 옮기는 fix)로 정한다.

## Boundaries & Constraints

**Always:**
- **단계 시한.** 두 설치 단계에 `timeout-minutes: 3`을 둔다. 통합 job의 예산 주석에서 설치 줄을 2분 → 3분으로, 합과 job 상한을 19 → 20분으로 바꾼다. `test-ci-scripts.sh`가 두 단계의 3분과 job 20분을 단정한다(기존 19분 단정은 대체).
- **판정 원장.** `_bmad-output/implementation-artifacts/ci-run-verdicts.md`(living, 추가 전용)를 둔다.
  - 기록 대상: 코드 수정 없이 재실행으로 풀린 시도, 재실행하지 않은 채 원인이 확정되지 않은 실패(develop push 포함), 간헐 시험 실패.
  - 행의 칸: 날짜(UTC)·run/attempt·이벤트/브랜치·job→단계·결말·원인·판정(`환경`·`간헐 시험`·`미조사`)·후속(재실행 run/attempt·defer 번호·fix).
  - 커밋으로 고친 결함은 기록하지 않는다.
- **규칙 문서.** `DEVELOPMENT.md` `### 간헐 실패`에 규칙을 적는다 — 재실행하기 전에(develop 실패는 발견한 다음 PR에서) 한 행을 먼저 쓰고, 미조사 행은 defer 번호를 단다.
- **소급과 defer.** 소급 다섯 행 가운데 develop 실패 둘은 `deferred-work.md`에 새 항목으로 append한다. 진단 artifact 이름과 보존 만료를 evidence에 적는다.
- **159·177의 주인.** `deferred-work-resolutions.md` 159·177 절에 지정을 적는다 — 주인은 다음 fix chore(가칭 `spec-lease-loss-test-doors.md`, 177의 `slowPageStores`·`expireLease` 시험을 `FinalSweepDoor`식 잠금 문으로 옮김)이고, 159는 그 fix가 `PlanEndTest`를 옮긴 뒤 다시 판정한다. 상태(미해소)와 건수는 바꾸지 않는다.
- **딸린 정정.** 이 chore가 고치는 19분 예산에 붙어 있던 `DEVELOPMENT.md` 첫 줄의 표 조각 오염(`같은 스택에서 Story 1.1 회귀 | 19분 |`)을 지우고, 19분 서술(CLAUDE.md·DEVELOPMENT.md·ci.yml 주석)을 20분으로 맞춘다.

**Ask First:**
- 설치 단계에 재시도를 넣거나, 통합 job 외 다른 job 상한을 바꿔야 할 때.

**Never:**
- 시험 코드 변경(177의 시험 이전은 다음 fix 몫), Playwright·Gradle 설정 변경, 재시도로 실패를 덮는 장치, 설치 중복 제거(deferred 20), 새 job·스크립트 추가.

</frozen-after-approval>

## Code Map

- `.github/workflows/ci.yml:246-250` -- `frontend-quality`의 설치 단계(시한 없음). job 상한은 `:196`의 8분.
- `.github/workflows/ci.yml:445-469` -- 통합 job 예산 주석(`:454` 설치 2분, `:458` 합 19분, `:463` 경위)과 `:469` 상한. `:300`이 19분을 인용한다.
- `.github/workflows/ci.yml:523-524` -- 통합 job의 설치 단계(시한 없음).
- `.github/scripts/test-ci-scripts.sh:1976-1990` -- 단계·job 시한 단정의 관용(`workflow_step_block … | grep -cxF '        timeout-minutes: N'`, `count_job_lines`). `:1989-1990`이 19분 단정이다.
- `DEVELOPMENT.md:1` -- 오염된 첫 줄. `:157` `### 간헐 실패`에 규칙을 넣고, `:819`·`:802`의 19분 서술을 고친다.
- `CLAUDE.md:130` -- `## CI`의 "통합 job timeout 19분" 서술.
- `_bmad-output/implementation-artifacts/deferred-work-resolutions.md:1479`(159)·`:1735`(177) -- 주인 지정을 덧붙일 절.
- `_bmad-output/implementation-artifacts/deferred-work.md` -- 끝에 develop 실패 둘을 append한다(현재 180건).
- 소급 근거(`gh api …/attempts/N/jobs`로 확인했다):
  - 42197758258 att1 — 설치 `cancelled`. 같은 run att2는 `FinalSweep` 간헐 실패로 #146이 고쳤다.
  - 42231959937 att1 — 설치 `cancelled`. att2는 2026-11-09에 green이었다.
  - 42134981077 — develop #141 머지. `first-snapshot.spec.ts:657` 비-USDT 추정 환산 시험이 `toBeVisible` 시간초과로 실패했다. 진단 artifact `same-origin-diagnostics-42134981077-1`은 7일 보존이라 2026-11-13T00:23:43Z에 만료된다.
  - 42549803177 — develop #146 머지. 백업 리허설이 `catalog(gen_20260102)` 단계에서 멈춰 단계 상한 40분에 걸렸다. 진단 디렉터리가 비어 `backup-rehearsal-diagnostics-42549803177-1`은 올라가지 않았고(`No files were found`) job 로그만 저장소 보존 90일 동안 남는다.
- 회고 액션:
  - `epic-5-retro-item-51` — 이 chore가 닫는다.
  - `epic-5-retro-item-55`(AI-8, `DEVELOPMENT.md`의 Windows 로컬 build 한계) — 같은 파일이지만 건드리지 않고 지나간다.
  - `epic-1-retro-item-8`(원장 판정 재개) — 159·177의 주인만 적으므로 일부만 다룬다.

## Tasks & Acceptance

**Execution:**
- [x] `.github/workflows/ci.yml` -- 두 설치 단계에 `timeout-minutes: 3`과 근거 주석을 단다. 통합 job 예산 표(설치 3분·합 20분)와 상한 20, `:300`·`:463`의 서술을 고친다 -- P-2
- [x] `.github/scripts/test-ci-scripts.sh` -- 두 설치 단계 3분과 통합 job 20분을 단정한다(19분 단정 대체) -- 판정 계약 유지
- [x] `_bmad-output/implementation-artifacts/ci-run-verdicts.md` -- 원장을 만들고(목적·대상·칸·판정 어휘) 소급 다섯 행을 적는다 -- P-2 기록
- [x] `_bmad-output/implementation-artifacts/deferred-work.md` -- develop 실패 둘(e2e `first-snapshot`, 백업 리허설 `catalog` 정지)을 append한다 -- 주인 없는 실패를 원장에 올린다
- [x] `_bmad-output/implementation-artifacts/deferred-work-resolutions.md` -- 159·177 절에 주인 지정 단락을 덧붙인다 -- P-3
- [x] `DEVELOPMENT.md` -- 첫 줄 오염을 지우고, `### 간헐 실패`에 원장 규칙을 더하고, 19분 서술을 고친다 -- 규칙의 자리
- [x] `CLAUDE.md` -- `## CI`의 19분 서술을 20분으로 고친다(경위 한 구절) -- 문서 정합

**Acceptance Criteria:**
- Given 수정된 `ci.yml`, when `bash .github/scripts/test-ci-scripts.sh`를 돌리면, then 종료 0이고 새 단정 셋(설치 3분 ×2, 통합 20분)이 통과한다.
- Given 설치 단계의 시한을 지우거나 통합 job을 19분으로 되돌린 사본, when 같은 스크립트를 돌리면, then 해당 단정이 실패한다.
- Given 원장, when 읽으면, then 소급 다섯 행이 run/attempt·job→단계·판정·후속을 모두 채우고, `미조사` 행은 각자 새 defer 번호를 가리킨다.
- Given 저장소 전체, when `19분`을 찾으면, then 통합 job 상한으로서의 19분 서술이 남지 않는다(역사 서술 "17분에서 19분으로"는 예외).
- Given `deferred-work-resolutions.md`, when 159·177 절을 읽으면, then 주인(다음 fix chore)과 159의 재판정 조건이 있고 상태 요약 표는 그대로다.

## Spec Change Log

- 2026-11-10 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap). verification-gap는 0건이었다.
  - **patch 10건(반영):**
    - 원장 행 1·3의 `환경` 근거를 로그 사실로 채웠다. 멈춤이 아니라 `azure.archive.ubuntu.com`에서 글꼴 패키지를 느리게 받고 있었다.
    - 원장 행을 시작 시각 순으로 정렬했다.
    - 재실행 결말은 기존 행에 덧쓰지 않고 새 행으로 남긴다(추가 전용). PR 실패는 행 커밋이 재실행을 대신한다.
    - 기록 대상·비대상(concurrency 취소·연쇄 job)을 원장과 `DEVELOPMENT.md`에서 맞췄다.
    - 설치 시한 항목을 독립 항목으로 올리고, 표본 출처(PR #139~#152, green 8회·16건)를 적었다.
    - 159·177 계열의 새 실패는 원인이 확정되기 전에는 `미조사`로 쓰게 했다.
    - Code Map의 artifact 서술을 정정했다.
    - `CLAUDE.md`에 원장과 develop push 확인을 등록했다.
  - **defer 0건.**
  - **done 전환 PR로 넘긴 것(CLAUDE.md 절차):** 181·182 판정, 원장 건수, sprint-status, 다음 fix chore의 추적.
  - **reject:** `frontend-quality` job 상한 상향, 예산 주석 단정.
  - **부정 AC 확인:** 구현 때 시한을 지운 사본으로 확인했다 — 새 단정 셋이 실패하고 종료 1이었다.

## Design Notes

- **시한 3분.** 관측 최대 89초(PR #139~#152 기간 green run 8회의 두 job 16건)의 두 배를 넘고, apt 미러가 느렸던 두 번(5분 44초·6분 23초 — `azure.archive.ubuntu.com`에서 글꼴 패키지를 분당 약 2MB로 받았다)보다는 짧다.
- **`frontend-quality`의 job 상한 8분은 그대로 둔다.** 단계 시한은 느린 미러에 걸린 설치를 job 상한보다 먼저, 이름 있는 실패로 끊으려는 것이다. 단계 상한의 합에 맞추는 예산표는 통합 job에만 있다.
- **재시도는 넣지 않는다.** 느린 미러가 반복되면 원장 행이 쌓여 드러나고, 그때 deferred 20(설치 중복 제거)과 함께 다룬다.

## Verification

**Commands:**
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 종료 0
- `git grep -n "19분"` -- expected: 통합 job 상한으로서의 19분 서술이 없음
- `head -1 DEVELOPMENT.md` -- expected: `# 개발 환경`

## Suggested Review Order

**설치 단계 시한**

- 느린 미러에 걸린 설치를 job 취소가 아니라 이름 있는 단계 실패로 끊는다
  [`ci.yml:257`](../../.github/workflows/ci.yml#L257)

- 통합 job도 같은 3분
  [`ci.yml:537`](../../.github/workflows/ci.yml#L537)

- 예산 합과 job 상한을 20분으로 맞춘 경위
  [`ci.yml:472`](../../.github/workflows/ci.yml#L472)

**판정 원장**

- 대상·비대상·재실행 절차·판정 어휘를 소유하는 머리말
  [`ci-run-verdicts.md:13`](ci-run-verdicts.md#L13)

- 소급 다섯 행 — 환경 둘·간헐 시험 하나·미조사 둘
  [`ci-run-verdicts.md:50`](ci-run-verdicts.md#L50)

- 미조사 행이 가리키는 새 defer 181·182
  [`deferred-work.md:555`](deferred-work.md#L555)

**간헐 실패 계열의 주인**

- 159의 주인을 다음 fix chore로, 재판정 조건과 함께
  [`deferred-work-resolutions.md:1498`](deferred-work-resolutions.md#L1498)

- 177의 주인 지정
  [`deferred-work-resolutions.md:1749`](deferred-work-resolutions.md#L1749)

**규칙 문서**

- 원장을 먼저 쓰는 규칙과 develop push 확인
  [`DEVELOPMENT.md:179`](../../DEVELOPMENT.md#L179)

- 설치 시한 3분과 표본 출처
  [`DEVELOPMENT.md:186`](../../DEVELOPMENT.md#L186)

- 세션 시작 확인에 develop push 결말을 더했다
  [`CLAUDE.md:11`](../../CLAUDE.md#L11)

**시험**

- 두 설치 단계 3분과 통합 job 20분 단정
  [`test-ci-scripts.sh:1983`](../../.github/scripts/test-ci-scripts.sh#L1983)
