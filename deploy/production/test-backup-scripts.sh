#!/usr/bin/env bash
# 백업 스크립트의 고정 입력 시험(Story 3.9). Docker·네트워크·실제 age·rclone이 필요 없다.
#
# 왜 있는가: 리허설(`rehearse-backup.sh`)은 실제 도구와 저장소로 정상·실패 경로를 돌지만, 준비 단계의 거부(운영 모드의 가상 시계·결함
# 주입, 설정·key 파일·권한)와 도구 설치의 무결성 판정은 고정 입력으로 빠짐없이 보는 편이 맞다. 도구는 PATH 앞의 가짜와 stub 바이너리가
# 대신한다.
#
#   install-backup-tools.sh  잘못된 바이트를 주는 curl → 준비 실패 2, bin/은 비어 있다. `--check`: 맞는 stub → 0, 다른 버전 → 1,
#                            설치 manifest와 다른 바이너리 → 1, manifest 없음 → 1.
#   backup.sh                운영 모드의 `BOKRILOG_BACKUP_NOW`·`BOKRILOG_BACKUP_FAULT`·`BOKRILOG_BACKUP_LOCK_WAIT` → 2,
#                            `BOKRILOG_ENV=production`의 리허설 → 2,
#                            알 수 없는 설정 키·같은 remote 이름·끝의 `/`·`//`·max_bytes > quota·rclone.conf 권한·identity 없음 → 2,
#                            `keys`: 정상 → 0, 저장소를 읽지 못함 → 1, identity와 recipient가 짝이 아님 → 1.
#
# 실행: bash deploy/production/test-backup-scripts.sh
#
# 종료 코드
#   0 -- 모든 단정 통과
#   1 -- 단정 실패
#   2 -- 준비 실패
#
# fixture는 임시 디렉터리에만 만들고 끝나면 지운다. key 모양의 값을 커밋하지 않는다 — identity fixture는 age 형식이 아닌 자리표시자다.

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
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

have_sha256_tool || fail_setup 'sha256sum 또는 shasum이 없다.'
work="$(mktemp -d)" || fail_setup '임시 디렉터리를 만들지 못했다.'
[ -d "${work}" ] || fail_setup "mktemp -d가 쓸 수 없는 경로를 냈다: '${work}'"
trap 'rm -rf "${work}"' EXIT

last_output=''
last_status=0

# expect <라벨> <종료 코드> [출력에 있어야 할 문구]
expect() {
  if [ "${last_status}" != "$2" ]; then
    record_fail "$1" "종료 ${last_status} (기대 $2): $(printf '%s\n' "${last_output}" | tail -3 | tr '\n' ' ')"
    return
  fi
  if [ -n "${3:-}" ] && ! printf '%s\n' "${last_output}" | grep -F -- "$3" >/dev/null; then
    record_fail "$1" "출력에 '$3'가 없다: $(printf '%s\n' "${last_output}" | tail -3 | tr '\n' ' ')"
    return
  fi
  record_pass "$1"
}

# 이 파일 시스템이 POSIX 권한을 표현하는가(Windows의 Git Bash는 아니다).
probe="${work}/mode-probe"
: >"${probe}"
chmod 600 "${probe}"
posix_modes=false
case "$(ls -ln "${probe}" | awk '{ print $1 }')" in
  -rw-------*) posix_modes=true ;;
esac

# ---------------------------------------------------------------- 가짜 도구

fake_bin="${work}/fake-bin"
mkdir -p "${fake_bin}"
# 무엇을 요청받든 `--output`에 배포본이 아닌 바이트를 쓴다.
cat >"${fake_bin}/curl" <<'FAKE'
#!/usr/bin/env bash
out=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    --output) out="$2"; shift 2 ;;
    *) shift ;;
  esac
done
[ -n "${out}" ] && printf 'not-the-release-archive\n' >"${out}"
exit 0
FAKE
chmod +x "${fake_bin}/curl"
# 풀기 도구는 이 시험에서 닿으면 안 된다(checksum이 먼저 거부한다). 호스트에 없어도 준비 단계를 지나게 둔다.
for unpacker in unzip tar; do
  printf '#!/usr/bin/env bash\nexit 1\n' >"${fake_bin}/${unpacker}"
  chmod +x "${fake_bin}/${unpacker}"
done

# stub_tools <prefix> <age 버전> <rclone 버전>. 고정 버전을 흉내 내는 stub 셋과 그 설치 manifest.
stub_tools() {
  mkdir -p "$1/bin"
  cat >"$1/bin/age" <<STUB
#!/usr/bin/env bash
[ "\${1:-}" = --version ] && printf '%s\n' '$2'
exit 0
STUB
  # \`-y identity\`는 identity fixture의 \`# public key:\` 줄을 낸다(실제 age-keygen의 identity 파일도 같은 줄을 담는다).
  cat >"$1/bin/age-keygen" <<'STUB'
#!/usr/bin/env bash
[ "${1:-}" = -y ] && awk '/^# public key: / { print $4 }' "$2"
exit 0
STUB
  cat >"$1/bin/rclone" <<STUB
#!/usr/bin/env bash
for arg in "\$@"; do
  case "\${arg}" in
    version) printf 'rclone %s\n' '$3'; exit 0 ;;
    lsf) [ -n "\${FAKE_RCLONE_FAIL:-}" ] && exit 1; exit 0 ;;
  esac
done
exit 0
STUB
  chmod +x "$1/bin/age" "$1/bin/age-keygen" "$1/bin/rclone"
  write_manifest "$1"
}

write_manifest() {
  : >"$1/MANIFEST"
  for name in age age-keygen rclone; do
    printf '%s  bin/%s\n' "$(sha256_of "$1/bin/${name}")" "${name}" >>"$1/MANIFEST"
  done
}

age_version="$(awk -F"'" '/^AGE_VERSION=/ { print $2; exit }' "${script_dir}/install-backup-tools.sh")"
rclone_version="$(awk -F"'" '/^RCLONE_VERSION=/ { print $2; exit }' "${script_dir}/install-backup-tools.sh")"
[ -n "${age_version}" ] && [ -n "${rclone_version}" ] || fail_setup '고정 버전을 읽지 못했다.'

# ---------------------------------------------------------------- install-backup-tools.sh

printf 'install-backup-tools.sh\n'
last_output="$(PATH="${fake_bin}:${PATH}" BACKUP_TOOLS_CACHE_DIR="${work}/cache" \
  bash "${script_dir}/install-backup-tools.sh" --prefix "${work}/installed" 2>&1)"
last_status=$?
expect '잘못된 바이트의 배포본 → 준비 실패 2' 2 'checksum이 맞지 않는다'
if [ -z "$(ls -A "${work}/installed/bin" 2>/dev/null)" ]; then
  record_pass '잘못된 배포본 · bin/에 아무것도 놓지 않았다'
else
  record_fail '잘못된 배포본 · bin/에 아무것도 놓지 않았다' "$(ls -A "${work}/installed/bin")"
fi
if [ -z "$(ls -A "${work}/cache" 2>/dev/null)" ]; then
  record_pass '잘못된 배포본 · 캐시에 남기지 않았다'
else
  record_fail '잘못된 배포본 · 캐시에 남기지 않았다' "$(ls -A "${work}/cache")"
fi

check_tools() {
  last_output="$(bash "${script_dir}/install-backup-tools.sh" --check --prefix "$1" 2>&1)"
  last_status=$?
}

stub_tools "${work}/good" "${age_version}" "${rclone_version}"
check_tools "${work}/good"
expect '--check · 고정 버전 stub과 맞는 manifest → 0' 0
stub_tools "${work}/old" 'v1.2.1' "${rclone_version}"
check_tools "${work}/old"
expect '--check · 다른 age 버전 → 1' 1 '버전  age'
stub_tools "${work}/tampered" "${age_version}" "${rclone_version}"
printf '# changed after install\n' >>"${work}/tampered/bin/rclone"
check_tools "${work}/tampered"
expect '--check · 설치 manifest와 다른 바이너리 → 1' 1 '변조  rclone'
stub_tools "${work}/unlisted" "${age_version}" "${rclone_version}"
rm -f "${work}/unlisted/MANIFEST"
check_tools "${work}/unlisted"
expect '--check · manifest 없음 → 1' 1 '변조'

# ---------------------------------------------------------------- backup.sh 준비 거부

printf '\nbackup.sh 준비 거부\n'
tools="${work}/good"
keys="${work}/keys"
override="${script_dir}/compose.rehearsal.yaml"
recipient='age1fixturerecipient0'

reset_keys() {
  rm -rf "${keys}"
  mkdir -p "${keys}/identities" "${keys}/recipients"
  printf '# key_id=k1\n%s\n' "${recipient}" >"${keys}/active.pub"
  printf '%s\n' "${recipient}" >"${keys}/recipients/k1.pub"
  printf '# created: fixture\n# public key: %s\nfixture-identity-placeholder\n' "${recipient}" >"${keys}/identities/k1.key"
  printf '[oci]\ntype = local\n\n[offsite]\ntype = local\n' >"${keys}/rclone.conf"
  chmod 700 "${keys}" "${keys}/identities" "${keys}/recipients"
  chmod 600 "${keys}/identities/k1.key" "${keys}/rclone.conf"
  chmod 644 "${keys}/active.pub" "${keys}/recipients/k1.pub"
  write_config
}

# write_config [key=value 덮어쓰기…]
write_config() {
  local key value pair line
  : >"${keys}/backup.env"
  for key in oci_remote offsite_remote max_generations max_bytes_oci max_bytes_offsite quota_bytes_oci quota_bytes_offsite; do
    case "${key}" in
      oci_remote) value='oci:bokrilog-backup' ;;
      offsite_remote) value='offsite:bokrilog-backup' ;;
      max_generations) value='30' ;;
      quota_bytes_*) value='20000000000' ;;
      *) value='10000000000' ;;
    esac
    line="${key}=${value}"
    for pair in "$@"; do
      [ "${pair%%=*}" = "${key}" ] && line="${pair}"
    done
    printf '%s\n' "${line}" >>"${keys}/backup.env"
  done
  for pair in "$@"; do
    case " oci_remote offsite_remote max_generations max_bytes_oci max_bytes_offsite quota_bytes_oci quota_bytes_offsite " in
      *" ${pair%%=*} "*) ;;
      *) printf '%s\n' "${pair}" >>"${keys}/backup.env" ;;
    esac
  done
  return 0
}

# backup <env 이름=값…> -- <인자…>. 호출자의 셸 환경이 판정에 새지 않게 이 함수가 넘길 값을 정한다.
backup() {
  local assignments=()
  while [ "$#" -gt 0 ] && [ "$1" != -- ]; do
    assignments+=("$1")
    shift
  done
  shift
  last_output="$(env -u BOKRILOG_BACKUP_NOW -u BOKRILOG_BACKUP_FAULT -u BOKRILOG_BACKUP_LOCK_WAIT -u BOKRILOG_ENV -u BOKRILOG_COMPOSE_OVERRIDE \
    BOKRILOG_BACKUP_KEYS_DIR="${keys}" BOKRILOG_BACKUP_TOOLS="${tools}" BOKRILOG_BACKUP_DIR="${work}/backup" \
    BOKRILOG_STATE_DIR="${work}/state" BOKRILOG_SECRETS_FILE="${work}/absent.env" \
    ${assignments[@]+"${assignments[@]}"} bash "${script_dir}/backup.sh" "$@" 2>&1)"
  last_status=$?
}

rehearsal_env=(BOKRILOG_ENV=ci "BOKRILOG_COMPOSE_OVERRIDE=${override}")

reset_keys
backup "${rehearsal_env[@]}" -- keys --rehearsal
expect '대조군 · 맞는 fixture의 keys → 0' 0 '필요한 key가 모두 맞다'

backup BOKRILOG_BACKUP_NOW=1767225600 -- run
expect '운영 모드의 BOKRILOG_BACKUP_NOW → 2' 2 '가상 시계'
backup BOKRILOG_BACKUP_FAULT=dump -- run
expect '운영 모드의 BOKRILOG_BACKUP_FAULT → 2' 2 '결함 주입'
backup BOKRILOG_BACKUP_LOCK_WAIT=5 -- run
expect '운영 모드의 BOKRILOG_BACKUP_LOCK_WAIT → 2' 2 '잠금 대기'
backup BOKRILOG_ENV=production "BOKRILOG_COMPOSE_OVERRIDE=${override}" -- run --rehearsal
expect 'BOKRILOG_ENV=production의 리허설 → 2' 2 '리허설은'
backup BOKRILOG_ENV=ci -- run --rehearsal
expect 'override 없는 리허설 → 2' 2 'override'
backup "${rehearsal_env[@]}" BOKRILOG_BACKUP_FAULT=explode -- run --rehearsal
expect '알 수 없는 결함 주입 → 2' 2 '알 수 없는 결함 주입'

write_config 'retention_days=40'
backup "${rehearsal_env[@]}" -- run --rehearsal
expect '알 수 없는 설정 키 → 2' 2 '알 수 없는 키'
write_config 'offsite_remote=oci:bokrilog-backup-copy'
backup "${rehearsal_env[@]}" -- run --rehearsal
expect '두 저장소가 같은 rclone remote → 2' 2 '같은 rclone remote'
write_config 'oci_remote=oci:bokrilog-backup/'
backup "${rehearsal_env[@]}" -- run --rehearsal
expect 'remote 값이 /로 끝남 → 2' 2 '/로 끝난다'
write_config 'oci_remote=oci:bokrilog//backup'
backup "${rehearsal_env[@]}" -- run --rehearsal
expect 'remote 값에 // → 2' 2 '//'
write_config 'max_bytes_offsite=30000000000'
backup "${rehearsal_env[@]}" -- run --rehearsal
expect 'max_bytes > quota → 2' 2 'quota_bytes_offsite보다 크다'
write_config

if [ "${posix_modes}" = true ]; then
  chmod 644 "${keys}/rclone.conf"
  backup "${rehearsal_env[@]}" -- run --rehearsal
  expect 'rclone.conf 권한 644 → 2' 2 'rclone.conf 권한이 600이 아니다'
  backup -- run
  expect '운영 모드도 rclone.conf 권한 644 → 2' 2 'rclone.conf 권한이 600이 아니다'
  chmod 600 "${keys}/rclone.conf"
  chmod 755 "${keys}/identities"
  backup "${rehearsal_env[@]}" -- run --rehearsal
  expect 'identities/ 권한 755 → 2' 2 'identities/ 권한이 700이 아니다'
  chmod 700 "${keys}/identities"
else
  record_pass '권한 검사 3건(이 파일 시스템은 POSIX 권한을 표현하지 못해 건너뜀 — CI가 단정한다)'
fi

rm -f "${keys}/identities/k1.key"
backup "${rehearsal_env[@]}" -- run --rehearsal
expect 'active key의 identity 없음 → 2' 2 'identities/k1.key가 없다'
reset_keys
printf '%s\n' 'age1otherrecipient9' >"${keys}/recipients/k1.pub"
backup "${rehearsal_env[@]}" -- run --rehearsal
expect 'identity와 recipient가 짝이 아님 → 2' 2 '짝이 아니다'

# ---------------------------------------------------------------- backup.sh keys

printf '\nbackup.sh keys\n'
reset_keys
backup "${rehearsal_env[@]}" FAKE_RCLONE_FAIL=1 -- keys --rehearsal
expect '저장소를 읽지 못함 → 1' 1 '저장소를 읽지 못해'
printf '# created: fixture\n# public key: %s\nother-identity-placeholder\n' 'age1otherrecipient9' \
  >"${keys}/identities/k1.key"
backup "${rehearsal_env[@]}" -- keys --rehearsal
expect 'identity와 recipient가 짝이 아님 → 1' 1 '짝이 아니다'
reset_keys
printf '# key_id=k1\n%s\n' 'age1otherrecipient9' >"${keys}/active.pub"
backup "${rehearsal_env[@]}" -- keys --rehearsal
expect 'active.pub과 recipients/가 다름 → 1' 1 'recipients/k1.pub과 다르다'

# ---------------------------------------------------------------- 판정

printf '\n%s개 중 %s개 통과, %s개 실패\n' "${tests_run}" "$((tests_run - tests_failed))" "${tests_failed}"
if [ "${tests_failed}" -ne 0 ]; then
  printf '::error::백업 스크립트 시험이 실패했다.\n'
  exit 1
fi
exit 0
