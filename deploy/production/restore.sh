#!/usr/bin/env bash
# 격리 복원과 삭제 계정 재활성화 차단(Story 3.10 AC-2~AC-5).
#
# 실행
#   deploy/production/restore.sh --generation <gen_YYYYMMDD|latest> --failure-at <ISO-8601> [--project bokrilog-restore-<x>]
#                                [--image <repository@sha256:…>] [--tombstone-max-lag <초>] [--allow-stale-tombstones]
#                                [--keep-on-failure] [--rpo-only] [--rehearsal]
#
#   --generation  OCI 밖 저장소 `generations/`의 generation 하나(`latest`는 가장 최근 것). OCI 저장소는 보지 않는다 — OCI 계정 장애에서도
#                 복원할 수 있어야 한다. 30일을 넘긴 generation은 받지 않는다(`GENERATION_TOO_OLD` — tombstone 안전 창 밖이다).
#   --failure-at  장애 기준 시각(`2026-10-13T09:00:00Z`·`…+09:00`). RPO = 이 시각 − generation의 dump 시작 시각.
#   --project     격리 compose 프로젝트 이름(기본 `bokrilog-restore-<UTC 시각>`). `bokrilog-restore-` 접두사여야 하고, 운영 프로젝트
#                 (`BOKRILOG_PROJECT`, 기본 `bokrilog-production`)와 같거나 이미 컨테이너·volume·망이 있는 프로젝트면 거부한다.
#   --image       복원에 쓸 앱 이미지(`<repository>@sha256:<64hex>`). 호스트를 잃어 배포 상태(`deploy.state`)가 없을 때 manifest의 `image`를
#                 준다. 주지 않으면 배포 상태의 활성 이미지다.
#   --tombstone-max-lag  외부 tombstone 사본(`tombstones/latest`)이 장애 시각보다 이만큼(초, 기본 3600) 넘게 앞서면 거부한다
#                 (`TOMBSTONES_STALE`). `--allow-stale-tombstones`는 경고를 남기고 계속한다(보고에 그 사실이 실린다).
#   --keep-on-failure  3단계 뒤의 실패에서도 격리 프로젝트를 지우지 않는다(조사용, 경고와 지우는 명령을 출력한다).
#   --rpo-only    저장소 목록과 checksum 파일만 보고 RPO를 잰다(내려받지도 복원하지도 않는다).
#
# 순서(같은 호스트, 빈 격리 프로젝트)
#   0. 배포·백업과 같은 잠금(`$BOKRILOG_STATE_DIR/.lock`, kind `restore`)을 쥔다. 앞선 실행의 임시본을 지운다.
#   1. offsite `tombstones/latest`의 이름(사본 시각)이 장애 시각 − max lag보다 뒤인지 본다(없으면 `TOMBSTONES_UNAVAILABLE`, 오래면
#      `TOMBSTONES_STALE` — 아무것도 띄우지 않는다). 고른 generation의 암호문·checksum을 권한 700 임시 디렉터리에 내려받아 SHA-256을
#      대조한다(`CHECKSUM_MISMATCH`면 아무것도 띄우지 않는다).
#   2. 이름의 key ID로 backup identity(`identities/<key_id>.key`)를 골라 복호화한다(`KEY_UNAVAILABLE`·`DECRYPT_FAILED`). 짝 recipient가
#      있으면 대조하고, 없으면 경고만 한다(호스트를 잃고 identity 사본만 남은 경우).
#   3. 새 프로젝트의 빈 PostgreSQL(새 volume)에 `pg_restore --single-transaction --exit-on-error`로 복원하고 평문을 바로 지운다.
#   4. migrate 역할(AD-12)을 돈다.
#   5. 1의 사본을 받아 tombstone identity로 복호화한다(`TOMBSTONES_UNAVAILABLE` — tombstone 없이는 서비스를 열지 않는다).
#   6. `restore-apply` 역할(격리 표지 `BOKRILOG_RESTORE_ISOLATED_PROJECT`): 사본의 키 ID와 digest를 복원된 outbox·계정과 대조하고, 맞은
#      계정을 다시 지우고, 세션을 모두 폐기하고, 불변식·자격정보 표본 복호를 센다(`APPLY_FAILED`).
#   7. 그다음에만 `api-blue`·`worker`를 띄운다. nginx는 띄우지 않고 공개 포트도 없다(있으면 API·Worker를 멈추고 `ISOLATION_BROKEN`).
#   8. RPO를 판정한다 — 24시간 이하면 통과, 넘으면 `RPO_EXCEEDED`(종료 1, 복원된 프로젝트는 그대로 둔다).
#   3단계 뒤의 실패는 격리 프로젝트를 volume까지 지운다(되살린 데이터를 남기지 않는다) — `--keep-on-failure`면 남긴다.
#
# 격리. 로그인 제공자·Bybit·공개 base URL·운영자 값은 비운 채로 띄운다(fail-closed — 외부 호출 0). 메트릭 공통 tag `env`는 `restore`다
# (운영 경보의 selector는 `env="production"`). 복원된 프로젝트는 손으로 지운다:
# `docker compose --project-name <project> --file deploy/production/compose.yaml down --volumes`. 격리 프로젝트가 도는 동안에는 배포가
# 시작되지 않는다(`deploy.sh` — 메모리 peak).
#
# 생성 시각은 checksum 파일 둘째 줄의 dump 시작 시각(`dump_started_at=`, `backup.sh`)이고, 없으면(그 줄 이전의 generation) generation
# 날짜의 00:00 UTC다(보수적 — RPO가 실제보다 길게 나온다).
#
# 설정(이름만 여기 있고 값은 운영자가 준다 — 백업과 같은 자리)
#   BOKRILOG_BACKUP_KEYS_DIR   backup identity(`identities/`)·`rclone.conf`·`backup.env`(offsite_remote) — 백업과 같다.
#   BOKRILOG_BACKUP_CONFIG     backup.env 경로(기본 `$BOKRILOG_BACKUP_KEYS_DIR/backup.env`).
#   BOKRILOG_BACKUP_TOOLS      age·rclone 설치 디렉터리(`install-backup-tools.sh`).
#   BOKRILOG_TOMBSTONE_KEYS_DIR  tombstone age key 디렉터리(기본 /etc/bokrilog/tombstone, 700): `identity.key`(600). backup key와 다르다.
#   BOKRILOG_RESTORE_DIR       작업 디렉터리(기본 /var/lib/bokrilog/restore, 700): `tmp/`(평문 dump·tombstone 임시본)·`logs/`(도구 출력).
#   BOKRILOG_STATE_DIR·BOKRILOG_SECRETS_FILE·BOKRILOG_COMPOSE_OVERRIDE·BOKRILOG_PROJECT   백업과 같다.
#
# 평문(dump·tombstone)은 권한 700 임시 디렉터리에만 두고 쓰자마자, 그리고 어느 실패·신호에서든 trap으로 지운다. 이 스크립트는 key·
# 자격정보 값을 읽지 않고 경로만 도구에 넘긴다. 출력에는 수와 code만 있다.
#
# **이 실행은 훈련 증거 원장에 기록되지 않는다**(deferred-work.md). 실제 별도 환경 복원 전까지 복원은 **실제 복원 미검증**이다.
#
# 종료 코드
#   0 -- 복원·적용·검증·서비스 기동이 모두 성공했고 RPO가 24시간 이하다(--rpo-only: RPO 통과)
#   1 -- 단계 실패(TOMBSTONES_STALE·GENERATION_TOO_OLD·CHECKSUM_MISMATCH·KEY_UNAVAILABLE·TOMBSTONES_UNAVAILABLE·APPLY_FAILED …)·
#        RPO_EXCEEDED·잠금·중단
#   2 -- 준비 실패(인자·프로젝트 이름·이미지·설정·도구·key 파일·권한·모드)

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
restore_dir="${BOKRILOG_RESTORE_DIR:-/var/lib/bokrilog/restore}"
state_dir="${BOKRILOG_STATE_DIR:-/var/lib/bokrilog/deploy}"
secrets_file="${BOKRILOG_SECRETS_FILE:-/etc/bokrilog/production.env}"
override_file="${BOKRILOG_COMPOSE_OVERRIDE:-}"
production_project="${BOKRILOG_PROJECT:-bokrilog-production}"

# RPO 목표(공개 베타, PORTFOLIO-SOLUTION-DESIGN)와 generation 나이 상한(tombstone 안전 창 — DeletionTombstoneApi). 설정으로 열지 않는다.
rpo_limit_seconds=86400
max_generation_age_seconds=$((30 * 86400))
step_limit=3600
role_limit=600
query_limit=60

tombstone_header='# bokrilog deletion tombstones DELETION-TOMBSTONE-1.0 key='

prep_fail() {
  printf '::error::복원 준비 실패: %s\n' "$1" >&2
  exit 2
}

say() {
  printf '%s\n' "$*"
}

# --------------------------------------------------------------------------
# 인자·모드
# --------------------------------------------------------------------------

generation_arg=''
failure_at=''
project=''
image_arg=''
tombstone_max_lag=3600
allow_stale=false
keep_on_failure=false
rpo_only=false
rehearsal=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --generation) [ "$#" -ge 2 ] || prep_fail '--generation에 값이 없다.'; generation_arg="$2"; shift 2 ;;
    --failure-at) [ "$#" -ge 2 ] || prep_fail '--failure-at에 값이 없다.'; failure_at="$2"; shift 2 ;;
    --project) [ "$#" -ge 2 ] || prep_fail '--project에 값이 없다.'; project="$2"; shift 2 ;;
    --image) [ "$#" -ge 2 ] || prep_fail '--image에 값이 없다.'; image_arg="$2"; shift 2 ;;
    --tombstone-max-lag) [ "$#" -ge 2 ] || prep_fail '--tombstone-max-lag에 값이 없다.'; tombstone_max_lag="$2"; shift 2 ;;
    --allow-stale-tombstones) allow_stale=true; shift ;;
    --keep-on-failure) keep_on_failure=true; shift ;;
    --rpo-only) rpo_only=true; shift ;;
    --rehearsal) rehearsal=true; shift ;;
    *) prep_fail "알 수 없는 인자: $1" ;;
  esac
done
[ -n "${generation_arg}" ] || prep_fail '--generation <gen_YYYYMMDD|latest>이 필요하다.'
[ -n "${failure_at}" ] || prep_fail '--failure-at <ISO-8601>이 필요하다.'
case "${generation_arg}" in
  latest) ;;
  *) matches "${generation_arg}" 'gen_[0-9]{8}' || prep_fail '--generation은 gen_YYYYMMDD 또는 latest다.' ;;
esac
failure_epoch="$(epoch_of_iso "${failure_at}")"
[ -n "${failure_epoch}" ] || prep_fail '--failure-at은 시간대가 붙은 ISO-8601 시각이다(예: 2026-10-13T09:00:00Z).'
matches "${tombstone_max_lag}" '[0-9]{1,6}' || prep_fail '--tombstone-max-lag는 초(정수)다.'

# 리허설은 그것이 리허설임을 두 가지로 보여야 한다 — 개발·CI 환경 값과 리허설 override(백업과 같은 규칙).
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

# 격리 프로젝트 이름. 운영 프로젝트·volume과 겹치는 이름을 받지 않는다 — compose의 프로젝트 이름이 volume·망 이름을 가른다.
[ -n "${project}" ] || project="bokrilog-restore-$(date -u +%Y%m%dt%H%M%S)"
matches "${project}" 'bokrilog-restore-[a-z0-9][a-z0-9-]{0,30}' \
  || prep_fail "격리 프로젝트 이름은 bokrilog-restore-<소문자·숫자·->여야 한다(운영 프로젝트 위에 복원하지 않는다): ${project}"
[ "${project}" != "${production_project}" ] || prep_fail "격리 프로젝트가 운영 프로젝트(${production_project})와 같다."

# 이미지. 운영은 레지스트리 digest만 받는다. 리허설은 CI가 같은 jar로 만든 로컬 image ID도 받는다(배포 gate와 같은 규칙).
if [ -n "${image_arg}" ]; then
  if ! matches "${image_arg}" '[a-z0-9][a-z0-9._/:-]{0,199}@sha256:[0-9a-f]{64}'; then
    [ "${rehearsal}" = true ] && matches "${image_arg}" 'sha256:[0-9a-f]{64}' \
      || prep_fail '--image는 <repository>@sha256:<64hex>여야 한다(tag만은 받지 않는다).'
  fi
fi

# --------------------------------------------------------------------------
# 설정·도구·key
# --------------------------------------------------------------------------

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
[ -d "${keys_dir}/identities" ] || prep_fail "backup identity 디렉터리가 없다: ${keys_dir}/identities"
private_dir "${keys_dir}/identities" || prep_fail 'identities/ 권한이 700이 아니다.'

bash "${script_dir}/install-backup-tools.sh" --check --prefix "${tools_dir}" >/dev/null 2>&1 \
  || prep_fail "age·rclone이 고정 버전으로 설치돼 있지 않다(${tools_dir}). install-backup-tools.sh를 먼저 돈다."
age_bin="$(tool age)"
rclone_bin="$(tool rclone)"
have_sha256_tool || prep_fail 'sha256sum 또는 shasum이 없다.'

tombstone_identity="${tombstone_keys_dir}/identity.key"
image=''
if [ "${rpo_only}" = false ]; then
  [ -d "${tombstone_keys_dir}" ] || prep_fail "tombstone key 디렉터리가 없다: ${tombstone_keys_dir}"
  private_dir "${tombstone_keys_dir}" || prep_fail "${tombstone_keys_dir} 권한이 700이 아니다."
  [ -s "${tombstone_identity}" ] || prep_fail "tombstone identity가 없다: ${tombstone_identity}"
  private_file "${tombstone_identity}" || prep_fail 'tombstone identity 권한이 600이 아니다.'
  [ -f "${secrets_file}" ] || prep_fail "비밀 파일이 없다: ${secrets_file}"
  command -v docker >/dev/null 2>&1 || prep_fail 'docker가 없다.'
  docker compose version >/dev/null 2>&1 || prep_fail 'docker compose v2를 쓸 수 없다.'
  if [ -n "${image_arg}" ]; then
    image="${image_arg}"
  else
    [ -f "${state_dir}/deploy.state" ] \
      || prep_fail "배포 상태가 없다(복원할 이미지를 모른다 — 호스트를 잃었으면 --image에 manifest의 image를 준다): ${state_dir}/deploy.state"
    image="$(kv_get "${state_dir}/deploy.state" active_image)"
    [ -n "${image}" ] || prep_fail '배포 상태에 활성 이미지가 없다(--image로 준다).'
  fi
  # 빈 격리 프로젝트여야 한다. 컨테이너·volume·망 하나라도 있으면 그 위에 복원하지 않는다.
  for kind in container volume network; do
    if [ "${kind}" = container ]; then
      found="$(docker ps -a --quiet --filter "label=com.docker.compose.project=${project}" 2>/dev/null)"
    else
      found="$(docker "${kind}" ls --quiet --filter "label=com.docker.compose.project=${project}" 2>/dev/null)"
    fi
    [ -z "${found}" ] || prep_fail "격리 프로젝트 ${project}가 비어 있지 않다(${kind}). 빈 새 프로젝트에만 복원한다."
  done
fi

# restore_key_problem <key ID>. identity 하나만 있으면 된다(호스트를 잃고 오프라인 사본만 남은 경우). recipient가 있으면 짝을 대조하고,
# 없으면 경고만 한다.
restore_key_problem() {
  local identity="${keys_dir}/identities/$1.key" recipient="${keys_dir}/recipients/$1.pub" derived wanted
  if [ ! -s "${identity}" ]; then
    printf 'identities/%s.key가 없다' "$1"
  elif ! private_file "${identity}"; then
    printf 'identities/%s.key 권한이 600이 아니다' "$1"
  elif [ -s "${recipient}" ]; then
    derived="$("$(tool age-keygen)" -y "${identity}" 2>/dev/null | tr -d '\r')"
    wanted="$(grep -E '^age1[0-9a-z]+$' "${recipient}" | head -1 | tr -d '\r')"
    if [ -z "${derived}" ] || [ "${derived}" != "${wanted}" ]; then
      printf 'identities/%s.key와 recipients/%s.pub이 짝이 아니다' "$1" "$1"
    fi
  fi
}

# --------------------------------------------------------------------------
# 1. generation 고르기와 RPO
# --------------------------------------------------------------------------

log_file=''
now_epoch="$(date -u +%s)"
say "restore ${generation_arg} → ${project}"

if ! listing="$(list_files offsite generations)"; then
  say '::error::복원 실패: STORE_UNAVAILABLE (offsite generations/를 읽지 못했다)'
  exit 1
fi
names="$(printf '%s\n' "${listing}" | awk -F'|' 'NF == 2 { print $2 }')"
# 암호문과 checksum이 모두 있는 generation만 후보다.
candidates="$(printf '%s\n' "${names}" | grep -E '^gen_[0-9]{8}\.[a-z0-9][a-z0-9_-]{0,31}\.dump\.age$' | while IFS= read -r name; do
  if printf '%s\n' "${names}" | grep -Fx -- "${name}.sha256" >/dev/null; then
    printf '%s\n' "${name}"
  fi
done | sort)"
if [ "${generation_arg}" = latest ]; then
  generation="$(printf '%s\n' "${candidates}" | awk -F. 'NF { g = $1 } END { print g }')"
else
  generation="${generation_arg}"
fi
cipher_name="$(printf '%s\n' "${candidates}" | awk -F. -v g="${generation}" '$1 == g')"
if [ -z "${generation}" ] || [ -z "${cipher_name}" ]; then
  say "::error::복원 실패: GENERATION_NOT_FOUND (${generation_arg}의 암호문·checksum이 offsite generations/에 없다)"
  exit 1
fi
if [ "$(printf '%s\n' "${cipher_name}" | awk 'NF' | wc -l | tr -d ' ')" != 1 ]; then
  say "::error::복원 실패: GENERATION_AMBIGUOUS (${generation}에 key ID가 다른 암호문이 둘 이상이다)"
  exit 1
fi
key_id="$(printf '%s' "${cipher_name}" | awk -F. '{ print $2 }')"

# 생성 시각: checksum 파일 둘째 줄의 dump 시작 시각. 없으면 generation 날짜 00:00 UTC(보수적). object 시각은 쓰지 않는다 — dump보다 늦다.
sum_text="$(rclone_run cat "${offsite_remote}/generations/${cipher_name}.sha256" 2>/dev/null | tr -d '\r')"
started="$(printf '%s\n' "${sum_text}" | sed -n 's/^dump_started_at=\([0-9][0-9]*\)$/\1/p' | head -1)"
if matches "${started:-}" '[0-9]{9,11}'; then
  generation_epoch="${started}"
  generation_source='dump 시작'
else
  generation_epoch="$(epoch_of_generation "${generation}")"
  generation_source='generation 날짜 00:00 UTC'
fi
rpo_seconds=$((failure_epoch - generation_epoch))
[ "${rpo_seconds}" -ge 0 ] || prep_fail "장애 시각이 generation 생성 시각(${generation_epoch})보다 앞선다."
rpo_text="$((rpo_seconds / 3600))h $(((rpo_seconds % 3600) / 60))m $((rpo_seconds % 60))s"
say "  generation ${generation} (key ${key_id}), 생성 시각 ${generation_epoch}(${generation_source}), RPO ${rpo_seconds}초(${rpo_text})"

rpo_verdict() {
  if [ "${rpo_seconds}" -le "${rpo_limit_seconds}" ]; then
    say "  RPO 통과: ${rpo_text} ≤ 24h"
    return 0
  fi
  say "::error::RPO_EXCEEDED: ${rpo_text} > 24h (generation ${generation})"
  return 1
}

if [ "${rpo_only}" = true ]; then
  rpo_verdict
  exit $?
fi

# 30일을 넘긴 generation은 tombstone 안전 창 밖이다 — 그 뒤에 지워진 계정의 tombstone이 외부 사본에서 이미 사라졌을 수 있다.
if [ $((now_epoch - generation_epoch)) -gt "${max_generation_age_seconds}" ]; then
  say "::error::복원 실패: GENERATION_TOO_OLD (${generation}의 생성 시각이 30일보다 오래됐다 — tombstone 안전 창 밖이다)"
  exit 1
fi

# 외부 tombstone 사본의 이름과 시각. 아무것도 받기 전에 본다.
tombstone_name="$(rclone_run cat "${offsite_remote}/tombstones/latest" 2>/dev/null | tr -d '\r\n')"
if ! matches "${tombstone_name}" '[0-9]{8}T[0-9]{6}Z\.age'; then
  say '::error::복원 실패: TOMBSTONES_UNAVAILABLE (tombstones/latest가 없거나 모양이 틀렸다 — tombstone 없이 서비스를 열지 않는다)'
  exit 1
fi
stamp="${tombstone_name%.age}"
tombstone_epoch="$(epoch_of_iso "${stamp:0:4}-${stamp:4:2}-${stamp:6:2}T${stamp:9:2}:${stamp:11:2}:${stamp:13:2}Z")"
stale_accepted=false
if [ -z "${tombstone_epoch}" ] || [ "${tombstone_epoch}" -lt $((failure_epoch - tombstone_max_lag)) ]; then
  if [ "${allow_stale}" = true ]; then
    stale_accepted=true
    say "  경고  TOMBSTONES_STALE: 사본 ${tombstone_name}이 장애 시각보다 ${tombstone_max_lag}초 넘게 앞선다 — --allow-stale-tombstones로 계속한다(그 사이의 삭제는 이 복원에 없다)."
  else
    say "::error::복원 실패: TOMBSTONES_STALE (사본 ${tombstone_name}이 장애 시각보다 ${tombstone_max_lag}초 넘게 앞선다 — 그 사이의 삭제가 빠진다. 내보내기를 고친 뒤 다시 돌거나 --allow-stale-tombstones)"
    exit 1
  fi
fi

# --------------------------------------------------------------------------
# 0. 잠금·작업 디렉터리
# --------------------------------------------------------------------------

operation="restore_$(date -u +%Y%m%dT%H%M%SZ)_$(random_hex 4)"
mkdir -p "${state_dir}" 2>/dev/null
lock_dir="${state_dir}/.lock"
if ! lock_acquire "${lock_dir}" restore "${operation}"; then
  say "::error::복원 실패: ${lock_state} (${lock_owner}). 진행 중인 배포·백업·복원이 끝난 뒤 다시 돈다."
  exit 1
fi

umask 077
mkdir -p "${restore_dir}/tmp" "${restore_dir}/logs" || { lock_release "${lock_dir}"; prep_fail "작업 디렉터리를 만들지 못했다: ${restore_dir}"; }
chmod 700 "${restore_dir}" "${restore_dir}/tmp" "${restore_dir}/logs" 2>/dev/null
# 앞선 실행이 남긴 임시본(평문 포함)을 지운다 — 잠금을 쥐었으므로 도는 다른 복원은 없다.
for entry in "${restore_dir}"/tmp/* "${restore_dir}"/tmp/.[!.]*; do
  [ -e "${entry}" ] || continue
  rm -rf "${entry}" && say '  정리  앞선 실행이 남긴 임시본'
done
find "${restore_dir}/logs" -type f -name 'restore_*.log' -mtime +30 -exec rm -f {} + 2>/dev/null
log_file="${restore_dir}/logs/${operation}.log"
: >"${log_file}"
say "  기록: ${log_file}"

work=''
project_created=false

# 격리 compose. 운영 비밀 파일을 치환 원본으로 쓰되 로그인 제공자·Bybit·공개 base URL·운영자 값은 셸 환경의 빈 값으로 덮는다(셸 환경이
# env 파일보다 앞선다) — 격리 환경에서 외부 호출이 없다. 메트릭 env는 `restore`다.
compose() {
  local args
  args=(--project-name "${project}" --env-file "${secrets_file}" --file "${compose_file}")
  if [ -n "${override_file}" ]; then
    args+=(--file "${override_file}")
  fi
  BOKRILOG_STATE_DIR="${state_dir}" BOKRILOG_ENV=restore BOKRILOG_ROLE_IO_DIR="${io_dir:-/nonexistent/bokrilog-role-io}" \
    BOKRILOG_IMAGE="${image}" BOKRILOG_AUDIT_IMAGE="${image}" BOKRILOG_BLUE_IMAGE="${image}" \
    BOKRILOG_GREEN_IMAGE="${image}" BOKRILOG_WORKER_IMAGE="${image}" \
    BOKRILOG_PUBLIC_BASE_URL= BOKRILOG_OPERATORS= BOKRILOG_BYBIT_BASE_URL= BOKRILOG_BYBIT_RESERVED_IP= \
    BOKRILOG_GOOGLE_CLIENT_ID= BOKRILOG_GOOGLE_CLIENT_SECRET= BOKRILOG_NAVER_CLIENT_ID= BOKRILOG_NAVER_CLIENT_SECRET= \
    BOKRILOG_KAKAO_CLIENT_ID= BOKRILOG_KAKAO_CLIENT_SECRET= \
    docker compose "${args[@]}" "$@"
}

# teardown. 3단계 뒤의 실패에서 되살린 데이터를 남기지 않는다(volume까지). --keep-on-failure면 남기고 지우는 명령을 알린다.
teardown() {
  [ "${project_created}" = true ] || return 0
  if [ "${keep_on_failure}" = true ]; then
    say "  경고  --keep-on-failure: 격리 프로젝트 ${project}(되살린 데이터 포함)를 남겼다. 조사 뒤 반드시 지운다:"
    say "        docker compose --project-name ${project} --file ${compose_file} down --volumes --remove-orphans"
    return 0
  fi
  guarded "${step_limit}" compose down --volumes --remove-orphans </dev/null >>"${log_file}" 2>&1 \
    && say "  정리  격리 프로젝트 ${project}를 volume까지 지웠다" \
    || say "  경고  격리 프로젝트 ${project}를 지우지 못했다: docker compose --project-name ${project} --file ${compose_file} down --volumes"
  project_created=false
}

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
  say '::error::복원 실패: INTERRUPTED (임시본을 지웠다)'
  teardown
  exit 1
}
trap on_signal INT HUP TERM
set -m

work="$(mktemp -d "${restore_dir}/tmp/run.XXXXXX")" || { say '::error::임시 디렉터리를 만들지 못했다.'; exit 1; }
chmod 700 "${work}"
io_dir="${work}/io"
mkdir -p "${io_dir}" && chmod 700 "${io_dir}"

fail() {
  say "::error::복원 실패: $1"
  [ -n "${work}" ] && rm -rf "${work}"
  work=''
  teardown
  exit 1
}

psql_value() {
  guarded "${query_limit}" compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password \
    --tuples-only --no-align --set=ON_ERROR_STOP=1 --command="$1" </dev/null 2>>"${log_file}" | tr -d '[:space:]'
}

# --------------------------------------------------------------------------
# 1. 내려받기와 checksum
# --------------------------------------------------------------------------

cipher="${work}/${cipher_name}"
say '  1. offsite 암호문·checksum 내려받기와 SHA-256 대조'
rclone_run copyto "${offsite_remote}/generations/${cipher_name}" "${cipher}" >>"${log_file}" 2>&1 \
  && rclone_run copyto "${offsite_remote}/generations/${cipher_name}.sha256" "${cipher}.sha256" >>"${log_file}" 2>&1 \
  || fail 'DOWNLOAD_FAILED'
actual_sha="$(sha256_of "${cipher}")"
recorded="$(tr -d '\r' <"${cipher}.sha256" | sed -n 1p)"
if ! matches "${actual_sha}" '[0-9a-f]{64}' || [ "${recorded}" != "${actual_sha}  ${cipher_name}" ]; then
  fail "CHECKSUM_MISMATCH (${generation}의 암호문이 기록된 SHA-256과 다르다 — 복원하지 않는다)"
fi
say '     대조 일치'

# --------------------------------------------------------------------------
# 2. key·복호화
# --------------------------------------------------------------------------

say "  2. backup identity ${key_id}로 복호화"
problem="$(restore_key_problem "${key_id}")"
[ -z "${problem}" ] || fail "KEY_UNAVAILABLE (${problem}) — 보존이 끝나기 전에는 과거 identity를 지우지 않는다"
[ -s "${keys_dir}/recipients/${key_id}.pub" ] \
  || say "     경고  recipients/${key_id}.pub이 없어 identity의 짝을 대조하지 않았다(복호화가 그 대조를 대신한다)."
plain="${work}/${generation}.dump"
guarded "${step_limit}" "${age_bin}" -d -i "${keys_dir}/identities/${key_id}.key" -o "${plain}" "${cipher}" 2>>"${log_file}" \
  || { rm -f "${plain}"; fail 'DECRYPT_FAILED'; }
rm -f "${cipher}" "${cipher}.sha256"
[ -s "${plain}" ] || fail 'DECRYPT_FAILED (빈 dump)'

# --------------------------------------------------------------------------
# 3. 빈 PostgreSQL에 복원
# --------------------------------------------------------------------------

say "  3. 격리 프로젝트 ${project}의 빈 PostgreSQL에 pg_restore"
project_created=true
guarded "${step_limit}" compose up -d --wait --wait-timeout 120 postgres </dev/null >>"${log_file}" 2>&1 || fail 'DB_UNAVAILABLE'
tables="$(psql_value "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")"
[ "${tables}" = 0 ] || fail "TARGET_NOT_EMPTY (격리 DB에 표 ${tables:-?}개가 있다)"
guarded "${step_limit}" compose exec -T postgres pg_restore --username=bokrilog --dbname=bokrilog --no-password \
  --exit-on-error --single-transaction <"${plain}" >>"${log_file}" 2>&1
restore_status=$?
rm -f "${plain}"
[ "${restore_status}" -eq 0 ] || fail 'RESTORE_FAILED (pg_restore가 실패했다 — 한 트랜잭션이라 아무것도 남지 않았다)'
say '     복원 완료, 평문 dump 삭제'

# --------------------------------------------------------------------------
# 4. migration
# --------------------------------------------------------------------------

say '  4. migrate 역할'
guarded "${step_limit}" compose --profile migrate run --rm --no-deps -T migrate </dev/null >>"${log_file}" 2>&1 \
  || fail 'MIGRATE_FAILED'

# --------------------------------------------------------------------------
# 5. 외부 tombstone
# --------------------------------------------------------------------------

say "  5. tombstone 사본 ${tombstone_name} 받기와 복호화"
rclone_run copyto "${offsite_remote}/tombstones/${tombstone_name}" "${work}/tombstones.age" >>"${log_file}" 2>&1 \
  || fail "TOMBSTONES_UNAVAILABLE (${tombstone_name}을 받지 못했다)"
guarded "${step_limit}" "${age_bin}" -d -i "${tombstone_identity}" -o "${io_dir}/tombstones.txt" "${work}/tombstones.age" \
  2>>"${log_file}" || { rm -f "${io_dir}/tombstones.txt"; fail 'TOMBSTONES_UNAVAILABLE (복호화 실패)'; }
rm -f "${work}/tombstones.age"
case "$(head -1 "${io_dir}/tombstones.txt" | tr -d '\r')" in
  "${tombstone_header}"*) ;;
  *) fail 'TOMBSTONES_UNAVAILABLE (tombstone 파일의 머리가 없다)' ;;
esac
tombstone_count="$(grep -cvE '^(#|[[:space:]]*$)' "${io_dir}/tombstones.txt" || true)"
say "     사본 ${tombstone_name}, tombstone ${tombstone_count}개"

# --------------------------------------------------------------------------
# 6. 적용과 검증(서비스를 열기 전)
# --------------------------------------------------------------------------

say '  6. restore-apply 역할: 사본 대조·tombstone 적용·세션 폐기·불변식·자격정보 표본'
apply_log="${work}/apply.log"
guarded "${role_limit}" compose --profile restore-apply run --rm --no-deps -T --user "$(id -u):$(id -g)" \
  -e "BOKRILOG_RESTORE_ISOLATED_PROJECT=${project}" restore-apply </dev/null >"${apply_log}" 2>&1
apply_status=$?
rm -f "${io_dir}/tombstones.txt"
cat "${apply_log}" >>"${log_file}"
report="$(sed -n 's/.*"event":"\(restore\.apply [^"]*\)".*/\1/p' "${apply_log}" | tail -1)"
rm -f "${apply_log}"
say "     ${report:-보고 없음}"
[ "${apply_status}" -eq 0 ] || fail 'APPLY_FAILED (사본 대조·불변식·자격정보 검증 또는 tombstone 적용이 실패했다 — 서비스를 열지 않는다)'

# --------------------------------------------------------------------------
# 7. 서비스
# --------------------------------------------------------------------------

say '  7. api-blue·worker 기동(nginx 없음, 공개 포트 없음)'
guarded "${step_limit}" compose up -d --no-deps --wait --wait-timeout 180 api-blue worker </dev/null >>"${log_file}" 2>&1 \
  || fail 'SERVICE_FAILED'
published="$(docker ps --filter "label=com.docker.compose.project=${project}" --format '{{.Names}} {{.Ports}}' 2>/dev/null \
  | grep -E -- '->' || true)"
if [ -n "${published}" ]; then
  # 서비스를 먼저 멈춘다 — 공개된 채로 실패 처리를 기다리지 않는다.
  guarded "${step_limit}" compose stop api-blue worker </dev/null >>"${log_file}" 2>&1
  fail "ISOLATION_BROKEN (공개 포트가 있다: ${published}) — API·Worker를 멈췄다"
fi
running="$(docker ps --filter "label=com.docker.compose.project=${project}" --format '{{.Label "com.docker.compose.service"}}' \
  2>/dev/null | sort | tr '\n' ' ' | sed 's/ $//')"
say "     실행 중: ${running}"

# --------------------------------------------------------------------------
# 8. RPO
# --------------------------------------------------------------------------

say '  8. RPO'
stale_note=''
[ "${stale_accepted}" = true ] && stale_note=' 경고: 오래된 tombstone 사본으로 복원했다(--allow-stale-tombstones).'
if rpo_verdict; then
  say "복원 완료: ${project} ← ${generation} (key ${key_id}).${stale_note} 이 실행은 훈련 증거 원장에 기록되지 않는다(실제 복원 미검증)."
  exit 0
fi
say "복원은 끝났지만 RPO 목표를 넘었다: ${project} ← ${generation}.${stale_note}"
exit 1
