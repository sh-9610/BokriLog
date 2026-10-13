#!/usr/bin/env bash
# 삭제 tombstone의 외부 사본(Story 3.10 AC-4).
#
# 실행: deploy/production/tombstone-export.sh [--rehearsal]
#   15분마다(systemd timer `bokrilog-tombstone-export.timer`). 순서는 다음과 같다.
#   1. 배포·백업과 같은 잠금(`$BOKRILOG_STATE_DIR/.lock`, kind `tombstone`)을 쥔다. 배포·백업·복원이 쥐고 있으면 **조용히 건너뛴다**(종료 0과
#      한 줄 — 다음 주기가 다시 돈다. 백업은 이 내보내기가 쥔 잠금을 5분까지 기다린다). 소유자가 죽은 잠금(`LOCK_STALE`)은 종료 1이다.
#      앞선 실행의 임시본을 지운다.
#   2. 일회성 `tombstone-export` 역할이 기한(+35일) 지난 outbox 행을 지우고, 파기 기한이 지금 + 24시간보다 뒤인 행 **전체**를 권한 700 임시
#      디렉터리의 파일 하나로 쓴다(매번 전체 — "보냈다" 표시가 없어 재시도해도 결과가 같다). 파일 머리에 digest 키 ID와 만든 시각이 있다.
#   3. **줄어듦 검사.** `latest`가 가리키는 앞선 사본을 받아 풀고, 그 사본에서 기한이 지금 + 25시간보다 뒤인 digest가 새 파일에 모두 있는지
#      본다. 하나라도 없으면 `TOMBSTONE_SHRANK`이고 latest를 바꾸지 않는다(복원된 DB·잘못된 DB가 사본을 덮어 tombstone을 잃지 않게). 앞선
#      사본을 풀 수 없거나(`PREVIOUS_UNREADABLE`) 키 ID가 다르면(`TOMBSTONE_KEY_CHANGED`) 역시 멈춘다.
#   4. 그 파일을 tombstone 전용 age recipient(`recipient.pub`, backup key와 다른 key)로 암호화하고 평문을 바로 지운다.
#   5. OCI 밖 저장소(`backup.env`의 offsite_remote)의 `tombstones/<UTC YYYYMMDDTHHMMSSZ>.age`에 올리고 원격 SHA-256을 대조한 뒤에만
#      `tombstones/latest`(그 object 이름 한 줄)를 갱신한다.
#   6. 이름의 시각으로 24시간을 넘긴 사본 object를 지운다(새 사본은 남긴다).
#
# 외부 수명. 사본은 24시간만 남고 각 사본은 기한이 24시간보다 더 남은 행만 실으므로, 어느 tombstone도 외부에서 `requested_at + 35일`을
# 넘겨 남지 않는다. tombstone은 요청 뒤 34일까지 사본에 실린다 — 복원은 30일을 넘긴 generation을 받지 않으므로 그 계정을 되살릴 수
# 있는 복원은 언제나 그 tombstone을 본다(`DeletionTombstoneApi`). 남는 틈은 내보내기 주기(15분)다 — 그 사이의 삭제는 다음 실행이
# 싣는다. 복원(`restore.sh`)은 `latest` 하나만 읽고, 그 사본이 장애 시각보다 1시간 넘게 앞서면 거부한다.
#
# 설정(이름만 여기 있다)
#   BOKRILOG_TOMBSTONE_KEYS_DIR  tombstone age key 디렉터리(기본 /etc/bokrilog/tombstone, 700): `recipient.pub`(공개 값, 첫 `age1…` 줄)과
#                                `identity.key`(600 — 줄어듦 검사가 앞선 사본을 푼다, 복원도 읽는다). 앱 key ring·감사·tombstone digest
#                                키·backup key와 섞지 않는다.
#   BOKRILOG_TOMBSTONE_DIR       작업 디렉터리(기본 /var/lib/bokrilog/tombstone, 700): `tmp/`(평문 임시본)·`logs/`.
#   BOKRILOG_BACKUP_KEYS_DIR·BOKRILOG_BACKUP_CONFIG·BOKRILOG_BACKUP_TOOLS   백업과 같다(rclone.conf·offsite_remote·age·rclone).
#   BOKRILOG_STATE_DIR·BOKRILOG_SECRETS_FILE·BOKRILOG_COMPOSE_OVERRIDE·BOKRILOG_PROJECT   백업과 같다.
#
# 평문 tombstone(digest·요청 ID·시각)은 임시 디렉터리에만 두고 암호화 직후, 그리고 어느 실패·신호에서든 지운다. 출력에는 수만 있다.
#
# 종료 코드
#   0 -- 내보내기·업로드·latest 갱신·파기 완료, 또는 다른 일이 잠금을 쥐어 건너뜀
#   1 -- 단계 실패(역할·줄어듦·암호화·업로드·대조·파기)·죽은 잠금·중단
#   2 -- 준비 실패(인자·설정·도구·key 파일·권한·모드)

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib.sh
. "${script_dir}/lib.sh"
# shellcheck source=backup-lib.sh
. "${script_dir}/backup-lib.sh"

compose_file="${script_dir}/compose.yaml"
keys_dir="${BOKRILOG_BACKUP_KEYS_DIR:-/etc/bokrilog/backup}"
config_file="${BOKRILOG_BACKUP_CONFIG:-${keys_dir}/backup.env}"
tools_dir="${BOKRILOG_BACKUP_TOOLS:-/opt/bokrilog/backup-tools}"
tombstone_keys_dir="${BOKRILOG_TOMBSTONE_KEYS_DIR:-/etc/bokrilog/tombstone}"
tombstone_dir="${BOKRILOG_TOMBSTONE_DIR:-/var/lib/bokrilog/tombstone}"
state_dir="${BOKRILOG_STATE_DIR:-/var/lib/bokrilog/deploy}"
secrets_file="${BOKRILOG_SECRETS_FILE:-/etc/bokrilog/production.env}"
override_file="${BOKRILOG_COMPOSE_OVERRIDE:-}"
project="${BOKRILOG_PROJECT:-bokrilog-production}"

# 사본 object의 수명(DELETION-TOMBSTONE-1.0의 EXPORT_MARGIN과 같다). 이름의 시각으로 이만큼 지나면 지운다. 설정으로 열지 않는다.
retention_seconds=86400
# 줄어듦 검사의 여유: 내보내기 역할(DB 시각)과 이 호스트 시계의 차이를 덮는다.
shrink_slack_seconds=3600
step_limit=600
role_limit=300

tombstone_header='# bokrilog deletion tombstones DELETION-TOMBSTONE-1.0 key='

prep_fail() {
  printf '::error::tombstone 내보내기 준비 실패: %s\n' "$1" >&2
  exit 2
}

say() {
  printf '%s\n' "$*"
}

rehearsal=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --rehearsal) rehearsal=true; shift ;;
    *) prep_fail "알 수 없는 인자: $1" ;;
  esac
done

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
fi
matches "${project}" 'bokrilog-[a-z][a-z0-9-]{0,39}' || prep_fail 'BOKRILOG_PROJECT는 bokrilog-<소문자·숫자·->여야 한다.'

[ -f "${config_file}" ] || prep_fail "설정 파일이 없다: ${config_file}"
offsite_remote="$(kv_get "${config_file}" offsite_remote)"
matches "${offsite_remote}" '[A-Za-z0-9_-]+:[A-Za-z0-9._/-]+' || prep_fail 'offsite_remote는 <remote>:<bucket[/경로]>여야 한다.'
case "${offsite_remote}" in
  */ | *//* | *:/*) prep_fail 'offsite_remote의 경로 모양이 틀렸다(끝의 /, //, /로 시작하는 bucket).' ;;
esac

remote_of() {
  printf '%s' "${offsite_remote}"
}

probe_posix_modes "${rehearsal}"
rclone_conf="${keys_dir}/rclone.conf"
[ -f "${rclone_conf}" ] || prep_fail "저장소 설정이 없다: ${rclone_conf}"
private_file "${rclone_conf}" || prep_fail 'rclone.conf 권한이 600이 아니다.'
recipient_file="${tombstone_keys_dir}/recipient.pub"
[ -d "${tombstone_keys_dir}" ] || prep_fail "tombstone key 디렉터리가 없다: ${tombstone_keys_dir}"
private_dir "${tombstone_keys_dir}" || prep_fail "${tombstone_keys_dir} 권한이 700이 아니다."
[ -f "${recipient_file}" ] || prep_fail "tombstone recipient가 없다: ${recipient_file}"
not_shared_writable "${recipient_file}" || prep_fail 'recipient.pub을 group·other가 쓸 수 있다.'
[ "$(grep -cE '^age1[0-9a-z]+$' "${recipient_file}" || true)" = 1 ] || prep_fail 'recipient.pub에는 age recipient가 정확히 하나여야 한다.'
# backup key와 같은 key를 쓰지 않는다 — tombstone은 backup과 다른 사람·다른 수명의 비밀이다.
for backup_recipient in "${keys_dir}"/recipients/*.pub; do
  [ -f "${backup_recipient}" ] || continue
  if [ "$(grep -E '^age1[0-9a-z]+$' "${backup_recipient}" | head -1)" = "$(grep -E '^age1[0-9a-z]+$' "${recipient_file}")" ]; then
    prep_fail 'tombstone recipient가 backup key와 같다(다른 key여야 한다).'
  fi
done
identity_file="${tombstone_keys_dir}/identity.key"
[ -s "${identity_file}" ] || prep_fail "tombstone identity가 없다(줄어듦 검사가 앞선 사본을 푼다): ${identity_file}"
private_file "${identity_file}" || prep_fail 'tombstone identity 권한이 600이 아니다.'

bash "${script_dir}/install-backup-tools.sh" --check --prefix "${tools_dir}" >/dev/null 2>&1 \
  || prep_fail "age·rclone이 고정 버전으로 설치돼 있지 않다(${tools_dir}). install-backup-tools.sh를 먼저 돈다."
age_bin="$(tool age)"
rclone_bin="$(tool rclone)"
have_sha256_tool || prep_fail 'sha256sum 또는 shasum이 없다.'
[ -f "${secrets_file}" ] || prep_fail "비밀 파일이 없다: ${secrets_file}"
command -v docker >/dev/null 2>&1 || prep_fail 'docker가 없다.'
docker compose version >/dev/null 2>&1 || prep_fail 'docker compose v2를 쓸 수 없다.'
[ -f "${state_dir}/deploy.state" ] || prep_fail "배포 상태가 없다: ${state_dir}/deploy.state"
image="$(kv_get "${state_dir}/deploy.state" active_image)"
[ -n "${image}" ] || prep_fail '배포 상태에 활성 이미지가 없다.'

# --------------------------------------------------------------------------
# 잠금·작업 디렉터리
# --------------------------------------------------------------------------

now_name="$(date -u +%Y%m%dT%H%M%SZ)"
now_epoch="$(date -u +%s)"
operation="tombstone_${now_name}_$(random_hex 4)"
lock_dir="${state_dir}/.lock"
if ! lock_acquire "${lock_dir}" tombstone "${operation}"; then
  case "${lock_state}" in
    *_IN_PROGRESS)
      # 배포·백업·복원이 돈다. 15분 뒤 다음 주기가 다시 돈다 — 실패로 남기지 않는다(그 틈은 주기 하나다).
      say "tombstone 내보내기 건너뜀: ${lock_state} (${lock_owner}). 다음 주기가 다시 돈다."
      exit 0
      ;;
  esac
  say "::error::tombstone 내보내기: ${lock_state} (${lock_owner}). 진행 중인 일이 정말 없는지 확인한 뒤 잠금을 손으로 지운다."
  exit 1
fi

umask 077
mkdir -p "${tombstone_dir}/tmp" "${tombstone_dir}/logs" || { lock_release "${lock_dir}"; prep_fail "작업 디렉터리를 만들지 못했다: ${tombstone_dir}"; }
chmod 700 "${tombstone_dir}" "${tombstone_dir}/tmp" "${tombstone_dir}/logs" 2>/dev/null
for entry in "${tombstone_dir}"/tmp/* "${tombstone_dir}"/tmp/.[!.]*; do
  [ -e "${entry}" ] || continue
  rm -rf "${entry}"
done
find "${tombstone_dir}/logs" -type f -name 'tombstone_*.log' -mtime +7 -exec rm -f {} + 2>/dev/null
log_file="${tombstone_dir}/logs/${operation}.log"
: >"${log_file}"

work=''
cleanup() {
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
  say '::error::tombstone 내보내기가 신호로 중단됐다. 임시본을 지웠다.'
  exit 1
}
trap on_signal INT HUP TERM
set -m

work="$(mktemp -d "${tombstone_dir}/tmp/run.XXXXXX")" || { say '::error::임시 디렉터리를 만들지 못했다.'; exit 1; }
chmod 700 "${work}"
io_dir="${work}/io"
mkdir -p "${io_dir}" && chmod 700 "${io_dir}"

fail() {
  say "::error::tombstone 내보내기 실패: $1"
  exit 1
}

compose() {
  local args
  args=(--project-name "${project}" --env-file "${secrets_file}" --file "${compose_file}")
  if [ -n "${override_file}" ]; then
    args+=(--file "${override_file}")
  fi
  BOKRILOG_STATE_DIR="${state_dir}" BOKRILOG_ENV="${BOKRILOG_ENV:-production}" BOKRILOG_ROLE_IO_DIR="${io_dir}" \
    BOKRILOG_IMAGE="${image}" BOKRILOG_AUDIT_IMAGE="${image}" BOKRILOG_BLUE_IMAGE="${image}" \
    BOKRILOG_GREEN_IMAGE="${image}" BOKRILOG_WORKER_IMAGE="${image}" \
    docker compose "${args[@]}" "$@"
}

say "tombstone export ${now_name} (operation ${operation})"

# --------------------------------------------------------------------------
# 2. 내보내기 역할
# --------------------------------------------------------------------------

guarded "${role_limit}" compose --profile tombstone-export run --rm --no-deps -T --user "$(id -u):$(id -g)" tombstone-export \
  </dev/null >>"${log_file}" 2>&1 || fail 'EXPORT_FAILED (내보내기 역할이 실패했다)'
plain="${io_dir}/tombstones.txt"
header="$(head -1 "${plain}" 2>/dev/null | tr -d '\r')"
case "${header}" in
  "${tombstone_header}"*) ;;
  *) fail 'EXPORT_FAILED (내보낸 파일이 없거나 머리가 없다)' ;;
esac
key_id="$(printf '%s' "${header#"${tombstone_header}"}" | awk '{ print $1 }')"
matches "${key_id}" '[0-9a-f]{16}' || fail 'EXPORT_FAILED (내보낸 파일 머리에 키 ID가 없다)'
count="$(grep -cvE '^(#|[[:space:]]*$)' "${plain}" || true)"

# --------------------------------------------------------------------------
# 3. 줄어듦 검사(앞선 사본의 살아 있는 digest가 새 파일에 모두 있어야 한다)
# --------------------------------------------------------------------------

previous="$(rclone_run cat "${offsite_remote}/tombstones/latest" 2>>"${log_file}" | tr -d '\r\n')"
if [ -n "${previous}" ]; then
  matches "${previous}" '[0-9]{8}T[0-9]{6}Z\.age' || fail 'PREVIOUS_UNREADABLE (latest의 모양이 틀렸다 — latest를 바꾸지 않는다)'
  rclone_run copyto "${offsite_remote}/tombstones/${previous}" "${work}/previous.age" >>"${log_file}" 2>&1 \
    || fail "PREVIOUS_UNREADABLE (${previous}을 받지 못했다 — latest를 바꾸지 않는다)"
  guarded "${step_limit}" "${age_bin}" -d -i "${identity_file}" -o "${work}/previous.txt" "${work}/previous.age" \
    2>>"${log_file}" || { rm -f "${work}/previous.txt"; fail 'PREVIOUS_UNREADABLE (앞선 사본을 풀지 못했다 — latest를 바꾸지 않는다)'; }
  rm -f "${work}/previous.age"
  previous_header="$(head -1 "${work}/previous.txt" | tr -d '\r')"
  case "${previous_header}" in
    "${tombstone_header}"*) ;;
    *) rm -f "${work}/previous.txt"; fail 'PREVIOUS_UNREADABLE (앞선 사본의 머리가 없다 — latest를 바꾸지 않는다)' ;;
  esac
  previous_key="$(printf '%s' "${previous_header#"${tombstone_header}"}" | awk '{ print $1 }')"
  if [ "${previous_key}" != "${key_id}" ]; then
    rm -f "${work}/previous.txt"
    fail 'TOMBSTONE_KEY_CHANGED (앞선 사본과 digest 키 ID가 다르다 — 키 회전은 아직 지원하지 않는다, latest를 바꾸지 않는다)'
  fi
  required_after=$((now_epoch + retention_seconds + shrink_slack_seconds))
  lost=0
  while read -r digest request requested destroy; do
    case "${digest}" in '#'* | '') continue ;; esac
    destroy_epoch="$(epoch_of_iso "${destroy}")"
    [ -n "${destroy_epoch}" ] && [ "${destroy_epoch}" -gt "${required_after}" ] || continue
    awk -v d="${digest}" '$1 == d { found = 1 } END { exit !found }' "${plain}" || lost=$((lost + 1))
  done <"${work}/previous.txt"
  rm -f "${work}/previous.txt"
  if [ "${lost}" -gt 0 ]; then
    rm -f "${plain}"
    fail "TOMBSTONE_SHRANK (앞선 사본 ${previous}의 살아 있는 tombstone ${lost}개가 새 파일에 없다 — latest를 바꾸지 않는다)"
  fi
  say "  줄어듦 검사 통과(앞선 사본 ${previous})"
fi

# --------------------------------------------------------------------------
# 4. 암호화
# --------------------------------------------------------------------------

object="${now_name}.age"
cipher="${work}/${object}"
guarded "${step_limit}" "${age_bin}" -R "${recipient_file}" -o "${cipher}" "${plain}" 2>>"${log_file}"
status=$?
rm -f "${plain}"
[ "${status}" -eq 0 ] && [ -s "${cipher}" ] || fail 'ENCRYPT_FAILED'
cipher_sha="$(sha256_of "${cipher}")"

# --------------------------------------------------------------------------
# 5. 업로드·대조·latest
# --------------------------------------------------------------------------

rclone_run copyto "${cipher}" "${offsite_remote}/tombstones/${object}" >>"${log_file}" 2>&1 || fail 'UPLOAD_FAILED'
remote_sha="$(rclone_run hashsum sha256 --download "${offsite_remote}/tombstones/${object}" 2>>"${log_file}" \
  | awk 'NR == 1 { print $1 }')"
[ -n "${cipher_sha}" ] && [ "${remote_sha}" = "${cipher_sha}" ] || fail 'CHECKSUM_FAILED (올린 사본이 다르다 — latest를 바꾸지 않는다)'
printf '%s\n' "${object}" >"${work}/latest"
rclone_run copyto "${work}/latest" "${offsite_remote}/tombstones/latest" >>"${log_file}" 2>&1 || fail 'LATEST_FAILED'
[ "$(rclone_run cat "${offsite_remote}/tombstones/latest" 2>>"${log_file}" | tr -d '\r\n')" = "${object}" ] \
  || fail 'LATEST_FAILED (latest가 새 사본을 가리키지 않는다)'
say "  사본 ${object}, tombstone ${count}개, latest 갱신"

# --------------------------------------------------------------------------
# 6. 24시간 지난 사본 파기
# --------------------------------------------------------------------------

listing="$(list_files offsite tombstones)" || fail 'PURGE_FAILED (tombstones/를 읽지 못했다)'
purged=0
purge_failed=0
while IFS='|' read -r size name; do
  [ -n "${name}" ] || continue
  matches "${name}" '[0-9]{8}T[0-9]{6}Z\.age' || continue
  [ "${name}" = "${object}" ] && continue
  stamp="${name%.age}"
  object_epoch="$(epoch_of_iso "${stamp:0:4}-${stamp:4:2}-${stamp:6:2}T${stamp:9:2}:${stamp:11:2}:${stamp:13:2}Z")"
  [ -n "${object_epoch}" ] || continue
  [ $((now_epoch - object_epoch)) -gt "${retention_seconds}" ] || continue
  if rclone_run deletefile "${offsite_remote}/tombstones/${name}" >>"${log_file}" 2>&1; then
    purged=$((purged + 1))
  else
    purge_failed=$((purge_failed + 1))
  fi
done <<LIST
${listing}
LIST
say "  파기 ${purged}개(24시간 초과)"
[ "${purge_failed}" -eq 0 ] || fail "PURGE_FAILED (24시간을 넘긴 사본 ${purge_failed}개를 지우지 못했다)"
say "tombstone 내보내기 완료: ${object}"
exit 0
