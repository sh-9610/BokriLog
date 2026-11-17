---
title: 'G-5 전 삭제 정책 기본값 — 정책 1.1·고지 hash·재가입 차단 기한·출시 증거 등록'
type: 'chore'
created: '2026-11-17'
status: 'done'
baseline_commit: 'dd6d30e55119977ca170befca46e1397434b9212'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-17.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Epic 5 회고 AI-6(`epic-5-retro-item-53`)의 결정 여섯(변경 제안서 `sprint-change-proposal-2026-11-17.md` D-1~D-6)이 코드·문서에 없다. 백업 카피가 구현보다 강하게 약속하고, 고지 hash가 화면 문장을 덮지 않고, 재가입 차단 기록에 기한이 없고, 출시 증거 일부가 기계가 읽는 자리에 없다.

**Approach:** 정책을 `ACCOUNT-DELETION-1.1`로 올리고 고지 문장(삭제 대상 문장·남기는 것·백업·법정 보존 없음)을 공유 표 하나로 옮겨 화면이 읽고 서버 고지 hash가 그 digest를 덮게 한다. 카피를 D-1·D-2·D-4대로 고치고, 판정자 주기가 기한이 지난 재가입 차단 기록을 지우며, 복원 절차·출시 증거·원장을 문서에 더한다.

## Boundaries & Constraints

**Always:**
- 고지 문장의 원본은 `frontend/src/content/accountDeletionPolicy.fixture.json`의 `notice` 하나다. 화면은 그것을 읽고, 서버는 그 정규 직렬화의 SHA-256 상수(`AccountDeletionPolicy.NOTICE_DIGEST`)를 hash 입력에 넣으며, 백엔드 시험이 표에서 다시 계산해 상수와 같음을 단정한다(문장을 바꾸면 시험이 버전·상수 갱신을 강제한다).
- 재가입 차단 기록의 기한: 그 `request_id`의 실행이 `DELETION_COMPLETED`이면 지운다. 실행 행이 없는 기록(`request_id` 없음 포함)은 기록 후 35일(tombstone 보존과 같은 수)이 지나면 지운다. 실행이 있고 아직 완료가 아니면 남긴다. 시각은 DB 시각이다.
- 기록 정리는 `BackupDestructionJudge` 주기에서 identity 공개 API 한 메서드로 돌고, 실패는 WARN 한 줄로 접어 다른 판정을 막지 않는다(`scrubNow`와 같은 관용). SQL은 `IdentityAccountRepository`에만 둔다.
- 카피는 금지 표현 규칙(`content/copyRules`)을 통과한다.

**Ask First:**
- 기존 시험의 기대를 이 Intent 밖에서 바꿔야 하는 경우(정책 버전·hash·카피 문자열을 따라가는 갱신은 승인 범위).
- 새 마이그레이션이 필요해지는 경우.

**Never:**
- 백업을 앞당겨 지우는 구현, API 역할에 tombstone 키, 재가입 차단의 해제 command·문의 경로.
- 내부 경보 기한(`요청 + 30일` → `OVERDUE`)·tombstone 보존식·완료 확인 세션 수명·인증 실패 수렴(D-5는 수용, 코드 없음)을 바꾸지 않는다.
- production 삭제 차단(`ACCOUNT_DELETION_UNAVAILABLE`)을 풀지 않는다. ERD·SPINE 본문은 고치지 않는다(다음 as-built 버전 몫).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 옛 화면 확정 | 1.0을 본 화면이 확정 | 409 `ACCOUNT_DELETION_POLICY_CHANGED` | 저장 없음 |
| 전체 완료 뒤 재가입 | 실행 `DELETION_COMPLETED`, 판정 주기 지남 | 차단 행 삭제 → 같은 identity 로그인이 새 계정 생성 | — |
| 완료 전 로그인 | 실행 `OPERATIONAL_DATA_ERASED` | 차단 유지, 계정 생성 없음(표지 `deleted`) | — |
| 실행 없는 기록 | `request_id` 없음 또는 실행 행 없음, 기록 36일 전 / 34일 전 | 지움 / 남김 | — |
| 정리 실패 | 정리 SQL 예외 | WARN 한 줄, 판정·완료 기록·세션 정리는 계속 | 다음 주기 재시도 |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/com/bokrilog/AccountDeletionPolicy.java` -- `VERSION`·`noticeHash()`(:76) — 1.1, `NOTICE_DIGEST` 추가
- `frontend/src/content/accountDeletionPolicy.fixture.json` -- 공유 표, `version` 1.1·`notice` 추가
- `frontend/src/content/accountDeletion.ts` -- `TARGET_SENTENCES`(:28)·`kept`·`backup`·`legalNone`·`ACCOUNT_DELETION_POLICY_VERSION` → 표에서 읽음
- `backend/src/test/java/com/bokrilog/AccountDeletionRequestTest.java` -- 공유 표 대조(:464)·hash 정규형(:684)
- `backend/src/main/java/com/bokrilog/BackupDestructionJudge.java` -- `judgeNow`(:129) finally에 정리 단계, `scrubNow` 패턴
- `backend/src/main/java/com/bokrilog/identity/AccountDeletionRunApi.java`·`identity/internal/AccountDeletionRunService.java`(:250 근처) -- 정리 메서드
- `backend/src/main/java/com/bokrilog/identity/internal/IdentityAccountRepository.java` -- 차단 표 SQL(:147·:174), `markDeletionCompleted`(:1004)
- `backend/src/test/java/com/bokrilog/DeletionCompletionTest.java` -- 전체 완료·차단 통합 시험 자리
- `frontend/src/screens/*AccountDeletion*.test.tsx`·`DeletionProgressScreen.completion.test.tsx` -- 카피 문자열 참조(따라감)
- `DEVELOPMENT.md` -- `### 복원 순서`(:728) RPO 구간 공지, `### 삭제 완료와 재가입 차단`(:752) 기한
- `_bmad-output/planning-artifacts/epics.md` -- frontmatter `launchEvidence`
- `_bmad-output/implementation-artifacts/deferred-work.md` -- item-33 운영 증거 넷 append, ERD·SPINE 반영 defer
- `CLAUDE.md` -- 5.1·5.4 계약 줄의 정책 버전·차단 기한
- 걸린 열린 회고 액션:
  - `epic-5-retro-item-53`(주제) — 이 작업이 닫는다.
  - `epic-1-retro-item-10`·`epic-3-retro-item-34`(주제) — `launchEvidence`로 닫는다.
  - `epic-3-retro-item-33`(주제) — 원장 append로 닫는다.
  - `epic-5-retro-item-54`(이름 — `IdentityAccountRepository`) — 메서드 하나만 더하고 분할은 지나간다.
  - `epic-3-retro-item-39`(과정 — 시험 값) — 시험에 digest·요청 ID 모양 리터럴을 쓰지 않는다(실행 시점 생성).
  - `epic-1-retro-item-7`(주제 인접 — 확인 hash 판별력) — 고지 hash와 다른 hash라 지나간다.

## Tasks & Acceptance

**Execution:**
- [x] `frontend/src/content/accountDeletionPolicy.fixture.json` -- `version` 1.1, `notice{targets{code:문장}, kept[], backup(템플릿 `{days}`), legalNone}` 추가(D-1·D-2·D-4 새 문장) -- 단일 원본
- [x] `frontend/src/content/accountDeletion.ts` -- 버전·문장을 표에서 읽기, 주석 갱신 -- 두 벌 제거
- [x] `backend/src/main/java/com/bokrilog/AccountDeletionPolicy.java` -- `VERSION` 1.1, `NOTICE_DIGEST`, `noticeHash()`에 `notice=` 줄 -- D-3
- [x] `backend/src/test/java/com/bokrilog/AccountDeletionRequestTest.java` -- 표의 `notice` 정규 직렬화 → `NOTICE_DIGEST` 대조, 정규형 시험 갱신 -- 강제 장치
- [x] identity API·서비스·저장소 -- `expireDeletedIdentities()`(위 기한 규칙, 지운 수) -- D-4
- [x] `backend/src/main/java/com/bokrilog/BackupDestructionJudge.java` -- 주기 finally에 정리 단계(`DELETED_IDENTITY_EXPIRED` 로그) -- D-4
- [x] 백엔드 시험 -- I/O 표의 차단 기한 다섯 행 -- 경계
- [x] 프런트 시험 -- 문자열 갱신을 따라가고 새 백업 문장 렌더 확인 -- 회귀
- [x] `DEVELOPMENT.md`·`CLAUDE.md` -- 복원 뒤 RPO 구간 공지 단계, 차단 기한, 정책 1.1 -- D-2·D-4
- [x] `epics.md`·`deferred-work.md` -- `launchEvidence` 아홉, item-33 넷 append -- D-6

**Acceptance Criteria:**
- Given 공유 표의 고지 문장 하나를 바꾸고 `NOTICE_DIGEST`를 그대로 두면, when 백엔드 시험을 돌리면, then 대조 시험이 실패한다.
- Given 설정 화면, when 삭제 정책을 렌더하면, then 백업 문장이 "운영 데이터 삭제를 마친 다음 날부터 최대 30일"과 복원 단서를 말하고 정책 버전 `ACCOUNT-DELETION-1.1`을 보인다.

## Spec Change Log

- 2026-11-17 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap) patch 루프 — 리뷰 전체 재실행 없음(`review_loop_iteration` 0 유지).
  - patch: 버전별 고지 digest 이력으로 버전 갱신을 실제로 강제(두 리뷰어가 같은 지적 — 문장과 `NOTICE_DIGEST`만 고치면 1.1 그대로 통과했다), 고지 문장의 줄바꿈 금지 단정, 정리 DELETE의 lock·statement 상한, 백업 약속 기준점 "다음 날부터"(1시간 `CLOSURE_SLACK`의 최악 경우), D-2 복원 단서를 외부 보관 기준으로 정밀화, 완료 영역의 범위 없는 옛 약속·하드코딩 30, 화면의 요청 + 30일을 "목표일"로 표기, DEVELOPMENT 문장 셋(단일 모드 35일·N-1 수용 근거·9단계 정의), 로그 누출 단정을 `LogLines`로, `launchEvidence`에 3.8 entitlement.
  - defer 3(원장 198~200): 복원 뒤 RPO 공지 수단, 법정 보존·고착 실행에서 재가입 차단 기록의 무기한 잔존, 정리 실패 관측.
  - reject: 프런트 대조 시험의 동어반복(단일 원본이 된 결과, 강제는 백엔드 digest 이력), 기한 상수 대조 시험의 의미 차이(수만 같게 두는 의도), 번들의 표 메타데이터 약 1.5KB, 법정 보존 뒤 고지 괄호(그때는 고지 hash가 바뀌어 새 버전으로 다시 쓴다).
  - KEEP: 고지 문장의 단일 원본은 공유 표 `notice`이고 서버는 digest 상수만 갖는다(Java에 한국어 사본 없음). 재가입 차단 정리는 identity 공개 API 한 메서드와 판정자 finally의 실패 격리.

## Design Notes

고지 정규 직렬화(서버 시험만 계산한다): 줄 단위로 `target.<CODE>=<문장>`(정책 `TARGETS` 순서) → `kept=<문장>`(배열 순서) → `backup=<템플릿>` → `legalNone=<문장>`을 `\n`으로 잇고 UTF-8 SHA-256. 정책 hash 정규형 끝에 `notice=<NOTICE_DIGEST>` 줄을 더한다. JSON 재직렬화에 기대지 않으므로 언어 간 차이가 없다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 통과
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run format:check && npm --prefix frontend run test` -- expected: 통과

## Suggested Review Order

**고지 hash와 버전 갱신 장치**

- 고지 문장의 digest를 정책 hash 마지막 줄로 — 서버엔 한국어 사본 없음
  [`AccountDeletionPolicy.java:94`](../../backend/src/main/java/com/bokrilog/AccountDeletionPolicy.java#L94)

- 고지 문장의 단일 원본과 버전별 digest 이력
  [`accountDeletionPolicy.fixture.json:22`](../../frontend/src/content/accountDeletionPolicy.fixture.json#L22)

- 표에서 다시 계산한 digest = 상수 = 이력[VERSION], 줄바꿈 금지
  [`AccountDeletionRequestTest.java:700`](../../backend/src/test/java/com/bokrilog/AccountDeletionRequestTest.java#L700)

**백업·복원 약속(D-1·D-2)**

- 기준점 "다음 날부터"와 외부 보관 뒤에만 되살리지 않는다는 단서
  [`accountDeletionPolicy.fixture.json:45`](../../frontend/src/content/accountDeletionPolicy.fixture.json#L45)

- 요청 + 30일은 기한이 아니라 내부 목표일로 표기
  [`DeletionProgressScreen.tsx:153`](../../frontend/src/screens/DeletionProgressScreen.tsx#L153)

- 화면 카피가 공유 표를 읽는다
  [`accountDeletion.ts:19`](../../frontend/src/content/accountDeletion.ts#L19)

**재가입 차단 기한(D-4)**

- 완료된 실행의 기록·실행 없는 35일 기록만 지우는 한 문장
  [`IdentityAccountRepository.java:217`](../../backend/src/main/java/com/bokrilog/identity/internal/IdentityAccountRepository.java#L217)

- lock 2초·statement 5초 상한(tombstone 판정과 같은 관용)
  [`IdentityAccountRepository.java:203`](../../backend/src/main/java/com/bokrilog/identity/internal/IdentityAccountRepository.java#L203)

- 판정 주기 finally에서 실패를 격리해 돈다
  [`BackupDestructionJudge.java:208`](../../backend/src/main/java/com/bokrilog/BackupDestructionJudge.java#L208)

**문서·출시 증거**

- 복원 뒤 RPO 구간 공지 단계
  [`DEVELOPMENT.md:741`](../../DEVELOPMENT.md#L741)

- 출시 증거 아홉의 기계 판독 자리
  [`epics.md:14`](../planning-artifacts/epics.md#L14)

**시험**

- 전체 완료 전엔 차단, 뒤엔 새 계정
  [`DeletionCompletionTest.java:318`](../../backend/src/test/java/com/bokrilog/DeletionCompletionTest.java#L318)

- 정리 실패는 WARN 한 줄, 메시지 누출 없음
  [`DeletionCompletionTest.java:360`](../../backend/src/test/java/com/bokrilog/DeletionCompletionTest.java#L360)

- 실행 없는 기록의 35일 경계
  [`DeletionCompletionTest.java:404`](../../backend/src/test/java/com/bokrilog/DeletionCompletionTest.java#L404)
