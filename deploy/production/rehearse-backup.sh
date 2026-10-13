#!/usr/bin/env bash
# 일일 암호화 백업의 CI 리허설(Story 3.9).
#
# 왜 있는가: `backup.sh`의 완료 조건·실패·재시도·파기·키 확인은 문서가 아니라 실행으로 고정해야 한다. 이 스크립트가 운영 compose에
# 리허설 override(`compose.rehearsal.yaml` — trust DB, `BOKRILOG_ENV=ci`)와 S3 호환 저장소 둘(`compose.rehearsal-backup.yaml` — MinIO
# 두 개, digest 고정)을 겹치고, 실제 `pg_dump`·age·rclone으로 절차를 시나리오마다 끝까지 돈다. 시각은 가상 시계
# (`BOKRILOG_BACKUP_NOW`)로 옮기고 결함은 리허설 전용 주입(`BOKRILOG_BACKUP_FAULT`)이나 저장소 정지로 만든다. **이것은 실제 저장소의
# 증거가 아니다** — 실제 OCI·OCI 밖 저장소에서 파기를 확인하기 전까지 백업은 실저장소 미검증이다.
#
# 실행: deploy/production/rehearse-backup.sh
#
# 전제: `backend/build/libs/bokrilog-backend.jar`가 있다(빌드하지 않는다 — CI는 품질 job의 산출물을 쓴다). docker·compose v2·curl·
# unzip. age·rclone은 `install-backup-tools.sh`로 고정 버전을 받는다(캐시). 배포 리허설과 프로젝트 이름·포트가 달라 겹치지 않는다.
#
# 시나리오(SPEC I/O 표)
#    1. 정상            두 저장소 generations/에 암호문·checksum, SUCCEEDED, gauge 갱신(전에는 미등록), 평문 0, 암호문에 원문 없음
#    2. 같은 날 재실행  no-op, 행·object 불변
#    3. 단계 실패       dump·암호화·변조된 checksum·catalog → FAILED <code>, generations/ 불변, 평문 0, 로컬 임시본 정리
#    4. 한쪽 업로드 실패  OCI 밖 저장소 정지 → FAILED UPLOAD_OFFSITE, 성공 없음, 파기 실패 감사
#    5. 재시도          부분 object 정리(incoming/) 뒤 같은 generation 성공
#    5b. 한쪽 이동 실패  OCI 밖 이동 실패 → FAILED MOVE_OFFSITE, 이미 옮긴 OCI는 incoming/으로 되돌림(generations/ 불변)
#    5c. 신호           dump 중 SIGTERM → 바로 처리, 임시본 0, FAILED INTERRUPTED, 잠금 해제, 종료 1
#    6. 잠금            배포 중·다른 백업 중·복원 중·tombstone 내보내기 중(상한까지 기다림)·죽은 소유자(LOCK_STALE, 지우지 않음)
#                       → 기록 역할 없이 대기열
#    7. 수량 상한       잠금을 쥔 실행이 대기열을 먼저 기록한다. 오래된 것부터 COUNT 파기
#    8. 용량 상한       오래된 것부터 BYTES 파기, 마지막 성공은 남긴다
#    9. 29일 경계       정확히 29일은 남기고 1초 뒤 파기(마지막 성공도 예외 없음) — 그 실행은 PostgreSQL 정지 중(파기는 돌고 행은 대기열)
#   10. 30일 기한 초과  지우지 못한 30일 초과 → PURGE_FAILED·PURGE_OVERDUE·감사·gauge, `backup.sh purge`가 지운다
#   11. 키 회전·확인    새 active key 뒤 과거 identity를 지우거나 짝이 아니거나 저장소를 읽지 못하면 `keys` 실패
#   12. 큰 dump         수 MB(파이프 버퍼의 수십 배) — 복호화 stream을 끝까지 읽어 인증한다(리허설 전용 scratch 표)
#   13. 누출            실행 출력·도구 기록·Worker 컨테이너 로그·object 이름·메트릭에 사용자 ID·이메일·key 값·저장소 비밀이 없다
#
# 종료 코드
#   0 -- 모든 단정 통과
#   1 -- 단정 실패(`  실패` 줄)
#   2 -- 준비 실패(산출물·도구·docker 없음, 이미지 빌드·저장소 기동 실패)
#
# `BOKRILOG_REHEARSAL_DIAGNOSTICS`(디렉터리)를 주면 실패했을 때 정리 전에 실행 출력·도구 기록·컨테이너 로그를 그곳에 옮겨 둔다. key·
# 저장소 설정·비밀 파일은 옮기지 않는다.

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd "${script_dir}/../.." && pwd -P)"
# shellcheck source=lib.sh
. "${script_dir}/lib.sh"

backend_jar="${repo_root}/backend/build/libs/bokrilog-backend.jar"
project='bokrilog-backup-rehearsal'
oci_bind='127.0.0.1:19100'
offsite_bind='127.0.0.1:19101'

# 가상 시계의 기준: 2026-01-01 00:00 UTC. 시나리오는 이 날부터 날 수로 움직인다.
base_epoch=1767225600

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
  printf '\n::error::백업 리허설 준비 실패: %s\n' "$1" >&2
  if [ -n "${2:-}" ]; then
    printf '%s\n' "$2" >&2
  fi
  exit 2
}

assert_equals() {
  if [ "$2" = "$3" ]; then
    record_pass "$1"
  else
    record_fail "$1" "실제 '$2' (기대 '$3')"
  fi
}

[ -f "${backend_jar}" ] || fail_setup "백엔드 jar가 없다: ${backend_jar}" '먼저 `backend/gradlew --project-dir backend build`를 실행한다.'
command -v docker >/dev/null 2>&1 || fail_setup 'docker가 없다.'
docker compose version >/dev/null 2>&1 || fail_setup 'docker compose v2를 쓸 수 없다.'
command -v curl >/dev/null 2>&1 || fail_setup 'curl이 없다.'
have_sha256_tool || fail_setup 'sha256sum 또는 shasum이 없다.'

work="$(mktemp -d)" || fail_setup '임시 디렉터리를 만들지 못했다.'
[ -d "${work}" ] || fail_setup "mktemp -d가 쓸 수 없는 경로를 냈다: '${work}'"
state_dir="${work}/state"
keys_dir="${work}/keys"
backup_dir="${work}/backup"
tools_dir="${work}/tools"
outputs="${work}/outputs"
mkdir -p "${state_dir}" "${keys_dir}/identities" "${backup_dir}" "${outputs}"
chmod 700 "${keys_dir}" "${keys_dir}/identities"

image=''
cleaned=false
secrets_file="${work}/production.env"
s3_user="rehearsal$(random_hex 6)"
s3_secret="$(random_hex 20)"

compose() {
  BOKRILOG_STATE_DIR="${state_dir}" BOKRILOG_ENV=ci \
    BOKRILOG_IMAGE="${image:-none}" BOKRILOG_AUDIT_IMAGE="${image:-none}" BOKRILOG_BLUE_IMAGE="${image:-none}" \
    BOKRILOG_GREEN_IMAGE="${image:-none}" BOKRILOG_WORKER_IMAGE="${image:-none}" \
    BOKRILOG_REHEARSAL_S3_USER="${s3_user}" BOKRILOG_REHEARSAL_S3_SECRET="${s3_secret}" \
    BOKRILOG_REHEARSAL_OCI_BIND="${oci_bind}" BOKRILOG_REHEARSAL_OFFSITE_BIND="${offsite_bind}" \
    docker compose --project-name "${project}" --env-file "${secrets_file}" --file "${script_dir}/compose.yaml" \
    --file "${script_dir}/compose.rehearsal.yaml" --file "${script_dir}/compose.rehearsal-backup.yaml" "$@" </dev/null
}

keep_diagnostics() {
  local target="${BOKRILOG_REHEARSAL_DIAGNOSTICS:-}"
  [ -n "${target}" ] || return 0
  mkdir -p "${target}/tool-logs" || return 0
  cp "${outputs}"/*.log "${target}/" 2>/dev/null
  cp "${backup_dir}"/logs/*.log "${target}/tool-logs/" 2>/dev/null
  if [ -f "${secrets_file}" ]; then
    compose logs --no-color --timestamps >"${target}/compose.log" 2>&1
  fi
  printf '  진단 기록: %s\n' "${target}"
}

cleanup() {
  local exit_code="${1:-0}"
  [ "${cleaned}" = true ] && return
  cleaned=true
  printf '\n리허설 정리\n'
  if [ "${exit_code}" -ne 0 ] || [ "${tests_failed}" -ne 0 ]; then
    keep_diagnostics
  fi
  if [ -f "${secrets_file}" ]; then
    compose down --remove-orphans --volumes >/dev/null 2>&1 || true
  fi
  [ -n "${image}" ] && docker image rm --force "${image}" >/dev/null 2>&1
  rm -rf "${work}"
}
trap 'cleanup $?' EXIT
trap 'cleanup 130; exit 130' INT
trap 'cleanup 143; exit 143' TERM

printf '백업 리허설\n\n입력 준비\n'

# ---------------------------------------------------------------- 도구·이미지

bash "${script_dir}/install-backup-tools.sh" --prefix "${tools_dir}" >"${outputs}/install-tools.log" 2>&1 \
  || fail_setup 'age·rclone을 받지 못했다.' "$(tail -5 "${outputs}/install-tools.log")"
tool() {
  if [ -x "${tools_dir}/bin/$1" ]; then
    printf '%s' "${tools_dir}/bin/$1"
  else
    printf '%s' "${tools_dir}/bin/$1.exe"
  fi
}
age_keygen="$(tool age-keygen)"
rclone_bin="$(tool rclone)"
printf '  도구 %s\n' "$(bash "${script_dir}/install-backup-tools.sh" --check --prefix "${tools_dir}" 2>&1 | tail -1)"

image="$(docker build --quiet --label org.bokrilog.rehearsal=backup --file "${repo_root}/backend/Dockerfile" \
  "${repo_root}/backend" 2>"${outputs}/build.log" | tail -1)"
matches "${image}" 'sha256:[0-9a-f]{64}' || fail_setup '이미지를 만들지 못했다.' "$(tail -20 "${outputs}/build.log")"
printf '  이미지 %s\n' "${image}"
printf 'active_slot=blue\nactive_image=%s\nworker_image=%s\n' "${image}" "${image}" >"${state_dir}/deploy.state"

# ---------------------------------------------------------------- 비밀·key·저장소 설정(실행마다 만든다)

{
  printf 'BOKRILOG_DB_URL=jdbc:postgresql://postgres:5432/bokrilog\n'
  printf 'BOKRILOG_DB_USERNAME=bokrilog\n'
  printf 'BOKRILOG_DB_PASSWORD=%s\n' "$(random_hex 16)"
  printf 'POSTGRES_PASSWORD=%s\n' "$(random_hex 16)"
  printf 'BOKRILOG_PUBLIC_BASE_URL=http://127.0.0.1:18099\n'
  printf 'BOKRILOG_AUDIT_DIGEST_KEY=%s\n' "$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
  printf 'BOKRILOG_SOURCE_CREDENTIAL_KEYS=rehearsal:%s\n' "$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
  printf 'BOKRILOG_SOURCE_CREDENTIAL_ACTIVE_KEY_VERSION=rehearsal\n'
  printf 'BOKRILOG_BYBIT_BASE_URL=https://bybit.rehearsal.test\n'
  printf 'BOKRILOG_BYBIT_RESERVED_IP=203.0.113.10\n'
  printf 'BOKRILOG_SUPPORT_CONTACT=support@bokrilog.test\n'
} >"${secrets_file}"
chmod 600 "${secrets_file}"

# new_key <key ID>. identity·recipient를 만들고 active.pub을 그 key로 바꾼다(운영 절차와 같은 배치 — DEVELOPMENT.md `### 키`).
mkdir -p "${keys_dir}/recipients"
chmod 700 "${keys_dir}/recipients"
new_key() {
  "${age_keygen}" -o "${keys_dir}/identities/$1.key" >/dev/null 2>>"${outputs}/keys.log" \
    || fail_setup "key $1을 만들지 못했다." "$(tail -3 "${outputs}/keys.log")"
  chmod 600 "${keys_dir}/identities/$1.key"
  "${age_keygen}" -y "${keys_dir}/identities/$1.key" >"${keys_dir}/recipients/$1.pub" 2>>"${outputs}/keys.log" \
    || fail_setup "key $1의 recipient를 쓰지 못했다."
  chmod 644 "${keys_dir}/recipients/$1.pub"
  {
    printf '# key_id=%s\n' "$1"
    cat "${keys_dir}/recipients/$1.pub"
  } >"${keys_dir}/active.pub" || fail_setup "key $1의 active.pub을 쓰지 못했다."
  chmod 644 "${keys_dir}/active.pub"
}
new_key k1

oci_bucket='bokrilog-oci-rehearsal'
offsite_bucket='bokrilog-offsite-rehearsal'
{
  for pair in "oci:${oci_bind}" "offsite:${offsite_bind}"; do
    printf '[%s]\ntype = s3\nprovider = Minio\nendpoint = http://%s\nregion = us-east-1\nforce_path_style = true\n' \
      "${pair%%:*}" "${pair#*:}"
    printf 'access_key_id = %s\nsecret_access_key = %s\n\n' "${s3_user}" "${s3_secret}"
  done
} >"${keys_dir}/rclone.conf"
chmod 600 "${keys_dir}/rclone.conf"

quota=1073741824
# write_config <max_generations> <max_bytes>. 두 저장소에 같은 상한·quota.
write_config() {
  {
    printf 'oci_remote=oci:%s\n' "${oci_bucket}"
    printf 'offsite_remote=offsite:%s\n' "${offsite_bucket}"
    printf 'max_generations=%s\n' "$1"
    printf 'max_bytes_oci=%s\nmax_bytes_offsite=%s\n' "$2" "$2"
    printf 'quota_bytes_oci=%s\nquota_bytes_offsite=%s\n' "${quota}" "${quota}"
  } >"${keys_dir}/backup.env"
}
write_config 30 "${quota}"

rclone_run() {
  "${rclone_bin}" --config "${keys_dir}/rclone.conf" --retries 1 --low-level-retries 2 --contimeout 5s --log-level ERROR "$@"
}

# ---------------------------------------------------------------- 스택

wait_store() {
  local waited=0
  while [ "${waited}" -lt 60 ]; do
    curl --silent --fail --max-time 3 --output /dev/null "http://$1/minio/health/live" && return 0
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

compose up -d postgres minio-oci minio-offsite >"${outputs}/stack.log" 2>&1 \
  || fail_setup '스택을 띄우지 못했다.' "$(tail -10 "${outputs}/stack.log")"
compose up -d --wait --wait-timeout 120 postgres >>"${outputs}/stack.log" 2>&1 \
  || fail_setup 'PostgreSQL이 준비되지 않았다.' "$(tail -10 "${outputs}/stack.log")"
wait_store "${oci_bind}" && wait_store "${offsite_bind}" || fail_setup '저장소가 준비되지 않았다.'
rclone_run mkdir "oci:${oci_bucket}" >>"${outputs}/stack.log" 2>&1 \
  && rclone_run mkdir "offsite:${offsite_bucket}" >>"${outputs}/stack.log" 2>&1 \
  || fail_setup '저장소 bucket을 만들지 못했다.' "$(tail -5 "${outputs}/stack.log")"
compose --profile migrate run --rm --no-deps -T migrate >>"${outputs}/stack.log" 2>&1 \
  || fail_setup 'migration이 실패했다.' "$(tail -20 "${outputs}/stack.log")"

psql_value() {
  compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password --tuples-only --no-align \
    --command="$1" 2>>"${outputs}/psql.log" | tr -d '[:space:]'
}

# 데이터 있는 DB. 사용자 ID와 이메일 모양의 식별자는 누출 검사의 표지다(실행마다 만든다).
marker_user="$(od -An -N 16 -tx1 /dev/urandom | tr -d ' \n' | awk '{ print substr($0,1,8) "-" substr($0,9,4) "-4" substr($0,14,3) "-a" substr($0,18,3) "-" substr($0,21,12) }')"
marker_email="rehearsal.$(random_hex 5)@bokrilog.test"
compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password --quiet --set=ON_ERROR_STOP=1 \
  --command="INSERT INTO identity_account_lifecycle (user_id, state) VALUES ('${marker_user}', 'ACTIVE'); INSERT INTO identity_account (user_id) VALUES ('${marker_user}'); INSERT INTO identity_social_link (link_id, user_id, provider, issuer, subject) VALUES (gen_random_uuid(), '${marker_user}', 'google', 'https://issuer.rehearsal.test', '${marker_email}')" \
  >>"${outputs}/psql.log" 2>&1 || fail_setup '표지 데이터를 넣지 못했다.' "$(tail -5 "${outputs}/psql.log")"

# 메트릭을 내는 Worker(운영 compose의 그 서비스). 백업 기록이 없을 때의 미등록부터 본다.
compose up -d --no-deps worker >>"${outputs}/stack.log" 2>&1 || fail_setup 'Worker를 띄우지 못했다.'
compose up -d --no-deps --wait --wait-timeout 180 worker >>"${outputs}/stack.log" 2>&1 \
  || fail_setup 'Worker가 준비되지 않았다.' "$(compose logs --no-color worker 2>&1 | tail -20)"

scrape() {
  compose exec -T worker wget -q -O - http://127.0.0.1:18081/actuator/prometheus 2>/dev/null
}

# series_value <시계열 이름과 label 머리>. 값(그 줄의 마지막 칸). 없으면 빈 문자열.
series_value() {
  scrape | awk -v want="$1" 'index($0, want) == 1 { print $NF; exit }'
}

# wait_series <머리> <기대 awk 식(v)> <초>. 시계열 값이 식을 만족할 때까지 기다린다(측정은 60초 주기).
wait_series() {
  local waited=0 value
  while [ "${waited}" -lt "$3" ]; do
    value="$(series_value "$1")"
    if [ -n "${value}" ] && awk -v v="${value}" "BEGIN { exit !($2) }"; then
      printf '%s' "${value}"
      return 0
    fi
    sleep 3
    waited=$((waited + 3))
  done
  printf '%s' "${value:-}"
  return 1
}

export BOKRILOG_BACKUP_KEYS_DIR="${keys_dir}"
export BOKRILOG_BACKUP_DIR="${backup_dir}"
export BOKRILOG_BACKUP_TOOLS="${tools_dir}"
export BOKRILOG_STATE_DIR="${state_dir}"
export BOKRILOG_SECRETS_FILE="${secrets_file}"
export BOKRILOG_COMPOSE_OVERRIDE="${script_dir}/compose.rehearsal.yaml"
export BOKRILOG_PROJECT="${project}"
export BOKRILOG_ENV=ci
export BOKRILOG_BACKUP_ACTOR=rehearsal-backup
unset BOKRILOG_BACKUP_FAULT BOKRILOG_BACKUP_NOW BOKRILOG_BACKUP_CONFIG

# ---------------------------------------------------------------- 관측 도우미

run_status=0
run_log=''
run_count=0

# at_day <날> [초]. 가상 시계 epoch(그날 03:00 UTC + 초).
at_day() {
  printf '%s' "$((base_epoch + $1 * 86400 + 3 * 3600 + ${2:-0}))"
}

# gen_of_day <날>. 그날의 generation ID.
gen_of_day() {
  awk -v z="$(((base_epoch + $1 * 86400) / 86400))" 'BEGIN {
    z += 719468; era = int(z / 146097); doe = z - era * 146097
    yoe = int((doe - int(doe / 1460) + int(doe / 36524) - int(doe / 146096)) / 365)
    y = yoe + era * 400; doy = doe - (365 * yoe + int(yoe / 4) - int(yoe / 100))
    mp = int((5 * doy + 2) / 153); d = doy - int((153 * mp + 2) / 5) + 1; m = mp + (mp < 10 ? 3 : -9)
    printf "gen_%04d%02d%02d", y + (m <= 2), m, d
  }'
}

# run_backup <라벨> <epoch> [결함]
run_backup() {
  run_count=$((run_count + 1))
  run_log="${outputs}/run-${run_count}.log"
  printf '\n%s\n' "$1"
  BOKRILOG_BACKUP_NOW="$2" BOKRILOG_BACKUP_FAULT="${3:-}" bash "${script_dir}/backup.sh" run --rehearsal \
    >"${run_log}" 2>&1
  run_status=$?
}

run_keys() {
  run_count=$((run_count + 1))
  run_log="${outputs}/keys-${run_count}.log"
  bash "${script_dir}/backup.sh" keys --rehearsal >"${run_log}" 2>&1
  run_status=$?
}

show_log_on_failure() {
  [ "${tests_failed}" -eq "$1" ] || { printf '        --- backup 출력 ---\n'; sed 's/^/        /' "${run_log}" | tail -40; }
}

# listing <store> <prefix>. 이름만, 정렬.
listing() {
  local bucket="${oci_bucket}"
  [ "$1" = offsite ] && bucket="${offsite_bucket}"
  rclone_run lsf --files-only "$1:${bucket}/$2" 2>/dev/null | sort | tr '\n' ' ' | sed 's/ $//'
}

# objects_of <gen…>. 두 파일(암호문·checksum)의 기대 목록.
objects_of() {
  local gen key="${OBJECT_KEY:-k1}" out=''
  for gen in "$@"; do
    out="${out} ${gen}.${key}.dump.age ${gen}.${key}.dump.age.sha256"
  done
  printf '%s' "${out# }"
}

events_of() {
  psql_value "SELECT string_agg(event || COALESCE(':' || store, '') || COALESCE(':' || code, ''), ',' ORDER BY sequence) FROM operations_backup_event WHERE generation_id = '$1'"
}

plaintext_left() {
  find "${backup_dir}/tmp" -type f 2>/dev/null | wc -l | tr -d ' '
}

assert_layout() {
  assert_equals "$1 · oci generations/" "$(listing oci generations)" "$2"
  assert_equals "$1 · offsite generations/" "$(listing offsite generations)" "$2"
  assert_equals "$1 · 로컬 임시본·평문 0" "$(plaintext_left)" '0'
}

# pending_count. 기록하지 못해 대기열에 남은 묶음 수.
pending_count() {
  find "${backup_dir}/pending" -type f -name '*.rows' 2>/dev/null | wc -l | tr -d ' '
}

# hold_lock <kind> <pid>. 배포·백업이 잠금을 쥔 상태를 흉내 낸다(`lib.sh`의 소유자 파일과 같은 모양).
hold_lock() {
  mkdir -p "${state_dir}/.lock"
  printf 'kind=%s\npid=%s\noperation=rehearsal_%s\nstarted=%s\n' "$1" "$2" "$1" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    >"${state_dir}/.lock/owner"
}

release_lock() {
  rm -f "${state_dir}/.lock/owner"
  rmdir "${state_dir}/.lock" 2>/dev/null
}

wait_postgres() {
  compose up -d --no-deps --wait --wait-timeout 120 postgres >>"${outputs}/stack.log" 2>&1
}

# restart_worker. DB가 멈췄다 돌아오면 Worker는 단일 잠금 연결을 잃고 끝난다(76). 메트릭을 다시 보려고 새로 띄운다.
restart_worker() {
  compose up -d --no-deps --force-recreate --wait --wait-timeout 180 worker >>"${outputs}/stack.log" 2>&1
}

success_rows() {
  psql_value "SELECT count(*) FROM operations_backup_event WHERE event = 'SUCCEEDED'"
}

# ---------------------------------------------------------------- 1. 정상

day0="$(gen_of_day 0)"
failed_before="${tests_failed}"
printf '\n1. 정상 전: 백업 기록이 없으면 gauge를 등록하지 않는다\n'
if wait_series 'bokrilog_sync_queue_age_seconds' 'v >= 0' 90 >/dev/null; then
  if scrape | grep -E '^bokrilog_backup_' >/dev/null; then
    record_fail '기록 전 · 백업 gauge 미등록' "$(scrape | grep -E '^bokrilog_backup_' | head -3)"
  else
    record_pass '기록 전 · 백업 gauge 미등록(NaN도 없다)'
  fi
else
  record_fail '기록 전 · Worker 측정' 'Worker가 측정하지 않는다.'
fi

run_backup "1. 정상(${day0})" "$(at_day 0)"
assert_equals '정상 · 종료 0' "${run_status}" '0'
assert_layout '정상' "$(objects_of "${day0}")"
assert_equals '정상 · incoming/ 비어 있음(oci·offsite)' "$(listing oci incoming)|$(listing offsite incoming)" '|'
assert_equals '정상 · 기록' "$(events_of "${day0}")" 'STARTED,SUCCEEDED,MEASURED:oci,MEASURED:offsite'
table_sha="$(psql_value "SELECT sha256 FROM operations_backup_event WHERE event = 'SUCCEEDED' AND generation_id = '${day0}'")"
rclone_run copyto "oci:${oci_bucket}/generations/${day0}.k1.dump.age" "${work}/fetched.age" >/dev/null 2>&1
assert_equals '정상 · 저장소의 암호문 SHA-256 = 기록' "$(sha256_of "${work}/fetched.age")" "${table_sha}"
sum_text="$(rclone_run cat "offsite:${offsite_bucket}/generations/${day0}.k1.dump.age.sha256" 2>/dev/null | tr -d '\r')"
assert_equals '정상 · checksum 파일 첫 줄 = 기록' "$(printf '%s\n' "${sum_text}" | sed -n 1p)" "${table_sha}  ${day0}.k1.dump.age"
if matches "$(printf '%s\n' "${sum_text}" | sed -n 2p)" 'dump_started_at=[0-9]{9,11}'; then
  record_pass '정상 · checksum 파일 둘째 줄은 dump 시작 시각(복원의 RPO 기준)'
else
  record_fail '정상 · checksum 파일 둘째 줄은 dump 시작 시각(복원의 RPO 기준)' "실제 '${sum_text}'"
fi
assert_equals '정상 · age 형식' "$(head -c 21 "${work}/fetched.age")" 'age-encryption.org/v1'
if grep -F "${marker_email}" "${work}/fetched.age" >/dev/null || grep -F "${marker_user}" "${work}/fetched.age" >/dev/null; then
  record_fail '정상 · 암호문에 원문 표지가 없다' '암호문에 표지가 그대로 있다.'
else
  record_pass '정상 · 암호문에 원문 표지가 없다'
fi
rm -f "${work}/fetched.age"
assert_equals '정상 · 측정 generation 수' \
  "$(psql_value "SELECT string_agg(store || ':' || count, ',' ORDER BY store) FROM operations_backup_event WHERE event = 'MEASURED' AND generation_id = '${day0}'")" \
  'oci:1,offsite:1'
show_log_on_failure "${failed_before}"

printf '\n   gauge(60초 주기 측정)\n'
recorded_epoch="$(psql_value "SELECT floor(extract(epoch FROM max(recorded_at))) FROM operations_backup_event WHERE event = 'SUCCEEDED'")"
gauge="$(wait_series 'bokrilog_backup_last_success_timestamp_seconds{' "int(v) == ${recorded_epoch:-0}" 120)"
assert_equals '정상 · last_success gauge = 성공 기록 시각' "$(awk -v v="${gauge:-0}" 'BEGIN { printf "%d", int(v) }')" "${recorded_epoch}"
if scrape | grep -E '^bokrilog_backup_last_success_timestamp_seconds\{env="ci",role="worker"\} ' >/dev/null; then
  record_pass '정상 · gauge label은 env·role뿐이다'
else
  record_fail '정상 · gauge label은 env·role뿐이다' "$(scrape | grep -E '^bokrilog_backup_' | head -3)"
fi
for store in oci offsite; do
  # 측정 행은 결과 행과 따로 기록되므로(뒤의 묶음) Worker가 그 사이에 한 번 읽었을 수 있다 — 다음 주기까지 기다린다.
  usage="$(wait_series "bokrilog_backup_store_usage_ratio{env=\"ci\",role=\"worker\",store=\"${store}\"}" 'v > 0 && v < 0.01' 120)"
  if [ -n "${usage}" ] && awk -v v="${usage}" 'BEGIN { exit !(v > 0 && v < 0.01) }'; then
    record_pass "정상 · ${store} 사용률 gauge(${usage})"
  else
    record_fail "정상 · ${store} 사용률 gauge" "값 '${usage}'"
  fi
  overdue_value="$(series_value "bokrilog_backup_purge_overdue{env=\"ci\",role=\"worker\",store=\"${store}\"}")"
  assert_equals "정상 · ${store} 기한 초과 gauge 0" \
    "$(awk -v v="${overdue_value:-none}" 'BEGIN { if (v == "none") print "없음"; else printf "%d", v }')" '0'
  if [ -n "$(series_value "bokrilog_backup_store_measured_timestamp_seconds{env=\"ci\",role=\"worker\",store=\"${store}\"}")" ]; then
    record_pass "정상 · ${store} 측정 시각 gauge"
  else
    record_fail "정상 · ${store} 측정 시각 gauge" '시계열이 없다.'
  fi
done

# ---------------------------------------------------------------- 2. 같은 날 재실행

failed_before="${tests_failed}"
rows_before="$(psql_value 'SELECT count(*) FROM operations_backup_event')"
audit_before="$(psql_value "SELECT count(*) FROM operations_audit_event WHERE command = 'backup'")"
run_backup "2. 같은 날 재실행(${day0})" "$(at_day 0 3600)"
assert_equals '재실행 · 종료 0' "${run_status}" '0'
assert_equals '재실행 · 행 불변' "$(psql_value 'SELECT count(*) FROM operations_backup_event')" "${rows_before}"
assert_layout '재실행' "$(objects_of "${day0}")"
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 3. 단계 실패

day1="$(gen_of_day 1)"
# 앞선 실행이 SIGKILL로 남긴 임시본(평문 모양)을 흉내 낸다. 다음 실행이 지운다.
mkdir -p "${backup_dir}/tmp/run.leftover"
printf 'PGDMP leftover plaintext\n' >"${backup_dir}/tmp/run.leftover/${day1}.dump"
for case_fault in dump:DUMP encrypt:ENCRYPT tamper-oci:CHECKSUM_OCI catalog:CATALOG; do
  failed_before="${tests_failed}"
  run_backup "3. 단계 실패 ${case_fault%%:*}(${day1})" "$(at_day 1)" "${case_fault%%:*}"
  assert_equals "실패 ${case_fault%%:*} · 종료 1" "${run_status}" '1'
  assert_equals "실패 ${case_fault%%:*} · 마지막 기록은 FAILED ${case_fault#*:}" \
    "$(psql_value "SELECT event || ':' || code FROM operations_backup_event WHERE generation_id = '${day1}' AND event IN ('FAILED', 'SUCCEEDED') ORDER BY sequence DESC LIMIT 1")" \
    "FAILED:${case_fault#*:}"
  assert_layout "실패 ${case_fault%%:*}" "$(objects_of "${day0}")"
  assert_equals "실패 ${case_fault%%:*} · 성공 기록 불변" "$(success_rows)" '1'
  show_log_on_failure "${failed_before}"
done
assert_equals '실패 · 앞선 실행의 로컬 임시본을 지웠다' \
  "$(psql_value "SELECT count(*) FROM operations_backup_event WHERE generation_id = '${day1}' AND event = 'PURGED' AND code = 'LOCAL_TEMP'")" '1'
assert_equals '실패 · 변조 뒤 남은 oci incoming/을 다음 실행이 정리했다' \
  "$(psql_value "SELECT count FROM operations_backup_event WHERE generation_id = '${day1}' AND event = 'PURGED' AND store = 'oci' AND code = 'INCOMING_CLEARED'")" '2'

# ---------------------------------------------------------------- 4. 한쪽 업로드 실패

failed_before="${tests_failed}"
compose stop minio-offsite >>"${outputs}/stack.log" 2>&1
audit_before="$(psql_value "SELECT count(*) FROM operations_audit_event WHERE command = 'backup' AND reason = 'PURGE_FAILED'")"
run_backup "4. 한쪽 업로드 실패(OCI 밖 저장소 정지, ${day1})" "$(at_day 1 60)"
assert_equals '한쪽 실패 · 종료 1' "${run_status}" '1'
assert_equals '한쪽 실패 · FAILED UPLOAD_OFFSITE' \
  "$(psql_value "SELECT event || ':' || store || ':' || code FROM operations_backup_event WHERE generation_id = '${day1}' AND event = 'FAILED' ORDER BY sequence DESC LIMIT 1")" \
  'FAILED:offsite:UPLOAD_OFFSITE'
assert_equals '한쪽 실패 · 성공 기록 없음' "$(success_rows)" '1'
assert_equals '한쪽 실패 · oci generations/ 불변' "$(listing oci generations)" "$(objects_of "${day0}")"
assert_equals '한쪽 실패 · oci incoming/에 검증된 부분 object' "$(listing oci incoming)" "$(objects_of "${day1}")"
assert_equals '한쪽 실패 · 읽지 못한 저장소의 파기 실패 감사' \
  "$(psql_value "SELECT count(*) FROM operations_audit_event WHERE command = 'backup' AND scope = 'BACKUP' AND reason = 'PURGE_FAILED' AND actor_kind = 'SYSTEM' AND details->>'store' = 'offsite' AND details->>'rehearsal' = 'true'")" \
  "$((audit_before + 1))"
assert_equals '한쪽 실패 · 평문 0' "$(plaintext_left)" '0'
show_log_on_failure "${failed_before}"
compose start minio-offsite >>"${outputs}/stack.log" 2>&1
wait_store "${offsite_bind}" || fail_setup 'OCI 밖 저장소가 다시 뜨지 않았다.'

# ---------------------------------------------------------------- 5. 재시도

failed_before="${tests_failed}"
# 끊긴 업로드가 남긴 부분 object(앞부분만 있는 암호문).
printf 'age-encryption.org/v1\npartial' | rclone_run rcat "offsite:${offsite_bucket}/incoming/${day1}.k1.dump.age" \
  >>"${outputs}/stack.log" 2>&1
run_backup "5. 재시도(부분 object 정리 뒤, ${day1})" "$(at_day 1 120)"
assert_equals '재시도 · 종료 0' "${run_status}" '0'
assert_layout '재시도' "$(objects_of "${day0}" "${day1}")"
assert_equals '재시도 · incoming/ 비어 있음(oci·offsite)' "$(listing oci incoming)|$(listing offsite incoming)" '|'
assert_equals '재시도 · 부분 object 정리 기록' \
  "$(psql_value "SELECT string_agg(store || ':' || count, ',' ORDER BY store) FROM operations_backup_event WHERE generation_id = '${day1}' AND event = 'PURGED' AND code = 'INCOMING_CLEARED' AND sequence > (SELECT max(sequence) FROM operations_backup_event WHERE event = 'STARTED' AND generation_id = '${day1}')")" \
  'oci:2,offsite:1'
assert_equals '재시도 · 같은 generation이 성공' \
  "$(psql_value "SELECT count(*) FROM operations_backup_event WHERE event = 'SUCCEEDED' AND generation_id = '${day1}'")" '1'
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 5b. 한쪽 이동 실패

day26="$(gen_of_day 26)"
failed_before="${tests_failed}"
run_backup "5b. OCI 밖 저장소의 generations/ 이동 실패(${day26})" "$(at_day 26)" move-offsite
assert_equals '이동 실패 · 종료 1' "${run_status}" '1'
assert_equals '이동 실패 · FAILED MOVE_OFFSITE' \
  "$(psql_value "SELECT event || ':' || store || ':' || code FROM operations_backup_event WHERE generation_id = '${day26}' AND event IN ('FAILED', 'SUCCEEDED') ORDER BY sequence DESC LIMIT 1")" \
  'FAILED:offsite:MOVE_OFFSITE'
assert_layout '이동 실패 · 기록되지 않은 generation이 generations/에 없다' "$(objects_of "${day0}" "${day1}")"
assert_equals '이동 실패 · 이미 옮긴 OCI는 incoming/으로 되돌렸다' "$(listing oci incoming)" "$(objects_of "${day26}")"
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 5c. 신호

day27="$(gen_of_day 27)"
failed_before="${tests_failed}"
run_count=$((run_count + 1))
run_log="${outputs}/run-${run_count}.log"
printf '\n5c. dump 중 SIGTERM(%s)\n' "${day27}"
BOKRILOG_BACKUP_NOW="$(at_day 27)" BOKRILOG_BACKUP_FAULT=slow-dump bash "${script_dir}/backup.sh" run --rehearsal \
  >"${run_log}" 2>&1 &
signal_pid=$!
waited=0
while [ "${waited}" -lt 240 ]; do
  grep -F '5. pg_dump' "${run_log}" >/dev/null 2>&1 && break
  sleep 1
  waited=$((waited + 1))
done
sleep 3
kill -TERM "${signal_pid}" 2>/dev/null
signal_started="$(date +%s)"
wait "${signal_pid}"
run_status=$?
signal_seconds=$(($(date +%s) - signal_started))
assert_equals '신호 · 종료 1' "${run_status}" '1'
if [ "${signal_seconds}" -lt 150 ]; then
  record_pass "신호 · 처리기가 바로 돌았다(${signal_seconds}초 — dump 단계를 기다리지 않는다)"
else
  record_fail '신호 · 처리기가 바로 돌았다' "${signal_seconds}초 걸렸다."
fi
assert_equals '신호 · 임시본·평문 0' "$(plaintext_left)" '0'
assert_equals '신호 · 잠금을 놓았다' "$([ -d "${state_dir}/.lock" ] && printf held || printf released)" 'released'
assert_equals '신호 · 마지막 기록은 FAILED INTERRUPTED' \
  "$(psql_value "SELECT event || ':' || code FROM operations_backup_event WHERE generation_id = '${day27}' AND event IN ('FAILED', 'SUCCEEDED') ORDER BY sequence DESC LIMIT 1")" \
  'FAILED:INTERRUPTED'
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 6. 잠금(배포 중·다른 백업 중·죽은 소유자)

day2="$(gen_of_day 2)"
failed_before="${tests_failed}"
pending_before="$(pending_count)"
hold_lock deploy "$$"
run_backup "6. 배포 중(${day2})" "$(at_day 2)"
assert_equals '배포 중 · 종료 1' "${run_status}" '1'
assert_equals '배포 중 · 기록 역할을 띄우지 않았다(아직 행 없음)' "$(events_of "${day2}")" ''
assert_equals '배포 중 · 실패 행은 대기열에' "$(pending_count)" "$((pending_before + 1))"
assert_equals '배포 중 · 배포의 잠금을 건드리지 않았다' "$(kv_get "${state_dir}/.lock/owner" kind)" 'deploy'
hold_lock backup "$$"
run_backup "6. 다른 백업 중(${day2})" "$(at_day 2 60)"
assert_equals '다른 백업 중 · 종료 1' "${run_status}" '1'
if grep -F '다른 백업이 진행 중' "${run_log}" >/dev/null; then
  record_pass '다른 백업 중 · BACKUP_IN_PROGRESS로 보고한다'
else
  record_fail '다른 백업 중 · BACKUP_IN_PROGRESS로 보고한다' "$(tail -3 "${run_log}")"
fi
# 복원·tombstone 내보내기(Story 3.10)도 같은 잠금을 쓴다. 복원은 기다리지 않고, 내보내기는 짧으므로 상한까지 기다린 뒤 실패한다.
hold_lock restore "$$"
run_backup "6. 복원 중(${day2})" "$(at_day 2 70)"
assert_equals '복원 중 · 종료 1' "${run_status}" '1'
if grep -F 'FAILED RESTORE_IN_PROGRESS' "${run_log}" >/dev/null; then
  record_pass '복원 중 · RESTORE_IN_PROGRESS로 보고한다(기다리지 않는다)'
else
  record_fail '복원 중 · RESTORE_IN_PROGRESS로 보고한다(기다리지 않는다)' "$(tail -3 "${run_log}")"
fi
hold_lock tombstone "$$"
export BOKRILOG_BACKUP_LOCK_WAIT=6
run_backup "6. tombstone 내보내기 중(${day2})" "$(at_day 2 80)"
unset BOKRILOG_BACKUP_LOCK_WAIT
assert_equals 'tombstone 내보내기 중 · 종료 1' "${run_status}" '1'
if grep -F '최대 6초 기다린다' "${run_log}" >/dev/null && grep -F 'FAILED TOMBSTONE_IN_PROGRESS' "${run_log}" >/dev/null; then
  record_pass 'tombstone 내보내기 중 · 상한까지 기다린 뒤 TOMBSTONE_IN_PROGRESS로 보고한다'
else
  record_fail 'tombstone 내보내기 중 · 상한까지 기다린 뒤 TOMBSTONE_IN_PROGRESS로 보고한다' "$(tail -3 "${run_log}")"
fi
sleep 0 &
dead_pid=$!
wait "${dead_pid}"
hold_lock deploy "${dead_pid}"
run_backup "6. 죽은 소유자의 잠금(${day2})" "$(at_day 2 120)"
assert_equals '죽은 소유자 · 종료 1' "${run_status}" '1'
if grep -F 'LOCK_STALE' "${run_log}" >/dev/null; then
  record_pass '죽은 소유자 · LOCK_STALE로 보고한다'
else
  record_fail '죽은 소유자 · LOCK_STALE로 보고한다' "$(tail -3 "${run_log}")"
fi
assert_equals '죽은 소유자 · 잠금을 스스로 지우지 않는다' "$([ -d "${state_dir}/.lock" ] && printf held || printf released)" 'held'
release_lock
assert_layout '잠금' "$(objects_of "${day0}" "${day1}")"
assert_equals '잠금 · 대기열 다섯' "$(pending_count)" "$((pending_before + 5))"
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 7. 수량 상한(대기열 기록)

write_config 3 "${quota}"
day3="$(gen_of_day 3)"
failed_before="${tests_failed}"
run_backup "7. 수량 상한 3(${day2}) — 잠금을 쥐면 대기열을 먼저 기록한다" "$(at_day 2 600)"
assert_equals '수량 · 셋째 generation · 종료 0' "${run_status}" '0'
assert_equals '대기열 · 비었다' "$(pending_count)" '0'
assert_equals '대기열 · 잠금 실패 행 다섯이 기록됐다' \
  "$(psql_value "SELECT string_agg(code, ',' ORDER BY code) FROM operations_backup_event WHERE generation_id = '${day2}' AND event = 'FAILED'")" \
  'BACKUP_IN_PROGRESS,DEPLOY_IN_PROGRESS,LOCK_STALE,RESTORE_IN_PROGRESS,TOMBSTONE_IN_PROGRESS'
assert_equals '대기열 · 그 행들이 이 실행의 시작보다 먼저다' \
  "$(psql_value "SELECT bool_and(f.sequence < s.sequence) FROM operations_backup_event f, operations_backup_event s WHERE f.generation_id = '${day2}' AND f.event = 'FAILED' AND s.generation_id = '${day2}' AND s.event = 'STARTED'")" \
  't'
run_backup "7. 수량 상한 3(${day3})" "$(at_day 3)"
assert_equals '수량 · 넷째 generation · 종료 0' "${run_status}" '0'
assert_layout '수량' "$(objects_of "${day1}" "${day2}" "${day3}")"
assert_equals '수량 · 가장 오래된 것을 COUNT로 파기(두 저장소)' \
  "$(psql_value "SELECT string_agg(store || ':' || code, ',' ORDER BY store) FROM operations_backup_event WHERE event = 'PURGED' AND generation_id = '${day0}'")" \
  'oci:COUNT,offsite:COUNT'
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 8. 용량 상한

size="$(psql_value "SELECT bytes / count FROM operations_backup_event WHERE event = 'MEASURED' AND store = 'oci' ORDER BY sequence DESC LIMIT 1")"
matches "${size:-}" '[0-9]+' || size=1
day4="$(gen_of_day 4)"
day5="$(gen_of_day 5)"
failed_before="${tests_failed}"
write_config 3 "$((size * 5 / 2))"
run_backup "8. 용량 상한 2.5개분(${day4})" "$(at_day 4)"
assert_equals '용량 · 종료 0' "${run_status}" '0'
assert_layout '용량' "$(objects_of "${day3}" "${day4}")"
assert_equals '용량 · COUNT 뒤 BYTES로 오래된 것부터' \
  "$(psql_value "SELECT string_agg(generation_id || ':' || code, ',' ORDER BY generation_id) FROM operations_backup_event WHERE event = 'PURGED' AND store = 'oci' AND code IN ('AGE', 'COUNT', 'BYTES') AND generation_id IN ('${day1}', '${day2}')")" \
  "${day1}:COUNT,${day2}:BYTES"
write_config 3 "$((size / 2))"
run_backup "8. 용량 상한 반 개분(${day5}) — 마지막 성공은 남긴다" "$(at_day 5)"
assert_equals '용량 반 개 · 종료 0' "${run_status}" '0'
assert_layout '용량 반 개' "$(objects_of "${day5}")"
show_log_on_failure "${failed_before}"
write_config 30 "${quota}"

# ---------------------------------------------------------------- 9. 29일 경계(DB 정지 중의 파기)

day5_start=$((base_epoch + 5 * 86400))
day34="$(gen_of_day 34)"
failed_before="${tests_failed}"
run_backup "9. 생성 후 정확히 29일(새 성공 없음)" "$((day5_start + 29 * 86400))" dump
assert_equals '29일 · 종료 1(dump 실패)' "${run_status}" '1'
assert_layout '29일 · 남는다' "$(objects_of "${day5}")"
compose stop postgres >>"${outputs}/stack.log" 2>&1
run_backup "9. 생성 후 29일 + 1초, PostgreSQL 정지" "$((day5_start + 29 * 86400 + 1))"
assert_equals '29일+1초·DB 정지 · 종료 1' "${run_status}" '1'
assert_layout '29일+1초·DB 정지 · 파기는 DB 없이도 돈다(마지막 성공도 지운다)' ''
if [ "$(pending_count)" -gt 0 ]; then
  record_pass "29일+1초·DB 정지 · 행은 대기열에($(pending_count)묶음)"
else
  record_fail '29일+1초·DB 정지 · 행은 대기열에' '대기열이 비었다.'
fi
wait_postgres || fail_setup 'PostgreSQL이 다시 뜨지 않았다.'
restart_worker || fail_setup 'Worker가 다시 뜨지 않았다.' "$(compose logs --no-color worker 2>&1 | tail -20)"
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 10. 30일 기한 초과

day40="$(gen_of_day 40)"
failed_before="${tests_failed}"
run_backup "10. 기한 초과 준비(${day40}) — DB 정지 중의 대기열을 먼저 기록한다" "$(at_day 40)"
assert_equals '기한 초과 준비 · 종료 0' "${run_status}" '0'
assert_equals 'DB 정지 뒤 · 대기열이 비었다' "$(pending_count)" '0'
assert_equals 'DB 정지 뒤 · AGE 파기(두 저장소)가 기록됐다' \
  "$(psql_value "SELECT string_agg(store || ':' || code, ',' ORDER BY store) FROM operations_backup_event WHERE event = 'PURGED' AND generation_id = '${day5}'")" \
  'oci:AGE,offsite:AGE'
assert_equals 'DB 정지 뒤 · FAILED DB_UNAVAILABLE과 상한 건너뜀(LIST_FAILED)' \
  "$(psql_value "SELECT string_agg(event || ':' || code, ',' ORDER BY sequence) FROM operations_backup_event WHERE generation_id = '${day34}' AND event IN ('FAILED', 'PURGE_FAILED') AND store IS NULL AND code <> 'DUMP'")" \
  'FAILED:DB_UNAVAILABLE,PURGE_FAILED:LIST_FAILED'
run_backup "10. 30일 뒤 OCI 밖 저장소의 삭제 실패" "$(at_day 70)" dump,purge-offsite
assert_equals '기한 초과 · 종료 1' "${run_status}" '1'
assert_equals '기한 초과 · oci는 지웠다' "$(listing oci generations)" ''
assert_equals '기한 초과 · offsite에 남았다' "$(listing offsite generations)" "$(objects_of "${day40}")"
assert_equals '기한 초과 · 기록(파기·파기 실패·기한 초과)' \
  "$(events_of "${day40}" | tr ',' '\n' | grep -E '^PURGE' | tr '\n' ',' | sed 's/,$//')" \
  'PURGED:oci:AGE,PURGE_FAILED:offsite:DELETE_FAILED,PURGE_OVERDUE:offsite:GENERATIONS'
assert_equals '기한 초과 · 감사 SYSTEM 행 둘' \
  "$(psql_value "SELECT string_agg(reason || ':' || outcome || ':' || (details->>'store'), ',' ORDER BY sequence) FROM operations_audit_event WHERE command = 'backup' AND scope = 'BACKUP' AND actor_kind = 'SYSTEM' AND actor_user_id IS NULL AND target = '${day40}'")" \
  'PURGE_FAILED:FAILED:offsite,PURGE_OVERDUE:FAILED:offsite'
assert_equals '기한 초과 · 측정이 기한 초과를 적었다' \
  "$(psql_value "SELECT code FROM operations_backup_event WHERE event = 'MEASURED' AND store = 'offsite' ORDER BY sequence DESC LIMIT 1")" \
  'PURGE_OVERDUE'
gauge="$(wait_series 'bokrilog_backup_purge_overdue{env="ci",role="worker",store="offsite"}' 'v == 1' 120)"
assert_equals '기한 초과 · offsite 기한 초과 gauge 1' "$(awk -v v="${gauge:-0}" 'BEGIN { printf "%d", v }')" '1'
measured_gauge="$(series_value 'bokrilog_backup_store_measured_timestamp_seconds{env="ci",role="worker",store="offsite"}')"
measured_epoch="$(psql_value "SELECT floor(extract(epoch FROM max(recorded_at))) FROM operations_backup_event WHERE event = 'MEASURED' AND store = 'offsite'")"
assert_equals '기한 초과 · offsite 측정 시각 gauge = 최신 측정 기록' \
  "$(awk -v v="${measured_gauge:-0}" 'BEGIN { printf "%d", int(v) }')" "${measured_epoch}"
printf '\n10. 파기만 다시(backup.sh purge)\n'
run_count=$((run_count + 1))
run_log="${outputs}/run-${run_count}.log"
BOKRILOG_BACKUP_NOW="$(at_day 70 60)" bash "${script_dir}/backup.sh" purge --rehearsal >"${run_log}" 2>&1
run_status=$?
assert_equals '파기만 · 종료 0' "${run_status}" '0'
assert_equals '기한 초과 해소 · offsite도 비었다' "$(listing offsite generations)" ''
assert_equals '기한 초과 해소 · 측정의 표지가 없다' \
  "$(psql_value "SELECT COALESCE(code, 'NONE') FROM operations_backup_event WHERE event = 'MEASURED' AND store = 'offsite' ORDER BY sequence DESC LIMIT 1")" \
  'NONE'
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 11. 키 회전·key 확인

day80="$(gen_of_day 80)"
day81="$(gen_of_day 81)"
failed_before="${tests_failed}"
run_backup "11. 회전 전 key k1(${day80})" "$(at_day 80)"
assert_equals '키 회전 · k1 generation · 종료 0' "${run_status}" '0'
new_key k2
run_backup "11. 새 active key k2(${day81})" "$(at_day 81)"
assert_equals '키 회전 · k2 generation · 종료 0' "${run_status}" '0'
assert_equals '키 회전 · object 이름에 key ID' "$(listing oci generations)" \
  "$(objects_of "${day80}") $(OBJECT_KEY=k2 objects_of "${day81}")"
assert_equals '키 회전 · 성공 기록의 key ID' \
  "$(psql_value "SELECT key_id FROM operations_backup_event WHERE event = 'SUCCEEDED' AND generation_id = '${day81}'")" 'k2'
run_keys
assert_equals '키 확인 · 두 key가 맞으면 0' "${run_status}" '0'
mv "${keys_dir}/identities/k1.key" "${work}/k1.key.aside"
run_keys
assert_equals '키 확인 · 과거 identity를 지우면 1' "${run_status}" '1'
if grep -E '없음  k1' "${run_log}" >/dev/null; then
  record_pass '키 확인 · 필요한 key ID(k1)를 보고한다'
else
  record_fail '키 확인 · 필요한 key ID(k1)를 보고한다' "$(tail -5 "${run_log}")"
fi
# 짝이 아닌 identity(다른 key를 같은 이름으로 두었다).
"${age_keygen}" -o "${keys_dir}/identities/k1.key" >/dev/null 2>>"${outputs}/keys.log"
chmod 600 "${keys_dir}/identities/k1.key"
run_keys
assert_equals '키 확인 · recipient와 짝이 아닌 identity면 1' "${run_status}" '1'
if grep -F '짝이 아니다' "${run_log}" >/dev/null; then
  record_pass '키 확인 · 짝이 아니라고 보고한다'
else
  record_fail '키 확인 · 짝이 아니라고 보고한다' "$(tail -5 "${run_log}")"
fi
mv -f "${work}/k1.key.aside" "${keys_dir}/identities/k1.key"
compose stop minio-offsite >>"${outputs}/stack.log" 2>&1
run_keys
assert_equals '키 확인 · 저장소 하나를 읽지 못하면 1' "${run_status}" '1'
if grep -F '저장소를 읽지 못해' "${run_log}" >/dev/null; then
  record_pass '키 확인 · 읽지 못한 저장소를 보고한다'
else
  record_fail '키 확인 · 읽지 못한 저장소를 보고한다' "$(tail -5 "${run_log}")"
fi
compose start minio-offsite >>"${outputs}/stack.log" 2>&1
wait_store "${offsite_bind}" || fail_setup 'OCI 밖 저장소가 다시 뜨지 않았다.'
run_keys
assert_equals '키 확인 · 되돌리면 0' "${run_status}" '0'
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 12. 큰 dump(파이프 버퍼보다 훨씬 크다)

day82="$(gen_of_day 82)"
failed_before="${tests_failed}"
# 리허설만 만드는 scratch 표다(제품 표의 의미를 건드리지 않는다). 압축해도 수 MB가 남게 무작위 값을 채우고 끝나면 지운다.
compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password --quiet --set=ON_ERROR_STOP=1 \
  --command="CREATE TABLE rehearsal_scratch (id integer PRIMARY KEY, payload text NOT NULL); INSERT INTO rehearsal_scratch SELECT n, md5(random()::text) || md5(random()::text) FROM generate_series(1, 150000) AS n" \
  >>"${outputs}/psql.log" 2>&1 || fail_setup 'scratch 표를 만들지 못했다.' "$(tail -5 "${outputs}/psql.log")"
run_backup "12. 큰 dump(${day82})" "$(at_day 82)"
compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password --quiet \
  --command='DROP TABLE rehearsal_scratch' >>"${outputs}/psql.log" 2>&1
assert_equals '큰 dump · 종료 0(복호화 stream을 끝까지 읽고 인증)' "${run_status}" '0'
big_bytes="$(psql_value "SELECT bytes FROM operations_backup_event WHERE event = 'SUCCEEDED' AND generation_id = '${day82}'")"
if matches "${big_bytes:-0}" '[0-9]+' && [ "${big_bytes:-0}" -gt 2000000 ]; then
  record_pass "큰 dump · 암호문 ${big_bytes} bytes(파이프 버퍼 64KB의 수십 배)"
else
  record_fail '큰 dump · 암호문이 수 MB다' "실제 '${big_bytes}'"
fi
if grep -F '복호화 stream 끝까지 인증' "${run_log}" >/dev/null; then
  record_pass '큰 dump · age·pg_restore 두 종료 코드를 따로 보았다'
else
  record_fail '큰 dump · age·pg_restore 두 종료 코드를 따로 보았다' "$(tail -5 "${run_log}")"
fi
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 13. 누출

printf '\n13. 누출 검사\n'
names="$(listing oci generations) $(listing offsite generations) $(listing oci incoming) $(listing offsite incoming)"
bad_names=''
for name in ${names}; do
  matches "${name}" 'gen_[0-9]{8}\.[a-z0-9][a-z0-9_-]*\.dump\.age(\.sha256)?' || bad_names="${bad_names} ${name}"
done
assert_equals '누출 · object 이름은 generation·key ID뿐이다' "${bad_names}" ''
metrics="$(scrape)"
# 기록 역할(`run --rm`)의 컨테이너 출력은 backup.sh가 도구 기록(`logs/`)에 남기고, Worker의 컨테이너 로그는 여기서 받는다.
compose logs --no-color worker >"${outputs}/worker-container.log" 2>&1
secret_values="${marker_user} ${marker_email} ${s3_secret} AGE-SECRET-KEY-"
for recipient in $(cat "${keys_dir}"/recipients/*.pub); do
  secret_values="${secret_values} ${recipient}"
done
leaks=''
for value in ${secret_values}; do
  for file in "${outputs}"/run-*.log "${outputs}"/keys-*.log "${outputs}/worker-container.log" "${backup_dir}"/logs/*.log; do
    [ -f "${file}" ] || continue
    grep -F -- "${value}" "${file}" >/dev/null && leaks="${leaks} ${file##*/}"
  done
  printf '%s\n' "${metrics}" | grep -F -- "${value}" >/dev/null && leaks="${leaks} metrics"
  printf '%s\n' "${names}" | grep -F -- "${value}" >/dev/null && leaks="${leaks} names"
done
assert_equals '누출 · 실행 출력·도구 기록·Worker 컨테이너 로그·메트릭·object 이름에 사용자 ID·이메일·key·저장소 비밀이 없다' \
  "${leaks}" ''
backup_series="$(printf '%s\n' "${metrics}" | grep -E '^bokrilog_backup_' | sed 's/[[:space:]].*$//' | sort -u | tr '\n' ' ')"
unexpected=''
for series in ${backup_series}; do
  matches "${series}" 'bokrilog_backup_(last_success_timestamp_seconds\{env="ci",role="worker"\}|(store_usage_ratio|purge_overdue|store_measured_timestamp_seconds)\{env="ci",role="worker",store="(oci|offsite)"\})' \
    || unexpected="${unexpected} ${series}"
done
assert_equals '누출 · 백업 메트릭 label은 env·role·store뿐이다' "${unexpected}" ''
assert_equals '누출 · 평문 dump가 작업 디렉터리 어디에도 없다' \
  "$(find "${backup_dir}" -type f -name '*.dump' 2>/dev/null | wc -l | tr -d ' ')" '0'

# ---------------------------------------------------------------- 판정

printf '\n%s개 중 %s개 통과, %s개 실패\n' "${tests_run}" "$((tests_run - tests_failed))" "${tests_failed}"
printf '이 결과는 CI 리허설이다(MinIO 두 개, 가상 시계). 실제 OCI·OCI 밖 저장소에서 파기를 확인하기 전까지 백업은 실저장소 미검증이다.\n'
if [ "${tests_failed}" -ne 0 ]; then
  printf '::error::백업 리허설이 실패했다.\n'
  exit 1
fi
exit 0
