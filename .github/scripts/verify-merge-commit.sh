#!/usr/bin/env bash
# PR merge commit 검증.
#
# `github.sha`가 이벤트가 보고한 base·head를 정확히 그 순서로 부모에 두는
# merge commit인지 확인한다. 뒤이은 변경 분류가 `${GITHUB_SHA}^1`을 비교 기준으로
# 삼으므로, 이 검증이 실패한 상태로 분류를 진행하면 잘못된 범위를 검사하게 된다.
#
# 정본: _bmad-output/implementation-artifacts/spec-ci-foundation.md `## I/O & Edge-Case Matrix`
#       (`merge ref 불일치` 행)
#
# 입력 (환경변수)
#   GITHUB_SHA -- 검증할 merge commit. 이벤트가 고정한 값이다.
#   BASE_SHA   -- github.event.pull_request.base.sha
#   HEAD_SHA   -- github.event.pull_request.head.sha
#
# 출력
#   성공 -- 종료 코드 0, stdout에 확인 메시지 한 줄
#   실패 -- 종료 코드 1, stdout에 `::error::` 주석
#
# 해석 불가능한 `GITHUB_SHA`도 실패다. `git rev-list`를 그대로 두면 `set -e` 아래에서
# 128로 죽어 `::error::` 주석 없이 단계가 끝난다. 종료 1 + `::error::` 계약을 지키려면
# 실패를 붙잡아야 한다.
#
# 이 계약은 .github/scripts/test-ci-scripts.sh가 고정한다.

set -euo pipefail

if ! parents="$(git rev-list --parents -n 1 "${GITHUB_SHA}" 2>/dev/null)"; then
  echo "::error::${GITHUB_SHA}를 이 저장소의 commit으로 해석할 수 없다."
  exit 1
fi

read -r -a fields <<<"${parents}"

if [ "${#fields[@]}" -ne 3 ]; then
  echo "::error::${GITHUB_SHA}가 부모 2개인 merge commit이 아니다 (부모 $(( ${#fields[@]} - 1 ))개)."
  exit 1
fi

if [ "${fields[1]}" != "${BASE_SHA}" ] || [ "${fields[2]}" != "${HEAD_SHA}" ]; then
  echo "::error::merge commit의 부모가 이벤트의 base·head와 다르다."
  echo "  기대: base=${BASE_SHA} head=${HEAD_SHA}"
  echo "  실제: parent1=${fields[1]} parent2=${fields[2]}"
  exit 1
fi

echo "merge commit ${GITHUB_SHA} 검증 완료 (base=${BASE_SHA}, head=${HEAD_SHA})."
