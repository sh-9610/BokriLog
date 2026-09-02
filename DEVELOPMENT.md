# 개발 환경

이 저장소의 BMAD 도구는 macOS 기본 `python3`를 직접 사용하지 않는다. BMAD 스크립트가 요구하는 Python 3.11 이상을 `uv`가 선택하거나 내려받아 실행한다. 제품 애플리케이션의 기술 기준선은 `ARCHITECTURE-SPINE.md`에서 Java/Spring/PostgreSQL과 React/Vite로 확정되었으며, 이 Python 환경은 제품 런타임과 무관한 기획 도구 전용이다. 제품 코드·lockfile·image digest는 아직 생성 전이다.

## 최초 준비

1. `uv`를 설치한다. macOS에서는 Homebrew의 `brew install uv`를 사용할 수 있다. 이 환경은 `uv 0.12.5`에서 검증했으며, 그보다 오래된 버전은 별도 검증이 필요하다.
2. 저장소 루트에서 아래 명령으로 환경을 검증한다.

```bash
./scripts/check-bmad-env.sh
```

점검은 다음을 확인한다.

- `uv` 명령 사용 가능 여부
- 저장소 내부 캐시 경로(`./.uv-cache`)
- `uv` 관리 Python 3.11 이상 사용 여부
- BMAD 중앙 설정 해석 가능 여부
- BMAD의 렌더링 및 메모리 로그 도구 실행 가능 여부

## BMAD 명령 실행 원칙

BMAD Python 도구는 항상 저장소 루트에서 `uv run --managed-python --no-cache`로 실행한다.

```bash
uv run --managed-python --no-cache _bmad/scripts/resolve_config.py \
  --project-root "$PWD"
```

`--managed-python`은 시스템 Python 3.9가 실수로 선택되는 것을 막는다. `--no-cache`는 BMAD의 일회성 도구 실행이 이전 캐시 상태에 의존하지 않도록 한다. 반면 패키지 설치처럼 캐시를 사용하는 일반 `uv` 명령은 `uv.toml`에 따라 저장소의 `.uv-cache`만 사용하며, 이 디렉터리는 Git에 포함되지 않는다.

## 파일 역할

- `uv.toml`: `uv` 캐시를 저장소 내부로 제한한다.
- `.codex/config.toml`: BMAD 도구의 UTF-8 출력을 Codex 작업 환경에 적용한다.
- `.gitignore`: 로컬 캐시, 가상환경, Python 바이트코드, macOS 메타데이터를 제외한다.
- `scripts/check-bmad-env.sh`: 위 설정이 실제로 동작하는지 한 번에 검증한다.

애플리케이션 기술 스택과 허용 버전은 아키텍처 문서를 권위 기준으로 삼고, 실제 설치 버전은 향후 Gradle/frontend lockfile과 image digest가 소유한다. 제품이 Python 애플리케이션이라는 전제에서 `.python-version`, `pyproject.toml`, 애플리케이션 `.venv`를 만들지 않는다.
