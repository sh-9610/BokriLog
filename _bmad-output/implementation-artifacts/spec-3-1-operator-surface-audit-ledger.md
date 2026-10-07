---
title: 'Story 3.1 — 보호된 운영자 표면과 추가 전용 감사 원장'
type: 'feature'
created: '2026-10-07'
status: 'draft'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Epic 3의 모든 운영 command(제한·자동 정지·복구·공지)가 지나갈 기반이 없다 — 운영자 권한 모델, 일반 사용자와 분리된 route·API, 표준 command envelope, 추가 전용 감사 원장과 그 조회, 감사 데이터의 삭제·보존 분류. `operations` 모듈은 판독 전용 `isRestricted(command)` 하나(`operations_restriction`, scope 축 없음)뿐이고, 운영자 allowlist 설정 키·감사 테이블·운영자 화면은 존재하지 않는다(`epics.md` Story 3.1, AC-1~8).

**Approach:** 운영자는 배포 환경의 `(issuer, subject, 권한)` allowlist로만 정해지고 요청마다 `identity` 공개 API로 `userId`로 풀어 세션 주체와 비교한다(세션에 역할을 심지 않는다 — 회수가 즉시 반영된다). 비운영자·미존재에는 `/api/operator/**` 전부 같은 404다. 운영 command는 Epic 1의 재인증·일회성 허가·세션 회전과 PR #71의 공통 부품(`SessionArming`·`SessionRotation`·`IdempotencyKey`)을 그대로 쓰는 준비→확정→취소 세 경로이고, envelope(불투명 operation ID·actor·command·scope·target·사유·확인 hash·정책 버전·요청/결정 시각·멱등 키)를 `operations` 모듈이 검증한다. 확인 hash는 서버가 준비 시 정한 확인 문구를 확정이 다시 제시해 판별력을 가진다(Epic 1 item-7의 운영 command 쪽 답). 감사 원장 `operations_audit_event`(V17)는 요청·승인·거절·실패·완료를 한 행씩 추가만 하고, 조회는 안정 cursor와 allowlist 필드만 돌려주며, 대량 추출(`audit-export`)은 이 스토리의 유일한 실제 command로서 envelope 전체를 지나 별도 감사 사건이 된다. 화면 `/operator`는 `React.lazy`로 분리된 첫 chunk이며 다섯 곳(서버 목적지·세션 목적지 맵·복귀 목적지·Nginx·`App.tsx`)에 더하되 복귀 목적지로는 운영자에게만 허용된다.

## Boundaries & Constraints

**Always:**
- 운영자 판정 입력은 설정 `bokrilog.operations.operators`(env `BOKRILOG_OPERATORS`, `issuer|subject|permissions;…`, permissions는 `command`·`audit` 조합) 하나다. 비면 운영자가 없고 모든 운영 요청이 404다(fail-closed, ERD §11 182). 형식 오류는 기동 거부다. 사용자 UI로 승격하는 경로를 만들지 않는다.
- `/api/operator/**`는 `AuthSecurityConfig.requiresAuthentication`과 `isProtectedRequest`에 판정 함수 하나로 함께 들어간다(인가·CSRF·`Origin`이 같은 술어). 미인증은 기존 401 계약, 인증된 비운영자·권한 부족·미존재 대상은 모두 같은 404 본문이다.
- 두 번째 인증 경로·두 번째 허가 저장소를 만들지 않는다. 운영 command의 준비는 `ActionGrantService.issue`, 확정은 `consume`의 Supplier 안에서 업무 변경과 감사 행을 같은 트랜잭션에 쓰고, 최종 권한(allowlist 재해석·`AccountAccessApi.decide(SENSITIVE)`)을 그 트랜잭션 안에서 다시 본다. 실제 실행된 요청만 `SessionRotation.completeSensitiveCommand`.
- 감사 원장의 public API는 추가와 조회뿐이다. 수정·삭제 API를 두지 않고, repository의 SQL은 INSERT·SELECT와 삭제 port의 비식별 UPDATE 한 문장뿐이며 시험이 SQL 문자열을 훑어 강제한다. 행에는 API 비밀·세션 id·금융 상세·이메일·원식별자를 넣지 않는다 — actor는 내부 `userId`와 keyed digest(`HMAC-SHA256`, 키는 설정 `bokrilog.operations.audit-digest-key`, 비면 기동 거부)다.
- 오류 응답은 공통 `{code,message,correlationId}` 한 모양. URL·로그·metric label에 actor·target 원식별자, 금융값, 토큰·허가 참조를 넣지 않는다.
- 금융 계산은 없다. 운영 제한의 실제 생성·자동 정지·복구·공지는 하지 않는다(3.2~3.5).
- 화면: `복리 계획 비교`·고지 인접 배치, 일반 사용자와 구분되는 제목, 조작 대상 44px, 확인 대화상자는 `useConfirmationDialog`를 쓰고 취소가 기본 포커스, 전역 대상은 대상명 재입력. 외부 요청 0건, 브라우저 저장소 금지. 라우터·인증 SDK·런타임 의존 추가 없음(`React.lazy`는 React 내장).
- 새로 쓰는 주석·카피는 한국어. 신규 마이그레이션은 `V17__operations_audit.sql` 하나이고 적용된 파일은 고치지 않는다.

**Ask First:**
- 감사 행에 DB 트리거로 UPDATE/DELETE를 막는 것(삭제 port의 비식별 UPDATE와 충돌하므로 이번에는 두지 않는다).
- `/operator`를 복귀 목적지에서 빼는 것(빼면 재인증 뒤 한 번 더 이동해야 한다 — `deferred-work.md` 192).
- 법정 분리 보존 필드·기간을 정하는 것(G-5 전 금지. 개발·CI 기본값은 "없음").

**Never:**
- `operations_restriction`에 scope 축을 더하는 것(3.2 SPEC 입력, 회고 AI-5). `identity.internal`·타 모듈 테이블 직접 접근. 이중 승인 흉내. 외부 로그를 감사 원장으로 쓰는 것. `OPERATOR_INVESTIGATION` 정밀 검증의 호출자 추가(회고 AI-2는 PM 결정 대기). 운영자 제어를 일반 번들 초기 화면·탐색·DOM에 섞는 것.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 비운영자 | 인증된 일반 사용자가 `GET /api/operator/audit` | 404 공통 본문(권한 유무·대상 존재 비노출) | 미인증은 401 |
| 권한 분리 | `audit`만 가진 운영자가 command 준비 / `command`만 가진 운영자가 감사 조회 | 둘 다 404 | N/A |
| 준비 | 운영자, 재인증 5분 안, `POST /api/operator/commands/audit-export`(scope·target·사유·멱등 키) | 200 `{operationId, confirmationPhrase, policyVersion}`, 허가 발급, 세션 무장, 감사 `REQUESTED` | 빈 사유·미지원 scope·body에 actor 주장·멱등 키 형식 오류 → 400/422 공통 code; 재인증 없음 → 403 `REAUTHENTICATION_REQUIRED` |
| 확정 | `/confirm`에 같은 멱등 키·확인 문구 일치 | 200 결과, 허가 소비·실행·감사 `COMPLETED` 한 트랜잭션, 세션 회전, 재인증 시각 제거 | 문구 불일치 → 허가 미소비·`DENIED` 감사·409; 같은 키 재전송 → 저장 결과, 회전 없음 |
| 회수 경합 | 준비 뒤 확정 전에 allowlist에서 제거(설정 재해석 가능한 시험 훅) | 확정 트랜잭션 안 재검사로 거절, 감사 `DENIED`, 상태 변경 없음 | 404 |
| 취소 | `/cancel` | 무장 폐기·허가 참조 무효화, 감사 `CANCELLED` 행 | 무장 없음도 204 |
| 감사 조회 | `GET /api/operator/audit?from&to&command&scope&outcome&cursor&limit` | allowlist 필드만, `(recordedAt, sequence)` keyset cursor, 최대 200행 | 잘못된 cursor → 400 |
| operation 조회 | `GET /api/operator/operations/{operationId}` | 그 operation의 감사 행 시간순 | 남의·미존재 → 404 |
| 삭제 분류 | `OperationsDeletionApi.eraseActor(userId)` | 그 사용자의 actor 행만 `actor_user_id` NULL + `actor_erased_at`, digest·사실 유지; 타 사용자·`SYSTEM` 행 불변 | 멱등 |

</frozen-after-approval>

## Code Map

- `_bmad-output/planning-artifacts/epics.md:2772-2855` -- Story 3.1 본문·AC-1~8·테스트 기준. 221-235 Epic 3 preamble.
- `backend/src/main/java/com/bokrilog/operations/` -- `OperationalRestrictionApi`(`isRestricted`), `internal/OperationalRestrictionService`·`Repository`, `package-info`. 테이블 `operations_restriction`(V4:183-200). 현재 최고 마이그레이션 **V16**.
- `AuthSecurityConfig.java` -- 인가 983-1007, `requiresAuthentication` 1216-1219, `isProtectedRequest` 1183-1203(세 곳이 공유: CSRF matcher 1062, `SameOriginFilter` 1081, 세션 guard), 401/403 1071-1079, `@EnableConfigurationProperties` 75-78, `AuthProperties` 355-393. `application.yml:156-` 설정 키 모음.
- 세션 주체: `AuthFlowHandlers.writeEstablishedPrincipal` 835-846(`userId`만), `verifiedIdentityOf` 896-913(issuer·subject는 콜백 순간만). `AuthSessionController.currentUserId` 580. 복귀 목적지 `ALLOWED_DESTINATIONS` `AuthFlowHandlers:111`, 재인증 시작이 목적지를 정하는 자리는 `AuthSessionController`의 reauthenticate 처리부.
- identity 공개 API: `AccountLifecycleApi.findUserIdBySocialIdentity(issuer, subject)`:112, `AccountAccessApi.decide(caller,target,SENSITIVE)`:28·`lockWriteAccess`:35, `AccountDeletionApi.erasePayload`:46.
- 허가·공통 부품: `ActionGrantService.issue`:213·`consume`:308-346(소비+업무 변경 같은 트랜잭션, 열린 트랜잭션에서 부르면 443 예외)·`invalidateReference`:253, `ActionGrant` 길이 상한 87-107·`canonicalConfirmationHash`:163, `SessionArming`(`arm/peek/consume/discard`), `SessionRotation.completeSensitiveCommand`:34, `sync.IdempotencyKey`. 준비/확정/취소 선례: `PlanEndFlow`·`PlanEndCommandService`, `BybitRevocationFlow`·`BybitRevocationCommandService`(문구 대조 203).
- 동시 세션: `SessionGuard.revokeSessionsOfExcept`:188.
- 모듈 경계: `ModuleBoundaryTest.java:50` 모듈 목록에 `operations` 있음; 모듈은 servlet·spring-web 금지 → HTTP·세션·허가 배선은 루트, envelope 검증·감사·조회는 `operations`.
- 프런트엔드: `App.tsx:24-40` if-체인(lazy 0건), `useSessionState.ts:35` `SessionDestination`·85-89 `DESTINATION_PATHS`, `content/auth.ts:166` `RETURN_DESTINATIONS`, Nginx `deploy/nginx/bokrilog.conf:234`. 토큰 `tokens.css:235-237` `--component-operator-control-*`. UX 근거 `DESIGN.md`·`EXPERIENCE.md`(operator-control). 대화상자 메커니즘 `components/useConfirmationDialog.ts`, 조회 엔진 `session/useReferencedRequest.ts`.
- 회고 입력: item-7(확인 hash) `sprint-status.yaml:141`; AI-5(scope)는 3.2 입력; AI-2(`OPERATOR_INVESTIGATION`) 보류.

## Tasks & Acceptance

**Execution:**
- [ ] `db/migration/V17__operations_audit.sql` -- `operations_audit_event`(`audit_id uuid PK`, `sequence bigint generated always as identity unique`, `operation_id text`, `actor_user_id uuid NULL`, `actor_digest text`, `actor_kind text CHECK OPERATOR|SYSTEM`, `command text`, `scope text`, `target text`, `reason text`, `outcome text CHECK REQUESTED|APPROVED|DENIED|FAILED|CANCELLED|COMPLETED`, `policy_version text`, `requested_at`, `decided_at NULL`, `correlation_id text`, `details jsonb`, `recorded_at timestamptz default statement_timestamp()`, `actor_erased_at NULL`; 인덱스 `(recorded_at, sequence)`, `(operation_id)`, `(actor_user_id)`) -- 추가 전용 권위 원본.
- [ ] `operations/OperatorCommandApi.java`·`OperatorAuditApi.java`·`OperationsDeletionApi.java`(public) + `internal/` 구현·repository -- envelope record와 검증(빈 사유·미지원 scope·client actor·길이), 감사 append, keyset 조회, operation 조회, `audit-export` 실행(최대 10,000행 JSON), `eraseActor`.
- [ ] `operations/OperatorProperties`(`bokrilog.operations.operators`·`audit-digest-key`) -- 파싱·기동 거부; `application.yml` 키와 compose env.
- [ ] 루트 `OperatorAccess.java` -- allowlist → `findUserIdBySocialIdentity` → 세션 주체 비교 + `decide(SENSITIVE)`; 권한별 판정; 시험용 재해석 훅.
- [ ] 루트 `OperatorCommandFlow.java`(`SessionArming`)·`OperatorCommandService.java`(issue → arm; consume Supplier 안에서 재검사·실행·감사; rotation)·`OperatorController.java`(`/api/operator/commands/{type}`·`/confirm`·`/cancel`, `/api/operator/audit`, `/api/operator/operations/{id}`) -- 공통 오류 계약, 404 통일.
- [ ] `AuthSecurityConfig` -- `/api/operator/` 판정 함수 하나를 `requiresAuthentication`·`isProtectedRequest`에; `AuthFlowHandlers.ALLOWED_DESTINATIONS`·재인증 목적지에 `/operator`(운영자일 때만, 아니면 `/dashboard`로 접음).
- [ ] 프런트엔드 -- `App.tsx` `/operator` → `React.lazy(() => import('./screens/OperatorScreen'))`; `screens/OperatorScreen.tsx`(제목·고지·감사 목록·필터·cursor 더 보기·`audit-export` 준비→확인 대화상자(`useConfirmationDialog`, 문구 재입력)→확정·취소·operation 조회), `session/useOperatorAudit.ts`(`useReferencedRequest` 아님 — 포인터 참조가 아니므로 자체 훅), `content/operator.ts`; `useSessionState`·`content/auth.ts` 목적지에 `/operator`; Nginx 허용 목록.
- [ ] 시험 -- 백엔드: `OperatorAccessTest`(비운영자 404·권한 분리·회수 즉시 반영), `OperatorCommandTest`(준비·확정·취소·문구 불일치·재전송·회수 경합·동시 두 탭 1회), `OperatorAuditTest`(append-only SQL 스캔·조회 cursor·allowlist 필드·export 감사 사건·operation 조회), `OperationsDeletionTest`(분류·비식별·타 사용자 보존), `ModuleBoundaryTest` 통과, 운영자 없는 설정에서 404; 프런트엔드: `OperatorScreen.test.tsx`·훅 시험·e2e `operator.spec.ts`(주입 origin: 운영자 로그인→재인증→export 왕복, 비운영자 404 화면, lazy chunk가 초기 로드에 없음, axe·44px); `verify-same-origin.sh`에 운영자 allowlist env와 왕복 단정.
- [ ] 문서 -- `CLAUDE.md`(엔드포인트·모듈·다섯 곳에 `/operator`·계약 한 줄), `DEVELOPMENT.md`(env), `deploy/compose/compose.yaml` env(dev-oidc 스텁 subject를 운영자로).

**Acceptance Criteria:**
- Given allowlist가 비어 있을 때, when 어떤 인증 사용자가 `/api/operator/**`를 부르면, then 전부 404 공통 본문이다.
- Given `command`·`audit` 권한을 모두 가진 운영자가 재인증 뒤 `audit-export`를 준비·확정하면, then 감사 원장에 `REQUESTED`·`COMPLETED` 두 행이 같은 operation ID로 있고, 응답은 allowlist 필드만의 행 묶음이며, 세션이 회전하고 재인증 시각이 지워진다.
- Given 확정 트랜잭션 중 allowlist에서 제거되면, then 거절되고 `DENIED` 행이 남으며 export 결과는 없다.
- Given 원장 repository 소스를 훑으면, then `UPDATE`는 `eraseActor` 한 문장뿐이고 `DELETE`는 0건이다.
- Given 초기 화면 로드를 e2e로 보면, then `OperatorScreen` chunk 요청이 없고 `/operator` 진입 때만 로드된다.
- Given `backend/gradlew --project-dir backend build`·프런트엔드 전체 검증·`verify-same-origin.sh`, then 모두 통과한다.

## Design Notes

- 역할을 세션에 심지 않는 이유: AC-6이 커밋 트랜잭션 안의 최종 권한 재검사를 요구한다. allowlist는 몇 건이라 요청마다 `(issuer, subject)` → `userId` 조회 비용이 무시할 만하고, 회수가 다음 요청에 바로 반영된다.
- `audit-export`가 3.1의 유일한 command인 이유: 실제 제한은 3.2 범위지만 envelope·허가·감사는 끝까지 지나는 command가 있어야 검증된다. AC-5가 대량 추출을 별도 감사 사건으로 정했으므로 export가 그 command다. 3.2는 같은 `OperatorCommandService`에 command type을 더하기만 한다.
- 확인 문구: 준비 응답이 `confirmationPhrase`(예: `감사 내보내기 {scope} {target}`)를 돌려주고, 허가의 확인 hash는 `canonicalConfirmationHash(command, target, phrase)`다. 확정은 사용자가 입력한 문구를 보내고 불일치는 허가 미소비다 — Epic 1 item-7이 운영 command에서는 닫히고, Epic 1·2 command의 hash 판별력은 ERD 정정 몫으로 남는다.
- 감사 행의 actor: `actor_user_id`(사용자 귀속, 삭제 시 NULL)와 `actor_digest`(keyed digest, 보존)를 함께 둔다. 분류 — OPERATOR 행은 "사용자 귀속 운영 데이터", SYSTEM 행은 "순수 운영 데이터", 법정 분리 보존 후보는 G-5 전까지 없음(개발·CI 기본).
- `/operator`를 복귀 목적지에 넣되 운영자에게만: 재인증 시작 시 서버가 운영자 여부를 보고 목적지를 정한다. 비운영자는 `/dashboard`로 접히므로 권한 유무가 목적지로 새지 않는다(둘 다 평범한 이동).
- 프런트엔드 운영자 화면은 포인터 참조 조회가 아니라 `useReferencedRequest`를 쓰지 않는다 — 대신 같은 늦은 응답·401/403 규칙을 자체 훅이 지킨다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 통과, 경고 0, 시험 수 ≥ 기준.
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run build && npm --prefix frontend run test && npm --prefix frontend run test:e2e` -- expected: 통과; `dist/assets/`에 OperatorScreen chunk가 별도 파일.
- `deploy/compose/verify-same-origin.sh` -- expected: 종료 코드 0, 운영자 왕복 단정 포함.
- `grep -n "UPDATE\|DELETE" backend/src/main/java/com/bokrilog/operations/internal/OperatorAuditRepository.java` -- expected: `eraseActor`의 UPDATE 1건.
