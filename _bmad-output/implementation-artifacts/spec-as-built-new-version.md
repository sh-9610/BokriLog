---
title: 'as-built 새 버전 — ARCHITECTURE-SPINE·PSD 1.1과 ERD-1.1.0'
type: 'chore'
created: '2026-11-16'
status: 'done'
baseline_commit: 'e78d59202150dbba7b9469b9df4485f444af084e'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** ARCHITECTURE-SPINE·PSD·ERD-1.0.0과 기획 문구(epics.md·EXPERIENCE.md)가 Epic 1~5에서 구현한 실제 모습(as-built)과 20여 곳에서 다르다. 회고 넷(Epic 2 item-19·Epic 3 item-35·Epic 4 item-44·Epic 5 item-52)과 Epic 1 item-9가 이것을 새 버전으로 반영하라고 남겼다. 지금은 문서를 읽는 사람이 각 SPEC과 CLAUDE.md를 따로 대조해야 실제 계약을 알 수 있다.

**Approach:** 새 폴더 `architecture-BokriLog-2026-11-16/`에 SPINE·PSD 전체 사본을 as-built로 고쳐 1.1로 발행한다. ERD는 새 파일 `ERD-1.1.0`으로 발행한다. 옛 파일은 frontmatter에 superseded 표지만 더한다. 끝난 스토리의 기획 문장(epics.md·EXPERIENCE.md)은 고치지 않고 바로 아래에 as-built 주석 한 줄을 덧붙인다. CI-PLAN은 deferred 185로 분리했다(사용자 결정 2026-11-16).

## Boundaries & Constraints

**Always:**
- 새 문서의 as-built 문장은 모두 코드·migration·스크립트·SPEC에서 확인한 사실만 쓴다. 바뀐 AD·절마다 근거(파일 또는 SPEC)를 문서 끝 `## 1.0 → 1.1 변경 기록`에 한 줄씩 남기고, 그 줄에 회고 출처(예: E3 R-3)를 붙인다.
- AD 번호·ERD 절 번호·정책 버전 이름(`CALC-1.0` 등)을 유지한다. 코드 주석 44곳이 이 이름을 가리킨다. 루트 패키지는 새 AD-24로 더한다. 바뀐 AD는 제목 표지 `[ADOPTED]`를 `[ADOPTED · 1.1 개정]`으로 바꾼다.
- 옛 문서는 frontmatter의 `status: superseded`와 `superseded_by` 두 줄만 바꾸거나 더한다. 본문은 한 글자도 바꾸지 않는다.
- 기획 문구 주석의 형식은 `> as-built(ERD-1.1.0 §n): …` 한 줄이다. 원래 문장은 그대로 둔다.
- 결정되지 않은 것(G-4·G-5 보존 기한·해제 경로, item-53 묶음)은 "미결정 — 주인 G-5/G-4"로만 적고 값을 지어내지 않는다.

**Ask First:**
- 확인한 as-built가 회고에 적힌 차이와 다르거나, 반영하려면 기존 결정(PRD 범위·AD 불변식)을 바꿔야 하는 경우.
- 광고를 닫은 뒤 대시보드 제목으로 포커스를 옮긴다는 요구를 deferred 구현 항목으로 남기는 것 말고, 다른 처분(요구 철회 등)이 필요해 보이는 경우.

**Never:**
- 코드·스크립트·시험·migration·동결된 SPEC 본문을 바꾸지 않는다. 3.8·3.10의 동결 문장은 새 버전 문서에만 반영한다.
- CI-PLAN, PRD, DESIGN.md를 바꾸지 않는다. 회고 액션이 아닌 새 차이를 찾아 넓히지 않는다(발견하면 deferred에 append만 한다).
- epics.md frontmatter의 `externalLaunchGates`를 바꾸지 않는다(item-10·34의 몫).

</frozen-after-approval>

## Code Map

- `_bmad-output/planning-artifacts/architecture/architecture-BokriLog-2026-08-24/ARCHITECTURE-SPINE.md` — 원본. 고칠 곳: AD-1·Consistency(entity·repository → `JdbcClient`), AD-12:124(일회성 역할 가운데 `deploy-audit`·`backup-record` 둘은 Flyway 없음, `deletion-sample`·`tombstone-export`·`restore-apply`는 검증만), AD-13:130(Terraform → 같은 호스트 compose·`manual`·격리 복원), AD-14:136(`/health/live` → `GET /api/status`, k6 없음), AD-16:148(`sync_run`·`LOST_LEASE` → 작업 표 여섯과 종단 code, `UNIQUE(provider_account_id, source_id)`·`CONFLICT`), AD-18:160(adapter는 브라우저 `ads/gptAdapter.ts`, 서버는 활성 판정, reservation 시점, 4.5 kill switch), AD-19:166(상태 identity V30·V31, 실행자 루트 `AccountErasure*` 단계·단일 모드, tombstone 키 역할 제한), Structural Seed:222(루트 패키지·`deploy/production`·`observability`, terraform·alloy 없음), Capability Map:279, Deferred:301(outbox 문장 — V25 tombstone outbox는 용도가 다르다).
- `.../PORTFOLIO-SOLUTION-DESIGN.md` — §3 모듈 표:43(실제 표: migration V1~V33의 CREATE TABLE, `frequency_cap` 없음, 루트 `action_grant`), §8 배포:194~214(curl smoke, 관리 포트 tag 확인, 파일 잠금, 실제 기록 상태 — `deploy/production/deploy.sh`), §9:218(같은 호스트 복원·RPO `dump_started_at`), §10:236~246, §11:250~260.
- `_bmad-output/planning-artifacts/EPIC-IMPLEMENTATION-READINESS.md` — ERD-1.0.0. §5:116(비회전 예외·`delete-account` 입력 문구 hash), §8:153~160(reservation 시점·이동 대상 `/dashboard`·방지 속성 범위·SHOW 뒤 관문은 소비 시점), engagement 데이터 계약 절 신설, §9:162~167(tombstone V25·V32 필드·상태·보존식·`CLOSURE_SLACK`·V24 판정·`OVERDUE`·V33 재가입 차단·완료 세션·전체 완료, 외부 사본 24시간+행 필터).
- `_bmad-output/planning-artifacts/epics.md` — 주석 자리: :3312(4.2 결정 상태), :3346(4.3 AC-3), :3424(5.1 AC-2 고정 문구). frontmatter `implementationDecisionVersion`·`inputDocuments`.
- `_bmad-output/planning-artifacts/ux-designs/ux-BokriLog-2026-08-23/EXPERIENCE.md:204` — 닫기 뒤 포커스. as-built 미구현.
- 근거: 각 SPEC(`spec-3-8`·`3-10`·`4-2`~`4-5`·`5-1`~`5-4`)의 Design Notes, CLAUDE.md `## 스토리가 고정한 계약`, `backend/src/main/resources/db/migration/`.
- 포인터 갱신: `CLAUDE.md:50,146,150,156,336`, `AGENTS.md:19`.
- 걸린 열린 회고 액션:
  - `epic-2-retro-item-19`(주제): 닫는다.
  - `epic-4-retro-item-44`(주제): 닫는다.
  - `epic-5-retro-item-52`(주제): 닫는다.
  - `epic-1-retro-item-9`(이름 — SPINE 모듈 지도): AD-24로 닫는다.
  - `epic-3-retro-item-35`(주제): CI-PLAN 부분을 deferred 185로 넘기고 나머지를 닫는다. 판정은 부분 해소다.
  - `epic-1-retro-item-7`(주제 — ERD §5 확인 hash): 지나간다. Epic 1·2 hash 정정 결정은 남는다.
  - `epic-5-retro-item-53`(주제 — ERD §9 G-5): 지나간다. 미결정으로만 표기한다.
  - `epic-1-retro-item-10`·`epic-3-retro-item-34`(이름 — epics.md 게이트): 지나간다.
  - `epic-3-retro-item-33`(이름 — deferred-work.md): 지나간다.
  - `epic-1-retro-item-8`·`epic-3-retro-item-38`(과정 — defer 판정): done 전환에서 185·신규 defer를 판정한다.

## Tasks & Acceptance

**Execution:**
- [x] `.../architecture-BokriLog-2026-11-16/ARCHITECTURE-SPINE.md` — 원본을 복사해 위 AD·Seed·Map·Deferred를 고치고, AD-24(루트 패키지: 인증 표면·민감 command 공통 부품·`action_grant`·삭제 실행자·운영 port 구현)를 더하고, 변경 기록을 단다. frontmatter는 version 1.1, `supersedes`, CI-PLAN companion은 옛 폴더를 가리킨다.
- [x] `.../architecture-BokriLog-2026-11-16/PORTFOLIO-SOLUTION-DESIGN.md` — 같은 방식으로 1.1을 발행한다.
- [x] `_bmad-output/planning-artifacts/EPIC-IMPLEMENTATION-READINESS-1.1.md` — ERD-1.1.0. 절 번호를 유지하고 §5·§8·§9를 고치고, engagement 데이터 계약은 §8.1로 더한다.
- [x] 옛 SPINE·PSD·ERD — frontmatter 표지만 고친다.
- [x] `epics.md`·`EXPERIENCE.md` — 주석 넷을 달고 frontmatter 포인터를 고친다.
- [x] `deferred-work.md` — 광고 닫기 뒤 대시보드 제목 포커스 구현을 append한다.
- [x] `CLAUDE.md`·`AGENTS.md` — 문서 포인터를 1.1로 바꾼다.

**Acceptance Criteria:**
- Given 새 SPINE에서, when AD 제목을 세면, then AD-1~AD-24가 모두 있다. 회고가 지목한 AD-12·13·14·16·18·19와 Consistency가 개정 표지와 변경 기록 줄을 가진다.
- Given 회고 R-항목(E2 A-3·R-4·R-7, E3 R-2·R-3의 CI-PLAN 외, E4 R-1·R-3, E5 R-2·R-3·R-6·A-4), when 변경 기록을 대조하면, then 항목마다 반영 위치가 하나 이상 있다.
- Given 옛 문서 셋, when `git diff`를 보면, then frontmatter 밖의 변경이 0줄이다.
- Given 새 문서의 상대 링크와 CLAUDE.md·AGENTS.md 포인터, when 경로를 확인하면, then 모두 존재하는 파일을 가리킨다.

## Spec Change Log

- 2026-11-16 구현: Code Map의 AD-12 "일회성 역할 다섯은 Flyway 없음"은 사실과 달랐다. Flyway를 끄는 역할은 `deploy-audit`·`backup-record` 둘이다(`application.yml` 역할 프로필 주석, 회고 E3 R-3). 구현은 사실대로 적었고, Code Map 줄만 고쳤다. frozen 블록은 바꾸지 않았다.
- 2026-11-16 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap) patch 루프, 15건:
  - 루트 소유 표 서술 통일.
  - `RestrictionTargets`를 port 구현 목록에서 뺐다.
  - AD-16 `heartbeat_at` 표별 정정.
  - PSD §4 괄호 문장 정정.
  - AD-14·PSD §10·Images·AD-15에 deferred 185·187 주석을 달았다.
  - SPINE 1.1과 CLAUDE.md:64의 ERD 참조를 1.1.0으로 바꿨다.
  - epics.md:157에 1.1 대체 주석을 달았다.
  - ERD §9 `requested_at` 옛 행 예외를 적었다.
  - EXPERIENCE 주석을 들여썼다.
  - ERD-1.1 frontmatter에 `version`을 더했다.
  - AD-19에 실행 종단 상태를 적었다.
  - AD-24의 시점 수치를 변경 기록으로 옮겼다.
  - PSD §8 배포 단계를 `deploy.sh`와 맞췄다.
  - 변경 기록을 맞췄다.
  - ERD 참조를 바꾼 곳은 AD-7이 아니라 AD-8이었다.
  - PSD §8 단계를 맞추다 `deploy.sh` `on_signal`의 `INTERRUPTED SIGNAL`이 `DeployAuditApi.Step`에 없다는 코드 결함을 찾았다(신호 중단이 원장에 남지 않는다). deferred 189로 남겼다.
  - defer 1건은 deferred 188(epics.md AR·남은 AC 주석)이다. reject는 CI-PLAN 포인터(SPEC Never, 185가 소유), AGENTS.md 마커 안쪽(refresh가 같은 경로로 재생성), resolutions·건수·sprint-status(done 전환 PR 몫), DEVELOPMENT.md §2.5(개정되지 않은 절)다.

## Design Notes

AD 번호를 유지해야 코드 주석이 `ARCHITECTURE-SPINE.md`라는 이름으로 계속 뜻이 통한다. 경로가 바뀌므로 정본을 찾는 길은 CLAUDE.md와 옛 문서의 `superseded_by` 둘이 맡는다. 예시 주석:

```markdown
**AC-3:** … 닫으면 binding된 동일 공식 결과로 이동한다.
> as-built(ERD-1.1.0 §8): 모든 결말은 `/dashboard`(그 시점의 현재 포인터, 저장된 최신 결과)로 replace한다.
```

## Verification

**Commands:**
- `grep -c "^### AD-" <새 SPINE>` — expected: 24
- `git diff develop -- <옛 문서 셋>` — expected: frontmatter 줄만 바뀐다.
- `bash .github/scripts/test-ci-scripts.sh` — expected: 통과(문서-only 분류 확인).

**Manual checks:**
- 변경 기록의 줄마다 근거 파일을 열어 as-built 문장과 대조한다.

## Suggested Review Order

**루트 패키지와 모듈 경계 (SPINE 1.1)**

- 진입점: 회고 항목마다 바꾼 위치와 근거를 한 표로 모은 변경 기록
  [`ARCHITECTURE-SPINE.md:346`](../planning-artifacts/architecture/architecture-BokriLog-2026-11-16/ARCHITECTURE-SPINE.md#L346)

- 루트 패키지를 새 AD로 인정 — 인증 표면·민감 command·삭제 실행자·port 구현
  [`ARCHITECTURE-SPINE.md:210`](../planning-artifacts/architecture/architecture-BokriLog-2026-11-16/ARCHITECTURE-SPINE.md#L210)

- 상태는 identity, 실행자는 루트로 나눈 삭제 orchestration과 종단 상태
  [`ARCHITECTURE-SPINE.md:179`](../planning-artifacts/architecture/architecture-BokriLog-2026-11-16/ARCHITECTURE-SPINE.md#L179)

- sync_run 대신 작업 표 여섯, source_id 유일성과 CONFLICT
  [`ARCHITECTURE-SPINE.md:158`](../planning-artifacts/architecture/architecture-BokriLog-2026-11-16/ARCHITECTURE-SPINE.md#L158)

- 브라우저 adapter·서버 활성 판정·reservation 시점·성능 kill switch
  [`ARCHITECTURE-SPINE.md:172`](../planning-artifacts/architecture/architecture-BokriLog-2026-11-16/ARCHITECTURE-SPINE.md#L172)

- Flyway를 끄는 일회성 역할 둘과 검증만 하는 역할 셋
  [`ARCHITECTURE-SPINE.md:127`](../planning-artifacts/architecture/architecture-BokriLog-2026-11-16/ARCHITECTURE-SPINE.md#L127)

**구현 계약 (ERD-1.1.0)**

- tombstone 필드·상태·보존식·CLOSURE_SLACK·재가입 차단·완료 세션
  [`EPIC-IMPLEMENTATION-READINESS-1.1.md:190`](../planning-artifacts/EPIC-IMPLEMENTATION-READINESS-1.1.md#L190)

- 광고 reservation 시점·이동 대상·관문 시점, 그리고 신설 데이터 계약
  [`EPIC-IMPLEMENTATION-READINESS-1.1.md:161`](../planning-artifacts/EPIC-IMPLEMENTATION-READINESS-1.1.md#L161)
  [`EPIC-IMPLEMENTATION-READINESS-1.1.md:176`](../planning-artifacts/EPIC-IMPLEMENTATION-READINESS-1.1.md#L176)

- delete-account 비회전 예외와 고정 문구
  [`EPIC-IMPLEMENTATION-READINESS-1.1.md:119`](../planning-artifacts/EPIC-IMPLEMENTATION-READINESS-1.1.md#L119)

**상세 설계 (PSD 1.1)**

- 실제 표 기준 모듈 표 — frequency_cap 없음, 루트 행
  [`PORTFOLIO-SOLUTION-DESIGN.md:46`](../planning-artifacts/architecture/architecture-BokriLog-2026-11-16/PORTFOLIO-SOLUTION-DESIGN.md#L46)

- curl smoke·파일 잠금·실제 감사 단계 여덟
  [`PORTFOLIO-SOLUTION-DESIGN.md:206`](../planning-artifacts/architecture/architecture-BokriLog-2026-11-16/PORTFOLIO-SOLUTION-DESIGN.md#L206)

**기획 문구 주석과 포인터**

- 끝난 스토리 문장 아래에 as-built 한 줄
  [`epics.md:3314`](../planning-artifacts/epics.md#L3314)
  [`epics.md:3349`](../planning-artifacts/epics.md#L3349)
  [`epics.md:3428`](../planning-artifacts/epics.md#L3428)
  [`EXPERIENCE.md:205`](../planning-artifacts/ux-designs/ux-BokriLog-2026-08-23/EXPERIENCE.md#L205)

- 옛 문서는 superseded 표지 두 줄만
  [`ARCHITECTURE-SPINE.md:8`](../planning-artifacts/architecture/architecture-BokriLog-2026-08-24/ARCHITECTURE-SPINE.md#L8)
  [`EPIC-IMPLEMENTATION-READINESS.md:3`](../planning-artifacts/EPIC-IMPLEMENTATION-READINESS.md#L3)

- 세션 시작 문서가 1.1을 가리킨다
  [`CLAUDE.md:50`](../../CLAUDE.md#L50)

- 분리·발견 항목 185~189
  [`deferred-work.md`](deferred-work.md)
