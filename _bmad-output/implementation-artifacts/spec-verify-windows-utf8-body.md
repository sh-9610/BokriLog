---
title: '동일 출처 검증 스크립트의 요청 본문을 Windows에서도 UTF-8 그대로 보내기'
type: 'bugfix'
created: '2026-10-12'
status: 'done'
baseline_commit: 'd069f7da962ed022d6f6fd497ec115edda643ae0'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Windows Git Bash(Docker Desktop 29.7.2)에서 `bash deploy/compose/verify-same-origin.sh`를 돌리면 develop(`d069f7d`)과 `feature/3-8-blue-green-deploy` 모두 약 1051개 단정 중 62개가 같은 목록으로 실패한다. 원인은 이렇다. Git Bash의 `curl`은 mingw 네이티브 빌드(`/mingw64/bin/curl`, x86_64-w64-mingw32)여서 argv를 ANSI 코드 페이지(이 기기는 949)로 해석한다. 그래서 `--data "<JSON>"`으로 넘긴 한글이 CP949 바이트로 나간다. 실측 결과 `{"c":"읽기"}`를 인자로 넘기면 `C0 D0 B1 E2`(Content-Length 12)가 나가고, `--data-binary @file`·`@-`로 넘기면 `EC 9D BD EA B8 B0`(14)가 나간다. Jackson이 잘못된 UTF-8을 읽지 못해 백엔드가 400 `BAD_REQUEST`를 답하고, 그 뒤 세션 회전·스냅샷·로그아웃·로그 누출 검사가 연쇄로 실패한다(로그 누출 검사는 빈 쿠키 값을 받는다). 한글이 실리는 본문은 다섯 개다. Bybit 연결 확정 세 번(서버가 준 확인 문구), 감사 내보내기 준비(`reason`)와 확정(서버가 준 확인 문구)이다. CI는 Linux만 돌기 때문에 이 결함을 보지 못한다.

**Approach:** JSON 요청 본문을 작업 디렉터리의 파일에 바이트 그대로 쓰고 `--data-binary @<파일>`로 보낸다. 같은 형태가 다시 들어오지 않도록 `fetch`가 argv 본문 플래그를 거부하게 하고, 이 규칙을 `DEVELOPMENT.md`의 두 기기 개발 절에 적는다.

## Boundaries & Constraints

**Always:** bash 3.2와 POSIX 도구만 쓴다(GNU 전용 플래그 금지, pipefail 아래에서 `| grep -q` 금지). 다섯 요청이 보내는 JSON 텍스트·헤더·메서드·경로는 지금과 바이트 단위로 같아야 한다. 본문 파일은 이미 `cleanup`이 지우는 `work_dir` 안에만 둔다. 새로 쓰거나 고치는 주석은 한국어로 쓴다.

**Ask First:** 백엔드·compose·CI 워크플로를 바꿔야 하는 경우. 이 다섯 곳 밖에서 Windows 전용 실패가 새로 드러나는 경우.

**Never:** 단정을 지우거나 약하게 만들지 않는다(기대 상태 코드와 code를 바꾸거나 건너뛰기 분기를 두는 것 포함). 백엔드 쪽에서 CP949를 받아 주지 않는다. 한글 문구를 ASCII로 바꾸지 않는다. 사용자 셸의 코드 페이지(`chcp`)나 로캘을 바꾸라고 요구하는 방식으로 고치지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Windows 한글 본문 | Git Bash, CP949, 확인 문구 `읽기 전용 Bybit 연결을 만듭니다. 확인 코드 …` | 백엔드가 UTF-8 그대로 받는다. 쓰기 키 422, IP 미바인딩 422, 정상 200 | N/A |
| Linux·macOS | UTF-8 셸 | 지금과 같은 바이트가 나가고 결과도 같다 | N/A |
| 회귀 | 누군가 `fetch … --data "<본문>"`을 다시 쓴다 | 모든 플랫폼에서 단정 실패로 드러난다 | `record_fail`, 요청은 보내지 않는다 |

</frozen-after-approval>

## Code Map

- `deploy/compose/verify-same-origin.sh:345` -- `fetch()`. 모든 curl 호출이 지나는 유일한 지점이다. `request_seq`와 `work_dir/body-N.txt` 명명 규칙이 여기 있다.
- `deploy/compose/verify-same-origin.sh:2310,2329,2340` -- Bybit 연결 확정 세 번. `connection_confirmation`은 2284·2296에서 서버 응답을 sed로 뽑은 한글이다.
- `deploy/compose/verify-same-origin.sh:2704,2715` -- 감사 내보내기 준비(리터럴 `reason` "동일 출처 검증")와 확정(`operator_phrase` "감사 내보내기 AUDIT_LEDGER …").
- `deploy/compose/verify-same-origin.sh:2797` -- 기존 선례: `--data-binary "@${oversized_body}"`.
- `deploy/compose/verify-same-origin.sh:235` -- `cleanup`이 `work_dir`을 지운다. 본문 파일(Secret 포함)의 수명이 여기서 끝난다.
- `deploy/compose/verify-same-origin.sh:2394` -- 연쇄 경로. 연결이 실패하면 `connection_rotated_cookie`가 비고, 그 값이 `google_session_cookie`로 넘어가 3111의 누출 검사가 빈 값을 본다. 근본 원인을 고치면 같이 풀린다.
- `DEVELOPMENT.md:27-45` -- 두 기기 개발 절. 경로 구분자 규칙(43행) 옆에 같은 결의 규칙을 하나 더한다.
- 읽기 전용 근거: `backend/.../BybitConnectionController.java:291`. 필드가 없으면 `CONNECTION_INVALID_FORMAT`이고, 400 `BAD_REQUEST`는 본문 파싱 실패에서 나온다.

## Tasks & Acceptance

**Execution:**
- [x] `deploy/compose/verify-same-origin.sh` -- `fetch` 옆에 본문 헬퍼를 둔다. 인자 하나를 `printf '%s'`로 `work_dir`의 새 파일에 쓰고 그 경로를 전역 변수로 남긴다(서브셸을 만들지 않는다). 다섯 호출을 `--data-binary "@${그 경로}"`로 바꾼다. `fetch`는 `--data`·`--data-raw`·`--data-ascii`·`-d`·`--json` 인자를 보면 `record_fail`을 남기고 그 요청을 보내지 않는다. 이유는 주석으로 적는다 -- 근본 원인 제거와 회귀 방지.
- [x] `DEVELOPMENT.md` -- 두 기기 개발 절에 한 문단을 더한다. Git Bash curl이 argv를 ANSI 코드 페이지로 읽으므로 비ASCII가 들 수 있는 본문은 파일로 넘긴다는 내용이다 -- 다음 스크립트 작성자를 위한 규칙.

**Acceptance Criteria:**
- Given Windows Git Bash(CP949), when `bash deploy/compose/verify-same-origin.sh`를 돌리면, then 앞의 62개 실패가 모두 통과하고 새 실패가 없다.
- Given 바뀐 스크립트, when 다섯 요청이 나가면, then 본문이 이전 `--data` 문자열과 같은 UTF-8 바이트다(로컬 캡처로 확인한다).
- Given 바뀐 스크립트, when `bash -n`을 돌리면, then 구문 오류가 없다.

## Spec Change Log

- 1차 리뷰 patch(2026-10-12): 리뷰 세 층이 같은 결함을 짚었다. 가드가 플래그 이름만 봐서 `--data-binary '<인라인>'`·`--data-urlencode`·`-F`가 지나가고, 안전한 `-d @file`은 거부됐다. 가드를 값 기준으로 바꿨다(본문 플래그의 값은 `@`로 시작해야 하고, `--data-raw`·`--data-urlencode`·`--form` 계열은 거부, 거부 메시지에는 값을 싣지 않는다). `request_body`는 쓰기 실패 시 `fail_setup`을 부르고, `fetch`는 요청 뒤 본문 파일을 지우고 전역을 비운다. 이전 본문(Secret 포함)을 다른 경로로 다시 보내는 것을 막고 Secret이 디스크에 남는 시간을 줄인다. DEVELOPMENT.md는 플래그 목록 대신 argv 전체(본문·헤더·URL)에 걸리는 규칙으로 다시 썼다. 검증 grep은 208행 `[ -d "`에 걸리는 오탐이 있어 바꿨다. KEEP: 다섯 호출의 JSON 텍스트·헤더, `printf '%s'` 파일 쓰기, `work_dir` 안에 두는 것.

## Verification

**Commands:**
- `bash -n deploy/compose/verify-same-origin.sh` -- expected: 출력 없음
- `bash deploy/compose/verify-same-origin.sh` (Windows Git Bash, 다른 `bokrilog` 프로젝트·18080 포트가 비어 있을 때) -- expected: 실패 0, 종료 코드 0
- `grep -nE -- '(--data[a-z-]*|--json|--form[a-z-]*) ' deploy/compose/verify-same-origin.sh | grep -vE -- '--data-binary "@'` -- expected: `fetch` 가드 자신의 세 줄(`case` 패턴 두 줄과 안내 메시지)만 일치
- 가드 stub 확인(curl을 기록 함수로 가리고 `request_body`·`discard_request_body`·`fetch`를 뽑아 실행) -- expected: 인라인 본문·`--data-raw`·`--form`·`--data-urlencode`·값 없는 끝 플래그는 curl 미호출·`last_status=000`·값 미출력, `@<파일>`·`@-`는 curl 도달, 요청 뒤 본문 파일 없음

## Suggested Review Order

**본문을 파일로 보내기(근본 원인)**

- 진입점. argv 대신 `work_dir` 파일에 바이트 그대로 쓰는 이유가 docblock에 있다
  [`verify-same-origin.sh:364`](../../deploy/compose/verify-same-origin.sh#L364)

- Bybit 연결 확정 세 번. JSON 텍스트는 그대로이고 전달 방식만 바뀐다
  [`verify-same-origin.sh:2380`](../../deploy/compose/verify-same-origin.sh#L2380)

- 감사 내보내기 준비와 확정. 리터럴 한글 `reason`과 서버 확인 문구가 실린다
  [`verify-same-origin.sh:2777`](../../deploy/compose/verify-same-origin.sh#L2777)

**재발 방지**

- 가드는 플래그 이름이 아니라 값이 `@`인지를 본다. 메시지에 값을 싣지 않는다
  [`verify-same-origin.sh:396`](../../deploy/compose/verify-same-origin.sh#L396)

- 요청 뒤 본문 파일을 지워 이전 Secret 본문이 다시 나가지 않게 한다
  [`verify-same-origin.sh:372`](../../deploy/compose/verify-same-origin.sh#L372)

- 저장소 규칙. argv 전체(본문·헤더·URL)에 걸리고, Linux CI는 이 결함을 보지 못한다
  [`DEVELOPMENT.md:45`](../../DEVELOPMENT.md#L45)
