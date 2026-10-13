#!/usr/bin/env bash
# 운영 배포의 사전 gate(Story 3.8 AC-1·AC-2).
#
# 왜 있는가: 배포가 컨테이너를 하나라도 바꾸기 전에 "이 입력으로 배포해도 되는가"를 한 번에 판정한다. 판정은 이 스크립트가
# 소유하고 `deploy.sh`는 그 종료 코드만 본다. 고정 입력 시험은 `test-preflight.sh`가 한다.
#
# 실행: deploy/production/preflight.sh --release <release.env> --host-facts <host-facts.env> --secrets <운영 env 파일>
#                                      --compose <compose.yaml> [--state <deploy.state>] [--actor <이름>]
#                                      [--project <compose 프로젝트>] [--override <compose 파일>] [--rehearsal]
#
#   --state      이전 배포의 상태 파일. 없으면 첫 배포다(rollback 창 닫힘·schema 0).
#   --actor      배포 수행자 이름(없으면 `BOKRILOG_DEPLOY_ACTOR`). 배포를 돌리는 셸이 준다 — 비밀 파일의 공유 값이 아니다.
#   --project    compose 프로젝트 이름(없으면 `BOKRILOG_PROJECT`, 그것도 없으면 `bokrilog-production`).
#   --override   겹치는 compose 파일(리허설만).
#   --rehearsal  CI 리허설(`rehearse-deploy.sh`). 실제 이미지 아키텍처·`cosign verify`·`cosign verify-attestation` 셋만 건너뛰고,
#                image ID(`sha256:<64hex>`) 참조를 받는다. `BOKRILOG_ENV`가 `ci`·`development`이고 override가 있을 때만 받는다.
#
# 검사
#   모드      리허설 ⇒ `BOKRILOG_ENV` ∈ {ci, development} + override 있음. 운영 ⇒ `BOKRILOG_ENV` 비었거나 `production` +
#             override 없음. 결함 주입 `BOKRILOG_DEPLOY_FAULT`는 그 리허설에서만. 프로젝트 이름 모양, 배포 수행자 모양
#   release   키 집합(알 수 없는 키·중복·누락), image 형식(`<repo>@sha256:<64hex>`, 저장소 180자까지 — tag·`latest` 금지),
#             platform `linux/arm64`, migration 버전·종류, SBOM·서명 파일의 SHA-256, 실제 이미지 아키텍처, `cosign verify`와
#             SBOM attestation(`cosign verify-attestation --type spdxjson`). 서명 신원은 manifest가 아니라 운영자가 소유한 host 사실의
#             `signer_identity`·`signer_issuer`다 — manifest를 고친 사람이 신원을 고르지 못하게
#   migration contract는 rollback 창이 닫혀 있고 migration을 정확히 하나 더할 때만, schema 역행은 rollback 창 안의 이전 이미지일
#             때만 받는다
#   host      home region = region, A1.Flex 2 OCPU/12GB, reserved IPv4, Always Free, 예상 월 비용 0, 서명 신원(운영)
#   자원      compose의 `mem_limit`(API 768m·Worker 512m·PostgreSQL 3g·Nginx 128m, 일회성 역할 ≤ Worker), Alloy 256MiB 예약,
#             blue/green peak(API×2 + 나머지) + OS 예약 ≤ 선언 메모리
#   compose   앱 이미지는 변수, 인프라 이미지는 `@sha256:` 고정, `build`·`env_file` 없음, API·Worker에 migrate 프로필 없음, 역할 고정,
#             일회성 역할(migrate·deploy-audit·backup-record) ≤ Worker 예산
#   비밀      파일 권한 600, 필수 이름이 비지 않음, 로그인 제공자 하나 이상, 프로필·역할·환경·Worker 설정·배포 수행자를 덮는
#             이름(`SPRING_PROFILES_*`·`SPRING_APPLICATION_JSON`·`BOKRILOG_ROLE`·`BOKRILOG_ENV`·`BOKRILOG_SYNC_WORKER_*`·
#             `BOKRILOG_DEPLOY_ACTOR`) 없음, backup key(age identity) 모양의 값 없음(Story 3.9)
#
# 종료 코드
#   0 -- 모든 검사 통과
#   1 -- 거부. 거부마다 `  거부  <CODE>  <설명>` 한 줄. `deploy.sh`는 첫 CODE를 감사 행에 남긴다.
#   2 -- 준비 실패(입력 파일·도구 없음). 아무 검사도 판정하지 않았다.

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib.sh
. "${script_dir}/lib.sh"

# 자원 표(AC-2). compose와 이 표가 갈라지면 gate가 거부한다.
expected_api_mib=768
expected_worker_mib=512
expected_postgres_mib=3072
expected_nginx_mib=128
# Grafana Alloy는 아직 compose에 없지만(후속 작업) 예약으로 계상한다.
alloy_reserved_mib=256

release_file=''
host_facts_file=''
secrets_file=''
compose_file=''
state_file=''
rehearsal=false
actor="${BOKRILOG_DEPLOY_ACTOR:-}"
project="${BOKRILOG_PROJECT:-bokrilog-production}"
override_file=''

refusals=0
checks=0

prep_fail() {
  printf '::error::gate 준비 실패: %s\n' "$1" >&2
  exit 2
}

pass() {
  checks=$((checks + 1))
  printf '  통과  %s\n' "$1"
}

refuse() {
  checks=$((checks + 1))
  refusals=$((refusals + 1))
  printf '  거부  %s  %s\n' "$1" "$2"
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --release | --host-facts | --secrets | --compose | --state | --actor | --project | --override)
      # 값을 받는 인자가 마지막에 값 없이 오면 `shift 2`가 실패해 끝없이 돈다. 먼저 막는다.
      [ "$#" -ge 2 ] || prep_fail "$1에 값이 없다."
      case "$1" in
        --release) release_file="$2" ;;
        --host-facts) host_facts_file="$2" ;;
        --secrets) secrets_file="$2" ;;
        --compose) compose_file="$2" ;;
        --state) state_file="$2" ;;
        --actor) actor="$2" ;;
        --project) project="$2" ;;
        --override) override_file="$2" ;;
      esac
      shift 2
      ;;
    --rehearsal) rehearsal=true; shift ;;
    *) prep_fail "알 수 없는 인자: $1" ;;
  esac
done

for pair in "release:${release_file}" "host-facts:${host_facts_file}" "secrets:${secrets_file}" "compose:${compose_file}"; do
  name="${pair%%:*}"
  path="${pair#*:}"
  [ -n "${path}" ] || prep_fail "--${name}가 없다."
  [ -f "${path}" ] || prep_fail "${name} 파일이 없다: ${path}"
done
have_sha256_tool || prep_fail 'sha256sum 또는 shasum이 없다.'
if [ "${rehearsal}" = false ]; then
  command -v docker >/dev/null 2>&1 || prep_fail 'docker가 없다(이미지 아키텍처 검사).'
  command -v cosign >/dev/null 2>&1 || prep_fail 'cosign이 없다(서명 검사). 운영 서버에 고정 버전을 설치한다.'
fi

printf 'gate (%s)\n' "$([ "${rehearsal}" = true ] && printf '리허설' || printf '운영')"

# ---------------------------------------------------------------- 모드·결함 주입

# 리허설은 그것이 리허설임을 두 가지로 보여야 한다 — 개발·CI 환경 값과 리허설 override. 운영 셸에서 `BOKRILOG_ENV`는 대개
# 비어 있으므로 환경 값 하나만 보면 `--rehearsal` 하나로 아키텍처·서명 검사를 건너뛸 수 있게 된다.
rehearsal_ok=false
case "${BOKRILOG_ENV:-}" in
  ci | development) [ -n "${override_file}" ] && [ -f "${override_file}" ] && rehearsal_ok=true ;;
esac
if [ "${rehearsal}" = true ]; then
  if [ "${rehearsal_ok}" = true ]; then
    pass "리허설 모드(BOKRILOG_ENV=${BOKRILOG_ENV}, override 있음)"
  else
    refuse REHEARSAL_IN_PRODUCTION '리허설은 BOKRILOG_ENV가 ci·development이고 리허설 override가 있을 때만 받는다.'
  fi
else
  case "${BOKRILOG_ENV:-}" in
    '' | production) pass '운영 모드 환경 값' ;;
    *) refuse ENV_NOT_PRODUCTION "운영 모드인데 BOKRILOG_ENV가 ${BOKRILOG_ENV}이다." ;;
  esac
  if [ -n "${override_file}" ]; then
    refuse OVERRIDE_IN_PRODUCTION '운영 모드에 겹치는 compose 파일이 있다.'
  fi
fi
if [ -n "${BOKRILOG_DEPLOY_FAULT:-}" ]; then
  if [ "${rehearsal}" = true ] && [ "${rehearsal_ok}" = true ]; then
    pass "결함 주입(${BOKRILOG_DEPLOY_FAULT})은 리허설에서만 쓴다"
  else
    refuse FAULT_INJECTION_IN_PRODUCTION '결함 주입 변수는 운영에서 거부한다.'
  fi
fi
if matches "${project}" 'bokrilog-[a-z][a-z0-9-]{0,39}'; then
  pass "compose 프로젝트 ${project}"
else
  refuse PROJECT_INVALID 'compose 프로젝트 이름은 bokrilog-<소문자·숫자·->여야 한다.'
fi
if matches "${actor}" '[A-Za-z0-9._@-]{1,128}'; then
  pass '배포 수행자 이름'
else
  refuse DEPLOY_ACTOR_INVALID '배포 수행자(--actor·BOKRILOG_DEPLOY_ACTOR)가 없거나 모양이 틀렸다.'
fi

# ---------------------------------------------------------------- release manifest

# 서명 신원(signer_identity·signer_issuer)은 여기 없다 — manifest에 있으면 알 수 없는 키로 거부한다(host 사실이 소유한다).
release_keys='image platform migration_version migration_kind sbom sbom_sha256 signature signature_sha256'
release_dir="$(cd "$(dirname "${release_file}")" && pwd -P)"

unknown_entry=''
duplicate_entry=''
seen=' '
while IFS= read -r key; do
  [ -n "${key}" ] || continue
  case " ${release_keys} " in
    *" ${key} "*) ;;
    *) unknown_entry="${key}" ;;
  esac
  case "${seen}" in
    *" ${key} "*) duplicate_entry="${key}" ;;
  esac
  seen="${seen}${key} "
done <<KEYS
$(kv_keys "${release_file}")
KEYS
if [ -n "${unknown_entry}" ]; then
  refuse RELEASE_KEY_UNKNOWN "release에 알 수 없는 키가 있다: ${unknown_entry}"
elif [ -n "${duplicate_entry}" ]; then
  refuse RELEASE_KEY_DUPLICATE "release에 같은 키가 두 번 있다: ${duplicate_entry}"
else
  pass 'release 키 집합'
fi

required_release="${release_keys}"
missing_release=''
for key in ${required_release}; do
  [ -n "$(kv_get "${release_file}" "${key}")" ] || missing_release="${missing_release} ${key}"
done
if [ -n "${missing_release}" ]; then
  refuse RELEASE_KEY_MISSING "release에 값이 없는 키:${missing_release}"
fi

image="$(kv_get "${release_file}" image)"
# 저장소 부분에 tag가 없어야 한다 — 마지막 `/` 뒤에 `:`가 있으면 tag다(앞쪽 `host:port`는 레지스트리 주소).
image_repo="${image%@*}"
image_last="${image_repo##*/}"
# 저장소 부분은 180자까지다 — 감사 행의 target(256자)에 digest와 함께 언제나 들어간다.
if matches "${image}" '[a-z0-9][a-z0-9._/:-]{0,179}@sha256:[0-9a-f]{64}' \
  && ! matches "${image_last}" '.*:.*' && ! matches "${image}" '.*latest.*'; then
  pass "image는 digest 참조다"
elif [ "${rehearsal}" = true ] && matches "${image}" 'sha256:[0-9a-f]{64}'; then
  pass 'image는 리허설 image ID 참조다'
else
  refuse IMAGE_REFERENCE_INVALID 'image는 <repo>@sha256:<64자 16진>이어야 한다(tag·latest 금지).'
fi

platform="$(kv_get "${release_file}" platform)"
if [ "${platform}" = 'linux/arm64' ]; then
  pass 'platform linux/arm64'
else
  refuse PLATFORM_INVALID "platform이 linux/arm64가 아니다: ${platform}"
fi

migration_version="$(kv_get "${release_file}" migration_version)"
if matches "${migration_version}" '[1-9][0-9]{0,5}'; then
  pass "migration_version ${migration_version}"
else
  refuse MIGRATION_VERSION_INVALID 'migration_version은 양의 정수여야 한다.'
  migration_version=''
fi

migration_kind="$(kv_get "${release_file}" migration_kind)"
case "${migration_kind}" in
  expand | contract) pass "migration_kind ${migration_kind}" ;;
  *) refuse MIGRATION_KIND_INVALID 'migration_kind는 expand 또는 contract여야 한다.' ;;
esac

# check_artifact <이름 키> <digest 키> <code 접두사>
check_artifact() {
  local name digest path actual
  name="$(kv_get "${release_file}" "$1")"
  digest="$(kv_get "${release_file}" "$2")"
  case "${name}" in
    '' | */* | .*)
      refuse "$3_MISSING" "$1은 release와 같은 디렉터리의 파일 이름이어야 한다."
      return
      ;;
  esac
  path="${release_dir}/${name}"
  if [ ! -f "${path}" ]; then
    refuse "$3_MISSING" "$1 파일이 없다: ${name}"
    return
  fi
  actual="$(sha256_of "${path}")"
  if matches "${digest}" '[0-9a-f]{64}' && [ "${actual}" = "${digest}" ]; then
    pass "$1 SHA-256 일치"
  else
    refuse "$3_DIGEST_MISMATCH" "$1의 SHA-256이 $2와 다르다."
  fi
}
check_artifact sbom sbom_sha256 SBOM
check_artifact signature signature_sha256 SIGNATURE

signer_identity="$(kv_get "${host_facts_file}" signer_identity)"
signer_issuer="$(kv_get "${host_facts_file}" signer_issuer)"
if [ "${rehearsal}" = true ]; then
  pass '리허설: 실제 이미지 아키텍처·cosign 서명·SBOM attestation 검사를 건너뛴다'
elif [ -z "${signer_identity}" ] || [ -z "${signer_issuer}" ]; then
  refuse SIGNER_IDENTITY_MISSING 'host 사실에 서명 신원(signer_identity·signer_issuer)이 없다.'
else
  actual_platform="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "${image}" 2>/dev/null)"
  if [ -z "${actual_platform}" ]; then
    refuse IMAGE_UNAVAILABLE '이미지를 이 호스트에서 찾을 수 없다(먼저 digest로 pull한다).'
  elif [ "${actual_platform}" = 'linux/arm64' ]; then
    pass '실제 이미지 아키텍처 linux/arm64'
  else
    refuse IMAGE_ARCHITECTURE_MISMATCH "실제 이미지 아키텍처가 ${actual_platform}이다."
  fi
  if cosign verify --certificate-identity "${signer_identity}" --certificate-oidc-issuer "${signer_issuer}" \
    "${image}" >/dev/null 2>&1; then
    pass 'cosign verify'
  else
    refuse SIGNATURE_INVALID 'cosign verify가 실패했다.'
  fi
  # SBOM도 같은 신원이 그 이미지에 붙인 attestation이어야 한다. 파일의 SHA-256(위)은 받은 파일이 manifest와 같다는 것까지만 본다.
  if cosign verify-attestation --type spdxjson --certificate-identity "${signer_identity}" \
    --certificate-oidc-issuer "${signer_issuer}" "${image}" >/dev/null 2>&1; then
    pass 'cosign verify-attestation(SBOM, spdxjson)'
  else
    refuse SBOM_ATTESTATION_INVALID 'SBOM attestation 검증(cosign verify-attestation --type spdxjson)이 실패했다.'
  fi
fi

# ---------------------------------------------------------------- migration 종류와 rollback 창

window='closed'
schema_version=0
previous_image=''
if [ -n "${state_file}" ] && [ -f "${state_file}" ]; then
  window="$(kv_get "${state_file}" rollback_window)"
  schema_version="$(kv_get "${state_file}" schema_version)"
  previous_image="$(kv_get "${state_file}" previous_image)"
  [ -n "${window}" ] || window='closed'
  matches "${schema_version}" '[0-9]+' || schema_version=0
fi

if [ -n "${migration_version}" ]; then
  if [ "${migration_kind}" = 'contract' ]; then
    if [ "${window}" = 'open' ]; then
      refuse CONTRACT_WINDOW_OPEN 'rollback 창이 열려 있다. `deploy.sh close-window` 뒤에 contract를 배포한다.'
    elif [ "${migration_version}" -ne $((schema_version + 1)) ]; then
      refuse CONTRACT_NOT_ISOLATED 'contract 배포는 migration을 정확히 하나만 더한다(expand와 섞지 않는다).'
    else
      pass 'contract: rollback 창 닫힘, migration 하나'
    fi
  elif [ "${migration_version}" -lt "${schema_version}" ]; then
    if [ "${window}" = 'open' ] && [ -n "${previous_image}" ] && [ "${image}" = "${previous_image}" ]; then
      pass 'rollback 창 안의 이전 이미지(old API + new schema)'
    else
      refuse MIGRATION_DOWNGRADE "schema ${schema_version}보다 낮은 이미지다. rollback 창 안의 이전 이미지만 받는다."
    fi
  else
    pass "expand: schema ${schema_version} → ${migration_version}"
  fi
fi

# ---------------------------------------------------------------- host 사실(AC-2)

host_keys='home_region region shape ocpus memory_gb reserved_ip always_free expected_monthly_cost_krw os_reserved_mib'
missing_host=''
for key in ${host_keys}; do
  [ -n "$(kv_get "${host_facts_file}" "${key}")" ] || missing_host="${missing_host} ${key}"
done
if [ -n "${missing_host}" ]; then
  refuse HOST_FACT_MISSING "host 사실에 값이 없는 키:${missing_host}"
fi

home_region="$(kv_get "${host_facts_file}" home_region)"
region="$(kv_get "${host_facts_file}" region)"
if [ -n "${home_region}" ] && [ "${home_region}" = "${region}" ]; then
  pass "home region ${home_region}"
else
  refuse HOST_REGION_INVALID 'region이 home region과 다르다(Always Free A1은 home region에서만).'
fi
if [ "$(kv_get "${host_facts_file}" shape)" = 'VM.Standard.A1.Flex' ] \
  && [ "$(kv_get "${host_facts_file}" ocpus)" = '2' ] && [ "$(kv_get "${host_facts_file}" memory_gb)" = '12' ]; then
  pass 'A1.Flex 2 OCPU / 12GB'
else
  refuse HOST_CAPACITY_INVALID 'shape·OCPU·메모리가 VM.Standard.A1.Flex 2 OCPU/12GB가 아니다.'
fi
if matches "$(kv_get "${host_facts_file}" reserved_ip)" '([0-9]{1,3}\.){3}[0-9]{1,3}'; then
  pass 'reserved IPv4'
else
  refuse RESERVED_IP_INVALID 'reserved_ip가 IPv4 주소가 아니다.'
fi
if [ "$(kv_get "${host_facts_file}" always_free)" = 'true' ]; then
  pass 'Always Free 표시'
else
  refuse NOT_ALWAYS_FREE 'Always Free 표시가 없다.'
fi
if [ "$(kv_get "${host_facts_file}" expected_monthly_cost_krw)" = '0' ]; then
  pass '예상 월 비용 0'
else
  refuse COST_NOT_ZERO '예상 월 비용이 0이 아니다.'
fi

# ---------------------------------------------------------------- 자원 상한과 peak(AC-2)

# check_limit <서비스> <기대 MiB> <비교: eq|le>
limit_ok=true
check_limit() {
  local raw mib
  raw="$(compose_service_value "${compose_file}" "$1" mem_limit)"
  mib="$(mib_of "${raw}")"
  if [ -z "${mib}" ]; then
    refuse MEMORY_LIMIT_MISMATCH "$1의 mem_limit이 없거나 읽을 수 없다: '${raw}'"
    limit_ok=false
  elif { [ "$3" = eq ] && [ "${mib}" -ne "$2" ]; } || { [ "$3" = le ] && [ "${mib}" -gt "$2" ]; }; then
    refuse MEMORY_LIMIT_MISMATCH "$1의 mem_limit ${mib}MiB가 표(${2}MiB)와 다르다."
    limit_ok=false
  fi
}
check_limit api-blue "${expected_api_mib}" eq
check_limit api-green "${expected_api_mib}" eq
check_limit worker "${expected_worker_mib}" eq
check_limit postgres "${expected_postgres_mib}" eq
check_limit nginx "${expected_nginx_mib}" eq
check_limit migrate "${expected_worker_mib}" le
check_limit deploy-audit "${expected_worker_mib}" le
check_limit backup-record "${expected_worker_mib}" le
if [ "${limit_ok}" = true ]; then
  pass 'mem_limit: API 768m·Worker 512m·PostgreSQL 3g·Nginx 128m·일회성 ≤ 512m'
fi

memory_gb="$(kv_get "${host_facts_file}" memory_gb)"
os_reserved="$(kv_get "${host_facts_file}" os_reserved_mib)"
peak=$((expected_api_mib * 2 + expected_worker_mib + expected_postgres_mib + expected_nginx_mib + alloy_reserved_mib))
if matches "${memory_gb}" '[0-9]+' && matches "${os_reserved}" '[0-9]+'; then
  if [ $((peak + os_reserved)) -le $((memory_gb * 1024)) ]; then
    pass "blue/green peak ${peak}MiB + OS ${os_reserved}MiB ≤ ${memory_gb}GB"
  else
    refuse PEAK_EXCEEDS_MEMORY "blue/green peak ${peak}MiB + OS ${os_reserved}MiB가 ${memory_gb}GB를 넘는다."
  fi
else
  refuse PEAK_EXCEEDS_MEMORY 'memory_gb·os_reserved_mib가 정수가 아니어서 peak를 판정할 수 없다.'
fi

# ---------------------------------------------------------------- compose 계약

unpinned=''
while IFS= read -r line; do
  [ -n "${line}" ] || continue
  value="${line#*image:}"
  value="${value# }"
  case "${value}" in
    '${BOKRILOG_'*'IMAGE:?'*'}') ;;
    *@sha256:*) matches "${value}" '[a-z0-9][a-z0-9._/:-]*@sha256:[0-9a-f]{64}' || unpinned="${unpinned} ${value}" ;;
    *) unpinned="${unpinned} ${value}" ;;
  esac
done <<IMAGES
$(grep -E '^    image:' "${compose_file}")
IMAGES
if [ -n "${unpinned}" ]; then
  refuse IMAGE_NOT_PINNED "digest로 고정하지 않은 이미지:${unpinned}"
else
  pass 'compose 이미지는 변수 또는 digest 고정'
fi
if grep -E '^    build:' "${compose_file}" >/dev/null; then
  refuse BUILD_IN_PRODUCTION '운영 compose에 build가 있다(운영 서버에서 이미지를 빌드하지 않는다).'
else
  pass 'compose에 build 없음'
fi
if grep -E '^    env_file:' "${compose_file}" >/dev/null; then
  refuse ENV_FILE_IN_COMPOSE '운영 compose가 env_file로 비밀 파일 전체를 넘긴다(서비스는 이름으로 고른 값만 받는다).'
else
  pass 'compose에 env_file 없음'
fi

role_ok=true
for service in api-blue api-green worker; do
  block="$(compose_service_block "${compose_file}" "${service}")"
  # `api-green`은 `api-blue`의 환경을 YAML anchor로 나눠 쓴다. 그 alias면 원본 블록의 환경으로 판정한다.
  if printf '%s\n' "${block}" | grep -E '^    environment: \*api-environment$' >/dev/null; then
    block="${block}
$(compose_service_block "${compose_file}" api-blue | awk '/^    environment: &api-environment$/ { on = 1; next } on && /^    [^ ]/ { on = 0 } on { print }')"
  fi
  if printf '%s\n' "${block}" | grep -E 'migrate' >/dev/null; then
    refuse MIGRATE_PROFILE_IN_SERVICE "${service}에 migrate 프로필이 있다(startup migration 금지)."
    role_ok=false
  fi
  want_role=api
  [ "${service}" = worker ] && want_role=worker
  if ! printf '%s\n' "${block}" | grep -E "^      BOKRILOG_ROLE: ${want_role}\$" >/dev/null; then
    refuse ROLE_MISSING "${service}의 BOKRILOG_ROLE이 ${want_role}이 아니다."
    role_ok=false
  fi
done
if [ "${role_ok}" = true ]; then
  pass 'API·Worker 역할 고정, migrate 프로필 없음'
fi

# ---------------------------------------------------------------- 운영 비밀

# 권한 600. `ls -ln`의 첫 열을 본다(GNU·BSD `stat`의 인자가 다르다).
#
# POSIX 권한을 표현하지 못하는 파일 시스템(Windows의 Git Bash — `chmod`가 효과가 없다)에서는 리허설에 한해 이 검사를 건너뛴다.
# 운영 서버(Linux)에서는 언제나 판정한다 — 판정할 수 없으면 거부다.
mode="$(ls -ln "${secrets_file}" 2>/dev/null | awk '{ print $1 }')"
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
case "${mode}" in
  -rw-------*) pass '비밀 파일 권한 600' ;;
  *)
    if [ "${posix_modes}" = false ]; then
      pass '리허설: 이 파일 시스템은 POSIX 권한을 표현하지 못해 권한 검사를 건너뛴다'
    else
      refuse SECRETS_PERMISSION "비밀 파일 권한이 600이 아니다: ${mode}"
    fi
    ;;
esac

required_secrets='BOKRILOG_DB_URL BOKRILOG_DB_USERNAME BOKRILOG_DB_PASSWORD POSTGRES_PASSWORD BOKRILOG_PUBLIC_BASE_URL
BOKRILOG_AUDIT_DIGEST_KEY BOKRILOG_SOURCE_CREDENTIAL_KEYS BOKRILOG_SOURCE_CREDENTIAL_ACTIVE_KEY_VERSION
BOKRILOG_BYBIT_BASE_URL BOKRILOG_BYBIT_RESERVED_IP BOKRILOG_SUPPORT_CONTACT'
missing_secrets=''
for name in ${required_secrets}; do
  [ -n "$(kv_get "${secrets_file}" "${name}")" ] || missing_secrets="${missing_secrets} ${name}"
done
if [ -n "${missing_secrets}" ]; then
  # 이름만 남긴다. 값은 어디에도 찍지 않는다.
  refuse SECRET_MISSING "비어 있는 필수 이름:${missing_secrets}"
else
  pass '필수 비밀 이름이 모두 있다'
fi
provider_ready=false
for provider in GOOGLE NAVER KAKAO; do
  if [ -n "$(kv_get "${secrets_file}" "BOKRILOG_${provider}_CLIENT_ID")" ] \
    && [ -n "$(kv_get "${secrets_file}" "BOKRILOG_${provider}_CLIENT_SECRET")" ]; then
    provider_ready=true
  fi
done
if [ "${provider_ready}" = true ]; then
  pass '로그인 제공자 하나 이상'
else
  refuse NO_SIGN_IN_PROVIDER '로그인 제공자 client id·secret 쌍이 하나도 없다.'
fi
# 비밀 파일이 정할 수 없는 이름. 프로필·역할·환경은 compose가, Worker 설정은 이미지가, 배포 수행자는 배포를 돌리는 셸이 정한다.
# `SPRING_APPLICATION_JSON`은 어떤 설정이든 한 줄로 덮을 수 있어 막는다.
forbidden_secret_names='^(SPRING_PROFILES_ACTIVE|SPRING_PROFILES_INCLUDE|SPRING_APPLICATION_JSON|BOKRILOG_ROLE|BOKRILOG_ENV|BOKRILOG_SYNC_WORKER_[A-Z0-9_]*|BOKRILOG_DEPLOY_ACTOR)$'
overriding="$(kv_keys "${secrets_file}" | grep -E "${forbidden_secret_names}" | tr '\n' ' ' | sed 's/ $//' || true)"
if [ -n "${overriding}" ]; then
  refuse PROFILE_OVERRIDE "비밀 파일이 정할 수 없는 이름을 담았다: ${overriding}"
else
  pass '비밀 파일이 프로필·역할·환경·Worker 설정·배포 수행자를 덮지 않는다'
fi

# backup key(age identity)는 앱 key ring·감사 digest 키와 분리한다(Story 3.9 AC-5). 그 key는 호스트의 `/etc/bokrilog/backup/`(권한
# 600)에만 있고 호스트 도구만 읽는다 — compose의 치환 원본인 이 파일에 있으면 앱 프로세스·컨테이너 환경으로 새어 나갈 길이 생긴다.
# 값을 찍지 않고 이름만 남긴다.
backup_key_names="$(awk '
  /^[[:space:]]*#/ { next }
  {
    pos = index($0, "=")
    if (pos == 0) next
    if (index(toupper(substr($0, pos + 1)), "AGE-SECRET-KEY-") > 0) print substr($0, 1, pos - 1)
  }
' "${secrets_file}" | tr '\n' ' ' | sed 's/ $//')"
if [ -n "${backup_key_names}" ]; then
  refuse BACKUP_KEY_IN_SECRETS "운영 비밀 파일에 backup key 모양의 값이 있다(백업 key는 /etc/bokrilog/backup/에만 둔다): ${backup_key_names}"
else
  pass '운영 비밀 파일에 backup key가 없다'
fi

# ---------------------------------------------------------------- 판정

printf '\n%s개 검사, %s개 거부\n' "${checks}" "${refusals}"
if [ "${refusals}" -ne 0 ]; then
  printf '::error::gate가 배포를 거부했다.\n'
  exit 1
fi
exit 0
