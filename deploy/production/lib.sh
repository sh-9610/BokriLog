#!/usr/bin/env bash
# 운영 배포 스크립트의 공용 도우미(Story 3.8). `preflight.sh`·`deploy.sh`·`rehearse-deploy.sh`·`test-preflight.sh`가 `.`로 읽는다.
#
# 이 파일은 함수만 정의한다. 실행하면 아무 일도 하지 않는다.
#
# 규약: bash 3.2 호환(연관 배열·`${v,,}`·`mapfile` 없음), GNU 전용 플래그(`sed -i`·`grep -P`·`readlink -f`) 없음, `set -o pipefail`
# 아래에서 `생산자 | grep -q`를 조건에 쓰지 않는다(PR #9 — `grep … >/dev/null`로 입력 끝까지 읽는다). 값을 `eval`하지 않는다.

# kv_get <파일> <키>
#
# `key=value` 파일에서 그 키의 값을 낸다. 주석(`#`)과 빈 줄은 건너뛰고, 값은 첫 `=` 뒤 전부다(따옴표를 풀지 않는다 — 값에 따옴표를
# 쓰지 않는다). 키가 없으면 빈 문자열이다. 파일을 셸로 실행하지 않으므로 값에 든 `$(…)`가 실행되지 않는다.
kv_get() {
  awk -v want="$2" '
    /^[[:space:]]*#/ { next }
    /^[[:space:]]*$/ { next }
    {
      pos = index($0, "=")
      if (pos == 0) next
      key = substr($0, 1, pos - 1)
      sub(/^[[:space:]]+/, "", key)
      sub(/[[:space:]]+$/, "", key)
      if (key == want && !found) {
        value = substr($0, pos + 1)
        sub(/\r$/, "", value)
        print value
        found = 1
      }
    }
  ' "$1"
}

# kv_keys <파일>. 주석·빈 줄을 뺀 키를 줄마다 낸다(등장 순서, 중복 포함).
kv_keys() {
  awk '
    /^[[:space:]]*#/ { next }
    /^[[:space:]]*$/ { next }
    {
      pos = index($0, "=")
      if (pos == 0) { print "<no-equals>"; next }
      key = substr($0, 1, pos - 1)
      sub(/^[[:space:]]+/, "", key)
      sub(/[[:space:]]+$/, "", key)
      print key
    }
  ' "$1"
}

# sha256_of <파일>. 소문자 16진 64자. 도구가 없으면 빈 문자열이다(호출자가 준비 실패로 다룬다).
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{ print $1 }'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{ print $1 }'
  fi
}

# have_sha256_tool. sha256 도구가 있는가.
have_sha256_tool() {
  command -v sha256sum >/dev/null 2>&1 || command -v shasum >/dev/null 2>&1
}

# matches <값> <확장 정규식>. 값 전체가 정규식과 맞는가. `grep -q`를 파이프 끝에 두지 않는다.
matches() {
  printf '%s\n' "$1" | grep -E -x -- "$2" >/dev/null
}

# mib_of <mem_limit 값>. `768m`·`3g` 모양을 MiB 정수로 낸다. 다른 모양이면 빈 문자열이다.
mib_of() {
  case "$1" in
    *[!0-9mg]* | '' ) return 0 ;;
  esac
  if matches "$1" '[0-9]+m'; then
    printf '%s' "${1%m}"
  elif matches "$1" '[0-9]+g'; then
    printf '%s' "$(( ${1%g} * 1024 ))"
  fi
}

# compose_service_block <compose 파일> <서비스 이름>. 그 서비스의 본문 줄만 낸다.
#
# YAML 파서를 쓰지 않는다(운영 서버에 도구를 더하지 않는다). 대신 이 저장소의 compose 들여쓰기 규약에 기댄다 — 0칸 최상위 키,
# 2칸 서비스 이름, 4칸 서비스 속성. `ci.yml`을 읽는 `test-ci-scripts.sh`와 같은 관용이다.
compose_service_block() {
  awk -v want="  $2:" '
    /^[[:space:]]*#/ { next }
    /^services:[[:space:]]*$/ { inservices = 1; next }
    inservices == 0 { next }
    /^[^[:space:]#]/ { inservices = 0; inblock = 0; next }
    /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ { inblock = ($0 == want); next }
    inblock { print }
  ' "$1"
}

# compose_service_value <compose 파일> <서비스> <속성>. 4칸 들여쓴 `속성: 값`의 값(따옴표 제거).
compose_service_value() {
  compose_service_block "$1" "$2" | awk -v want="$3" '
    {
      line = $0
      if (index(line, "    " want ":") != 1) next
      value = substr(line, length("    " want ":") + 1)
      sub(/^[[:space:]]+/, "", value)
      sub(/[[:space:]]+#.*$/, "", value)
      gsub(/^["\047]|["\047]$/, "", value)
      print value
      exit
    }
  '
}

# random_hex <바이트 수>. 소문자 16진.
random_hex() {
  od -An -N "$1" -tx1 /dev/urandom | tr -d ' \n'
}
