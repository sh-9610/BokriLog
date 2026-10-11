---
title: 'Story 3.6 — 사용자 문의를 접수하고 영업일 3일 답변 목표 추적하기'
type: 'feature'
created: '2026-10-10'
status: 'done'
review_loop_iteration: 0
baseline_commit: 'a7056ba31f3f37590ca18876398394c2afa6e5b5'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-5-service-notice.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 사용자가 장애·계정 문제를 알릴 경로가 3.5 공지의 지원 연락처(`mailto`) 하나뿐이다. 문의 접수·상태·답변 목표가 없고, 운영자는 답변 목표 달성률을 실제 데이터로 셀 수 없다(`epics.md` Story 3.6 AC-1~6, `EPIC-IMPLEMENTATION-READINESS.md` §7 `SUPPORT-1.0`).

**Approach:**
- **사용자 접수.** 인증 사용자가 설정 화면의 문의 폼으로 접수한다. 서버는 민감값 모양을 거절하고 문의·접수 시각·불투명 reference·답변 목표 시각을 한 트랜잭션에 저장한다.
- **답변 목표.** 버전 seed 대한민국 영업일 달력(2026·2027)으로 "접수 다음 영업일부터 세 번째 영업일 18:00 KST"를 결정적으로 계산한다.
- **운영 처리.** 운영자는 보호 표면에서 최소화된 문의를 보고, 보호 command로 상태·답변을 기록한다. 첫 답변 시각과 목표 달성 여부로 달성률·처리 영업시간을 집계한다.
- **보존.** 해결 후 90일이 지난 문의와 삭제 사용자의 문의는 지운다.

## Boundaries & Constraints

**Always:**
- **입력(AC-1).** `POST /api/support/inquiries`(인증, CSRF·`Origin` — `/api/support/` 접두사를 기존 판정 술어에 더함, `IdempotencyKey` 필수)가 받는 필드는 다음과 같다.
  - `category`: 닫힌 코드 `ACCOUNT|CONNECTION|SYNC_PLAN|SERVICE_NOTICE|OTHER`
  - `title`: 1~80자
  - `description`: 1~2000자
  - `contact`: `IN_APP` 또는 `EMAIL`(이때 `replyEmail` 필수, 이메일 형식 254자 이내)
  - 제목·설명은 다음 모양이면 422 `INQUIRY_SENSITIVE_CONTENT`로 거절한다(서버 판정이 권위이고 화면은 같은 규칙으로 미리 안내). 거절 응답과 로그에는 어느 규칙인지 code만 싣고 원문을 싣지 않는다.
    - API Key·Secret·토큰 모양(16자 이상 영숫자·`-_` 연속, `Bearer `, `-----BEGIN`)
    - 지갑 주소 모양(`0x`+40 hex, 26~62자 base58·bech32)
    - 3.5 메시지 규칙의 금융값 모양(구분자 포함 숫자 6자리 이상, 통화 기호·`USD`·`USDT`·`원`과 붙은 숫자)
    - 이메일 모양
  - 사용자당 접수는 DB 시각 기준 최근 24시간 5건 이내다(429 `INQUIRY_RATE_LIMITED`).
- **소유권·원자성(AC-2).**
  - `AccountAccessApi.decide(SENSITIVE)`를 쓰고, 삭제 진행 계정은 공통 404다.
  - 문의 행 하나에 `reference`(불투명, 사람이 읽는 `Q-` + 10자 base32 난수), `received_at`(DB 시각), `target_at`, `calendar_version`, `timezone='Asia/Seoul'`, `status='RECEIVED'`를 같은 INSERT로 쓴다.
  - 같은 사용자·같은 멱등 키는 같은 접수 결과를 돌려준다. 같은 키에 다른 입력이면 409 `IDEMPOTENCY_CONFLICT`다. 멱등의 권위는 `(user_id, idempotency_key_digest)` 유일 색인이다.
- **영업일 목표(AC-3).**
  - `V22`가 `support_business_calendar(calendar_version, year, holiday_date, name)`에 seed를 넣는다. 버전은 `KR-HOLIDAY-2026.1`이고 2026·2027 두 해를 담는다. 2026은 노동절 신설·제헌절 재지정·6/3 지방선거일 포함, 2027은 우주항공청 월력요항 기준(설 연휴 2/6~2/8·대체 2/9)이다. 머리말에 출처와 확인일(2026-10-10)을 적는다.
  - 영업일은 `Asia/Seoul` 월~금에서 그 달력의 공휴일을 뺀 날이다.
  - 목표는 접수 시각의 KST 날짜 D 다음의 영업일들 중 세 번째 영업일 18:00 KST다.
  - 계산에 필요한 연도의 행이 달력에 없으면 `target_at`은 NULL, `target_state='CALENDAR_PENDING'`이다. 접수는 막지 않는다. 화면 문장은 "답변 목표를 아직 정하지 못했습니다"다.
  - 계산은 순수 함수 하나(`BusinessDayCalendar`)이고 고정 fixture로 시험한다. 경계 시각(금 17:59·토·공휴일 전날 23:59 KST·연말·UTC 자정 경계)을 덮는다.
  - 화면·응답은 "답변 목표"라고만 말하고 SLA·보장 표현을 쓰지 않는다.
- **사용자 상태(AC-4).**
  - `GET /api/support/inquiries`는 내 문의 목록이다(reference·범주·제목·접수 시각·답변 목표·상태·운영자 답변·답변 시각).
  - 설정 화면 `문의` 영역은 폼과 목록, 공개 지원 이메일(3.5 `SupportContact` 설정값, 있을 때만 `mailto`)을 보인다.
  - 폼은 영구 라벨·도움말과 민감값 금지 안내를 둔다.
  - 제출 실패·네트워크 끊김·5xx면 입력을 보존한다. 같은 멱등 키로 사용자가 다시 누를 때만 재전송하고 자동 재제출하지 않는다.
  - 성공하면 reference·접수 시각·답변 목표를 status 문장 한 번과 확인 블록으로 보인다.
  - 화면당 live region은 하나다. 320px·400% 확대에서 읽힌다.
- **운영 처리(AC-5).** 운영 command `update-inquiry`(3.1 envelope·`command` 권한·재인증·허가·감사)를 둔다.
  - target은 reference다.
  - 상태 전이는 `RECEIVED → IN_PROGRESS → ANSWERED → RESOLVED` 단조이고 `ANSWERED`는 건너뛸 수 없다.
  - 답변 문장(1~1000자, 같은 민감값 규칙)은 `ANSWERED` 전이에 필수다. 첫 `ANSWERED`가 `first_answered_at`(DB 시각)과 `target_met = first_answered_at <= target_at`(목표 미정이면 NULL)을 쓴다.
  - 감사 `details`에는 상태·reference digest만 남기고 제목·본문·이메일·답변 원문은 넣지 않는다.
  - 운영자 조회 `GET /api/operator/inquiries`(`command` 권한)는 최소화한 목록이다. 범주·제목·상태·접수·목표·첫 답변·달성 여부를 보이고 본문은 상세에서만 보인다. 회신 이메일은 마스킹(`a***@d***`)한다.
  - 집계 `GET /api/operator/inquiries/metrics?from&to`는 그 기간 목표 시각이 지난 문의 수(분모), 그중 목표 안 첫 답변 수(분자), 달성률, 첫 답변까지의 영업시간(09~18 KST) 중앙값·합계를 돌려준다. 목표 미정 문의는 분모에서 빼고 따로 센다.
  - 자동시험은 계산 정확성만 단정한다. 달성 "선언" 문장을 만들지 않는다.
- **보존·삭제(AC-6).**
  - `RESOLVED` 뒤 90일이 지난 문의는 사용자·운영자 목록 조회 트랜잭션 시작 때 DELETE로 지운다(백그라운드 없음, DB 시각).
  - `OperationsDeletionApi.eraseInquiries(userId)`는 그 사용자의 문의·멱등 행을 지운다. 다른 사용자는 불변이다.
  - 법정 분리 보존은 G-5 전이라 없음(개발·CI 기본)이다.
  - 로그·metric에 제목·본문·이메일·답변을 남기지 않는다(로그 캡처 시험).
- **마이그레이션.** 신규는 `V22__support_inquiry.sql` 하나다(문의 표·달력 표·seed·색인).
- **주석·카피.** 새 주석·카피는 한국어(`content/support.ts`)이고 금지 표현 규칙(`content/copyRules.ts`)을 따른다. 운영 정책 버전은 `OPERATOR-COMMAND-1.4`다.

**Ask First:**
- 회신 이메일을 실제로 발송하는 것(이메일 발송 공급자 도입).
- 첨부 파일.
- 외부 티켓 시스템 연동.
- 90일·24시간 5건 값 변경.
- 2028년 달력 추가.

**Never:**
- 3일 답변 보장·SLA 표현.
- 금융 원문·자격정보 입력 허용.
- 자동 재제출.
- 로그·분석에 본문·연락처.
- G-5 미승인 영구 보존.
- 24시간 당직 약속.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 접수 | 인증 사용자, 유효 입력, 2026-10-12(월) 10:00 KST | 201 `{reference, receivedAt, targetAt: 2026-10-15T18:00+09:00, status: RECEIVED}` | 민감값 422 `INQUIRY_SENSITIVE_CONTENT`, 필드 오류 422, CSRF 없음 403 |
| 주말·공휴일 | 2026-10-08(목) 20:00 KST 접수(10/9 한글날) | 목표 2026-10-14(수) 18:00 | — |
| 연말·연도 경계 | 2026-12-30(수) 접수 | 12/31·1/4·1/5 → 2027-01-05 18:00 | — |
| 설 연휴 | 2027-02-05(금) 접수 | 2/10·2/11·2/12 → 2027-02-12 18:00 | — |
| 달력 없음 | 2027-12-30 접수(2028 행 없음) | 접수 성공, `targetAt` null, 목표 미정 문장 | — |
| 멱등 | 같은 키 재전송 / 같은 키 다른 입력 | 같은 reference / 409 `IDEMPOTENCY_CONFLICT` | — |
| rate limit | 24시간 안 6번째 | 429 `INQUIRY_RATE_LIMITED`, 행 없음 | — |
| 입력 보존 | 화면, 제출 네트워크 실패 | 입력 유지, 자동 재전송 없음, 재시도는 같은 키 | — |
| 운영 답변 | `update-inquiry` `ANSWERED` + 답변 | `first_answered_at`·`target_met` 기록, 사용자 목록에 답변 | 답변 없음 422, 역행 전이 409 |
| 달성률 | 기간 안 목표 지난 4건 중 3건 목표 안 답변, 미정 1건 | 분모 4·분자 3·75%, 미정 1 따로 | — |
| 보존 | `RESOLVED` 91일 경과(행 시각 이동) | 다음 조회에서 사라짐 | 89일은 유지 |
| 삭제 | `eraseInquiries(u)` | u 문의·멱등 행 0, 다른 사용자 불변 | 멱등 |
| 권한 | 다른 사용자·비운영자 | 남의 문의 없음 / 운영자 경로 404 | — |

</frozen-after-approval>

## Code Map

- `_bmad-output/planning-artifacts/epics.md` Story 3.6, `EPIC-IMPLEMENTATION-READINESS.md` §7 `SUPPORT-1.0`. 최고 마이그레이션 V21.
- **공휴일 출처(2026-10-10 확인):** 2026은 「관공서의 공휴일에 관한 규정」 2026-04 개정(노동절 신설·제헌절 재지정, 국가법령정보센터)과 인사혁신처 공지 기준이다.
  - 1/1, 2/16~18, 3/1·대체 3/2, 5/1, 5/5, 5/24·대체 5/25, 6/3(지방선거), 6/6, 7/17, 8/15·대체 8/17, 9/24~26, 10/3·대체 10/5, 10/9, 12/25
  - 2027은 우주항공청 2027년 월력요항 기준이다.
    - 1/1, 2/6~8·대체 2/9, 3/1, 5/1·대체 5/3, 5/5, 5/13, 6/6, 7/17·대체 7/19, 8/15·대체 8/16, 9/14~16, 10/3·대체 10/4, 10/9·대체 10/11, 12/25·대체 12/27
    - 구현 중 월력요항과 다른 날짜가 확인되면 멈추고 보고한다.
- **command 본보기:** `operations/OperatorCommandApi.java`(`PUBLISH_NOTICE`:66, `POLICY_VERSION` 1.3), `internal/OperatorEnvelopeService.java`(3.5 메시지 규칙 `NoticeRules`·`completeNotice`), 루트 `OperatorCommandService`·`OperatorController`·`OperatorGate`
- **설정·연락처:** 루트 `SupportContact.java`(3.5, `BOKRILOG_SUPPORT_CONTACT`)
- **사용자 표면 본보기**
  - 루트 `ServiceNoticeController.java`(인증·`AccountAccessApi`·공통 오류)
  - `AuthSecurityConfig`(새 접두사를 판정 술어 하나에, 전역 `authenticated()` 금지)
  - `sync.IdempotencyKey`
- **삭제 port:** `operations/OperationsDeletionApi.java`(`eraseActor`:24·`eraseRestrictionTargets`:34)
- **화면**
  - `frontend/src/screens/SettingsScreen.tsx`(`useSessionGate('settings')`:137, 공지 :142, live region)
  - 운영자 `screens/OperatorScreen.tsx`·`session/useOperatorNotice.ts`(상태 기계 본보기), `content/copyRules.ts`, `content/operator.ts`
  - 확인 대화상자 `components/useConfirmationDialog.ts`
- **시험 본보기:** `ServiceNoticeTest`(command·사용자 조회·로그 allowlist), `OperatorTestBase`, `AuthFlowTestBase.java:228`(TRUNCATE에 문의 표 추가 — 달력 seed 표는 지우지 않는다), `SettingsScreen.test.tsx`, e2e `operator.spec.ts`

## Tasks & Acceptance

**Execution:**
- [x] `db/migration/V22__support_inquiry.sql` -- 다음을 만든다.
  - `support_business_calendar`(PK `(calendar_version, holiday_date)`)와 2026·2027 seed, 버전별 수록 연도 표(`support_calendar_year`)
  - `support_inquiry`(`inquiry_id`, `user_id`, `reference` unique, `category` CHECK, `title`, `description`, `contact` CHECK, `reply_email` NULL, `idempotency_key_digest`, `request_digest`, `received_at`, `target_at` NULL, `target_state`, `calendar_version`, `timezone`, `status` CHECK, `answer` NULL, `first_answered_at` NULL, `target_met` NULL, `resolved_at` NULL, 유일 `(user_id, idempotency_key_digest)`, 색인)
- [x] `operations/SupportInquiryApi.java`(public) + `internal/SupportInquiryService`·`SupportInquiryRepository`·`BusinessDayCalendar`·`InquiryRules`
  - 접수·목록·보존 삭제·집계·운영 전이를 둔다.
  - `OperatorCommandApi`·`OperatorEnvelopeService`에 `update-inquiry`를 더하고, 정책 1.4로 올린다.
  - `OperationsDeletionApi.eraseInquiries`를 더한다.
- [x] 루트 `SupportInquiryController.java`(`/api/support/inquiries` GET·POST)·`OperatorController`(`/api/operator/inquiries`·`/{reference}`·`/metrics`)·`OperatorGate`·`AuthSecurityConfig`
- [x] 프런트엔드
  - `components/SupportInquiryPanel.tsx` + `session/useSupportInquiries.ts` + `content/support.ts`, `SettingsScreen`에 배치
  - 운영자 `OperatorScreen` 문의 영역(목록·상세·전이 폼·대화상자·집계) + `session/useOperatorInquiry.ts`
- [x] 시험
  - 백엔드 `BusinessDayCalendarTest`(fixture: 매트릭스 날짜 전부, 경계 시각, 달력 없음)
  - 백엔드 `SupportInquiryTest`: 매트릭스 전 행, 민감값 규칙 케이스, 로그 캡처에 본문·이메일 없음, 감사 details allowlist
  - 프런트 폼·목록·입력 보존·운영자 흐름 시험
  - e2e 주입 origin: 사용자 접수 → 운영자 답변 → 사용자 목록 답변, axe·320px
- [x] 문서 -- `CLAUDE.md` 엔드포인트·계약 한 줄, `DEVELOPMENT.md`(달력 seed 갱신 절차: 다음 해 월력요항 발표 뒤 새 버전 마이그레이션).

**Acceptance Criteria:**
- Given 달력 seed와 `BusinessDayCalendar`를 고정 fixture로 돌리면, then 매트릭스의 날짜 넷이 정확히 그 목표 시각이다.
- Given 로그를 캡처한 채 접수·운영 답변을 하면, then 로그에 제목·본문·회신 이메일·답변 원문이 0건이다.
- Given 원장 repository를 훑으면, then UPDATE는 `eraseActor` 한 문장뿐이다.
- Given 백엔드 build·프런트엔드 전체 검증·새 스택 `verify-same-origin.sh --keep-stack`·주입 origin e2e를 돌리면, then 모두 통과한다.

## Spec Change Log

- 2026-10-11 CI 수정(PR #85 첫 실행): `SupportInquiryTest`의 답변 시험이 표지로 쓴 UUID 앞 8자가 숫자만으로 이어지면 금융값 규칙(숫자 6자리 이상)에 걸려 422가 났다(약 2% 확률, CI에서 재현). 제목·본문·답변 표지를 영문 소문자 8자(`letterMarker`)로 바꿨다 — 시험 입력만의 결함이고 규칙·제품 코드는 그대로다. SPEC 본문 변경 없음.

- 2026-10-10 Matrix Test Audit(step-03): 13행 모두 `SupportInquiryTest`·`BusinessDayCalendarTest`·`InquiryRulesTest`·`SupportInquiryPanel.test.tsx`가 덮고 Testcontainers 빌드 1320건·새 스택 주입 origin e2e 68건 통과. 구현이 정한 곳 — 금융값 규칙을 3.5 공지 메시지와 `InquiryRules.financialLike` 한 벌로 합침, 원장 target은 reference의 keyed digest, 새 접수 201·같은 키 재전송 200, 접수는 사용자 advisory 잠금 아래에서 키·상한·목표·INSERT. SPEC 본문 변경 없음.

- 2026-10-10 리뷰 patch 루프(blind·edge-case·verification-gap): 주요 patch — 민감값 규칙 오탐 축소(토큰은 영문자·숫자가 함께 있는 20자 이상 낱말, 정규 UUID·대문자 오류 code 허용, ISO 날짜·시각은 금융값에서 제외)와 우회 차단(NFKC·`\p{Cf}` 제거 뒤 판정, 유니코드 공백 구분자, 전각 통화, 제목의 서식 문자·줄 구분자 거절), 서버·화면 규칙을 공유 fixture 표(`frontend/src/content/inquiryRules.fixture.json`)로 함께 강제, 운영자 상세의 회신 이메일 원문(목록은 마스킹)과 사용자 회신 카피 정정, 사용자 목록의 본문 펼치기, 조회 트랜잭션의 `CALENDAR_PENDING` 채우기(정해진 목표는 소급하지 않음), 집계 영업시간을 행의 `calendar_version` 달력으로, seed·fixture 짝 시험을 지금 버전의 최신 마이그레이션으로, 집계 `purgeHorizon`, 운영자 목록 미해결 우선·상태 필터·접수 번호 직접 열기, 처리 폼은 가능한 다음 전이만, 확정 시 주인 계정 삭제 진행이면 `TARGET_INVALID`·`eraseInquiries`는 접수와 같은 사용자 잠금, 재생 상태를 공통 `status`로 읽음, 확인 문구 낱말을 사용자 표지와 같게(`접수됨`·`해결됨`), 보존 삭제·미정 채우기를 `SKIP LOCKED` 묶음으로, 본문 상한을 이스케이프 최대치로, 프런트 재전송 상태·연락처 모양·응답 없는 접수 뒤 입력 변경 시 목록 재확인, 운영자 훅 프로미스 거부를 실패로, 사용자 화면 버튼 토큰, 시험 보강(확정 시점 대상 소멸·주인 삭제, 같은 키·다른 키 동시 접수, 두 운영자 동시 확정, 미래 목표 미답변 분모 제외, 집계 숫자 노드, 삭제 계정 404 단정). defer 1건 — 답변 정정·사용자 후속 응답·재개. SPEC 본문 변경 없음.

## Design Notes

- **연락처를 계정에서 가져오지 않는 이유.** `identity` 공개 API에 이메일이 없다(세션은 `user_id` 하나만 담는다). 이메일 회신을 원하는 사용자만 직접 입력하고, 그 값은 문의 행과 함께 90일 보존·삭제 port를 따른다. 기본은 `IN_APP`(설정 화면의 상태·답변)이라 개인정보 입력이 없다.
- **보존 삭제를 조회 트랜잭션에서 하는 이유.** 2.14가 고정한 "백그라운드 없음"과 같은 선택이다. 문의가 읽히는 모든 경로가 먼저 지우므로 만료된 문의는 누구에게도 보이지 않는다. 행이 물리적으로 남는 시간은 다음 조회까지다.
- **목표 미정을 접수 거절로 접지 않는 이유.** 사용자의 문의는 받아야 한다. 확정을 막는 것은 deadline이지 접수가 아니다(`SUPPORT-1.0`).
- **2027 설 연휴.** 월력요항(한국 표준시 기준 음력)은 설날을 2/7(일)로 두고 대체공휴일을 2/9로 정한다. 2/6을 설날로 계산한 자료(중국 기준)와 다르므로 출처를 머리말에 남긴다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 통과, 경고 0.
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run build && npm --prefix frontend run test && npm --prefix frontend run test:e2e` -- expected: 통과.
- `docker compose --file deploy/compose/compose.yaml down --remove-orphans --volumes && deploy/compose/verify-same-origin.sh --keep-stack && PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 npm --prefix frontend run test:e2e:run` -- expected: 모두 통과(끝나면 스택 down).

## Suggested Review Order

**접수의 원자성과 목표**

- 진입점: 사용자 잠금 아래 키 판정 → 24시간 상한 → 목표 계산 → INSERT 한 번
  [`SupportInquiryService.java:71`](../../backend/src/main/java/com/bokrilog/operations/internal/SupportInquiryService.java#L71)

- 영업일 목표: 접수 KST 날짜 다음 세 번째 영업일 18:00
  [`BusinessDayCalendar.java:31`](../../backend/src/main/java/com/bokrilog/operations/internal/BusinessDayCalendar.java#L31)

- 달력 seed(2026 개정 법령·2027 월력요항)와 문의 표
  [`V22__support_inquiry.sql:39`](../../backend/src/main/resources/db/migration/V22__support_inquiry.sql#L39)
  [`V22__support_inquiry.sql:104`](../../backend/src/main/resources/db/migration/V22__support_inquiry.sql#L104)

**민감값 규칙**

- NFKC·서식 문자 제거 뒤 SECRET·WALLET·FINANCIAL·EMAIL 판정(서버·화면 공유 fixture)
  [`InquiryRules.java:122`](../../backend/src/main/java/com/bokrilog/operations/internal/InquiryRules.java#L122)

**운영 처리**

- `update-inquiry` 확정: 잠금 → 권한 → 행 잠금 → 주인 계정·전이 재검사 → 조건부 UPDATE
  [`OperatorEnvelopeService.java:906`](../../backend/src/main/java/com/bokrilog/operations/internal/OperatorEnvelopeService.java#L906)

- 조회 트랜잭션의 보존 삭제·미정 목표 채우기(SKIP LOCKED 묶음)
  [`SupportInquiryService.java:174`](../../backend/src/main/java/com/bokrilog/operations/internal/SupportInquiryService.java#L174)

**화면**

- 사용자 접수 HTTP 표면
  [`SupportInquiryController.java:150`](../../backend/src/main/java/com/bokrilog/SupportInquiryController.java#L150)

- 설정 화면 문의 영역: 입력 보존·자동 재제출 없음·같은 키 재전송
  [`SupportInquiryPanel.tsx:139`](../../frontend/src/components/SupportInquiryPanel.tsx#L139)
  [`useSupportInquiries.ts:239`](../../frontend/src/session/useSupportInquiries.ts#L239)

**주변**

- 시험: 매트릭스 전 행·동시성·로그 원문 0건·달력 경계
  [`SupportInquiryTest.java:1`](../../backend/src/test/java/com/bokrilog/SupportInquiryTest.java#L1)
  [`BusinessDayCalendarTest.java:1`](../../backend/src/test/java/com/bokrilog/operations/internal/BusinessDayCalendarTest.java#L1)
