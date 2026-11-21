---
title: 'CI 비-green 실행 판정 원장'
status: living
created: '2026-11-10'
updated: '2026-11-17'
owner_spec: '_bmad-output/implementation-artifacts/spec-ci-flaky-run-verdicts.md'
---

# CI 비-green 실행 판정 원장

GitHub Actions(`.github/workflows/ci.yml`)에서 green이 아니었던 시도를 한 행씩 적는 추가 전용 원장이다(Epic 5 회고 AI-4, `epic-5-retro-item-51`). 재실행으로 풀린 실패와 원인을 모른 채 지나간 실패가 어디에도 남지 않아, 같은 실패가 되풀이되는지 볼 수 없었던 것을 막는다.

## 규칙

- **추가만 한다.** 기존 행을 고치거나 지우지 않는다. 판정이 바뀌면(미조사 → 원인 확정) 그 경위는 행이 가리키는 defer 번호의 `deferred-work-resolutions.md` 절에 적고, 같은 실패가 다시 나면 새 행을 더한다.
- **행 하나는 green이 아닌 시도 하나다.** 재실행의 결말은 그 행에 덧쓰지 않는다. 재실행이 다시 green이 아니면 새 행을 더한다. 후속 칸에는 기록 시점에 아는 것만 쓴다(소급 행은 이미 아는 결말을 써도 된다).
- **정렬.** 소급 행은 시도 시작 시각 순이다. 그 뒤의 행은 기록한 순서로 끝에 더한다.
- **기록 대상.**
  - 코드 수정 없이 재실행으로 풀린 시도
  - 재실행하지 않은 채 원인이 확정되지 않은 실패 — `develop` push 실패를 포함한다
  - 간헐 시험 실패(시험 구조의 경합·시간 의존으로 난 실패) — 그 뒤 fix가 고쳤어도 그 실패 자체는 적는다
- **기록하지 않는 것.**
  - 커밋으로 고친 결함(제품·시험·스크립트의 실제 결함을 고친 커밋이 다음 실행을 green으로 만든 경우). 원인과 재검증 결과는 일반 PR의 설명 또는 해당 SPEC의 Change Log에 남긴다.
  - 같은 PR의 새 push로 `ci.yml` `concurrency`의 `cancel-in-progress`가 취소한 run.
  - 원인 job의 실패 때문에 뒤따라 취소·실패한 job과 집계(`ci-required`). 원인 job의 행 하나로 적는다.
- **언제 쓰는가.**
  - **PR 실패.** 재실행하기 전에 행을 쓰고 그 PR 브랜치에 커밋해 push한다. 그 push가 새 run을 띄우므로 재실행을 대신한다 — 따로 `Re-run all jobs`를 누르지 않는다.
  - **`develop` 실패.** 발견한 다음 PR에 행을 싣는다. 재실행하지 않았어도 쓴다.
  - 절차의 상세는 `DEVELOPMENT.md` `### 간헐 실패`가 소유한다.
- **`미조사` 행은 defer 번호를 단다.** 원인을 확정하지 못한 실패는 `deferred-work.md`에 항목을 append하고 그 번호를 후속 칸에 적는다. 기존 defer가 그 실패를 이미 소유하면(예: 159·177) 그 번호를 단다. 진단 artifact가 있으면 이름과 보존 만료를 defer evidence에 적는다 — artifact는 보존 기한(`ci.yml`의 `retention-days`, 진단은 7일)이 지나면 사라지고 job 로그(저장소 보존 90일)만 남는다.

## 칸

| 칸 | 내용 |
|---|---|
| 날짜(UTC) | 그 시도가 시작된 날 |
| run/attempt | `databaseId`/`run_attempt` |
| 이벤트/브랜치 | `pull_request`·`push`와 head 브랜치(PR 번호 또는 머지한 PR) |
| job → 단계 | green이 아니었던 원인 job과 그 안의 단계 이름(`ci.yml`의 `name` 그대로) |
| 결말 | `failure`·`cancelled`와 걸린 시간·상한 |
| 원인 | 로그에서 확인한 사실. 추정은 추정이라고 쓴다 |
| 판정 | 아래 어휘 셋 중 하나 |
| 후속 | 재실행 run/attempt와 그 결말, defer 번호, 고친 fix(PR·SPEC). 기록 시점에 아는 것만 |

**판정 어휘.**
- `환경` — 러너·외부 미러·네트워크처럼 저장소 코드와 무관한 원인이 로그로 확인됐다.
- `간헐 시험` — 시험 구조(시간 기반 순서·경합)가 원인으로 확인됐다. 같은 계열의 defer나 고친 fix를 후속에 적는다.
- `미조사` — 원인을 확정하지 못했다. 후속에 defer 번호가 반드시 있다.

## 행

| 날짜(UTC) | run/attempt | 이벤트/브랜치 | job → 단계 | 결말 | 원인 | 판정 | 후속 |
|---|---|---|---|---|---|---|---|
| 2026-11-06 | 42134981077/1 | `push` `develop`(PR #141 머지 `f95ebb5`) | `same-origin-integration` → `Story 1.1 회귀 (Nginx origin)` | `failure` — 1건 실패·72건 통과(3.8분) | `first-snapshot.spec.ts:657`(비-USDT 추정 환산·보류 목록) 시험이 `connectAndSavePlan`(`support/firstPlan.ts:60`)에서 첫 확인 성공 문구를 본 뒤 첫 값 `1,234.56 USD`를 기본 5초 안에 찾지 못했다. 원인은 확정하지 않았다 | `미조사` | 재실행하지 않았다. 다음 develop push(42142542381, PR #142)는 green. deferred 181 |
| 2026-11-06 | 42197758258/1 | `pull_request` `feature/5-4-deletion-completion`(PR #145) | `frontend-quality` → `Chromium headless shell 설치` | `cancelled` — 단계 6분 23초, job 상한 8분 | 느린 apt 미러. `--with-deps`가 `azure.archive.ubuntu.com`에서 글꼴 패키지를 받는 중이었다 — `fonts-freefont-ttf`(5.6MB)에 2분 28초, `fonts-unifont`(3.0MB)에 1분 19초, 다음 `fonts-wqy-zenhei`를 받던 중 job 상한에 취소됐다(정상 설치는 단계 전체 14~89초). 단계 시한이 없어 job 전체가 취소됐다 | `환경` | 재실행 42197758258/2(같은 날)는 다른 원인으로 실패했다(다음 행). 단계 시한 3분은 `spec-ci-flaky-run-verdicts.md` |
| 2026-11-06 | 42197758258/2 | `pull_request` `feature/5-4-deletion-completion`(PR #145) | `backend-quality` → `Gradle 빌드` | `failure` | `PlanVerificationTest.aContinuationReclaimedInItsFinalSweepPublishesOnceAtItsTcFinal` 실패 — `pg_sleep` 지연 trigger + `expireLease`의 시간 기반 순서로 마지막 점검 중 lease 상실을 만드는 구조가 holder보다 늦었다 | `간헐 시험` | fix PR #146(`spec-plan-verification-final-sweep-race.md`, advisory lock 잠금 문). 같은 계열의 남은 시험은 deferred 177. PR #145는 develop 병합 뒤 green으로 머지했다 |
| 2026-11-06 | 42231959937/1 | `pull_request` `fix/plan-verification-final-sweep-race`(PR #146) | `frontend-quality` → `Chromium headless shell 설치` | `cancelled` — 단계 5분 44초, job 상한 8분 | 느린 apt 미러. `azure.archive.ubuntu.com`에서 `fonts-freefont-ttf`에 2분 32초, 다음 `fonts-unifont`를 받던 중 job 상한에 취소됐다(분당 약 2MB). 단계 시한이 없어 job 전체가 취소됐다 | `환경` | 재실행 42231959937/2(2026-11-09, `Re-run all jobs`) green. 단계 시한 3분은 `spec-ci-flaky-run-verdicts.md` |
| 2026-11-09 | 42549803177/1 | `push` `develop`(PR #146 머지 `2441364`) | `backup-rehearsal` → `백업 리허설` | `failure` — 단계 상한 40분 | 리허설 출력이 `3. 단계 실패 catalog(gen_20260102)` 머리(02:18:10) 뒤 아무것도 내지 않은 채 37분 멈췄고 단계 상한에 끊겼다. 진단 디렉터리가 비어 있어 `backup-rehearsal-diagnostics-42549803177-1`은 올라가지 않았다(`No files were found`). 원인은 확정하지 않았다 | `미조사` | 재실행하지 않았다. 다음 develop push(42563422690, PR #145)는 green. deferred 182 |
| 2026-11-17 | 44069265209/1 | `pull_request` `chore/g5-deletion-policy-defaults`(PR #167) | `same-origin-integration` → `Story 1.1 회귀 (Nginx origin)` | `failure` — 1건 실패·73건 통과(4.5분) | `auth-entry.spec.ts:1215`(설정 화면 Vitals beacon 204) 시험이 15초 동안 `/api/engagement/telemetry` 요청을 하나도 보지 못했다(trace에 telemetry POST 0건, 콘솔 오류 없음, 화면은 정상 렌더). `web-vitals` 6.2.3 `onLCP`는 첫 신뢰된 입력에서 관측을 끊고 확정하는데, LCP 항목을 받기 전이면(값 −1) 아무것도 보고하지 않는다 — 시험이 제목이 보인 직후 클릭해 부하에서 첫 LCP 항목보다 클릭이 앞섰다. 같은 브랜치 로컬 주입 origin 8회는 모두 통과 | `간헐 시험` | 같은 PR의 커밋이 시험을 "LCP 항목이 관측된 뒤 클릭"으로 고쳤다(로컬 10회 통과). 재실행하지 않았다 |
