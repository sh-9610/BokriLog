---
title: 'Story 5.4 삭제 계정이 복원 후 재활성화되지 않음을 통합 검증하고 완료 안내하기'
type: 'feature'
created: '2026-11-06'
status: 'done'
baseline_commit: '5e4e61d73f5aff985130bea396d9ecdbda41bb51'
review_loop_iteration: 1
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-3-backup-destruction-tracking.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 삭제가 끝난(`DELETED`) 계정의 소셜 identity로 다시 로그인하면 빈 새 계정이 자동으로 생기고, 삭제 완료와 함께 요청 세션이 지워져 사용자는 완료 안내 대신 '로그인 만료'를 본다. "전체 삭제 완료"(운영 삭제 + 백업 파기 + 법정 보존)를 기록하는 곳이 없고, 복원 리허설은 모듈별 잔여를 단정하지 않으며, 실환경 격리 시험의 상태를 둘 자리도 없다.

**Approach:** 삭제한 소셜 identity의 keyed digest로 재가입을 막고(사용자 결정 2026-11-06), 요청 세션 하나를 user_id 없는 완료 확인 전용 세션으로 바꿔 서버 값으로 완료를 안내하며, 백업 파기 판정이 `DESTROYED`를 내면 identity에 전체 삭제 완료를 기록한다. 복원 보고·리허설에 모듈별 잔여를 싣고, 실환경 격리 시험은 자동시험과 다른 상태로 문서에 둔다. 미룬 162(삭제 뒤 감사 행의 수행자)·170(지원 연락처)을 함께 닫는다.

## Boundaries & Constraints

**Always:**
- 재가입 차단: 삭제 실행이 소셜 매핑을 지우는 트랜잭션(`CREDENTIALS` 단계, 단일 모드·`reapply` 포함)에서 `(issuer, subject)`의 keyed digest만 identity 소유 새 표(V33, 사용자 열 없음)에 남긴다 — 원 식별자 금지. digest 키는 API가 이미 가진 감사 digest 키에 고유 접두사(`deleted-identity:`)이고, identity는 키·HMAC을 갖지 않고 루트가 구현하는 port로 받는다(tombstone 키 금지). 그 digest의 로그인은 계정을 만들지 않고 새 표지 `deleted`로 닫으며, 다른 수단 연결(`/api/auth/link`)도 거부한다. 차단은 G-5가 재가입을 허용할 때까지 유지하고, 삭제 정책 화면의 "남기는 것"에 이 기록을 적는다.
- 완료 전용 세션: `IDENTITY` 단계는 요청 세션을 지우지 않고 같은 커밋에서 user_id·인증 문맥을 모두 걷어낸 완료 표지(삭제 request ID만)로 바꾼다 — 그 뒤 어떤 `spring_session` 행도 그 user_id를 담지 않는다(잔여 0 유지). 그 세션은 완료 조회(`GET /api/account/deletion`)와 로그아웃만 하고 나머지는 미인증과 같다. 수명은 기존 유휴 30분·절대 12시간이다. 다른 세션·옛 쿠키는 지금처럼 미인증이다.
- 완료 조회는 request ID로 서버 값만 싣는다: 운영 삭제 시각, 백업 파기 기한(요청 + 30일), 백업 파기 상태(`PENDING`·`OVERDUE`·`DESTROYED`와 완료 시각), 전체 삭제 완료 여부, 법정 분리 보존(지금 없음), 지원 연락처(170, 비면 생략). 진행 화면은 그 자리에서 완료 화면이 되고 부분 실패·기한 초과를 숨기지 않는다. 세션 응답은 완료 세션에만 완료 표지를 싣고, 일반 미인증 응답 본문은 글자까지 그대로다.
- 전체 삭제 완료: 실행 상태 권위는 identity다. 운영 삭제 완료 + 그 요청 tombstone `DESTROYED` + 법정 분리 보존 처리(목록이 비면 충족, 비지 않으면 미충족 — 증거 없이 완료로 쓰지 않는다)일 때만 identity 실행에 완료(상태·시각)를 기록한다. 루트 판정자가 `DESTROYED`가 된 요청과 아직 완료가 아닌 `DESTROYED` 요청(복원으로 옮긴 것 포함)을 identity에 넘긴다. 단조이고 멱등이다.
- `DELETED` 뒤 확정 재전송·OIDC 콜백·대기 Worker·옛 쿠키는 계정·요청·세션·데이터를 다시 만들지 못한다(재전송은 새 거부 code 하나, 공유 표에 등록).
- 복원: `restore-apply` 보고와 `rehearse-restore.sh`가 삭제 표본의 모듈별 잔여(identity·로그인 매핑·세션·자격정보·plan·source·snapshot·calculation 결과·문의·동의)를 따로 싣고 0을 단정한다. 표본 seed를 그 모듈들로 넓힌다. 복원은 재가입 차단 digest도 gen0의 매핑에서 다시 만든다.
- 162: 운영 command 거절·실패 행(`recordQuietly`·`recordRefusedProposal` 경로)은 append 잠금 뒤 수행자 계정의 삭제 시작 여부를 보고, 시작됐으면 수행자 귀속 없이 쓴다(3.10의 늦은 신호와 같은 규칙).
- AC-5: 실환경 격리 삭제·복원 통합시험은 `DEVELOPMENT.md`에 절차·결과 기록 자리와 상태 `미수행`으로 두고, CI 리허설 통과와 다른 상태임을 적는다.

**Ask First:**
- 세션 수명 규칙·`SessionGuard` 정책이나 일반 미인증 응답 본문을 바꿔야 할 때.
- 기존 로그인 실패 표지의 의미를 바꾸거나 `deleted` 말고 표지를 더 늘려야 할 때.
- 삭제 정책 서버 상수(고지 hash 입력)나 정책 버전을 바꿔야 할 때.

**Never:** 재가입 차단의 해제 경로, 원 issuer·subject 저장, identity의 HTTP 표면·키, API에 tombstone 키, 완료 세션에 user_id, 무세션 영수증 조회, 법정 보존 증거 없이 전체 완료, CI 리허설로 실환경 시험 통과 기록, 백업 조기 파기, 새 런타임 의존.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 같은 identity 재로그인 | `DELETED` 계정의 issuer·subject로 로그인 | 계정·세션 생성 없음, 표지 `deleted` | N/A |
| 삭제 identity 연결 | 다른 ACTIVE 계정이 그 identity를 연결 | 연결 거부, 매핑 없음 | 기존 연결 실패 흐름 |
| 완료 세션 | `IDENTITY` 커밋 뒤 요청 세션 | 완료 조회·로그아웃만, 세션 행에 user_id 없음 | 다른 경로는 미인증과 같음 |
| 옛 쿠키 | 요청 세션이 아닌 쿠키 | 미인증 | N/A |
| 확정 재전송 | `DELETED` 뒤 같은 키 확정 | 아무것도 만들지 않음 | 새 거부 code |
| 전체 완료 | tombstone `DESTROYED`, 법정 보존 없음 | identity 완료 기록, 화면 완료 | 다시 판정해도 멱등 |
| 기한 초과 | `OVERDUE` | 전체 완료 아님, 화면에 초과 | N/A |
| 복원 | 삭제 전 generation 복원 | 모듈별 잔여 0 보고 | 잔여 있으면 거부 |
| 삭제 뒤 거절 행 | 삭제된 운영자의 command 거절 | 수행자 귀속 없음 | N/A |

</frozen-after-approval>

## Code Map

- `backend/.../AuthSessionService.java:143-179` -- `attempt`: ACTIVE 매핑 조회(:145) 실패 시 `createAccount`(:156)·`linkSocialIdentity`(:157) — 차단 검사 자리(:155 앞)
- `backend/.../identity/AccountLifecycleApi.java:27,51,112`·`identity/internal/AccountLifecycleService.java:62-106`·`IdentityAccountRepository.java:100,166,242` -- 계정·매핑 생성 술어. `linkSocialIdentity`에 차단 술어를 두면 로그인·연결(`LinkService.java:276-316`, :292) 둘 다 막힌다. `AccessDecision`은 둘뿐이라 삭제 identity 판정은 별도 조회가 필요
- `backend/.../AccountErasure.java:151-154`·`identity/internal/AccountDeletionService.java:95-105` -- 매핑은 `CREDENTIALS` 단계 `eraseSocialLinks`가 지운다(IDENTITY의 `deleteSocialLinks` :69는 이미 0행) — digest는 여기서 지우기 전에. `reapply`(:114-130)도 `apply`를 지난다
- `backend/.../operations/internal/ActorDigest.java:25`·`OperationsConfiguration.java:28-32,64-87`·`ConfiguredSecrets.java:52`·`application.yml:237` -- 감사 digest 키와 접두사 HMAC 관용(package-private, servlet·일회성 역할). identity는 다른 모듈을 import하지 않는다 → identity 공개 port + 루트 구현(operations 공개 API 위임). `AccountErasureState` 관용의 역방향
- `backend/.../AuthFlowHandlers.java:131-143,589-596,725-738` -- 실패 표지 상수와 `converge(FAILURE_MARKER_FAILED)`, 연결 실패. 표지 javadoc("삭제 계정 구분 금지")을 함께 고친다. 프런트 `frontend/src/content/auth.ts:239,308,323,337,373`·`App.tsx:99`
- `backend/.../identity/internal/AccountDeletionService.java:71,82`·`IdentityAccountRepository.java:266-270,374-384` -- 요청 행·세션 삭제(`principal_name = userId`)와 잔여 집계(spring_session 포함). 남길 세션은 principal·속성에서 user_id를 걷어내야 잔여 0
- `backend/.../SessionGuard.java:60,142,200`·`AccountDeletionRequestService.java:188,368-372` -- 요청 세션만 남기는 5.1 장치(`revokeSessionsOfExcept`), `runOf`가 `operationalErasedAt`을 싣는다. 요청 세션 id를 어디서 아는지(요청 행·실행 행) 확인
- `backend/.../AuthSessionController.java:165-185,222-241` -- 세션 응답, `deletionInProgress = requestOf(current).isPresent()`
- `backend/.../AccountDeletionController.java:73-79,125-148,183-202,412-421` -- GET 권한(:187 READ → DELETED는 404), 뷰·진행 매핑. 완료 세션 분기와 완료 뷰
- `backend/.../operations/internal/BackupDestructionService.java:39,150-170`·`DeletionTombstoneRepository.java:147,268,341` -- request ID로 상태를 읽는 공개 메서드가 없다(더한다). DESTROYED 행은 기한 뒤 삭제되므로 그 전에 identity로
- `backend/.../BackupDestructionJudge.java:116-129`·`operations/BackupDestructionApi.java:67` -- `Judgement`는 수만 — DESTROYED request ID를 돌려주거나 sweep. `ModuleBoundaryTest:354-356`이 호출자를 묶는다
- `backend/src/main/resources/db/migration/V31__identity_deletion_run.sql:48-49,54-55` -- 실행 상태 CHECK. 다음 migration은 V33
- `backend/.../AccountDeletionPolicy.java:68` -- `LEGAL_RETENTION` 빈 목록(고지 hash 입력 — 바꾸지 않는다)
- `backend/.../OperatorCommandService.java:423-432`·`operations/internal/OperatorEnvelopeService.java:341-351,579-590`·`operations/AccountErasureState.java:15-22`·`OperationalSignalService.java:139-144,195` -- 162: `append`의 `lockAppends` 뒤 `environment.accountUsable`(`OperatorCommandFacts.java:86`)로 판정, 늦은 신호와 같은 패턴
- `backend/.../SupportContact.java:18-40`·`SupportInquiryController.java:116-139` -- 170: 루트 bean 주입 선례
- `deploy/production/rehearse-restore.sh:311-334,448-452,741-783`·`RestoreInvariants.java:43,82-93`·`RestoreApplication.java:115-128` -- 표본 seed(지금 identity·세션만), gen0은 삭제 전, 잔여는 합산 한 줄 → 모듈별로
- `frontend/src/screens/DeletionProgressScreen.tsx:46-51,94-95,142-143`·`session/accountDeletion.ts:34-47,157,205,361`·`content/accountDeletion.ts:135-163,236`·`session/useSessionState.ts:91,499,571` -- 401 → 만료 이동, 완료 문구(`stateErased`는 지금 보일 수 없음), 세션 게이트
- `frontend/src/content/accountDeletionPolicy.fixture.json` -- `progressStatuses`·`refusalCodes` 공유 표(완료 상태·새 code를 더한다)
- `DEVELOPMENT.md:664-` -- 복원 절(AC-5 상태 기록 자리)
- 시험 앵커: `AuthProviderIndependenceTest:107,143`, `AuthDeletionPathTest:39,59`(완료 세션과 충돌 — 요청 세션 외 쿠키로 좁힌다), `AccountErasureRunTest:96,145-146`(삭제 뒤 GET 401 단정이 바뀐다), `AccountDeletionRequestTest:66,384,680,810`, `RestoreApplyTest:63,134,420,481`, `BackupDestructionJudgeTest:51,267,305`, `ErasureAuditLockTest:85,114,202`, `OperatorCommandTest:174,194,238`, `frontend/src/screens/DeletionProgressScreen.test.tsx:170,475,524`
- 걸린 열린 회고 액션:
  - `epic-2-retro-item-24`(in-progress, information_schema 잔여 완전성 `RestoreApplyTest:481`) -- 새 digest 표에 사용자 열이 없어야 한다. 일부만 다룬다(모듈별 잔여 보고)
  - `epic-3-retro-item-34`(실제 격리 복원 증거 게이트)·`item-33`(운영 증거 항목) -- AC-5 상태 기록으로 일부 다룬다, 실제 수행은 하지 않는다
  - `epic-3-retro-item-29`(복원본 전역 정지)·`item-35`(3.10 동결 SPEC 문장)·`epic-1-retro-item-2`(`onFailure` 무장 폐기, 같은 파일)·`epic-1-retro-item-10`(게이트 기계 판독 자리) -- 건드리지 않고 지나간다
- 미룬 항목: 162·170 닫는다(사용자 결정). 27(`identity`의 `spring_session` 결합)은 완료 세션 처리로 결합이 늘 수 있어 다시 판정한다. 171 잔여(첫 claim 전 창)·174~176은 지나간다

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/resources/db/migration/V33__deletion_completion.sql` -- 재가입 차단 digest 표(identity 소유, 사용자 열 없음), 실행 전체 완료 상태·시각(V31 CHECK 갱신) -- 상태의 권위
- [x] `backend/.../identity/**`·루트 port 구현 -- digest port, `CREDENTIALS`(단일 모드·`reapply` 포함)에서 digest 기록, 로그인·연결 차단 판정, 완료 세션 변환, 완료 기록 API -- D1·D2·D3
- [x] `backend/.../AuthSessionService.java`·`AuthFlowHandlers.java`·`LinkService.java` -- 차단 판정과 표지 `deleted` -- AC-2
- [x] `backend/.../AccountDeletionController.java`·`AuthSessionController.java`·`SessionGuard`(필요 범위) -- 완료 세션의 완료 조회·로그아웃, 완료 뷰, 지원 연락처, 재전송 거부 code -- AC-3·AC-4·170
- [x] `backend/.../operations/**`·`BackupDestructionJudge.java` -- request ID 상태 조회, DESTROYED 요청을 identity로(sweep 포함) -- AC-3
- [x] `backend/.../operations/internal/OperatorEnvelopeService.java` -- 거절·실패 행 수행자 비식별 -- 162
- [x] `backend/.../RestoreInvariants.java`·`RestoreApplication.java`·`deploy/production/rehearse-restore.sh` -- 모듈별 잔여 보고·단정, 표본 seed 확장, 재가입 digest 재생성 확인 -- AC-1
- [x] `frontend/src/**` -- 완료 화면(진행 화면 전환, 서버 값·초과·실패·지원 연락처, 접근성), `deleted` 표지 문구, "남기는 것"에 재가입 차단 기록, 공유 표 -- AC-4
- [x] `DEVELOPMENT.md` -- AC-5 실환경 격리 시험 절차·결과 기록 자리·상태 `미수행`, 완료 세션·재가입 차단 서술 -- AC-5
- [x] 시험: 매트릭스 아홉 행, 완료 세션 행에 user_id 없음과 잔여 0, digest 표에 원 식별자·사용자 열 없음, 법정 보존이 비지 않으면 완료 안 됨, 프런트 완료·초과·실패·연락처·`deleted` 표지

**Acceptance Criteria:**
- Given 삭제가 끝난 계정, when 요청 세션이 완료 조회를 하면, then 운영 삭제 시각·백업 파기 기한과 상태·법정 보존·지원 연락처가 서버 값으로 오고, 그 세션으로 다른 API는 미인증과 같다.
- Given 법정 분리 보존 목록이 비지 않은 정책(시험용), when tombstone이 `DESTROYED`가 되면, then 전체 삭제 완료를 기록하지 않는다.
- Given 복원 리허설, when 삭제 전 generation을 복원하면, then 보고가 모듈별 잔여 0을 싣고 리허설이 각 값을 단정한다.

## Spec Change Log

- 구현에서 확정한 것(2026-11-06) — **재가입 차단**: V33 `identity_deleted_identity(identity_digest, request_id, recorded_at)`(사용자 열 없음 — `request_id`는 1차 리뷰가 더했다). digest는 감사 digest 키의 `deleted-identity:<issuer 길이>:<issuer>:<subject>` HMAC이고 identity 공개 port `DeletedIdentityDigests`를 루트 `DeletedIdentityDigestPort`가 operations 공개 API `DeletedIdentityDigestApi`(모든 역할에 배선, 키가 없으면 빈 값)로 구현한다. 기록은 identity `eraseSocialLinks`·`erasePayload`가 매핑을 지우기 전에 같은 트랜잭션에서 하고, 매핑이 있는데 키가 없으면 예외(차단 기록 없는 삭제 금지 — 웹 없는 identity 모듈 시험은 키를 동적 속성으로 받는다). 판정은 `AccountLifecycleApi.isDeletedIdentity`(새 계정 생성 앞)와 `insertSocialLinkIfActive`의 `NOT EXISTS` 술어(로그인·연결 공통) 두 겹이고 표지는 `AuthFlowHandlers.FAILURE_MARKER_DELETED`(`deleted`). **완료 세션**: 확정이 요청 세션을 V33 `identity_deletion_request`에 남기고(`requestDeletion` 5-인자판. 4-인자판은 요청 세션을 기록하지 않는 판이고 구현 클래스의 `@Transactional` 메서드다 — 아래 검증 문단. 1차 리뷰 뒤 남기는 값은 세션 ID의 SHA-256이 아니라 세션 행 키 `session_primary_id`다), 마지막 단계가 그 세션의 `principal_name`을 비우고 CSRF 토큰 말고 속성을 지운 뒤 V33 `identity_deletion_completion_session(session_primary_id → spring_session cascade, request_id)`로 잇는다 — 세션 속성은 Java 직렬화라 SQL로 쓸 수 없어 "요청 ID만 든 세션"을 이 곁표로 구현했다. CSRF 토큰을 남긴 것은 화면이 들고 있는 토큰으로 로그아웃하게 하려는 것이다(사용자를 가리키지 않는다). 인가는 `AuthSecurityConfig.isDeletionCompletionRequest`(GET 조회·POST 확정)에 완료 세션을 허용하는 `access` 규칙 하나를 더했고 판독은 루트 `DeletionCompletionSessions`가 세션 ID로 한다. 세션 응답 필드 `deletionCompleted`(완료 세션에만, CSRF 토큰과 함께), 확정 재전송 409 `ACCOUNT_DELETION_COMPLETED`, 조회 응답의 `completion`(요청 ID·요청 시각·운영 완료 시각·백업 파기 기한·상태·완료 시각·전체 완료·시각)과 `supportContact`(요청이 있거나 완료 조회일 때, 비면 생략 — 170). **전체 완료**: V31 상태에 `DELETION_COMPLETED`·`deletion_completed_at`(CHECK 갱신), `AccountDeletionRunApi.awaitingDeletionCompletion`·`recordDeletionCompleted`(호출자는 판정자 하나 — `ModuleBoundaryTest`)·`completedDeletionOf`, operations `BackupDestructionApi.destroyedAmong`·`destructionOf`, 판정자 `BackupDestructionJudge.completeNow`(판정마다 sweep, 법정 보존 목록이 비지 않으면 0 — 시험 훅 `reinterpretLegalRetention`). **복원**: `RestoreInvariants.Violations.residuals`(키 열하나 `RESIDUAL_KEYS`)와 보고 줄 `residual_<키>=`, 리허설 삭제 표본 seed를 source·자격정보(같은 key ring으로 봉함)·portfolio snapshot·sync 작업·plan 확인·문의·동의로 넓히고 각 잔여 0과 재가입 digest 재생성(2)을 단정. **162**: `OperatorEnvelopeService.recordDecision`의 append가 원장 잠금 뒤 `accountUsable`이 거짓이면 `actor_user_id` 없이 `actor_erased_at`을 채워 쓴다(`OperatorAuditRepository.NewRow.actorErased`). **AC-5**: `DEVELOPMENT.md` 복원 절에 "실환경 격리 삭제·복원 통합시험" 절(상태 `미수행`, 절차 여덟 단계 — 1차 리뷰가 한 단계를 더했다, 결과 기록 표)을 더했다. 알려진 한계: 삭제가 끝나는 커밋과 겹친 같은 세션의 요청이 그 뒤 저장되면 `principal_name`이 한 번 되살아날 수 있다(인증 문맥 속성은 이미 지워져 미인증이고 판정 주기(기본 5분)의 정리가 다시 비운다 — 1차 리뷰. "다음 요청의 저장"은 정리 경로가 아니다 — 2차 리뷰가 문장을 바로잡았다). `requestDeletion`의 4-인자 판은 인터페이스 기본 메서드로 두면 프록시를 지나지 않아 트랜잭션·생명주기 잠금 없이 판정된다(전체 빌드의 동시 요청 시험이 중복 키로 잡았다) — 구현의 `@Transactional` 메서드로 옮겼다. 검증: 백엔드 전체 `build` 1,740건 가운데 실패 350건은 이 PC의 `Bad address: listen` 소켓 결함으로 컨텍스트가 뜨지 않은 21 클래스(+위 중복 키 1건)였고, 수정 뒤 그 클래스 전부와 `DeletionCompletionTest`를 다시 돌려 354/354 통과, `AccountDeletionRequestTest`·`ModuleBoundaryTest` 통과(`-Werror` 경고 없음). 프런트 typecheck·lint·lint:css·format:check와 전체 시험(1,638 — `OperatorNotice` 1건은 부하 아래 lazy chunk 시간초과로 실패했다가 단독 재실행 통과, deferred 158) 통과, `test-restore-scripts.sh` 75/75. 리허설(`rehearse-restore.sh`)은 로컬에서 돌리지 않았다 — PR CI가 판정한다.
- 1차 리뷰 patch(2026-11-06) — 리뷰 지적 P1~P11·T1~T6·D1~D4를 모두 반영했다: verify-same-origin의 세션 TRUNCATE에 완료 세션 표(P1); 재가입 차단 기록에 단계 모드 실행의 `request_id`를 더해 표지 `deleted`는 그 계정이 `DELETED`일 때만, 진행 중(CREDENTIALS 뒤 재시도 필요)은 계정을 만들지 않고 `failed`(`AccountLifecycleApi.DeletedIdentityState` NONE·DELETION_UNFINISHED·DELETED·UNVERIFIABLE), 실패 경로의 판정 예외는 `failed`로 접음(P2); 키 없음·읽을 수 없는 키는 fail-closed — 새 매핑 거부·차단 상태 UNVERIFIABLE(P3); 인증 성공(`writeEstablishedPrincipal`)이 회전 전·후 세션 ID로 완료 연결을 지우고 완료 판독은 미인증 세션만(P4); 요청 세션은 `session_primary_id`로 저장·변환하고 변환 0행은 `REQUEST_SESSION_GONE` 한 줄(P5, V33 수정 — 미적용); 판정자 `scrubNow`가 완료 세션의 되살아난 주체 이름·속성을 비움(P6); 복원 잔여 칸을 직접 세고 뺄셈 칸은 절댓값을 위반으로, 키 이름을 모듈 이름으로(`portfolio`·`sync`·`operations`·`engagement`, P7); 운영자 감사 행은 모두 원장 잠금 뒤 계정 삭제 시작을 봄(P8); 지원 연락처 판정을 `session/supportContact.ts` 하나로 모아 세 곳이 씀·링크는 본문 문장으로 설명(P9); 완료 상태 줄을 파기 상태별로(P10); 완료 화면 수명 한계 안내와 12시간을 말하는 로그아웃 안내(P11); 진행 화면은 게이트의 `deletionCompleted`를 `key`로 받아 재확인이 완료로 바뀌면 다시 읽는다(T3에서 드러남); 확정 대화상자는 409 `ACCOUNT_DELETION_COMPLETED`를 진행 중 거절처럼 문서 재요청으로 처리. 시험 T1~T6(전체 완료 뒤 단일 모드 재삭제 불변, 키 없는 fail-closed `DeletedIdentityKeylessTest`, visibility 재확인의 완료 전환, 진행 조회의 연락처, 잔여 키별 단정, 401·403 고정과 409 대화상자), `ModuleBoundaryTest` 호출자 규칙 셋(D1), 문서 정합·키 회전 한계(deferred 139와 같은 주인)·unlink 범위·AC-5 절차 보강(D3·D4), 시험 파일 이름 `DeletionProgressScreen.completion.test.tsx`. 검증: 바뀐·영향 백엔드 시험 클래스 52개 546건 + `AccountDeletionRequestTest`·`AccountErasureWorkerRoleTest` 29건 통과(Gradle 실행기의 `Bad address: listen` 소켓 결함으로 빠진 클래스는 다시 돌려 통과), 프런트 typecheck·lint·lint:css·format:check와 전체 시험 1,642건(1 skip) 통과, `test-restore-scripts.sh` 75/75, `test-ci-scripts.sh` 380/380. 리허설·백엔드 전체 `build`는 PR CI가 판정한다.
- 2차 리뷰 patch(2026-11-06) — 리뷰 지적 Q1~Q17을 모두 반영했다(갈림길은 가장 보수적인 쪽): 진행 화면이 게이트의 `deletionCompleted`를 `completed`로 받아 완료 조회를 읽기 전·읽지 못함·다시 확인 중에도 완료 쪽 제목·문장·안내·버튼을 고르고, 마지막으로 읽은 완료 조회를 기억해 다시 확인 동안 제목이 되돌아가지 않음(Q1); 진행 중 다시 시도가 401이면 만료로 보내기 전에 조회를 한 번 다시 읽어 완료면 그 자리에서 완료 화면, 아니면 만료(Q2 — 사용자 조작의 결과 확인이지 자동 재시도가 아니다); 판정자 `judgeNow`가 전체 완료 기록 실패를 WARN으로 접고 완료 세션 정리는 `finally`에서 돌려 파기 판정이 실패해도 돈다(Q3); 정리는 주체 이름이 그 요청 실행의 원래 사용자 ID인 세션(또는 주체 없는 세션의 남기는 속성 밖 속성)만 비우고, 다른 사용자로 인증된 세션은 주체·속성을 건드리지 않고 완료 연결 행만 지움 — 남기는 속성은 CSRF 토큰과 OAuth2 인가 요청 둘이고 이름은 Spring 저장소 클래스 이름 + 접미사로 만들어 사본 리터럴이 없다(`KEPT_SESSION_ATTRIBUTES`, Q4 — 인가 요청을 남긴 것은 완료 세션에서 시작한 다른 identity 로그인이 정리 뒤에도 끝나게 하려는 것이고, 시험이 그 콜백 왕복으로 이름을 대조한다); 매핑 INSERT가 성공한 뒤 새 문장으로 차단 기록을 다시 보고 있으면 방금 만든 매핑을 지우고 DENIED(Q5 — 예외를 던지지 않는 쪽을 골랐다: 기존 `linkSocialIdentity`의 롤백 표시 금지 근거와 같고 두 호출자는 거부면 자기 트랜잭션을 되돌린다); 차단 기록 판정은 요청 ID가 없거나 그 실행 행이 없으면 끝난 삭제(`DELETED`)로, 실행 행이 있고 계정이 `DELETED`가 아닐 때만 미완료(Q6 — 실행 행을 지우는 문장이 없고 요청 ID는 실행 행에서 읽어 기록하므로 진행 중 계정에는 언제나 실행 행이 있다. 반대로 "실행 없음 = 미완료"로 두면 보존 정리 뒤 삭제 identity가 `failed`로 영원히 접혀 표지가 틀린다); 절대 수명이 지난 완료 세션은 일반 미인증·완료 연결 cascade 삭제(Q7 — `SessionGuard`가 이미 적용, 코드 변경 없음·시험만); 복원 보고의 `identity` 칸을 identity `remainingRowsOf`에서 매핑·세션 칸을 뺀 절댓값으로 바꿔 모듈 집계를 권위로 두고(`remainingPayloadRowsOf`·`countPayloadRowsOf` 삭제), `Violations`는 합을 내역에서 내고 보조 생성자를 없앰(Q8); 진행·완료 화면에 요청 ID(불투명 참조 — 계정 식별자가 아니다)와 문의 문장 "문의할 때 이 요청 ID를 알려 주세요"(Q9); `ActorDigest.ofDeletedIdentity` 정답 대조 시험(`ActorDigestTest`, 기대값은 `javax.crypto`로 따로 계산·경계 쌍·접두사·키 분리, Q10); 완료 세션에서 삭제한 identity로 다시 로그인하면 표지 `deleted`와 함께 실패 수렴이 세션을 폐기해 완료 세션도 닫힘 — 세션 정책은 바꾸지 않고 시험과 `DEVELOPMENT.md`에 적음(Q11, Ask First 범위 밖); "다음 요청이 비운다" 문장 삭제(정리 경로는 판정 주기 하나, Q12); `OperatorEnvelopeService.append`의 `unattributedIfErased` 인자를 없애고(모든 운영자 행이 판정) `accountUsable`이 생명주기를 잠그지 않는 읽기이고 순서는 원장 잠금이 정한다는 근거를 주석으로(Q13); identity 시험 여섯 클래스의 키 동적 속성을 공용 base `IdentityKeyedTestBase`로 모으고 `IdentityTestKeys`를 지움 — 키 없는 `DeletedIdentityKeylessTest`는 상속하지 않음(Q14); `AccountDeletionRequestService`의 SHA-256 주석·변수 이름(`requestSessionId`), V33 주석("이 마이그레이션이 V30 요청 행에 더한다" — V33은 아직 어느 환경에도 적용 전이다), 위 "일곱 단계"를 여덟으로, 차단 기록 `request_id`가 실행 행(`user_id`)을 거쳐 계정에 닿고 그 보존은 deferred 173(G-5)과 같은 주인이라는 문단(Q15); 새 코드의 정규화된 이름을 import로·`BackupDestructionJudgeRoleTest` 긴 줄 정리·`REQUEST_SESSION_GONE`을 구조화 key `code`로(Q16); 완료 상태 줄의 기한 안 문장은 `progress.stateErased`를 참조(`stateErasedPending` 삭제)하고 완료 화면의 백업 파기 기한은 시각까지(`accountDeletionDateText` 삭제, Q17). 시험: `DeletionCompletionTest` +5(다른 사용자 세션 정리·인가 요청 보존과 콜백·실행 행 없는 기록·절대 수명·삭제 identity 재로그인), `AccountDeletionTest` +1(재확인 되돌림, spy), `BackupDestructionJudgeRoleTest` +1, `ActorDigestTest` 2, 프런트 완료 시험 +4(읽지 못함·다시 확인 제목·다시 시도 401의 완료/만료 두 갈래)와 요청 ID 단정. 검증: 바뀐·영향 백엔드 시험 클래스(`DeletionCompletionTest`·`Restore*`·`AccountDeletionRequestTest`·`AccountErasure*`·`ModuleBoundaryTest`·`ControllerSupportTest`·`identity.*`·`BackupDestructionJudge*`·`Auth*`·`Operator*`·`ErasureAuditLockTest`·`SessionGuardTest`·`UnreadableSessionAttributeTest`·`OperationsDeletionTest`·`ServiceNotice*`·`SupportInquiryTest`·`DeletionBackupMigrationTest`·`LogRedactionTest`·`operations.internal.*`) 652건 통과(`-Werror` 경고 없음), 프런트 typecheck·lint·lint:css·format:check와 전체 시험 1,646건(1 skip) 통과, `test-restore-scripts.sh` 75/75, `test-ci-scripts.sh` 380/380. 리허설·백엔드 전체 `build`는 PR CI가 판정한다.

## Design Notes

**완료 세션의 모양.** user_id를 걷어내는 이유는 둘이다 — 삭제 뒤 잔여(`remainingRowsOf`의 spring_session)가 0이어야 하고, 그 세션이 다시 인증 문맥으로 쓰이면 안 된다. 세션은 request ID만 들고, 완료 조회는 그 ID로 identity 실행 행(운영 완료·전체 완료)과 operations tombstone(파기 상태)을 읽는다. 로그아웃은 기존 폐기 세 조건(행 삭제·`SecurityContext` 정리·쿠키 만료)을 그대로 만족해야 한다.

**표지 `deleted`.** 지금 표지 계약은 "삭제 계정을 구분하지 않는다"이다. 이번 구분은 그 identity를 가진 본인에게만 보이고(로그인 성공 직후의 같은 브라우저), 재가입이 막힌 이유를 숨기면 사용자가 계속 시도한다. 타 사용자·대상 접근의 `DENIED` 하나 규칙은 그대로다.

**재가입 차단의 범위와 키.** 차단은 삭제 시점에 그 계정에 연결돼 있던 매핑뿐이다 — 그 전에 해제(unlink)한 로그인 수단은 그 계정의 매핑이 아니므로 막지 않는다. 기록은 감사 digest 키에 묶이므로 그 키를 회전하면 기존 기록이 대조되지 않는다 — 회전 절차는 tombstone 키 회전(deferred 139)과 같은 주인이 정한다.

**완료 화면의 한계.** 완료 확인 세션의 수명(유휴 30분·처음 로그인 기준 절대 12시간)은 백업 파기(요청부터 최대 30일)보다 짧아 이 화면에서 전체 삭제 완료를 보지 못할 수 있다. 화면이 그 사실과 지원 연락처를 말하고 영수증·무세션 조회는 두지 않는다(Never).

**재가입 차단 digest의 보존.** G-5 전 production 삭제가 막혀 있어 이 표는 개발·CI에만 생긴다. 보존 기한·해제는 G-5가 정한다 — 그 전까지 해제 경로를 만들지 않는다. 서버 정책 상수(고지 hash 입력)는 "남기는 것"을 담지 않으므로 화면 문구만 고치고 정책 버전은 그대로다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 통과(로컬 소켓 결함이면 대상 클래스 재실행과 PR CI `backend-quality`)
- `npm --prefix frontend run typecheck`·`lint`·`lint:css`·`format:check`·`test` -- expected: 통과
- `bash .github/scripts/test-ci-scripts.sh`·`bash deploy/production/test-restore-scripts.sh` -- expected: 통과(리허설은 PR CI가 판정)

## Suggested Review Order

**재가입 차단(D1)**

- 삭제 실행이 매핑을 지우기 전에 같은 트랜잭션에서 `(issuer, subject)` digest를 남긴다
  [`AccountDeletionService.java:48`](../../backend/src/main/java/com/bokrilog/identity/internal/AccountDeletionService.java#L48)

- 판정 넷(NONE·미완료·DELETED·판정 불가) — 실행 행이 없거나 요청 ID 없으면 DELETED
  [`AccountLifecycleService.java:139`](../../backend/src/main/java/com/bokrilog/identity/internal/AccountLifecycleService.java#L139)

- 연결 INSERT 술어와 성공 뒤 재확인이 삭제 커밋과 겹친 경합을 닫는다
  [`IdentityAccountRepository.java:103`](../../backend/src/main/java/com/bokrilog/identity/internal/IdentityAccountRepository.java#L103)

- digest 입력은 접두사·issuer 길이로 경계를 모호하지 않게 만든다
  [`ActorDigest.java:104`](../../backend/src/main/java/com/bokrilog/operations/internal/ActorDigest.java#L104)

- 표지 `deleted`는 삭제가 끝난 identity에만, 판정 예외는 `failed`로 접는다
  [`AuthFlowHandlers.java:1136`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L1136)

**완료 확인 전용 세션(D2)**

- IDENTITY 커밋이 요청 세션을 지우지 않고 주체·속성을 걷어 요청 ID로 잇는다
  [`IdentityAccountRepository.java:217`](../../backend/src/main/java/com/bokrilog/identity/internal/IdentityAccountRepository.java#L217)

- 미인증 세션에서만 완료 연결을 읽고 로그인 성공은 연결을 지운다
  [`DeletionCompletionSessions.java:44`](../../backend/src/main/java/com/bokrilog/DeletionCompletionSessions.java#L44)

- 완료 세션이 닿는 경로는 완료 조회와 확정 재전송 거부 둘뿐이다
  [`AuthSecurityConfig.java:1073`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L1073)

- 판정 주기 정리는 되살아난 주체만 비우고 다른 사용자 세션은 연결만 지운다
  [`IdentityAccountRepository.java:263`](../../backend/src/main/java/com/bokrilog/identity/internal/IdentityAccountRepository.java#L263)

**전체 삭제 완료(D3)와 완료 안내**

- 파기 완료·법정 보존 처리가 함께일 때만 전체 완료를 기록하고 실패는 정리를 막지 않는다
  [`BackupDestructionJudge.java:169`](../../backend/src/main/java/com/bokrilog/BackupDestructionJudge.java#L169)

- 완료 조회가 운영 완료·기한·파기 상태·전체 완료·지원 연락처를 싣는다
  [`AccountDeletionController.java:272`](../../backend/src/main/java/com/bokrilog/AccountDeletionController.java#L272)

- 진행 화면이 완료 표지만으로 완료 문구를 고르고 그 자리에서 완료 화면이 된다
  [`DeletionProgressScreen.tsx:53`](../../frontend/src/screens/DeletionProgressScreen.tsx#L53)

- 세션 게이트가 완료 세션을 만료로 세지 않는다
  [`useSessionState.ts:87`](../../frontend/src/session/useSessionState.ts#L87)

**복원·162**

- 복원 보고가 모듈별 잔여를 싣고 합은 내역에서만 낸다
  [`RestoreInvariants.java:47`](../../backend/src/main/java/com/bokrilog/RestoreInvariants.java#L47)

- 원장 잠금 뒤 수행자가 삭제 중이면 귀속 없이 쓴다(잠그지 않는 읽기)
  [`OperatorEnvelopeService.java:598`](../../backend/src/main/java/com/bokrilog/operations/internal/OperatorEnvelopeService.java#L598)

**주변**

- 스키마: 차단 digest·완료 연결 표, 요청 세션 키, 전체 완료 상태
  [`V33__deletion_completion.sql:42`](../../backend/src/main/resources/db/migration/V33__deletion_completion.sql#L42)

- 리허설 단정: 모듈별 잔여 0과 재가입 digest 재생성
  [`rehearse-restore.sh:792`](../../deploy/production/rehearse-restore.sh#L792)

- 지원 연락처 모양 판정 하나로 세 화면이 공유
  [`supportContact.ts:13`](../../frontend/src/session/supportContact.ts#L13)

- AC-5 실환경 격리 시험 절차·결과 기록 자리(상태 미수행)
  [`DEVELOPMENT.md:735`](../../DEVELOPMENT.md#L735)

- 매트릭스 시험
  [`DeletionCompletionTest.java:97`](../../backend/src/test/java/com/bokrilog/DeletionCompletionTest.java#L97)
