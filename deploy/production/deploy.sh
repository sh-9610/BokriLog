#!/usr/bin/env bash
# ARM64 단일 서버 blue/green 배포와 rollback(Story 3.8 AC-1~5).
#
# 실행
#   deploy/production/deploy.sh deploy --release <release.env> [--actor <이름>] [--rehearsal]
#   deploy/production/deploy.sh close-window [--actor <이름>]   rollback 창을 닫는다(contract 배포 전에 한 번). 유일한 닫는 경로다.
#   deploy/production/deploy.sh status                          상태 파일을 보인다.
#
# 환경(이름만 여기 있고 값은 운영자가 준다)
#   BOKRILOG_STATE_DIR        상태 디렉터리(기본 /var/lib/bokrilog/deploy). 상태 파일 `deploy.state` 하나가 활성 슬롯·이미지·
#                             Worker 이미지·이전 이미지·schema 버전·rollback 창을 기록한다. `nginx/`에 upstream include가 있다.
#   BOKRILOG_SECRETS_FILE     운영 env 파일(기본 /etc/bokrilog/production.env, 권한 600). 저장소 밖이다. compose의 `--env-file`로
#                             넘기고 서비스는 이름으로 고른 값만 받는다.
#   BOKRILOG_HOST_FACTS       host 사실(기본 /etc/bokrilog/host-facts.env). 서명 신원(signer_identity·signer_issuer)도 여기 있다.
#   BOKRILOG_DEPLOY_ACTOR     배포 수행자 이름(`--actor`와 같다). 배포를 돌리는 셸이 준다 — 비밀 파일에 두지 않는다.
#   BOKRILOG_COMPOSE_OVERRIDE 겹칠 compose 파일(리허설만 `compose.rehearsal.yaml`).
#   BOKRILOG_PROJECT          compose 프로젝트 이름(기본 bokrilog-production, 모양 `bokrilog-<소문자>`).
#   BOKRILOG_DEPLOY_FAULT     리허설 전용 결함 주입: `drain-incomplete`(Worker drain이 75로 끝난 것으로 본다)·
#                             `inactive-readiness`(대기 슬롯이 뜨지 못한다)·`post-cutover-smoke`(전환 직후 새 슬롯이 멈춘다).
#                             운영에서는 gate가 거부한다.
#
# 순서(AC-3) — 단계마다 감사 원장에 SYSTEM 행 하나(일회성 `deploy-audit` 역할, 같은 operation ID)
#    0. 상태 대조: 상태 파일의 활성 슬롯과 실제로 도는 API 슬롯이 같아야 한다(다르면 gate 거부 — 활성 슬롯을 다시 만들지 않게)
#    1. gate(`preflight.sh`)                         거부면 `GATE FAILED`(활성 이미지로 기록), 아무 컨테이너도 바꾸지 않는다
#    2. `GATE REQUESTED`
#    3. Worker drain(SIGTERM → 종료 코드 0)            `DRAINED`
#    4. 단일 migrate 역할 + schema 최대 버전 확인       `MIGRATED` — 읽은 schema 버전은 그 자리에서 상태 파일에 남긴다
#    5. 기존 슬롯(old API + new schema) smoke
#    6. 대기 슬롯 기동
#    7. readiness(`/api/status`)·smoke·telemetry(관리 포트의 env·role tag)   `INACTIVE_VERIFIED`
#    8. upstream include 교체 → `nginx -t` → reload
#    9. 전환 뒤 smoke                                  `CUTOVER`
#   10. 새 Worker 하나 시작 + telemetry(env·role="worker")
#   11. 이전 슬롯 정지 → 상태 파일 갱신                `VERIFIED`
#   빈 데이터베이스에 대한 첫 배포는 원장 표가 아직 없어 2·3의 행을 줄 세웠다가 4의 migration 직후(`MIGRATED`보다 앞) 쓴다.
#
# 실패(AC-4)
#   전환 전 — 대기 슬롯을 지우고, upstream은 그대로 두고, 이전 digest의 Worker를 재개한다. 해당 단계 `FAILED`.
#   전환 뒤 — upstream을 이전 슬롯으로 되돌리고 이전 digest의 Worker를 재개한 뒤 새 슬롯을 지운다. `FAILED` 뒤 `ROLLED_BACK`.
#   신호(INT·HUP·TERM)도 같다 — 전환 전이면 `INTERRUPTED SIGNAL`로 전환 전 정리, 전환 뒤면 되돌림이다.
#   이전 슬롯 정지 뒤의 되돌리기는 이전 release manifest로 이 절차를 다시 도는 것이다. rollback 창 안에서는 expand만 받으므로
#   이전 digest가 새 schema 위에서 뜬다(old API + new schema). contract는 창이 닫힌 뒤 별도 배포다.
#
# smoke는 미인증 경로 셋이다 — `GET /api/status`(200 `{"status":"UP"}`), `GET /api/auth/session`(200 미인증 본문),
# `GET /api/connections/bybit`(401 공통 오류 모양 `UNAUTHENTICATED`). 운영 제한이 막는 기능이 아니므로 제한 중 배포도 smoke를
# 통과한다. 배포는 제한 행을 쓰지 않는다 — 감사 행이 시작·끝의 활성 제한 수를 남기고, 둘이 다르면 경고한다.
#
# 이 절차는 배포 실패만 완화한다. VM·volume·PostgreSQL·Nginx·리전 장애를 이중화하지 않고 무중단을 약속하지 않는다. 실제 운영
# 대상 smoke 전까지 이 절차는 **운영 배포 미검증**이다(CI 리허설만 통과했다).
#
# 종료 코드
#   0 -- 배포 완료(`VERIFIED`)·창 닫기 완료
#   1 -- gate 거부 또는 배포 실패(전환 전 정리·전환 뒤 rollback을 마쳤다). 원인은 감사 행과 출력의 마지막 줄에 있다
#   2 -- 준비 실패(인자·파일·도구·상태 잠금)

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib.sh
. "${script_dir}/lib.sh"

compose_file="${script_dir}/compose.yaml"
state_dir="${BOKRILOG_STATE_DIR:-/var/lib/bokrilog/deploy}"
secrets_file="${BOKRILOG_SECRETS_FILE:-/etc/bokrilog/production.env}"
host_facts_file="${BOKRILOG_HOST_FACTS:-/etc/bokrilog/host-facts.env}"
override_file="${BOKRILOG_COMPOSE_OVERRIDE:-}"
project="${BOKRILOG_PROJECT:-bokrilog-production}"
fault="${BOKRILOG_DEPLOY_FAULT:-}"
actor="${BOKRILOG_DEPLOY_ACTOR:-}"
state_file="${state_dir}/deploy.state"

# readiness·healthy 대기 상한(초).
ready_timeout=180

# 앱 이미지 참조의 모양. 저장소 부분은 180자까지라 감사 행의 target(256자)에 언제나 들어간다.
image_pattern='[a-z0-9][a-z0-9._/:-]{0,179}@sha256:[0-9a-f]{64}'

prep_fail() {
  printf '::error::배포 준비 실패: %s\n' "$1" >&2
  exit 2
}

say() {
  printf '%s\n' "$*"
}

# --------------------------------------------------------------------------
# 인자
# --------------------------------------------------------------------------

command_name="${1:-}"
[ "$#" -gt 0 ] && shift
release_file=''
rehearsal=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --release)
      [ "$#" -ge 2 ] || prep_fail '--release에 값이 없다.'
      release_file="$2"
      shift 2
      ;;
    --actor)
      [ "$#" -ge 2 ] || prep_fail '--actor에 값이 없다.'
      actor="$2"
      shift 2
      ;;
    --rehearsal) rehearsal=true; shift ;;
    *) prep_fail "알 수 없는 인자: $1" ;;
  esac
done

matches "${project}" 'bokrilog-[a-z][a-z0-9-]{0,39}' || prep_fail 'BOKRILOG_PROJECT는 bokrilog-<소문자·숫자·->여야 한다.'

case "${command_name}" in
  status)
    if [ -f "${state_file}" ]; then
      cat "${state_file}"
    else
      say '상태 파일이 없다(첫 배포 전).'
    fi
    exit 0
    ;;
  close-window | deploy) ;;
  *) prep_fail '명령은 deploy·close-window·status 중 하나다.' ;;
esac

# --------------------------------------------------------------------------
# 상태 파일
# --------------------------------------------------------------------------

state_get() {
  if [ -f "${state_file}" ]; then
    kv_get "${state_file}" "$1"
  fi
}

# write_state <key=value…>. 기존 값 위에 덮어 쓰고 rename으로 바꾼다(반쯤 쓴 상태 파일이 남지 않는다).
write_state() {
  local tmp="${state_file}.tmp" pair key keys value
  keys='active_slot active_image worker_image previous_image schema_version rollback_window last_operation last_result'
  {
    printf '# BokriLog 배포 상태(deploy.sh가 쓴다 — 손으로 고치지 않는다)\n'
    for key in ${keys}; do
      value="$(state_get "${key}")"
      for pair in "$@"; do
        [ "${pair%%=*}" = "${key}" ] && value="${pair#*=}"
      done
      printf '%s=%s\n' "${key}" "${value}"
    done
  } >"${tmp}" && mv "${tmp}" "${state_file}"
}

[ -f "${secrets_file}" ] || prep_fail "비밀 파일이 없다: ${secrets_file}"
command -v docker >/dev/null 2>&1 || prep_fail 'docker가 없다.'
docker compose version >/dev/null 2>&1 || prep_fail 'docker compose v2를 쓸 수 없다.'
mkdir -p "${state_dir}/nginx" "${state_dir}/logs" || prep_fail "상태 디렉터리를 만들지 못했다: ${state_dir}"

operation="deploy_$(date -u +%Y%m%dT%H%M%SZ)_$(random_hex 6)"

# 배포와 창 닫기는 한 번에 하나다. 일일 백업(`backup.sh`, Story 3.9)도 같은 잠금을 쥐고 돈다 — 백업 중에는 배포가 시작되지 않고, 배포
# 중에는 백업이 시작되지 않는다. 잠금 안의 `owner`가 누가(deploy|backup·pid·operation·시작 시각) 쥐었는지 말한다(`lib.sh`).
lock_dir="${state_dir}/.lock"
if ! lock_acquire "${lock_dir}" deploy "${operation}"; then
  case "${lock_state}" in
    BACKUP_IN_PROGRESS) prep_fail "백업이 진행 중이다(${lock_owner}). 끝난 뒤 다시 돈다." ;;
    DEPLOY_IN_PROGRESS) prep_fail "다른 배포가 진행 중이다(${lock_owner})." ;;
    *) prep_fail "LOCK_STALE: 잠금(${lock_dir})의 소유자가 없다(${lock_owner}). 진행 중인 배포·백업이 정말 없는지 확인한 뒤 손으로 지운다(DEVELOPMENT.md)." ;;
  esac
fi
trap 'lock_release "${lock_dir}"' EXIT
log_file="${state_dir}/logs/${operation}.log"
: >"${log_file}"
say "operation: ${operation}"
say "  기록: ${log_file}"

old_slot="$(state_get active_slot)"
old_image="$(state_get active_image)"
old_worker_image="$(state_get worker_image)"
old_schema="$(state_get schema_version)"
matches "${old_schema:-}" '[0-9]+' || old_schema=0

new_image=''
migration_version=''
migration_kind=''
if [ "${command_name}" = deploy ]; then
  [ -n "${release_file}" ] || prep_fail '--release가 없다.'
  [ -f "${release_file}" ] || prep_fail "release 파일이 없다: ${release_file}"
  [ -f "${host_facts_file}" ] || prep_fail "host 사실 파일이 없다: ${host_facts_file}"
  new_image="$(kv_get "${release_file}" image)"
  migration_version="$(kv_get "${release_file}" migration_version)"
  migration_kind="$(kv_get "${release_file}" migration_kind)"
fi

if [ "${old_slot}" = blue ]; then
  new_slot=green
else
  new_slot=blue
fi

# compose 변수. 모든 이미지 변수가 비지 않아야 compose가 파일을 읽는다 — 아직 쓰지 않은 자리는 이번 이미지로 채운다.
image_ok=false
if matches "${new_image}" "${image_pattern}" || { [ "${rehearsal}" = true ] && matches "${new_image}" 'sha256:[0-9a-f]{64}'; }; then
  image_ok=true
fi
placeholder="${new_image}"
[ "${image_ok}" = true ] || placeholder="${old_image}"
run_image="${placeholder}"
audit_image="${placeholder}"
blue_image="${placeholder}"
green_image="${placeholder}"
worker_run_image="${old_worker_image:-${placeholder}}"
if [ "${old_slot}" = blue ]; then
  blue_image="${old_image}"
elif [ "${old_slot}" = green ]; then
  green_image="${old_image}"
fi
slot_env="${BOKRILOG_ENV:-}"
if [ "${rehearsal}" = true ]; then
  expected_env="${BOKRILOG_ENV:-ci}"
else
  expected_env=production
fi

compose() {
  local args
  args=(--project-name "${project}" --env-file "${secrets_file}" --file "${compose_file}")
  if [ -n "${override_file}" ]; then
    args+=(--file "${override_file}")
  fi
  BOKRILOG_STATE_DIR="${state_dir}" BOKRILOG_ENV="${slot_env}" \
    BOKRILOG_IMAGE="${run_image:-none}" BOKRILOG_AUDIT_IMAGE="${audit_image:-none}" \
    BOKRILOG_BLUE_IMAGE="${blue_image:-none}" BOKRILOG_GREEN_IMAGE="${green_image:-none}" \
    BOKRILOG_WORKER_IMAGE="${worker_run_image:-none}" \
    docker compose "${args[@]}" "$@" </dev/null
}

# 값을 내지 않는 compose 호출. 출력은 기록 파일로 간다.
compose_quiet() {
  compose "$@" >>"${log_file}" 2>&1
}

# --------------------------------------------------------------------------
# 감사
# --------------------------------------------------------------------------

# audit <단계> <결과> [code] [slot] [previous] [target]
audit() {
  local step="$1" outcome="$2" code="${3:-}" slot="${4:-}" previous="${5:-}" target
  target="${6-${new_image}}"
  if [ "${image_ok}" = false ] && [ "${target}" = "${new_image}" ]; then
    target=''
  fi
  if [ -z "${audit_image}" ]; then
    say "  경고  감사 이미지가 없어 ${step} ${outcome}을 기록하지 못했다(첫 배포의 gate 거부)."
    return 1
  fi
  if compose_quiet --profile deploy-audit run --rm --no-deps -T \
    -e "BOKRILOG_DEPLOY_OPERATION=${operation}" -e "BOKRILOG_DEPLOY_STEP=${step}" \
    -e "BOKRILOG_DEPLOY_OUTCOME=${outcome}" -e "BOKRILOG_DEPLOY_TARGET=${target}" \
    -e "BOKRILOG_DEPLOY_CODE=${code}" -e "BOKRILOG_DEPLOY_SLOT=${slot}" -e "BOKRILOG_DEPLOY_PREVIOUS=${previous}" \
    -e "BOKRILOG_DEPLOY_ACTOR=${actor}" -e "BOKRILOG_DEPLOY_REHEARSAL=${rehearsal}" deploy-audit; then
    say "  감사  ${step} ${outcome}${code:+ ${code}}"
    return 0
  fi
  say "  경고  감사 ${step} ${outcome}을 기록하지 못했다."
  return 1
}

# --------------------------------------------------------------------------
# 컨테이너 도우미
# --------------------------------------------------------------------------

container_of() {
  compose ps -a -q "$1" 2>/dev/null | head -1
}

is_running() {
  local id
  id="$(container_of "$1")"
  [ -n "${id}" ] && [ "$(docker inspect --format '{{.State.Running}}' "${id}" 2>/dev/null)" = true ]
}

# wait_healthy <서비스>. healthy면 0, 끝났거나 재시작했거나 시간이 다하면 1.
wait_healthy() {
  local id status health restarts waited=0
  while [ "${waited}" -lt "${ready_timeout}" ]; do
    id="$(container_of "$1")"
    if [ -n "${id}" ]; then
      status="$(docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} {{.RestartCount}}' "${id}" 2>/dev/null)"
      health="$(printf '%s' "${status}" | awk '{ print $2 }')"
      restarts="$(printf '%s' "${status}" | awk '{ print $3 }')"
      case "${status}" in
        running*) [ "${health}" = healthy ] && return 0 ;;
        exited* | dead*) return 1 ;;
      esac
      [ "${restarts:-0}" = 0 ] || return 1
    fi
    sleep 2
    waited=$((waited + 2))
  done
  return 1
}

# http_probe <URL>. Nginx 컨테이너 안에서 요청한다(같은 compose 망, 호스트 포트와 무관). `probe_status`·`probe_body`.
probe_status=''
probe_body=''
http_probe() {
  local out
  out="$(compose exec -T nginx curl --silent --max-time 10 --output - --write-out '\n%{http_code}' "$1" 2>/dev/null)" \
    || out="$(printf '\n000')"
  probe_status="${out##*$'\n'}"
  probe_body="${out%$'\n'*}"
}

# smoke <base URL>. 미인증 경로 셋.
smoke() {
  http_probe "$1/api/status"
  [ "${probe_status}" = 200 ] && [ "${probe_body}" = '{"status":"UP"}' ] || return 1
  http_probe "$1/api/auth/session"
  [ "${probe_status}" = 200 ] && [ "${probe_body}" = '{"authenticated":false,"destination":"entry"}' ] || return 1
  http_probe "$1/api/connections/bybit"
  [ "${probe_status}" = 401 ] || return 1
  case "${probe_body}" in
    *'"code":"UNAUTHENTICATED"'*'"correlationId":"'*) return 0 ;;
  esac
  return 1
}

# smoke_retry <base URL>. reload 직후 이전 worker 프로세스가 잠깐 남을 수 있어 다섯 번까지 본다.
smoke_retry() {
  local attempt=0
  while [ "${attempt}" -lt 5 ]; do
    smoke "$1" && return 0
    attempt=$((attempt + 1))
    sleep 1
  done
  return 1
}

# telemetry_ok <서비스> <역할>. 관리 포트 출력의 모든 시계열에 env·role tag가 있다.
telemetry_ok() {
  local out series lacking
  out="$(compose exec -T "$1" wget -q -O - http://127.0.0.1:18081/actuator/prometheus 2>/dev/null)" || return 1
  series="$(printf '%s\n' "${out}" | grep -v '^#' | grep -v '^[[:space:]]*$')"
  [ -n "${series}" ] || return 1
  lacking="$(printf '%s\n' "${series}" | grep -cvF "env=\"${expected_env}\"" || true)"
  [ "${lacking}" = 0 ] || return 1
  lacking="$(printf '%s\n' "${series}" | grep -cvF "role=\"$2\"" || true)"
  [ "${lacking}" = 0 ]
}

# write_upstream <슬롯>. 같은 디렉터리 안 rename이라 nginx가 반쯤 쓴 파일을 읽지 않는다.
#
# 이름은 Docker 내장 DNS(127.0.0.11)로 10초마다 다시 해석한다(`resolve`, 공유 메모리 `zone` 필요 — nginx 1.27.3+). 그래서 슬롯이
# 재시작해 주소가 바뀌어도 nginx가 옛 주소에 묶이지 않고, 슬롯이 아직 없을 때도 nginx가 뜨고 그동안은 502(공통 오류)다.
write_upstream() {
  local target="${state_dir}/nginx/upstream-backend.conf" tmp="${state_dir}/nginx/.upstream-backend.conf.tmp"
  {
    printf '# 배포 스크립트가 쓴다(%s). 활성 슬롯: %s\n' "${operation}" "$1"
    printf 'upstream bokrilog_backend {\n'
    printf '  zone bokrilog_backend 64k;\n'
    printf '  resolver 127.0.0.11 valid=10s ipv6=off;\n'
    printf '  server api-%s:8080 resolve;\n' "$1"
    printf '}\n'
  } >"${tmp}" && mv "${tmp}" "${target}"
}

reload_nginx() {
  compose_quiet exec -T nginx nginx -t && compose_quiet exec -T nginx nginx -s reload
}

# psql_value <SQL>. 값 하나.
psql_value() {
  compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password --tuples-only --no-align \
    --command="$1" 2>>"${log_file}" | tr -d '[:space:]'
}

# resume_worker <이미지>. 이전 digest의 Worker를 다시 띄운다. 이미지가 없으면(첫 배포) 할 일이 없다.
resume_worker() {
  [ -n "$1" ] || return 0
  worker_run_image="$1"
  if compose_quiet up -d --no-deps --force-recreate worker && wait_healthy worker; then
    say "  재개  Worker(${1})"
    return 0
  fi
  say "  경고  이전 Worker를 재개하지 못했다. 수동 확인이 필요하다."
  return 1
}

remove_slot() {
  compose_quiet rm --force --stop --volumes "api-$1"
}

# 단계: none(아직 아무것도 바꾸지 않았다) → before(전환 전, 바꾸는 중) → after(전환 뒤) → done. 신호 처리기가 이 값으로 정리 경로를
# 고른다.
phase=none

finish_failed() {
  phase=done
  write_state "last_operation=${operation}" "last_result=$1" >/dev/null 2>&1
  say "::error::배포 ${operation}이 실패했다: $2"
  exit 1
}

# 전환 전 실패: 대기 슬롯을 지우고 이전 Worker를 재개한다. upstream은 바꾸지 않았다.
fail_before_cutover() {
  local step="$1" code="$2"
  phase=done
  trap '' INT HUP TERM
  say "  실패  ${step} ${code}"
  audit "${step}" FAILED "${code}" "${new_slot}" "${old_image}"
  if [ "${started_new_slot:-false}" = true ]; then
    remove_slot "${new_slot}"
    say "  정리  대기 슬롯 api-${new_slot}을 지웠다"
  fi
  if [ "${drained:-false}" = true ]; then
    resume_worker "${old_worker_image}"
  fi
  finish_failed FAILED "${step} ${code}"
}

# 전환 뒤 실패: upstream을 이전 슬롯으로 되돌리고 이전 Worker를 재개한다.
fail_after_cutover() {
  local step="$1" code="$2"
  phase=done
  trap '' INT HUP TERM
  say "  실패  ${step} ${code}"
  audit "${step}" FAILED "${code}" "${new_slot}" "${old_image}"
  if [ -z "${old_slot}" ]; then
    # 첫 배포에는 돌아갈 슬롯이 없다.
    compose_quiet stop worker
    finish_failed FAILED "${step} ${code} (첫 배포라 되돌릴 슬롯이 없다)"
  fi
  if ! write_upstream "${old_slot}"; then
    say "  경고  이전 슬롯의 upstream을 쓰지 못했다. 수동 확인이 필요하다."
    audit ROLLED_BACK FAILED UPSTREAM_WRITE_FAILED "${old_slot}" "${new_image}" "${old_image}"
    finish_failed FAILED "${step} ${code} — upstream을 되돌리지 못했다"
  fi
  if ! reload_nginx; then
    say "  경고  이전 슬롯으로 nginx를 되돌리지 못했다. 수동 확인이 필요하다."
  fi
  if is_running worker; then
    compose_quiet stop worker
  fi
  resume_worker "${old_worker_image}"
  remove_slot "${new_slot}"
  if smoke_retry 'http://127.0.0.1'; then
    audit ROLLED_BACK COMPLETED '' "${old_slot}" "${new_image}" "${old_image}"
    write_state "last_operation=${operation}" "last_result=ROLLED_BACK" >/dev/null 2>&1
    say "  되돌림  api-${old_slot}(${old_image})"
    say "::error::배포 ${operation}이 전환 뒤 실패해 이전 슬롯으로 되돌렸다: ${step} ${code}"
    exit 1
  fi
  audit ROLLED_BACK FAILED ROLLBACK_SMOKE_FAILED "${old_slot}" "${new_image}" "${old_image}"
  finish_failed FAILED "${step} ${code} — 되돌린 뒤 smoke도 실패했다. 수동 확인이 필요하다"
}

# 신호(INT·HUP·TERM). 바꾸기 전이면 그냥 끝내고, 전환 전이면 전환 전 정리, 전환 뒤면 되돌림이다.
on_signal() {
  case "${phase}" in
    before) fail_before_cutover INTERRUPTED SIGNAL ;;
    after) fail_after_cutover INTERRUPTED SIGNAL ;;
    done) ;;
    *)
      say '::error::신호로 중단했다. 아직 아무것도 바꾸지 않았다.'
      exit 1
      ;;
  esac
}
trap on_signal INT HUP TERM

# --------------------------------------------------------------------------
# close-window
# --------------------------------------------------------------------------

if [ "${command_name}" = close-window ]; then
  [ -f "${state_file}" ] || prep_fail '상태 파일이 없다.'
  [ -n "${old_image}" ] || prep_fail '상태 파일에 활성 이미지가 없다.'
  matches "${actor}" '[A-Za-z0-9._@-]{1,128}' || prep_fail '배포 수행자(--actor·BOKRILOG_DEPLOY_ACTOR)가 없거나 모양이 틀렸다.'
  if [ "$(state_get rollback_window)" != open ]; then
    say 'rollback 창이 이미 닫혀 있다.'
    exit 0
  fi
  # 감사 행을 먼저 쓴다. 쓰지 못하면 창을 닫지 않는다(감사 없는 상태 변경을 하지 않는다).
  audit_image="${old_image}"
  image_ok=true
  if ! audit WINDOW_CLOSED COMPLETED '' "$(state_get active_slot)" "$(state_get previous_image)" "${old_image}"; then
    say '::error::창 닫기를 원장에 기록하지 못했다. 창은 열린 채다.'
    exit 1
  fi
  write_state rollback_window=closed "last_operation=${operation}" last_result=WINDOW_CLOSED \
    || prep_fail '상태 파일을 쓰지 못했다.'
  say 'rollback 창을 닫았다. 이제 이전 digest로 되돌릴 수 없고 contract 배포를 받는다.'
  exit 0
fi

# --------------------------------------------------------------------------
# 0~1. 상태 대조와 gate
# --------------------------------------------------------------------------

# refuse_gate <code> <설명>. 아무것도 바꾸지 않고 끝낸다. 감사 행은 지금 활성 이미지의 감사 역할이 쓴다(거부된 이미지는 띄우지
# 않는다 — 첫 배포면 기록할 곳이 없다).
refuse_gate() {
  say "  거부  $1  $2"
  audit_image="${old_image}"
  if [ -n "${old_image}" ] && is_running postgres; then
    audit GATE FAILED "$1"
  else
    say '  경고  활성 배포가 없어 gate 거부를 원장에 기록하지 못했다.'
  fi
  say "::error::gate가 배포를 거부했다: $1"
  exit 1
}

say ''
say '0. 상태 대조'
running_slots=''
for slot in blue green; do
  is_running "api-${slot}" && running_slots="${running_slots}${slot} "
done
running_slots="${running_slots% }"
if [ -z "${old_slot}" ]; then
  # 상태 파일이 없거나 활성 슬롯이 비었는데 API 슬롯이 돈다 — 상태를 잃은 것이다. 새로 배포하면 도는 슬롯을 다시 만든다.
  [ -z "${running_slots}" ] || refuse_gate STATE_MISSING "상태 파일에 활성 슬롯이 없는데 api-${running_slots}가 돈다."
elif [ "${running_slots}" != "${old_slot}" ]; then
  refuse_gate STATE_MISMATCH "상태 파일의 활성 슬롯은 ${old_slot}인데 도는 API 슬롯은 '${running_slots:-없음}'이다."
fi
say "  활성 슬롯 ${old_slot:-없음}"

say ''
say '1. gate'
if [ "${rehearsal}" = false ] && [ "${image_ok}" = true ]; then
  # 운영 서버에서 이미지를 빌드하지 않는다. digest로 받기만 한다.
  docker pull "${new_image}" >>"${log_file}" 2>&1 || say '  경고  이미지를 받지 못했다(gate가 판정한다).'
fi
preflight_args=(--release "${release_file}" --host-facts "${host_facts_file}" --secrets "${secrets_file}"
  --compose "${compose_file}" --state "${state_file}" --actor "${actor}" --project "${project}"
  --override "${override_file}")
[ "${rehearsal}" = true ] && preflight_args+=(--rehearsal)
preflight_out="$(bash "${script_dir}/preflight.sh" "${preflight_args[@]}" 2>&1)"
preflight_status=$?
printf '%s\n' "${preflight_out}" >>"${log_file}"
if [ "${preflight_status}" -eq 2 ]; then
  printf '%s\n' "${preflight_out}"
  prep_fail 'gate가 준비 실패로 끝났다.'
fi
if [ "${preflight_status}" -ne 0 ]; then
  printf '%s\n' "${preflight_out}" | grep -E '^  거부  '
  code="$(printf '%s\n' "${preflight_out}" | awk '/^  거부  / { print $2; exit }')"
  refuse_gate "${code:-GATE_REFUSED}" 'gate 거부(위 줄들)'
fi
say '  통과'

# --------------------------------------------------------------------------
# 2. 요청 기록
# --------------------------------------------------------------------------

say ''
say "2. 요청 기록(${old_image:-첫 배포} → ${new_image}, 대기 슬롯 ${new_slot})"
# 이미 도는 PostgreSQL은 다시 만들지 않는다(설정이 달라 보여도 배포 중에 DB를 재생성하지 않는다).
if ! compose_quiet up -d --no-recreate --wait --wait-timeout "${ready_timeout}" postgres; then
  say '::error::PostgreSQL이 준비되지 않았다. 아무것도 바꾸지 않았다.'
  exit 1
fi
# 원장이 아직 없으면(빈 데이터베이스에 대한 첫 배포) migration 앞의 두 행(GATE·DRAINED)을 줄 세워 두고, 첫 migration이 원장 표를
# 만든 직후 같은 순서로 쓴다 — MIGRATED보다 앞이다. 그 사이에 바뀌는 것은 원장을 만드는 그 migration 하나뿐이다.
ledger_ready="$(psql_value "SELECT to_regclass('public.operations_audit_event') IS NOT NULL")"
pending_audits=()

# record <audit 인자…>. 원장이 있으면 바로 쓰고, 없으면 줄 세운다.
record() {
  if [ "${ledger_ready}" = t ]; then
    audit "$@"
    return
  fi
  pending_audits+=("$1|$2|${3:-}|${4:-}|${5:-}")
  say "  감사 대기  $1 $2(원장이 아직 없다 — 첫 migration 뒤에 쓴다)"
}

# flush_pending. 줄 세운 행을 순서대로 쓴다. 하나라도 실패하면 1.
flush_pending() {
  local entry step outcome code slot previous
  [ "${ledger_ready}" = t ] && return 0
  ledger_ready="$(psql_value "SELECT to_regclass('public.operations_audit_event') IS NOT NULL")"
  [ "${ledger_ready}" = t ] || return 1
  for entry in ${pending_audits[@]+"${pending_audits[@]}"}; do
    IFS='|' read -r step outcome code slot previous <<ENTRY
${entry}
ENTRY
    audit "${step}" "${outcome}" "${code}" "${slot}" "${previous}" || return 1
  done
  pending_audits=()
  return 0
}

if ! record GATE REQUESTED '' "${new_slot}" "${old_image}"; then
  say '::error::배포 요청을 원장에 기록하지 못했다. 감사 없는 상태 변경을 하지 않는다.'
  exit 1
fi
write_state "last_operation=${operation}" "last_result=REQUESTED" || prep_fail '상태 파일을 쓰지 못했다.'

# --------------------------------------------------------------------------
# 3. Worker drain
# --------------------------------------------------------------------------

say ''
say '3. Worker drain'
drained=false
started_new_slot=false
phase=before
if is_running worker; then
  worker_id="$(container_of worker)"
  compose_quiet stop worker
  drain_code="$(docker inspect --format '{{.State.ExitCode}}' "${worker_id}" 2>/dev/null)"
  drained=true
  if [ "${fault}" = drain-incomplete ]; then
    # 리허설 전용 결함 주입. 이 절차가 75를 받았을 때의 반응만 본다 — 실제 JVM이 신호를 받아 drain 뒤 그 코드로 끝나는 경로
    # (`WorkerDrain` → `BokriLogApplication`의 halt)는 이 리허설도 시험도 고정하지 않는다(시험은 빈의 `exitCode()`까지다).
    drain_code=75
  fi
  case "${drain_code}" in
    0) say '  Worker가 진행 중 작업을 비우고 종료 코드 0으로 끝났다' ;;
    # drain-timeout(worker.drain.incomplete). 남은 작업은 lease 만료 뒤 다시 잡힌다.
    75) fail_before_cutover DRAINED DRAIN_INCOMPLETE ;;
    # 단일 Worker 잠금 연결을 잃고 스스로 끝났다(worker.lock.lost).
    76) fail_before_cutover DRAINED LOCK_LOST ;;
    *) fail_before_cutover DRAINED WORKER_EXIT_UNEXPECTED ;;
  esac
else
  # 멈춰 있던 Worker도 재개 대상이다(이전 digest).
  [ -n "${old_worker_image}" ] && drained=true
  say '  돌던 Worker가 없다'
fi
record DRAINED COMPLETED '' '' "${old_worker_image}" || fail_before_cutover DRAINED AUDIT_UNAVAILABLE

# --------------------------------------------------------------------------
# 4. migration
# --------------------------------------------------------------------------

say ''
say "4. migration(${migration_kind} → V${migration_version})"
if ! compose_quiet --profile migrate run --rm --no-deps -T migrate; then
  flush_pending
  fail_before_cutover MIGRATED MIGRATION_FAILED
fi
flush_pending || fail_before_cutover MIGRATED AUDIT_UNAVAILABLE
schema_now="$(psql_value 'SELECT COALESCE(max(CAST(version AS integer)), 0) FROM flyway_schema_history WHERE success AND version IS NOT NULL')"
# 이 배포가 뒤에서 실패해도 schema는 이미 바뀌었다. 다음 gate가 옛 값으로 판정하지 않게 지금 남긴다.
if matches "${schema_now}" '[0-9]+'; then
  write_state "schema_version=${schema_now}" >/dev/null 2>&1 || say '  경고  schema 버전을 상태 파일에 남기지 못했다.'
fi
expected_schema="${migration_version}"
if [ "${migration_version}" -lt "${old_schema}" ]; then
  # rollback 창 안의 이전 이미지(gate가 그 경우만 받았다): schema는 그대로 앞서 있다.
  expected_schema="${old_schema}"
fi
if [ "${schema_now}" != "${expected_schema}" ]; then
  say "  schema 최대 버전 ${schema_now:-?} (기대 ${expected_schema})"
  fail_before_cutover MIGRATED SCHEMA_VERSION_MISMATCH
fi
say "  schema 최대 버전 ${schema_now}"
audit MIGRATED COMPLETED || fail_before_cutover MIGRATED AUDIT_UNAVAILABLE

# --------------------------------------------------------------------------
# 5~7. 기존 슬롯 smoke, 대기 슬롯 기동·검증
# --------------------------------------------------------------------------

say ''
say '5. 기존 슬롯(old API + new schema) smoke'
if [ -n "${old_slot}" ]; then
  smoke_retry 'http://127.0.0.1' || fail_before_cutover INACTIVE_VERIFIED OLD_SLOT_SMOKE_FAILED
  say "  api-${old_slot} 통과"
else
  say '  첫 배포라 기존 슬롯이 없다'
fi

say ''
say "6. 대기 슬롯 api-${new_slot} 기동"
if [ "${new_slot}" = blue ]; then
  blue_image="${new_image}"
else
  green_image="${new_image}"
fi
started_new_slot=true
if [ "${fault}" = inactive-readiness ]; then
  # 리허설 결함 주입: 대기 슬롯이 읽을 수 없는 환경 값으로 떠서 기동을 거부한다(gate가 운영에서는 막는다).
  slot_env='fault-injected'
fi
compose_quiet up -d --no-deps --force-recreate "api-${new_slot}"
slot_env="${BOKRILOG_ENV:-}"

say '7. readiness·smoke·telemetry'
wait_healthy "api-${new_slot}" || fail_before_cutover INACTIVE_VERIFIED INACTIVE_READINESS_FAILED
if ! is_running nginx; then
  # 첫 배포: 진입점이 아직 없다. upstream을 대기 슬롯으로 두고 Nginx를 띄운다(바깥에서 닿는 첫 순간이다).
  if ! write_upstream "${new_slot}"; then
    say '  경고  upstream을 쓰지 못했다.'
    fail_before_cutover INACTIVE_VERIFIED UPSTREAM_WRITE_FAILED
  fi
  compose_quiet up -d --no-deps --wait --wait-timeout "${ready_timeout}" nginx \
    || fail_before_cutover INACTIVE_VERIFIED NGINX_UNAVAILABLE
fi
smoke_retry "http://api-${new_slot}:8080" || fail_before_cutover INACTIVE_VERIFIED INACTIVE_SMOKE_FAILED
telemetry_ok "api-${new_slot}" api || fail_before_cutover INACTIVE_VERIFIED INACTIVE_TELEMETRY_FAILED
say "  api-${new_slot} 통과"
audit INACTIVE_VERIFIED COMPLETED '' "${new_slot}" || fail_before_cutover INACTIVE_VERIFIED AUDIT_UNAVAILABLE

# --------------------------------------------------------------------------
# 8~9. 전환
# --------------------------------------------------------------------------

say ''
say "8. 전환(upstream → api-${new_slot})"
upstream_file="${state_dir}/nginx/upstream-backend.conf"
upstream_backup="${state_dir}/nginx/.upstream-backend.conf.previous"
[ -f "${upstream_file}" ] && cp "${upstream_file}" "${upstream_backup}"
if ! write_upstream "${new_slot}"; then
  [ -f "${upstream_backup}" ] && mv "${upstream_backup}" "${upstream_file}"
  fail_before_cutover CUTOVER UPSTREAM_WRITE_FAILED
fi
if ! reload_nginx; then
  [ -f "${upstream_backup}" ] && mv "${upstream_backup}" "${upstream_file}"
  fail_before_cutover CUTOVER NGINX_CONFIG_INVALID
fi
rm -f "${upstream_backup}"
phase=after

say '9. 전환 뒤 smoke'
if [ "${fault}" = post-cutover-smoke ]; then
  # 리허설 결함 주입: 전환 직후 새 슬롯이 멈춘다.
  compose_quiet stop "api-${new_slot}"
fi
smoke_retry 'http://127.0.0.1' || fail_after_cutover CUTOVER POST_CUTOVER_SMOKE_FAILED
audit CUTOVER COMPLETED '' "${new_slot}" "${old_image}" || fail_after_cutover CUTOVER AUDIT_UNAVAILABLE

# --------------------------------------------------------------------------
# 10~11. 새 Worker, 이전 슬롯 정지
# --------------------------------------------------------------------------

say ''
say '10. 새 Worker 하나'
worker_run_image="${new_image}"
if ! compose_quiet up -d --no-deps --force-recreate worker || ! wait_healthy worker; then
  fail_after_cutover VERIFIED WORKER_START_FAILED
fi
telemetry_ok worker worker || fail_after_cutover VERIFIED WORKER_TELEMETRY_FAILED
say "  Worker(${new_image})"

say "11. 이전 슬롯 정지"
if [ -n "${old_slot}" ]; then
  remove_slot "${old_slot}"
  say "  api-${old_slot} 정지"
fi
phase=done

window=closed
if [ -n "${old_image}" ] && [ "${migration_kind}" = expand ]; then
  window=open
fi
previous="${old_image}"
if ! write_state "active_slot=${new_slot}" "active_image=${new_image}" "worker_image=${new_image}" \
  "previous_image=${previous}" "schema_version=${schema_now}" "rollback_window=${window}" \
  "last_operation=${operation}" "last_result=VERIFIED"; then
  say '::error::상태 파일을 쓰지 못했다. 수동 확인이 필요하다(전환은 끝났다).'
  exit 1
fi
audit VERIFIED COMPLETED '' "${new_slot}" "${old_image}" \
  || say '  경고  VERIFIED를 원장에 기록하지 못했다. 배포는 끝났다.'

# 배포는 제한 행을 쓰지 않는다. 시작·끝의 활성 제한 수를 원장에서 비교한다.
counts="$(psql_value "SELECT string_agg(details->>'activeRestrictions', ',' ORDER BY sequence) FROM operations_audit_event WHERE operation_id = '${operation}' AND reason IN ('GATE', 'VERIFIED')")"
case "${counts}" in
  *,*)
    if [ "${counts%%,*}" != "${counts##*,}" ]; then
      say "  경고  배포 중 활성 제한 수가 바뀌었다(${counts}). 운영자가 제한을 바꿨는지 확인한다."
    else
      say "  활성 제한 ${counts%%,*}건(시작·끝 같음)"
    fi
    ;;
esac

say ''
say "배포 완료: api-${new_slot}(${new_image}), rollback 창 ${window}"
exit 0
