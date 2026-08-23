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
  ".codex/config.toml"
  "_bmad/scripts/resolve_config.py"
  "_bmad/scripts/render_skill.py"
  "_bmad/scripts/memlog.py"
  "uv.toml"
)

for required_path in "${required_paths[@]}"; do
  [[ -e "$required_path" ]] || fail "필수 경로가 없습니다: $required_path"
done

printf '[1/6] uv 확인: '
uv --version

cache_dir="$(uv cache dir)"
if [[ "$cache_dir" == /* ]]; then
  cache_path="${cache_dir%/}"
else
  cache_path="$project_root/${cache_dir#./}"
  cache_path="${cache_path%/}"
fi
[[ "$cache_path" == "$project_root/.uv-cache" ]] || fail "uv 캐시 경로가 ./.uv-cache가 아닙니다: $cache_dir"
printf '[2/6] 프로젝트 로컬 캐시 확인: %s\n' "$cache_dir"

python_summary="$(
  uv run --managed-python --no-cache python -c \
    'import sys; assert sys.version_info >= (3, 11), sys.version; print(f"Python {sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro} | {sys.executable}")'
)"
printf '[3/6] uv 관리 Python 확인: %s\n' "$python_summary"

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
printf '[4/6] BMAD 중앙 설정 확인:\n%s\n' "$config_summary"

if ! render_summary="$(
  uv run --managed-python --no-cache _bmad/scripts/render_skill.py \
    --project-root "$project_root" \
    --skill "$project_root/.agents/skills/bmad-build"
)"; then
  fail "BMAD 스킬 렌더링 명령 실행에 실패했습니다."
fi
rendered_workflow="${render_summary#read and follow }"
[[ "$rendered_workflow" != "$render_summary" && -s "$rendered_workflow" ]] || fail "BMAD 스킬 렌더링 연기 테스트에 실패했습니다."
printf '[5/6] BMAD 스킬 렌더링 확인: bmad-build 정상\n'

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
printf '[6/6] BMAD 메모리 로그 확인: init, append 정상\n'

printf '완료: BMAD 작업 환경이 준비되었습니다.\n'
