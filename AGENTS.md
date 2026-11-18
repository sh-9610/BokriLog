<!-- bmad:context -->
<!-- Verified 2026-09-25 against bcd1cc5. Managed by bmad-project-context; edits inside this block are replaced on refresh. Keep anything you want preserved outside the markers. -->

## BokriLog

Bybit 읽기 전용 연결로 복리 목표 계획을 기록·비교하는 개인 프로젝트다. Java 25 + Spring Boot 4.1 백엔드(`backend/`), React 19 + Vite 8 정적 SPA(`frontend/`), Nginx 동일 출처 구성(`deploy/`)으로 이뤄진다. 기획·구현 산출물은 `_bmad-output/`에 있고 대화와 산출 문서는 한국어다(브랜치명·커밋 subject·코드 식별자만 영문).

## Policy

- `backend/`·`frontend/`·`deploy/`·`.github/`·`_bmad-output/` 중 무엇이든 바꾸기 전에 `CLAUDE.md`를 읽는다. 스택 고정, 모듈 경계, 세션·인증 계약, 오류·캐시 헤더, 금융 계산, 현재 진행 지점이 전부 거기 있고 이 블록은 그것을 옮겨 적지 않는다 — 두 벌이 되면 갈라진다.
- `develop`·`main`에 직접 push하지 않는다. 토픽 브랜치를 내고 GitHub PR의 `Create a merge commit`으로만 병합한다(ruleset이 squash·rebase를 막는다). 토픽 브랜치 작업은 사용자 체크아웃을 옮기지 않고 별도 `git worktree`에서 한다.
- 커밋·푸시는 사용자가 요청할 때만 한다.
- `.claude/skills/`와 `.agents/skills/`를 손으로 고치지 않는다. 두 트리는 같은 설치본이 만든 동일 파일이며, 동작을 바꿀 때는 `_bmad/custom/` 또는 `bmad-customize` 스킬을 쓴다.
- `_bmad/config*.toml`과 모듈 `config.yaml`은 인스톨러가 재생성한다. 직접 고치지 않는다.
- 자격정보 모양 리터럴을 값이 가짜여도 넣지 않는다. gitleaks가 잡으면 규칙을 완화하지 말고 리터럴을 없앤다.

## Where things are

- 기획 산출물 `_bmad-output/planning-artifacts/` — `epics.md`, `EPIC-IMPLEMENTATION-READINESS-1.1.md`(`ERD-1.1.0`), `architecture/architecture-BokriLog-2026-11-16/`(SPINE·PSD 1.1, CI-PLAN 1.4.0)
- 구현 산출물 `_bmad-output/implementation-artifacts/` — `sprint-status.yaml`, `spec-*.md`, `deferred-work*.md`, 에픽 회고
- 절차와 명령의 상세는 `DEVELOPMENT.md`가 소유한다.

## Running and verifying

루트에 매니페스트가 없다. 모든 명령은 저장소 루트에서 접두사와 함께 실행한다.

- 프런트엔드는 `npm --prefix frontend <스크립트>`다. 스크립트 이름은 `frontend/package.json`에 있다.
- Node는 `frontend/.nvmrc`의 24다. Node 25에서는 Vitest가 내장 `localStorage` 때문에 실패하므로 버전 관리자로 맞춘다.
- 백엔드는 `backend/gradlew --project-dir backend build`다. JDK 25가 필요하고 toolchain 자동 프로비저닝은 꺼져 있으므로(`backend/gradle.properties`) 설치된 JDK를 Gradle이 찾아야 한다. PATH의 `java -version`이 낮게 나와도 정상이며 `JAVA_HOME`을 셸에서 바꿔 두지 않는다.
- **백엔드 시험은 Docker 데몬을 전제한다.** Testcontainers가 실제 `postgres:18.6-alpine`을 띄우며 인메모리 데이터베이스로 대체하지 않는다. 데몬이 없으면 `build`가 컨테이너 시작 실패로 멈춘다.
- 동일 출처 I/O 계약 전체는 `deploy/compose/verify-same-origin.sh` 하나가 본다. 두 산출물을 먼저 빌드해야 하고 종료 코드는 0 통과, 1 단정 실패, 2 준비 실패(산출물이나 docker 없음)다.
- `PLAYWRIGHT_BASE_URL`을 주면 Playwright가 자체 `preview` 대신 그 origin을 검증하고, 주입 origin에서만 도는 성공 왕복 시험이 함께 켜진다.
- BMAD Python 스크립트는 `uv run --managed-python --no-cache`로 실행한다. Windows에 `python3`는 없으므로 직접 호출하지 않는다.
- 환경 점검은 `./scripts/check-bmad-env.sh`다. 두 스킬 트리의 동일성과 `_bmad/scripts/`의 로컬 UTF-8 수정 잔존도 함께 검사한다.
- 로컬에서 CI 판정 계약을 미리 보는 명령은 `bash .github/scripts/test-ci-scripts.sh`다.

<!-- /bmad:context -->
