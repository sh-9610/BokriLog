#!/usr/bin/env bash
# 변경 분류.
#
# 이번 실행이 문서-only인지, 그리고 백업·복원 리허설이 필요한지 판정해 `GITHUB_OUTPUT`에 기록한다.
# 분류는 fail-closed다. 비교 기준이 없거나 규칙에 없는 경로가 하나라도 있으면 전체 검사를 실행한다.
#
# 정본: _bmad-output/implementation-artifacts/spec-ci-foundation.md `## I/O & Edge-Case Matrix`
#       _bmad-output/implementation-artifacts/spec-ci-critical-path.md `## I/O & Edge-Case Matrix`(backup-restore)
#       CI-PLAN.md §5 (분류 표와 rename 규칙, 승인된 비적용)
#
# 입력 (환경변수)
#   GITHUB_EVENT_NAME    -- pull_request | push | workflow_dispatch
#   GITHUB_SHA           -- 분류 대상 commit. PR에서는 검증된 merge commit이다.
#   EVENT_BEFORE         -- github.event.before. push에서만 의미가 있고 그 외에는 빈 문자열이다.
#   GITHUB_OUTPUT        -- 결과를 append할 파일 경로
#   GITHUB_STEP_SUMMARY  -- 선택. 있으면 판정 결과를 job summary에도 남긴다.
#
# 출력 (GITHUB_OUTPUT)
#   docs-only      -- `true` | `false`
#   reason         -- `docs-only` | `product-change` | `no-comparison-base` | `empty-diff`
#   backup-restore -- `true` | `false`. `false`는 PR의 변경이 전부 건너뛰기 목록(`frontend/**`, `_bmad-output/**`,
#                     최상위 `*.md`) 안일 때뿐이다. 그때 백업·복원 리허설은 승인된 비적용이다(CI-PLAN §5). 목록 밖 경로가
#                     하나라도 있거나, 비교 기준이 없거나, 빈 diff거나, git이 실패하거나, PR이 아닌 이벤트(push·
#                     workflow_dispatch — `develop` 통합 CI는 전체를 요구한다)면 `true`다. 관련 경로를 나열하는 허용 목록이
#                     아니라 건너뛰기 목록인 이유는 새 경로(예: 새 최상위 디렉터리)가 조용히 리허설을 건너뛰지 않게 하기 위해서다.
#
# 종료 코드는 항상 0이다. 판정 불가는 실패가 아니라 `no-comparison-base`로 표현하며
# 그 값이 전체 검사를 켠다. git 명령이 실패해도(해석 불가능한 SHA, 손상된 저장소)
# `set -e`로 죽지 않고 같은 fail-closed 값을 낸다. 죽으면 `GITHUB_OUTPUT`이 빈 채로
# 남아 뒤 단계의 `!= 'true'` 게이트가 우연히 통과하는 것과 구분되지 않는다.
#
# 이 계약은 .github/scripts/test-ci-scripts.sh가 고정한다.

set -euo pipefail

diff_file=''
cleanup() {
  if [ -n "${diff_file}" ]; then
    rm -f "${diff_file}"
  fi
}
trap cleanup EXIT

# emit <docs-only> <reason> <backup-restore>. 모든 판정 지점(조기 종료 포함)이 세 값을 함께 낸다.
emit() {
  echo "docs-only=$1" >>"${GITHUB_OUTPUT}"
  echo "reason=$2" >>"${GITHUB_OUTPUT}"
  echo "backup-restore=$3" >>"${GITHUB_OUTPUT}"
  echo "분류 결과: docs-only=$1 (사유: $2), backup-restore=$3"
  # PR 화면에서 제품 job·백업·복원 리허설이 왜 건너뛰어졌는지(또는 왜 전부 돌았는지) 보이게 한다.
  # 단계 로그를 펼치지 않고도 판정 근거가 남는다.
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
      echo "### 변경 분류"
      echo ""
      echo "- \`docs-only\`: \`$1\`"
      echo "- \`reason\`: \`$2\`"
      echo "- \`backup-restore\`: \`$3\`"
    } >>"${GITHUB_STEP_SUMMARY}"
  fi
}

base=''
case "${GITHUB_EVENT_NAME}" in
  pull_request)
    # 앞 단계가 부모를 검증한 merge commit이다. 첫 부모가 target base다.
    # 해석에 실패하면(검증 단계를 건너뛴 호출, root commit, 알 수 없는 SHA) 비교 기준이
    # 없는 것으로 본다. `set -e` 아래에서 그대로 두면 128로 죽는다.
    if ! base="$(git rev-parse --verify --quiet "${GITHUB_SHA}^1" 2>/dev/null)"; then
      base=''
    fi
    ;;
  push)
    if [ -n "${EVENT_BEFORE}" ] \
      && [ "${EVENT_BEFORE}" != "0000000000000000000000000000000000000000" ] \
      && git cat-file -e "${EVENT_BEFORE}^{commit}" 2>/dev/null; then
      base="${EVENT_BEFORE}"
    fi
    ;;
esac

# 분류는 fail-closed다. 비교 기준이 없으면(수동 진단, 브랜치 신규 생성,
# force push로 사라진 이전 commit) 전체 검사를 실행한다.
if [ -z "${base}" ]; then
  emit false no-comparison-base true
  exit 0
fi

if ! diff_file="$(mktemp)"; then
  diff_file=''
  echo "::warning::임시 파일을 만들지 못해 변경 목록을 읽을 수 없다."
  emit false no-comparison-base true
  exit 0
fi

# rename은 변경 전 경로와 변경 후 경로를 모두 평가해야 한다.
# rename 검출을 끄면 삭제 + 추가로 나뉘어 두 경로가 모두 목록에 남는다.
#
# `-z`는 경로를 NUL로 구분해 원본 바이트 그대로 낸다. `core.quotePath=false`는 비ASCII
# 경로를 C-quote(`"_bmad-output/\355\225\234\352\270\200.md"`)로 감싸지 않게 한다. 둘 중
# 하나라도 빠지면 한글 파일명 문서가 따옴표 붙은 한 줄로 들어와 `_bmad-output/*` 허용
# 규칙에 걸리지 않고 `product-change`로 오분류된다. 공백·개행이 든 경로도 같은 이유로
# 줄 단위로는 읽을 수 없다.
#
# 명령 치환은 NUL 바이트를 버리므로 결과를 파일로 받는다.
if ! git -c core.quotePath=false diff --no-renames -z --name-only \
  "${base}" "${GITHUB_SHA}" >"${diff_file}"; then
  echo "::warning::${base}..${GITHUB_SHA} 범위의 변경 목록을 얻지 못했다."
  emit false no-comparison-base true
  exit 0
fi

if [ ! -s "${diff_file}" ]; then
  emit false empty-diff true
  exit 0
fi

# 두 판정을 한 루프에서 모은다.
#   docs-only      -- 허용 목록 방식. 목록 밖 경로가 하나라도 있으면 제품 job 전체를 실행한다.
#                     `.github/workflows/**`, `frontend/**`, lockfile, script는 `*/*`에 걸려 문서-only가 될 수 없다.
#   backup-restore -- 건너뛰기 목록(`frontend/**`, `_bmad-output/**`, 최상위 `*.md`) 밖 경로가 하나라도 있으면 리허설을 돈다.
#                     `frontend/*`는 슬래시까지 맞춰야 한다 — `frontend-tools/x` 같은 이웃 디렉터리는 `*/*`로 떨어진다.
echo "변경 파일:"
docs_only=true
backup_skippable=true
while IFS= read -r -d '' path; do
  [ -n "${path}" ] || continue
  printf '  %s\n' "${path}"
  case "${path}" in
    _bmad-output/*) ;;
    frontend/*) docs_only=false ;;
    */*)
      docs_only=false
      backup_skippable=false
      ;;
    *.md) ;;
    *)
      docs_only=false
      backup_skippable=false
      ;;
  esac
done <"${diff_file}"

# 승인된 비적용은 PR에만 있다. `develop`·`main` push는 실제 merge commit의 통합 검증이라 전체를 요구한다(CI-PLAN §5).
backup_restore=true
if [ "${GITHUB_EVENT_NAME}" = pull_request ] && [ "${backup_skippable}" = true ]; then
  backup_restore=false
fi

if [ "${docs_only}" = true ]; then
  emit true docs-only "${backup_restore}"
else
  emit false product-change "${backup_restore}"
fi
