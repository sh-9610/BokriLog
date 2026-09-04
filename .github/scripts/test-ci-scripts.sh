#!/usr/bin/env bash
# CI 계약 시험.
#
# 왜 있는가: `classify-changes.sh`의 분류 규칙과 `verify-merge-commit.sh`의 부모 검증은
# CI 판정 전체를 좌우하지만 workflow 안에서는 실행 결과를 봐야만 검증된다. CI-PLAN.md §5는
# "분류 규칙과 허용 사유 enum은 workflow 구현 SPEC의 시험 대상이다"라고 요구한다.
# 이 스크립트는 임시 git fixture를 만들어 두 스크립트를 실제로 실행하고
# `docs-only` 값과 `reason` enum, 종료 코드, 오류 메시지를 함께 단정한다.
#
# 스크립트만으로는 부족하다. `ci.yml`의 job 이름·러너·게이트가 바뀌면 스크립트가 아무리
# 정확해도 필수 검사가 조용히 사라진다. branch protection은 `ci-required`라는 이름 하나만
# 보므로 job 이름을 바꾸면 required check가 영구 대기로 남고, `docs-only` 게이트를 한 줄
# 지우거나 더하면 검사 범위가 말없이 달라진다. 그래서 `ci.yml`의 계약도 여기서 단정한다.
#
# 정본: _bmad-output/implementation-artifacts/spec-ci-foundation.md `## I/O & Edge-Case Matrix`
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

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd "${script_dir}/../.." && pwd -P)"
classify_script="${script_dir}/classify-changes.sh"
verify_script="${script_dir}/verify-merge-commit.sh"
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

for required in "${classify_script}" "${verify_script}" "${workflow_file}"; do
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

  (
    cd "${dir}" || exit 97
    GITHUB_EVENT_NAME="${event}" \
    GITHUB_SHA="${sha}" \
    EVENT_BEFORE="${before}" \
    GITHUB_OUTPUT="${out_file}" \
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

# workflow_step_block <단계 이름>. 해당 step의 본문 줄만 stdout으로 낸다.
workflow_step_block() {
  awk -v want="      - name: $1" '
    index($0, "      - name: ") == 1 { inblock = ($0 == want); next }
    inblock { print }
  ' "${workflow_file}"
}

# check_workflow_contains <라벨> <파일> <찾을 문자열>
check_workflow_contains() {
  local label="$1" file="$2" needle="$3"
  if grep -qF -- "${needle}" "${file}"; then
    record_pass "${label}"
  else
    record_fail "${label}" "'${needle}'를 찾지 못했다."
  fi
}

# count_lines <정규식>. 파일에서 일치하는 줄 수를 stdout으로 낸다.
count_lines() {
  grep -cE -- "$1" "${workflow_file}" 2>/dev/null || true
}

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
# workflow 계약
# --------------------------------------------------------------------------
printf '\nci.yml 계약\n'

# branch protection이 참조하는 이름이다. job id나 `name`이 바뀌면 required check가
# 영원히 대기 상태로 남고 병합은 아무 검사 없이 열린다.
jobs_line="$(grep -n '^jobs:$' "${workflow_file}" | head -1 | cut -d: -f1)"
if [ -z "${jobs_line}" ]; then
  record_fail 'ci.yml · jobs 블록 존재' "'^jobs:' 줄이 없다."
else
  job_ids="$(tail -n +$((jobs_line + 1)) "${workflow_file}" \
    | grep -E '^  [A-Za-z0-9_.-]+:[[:space:]]*$' \
    | sed 's/^  //; s/:[[:space:]]*$//' \
    | tr '\n' ' ' | sed 's/ *$//')"
  assert_equals 'ci.yml · job은 ci-required 하나뿐' "${job_ids}" 'ci-required'
fi

assert_equals 'ci.yml · job name은 ci-required' \
  "$(count_lines '^    name: ci-required$')" '1'

# `ubuntu-latest`는 러너 갱신이 CI 판정을 바꾼다.
assert_equals 'ci.yml · runs-on은 ubuntu-24.04 고정' \
  "$(count_lines '^    runs-on: ubuntu-24\.04$')" '1'

# top-level `paths`·`paths-ignore`는 workflow 자체를 생략시켜 `ci-required` status가
# 만들어지지 않는 분기를 연다(CI-PLAN §5).
assert_equals 'ci.yml · top-level paths·paths-ignore 없음' \
  "$(count_lines '^ {0,4}paths(-ignore)?:')" '0'

# 외부 action은 full commit SHA로 고정한다(CI-PLAN §11). 이 값이 바뀌면 리뷰 없이
# 다른 코드가 필수 경로에서 실행된다.
for pinned_sha in \
  3d3c42e5aac5ba805825da76410c181273ba90b1 \
  820762786026740c76f36085b0efc47a31fe5020 \
  043fb46d1a93c77aae656e7c1c64a875d1fc6a0a \
  e0c47f4f8be36e29cdc102c57e68cb5cbf0e8d1e; do
  assert_equals "ci.yml · action SHA ${pinned_sha} 고정" \
    "$(count_lines "^[[:space:]]*uses: [^[:space:]]+@${pinned_sha}([[:space:]]|\$)")" '1'
done

# 문서-only 게이트. 개수까지 단정한다. 게이트를 하나 더 달면 검사가 조용히 줄고,
# 하나 지우면 문서-only PR이 전체 검사를 돈다. 둘 다 여기서 걸린다.
docs_gate="if: steps.classify.outputs.docs-only != 'true'"
assert_equals 'ci.yml · docs-only 게이트를 단 단계는 10개' \
  "$(grep -cF "        ${docs_gate}" "${workflow_file}")" '10'

for gated_step in \
  'Node 준비' \
  '의존 설치' \
  '타입 검사' \
  'ESLint' \
  'stylelint' \
  'Prettier 검사' \
  '프로덕션 빌드' \
  'Vitest' \
  'Chromium headless shell 설치' \
  'Chromium E2E'; do
  if workflow_step_block "${gated_step}" | grep -qF -- "${docs_gate}"; then
    record_pass "ci.yml · '${gated_step}' 단계에 docs-only 게이트가 있다"
  else
    record_fail "ci.yml · '${gated_step}' 단계에 docs-only 게이트가 있다" \
      '단계를 찾지 못했거나 게이트가 없다.'
  fi
done

# lint·stylelint·format 검사는 타입 검사 뒤, 프로덕션 빌드 앞이다(spec-frontend-lint-policy.md,
# CI-PLAN §6). 빌드 뒤로 밀리면 빠른 실패가 늦어지고, 타입 검사 앞으로 가면 타입 인지 lint가
# 타입 오류를 lint 오류로 다시 보고한다.
step_line() {
  grep -n "^      - name: $1\$" "${workflow_file}" | head -1 | cut -d: -f1
}
typecheck_step_line="$(step_line '타입 검사')"
build_step_line="$(step_line '프로덕션 빌드')"
for quality_step in 'ESLint' 'stylelint' 'Prettier 검사'; do
  quality_step_line="$(step_line "${quality_step}")"
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

# 계약 시험이 분류보다 뒤로 가면 분류기를 분류기로 게이트하게 된다.
test_step_line="$(grep -n '^      - name: CI 스크립트 계약 시험$' "${workflow_file}" | head -1 | cut -d: -f1)"
classify_step_line="$(grep -n '^      - name: 변경 분류$' "${workflow_file}" | head -1 | cut -d: -f1)"
if [ -n "${test_step_line}" ] && [ -n "${classify_step_line}" ] \
  && [ "${test_step_line}" -lt "${classify_step_line}" ]; then
  record_pass 'ci.yml · 계약 시험 단계가 분류 단계보다 앞에 있다'
else
  record_fail 'ci.yml · 계약 시험 단계가 분류 단계보다 앞에 있다' \
    "계약 시험 줄 '${test_step_line}', 분류 줄 '${classify_step_line}'"
fi

assert_equals 'ci.yml · 분류 단계 id는 classify' \
  "$(count_lines '^        id: classify$')" '1'

# PR이 자기 분류기를 고쳐 검사를 축소할 수 없어야 한다(CI-PLAN §5).
if workflow_step_block '변경 분류' \
  | grep -qF 'git show "${PR_BASE_SHA}:.github/scripts/classify-changes.sh"'; then
  record_pass 'ci.yml · PR에서는 target base의 승인된 분류기를 꺼내 쓴다'
else
  record_fail 'ci.yml · PR에서는 target base의 승인된 분류기를 꺼내 쓴다' \
    '분류 단계에 git show <base>:classify-changes.sh 호출이 없다.'
fi

# `ignore`면 진단물 경로가 어긋나도 업로드 단계가 조용히 성공한다.
assert_equals 'ci.yml · artifact 업로드는 if-no-files-found: warn' \
  "$(count_lines '^          if-no-files-found: warn$')" '1'

# 필수 실행이 `test:e2e:run`을 쓰는지. `test:e2e`는 같은 빌드를 두 번 한다.
check_workflow_contains 'ci.yml · E2E는 build를 건너뛰는 test:e2e:run을 쓴다' \
  "${workflow_file}" 'npm --prefix frontend run test:e2e:run'

# --------------------------------------------------------------------------
printf '\n%s개 중 %s개 통과, %s개 실패\n' \
  "${tests_run}" "$((tests_run - tests_failed))" "${tests_failed}"

if [ "${tests_failed}" -ne 0 ]; then
  printf '::error::CI 계약 시험이 실패했다.\n'
  exit 1
fi
exit 0
