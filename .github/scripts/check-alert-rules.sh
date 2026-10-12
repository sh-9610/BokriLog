#!/usr/bin/env bash
# 경보 규칙 시험(Story 3.7 AC-2·AC-5).
#
# `deploy/observability/alerts.yml`(Prometheus 규칙)을 세 겹으로 판정한다.
#   1. 형식 계약 -- 규칙마다 `for:`·`severity`·`group` label이 있고, `strong_signal` label은 승인된 강한 신호 경보에만
#                  `"true"`로 있다. 일반 경보가 자동 정지로 이어지는 label을 달고 들어오면 여기서 막힌다.
#   2. 문법       -- `promtool check rules`.
#   3. 동작       -- `promtool test rules alerts.test.yml`. 임계값의 경계(직전 미발화·직후 발화), 지속시간, 복구, 미측정
#                  경보를 가상 시계로 고정한다.
#
# promtool은 CI·개발 전용 도구다. 운영 이미지·jar·서버에 들어가지 않는다. 고정 버전의 Prometheus 배포본을 받아 SHA-256을
# 대조해 캐시에 두고, 쓸 때마다 다시 대조한 뒤 그 안의 promtool만 꺼내 쓴다. 대조가 맞지 않으면 쓰지 않는다.
#
# 실행: bash .github/scripts/check-alert-rules.sh
# 환경
#   PROMTOOL           -- 이 경로의 promtool을 쓴다(내려받지 않는다). 실행할 수 없으면 준비 실패다. 계약 시험이 가짜
#                         promtool로 세 종료 경로를 단정하는 데 쓴다.
#   PROMTOOL_CACHE_DIR -- 내려받은 배포본의 캐시. 기본 `${XDG_CACHE_HOME:-$HOME/.cache}/bokrilog/promtool-<버전>`. 쓸 때마다
#                         SHA-256을 다시 대조하고 promtool은 매번 임시 디렉터리로 꺼낸다.
#   ALERT_RULES_DIR    -- 규칙 디렉터리. 기본 `deploy/observability`.
# 종료 코드
#   0 -- 형식·문법·단위시험 모두 통과
#   1 -- 판정 실패(형식 계약 위반, 문법 오류, 단위시험 실패)
#   2 -- 준비 실패(규칙 파일 없음, promtool을 얻지 못함 — 지원하지 않는 플랫폼·내려받기 실패·checksum 불일치,
#        promtool 실행 불가 126·127)
#
# bash 3.2 호환이고 GNU 전용 플래그를 쓰지 않는다. `pipefail` 아래에서 조기 종료하는 grep 파이프를 조건에 쓰지 않는다(PR #9).

set -uo pipefail

PROMETHEUS_VERSION='3.5.0'

# 승인된 강한 신호 경보(공백으로 구분). 비어 있다 — AC-2의 경보는 모두 일반 경보다. 이름을 더하는 것은 판정표
# `OPS-SIGNAL-1.0`의 신호와 잇는 결정이며 SPEC이 승인한다.
approved_strong_signal_alerts=''

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd "${script_dir}/../.." && pwd -P)"
rules_dir="${ALERT_RULES_DIR:-${repo_root}/deploy/observability}"
rules_file="${rules_dir}/alerts.yml"
tests_file="${rules_dir}/alerts.test.yml"

fail_setup() {
  printf '::error::경보 규칙 시험 준비 실패: %s\n' "$1" >&2
  if [ -n "${2:-}" ]; then
    printf '%s\n' "$2" >&2
  fi
  exit 2
}

fail_check() {
  printf '::error::경보 규칙 시험 실패: %s\n' "$1" >&2
  if [ -n "${2:-}" ]; then
    printf '%s\n' "$2" >&2
  fi
  exit 1
}

[ -f "${rules_file}" ] || fail_setup "규칙 파일이 없다: ${rules_file}"
[ -f "${tests_file}" ] || fail_setup "단위시험 파일이 없다: ${tests_file}"

# --------------------------------------------------------------------------
# promtool 준비
# --------------------------------------------------------------------------

# expected_sha256 <배포본 이름>. 공식 `sha256sums.txt`(v3.5.0)의 값이다. 버전을 올릴 때 넷을 함께 바꾼다.
expected_sha256() {
  case "$1" in
    "prometheus-${PROMETHEUS_VERSION}.linux-amd64.tar.gz")
      printf '%s' 'e811827af26d822afb09a4f28314f61b618b12cff5369835a67f674d8b46f39a' ;;
    "prometheus-${PROMETHEUS_VERSION}.linux-arm64.tar.gz")
      printf '%s' '173389cc42bf09c4e6e54cb53fa07a5a835d7c261e14775d2183181d6e385d1c' ;;
    "prometheus-${PROMETHEUS_VERSION}.darwin-amd64.tar.gz")
      printf '%s' '5856d0c306e7d852d9a79788b20acb630501b3fab120b69f5317d6b0732dea57' ;;
    "prometheus-${PROMETHEUS_VERSION}.darwin-arm64.tar.gz")
      printf '%s' 'c17bc2c992f0b5515eebe2b4ccd626214f8e309a0fe6ec40ed13c42ec13d05fb' ;;
    *) printf '' ;;
  esac
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    printf ''
  fi
}

resolve_promtool() {
  if [ -n "${PROMTOOL:-}" ]; then
    [ -f "${PROMTOOL}" ] && [ -x "${PROMTOOL}" ] || fail_setup "PROMTOOL이 실행할 수 있는 파일이 아니다: ${PROMTOOL}"
    promtool="${PROMTOOL}"
    return
  fi

  local os arch
  case "$(uname -s)" in
    Linux) os='linux' ;;
    Darwin) os='darwin' ;;
    *) fail_setup "지원하지 않는 OS다: $(uname -s)" 'PROMTOOL에 promtool 경로를 주면 그것을 쓴다.' ;;
  esac
  case "$(uname -m)" in
    x86_64 | amd64) arch='amd64' ;;
    arm64 | aarch64) arch='arm64' ;;
    *) fail_setup "지원하지 않는 아키텍처다: $(uname -m)" 'PROMTOOL에 promtool 경로를 주면 그것을 쓴다.' ;;
  esac

  local archive="prometheus-${PROMETHEUS_VERSION}.${os}-${arch}.tar.gz"
  local expected
  expected="$(expected_sha256 "${archive}")"
  [ -n "${expected}" ] || fail_setup "고정 checksum이 없는 배포본이다: ${archive}"
  command -v tar >/dev/null 2>&1 || fail_setup 'tar가 없어 promtool을 꺼낼 수 없다.'

  # 캐시는 배포본 자체다. 쓸 때마다 SHA-256을 다시 대조하고 promtool은 매번 임시 디렉터리로 꺼낸다 — 캐시가 바뀌었으면
  # 쓰지 않고 다시 받는다. 받은 파일은 같은 디렉터리의 임시 이름에 쓴 뒤 rename으로 원자 저장한다.
  local cache_dir="${PROMTOOL_CACHE_DIR:-${XDG_CACHE_HOME:-${HOME}/.cache}/bokrilog/promtool-${PROMETHEUS_VERSION}}"
  local cached="${cache_dir}/${archive}"
  work="$(mktemp -d)" || fail_setup '임시 디렉터리를 만들지 못했다.'
  # shellcheck disable=SC2064
  trap "rm -rf '${work}'" EXIT

  local actual=''
  if [ -f "${cached}" ]; then
    actual="$(sha256_of "${cached}")"
    [ -n "${actual}" ] || fail_setup 'sha256sum·shasum이 없어 checksum을 대조할 수 없다.'
  fi
  if [ "${actual}" != "${expected}" ]; then
    command -v curl >/dev/null 2>&1 || fail_setup 'curl이 없어 promtool을 내려받을 수 없다.'
    mkdir -p "${cache_dir}" || fail_setup "캐시 디렉터리를 만들지 못했다: ${cache_dir}"
    local partial="${cached}.partial.$$"
    local url="https://github.com/prometheus/prometheus/releases/download/v${PROMETHEUS_VERSION}/${archive}"
    printf 'promtool %s 내려받기: %s\n' "${PROMETHEUS_VERSION}" "${url}"
    if ! curl --fail --silent --show-error --location --retry 2 --max-time 60 --output "${partial}" "${url}" \
      2>"${work}/curl.log"; then
      rm -f "${partial}"
      fail_setup 'promtool 배포본을 내려받지 못했다.' "$(cat "${work}/curl.log" 2>/dev/null)"
    fi
    actual="$(sha256_of "${partial}")"
    if [ -z "${actual}" ]; then
      rm -f "${partial}"
      fail_setup 'sha256sum·shasum이 없어 checksum을 대조할 수 없다.'
    fi
    if [ "${actual}" != "${expected}" ]; then
      rm -f "${partial}"
      fail_setup "checksum이 맞지 않는다: ${archive}" "기대 ${expected}, 실제 ${actual}"
    fi
    mv -f "${partial}" "${cached}" || fail_setup "배포본을 캐시에 두지 못했다: ${cached}"
  fi

  if ! tar -xzf "${cached}" -C "${work}" "prometheus-${PROMETHEUS_VERSION}.${os}-${arch}/promtool" \
    2>"${work}/tar.log"; then
    fail_setup 'promtool을 배포본에서 꺼내지 못했다.' "$(cat "${work}/tar.log" 2>/dev/null)"
  fi
  promtool="${work}/prometheus-${PROMETHEUS_VERSION}.${os}-${arch}/promtool"
  [ -x "${promtool}" ] || fail_setup "꺼낸 promtool을 실행할 수 없다: ${promtool}"
}

# --------------------------------------------------------------------------
# 1. 형식 계약
# --------------------------------------------------------------------------

# 규칙 블록(`      - alert: <이름>`)마다 for·severity·group을 확인하고 strong_signal label을 모은다. 들여쓰기는 이 저장소의
# alerts.yml 형식을 그대로 따른다(규칙 6칸, 속성 8칸, label 10칸). 출력은 위반 한 줄씩이고 없으면 비어 있다.
contract_violations="$(awk -v approved=" ${approved_strong_signal_alerts} " '
  function close_rule() {
    if (name == "") return
    if (!has_for) print name ": for: 지속시간이 없다"
    if (!has_severity) print name ": severity label(info|warning|critical)이 없다"
    if (!has_group) print name ": group label이 없다"
    if (strong != "") {
      if (strong != "\"true\"") print name ": strong_signal 값은 \"true\"만 쓴다(지금 " strong ")"
      else if (index(approved, " " name " ") == 0) print name ": 승인되지 않은 강한 신호 경보다"
    }
    count++
  }
  # 다른 들여쓰기의 규칙 머리는 이 검사가 읽지 못하는 형식이다. 조용히 건너뛰지 않고 거절한다.
  /^[[:space:]]*-[[:space:]]*alert:/ && !/^      - alert: / { print NR ": 규칙 머리(- alert:)의 들여쓰기는 6칸이어야 한다"; next }
  # flow 표기(`labels: {…}`)는 아래 줄 단위 검사를 비켜 간다. 블록 표기만 받는다.
  /^[[:space:]]*(labels|annotations):[[:space:]]*[^[:space:]#]/ { print NR ": labels·annotations는 블록 표기만 쓴다"; next }
  /^      - alert: / {
    close_rule()
    name = substr($0, length("      - alert: ") + 1)
    has_for = 0; has_severity = 0; has_group = 0; strong = ""; section = ""
    next
  }
  /^      - record: / { close_rule(); name = ""; section = ""; next }
  name != "" && /^        labels:[[:space:]]*$/ { section = "labels"; next }
  name != "" && /^        annotations:[[:space:]]*$/ { section = "annotations"; next }
  # 규칙 속성(8칸)이 오면 label·annotation 구간이 끝난다.
  name != "" && /^        [^ ]/ { section = "" }
  name != "" && /^        for: [0-9]+[smhd]$/ { has_for = 1 }
  name != "" && section == "labels" && /^          severity: (info|warning|critical)$/ { has_severity = 1 }
  name != "" && section == "labels" && /^          group: [a-z_]+$/ { has_group = 1 }
  name != "" && section == "labels" && /^          strong_signal: / {
    strong = substr($0, length("          strong_signal: ") + 1)
  }
  END {
    close_rule()
    if (count == 0) print "경보 규칙이 하나도 없다"
  }
' "${rules_file}")" || fail_setup 'awk로 규칙 파일을 읽지 못했다.'

if [ -n "${contract_violations}" ]; then
  fail_check '규칙 형식 계약을 어겼다.' "${contract_violations}"
fi
printf '형식 계약 통과\n'

# --------------------------------------------------------------------------
# 2·3. promtool
# --------------------------------------------------------------------------

promtool=''
work=''
resolve_promtool

# run_promtool <설명> <인자...>. promtool을 규칙 디렉터리에서 실행한다(규칙 경로를 시험 파일 기준으로 푼다). 126·127(실행 불가·
# 명령 없음)은 판정이 아니라 준비 실패(2)다 — 판정 실패(1)와 섞이면 "규칙이 틀렸다"로 읽힌다.
run_promtool() {
  local description="$1" status
  shift
  (cd "${rules_dir}" && "${promtool}" "$@")
  status=$?
  if [ "${status}" -eq 126 ] || [ "${status}" -eq 127 ]; then
    fail_setup "promtool을 실행하지 못했다(종료 코드 ${status}): ${promtool}"
  fi
  if [ "${status}" -ne 0 ]; then
    fail_check "${description}이 실패했다."
  fi
}

run_promtool '규칙 문법 검사(promtool check rules)' check rules alerts.yml
run_promtool '경보 단위시험(promtool test rules)' test rules alerts.test.yml
printf '경보 규칙 시험 통과\n'
exit 0
