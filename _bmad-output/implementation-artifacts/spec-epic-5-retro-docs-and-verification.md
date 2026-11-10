---
title: 'Epic 5 회고 AI-2·AI-3 — 삭제 문서·경계 정합과 삭제 경로의 동일 출처 검증'
type: 'chore'
created: '2026-11-10'
status: 'done'
baseline_commit: 'c3322fa20be1320bbec6c9e4e5f0b47901fe2057'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-retro-2026-11-09.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:**
- Epic 5 회고가 남긴 정합 문제가 셋이다(A-2·D-4·D-5·B-3).
  - 문서·주석이 삭제 코드와 어긋난다: `DEVELOPMENT.md` tombstone 절과 N-1 주의, 컨트롤러 Javadoc, 경로 리터럴, 영어 주석.
  - 5.2 로그만 구조화 키를 쓰지 않는다.
  - identity가 Spring Security 클래스를 import하는데 `ModuleBoundaryTest`가 잡지 못한다.
- 사용자 삭제 경로가 브라우저 층·동일 출처 스택에서 한 번도 돌지 않는다(B-5). 개발·CI compose가 tombstone 키를 넘기지 않아, 그 스택의 삭제는 영원히 "진행 중"으로 끝난다.

**Approach:**
- 문서·주석·로그를 코드에 맞춘다.
- 남길 세션 속성 이름은 루트가 identity port로 넘기고, 경계 시험이 모듈의 `org.springframework.security` 의존을 막는다(사용자 결정 2026-11-10).
- compose와 `verify-same-origin.sh`가 tombstone 키를 공급한다.
- 주입 origin Playwright 시험 하나가 요청 → 진행 → 완료 → 재가입 거부를 브라우저로 밟는다(사용자 결정 2026-11-10).

## Boundaries & Constraints

**Always:**
- 동작은 바꾸지 않는다. 로그 문장·Javadoc·문서·경계·시험 경로만 바꾼다. 남기는 속성 집합도 지금과 같은 두 이름이다.
- 구조화 로그는 SLF4J fluent `addKeyValue`이고 `RedactingJsonEncoder` 허용 키만 쓴다. 단계 이름은 `kind`(`DeployAuditRunner` 선례), 실패 code는 `code`, 예외는 단순 이름 `exception`, 그 밖의 닫힌 값은 `reason`, 결과는 `outcome`이다. 잔여 단계 목록은 `.`로 이은 `reason`이다. 이벤트 본문은 placeholder 없는 고정 문장이다.
- port 구현은 웹 조건 없이 모든 역할에 배선한다. `deletion-sample`·`restore-apply`·identity 시험에도 빈이 있어야 하기 때문이다(`DeletedIdentityDigestPort` 선례).
- tombstone 키는 감사 키와 따로 만들고, 스크립트의 금지 이름·값 누출 검사에 더한다. compose에는 이름만 두고 값은 두지 않는다.
- e2e는 `PLAYWRIGHT_BASE_URL`이 있을 때만 돈다. 시험마다 고유 subject를 쓰고 기존 helper를 재사용한다. Worker 대기는 `다시 확인`을 누르는 반복이다(진행 화면에는 polling이 없다).

**Ask First:**
- 시험 예산 때문에 CI 시한(`ci.yml` 단계·job, `globalTimeout`)을 바꿔야 할 때
- 기존 단언 문구를 바꿔야 할 때

**Never:**
- 삭제 동작 변경
- `spring_session` SQL 결합 해소(deferred 27)
- `IdentityAccountRepository` 분할(AI-7)
- 메트릭·경보 추가(172)
- `verify-same-origin.sh`에 한글 문구 curl 왕복 추가(CP949, deferred 246)
- 키 리터럴 커밋
- 새 의존

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 동일 출처 삭제 왕복 | 주입 origin, 새 subject, 본인 확인 뒤 문구 입력·확정 | 진행 화면 → `다시 확인` 반복 안에 완료 화면 → 같은 identity 재로그인은 `/?auth=deleted`와 재가입 불가 문장 | 대기 시한 안에 완료가 없으면 실패 |
| 키 없는 스택 | `BOKRILOG_TOMBSTONE_DIGEST_KEY` 없음 | 이전과 같음(Worker가 집지 않음) | N/A |
| 키 누출 | 응답·로그에 tombstone 키 값 | 동일 출처 검증 실패 | 기존 누출 검사 |
| 경계 위반 | 모듈이 `org.springframework.security..` import | `ModuleBoundaryTest` 실패 | N/A |
| 5.2 실패 로그 | 단계 실패·잔여·기록 실패 | JSON에 `code`·`kind`·`exception`/`reason` 키, `event`는 고정 문장 | N/A |

</frozen-after-approval>

## Code Map

**문서**
- `DEVELOPMENT.md:680-682`
  - 고칠 것: "`AccountErasure` 한 트랜잭션", 모듈 순서(portfolio→…→operations), "사용자 삭제 경로는 아직 없다", 행 칸 "…뿐이다·+35일".
  - HEAD의 사실: 두 모드(`AccountErasure.java:18-46`), 순서 `DeletionStep.java:18-26`, V32 열과 `max(요청, 운영 완료)+35일`(같은 절 `:686`·`:690`).
- `DEVELOPMENT.md:697`
  - N-1 주의가 5.3만 다룬다. 5.4 이미지에서 되돌리면 V33 재가입 차단·완료 세션 판정이 사라진다는 줄을 더한다(B-3).
- `DEVELOPMENT.md:379`·`:419`
  - 수동 기동 명령에 tombstone 키를 더하고, 없으면 삭제가 진행 중으로 남는다는 문장을 쓴다.

**Javadoc·주석**
- `backend/src/main/java/com/bokrilog/AccountDeletionController.java`
  - `:38`(GET)과 `:44-46`(확정 응답)에 `progress`를 더한다. 확정은 고정 `CONFIRMED_PROGRESS`(`:154`)다.
  - `:145` `ProgressView` `@param status`에 `DELETION_COMPLETED`(`PROGRESS_COMPLETED` `:88`)를 더한다.
- `backend/src/main/java/com/bokrilog/AuthSecurityConfig.java`
  - `:290-291` `ACCOUNT_PATH` Javadoc에 `/retry`(5.2)를 더한다.
  - `:1289-1294` `isDeletionCompletionRequest`는 `AccountDeletionController.DELETION_PATH`·`CONFIRM_PATH`(`:66`·`:68`, 같은 패키지 컴파일 상수)를 참조한다.
- `backend/src/test/java/com/bokrilog/AccountDeletionRequestTest.java:970`
  - 영어 Javadoc을 한국어로 바꾼다.

**5.2 구조화 로그**
- `backend/src/main/java/com/bokrilog/AccountErasureRun.java` `:143`·`:313`·`:316`·`:324`·`:338`
- `backend/src/main/java/com/bokrilog/AccountErasureWorker.java` `:112`·`:128`·`:159`·`:196`
- 본보기: `AccountDeletionRequestService.java:387`, `BackupDestructionJudge.java:210`, `DeployAuditRunner.java:73`
- 허용 키는 `RedactingJsonEncoder.java:51-52`다. 값이 `CODE_VALUE` `[A-Za-z][A-Za-z0-9_.:-]{0,63}`(`:62`)에 맞지 않으면 그 키는 버려진다.
- `backend/src/test/java/com/bokrilog/AccountErasureRunTest.java:657-660`
  - `event`의 `FAILURE_UNRECORDED` 단정을 `code` 키로 옮긴다. `:151`은 문장이 같으면 그대로다.

**경계**
- `backend/src/main/java/com/bokrilog/identity/internal/IdentityAccountRepository.java`
  - Security import(`:13-14`)와 `KEPT_SESSION_ATTRIBUTES`(`:315-317`)를 쓰는 곳이 셋이다: `scrubSession` `:243`, `convertToCompletionSession` `:218`, `scrubCompletionSessions` `:264`·`:273`.
  - 이름을 port에서 받도록 바꾼다.
- 새 identity 공개 port `CompletionSessionAttributes`(`keptAttributeNames()`)
  - 루트 `@Component`가 Security 두 기본 이름으로 구현한다. 근거는 `AuthSecurityConfig:1402-1404`이고, 패턴은 `identity/DeletedIdentityDigests.java`와 루트 `DeletedIdentityDigestPort.java`를 본뜬다.
- `backend/src/test/java/com/bokrilog/ModuleBoundaryTest.java`
  - `:100-125`(identity HTTP)와 `:158-175`(모든 모듈)에 `"org.springframework.security.."`를 더한다.
- `DeletionCompletionTest.java:62-65`
  - 시험 쪽의 같은 이름 식이다. 그대로 둔다.

**스택·e2e**
- `deploy/compose/compose.yaml:178-180`
  - 감사 키 옆에 `BOKRILOG_TOMBSTONE_DIGEST_KEY: ${BOKRILOG_TOMBSTONE_DIGEST_KEY:-}`를 두고 주석을 단다. 주석 내용: 값 없음, 감사 키와 다름, `all` Worker가 이 값이 있어야 삭제를 집음.
- `deploy/compose/verify-same-origin.sh`
  - 키 생성·export(`:272-273`)
  - `forbidden_fragments`(`:149` 옆)
  - 값 누출 라벨 목록(`:3173-3176`)과 `case`(`:3205` 옆). 둘을 함께 고친다(모르는 라벨은 `:3209`에서 실패).
- 새 `frontend/e2e/account-deletion.spec.ts`
  - 본보기: `support-inquiry.spec.ts`의 스킵 게이트(`:20`·`:78-82`)와 재인증 대화상자(`:55-75`), `plan-end.spec.ts:145-200`의 시한.
  - helper: `e2e/support/navigation.ts`의 `signIn`·`consent`·`waitForLanding`.
  - 카피: `src/content/accountDeletion`·`auth`의 문자열.
  - 재가입 거부는 `auth-entry.spec.ts:479-483` 관용을 따른다.
  - 예산: 시험당 120초, `globalTimeout` 420초(`playwright.config.ts:88`·`:112`), CI 단계 8분.

**걸린 열린 회고 액션**
- `epic-5-retro-item-49`(AI-2)와 `item-50`(AI-3): 이 작업이 닫는다. B-3 본체(재가입 차단의 내구성)는 `item-53`(G-5)에 남는다.
- `epic-3-retro-item-28`: verify 누출 목록을 보강하지만 닫지 않는다.
- `epic-5-retro-item-54`(AI-7, `IdentityAccountRepository` 분할): 지나간다.
- `epic-5-retro-item-51`(CI 시한): 시한을 바꾸지 않으므로 지나간다.
- `epic-2-retro-item-23`·`item-24`: 지나간다.
- `epic-1-retro-item-2`·`-3`·`-4`(`AuthSecurityConfig`·`AuthFlowHandlers` 동작): 동작을 건드리지 않으므로 지나간다.
- deferred 27: Security 타입 의존만 없앤다. SQL 결합은 그대로라 done 전환에서 다시 판정한다.

## Tasks & Acceptance

**Execution:**
- [x] `DEVELOPMENT.md` -- tombstone 절·N-1 주의·수동 기동 키를 코드에 맞춘다 -- D-5·B-3
- [x] `AccountDeletionController.java`·`AuthSecurityConfig.java`·`AccountDeletionRequestTest.java` -- Javadoc·상수 참조·주석을 고친다 -- D-5
- [x] `AccountErasureRun.java`·`AccountErasureWorker.java`·`AccountErasureRunTest.java` -- 아홉 줄을 구조화 키로 바꾸고 단정을 `code` 키로 옮긴다 -- D-4
- [x] identity port·루트 구현·`IdentityAccountRepository`·`AccountDeletionService`·`ModuleBoundaryTest` -- 속성 이름을 port에서 받고 Security 의존을 막는다 -- A-2
- [x] `compose.yaml`·`verify-same-origin.sh`·`test-ci-scripts.sh`(계약 시험이 해당하면) -- tombstone 키를 공급하고 누출 검사에 넣는다 -- B-5
- [x] `frontend/e2e/account-deletion.spec.ts` -- 매트릭스 첫 행을 시험한다 -- B-5
- [x] `CLAUDE.md` -- 계약 줄이 바뀐 사실을 적는다: port, 경계 규칙, compose 키 -- 문서 정합

**Acceptance Criteria:**
- Given 운영 소스, when `ModuleBoundaryTest`가 돌면, then 어떤 모듈도 `org.springframework.security..`에 의존하지 않고 완료 세션이 남기는 속성은 이전과 같은 두 이름이다(`DeletionCompletionTest` 통과).
- Given `verify-same-origin.sh --keep-stack`, when 그 origin에 e2e를 돌리면, then 삭제 왕복 시험이 통과하고 동일 출처 검증 단정이 모두 통과한다.
- Given 5.2 단계 실패, when 로그를 읽으면, then `event`는 고정 문장이고 값은 허용 키로만 나온다.

## Spec Change Log

### 구현 뒤 매트릭스 감사(2026-11-10)

매트릭스 2행(키 없는 스택)을 덮는 시험이 없었다. `AccountErasureWorkerRoleTest`에 `tombstone_키가_없으면_집지_않는다`를 더했고 통과했다.

### 1차 리뷰(2026-11-10) — patch 10건·defer 1건·reject 12건

세 갈래(Blind·Edge Case·Verification Gap)를 돌렸다. intent_gap·bad_spec은 없었다.

**patch(10건):**
1. e2e 대기 반복이 두 다시 확인 버튼 중 하나를 누르고, 재시도 필요에서는 바로 실패한다.
2. e2e 확인 문구를 공유 fixture에서 가져온다.
3. 남길 속성 이름이 빈 목록이면 실패한다.
4. port 계약 단위 시험을 더한다.
5. 잔여 단계가 둘일 때 `reason`을 단정한다.
6. 실행 줄의 `outcome`·`kind`를 단정한다.
7. `ModuleBoundaryTest` 사유 문장에 Security를 더한다.
8. 긴 줄의 형식을 고친다.
9. `DEVELOPMENT.md`에서 복원 적용 서술을 나누고, 스크립트 키 생성을 적는다.
10. `CLAUDE.md` compose 키 문장을 줄인다.

**defer(1건):** 180. 삭제 왕복 뒤 로그의 tombstone 키 누출 판정.

**reject(12건):**
- `addKeyValue(null)`: encoder가 null 값을 건너뛴다(`RedactingJsonEncoder.java:116`).
- `outcome` 허용 키: 이미 허용 키다.
- 영어 주석만 바꾼 헝크: 회고 D-5와 승인된 SPEC 범위다.
- `LOG_KEYS` 고정 키 리터럴: 표시 수준이다.
- 다른 경로의 사용자 ID 단정: 범위 밖이다.
- `isAccountPath` 문자열: 이번 변경 전부터 있었다.
- 회고 표기 혼용: 회고 문서의 두 체계 그대로다.
- SPEC·sprint-status가 diff에 없음: 리뷰용 diff에서 뺐고, done 전환이 맡는다.
- N-1의 Flyway validate 전제: 같은 절의 5.3 주의와 같은 전제다.
- 절 이름 참조: 실제로 있다(`DEVELOPMENT.md:686`·`:689`).
- e2e 예산: 전체 74건 4.1분으로 `globalTimeout` 7분 안이다.
- 스크립트의 Worker 배선 확인: e2e가 덮는다.

**KEEP:**
- 루트 port 주입
- 모든 모듈의 Security 의존 금지
- `kind`·`code`·`exception`·`reason`·`outcome` 키 대응
- compose에 값 없는 이름
- e2e의 고유 subject

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build`: 성공(Docker 필요, Windows 소켓 결함으로 난 실패는 그 클래스를 다시 돌려 판정)
- `bash .github/scripts/test-ci-scripts.sh`: 통과
- `npm --prefix frontend run typecheck`·`lint`·`format:check`: 통과
- `deploy/compose/verify-same-origin.sh --keep-stack` 뒤 `CI=true PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 npm --prefix frontend run test:e2e:run`: 전체 통과, 소요 시간 기록

## Suggested Review Order

**경계: identity의 Security 의존 제거**

- 남길 세션 속성 이름을 루트가 주는 identity port
  [`CompletionSessionAttributes.java:13`](../../backend/src/main/java/com/bokrilog/identity/CompletionSessionAttributes.java#L13)

- 웹 조건 없는 루트 구현 — Security 두 기본 이름
  [`CompletionSessionAttributesPort.java:25`](../../backend/src/main/java/com/bokrilog/CompletionSessionAttributesPort.java#L25)

- SQL 전에 빈 목록을 거절하는 가드
  [`IdentityAccountRepository.java:219`](../../backend/src/main/java/com/bokrilog/identity/internal/IdentityAccountRepository.java#L219)

- 모든 모듈의 `org.springframework.security` 의존 금지
  [`ModuleBoundaryTest.java:124`](../../backend/src/test/java/com/bokrilog/ModuleBoundaryTest.java#L124)

**삭제 경로의 동일 출처 검증**

- 주입 origin 삭제 왕복 — 요청·진행·완료·재가입 거부
  [`account-deletion.spec.ts:70`](../../frontend/e2e/account-deletion.spec.ts#L70)

- compose는 이름만, 값은 스크립트가 공급
  [`compose.yaml:185`](../../deploy/compose/compose.yaml#L185)

- 감사 키와 따로 만든 tombstone 키와 누출 검사
  [`verify-same-origin.sh:280`](../../deploy/compose/verify-same-origin.sh#L280)

**5.2 구조화 로그**

- 잔여 단계를 `.`로 이은 `reason`과 `kind`·`code`
  [`AccountErasureRun.java:324`](../../backend/src/main/java/com/bokrilog/AccountErasureRun.java#L324)

- 실행 결말의 `outcome`
  [`AccountErasureRun.java:354`](../../backend/src/main/java/com/bokrilog/AccountErasureRun.java#L354)

- Worker 실패 줄의 `exception`
  [`AccountErasureWorker.java:113`](../../backend/src/main/java/com/bokrilog/AccountErasureWorker.java#L113)

**문서·주석 정합**

- 두 모드·여덟 단계·V32 칸으로 고친 tombstone 절
  [`DEVELOPMENT.md:680`](../../DEVELOPMENT.md#L680)

- 5.4 롤백 시 재가입 차단이 사라진다는 N-1 주의
  [`DEVELOPMENT.md:698`](../../DEVELOPMENT.md#L698)

- 리터럴 대신 컨트롤러 경로 상수
  [`AuthSecurityConfig.java:1292`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L1292)

- 응답 Javadoc의 `progress`·`DELETION_COMPLETED`
  [`AccountDeletionController.java:38`](../../backend/src/main/java/com/bokrilog/AccountDeletionController.java#L38)

**시험**

- 두 모듈 잔여의 `reason` 단정
  [`AccountErasureRunTest.java:453`](../../backend/src/test/java/com/bokrilog/AccountErasureRunTest.java#L453)

- port 이름이 Spring 저장소의 실제 속성 이름과 같은지
  [`CompletionSessionAttributesPortTest.java:27`](../../backend/src/test/java/com/bokrilog/CompletionSessionAttributesPortTest.java#L27)

- 키 없는 Worker는 집지 않는다(매트릭스 2행)
  [`AccountErasureWorkerRoleTest.java:109`](../../backend/src/test/java/com/bokrilog/AccountErasureWorkerRoleTest.java#L109)
