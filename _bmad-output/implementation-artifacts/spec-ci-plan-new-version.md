---
title: 'CI-PLAN as-built 새 버전 1.4.0과 job timeout 실측 보정'
type: 'chore'
created: '2026-11-18'
status: 'done'
scope_choice: 'K' # 2026-11-18 CHECKPOINT 1 — 토큰 초과(약 8,400자)를 [K] 유지로 승인(epic-3-retro-item-38 규약)
baseline_commit: '7d220a39e089bd5bc325045b43216613b29be0a9'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** CI-PLAN 1.3.0(2026-09-05)은 frontend-only 단일 job 시절의 문서다. 지금의 9-job 구조, `backup-restore` 분류, jar digest 대조, 리허설 셋, 재실행 원장이 문서에 없다. 반대로 문서에는 있지만 구현되지 않은 계획(Release Gate·`release-validation`·WebKit·ARM64·SBOM·서명·k6)이 구현된 것처럼 읽히고, timeout 값도 실제와 다르다. SPINE·PSD 1.1이 이 차이를 deferred 185로 가리키고 있다(146 잔여 포함).

**Approach:** SPINE 1.1과 같은 폴더(`architecture-BokriLog-2026-11-16/`)에 CI-PLAN 1.4.0 전체 사본을 as-built로 고쳐 발행하고, 옛 1.3.0에는 superseded 표지만 단다. 미구현 계획은 계약을 남긴 채 "미구현"과 주인을 표시한다(사용자 결정 1-a). timeout은 실측 표와 보정 규칙·권장값만 문서에 적고 `ci.yml`은 바꾸지 않는다. 적용은 deferred로 남긴다(사용자 결정 2-a).

## Boundaries & Constraints

**Always:**
- 절 번호 §1~§18과 결정 이름 CI-D1~CI-D6을 유지한다. `ci.yml`과 `.github/scripts/*.sh` 주석이 "CI-PLAN.md §1·§5·§6·§8·§11"을 인용한다. 바뀐 절은 제목에 `[1.4 개정]`을 달고, 문서 끝 `## 1.3 → 1.4 변경 기록`에 근거(파일:줄 또는 SPEC)를 한 줄씩 남긴다.
- as-built 문장은 `ci.yml`·스크립트·`playwright.config.ts`·`build.gradle.kts`·SPEC·원장에서 확인한 사실만 쓴다.
- 미구현 항목에는 `미구현 — 주인 deferred <번호>`를 단다. 주인 항목이 없으면 `deferred-work.md`에 새 항목을 append하고 그 번호를 단다.
- 실측 표의 출처는 2026-11-04~11-17 develop push 성공 실행(PR #133 fork 분할 이후, job별 n=18~19)의 GitHub job API 시작·완료 차이다. 표에는 n·최소·중앙값·최댓값·지금 timeout을 싣는다.
- 옛 문서는 frontmatter의 `status: superseded`와 `superseded_by` 두 줄만 바꾸거나 더한다.

**Ask First:**
- as-built를 적으려면 계획의 결정(CI-D1~D6의 원칙, 보안 경계)을 철회하거나 약화해야 하는 경우.
- 구현이 문서보다 약한 보안 경계(예: CI-D5 aggregator 재조회 부재)를 "결정 변경"으로 적을지 "미구현"으로 적을지 판단이 갈리는 경우. 기본값은 "미구현"이다.

**Never:**
- `.github/**`, `frontend/**`, `backend/**`, `deploy/**`를 바꾸지 않는다. 이 PR은 문서-only 경로여야 한다.
- 옛 CI-PLAN 본문을 고치지 않는다. 미구현 계획을 삭제하거나 출시 게이트 문서로 옮기지 않는다.
- 새 CI 차이를 찾아 범위를 넓히지 않는다. 발견하면 deferred에 append만 한다.

</frozen-after-approval>

## Code Map

- `_bmad-output/planning-artifacts/architecture/architecture-BokriLog-2026-08-24/CI-PLAN.md` -- 원본 1.3.0(495줄). 고칠 곳: frontmatter(source·companions가 ERD-1.0.0·옛 SPINE을 가리킴), §1:27-48(단일 job·3-job 그래프 → 9-job, peak runner), §3:65-99(기준선·실측 → 풀스택·새 실측 표), §4 CI-D2:122-142(세 경로는 하나의 그래프, Release Gate 미구현), CI-D5:156-160(`merge_sha` output·aggregator 재조회 미구현, `fetch-depth: 0`), §5:166-197(`docs-only`·`reason` 4종·`backup-restore`, 문서-only 정의, 반대편 비적용 미구현, `release-validation` 미구현), §6:199-240(단계는 `frontend-quality`, Vitest 4분, `globalTimeout` 180/420초, dist 1일 보관), §7(WebKit 미구현), §8:255-279(리허설 셋·`backend-assemble`·jar SHA-256 대조·fork 분할·timeout 실제값·SAST 미구현), §10(측정 장소·원장), §11(gitleaks 세부·SAST·dependency·container 미구현), §12(전체 미구현, `deploy/production/release.env.example`·`preflight.sh`가 소비자), §13(develop·main 같은 ruleset), §14(bmad-contract 미구현), §15·§16(소유권 표 갱신).
- 근거: `.github/workflows/ci.yml`(job 9개, timeout `:89,196,243,257,262,319,396,479,537,585,654,721,773`), `.github/scripts/classify-changes.sh:18-30,65-79,106,129-148`, `aggregate-results.sh:118-170,214-267`, `verify-merge-commit.sh:28-43`, `test-ci-scripts.sh:1577,1701-1717`, `frontend/playwright.config.ts:96-127`, `backend/build.gradle.kts:170-190,244-256`, `ci-run-verdicts.md`, `spec-ci-*.md` 다섯.
- 미구현의 주인: 125(ARM64·SBOM·cosign·GHCR — release-validation·evidence·k6도 여기에 묶는다), 13(WebKit), 16(bmad-contract), 21(backend 정적분석), 165(소비 job jar 대조), 164(리허설 PR 경로), 20(통합 job 설치 중복), 19(Re-run failed 불가). 주인 없는 것: CI-D5 재조회·`merge_sha` 전달, 반대편 비적용 분류, dependency·license·container scan, Actions 사용량 예산 → 새 deferred.
- 포인터 갱신: `ARCHITECTURE-SPINE.md`·`PORTFOLIO-SOLUTION-DESIGN.md`(1.1) frontmatter companions와 SPINE:28 문장, `CLAUDE.md` `## CI`·`## 산출물 위치와 권위`, `AGENTS.md:19`, `DEVELOPMENT.md:812-851`(CI-PLAN 경로, `:845` `backend-quality` 15분 → 실제 20분).
- 걸린 열린 회고 액션:
  - `epic-1-retro-item-8`(과정 — defer 판정): done 전환에서 185·146과 새 defer를 판정한다. 일부만 다룬다.
  - `epic-3-retro-item-38`(과정 — [K] 기록·patch 건수): [K]를 고르면 frontmatter에 남기고 Change Log에 건수를 적는다. 일부만 다룬다.
  - `epic-3-retro-item-31`(주제 — 셸 규약 검사): 스크립트를 바꾸지 않으므로 지나간다.

## Tasks & Acceptance

**Execution:**
- [x] `.../architecture-BokriLog-2026-11-16/CI-PLAN.md` -- 원본을 복사해 위 절을 as-built로 고치고 실측 표·timeout 보정 규칙·권장값(§3·§10)과 변경 기록을 단다. frontmatter는 version 1.4.0, `supersedes`, source·companions를 1.1·ERD-1.1.0으로 -- 정본 발행.
- [x] 옛 `CI-PLAN.md` -- frontmatter 표지 두 줄만 고친다 -- 경로 안내.
- [x] `deferred-work.md` -- 주인 없는 미구현 묶음과 timeout 권장값 적용을 append한다 -- 미구현의 주인.
- [x] SPINE·PSD 1.1, `CLAUDE.md`, `AGENTS.md`, `DEVELOPMENT.md` -- 포인터와 위 timeout 진술을 맞춘다 -- 정본을 찾는 길.

**Acceptance Criteria:**
- Given 새 CI-PLAN, when `## ` 절 제목을 세면, then §1~§18이 원본과 같은 번호·순서로 있고 바뀐 절에 `[1.4 개정]`과 변경 기록 줄이 있다.
- Given 새 CI-PLAN의 job 이름과 timeout, when `ci.yml`과 대조하면, then 9개 job과 job·단계 timeout이 모두 일치한다.
- Given 원본의 미구현 계획(세 경로·release-validation·WebKit·ARM64·SBOM·서명·evidence·k6·CI-D5 재조회·반대편 비적용·SAST·dependency·container scan·bmad-contract·사용량 예산), when 새 문서를 읽으면, then 항목마다 `미구현 — 주인 deferred <번호>`가 있고 그 번호가 `deferred-work.md`에 있다.
- Given 옛 CI-PLAN, when `git diff`를 보면, then frontmatter 밖 변경이 0줄이다.
- Given 이 PR, when CI가 분류하면, then `docs-only=true`다.

## Spec Change Log

- 2026-11-18 구현: Design Notes의 예시(restore 15·frontend 9)를 재현하려고 "범위 밖이면 범위 안의 가장 큰 5분 배수, 없으면 상한"을 고르는 규칙을 §10에 명시했다. frozen 블록은 바꾸지 않았다.
- 2026-11-18 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap) patch 루프 — patch 17·defer 0·reject 4:
  - **timeout 규칙 보강.** 단계 timeout 합 + 2 하한, 내리는 값의 절대 하한 5분, 최댓값을 쓰는 이유, 적용 전 재측정, 최댓값이 timeout ÷ 1.5를 넘으면 재보정. 권장값은 `changes` 5 유지·`frontend-quality` 13·`backend-assemble` 5로 바뀌었다(209가 205를 보정).
  - **사실 정정.** 통합 job 단계 timeout 서술, 임계 경로를 추론으로 표시, 문서-only push는 두 job, Gradle lockfile 부재 단서(§9·§10), n 표기, mermaid `needs` 실선, 줄 번호 기준 커밋, DEVELOPMENT.md 경위 중복.
  - **재현 절차.** 측정 명령·필터·계산식을 §3에 적었다.
  - **주인 정리(Code Map 정정).** Code Map이 125에 묶으라던 release 경로(release-validation·release PR 검증·수동 재생성·CD·privileged workflow·cache 격리·k6·environment reviewer)는 125의 원래 범위를 넘어 새 207로 옮겼다. `main` 대상 PR의 리허설 건너뛰기(206)와 CI-D2 목표 재설정(208)을 append했다. SPINE·PSD 1.1 본문의 185 포인터를 125·207과 "CI-PLAN 1.4 §12"로 바꿨다.
  - **사고 하나.** patch 중 치환이 머리 문단의 같은 제목 문자열을 잡아 새 CI-PLAN이 잘렸고 전체를 다시 썼다. 다시 쓴 뒤 §2·§18·CI-D1·CI-D3이 원본과 바이트 단위로 같음, §1~§18 순서, timeout 표와 `ci.yml` 일치, 미구현 번호의 원장 존재를 다시 확인했다.
  - **reject.** CLAUDE.md 머리 문단·현재 진행 지점·"200건"(done 전환 몫), SPEC Code Map 줄 목록 보완(검증은 끝남), deferred 13을 WebKit 주인에서 빼자는 제안(결정이 먼저다), SPEC Verification에 기계 명령 추가(수동 대조로 확인함).

## Design Notes

timeout 보정 규칙(권장값 계산): `권장 = ceil(최댓값 × 1.5)`분을 하한으로, `ceil(최댓값 × 2.5)`분을 상한으로 두고, 지금 값이 그 범위 밖일 때만 권장값을 적는다. 정상 실행의 꼬리를 흡수하면서도 멈춘 실행을 빨리 끊는 폭이다. 예: `restore-rehearsal` 최댓값 6.7분 → 범위 11~17분, 지금 40분은 상한 밖이므로 권장 15분. `frontend-quality` 5.8분 → 9~15분, 지금 8분은 하한 밖이므로 권장 9분. 단계 timeout은 job 값보다 작아야 하므로 권장값을 적용하는 deferred 항목에 단계 값 조정도 함께 적는다.

## Verification

**Commands:**
- `git diff develop --stat` -- expected: `_bmad-output/**`와 최상위 `*.md`만 바뀐다.
- `git diff develop -- <옛 CI-PLAN>` -- expected: frontmatter 줄만 바뀐다.
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 통과.

**Manual checks:**
- 새 문서의 job·timeout 진술을 `ci.yml`에서 하나씩 찾는다. 상대 링크와 포인터가 존재하는 파일을 가리킨다.

## Suggested Review Order

**job 구조와 판정 (CI-PLAN 1.4 §1·§4)**

- 진입점: 절마다 바꾼 위치와 근거를 모은 변경 기록
  [`CI-PLAN.md:703`](../planning-artifacts/architecture/architecture-BokriLog-2026-11-16/CI-PLAN.md#L703)

- 9-job 그래프 — assemble 분리, 리허설 셋, digest 대조를 실선 needs로
  [`CI-PLAN.md:39`](../planning-artifacts/architecture/architecture-BokriLog-2026-11-16/CI-PLAN.md#L39)

- 세 실행 경로는 하나의 그래프, 목표 초과의 주인 208
  [`CI-PLAN.md:178`](../planning-artifacts/architecture/architecture-BokriLog-2026-11-16/CI-PLAN.md#L178)

- aggregator 판정 as-built와 재조회 미구현(201)
  [`CI-PLAN.md:219`](../planning-artifacts/architecture/architecture-BokriLog-2026-11-16/CI-PLAN.md#L219)

**실측과 timeout 보정 (§3·§10)**

- 재현 가능한 측정 명령·필터·계산식
  [`CI-PLAN.md:108`](../planning-artifacts/architecture/architecture-BokriLog-2026-11-16/CI-PLAN.md#L108)

- 보정 규칙 — 범위·단계 합·절대 하한·재보정 조건
  [`CI-PLAN.md:473`](../planning-artifacts/architecture/architecture-BokriLog-2026-11-16/CI-PLAN.md#L473)

- 권장값 표 — ci.yml은 그대로, 적용은 205·209
  [`CI-PLAN.md:479`](../planning-artifacts/architecture/architecture-BokriLog-2026-11-16/CI-PLAN.md#L479)

**미구현 계획과 주인 (§12 외)**

- Release Gate 전체가 미구현 — 생산은 125, release 경로는 207
  [`CI-PLAN.md:518`](../planning-artifacts/architecture/architecture-BokriLog-2026-11-16/CI-PLAN.md#L518)

- 새 주인 항목 201~209 (append만)
  [`deferred-work.md:614`](deferred-work.md#L614)

**포인터**

- 옛 1.3.0은 frontmatter 두 줄만
  [`CI-PLAN.md:7`](../planning-artifacts/architecture/architecture-BokriLog-2026-08-24/CI-PLAN.md#L7)

- SPINE 1.1이 같은 폴더의 1.4를 정본으로, ARM64·k6 주인은 125·207
  [`ARCHITECTURE-SPINE.md:28`](../planning-artifacts/architecture/architecture-BokriLog-2026-11-16/ARCHITECTURE-SPINE.md#L28)
  [`ARCHITECTURE-SPINE.md:148`](../planning-artifacts/architecture/architecture-BokriLog-2026-11-16/ARCHITECTURE-SPINE.md#L148)

- 세션 문서의 정본 경로와 timeout 진술
  [`CLAUDE.md:132`](https://github.com/sh-9610/BokriLog/blob/35dfcf9c4901017c9d0050d125ec2a3b2203b0ca/CLAUDE.md#L132)
  [`DEVELOPMENT.md:845`](../../DEVELOPMENT.md#L845)
