#!/usr/bin/env bash
# 복원 스크립트의 고정 입력 시험(Story 3.10). Docker·네트워크·실제 age·rclone이 필요 없다.
#
# 왜 있는가: 리허설(`rehearse-restore.sh`)은 실제 도구·저장소·컨테이너로 복원 경로를 돌지만, manifest 검사기의 판정과 복원·내보내기의
# 준비 거부(인자·운영 프로젝트 이름·모드·설정)는 고정 입력으로 빠짐없이 보는 편이 맞다.
#
#   check-recovery-manifest.sh  견본·age 암호문 값 → 0, 평문 age identity·PEM·32바이트 base64·base64url key·16진 비밀(참조 경로 안
#                               포함)·주석의 `age:` 비밀·age 접두사가 아닌 `age:` 값·이름 칸·참조 경로의 긴 토큰·Terraform state
#                               본문·tag 이미지·필수 키 누락·알 수 없는 키·중복 키·대문자 참조 → 1, 파일 없음·인자 없음 → 2. 거부
#                               출력에 값이 없다.
#   restore.sh                  인자 없음·generation 모양·시간대 없는 장애 시각·운영 프로젝트 이름(`bokrilog-production`)·접두사 밖
#                               이름·운영 프로젝트와 같은 이름·tag 이미지·숫자 아닌 tombstone lag·운영 모드의 ci 환경·override 없는
#                               리허설·알 수 없는 인자·설정 없음 → 2.
#   tombstone-export.sh         알 수 없는 인자·운영 환경의 리허설·recipient 없음·backup key와 같은 recipient·identity 없음 → 2.
#   backup-lib.sh               `epoch_of_iso`(UTC·시간대·소수·모양 밖·달의 길이·윤년·시간대 범위).
#
# 실행: bash deploy/production/test-restore-scripts.sh
#
# 종료 코드
#   0 -- 모든 단정 통과
#   1 -- 단정 실패
#   2 -- 준비 실패
#
# fixture는 임시 디렉터리에만 만들고 끝나면 지운다. 비밀 모양의 값(age identity·PEM·base64 key)은 실행 시점에 만든다 — 커밋하지 않는다.

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib.sh
. "${script_dir}/lib.sh"
# shellcheck source=backup-lib.sh
. "${script_dir}/backup-lib.sh"

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

assert_equals() {
  if [ "$2" = "$3" ]; then
    record_pass "$1"
  else
    record_fail "$1" "실제 '$2' (기대 '$3')"
  fi
}

# 실행 시점에 만드는 비밀 모양의 값들. 커밋된 파일에는 이 값들이 없다.
bech32='qpzry9x8gf2tvdw0s3jn54khce6mua7l'
age_identity="AGE-SECRET-KEY-1$(od -An -N 58 -tu1 /dev/urandom | tr -s ' ' '\n' | awk -v alphabet="${bech32}" 'NF { printf "%s", substr(alphabet, ($1 % 32) + 1, 1) }' | tr 'a-z' 'A-Z')"
random_base64="$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
# 섞인 대소문자·숫자가 반드시 들어가게 한 줄 덧붙인다(무작위 32바이트는 거의 언제나 그렇지만 시험이 우연에 기대지 않게).
random_base64="Ab1${random_base64}"
pem_begin="$(printf -- '-----BEGIN %s-----' 'PRIVATE KEY')"
age_ciphertext="age:$(printf 'age-encryption.org/v1\n-> X25519 fixture\n' | base64 | tr -d '\n')"

# ---------------------------------------------------------------- check-recovery-manifest.sh

printf 'check-recovery-manifest.sh\n'
example="${script_dir}/recovery-manifest.example"

check_manifest() {
  last_output="$(bash "${script_dir}/check-recovery-manifest.sh" "$@" 2>&1)"
  last_status=$?
}

# manifest_with <파일> <sed 식…>. 견본을 고쳐 fixture를 만든다.
manifest_with() {
  local file="$1"
  shift
  cp "${example}" "${file}"
  local expression
  for expression in "$@"; do
    sed "${expression}" "${file}" >"${file}.next" && mv "${file}.next" "${file}"
  done
}

check_manifest "${example}"
expect '견본 → 0' 0 '복구 manifest 통과'
expect '견본 · 실제 복원 미검증을 말한다' 0 '실제 복원 미검증'

manifest_with "${work}/age.env" "s|^audit_digest_key=.*|audit_digest_key=${age_ciphertext}|"
check_manifest "${work}/age.env"
expect 'age 암호문 값 → 0' 0 '통과'

manifest_with "${work}/identity.env" "s|^tombstone_age_key=.*|tombstone_age_key=${age_identity}|"
check_manifest "${work}/identity.env"
expect '평문 age identity → 1' 1 'PLAINTEXT_SECRET AGE_IDENTITY'
if printf '%s\n' "${last_output}" | grep -F -- "${age_identity}" >/dev/null; then
  record_fail '평문 age identity · 거부 출력에 값이 없다' '출력에 identity 값이 있다.'
else
  record_pass '평문 age identity · 거부 출력에 값이 없다'
fi

manifest_with "${work}/comment.env"
printf '# %s\n' "${age_identity}" >>"${work}/comment.env"
check_manifest "${work}/comment.env"
expect '주석 속 평문 age identity → 1' 1 'AGE_IDENTITY'

manifest_with "${work}/pem.env"
printf 'credential_note=%s\n' "${pem_begin}" >>"${work}/pem.env"
check_manifest "${work}/pem.env"
expect 'PEM 블록 → 1' 1 'PEM_BLOCK'

manifest_with "${work}/base64.env" "s|^audit_digest_key=.*|audit_digest_key=${random_base64}|"
check_manifest "${work}/base64.env"
expect '32바이트 base64 key → 1' 1 'LONG_BASE64'
if printf '%s\n' "${last_output}" | grep -F -- "${random_base64}" >/dev/null; then
  record_fail '32바이트 base64 · 거부 출력에 값이 없다' '출력에 key 값이 있다.'
else
  record_pass '32바이트 base64 · 거부 출력에 값이 없다'
fi

manifest_with "${work}/tfstate.env"
printf '# {"version": 4, "terraform_version": "1.9.8", "serial": 12, "lineage": "fixture"}\n' >>"${work}/tfstate.env"
check_manifest "${work}/tfstate.env"
expect 'Terraform state 본문 → 1' 1 'TFSTATE_BODY'

manifest_with "${work}/tag.env" 's|^image=.*|image=ghcr.io/example/bokrilog-backend:1.2.3|'
check_manifest "${work}/tag.env"
expect 'tag만 있는 이미지 → 1' 1 'IMAGE_NOT_PINNED'

manifest_with "${work}/missing.env" '/^grafana=/d'
check_manifest "${work}/missing.env"
expect '필수 키 누락 → 1' 1 'grafana: MISSING_KEY'

manifest_with "${work}/unknown.env"
printf 'retention_days=40\n' >>"${work}/unknown.env"
check_manifest "${work}/unknown.env"
expect '알 수 없는 키 → 1' 1 'UNKNOWN_KEY'

manifest_with "${work}/duplicate.env"
printf 'dns=ref:runbook/dns/other\n' >>"${work}/duplicate.env"
check_manifest "${work}/duplicate.env"
expect '중복 키 → 1' 1 'dns: DUPLICATE_KEY'

manifest_with "${work}/upper.env" 's|^dns=.*|dns=ref:Runbook/DNS|'
check_manifest "${work}/upper.env"
expect '대문자 참조 → 1' 1 'dns: VALUE_SHAPE(ref)'

hex_secret="$(od -An -N 32 -tx1 /dev/urandom | tr -d ' \n')"
manifest_with "${work}/hex.env" "s|^dns=.*|dns=ref:vault/${hex_secret}|"
check_manifest "${work}/hex.env"
expect '참조 경로 안의 16진 비밀 → 1' 1 'HEX_SECRET'
manifest_with "${work}/hexvalue.env" "s|^audit_digest_key=.*|audit_digest_key=${hex_secret}|"
check_manifest "${work}/hexvalue.env"
expect '16진 비밀 값 → 1' 1 'HEX_SECRET'
base64url="Ab1$(head -c 33 /dev/urandom | base64 | tr -d '\n=' | tr '+/' '-_')"
manifest_with "${work}/b64url.env" "s|^credential_keys=.*|credential_keys=${base64url}|"
check_manifest "${work}/b64url.env"
expect 'base64url key → 1' 1 'LONG_BASE64'
manifest_with "${work}/agecomment.env"
printf '# age:%s\n' "${random_base64}" >>"${work}/agecomment.env"
check_manifest "${work}/agecomment.env"
expect '주석의 age: 비밀은 걷어 내지 않는다 → 1' 1 'LONG_BASE64'
manifest_with "${work}/fakeage.env" "s|^tombstone_age_key=.*|tombstone_age_key=age:${random_base64}|"
check_manifest "${work}/fakeage.env"
expect 'age 접두사가 아닌 age: 값 → 1' 1 'LONG_BASE64'
token="k9$(od -An -N 24 -tx1 /dev/urandom | tr -d ' \n' | tr '0-9' 'g-p')x7"
manifest_with "${work}/nametoken.env" "s|^credential_active_key_version=.*|credential_active_key_version=v${token}|"
check_manifest "${work}/nametoken.env"
expect '이름 칸의 긴 토큰 → 1' 1 'LONG_TOKEN'
manifest_with "${work}/reftoken.env" "s|^grafana=.*|grafana=ref:grafana/${token}|"
check_manifest "${work}/reftoken.env"
expect '참조 경로의 긴 토큰 → 1' 1 'LONG_TOKEN'

manifest_with "${work}/version.env" 's|^manifest_version=.*|manifest_version=latest|'
check_manifest "${work}/version.env"
expect '버전 아닌 manifest_version → 1' 1 'manifest_version: VALUE_SHAPE(version)'

check_manifest "${work}/absent.env"
expect '파일 없음 → 2' 2 '파일이 없다'
check_manifest
expect '인자 없음 → 2' 2 '사용법'

# ---------------------------------------------------------------- restore.sh 준비 거부

printf '\nrestore.sh 준비 거부\n'
override="${script_dir}/compose.rehearsal.yaml"
keys="${work}/keys"
mkdir -p "${keys}/identities"
printf 'offsite_remote=offsite:bokrilog-backup\n' >"${keys}/backup.env"

# restore <env 이름=값…> -- <인자…>. 호출자의 셸 환경이 판정에 새지 않게 이 함수가 넘길 값을 정한다.
restore() {
  local assignments=()
  while [ "$#" -gt 0 ] && [ "$1" != -- ]; do
    assignments+=("$1")
    shift
  done
  shift
  last_output="$(env -u BOKRILOG_ENV -u BOKRILOG_COMPOSE_OVERRIDE -u BOKRILOG_PROJECT \
    BOKRILOG_BACKUP_KEYS_DIR="${keys}" BOKRILOG_BACKUP_TOOLS="${work}/tools" BOKRILOG_RESTORE_DIR="${work}/restore" \
    BOKRILOG_STATE_DIR="${work}/state" BOKRILOG_SECRETS_FILE="${work}/absent.env" \
    BOKRILOG_TOMBSTONE_KEYS_DIR="${work}/tombstone-keys" \
    ${assignments[@]+"${assignments[@]}"} bash "${script_dir}/restore.sh" "$@" 2>&1)"
  last_status=$?
}

at='2026-10-13T09:00:00Z'
restore --
expect '인자 없음 → 2' 2 '--generation'
restore -- --generation gen_2026101 --failure-at "${at}"
expect 'generation 모양 밖 → 2' 2 'gen_YYYYMMDD'
restore -- --generation latest --failure-at 2026-10-13T09:00:00
expect '시간대 없는 장애 시각 → 2' 2 '시간대'
restore -- --generation latest --failure-at "${at}" --project bokrilog-production
expect '운영 프로젝트 이름 → 2' 2 '운영 프로젝트 위에 복원하지 않는다'
restore -- --generation latest --failure-at "${at}" --project bokrilog-restore-UPPER
expect '대문자가 든 격리 이름 → 2' 2 'bokrilog-restore-'
restore -- --generation latest --failure-at "${at}" --project restore-a
expect '접두사 밖 이름 → 2' 2 'bokrilog-restore-'
restore BOKRILOG_PROJECT=bokrilog-restore-a -- --generation latest --failure-at "${at}" --project bokrilog-restore-a
expect '운영 프로젝트로 정한 이름과 같다 → 2' 2 '운영 프로젝트'
restore BOKRILOG_ENV=ci -- --generation latest --failure-at "${at}"
expect '운영 모드의 ci 환경 → 2' 2 '운영 모드인데'
restore BOKRILOG_ENV=ci -- --generation latest --failure-at "${at}" --rehearsal
expect 'override 없는 리허설 → 2' 2 'override'
restore BOKRILOG_ENV=production "BOKRILOG_COMPOSE_OVERRIDE=${override}" -- --generation latest --failure-at "${at}" --rehearsal
expect '운영 환경의 리허설 → 2' 2 '리허설은'
restore "BOKRILOG_COMPOSE_OVERRIDE=${override}" -- --generation latest --failure-at "${at}"
expect '운영 모드의 override → 2' 2 '겹치는 compose'
restore -- --generation latest --failure-at "${at}" --keep
expect '알 수 없는 인자 → 2' 2 '알 수 없는 인자'
restore -- --generation latest --failure-at "${at}" --image ghcr.io/example/bokrilog-backend:1.2.3
expect 'tag만 있는 --image → 2' 2 '@sha256'
restore -- --generation latest --failure-at "${at}" --image "sha256:$(printf '%064d' 0)"
expect '운영 모드의 로컬 image ID → 2' 2 '@sha256'
restore -- --generation latest --failure-at "${at}" --tombstone-max-lag 1h
expect '숫자 아닌 --tombstone-max-lag → 2' 2 'tombstone-max-lag'
restore BOKRILOG_BACKUP_CONFIG="${work}/absent-backup.env" -- --generation latest --failure-at "${at}"
expect '설정 없음 → 2' 2 '설정 파일이 없다'
printf 'offsite_remote=offsite:bokrilog-backup/\n' >"${work}/slash.env"
restore BOKRILOG_BACKUP_CONFIG="${work}/slash.env" -- --generation latest --failure-at "${at}"
expect 'offsite_remote가 /로 끝남 → 2' 2 'offsite_remote'

# ---------------------------------------------------------------- tombstone-export.sh 준비 거부

printf '\ntombstone-export.sh 준비 거부\n'
tkeys="${work}/tombstone-keys"
mkdir -p "${tkeys}" "${keys}/recipients"
chmod 700 "${keys}" "${keys}/identities" "${keys}/recipients" "${tkeys}"
printf '[offsite]\ntype = local\n' >"${keys}/rclone.conf"
chmod 600 "${keys}/rclone.conf"
printf 'age1backupfixture0\n' >"${keys}/recipients/k1.pub"

export_run() {
  local assignments=()
  while [ "$#" -gt 0 ] && [ "$1" != -- ]; do
    assignments+=("$1")
    shift
  done
  shift
  last_output="$(env -u BOKRILOG_ENV -u BOKRILOG_COMPOSE_OVERRIDE -u BOKRILOG_PROJECT \
    BOKRILOG_BACKUP_KEYS_DIR="${keys}" BOKRILOG_BACKUP_TOOLS="${work}/tools" BOKRILOG_TOMBSTONE_DIR="${work}/tombstone" \
    BOKRILOG_STATE_DIR="${work}/state" BOKRILOG_SECRETS_FILE="${work}/absent.env" BOKRILOG_TOMBSTONE_KEYS_DIR="${tkeys}" \
    ${assignments[@]+"${assignments[@]}"} bash "${script_dir}/tombstone-export.sh" "$@" 2>&1)"
  last_status=$?
}

rehearsal_env=(BOKRILOG_ENV=ci "BOKRILOG_COMPOSE_OVERRIDE=${override}")
export_run -- --now
expect '알 수 없는 인자 → 2' 2 '알 수 없는 인자'
export_run BOKRILOG_ENV=production "BOKRILOG_COMPOSE_OVERRIDE=${override}" -- --rehearsal
expect '운영 환경의 리허설 → 2' 2 '리허설은'
export_run "${rehearsal_env[@]}" -- --rehearsal
expect 'recipient 없음 → 2' 2 'recipient가 없다'
printf 'age1backupfixture0\n' >"${tkeys}/recipient.pub"
chmod 644 "${tkeys}/recipient.pub"
export_run "${rehearsal_env[@]}" -- --rehearsal
expect 'backup key와 같은 recipient → 2' 2 'backup key와 같다'
printf 'age1tombstonefixture0\nage1tombstonefixture1\n' >"${tkeys}/recipient.pub"
export_run "${rehearsal_env[@]}" -- --rehearsal
expect 'recipient 둘 → 2' 2 '정확히 하나'
printf 'age1tombstonefixture0\n' >"${tkeys}/recipient.pub"
export_run "${rehearsal_env[@]}" -- --rehearsal
expect 'identity 없음 → 2' 2 'tombstone identity가 없다'
printf 'fixture-identity-placeholder\n' >"${tkeys}/identity.key"
chmod 600 "${tkeys}/identity.key"
export_run "${rehearsal_env[@]}" -- --rehearsal
expect '맞는 recipient·identity · 다음 준비(도구 없음) → 2' 2 'age·rclone이 고정 버전으로 설치돼 있지 않다'

# ---------------------------------------------------------------- backup-lib.sh epoch_of_iso

printf '\nbackup-lib.sh epoch_of_iso\n'
assert_equals 'epoch 0' "$(epoch_of_iso 1970-01-01T00:00:00Z)" '0'
assert_equals 'UTC 자정' "$(epoch_of_iso 2026-01-01T00:00:00Z)" '1767225600'
assert_equals '+09:00은 UTC로 접힌다' "$(epoch_of_iso 2026-01-01T09:00:00+09:00)" '1767225600'
assert_equals '-05:00은 UTC로 접힌다' "$(epoch_of_iso 2025-12-31T19:00:00-05:00)" '1767225600'
assert_equals '소수는 버린다' "$(epoch_of_iso 2026-01-01T00:00:00.999999999Z)" '1767225600'
assert_equals '윤일' "$(epoch_of_iso 2024-02-29T12:00:00Z)" '1709208000'
assert_equals '시간대 없음 → 빈 값' "$(epoch_of_iso 2026-01-01T00:00:00)" ''
assert_equals '날짜만 → 빈 값' "$(epoch_of_iso 2026-01-01)" ''
assert_equals '13월 → 빈 값' "$(epoch_of_iso 2026-13-01T00:00:00Z)" ''
assert_equals '평년 2월 29일 → 빈 값' "$(epoch_of_iso 2023-02-29T00:00:00Z)" ''
assert_equals '2100년(평년) 2월 29일 → 빈 값' "$(epoch_of_iso 2100-02-29T00:00:00Z)" ''
assert_equals '2000년(윤년) 2월 29일' "$(epoch_of_iso 2000-02-29T00:00:00Z)" '951782400'
assert_equals '4월 31일 → 빈 값' "$(epoch_of_iso 2026-04-31T00:00:00Z)" ''
assert_equals '+14:00은 받는다' "$(epoch_of_iso 2026-01-01T14:00:00+14:00)" '1767225600'
assert_equals '+14:01 → 빈 값' "$(epoch_of_iso 2026-01-01T00:00:00+14:01)" ''
assert_equals '-15:00 → 빈 값' "$(epoch_of_iso 2026-01-01T00:00:00-15:00)" ''
assert_equals '분 60 시간대 → 빈 값' "$(epoch_of_iso 2026-01-01T00:00:00+09:60)" ''
assert_equals 'generation 날짜와 같은 자정' "$(epoch_of_iso 2026-01-01T00:00:00Z)" "$(epoch_of_generation gen_20260101)"

# ---------------------------------------------------------------- 판정

printf '\n%s개 중 %s개 통과, %s개 실패\n' "${tests_run}" "$((tests_run - tests_failed))" "${tests_failed}"
if [ "${tests_failed}" -ne 0 ]; then
  printf '::error::복원 스크립트 시험이 실패했다.\n'
  exit 1
fi
exit 0
