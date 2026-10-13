---
title: '운영 command 상태 기계 공통화와 되돌림 계보를 따르는 서비스 공지'
type: 'refactor'
created: '2026-10-13'
status: 'done'
review_loop_iteration: 0
baseline_commit: 'b318c2125ee5c07fe303590f23e6572537d47f45'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-retro-2026-10-13.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-5-service-notice.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:**
- **운영 command 사본 (Epic 3 회고 D-1, AI-5).** 운영 command 일곱(감사 추출, 제한 넷, 해제, 공지 발행, 문의 갱신)은 준비 → 확인 → 실행 흐름을 command마다 베껴 쓴다.
  - 새 command 하나를 더하려면 백엔드 약 12곳을 고쳐야 한다. `Armed`의 nullable 칸, if 사슬, `instanceof` 분기, 확정 prologue 다섯 벌이 그 자리다.
  - 프런트엔드는 같은 상태 기계 훅 다섯 벌과 API 함수 다섯 벌을 쓴다.
  - 3.5가 "네 번째 사본 전에 묶자"고 미뤘는데 3.6이 네 번째를 만들었다.
- **잘못된 복구 공지 (회고 B-1, AI-1).** 해제 smoke가 실패하면 제한이 새 ID R2(`reinstated_from = R1`)로 되돌아간다. 그런데 공지는 제한 ID를 정확히 일치로만 대조한다.
  - 사용자 화면에서 진행 중 공지가 사라지고 `RESTRICTED_WITHOUT_NOTICE`가 뜬다.
  - R1만 덮는 RECOVERED 공지가 통과해, R2가 막고 있는데 "복구됨"을 알린다.

**Approach:**
- **백엔드.** command마다 전략 객체 하나(`OperatorCommand`)가 자기 몫만 정의한다. 해석, 검증, 준비 뒤 거절, 미리보기, 확정 본문, 커밋 뒤 처리, 재전송 재구성, 응답 모양이 그 몫이다. 엔진은 무장·허가·잠금·최종 권한·회전·감사 순서를 한 번만 갖는다.
- **프런트엔드.** 공통 command 상태 기계 훅 하나와 API 삼종(prepare·confirm·cancel) 하나에 command별 설정만 넘긴다.
- **공지.** 공지의 덮는 단위를 제한 하나가 아니라 **되돌림 계보**로 바꾼다.
- **경계.** 외부 동작(HTTP 경로·본문·code·원장 행·화면 카피)은 그대로다. 공지 계보만 의도한 변경이다.

## Boundaries & Constraints

**Always:**
- **동작 보존.** 다음은 바꾸지 않는다.
  - 운영자 경로·메서드·요청·응답 본문·code·HTTP 상태
  - 원장(`operations_audit_event`) 행의 command·scope·target·outcome·details 키
  - 확인 문구·`POLICY_VERSION`
  - 프런트엔드 화면 DOM·카피·초점·live region
  - 기존 백엔드 시험(`OperatorCommandTest`·`OperatorRestrictionTest`·`OperatorRecoveryTest`·`ServiceNoticeTest`·`SupportInquiryTest`·`OperatorAccessTest`·`OperatorAuditTest`), 프런트엔드 시험(`OperatorScreen.test.tsx`·`OperatorNotice.test.tsx`·`OperatorInquiry.test.tsx`), `e2e/operator.spec.ts`, `verify-same-origin.sh` 운영자 절
  - 위 시험은 기대값을 고치지 않고 통과해야 한다. 공지 계보로 의도가 바뀌는 단정만 예외이고, 그 단정은 Change Log에 근거를 적는다.
- **백엔드 엔진.**
  - `OperatorCommand<A extends Serializable, R>` 구현 일곱이 command 이름·권한·해석·`validate`·`stateRefusal`·`preview`·`complete`·`afterCommit`·`replay`·`view`를 갖는다. 제한 넷은 기능 값 하나로 갈리는 한 구현이어도 된다.
  - 확정 prologue(트랜잭션 가드 → `lockAppends` → 최종 권한 → `ACCESS_REVOKED`)는 엔진 템플릿 한 곳에만 둔다.
  - `Armed`는 command별 nullable 칸 대신 `Serializable` payload 하나를 든다.
  - `OperatorEnvelopeService`의 command별 if 사슬, `OperatorCommandService`의 `instanceof` 분기, `OperatorController`의 command별 switch는 등록표 조회로 바꾼다.
  - 공통 부품 셋(`SessionArming`·`SessionRotation`·`IdempotencyKey`)은 그대로 쓴다.
  - SQL은 지금 저장소 클래스에 그대로 둔다. 모듈 경계도 그대로다. 공개 API는 모듈 루트, 구현은 `internal`이다.
- **무장 직렬화.** `serialVersionUID`를 올린다. 배포 전에 무장한 세션은 확정 때 지금과 같은 단일 거절(재준비 요구)이 된다. 운영 서비스가 아직 없어 수용한다. 이 결정은 3.2 defer(`Armed` 필드 증가 패턴)의 해소로 기록한다.
- **프런트엔드.**
  - `session/useOperatorCommand.ts` 하나가 공통 상태 기계를 맡는다.
    - 단계: `idle·preparing·armed·confirming·completed·refused·cancelled`
    - 멱등 키 생성·재생성
    - 이중 제출 방지
    - `unanswered` → uncertain
    - `CONFIRMATION_MISMATCH` → 무장 유지
    - 목록을 바꾸는 code의 새로고침 콜백
    - 취소
  - 다섯 훅은 그 위의 얇은 설정(입력·payload·목록·code 집합)이 된다.
  - `operatorApi.ts`의 prepare·confirm·cancel 삼종 다섯 벌은 command 이름을 받는 한 벌과 command별 응답 해석기로 줄인다.
  - 목록 sequence·AbortController는 공통 도우미 하나로 둔다. 기존 공통 부품(`useConfirmationDialog`·`isInstant`)은 그대로 쓴다.
- **공지 계보(B-1).**
  - 공지 cover R은 R과 R에서 `reinstated_from`으로 이어진 모든 후손을 덮는다. 다중 hop도 포함한다.
  - **사용자 읽기**(`ServiceNoticeService.read`·`latestRelevant`): 효력 제한 X는 X 자신이나 X의 조상이 cover에 있으면 덮인 것이다. 그래서 R2는 R1을 덮는 공지에 덮인다.
  - **발행 검증**(`coverRefusal`·`completeNotice`, 준비·확정 둘 다):
    - 진행 중(INVESTIGATING·SCOPE_CHANGED) 공지는 cover의 계보 안에 열린 제한이 하나 이상 있어야 한다.
    - RECOVERED는 cover 계보 전체가 닫혀 있어야 한다.
    - 그래서 R2가 열려 있으면 R1만 덮는 RECOVERED는 거절된다(`TARGET_INVALID`).
  - 계보 질의는 `OperationalRestrictionRepository`의 기존 `LINEAGE` 재귀 CTE 관용으로 더한다(조상·후손 방향).
  - 운영자 목록 응답에 `reinstatedFrom`(선택 필드)을 더한다. 화면의 공지 cover 기본값은 계보로 이어진 현재 열린 제한을 고른다.
- **시험.**
  - 공지 계보:
    - R1 → R2(→ R3) 되돌림 뒤 사용자 공지가 이어지는지
    - RESTRICTED_WITHOUT_NOTICE가 사라지는지
    - R2가 열린 동안 RECOVERED가 거절되는지
    - R2까지 닫힌 뒤 RECOVERED가 통과하는지
    - `ServiceNoticeTest`와 `OperatorRecoveryTest`의 `hookSmoke` 관용으로 쓴다.
  - 엔진: 등록표가 일곱 command를 모두 덮는지, 템플릿 prologue를 우회하는 구현이 없는지(ArchUnit 또는 단위).
  - 프런트엔드: `useOperatorCommand` 훅 단위 시험(상태 전이·키·unanswered·mismatch·취소).
- **문서.** `CLAUDE.md` 운영자 표면 계약 줄을 고친다. 새 command는 `OperatorCommand` 구현 하나와 등록, 화면은 `useOperatorCommand`를 쓴다. 공지는 계보 단위로 덮는다. `deferred-work-resolutions.md`에 3.5 defer와 3.2 `Armed` defer의 해소를 기록한다. 새 주석은 한국어다.

**Ask First:**
- 외부 동작(경로·본문·code·원장 details·카피)을 바꿔야 하는 경우
- 새 migration이 필요한 경우(계보는 V20의 `reinstated_from`으로 충분해야 한다)
- 무장 호환 역직렬화를 두는 경우
- 대시보드 훅·발행 서비스 분할(Epic 2 item-21)까지 넓히는 경우

**Never:**
- 새 command 추가
- 원장 스키마 변경
- 시험 기대값을 리팩터에 맞게 고치는 것(공지 계보 의도 변경만 예외)
- 공통 부품 사본 만들기
- 공지가 제한을 풀거나 숨기는 것

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 기존 command 일곱 | 지금 시험·e2e·검증 스크립트 | 전부 같은 결과(경로·본문·code·원장 행·화면) | — |
| 되돌림 뒤 사용자 공지 | R1 덮는 INVESTIGATING, R1 해제 smoke 실패 → R2 | `GET /api/notices`가 그 공지를 R2에 대해 보여 준다, `RESTRICTED_WITHOUT_NOTICE` 없음 | — |
| 다중 hop | R1 → R2 → R3 | R3도 R1 공지에 덮인다 | — |
| 잘못된 복구 | R2 열림, covers {R1}로 RECOVERED | 거절 | `TARGET_INVALID`, 준비·확정 둘 다 |
| 올바른 복구 | R2까지 닫힘, covers {R1} | RECOVERED 통과 | — |
| 운영자 cover 기본값 | 최신 covers {R1}, 열린 R2 | 화면 기본값이 R2를 고른다 | — |
| 배포 전 무장 | 이전 `serialVersionUID`로 무장한 세션 | 확정 시 기존 단일 거절(재준비) | — |
| 새 command 추가 비용 | 엔진 | 구현 하나 + 등록 한 줄(시험이 등록표 완전성 고정) | — |

</frozen-after-approval>

## Code Map

- **백엔드(BE = `backend/src/main/java/com/bokrilog`):**
  - `operations/OperatorCommandApi.java:31-82`(상수), `:209-226`(`Proposal`), `:245-351`(payload·`Accepted`), `:461`(`CommandResult`), `:526-650`(Result·Completion·`completeX`), `:109`(`POLICY_VERSION` — 바꾸지 않는다)
  - `operations/internal/OperatorEnvelopeService.java:133-172`(`isSupported`·`permissionsOf`·`validate` 사슬), `:190-444`(`validate*`, `Accepted(..., null...)`), `:720-770`(`recordRefusedProposal`), `:780-1041`(`complete*` prologue 다섯·`revertLift`), `:1063-1098`(`replay`)
  - `OperatorCommandFlow.java:61-68`(`Armed`·`serialVersionUID` 5L)
  - `OperatorCommandService.java:142-148`(`Preparation`·`Refused.detail`), `:258-261,299-360`(unpack·거절 블록), `:390-393`(`Armed` 생성), `:451-594`(confirm 사슬·DuplicateKey·커밋 뒤 instanceof), `:712-726`(`replayed`)
  - `OperatorController.java:596,637,731`(일반 끝점), `:679-718`(`completedBody`·`preparedImpact`), `:784-849`(`proposalOf`), `:375-382`(`ActiveRestriction`)
- **공지 계보:**
  - `operations/internal/ServiceNoticeService.java:23-26`(정확 일치 규칙 javadoc — 고친다), `:67-130`(`read`)
  - `ServiceNoticeRepository.java:87-97`(`latestRelevant`)
  - `OperatorEnvelopeService.java:362-363`(covers 상속), `:484-502`(`coverRefusal`), `:870`(확정 재검사)
  - `OperationalRestrictionRepository.java:49-51`(`LINEAGE` CTE, 조상 방향), `:136-158`(`matchingAmong`), `:164-178`(`coverFacts`), `:304-315`(`reinstate`)
  - V20:30-33(`reinstated_from` FK)
  - `ServiceNoticeController.java:59,103-175`
- **프런트엔드:**
  - `frontend/src/session/operatorApi.ts`(삼종 :145/187/242, :333/380/431, :515/565/618, :730/779/829, :1041/1089/1131; `post` :1444, `read` :1401; 해석기 :1179-1389)
  - 훅 `useOperator{Export,Restriction,Recovery,Notice,Inquiry}.ts`(공통·차이는 조사 결과)
  - `screens/OperatorScreen.tsx:441-507`(배선), `:498,1466`(cover 기본값), `:2192-2233`(대화상자)
- **시험:**
  - 백엔드: `OperatorCommandTest`(19), `OperatorRestrictionTest`(11), `OperatorRecoveryTest`(20, smoke :652-786 `hookSmoke`), `ServiceNoticeTest`(15, :223·:385·:467 확장 지점), `SupportInquiryTest` 운영자 부분
  - 프런트엔드: `OperatorScreen.test.tsx`(45), `OperatorNotice.test.tsx`, `OperatorInquiry.test.tsx`
  - e2e: `e2e/operator.spec.ts:183,252`
  - `verify-same-origin.sh:2715-2818`
- **defer:** `deferred-work.md:368-370`(3.5 상태 기계), `:366-367`(3.2 `Armed` 필드·`serialVersionUID`)

## Tasks & Acceptance

**Execution:**
- [x] `operations/OperatorCommandApi`·`internal/OperatorEnvelopeService` -- command 전략 인터페이스·등록표·확정 템플릿(prologue 한 곳), command별 구현으로 이동.
- [x] `OperatorCommandFlow`·`OperatorCommandService`·`OperatorController` -- payload 하나의 `Armed`, 등록표 조회로 분기 제거.
- [x] `OperationalRestrictionRepository`·`ServiceNoticeService`·`ServiceNoticeRepository`·`coverRefusal`·`completeNotice` -- 계보 질의와 계보 단위 덮기·검증. 목록 응답 `reinstatedFrom`.
- [x] `frontend/src/session/useOperatorCommand.ts`·`operatorApi.ts`·훅 다섯·`OperatorScreen.tsx` -- 공통 상태 기계·API 한 벌, cover 기본값 계보.
- [x] 시험 -- 공지 계보(백엔드·화면), 엔진 등록 완전성·prologue 우회 금지, `useOperatorCommand` 단위, 기존 시험 무변경 통과.
- [x] `CLAUDE.md`·`deferred-work-resolutions.md` -- 계약 줄과 defer 해소.

**Acceptance Criteria:**
- Given 기존 운영자 시험·e2e·`verify-same-origin.sh`를 돌리면, then 기대값 변경 없이 통과한다(공지 계보 단정만 예외).
- Given 새 command를 더한다고 할 때, then 고쳐야 할 곳은 전략 구현 하나와 등록, 화면은 설정 하나다(Design Notes 예시와 등록 완전성 시험).
- Given 백엔드 build·프런트엔드 전체 검증·주입 origin e2e·`test-ci-scripts.sh`를 돌리면, then 모두 통과한다.

## Spec Change Log

- 2026-10-13 구현(SPEC 본문 변경 없음). 구현에서 확정한 것과 Design Notes 예시(형태만)와 다른 자리:
  - **전략의 자리.** 전략 인터페이스 `OperatorCommand`와 구현 다섯(`AuditExportCommand`·`RestrictCommand`(제한 넷)·`LiftRestrictionCommand`·`PublishNoticeCommand`·`UpdateInquiryCommand`)은 `operations.internal`에 있고, 등록표는 `OperatorEnvelopeService` 생성자의 목록 한 줄씩이다. 모듈 밖 사실(제한 대상·영향·해제 차원·지원 연락처·계정 상태)은 operations가 정의하고 루트 `OperatorCommandFacts`가 구현하는 port `OperatorCommandEnvironment`로만 닿는다(`PlanPublicationPort` 관용). 루트 흐름·컨트롤러는 `OperatorCommandApi`의 command 이름 조회만 쓴다.
  - **`stateRefusal`·`preview`는 `inspect` 하나다.** 제한의 대상 확인이 거절과 영향 미리보기를 함께 정하므로 대상 사실을 한 번만 읽는다(이전 흐름과 같은 읽기 수).
  - **해제 본문은 엔진의 `completeLift`에 남았다.** `OperatorRecoveryTest`의 소스 스캔이 해제 UPDATE의 유일한 호출 자리를 `OperatorEnvelopeService.java`의 `public LiftCompletion completeLift(`로 고정한다(기대값 무변경 원칙). 해제 전략의 `complete`가 그것을 부르고, prologue는 템플릿에 있다(`OperatorCommandRegistryTest`가 `completeLift`의 호출자를 해제 전략 하나로 고정한다). `revertLift`·`recordSmokePassed`는 `OperatorRecoveryTest`가 spy로 감싸므로 `OperatorCommandApi`에 남고, 커밋 뒤 처리는 그 빈을 인자로 받는다.
  - **거절은 예외 하나다.** command별 `Completion` sealed 넷 대신 `OperatorCommandApi.CommandRefusal`(스택 없음)을 템플릿·전략이 던지고 허가 소비의 트랜잭션이 되돌린다. 응답 본문·미리보기는 전략이 만든 `Map`이고(필드 순서·`null` 생략 규칙은 이전 record와 같다), 재전송은 원장 `details` JSON을 그 command의 전략이 다시 읽는다(`OperatorAuditRepository.completed`가 열 추출 대신 `details` 원문을 준다).
  - **동작이 미세하게 달라진 두 자리(외부 계약 밖).** (1) 내보내기의 `operator command completed` 로그가 행 재조회 **뒤**에 남는다 — 재조회가 실패하면 경고 한 줄만 남는다. (2) 공지 확정의 지원 연락처 확인이 템플릿 prologue(원장 잠금·최종 권한) 뒤로 옮겼다 — 연락처가 비고 권한도 회수된 확정은 422가 아니라 404다.
  - **화면 cover 기본값.** 목록 응답의 `reinstatedFrom`은 직접 부모(DB 열 그대로)다. 화면은 사건 최신 버전의 덮는 제한에서 활성 목록 안의 되돌림 사슬을 끝까지 따라간다(`liveLineage`). 닫힌 중간 행을 거치는 다중 hop(R1 → R2(닫힘) → R3)은 화면 기본값이 R3를 고르지 못한다 — 서버의 사용자 공지·발행 검증은 다중 hop을 덮는다.
  - **기존 시험 기대값 변경 0건.** 새 시험: 백엔드 `ServiceNoticeLineageTest`(R1→R2·R3 되돌림 뒤 사용자 공지 지속·`RESTRICTED_WITHOUT_NOTICE` 없음·열린 계보의 복구 공지 준비·확정 거절·계보가 닫힌 뒤 통과·목록 `reinstatedFrom`, `OperatorRecoveryTest`와 같은 spy 구성으로 컨텍스트 공유), `operations.internal.OperatorCommandRegistryTest`(등록표 = 공개 command 이름 상수 일곱, 구현 전부 등록, 중복 이름 거부, ArchUnit으로 전략의 잠금·트랜잭션 우회 금지와 `complete`·`completeLift` 호출자 고정). 프런트엔드 `useOperatorCommand.test.tsx`(전이·키·unanswered·mismatch·취소·목록 콜백·이중 제출), `OperatorNotice.test.tsx`에 계보 cover 기본값·복구 공지의 열린 되돌림 집계 두 건.

- 2026-10-13 매트릭스 감사 patch — I/O 행 "배포 전 무장"(SPEC 본문 변경 없음). 새 `OperatorArmingVersionTest`가 실제 JDBC 세션 저장소의 무장 속성 바이트를 바꿔 놓고 HTTP로 재현했다: 배포 전 모양(payload 칸이 없는 버전 5 스트림)·지금 빌드에 없는 payload 클래스·깨진 바이트. 고치기 전에는 확정이 500이었고, 취소는 무장을 버리지 못했으며(Spring Session의 `HttpSession` 어댑터가 쓰기·지우기 전에도 옛 값을 먼저 읽어 같은 변환 예외를 낸다), 준비도 같은 이유로 막혔다. record는 `serialVersionUID`를 대조하지 않으므로 버전만 다른 바이트는 그대로 읽힌다 — 옛 모양을 거르는 것은 칸 구성이다. 고친 것: (1) `SessionAttributeConversion`이 Spring Session JDBC의 `springSessionConversionService`를 둔다 — 쓰기는 기본과 같은 Java 직렬화이고, 풀 수 없는 바이트는 예외 대신 없는 속성(`null`)으로 읽고 예외 단순 이름 한 줄만 경고로 남긴다(세션 저장소 수준이라 모든 Flow와 속성이 같은 규칙을 받는다. 로그인 문맥이 풀리지 않으면 미인증과 같다). (2) `SessionArming.peekIncludingExpired`가 이름은 있는데 값이 `null`인 속성(세션은 `null`을 저장하지 않는다)을 읽을 수 없는 값으로 보고 그 속성만 지운다 — 타입이 어긋난 값은 여전히 버리지 않는다(`SessionArmingTest` 기존 단정 그대로, 새 단위 시험 하나). (3) `OperatorCommandFlow.Armed`의 compact 생성자가 payload를 요구한다 — record 역직렬화가 이 생성자를 지나므로 payload 칸이 없는 옛 모양이 읽기 실패로 걸러진다. 결과: 세 경우 모두 확정이 무장이 없는 확정과 같은 403 `OPERATOR_COMMAND_NOT_CONFIRMED`(같은 문장)이고, 무장 속성 하나만 지워지며, 재인증 시각·로그인이 남아 감사 조회 200과 같은 세션의 재준비 200이 된다. 취소는 204이고 깨진 무장을 지운다.

- 2026-10-13 사용자 결정 반영(SPEC 본문 변경 없음). (1) **공지 확정의 판정 순서 — 수용된 편차.** 지원 연락처 확인이 템플릿 prologue(원장 잠금 → 최종 권한) 뒤의 공지 본문으로 옮겨, 확정 요청 전에 연락처가 비고 원장 잠금을 기다리는 사이 권한이 회수되면 422 `SUPPORT_CONTACT_UNCONFIGURED`가 아니라 404(`ACCESS_REVOKED`)다. 사용자가 2026-10-13에 이 순서를 수용했고, `ServiceNoticeTest.확정_순간_권한_회수가_빈_연락처보다_먼저인_404다`가 그 순서를 고정한다(쓰기 없음, `REQUESTED`·`DENIED:ACCESS_REVOKED`). 권한이 확정 요청 전에 빠지면 게이트가 이전처럼 404다. (2) **`liveCovers`(Option A, 사용자 결정).** `GET /api/operator/notices`의 사건마다 최신 버전에 선택 필드 `liveCovers`(덮는 제한의 되돌림 계보 가운데 지금 열린 제한, 열린 cover 자신 포함, 먼저 시작한 순)를 싣는다 — `OperationalRestrictionRepository.openLineage`(`DESCENT` CTE, 최신 버전 전체를 한 문장)를 `OperationalRestrictionApi.openLineage`가 낸다. 이전 버전에는 싣지 않는다. 화면은 `liveLineage()` 걷기를 없애고 `liveCovers`를 공지 덮는 제한 기본값과 "아직 효력 중" 집계에 그대로 쓴다(서버가 싣지 않은 응답은 덮는 제한 가운데 활성인 것으로 접는다 — 이전 동작). 그래서 앞 줄의 "다중 hop 기본값" 한계가 사라졌다. 시험: `ServiceNoticeLineageTest.사건_목록의_liveCovers는_닫힌_중간_행을_지나_지금_열린_되돌림을_싣는다`(R1 → R2(닫힘) → R3에서 R1 사건·중간 행 R2 사건 모두 [R3], 열린 cover 자신, 계보가 모두 닫히면 빈 배열, 이전 버전엔 없음), `OperatorNotice.test.tsx`의 계보 두 건을 다중 hop(R3의 `reinstatedFrom`이 목록에 없는 R2)으로 바꿨다 — 이 chore가 더한 시험이다.
- 2026-10-13 `liveCovers` 재검증 patch(SPEC 본문 변경 없음). 주입 origin `e2e/operator.spec.ts:252`가 실패했다 — 사건 목록은 복구 전에 읽히고 복구는 활성 제한 목록만 다시 읽으므로, 복구 공지 단계에서 오래된 `liveCovers`가 이미 닫힌 제한을 "아직 효력 중"으로 세어 준비를 막았다. 화면은 `liveCovers`(없으면 `covers`)를 지금 활성 목록에 있는 것으로만 센다 — 다중 hop의 R3는 활성 목록에 있으므로 그대로 고른다. `OperatorNotice.test.tsx`에 그 경우 한 건을 더했다.
- 2026-10-13 리뷰 patch 루프(SPEC 본문 변경 없음) — patch 18·defer 2·reject 4. **patch:** (1) 세션 판독 규칙 — 풀 수 없는 속성 바이트는 `null`이 아니라 표지 `UnreadableSessionValue`로 읽고(이전 patch의 `null`은 손상된 재인증 표지를 "표시 없음"으로 푸는 fail-open이었다) 무장은 없는 무장으로 접어 그 속성만 지우고, 재인증 표지는 `SessionGuard.isMarked` 한 곳이 표시된 것으로 보며(세 판독 자리가 그것을 부른다), 로그인 문맥은 Spring Security가 미인증으로 읽고 주체 이름 색인은 `ReadablePrincipalNameIndexResolver`가 타입으로 읽는다(기본 SpEL resolver는 저장마다 500이었다). 경고는 세션·속성마다 한 번(상한 512의 LRU), 예외 단순 이름만. `UnreadableSessionAttributeTest`가 로그인 문맥·재인증 표지 바이트를 깨뜨려 세션 응답(미인증 본문과 글자까지 같음)·보호 경로 401·로그아웃 계약·표시된 세션과 같은 민감 command 거절을 HTTP로 본다. (2) prologue 우회 ArchUnit 규칙을 `DescribedPredicate<JavaMethodCall>`(대상이 `OperatorCommand`에 대입되는 `complete`, bridge 제외)로 바꾸고 일시적 위반 호출로 실패를 확인한 뒤 지웠다. 기대 command 이름은 `OperatorCommandApi.COMMANDS`에서 온다. (3) 모양이 어긋난 완료 행의 재전송은 기준 `b318c21`의 차례(그 command → 제한 생성의 사실 → 추출, 없는 값은 0·거짓)로 접는다(`OperatorReplayFallbackTest`). (4) 다른 command의 `Proposal.input`은 `IllegalArgumentException`(`CommandJson.inputOf`). (5) `OperatorCommandContractTest`가 일곱 command의 미리보기·확정 본문 JSON을 리팩터 전 record 정의와 글자까지 대조하고 `OperatorPreparedViewTest`가 미리보기의 `null`이 준비 응답에서 살아남는지 본다. (6) 전략은 엔진을 필드로 들지 않고 세 단계 모두 인자(`OperatorEnvelopeService engine`, 호출된 그 빈)로만 받는다 — 생성자의 `this` 전달이 없어졌다. (7) `OperatorControllerRefusalsTest`. (8) `liveCovers`는 `ServiceNoticeApi.incidents`가 사건과 함께 읽기 전용 트랜잭션 하나에서 계산하고(`OperationalRestrictionApi.openLineage`는 없앴다), 버전 없는 사건은 `Incident` 생성자가 거부한다. (9) `OperationalRestrictionApi.isActive` javadoc을 마이그레이션 시험 전용으로 고쳤다(그 시험이 써서 남긴다). (10) 문의 `answer`는 준비·무장·확정 중에만 있다(`useOperatorInquiry.test.tsx`). (11) 덮는 제한 기본값은 운영자가 고치기 전까지 활성 목록과 함께 다시 계산한다(`OperatorNotice.test.tsx`). (12) `useOperatorCommand.test.tsx`에 확정 이중 제출·전송 거부 `.catch`·`pendingOperationOf`·`confirmTimeoutMs`·무장 전 취소·진행 중 reset 6건과 중립 도우미 `stubOperatorCommand`. (13) `useSequencedRead.test.tsx`, CLAUDE.md 공통 부품에 그 구분을 적었다. (14) 화면 타입·해석기에서 `reinstatedFrom`을 뺐고 백엔드 javadoc을 정보용으로 고쳤다. (15) KST 주석을 `nextUpdateIso` 위로 되돌렸다. (16) CLAUDE.md의 `liveCovers`·새 command 비용·화면 하나의 훅 다섯 문장. (17) `deferred-work-resolutions.md` 118·119의 확인 수치를 최종 실행으로. (18) 이 줄. **defer(조정자가 기록):** `reinstated_from` 색인(마이그레이션, Ask First), 실제 v5 바이트 fixture. **reject:** `openLineage` 실패 fallback(이제 한 트랜잭션), `SessionArming` 동시 discard 경합, ArchUnit 전이 의존(`ModuleBoundaryTest` 선례), 4번 밖의 API 타입 안전 손실. 최종 확인: 백엔드 build 1465건, 프런트엔드 전체(Vitest 1378·Playwright 40), `verify-same-origin.sh` 1051/1051, 주입 origin Playwright 68, `test-ci-scripts.sh` 239/239.

## Design Notes

- **전략 하나 = command 하나.** 엔진 템플릿은 다음 순서를 한 곳에 둔다.
  1. 무장 확인
  2. 허가 소비(같은 트랜잭션)
  3. 원장 잠금
  4. 최종 권한
  5. command `complete`
  6. `COMPLETED` 행
  7. 커밋 뒤 `afterCommit`
  8. 세션 회전

  command는 5와 7의 내용만 안다. 예(형태만):

  ```java
  interface OperatorCommand<A extends Serializable, R> {
      Set<String> names(); Set<Permission> permissions();
      Proposal parse(JsonNode body); Accepted<A> validate(Proposal p);
      Optional<Refusal> stateRefusal(A payload); Map<String,Object> preview(A payload);
      Completion<R> complete(Envelope e, A payload); void afterCommit(R result);
      Optional<R> replay(AuditRow completed); Map<String,Object> view(R result);
  }
  ```

- **계보로 덮는 이유.** 되돌림은 "같은 사건의 같은 제한이 다시 걸린 것"이다(`reinstated_from`이 원 제한·출처·신호 계보를 잇는다, 3.4). 공지는 사건을 알리므로 계보를 따라가는 것이 사용자가 보는 사실과 맞다. 이 방식이면 운영자가 되돌림마다 공지를 다시 발행하지 않아도 된다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 통과, 경고 0.
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run build && npm --prefix frontend run test && npm --prefix frontend run test:e2e` -- expected: 통과.
- `deploy/compose/verify-same-origin.sh --keep-stack && PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 npm --prefix frontend run test:e2e:run` 뒤 스택 down -- expected: 통과.
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 통과.

## Suggested Review Order

**운영 command 엔진**

- 진입점: command 전략 인터페이스 — 해석·검증·점검·확정·커밋 뒤·재전송·응답
  [`OperatorCommand.java:29`](../../backend/src/main/java/com/bokrilog/operations/internal/OperatorCommand.java#L29)

- 확정 템플릿 한 곳(트랜잭션 가드 → 원장 잠금 → 최종 권한 → 전략 본문 → `COMPLETED`)
  [`OperatorEnvelopeService.java:306`](../../backend/src/main/java/com/bokrilog/operations/internal/OperatorEnvelopeService.java#L306)

- 등록표와 구현 예(제한 넷이 한 구현)
  [`OperatorCommandRegistry.java:15`](../../backend/src/main/java/com/bokrilog/operations/internal/OperatorCommandRegistry.java#L15)
  [`RestrictCommand.java:42`](../../backend/src/main/java/com/bokrilog/operations/internal/RestrictCommand.java#L42)

- payload 하나의 무장, command를 가르지 않는 확정
  [`OperatorCommandFlow.java:59`](../../backend/src/main/java/com/bokrilog/OperatorCommandFlow.java#L59)
  [`OperatorCommandService.java:275`](../../backend/src/main/java/com/bokrilog/OperatorCommandService.java#L275)

**공지 되돌림 계보**

- 조상·후손 재귀 CTE와 계보 단위 cover 판정
  [`OperationalRestrictionRepository.java:165`](../../backend/src/main/java/com/bokrilog/operations/internal/OperationalRestrictionRepository.java#L165)
  [`PublishNoticeCommand.java:43`](../../backend/src/main/java/com/bokrilog/operations/internal/PublishNoticeCommand.java#L43)

- 사용자 읽기: 조상이 덮이면 덮인 것, 목록의 `liveCovers`는 한 읽기 트랜잭션
  [`ServiceNoticeService.java:29`](../../backend/src/main/java/com/bokrilog/operations/internal/ServiceNoticeService.java#L29)

**세션 읽기 규칙(배포 전 무장·fail-closed)**

- 읽을 수 없는 속성은 표시값 — 무장은 버리고, 재인증 표지는 표지로, 문맥은 미인증
  [`SessionAttributeConversion.java:29`](../../backend/src/main/java/com/bokrilog/SessionAttributeConversion.java#L29)
  [`UnreadableSessionValue.java:28`](../../backend/src/main/java/com/bokrilog/UnreadableSessionValue.java#L28)
  [`SessionGuard.java:146`](../../backend/src/main/java/com/bokrilog/SessionGuard.java#L146)
  [`SessionArming.java:106`](../../backend/src/main/java/com/bokrilog/SessionArming.java#L106)

**프런트엔드**

- 공통 상태 기계와 API 한 벌
  [`useOperatorCommand.ts:97`](../../frontend/src/session/useOperatorCommand.ts#L97)
  [`operatorApi.ts:182`](../../frontend/src/session/operatorApi.ts#L182)

- 공지 cover 기본값: `liveCovers ?? covers` ∩ 활성 목록
  [`OperatorScreen.tsx:387`](../../frontend/src/screens/OperatorScreen.tsx#L387)

**시험**

- 계보(단일·다중 hop·잘못된 복구 거절·`liveCovers`)
  [`ServiceNoticeLineageTest.java:36`](../../backend/src/test/java/com/bokrilog/ServiceNoticeLineageTest.java#L36)

- 깨진 세션 바이트의 HTTP 결과(문맥·재인증 표지·무장)
  [`UnreadableSessionAttributeTest.java:17`](../../backend/src/test/java/com/bokrilog/UnreadableSessionAttributeTest.java#L17)

- 응답 계약 문자 단위 대조, 등록 완전성·prologue 우회 금지
  [`OperatorCommandContractTest.java:41`](../../backend/src/test/java/com/bokrilog/operations/internal/OperatorCommandContractTest.java#L41)
  [`OperatorCommandRegistryTest.java:12`](../../backend/src/test/java/com/bokrilog/operations/internal/OperatorCommandRegistryTest.java#L12)
