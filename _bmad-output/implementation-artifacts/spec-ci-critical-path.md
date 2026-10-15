---
title: 'CI 임계 경로 단축: 백업 리허설 job 분리와 백업·복원 리허설의 경로 분기'
type: 'chore'
created: '2026-10-15'
status: 'done'
review_loop_iteration: 0
baseline_commit: '189c16bc57db438443b06c4e3a4f950a28c0567e'
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-BokriLog-2026-08-24/CI-PLAN.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 제품 PR의 CI wall-clock이 31분 안팎이다(PR #100: `backend-quality` 15분 → `deploy-rehearsal` 15분 26초). `deploy-rehearsal`이 배포 리허설(5~6분)과 백업 리허설(9~11분)을 한 job에서 직렬로 돌아 임계 경로가 되고, 프런트엔드만 바꾼 PR도 백업·복원 리허설을 탄다(Epic 3 회고 P-2·AI-12, 3.9 defer). 또 CLAUDE.md·DEVELOPMENT.md의 "로컬 `clean build` 3분 13초"는 회고가 잰 Windows 로컬 17~18분과 다르다.

**Approach:** 백업 리허설을 `backend-quality`만 기다리는 새 job `backup-rehearsal`로 떼어 배포·복원 리허설과 병렬로 돌린다. `classify-changes.sh`가 두 번째 출력 `backup-restore`를 내고, PR의 변경이 전부 프런트엔드·문서 경로일 때만 `false`로 백업·복원 두 리허설을 승인된 비적용으로 건너뛴다. `aggregate-results.sh`가 그 분류로 두 job의 기대치를 정한다. 로컬 build 시간 서술은 이번에 다시 잰 값으로 고친다.

## Boundaries & Constraints

**Always:**
- 분류는 fail-closed다. 건너뛰기 목록(`frontend/**`, `_bmad-output/**`, 최상위 `*.md`) 밖 경로가 하나라도 있거나, 비교 기준이 없거나, git이 실패하거나, PR이 아닌 이벤트(push·workflow_dispatch)면 `backup-restore=true`다. 관련 경로를 나열하는 허용 목록(3.9 defer 초안)은 새 경로를 조용히 건너뛰므로 쓰지 않는다.
- 두 리허설 job의 게이트는 `!= 'false'`다. 출력이 빈 값(이 출력을 모르는 base 분류기로 도는 이 PR 자신)이면 돈다. 집계도 빈 값을 `success` 기대로 읽고, `true`·`false`·빈 값 밖의 값은 `::error::`와 함께 `success` 기대다.
- PR은 지금처럼 target base의 분류기를 쓴다. 필수 검사 이름 `ci-required`, 기존 다섯 job의 이름·`needs`·단계 timeout(배포 20·백업 40·복원 30분)은 그대로다.
- 판정은 스크립트에 두고 `test-ci-scripts.sh`가 새 분류·집계·workflow 계약을 단정한다(I/O 표 전부).

**Ask First:**
- 배포 리허설이나 same-origin 통합까지 경로 분기에 넣는 것, `backend/src/test/**`를 건너뛰기 목록에 더하는 것.
- `backend-quality`의 시험 시간 자체를 줄이는 변경(병렬화·suite 배치).

**Never:**
- top-level `paths` 필터, 재시도·`continue-on-error`로 시간을 줄이는 것, 리허설 시나리오·스크립트(`deploy/production/**`) 변경.
- 기존 `docs-only` 출력의 값·사유 enum 변경.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 프런트엔드 PR | PR, `frontend/src/a.tsx`·`DEVELOPMENT.md` | `docs-only=false`, `backup-restore=false`; 백업·복원 job skipped, 집계 통과 | N/A |
| 백엔드 PR | PR, `frontend/x`·`backend/y` | `backup-restore=true`; 세 리허설 모두 success 기대 | N/A |
| 문서-only PR | PR, `_bmad-output/a.md` | `docs-only=true`; 모든 제품 job skipped(기존 그대로) | N/A |
| push·dispatch | `develop` push(프런트엔드만) | `backup-restore=true` | N/A |
| 기준 없음 | PR, `^1` 해석 실패 / 빈 diff | `backup-restore=true` | fail-closed |
| 옛 분류기 | 출력 빈 값 | 두 job 실행, 집계는 success 기대 | fail-closed |
| 분류·결과 불일치 | `false`인데 백업 job success, 또는 `true`인데 skipped | 집계 종료 1, `::error::` | 병합 차단 |

</frozen-after-approval>

## Code Map

- `.github/scripts/classify-changes.sh` -- `emit`(docs-only·reason)과 경로 `case` 루프. 같은 루프에서 두 번째 판정을 모으고 모든 조기 `emit` 지점이 `backup-restore`도 낸다.
- `.github/scripts/aggregate-results.sh` -- `check_job`이 전역 `expected` 하나를 쓴다. job별 기대치를 받게 바꾸고 `BACKUP_RESTORE`·`BACKUP_RESULT` 입력, `changes` 실패 시 판정 불가 행을 더한다.
- `.github/workflows/ci.yml:460-544` -- `deploy-rehearsal`의 `백업 스크립트 시험`·`백업 리허설` 단계와 타임라인 주석. `:546-607` `restore-rehearsal`이 새 job의 본보기(jar 하나, `needs: [changes, backend-quality]`). `:609-639` `ci-required`의 `needs`·env. `:1-35` 머리 주석의 job 그림. `:73-75` `changes` outputs.
- `.github/scripts/test-ci-scripts.sh` -- `check_aggregate`(:679, `restore_result_override`), job 목록(:1086), runs-on 개수(7), `needs`(:1110-1117), env(:1145), `assert_job_steps`(:1221-1237), docs 게이트 개수(:1253, 5), 진단 job 목록(:1399), `deploy-rehearsal` 백업 단정(:1524-1540).
- `CLAUDE.md` `## CI`, `DEVELOPMENT.md:733` 부근 -- 로컬 `clean build` 시간 서술과 CI 구조 문장.
- `_bmad-output/implementation-artifacts/deferred-work-resolutions.md` -- 3.9 경로 분기 defer의 판정 칸(PR CI 성공 뒤에만 해소로 적는다).

## Tasks & Acceptance

**Execution:**
- [x] `.github/scripts/classify-changes.sh` -- `backup-restore` 출력·요약 줄 추가, I/O 표의 규칙 구현 -- 경로 분기의 판정 소유자
- [x] `.github/scripts/aggregate-results.sh` -- `backup-rehearsal` 입력, 두 리허설 job의 분류 기반 기대치 -- 승인된 skip과 누락된 job을 구분
- [x] `.github/workflows/ci.yml` -- `backup-rehearsal` job 신설(백업 스크립트 시험·jar 내려받기·백업 리허설·진단 업로드, job timeout 50분), `deploy-rehearsal`에서 두 백업 단계 제거(job timeout 30분), 두 리허설 게이트, `ci-required` needs·env, 주석 타임라인 -- 임계 경로 단축
- [x] `.github/scripts/test-ci-scripts.sh` -- 위 세 파일의 계약 단정 갱신·추가 -- 계약 고정
- [x] `CLAUDE.md`, `DEVELOPMENT.md` -- CI job 구조와 로컬 `clean build` 실측(이번 측정값·환경 명시) 갱신 -- 회고 P-2

**Acceptance Criteria:**
- Given 이 브랜치, when `bash .github/scripts/test-ci-scripts.sh`, then 전부 통과한다.
- Given 이 PR의 CI, when 끝나면, then `ci-required` green이고 세 리허설이 모두 돈다(옛 분류기 → 빈 값 → 실행) — 백업 리허설이 배포 리허설과 겹쳐 돈 것을 job 시각으로 확인해 SPEC에 기록한다.
- Given 로컬 Docker, when `backend/gradlew --project-dir backend clean build`, then 통과하고 그 wall-clock이 문서에 실린다.

## Spec Change Log

- 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap) patch 루프: patch 11·defer 1·reject 5. patch — 세 리허설 진단 업로드 `if:` 전체 줄 단정과 deploy `id: rehearsal` 연결 단정(`(failure() || cancelled())`를 지워도 292건이 통과하던 공백), 옛 분류기 안내를 제품 변경일 때만 `::notice::`로, 문서-only + 깨진 `BACKUP_RESTORE` 종료 1 사례, 백엔드 wrapper 경로 사례, dispatch 사례 라벨, 분류기의 낡은 "frontend" 주석, DEVELOPMENT.md `restore-rehearsal` 행의 병렬 대상, 타임라인의 스크립트 시험 단계 상한(2 → 3분), 이 Design Notes의 단축량 문장, CLAUDE.md 서술 축소와 측정값 자리 채움. defer — CI-PLAN §1·§5 as-built 반영(`deferred-work.md` 146, AI-10 범위). reject — 백업 job의 `frontend/dist` 의존(리허설이 nginx를 띄우지 않고 복원 job이 이미 jar 하나로 돈다), 진단 디렉터리 생성(`rehearse-backup.sh`가 `mkdir -p`), 동결 문장 "다섯 job" 해석(새 job을 더하려면 `ci-required` needs는 바뀌어야 한다), 추적 산출물 갱신(done 전환 PR 몫), 콜드 이미지 빌드 시간(이 PR CI가 잰다).

## Design Notes

PR #100 실측(분): backend 15.0 → {same-origin 6.2, deploy 5.4 + backup 9.5, restore 5.1}. 분리 뒤 백엔드 PR의 꼬리(`backend-quality` 뒤)는 15분 26초에서 max(배포 ≈6, 백업 ≈10, 통합 ≈7, 복원 ≈5) ≈ 10분으로 약 5분 줄고, 프런트엔드 PR은 백업·복원이 빠져 꼬리가 ≈7분(통합)이 되어 약 9분 줄어든다. 새 job은 checkout·jar 내려받기 약 10초를 더 쓴다(CI-PLAN §10 비용 주의).

CI-PLAN §5가 프런트엔드-only 변경의 반대편을 "승인된 비적용"으로 허용하고 `develop` 통합 job은 전체를 요구하므로, push는 항상 `true`다.

## Verification

**Commands:**
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 실패 0
- `backend/gradlew --project-dir backend clean build` -- expected: 성공, 소요 시간 기록(Docker Desktop 필요)

## 측정

- 로컬 `clean build`(2026-10-15, Windows 11·Docker Desktop 29.7.2, 시험 1,496건): 다른 작업 없이 18분 36초, 다른 검사와 겹쳐 22분 35초. 둘 다 성공.
- `bash .github/scripts/test-ci-scripts.sh`: 299/299.
- PR #104 CI(옛 base 분류기 → `backup-restore` 빈 값 → 세 리허설 모두 실행, 둘 다 `ci-required` green). 두 실행 모두 `backup-rehearsal`이 `deploy-rehearsal`과 같은 시각에 시작해 겹쳐 돌았다(AC 2).

  | 실행 | wall-clock | `backend-quality` | 배포 | 백업 | 통합 | 복원 | `backend-quality` 뒤 꼬리 |
  |---|---|---|---|---|---|---|---|
  | PR #100(분리 전, 비교) | 30분 56초 | 14분 55초 | 단계 5분 23초 + 백업 단계 9분 27초 직렬 | — | 6분 9초 | 5분 6초 | 15분 29초 |
  | 38669922463 (`3e3606e`) | 25분 44초 | 12분 3초 | 7분 50초 | 13분 12초 | 7분 1초 | 4분 16초 | 13분 14초 |
  | 38672281349 (`9a2dd5b`, develop 반영) | 23분 21초 | 12분 43초 | 7분 40초 | 10분 14초 | 7분 41초 | 4분 41초 | 10분 16초 |

- 단독 job의 백업 리허설은 10~13분으로, 배포 리허설 뒤에 이어 돌던 때(9.5분 안팎)보다 길다 — 같은 러너에서 배포 리허설이 남긴 이미지 레이어를 더는 공유하지 않는 몫으로 보인다. 그래서 백엔드 PR의 꼬리 단축은 Design Notes 추정(약 5분)보다 작은 2~5분(15분 29초 → 13분 14초·10분 16초)이다. 프런트엔드-only PR의 건너뛰기 효과는 머지 뒤 첫 그런 PR에서 처음 측정된다.

## Suggested Review Order

**변경 분류 (판정의 출처)**

- 건너뛰기 목록 루프: `frontend/*`만 리허설을 끄고 나머지 모든 경로는 켠다
  [`classify-changes.sh:125`](../../.github/scripts/classify-changes.sh#L125)

- 승인된 비적용은 PR에만 있다. push·dispatch는 항상 true
  [`classify-changes.sh:145`](../../.github/scripts/classify-changes.sh#L145)

- 조기 종료 지점은 모두 fail-closed로 true를 낸다
  [`classify-changes.sh:85`](../../.github/scripts/classify-changes.sh#L85)

**필수 검사 집계**

- 백업·복원 분류 해석: 빈 값은 옛 분류기, 그 밖 값은 실패
  [`aggregate-results.sh:122`](../../.github/scripts/aggregate-results.sh#L122)

- 문서-only가 우선이고 제품 변경일 때만 백업·복원 기대치가 바뀐다
  [`aggregate-results.sh:149`](../../.github/scripts/aggregate-results.sh#L149)

- 두 리허설 job이 job별 기대치로 판정된다
  [`aggregate-results.sh:193`](../../.github/scripts/aggregate-results.sh#L193)

**workflow 배선**

- 새 job: backend jar만 기다려 배포 리허설과 병렬
  [`ci.yml:537`](../../.github/workflows/ci.yml#L537)

- 게이트는 `!= 'false'` — 빈 값이면 돈다
  [`ci.yml:542`](../../.github/workflows/ci.yml#L542)

- 배포 리허설은 백업 단계를 떼고 job timeout 70 → 30분
  [`ci.yml:469`](../../.github/workflows/ci.yml#L469)

- 분류 출력과 집계 입력의 연결
  [`ci.yml:688`](../../.github/workflows/ci.yml#L688)

**계약 시험과 문서**

- 분류 경계 사례(프런트엔드·백엔드·push·rename·이웃 디렉터리)
  [`test-ci-scripts.sh:476`](../../.github/scripts/test-ci-scripts.sh#L476)

- 집계의 I/O 표 사례
  [`test-ci-scripts.sh:890`](../../.github/scripts/test-ci-scripts.sh#L890)

- 진단 업로드 `if:` 전체 줄 단정
  [`test-ci-scripts.sh:1851`](../../.github/scripts/test-ci-scripts.sh#L1851)

- job 표와 로컬 build 실측
  [`DEVELOPMENT.md:731`](../../DEVELOPMENT.md#L731)
