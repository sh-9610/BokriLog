---
title: 'Story 5.1 삭제 범위와 보존 정책을 확인하고 계정 삭제 요청하기'
type: 'feature'
created: '2026-11-05'
status: 'done'
baseline_commit: 'f5fba96e0ff8e083c5795452ac5ad6987a474bbe'
review_loop_iteration: 1
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 계정 삭제 경로가 사용자에게 열려 있지 않습니다. 지금 삭제는 리허설·복원 전용 `AccountErasure` 한 트랜잭션뿐이고, 사용자가 삭제 범위와 보존 정책을 확인하고 요청하는 표면도, 요청을 기록하는 자리도, 요청 즉시 접근을 막는 장치도 없습니다(FR-19, NFR-10·11, Story 5.1). 실제 모듈 삭제(5.2)와 백업 파기(5.3)는 이 요청 위에 섭니다.

**Approach:** 설정 화면 마지막에 `계정 삭제` 구역을 두어 서버가 내준 삭제 정책(즉시 차단, 운영 데이터 삭제 대상, 백업 30일 파기, 법정 분리 보존 없음)을 보여 주고 연결 해제와의 차이를 설명합니다. 삭제는 기존 민감 command 계약(최근 재인증 → 5분 일회성 허가 → 확인)을 따르는 `delete-account` command로 엽니다. 확정은 고정 문구 `계정을 삭제합니다` 재입력을 요구하고, 한 트랜잭션에서 삭제 요청 행(request ID·시작 시각·정책 version·고지 hash)과 `ACTIVE → DELETION_IN_PROGRESS` 전환을 원자로 만든 뒤 요청 세션을 뺀 그 사용자의 모든 세션을 폐기합니다. 요청 세션은 ID를 회전하지 않고(재인증 시각만 지운다) 남기되 삭제 상태 조회·같은 요청 재전송·로그아웃만 할 수 있는 상태 전용 세션이 되고, 화면은 어떤 경로든 삭제 진행 화면만 보여 줍니다. production(`BOKRILOG_ENV=production`)에서는 G-5 승인 전이므로 준비·확정을 열지 않습니다.

## Boundaries & Constraints

**Always:**
- 기존 공통 부품을 그대로 쓰고 사본을 만들지 않는다 — `SessionArming<T>`(무장), `ActionGrant`의 `issue`·`consume`·`canonicalConfirmationHash`·`normalize`, `SessionRotation`, `IdempotencyKey`, `ControllerSupport`(세션 사용자·SENSITIVE 판정·correlation id·no-store 오류), 프런트엔드 `useConfirmationDialog`·`session/http.ts`·`useReauthentication`.
- 허가 귀속은 기존 다섯 축(user·session·command·target·확인 hash)을 바꾸지 않는다. command는 `delete-account`, target은 `<userId>|<정책 version>`, 확인 문구는 서버 상수 `계정을 삭제합니다`이고 hash에는 그 문구가 들어간다. 확정 본문은 사용자가 **입력한** 문구를 싣고, NFC·strip 등가가 아니면 `CONFIRMATION_MISMATCH`로 거절하되 허가를 소비하지 않고 무장도 남긴다(운영자 command 선례).
- 정책은 서버 상수 하나(`AccountDeletionPolicy`, version `ACCOUNT-DELETION-1.0`)가 소유한다 — 삭제 대상 code 목록, 백업 파기 기한 30일, 법정 분리 보존 항목(지금 빈 목록, G-5 개발·CI 기본값). 고지 hash는 그 정책의 정규 직렬화 SHA-256이다. 화면은 code를 닫힌 맵으로 카피에 잇고, 두 벌의 code 목록은 공유 표(`frontend/src/content/accountDeletionPolicy.fixture.json`)를 프런트·백엔드 시험이 함께 읽어 묶는다(3.6·4.5 관용). 확정 요청은 사용자가 본 정책 version을 싣고 서버 상수와 다르면 409 `ACCOUNT_DELETION_POLICY_CHANGED`로 저장하지 않는다.
- 삭제 요청은 `identity` 소유 표 하나(V30)이고 사용자당 한 행이다 — request ID(불투명), user_id(lifecycle guard 행 참조), 시작 시각(DB `statement_timestamp()`), 정책 version, 고지 hash, 멱등 키 digest. 생성과 `markDeletionInProgress`는 lifecycle 행 잠금 아래 한 문장 묶음이며 허가 소비와 같은 트랜잭션이다. `identity`에는 HTTP가 없으므로 요청 표면은 루트 패키지가 맡는다.
- 응답 유실·같은 멱등 키 재전송은 같은 request를 200으로 돌려준다. 재전송 판정은 허가·SENSITIVE 판정보다 **앞에서** 요청 행으로 한다(삭제가 시작되면 허가 경로는 SENSITIVE에서 막히므로). 다른 키의 두 번째 요청과 이미 삭제 중인 계정의 준비는 409 `ACCOUNT_DELETION_IN_PROGRESS`이고 새 행·허가를 만들지 않는다.
- 확정 커밋 뒤 순서는 다른 세션 폐기(`SessionGuard.revokeSessionsOfExcept`, 요청 세션 id만 남긴다) → 무장 폐기 → 재인증 시각 제거이고, 요청 세션의 ID는 회전하지 않는다(ERD §5 "성공 시 회전"의 이 command 한정 예외 — Design Notes). 커밋 뒤 단계의 어떤 실패도 커밋된 요청을 오류 응답으로 바꾸지 않는다(같은 200을 돌려준다). 남은 요청 세션은 `GET /api/account/deletion`(상태)·같은 확정 재전송·`POST /api/auth/logout`만 쓸 수 있다 — 그 밖의 경로는 이미 `DELETION_IN_PROGRESS`를 거부하므로(SENSITIVE·`lockWriteAccess`) 새 차단 장치를 만들지 않고, 그 거부를 시험으로 고정한다.
- 세션 확인 응답(`GET /api/auth/session`)에 `deletionInProgress`(삭제 요청이 있으면 `true`)를 더하고, 화면은 그 값이 참이면 경로와 무관하게 삭제 진행 화면만 그린다(새 경로·복귀 목적지를 더하지 않는다 — 다섯 곳 계약을 건드리지 않는다).
- 새 접두사 `/api/account/`는 인가·CSRF·동일 출처 판정의 같은 술어(`AuthSecurityConfig.isProtectedRequest`·`requiresAuthentication`)에 더한다.
- production 판정은 `bokrilog.telemetry.env`(`BOKRILOG_ENV`)가 `production`인가 하나다. 그 환경에서 준비·확정은 403 `ACCOUNT_DELETION_UNAVAILABLE`이고 아무 행도 만들지 않으며, 정책 조회는 `available: false`를 싣는다. 화면은 그때 실행 버튼을 열지 않는다.
- 로그·분석·오류 응답에 허가 참조·확인 문구·정책 원값을 남기지 않는다. 로그는 `RedactingJsonEncoder` 관용(`event`·code·상태만)이다.
- 새 주석·카피는 한국어다. 화면은 `복리 계획 비교`·비추천 고지 인접 배치와 화면당 live region 하나 규칙을 지킨다.

**Ask First:**
- 기존 시험의 단정을 바꿔야 하는 경우(특히 복원 불변식·`ModuleBoundaryTest` 호출자 목록 외의 것).
- 허가 귀속 축이나 `action_grant` 스키마를 바꿔야 하는 경우.
- 세션 guard 필터가 lifecycle을 보게 해야 하는 경우(지금은 보지 않는다).

**Never:**
- 실제 모듈 데이터 삭제·`AccountErasure` 실행·tombstone 기록·백업 파기(5.2·5.3 몫). 이 스토리의 요청은 삭제를 시작만 한다.
- 이름·이메일 같은 표시 정보를 저장하거나 OIDC scope를 넓히는 일(V1 금지 유지). "계정 표시명 재입력"은 고정 문구로 대신한다(아래 accepted deviation).
- 삭제 취소(되돌리기) 경로 — 상태는 단조 전진뿐이다.
- 새 화면 경로·Nginx 허용 목록 변경, 무세션 상태 조회 표면, 브라우저 저장소 사용.
- 자동 재시도 루프.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 정책 조회 | ACTIVE, 비production | 200 `{available:true, policy:{version, targets, backupDestroyDays:30, legalRetention:[]}, request:null}` | N/A |
| 재인증 없음 | 준비 요청, 재인증 5분 밖 | 403 `REAUTHENTICATION_REQUIRED`, 허가·무장 없음 | 화면은 본인 확인 안내 |
| 준비 | 최근 재인증 | 200 `{policyVersion, confirmation:"계정을 삭제합니다"}`, 무장 | N/A |
| 문구 불일치 | 확정, 입력 문구 ≠ 상수 | 403 `CONFIRMATION_MISMATCH`, 허가 미소비·무장 유지 | 같은 대화상자에서 다시 입력 |
| 정책 바뀜 | 확정 본문 version ≠ 서버 | 409 `ACCOUNT_DELETION_POLICY_CHANGED`, 행 없음 | 정책 다시 읽기 |
| 확정 성공 | 무장·허가·문구·키 일치 | 200 `{requestId, requestedAt, state:"DELETION_IN_PROGRESS", policyVersion}`, 다른 세션 0개, 요청 세션 ID 유지(재인증 시각만 제거) | N/A |
| 응답 유실 재전송 | 같은 멱등 키 | 같은 200 본문, 새 행 없음(같은 쿠키로 재전송된다) | N/A |
| 중복 요청 | 다른 키, 또는 삭제 중 준비 | 409 `ACCOUNT_DELETION_IN_PROGRESS` | N/A |
| 키 없음 | 확정에 Idempotency-Key 없음 | 422 `IDEMPOTENCY_KEY_REQUIRED` | N/A |
| production | `BOKRILOG_ENV=production` | 준비·확정 403 `ACCOUNT_DELETION_UNAVAILABLE`, 조회 `available:false` | 화면은 실행 버튼 없음 |
| 삭제 중 다른 경로 | 남은 세션으로 대시보드·동기화·연결·광고·문의 | 지금과 같은 거부(404 등), 쓰기 없음 | N/A |
| 다른 세션 | 요청 전 다른 브라우저 세션 | 확정 뒤 401(세션 없음) | N/A |
| 취소 | 무장 있음/없음 | 204 no-store, 허가 무효화 | N/A |

</frozen-after-approval>

## Code Map

경로 접두사: 백엔드 `backend/src/main/java/com/bokrilog/`, 시험 `backend/src/test/java/com/bokrilog/`, 프런트 `frontend/src/`.

- 템플릿(Bybit 연결 해제, 2.16): `BybitConnectionController` `REVOCATION_PATH`:354-358·`REVOCATION_REFUSALS`:363-377·`prepareRevocation`:402·`confirmRevocation`:433·`cancelRevocation`:471, `BybitRevocationFlow`(SessionArming 위임 :31), `BybitRevocationCommandService` `prepare`:154(issue :163, 재인증 없음 → `REAUTHENTICATION_REQUIRED`)·`confirm`:186(멱등 재생 → 무장 대조 → `consume` :209 → 회전 :241)·`abandon`:255.
- 허가: `ActionGrant` `canonicalConfirmationHash`:163·`normalize`:301·`Binding`:128, `ActionGrantService.issue`:213·`attempt`:348(`decide(SENSITIVE)` :230·:355). command 상수 관례는 kebab(`LinkService`:54·`UnlinkService`:66·`BybitRevocationCommandService`:65·`PlanEndApi`:33). `action_grant.user_id`는 `identity_account` CASCADE(V3:40) — 5.1은 payload를 지우지 않으므로 영향 없음.
- 확인 문구 재입력 선례: `OperatorCommandService`:198-207(mismatch는 소비하지 않음), 화면 `components/OperatorExportDialog.tsx`:42-53(`typed.normalize('NFC').trim()` 일치 전 확정 비활성).
- identity: `AccountDeletionApi`(`beginDeletion`:30·`erasePayload`:47·`deletionInProgressAccounts`:59-63·`remainingRowsOf`:77), `AccountDeletionService`:29-85·:113, `IdentityAccountRepository` `markDeletionInProgress`:208-215·잠금 :36-38, `AccountAccessService`:46-47(IN_PROGRESS는 READ만), V1 lifecycle 표. 새 요청 API는 이 모듈 공개 API에 둔다.
- 세션: `SessionGuard.revokeSessionsOfExcept`:200(호출자 `UnlinkService.settleSessions`:374-385 — 회전 전 id로 폐기), `SessionRotation`:34-56, `AuthSessionController` 세션 응답 :163-205, `abandonArmedFlows`:119-125(다른 흐름 무장 버리기 목록).
- 경계·인가: `AuthSecurityConfig.isProtectedRequest`:1239-1260·`requiresAuthentication`:1273-1277, `ModuleBoundaryTest`:322-331(`beginDeletion`·`erasePayload`·tombstone `record`는 `AccountErasure`만)·:350-356(`erase` 호출자), `ControllerSupportTest`:159-186(새 컨트롤러도 `ControllerSupport`만).
- production 선례: `BokrilogRoleConfig`:23·:33-34, `DeletionSampleRunner.requireRehearsal`:72-79, `application.yml`:256-257(`bokrilog.telemetry.env`).
- 복원: `RestoreInvariants`:23·:36·:88(끝나지 않은 삭제 = 위반). 5.1부터 요청 행이 있는 `DELETION_IN_PROGRESS`는 정상 대기 상태이므로 "요청 행 없는 IN_PROGRESS"만 위반으로 좁힌다. `AccountDeletionService.erasePayload`는 요청 행도 함께 지운다(payload와 같은 운명, `remainingRowsOf`가 0이 되게).
- 프런트: `screens/SettingsScreen.tsx`(마지막 영역 `SupportInquiryPanel`:810·주석 :806, 대화상자 형제 :831-847·`inert` :361, `lastFlow`:173·`busy`:321·`dialogOpen`:303, `ReauthPanel`:441, 본인 확인 구역 :777-804), `session/bybitConnection.ts` 해제 전송 :205-273, `session/useBybitConnection.ts`:277-393(키는 대화상자 열 때 한 번, `unanswered`면 같은 키 재전송), `components/useConfirmationDialog.ts`, `session/useSessionState.ts`(세션 응답 파싱 `tokenOf`:165), `App.tsx` 경로 맵, `test/reauthGate.ts`(`expectReauthGateRejection`), content `auth.ts`:785-804(대상명 재입력은 Epic 5 몫이라는 주석).
- 마이그레이션 다음 번호: V30.
- 열린 회고 액션·미룬 결정:
  - `epic-1-retro-item-7`(확인 hash 판별력): 새 command는 입력 문구를 hash·대조에 쓴다. link·unlink의 상수 문구는 건드리지 않고 지나간다(일부).
  - `epic-2-retro-item-24`(erase 완전성 시험): 새 사용자 열 표(V30)를 `erasePayload`·`remainingRowsOf`가 덮게 한다. 시험 자체는 만들지 않고 지나간다.
  - `epic-1-retro-item-2`(`AuthFlowHandlers.onFailure` 무장 폐기): 새 무장도 그 폐기 대상이 되지만 동작을 바꾸지 않고 지나간다.
  - deferred 27(identity의 `spring_session` raw DELETE)·67(삭제 참여자 레지스트리)·162(삭제 뒤 운영자 행 `actor_user_id`): 5.1은 루트 `SessionGuard`로 폐기하고 레지스트리·운영자 행은 건드리지 않는다 — done 전환에서 판정한다.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/resources/db/migration/V30__identity_deletion_request.sql` -- 요청 표(사용자당 유일, lifecycle 참조, hex·길이 CHECK) -- 요청의 단일 기록.
- [x] `identity` 공개 API·internal -- `requestDeletion(user, policyVersion, noticeHash, idempotencyKeyDigest)`(잠금 → 기존 행 판정 → INSERT + `markDeletionInProgress`, 결과 Created·Replayed·InProgress·Denied)와 `deletionRequestOf(user)`; `erasePayload`가 요청 행도 지우고 `remainingRowsOf`가 센다 -- 요청 원자성.
- [x] 루트 `AccountDeletionPolicy`·`AccountDeletionFlow`(SessionArming)·`AccountDeletionRequestService`·`AccountDeletionController`(`GET /api/account/deletion`, `POST …`·`/confirm`·`/cancel`) -- 위 매트릭스. 확정 성공 뒤 세션 정리 순서를 지킨다. 다른 흐름 무장 버리기 목록에 더한다 -- 표면.
- [x] `AuthSecurityConfig` -- `/api/account/` 인가·CSRF·Origin 술어 -- 보호.
- [x] `AuthSessionController` -- 세션 응답에 `deletionInProgress` -- 화면 전환 근거.
- [x] `RestoreInvariants` -- 요청 행 없는 IN_PROGRESS만 위반 -- 5.1 이후의 정상 대기 상태.
- [x] `ModuleBoundaryTest` -- `requestDeletion`은 `AccountDeletionRequestService`만 부른다(기존 :322 규칙 유지) -- 경계.
- [x] `backend/src/test/.../AccountDeletionRequestTest`(신규) -- 매트릭스 전 행, 원자성(허가 소비·요청 행·상태 전환이 함께 커밋되거나 함께 롤백), 동시 확정 두 건(하나만 Created), 삭제 중 다른 경로 거부와 다른 세션 401, 로그 누출 없음 -- 검증.
- [x] `RestoreApplyTest`(또는 불변식 시험) -- 요청 행 있는 IN_PROGRESS는 통과, 없는 것은 위반 -- 불변식.
- [x] `frontend/src/content/accountDeletion.ts`·`accountDeletionPolicy.fixture.json` -- 구역·정책 항목(닫힌 맵)·연결 해제와 차이·대화상자·진행 화면·거절 code 카피 -- 카피 소유.
- [x] `frontend/src/session/accountDeletion.ts`·`useAccountDeletion.ts` -- 조회·준비·확정·취소(`http.ts` 사용, 키는 대화상자 열 때 한 번, `unanswered`면 같은 키) -- 전송·상태.
- [x] `frontend/src/components/AccountDeletionPanel.tsx`·`AccountDeletionDialog.tsx` -- 정책 표시(AC-1), 재인증 게이트, 문구 재입력 대화상자(기본 포커스 취소, 일치 전 확정 비활성) -- 화면.
- [x] `frontend/src/screens/SettingsScreen.tsx` -- 마지막 구역으로 패널 배치, `lastFlow`·`busy`·`dialogOpen`에 삭제 추가 -- 배치.
- [x] `frontend/src/screens/DeletionProgressScreen.tsx`·`session/useSessionState.ts`·`App.tsx` -- `deletionInProgress`면 어느 경로든 진행 화면(요청 시각·정책 version·진행 중·로그아웃), 다른 화면 렌더 안 함 -- 즉시 차단의 화면 쪽.
- [x] 프런트 시험 -- 패널·대화상자(포커스·Esc·문구 게이트·재인증 게이트 `expectReauthGateRejection`), 진행 화면 전환, 정책 code 공유 표 대조, 거절 code 맵과 백엔드 맵 대조 -- 검증.
- [x] `CLAUDE.md` -- 엔드포인트 목록에 `/api/account/deletion` 넷, `## 스토리가 고정한 계약`에 삭제 요청 한 줄 -- 규칙의 자리.

**Acceptance Criteria:**
- Given 이 변경, when `backend/gradlew --project-dir backend build`와 프런트 typecheck·lint·lint:css·format:check·build·test, then 모두 통과하고 기존 시험은 복원 불변식·호출자 목록 외에 단정을 바꾸지 않는다.
- Given 확정 성공, when DB를 보면, then 요청 행 하나·lifecycle `DELETION_IN_PROGRESS`·소비된 허가가 같은 트랜잭션에서 생겼고 `AccountErasure`·tombstone·모듈 삭제는 실행되지 않았다.
- Given production 환경 값, when 준비·확정을 보내면, then 행이 하나도 생기지 않는다.

## Spec Change Log

- 1차 리뷰(2026-11-05, blind·edge-case·verification-gap) — **intent_gap 하나**: 확정 뒤 세션 ID 회전(Always)과 응답 유실 시 같은 키 재전송의 같은 200(매트릭스)이 충돌했다. 회전된 쿠키가 실린 응답이 유실되면 재전송이 옛 ID로 나가 401이 되고, 삭제 중 계정은 재로그인도 거부돼 사용자는 접수 여부를 볼 수 없었다. 사용자 결정(2026-11-05)으로 Intent를 고쳤다 — 삭제 확정은 세션 ID를 회전하지 않고 재인증 시각만 지운다(ERD §5 회전 규칙의 이 command 한정 예외), 커밋 뒤 단계의 실패는 커밋된 요청을 오류로 바꾸지 않는다. 같은 결정으로 원칙상의 되돌림·재구현 대신 구현을 유지하고 patch로 이었다(KEEP: 요청 원자성·재생 선판정·세션 정리 순서·정책 공유 표·진행 화면 게이트 구조). 같은 리뷰의 patch·defer·reject는 아래 줄에 적는다.
- 1차 리뷰 분류 — patch 18·defer 1·reject 7. patch: 회전 없는 확정과 커밋 뒤 단계의 실패 격리(`settleStep`), 정책 버전을 사용자가 본 패널 값으로 준비·확정 양쪽에서 대조, 502·503·504를 무응답(uncertain)으로 접기, 불확실 취소의 진행 화면 이동, 삭제 게이트를 재인증 표시보다 앞에, Java `strip` 동치 문구 정규화 helper, 문구 입력의 `aria-invalid`·`aria-describedby`, 요청 행이 있는 ACTIVE 계정을 복원 불변식 위반으로, 설정 흐름 교차 포기 시험, 네 화면 게이트 시험, 배경 차단 시험, 카피 정정, CLAUDE.md 개수(일곱 Flow·네 대화상자). defer: 삭제 진행 화면에 문의 경로가 없다(진행 중 계정은 문의 접수가 거부된다, 결정 지점 5.2·5.4). reject: 세션 응답의 DB 오류 500(fail-closed, deferred 161과 겹침), 정책·진행 화면이 5.2·5.3이 구현할 목표 동작을 적는 것(정책 고지의 범위), 기한을 지금 정책으로 계산(버전이 하나뿐), `request null` 응답의 표시(5.1에서 불가능), 포기 목록 중복(기존 패턴), `RestoreInvariants` N+1(복원 1회·소량), 경합 시험의 상태 범위(의도된 허용 범위).
- patch 뒤 검증(2026-11-05) — 프런트엔드 typecheck·lint·lint:css·format:check·build 통과, 전체 Vitest 68 파일·1,602건 통과(1 skip). 백엔드 대상 `--rerun` 132건 통과. 로컬 전체 `clean build`는 이 PC의 Windows 소켓 결함(`Bad address: listen`, 시험 밖 .NET `TcpListener`도 30회 중 8~11회 WSAEFAULT)으로 컨텍스트 적재가 실패해 failure threshold로 번졌다 — 코드와 무관하며 백엔드 전체 판정은 PR CI `backend-quality`가 소유한다.
- 2차 리뷰(2026-11-05, `review_loop_iteration: 1`) — intent_gap·bad_spec 0, patch 15·defer 1·reject 11. patch: 회전 문구가 남은 주석 셋(컨트롤러·시험 javadoc, `ConsumedRequiringSessionRotation` 이름 설명), 같은 키·다른 본문 재전송의 409 충돌(템플릿 2.16과 같은 계약), 재생 경로의 세션 정리 재실행(커밋 뒤 프로세스 중단 대비), `settleStep` 경고의 예외 단순 이름, 모르는 정책 대상 code를 낡은 정책으로 접기, 법정 보존 항목 key 충돌 방지, 시험 보강 여섯(연결 준비 성공의 삭제 무장 폐기, 다른 세션 폐기 실패의 성공 응답, 본문 없는 5xx·판독 불가 200의 불확실 유지, 삭제 게이트의 계측 미등록, 삭제 중 거부 경로의 정확한 상태·code, OIDC 재로그인 결과 고정, 정책 고지 hash 고정), CLAUDE.md의 `epic-5-context.md` 문장·목록과 `epic-5-context.md`의 5.1 예외 절. defer: 실행 전 복원에서의 요청 내구성(5.2·5.3 결정). reject: 재인증 표시 세션의 진행 화면 401(운영 코드에 표시 호출자가 없고 표시 세션 차단은 1.8 계약), 준비 단계 409 뒤 자동 재조회(카피가 새로 열기를 안내한다), 커밋과 겹친 OIDC 로그인 경합(로그인은 비활성 계정을 거부하고 남은 세션도 게이트·쓰기 차단에 걸린다), `getName()` null(주체 이름은 항상 내부 user_id, 기존 `currentUserId`와 같은 판독), production 게이트 기본값(운영 compose가 `BOKRILOG_ENV` 기본 `production`), 고지 직렬화 구분자(보존 목록이 비어 있고 내용 변경은 버전을 올린다), 진행 화면 live region의 `aria-busy`(대시보드·온보딩과 같은 기존 패턴), 복원 보고의 두 위반 합산(조치 경로가 같다), 클라이언트의 백업 기한 계산(표시용, 권위는 5.3), 커밋 뒤 정리 실패의 메트릭·경보(5.2 orchestration 몫), 로컬 전체 빌드 미통과(이 PC의 소켓 결함 — CI가 판정).
- 2차 patch 반영에서 확정한 것 — 같은 키·다른 본문의 `IDEMPOTENCY_CONFLICT`는 409이고 화면은 그것을 이미 진행 중으로 읽어 진행 화면으로 간다(그 code는 그 키의 요청 행이 있을 때만 나온다). 공유 표 `accountDeletionPolicy.fixture.json`의 거절 code에 더했다. 계측 시험은 "Vitals 미등록"이 아니라 "등록되더라도 토큰이 없어 보고·`pagehide` 뒤 요청 0건"을 단정한다 — `useTelemetry`는 4.5 설계대로 게이트 판정 전에 마운트에서 등록한다(토큰을 넘기도록 바꾸면 대시보드 사례가 실패함을 확인했다). 삭제 중 거부 경로는 관측값으로 고정했다(대시보드·동기화·연결 403 `FORBIDDEN`, 광고 동의·문의 접수·조회 404 `NOT_FOUND`), OIDC 재로그인은 302 `/?auth=failed`·쿠키 없음이다. 검증: 프런트엔드 다섯 검사 통과·전체 Vitest 68 파일 1,610건 통과(1 skip), 백엔드 `AccountDeletionRequestTest` 25·`BybitConnectionTest` 39·`ControllerSupportTest` 15·`ModuleBoundaryTest` 34·`AuthSessionContractTest` 18·`RestoreApplyTest` 15 통과(`-Werror` 컴파일 경고 없음). 그 사이의 실패는 모두 이 PC의 `Bad address` 소켓 결함이었고, `RestoreApplyTest` 준비 단계의 `NEEDS_REVIEW` 1회는 다음 실행에서 통과했다(변경하지 않은 sync 경로의 기존 타이밍 간헐 실패로 본다). 백엔드 전체 판정은 PR CI `backend-quality`가 소유한다.

## Design Notes

**회전 예외의 근거.** 회전은 민감 조작 전에 탈취된 세션 ID가 조작 뒤에도 쓰이지 않게 하는 장치다. 삭제 확정 뒤 남는 세션은 상태 조회·로그아웃만 할 수 있고(그 밖은 `DELETION_IN_PROGRESS` 거부), 그 ID는 몇 분 전 재인증 로그인에서 이미 새로 회전됐다 — 남겨 둔 ID로 할 수 있는 일은 진행 상태 보기뿐이다. 반대로 회전하면 응답 유실 한 번에 사용자가 접수 확인을 영영 잃는다.

**Accepted deviation(AC-2 "계정 표시명 재입력").** 서비스는 이름·이메일을 저장하지 않는다(V1, openid scope만). 사용자 결정(2026-11-05)으로 고정 문구 `계정을 삭제합니다` 재입력으로 대신한다. 문구는 hash와 확정 대조에 모두 들어가 "실수로 누른 삭제"를 막는 목적을 지킨다.

**Accepted deviation(AC-3 "모든 세션 차단").** 사용자 결정(2026-11-05)으로 요청 세션 하나는 남긴다(ID 회전도 하지 않는다 — 아래). 그 세션은 상태 조회·같은 요청 재전송·로그아웃만 할 수 있고(나머지는 기존 `DELETION_IN_PROGRESS` 거부), 그래야 AC-4(응답 유실 시 같은 operation·진행 상태 제공)가 성립한다. 로그아웃하면 다시 로그인할 수 없다(1.4 계약 — 삭제 중 계정은 로그인 거부). 삭제가 끝나면 5.2·5.4가 그 세션도 끝낸다.

재전송을 허가 경로 앞에서 판정하는 이유: 삭제가 시작되면 `decide(SENSITIVE)`가 거부하므로 허가의 `findStoredResult` 재생 경로에 닿지 못한다. 요청 행의 `(user_id)` 유일성과 멱등 키 digest가 재생의 권위다.

```java
// 확정: 재생 → 게이트 → 무장·문구 → 허가 소비 + 요청 생성(한 트랜잭션) → 세션 정리
if (replay(user, keyDigest) instanceof Existing e) return ok(e);
```

## Verification

**Commands:**
- `backend/gradlew --project-dir backend test --tests 'com.bokrilog.AccountDeletion*' --tests 'com.bokrilog.ModuleBoundaryTest' --tests 'com.bokrilog.Restore*' --tests 'com.bokrilog.ControllerSupportTest'` -- expected: 통과
- `backend/gradlew --project-dir backend clean build` -- expected: 통과(전체 판정은 CI `ci-required`)
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run build && npm --prefix frontend run test` -- expected: 통과

## Suggested Review Order

**요청 확정의 원자성과 커밋 뒤 정리**

- 진입점 — 재생 선판정 → 정책·접근 → 무장·문구 → 허가 소비와 요청을 한 트랜잭션으로.
  [`AccountDeletionRequestService.java:208`](../../backend/src/main/java/com/bokrilog/AccountDeletionRequestService.java#L208)
- 같은 키는 같은 본문일 때만 재생, 다르면 409 충돌(2.16 템플릿 계약).
  [`AccountDeletionRequestService.java:336`](../../backend/src/main/java/com/bokrilog/AccountDeletionRequestService.java#L336)
- 회전 없이 다른 세션 폐기 → 무장 폐기 → 재인증 시각 제거, 단계마다 실패 격리.
  [`AccountDeletionRequestService.java:310`](../../backend/src/main/java/com/bokrilog/AccountDeletionRequestService.java#L310)
- 회전 예외를 공통 부품에 한 메서드로 둬 사본을 만들지 않는다.
  [`SessionRotation.java:50`](../../backend/src/main/java/com/bokrilog/SessionRotation.java#L50)
- 요청 행과 `DELETION_IN_PROGRESS` 전환을 identity 안에서 한 문장 흐름으로.
  [`AccountDeletionService.java:145`](../../backend/src/main/java/com/bokrilog/identity/internal/AccountDeletionService.java#L145)
- 사용자당 한 행, DB 시각, 정책 버전·고지 hash·키 digest만 저장.
  [`V30__identity_deletion_request.sql:1`](../../backend/src/main/resources/db/migration/V30__identity_deletion_request.sql#L1)

**정책 고지와 운영 게이트**

- 정책 버전·대상 code·30일·법정 보존(빈 목록)·고정 문구와 고지 hash의 단일 출처.
  [`AccountDeletionPolicy.java:26`](../../backend/src/main/java/com/bokrilog/AccountDeletionPolicy.java#L26)
- G-5 전 production은 준비·확정 403 — 기동 시 env로 판정.
  [`AccountDeletionRequestService.java:146`](../../backend/src/main/java/com/bokrilog/AccountDeletionRequestService.java#L146)
- 조회·준비·확정·취소 HTTP 표면, `ControllerSupport` 판독을 그대로 쓴다.
  [`AccountDeletionController.java:149`](../../backend/src/main/java/com/bokrilog/AccountDeletionController.java#L149)
- 새 접두사 `/api/account`에만 인가·CSRF·`Origin`을 건다.
  [`AuthSecurityConfig.java:289`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L289)

**삭제 진행 세션의 게이트**

- 세션 응답이 `deletionInProgress`를 싣는다(재인증 표시 세션 포함).
  [`AuthSessionController.java:225`](../../backend/src/main/java/com/bokrilog/AuthSessionController.java#L225)
- 게이트는 다른 화면에 토큰을 주지 않고 진행 화면 로그아웃에만 `deletionToken`.
  [`useSessionState.ts:571`](../../frontend/src/session/useSessionState.ts#L571)
- 어느 인증 경로든 진행 화면 하나만 — 상태·다시 확인·로그아웃.
  [`DeletionProgressScreen.tsx:43`](../../frontend/src/screens/DeletionProgressScreen.tsx#L43)
- 설정 화면이 게이트에서 진행 화면으로 접는다(대시보드 등 네 화면 같은 모양).
  [`SettingsScreen.tsx:290`](../../frontend/src/screens/SettingsScreen.tsx#L290)
- 다른 설정 흐름은 게이트를 지난 뒤에만 삭제 무장을 버린다.
  [`BybitConnectionController.java:270`](../../backend/src/main/java/com/bokrilog/BybitConnectionController.java#L270)

**설정 화면의 요청 흐름**

- 본 정책 버전을 준비·확정에 싣고, 응답 없는 확정은 같은 키로 불확실 유지.
  [`useAccountDeletion.ts:181`](../../frontend/src/session/useAccountDeletion.ts#L181)
- 판독 불가 200·502/503/504·본문 없는 5xx를 무응답으로 접는다.
  [`accountDeletion.ts:254`](../../frontend/src/session/accountDeletion.ts#L254)
- 모르는 대상 code는 낡은 정책으로 접어 시작을 막는다.
  [`AccountDeletionPanel.tsx:50`](../../frontend/src/components/AccountDeletionPanel.tsx#L50)
- 고정 문구 재입력, 불일치는 `aria-invalid`로.
  [`AccountDeletionDialog.tsx:93`](../../frontend/src/components/AccountDeletionDialog.tsx#L93)
- 서버 `strip`·NFC와 같은 문구 정규화.
  [`accountDeletion.ts:233`](../../frontend/src/content/accountDeletion.ts#L233)

**복원·경계·시험**

- 요청 없는 진행 중 계정과 요청 있는 ACTIVE 계정을 복원 불변식 위반으로.
  [`RestoreInvariants.java:105`](../../backend/src/main/java/com/bokrilog/RestoreInvariants.java#L105)
- 확정·재생·충돌·정리 실패·게이트 교차 포기의 통합 시험.
  [`AccountDeletionRequestTest.java:487`](../../backend/src/test/java/com/bokrilog/AccountDeletionRequestTest.java#L487)
- 정책 공유 표 — 서버 code·화면 문장 집합을 두 시험이 함께 읽는다.
  [`accountDeletionPolicy.fixture.json:1`](../../frontend/src/content/accountDeletionPolicy.fixture.json#L1)
