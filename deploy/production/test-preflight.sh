#!/usr/bin/env bash
# 배포 gate의 고정 입력 시험(Story 3.8 AC-1·AC-2).
#
# 왜 있는가: `preflight.sh`의 판정이 배포 전체를 막거나 연다. 그 판정을 고정 fixture로 실제 실행해 거부 경로마다 종료 코드와
# 거부 code를 단정한다. Docker·cosign·네트워크가 필요 없다 — 두 도구는 PATH 앞의 가짜가 대신한다(이미지 아키텍처·서명 검사의
# 실제 실행은 운영 서버의 몫이다).
#
# 실행: bash deploy/production/test-preflight.sh
#
# 종료 코드
#   0 -- 모든 단정 통과
#   1 -- 단정 실패
#   2 -- 준비 실패
#
# fixture는 임시 디렉터리에만 만들고 끝나면 지운다. 64자 16진 값은 실행 시점에 만든다(자격정보 모양 리터럴을 커밋하지 않는다).

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
preflight="${script_dir}/preflight.sh"
compose_source="${script_dir}/compose.yaml"
host_example="${script_dir}/host-facts.env.example"
# shellcheck source=lib.sh
. "${script_dir}/lib.sh"

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

fail_setup() {
  printf '\n::error::시험 준비 실패: %s\n' "$1" >&2
  exit 2
}

for required in "${preflight}" "${compose_source}" "${host_example}"; do
  [ -f "${required}" ] || fail_setup "${required}가 없다."
done
have_sha256_tool || fail_setup 'sha256sum 또는 shasum이 없다.'

work="$(mktemp -d)" || fail_setup '임시 디렉터리를 만들지 못했다.'
[ -d "${work}" ] || fail_setup "mktemp -d가 쓸 수 없는 경로를 냈다: '${work}'"
trap 'rm -rf "${work}"' EXIT

# ---------------------------------------------------------------- 가짜 도구

fake_bin="${work}/bin"
mkdir -p "${fake_bin}" || fail_setup '가짜 도구 디렉터리를 만들지 못했다.'
cat >"${fake_bin}/docker" <<'FAKE' || fail_setup '가짜 docker를 쓰지 못했다.'
#!/usr/bin/env bash
# `docker image inspect --format … <image>`만 흉내 낸다.
if [ "${1:-}" = image ] && [ "${2:-}" = inspect ]; then
  [ "${FAKE_INSPECT_STATUS:-0}" = 0 ] || exit 1
  printf '%s\n' "${FAKE_PLATFORM:-linux/arm64}"
  exit 0
fi
exit 1
FAKE
write_fake_cosign() {
  cat >"${fake_bin}/cosign" <<'FAKE' || fail_setup '가짜 cosign을 쓰지 못했다.'
#!/usr/bin/env bash
# `cosign verify`(이미지 서명)와 `cosign verify-attestation --type spdxjson`(SBOM)을 따로 흉내 낸다. 받은 신원도 남긴다.
printf '%s\n' "$*" >>"${FAKE_COSIGN_LOG:-/dev/null}"
case "${1:-}" in
  verify) exit "${FAKE_COSIGN_STATUS:-0}" ;;
  verify-attestation) exit "${FAKE_ATTEST_STATUS:-0}" ;;
esac
exit 1
FAKE
  chmod +x "${fake_bin}/cosign"
}
write_fake_cosign
chmod +x "${fake_bin}/docker"

# 이 파일 시스템이 POSIX 권한을 표현하는가(Windows의 Git Bash는 아니다).
probe="${work}/mode-probe"
: >"${probe}"
chmod 600 "${probe}"
posix_modes=false
case "$(ls -ln "${probe}" | awk '{ print $1 }')" in
  -rw-------*) posix_modes=true ;;
esac

# ---------------------------------------------------------------- fixture

hex_of() {
  printf '%s' "$1" | (sha256sum 2>/dev/null || shasum -a 256) | awk '{ print $1 }'
}

image_digest="$(hex_of preflight-image)"
valid_image="registry.test/bokrilog/backend@sha256:${image_digest}"
previous_image="registry.test/bokrilog/backend@sha256:$(hex_of preflight-previous)"
rehearsal_image="sha256:$(hex_of preflight-rehearsal)"

fixture="${work}/release"
mkdir -p "${fixture}"
printf '{"spdxVersion":"SPDX-2.3","name":"preflight-fixture"}\n' >"${fixture}/sbom.spdx.json"
printf 'preflight-fixture-signature\n' >"${fixture}/bundle.sig"
sbom_sha="$(sha256_of "${fixture}/sbom.spdx.json")"
signature_sha="$(sha256_of "${fixture}/bundle.sig")"

# write_release <파일> [key=value 덮어쓰기…]. 빈 값(`key=`)은 줄을 지운다.
write_release() {
  local file="$1" key value pair line
  shift
  : >"${file}"
  for key in image platform migration_version migration_kind sbom sbom_sha256 signature signature_sha256; do
    case "${key}" in
      image) value="${valid_image}" ;;
      platform) value='linux/arm64' ;;
      migration_version) value='24' ;;
      migration_kind) value='expand' ;;
      sbom) value='sbom.spdx.json' ;;
      sbom_sha256) value="${sbom_sha}" ;;
      signature) value='bundle.sig' ;;
      signature_sha256) value="${signature_sha}" ;;
    esac
    line="${key}=${value}"
    for pair in "$@"; do
      if [ "${pair%%=*}" = "${key}" ]; then
        line="${pair}"
        [ -n "${pair#*=}" ] || line=''
      fi
    done
    [ -n "${line}" ] && printf '%s\n' "${line}" >>"${file}"
  done
  return 0
}

# write_kv <파일> <기본 파일> [key=value 덮어쓰기…]. 기본 파일의 키 순서를 지키고 빈 값(`key=`)은 줄을 지운다.
write_kv() {
  local file="$1" base="$2" key pair line
  shift 2
  : >"${file}"
  while IFS= read -r key; do
    [ -n "${key}" ] || continue
    line="${key}=$(kv_get "${base}" "${key}")"
    for pair in "$@"; do
      if [ "${pair%%=*}" = "${key}" ]; then
        line="${pair}"
        [ -n "${pair#*=}" ] || line=''
      fi
    done
    [ -n "${line}" ] && printf '%s\n' "${line}" >>"${file}"
  done <<KEYS
$(kv_keys "${base}")
KEYS
  return 0
}

secrets_base="${work}/secrets.base"
{
  printf 'BOKRILOG_DB_URL=jdbc:postgresql://postgres:5432/bokrilog\n'
  printf 'BOKRILOG_DB_USERNAME=bokrilog\n'
  printf 'BOKRILOG_DB_PASSWORD=%s\n' "$(random_hex 12)"
  printf 'POSTGRES_PASSWORD=%s\n' "$(random_hex 12)"
  printf 'BOKRILOG_PUBLIC_BASE_URL=https://bokrilog.test\n'
  printf 'BOKRILOG_AUDIT_DIGEST_KEY=%s\n' "$(random_hex 32)"
  printf 'BOKRILOG_SOURCE_CREDENTIAL_KEYS=v1:%s\n' "$(random_hex 32)"
  printf 'BOKRILOG_SOURCE_CREDENTIAL_ACTIVE_KEY_VERSION=v1\n'
  printf 'BOKRILOG_BYBIT_BASE_URL=https://bybit.test\n'
  printf 'BOKRILOG_BYBIT_RESERVED_IP=203.0.113.10\n'
  printf 'BOKRILOG_SUPPORT_CONTACT=support@bokrilog.test\n'
  printf 'BOKRILOG_GOOGLE_CLIENT_ID=%s\n' "$(random_hex 8)"
  printf 'BOKRILOG_GOOGLE_CLIENT_SECRET=%s\n' "$(random_hex 16)"
} >"${secrets_base}"

# write_secrets <파일> [key=value…] — 600으로 만든다.
write_secrets() {
  local file="$1"
  shift
  write_kv "${file}" "${secrets_base}" "$@"
  chmod 600 "${file}"
}

# compose 변형: <파일> <sed 식>
write_compose() {
  sed "$2" "${compose_source}" >"$1"
}

state_open="${work}/state.open"
printf 'active_slot=blue\nactive_image=%s\nworker_image=%s\nprevious_image=%s\nschema_version=24\nrollback_window=open\n' \
  "${valid_image}" "${valid_image}" "${previous_image}" >"${state_open}"
state_closed="${work}/state.closed"
printf 'active_slot=blue\nactive_image=%s\nworker_image=%s\nprevious_image=%s\nschema_version=24\nrollback_window=closed\n' \
  "${valid_image}" "${valid_image}" "${previous_image}" >"${state_closed}"

release="${fixture}/release.env"
write_release "${release}"
secrets="${work}/secrets.env"
write_secrets "${secrets}"
host="${work}/host-facts.env"
write_kv "${host}" "${host_example}" 'signer_identity=https://github.test/release.yml@refs/heads/main' \
  'signer_issuer=https://token.actions.test'
compose="${work}/compose.yaml"
cp "${compose_source}" "${compose}"
# 리허설 모드가 요구하는 override(내용은 gate가 보지 않는다 — 있는지만 본다).
override="${script_dir}/compose.rehearsal.yaml"
cosign_log="${work}/cosign.log"

# ---------------------------------------------------------------- 실행 도우미

last_output=''
last_status=0

# run_gate [--rehearsal] [--state F] [--release F] [--host F] [--secrets F] [--compose F] [--no-cosign]
#
# 모드 입력은 호출자가 변수로 덮는다(비우려면 빈 값을 준다): GATE_ENV(기본 — 리허설 `ci`, 운영 빈 값), GATE_OVERRIDE(기본 —
# 리허설은 리허설 override, 운영 빈 값), GATE_ACTOR(기본 `preflight-fixture`), GATE_PROJECT(기본 `bokrilog-production`). 호출자의
# 셸 환경(`BOKRILOG_ENV` 등)이 판정에 새지 않게 이 함수가 그 값을 명시적으로 넘긴다. 환경 변수 FAKE_*·BOKRILOG_DEPLOY_FAULT는
# 호출자가 앞에 둔다.
run_gate() {
  local r="${release}" h="${host}" s="${secrets}" c="${compose}" st='' extra='' path="${fake_bin}:${PATH}"
  local env_value override_value
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --rehearsal) extra='--rehearsal'; shift ;;
      --state) st="$2"; shift 2 ;;
      --release) r="$2"; shift 2 ;;
      --host) h="$2"; shift 2 ;;
      --secrets) s="$2"; shift 2 ;;
      --compose) c="$2"; shift 2 ;;
      --no-cosign) rm -f "${fake_bin}/cosign"; shift ;;
      *) fail_setup "run_gate 인자: $1" ;;
    esac
  done
  if [ -n "${extra}" ]; then
    env_value="${GATE_ENV-ci}"
    override_value="${GATE_OVERRIDE-${override}}"
  else
    env_value="${GATE_ENV-}"
    override_value="${GATE_OVERRIDE-}"
  fi
  last_output="$(PATH="${path}" BOKRILOG_ENV="${env_value}" FAKE_COSIGN_LOG="${cosign_log}" \
    bash "${preflight}" --release "${r}" --host-facts "${h}" --secrets "${s}" --compose "${c}" \
    ${st:+--state "${st}"} --actor "${GATE_ACTOR-preflight-fixture}" \
    --project "${GATE_PROJECT-bokrilog-production}" --override "${override_value}" ${extra} 2>&1)"
  last_status=$?
  if [ "${posix_modes}" = false ] && [ "${last_status}" -eq 1 ]; then
    # 이 파일 시스템에서는 600 비밀 파일을 만들 수 없어 운영 모드의 권한 검사가 언제나 거부한다. 그 거부만 걷어 내고 판정한다
    # (권한 검사 자체는 POSIX 파일 시스템의 CI가 단정한다).
    last_output="$(printf '%s\n' "${last_output}" | grep -vF '  거부  SECRETS_PERMISSION  ')"
    if ! printf '%s\n' "${last_output}" | grep -F '  거부  ' >/dev/null; then
      last_status=0
    fi
  fi
}

# expect <라벨> <종료 코드> [거부 code]
expect() {
  local label="$1" want="$2" code="${3:-}"
  if [ "${last_status}" -ne "${want}" ]; then
    record_fail "${label}" "종료 코드 ${last_status} (기대 ${want}) — $(printf '%s' "${last_output}" | grep -E '거부|error' | tr '\n' '|')"
    return
  fi
  if [ -n "${code}" ] && ! printf '%s\n' "${last_output}" | grep -F -- "  거부  ${code}  " >/dev/null; then
    record_fail "${label}" "거부 code ${code}가 없다 — $(printf '%s' "${last_output}" | grep -E '거부' | tr '\n' '|')"
    return
  fi
  record_pass "${label}"
}

# expect_only <라벨> <code>. 거부가 그 code 하나뿐이다(다른 검사가 함께 깨지지 않았다).
expect_only() {
  local label="$1" code="$2" refused
  refused="$(printf '%s\n' "${last_output}" | awk '/^  거부  / { print $2 }' | sort -u | tr '\n' ' ' | sed 's/ $//')"
  if [ "${last_status}" -eq 1 ] && [ "${refused}" = "${code}" ]; then
    record_pass "${label}"
  else
    record_fail "${label}" "종료 ${last_status}, 거부 '${refused}' (기대 ${code} 하나)"
  fi
}

# ---------------------------------------------------------------- 시험

printf 'gate 고정 입력 시험\n\n'

printf '통과\n'
run_gate
expect '정상 입력 → 0' 0
run_gate --state "${state_closed}"
expect '정상 입력·이전 배포 있음 → 0' 0
run_gate --rehearsal
expect '리허설 모드 정상 입력 → 0' 0
r="${fixture}/rehearsal.env"
write_release "${r}" "image=${rehearsal_image}"
run_gate --rehearsal --release "${r}"
expect '리허설: image ID 참조(서명 신원은 보지 않는다) → 0' 0
h="${work}/host.no-signer"
write_kv "${h}" "${host_example}" 'signer_identity=' 'signer_issuer='
run_gate --rehearsal --host "${h}"
expect '리허설: host 사실에 서명 신원이 없어도 → 0' 0
r="${fixture}/rehearsal-tag.env"
write_release "${r}" 'image=registry.test/bokrilog/backend:rehearsal'
run_gate --rehearsal --release "${r}"
expect_only '리허설도 tag 참조는 거부' IMAGE_REFERENCE_INVALID

printf '\nrelease 형식(AC-1)\n'
for bad in 'registry.test/bokrilog/backend:1.0' "registry.test/bokrilog/backend:1.0@sha256:${image_digest}" \
  'registry.test/bokrilog/backend:latest' "registry.test/bokrilog/latest@sha256:${image_digest}" \
  "${rehearsal_image}" "registry.test/bokrilog/backend@sha256:${image_digest%?}"; do
  r="${fixture}/bad-image.env"
  write_release "${r}" "image=${bad}"
  run_gate --release "${r}"
  expect_only "image '${bad%%@*}…' → IMAGE_REFERENCE_INVALID" IMAGE_REFERENCE_INVALID
done
r="${fixture}/port.env"
write_release "${r}" "image=registry.test:5000/bokrilog/backend@sha256:${image_digest}"
run_gate --release "${r}"
expect '레지스트리 주소의 포트는 tag가 아니다 → 0' 0
r="${fixture}/platform.env"
write_release "${r}" 'platform=linux/amd64'
run_gate --release "${r}"
expect_only 'platform linux/amd64 → PLATFORM_INVALID' PLATFORM_INVALID
r="${fixture}/version.env"
write_release "${r}" 'migration_version=v24'
run_gate --release "${r}"
expect_only 'migration_version 정수 아님 → MIGRATION_VERSION_INVALID' MIGRATION_VERSION_INVALID
r="${fixture}/kind.env"
write_release "${r}" 'migration_kind=both'
run_gate --release "${r}"
expect_only 'migration_kind 밖 → MIGRATION_KIND_INVALID' MIGRATION_KIND_INVALID
r="${fixture}/unknown.env"
write_release "${r}"
printf 'startup_migration=true\n' >>"${r}"
run_gate --release "${r}"
expect_only '알 수 없는 키 → RELEASE_KEY_UNKNOWN' RELEASE_KEY_UNKNOWN
r="${fixture}/duplicate.env"
write_release "${r}"
printf 'image=%s\n' "${previous_image}" >>"${r}"
run_gate --release "${r}"
expect_only '중복 키 → RELEASE_KEY_DUPLICATE' RELEASE_KEY_DUPLICATE
r="${fixture}/missing.env"
write_release "${r}"
printf 'signer_identity=https://attacker.test/release.yml@refs/heads/main\n' >>"${r}"
run_gate --release "${r}"
expect_only '서명 신원은 manifest가 고를 수 없다 → RELEASE_KEY_UNKNOWN' RELEASE_KEY_UNKNOWN
r="${fixture}/long-repo.env"
write_release "${r}" "image=registry.test/$(printf '%0166d' 0 | tr 0 a)@sha256:${image_digest}"
run_gate --release "${r}"
expect '저장소 180자(경계) → 0' 0
write_release "${r}" "image=registry.test/$(printf '%0167d' 0 | tr 0 a)@sha256:${image_digest}"
run_gate --release "${r}"
expect_only '저장소 181자(감사 target에 넘친다) → IMAGE_REFERENCE_INVALID' IMAGE_REFERENCE_INVALID

printf '\nSBOM·서명·아키텍처(AC-1)\n'
r="${fixture}/sbom.env"
write_release "${r}" "sbom_sha256=$(hex_of other-sbom)"
run_gate --release "${r}"
expect_only 'SBOM SHA-256 불일치 → SBOM_DIGEST_MISMATCH' SBOM_DIGEST_MISMATCH
r="${fixture}/signature.env"
write_release "${r}" 'signature=absent.sig'
run_gate --release "${r}"
expect_only '서명 파일 없음 → SIGNATURE_MISSING' SIGNATURE_MISSING
r="${fixture}/traversal.env"
write_release "${r}" 'sbom=../secrets.env'
run_gate --release "${r}"
expect_only 'release 밖 경로 → SBOM_MISSING' SBOM_MISSING
FAKE_PLATFORM=linux/amd64 run_gate
expect_only '실제 이미지 amd64 → IMAGE_ARCHITECTURE_MISMATCH' IMAGE_ARCHITECTURE_MISMATCH
FAKE_INSPECT_STATUS=1 run_gate
expect_only '이미지 없음 → IMAGE_UNAVAILABLE' IMAGE_UNAVAILABLE
FAKE_COSIGN_STATUS=1 run_gate
expect_only 'cosign verify 실패 → SIGNATURE_INVALID' SIGNATURE_INVALID
FAKE_ATTEST_STATUS=1 run_gate
expect_only 'SBOM attestation 실패 → SBOM_ATTESTATION_INVALID' SBOM_ATTESTATION_INVALID
: >"${cosign_log}"
run_gate
if grep -F 'verify-attestation --type spdxjson --certificate-identity https://github.test/release.yml@refs/heads/main' "${cosign_log}" >/dev/null \
  && grep -F 'verify --certificate-identity https://github.test/release.yml@refs/heads/main --certificate-oidc-issuer https://token.actions.test' "${cosign_log}" >/dev/null; then
  record_pass 'cosign 두 검증은 host 사실의 서명 신원을 쓴다'
else
  record_fail 'cosign 두 검증은 host 사실의 서명 신원을 쓴다' "$(tr '\n' '|' <"${cosign_log}")"
fi
h="${work}/host.no-signer"
write_kv "${h}" "${host_example}" 'signer_identity='
run_gate --host "${h}"
expect_only '운영 모드의 host 사실에 서명 신원 없음 → SIGNER_IDENTITY_MISSING' SIGNER_IDENTITY_MISSING
FAKE_PLATFORM=linux/amd64 FAKE_COSIGN_STATUS=1 FAKE_ATTEST_STATUS=1 run_gate --rehearsal
expect '리허설은 아키텍처·서명·attestation 검사만 건너뛴다 → 0' 0
if command -v cosign >/dev/null 2>&1; then
  record_pass 'cosign 없음 → 2 (이 호스트에 cosign이 있어 건너뜀)'
else
  run_gate --no-cosign
  expect 'cosign 없음 → 준비 실패 2' 2
  write_fake_cosign
fi
run_gate --release "${fixture}/absent.env"
expect 'release 파일 없음 → 준비 실패 2' 2
# 값을 받는 인자가 값 없이 마지막에 오면 끝없이 돌지 않고 준비 실패다.
for flag in --release --host-facts --secrets --compose --state --actor --project --override; do
  if command -v timeout >/dev/null 2>&1; then
    timeout 20 bash "${preflight}" "${flag}" >/dev/null 2>&1
  else
    bash "${preflight}" "${flag}" >/dev/null 2>&1
  fi
  flag_status=$?
  if [ "${flag_status}" -eq 2 ]; then
    record_pass "값 없는 ${flag} → 준비 실패 2"
  else
    record_fail "값 없는 ${flag} → 준비 실패 2" "종료 ${flag_status}"
  fi
done

printf '\nmigration 종류와 rollback 창(AC-4)\n'
r="${fixture}/contract.env"
write_release "${r}" 'migration_kind=contract' 'migration_version=25'
run_gate --release "${r}" --state "${state_open}"
expect_only 'contract + 창 열림 → CONTRACT_WINDOW_OPEN' CONTRACT_WINDOW_OPEN
run_gate --release "${r}" --state "${state_closed}"
expect 'contract + 창 닫힘 + migration 하나 → 0' 0
r="${fixture}/contract-jump.env"
write_release "${r}" 'migration_kind=contract' 'migration_version=26'
run_gate --release "${r}" --state "${state_closed}"
expect_only 'contract가 migration 둘(expand 섞임) → CONTRACT_NOT_ISOLATED' CONTRACT_NOT_ISOLATED
r="${fixture}/downgrade.env"
write_release "${r}" 'migration_version=23'
run_gate --release "${r}" --state "${state_open}"
expect_only '창 안이어도 이전 이미지가 아닌 schema 역행 → MIGRATION_DOWNGRADE' MIGRATION_DOWNGRADE
r="${fixture}/rollback.env"
write_release "${r}" 'migration_version=23' "image=${previous_image}"
run_gate --release "${r}" --state "${state_open}"
expect '창 안의 이전 이미지(old API + new schema) → 0' 0
run_gate --release "${r}" --state "${state_closed}"
expect_only '창이 닫힌 뒤의 이전 이미지 → MIGRATION_DOWNGRADE' MIGRATION_DOWNGRADE

printf '\nhost 사실·비용(AC-2)\n'
h="${work}/host.cost"
write_kv "${h}" "${host_example}" 'expected_monthly_cost_krw=1200'
run_gate --host "${h}"
expect_only '예상 월 비용 > 0 → COST_NOT_ZERO' COST_NOT_ZERO
write_kv "${h}" "${host_example}" 'always_free=false'
run_gate --host "${h}"
expect_only 'Always Free 아님 → NOT_ALWAYS_FREE' NOT_ALWAYS_FREE
write_kv "${h}" "${host_example}" 'region=ap-seoul-1'
run_gate --host "${h}"
expect_only 'region ≠ home region → HOST_REGION_INVALID' HOST_REGION_INVALID
write_kv "${h}" "${host_example}" 'shape=VM.Standard.E2.1.Micro'
run_gate --host "${h}"
expect_only 'A1.Flex 아님 → HOST_CAPACITY_INVALID' HOST_CAPACITY_INVALID
write_kv "${h}" "${host_example}" 'ocpus=4'
run_gate --host "${h}"
expect_only 'OCPU 4 → HOST_CAPACITY_INVALID' HOST_CAPACITY_INVALID
write_kv "${h}" "${host_example}" 'reserved_ip=ephemeral'
run_gate --host "${h}"
expect_only 'reserved IP 아님 → RESERVED_IP_INVALID' RESERVED_IP_INVALID
write_kv "${h}" "${host_example}" 'os_reserved_mib=7000'
run_gate --host "${h}"
expect_only 'blue/green peak + OS 예약 > 12GB → PEAK_EXCEEDS_MEMORY' PEAK_EXCEEDS_MEMORY
write_kv "${h}" "${host_example}" 'os_reserved_mib=6784'
run_gate --host "${h}"
expect 'peak 5504MiB + OS 6784MiB = 12288MiB(경계) → 0' 0
write_kv "${h}" "${host_example}" 'os_reserved_mib=6785'
run_gate --host "${h}"
expect_only 'peak + OS = 12289MiB(경계 직후) → PEAK_EXCEEDS_MEMORY' PEAK_EXCEEDS_MEMORY
write_kv "${h}" "${host_example}" 'reserved_ip='
run_gate --host "${h}"
expect '사실 누락 → HOST_FACT_MISSING' 1 HOST_FACT_MISSING

printf '\ncompose 계약(AC-1·AC-2·AC-3)\n'
c="${work}/compose.variant.yaml"
write_compose "${c}" 's/^    mem_limit: 768m$/    mem_limit: 1g/'
run_gate --compose "${c}"
expect_only 'API mem_limit 1g → MEMORY_LIMIT_MISMATCH' MEMORY_LIMIT_MISMATCH
write_compose "${c}" 's/^    mem_limit: 3g$/    mem_limit: 4g/'
run_gate --compose "${c}"
expect_only 'PostgreSQL mem_limit 4g → MEMORY_LIMIT_MISMATCH' MEMORY_LIMIT_MISMATCH
write_compose "${c}" 's/^    mem_limit: 128m$/    mem_limit: 256m/'
run_gate --compose "${c}"
expect_only 'Nginx mem_limit 256m → MEMORY_LIMIT_MISMATCH' MEMORY_LIMIT_MISMATCH
write_compose "${c}" 's/^    image: nginx:1.30.4-alpine@sha256:[0-9a-f]*$/    image: nginx:1.30.4-alpine/'
run_gate --compose "${c}"
expect_only '인프라 이미지 tag 참조 → IMAGE_NOT_PINNED' IMAGE_NOT_PINNED
write_compose "${c}" 's/^      BOKRILOG_ROLE: api$/      BOKRILOG_ROLE: api\
      SPRING_PROFILES_ACTIVE: migrate/'
run_gate --compose "${c}"
expect_only 'API 슬롯에 migrate 프로필 → MIGRATE_PROFILE_IN_SERVICE' MIGRATE_PROFILE_IN_SERVICE
write_compose "${c}" 's/^      BOKRILOG_ROLE: worker$/      BOKRILOG_ROLE: all/'
run_gate --compose "${c}"
expect_only 'Worker 역할이 all → ROLE_MISSING' ROLE_MISSING
write_compose "${c}" 's/^  worker:$/  worker:\
    build: ..\/..\/backend/'
run_gate --compose "${c}"
expect_only '운영 compose에 build → BUILD_IN_PRODUCTION' BUILD_IN_PRODUCTION
write_compose "${c}" 's/^  migrate:$/  migrate:\
    env_file:\
      - production.env/'
run_gate --compose "${c}"
expect_only '운영 compose가 env_file로 비밀 파일 전체를 넘김 → ENV_FILE_IN_COMPOSE' ENV_FILE_IN_COMPOSE

printf '\n운영 비밀\n'
s="${work}/secrets.variant"
write_secrets "${s}" 'BOKRILOG_DB_PASSWORD='
run_gate --secrets "${s}"
expect_only 'DB 비밀번호 비어 있음 → SECRET_MISSING' SECRET_MISSING
if printf '%s' "${last_output}" | grep -F "$(kv_get "${secrets_base}" POSTGRES_PASSWORD)" >/dev/null; then
  record_fail 'gate 출력에 비밀값이 없다' '비밀값이 출력에 있다.'
else
  record_pass 'gate 출력에 비밀값이 없다'
fi
for forbidden in 'SPRING_PROFILES_INCLUDE=migrate' 'SPRING_APPLICATION_JSON={}' \
  'BOKRILOG_SYNC_WORKER_DRAIN_TIMEOUT=1s' 'BOKRILOG_ENV=ci' 'BOKRILOG_DEPLOY_ACTOR=shared-actor'; do
  write_secrets "${s}"
  printf '%s\n' "${forbidden}" >>"${s}"
  run_gate --secrets "${s}"
  expect_only "비밀 파일의 ${forbidden%%=*} → PROFILE_OVERRIDE" PROFILE_OVERRIDE
done
write_secrets "${s}" 'BOKRILOG_GOOGLE_CLIENT_SECRET='
run_gate --secrets "${s}"
expect_only '로그인 제공자 쌍 없음 → NO_SIGN_IN_PROVIDER' NO_SIGN_IN_PROVIDER
write_secrets "${s}"
printf 'SPRING_PROFILES_ACTIVE=migrate\n' >>"${s}"
run_gate --secrets "${s}"
expect_only '비밀 파일이 프로필을 덮음 → PROFILE_OVERRIDE' PROFILE_OVERRIDE
write_secrets "${s}"
printf 'BOKRILOG_ROLE=all\n' >>"${s}"
run_gate --secrets "${s}"
expect_only '비밀 파일이 역할을 덮음 → PROFILE_OVERRIDE' PROFILE_OVERRIDE
if [ "${posix_modes}" = true ]; then
  write_secrets "${s}"
  chmod 644 "${s}"
  run_gate --secrets "${s}"
  expect_only '비밀 파일 권한 644 → SECRETS_PERMISSION' SECRETS_PERMISSION
  run_gate --secrets "${s}" --rehearsal
  expect_only '리허설도 권한을 본다 → SECRETS_PERMISSION' SECRETS_PERMISSION
else
  record_pass '비밀 파일 권한 644 → SECRETS_PERMISSION (이 파일 시스템은 POSIX 권한을 표현하지 못해 건너뜀)'
fi

printf '\n결함 주입·리허설 모드\n'
BOKRILOG_DEPLOY_FAULT=post-cutover-smoke run_gate
expect_only '운영 모드의 결함 주입 → FAULT_INJECTION_IN_PRODUCTION' FAULT_INJECTION_IN_PRODUCTION
BOKRILOG_DEPLOY_FAULT=post-cutover-smoke run_gate --rehearsal
expect '리허설의 결함 주입 → 0' 0
GATE_ENV=production BOKRILOG_DEPLOY_FAULT=post-cutover-smoke run_gate --rehearsal
expect 'BOKRILOG_ENV=production의 리허설 → 거부' 1 REHEARSAL_IN_PRODUCTION
expect 'BOKRILOG_ENV=production의 결함 주입 → 거부' 1 FAULT_INJECTION_IN_PRODUCTION
GATE_ENV= run_gate --rehearsal
expect_only 'BOKRILOG_ENV가 비면(운영 셸) --rehearsal을 받지 않는다 → REHEARSAL_IN_PRODUCTION' REHEARSAL_IN_PRODUCTION
GATE_OVERRIDE= run_gate --rehearsal
expect_only 'override 없는 리허설 → REHEARSAL_IN_PRODUCTION' REHEARSAL_IN_PRODUCTION
GATE_OVERRIDE="${work}/absent-override.yaml" run_gate --rehearsal
expect_only '없는 override 파일의 리허설 → REHEARSAL_IN_PRODUCTION' REHEARSAL_IN_PRODUCTION
GATE_ENV=development run_gate --rehearsal
expect '개발 환경의 리허설 → 0' 0
GATE_ENV=ci run_gate
expect_only '운영 모드인데 BOKRILOG_ENV=ci → ENV_NOT_PRODUCTION' ENV_NOT_PRODUCTION
GATE_ENV=production run_gate
expect '운영 모드 BOKRILOG_ENV=production → 0' 0
GATE_OVERRIDE="${override}" run_gate
expect_only '운영 모드에 override → OVERRIDE_IN_PRODUCTION' OVERRIDE_IN_PRODUCTION

printf '\n프로젝트·배포 수행자\n'
for project in bokrilog 'bokrilog-Prod' 'other-production' 'bokrilog-' 'bokrilog-a;rm'; do
  GATE_PROJECT="${project}" run_gate
  expect_only "프로젝트 '${project}' → PROJECT_INVALID" PROJECT_INVALID
done
GATE_PROJECT=bokrilog-rehearsal run_gate
expect '프로젝트 bokrilog-rehearsal → 0' 0
too_long_actor="$(printf '%0129d' 0 | tr 0 a)"
actor_case=0
for bad_actor in '' 'deployer with space' "${too_long_actor}"; do
  actor_case=$((actor_case + 1))
  GATE_ACTOR="${bad_actor}" run_gate
  expect_only "배포 수행자 모양 밖 #${actor_case}(빈 값·공백·129자) → DEPLOY_ACTOR_INVALID" DEPLOY_ACTOR_INVALID
done

printf '\n스크립트 규약\n'
for script in preflight.sh deploy.sh rehearse-deploy.sh lib.sh test-preflight.sh; do
  if [ ! -f "${script_dir}/${script}" ]; then
    record_fail "${script}가 있다" '파일이 없다.'
    continue
  fi
  # 주석 줄은 보지 않는다 — 규약을 설명하는 주석이 그 낱말을 담는다.
  code_lines="$(grep -vE '^[[:space:]]*#' "${script_dir}/${script}")"
  assert_count="$(printf '%s\n' "${code_lines}" | grep -cE '\|[[:space:]]*grep -[[:alpha:]]*q' || true)"
  if [ "${assert_count}" = 0 ]; then
    record_pass "${script} · pipefail 아래 조기 종료 grep -q 파이프가 없다"
  else
    record_fail "${script} · pipefail 아래 조기 종료 grep -q 파이프가 없다" "${assert_count}곳"
  fi
  gnu_count="$(printf '%s\n' "${code_lines}" | grep -cE 'sed -i|grep -P|readlink -f' || true)"
  # 이 줄 자신이 그 낱말을 담으므로 이 파일은 한 줄을 뺀다.
  [ "${script}" = test-preflight.sh ] && gnu_count=$((gnu_count - 1))
  if [ "${gnu_count}" -le 0 ]; then
    record_pass "${script} · GNU 전용 플래그가 없다"
  else
    record_fail "${script} · GNU 전용 플래그가 없다" "${gnu_count}곳"
  fi
  if bash -n "${script_dir}/${script}" 2>/dev/null; then
    record_pass "${script} · bash 구문"
  else
    record_fail "${script} · bash 구문" "$(bash -n "${script_dir}/${script}" 2>&1 | head -3)"
  fi
done

printf '\n%s개 중 %s개 통과, %s개 실패\n' "${tests_run}" "$((tests_run - tests_failed))" "${tests_failed}"
if [ "${tests_failed}" -ne 0 ]; then
  printf '::error::gate 시험이 실패했다.\n'
  exit 1
fi
exit 0
