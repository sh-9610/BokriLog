#!/usr/bin/env bash

set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$project_root"

fail() {
  printf '실패: %s\n' "$1" >&2
  exit 1
}

command -v uv >/dev/null 2>&1 || fail "uv가 설치되어 있지 않습니다."

required_paths=(
  ".agents/skills"
  ".agents/skills/bmad-build/workflow.md"
  ".claude/skills"
  ".claude/skills/bmad-build/workflow.md"
  ".codex/config.toml"
  "_bmad/_config/manifest.yaml"
  "_bmad/scripts/resolve_config.py"
  "_bmad/scripts/render_skill.py"
  "_bmad/scripts/memlog.py"
  "uv.toml"
)

for required_path in "${required_paths[@]}"; do
  [[ -e "$required_path" ]] || fail "필수 경로가 없습니다: $required_path"
done

printf '[1/9] uv 확인: '
uv --version

cache_dir="$(uv cache dir)"
if [[ "$cache_dir" == /* ]]; then
  cache_path="${cache_dir%/}"
else
  cache_path="$project_root/${cache_dir#./}"
  cache_path="${cache_path%/}"
fi
[[ "$cache_path" == "$project_root/.uv-cache" ]] || fail "uv 캐시 경로가 ./.uv-cache가 아닙니다: $cache_dir"
printf '[2/9] 프로젝트 로컬 캐시 확인: %s\n' "$cache_dir"

python_summary="$(
  uv run --managed-python --no-cache python -c \
    'import sys; assert sys.version_info >= (3, 11), sys.version; print(f"Python {sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro} | {sys.executable}")'
)"
printf '[3/9] uv 관리 Python 확인: %s\n' "$python_summary"

config_summary="$(
  uv run --managed-python --no-cache _bmad/scripts/resolve_config.py \
    --project-root "$project_root" \
    --key core.communication_language \
    --key core.document_output_language
)"
if ! printf '%s' "$config_summary" | uv run --managed-python --no-cache python -c \
  'import json, sys; data = json.load(sys.stdin); expected = {"core.communication_language": "Korean", "core.document_output_language": "Korean"}; raise SystemExit(0 if data == expected else 1)'; then
  fail "BMAD 중앙 언어 설정이 예상값과 다릅니다."
fi
printf '[4/9] BMAD 중앙 설정 확인:\n%s\n' "$config_summary"

# 인스톨러 재실행은 이 저장소의 로컬 패치를 조용히 되돌린다. 한글 출력 경로를 지킨다.
for patched_script in _bmad/scripts/resolve_config.py _bmad/scripts/render_skill.py; do
  grep -F -- 'reconfigure(encoding="utf-8")' "$patched_script" >/dev/null \
    || fail "$patched_script 의 UTF-8 stdout 설정이 사라졌습니다. DEVELOPMENT.md의 인스톨러 재실행 절차를 확인하세요."
done
printf '[5/9] 한글 출력 보호 확인: resolve_config.py, render_skill.py 정상\n'

for ide in codex claude-code; do
  grep -E -- "^[[:space:]]*-[[:space:]]*${ide}[[:space:]]*$" _bmad/_config/manifest.yaml >/dev/null \
    || fail "BMAD 설치 manifest의 ides에 $ide 가 없습니다."
done
printf '[6/9] IDE 타깃 확인: codex, claude-code 등록됨\n'

if ! parity_report="$(diff -r -q -x __pycache__ -- .agents/skills .claude/skills 2>&1)"; then
  fail "codex와 claude-code 스킬 트리가 어긋났습니다. 인스톨러를 두 타깃 모두로 다시 실행하세요.
$parity_report"
fi
printf '[7/9] 스킬 트리 동기 확인: .agents/skills 와 .claude/skills 동일\n'

for skill_root in .agents/skills/bmad-build .claude/skills/bmad-build; do
  if ! render_summary="$(
    uv run --managed-python --no-cache _bmad/scripts/render_skill.py \
      --project-root "$project_root" \
      --skill "$project_root/$skill_root"
  )"; then
    fail "BMAD 스킬 렌더링 명령 실행에 실패했습니다: $skill_root"
  fi
  rendered_workflow="${render_summary#read and follow }"
  [[ "$rendered_workflow" != "$render_summary" && -s "$rendered_workflow" ]] \
    || fail "BMAD 스킬 렌더링 연기 테스트에 실패했습니다: $skill_root"
done
printf '[8/9] BMAD 스킬 렌더링 확인: 두 타깃 모두 bmad-build 정상\n'

temp_workspace="$(mktemp -d "${TMPDIR:-/tmp}/bokrilog-bmad-check.XXXXXX")"
cleanup() {
  rm -r -- "$temp_workspace"
}
trap cleanup EXIT

uv run --managed-python --no-cache _bmad/scripts/memlog.py init \
  --workspace "$temp_workspace" \
  --field "topic=BMAD 환경 점검" >/dev/null
uv run --managed-python --no-cache _bmad/scripts/memlog.py append \
  --workspace "$temp_workspace" \
  --type event \
  --text "연기 테스트 통과" >/dev/null
grep -F -- '- (event) 연기 테스트 통과' "$temp_workspace/.memlog.md" >/dev/null || fail "BMAD 메모리 로그 연기 테스트에 실패했습니다."
printf '[9/9] BMAD 메모리 로그 확인: init, append 정상\n'

printf '완료: BMAD 작업 환경이 준비되었습니다.\n'
