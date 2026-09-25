---
title: 'Story 1.11 — 로그인 수단을 안전하게 해제하기'
type: 'feature'
created: '2026-09-25'
status: 'done'
review_loop_iteration: 0
baseline_commit: '1a9287f19be71afbc887d4eb17efe80d2018225e'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-10-link-sign-in-method.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-9-reauthentication-action-grant.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 연결은 되는데 해제가 안 된다. 사용자는 쓰지 않는 외부 계정 연결을 떼어 낼 수 없고, 설정 화면은 "연결됨"을 보여 줄 뿐 아무 조작도 주지 않는다. 더 무거운 것은 **마지막 수단 보호가 어디에도 없다는 것**이다 — 해제 경로가 생기는 순간 그 보호가 없으면 사용자가 자기 계정에서 영구히 잠긴다.

**Approach:** 마지막 수단 판정을 **삭제 문장 안에** 넣어 UI 상태와 무관하게 서버가 거절하게 한다. 해제는 OIDC 왕복이 없으므로 확인 대화상자가 그 자리를 대신한다 — 대화상자를 여는 순간 허가가 발급되고 최종 확인이 그것을 소비한다. 커밋 뒤에는 해제된 수단에서 유래했을 수 있는 다른 세션을 모두 폐기하고 현재 세션만 회전시켜 남긴다.

## Boundaries & Constraints

**Always:**
- **마지막 수단 보호는 삭제 문장의 술어다.** 애플리케이션이 먼저 세고 나중에 지우는 형태를 만들지 않는다 — 그 사이가 곧 "동시 요청으로 모든 수단이 사라지는" 창이다. `identity`가 계정 생명주기 행을 잠근 뒤 한 문장으로 판정·삭제한다(`insertSocialLinkIfActive`와 같은 관용).
- 해제는 **최근 재인증(1.8)과 일회성 허가(1.9)**를 요구한다. 허가는 현재 사용자·현재 세션·`해제` 작업·대상 제공자에 귀속하고 5분 동안 한 번만 쓰인다.
- **최종 확인 전에는 아무것도 바뀌지 않는다.** 확인 대화상자를 여는 요청은 허가를 발급하고 무장할 뿐 매핑·세션·목록을 건드리지 않는다.
- 허가 소비·매핑 제거·결과 저장은 **한 트랜잭션**이다. 부분 완료를 남기지 않는다.
- 커밋 뒤 **현재 세션 외의 그 사용자 세션을 모두 폐기하고** 현재 세션은 다시 회전시켜 유지한다. 세션은 어떤 제공자로 인증됐는지 기록하지 않으므로(그 기록은 "세션이 담는 값은 내부 `user_id` 하나"라는 불변식이 금지한다) AC-7이 정한 **fail-safe 갈래**를 택한다.
- 거부는 하나의 값이다. 마지막 수단·허가 만료·세션 불일치·삭제 상태·미연결 제공자가 응답으로 구분되지 않는다. 다만 **마지막 수단은 화면이 미리 설명한다** — 서버 응답이 아니라 연결 목록에서 사용자가 이미 아는 사실이다.
- 확인 대화상자는 대상·비가역 결과·보존되는 데이터를 구분해 설명하고 **기본 포커스를 취소에 둔다**(DESIGN.md `confirmation-dialog`).
- 해제된 제공자로는 그 계정에 다시 로그인할 수 없고 자동 재연결도 없다. 같은 `operation_id` 재호출은 저장된 완료 결과로 수렴한다.
- 외부 식별자 원문·제공자 토큰·허가 참조를 화면·응답·URL·로그 어디에도 싣지 않는다.

**Ask First:**
- `identity` 공개 API에 **해제**를 더하는 것. 마지막 수단 보호를 그 안에 넣어야 원자성이 성립한다.
- `SessionGuard`에 **현재 세션만 남기고 폐기**하는 메서드를 더하는 것. 지금은 전부 폐기만 있다.
- 세션에 인증 제공자를 기록해 AC-7의 1차 갈래("대상 identity에서 유래한 세션만 폐기")를 택하는 것. 기본은 **택하지 않는다** — `user_id` 하나만 담는다는 불변식을 깬다.

**Never:**
- 외부 제공자 계정 자체의 삭제, BokriLog 계정 삭제(Epic 5), 계정 간 병합·이전.
- 새 마이그레이션. `identity_social_link`가 이미 있고 컬럼을 늘리지 않는다.
- 새 내부 경로·새 화면. 해제는 설정 화면 안의 대화상자다.
- 해제 실패를 자동 재시도하는 것. 사용자가 명시적으로 다시 시작한다.
- 마지막 수단 거부를 **다른 거부와 다른 코드**로 답하는 것. 화면이 미리 아는 사실이라 응답을 나눌 이유가 없다.
- 해제된 매핑을 운영 데이터에서 되살리는 경로.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| 해제 가능 표시 | 수단 둘 이상 연결 | 각 수단에 키보드 도달 가능한 해제 동작 | N/A |
| 마지막 수단 표시 | 수단 하나 | 해제 동작 비활성, 마지막 접근 수단임을 설명 | N/A |
| 확인 준비 | 재인증 신선 + 대상이 연결됨 + 수단 둘 이상 | 허가 발급·무장, 204. **매핑·세션 무변경** | N/A |
| 재인증 없음·오래됨 | 재인증 시각 없거나 창 밖 | 무장하지 않음, 거부 하나 | 화면이 재인증 안내 |
| 정상 해제 | 무장 + 최종 확인 | 허가 소비·매핑 제거·결과 저장 1회 커밋, 다른 세션 폐기, 현재 세션 회전 | N/A |
| 마지막 수단 해제 시도 | 수단 하나인데 API 직접 호출 | 삭제 0행, 매핑·세션 무변경 | 거부 하나 |
| 동시 해제 둘 | 수단 둘을 동시에 해제 | 정확히 하나만 성공, 최소 하나 보존 | 나머지는 거부 하나 |
| 미연결 제공자 해제 | 연결되지 않은 제공자 | 삭제 0행, 무변경 | 같은 거부 |
| 허가 만료·이미 사용됨 | 무장은 살아 있으나 허가가 죽음 | 무변경 | 같은 거부 |
| 삭제 전환 | 흐름 도중 `DELETION_IN_PROGRESS` | 무변경 | `AccessDecision.DENIED` 하나 |
| 최종 확인 전 이탈 | 무장만 하고 확인하지 않음 | 매핑·세션·목록 무변경, 무장은 5분 뒤 만료 | N/A |
| 응답 유실 후 재전송 | 같은 `operation_id` | 저장된 같은 결과, 매핑 재삭제 없음 | N/A |
| 해제 뒤 그 제공자 로그인 | 제거된 `(issuer, subject)` | 기존 계정에 닿지 않는다(정책에 따라 신규 가입) | 자동 재연결 없음 |
| 해제 뒤 남은 수단 로그인 | 남은 제공자 | 같은 내부 사용자 | N/A |
| 다중 기기 세션 | 같은 사용자의 다른 세션들 | 현재 세션 외 전부 폐기 | N/A |
| 해제 뒤 계정 삭제 | `erasePayload` | 남은 매핑이 사라지고 다른 사용자는 보존 | N/A |
| CSRF·교차 출처 | 토큰 없음 또는 다른 `Origin` | 403 | 기존 계약 |
| 미지 제공자 | `/api/auth/unlink/<미등록 이름>` | 404 | 아는 제공자 목록·개수 미노출 |

</frozen-after-approval>

## Code Map

**조사가 정한 사실 (2026-09-25)**

- **해제 API가 없다.** `AccountLifecycleApi`는 `createAccount`·`linkSocialIdentity`·`decideSessionCreation`·`findUserIdBySocialIdentity`·`findLinkedProviders`(`identity/AccountLifecycleApi.java:109`, 1.10이 더함) 다섯이고 제거가 없다. 저장소에도 `deleteSocialLinks(userId)`(전량, 삭제 경로 전용, `internal/IdentityAccountRepository.java:198-202`)만 있다.
- **원자적 판정·쓰기의 관용이 이미 있다.** `insertSocialLinkIfActive`(`:95-108`)가 `INSERT ... SELECT ... FROM identity_account_lifecycle WHERE user_id=:userId AND state='ACTIVE' ON CONFLICT DO NOTHING` 한 문장으로 상태와 유일성을 함께 판정한다. `AccountLifecycleService.linkSocialIdentity`(`internal/AccountLifecycleService.java:61`)가 `lockLifecycleForUpdate`(`:75`)로 먼저 잠그므로 같은 사용자의 동시 요청이 직렬화된다. 해제도 **같은 두 겹**을 쓴다.
- **`setRollbackOnly`을 쓰지 않는 것이 거부의 관용이다.** 상태 술어를 문장 안에 넣고 0행으로 답하므로 예외가 없고 호출자 트랜잭션이 오염되지 않는다(`internal/AccountLifecycleService.java:80-105` 주석).
- **세션은 인증 제공자를 기록하지 않는다.** `spring_session`의 컬럼은 `PRINCIPAL_NAME`까지이고(`V2__spring_session.sql:23-31`) 앱이 쓰는 속성 다섯(`AuthFlowHandlers`의 목적지 둘, `LinkFlow.FLOW_ATTRIBUTE`, `ReauthenticationFlow`의 둘, `SessionGuard.REAUTH_REQUIRED_ATTRIBUTE`) 어디에도 제공자가 없다. AC-7의 1차 갈래는 그 기록을 요구하고 CLAUDE.md의 세션 불변식이 그것을 금지하므로 **fail-safe 갈래가 유일한 길**이다.
- **`SessionGuard`에 "현재 세션만 남기기"가 없다.** `revokeSessionsOf(UUID)`(`SessionGuard.java:159`)는 예외 없이 전부 지우고, `revokeSession(String)`(`:168`)은 하나만 지운다. 둘 사이가 이 스토리가 메울 간극이다. 내부 `SessionStore.deleteByPrincipal`(`:237-259`)이 경합 재확인 2회를 이미 한다.
- **1.9 포트의 모양.** `Optional<String> issue(HttpSession, UUID, String command, String target, String confirmation)`(`ActionGrantService.java:213`), `void invalidate(UUID, String command, String target)`(`:254`), `Consumption consume(ConsumptionRequest, Supplier<String>)`(`:310`), `record ConsumptionRequest(HttpSession, UUID, String grantReference, String command, String target, String confirmation, String operationId, @Nullable String boundSessionId)`(`:127-135`)와 `boundSessionId` 생략 생성자(`:138-147`). **해제에는 OIDC 왕복이 없어 세션이 회전하지 않으므로 그 생략 생성자를 쓴다.**
- **1.10이 남긴 형태를 그대로 따른다.** `LinkService`(`LinkService.java:139`·`:161`·`:228`·`:252`, 상수 `COMMAND`·`CONFIRMATION`·`RESULT_LINKED`), `LinkFlow`(`LinkFlow.java:49`·`:78-94`), `AuthSessionController`의 `GET /api/auth/link`(`:318`)·`POST /api/auth/link/{provider}`(`:361`). 해제는 그 대칭이다.
- **설정 화면에 자리가 이미 있다.** `SettingsScreen.tsx:223-253`의 제공자 행 렌더에서 `linked.includes(provider)`면 `연결됨`만 그린다(`:228-230`) — 해제 버튼이 들어갈 곳이 정확히 그 가지다. 상태 모델은 `linked`·`linkStatus`·`attempt`(`:75-83`)이고 훅 넷(`useSessionGate('settings')`·`useSignOut`·`useReauthentication`·`useLinking`)을 쓴다.
- **대화상자 토큰이 이미 있다.** `tokens.css:218-221`의 `--component-confirmation-dialog-{background,foreground,radius,destructive}`. DESIGN.md 컴포넌트 표(`:267`)가 "제목에 대상과 결과를 쓰고, 보존되는 데이터와 삭제되는 데이터를 분리한다. 취소가 기본 포커스다"를 규정한다.
- **동시성 시험의 관용.** `identity/AccountConcurrencyTest.java`의 `runQueuedOnLifecycleLocks`(`:163-199`)가 시험 트랜잭션으로 대상 행을 `FOR UPDATE` 잠그고 `awaitBlockedBackends`(`:131-149`, `pg_locks` 폴링)로 실제 대기열 진입을 확인한 뒤 순서대로 푼다. 승자를 스케줄러가 아니라 시험이 정한다.
- **1.10의 시험 헬퍼.** `AuthFlowTestBase.linkStatus(...)`(`:436`), `startLink(...)`(`:448`·`:471`), `link(provider, sessionCookie, csrfToken, subject)`(`:486`, 왕복 원샷).

**새로 만드는 자리**

- `backend/src/main/java/com/bokrilog/identity/` -- `unlinkSocialIdentity(userId, provider)` 공개 메서드와 그 구현·저장소 SQL. 생명주기 행 잠금 뒤 **한 문장**으로 `state='ACTIVE'`·대상 존재·남은 수단 2개 이상을 함께 판정하고 0/1행으로 답한다. `AccessDecision` 하나로 접는다.
- `backend/src/main/java/com/bokrilog/SessionGuard.java` -- 현재 세션 하나를 빼고 그 사용자의 세션을 폐기하는 메서드. 기존 `deleteByPrincipal`의 경합 재확인을 그대로 쓴다.
- `backend/src/main/java/com/bokrilog/UnlinkFlow.java` -- 확인 대화상자가 열린 동안의 무장. `(targetProvider, userId, grantReference, operationId, armedAt)`. `LinkFlow`와 같은 모양이되 `returnPath`·`boundSessionId`가 없다 — 왕복이 없어 세션이 회전하지 않는다.
- `backend/src/main/java/com/bokrilog/UnlinkService.java` -- 준비(재인증 신선도 → 마지막 수단 아님 → 허가 발급 → 무장)와 실행(허가 소비 트랜잭션 안에서 `unlinkSocialIdentity`, 커밋 뒤 세션 정리)을 소유한다. `LinkService`의 대칭이다.
- `frontend/src/components/UnlinkDialog.tsx` (+ `.module.css`) -- 확인 대화상자. 대상·비가역 결과·보존되는 데이터를 구분해 설명하고 기본 포커스를 취소에 두며 포커스를 가두고 닫을 때 호출점으로 돌려준다.
- `frontend/src/session/unlinkProvider.ts` + `useUnlinking.ts` -- 준비·실행 호출과 진행 상태. `linkProvider.ts`·`useLinking.ts`와 같은 모양.

**기존 파일에서 넓힐 자리**

- `AuthSessionController` + `AuthSecurityConfig` -- 엔드포인트 둘(`POST .../unlink/{provider}`=준비, `POST .../unlink/{provider}/confirm`=실행)과 경로 상수. 404/403/204 관용을 따른다.
- `AuthSessionController.startReauthentication`·`LinkService.start` -- 무장 시 상대 흐름을 버리는 자리가 이미 둘이다(`:280`, `LinkService.java:193-194`). 해제 무장을 그 목록에 넣는다 — **세 흐름이 서로를 버린다**.
- `SettingsScreen.tsx` (+ `.module.css`) -- 연결된 행에 해제 버튼, 수단이 하나면 비활성과 설명, 대화상자 연결, 성공·실패 표지.
- `content/auth.ts` -- 해제 엔드포인트·표지·대화상자 카피. 표지는 닫힌 집합 세 곳에 함께 더한다.
- `AuthFlowTestBase` -- 해제 준비·실행 헬퍼를 `startLink`·`link` 옆에 더한다. 새 `@DynamicPropertySource`를 만들지 않는다.
- 무변경: `db/migration/**`, `deploy/nginx/`, `App.tsx`(새 경로 없음), `identity`의 기존 메서드.

## Tasks & Acceptance

**Execution:**

- [x] `backend/src/main/java/com/bokrilog/identity/` -- `unlinkSocialIdentity(userId, provider)`와 구현·저장소 SQL을 더한다. 생명주기 행을 잠근 뒤 한 문장으로 `ACTIVE`·대상 존재·**남은 수단 2개 이상**을 함께 판정한다. 거부에 `setRollbackOnly`를 쓰지 않는다. -- AC-2·AC-5. 애플리케이션이 먼저 세고 나중에 지우면 그 사이가 곧 계정이 잠기는 창이다.
- [x] `backend/src/test/java/com/bokrilog/identity/` -- 해제의 identity 축: 수단 1·2·3개, 마지막 수단 거부, 미연결 제공자, 삭제 진행·완료 계정, 타 사용자 매핑 보존, 그리고 **두 수단 동시 해제에서 정확히 하나만 성공**(`runQueuedOnLifecycleLocks` 관용). -- AC-2·AC-5의 저장소 층 소유자.
- [x] `backend/src/main/java/com/bokrilog/SessionGuard.java` -- 현재 세션 하나를 빼고 그 사용자의 세션을 폐기하는 메서드와 시험. 기존 경합 재확인을 그대로 쓴다. -- AC-7.
- [x] `backend/src/main/java/com/bokrilog/UnlinkFlow.java` -- 확인 대화상자가 열린 동안의 무장. `LinkFlow`와 같은 모양이되 왕복이 없어 `returnPath`·`boundSessionId`가 없다. -- AC-4. 최종 확인 전에 아무것도 바뀌지 않는다는 계약이 이 무장 하나로 성립한다.
- [x] `backend/src/main/java/com/bokrilog/UnlinkService.java` -- 준비와 실행. 실행은 허가 소비 트랜잭션 안에서 `unlinkSocialIdentity`를 부르고, 커밋 뒤 다른 세션을 폐기하고 현재 세션을 회전시킨다. 거부는 하나로 접는다. -- AC-3·AC-5·AC-6·AC-8.
- [x] `backend/src/main/java/com/bokrilog/AuthSessionController.java` + `AuthSecurityConfig.java` -- 엔드포인트 둘과 경로 상수. 세 흐름이 서로를 버리는 목록에 해제를 넣는다. `authorizeHttpRequests`는 그대로 둔다. -- AC-3.
- [x] `backend/src/test/java/com/bokrilog/AuthUnlinkProviderTest.java` -- 정상 해제, 마지막 수단 API 직접 호출, 재인증 없음·오래됨, 허가 만료·소비됨, 미연결 제공자, 삭제 전환, 최종 확인 전 무변경, 같은 `operation_id` 재전송, 해제 뒤 그 제공자 로그인이 기존 계정에 닿지 않음, 남은 수단 로그인, 다중 세션 폐기와 현재 세션 회전, CSRF·교차 출처·미지 제공자. identity가 고정한 동시성 축은 복제하지 않는다. -- AC-2~AC-9의 HTTP 층 소유자.
- [x] `backend/src/test/java/com/bokrilog/AuthFlowTestBase.java` -- 해제 준비·실행 헬퍼를 기존 자리에 더한다. -- 컨텍스트 캐시를 가르지 않는다.
- [x] `frontend/src/content/auth.ts` -- 해제 엔드포인트 둘, 표지(취소·실패·성공)를 닫힌 집합 세 곳에 함께, 대화상자 카피(대상·비가역 결과·보존되는 데이터·마지막 수단 설명)와 카피 표류 방지 문자열. -- AC-1·AC-4.
- [x] `frontend/src/session/unlinkProvider.ts` + `useUnlinking.ts` -- 준비·실행 호출과 진행 상태를 `linkProvider.ts`·`useLinking.ts`와 같은 모양으로 만든다(CSRF 헤더·`same-origin`·시한·중복 제출 가드·실패 시 표지). -- AC-8.
- [x] `frontend/src/components/UnlinkDialog.tsx` (+ `.module.css`) -- 확인 대화상자. 기본 포커스는 취소, 포커스 가둠, 닫을 때 호출점 복귀, `confirmation-dialog` 토큰만 사용. -- AC-4. DESIGN.md가 규정한 형태다.
- [x] `frontend/src/screens/SettingsScreen.tsx` (+ `.module.css`) -- 연결된 행에 해제 버튼, 수단이 하나면 비활성과 "마지막 접근 수단" 설명, 대화상자 연결, 표지 렌더. -- AC-1·AC-8.
- [x] `frontend/src/components/UnlinkDialog.test.tsx` + `frontend/src/screens/SettingsScreen.test.tsx` + `frontend/src/test/setup.ts` -- 대화상자의 기본 포커스·가둠·복귀·취소 무변경, 수단 1개일 때 비활성과 설명, 해제 요청의 토큰 헤더·중복 제출 차단, 표지별 문장, 저장소 미사용. 새 엔드포인트용 stub 헬퍼를 더한다. -- 화면 축은 이 층이 소유한다.
- [x] `frontend/e2e/auth-entry.spec.ts` -- 게이트 안쪽에서 로그인·연결 뒤 `/settings`를 열어 해제 대화상자의 axe WCAG 2.2 AA 0위반·최소 44×44px·320px 넘침 없음·키보드 도달과 취소 복귀를 단정한다. -- AC-4의 기하·접근성을 보는 유일한 층이다.
- [x] `deploy/compose/verify-same-origin.sh` -- 해제 왕복 절(연결 둘 → 재인증 → 준비 204 → 실행 → 매핑 1행 → 세션 회전 → 다른 세션 폐기)과 거부 탐침(마지막 수단·재인증 없음·CSRF·미지 제공자)을 더한다. 완주 가드 형태를 따르고 `set -o pipefail`에서 `생산자 | grep -q`를 쓰지 않는다. -- HTTP 층에서 해제 계약을 보는 유일한 자리다.
- [x] `DEVELOPMENT.md` + `_bmad-output/implementation-artifacts/epic-1-context.md` -- 해제 표면·마지막 수단 보호의 위치·세션 정리 정책을 적고, `## Goal`을 Epic 1 완료로 맞춘다. `CLAUDE.md`는 건드리지 않는다. -- 같은 사실이 두 곳에서 갈리지 않게 한다.

**Acceptance Criteria:**

- Given `develop`의 Story 1.1~1.10 계약, when 이 스토리의 변경을 얹으면, then `GET /api/nope` 404·`POST /api/status` 405·`POST /api/auth/session` 403·반복 로그아웃 403·오류 본문 전문 동등성·보안 헤더·캐시 헤더·세 제공자 로그인 왕복·재인증 왕복·연결 왕복이 모두 그대로 통과한다.
- Given 수단 하나만 연결된 계정, when API를 직접 호출해 그 수단의 해제를 시도하면, then `identity_social_link`의 행 수가 그대로이고 세션도 그대로다.
- Given 수단 둘을 동시에 해제하는 두 요청, when 둘 다 커밋을 시도하면, then 정확히 하나만 성공하고 남은 행이 1개 이상이다.
- Given 해제 성공, when 제거된 제공자로 로그인하면, then 기존 `user_id`의 세션이 열리지 않고 그 계정의 매핑도 늘지 않는다. 남은 수단으로는 같은 `user_id`로 로그인된다.
- Given 해제 성공, when 그 사용자의 세션을 검사하면, then 현재 세션 하나만 남고 그 ID는 커밋 전과 다르다.
- Given 최종 확인을 하지 않고 이탈한 흐름, when 저장소를 검사하면, then 매핑·세션·연결 목록이 준비 전과 같다.
- Given 해제가 거부되는 모든 경우, when 호출자가 응답을 관찰하면, then 마지막 수단·허가 만료·세션 불일치·삭제 상태·미연결이 서로 구분되지 않는다.
- Given 확인 대화상자, when 키보드만으로 조작하면, then 기본 포커스가 취소에 있고 포커스가 대화상자 안에 갇히며 닫을 때 호출 버튼으로 돌아오고 axe WCAG 2.2 AA 위반이 0건이다.
- Given 해제된 계정, when `erasePayload`가 실행되면, then 남은 매핑이 사라지고 다른 사용자의 매핑은 남는다.
- Given 새 마이그레이션, when `db/migration/` diff를 보면, then 변경 0건이다.

## Spec Change Log

- 2026-09-25 첫 구현. frozen 블록은 바꾸지 않았고 설계 결정도 뒤집지 않았다. 아래 아홉은 구현 중에 확정한 것이다.

  - **마지막 수단 보호의 술어가 "남은 수단 2개 이상"이다.** `DELETE … WHERE user_id = :userId AND provider = :provider AND EXISTS(생명주기가 ACTIVE) AND (SELECT count(*) … WHERE user_id = :userId) > 1` 한 문장이며, PostgreSQL의 DELETE가 자기 서브쿼리를 삭제 이전 스냅숏에서 읽으므로 "지우기 전에 둘 이상"이 곧 "지운 뒤 하나 이상"이다. 잠금과 술어 **둘 다** 필요하다 — 잠금만으로는 서로 다른 트랜잭션의 스냅숏이 갈리고, 술어만으로는 잠금 없는 동시 실행에서 두 DELETE가 같은 스냅숏을 본다. `AccountConcurrencyTest`의 대기열이 그 둘을 함께 관측한다.
  - **등록 구성 게이트를 두지 않았다.** 연결 시작은 그것을 보아야 했지만(뒤따르는 인가 시작이 fail-closed로 끝나면 참조할 수 없는 허가가 남는다) 해제에는 그 왕복이 없다. 오히려 **자격정보가 빠진 제공자일수록 떼어 낼 수 있어야 한다** — 구성이 사라졌다는 이유로 이미 연결된 수단이 영구히 붙박이가 되면 그것이 계정을 가두는 또 다른 길이다.
  - **`UnlinkFlow`에 `boundSessionId`가 없다.** 준비와 확인 사이에 세션 고정 방어가 끼지 않으므로 발급 시각의 세션 id와 소비 시각의 live id가 같고, 1.9 포트의 기본 생성자가 그대로 성립한다. 두 흐름의 차이가 record의 필드 유무로 읽힌다.
  - **세션 정리의 순서가 계약이다.** 폐기가 먼저이고 회전이 나중이다 — `changeSessionId`는 요청 끝에야 저장소에 반영되므로 회전 뒤에 `session.getId()`를 읽으면 저장소에 없는 새 id가 나오고, 저장소에 남은 옛 id의 행이 "다른 세션"으로 분류돼 함께 지워진다. 그러면 방금 해제한 사용자가 그 자리에서 로그아웃된다.
  - **`SessionGuard`의 "비었으면 끝"을 찾은 수가 아니라 대상 수로 판정한다.** 남길 세션이 있으면 `findByPrincipalName`은 결코 비지 않으므로, 찾은 수로 보면 경합 재확인 순환이 언제나 두 번 돈다.
  - **해제 결과 표지 셋을 `URL_NOTICE_KINDS`에 넣지 않았다.** 해제는 문서를 떠나지 않아 표지를 주소에 실을 이유가 없고, 넣으면 어떤 서버 경로도 만들지 않는 문장을 주소창이 만들어 낸다. `signedOut`이 같은 이유로 그 목록 밖에 있다 — "조용히 무시되는 값이 없다"는 목적은 그대로다: 세 값의 소비자는 화면의 상태 하나이고 타입이 그 연결을 강제한다(`UnlinkNoticeKind`).
  - **`<dialog>`를 쓰지 않았다.** `showModal`의 동작(`::backdrop`·inert·`Esc`)은 브라우저마다 구현 시점이 다르고 시험 환경에서는 절반만 산다. 필요한 넷(기본 포커스, 포커스 가둠, `Esc`, 호출점 복귀)을 직접 들고 있는 편이 어느 층에서도 같은 동작을 보장한다.
  - **e2e가 스텁 `subject`를 고정한다.** 스텁은 요청마다 새 `subject`를 만들므로 같은 제공자로 두 번 왕복하면 두 번째는 다른 사람이고 재인증이 **조용히** 실패한다(성공과 같은 화면으로 수렴한다). 검증 스크립트가 쓰는 것과 같은 `dev_subject`를 동의 화면에서 붙여 다시 연다. `page.route`의 `continue({url})`은 이 경로에서 반영되지 않아 쓰지 않았다.
  - **검증(전량 실측, Docker 있음).** 백엔드 `clean build` **371** 시험 통과·경고 0건(`-Werror`)·jar 생성(1.10의 323에서 48 증가), `deploy/compose/verify-same-origin.sh` **823/823 exit 0**, `PLAYWRIGHT_BASE_URL` 주입 E2E **52/52**, preview E2E 38 통과·14 건너뜀, 프런트엔드 전 검사(`ci`·typecheck·ESLint·stylelint·`format:check`·build·Vitest **242**), `bash .github/scripts/test-ci-scripts.sh` 147/147.

- 2026-09-25 적대적 리뷰 세 층의 판정 16건. frozen 블록은 바꾸지 않았고 설계 재작업도 없다. 앞의 여섯은 상태가 실제로 어긋나던 자리이고, 다음 일곱은 대화상자의 접근성, 나머지는 검증이 아무것도 관측하지 않던 자리다.

  - **거절되는 시작 요청 하나가 살아 있는 무장과 그 허가를 파괴했다.** 무장을 버리는 일이 게이트보다 앞에 있어, 이미 연결된 제공자로 연결을 누르거나 마지막 수단의 해제를 누르면 403과 함께 진행 중이던 다른 흐름이 사라졌다 — 같은 파일이 `대상이 다른 확인은 무장을 없애지 않는다`로 정확히 그 반대를 계약으로 두었는데 규칙이 갈렸다. 이제 버리는 일이 **언제나 게이트 뒤**다: 각 `start`는 허가 발급이 성공한 뒤에 앞선 무장을 대신하고(`supersedePreviousArming`, 같은 대상이면 허가를 다시 무효화하지 않는다 — 방금 발급한 것이 그 술어에 걸린다), 상대 서비스의 흐름은 컨트롤러가 그 성공을 보고 버린다. 네 축을 시험이 고정한다(연결·해제 각각이 자기 무장과 상대 무장을 지킨다).
  - **최종 확인이 무장을 허가 소비보다 먼저 버렸다.** `UnlinkRejected`가 아닌 예외(데이터베이스 오류, 포트의 배선 실수)가 나면 무장만 사라지고 허가는 남아 아무도 참조할 수 없는 행이 수명을 산다. 이제 확인은 무장을 **지우지 않는다** — 사라지는 길은 다음 무장이 대신하거나, 다른 흐름이 버리거나, 수명이 다하는 셋이고, 일회성은 무장이 아니라 허가가 갖는다(조건부 UPDATE 한 문장). `UnlinkFlow`에 `consume`이 없는 이유가 그것이다.
  - **그 결과로 멱등 계약이 HTTP 표면에서 도달하게 됐다.** 무장이 남으므로 같은 확인을 다시 보낼 수 있고 허가의 저장된 결과가 답한다. 다만 **커밋 뒤 세션 정리는 다시 돌지 않는다** — 업무 변경이 일어나지 않았는데 세션을 또 폐기·회전시키면 "커밋 뒤에만 정리한다"가 깨진다. 처음 소비와 재전송을 가르는 재료는 하나뿐이라(허가 포트가 둘을 같은 타입으로 답한다) 포트에 건넨 작업이 불렸는지를 상자 하나로 관측한다. 회전 여부는 `Set-Cookie`로 밖에서 보이므로 시험이 그것을 단정한다.
  - **커밋 뒤 세션 정리가 실패하면 클라이언트가 해제를 실패로 읽었다.** 매핑은 이미 커밋됐으므로 예외를 올리면 화면이 "해제하지 못했다"를 말하면서 실제로는 해제된 상태가 된다 — 그 불일치가 정리 실패보다 나쁘다(`ActionGrantService.sweep`과 같은 판단). 이제 삼킨다.
  - **화면이 "다른 기기의 로그인 상태는 모두 종료됐습니다"라고 말했다.** 폐기는 조회와 삭제 사이에 커밋된 로그인을 놓칠 수 있으므로 그 문장은 드물게 거짓이다. 결과 표지와 대화상자 카피 모두 사용자가 할 일을 말하도록 고쳤다 — "다른 기기에서는 다시 로그인해야 합니다"는 어느 경우에도 참이다.
  - **취소가 서버에 알려지지 않았다.** 준비가 만든 무장과 허가가 5분을 그대로 살았고, 그동안 화면은 "바뀐 것은 없습니다"라고 말했다. 표면을 하나 더 열었다(`POST /api/auth/unlink/<제공자>/cancel`). SPEC Code Map의 "엔드포인트 둘"에서 하나 늘어난 것이며 근거는 대칭이다 — 준비가 서버 상태를 만드는 설계라면 되돌리는 경로가 있어야 하고, 그 문장이 참이어야 카피도 참이다. 무장 유무로 답을 나누지 않는 멱등 204다(나누면 "저 세션이 무엇을 준비했는가"를 묻는 오라클이 된다). 클라이언트는 결과를 기다리지 않는다 — 취소는 즉시 닫혀야 하고 실패해도 사용자가 할 일이 없다.
  - **겹 클릭 한 번으로 포커스 가둠과 `Esc`가 무력화됐다.** 두 처리기가 겹 `<div>`의 `onKeyDown`에 있었는데 겹은 포커스를 받을 수 없어, 그것을 클릭하면 포커스가 `<body>`로 떨어지고 이후 keydown이 React 루트에 닿지 않는다. 방어를 둘로 뒀다 — `document`의 capture 단계 처리기(포커스가 밖에 있으면 다음 `Tab`에 안으로 끌어온다)와 겹의 `onMouseDown` 기본 동작 차단(포커스를 잃는 것 자체를 막는다). 처리기를 대화상자로 좁히는 변이가 실제로 실패한다.
  - **배경이 비활성화되지 않았다.** `inert`도 스크롤 잠금도 없어 스크린리더 가상 커서가 뒤 화면을 읽고 문서가 스크롤됐으며, `busy`에 대화상자 개폐가 빠져 있어 모달 뒤의 연결·본인 확인·로그아웃이 눌렸다 — 그것들은 열려 있는 해제 무장을 버리므로 사용자가 확인을 누르는 순간 이유 없이 실패한다. 대화상자를 화면의 **형제**로 옮겨 `inert`를 걸고(안에 두면 그 속성이 대화상자까지 덮는다) 문서 스크롤을 잠갔으며, 머리말의 로그아웃도 다른 조작과 같은 가드를 지난다(`ReauthPanel`과 같은 배치).
  - **해제 성공 뒤 포커스 이동이 조건에 걸려 있었다.** `activeElement === body` 가드는 대화상자가 닫히는 시점에 호출점 버튼이 아직 있으면 거짓이 된다. 조건을 성질로 갈랐다 — 성공에서만 화면이 결과 문장으로 옮기고(`restoreFocus=false`), 취소·실패에서는 대화상자가 호출점으로 돌려준다. 실제 브라우저에서는 낙관적 목록 갱신이 같은 커밋에서 그 버튼을 지우므로 두 형태가 같은 결과를 내고, 그래서 이 변이는 관측되지 않는다 — 그럼에도 조건 없는 쪽을 남기는 이유는 그 일치가 우연이기 때문이다.
  - **해제 결과 표지가 `role="status"` 영역 밖에 있었다.** 취소·실패에서는 포커스도 움직이지 않아 보조기술 사용자에게 아무 신호가 없었고, `content/auth.ts`의 주석은 이미 "`role="status"`로 알린다"를 근거로 들고 있었다. 화면당 하나뿐인 live region이 그 문장을 말하게 옮겼다.
  - **비활성 상태에 시각 표현이 없었다.** `.unlinkButton`·`.cancelButton`·`.confirmButton`에 `[aria-disabled='true']` 규칙을 더했다(`SignOutButton.module.css`의 관용). 특히 마지막 수단의 해제 버튼은 일시적 pending이 아니라 계속 비활성인데 활성 버튼과 픽셀 단위로 같았다.
  - **목록이 낡은 동안 이미 해제된 제공자의 버튼이 살아 있었다.** 재조회가 돌아오기 전에 누르면 곧바로 403 실패 표지가 난다 — 방금 해제한 것을 다시 해제하려다 실패를 보는 상태다. 서버가 무엇을 지웠는지는 화면이 이미 알고 있으므로 그 수단을 먼저 목록에서 뺀다.
  - **다른-기기 세션 검증 루프에 완주 가드가 없었다.** 목록이 비면 뒤따르는 순환이 단정을 하나도 기록하지 않고 지나갔다. 만든 수를 세어 단정하고, 왕복의 세 단계마다 상태를 단정한다 — 하나가 실패하면 세션 수 단정이 줄줄이 어긋나고 그때 원인이 "해제가 세션을 잘못 셌다"로 잘못 읽힌다.
  - **거부 탐침 하나가 빠졌고 대상 계정을 내용으로 추측했다.** "재인증 없음"은 방금 만든 기기 세션으로 본다 — 같은 사용자·같은 두 수단이면서 본인 확인만 지나지 않았으므로 그 게이트 하나만 남는다. 확인·취소 경로의 405도 더했다. 대상 계정은 `HAVING count(*) = 2`가 아니라 연결 왕복이 쓴 식별자로 찾는다(그런 계정이 둘이 되는 날 `psql_value`가 여러 줄을 돌려주고 이후 단정이 조용히 빗나간다). 서로 모순되던 주석 두 줄도 하나로 고쳤다.
  - **`protectedHeaders`가 통합이 아니라 네 번째 사본이었다.** 로그아웃·재인증 시작·연결 시작의 같은 `(Origin, CSRF)` 조립을 그 헬퍼 하나로 모았다. `UnlinkDialog.test.tsx`가 카피로 정규식을 만들던 자리는 세 문단 전문 비교로 바꿨다 — `toHaveAccessibleDescription`의 문자열 인자는 전문 비교이고, 정규식으로 만들면 카피의 마침표가 메타 문자가 되어 단정이 조용히 느슨해진다.

  - **변이 확인.** 고친 축마다 직접 확인했다 — 마지막 수단 술어를 `> 0`으로 무르고(4건 실패), 해제 준비의 상대 흐름 정리를 게이트 앞으로 되돌리고(1건), 연결 시작의 같은 정리를 앞으로 되돌리고(1건), 재전송에서도 세션을 정리하게 하고(1건), 확인이 무장을 버리게 하고(2건), 취소를 no-op으로 만들고(1건), 남길 세션 제외를 지우고(4건), 최소 수단 사전 확인을 지우고(1건), `busy`에서 대화상자 개폐를 빼고(1건), 낙관적 목록 갱신을 지우고(1건), 취소 요청을 지우고(1건), 결과 표지를 live region 밖으로 옮기고(1건), 대화상자의 키 처리기를 겹으로 좁힌 뒤(e2e 1건) 각각 원복했다. 관측되지 않은 하나는 위에 적은 성공 포커스의 조건이며 그 이유는 구조적이다.
  - **검증(전량 실측, Docker 있음).** 백엔드 `clean build` **372** 시험 통과·경고 0건(`-Werror`)·jar 생성(리뷰 전 371에서 1 증가), `deploy/compose/verify-same-origin.sh` **823/823 exit 0**, `PLAYWRIGHT_BASE_URL` 주입 E2E **52/52**, preview E2E 38 통과·14 건너뜀, 프런트엔드 전 검사(typecheck·ESLint·stylelint·`format:check`·build·Vitest **248**), `bash .github/scripts/test-ci-scripts.sh` 147/147. 수동 검사도 통과했다 — `git diff --stat develop -- db/migration/ deploy/nginx/ frontend/src/App.tsx`가 변경 0건, `grep -rn "issuer\|subject" frontend/src`가 주석과 음성 단정 fixture 외 0건, 실행 중인 스택의 `docker compose logs`에 `issuer`·`subject`·`grant_id`·`confirmation_hash`·`unlink-provider`·`bokrilog.auth.unlink`·`dev_subject`·`unlinkSucceeded` 0건(해제 경로는 access log에 경로로만 26건 남고 쿼리는 없다).

## Design Notes

**마지막 수단 보호가 삭제 문장 안에 있어야 하는 이유.** "먼저 세고 1개면 거절, 아니면 지운다"는 두 문장 사이에 다른 요청이 들어오면 둘 다 통과해 계정이 잠긴다. `identity`는 이미 그 함정을 연결 쪽에서 피했다 — `insertSocialLinkIfActive`가 상태 술어를 INSERT 안에 넣고 유일성은 인덱스에 맡긴다. 해제도 같은 모양이며, 생명주기 행 잠금이 같은 사용자의 동시 해제를 직렬화하므로 두 번째 요청은 "남은 수단 2개 이상"을 만족하지 못해 0행으로 끝난다. 잠금과 술어 둘 다 필요하다 — 잠금만으로는 서로 다른 트랜잭션이 본 스냅숏이 갈리고, 술어만으로는 잠금 없는 동시 실행에서 둘 다 통과한다.

**세션 정리는 AC-7이 준 두 갈래 중 fail-safe 쪽이다.** 1차 갈래는 "대상 identity에서 유래한 세션만 폐기"인데 그러려면 세션이 어떤 제공자로 인증됐는지를 기록해야 한다. 그 기록은 CLAUDE.md의 불변식("세션이 담는 값은 내부 `user_id` 하나이며")을 깬다. AC-7은 그 경우를 예상하고 "세션 출처를 안전하게 판정할 수 없으면 현재 세션 외의 세션을 모두 폐기한다"를 함께 정했다. 그래서 이 스토리는 불변식을 지키고 더 강한 정리를 한다 — 사용자는 다른 기기에서 다시 로그인해야 하고, 그것이 해제라는 조작의 무게에 맞는 대가다.

**확인 대화상자가 OIDC 왕복의 자리를 대신한다.** 연결은 대상 제공자의 왕복이 있어 허가가 그 창을 건너야 했고, 세션이 반드시 회전하므로 귀속 세션 id를 흐름 record가 들고 있어야 했다. 해제에는 왕복이 없다 — 창은 "대화상자가 열린 뒤 사용자가 확인을 누르기까지"이고 그 사이 세션은 회전하지 않는다. 그래서 `UnlinkFlow`에는 `returnPath`도 `boundSessionId`도 없고 1.9 포트의 기본 생성자를 쓴다. 두 흐름의 차이가 그 두 필드의 유무로 읽힌다.

**세 흐름이 서로를 버린다.** 1.10이 연결·재인증 둘을 서로 버리게 만들었고(설정 화면이 두 버튼을 같은 페이지에 그리기 때문이다) 해제가 세 번째다. 같은 화면에 연결·본인 확인·해제가 함께 있으므로 무장이 겹칠 수 있고, 겹치면 콜백이나 확인이 엉뚱한 흐름을 소비한다. 무장 시 나머지 둘을 버리고 그 허가도 함께 무효화하는 것이 그 화면에서 성립하는 유일한 규칙이다.

**마지막 수단만 화면이 미리 설명한다.** 다른 거부는 전부 하나의 값으로 접지만 마지막 수단은 다르다 — 사용자가 이미 연결 목록에서 아는 사실이고, 그것을 숨기면 비활성화된 버튼에 이유가 없어 접근성이 깨진다. 서버 응답은 여전히 구분하지 않는다(API 직접 호출자는 아무것도 새로 배우지 못한다). 설명은 화면이 자기가 아는 목록에서 만든다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend clean build` -- expected: 해제·마지막 수단·동시성·세션 정리 시험 통과, 경고 0건(`-Werror`), jar 생성. Docker 데몬 필요
- `npm --prefix frontend ci && npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run build && npm --prefix frontend run test` -- expected: 전부 통과, 경고 0건
- `npm --prefix frontend run test:e2e` -- expected: Story 1.1·1.4~1.10 회귀 통과
- `deploy/compose/verify-same-origin.sh` -- expected: 해제 왕복 절 포함 모든 단정 통과, exit 0
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 전부 통과
- `git diff --stat develop -- backend/src/main/resources/db/migration/ deploy/nginx/ frontend/src/App.tsx` -- expected: 변경 0건

**Manual checks:**
- `grep -rn "issuer\|subject" frontend/src` -- expected: 주석 외 0건
- 실행 중인 스택의 `docker compose logs`에서 `issuer`·`subject`·허가 참조·세션 ID 검색 -- expected: 0건
- 브라우저에서 확인 대화상자의 기본 포커스·`Esc` 닫기·호출점 복귀 -- expected: 취소에 포커스, 닫으면 해제 버튼으로 복귀
