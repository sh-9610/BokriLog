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

# --------------------------------------------------------------------------
# 배포·백업 공용 잠금(Story 3.9). 상태 디렉터리의 `.lock`을 배포(`deploy.sh`)·백업(`backup.sh`)·복원(`restore.sh`)·tombstone 내보내기
# (`tombstone-export.sh`, Story 3.10)가 함께 쓴다 — 넷 모두 일회성 역할 JVM을 띄우므로 blue/green peak 위에 겹치지 않게 한다.
# --------------------------------------------------------------------------

# lock_acquire <잠금 디렉터리> <kind: deploy|backup|restore|tombstone> <operation ID>
#
# mkdir은 원자적이다. 쥐면 그 안에 소유자 파일(`owner` — kind·pid·operation·started)을 rename으로 쓰고 0. 못 쥐면 1이고 `lock_state`에
# 이유를 둔다: `DEPLOY_IN_PROGRESS`·`BACKUP_IN_PROGRESS`·`RESTORE_IN_PROGRESS`·`TOMBSTONE_IN_PROGRESS`(소유자가 살아 있다 — kind로
# 가른다, 모르는 kind는 배포로 읽는다) 또는 `LOCK_STALE`(소유자 pid가 죽었거나 소유자 파일이 없다). 죽은 잠금도 지우지 않는다
# — 진행 중인 일이 정말 없는지는 사람이 확인한다. `lock_owner`에 소유자 파일 내용을 한 줄로 둔다.
lock_state=''
lock_owner=''
lock_acquire() {
  local dir="$1" kind="$2" operation="$3" tmp owner_kind owner_pid
  lock_state=''
  lock_owner=''
  if mkdir "${dir}" 2>/dev/null; then
    tmp="${dir}/.owner.tmp"
    printf 'kind=%s\npid=%s\noperation=%s\nstarted=%s\n' "${kind}" "$$" "${operation}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
      >"${tmp}" && mv "${tmp}" "${dir}/owner"
    return 0
  fi
  # mkdir과 소유자 파일 쓰기 사이의 짧은 틈일 수 있다. 한 번 더 본다.
  [ -f "${dir}/owner" ] || sleep 2
  if [ ! -f "${dir}/owner" ]; then
    lock_state=LOCK_STALE
    lock_owner='소유자 파일 없음'
    return 1
  fi
  owner_kind="$(kv_get "${dir}/owner" kind)"
  owner_pid="$(kv_get "${dir}/owner" pid)"
  lock_owner="$(awk 'NF' "${dir}/owner" | tr '\n' ' ' | sed 's/ $//')"
  if ! matches "${owner_pid}" '[0-9]+' || ! { kill -0 "${owner_pid}" 2>/dev/null || [ -d "/proc/${owner_pid}" ]; }; then
    lock_state=LOCK_STALE
  else
    case "${owner_kind}" in
      backup) lock_state=BACKUP_IN_PROGRESS ;;
      restore) lock_state=RESTORE_IN_PROGRESS ;;
      tombstone) lock_state=TOMBSTONE_IN_PROGRESS ;;
      *) lock_state=DEPLOY_IN_PROGRESS ;;
    esac
  fi
  return 1
}

# lock_release <잠금 디렉터리>. 자기가 쥔 잠금만 놓는다(소유자 pid가 이 프로세스일 때).
lock_release() {
  [ -d "$1" ] || return 0
  [ "$(kv_get "$1/owner" pid 2>/dev/null)" = "$$" ] || return 0
  rm -f "$1/owner" "$1/.owner.tmp"
  rmdir "$1" 2>/dev/null
}
