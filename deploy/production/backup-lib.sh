#!/usr/bin/env bash
# 백업·복원·tombstone 내보내기의 공용 도우미(Story 3.9에서 뽑아 Story 3.10이 함께 쓴다). `backup.sh`·`restore.sh`·
# `tombstone-export.sh`가 `lib.sh` 다음에 `.`로 읽는다.
#
# 이 파일은 함수만 정의한다. 실행하면 아무 일도 하지 않는다. 함수가 읽는 전역 값은 호출자가 먼저 정한다 — `tools_dir`(도구 설치
# 디렉터리), `keys_dir`(backup key 디렉터리), `posix_modes`(권한을 판정하는가), `rclone_bin`·`rclone_conf`·`step_limit`(저장소 호출),
# `log_file`(도구 출력 기록, 비면 버린다), `remote_of <store>`(저장소 이름 → rclone remote). 배경 실행은 전역 `child`에 그 pid를 둔다.
#
# 규약은 `lib.sh`와 같다: bash 3.2 호환, GNU 전용 플래그 없음, pipefail 아래 `| grep -q` 없음, 값을 `eval`하지 않는다.

# --------------------------------------------------------------------------
# 배경 실행. 긴 단계는 배경에서 돌리고 기다린다 — 그래야 신호가 오면 bash가 그 단계가 끝나기를 기다리지 않고 바로 처리기를 돈다. 한
# 단계에는 상한이 있다(넘으면 끝내고 그 단계의 종료 코드).
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

# --------------------------------------------------------------------------
# 도구·권한
# --------------------------------------------------------------------------

# tool <이름>. 설치 디렉터리의 바이너리 경로(Windows는 .exe). 없으면 빈 출력이다.
tool() {
  if [ -x "${tools_dir}/bin/$1" ]; then
    printf '%s' "${tools_dir}/bin/$1"
  elif [ -x "${tools_dir}/bin/$1.exe" ]; then
    printf '%s' "${tools_dir}/bin/$1.exe"
  fi
}

# probe_posix_modes <rehearsal: true|false>. 이 파일 시스템이 POSIX 권한을 표현하는가를 `posix_modes`에 둔다. Windows의 Git Bash는
# 아니다 — 리허설에 한해 권한 검사를 건너뛴다(운영은 언제나 판정).
probe_posix_modes() {
  local probe
  posix_modes=true
  [ "$1" = true ] || return 0
  probe="$(mktemp "${TMPDIR:-/tmp}/bokrilog-mode.XXXXXX" 2>/dev/null)" || probe=''
  if [ -n "${probe}" ]; then
    chmod 600 "${probe}" 2>/dev/null
    case "$(ls -ln "${probe}" | awk '{ print $1 }')" in
      -rw-------*) ;;
      *) posix_modes=false ;;
    esac
    rm -f "${probe}"
  fi
}

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

# key_problem <key ID>. 그 backup key의 문제 하나를 낸다(없으면 빈 출력): identity 존재·권한, recipient 존재·쓰기 권한, 두 파일이
# 짝인가(`age-keygen -y identity` = recipient).
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

# --------------------------------------------------------------------------
# 저장소
# --------------------------------------------------------------------------

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

# epoch_of_iso <YYYY-MM-DDTHH:MM:SS[.소수](Z|±HH:MM)>. epoch 초(소수는 버린다). 모양 밖이면 빈 출력이다 — 시간대 표기가 없는 값(어느
# 시계의 시각인지 모른다), 없는 날짜(달의 길이·윤년), ±14:00 밖이거나 분이 60 이상인 시간대를 받지 않는다.
epoch_of_iso() {
  matches "$1" '[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})' || return 0
  awk -v t="$1" 'BEGIN {
    y = substr(t, 1, 4) + 0; m = substr(t, 6, 2) + 0; d = substr(t, 9, 2) + 0
    hh = substr(t, 12, 2) + 0; mi = substr(t, 15, 2) + 0; ss = substr(t, 18, 2) + 0
    if (m < 1 || m > 12 || d < 1 || hh > 23 || mi > 59 || ss > 59) exit
    # 달의 길이(윤년 포함). 2월 30일·4월 31일 같은 날짜는 받지 않는다.
    leap = (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0))
    split("31 28 31 30 31 30 31 31 30 31 30 31", days, " ")
    limit = days[m] + (m == 2 && leap)
    if (d > limit) exit
    zone = substr(t, length(t), 1); offset = 0
    if (zone != "Z") {
      sign = substr(t, length(t) - 5, 1)
      oh = substr(t, length(t) - 4, 2) + 0; om = substr(t, length(t) - 1, 2) + 0
      # 시간대는 ±14:00 안이고 분은 60 미만이다.
      if (om > 59 || oh * 60 + om > 14 * 60) exit
      offset = oh * 3600 + om * 60
      if (sign == "+") offset = -offset
    }
    yy = y - (m <= 2); era = int(yy / 400); yoe = yy - era * 400
    doy = int((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5) + d - 1
    doe = yoe * 365 + int(yoe / 4) - int(yoe / 100) + doy
    printf "%.0f", (era * 146097 + doe - 719468) * 86400 + hh * 3600 + mi * 60 + ss + offset
  }'
}
