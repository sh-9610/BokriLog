# BokriLog

Bybit 읽기 전용 연결로 복리 목표 계획을 기록·비교하는 개인 프로젝트. 현재는 BMAD 기획 산출물만 존재하고 제품 코드는 아직 없다.

## 언어

대화와 산출 문서 모두 한국어다. BMAD 중앙 설정(`_bmad/core/config.yaml`)이 `communication_language: Korean`, `document_output_language: Korean`으로 고정되어 있으므로 이 값을 따른다.

## BMAD 스킬 실행

이 저장소의 작업은 `.claude/skills/bmad-*` 스킬로 진행한다. 각 스킬의 `SKILL.md`는 렌더링 명령 하나만 담고 있다.

- `SKILL.md`가 지시하는 `render_skill.py` 명령을 그대로 한 번 실행하고, 출력된 절대경로 `workflow.md`를 읽어 따른다.
- 렌더링이 실패하면 명령 출력을 보고하고 중단한다. `.claude/skills/` 아래의 워크플로 원본을 직접 읽어 대신 실행하지 않는다.
- `.claude/skills/`(Claude Code)와 `.agents/skills/`(Codex)는 같은 설치본에서 생성된 동일 파일이다. 스킬 파일을 손으로 고치지 않는다. 동작을 바꿀 때는 `_bmad/custom/` 또는 `bmad-customize` 스킬을 사용한다.

## Python 도구 실행

BMAD Python 스크립트는 저장소 루트에서 `uv run --managed-python --no-cache`로 실행한다. Windows에서 `python3`는 존재하지 않으므로 직접 호출하지 않는다.

```bash
uv run --managed-python --no-cache _bmad/scripts/resolve_config.py --project-root "$PWD"
```

셸 스크립트는 POSIX 셸을 요구한다. Windows에서는 Git Bash로 실행한다. 환경이 의심되면 먼저 점검한다.

```bash
./scripts/check-bmad-env.sh
```

## 산출물 위치

- `_bmad-output/planning-artifacts/` — 브리프, PRD, UX 설계, 아키텍처, `epics.md`, `EPIC-IMPLEMENTATION-READINESS.md`
- `_bmad-output/implementation-artifacts/` — SPEC, 스프린트 상태, 스토리, 리뷰, `deferred-work.md`
- `_bmad/render/` — 스킬 렌더링 스냅샷. 생성물이며 커밋하지 않는다.

## 현재 진행 지점

기획은 완료됐다. `epics.md`는 5개 에픽·45개 스토리가 승인된 상태이고 `implementationReadinessStatus: ready-for-sprint-planning`이다. 구현 계약은 `EPIC-IMPLEMENTATION-READINESS.md`의 `ERD-1.0.0`이 권위 기준이다.

다음 단계는 `bmad-sprint-planning`으로 `sprint-status.yaml`을 생성하는 것이다. 아직 생성되지 않았다.

제품 범위 충돌 시 우선순위는 PRD의 제품 범위, 아키텍처의 불변식, `ERD-1.0.0`의 하위 구현 계약 순이다. 결정을 바꿀 때는 기존 문서를 덮어쓰지 않고 새 버전으로 발행한다.

## 주의

- BMAD 인스톨러를 다시 실행하면 `_bmad/scripts/`의 로컬 수정이 업스트림 원본으로 덮어쓰인다. 절차와 복구 방법은 `DEVELOPMENT.md`에 있다.
- 제품 기술 스택은 `ARCHITECTURE-SPINE.md`가 권위 기준이다(Java/Spring/PostgreSQL, React/Vite). 이 저장소의 Python 환경은 기획 도구 전용이며 제품 런타임과 무관하다.
