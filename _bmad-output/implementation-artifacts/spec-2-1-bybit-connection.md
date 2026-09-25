---
title: 'Story 2.1: 읽기 전용 Bybit UTA 연결을 검증하고 안전하게 저장하기'
type: 'feature'
created: '2026-09-25'
status: 'done'
baseline_commit: 'd242645298b4f5ce14a0aeeaba99596b0be3db05'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Epic 1은 계정만 만들었다. 사용자는 로그인할 수 있을 뿐 자기 자산을 이 서비스에 연결할 방법이 없고, 저장소에는 **금융 원본을 담을 그릇도 비밀을 안전하게 둘 수단도 하나도 없다** — 암호화 자재가 `backend/` 전체에 0건이고, 모듈은 `identity` 하나뿐이며, 인증으로 막는 경로도 아직 없다. 이 스토리가 그 셋을 한꺼번에 들이면서 Epic 2의 나머지 열다섯이 올라설 바닥을 놓는다.

**Approach:** 세 단계를 순서대로 강제한다 — 최근 재인증(1.8) → `bybit-connection-create` 일회성 허가(1.9) → Bybit 세 엔드포인트 검증. **외부 호출은 트랜잭션 밖에서 끝내고**, 결과를 받은 뒤 허가 소비와 저장을 한 트랜잭션으로 커밋한다. 검증이 하나라도 확인 불가·모호하면 저장 없이 fail-closed로 거절하고 자동 재시도하지 않는다. 화면은 새 경로를 만들지 않고 `/settings` 안에서 연다.

## Boundaries & Constraints

**Always:**

- **검증 전부 통과 뒤에만 저장한다.** 순서는 `GET /v5/user/query-api` → `GET /v5/account/info` → `GET /v5/account/wallet-balance?accountType=UNIFIED`이고, 각각 `readOnly=1`·`isMaster=true`·`parentUid="0"`·`ips`에 운영 reserved IP 포함·쓰기 성격 permission 배열 전부 비어 있음 / `unifiedMarginStatus ∈ {5,6}` / 성공 + 단일 `UNIFIED` 결과 + 유효한 `totalEquity`를 요구한다. **모르는 값과 새 필드는 거부**다 — 허용 목록을 코드가 소유하고 목록에 없으면 닫는다.
- **외부 호출 동안 DB 트랜잭션도 계정 잠금도 열지 않는다.** 결과를 받은 뒤 최종 저장 트랜잭션에서 허가·소유권·삭제 상태·운영 제한·기존 연결 여부를 **다시** 검사한다. 경계는 `ActionGrantService.consume`이 소유하므로 호출자가 먼저 트랜잭션을 열면 포트가 거절한다.
- **자격정보는 AES-256-GCM·매 암호화마다 새 96-bit CSPRNG nonce·AAD `user_id/connection_id/credential_id/key_version/algorithm_version`·active key version으로만 저장한다.** key ring은 active 하나 + decrypt-only 과거 키이고 DB·Git·로그 밖에서 주입한다. 키가 없으면 **기동이 아니라 연결 기능이 fail-closed**다(제공자 등록과 같은 관용).
- **평문·부분 연결·지갑 응답 원문을 저장하지 않고, 성공 뒤 Secret 원문도 마스킹 값도 재표시하지 않는다.** Secret은 URL·브라우저 저장소·분석·로그·오류 추적·지표·광고 payload 어디에도 남기지 않는다.
- **`provider_account_id`가 안정 식별자다.** 검증된 main `userID`에서 파생하고 자격정보·connection 교체에도 재사용한다. **connection ID는 수집 provenance일 뿐 사건 고유키가 아니다.**
- 최종 트랜잭션의 원자 단위는 **허가 소비 + 암호화 자격정보 + `provider=BYBIT` connection + provider account + `USD` 기본 portfolio 생성·재사용 + 활성 source membership** 전부다. 반쪽 상태를 남기지 않는다.
- **DB 제약이 한 개 규칙을 강제한다** — 사용자당 활성 Bybit 연결 1개, portfolio당 활성 source 1개, 사용자당 `USD` 기본 portfolio 1개. 애플리케이션이 먼저 세고 나중에 넣는 형태를 만들지 않는다(Story 1.11의 관용).
- **운영 제한의 durable 판독 기반을 이 스토리가 짓는다**(`epics.md` Epic 2 독립 완료 조건). admission이 그것을 fail-closed로 읽는다. Epic 3은 그 제한을 **만들고 푸는** 운영자 표면을 얹을 뿐이다.
- **인증으로 막는 첫 경로가 생긴다.** 새 접두사에만 `authenticated()`를 걸고 나머지는 `permitAll`을 유지한다 — 전역으로 켜면 Story 1.2가 고정한 404·405가 401·302로 뒤집힌다. CSRF·`Origin` 판정도 **접두사 집합으로 넓히되 판정 함수는 하나로 유지**한다.
- **허가의 `confirmation`은 사용자가 실제로 본 확인 문구를 담는다.** command별 상수를 그대로 쓰지 않는다 — 그러면 해시가 `(command, target)`의 결정적 함수가 되어 `ERD-1.0.0` §5가 다섯 번째 축을 둔 뜻이 사라진다. Story 1.11까지의 두 command는 이 라운드에서 건드리지 않는다(`deferred-work.md` 65번).
- **거부는 사용자에게 구분 가능하다** — 형식·인증 거부·쓰기 권한·권한 부족·서브 UID·UID 불일치·IP·미지원 UTA·지갑 조회 실패·시간초과·rate limit·제공자 장애·응답 형식이 각각 다음 행동과 함께 나간다. **예외 둘**: 타 사용자·미존재 대상은 같은 외부 오류 계약으로 접히고, 이미 다른 BokriLog 계정에 연결된 UID는 원인만 알리고 상대 계정을 드러내지 않는다.
- **실패나 응답 누락을 `0 USDT`나 정상 연결로 표시하지 않는다.** 이 검증 조회를 공식 현재가치 스냅샷으로 발행하지 않는다.
- 화면은 `secure-input-form` 계약을 지킨다 — 영구 라벨·도움말·필드 오류·오류 요약·붙여넣기 허용, 실패 시 **Secret만** 지우고 비밀 아닌 입력과 오류 설명은 유지, 실패 시 포커스는 비밀 필드로.
- 이 스토리가 만든 데이터의 삭제 경로와 시험을 함께 완성한다. **삭제 상태가 저장·허가 소비와 경쟁하면 삭제가 이긴다.**

**Ask First:**

- **모듈을 셋 만드는 것** — `source`·`portfolio`·`operations`. `operations`를 지금 만드는 근거는 `epics.md`가 판독 기반의 소유자로 그 이름을 쓰기 때문이고, 나중에 옮기는 비용이 지금 만드는 비용보다 크다. 대안은 판독 기반을 `source`에 두고 Epic 3이 옮기는 것이다.
- **`ARCHITECTURE-SPINE.md`의 모듈 지도를 as-built로 정정하는 것을 이 스토리에 묶을지**(Epic 1 회고 액션 9). 지도가 루트 패키지 약 7,100줄과 루트 소유 테이블을 반영하지 않은 채로 다섯 모듈을 더하면 어긋남이 커진다.
- **새 HTTP 접두사의 이름** — `/api/connections/`를 제안한다. 이 값이 `PROTECTED_PATH_PREFIX` 집합·`authorizeHttpRequests`·Nginx·시험에 동시에 박힌다.
- **key ring 주입 형식** — 환경 변수에 base64 키 목록을 담는 형태를 제안한다. production 비밀 공급은 Story 3.8이 소유하므로 이 스토리는 읽는 쪽 계약만 정한다.
- **최소 조회 permission의 리터럴 목록.** 어느 계획 문서도 Bybit permission 문자열을 열거하지 않는다 — API 스파이크가 채울 공백이다. 목록을 **지어내지 않고** 허용 목록 구조와 fail-closed 판정만 먼저 만든 뒤 스파이크 결과로 채운다.

**Never:**

- 공식 현재가치 스냅샷 저장·표시(2.2), 계획 생성(2.3), 동기화 작업 큐와 Worker·lease·fencing(2.2 이후), 자금흐름 수집(2.4~2.6), 연결 해제(2.16), 둘 이상의 금융 source 연결·합산.
- **연결 검증에서 자동 재시도.** `ERD-1.0.0` §2.5의 3회 backoff는 sync page 수집 계약이며 admission에는 적용하지 않는다(AC-5·AD-21이 시간초과·rate limit 포함 금지). 사용자가 명시적으로 다시 시작한다.
- **Day 90을 조건부 게이트로 다루는 것.** 신규 연결은 처음부터 regional reserved IP binding을 요구한다(`ERD-1.0.0` §10·AR-21). 이 스토리는 신규 연결만 만든다.
- **CSP를 넓히는 것.** `form-action 'none'`을 유지한다 — 이 폼은 네이티브 제출을 하지 않고 `fetch`로만 보내므로 `connect-src` 관할이다. `'none'`을 남기면 JS가 죽었을 때 자격정보가 네이티브 제출로 새는 경로까지 닫힌다. `bokrilog.conf`와 `verify-same-origin.sh`의 CSP 전문 상수를 **둘 다 그대로 둔다**.
- **새 내부 경로.** 연결 화면은 `/settings` 안이다(UX-DR15). 경로를 더하면 등가 검사 없는 다섯 곳을 또 손으로 맞춰야 한다.
- BokriLog가 사용자 대신 Bybit 계정을 UTA 2.0으로 업그레이드하는 것. 안내만 한다.
- 서브 UID·Classic(`1`)·UTA 1.0(`3`·`4`)·알 수 없는 status를 지원 목록에 넣는 것.
- 자격정보 모양 리터럴을 소스·compose·fixture에 두는 것. 시험 키는 런타임 생성이다.
- 운영 성공률 95%를 자동시험 결과로 선언하는 것.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 정상 연결 | 인증 세션 + 5분 내 재인증 + 유효 허가, 읽기 전용 메인 UID UTA 2.0(`5`) 키 | 세 검증 통과 → 한 트랜잭션에 허가 소비·자격정보·connection·provider account·`USD` portfolio·membership 저장 → 세션 ID 회전 → `연결됨`·검증 시각·적용 정책 버전 표시 | N/A |
| UTA Pro | `unifiedMarginStatus=6` | 정상 연결과 동일 | N/A |
| 재인증 없음 | 허가 미발급 또는 만료 | 외부 호출 0건, 저장 0건 | 허가 계약의 단일 거부값 |
| 화면 우회 | 허가 없이 직접 `POST` | 외부 호출 0건, 저장 0건 | 같은 단일 거부값 |
| 쓰기 권한 | permission 배열에 쓰기 성격 값 | 저장 없이 거절 | 쓰기 권한 전용 오류 + 읽기 전용 키 재발급 안내 |
| 서브 UID | `isMaster=false` 또는 `parentUid≠"0"` | 저장 없이 거절 | 메인 UID 필요 안내 |
| 미지원 UTA | `unifiedMarginStatus ∈ {1,3,4,미상}` | 저장 없이 거절 | UTA 2.0 전환 안내(대행 업그레이드 없음) |
| IP 미바인딩 | `ips`에 운영 reserved IP 없음 | 저장 없이 거절 | IP 등록 안내 |
| 지갑 조회 실패 | 세 번째 호출 실패 또는 `UNIFIED` 결과 없음 | 저장 없이 거절, `0 USDT`로 표시하지 않음 | 조회 실패 오류 + 재시도는 사용자 조작 |
| 모르는 필드·값 | 허용 목록 밖 permission, 미상 status | 저장 없이 거절 | fail-closed, 낙관적 해석 없음 |
| 시간초과·rate limit | 외부 호출이 느리거나 429 | 저장 없이 거절, **자동 재시도 없음** | 각각 구분된 오류 + 잠시 후 재시도 안내 |
| 응답 형식 오류 | 스키마 불일치 | 저장 없이 거절 | 형식 오류, 원문을 화면·로그에 싣지 않음 |
| 이미 연결됨 | 그 사용자에게 활성 연결 존재 | 외부 호출 0건 | admission 거부 |
| 남의 계정 UID | 그 main UID가 다른 계정에 활성 연결 | 저장 없이 거절 | 상대 계정·제공자를 드러내지 않는 문장 |
| 동시 연결 | 같은 사용자 두 요청이 동시에 최종 저장 | 정확히 하나만 성공 | DB 제약이 갈라냄, 진 쪽은 전체 롤백 |
| 삭제 경쟁 | 저장 중 계정 삭제 시작 | 삭제가 이김 | 저장·허가 소비 전체 롤백, 작업·재시도로 재생성 없음 |
| 운영 제한 | 신규 연결 제한 활성 | 외부 호출 0건 | admission 거부 |
| 검증 실패 뒤 폼 | 위 거절 중 무엇이든 | Secret 필드만 비우고 나머지 입력·오류 설명 유지, 포커스는 비밀 필드 | 필드 오류 + 오류 요약 |
| 삭제 | 사용자 삭제 실행 | 자격정보·provider account·connection·portfolio·membership·임시 검증 데이터 제거, 타 사용자 보존 | 부분 실패를 완료로 보고하지 않음 |

</frozen-after-approval>

## Code Map

**모듈 템플릿 — `identity`를 그대로 따른다**

- `backend/src/main/java/com/bokrilog/identity/package-info.java:1-16` -- 모듈 헌장. Javadoc 산문만이고 `@ApplicationModule`·`@NamedInterface`는 저장소 전체에 없다. 새 모듈도 같은 형태.
- `.../identity/AccountLifecycleApi.java:18`, `AccountAccessApi.java:14,28`, `AccountDeletionApi.java:16,29,46` -- 공개 API는 루트 패키지의 인터페이스뿐
- `.../identity/internal/AccountLifecycleService.java:23-24` -- 구현은 `internal`의 **package-private** `@Service class ... implements ...Api`
- `.../identity/internal/IdentityAccountRepository.java:19-24,36` -- `@Repository` + `JdbcClient` 생성자 주입, 원시 SQL + named param. JPA 없음. `lockLifecycleForUpdate`가 `SELECT ... FOR UPDATE` 관용
- `.../identity/internal/AccountState.java:10` -- 내부 전용 enum + `parse`, 모르는 값은 빈 값 = 거부

**허가 소비 — 정확한 호출 모양**

- `backend/src/main/java/com/bokrilog/ActionGrantService.java:213-218` -- `issue(session, userId, command, target, confirmation)`
- `.../ActionGrantService.java:127-146` -- `ConsumptionRequest` 8-인자와 7-인자(왕복 없는 소비: `boundSessionId = null`). **이 스토리는 7-인자**다 — OIDC 왕복이 없어 발급과 소비 사이에 세션이 회전하지 않는다
- `.../ActionGrantService.java:84,96,99,310` -- `sealed interface Consumption` = `ConsumedRequiringSessionRotation(String)` \| `Denied()`; `consume(request, Supplier<String>)`
- `.../UnlinkService.java:172,294-300,325,341,365-370` -- 왕복 없는 소비자의 전범. 발급·소비·회전 순서와 `revokeSessionsOfExcept` 배치
- `.../AuthFlowHandlers.java:691,751-755` -- 호출자가 지는 회전 의무(`request.changeSessionId()`)
- `.../ActionGrantService.java` 트랜잭션 경계 -- 호출자가 먼저 트랜잭션을 열면 `IllegalStateException`. 외부 호출을 먼저 끝내야 하는 이유가 여기 있다

**인가·CSRF 표면 — 이 스토리가 처음 연다**

- `backend/src/main/java/com/bokrilog/AuthSecurityConfig.java:915-927` -- `authorizeHttpRequests`. 현재 규칙은 `anyRequest().permitAll()` 하나. `:917-926` 근거 주석이 **이 스토리를 지목한다**("보호는 서버가 소유한 자원이 생기는 스토리가 그 자원과 함께 더한다") — 주석도 함께 고친다
- `.../AuthSecurityConfig.java:213,1052-1055,1098-1107` -- `PROTECTED_PATH_PREFIX = "/api/auth/"`와 `isProtectedRequest`의 `startsWith`. **접두사 집합으로 넓히되 판정은 이 함수 하나에 남긴다**(`:1100-1101` 주석이 그 이유)
- `.../AuthSecurityConfig.java:982-984,1000-1003,1164-1178` -- 세션 귀속 CSRF 저장소, `SameOriginFilter`가 `CsrfFilter` **앞**, `expectedOrigin == null`이면 전부 거절
- `.../AuthSessionController.java:553-567` -- `currentUserId()` 관용. 인가 규칙이 생겨도 핸들러 검사를 지우지 않는다(이중 방어)
- `.../ApiErrorHandler.java:91,103-112,197-201` -- `record ApiError(code, message, correlationId)`와 `/error` 대체

**fail-closed 외부 구성 — key ring이 따를 형태**

- `backend/src/main/resources/application.yml:17-24,158-167` -- `${BOKRILOG_*:}` 빈 문자열 기본값
- `.../AuthSecurityConfig.java:458-462` -- 가드 본체. 하나라도 비면 그 등록만 조용히 빠진다
- `.../AuthSecurityConfig.java:423` -- 오류 메시지에 env 이름도 제공자도 싣지 않는다는 계약
- **암호화 자재는 `backend/` 전체에 0건**(`aes|gcm|cipher|keystore|javax.crypto|secretkey` 검색). 키 관리·`javax.crypto` 배선·회전이 전부 신규다

**마이그레이션**

- `backend/src/main/resources/db/migration/V3__action_grant.sql:1-8,21-23,34-41,104-106` -- 헤더 형식(요약 → `--` → 근거 산문), RESTRICT↔CASCADE 관용을 뒤집는 근거, 부분 유일 색인. **새 파일은 `V4__`**
- `.../V1__identity_account.sql:15,27,29` -- 모듈 소유 테이블의 `identity_` 접두사와 `ON DELETE RESTRICT`
- `.../SchemaValidationConfig.java:32-35,39-42` -- 기본은 `validate`만, `migrate` 프로필만 실제 마이그레이션

**dev 스텁 — Bybit 어댑터가 복제할 골격**

- `backend/src/main/java/com/bokrilog/DevOidcStubConfig.java:51,56,59,113-120,224-267,351-355` -- `@Profile` + **단일 `RouterFunction` 빈** + 생성자 guard(실제 자격정보와 공존하면 기동 거부) + 내부 base URL 분리
- `.../DevOidcStub.java` -- 스텁 **논리**는 `main`에 있고 표면만 둘. 두 번 만들지 않는다
- `backend/src/test/java/com/bokrilog/OidcStubConfiguration.java:45,55,69,86-101,183-214` -- 시험 쪽 표면. 루프백 `HttpServer` + `registerProperties`

**시험 관례**

- `backend/src/test/java/com/bokrilog/AuthFlowTestBase.java:65-66,72-75,132-142` -- 실제 HTTP(MockMvc 아님), **유일한** `@DynamicPropertySource`(하위에서 또 선언하면 컨텍스트가 갈라진다), `resetStore()`의 TRUNCATE 목록 — **새 테이블을 이름으로 추가**
- `.../AuthFlowTestBase.java:276,280,1029` -- `signIn(...)`, `databaseNow()`
- `.../AuthLinkProviderTest.java:843` -- `signInAndReauthenticate()` 레시피. 재인증 시각이 없으면 허가가 발급되지 않는다
- `.../ModuleBoundaryTest.java:33-42,45-58,61-87` -- `getModuleByName("identity")` **존재 단정 뒤** `verify()`. 새 모듈마다 존재 단정을 더하지 않으면 verify가 조용히 통과한다
- `.../PostgresTestcontainersConfig.java:35,37,55-59` -- `postgres:18.6-alpine`, JVM당 컨테이너 하나, 시험만 `Flyway::migrate`
- `.../identity/AccountConcurrencyTest.java:54,163,220,400` -- 동시성의 전범. `pg_locks` 폴링으로 겹침을 우연에 맡기지 않는다
- `.../AuthDeletionPathTest.java:134` -- 저장 실패 주입(`@MockitoSpyBean` + `doThrow().doCallRealMethod()`)
- `.../ActionGrantTest.java:872-888` -- 마이그레이션 CHECK 상한 ↔ 자바 상수 대조 관용
- `.../AuthTokenValidationTest.java:125` -- 외부 실패 주입은 스텁에 실패 계획을 등록하는 형태

**프런트엔드 — 저장소 첫 텍스트 입력 폼**

- `frontend/src/screens/SettingsScreen.tsx:7-14,22,88-91,118` -- 카피 import, CSS 모듈, CSRF 토큰 주입
- `frontend/src/styles/tokens.css:213-216` -- **`--component-secure-input-form-*` 네 토큰이 이미 있다**. 없는 토큰을 지어내지 않는다
- `frontend/src/session/unlinkProvider.ts:87-118` -- 공유 `post()` 헬퍼(모듈 로컬·미export). 새 모듈은 이것을 재사용하고, 재사용하려면 export해야 한다
- `frontend/src/components/UnlinkDialog.tsx:69-73,79-136,146` -- 초기 포커스·`Tab` 트랩·`Esc`·opener 복귀. **주의: 트랩이 `querySelectorAll('button')`만 모은다** — 입력 필드를 넣으면 셀렉터를 넓혀야 한다
- `frontend/src/content/auth.ts:132,444,910` -- `X-CSRF-TOKEN`, 문구 사전, 시험용 카피 배열
- `frontend/src/App.tsx:25-34` -- 닫힌 경로 맵. **이 스토리는 손대지 않는다**(새 경로 없음)

**동일 출처 검증**

- `deploy/compose/verify-same-origin.sh:370-551` -- 단정 도우미 전량(`assert_equals`·`assert_rows`·`assert_security_headers` 등)
- `.../verify-same-origin.sh:594-601` -- 복사할 최소 절, `:1692` 해제 왕복이 3-엔드포인트 흐름의 전범
- `.../verify-same-origin.sh:489-494` -- CSP 전문 상수. **이 스토리는 바꾸지 않는다**
- `deploy/nginx/bokrilog.conf:134,184-188,231` -- CSP(불변), `/api` 프록시 두 location, 경로 허용 목록(불변)
- `deploy/compose/compose.yaml:103-139` -- 백엔드 env 주입 자리. key ring 변수는 **이름만, 값은 비움**(`:132-133` 근거)

## Tasks & Acceptance

**Execution:**

- [x] `backend/src/main/resources/db/migration/V4__source_connection.sql` -- `source_provider_account`·`source_connection`·`source_credential`·`portfolio`·`portfolio_source_membership`·`operations_restriction` 생성 -- 모듈 소유 테이블 접두사 관용을 따르고, 한 개 규칙을 조건부 유일 색인으로 강제한다. 헤더에 RESTRICT/CASCADE 선택 근거를 적는다
- [x] `backend/src/main/java/com/bokrilog/source/package-info.java` 외 `source` 모듈 -- 공개 API(`SourceConnectionApi`)와 `internal` 구현·`JdbcClient` 저장소 -- Bybit 어댑터와 자격정보 소유
- [x] `backend/src/main/java/com/bokrilog/portfolio/…` -- `PortfolioMembershipApi` + `internal` -- 기본 `USD` portfolio와 활성 membership 소유
- [x] `backend/src/main/java/com/bokrilog/operations/…` -- `OperationalRestrictionApi`(판독 전용) + `internal` -- durable 제한을 읽는다. 쓰기·운영자 표면은 Epic 3
- [x] `backend/src/main/java/com/bokrilog/source/internal/CredentialCipher.java` -- AES-256-GCM·96-bit nonce·AAD 5필드·key ring -- 저장소 첫 암호화 자재
- [x] `backend/src/main/java/com/bokrilog/source/internal/BybitAdapter.java` -- 세 엔드포인트 순차 호출, provider-neutral DTO 반환, 허용 목록 밖은 fail-closed -- 재시도 없음
- [x] `backend/src/main/java/com/bokrilog/BybitConnectionController.java` + `BybitConnectionService.java` -- `/api/connections/bybit` 준비·확인·취소 -- 루트 패키지가 HTTP를 소유하는 관용(모듈에는 HTTP 표면이 없다)
- [x] `backend/src/main/java/com/bokrilog/AuthSecurityConfig.java` -- `PROTECTED_PATH_PREFIX`를 집합으로, 새 접두사에 `authenticated()`, `permitAll` 근거 주석 갱신 -- 판정 함수는 하나로 유지
- [x] `backend/src/main/java/com/bokrilog/DevBybitStubConfig.java` + 스텁 논리 -- `@Profile` + 단일 `RouterFunction` + 생성자 guard + 내부 base URL -- 성공 2종·거부 9종을 결정적으로 낸다
- [x] `backend/src/main/resources/application.yml`, `deploy/compose/compose.yaml` -- key ring·Bybit base URL·스텁 프로필 변수 -- 이름만, 값은 비움
- [x] `frontend/src/screens/SettingsScreen.tsx` + `BybitConnectionForm.tsx` + CSS 모듈 -- `secure-input-form` 계약을 지키는 첫 폼 -- 기존 토큰만 쓴다
- [x] `frontend/src/content/auth.ts`(또는 새 content 모듈) -- 연결 카피·오류 문장·시험용 배열 -- 화면에 한글 리터럴을 두지 않는다
- [x] `frontend/src/session/bybitConnection.ts` -- 요청 모듈. `unlinkProvider.ts`의 `post()`를 export해 재사용 -- 네 번째 복제를 만들지 않는다
- [x] 백엔드 시험 -- admission·어댑터 11종·트랜잭션·암호화·동시성·삭제 경쟁·경계 -- `AuthFlowTestBase` 상속, `resetStore()`에 새 테이블 추가
- [x] `backend/src/test/java/com/bokrilog/ModuleBoundaryTest.java` -- 새 모듈 셋의 존재 단정과 `internal` 미노출 규칙 -- 존재 단정을 빠뜨리면 `verify()`가 조용히 통과한다
- [x] `frontend/src/screens/*.test.tsx` -- 폼 접근성·Secret만 초기화·저장소 무기록·비밀 미노출
- [x] `deploy/compose/verify-same-origin.sh` -- 연결 왕복 절 추가 -- 스텁으로 성공·거부를 실제 HTTP로 지난다
- [x] `frontend/e2e/*.spec.ts` -- 주입 origin 게이트 안에서 성공 왕복과 비밀 비노출

**Acceptance Criteria:**

- Given 재인증도 허가도 없는 인증 세션, when 연결 확인을 직접 호출하면, then 외부 호출 0건·저장 0건이고 허가 계약의 단일 거부값이 나간다
- Given 유효한 허가와 지원되는 읽기 전용 키, when 연결이 성공하면, then 여섯 저장이 한 트랜잭션으로 커밋되고 세션 ID가 회전하며 응답 어디에도 Secret이 없다
- Given 외부 호출이 진행 중, when 그 순간의 DB 상태를 보면, then 열린 트랜잭션도 계정 잠금도 없다
- Given 같은 사용자의 두 연결 요청이 동시에 최종 저장에 도달, when 둘 다 커밋을 시도하면, then 정확히 하나만 성공하고 진 쪽은 자격정보·portfolio·membership을 하나도 남기지 않는다
- Given 저장 도중 계정 삭제가 시작, when 둘이 경합하면, then 삭제가 이기고 허가 소비까지 롤백되며 재시도로 연결이 되살아나지 않는다
- Given 같은 main UID로 자격정보를 교체해 다시 연결, when 저장이 성공하면, then `provider_account_id`가 같은 값으로 재사용된다
- Given 계정 삭제 실행, when `source`·`portfolio` 삭제 경로가 돌면, then 그 사용자의 자격정보·connection·provider account·portfolio·membership이 사라지고 다른 사용자 것은 남는다
- Given 새 모듈 셋, when `ModuleBoundaryTest`가 돌면, then 세 모듈이 이름으로 발견되고 `internal` 패키지가 밖에서 보이지 않는다
- Given 진입 화면과 기존 API 경로들, when 인가 규칙이 켜진 뒤 회귀를 돌면, then `/api/nope`의 404와 `POST /api/status`의 405가 그대로다
- Given 배포된 스택, when CSP 헤더를 전문 비교하면, then `form-action 'none'`을 포함해 Story 1.2가 고정한 값과 글자까지 같다

## Spec Change Log

## Design Notes

**왜 세 모듈인가.** `source`와 `portfolio`는 AD-15가 소유물을 이미 못박았다. `operations`가 논쟁적인데, `epics.md`의 Epic 2 독립 완료 조건이 "`operations`의 durable restriction 판독 기반"이라고 **모듈 이름으로** 적었고 Story 3.2의 선행이 "Epic 2의 restriction 검사 지점"이다. 판독 기반을 `source`에 두면 Epic 3이 그것을 옮겨야 하고, 옮기는 동안 admission이 가리키는 곳이 바뀐다.

**왜 새 경로를 만들지 않는가.** 내부 경로 집합은 지금 다섯 곳에 복제돼 있고 등가 검사가 없다(미해소 항목). UX-DR15가 설정 화면을 Bybit 연결의 집으로 정했으므로 `/settings` 안에서 여는 것이 문서와도 맞고 복제도 늘리지 않는다.

**왜 CSP를 넓히지 않는가.** `form-action`은 네이티브 폼 제출의 목적지를 제한한다. 이 폼은 `onSubmit`에서 기본 동작을 막고 `fetch`로 보내므로 `connect-src 'self'` 관할이고 `form-action`은 발동하지 않는다. `'none'`을 남기면 JS가 실패한 상태에서 사용자가 Enter를 눌러도 자격정보가 어디로도 나가지 않는다 — 비밀을 다루는 폼에서 이쪽이 엄격하게 낫다. `bokrilog.conf:132`의 주석("폼이 생기면 그 스토리가 `'self'`로 연다")은 폼이 fetch 기반이 될 줄 모르고 쓴 것이므로 주석만 정정한다.

**재시도 금지의 근거.** `ERD-1.0.0` §2.5가 timeout·429·5xx에 3회 backoff를 요구하지만 그 절의 문맥은 sync page 수집이다. AC-5와 AD-21은 연결 검증에서 시간초과·rate limit을 포함해 자동 재시도를 금지한다. 두 문서가 admission 제외를 명시하지 않아 충돌처럼 보이므로 이 SPEC이 범위를 못박는다 — **admission은 재시도하지 않는다.**

**confirmation의 새 계약.** `ERD-1.0.0` §5는 허가를 다섯에 귀속시키라 하는데 as-built의 다섯 번째 축은 command별 상수라 판별력이 0이다. Story 2.13·2.16의 AC-3이 "확인 뒤 상태가 바뀐 허가를 거절한다"를 요구하므로 그 축이 실제로 살아 있어야 한다. 이 스토리가 `bybit-connection-create`에 대해 **사용자가 본 확인 문구를 담는 형태**를 정하고, Epic 1의 두 command 개조는 별도 라운드다.

## Verification

**Commands:**

- `backend/gradlew --project-dir backend build` -- expected: 전체 통과, `-Werror` 경고 0. Docker 데몬 필요
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check` -- expected: 경고 0
- `npm --prefix frontend run test` -- expected: 전량 통과
- `npm --prefix frontend run build` -- expected: 성공
- `deploy/compose/verify-same-origin.sh` -- expected: exit 0. 새 연결 왕복 절 포함, CSP 전문 단정 불변
- `deploy/compose/verify-same-origin.sh --keep-stack` 뒤 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 npm --prefix frontend run test:e2e:run` -- expected: 전량 통과
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 전량 통과

**Manual checks:**

- 성공 연결 뒤 `source_credential` 행을 직접 조회해 평문 조각이 없고 `nonce`가 행마다 다른지 본다
- 실패한 검증 뒤 `source_*`·`portfolio*` 테이블의 행 수가 0인지 본다
- 스택 로그 전문에서 API Key·Secret·`BOKRILOG_*` 이름이 나오지 않는지 본다(`verify-same-origin.sh`의 누출 검사가 자동화하지만 새 경로는 눈으로도 확인)

## Suggested Review Order

**세 단계를 순서대로 강제하는 자리 — 여기서 시작한다**

- 준비의 게이트. 저장할 수 없는 스택에서는 제공자를 두드리기 전에 닫는다
  [`BybitConnectionService.java:191`](../../backend/src/main/java/com/bokrilog/BybitConnectionService.java#L191)

- 확인 단계가 게이트를 처음부터 다시 통과한다 — 준비 시점의 판정을 믿지 않는다
  [`BybitConnectionService.java:287`](../../backend/src/main/java/com/bokrilog/BybitConnectionService.java#L287)

- 사용자가 본 문구와 되돌아온 문구를 NFC 정규화 후 비교한다. 다섯 번째 귀속 축이 여기서 산다
  [`BybitConnectionService.java:307`](../../backend/src/main/java/com/bokrilog/BybitConnectionService.java#L307)

**원자성 — 외부 호출은 밖에서, 여섯 저장은 한 트랜잭션에서**

- 허가 포트가 트랜잭션 경계를 소유하므로 외부 호출이 이 앞에서 이미 끝나 있다
  [`BybitConnectionService.java:343`](../../backend/src/main/java/com/bokrilog/BybitConnectionService.java#L343)

- 트랜잭션 **안**의 운영 제한 재검사. 왕복 중에 제한이 켜지면 여기서 전체가 롤백된다
  [`BybitConnectionService.java:378`](../../backend/src/main/java/com/bokrilog/BybitConnectionService.java#L378)

- 한 개 규칙을 애플리케이션이 세지 않고 조건부 유일 색인이 강제한다
  [`V4__source_connection.sql:86`](../../backend/src/main/resources/db/migration/V4__source_connection.sql#L86)

**인증 게이트 — 저장소에서 처음 열리는 자리**

- 새 접두사에만 `authenticated()`. 나머지는 `permitAll`로 남아 Story 1.2의 404·405가 살아 있다
  [`AuthSecurityConfig.java:956`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L956)

- 인가와 CSRF·`Origin`이 같은 술어 하나를 부른다. 비대칭이 생길 자리를 없앴다
  [`AuthSecurityConfig.java:1199`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L1199)

**비밀 — 저장소 첫 암호화 자재**

- AES-256-GCM, 매번 새 96비트 nonce, AAD 다섯 필드가 암호문을 그 행에 묶는다
  [`CredentialCipher.java:41`](../../backend/src/main/java/com/bokrilog/source/internal/CredentialCipher.java#L41)

- 자격정보 컬럼과 그 CHECK 상한. 평문도 응답 원문도 들어오지 않는다
  [`V4__source_connection.sql:103`](../../backend/src/main/resources/db/migration/V4__source_connection.sql#L103)

**제공자 판정 — 모르는 값은 전부 닫는다**

- 세 호출을 순서대로 한 번씩. 앞이 거절하면 뒤를 보내지 않고 재시도도 없다
  [`BybitAdapter.java:208`](../../backend/src/main/java/com/bokrilog/source/internal/BybitAdapter.java#L208)

- 허용 목록은 비어 있고 그 공백은 스파이크가 채운다 — 비어 있으면 닫히는 쪽이 기본값이다
  [`BybitAdapter.java:257`](../../backend/src/main/java/com/bokrilog/source/internal/BybitAdapter.java#L257)

- 지갑 응답은 단일 `UNIFIED`와 canonical decimal만 받는다. 0으로 대체하지 않는다
  [`BybitAdapter.java:237`](../../backend/src/main/java/com/bokrilog/source/internal/BybitAdapter.java#L237)

**모듈 경계 — `identity` 하나에서 넷으로**

- 연결·자격정보·어댑터를 소유한다. HTTP 표면은 없다
  [`SourceConnectionApi.java:20`](../../backend/src/main/java/com/bokrilog/source/SourceConnectionApi.java#L20)

- 호출자의 트랜잭션에 참여할 뿐 자기 경계를 열지 않는다
  [`PortfolioMembershipApi.java:36`](../../backend/src/main/java/com/bokrilog/portfolio/PortfolioMembershipApi.java#L36)

- 판독 전용. 제한을 만들고 푸는 운영자 표면은 Epic 3이 얹는다
  [`OperationalRestrictionApi.java:25`](../../backend/src/main/java/com/bokrilog/operations/OperationalRestrictionApi.java#L25)

**화면 — 저장소 첫 텍스트 입력 폼**

- 네이티브 제출을 하지 않으므로 CSP `form-action 'none'`을 넓히지 않았다
  [`BybitConnectionForm.tsx:50`](../../frontend/src/components/BybitConnectionForm.tsx#L50)

- `/connect`만 20초 예산. 서버 최악(5초×3)을 덮어 성공이 abort로 뒤집히지 않는다
  [`bybitConnection.ts:43`](../../frontend/src/session/bybitConnection.ts#L43)

**시험 — 감사가 통과한 근거**

- 외부 호출이 도는 동안 열린 트랜잭션도 계정 잠금도 없음을 호출 스레드에서 관측한다
  [`BybitConnectionTransactionTest.java:1`](../../backend/src/test/java/com/bokrilog/BybitConnectionTransactionTest.java#L1)

- 네 흐름의 상호 폐기 양방향과 "거절되는 시작은 남의 무장을 지킨다"
  [`BybitConnectionFlowTest.java:1`](../../backend/src/test/java/com/bokrilog/BybitConnectionFlowTest.java#L1)

- 기본 프로필에 스텁이 없고, 프로필과 실제 주소가 겹치면 양방향 모두 기동을 거부한다
  [`DevBybitStubProfileTest.java:1`](../../backend/src/test/java/com/bokrilog/DevBybitStubProfileTest.java#L1)
