#!/usr/bin/env bash
# 운영 배포 절차의 CI 리허설(Story 3.8).
#
# 왜 있는가: `deploy.sh`의 순서·rollback·gate·감사는 문서가 아니라 실행으로 고정해야 한다. 이 스크립트가 운영 compose에 개발
# override(`compose.rehearsal.yaml` — trust DB, `BOKRILOG_ENV=ci`)를 겹치고, 같은 jar로 만든 두 이미지(로컬 image ID)를 써서
# 실제 절차를 시나리오마다 끝까지 돈다. 리허설은 아키텍처·서명 검사만 건너뛰고(`--rehearsal`) 감사 행에 `rehearsal:true`를
# 남긴다. **이것은 운영 배포의 증거가 아니다** — 실제 운영 대상 smoke 전까지 운영 배포는 미검증이다.
#
# 실행: deploy/production/rehearse-deploy.sh
#
# 전제: `backend/build/libs/bokrilog-backend.jar`와 `frontend/dist/index.html`이 있다(이 스크립트는 둘 다 빌드하지 않는다 — CI가
# 두 품질 job의 산출물을 그대로 쓴다). docker·compose v2·curl. 개발 스택(`deploy/compose`)과 프로젝트 이름·포트가 달라 함께 떠도
# 겹치지 않는다(공개 포트 127.0.0.1:18090).
#
# 시나리오(SPEC I/O 표)
#   1. 정상 #1   첫 배포 → blue 활성, Worker 1(A), 감사 GATE REQUESTED → … → VERIFIED COMPLETED
#   2. 제한 중 정상 #2  GLOBAL SYNC 제한을 연 채 → green 활성, Worker 1(B), 제한 수 불변(행·감사 둘 다), rollback 창 열림
#   3. 두 번째 Worker  잠금 실패로 기동 실패, 돌던 Worker는 그대로 1
#   3b. drain 미완료  Worker drain 종료 코드 75(결함 주입) → 배포 중단, green·Worker(B) 그대로, DRAINED FAILED DRAIN_INCOMPLETE
#   4. 전환 전 실패  대기 슬롯 readiness 실패 → green 그대로, 대기 슬롯 제거, 이전 Worker(B) 재개, INACTIVE_VERIFIED FAILED
#   5. 전환 뒤 실패  전환 직후 새 슬롯 정지 → green으로 되돌림, Worker(B), CUTOVER FAILED 뒤 ROLLED_BACK
#   6. contract 거부  rollback 창이 열린 채 contract → gate 거부, 컨테이너 불변, GATE FAILED CONTRACT_WINDOW_OPEN
#   7. 창 닫기  `close-window` → 상태 파일의 rollback 창 닫힘, 감사 WINDOW_CLOSED COMPLETED
#
# 종료 코드
#   0 -- 모든 단정 통과
#   1 -- 단정 실패(`  실패` 줄)
#   2 -- 준비 실패(산출물·docker 없음, 이미지 빌드 실패)
#
# 스택·이미지·임시 디렉터리는 성공·실패·중단 어느 경로에서도 정리한다.
#
# `BOKRILOG_REHEARSAL_DIAGNOSTICS`(디렉터리)를 주면 실패했을 때 정리 전에 배포 기록·단계 로그·컨테이너 로그를 그곳에 옮겨 둔다
# (CI가 진단 artifact로 올린다). 리허설 비밀 파일은 옮기지 않는다.

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd "${script_dir}/../.." && pwd -P)"
# shellcheck source=lib.sh
. "${script_dir}/lib.sh"

backend_jar="${repo_root}/backend/build/libs/bokrilog-backend.jar"
dist_index="${repo_root}/frontend/dist/index.html"
project='bokrilog-rehearsal'
public_bind='127.0.0.1:18090'
public_url="http://${public_bind}"

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
  printf '\n::error::리허설 준비 실패: %s\n' "$1" >&2
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
[ -f "${dist_index}" ] || fail_setup "프런트엔드 산출물이 없다: ${dist_index}" '먼저 `npm --prefix frontend run build`를 실행한다.'
command -v docker >/dev/null 2>&1 || fail_setup 'docker가 없다.'
docker compose version >/dev/null 2>&1 || fail_setup 'docker compose v2를 쓸 수 없다.'
command -v curl >/dev/null 2>&1 || fail_setup 'curl이 없다.'
have_sha256_tool || fail_setup 'sha256sum 또는 shasum이 없다.'

work="$(mktemp -d)" || fail_setup '임시 디렉터리를 만들지 못했다.'
[ -d "${work}" ] || fail_setup "mktemp -d가 쓸 수 없는 경로를 냈다: '${work}'"
state_dir="${work}/state"
mkdir -p "${state_dir}"

image_a=''
image_b=''
cleaned=false

# 확인용 compose 호출. 이미지 변수는 읽기에만 쓰이므로 아무 이미지나 채운다.
compose() {
  BOKRILOG_STATE_DIR="${state_dir}" BOKRILOG_ENV=ci \
    BOKRILOG_IMAGE="${image_a:-none}" BOKRILOG_AUDIT_IMAGE="${image_a:-none}" BOKRILOG_BLUE_IMAGE="${image_a:-none}" \
    BOKRILOG_GREEN_IMAGE="${image_a:-none}" BOKRILOG_WORKER_IMAGE="${image_b:-${image_a:-none}}" \
    BOKRILOG_PUBLIC_BIND="${public_bind}" BOKRILOG_FRONTEND_DIST="${repo_root}/frontend/dist" \
    docker compose --project-name "${project}" --env-file "${secrets_file}" --file "${script_dir}/compose.yaml" \
    --file "${script_dir}/compose.rehearsal.yaml" "$@" </dev/null
}

# keep_diagnostics. 실패한 리허설의 기록을 진단 디렉터리로 옮긴다(비밀 파일 제외).
keep_diagnostics() {
  local target="${BOKRILOG_REHEARSAL_DIAGNOSTICS:-}"
  [ -n "${target}" ] || return 0
  mkdir -p "${target}/state-logs" || return 0
  cp "${work}"/*.log "${target}/" 2>/dev/null
  cp "${state_dir}"/logs/*.log "${target}/state-logs/" 2>/dev/null
  cp "${state_dir}/deploy.state" "${state_dir}/nginx/upstream-backend.conf" "${target}/" 2>/dev/null
  if [ -f "${secrets_file:-}" ]; then
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
  if [ -f "${secrets_file:-}" ]; then
    compose down --remove-orphans --volumes >/dev/null 2>&1 || true
  fi
  for image in "${image_a}" "${image_b}"; do
    [ -n "${image}" ] && docker image rm --force "${image}" >/dev/null 2>&1
  done
  rm -rf "${work}"
}
trap 'cleanup $?' EXIT
trap 'cleanup 130; exit 130' INT
trap 'cleanup 143; exit 143' TERM

printf '운영 배포 리허설\n\n'

# ---------------------------------------------------------------- 입력

printf '입력 준비\n'
build_image() {
  docker build --quiet --label "org.bokrilog.rehearsal=$1" --file "${repo_root}/backend/Dockerfile" \
    "${repo_root}/backend" 2>"${work}/build-$1.log" | tail -1
}
image_a="$(build_image a)"
matches "${image_a}" 'sha256:[0-9a-f]{64}' || fail_setup '이미지 A를 만들지 못했다.' "$(tail -20 "${work}/build-a.log")"
image_b="$(build_image b)"
matches "${image_b}" 'sha256:[0-9a-f]{64}' || fail_setup '이미지 B를 만들지 못했다.' "$(tail -20 "${work}/build-b.log")"
[ "${image_a}" != "${image_b}" ] || fail_setup '두 이미지의 ID가 같다.'
printf '  이미지 A %s\n  이미지 B %s\n' "${image_a}" "${image_b}"

# 이 이미지가 담은 마지막 migration 버전.
schema_version=0
for file in "${repo_root}"/backend/src/main/resources/db/migration/V*__*.sql; do
  name="${file##*/V}"
  number="${name%%__*}"
  if matches "${number}" '[0-9]+' && [ "${number}" -gt "${schema_version}" ]; then
    schema_version="${number}"
  fi
done
[ "${schema_version}" -gt 0 ] || fail_setup 'migration 파일을 찾지 못했다.'

# write_release <디렉터리> <이미지> <kind> <version>
write_release() {
  mkdir -p "$1"
  printf '{"spdxVersion":"SPDX-2.3","name":"rehearsal"}\n' >"$1/sbom.spdx.json"
  printf 'rehearsal-signature-bundle\n' >"$1/bundle.sig"
  {
    printf 'image=%s\n' "$2"
    printf 'platform=linux/arm64\n'
    printf 'migration_version=%s\n' "$4"
    printf 'migration_kind=%s\n' "$3"
    printf 'sbom=sbom.spdx.json\n'
    printf 'sbom_sha256=%s\n' "$(sha256_of "$1/sbom.spdx.json")"
    printf 'signature=bundle.sig\n'
    printf 'signature_sha256=%s\n' "$(sha256_of "$1/bundle.sig")"
  } >"$1/release.env"
}
write_release "${work}/release-a" "${image_a}" expand "${schema_version}"
write_release "${work}/release-b" "${image_b}" expand "${schema_version}"
write_release "${work}/release-contract" "${image_b}" contract "$((schema_version + 1))"

# 리허설 비밀. 값은 실행 시점에 만든다(자격정보 모양 리터럴을 커밋하지 않는다). DB는 trust라 비밀번호는 쓰이지 않는다.
secrets_file="${work}/production.env"
{
  printf 'BOKRILOG_DB_URL=jdbc:postgresql://postgres:5432/bokrilog\n'
  printf 'BOKRILOG_DB_USERNAME=bokrilog\n'
  printf 'BOKRILOG_DB_PASSWORD=%s\n' "$(random_hex 16)"
  printf 'POSTGRES_PASSWORD=%s\n' "$(random_hex 16)"
  printf 'BOKRILOG_PUBLIC_BASE_URL=%s\n' "${public_url}"
  printf 'BOKRILOG_AUDIT_DIGEST_KEY=%s\n' "$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
  printf 'BOKRILOG_TOMBSTONE_DIGEST_KEY=%s\n' "$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
  printf 'BOKRILOG_SOURCE_CREDENTIAL_KEYS=rehearsal:%s\n' "$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
  printf 'BOKRILOG_SOURCE_CREDENTIAL_ACTIVE_KEY_VERSION=rehearsal\n'
  printf 'BOKRILOG_BYBIT_BASE_URL=https://bybit.rehearsal.test\n'
  printf 'BOKRILOG_BYBIT_RESERVED_IP=203.0.113.10\n'
  printf 'BOKRILOG_SUPPORT_CONTACT=support@bokrilog.test\n'
  printf 'BOKRILOG_GOOGLE_CLIENT_ID=rehearsal-%s\n' "$(random_hex 8)"
  printf 'BOKRILOG_GOOGLE_CLIENT_SECRET=%s\n' "$(random_hex 24)"
} >"${secrets_file}"
chmod 600 "${secrets_file}"
host_facts="${work}/host-facts.env"
cp "${script_dir}/host-facts.env.example" "${host_facts}"

export BOKRILOG_STATE_DIR="${state_dir}"
export BOKRILOG_SECRETS_FILE="${secrets_file}"
export BOKRILOG_HOST_FACTS="${host_facts}"
export BOKRILOG_COMPOSE_OVERRIDE="${script_dir}/compose.rehearsal.yaml"
export BOKRILOG_PROJECT="${project}"
export BOKRILOG_PUBLIC_BIND="${public_bind}"
export BOKRILOG_FRONTEND_DIST="${repo_root}/frontend/dist"
export BOKRILOG_ENV=ci
# 배포 수행자는 비밀 파일이 아니라 배포를 돌리는 셸이 준다.
export BOKRILOG_DEPLOY_ACTOR=rehearsal-deployer
unset BOKRILOG_DEPLOY_FAULT

# ---------------------------------------------------------------- 관측 도우미

psql_value() {
  compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password --tuples-only --no-align \
    --command="$1" 2>>"${work}/psql.log" | tr -d '[:space:]'
}

last_operation=''
deploy_status=0

# run_deploy <라벨> <release 디렉터리> [결함]
run_deploy() {
  local log="${work}/deploy-$((tests_run + 1)).log"
  printf '\n%s\n' "$1"
  BOKRILOG_DEPLOY_FAULT="${3:-}" bash "${script_dir}/deploy.sh" deploy --release "$2/release.env" --rehearsal \
    >"${log}" 2>&1
  deploy_status=$?
  last_operation="$(awk '/^operation: / { print $2; exit }' "${log}")"
  last_log="${log}"
}

show_log_on_failure() {
  [ "${tests_failed}" -eq "$1" ] || { printf '        --- deploy 출력 ---\n'; sed 's/^/        /' "${last_log}" | tail -40; }
}

audit_trail() {
  psql_value "SELECT string_agg(reason || ':' || outcome || COALESCE(':' || (details->>'code'), ''), ',' ORDER BY sequence) FROM operations_audit_event WHERE operation_id = '$1'"
}

running_ids() {
  docker ps --quiet --filter "label=com.docker.compose.project=${project}" | sort | tr '\n' ' '
}

# service_images <서비스>. 그 서비스의 돌고 있는 컨테이너 image ID들.
service_images() {
  local id
  for id in $(docker ps --quiet --filter "label=com.docker.compose.project=${project}" \
    --filter "label=com.docker.compose.service=$1"); do
    docker inspect --format '{{.Image}}' "${id}"
  done | tr '\n' ' ' | sed 's/ $//'
}

active_restrictions() {
  psql_value "SELECT count(*) FROM operations_restriction WHERE effective_until IS NULL"
}

public_status() {
  curl --silent --max-time 10 --output /dev/null --write-out '%{http_code}' "${public_url}/api/status" || true
}

# assert_layout <라벨 접두사> <활성 슬롯> <활성 이미지> <Worker 이미지>
assert_layout() {
  local other=blue
  [ "$2" = blue ] && other=green
  assert_equals "$1 · 상태 파일의 활성 슬롯" "$(kv_get "${state_dir}/deploy.state" active_slot)" "$2"
  assert_equals "$1 · 상태 파일의 활성 이미지" "$(kv_get "${state_dir}/deploy.state" active_image)" "$3"
  if grep -F "server api-$2:8080 resolve;" "${state_dir}/nginx/upstream-backend.conf" >/dev/null 2>&1; then
    record_pass "$1 · upstream은 api-$2"
  else
    record_fail "$1 · upstream은 api-$2" "$(cat "${state_dir}/nginx/upstream-backend.conf" 2>/dev/null)"
  fi
  assert_equals "$1 · 돌고 있는 api-$2" "$(service_images "api-$2")" "$3"
  assert_equals "$1 · api-${other}는 없다" "$(service_images "api-${other}")" ''
  assert_equals "$1 · Worker는 하나이고 기대한 이미지다" "$(service_images worker)" "$4"
  assert_equals "$1 · 공개 진입점 /api/status" "$(public_status)" '200'
}

assert_rehearsal_marked() {
  assert_equals "$1 · 모든 감사 행에 rehearsal:true" \
    "$(psql_value "SELECT count(*) FROM operations_audit_event WHERE operation_id = '$2' AND (details->>'rehearsal') IS DISTINCT FROM 'true'")" '0'
  assert_equals "$1 · 감사 행은 SYSTEM·deploy·DEPLOY" \
    "$(psql_value "SELECT count(*) FROM operations_audit_event WHERE operation_id = '$2' AND NOT (actor_kind = 'SYSTEM' AND command = 'deploy' AND scope = 'DEPLOY' AND actor_user_id IS NULL)")" '0'
}

normal_trail='GATE:REQUESTED,DRAINED:COMPLETED,MIGRATED:COMPLETED,INACTIVE_VERIFIED:COMPLETED,CUTOVER:COMPLETED,VERIFIED:COMPLETED'

# ---------------------------------------------------------------- 1. 정상 #1(첫 배포)

failed_before="${tests_failed}"
run_deploy '1. 정상 배포 #1(첫 배포, 이미지 A)' "${work}/release-a"
assert_equals '정상 #1 · 종료 0' "${deploy_status}" '0'
assert_layout '정상 #1' blue "${image_a}" "${image_a}"
assert_equals '정상 #1 · 감사 단계 순서' "$(audit_trail "${last_operation}")" "${normal_trail}"
assert_rehearsal_marked '정상 #1' "${last_operation}"
assert_equals '정상 #1 · 첫 배포는 rollback 창이 닫혀 있다' "$(kv_get "${state_dir}/deploy.state" rollback_window)" 'closed'
assert_equals '정상 #1 · schema 최대 버전' "$(kv_get "${state_dir}/deploy.state" schema_version)" "${schema_version}"
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 2. 제한 중 정상 #2

printf '\nGLOBAL SYNC 제한을 연다(리허설 fixture)\n'
compose exec -T postgres psql --username=bokrilog --dbname=bokrilog --no-password --quiet --set=ON_ERROR_STOP=1 \
  --command="INSERT INTO operations_restriction (restriction_id, feature, scope, target, operation_id, origin, effective_from) VALUES (gen_random_uuid(), 'SYNC', 'GLOBAL', 'ALL', 'op_rehearsal_restriction', 'OPERATOR', statement_timestamp())" \
  >>"${work}/psql.log" 2>&1 || fail_setup '리허설 제한을 만들지 못했다.' "$(tail -5 "${work}/psql.log")"
restrictions_before="$(active_restrictions)"
assert_equals '제한 fixture · 활성 제한 1' "${restrictions_before}" '1'

failed_before="${tests_failed}"
run_deploy '2. 제한 중 정상 배포 #2(이미지 B)' "${work}/release-b"
assert_equals '정상 #2 · 종료 0' "${deploy_status}" '0'
assert_layout '정상 #2' green "${image_b}" "${image_b}"
assert_equals '정상 #2 · 감사 단계 순서' "$(audit_trail "${last_operation}")" "${normal_trail}"
assert_rehearsal_marked '정상 #2' "${last_operation}"
assert_equals '정상 #2 · 활성 제한 수 불변(행)' "$(active_restrictions)" "${restrictions_before}"
assert_equals '정상 #2 · 활성 제한 수 불변(감사 GATE·VERIFIED)' \
  "$(psql_value "SELECT string_agg(details->>'activeRestrictions', ',' ORDER BY sequence) FROM operations_audit_event WHERE operation_id = '${last_operation}' AND reason IN ('GATE', 'VERIFIED')")" \
  "${restrictions_before},${restrictions_before}"
assert_equals '정상 #2 · rollback 창이 열렸다' "$(kv_get "${state_dir}/deploy.state" rollback_window)" 'open'
assert_equals '정상 #2 · 이전 이미지는 A' "$(kv_get "${state_dir}/deploy.state" previous_image)" "${image_a}"
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 3. 두 번째 Worker

printf '\n3. 두 번째 Worker\n'
# 확인용 compose의 Worker 이미지는 B다(지금 돌고 있는 Worker와 같은 digest). 떼어 띄우고 끝날 때까지만 기다린다 — 잠금 판정이
# 깨져 두 번째 Worker가 뜨면 이 시험이 그 Worker를 붙든 채 멈추지 않게 상한(120초)을 두고, 그때는 실패로 적고 지운다.
second_id="$(compose run --detach --no-deps worker 2>"${work}/second-worker-start.log" | tail -1)"
second_status=''
if [ -n "${second_id}" ]; then
  waited=0
  while [ "${waited}" -lt 120 ]; do
    if [ "$(docker inspect --format '{{.State.Running}}' "${second_id}" 2>/dev/null)" != true ]; then
      second_status="$(docker inspect --format '{{.State.ExitCode}}' "${second_id}" 2>/dev/null)"
      break
    fi
    sleep 2
    waited=$((waited + 2))
  done
  docker logs "${second_id}" >"${work}/second-worker.log" 2>&1
  docker rm --force "${second_id}" >/dev/null 2>&1
fi
if [ -z "${second_id}" ]; then
  record_fail '두 번째 Worker를 띄웠다' "$(tail -5 "${work}/second-worker-start.log")"
elif [ -z "${second_status}" ]; then
  record_fail '두 번째 Worker는 잠금 실패로 끝난다' '120초 안에 끝나지 않았다(지웠다).'
elif [ "${second_status}" != 0 ]; then
  record_pass "두 번째 Worker는 잠금 실패로 끝난다(종료 ${second_status})"
else
  record_fail '두 번째 Worker는 잠금 실패로 끝난다' '종료 0으로 끝났다.'
fi
if grep -F 'worker.lock.unavailable' "${work}/second-worker.log" >/dev/null; then
  record_pass '두 번째 Worker의 로그에 worker.lock.unavailable'
else
  record_fail '두 번째 Worker의 로그에 worker.lock.unavailable' "$(tail -5 "${work}/second-worker.log")"
fi
assert_equals '두 번째 Worker 뒤에도 Worker는 하나(B)' "$(service_images worker)" "${image_b}"

# ---------------------------------------------------------------- 3b. drain 미완료

failed_before="${tests_failed}"
run_deploy '3b. drain 미완료(종료 코드 75, 이미지 A)' "${work}/release-a" drain-incomplete
assert_equals 'drain 미완료 · 종료 1' "${deploy_status}" '1'
assert_layout 'drain 미완료' green "${image_b}" "${image_b}"
assert_equals 'drain 미완료 · 감사 단계 순서' "$(audit_trail "${last_operation}")" \
  'GATE:REQUESTED,DRAINED:FAILED:DRAIN_INCOMPLETE'
assert_rehearsal_marked 'drain 미완료' "${last_operation}"
assert_equals 'drain 미완료 · 활성 제한 수 불변' "$(active_restrictions)" "${restrictions_before}"
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 4. 전환 전 실패

failed_before="${tests_failed}"
run_deploy '4. 전환 전 실패(대기 슬롯 readiness, 이미지 A)' "${work}/release-a" inactive-readiness
assert_equals '전환 전 실패 · 종료 1' "${deploy_status}" '1'
assert_layout '전환 전 실패' green "${image_b}" "${image_b}"
assert_equals '전환 전 실패 · 감사 단계 순서' "$(audit_trail "${last_operation}")" \
  'GATE:REQUESTED,DRAINED:COMPLETED,MIGRATED:COMPLETED,INACTIVE_VERIFIED:FAILED:INACTIVE_READINESS_FAILED'
assert_rehearsal_marked '전환 전 실패' "${last_operation}"
assert_equals '전환 전 실패 · 활성 제한 수 불변' "$(active_restrictions)" "${restrictions_before}"
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 5. 전환 뒤 실패 → rollback

failed_before="${tests_failed}"
run_deploy '5. 전환 뒤 실패 → rollback(이미지 A)' "${work}/release-a" post-cutover-smoke
assert_equals '전환 뒤 실패 · 종료 1' "${deploy_status}" '1'
assert_layout '전환 뒤 실패' green "${image_b}" "${image_b}"
assert_equals '전환 뒤 실패 · 감사 단계 순서' "$(audit_trail "${last_operation}")" \
  'GATE:REQUESTED,DRAINED:COMPLETED,MIGRATED:COMPLETED,INACTIVE_VERIFIED:COMPLETED,CUTOVER:FAILED:POST_CUTOVER_SMOKE_FAILED,ROLLED_BACK:COMPLETED'
assert_rehearsal_marked '전환 뒤 실패' "${last_operation}"
assert_equals '전환 뒤 실패 · 활성 제한 수 불변' "$(active_restrictions)" "${restrictions_before}"
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 6. contract 거부

failed_before="${tests_failed}"
ids_before="$(running_ids)"
run_deploy '6. rollback 창이 열린 채 contract' "${work}/release-contract"
assert_equals 'contract 거부 · 종료 1' "${deploy_status}" '1'
assert_equals 'contract 거부 · 컨테이너가 바뀌지 않았다' "$(running_ids)" "${ids_before}"
assert_equals 'contract 거부 · 감사 GATE FAILED' "$(audit_trail "${last_operation}")" 'GATE:FAILED:CONTRACT_WINDOW_OPEN'
assert_equals 'contract 거부 · 활성 슬롯 그대로' "$(kv_get "${state_dir}/deploy.state" active_slot)" 'green'
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 7. 창 닫기

printf '\n7. rollback 창 닫기\n'
failed_before="${tests_failed}"
ids_before="$(running_ids)"
last_log="${work}/close-window.log"
close_status=0
bash "${script_dir}/deploy.sh" close-window >"${last_log}" 2>&1 || close_status=$?
last_operation="$(awk '/^operation: / { print $2; exit }' "${last_log}")"
assert_equals '창 닫기 · 종료 0' "${close_status}" '0'
assert_equals '창 닫기 · 상태 파일의 rollback 창' "$(kv_get "${state_dir}/deploy.state" rollback_window)" 'closed'
assert_equals '창 닫기 · 감사 WINDOW_CLOSED' "$(audit_trail "${last_operation}")" 'WINDOW_CLOSED:COMPLETED'
assert_equals '창 닫기 · 감사 target은 활성 이미지' \
  "$(psql_value "SELECT target FROM operations_audit_event WHERE operation_id = '${last_operation}'")" "${image_b}"
assert_equals '창 닫기 · 컨테이너가 바뀌지 않았다' "$(running_ids)" "${ids_before}"
show_log_on_failure "${failed_before}"

# ---------------------------------------------------------------- 판정

printf '\n%s개 중 %s개 통과, %s개 실패\n' "${tests_run}" "$((tests_run - tests_failed))" "${tests_failed}"
printf '이 결과는 CI 리허설이다. 실제 운영 대상 smoke 전까지 운영 배포는 미검증이다.\n'
if [ "${tests_failed}" -ne 0 ]; then
  printf '::error::배포 리허설이 실패했다.\n'
  exit 1
fi
exit 0
