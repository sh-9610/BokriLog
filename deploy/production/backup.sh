#!/usr/bin/env bash
# 일일 암호화 백업의 이중 보관과 30일 파기(Story 3.9 AC-1~5).
#
# 실행
#   deploy/production/backup.sh run [--rehearsal]     하루 한 번(systemd timer `bokrilog-backup.timer`). 아래 순서 전체.
#   deploy/production/backup.sh purge [--rehearsal]   파기·측정만 다시 돈다(저장소 문제를 고친 뒤). 같은 날 `run`은 no-op이므로
#                                                     파기만 다시 돌리는 길이 이것이다.
#   deploy/production/backup.sh keys [--rehearsal]    두 저장소에 남은 generation이 쓰는 key ID마다 identity·recipient 짝과 권한을
#                                                     확인한다.
#
# 순서(run)
#   0. 같은 날 재실행 판정: 표에 그날 generation(`gen_<UTC YYYYMMDD>`)의 `SUCCEEDED`가 있으면 아무것도 하지 않는다(행·object 불변).
#   1. 배포와 같은 잠금(`$BOKRILOG_STATE_DIR/.lock`, 안의 `owner`가 kind·pid·operation·시작 시각)을 쥔다. 배포 중이면
#      `DEPLOY_IN_PROGRESS`, 다른 백업 중이면 `BACKUP_IN_PROGRESS`, 소유자가 죽었으면 `LOCK_STALE`(지우지 않는다)로 끝난다 — 그
#      `FAILED` 행은 JVM을 띄우지 않고 대기열에만 넣는다(배포의 blue/green peak 중에 기록 역할 512m를 더 띄우지 않는다).
#   2. 대기열(`$BOKRILOG_BACKUP_DIR/pending/`)을 먼저 기록하고, 잠금을 쥔 뒤 0을 다시 본다(동시에 손으로 돌린 실행).
#   3. 앞선 실행이 남긴 로컬 임시본을 지우고(`PURGED LOCAL_TEMP`) `STARTED`를 기록한다.
#   4. 두 저장소의 `incoming/`(앞선 실행의 부분 object)을 비운다(`PURGED INCOMING_CLEARED`).
#   5. `pg_dump --format=custom`(운영 postgres 컨테이너의 것, 로컬 소켓) → 권한 700 임시 디렉터리. 종료 코드 0과 비어 있지 않음을
#      본 뒤에만
#   6. `age -R active.pub`로 암호화하고 평문을 바로 지운다.
#   7. 암호문 SHA-256.
#   8. `age -d -i identities/<key_id>.key | pg_restore --list`: pg_restore가 성공하고 목록이 비어 있지 않으며, pg_restore가 TOC를 읽고
#      끝난 뒤 남은 stream을 끝까지 읽어 age가 EOF까지 가서 전체를 인증하고 0으로 끝난다(두 종료 코드를 따로 본다).
#   9. 두 저장소 각각 `incoming/`에 암호문·checksum을 올리고 원격 SHA-256(내려받아 계산)과 checksum 파일을 대조한다.
#  10. 둘 다 맞으면 두 저장소 모두 `generations/`로 옮긴다. 한쪽 이동이 실패하면 이미 옮긴 쪽을 `incoming/`으로 되돌린다(못 하면
#      지운다) — `generations/`에 기록되지 않은 generation이 남지 않는다. 여기까지 모두 성공해야 `SUCCEEDED`다.
#  11. 결과 행 하나를 따로 기록한다.
#  12. 파기(`### 파기`, DEVELOPMENT.md)·측정을 저장소 목록만으로 하고 그 행을 따로 기록한다. DB에 닿지 못해도(PostgreSQL 정지·V24
#      없음·시작 기록 실패) 파기는 돈다 — dump는 하지 않고, 행은 대기열에 넣고, 종료 1이다. 마지막 성공을 알 수 없으면(조회 실패·
#      기록되지 않은 대기열) 수량·bytes 상한 파기를 건너뛰고 `PURGE_FAILED LIST_FAILED`를 남긴다 — 30일 규칙은 그대로 돈다.
#   기록은 한 번에 200행 이하이고, 기록 뒤 표에서 그만큼의 행이 생겼는지 psql로 확인한다(그 프로필이 없는 옛 이미지는 아무것도 쓰지
#   않고 0으로 끝날 수 있다). 기록하지 못한 행은 대기열에 남고 다음 실행이 먼저 기록한다.
#   어느 경로·신호에서든 임시 디렉터리(평문 포함)를 지운다. 긴 단계는 배경에서 돌리고 `wait`하므로 신호가 오면 바로 처리기가 돌아 그
#   단계를 끝내고, `FAILED INTERRUPTED`를 기록(못 하면 대기열)하고, 잠금을 놓는다.
#
# 시각은 실제 시계(UTC)다. 리허설만 `BOKRILOG_BACKUP_NOW`(epoch 초)로 덮는다 — 운영에서는 거부한다. 가상 시계의 파기 결과는 실제 파기의
# 증거가 아니다(**실저장소 미검증**).
#
# 설정(이름만 여기 있고 값은 운영자가 준다)
#   BOKRILOG_BACKUP_KEYS_DIR  backup key·저장소 설정 디렉터리(기본 /etc/bokrilog/backup, 권한 700). 저장소 밖이다.
#       active.pub            지금 key. 첫 줄 `# key_id=<id>`, 다음 줄 `age1…`(공개 값, group·other 쓰기 금지).
#       recipients/<id>.pub   key마다 recipient(공개 값). active.pub의 recipient와 같아야 한다.
#       identities/<id>.key   age identity(비밀, 디렉터리 700·파일 600). 보존이 끝나기 전에는 과거 identity를 지우지 않는다.
#       rclone.conf           두 저장소 remote 설정(비밀, 권한 600).
#       backup.env            `key=value`: oci_remote·offsite_remote·max_generations·max_bytes_oci·max_bytes_offsite·
#                             quota_bytes_oci·quota_bytes_offsite(견본 `backup.env.example`).
#   BOKRILOG_BACKUP_CONFIG    backup.env 경로(기본 `$BOKRILOG_BACKUP_KEYS_DIR/backup.env`).
#   BOKRILOG_BACKUP_DIR       작업 디렉터리(기본 /var/lib/bokrilog/backup, 권한 700): `tmp/`(평문·암호문 임시본)·`logs/`(도구 출력)·
#                             `pending/`(기록하지 못한 행).
#   BOKRILOG_BACKUP_TOOLS     age·rclone 설치 디렉터리(기본 /opt/bokrilog/backup-tools, `install-backup-tools.sh`).
#   BOKRILOG_BACKUP_ACTOR     감사 행의 수행자 이름(기본 `backup-timer`). 원장에는 `backup:` keyed digest로만 남는다.
#   BOKRILOG_STATE_DIR        배포 상태 디렉터리(기본 /var/lib/bokrilog/deploy) — `.lock`과 활성 이미지(`deploy.state`).
#   BOKRILOG_SECRETS_FILE     운영 env 파일(기본 /etc/bokrilog/production.env). compose 치환 원본 — backup key는 여기 없다.
#   BOKRILOG_COMPOSE_OVERRIDE·BOKRILOG_PROJECT   배포 스크립트와 같다(리허설만 override).
#   BOKRILOG_BACKUP_FAULT     리허설 전용 결함 주입(쉼표 목록): `dump`·`slow-dump`·`encrypt`·`catalog`·`tamper-oci`·`move-offsite`·
#                             `purge-oci`·`purge-offsite`.
#
# backup key·저장소 자격정보는 DB·Git·로그·compose 환경·앱 프로세스 어디에도 넣지 않는다. 이 스크립트는 그 값을 읽지 않고 경로만
# 도구에 넘긴다. object 이름은 `gen_<YYYYMMDD>.<key_id>.dump.age(.sha256)`뿐이다 — 사용자·금융 식별자가 없다.
#
# 종료 코드
#   0 -- 성공(또는 같은 날 no-op), 파기 완료, 모든 행 기록 / keys: 필요한 key가 모두 맞다
#   1 -- 단계 실패·파기 실패·기한 초과·기록 실패(대기열)·잠금(배포 중·백업 중·LOCK_STALE)·중단 / keys: 문제가 있거나 저장소를 읽지 못했다
#   2 -- 준비 실패(인자·설정·도구·key 파일·권한·모드)

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib.sh
. "${script_dir}/lib.sh"

compose_file="${script_dir}/compose.yaml"
keys_dir="${BOKRILOG_BACKUP_KEYS_DIR:-/etc/bokrilog/backup}"
config_file="${BOKRILOG_BACKUP_CONFIG:-${keys_dir}/backup.env}"
backup_dir="${BOKRILOG_BACKUP_DIR:-/var/lib/bokrilog/backup}"
tools_dir="${BOKRILOG_BACKUP_TOOLS:-/opt/bokrilog/backup-tools}"
state_dir="${BOKRILOG_STATE_DIR:-/var/lib/bokrilog/deploy}"
secrets_file="${BOKRILOG_SECRETS_FILE:-/etc/bokrilog/production.env}"
override_file="${BOKRILOG_COMPOSE_OVERRIDE:-}"
project="${BOKRILOG_PROJECT:-bokrilog-production}"
actor="${BOKRILOG_BACKUP_ACTOR:-backup-timer}"
virtual_now="${BOKRILOG_BACKUP_NOW:-}"
fault="${BOKRILOG_BACKUP_FAULT:-}"
pending_dir="${backup_dir}/pending"

# 30일 규칙(AC-4). 생성 후 이만큼을 넘으면 지우고, 이만큼을 넘긴 것이 남으면 기한 초과다. 설정으로 열지 않는다.
purge_after_seconds=$((29 * 86400))
overdue_after_seconds=$((30 * 86400))

# 한 번의 기록 상한. `BackupRecordApi.MAX_ROWS`(500)보다 작다.
record_chunk=200
# 단계마다의 상한(초). 넘기면 그 단계를 끝내고 실패로 다룬다.
step_limit=3600
record_limit=300
query_limit=60

stores='oci offsite'
object_pattern='gen_[0-9]{8}\.[a-z0-9][a-z0-9_-]{0,31}\.dump\.age(\.sha256)?'

prep_fail() {
  printf '::error::백업 준비 실패: %s\n' "$1" >&2
  exit 2
}

say() {
  printf '%s\n' "$*"
}

# --------------------------------------------------------------------------
# 인자·모드
# --------------------------------------------------------------------------

command_name="${1:-}"
[ "$#" -gt 0 ] && shift
rehearsal=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --rehearsal) rehearsal=true; shift ;;
    *) prep_fail "알 수 없는 인자: $1" ;;
  esac
done
case "${command_name}" in
  run | purge | keys) ;;
  *) prep_fail '명령은 run·purge·keys 중 하나다.' ;;
esac

# 리허설은 그것이 리허설임을 두 가지로 보여야 한다 — 개발·CI 환경 값과 리허설 override(배포 gate와 같은 규칙). 운영에서는 가상 시계·
# 결함 주입·override를 거부한다.
if [ "${rehearsal}" = true ]; then
  case "${BOKRILOG_ENV:-}" in
    ci | development) ;;
    *) prep_fail '리허설은 BOKRILOG_ENV가 ci·development일 때만 받는다.' ;;
  esac
  [ -n "${override_file}" ] && [ -f "${override_file}" ] || prep_fail '리허설은 리허설 override(BOKRILOG_COMPOSE_OVERRIDE)가 있어야 한다.'
else
  case "${BOKRILOG_ENV:-}" in
    '' | production) ;;
    *) prep_fail "운영 모드인데 BOKRILOG_ENV가 ${BOKRILOG_ENV}이다." ;;
  esac
  [ -z "${override_file}" ] || prep_fail '운영 모드에 겹치는 compose 파일이 있다.'
  [ -z "${virtual_now}" ] || prep_fail 'BOKRILOG_BACKUP_NOW(가상 시계)는 리허설 전용이다. 운영에서 거부한다.'
  [ -z "${fault}" ] || prep_fail 'BOKRILOG_BACKUP_FAULT(결함 주입)는 리허설 전용이다. 운영에서 거부한다.'
fi
matches "${project}" 'bokrilog-[a-z][a-z0-9-]{0,39}' || prep_fail 'BOKRILOG_PROJECT는 bokrilog-<소문자·숫자·->여야 한다.'
matches "${actor}" '[A-Za-z0-9._@-]{1,128}' || prep_fail 'BOKRILOG_BACKUP_ACTOR의 모양이 틀렸다.'
for injected in $(printf '%s' "${fault}" | tr ',' ' '); do
  case "${injected}" in
    dump | slow-dump | encrypt | catalog | tamper-oci | move-offsite | purge-oci | purge-offsite) ;;
    *) prep_fail "알 수 없는 결함 주입: ${injected}" ;;
  esac
done

# has_fault <이름>
has_fault() {
  case ",${fault}," in
    *",$1,"*) return 0 ;;
  esac
  return 1
}

# --------------------------------------------------------------------------
# 설정
# --------------------------------------------------------------------------

[ -f "${config_file}" ] || prep_fail "설정 파일이 없다: ${config_file}"
config_keys='oci_remote offsite_remote max_generations max_bytes_oci max_bytes_offsite quota_bytes_oci quota_bytes_offsite'
while IFS= read -r key; do
  [ -n "${key}" ] || continue
  case " ${config_keys} " in
    *" ${key} "*) ;;
    *) prep_fail "설정에 알 수 없는 키가 있다: ${key}" ;;
  esac
done <<KEYS
$(kv_keys "${config_file}")
KEYS
oci_remote="$(kv_get "${config_file}" oci_remote)"
offsite_remote="$(kv_get "${config_file}" offsite_remote)"
max_generations="$(kv_get "${config_file}" max_generations)"
for pair in "oci_remote:${oci_remote}" "offsite_remote:${offsite_remote}"; do
  value="${pair#*:}"
  matches "${value}" '[A-Za-z0-9_-]+:[A-Za-z0-9._/-]+' || prep_fail "${pair%%:*}은 <remote>:<bucket[/경로]>여야 한다."
  case "${value}" in
    */) prep_fail "${pair%%:*}이 /로 끝난다(object 경로가 \`//\`가 된다)." ;;
    *//*) prep_fail "${pair%%:*}에 \`//\`가 있다." ;;
    *:/*) prep_fail "${pair%%:*}의 bucket이 /로 시작한다." ;;
  esac
done
# 두 저장소는 서로 다른 rclone remote여야 한다 — 같은 remote의 다른 경로는 같은 업체·같은 계정이라 이중 보관이 아니다.
[ "${oci_remote%%:*}" != "${offsite_remote%%:*}" ] \
  || prep_fail '두 저장소가 같은 rclone remote를 쓴다(OCI와 OCI 밖은 다른 remote여야 한다).'
matches "${max_generations}" '[1-9][0-9]{0,3}' || prep_fail 'max_generations는 양의 정수여야 한다.'
for store in ${stores}; do
  configured_max="$(kv_get "${config_file}" "max_bytes_${store}")"
  configured_quota="$(kv_get "${config_file}" "quota_bytes_${store}")"
  matches "${configured_max}" '[1-9][0-9]{0,17}' || prep_fail "max_bytes_${store}는 양의 정수여야 한다."
  matches "${configured_quota}" '[1-9][0-9]{0,17}' || prep_fail "quota_bytes_${store}는 양의 정수여야 한다."
  [ "${configured_max}" -le "${configured_quota}" ] || prep_fail "max_bytes_${store}가 quota_bytes_${store}보다 크다."
done

# --------------------------------------------------------------------------
# key 파일·권한
# --------------------------------------------------------------------------

active_pub="${keys_dir}/active.pub"
rclone_conf="${keys_dir}/rclone.conf"
[ -f "${active_pub}" ] || prep_fail "recipient 파일이 없다: ${active_pub}"
[ -f "${rclone_conf}" ] || prep_fail "저장소 설정이 없다: ${rclone_conf}"
key_id="$(awk -F= '/^# key_id=/ { print $2; exit }' "${active_pub}" | tr -d '\r')"
matches "${key_id}" '[a-z0-9][a-z0-9_-]{0,31}' || prep_fail 'active.pub의 첫 줄은 `# key_id=<id>`여야 한다(소문자·숫자·_-).'
recipient_lines="$(grep -cE '^age1[0-9a-z]+$' "${active_pub}" || true)"
[ "${recipient_lines}" = 1 ] || prep_fail 'active.pub에는 age recipient가 정확히 하나여야 한다.'
active_recipient="$(grep -E '^age1[0-9a-z]+$' "${active_pub}" | tr -d '\r')"

# 이 파일 시스템이 POSIX 권한을 표현하는가. Windows의 Git Bash는 아니다 — 리허설에 한해 권한 검사를 건너뛴다(운영은 언제나 판정).
posix_modes=true
if [ "${rehearsal}" = true ]; then
  probe="$(mktemp "${TMPDIR:-/tmp}/bokrilog-mode.XXXXXX" 2>/dev/null)" || probe=''
  if [ -n "${probe}" ]; then
    chmod 600 "${probe}" 2>/dev/null
    case "$(ls -ln "${probe}" | awk '{ print $1 }')" in
      -rw-------*) ;;
      *) posix_modes=false ;;
    esac
    rm -f "${probe}"
  fi
fi

mode_of() {
  ls -lnd "$1" 2>/dev/null | awk '{ print substr($1, 1, 10) }'
}

# private_file <경로>. 소유자만 읽고 쓸 수 있다(600·400).
private_file() {
  [ "${posix_modes}" = true ] || return 0
  case "$(mode_of "$1")" in
    -rw------- | -r--------) return 0 ;;
  esac
  return 1
}

# private_dir <경로>. 소유자만 들어갈 수 있다(700).
private_dir() {
  [ "${posix_modes}" = true ] || return 0
  [ "$(mode_of "$1")" = drwx------ ]
}

# not_shared_writable <경로>. group·other가 쓸 수 없다.
not_shared_writable() {
  [ "${posix_modes}" = true ] || return 0
  local mode
  mode="$(mode_of "$1")"
  [ -n "${mode}" ] && [ "$(printf '%s' "${mode}" | cut -c6)" != w ] && [ "$(printf '%s' "${mode}" | cut -c9)" != w ]
}

# key_dir_problems. key 디렉터리·파일 권한의 문제를 줄마다 낸다(없으면 빈 출력).
key_dir_problems() {
  private_dir "${keys_dir}" || printf '%s 권한이 700이 아니다\n' "${keys_dir}"
  if [ -d "${keys_dir}/identities" ]; then
    private_dir "${keys_dir}/identities" || printf 'identities/ 권한이 700이 아니다\n'
  else
    printf 'identities/가 없다\n'
  fi
  [ -d "${keys_dir}/recipients" ] || printf 'recipients/가 없다\n'
  not_shared_writable "${active_pub}" || printf 'active.pub을 group·other가 쓸 수 있다\n'
  private_file "${rclone_conf}" || printf 'rclone.conf 권한이 600이 아니다\n'
}

# tool <이름>. 설치 디렉터리의 바이너리 경로(Windows는 .exe).
tool() {
  if [ -x "${tools_dir}/bin/$1" ]; then
    printf '%s' "${tools_dir}/bin/$1"
  elif [ -x "${tools_dir}/bin/$1.exe" ]; then
    printf '%s' "${tools_dir}/bin/$1.exe"
  fi
}

# key_problem <key ID>. 그 key의 문제 하나를 낸다(없으면 빈 출력): identity 존재·권한, recipient 존재·쓰기 권한, 두 파일이 짝인가
# (`age-keygen -y identity` = recipient).
key_problem() {
  local identity="${keys_dir}/identities/$1.key" recipient="${keys_dir}/recipients/$1.pub" derived wanted
  if [ ! -s "${identity}" ]; then
    printf 'identities/%s.key가 없다' "$1"
  elif ! private_file "${identity}"; then
    printf 'identities/%s.key 권한이 600이 아니다' "$1"
  elif [ ! -s "${recipient}" ]; then
    printf 'recipients/%s.pub이 없다' "$1"
  elif ! not_shared_writable "${recipient}"; then
    printf 'recipients/%s.pub을 group·other가 쓸 수 있다' "$1"
  else
    derived="$("$(tool age-keygen)" -y "${identity}" 2>/dev/null | tr -d '\r')"
    wanted="$(grep -E '^age1[0-9a-z]+$' "${recipient}" | head -1 | tr -d '\r')"
    if [ -z "${derived}" ] || [ "${derived}" != "${wanted}" ]; then
      printf 'identities/%s.key와 recipients/%s.pub이 짝이 아니다' "$1" "$1"
    fi
  fi
}

bash "${script_dir}/install-backup-tools.sh" --check --prefix "${tools_dir}" >/dev/null 2>&1 \
  || prep_fail "age·rclone이 고정 버전으로 설치돼 있지 않다(${tools_dir}). install-backup-tools.sh를 먼저 돈다."
age_bin="$(tool age)"
rclone_bin="$(tool rclone)"
have_sha256_tool || prep_fail 'sha256sum 또는 shasum이 없다.'

remote_of() {
  case "$1" in
    oci) printf '%s' "${oci_remote}" ;;
    offsite) printf '%s' "${offsite_remote}" ;;
  esac
}

# --------------------------------------------------------------------------
# 배경 실행. 긴 단계는 배경에서 돌리고 기다린다 — 그래야 신호가 오면 bash가 그 단계가 끝나기를 기다리지 않고 바로 처리기를 돈다. 한
# 단계에는 상한이 있다(넘으면 끝내고 124 대신 그 단계의 종료 코드).
# --------------------------------------------------------------------------

child=''

# guarded <상한 초> <명령…>. stdin은 호출자의 것을 그대로 넘긴다(배경 명령의 stdin이 /dev/null로 바뀌지 않게).
#
# 감시자는 1초마다 명령이 살아 있는지 보고, 상한을 넘기면 TERM을, 5초 뒤에도 남았으면 KILL을 보낸다 — 신호 처리기 안에서 띄운 명령은
# TERM을 무시한 채 물려받으므로 KILL까지 가야 끝난다. 명령이 끝나면 감시자를 KILL로 끝낸다(TERM은 같은 이유로 무시될 수 있고, 그러면
# 감시자를 기다리는 동안 상한만큼 멈춘다). 남는 것은 길어야 1초짜리 `sleep` 하나다.
guarded() {
  local limit="$1" status watchdog
  shift
  "$@" 0<&0 &
  child=$!
  (
    waited=0
    while [ "${waited}" -lt "${limit}" ]; do
      kill -0 "${child}" 2>/dev/null || exit 0
      sleep 1
      waited=$((waited + 1))
    done
    kill -TERM -- "-${child}" 2>/dev/null || kill -TERM "${child}" 2>/dev/null
    sleep 5
    kill -KILL -- "-${child}" 2>/dev/null || kill -KILL "${child}" 2>/dev/null
  ) </dev/null >/dev/null 2>&1 &
  watchdog=$!
  wait "${child}"
  status=$?
  kill -KILL "${watchdog}" 2>/dev/null
  wait "${watchdog}" 2>/dev/null
  child=''
  return "${status}"
}

# 실행마다 같은 rclone 옵션. 저장소가 멈췄을 때 오래 매달리지 않게 재시도·timeout을 줄인다. 설정은 경로로만 넘긴다(값을 읽지 않는다).
rclone_run() {
  guarded "${step_limit}" "${rclone_bin}" --config "${rclone_conf}" --retries 2 --low-level-retries 3 --contimeout 10s \
    --timeout 2m --log-level ERROR "$@"
}

# list_files <store> <prefix>. `size|name` 줄들. 없는 경로(종료 3)는 빈 목록이다. 읽지 못하면 1.
list_files() {
  local out status
  out="$(rclone_run lsf --files-only --format sp --separator '|' "$(remote_of "$1")/$2" 2>>"${log_file:-/dev/null}")"
  status=$?
  case "${status}" in
    0) printf '%s' "${out}" ;;
    3) ;;
    *) return 1 ;;
  esac
  return 0
}

# --------------------------------------------------------------------------
# 시각(UTC). epoch 초 ↔ 달력 날짜는 awk 산술로 한다(GNU·BSD date의 인자가 다르다).
# --------------------------------------------------------------------------

if [ -n "${virtual_now}" ]; then
  matches "${virtual_now}" '[0-9]{9,11}' || prep_fail 'BOKRILOG_BACKUP_NOW는 epoch 초여야 한다.'
  now="${virtual_now}"
else
  now="$(date -u +%s)"
fi

# date_of_epoch <epoch 초>. YYYYMMDD.
date_of_epoch() {
  awk -v z="$(($1 / 86400))" 'BEGIN {
    z += 719468; era = int(z / 146097); doe = z - era * 146097
    yoe = int((doe - int(doe / 1460) + int(doe / 36524) - int(doe / 146096)) / 365)
    y = yoe + era * 400; doy = doe - (365 * yoe + int(yoe / 4) - int(yoe / 100))
    mp = int((5 * doy + 2) / 153); d = doy - int((153 * mp + 2) / 5) + 1; m = mp + (mp < 10 ? 3 : -9)
    printf "%04d%02d%02d", y + (m <= 2), m, d
  }'
}

# epoch_of_generation <gen_YYYYMMDD>. 그날 00:00 UTC의 epoch 초.
epoch_of_generation() {
  awk -v g="${1#gen_}" 'BEGIN {
    y = substr(g, 1, 4) + 0; m = substr(g, 5, 2) + 0; d = substr(g, 7, 2) + 0
    y -= (m <= 2); era = int(y / 400); yoe = y - era * 400
    doy = int((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5) + d - 1
    doe = yoe * 365 + int(yoe / 4) - int(yoe / 100) + doy
    printf "%.0f", (era * 146097 + doe - 719468) * 86400
  }'
}

generation="gen_$(date_of_epoch "${now}")"
operation="backup_${generation#gen_}_$(random_hex 6)"

# --------------------------------------------------------------------------
# keys
# --------------------------------------------------------------------------

if [ "${command_name}" = keys ]; then
  log_file=''
  problems=0
  while IFS= read -r line; do
    [ -n "${line}" ] || continue
    say "  문제  ${line}"
    problems=$((problems + 1))
  done <<PROBLEMS
$(key_dir_problems)
PROBLEMS
  active_listed="$(grep -E '^age1[0-9a-z]+$' "${keys_dir}/recipients/${key_id}.pub" 2>/dev/null | head -1 | tr -d '\r')"
  if [ "${active_listed}" != "${active_recipient}" ]; then
    say "  문제  active.pub의 recipient가 recipients/${key_id}.pub과 다르다"
    problems=$((problems + 1))
  fi
  needed=" ${key_id} "
  unreadable=''
  for store in ${stores}; do
    for prefix in generations incoming; do
      if ! listing="$(list_files "${store}" "${prefix}")"; then
        unreadable="${unreadable} ${store}/${prefix}"
        continue
      fi
      while IFS='|' read -r size name; do
        [ -n "${name}" ] || continue
        matches "${name}" "${object_pattern}" || continue
        used="$(printf '%s' "${name}" | awk -F. '{ print $2 }')"
        case "${needed}" in
          *" ${used} "*) ;;
          *) needed="${needed}${used} " ;;
        esac
      done <<LIST
${listing}
LIST
    done
  done
  missing=''
  for id in ${needed}; do
    problem="$(key_problem "${id}")"
    if [ -z "${problem}" ]; then
      say "  있음  ${id}"
    else
      say "  없음  ${id}  (${problem})"
      missing="${missing} ${id}"
    fi
  done
  if [ -n "${unreadable}" ]; then
    say "::error::저장소를 읽지 못해 필요한 key를 모두 확인하지 못했다:${unreadable}"
    exit 1
  fi
  if [ -n "${missing}" ] || [ "${problems}" -gt 0 ]; then
    summary=''
    [ -n "${missing}" ] && summary=" 쓸 수 없는 key${missing}."
    [ "${problems}" -gt 0 ] && summary="${summary} 권한·짝 문제 ${problems}건."
    say "::error::key 확인 실패:${summary} 보존이 끝나기 전에는 과거 identity를 지우지 않는다."
    exit 1
  fi
  say "필요한 key가 모두 맞다(active ${key_id})."
  exit 0
fi

# --------------------------------------------------------------------------
# run·purge 준비
# --------------------------------------------------------------------------

first_problem="$(key_dir_problems | head -1)"
[ -z "${first_problem}" ] || prep_fail "key 디렉터리: ${first_problem}"
active_problem="$(key_problem "${key_id}")"
[ -z "${active_problem}" ] || prep_fail "active key: ${active_problem}"
[ "$(grep -E '^age1[0-9a-z]+$' "${keys_dir}/recipients/${key_id}.pub" | head -1 | tr -d '\r')" = "${active_recipient}" ] \
  || prep_fail "active.pub의 recipient가 recipients/${key_id}.pub과 다르다."
active_identity="${keys_dir}/identities/${key_id}.key"
[ -f "${secrets_file}" ] || prep_fail "비밀 파일이 없다: ${secrets_file}"
command -v docker >/dev/null 2>&1 || prep_fail 'docker가 없다.'
docker compose version >/dev/null 2>&1 || prep_fail 'docker compose v2를 쓸 수 없다.'
[ -f "${state_dir}/deploy.state" ] || prep_fail "배포 상태가 없다(첫 배포 전에는 백업할 것도 기록할 곳도 없다): ${state_dir}/deploy.state"
image="$(kv_get "${state_dir}/deploy.state" active_image)"
[ -n "${image}" ] || prep_fail '배포 상태에 활성 이미지가 없다.'

umask 077
mkdir -p "${backup_dir}/tmp" "${backup_dir}/logs" "${pending_dir}" || prep_fail "작업 디렉터리를 만들지 못했다: ${backup_dir}"
chmod 700 "${backup_dir}" "${backup_dir}/tmp" "${backup_dir}/logs" "${pending_dir}" 2>/dev/null
log_file="${backup_dir}/logs/${operation}.log"
: >"${log_file}" || prep_fail "기록 파일을 만들지 못했다: ${log_file}"
# 도구 출력 기록은 30일이 지나면 지운다(값은 없지만 쌓이지 않게).
find "${backup_dir}/logs" -type f -name 'backup_*.log' -mtime +30 -exec rm -f {} + 2>/dev/null

compose() {
  local args
  args=(--project-name "${project}" --env-file "${secrets_file}" --file "${compose_file}")
  if [ -n "${override_file}" ]; then
    args+=(--file "${override_file}")
  fi
  BOKRILOG_STATE_DIR="${state_dir}" BOKRILOG_ENV="${BOKRILOG_ENV:-}" \
    BOKRILOG_IMAGE="${image}" BOKRILOG_AUDIT_IMAGE="${image}" BOKRILOG_BLUE_IMAGE="${image}" \
    BOKRILOG_GREEN_IMAGE="${image}" BOKRILOG_WORKER_IMAGE="${image}" \
    docker compose "${args[@]}" "$@"
}

# psql_value <SQL>. 값 하나. psql이 실패하면 1(pipefail).
psql_value() {
  guarded "${query_limit}" compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password \
    --tuples-only --no-align --set=ON_ERROR_STOP=1 --command="$1" </dev/null 2>>"${log_file}" | tr -d '[:space:]'
}

# --------------------------------------------------------------------------
# 기록·대기열
# --------------------------------------------------------------------------

db_ok=true
db_code=''
pending_seq=0

# rows 칸: event,generation,store,code,bytes,count,quota,sha256,key_id
rows=''
add_row() {
  rows="${rows}${rows:+;}$1,$2,${3:-},${4:-},${5:-},${6:-},${7:-},${8:-},${9:-}"
}

# queue_pending <행들>. 실행 하나의 operation ID와 함께 파일 하나로 둔다(같은 디렉터리의 임시 이름 → rename). 잠금 없이도 쓸 수 있다 —
# 파일 이름이 실행마다 다르다.
queue_pending() {
  local file tmp
  [ -n "$1" ] || return 0
  pending_seq=$((pending_seq + 1))
  file="${pending_dir}/${operation}.$(printf '%03d' "${pending_seq}").rows"
  tmp="${pending_dir}/.${operation}.$(printf '%03d' "${pending_seq}").tmp"
  printf '%s\n%s\n' "${operation}" "$1" >"${tmp}" && mv "${tmp}" "${file}"
}

# record_batch <operation ID> <행들>. 일회성 기록 역할 하나로 한 트랜잭션에 쓰고, 표에 정확히 그만큼의 행이 새로 생겼는지 본다.
record_batch() {
  local op="$1" batch="$2" before created want
  [ "${db_ok}" = true ] || return 1
  before="$(psql_value 'SELECT COALESCE(max(sequence), 0) FROM operations_backup_event')" || return 1
  matches "${before}" '[0-9]+' || return 1
  guarded "${record_limit}" compose --profile backup-record run --rm --no-deps -T \
    -e "BOKRILOG_BACKUP_OPERATION=${op}" -e "BOKRILOG_BACKUP_ACTOR=${actor}" \
    -e "BOKRILOG_BACKUP_EVENTS=${batch}" -e "BOKRILOG_BACKUP_REHEARSAL=${rehearsal}" backup-record \
    </dev/null >>"${log_file}" 2>&1 || return 1
  want="$(printf '%s' "${batch}" | awk -F';' '{ print NF }')"
  created="$(psql_value "SELECT count(*) FROM operations_backup_event WHERE sequence > ${before}")" || return 1
  if [ "${created}" != "${want}" ]; then
    say "  경고  기록 역할이 ${want}행 대신 ${created:-?}행을 남겼다(그 이미지에 backup-record 프로필이 없을 수 있다)."
    return 1
  fi
  return 0
}

# record_rows <행들>. 200행씩 기록하고, 기록하지 못한 묶음은 대기열에 둔다. 하나라도 대기열로 가면 1.
record_rows() {
  local all="$1" chunk='' count=0 line status=0
  [ -n "${all}" ] || return 0
  while IFS= read -r line; do
    [ -n "${line}" ] || continue
    chunk="${chunk}${chunk:+;}${line}"
    count=$((count + 1))
    if [ "${count}" -ge "${record_chunk}" ]; then
      record_or_queue "${chunk}" || status=1
      chunk=''
      count=0
    fi
  done <<ROWS
$(printf '%s\n' "${all}" | tr ';' '\n')
ROWS
  if [ -n "${chunk}" ]; then
    record_or_queue "${chunk}" || status=1
  fi
  return "${status}"
}

record_or_queue() {
  record_batch "${operation}" "$1" && return 0
  queue_pending "$1"
  say "  경고  행 $(printf '%s' "$1" | awk -F';' '{ print NF }')개를 기록하지 못해 대기열(${pending_dir})에 남겼다."
  return 1
}

# flush_pending. 대기열을 오래된 것부터 기록한다. 하나라도 남으면 1(나머지는 다음 실행이 다시 본다).
flush_pending() {
  local file op batch gen
  for file in "${pending_dir}"/*.rows; do
    [ -f "${file}" ] || continue
    op="$(sed -n 1p "${file}")"
    batch="$(sed -n 2p "${file}")"
    if matches "${op}" '[A-Za-z0-9_-]{1,64}' && [ -n "${batch}" ] && record_batch "${op}" "${batch}"; then
      rm -f "${file}"
      say "  대기열  ${file##*/} 기록"
      continue
    fi
    # 성공 행 하나가 실제로는 이미 들어가 있으면(기록 뒤 확인만 실패했다) 다시 쓰지 않는다 — 두 번째 성공은 거절된다.
    case "${batch}" in
      SUCCEEDED,*)
        gen="$(printf '%s' "${batch}" | awk -F, '{ print $2 }')"
        if matches "${gen}" 'gen_[0-9]{8}' && [ "$(psql_value "SELECT EXISTS (SELECT 1 FROM operations_backup_event WHERE event = 'SUCCEEDED' AND generation_id = '${gen}')")" = t ]; then
          rm -f "${file}"
          continue
        fi
        ;;
    esac
    say "  경고  대기열 ${file##*/}을 기록하지 못했다."
    return 1
  done
  return 0
}

pending_left() {
  for file in "${pending_dir}"/*.rows; do
    [ -f "${file}" ] && return 0
  done
  return 1
}

succeeded_today() {
  [ "${db_ok}" = true ] || return 1
  [ "$(psql_value "SELECT EXISTS (SELECT 1 FROM operations_backup_event WHERE event = 'SUCCEEDED' AND generation_id = '${generation}')")" = t ]
}

say "backup ${command_name} ${generation} (operation ${operation})"
say "  기록: ${log_file}"

# --------------------------------------------------------------------------
# 0. DB와 같은 날 재실행
# --------------------------------------------------------------------------

if [ -z "$(guarded "${query_limit}" compose ps --status running -q postgres 2>>"${log_file}" </dev/null)" ]; then
  db_ok=false
  db_code=DB_UNAVAILABLE
  say '  경고  PostgreSQL이 돌지 않는다. dump·기록 없이 파기만 하고 행은 대기열에 둔다.'
elif [ "$(psql_value "SELECT to_regclass('public.operations_backup_event') IS NOT NULL")" != t ]; then
  db_ok=false
  db_code=RECORD_TABLE_MISSING
  say '  경고  백업 기록 표(V24)를 읽지 못했다. dump·기록 없이 파기만 하고 행은 대기열에 둔다.'
fi
if [ "${command_name}" = run ] && succeeded_today; then
  say "  ${generation}은 이미 성공했다. 아무것도 하지 않는다."
  exit 0
fi

# --------------------------------------------------------------------------
# 1. 잠금
# --------------------------------------------------------------------------

lock_dir="${state_dir}/.lock"
if ! lock_acquire "${lock_dir}" backup "${operation}"; then
  case "${lock_state}" in
    DEPLOY_IN_PROGRESS) say "  배포가 진행 중이다(${lock_owner}). 시작하지 않는다 — timer가 다시 돈다." ;;
    BACKUP_IN_PROGRESS) say "  다른 백업이 진행 중이다(${lock_owner}). 시작하지 않는다." ;;
    *) say "  LOCK_STALE: 잠금(${lock_dir})의 소유자가 없다(${lock_owner}). 지우지 않는다 — 진행 중인 배포·백업이 정말 없는지 확인한 뒤 손으로 지운다(DEVELOPMENT.md)." ;;
  esac
  # 배포가 잠금을 쥔 동안(blue/green peak)에는 기록 역할 JVM을 띄우지 않는다. 실패 행은 대기열에 두고 잠금을 쥔 다음 실행이 기록한다.
  queue_pending "FAILED,${generation},,${lock_state},,,,,"
  say "::error::백업 ${generation}: FAILED ${lock_state}"
  exit 1
fi

work=''
outcome=''
outcome_recorded=false
cleanup() {
  # 평문이 남을 수 있는 유일한 자리다. 어느 경로에서든 먼저 지운다.
  [ -n "${work}" ] && rm -rf "${work}"
  work=''
  lock_release "${lock_dir}"
}
trap cleanup EXIT

on_signal() {
  trap '' INT HUP TERM
  if [ -n "${child}" ]; then
    kill -TERM -- "-${child}" 2>/dev/null || kill -TERM "${child}" 2>/dev/null
    wait "${child}" 2>/dev/null
    child=''
  fi
  [ -n "${work}" ] && rm -rf "${work}"
  work=''
  say "::error::백업 ${generation}이 신호로 중단됐다. 임시본을 지웠다."
  # 정지 유예(systemd TimeoutStopSec 180초) 안에 끝나도록 기록 상한을 줄인다. 못 하면 대기열이다.
  record_limit=90
  if [ "${command_name}" = run ] && [ "${outcome_recorded}" = false ]; then
    record_rows "FAILED,${generation},,INTERRUPTED,,,,," || true
  fi
  queue_pending "${rows}"
  exit 1
}
trap on_signal INT HUP TERM

# 배경 단계마다 자기 process group을 준다 — 처리기가 그 group 전체(파이프라인의 age·docker)를 끝낸다.
set -m

# --------------------------------------------------------------------------
# 2. 대기열과 다시 보기
# --------------------------------------------------------------------------

if [ "${db_ok}" = true ]; then
  flush_pending || say '  경고  대기열이 남았다.'
fi
if [ "${command_name}" = run ] && succeeded_today; then
  say "  ${generation}은 그사이 성공했다. 아무것도 하지 않는다."
  exit 0
fi

# --------------------------------------------------------------------------
# 3. 로컬 임시본·시작 기록
# --------------------------------------------------------------------------

leftovers=0
for entry in "${backup_dir}"/tmp/* "${backup_dir}"/tmp/.[!.]*; do
  [ -e "${entry}" ] || continue
  rm -rf "${entry}" && leftovers=$((leftovers + 1))
done
if [ "${leftovers}" -gt 0 ]; then
  say "  정리  앞선 실행이 남긴 로컬 임시본 ${leftovers}개"
  add_row PURGED "${generation}" '' LOCAL_TEMP '' "${leftovers}"
fi

records_ok=true
if [ "${command_name}" = run ] && [ "${db_ok}" = true ] && ! record_batch "${operation}" "STARTED,${generation},,,,,,,"; then
  say '  경고  시작을 기록하지 못했다. 기록 없는 백업을 하지 않는다 — dump 없이 파기만 한다.'
  queue_pending "STARTED,${generation},,,,,,,"
  records_ok=false
  db_ok=false
  db_code=RECORD_UNAVAILABLE
fi

# --------------------------------------------------------------------------
# 4. incoming 정리
# --------------------------------------------------------------------------

if [ "${command_name}" = run ]; then
  for store in ${stores}; do
    if listing="$(list_files "${store}" incoming)"; then
      partial="$(printf '%s\n' "${listing}" | awk 'NF' | wc -l | tr -d ' ')"
      if [ "${partial}" -gt 0 ]; then
        if rclone_run delete "$(remote_of "${store}")/incoming" >>"${log_file}" 2>&1; then
          say "  정리  ${store} incoming/ 부분 object ${partial}개"
          add_row PURGED "${generation}" "${store}" INCOMING_CLEARED '' "${partial}"
        else
          say "  경고  ${store} incoming/을 비우지 못했다(파기 단계가 다시 본다)."
        fi
      fi
    else
      say "  경고  ${store} incoming/을 읽지 못했다."
    fi
  done
fi

# --------------------------------------------------------------------------
# 5~10. 생성·암호화·검증·업로드·이동
# --------------------------------------------------------------------------

fail_code=''
fail_store=''
cipher_sha=''
cipher_bytes=''
cipher_name="${generation}.${key_id}.dump.age"

step() {
  say "  $1"
}

store_code() {
  printf '%s_%s' "$1" "$(printf '%s' "$2" | tr 'a-z' 'A-Z')"
}

# catalog_stream <identity>. 복호화 stream을 pg_restore --list에 주고, pg_restore가 TOC만 읽고 끝난 뒤 남은 stream을 끝까지 읽는다 —
# 그래야 age가 EOF까지 읽어 마지막 chunk까지 인증하고(그 전에 끊기면 EPIPE) 그 종료 코드가 의미를 가진다. 두 종료 코드를 따로 남긴다.
catalog_stream() {
  "${age_bin}" -d -i "$1" "${cipher}" 2>>"${log_file}" | {
    compose exec -T postgres pg_restore --list >"${work}/catalog.txt" 2>>"${log_file}"
    printf '%s' "$?" >"${work}/restore.status"
    cat >/dev/null
  }
  printf '%s' "${PIPESTATUS[0]}" >"${work}/decrypt.status"
}

# rollback_moved <store…>. 이미 generations/로 옮긴 저장소의 object를 incoming/으로 되돌린다. 못 되돌리면 지운다.
rollback_moved() {
  local store remote
  for store in "$@"; do
    remote="$(remote_of "${store}")"
    if rclone_run moveto "${remote}/generations/${cipher_name}" "${remote}/incoming/${cipher_name}" >>"${log_file}" 2>&1 \
      && rclone_run moveto "${remote}/generations/${cipher_name}.sha256" "${remote}/incoming/${cipher_name}.sha256" \
        >>"${log_file}" 2>&1; then
      say "     ${store} generations/의 ${generation}을 incoming/으로 되돌렸다"
    else
      rclone_run delete --include "${generation}.*" "${remote}/generations" >>"${log_file}" 2>&1
      say "     ${store} generations/의 ${generation}을 되돌리지 못해 지웠다"
    fi
  done
}

produce() {
  local dbname=bokrilog recipients="${active_pub}" identity="${active_identity}" status entries
  local store remote remote_sha remote_sum moved=''

  step '5. pg_dump --format=custom'
  if has_fault slow-dump; then
    # 리허설 결함 주입: dump가 오래 걸린다. 이 사이에 신호를 보내 처리기가 바로 도는지 본다.
    guarded "${step_limit}" sleep 600
  fi
  # 리허설 결함 주입: 없는 데이터베이스를 dump하게 해 pg_dump가 0이 아닌 코드로 끝난다.
  has_fault dump && dbname=bokrilog_fault_missing
  guarded "${step_limit}" compose exec -T postgres pg_dump --username=bokrilog --dbname="${dbname}" --format=custom \
    --no-password </dev/null >"${plain}" 2>>"${log_file}"
  status=$?
  if [ "${status}" -ne 0 ] || [ ! -s "${plain}" ]; then
    fail_code=DUMP
    return 1
  fi

  step '6. age 암호화(active recipient)'
  if has_fault encrypt; then
    # 리허설 결함 주입: 읽을 수 없는 recipient로 age가 실패한다.
    recipients="${work}/invalid.pub"
    printf 'age1invalidrecipient\n' >"${recipients}"
  fi
  guarded "${step_limit}" "${age_bin}" -R "${recipients}" -o "${cipher}" "${plain}" 2>>"${log_file}"
  status=$?
  # 평문은 암호화 직후 지운다(성공·실패 무관).
  rm -f "${plain}"
  if [ "${status}" -ne 0 ] || [ ! -s "${cipher}" ]; then
    fail_code=ENCRYPT
    return 1
  fi

  step '7. 암호문 SHA-256'
  cipher_sha="$(sha256_of "${cipher}")"
  if ! matches "${cipher_sha}" '[0-9a-f]{64}'; then
    fail_code=CHECKSUM
    return 1
  fi
  printf '%s  %s\n' "${cipher_sha}" "${cipher_name}" >"${sum_file}"
  cipher_bytes="$(wc -c <"${cipher}" | tr -d ' ')"

  step '8. 복호화 stream의 pg_restore --list(age는 끝까지 인증)'
  if has_fault catalog; then
    # 리허설 결함 주입: 이 암호문을 풀 수 없는 identity로 복호화한다.
    identity="${work}/wrong.key"
    "$(tool age-keygen)" -o "${identity}" >/dev/null 2>>"${log_file}"
  fi
  guarded "${step_limit}" catalog_stream "${identity}"
  entries="$(grep -cvE '^[[:space:]]*(;|$)' "${work}/catalog.txt" 2>/dev/null || true)"
  decrypt_status="$(cat "${work}/decrypt.status" 2>/dev/null)"
  restore_status="$(cat "${work}/restore.status" 2>/dev/null)"
  rm -f "${work}/catalog.txt" "${work}/wrong.key" "${work}/decrypt.status" "${work}/restore.status"
  if [ "${decrypt_status}" != 0 ] || [ "${restore_status}" != 0 ] || [ "${entries:-0}" -eq 0 ]; then
    say "     age ${decrypt_status:-?}, pg_restore ${restore_status:-?}, catalog ${entries:-0}개"
    fail_code=CATALOG
    return 1
  fi
  say "     catalog ${entries}개 항목, 복호화 stream 끝까지 인증"

  step '9. 두 저장소 incoming/ 업로드와 원격 SHA-256 대조'
  for store in ${stores}; do
    remote="$(remote_of "${store}")"
    if ! rclone_run copyto "${cipher}" "${remote}/incoming/${cipher_name}" >>"${log_file}" 2>&1 \
      || ! rclone_run copyto "${sum_file}" "${remote}/incoming/${cipher_name}.sha256" >>"${log_file}" 2>&1; then
      fail_code="$(store_code UPLOAD "${store}")"
      fail_store="${store}"
      return 1
    fi
    if [ "${store}" = oci ] && has_fault tamper-oci; then
      # 리허설 결함 주입: 올린 object가 저장소에서 바뀐다. 원격 대조가 그것을 잡아야 한다.
      printf 'tampered\n' | rclone_run rcat "${remote}/incoming/${cipher_name}" >>"${log_file}" 2>&1
    fi
    remote_sha="$(rclone_run hashsum sha256 --download "${remote}/incoming/${cipher_name}" 2>>"${log_file}" \
      | awk 'NR == 1 { print $1 }')"
    remote_sum="$(rclone_run cat "${remote}/incoming/${cipher_name}.sha256" 2>>"${log_file}" | tr -d '\r')"
    if [ "${remote_sha}" != "${cipher_sha}" ] || [ "${remote_sum}" != "${cipher_sha}  ${cipher_name}" ]; then
      fail_code="$(store_code CHECKSUM "${store}")"
      fail_store="${store}"
      return 1
    fi
    say "     ${store} 대조 일치"
  done

  step '10. generations/로 이동'
  for store in ${stores}; do
    remote="$(remote_of "${store}")"
    # 같은 ID는 덮어쓴다 — 같은 날 앞선 실패가 남겼을 수 있는 같은 generation을 먼저 지운다. 이 삭제의 실패도 이동 실패다.
    rclone_run delete --include "${generation}.*" "${remote}/generations" >>"${log_file}" 2>&1
    status=$?
    if [ "${status}" -ne 0 ] && [ "${status}" -ne 3 ]; then
      fail_code="$(store_code MOVE "${store}")"
      fail_store="${store}"
      rollback_moved ${moved}
      return 1
    fi
    move_ok=true
    current=''
    if [ "${store}" = offsite ] && has_fault move-offsite; then
      # 리허설 결함 주입: OCI 밖 저장소의 이동이 실패한다. 이미 옮긴 쪽(OCI)을 되돌려야 한다.
      move_ok=false
    elif ! rclone_run moveto "${remote}/incoming/${cipher_name}" "${remote}/generations/${cipher_name}" >>"${log_file}" 2>&1; then
      move_ok=false
    else
      current="${store}"
      if ! rclone_run moveto "${remote}/incoming/${cipher_name}.sha256" \
        "${remote}/generations/${cipher_name}.sha256" >>"${log_file}" 2>&1 \
        || [ "$(rclone_run lsf --files-only "${remote}/generations/${cipher_name}" 2>>"${log_file}")" != "${cipher_name}" ]; then
        move_ok=false
      fi
    fi
    if [ "${move_ok}" = false ]; then
      fail_code="$(store_code MOVE "${store}")"
      fail_store="${store}"
      rollback_moved ${moved} ${current}
      return 1
    fi
    moved="${moved} ${store}"
  done
  return 0
}

if [ "${command_name}" = run ]; then
  if [ "${db_ok}" = true ]; then
    work="$(mktemp -d "${backup_dir}/tmp/run.XXXXXX")" || { say '::error::임시 디렉터리를 만들지 못했다.'; exit 1; }
    chmod 700 "${work}"
    plain="${work}/${generation}.dump"
    cipher="${work}/${cipher_name}"
    sum_file="${cipher}.sha256"
    if produce; then
      outcome=SUCCEEDED
      say "  성공  ${generation} (${cipher_bytes} bytes, key ${key_id})"
    else
      outcome=FAILED
      say "  실패  ${fail_code}"
    fi
    # 평문·암호문 임시본을 지금 지운다(파기 단계는 저장소만 다룬다).
    rm -rf "${work}"
    work=''
  else
    outcome=FAILED
    fail_code="${db_code}"
    say "  실패  ${fail_code}(dump하지 않는다)"
  fi

  # 11. 결과 행 하나를 따로 기록한다 — 파기 행 묶음의 거절이 결과를, 결과의 거절이 파기 감사 행을 되돌리지 않게.
  if [ "${outcome}" = SUCCEEDED ]; then
    outcome_row="SUCCEEDED,${generation},,,${cipher_bytes},,,${cipher_sha},${key_id}"
  else
    outcome_row="FAILED,${generation},${fail_store},${fail_code},,,,,"
  fi
  outcome_recorded=true
  record_rows "${outcome_row}" || records_ok=false
fi

# --------------------------------------------------------------------------
# 12. 파기·측정
# --------------------------------------------------------------------------

say '  파기(29일 초과·수량·용량)와 측정'
purge_problem=false

# 마지막 성공. 모르면(조회 실패·기록되지 않은 대기열) 수량·bytes 상한 파기를 하지 않는다 — 그것이 마지막 성공을 지울 수 있다.
last_success=''
last_success_known=false
if [ "${outcome}" = SUCCEEDED ]; then
  last_success="${generation}"
  last_success_known=true
elif [ "${db_ok}" = true ] && ! pending_left; then
  if found="$(psql_value "SELECT COALESCE((SELECT generation_id FROM operations_backup_event WHERE event = 'SUCCEEDED' ORDER BY sequence DESC LIMIT 1), 'NONE')")" \
    && [ -n "${found}" ]; then
    last_success_known=true
    [ "${found}" = NONE ] || last_success="${found}"
  fi
fi
if [ "${last_success_known}" = false ]; then
  say '    마지막 성공을 알 수 없어 수량·bytes 상한 파기를 건너뛴다(30일 규칙은 돈다).'
  add_row PURGE_FAILED "${generation}" '' LIST_FAILED
  purge_problem=true
fi

# delete_generation <store> <prefix> <generation>. 그 generation의 object를 모두 지운다. 하나라도 남으면 1.
delete_generation() {
  local remote
  remote="$(remote_of "$1")"
  if has_fault "purge-$1"; then
    # 리허설 결함 주입: 그 저장소의 삭제가 실패한다.
    return 1
  fi
  rclone_run delete --include "$3.*" "${remote}/$2" >>"${log_file}" 2>&1 || return 1
  [ -z "$(rclone_run lsf --files-only --include "$3.*" "${remote}/$2" 2>>"${log_file}")" ]
}

# gen_table <목록>. 이름이 모양 안인 object를 generation마다 `gen bytes 파일수 key` 한 줄로 묶는다(오래된 순). 이름 판정은 grep -E가
# 한다 — awk 정규식의 반복 구간({n})은 구현마다 다르다. 합계는 `%.0f`로 낸다(일부 awk의 `%d`는 32비트에서 잘린다).
gen_table() {
  printf '%s\n' "$1" | grep -E "^[0-9]+\|${object_pattern}\$" \
    | awk -F'|' '{ split($2, p, "."); bytes[p[1]] += $1; files[p[1]]++; key[p[1]] = p[2] }
      END { for (g in bytes) printf "%s %.0f %d %s\n", g, bytes[g], files[g], key[g] }' | sort
}

# field_of <표> <generation> <칸 번호>
field_of() {
  printf '%s\n' "$1" | awk -v g="$2" -v n="$3" '$1 == g { print $n; exit }'
}

for store in ${stores}; do
  max_bytes="$(kv_get "${config_file}" "max_bytes_${store}")"
  quota="$(kv_get "${config_file}" "quota_bytes_${store}")"
  store_overdue=false

  if ! incoming="$(list_files "${store}" incoming)" || ! listing="$(list_files "${store}" generations)"; then
    say "    ${store} 읽기 실패"
    add_row PURGE_FAILED "${generation}" "${store}" LIST_FAILED
    purge_problem=true
    continue
  fi

  # incoming/: 4단계가 비웠으므로 남은 것은 지금 실행의 실패분이거나 비우지 못한 것이다. 30일 규칙만 적용한다.
  for gen in $(gen_table "${incoming}" | awk '{ print $1 }'); do
    age_s=$((now - $(epoch_of_generation "${gen}")))
    [ "${age_s}" -gt "${purge_after_seconds}" ] || continue
    if delete_generation "${store}" incoming "${gen}"; then
      add_row PURGED "${gen}" "${store}" INCOMING_AGE
    else
      add_row PURGE_FAILED "${gen}" "${store}" DELETE_FAILED
      purge_problem=true
    fi
  done

  # generations/: 지울 것 고르기. 29일 초과(예외 없음) → 수량 → bytes(둘 다 마지막 성공은 남긴다), 오래된 것부터.
  table="$(gen_table "${listing}")"
  count="$(printf '%s\n' "${table}" | awk 'NF' | wc -l | tr -d ' ')"
  total="$(printf '%s\n' "${table}" | awk '{ s += $2 } END { printf "%.0f", s }')"
  doomed=''
  for gen in $(printf '%s\n' "${table}" | awk '{ print $1 }'); do
    age_s=$((now - $(epoch_of_generation "${gen}")))
    if [ "${age_s}" -gt "${purge_after_seconds}" ]; then
      doomed="${doomed} ${gen}=AGE"
      count=$((count - 1))
      total=$((total - $(field_of "${table}" "${gen}" 2)))
    fi
  done
  if [ "${last_success_known}" = true ]; then
    for gen in $(printf '%s\n' "${table}" | awk '{ print $1 }'); do
      case "${doomed} " in *" ${gen}="*) continue ;; esac
      [ "${gen}" = "${last_success}" ] && continue
      if [ "${count}" -gt "${max_generations}" ]; then
        doomed="${doomed} ${gen}=COUNT"
      elif [ "${total}" -gt "${max_bytes}" ]; then
        doomed="${doomed} ${gen}=BYTES"
      else
        continue
      fi
      count=$((count - 1))
      total=$((total - $(field_of "${table}" "${gen}" 2)))
    done
  fi

  for entry in ${doomed}; do
    gen="${entry%%=*}"
    reason="${entry#*=}"
    gen_bytes="$(field_of "${table}" "${gen}" 2)"
    gen_files="$(field_of "${table}" "${gen}" 3)"
    gen_key="$(field_of "${table}" "${gen}" 4)"
    if delete_generation "${store}" generations "${gen}"; then
      say "    ${store} ${gen} 파기(${reason})"
      add_row PURGED "${gen}" "${store}" "${reason}" "${gen_bytes}" "${gen_files}" '' '' "${gen_key}"
    else
      say "    ${store} ${gen} 파기 실패(${reason})"
      add_row PURGE_FAILED "${gen}" "${store}" DELETE_FAILED '' "${gen_files}" '' '' "${gen_key}"
      purge_problem=true
    fi
  done

  # 파기 뒤의 저장소 목록으로 기한 초과와 사용량을 판정한다(지우지 못한 것이 남았는가).
  if ! incoming="$(list_files "${store}" incoming)" || ! listing="$(list_files "${store}" generations)"; then
    add_row PURGE_FAILED "${generation}" "${store}" LIST_FAILED
    purge_problem=true
    continue
  fi
  for prefix in incoming generations; do
    if [ "${prefix}" = incoming ]; then
      after="$(gen_table "${incoming}")"
    else
      after="$(gen_table "${listing}")"
    fi
    for gen in $(printf '%s\n' "${after}" | awk '{ print $1 }'); do
      age_s=$((now - $(epoch_of_generation "${gen}")))
      if [ "${age_s}" -ge "${overdue_after_seconds}" ]; then
        say "    ${store} ${prefix}/${gen} 기한 초과(30일)"
        add_row PURGE_OVERDUE "${gen}" "${store}" "$(printf '%s' "${prefix}" | tr 'a-z' 'A-Z')"
        store_overdue=true
        purge_problem=true
      fi
    done
  done
  table="$(gen_table "${listing}")"
  remaining_count="$(printf '%s\n' "${table}" | awk 'NF' | wc -l | tr -d ' ')"
  store_bytes="$(printf '%s\n%s\n' "${listing}" "${incoming}" | awk -F'|' 'NF == 2 && $1 ~ /^[0-9]+$/ { s += $1 }
    END { printf "%.0f", s }')"
  measured_code=''
  [ "${store_overdue}" = true ] && measured_code=PURGE_OVERDUE
  add_row MEASURED "${generation}" "${store}" "${measured_code}" "${store_bytes}" "${remaining_count}" "${quota}"
  say "    ${store} 측정: ${store_bytes} / ${quota} bytes, generation ${remaining_count}개"
done

# --------------------------------------------------------------------------
# 13. 파기·측정 기록과 판정
# --------------------------------------------------------------------------

purge_rows="${rows}"
rows=''
if [ "${db_ok}" = true ]; then
  record_rows "${purge_rows}" || records_ok=false
else
  queue_pending "${purge_rows}"
  records_ok=false
fi

if [ "${records_ok}" = false ]; then
  say "::error::백업 ${generation}: 기록하지 못한 행이 대기열에 있다(${db_code:-RECORD_FAILED}). 기록 없는 결과는 성공이 아니다 — 다음 실행이 먼저 기록한다."
  exit 1
fi
if [ "${command_name}" = run ] && [ "${outcome}" != SUCCEEDED ]; then
  say "::error::백업 ${generation}: FAILED ${fail_code}"
  exit 1
fi
if [ "${purge_problem}" = true ]; then
  say "::error::백업 ${generation}: 파기에 문제가 있다(PURGE_FAILED·PURGE_OVERDUE, 감사 원장 참조)."
  exit 1
fi
say "백업 ${command_name} 완료: ${generation}"
exit 0
