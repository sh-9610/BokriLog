#!/usr/bin/env bash
# 백업 도구(age·rclone)의 고정 버전 설치와 확인(Story 3.9).
#
# 왜 있는가: 백업 절차(`backup.sh`)는 운영 호스트에서 age로 암호화·복호화하고 rclone으로 두 S3 호환 저장소에 올린다. 운영 서버에서
# 이미지를 빌드하지 않으므로 두 도구를 담은 이미지를 따로 들이지 않고, 공식 배포본을 고정 버전·SHA-256 표로 받는다(promtool의
# `check-alert-rules.sh`와 같은 관용 — `.partial`에 받은 뒤 대조하고 원자 이동, 캐시는 쓸 때마다 다시 대조). 버전의 유일한 정의가
# 이 파일이다 — `backup.sh`는 `--check`로 설치된 바이너리가 이 버전인지 확인한 뒤에만 돈다. 설치는 꺼낸 바이너리의 SHA-256을 설치
# manifest(`<prefix>/MANIFEST`)에 남기고, `--check`가 그 값으로 바이너리를 다시 대조한다(설치 뒤 바뀐 바이너리를 잡는다). 설치
# 디렉터리는 root 소유이고 백업 실행 계정(`bokrilog`)이 쓸 수 없어야 한다(DEVELOPMENT.md).
#
# 실행
#   deploy/production/install-backup-tools.sh [--prefix <디렉터리>]           받아서 대조하고 <디렉터리>/bin에 둔다
#   deploy/production/install-backup-tools.sh --check [--prefix <디렉터리>]   설치된 도구가 이 버전인지만 본다(내려받지 않는다)
#
# 환경
#   BOKRILOG_BACKUP_TOOLS     설치 디렉터리(기본 /opt/bokrilog/backup-tools). `--prefix`가 우선한다.
#   BACKUP_TOOLS_CACHE_DIR    내려받은 배포본의 캐시(기본 `${XDG_CACHE_HOME:-$HOME/.cache}/bokrilog/backup-tools`).
#
# 지원 플랫폼: linux-arm64(운영 A1)·linux-amd64(CI)·darwin-arm64·darwin-amd64·windows-amd64(Git Bash, 개발 리허설).
#
# 종료 코드
#   0 -- 설치·확인 완료
#   1 -- `--check`: 도구가 없거나 버전이 다르다
#   2 -- 준비 실패(지원하지 않는 플랫폼, 내려받기 실패, checksum 불일치, 풀기 실패, 도구 없음)
#
# bash 3.2 호환이고 GNU 전용 플래그를 쓰지 않는다. `pipefail` 아래에서 조기 종료하는 grep 파이프를 조건에 쓰지 않는다(PR #9).

set -uo pipefail

AGE_VERSION='v1.3.2'
RCLONE_VERSION='v1.75.1'

prefix="${BOKRILOG_BACKUP_TOOLS:-/opt/bokrilog/backup-tools}"
mode=install

fail_setup() {
  printf '::error::백업 도구 준비 실패: %s\n' "$1" >&2
  if [ -n "${2:-}" ]; then
    printf '%s\n' "$2" >&2
  fi
  exit 2
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --prefix)
      [ "$#" -ge 2 ] || fail_setup '--prefix에 값이 없다.'
      prefix="$2"
      shift 2
      ;;
    --check) mode=check; shift ;;
    *) fail_setup "알 수 없는 인자: $1" ;;
  esac
done

# --------------------------------------------------------------------------
# 플랫폼과 고정 checksum
# --------------------------------------------------------------------------

case "$(uname -s)" in
  Linux) os='linux' ;;
  Darwin) os='darwin' ;;
  MINGW* | MSYS* | CYGWIN*) os='windows' ;;
  *) fail_setup "지원하지 않는 OS다: $(uname -s)" ;;
esac
case "$(uname -m)" in
  x86_64 | amd64) arch='amd64' ;;
  arm64 | aarch64) arch='arm64' ;;
  *) fail_setup "지원하지 않는 아키텍처다: $(uname -m)" ;;
esac
[ "${os}" = windows ] && [ "${arch}" != amd64 ] && fail_setup 'Windows는 amd64만 지원한다.'

exe=''
[ "${os}" = windows ] && exe='.exe'
# rclone은 macOS를 `osx`라 부른다.
rclone_os="${os}"
[ "${os}" = darwin ] && rclone_os='osx'

if [ "${os}" = windows ]; then
  age_archive="age-${AGE_VERSION}-${os}-${arch}.zip"
else
  age_archive="age-${AGE_VERSION}-${os}-${arch}.tar.gz"
fi
rclone_dir="rclone-${RCLONE_VERSION}-${rclone_os}-${arch}"
rclone_archive="${rclone_dir}.zip"

# expected_sha256 <배포본 이름>. age는 GitHub 릴리스의 자산 digest, rclone은 공식 `SHA256SUMS`(서명된 목록)의 값이다. 버전을 올릴 때
# 표 전체를 함께 바꾼다.
expected_sha256() {
  case "$1" in
    "age-v1.3.2-linux-arm64.tar.gz") printf '%s' '6b8dc4333c53a5a57c9e5834e3a48f92605d7154014cd07269ff3327db5d37f4' ;;
    "age-v1.3.2-linux-amd64.tar.gz") printf '%s' 'cbe24006683f8eb669266162894b9a522a1af52f2665fbc63a4bb032ed26ac10' ;;
    "age-v1.3.2-darwin-arm64.tar.gz") printf '%s' 'e2020b073c44f692685a24d6abc378817eb81ffaaf49fd0531ef8565f767f2f5' ;;
    "age-v1.3.2-darwin-amd64.tar.gz") printf '%s' '1d1e4bc66e1427edad7739ae7616157de0e79db8b6d2a1497d7d9925fb06a539' ;;
    "age-v1.3.2-windows-amd64.zip") printf '%s' 'f48d8f8f9ebe903ab5027ed067652f2cc1db94bc206976430133b905dcd8e8c7' ;;
    "rclone-v1.75.1-linux-arm64.zip") printf '%s' '03f2504174034b6d004152ed7369251c9a9ec1f7e0836eda420f5c7a5ec0dff9' ;;
    "rclone-v1.75.1-linux-amd64.zip") printf '%s' '982b5aa772841168f8e380f139e9e787b2a105403e32b94da8676a0e1c0a13ab' ;;
    "rclone-v1.75.1-osx-arm64.zip") printf '%s' 'c61d7a371c62bcbbe882c3423aa4b8bf63485c248dd0f692997b8f0c3f6d0c6f' ;;
    "rclone-v1.75.1-osx-amd64.zip") printf '%s' '29253d0288b8fbbac46baad6e5f6add6cb01d462c79f10805bbd4631c4cdf82c' ;;
    "rclone-v1.75.1-windows-amd64.zip") printf '%s' '200eb602c126d82aa38b51e0f6b9ae837473ff99b51278d3f6f837574c494d6e' ;;
    *) printf '' ;;
  esac
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{ print $1 }'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{ print $1 }'
  else
    printf ''
  fi
}

# --------------------------------------------------------------------------
# --check
# --------------------------------------------------------------------------

# version_of <바이너리> <age|rclone>. 첫 줄의 버전 낱말.
version_of() {
  case "$2" in
    age) "$1" --version 2>/dev/null | awk 'NR == 1 { print $1 }' ;;
    rclone) "$1" version 2>/dev/null | awk 'NR == 1 { print $2 }' ;;
  esac
}

# binary_of <이름>. 설치된 바이너리 경로(Windows는 .exe도 본다). 없으면 빈 문자열.
binary_of() {
  if [ -f "${prefix}/bin/$1" ]; then
    printf '%s' "${prefix}/bin/$1"
  elif [ -f "${prefix}/bin/$1.exe" ]; then
    printf '%s' "${prefix}/bin/$1.exe"
  fi
}

# 설치 manifest. 꺼낸 바이너리마다 `<sha256>  <bin/이름>` 한 줄. 설치가 쓰고 `--check`가 다시 대조한다 — 배포본 checksum은 받을 때만
# 보므로, 설치 뒤에 바뀐 바이너리는 이 대조가 잡는다.
manifest="${prefix}/MANIFEST"

check_installed() {
  local tool path version want status=0 recorded actual
  for tool in age age-keygen rclone; do
    path="$(binary_of "${tool}")"
    if [ -z "${path}" ] || [ ! -x "${path}" ]; then
      printf '  없음  %s\n' "${prefix}/bin/${tool}"
      status=1
      continue
    fi
    recorded="$(awk -v want="bin/${tool}" '$2 == want { print $1; exit }' "${manifest}" 2>/dev/null)"
    actual="$(sha256_of "${path}")"
    if [ -z "${recorded}" ] || [ "${recorded}" != "${actual}" ]; then
      printf '  변조  %s의 SHA-256이 설치 manifest와 다르다(또는 manifest가 없다)\n' "${path##*/}"
      status=1
    fi
  done
  [ "${status}" -eq 0 ] || return 1
  version="$(version_of "$(binary_of age)" age)"
  want="${AGE_VERSION}"
  if [ "${version}" != "${want}" ]; then
    printf '  버전  age %s (기대 %s)\n' "${version:-?}" "${want}"
    status=1
  fi
  version="$(version_of "$(binary_of rclone)" rclone)"
  want="${RCLONE_VERSION}"
  if [ "${version}" != "${want}" ]; then
    printf '  버전  rclone %s (기대 %s)\n' "${version:-?}" "${want}"
    status=1
  fi
  return "${status}"
}

if [ "${mode}" = check ]; then
  if check_installed; then
    printf 'age %s, rclone %s (%s)\n' "${AGE_VERSION}" "${RCLONE_VERSION}" "${prefix}/bin"
    exit 0
  fi
  printf '::error::백업 도구가 고정 버전이 아니다. deploy/production/install-backup-tools.sh로 설치한다.\n' >&2
  exit 1
fi

# --------------------------------------------------------------------------
# 설치
# --------------------------------------------------------------------------

command -v curl >/dev/null 2>&1 || fail_setup 'curl이 없다.'
command -v unzip >/dev/null 2>&1 || fail_setup 'unzip이 없다(rclone 배포본은 zip이다). 운영 호스트에 설치한다.'
command -v tar >/dev/null 2>&1 || fail_setup 'tar가 없다.'
[ -n "$(sha256_of /dev/null 2>/dev/null)" ] || fail_setup 'sha256sum·shasum이 없어 checksum을 대조할 수 없다.'

cache_dir="${BACKUP_TOOLS_CACHE_DIR:-${XDG_CACHE_HOME:-${HOME}/.cache}/bokrilog/backup-tools}"
mkdir -p "${cache_dir}" || fail_setup "캐시 디렉터리를 만들지 못했다: ${cache_dir}"
mkdir -p "${prefix}/bin" || fail_setup "설치 디렉터리를 만들지 못했다: ${prefix}/bin"
work="$(mktemp -d)" || fail_setup '임시 디렉터리를 만들지 못했다.'
trap 'rm -rf "${work}"' EXIT

# fetch <배포본 이름> <URL>. 캐시가 고정 checksum과 맞으면 그것을, 아니면 받아서 대조한 뒤 캐시에 원자 저장한다. 경로를 낸다.
fetch() {
  local archive="$1" url="$2" expected actual cached partial
  expected="$(expected_sha256 "${archive}")"
  [ -n "${expected}" ] || fail_setup "고정 checksum이 없는 배포본이다: ${archive}"
  cached="${cache_dir}/${archive}"
  actual=''
  [ -f "${cached}" ] && actual="$(sha256_of "${cached}")"
  if [ "${actual}" != "${expected}" ]; then
    partial="${cached}.partial.$$"
    printf '내려받기: %s\n' "${url}" >&2
    if ! curl --fail --silent --show-error --location --retry 2 --max-time 120 --output "${partial}" "${url}" \
      2>"${work}/curl.log"; then
      rm -f "${partial}"
      fail_setup "내려받지 못했다: ${archive}" "$(cat "${work}/curl.log" 2>/dev/null)"
    fi
    actual="$(sha256_of "${partial}")"
    if [ "${actual}" != "${expected}" ]; then
      rm -f "${partial}"
      fail_setup "checksum이 맞지 않는다: ${archive}" "기대 ${expected}, 실제 ${actual}"
    fi
    mv -f "${partial}" "${cached}" || fail_setup "배포본을 캐시에 두지 못했다: ${cached}"
  fi
  printf '%s' "${cached}"
}

# place <꺼낸 파일> <이름>. 같은 디렉터리의 임시 이름으로 복사한 뒤 rename으로 바꾼다(반쯤 쓴 바이너리가 남지 않는다).
place() {
  local tmp="${prefix}/bin/.$2.tmp.$$"
  cp "$1" "${tmp}" && chmod 755 "${tmp}" && mv -f "${tmp}" "${prefix}/bin/$2" \
    || { rm -f "${tmp}"; fail_setup "설치하지 못했다: ${prefix}/bin/$2"; }
}

age_cached="$(fetch "${age_archive}" "https://github.com/FiloSottile/age/releases/download/${AGE_VERSION}/${age_archive}")" || exit 2
rclone_cached="$(fetch "${rclone_archive}" "https://github.com/rclone/rclone/releases/download/${RCLONE_VERSION}/${rclone_archive}")" || exit 2

mkdir -p "${work}/age" "${work}/rclone"
if [ "${os}" = windows ]; then
  unzip -q -o "${age_cached}" "age/age${exe}" "age/age-keygen${exe}" -d "${work}/age" 2>"${work}/unzip.log" \
    || fail_setup 'age 배포본을 풀지 못했다.' "$(cat "${work}/unzip.log")"
else
  tar -xzf "${age_cached}" -C "${work}/age" age/age age/age-keygen 2>"${work}/tar.log" \
    || fail_setup 'age 배포본을 풀지 못했다.' "$(cat "${work}/tar.log")"
fi
unzip -q -o "${rclone_cached}" "${rclone_dir}/rclone${exe}" -d "${work}/rclone" 2>"${work}/unzip.log" \
  || fail_setup 'rclone 배포본을 풀지 못했다.' "$(cat "${work}/unzip.log")"

place "${work}/age/age/age${exe}" "age${exe}"
place "${work}/age/age/age-keygen${exe}" "age-keygen${exe}"
place "${work}/rclone/${rclone_dir}/rclone${exe}" "rclone${exe}"

# 설치 manifest를 원자적으로 쓴다(rename). `--check`가 이 값으로 바이너리를 다시 대조한다.
{
  # 이름은 플랫폼과 무관한 논리 이름(`bin/age`)이다 — Windows의 `.exe`는 경로를 풀 때 붙는다.
  for name in age age-keygen rclone; do
    printf '%s  bin/%s\n' "$(sha256_of "$(binary_of "${name}")")" "${name}"
  done
} >"${manifest}.tmp.$$" && mv -f "${manifest}.tmp.$$" "${manifest}" || fail_setup "설치 manifest를 쓰지 못했다: ${manifest}"

if ! check_installed; then
  fail_setup '설치한 도구의 버전이 고정 버전과 다르다.'
fi
printf '설치 완료: age %s, rclone %s (%s)\n' "${AGE_VERSION}" "${RCLONE_VERSION}" "${prefix}/bin"
exit 0
