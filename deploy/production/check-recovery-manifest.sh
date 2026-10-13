#!/usr/bin/env bash
# 복구 manifest 검사기(Story 3.10 AC-1).
#
# 왜 있는가: 서버·OCI 계정을 잃은 뒤 "다시 세우는 법"은 OCI 밖에 버전을 붙여 둔 manifest 하나가 담는다(PORTFOLIO-SOLUTION-DESIGN). 그
# 파일은 저장소 밖에 있다 — 저장소에는 양식(`recovery-manifest.example`)과 이 검사기만 둔다. 검사기는 manifest가 필요한 것을 모두
# 가리키는지, 그리고 **평문 비밀을 담지 않았는지**를 본다. 값은 위치 참조(`ref:<소문자 경로>`)·age 암호문(`age:<base64>`)·버전·digest·
# 이름뿐이다.
#
# 실행: deploy/production/check-recovery-manifest.sh <manifest 파일>
#
# 형식: `key=value` 한 줄씩(`#` 주석·빈 줄 허용). 키는 아래 표의 것만, 한 번씩. 필수 키(값의 종류)
#   manifest_version              버전(`1.0.0` 모양) — manifest 자체의 버전. 바꿀 때 새 버전으로 발행한다
#   image                         앱 이미지 digest 참조(`<repository>@sha256:<64hex>`, tag만은 거부)
#   terraform                     Terraform source·state 복구법: `manual`(수동 재구축) 또는 `ref:`
#   dns · tls · oauth · grafana · backup_config     설정 위치 `ref:`
#   backup_key_ids                backup key ID 목록(쉼표, `backup.sh`의 key ID 모양). 저장소에 남은 generation이 쓰는 것 전부
#   credential_active_key_version 자격정보 key ring의 active key version(이름)
#   credential_keys · audit_digest_key · tombstone_digest_key · tombstone_age_key · backup_age_keys
#                                 암호화된 운영 비밀의 위치 `ref:` 또는 age 암호문 `age:`
#   bootstrap_order               기동 순서(쉼표로 이은 단계 이름)
#
# 거부(종료 1): 필수 키 없음·알 수 없는 키·중복 키·값 종류 밖, 그리고 줄 어디에든(주석·참조 경로·이름 칸 포함) 평문 비밀 모양 —
# `AGE-SECRET-KEY-`, PEM(`-----BEGIN`), Terraform state 본문(`"terraform_version"`·`"lineage"`·`"resources"`·`"serial"`), 16진 43자 이상
# (`HEX_SECRET`), 대소문자·숫자가 섞인 43자 이상의 base64·base64url(`LONG_BASE64`), 글자·숫자가 섞이고 구분자가 거의 없는 43자 이상의
# 토큰(`LONG_TOKEN`). 걷어 내는 것은 비밀 키 값 전체가 진짜 age 암호문(`age-encryption.org/v1` 접두사)인 경우와 `image=` 값 전체가 digest
# 참조인 경우뿐이다.
# 거부 사유는 키 이름과 code만 출력한다(값을 찍지 않는다).
#
# 종료 코드
#   0 -- 통과
#   1 -- 거부(사유가 한 줄씩)
#   2 -- 준비 실패(인자·파일 없음)

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib.sh
. "${script_dir}/lib.sh"

[ "$#" -eq 1 ] || { printf '::error::사용법: check-recovery-manifest.sh <manifest 파일>\n' >&2; exit 2; }
manifest="$1"
[ -f "${manifest}" ] || { printf '::error::manifest 파일이 없다: %s\n' "${manifest}" >&2; exit 2; }

refusals=0
refuse() {
  refusals=$((refusals + 1))
  printf '  거부  %s: %s\n' "$1" "$2"
}

# 키 → 값의 종류.
kind_of() {
  case "$1" in
    manifest_version) printf version ;;
    image) printf digest ;;
    terraform) printf terraform ;;
    dns | tls | oauth | grafana | backup_config) printf ref ;;
    backup_key_ids) printf key_ids ;;
    credential_active_key_version) printf name ;;
    credential_keys | audit_digest_key | tombstone_digest_key | tombstone_age_key | backup_age_keys) printf secret ;;
    bootstrap_order) printf names ;;
  esac
}

required='manifest_version image terraform dns tls oauth grafana backup_config backup_key_ids credential_active_key_version
credential_keys audit_digest_key tombstone_digest_key tombstone_age_key backup_age_keys bootstrap_order'

# 참조는 소문자 경로다 — 대소문자가 섞인 base64 비밀이 참조 모양으로 들어올 수 없다.
ref_shape='ref:[a-z0-9][a-z0-9._/:@-]{0,199}'
# age 암호문: base64 본문이 `age-encryption.org/v1`로 시작한다(그 접두사의 base64).
age_shape='age:YWdlLWVuY3J5cHRpb24ub3JnL3Yx[A-Za-z0-9+/=]{16,}'

# value_ok <종류> <값>
value_ok() {
  case "$1" in
    version) matches "$2" '[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}' ;;
    digest) matches "$2" '[a-z0-9][a-z0-9._/:-]{0,199}@sha256:[0-9a-f]{64}' ;;
    terraform) [ "$2" = manual ] || matches "$2" "${ref_shape}" ;;
    ref) matches "$2" "${ref_shape}" ;;
    key_ids) matches "$2" '[a-z0-9][a-z0-9_-]{0,31}(,[a-z0-9][a-z0-9_-]{0,31})*' ;;
    name) matches "$2" '[A-Za-z0-9][A-Za-z0-9._-]{0,63}' ;;
    secret) matches "$2" "${ref_shape}" || matches "$2" "${age_shape}" ;;
    names) matches "$2" '[a-z][a-z0-9-]{0,31}(,[a-z][a-z0-9-]{0,31})*' ;;
    *) return 1 ;;
  esac
}

# 평문 비밀 모양. 줄 전체(주석 포함)를 본다 — 주석에 붙여 넣은 key도 평문이다.
secret_code() {
  local line="$1" body
  if printf '%s\n' "${line}" | grep -iF 'AGE-SECRET-KEY-' >/dev/null; then
    printf AGE_IDENTITY
    return
  fi
  if printf '%s\n' "${line}" | grep -F -- '-----BEGIN' >/dev/null; then
    printf PEM_BLOCK
    return
  fi
  if printf '%s\n' "${line}" | grep -E '"(terraform_version|lineage|resources|serial)"[[:space:]]*:' >/dev/null; then
    printf TFSTATE_BODY
    return
  fi
  # 길어도 정상인 값은 둘뿐이다 — 비밀 키의 값 전체가 진짜 age 암호문(`age:` + `age-encryption.org/v1`의 base64로 시작)인 경우와
  # `image=`의 값 전체가 digest 참조인 경우. 그 값만 걷어 낸다(주석·다른 자리의 `age:`·`@sha256:`은 걷지 않는다).
  body="${line}"
  case "${line}" in
    '#'*) ;;
    *=*)
      local key="${line%%=*}" value="${line#*=}"
      if [ "$(kind_of "${key}")" = secret ] && matches "${value}" "${age_shape}"; then
        body="${key}="
      elif [ "${key}" = image ] && matches "${value}" '[a-z0-9][a-z0-9._/:-]{0,199}@sha256:[0-9a-f]{64}'; then
        body="${key}=${value%@sha256:*}"
      fi
      ;;
  esac
  # 16진 비밀(32바이트 이상 = 43자 이상, 흔히 64자).
  if printf '%s\n' "${body}" | grep -E '[0-9A-Fa-f]{43,}' >/dev/null; then
    printf HEX_SECRET
    return
  fi
  local run separators
  for run in $(printf '%s\n' "${body}" | grep -oE '[A-Za-z0-9+/_=-]{43,}' || true); do
    # base64·base64url(`-`·`_`) key: 대문자·소문자·숫자가 모두 섞인 43자 이상.
    if matches "${run}" '.*[A-Z].*' && matches "${run}" '.*[a-z].*' && matches "${run}" '.*[0-9].*'; then
      printf LONG_BASE64
      return
    fi
    # 참조 경로·이름 칸 안의 토큰: 글자와 숫자가 섞이고 구분자(`-`·`_`·`/`)가 거의 없는 43자 이상 — 사람이 쓰는 경로가 아니다.
    separators="$(printf '%s' "${run}" | tr -cd '/_-' | wc -c | tr -d ' ')"
    if matches "${run}" '.*[0-9].*' && matches "${run}" '.*[A-Za-z].*' && [ "${separators}" -le 3 ]; then
      printf LONG_TOKEN
      return
    fi
  done
}

seen=' '
line_number=0
while IFS= read -r raw || [ -n "${raw}" ]; do
  line_number=$((line_number + 1))
  line="$(printf '%s' "${raw}" | tr -d '\r')"
  code="$(secret_code "${line}")"
  if [ -n "${code}" ]; then
    refuse "줄 ${line_number}" "PLAINTEXT_SECRET ${code}"
    continue
  fi
  case "${line}" in
    '' | '#'*) continue ;;
    *=*) ;;
    *) refuse "줄 ${line_number}" 'MALFORMED_LINE'; continue ;;
  esac
  key="${line%%=*}"
  value="${line#*=}"
  kind="$(kind_of "${key}")"
  if [ -z "${kind}" ]; then
    refuse "줄 ${line_number}" 'UNKNOWN_KEY'
    continue
  fi
  case "${seen}" in
    *" ${key} "*) refuse "${key}" 'DUPLICATE_KEY'; continue ;;
  esac
  seen="${seen}${key} "
  if ! value_ok "${kind}" "${value}"; then
    case "${kind}" in
      digest) refuse "${key}" 'IMAGE_NOT_PINNED(<repository>@sha256:<64hex>)' ;;
      *) refuse "${key}" "VALUE_SHAPE(${kind})" ;;
    esac
  fi
done <"${manifest}"

for key in ${required}; do
  case "${seen}" in
    *" ${key} "*) ;;
    *) refuse "${key}" 'MISSING_KEY' ;;
  esac
done

if [ "${refusals}" -ne 0 ]; then
  printf '::error::복구 manifest가 거부됐다(%s건). 값은 ref:·age: 참조·버전·digest·이름만 담는다.\n' "${refusals}"
  exit 1
fi
printf '복구 manifest 통과: %s\n' "${manifest##*/}"
printf '이 검사는 manifest의 모양만 본다. 실제 복원 성공의 증거가 아니다(실제 복원 미검증).\n'
exit 0
