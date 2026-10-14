#!/usr/bin/env bash
# CI 계약 시험.
#
# 왜 있는가: `classify-changes.sh`의 분류 규칙, `verify-merge-commit.sh`의 부모 검증,
# `aggregate-results.sh`의 집계 판정은 CI 판정 전체를 좌우하지만 workflow 안에서는 실행 결과를
# 봐야만 검증된다. CI-PLAN.md §5는 "분류 규칙과 허용 사유 enum은 workflow 구현 SPEC의 시험
# 대상이다"라고 요구한다. 이 스크립트는 임시 git fixture를 만들어 세 스크립트를 실제로 실행하고
# 값과 enum, 종료 코드, 오류 메시지를 함께 단정한다.
#
# 스크립트만으로는 부족하다. `ci.yml`의 job 구조·러너·게이트가 바뀌면 스크립트가 아무리
# 정확해도 필수 검사가 조용히 사라진다. branch protection은 `ci-required`라는 이름 하나만
# 보므로 job 이름을 바꾸면 required check가 영구 대기로 남고, `docs-only` 게이트를 한 줄
# 지우거나 더하면 검사 범위가 말없이 달라진다. 그래서 `ci.yml`의 계약도 여기서 단정한다.
#
# Story 1.2에서 단일 job이 `changes → frontend-quality·backend-quality →
# same-origin-integration → ci-required`로 갈라졌다(CI-PLAN §1). 단일 job을 전제한 단정
# (`runs-on` 정확히 1회, 액션 SHA 각 1회, 단계 게이트 10회)은 그 구조에서 스스로 실패하므로
# job 구조 계약으로 다시 설계했다. 등장 횟수 대신 job 목록·`needs` 배선·action@SHA 집합을 본다.
# 다만 지워진 단계 게이트 10회 루프는 그 단계들이 존재한다는 계약이기도 했다. 그 몫은
# job별 단계 목록 단정이 대신한다. 없으면 `Vitest` 단계를 지워도 이 시험이 통과한다.
#
# Story 3.7이 네 번째 스크립트 `check-alert-rules.sh`(경보 규칙의 형식 계약·promtool 시험)를 더했다. 여기서는 가짜
# promtool로 그 판정과 종료 코드 0/1/2를 단정한다 — 네트워크를 쓰지 않는다.
#
# 정본: _bmad-output/implementation-artifacts/spec-ci-foundation.md `## I/O & Edge-Case Matrix`
#       _bmad-output/implementation-artifacts/spec-1-2-same-origin-shell.md `## Tasks & Acceptance`
#
# 실행: bash .github/scripts/test-ci-scripts.sh
# 종료 코드
#   0 -- 모든 단정 통과
#   1 -- 단정 실패 (`  실패` 줄과 `::error::CI 계약 시험이 실패했다.`)
#   2 -- 시험 준비 실패 (`::error::시험 준비 실패:`). fixture를 못 만든 상태라 단정 결과가
#        없다. 단정 실패와 구분해야 "0개 통과"를 "문제 없음"으로 읽지 않는다.
#
# `ci.yml` 파싱은 러너와 개발 기기 양쪽에 항상 있는 도구(bash·grep·sed·awk)만 쓴다.
# PyYAML은 GitHub hosted 러너의 PATH 위 python3에 있다고 보장되지 않고, 개발 기기(Windows)의
# `python3`는 Microsoft Store 스텁이라 실행되지 않는다. 대신 들여쓰기를 지키는 정확한 줄
# 비교로 단정한다. `ci.yml`은 이 저장소가 소유하는 단일 파일이고 형식이 고정돼 있다.
#
# 이 스크립트는 네트워크·설치·저장소 상태를 건드리지 않는다. fixture는 임시 디렉터리에만
# 만들고 종료할 때 지운다.

set -uo pipefail

# `pipefail` 아래에서 생산자의 출력을 `grep -q`로 받는 파이프를 if 조건에 쓰지 않는다. -q는 첫 매치에서 바로
# 끝나고, 아직 쓰고 있던 생산자(awk)는 SIGPIPE(141)로 죽어 파이프라인 전체가 실패로 읽힌다.
# 어느 쪽이 먼저 끝나는지는 스케줄링에 달려 있어 로컬에서는 통과하고 러너에서만 실패한다
# (PR #9 첫 실행, `frontend-quality` 게이트 단정). 대신 `grep ... >/dev/null`을 쓴다. -q가
# 없는 grep은 입력을 끝까지 읽으므로 생산자가 SIGPIPE를 받지 않는다. 아래 자기 단정이 재발을 막는다.

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd "${script_dir}/../.." && pwd -P)"
classify_script="${script_dir}/classify-changes.sh"
verify_script="${script_dir}/verify-merge-commit.sh"
aggregate_script="${script_dir}/aggregate-results.sh"
alert_rules_script="${script_dir}/check-alert-rules.sh"
workflow_file="${repo_root}/.github/workflows/ci.yml"

# --------------------------------------------------------------------------
# 보고
# --------------------------------------------------------------------------

tests_run=0
tests_failed=0

record_pass() {
  tests_run=$((tests_run + 1))
  printf '  통과  %s\n' "$1"
}

record_fail() {
  tests_run=$((tests_run + 1))
  tests_failed=$((tests_failed + 1))
  printf '  실패  %s\n' "$1"
  printf '        %s\n' "$2"
}

# 준비 실패는 단정 실패가 아니다. 여기서 즉시 중단한다. 그대로 진행하면 빈 경로나
# 만들다 만 fixture 위에서 단정이 돌아 통과·실패 어느 쪽도 의미가 없어진다.
fail_setup() {
  printf '\n::error::시험 준비 실패: %s\n' "$1" >&2
  if [ -n "${2:-}" ]; then
    printf '%s\n' "$2" >&2
  fi
  exit 2
}

for required in "${classify_script}" "${verify_script}" "${aggregate_script}" "${alert_rules_script}" \
  "${workflow_file}"; do
  if [ ! -f "${required}" ]; then
    fail_setup "${required} 가 없다."
  fi
done

work_root="$(mktemp -d)" || fail_setup '임시 디렉터리를 만들지 못했다.'
if [ -z "${work_root}" ] || [ ! -d "${work_root}" ]; then
  fail_setup "mktemp -d가 쓸 수 없는 경로를 냈다: '${work_root}'"
fi

cleanup() {
  # 읽기 전용 비트가 남은 .git 객체 때문에 Windows에서 삭제가 막히는 일을 피한다.
  chmod -R u+w "${work_root}" 2>/dev/null || true
  rm -rf "${work_root}"
}
trap cleanup EXIT

# 사용자·러너의 전역 훅이 fixture 커밋에 끼어들지 못하게 빈 디렉터리를 가리킨다.
empty_hooks="${work_root}/empty-hooks"
mkdir -p "${empty_hooks}" || fail_setup "훅 디렉터리를 만들지 못했다: ${empty_hooks}"

setup_log="${work_root}/setup.log"

# run_setup <설명> <명령...>
# 준비 단계의 모든 명령은 이 함수를 거친다. 오류를 버리면 fixture가 조용히 비어
# 잘못된 통과가 나온다.
run_setup() {
  local description="$1"
  shift
  if ! "$@" >"${setup_log}" 2>&1; then
    fail_setup "${description}" "$(cat "${setup_log}" 2>/dev/null)"
  fi
}

# --------------------------------------------------------------------------
# fixture 도우미
# --------------------------------------------------------------------------

# 커밋 서명·훅·전역 설정이 시험 결과를 바꾸지 않도록 fixture마다 고정한다.
git_fixture() {
  local dir="$1"
  shift
  git -C "${dir}" \
    -c user.name='CI Fixture' \
    -c user.email='ci-fixture@example.invalid' \
    -c commit.gpgsign=false \
    -c "core.hooksPath=${empty_hooks}" \
    "$@"
}

# new_repo <이름>. 경로를 stdout으로 낸다.
# 명령 치환 안에서 도는 함수라 여기서 `fail_setup`을 부르면 서브셸만 죽는다.
# 실패는 반환 코드로 알리고 호출부가 중단한다.
new_repo() {
  local name="$1"
  local dir="${work_root}/repo-${name}"
  mkdir -p "${dir}" || return 1
  git -C "${dir}" init -q -b main >/dev/null 2>&1 || return 1
  printf '%s' "${dir}"
}

# make_repo <이름>. 준비 실패를 즉시 중단으로 바꾼 `new_repo` 래퍼.
make_repo() {
  local dir
  dir="$(new_repo "$1")" || fail_setup "저장소 fixture '$1'을 만들지 못했다."
  if [ -z "${dir}" ] || [ ! -d "${dir}/.git" ]; then
    fail_setup "저장소 fixture '$1'이 git 저장소가 아니다: '${dir}'"
  fi
  printf '%s' "${dir}"
}

# rev <repo> <revision>. 해석한 SHA를 stdout으로 낸다.
rev() {
  local dir="$1" revision="$2" sha
  sha="$(git -C "${dir}" rev-parse "${revision}" 2>&1)" \
    || fail_setup "${revision}을 해석하지 못했다 (${dir})." "${sha}"
  printf '%s' "${sha}"
}

# write_file <repo> <상대경로> [내용]
write_file() {
  local dir="$1" path="$2" body="${3:-fixture 내용}"
  run_setup "디렉터리 생성: $(dirname "${path}")" mkdir -p "${dir}/$(dirname "${path}")"
  if ! printf '%s\n' "${body}" >"${dir}/${path}"; then
    fail_setup "파일을 쓰지 못했다: ${dir}/${path}"
  fi
}

# commit_all <repo> <메시지>
commit_all() {
  local dir="$1" message="$2"
  run_setup "git add -A (${dir})" git_fixture "${dir}" add -A
  run_setup "git commit '${message}' (${dir})" git_fixture "${dir}" commit -q -m "${message}"
}

# 초기 커밋: 제품·문서·최상위 파일이 모두 존재하는 기준 트리.
seed_repo() {
  local dir="$1"
  write_file "${dir}" 'README.md' '읽어보기'
  write_file "${dir}" '.gitattributes' '* text=auto eol=lf'
  write_file "${dir}" 'frontend/src/App.tsx' 'export const App = () => null;'
  write_file "${dir}" 'frontend/package-lock.json' '{}'
  write_file "${dir}" 'scripts/check-bmad-env.sh' '#!/usr/bin/env bash'
  write_file "${dir}" '.github/workflows/ci.yml' 'name: CI'
  write_file "${dir}" '_bmad-output/planning-artifacts/plan.md' '계획'
  commit_all "${dir}" 'seed'
}

# merge_branch <repo> <브랜치명> <변경 함수>
# main에서 분기해 변경 함수를 적용하고 --no-ff로 병합한다.
# 결과 merge commit의 parent1은 병합 직전 main, parent2는 브랜치 tip이다.
merge_branch() {
  local dir="$1" branch="$2" mutate="$3"
  run_setup "브랜치 ${branch} 생성" git_fixture "${dir}" checkout -q -b "${branch}" main
  "${mutate}" "${dir}"
  commit_all "${dir}" "${branch}"
  run_setup 'main 복귀' git_fixture "${dir}" checkout -q main
  run_setup "브랜치 ${branch} 병합" \
    git_fixture "${dir}" merge -q --no-ff --no-edit -m "merge ${branch}" "${branch}"
}

# --------------------------------------------------------------------------
# 단정
# --------------------------------------------------------------------------

# assert_equals <라벨> <실제> <기대>
assert_equals() {
  local label="$1" got="$2" want="$3"
  if [ "${got}" = "${want}" ]; then
    record_pass "${label}"
  else
    record_fail "${label}" "실제 '${got}' (기대 '${want}')"
  fi
}

# check_classify <라벨> <repo> <event> <sha> <before> <기대 docs-only> <기대 reason>
check_classify() {
  local label="$1" dir="$2" event="$3" sha="$4" before="$5"
  local want_docs="$6" want_reason="$7"
  local out_file log_file status got_docs got_reason line_count

  out_file="${work_root}/output-$((tests_run + 1)).txt"
  log_file="${work_root}/log-$((tests_run + 1)).txt"
  : >"${out_file}"

  # 분류기도 `GITHUB_STEP_SUMMARY`가 있으면 거기에 판정을 덧붙인다. 러너에서 이 시험이
  # 돌 때 실제 job summary를 물려받으면 시험 fixture의 분류 결과가 PR 화면에 실린다.
  # 전용 sink로 돌린다. summary 기록 자체는 아래 전용 시험이 따로 단정한다.
  (
    cd "${dir}" || exit 97
    GITHUB_EVENT_NAME="${event}" \
    GITHUB_SHA="${sha}" \
    EVENT_BEFORE="${before}" \
    GITHUB_OUTPUT="${out_file}" \
    GITHUB_STEP_SUMMARY="${work_root}/classify-sink.md" \
    bash "${classify_script}"
  ) >"${log_file}" 2>&1
  status=$?

  if [ "${status}" -ne 0 ]; then
    record_fail "${label}" "종료 코드 ${status} (기대 0) — $(tr '\n' '|' <"${log_file}")"
    return
  fi

  # GITHUB_OUTPUT 계약: docs-only 한 줄, reason 한 줄. 그 외 줄이 있으면 실패다.
  line_count="$(grep -c '' "${out_file}")"
  if [ "${line_count}" -ne 2 ]; then
    record_fail "${label}" "GITHUB_OUTPUT 줄 수 ${line_count} (기대 2) — $(tr '\n' '|' <"${out_file}")"
    return
  fi

  got_docs="$(sed -n 's/^docs-only=//p' "${out_file}")"
  got_reason="$(sed -n 's/^reason=//p' "${out_file}")"

  if [ "${got_docs}" != "${want_docs}" ] || [ "${got_reason}" != "${want_reason}" ]; then
    record_fail "${label}" \
      "docs-only=${got_docs} reason=${got_reason} (기대 docs-only=${want_docs} reason=${want_reason})"
    return
  fi

  record_pass "${label}"
}

# check_verify <라벨> <repo> <sha> <base> <head> <기대 종료 코드> <기대 메시지 조각>
check_verify() {
  local label="$1" dir="$2" sha="$3" base="$4" head="$5"
  local want_status="$6" want_message="$7"
  local log_file status

  log_file="${work_root}/verify-$((tests_run + 1)).txt"

  (
    cd "${dir}" || exit 97
    GITHUB_SHA="${sha}" \
    BASE_SHA="${base}" \
    HEAD_SHA="${head}" \
    bash "${verify_script}"
  ) >"${log_file}" 2>&1
  status=$?

  if [ "${status}" -ne "${want_status}" ]; then
    record_fail "${label}" "종료 코드 ${status} (기대 ${want_status}) — $(tr '\n' '|' <"${log_file}")"
    return
  fi

  if [ -n "${want_message}" ] && ! grep -qF -- "${want_message}" "${log_file}"; then
    record_fail "${label}" "메시지에 '${want_message}'가 없다 — $(tr '\n' '|' <"${log_file}")"
    return
  fi

  record_pass "${label}"
}

# --- ci.yml 읽기 -----------------------------------------------------------
# job이 여럿이 된 뒤로 "파일 전체에서 몇 번 나오는가"만으로는 계약을 고정할 수 없다.
# 같은 단계 이름(`저장소 checkout`)이 여러 job에 있고, 같은 action이 여러 job에서 쓰인다.
# 그래서 job 블록을 먼저 잘라 내고 그 안에서 단정한다.
#
# YAML 파서를 쓰지 않는 이유는 파일 머리말에 적었다. 대신 이 파일의 들여쓰기 규약에 기댄다.
#   0칸 -- 최상위 키(`jobs:`)      2칸 -- job id      4칸 -- job 속성
#   6칸 -- `- name:` 단계 머리      8칸 -- 단계 속성

# workflow_job_block <job id>. 해당 job의 본문 줄만 stdout으로 낸다.
workflow_job_block() {
  awk -v want="  $1:" '
    /^jobs:$/ { injobs = 1; next }
    injobs == 0 { next }
    # 최상위 키를 다시 만나면 jobs 블록이 끝난 것이다.
    /^[^[:space:]]/ { injobs = 0; inblock = 0; next }
    /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ { inblock = ($0 == want); next }
    inblock { print }
  ' "${workflow_file}"
}

# workflow_job_ids. jobs 블록의 job id를 등장 순서대로 공백으로 이어 낸다.
workflow_job_ids() {
  awk '
    /^jobs:$/ { injobs = 1; next }
    injobs == 0 { next }
    /^[^[:space:]]/ { injobs = 0; next }
    /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ {
      sub(/:[[:space:]]*$/, "")
      sub(/^  /, "")
      printf "%s ", $0
    }
  ' "${workflow_file}" | sed 's/ *$//'
}

# workflow_step_block <job id> <단계 이름>. 그 job 안 해당 step의 본문 줄만 낸다.
workflow_step_block() {
  workflow_job_block "$1" | awk -v want="      - name: $2" '
    index($0, "      - name: ") == 1 { inblock = ($0 == want); next }
    inblock { print }
  '
}

# job_step_line <job id> <단계 이름>. job 블록 안에서의 줄 번호를 낸다. 없으면 빈 값.
# 단계 이름은 정규식이 아니라 리터럴로 비교한다(`-F -x`). 실제 단계 이름에 이미
# `Story 1.1 회귀 (Nginx origin)`처럼 정규식 메타문자가 들어 있어, 정규식으로 두면
# 이름이 있는데도 조용히 안 맞는 단정이 만들어진다. 못 찾은 것과 구분되지 않으므로
# 잘못된 통과가 아니라 잘못된 실패로도, 그 반대로도 나올 수 있다.
job_step_line() {
  workflow_job_block "$1" | grep -nxF -- "      - name: $2" | head -1 | cut -d: -f1
}

# assert_step_exists <job id> <단계 이름>
# 단계가 있기만 해도 통과다. 순서·내용 단정은 각 절이 따로 한다. 이 단정이 없으면
# 순서 단정에 쓰이지 않는 단계(`Vitest`, `의존 설치` 등)는 통째로 지워도 계약 시험이 통과한다.
assert_step_exists() {
  local job_id="$1" step_name="$2"
  if [ -n "$(job_step_line "${job_id}" "${step_name}")" ]; then
    record_pass "ci.yml · '${job_id}'에 '${step_name}' 단계가 있다"
  else
    record_fail "ci.yml · '${job_id}'에 '${step_name}' 단계가 있다" \
      '단계가 없다. 지워졌거나 이름이 바뀌었다.'
  fi
}

# count_lines <정규식>. 파일 전체에서 일치하는 줄 수를 stdout으로 낸다.
count_lines() {
  grep -cE -- "$1" "${workflow_file}" 2>/dev/null || true
}

# count_job_lines <job id> <정규식>. 그 job 블록 안에서 일치하는 줄 수를 낸다.
count_job_lines() {
  workflow_job_block "$1" | grep -cE -- "$2" 2>/dev/null || true
}

# workflow_uses. 쓰이는 `action@SHA`를 중복 없이 정렬해 공백으로 이어 낸다.
# 등장 횟수가 아니라 집합을 단정하기 위한 값이다. 새 action이 들어오거나 SHA가 바뀌면 달라진다.
workflow_uses() {
  grep -E '^[[:space:]]*uses: ' "${workflow_file}" \
    | sed 's/^[[:space:]]*uses: //; s/[[:space:]]*#.*$//; s/[[:space:]]*$//' \
    | sort -u \
    | tr '\n' ' ' \
    | sed 's/ *$//'
}

# --------------------------------------------------------------------------
# 이 파일 자체의 규약
# --------------------------------------------------------------------------

assert_equals 'test-ci-scripts.sh · pipefail 아래 조기 종료 grep -q 파이프가 없다' \
  "$(grep -cE '\|[[:space:]]*grep -[[:alpha:]]*q' "${BASH_SOURCE[0]}" | tr -d '[:space:]')" '0'

# --------------------------------------------------------------------------
# 변경 시나리오
# --------------------------------------------------------------------------

mutate_product() {
  write_file "$1" 'frontend/src/App.tsx' 'export const App = () => <main />;'
}

mutate_docs_only() {
  write_file "$1" '_bmad-output/planning-artifacts/plan.md' '갱신한 계획'
  write_file "$1" 'NOTES.md' '최상위 문서'
}

mutate_workflow() {
  write_file "$1" '.github/workflows/ci.yml' 'name: CI # 변경'
}

mutate_ci_script() {
  write_file "$1" '.github/scripts/classify-changes.sh' '#!/usr/bin/env bash'
}

mutate_dotfile() {
  write_file "$1" '.gitattributes' '* text=auto eol=lf # 변경'
}

mutate_repo_script() {
  write_file "$1" 'scripts/check-bmad-env.sh' '#!/usr/bin/env bash # 변경'
}

mutate_lockfile() {
  write_file "$1" 'frontend/package-lock.json' '{"lockfileVersion":3}'
}

mutate_mixed() {
  write_file "$1" '_bmad-output/planning-artifacts/plan.md' '갱신한 계획'
  write_file "$1" 'frontend/src/App.tsx' 'export const App = () => <main />;'
}

# 비ASCII 경로. git은 기본값 `core.quotePath=true`에서 이런 경로를
# `"_bmad-output/\355\225\234\352\270\200.md"` 형태의 C-quote 한 줄로 낸다. 분류기가 그
# 값을 그대로 읽으면 앞의 따옴표 때문에 `_bmad-output/*`에 걸리지 않고 오분류된다.
mutate_docs_hangul() {
  write_file "$1" '_bmad-output/planning-artifacts/한글 기획 노트.md' '한글 문서'
  write_file "$1" '스토리 메모.md' '최상위 한글 문서'
}

# 공백 포함 제품 경로. 줄 단위로 읽어도 우연히 맞는 경로라 분류 결과만으로는
# 구분되지 않지만, 아래 한글 문서 사례와 함께 -z 읽기를 고정한다.
mutate_product_with_space() {
  write_file "$1" 'frontend/src/새 화면.tsx' 'export const Screen = () => null;'
}

# 문서 내부 rename. 내용이 같아 rename 검출을 켜면 R 한 줄로 접힌다.
mutate_rename_docs_to_docs() {
  local dir="$1"
  run_setup '문서 내부 rename' git_fixture "${dir}" mv \
    '_bmad-output/planning-artifacts/plan.md' \
    '_bmad-output/planning-artifacts/plan-v2.md'
}

# 문서 -> frontend rename. 변경 후 경로가 제품이라 문서-only가 될 수 없다.
mutate_rename_docs_to_frontend() {
  local dir="$1"
  run_setup '문서 -> frontend rename' git_fixture "${dir}" mv \
    '_bmad-output/planning-artifacts/plan.md' \
    'frontend/src/plan.md'
}

# frontend -> 문서 rename. 변경 후 경로만 보면 문서로 오분류된다.
# `--no-renames`가 삭제 + 추가로 나눠 변경 전 경로까지 평가하는지 고정한다.
mutate_rename_frontend_to_docs() {
  local dir="$1"
  run_setup 'frontend -> 문서 rename' git_fixture "${dir}" mv \
    'frontend/src/App.tsx' \
    '_bmad-output/planning-artifacts/App.tsx'
}

printf 'CI 계약 시험\n\n'
printf '변경 분류 — pull_request\n'

# --- pull_request 경로 -----------------------------------------------------
pr_case() {
  local name="$1" mutate="$2" want_docs="$3" want_reason="$4" label="$5"
  local dir sha
  dir="$(make_repo "${name}")" || exit 2
  seed_repo "${dir}"
  merge_branch "${dir}" "topic-${name}" "${mutate}"
  sha="$(rev "${dir}" HEAD)" || exit 2
  check_classify "${label}" "${dir}" pull_request "${sha}" '' "${want_docs}" "${want_reason}"
}

pr_case product        mutate_product        false product-change 'frontend/** 제품 변경 -> product-change'
pr_case docs           mutate_docs_only      true  docs-only      '_bmad-output/** + 최상위 *.md -> docs-only'
pr_case workflow       mutate_workflow       false product-change '.github/workflows/** 변경 -> product-change'
pr_case ciscript       mutate_ci_script      false product-change '.github/scripts/** 변경 -> product-change'
pr_case dotfile        mutate_dotfile        false product-change '최상위 dotfile(.gitattributes) -> product-change'
pr_case reposcript     mutate_repo_script    false product-change 'scripts/** 경로 -> product-change'
pr_case lockfile       mutate_lockfile       false product-change 'lockfile 변경 -> product-change'
pr_case mixed          mutate_mixed          false product-change '문서 + 제품 혼합 -> product-change'
pr_case rndocs         mutate_rename_docs_to_docs      true  docs-only      '문서 내부 rename -> docs-only'
pr_case rnout          mutate_rename_docs_to_frontend  false product-change '문서 -> frontend rename -> product-change'
pr_case rnin           mutate_rename_frontend_to_docs  false product-change 'frontend -> 문서 rename(변경 전 경로 평가) -> product-change'

# 경로 인코딩. 파일명에 개행을 넣는 사례는 Windows 파일 시스템이 허용하지 않아
# fixture로 만들 수 없다. `-z` 읽기가 개행까지 감당한다는 점은 구분자 자체로 보장된다.
pr_case hangul         mutate_docs_hangul       true  docs-only      '한글·공백 파일명 문서-only -> docs-only'
pr_case spaceproduct   mutate_product_with_space false product-change '공백 포함 frontend 경로 -> product-change'

# 빈 diff: 내용을 바꾸지 않는 커밋을 병합한다. merge commit과 parent1의 트리가 같다.
empty_dir="$(make_repo empty)" || exit 2
seed_repo "${empty_dir}"
run_setup '빈 diff 브랜치 생성' git_fixture "${empty_dir}" checkout -q -b topic-empty main
run_setup '내용 없는 커밋' git_fixture "${empty_dir}" commit -q --allow-empty -m '내용 없는 커밋'
run_setup 'main 복귀' git_fixture "${empty_dir}" checkout -q main
run_setup '빈 diff 병합' \
  git_fixture "${empty_dir}" merge -q --no-ff --no-edit -m 'merge topic-empty' topic-empty
empty_sha="$(rev "${empty_dir}" HEAD)" || exit 2
check_classify '빈 diff -> empty-diff' "${empty_dir}" pull_request "${empty_sha}" '' false empty-diff

# --- 비-merge SHA와 해석 불가 SHA ------------------------------------------
# 분류기의 종료 코드는 항상 0이라는 계약을 이 두 경로가 고정한다. `set -e` 아래에서
# `git rev-parse ^1`이 실패하면 스크립트가 128로 죽고 GITHUB_OUTPUT이 빈 채로 남는다.
printf '\n변경 분류 — 비-merge·해석 불가 SHA\n'

nonmerge_dir="$(make_repo nonmerge)" || exit 2
seed_repo "${nonmerge_dir}"
nonmerge_root="$(rev "${nonmerge_dir}" HEAD)" || exit 2
write_file "${nonmerge_dir}" 'frontend/src/App.tsx' 'export const App = () => <main />;'
commit_all "${nonmerge_dir}" '비-merge 제품 변경'
nonmerge_sha="$(rev "${nonmerge_dir}" HEAD)" || exit 2

check_classify 'pull_request · 비-merge SHA -> 첫 부모 기준으로 분류하고 종료 0' \
  "${nonmerge_dir}" pull_request "${nonmerge_sha}" '' false product-change

check_classify 'pull_request · 부모 없는 root commit -> no-comparison-base(종료 0)' \
  "${nonmerge_dir}" pull_request "${nonmerge_root}" '' false no-comparison-base

check_classify 'pull_request · 해석 불가 SHA -> no-comparison-base(종료 0)' \
  "${nonmerge_dir}" pull_request 'deadbeefdeadbeefdeadbeefdeadbeefdeadbeef' '' \
  false no-comparison-base

# --- octopus merge ---------------------------------------------------------
printf '\noctopus merge\n'

octopus_dir="$(make_repo octopus)" || exit 2
seed_repo "${octopus_dir}"
octopus_base="$(rev "${octopus_dir}" HEAD)" || exit 2
for leaf in one two; do
  run_setup "octopus 브랜치 ${leaf} 생성" \
    git_fixture "${octopus_dir}" checkout -q -b "octopus-${leaf}" main
  write_file "${octopus_dir}" "_bmad-output/planning-artifacts/${leaf}.md" "${leaf} 문서"
  commit_all "${octopus_dir}" "octopus ${leaf}"
  run_setup 'main 복귀' git_fixture "${octopus_dir}" checkout -q main
done
# `--no-ff`가 없으면 octopus 전략이 첫 브랜치로 fast-forward한 뒤 나머지만 병합해
# 부모 2개짜리 commit이 나온다. 부모 3개를 만들려면 명시해야 한다.
run_setup 'octopus 병합' \
  git_fixture "${octopus_dir}" merge -q --no-ff --no-edit -m 'octopus merge' octopus-one octopus-two
octopus_sha="$(rev "${octopus_dir}" HEAD)" || exit 2
octopus_one="$(rev "${octopus_dir}" octopus-one)" || exit 2

check_verify '부모 3개(octopus merge) -> 종료 1' \
  "${octopus_dir}" "${octopus_sha}" "${octopus_base}" "${octopus_one}" 1 \
  '부모 2개인 merge commit이 아니다 (부모 3개).'

check_classify 'octopus merge도 분류는 종료 0' \
  "${octopus_dir}" pull_request "${octopus_sha}" '' true docs-only

# --- push 경로 -------------------------------------------------------------
printf '\n변경 분류 — push · workflow_dispatch\n'

push_dir="$(make_repo push)" || exit 2
seed_repo "${push_dir}"
push_before="$(rev "${push_dir}" HEAD)" || exit 2
write_file "${push_dir}" '_bmad-output/planning-artifacts/plan.md' 'push 갱신'
commit_all "${push_dir}" 'push 문서 변경'
push_after="$(rev "${push_dir}" HEAD)" || exit 2

check_classify 'push · 정상 before -> docs-only' \
  "${push_dir}" push "${push_after}" "${push_before}" true docs-only

check_classify 'push · zero SHA before -> no-comparison-base' \
  "${push_dir}" push "${push_after}" '0000000000000000000000000000000000000000' false no-comparison-base

check_classify 'push · 존재하지 않는 before -> no-comparison-base' \
  "${push_dir}" push "${push_after}" 'deadbeefdeadbeefdeadbeefdeadbeefdeadbeef' false no-comparison-base

check_classify 'push · 빈 before -> no-comparison-base' \
  "${push_dir}" push "${push_after}" '' false no-comparison-base

check_classify 'push · before == after -> empty-diff' \
  "${push_dir}" push "${push_after}" "${push_after}" false empty-diff

check_classify 'workflow_dispatch -> no-comparison-base' \
  "${push_dir}" workflow_dispatch "${push_after}" '' false no-comparison-base

# --- job summary -----------------------------------------------------------
# 분류 결과가 PR 화면에 남는지 고정한다. 이 줄이 없으면 frontend 검사가 왜 건너뛰어졌는지
# 단계 로그를 펼치기 전에는 알 수 없다.
summary_out="${work_root}/summary-output.txt"
summary_file="${work_root}/summary.md"
: >"${summary_out}"
: >"${summary_file}"
summary_status=0
(
  cd "${push_dir}" || exit 97
  GITHUB_EVENT_NAME=push \
  GITHUB_SHA="${push_after}" \
  EVENT_BEFORE="${push_before}" \
  GITHUB_OUTPUT="${summary_out}" \
  GITHUB_STEP_SUMMARY="${summary_file}" \
  bash "${classify_script}"
) >"${work_root}/summary-log.txt" 2>&1 || summary_status=$?

if [ "${summary_status}" -ne 0 ]; then
  record_fail 'GITHUB_STEP_SUMMARY 기록' \
    "종료 코드 ${summary_status} (기대 0) — $(tr '\n' '|' <"${work_root}/summary-log.txt")"
elif grep -qF '`docs-only`: `true`' "${summary_file}" \
  && grep -qF '`reason`: `docs-only`' "${summary_file}"; then
  record_pass 'GITHUB_STEP_SUMMARY에 docs-only와 reason을 남긴다'
else
  record_fail 'GITHUB_STEP_SUMMARY에 docs-only와 reason을 남긴다' \
    "$(tr '\n' '|' <"${summary_file}")"
fi

# --- merge commit 검증 -----------------------------------------------------
printf '\nPR merge commit 검증\n'

verify_dir="$(make_repo verify)" || exit 2
seed_repo "${verify_dir}"
verify_seed="$(rev "${verify_dir}" HEAD)" || exit 2
write_file "${verify_dir}" 'README.md' 'base 진행'
commit_all "${verify_dir}" 'base 진행'
verify_base="$(rev "${verify_dir}" HEAD)" || exit 2
merge_branch "${verify_dir}" topic-verify mutate_product
verify_merge="$(rev "${verify_dir}" HEAD)" || exit 2
verify_head="$(rev "${verify_dir}" topic-verify)" || exit 2

check_verify '정상 merge -> 종료 0' \
  "${verify_dir}" "${verify_merge}" "${verify_base}" "${verify_head}" 0 '검증 완료'

check_verify '부모 뒤바뀜 -> 종료 1' \
  "${verify_dir}" "${verify_merge}" "${verify_head}" "${verify_base}" 1 \
  '::error::merge commit의 부모가 이벤트의 base·head와 다르다.'

check_verify 'base 불일치 -> 종료 1' \
  "${verify_dir}" "${verify_merge}" "${verify_seed}" "${verify_head}" 1 \
  '::error::merge commit의 부모가 이벤트의 base·head와 다르다.'

check_verify 'head 불일치 -> 종료 1' \
  "${verify_dir}" "${verify_merge}" "${verify_base}" "${verify_seed}" 1 \
  '::error::merge commit의 부모가 이벤트의 base·head와 다르다.'

check_verify '부모 1개(비-merge commit) -> 종료 1' \
  "${verify_dir}" "${verify_base}" "${verify_base}" "${verify_head}" 1 \
  '부모 2개인 merge commit이 아니다 (부모 1개).'

check_verify '부모 0개(root commit) -> 종료 1' \
  "${verify_dir}" "${verify_seed}" "${verify_base}" "${verify_head}" 1 \
  '부모 2개인 merge commit이 아니다 (부모 0개).'

# 해석 불가능한 SHA도 실패다. `git rev-list`를 감싸지 않으면 128로 죽어 `::error::`가
# 붙지 않고, 로그만 보는 사람에게는 검증이 돌지 않은 것과 구분되지 않는다.
check_verify '해석 불가 SHA -> 종료 1 + ::error::' \
  "${verify_dir}" 'deadbeefdeadbeefdeadbeefdeadbeefdeadbeef' "${verify_base}" "${verify_head}" 1 \
  '::error::deadbeefdeadbeefdeadbeefdeadbeefdeadbeef를 이 저장소의 commit으로 해석할 수 없다.'

check_verify '빈 SHA -> 종료 1 + ::error::' \
  "${verify_dir}" '' "${verify_base}" "${verify_head}" 1 '::error::'

# 검증이 통과한 merge commit에서 분류가 parent1을 기준으로 삼는지 함께 고정한다.
check_classify '검증된 merge commit의 비교 기준은 parent1' \
  "${verify_dir}" pull_request "${verify_merge}" '' false product-change

# --------------------------------------------------------------------------
# 집계 판정
# --------------------------------------------------------------------------
printf '\n필수 검사 집계\n'

# 집계 스크립트는 `GITHUB_STEP_SUMMARY`가 있으면 거기에 판정 표를 덧붙인다. 아래 시험은
# 러너의 `changes` job 안에서 도는데, 그 변수를 그대로 물려주면 열몇 번의 시험 호출이
# 만들어 낸 가짜 판정 표가 실제 PR 화면의 job summary에 그대로 실린다. 시험 전용 sink로 돌린다.
aggregate_sink="${work_root}/aggregate-sink.md"

# check_aggregate <라벨> <docs_only> <changes> <frontend> <backend> <integration> <rehearsal>
#                 <기대 종료 코드> [기대 메시지 조각] [없어야 하는 메시지 조각]
#
# 복원 리허설(Story 3.10)의 결과는 기본으로 <rehearsal>과 같다 — 두 리허설은 같은 분류에서 같이 돌고 같이 건너뛴다. 복원 리허설만 다른
# 경우는 호출 앞에 `restore_result_override=<값>`을 붙인다(빈 값도 그대로 쓴다).
check_aggregate() {
  local label="$1" docs="$2" changes="$3" frontend="$4" backend="$5" integration="$6" rehearsal="$7"
  local want_status="$8" want_message="${9:-}" unwanted_message="${10:-}"
  local log_file status

  log_file="${work_root}/aggregate-$((tests_run + 1)).txt"

  (
    DOCS_ONLY="${docs}" \
    CHANGES_RESULT="${changes}" \
    FRONTEND_RESULT="${frontend}" \
    BACKEND_RESULT="${backend}" \
    INTEGRATION_RESULT="${integration}" \
    REHEARSAL_RESULT="${rehearsal}" \
    RESTORE_RESULT="${restore_result_override-${rehearsal}}" \
    GITHUB_STEP_SUMMARY="${aggregate_sink}" \
    bash "${aggregate_script}"
  ) >"${log_file}" 2>&1
  status=$?

  if [ "${status}" -ne "${want_status}" ]; then
    record_fail "${label}" "종료 코드 ${status} (기대 ${want_status}) — $(tr '\n' '|' <"${log_file}")"
    return
  fi

  if [ -n "${want_message}" ] && ! grep -qF -- "${want_message}" "${log_file}"; then
    record_fail "${label}" "메시지에 '${want_message}'가 없다 — $(tr '\n' '|' <"${log_file}")"
    return
  fi

  if [ -n "${unwanted_message}" ] && grep -qF -- "${unwanted_message}" "${log_file}"; then
    record_fail "${label}" "메시지에 '${unwanted_message}'가 있으면 안 된다 — $(tr '\n' '|' <"${log_file}")"
    return
  fi

  record_pass "${label}"
}

check_aggregate '제품 변경 · 네 job 모두 success -> 종료 0' \
  false success success success success success 0 '모든 예상 결과가 일치한다.'

check_aggregate '문서-only · 네 job 모두 skipped -> 종료 0' \
  true success skipped skipped skipped skipped 0 '모든 예상 결과가 일치한다.'

# 실패는 어느 job에서 나든 병합을 막는다.
check_aggregate '제품 변경 · frontend failure -> 종료 1' \
  false success failure success success success 1 '::error::frontend-quality job이 failure로 끝났다.'

check_aggregate '제품 변경 · backend failure -> 종료 1' \
  false success success failure success success 1 '::error::backend-quality job이 failure로 끝났다.'

check_aggregate '제품 변경 · integration cancelled -> 종료 1' \
  false success success success cancelled success 1 '::error::same-origin-integration job이 cancelled로 끝났다.'

# Story 3.8: 배포 리허설도 필수다. 리허설이 깨진 채로 병합되면 배포 절차가 검증 없이 develop에 들어간다.
check_aggregate '제품 변경 · deploy-rehearsal failure -> 종료 1' \
  false success success success success failure 1 '::error::deploy-rehearsal job이 failure로 끝났다.'

# 예상하지 않은 skip. `if` 조건을 잘못 고치면 검사가 조용히 사라진다.
check_aggregate '제품 변경 · 예상 밖 skipped -> 종료 1' \
  false success success skipped success success 1 \
  '::error::backend-quality job이 건너뛰어졌지만 이 변경은 제품 변경으로 분류됐다.'

check_aggregate '제품 변경 · 리허설만 예상 밖 skipped -> 종료 1' \
  false success success success success skipped 1 \
  '::error::deploy-rehearsal job이 건너뛰어졌지만 이 변경은 제품 변경으로 분류됐다.'

# 문서-only인데 제품 job이 돌았다. 분류와 게이트가 어긋난 상태다.
check_aggregate '문서-only · 예상 밖 success -> 종료 1' \
  true success success skipped skipped skipped 1 \
  '::error::frontend-quality job이 실행됐지만 이 변경은 문서-only로 분류됐다.'

# 빈 값. `needs`에서 job을 빼면 결과가 빈 문자열로 온다. 통과로 읽으면 job 하나를
# 지우는 것만으로 필수 검사가 사라진다.
check_aggregate '제품 변경 · 빈 결과 -> 종료 1' \
  false success success success '' success 1 \
  '::error::same-origin-integration job의 결과가 비었다.'

check_aggregate '제품 변경 · 리허설 결과 없음 -> 종료 1' \
  false success success success success '' 1 \
  '::error::deploy-rehearsal job의 결과가 비었다.'

# Story 3.10: 복원 리허설도 필수다. 다른 job이 모두 통과해도 그 하나가 실패·건너뜀·빈 결과면 병합을 막는다.
restore_result_override=failure check_aggregate '제품 변경 · restore-rehearsal failure -> 종료 1' \
  false success success success success success 1 '::error::restore-rehearsal job이 failure로 끝났다.'

restore_result_override=skipped check_aggregate '제품 변경 · 복원 리허설만 예상 밖 skipped -> 종료 1' \
  false success success success success success 1 \
  '::error::restore-rehearsal job이 건너뛰어졌지만 이 변경은 제품 변경으로 분류됐다.'

restore_result_override='' check_aggregate '제품 변경 · 복원 리허설 결과 없음 -> 종료 1' \
  false success success success success success 1 \
  '::error::restore-rehearsal job의 결과가 비었다.'

restore_result_override=success check_aggregate '문서-only · 복원 리허설만 예상 밖 success -> 종료 1' \
  true success skipped skipped skipped skipped 1 \
  '::error::restore-rehearsal job이 실행됐지만 이 변경은 문서-only로 분류됐다.'

check_aggregate 'changes job 실패 -> 종료 1' \
  false failure skipped skipped skipped skipped 1 '::error::changes job이 success가 아니다'

check_aggregate 'changes job 결과 없음 -> 종료 1' \
  false '' success success success success 1 '::error::changes job이 success가 아니다'

# 실제 `changes` 실패의 모습은 `DOCS_ONLY`까지 빈 값이다. 그 job이 분류 결과를 만들지 못했기
# 때문이다. 이때 판정을 계속 진행하면 기대치가 `success`로 굳어, 정상적으로 건너뛰어진 네 job이
# 각각 "건너뛰어졌지만 제품 변경으로 분류됐다"는 오류를 받고 `DOCS_ONLY` 오류까지 붙는다.
# 진짜 원인 하나에 없는 문제 다섯 개가 얹히는 셈이라 로그를 읽는 사람을 잘못된 곳으로 보낸다.
check_aggregate 'changes 실패 · 건너뛴 job을 분류·게이트 불일치로 오인하지 않는다' \
  '' failure skipped skipped skipped skipped 1 \
  '::error::changes job이 success가 아니다' \
  '건너뛰어졌지만 이 변경은 제품 변경으로 분류됐다'

check_aggregate 'changes 실패 · DOCS_ONLY 오류를 덧붙이지 않는다' \
  '' failure skipped skipped skipped skipped 1 \
  '이 실행의 원인은 changes job 하나다' \
  '::error::DOCS_ONLY가 true·false가 아니다'

# 줄 수까지 고정한다. 위 두 단정은 특정 문구만 보므로 다른 문구로 오해를 부르는 줄이
# 다시 늘어나도 걸리지 않는다. 허용되는 `::error::`는 원인 1줄과 종료 1줄뿐이다.
changes_fail_log="${work_root}/aggregate-changes-fail.txt"
(
  DOCS_ONLY='' \
  CHANGES_RESULT=failure \
  FRONTEND_RESULT=skipped \
  BACKEND_RESULT=skipped \
  INTEGRATION_RESULT=skipped \
  REHEARSAL_RESULT=skipped \
  RESTORE_RESULT=skipped \
  GITHUB_STEP_SUMMARY="${aggregate_sink}" \
  bash "${aggregate_script}"
) >"${changes_fail_log}" 2>&1
assert_equals 'changes 실패 · ::error::는 원인 1줄 + 종료 1줄뿐' \
  "$(grep -c -- '::error::' "${changes_fail_log}")" '2'

# 분류 결과 자체가 깨진 경우. fail-closed로 가장 엄격한 기대치를 적용한다.
check_aggregate 'DOCS_ONLY가 빈 값 -> 종료 1' \
  '' success success success success success 1 '::error::DOCS_ONLY가 true·false가 아니다'

check_aggregate 'DOCS_ONLY가 알 수 없는 값 -> 종료 1' \
  maybe success success success success success 1 '::error::DOCS_ONLY가 true·false가 아니다'

# 집계 결과가 PR 화면에 남는지 고정한다. 어떤 job이 왜 막았는지 단계 로그를 펼치기 전에 보인다.
aggregate_summary="${work_root}/aggregate-summary.md"
: >"${aggregate_summary}"
aggregate_summary_status=0
(
  DOCS_ONLY=false \
  CHANGES_RESULT=success \
  FRONTEND_RESULT=success \
  BACKEND_RESULT=success \
  INTEGRATION_RESULT=success \
  REHEARSAL_RESULT=success \
  RESTORE_RESULT=success \
  GITHUB_STEP_SUMMARY="${aggregate_summary}" \
  bash "${aggregate_script}"
) >"${work_root}/aggregate-summary-log.txt" 2>&1 || aggregate_summary_status=$?

if [ "${aggregate_summary_status}" -ne 0 ]; then
  record_fail '집계가 GITHUB_STEP_SUMMARY에 판정 표를 남긴다' \
    "종료 코드 ${aggregate_summary_status} (기대 0) — $(tr '\n' '|' <"${work_root}/aggregate-summary-log.txt")"
elif grep -qF '### 필수 검사 집계' "${aggregate_summary}" \
  && grep -qF '| `frontend-quality` |' "${aggregate_summary}" \
  && grep -qF '| `backend-quality` |' "${aggregate_summary}" \
  && grep -qF '| `same-origin-integration` |' "${aggregate_summary}" \
  && grep -qF '| `deploy-rehearsal` |' "${aggregate_summary}" \
  && grep -qF '| `restore-rehearsal` |' "${aggregate_summary}"; then
  record_pass '집계가 GITHUB_STEP_SUMMARY에 판정 표를 남긴다'
else
  record_fail '집계가 GITHUB_STEP_SUMMARY에 판정 표를 남긴다' \
    "$(tr '\n' '|' <"${aggregate_summary}")"
fi

# --------------------------------------------------------------------------
# 경보 규칙 시험 스크립트(Story 3.7)
# --------------------------------------------------------------------------
printf '\n경보 규칙 시험 스크립트\n'

# promtool 자리에 가짜를 둔다(`PROMTOOL`). 이 시험은 네트워크를 쓰지 않는다 — 실제 promtool 내려받기·대조·단위시험은
# 통합 job의 `경보 규칙 시험` 단계가 돈다. 여기서 고정하는 것은 스크립트의 판정이다: 형식 계약, 종료 코드 0/1/2, 그리고
# promtool을 어떤 인자로 부르는가.
fake_promtool="${work_root}/fake-promtool"
fake_promtool_log="${work_root}/fake-promtool.log"
cat >"${fake_promtool}" <<'FAKE' || fail_setup '가짜 promtool을 쓰지 못했다.'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"${FAKE_PROMTOOL_LOG}"
exit "${FAKE_PROMTOOL_EXIT:-0}"
FAKE
run_setup '가짜 promtool 실행 권한' chmod +x "${fake_promtool}"

# write_rules <디렉터리> <규칙 본문>. 단위시험 파일은 내용과 무관하다(가짜 promtool이 판정한다).
write_rules() {
  run_setup "규칙 디렉터리 생성: $1" mkdir -p "$1"
  printf 'groups:\n  - name: fixture\n    rules:\n%s\n' "$2" >"$1/alerts.yml" || fail_setup "규칙을 쓰지 못했다: $1"
  printf 'rule_files:\n  - alerts.yml\ntests: []\n' >"$1/alerts.test.yml" || fail_setup "단위시험을 쓰지 못했다: $1"
}

valid_rule='      - alert: FixtureAlert
        expr: up == 0
        for: 5m
        labels:
          severity: warning
          group: availability
        annotations:
          summary: fixture'

# check_alert_rules <라벨> <규칙 디렉터리> <promtool 경로> <가짜 종료 코드> <기대 종료 코드> <기대 메시지 조각>
# promtool 경로가 비면 `PROMTOOL`을 주지 않는다 — 내려받기 경로를 탄다(아래 가짜 curl·uname이 PATH 앞에 있을 때만 쓴다).
check_alert_rules() {
  local label="$1" rules="$2" tool="$3" fake_exit="$4" want_status="$5" want_message="$6"
  local log_file status
  log_file="${work_root}/alert-rules-$((tests_run + 1)).txt"
  : >"${fake_promtool_log}"
  (
    if [ -n "${tool}" ]; then
      export PROMTOOL="${tool}"
    else
      unset PROMTOOL
    fi
    ALERT_RULES_DIR="${rules}" \
    FAKE_PROMTOOL_EXIT="${fake_exit}" \
    FAKE_PROMTOOL_LOG="${fake_promtool_log}" \
    bash "${alert_rules_script}"
  ) >"${log_file}" 2>&1
  status=$?
  if [ "${status}" -ne "${want_status}" ]; then
    record_fail "${label}" "종료 코드 ${status} (기대 ${want_status}) — $(tr '\n' '|' <"${log_file}")"
    return
  fi
  if [ -n "${want_message}" ] && ! grep -F -- "${want_message}" "${log_file}" >/dev/null; then
    record_fail "${label}" "메시지에 '${want_message}'가 없다 — $(tr '\n' '|' <"${log_file}")"
    return
  fi
  record_pass "${label}"
}

rules_ok="${work_root}/rules-ok"
write_rules "${rules_ok}" "${valid_rule}"
check_alert_rules '경보 규칙 · 형식 계약과 promtool이 통과하면 0' "${rules_ok}" "${fake_promtool}" 0 0 '경보 규칙 시험 통과'
assert_equals '경보 규칙 · promtool을 문법 검사와 단위시험 두 번 부른다' \
  "$(tr '\n' '|' <"${fake_promtool_log}")" 'check rules alerts.yml|test rules alerts.test.yml|'
check_alert_rules '경보 규칙 · promtool 판정이 실패하면 1' "${rules_ok}" "${fake_promtool}" 1 1 '경보 규칙 시험 실패'
check_alert_rules '경보 규칙 · promtool을 얻지 못하면 2' "${rules_ok}" "${work_root}/no-such-promtool" 0 2 \
  '경보 규칙 시험 준비 실패'
# promtool이 실행되지 못하면(126 실행 불가·127 명령 없음) 판정 실패(1)가 아니라 준비 실패(2)다.
check_alert_rules '경보 규칙 · promtool 126은 2' "${rules_ok}" "${fake_promtool}" 126 2 '종료 코드 126'
check_alert_rules '경보 규칙 · promtool 127은 2' "${rules_ok}" "${fake_promtool}" 127 2 '종료 코드 127'

# 내려받기 경로: 가짜 `uname`·`curl`을 PATH 앞에 둔다. 네트워크를 쓰지 않는다.
fake_bin="${work_root}/fake-bin"
run_setup '가짜 명령 디렉터리' mkdir -p "${fake_bin}"
cat >"${fake_bin}/uname" <<'FAKE' || fail_setup '가짜 uname을 쓰지 못했다.'
#!/usr/bin/env bash
case "$1" in
  -s) printf '%s\n' "${FAKE_UNAME_S:-Linux}" ;;
  -m) printf '%s\n' "${FAKE_UNAME_M:-x86_64}" ;;
  *) printf '%s\n' "${FAKE_UNAME_S:-Linux}" ;;
esac
FAKE
cat >"${fake_bin}/curl" <<'FAKE' || fail_setup '가짜 curl을 쓰지 못했다.'
#!/usr/bin/env bash
# --output <경로>에 가짜 내용을 쓰거나(FAKE_CURL=garbage) 내려받기 실패(22)로 끝난다.
out=''
while [ "$#" -gt 0 ]; do
  if [ "$1" = '--output' ]; then out="$2"; shift; fi
  shift
done
if [ "${FAKE_CURL:-fail}" = 'garbage' ] && [ -n "${out}" ]; then
  printf 'not a prometheus archive\n' >"${out}"
  exit 0
fi
printf 'curl: (22) fixture download failure\n' >&2
exit 22
FAKE
run_setup '가짜 명령 실행 권한' chmod +x "${fake_bin}/uname" "${fake_bin}/curl"

# check_download <라벨> <uname -s> <uname -m> <curl 동작> <기대 메시지 조각>. 기대 종료 코드는 언제나 2다.
check_download() {
  local label="$1" os="$2" arch="$3" curl_mode="$4" want_message="$5" cache
  cache="${work_root}/promtool-cache-$((tests_run + 1))"
  (
    PATH="${fake_bin}:${PATH}" \
    FAKE_UNAME_S="${os}" \
    FAKE_UNAME_M="${arch}" \
    FAKE_CURL="${curl_mode}" \
    PROMTOOL_CACHE_DIR="${cache}" \
    check_alert_rules "${label}" "${rules_ok}" '' 0 2 "${want_message}"
  )
}
# 서브셸 안의 단정은 바깥 계수에 남지 않는다. 결과 줄을 세어 바깥에 옮긴다.
download_log="${work_root}/download-checks.txt"
: >"${download_log}"
{
  check_download '경보 규칙 · 내려받기 실패는 2' Linux x86_64 fail '내려받지 못했다'
  check_download '경보 규칙 · checksum 불일치는 2' Linux x86_64 garbage 'checksum이 맞지 않는다'
  check_download '경보 규칙 · 지원하지 않는 OS는 2' SunOS x86_64 fail '지원하지 않는 OS다'
  check_download '경보 규칙 · 지원하지 않는 아키텍처는 2' Linux mips fail '지원하지 않는 아키텍처다'
} >"${download_log}"
while IFS= read -r download_line; do
  case "${download_line}" in
    '  통과  '*) record_pass "${download_line#  통과  }" ;;
    '  실패  '*) record_fail "${download_line#  실패  }" '아래 줄 참조' ;;
    *) [ -n "${download_line}" ] && printf '%s\n' "${download_line}" ;;
  esac
done <"${download_log}"
assert_equals '경보 규칙 · 내려받기 경로 단정 넷이 모두 돌았다' "$(grep -c '^  \(통과\|실패\)  ' "${download_log}")" '4'

rules_missing="${work_root}/rules-missing"
write_rules "${rules_missing}" "${valid_rule}"
run_setup '단위시험 파일 제거' rm -f "${rules_missing}/alerts.test.yml"
check_alert_rules '경보 규칙 · 단위시험 파일이 없으면 2' "${rules_missing}" "${fake_promtool}" 0 2 '단위시험 파일이 없다'

# 형식 계약 위반은 promtool을 부르기 전에 1이다.
rules_no_group="${work_root}/rules-no-group"
write_rules "${rules_no_group}" '      - alert: NoGroup
        expr: up == 0
        for: 5m
        labels:
          severity: warning'
check_alert_rules '경보 규칙 · group label이 없으면 1' "${rules_no_group}" "${fake_promtool}" 0 1 'NoGroup: group label이 없다'
assert_equals '경보 규칙 · 형식 계약 위반은 promtool을 부르지 않는다' "$(grep -c '' "${fake_promtool_log}")" '0'

rules_no_for="${work_root}/rules-no-for"
write_rules "${rules_no_for}" '      - alert: NoFor
        expr: up == 0
        labels:
          severity: critical
          group: availability'
check_alert_rules '경보 규칙 · for:가 없으면 1' "${rules_no_for}" "${fake_promtool}" 0 1 'NoFor: for: 지속시간이 없다'

rules_strong="${work_root}/rules-strong"
write_rules "${rules_strong}" "${valid_rule}
      - alert: GeneralButStrong
        expr: up == 0
        for: 5m
        labels:
          severity: critical
          group: availability
          strong_signal: \"true\""
check_alert_rules '경보 규칙 · 승인되지 않은 경보의 strong_signal은 1' "${rules_strong}" "${fake_promtool}" 0 1 \
  'GeneralButStrong: 승인되지 않은 강한 신호 경보다'

rules_info="${work_root}/rules-info"
write_rules "${rules_info}" '      - alert: InfoAlert
        expr: absent(up)
        for: 1h
        labels:
          severity: info
          group: unmeasured'
check_alert_rules '경보 규칙 · severity info는 받는다' "${rules_info}" "${fake_promtool}" 0 0 '경보 규칙 시험 통과'

# label은 labels 구간만 본다. annotations에 적힌 severity·group은 label이 아니다.
rules_annotation="${work_root}/rules-annotation"
write_rules "${rules_annotation}" '      - alert: AnnotatedOnly
        expr: up == 0
        for: 5m
        annotations:
          severity: warning
          group: availability'
check_alert_rules '경보 규칙 · annotations의 severity·group은 label이 아니다(1)' "${rules_annotation}" \
  "${fake_promtool}" 0 1 'AnnotatedOnly: severity label'

rules_flow="${work_root}/rules-flow"
write_rules "${rules_flow}" '      - alert: FlowLabels
        expr: up == 0
        for: 5m
        labels: {severity: warning, group: availability}'
check_alert_rules '경보 규칙 · flow 표기 labels는 1' "${rules_flow}" "${fake_promtool}" 0 1 '블록 표기만 쓴다'

rules_indent="${work_root}/rules-indent"
write_rules "${rules_indent}" "${valid_rule}
    - alert: Misindented
      expr: up == 0"
check_alert_rules '경보 규칙 · 다른 들여쓰기의 - alert:는 1' "${rules_indent}" "${fake_promtool}" 0 1 \
  '들여쓰기는 6칸이어야 한다'

rules_empty="${work_root}/rules-empty"
write_rules "${rules_empty}" '      - record: fixture:up'
check_alert_rules '경보 규칙 · 경보가 하나도 없으면 1' "${rules_empty}" "${fake_promtool}" 0 1 '경보 규칙이 하나도 없다'

# 저장소의 실제 규칙 파일이 형식 계약을 지킨다(promtool 판정은 통합 job이 실제로 돈다).
check_alert_rules '경보 규칙 · 저장소의 alerts.yml이 형식 계약을 지킨다' "${repo_root}/deploy/observability" \
  "${fake_promtool}" 0 0 '형식 계약 통과'

# 스크립트도 이 파일과 같은 규약을 지킨다: pipefail 아래 조기 종료 grep -q 파이프가 없다.
assert_equals 'check-alert-rules.sh · pipefail 아래 조기 종료 grep -q 파이프가 없다' \
  "$(grep -cE '\|[[:space:]]*grep -[[:alpha:]]*q' "${alert_rules_script}" | tr -d '[:space:]')" '0'

# --------------------------------------------------------------------------
# workflow 계약
# --------------------------------------------------------------------------
printf '\nci.yml 계약\n'

# --- job 구조 --------------------------------------------------------------
# Story 1.2에서 단일 job이 CI-PLAN §1의 병렬 구조로 갈라졌다. 그 구조 자체가 계약이다.
# job 하나를 지우거나 `needs`를 한 줄 고치면 필수 검사가 조용히 사라지고, branch protection은
# `ci-required`라는 이름 하나만 보므로 그 사실을 알지 못한다.

# `changes → frontend-quality·backend-quality → same-origin-integration·deploy-rehearsal → ci-required`.
# 순서까지 단정한다. 파일에서 읽는 순서가 곧 사람이 이해하는 실행 순서다. `deploy-rehearsal`은 Story 3.8이 더했다.
assert_equals 'ci.yml · job 목록과 순서' \
  "$(workflow_job_ids)" \
  'changes frontend-quality backend-quality same-origin-integration deploy-rehearsal restore-rehearsal ci-required'

# branch protection이 참조하는 이름이다. 바뀌면 required check가 영원히 대기 상태로 남고
# 병합은 아무 검사 없이 열린다.
assert_equals 'ci.yml · job name은 ci-required' \
  "$(count_lines '^    name: ci-required$')" '1'

# `ubuntu-latest`는 러너 갱신이 CI 판정을 바꾼다. 일곱 job 모두 고정한다.
assert_equals 'ci.yml · 모든 job이 runs-on: ubuntu-24.04 고정' \
  "$(count_lines '^    runs-on: ubuntu-24\.04$')" '7'

for job_id in changes frontend-quality backend-quality same-origin-integration deploy-rehearsal restore-rehearsal ci-required; do
  assert_equals "ci.yml · '${job_id}' job이 ubuntu-24.04를 쓴다" \
    "$(count_job_lines "${job_id}" '^    runs-on: ubuntu-24\.04$')" '1'
done

# --- needs 배선 ------------------------------------------------------------
# 이 네 줄이 위 mermaid 그림 전체다. 하나만 어긋나도 검사가 병렬이 아니라 무관해진다.
assert_equals 'ci.yml · frontend-quality는 changes를 기다린다' \
  "$(count_job_lines frontend-quality '^    needs: changes$')" '1'
assert_equals 'ci.yml · backend-quality는 changes를 기다린다' \
  "$(count_job_lines backend-quality '^    needs: changes$')" '1'
assert_equals 'ci.yml · same-origin-integration은 두 품질 job을 기다린다' \
  "$(count_job_lines same-origin-integration '^    needs: \[changes, frontend-quality, backend-quality\]$')" '1'
assert_equals 'ci.yml · deploy-rehearsal은 두 품질 job을 기다린다' \
  "$(count_job_lines deploy-rehearsal '^    needs: \[changes, frontend-quality, backend-quality\]$')" '1'
# 복원 리허설(Story 3.10)은 backend jar 하나만 쓴다 — 프런트엔드 산출물을 기다리지 않는다.
assert_equals 'ci.yml · restore-rehearsal은 changes와 backend-quality를 기다린다' \
  "$(count_job_lines restore-rehearsal '^    needs: \[changes, backend-quality\]$')" '1'
# 집계가 기다리지 않는 job의 결과는 빈 값으로 온다. 여섯 job이 모두 있어야 한다.
assert_equals 'ci.yml · ci-required는 모든 선행 job을 기다린다' \
  "$(count_job_lines ci-required '^    needs: \[changes, frontend-quality, backend-quality, same-origin-integration, deploy-rehearsal, restore-rehearsal\]$')" '1'

# 선행 job이 실패·취소·skip 어느 쪽이든 집계는 실행되어 판정을 남겨야 한다.
# 이 줄이 없으면 실패한 실행에서 required check가 영원히 대기 상태로 남는다.
assert_equals 'ci.yml · ci-required는 always()로 실행된다' \
  "$(count_job_lines ci-required '^    if: \$\{\{ always\(\) \}\}$')" '1'

# CI-PLAN §5: aggregator timeout은 1분이다.
assert_equals 'ci.yml · ci-required timeout은 1분' \
  "$(count_job_lines ci-required '^    timeout-minutes: 1$')" '1'

# 집계는 스크립트가 소유한다. YAML 표현식은 저장소의 시험으로 고정할 수 없다.
if workflow_step_block ci-required '필수 검사 집계' \
  | grep -F 'bash .github/scripts/aggregate-results.sh' >/dev/null; then
  record_pass 'ci.yml · ci-required는 aggregate-results.sh로 판정한다'
else
  record_fail 'ci.yml · ci-required는 aggregate-results.sh로 판정한다' \
    '집계 단계에 aggregate-results.sh 호출이 없다.'
fi

# 집계 스크립트가 읽는 여섯 입력이 실제로 전달되는지. 하나라도 빠지면 그 job은
# 빈 값으로 평가되고, 스크립트는 이를 실패로 읽어야 하므로 여기서 먼저 잡는다.
for aggregate_input in \
  'DOCS_ONLY: ${{ needs.changes.outputs.docs-only }}' \
  'CHANGES_RESULT: ${{ needs.changes.result }}' \
  'FRONTEND_RESULT: ${{ needs.frontend-quality.result }}' \
  'BACKEND_RESULT: ${{ needs.backend-quality.result }}' \
  'INTEGRATION_RESULT: ${{ needs.same-origin-integration.result }}' \
  'REHEARSAL_RESULT: ${{ needs.deploy-rehearsal.result }}' \
  'RESTORE_RESULT: ${{ needs.restore-rehearsal.result }}'; do
  if workflow_step_block ci-required '필수 검사 집계' | grep -F -- "${aggregate_input}" >/dev/null; then
    record_pass "ci.yml · 집계 입력 '${aggregate_input%%:*}'을 전달한다"
  else
    record_fail "ci.yml · 집계 입력 '${aggregate_input%%:*}'을 전달한다" \
      "'${aggregate_input}' 줄이 없다."
  fi
done

# `changes`가 분류 결과를 밖으로 내보내지 않으면 아래 게이트가 전부 빈 값과 비교하게 된다.
assert_equals 'ci.yml · changes job이 docs-only를 output으로 낸다' \
  "$(count_job_lines changes '^      docs-only: \$\{\{ steps\.classify\.outputs\.docs-only \}\}$')" '1'

# --- 각 job의 필수 단계 -----------------------------------------------------
# 단일 job 시절에는 `docs-only` 게이트가 단계마다 붙어 있었고 그 개수(10회)를 세는 단정이
# 곧 "이 단계들이 존재한다"는 계약이기도 했다. 게이트가 job 단위로 올라가면서 그 부수 효과가
# 사라졌다. 순서·내용 단정이 걸린 단계는 일부뿐이라, 목록이 없으면 `Vitest`나 `의존 설치`처럼
# 아무 단정도 참조하지 않는 단계를 통째로 지워도 이 시험이 통과한다.
#
# 그래서 각 job이 실제로 무엇을 하는지를 목록으로 고정한다. 단계를 의도적으로 없앨 때는
# 여기서도 함께 지운다. 그 두 줄의 삭제가 리뷰가 봐야 할 지점이다.
assert_job_steps() {
  local job_id="$1" step
  while IFS= read -r step; do
    [ -n "${step}" ] || continue
    assert_step_exists "${job_id}" "${step}"
  done
}

assert_job_steps changes <<'STEPS'
저장소 checkout
PR merge commit 검증
committed-secret 검사
CI 스크립트 계약 시험
변경 분류
STEPS

assert_job_steps frontend-quality <<'STEPS'
저장소 checkout
Node 준비
의존 설치
타입 검사
ESLint
stylelint
Prettier 검사
프로덕션 빌드
Vitest
Chromium headless shell 설치
Chromium E2E
통합 입력 업로드 (frontend dist)
실패 진단 artifact 업로드
STEPS

assert_job_steps backend-quality <<'STEPS'
저장소 checkout
JDK 준비
Gradle 빌드
통합 입력 업로드 (backend jar)
실패 진단 artifact 업로드
STEPS

assert_job_steps same-origin-integration <<'STEPS'
저장소 checkout
경보 규칙 시험
통합 입력 내려받기 (frontend dist)
통합 입력 내려받기 (backend jar)
동일 출처 검증
Node 준비
의존 설치
Chromium headless shell 설치
Story 1.1 회귀 (Nginx origin)
compose 정리
실패 진단 artifact 업로드
STEPS

assert_job_steps deploy-rehearsal <<'STEPS'
저장소 checkout
배포 gate 시험
백업 스크립트 시험
통합 입력 내려받기 (frontend dist)
통합 입력 내려받기 (backend jar)
배포 리허설
백업 리허설
실패 진단 artifact 업로드
STEPS

assert_job_steps restore-rehearsal <<'STEPS'
저장소 checkout
복원 스크립트 시험
통합 입력 내려받기 (backend jar)
복원 리허설
실패 진단 artifact 업로드
STEPS

assert_job_steps ci-required <<'STEPS'
저장소 checkout
필수 검사 집계
STEPS

# --- 문서-only 게이트 ------------------------------------------------------
# 게이트는 이제 단계가 아니라 job에 붙는다. 개수까지 단정한다. 하나 더 달면 검사가 조용히
# 줄고, 하나 지우면 문서-only PR이 전체 검사를 돈다. 둘 다 여기서 걸린다.
# `ci-required`에는 붙지 않는다. 집계는 어떤 분류에서도 실행되어야 한다.
docs_gate="if: needs.changes.outputs.docs-only != 'true'"
assert_equals 'ci.yml · docs-only 게이트를 단 job은 5개' \
  "$(grep -cF "    ${docs_gate}" "${workflow_file}")" '5'

for gated_job in frontend-quality backend-quality same-origin-integration deploy-rehearsal restore-rehearsal; do
  if workflow_job_block "${gated_job}" | grep -F -- "${docs_gate}" >/dev/null; then
    record_pass "ci.yml · '${gated_job}' job에 docs-only 게이트가 있다"
  else
    record_fail "ci.yml · '${gated_job}' job에 docs-only 게이트가 있다" \
      'job을 찾지 못했거나 게이트가 없다.'
  fi
done

if workflow_job_block ci-required | grep -F -- "${docs_gate}" >/dev/null; then
  record_fail 'ci.yml · ci-required에는 docs-only 게이트가 없다' \
    '집계가 문서-only PR에서 건너뛰어지면 required check가 영원히 대기한다.'
else
  record_pass 'ci.yml · ci-required에는 docs-only 게이트가 없다'
fi

# --- 이벤트와 필터 ---------------------------------------------------------
# top-level `paths`·`paths-ignore`는 workflow 자체를 생략시켜 `ci-required` status가
# 만들어지지 않는 분기를 연다(CI-PLAN §5).
assert_equals 'ci.yml · top-level paths·paths-ignore 없음' \
  "$(count_lines '^ {0,4}paths(-ignore)?:')" '0'

# --- 외부 action 고정 ------------------------------------------------------
# 외부 action은 full commit SHA로 고정한다(CI-PLAN §11). 값이 바뀌면 리뷰 없이 다른 코드가
# 필수 경로에서 실행된다. checkout처럼 여러 job이 쓰는 action은 여러 번 나오므로 등장 횟수가
# 아니라 "쓰이는 action@SHA 집합"을 단정한다. 새 action이 하나라도 들어오면 여기서 걸린다.
assert_equals 'ci.yml · 모든 uses가 40자리 commit SHA로 고정됐다' \
  "$(count_lines '^[[:space:]]*uses: [^[:space:]]+@[0-9a-f]{40}([[:space:]]|$)')" \
  "$(count_lines '^[[:space:]]*uses: ')"

assert_equals 'ci.yml · 허용된 action@SHA 집합' \
  "$(workflow_uses)" \
  'actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c actions/setup-java@dd06d9cba3e5552c54d9f8ea23572deb30010f7c actions/setup-node@820762786026740c76f36085b0efc47a31fe5020 actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a gitleaks/gitleaks-action@e0c47f4f8be36e29cdc102c57e68cb5cbf0e8d1e'

# --- changes job -----------------------------------------------------------
# 계약 시험이 분류보다 뒤로 가면 분류기를 분류기로 게이트하게 된다.
test_step_line="$(job_step_line changes 'CI 스크립트 계약 시험')"
classify_step_line="$(job_step_line changes '변경 분류')"
if [ -n "${test_step_line}" ] && [ -n "${classify_step_line}" ] \
  && [ "${test_step_line}" -lt "${classify_step_line}" ]; then
  record_pass 'ci.yml · 계약 시험 단계가 분류 단계보다 앞에 있다'
else
  record_fail 'ci.yml · 계약 시험 단계가 분류 단계보다 앞에 있다' \
    "계약 시험 줄 '${test_step_line}', 분류 줄 '${classify_step_line}'"
fi

assert_equals 'ci.yml · 분류 단계 id는 classify' \
  "$(count_job_lines changes '^        id: classify$')" '1'

# 계약 시험에는 조건이 붙지 않는다. 조건이 붙는 순간 우회 경로가 생긴다.
if workflow_step_block changes 'CI 스크립트 계약 시험' | grep -E '^        if:' >/dev/null; then
  record_fail 'ci.yml · 계약 시험 단계에 조건이 없다' \
    '조건이 붙으면 그 조건을 만족하지 않는 PR에서 계약 시험이 사라진다.'
else
  record_pass 'ci.yml · 계약 시험 단계에 조건이 없다'
fi

# PR이 자기 분류기를 고쳐 검사를 축소할 수 없어야 한다(CI-PLAN §5).
if workflow_step_block changes '변경 분류' \
  | grep -F 'git show "${PR_BASE_SHA}:.github/scripts/classify-changes.sh"' >/dev/null; then
  record_pass 'ci.yml · PR에서는 target base의 승인된 분류기를 꺼내 쓴다'
else
  record_fail 'ci.yml · PR에서는 target base의 승인된 분류기를 꺼내 쓴다' \
    '분류 단계에 git show <base>:classify-changes.sh 호출이 없다.'
fi

# 후속 job은 움직이는 ref가 아니라 이벤트가 고정한 commit을 checkout한다(CI-PLAN §5).
assert_equals 'ci.yml · 모든 job이 github.sha를 checkout한다' \
  "$(count_lines '^          ref: \$\{\{ github\.sha \}\}$')" '7'

# --- frontend-quality ------------------------------------------------------
# lint·stylelint·format 검사는 타입 검사 뒤, 프로덕션 빌드 앞이다(spec-frontend-lint-policy.md,
# CI-PLAN §6). 빌드 뒤로 밀리면 빠른 실패가 늦어지고, 타입 검사 앞으로 가면 타입 인지 lint가
# 타입 오류를 lint 오류로 다시 보고한다.
typecheck_step_line="$(job_step_line frontend-quality '타입 검사')"
build_step_line="$(job_step_line frontend-quality '프로덕션 빌드')"
for quality_step in 'ESLint' 'stylelint' 'Prettier 검사'; do
  quality_step_line="$(job_step_line frontend-quality "${quality_step}")"
  if [ -n "${typecheck_step_line}" ] && [ -n "${build_step_line}" ] && [ -n "${quality_step_line}" ] \
    && [ "${typecheck_step_line}" -lt "${quality_step_line}" ] \
    && [ "${quality_step_line}" -lt "${build_step_line}" ]; then
    record_pass "ci.yml · '${quality_step}' 단계가 타입 검사 뒤, 프로덕션 빌드 앞에 있다"
  else
    record_fail "ci.yml · '${quality_step}' 단계가 타입 검사 뒤, 프로덕션 빌드 앞에 있다" \
      "타입 검사 줄 '${typecheck_step_line}', '${quality_step}' 줄 '${quality_step_line}', 빌드 줄 '${build_step_line}'"
  fi
done

# 세 단계가 package.json의 명령을 그대로 부르는지 고정한다. 인라인 옵션이 붙으면 로컬 재현 명령과 어긋난다.
# 행 전체를 앵커로 잡는다. 부분 문자열이면 `run lint`가 `run lint:css` 줄에도 맞아 단정이 비어 버린다.
assert_equals 'ci.yml · ESLint 단계는 npm run lint를 그대로 쓴다' \
  "$(count_lines '^        run: npm --prefix frontend run lint$')" '1'
assert_equals 'ci.yml · stylelint 단계는 npm run lint:css를 그대로 쓴다' \
  "$(count_lines '^        run: npm --prefix frontend run lint:css$')" '1'
assert_equals 'ci.yml · Prettier 단계는 npm run format:check를 그대로 쓴다' \
  "$(count_lines '^        run: npm --prefix frontend run format:check$')" '1'

# 필수 실행이 `test:e2e:run`을 쓰는지. `test:e2e`는 같은 빌드를 두 번 한다.
if workflow_step_block frontend-quality 'Chromium E2E' \
  | grep -F 'npm --prefix frontend run test:e2e:run' >/dev/null; then
  record_pass 'ci.yml · E2E는 build를 건너뛰는 test:e2e:run을 쓴다'
else
  record_fail 'ci.yml · E2E는 build를 건너뛰는 test:e2e:run을 쓴다' \
    'Chromium E2E 단계가 test:e2e:run을 부르지 않는다.'
fi

# --- backend-quality -------------------------------------------------------
# Java major가 `ARCHITECTURE-SPINE.md` Stack 표와 어긋나면 toolchain 해석이 실패하거나
# 다른 bytecode가 나온다. wrapper가 아닌 임의 gradle을 부르면 버전 고정이 무의미해진다.
if workflow_step_block backend-quality 'JDK 준비' | grep -F "java-version: '25'" >/dev/null; then
  record_pass 'ci.yml · backend-quality는 JDK 25를 준비한다'
else
  record_fail 'ci.yml · backend-quality는 JDK 25를 준비한다' "java-version: '25' 줄이 없다."
fi

if workflow_step_block backend-quality 'Gradle 빌드' | grep -F 'backend/gradlew' >/dev/null; then
  record_pass 'ci.yml · backend-quality는 wrapper로 빌드한다'
else
  record_fail 'ci.yml · backend-quality는 wrapper로 빌드한다' 'gradlew 호출이 없다.'
fi

# --- 통합 입력 artifact ----------------------------------------------------
# 두 품질 job이 만든 산출물을 통합 job이 재빌드 없이 소비한다(CI-PLAN §8).
# retention 1일은 그 계약의 일부다. 통합 입력을 장기 보존하지 않는다.
assert_equals 'ci.yml · 통합 입력 artifact retention은 1일' \
  "$(count_lines '^          retention-days: 1$')" '2'

# 시험이 통과해도 산출물이 올라가지 않으면 통합 job이 검증할 대상이 없다.
# `warn`이면 그 사실을 아무도 모른 채 job이 성공한다.
assert_equals 'ci.yml · 통합 입력 업로드는 if-no-files-found: error' \
  "$(count_lines '^          if-no-files-found: error$')" '2'

# 부분 문자열 비교는 `retention-days: 14`·`retention-days: 10`에도 맞는다. 진단 artifact가
# 7일이라 지금은 우연히 통과하지만, 그 값을 늘리는 순간 이 단정은 통합 입력이 사라져도
# 통과한다. 파일의 다른 단정과 같게 줄 전체를 앵커로 잡고 job 안에서 센다.
for producer in frontend-quality backend-quality; do
  assert_equals "ci.yml · '${producer}'가 retention 1일 통합 입력을 만든다" \
    "$(count_job_lines "${producer}" '^          retention-days: 1$')" '1'
done

# 진단 업로드는 네 job 모두 `warn`이다. `ignore`로 바꾸면 report·trace 경로가 어긋나도 이 단계가
# 조용히 성공해, 실패한 실행에 진단물이 없다는 사실을 아무도 모른 채 영원히 "업로드 성공"이 된다.
# `error`도 답이 아니다. 진단 수집이 job 판정을 뒤집으면 원인이 진단 경로로 바뀐다.
assert_equals 'ci.yml · 진단 artifact 업로드는 if-no-files-found: warn' \
  "$(count_lines '^          if-no-files-found: warn$')" '5'

for diagnostics_job in frontend-quality backend-quality same-origin-integration deploy-rehearsal restore-rehearsal; do
  assert_equals "ci.yml · '${diagnostics_job}'의 진단 업로드가 warn이다" \
    "$(count_job_lines "${diagnostics_job}" '^          if-no-files-found: warn$')" '1'
done

# 이름에 run ID와 attempt가 들어가야 취소되거나 대체된 실행의 artifact를 재사용하지 않는다.
# 생산 2건(두 품질 job)과 소비 2건(통합 job)을 따로 센다. 같은 정규식이 양쪽에 걸린다는 것이
# 곧 "소비자가 생산자와 같은 이름을 본다"는 뜻이다. 한쪽 이름만 바꾸면 여기서 걸린다.
artifact_name_pattern='^          name: (frontend-dist|backend-jar)-\$\{\{ github\.run_id \}\}-\$\{\{ github\.run_attempt \}\}$'
assert_equals 'ci.yml · 두 품질 job이 run ID·attempt가 붙은 통합 입력을 만든다' \
  "$(( $(count_job_lines frontend-quality "${artifact_name_pattern}") \
     + $(count_job_lines backend-quality "${artifact_name_pattern}") ))" '2'
assert_equals 'ci.yml · 통합 job이 같은 이름의 통합 입력을 소비한다' \
  "$(count_job_lines same-origin-integration "${artifact_name_pattern}")" '2'

# --- same-origin-integration ----------------------------------------------
# 통합 job이 다시 빌드하면 검증 대상이 품질 job이 검증한 바이트와 달라진다.
if workflow_job_block same-origin-integration | grep -E 'run: npm --prefix frontend run build$' >/dev/null; then
  record_fail 'ci.yml · 통합 job은 프런트엔드를 다시 빌드하지 않는다' \
    '통합 job에 build 단계가 있다. 품질 job의 산출물을 그대로 써야 한다.'
else
  record_pass 'ci.yml · 통합 job은 프런트엔드를 다시 빌드하지 않는다'
fi

if workflow_job_block same-origin-integration | grep -F 'gradlew' >/dev/null; then
  record_fail 'ci.yml · 통합 job은 백엔드를 다시 빌드하지 않는다' \
    '통합 job에 gradlew 호출이 있다. 품질 job의 jar를 그대로 써야 한다.'
else
  record_pass 'ci.yml · 통합 job은 백엔드를 다시 빌드하지 않는다'
fi

# Vitest 단계의 상한은 실측(PR #87에서 2분 5초)보다 넉넉한 4분이다. 다시 2분으로 좁히면 통과한 실행도 시간 초과로 끝난다.
assert_equals 'ci.yml · Vitest 단계의 timeout은 4분' \
  "$(workflow_step_block frontend-quality 'Vitest' | grep -cxF '        timeout-minutes: 4')" '1'

# 경보 규칙 시험의 판정은 스크립트가 소유한다(Story 3.7). 단계에 인라인 판정을 두지 않는다. 내려받기가 멈춰도 job 예산을
# 다 쓰지 않게 단계 상한이 있다.
assert_equals 'ci.yml · 경보 규칙 시험 단계의 timeout은 5분' \
  "$(workflow_step_block same-origin-integration '경보 규칙 시험' | grep -cxF '        timeout-minutes: 5')" '1'
# 주입된 origin 회귀 단계는 Playwright `globalTimeout` 7분(`frontend/playwright.config.ts`, 그 시험이 단계보다 짧음을 고정)을
# 감싸는 8분이다(Story 2.17 — 4.6분 실측이 5분 상한에 붙었다). 단계가 그보다 짧아지면 진단 artifact 업로드 전에 끊긴다.
assert_equals 'ci.yml · 주입 origin 회귀 단계의 timeout은 8분' \
  "$(workflow_step_block same-origin-integration 'Story 1.1 회귀 (Nginx origin)' | grep -cxF '        timeout-minutes: 8')" '1'
# 통합 job 상한은 ci.yml 주석의 단계 상한 합 19분이다(Story 2.17). 회귀 단계만 올리고 job을 그대로 두면 정상 실행이 끊긴다.
assert_equals 'ci.yml · same-origin-integration job의 timeout은 19분' \
  "$(count_job_lines same-origin-integration '^    timeout-minutes: 19$')" '1'
assert_equals 'ci.yml · 경보 규칙 시험 단계는 check-alert-rules.sh 하나를 부른다' \
  "$(workflow_step_block same-origin-integration '경보 규칙 시험' | grep -E '^        run: ' | sed 's/^        run: //')" \
  'bash .github/scripts/check-alert-rules.sh'

if workflow_step_block same-origin-integration '동일 출처 검증' \
  | grep -F 'bash deploy/compose/verify-same-origin.sh' >/dev/null; then
  record_pass 'ci.yml · 통합 job은 로컬과 같은 검증 스크립트를 쓴다'
else
  record_fail 'ci.yml · 통합 job은 로컬과 같은 검증 스크립트를 쓴다' \
    'verify-same-origin.sh 호출이 없다.'
fi

# 검증 스크립트가 스택을 남기지 않으면 뒤의 Story 1.1 회귀는 죽은 origin에 요청한다. 반대로
# 스택을 남기는데 job이 compose를 또 띄우면 같은 스택을 두 번 세우는 것이다. 둘 다 여기서 막는다.
if workflow_step_block same-origin-integration '동일 출처 검증' \
  | grep -F 'verify-same-origin.sh --keep-stack' >/dev/null; then
  record_pass 'ci.yml · 동일 출처 검증은 --keep-stack으로 스택을 남긴다'
else
  record_fail 'ci.yml · 동일 출처 검증은 --keep-stack으로 스택을 남긴다' '--keep-stack이 없다.'
fi

if workflow_job_block same-origin-integration | grep -E 'docker compose .* up( |$)' >/dev/null; then
  record_fail 'ci.yml · 통합 job은 compose를 직접 띄우지 않는다' \
    '검증 스크립트가 스택을 소유한다. job에 별도 compose up이 있다.'
else
  record_pass 'ci.yml · 통합 job은 compose를 직접 띄우지 않는다'
fi

# 주입이 없으면 Playwright가 자기 preview 서버를 띄운다. 그러면 Nginx가 아니라 그 서버를
# 검증하면서도 green이 난다. Story 1.1 회귀가 조용히 무의미해지는 유일한 경로다.
if workflow_step_block same-origin-integration 'Story 1.1 회귀 (Nginx origin)' \
  | grep -F 'PLAYWRIGHT_BASE_URL: http://127.0.0.1:18080' >/dev/null; then
  record_pass 'ci.yml · Story 1.1 회귀는 Nginx origin을 주입받는다'
else
  record_fail 'ci.yml · Story 1.1 회귀는 Nginx origin을 주입받는다' \
    'PLAYWRIGHT_BASE_URL 주입이 없다.'
fi

# compose 스택은 실패·취소에서도 정리해야 한다. 러너는 매번 새로 뜨지만 이 단계가 없으면
# 로컬에서 같은 명령을 따라 한 사람의 기기에 스택이 남는다.
if workflow_step_block same-origin-integration 'compose 정리' | grep -F 'if: always()' >/dev/null; then
  record_pass 'ci.yml · compose 정리는 always()로 실행된다'
else
  record_fail 'ci.yml · compose 정리는 always()로 실행된다' 'if: always()가 없다.'
fi

# --- deploy-rehearsal(Story 3.8) ---------------------------------------------
# 리허설도 두 품질 job의 산출물을 그대로 소비한다. 다시 빌드하면 리허설이 돈 바이트가 시험된 바이트와 달라진다.
assert_equals 'ci.yml · 리허설 job이 같은 이름의 통합 입력을 소비한다' \
  "$(count_job_lines deploy-rehearsal "${artifact_name_pattern}")" '2'
if workflow_job_block deploy-rehearsal | grep -E 'gradlew|run: npm --prefix frontend run build$' >/dev/null; then
  record_fail 'ci.yml · 리허설 job은 산출물을 다시 빌드하지 않는다' '리허설 job에 gradlew 또는 프런트엔드 build가 있다.'
else
  record_pass 'ci.yml · 리허설 job은 산출물을 다시 빌드하지 않는다'
fi
# 판정은 두 스크립트가 소유한다. 단계에는 그 호출 한 줄씩만 있다.
assert_equals 'ci.yml · 배포 gate 시험 단계는 test-preflight.sh 하나를 부른다' \
  "$(workflow_step_block deploy-rehearsal '배포 gate 시험' | grep -E '^        run: ' | sed 's/^        run: //')" \
  'bash deploy/production/test-preflight.sh'
assert_equals 'ci.yml · 배포 리허설 단계는 rehearse-deploy.sh 하나를 부른다' \
  "$(workflow_step_block deploy-rehearsal '배포 리허설' | grep -E '^        run: ' | sed 's/^        run: //')" \
  'bash deploy/production/rehearse-deploy.sh'
# 리허설 스크립트가 스택을 소유한다. job이 compose를 직접 띄우지 않는다.
if workflow_job_block deploy-rehearsal | grep -E 'docker compose .* up( |$)' >/dev/null; then
  record_fail 'ci.yml · 리허설 job은 compose를 직접 띄우지 않는다' '리허설 job에 별도 compose up이 있다.'
else
  record_pass 'ci.yml · 리허설 job은 compose를 직접 띄우지 않는다'
fi
# 실패한 리허설의 기록을 남길 자리를 스크립트에 준다. 없으면 스크립트가 정리하며 기록을 지워 진단 업로드가 빈다.
if workflow_step_block deploy-rehearsal '배포 리허설' \
  | grep -F 'BOKRILOG_REHEARSAL_DIAGNOSTICS: ${{ runner.temp }}/deploy-rehearsal-diagnostics' >/dev/null \
  && workflow_step_block deploy-rehearsal '실패 진단 artifact 업로드' \
  | grep -F 'path: ${{ runner.temp }}/deploy-rehearsal-diagnostics' >/dev/null; then
  record_pass 'ci.yml · 리허설 진단 기록 자리와 업로드 경로가 같다'
else
  record_fail 'ci.yml · 리허설 진단 기록 자리와 업로드 경로가 같다' '진단 디렉터리 env 또는 업로드 경로가 없다.'
fi
assert_equals 'ci.yml · 배포 리허설 단계의 timeout은 20분' \
  "$(workflow_step_block deploy-rehearsal '배포 리허설' | grep -cxF '        timeout-minutes: 20')" '1'
assert_equals 'ci.yml · deploy-rehearsal job timeout은 70분' \
  "$(count_job_lines deploy-rehearsal '^    timeout-minutes: 70$')" '1'
# 백업 리허설(Story 3.9)도 판정은 스크립트가 소유한다. 단계에는 호출 한 줄과 진단 자리, timeout만 있다. 진단 자리는 업로드 경로 아래다
# — 업로드 단계 하나가 두 리허설의 기록을 함께 올린다.
assert_equals 'ci.yml · 백업 스크립트 시험 단계는 test-backup-scripts.sh 하나를 부른다' \
  "$(workflow_step_block deploy-rehearsal '백업 스크립트 시험' | grep -E '^        run: ' | sed 's/^        run: //')" \
  'bash deploy/production/test-backup-scripts.sh'
assert_equals 'ci.yml · 백업 리허설 단계는 rehearse-backup.sh 하나를 부른다' \
  "$(workflow_step_block deploy-rehearsal '백업 리허설' | grep -E '^        run: ' | sed 's/^        run: //')" \
  'bash deploy/production/rehearse-backup.sh'
assert_equals 'ci.yml · 백업 리허설 단계의 timeout은 40분' \
  "$(workflow_step_block deploy-rehearsal '백업 리허설' | grep -cxF '        timeout-minutes: 40')" '1'
if workflow_step_block deploy-rehearsal '백업 리허설' \
  | grep -F 'BOKRILOG_REHEARSAL_DIAGNOSTICS: ${{ runner.temp }}/deploy-rehearsal-diagnostics/backup' >/dev/null; then
  record_pass 'ci.yml · 백업 리허설 진단 기록은 업로드 경로 아래에 있다'
else
  record_fail 'ci.yml · 백업 리허설 진단 기록은 업로드 경로 아래에 있다' '진단 디렉터리 env가 없거나 업로드 경로 밖이다.'
fi
# --- restore-rehearsal(Story 3.10) -------------------------------------------
# 복원 리허설도 품질 job의 jar를 그대로 소비한다(프런트엔드 산출물은 쓰지 않는다). 판정은 두 스크립트가 소유한다.
assert_equals 'ci.yml · 복원 리허설 job이 같은 이름의 backend jar를 소비한다' \
  "$(count_job_lines restore-rehearsal "${artifact_name_pattern}")" '1'
if workflow_job_block restore-rehearsal | grep -E 'gradlew|run: npm --prefix frontend run build$' >/dev/null; then
  record_fail 'ci.yml · 복원 리허설 job은 산출물을 다시 빌드하지 않는다' '복원 리허설 job에 gradlew 또는 프런트엔드 build가 있다.'
else
  record_pass 'ci.yml · 복원 리허설 job은 산출물을 다시 빌드하지 않는다'
fi
if workflow_job_block restore-rehearsal | grep -E 'docker compose .* up( |$)' >/dev/null; then
  record_fail 'ci.yml · 복원 리허설 job은 compose를 직접 띄우지 않는다' '복원 리허설 job에 별도 compose up이 있다.'
else
  record_pass 'ci.yml · 복원 리허설 job은 compose를 직접 띄우지 않는다'
fi
assert_equals 'ci.yml · 복원 스크립트 시험 단계는 test-restore-scripts.sh 하나를 부른다' \
  "$(workflow_step_block restore-rehearsal '복원 스크립트 시험' | grep -E '^        run: ' | sed 's/^        run: //')" \
  'bash deploy/production/test-restore-scripts.sh'
assert_equals 'ci.yml · 복원 리허설 단계는 rehearse-restore.sh 하나를 부른다' \
  "$(workflow_step_block restore-rehearsal '복원 리허설' | grep -E '^        run: ' | sed 's/^        run: //')" \
  'bash deploy/production/rehearse-restore.sh'
assert_equals 'ci.yml · 복원 리허설 단계의 timeout은 30분' \
  "$(workflow_step_block restore-rehearsal '복원 리허설' | grep -cxF '        timeout-minutes: 30')" '1'
assert_equals 'ci.yml · restore-rehearsal job timeout은 40분' \
  "$(count_job_lines restore-rehearsal '^    timeout-minutes: 40$')" '1'
if workflow_step_block restore-rehearsal '복원 리허설' \
  | grep -F 'BOKRILOG_REHEARSAL_DIAGNOSTICS: ${{ runner.temp }}/restore-rehearsal-diagnostics' >/dev/null \
  && workflow_step_block restore-rehearsal '실패 진단 artifact 업로드' \
  | grep -F 'path: ${{ runner.temp }}/restore-rehearsal-diagnostics' >/dev/null; then
  record_pass 'ci.yml · 복원 리허설 진단 기록 자리와 업로드 경로가 같다'
else
  record_fail 'ci.yml · 복원 리허설 진단 기록 자리와 업로드 경로가 같다' '진단 디렉터리 env 또는 업로드 경로가 없다.'
fi
# 리허설 스크립트도 이 저장소의 셸 규약을 따른다(gate 시험이 그 파일들을 훑는다). 여기서도 한 번 더 막는다.
for deploy_script in deploy.sh preflight.sh rehearse-deploy.sh lib.sh test-preflight.sh backup.sh install-backup-tools.sh \
  rehearse-backup.sh test-backup-scripts.sh backup-lib.sh restore.sh tombstone-export.sh check-recovery-manifest.sh \
  rehearse-restore.sh test-restore-scripts.sh; do
  deploy_path="${repo_root}/deploy/production/${deploy_script}"
  if [ ! -f "${deploy_path}" ]; then
    record_fail "deploy/production/${deploy_script}가 있다" '파일이 없다.'
    continue
  fi
  assert_equals "deploy/production/${deploy_script} · pipefail 아래 조기 종료 grep -q 파이프가 없다" \
    "$(grep -vE '^[[:space:]]*#' "${deploy_path}" | grep -cE '\|[[:space:]]*grep -[[:alpha:]]*q' | tr -d '[:space:]')" '0'
done

# --------------------------------------------------------------------------
printf '\n%s개 중 %s개 통과, %s개 실패\n' \
  "${tests_run}" "$((tests_run - tests_failed))" "${tests_failed}"

if [ "${tests_failed}" -ne 0 ]; then
  printf '::error::CI 계약 시험이 실패했다.\n'
  exit 1
fi
exit 0
