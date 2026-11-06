#!/usr/bin/env bash
# 격리 복원과 삭제 계정 재활성화 차단의 CI 리허설(Story 3.10).
#
# 왜 있는가: `restore.sh`의 순서(checksum → 복호화 → 빈 DB 복원 → migration → 외부 tombstone → 적용·검증 → 서비스)와 tombstone 내보내기는
# 문서가 아니라 실행으로 고정해야 한다. 이 스크립트가 운영 compose에 리허설 override(`compose.rehearsal.yaml`)와 S3 호환 저장소 둘
# (`compose.rehearsal-backup.yaml`)을 겹친 "운영 대역" 프로젝트를 세우고, 실제 `backup.sh`로 generation을 만든 뒤 표본 계정을 지우고
# tombstone을 내보내고, 그 **삭제 이전** generation을 빈 격리 프로젝트에 복원한다. 시나리오는 SPEC I/O 표다.
#
# **이것은 실제 복원의 증거가 아니다** — 같은 호스트의 MinIO·가상 시계·리허설 key다. 실제 별도 환경 복원 전까지 복원은 실제 복원
# 미검증이다.
#
# 실행: deploy/production/rehearse-restore.sh
#
# 전제: `backend/build/libs/bokrilog-backend.jar`가 있다(빌드하지 않는다). docker·compose v2·curl·unzip·node(표본 자격정보를 앱과 같은
# AES-256-GCM 형식으로 봉한다). age·rclone은 `install-backup-tools.sh`로 받는다. 배포·백업 리허설과 프로젝트 이름·포트가 달라 겹치지 않는다.
#
# 시나리오
#    1. 백업 두 번      gen0(key k1) → key 회전 → gen1(key k2), checksum 둘째 줄의 dump 시작 시각, `latest`는 gen1/k2
#    2. tombstone 없음  외부 `tombstones/latest` 없이 gen0 복원 → TOMBSTONES_UNAVAILABLE, 종료 1, 아무것도 띄우지 않음
#    3. 삭제 표본       운영 대역에서 표본 계정 삭제(tombstone outbox 행 한 트랜잭션), 운영 환경·리허설 표지 없음이면 사유와 함께 거부
#    3b. 삭제 실행      (Story 5.3) 백업 전에 요청된 삭제를 운영 대역의 Worker가 집는다 — 첫 claim의 tombstone은 요청 시각(V30), 운영
#                       완료가 집합을 닫고 보존을 늘린다, 감사 행은 수뿐
#    4. 내보내기        기한 지난 미완료 outbox 행도 남고 실린다, 24시간 지난 사본 object는 지워지고 그 안의 것은 남는다, 머리의
#                       키 ID·시각, latest 갱신 / (Story 5.3) 미완료로 실린 행이 백업 파기 완료가 되면 다음 성공한 내보내기에 DESTROYED로
#                       한 번 실리고(줄어듦 없음) 그다음 내보내기가 지우고 뺀다 / 줄어든 내보내기(살아 있는 행·기한 지난 미완료 행이
#                       빠졌다)는 TOMBSTONE_SHRANK·latest 불변 / 배포 중 내보내기는 건너뜀(0), 백업 중 복원은 BACKUP_IN_PROGRESS(1) —
#                       둘 다 역할을 띄우지 않는다
#    5. 정상 복원       삭제 **이전** gen0(과거 key k1)을 빈 격리 프로젝트에 → 표본 DELETED·잔여 0·세션 0, 실행 전(삭제 진행 중·요청
#                       행)이던 요청 계정도 첫 claim의 tombstone으로 DELETED(Story 5.3), 유지 계정·자격정보 그대로,
#                       키 ID 일치, 자격정보 표본 1/1, 불변식 통과, API·Worker 기동(OIDC·Bybit·공개 URL 값 없음, env restore),
#                       nginx·공개 포트 없음, RPO 통과
#    6. RPO 경계        dump 시작 + 24h → 통과(0), + 24h 1초 → RPO_EXCEEDED(1) / dump 시작이 없는 옛 generation은 날짜 00:00 UTC 기준
#                       / 30일을 넘긴 generation은 GENERATION_TOO_OLD
#    7. 키              과거 identity가 없으면 KEY_UNAVAILABLE(1), 아무것도 띄우지 않는다
#    8. 변조            checksum 불일치 → CHECKSUM_MISMATCH(1), 아무것도 띄우지 않는다
#    9. 적용 실패       다른 자격정보 key ring → APPLY_FAILED(CREDENTIALS_UNAVAILABLE), --keep-on-failure면 PostgreSQL만 남는다 /
#                       다른 tombstone digest 키 → APPLY_FAILED(TOMBSTONE_KEY_ID_MISMATCH), 격리 프로젝트는 volume까지 지워진다
#   10. latest 이상     풀 수 없는·머리 없는 사본 → TOMBSTONES_UNAVAILABLE(지워짐) / 장애 시각보다 2시간 앞선 사본 → TOMBSTONES_STALE
#   11. 운영 이름       `--project bokrilog-production` → 2, 운영 대역 이름 → 2, 비어 있지 않은 프로젝트 → 2
#   12. 누출            실행 출력·도구 기록·복원된 컨테이너 로그에 사용자 ID·이메일·tombstone digest·key·자격정보·저장소 비밀이 없고
#                       평문 임시본 0
#
# 종료 코드
#   0 -- 모든 단정 통과
#   1 -- 단정 실패(`  실패` 줄)
#   2 -- 준비 실패(산출물·도구·docker 없음, 이미지 빌드·저장소 기동 실패)
#
# `BOKRILOG_REHEARSAL_DIAGNOSTICS`(디렉터리)를 주면 실패했을 때 정리 전에 실행 출력·도구 기록·컨테이너 로그를 그곳에 옮겨 둔다. key·
# 저장소 설정·비밀 파일·평문 tombstone은 옮기지 않는다.

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd "${script_dir}/../.." && pwd -P)"
# shellcheck source=lib.sh
. "${script_dir}/lib.sh"
# shellcheck source=backup-lib.sh
. "${script_dir}/backup-lib.sh"

backend_jar="${repo_root}/backend/build/libs/bokrilog-backend.jar"
# 운영 대역 프로젝트. 복원 대상 프로젝트는 아래 restore_projects다.
project='bokrilog-restore-src'
restore_projects='bokrilog-restore-r1 bokrilog-restore-r2 bokrilog-restore-r3 bokrilog-restore-r4 bokrilog-restore-r5
bokrilog-restore-r6 bokrilog-restore-r7 bokrilog-restore-r8 bokrilog-restore-r9'
oci_bind='127.0.0.1:19110'
offsite_bind='127.0.0.1:19111'

# 가상 시계의 기준: 2026-01-01 00:00 UTC.
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
  printf '\n::error::복원 리허설 준비 실패: %s\n' "$1" >&2
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

# assert_contains <라벨> <파일> <문구>
assert_contains() {
  if grep -F -- "$3" "$2" >/dev/null 2>&1; then
    record_pass "$1"
  else
    record_fail "$1" "'$3'가 없다: $(tail -5 "$2" 2>/dev/null | tr '\n' ' ')"
  fi
}

[ -f "${backend_jar}" ] || fail_setup "백엔드 jar가 없다: ${backend_jar}" '먼저 `backend/gradlew --project-dir backend build`를 실행한다.'
command -v docker >/dev/null 2>&1 || fail_setup 'docker가 없다.'
docker compose version >/dev/null 2>&1 || fail_setup 'docker compose v2를 쓸 수 없다.'
command -v curl >/dev/null 2>&1 || fail_setup 'curl이 없다.'
command -v node >/dev/null 2>&1 || fail_setup 'node가 없다(표본 자격정보를 봉한다).'
have_sha256_tool || fail_setup 'sha256sum 또는 shasum이 없다.'

work="$(mktemp -d)" || fail_setup '임시 디렉터리를 만들지 못했다.'
[ -d "${work}" ] || fail_setup "mktemp -d가 쓸 수 없는 경로를 냈다: '${work}'"
state_dir="${work}/state"
keys_dir="${work}/keys"
tombstone_keys_dir="${work}/tombstone-keys"
backup_dir="${work}/backup"
restore_dir="${work}/restore"
tombstone_dir="${work}/tombstone"
tools_dir="${work}/tools"
outputs="${work}/outputs"
mkdir -p "${state_dir}" "${keys_dir}/identities" "${keys_dir}/recipients" "${tombstone_keys_dir}" "${backup_dir}" "${outputs}"
chmod 700 "${keys_dir}" "${keys_dir}/identities" "${keys_dir}/recipients" "${tombstone_keys_dir}"

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

# restored <프로젝트> <compose 인자…>. 복원된 격리 프로젝트(저장소 없음).
restored() {
  local name="$1"
  shift
  BOKRILOG_STATE_DIR="${state_dir}" BOKRILOG_ENV=ci \
    BOKRILOG_IMAGE="${image:-none}" BOKRILOG_AUDIT_IMAGE="${image:-none}" BOKRILOG_BLUE_IMAGE="${image:-none}" \
    BOKRILOG_GREEN_IMAGE="${image:-none}" BOKRILOG_WORKER_IMAGE="${image:-none}" \
    docker compose --project-name "${name}" --env-file "${secrets_file}" --file "${script_dir}/compose.yaml" \
    --file "${script_dir}/compose.rehearsal.yaml" "$@" </dev/null
}

keep_diagnostics() {
  local target="${BOKRILOG_REHEARSAL_DIAGNOSTICS:-}" name
  [ -n "${target}" ] || return 0
  mkdir -p "${target}/tool-logs" || return 0
  cp "${outputs}"/*.log "${target}/" 2>/dev/null
  cp "${restore_dir}"/logs/*.log "${tombstone_dir}"/logs/*.log "${backup_dir}"/logs/*.log "${target}/tool-logs/" 2>/dev/null
  if [ -f "${secrets_file}" ]; then
    compose logs --no-color --timestamps >"${target}/compose-source.log" 2>&1
    for name in ${restore_projects}; do
      restored "${name}" logs --no-color --timestamps >"${target}/compose-${name}.log" 2>&1
    done
  fi
  printf '  진단 기록: %s\n' "${target}"
}

cleanup() {
  local exit_code="${1:-0}" name
  [ "${cleaned}" = true ] && return
  cleaned=true
  printf '\n리허설 정리\n'
  if [ "${exit_code}" -ne 0 ] || [ "${tests_failed}" -ne 0 ]; then
    keep_diagnostics
  fi
  if [ -f "${secrets_file}" ]; then
    for name in ${restore_projects}; do
      restored "${name}" down --remove-orphans --volumes >/dev/null 2>&1 || true
    done
    compose down --remove-orphans --volumes >/dev/null 2>&1 || true
  fi
  [ -n "${image}" ] && docker image rm --force "${image}" >/dev/null 2>&1
  rm -rf "${work}"
}
trap 'cleanup $?' EXIT
trap 'cleanup 130; exit 130' INT
trap 'cleanup 143; exit 143' TERM

printf '복원 리허설\n\n입력 준비\n'

# ---------------------------------------------------------------- 도구·이미지

bash "${script_dir}/install-backup-tools.sh" --prefix "${tools_dir}" >"${outputs}/install-tools.log" 2>&1 \
  || fail_setup 'age·rclone을 받지 못했다.' "$(tail -5 "${outputs}/install-tools.log")"
age_bin="$(tool age)"
age_keygen="$(tool age-keygen)"
rclone_bin="$(tool rclone)"

image="$(docker build --quiet --label org.bokrilog.rehearsal=restore --file "${repo_root}/backend/Dockerfile" \
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
  printf 'BOKRILOG_PUBLIC_BASE_URL=http://127.0.0.1:18098\n'
  printf 'BOKRILOG_AUDIT_DIGEST_KEY=%s\n' "$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
  printf 'BOKRILOG_TOMBSTONE_DIGEST_KEY=%s\n' "$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
  printf 'BOKRILOG_SOURCE_CREDENTIAL_KEYS=rehearsal:%s\n' "$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
  printf 'BOKRILOG_SOURCE_CREDENTIAL_ACTIVE_KEY_VERSION=rehearsal\n'
  printf 'BOKRILOG_BYBIT_BASE_URL=https://bybit.rehearsal.test\n'
  printf 'BOKRILOG_BYBIT_RESERVED_IP=203.0.113.10\n'
  printf 'BOKRILOG_SUPPORT_CONTACT=support@bokrilog.test\n'
} >"${secrets_file}"
chmod 600 "${secrets_file}"

# new_key <key ID>. backup identity·recipient를 만들고 active.pub을 그 key로 바꾼다(DEVELOPMENT.md `### 키`).
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

# tombstone 전용 age key(backup key와 다르다).
"${age_keygen}" -o "${tombstone_keys_dir}/identity.key" >/dev/null 2>>"${outputs}/keys.log" \
  || fail_setup 'tombstone key를 만들지 못했다.'
chmod 600 "${tombstone_keys_dir}/identity.key"
"${age_keygen}" -y "${tombstone_keys_dir}/identity.key" >"${tombstone_keys_dir}/recipient.pub" 2>>"${outputs}/keys.log" \
  || fail_setup 'tombstone recipient를 쓰지 못했다.'
chmod 644 "${tombstone_keys_dir}/recipient.pub"

oci_bucket='bokrilog-oci-restore-rehearsal'
offsite_bucket='bokrilog-offsite-restore-rehearsal'
{
  for pair in "oci:${oci_bind}" "offsite:${offsite_bind}"; do
    printf '[%s]\ntype = s3\nprovider = Minio\nendpoint = http://%s\nregion = us-east-1\nforce_path_style = true\n' \
      "${pair%%:*}" "${pair#*:}"
    printf 'access_key_id = %s\nsecret_access_key = %s\n\n' "${s3_user}" "${s3_secret}"
  done
} >"${keys_dir}/rclone.conf"
chmod 600 "${keys_dir}/rclone.conf"
quota=1073741824
{
  printf 'oci_remote=oci:%s\noffsite_remote=offsite:%s\nmax_generations=30\n' "${oci_bucket}" "${offsite_bucket}"
  printf 'max_bytes_oci=%s\nmax_bytes_offsite=%s\nquota_bytes_oci=%s\nquota_bytes_offsite=%s\n' \
    "${quota}" "${quota}" "${quota}" "${quota}"
} >"${keys_dir}/backup.env"

store() {
  "${rclone_bin}" --config "${keys_dir}/rclone.conf" --retries 1 --low-level-retries 2 --contimeout 5s --log-level ERROR "$@"
}

# ---------------------------------------------------------------- 운영 대역 스택

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
store mkdir "oci:${oci_bucket}" >>"${outputs}/stack.log" 2>&1 \
  && store mkdir "offsite:${offsite_bucket}" >>"${outputs}/stack.log" 2>&1 \
  || fail_setup '저장소 bucket을 만들지 못했다.' "$(tail -5 "${outputs}/stack.log")"
compose --profile migrate run --rm --no-deps -T migrate >>"${outputs}/stack.log" 2>&1 \
  || fail_setup 'migration이 실패했다.' "$(tail -20 "${outputs}/stack.log")"

# psql_in <프로젝트 함수> <SQL>. 값 하나.
psql_src() {
  compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password --tuples-only --no-align \
    --command="$1" 2>>"${outputs}/psql.log" | tr -d '[:space:]'
}
psql_restored() {
  restored "$1" exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password --tuples-only --no-align \
    --command="$2" 2>>"${outputs}/psql.log" | tr -d '[:space:]'
}

uuid_v4() {
  od -An -N 16 -tx1 /dev/urandom | tr -d ' \n' \
    | awk '{ print substr($0,1,8) "-" substr($0,9,4) "-4" substr($0,14,3) "-a" substr($0,18,3) "-" substr($0,21,12) }'
}

# 유지 계정과 표본 계정. 각자 인증 매핑·세션 하나. 사용자 ID·이메일 모양 식별자는 누출 검사의 표지다(실행마다 만든다).
kept_user="$(uuid_v4)"
sample_user="$(uuid_v4)"
kept_email="rehearsal.kept.$(random_hex 5)@bokrilog.test"
sample_email="rehearsal.sample.$(random_hex 5)@bokrilog.test"
seed_sql=''
for pair in "${kept_user}:${kept_email}" "${sample_user}:${sample_email}"; do
  user="${pair%%:*}"
  email="${pair#*:}"
  seed_sql="${seed_sql} INSERT INTO identity_account_lifecycle (user_id, state) VALUES ('${user}', 'ACTIVE');"
  seed_sql="${seed_sql} INSERT INTO identity_account (user_id) VALUES ('${user}');"
  seed_sql="${seed_sql} INSERT INTO identity_social_link (link_id, user_id, provider, issuer, subject) VALUES (gen_random_uuid(), '${user}', 'google', 'https://issuer.rehearsal.test', '${email}');"
  seed_sql="${seed_sql} INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time, principal_name) VALUES ('$(uuid_v4)', '$(uuid_v4)', 0, 0, 1800, 4102444800000, '${user}');"
done
# 삭제를 요청했지만 아직 실행되지 않은 계정(Story 5.3 "실행 전 복원"). 백업 gen0·gen1은 이 상태(삭제 진행 중·요청 행)를 담는다. 뒤에서
# 운영 대역의 Worker가 이 요청을 집어 첫 단계에서 tombstone을 남기고 끝까지 지운다.
pending_user="$(uuid_v4)"
pending_email="rehearsal.pending.$(random_hex 5)@bokrilog.test"
seed_sql="${seed_sql} INSERT INTO identity_account_lifecycle (user_id, state) VALUES ('${pending_user}', 'DELETION_IN_PROGRESS');"
seed_sql="${seed_sql} INSERT INTO identity_account (user_id) VALUES ('${pending_user}');"
seed_sql="${seed_sql} INSERT INTO identity_social_link (link_id, user_id, provider, issuer, subject) VALUES (gen_random_uuid(), '${pending_user}', 'google', 'https://issuer.rehearsal.test', '${pending_email}');"
seed_sql="${seed_sql} INSERT INTO identity_deletion_request (request_id, user_id, policy_version, notice_hash, idempotency_key_digest) VALUES ('rehearsal-pending-1', '${pending_user}', 'ACCOUNT-DELETION-1.0', '$(random_hex 32)', '$(random_hex 32)');"
compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password --quiet --set=ON_ERROR_STOP=1 \
  --command="${seed_sql}" >>"${outputs}/psql.log" 2>&1 || fail_setup '표지 데이터를 넣지 못했다.' "$(tail -5 "${outputs}/psql.log")"

# 유지 계정의 실제 자격정보 행 하나 — 앱의 `CredentialCipher`와 같은 형식(AES-256-GCM, 96비트 nonce, 길이 접두사 AAD·평문)으로 리허설
# key ring의 key로 봉한다. 복원의 자격정보 표본 복호가 0/0이 아니라 1/1이어야 한다(다른 key ring이면 0/1). 평문 값은 실행마다 만든다.
api_key="rehearsalkey$(random_hex 8)"
api_secret="rehearsalsecret$(random_hex 12)"
connection_id="$(uuid_v4)"
credential_id="$(uuid_v4)"
provider_account_id="$(uuid_v4)"
cat >"${work}/seal.js" <<'SEAL'
const crypto = require('crypto');
const [key, user, connection, credential, version, apiKey, apiSecret] = process.argv.slice(2);
const framed = (parts) => Buffer.concat(parts.map((part) => {
  const bytes = Buffer.from(part, 'utf8');
  const length = Buffer.alloc(4);
  length.writeInt32BE(bytes.length);
  return Buffer.concat([length, bytes]);
}));
const nonce = crypto.randomBytes(12);
const cipher = crypto.createCipheriv('aes-256-gcm', Buffer.from(key, 'base64'), nonce);
cipher.setAAD(framed([user, connection, credential, version, 'AES256GCM-1']));
const sealed = Buffer.concat([cipher.update(framed([apiKey, apiSecret])), cipher.final(), cipher.getAuthTag()]);
process.stdout.write(nonce.toString('hex') + ' ' + sealed.toString('hex'));
SEAL
ring_key="$(kv_get "${secrets_file}" BOKRILOG_SOURCE_CREDENTIAL_KEYS)"
sealed="$(node "${work}/seal.js" "${ring_key#*:}" "${kept_user}" "${connection_id}" "${credential_id}" rehearsal \
  "${api_key}" "${api_secret}")" || fail_setup '표본 자격정보를 봉하지 못했다.'
# 삭제 표본(Story 5.4)도 같은 형식의 자격정보 하나를 갖는다 — 자격정보 잔여 0을 단정하려면 지울 행이 있어야 한다. 같은 key ring으로 봉해
# 복원의 자격정보 표본 복호가 그 행을 만나도(다시 지우기 전) 실패하지 않는다.
sample_connection_id="$(uuid_v4)"
sample_credential_id="$(uuid_v4)"
sample_provider_account_id="$(uuid_v4)"
sample_sealed="$(node "${work}/seal.js" "${ring_key#*:}" "${sample_user}" "${sample_connection_id}" "${sample_credential_id}" \
  rehearsal "rehearsalkey$(random_hex 8)" "rehearsalsecret$(random_hex 12)")" || fail_setup '삭제 표본 자격정보를 봉하지 못했다.'
rm -f "${work}/seal.js"
compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password --quiet --set=ON_ERROR_STOP=1 \
  --command="INSERT INTO source_provider_account (provider_account_id, user_id, provider, provider_account_ref) VALUES ('${provider_account_id}', '${kept_user}', 'BYBIT', 'rehearsal-$(random_hex 4)'); INSERT INTO source_connection (connection_id, user_id, provider_account_id, provider, status, verified_at, policy_version) VALUES ('${connection_id}', '${kept_user}', '${provider_account_id}', 'BYBIT', 'ACTIVE', now(), 'bybit-readonly-uta-1.0'); INSERT INTO source_credential (credential_id, connection_id, key_version, algorithm_version, nonce, ciphertext) VALUES ('${credential_id}', '${connection_id}', 'rehearsal', 'AES256GCM-1', decode('${sealed%% *}', 'hex'), decode('${sealed#* }', 'hex'))" \
  >>"${outputs}/psql.log" 2>&1 || fail_setup '표본 자격정보 행을 넣지 못했다.' "$(tail -5 "${outputs}/psql.log")"

# 삭제 표본의 모듈 데이터(Story 5.4) — 복원 보고의 모듈별 잔여(로그인 매핑·세션·자격정보·source·portfolio·sync·plan·operations(문의)·engagement(동의))를
# 각각 0으로 단정하려면 그 모듈마다 지울 행이 있어야 한다. 백업 gen0은 이 행들을 담고, 복원이 tombstone으로 다시 지운다. 금융 값은 리허설
# 표지일 뿐이다(값이 무엇이든 지워진다).
sample_portfolio_id="$(uuid_v4)"
sample_membership_id="$(uuid_v4)"
sample_job_id="$(uuid_v4)"
sample_source_snapshot_id="$(uuid_v4)"
sample_portfolio_snapshot_id="$(uuid_v4)"
sample_seed="INSERT INTO source_provider_account (provider_account_id, user_id, provider, provider_account_ref) VALUES ('${sample_provider_account_id}', '${sample_user}', 'BYBIT', 'rehearsal-sample-$(random_hex 4)');"
sample_seed="${sample_seed} INSERT INTO source_connection (connection_id, user_id, provider_account_id, provider, status, verified_at, policy_version) VALUES ('${sample_connection_id}', '${sample_user}', '${sample_provider_account_id}', 'BYBIT', 'ACTIVE', now(), 'bybit-readonly-uta-1.0');"
sample_seed="${sample_seed} INSERT INTO source_credential (credential_id, connection_id, key_version, algorithm_version, nonce, ciphertext) VALUES ('${sample_credential_id}', '${sample_connection_id}', 'rehearsal', 'AES256GCM-1', decode('${sample_sealed%% *}', 'hex'), decode('${sample_sealed#* }', 'hex'));"
sample_seed="${sample_seed} INSERT INTO portfolio (portfolio_id, user_id, base_currency) VALUES ('${sample_portfolio_id}', '${sample_user}', 'USD');"
sample_seed="${sample_seed} INSERT INTO portfolio_source_membership (membership_id, portfolio_id, connection_id, provider_account_id, started_at) VALUES ('${sample_membership_id}', '${sample_portfolio_id}', '${sample_connection_id}', '${sample_provider_account_id}', now());"
sample_seed="${sample_seed} INSERT INTO sync_job (job_id, user_id, connection_id, provider_account_id, idempotency_key, status, finished_at) VALUES ('${sample_job_id}', '${sample_user}', '${sample_connection_id}', '${sample_provider_account_id}', 'rehearsal-sample-job', 'SUCCEEDED', now());"
sample_seed="${sample_seed} INSERT INTO sync_source_snapshot (source_snapshot_id, user_id, connection_id, provider_account_id, job_id, provider, equity_decimal, measured_at, received_at, input_status, policy_version) VALUES ('${sample_source_snapshot_id}', '${sample_user}', '${sample_connection_id}', '${sample_provider_account_id}', '${sample_job_id}', 'BYBIT', '1000', now(), now(), 'VERIFIED', 'bybit-readonly-uta-1.0');"
sample_seed="${sample_seed} INSERT INTO portfolio_snapshot (portfolio_snapshot_id, portfolio_id, user_id, amount_decimal, base_currency, measured_at, publication_sequence) VALUES ('${sample_portfolio_snapshot_id}', '${sample_portfolio_id}', '${sample_user}', '1000', 'USD', now(), 1);"
sample_seed="${sample_seed} INSERT INTO portfolio_snapshot_component (portfolio_snapshot_id, membership_id, source_snapshot_id, portfolio_id, user_id, connection_id, provider_account_id, amount_decimal, policy_version) VALUES ('${sample_portfolio_snapshot_id}', '${sample_membership_id}', '${sample_source_snapshot_id}', '${sample_portfolio_id}', '${sample_user}', '${sample_connection_id}', '${sample_provider_account_id}', '1000', 'bybit-readonly-uta-1.0');"
sample_seed="${sample_seed} INSERT INTO portfolio_current_snapshot (portfolio_id, portfolio_snapshot_id, publication_sequence) VALUES ('${sample_portfolio_id}', '${sample_portfolio_snapshot_id}', 1);"
sample_seed="${sample_seed} INSERT INTO plan_condition_confirmation (confirmation_hash, user_id, session_hash, expires_at, canonical_hash, portfolio_id, portfolio_snapshot_id, membership_id, connection_id, provider_account_id, source_snapshot_id, p0_decimal, t0, end_at, period, target_rate, source_policy_version, portfolio_policy_version, calendar_policy, calculation_policy) VALUES ('$(random_hex 32)', '${sample_user}', '$(random_hex 32)', now() + interval '5 minutes', '$(random_hex 32)', '${sample_portfolio_id}', '${sample_portfolio_snapshot_id}', '${sample_membership_id}', '${sample_connection_id}', '${sample_provider_account_id}', '${sample_source_snapshot_id}', '1000', now(), now() + interval '30 days', 'MONTHLY', '1', 'bybit-readonly-uta-1.0', 'rehearsal', 'rehearsal', 'rehearsal');"
sample_seed="${sample_seed} INSERT INTO support_inquiry (inquiry_id, user_id, reference, category, title, description, contact, idempotency_key_digest, request_digest, received_at, target_state, calendar_version, timezone, status) VALUES ('$(uuid_v4)', '${sample_user}', 'Q-SAMPLEAAAA', 'ACCOUNT', 'rehearsal inquiry', 'rehearsal inquiry body', 'IN_APP', '$(random_hex 32)', '$(random_hex 32)', now(), 'CALENDAR_PENDING', 'KR-HOLIDAY-2026.1', 'Asia/Seoul', 'RECEIVED');"
sample_seed="${sample_seed} INSERT INTO engagement_ad_consent_decision (user_id, policy_version, choice, idempotency_key_digest, request_digest) VALUES ('${sample_user}', 'AD-CONSENT-1.0', 'GRANTED', '$(random_hex 32)', '$(random_hex 32)');"
compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password --quiet --set=ON_ERROR_STOP=1 \
  --command="${sample_seed}" >>"${outputs}/psql.log" 2>&1 \
  || fail_setup '삭제 표본의 모듈 데이터를 넣지 못했다.' "$(tail -5 "${outputs}/psql.log")"
# 표본이 실제로 모듈마다 행을 가졌다(잔여 0 단정이 빈 표를 세어 조용히 통과하지 않게).
assert_equals '삭제 표본 seed · 로그인 매핑·세션·자격정보·source·portfolio·sync·plan·operations(문의)·engagement(동의) 행이 있다' \
  "$(psql_src "SELECT (SELECT count(*) FROM identity_social_link WHERE user_id = '${sample_user}') || ':' || (SELECT count(*) FROM spring_session WHERE principal_name = '${sample_user}') || ':' || (SELECT count(*) FROM source_credential WHERE connection_id = '${sample_connection_id}') || ':' || (SELECT count(*) FROM source_connection WHERE user_id = '${sample_user}') || ':' || (SELECT count(*) FROM portfolio_snapshot WHERE user_id = '${sample_user}') || ':' || (SELECT count(*) FROM sync_source_snapshot WHERE user_id = '${sample_user}') || ':' || (SELECT count(*) FROM plan_condition_confirmation WHERE user_id = '${sample_user}') || ':' || (SELECT count(*) FROM support_inquiry WHERE user_id = '${sample_user}') || ':' || (SELECT count(*) FROM engagement_ad_consent_decision WHERE user_id = '${sample_user}')")" \
  '1:1:1:1:1:1:1:1:1'

export BOKRILOG_BACKUP_KEYS_DIR="${keys_dir}"
export BOKRILOG_BACKUP_DIR="${backup_dir}"
export BOKRILOG_BACKUP_TOOLS="${tools_dir}"
export BOKRILOG_TOMBSTONE_KEYS_DIR="${tombstone_keys_dir}"
export BOKRILOG_TOMBSTONE_DIR="${tombstone_dir}"
export BOKRILOG_RESTORE_DIR="${restore_dir}"
export BOKRILOG_STATE_DIR="${state_dir}"
export BOKRILOG_SECRETS_FILE="${secrets_file}"
export BOKRILOG_COMPOSE_OVERRIDE="${script_dir}/compose.rehearsal.yaml"
export BOKRILOG_PROJECT="${project}"
export BOKRILOG_ENV=ci
export BOKRILOG_BACKUP_ACTOR=rehearsal-restore
unset BOKRILOG_BACKUP_FAULT BOKRILOG_BACKUP_NOW BOKRILOG_BACKUP_CONFIG

run_status=0
run_log=''
run_count=0

# run_script <라벨> <스크립트> <인자…>
run_script() {
  local label="$1" script="$2"
  shift 2
  run_count=$((run_count + 1))
  run_log="${outputs}/run-${run_count}.log"
  printf '\n%s\n' "${label}"
  bash "${script_dir}/${script}" "$@" >"${run_log}" 2>&1
  run_status=$?
}

show_log_on_failure() {
  [ "${tests_failed}" -eq "$1" ] || { printf '        --- 출력 ---\n'; sed 's/^/        /' "${run_log}" | tail -40; }
}

at_day() {
  printf '%s' "$((base_epoch + $1 * 86400 + 3 * 3600))"
}

# iso_of_epoch <epoch 초>. `YYYY-MM-DDTHH:MM:SSZ`.
iso_of_epoch() {
  local day seconds
  day="$(date_of_epoch "$1")"
  seconds=$(($1 % 86400))
  printf '%s-%s-%sT%02d:%02d:%02dZ' "${day:0:4}" "${day:4:2}" "${day:6:2}" $((seconds / 3600)) $(((seconds % 3600) / 60)) \
    $((seconds % 60))
}

# stamp_of <epoch 초>. `YYYYMMDDTHHMMSSZ`(tombstone 사본 object 이름).
stamp_of() {
  local day seconds
  day="$(date_of_epoch "$1")"
  seconds=$(($1 % 86400))
  printf '%sT%02d%02d%02dZ' "${day}" $((seconds / 3600)) $(((seconds % 3600) / 60)) $((seconds % 60))
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

# services_of <프로젝트>. 실행 중인 서비스 이름(정렬).
services_of() {
  docker ps --filter "label=com.docker.compose.project=$1" --format '{{.Label "com.docker.compose.service"}}' 2>/dev/null \
    | sort | tr '\n' ' ' | sed 's/ $//'
}

# resources_of <프로젝트>. 그 프로젝트의 컨테이너·volume 수.
resources_of() {
  local containers volumes
  containers="$(docker ps -a --quiet --filter "label=com.docker.compose.project=$1" 2>/dev/null | awk 'NF' | wc -l | tr -d ' ')"
  volumes="$(docker volume ls --quiet --filter "label=com.docker.compose.project=$1" 2>/dev/null | awk 'NF' | wc -l | tr -d ' ')"
  printf '%s' "$((containers + volumes))"
}

# ---------------------------------------------------------------- 1. 백업 두 번(key 회전)

day0="gen_$(date_of_epoch "$(at_day 0)")"
day1="gen_$(date_of_epoch "$(at_day 1)")"
failed_before="${tests_failed}"
BOKRILOG_BACKUP_NOW="$(at_day 0)" run_script "1. 백업 ${day0}(key k1)" backup.sh run --rehearsal
assert_equals '백업 gen0 · 종료 0' "${run_status}" '0'
show_log_on_failure "${failed_before}"
new_key k2
failed_before="${tests_failed}"
BOKRILOG_BACKUP_NOW="$(at_day 1)" run_script "1. 백업 ${day1}(key k2)" backup.sh run --rehearsal
assert_equals '백업 gen1 · 종료 0' "${run_status}" '0'
assert_equals '두 generation이 offsite에 있다' \
  "$(store lsf --files-only "offsite:${offsite_bucket}/generations" 2>/dev/null | grep -cE '\.dump\.age$' || true)" '2'
if matches "$(store cat "offsite:${offsite_bucket}/generations/${day0}.k1.dump.age.sha256" 2>/dev/null | tr -d '\r' | sed -n 2p)" \
  'dump_started_at=[0-9]{9,11}'; then
  record_pass 'checksum 파일 둘째 줄은 dump 시작 시각'
else
  record_fail 'checksum 파일 둘째 줄은 dump 시작 시각' "$(store cat "offsite:${offsite_bucket}/generations/${day0}.k1.dump.age.sha256" 2>&1)"
fi
show_log_on_failure "${failed_before}"

run_script '1. latest는 가장 최근 generation(gen1, key k2)' restore.sh --generation latest --failure-at 2099-01-01T00:00:00Z \
  --rpo-only --rehearsal
assert_contains 'latest · gen1을 고른다' "${run_log}" "generation ${day1} (key k2)"

# gen0의 생성 시각(dump 시작)을 restore.sh 자신에게 묻는다 — RPO 경계와 정상 복원의 장애 시각이 같은 기준을 쓴다.
run_script '   gen0 생성 시각' restore.sh --generation "${day0}" --failure-at 2099-01-01T00:00:00Z --rpo-only --rehearsal
gen0_epoch="$(sed -n 's/.*생성 시각 \([0-9][0-9]*\)(dump 시작).*/\1/p' "${run_log}" | head -1)"
matches "${gen0_epoch:-}" '[0-9]{9,11}' || fail_setup 'gen0의 dump 시작 시각을 읽지 못했다.' "$(tail -5 "${run_log}")"
printf '  gen0 dump 시작 %s\n' "${gen0_epoch}"
normal_failure_at="$(iso_of_epoch $((gen0_epoch + 3600)))"

# ---------------------------------------------------------------- 2. tombstone 없음

failed_before="${tests_failed}"
run_script '2. tombstone 사본 없이 복원(r1)' restore.sh --generation "${day0}" --failure-at "${normal_failure_at}" \
  --project bokrilog-restore-r1 --rehearsal
assert_equals 'tombstone 없음 · 종료 1' "${run_status}" '1'
assert_contains 'tombstone 없음 · TOMBSTONES_UNAVAILABLE' "${run_log}" 'TOMBSTONES_UNAVAILABLE'
assert_equals 'tombstone 없음 · 아무것도 띄우지 않았다(적용 전 단계에서 멈춤)' "$(resources_of bokrilog-restore-r1)" '0'
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 3. 삭제 표본

printf '\n3. 삭제 표본\n'
sample_run() {
  compose --profile deletion-sample run --rm --no-deps -T "$@" deletion-sample >"${outputs}/deletion-${run_count}.log" 2>&1
}
run_count=$((run_count + 1))
sample_run -e BOKRILOG_ENV=production -e BOKRILOG_DELETION_SAMPLE_REHEARSAL=true \
  -e "BOKRILOG_DELETION_SAMPLE_USER=${kept_user}" -e BOKRILOG_DELETION_SAMPLE_REQUEST=rehearsal-refused-1
refused_status=$?
assert_equals '삭제 표본 · 운영 환경 값이면 기동 거부' "$([ "${refused_status}" -ne 0 ] && printf refused)" 'refused'
assert_contains '삭제 표본 · 거부 사유는 운영 환경' "${outputs}/deletion-${run_count}.log" 'DELETION_SAMPLE_PRODUCTION'
run_count=$((run_count + 1))
sample_run -e "BOKRILOG_DELETION_SAMPLE_USER=${kept_user}" -e BOKRILOG_DELETION_SAMPLE_REQUEST=rehearsal-refused-2
refused_status=$?
assert_equals '삭제 표본 · 리허설 표지가 없으면 기동 거부' "$([ "${refused_status}" -ne 0 ] && printf refused)" 'refused'
assert_contains '삭제 표본 · 거부 사유는 리허설 표지 없음' "${outputs}/deletion-${run_count}.log" 'DELETION_SAMPLE_NOT_REHEARSAL'
assert_equals '삭제 표본 · 거부된 실행은 유지 계정을 바꾸지 않았다' \
  "$(psql_src "SELECT state FROM identity_account_lifecycle WHERE user_id = '${kept_user}'")" 'ACTIVE'
run_count=$((run_count + 1))
sample_run -e BOKRILOG_DELETION_SAMPLE_REHEARSAL=true -e "BOKRILOG_DELETION_SAMPLE_USER=${sample_user}" \
  -e BOKRILOG_DELETION_SAMPLE_REQUEST=rehearsal-delete-1
deletion_status=$?
assert_equals '삭제 표본 · 종료 0' "${deletion_status}" '0'
[ "${deletion_status}" -eq 0 ] || sed 's/^/        /' "${outputs}/deletion-${run_count}.log" | tail -20
assert_equals '삭제 표본 · 운영 대역에서 DELETED' \
  "$(psql_src "SELECT state FROM identity_account_lifecycle WHERE user_id = '${sample_user}'")" 'DELETED'
assert_equals '삭제 표본 · outbox 행 하나(같은 트랜잭션)' \
  "$(psql_src "SELECT count(*) FROM operations_deletion_tombstone WHERE request_id = 'rehearsal-delete-1'")" '1'
assert_equals '삭제 표본 · 운영 대역에서 모듈 데이터 0(source·portfolio·sync·plan·operations(문의)·engagement(동의))' \
  "$(psql_src "SELECT (SELECT count(*) FROM source_connection WHERE user_id = '${sample_user}') + (SELECT count(*) FROM portfolio_snapshot WHERE user_id = '${sample_user}') + (SELECT count(*) FROM sync_source_snapshot WHERE user_id = '${sample_user}') + (SELECT count(*) FROM plan_condition_confirmation WHERE user_id = '${sample_user}') + (SELECT count(*) FROM support_inquiry WHERE user_id = '${sample_user}') + (SELECT count(*) FROM engagement_ad_consent_decision WHERE user_id = '${sample_user}')")" '0'
# 재가입 차단 digest(Story 5.4): 지운 로그인 매핑마다 digest 하나. 원 식별자(이메일 모양 표지)는 남지 않는다.
assert_equals '삭제 표본 · 재가입 차단 digest 하나(원 식별자 없음)' \
  "$(psql_src "SELECT count(*) || ':' || bool_and(identity_digest ~ '^[0-9a-f]{64}\$') FROM identity_deleted_identity")" '1:true'
# 기한이 지난 outbox 행(40일 전 요청). 집합이 열린 채 멈춘 실행이라 백업 파기가 완료가 아니고(Story 5.3) 기한이 지나도 남고 실린다 —
# 복원이 옮긴 뒤 닫힐 길이 없으므로 restore-apply가 닫는다. (닫힌 집합의 미완료 행은 아래 4에서 Worker가 멈춘 뒤 넣는다 — 3b의 Worker
# 판정이 그 행을 먼저 완료로 바꾸지 않게.)
compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password --quiet --set=ON_ERROR_STOP=1 \
  --command="INSERT INTO operations_deletion_tombstone (request_id, user_digest, requested_at, destroy_after, origin) SELECT 'rehearsal-unfinished-1', repeat('f', 64), t.at, t.at + interval '840 hours', 'DELETION' FROM (SELECT date_trunc('milliseconds', now()) - interval '40 days' AS at) t" \
  >>"${outputs}/psql.log" 2>&1 || fail_setup '기한 지난 outbox 행을 넣지 못했다.'

# ---------------------------------------------------------------- 3b. 삭제 실행(Story 5.3 — Worker 첫 claim의 tombstone)

# 운영 대역의 Worker(운영 compose의 그 서비스, BOKRILOG_ENV=ci, tombstone 키를 받는다)가 삭제 요청을 집는다. 첫 단계 앞에서 요청 시각(V30)의
# tombstone을 남기고, 끝까지 지우면 마지막 단계가 영향 generation 집합을 닫고 보존을 늘린다. 백업 gen0·gen1은 그 전의 상태(삭제 진행
# 중·요청 행)를 담는다 — 아래 정상 복원이 그 상태를 복원하고 이 tombstone으로 그 계정을 다시 지운다("실행 전 복원").
failed_before="${tests_failed}"
printf '\n3b. 삭제 실행(Worker 첫 claim)\n'
pending_requested="$(psql_src "SELECT to_char(date_trunc('milliseconds', requested_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS') FROM identity_deletion_request WHERE request_id = 'rehearsal-pending-1'")"
compose up -d --no-deps --wait --wait-timeout 180 worker >>"${outputs}/stack.log" 2>&1 \
  || fail_setup 'Worker가 준비되지 않았다.' "$(compose logs --no-color worker 2>&1 | tail -20)"
waited=0
while [ "${waited}" -lt 120 ]; do
  [ "$(psql_src "SELECT state FROM identity_account_lifecycle WHERE user_id = '${pending_user}'")" = DELETED ] && break
  sleep 2
  waited=$((waited + 2))
done
compose logs --no-color worker >"${outputs}/source-worker.log" 2>&1
compose stop worker >>"${outputs}/stack.log" 2>&1
compose rm -f worker >>"${outputs}/stack.log" 2>&1
assert_equals '삭제 실행 · Worker가 요청 계정을 끝까지 지웠다' \
  "$(psql_src "SELECT state FROM identity_account_lifecycle WHERE user_id = '${pending_user}'")" 'DELETED'
assert_equals '삭제 실행 · tombstone의 요청 시각은 삭제 요청(V30)의 시각이다(첫 claim)' \
  "$(psql_src "SELECT to_char(requested_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS') FROM operations_deletion_tombstone WHERE request_id = 'rehearsal-pending-1'")" \
  "${pending_requested}"
assert_equals '삭제 실행 · 운영 완료가 집합을 닫고 보존을 늘렸다(줄지 않는다)' \
  "$(psql_src "SELECT origin || ':' || (closed_at IS NOT NULL) || ':' || (destroy_after >= closed_at + interval '840 hours') || ':' || (destroy_after >= requested_at + interval '840 hours') FROM operations_deletion_tombstone WHERE request_id = 'rehearsal-pending-1'")" \
  'DELETION:true:true:true'
assert_equals '삭제 실행 · 감사 SYSTEM 행은 기록·연장 한 번씩이고 수뿐이다' \
  "$(psql_src "SELECT string_agg(reason || ':' || target || ':' || (details->>'count'), ',' ORDER BY reason) FROM operations_audit_event WHERE command = 'tombstone' AND reason IN ('RECORDED', 'RETENTION_EXTENDED') AND actor_kind = 'SYSTEM' AND actor_user_id IS NULL")" \
  'RECORDED:deletion-tombstone:1,RECORDED:deletion-tombstone:1,RETENTION_EXTENDED:deletion-tombstone:1,RETENTION_EXTENDED:deletion-tombstone:1'
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 4. 내보내기

failed_before="${tests_failed}"
now_s="$(date -u +%s)"
old_object="$(stamp_of $((now_s - 25 * 3600))).age"
recent_object="$(stamp_of $((now_s - 2 * 3600))).age"
for fake in "${old_object}" "${recent_object}"; do
  printf 'older-tombstone-object\n' | store rcat "offsite:${offsite_bucket}/tombstones/${fake}" >>"${outputs}/stack.log" 2>&1 \
    || fail_setup '앞선 사본 object를 올리지 못했다.'
done
# 기한이 지난 닫힌 집합의 미완료 행(40일 전 요청, Story 5.3). 기한이 지나도 남고 실린다 — 아래에서 완료로 바꿔 지워지는 전이를 본다.
compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password --quiet --set=ON_ERROR_STOP=1 \
  --command="INSERT INTO operations_deletion_tombstone (request_id, user_digest, requested_at, destroy_after, origin, closed_at) SELECT 'rehearsal-expired-1', repeat('e', 64), t.at, t.at + interval '840 hours', 'DELETION', t.at FROM (SELECT date_trunc('milliseconds', now()) - interval '40 days' AS at) t" \
  >>"${outputs}/psql.log" 2>&1 || fail_setup '기한 지난 닫힌 미완료 행을 넣지 못했다.'
run_script '4. tombstone 내보내기' tombstone-export.sh --rehearsal
assert_equals '내보내기 · 종료 0' "${run_status}" '0'
latest="$(store cat "offsite:${offsite_bucket}/tombstones/latest" 2>/dev/null | tr -d '\r\n')"
if matches "${latest}" '[0-9]{8}T[0-9]{6}Z\.age'; then
  record_pass "내보내기 · latest가 새 사본을 가리킨다(${latest})"
else
  record_fail '내보내기 · latest가 새 사본을 가리킨다' "실제 '${latest}'"
fi
tombstone_objects="$(store lsf --files-only "offsite:${offsite_bucket}/tombstones" 2>/dev/null)"
assert_equals '내보내기 · 24시간 지난 사본 object는 지워졌다' \
  "$(printf '%s\n' "${tombstone_objects}" | grep -cxF "${old_object}" || true)" '0'
assert_equals '내보내기 · 24시간 안의 사본 object는 남는다' \
  "$(printf '%s\n' "${tombstone_objects}" | grep -cxF "${recent_object}" || true)" '1'
assert_equals '내보내기 · 기한이 지났어도 백업 파기가 끝나지 않은 행 둘은 남는다' \
  "$(psql_src "SELECT count(*) FROM operations_deletion_tombstone WHERE request_id IN ('rehearsal-expired-1', 'rehearsal-unfinished-1')")" '2'
peek="${work}/peek"
mkdir -p "${peek}" && chmod 700 "${peek}"
store copyto "offsite:${offsite_bucket}/tombstones/${latest}" "${peek}/latest.age" >>"${outputs}/stack.log" 2>&1
"${age_bin}" -d -i "${tombstone_keys_dir}/identity.key" -o "${peek}/tombstones.txt" "${peek}/latest.age" 2>>"${outputs}/keys.log"
assert_equals '내보내기 · 사본은 tombstone 전용 key로만 풀린다(backup key로는 안 된다)' \
  "$("${age_bin}" -d -i "${keys_dir}/identities/k2.key" "${peek}/latest.age" >/dev/null 2>&1 && printf opened || printf refused)" 'refused'
if matches "$(head -1 "${peek}/tombstones.txt" 2>/dev/null)" \
  '# bokrilog deletion tombstones DELETION-TOMBSTONE-1\.0 key=[0-9a-f]{16} exported=[0-9TZ:.-]+'; then
  record_pass '내보내기 · 머리에 키 ID와 만든 시각이 있다'
else
  record_fail '내보내기 · 머리에 키 ID와 만든 시각이 있다' "$(head -1 "${peek}/tombstones.txt" 2>&1)"
fi
assert_equals '내보내기 · 사본은 머리와 살아 있는 행 넷(표본·삭제 실행·기한 지난 미완료 둘)' "$(awk 'NF' "${peek}/tombstones.txt" 2>/dev/null | wc -l | tr -d ' ')" '5'
assert_contains '내보내기 · 기한이 지난 열린 미완료 행도 실렸다' "${peek}/tombstones.txt" ' rehearsal-unfinished-1 '
assert_equals '내보내기 · 기한이 지난 닫힌 미완료 행은 PENDING으로 실렸다' \
  "$(awk '$2 == "rehearsal-expired-1" { print $6 ":" $7 }' "${peek}/tombstones.txt")" 'PENDING:CLOSED'
assert_contains '내보내기 · 표본의 요청 ID가 실렸다' "${peek}/tombstones.txt" ' rehearsal-delete-1 '
# Story 5.3: 줄은 digest·요청 ID·요청 시각·보존 기한·파기 기한·파기 상태·집합·영향 generation 여덟 칸이다(사용자 원문 없음).
assert_equals '내보내기 · 삭제 실행의 줄은 여덟 칸이고 닫힌 집합·파기 미완료다' \
  "$(awk '$2 == "rehearsal-pending-1" { print NF ":" $6 ":" $7 }' "${peek}/tombstones.txt")" '8:PENDING:CLOSED'
if grep -F -e "${sample_user}" -e "${kept_user}" -e "${pending_user}" "${peek}/tombstones.txt" >/dev/null; then
  record_fail '내보내기 · 사용자 ID 원문이 없다' "$(cat "${peek}/tombstones.txt")"
else
  record_pass '내보내기 · 사용자 ID 원문이 없다'
fi
sample_digest="$(awk '$2 == "rehearsal-delete-1" { print $1 }' "${peek}/tombstones.txt")"
matches "${sample_digest:-}" '[0-9a-f]{64}' || record_fail '내보내기 · 표본 digest가 64자 16진이다' "실제 '${sample_digest}'"
rm -rf "${peek}"
# 사본을 올리고 대조하고 latest를 바꾼 뒤에만 성공이 기록된다(Story 5.3 — Worker gauge·경보 TombstoneExportStale의 근거).
assert_equals '내보내기 · 성공 기록 하나(실린 수)와 감사 SYSTEM 행' \
  "$(psql_src "SELECT (SELECT string_agg(CAST(tombstones AS text), ',') FROM operations_tombstone_export WHERE exported_at IS NOT NULL) || '|' || (SELECT string_agg(details->>'count', ',' ORDER BY sequence) FROM operations_audit_event WHERE command = 'tombstone' AND reason = 'EXPORTED')")" \
  '4|4'
show_log_on_failure "${failed_before}"

# Story 5.3 완료 전이: 미완료로 실렸던 행이 백업 파기 완료가 되면(판정을 흉내 낸다) 다음 내보내기는 그 행을 DESTROYED로 한 번 싣고
# (기한이 지났어도 — 앞선 사본에 실린 행이 바로 빠지면 줄어듦 검사가 영원히 막힌다) 성공으로 기록된다. 그 성공 뒤의 내보내기가 비로소 그
# 행을 지우고 뺀다 — 앞선 사본의 그 줄은 DESTROYED이고 기한이 지났으므로 줄어듦이 아니다.
failed_before="${tests_failed}"
compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password --quiet --set=ON_ERROR_STOP=1 \
  --command="UPDATE operations_deletion_tombstone SET status = 'DESTROYED', destroyed_at = statement_timestamp() WHERE request_id = 'rehearsal-expired-1'" \
  >>"${outputs}/psql.log" 2>&1 || fail_setup '기한 지난 행을 완료로 바꾸지 못했다.'
sleep 1
run_script '4. 완료 전이 뒤의 내보내기' tombstone-export.sh --rehearsal
assert_equals '완료 전이 · 종료 0(줄어듦 아님)' "${run_status}" '0'
if grep -F 'TOMBSTONE_SHRANK' "${run_log}" >/dev/null; then
  record_fail '완료 전이 · TOMBSTONE_SHRANK가 없다' "$(tail -5 "${run_log}")"
else
  record_pass '완료 전이 · TOMBSTONE_SHRANK가 없다'
fi
latest="$(store cat "offsite:${offsite_bucket}/tombstones/latest" 2>/dev/null | tr -d '\r\n')"
mkdir -p "${peek}" && chmod 700 "${peek}"
store copyto "offsite:${offsite_bucket}/tombstones/${latest}" "${peek}/latest.age" >>"${outputs}/stack.log" 2>&1
"${age_bin}" -d -i "${tombstone_keys_dir}/identity.key" -o "${peek}/tombstones.txt" "${peek}/latest.age" 2>>"${outputs}/keys.log"
assert_equals '완료 전이 · 그 행은 DESTROYED로 한 번 실렸다' \
  "$(awk '$2 == "rehearsal-expired-1" { print $6 ":" $7 }' "${peek}/tombstones.txt")" 'DESTROYED:CLOSED'
rm -rf "${peek}"
assert_equals '완료 전이 · 그 성공이 기록되기 전에는 지우지 않았다(행이 남고 성공 기록 둘)' \
  "$(psql_src "SELECT (SELECT count(*) FROM operations_deletion_tombstone WHERE request_id = 'rehearsal-expired-1') || '|' || (SELECT count(*) FROM operations_tombstone_export WHERE exported_at IS NOT NULL)")" \
  '1|2'
sleep 1
run_script '4. 완료 행이 실린 성공 뒤의 내보내기' tombstone-export.sh --rehearsal
assert_equals '완료 행 제외 · 종료 0(줄어듦 아님)' "${run_status}" '0'
if grep -F 'TOMBSTONE_SHRANK' "${run_log}" >/dev/null; then
  record_fail '완료 행 제외 · TOMBSTONE_SHRANK가 없다' "$(tail -5 "${run_log}")"
else
  record_pass '완료 행 제외 · TOMBSTONE_SHRANK가 없다'
fi
assert_equals '완료 행 제외 · 그 행은 지워졌고 만료 삭제 감사 행이 남았다' \
  "$(psql_src "SELECT (SELECT count(*) FROM operations_deletion_tombstone WHERE request_id = 'rehearsal-expired-1') || '|' || (SELECT string_agg(details->>'count', ',' ORDER BY sequence) FROM operations_audit_event WHERE command = 'tombstone' AND reason = 'EXPIRED_PURGED')")" \
  '0|1'
latest="$(store cat "offsite:${offsite_bucket}/tombstones/latest" 2>/dev/null | tr -d '\r\n')"
mkdir -p "${peek}" && chmod 700 "${peek}"
store copyto "offsite:${offsite_bucket}/tombstones/${latest}" "${peek}/latest.age" >>"${outputs}/stack.log" 2>&1
"${age_bin}" -d -i "${tombstone_keys_dir}/identity.key" -o "${peek}/tombstones.txt" "${peek}/latest.age" 2>>"${outputs}/keys.log"
if grep -F ' rehearsal-expired-1 ' "${peek}/tombstones.txt" >/dev/null; then
  record_fail '완료 행 제외 · 새 사본에 그 행이 없다' "$(cat "${peek}/tombstones.txt")"
else
  record_pass '완료 행 제외 · 새 사본에 그 행이 없다'
fi
assert_contains '완료 행 제외 · 기한 지난 열린 미완료 행은 계속 실린다' "${peek}/tombstones.txt" ' rehearsal-unfinished-1 '
rm -rf "${peek}"
show_log_on_failure "${failed_before}"

# 줄어듦 검사: outbox에서 살아 있는 행이 사라진 DB(예: 잘못된 DB·복원 중의 DB)의 내보내기는 latest를 바꾸지 못한다.
failed_before="${tests_failed}"
saved_row="$(psql_src "SELECT request_id || ',' || user_digest || ',' || requested_at || ',' || destroy_after || ',' || origin FROM operations_deletion_tombstone WHERE request_id = 'rehearsal-delete-1'")"
compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password --quiet --set=ON_ERROR_STOP=1 \
  --command="CREATE TABLE rehearsal_saved_tombstone AS SELECT * FROM operations_deletion_tombstone WHERE request_id = 'rehearsal-delete-1'; DELETE FROM operations_deletion_tombstone WHERE request_id = 'rehearsal-delete-1'" \
  >>"${outputs}/psql.log" 2>&1 || fail_setup 'outbox 행을 잠시 빼지 못했다.'
sleep 1
run_script '4. 줄어든 내보내기' tombstone-export.sh --rehearsal
assert_equals '줄어듦 · 종료 1' "${run_status}" '1'
assert_contains '줄어듦 · TOMBSTONE_SHRANK' "${run_log}" 'TOMBSTONE_SHRANK'
assert_equals '줄어듦 · latest는 그대로다' "$(store cat "offsite:${offsite_bucket}/tombstones/latest" 2>/dev/null | tr -d '\r\n')" "${latest}"
assert_equals '줄어듦 · 성공으로 기록하지 않는다' \
  "$(psql_src 'SELECT count(*) FROM operations_tombstone_export WHERE exported_at IS NOT NULL')" '3'
compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password --quiet --set=ON_ERROR_STOP=1 \
  --command="INSERT INTO operations_deletion_tombstone SELECT * FROM rehearsal_saved_tombstone; DROP TABLE rehearsal_saved_tombstone" \
  >>"${outputs}/psql.log" 2>&1 || fail_setup 'outbox 행을 되돌리지 못했다.'
assert_equals '줄어듦 · 되돌린 행이 같다' \
  "$(psql_src "SELECT request_id || ',' || user_digest || ',' || requested_at || ',' || destroy_after || ',' || origin FROM operations_deletion_tombstone WHERE request_id = 'rehearsal-delete-1'")" \
  "${saved_row}"
# 기한이 지난 미완료 행(Story 5.3 — 그 계정이 든 백업이 남아 있을 수 있다)이 빠져도 줄어듦이다. 앞선 사본의 그 줄은 기한과 무관하게 본다.
saved_unfinished="$(psql_src "SELECT request_id || ',' || user_digest || ',' || requested_at || ',' || destroy_after || ',' || origin FROM operations_deletion_tombstone WHERE request_id = 'rehearsal-unfinished-1'")"
compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password --quiet --set=ON_ERROR_STOP=1 \
  --command="CREATE TABLE rehearsal_saved_tombstone AS SELECT * FROM operations_deletion_tombstone WHERE request_id = 'rehearsal-unfinished-1'; DELETE FROM operations_deletion_tombstone WHERE request_id = 'rehearsal-unfinished-1'" \
  >>"${outputs}/psql.log" 2>&1 || fail_setup '기한 지난 미완료 행을 잠시 빼지 못했다.'
sleep 1
run_script '4. 기한 지난 미완료 행이 빠진 내보내기' tombstone-export.sh --rehearsal
assert_equals '줄어듦(미완료) · 종료 1' "${run_status}" '1'
assert_contains '줄어듦(미완료) · TOMBSTONE_SHRANK' "${run_log}" 'TOMBSTONE_SHRANK'
assert_equals '줄어듦(미완료) · latest는 그대로다' "$(store cat "offsite:${offsite_bucket}/tombstones/latest" 2>/dev/null | tr -d '\r\n')" "${latest}"
compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password --quiet --set=ON_ERROR_STOP=1 \
  --command="INSERT INTO operations_deletion_tombstone SELECT * FROM rehearsal_saved_tombstone; DROP TABLE rehearsal_saved_tombstone" \
  >>"${outputs}/psql.log" 2>&1 || fail_setup '기한 지난 미완료 행을 되돌리지 못했다.'
assert_equals '줄어듦(미완료) · 되돌린 행이 같다' \
  "$(psql_src "SELECT request_id || ',' || user_digest || ',' || requested_at || ',' || destroy_after || ',' || origin FROM operations_deletion_tombstone WHERE request_id = 'rehearsal-unfinished-1'")" \
  "${saved_unfinished}"
show_log_on_failure "${failed_before}"

# 성공 기록 실패(Story 5.3, 리허설 결함 주입 `record`): 사본은 올라가고 latest도 바뀌지만 기록은 없다 — 그래도 24시간 지난 사본 파기는 하고
# 끝에서 RECORD_FAILED(1)로 알린다.
failed_before="${tests_failed}"
stale_object="$(stamp_of $(($(date -u +%s) - 26 * 3600))).age"
printf 'older-tombstone-object\n' | store rcat "offsite:${offsite_bucket}/tombstones/${stale_object}" >>"${outputs}/stack.log" 2>&1 \
  || fail_setup '앞선 사본 object를 올리지 못했다.'
sleep 1
BOKRILOG_TOMBSTONE_FAULT=record run_script '4. 성공 기록이 실패한 내보내기' tombstone-export.sh --rehearsal
assert_equals '기록 실패 · 종료 1' "${run_status}" '1'
assert_contains '기록 실패 · RECORD_FAILED' "${run_log}" 'RECORD_FAILED'
assert_equals '기록 실패 · 그래도 24시간 지난 사본은 지웠다' \
  "$(store lsf --files-only "offsite:${offsite_bucket}/tombstones" 2>/dev/null | grep -cxF "${stale_object}" || true)" '0'
assert_equals '기록 실패 · 성공으로 기록하지 않았다' \
  "$(psql_src 'SELECT count(*) FROM operations_tombstone_export WHERE exported_at IS NOT NULL')" '3'
latest="$(store cat "offsite:${offsite_bucket}/tombstones/latest" 2>/dev/null | tr -d '\r\n')"
show_log_on_failure "${failed_before}"

# 배포·백업이 잠금을 쥐면 내보내기는 조용히 건너뛰고(종료 0, 역할 없음) 복원은 시작하지 않는다(종료 1, 역할 없음).
failed_before="${tests_failed}"
hold_lock deploy "$$"
run_script '4. 배포 중의 내보내기' tombstone-export.sh --rehearsal
assert_equals '배포 중 내보내기 · 종료 0(건너뜀)' "${run_status}" '0'
assert_contains '배포 중 내보내기 · 건너뛴다고 말한다' "${run_log}" '건너뜀: DEPLOY_IN_PROGRESS'
assert_equals '배포 중 내보내기 · latest는 그대로다' \
  "$(store cat "offsite:${offsite_bucket}/tombstones/latest" 2>/dev/null | tr -d '\r\n')" "${latest}"
hold_lock backup "$$"
run_script '4. 백업 중의 복원(r5)' restore.sh --generation "${day0}" --failure-at "${normal_failure_at}" \
  --project bokrilog-restore-r5 --rehearsal
assert_equals '백업 중 복원 · 종료 1' "${run_status}" '1'
assert_contains '백업 중 복원 · BACKUP_IN_PROGRESS' "${run_log}" 'BACKUP_IN_PROGRESS'
assert_equals '백업 중 복원 · 아무것도 띄우지 않았다' "$(resources_of bokrilog-restore-r5)" '0'
assert_equals '백업 중 · 잠금을 건드리지 않았다' "$(kv_get "${state_dir}/.lock/owner" kind)" 'backup'
release_lock
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 5. 정상 복원(삭제 이전 generation, 과거 key)

failed_before="${tests_failed}"
run_script "5. 정상 복원(r2 ← ${day0}, key k1, 장애 ${normal_failure_at})" restore.sh --generation "${day0}" \
  --failure-at "${normal_failure_at}" --project bokrilog-restore-r2 --rehearsal
assert_equals '정상 복원 · 종료 0' "${run_status}" '0'
assert_contains '정상 복원 · 검증 보고가 통과다' "${run_log}" 'result=PASSED'
assert_contains '정상 복원 · tombstone 둘(표본·삭제 실행)이 복원된 계정과 맞았다' "${run_log}" 'matched=2 erased=2'
# 모듈별 잔여(Story 5.4) — 다시 지운 표본·삭제 실행 계정의 로그인 매핑·세션·자격정보·plan·source·portfolio·sync·operations(문의)·engagement(동의)가 각각 0이다.
assert_contains '정상 복원 · 보고가 모듈별 잔여 0을 따로 싣는다' "${run_log}" \
  'residual_identity=0 residual_sign_in=0 residual_sessions=0 residual_credentials=0 residual_source=0 residual_portfolio=0 residual_sync=0 residual_plan=0 residual_operations=0 residual_engagement=0 residual_grants=0'
assert_equals '정상 복원 · 표본의 모듈 데이터 0(복원된 gen0에는 있었다)' \
  "$(psql_restored bokrilog-restore-r2 "SELECT (SELECT count(*) FROM source_connection WHERE user_id = '${sample_user}') + (SELECT count(*) FROM source_credential WHERE connection_id = '${sample_connection_id}') + (SELECT count(*) FROM portfolio_snapshot WHERE user_id = '${sample_user}') + (SELECT count(*) FROM sync_source_snapshot WHERE user_id = '${sample_user}') + (SELECT count(*) FROM plan_condition_confirmation WHERE user_id = '${sample_user}') + (SELECT count(*) FROM support_inquiry WHERE user_id = '${sample_user}') + (SELECT count(*) FROM engagement_ad_consent_decision WHERE user_id = '${sample_user}')")" '0'
# 재가입 차단 digest(Story 5.4)는 gen0의 매핑에서 다시 만들어진다 — gen0은 두 계정의 삭제 전이라 digest가 없었다.
assert_equals '정상 복원 · 재가입 차단 digest를 gen0의 매핑에서 다시 만들었다(표본·삭제 실행 둘)' \
  "$(psql_restored bokrilog-restore-r2 "SELECT count(*) FROM identity_deleted_identity")" '2'
# Story 5.3 "실행 전 복원": gen0은 삭제 요청 뒤·실행 전의 상태(삭제 진행 중·요청 행)를 담았다 — 첫 claim의 tombstone으로 다시 지웠다.
assert_equals '실행 전 복원 · 요청 계정은 DELETED이고 요청 행·payload가 없다' \
  "$(psql_restored bokrilog-restore-r2 "SELECT state || ':' || (SELECT count(*) FROM identity_deletion_request WHERE user_id = '${pending_user}') || ':' || (SELECT count(*) FROM identity_account WHERE user_id = '${pending_user}') FROM identity_account_lifecycle WHERE user_id = '${pending_user}'")" \
  'DELETED:0:0'
assert_equals '실행 전 복원 · 복원 적용의 감사 SYSTEM 행은 수뿐이다' \
  "$(psql_restored bokrilog-restore-r2 "SELECT reason || ':' || target || ':' || (details->>'erased') || ':' || (details->>'matched') FROM operations_audit_event WHERE command = 'tombstone' AND reason LIKE 'RESTORE%'")" \
  'RESTORE_APPLIED:deletion-tombstone:2:2'
assert_contains '정상 복원 · 사본의 키 ID가 맞았다' "${run_log}" 'key_id=MATCHED'
assert_equals '정상 복원 · 맞는 계정 없이 열린 채 옮긴 tombstone은 restore-apply가 닫았다'   "$(psql_restored bokrilog-restore-r2 "SELECT origin || ':' || (closed_at IS NOT NULL) || ':' || (destroy_after >= closed_at + interval '840 hours') FROM operations_deletion_tombstone WHERE request_id = 'rehearsal-unfinished-1'")"   'RESTORE:true:true'
assert_contains '정상 복원 · 표본 자격정보가 지금 key ring으로 풀렸다' "${run_log}" 'credentials_opened=1/1 active_key=PRESENT'
assert_contains '정상 복원 · RPO 통과' "${run_log}" 'RPO 통과'
assert_equals '정상 복원 · 표본은 DELETED' \
  "$(psql_restored bokrilog-restore-r2 "SELECT state FROM identity_account_lifecycle WHERE user_id = '${sample_user}'")" 'DELETED'
assert_equals '정상 복원 · 표본의 payload·인증 매핑 0' \
  "$(psql_restored bokrilog-restore-r2 "SELECT (SELECT count(*) FROM identity_account WHERE user_id = '${sample_user}') + (SELECT count(*) FROM identity_social_link WHERE user_id = '${sample_user}')")" '0'
assert_equals '정상 복원 · 세션 0(유지 계정 것도 폐기)' \
  "$(psql_restored bokrilog-restore-r2 'SELECT count(*) FROM spring_session')" '0'
assert_equals '정상 복원 · 유지 계정은 ACTIVE·매핑·자격정보 그대로' \
  "$(psql_restored bokrilog-restore-r2 "SELECT state || ':' || (SELECT count(*) FROM identity_social_link WHERE user_id = '${kept_user}') || ':' || (SELECT count(*) FROM source_credential k JOIN source_connection c ON c.connection_id = k.connection_id WHERE c.user_id = '${kept_user}') FROM identity_account_lifecycle WHERE user_id = '${kept_user}'")" \
  'ACTIVE:1:1'
assert_equals '정상 복원 · 외부 tombstone이 복원된 outbox로 옮겨졌다(RESTORE)' \
  "$(psql_restored bokrilog-restore-r2 "SELECT origin FROM operations_deletion_tombstone WHERE request_id = 'rehearsal-delete-1'")" 'RESTORE'
assert_equals '정상 복원 · API·Worker가 떴고 nginx는 없다' "$(services_of bokrilog-restore-r2)" 'api-blue postgres worker'
published="$(docker ps --filter label=com.docker.compose.project=bokrilog-restore-r2 --format '{{.Ports}}' 2>/dev/null \
  | grep -F -- '->' || true)"
assert_equals '정상 복원 · 공개 포트 없음' "${published}" ''
for service in api-blue worker; do
  assert_equals "정상 복원 · ${service}에 로그인 제공자·Bybit·공개 URL 값이 없고 env는 restore다" \
    "$(restored bokrilog-restore-r2 exec -T "${service}" sh -c 'printf "%s|%s|%s|%s|%s" "${BOKRILOG_GOOGLE_CLIENT_ID:-}" "${BOKRILOG_BYBIT_BASE_URL:-}" "${BOKRILOG_BYBIT_RESERVED_IP:-}" "${BOKRILOG_PUBLIC_BASE_URL:-}" "${BOKRILOG_ENV:-}"' 2>/dev/null)" \
    '||||restore'
done
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 6. RPO 경계와 생성 시각 기준

failed_before="${tests_failed}"
run_script '6. RPO 경계: dump 시작 + 24h' restore.sh --generation "${day0}" --failure-at "$(iso_of_epoch $((gen0_epoch + 86400)))" \
  --rpo-only --rehearsal
assert_equals 'RPO 24h · 종료 0' "${run_status}" '0'
assert_contains 'RPO 24h · 통과' "${run_log}" 'RPO 통과'
run_script '6. RPO 경계: dump 시작 + 24h 1초' restore.sh --generation "${day0}" \
  --failure-at "$(iso_of_epoch $((gen0_epoch + 86401)))" --rpo-only --rehearsal
assert_equals 'RPO 24h 1초 · 종료 1' "${run_status}" '1'
assert_contains 'RPO 24h 1초 · RPO_EXCEEDED' "${run_log}" 'RPO_EXCEEDED'
# dump 시작 시각이 없는 옛 generation(checksum 한 줄)은 generation 날짜 00:00 UTC로 잰다.
legacy='gen_20251201'
printf 'legacy-ciphertext\n' | store rcat "offsite:${offsite_bucket}/generations/${legacy}.k1.dump.age" >>"${outputs}/stack.log" 2>&1
printf '%s  %s\n' "$(printf '%064d' 0)" "${legacy}.k1.dump.age" \
  | store rcat "offsite:${offsite_bucket}/generations/${legacy}.k1.dump.age.sha256" >>"${outputs}/stack.log" 2>&1
run_script '6. 옛 generation: 날짜 00:00 UTC + 24h' restore.sh --generation "${legacy}" --failure-at 2025-12-02T00:00:00Z \
  --rpo-only --rehearsal
assert_equals '옛 generation · 24h 종료 0' "${run_status}" '0'
assert_contains '옛 generation · 기준은 generation 날짜 00:00 UTC' "${run_log}" '(generation 날짜 00:00 UTC)'
run_script '6. 옛 generation: 날짜 00:00 UTC + 24h 1초' restore.sh --generation "${legacy}" --failure-at 2025-12-02T00:00:01Z \
  --rpo-only --rehearsal
assert_equals '옛 generation · 24h 1초 종료 1' "${run_status}" '1'
run_script '6. 30일을 넘긴 generation(r3)' restore.sh --generation "${legacy}" --failure-at "$(iso_of_epoch "$(date -u +%s)")" \
  --project bokrilog-restore-r3 --rehearsal
assert_equals '오래된 generation · 종료 1' "${run_status}" '1'
assert_contains '오래된 generation · GENERATION_TOO_OLD' "${run_log}" 'GENERATION_TOO_OLD'
assert_equals '오래된 generation · 아무것도 띄우지 않았다' "$(resources_of bokrilog-restore-r3)" '0'
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 7. 키

failed_before="${tests_failed}"
mv "${keys_dir}/identities/k1.key" "${work}/k1.key.aside"
run_script '7. 과거 identity 없이 복원(r3)' restore.sh --generation "${day0}" --failure-at "${normal_failure_at}" \
  --project bokrilog-restore-r3 --rehearsal
mv "${work}/k1.key.aside" "${keys_dir}/identities/k1.key"
assert_equals '키 없음 · 종료 1' "${run_status}" '1'
assert_contains '키 없음 · KEY_UNAVAILABLE' "${run_log}" 'KEY_UNAVAILABLE'
assert_equals '키 없음 · 격리 프로젝트에 아무것도 없다' "$(resources_of bokrilog-restore-r3)" '0'
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 8. 변조

failed_before="${tests_failed}"
tampered='gen_20251230'
printf 'tampered-ciphertext\n' | store rcat "offsite:${offsite_bucket}/generations/${tampered}.k2.dump.age" \
  >>"${outputs}/stack.log" 2>&1
printf '%s  %s\ndump_started_at=%s\n' "$(printf '%064d' 0)" "${tampered}.k2.dump.age" "$(($(date -u +%s) - 60))" \
  | store rcat "offsite:${offsite_bucket}/generations/${tampered}.k2.dump.age.sha256" >>"${outputs}/stack.log" 2>&1
run_script '8. checksum이 맞지 않는 generation(r4)' restore.sh --generation "${tampered}" \
  --failure-at "$(iso_of_epoch "$(date -u +%s)")" --project bokrilog-restore-r4 --rehearsal
assert_equals '변조 · 종료 1' "${run_status}" '1'
assert_contains '변조 · CHECKSUM_MISMATCH' "${run_log}" 'CHECKSUM_MISMATCH'
assert_equals '변조 · 격리 프로젝트에 아무것도 없다' "$(resources_of bokrilog-restore-r4)" '0'
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 9. 적용 실패(다른 key ring)·tombstone 키 불일치

# variant_secrets <파일> <이름> <값>. 리허설 비밀 파일에서 한 이름만 바꾼 사본.
variant_secrets() {
  awk -v name="$2" -v value="$3" 'index($0, name "=") == 1 { print name "=" value; next } { print }' "${secrets_file}" >"$1"
  chmod 600 "$1"
}

failed_before="${tests_failed}"
variant_secrets "${work}/other-ring.env" BOKRILOG_SOURCE_CREDENTIAL_KEYS "rehearsal:$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
BOKRILOG_SECRETS_FILE="${work}/other-ring.env" run_script '9. 다른 자격정보 key ring으로 복원(r6, --keep-on-failure)' restore.sh \
  --generation "${day0}" --failure-at "${normal_failure_at}" --project bokrilog-restore-r6 --keep-on-failure --rehearsal
assert_equals '적용 실패 · 종료 1' "${run_status}" '1'
assert_contains '적용 실패 · APPLY_FAILED' "${run_log}" 'APPLY_FAILED'
assert_contains '적용 실패 · 보고는 CREDENTIALS_UNAVAILABLE' "${run_log}" 'codes=CREDENTIALS_UNAVAILABLE'
assert_contains '적용 실패 · --keep-on-failure가 지우는 명령을 알린다' "${run_log}" 'down --volumes --remove-orphans'
assert_equals '적용 실패 · PostgreSQL만 남았다(API·Worker 미기동)' "$(services_of bokrilog-restore-r6)" 'postgres'
restored bokrilog-restore-r6 down --remove-orphans --volumes >/dev/null 2>&1
variant_secrets "${work}/other-digest.env" BOKRILOG_TOMBSTONE_DIGEST_KEY "$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
BOKRILOG_SECRETS_FILE="${work}/other-digest.env" run_script '9. 다른 tombstone digest 키로 복원(r7)' restore.sh \
  --generation "${day0}" --failure-at "${normal_failure_at}" --project bokrilog-restore-r7 --rehearsal
assert_equals 'digest 키 불일치 · 종료 1' "${run_status}" '1'
assert_contains 'digest 키 불일치 · TOMBSTONE_KEY_ID_MISMATCH' "${run_log}" 'codes=TOMBSTONE_KEY_ID_MISMATCH'
assert_equals 'digest 키 불일치 · 실패한 격리 프로젝트는 volume까지 지워졌다' "$(resources_of bokrilog-restore-r7)" '0'
rm -f "${work}/other-ring.env" "${work}/other-digest.env"
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 10. 풀 수 없는·머리 없는·오래된 latest

failed_before="${tests_failed}"
broken="$(stamp_of "$(date -u +%s)").age"
printf 'not-an-age-file\n' | store rcat "offsite:${offsite_bucket}/tombstones/${broken}" >>"${outputs}/stack.log" 2>&1
printf '%s\n' "${broken}" | store rcat "offsite:${offsite_bucket}/tombstones/latest" >>"${outputs}/stack.log" 2>&1
run_script '10. 풀 수 없는 latest(r8)' restore.sh --generation "${day0}" --failure-at "${normal_failure_at}" \
  --project bokrilog-restore-r8 --rehearsal
assert_equals '풀 수 없는 latest · 종료 1' "${run_status}" '1'
assert_contains '풀 수 없는 latest · TOMBSTONES_UNAVAILABLE' "${run_log}" 'TOMBSTONES_UNAVAILABLE (복호화 실패)'
assert_equals '풀 수 없는 latest · 격리 프로젝트를 지웠다' "$(resources_of bokrilog-restore-r8)" '0'
sleep 1
headerless="$(stamp_of "$(date -u +%s)").age"
printf 'digest request requested destroy\n' >"${work}/headerless.txt"
"${age_bin}" -R "${tombstone_keys_dir}/recipient.pub" -o "${work}/headerless.age" "${work}/headerless.txt" 2>>"${outputs}/keys.log"
store copyto "${work}/headerless.age" "offsite:${offsite_bucket}/tombstones/${headerless}" >>"${outputs}/stack.log" 2>&1
printf '%s\n' "${headerless}" | store rcat "offsite:${offsite_bucket}/tombstones/latest" >>"${outputs}/stack.log" 2>&1
rm -f "${work}/headerless.txt" "${work}/headerless.age"
run_script '10. 머리 없는 latest(r9)' restore.sh --generation "${day0}" --failure-at "${normal_failure_at}" \
  --project bokrilog-restore-r9 --rehearsal
assert_equals '머리 없는 latest · 종료 1' "${run_status}" '1'
assert_contains '머리 없는 latest · TOMBSTONES_UNAVAILABLE' "${run_log}" 'TOMBSTONES_UNAVAILABLE (tombstone 파일의 머리가 없다)'
assert_equals '머리 없는 latest · 격리 프로젝트를 지웠다' "$(resources_of bokrilog-restore-r9)" '0'
printf '%s\n' "${latest}" | store rcat "offsite:${offsite_bucket}/tombstones/latest" >>"${outputs}/stack.log" 2>&1
latest_stamp="${latest%.age}"
latest_epoch="$(epoch_of_iso "${latest_stamp:0:4}-${latest_stamp:4:2}-${latest_stamp:6:2}T${latest_stamp:9:2}:${latest_stamp:11:2}:${latest_stamp:13:2}Z")"
run_script '10. 장애 시각보다 2시간 앞선 latest' restore.sh --generation "${day0}" \
  --failure-at "$(iso_of_epoch $((latest_epoch + 7200)))" --project bokrilog-restore-r9 --rehearsal
assert_equals '오래된 latest · 종료 1' "${run_status}" '1'
assert_contains '오래된 latest · TOMBSTONES_STALE' "${run_log}" 'TOMBSTONES_STALE'
assert_equals '오래된 latest · 아무것도 띄우지 않았다' "$(resources_of bokrilog-restore-r9)" '0'
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 11. 운영 이름·비어 있지 않은 프로젝트

failed_before="${tests_failed}"
run_script '11. 운영 프로젝트 이름' restore.sh --generation "${day0}" --failure-at "${normal_failure_at}" \
  --project bokrilog-production --rehearsal
assert_equals '운영 이름 · 종료 2' "${run_status}" '2'
run_script '11. 운영 대역 프로젝트 이름' restore.sh --generation "${day0}" --failure-at "${normal_failure_at}" \
  --project "${project}" --rehearsal
assert_equals '운영 대역 이름(BOKRILOG_PROJECT) · 종료 2' "${run_status}" '2'
run_script '11. 이미 복원된(비어 있지 않은) 프로젝트' restore.sh --generation "${day0}" --failure-at "${normal_failure_at}" \
  --project bokrilog-restore-r2 --rehearsal
assert_equals '비어 있지 않은 프로젝트 · 종료 2' "${run_status}" '2'
assert_contains '비어 있지 않은 프로젝트 · 사유' "${run_log}" '비어 있지 않다'
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 12. 누출

printf '\n12. 누출 검사\n'
restored bokrilog-restore-r2 logs --no-color >"${outputs}/restored-containers.log" 2>&1
secret_values="${kept_user} ${sample_user} ${pending_user} ${kept_email} ${sample_email} ${pending_email} ${s3_secret} AGE-SECRET-KEY- ${sample_digest:-} ${api_secret}"
for value in $(awk -F= '/^BOKRILOG_(AUDIT|TOMBSTONE)_DIGEST_KEY=/ { print $2 } /^BOKRILOG_SOURCE_CREDENTIAL_KEYS=/ { sub(/^[^:]*:/, "", $2); print $2 }' "${secrets_file}"); do
  secret_values="${secret_values} ${value}"
done
leaks=''
for value in ${secret_values}; do
  for file in "${outputs}"/run-*.log "${outputs}"/deletion-*.log "${outputs}/restored-containers.log" "${outputs}/source-worker.log" \
    "${restore_dir}"/logs/*.log "${tombstone_dir}"/logs/*.log; do
    [ -f "${file}" ] || continue
    grep -F -- "${value}" "${file}" >/dev/null && leaks="${leaks} ${file##*/}"
  done
done
assert_equals '누출 · 실행 출력·도구 기록·복원된 컨테이너 로그에 사용자 ID·이메일·tombstone digest·key·자격정보·저장소 비밀이 없다' \
  "${leaks}" ''
assert_equals '누출 · 복원·내보내기 임시본(평문 dump·tombstone)이 남지 않았다' \
  "$(find "${restore_dir}/tmp" "${tombstone_dir}/tmp" -type f 2>/dev/null | wc -l | tr -d ' ')" '0'
assert_equals '누출 · 평문 dump가 작업 디렉터리 어디에도 없다' \
  "$(find "${restore_dir}" "${tombstone_dir}" "${backup_dir}" -type f -name '*.dump' 2>/dev/null | wc -l | tr -d ' ')" '0'
names="$(store lsf --files-only "offsite:${offsite_bucket}/tombstones" 2>/dev/null | tr '\n' ' ')"
bad_names=''
for name in ${names}; do
  matches "${name}" '([0-9]{8}T[0-9]{6}Z\.age|latest)' || bad_names="${bad_names} ${name}"
done
assert_equals '누출 · tombstone object 이름은 시각뿐이다' "${bad_names}" ''

# ---------------------------------------------------------------- 판정

printf '\n%s개 중 %s개 통과, %s개 실패\n' "${tests_run}" "$((tests_run - tests_failed))" "${tests_failed}"
printf '이 결과는 CI 리허설이다(같은 호스트의 MinIO 두 개, 가상 시계, 리허설 key). 실제 별도 환경에서 복원하기 전까지 복원은 실제 복원 미검증이다.\n'
if [ "${tests_failed}" -ne 0 ]; then
  printf '::error::복원 리허설이 실패했다.\n'
  exit 1
fi
exit 0
