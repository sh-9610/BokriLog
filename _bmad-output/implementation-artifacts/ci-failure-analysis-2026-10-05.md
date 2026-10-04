# 최근 CI 실패 분석 (2026-10-05)

## 범위와 판정

GitHub Actions 최근 100개 실행 중 2026-09-28 UTC 이후 50개를 조회했다. 43개 성공, 7개 실패다. 그중 `feature/*` 실행은 17개(성공10, 실패7)다. 문서 PR·develop push도 전체 분모에 포함되므로 14%를 제품 PR 실패율로 읽지 않는다. 취소되거나 로그가 없는 실행을 성공으로 간주하지 않았으며, 분석 대상 7개는 모두 실패 로그를 확인했다. 시각과 범위는 UTC 기준이다.

| 실행 | 실패 표면 | 확인된 원인과 조치 |
|---|---|---|
| [Story 2.8](https://github.com/sh-9610/BokriLog/actions/runs/36438487762) | 주입 origin 로그인 수단 해제 E2E | 대화상자를 취소하고 즉시 다시 열 때 이전 cancel이 새 준비보다 늦게 끝나 무장을 지웠다. 성공 대신 실패 안내를 받았다. `48a9671bf46f1b3a49761bf12b5690af9e681d18`에서 새 준비가 진행 중 cancel을 기다리도록 수정했다. |
| [Story 2.11](https://github.com/sh-9610/BokriLog/actions/runs/36568092204) | 주입 origin Playwright 전체 실행 | 57개 통과 후 globalTimeout 180초에 도달하여 나머지2개를 실행하지 못했다. 작업이 늘어난 실측을 전체 시험 시간 예산이 따라가지 못했다. 현재 주입 origin은 300초이며 CI 회귀 단계는6분이다. |
| [Story 2.12](https://github.com/sh-9610/BokriLog/actions/runs/37119393640) | Onboarding Vitest | 비동기 읽기 후 재인증 제목이 아직 없는 상태에서 단정했다. 기존 effect-timing SPEC이 부하 재현과 대기·effect 수명 변경을 기록한다. |
| [Story 2.13 첫 실패](https://github.com/sh-9610/BokriLog/actions/runs/37134688361) | SignedIn Vitest | 401 화면 전환 뒤 초점이 아직 이동하지 않은 상태에서 단정했다. `4330d2f20d2515388b2e8aecdf641e35351c1f66`에서 커밋과 함께 성립해야 할 효과와 조건 대기를 보완했다. |
| [Story 2.13 두 번째 실패](https://github.com/sh-9610/BokriLog/actions/runs/37143229430) | 계획 종료 E2E | Escape로 대화상자를 닫은 뒤 배경이 inert인 동안 초점을 복귀시켜 버튼 초점이 소실됐다. `47438ed54dd44875e5c71ed3c9b8c2e19701b800`에서 inert 해제 커밋 뒤 초점을 복귀시켰다. |
| [Story 2.14](https://github.com/sh-9610/BokriLog/actions/runs/37199365458) | committed-secret 검사 | 시험의 멱등성 문자열15개가 generic-api-key로 탐지됐다. 금융·백엔드 시험 실패가 아니다. 기존 스토리 수정은 해당 문자열 리터럴을 제거했으며 현재 key helper도 값을 런타임에 만든다. |
| [Story 2.15 / PR63](https://github.com/sh-9610/BokriLog/actions/runs/37230539867) | backend FirstSnapshotTest 초기화 | `resetStore()`의 TRUNCATE와 비동기 worker가 서로 다른 순서로 테이블 잠금을 잡아 deadlock2건이 발생했다. 시험 메서드의 순차 실행은 scheduler·virtual worker의 정지를 뜻하지 않는다. 아래 수정으로 해결한다. |

## 반복되는 이유

실패7건 중 UI 비동기·초점 경합4건, 전체 시험 시간 예산1건, 시험 문자열 secret 탐지1건, PostgreSQL 시험 초기화 경합1건이다. 하나의 GitHub 장애나 하나의 아직 남은 오류가 계속 반복됐다는 증거는 없다.

공통적인 검증 공백은 로컬의 정상 속도에서 한 번 통과한 결과를 비동기 수명·순서의 증거로 읽은 점이다. UI 요청 취소/재준비, passive effect와 DOM 커밋, inert 해제와 초점 복귀, DB 초기화와 백그라운드 트랜잭션은 각각 명시적 순서가 필요하다. CI 부하는 그 빈틈이 드러날 가능성을 높인다. CI가 새 오류를 만든다는 결론은 이 로그로 뒷받침되지 않는다.

Story 2.15는 전체 로컬 backend1095개, frontend1164개와 프런트 부하 반복을 통과했지만, 백엔드 초기화와 worker가 겹치는 잠금 순서를 별도 시험으로 검증하지 않아 이번 실패를 놓쳤다. 프런트 부하 반복의 성공을 백엔드 경합 검증의 대용으로 볼 수 없다.

`ci-required` 실패와 `same-origin-integration` skip은 이번 backend 실패의 후속 결과다. 독립된 세 원인으로 세지 않는다.

## PR63 수정과 재발 방지

- `AuthFlowTestBase`의 초기화를 `PostgresStoreReset`으로 감싼다. public 전체 테이블을 같은 트랜잭션에서 `ACCESS EXCLUSIVE NOWAIT`로 먼저 잠근다. 일부 잠금을 잡은 뒤 사용 중인 테이블을 만나면 트랜잭션 전체를 롤백하고20ms 후 재시도한다. 경합 재시도 상한은20초다.
- 선행 잠금의 SQLSTATE55P03만 재시도한다. cleanup 시작 뒤 오류·SQL 오류·deadlock 자체는 그대로 실패시킨다. 시험 데이터 정리는 하나의 트랜잭션이므로 정리 도중 실패해도 앞선 TRUNCATE가 복구된다. 외부 트랜잭션 안의 초기화는 금지한다.
- PostgreSQL 경합 시험은 늦게 생성된 portfolio를 worker가 먼저 잠근 상태를 강제하고, NOWAIT 실패의 롤백을 latch로 관측한 뒤 worker가 앞선 identity_account를 읽는 것을 확인한다. 고정 sleep이나 실행 속도로 겹침을 추정하지 않는다. cleanup은 실패한 접수에서는0회, 잠금 해제 뒤 성공할 때1회다.
- 정리 오류의 실제 데이터 롤백, 경합 시간 제한, 인터럽트 상태 보존을 회귀 시험에 포함한다. 운영 worker·금융 계산·CI 판정 계약과 재시도 정책은 바꾸지 않는다.

기존 UI 수정은 `spec-frontend-effect-timing.md`의 첫 관측 가드·부하 반복을 유지한다. 앞으로 비동기 변경은 요청 시작/취소/완료·커밋·초점·백그라운드 DB 접근이 겹치는 구체적 순서를 시험에서 강제한다. 시간 예산은 실제 단계별 실행 시간을 보고 조정하며, secret 검사는 작업 파일 검사와 함께 커밋 범위도 확인한다. 실패 실행을 그대로 재실행해 통과시키는 방식은 원인을 제거한 증거가 아니다.

## 검증 결과

- 전체 backend clean build: 69 suite / 1,097개 / 실패·오류·skip0, 6분20초. 로그 `/tmp/pr63-ci-fix-build.log`, XML `/tmp/pr63-ci-fix-build-xml/`.
- 리뷰 보완 뒤 실제 PostgreSQL 경합·롤백·제한·인터럽트4개와 CI 실패 기존2개: 6/6통과 (`/tmp/pr63-ci-fix-final-targeted.log`).
- 수정 커밋 범위 gitleaks 및 `git diff --check` 확인 뒤 기존 PR에 push한다. 서버의 최종 결과는 [PR63 checks](https://github.com/sh-9610/BokriLog/pull/63/checks)가 소유한다.
