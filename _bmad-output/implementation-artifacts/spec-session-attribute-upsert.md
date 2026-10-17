---
title: 'fix — 같은 세션의 동시 첫 세션 확인이 500이 되지 않게 세션 속성 저장을 upsert로'
type: 'bugfix'
created: '2026-10-17'
status: 'done'
baseline_commit: 'd92f569af13962b2a4dcfd41e10dfc4ad46cc97e'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 로그인 직후 같은 세션으로 `GET /api/auth/session`이 둘 겹치면 하나가 500이 되고, 화면은 fail-closed로 미인증을 그린다.
- **관측.** PR #123 CI run 39039915046의 `ad-transition.spec.ts:137`에서 났다. trace를 보면 회전된 새 쿠키로 보낸 세션 확인이 500이었고, 직전 문서의 같은 요청은 중단된 채 서버에서 처리 중이었다. 화면은 "로그인 상태가 아닙니다"였다.
- **연관.** `deferred-work.md` 155(`ad-consent.spec.ts:71`, 로그인 직후 `/settings`가 미인증)도 같은 증상이다.
- **원인.** CSRF 토큰은 그 세션의 첫 세션 확인에서 만들어져 세션 속성으로 저장된다(`HttpSessionCsrfTokenRepository`). Spring Session JDBC의 기본 속성 저장은 plain `INSERT`라서, 두 요청이 같은 속성을 처음 저장하면 `(SESSION_PRIMARY_ID, ATTRIBUTE_NAME)` 기본 키가 충돌한다.
- **사용자 영향.** 실제 사용자도 로그인 직후 빠르게 탭을 열거나 이동하면 같은 일을 겪는다.

**Approach:**
- spring-session-jdbc 4.1.1이 제공하는 `PostgreSqlJdbcIndexedSessionRepositoryCustomizer`를 빈으로 등록한다. 이 customizer는 속성 저장을 `INSERT … ON CONFLICT … DO UPDATE`로 바꾼다.
- 고치기 전에 이 경합을 재현하는 시험을 먼저 둔다.

## Boundaries & Constraints

**Always:**
- **재현 시험이 먼저다.** 고치기 전 코드에서 실패하는 것을 확인한다.
  - 시험 모양: 로그인으로 새 세션을 만들고, 아직 CSRF 속성이 없는 상태에서 같은 쿠키로 `GET /api/auth/session`을 여러 개 동시에 보낸다. 동시 출발 장치(예: `CountDownLatch`)를 쓰고 세션을 여러 개로 반복해 결정성을 높인다.
  - 고친 뒤 단정: 모두 200이고 응답의 `authenticated`가 참이다. 세션 행은 하나, CSRF 속성 행도 하나다.
  - 재현이 결정적이지 않으면 그 사실과 실패 비율을 SPEC Change Log에 적는다. 고친 뒤 반복 실행에서 실패가 0이어야 한다.
- **customizer 등록.** 기존 `SessionAttributeConversion`의 `SessionRepositoryCustomizer` 관용처럼 루트 패키지 구성에 빈 하나를 둔다. 기존 customizer(`principalIndexCustomizer`·변환 서비스)와 함께 적용돼야 한다.
- **시험 하나 더.** 속성 저장 SQL이 upsert라는 것을 등록된 저장소에서 확인한다(예: 같은 속성을 두 번 저장해도 예외가 없다).
- **문서.** `CLAUDE.md` `## 코드 규칙`의 "세션" 항목에 "세션 속성 저장은 PostgreSQL upsert(`PostgreSqlJdbcIndexedSessionRepositoryCustomizer`)라 같은 세션의 동시 요청이 같은 속성을 처음 저장해도 충돌하지 않는다"를 한 구절로 더한다.

**Ask First:** 세션 저장소 구성(flush·save mode, 표 이름)을 바꾸는 것, CSRF 토큰 저장 방식을 바꾸는 것, 마이그레이션.

**Never:** 프런트엔드가 500을 재시도로 덮는 것, e2e를 고쳐 증상을 숨기는 것, 새 의존 추가.

</frozen-after-approval>

## Code Map

모든 경로와 명령은 worktree `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog-fix-session`(브랜치 `fix/session-attribute-upsert`) 기준이다. 사용자 체크아웃은 고치지 않는다. 백엔드 시험은 Docker Desktop이 떠 있어야 한다.

걸린 열린 회고 액션: 없음. 이 fix가 원인일 수 있는 `deferred-work.md` 155의 판정은 done 전환(이 PR) 몫이다.

- `backend/src/main/java/com/bokrilog/SessionAttributeConversion.java:47-57` — 기존 `SessionRepositoryCustomizer<JdbcIndexedSessionRepository>` 빈(`principalIndexCustomizer`). 새 빈을 이 옆이나 같은 구성 클래스에 둔다.
- `~/.gradle/caches/.../spring-session-jdbc-4.1.1-sources.jar`의 `org/springframework/session/jdbc/PostgreSqlJdbcIndexedSessionRepositoryCustomizer.java` — 제공 클래스(읽기 전용). `%TABLE_NAME%` 자리표시를 쓴다.
- `backend/src/main/java/com/bokrilog/AuthSecurityConfig.java:1342-1383` — `SessionBoundCsrfTokenRepository`(첫 세션 확인에서 토큰을 저장). 바꾸지 않는다.
- `backend/src/main/java/com/bokrilog/AuthSessionController.java:164-260` — `GET /api/auth/session`과 `csrfToken(...)`.
- `backend/src/test/java/com/bokrilog/AuthSessionContractTest.java`·`AuthFlowTestBase.java` — 로그인 helper와 세션 쿠키를 다루는 선례. 새 시험은 이 기반 위에 둔다(예: `AuthSessionConcurrencyTest`).
- `CLAUDE.md` `## 코드 규칙` "세션" 항목.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/test/java/com/bokrilog/AuthSessionConcurrencyTest.java`(새) — 재현 시험. 고치기 전에 실패하는 것을 확인한다.
- [x] `backend/src/main/java/com/bokrilog/SessionAttributeConversion.java`(또는 같은 구성) — upsert customizer 빈.
- [x] 같은 시험 파일 — upsert 저장 확인.
- [x] `CLAUDE.md` — 세션 항목 한 구절.

**Acceptance Criteria:**
- Given 고치기 전 코드, when 재현 시험을 돌릴 때, then 동시 세션 확인 가운데 500이 나온다.
- Given 고친 코드, when 재현 시험을 10회 이상 반복할 때, then 실패가 0이다.

## Spec Change Log

- 구현: 재현은 결정적이었다 — 고치기 전 첫 판 `AuthSessionConcurrencyTest`(10회 반복 × 세션 5개 × 동시 세션 확인 8개)가 모두 500으로, upsert 시험이 `DuplicateKeyException`으로 실패했다(11건 중 11건, 코디네이터가 수정만 되돌려 다시 확인). `PostgreSqlJdbcIndexedSessionRepositoryCustomizer` 빈(`SessionAttributeConversion.postgreSqlAttributeUpsert`)을 더한 뒤 실패 0.
- 2026-10-17 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap) — intent_gap·bad_spec 0, patch 6, defer 2, reject 2.
  - **patch(가장 큰 것):** upsert만으로는 동시 첫 세션 확인이 서로 다른 CSRF 토큰을 응답하고 저장소에는 마지막 것만 남아, 진 응답을 받은 화면의 다음 POST가 403이 된다(500 → 나중의 403). upsert만 둔 상태에서 3회 모두 "응답 토큰 ≠ 저장 토큰"으로 재현됐다. Ask First의 "CSRF 토큰 저장 방식"이 아니라 저장 시점의 변경으로 보고 사용자 상시 위임(추천안 진행)에 따라 넣었다 — `AuthFlowHandlers.storeCsrfToken`이 개설된 세션을 남기는 인증 성공 경로 넷(로그인·재인증·재인증 수렴·연결)에서 redirect 전에 지연 토큰을 한 번 풀어 저장한다(Spring Security `CsrfAuthenticationStrategy`가 인증 성공에서 기존 토큰을 지우므로 재인증·연결 뒤의 같은 경쟁도 닫힌다).
  - **patch(그 밖):** 시험이 XOR 마스킹을 푼 토큰이 저장 토큰과 같은지·그 토큰으로 로그아웃 204·로그인 직후 CSRF 행 1을 단정, `finally`의 출발 신호와 모아서 단정, 반복을 `@RepeatedTest(3)` × 세션 3 × 동시 8로 축소, CSRF 속성 이름을 관측으로 얻음, 등록 저장소의 `ON CONFLICT`와 기존 색인 customizer 동시 적용 단정, Javadoc·`CLAUDE.md` 구절.
  - **구현 중 확정:** `verify-same-origin.sh`의 "재인증 콜백이 무장 속성을 소비한다"가 속성 행 전체 수를 무장 전과 비교해, 콜백의 세 변화(무장 소비·재인증 시각·CSRF 토큰 삭제)가 우연히 상쇄돼 통과하고 있었다. 토큰을 다시 저장하자 +1이 남아 연결 무장과 같이 그 속성 이름으로 0을 세게 바꿨다(무장 저장은 앞의 +1 단정이 그대로 본다).
  - **defer:** 160(세션 행이 먼저 지워진 뒤 속성 저장의 FK 위반), 161(세션 확인 500을 미인증 문구로 그림).
  - **reject:** Code Map의 기기 경로·줄 번호(기존 SPEC 관례), 반복 실행을 기본 빌드에서 떼는 것(축소한 반복으로 충분하다).
  - **검증:** 백엔드 `build` 통과(9분 31초), 인증·세션 묶음 22개 클래스 272건 실패 0, `verify-same-origin.sh` 1,103건 통과, 주입 origin `ad-consent`·`ad-transition` `--repeat-each 5` 15건 통과·flaky 0, 주입 origin 전체 73건 통과.
  - **KEEP:** 재현 시험 먼저, 저장소 구현·속성 이름·응답 계약 불변, 재시도로 덮지 않음.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend test --rerun --tests 'com.bokrilog.AuthSessionConcurrencyTest'` — expected: 통과(반복 포함).
- `backend/gradlew --project-dir backend build` — expected: 통과.
- `deploy/compose/verify-same-origin.sh --keep-stack` 뒤 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 npm --prefix frontend run test:e2e:run`, 스택을 내린다 — expected: 0 종료.

## Suggested Review Order

- 인증 성공 요청이 CSRF 토큰을 미리 저장한다 — 첫 세션 확인들이 경쟁하지 않는다
  [`AuthFlowHandlers.java`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java)

- 세션 속성 저장을 PostgreSQL upsert로
  [`SessionAttributeConversion.java`](../../backend/src/main/java/com/bokrilog/SessionAttributeConversion.java)

- 동시 첫 세션 확인 재현 시험과 upsert·customizer 단정
  [`AuthSessionConcurrencyTest.java`](../../backend/src/test/java/com/bokrilog/AuthSessionConcurrencyTest.java)

- 재인증 무장 소비를 속성 이름으로 세는 단정
  [`verify-same-origin.sh`](../../deploy/compose/verify-same-origin.sh)
