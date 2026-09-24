---
title: 'Story 1.9 — 민감 작업에 5분·일회성 재인증 허가 적용하기'
type: 'feature'
created: '2026-09-24'
status: 'done'
review_loop_iteration: 0
baseline_commit: '7ba6363ab0c895083bdf8be96c6d767a492d0591'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-8-reauthentication-session-rotation.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-3-identity-ownership.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Story 1.8이 재인증 시각을 세션 속성 하나로 남겼지만 그것을 읽는 코드가 아직 없다. "최근에 본인을 확인했다"가 곧 "이 작업을 지금 한 번 실행해도 된다"로 바뀌는 자리가 비어 있고, 그래서 1.10·1.11의 연결·해제가 기댈 허가 계약이 없다. 세션 속성만으로는 그 계약을 만들 수 없다 — 업무 변경과 같은 트랜잭션에서 커밋되지 않고, 동시에 도착한 두 요청을 원자적으로 가를 수도 없다.

**Approach:** 서버가 보관하는 불투명 허가 행 하나를 만들고, 소비를 **조건부 UPDATE 한 문장**으로 표현한다. 그 문장이 귀속·만료·미소비를 한 번에 판정하므로 판정과 소비 사이에 창이 없다. 업무 변경은 같은 트랜잭션 안에서 호출자가 실행하고, 같은 `operation_id` 재전송은 저장된 결과를 그대로 돌려준다. HTTP 표면은 만들지 않는다.

## Boundaries & Constraints

**Always:**
- 허가는 **user·session·command·target·canonical confirmation hash** 다섯에 귀속하고 정확히 한 번 소비된다(`SECURITY-ACTION-1.0`). 다섯 중 하나라도 어긋나면 소비되지 않는다.
- **시각의 권위는 PostgreSQL 하나다.** 발급이 `expires_at`을 절대 시각으로 못 박고, 만료 판정은 소비 UPDATE의 술어(`now() < expires_at`)가 한다. 애플리케이션 시계로 만료를 판정하지 않는다 — 인스턴스마다 다르고 뒤로 갈 수 있으며, 판정과 소비가 두 문장으로 갈리면 그 사이가 곧 경합 창이다.
- 수명은 발급 시각부터 **5분 미만**이다. `now() >= expires_at`이면 만료다. 이 값은 1.8의 `FRESHNESS_WINDOW`·`ARMED_LIFETIME`과 **같은 숫자지만 다른 상수**다 — 그 둘은 각각 OIDC `max_age` 프로토콜 값과 OAuth 왕복 시간에 묶여 있고, 여기 값은 확인 화면과 실행 사이의 인간 시간이다.
- 소비와 업무 변경은 **같은 트랜잭션 경계**에서 커밋된다. 업무 변경이 실패하면 소비도 롤백되어 "허가만 소비된 성공"이 남지 않는다.
- 같은 `operation_id` 재전송은 저장된 **같은 결과**를 돌려주고 업무 변경을 다시 실행하지 않는다. 다른 `operation_id`는 새 요청이며 이미 소비된 허가로는 통과하지 못한다.
- 브라우저에 나가는 것은 **의미를 해석할 수 없는 참조 하나**다. 사용자·작업·대상·시각을 그 값에서 복원할 수 없다.
- 소비는 `identity` 공개 API로 삭제 상태를 다시 본다(`AccountAccessApi.decide(user, user, SENSITIVE)`). 조회 결과와 무관하게 소비 UPDATE가 귀속을 다시 판정한다.
- 확인 화면 조회는 허가를 소비하지 않는다. 소비는 실행 요청 하나에서만 일어난다.
- 거부는 하나의 값이다. 만료·이미 사용됨·귀속 불일치·삭제 상태가 호출자에게 구분되지 않는다.

**Ask First:**
- 루트 패키지가 **자기 소유 테이블을 갖는 것**. 지금까지 모든 테이블은 `identity` 소유이고 루트 패키지는 데이터베이스에 닿지 않았다. 이 스토리가 그 선을 처음 넘는다(대안은 새 모듈 신설이며 그것은 `ARCHITECTURE-SPINE`의 모듈 목록을 바꾸는 일이다).
- 허가 테이블이 `identity_account(user_id)`를 `ON DELETE CASCADE`로 참조하는 것. 삭제 경로가 새 테이블을 알지 않아도 되는 대신 스키마에 루트 → `identity` 방향 의존이 하나 생긴다.
- 5분·허가 참조 길이·`operation_id` 형식 같은 정책 값의 변경.

**Never:**
- **어떤 HTTP 표면도 만들지 않는다.** 발급·소비·조회 엔드포인트를 두지 않는다. 실행 가능한 민감 command가 아직 없으므로 지금 만드는 경로는 소비자 없는 표면이 되고, 시험용 상태 변경을 외부 API로 여는 것은 `SessionGuard`가 이미 거절한 형태다.
- 실제 로그인 수단 연결·해제(1.10·1.11), Bybit 변경(Epic 2), 계정 삭제의 업무 상태 변경(Epic 5).
- 허가를 소비하는 쪽에서 세션을 회전시키는 코드. 회전은 HTTP 요청 문맥을 요구하고 이 스토리에는 그 문맥이 없다 — 포트가 **회전 의무를 결과 타입으로 알리고** 실제 회전은 첫 소비자 스토리가 자기 엔드포인트에서 배선한다.
- `command`·`target` 값의 닫힌 집합을 여기서 정하는 것. 포트는 그 문자열을 해석하지 않고 소비자가 소유한다.
- 새 의존 추가. `spring-boot-starter-jdbc`·Flyway·Testcontainers가 이미 있고 그것으로 충분하다.
- 스케줄러·배치. 만료된 행은 발급 경로가 기회적으로 쓸어 낸다.
- 1.8이 만든 세션 속성(`bokrilog.auth.reauthenticated-at`)의 의미 변경. 이 스토리는 그것을 **읽기만** 한다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| 발급 | 재인증 시각이 신선한 세션 + command·target·confirmation | 불투명 참조 하나, 행 1개, `expires_at = now() + 5분` | N/A |
| 재인증 없음·오래됨 | 세션에 재인증 시각이 없거나 5분을 넘김 | 발급하지 않음 | 거부 하나 |
| 정상 소비 | 다섯 귀속이 모두 일치하고 미소비·미만료 | 업무 변경과 함께 1회 커밋, 결과 저장, 회전 의무를 담은 성공 | N/A |
| 만료 | `now() >= expires_at` | 소비 0행, 업무 변경 미실행 | 거부 하나 |
| 이미 사용됨 | `consumed_at IS NOT NULL` | 소비 0행 | 거부 하나 |
| 귀속 불일치 | user·session·command·target·hash 중 하나가 다름 | 소비 0행 | 거부 하나(어느 축인지 알려 주지 않음) |
| 업무 변경 실패 | 소비 UPDATE 성공 뒤 호출자 작업이 예외 | 트랜잭션 롤백, 허가는 **미소비로 남는다** | 예외를 호출자에게 전파 |
| 같은 `operation_id` 재전송 | 이미 성공한 실행과 같은 값 | 저장된 같은 결과, 업무 변경 재실행 없음 | N/A |
| 다른 `operation_id` 재전송 | 같은 허가, 다른 값 | 소비 0행(이미 소비됨) | 거부 하나 |
| 동시 소비 | 같은 허가로 두 요청이 동시에 | 정확히 하나만 승인, 나머지는 상태 변경 없이 거부 | 거부 하나 |
| 확인 화면 조회 | 허가를 읽기만 함 | `consumed_at` 불변 | N/A |
| 로그아웃·만료·세션 교체 | 그 세션이 사라지거나 ID가 바뀜 | 그 session에 귀속된 허가는 소비 불가 | 거부 하나 |
| 의심 세션 표시 | `reauth_required` 표시된 세션 | 발급·소비 모두 거부 | 거부 하나 |
| 삭제 상태 전환 | `DELETION_IN_PROGRESS`로 전환 | 소비 거부 | `AccessDecision.DENIED` 하나 |
| 계정 payload 삭제 | `erasePayload` 실행 | 그 계정의 허가 행이 함께 사라진다 | N/A |

</frozen-after-approval>

## Code Map

**결정을 정한 조사 사실 (2026-09-24 실측)**

- **루트 패키지는 자기 테이블이 없다.** `AuthSessionService.java:20-22`가 "`identity` 공개 API만 쓰고 저장소에 닿지 않는다"를 명시하고, 1.8의 `ReauthenticationFlow.java:60-63`은 새 마이그레이션을 피하려고 상태를 세션 속성에 두었다. DB에 닿는 루트 클래스는 `SchemaValidationConfig`(전략만 정하고 SQL은 없다) 하나다. 이 스토리가 그 선을 넘는다.
- **조건부 UPDATE로 경합을 가르는 관용이 이미 있다.** `identity/internal/IdentityAccountRepository.java:142-149`·`:157-164`의 `UPDATE ... WHERE user_id=:id AND state='이전상태'`가 영향 행 수 0/1로 전이를 가른다. `insertSocialLinkIfActive`(`:94-107`)도 같은 계열이다. 소비 UPDATE가 이 형태를 그대로 쓴다.
- **시계 seam이 없다.** 코드베이스 전체에 `Clock` 빈이 없고 운영 코드는 `Instant.now()`를 직접 부른다(`ReauthenticationFlow.java:120,136`·`SessionGuard.java:142`). 그래서 만료를 애플리케이션이 판정하면 시험이 시간을 조작할 방법이 없다. 반대로 시각 컬럼은 전부 `timestamptz NOT NULL DEFAULT now()`이고(`V1__identity_account.sql:20-21,30,41`) 시험은 SQL로 시각을 옮기는 관용을 이미 갖는다(`AuthFlowTestBase.ageLastAccess:583-595`). **판정을 DB로 옮기면 seam이 공짜로 생긴다.**
- **영속화는 `JdbcClient`다.** `IdentityAccountRepository.java:5,21`. JPA는 없고 `spring-boot-starter-jdbc`가 이미 운영 classpath에 있다.
- **트랜잭션 경계는 둘 다 쓰인다.** `identity.internal` 서비스 셋은 `@Transactional`, `AuthSessionService.java:27-29`는 재시도마다 새 트랜잭션이 필요해 `TransactionTemplate`을 쓴다. 이 스토리의 소비는 **호출자 작업을 감싸야** 하므로 `TransactionTemplate`이다.
- **1.8이 남긴 것을 읽는 코드가 아직 없다.** `ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE`(`:53`)에 `Instant`가 쓰이고(`AuthFlowHandlers.java:554`) 읽는 곳은 0건이다. `recordAuthentication`(`:187`)의 짝인 조회가 이 스토리에서 처음 생긴다.
- **세션 회전은 HTTP 문맥에서만 일어난다.** 유일한 배선이 `AuthSecurityConfig.java:819-820`이고 실행은 Spring Security 필터가 성공 핸들러 직전에 한다. 요청 밖에서 회전시키는 경로는 없다.
- **`SessionGuard`의 네 메서드는 아직 아무도 부르지 않는다**(`:159`·`:168`·`:180`·`:189`, 시험에서만 호출). 의심 세션 판정을 이 스토리가 처음 소비한다.

**새로 만드는 자리**

- `backend/src/main/resources/db/migration/V3__action_grant.sql` -- 허가 테이블 하나. 컬럼은 `grant_id`(PK, 불투명 참조), `user_id`(FK → `identity_account` ON DELETE CASCADE), `session_id`, `command`, `target`, `confirmation_hash`, `issued_at`·`expires_at`(`timestamptz`), `consumed_at`(nullable), `operation_id`(nullable, UNIQUE), `result`(nullable). `V1`의 주석·명명·CHECK 관용을 그대로 따른다(길이 CHECK는 `octet_length(...) BETWEEN 1 AND N`).
- `backend/src/main/java/com/bokrilog/ActionGrant.java` -- 허가의 값 표현과 정책 상수. 수명 5분, 참조 생성, `canonicalConfirmationHash(command, target, confirmation)` 하나. 소비자마다 다른 정규화를 발명하지 않게 이 메서드가 유일한 생산자다.
- `backend/src/main/java/com/bokrilog/ActionGrantRepository.java` -- `JdbcClient` SQL 넷: 발급 INSERT, 소비 조건부 UPDATE, `operation_id` 조회, 만료 행 sweep. `IdentityAccountRepository`의 모양을 따른다.
- `backend/src/main/java/com/bokrilog/ActionGrantService.java` -- 공통 허가 포트. `issue(...)`와 `consume(request, operation)` 둘. `TransactionTemplate`으로 소비 UPDATE와 호출자 작업을 한 경계에 넣는다. `AccountAccessApi`·`SessionGuard`·`ReauthenticationFlow`를 읽는 자리이며 `@ConditionalOnWebApplication(type = SERVLET)`이다(`AuthSessionService`와 같은 이유).

**기존 파일에서 넓힐 자리**

- `ReauthenticationFlow.java:53`·`:187` -- `AUTHENTICATED_AT_ATTRIBUTE`의 조회(`lastAuthenticatedAt(HttpSession)`)를 더한다. 발급 게이트가 그 값을 본다. 기록 쪽은 무변경이다.
- `SessionGuard.java:159-189` -- 무변경. 발급·소비가 `sessionCountOf`/판정을 부르는 쪽이다.
- `AuthFlowTestBase.java:583-614`·`:644-647` -- 시각을 옮기는 헬퍼와 세션 속성 조회가 이미 있다. 허가 행의 시각을 옮기는 헬퍼 하나를 같은 자리에 더한다(새 `@DynamicPropertySource`를 만들지 않는다).
- 무변경: `identity/**`(공개 API·내부 코드·기존 마이그레이션), `deploy/nginx/`, `frontend/**`, `AuthSecurityConfig`·`AuthSessionController`·`AuthFlowHandlers`의 경로·필터.

**시험을 더할 자리**

- `backend/src/test/java/com/bokrilog/ActionGrantTest.java` -- 발급 게이트, 다섯 귀속, 만료 경계(직후·5분 직전·정확히 5분·이후·시각 역행), 확인 조회 무소비, 로그아웃·세션 교체·의심 세션·삭제 전환 무효화, `operation_id` 재전송. 시각은 `AuthFlowTestBase`의 관용대로 SQL로 옮긴다.
- `backend/src/test/java/com/bokrilog/ActionGrantTransactionTest.java` -- 업무 변경 실패 시 허가가 미소비로 남는지, 같은·다른 `operation_id`, 응답 유실 재전송. 내부 시험 소비자는 이 파일이 소유하고 HTTP로 노출하지 않는다.
- `backend/src/test/java/com/bokrilog/ActionGrantConcurrencyTest.java` -- 같은 허가의 동시 소비에서 정확히 하나만 승인. `identity/AccountConcurrencyTest.java:93-115`의 `CyclicBarrier` + `ExecutorService` 형태와 `@TestPropertySource` 풀 크기 지정(`:54`)을 따른다.

## Tasks & Acceptance

**Execution:**

- [x] `backend/src/main/resources/db/migration/V3__action_grant.sql` -- 허가 테이블 하나를 `V1`의 주석·명명·제약 관용대로 만든다. `expires_at`은 발급이 못 박는 절대 시각이고, `operation_id`에 UNIQUE를 둔다(멱등의 권위가 인덱스 하나다). `user_id`는 `identity_account`를 `ON DELETE CASCADE`로 참조한다. expand 우선이며 기존 파일을 고치지 않는다. -- AC-2·AC-5. 삭제 경로가 새 테이블을 알지 않아도 되는 대가로 스키마 의존 하나를 진다(Ask First 항목).
- [x] `backend/src/main/java/com/bokrilog/ActionGrant.java` -- 수명 상수, 불투명 참조 생성, `canonicalConfirmationHash`를 담는다. 1.8의 `FRESHNESS_WINDOW`·`ARMED_LIFETIME`을 재사용하지 않고 이름과 근거가 다른 상수를 새로 선언한다. -- 같은 숫자를 공유하면 OIDC 프로토콜 값이 바뀌는 날 허가 정책이 조용히 따라 움직인다.
- [x] `backend/src/main/java/com/bokrilog/ActionGrantRepository.java` -- `JdbcClient` SQL 넷. 소비는 **조건부 UPDATE 한 문장**이며 술어에 다섯 귀속·`consumed_at IS NULL`·`now() < expires_at`을 모두 넣고 영향 행 수로 판정한다. -- 판정과 소비가 두 문장으로 갈리면 그 사이가 곧 동시 소비의 창이다.
- [x] `backend/src/main/java/com/bokrilog/ActionGrantService.java` -- 포트 둘. `issue`는 재인증 신선도·의심 세션·삭제 상태를 보고 발급하며 만료 행을 기회적으로 쓸어 낸다. `consume`은 `TransactionTemplate` 안에서 조건부 UPDATE → 호출자 작업 → 결과 저장을 하고, 같은 `operation_id`가 이미 있으면 저장된 결과를 그대로 돌려준다. 결과 타입이 **세션 회전 의무**를 이름으로 드러낸다. -- AC-1·AC-3·AC-4. 회전 자체는 HTTP 문맥이 없어 여기서 하지 않는다.
- [x] `backend/src/main/java/com/bokrilog/ReauthenticationFlow.java` -- `AUTHENTICATED_AT_ATTRIBUTE`의 조회 하나를 더한다. 속성 이름과 타입은 1.8이 정한 그대로다. -- 기록만 있고 조회가 없던 짝을 맞춘다.
- [x] `backend/src/test/java/com/bokrilog/AuthFlowTestBase.java` -- 허가 행의 `issued_at`·`expires_at`을 옮기는 헬퍼와 허가 행 수·소비 여부 조회를 `ageLastAccess`와 같은 자리에 더한다. 새 `@DynamicPropertySource`를 만들지 않는다. -- 컨텍스트 캐시를 가르지 않는 것이 CI wall-clock의 전제다.
- [x] `backend/src/test/java/com/bokrilog/ActionGrantTest.java` -- 발급 게이트·다섯 귀속·만료 네 경계와 시각 역행·확인 조회 무소비·무효화 네 축을 단정한다. -- AC-1·AC-2·AC-4·AC-5의 단일 소유자.
- [x] `backend/src/test/java/com/bokrilog/ActionGrantTransactionTest.java` -- 내부 시험 소비자로 소비 성공, 업무 실패 롤백(허가가 미소비로 남는다), 같은·다른 `operation_id` 재전송을 단정한다. -- AC-3. 이 소비자는 HTTP로 노출되지 않는다.
- [x] `backend/src/test/java/com/bokrilog/ActionGrantConcurrencyTest.java` -- 같은 허가로 동시에 들어온 요청 중 정확히 하나만 승인되고 나머지가 상태를 바꾸지 않음을 단정한다. `AccountConcurrencyTest`의 형태를 따른다. -- AC-4. 조건부 UPDATE가 실제로 원자적인지 보는 유일한 층이다.
- [x] `DEVELOPMENT.md` + `_bmad-output/implementation-artifacts/epic-1-context.md` -- 허가 포트·새 테이블·시각 권위가 DB라는 사실과 `## Goal`의 다음 착수 지점(1.10)을 적는다. `CLAUDE.md`는 건드리지 않는다(머지 뒤 별도 docs PR이 소유한다). -- 같은 사실이 두 곳에서 갈리지 않게 한다.

**Acceptance Criteria:**

- Given `develop`의 Story 1.1~1.8 계약, when 이 스토리의 변경을 얹으면, then `GET /api/nope` 404·`POST /api/status` 405·`POST /api/auth/session` 403·반복 로그아웃 403·오류 본문 전문 동등성·보안 헤더·캐시 헤더·세 제공자 로그인 왕복·재인증 왕복이 모두 그대로 통과한다.
- Given 이 스토리가 끝난 저장소, when `backend/src/main/java/com/bokrilog`에서 `@RestController`·`@GetMapping`·`@PostMapping`을 검색하면, then 결과가 Story 1.8과 같다(새 HTTP 표면 0건).
- Given `identity` 모듈, when 이 스토리가 끝나면, then 공개 API 시그니처·내부 코드·기존 마이그레이션이 바뀌지 않았고 `ModuleBoundaryTest`가 경계와 미노출을 그대로 단정한다.
- Given 기동, when API 프로세스가 뜨면, then `Flyway::validate`만 돌고 `V3`는 `migrate` 프로필에서만 적용된다(`SchemaValidationConfig`의 계약이 그대로다).
- Given 허가가 발급된 계정, when `erasePayload`가 실행되면, then 그 계정의 허가 행이 함께 사라지고 다른 사용자의 행은 남는다.
- Given 소비가 거부되는 모든 경우, when 호출자가 그 결과를 관찰하면, then 만료·이미 사용됨·귀속 불일치·삭제 상태가 서로 구분되지 않는다.
- Given 불투명 참조, when 그 값을 검사하면, then 사용자·작업·대상·시각을 복원할 수 없고 응답·로그 어디에도 `session_id`·`confirmation_hash`가 남지 않는다.

## Spec Change Log

- 2026-09-24 첫 구현. frozen 블록은 바꾸지 않았고 설계 결정도 뒤집지 않았다. 아래 아홉은 구현 중에 확정한 것이고 마지막 둘은 변이 확인과 전량 검증 결과다.

  - **재인증 신선도 판정도 데이터베이스가 한다.** Code Map은 만료만 DB로 옮겼지만 frozen 블록의 "시각의 권위는 PostgreSQL 하나다"를 발급 게이트에도 적용했다. 세션이 든 재인증 시각을 자바가 `Instant.now()`와 비교하면 그 값을 기록한 인스턴스와 지금 판정하는 인스턴스의 시계 차이가 그대로 정책 완화가 된다. 그래서 발급이 **조건부 INSERT 한 문장**이고(`WHERE :reauthenticatedAt > now() - 창`) 그 문장이 `expires_at`도 함께 못 박는다. 결과적으로 `ActionGrant`의 두 수명 상수는 데이터베이스에 건네는 숫자일 뿐 자바가 비교하는 값이 아니다.
  - **정책 상수가 둘이다(`LIFETIME`·`REAUTHENTICATION_WINDOW`).** I/O 표가 가른 두 게이트가 서로 다른 축이기 때문이다 — 하나는 본인 확인에서 허가까지(`SECURITY-ACTION-1.0`의 "재인증 후 5분 이내 발급"), 다른 하나는 허가에서 실행까지(`expires_at = now() + 5분`). 숫자가 같다는 이유로 하나로 합치면 한쪽만 바꾸는 날 다른 쪽이 조용히 따라간다. Story 1.8의 두 상수는 어느 쪽도 재사용하지 않았고, 세 값 모두 `ActionGrantTest.정책_값은_SPEC이_소유한다`가 직접 고정한다.
  - **포트가 세션 id가 아니라 `HttpSession`을 받는다.** 귀속의 세션 축을 클라이언트가 보낸 문자열에서 읽으면 그 값이 곧 귀속을 우회하는 입력이 된다. 그래서 `issue`의 첫 인자가 세션이고 `consume`의 요청 record도 세션을 성분으로 든다 — record가 값 타입이 아닌 성분을 드는 유일한 자리이며, 그 대가로 세 가지(세션 id·재인증 시각·의심 세션 표시)의 출처가 하나로 고정된다. **로그아웃으로 사라진 세션의 무효화는 이 구조가 성립시킨다** — 그 세션에는 건넬 `HttpSession`이 없다. 시험은 그것을 "로그아웃 뒤 새 세션"과 "회전 뒤 새 세션"으로 관측한다.
  - **세션 판정을 `SessionGuard.evaluate` 하나가 한다.** 의심 세션 표시와 절대 수명 초과가 같은 하나의 거부로 접히고, 두 상태를 허가 쪽에서 다시 정의하지 않는다. `SessionGuard`는 무변경이다.
  - **소비 UPDATE가 `operation_id`를 함께 적는다.** 결과 저장 시점으로 미루면 서로 다른 허가에 같은 `operation_id`를 들고 온 두 요청이 **모두 업무 변경을 실행한 뒤에야** 유일 인덱스에 부딪힌다. 소비 문장에서 적으면 충돌이 업무 변경보다 앞에서 나고, 그 `DuplicateKeyException`이 곧 "이미 성공한 실행"이다.
  - **저장된 결과 조회를 사용자로 좁혔다.** 좁히지 않으면 남의 `operation_id`를 찍어 맞힌 요청이 그 실행 결과를 돌려받는다. 좁힌 결과 그 경우는 거부 하나로 수렴한다(`ActionGrantTransactionTest.남의_operation_id로는_저장된_결과를_얻을_수_없다`).
  - **sweep은 미소비 행만 지운다.** 소비된 행의 `operation_id`와 `result`가 멱등 재전송의 유일한 근거이고, 지우면 응답을 잃은 클라이언트의 재전송이 저장된 결과 대신 거부를 받는다. 남는 행의 정리는 계정 삭제의 `ON DELETE CASCADE`가 맡는다 — **소비된 허가의 장기 보존 상한은 이 스토리가 정하지 않았고** 그것을 정할 자리는 실제 소비자가 생기는 스토리다.
  - **저장소 SQL이 넷이 아니라 다섯이다.** Code Map의 넷에 결과 저장 UPDATE가 하나 더 붙는다. 결과는 호출자 작업이 끝나야 알 수 있어 소비 문장에 합칠 수 없고, 소비 문장 앞으로 옮기면 업무 변경이 소비보다 먼저 실행된다.
  - **세 시험 모두 `AuthFlowTestBase`를 상속한다.** 새 `@DynamicPropertySource`도 `@TestPropertySource`도 두지 않아 컨텍스트가 갈라지지 않는다 — `AccountConcurrencyTest`가 풀 크기를 지정한 것과 달리 여기 참여 연결은 잠금 트랜잭션 1개와 소비 2개뿐이라 Hikari 기본값 안이다. 세션은 `spring-test`의 `MockHttpSession`으로 만든다(새 의존 없음). 이 포트에는 HTTP 표면이 없어 요청 문맥이 없고 Spring Session의 `HttpSession` 어댑터는 공개 타입이 아니기 때문이며, 세션 id를 실제 로그인이 만든 값으로 주는 축에서는 그 값을 그대로 넣어 저장소의 사실과 맞춘다. 내부 시험 소비자의 업무 변경은 `identity` 공개 API의 매핑 연결 하나다 — 실제 쓰기여야 롤백을 행 수로 관측할 수 있고, 그 API가 호출자 트랜잭션에 참여한다는 것은 `AccountLinkDenialTest`가 이미 고정한 계약이다.

  - **변이 확인.** 새 축이 실제로 관측하는지 직접 확인했다 — 소비 UPDATE의 `now() < expires_at`을 지우고(3건 실패), `session_id` 술어를 항진명제로 바꾸고(3건 실패), 발급의 재인증 신선도 술어를 지우고(1건 실패), 세션 판정을 항상 참으로 바꾸고(2건 실패), 트랜잭션 경계를 없애고(1건 실패), `consumed_at IS NULL` 술어를 지운 뒤(4건 실패 — 동시 소비 포함) 각각 원복했다.
  - **검증(전량 실측, Docker 있음).** 백엔드 `clean build` **263** 시험 통과·경고 0건(`-Werror`)·jar 생성(1.8의 233에서 30 증가), 프런트엔드 전 검사(`ci`·typecheck·ESLint·stylelint·`format:check`·build·Vitest **193**), `bash .github/scripts/test-ci-scripts.sh` 147/147. 수동 검사도 통과했다 — `backend/src/main/java/com/bokrilog`의 `@RestController`·`@GetMapping`·`@PostMapping` 목록이 Story 1.8과 같고(새 HTTP 표면 0건), `ActionGrant*.java`에 1.8 상수 이름 0건, `Instant.now()`는 만료 판정에 쓰이지 않으며, `git diff --stat develop -- identity/ frontend/ deploy/`가 변경 0건이다.

- 2026-09-24 적대적 리뷰 세 층의 `patch` 판정 18건. frozen 블록은 바꾸지 않았고 설계 재작업도 없다. 무게순으로, 앞의 열은 계약이 실제로 성립하지 않던 자리이고 뒤의 여섯은 검증이 아무것도 관측하지 않던 자리다.

  - **멱등 키가 요청 지문에 묶이지 않았다.** `replay`가 `operation_id`와 `user_id`만 보고 저장된 결과를 성공으로 돌려줬다. 같은 사용자가 `link-provider`에 쓴 값을 뒤에 `unlink-provider` 요청에 재사용하면 해제는 실행되지 않은 채 연결의 결과가 "이미 성공한 실행"으로 돌아간다. 저장된 결과 조회의 술어를 허가 참조·사용자·`command`·`target`·확인 해시로 넓혔다. **세션만은 뺐다** — 소비 성공이 세션 회전을 요구하므로(`SECURITY-ACTION-1.0`) 응답을 잃은 재전송은 반드시 다른 세션에서 오고, 세션을 넣으면 멱등 경로가 실제 흐름에서 한 번도 성립하지 않는다.
  - **`catch (DuplicateKeyException)`이 호출자 업무 변경의 예외까지 삼켰다.** 그 catch가 트랜잭션 전체를 감싸고 있어 1.10의 `linkSocialIdentity`(유일 제약 위반이 정상 경로인 대표 사례)가 던지는 예외가 "이미 성공한 실행"으로 접혔다. 충돌을 <b>일으킨 문장 자리에서</b> 사설 타입(`OperationIdConflict`)으로 감싸 우리 인덱스의 것만 가려내고 나머지는 그대로 전파한다. 업무 변경이 그 예외를 던지는 축을 시험이 고정한다.
  - **`operation_id` 유일 인덱스가 전역이라 사용자 사이에 선점이 성립했다.** 읽는 쪽은 이미 사용자로 좁혀 있었는데 인덱스에는 그 축이 없었다 — 1.10이 `link-naver-1` 같은 추측 가능한 값을 쓰면 먼저 쓴 쪽이 다른 사용자의 그 값을 **영구히** 막는다. `(user_id, operation_id)` 부분 유일 인덱스로 바꿨다(`V3`는 미머지라 파일을 고쳤다). 두 사용자가 같은 값으로 각자 성공하는 축을 더했고, "남의 결과를 얻을 수 없다"는 축은 그 새 계약으로 다시 썼다.
  - **발급 신선도 술어에 미래 상한이 없었다.** 재인증 시각만은 애플리케이션이 쓰는 값이라(`AuthFlowHandlers`) 앞선 시계가 기록한 값이 과거 쪽 술어 하나로는 창을 영원히 만족한다. 상한(`REAUTHENTICATION_FUTURE_SKEW`, 60초)을 술어에 더해 창을 양쪽에서 닫고 미래 두 축을 시험한다. 1.8의 skew와 같은 크기지만 다른 상수다 — 그쪽은 제공자 시계를 향하고 이쪽은 우리 인스턴스들 사이를 향한다.
  - **정규화가 필드마다 엇갈렸다.** 해시는 `command`·`target`을 정규화해 넣는데 컬럼에는 원문이 저장·비교돼, `" link-provider"`는 해시는 맞고 컬럼은 어긋나 영영 소비할 수 없는 허가가 됐다. 정규화를 `ActionGrant.normalize` 하나로 뽑아 저장과 해시 양쪽이 같은 값을 쓰게 했다.
  - **재인증 한 번이 같은 작업의 허가를 여럿 만들었다.** 세션 속성은 발급 뒤에도 남으므로 창 안에서 반복 발급이 가능하고 각각이 다시 5분을 산다. 발급이 같은 `(user_id, command, target)`의 미소비 허가를 먼저 무효화한다. 무효화와 발급을 한 트랜잭션에 두어, 발급이 서지 않으면 앞의 허가도 그대로 남는다 — 그러지 않으면 재인증이 오래된 요청 하나가 살아 있는 허가를 없애는 도구가 된다.
  - **포트가 `userId`와 세션 주체를 대조하지 않았다.** 둘 다 호출자가 건네는 값이라 대조가 없으면 "세션이 유일한 `user_id` 권위"가 문장으로만 남는다. 세션에 저장된 `PreAuthenticatedAuthenticationToken`의 이름과 대조하고(`AuthFlowHandlers.carriesEstablishedPrincipal`과 같은 관용), 주체 없는 세션과 다른 사용자 축을 시험한다.
  - **무효화된 세션과 `null` 세션이 예외로 샜다.** 세션을 읽는 자리를 `SessionFacts` 하나로 모아 `IllegalStateException`과 `null`을 같은 거부로 접었다. 흩어져 있으면 "구분되지 않는 거부 하나"가 새는 자리가 그만큼 늘어난다.
  - **`erasePayload`와 발급 INSERT의 경합이 외래키 위반으로 샜다.** 삭제 판정은 생명주기 행을 보고 외래키는 payload 행을 보므로 그 사이에 삭제가 커밋되면 위반이 그대로 올라왔다. 같은 거부 하나로 접고, 두 행을 갈라 그 상태를 결정적으로 만든 축을 시험한다.
  - **호출자가 이미 트랜잭션을 연 경우의 계약이 없었다.** 참여하면 멱등 충돌이 호출자의 트랜잭션을 rollback-only로 만들어 커밋이 `UnexpectedRollbackException`으로 끝난다. `REQUIRES_NEW`는 AC-3 자체를 깨므로 답이 아니다. **포트가 경계를 소유한다**를 계약으로 적고 기존 트랜잭션을 감지하면 거절한다(거부가 아니라 예외 — 배선 실수다). 재전송 조회는 이미 트랜잭션 밖이라 그대로 뒀다.
  - **`MAX_RESULT_BYTES`를 아무도 강제하지 않았다.** 상한을 넘는 결과는 업무 변경이 **이미 실행된 뒤** CHECK 위반으로 호출자 트랜잭션을 abort시켰다. `isStorableResult` 게이트를 채우고 초과 축을 시험한다.
  - **sweep이 발급을 막을 수 있었고 범위가 전체 테이블이었다.** 예외를 삼키고, 범위를 발급하는 사용자로 좁혔다 — 범위 없는 DELETE를 매 발급마다 돌리면 발급 하나의 비용이 테이블 크기에 비례한다. 남의 만료 행이 보존되는 축을 함께 단정한다.
  - **세션 절대 수명 축이 한 번도 실행되지 않았다.** 모든 시험 세션의 생성 시각이 "지금"이라 `isUsableSession`에서 그 인자를 통째로 버려도 전부 통과했다. 공용 헬퍼가 생성 시각을 인자로 받게 하고 수명 너머의 세션으로 발급·소비 두 축을 단정한다.
  - **값 거부 게이트(`isAcceptable`)를 실행하는 시험이 0건이었다.** 상한 초과 `command`·`target`·`confirmation`과 빈 `operationId`·`grantReference`가 정의된 거부로 끝남을 단정하고, **`ActionGrant`의 `MAX_*` 상수와 `V3`의 CHECK 숫자가 같다**는 등가 단정을 마이그레이션 파일을 읽어 확인한다(두 값이 갈리면 값으로 거부하던 입력이 호출자 트랜잭션을 지운다).
  - **발급 창 경계가 시험되지 않았다.** 좌표를 `databaseNow()`에서 가져와 창 안·정확히 5분·창 밖·skew 안의 미래·skew 밖의 미래 다섯을 고정한다. 다만 **`>`와 `>=`는 구분되지 않는다** — 비교 대상 하나가 `now()`라 좌표를 잡는 순간과 판정하는 순간 사이에 경계가 움직이고, 두 술어가 갈리는 지점은 그 순간 하나뿐이라 밖에서 맞출 수 없다(변이로 확인했다). 만료 경계는 좌표가 행에 저장돼 움직이지 않으므로 그 한 점이 실제로 고정된다.
  - **같은 `operation_id`의 동시 도착이 없었다.** 동시성 시험을 셋으로 늘렸다 — 같은 허가·다른 `operation_id`(하나만 승인), 같은 허가·같은 `operation_id`(둘 다 같은 결과, 업무는 한 번), 다른 허가·같은 `operation_id`(인덱스가 하나를 거절하고 진 쪽은 업무를 실행하지 못한다).
  - **로그아웃 무효화 시험이 자명한 명제를 봤다.** 지금은 그 허가에 남은 두 경로를 모두 막는다 — 다시 로그인한 새 세션(귀속의 세션 축)과 죽은 id를 주장하는 세션(주체 대조). 그 둘 말고는 경로가 없다는 사실(같은 id의 살아 있는 세션은 다시 만들어지지 않는다)이 이 계약의 나머지다.
  - **`storeResult`만 영향 행 수를 버렸다.** 1이 아니면 소리 내어 실패하게 했다. 이 가지는 **구조상 도달하지 않는다** — 바로 앞 UPDATE가 같은 트랜잭션에서 그 행을 잠갔다. 변이로 확인했고, 그럼에도 남기는 이유는 이 클래스에 0행을 성공으로 접는 자리를 하나도 두지 않기 위해서다(주석에 적었다).
  - **구조·문서.** `ActionGrantRepository`에도 `@ConditionalOnWebApplication`을 달았다(루트 패키지의 나머지 빈과 같은 조건). `V3` 머리말에 **`ON DELETE CASCADE`가 `V1`의 `RESTRICT` 관용을 뒤집는 사실과 근거**를 적고, `expires_at > issued_at` CHECK를 더하고, 두 색인을 각자의 술어에 맞춘 부분 색인으로 좁혔다. 시험 픽스처(command·target·확인 문구·세션 생성)는 `AuthFlowTestBase`로 올려 세 파일의 복제를 없앴고, `blockedBackends`는 `action_grant` 잠금을 들고 대기하는 pid만 세도록 좁혔다(질의 문자열로 좁히는 첫 시도는 실패했다 — 대기 중인 백엔드의 `pg_stat_activity.query`가 직전 값으로 남아 있었다).

  - **변이 확인.** 새 축마다 직접 확인했다 — replay 지문을 `operation_id`만으로 되돌리고(3건 실패), 업무 예외를 함께 삼키고(1건), 인덱스를 전역으로 되돌리고(1건), 미래 상한을 지우고(1건), 정규화를 한쪽만 하고(1건), 살아 있는 허가 무효화를 지우고(1건), 주체 대조를 무르고(3건), 무효 세션 catch를 지우고(1건), FK catch를 지우고(1건), 트랜잭션 경계 검사를 무르고(1건), 결과 상한 게이트를 지우고(1건), sweep 범위를 전체로 넓히고(1건), 세션 생성 시각을 "지금"으로 고정하고(1건), `isAcceptable`을 항진명제로 바꾼 뒤(1건) 각각 원복했다. 관측되지 않은 둘은 위에 적은 대로 구조상 도달할 수 없는 자리다(`>` 대 `>=`, `storeResult`의 0행).
  - **검증(전량 실측, Docker 있음).** 백엔드 `clean build` **279** 시험 통과·경고 0건(`-Werror`)·jar 생성, `deploy/compose/verify-same-origin.sh` **583/583 exit 0**, 프런트엔드 전 검사(`ci`·typecheck·ESLint·stylelint·`format:check`·build·Vitest **193**), `bash .github/scripts/test-ci-scripts.sh` 147/147. 수동 검사도 그대로다 — 새 HTTP 표면 0건, `ActionGrant*.java`에 1.8 상수 이름 0건, `Instant.now()`가 만료를 판정하지 않음, `identity/`·기존 마이그레이션·`frontend/`·`deploy/` 변경 0건, 실행 중인 스택 로그에 세션 ID·`confirmation_hash`·허가 참조 0건.

## Design Notes

**만료를 데이터베이스가 판정하는 것이 이 스토리의 중심 결정이다.** 셋을 한꺼번에 해결한다. (1) `now() >= expires_at`을 소비 UPDATE의 술어로 넣으면 판정과 소비 사이에 창이 없어져 동시 소비가 인덱스 하나로 갈린다. (2) 코드베이스에 시계 seam이 없는데 시험은 "정확히 5분"과 "서버 시각 역행"을 봐야 한다 — 시각 컬럼을 SQL로 옮기는 관용(`ageLastAccess`)이 이미 있으므로 판정이 DB에 있으면 seam이 공짜로 생긴다. (3) 인스턴스가 늘어날 때 애플리케이션 시계의 불일치가 보안 판정을 흔들지 않는다. 대가는 만료 판정을 단위 시험으로 볼 수 없다는 것이고, 그래서 경계 시험이 Testcontainers를 요구한다.

**`expires_at`을 발급이 못 박는다.** `issued_at + 5분`을 소비 시점에 계산하면 서버 시각이 뒤로 가는 순간 수명이 늘어난다. 절대 시각을 저장하면 그 경로가 닫히고, 역행 방어가 "발급이 기록한 값을 아무도 다시 계산하지 않는다"는 한 문장이 된다.

**`operation_id`의 UNIQUE 인덱스가 멱등의 권위다.** 애플리케이션이 "이미 있나?"를 먼저 조회하고 없으면 INSERT하는 형태는 동시 재전송에서 둘 다 통과한다. 인덱스가 두 번째를 거절하게 두고 그 충돌을 "이미 성공한 실행"으로 읽는다. 저장된 결과를 돌려주는 경로가 그 충돌 처리 하나다.

**업무 변경 실패에서 허가가 살아남는 것은 의도다.** AC-3이 요구하는 것은 "허가만 소비된 성공 상태를 남기지 않는다"이고, 트랜잭션 롤백은 소비 UPDATE도 되돌린다. 그래서 사용자는 같은 허가로 다시 시도할 수 있고 새 재인증을 강요받지 않는다. 반대로 성공한 실행은 결과와 소비가 함께 커밋되므로 재전송이 업무를 두 번 실행하지 않는다.

**세션 회전을 여기서 하지 않는 이유.** `SECURITY-ACTION-1.0`은 성공 시 세션 재회전을 요구하지만 회전은 Spring Security 필터가 HTTP 요청 문맥에서만 수행하고(`AuthSecurityConfig.java:819-820`) 이 스토리에는 그 문맥이 없다. 포트가 회전 의무를 결과 타입의 이름으로 드러내고 실제 배선은 첫 소비자(1.10)가 자기 엔드포인트에서 한다. 여기서 억지로 회전 경로를 만들면 HTTP 표면이 생기고 그것이 이 스토리의 Never를 깬다.

**루트 패키지가 처음으로 테이블을 갖는다.** 지금까지 모든 테이블은 `identity` 소유였고 루트 패키지는 `identity` 공개 API만 불렀다. 허가는 계정 소유권도 삭제 상태도 아니고 **세션과 작업**에 귀속하므로 `identity`에 둘 수 없다 — 그 모듈은 서블릿·HTTP 의존이 금지되어 세션 개념을 가질 수 없고(`ModuleBoundaryTest`), `AccessDecision`은 두 값뿐이라 표현할 자리도 없다. 새 모듈을 만드는 길은 `ARCHITECTURE-SPINE`의 모듈 목록을 바꾸는 일이라 이 스토리의 범위를 넘는다. 남는 답이 루트 패키지이며, 인증 표면이 이미 거기 있으므로 소유 관계도 자연스럽다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend clean build` -- expected: 허가 발급·소비·만료·동시성·트랜잭션 시험 통과, 경고 0건(`-Werror`), jar 생성. Docker 데몬 필요
- `npm --prefix frontend ci && npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run build && npm --prefix frontend run test` -- expected: 전부 통과. 프런트엔드는 이 스토리에서 무변경이므로 회귀만 본다
- `deploy/compose/verify-same-origin.sh` -- expected: 모든 단정 통과, exit 0. 마이그레이션이 하나 늘었으므로 `migrate` 역할이 `V3`를 적용한 뒤 백엔드가 healthy가 되는지가 실제 확인점이다
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 전부 통과. `ci.yml`을 바꾸지 않았으므로 무변경 통과여야 한다
- `git diff --stat develop -- backend/src/main/java/com/bokrilog/identity/ frontend/ deploy/nginx/` -- expected: 변경 0건

**Manual checks:**
- `grep -rn "@RestController\|@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping" backend/src/main/java/com/bokrilog --include=*.java` -- expected: Story 1.8과 같은 목록(새 HTTP 표면 0건)
- `grep -rn "FRESHNESS_WINDOW\|ARMED_LIFETIME" backend/src/main/java/com/bokrilog/ActionGrant*.java` -- expected: 0건(정책 상수를 공유하지 않는다)
- `grep -rn "Instant.now()" backend/src/main/java/com/bokrilog/ActionGrant*.java` -- expected: 만료 판정에 쓰이는 자리 0건
- 실행 중인 스택의 `docker compose logs`에서 세션 ID·`confirmation_hash`·허가 참조 검색 -- expected: 0건

## Suggested Review Order

**소비 — 이 스토리의 설계 의도 전부가 한 문장에 있다**

- 조건부 UPDATE 하나가 다섯 귀속·미소비·만료를 함께 판정한다. 판정과 소비 사이에 창이 없다.
  [`ActionGrantRepository.java:143`](../../backend/src/main/java/com/bokrilog/ActionGrantRepository.java#L143)

- 그 문장을 호출자 작업과 같은 트랜잭션에 넣는 자리. 업무 변경이 실패하면 소비도 되돌아간다.
  [`ActionGrantService.java:301`](../../backend/src/main/java/com/bokrilog/ActionGrantService.java#L301)

- 포트가 트랜잭션 경계를 소유한다. 호출자가 이미 열어 두었으면 거절한다.
  [`ActionGrantService.java:264`](../../backend/src/main/java/com/bokrilog/ActionGrantService.java#L264)

**멱등 — 인덱스가 권위이고 요청 지문이 열쇠다**

- 저장된 결과는 요청 지문 전체가 같을 때만 돌아온다. 같은 키를 다른 command에 재사용해도 옛 결과가 새 작업의 성공이 되지 않는다.
  [`ActionGrantRepository.java:197`](../../backend/src/main/java/com/bokrilog/ActionGrantRepository.java#L197)

- 우리 인덱스의 충돌만 "이미 실행됨"으로 읽는다. 호출자 업무 변경의 유일 제약 위반은 그대로 전파된다.
  [`ActionGrantService.java:360`](../../backend/src/main/java/com/bokrilog/ActionGrantService.java#L360)

- `(user_id, operation_id)` 부분 유일 색인. 사용자 축이 없으면 추측 가능한 키로 남의 작업을 막을 수 있다.
  [`V3__action_grant.sql`](../../backend/src/main/resources/db/migration/V3__action_grant.sql)

**발급 — 시각의 권위와 게이트 셋**

- 신선도 술어가 양쪽을 닫는다. 과거는 창, 미래는 skew — 그 값은 애플리케이션 시계가 쓴 것이기 때문이다.
  [`ActionGrantRepository.java:65`](../../backend/src/main/java/com/bokrilog/ActionGrantRepository.java#L65)

- 같은 귀속의 살아 있는 허가를 먼저 무효화한다. 재인증 한 번이 같은 작업의 허가를 겹쳐 만들지 않는다.
  [`ActionGrantRepository.java:111`](../../backend/src/main/java/com/bokrilog/ActionGrantRepository.java#L111)

- 세션에서 읽는 사실 셋을 한 곳에서 꺼낸다. 무효화된 세션과 `null`이 같은 거부로 접힌다.
  [`ActionGrantService.java:193`](../../backend/src/main/java/com/bokrilog/ActionGrantService.java#L193)

**값과 정책 — 상수가 사는 곳**

- 수명·재인증 창·미래 skew 셋. 1.8의 상수를 재사용하지 않는 이유가 각 javadoc에 있다.
  [`ActionGrant.java:40`](../../backend/src/main/java/com/bokrilog/ActionGrant.java#L40)

- 정규화가 한 함수다. 저장과 해시가 같은 값을 쓰지 않으면 영영 소비할 수 없는 허가가 생긴다.
  [`ActionGrant.java:177`](../../backend/src/main/java/com/bokrilog/ActionGrant.java#L177)

- 확인 문구의 정본 해시. 소비자마다 다른 정규화를 발명하지 않게 이 메서드가 유일한 생산자다.
  [`ActionGrant.java:163`](../../backend/src/main/java/com/bokrilog/ActionGrant.java#L163)

**1.8과의 접점**

- 기록만 있고 조회가 없던 짝을 맞춘다. 발급 게이트가 읽는 값이다.
  [`ReauthenticationFlow.java`](../../backend/src/main/java/com/bokrilog/ReauthenticationFlow.java)

**검증**

- AC-1·AC-2·AC-4·AC-5의 단일 소유자. 만료 네 경계와 값 거부 게이트가 여기 있다.
  [`ActionGrantTest.java`](../../backend/src/test/java/com/bokrilog/ActionGrantTest.java)

- 내부 시험 소비자. 업무 실패 롤백과 `operation_id` 재전송이 이 파일의 축이다.
  [`ActionGrantTransactionTest.java`](../../backend/src/test/java/com/bokrilog/ActionGrantTransactionTest.java)

- 조건부 UPDATE가 실제로 원자적인지 보는 유일한 층.
  [`ActionGrantConcurrencyTest.java`](../../backend/src/test/java/com/bokrilog/ActionGrantConcurrencyTest.java)
