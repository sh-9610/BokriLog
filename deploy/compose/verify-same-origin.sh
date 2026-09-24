#!/usr/bin/env bash
# 동일 출처 셸 검증.
#
# 왜 있는가: `spec-1-2-same-origin-shell.md`의 I/O 표는 브라우저 없이도 재현 가능해야 한다.
# Playwright는 화면을 검증하지만 상태 코드·오류 본문·응답 헤더·로그 누출은 curl 한 벌이 더 정확하고
# 더 싸다. 로컬과 CI가 같은 명령을 쓴다. CI에만 있는 판정을 만들지 않는다.
#
# 정본: _bmad-output/implementation-artifacts/spec-1-2-same-origin-shell.md `## I/O & Edge-Case Matrix`
#
# 실행: deploy/compose/verify-same-origin.sh [--keep-stack]
#
#   --keep-stack  모든 단정이 통과하면 compose 스택을 내리지 않고 백엔드를 다시 띄워 건강한
#                 상태로 남긴다. CI의 통합 job이 이 스택에 Story 1.1 Playwright 회귀를 바로
#                 돌리기 위한 옵션이다. 스택을 한 번만 세우고 두 검증이 같은 바이트를 본다.
#                 단정이 하나라도 실패하거나 준비가 실패하면 옵션과 무관하게 정리한다.
#
# 전제
#   - `frontend/dist/index.html`과 `backend/build/libs/bokrilog-backend.jar`가 이미 존재한다.
#     이 스크립트는 어느 쪽도 빌드하지 않는다. CI의 통합 job이 두 품질 job의 산출물을 재빌드 없이
#     소비하는 것과 같은 모양을 로컬에서도 유지한다(CI-PLAN §8).
#   - docker와 compose v2를 쓸 수 있다.
#
# 실행 순서
#   스키마 마이그레이션(1회) → 스택 기동 → I/O 표 → identity 미노출 → 백엔드 비가용 → 로그 누출.
#   마이그레이션이 먼저인 것은 계약이다. 기본 프로필의 백엔드 기동은 schema 검증만 하므로
#   (AD-12) 빈 데이터베이스에 `up`부터 하면 백엔드가 healthy가 되지 못한다.
#
# 종료 코드
#   0 -- 모든 단정 통과
#   1 -- 단정 실패 (`  실패` 줄과 `::error::동일 출처 검증이 실패했다.`)
#   2 -- 준비 실패 (`::error::검증 준비 실패:`). 산출물이나 docker가 없어 단정이 하나도 돌지 않았다.
#        "0개 통과"를 "문제 없음"으로 읽지 않기 위해 단정 실패와 구분한다.
#
# compose 스택은 성공·실패·중단 어느 경로에서도 정리한다. 유일한 예외는 `--keep-stack`으로
# 성공한 경우이며, 그때는 정리 명령을 출력하고 스택을 남긴다.

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd "${script_dir}/../.." && pwd -P)"
compose_file="${script_dir}/compose.yaml"

# compose.yaml의 게시 포트와 같은 값이다. 한쪽만 바꾸면 준비 단계에서 바로 드러난다.
base_url='http://127.0.0.1:18080'

dist_index="${repo_root}/frontend/dist/index.html"
backend_jar="${repo_root}/backend/build/libs/bokrilog-backend.jar"

keep_stack=false

# 이 스크립트가 도는 제공자. 아래 세 루프(흐름 없는 콜백 탐침·성공 왕복·업스트림 오류 탐침)가
# 모두 이 목록 하나를 읽고, 세 완주 가드도 여기서 센 수를 쓴다.
#
# 목록마다 개수를 손으로 적지 않는다. 가드가 자기가 지키는 목록과 어긋날 수 있는 구조이면 그
# 가드는 "leg이 조용히 사라지는 것"을 막지 못한다 — 막으려던 결함과 같은 모양의 결함이 가드 안에
# 생기는 셈이다. 이 파일 안의 중복을 닫는 것이며, 계층을 넘는 목록 등가성 검사는 여전히 별도
# 목표다(미해소 항목 33·38).
providers='google
naver
kakao'

# 목록의 길이. `wc -l`을 쓰지 않는다 — 마지막 줄바꿈 유무로 값이 흔들리고, `set -o pipefail`
# 아래에서 파이프의 종료 코드까지 함께 봐야 한다. 셸만으로 세면 그 두 가지가 다 없다.
provider_count=0
while read -r listed_provider; do
  [ -n "${listed_provider}" ] || continue
  provider_count=$((provider_count + 1))
done <<PROVIDER_LIST
${providers}
PROVIDER_LIST

# --------------------------------------------------------------------------
# 보고
# --------------------------------------------------------------------------

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
  printf '\n::error::검증 준비 실패: %s\n' "$1" >&2
  if [ -n "${2:-}" ]; then
    printf '%s\n' "$2" >&2
  fi
  exit 2
}

# --------------------------------------------------------------------------
# 누출 조각
# --------------------------------------------------------------------------

# 응답과 로그 어디에도 남으면 안 되는 조각들.
#   내부 경로 -- 컨테이너 안의 파일 시스템 경로
#   스택      -- 예외 스택 프레임
#   환경 변수 -- 이름만 나와도 어떤 값을 찾아야 하는지 알려 준다
#
# 클라이언트가 보낸 요청 경로(`/api/nope`)와 upstream 주소는 여기 포함하지 않는다. 둘 다 내부
# 정보가 아니라 그 요청 자체의 사실이며, 오류 로그의 존재 이유다.
forbidden_fragments=(
  '/app/app.jar'
  '/usr/share/nginx'
  '/etc/nginx'
  '/docker-entrypoint'
  'at org.springframework'
  'at java.'
  'at jakarta.'
  'Whitelabel'
  'JAVA_HOME'
  'JAVA_TOOL_OPTIONS'
  'SERVER_PORT'
  'BOKRILOG_DB_URL'
  'BOKRILOG_DB_USERNAME'
  'BOKRILOG_PUBLIC_BASE_URL'
  'BOKRILOG_GOOGLE_CLIENT_ID'
  'BOKRILOG_GOOGLE_CLIENT_SECRET'
  'BOKRILOG_NAVER_CLIENT_ID'
  'BOKRILOG_NAVER_CLIENT_SECRET'
  'BOKRILOG_KAKAO_CLIENT_ID'
  'BOKRILOG_KAKAO_CLIENT_SECRET'
  'BOKRILOG_DEV_OIDC_INTERNAL_BASE_URL'
  'SPRING_PROFILES_ACTIVE'
  'NGINX_ENTRYPOINT_QUIET_LOGS'
  # 재인증이 제공자에게 보내는 최근 인증 요청·증거. 인가 요청의 `claims`와 ID Token의 claim
  # 이름이 그 값이며 둘 다 로그에 남을 이유가 없다. access log의 `map`이 쿼리를 지우고
  # 백엔드는 요청을 찍지 않으므로 0건이어야 한다.
  'auth_time'
)

# assert_no_forbidden_fragments <대상 이름> <파일>
#
# 목록을 한 곳에 두고 두 대상(마이그레이션 로그, 스택 로그)이 같은 목록을 쓴다. 조각이 한쪽에만
# 늘어나 다른 쪽이 조용히 통과하는 일을 만들지 않는다.
assert_no_forbidden_fragments() {
  local label="$1" file="$2"
  local fragment
  local leak_found=false
  for fragment in "${forbidden_fragments[@]}"; do
    if grep -qF -- "${fragment}" "${file}"; then
      record_fail "${label}에 '${fragment}'가 없다" "$(grep -nF -- "${fragment}" "${file}" | head -3)"
      leak_found=true
    fi
  done
  if [ "${leak_found}" = false ]; then
    record_pass "${label}에 내부 경로·스택·환경 변수 이름이 없다"
  fi
}

# --------------------------------------------------------------------------
# 준비
# --------------------------------------------------------------------------

for arg in "$@"; do
  case "${arg}" in
    --keep-stack) keep_stack=true ;;
    *) fail_setup "알 수 없는 인자: ${arg}" '지원하는 인자는 --keep-stack 하나다.' ;;
  esac
done

command -v docker >/dev/null 2>&1 || fail_setup 'docker 명령을 찾을 수 없다.'
docker compose version >/dev/null 2>&1 || fail_setup 'docker compose v2를 쓸 수 없다.'
# 모든 단정이 curl로 요청을 보낸다. 없으면 단정이 전부 '000'으로 실패하고, 그 실패는 "계약이
# 깨졌다"로 읽힌다. 준비 실패(2)와 단정 실패(1)를 구분하는 것이 이 스크립트의 종료 코드 규약이다.
command -v curl >/dev/null 2>&1 || fail_setup 'curl 명령을 찾을 수 없다.'
[ -f "${compose_file}" ] || fail_setup "compose 파일이 없다: ${compose_file}"

[ -f "${dist_index}" ] || fail_setup \
  "프런트엔드 산출물이 없다: ${dist_index}" \
  '먼저 `npm --prefix frontend run build`를 실행한다.'
[ -f "${backend_jar}" ] || fail_setup \
  "백엔드 jar가 없다: ${backend_jar}" \
  '먼저 `backend/gradlew --project-dir backend build`를 실행한다.'

work_dir="$(mktemp -d)" || fail_setup '임시 디렉터리를 만들지 못했다.'
[ -d "${work_dir}" ] || fail_setup "mktemp -d가 쓸 수 없는 경로를 냈다: '${work_dir}'"

compose() {
  docker compose --file "${compose_file}" "$@"
}

stack_up=false
cleaned_up=false

cleanup() {
  # 첫 인자는 스크립트의 종료 코드다. EXIT 트랩은 `$?`를, INT·TERM 트랩은 신호 코드를 넘긴다.
  # EXIT와 INT·TERM 두 경로에서 모두 불릴 수 있다. 두 번 도는 것을 막는다.
  local exit_code="${1:-1}"
  if [ "${cleaned_up}" = true ]; then
    return
  fi
  cleaned_up=true
  if [ "${stack_up}" = true ]; then
    if [ "${keep_stack}" = true ] && [ "${exit_code}" -eq 0 ]; then
      # 성공했고 남기라고 했다. 정리 명령을 그대로 적어 두어 사용자가 복사해 쓸 수 있게 한다.
      printf '\ncompose 스택 유지 (--keep-stack)\n'
      printf '  정리: docker compose --file %s down --remove-orphans --volumes\n' "${compose_file}"
    else
      printf '\ncompose 정리\n'
      compose down --remove-orphans --volumes >/dev/null 2>&1 || true
    fi
  fi
  rm -rf "${work_dir}"
}

# EXIT만 잡으면 Ctrl-C가 이 스크립트를 죽일 때 compose 스택과 임시 디렉터리가 남는다. bash는
# 트랩이 없는 SIGINT로 죽을 때 EXIT 트랩을 실행하지 않기 때문이다. INT·TERM에서는 정리한 뒤
# 그 신호에 해당하는 코드로 바로 끝낸다. 여기서 되돌아가면 남은 단정이 죽은 스택에 요청을 보낸다.
trap 'cleanup $?' EXIT
trap 'cleanup 130; exit 130' INT
trap 'cleanup 143; exit 143' TERM

printf '동일 출처 검증\n\n'

# 스키마 마이그레이션을 `up` 앞에서 한 번만 실행한다(AD-12).
#
# `run --rm migrate`가 `postgres`를 healthy까지 띄운 뒤 migrate 컨테이너를 한 번 돌리고 지운다.
# 이 실행이 스키마를 바꾸는 유일한 경로다. 뒤이어 뜨는 백엔드는 기동 시점에 그 스키마를
# 검증만 하고(`SchemaValidationConfig`), 검증이 실패하면 healthy가 되지 못해 아래 `--wait`가
# 그 사실을 먼저 잡는다.
#
# `up --wait`에 맡기지 않는 이유는 정상 종료가 healthcheck 모델과 맞지 않기 때문이다. 종료
# 코드가 곧 판정인 일회성 역할은 명시적으로 부르고 그 코드를 그대로 읽는다.
#
# 이 실행이 postgres를 띄우므로 `stack_up`을 여기서 올린다. 아래로 내리면 마이그레이션이
# 실패했을 때 postgres 컨테이너가 정리되지 않고 남는다.
stack_up=true
printf 'schema 마이그레이션\n'

# 이미지 빌드와 마이그레이션 실행을 두 로그로 나눈다.
#
# 한 로그에 섞으면 아래 조각 검사가 보는 대상이 흐려진다. docker의 빌드 진행 출력에는 Dockerfile의
# `COPY … /app/app.jar` 줄이 그대로 실리는데, 그것은 저장소가 커밋한 파일의 내용이지 마이그레이션
# 프로세스가 흘린 정보가 아니다. 검사해야 하는 것은 실행 중인 프로세스가 밖으로 내보내는 바이트다.
#
# 아래 `run`에 `--build`를 붙이지 않는 것으로 그 분리가 성립한다. 이미지는 바로 앞에서 만들었으므로
# `run`은 다시 빌드하지 않고 빌드 진행 출력도 남기지 않는다. (`run`에는 `--no-build`가 없다.)
if ! compose --profile migrate build migrate >"${work_dir}/migrate-build.log" 2>&1; then
  fail_setup 'migrate 이미지 빌드에 실패했다.' "$(tail -40 "${work_dir}/migrate-build.log" 2>/dev/null)"
fi
if ! compose --profile migrate run --rm migrate >"${work_dir}/migrate.log" 2>&1; then
  fail_setup '스키마 마이그레이션에 실패했다.' "$(tail -40 "${work_dir}/migrate.log" 2>/dev/null)"
fi
printf '  마이그레이션 완료\n'

# 성공한 마이그레이션 로그도 검사한다.
#
# 이 로그는 실패했을 때만 인쇄되므로, 검사하지 않으면 성공한 실행의 출력은 아무도 보지 않는다.
# 그런데 이 프로세스는 스택에서 스키마를 바꾸는 유일한 프로세스이고 백엔드와 같은 이미지·같은
# 코드로 돈다. 여기서 내부 경로나 환경 변수 이름이 새면 배포 시점의 migration 역할에서도 샌다.
assert_no_forbidden_fragments 'migrate 로그' "${work_dir}/migrate.log"
printf '\n'

printf 'compose 기동 (%s)\n' "${base_url}"

# `--wait`는 기본 프로필 세 서비스의 healthcheck가 healthy가 될 때까지 기다린다.
# 기동을 기다리는 sleep을 스크립트에 넣지 않는다. `migrate`는 `profiles`에 가려 여기 들어오지
# 않으므로, 정상 종료한 일회성 컨테이너를 `--wait`가 영원히 기다리는 일도 없다.
if ! compose up --detach --build --wait --wait-timeout 180 >"${work_dir}/up.log" 2>&1; then
  fail_setup 'compose 기동에 실패했다.' "$(tail -40 "${work_dir}/up.log" 2>/dev/null)"
fi
printf '  기동 완료\n\n'

# --------------------------------------------------------------------------
# 요청 도우미
# --------------------------------------------------------------------------

# fetch <메서드> <경로> [추가 curl 인자...]
# 결과를 전역 `last_status`·`last_headers`·`last_body`에 남긴다.
#
# 명령 치환(`$(fetch ...)`)으로 부르지 않는다. 치환은 서브셸이라 파일 경로 대입이 사라지고
# 뒤따르는 헤더·본문 단정이 빈 경로를 읽는다. 상태 코드도 변수로 돌려주는 이유가 이것이다.
request_seq=0
last_status=''
last_headers=''
last_body=''

# 백엔드가 만든 오류에서 받아 둘 요청 식별자. 응답 헤더·응답 본문·진입점 로그 세 곳이 같은 값을
# 쓰는지 확인하는 데 쓴다.
tracked_request_id=''

fetch() {
  local method="$1" path="$2"
  shift 2
  request_seq=$((request_seq + 1))
  last_headers="${work_dir}/headers-${request_seq}.txt"
  last_body="${work_dir}/body-${request_seq}.txt"
  : >"${last_headers}"
  : >"${last_body}"
  last_status="$(curl --silent --show-error --max-time 15 \
    --request "${method}" \
    --dump-header "${last_headers}" \
    --output "${last_body}" \
    --write-out '%{http_code}' \
    "$@" \
    "${base_url}${path}" 2>"${work_dir}/curl-err-${request_seq}.txt")" || last_status='000'
}

# header_value <이름>. 헤더 값을 소문자 이름으로 찾아 stdout으로 낸다.
header_value() {
  tr -d '\r' <"${last_headers}" \
    | awk -v want="$(printf '%s' "$1" | tr 'A-Z' 'a-z')" '
        { line = $0
          pos = index(line, ":")
          if (pos == 0) next
          name = tolower(substr(line, 1, pos - 1))
          if (name != want) next
          value = substr(line, pos + 1)
          sub(/^[ \t]+/, "", value)
          print value
        }'
}

# psql_value <SQL>. 스택 안의 postgres에 한 값짜리 질의를 던져 stdout으로 낸다.
#
# HTTP 응답만으로는 "그 경로가 없다"까지만 알 수 있고 "그 요청이 아무것도 만들지 않았다"는
# 알 수 없다. identity에는 HTTP 표면이 없어서 상태를 밖에서 되읽을 방법도 없으므로, 저장소를
# 직접 본다. 실패하면 빈 문자열을 내고 이유는 `psql-err.txt`에 남는다.
#
# stdin을 `/dev/null`로 닫는다. `docker compose exec`는 `-T`로도 stdin을 컨테이너로 넘기므로,
# 이 함수를 `while read` 루프 안에서 부르면 루프에 남은 입력을 psql이 통째로 먹어 루프가 첫
# 반복에서 끝난다. 실제로 그것이 제공자 왕복 루프가 네이버 iteration을 건너뛴 원인이었다 —
# 단정이 하나도 실패하지 않고 조용히 사라지는 형태라 로그만 보면 통과처럼 읽힌다.
psql_value() {
  compose exec -T postgres psql \
    --username=bokrilog --dbname=bokrilog --no-password \
    --tuples-only --no-align --command="$1" \
    </dev/null 2>"${work_dir}/psql-err.txt" \
    | tr -d '[:space:]'
}

# psql_exec <라벨> <SQL>. 값을 읽지 않는 문장을 실행한다.
#
# 성공 왕복이 만든 행을 지워 스택을 원래 상태로 되돌리는 데 쓴다. 실패를 삼키지 않는다 —
# 되돌리지 못한 채 뒤 단계로 넘어가면 그 단계가 무엇을 세는지 알 수 없게 된다.
#
# stdin은 `psql_value`와 같은 이유로 닫는다.
psql_exec() {
  local label="$1" sql="$2"
  if compose exec -T postgres psql \
    --username=bokrilog --dbname=bokrilog --no-password --quiet \
    --set=ON_ERROR_STOP=1 --command="${sql}" \
    </dev/null >"${work_dir}/psql-exec.log" 2>&1; then
    record_pass "${label}"
  else
    record_fail "${label}" "$(tail -5 "${work_dir}/psql-exec.log" 2>/dev/null)"
  fi
}

# 응답이 새로 준 세션 쿠키의 값.
#
# curl의 쿠키 엔진을 쓰지 않고 우리가 직접 실어 보낸다. 세션 쿠키에는 `Secure`가 붙어 있어
# 클라이언트마다 평문 루프백에서의 회신 규칙이 다르고, 이 검증이 고정하려는 것은 그 규칙이
# 아니라 서버가 내보낸 바이트다.
session_cookie_value() {
  tr -d '\r' <"${last_headers}" \
    | sed -n 's/^[Ss]et-[Cc]ookie:[[:space:]]*BOKRILOG_SESSION=\([^;]*\).*/\1/p' \
    | head -1
}

# --------------------------------------------------------------------------
# 단정
# --------------------------------------------------------------------------

assert_equals() {
  local label="$1" got="$2" want="$3"
  if [ "${got}" = "${want}" ]; then
    record_pass "${label}"
  else
    record_fail "${label}" "실제 '${got}' (기대 '${want}')"
  fi
}

assert_body_contains() {
  local label="$1" needle="$2"
  if grep -qF -- "${needle}" "${last_body}"; then
    record_pass "${label}"
  else
    record_fail "${label}" "본문에 '${needle}'가 없다 — $(head -c 400 "${last_body}")"
  fi
}

# 상태 코드가 주어진 목록 중 하나인지. 값 하나로 좁힐 수 없는 경우에만 쓴다.
assert_status_in() {
  local label="$1" got="$2"
  shift 2
  local want
  for want in "$@"; do
    if [ "${got}" = "${want}" ]; then
      record_pass "${label}"
      return
    fi
  done
  record_fail "${label}" "실제 '${got}' (기대 '$*' 중 하나)"
}

assert_body_lacks() {
  local label="$1" needle="$2"
  if grep -qF -- "${needle}" "${last_body}"; then
    record_fail "${label}" "본문에 '${needle}'가 있다 — $(head -c 400 "${last_body}")"
  else
    record_pass "${label}"
  fi
}

# assert_prefix <라벨> <실제> <기대 접두사>
assert_prefix() {
  local label="$1" got="$2" want="$3"
  case "${got}" in
    "${want}"*) record_pass "${label}" ;;
    *) record_fail "${label}" "실제 '${got}' (기대 '${want}…')" ;;
  esac
}

# resolve_location <Location 값>
#
# 상대 경로면 이 origin을 붙이고 절대 URI면 그대로 둔다. 이 정규화 없이 접미사만 비교하면
# 포트를 잃은 절대 URI(`http://127.0.0.1/onboarding`)가 통과한다. Nginx가 `Host $host`로 포트를
# 잃은 값을 넘기므로 리다이렉트가 절대 URI로 나가는 순간 실제로 그 값이 나가고, 브라우저는
# 80번 포트로 가서 끊긴다. 그래서 목적지 단정은 해석된 절대 URI의 동등성이어야 한다.
resolve_location() {
  case "$1" in
    /*) printf '%s%s' "${base_url}" "$1" ;;
    *) printf '%s' "$1" ;;
  esac
}

# assert_value_contains <라벨> <실제> <기대 조각>
assert_value_contains() {
  local label="$1" got="$2" want="$3"
  case "${got}" in
    *"${want}"*) record_pass "${label}" ;;
    *) record_fail "${label}" "실제 '${got}' (기대 '${want}' 포함)" ;;
  esac
}

# assert_value_lacks <라벨> <실제> <없어야 하는 조각>
assert_value_lacks() {
  local label="$1" got="$2" needle="$3"
  case "${got}" in
    *"${needle}"*) record_fail "${label}" "실제 '${got}'에 '${needle}'가 있다" ;;
    *) record_pass "${label}" ;;
  esac
}

# assert_rows <라벨> <SQL> <기대 값>
assert_rows() {
  local label="$1" sql="$2" want="$3" got
  got="$(psql_value "${sql}")"
  if [ "${got}" = "${want}" ]; then
    record_pass "${label}"
  else
    record_fail "${label}" \
      "실제 '${got}' (기대 '${want}') — $(head -c 300 "${work_dir}/psql-err.txt" 2>/dev/null)"
  fi
}

# 오류 본문의 correlation ID. 없으면 빈 문자열이다.
correlation_id_of_body() {
  sed -n 's/.*"correlationId":"\([^"]*\)".*/\1/p' "${last_body}"
}

# 오류 본문에 correlation ID가 실제로 실렸는지. 빈 값이면 추적 경로가 없는 것과 같다.
assert_correlation_id() {
  local label="$1" value
  value="$(correlation_id_of_body)"
  if [ -n "${value}" ]; then
    record_pass "${label}"
  else
    record_fail "${label}" "correlationId가 비었다 — $(head -c 400 "${last_body}")"
  fi
}

# 오류 본문에서 correlation ID 값만 지운다. 두 계층이 만든 같은 code의 본문을 통째로 비교하기
# 위한 정규화이며, 값이 다른 것은 correlation ID 하나뿐이다.
normalized_error_body() {
  sed 's/"correlationId":"[^"]*"/"correlationId":"<id>"/' "${last_body}"
}

# CSP 전문. 부분 문자열이 아니라 전체를 비교한다. 부분 비교로는 `script-src 'self'`에
# `'unsafe-inline'`이 붙거나 `connect-src 'self'`가 `*`로 넓어져도 통과한다. 정책이 넓어지는
# 변경은 정확히 그 모양으로 오기 때문에 이 단정은 전문 동등성이어야 의미가 있다.
# 값을 바꿀 때는 `deploy/nginx/bokrilog.conf`와 이 상수를 함께 바꾼다.
expected_csp="default-src 'none'; script-src 'self'; style-src 'self'; style-src-attr 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

# 해시가 박힌 정적 자산의 캐시 지시. 파일 이름이 곧 내용이므로 영구 캐시가 안전하다.
expected_asset_cache_control='public, max-age=31536000, immutable'

# 그 밖의 모든 응답. 인증된 문서가 캐시·bfcache에서 복원되지 않게 한다.
expected_document_cache_control='no-store'

# assert_cache_control <라벨> <기대 값>
#
# 같은 이름의 헤더가 두 번 나갈 수 있다. `/api/auth/session`은 백엔드가 자기 응답에 `no-store`를
# 붙이고 진입점도 같은 지시를 낸다. HTTP는 같은 이름의 헤더를 쉼표로 이은 것과 같이 다루므로
# 값이 같으면 의미는 하나다. 확인해야 하는 것은 "서로 다른 지시가 함께 나가지 않는가"이고,
# 그래서 값의 집합을 보아 기대한 하나만 남는지 단정한다.
#
# 헤더가 아예 없으면 빈 문자열이 되어 기대 값과 다르므로 실패한다. `add_header` 한 줄이 사라지는
# 회귀가 여기서 걸린다.
assert_cache_control() {
  local label="$1" want="$2" values
  values="$(header_value 'Cache-Control' | sort -u | tr '\n' '|' | sed 's/|$//')"
  if [ "${values}" = "${want}" ]; then
    record_pass "${label}"
  else
    record_fail "${label}" "실제 '${values}' (기대 '${want}' 하나만)"
  fi
}

# 응답 헤더 계약. 모든 응답에서 확인한다. 오류 응답에서 특히 중요하다.
#
# 두 번째 인자는 기대하는 `Cache-Control` 값이다. 생략하면 문서·API 응답의 기본값(`no-store`)이며
# 해시 자산을 확인하는 호출만 값을 넘긴다. 캐시 단정을 이 함수에 두어 모든 응답 그룹에 한 번에
# 전파시킨다 — 그룹마다 따로 적으면 새 그룹이 조용히 빠진다.
assert_security_headers() {
  local context="$1" value
  local want_cache="${2:-${expected_document_cache_control}}"

  assert_equals "${context} · CSP" "$(header_value 'Content-Security-Policy')" "${expected_csp}"
  assert_cache_control "${context} · Cache-Control" "${want_cache}"
  assert_equals "${context} · X-Content-Type-Options" "$(header_value 'X-Content-Type-Options')" 'nosniff'
  assert_equals "${context} · Referrer-Policy" "$(header_value 'Referrer-Policy')" 'no-referrer'
  assert_equals "${context} · X-Frame-Options" "$(header_value 'X-Frame-Options')" 'DENY'

  # `server_tokens off`가 지우는 것은 버전뿐이다. 남는 값은 `nginx`여야 한다.
  value="$(header_value 'Server')"
  if [ "${value}" = 'nginx' ]; then
    record_pass "${context} · Server 헤더에 버전이 없다"
  else
    record_fail "${context} · Server 헤더에 버전이 없다" "실제 '${value}'"
  fi

  # 요청 식별자는 성공 응답에도 실린다. 사용자가 신고할 때 화면에 오류가 없어도 이 값 하나로
  # 그 요청을 특정할 수 있어야 한다.
  value="$(header_value 'X-Request-Id')"
  if [ -n "${value}" ]; then
    record_pass "${context} · X-Request-Id 헤더"
  else
    record_fail "${context} · X-Request-Id 헤더" '헤더가 없거나 비었다'
  fi
}

# --------------------------------------------------------------------------
# I/O 표
# --------------------------------------------------------------------------

printf '서비스 루트\n'

fetch GET /
assert_equals '`GET /` 상태 200' "${last_status}" '200'
assert_equals '`GET /` Content-Type' "$(header_value 'Content-Type')" 'text/html'
assert_body_contains '`GET /`가 Story 1.1 진입 화면 문서다' '<div id="root">'
assert_body_contains '`GET /`에 noscript 고지가 있다' '투자성과·계좌 수익률·투자 추천이 아닙니다.'
assert_security_headers '`GET /`'

# 번들 asset은 `/assets/...` 루트 절대경로다. 하위 경로에 mount하면 여기서 깨진다.
asset_path="$(sed -n 's/.*src="\(\/assets\/[^"]*\.js\)".*/\1/p' "${last_body}" | head -1)"
if [ -z "${asset_path}" ]; then
  record_fail '진입 문서가 `/assets/*.js` 번들을 참조한다' "index.html에서 asset 경로를 찾지 못했다."
else
  record_pass '진입 문서가 `/assets/*.js` 번들을 참조한다'
  fetch GET "${asset_path}"
  assert_equals "\`GET ${asset_path}\` 상태 200" "${last_status}" '200'
  # 해시 자산만 영구 캐시다. 보안 헤더 다섯 개는 그대로 실려야 한다 — 캐시 지시를 하위
  # location에서 내면 그 다섯 개가 이 응답에서 전부 사라진다.
  assert_security_headers "\`GET ${asset_path}\`" "${expected_asset_cache_control}"

  # 성공한 자산 응답만 영구 캐시다. 같은 경로의 non-GET은 파일을 찾기 전에 405로 되돌아가며
  # 그 응답은 저장 대상이 아니다. 경로만 보는 map은 이 응답에도 `immutable`을 실어 보낸다.
  fetch POST "${asset_path}"
  assert_equals "\`POST ${asset_path}\` 상태 405" "${last_status}" '405'
  assert_body_contains "\`POST ${asset_path}\` 안정 code" '"code":"METHOD_NOT_ALLOWED"'
  assert_security_headers "\`POST ${asset_path}\`"
fi

# 존재하지 않는 자산. 404를 1년간 `immutable`로 고정하면 그 파일을 실제로 추가하는 배포 뒤에도
# 브라우저와 공유 캐시에 깨진 상태가 남는다(`immutable`은 재검증까지 막는다).
fetch GET /assets/does-not-exist-verify-probe.js
assert_equals '`GET /assets/<없는 파일>` 상태 404' "${last_status}" '404'
assert_body_contains '`GET /assets/<없는 파일>` 안정 code' '"code":"NOT_FOUND"'
assert_correlation_id '`GET /assets/<없는 파일>` correlation ID'
assert_security_headers '`GET /assets/<없는 파일>`'

printf '\n상태 확인\n'

fetch GET /api/status
assert_equals '`GET /api/status` 상태 200' "${last_status}" '200'
assert_equals '`GET /api/status` Content-Type' "$(header_value 'Content-Type')" 'application/json'
# 고정된 최소 필드. 시각·버전·호스트명·빌드 정보가 붙으면 이 동등성이 깨진다.
assert_equals '`GET /api/status` 본문은 고정된 최소 필드뿐' "$(cat "${last_body}")" '{"status":"UP"}'
assert_security_headers '`GET /api/status`'

printf '\n알 수 없는 API\n'

fetch GET /api/nope
assert_equals '`GET /api/nope` 상태 404' "${last_status}" '404'
assert_equals '`GET /api/nope` Content-Type' "$(header_value 'Content-Type')" 'application/json'
assert_body_contains '`GET /api/nope` 안정 code' '"code":"NOT_FOUND"'
assert_body_contains '`GET /api/nope` 사용자 메시지' '"message":"요청한 경로를 찾을 수 없습니다."'
assert_correlation_id '`GET /api/nope` correlation ID'

# 백엔드가 만든 오류다. 응답 헤더의 식별자와 본문의 correlationId가 같은 값이어야 두 계층이
# 한 ID를 쓴다는 말이 성립한다. `proxy_set_header X-Request-Id $request_id`가 사라지면 백엔드가
# 자기 UUID를 만들어 두 값이 갈라지는데, 그 회귀는 다른 어떤 단정에도 걸리지 않는다.
tracked_request_id="$(header_value 'X-Request-Id')"
assert_equals '`GET /api/nope` 응답 헤더와 본문이 같은 식별자를 쓴다' \
  "$(correlation_id_of_body)" "${tracked_request_id}"
assert_body_lacks '`GET /api/nope`가 요청 경로를 되돌려주지 않는다' '/api/nope'
assert_body_lacks '`GET /api/nope`에 스택이 없다' 'org.springframework'
assert_body_lacks '`GET /api/nope`에 프레임워크 기본 오류 본문이 없다' '"timestamp"'
assert_security_headers '`GET /api/nope`'

printf '\n잘못된 메서드\n'

fetch POST /api/status
assert_equals '`POST /api/status` 상태 405' "${last_status}" '405'
assert_equals '`POST /api/status` Content-Type' "$(header_value 'Content-Type')" 'application/json'
assert_body_contains '`POST /api/status` 안정 code' '"code":"METHOD_NOT_ALLOWED"'
assert_body_contains '`POST /api/status` 사용자 메시지' '"message":"요청한 방식으로는 처리할 수 없습니다."'
assert_correlation_id '`POST /api/status` correlation ID'
assert_body_lacks '`POST /api/status`에 Spring 기본 오류 본문이 없다' 'Whitelabel'
assert_body_lacks '`POST /api/status`에 프레임워크 기본 오류 본문이 없다' '"path"'

# 정적 경로의 잘못된 메서드. Nginx가 파일을 먼저 찾으면 그 파일의 MIME 타입이 응답에 박혀
# 본문은 JSON인데 Content-Type은 text/html인 응답이 나간다.
fetch POST /
assert_equals '`POST /` 상태 405' "${last_status}" '405'
assert_equals '`POST /` Content-Type' "$(header_value 'Content-Type')" 'application/json'
assert_body_contains '`POST /` 안정 code' '"code":"METHOD_NOT_ALLOWED"'
assert_body_lacks '`POST /`가 index.html을 돌려주지 않는다' '<div id="root">'
assert_security_headers '`POST /`'

# 확장자가 붙은 경로의 오류. nginx는 `return`의 Content-Type을 정할 때 URI 확장자를 먼저
# 찾으므로, named location에서 MIME 맵을 비우지 않으면 본문은 JSON인데 Content-Type이
# `application/javascript`로 나간다.
fetch GET /nope.js
assert_equals '`GET /nope.js` 상태 404' "${last_status}" '404'
assert_equals '`GET /nope.js` Content-Type' "$(header_value 'Content-Type')" 'application/json'
assert_body_contains '`GET /nope.js` 안정 code' '"code":"NOT_FOUND"'

printf '\n오류 계약 단일성\n'

# 같은 code의 오류 본문이 두 계층에서 글자 하나까지 같은지. code 문자열과 한국어 메시지는
# 백엔드 상수와 nginx의 `return` 문자열 두 곳에 리터럴로 있다. 한쪽만 고쳐도 컴파일도 기동도
# 성공하고, 클라이언트는 어느 계층이 답했느냐에 따라 다른 문자열을 받는다.
# 여기서 두 본문을 통째로 비교해 그 갈라짐을 이 스크립트가 붙잡는다.
fetch GET /nope
nginx_not_found="$(normalized_error_body)"
fetch GET /api/nope
assert_equals 'NOT_FOUND 본문이 nginx와 백엔드에서 같다' \
  "$(normalized_error_body)" "${nginx_not_found}"

fetch POST /
nginx_method_not_allowed="$(normalized_error_body)"
fetch POST /api/status
assert_equals 'METHOD_NOT_ALLOWED 본문이 nginx와 백엔드에서 같다' \
  "$(normalized_error_body)" "${nginx_method_not_allowed}"

printf '\n%s 경계\n' '`/api`'

# `location ^~ /api`는 접두사 비교라 `/apifoo`·`/api-docs`도 백엔드로 보낸다. 그런데 두 계층의
# 404 본문이 같아서 `GET /apifoo`의 404만으로는 어느 쪽이 답했는지 알 수 없다.
# 메서드가 판별자다. 정적 location은 GET·HEAD가 아니면 파일을 찾기 전에 405를 돌려주고,
# 백엔드는 매핑이 없는 경로에 404를 돌려준다. 그래서 `POST`의 상태 코드가 곧 "프록시되었는가"다.
fetch GET /apifoo
assert_equals '`GET /apifoo` 상태 404' "${last_status}" '404'
assert_body_contains '`GET /apifoo` 안정 code' '"code":"NOT_FOUND"'
fetch POST /apifoo
assert_equals '`POST /apifoo` 상태 405(백엔드로 가지 않는다)' "${last_status}" '405'

fetch POST /api-docs
assert_equals '`POST /api-docs` 상태 405(백엔드로 가지 않는다)' "${last_status}" '405'

# 반대쪽. `/api` 하나는 백엔드가 받아야 한다. 정적 location이 받으면 405가 온다.
fetch GET /api
assert_equals '`GET /api` 상태 404' "${last_status}" '404'
assert_equals '`GET /api` Content-Type' "$(header_value 'Content-Type')" 'application/json'
assert_body_contains '`GET /api` 안정 code' '"code":"NOT_FOUND"'
fetch POST /api
assert_equals '`POST /api` 상태 404(백엔드가 받는다)' "${last_status}" '404'

printf '\nidentity 미노출\n'

# identity는 HTTP 표면을 갖지 않는다. 계정 생성·상태 변경·삭제에 밖에서 닿는 경로가 존재하지
# 않는 것이 이 모듈의 계약이다. `ModuleBoundaryTest`가 컴파일된 구조에서 그것을 막고, 여기서는
# 실제로 뜬 스택에 요청을 던져 확인한다. 구조 검사만 있으면 나중에 다른 모듈이 identity를
# 감싸는 컨트롤러를 만들어도 이 스크립트는 모른다.
#
# 탐침 경로는 "이 기능이 HTTP로 열린다면 아마 이 이름이었을 것"들이다. 목록을 늘리는 것으로
# 부재를 증명할 수는 없으므로, 아래 저장소 질의가 실제 증거다.
while read -r probe_method probe_path; do
  [ -n "${probe_method}" ] || continue
  fetch "${probe_method}" "${probe_path}"
  # 404(매핑 없음)와 405(정적 location이 먼저 받음) 둘 다 "그 경로가 없다"의 답이다.
  assert_status_in "\`${probe_method} ${probe_path}\` 상태 404 또는 405" "${last_status}" '404' '405'
  # 성공했거나 무언가를 만들었다면 그 흔적이 본문에 남는다. 계정 식별자가 응답에 실리는 경로는
  # 어디에도 없어야 한다(AD-14).
  assert_body_lacks "\`${probe_method} ${probe_path}\` 응답에 계정 식별자가 없다" 'user_id'
done <<'PROBES'
GET /api/accounts
POST /api/accounts
DELETE /api/accounts
GET /api/accounts/00000000-0000-0000-0000-000000000000
DELETE /api/accounts/00000000-0000-0000-0000-000000000000
POST /api/accounts/00000000-0000-0000-0000-000000000000/deletion
GET /api/identity
POST /api/identity/accounts
GET /api/users/me
DELETE /api/users/me
POST /api/sessions
PROBES

# 마이그레이션이 실제로 적용됐다는 증거. 백엔드가 healthy라는 것은 Flyway 검증이 통과했다는
# 뜻이지만, 그 검증이 보는 것은 적용 이력이지 테이블의 존재가 아니다. 세 테이블을 직접 센다.
identity_tables="$(psql_value "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('identity_account_lifecycle', 'identity_account', 'identity_social_link')")"
if [ "${identity_tables}" = '3' ]; then
  record_pass '마이그레이션이 identity 세 테이블을 만들었다'
else
  record_fail '마이그레이션이 identity 세 테이블을 만들었다' \
    "실제 '${identity_tables}' (기대 '3') — $(head -c 300 "${work_dir}/psql-err.txt" 2>/dev/null)"
fi

# 위 탐침이 하나도 통하지 않았다는 것을 저장소에서 확인한다. 상태 코드는 "경로가 없다"까지만
# 말해 주고, 부수 효과가 없었다는 것은 이 질의만이 말할 수 있다.
lifecycle_rows="$(psql_value 'SELECT count(*) FROM identity_account_lifecycle')"
if [ "${lifecycle_rows}" = '0' ]; then
  record_pass 'HTTP 요청으로 계정이 하나도 만들어지지 않았다'
else
  record_fail 'HTTP 요청으로 계정이 하나도 만들어지지 않았다' \
    "identity_account_lifecycle 행 수가 '${lifecycle_rows}'다 — $(head -c 300 "${work_dir}/psql-err.txt" 2>/dev/null)"
fi

# 바깥에 열리는 포트는 nginx 하나다. 데이터베이스가 생긴 뒤에도 그대로여야 한다.
#
# 렌더링된 구성에서 센다. `compose port`는 게시되지 않은 포트에도 종료 코드 0과 `invalid IP:0`을
# 돌려주어 판정에 쓸 수 없고, 실행 중인 컨테이너만 볼 수 있어 `profiles`에 가린 `migrate`는
# 아예 보이지 않는다. `--profile migrate`를 붙인 `config`는 네 서비스를 모두 펼치므로 이 한
# 단정이 스택 전체를 덮는다.
published_ports="$(compose --profile migrate config 2>/dev/null \
  | sed -n 's/^[[:space:]]*published:[[:space:]]*"\{0,1\}\([0-9][0-9]*\)"\{0,1\}[[:space:]]*$/\1/p' \
  | tr '\n' ' ' | sed 's/[[:space:]]*$//')"
if [ "${published_ports}" = '18080' ]; then
  record_pass '호스트에 게시된 포트는 nginx 하나뿐이다'
else
  record_fail '호스트에 게시된 포트는 nginx 하나뿐이다' \
    "실제 '${published_ports}' (기대 '18080'). postgres·backend·migrate는 어떤 포트도 게시하지 않는다."
fi

printf '\n인증 표면\n'

# 이 스택에는 실제 Google 자격정보가 없다. 대신 `dev-oidc` 프로필의 local OIDC 스텁이 제공자
# 자리에 있어(`DevOidcStubConfig`) 아래 `스텁 제공자 성공 왕복`이 인가 시작부터 세션 개설까지를
# 실제로 지난다. 브라우저와 백엔드가 같은 origin에서 스텁에 닿을 수 있어야 그 왕복이 성립하며,
# 그것이 스텁을 시험 소스가 아니라 `/api/dev-oidc/**`에 둔 이유다.
#
# "자격정보가 없으면 등록이 만들어지지 않는다"는 fail-closed 계약은 이제 compose가 아니라
# `DevOidcStubProfileTest`가 지킨다. 그 시험은 기본 프로필에서 스텁 빈이 하나도 없고
# `/api/dev-oidc/**`가 노출되지 않으며, 스텁 프로필이 실제 제공자 등록과 함께 활성이면 기동이
# 거부되는 것을 단정한다.

# 세션 확인. 인증 여부와 목적지 종류만 담는다. `user_id`·`subject`·토큰이 응답에 없다(AD-14).
fetch GET /api/auth/session
assert_equals '`GET /api/auth/session` 상태 200' "${last_status}" '200'
assert_equals '`GET /api/auth/session` Content-Type' "$(header_value 'Content-Type')" 'application/json'
assert_equals '`GET /api/auth/session` 본문은 상태와 목적지 종류뿐' \
  "$(cat "${last_body}")" '{"authenticated":false,"destination":"entry"}'
assert_body_lacks '`GET /api/auth/session` 응답에 계정 식별자가 없다' 'user_id'
# 미인증 응답에는 CSRF 토큰이 실리지 않는다. 세션에 귀속되지 않은 토큰은 어떤 상태 변경도
# 통과시키지 못하고, 실으려면 그 필드를 채우려고 세션을 만드는 경로가 열린다.
assert_body_lacks '미인증 세션 확인에 CSRF 토큰이 없다' 'csrfToken'
assert_security_headers '`GET /api/auth/session`'

# 세션 확인만으로 세션이 만들어지지 않는다. 쿠키를 주면 그 자체가 상태 변경이다.
if [ -z "$(header_value 'Set-Cookie')" ]; then
  record_pass '`GET /api/auth/session`이 세션 쿠키를 만들지 않는다'
else
  record_fail '`GET /api/auth/session`이 세션 쿠키를 만들지 않는다' \
    "Set-Cookie가 있다 — $(header_value 'Set-Cookie')"
fi

# 모르는 제공자. 그 경로는 존재하지 않는다. 등록되지 않은 제공자로는 흐름이 시작되지 않는다.
#
# 탐침의 이름이 브랜드가 아니다. Story 1.6까지 이 자리가 `kakao`였고, Story 1.7이 카카오를 아는
# 제공자로 만들면서 그 단정은 통과하되 뜻을 잃었다 — 404를 고정하던 자리가 실제 등록을 만나면
# 판정이 뒤집힌다. 등록될 수 없는 이름이면 다음 제공자가 들어와도 그 일이 되풀이되지 않는다.
fetch GET /api/auth/authorize/unregistered-provider
assert_equals '`GET /api/auth/authorize/unregistered-provider` 상태 404' "${last_status}" '404'
assert_body_contains '모르는 제공자 안정 code' '"code":"NOT_FOUND"'
# 아는 제공자 이름이 응답에 새지 않는다. 되비추면 이 경로가 곧 제공자 목록을 열거하는 표면이 된다.
assert_body_lacks '모르는 제공자 응답에 Google 이름이 없다' 'google'
assert_body_lacks '모르는 제공자 응답에 네이버 이름이 없다' 'naver'
assert_body_lacks '모르는 제공자 응답에 카카오 이름이 없다' 'kakao'
# 요청한 이름도 되비추지 않는다. 되비추면 그 경로가 곧 반사 표면이 된다.
assert_body_lacks '모르는 제공자 응답이 요청한 이름을 되비추지 않는다' 'unregistered-provider'

# 세션에 귀속되지 않은 콜백. 시작한 흐름이 없으므로 인증 화면으로 수렴한다.
# 표지는 닫힌 값 하나이며 code·state를 되돌려주지 않는다. 모든 제공자의 답이 같아야 한다 —
# 갈리면 그 차이가 곧 "어느 제공자로 흐름이 있었는가"를 알려 주는 신호가 된다.
callback_probe_count=0
while read -r probe_provider; do
  [ -n "${probe_provider}" ] || continue
  callback_probe_count=$((callback_probe_count + 1))
  fetch GET "/api/auth/callback/${probe_provider}?code=probe-code&state=probe-state"
  assert_equals "흐름 없는 ${probe_provider} 콜백 상태 302" "${last_status}" '302'
  assert_equals "흐름 없는 ${probe_provider} 콜백은 인증 화면으로 수렴한다" \
    "$(resolve_location "$(header_value 'Location')")" "${base_url}/?auth=failed"
  assert_value_lacks "${probe_provider} 콜백 목적지가 code를 되돌려주지 않는다" \
    "$(header_value 'Location')" 'probe-code'
  assert_value_lacks "${probe_provider} 콜백 목적지가 state를 되돌려주지 않는다" \
    "$(header_value 'Location')" 'probe-state'
  assert_value_lacks "${probe_provider} 콜백 목적지에 제공자 이름이 없다" \
    "$(header_value 'Location')" "${probe_provider}"
  assert_body_lacks "${probe_provider} 콜백 응답이 code를 되돌려주지 않는다" 'probe-code'
  assert_body_lacks "${probe_provider} 콜백 응답에 계정 식별자가 없다" 'user_id'
  assert_security_headers "\`GET /api/auth/callback/${probe_provider}\` (흐름 없음)"
done <<CALLBACK_PROVIDERS
${providers}
CALLBACK_PROVIDERS

# 이 루프도 제공자 수만큼 돌았다. 없는 단정은 실패로 보이지 않으므로 iteration 수 자체를 세는
# 자리가 있어야 leg 하나가 조용히 사라지는 사고가 붉게 나온다(아래 왕복 루프와 같은 근거이며,
# Story 1.6이 실제로 겪은 결함이 그 형태였다).
assert_equals '흐름 없는 콜백 루프가 제공자 수만큼 돌았다' "${callback_probe_count}" "${provider_count}"

# 상태 변경 요청은 동일 출처 `Origin`과 세션 귀속 CSRF 토큰을 함께 요구한다.
fetch POST /api/auth/session
assert_equals '`POST /api/auth/session` 상태 403' "${last_status}" '403'
assert_body_contains '`POST /api/auth/session` 안정 code' '"code":"FORBIDDEN"'
assert_correlation_id '`POST /api/auth/session` correlation ID'

fetch POST /api/auth/session -H 'Origin: https://evil.example'
assert_equals '다른 출처의 `Origin`도 403' "${last_status}" '403'
assert_body_contains '다른 출처 거절의 안정 code' '"code":"FORBIDDEN"'

# CSRF 검사가 인증 경로 밖으로 새지 않는다. Story 1.2가 고정한 405가 403으로 뒤집히면 여기서 걸린다.
fetch POST /api/status
assert_equals 'CSRF 검사가 `POST /api/status`의 405를 바꾸지 않는다' "${last_status}" '405'

# 세션 테이블이 실제로 만들어졌다. V2 마이그레이션이 적용됐다는 증거다.
session_tables="$(psql_value "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('spring_session', 'spring_session_attributes')")"
if [ "${session_tables}" = '2' ]; then
  record_pass '마이그레이션이 세션 두 테이블을 만들었다'
else
  record_fail '마이그레이션이 세션 두 테이블을 만들었다' \
    "실제 '${session_tables}' (기대 '2') — $(head -c 300 "${work_dir}/psql-err.txt" 2>/dev/null)"
fi

# 위 요청 중 어느 것도 세션을 만들지 않았다. 상태 코드는 "그 요청이 실패했다"까지만 말해 준다.
session_rows="$(psql_value 'SELECT count(*) FROM spring_session')"
if [ "${session_rows}" = '0' ]; then
  record_pass '인증 표면 요청으로 세션이 하나도 만들어지지 않았다'
else
  record_fail '인증 표면 요청으로 세션이 하나도 만들어지지 않았다' \
    "spring_session 행 수가 '${session_rows}'다 — $(head -c 300 "${work_dir}/psql-err.txt" 2>/dev/null)"
fi

# 계정도 늘지 않았다. 앞의 identity 탐침과 같은 질의이며, 아래 성공 왕복 앞에서 한 번 더 센다.
# 이 단정이 왕복보다 앞에 있어야 "계정이 그 왕복에서만 늘었다"가 말이 된다.
lifecycle_rows="$(psql_value 'SELECT count(*) FROM identity_account_lifecycle')"
if [ "${lifecycle_rows}" = '0' ]; then
  record_pass '인증 표면 요청으로 계정이 하나도 만들어지지 않았다'
else
  record_fail '인증 표면 요청으로 계정이 하나도 만들어지지 않았다' \
    "identity_account_lifecycle 행 수가 '${lifecycle_rows}'다"
fi

printf '\n스텁 제공자 성공 왕복\n'

# 이 실행의 스텁 `subject` 표지. 제공자 이름이 앞에 붙어 왕복마다 값이 갈리고, 실행마다 달라지므로
# 저장소에 남는 고정 리터럴이 되지 않는다. `(issuer, subject)`가 전역 유일 키이고 issuer가
# 제공자마다 다르므로 세 왕복은 같은 표지를 써도 별개 계정이다.
stub_subject_marker="verify-subject-$$"

# 인가 시작 → 동의 → 콜백 → 세션 쿠키 → 목적지. 여기까지가 Story 1.4의 성공 경로이고 Story 1.6·1.7이
# 그것을 제공자마다 한 번씩 돌린다. 앞의 두 저장소 단정이 0을 확인했으므로, 아래에서 늘어난 행은
# 이 왕복들이 만든 것뿐이다. 끝에서 그 행들을 지워 스택을 원래 상태로 되돌린다 — 이 스크립트 뒤에
# 오는 Playwright 회귀가 자기 왕복으로 무엇을 만들었는지 세려면 저장소가 비어 있어야 한다.
#
# 왕복마다 서로 다른 계정이 생긴다. 제공자마다 스텁 인스턴스가 하나이고 issuer가 갈리므로
# (`DevOidcStubConfig`) 같은 `subject`가 와도 `(issuer, subject)`가 달라 별개 계정이며, 아래 행 수가
# 왕복마다 하나씩 늘어나는 것이 그 사실이다.

# round_trip <제공자>
#
# 한 제공자의 왕복을 지나고 그 결과를 전역에 남긴다. 왕복 논리를 제공자마다 복사하면 단계 하나가
# 갈라져도 아무도 모르므로, 단정까지 이 함수 안에 둔다.
#
# 남기는 값: rt_flow_cookie · rt_session_cookie · rt_csrf_token · rt_code · rt_state
round_trip() {
  local provider="$1"
  local stub_base="/api/dev-oidc/${provider}"

  # 결과 전역을 진입에서 되돌린다.
  #
  # 이 다섯 값은 `local`이 아니다. 그럴 수 없다 — bash의 `local`은 함수가 끝나면 값을 되돌리므로,
  # 루프의 `case`가 이 이름들을 `google_*`·`naver_*`로 옮기는 경로가 `set -u` 아래에서 통째로
  # 죽는다(그 `case`는 함수가 반환한 **뒤에** 돈다). 그래서 전역으로 남기고 대신 진입에서 비운다.
  #
  # 비우지 않으면 leg 하나가 중간에 사라질 때 다음 leg이 앞 leg의 값을 물려받는다. 이 스토리가
  # 방금 고친 결함이 정확히 그 형태였고(루프 안의 psql이 heredoc을 먹어 네이버 iteration이 시작조차
  # 하지 않았다), 그때 남는 상태가 이 전역들이다 — 그러면 `case`가 google의 쿠키·code·state를
  # `naver_*`로 옮기고, "세 왕복이 서로 다른 세션을 만든다" 같은 단정이 같은 값을 두 번 보며
  # 통과한다. 빈 값이면 그 자리에서 guard가 이름을 말하고 실패한다.
  rt_flow_cookie=''
  rt_session_cookie=''
  rt_csrf_token=''
  rt_code=''
  rt_state=''

  # 스텁의 discovery 문서. 이 애플리케이션은 discovery 왕복을 하지 않지만, 문서를 두고 검증하지
  # 않으면 그 경로가 아무도 보지 않는 표면이 된다.
  fetch GET "${stub_base}/.well-known/openid-configuration"
  assert_equals "${provider} 스텁 discovery 상태 200" "${last_status}" '200'
  assert_equals "${provider} 스텁 discovery Content-Type" \
    "$(header_value 'Content-Type')" 'application/json'
  # issuer가 제공자별 경로 세그먼트를 포함한다. 두 스텁의 issuer가 같으면 한쪽이 서명한
  # ID Token을 다른 쪽의 JWKS로 검증하게 되고, 그때 제공자들의 식별자가 한 namespace에 섞인다.
  assert_body_contains "${provider} 스텁 discovery의 issuer가 제공자별 공개 origin이다" \
    "\"issuer\":\"${base_url}${stub_base}\""
  assert_body_contains "${provider} 스텁 discovery가 S256만 알린다" \
    '"code_challenge_methods_supported":["S256"]'

  # 스텁 JWKS. 공개 키만 나간다.
  fetch GET "${stub_base}/jwks"
  assert_equals "${provider} 스텁 JWKS 상태 200" "${last_status}" '200'
  assert_body_contains "${provider} 스텁 JWKS에 RSA 공개 키가 있다" '"kty":"RSA"'
  assert_body_lacks "${provider} 스텁 JWKS에 개인 키 성분이 없다" '"d":'

  # 1) 인가 시작. 제공자(=스텁)의 인가 엔드포인트로 302다.
  fetch GET "/api/auth/authorize/${provider}"
  assert_equals "\`GET /api/auth/authorize/${provider}\` 상태 302" "${last_status}" '302'
  local authorize_location
  authorize_location="$(header_value 'Location')"
  assert_prefix "${provider} 인가 시작이 그 제공자 스텁의 인가 엔드포인트로 보낸다" \
    "${authorize_location}" "${base_url}${stub_base}/authorize"
  assert_value_contains "${provider} 인가 요청이 PKCE S256을 쓴다" \
    "${authorize_location}" 'code_challenge_method=S256'
  # 파라미터를 정확히 비교한다. 부분 문자열로 보면 `scope=openid%20profile`·`scope=openid+email`이
  # 그대로 통과해 "기본 동의 범위는 openid뿐이다"가 확인되지 않는다. 네이버 discovery는
  # `profile`도 광고하므로 이 단정이 네이버에서 특히 중요하다.
  local authorize_scope
  authorize_scope="$(printf '%s' "${authorize_location}" | sed -n 's/.*[?&]scope=\([^&]*\).*/\1/p')"
  assert_equals "${provider} 인가 요청의 동의 범위는 openid뿐이다" "${authorize_scope}" 'openid'
  assert_value_contains "${provider} 인가 요청에 nonce가 있다" "${authorize_location}" 'nonce='
  assert_value_contains "${provider} 인가 요청에 redirect URI가 있다" \
    "${authorize_location}" 'redirect_uri='
  # verifier는 서버가 세션에만 들고 있다. 제공자에게 가는 것은 challenge뿐이다.
  assert_value_lacks "${provider} 인가 요청에 PKCE verifier가 실리지 않는다" \
    "${authorize_location}" 'code_verifier'
  rt_flow_cookie="$(session_cookie_value)"
  if [ -n "${rt_flow_cookie}" ]; then
    record_pass "${provider} 인가 시작이 흐름 세션을 만든다"
  else
    record_fail "${provider} 인가 시작이 흐름 세션을 만든다" 'Set-Cookie에 세션 쿠키가 없다'
  fi

  # 2) 동의 화면. 실제 제공자의 동의 화면 자리이며 조작은 링크 하나뿐이다.
  #    폼도 인라인 스크립트도 쓰지 않으므로 `form-action 'none'` 아래에서 그대로 동작한다.
  #
  #    스텁의 `subject`를 이 왕복이 고정한다. 기본값은 요청마다 새 UUID이고 그래서 개발 스택의
  #    왕복은 늘 신규 가입이지만, 그 상태에서는 "같은 사람이 다시 돌아오는" 흐름을 이 계층에서
  #    만들 수 없다 — 재인증이 정확히 그 흐름이다. 값은 스크립트가 고르므로 저장소에 식별자
  #    리터럴이 남지 않고, 아래 재인증 절이 같은 값을 다시 보낸다.
  fetch GET "${authorize_location#"${base_url}"}&dev_subject=${provider}-${stub_subject_marker}"
  assert_equals "${provider} 스텁 동의 화면 상태 200" "${last_status}" '200'
  assert_prefix "${provider} 스텁 동의 화면 Content-Type" \
    "$(header_value 'Content-Type')" 'text/html'
  assert_body_contains "${provider} 스텁 동의 화면이 실제 제공자가 아님을 밝힌다" \
    '실제 제공자가 아닙니다'
  assert_body_contains "${provider} 스텁 동의 화면에 동의 링크가 있다" '동의하고 계속하기'
  assert_security_headers "${provider} 스텁 동의 화면"

  # 동의 링크의 목적지. HTML escape를 되돌려 그대로 따라간다.
  local consent_href
  consent_href="$(sed -n 's/.*<a href="\([^"]*\)".*/\1/p' "${last_body}" | head -1 | sed 's/&amp;/\&/g')"
  assert_prefix "${provider} 동의 링크가 공개 origin의 그 제공자 콜백으로 되돌린다" \
    "${consent_href}" "${base_url}/api/auth/callback/${provider}"

  # 이 왕복의 authorization code와 `state`. 아래 로그 누출 검사가 이 값들을 찾는다.
  # 조각 목록에 넣을 수 없는 값이라 여기서 잡아 둔다 — 실행마다 달라지는 것이 그 성질이다.
  rt_code="$(printf '%s' "${consent_href}" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')"
  rt_state="$(printf '%s' "${consent_href}" | sed -n 's/.*[?&]state=\([^&]*\).*/\1/p')"

  # 3) 콜백. 검증을 통과하면 계정·매핑·세션이 한 번에 생기고 신규 사용자는 온보딩으로 간다.
  fetch GET "${consent_href#"${base_url}"}" -H "Cookie: BOKRILOG_SESSION=${rt_flow_cookie}"
  assert_equals "${provider} 검증된 콜백 상태 302" "${last_status}" '302'
  local callback_location
  callback_location="$(header_value 'Location')"
  assert_equals "${provider} 신규 사용자는 온보딩으로 간다" \
    "$(resolve_location "${callback_location}")" "${base_url}/onboarding"
  assert_value_lacks "${provider} 콜백 목적지에 code가 없다" "${callback_location}" 'code='
  assert_value_lacks "${provider} 콜백 목적지에 state가 없다" "${callback_location}" 'state='
  assert_body_lacks "${provider} 콜백 응답에 계정 식별자가 없다" 'user_id'

  # 세션 쿠키. 브라우저에 남는 유일한 값이며 속성 세 가지가 계약이다.
  rt_session_cookie="$(session_cookie_value)"
  local set_cookie_header
  set_cookie_header="$(header_value 'Set-Cookie')"
  assert_value_contains "${provider} 세션 쿠키 Path" "${set_cookie_header}" 'Path=/'
  assert_value_contains "${provider} 세션 쿠키 HttpOnly" "${set_cookie_header}" 'HttpOnly'
  assert_value_contains "${provider} 세션 쿠키 Secure" "${set_cookie_header}" 'Secure'
  assert_value_contains "${provider} 세션 쿠키 SameSite=Lax" "${set_cookie_header}" 'SameSite=Lax'
  if [ -n "${rt_session_cookie}" ] && [ "${rt_session_cookie}" != "${rt_flow_cookie}" ]; then
    record_pass "${provider} 로그인 시점에 세션 ID가 회전한다"
  else
    record_fail "${provider} 로그인 시점에 세션 ID가 회전한다" \
      '콜백이 새 세션 쿠키를 주지 않았다. 세션 고정 방어가 성립하지 않는다.'
  fi

  # 4) 세션 확인. 화면이 상태를 확인하는 유일한 경로이며 인증 여부·목적지 종류·세션 귀속 CSRF
  #    토큰만 담는다. 토큰이 여기 실리는 것이 브라우저에 쿠키를 하나로 유지하는 배치다.
  fetch GET /api/auth/session -H "Cookie: BOKRILOG_SESSION=${rt_session_cookie}"
  assert_equals "${provider} 로그인 뒤 \`GET /api/auth/session\` 상태 200" "${last_status}" '200'
  # 토큰 값만 가리고 전문을 비교한다. 값은 실행마다 달라 고정할 수 없지만 고정하려는 것은 값이
  # 아니라 무엇이 나가는가이며, 조각 포함 검사만 남기면 응답에 필드가 하나 늘어도 통과한다.
  #
  # 세 제공자의 본문이 글자까지 같다. 갈리면 그 차이가 곧 "어느 제공자로 로그인했는가"를 알려
  # 주는 신호이고, 화면은 그것을 알 이유가 없다.
  assert_equals "${provider} 로그인 뒤 세션 확인 본문은 상태·목적지·토큰뿐" \
    "$(sed 's/"csrfToken":"[^"]*"/"csrfToken":"<가림>"/' "${last_body}")" \
    '{"authenticated":true,"destination":"onboarding","csrfToken":"<가림>"}'
  assert_body_lacks "${provider} 로그인 뒤 세션 확인에 계정 식별자가 없다" 'user_id'
  assert_body_lacks "${provider} 로그인 뒤 세션 확인에 제공자 토큰이 없다" 'id_token'
  # 제공자 이름도 응답에 없다. 위 전문 비교가 이미 그것을 막지만 의도를 이름으로 남긴다.
  assert_body_lacks "${provider} 로그인 뒤 세션 확인에 제공자 이름이 없다" "${provider}"
  # 세션 식별자가 응답 본문에 실리지 않는다. 실리면 그 자체가 세션 유출이다.
  assert_body_lacks "${provider} 로그인 뒤 세션 확인에 세션 쿠키 값이 없다" "${rt_session_cookie}"
  assert_cache_control "${provider} 로그인 뒤 세션 확인은 저장하지 않는다" \
    "${expected_document_cache_control}"

  # 상태 변경 요청에 실을 토큰. 쿠키 저장소를 쓰지 않으므로 이 본문이 유일한 통로다.
  rt_csrf_token="$(sed -n 's/.*"csrfToken":"\([^"]*\)".*/\1/p' "${last_body}")"
  if [ -n "${rt_csrf_token}" ]; then
    record_pass "${provider} 로그인 뒤 세션 확인이 세션 귀속 CSRF 토큰을 준다"
  else
    record_fail "${provider} 로그인 뒤 세션 확인이 세션 귀속 CSRF 토큰을 준다" \
      "본문에 csrfToken이 없다 — $(head -c 200 "${last_body}")"
  fi

  # 5) 저장된 `provider` 값. 인증에 실제로 쓰인 registration id가 들어가야 한다.
  #    이 컬럼에 리터럴이 들어가면 어느 제공자로 로그인해도 같은 이름이 남고, 그 컬럼을 술어로
  #    쓰는 이후 스토리(로그인 수단 연결·해제)가 거짓을 읽는다.
  assert_rows "${provider} 매핑의 provider 컬럼이 인증에 쓰인 이름이다" \
    "SELECT count(*) FROM identity_social_link WHERE provider = '${provider}'" '1'
}

# 왕복을 제공자마다 한 번씩 돈다. 행 수가 왕복마다 하나씩 늘어나는 것이 "왕복들이 서로 다른
# 계정을 만든다"의 증거다.
#
# 왕복 결과 변수를 루프 앞에서 빈 값으로 선언한다. `set -u` 아래에서 어느 leg이 돌지 않으면
# 그 이름을 처음 읽는 줄에서 스크립트가 즉시 죽고, 그 뒤의 단정 수백 개가 전부 사라진다 —
# 무엇이 빠졌는지 말해 주는 아래 두 guard도 그 안에 있어서 함께 사라진다. 빈 값으로 두면
# guard가 실제로 돌아 "어느 제공자의 쿠키·토큰을 얻지 못했다"를 단정 실패로 보고한다.
google_session_cookie=''
google_csrf_token=''
google_round_trip_code=''
google_round_trip_state=''
naver_session_cookie=''
naver_csrf_token=''
naver_round_trip_code=''
naver_round_trip_state=''
kakao_session_cookie=''
kakao_csrf_token=''
kakao_round_trip_code=''
kakao_round_trip_state=''

round_trip_count=0
while read -r rt_provider; do
  [ -n "${rt_provider}" ] || continue
  round_trip "${rt_provider}"
  round_trip_count=$((round_trip_count + 1))

  assert_rows "왕복 ${round_trip_count}회 뒤 계정이 ${round_trip_count}개다" \
    'SELECT count(*) FROM identity_account_lifecycle' "${round_trip_count}"
  assert_rows "왕복 ${round_trip_count}회 뒤 인증 매핑이 ${round_trip_count}행이다" \
    'SELECT count(*) FROM identity_social_link' "${round_trip_count}"
  assert_rows "왕복 ${round_trip_count}회 뒤 세션이 ${round_trip_count}행이다" \
    'SELECT count(*) FROM spring_session' "${round_trip_count}"
  # 세션 본문도 PostgreSQL에 있다. 쿠키는 불투명 식별자뿐이고 상태는 서버가 들고 있다.
  assert_rows '세션 속성이 PostgreSQL에 산다' \
    'SELECT (count(*) > 0)::int FROM spring_session_attributes' '1'

  # 왕복별 값을 이름 있는 변수로 옮긴다. 아래 로그아웃 절과 로그 누출 검사가 세 왕복의 값을
  # 함께 본다. bash 3.2에는 연관 배열이 없으므로 제공자가 셋인 지금은 이 분기가 가장 단순하다.
  case "${rt_provider}" in
    google)
      google_session_cookie="${rt_session_cookie}"
      google_csrf_token="${rt_csrf_token}"
      google_round_trip_code="${rt_code}"
      google_round_trip_state="${rt_state}"
      ;;
    naver)
      naver_session_cookie="${rt_session_cookie}"
      naver_csrf_token="${rt_csrf_token}"
      naver_round_trip_code="${rt_code}"
      naver_round_trip_state="${rt_state}"
      ;;
    kakao)
      kakao_session_cookie="${rt_session_cookie}"
      kakao_csrf_token="${rt_csrf_token}"
      kakao_round_trip_code="${rt_code}"
      kakao_round_trip_state="${rt_state}"
      ;;
    *)
      record_fail '왕복 결과를 아는 제공자로 옮긴다' "모르는 제공자다 — ${rt_provider}"
      ;;
  esac
done <<ROUND_TRIP_PROVIDERS
${providers}
ROUND_TRIP_PROVIDERS

# 루프가 제공자 수만큼 돌았다. 이 단정이 없으면 leg 하나가 조용히 사라져도 로그에는 실패가
# 아니라 **없음**으로 남고, 없는 단정은 아무도 세지 않는다. 실제로 그 상태가 한 번 있었다 —
# 루프 안의 psql이 heredoc에 남은 제공자 이름을 먹어 네이버 왕복이 시작조차 하지 않았고,
# 통과 수만 줄어들 뿐 아무것도 붉게 나오지 않았다. 원인은 `psql_value`에서 닫았고 이 단정은
# 같은 형태의 다음 사고를 잡는 자리다.
assert_equals '왕복 루프가 제공자 수만큼 돌았다' "${round_trip_count}" "${provider_count}"

# 세 왕복이 서로 다른 계정을 만들었다. 같은 계정이면 제공자 식별자가 병합된 것이다.
assert_rows '세 왕복이 서로 다른 계정을 만들었다' \
  'SELECT count(DISTINCT user_id) FROM identity_social_link' '3'
# 이 시점에는 한 계정이 두 매핑을 갖지 않는다. 연결 경로는 아래 `연결 왕복` 절이 처음 지난다.
assert_rows '한 계정이 두 제공자 매핑을 갖지 않는다' \
  'SELECT count(*) FROM (SELECT user_id FROM identity_social_link GROUP BY user_id HAVING count(*) > 1) multi' \
  '0'
# 저장된 제공자 이름이 정확히 셋이다. 어느 행도 다른 제공자의 이름을 갖지 않는다.
assert_rows '저장된 제공자 이름이 정확히 셋이다' \
  'SELECT count(DISTINCT provider) FROM identity_social_link' '3'

# 세 세션이 서로 다르다. 같으면 뒤의 왕복이 앞 세션을 갈아탄 것이다.
if [ -n "${google_session_cookie}" ] && [ -n "${naver_session_cookie}" ] \
  && [ -n "${kakao_session_cookie}" ] \
  && [ "${google_session_cookie}" != "${naver_session_cookie}" ] \
  && [ "${google_session_cookie}" != "${kakao_session_cookie}" ] \
  && [ "${naver_session_cookie}" != "${kakao_session_cookie}" ]; then
  record_pass '세 왕복이 서로 다른 세션을 만든다'
else
  record_fail '세 왕복이 서로 다른 세션을 만든다' \
    '세 왕복의 세션 쿠키를 얻지 못했거나 같은 값이 섞여 있다. 아래 보존 단정이 아무것도 보지 않는다.'
fi

printf '\n재인증 왕복\n'

# 인증된 세션의 자발 재인증이다. Story 1.8의 HTTP 계약을 보는 유일한 자리이며, 표시된 세션의
# 회복은 여기서 만들 수 없다 — `SessionGuard`에는 HTTP 표면이 없고 세션 속성은 Java 직렬화라
# SQL로도 만들 수 없다. 그 축은 백엔드 시험이 소유한다(`AuthReauthenticationTest`).
#
# 위 왕복이 만든 Google 세션을 쓴다. 회전 뒤에는 쿠키와 토큰이 새 값이 되므로 아래에서 두 전역을
# 갱신한다 — 갱신하지 않으면 뒤따르는 로그아웃 절이 죽은 쿠키로 403을 받고, 그 실패는 "로그아웃이
# 깨졌다"로 잘못 읽힌다.

reauth_session_cookie="${google_session_cookie}"
reauth_csrf_token="${google_csrf_token}"

if [ -n "${reauth_session_cookie}" ] && [ -n "${reauth_csrf_token}" ]; then
  record_pass '재인증에 쓸 세션 쿠키와 토큰을 얻었다'
else
  record_fail '재인증에 쓸 세션 쿠키와 토큰을 얻었다' \
    'Google 왕복의 쿠키나 토큰이 없다. 아래 단정이 아무것도 보지 않는다.'
fi

# 1) 시작의 보호. `/api/auth/` 아래 상태 변경이므로 로그아웃과 같은 계약을 예외 없이 지난다.
fetch POST /api/auth/reauthenticate/google \
  -H "Cookie: BOKRILOG_SESSION=${reauth_session_cookie}" \
  -H "Origin: ${base_url}"
assert_equals '토큰 없는 재인증 시작 403' "${last_status}" '403'
assert_body_contains '토큰 없는 재인증 시작 안정 code' '"code":"FORBIDDEN"'
assert_correlation_id '토큰 없는 재인증 시작 correlation ID'

fetch POST /api/auth/reauthenticate/google \
  -H "Cookie: BOKRILOG_SESSION=${reauth_session_cookie}" \
  -H 'Origin: https://evil.example' \
  -H "X-CSRF-TOKEN: ${reauth_csrf_token}"
assert_equals '교차 출처 재인증 시작 403' "${last_status}" '403'
assert_body_contains '교차 출처 재인증 시작 안정 code' '"code":"FORBIDDEN"'

fetch POST /api/auth/reauthenticate/google -H "Origin: ${base_url}" \
  -H 'X-CSRF-TOKEN: verifyanonymousreauthmarker'
assert_equals '미인증 재인증 시작 403' "${last_status}" '403'
assert_body_contains '미인증 재인증 시작 안정 code' '"code":"FORBIDDEN"'

# 모르는 제공자는 그 경로가 존재하지 않는 것이다. 아는 이름도 개수도 응답에 실리지 않는다.
fetch POST /api/auth/reauthenticate/nosuchprovider \
  -H "Cookie: BOKRILOG_SESSION=${reauth_session_cookie}" \
  -H "Origin: ${base_url}" \
  -H "X-CSRF-TOKEN: ${reauth_csrf_token}"
assert_equals '미지 제공자 재인증 시작 404' "${last_status}" '404'
assert_body_contains '미지 제공자 재인증 시작 안정 code' '"code":"NOT_FOUND"'
assert_body_lacks '미지 제공자 응답이 이름을 되비추지 않는다' 'nosuchprovider'
assert_body_lacks '미지 제공자 응답에 아는 제공자 이름이 없다' 'google'

# 위 네 거절 중 어느 것도 상태를 바꾸지 않았다.
assert_rows '재인증 시작 거절이 세션을 만들지 않는다' 'SELECT count(*) FROM spring_session' '3'

# 2) 무장. 본문 없는 204이고 세션 행 수는 그대로다.
#
# 무장 전의 속성 행 수를 먼저 센다. "행이 하나라도 있다"를 보는 단정은 앞선 세 로그인이 남긴
# `SPRING_SECURITY_CONTEXT`·목적지 속성 때문에 이미 참이고, 그러면 무장 호출을 통째로 지워도
# green이다 — 검사가 아무것도 관측하지 않는 상태다. 델타를 보면 그 호출이 실제로 행을 하나
# 더했는지가 드러난다.
reauth_attributes_before="$(psql_value 'SELECT count(*) FROM spring_session_attributes')"

fetch POST /api/auth/reauthenticate/google \
  -H "Cookie: BOKRILOG_SESSION=${reauth_session_cookie}" \
  -H "Origin: ${base_url}" \
  -H "X-CSRF-TOKEN: ${reauth_csrf_token}"
assert_equals '재인증 시작 204' "${last_status}" '204'
if [ ! -s "${last_body}" ]; then
  record_pass '재인증 시작 응답에 본문이 없다'
else
  record_fail '재인증 시작 응답에 본문이 없다' "$(head -c 200 "${last_body}")"
fi
assert_security_headers '재인증 시작 204'
assert_rows '재인증 시작이 세션 행을 늘리지 않는다' 'SELECT count(*) FROM spring_session' '3'

# 무장은 세션 속성 **하나**다. 새 컬럼도 새 테이블도 만들지 않았다.
#
# 델타가 정확히 1이어야 한다. 0이면 무장이 저장되지 않은 것이고(그때 아래 인가 시작은 재인증이
# 아니라 거절로 끝난다), 2 이상이면 이 스토리가 세션에 값을 하나보다 많이 심은 것이다.
reauth_attributes_after="$(psql_value 'SELECT count(*) FROM spring_session_attributes')"
if [ -n "${reauth_attributes_before}" ] && [ -n "${reauth_attributes_after}" ] \
  && [ "$((reauth_attributes_after - reauth_attributes_before))" = '1' ]; then
  record_pass '무장이 세션 속성 하나를 더한다'
else
  record_fail '무장이 세션 속성 하나를 더한다' \
    "속성 행이 '${reauth_attributes_before}'에서 '${reauth_attributes_after}'로 바뀌었다 (기대 +1)"
fi

# 3) 인가 시작. 인증된 세션인데도 통과한다 — 무장이 그 예외를 만든다.
fetch GET /api/auth/authorize/google -H "Cookie: BOKRILOG_SESSION=${reauth_session_cookie}"
assert_equals '무장된 세션의 인가 시작 302' "${last_status}" '302'
reauth_authorize_location="$(header_value 'Location')"
assert_prefix '재인증 인가 시작이 제공자 스텁으로 간다' \
  "${reauth_authorize_location}" "${base_url}/api/dev-oidc/google/authorize"
# 공통 `max_age`. OIDC Core가 이 파라미터와 함께 `auth_time`을 필수로 만든다.
assert_value_contains '재인증 인가 요청이 max_age를 보낸다' \
  "${reauth_authorize_location}" 'max_age=300'
# Google이 공표한 증거 요청. essential `auth_time`이 `claims`에 실린다.
assert_value_contains 'Google 재인증 인가 요청이 essential auth_time을 요청한다' \
  "${reauth_authorize_location}" 'auth_time'
# 공표하지 않은 수단은 보내지 않는다. Google 문서의 `prompt` 목록에 `login`이 없다.
assert_value_lacks 'Google 재인증 인가 요청에 prompt가 없다' \
  "${reauth_authorize_location}" 'prompt='
# 기존 검증 재료가 그대로다. 파라미터를 덮어썼다면 여기서 먼저 걸린다.
assert_value_contains '재인증 인가 요청이 PKCE S256을 쓴다' \
  "${reauth_authorize_location}" 'code_challenge_method=S256'
assert_value_contains '재인증 인가 요청에 nonce가 있다' "${reauth_authorize_location}" 'nonce='
assert_value_lacks '재인증 인가 요청에 PKCE verifier가 실리지 않는다' \
  "${reauth_authorize_location}" 'code_verifier'

# 4) 동의 화면. 로그인과 같은 화면이며 제공자 이름을 적지 않는다.
# 로그인 왕복과 **같은** `subject`를 보낸다. 재인증은 검증된 식별자가 현재 사용자의 것인지를
# 묻는 것이므로, 다른 값을 보내면 이 절은 성공 경로가 아니라 "미연결 식별자" 실패를 본다.
fetch GET "${reauth_authorize_location#"${base_url}"}&dev_subject=google-${stub_subject_marker}"
assert_equals '재인증 동의 화면 200' "${last_status}" '200'
reauth_consent_href="$(sed -n 's/.*<a href="\([^"]*\)".*/\1/p' "${last_body}" | head -1 | sed 's/&amp;/\&/g')"
assert_prefix '재인증 동의 링크가 공개 origin의 콜백으로 되돌린다' \
  "${reauth_consent_href}" "${base_url}/api/auth/callback/google"
reauth_code="$(printf '%s' "${reauth_consent_href}" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')"
reauth_state="$(printf '%s' "${reauth_consent_href}" | sed -n 's/.*[?&]state=\([^&]*\).*/\1/p')"

# 5) 콜백. 붙들어 둔 목적지로 돌아가고 세션 ID가 회전한다.
fetch GET "${reauth_consent_href#"${base_url}"}" \
  -H "Cookie: BOKRILOG_SESSION=${reauth_session_cookie}"
assert_equals '재인증 콜백 302' "${last_status}" '302'
reauth_callback_location="$(header_value 'Location')"
# 무장 시각에 붙들어 둔 목적지다. 콜백이 들고 온 어떤 값도 목적지가 되지 않는다.
assert_equals '재인증이 붙들어 둔 목적지로 돌아간다' \
  "$(resolve_location "${reauth_callback_location}")" "${base_url}/onboarding"
# 성공에는 표지가 붙지 않는다. 할 말이 없다.
assert_value_lacks '재인증 성공 목적지에 표지가 없다' "${reauth_callback_location}" 'auth='
assert_value_lacks '재인증 목적지에 code가 없다' "${reauth_callback_location}" 'code='
assert_value_lacks '재인증 목적지에 state가 없다' "${reauth_callback_location}" 'state='

reauth_rotated_cookie="$(session_cookie_value)"
if [ -n "${reauth_rotated_cookie}" ] && [ "${reauth_rotated_cookie}" != "${reauth_session_cookie}" ]; then
  record_pass '재인증 성공 시점에 세션 ID가 회전한다'
else
  record_fail '재인증 성공 시점에 세션 ID가 회전한다' \
    '콜백이 새 세션 쿠키를 주지 않았다. 재인증이 회전을 만들지 못했다.'
fi

# 행은 그대로 셋이다. 회전이 UPDATE가 아니라 INSERT로 끝나면 여기서 넷이 된다.
assert_rows '재인증이 세션 행을 늘리지 않는다' 'SELECT count(*) FROM spring_session' '3'
# 계정도 매핑도 그대로다. 재인증은 계정을 만들지도 바꾸지도 않는다.
assert_rows '재인증이 계정을 만들지 않는다' 'SELECT count(*) FROM identity_account_lifecycle' '3'
assert_rows '재인증이 인증 매핑을 만들지 않는다' 'SELECT count(*) FROM identity_social_link' '3'

# 무장은 일회성이다. 소비 뒤 속성 행 수가 무장 전으로 되돌아간다 — 되돌아오지 않으면 소비된
# 흐름이 세션에 그대로 남아 다음 콜백이 같은 무장을 다시 쓴다.
reauth_attributes_consumed="$(psql_value 'SELECT count(*) FROM spring_session_attributes')"
if [ "${reauth_attributes_consumed}" = "${reauth_attributes_before}" ]; then
  record_pass '재인증 콜백이 무장 속성을 소비한다'
else
  record_fail '재인증 콜백이 무장 속성을 소비한다' \
    "속성 행이 '${reauth_attributes_consumed}'다 (기대 '${reauth_attributes_before}')"
fi

# 6) 회전 전 쿠키는 죽었고 새 쿠키는 인증 상태다.
fetch GET /api/auth/session -H "Cookie: BOKRILOG_SESSION=${reauth_session_cookie}"
assert_equals '재인증 전 쿠키의 세션 확인은 미인증이다' \
  "$(cat "${last_body}")" '{"authenticated":false,"destination":"entry"}'

fetch GET /api/auth/session -H "Cookie: BOKRILOG_SESSION=${reauth_rotated_cookie}"
assert_equals '재인증 뒤 세션 확인 200' "${last_status}" '200'
# 본문 모양이 로그인 직후와 글자까지 같다. 재인증이 응답에 필드를 늘리지 않는다.
assert_equals '재인증 뒤 세션 확인 본문은 상태·목적지·토큰뿐' \
  "$(sed 's/"csrfToken":"[^"]*"/"csrfToken":"<가림>"/' "${last_body}")" \
  '{"authenticated":true,"destination":"onboarding","csrfToken":"<가림>"}'
assert_cache_control '재인증 뒤 세션 확인은 저장하지 않는다' "${expected_document_cache_control}"

# 7) 다른 두 사용자 세션은 그대로다.
fetch GET /api/auth/session -H "Cookie: BOKRILOG_SESSION=${naver_session_cookie}"
assert_body_contains '재인증이 다른 사용자의 세션을 건드리지 않는다' '"authenticated":true'
fetch GET /api/auth/session -H "Cookie: BOKRILOG_SESSION=${kakao_session_cookie}"
assert_body_contains '재인증이 세 번째 사용자의 세션도 건드리지 않는다' '"authenticated":true'

# 8) 무장은 일회성이다. 같은 콜백을 다시 보내도 아무것도 바뀌지 않는다.
fetch GET "${reauth_consent_href#"${base_url}"}" \
  -H "Cookie: BOKRILOG_SESSION=${reauth_rotated_cookie}"
assert_equals '두 번째 재인증 콜백 302' "${last_status}" '302'
# 무장이 소비됐으므로 검증에 넘기지 않고 지금 목적지로 되돌린다.
assert_equals '두 번째 콜백은 지금 목적지로 되돌린다' \
  "$(resolve_location "$(header_value 'Location')")" "${base_url}/onboarding"
assert_rows '두 번째 콜백이 계정을 만들지 않는다' \
  'SELECT count(*) FROM identity_account_lifecycle' '3'
assert_rows '두 번째 콜백이 세션 행을 바꾸지 않는다' 'SELECT count(*) FROM spring_session' '3'

# 회전한 쿠키와 그 세션의 새 토큰으로 전역을 갱신한다. 아래 로그아웃 절이 이 값을 쓴다.
fetch GET /api/auth/session -H "Cookie: BOKRILOG_SESSION=${reauth_rotated_cookie}"
google_session_cookie="${reauth_rotated_cookie}"
google_csrf_token="$(sed -n 's/.*"csrfToken":"\([^"]*\)".*/\1/p' "${last_body}")"
if [ -n "${google_csrf_token}" ]; then
  record_pass '재인증 뒤 세션이 새 CSRF 토큰을 준다'
else
  record_fail '재인증 뒤 세션이 새 CSRF 토큰을 준다' \
    "본문에 csrfToken이 없다 — $(head -c 200 "${last_body}")"
fi



printf '\n연결 왕복\n'

# 로그인 수단 연결이다. Story 1.10의 HTTP 계약을 보는 유일한 자리이자, 1.8의 재인증과 1.9의
# 허가가 실제로 작동하는지 보는 **첫 층**이다 — 그전까지 두 계약에는 소비자가 하나도 없었다.
#
# 위 재인증 절이 Google 세션의 재인증 시각을 막 기록했다. 연결 시작은 그 값이 신선할 때만
# 허가를 발급하므로 이 절은 그 바로 뒤에 있어야 한다.
#
# 세션 쿠키와 토큰은 재인증 절이 갱신한 값이다. 연결 성공이 세션을 한 번 더 회전시키므로 아래에서
# 두 전역을 다시 갱신한다 — 갱신하지 않으면 뒤따르는 로그아웃 절이 죽은 쿠키로 403을 받고 그
# 실패는 "로그아웃이 깨졌다"로 잘못 읽힌다.

link_session_cookie="${google_session_cookie}"
link_csrf_token="${google_csrf_token}"

if [ -n "${link_session_cookie}" ] && [ -n "${link_csrf_token}" ]; then
  record_pass '연결에 쓸 세션 쿠키와 토큰을 얻었다'
else
  record_fail '연결에 쓸 세션 쿠키와 토큰을 얻었다' \
    '재인증 뒤의 쿠키나 토큰이 없다. 아래 단정이 아무것도 보지 않는다.'
fi

# 1) 연결 상태 조회. 제공자 이름만 나간다.
fetch GET /api/auth/link -H "Cookie: BOKRILOG_SESSION=${link_session_cookie}"
assert_equals '연결 상태 조회 200' "${last_status}" '200'
assert_equals '연결 상태 조회 Content-Type' "$(header_value 'Content-Type')" 'application/json'
# 본문 전문을 고정한다. 필드가 하나 늘면 그것이 곧 외부 식별자가 새어 나갈 자리다.
#
# 뒤에 `assert_body_lacks`를 붙이지 않는다. 전문 동등성이 이미 그 계약을 통째로 소유하므로 같은
# 본문에 "무엇이 없다"를 다시 거는 단정은 실패할 수 없다.
assert_equals '연결 상태 본문은 제공자 이름 배열뿐' \
  "$(cat "${last_body}")" '{"providers":["google"]}'
assert_security_headers '연결 상태 조회'

# 미인증 조회는 공통 거부 하나다. 아는 제공자 이름도 개수도 실리지 않는다.
fetch GET /api/auth/link
assert_equals '미인증 연결 상태 조회 403' "${last_status}" '403'
# 본문은 공통 오류 계약이 소유한다. 그 모양에 제공자 이름이 들어갈 자리가 없으므로 "없다"를
# 다시 거는 단정은 실패할 수 없고, 여기서 볼 것은 code와 correlation ID 둘이다.
assert_body_contains '미인증 연결 상태 조회 안정 code' '"code":"FORBIDDEN"'
assert_correlation_id '미인증 연결 상태 조회 correlation ID'

# 접두사를 메서드가 가른다. 안전한 메서드로는 시작할 수 없고, 제공자 없는 시작은 경로가 아니다.
fetch GET /api/auth/link/naver -H "Cookie: BOKRILOG_SESSION=${link_session_cookie}"
# 그 경로는 있고 메서드가 다르다. 안전한 메서드로는 연결을 시작할 수 없다는 사실이 이 405다.
assert_equals '`GET /api/auth/link/<제공자>` 405(시작은 안전한 메서드가 아니다)' \
  "${last_status}" '405'
assert_body_contains '`GET /api/auth/link/<제공자>` 안정 code' '"code":"METHOD_NOT_ALLOWED"'
assert_body_lacks '`GET /api/auth/link/<제공자>`가 연결 상태를 돌려주지 않는다' 'providers'

fetch POST /api/auth/link \
  -H "Cookie: BOKRILOG_SESSION=${link_session_cookie}" \
  -H "Origin: ${base_url}" \
  -H "X-CSRF-TOKEN: ${link_csrf_token}"
assert_status_in '`POST /api/auth/link` 405 또는 404(시작은 제공자를 요구한다)' \
  "${last_status}" '405' '404'
assert_body_lacks '`POST /api/auth/link`가 연결을 시작하지 않는다' 'providers'
assert_rows '메서드 탐침이 허가를 만들지 않는다' 'SELECT count(*) FROM action_grant' '0'

# 2) 시작의 보호. `/api/auth/` 아래 상태 변경이므로 로그아웃·재인증과 같은 계약을 지난다.
fetch POST /api/auth/link/naver \
  -H "Cookie: BOKRILOG_SESSION=${link_session_cookie}" \
  -H "Origin: ${base_url}"
assert_equals '토큰 없는 연결 시작 403' "${last_status}" '403'
assert_body_contains '토큰 없는 연결 시작 안정 code' '"code":"FORBIDDEN"'
assert_correlation_id '토큰 없는 연결 시작 correlation ID'

fetch POST /api/auth/link/naver \
  -H "Cookie: BOKRILOG_SESSION=${link_session_cookie}" \
  -H 'Origin: https://evil.example' \
  -H "X-CSRF-TOKEN: ${link_csrf_token}"
assert_equals '교차 출처 연결 시작 403' "${last_status}" '403'
assert_body_contains '교차 출처 연결 시작 안정 code' '"code":"FORBIDDEN"'

fetch POST /api/auth/link/naver -H "Origin: ${base_url}" \
  -H 'X-CSRF-TOKEN: verifyanonymouslinkmarker'
assert_equals '미인증 연결 시작 403' "${last_status}" '403'
assert_body_contains '미인증 연결 시작 안정 code' '"code":"FORBIDDEN"'

fetch POST /api/auth/link/nosuchprovider \
  -H "Cookie: BOKRILOG_SESSION=${link_session_cookie}" \
  -H "Origin: ${base_url}" \
  -H "X-CSRF-TOKEN: ${link_csrf_token}"
assert_equals '미지 제공자 연결 시작 404' "${last_status}" '404'
assert_body_contains '미지 제공자 연결 시작 안정 code' '"code":"NOT_FOUND"'
assert_body_lacks '미지 제공자 연결 시작이 이름을 되비추지 않는다' 'nosuchprovider'
assert_body_lacks '미지 제공자 연결 시작에 아는 제공자 이름이 없다' 'naver'

# 이미 연결된 제공자. 재인증은 신선한데도 거부이며 응답은 위 넷과 구분되지 않는다.
fetch POST /api/auth/link/google \
  -H "Cookie: BOKRILOG_SESSION=${link_session_cookie}" \
  -H "Origin: ${base_url}" \
  -H "X-CSRF-TOKEN: ${link_csrf_token}"
assert_equals '이미 연결된 제공자의 연결 시작 403' "${last_status}" '403'
assert_body_contains '이미 연결된 제공자의 안정 code' '"code":"FORBIDDEN"'

# 위 다섯 거절 중 어느 것도 허가를 만들지 않았다.
assert_rows '연결 시작 거절이 허가를 만들지 않는다' 'SELECT count(*) FROM action_grant' '0'
assert_rows '연결 시작 거절이 매핑을 만들지 않는다' 'SELECT count(*) FROM identity_social_link' '3'

# 3) 무장. 본문 없는 204이고 허가 행이 정확히 하나 생긴다.
#
# 무장 전에는 그 속성이 없다. 이 줄이 없으면 아래 "정확히 하나" 단정이 앞선 절이 남긴 값을 보고
# 통과할 수 있다.
assert_rows '무장 전에는 연결 흐름 속성이 없다' \
  "SELECT count(*) FROM spring_session_attributes WHERE attribute_name = 'bokrilog.auth.link'" '0'

fetch POST /api/auth/link/naver \
  -H "Cookie: BOKRILOG_SESSION=${link_session_cookie}" \
  -H "Origin: ${base_url}" \
  -H "X-CSRF-TOKEN: ${link_csrf_token}"
assert_equals '연결 시작 204' "${last_status}" '204'
if [ ! -s "${last_body}" ]; then
  record_pass '연결 시작 응답에 본문이 없다'
else
  record_fail '연결 시작 응답에 본문이 없다' "$(head -c 200 "${last_body}")"
fi
assert_security_headers '연결 시작 204'
assert_rows '연결 시작이 허가 하나를 발급한다' 'SELECT count(*) FROM action_grant' '1'
assert_rows '발급된 허가는 아직 소비되지 않았다' \
  'SELECT count(*) FROM action_grant WHERE consumed_at IS NULL' '1'
assert_rows '연결 시작이 세션 행을 늘리지 않는다' 'SELECT count(*) FROM spring_session' '3'

# 허가 참조는 브라우저로 나가지 않는다. 서버가 세션에 들고 있다가 콜백에서 그대로 소비한다.
link_grant_reference="$(psql_value 'SELECT grant_id FROM action_grant')"
if [ -n "${link_grant_reference}" ]; then
  record_pass '허가 참조를 저장소에서 읽었다'
else
  record_fail '허가 참조를 저장소에서 읽었다' '아래 누출 검사가 아무것도 보지 않는다.'
fi

# 무장은 세션 속성 하나다. 이름으로 센다 — 전체 행 수의 델타로 보면 같은 요청이 함께 바꾸는
# 다른 속성(성공한 인증마다 갈아치우는 CSRF 토큰 등)에 그 값이 묻힌다.
assert_rows '연결 무장이 세션 속성 하나를 만든다' \
  "SELECT count(*) FROM spring_session_attributes WHERE attribute_name = 'bokrilog.auth.link'" '1'

# 4) 인가 시작. 인증된 세션인데도 통과한다 — 무장이 그 예외를 만든다.
fetch GET /api/auth/authorize/naver -H "Cookie: BOKRILOG_SESSION=${link_session_cookie}"
assert_equals '무장된 세션의 연결 인가 시작 302' "${last_status}" '302'
link_authorize_location="$(header_value 'Location')"
assert_prefix '연결 인가 시작이 대상 제공자 스텁으로 간다' \
  "${link_authorize_location}" "${base_url}/api/dev-oidc/naver/authorize"
# 연결은 재인증이 아니다. 재인증 파라미터를 얹으면 이 왕복이 매번 재인증을 요구한다.
assert_value_lacks '연결 인가 요청에 max_age가 없다' "${link_authorize_location}" 'max_age='
assert_value_lacks '연결 인가 요청에 prompt가 없다' "${link_authorize_location}" 'prompt='
# 기존 검증 재료는 그대로다.
assert_value_contains '연결 인가 요청이 PKCE S256을 쓴다' \
  "${link_authorize_location}" 'code_challenge_method=S256'
assert_value_contains '연결 인가 요청에 nonce가 있다' "${link_authorize_location}" 'nonce='
assert_value_lacks '연결 인가 요청에 PKCE verifier가 실리지 않는다' \
  "${link_authorize_location}" 'code_verifier'

# 5) 동의 화면. 위 네이버 로그인 왕복과 **다른** subject를 보낸다 — 연결은 미연결 식별자를
#    지금 계정에 붙이는 일이고, 같은 값을 보내면 "다른 계정에 연결된 식별자" 실패를 본다.
fetch GET "${link_authorize_location#"${base_url}"}&dev_subject=link-${stub_subject_marker}"
assert_equals '연결 동의 화면 200' "${last_status}" '200'
link_consent_href="$(sed -n 's/.*<a href="\([^"]*\)".*/\1/p' "${last_body}" | head -1 | sed 's/&amp;/\&/g')"
assert_prefix '연결 동의 링크가 공개 origin의 콜백으로 되돌린다' \
  "${link_consent_href}" "${base_url}/api/auth/callback/naver"
link_code="$(printf '%s' "${link_consent_href}" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')"
link_state="$(printf '%s' "${link_consent_href}" | sed -n 's/.*[?&]state=\([^&]*\).*/\1/p')"

# 6) 콜백. 설정 화면으로 돌아가고 매핑과 허가 소비가 함께 커밋된다.
fetch GET "${link_consent_href#"${base_url}"}" \
  -H "Cookie: BOKRILOG_SESSION=${link_session_cookie}"
assert_equals '연결 콜백 302' "${last_status}" '302'
link_callback_location="$(header_value 'Location')"
# 목적지는 서버 상수 하나다. 클라이언트가 보낸 값이 아니다.
#
# 성공도 표지 하나를 단다. 왕복 뒤의 문서는 새로 열리므로 "방금 연결했다"를 아는 값은 주소의
# 표지뿐이고, 그것이 없으면 화면은 조작의 시작만 알리고 결과는 알리지 않는다.
assert_equals '연결이 설정 화면으로 결과 표지와 함께 돌아간다' \
  "$(resolve_location "${link_callback_location}")" "${base_url}/settings?auth=linkSucceeded"
# 무엇을 연결했는지는 말하지 않는다.
assert_value_lacks '연결 성공 표지가 대상 제공자를 말하지 않는다' \
  "${link_callback_location}" 'naver'
assert_value_lacks '연결 목적지에 code가 없다' "${link_callback_location}" 'code='
assert_value_lacks '연결 목적지에 state가 없다' "${link_callback_location}" 'state='
assert_body_lacks '연결 콜백 응답에 계정 식별자가 없다' 'user_id'

link_rotated_cookie="$(session_cookie_value)"
if [ -n "${link_rotated_cookie}" ] && [ "${link_rotated_cookie}" != "${link_session_cookie}" ]; then
  record_pass '연결 성공 시점에 세션 ID가 회전한다'
else
  record_fail '연결 성공 시점에 세션 ID가 회전한다' \
    '콜백이 새 세션 쿠키를 주지 않았다. 허가 소비가 요구하는 재회전이 성립하지 않았다.'
fi

# 매핑이 하나 늘고 그 계정이 두 제공자를 갖는다. 계정은 늘지 않았다.
assert_rows '연결이 매핑을 하나 더한다' 'SELECT count(*) FROM identity_social_link' '4'
assert_rows '연결이 계정을 만들지 않는다' 'SELECT count(*) FROM identity_account_lifecycle' '3'
assert_rows '한 계정이 두 제공자 매핑을 갖는다' \
  'SELECT count(*) FROM (SELECT user_id FROM identity_social_link GROUP BY user_id HAVING count(*) = 2) linked' \
  '1'
assert_rows '그 두 매핑의 제공자가 서로 다르다' \
  'SELECT count(DISTINCT provider) FROM identity_social_link WHERE user_id = (SELECT user_id FROM identity_social_link GROUP BY user_id HAVING count(*) = 2)' \
  '2'
# 허가 소비와 매핑 추가가 한 트랜잭션이다. 반쪽 상태가 남지 않는다.
assert_rows '허가가 정확히 한 번 소비됐다' \
  'SELECT count(*) FROM action_grant WHERE consumed_at IS NOT NULL' '1'
assert_rows '연결이 세션 행을 늘리지 않는다' 'SELECT count(*) FROM spring_session' '3'

# 무장은 일회성이다. 소비 뒤 그 속성이 어느 세션에도 남지 않는다 — 남으면 다음 콜백이 같은
# 무장을 다시 쓴다.
assert_rows '연결 콜백이 무장 속성을 소비한다' \
  "SELECT count(*) FROM spring_session_attributes WHERE attribute_name = 'bokrilog.auth.link'" '0'
# 목적지와 재인증 기록은 회전을 건너 살아남는다. 이 왕복에서 세션 ID가 두 번 바뀌므로(고정 방어
# + 허가 소비의 재회전) 그 둘 중 하나라도 속성을 잃으면 사용자는 다음 연결을 이유 없이 거부당한다.
assert_rows '연결 뒤에도 목적지 속성이 세 세션에 남아 있다' \
  "SELECT count(*) FROM spring_session_attributes WHERE attribute_name = 'bokrilog.auth.destination'" '3'
assert_rows '연결 뒤에도 재인증 기록이 남아 있다' \
  "SELECT count(*) FROM spring_session_attributes WHERE attribute_name = 'bokrilog.auth.reauthenticated-at'" '1'

# 7) 회전 전 쿠키는 죽었고 새 쿠키로는 두 제공자가 보인다.
fetch GET /api/auth/session -H "Cookie: BOKRILOG_SESSION=${link_session_cookie}"
assert_equals '연결 전 쿠키의 세션 확인은 미인증이다' \
  "$(cat "${last_body}")" '{"authenticated":false,"destination":"entry"}'

fetch GET /api/auth/link -H "Cookie: BOKRILOG_SESSION=${link_rotated_cookie}"
assert_equals '연결 뒤 상태 조회 200' "${last_status}" '200'
# 순서가 고정이다. 화면이 아는 고정 순서와 같은 목록이 응답을 만든다.
assert_equals '연결 뒤 상태 조회가 두 제공자를 답한다' \
  "$(cat "${last_body}")" '{"providers":["google","naver"]}'

# 다른 두 사용자는 그대로다.
fetch GET /api/auth/link -H "Cookie: BOKRILOG_SESSION=${naver_session_cookie}"
assert_equals '다른 사용자의 연결 상태 조회 200' "${last_status}" '200'
assert_equals '다른 사용자의 연결 상태는 그대로다' "$(cat "${last_body}")" '{"providers":["naver"]}'
fetch GET /api/auth/link -H "Cookie: BOKRILOG_SESSION=${kakao_session_cookie}"
assert_equals '세 번째 사용자의 연결 상태 조회 200' "${last_status}" '200'
assert_equals '세 번째 사용자의 연결 상태도 그대로다' \
  "$(cat "${last_body}")" '{"providers":["kakao"]}'

# 8) 회전한 쿠키의 새 토큰으로 전역을 갱신한다. 아래 거부 탐침과 로그아웃 절이 이 값을 쓴다.
fetch GET /api/auth/session -H "Cookie: BOKRILOG_SESSION=${link_rotated_cookie}"
google_session_cookie="${link_rotated_cookie}"
google_csrf_token="$(sed -n 's/.*"csrfToken":"\([^"]*\)".*/\1/p' "${last_body}")"
if [ -n "${google_csrf_token}" ]; then
  record_pass '연결 뒤 세션이 새 CSRF 토큰을 준다'
else
  record_fail '연결 뒤 세션이 새 CSRF 토큰을 준다' \
    "본문에 csrfToken이 없다 — $(head -c 200 "${last_body}")"
fi

# 9) 거부 탐침: 연결 무장 도중 본인 확인을 시작하면 그 흐름이 대신 성립한다.
#
# 설정 화면은 연결 버튼 셋과 본인 확인 버튼 셋을 같은 페이지에 그린다. "무장 → 제공자 화면에서
# 이탈 → 본인 확인"은 그래서 평범한 경로이고, 연결 무장이 남아 있으면 그 콜백이 연결 분기에
# 삼켜져 재인증이 성립하지 않는다. 무장 시각에 상대 흐름을 버리는 것이 그 상태를 없앤다.
fetch POST /api/auth/link/kakao \
  -H "Cookie: BOKRILOG_SESSION=${google_session_cookie}" \
  -H "Origin: ${base_url}" \
  -H "X-CSRF-TOKEN: ${google_csrf_token}"
assert_equals '연결 무장(버려질 것) 204' "${last_status}" '204'
assert_rows '그 무장이 허가 하나를 남긴다' \
  'SELECT count(*) FROM action_grant WHERE consumed_at IS NULL' '1'

fetch POST /api/auth/reauthenticate/google \
  -H "Cookie: BOKRILOG_SESSION=${google_session_cookie}" \
  -H "Origin: ${base_url}" \
  -H "X-CSRF-TOKEN: ${google_csrf_token}"
assert_equals '연결 무장 뒤의 재인증 시작 204' "${last_status}" '204'
# 흐름도 허가도 함께 사라진다. 흐름만 버리면 참조할 수 없는 허가가 수명을 산다.
assert_rows '재인증 시작이 연결 무장을 버린다' \
  "SELECT count(*) FROM spring_session_attributes WHERE attribute_name = 'bokrilog.auth.link'" '0'
assert_rows '버려진 무장의 허가도 함께 사라진다' \
  'SELECT count(*) FROM action_grant WHERE consumed_at IS NULL' '0'

fetch GET /api/auth/authorize/google -H "Cookie: BOKRILOG_SESSION=${google_session_cookie}"
assert_equals '갈아탄 흐름의 인가 시작 302' "${last_status}" '302'
switched_authorize_location="$(header_value 'Location')"
fetch GET "${switched_authorize_location#"${base_url}"}&dev_subject=google-${stub_subject_marker}"
switched_consent_href="$(sed -n 's/.*<a href="\([^"]*\)".*/\1/p' "${last_body}" | head -1 | sed 's/&amp;/\&/g')"
fetch GET "${switched_consent_href#"${base_url}"}" \
  -H "Cookie: BOKRILOG_SESSION=${google_session_cookie}"
switched_callback_location="$(header_value 'Location')"
# 재인증이 성립한다 — 붙들어 둔 목적지로 표지 없이 돌아간다. 연결 분기에 삼켜지면 여기가
# `/settings?auth=linkFailed`가 된다.
assert_equals '갈아탄 콜백은 재인증으로 성립한다' \
  "$(resolve_location "${switched_callback_location}")" "${base_url}/onboarding"
assert_rows '갈아탄 콜백이 매핑을 만들지 않는다' 'SELECT count(*) FROM identity_social_link' '4'

# 회전한 쿠키와 새 토큰으로 전역을 갱신한다.
switched_cookie="$(session_cookie_value)"
if [ -n "${switched_cookie}" ]; then
  record_pass '갈아탄 재인증이 세션을 회전시킨다'
  fetch GET /api/auth/session -H "Cookie: BOKRILOG_SESSION=${switched_cookie}"
  google_session_cookie="${switched_cookie}"
  google_csrf_token="$(sed -n 's/.*"csrfToken":"\([^"]*\)".*/\1/p' "${last_body}")"
else
  record_fail '갈아탄 재인증이 세션을 회전시킨다' '콜백이 새 세션 쿠키를 주지 않았다.'
fi

# 10) 거부 탐침: 다른 계정에 연결된 식별자.
#
# 카카오 왕복이 만든 식별자를 이 계정에 붙이려 한다. 스키마의 `(issuer, subject)` 전역 유일이
# 최종 방어이고, 여기서 보는 것은 그 거부가 HTTP 층에서 **표지 하나**로 수렴하며 양쪽 계정의
# 매핑이 모두 그대로라는 사실이다. 상대 계정의 존재도 소유자도 드러나지 않는다.
fetch POST /api/auth/link/kakao \
  -H "Cookie: BOKRILOG_SESSION=${google_session_cookie}" \
  -H "Origin: ${base_url}" \
  -H "X-CSRF-TOKEN: ${google_csrf_token}"
assert_equals '두 번째 연결 시작 204' "${last_status}" '204'

fetch GET /api/auth/authorize/kakao -H "Cookie: BOKRILOG_SESSION=${google_session_cookie}"
assert_equals '두 번째 연결 인가 시작 302' "${last_status}" '302'
taken_authorize_location="$(header_value 'Location')"

fetch GET "${taken_authorize_location#"${base_url}"}&dev_subject=kakao-${stub_subject_marker}"
assert_equals '두 번째 연결 동의 화면 200' "${last_status}" '200'
taken_consent_href="$(sed -n 's/.*<a href="\([^"]*\)".*/\1/p' "${last_body}" | head -1 | sed 's/&amp;/\&/g')"

fetch GET "${taken_consent_href#"${base_url}"}" \
  -H "Cookie: BOKRILOG_SESSION=${google_session_cookie}"
assert_equals '남의 식별자를 쓴 연결 콜백 302' "${last_status}" '302'
taken_callback_location="$(header_value 'Location')"
assert_equals '남의 식별자를 쓴 연결은 표지 하나로 수렴한다' \
  "$(resolve_location "${taken_callback_location}")" "${base_url}/settings?auth=linkFailed"
assert_value_lacks '거부 표지가 사유를 말하지 않는다' "${taken_callback_location}" 'taken'
# 양쪽 다 무변경이다. 허가도 소비되지 않았다 — 반쪽 상태가 남지 않는다.
assert_rows '거부된 연결이 매핑을 만들지 않는다' 'SELECT count(*) FROM identity_social_link' '4'
assert_rows '거부된 연결이 허가를 소비하지 않는다' \
  'SELECT count(*) FROM action_grant WHERE consumed_at IS NOT NULL' '1'
assert_rows '거부된 연결이 계정을 만들지 않는다' \
  'SELECT count(*) FROM identity_account_lifecycle' '3'

# 검증을 통과한 뒤 거절됐으므로 세션 ID는 이미 회전한 상태다(재인증 실패와 같은 근거).
taken_rotated_cookie="$(session_cookie_value)"
if [ -n "${taken_rotated_cookie}" ]; then
  record_pass '거부된 연결도 세션을 잃지 않는다'
  fetch GET /api/auth/session -H "Cookie: BOKRILOG_SESSION=${taken_rotated_cookie}"
  assert_body_contains '거부된 연결 뒤에도 인증 상태다' '"authenticated":true'
  google_session_cookie="${taken_rotated_cookie}"
  google_csrf_token="$(sed -n 's/.*"csrfToken":"\([^"]*\)".*/\1/p' "${last_body}")"
else
  record_fail '거부된 연결도 세션을 잃지 않는다' '콜백이 새 세션 쿠키를 주지 않았다.'
fi


printf '\n로그아웃 왕복\n'

# 위 왕복들이 만든 세션을 실제로 끝낸다. 여기까지가 Story 1.5의 성공 경로 전부다.
#
# 사용자 셋은 위 절이 이미 만들었다. 왕복이 제공자마다 도므로 별도의 두 번째 왕복이 필요하지
# 않고, 그래서 Google 세션의 로그아웃이 **다른 제공자로 로그인한 다른 사용자들**의 세션을
# 건드리지 않는지를 이 계층에서 확인할 수 있다.
#
# 아래 이름은 그 왕복의 결과다. 첫 로그아웃 대상이 Google 세션이고 보존 대상이 네이버·카카오
# 세션이다. 셋 다 자기 세션을 끝낼 수 있어야 하므로 마지막에 나머지 둘도 로그아웃한다.
assert_rows '이제 계정이 셋이다' 'SELECT count(*) FROM identity_account_lifecycle' '3'
assert_rows '이제 세션이 셋이다' 'SELECT count(*) FROM spring_session' '3'

session_cookie="${google_session_cookie}"
csrf_token="${google_csrf_token}"
second_session_cookie="${naver_session_cookie}"
second_csrf_token="${naver_csrf_token}"
third_session_cookie="${kakao_session_cookie}"
third_csrf_token="${kakao_csrf_token}"

if [ -n "${session_cookie}" ] && [ -n "${csrf_token}" ] \
  && [ -n "${second_session_cookie}" ] && [ -n "${second_csrf_token}" ] \
  && [ -n "${third_session_cookie}" ] && [ -n "${third_csrf_token}" ]; then
  record_pass '세 왕복의 쿠키와 토큰을 모두 얻었다'
else
  record_fail '세 왕복의 쿠키와 토큰을 모두 얻었다' \
    '세 사용자의 쿠키나 토큰을 얻지 못했다. 아래 보존 단정이 아무것도 보지 않는다.'
fi

# 1) 정상 로그아웃. 204이고 본문이 없다.
fetch POST /api/auth/logout \
  -H "Cookie: BOKRILOG_SESSION=${session_cookie}" \
  -H "Origin: ${base_url}" \
  -H "X-CSRF-TOKEN: ${csrf_token}"
assert_equals '로그아웃 상태 204' "${last_status}" '204'
if [ ! -s "${last_body}" ]; then
  record_pass '로그아웃 응답에 본문이 없다'
else
  record_fail '로그아웃 응답에 본문이 없다' "$(head -c 200 "${last_body}")"
fi

# 쿠키를 지우는 그 하나의 응답이다. 보안 헤더 다섯 개와 캐시 지시를 여기서도 확인한다 —
# `Set-Cookie`를 실어 나르는 응답이 헤더 미검증으로 남으면 안 된다. 204에도 `always`가 붙은
# add_header 블록이 그대로 걸려야 한다.
assert_security_headers '로그아웃 204'

# 쿠키 만료 지시. 값이 비고 즉시 만료가 실린다. 회전이 아니라 폐기다.
logout_set_cookie="$(header_value 'Set-Cookie')"
assert_value_contains '로그아웃이 세션 쿠키를 빈 값으로 만든다' \
  "${logout_set_cookie}" 'BOKRILOG_SESSION=;'
assert_value_contains '로그아웃이 쿠키 즉시 만료를 지시한다' "${logout_set_cookie}" 'Max-Age=0'
assert_value_contains '만료 지시도 Path를 유지한다' "${logout_set_cookie}" 'Path=/'
# 만료 쿠키도 속성 세 개를 그대로 들고 있어야 한다. 브라우저는 속성이 어긋난 `Set-Cookie`를
# 원래 쿠키와 다른 것으로 보아 삭제에 실패할 수 있고, 그러면 쿠키가 살아남는다.
assert_value_contains '만료 지시도 HttpOnly를 유지한다' "${logout_set_cookie}" 'HttpOnly'
assert_value_contains '만료 지시도 Secure를 유지한다' "${logout_set_cookie}" 'Secure'
assert_value_contains '만료 지시도 SameSite를 유지한다' "${logout_set_cookie}" 'SameSite=Lax'
if [ -z "$(session_cookie_value)" ]; then
  record_pass '로그아웃이 새 세션 값을 주지 않는다'
else
  record_fail '로그아웃이 새 세션 값을 주지 않는다' "새 쿠키 값이 실렸다 — $(session_cookie_value)"
fi

# 세션 행이 하나만 사라졌다. 계정과 매핑은 그대로다 — 로그아웃은 계정 상태를 바꾸지 않는다.
assert_rows '로그아웃이 그 세션 행만 지운다' 'SELECT count(*) FROM spring_session' '2'
assert_rows '로그아웃이 계정을 지우지 않는다' 'SELECT count(*) FROM identity_account_lifecycle' '3'
# 넷이다. 위 연결 왕복이 Google 계정에 네이버 매핑 하나를 더했다.
assert_rows '로그아웃이 인증 매핑을 지우지 않는다' 'SELECT count(*) FROM identity_social_link' '4'

# 그 쿠키로 이어지는 세션 확인이 더 이상 인증 상태가 아니다.
fetch GET /api/auth/session -H "Cookie: BOKRILOG_SESSION=${session_cookie}"
assert_equals '로그아웃한 쿠키의 세션 확인은 미인증이다' \
  "$(cat "${last_body}")" '{"authenticated":false,"destination":"entry"}'

# 2) 다른 사용자 보존. 남은 두 세션의 인증 상태와 목적지가 그대로다.
fetch GET /api/auth/session -H "Cookie: BOKRILOG_SESSION=${second_session_cookie}"
assert_equals '다른 사용자의 세션 확인 상태 200' "${last_status}" '200'
assert_body_contains '다른 사용자는 여전히 인증 상태다' '"authenticated":true'
assert_body_contains '다른 사용자의 목적지도 그대로다' '"destination":"onboarding"'

fetch GET /api/auth/session -H "Cookie: BOKRILOG_SESSION=${third_session_cookie}"
assert_equals '세 번째 사용자의 세션 확인 상태 200' "${last_status}" '200'
assert_body_contains '세 번째 사용자도 여전히 인증 상태다' '"authenticated":true'
# 목적지도 함께 본다. 인증 여부만 보면 이 세션의 목적지만 바뀌는 회귀가 통과한다 —
# 보존은 "인증이 살아 있다"가 아니라 "이 세션이 그대로다"이고, 위 두 번째 세션이 이미 그 형태다.
assert_body_contains '세 번째 사용자의 목적지도 그대로다' '"destination":"onboarding"'

# 3) 반복 로그아웃. 토큰이 세션과 함께 사라졌으므로 CSRF 단계에서 403으로 수렴한다.
#    "안전하게 처리"는 멱등한 상태 코드가 아니라 무변경이며 403이 그것을 정확히 만족한다.
fetch POST /api/auth/logout \
  -H "Cookie: BOKRILOG_SESSION=${session_cookie}" \
  -H "Origin: ${base_url}" \
  -H "X-CSRF-TOKEN: ${csrf_token}"
assert_equals '반복 로그아웃 상태 403' "${last_status}" '403'
assert_body_contains '반복 로그아웃 안정 code' '"code":"FORBIDDEN"'
assert_correlation_id '반복 로그아웃 correlation ID'
assert_body_lacks '반복 로그아웃이 토큰을 되비추지 않는다' "${csrf_token}"
# 새 세션 행이 생기지 않는다. 기본 CSRF 저장소는 토큰을 저장하려고 세션을 만든다.
assert_rows '반복 로그아웃이 새 세션 행을 만들지 않는다' 'SELECT count(*) FROM spring_session' '2'
assert_security_headers '반복 로그아웃'

# 4) 교차 출처 로그아웃. 토큰이 맞아도 `SameOriginFilter`가 먼저 막는다.
fetch POST /api/auth/logout \
  -H "Cookie: BOKRILOG_SESSION=${second_session_cookie}" \
  -H 'Origin: https://evil.example' \
  -H "X-CSRF-TOKEN: ${second_csrf_token}"
assert_equals '교차 출처 로그아웃 상태 403' "${last_status}" '403'
assert_body_contains '교차 출처 로그아웃 안정 code' '"code":"FORBIDDEN"'
assert_rows '교차 출처 로그아웃은 무변경이다' 'SELECT count(*) FROM spring_session' '2'

# 5) CSRF 토큰 없는 로그아웃. 세션이 살아 있어야 한다.
fetch POST /api/auth/logout \
  -H "Cookie: BOKRILOG_SESSION=${second_session_cookie}" \
  -H "Origin: ${base_url}"
assert_equals '토큰 없는 로그아웃 상태 403' "${last_status}" '403'
assert_body_contains '토큰 없는 로그아웃 안정 code' '"code":"FORBIDDEN"'
fetch GET /api/auth/session -H "Cookie: BOKRILOG_SESSION=${second_session_cookie}"
assert_body_contains '거절된 로그아웃 뒤에도 세션이 살아 있다' '"authenticated":true'

# 6) 미인증 로그아웃. 인증 여부를 응답으로 구분하지 않는다.
fetch POST /api/auth/logout -H "Origin: ${base_url}" -H 'X-CSRF-TOKEN: verifyanonymousmarker'
assert_equals '미인증 로그아웃 상태 403' "${last_status}" '403'
assert_body_contains '미인증 로그아웃 안정 code' '"code":"FORBIDDEN"'
assert_rows '미인증 로그아웃이 세션을 만들지 않는다' 'SELECT count(*) FROM spring_session' '2'

# 7) 로그아웃 표면은 `/api/auth/logout` 하나다. `logout()`을 매처 없이 켜면 `/logout`이 생긴다.
fetch POST /logout -H "Origin: ${base_url}"
assert_equals '`POST /logout` 상태 405(진입점의 정적 location이 받는다)' "${last_status}" '405'
assert_body_contains '`POST /logout` 안정 code' '"code":"METHOD_NOT_ALLOWED"'
fetch GET /api/auth/logout
assert_equals '`GET /api/auth/logout` 상태 404(안전한 메서드로는 로그아웃이 없다)' \
  "${last_status}" '404'
assert_rows '로그아웃 표면 탐침이 세션을 지우지 않는다' 'SELECT count(*) FROM spring_session' '2'

# 8) 남은 두 세션도 정상 로그아웃으로 끝낸다. 세 사용자 모두 나갈 수 있어야 한다.
fetch POST /api/auth/logout \
  -H "Cookie: BOKRILOG_SESSION=${second_session_cookie}" \
  -H "Origin: ${base_url}" \
  -H "X-CSRF-TOKEN: ${second_csrf_token}"
assert_equals '두 번째 로그아웃 상태 204' "${last_status}" '204'
assert_rows '두 로그아웃 뒤 세션 행이 하나 남는다' 'SELECT count(*) FROM spring_session' '1'

fetch POST /api/auth/logout \
  -H "Cookie: BOKRILOG_SESSION=${third_session_cookie}" \
  -H "Origin: ${base_url}" \
  -H "X-CSRF-TOKEN: ${third_csrf_token}"
assert_equals '세 번째 로그아웃 상태 204' "${last_status}" '204'
assert_rows '세 로그아웃 뒤 세션 행이 0개다' 'SELECT count(*) FROM spring_session' '0'
# 속성 행도 함께 사라진다(`ON DELETE CASCADE`). 세션 비밀이 남는 경로가 없다.
assert_rows '세 로그아웃 뒤 세션 속성 행도 0개다' \
  'SELECT count(*) FROM spring_session_attributes' '0'

printf '\n스텁 제공자 성공 왕복 정리\n'

# 스택을 원래 상태로 되돌린다. 뒤 단계와 Playwright 회귀가 빈 저장소를 전제한다.
psql_exec '왕복이 만든 세션을 지운다' 'TRUNCATE TABLE spring_session, spring_session_attributes'
# `action_grant`를 이름으로 적는다. `identity_account`를 참조하므로 CASCADE가 어차피 비우지만,
# 이름이 없으면 그 정리가 외래키 설정에 딸린 부수 효과로만 성립한다(`AuthFlowTestBase`와 같은 규율).
psql_exec '왕복이 만든 계정과 매핑을 지운다' \
  'TRUNCATE TABLE action_grant, identity_social_link, identity_account, identity_account_lifecycle CASCADE'
assert_rows '되돌린 뒤 계정 행이 0개다' 'SELECT count(*) FROM identity_account_lifecycle' '0'
assert_rows '되돌린 뒤 세션 행이 0개다' 'SELECT count(*) FROM spring_session' '0'
assert_rows '되돌린 뒤 허가 행이 0개다' 'SELECT count(*) FROM action_grant' '0'

printf '\n알려진 내부 경로 허용 목록\n'

# 폴백이 아니라 허용 목록이다. 이름을 적은 세 경로만 진입 문서를 받는다.
while read -r allowed_path; do
  [ -n "${allowed_path}" ] || continue
  fetch GET "${allowed_path}"
  assert_equals "\`GET ${allowed_path}\` 상태 200" "${last_status}" '200'
  assert_equals "\`GET ${allowed_path}\` Content-Type" "$(header_value 'Content-Type')" 'text/html'
  assert_body_contains "\`GET ${allowed_path}\`가 진입 문서다" '<div id="root">'
  assert_security_headers "\`GET ${allowed_path}\`"
  # 문서는 200이지만 그 안에 사용자 데이터가 없다. 로그인 상태는 화면이 세션을 확인한 뒤에 정한다.
  assert_body_lacks "\`GET ${allowed_path}\` 문서에 계정 식별자가 없다" 'user_id'
done <<'ALLOWED_PATHS'
/onboarding
/dashboard
/settings
ALLOWED_PATHS

# 정적 경로의 잘못된 메서드. 허용 목록 location도 파일을 찾기 전에 되돌린다.
fetch POST /onboarding
assert_equals '`POST /onboarding` 상태 405' "${last_status}" '405'
assert_equals '`POST /onboarding` Content-Type' "$(header_value 'Content-Type')" 'application/json'
assert_body_lacks '`POST /onboarding`가 index.html을 돌려주지 않는다' '<div id="root">'

# 목록 밖의 경로는 계속 404다. 접두사가 겹치는 값도 받지 않는다.
while read -r rejected_path; do
  [ -n "${rejected_path}" ] || continue
  fetch GET "${rejected_path}"
  assert_equals "\`GET ${rejected_path}\` 상태 404" "${last_status}" '404'
  assert_body_contains "\`GET ${rejected_path}\` 안정 code" '"code":"NOT_FOUND"'
  assert_body_lacks "\`GET ${rejected_path}\`가 index.html로 대체되지 않는다" '<div id="root">'
done <<'REJECTED_PATHS'
/onboarding/x
/onboarding-x
/dashboards
/dashboard/
/settings/x
/settings-x
REJECTED_PATHS

printf '\n한도를 넘는 요청\n'

# `client_max_body_size 64k`로 바로 도달하는 413이다. nginx에 이 상태의 `error_page`가 없으면
# 기본 HTML 오류 화면이 나가 오류 계약이 두 벌이 된다.
# 상태 코드는 413 그대로여야 한다. `error_page`를 `= @이름`으로 쓰면 named location의 `return`
# 코드(400)로 뭉개진다. 상태가 남고 본문만 공통 계약인 것이 이 단정이 고정하는 모양이다.
oversized_body="${work_dir}/oversized.bin"
head -c 70000 /dev/zero | tr '\0' 'x' >"${oversized_body}"

fetch POST /api/ --data-binary "@${oversized_body}"
assert_equals '64k를 넘는 본문은 413' "${last_status}" '413'
assert_equals '413 Content-Type' "$(header_value 'Content-Type')" 'application/json'
assert_body_contains '413 안정 code' '"code":"BAD_REQUEST"'
assert_body_contains '413 사용자 메시지' '"message":"요청이 올바르지 않습니다. 입력을 확인해 주세요."'
assert_correlation_id '413 correlation ID'
assert_body_lacks '413에 nginx 기본 HTML이 없다' '<html>'
assert_security_headers '413'

printf '\n알 수 없는 정적 경로\n'

fetch GET /nope
assert_equals '`GET /nope` 상태 404' "${last_status}" '404'
assert_body_contains '`GET /nope` 안정 code' '"code":"NOT_FOUND"'
assert_correlation_id '`GET /nope` correlation ID'
# 라우터가 없다. index.html로 되돌리면 존재하지 않는 경로가 200 + 진입 화면이 된다.
assert_body_lacks '`GET /nope`가 index.html로 대체되지 않는다' '<div id="root">'
assert_security_headers '`GET /nope`'

# `/api`만 프록시한다. 그 밖의 경로는 정적이거나 404이며 어디로도 프록시하지 않는다.
fetch GET /actuator/health
assert_equals '`GET /actuator/health` 상태 404(프록시 대상이 아니다)' "${last_status}" '404'
assert_body_lacks '`GET /actuator/health`가 백엔드에 닿지 않는다' '"status":"UP"'

printf '\n백엔드 응답 지연\n'

# `pause`는 컨테이너의 모든 프로세스를 멈춘다. 커널이 TCP 연결은 그대로 받아 주므로 nginx는
# 연결에 성공한 뒤 응답을 기다리다 `proxy_read_timeout`에 걸린다. 그것이 504이며 502(연결 거부)와
# 다른 상태다. 오류는 실제 상태를 그대로 반영해야 하므로 502로 뭉개면 안 된다.
# `error_page 502 503 504 = @upstream_unavailable`처럼 `=`를 붙이면 정확히 그 뭉갬이 일어난다.
#
# `proxy_read_timeout`이 30초라 이 한 단정에 그만큼 걸린다. 상태 코드가 실제 상태를 반영하는지는
# 이 경로 말고는 확인할 방법이 없어 그 시간을 쓴다.
if ! compose pause backend >"${work_dir}/pause.log" 2>&1; then
  fail_setup '백엔드를 일시 정지하지 못했다.' "$(tail -20 "${work_dir}/pause.log" 2>/dev/null)"
fi

printf '  읽기 timeout(30초)을 기다린다\n'
fetch GET /api/status --max-time 60
assert_equals '응답이 없는 백엔드는 504(502로 뭉개지 않는다)' "${last_status}" '504'
assert_equals '504 Content-Type' "$(header_value 'Content-Type')" 'application/json'
assert_body_contains '504 안정 code' '"code":"UPSTREAM_UNAVAILABLE"'
assert_body_contains '504 사용자 메시지' \
  '"message":"서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."'
assert_correlation_id '504 correlation ID'
assert_body_lacks '504에 nginx 기본 HTML이 없다' '<html>'
assert_security_headers '504'

if ! compose unpause backend >"${work_dir}/unpause.log" 2>&1; then
  fail_setup '백엔드를 다시 시작하지 못했다.' "$(tail -20 "${work_dir}/unpause.log" 2>/dev/null)"
fi

printf '\n백엔드 비가용\n'

if ! compose stop backend >"${work_dir}/stop.log" 2>&1; then
  fail_setup '백엔드를 정지하지 못했다.' "$(tail -20 "${work_dir}/stop.log" 2>/dev/null)"
fi

fetch GET /api/status --max-time 30
# 502(연결 거부)와 504(연결 timeout) 중 어느 쪽이 나오는지는 우리 설정이 아니라 컨테이너를 멈춘
# 직후의 커널 상태가 정한다. 컨테이너가 사라지면 nginx 쪽 ARP 항목이 잠시 REACHABLE로 남아
# 패킷이 응답 없는 MAC으로 나가고, 그 사이에는 연결이 거부되는 대신 `proxy_connect_timeout`에
# 걸린다(504). 항목이 만료된 뒤에는 곧바로 EHOSTUNREACH가 되어 502다.
#
# 둘 다 "업스트림에 닿을 수 없다"는 같은 사실이고 본문은 아래에서 글자까지 고정한다. 여기서 한
# 값으로 좁히면 그 순간의 커널 캐시 상태에 따라 깜빡이는 단정이 된다. 상태 코드가 실제 상태를
# 반영하는지는 위 `백엔드 응답 지연`(504)이 결정적으로 확인한다.
assert_status_in '백엔드 정지 후 `GET /api/status` 상태' "${last_status}" '502' '504'
assert_equals '백엔드 정지 후 Content-Type' "$(header_value 'Content-Type')" 'application/json'
assert_body_contains '백엔드 정지 후 안정 code' '"code":"UPSTREAM_UNAVAILABLE"'
assert_body_contains '백엔드 정지 후 사용자 메시지' \
  '"message":"서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."'
assert_correlation_id '백엔드 정지 후 correlation ID'
# nginx 기본 502 화면은 HTML이고 `<center>nginx</center>` 줄을 담는다.
assert_body_lacks '백엔드 정지 후 nginx 기본 HTML이 없다' '<html>'
assert_body_lacks '백엔드 정지 후 upstream 주소가 없다' 'backend:8080'
assert_body_lacks '백엔드 정지 후 nginx 버전이 없다' 'nginx/'
assert_security_headers '백엔드 정지 후 `GET /api/status`'

# 업스트림 오류가 콜백의 쿼리 문자열을 로그에 남기지 않는지 확인할 재료를 만든다.
#
# `error_log`는 `access_log`와 달리 형식을 바꿀 수 없고 항목마다 원본 요청 줄을 그대로 적는다.
# 그래서 위쪽 `map`이 잘라낸 쿼리가 오류 항목에는 그대로 남는다. 막는 것은 `bokrilog.conf`의
# `^~ /api/auth/callback/` 블록이며 그 경로의 오류 수준만 올린다. 블록이 사라지면 아래 로그
# 검사에서 걸린다.
#
# 백엔드가 멈춘 지금이 유일한 기회다. 살아 있으면 콜백은 302로 끝나 업스트림 오류가 생기지 않는다.
# 표지는 우리가 고른 고정 문자열이라 왕복 값과 달리 "검사가 아무것도 보지 않는" 상태가 없다.
#
# 모든 제공자를 부른다. 그 블록은 `^~` 접두사 매칭이라 제공자 경로 전부를 받지만, 한 경로만
# 탐침하면 접두사가 `/api/auth/callback/google`로 좁아지는 변경이 통과한다.
upstream_error_code='verifyupstreamerrorcodemarker'
upstream_error_state='verifyupstreamerrorstatemarker'
upstream_probe_count=0
while read -r upstream_provider; do
  [ -n "${upstream_provider}" ] || continue
  upstream_probe_count=$((upstream_probe_count + 1))
  fetch GET \
    "/api/auth/callback/${upstream_provider}?code=${upstream_error_code}-${upstream_provider}&state=${upstream_error_state}-${upstream_provider}" \
    --max-time 30
  assert_status_in "백엔드 정지 후 ${upstream_provider} 콜백 상태" "${last_status}" '502' '504'
done <<UPSTREAM_ERROR_PROVIDERS
${providers}
UPSTREAM_ERROR_PROVIDERS

# 이 루프도 제공자 수만큼 돌았다. 근거는 위 두 루프와 같다 — 없는 단정은 붉은 단정이 아니다.
assert_equals '업스트림 오류 탐침이 제공자 수만큼 돌았다' "${upstream_probe_count}" "${provider_count}"

printf '\n로그 누출 검사\n'

raw_log_file="${work_dir}/compose-raw.log"
log_file="${work_dir}/compose.log"

# 검사 대상은 스택의 모든 서비스다. 서비스를 골라 좁히면 고르지 않은 서비스가 무엇을 찍는지
# 아무도 보지 않게 되고, 그 사각지대는 서비스가 늘어날 때마다 커진다.
#
# 예외는 한 줄뿐이다. postgres 공식 이미지의 entrypoint가 초기화 때
# `/usr/local/bin/docker-entrypoint.sh: ignoring /docker-entrypoint-initdb.d/*`를 찍고 그 줄이
# 조각 목록의 `/docker-entrypoint`에 걸린다. BokriLog가 만든 출력이 아니고 nginx의
# `NGINX_ENTRYPOINT_QUIET_LOGS` 같은 스위치도 없다. 그렇다고 조각을 뺄 수는 없다. 그 조각은
# nginx가 컨테이너 내부 경로를 다시 찍기 시작하면 걸리는 유일한 단정이다.
#
# 그래서 조각이 아니라 그 한 줄만 좁은 패턴으로 걸러 낸다. 잃는 것은 postgres entrypoint의
# 그 한 줄뿐이고, 나머지 모든 서비스의 모든 줄이 검사 대상으로 남는다.
log_ready=false
if ! compose logs --no-color --no-log-prefix >"${raw_log_file}" 2>&1; then
  record_fail 'compose 로그를 읽는다' "$(tail -20 "${raw_log_file}" 2>/dev/null)"
elif [ ! -s "${raw_log_file}" ]; then
  # 빈 로그에서는 어떤 grep도 0건이다. "검사했다"와 "볼 것이 없었다"를 구분한다.
  record_fail 'compose 로그를 읽는다' '로그가 비었다. 검사가 아무것도 보지 않았다.'
else
  # 걸러 낸 뒤 남는 줄이 없으면 grep의 종료 코드가 1이다. 그 자체는 실패가 아니므로 삼키고,
  # "볼 것이 없었다"는 바로 아래에서 파일 크기로 판정한다.
  grep -vF -- 'ignoring /docker-entrypoint-initdb.d/' "${raw_log_file}" >"${log_file}" || true
  if [ ! -s "${log_file}" ]; then
    record_fail 'compose 로그를 읽는다' '걸러 내고 나니 검사할 줄이 남지 않았다.'
  else
    record_pass 'compose 로그를 읽는다'
    log_ready=true
  fi
fi

if [ "${log_ready}" = true ]; then
  # 두 계층의 로그를 한 ID로 잇는다는 주장을 실제로 확인한다. 클라이언트가 헤더로 받은 그 값이
  # 진입점 로그에도 남아 있어야 사용자가 신고한 ID로 요청을 찾을 수 있다.
  # `log_format`에서 `$request_id`를 빼면 여기서 걸린다.
  if [ -z "${tracked_request_id}" ]; then
    record_fail '진입점 로그에 요청 식별자가 남는다' '앞 단계에서 X-Request-Id를 받지 못했다.'
  elif grep -qF -- "${tracked_request_id}" "${log_file}"; then
    record_pass '진입점 로그에 요청 식별자가 남는다'
  else
    record_fail '진입점 로그에 요청 식별자가 남는다' \
      "access log에서 '${tracked_request_id}'를 찾지 못했다."
  fi

  assert_no_forbidden_fragments '스택 로그' "${log_file}"

  # access log이 요청된 경로를 계속 남긴다.
  #
  # `log_format`의 `$request_path`를 `$uri`로 바꾸면 허용 목록 location의 내부 redirect 때문에
  # `/onboarding` 요청이 `/index.html`로 기록된다. 쿼리 문자열을 지우는 것과 어느 경로가
  # 요청됐는지를 잃는 것은 다른 일이고, 뒤의 것은 access log의 이유를 없앤다. 위 `알려진 내부
  # 경로 허용 목록` 절이 그 경로를 실제로 요청했으므로 여기서 확인할 수 있다.
  #
  # `grep`의 출력을 버리되 입력은 끝까지 읽는다. 조건에 `| grep -q`를 쓰면 첫 매치에서 끝나
  # 생산자가 SIGPIPE를 받는다(PR #9).
  if grep -F -- '"GET /onboarding HTTP' "${log_file}" >/dev/null; then
    record_pass 'access log이 허용 목록 경로를 요청된 이름으로 남긴다'
  else
    record_fail 'access log이 허용 목록 경로를 요청된 이름으로 남긴다' \
      "access log에서 '\"GET /onboarding HTTP'를 찾지 못했다. 경로가 내부 redirect 뒤의 값으로 기록됐을 수 있다."
  fi

  if grep -F -- '"GET /index.html HTTP' "${log_file}" >/dev/null; then
    record_fail 'access log이 내부 redirect 뒤의 경로로 바꿔 적지 않는다' \
      "$(grep -nF -- '"GET /index.html HTTP' "${log_file}" | head -2)"
  else
    record_pass 'access log이 내부 redirect 뒤의 경로로 바꿔 적지 않는다'
  fi

  # 성공 왕복들이 흘린 값을 찾는다.
  #
  # 조각 목록으로는 잡을 수 없다. authorization code와 `state`는 실행마다 달라지고, 그것이 바로
  # 이 값들의 성질이다. 위 왕복이 실제로 쓴 값을 그대로 찾는 것만이 "로그에 남지 않는다"를
  # 확인하는 방법이다. 진입점의 `log_format`이 쿼리 문자열을 남기기 시작하면 여기서 걸린다.
  #
  # 세 왕복의 값을 모두 본다. 제공자가 늘어도 access log의 `map`은 하나지만, 한 왕복의 값만
  # 검사하면 다른 제공자의 콜백이 다른 location으로 흘러 쿼리를 남기기 시작해도 통과한다.
  for leaked_label in 'google code' 'google state' 'naver code' 'naver state' \
    'kakao code' 'kakao state' 'reauth code' 'reauth state' 'link code' 'link state' \
    'link 허가 참조'; do
    case "${leaked_label}" in
      'google code') leaked_value="${google_round_trip_code}" ;;
      'google state') leaked_value="${google_round_trip_state}" ;;
      'reauth code') leaked_value="${reauth_code}" ;;
      'reauth state') leaked_value="${reauth_state}" ;;
      'link code') leaked_value="${link_code}" ;;
      'link state') leaked_value="${link_state}" ;;
      # 허가 참조는 브라우저에도 로그에도 나가지 않는다. 서버가 세션에 들고 있다가 소비하는
      # 값이므로 응답에 실릴 자리가 없고, 그 사실을 실제 값으로 확인한다.
      'link 허가 참조') leaked_value="${link_grant_reference}" ;;
      'naver code') leaked_value="${naver_round_trip_code}" ;;
      'naver state') leaked_value="${naver_round_trip_state}" ;;
      'kakao code') leaked_value="${kakao_round_trip_code}" ;;
      'kakao state') leaked_value="${kakao_round_trip_state}" ;;
      # 마지막 이름도 자기 분기를 갖는다. `*)`가 그것을 겸하면 오타 하나나 새 이름 하나가
      # 마지막 값을 다른 이름으로 다시 검사하며 통과한다 — 위 왕복 `case`가 이미 쓰는 형태다.
      *)
        record_fail '왕복 누출 검사 대상을 아는 이름으로 옮긴다' "모르는 이름이다 — ${leaked_label}"
        continue
        ;;
    esac
    if [ -z "${leaked_value}" ]; then
      record_fail "스택 로그에 왕복의 ${leaked_label}가 없다" \
        "왕복에서 ${leaked_label} 값을 읽지 못해 검사가 아무것도 보지 않았다."
    elif grep -qF -- "${leaked_value}" "${log_file}"; then
      record_fail "스택 로그에 왕복의 ${leaked_label}가 없다" \
        "$(grep -nF -- "${leaked_value}" "${log_file}" | head -2)"
    else
      record_pass "스택 로그에 왕복의 ${leaked_label}가 없다"
    fi
  done

  # 업스트림 오류 경로가 남긴 것도 같이 본다. access log는 위 `map`이 막지만 error_log는 그
  # 수단이 없어 별도 location이 막는다. 두 방어가 각각 자기 단정을 갖는다.
  for leaked_label in code state; do
    case "${leaked_label}" in
      code) leaked_value="${upstream_error_code}" ;;
      state) leaked_value="${upstream_error_state}" ;;
      *)
        record_fail '업스트림 오류 누출 검사 대상을 아는 이름으로 옮긴다' \
          "모르는 이름이다 — ${leaked_label}"
        continue
        ;;
    esac
    if grep -qF -- "${leaked_value}" "${log_file}"; then
      record_fail "스택 로그에 업스트림 오류 콜백의 ${leaked_label}가 없다" \
        "$(grep -nF -- "${leaked_value}" "${log_file}" | head -2)"
    else
      record_pass "스택 로그에 업스트림 오류 콜백의 ${leaked_label}가 없다"
    fi
  done

  # 로그아웃 왕복이 흘린 값을 찾는다.
  #
  # 조각 목록으로는 잡을 수 없다. 세션 쿠키 값과 CSRF 토큰은 실행마다 달라지고, 그것이 이 값들의
  # 성질이다. 위 왕복이 실제로 쓴 값을 그대로 찾는 것만이 "세션 ID·CSRF 토큰이 로그에 남지
  # 않는다"를 확인하는 방법이다. 로그아웃은 쿼리 문자열이 없으므로 access log에는 경로만 남고,
  # 이 검사는 헤더가 어딘가에 찍히기 시작하는 회귀를 잡는다.
  for leaked_label in 'google 세션 쿠키' 'google CSRF 토큰' 'naver 세션 쿠키' 'naver CSRF 토큰' \
    'kakao 세션 쿠키' 'kakao CSRF 토큰'; do
    case "${leaked_label}" in
      'google 세션 쿠키') leaked_value="${google_session_cookie}" ;;
      'google CSRF 토큰') leaked_value="${google_csrf_token}" ;;
      'naver 세션 쿠키') leaked_value="${naver_session_cookie}" ;;
      'naver CSRF 토큰') leaked_value="${naver_csrf_token}" ;;
      'kakao 세션 쿠키') leaked_value="${kakao_session_cookie}" ;;
      'kakao CSRF 토큰') leaked_value="${kakao_csrf_token}" ;;
      *)
        record_fail '로그아웃 누출 검사 대상을 아는 이름으로 옮긴다' "모르는 이름이다 — ${leaked_label}"
        continue
        ;;
    esac
    if [ -z "${leaked_value}" ]; then
      record_fail "스택 로그에 ${leaked_label} 값이 없다" \
        "왕복에서 그 값을 읽지 못해 검사가 아무것도 보지 않았다."
    elif grep -qF -- "${leaked_value}" "${log_file}"; then
      record_fail "스택 로그에 ${leaked_label} 값이 없다" \
        "$(grep -nF -- "${leaked_value}" "${log_file}" | head -2)"
    else
      record_pass "스택 로그에 ${leaked_label} 값이 없다"
    fi
  done

  # 로그아웃 경로 자체는 access log에 남아야 한다. 상태 변경 요청의 존재를 감추지 않는다.
  if grep -F -- '"POST /api/auth/logout HTTP' "${log_file}" >/dev/null; then
    record_pass 'access log이 로그아웃 요청을 경로로 남긴다'
  else
    record_fail 'access log이 로그아웃 요청을 경로로 남긴다' \
      "access log에서 '\"POST /api/auth/logout HTTP'를 찾지 못했다."
  fi
fi

# --------------------------------------------------------------------------
printf '\n%s개 중 %s개 통과, %s개 실패\n' \
  "${tests_run}" "$((tests_run - tests_failed))" "${tests_failed}"

if [ "${tests_failed}" -ne 0 ]; then
  printf '::error::동일 출처 검증이 실패했다.\n'
  exit 1
fi

if [ "${keep_stack}" = true ]; then
  # 위 `백엔드 비가용` 단계가 백엔드를 정지시켰다. 스택을 남길 때는 건강한 상태로 넘겨야
  # 뒤에 오는 검증(Story 1.1 회귀)이 죽은 백엔드를 우연히 통과하거나 우연히 실패하지 않는다.
  # `up --wait`는 정지된 컨테이너를 다시 시작하고 healthcheck가 healthy가 될 때까지 기다린다.
  # 이미지는 앞에서 만든 것을 그대로 쓴다(`--build` 없음).
  printf '\n백엔드 재기동 (--keep-stack)\n'
  if ! compose up --detach --wait --wait-timeout 120 backend >"${work_dir}/restart.log" 2>&1; then
    fail_setup '스택을 남기려 했지만 백엔드를 다시 기동하지 못했다.' \
      "$(tail -20 "${work_dir}/restart.log" 2>/dev/null)"
  fi
  printf '  기동 완료. 스택이 %s에 떠 있다\n' "${base_url}"
fi
exit 0
