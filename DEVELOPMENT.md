# 개발 환경

이 저장소의 BMAD 도구는 운영체제 기본 `python3`를 직접 사용하지 않는다. BMAD 스크립트가 요구하는 Python 3.11 이상을 `uv`가 선택하거나 내려받아 실행한다. 제품 애플리케이션의 기술 기준선은 `ARCHITECTURE-SPINE.md`에서 Java/Spring/PostgreSQL과 React/Vite로 확정되었으며, 이 Python 환경은 제품 런타임과 무관한 기획 도구 전용이다. 제품 코드·lockfile·image digest는 아직 생성 전이다.

## 최초 준비

1. `uv`를 설치한다. Windows에서는 `winget install astral-sh.uv`, macOS에서는 Homebrew의 `brew install uv`를 사용할 수 있다. 이 환경은 `uv 0.12.5`에서 검증했으며, 그보다 오래된 버전은 별도 검증이 필요하다.
2. 점검 스크립트는 POSIX 셸을 요구한다. Windows에서는 Git Bash에서 실행한다.
3. 저장소 루트에서 아래 명령으로 환경을 검증한다.

```bash
./scripts/check-bmad-env.sh
```

점검은 다음을 확인한다.

- `uv` 명령 사용 가능 여부
- 저장소 내부 캐시 경로(`./.uv-cache`)
- `uv` 관리 Python 3.11 이상 사용 여부
- BMAD 중앙 설정 해석 가능 여부
- BMAD 스크립트의 UTF-8 stdout 설정 잔존 여부
- 설치 manifest에 `codex`와 `claude-code` 타깃이 모두 등록되어 있는지
- 두 타깃의 스킬 트리가 서로 동일한지. 차이가 있으면 파일 목록·내용 차이와 줄바꿈 차이를 구분해 보고한다
- BMAD 스킬 렌더링이 두 타깃 모두에서 동작하는지
- BMAD 메모리 로그 도구 실행 가능 여부

## 두 기기 개발 (Windows·macOS)

이 저장소는 Windows와 macOS 양쪽에서 개발한다. 기기 간 차이는 저장소 파일로 고정하고 각 기기의 Git 로컬 설정에 의존하지 않는다.

줄바꿈은 `.gitattributes`가 결정한다. 텍스트 파일은 인덱스와 작업 트리 모두 LF로 통일하고, 셸 스크립트와 빌드 wrapper(`gradlew`, `mvnw`)는 LF로 명시 고정한다. `*.bat`과 `*.cmd`만 CRLF를 유지한다. `core.autocrlf`는 기기별 로컬 설정이라 공유되지 않으므로 신뢰하지 않는다. 셸 스크립트에 CR이 섞이면 bash가 `$'\r': command not found`로 즉시 실패한다.

새 기기에서 clone한 뒤에는 위 `## 최초 준비`만 수행하면 된다. `.agents/skills/`와 `.claude/skills/`가 저장소에 포함되어 있으므로 인스톨러를 다시 실행하지 않아도 점검이 통과한다.

`scripts/check-bmad-env.sh`는 bash 3.2와 호환되며 `sed -i`, `grep -P`, `readlink -f` 같은 GNU 전용 플래그를 쓰지 않는다. macOS 기본 bash에서 수정 없이 동작한다.

작업 트리에 CRLF가 섞인 파일이 남아 있다면 `.gitattributes` 도입 이전에 체크아웃된 파일이다. 인덱스는 이미 LF이므로 커밋 결과와 다른 기기의 clone에는 영향이 없다. 정리하려면 `git status`가 깨끗한 상태에서만 아래를 실행한다. 미커밋 변경이 있으면 사라진다.

```bash
git rm --cached -r . && git reset --hard
```

저장소를 두는 경로에는 비ASCII 문자를 피한다. Git 자체에는 영향이 없지만 Java·Gradle 도구가 일부 환경에서 경로 인코딩 문제를 일으킬 수 있다.

## BMAD 명령 실행 원칙

BMAD Python 도구는 항상 저장소 루트에서 `uv run --managed-python --no-cache`로 실행한다.

```bash
uv run --managed-python --no-cache _bmad/scripts/resolve_config.py \
  --project-root "$PWD"
```

`--managed-python`은 오래된 시스템 Python이 실수로 선택되는 것을 막는다. `--no-cache`는 BMAD의 일회성 도구 실행이 이전 캐시 상태에 의존하지 않도록 한다. 반면 패키지 설치처럼 캐시를 사용하는 일반 `uv` 명령은 `uv.toml`에 따라 저장소의 `.uv-cache`만 사용하며, 이 디렉터리는 Git에 포함되지 않는다.

## 에이전트 타깃

BMAD 스킬은 에이전트별 디렉터리로 설치되며, 이 저장소는 Codex와 Claude Code를 함께 사용한다. 두 디렉터리는 같은 설치본에서 생성된 동일한 파일이며 한쪽만 갱신하면 안 된다. 스킬 본문은 실행 시점에 `_bmad/render`의 불변 스냅샷으로 렌더링되므로, 두 타깃은 같은 스냅샷 하나를 공유한다.

인스톨러를 다시 실행할 때는 항상 두 타깃을 함께 지정한다.

```bash
npx bmad-method@6.11.0 install \
  --directory . --action update --modules bmm --tools codex,claude-code \
  --user-name "SSAFY" --communication-language "Korean" \
  --document-output-language "Korean" --output-folder "_bmad-output" \
  --set bmm.user_skill_level=intermediate \
  --set 'bmm.planning_artifacts={project-root}/_bmad-output/planning-artifacts' \
  --set 'bmm.implementation_artifacts={project-root}/_bmad-output/implementation-artifacts' \
  --set 'bmm.project_knowledge={project-root}/docs' \
  --yes
```

기존 설치가 있으면 `--action`은 `update` 또는 `quick-update`만 받는다. `install`은 거부된다.

인스톨러는 `_bmad/scripts/`의 로컬 수정을 `.bak`로 백업한 뒤 업스트림 원본으로 덮어쓴다. 이 저장소는 `resolve_config.py`에 stdout을 UTF-8로 재설정하는 수정을 유지하고 있는데, 업스트림에는 그 코드가 없어 한글 산출물이 Windows 기본 콘솔 인코딩에서 깨질 수 있다. 따라서 인스톨러 실행 후에는 반드시 다음을 수행한다.

1. `git diff _bmad/scripts/` 로 사라진 수정을 확인한다.
2. 필요한 수정을 되돌린다: `git checkout -- _bmad/scripts/resolve_config.py`
3. `.bak` 파일을 삭제한다.
4. `./scripts/check-bmad-env.sh` 를 다시 실행한다. 5번 항목이 이 수정의 잔존을 검사한다.

## 파일 역할

- `uv.toml`: `uv` 캐시를 저장소 내부로 제한한다.
- `.codex/config.toml`: BMAD 도구의 UTF-8 출력을 Codex 작업 환경에 적용한다.
- `.agents/skills/`: Codex가 읽는 BMAD 스킬. 인스톨러가 생성하며 커밋한다.
- `.claude/skills/`: Claude Code가 읽는 BMAD 스킬. 같은 설치본에서 생성하며 커밋한다.
- `CLAUDE.md`: Claude Code 세션에 프로젝트 규칙과 현재 진행 지점을 전달한다.
- `_bmad/`: BMAD 설치본과 설정. `_bmad/config*.toml`과 모듈 `config.yaml`은 인스톨러가 재생성하므로 직접 수정하지 않고 `_bmad/custom/`을 사용한다.
- `_bmad/render/`: 스킬 렌더링 스냅샷. 생성물이며 Git에 포함되지 않는다.
- `_bmad-output/`: 기획·구현 산출물 정본.
- `.gitattributes`: 줄바꿈 규칙을 저장소에 고정한다. Windows·macOS가 같은 바이트를 보게 한다.
- `.gitignore`: 로컬 캐시, 가상환경, Python 바이트코드, 두 운영체제의 메타데이터, IDE 로컬 상태와 빌드 산출물을 제외한다.
- `scripts/check-bmad-env.sh`: 위 설정이 실제로 동작하는지 한 번에 검증한다.

애플리케이션 기술 스택과 허용 버전은 아키텍처 문서를 권위 기준으로 삼고, 실제 설치 버전은 향후 Gradle/frontend lockfile과 image digest가 소유한다. 제품이 Python 애플리케이션이라는 전제에서 `.python-version`, `pyproject.toml`, 애플리케이션 `.venv`를 만들지 않는다.
