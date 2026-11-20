---
title: '미룬 결정 원장 판정 라운드 — 미판정 91건 판정과 done 전환의 정기 판정 규칙'
type: 'chore'
created: '2026-10-15'
status: 'done'
review_loop_iteration: 0
baseline_commit: '189c16bc57db438443b06c4e3a4f950a28c0567e'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/deferred-work-resolutions.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `deferred-work.md`는 지금 **144건**인데 원장(`deferred-work-resolutions.md`)은 51~82·84~117·120~144의 **91건**을 판정하지 않았고, 상태 표와 `CLAUDE.md`는 "142건·미판정 89"로 틀려 있다(운영 command chore의 141·142를 세지 않았고 PR #101이 2.17 항목을 141·142로 잘못 적었다). 판정이 멈춘 것이 Epic 1 회고 #8·Epic 2 #18·Epic 3 #36이 세 번 요구한 일이다.

**Approach:** 91건을 지금 코드·시험·문서 근거로 판정해 원장에 항목별 절을 더하고 상태 표·건수를 바로잡는다. 다른 세션의 CI chore와 겹치는 항목(92·129·131)은 판정하지 않고 보류 표시만 한다. 그리고 done 전환 PR이 그 스토리의 defer를 판정하는 규칙을 `CLAUDE.md` 표준 흐름에 적는다.

## Boundaries & Constraints

**Always:**
- 판정은 넷이다 — **해소**(지금 코드에서 문제가 없고, 그것을 지키는 시험·검사·문서 경로를 `file:line` 또는 시험 이름으로 적는다; 시험이 CI에서 도는지 확인한다), **부분 해소**(해결된 부분과 남은 부분을 함께 적는다), **미해소**(아직 그대로; 남은 이유와 결정 지점·주인 — 스토리·게이트·"주인 없음" — 를 적는다), **미판정**(보류 항목만).
- "고쳤을 것" 추정은 해소가 아니다. 근거가 약하면 부분 해소·미해소로 적는다.
- 항목별 절은 기존 형식(`### <번호>. <제목>` + `**판정(2026-10-15, 판정 라운드).**` 본문)을 따르고 번호 순서로 끼운다. 기존 판정 절(1~50·83·118·119)과 지난 갱신 문단은 고치지 않는다 — 틀린 수치는 새 갱신 문단이 정정한다.
- 상태 표는 144건 기준으로 다시 센다(해소·부분 해소·미해소·미판정의 합 = 144).
- 출시 전 닫아야 할 항목(보안·삭제·데이터 정확성)은 갱신 문단에 따로 목록으로 둔다.

**Ask First:**
- 판정 중 제품 코드·시험을 고쳐야 해소되는 항목이 보여도 고치지 않는다 — 목록으로 모아 보고한다.
- 회고 액션(#8·#18·#36) 상태 변경과 `sprint-status.yaml` 수정.
- `_bmad/custom/`로 `bmad-build` 단계 자체를 바꾸는 것.

**Never:**
- `deferred-work.md` 항목 수정·삭제·재번호. 새 defer 추가는 이 chore의 리뷰가 낼 때만.
- 제품 코드·`ci.yml`·스크립트 변경.
- 무거운 로컬 스택 실행(`verify-same-origin.sh`·리허설) — 다른 세션과 Docker를 나눈다. 필요한 시험은 Gradle·Vitest 단위로만.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 고쳐진 항목 | 뒤 스토리가 구현(예: 104·105 → PR #73) | 해소 + 코드·시험 근거 | 시험이 없으면 부분 해소 |
| 그대로인 항목 | 외부 게이트·후속 스토리 몫 | 미해소 + 결정 지점 | 주인이 없으면 "주인 없음" 명시 |
| 두 항목이 같은 원인 | 같은 수정이 둘을 닫음 | 각 절에 판정, 서로 참조 | — |
| CI chore와 겹침 | 92·129·131 | 미판정(보류: CI chore) 표시 | CI chore 머지 뒤 판정 |
| 항목 전제가 사라짐 | 설계가 바뀌어 문제가 성립하지 않음 | 해소(전제 소멸) + 바뀐 근거 | — |

</frozen-after-approval>


## Code Map

- `_bmad-output/implementation-artifacts/deferred-work.md` -- 기존 항목은 읽기 전용. 항목 번호 = `- source_spec` 등장 순서(1~145). 51~82: Epic 1 후반·Epic 2 전반, 84~117: Epic 2 후반·공통 chore·Epic 3 전반, 120~144: Epic 3 후반·chore·2.17(143·144). 145는 이 chore의 리뷰가 append한 defer(원장 건수 대조 검사).
- `_bmad-output/implementation-artifacts/deferred-work-resolutions.md` -- 상태 표 `## 상태 요약`, 갱신 문단(최신이 위, 이 chore의 것은 "2026-10-15 갱신(판정 라운드)"), 항목별 판정 `## 항목별 판정`(1~44)·`## 갱신 규칙`·`## 항목별 판정(계속)`(45~145). 형식 견본: 83·118.
- `_bmad-output/implementation-artifacts/spec-*.md` -- 각 항목의 `source_spec`. 뒤 스토리의 `## Spec Change Log`·Design Notes가 해소 단서다.
- `CLAUDE.md` -- `## 미룬 결정`(건수·표 수치), `### 스토리·변경 작업의 표준 흐름` 7번(done 전환).
- `backend/src/test/**`·`frontend/src/**/*.test.tsx`·`frontend/e2e/**`·`.github/scripts/test-ci-scripts.sh` -- 해소 근거 시험.

## Tasks & Acceptance

**Execution:**
- [x] `deferred-work-resolutions.md` -- 91건 가운데 88건의 항목별 판정 절을 번호 순서로 더하고, 92·129·131은 보류 절(한 줄 이유)을 둔다 -- 원장 공백 해소. 서브에이전트 묶음: 51~82 / 84~117 / 120~144.
- [x] `deferred-work-resolutions.md` -- 상태 표 재집계(145건, 절마다 마지막 판정 머리말 기준), `updated`, 맨 위 갱신 문단(건수 정정 경위, 판정 분포, 보류 셋, 출시 전 닫을 항목 목록, 고쳐야 해소되는 항목 목록) -- 수치 정정.
- [x] `CLAUDE.md` -- `## 미룬 결정`의 건수·표 수치, 표준 흐름 7번에 done 전환 PR의 판정 규칙(append한 defer·해소한 defer·결정 지점으로 지목된 defer) -- 재발 방지(Epic 1 회고 액션 8(`epic-1-retro-item-8`)·Epic 2 회고 AI-6(`epic-2-retro-item-18`)·Epic 3 회고 AI-11(`epic-3-retro-item-36`)).
- [x] `deferred-work.md`·`deferred-work-resolutions.md` -- 리뷰 defer 145를 append하고 같은 PR에서 판정 -- 새 규칙의 첫 적용.

**Acceptance Criteria:**
- Given 원장, when 절마다 마지막 판정 머리말로 상태 표를 센다, then 네 칸의 합이 145이고 미판정은 3(보류 셋)이다.
- Given 해소로 적힌 절, when 근거 경로를 연다, then 그 파일·시험이 존재하고 항목의 문제를 실제로 다룬다.
- Given `CLAUDE.md`, when 표준 흐름 7번을 읽는다, then done 전환 PR의 판정 규칙이 있다.

## Verification

**Commands:**
- `grep -c '^- source_spec' _bmad-output/implementation-artifacts/deferred-work.md` -- expected: 145
- `grep -oE '^### [0-9]+\.' _bmad-output/implementation-artifacts/deferred-work-resolutions.md | tr -dc '0-9\n' | awk '$1!=NR{exit 1} END{print NR}'` -- expected: 145, exit 0(1부터 빈틈·뒤바뀜 없이 연속)
- 절마다 마지막 판정 머리말(`**해소…`·`**부분 해소…`·`**미해소…`·`**미판정…`, `**미해소 잔여:**` 제외)을 센 값 -- expected: 상태 표 네 칸(해소·부분 해소·미해소·미판정)과 같다
- `git status --short` -- expected: 원장·`deferred-work.md`·`CLAUDE.md`·이 SPEC만(SPEC은 untracked라 `git diff --stat`에는 나오지 않는다)

**Manual checks:**
- 해소 절마다 근거 경로가 실제로 있는지 표본 확인(리뷰 verification-gap 렌즈가 전수 확인).

## Spec Change Log

- 2026-10-15 리뷰 patch(3갈래): `CLAUDE.md` 7번 판정 규칙의 대상(스토리·chore·fix의 done 전환 PR)·세 부류로 확대와 회고 표기 통일(AI-n·id 병기), `## 미룬 결정`의 회고 액션 상태 중립화와 고정 앵커, 원장 `## 갱신 규칙` 2026-10-15 두 줄·`## 항목별 판정(계속)` 제목·보류 판정 주체(AI-12 chore의 done 전환 PR)·117 (나) 분류 정정, 35(부분 해소)·37(재확인) 새 판정, 해소 기준 일관성으로 78·104를 부분 해소로(57은 실경로 근거 `PlanEndTest`로 바꿔 해소 유지), 67·115·116 인용 정정, 갱신 문단 분류 범위·124·63·원장 밖 차단 요인 정정, defer 145 append·판정, 새 판정 머리말에 기준 `189c16b`, 상태 표 145건(25·20·97·3).

## Suggested Review Order

**라운드 결과 요약**

- 144→145건 정정 경위·판정 분포·보류 셋·출시 전 목록이 한 문단에 모인다
  [`deferred-work-resolutions.md:25`](deferred-work-resolutions.md#L25)

- 상태 표 — 절마다 마지막 판정 머리말 기준 25·20·97·3
  [`deferred-work-resolutions.md:16`](deferred-work-resolutions.md#L16)

- 출시 전 닫아야 할 항목과 원장 밖 차단 요인 참조
  [`deferred-work-resolutions.md:31`](deferred-work-resolutions.md#L31)

**재발 방지 규칙**

- done 전환 PR이 세 부류(append·해소·지목)의 defer를 판정한다
  [`CLAUDE.md:35`](https://github.com/sh-9610/BokriLog/blob/d2922b5354cdda69345d56f5b8fbc60559c93c6b/CLAUDE.md#L35)

- 원장 갱신 규칙에 판정 시점의 정본과 (가)·(나) 목록을 덧붙임
  [`deferred-work-resolutions.md:408`](deferred-work-resolutions.md#L408)

- 35는 규칙이 들어가 부분 해소, 기계 검사 잔여는 145가 소유
  [`deferred-work-resolutions.md:319`](deferred-work-resolutions.md#L319)

**판정 표본 (리뷰가 다시 본 절)**

- 57 — 실제 세션 id로 소비하는 계획 종료 왕복으로 근거를 바꿈
  [`deferred-work-resolutions.md:492`](deferred-work-resolutions.md#L492)

- 67 — 카탈로그 시험이 모듈 술어를 부르지 않는 사실대로 부분 해소
  [`deferred-work-resolutions.md:550`](deferred-work-resolutions.md#L550)

- 92 — CI chore와 겹쳐 보류, 판정 주체는 그 chore의 done 전환 PR
  [`deferred-work-resolutions.md:698`](deferred-work-resolutions.md#L698)

**주변**

- 새 defer 145(건수 대조 기계 검사)와 같은 PR의 판정
  [`deferred-work.md:446`](deferred-work.md#L446)
  [`deferred-work-resolutions.md:1008`](deferred-work-resolutions.md#L1008)

- `## 미룬 결정` 건수·분포·회고 액션 확인 대기 문장
  [`CLAUDE.md:264`](https://github.com/sh-9610/BokriLog/blob/d2922b5354cdda69345d56f5b8fbc60559c93c6b/CLAUDE.md#L264)
