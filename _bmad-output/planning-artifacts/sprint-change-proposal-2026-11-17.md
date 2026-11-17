---
title: 'Sprint Change Proposal — G-5 전 결정 묶음(Epic 5 회고 AI-6)'
date: '2026-11-17'
trigger: 'epic-5-retro-item-53'
workflow: 'bmad-correct-course'
mode: 'batch'
scope: 'moderate'
status: 'approved'
approvedBy: '사용자(2026-11-17 — 결정 여섯의 요약을 본 뒤 "진행해줘"로 구현까지 승인)'
baseline_commit: 'dd6d30e'
---

# Sprint Change Proposal — G-5 전 결정 묶음

이 문서는 Epic 5 회고 AI-6(`epic-5-retro-item-53`)이 사용자 몫으로 남긴 결정 여섯을 정한다. 정한 것은 **제품·엔지니어링 기본값**이다. G-5 자체(법률·개인정보 승인)는 외부 승인이며 이 문서가 대신하지 않는다 — production 삭제 실행과 공개 베타는 G-5 승인 전까지 지금처럼 막혀 있다(`ACCOUNT_DELETION_UNAVAILABLE`). 아래 결정은 G-5 검토에 올릴 초안의 내용이 된다.

## 1. Issue Summary

- **계기.** Epic 5 회고(`epic-5-retro-2026-11-09.md`)가 R-4·R-5·R-6·R-8·B-2·B-3을 "G-5 전 결정"으로 묶었다. `ERD-1.1.0` §9도 같은 항목을 "미결정 — 주인 G-5(Epic 5 회고 item-53)"로 넘겼다(`EPIC-IMPLEMENTATION-READINESS-1.1.md:205·206·209`).
- **문제 유형.** 구현 중 드러난 기술적 한계와 원래 요구의 모호함이 섞여 있다. 사용자에게 보여 주는 약속이 구현보다 강하고(R-4), 고지 hash가 그 약속을 덮지 않으며(R-5), 새 보존 범주(재가입 차단)에 기한이 없고(R-6·B-3), 출시 증거 일부가 기계가 읽는 자리에 없다(R-8).
- **근거.**
  - 백업 카피 `frontend/src/content/accountDeletion.ts` `backup(days)`는 "요청일부터 최대 30일 안에 파기"·"복원하더라도 삭제한 계정은 되살리지 않습니다"라고 쓴다. 그런데 실행이 멈추면 백업을 앞당겨 지우지 않고 `OVERDUE` 경보만 낸다(사용자 결정 2026-11-06, `spec-5-3:92`). 또 요청 확정 → 첫 claim(tombstone 기록) → 첫 외부 내보내기(15분 timer) 사이의 창(deferred 171 잔여)에 장애가 나서 그 이전 generation으로 복원하면 요청 자체가 사라진다.
  - `AccountDeletionPolicy.noticeHash()`는 버전·대상 code·파기 일수·법정 보존 목록·고정 문구만 직렬화한다. "남기는 것" 문장은 5.3·5.4에서 바뀌었지만 정책 버전과 hash는 `ACCOUNT-DELETION-1.0` 그대로다(`spec-5-4:119`).
  - `identity_deleted_identity`(V33)에는 만료·해제 경로가 없다. Story 5.4 AC-2는 "이후 신규 가입 허용은 G-5 정책만 따른다"고 썼다.
  - NFR-18(5.4 AC-5 실환경 통합시험)과 실저장소 파기의 미수행 표기는 `DEVELOPMENT.md`에만 있고, `epics.md` frontmatter `externalLaunchGates`는 G-4·G-5·production 비밀 셋뿐이다.

## 2. Impact Analysis

### 체크리스트 요약

| 절 | 결과 |
|---|---|
| 1 계기·문제·근거 | [x] 위 §1 |
| 2 에픽 영향 | [x] Epic 5는 `done` 유지. 새 스토리 없이 chore 하나로 반영한다. Epic 1~4는 계약 변화 없음 |
| 3 산출물 충돌 | [!] `epics.md` frontmatter(출시 증거 이름), 계정 삭제 카피·정책 버전, `DEVELOPMENT.md` 복원 절차. PRD 범위·아키텍처 불변식은 그대로 |
| 4 경로 선택 | [x] Direct Adjustment(아래 §3) |
| 5 제안 구성 | [x] 아래 §4 |
| 6 승인·인계 | [x] 상시 위임으로 추천안 채택, chore 인계 |

### 에픽·스토리 영향

- **Epic 5.** 모든 스토리는 `done`이다. 결정은 Story 5.1(정책·고지)·5.3(백업 약속)·5.4(재가입 차단·완료 세션)의 as-built 위에 얹히며 AC를 다시 열지 않는다. 5.4 AC-2가 G-5에 맡긴 "이후 신규 가입"을 이 문서가 정한다.
- **Epic 1.** B-2의 완료 세션 보호는 Epic 1 실패 수렴 규칙을 바꾸지 않는 쪽으로 정한다(D-5).
- **Epic 3.** 복원 절차(3.10)에 한 단계가 더해진다(D-2). 출시 증거 이름이 회고 item-34의 요구를 채운다(D-6).

### 기술 영향

- 정책 버전 `ACCOUNT-DELETION-1.0 → 1.1`(서버 상수·화면 상수·공유 표·V30 CHECK 모양 안).
- 고지 hash 입력에 화면 고지 문장을 더한다.
- 전체 삭제 완료(`DELETION_COMPLETED`) 기록과 같은 주기에서 재가입 차단 행을 지운다.
- production은 G-5 전이라 요청 행이 0건이다. 정책 버전을 올려도 이미 저장된 요청이 없어 비용이 가장 낮을 때다(회고 Open question 3).

## 3. Recommended Approach

**Direct Adjustment.** 새 스토리나 롤백 없이 chore 하나(`bmad-build`)로 반영한다. 백업을 앞당겨 지우거나 API에 tombstone 키를 주는 식의 큰 구조 변경은 택하지 않는다 — 약속을 구현에 맞춰 정직하게 고치고, 남는 창은 고지와 운영 절차로 덮는다.

- **노력:** 중간(백엔드 정책 상수·판정자 한 곳, 프런트 카피·공유 표, 문서). 정책 버전 갱신에 새 마이그레이션은 필요 없다(V30 CHECK `^ACCOUNT-DELETION-[0-9]+\.[0-9]+$`가 1.1을 받는다).
- **위험:** 낮음. production 요청 행이 없고, 모든 변경이 개발·CI에서만 효력이 있다.
- **일정 영향:** 없음. 출시 경로의 다른 블로커(Bybit 실연동·운영 인프라)와 병렬이다.

## 4. Detailed Change Proposals — 결정 여섯

### D-1. 30일 파기 약속(R-4 앞부분)

- **결정:** 약속의 기준점을 "요청일"에서 "운영 데이터 삭제를 마친 날"로 바꾼다. 백업을 앞당겨 지우는 구현은 하지 않는다(2026-11-06 결정 유지).
- **근거:** 백업 generation은 생성 29일 초과 시 예외 없이 파기되고, 사용자 데이터를 담을 수 있는 마지막 generation은 운영 완료 + `CLOSURE_SLACK`(1시간) 전에 시작한 것이다. 그래서 "운영 데이터 삭제를 마친 날부터 최대 30일"은 구현이 지키는 문장이다. 실행이 재시도 필요로 멈춘 기간만큼 늦어질 수 있다는 사실도 함께 쓴다.
- **카피 OLD:** `암호화 백업에 남은 사본은 요청일부터 최대 ${days}일 안에 파기합니다.`
- **카피 NEW(초안):** `암호화 백업에 남은 사본은 운영 데이터 삭제를 마친 날부터 최대 ${days}일 안에 파기합니다. 삭제 처리가 늦어지면 그만큼 늦어질 수 있습니다.`
- **유지:** 내부 경보 기한(`요청 + 30일` → `OVERDUE`)은 그대로 둔다. 사용자 약속보다 엄격한 내부 목표다.

### D-2. "복원해도 되살리지 않음"(R-4 뒷부분, deferred 171 잔여)

- **결정:** 약속은 유지하되 적용 범위를 "삭제 처리가 시작된 요청"으로 좁혀 쓰고, 남는 창(요청 확정 뒤 외부 tombstone 내보내기 전)은 운영 절차로 덮는다 — 복원 뒤 복원 보고의 RPO 구간을 대상으로 서비스 공지(3.5)를 내어 그 구간에 삭제를 요청한 사용자에게 다시 요청하도록 안내한다.
- **근거:** 창을 코드로 없애려면 API가 tombstone 키를 가져야 하는데, 이는 tombstone 키 역할 제한(`TOMBSTONE_KEY_IN_API` gate)과 충돌한다. 남는 창은 일반 데이터의 RPO 손실과 같은 성격이며, 복원 보고가 이미 RPO를 싣는다.
- **카피 OLD:** `그 사이 백업으로 복원하더라도 삭제한 계정은 되살리지 않습니다.`
- **카피 NEW(초안):** `그 사이 백업으로 복원하더라도 삭제 처리가 시작된 계정은 되살리지 않습니다. 요청 직후 장애로 요청 기록까지 잃는 드문 경우에는 공지로 알려 드리며, 다시 요청해 주셔야 합니다.`
- **문서:** `DEVELOPMENT.md` 복원 절차에 "복원 보고의 RPO 구간 공지" 단계를 더한다.
- **deferred 171:** 이 결정으로 주인(G-5)의 판정을 내린다 — 남는 창은 고지·운영 절차로 수용한다(판정 기록은 chore의 done 전환).

### D-3. 고지 hash 범위(R-5, 회고 Open question 3)

- **결정:** 화면이 보여 주는 고지 문장(삭제 대상 문장·"남기는 것"·백업·법정 보존 문장)을 고지 hash 입력에 넣고, 정책 버전을 `ACCOUNT-DELETION-1.1`로 올린다.
- **방법(SPEC에서 확정):** 문장 원본은 이미 두 층이 함께 읽는 공유 표 `frontend/src/content/accountDeletionPolicy.fixture.json`으로 옮기고, 서버 정규 직렬화가 그 문장들을 포함한다. 화면은 같은 표를 참조한다. 문장을 바꾸면 hash가 바뀌어 시험이 버전 갱신을 강제한다.
- **근거:** ERD §9의 원래 의도("사용자 고지 hash를 삭제 요청에 고정")를 회복한다. D-1·D-2·D-4가 어차피 고지 문장을 바꾸므로 버전 갱신은 한 번으로 끝난다. production 요청 행이 0건이라 지금이 가장 싸다.

### D-4. 재가입 차단의 보존·해제·복원 내구성(R-6·B-3, 5.4 AC-2)

- **결정:**
  - **보존 기한:** 재가입 차단 기록은 그 삭제의 **전체 삭제 완료**(`DELETION_COMPLETED` — 운영 완료 + 백업 파기 `DESTROYED` + 법정 보존 처리)가 기록될 때 함께 지운다. 그 뒤 같은 로그인 수단의 가입은 새 계정으로 허용된다(옛 데이터는 없다).
  - **해제 경로:** 별도 해제 command나 문의 경로는 만들지 않는다. 기한이 해제다.
  - **복원 내구성:** 기한 안의 복원은 지금처럼 tombstone 재삭제가 차단을 다시 만든다. 계정 생성 이전 generation으로 복원해 차단이 없어지는 경우(B-3)와 N-1 롤백은 수용한다 — 재활성화가 아니며, 기한 뒤 재가입 허용과 같은 결과다.
- **근거:** 차단의 목적은 삭제·백업 파기 기간 동안 같은 identity의 로그인이 빈 신규 계정을 만들거나 삭제를 흐리는 것을 막는 것이다(5.4 AC-2). 그 기간이 끝나면 목적이 사라지므로 최소 보유 원칙에 따라 기록도 지운다. 무기한 보존은 G-5 검토에서 근거를 대기 어렵다.
- **구현 메모:** `BackupDestructionJudge`가 전체 삭제 완료를 기록하는 같은 주기에서 그 `request_id`의 차단 행을 지운다(실패해도 다른 일을 막지 않는 기존 패턴). 단일 모드(리허설·복원 재삭제)처럼 `request_id`가 없는 행의 기한은 SPEC이 정한다.
- **카피 NEW(초안, "남기는 것"):** `재가입 차단 기록 — 삭제가 모두 끝날 때까지(백업 사본 파기 확인까지) 삭제한 계정의 로그인 수단으로 새로 가입하지 못하게, 그 로그인 수단을 되짚을 수 없는 암호화 값(keyed digest)만 남깁니다. 삭제가 모두 끝나면 이 기록도 지웁니다.`

### D-5. 완료 확인 세션의 실패 수렴 보호(B-2, 회고 Open question 2)

- **결정:** 추가 보호를 하지 않고 "완료 화면은 최선 노력"으로 수용한다.
- **근거:** B-2의 세 경로 가운데 (b) 크로스사이트 위조 콜백은 PR #165(`spec-auth-callback-residual-guards.md`, deferred 190)가 이미 막았다 — 인가 요청에 닿지 못한 실패는 로그인 전 세션과 완료 확인 세션을 지우지 않는다(`AuthFlowHandlers.java:1271-1278`). 남은 (a) 완료 세션에서 시작한 로그인을 사용자가 취소하는 경우와 (c) 로그인 시작이 유휴 수명을 5분으로 줄이는 경우는 모두 사용자가 스스로 시작한 동작이다. 완료 화면은 요청 ID와 지원 연락처를 보여 주므로 잃는 것은 화면 재방문뿐이다.
- **변경:** 코드 없음. 수용 사실을 chore SPEC과 done 전환 기록에 남긴다.

### D-6. 출시 증거의 기계 판독 등록(R-8, Epic 1 item-10·Epic 3 item-33·34 범위)

- **결정:** `epics.md` frontmatter에 출시 증거 이름을 추가한다. 승인 게이트(`externalLaunchGates`)와 구분해 운영·실연동 증거는 새 키 `launchEvidence`에 둔다.
- **NEW(초안):**
  ```yaml
  launchEvidence: ['epic-1-live-oidc-roundtrip', 'epic-2-bybit-live-verification', 'ops-deploy-smoke-3.8', 'ops-real-storage-backup-destruction-3.9-5.3', 'ops-isolated-restore-3.10', 'nfr-18-deletion-restore-integration-5.4', 'g-3-signal-drill', 'g-6-ip-notice-dispatch']
  ```
- **함께:** `deferred-work.md`에 회고 item-33이 지목한 운영 증거 항목(3.9 실제 저장소 연결·파기 증거, 3.8 운영 배포 smoke, 3.8 AC-2 entitlement 실제 확인, 3.10 AC-2 인프라 재구축)을 append한다.
- **효과:** Epic 1 item-10·Epic 3 item-33·item-34가 이 chore로 함께 닫힌다.

## 5. Implementation Handoff

- **범위 분류:** Moderate — 새 스토리 없이 chore 하나와 회고 액션 상태 정리.
- **인계:** Developer(`bmad-build`) — chore `spec-g5-deletion-policy-defaults.md`, 브랜치 `chore/g5-deletion-policy-defaults`.
- **chore 범위:**
  1. 정책 `ACCOUNT-DELETION-1.1`, 고지 문장의 공유 표 이전과 hash 입력 포함(D-3).
  2. 백업·복원·재가입 차단 카피(D-1·D-2·D-4).
  3. 전체 삭제 완료 시 재가입 차단 행 삭제(D-4).
  4. `DEVELOPMENT.md` 복원 절차의 RPO 구간 공지 단계(D-2).
  5. `epics.md` `launchEvidence`와 `deferred-work.md` append(D-6).
- **성공 기준:**
  - 정책 버전·hash가 두 층 시험에서 같은 공유 표로 묶이고, 고지 문장을 바꾸면 시험이 실패한다.
  - 전체 삭제 완료 뒤 같은 identity의 로그인이 새 계정을 만들고, 그 전에는 지금처럼 만들지 않는다.
  - 카피가 금지 표현 규칙(`copyRules`)을 통과한다.
  - `backend build`·프런트 품질 검사·`ci-required`가 green이다.
- **done 전환에서 함께 처리:** sprint-status의 `epic-5-retro-item-53`·`epic-1-retro-item-10`·`epic-3-retro-item-33`·`epic-3-retro-item-34`를 `done`으로, deferred 171·139(재가입 차단 키 회전 몫 재확인) 판정을 `deferred-work-resolutions.md`에 적는다.
- **변하지 않는 것:** production 삭제 실행 차단과 공개 베타 차단은 G-5 외부 승인 전까지 유지된다. 이 문서의 결정은 G-5 검토 초안이며 승인이 다른 결정을 요구하면 새 정책 버전으로 다시 바꾼다.
