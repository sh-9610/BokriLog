#!/usr/bin/env bash
# 필수 검사 집계.
#
# 왜 있는가: Story 1.2부터 `ci-required`는 스스로 검사를 실행하지 않는다. 선행 job의 결과를 모아
# 병합 가능 여부 하나를 판정하는 aggregator다(CI-PLAN §1·§5). 이 판정이 틀리면 실패한 검사 위에서
# 병합이 열린다. YAML 표현식 안에 두면 저장소의 시험으로 고정할 수 없으므로 스크립트로 분리한다.
#
# 판정은 fail-closed다. `success`와 "예상한 skipped" 두 가지만 통과이고, `failure`·`cancelled`·
# 빈 값·예상하지 않은 `skipped`·알 수 없는 값은 모두 실패다. GitHub의 `needs.<job>.result`는
# job이 아예 실행되지 않으면 빈 문자열로 온다. 그 경우를 통과로 읽으면 job 하나를 지우는 것만으로
# 필수 검사가 사라진다.
#
# 다만 `changes`가 실패하면 분류 결과가 없어 나머지 job의 기대치를 정할 수 없다. 그때는 원인 하나만
# 보고하고 즉시 끝낸다. 계속 진행하면 정상적으로 건너뛰어진 세 job에 대해 오해를 부르는 `::error::`
# 세 줄이 더 붙는다.
#
# 정본: _bmad-output/implementation-artifacts/spec-1-2-same-origin-shell.md `## Tasks & Acceptance`
#       CI-PLAN.md §1(job 구조), §5(CI-D5 고정된 required gate 판정)
#
# 입력 (환경변수)
#   DOCS_ONLY           -- `changes` job이 낸 분류 결과. `true`면 제품 job은 건너뛴 것이 정상이다.
#   CHANGES_RESULT      -- `needs.changes.result`
#   FRONTEND_RESULT     -- `needs.frontend-quality.result`
#   BACKEND_RESULT      -- `needs.backend-quality.result`
#   INTEGRATION_RESULT  -- `needs.same-origin-integration.result`
#   REHEARSAL_RESULT    -- `needs.deploy-rehearsal.result`(Story 3.8 — 운영 배포 절차의 리허설)
#   RESTORE_RESULT      -- `needs.restore-rehearsal.result`(Story 3.10 — 격리 복원 리허설)
#   GITHUB_STEP_SUMMARY -- 선택. 있으면 판정 표를 job summary에도 남긴다.
#
# 종료 코드
#   0 -- 병합 가능
#   1 -- 병합 불가 (`::error::` 줄과 함께 사유를 남긴다)
#
# 이 계약은 .github/scripts/test-ci-scripts.sh가 고정한다.

set -uo pipefail

docs_only="${DOCS_ONLY:-}"
changes_result="${CHANGES_RESULT:-}"

failures=0
summary_rows=''

# add_row <job 이름> <실제 결과> <기대>
add_row() {
  summary_rows="${summary_rows}| \`$1\` | \`${2:-<빈 값>}\` | \`$3\` |
"
}

# write_summary. 판정 표를 job summary에 남긴다. 어떤 job이 왜 막았는지 단계 로그를 펼치기
# 전에 보이게 하는 유일한 경로다.
write_summary() {
  [ -n "${GITHUB_STEP_SUMMARY:-}" ] || return 0
  {
    echo '### 필수 검사 집계'
    echo ''
    echo "- \`docs-only\`: \`${docs_only:-<빈 값>}\`"
    echo ''
    echo '| job | 결과 | 기대 |'
    echo '|---|---|---|'
    printf '%s' "${summary_rows}"
  } >>"${GITHUB_STEP_SUMMARY}"
}

add_row 'changes' "${changes_result}" 'success'

# `changes`는 어떤 분류에서도 반드시 성공해야 한다. 이 job이 분류를 만들기 때문이다.
# 여기가 실패하면 아래 세 job의 결과는 어떤 값이든 의미가 없으므로 원인 하나만 보고하고 끝낸다.
#
# 계속 진행하면 `DOCS_ONLY`가 빈 값이라 기대치가 `success`로 굳고, 정상적으로 건너뛰어진 세 job이
# 각각 "건너뛰어졌지만 제품 변경으로 분류됐다"는 `::error::`를 낸다. 진짜 원인 하나에 오해를
# 부르는 세 줄이 붙어 로그를 읽는 사람을 분류·게이트 불일치라는 없는 문제로 보낸다.
if [ "${changes_result}" != 'success' ]; then
  printf '::error::changes job이 success가 아니다 (실제 %s). 분류 결과가 없으므로 나머지 job은 판정하지 않는다. 이 실행의 원인은 changes job 하나다.\n' \
    "${changes_result:-<빈 값>}"
  add_row 'frontend-quality' "${FRONTEND_RESULT:-}" '판정 불가'
  add_row 'backend-quality' "${BACKEND_RESULT:-}" '판정 불가'
  add_row 'same-origin-integration' "${INTEGRATION_RESULT:-}" '판정 불가'
  add_row 'deploy-rehearsal' "${REHEARSAL_RESULT:-}" '판정 불가'
  add_row 'restore-rehearsal' "${RESTORE_RESULT:-}" '판정 불가'
  write_summary
  printf '\n::error::필수 검사 집계가 실패했다. 병합 조건을 충족하지 않는다.\n'
  exit 1
fi

# 분류 결과가 두 가지 중 하나가 아니면 그 자체가 실패다. 오타 하나로 전체 판정이
# "제품 변경" 쪽으로 조용히 넘어가거나 그 반대가 되는 일을 막는다.
case "${docs_only}" in
  true) expected='skipped' ;;
  false) expected='success' ;;
  *)
    printf '::error::DOCS_ONLY가 true·false가 아니다 (실제 %s).\n' "${docs_only:-<빈 값>}"
    # 판정 불가는 fail-closed다. 나머지 job에는 가장 엄격한 기대치를 적용한다.
    expected='success'
    failures=$((failures + 1))
    ;;
esac

# check_job <job 이름> <실제 결과>
check_job() {
  local name="$1" result="${2:-}"

  add_row "${name}" "${result}" "${expected}"

  if [ "${result}" = "${expected}" ]; then
    printf '  통과  %s = %s\n' "${name}" "${result}"
    return
  fi

  printf '  실패  %s = %s (기대 %s)\n' "${name}" "${result:-<빈 값>}" "${expected}"
  case "${result}" in
    '')
      printf '::error::%s job의 결과가 비었다. job이 실행되지 않았거나 needs에서 빠졌다.\n' "${name}"
      ;;
    skipped)
      printf '::error::%s job이 건너뛰어졌지만 이 변경은 제품 변경으로 분류됐다.\n' "${name}"
      ;;
    success)
      printf '::error::%s job이 실행됐지만 이 변경은 문서-only로 분류됐다. 분류와 게이트가 어긋난다.\n' "${name}"
      ;;
    *)
      printf '::error::%s job이 %s로 끝났다.\n' "${name}" "${result}"
      ;;
  esac
  failures=$((failures + 1))
}

printf '필수 검사 집계 (docs-only=%s, 기대 결과=%s)\n' "${docs_only:-<빈 값>}" "${expected}"

check_job 'frontend-quality' "${FRONTEND_RESULT:-}"
check_job 'backend-quality' "${BACKEND_RESULT:-}"
check_job 'same-origin-integration' "${INTEGRATION_RESULT:-}"
check_job 'deploy-rehearsal' "${REHEARSAL_RESULT:-}"
check_job 'restore-rehearsal' "${RESTORE_RESULT:-}"

write_summary

if [ "${failures}" -ne 0 ]; then
  printf '\n::error::필수 검사 집계가 실패했다. 병합 조건을 충족하지 않는다.\n'
  exit 1
fi

printf '\n모든 예상 결과가 일치한다.\n'
exit 0
