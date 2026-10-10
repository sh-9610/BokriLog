---
title: 'Story 3.5 — 제한·장애·복구 상태를 서비스 내부 공지로 안내하기'
type: 'feature'
created: '2026-10-10'
status: 'done'
review_loop_iteration: 0
baseline_commit: 'e10289f4839c89568ca3fcdedc4edb0eeea6debe'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-4-manual-recovery.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 3.2~3.4가 만든 제한은 사용자 화면에 기능별 조각 문구로만 드러난다. 대시보드 `RESTRICTED`, 동기화 `SYNC_UNAVAILABLE` 같은 것들이다. 사용자는 무엇이 어디까지 멈췄는지, 무엇은 계속 쓸 수 있는지, 언제 다시 안내받는지, 어디로 문의하는지 알 수 없다. 운영자도 그것을 알릴 수단이 없다(`epics.md` Story 3.5 AC-1~6, `deferred-work.md`의 "제한 중 사용자 상태 표시" 항목들).

**Approach:**
- **저장.** `operations`가 incident와 그 공지 버전을 추가 전용으로 소유한다.
- **작성.** 운영 command `publish-notice` 하나가 새 incident(`INVESTIGATING`)와 같은 incident의 새 버전(`SCOPE_CHANGED`·`RECOVERED`)을 발행한다. 3.1 envelope·감사를 지난다.
- **사용자 조회.** 인증 사용자의 `GET /api/notices`가 그 사용자에게 적용되는 effective restriction과 그것을 덮는 최신 공지 버전을 함께 풀어 돌려준다. 공지가 없는 제한은 안전한 기본 항목으로 접는다.
- **화면.** 대시보드와 설정 상단에 마지막 정상값과 분리된 공지 영역을 둔다.

## Boundaries & Constraints

**Always:**
- **공지 계약(AC-1).** 공지 버전 하나는 다음 필드를 필수로 가진다.
  - `incidentId`, `version`(1부터 증가), `status`(`INVESTIGATING|SCOPE_CHANGED|RECOVERED`)
  - `startedAt`(incident 첫 버전의 DB 시각)
  - `affectedFeatures`·`continuingFeatures`: 연결한 제한의 기능에서 서버가 계산한다. 닫힌 기능 코드 넷의 부분집합이다.
  - `message`: 운영자 한 줄. 3.1 사유 규칙(길이 200자·이메일 모양 거절)에 더해 숫자 6자리 이상 연속·URL 모양을 거절한다(금융값·식별자·내부 경로 차단).
  - 다음 시각: `INVESTIGATING|SCOPE_CHANGED`면 `nextUpdateAt`(지금보다 미래·7일 이내), `RECOVERED`면 `recoveredAt`(DB 시각)
  - `supportContact`: 배포 설정 `bokrilog.operations.support-contact`(env `BOKRILOG_SUPPORT_CONTACT`, 이메일 형식). 비면 발행을 `SUPPORT_CONTACT_UNCONFIGURED`(422)로 거절한다 — 허위 지원 경로를 만들지 않는다.
  - 공지 응답·행에는 사용자 식별자·금융값·제한 target 원문을 넣지 않는다.
- **연속 이력(AC-3).**
  - `operations_notice_version`은 추가 전용이다. 버전 행에는 이전 버전 번호와 연결 제한 ID 목록(`covers`)이 있다. 수정·삭제 SQL은 없다(소스 스캔).
  - 새 버전은 같은 incident의 최신 버전 다음 번호여야 한다. 경합은 원장 잠금 아래에서 `NOTICE_VERSION_CONFLICT`(409)다.
  - `RECOVERED` 뒤에는 새 버전을 받지 않는다(`INCIDENT_CLOSED`, 409).
  - `covers`는 발행 시점의 활성 제한 ID여야 한다(`TARGET_INVALID`). 단, `RECOVERED`는 이미 닫힌 제한을 가리킬 수 있다.
- **작성 command(AC-6).** `publish-notice`는 `command` 권한, 3.1 재인증·허가·멱등 키·`SessionRotation`, 감사 `REQUESTED`/`COMPLETED`(`details` = `{incidentId, version, status, covers 수}`, message 원문 제외)를 쓴다.
  - 확인 문구: 새 incident는 `공지 발행 새 사건`, 기존은 `공지 <status 라벨> v<version>`.
  - 정책 버전 `OPERATOR-COMMAND-1.3`.
- **사용자 범위 매핑(AC-2).** `GET /api/notices`(인증, 공통 오류 계약, 계정 접근 판정 `AccountAccessApi` SENSITIVE)는 그 사용자의 차원(활성·최신 계획, 기본 portfolio, 현재 연결, 계산 정책)으로 기능 넷 각각의 `matching`을 읽는다.
  - 각 효력 제한을 덮는 최신 공지 버전(`covers`에 그 ID)이 있으면 그 공지를 싣는다.
  - 없으면 `{kind: "RESTRICTED_WITHOUT_NOTICE", affectedFeatures, continuingFeatures}` 기본 항목을 싣는다. 공지가 없다고 제한을 숨기거나 허용으로 접지 않는다.
  - `RECOVERED` 공지는 `recoveredAt`부터 72시간 동안 그 incident의 제한에 영향을 받았던 사용자에게만 싣는다. 판정은 공지가 덮는 제한의 차원이 그 사용자 차원과 맞는가로 한다.
  - 응답은 `{asOf, expiresAt, items[]}`이다. `expiresAt`은 항목들의 `nextUpdateAt` 최솟값과 `asOf + 5분` 중 이른 쪽이다.
- **화면(AC-4·AC-5).**
  - `components/ServiceNotice.tsx`를 대시보드(`SignedInScreen`)와 설정(`SettingsScreen`) 상단, 마지막 정상값 블록 밖에 둔다.
  - 각 항목은 상태 표지를 문장으로 보인다(`조사 중`·`범위 변경`·`복구됨`·`운영 제한`). 색만으로 구분하지 않는다. 이어서 시작 시각·영향 기능·계속 가능한 기능·다음 안내 또는 복구 시각·지원 연락처(`mailto:` 링크, 설정값이 있을 때만)를 보인다.
  - 재시도 버튼은 두지 않는다.
  - 조회 실패나 모르는 모양이면 마지막으로 검증된 응답이 `expiresAt` 전일 때만 그것을 유지한다. 아니면 "서비스 상태를 확인하지 못했습니다" 문장 하나를 보이고 기능 허용을 주장하지 않는다.
  - 401·403은 메모리 값을 지운다. 브라우저 저장소는 쓰지 않는다.
  - 공지 영역은 정적 콘텐츠(landmark `region` + 제목)이고, 화면의 live region을 늘리지 않는다.
  - 320px·400% 확대에서 줄바꿈으로 읽힌다.
- **운영자 화면.** `/operator`에 공지 영역을 둔다.
  - incident 목록과 각 버전 이력(읽기 전용)을 보인다.
  - 발행 폼: 새 incident 또는 기존 incident 선택, 상태, 활성 제한 체크(목록 재사용), 메시지, 다음 안내 시각.
  - 그다음 `useConfirmationDialog` 대화상자에서 계산된 영향·계속 가능한 기능을 미리보기로 보이고 문구를 다시 입력받는다. 취소가 기본 포커스다. 그 뒤 확정·취소.
  - incident 목록 조회 `GET /api/operator/notices`(`command` 권한)를 둔다.
- **삭제.** 공지 행에는 사용자 귀속이 없다. 삭제된(삭제 진행) 계정은 `GET /api/notices`가 공통 401/404 계약으로 거절한다.
- **마이그레이션.** 신규는 `V21__operations_notice.sql` 하나다(incident·version 표, CHECK, 색인).
- **주석·카피.** 새 주석·카피는 한국어다. 카피는 `content/serviceNotice.ts`이고, 금지 표현(실시간·정확 보장·손실 공포)이 없음을 시험한다.

**Ask First:**
- 공지에 금융값·사용자별 세부를 넣는 것.
- 푸시·SMS·이메일 발송.
- 비로그인 진입 화면에 공지를 보이는 것.
- 72시간 복구 표시 기간·5분 만료를 바꾸는 것.

**Never:**
- 공지 버전 수정·삭제.
- 공지가 제한을 풀거나 허용으로 접는 것.
- 허위·빈 지원 링크.
- 지원 문의 접수(3.6).
- 사용자 화면의 재시도 유도.
- 브라우저 저장소 사용.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 새 incident | 운영자 `publish-notice` `{incident: NEW, status: INVESTIGATING, covers:[r1], message, nextUpdateAt}` | v1 행, 감사 `REQUESTED`/`COMPLETED` | 빈 연락처 422 `SUPPORT_CONTACT_UNCONFIGURED`, 메시지 숫자열·URL 422, 닫힌 제한 `TARGET_INVALID` |
| 범위 변경 | 같은 incident `SCOPE_CHANGED` covers [r1,r2] | v2, v1 불변, 이전 버전 연결 | 동시 v2 둘 → 하나 409 `NOTICE_VERSION_CONFLICT` |
| 복구 | `RECOVERED` | v3 `recoveredAt`, 이후 발행 409 `INCIDENT_CLOSED` | — |
| 대상 사용자 | r1=`SYNC:PLAN` A, 사용자 A의 `GET /api/notices` | 공지 항목 1, 영향 `SYNC`, 계속 가능 셋 | — |
| 비대상 사용자 | 사용자 B | 빈 `items` | — |
| 공지 없는 제한 | `NEW_CONNECTION:GLOBAL`, 공지 없음 | 모든 사용자에게 `RESTRICTED_WITHOUT_NOTICE` 항목 | — |
| 복구 후 | v3 `RECOVERED`, 73시간 경과(행 시각 이동) | 72시간 안에는 A에게 복구 항목, 지나면 없음 | — |
| 조회 실패 | 화면, `/api/notices` 500 | 만료 전 마지막 응답 유지, 없으면 "확인하지 못했습니다" | 재시도 버튼 없음 |
| 삭제 계정 | 삭제 진행 계정 | 공통 401/404, 사용자별 항목 없음 | — |
| 권한 | 비운영자·`audit`만 가진 운영자의 발행·`/api/operator/notices` | 404 | — |

</frozen-after-approval>

## Code Map

- `_bmad-output/planning-artifacts/epics.md` Story 3.5 -- AC-1~6. 최고 마이그레이션 V20.
- **command 본보기(3.4)**
  - `operations/OperatorCommandApi.java`: `LIFT_RESTRICTION`:51, `POLICY_VERSION`(현재 1.2), sealed 결과·replay
  - `operations/internal/OperatorEnvelopeService.java`: `validateLift`·`completeLift`:494(잠금 → 권한 → 재검사 → 쓰기 → `COMPLETED`)
  - 루트 `OperatorCommandService.java`: 준비 순서(키 → 재인증 → 대상)
  - `OperatorController.java`: REFUSALS, `PreparedImpactView` sealed
- **제한 판독**
  - `operations/OperationalRestrictionApi.java`: `evaluate`:59-68, `matching`:87, `active`
  - `RestrictionFeature` 기능 넷
  - 루트 `RestrictionTargets.java`: 기능별 단계·남는 기능 계산, `remaining`·`subjectOf`(차원 구성 본보기)
- **사용자 차원 공개 API**
  - `sync/PlanPublicationPort.activePlan(userId)`:44·`latestPlan`:93
  - `source/SourceConnectionApi.currentConnection(userId)`:243
  - `portfolio/PortfolioMembershipApi.findDefaultPortfolio(userId)`:133
  - 계산 정책은 계획의 `calculationPolicy`
- **인증 표면:** 새 접두사 인가는 `AuthSecurityConfig`의 판정 술어 하나에 더한다(전역 `authenticated()` 금지, CLAUDE.md "인가는 새 접두사에만"). 본보기는 `/api/connections/**`. 세션 사용자는 `AuthSessionController.currentUserId`.
- **화면**
  - `frontend/src/screens/SignedInScreen.tsx`: 대시보드, `useSessionGate('dashboard')`:60
  - `screens/SettingsScreen.tsx`
  - 운영자 `screens/OperatorScreen.tsx`(제한·복구 영역 본보기), `session/useOperatorRecovery.ts`, `session/operatorApi.ts`, `content/operator.ts`(`formatOperatorTime`)
  - 사용자 조회 엔진 본보기 `session/useDashboard.ts`(늦은 응답 버림·401/403 지움)
  - 디자인 토큰 `styles/tokens.css`
- **시험 본보기:** `OperatorRecoveryTest`·`OperatorRestrictionTest`(command), `RestrictionEnforcementTest`(사용자 차원 제한), `OperatorTestBase`, `AuthFlowTestBase.java:228`(TRUNCATE에 공지 표 추가), `OperatorScreen.test.tsx`, e2e `operator.spec.ts`

## Tasks & Acceptance

**Execution:**
- [x] `db/migration/V21__operations_notice.sql`
  - `operations_notice_incident(incident_id PK, started_at default statement_timestamp())`
  - `operations_notice_version(incident_id FK, version, status CHECK, message, next_update_at, recovered_at, covers uuid[], operation_id, created_at, PK(incident_id, version), CHECK 상태별 시각)`
- [x] `operations/ServiceNoticeApi.java`(public: 사용자 조회용 해석·운영자 목록) + `internal/ServiceNoticeService`·`ServiceNoticeRepository`
  - 추가 전용 SQL과 버전 규칙을 둔다.
  - `OperatorCommandApi`·`OperatorEnvelopeService`에 `publish-notice` 검증·문구·`completeNotice`를 더하고 정책 1.3으로 올린다.
  - `OperatorProperties`(또는 새 설정 record)에 `support-contact`를 두고 `application.yml`·compose env를 맞춘다. compose는 검증 스크립트와 e2e가 주입하는 개발용 값을 쓴다.
- [x] 루트 `ServiceNoticeController.java`(`GET /api/notices`)·사용자 차원 해석·`OperatorController`(`GET /api/operator/notices`, 새 code)·`AuthSecurityConfig` 접두사
- [x] 프런트엔드
  - `components/ServiceNotice.tsx` + `session/useServiceNotices.ts`(새 파일들) + `content/serviceNotice.ts`, `SignedInScreen`·`SettingsScreen`에 배치.
  - 운영자 `OperatorScreen` 공지 영역 + `session/useOperatorNotice.ts` + `operatorApi.ts`·`content/operator.ts`.
- [x] 시험
  - 백엔드 `ServiceNoticeTest`: 매트릭스 전 행, 추가 전용 소스 스캔, 응답 allowlist(UUID·숫자열·이메일 외 값 없음), 기능별 매핑 — plan·connection·portfolio·policy·global.
  - 프런트: 화면·훅 시험(표지 문장·마지막 정상값 분리·재시도 부재·만료 캐시·실패·401), 운영자 공지 흐름.
  - e2e 주입 origin: 운영자가 광고 제한 → 공지 발행, 같은 사용자 대시보드에 공지, 복구 순서. axe·320px 검사 포함.
- [x] 문서 -- `CLAUDE.md` 엔드포인트·계약 한 줄, `DEVELOPMENT.md` env(`BOKRILOG_SUPPORT_CONTACT`).

**Acceptance Criteria:**
- Given 효력 중인 제한이 사용자에게 적용되면, when 그 제한을 덮는 공지가 없더라도, then `GET /api/notices`는 그 제한을 `RESTRICTED_WITHOUT_NOTICE`로 싣는다.
- Given 공지 버전 표의 운영 SQL을 훑으면, then UPDATE·DELETE가 0건이다.
- Given 원장 repository를 훑으면, then UPDATE는 `eraseActor` 한 문장뿐이다.
- Given 백엔드 build·프런트엔드 전체 검증·새 스택 `verify-same-origin.sh --keep-stack`·주입 origin e2e를 돌리면, then 모두 통과한다.

## Spec Change Log

- 2026-10-10 리뷰 patch 루프(blind·edge-case·verification-gap): 주요 patch — 지원 연락처를 버전 행(`support_contact` NOT NULL)에 확정 시점 값으로 고정해 조회가 그 값을 싣고, 복구 공지는 덮는 제한이 모두 닫혀 있어야 하며(열려 있으면 `TARGET_INVALID`, 운영자 화면은 덮는 제한을 비워 보내고 열린 제한을 미리 말한다), 연락처 검사를 멱등 키·재인증 판정 뒤로 옮기고, 확정 중 무결성 위반은 버전 기본키(`operations_notice_version_pkey`)만 `NOTICE_VERSION_CONFLICT`·나머지는 실행 실패, 확인 문구가 사건·범위를 묶음(`공지 <상태> <사건 ID 앞 8자> v<n>`·`공지 발행 새 사건 제한 <n>건`), 메시지 규칙 강화(구분자로 이은 숫자 6자리 이상·IP·포트·통화 금액·화면 금지 표현 — 금지 목록은 `content/copyRules.ts` 하나를 서버가 대조), 다음 안내가 지난 공지는 `overdue`로 싣고 만료 계산에서 뺌, `forSubjects`를 읽기 전용 `REPEATABLE READ` 한 스냅샷으로 묶고 복구 대상 판정을 한 질의로, 해제된 연결을 계획 계보 차원에서도 뺌, 범위 밖 다음 안내 시각 422, 삭제 계정 404 고정, `useServiceNotices`의 404 지움·상대 TTL·렌더 시점 기한·기한 타이머, 시각 `KST` 표기와 운영자 입력의 KST 해석, 운영자 미리보기가 사용자 화면과 같은 기능 문장, 진입 화면의 공지 조회 0건 단정, 시험 보강(확정 순간 덮는 제한 해제·연락처 비움·같은 키 다른 범위, 활성 제한만 보내는 범위 변경, 늦어짐 표시·재조회 간격, 종료 계획의 PLAN 차원). defer 1건 — 운영 command 상태 기계 사본 셋째(`useOperatorNotice`). reject 1건 — 작업 중 sprint 상태 표기. e2e `tabStops` 보조는 유지 결정. SPEC 본문 변경 없음.

- 2026-10-10 Matrix Test Audit(step-03): 10행 모두 `ServiceNoticeTest`·`ServiceNotice.test.tsx`가 덮고 Testcontainers 빌드 1280건·새 스택 주입 origin e2e 67건 통과. 구현이 정한 곳 — 공지 메시지가 원장 사유 열에 남음(`details`에는 원문 없음), 복구 공지의 `covers`를 비우면 직전 버전 것을 이음, 사용자 화면은 남은 신뢰 시간의 절반(최소 15초)마다 다시 읽음, 다른 스토리 e2e 넷의 키보드 순서 시험이 공지 링크를 건너뛰도록 `e2e/support/tabStops.ts`를 더함. SPEC 본문 변경 없음.

## Design Notes

- **영향·계속 가능 기능을 서버가 계산하는 이유.** 운영자가 자유롭게 쓰면 실제 제한과 공지가 어긋난다. 공지가 "동기화만 멈춤"이라는데 표시도 막혀 있는 식이다. 연결한 제한에서 계산하면 AC-2의 "제한과 범위 일치"가 구조적으로 성립한다. 운영자가 쓰는 것은 원인 수준의 한 줄뿐이다.
- **공지가 없는 제한의 기본 항목.** epic 문맥은 "대응 공지가 없으면 fail-closed"라고 했다. 3.3 자동 정지는 운영 시간 밖에 공지 없이 생긴다. 그때 사용자는 최소한 무엇이 멈췄는지 알아야 한다.
- **지원 연락처가 비면 발행 거절.** 문의 접수(3.6) 전이라 화면이 열 문의 화면이 없다. 설정된 연락처만 진짜 경로다. 기본 항목은 연락처가 있을 때만 보인다.
- **72시간·5분.** 복구 공지는 "종료 상태·시각을 같은 채널에 남긴다"는 요구를 위해 일정 기간 보여야 한다. 만료 5분은 마지막 검증 응답을 얼마나 믿을지의 상한이다. 둘 다 Ask First로 고정한다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 통과, 경고 0.
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run build && npm --prefix frontend run test && npm --prefix frontend run test:e2e` -- expected: 통과.
- `docker compose --file deploy/compose/compose.yaml down --remove-orphans --volumes && deploy/compose/verify-same-origin.sh --keep-stack && PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 npm --prefix frontend run test:e2e:run` -- expected: 모두 통과(끝나면 스택 down).

## Suggested Review Order

**공지 작성(운영자 command)**

- 진입점: 원장 잠금 아래 버전 연속·닫힌 사건·덮는 제한·연락처를 다시 보고 버전 행을 쓴다
  [`OperatorEnvelopeService.java:754`](../../backend/src/main/java/com/bokrilog/operations/internal/OperatorEnvelopeService.java#L754)

- 추가 전용 incident·버전 표와 버전마다 고정한 지원 연락처
  [`V21__operations_notice.sql:37`](../../backend/src/main/resources/db/migration/V21__operations_notice.sql#L37)

**사용자 범위 매핑**

- 한 스냅샷에서 사용자 차원의 효력 제한과 덮는 공지를 함께 푼다
  [`ServiceNoticeService.java:55`](../../backend/src/main/java/com/bokrilog/operations/internal/ServiceNoticeService.java#L55)

- 사용자 차원 구성과 `GET /api/notices`
  [`ServiceNoticeController.java:103`](../../backend/src/main/java/com/bokrilog/ServiceNoticeController.java#L103)

**화면**

- 마지막 정상값과 분리된 공지 영역(상태 표지 문장, 재시도 없음)
  [`ServiceNotice.tsx:88`](../../frontend/src/components/ServiceNotice.tsx#L88)

- 상대 TTL 신뢰 판정·만료 타이머·401/403/404 지움
  [`useServiceNotices.ts:211`](../../frontend/src/session/useServiceNotices.ts#L211)

- 운영자 발행 미리보기(사용자 화면과 같은 문장, 문구 재입력)
  [`OperatorNoticeDialog.tsx:40`](../../frontend/src/components/OperatorNoticeDialog.tsx#L40)

**주변**

- 시험: 매트릭스 전 행·확정 시점 재검사·메시지 규칙·추가 전용 스캔
  [`ServiceNoticeTest.java:1`](../../backend/src/test/java/com/bokrilog/ServiceNoticeTest.java#L1)
  [`ServiceNotice.test.tsx:1`](../../frontend/src/components/ServiceNotice.test.tsx#L1)
