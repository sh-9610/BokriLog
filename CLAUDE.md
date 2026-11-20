# BokriLog

Bybit 읽기 전용 연결로 복리 목표 계획을 기록·비교하는 개인 프로젝트다. 제품 코드는 `frontend/`, `backend/`, `deploy/`에 있다.

## 언어

대화와 산출 문서 모두 한국어다. BMAD 중앙 설정(`_bmad/core/config.yaml`)이 `communication_language: Korean`, `document_output_language: Korean`으로 고정되어 있으므로 이 값을 따른다. 브랜치명·커밋 subject·코드 식별자만 영문이다. 코드 주석도 한국어다 — 새로 쓰거나 고치는 주석은 한국어로 쓰고, 기존 영어 주석은 그 파일을 고칠 때 손대는 범위에서만 바꾸며 주석만 바꾸는 일괄 치환은 하지 않는다(Epic 2 회고 AI-10, 2026-10-06 결정).

## 세션 시작 시 확인 순서

[현재 진행 지점](#현재-진행-지점)을 조회하고 아래 영역 표에서 변경에 필요한 계약을 읽는다.
스토리를 시작하거나 코드를 바꾸는 요청이면 `bmad-build` 스킬로 진행한다. 질문·조사·문서 읽기는 스킬 없이 답한다.

## BMAD 스킬 실행

이 저장소의 작업은 `.claude/skills/bmad-*` 스킬로 진행한다. 각 스킬의 `SKILL.md`는 렌더링 명령 하나만 담고 있다.

- `SKILL.md`가 지시하는 `render_skill.py` 명령을 그대로 한 번 실행하고, 출력된 절대경로 `workflow.md`를 읽어 따른다.
- 렌더링이 실패하면 명령 출력을 보고하고 중단한다. `.claude/skills/` 아래의 워크플로 원본을 직접 읽어 대신 실행하지 않는다.
- `.claude/skills/`(Claude Code)와 `.agents/skills/`(Codex)는 같은 설치본에서 생성된 동일 파일이다. 스킬 파일을 손으로 고치지 않는다. 동작을 바꿀 때는 `_bmad/custom/` 또는 `bmad-customize` 스킬을 사용한다.
- `_bmad/config*.toml`과 모듈 `config.yaml`은 인스톨러가 재생성한다. 직접 수정하지 않는다.

### 스토리·변경 작업의 표준 흐름

`bmad-build`가 다음 순서를 강제한다. 단계를 건너뛰지 않는다.

1. **SPEC 작성** — `_bmad-output/implementation-artifacts/spec-<slug>.md`. 스토리는 `spec-1-3-<slug>.md`처럼 스토리 번호로 시작하고, chore·fix도 SPEC을 쓴다(예: `spec-ci-foundation.md`). SPEC의 `## Intent`·`## Boundaries & Constraints`는 사람이 소유하며 승인 뒤 고치지 않는다.
2. **CHECKPOINT 1(Intent 승인)** — 사용자 승인을 받기 전에는 구현하지 않는다. SPEC 토큰 초과 경고가 나오면 사용자는 보통 `[K] 유지`를, 도구 선택 안이 여럿이면 추천안을 고른다.
3. **구현** — 아래 `## 코드 규칙`을 지킨다. `bmad-build`가 sprint-status를 `in-progress`로 올리고 에픽을 `in-progress`로 든다.
4. **리뷰** — 리뷰 서브에이전트를 병렬로 돌리고 결과를 `patch`·`defer`·`reject`로 분류한다. `defer`는 `deferred-work.md`에 **추가만** 한다. patch 루프와 2차 리뷰의 결과는 SPEC `## Spec Change Log`에 한 줄씩 적고, `review_loop_iteration`은 리뷰 전체를 다시 돌린 2차 리뷰에만 올린다(Epic 2 회고 AI-13, 2026-10-06 결정).
5. **present** — SPEC `status: done`, sprint-status `review`. 여기서 스킬은 끝난다.
6. **PR** — 토픽 브랜치를 push하고 `develop`으로 PR을 연다. `ci-required`가 green이면 `Create a merge commit`으로 머지한다(`gh pr merge --merge`).
7. **done 전환** — 머지 뒤 `chore/sprint-status-<n>-<m>-done` 브랜치에서 sprint-status 한 줄을 `done`으로 바꾸는 docs PR을 따로 올린다(PR #3, #10 패턴). 문서-only 경로라 `changes → ci-required`만 돌고 1분 안에 끝난다. 스토리·chore·fix의 done 전환 PR(done 전환 PR이 없는 작업이면 그 작업의 PR 자체)은 세 부류의 defer 판정을 `deferred-work-resolutions.md`에 함께 적는다 — 그 작업이 `deferred-work.md`에 append한 defer, 그 작업이 해소한 defer, 그 작업을 결정 지점으로 지목한 defer(결정하지 못하고 지나갔으면 미해소·주인 상실로라도 판정). 항목별 절과 상태 표·건수를 같은 PR에서 맞추고 판정을 다음 라운드로 넘기지 않는다(Epic 1 회고 액션 8(`epic-1-retro-item-8`)·Epic 2 회고 AI-6(`epic-2-retro-item-18`)·Epic 3 회고 AI-11(`epic-3-retro-item-36`)).

토픽 브랜치 작업은 사용자 체크아웃을 옮기지 않고 별도 `git worktree`에서 하고, 머지 뒤 worktree와 브랜치를 모두 지운다.

### 커밋·푸시·머지의 자율 범위

- 커밋·푸시는 사용자가 요청할 때만 한다.
- "진행해줘"·"병합하는 데 문제가 없다면 진행해줘"처럼 명시적 진행 지시가 있고 `ci-required`가 green이며 충돌이 없으면 push → PR → 머지까지 한 번에 진행한다. 사용자는 PR 페이지에서 사후 검토한다.
- "확인해줘"류 지시면 PR까지만 열고 머지는 묻는다.
- 실패한 CI를 재시도로 덮지 않는다. 원인을 찾아 고친 커밋을 올린다.
- "묻지 말고 쭉 진행해"류 상시 위임은 SPEC `<frozen-after-approval>`(Intent·Boundaries·I/O 표)의 해석을 덮지 않는다. 그 부분이 모호하거나 현실과 어긋나 해석이 필요하면 상시 위임이 있어도 멈추고 묻는다. 상시 위임이 덮는 것은 그 밖이다: 리뷰 patch 반영, 위 조건을 갖춘 push·PR·머지, 다음 작업 착수(Epic 5 회고 AI-8, 2026-11-11 결정).
- 오래 혼자 진행할 때는 마디마다 한두 줄로 보고한다. 마디는 SPEC 승인, 구현 완료, 리뷰 분류, PR 열림, CI 결과, 머지다. 보고에는 끝난 것과 다음 것을 적고, 작업 하나가 끝나면 요약한다(Epic 5 회고 AI-8, 2026-11-11 결정).

## 코드 규칙

세 디렉터리 공통 불변식이다. 근거는 `ARCHITECTURE-SPINE.md`(1.1, `architecture-BokriLog-2026-11-16/`)의 AD-1~AD-24와 각 스토리 SPEC이다.

- **스택 고정.** Java 25 LTS, Spring Boot 4.1.x(현재 4.1.1), Spring Modulith 2.1.x(현재 2.1.1), React 19.2.x, Vite 8.2.x, Nginx 1.30.x, PostgreSQL 18.x(현재 18.6). 허용 계열은 `ARCHITECTURE-SPINE.md` Stack 표가, 실제 버전은 lockfile·wrapper·이미지 태그가 소유한다. 버전이 해결되지 않으면 조용히 낮추지 말고 보고한다.
- **오류 응답 한 가지.** `{"code":…,"message":…,"correlationId":…}`. Spring과 Nginx가 같은 모양을 돌려주고 `code`는 안정적인 machine code다. 내부 경로·스택·프레임워크 기본 오류 본문·환경 변수 이름은 응답과 로그 어디에도 남기지 않는다.
- **자격정보.** `application.yml`·compose·시험 fixture에 자격정보 모양 리터럴을 넣지 않는다. 값이 가짜여도 gitleaks가 잡는다. 탐지가 나면 규칙을 완화하지 말고 리터럴을 없앤다. 예외는 `.gitleaksignore` 한 줄과 근거 주석으로만 둔다.
- **줄바꿈.** 신규 파일은 LF다. `.gitattributes`가 결정하고 `*.bat`·`*.cmd`만 CRLF다.
- **셸 스크립트.** `set -o pipefail` 아래에서 `생산자 | grep -q`를 조건에 쓰지 않는다. grep이 첫 매치에서 끝나 생산자가 SIGPIPE를 받고 러너에서만 실패한다(PR #9 첫 실행 실패 원인). `grep … >/dev/null`로 입력 끝까지 읽는다. bash 3.2 호환, GNU 전용 플래그(`sed -i`, `grep -P`, `readlink -f`) 금지.

## 현재 진행 지점

현재 체크아웃에서 `uv run --managed-python --no-cache scripts/session-status.py`를 실행한다. [조회 절차](DEVELOPMENT.md#세션-상태-조회) 사용자 체크아웃은 `develop`에 두고, 진행·리뷰 스토리와 열린 회고 액션을 확인한다. 스토리 배경은 해당 `epic-<n>-context.md`, 과거 결정과 미검증 출시 게이트는 [구현 이력](_bmad-output/implementation-artifacts/context/history.md)에서 찾는다.

## 스토리가 고정한 계약

작업이 닿는 영역의 파일을 모두 읽고 SPEC frontmatter의 `context`에 각 경로를 `{project-root}/_bmad-output/implementation-artifacts/context/파일명.md`로 적는다. 해당 스토리 SPEC의 Design Notes와 구현에서 확정한 내용도 확인한다.

| 변경 영역 | 계약 파일 |
|---|---|
| 로그인·로그아웃·재인증·세션 | [인증과 세션](_bmad-output/implementation-artifacts/context/authentication.md) |
| identity 소유권·계정 삭제·완료 세션 | [계정 소유권과 삭제](_bmad-output/implementation-artifacts/context/deletion.md) |
| Bybit 연결·수집·계획 발행·금융 계산 | [Bybit 연결과 금융 계산](_bmad-output/implementation-artifacts/context/finance.md) |
| <a id="프런트엔드-실행"></a>프런트엔드 실행·화면·복귀 경로·브라우저 경계·디자인 토큰 | [경로와 화면](_bmad-output/implementation-artifacts/context/frontend.md) |
| <a id="백엔드동일-출처-실행"></a>백엔드 실행·모듈·의존 추가·DB 마이그레이션 | [백엔드 의존·스키마·실행](_bmad-output/implementation-artifacts/context/backend.md) |
| 운영 권한·제한·공지·문의·관측 | [운영자 표면](_bmad-output/implementation-artifacts/context/operations.md) |
| 배포·동일 출처·Nginx 헤더·백업·격리 복원 | [배포·동일 출처·백업과 복원](_bmad-output/implementation-artifacts/context/deployment.md) |
| 광고 동의·자격·표시·계측 | [광고](_bmad-output/implementation-artifacts/context/advertising.md) |
| <a id="ci"></a>시험 격리·동시성·CI 판정 | [시험과 CI](_bmad-output/implementation-artifacts/context/testing.md) |

예를 들어 계정 삭제 뒤 남는 인증 세션을 바꾸는 SPEC은 다음 두 파일을 함께 전달한다.

```yaml
context:
  - '{project-root}/_bmad-output/implementation-artifacts/context/authentication.md'
  - '{project-root}/_bmad-output/implementation-artifacts/context/deletion.md'
```

## Python 도구 실행

BMAD Python 스크립트는 저장소 루트에서 `uv run --managed-python --no-cache`로 실행한다. 이 명령은 BMAD 기획 도구 전용이며 제품 런타임과 무관하다. Windows에서 `python3`는 존재하지 않으므로 직접 호출하지 않는다. 셸 스크립트는 POSIX 셸을 요구하고 Windows에서는 Git Bash로 실행한다. 환경이 의심되면 먼저 점검한다.

```bash
./scripts/check-bmad-env.sh
```

## 산출물 위치와 권위

- `_bmad-output/planning-artifacts/` — `epics.md`(5 에픽·45 스토리 승인), `EPIC-IMPLEMENTATION-READINESS-1.1.md`(`ERD-1.1.0` — 옛 `EPIC-IMPLEMENTATION-READINESS.md`(`ERD-1.0.0`)는 superseded), `architecture/architecture-BokriLog-2026-11-16/`(`ARCHITECTURE-SPINE.md`·`PORTFOLIO-SOLUTION-DESIGN.md` 1.1과 `CI-PLAN.md` 1.4.0 — 옛 폴더 `architecture-BokriLog-2026-08-24/`의 세 파일은 superseded다), `prds/prd-BokriLog-2026-08-23/prd.md`, `ux-designs/ux-BokriLog-2026-08-23/`(`DESIGN.md`, `EXPERIENCE.md`), `briefs/`
- `_bmad-output/implementation-artifacts/` — `sprint-status.yaml`, `epic-<n>-context.md`(1~5), `epic-<n>-retro-<날짜>.md`, `spec-*.md`, `deferred-work.md`, `deferred-work-resolutions.md`, `ci-run-verdicts.md`(CI 비-green 실행 판정 원장)
- `_bmad/render/` — 스킬 렌더링 스냅샷. 생성물이며 커밋하지 않는다.

충돌 시 우선순위는 PRD의 제품 범위 → 아키텍처의 불변식(SPINE 1.1) → `ERD-1.1.0`의 하위 구현 계약 순이다. 결정을 바꿀 때는 기존 문서를 덮어쓰지 않고 새 버전으로 발행한다. `sprint-status.yaml`의 스토리 키 앞부분(`1-3`)이 브랜치 번호이고, 스토리 본문은 `epics.md`의 해당 `### Story` 절이다.

`deferred-work.md`는 `bmad-build`가 `defer` 판정마다 항목을 **추가만** 하는 로그다. 기존 항목을 고치거나 지우지 않는다. 해소 여부는 `deferred-work-resolutions.md`에 기록하고 상태 요약 표를 함께 맞춘다. 검사가 실제로 성공한 뒤에만 해소로 적는다.

## 미룬 결정

판정과 미해소 항목은 [미룬 결정 원장](_bmad-output/implementation-artifacts/deferred-work-resolutions.md)에서 확인한다.

## 브랜치·커밋

git flow를 따른다. `main`은 릴리스만 받고 직접 커밋하지 않는다. `develop`이 통합 브랜치이며 모든 토픽 브랜치는 `develop`에서 분기한다.

병합은 GitHub PR의 `Create a merge commit`으로만 한다. `develop`·`main`에 직접 push하지 않는다. `Squash and merge`·`Rebase and merge`는 토픽 브랜치 경계를 지우므로 쓰지 않으며 ruleset도 막는다. 병합 후 토픽 브랜치는 삭제한다.

- `feature/<에픽>-<스토리>-<slug>` — 스토리 구현. 예: `feature/1-3-identity-ownership`
- `chore/<slug>` — 도구·환경·저장소 정비, sprint-status `done` 전환
- `docs/<slug>` — 기획 산출물·문서
- `fix/<slug>` — `develop` 위 결함 수정
- `hotfix/<slug>`, `release/<version>` — 릴리스 이후·릴리스 준비에만 사용

slug는 짧은 영문 소문자 kebab-case다. 한글 브랜치명과 도구 이름(`claude` 등) 접두사는 쓰지 않는다. 커밋 메시지는 Conventional Commits의 영문 소문자 subject를 따르고 유형은 `feat` `fix` `chore` `docs` `refactor` `test`다.

## 주의

- BMAD 인스톨러를 다시 실행하면 `_bmad/scripts/`의 로컬 수정(UTF-8 stdout)이 업스트림 원본으로 덮어쓰인다. 절차와 복구 방법은 `DEVELOPMENT.md`의 `## 에이전트 타깃`에 있다.
- 제품 기술 스택은 `ARCHITECTURE-SPINE.md`(1.1, `architecture-BokriLog-2026-11-16/`)가 권위 기준이다(Java/Spring/PostgreSQL, React/Vite). 이 저장소의 Python 환경은 기획 도구 전용이며 제품 런타임과 무관하다.
- `.idea/`, `.claude/settings.local.json`, `compose.override.yaml`, `.env*`는 기기별 파일이라 커밋하지 않는다. 저장소가 소유하는 compose 구성은 `deploy/compose/compose.yaml` 하나다.
- 스토리 머지 때 공통 계약이 바뀌면 해당 영역 문서를 갱신한다. 진행 상태는 sprint-status 원장에, 스토리별 도입·리뷰 경위는 SPEC에 기록한다.
- `AGENTS.md`는 Codex가 읽는 진입 파일이며 이 파일을 대체하지 않는다. 규칙의 권위는 `CLAUDE.md` 하나이고 `AGENTS.md`는 오리엔테이션·실행 명령과 "제품 경로를 바꾸기 전에 `CLAUDE.md`를 읽으라"는 지시만 담는다. 규칙을 그리로 복사하지 않는다 — 두 벌이 되면 갈라지고 맞춰 줄 검사가 없다. `AGENTS.md`의 `bmad:context` 마커 안쪽은 `bmad-project-context` 스킬이 소유하며 refresh 때 교체된다.
