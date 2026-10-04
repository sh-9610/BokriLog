#!/usr/bin/env bash
# 프런트엔드 시험 부하 반복.
#
# 왜 있는가: CI `frontend-quality`의 Vitest가 가끔 붉게 나오는데 로컬 8코어에서는 거의 재현되지
# 않았다. 원인은 effect 시점이었고(`_bmad-output/implementation-artifacts/spec-frontend-effect-timing.md`)
# 그 종류의 결함은 CPU가 붐벼 React Scheduler가 프레임을 넘겨 양보할 때만 드러난다. 이 스크립트는 그
# 부하를 로컬에서 만들고 같은 시험을 여러 번 돌린다. 부하 없이 한 번 통과한 것을 "고쳐졌다"로 읽지
# 않기 위해서다.
#
# 실행: bash frontend/scripts/stress-test.sh [--runs N] [--load N] [-- <Vitest 인자>...]
#
#   --runs N  Vitest 실행 횟수. 기본 12.
#   --load N  부하 프로세스(`yes > /dev/null`) 수. 기본 CPU 수 × 2.
#   --        뒤의 인자는 그대로 `vitest run`에 넘긴다.
#             예: bash frontend/scripts/stress-test.sh --runs 30 -- src/components/InitialInputsCollection.test.tsx
#
# 횟수는 하한이 아니라 기준선의 실패율에 맞춘다. 기본 12회는 최소다 — 고치기 전 기준선이 12회 중 1~2회
# 실패하는 결함이라면 깨끗한 12회만으로는 약한 근거다. 그만큼 늘려 돌리거나 결함을 결정적으로 잡는
# 시험(첫 관측 가드, `DEVELOPMENT.md`의 `시험의 대기`)과 함께 쓴다.
#
# 전제
#   - Node major가 `frontend/.nvmrc`와 같다. 다르면 시험을 돌리지 않는다 — Node 25에서는 Vitest가
#     내장 `localStorage` 때문에 실패하고, 그 실패를 부하 탓으로 읽게 된다.
#   - `npm --prefix frontend ci`로 의존이 설치돼 있다.
#
# 종료 코드
#   0 -- 모든 실행 통과
#   1 -- 실패한 실행이 하나 이상. 실패한 회차와 그 회차의 실패 시험 이름을 요약하고 로그를 남긴다.
#   2 -- 준비 실패. 시험이 돌지 않았거나(Node·의존·인자) Vitest가 시험 파일을 하나도 찾지 못했다(필터
#        오타). "0회 실패"를 "문제 없음"으로 읽지 않기 위해 구분한다.
#   중단(Ctrl-C·TERM·HUP)은 신호 코드(130·143·129)로 끝난다.
#
# 정리. 정상 종료·실패·INT·TERM·HUP에서는 트랩이 도는 중인 Vitest와 부하 프로세스를 끝낸다. 비대화형
# 셸이 띄운 백그라운드 명령은 SIGINT를 무시하므로 Ctrl-C가 그 프로세스들에 닿지 않는다 — 트랩이 직접
# 끝낸다. SIGKILL(`kill -9`)은 트랩을 거치지 않으므로 부하가 남는다. 그때는 `pkill -x yes`로 지운다.

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
frontend_dir="$(cd "${script_dir}/.." && pwd -P)"

runs=12
load=''
vitest_args=()

usage() {
  cat <<'USAGE'
실행: bash frontend/scripts/stress-test.sh [--runs N] [--load N] [-- <Vitest 인자>...]

  --runs N  Vitest 실행 횟수. 기본 12(최소다 — 기준선의 실패율에 맞춰 늘린다).
  --load N  부하 프로세스(`yes > /dev/null`) 수. 기본 CPU 수 × 2.
  --        뒤의 인자는 그대로 `vitest run`에 넘긴다.
USAGE
}

fail_setup() {
  printf '\n부하 반복 준비 실패: %s\n' "$1" >&2
  if [ -n "${2:-}" ]; then
    printf '%s\n' "$2" >&2
  fi
  exit 2
}

# 1 이상의 10진 정수인가.
is_count() {
  case "$1" in
    '' | *[!0-9]*) return 1 ;;
  esac
  [ "$((10#$1))" -gt 0 ]
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --runs | --load)
      [ "$#" -ge 2 ] || fail_setup "$1에 값이 없다."
      is_count "$2" || fail_setup "$1의 값은 1 이상의 정수여야 한다: '$2'"
      # `08`처럼 0으로 시작하는 값을 산술이 8진수로 읽지 않게 10진수로 고정한다.
      if [ "$1" = '--runs' ]; then runs=$((10#$2)); else load=$((10#$2)); fi
      shift 2
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    --)
      shift
      vitest_args=("$@")
      break
      ;;
    *)
      fail_setup "알 수 없는 인자다: '$1'" "$(usage)"
      ;;
  esac
done

# --------------------------------------------------------------------------
# 준비
# --------------------------------------------------------------------------

nvmrc="${frontend_dir}/.nvmrc"
[ -f "${nvmrc}" ] || fail_setup "Node 버전 고정 파일이 없다: ${nvmrc}"
expected_major="$(tr -d ' \t\r\n' <"${nvmrc}")"
expected_major="${expected_major#v}"
expected_major="${expected_major%%.*}"
is_count "${expected_major}" || fail_setup "${nvmrc}에서 Node major를 읽지 못했다."

command -v node >/dev/null 2>&1 || fail_setup 'node를 찾지 못했다.'
node_version="$(node --version 2>/dev/null)" || fail_setup 'node --version이 실패했다.'
node_major="${node_version#v}"
node_major="${node_major%%.*}"
[ "${node_major}" = "${expected_major}" ] || fail_setup \
  "Node ${node_version}이다. frontend/.nvmrc가 고정한 major는 ${expected_major}다." \
  '`fnm use`·`nvm use` 등으로 맞춘 뒤 다시 실행한다.'

vitest_bin="${frontend_dir}/node_modules/.bin/vitest"
[ -x "${vitest_bin}" ] || fail_setup "Vitest가 없다: ${vitest_bin}" \
  '먼저 `npm --prefix frontend ci`를 실행한다.'

command -v yes >/dev/null 2>&1 || fail_setup '부하를 만들 `yes`를 찾지 못했다.'

if [ -z "${load}" ]; then
  # `getconf`는 macOS와 Linux 모두에 있다. `nproc`은 GNU 전용이라 쓰지 않는다.
  cpus="$(getconf _NPROCESSORS_ONLN 2>/dev/null)" || cpus=''
  if ! is_count "${cpus}"; then
    cpus="$(sysctl -n hw.ncpu 2>/dev/null)" || cpus=''
  fi
  is_count "${cpus}" || fail_setup 'CPU 수를 알아내지 못했다.' '`--load N`으로 부하 프로세스 수를 직접 준다.'
  load=$((cpus * 2))
fi

# 어느 트리를 쟀는지. git이 없거나 저장소 밖이면 조용히 건너뛴다.
revision=''
if command -v git >/dev/null 2>&1; then
  commit="$(git -C "${frontend_dir}" rev-parse --short HEAD 2>/dev/null)" || commit=''
  if [ -n "${commit}" ]; then
    changes="$(git -C "${frontend_dir}" status --porcelain 2>/dev/null)" || changes=''
    if [ -n "${changes}" ]; then
      revision="${commit}(커밋되지 않은 변경 있음)"
    else
      revision="${commit}"
    fi
  fi
fi

# Vitest는 작업 디렉터리를 프로젝트 루트로 쓴다(`lint-policy.test.ts`도 그 경로를 읽는다).
cd "${frontend_dir}" || fail_setup "작업 디렉터리를 옮기지 못했다: ${frontend_dir}"

tmp_root="${TMPDIR:-/tmp}"
work_dir="$(mktemp -d "${tmp_root%/}/bokrilog-stress.XXXXXX")" ||
  fail_setup '임시 디렉터리를 만들지 못했다.'
[ -d "${work_dir}" ] || fail_setup "mktemp -d가 쓸 수 없는 경로를 냈다: '${work_dir}'"

# --------------------------------------------------------------------------
# 부하와 정리
# --------------------------------------------------------------------------

load_pids=()
vitest_pid=''
keep_logs=false
cleaned_up=false

stop_vitest() {
  if [ -z "${vitest_pid}" ]; then
    return
  fi
  # 작업 프로세스(Vitest의 fork)를 먼저, 그다음 본체를 끝낸다. 본체만 끝내면 작업 프로세스가 고아로 남을
  # 수 있다.
  pkill -TERM -P "${vitest_pid}" 2>/dev/null || true
  kill -TERM "${vitest_pid}" 2>/dev/null || true
  wait "${vitest_pid}" 2>/dev/null || true
  vitest_pid=''
}

stop_load() {
  local pid
  for pid in ${load_pids[@]+"${load_pids[@]}"}; do
    kill "${pid}" 2>/dev/null || true
  done
  # 띄웠지만 아직 목록에 들지 못한 부하(`&` 직후에 신호가 온 경우)도 이 셸의 자식이다.
  pkill -P "$$" -x yes 2>/dev/null || true
  for pid in ${load_pids[@]+"${load_pids[@]}"}; do
    wait "${pid}" 2>/dev/null || true
  done
  load_pids=()
}

cleanup() {
  # EXIT와 INT·TERM·HUP 두 경로에서 모두 불릴 수 있다. 두 번 도는 것을 막는다.
  if [ "${cleaned_up}" = true ]; then
    return
  fi
  cleaned_up=true
  stop_vitest
  stop_load
  # 목록 밖에서 끝낸 자식까지 거둔다. 남은 자식은 모두 끝났으므로 바로 돌아온다.
  wait 2>/dev/null || true
  if [ "${keep_logs}" != true ]; then
    rm -rf "${work_dir}"
  elif [ "${1:-}" = 'interrupted' ]; then
    printf '\n중단됐다. 실패한 회차의 로그: %s\n' "${work_dir}" >&2
  fi
}

# 신호마다 트랩을 둔다. Vitest와 부하는 백그라운드라 Ctrl-C가 닿지 않고, 스크립트는 그 실행을 `wait`로
# 기다리므로 신호가 오면 그 자리에서 트랩이 돈다(실행 하나가 끝나기를 기다리지 않는다). 신호에서는 정리한
# 뒤 그 신호의 코드로 바로 끝낸다 — 트랩에서 되돌아가면 남은 회차가 부하 없이 돌아 "부하 아래 통과"를
# 거짓으로 보고한다.
trap 'cleanup' EXIT
trap 'cleanup interrupted; exit 130' INT
trap 'cleanup interrupted; exit 143' TERM
trap 'cleanup interrupted; exit 129' HUP

start_load() {
  local started=0
  while [ "${started}" -lt "${load}" ]; do
    yes >/dev/null &
    load_pids+=("$!")
    started=$((started + 1))
  done
}

# --------------------------------------------------------------------------
# 반복
# --------------------------------------------------------------------------

printf '프런트엔드 시험 부하 반복\n'
if [ -n "${revision}" ]; then
  printf '  커밋 %s\n' "${revision}"
fi
printf '  Node %s · 부하 프로세스 %s개 · %s회' "${node_version}" "${load}" "${runs}"
if [ "${#vitest_args[@]}" -gt 0 ]; then
  printf ' · vitest run %s' "${vitest_args[*]}"
fi
printf '\n\n'

start_load

failed_runs=0
# 회차별 판정. 실패한 회차만 아래 요약이 다시 읽는다.
verdicts=()
# 실패한 시험 이름. 한 줄에 `<회차><탭><파일 > 묶음 > 시험>`이다.
failures="${work_dir}/failures.txt"
: >"${failures}"
run=1
while [ "${run}" -le "${runs}" ]; do
  log="${work_dir}/run-${run}.log"
  started_at="$(date +%s)"
  # 색 없이 남긴다. 실패 시험 이름을 줄 머리(` FAIL `)로 찾는다. 백그라운드로 띄우고 기다리는 것은 신호가
  # 오면 실행이 끝나기 전에 트랩이 돌게 하기 위해서다.
  NO_COLOR=1 "${vitest_bin}" run ${vitest_args[@]+"${vitest_args[@]}"} >"${log}" 2>&1 &
  vitest_pid=$!
  wait "${vitest_pid}"
  status=$?
  vitest_pid=''
  if [ "${status}" -eq 0 ]; then
    verdicts+=('통과')
  else
    if grep -F 'No test files found' "${log}" >/dev/null; then
      fail_setup 'Vitest가 시험 파일을 하나도 찾지 못했다. `--` 뒤의 필터를 확인한다.' \
        "$(tail -5 "${log}")"
    fi
    verdicts+=('실패')
    failed_runs=$((failed_runs + 1))
    # 실패를 조사할 수 있게 로그를 남긴다. 뒤에서 중단되더라도 지우지 않는다.
    keep_logs=true
    # Vitest 요약의 ` FAIL  <파일> > <묶음> > <시험>` 줄이다. 이름이 없는 실패(시험 밖의 오류)는
    # 아무것도 더하지 않고, 그 회차는 아래 요약이 따로 다룬다.
    awk -v run="${run}" '/^ ?FAIL / { sub(/^ ?FAIL +/, ""); print run "\t" $0 }' "${log}" \
      >>"${failures}"
  fi
  tests_line="$(grep -E '^ +Tests ' "${log}" | tail -1 | sed -E 's/^ +//')"
  printf '  %3d/%s  %s  %4ss  %s\n' "${run}" "${runs}" "${verdicts[$((run - 1))]}" \
    "$(($(date +%s) - started_at))" "${tests_line:-(시험 요약 없음)}"
  run=$((run + 1))
done

stop_load

printf '\n%s회 중 %s회 통과, %s회 실패\n' "${runs}" "$((runs - failed_runs))" "${failed_runs}"

if [ "${failed_runs}" -eq 0 ]; then
  exit 0
fi

printf '\n실패한 회차\n'
run=1
while [ "${run}" -le "${runs}" ]; do
  if [ "${verdicts[$((run - 1))]}" = '실패' ]; then
    printf '  %s회차 (%s)\n' "${run}" "${work_dir}/run-${run}.log"
    names="$(awk -F '\t' -v run="${run}" '$1 == run { print "      " $2 }' "${failures}")"
    if [ -n "${names}" ]; then
      printf '%s\n' "${names}"
    else
      # 시험 밖의 실패다(처리되지 않은 오류, 수집 실패, 프로세스 종료). 로그 끝을 보인다.
      printf '      실패한 시험 이름이 없다. 로그 끝:\n'
      tail -5 "${work_dir}/run-${run}.log" | sed 's/^/        /'
    fi
  fi
  run=$((run + 1))
done

if [ -s "${failures}" ]; then
  printf '\n시험별 실패 횟수\n'
  cut -f 2 "${failures}" | sort | uniq -c | sort -rn | sed -E 's/^ */  /'
fi

exit 1
