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
  'NGINX_ENTRYPOINT_QUIET_LOGS'
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
psql_value() {
  compose exec -T postgres psql \
    --username=bokrilog --dbname=bokrilog --no-password \
    --tuples-only --no-align --command="$1" 2>"${work_dir}/psql-err.txt" \
    | tr -d '[:space:]'
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

# 응답 헤더 계약. 모든 응답에서 확인한다. 오류 응답에서 특히 중요하다.
assert_security_headers() {
  local context="$1" value

  assert_equals "${context} · CSP" "$(header_value 'Content-Security-Policy')" "${expected_csp}"
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
fi

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
