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
# 기대치는 job마다 정한다. `docs-only` 분류가 모든 제품 job의 기대치를 정하고, 제품 변경일 때 백업·복원 리허설
# 두 job만 `backup-restore` 분류를 따로 본다(spec-ci-critical-path.md). `backup-restore=false`(PR의 변경이 전부
# 프런트엔드·문서 경로)면 두 job은 건너뛴 것이 정상이고, `true`나 빈 값이면 실행돼야 한다. 빈 값은 이 출력을
# 모르는 target base의 옛 분류기가 돈 PR이다 — 그 PR에서 두 job은 실제로 돌므로 `success`를 기대한다. 그 밖의 값은
# 분류가 깨진 것이라 `::error::`와 함께 실패로 세고 가장 엄격한 `success`를 기대한다.
#
# 다만 `changes`가 실패하면 분류 결과가 없어 나머지 job의 기대치를 정할 수 없다. 그때는 원인 하나만
# 보고하고 즉시 끝낸다. 계속 진행하면 정상적으로 건너뛰어진 job마다 오해를 부르는 `::error::`
# 줄이 더 붙는다.
#
# 정본: _bmad-output/implementation-artifacts/spec-1-2-same-origin-shell.md `## Tasks & Acceptance`
#       _bmad-output/implementation-artifacts/spec-ci-critical-path.md `## I/O & Edge-Case Matrix`
#       CI-PLAN.md §1(job 구조), §5(CI-D5 고정된 required gate 판정, 승인된 비적용)
#
# 입력 (환경변수)
#   DOCS_ONLY           -- `changes` job이 낸 분류 결과. `true`면 제품 job은 건너뛴 것이 정상이다.
#   BACKUP_RESTORE      -- `changes` job이 낸 백업·복원 분류. `false`면 백업·복원 리허설은 건너뛴 것이 정상이다.
#                          빈 값(옛 분류기)은 `true`와 같게 읽는다.
#   CHANGES_RESULT      -- `needs.changes.result`
#   FRONTEND_RESULT     -- `needs.frontend-quality.result`
#   BACKEND_RESULT      -- `needs.backend-quality.result`
#   INTEGRATION_RESULT  -- `needs.same-origin-integration.result`
#   REHEARSAL_RESULT    -- `needs.deploy-rehearsal.result`(Story 3.8 — 운영 배포 절차의 리허설)
#   BACKUP_RESULT       -- `needs.backup-rehearsal.result`(Story 3.9 — 백업 리허설, spec-ci-critical-path.md가 job을 분리)
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
backup_restore="${BACKUP_RESTORE:-}"
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
    echo "- \`backup-restore\`: \`${backup_restore:-<빈 값>}\`"
    echo ''
    echo '| job | 결과 | 기대 |'
    echo '|---|---|---|'
    printf '%s' "${summary_rows}"
  } >>"${GITHUB_STEP_SUMMARY}"
}

add_row 'changes' "${changes_result}" 'success'

# `changes`는 어떤 분류에서도 반드시 성공해야 한다. 이 job이 분류를 만들기 때문이다.
# 여기가 실패하면 아래 job의 결과는 어떤 값이든 의미가 없으므로 원인 하나만 보고하고 끝낸다.
#
# 계속 진행하면 `DOCS_ONLY`가 빈 값이라 기대치가 `success`로 굳고, 정상적으로 건너뛰어진 job이
# 각각 "건너뛰어졌지만 제품 변경으로 분류됐다"는 `::error::`를 낸다. 진짜 원인 하나에 오해를
# 부르는 줄이 붙어 로그를 읽는 사람을 분류·게이트 불일치라는 없는 문제로 보낸다.
if [ "${changes_result}" != 'success' ]; then
  printf '::error::changes job이 success가 아니다 (실제 %s). 분류 결과가 없으므로 나머지 job은 판정하지 않는다. 이 실행의 원인은 changes job 하나다.\n' \
    "${changes_result:-<빈 값>}"
  add_row 'frontend-quality' "${FRONTEND_RESULT:-}" '판정 불가'
  add_row 'backend-quality' "${BACKEND_RESULT:-}" '판정 불가'
  add_row 'same-origin-integration' "${INTEGRATION_RESULT:-}" '판정 불가'
  add_row 'deploy-rehearsal' "${REHEARSAL_RESULT:-}" '판정 불가'
  add_row 'backup-rehearsal' "${BACKUP_RESULT:-}" '판정 불가'
  add_row 'restore-rehearsal' "${RESTORE_RESULT:-}" '판정 불가'
  write_summary
  printf '\n::error::필수 검사 집계가 실패했다. 병합 조건을 충족하지 않는다.\n'
  exit 1
fi

# 분류 결과가 두 가지 중 하나가 아니면 그 자체가 실패다. 오타 하나로 전체 판정이
# "제품 변경" 쪽으로 조용히 넘어가거나 그 반대가 되는 일을 막는다.
#
# `basis`는 기대가 어긋났을 때 `::error::` 문장에 넣는 분류 이름이다(조사까지 포함한다).
case "${docs_only}" in
  true)
    expected='skipped'
    basis='문서-only로'
    ;;
  false)
    expected='success'
    basis='제품 변경으로'
    ;;
  *)
    printf '::error::DOCS_ONLY가 true·false가 아니다 (실제 %s).\n' "${docs_only:-<빈 값>}"
    # 판정 불가는 fail-closed다. 나머지 job에는 가장 엄격한 기대치를 적용한다.
    expected='success'
    basis='제품 변경으로'
    failures=$((failures + 1))
    ;;
esac

# 백업·복원 리허설 두 job의 기대치. 빈 값은 옛 분류기(이 출력을 모르는 target base)이고 두 job은 실제로 돈다.
case "${backup_restore}" in
  true)
    backup_restore_expected='success'
    backup_restore_basis='백업·복원 적용(backup-restore=true)으로'
    ;;
  '')
    # 안내는 이 값이 기대치를 정하는 제품 변경에서만 낸다. 문서-only 로그에 무관한 줄을 섞지 않는다.
    if [ "${docs_only}" = false ]; then
      printf '::notice::백업·복원 분류가 비었다 — 이 출력을 모르는 옛 분류기로 판정했다. 빈 값은 true와 같게 읽는다(fail-closed).\n'
    fi
    backup_restore_expected='success'
    backup_restore_basis='백업·복원 적용(backup-restore 빈 값 — 옛 분류기)으로'
    ;;
  false)
    backup_restore_expected='skipped'
    backup_restore_basis='백업·복원 비적용(backup-restore=false)으로'
    ;;
  *)
    printf '::error::BACKUP_RESTORE가 true·false·빈 값이 아니다 (실제 %s).\n' "${backup_restore}"
    backup_restore_expected='success'
    backup_restore_basis='백업·복원 적용(판정 불가 — fail-closed)으로'
    failures=$((failures + 1))
    ;;
esac

# 문서-only면 백업·복원 분류와 무관하게 모든 제품 job이 건너뛴다. `docs-only`가 깨졌으면 가장 엄격한 `success`다.
# 백업·복원 분류는 제품 변경일 때만 두 job의 기대치를 바꾼다.
if [ "${docs_only}" = false ]; then
  backup_expected="${backup_restore_expected}"
  backup_basis="${backup_restore_basis}"
else
  backup_expected="${expected}"
  backup_basis="${basis}"
fi

# check_job <job 이름> <실제 결과> <기대> <분류 이름(조사 포함)>
check_job() {
  local name="$1" result="${2:-}" want="$3" why="$4"

  add_row "${name}" "${result}" "${want}"

  if [ "${result}" = "${want}" ]; then
    printf '  통과  %s = %s\n' "${name}" "${result}"
    return
  fi

  printf '  실패  %s = %s (기대 %s)\n' "${name}" "${result:-<빈 값>}" "${want}"
  case "${result}" in
    '')
      printf '::error::%s job의 결과가 비었다. job이 실행되지 않았거나 needs에서 빠졌다.\n' "${name}"
      ;;
    skipped)
      printf '::error::%s job이 건너뛰어졌지만 이 변경은 %s 분류됐다.\n' "${name}" "${why}"
      ;;
    success)
      printf '::error::%s job이 실행됐지만 이 변경은 %s 분류됐다. 분류와 게이트가 어긋난다.\n' "${name}" "${why}"
      ;;
    *)
      printf '::error::%s job이 %s로 끝났다.\n' "${name}" "${result}"
      ;;
  esac
  failures=$((failures + 1))
}

printf '필수 검사 집계 (docs-only=%s, backup-restore=%s, 기대 결과=%s, 백업·복원 리허설 기대=%s)\n' \
  "${docs_only:-<빈 값>}" "${backup_restore:-<빈 값>}" "${expected}" "${backup_expected}"

check_job 'frontend-quality' "${FRONTEND_RESULT:-}" "${expected}" "${basis}"
check_job 'backend-quality' "${BACKEND_RESULT:-}" "${expected}" "${basis}"
check_job 'same-origin-integration' "${INTEGRATION_RESULT:-}" "${expected}" "${basis}"
check_job 'deploy-rehearsal' "${REHEARSAL_RESULT:-}" "${expected}" "${basis}"
check_job 'backup-rehearsal' "${BACKUP_RESULT:-}" "${backup_expected}" "${backup_basis}"
check_job 'restore-rehearsal' "${RESTORE_RESULT:-}" "${backup_expected}" "${backup_basis}"

write_summary

if [ "${failures}" -ne 0 ]; then
  printf '\n::error::필수 검사 집계가 실패했다. 병합 조건을 충족하지 않는다.\n'
  exit 1
fi

printf '\n모든 예상 결과가 일치한다.\n'
exit 0
