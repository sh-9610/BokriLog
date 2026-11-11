---
title: 'Deferred Work 해소 원장'
status: living
created: '2026-09-02'
updated: '2026-11-11'
applies_to: '_bmad-output/implementation-artifacts/deferred-work.md'
decision_baseline: 'ERD-1.0.0'
---

# Deferred Work 해소 원장

`deferred-work.md`는 `bmad-build`가 `defer` 판정마다 항목을 **추가만** 하는 로그다. 스킬 정의(`.claude/skills/bmad-build/step-04-review.md`)가 기존 항목의 수정을 금지하므로, 해소 여부는 그 파일을 고치지 않고 이 원장에 기록한다.

항목 순서는 `deferred-work.md`의 등장 순서와 같다. 판정 기준은 `ERD-1.0.0`, PRD `2026-08-23` 판, `epics.md`(`implementationReadinessStatus: ready-for-sprint-planning`)다.

## 상태 요약

| 상태 | 건수 |
|---|---|
| 해소 | 39 |
| 부분 해소 | 25 |
| 미해소 | 120 |
| 미판정 | 0 |

**2026-11-11 갱신(lease 상실·지연 시험의 잠금 문 이전 fix).** `spec-lease-loss-test-doors.md`(PR #158, 기준 `ab6d798`)의 판정이다. done 전환 PR이 없는 fix라 이 PR이 판정한다.
- **이 fix가 해소한 항목:** 177(시간 기반 순서로 lease 상실을 만드는 백엔드 시험들)이다. 대상 시험 아홉 자리를 `PlanSyncTestBase`의 공용 페이지 저장 문(만료형·지연형)으로 옮겼고, `src/test`의 `pg_sleep`이 0건이며 `PageDoorSourceTest`가 그것을 고정한다. SPEC Verification 명령이 최종 코드로 세 번 통과했다(그 사이 183의 루프백 listen 실패 다섯 번은 아래 177 절 — 시험 본문의 실패는 없다). 해소로 판정했다.
- **이 fix가 append한 defer:** 184(운영 코드에 두 정수 advisory 키가 생기지 않게 막는 `src/main/java` 소스 스캔) 하나다. 미해소로 판정했고 주인은 없다.
- **이 fix를 결정 지점으로 지목한 항목:** 159(전체 빌드에서만 나는 계획 종단 시험 간헐 실패)와 177이다. 159는 다시 판정해 부분 해소 그대로 두었다 — `RESULT_TOO_RECENT` 16건의 후보는 JVM↔DB 시계 차이로 좁혔고 `PlanCompletionTest`의 작업 `FAILED` 한 번은 설명되지 않은 채 남는다(아래 159 절).
- **표:** 184건이다(해소 39·부분 해소 25·미해소 120·미판정 0).

**2026-11-11 갱신(시험 PostgreSQL 컨테이너 수명의 컨텍스트 분리 fix).** `spec-test-container-context-lifecycle.md`(PR #157)(기준 `df5a9c6`)의 판정이다. done 전환 PR이 없는 fix라 이 PR이 판정한다.
- **이 fix가 해소한 항목:** 없다.
- **이 fix가 append한 defer:** 183(Windows 로컬 전체 백엔드 build의 루프백 소켓 `Bad address`) 하나다. 미해소로 판정했고 주인은 없다.
- **이 fix를 결정 지점으로 지목한 항목:** 없다.
- **지나간 항목:** 159(전체 빌드에서만 나는 계획 종단 시험 간헐 실패)에 같은 "전체 빌드에서만" 계열의 다른 경로(컨텍스트 종료가 공유 컨테이너를 멈추는 경로)를 적고 그 경로를 닫았다. 그 경로는 `Connection refused`를 내므로 159의 관측을 직접 설명하지 않는다. 부분 해소 그대로이고 주인(lease 상실 시험 잠금 문 fix)도 그대로다. 177은 그 fix 몫이라 손대지 않았다.
- **표:** 183건이다(해소 38·부분 해소 25·미해소 120·미판정 0).

**2026-11-10 갱신(첫 스냅샷 상태 조회 원자 읽기 fix).** `spec-first-snapshot-state-atomic-read.md`(PR #156, 기준 `d6c6872`)의 판정이다. done 전환 PR이 없는 fix라 이 PR이 판정한다.
- **이 fix가 해소한 항목:** 181(develop push e2e `first-snapshot` 실패)이다. `SyncJobService.state`를 읽기 전용 `REPEATABLE READ` 트랜잭션 하나로 읽게 했고, 두 읽기 사이에 발행 커밋을 끼우는 결정적 시험이 옛 코드에서 실패하고 새 코드에서 통과함을 확인했다. 해소로 판정했다.
- **이 fix가 append한 defer:** 없다.
- **이 fix를 결정 지점으로 지목한 항목:** 181뿐이다.
- **표:** 182건이다(해소 38·부분 해소 25·미해소 119·미판정 0).

**2026-11-10 갱신(181 원인 확정).** 진단 artifact가 만료되기 전에 181의 trace를 조사했다. 원인은 간헐 시험이 아니라 제품 결함이었다 — `GET /api/snapshots/first`가 공식값과 작업 상태를 트랜잭션 없이 따로 읽어, 작업이 `SUCCEEDED`인데 값이 없는 응답을 낼 수 있다. 상태는 미해소 그대로(제품 fix 필요)이고, 표는 182건 그대로다.

**2026-11-10 갱신(Epic 5 회고 AI-4 CI 설치 단계 시한·비-green 실행 판정 원장 chore done 전환).** `spec-ci-flaky-run-verdicts.md`(PR #153, 머지 커밋 `a3dd2ff`)의 판정이다.
- **이 chore가 append한 defer:** 181(develop push e2e `first-snapshot` 실패)·182(백업 리허설 `catalog` 단계 정지) 둘이다. 1차 리뷰가 아니라 SPEC이 소급 기록하며 append했다(리뷰는 defer 0건). 둘 다 미해소로 판정했다.
- **이 chore가 해소한 항목:** 없다. 회고 P-2·P-3(설치 단계 시한·재실행 기록·간헐 실패 계열의 주인)은 원장 항목이 아니라 회고 action item `epic-5-retro-item-51`이 소유했고, 이 PR이 그것을 done으로 바꾼다.
- **이 chore를 결정 지점으로 지목한 항목:**
  - **159·177** — 회고 P-3이 AI-4를 주인 결정 자리로 지목했다. 주인을 다음 fix chore(가칭 `spec-lease-loss-test-doors.md`)로 정했고, 각 절의 "주인 지정" 단락에 적었다. 상태는 그대로다(159 부분 해소, 177 미해소).
  - **180** — 미해소 잔여가 `epic-5-retro-item-51`과 함께 볼 수 있다고 적었다. 이 chore는 로그 누출 판정을 바꾸지 않고 지나갔으므로 미해소 그대로다.
- **지나간 항목:** 20(통합 job의 설치 중복)은 같은 설치 단계에 시한만 두고 중복은 없애지 않았다. 미해소 그대로이고, 원장의 `환경` 행이 쌓이면 함께 다룬다(`DEVELOPMENT.md` `### 간헐 실패`).
- **표:** 182건이다(해소 37·부분 해소 25·미해소 120·미판정 0).

**2026-11-10 갱신(Epic 5 회고 AI-2·AI-3 문서·경계 정합과 삭제 경로 동일 출처 검증 chore done 전환).** `spec-epic-5-retro-docs-and-verification.md`(PR #151, 머지 커밋 `9774e4c`)의 판정이다.
- **이 chore가 append한 defer:** 180(삭제 왕복 뒤 로그의 tombstone 키 누출 판정) 한 건이다. 미해소로 판정했다.
- **이 chore가 해소한 항목:** 없다. 회고 B-5(삭제 경로가 동일 출처에서 돈 적이 없다)와 A-2(identity의 Security 의존)는 원장 항목이 아니라 회고 action item(`epic-5-retro-item-50`·`item-49`)이 소유했고, 이 PR이 둘을 done으로 바꾼다.
- **이 chore가 지나간 항목:** 27(`identity`의 `spring_session` 결합)이다. `IdentityAccountRepository`를 고쳤으므로 다시 판정했다. Security 타입 의존은 없앴지만 raw SQL 결합은 그대로라 미해소다.
- **표:** 180건이다(해소 37·부분 해소 25·미해소 118·미판정 0).

**2026-11-10 갱신(Epic 5 회고 AI-1 삭제 실행 재대기열 fix done 전환).** `spec-deletion-run-requeue.md`(PR #149, 머지 커밋 `107407d`)는 판정할 항목이 없다.
- 이 fix는 `deferred-work.md`에 아무것도 append하지 않았다(1차 리뷰와 최종 검증 모두 defer 0건).
- 해소한 항목도 없다. 재대기열 주체 부재(회고 B-1)는 원장 항목이 아니라 회고 action item `epic-5-retro-item-48`이 소유했고, 이 PR이 그것을 done으로 바꾼다.
- 이 fix를 결정 지점으로 지목한 항목도 없다.
- 인접 항목인 **172**(삭제 실행의 운영 메트릭·경보)와 **178**(전체 삭제 완료 sweep 상한·고착 관측)은 지나갔다. 이 fix는 재시도 필요 실행을 운영자 화면 목록으로 찾게 했을 뿐, 그 수를 알리는 메트릭·경보는 더하지 않았다. 그래서 둘 다 미해소 그대로다.
- 표는 179건 그대로다.

**2026-11-09 갱신(Story 5.4 삭제 완료 검증·안내 done 전환).** `spec-5-4-deletion-completion-verification.md`(PR #145, 머지 커밋 `f259096`)의 판정이다. 이 스토리로 Epic 5의 네 스토리가 모두 닫혔다.

- **append한 항목.** 둘이고, 둘 다 1차 리뷰가 append했으며 미해소다.
  - **178** — 전체 삭제 완료 판정자의 sweep 상한과 고착 실행의 관측.
  - **179** — 복원 리허설의 삭제 표본 seed가 plan·sync 계산 표를 넣지 않는다.
  - 리뷰 분류: 1차는 patch 21(P1~P11·T1~T6·D1~D4) · defer 2, 2차는 patch 17(Q1~Q17) · defer 0이었다. 두 번 다 intent_gap·bad_spec은 0이었고, reject 수는 SPEC에 남지 않았다.
- **해소한 항목.** 둘이다. 둘 다 SPEC Intent가 사용자 결정으로 이 스토리에서 닫기로 정했다. 판정은 아래 각 절에 적었다.
  - **162** — 삭제 커밋 뒤 운영자 거절·실패 행의 `actor_user_id`.
  - **170** — 삭제 진행 화면의 지원 경로.
- **결정 지점으로 지목된 항목.** **27**(`identity`의 `spring_session` 결합)은 SPEC Code Map이 다시 판정하라고 지목했다. 미해소이고, 완료 세션 처리로 결합이 넓어졌다.
- **확인만 한 항목.**
  - **171**(첫 claim 전·첫 내보내기 전 창, 주인 G-5)·**174~176**(백업 파기 판정 근거)은 5.4가 지나갔고 그대로다.
  - **139**(tombstone 키 회전)·**110**(감사 digest 키 회전)은 미해소 그대로다. 5.4의 재가입 차단 digest가 감사 digest 키에 묶여, 그 키를 회전하면 차단 기록이 대조되지 않는다(SPEC Design Notes). 회전 절차의 주인이 다뤄야 할 기록이 하나 늘었다.
  - **173**(삭제 기록의 보존 기한)은 부분 해소 그대로다. 새 V33 차단 기록의 보존도 같은 G-5 지점에 얹혔다(아래 절).
  - 열린 회고 액션 `epic-2-retro-item-24`(erase 완전성)·`epic-3-retro-item-33`·`item-34`(운영·격리 복원 증거)는 일부만 다뤘고 열려 있다. 5.4는 복원 보고에 모듈별 잔여를 싣고, 실환경 격리 시험의 기록 자리를 상태 `미수행`으로 두었다.

그래서 앞 표(177건)에서 미해소 +2 −2·해소 +2다. 합은 179다.

**2026-11-06 갱신(마지막 점검 재획득 시험 경합 fix).** `spec-plan-verification-final-sweep-race.md`의 판정이다. 이 fix는 sprint-status 스토리가 아니어서 done 전환 PR이 없으므로 이 PR 자체가 판정한다.

- **append한 항목.** 하나이고 미해소다.
  - **177** — `pg_sleep` 지연 + 진행 대기 + `expireLease`의 시간 기반 순서로 lease 상실을 만드는 시험들. 1·2차 리뷰가 append했다.
  - 리뷰 분류: 1차는 bad_spec 1(문을 연 뒤 만료가 행 잠금 대기 순서에 기댄다 — trigger 안 만료로 다시 구현) · defer 2 · reject 2, 2차는 patch 6 · defer 0 · reject 4였다. 1차가 append한 defer 하나(첫 holder `tc` 미대조)는 커밋 전에 2차 patch가 닫아 원장에 남기지 않았다.
- **해소한 항목·결정 지점으로 지목된 항목.** 없다. 같은 계열의 159(계획 종단 시험 간헐 실패)는 177이 원인 후보로 연결했고 판정은 바꾸지 않았다.

**2026-11-06 갱신(Story 5.3 외부 tombstone·백업 파기 추적 done 전환).** `spec-5-3-backup-destruction-tracking.md`(PR #143, 머지 커밋 `d578cc4`)의 판정이다.

- **append한 항목.** 셋이고, 모두 미해소다. 셋 다 V24 기록만으로는 알 수 없어 저장소 조회나 `backup.sh` 순서 변경(SPEC Ask First)이 필요한 경우다.
  - **174** — 결과 행 없이 끝난 백업 실행의 generation 판정 근거. 1차 리뷰가 append했다.
  - **175** — 백업 대기열 기록 실패와 판정의 순서 가정. 2차 리뷰가 append했다.
  - **176** — `PURGED`가 끝내 없는 영향 generation의 운영 해소 경로. 2차 리뷰가 append했다.
  - 리뷰 분류: 1차는 patch 18 · defer 1 · reject 4, 2차는 patch 14 · defer 2 · reject 5였다. 두 번 다 intent_gap·bad_spec은 0이었다.
- **부분 해소로 올린 항목.** 셋이다. 판정은 아래 각 절에 적었다.
  - **171** — 실행 전 복원에서의 요청 내구성. 첫 claim에 tombstone을 남겨 실행 중 복원을 막았다. 요청 확정과 첫 claim 사이·첫 내보내기 전의 창이 남아 주인을 G-5로 옮긴다.
  - **138** — tombstone 내보내기의 평시 관측. 성공 기록·gauge·`TombstoneExportStale` 경보를 더했다. systemd `OnFailure`와 운영 관측 연결이 남는다.
  - **173** — 실행·단계 기록의 보존 기한. 실행 기록은 생명주기 행과 운명을 같이한다고 정했다. 생명주기 행 자체의 보존 기한이 G-5 보존 검토로 남는다.
- **결정 지점으로 지목된 항목.** **139**(tombstone digest 키 회전)는 미해소 그대로다. 5.3이 키 회전을 SPEC Never로 두었고 주인이 없어졌다.
- **확인만 한 항목.** **133**(대기열 행 단위 멱등)·**132**(저장소 사용률)·**140**(manifest 대조)은 5.3이 지나갔고 그대로다 — 판정은 중복 행에 영향받지 않는다(저장소별 가장 이른 파기 기록). **162**·**170**의 주인은 그대로 5.4다. 열린 회고 액션 `epic-3-retro-item-33`(운영 증거 항목)은 일부만 다뤘고(174~176이 실저장소 파기 판정 근거를 남겼다) 열려 있다.

그래서 앞 표(173건)에서 미해소 +3 −3·부분 해소 +3이다. 합은 176이다.

**2026-11-06 갱신(Story 5.2 계정 데이터 삭제 실행 done 전환).** `spec-5-2-account-data-erasure.md`(PR #141, 머지 커밋 `f95ebb5`)의 판정이다.

- **append한 항목.** 둘이고, 둘 다 1차 리뷰가 append했으며 미해소다.
  - **172** — 삭제 실행의 운영 관측(메트릭·경보).
  - **173** — 실행·단계 기록의 보존 기한.
  - 리뷰 분류: 1차는 patch 21 · defer 2 · reject 5, 2차는 patch 22 · reject 3이었다. 두 번 다 intent_gap·bad_spec은 0이었다.
- **해소한 항목.** 없다.
- **결정 지점으로 지목된 항목.** 여섯 모두 미해소·부분 해소 그대로다. 판정은 아래 각 절에 적었다.
  - **27** — `identity`의 `spring_session` 결합. 이번에 주인이 없어졌다.
  - **56** — 소비된 허가 행의 보존 상한. 다음 결정 지점을 G-5 보존 검토로 옮긴다.
  - **67** — 삭제 참여자 목록의 수작업 나열. 단계 목록 하나와 단계별 잔여 검증으로 한 걸음 더 줄었다.
  - **162** — 삭제 커밋 뒤 운영자 거절·실패 행의 `actor_user_id`. 주인을 5.4로 옮긴다.
  - **170** — 삭제 진행 화면의 지원 경로. 주인을 5.4로 옮긴다.
  - **171** — 실행 전 복원에서의 요청 내구성. 주인을 5.3으로 옮긴다.

그래서 앞 표(171건)에서 미해소 +2다. 합은 173이다.

**2026-11-05 갱신(Story 5.1 계정 삭제 요청 done 전환).** `spec-5-1-account-deletion-request.md`(PR #139, 머지 커밋 `822816b`)의 판정이다.

- **append한 항목.** 둘이고, 둘 다 미해소다.
  - **170** — 삭제 진행 화면의 지원(문의) 경로. 1차 리뷰가 append했다.
  - **171** — 실행 전 복원에서의 삭제 요청 내구성. 2차 리뷰가 append했다.
  - 리뷰 분류: 1차는 intent_gap 1 · patch 18 · defer 1 · reject 7, 2차는 patch 15 · defer 1 · reject 11이었다.
- **해소한 항목.** 없다.
- **결정 지점으로 지목된 항목.** 셋 다 미해소·부분 해소 그대로다. 이 스토리는 요청만 열고 데이터 삭제(erase)를 사용자 경로로 끌어오지 않았으므로, 주인을 erase를 사용자 경로에 잇는 Story 5.2로 옮긴다.
  - **27** — `identity`의 `spring_session` raw DELETE 결합.
  - **56** — 소비된 허가 행의 보존 상한.
  - **67** — 삭제 참여자 목록의 수작업 나열.
  - 판정은 아래 각 절에 적었다.
- **확인만 한 항목.** **4**(삭제 범위·완료 시한)는 부분 해소 그대로다. 5.1이 G-5 전 production의 요청 준비·확정을 403 `ACCOUNT_DELETION_UNAVAILABLE`로 닫았지만, 잔여(법정 분리 보존의 대상·근거·기간)는 게이트 G-5가 소유한다.
- **162**(삭제 커밋 뒤 운영자 거절·실패 행의 `actor_user_id`)는 결정 지점이 5.2·5.4라 이번에 판정하지 않는다.

그래서 앞 표(169건)에서 미해소 +2다. 합은 171이다.

**2026-11-04 갱신(광고 시험 공용 helper·engagement 바깥 의존 규칙 chore done 전환).** `spec-ad-test-commons.md`(PR #137, 머지 커밋 `51fd410`, Epic 4 회고 AI-8 `epic-4-retro-item-47`의 ③ 시험·경계 묶음)를 판정했다.

- **append한 항목.** 없다. 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap)는 patch 7 · defer 0 · reject 10이었다.
- **해소한 항목.** **167**(광고 시험 공용 helper·engagement 바깥 의존 허용 목록). 아래 절에 적었다. 이로써 item-47의 세 묶음(①·②·③)이 모두 닫혀 sprint-status에서 그 액션을 `done`으로 바꿨다.
- **결정 지점으로 지목된 항목.** 없다. 이 chore가 지나간 열린 회고 액션 `epic-3-retro-item-39`(시험 표지 생성 도우미)·`epic-2-retro-item-24`(erase 완전성 시험)는 그대로 열려 있다.

그래서 앞 표(169건)에서 미해소 −1·해소 +1이다. 합은 169 그대로다.

**2026-11-04 갱신(프런트엔드 POST·JSON 판독 공통 부품 chore done 전환).** `spec-frontend-request-commons.md`(PR #135, 머지 커밋 `afb08f0`, Epic 4 회고 AI-8 `epic-4-retro-item-47`의 ② 프런트엔드 묶음)를 판정했다.

- **append한 항목.** 없다. 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap)는 patch 6 · defer 0 · reject 10이었다.
- **해소한 항목.** **166**(프런트엔드 동일 출처 JSON POST·JSON 판독 helper). 아래 절에 적었다.
- **결정 지점으로 지목된 항목.** 없다. **167**(③ 시험·경계 묶음)은 이 chore의 Never라 미해소 그대로이고, 주인은 여전히 item-47의 다음 chore다. 그래서 item-47은 `in-progress`로 남는다.

그래서 앞 표(169건)에서 미해소 −1·해소 +1이다. 합은 169 그대로다.

**2026-11-04 갱신(CI 시험 fork 분할 chore 첫 CI 실측).** `spec-ci-test-forks.md`(PR #133, 머지 커밋 `7fad060`)의 CI 실측 뒤 판정을 마저 적었다. 그 PR이 163을 부분 해소로 두고 "PR CI `backend-quality`가 SPEC AC 1의 범위(≤ 10분)에 들면 해소로 올린다"는 역전 규칙을 남겼다.

- **append한 항목.** 없다.
- **해소로 올린 항목.** **163**. PR #133 CI(run 41752910193)의 `backend-quality`가 14분 19초 → 10분 14초였다. 경계 10분을 14초 넘어 규칙상 Ask First 구간이었지만, 원인이 확인됐다 — private 저장소의 기본 러너가 4코어가 아니라 Gradle이 fork를 CPU 수로 상한해 executor가 2개였다. 2 vCPU에서 fork를 늘려도 이득이 없어 사용자가 이 값을 수용했고(2026-11-04) 해소로 판정했다.
- **결정 지점으로 지목된 항목.** 없다. 164(리허설의 PR 경로 분리)는 그대로 미해소이고, 이 실측에서 가장 늦게 끝난 job은 `deploy-rehearsal`(`frontend-quality` 뒤 약 7분 50초)이었다.

그래서 앞 표(169건)에서 부분 해소 −1·해소 +1이다. 합은 169 그대로다.

**2026-11-03 갱신(CI 시험 fork 분할 chore).** `spec-ci-test-forks.md`(브랜치 `chore/ci-test-forks`, PR #133, 기준 `66d4938`, PR 전에 `develop` `c213b6c`(PR #130·#132 뒤) 위로 다시 얹음 — done 전환 PR이 없는 chore라 그 PR 자체가 판정 자리다)를 판정했다.

- **append한 항목.** 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap)가 낸 defer 둘 — **168**(fork 격리 불변식을 `src/test/java` 소스 스캔 시험으로 고정)·**169**(CI에서 fork 수를 기계가 판정 — `[test-forks]` executor 줄 집계와 `org.gradle.workers.max` 부재 단정) — 를 미해소로 판정했다. 둘 다 SPEC Always(시험 코드·`ci.yml`·`.github/scripts` 불변)가 이 chore에서 막은 범위이고 아래 절에 적었다.
- **해소한 항목.** 없다.
- **부분 해소로 올린 항목.** **163**(`backend-quality` 시험의 JVM 병렬화). `build.gradle.kts`의 `Test`에 `maxParallelForks = 3`을 더해 시험 클래스를 JVM(fork) 셋에 나눴고, fork마다 PostgreSQL 컨테이너 하나(`PostgresTestcontainersConfig`의 static 계약 그대로)다. 163이 첫 질문으로 둔 "JVM 여럿에서 안전한가"는 SPEC Intent가 답했다 — scheduler·virtual worker와 `resetStore`의 NOWAIT 전체 잠금은 자기 JVM의 컨테이너 안이고, 포트는 전부 임의(`RANDOM_PORT`·루프백 0), 파일은 `@TempDir` 하나뿐이라 fork 사이 공유 자원이 없다(아래 절). 두 SPEC(`spec-backend-test-speed.md`·`spec-ci-critical-path.md`)의 병렬화 경계는 이 SPEC Intent가 JVM 단위 fork 분할에 한해 다시 열었고, JVM 안 병렬·컨테이너 공유는 여전히 금지다. 로컬 `clean build`는 19분 42초 → 11분 44초(2026-11-03, Windows 11, 시험 1,610건)다. 해소가 아니라 부분 해소인 이유 — 지금은 로컬 실측만 있다. PR CI `backend-quality` 실측이 그 SPEC AC 1의 범위(≤ 10분)에 들면 PR에서 해소로 올리고, 10분 초과~14분 19초 미만이면 Ask First(fork 수 2·4 또는 suite 배치) 뒤 결정, 14분 19초 이상이면 실패다.
- **결정 지점으로 지목된 항목.** **164**(리허설의 PR 경로 분리)·**165**(소비 job 넷의 내려받은 jar 대조)는 이 chore의 Never·범위 밖이라 미해소 그대로다. **159**(전체 빌드에서만 나는 간헐 실패)는 fork 수를 3으로 둔 근거(4 vCPU에서 코어 하나를 남긴다)에 쓰였을 뿐 판정을 바꾸지 않았다(부분 해소 그대로). 걸린 열린 회고 액션 `epic-3-retro-item-38`(SPEC [K] 선택·patch 건수 기록 규약)은 이 SPEC이 따르되 닫지 않았고, `epic-3-retro-item-37`(CI 임계 경로)은 done이며 이 chore는 그 둘째 후속이다.

그래서 앞 표(167건)에서 미해소 −1+2·부분 해소 +1이다. 합은 169다.

**2026-11-03 갱신(루트 컨트롤러 공통 부품 chore done 전환).** `spec-controller-commons.md`(PR #130, 머지 커밋 `0456622`, Epic 4 회고 AI-8 `epic-4-retro-item-47`의 ① 백엔드 묶음)를 판정했다.

- **append한 항목.** AI-8을 세 목표로 나눈 결정([S])이 SPEC 앞에서 남은 두 묶음을 더했고, 둘 다 미해소다.
  - **166:** 프런트엔드 동일 출처 JSON POST·JSON 판독 helper(② 묶음).
  - **167:** 광고 시험 공용 helper·engagement 바깥 의존 허용 목록(③ 묶음).
  - 1차 리뷰의 defer는 0건이다.
- **해소한 항목.** 없다. 이 chore가 닫은 것은 회고 액션 item-47의 ①이고, 그 액션은 ②·③이 남아 `in-progress`다.
- **결정 지점으로 지목된 항목.** 없다. 원장에서 AI-8·item-47을 가리키는 항목은 이 chore가 append한 둘뿐이고, 104(Epic 2 회고 AI-8의 프런트엔드 묶음)는 이미 해소된 다른 액션이다.

그래서 앞 표(165건)에서 미해소 +2다. 합은 167이다.

**2026-11-03 갱신(CI backend jar 생성 job chore).** `spec-ci-backend-assemble-job.md`(브랜치 `chore/ci-backend-assemble-job`, PR #129, 기준 `6b16acb` — done 전환 PR이 없는 chore라 그 PR 자체가 판정 자리다)를 판정했다.

- **append한 항목.** 2026-11-03 CI 시간 분석이 이 chore의 SPEC 앞에서 단일 목표 규칙으로 떼어 더한 둘 — **163**(`backend-quality` 시험의 JVM 병렬화)·**164**(리허설 셋을 백엔드 변경 PR의 필수 경로에서 빼는 정책) — 을 미해소로 판정했다. 둘 다 이 chore의 Never(백엔드 시험 병렬화·suite 배치, 리허설의 PR 경로 분리)이고 이 chore는 손대지 않았다. 1차 리뷰(blind-hunter)가 낸 defer **165**(소비 job 넷이 내려받은 jar를 생산자 output과 대조하지 않음)도 미해소로 판정했다.
- **해소한 항목.** 없다. 이 chore가 닫은 것은 CI 임계 경로이지 원장 항목이 아니다.
- **결정 지점으로 지목된 항목.** **20**(`same-origin-integration`의 `npm ci`·Chromium 설치 중복)을 재확인했다 — 이 chore가 건드리지 않아 미해소 그대로다(아래 절). 걸린 열린 회고 액션 `epic-3-retro-item-31`(셸 규약 검사 통일)은 새 셸 코드가 `aggregate-results.sh` 안에만 들어가므로 건드리지 않고 지나갔고, `epic-3-retro-item-37`(CI 임계 경로)은 done이며 이 chore는 그 후속이다.

그래서 앞 표(162건)에서 미해소 +3이다. 합은 165다.

**2026-11-03 갱신(계정 삭제·감사 원장 잠금 직렬화 fix done 전환).** `spec-erasure-audit-lock.md`(PR #127, 머지 커밋 `c6362a7`, Epic 3 회고 item-27)를 판정했다.

- **append한 항목.** **162**(삭제 커밋 뒤 append되는 운영자 거절·실패 행의 `actor_user_id`)를 더했고 미해소다.
- **해소한 항목.** 없다. 이 fix가 닫은 것은 회고 액션 `epic-3-retro-item-27`이고 deferred 항목이 아니다.
- **결정 지점으로 지목된 항목.** 없다. 계정 삭제·원장 비식별을 다루는 **111**(운영 command 사유의 비식별 범위)은 이 fix를 지목하지 않았고 범위도 겹치지 않아 판정을 바꾸지 않았다.

그래서 앞 표(161건)에서 미해소 +1이다. 합은 162다.

**2026-10-18 갱신(Epic 4 회고 AI-6 chore done 전환).** `spec-flaky-test-diagnostics.md`(PR #125, 머지 커밋 `4e248b8`)를 판정했다.

- **append한 항목.** 없다(리뷰 defer 0건).
- **해소·부분 해소한 항목.** 이 chore를 주인으로 지목한 셋 가운데 **158**·**159**를 부분 해소로 판정했다. **155**는 세션 속성 upsert fix(PR #124)가 이미 해소로 판정했다.
- **결정 지점으로 지목된 항목.** 위 셋 말고는 없다.

그래서 앞 표(161건)에서 미해소 −2, 부분 해소 +2다. 합은 161 그대로다.

**2026-10-17 갱신(세션 속성 upsert fix).** `spec-session-attribute-upsert.md`(PR 자체가 판정 자리 — done 전환 PR이 없는 fix)를 판정했다.

- **append한 항목.** **160**(세션 행이 먼저 지워진 뒤 속성 저장의 FK 위반)·**161**(세션 확인 500을 미인증 문구로 그림)을 더했고 둘 다 미해소다.
- **해소한 항목.** **155**(주입 origin e2e의 로그인 직후 미인증)를 해소로 판정했다 — 원인을 찾아 고쳤다.
- **결정 지점으로 지목된 항목.** 없다.

그래서 앞 표(159건)에서 해소 +1, 미해소 −1+2다. 합은 161이다.

**2026-10-17 갱신(Epic 4 회고 AI-2·AI-3 chore done 전환).** `spec-epic-4-retro-docs-and-verification.md`(PR #121, 머지 커밋 `1333c62`)를 판정했다.

- **append한 항목.** **159**(전체 빌드에서만 나는 계획 종단 시험 간헐 실패) 하나를 더했고 미해소로 판정했다.
- **해소한 항목.** 없다. 이 chore는 회고 액션 둘을 닫았지만 원장 항목을 닫지는 않았다.
- **결정 지점으로 지목된 항목.** 없다. 다만 G-4가 묶는 151·153·154·148·152를 `CLAUDE.md` 미검증 문장에 이름으로 적었다. 판정은 그대로 미해소다.

그래서 앞 표(158건)에서 미해소만 +1이다. 합은 159다.

**2026-10-17 갱신(Story 4.5 done 전환).** `spec-4-5-ad-telemetry-web-vitals.md`(PR #119, 머지 커밋 `ae06d92`)를 판정했다. 세 부류는 다음과 같다.

- **append한 항목.** 없다. 1차 리뷰 결과 defer는 0건이었다.
- **해소한 항목.** 이 스토리를 주인으로 지목한 셋을 해소로 판정했다. **156**(광고 이벤트 분리 계측)과 **157**(Web Vitals 수집과 성능 kill switch)은 이 스토리의 범위 자체였다. **122**(3.7이 미룬 Web Vitals 수집)는 157로 주인을 옮겨 둔 항목이다.
- **결정 지점으로 지목된 항목.** 위 셋 말고는 없다.

그래서 앞 표(158건)에서 해소 +3, 미해소 −3이다. 합은 158 그대로다.

**2026-10-17 갱신(Epic 4 회고 AI-1 fix done 전환).** `spec-ad-slot-lifetime.md`(PR #116, 머지 커밋 `24e8443`)는 판정할 항목이 없다. 이 fix는 `deferred-work.md`에 아무것도 append하지 않았고(리뷰 결과 defer 0건), 해소한 항목도 없으며, 이 fix를 결정 지점으로 지목한 항목도 없다. 광고 transition 수명을 다룬 **149**는 Story 4.3 done 전환이 이미 해소로 판정했다. 표는 158건 그대로다.

**2026-10-16 갱신(Story 4.4 done 전환).** `spec-4-4-ad-fail-open.md`(PR #113, 머지 커밋 `ec51014`)는 판정 둘을 남겼다.
- **append한 항목.** 셋을 더했고 모두 미해소다.
  - **156** 광고 이벤트 분리 계측 — 범위 분할로 뗐다
  - **157** Web Vitals 수집과 성능 kill switch — 범위 분할로 뗐다
  - **158** Windows 로컬 Vitest의 운영자 lazy chunk 대기 시간초과 — 기존 결함
- **결정 지점으로 지목된 항목.** **122**(Web Vitals 수집)는 4.4가 결정하지 않고 157로 넘겼다. 미해소 그대로이고 주인을 157로 옮겼다.

해소한 항목은 없다. 그래서 앞 표(155건)에서 미해소만 +3이다 — 합 158.

**2026-10-16 갱신(Story 4.3 done 전환).** `spec-4-3-neutral-ad-transition.md`(PR #111, 머지 커밋 `61aac2b`)는 판정 셋을 남겼다.
- **해소한 항목.** 이 스토리를 주인으로 지목한 **149**(transition 소비·수명)·**150**(후보 신선도)을 해소로 판정했다. 149는 1회 소비와 120초 수명으로 닫았고, 150은 술어를 바꾸지 않고 그 소비 창으로 묶는 결정으로 닫았다.
- **append한 항목.** 셋을 더했고 모두 미해소다.
  - **153** CSP 후보값 실측·SHOW 왕복 — 주인 G-4
  - **154** 광고 단위 경로 형식(MCM) — 주인 G-4
  - **155** 주입 origin e2e 새 스택 첫 실행 간헐 실패 — 주인 없음
- **재확인한 항목.** **151**은 같은 성격의 `BOKRILOG_AD_UNIT_PATH`가 더해져 재확인했다(미해소 그대로).

그래서 앞 표(152건)에서 해소 +2, 미해소 −2+3이다 — 합 155.

**2026-10-15 갱신(Story 4.2 done 전환).** `spec-4-2-ad-eligibility.md`(PR #109, 머지 커밋 `090efb9`)가 `deferred-work.md`에 항목 넷을 append했고 모두 미해소로 판정했다.
- **149** transition 소비·수명 — 주인 Story 4.3
- **150** 후보 신선도 — 주인 Story 4.3
- **151** 공급자 변수 배포 연결 — 주인 광고 출시 게이트
- **152** 결정 행 보존 기한 — 주인 G-4

이 스토리를 결정 지점으로 지목한 항목은 없다. **67**은 engagement 삭제 참여자가 다시 늘어 재확인했다(부분 해소 그대로). 해소한 항목은 없다. 그래서 앞 표(148건)에서 미해소만 +4다 — 합 152.

**2026-10-15 갱신(Story 4.1 done 전환).** `spec-4-1-ad-consent.md`(PR #107, 머지 커밋 `176190f`)가 `deferred-work.md`에 항목 **147**(광고 동의 결정 행의 빈도 제한·행 수 상한)·**148**(광고 동의 결정 이력의 보존 기한)을 append했다. 둘 다 미해소로 판정했다. 이 스토리를 결정 지점으로 이름 붙인 항목은 없다. 다만 **67** 잔여가 "Epic 4 `engagement` 같은 새 모듈"을 짚었으므로 재확인했다(부분 해소 그대로). 해소한 항목은 없다. 그래서 앞 표(146건: 해소 26·부분 해소 20·미해소 100·미판정 0)에서 미해소만 +2다 — 합 148.

**2026-10-15 갱신(CI 임계 경로 chore done 전환).** `spec-ci-critical-path.md`(PR #104, 머지 커밋 `571bb3c`)가 `deferred-work.md`에 항목 **146**(CI-PLAN §1·§5 as-built 반영)을 append했다. 표를 **146건** 기준으로 각 절의 마지막 판정 머리말로 다시 셌다. 판정 라운드가 이 chore 몫으로 남긴 보류 셋을 판정했다 — **131** 해소(백업·복원 리허설의 fail-closed 경로 분기와 job 분리, 분류·집계·timeout을 `test-ci-scripts.sh`가 지킨다), **92**·**129** 미해소(이 chore가 compose dispatch와 배포 리허설 시나리오를 건드리지 않고 지나가 주인 상실). **146**은 미해소(Epic 3 회고 AI-10 몫), **145**는 재확인(미해소 그대로). 그래서 판정 라운드 표(145건: 해소 25·부분 해소 20·미해소 97·미판정 3)에서 해소 +1·미해소 +3(92·129·146)·미판정 −3이다 — 합 146.

**2026-10-15 갱신(판정 라운드, `spec-deferred-work-verdict-round.md`).** 표를 `deferred-work.md`의 **145건** 기준으로 다시 셌다. 각 절의 **마지막** 판정 머리말을 센 값이다. 바로 아래 문단(Story 2.17 done 전환)의 수치는 틀렸다 — 운영 command 공통화 chore(`spec-operator-command-commons.md`, PR #97)가 항목 **141**(`operations_restriction.reinstated_from` 색인)·**142**(`OperatorArmingVersionTest`의 실제 v5 fixture)를 append했는데 그 chore의 갱신 문단(2026-10-13)이 그 둘을 세지 않았고, 그래서 PR #101이 2.17의 두 항목을 141·142로 적었다. 2.17 항목의 실제 번호는 **143**(정책 버전이 바뀐 재연결의 재기준)·**144**(다른 계정 연결 뒤 완료 비교 보류)다. 그 문단은 고치지 않고 이 문단이 정정한다. 정정 직전의 바른 표는 144건·미판정 91이었다.

이 라운드가 미판정 91건 가운데 **88건**을 판정했다 — 해소 7(57·72·86·87·98·100·105), 부분 해소 15(64·66·67·73·75·78·79·82·97·99·104·108·111·115·116), 미해소 66. 해소 일곱은 모두 뒤 스토리·chore가 실제로 닫은 것이고(2.12가 72·86·87, PR #71이 98, PR #73이 100·105, 1.10·2.13이 57) 각 절이 지키는 시험과 그것이 도는 CI job을 적었다. 78(PR #91)·104(PR #73)는 구현은 들어왔지만 지키는 검사가 없어(Windows 결함을 CI가 보지 못하고 가드 행동 시험이 없다 / 새 사본 금지가 규칙 문장뿐이다) "시험이 없으면 부분 해소" 기준으로 부분 해소다. 기존 판정 절 가운데 **35**에 새 판정(부분 해소 — 이 라운드가 규칙을 `CLAUDE.md`에 넣었으나 기계 검사가 없다)을, **37**에 재확인 판정(미해소 그대로)을 덧붙였다. 이 라운드의 리뷰가 낸 defer 하나를 `deferred-work.md`에 **145**(원장 건수 대조 검사)로 append하고 같은 PR에서 판정했다(미해소). 그래서 표는 기존 53건(35는 미해소 → 부분 해소)이 해소 18·부분 해소 5·미해소 30, 이 라운드의 88건이 해소 7·부분 해소 15·미해소 66, 145가 미해소 1, 보류 3이다 — 합 145. 시험은 이 라운드에서 다시 돌리지 않았다. 새 판정 머리말마다 적은 기준 커밋 `189c16b`의 코드가 근거이고, 줄 번호 인용도 그 커밋 기준이며, 그 코드는 PR #100의 `ci-required` 전 job이 성공했다.

**보류 셋(미판정).** 92(compose dispatch 간격·통합 job 예산)·129(배포 리허설의 실제 호환 창·JVM 종료 경로)·131(백업 리허설 CI 경로 분기·timeout)은 진행 중인 CI 임계 경로 chore(Epic 3 회고 AI-12, `epic-3-retro-item-37`)와 겹쳐 판정하지 않았다. 판정 주체는 그 chore의 done 전환 PR이다(`CLAUDE.md` 표준 흐름 7번).

**출시 전 닫아야 할 항목(보안·삭제·데이터 정확성).** 개발 스프린트는 막지 않지만 공개 베타·실연동·운영 배포 전에 결정이 필요한 것들이다.

- 보안 — 58(동시 발급이 살아 있는 허가 둘을 남긴다), 65(Epic 1 두 command의 확인 hash 판별력 0, Epic 1 회고 item-7), 110·139(감사·tombstone digest 키를 회전할 수 없다), 113(쓰기 가능 key 저장 신호 1번의 감지 지점 없음), 114(교차 사용자 접근 신호 2번의 감지 지점 없음), 115 잔여(일회성 역할의 비밀값 로그 감지, Epic 3 회고 AI-3), 126 중 production 비밀 공급·운영 nginx 권한 강화, 127(TLS 종단 — `Secure` 쿠키라 운영 로그인의 전제다).
- 삭제·개인정보 — 56·59·71(허가 결과·확인 행의 보존 상한 없음), 67 잔여(삭제 참여자를 손으로 나열하고 모듈 술어가 표 전부를 덮는지 검사가 없다, Epic 4 새 모듈·Epic 5 사용자 삭제 전), 111 잔여(운영 command 사유 자유 텍스트의 개인정보, G-5), 138(tombstone 내보내기 정체를 평시에 모르고, `--allow-stale-tombstones` 복원이 그 기간의 삭제 계정을 되살릴 수 있다).
- 데이터 정확성 — 66 잔여(Bybit permission·retCode 표가 비어 실제 읽기 전용 키가 거절된다, 실연동 전), 73 잔여(첫 계획 환산의 Bybit 시각에 상한이 없어 미종료 candle이 공유 증거로 저장될 수 있다), 94(현재가치 측정 시각과 `tc` 간격의 입출금이 그 버전 지표를 틀리게 한다), 95(3.8 역할 분리 뒤 API·Worker가 IP 403 회로를 공유하지 않는다, 실연동 전).
- 출시 게이트 자체 — 117·135(실제 훈련 증거 0건, Epic 3 회고 AI-9), 125(이미지 공급망이 없어 fail-closed gate가 첫 운영 배포를 막는다).
- 이 원장 밖의 차단 요인 — Epic 3 회고 AI-2(`epic-3-retro-item-27`, 계정 삭제와 원장 잠금 직렬화)·AI-4(`epic-3-retro-item-29`, 복원본의 운영 안전 상태)·AI-8(`epic-3-retro-item-33`, `deferred-work.md`에 아직 기입되지 않은 운영 증거 항목), 그리고 `CLAUDE.md`가 미검증으로 적은 운영 증거 셋(3.8 운영 배포 smoke·3.9 실저장소 백업·파기·3.10 실제 격리 복원).

**제품 코드·시험을 고쳐야 해소되는 항목.** 이 라운드는 아무것도 고치지 않았다. 아래 분류는 이 라운드가 판정한 88건 가운데 미해소·부분 해소 81건(66 + 15)만 대상으로 한다 — 원장 전체의 미해소·부분 해소는 표의 두 칸 합(117)이고, 이전 판정 35건은 이 분류에 넣지 않았다. 81건 가운데 코드나 시험 변경 없이 닫히는 것은 결정·운영 절차만 남은 몇 건(120의 제품 결정, 137의 실연동 게이트 실행, 136의 runbook)뿐이고 나머지는 변경이 필요하다. 값싸고 범위가 좁아 먼저 닫을 수 있는 것은 55(두 훅의 ref 래치)·63(닫힌 맵의 값 대응 단정)·77(409 문구 보존 순서)·89(대기를 스텁 지연보다 길게)·106(계획 종료 성공 문구의 재인증 안내)·107(정본과 겹치는 대화상자 메커니즘 단정 삭제)·108(해제 e2e에 재인증 없는 403 단계)·130(`fetch` 가드 행동 시험)·141(부분 색인 마이그레이션)이다. 묶어서 하는 편이 싼 묶음은 CSS 소유 구조(34·52·62·64·84·88·93·99·102), 계층 간 계약 리터럴 동등성(33·38·48·51·61·80·101, 3.6의 공유 fixture가 선례 — 같은 뿌리의 63은 먼저 닫을 것), 보존 상한(56·59·71), 작업 lease·heartbeat(74·85·96), 운영 관측 연결(115·121·126·132·138), 키 회전(110·126·139), 훈련 증거(117·126·135)다.

**주인 확인.** 이 라운드에서 `## 갱신 규칙`의 두 부류에 새로 드는 항목이 생겼다. (가) 소유를 조건으로 적은 것 — 85·96("이력 규모가 보이는 시점"), 120("운영 데이터가 쌓인 뒤"), 143("정책 버전을 올리는 스토리"). (나) 지목 스토리가 지나가 주인이 없어진 것 — 56(1.10), 95·110(3.8이 재차 미뤘다), 112(3.4·3.5). 117은 3.8이 재차 미뤘지만 소유가 출시 게이트(Epic 3 회고 AI-9, `epic-3-retro-item-34`)로 정해져 있어 (나)에 넣지 않는다. 나머지 미해소의 대부분도 "주인 없음"이며 각 절이 그 사실을 적는다. 판정을 다음 라운드로 넘기지 않도록 done 전환 PR이 판정을 함께 적는 규칙을 `CLAUDE.md` 표준 흐름 7번에 더했고 `## 갱신 규칙`에도 날짜를 붙여 적었다(Epic 1 회고 액션 8(`epic-1-retro-item-8`)·Epic 2 회고 AI-6(`epic-2-retro-item-18`)·Epic 3 회고 AI-11(`epic-3-retro-item-36`) — 세 액션의 상태 변경은 사용자 확인 대기다).

**2026-10-15 갱신(Story 2.17 done 전환).** `spec-2-17-bybit-reconnect.md`(PR #100)가 `deferred-work.md`에 항목 **141**(source 정책 버전이 첫 연결과 재연결 사이에 바뀐 경우의 재기준 조건 — 지금은 정책 버전이 하나라 재현 경로가 없다)·**142**(완료 계획의 첫 종료 후 관측이 고정되기 전에 해제하고 다른 계정으로 연결한 경우의 완료 비교 보류)를 append했다. 판정은 아직 없다. 그래서 표를 **142건** 기준으로 맞추고 미판정을 87→89로 늘렸다. 나머지 수치는 그대로다.

**2026-10-13 갱신(운영 command 공통화 chore, `spec-operator-command-commons.md`).** 표를 `deferred-work.md`의 **140건** 기준으로 맞췄다. 2026-10-07 뒤 Epic 3 스토리(3.1~3.10)가 append한 항목 109~140(32건)은 판정이 없어 미판정으로 센다. 이 chore가 그 가운데 **118**(운영 command 무장의 직렬화 버전이 스토리마다 오르는 패턴)과 **119**(운영자 화면 command 상태 기계 사본)을 해소했다 — 아래 두 절이 판정을 갖는다. 그래서 해소 16→18, 미판정 57+32−2=87이다. 나머지 판정 라운드는 Epic 3 회고 AI-11의 몫이다.

**2026-10-07 갱신(프런트엔드 공통 부품 chore done 전환).** `spec-frontend-dialog-commons.md`(PR #73)가 `deferred-work.md`에 항목 **106**(계획 중도 종료 성공 문구의 재인증 안내 부재)·**107**(`UnlinkDialog`·`PlanEndDialog` 시험의 메커니즘 중복)·**108**(Bybit 연결 성공 → 해제 준비 403의 주입 origin e2e 부재)을 append했다. 판정은 아직 없다. 그래서 표를 **108건** 기준으로 맞추고 미판정을 54→57로 늘렸다. 같은 PR이 항목 104(D-4 프런트엔드 묶음)와 105(재인증 게이트 안내)를 실제로 구현했지만, 두 항목의 판정(해소)은 이 원장의 판정 라운드에서 검사와 함께 적는다 — 지금은 미판정으로 둔다.

**2026-10-06 갱신(민감 command 공통 부품 chore done 전환).** `spec-sensitive-command-commons.md`(PR #71)가 `deferred-work.md`에 항목 **104**(AI-8의 프런트엔드 묶음 D-4, 백엔드와 분리해 다음 chore로 미룸)와 **105**(실행 성공 직후 다음 민감 command가 재인증 게이트에 떨어지는 화면 동작·카피 확인)를 append했다. 판정은 아직 없다. 그래서 표를 **105건** 기준으로 맞추고 미판정을 52→54로 늘렸다. 나머지 수치는 그대로다. 같은 PR이 Epic 1 회고 item-1(재인증 시각 연쇄 승인)을 닫았지만 그것은 이 원장의 항목이 아니라 `sprint-status.yaml` `action_items`의 몫이다.

**2026-10-05 갱신(Story 2.16 done 전환 뒤 재집계).** Story 2.16(PR #65)이 `deferred-work.md`에 항목 **103**(민감 command 확정이 커밋·세션 회전한 뒤 응답을 잃으면 같은 키 재시도가 회전 전 쿠키로 나가는 경로)을 append했는데 표가 102건에 머물러 있었다. 판정은 아직 없다. 그래서 표를 **103건** 기준으로 맞추고 미판정을 51→52로 늘렸다. 나머지 수치는 그대로다.

**2026-10-04 갱신(Story 2.14 done 전환).** Story 2.14(PR #60)가 `deferred-work.md`에 항목 **101~102**를 append했다. 판정은 아직 없다. 그래서 표를 **102건** 기준으로 맞추고 미판정을 49→51로 늘렸다. 나머지 수치는 그대로다.

**2026-10-04 갱신(Story 2.13 done 전환).** Story 2.13(PR #57)이 `deferred-work.md`에 항목 **98~100**을 append했다. 판정은 아직 없다. 그래서 표를 **100건** 기준으로 맞추고 미판정을 46→49로 늘렸다. 나머지 수치는 그대로다.

**2026-10-04 갱신(frontend-effect-timing 라운드).** 표를 `deferred-work.md`의 **97건** 전체로 맞췄다. 판정을 가진 것은 51건이다 — 항목 1~50과 이 라운드가 새로 판정한 83이다. 항목 **50이 해소**로 넘어갔고 **83이 해소**로 새로 들어왔다. 둘은 같은 원인(부하 아래 늦게 도는 passive effect)이었다. 그래서 해소 14→16, 미해소 32→31이다. 나머지 46건(51~82와 84~97)은 아직 판정이 없어 `미판정`으로 따로 센다. 이 라운드의 범위가 아니어서 판정하지 않았다.

**2026-09-09 갱신(Story 1.7 라운드).** 표를 **50건** 기준으로 맞췄다. 항목 **42가 해소**로 넘어갔고(세 루프 모두 완주 수 가드를 갖췄고 `verify-same-origin.sh` 526/526 exit 0으로 확인했다) 항목 **41이 부분 해소**가 됐다(판단하는 층은 생겼으나 그 등식이 44px 바닥 아래에서만 성립한다는 취약성은 그대로다). 이 라운드가 append한 여섯(45~50)은 전부 미해소다. 그래서 해소 13→14, 부분 해소 3→4, 미해소 28→32다.

**2026-09-09 갱신(Story 1.6 라운드).** 표를 **44건** 기준으로 맞췄다. 해소 13과 부분 해소 3은 그대로이고 미해소가 21에서 28로 늘었다 — 이 라운드가 `deferred-work.md`에 append한 일곱 항목(38~44) 전부가 미해소이며 아래 절이 그 판정을 갖는다. 그리고 **주인 없던 두 항목 23·27을 Story 1.6이 실제로 보았고 재차 미뤘다**(각 절의 2026-09-09 판정). 해소로 넘어간 항목은 이 라운드에 없다.

아래 문단들은 2026-09-08 라운드가 적은 것이며 그때의 37건 기준을 그대로 남긴다. 낡은 것은 수치가 아니라 시점이므로 덮어쓰지 않는다.

표는 항목별 판정을 다시 세어 2026-09-08에 **37건** 기준으로 맞췄다. 해소 13은 기존 10건(1·2·3·5·6·8·9·10·11·12)에 14·15·24가 더해진 결과이고, 부분 해소 3은 4·7·28, 미해소 21은 13·16·17·18·19·20·21·22와 23·25·26·27·29·30·31·32·33·34, 그리고 이 라운드가 append한 35·36·37이다.

**이 라운드가 세 항목을 append하고 같은 라운드에서 판정했다.** 리뷰가 낸 `defer` 셋(35 판정 기록 단계의 절차 공백, 36 인용 해석 검사, 37 소유자·확인일 표)을 `deferred-work.md`에 더했다. 판정 없이 넘기면 이 라운드가 고친 바로 그 드리프트를 스스로 다시 시작하는 것이므로 세 절을 함께 넣었다. 그래서 항목 수가 34에서 37로 늘었다.

**판정이 두 스프린트만큼 밀렸던 사실을 먼저 적는다.** Story 1.3(PR #12)·Story 1.4(PR #14)·lint-policy(PR #16)·Story 1.5(PR #17) 네 라운드가 append한 12건(항목 23~34)이 이번 라운드 전까지 아무 판정도 갖지 못했다. `deferred-work.md`에 항목을 **추가**하는 단계는 `bmad-build` step-04가 강제하지만 그 항목의 판정을 이 원장에 쓰는 단계는 어느 스킬에도 없어서, 원장은 사람이 따로 챙길 때만 갱신됐고 네 라운드 연속으로 챙기지 못했다. 그 결과 "검사가 실제로 성공한 뒤에만 해소로 적는다"는 이 원장의 규율이 원장 자신 안에서 먼저 깨졌다. `## 갱신 규칙`의 마지막 줄이 그 재발을 막는 최소 수단이다.

**소유 스토리가 지나갔는데 처리되지 않은 항목이 둘이다.** 23(`identity` 행 잠금 대기 상한)은 Story 1.4를, 27(`identity`가 `spring_session`에 직접 SQL을 던지는 결합)은 Story 1.5를 결정 지점으로 지목했지만 두 스토리 모두 `done`이 된 지금 **현재 주인이 없다**. 그 사이 27은 악화됐고(`principal_name == user_id` 규약이 세 곳에 독립 재구현되고 세션 삭제 경로가 둘로 늘었다) 23은 원문이 예고한 조건이 성립했다(OIDC 콜백이 열려 그 잠금 대기가 사용자 요청의 대기가 됐다).

**개발을 막는 항목은 여전히 없다.** 부분 해소 3건 중 4·7은 외부 승인 게이트(G-5, 출시 전 사용자 과업)에 묶여 있고 28은 Epic 2가 두 화면을 실제로 갈라 놓을 때 다시 판단하는 항목이다.

**미해소 21건 중 열셋은 주인이 없다.** 소유가 정해진 것은 여섯이다 — 13(Story 3.8 전), 16(CI-PLAN §14의 별도 검사), 17(CI-PLAN §11의 조건부 의존 갱신 PR), 18(별도 작업), 19·20(CI-PLAN §8·§10을 다시 읽는 결정). 22와 32는 소유를 붙이는 것 자체가 항목의 내용이다. 나머지 열셋 — 21·23·25·26·27·29·30·31·33·34·35·36·37 — 은 어느 스토리도 갖고 있지 않다. 그중 23·27은 소유 스토리가 지나가 주인을 잃은 경우이고(위 문단), 21·29와 30·31, 28·34, 36·37은 각각 한 쌍이라 함께 결정해야 싸다. Story 1.6이 같은 콜백·세션 개설 경로를 다시 지나므로 그 SPEC이 23·27을 함께 볼 첫 기회를 갖는다. 이 열거가 37개 절을 다 읽지 않고 주인 없는 항목을 보는 유일한 자리이며, 항목별 소유자·마지막 확인일 열을 가진 표를 둘지는 항목 37이 소유한다.

## 항목별 판정

### 1. 장기 미접속 시 Transaction Log 보존기간 밖 자금흐름 탐지·격리

**해소.** `ERD-1.0.0` §2.2가 Transaction Log의 공식 조회 가능 범위를 최대 2년으로 고정하고, 필요한 구간이 그 범위를 벗어나거나 필수 페이지를 증명하지 못하면 기존 계획 catch-up을 `복구 불가`로 종료하며 마지막 정상 결과를 보존하고 새 계획 선택을 안내한다. `epics.md`의 `G-1 부분 확정`이 30일 초과 장기 미동기화의 사용자 선택 분기를 함께 확정했다.

### 2. Product Brief 부록의 Forge 정본 상대경로 링크

**해소(2026-09-02).** `briefs/brief-BokriLog-2026-08-16/addendum.md`의 `forged-idea.md` 코드 표기를 실제 정본 위치를 가리키는 상대경로 링크 `../../../forge/read-only-crypto-goal-tracker/forged-idea.md`로 교체했다.

### 3. Day 60 고정 IP 전환 실패 시 신규 연결 중단과 Day 90 종료 조건

**해소.** `BYBIT-IP-1.0`(`ERD-1.0.0` §10)이 신규 연결의 regional reserved public IP binding 요구, 기존 미제한 연결의 Day 60 사전 공지와 Day 90 sync·재연결 정지, 키 교체 시 provider account 재사용을 확정했다. 구현 지점은 `epics.md` Story 2.1의 연결 admission이며 PRD §12.1에 해소 참조를 남겼다.

### 4. 사용자 요청에 따른 삭제 범위와 완료 시한

**부분 해소.** 삭제 **범위**는 확정됐다. `epics.md`의 `데이터 도입 시 삭제 계약·테스트 기준`이 모듈별 deletion port와 소유권·시험 기준을 고정하고, Epic 5 Story 5.1~5.4가 전체 계정 삭제·부분 실패 추적·tombstone·재활성화 차단을 담당한다. `ERD-1.0.0` §9는 개발·CI 기본값을 법정 분리 보존 `없음`으로 두고 모든 사용자 데이터를 삭제한다.

**미해소 잔여:** 법정 분리 보존의 대상 필드·법적 근거·목적·기간과 그에 따른 완료 시한. 게이트 G-5로 남아 production 삭제 실행과 공개 베타만 차단한다.

### 5. 공개 베타 활성화 완료율의 분모와 측정 기간

**해소.** PRD §10.1 `SM-2`가 분모를 `온보딩을 시작한 유효 테스트 사용자`로, 활성화 완료 시점을 `FR-4의 계획 생성 성공 시각`으로 확정했다. 측정 기간은 PRD §10의 판단 시점인 공개 베타 Day 60이다. 유효 사용자 정의는 `SM-1`이 소유한다.

### 6. 동기화 성공률의 측정 단위와 제외 규칙

**해소.** PRD §10.1 `SM-4`가 `NFR-12`의 성공 정의를 참조하고, `NFR-12`는 성공을 `필수 API 구간·페이지 검증과 원자 발행을 완료한 유효 동기화`로 규정한다. `epics.md`의 `검증 시점과 완료 증거` 절이 `Bybit 오류를 분모에서 임의 제외하지 않는다`를 명시해 제외 규칙 남용을 차단한다.

### 7. 이해도 80%를 판정할 문항·합격 기준·응답 대상·측정 시점

**부분 해소.** PRD §10.1 `SM-5`가 응답 대상을 `사용자 과업 평가 참가자`로, 판정 대상을 현재 시점 계획금액·계획 대비 차이·외부 자금흐름 조정·세 가지 신뢰 상태 4개 개념으로, 합격 기준을 80%로 확정했다.

**미해소 잔여:** 실제 문항과 과업 스크립트. PRD §11 출시 전 게이트의 `오류·검토 필요·계산 불가 상태에 대한 사용자 이해 과업을 수행한다`로 남아 있으며 개발 스프린트를 막지 않는다.

### 8. 주 2시간 운영 목표의 안정화 시작 조건과 측정 주수

**해소.** PRD §10.1 `SM-6`이 안정화 시점을 `최근 14일 동안 중대 사고가 없고 미해결된 치명적 결함과 높은 심각도의 결함이 모두 0건인 첫날`로 확정했다. `epics.md`의 `검증 시점과 완료 증거`가 실제 목표 달성은 운영 기간 측정으로만 판단하도록 분리했다.

### 9. 강한 신호별 자동 정지 발동 조건과 60초 측정 경계

**해소.** `OPS-SIGNAL-1.0`(`ERD-1.0.0` §6.1)이 신호별 권위 증거와 자동 제한 범위를 판정표로 고정하고, 강한 신호가 아닌 사건(단일 사용자 인증 만료, 일반 timeout·rate limit·5xx, 단일 가격 결측, 광고 오류)을 명시적으로 배제했다. §6.2가 `감지 후 60초 안에 durable하게 설정하고 자동 해제하지 않는다`로 측정 경계를 확정했다.

### 10. 계산 사고에서 `last_trusted_only` 표시를 허용할 조건

**해소.** `ERD-1.0.0` §6.1이 `같은 canonical input/policy에서 다른 result hash`를 deterministic replay 1건으로 강한 신호로 판정하고, 해당 calculation policy의 **표시와 publication을 함께 정지**하며 범위가 불명하면 sync·계산을 전역 정지한다. 따라서 잘못된 계산 버전이 만든 과거 값이 마지막 신뢰값으로 계속 노출되는 경로가 차단된다.

### 11. 프런트엔드 시험을 자동 실행하는 CI 워크플로

**해소(2026-09-04).** `.github/workflows/ci.yml`의 단일 `ci-required` job이 `develop`·`main`의 PR과 push에서 install, typecheck, build, Vitest, Chromium E2E를 실행하고 `CI`를 설정한다(CI-PLAN §6, `spec-ci-foundation.md`). CI-PLAN §16이 요구한 "검사가 실제로 성공한 뒤" 조건은 GitHub-hosted Ubuntu 실행 6회 green(PR #2·#4, `develop` push 3회, `workflow_dispatch` 1회)으로 충족했다. Ruleset `protect-integration-branches`가 `develop`·`main`에서 `ci-required` 통과를 병합 조건으로 강제한다(CI-PLAN §13).

### 12. 린터·포매터(ESLint·stylelint) 도입

**해소(2026-09-05).** `spec-frontend-lint-policy.md`(PR #6, `develop` 병합 커밋 e0452a5)가 CI-PLAN §6·§8이 정한 품질 정책 PR이다. `frontend/stylelint.config.js`의 `declaration-strict-value`가 `DESIGN.md` 네 범주(색·타이포그래피·모서리·간격)에서 리터럴을 금지하고 `tokens.css` 참조를 강제하며, `frontend/eslint.config.ts`의 perfectionist가 import 순서를 고정한다. `frontend/lint-policy.test.ts`의 fixture 49개가 규칙이 실제로 위반을 잡는지 고정하고, `ci.yml`의 `ci-required`가 타입 검사 뒤·빌드 앞에 ESLint·stylelint·Prettier 단계를 필수로 실행한다. CI-PLAN §16의 "검사가 실제로 성공한 뒤" 조건은 PR #6과 병합 후 `develop` push 실행 green으로, "실패가 병합을 차단한다"는 리터럴 색을 넣은 일회용 PR #7이 stylelint 단계에서 실패해 BLOCKED가 된 것으로 확인했다. 정적 접근성 lint(jsx-a11y)는 ESLint 10 peer 미지원으로 항목 17에 분리했다.

### 13. Playwright 시험 대상에 WebKit 추가 여부

**미해소.** CI-PLAN §7이 WebKit을 비필수 job으로 시작하고 §17이 10회 측정에서 안정성과 p95 예산을 충족할 때 `develop` required로 승격하도록 정했다. `main` PR의 WebKit 필수 검사는 Story 3.8의 `release-validation` 범위다(§5, §13). 결정 시점은 Story 3.8 전이며 지금은 막는 항목이 없다.

### 14. CSP를 비롯한 보안 응답 헤더

**미해소.** Story 1.2의 Nginx 구성이 소유한다. `epics.md` Story 1.2와 `ARCHITECTURE-SPINE.md`의 동일 출처 경계가 정의 지점이다. Story 1.2 착수 시 SPEC에서 결정한다.

**부분 해소(2026-09-06).** `spec-1-2-same-origin-shell.md` `## Design Notes`가 CSP를 결정하고 `deploy/nginx/bokrilog.conf`가 구현했다. `ARCHITECTURE-SPINE.md` AD-4(동일 출처 셸)의 경계 위에서 Nginx 응답 헤더가 CSP의 **유일한** 정의다. `<meta>` CSP를 두지 않는다. 문서 본문에 정책을 두면 문서를 바꿀 수 있는 경로가 곧 정책을 바꿀 수 있는 경로가 되기 때문이다.

적용한 헤더는 네 가지이며 모두 `always`가 붙어 4xx·5xx 응답에도 실린다. `server_tokens off`가 `Server` 헤더에서 버전을 지운다.

```text
Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self';
  style-src-attr 'unsafe-inline'; img-src 'self' data:; connect-src 'self';
  base-uri 'none'; form-action 'none'; frame-ancestors 'none'
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
X-Frame-Options: DENY
```

두 지시어에 근거가 필요하다. `style-src-attr 'unsafe-inline'`은 `frontend/index.html`의 noscript 폴백이 인라인 `style` 속성을 쓰기 때문이다. JS가 꺼진 브라우저에서는 `tokens.css`가 로드되지 않아 폴백 리터럴이 속성에 있어야 하고, 이 지시어를 빼면 가입 전 고지 화면이 스타일 없이 나온다(항목 18이 같은 폴백을 다룬다). `img-src 'self' data:`는 파비콘이 인라인 `data:` URI이기 때문이다. 외부 파일로 바꾸면 매 로드마다 `/favicon.ico` 요청이 생겨 Story 1.1의 "문서·번들 외 요청 0건" 계약이 깨진다.

검증 근거: `deploy/compose/verify-same-origin.sh`가 `GET /`·`/api/status`·`/api/nope`·`/nope`·백엔드 정지 후 502 다섯 응답 각각에서 네 헤더와 `Server` 버전 부재를 단정하며 2026-09-06 로컬 실행에서 64개 단정이 모두 통과했다. 헤더가 실제로 화면을 깨뜨리지 않는다는 것은 Story 1.1의 Playwright 13개(axe WCAG 검사 포함)를 Nginx origin에 대고 본문 수정 없이 돌려 확인했다.

**미해소 잔여:** CI-PLAN §16의 "검사가 실제로 성공한 뒤에만 완료로 본다" 조건 중 GitHub-hosted 실행분. 지금까지의 근거는 2026-09-06 로컬 실행뿐이고 이 구성으로 CI가 돌아간 적은 없다. Story 1.2의 PR에서 `same-origin-integration` job이 green이 되면 그 실행을 근거로 **해소** 판정을 새로 추가한다(덮어쓰지 않는다). 그 전에 해소로 적으면 원장이 검증되지 않은 상태를 검증된 상태로 보고하게 되고, 이 원장의 존재 이유가 사라진다.

**이번에 채택하지 않은 헤더와 그 소유.** 항목 14를 닫으면서 기록되지 않은 새 공백이 열리지 않도록 남긴다. 아래 네 가지는 "빠뜨린 것"이 아니라 이 스토리 범위에서 근거를 댈 수 없어 **의도적으로 넣지 않은 것**이다.

| 헤더 | 지금 넣지 않은 이유 | 결정 지점 |
|---|---|---|
| `Permissions-Policy` | 현재 화면이 geolocation·camera·microphone·payment 어느 강력 기능도 쓰지 않는다. 지금 값을 정하면 근거 없는 목록이 된다. 실제로 차단할 기능은 광고 SDK와 배포 프로필이 정해질 때 결정된다 | Story 3.8(배포 프로필). 광고 도입 시 Epic 4가 재검토 |
| `Cross-Origin-Opener-Policy`·`Cross-Origin-Resource-Policy` | `frame-ancestors 'none'`과 `default-src 'none'`이 지금의 위협(임베딩·외부 로드)을 이미 막는다. COOP `same-origin`은 제공자 왕복·팝업 흐름과 상호작용하므로 그 흐름이 없는 지금 정하면 나중에 다시 뒤집어야 한다 | Story 1.4(Google 가입·로그인) |
| HSTS(`Strict-Transport-Security`) | 개발·CI 프로필은 HTTP 전용이다. HTTP 응답의 HSTS는 브라우저가 무시하고, 로컬에 켜 두면 `127.0.0.1`을 HTTPS로 강제해 개발을 막는다. TLS 종단이 있는 곳의 헤더다 | Story 3.8(TLS 종단·배포 프로필) |
| `Cache-Control`(`index.html` 대 해시 자산 `/assets`) | Vite가 자산 파일명에 내용 해시를 넣으므로 `/assets`는 장기 immutable, `index.html`은 `no-cache`가 맞다. 지금은 Nginx 정적 제공 기본값(`Last-Modified`·`ETag` 조건부 요청)에 맡겼다. 이 구분은 CDN·프록시 캐시와 함께 정해야 의미가 있다 | Story 3.8(배포 프로필) |

네 항목 모두 Story 1.1의 "문서·번들 외 요청 0건" 계약과 현재 I/O 표의 어떤 행에도 영향을 주지 않는다. 위 결정 지점의 SPEC이 이를 다루지 않고 지나가면 그 시점에 `deferred-work.md` 항목으로 다시 올린다.

**해소(2026-09-08).** 위 **부분 해소** 문단이 남긴 잔여 조건은 GitHub-hosted 실행분 하나였고 그것이 충족됐다. Story 1.2의 PR #9 실행(`actions/runs/34035780145`, 2026-09-06 13:20~13:24 UTC)에서 `changes`·`frontend-quality`·`backend-quality`·`same-origin-integration`·`ci-required` 다섯 job이 모두 success다. 그중 `same-origin-integration`의 `동일 출처 검증` 단계가 `verify-same-origin.sh`로 네 헤더와 `Server` 버전 부재를 Nginx origin에서 단정하고, 이어지는 `Story 1.1 회귀 (Nginx origin)` 단계가 Playwright를 같은 origin에 대고 통과했다. `develop`에 들어간 머지 커밋은 `d9a1fc2`다. 이후 이 CSP를 바꾸는 변경은 이 항목이 아니라 새 항목으로 다룬다.

### 15. 진입 화면 `다음 단계` 카피를 소셜 로그인 버튼으로 교체

**미해소.** `epics.md` Story 1.4(Google 가입·로그인)가 소유한다. `frontend/src/content/entry.ts`의 "로그인 수단 선택은 아직 준비 중입니다."는 로그인 수단이 생기는 즉시 거짓이 되므로 Story 1.4 SPEC의 필수 변경으로 넣는다.

**해소(2026-09-08).** 소유 스토리가 그 카피를 실제 버튼으로 교체했다. `git diff 2f300f6^1 2f300f6 -- frontend/src/content/entry.ts`가 내는 변경은 한 줄뿐이다 — 현재 `:59`가 된 그 줄이 "로그인 수단 선택은 아직 준비 중입니다…"에서 "Google로 계속하면 본인 확인을 거쳐 계정이 만들어지고, 처음 로그인하면 온보딩 화면으로 이동합니다"로 바뀌었다. 그 파일에서 "준비 중"을 찾으면 지금 0건이다. PR #14 머지 커밋 `2f300f6`. 버튼이 실제로 왕복하는지는 `dev-oidc` 스텁 위에서 `frontend/e2e/auth-entry.spec.ts`와 `deploy/compose/verify-same-origin.sh`가 확인하며 그 PR의 `ci-required`가 green이었다.

**이 항목의 논리는 Story 1.6·1.7에서 다시 성립한다.** 항목이 붙잡은 것은 "로그인 수단이 생기는 즉시 거짓이 되는 문장"이었고, 같은 모양의 문장이 지금 `frontend/src/content/auth.ts:177`에 있다(`네이버·카카오 로그인은 아직 준비 중입니다.`). 그 파일 `:176` 주석이 "네이버·카카오는 Story 1.6·1.7이 더한다"고 스스로 적었고 `frontend/src/screens/EntryScreen.test.tsx:143`이 그 문장을 단정한다. 위 해소는 Google 축에 한정되며 네이버·카카오 축은 이 항목을 재사용하지 않고 그 두 스토리가 각자 SPEC의 필수 변경으로 다룬다.

### 16. `bmad-contract` 검사 구현

**미해소.** CI-PLAN §14가 `_bmad/**`·`.agents/skills/**`·`.claude/skills/**`·`.codex/**`·`scripts/check-bmad-env.sh`·`uv.toml` 변경에만 도는 별도 검사로 정했다. 러너에 `uv`와 관리 Python 다운로드가 필요해 frontend 필수 경로와 분리했다(`spec-ci-foundation.md`). 그때까지 이 경로만 바뀐 PR은 fail-closed 분류로 전체 frontend 검사를 돌고, BMAD 계약 자체는 어떤 게이트도 검증하지 않는다. 개발 스프린트를 막지 않는다.

### 17. `eslint-plugin-jsx-a11y` 도입

**미해소.** 2026-09-05 기준 최신판(6.10.2)의 peerDependencies가 ESLint 9까지만 선언해 항목 12의 정책 PR에서 제외했다. CI-PLAN §11이 정한 별도 의존 갱신 PR로 추가한다. 렌더 결과의 접근성은 E2E axe 검사가 담당하므로 개발 스프린트를 막지 않는다.

### 18. noscript 인라인 스타일 폴백 값의 표류 시험

**미해소.** `frontend/index.html`의 noscript 인라인 스타일은 JS 없이 `tokens.css`가 로드되지 않아 `var(--…, 폴백)` 폴백 리터럴이 필요하고, stylelint 대상(`src/**/*.css`) 밖이다. 폴백 값을 `tokens.css`와 비교하는 시험이 없어 토큰 변경을 따라가지 못해도 CI가 알지 못한다. `DEVELOPMENT.md` 예외 목록에 근거를 기록했고 시험은 별도 작업이다. 개발 스프린트를 막지 않는다.

### 19. 통합 입력 artifact 이름의 `run_attempt`와 `Re-run failed jobs`

**미해소(2026-09-06).** Story 1.2가 append했다. CI-PLAN §8이 artifact 이름에 run attempt를 넣도록 요구한 결과, `same-origin-integration`만 실패한 실행에서 `Re-run failed jobs`가 동작하지 않는다. 이미 성공한 producer job은 다시 돌지 않아 새 attempt 이름의 artifact가 없기 때문이다. 계획 문서의 요구와 복구 경로가 충돌하므로 조용한 이름 변경이 아니라 CI-PLAN §8 자체를 다시 읽는 결정이 필요하다. 그때까지의 우회는 `Re-run all jobs`이며 `DEVELOPMENT.md` `## CI`에 적었다.

### 20. `same-origin-integration`의 중복 설치

**미해소(2026-09-06).** Story 1.2가 append했다. 통합 job이 `frontend-quality`와 같은 `npm ci`·Chromium 설치를 되풀이한다. CI-PLAN §10의 시간 예산 조정 순서가 "중복 build·설치 제거"를 첫 번째로 두므로, 예산이 문제가 되는 시점의 첫 후보다. 지금은 job timeout을 16분으로 잡아 감당하고 있다.

**미해소(2026-11-03, CI backend jar 생성 job chore 판정, 기준 `6b16acb`) — 재확인.** 이 chore는 backend jar의 생산자를 `backend-assemble`로 떼어 통합 job이 시험을 기다리지 않게 했을 뿐, 통합 job 안의 `npm ci`·Chromium 설치 중복(`ci.yml`의 `의존 설치`·`Chromium headless shell 설치` 단계)은 건드리지 않았다. 통합 job이 더 일찍 시작하므로 그 2~4분이 PR 임계 경로에 남는 비중은 줄었고, job timeout은 19분이다. 미해소 그대로이고 주인 없음.

### 21. 백엔드 품질 게이트(포매터·정적 분석·아키텍처 규칙)

**미해소(2026-09-06).** Story 1.2가 append했다. 프런트엔드 네 게이트(typecheck·ESLint·stylelint·Prettier)에 대응하는 백엔드 게이트가 `gradle build` 하나뿐이다. 소유 스토리가 없다. Java 코드가 적은 지금이 도입 비용이 가장 낮고, 미루면 전체 재포맷 커밋이 생긴다. 항목 12(프런트엔드 린터)가 같은 문제를 프런트엔드에서 먼저 겪었다.

### 22. production 컨테이너 권한 강화

**미해소(2026-09-06).** Story 1.2가 append했다. `deploy/compose/compose.yaml`의 nginx 서비스에 `user`·`read_only`·`cap_drop`·`security_opt`·자원 제한이 없다. compose 파일 주석은 Story 3.8이 소유한다고 적었지만 `epics.md` Story 3.8의 AC-1~AC-5는 digest·서명·SBOM·메모리 한도·blue/green 순서만 다루고 컨테이너 권한은 다루지 않는다. 소유를 실제로 붙이는 것이 이 항목의 내용이다. 현재 구성은 개발·CI 전용이므로 개발 스프린트를 막지 않는다.

### 23. `identity` 행 잠금 대기 상한과 HTTP 요청 타임아웃

**미해소(2026-09-08).** Story 1.3이 append했다(PR #12, 머지 커밋 `89b8948`). 구조는 그대로다 — `IdentityAccountRepository.java:35-43`의 `SELECT state … FOR UPDATE`에 `NOWAIT`도 `lock_timeout`도 없고, `AccountLifecycleService.java:44-77`의 `createAccount`·`linkSocialIdentity`에 붙은 `@Transactional`에도 timeout 속성이 없다. `backend/src/main/resources/application.yml`에서 `lock_timeout`·`statement_timeout`과 Tomcat 요청 타임아웃을 찾으면 0건이다.

**원문이 예고한 조건은 이미 성립했다.** 항목은 "지금은 identity에 HTTP 표면이 없어 사용자가 그 대기를 볼 경로가 없지만, Story 1.4가 OIDC 콜백을 열면 그 대기가 곧 사용자 요청의 대기가 된다"고 적었다. 그 콜백은 열렸다 — `AuthFlowHandlers.java:339-342`의 `onSuccess`가 `AuthSessionService`를 부르고 `AuthSessionService.java:104-105`가 같은 요청 스레드에서 `createAccount`·`linkSocialIdentity`에 동기로 도달한다. 즉 이 항목은 예고 상태가 아니라 성립한 상태로 미해소다.

**미해소 잔여:** 상한 값(몇 초로 끊을지), 끊었을 때의 거부 계약, 그리고 그것을 소유할 스토리 전부. `spec-1-4-google-sign-in.md`·`spec-1-5-logout-session-expiry.md`에서 잠금 상한·요청 타임아웃을 찾으면 0건이다.

**주인이 없는 이유는 소유를 이름이 아니라 조건으로 적었기 때문이다.** 원문은 소유를 "HTTP 요청 타임아웃이 생기는 스토리와 함께 값을 결정한다"로 적었고 스토리 이름을 대지 않았다. Story 1.4는 그 조건의 **방아쇠**로만 등장한다("Story 1.4가 OIDC 콜백을 열면 그 대기가 곧 사용자 요청의 대기가 된다"). 그 방아쇠는 당겨졌는데 조건에 해당하는 스토리가 지정된 적이 없으므로, `done` 전환 시점의 확인이 걸릴 이름이 처음부터 없었다. Story 1.6이 같은 콜백 경로에 두 번째 제공자를 얹으므로 그 SPEC이 이 결정을 볼 첫 기회이며, `## 갱신 규칙`의 (가) 부류가 이 모양을 다시 놓치지 않기 위한 장치다.

**재차 미룸(2026-09-09) — Story 1.6이 보았고 미뤘다.** 위 문단이 예고한 "첫 기회"가 실제로 왔다. Story 1.6은 같은 콜백·세션 개설 경로에 두 번째 제공자를 얹으며 이 항목을 명시적으로 검토했고, `spec-1-6-naver-sign-in.md`의 `## Boundaries & Constraints`(Never: "미룬 항목 두 건을 이 스토리에서 해소하는 것")와 `## Design Notes` 마지막 절이 그 판정과 근거를 소유한다. 근거는 둘이다 — (1) 상한은 JDBC·트랜잭션 정책의 변경이고 이 스토리가 만든 변경(등록 저장소를 맵으로, `provider` 값을 흐름에서, 스텁 인스턴스를 둘로)과 폭발 반경이 겹치지 않는다, (2) 값의 근거가 이 스토리에는 없다 — 상한을 몇 초로 끊을지 정하려면 요청 타임아웃 정책이 함께 있어야 하고 그 정책은 아직 어느 스토리도 갖고 있지 않다.

구조는 2026-09-08 판정 시점과 같다. Story 1.6은 `identity`를 한 줄도 바꾸지 않았고(`git diff --stat develop -- backend/src/main/java/com/bokrilog/identity/`가 0), `IdentityAccountRepository`의 `FOR UPDATE`에 `NOWAIT`·`lock_timeout`이 없는 것도, `application.yml`에 `lock_timeout`·`statement_timeout`·Tomcat 요청 타임아웃이 없는 것도 그대로다. 달라진 것은 그 대기에 도달하는 **사용자 경로가 하나 늘었다**는 사실뿐이다 — 이제 두 제공자의 콜백이 같은 `createAccount`·`linkSocialIdentity`에 동기로 닿는다.

**미해소 잔여:** 2026-09-08 판정과 같다(상한 값, 거부 계약, 소유 스토리). 주인 없음도 그대로다. Story 1.7(카카오)이 세 번째 제공자로 같은 경로를 또 지나므로 그 SPEC이 다음 기회이며, 제공자를 하나 더 더하는 것만으로는 값의 근거가 생기지 않는다는 사실이 이 라운드가 확인한 것이다.

### 24. `(issuer, subject)` 조회 API와 계정 생성·매핑 연결의 트랜잭션 경계

**해소(2026-09-08).** Story 1.3이 append했고(PR #12) 지목한 Story 1.4가 두 절반을 모두 처리했다. 조회 API는 `AccountLifecycleApi`에 `findUserIdBySocialIdentity`로 들어왔고 구현은 `AccountLifecycleService.java:122`다. 트랜잭션 경계는 `identity` 밖에서 잡혔다 — `AuthSessionService.java:68-90`의 `establish()`가 `TransactionTemplate` 하나로 한 시도를 감싸고, `:92-127`의 `attempt()`가 그 트랜잭션 안에서 조회·계정 생성·매핑 연결·세션 판정을 차례로 수행한다. 매핑 연결이 거부되면 `:117`이 `setRollbackOnly()`를 불러 방금 만든 계정을 되돌리고, 세션 판정이 거부되면 `:124`가 같은 일을 한다. 항목이 우려한 "두 호출이 각각 자기 트랜잭션을 열어 매핑 없는 ACTIVE 계정이 영구히 남는다"가 그 두 롤백으로 닫힌다. `AuthAccountMappingTest.java:78-148`이 같은 `(issuer, subject)`로 동시에 처음 로그인하는 경합에서 계정과 매핑이 각각 하나이고 두 세션의 `principal_name`이 그 하나를 가리키는 것까지 단정한다. 검사 근거는 PR #14 머지 커밋 `2f300f6`과 그 PR의 `ci-required` green이다.

### 25. `PostgresTestcontainersConfig`의 공유 컨테이너 수명과 Spring 컨텍스트 캐시

**미해소(2026-09-08).** Story 1.3이 append했다(PR #12). 구조는 그대로다 — `backend/src/test/java/com/bokrilog/PostgresTestcontainersConfig.java:37-43`이 컨테이너를 `static` 필드로 들고 그 인스턴스를 `@Bean @ServiceConnection`으로 등록하므로, Spring이 컨텍스트를 닫을 때 그 컨테이너의 `stop()`을 부를 수 있는 경로가 남아 있다.

원문의 "지금은 컨텍스트가 넷뿐이라 축출이 일어나지 않아 드러나지 않는다"는 전제는 시험이 늘어난 뒤에도 성립한다. 시험 클래스는 18개가 됐지만 **구별되는 Spring 컨텍스트는 지금도 정확히 넷이다** — (1) `AuthFlowTestBase`(`RANDOM_PORT`, 하위 클래스 8개가 `@DynamicPropertySource`를 기반 클래스 한 곳에 두어 공유), (2) `StatusApiTest`(`RANDOM_PORT` + 중첩 `TestOnlyEndpointConfiguration`), (3) `identity`의 `AccountAccessDecisionTest`·`AccountDeletionTest`·`AccountLinkDenialTest`(`webEnvironment = NONE`, 셋이 공유), (4) `AccountConcurrencyTest`(`@TestPropertySource`로 hikari 풀 크기를 바꿔 별도). 저장소 전체에 `@DirtiesContext`가 0건이고 Spring 기본 캐시 상한(32)에 여유가 크다. 그 넷이 유지되는 것은 우연이 아니라 `AuthFlowTestBase.java:38-41`이 컨텍스트 재사용을 의도적으로 설계한 결과이며, 반대로 하위 클래스 하나에 `@TestPropertySource`나 별도 configuration이 붙는 순간 수가 늘어난다. 즉 이 항목은 아직 드러나지 않은 잠복 결함이고, 드러나지 않았다는 것이 해소의 근거는 아니므로 판정은 미해소다.

**미해소 잔여:** 컨테이너 수명을 JVM에 묶는 방식의 결정(수동 시작 + shutdown hook 또는 컨테이너 재사용 설정). 소유 스토리는 정해지지 않았다.

### 26. `verify-same-origin.sh` 마이그레이션 호출의 이식 가능한 시간 상한

**미해소(2026-09-08).** Story 1.3이 append했다(PR #12). `deploy/compose/verify-same-origin.sh:218-222`의 `compose --profile migrate build migrate`와 `compose --profile migrate run --rm migrate`에 여전히 시간 상한이 없다. 스크립트의 다른 대기는 그대로 묶여 있다 — `:239`의 `up --detach --build --wait --wait-timeout 180`, 그리고 개별 요청의 `--max-time`. Story 1.5가 이 파일을 크게 고쳤지만(로그아웃·CSRF·캐시 왕복 추가) 이 갭은 손대지 않았다.

**미해소 잔여:** bash 3.2에서 도는 이식 가능한 구현(백그라운드 실행 + 감시 루프)과 그 상한 값. 소유 스토리는 정해지지 않았다.

### 27. `identity`가 `spring_session`에 직접 SQL을 던지는 결합

**미해소(2026-09-08) — 악화.** Story 1.4가 append했다(PR #14). 결정 지점으로 지목한 Story 1.5는 이 결합을 **명시적 범위 밖으로 두고** `done`이 됐다 — `SessionGuard.java:23-26`의 javadoc이 "`identity`의 `deleteSessionsOf`는 삭제 경로 전용으로 그대로 남고, 이 스토리는 그 결합을 넓히지 않는다"고 적었다. `IdentityAccountRepository.java:195-198`의 `DELETE FROM spring_session WHERE principal_name = :principalName`은 그대로다. 결합을 넓히지 않은 것은 사실이지만 그 주변이 셋 늘었다.

- `principal_name == user_id` 규약이 세 곳에 독립 재구현됐다 — `AuthFlowHandlers.java:389-397`(세션에 쓰는 쪽), `IdentityAccountRepository.java:196-197`(raw SQL로 읽는 쪽), `SessionGuard.java:209-211`(Spring Session API로 읽는 쪽). 공유 상수도 공유 변환 함수도 없다. `SessionGuard.java:206-207`의 주석이 "두 곳이 갈라지면 사용자 단위 조작이 조용히 0건이 되므로 변환을 한 곳에 둔다"고 스스로 경고하면서 그 변환을 세 번째로 다시 적었다.
- 세션 제거 경로가 둘이 됐다 — 계정 삭제는 raw SQL(`IdentityAccountRepository.java:195-198`), 세션 폐기는 Spring Session API(`SessionGuard.java:237-259`). 규약이 갈리면 두 경로가 각각 0행이 된다.
- 경계 검사는 이 결합을 보지 못한다. `ModuleBoundaryTest`는 패키지 의존만 본다. 다만 지금의 실제 안전망은 원문이 적은 것보다 낫다 — `AuthDeletionPathTest`와 `SessionGuardTest`가 둘 다 진짜 로그인으로 세션을 만든 뒤 폐기 결과를 단정하므로, `AuthFlowHandlers`가 principal에 싣는 값이 바뀌면 두 시험이 **함께** 깨진다. 조용한 실패가 성립하는 경우는 시험 없이 네 번째 읽는 쪽이 추가될 때이며, 규약이 공유 상수가 아닌 한 그 추가를 막는 장치는 없다.

**미해소 잔여:** 세 선택지(세션 폐기를 이벤트·포트로 뒤집기 / `principal_name` 규약을 공유 상수로 승격 / 세션 소유를 통째로 루트 패키지로 이전) 중 무엇을 택할지. **결정 지점으로 지목된 Story 1.5가 이미 `done`이므로 현재 주인이 없다.** Story 1.6이 같은 세션 개설 경로에 두 번째 제공자를 얹고 Epic 5가 삭제 화면에서 이 DELETE를 사용자 경로로 끌어오므로, 늦어도 그 두 지점에서는 결정이 필요하다.

**재차 미룸(2026-09-09) — Story 1.6이 보았고 미뤘다.** 위 문단이 지목한 두 지점 중 앞의 것이 왔다. Story 1.6은 같은 세션 개설 경로에 두 번째 제공자를 얹으며 이 항목을 명시적으로 검토했고, `spec-1-6-naver-sign-in.md`의 `## Boundaries & Constraints`(Never: "`identity`의 `spring_session` 결합 확대", "미룬 항목 두 건을 이 스토리에서 해소하는 것")와 `## Design Notes` 마지막 절이 그 판정과 근거를 소유한다. 근거는 세 선택지 모두가 `identity` **내부 변경**을 요구하는 반면 이 스토리는 `identity`를 한 줄도 바꾸지 않았다는 것이다 — 여기서 손대면 "제공자를 늘린다"는 단일 목표에 관계없는 모듈 리팩터가 같은 diff에 섞인다.

결합은 넓어지지도 악화되지도 않았다. Story 1.6은 세션 개설 경로에 제공자 축을 하나 더했을 뿐이고, `principal_name == user_id` 규약을 읽는 **네 번째 자리를 만들지 않았다**(2026-09-08 판정이 세 곳으로 센 그 상태 그대로다). `IdentityAccountRepository`의 `DELETE FROM spring_session WHERE principal_name = :principalName`도, `SessionGuard`의 Spring Session API 경로도 무변경이다. 새 마이그레이션도 새 의존도 없다.

**미해소 잔여:** 2026-09-08 판정과 같다(세 선택지 중 하나를 택하는 결정). 주인 없음도 그대로다. 남은 지목 지점은 Epic 5의 삭제 화면이고, Story 1.7(카카오)이 같은 경로를 한 번 더 지난다 — 제공자를 더하는 것이 이 결정의 재료를 만들지 않는다는 사실은 이 라운드가 확인했다.

**미해소(2026-11-05, Story 5.1 판정, 기준 `822816b`) — 결합은 넓어지지 않았다.** Epic 5의 첫 스토리가 왔지만 이 DELETE를 사용자 경로로 끌어오지 않았다.
- **세션 폐기 경로.** 5.1은 삭제 요청을 접수하고 계정을 `DELETION_IN_PROGRESS`로 옮길 뿐 데이터를 지우지 않는다. 확정 뒤의 다른 세션 폐기는 1.11과 같은 `SessionGuard.revokeSessionsOfExcept`(Spring Session API 경로)다.
- **변하지 않은 것.** `IdentityAccountRepository`의 `DELETE FROM spring_session WHERE principal_name = :principalName`은 무변경이다. 5.1이 그 파일에 더한 것은 `identity_deletion_request` 조회·삽입뿐이다.
- **새로 생긴 판독 자리.** 세션 응답의 삭제 진행 판독을 위해 `ControllerSupport.storedSessionUserId`가 생겼다. 세션에 저장된 `SecurityContext`의 주체 이름을 user_id로 읽는 자리이며, 기존 `currentUserId`와 같은 helper 안에 있다 — 규약을 읽는 자리가 하나 늘었지만 공용 helper로 묶여 있다.

**미해소 잔여:** 위 세 선택지 가운데 무엇을 택할지. 다음 결정 지점은 `AccountErasure`(identity의 세션 raw DELETE를 부른다)를 사용자 삭제 경로에 잇는 Story 5.2다.

**미해소(2026-11-06, Story 5.2 판정, 기준 `f95ebb5`) — 사용자 경로로 끌려왔고 결합은 그대로다.**
- **이제 사용자 경로다.** 5.2가 erase를 사용자 경로에 이었다. Worker 단계 모드의 `IDENTITY` 단계가 `erasePayload`를 부르고, 그 안의 `DELETE FROM spring_session WHERE principal_name = :principalName`(`IdentityAccountRepository.java:263`)이 요청 세션까지 지운다.
- **결합은 넓어지지 않았다.** 세 선택지 가운데 어느 것도 택하지 않았다. 규약을 읽는 자리도 늘지 않았다.
- **안전망.** 정상 실행 시험(`AccountErasureRunTest`)이 진짜 로그인 세션을 만든 뒤 삭제 완료 뒤의 401을 단정한다. 규약이 갈라지면 이 시험이 깨진다.

**미해소 잔여:** 세 선택지 가운데 무엇을 택할지. 남은 Epic 5 스토리(5.3 tombstone·백업 파기, 5.4 복원 통합 검증)는 세션 저장 경로를 바꾸지 않는다. 그래서 지명된 결정 지점이 없어 주인이 없다(`## 갱신 규칙` (나) 부류).

**미해소(2026-11-09, Story 5.4 판정, 기준 `f259096`) — 결합이 넓어졌다.** 5.4 SPEC Code Map이 "완료 세션 처리로 결합이 늘 수 있어 다시 판정한다"고 지목했고, 실제로 늘었다.
- **새로 생긴 raw SQL.** 완료 세션 전환과 정리가 `identity` 안에서 `spring_session`·`spring_session_attributes`를 직접 다룬다. 요청 세션 찾기(`IdentityAccountRepository.java:218`), 속성 삭제(`:239`), 주체 이름 비우기(`:244`), 되살아난 주체 정리 조회(`:265-269`), 완료 연결 조회(`:298`·`:321`)다. 완료 연결 표 `identity_deletion_completion_session`은 `spring_session` 행 키에 cascade FK로 묶였다(V33).
- **규약을 읽는 자리.** `principal_name == user_id` 규약을 읽는 raw SQL이 늘었다(`:218`·`:341`·`:500`·`:615`). 남기는 속성 이름은 Spring 저장소 클래스 이름에서 만들어 사본 리터럴은 없다(`KEPT_SESSION_ATTRIBUTES`).
- **안전망.** `DeletionCompletionTest`가 진짜 로그인 세션으로 완료 전환·정리·다른 사용자 세션 보존·인가 요청 보존을 단정한다. 규약이 갈라지면 이 시험이 깨진다.

**미해소 잔여:** 세 선택지 가운데 무엇을 택할지. 결합 면적이 커져 셋째 선택지(세션 소유를 루트로 이전)의 비용이 늘었다. Epic 5가 닫혀 지명된 결정 지점이 없고 주인이 없다(`## 갱신 규칙` (나) 부류).

**미해소(2026-11-10, Epic 5 회고 AI-2 chore 판정, 기준 `9774e4c`) — Security 타입 의존만 없앴다.**
- **없앤 것.** `IdentityAccountRepository`는 완료 확인 세션에 남길 속성 이름을 만들려고 Spring Security 저장소 클래스 둘을 import했다. 이제 그 이름은 루트가 identity port `CompletionSessionAttributes`로 넘긴다(구현 `CompletionSessionAttributesPort`). `ModuleBoundaryTest`는 모든 모듈의 `org.springframework.security..` 의존을 막는다.
- **그대로인 것.** `spring_session`·`spring_session_attributes`에 직접 던지는 SQL(세션 행 삭제, 완료 세션의 속성 정리·`principal_name` 비우기, 완료 세션 연결 표의 FK)은 바뀌지 않았다. 세 선택지 가운데 어느 것도 택하지 않았다.

**미해소 잔여:** 2026-11-09 판정과 같다. 세 선택지 가운데 무엇을 택할지 정해야 하고, 주인이 없다. 이 chore는 결합을 넓히지 않았다. 남길 속성 이름의 출처를 루트로 옮겼으므로, 셋째 선택지(세션 소유를 루트로 이전)를 택할 때 옮길 것이 하나 줄었다.

### 28. `OnboardingScreen`·`SignedInScreen`을 세션 게이트 셸 하나로 합칠지

**부분 해소(2026-09-08).** Story 1.4가 append했다(PR #14). 버튼 축의 중복은 Story 1.5가 실제로 만들지 않았다 — `frontend/src/components/SignOutButton.tsx`(52줄)와 `SignOutButton.test.tsx`(239줄)를 두 화면이 함께 쓴다. 이 라운드에 새로 생길 수 있었던 중복 한 축이 생기지 않았으므로 그만큼은 해소다.

**부분 해소는 실체 축에만 걸린다 — 결정은 손대지 않았다.** 이 항목의 내용은 "합칠지 정한다"는 결정이고 그 결정은 여전히 없다. 부분 해소로 판정하는 근거는 항목 4·7의 선례와 같은 모양이다(실체의 일부가 내려앉고 잔여가 남으면 부분 해소). 결정 축만 기준으로 세면 이 항목은 미해소이고 상태 요약은 부분 해소 2·미해소 22가 된다. 결정형 항목에서 부분 해소를 언제 허용하는지는 아직 규칙에 없으며, 이 절은 두 읽기가 가능하다는 사실을 드러내 두는 쪽을 택했다.

**미해소 잔여:** 화면 축의 중복은 그대로다. 실측하면 `OnboardingScreen.tsx` 103줄, `SignedInScreen.tsx` 108줄 가운데 80줄이 동일하고(`git diff --no-index` 기준 28 추가·23 삭제), `.module.css`는 양쪽 189줄 중 최상단 주석 한 줄만 다르다(그 CSS는 항목 34가 따로 다룬다). 시험은 오히려 발산했다 — `OnboardingScreen.test.tsx` 422줄 대 `SignedInScreen.test.tsx` 362줄, `git diff --no-index` 출력 618줄(195 추가·255 삭제). 즉 통합을 미룰 근거(Epic 2가 대시보드에 지표를 넣으면 두 화면이 실제로 갈라진다)와 통합을 앞당길 근거(같은 수정을 두 번 하는 비용)가 함께 커졌고 결정은 아직 없다.

### 29. 백엔드 import 정렬 관용과 그것을 강제할 게이트

**미해소(2026-09-08).** Story 1.4가 append했다(PR #14). `backend/build.gradle.kts`에 spotless·checkstyle·pmd·archunit 플러그인이 없고(`:111`의 ArchUnit 언급은 Modulith의 전이 의존을 운영 classpath에서 빼는 주석이다), 컴파일 게이트는 `:131`의 `-Xlint:all -Werror` 하나다. 항목 21(백엔드 품질 게이트)과 한 쌍이며 둘 다 소유 스토리가 없다.

**원문 주장의 일부는 실측과 다르다.** 이번 라운드에 백엔드 java 파일 전체의 비-static import 순서를 기계로 확인한 결과 알파벳 순서를 벗어난 파일은 `AuthSecurityConfig.java` 하나뿐이었다 — `:19-20`이 `…security.core.Authentication`을 `…security.config…`보다 앞에 두고, `:28-29`가 `…oauth2.core.oidc.OidcScopes`를 `…oauth2.core.endpoint…`보다 앞에 둔다. 반면 원문이 두 번째 근거로 든 `DevOidcStubTest.java:12-13`은 `org.jspecify`가 `org.junit`보다 앞이므로 알파벳순에 **맞고**, "정렬 기준이 파일마다 다르다"는 그 예시는 성립하지 않는다. Story 1.5가 더한 파일 5개(`SessionGuard.java`·`AuthLogoutTest.java`·`AuthSessionExpiryTest.java`·`AuthSessionStateJsonTest.java`·`SessionGuardTest.java`)도 같은 검사를 통과한다. 원인 진단(게이트가 없어 사람 리뷰에만 의존한다)은 그대로 유효하다.

**미해소 잔여:** 관용(알파벳 단일 블록인지 그룹 분리인지)과 그것을 강제할 도구(Spotless `importOrder` 또는 Checkstyle `CustomImportOrder`) 결정. 항목 21과 함께 정한다.

### 30. `lint:css` 범위 단정을 집합 동등 비교로 바꿀지

**미해소(2026-09-08) — 악화.** lint-policy 라운드가 append했다(PR #16, 머지 커밋 `8f4fcaf`). `frontend/lint-policy.test.ts:679-685`가 여전히 `expect.arrayContaining`으로 `src/styles/global.css`·`src/styles/tokens.css`·`src/screens/EntryScreen.module.css` 세 개만 확인한다. 그런데 `frontend/package.json:16`의 `lint:css` glob(`src/**/*.css`)이 실제로 고르는 파일은 원문이 적은 다섯 개가 아니라 **여섯 개**다 — Story 1.5가 `src/components/SignOutButton.module.css`를 더해 아무 단정도 받지 않는 CSS가 둘에서 셋(`OnboardingScreen.module.css`·`SignedInScreen.module.css`·`SignOutButton.module.css`)으로 늘었다. 새 CSS 모듈이 glob에서 빠져도 이 시험은 통과하므로, `spec-frontend-lint-policy.md`가 적은 목적("검사 대상이 조용히 줄어드는 것을 막는다")이 지켜지지 않는다.

원문이 함께 처리해야 한다고 적은 정합 쪽은 드리프트가 없다 — `spec-frontend-lint-policy.md:69`가 못박은 시험 49개와 현재 `lint-policy.test.ts`의 시험 수가 일치한다.

**미해소 잔여:** 디스크에서 `src/**/*.css`를 독립 수집해 집합으로 비교하는 시험으로 바꾸는 일. 소유 스토리는 정해지지 않았다.

### 31. `lint-policy.test.ts`의 경로 조립 가정과 범위 단정의 부정 방향 공백

**미해소(2026-09-08).** lint-policy 라운드가 append했다(PR #16). 그 PR(`1ddeebf`)은 출력측 경로만 POSIX로 정규화했고 입력측 결합은 그대로다 — `` `${cwd}/${filePath}` `` 결합이 `frontend/lint-policy.test.ts:592`(`prettier.resolveConfig`)·`:615`(`prettier.getFileInfo`)·`:702`(`eslint.isPathIgnored`) 세 곳에 남아 있고, 같은 형태의 변형이 `:616`(`.prettierignore`)·`:660`(`package.json`) 둘 더 있다. Windows에서 `C:\…\frontend/src/…` 혼합 구분자가 되며, 지금 통과하는 것은 ESLint·Prettier의 내부 정규화 덕이다.

부정 방향 단정 셋은 전부 없다. `:658-708`의 `명령 층의 선택 범위` describe에는 시험이 둘(`lint:css` glob이 전역 CSS·토큰·CSS Module을 고른다, `eslint .`이 앱·E2E·설정 파일을 무시하지 않는다)뿐이어서 (a) `lint:css`가 `src/` 밖이나 CSS 아닌 파일을 고르지 않는다 (b) `eslint .`이 `globalIgnores`를 실제로 무시한다 (c) 두 명령의 `--max-warnings 0`이 남아 있다를 아무것도 단정하지 않는다. glob 추출 정규식 `/"([^"]+)"/`도 `:664`에 그대로다.

**미해소 잔여:** 위 다섯 결합의 경로 조립 방식과 부정 방향 단정 셋. 항목 30과 같은 파일을 고치므로 함께 처리하는 편이 싸다.

### 32. Windows 전용 결함을 CI가 볼 수 있게 할지

**미해소(2026-09-08).** lint-policy 라운드가 append했다(PR #16). `.github/workflows/ci.yml`의 다섯 job(`:59` `changes`·`:164` `frontend-quality`·`:261` `backend-quality`·`:332` `same-origin-integration`·`:424` `ci-required`)이 모두 `ubuntu-24.04`에서 돈다(`runs-on`은 `:63`·`:168`·`:265`·`:336`·`:431`). matrix leg를 더하면 `.github/scripts/test-ci-scripts.sh`의 고정 단정 다섯 곳을 함께 고쳐야 한다 — `:825-827`(job 목록과 순서), `:835-836`(`runs-on: ubuntu-24.04` 다섯 줄), `:838-841`(job별 `runs-on` 확인 루프), `:1121-1124`·`:1125-1126`(artifact 이름의 생산·소비 대칭).

**근거로 인용할 CI-PLAN 절이 없다.** `CI-PLAN.md`에는 OS matrix를 다루는 절이 존재하지 않는다(§1~§18 어디에도 없다). Windows·macOS runner를 언급하는 곳은 두 군데인데 §7(브라우저 실행 정책)의 `:253`("Firefox나 Windows·macOS runner는 지원 브라우저 정책, 실제 결함 또는 사용자 비율이 필요성을 보일 때 추가한다")과 §17(재검토 조건)의 WebKit 승격 항목(`:472`, "Firefox 또는 OS matrix를 검토한다")이며 둘 다 Playwright 브라우저 매트릭스 맥락이다. 이 항목이 필요로 하는 "어느 job을 어느 OS에서 도는가"의 근거로는 쓸 수 없으므로 없는 절 번호를 지목하지 않고 절이 없다는 사실을 그대로 적는다.

**미해소 잔여:** 결정 자체(전체 matrix인지 프런트엔드 단위 시험만 Windows에서 한 번 더 도는 축소안인지)와 그 비용을 소유할 스토리. 결정 지점이 배정되지 않았고 배정하는 것 자체가 이 항목의 내용이다.

### 33. 계층을 넘나드는 계약 리터럴의 중복을 Docker 없는 검사로 묶을지

**미해소(2026-09-08) — 규모 확대.** Story 1.5가 append했다(PR #17, 머지 커밋 `5b6ae01`). 실측이 원문의 수치보다 크다.

- 자산 `Cache-Control`(`public, max-age=31536000, immutable`)은 원문대로 세 곳이다 — `deploy/nginx/bokrilog.conf:76`, `deploy/compose/verify-same-origin.sh:458`, `frontend/e2e/auth-entry.spec.ts:585`.
- `X-CSRF-TOKEN`은 세 곳이 아니라 **일곱 곳**이다. 정의는 둘(`AuthSecurityConfig.java:102`의 `CSRF_HEADER_NAME`, `frontend/src/content/auth.ts:43`의 `CSRF_HEADER`)이고, `verify-same-origin.sh`가 공유 변수 없이 리터럴을 다섯 번 다시 적는다(`:963`·`:1014`·`:1027`·`:1042`·`:1060`).
- 오류 `code` 문자열은 백엔드가 여섯 종을 정의하고(`ApiErrorHandler.java:47`·`:49`·`:51`·`:63`·`:66`·`:68`) nginx가 그중 넷을 리터럴로 다시 적는다(`bokrilog.conf:284`·`:290`·`:296`·`:302`). `ApiErrorHandler.java:43-45`가 그 중복을 주석으로 경고한다.
- 원문이 언급하지 않은 동종 중복이 둘 더 있다 — `/api/auth` 경로 문자열과 세션 쿠키 이름 `BOKRILOG_SESSION`이다. 계수는 측정 범위에 따라 달라지므로 범위를 함께 적는다: `backend/src`·`frontend/src`·`frontend/e2e`·`deploy`·`.github` 기준으로 `/api/auth`가 19개 파일에 90회대, `BOKRILOG_SESSION`이 20회대이며 저장소 전체(문서 포함)로 넓히면 둘 다 더 늘어난다. 정확한 수치가 필요하면 이 항목이 요구하는 계수 검사가 그것을 소유해야 한다 — 지금은 사람이 세는 값이라 이 문단 자체가 드리프트 대상이다.
- 유휴 30분은 단일 정의다. `application.yml:111`(`timeout: 30m`)뿐이고 코드가 그 숫자를 다시 적지 않는다(`ofMinutes(30)`·`1800` 모두 0건).
- **절대 12시간은 두 곳이다.** `application.yml:162`(`absolute-lifetime: 12h`)와 `SessionGuard.java:59`의 `DEFAULT_ABSOLUTE_LIFETIME = Duration.ofHours(12)`. 후자는 기본값이면서 동시에 상한이다 — `:83-87`이 구성값이 없거나 12시간을 넘으면 이 상수로 되돌린다. 그래서 `SessionGuard.java:55` 주석이 "값의 정의는 `application.yml`의 `bokrilog.session.absolute-lifetime` 하나다"라고 적었지만 실제로는 그 파일이 상한을 함께 소유하고, yml을 12시간보다 길게 바꾸면 조용히 무시된다. 이 축도 항목의 범위에 든다.
- 반면 `AuthFlowHandlers.java:116`의 `FLOW_SESSION_TIMEOUT_SECONDS = 300`은 인증 전 흐름 세션의 별개 값이며 위 두 수명의 중복이 아니다(정의가 그 한 곳뿐이다).

Docker 없는 정적 리터럴 비교 시험은 이 저장소에 전례가 없다. `frontend/lint-policy.test.ts`는 린터를 실제로 실행해 판정하는 다른 성격이고, 오류 본문의 두 계층 비교(`verify-same-origin.sh:622`·`:628`)도 런타임 응답을 맞대는 방식이다.

**미해소 잔여:** 결정 자체(저장소 파일을 읽어 동등성을 단정하는 Docker 없는 시험인지, 계약 값을 생성 산출물로 만드는 것인지)와 그 범위(위 다섯 축 중 어디까지 묶을지). 소유 스토리는 정해지지 않았다.

### 34. 두 인증 화면의 공통 레이아웃 CSS가 또 갈라져 복사됐다

**미해소(2026-09-08).** Story 1.5가 append했다(PR #17). `frontend/src/screens/OnboardingScreen.module.css`와 `SignedInScreen.module.css`는 각각 189줄이고 `diff`가 내는 차이는 최상단 주석 한 줄(`온보딩 화면 레이아웃` 대 `로그인 상태 화면 레이아웃`)뿐이다. 이 라운드가 더한 `.header` 규칙은 양쪽 `:65-75`에 바이트 동일하게 들어 있다(원문은 이를 "5속성"으로 적었으나 실제 선언은 아홉 개다 — 규모만 다르고 복제 사실은 같다). 로그아웃 버튼 자체는 `SignOutButton.tsx`로 공유했지만 그 버튼을 앉힐 헤더 레이아웃은 컴포넌트가 소유하지 않아 두 벌이 됐다.

**미해소 잔여:** 항목 28의 셸 추출 결정. 이 항목은 그 결정의 독립 근거이며 스스로 닫히지 않는다 — 28이 통합을 택하면 함께 닫히고, 미루기를 택하면 다음 라운드가 같은 복제를 한 번 더 늘린다.

### 35. 판정을 원장에 쓰는 단계를 스킬·표준 흐름에 넣을지

**미해소(2026-09-08).** 이 라운드가 append했다. 12건 누락을 고치면서 그 누락을 만든 구조는 그대로라는 사실이 드러났고, 처방으로 넣은 것은 위 `## 갱신 규칙`의 줄들뿐이다. `bmad-build` step-04는 `deferred-work.md`에 항목을 추가하도록 강제하지만 판정을 이 원장에 쓰는 단계는 어느 스킬에도 없다. 즉 강제 수단 없는 규칙이며, 이번 라운드가 진단한 실패의 원인은 문서가 아니라 절차에 있다.

**미해소 잔여:** `CLAUDE.md`가 스킬 파일 손수정을 금지하고 동작 변경을 `_bmad/custom/`·`bmad-customize`에 맡겼으므로, 그 경로로 present 단계에 판정 확인을 배선할지 아니면 `CLAUDE.md`의 표준 흐름 5·7에 문장으로만 둘지를 정한다. 소유 스토리는 없다.

**부분 해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** 위 잔여의 두 선택지 가운데 둘째를 택했다. `spec-deferred-work-verdict-round.md`가 `CLAUDE.md` 표준 흐름 7번(done 전환)에 판정 규칙을 넣었다 — 스토리·chore·fix의 done 전환 PR(done 전환 PR이 없는 작업이면 그 작업의 PR 자체)이 그 작업이 append한 defer, 해소한 defer, 그 작업을 결정 지점으로 지목한 defer의 판정을 원장(항목별 절·상태 표·건수)에 함께 적는다. 아래 `## 갱신 규칙`의 2026-10-15 줄이 그 PR을 판정 시점의 정본으로 정한다. `bmad-build` 스킬 단계 자체는 바꾸지 않았다(SPEC Ask First). Epic 1 회고 액션 8(`epic-1-retro-item-8`)·Epic 2 회고 AI-6(`epic-2-retro-item-18`)·Epic 3 회고 AI-11(`epic-3-retro-item-36`)이 이 규칙을 요구했고, 세 액션의 상태 변경은 사용자 확인 대기다.

**미해소 잔여:** 규칙은 여전히 산문이라 강제 수단이 없다. 건수 어긋남(141·142를 세지 않은 2026-10-13 갱신이 그 실례다)을 기계가 잡는 검사는 항목 145가 소유하고, `_bmad/custom/`로 present 단계에 배선하는 길은 열린 채다. 주인 없음.

### 36. 원장 인용의 해석 가능성을 확인하는 Docker 없는 검사

**미해소(2026-09-08).** 이 라운드가 append했다. 원장은 판정마다 검증 가능한 근거를 지목하는 것을 규율로 삼는데 그 근거를 보는 검사가 없다. 이번 라운드가 그 공백을 실측했다 — 첫 구현이 항목 15에서 Story 1.4가 건드리지 않은 줄을 "바뀌었다"고 적었고, SPEC이 정한 검증 명령 다섯 개를 모두 통과했다. 잡아낸 것은 리뷰 서브에이전트가 인용 32건을 훑은 결과이며 저장소 쪽 장치는 없다.

**미해소 잔여:** 기계로 닫히는 세 축(인용 경로의 존재와 줄 범위, 커밋·run id의 해석, 원장 절 집합과 `deferred-work.md` 항목의 개수·순서 동등)을 `.github/scripts/`의 셸 검사로 둘지. 문서-only 경로도 `changes` job을 지나므로 붙일 자리는 있다. 항목 33의 계약 리터럴 계수 검사와 같은 성격이라 함께 설계하는 편이 싸다. 소유 스토리는 없다.

### 37. 항목별 소유자·마지막 확인일 표를 원장에 둘지

**미해소(2026-09-08).** 이 라운드가 append했다. 판정이 두 스프린트 밀린 직접 원인은 주인 없는 항목이 보이지 않는 것이었는데, 이 라운드는 그 가시성을 `## 상태 요약`의 산문 한 문단으로만 고쳤다. 그 문단은 손으로 유지되는 열거라 다음 라운드에 다시 낡고, frontmatter에는 `updated:` 하나뿐이어서 항목별 `last_checked`도 다음 확인 시점도 없다.

**미해소 잔여:** 번호·제목·판정·결정 지점·마지막 확인일 열을 가진 표를 둘지, 그 표와 절이 어긋날 새 드리프트 축을 감수할지의 교환. 항목 36의 검사가 그 정합을 기계로 잡아 줄 수 있으므로 함께 정한다. 소유 스토리는 없다.

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`) — 재확인.** 원장 frontmatter는 여전히 `updated:` 하나이고 항목별 `last_checked`·결정 지점 열을 가진 표는 없다. 2026-10-15 판정 라운드도 주인 없는 항목의 가시성을 맨 위 갱신 문단의 "주인 확인" 산문과 아래 `## 갱신 규칙`의 (가)·(나) 목록 갱신으로만 고쳤다 — 이 항목이 예고한 "손으로 유지되는 열거"가 한 번 더 늘었다. 각 절의 머리말에 판정 날짜와 기준 커밋을 적기 시작한 것이 항목별 마지막 확인 시점의 부분 대체이지만 표로 모이지 않는다.

**미해소 잔여:** 2026-09-08 판정과 같다(표를 둘지, 그 정합 검사를 항목 36·145와 함께 설계할지). 주인 없음.

### 38. 계층·언어를 넘나드는 제공자 이름 목록의 복제와 그 동등성 검사

**미해소(2026-09-09).** Story 1.6이 append했다. 같은 제공자 집합이 열 곳 넘게 나뉘어 있다 — `AuthSecurityConfig.SUPPORTED_REGISTRATION_IDS`, `DevOidcStubConfig.PROVIDERS`, `OidcStubConfiguration`의 인라인 목록 둘, `DevOidcStubProfileTest.PROVIDERS`, `StatusApiTest`의 인라인 배열, `verify-same-origin.sh`의 heredoc 셋과 `case` 분기와 하드코딩된 기대값 `'2'` 셋, `frontend/src/content/auth.ts`의 `SIGN_IN_PROVIDERS`다. Java 안의 다섯 곳은 상수 하나로 접을 수 있지만 bash·TypeScript 쪽은 언어가 달라 접히지 않으므로 처방은 통합이 아니라 계층 간 동등성 검사다.

**항목 33과 같은 성격이다.** 33이 "계층을 넘나드는 계약 리터럴의 중복을 Docker 없는 검사로 묶는 문제"이고 이것은 그 리터럴 집합에 제공자 이름을 더한 것이다. 함께 설계하는 편이 싸다. 이 라운드가 그 검사의 값을 실측했다는 사실이 새 재료다 — 이 스토리가 실제로 겪은 결함이 "목록에서 빠진 leg이 붉게 나오지 않고 사라진다"는 형태였다.

**미해소 잔여:** 검사의 형태와 범위(항목 33과 같은 결정). Story 1.7(카카오)이 이 전부를 손으로 고쳐야 하므로 그 SPEC이 다음 기회다. 소유 스토리는 정해지지 않았다.

### 39. 두 스텁의 서명 키·issuer 격리를 지나는 부정 경로 단정

**미해소(2026-09-09).** Story 1.6이 append했다. `DevOidcStubConfig`·`OidcStubConfiguration`·`verify-same-origin.sh`의 주석이 모두 "한쪽 스텁이 서명한 ID Token은 다른 쪽의 JWKS로 검증되지 않는다"를 계약으로 적지만, 그 부정 경로를 실제로 지나는 시험은 한 층도 없다. 지금 단정은 "두 issuer 문자열이 다르다"는 비교로 대체돼 있다(`AuthNaverSignInTest`).

**현재 시험 층에서는 그 케이스를 만들 수조차 없다.** `AuthFlowTestBase.issueCode(Authorization, TokenPlan)`가 흐름의 제공자로만 code를 발급하도록 구조로 막아 두었고, 이 리뷰 라운드가 `callback`·`callbackWithError`에서도 같은 결합을 세웠다(제공자를 인자로 다시 받지 않는다). 그 결합은 시험이 계약을 우회하는 것을 막는 장치이므로 되돌리지 않는다 — 확인하려면 `DevOidcStub`을 직접 불러 다른 스텁의 서명 토큰을 다른 등록의 콜백에 제시하는 **낮은 층** 단정 하나가 필요하다.

**미해소 잔여:** 그 낮은 층 단정을 둘지, 그리고 둘 자리(스텁 단위 시험인 `DevOidcStubTest`인지 인증 흐름 시험인지). 이 라운드가 더한 issuer 겹침 기동 거부(`ProviderClientRegistrations.rejectSharedIssuer`)는 **구성 오류**를 막는 것이고 이 항목은 **서명 격리**를 확인하는 것이라 서로 대체되지 않는다. 소유 스토리는 정해지지 않았다.

### 40. 한 제공자만 구성된 스택을 HTTP·화면 층에서 지나는 검사

**미해소(2026-09-09).** Story 1.6이 append했다. 제공자별 fail-closed 판정을 보는 시험 둘(`AuthNaverSignInTest`·`DevOidcStubProfileTest`)은 `ProviderClientRegistrations.from()`을 직접 불러 맵 내용만 본다. `StatusApiTest`는 **어느 제공자도 구성되지 않아** `oauth2Login` DSL 자체가 배선되지 않은 다른 상태를 보고, compose·Playwright는 `dev-oidc`의 `@Primary` 등록이 언제나 두 제공자를 함께 만들므로 부분 구성 스택이 존재하지 않는다. 그래서 "필터는 배선됐고 그 등록만 없다"는 상태가 어느 층에도 없다.

그 상태에서 사용자가 보는 것이 문제다 — 화면은 제공자 버튼을 조건 없이 그리고(`SIGN_IN_PROVIDERS`), 세션 확인 응답에는 어떤 제공자가 구성됐는지 알 방법이 없고(필드 셋뿐), 인증 시작은 최상위 내비게이션이라 사용자는 `{"code":"INTERNAL_ERROR",…}` 본문을 브라우저에서 그대로 본다. 지금은 개발·CI가 두 제공자를 모두 스텁으로 구성하고 production 배포가 없어(Story 3.8) 도달하지 않지만, 실제 제공자 등록이 하나씩 들어오는 시점에 그 창이 열린다.

**미해소 잔여:** 처방이 자명하지 않은 것이 이 항목의 내용이다. 구성 여부를 세션 확인 응답에 싣는 것은 Story 1.4가 "소비자 없는 필드를 만들지 않는다"로 거부한 방향이고, 화면이 그것을 알면 "어떤 제공자가 살아 있는가"가 응답에 실린다. 결정은 실제 제공자 등록을 들이는 스토리(Epic 1의 **실연동 미검증** 게이트)와 production 배포(Story 3.8)가 함께 볼 지점이다. 소유 스토리는 정해지지 않았다.

### 41. 두 제공자 버튼의 콘텐츠 크기 차이가 "동등한 시각 면적"으로 읽히는지 보는 층

**미해소(2026-09-09).** Story 1.6이 append했다. 대비 하한을 만족시키려 네이버 레이블을 20px·700, 로고를 24px로 둔 결과 Google 버튼(14px·600, 로고 18px)과 콘텐츠 크기가 갈렸다. 유일한 guard는 `auth-entry.spec.ts`의 두 버튼 폭·높이 동일 단정이고, 그 등식은 두 버튼의 콘텐츠 높이가 **둘 다** `--component-social-sign-in-min-target-size`(44px) 아래에 머무는 동안만 성립한다. 어느 한쪽이 그 값을 넘으면 "시각 면적" 회귀가 아니라 무관해 보이는 픽셀 불일치로 나타난다.

네이버 가이드의 "타사 버튼과 함께 쓸 때 크기를 줄이지 말라"는 만족한다. 보이지 않는 것은 반대 방향이다 — 한 제공자가 강조되어 보이는 것을 `DESIGN.md`가 금지하는데 그것을 판단하는 층이 없다. 이 리뷰 라운드가 로고를 공식 애셋 경로로 바꿔 잉크가 24px 박스를 가득 채우게 되었으므로 두 버튼의 로고 잉크 크기 차이는 오히려 더 뚜렷해졌고, 그 사실은 이 항목을 닫지 않는다.

**부분 해소(2026-09-09, Story 1.7).** 세 번째 버튼이 들어오면서 판단하는 층이 생겼다 — `e2e/auth-entry.spec.ts`가 세 버튼의 바깥 크기 동일, 공표 색·모서리, 그리고 목록 자식 수와 버튼 안쪽 구성(배지 형제·강조 자식·구분선이 모두 걸린다)을 이름으로 단정한다. 첫 시도는 `data-recommended` 속성을 읽어 **언제나 통과했고** 리뷰가 그것을 잡아 마크업 모양을 재는 형태로 바꿨다.

**미해소 잔여:** 이 원장이 처음 지적한 취약성 자체는 남았다 — 바깥 크기 등식은 세 버튼의 콘텐츠가 **모두** `--component-social-sign-in-min-target-size` 아래에 머무는 동안만 성립하고, 어느 하나가 그 값을 넘으면 "한 제공자가 강조되어 보인다"가 아니라 무관해 보이는 픽셀 불일치로 나타난다. 콘텐츠 높이나 잉크 면적을 직접 재는 축은 여전히 없다. 소유 스토리는 정해지지 않았다.

### 42. `verify-same-origin.sh`의 나머지 두 제공자 루프에 완주 수 가드가 없다

**해소(2026-09-09, Story 1.7).** 세 루프가 모두 완주 수 가드를 갖췄고, 가드가 세는 기대값은 스크립트 안의 제공자 목록 하나에서 파생된다(하드코딩된 `3` 셋이 사라졌다). `deploy/compose/verify-same-origin.sh`를 실제로 돌려 526개 단정 전부 통과·exit 0으로 확인했다. 아래는 Story 1.6이 이 항목을 열 때 적은 근거다 — 이 라운드가 왕복 루프에만 `왕복 루프가 제공자 수만큼 돌았다`를 넣었다. 콜백 탐침(`CALLBACK_PROVIDERS`)과 업스트림 오류(`UPSTREAM_ERROR_PROVIDERS`) 루프는 `docker compose exec`를 부르지 않아 이번 stdin 결함에는 걸리지 않았지만, 한 목록에서 이름이 빠지면 그 제공자를 조용히 건너뛴다는 형태는 같다. "없는 단정은 붉게 나오지 않는다"가 이 스크립트에서 두 번 더 성립할 수 있다.

**남은 교환:** 루프 완주 수를 세는 관용을 함수 하나로 묶을지. 항목 38·43과 같은 파일의 같은 부류(스크립트가 조용히 절반만 도는 형태)이므로 함께 보는 편이 싸다. 이 리뷰 라운드는 대신 `round_trip()` 진입에서 결과 전역을 비우는 겹을 하나 더했다 — 그것은 leg이 사라졌을 때 **뒤의 단정이 앞 leg의 값을 물려받는 것**을 막는 것이고, 이 항목의 "leg이 사라진 것 자체를 세는 자리"와는 다른 축이다. 소유 스토리는 정해지지 않았다.

### 43. `compose()` 한 곳에 stdin 차단을 두어 `docker compose exec`의 stdin 삼킴을 원천 차단할지

**미해소(2026-09-09).** Story 1.6이 append했다. 이 라운드는 원인을 `psql_value`·`psql_exec` 두 함수에서 닫았다. 그것이 실제 결함 경로를 없애지만, 앞으로 누가 루프 안에서 다른 `compose exec`를 부르면 같은 결함이 다시 난다. `compose()` 한 곳에 `</dev/null`을 두면 클래스가 원천 차단되는데 그 함수는 `up`·`run --rm migrate`·`logs`도 태우므로 그 호출들이 stdin을 쓰지 않는다는 확인이 함께 필요하다.

**미해소 잔여:** 그 확인과 변경. 검증이 통과하는 상태에서 넓히는 변경이라 이 스토리에서 하지 않았고, 리뷰 라운드에서도 같은 판정을 유지한다 — 이 라운드의 patch 열한 건은 모두 결함이나 단정 공백을 닫는 것이고 이것은 예방적 확장이다. 소유 스토리는 정해지지 않았다.

### 44. `application.yml`이 제공자 엔드포인트 재정의 네 이름을 선언하지 않는다

**미해소(2026-09-09).** Story 1.6이 append했다. 이 저장소의 규약은 "이름은 `application.yml`이 정하고 값은 환경이 준다"인데 `issuer-uri`·`authorization-uri`·`token-uri`·`jwk-set-uri` 네 이름은 어느 제공자에도 선언돼 있지 않다. 시험(`OidcStubConfiguration`)이 `DynamicPropertyRegistry`로 실제로 그 이름을 주므로 이름은 존재하고 Boot의 relaxed binding으로 동작하지만, 개발·CI가 무엇을 덮는지가 코드에서만 발견된다.

이 스토리의 SPEC Execution 항목은 네이버에 그 넷을 더하라고 적었으나 Google이 선언하지 않은 상태에서 한쪽만 더하면 비대칭이 생긴다. 그래서 두 제공자를 함께 정하는 것으로 미뤘고, 그 판정은 리뷰 라운드에서도 그대로다 — 카카오가 들어오면 세 제공자 × 네 이름이 되므로 한 번에 정하는 편이 싸다.

**미해소 잔여:** 네 이름을 세 제공자 모두에 선언할지(그러면 `application.yml`이 열두 줄 늘고 값은 전부 비어 있다), 아니면 규약의 예외로 두고 그 사실을 주석으로 남길지. Story 1.7이 다음 기회다. 소유 스토리는 정해지지 않았다.

## 갱신 규칙

- `deferred-work.md`에 새 항목이 append되면 이 원장에 같은 순서로 판정을 추가한다.
- 판정을 바꿀 때는 기존 문단을 덮어쓰지 않고 날짜와 근거를 붙여 새 판정을 추가한다.
- 근거는 판정의 종류에 맞게 지목한다. **계획 결정**이 닫는 항목은 권위 문서(PRD, `ARCHITECTURE-SPINE.md`, `EPIC-IMPLEMENTATION-READINESS.md`, `epics.md`)의 절 번호로, **구현·검사**가 닫는 항목은 `파일:줄번호`·PR 번호·머지 커밋·CI job 결과로 지목한다. 어느 쪽이든 읽는 사람이 열어서 확인할 수 있어야 한다. (2026-09-08 개정: 이 줄은 원래 권위 문서 절 번호만 허용했으나 항목 11·12가 이미 `ci.yml`·SPEC·PR 번호를 근거로 썼고 14·15·24도 그렇다. 낡은 것은 판정이 아니라 규칙 문장이었다.)
- 인용한 근거가 그 사실을 실제로 뒷받침하는지 열어서 확인한 뒤에 적는다. 줄이 존재한다는 것만으로는 부족하다 — 항목 15의 첫 해소 판정이 Story 1.4가 건드리지 않은 줄을 "바뀌었다"고 적었고 그것은 개수·형태 검사 전부를 통과했다.
- 스토리가 `done`으로 넘어갈 때, 그 스토리를 결정 지점으로 지목한 항목의 판정을 함께 확인한다. 판정이 없으면 그 스토리의 present 단계에서 추가하고, 결정하지 못한 채 지나갔으면 미해소로 적으면서 주인이 없어진 사실을 남긴다. 항목 23·27이 이 확인 없이 소유 스토리가 지나가 버린 사례이며, 두 스프린트가 지난 뒤 이 원장을 다시 맞출 때야 드러났다.
- 위 확인은 소유가 스토리 이름으로 적힌 항목만 잡는다. 그래서 두 부류를 스프린트 경계에서 따로 재확인한다 — (가) 소유를 조건으로 적은 항목(항목 23의 "HTTP 요청 타임아웃이 생기는 스토리"처럼 이름이 없어 `done` 방아쇠가 걸리지 않는다), (나) 소유 스토리가 지나가 주인이 없어진 항목. 현재 (가)·(나)에 드는 것은 23·27이며, 주인 없는 항목 전체 목록은 `## 상태 요약`이 소유한다.
- `defer` 판정으로 `deferred-work.md`에 새 항목을 append한 라운드는 **그 라운드 안에서** 새 항목의 판정도 함께 적는다. 다음 라운드로 넘기면 이 원장이 고치려는 드리프트를 스스로 시작하는 것이다. 항목 35·36·37이 그 규칙을 적용한 첫 사례다.
- (2026-10-15 추가) 판정 시점의 정본은 `CLAUDE.md` 표준 흐름 7번이다 — 스토리·chore·fix의 done 전환 PR(done 전환 PR이 없는 작업이면 그 작업의 PR 자체)이 그 작업이 append한 defer, 해소한 defer, 그 작업을 결정 지점으로 지목한 defer의 판정을 함께 적는다(지나갔으면 미해소·주인 상실로라도). 위 줄의 "그 스토리의 present 단계에서"와 "그 라운드 안에서"는 그 PR 안에서 지키는 것으로 읽는다.
- (2026-10-15 추가) 위 (가)·(나) 재확인 목록을 갱신한다. 지금 (가)(소유를 조건으로 적은 것)에 드는 것은 23·85·96·120·143, (나)(지목 스토리가 지나가 주인이 없어진 것)에 드는 것은 27·56·95·110·112다. 117은 3.8이 재차 미뤘지만 소유가 출시 게이트(Epic 3 회고 AI-9, `epic-3-retro-item-34`)로 정해져 있어 넣지 않는다. 근거는 각 절과 상태 요약의 2026-10-15 판정 라운드 갱신 문단이다.

## 항목별 판정(계속)

### 45. 제공자별 왕복 시험이 제공자 비종속 축을 통째로 복제한다

**미해소(2026-09-09).** Story 1.7이 append했다. `AuthKakaoSignInTest`가 인가 시작 불변식·신규·기존·취소·제공자 오류·토큰 장애·이미 인증된 세션을 `AuthNaverSignInTest`에서 그대로 되풀이해 셋째 사본이 됐다. 이 축들은 제공자로 파라미터화된 코드(`AuthFlowHandlers`·`AuthSessionService`)를 지나므로, 같은 SPEC이 `AuthTokenValidationTest`의 검증 실패 18축을 "제공자 비종속이라 복제하지 않는다"로 거절한 것과 성질이 같다.

**미해소 잔여:** 파라미터화한 공통 시험 하나와 제공자 고유 구성만 보는 작은 시험으로 가를지, 아니면 제공자마다 읽기 쉬운 사본을 유지하는 값을 인정할지. 넷째 제공자는 없으므로 압력은 당장 오지 않는다 — 판단은 제공자를 늘리는 스토리가 아니라 시험 구조를 소유하는 라운드가 해야 한다. 소유 스토리는 정해지지 않았다.

### 46. `credentials()` fixture와 그 근거 주석이 시험 세 파일에 복제됐다

**미해소(2026-09-09).** Story 1.7이 append했다. `AuthNaverSignInTest`·`DevOidcStubProfileTest`·`AuthKakaoSignInTest`가 같은 본문과 같은 "자격정보 모양 리터럴을 시험 fixture에도 두지 않는다" 주석을 각자 든다.

**미해소 잔여:** `AuthFlowTestBase`로 올릴지 작은 시험 헬퍼를 새로 둘지. 전자는 이 스토리가 무변경으로 둔 파일이라 손대지 않았다. 항목 45와 같은 뿌리(시험 구조)이므로 함께 보는 편이 싸다. 소유 스토리는 정해지지 않았다.

### 47. 스텁 기동 guard가 제공자마다 손으로 쓴 OR 사슬이라 하나를 빠뜨리면 열린 채 통과한다

**미해소(2026-09-09).** Story 1.7이 append했다. `DevOidcStubConfig`의 guard는 `hasCredentials(google) || hasCredentials(naver) || hasCredentials(kakao)`이고 `PROVIDERS`와도 `AuthProperties`의 성분과도 컴파일 시점 연결이 없다. 이것이 스텁과 실제 제공자가 함께 뜨는 것을 막는 두 층 중 하나이므로, 넷째 성분을 record에 더하고 이 줄을 잊으면 실제 자격정보가 스텁 옆에서 살아 있게 된다.

**미해소 잔여:** `AuthProperties`에 `providers()` 접근자를 두어 `from()`과 이 guard가 함께 순회하면 그 실패 모양이 사라진다. 다만 그 변경은 `from()`의 구조를 바꾸므로 검증이 통과한 상태에서 넓히지 않았다. 항목 48과 같은 처방을 공유한다. 소유 스토리는 정해지지 않았다.

### 48. 같은 계층 안에도 제공자 목록 사본이 넷이고 `SUPPORTED_REGISTRATION_IDS` 등가 단정이 없다

**미해소(2026-09-09).** Story 1.7이 append했다. `DevOidcStubProfileTest.PROVIDERS`·`OidcStubConfiguration.PROVIDERS`·`AuthProviderIndependenceTest.PROVIDERS`·`StatusApiTest`의 인라인 배열이 모두 같은 패키지의 `DevOidcStubConfig.PROVIDERS`를 참조할 수 있다. 특히 `SUPPORTED_REGISTRATION_IDS`(404와 500을 가르는 집합)와 `from()`이 채우는 맵의 키 집합이 어긋나면 아는 제공자가 조용히 404가 되는데, 그 둘을 묶는 단정이 없다.

**미해소 잔여:** 이것은 항목 33·38이 다루는 **계층 간** 등가성이 아니라 계층 **안** 중복이라 처방이 다르고 훨씬 싸다(`containsExactlyInAnyOrderElementsOf` 한 줄과 상수 참조 넷). 33·38이 열려 있는 동안 이 값싼 절반만 먼저 닫을지가 판단할 지점이다. 소유 스토리는 정해지지 않았다.

### 49. 카카오 심볼이 래스터 추출인데 그 추출을 재현할 산출물이 저장소에 없다

**미해소(2026-09-09).** Story 1.7이 append했다. 카카오는 네이버와 달리 버튼 벡터 애셋을 공표하지 않고 가이드와 리소스 도구가 PNG·PSD만 낸다. 그래서 공식 래스터에서 심볼을 추출해 타원과 직선으로 복원했고 근거(RMS 0.11px·최대 0.42px, 52.9px 기준)를 SPEC의 Change Log와 코드 주석에 적었지만, 추출 스크립트도 원본 해시도 적합도 데이터도 커밋되지 않았다. 자동 검사는 종횡비와 색 둘뿐이다.

**미해소 잔여:** 가이드의 첫 금지가 "심볼의 형태·비율·색 변경"이므로 가이드가 갱신될 때 그 주장을 다시 검증할 방법이 필요하다. 추출 산출물을 저장소에 두는 길과 실연동 게이트의 항목으로 올리는 길이 있고, 전자는 저장소에 이미지 처리 도구 의존을 들이는 대가가 있다. 소유 스토리는 정해지지 않았다.

### 50. `OnboardingScreen.test.tsx`의 목적지 이동 단정이 부하 아래 간헐적으로 깨진다

**미해소(2026-09-09).** Story 1.7 라운드의 검증 중 백엔드 Gradle 빌드와 프런트엔드 Vitest를 동시에 돌렸을 때 `OnboardingScreen.test.tsx:162`의 `replaceLocation('/dashboard')`가 호출 0건으로 실패했고, 부하 없는 재실행에서는 157/157이 통과했다. 이 파일은 Story 1.7이 논리를 바꾸지 않았으므로 `develop`에 이미 있던 취약성이며 CI 러너가 붐빌 때 같은 방식으로 붉게 나올 수 있다.

**미해소 잔여:** 대기 조건을 명시적으로 바꾸는 것(`findBy*`의 타임아웃이 아니라 이동이 일어나는 사건 자체를 기다리는 형태)이 처방이지만, 재현이 부하에 의존해 고친 뒤 확인하기가 어렵다. 비결정적 실패는 재실행으로 덮이면서 신뢰를 갉아먹으므로 방치할 항목은 아니다. 소유 스토리는 정해지지 않았다.

**해소(2026-10-04, frontend-effect-timing).** 원인은 대기 조건이 아니라 이동의 시점이었다. `replaceLocation`이 `useSessionGate`의 passive effect에 있었고, 세션 응답이 만든 갱신의 passive effect는 Scheduler의 다음 작업으로 미뤄져 부하가 걸리면 `findByText(redirecting)` 직후의 동기 단정보다 늦게 돌았다. 두 이동(서버가 고른 목적지, 만료 표지)을 layout effect로 옮겨 "이동 중"을 그린 커밋에서 떠나게 했다(`frontend/src/session/useSessionState.ts:498`·`:506`). 원문의 처방대로 단정은 이동 자체를 기다린다(`frontend/src/screens/OnboardingScreen.test.tsx:416-418`, 같은 모양의 `frontend/src/screens/SignedInScreen.test.tsx:1368-1370`). 기대값은 바뀌지 않았다.

확인은 두 겹이다.

- **결정적 가드.** `frontend/src/screens/SignedInScreen.test.tsx:1849`의 첫 관측 가드가 "이동 중" 문장이 처음 보이는 순간 `replaceLocation`이 이미 불렸는지를 부하 없이 단정한다(`frontend/src/test/firstSight.ts`). 이 effect 하나를 `useEffect`로 되돌리면 그 파일 10회 중 10회 이 가드가 실패했고, 복원하면 10회 중 10회 통과했다. 이 가드는 CI `frontend-quality`가 PR마다 돌리는 Vitest에 들어 있으므로 effect가 passive로 돌아가면 부하와 무관하게 붉게 나온다.
- **로컬 부하 반복.** `frontend/scripts/stress-test.sh`(`yes` 16개)로 리뷰를 반영한 최종 트리의 전체 Vitest(871건)를 36회 돌려 모두 통과했다. 리뷰 반영 전 구현 트리(866건)도 12회·24회 모두 통과했다. 같은 스크립트·같은 부하에서 수정 전 트리(`7245371`)는 12회 중 1회 실패했다(`SignedInScreen` AC-13의 초점 단정, 같은 원인의 다른 효과). `_bmad-output/implementation-artifacts/spec-frontend-effect-timing.md` `## 측정`의 기준선은 12회 중 2회 실패였다. 가드 파일만 3회 돈 부하 반복도 통과했다.

### 51. `dev_subject` 파라미터 이름이 Java 상수·셸·e2e에 각각 리터럴로 있고 등가 검사가 없다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`) — 규모 확대.** Story 1.8이 append했다. 정의는 여전히 `backend/src/main/java/com/bokrilog/DevOidcStub.java:75`의 `SUBJECT_OVERRIDE_PARAMETER = "dev_subject"` 하나인데, 그 리터럴을 손으로 다시 적는 자리가 원문의 두 곳에서 늘었다 — `deploy/compose/verify-same-origin.sh`가 아홉 곳(`:1101`·`:1391`·`:1633`·`:1741`·`:1799`·`:1836`·`:2008`·`:2729`·`:2766`)에서 `&dev_subject=`를 조립하고, 주입 origin Playwright가 다섯 곳(`frontend/e2e/auth-entry.spec.ts:114`·`bybit-connection.spec.ts:70`·`operator.spec.ts:46`·`support/firstPlan.ts:28`·`support-inquiry.spec.ts:37`)에서 `searchParams.set('dev_subject', …)`를 쓴다. 세 계층을 대조하는 검사는 `.github/scripts/test-ci-scripts.sh`에도 백엔드·프런트엔드 시험에도 없다. 항목 33·38과 같은 뿌리이고 61·63·80·101과 한 묶음이다.

**미해소 잔여:** 등가 검사의 형태(Docker 없는 정적 대조인지 생성 산출물인지)와 범위. 항목 33의 결정이 함께 닫는다. 주인 없음.

### 52. `ReauthPanel.module.css`가 진입 화면의 제공자 버튼 규칙 셋을 복제한다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 1.8이 append했다. `.googleButton`·`.naverButton`·`.kakaoButton` 세 규칙이 같은 토큰으로 `frontend/src/components/ReauthPanel.module.css:76-135`와 `frontend/src/screens/EntryScreen.module.css:177-276`에 두 벌 그대로 있고, 항목 62의 세 번째 사본(`SettingsScreen.module.css:330-`)도 남아 있다. "세 버튼의 바깥 크기가 서로 같다"를 재는 e2e는 여전히 진입 화면 하나다(`frontend/e2e/auth-entry.spec.ts:146`). PR #73(`spec-frontend-dialog-commons.md`)은 대화상자 CSS만 `ConfirmationDialog.module.css` 하나로 모았고 제공자 버튼은 건드리지 않았다.

**미해소 잔여:** 제공자 버튼을 소유할 공유 부품(또는 공유 CSS 모듈)을 정하는 CSS 소유 구조 결정. 항목 34·62·64·84·88·93·99·102와 같은 묶음이다. 주인 없음.

### 53. 아는 이름이지만 구성되지 않은 제공자로 재인증을 무장하면 최상위 내비게이션이 원본 오류 JSON에 닿는다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 1.8이 append했다. 재인증 시작은 지금도 제공자 이름만 본다 — `AuthSessionController.java:316-324`의 `startReauthentication`은 `isSupportedProvider`만 확인하고 등록 구성 여부를 조회하지 않으며(javadoc이 그 결정을 적는다), 이어지는 인가 시작이 `:281-289`의 `authorizationUnavailable`에서 500을 낸다. 그 요청은 최상위 내비게이션이라 사용자가 공통 오류 JSON 본문을 그대로 본다. 반면 연결 시작은 `LinkService.java:162`의 `isConfiguredProvider`로 미구성 제공자를 시작 단계에서 닫으므로(`AuthLinkProviderTest`의 `구성되지_않은_제공자의_연결_시작은_거부된다`) 두 시작의 처리가 갈라져 있다.

**미해소 잔여:** 화면 층에서 부분 구성 스택을 다루는 일은 항목 40(미해소)의 범위이고, 재인증 시작도 연결 시작처럼 구성 여부로 닫을지는 그 결정과 함께 본다. 실제 제공자 등록이 하나씩 들어오는 Epic 1 실연동 게이트 전에 정해야 한다. 주인 없음.

### 54. 절대 수명 12시간의 끝에 성공한 재인증이 몇 분 뒤 만료로 끝난다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 1.8이 append했다. `SessionGuard.java:137-142`는 절대 수명 마감을 세션 `CREATION_TIME`에서만 계산하고 회전은 그 값을 바꾸지 않는다. 재인증 시작(`AuthSessionController.java:316-`)은 남은 절대 수명을 보지 않고 무장하며, 남은 수명이 무장 수명보다 짧으면 거절하는 판정도 없다. 1.8 뒤에 수명 계약을 다시 연 SPEC은 없다(`spec-sensitive-command-commons.md`도 재인증 시각 소비만 다뤘다). 보안 경계는 지켜지고 문제는 사용성이다.

**미해소 잔여:** Story 1.5가 소유한 수명 계약을 다시 열어 재인증 시작의 남은 수명 판정을 둘지. 주인 없음.

### 55. `useSignOut`·`useReauthentication`의 중복 제출 가드가 React 상태를 읽어 같은 tick의 두 번째 호출을 막지 못한다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 1.8이 append했다. `frontend/src/session/useSignOut.ts:33-38`과 `frontend/src/session/useReauthentication.ts:30-35`이 각각 `const [pending, setPending] = useState(false)` 뒤에 `if (pending) return`을 두고, `useRef` 래치는 어느 쪽에도 없다. 실제 브라우저에서는 discrete event마다 flush되어 창이 사실상 닫혀 있지만, 프로그래밍 방식 연속 dispatch에서는 두 번째 재인증 무장이 첫 번째를 덮을 수 있다.

**미해소 잔여:** 두 훅에 같은 ref 래치를 함께 다는 변경(원문대로 한쪽만 고쳐 관용을 가르지 않는다). 주인 없음.

### 56. 소비된 허가 행에 보존 상한이 없어 민감 작업마다 `result` 문자열이 무기한 쌓인다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 1.9가 append했다. 기회적 청소 `ActionGrantRepository.java:248-253`(`deleteExpiredUnconsumedOf`)은 여전히 미소비·만료 행만 지우고, 그 javadoc(`:240-244`)이 소비된 행의 정리를 계정 삭제의 `ON DELETE CASCADE`에 맡긴다. 상한을 정하라고 지목된 Story 1.10은 재시도 정책도 보존 상한도 정하지 않고 `done`이 됐고(`spec-1-10-link-sign-in-method.md`에 해당 언급 0건), 그 뒤 결과가 쌓이는 발급 자리가 넷 늘었다(`BybitConnectionService`·`BybitRevocationCommandService`·`PlanEndCommandService`·`OperatorCommandService`). Story 3.10이 `RestoreInvariants`에서 이 표를 삭제 잔여 검사에 넣었지만 그것은 삭제된 계정의 잔여를 보는 것이지 보존 상한이 아니다. 항목 59·71과 같은 뿌리다.

**미해소 잔여:** 보존 기간 값(재전송이 도착할 수 있는 최대 시간)과 sweep 술어 확대. 지목된 1.10이 지나가 주인 없음(`## 갱신 규칙` (나) 부류). 개인정보 보존 정책(Epic 5)과 함께 볼 지점이다.

**미해소(2026-11-05, Story 5.1 판정, 기준 `822816b`).** Epic 5의 첫 스토리가 지나갔고 보존 기간 값을 정하지 않았다.
- **늘어난 것.** 5.1이 결과를 남기는 발급 자리를 하나 더했다 — `delete-account` command(`AccountDeletionRequestService`).
- **줄어든 것.** 삭제가 요청된 계정은 새 허가를 받지 못한다 — 쓰기 접근 거부, 요청 세션 하나만 상태 전용으로 남는다. 그래서 그 계정의 행은 erase의 `ON DELETE CASCADE`를 기다린다.

**미해소 잔여:** 위와 같다. 개인정보 보존 정책을 실제 삭제·보존 기한과 함께 정하는 Story 5.2·5.3이 다음 결정 지점이다.

**미해소(2026-11-06, Story 5.2 판정, 기준 `f95ebb5`).** 5.2는 보존 기간을 정하지 않았다.
- **삭제되는 계정은 문제가 아니다.** 소비된 허가 행은 `IDENTITY` 단계의 `identity_account` 삭제와 함께 `ON DELETE CASCADE`로 사라진다. 잔여 검증은 `action_grant`도 센다(`RestoreInvariants`).
- **남은 문제.** 삭제하지 않는 계정의 무기한 누적은 그대로다.

**미해소 잔여:** 보존 기간 값과 sweep 술어 확대. 개인정보 보존 정책 값을 정하는 G-5(법정 분리 보존 승인) 검토가 다음 결정 지점이다. Epic 5의 남은 스토리는 이 값을 정하지 않는다.

### 57. 허가 포트가 `MockHttpSession`으로만 구동되고 실제 Spring Session JDBC 세션으로 발급→소비 왕복을 지나는 층이 없다

**해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 1.9가 append했고, 지목된 Story 1.10과 그 뒤 민감 command들이 HTTP 표면을 만들면서 그 층이 생겼다. 근거는 **현재 요청의 세션 id로 소비하는** 실경로다 — 연결 흐름은 OIDC 콜백이 세션을 회전시키므로 발급 때 기록한 id를 그대로 제시해 소비하고(`ActionGrantService.java:543`, `LinkService.java:215`) 실제 id와의 어긋남을 볼 수 없어 근거로 쓰지 않는다. 계획 종료는 준비 요청에서 그 요청의 실제 Spring Session JDBC 세션 id(`SessionFacts.of`의 `session.getId()`)로 발급하고, 별도 HTTP 요청인 확정에서 무장의 세션 id가 지금 세션과 같은지 보고(`PlanEndCommandService.java:156`, `armed.sessionId().equals(session.getId())`) 제시값 없이 지금 요청의 세션 id로 소비한다 — 소비 술어가 `session_id = :sessionId`를 포함하므로(`ActionGrantRepository.java:165`) `HttpSession#getId()`가 요청 사이에서 귀속 값과 다르면 소비가 실패한다. `PlanEndTest.anEarlyEndPreparesConfirmsAndLeavesAReadOnlyRecord`(`:59`)가 준비 뒤 미소비(`:82`)와 확정 뒤 소비(`:113`)를 단정한다. Bybit 해제(`BybitRevocationCommandService.java:202`)와 운영 command(`OperatorCommandService.java:296`)도 같은 모양이다. 서로 다른 두 실제 세션 사이의 귀속은 `AuthUnlinkProviderTest.java:598`의 `오래된_탭의_취소는_다른_세션의_허가를_지우지_않는다`가 지난다. 모두 `backend-quality`에서 돈다. 발급 행의 `session_id`를 `spring_session.session_id`와 직접 비교하는 단정은 없지만, 위 소비가 그 동일성을 관측한다.

### 58. 같은 `(user, command, target)`으로 동시에 들어온 두 발급이 살아 있는 허가를 둘 남길 수 있다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 1.9가 append했다. 발급은 지금도 `ActionGrantService.java:260-280`의 트랜잭션 하나에서 `invalidateLiveGrants`(DELETE) 뒤 `issueIfReauthenticationFresh`(INSERT)를 차례로 실행할 뿐 행 잠금도 advisory lock도 없고, 유일 색인은 `(user_id, operation_id)` 하나다(`V3__action_grant.sql:104-105`). 두 READ COMMITTED 트랜잭션이 동시에 지나면 각자 삽입한다. `spec-sensitive-command-commons.md`는 `issue()`의 `(u,c,t)` 무효화를 "같은 작업의 살아 있는 허가는 하나" 규칙으로 유지한다고 적었지만 그 동시성은 다루지 않았고, `ActionGrantConcurrencyTest`는 소비·멱등 경합만 본다. 소비는 허가 단위로 한 번뿐이라 민감 작업이 두 번 실행되지는 않는다.

**미해소 잔여:** 발급 직렬화 — 생명주기 행 잠금 또는 새 마이그레이션의 `(user_id, command, target) WHERE consumed_at IS NULL` 부분 유일 색인(V3는 이미 적용돼 고칠 수 없다). 주인 없음.

### 59. sweep이 발급 사용자 범위로 좁혀져 다시 발급하지 않는 사용자의 만료 미소비 행이 영구히 남는다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 1.9가 append했다. `ActionGrantService.java:418-424`의 sweep은 `deleteExpiredUnconsumedOf(userId)`(`ActionGrantRepository.java:248-253`)로 발급 사용자 범위만 지우고 다른 청소 경로는 없다 — Story 3.8의 Worker 스케줄링에도 이 표는 들어가지 않았다. 계정 삭제의 cascade가 여전히 유일한 퇴로이며 항목 56과 같은 뿌리다.

**미해소 잔여:** 보존 정책 라운드가 56과 함께 정한다(전역 주기 청소를 Worker에 둘지 포함). 주인 없음.

### 60. `/settings`에서 시작한 재인증이 그 화면이 아니라 세션의 확정 목적지로 돌아온다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 1.10이 append했다. 재인증 시작의 복귀 목적지는 여전히 세션 `DESTINATION_ATTRIBUTE` 하나다 — `AuthSessionController.java:331-338`이 `allowedDestination(sessionAttribute(request))`를 읽고, 그 뒤에는 Story 3.1이 더한 운영자 접기(`foldDestination`)만 있다. `CLAUDE.md`의 `## 스토리가 고정한 계약`이 지금도 "복귀 목적지의 출처는 세션 하나이며 클라이언트는 목적지를 보내지 않는다(1.8)"를 계약으로 적는다. 연결 흐름은 서버 상수 `SETTINGS_PATH`로 돌아오므로(`LinkService.java:210`) 이 항목은 설정 화면의 본인 확인 버튼에만 해당한다.

**미해소 잔여:** Story 1.8의 frozen 경계를 다시 협상하는 판단(로그인 시작이 `?next=`를 닫힌 집합으로 받는 선례는 있다). 주인 없음.

### 61. 내부 경로 집합이 다섯 곳에 복제됐고 서로 같은지 보는 검사가 없다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`) — 규모 확대.** Story 1.10이 append했다. 다섯 곳은 지금도 손으로 맞춘다 — `deploy/nginx/bokrilog.conf:238`의 정규식, `AuthFlowHandlers.java:120-121`의 `ALLOWED_DESTINATIONS`, `frontend/src/App.tsx:66-80`의 경로 분기, `frontend/src/session/useSessionState.ts:86`의 `DESTINATION_PATHS`, `frontend/src/content/auth.ts:174`의 `RETURN_DESTINATIONS`. 그사이 `/plan-conditions`(2.3)와 `/operator`(3.1)가 더해졌고 `/operator`에는 다섯 곳과 별개로 `OperatorAccess.foldDestination` 접기가 붙었다. `AuthFlowHandlers.java:95-100`의 javadoc이 "다섯의 등가를 보는 검사는 아직 없다"고 스스로 적는다. 항목 33·38과 같은 뿌리다.

**미해소 잔여:** 계층 간 등가 검사(항목 33의 결정). 주인 없음.

### 62. 제공자 버튼 CSS 규칙의 세 번째 사본이 생겼다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 1.10이 append했다. `frontend/src/screens/SettingsScreen.module.css:330-`의 `.googleButton`·`.naverButton`·`.kakaoButton`이 세 번째 사본으로 그대로 있다(최소 크기 쌍 `:335-336`). 설정 화면의 e2e(`frontend/e2e/auth-entry.spec.ts:850-866`)는 조작 대상마다 44×44 하한만 재고 세 버튼의 바깥 크기가 같은지는 재지 않는다. 항목 52와 같은 뿌리다.

**미해소 잔여:** 항목 52와 같은 CSS 소유 구조 결정. 주인 없음.

### 63. 제공자별 닫힌 맵이 화면 카피에 둘 더 생겼고 그 값이 맞는 제공자를 가리키는지 보는 검사가 없다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 1.11이 append했다. `frontend/src/content/auth.ts:763-767`(`unlinkLabels`)과 `:795-799`(`unlinkDialog.titles`)가 그대로 제공자 키의 닫힌 맵이다. 키 누락은 타입이 잡지만 값이 엉뚱한 제공자를 가리키는지 보는 단정은 없다 — 시험(`SettingsScreen.test.tsx:376`·`UnlinkDialog.test.tsx:82`)은 같은 맵의 값을 다시 읽어 비교하므로 그 축을 볼 수 없다. 항목 33·38·48과 같은 뿌리다.

**미해소 잔여:** 같은 계층 안의 값 대응 단정(예: 각 값이 같은 파일 `:738`의 `providerNames[provider]`를 포함하는지). 항목 48처럼 값싸게 먼저 닫을 수 있다. 주인 없음.

### 64. 최소 조작 영역 44×44px 규칙의 사본이 CSS 네 곳으로 늘었다

**부분 해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 1.11이 append했다. 대화상자 축은 닫혔다 — PR #73(`spec-frontend-dialog-commons.md`)이 `UnlinkDialog.module.css`를 지웠고, 이제 확인 대화상자 여덟(`UnlinkDialog`·`PlanEndDialog`·`ConnectionRevokeDialog`·운영자 대화상자 다섯)이 `frontend/src/components/ConfirmationDialog.module.css` 하나를 쓴다. 원문이 센 "네 번째 사본"은 사라졌다(항목 99와 같은 수정).

**미해소 잔여:** 제공자 버튼의 `--component-social-sign-in-min-target-size` 쌍은 세 화면에 그대로이고(`EntryScreen.module.css:183-184`·`ReauthPanel.module.css:82-83`·`SettingsScreen.module.css:335-336`), 일반 조작의 `--space-target-min` 하한은 CSS 모듈 열아홉에 각자 적혀 있다. 값이 토큰이라 표류해도 조용하고 기하를 재는 e2e는 화면마다 따로다. 항목 34·52·62·84와 같은 CSS 소유 구조 결정이다. 주인 없음.

### 65. Epic 1의 `link-provider`·`unlink-provider`를 새 confirmation hash 계약으로 개조한다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.1의 다중 목표 점검이 갈라내 append했다(source_spec 없음). 두 command의 확인 문구는 여전히 상수다 — `LinkService.java:66`(`CONFIRMATION = "연결"`)·`UnlinkService.java:77`(`"해제"`)이고 둘 다 그 상수로 발급하므로(`LinkService.java:181`·`UnlinkService.java:172`), `ActionGrant.java:163-169`가 `(command, target, confirmation)`으로 만드는 해시는 이 두 command에서 `(command, target)`의 결정적 함수이고 판별력이 0이다. 다른 발급 자리(Bybit 연결·연결 해제·계획 종료·운영 command)는 요청마다 정해진 문구 값을 넘기고, 운영 command 쪽은 Story 3.1이 확인 문구 재입력으로 닫았다. `sprint-status.yaml`의 `epic-1-retro-item-7`은 `in-progress`이고 `CLAUDE.md`는 "Epic 1·2 command의 hash는 ERD 정정 몫으로 남는다"고 적는다.

**미해소 잔여:** 두 command를 개조할지, `ERD-1.0.0` §5를 정정할지의 선택. 주인은 Epic 1 회고 item-7이다.

### 66. Bybit permission·retCode 허용 목록이 비어 있어 실제 제공자에서 거절 사유가 오분류될 수 있고, 그 공백을 읽는 게이트가 없다

**부분 해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.1이 append했다. 거절 사유 가운데 rate limit은 Story 2.12에서 실제 모양을 얻었다 — `backend/src/main/java/com/bokrilog/source/internal/BybitAdapter.java:211`이 `RATE_LIMIT_RET_CODE = 10006`을 두고 모든 서명 호출이 지나는 공통 교환(`:1035`)이 그것을 `RATE_LIMITED`로 읽으며, `BybitAdapterTest.java:353`의 `retcode10006,RATE_LIMITED`가 고정한다. HTTP 403은 IP 차단 회로(`BybitCallBudget`)가 받는다. 이 시험들은 `backend-quality`에서 돈다.

**미해소 잔여:** `READ_ONLY_PERMISSION_GROUPS`(`:152`)·`REQUIRED_PERMISSION_GROUPS`(`:161`)·`RET_CODE_REASONS`(`:171`)는 여전히 비어 있고 `BybitAdapterTest.java:653`이 그 비어 있음을 고정한다. 그래서 permission 배열이 비어 있지 않은 실제 읽기 전용 키는 전부 쓰기 권한으로 거절되고, `10006` 밖의 retCode(IP 거부 포함)는 호출 자리의 기본값으로 접힌다. 목록이 빈 채로 실연동을 열지 못하게 하는 기계 판독 표시도 없다(운영 gate `deploy/production/preflight.sh`는 이 표를 보지 않는다). 주인은 Epic 2 Bybit 실연동 게이트(API 스파이크)다.

### 67. 모듈별 삭제 경로를 열거하는 포트·레지스트리가 없고 `operations_restriction`에는 삭제 경로가 없다

**부분 해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.1이 append했다. 세 가지가 바뀌었다. erase에 운영 호출자가 생겼다 — Story 3.10의 루트 `AccountErasure`가 한 트랜잭션에서 portfolio → sync → plan → source → operations 순서로 부른다. `operations_restriction`에 삭제 경로가 생겼다 — `OperationsDeletionApi.java:38`의 `eraseRestrictionTargets`가 사용자 귀속 target을 digest로 비식별한다. 사용자 열의 소유 규칙을 카탈로그와 대조하는 시험이 생겼다 — `RestoreApplyTest.java:334`의 `사용자를_가리키는_모든_열은_정확히_한_모듈이_센다`는 시험 안에 **다시 적은** 접두사·열 규칙(`portfolio_*`·`sync_*`·`plan_*`·`source_*`·`operations_*`·`support_*`, 루트의 `action_grant` 등)을 `information_schema`의 모든 사용자 열과 대조해 어느 규칙에도 안 맞거나 두 규칙에 맞는 열을 거부한다. 다만 모듈의 `remainingRowsOf`를 부르지는 않으므로 모듈 SQL이 그 규칙과 같은지는 보지 않는다. `삭제된_계정에_남은_행은_모듈마다_세어져_판정을_실패시킨다`(`:277`)도 모듈별 세기를 부르지만 양수·합만 본다. 둘 다 `backend-quality`에서 돈다. Epic 1 회고가 짚은 "`action_grant`를 언급하지 않는 삭제 시험"은 이 두 시험이 `action_grant`를 루트 몫으로 다루면서 닫혔다.

**미해소 잔여:** 삭제 순서와 참여자 목록은 여전히 `AccountErasure`와 `RestoreInvariants`가 손으로 나열한다. 모듈 술어(`remainingRowsOf`)가 접두사 규칙의 표 전부를 덮는지 보는 검사가 없다 — 시험 모양은 모든 카탈로그 사용자 열에 한 행씩 심고 `residualRows`가 심은 수와 같은지 보는 것이다. Epic 4 `engagement` 같은 새 모듈의 표는 위 대조가 "어느 규칙에도 안 맞음"으로 잡지만, 규칙에 맞는 표를 모듈 SQL이나 erase가 빠뜨린 것은 그 검사가 있어야 드러난다. 사용자 삭제 경로도 아직 없다. 주인은 Epic 5다.

**부분 해소(2026-10-15, Story 4.1 판정, 기준 `176190f`) — 재확인.** 잔여가 예고한 새 모듈이 생겼고, 이번에도 손으로 더했다.
- **손으로 더한 자리.** Story 4.1(PR #107)이 `engagement`를 `AccountErasure`의 단계 목록(`AccountErasure.java:25`, 필드 `:57`)과 `RestoreInvariants` 합산(`RestoreInvariants.java:85`)에 직접 넣었다. 규칙 대조 시험에도 `engagement_*`·`user_id` 규칙(`RestoreApplyTest.java:359`)과 모듈별 합(`:296`·`:307`)을 직접 넣었다.
- **이번에 생긴 검사.** `ModuleBoundaryTest`가 `eraseConsents` 호출자를 `AccountErasure`로 고정한다. 같은 시험이 `engagement_` 표 이름이 `engagement/internal` 밖의 운영 소스에 나오지 않음을 훑는다.
- **아직 없는 검사.** 모듈 `remainingRowsOf`가 규칙의 표 전부를 덮는지 보는 일반 검사는 여전히 없다.

**미해소 잔여:** 위 잔여와 같다. 주인은 Epic 5다.

**부분 해소(2026-10-15, Story 4.2 판정, 기준 `090efb9`) — 재확인.** Story 4.2(PR #109)가 engagement의 두 번째 표(`engagement_ad_attempt`)를 더했다.
- **바뀐 것.** 잔여 행 세기가 모듈 수준 `EngagementResidueRepository`로 옮겨졌다. 이 세기는 `engagement_%` 표의 `user_id` 열을 카탈로그로 모두 센다. 그래서 engagement 안에서는 새 표가 저절로 세어진다.
- **그대로인 것.** 삭제 참여자는 다시 손으로 더했다(`AccountErasure.java`의 `eraseAdAttempts`). 모듈 사이의 일반 검사도 여전히 없다.

**미해소 잔여:** 위 잔여와 같다. 주인은 Epic 5다.

**부분 해소(2026-11-05, Story 5.1 판정, 기준 `822816b`) — 재확인.** 사용자 삭제 경로의 **요청** 절반이 생겼고, 이번에도 참여자 목록을 손으로 고쳤다.
- **요청 경로.** `POST /api/account/deletion`과 그 `/confirm`·`/cancel`이 생겼다. 확정은 요청 행 `identity_deletion_request`(V30)와 `DELETION_IN_PROGRESS` 전환을 허가 소비와 한 트랜잭션으로 커밋한다.
- **아직 없는 것.** erase(`AccountErasure`)의 사용자 호출자는 여전히 없다. `ModuleBoundaryTest`가 `requestDeletion`의 호출자를 `AccountDeletionRequestService`로 고정한다.
- **손으로 더한 자리.** 새 identity 표는 `erasePayload`가 지우고 `remainingRowsOf`가 세도록 손으로 더했다. `RestoreInvariants`의 삭제 불변식도 손으로 바꿨다 — 요청 없는 진행 중 계정, 요청이 있는 ACTIVE 계정이 위반이다.
- **여전히 없는 검사.** 모듈 술어가 규칙의 표 전부를 덮는지 보는 일반 검사는 그대로 없다.

**미해소 잔여:** 위와 같다. 다음 결정 지점은 erase를 사용자 경로에 잇는 Story 5.2다.

**부분 해소(2026-11-06, Story 5.2 판정, 기준 `f95ebb5`) — 참여자 나열이 한 곳 더 줄었다.**
- **단계 목록 하나.** 삭제 참여자와 순서는 이제 `identity` `DeletionStep` 하나가 소유한다. 단일 모드(리허설 표본·복원 적용)와 단계 모드(Worker)가 같은 목록을 돈다(`AccountErasure.apply`·`residueOf`).
- **단계별 잔여 검증.** 각 단계는 그 모듈의 `remainingRowsOf` 0을 확인해야 성공이다. 마지막 단계는 앞 단계 전부를 다시 센다. 모듈 SQL이나 erase가 빠뜨린 행은 실행 중에 `RESIDUAL_FOUND`로 드러난다.
- **아직 손으로 나열하는 곳.** `RestoreInvariants` 합산(`RestoreInvariants.java:86-89`)은 여전히 모듈을 손으로 나열한다.
- **여전히 없는 검사.** 모듈 술어가 규칙의 표 전부를 덮는지 보는 일반 검사(`epic-2-retro-item-24`)는 그대로 없다.

**미해소 잔여:** 잔여 합산을 같은 목록으로 돌리는 것과 일반 덮음 검사. 주인은 `epic-2-retro-item-24`(in-progress)다.

### 68. 5분 무장 수명이 화면에 보이지 않고 만료가 구분되지 않는 거부로 끝난다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.1이 append했다. `BybitConnectionFlow.java:58`의 `ARMED_LIFETIME = Duration.ofMinutes(5)`가 그대로이고 화면 카피 `frontend/src/content/connection.ts:236`도 "이 문구는 이번 연결 요청에만 유효합니다"뿐이며 남은 시간을 말하지 않는다. 준비 응답에 무장 시각이나 만료를 싣는 결정은 어느 SPEC에도 없고, 만료는 여전히 구분되지 않는 `FORBIDDEN`이다.

**미해소 잔여:** 무장 시각을 클라이언트에 줄지의 별도 판단(거부를 하나로 두는 계약은 유지한다). 주인 없음.

### 69. Bybit 왕복 e2e 넷이 `PLAYWRIGHT_BASE_URL` 게이트 안에 있어 기본 실행에서 돌지 않는다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.1이 append했다. `frontend/e2e/bybit-connection.spec.ts:105-108`의 `test.skip(injectedOrigin === '', …)`가 그대로라 기본 실행(`frontend-quality`의 `test:e2e:run`)에서는 돌지 않고 `same-origin-integration`의 주입 origin 실행에서만 돈다. CI에서는 PR마다 돌지만, 로컬 기본 실행에서 브라우저 층의 자격정보 비노출을 볼 길이 없다는 사실은 남는다. 항목 108과 같은 뿌리(주입 origin 게이트 밖 e2e)다.

**미해소 잔여:** e2e 게이트 구조를 정하는 결정. 주인 없음.

### 70. `SettingsScreen.test.tsx`의 여러 시험이 연결 상태 요청을 stub하지 않아 Bybit 절이 `unavailable` 분기로 렌더된다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.1이 append했다. 원문이 지목한 시험들은 지금도 연결 상태 요청을 stub하지 않는다 — `SettingsScreen.test.tsx:541`(`해제: 확인하면 목록을 다시 읽고 결과를 말한다`)·`:672`(`해제 성공 뒤 포커스가 결과 문장으로 간다`)·`:854`(연결 상태 재확인)에 `stubConnectionStatusRequest`가 없고, 그 요청은 `frontend/src/test/setup.ts:548-551`의 전역 거부에 부딪혀 기록에만 남는다. 같은 파일의 다른 시험은 대부분 `stubConnectionStatusRequest(notConnected)`를 명시하게 됐지만 기록이 비었는지 단정하는 시험은 `:535` 하나뿐이고, `afterEach`(`setup.ts:648-663`)는 기록을 지울 뿐 단정하지 않는다.

**미해소 잔여:** 화면 시험이 자기 전제를 명시하게 하는 방법(전역 사후 단정을 둘지, 연결 상태에도 3.5·3.6처럼 기본 응답을 둘지). 주인 없음.

### 71. 10분 확인 참조가 만료된 뒤에도 계정 삭제 전까지 확인 행이 보존된다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.3이 append했다. 만료 행을 청소하는 작업은 여전히 없다 — `plan_condition_confirmation`을 지우는 문장은 `PlanConditionRepository.java:169`의 계정 단위 erase 하나이고 그 호출자는 Story 3.10의 `AccountErasure`뿐이다. `expires_at`은 확인을 거절하는 데에만 쓰인다. Story 3.6이 문의에 "해결 뒤 90일, 조회 트랜잭션이 먼저 지운다"는 보존 상한 선례를 만들었지만 이 표에는 적용되지 않았다. 항목 56·59와 같은 뿌리다.

**미해소 잔여:** 확인 데이터의 보존 상한과 청소 시점(조회 시점 청소 선례를 따를지). 주인 없음.

### 72. 공유 증거에 사건 분 candle이 있으면 kline을 다시 부르지 않아 제공자 쪽 종가 정정이 관측되지 않는다

**해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.6이 append했고 지목한 Story 2.12가 닫았다. 정밀 검증(`VERIFY`·`CATCH_UP`)은 대상 안 흐름의 사건 분 candle을 캐시가 있어도 다시 조회한다 — `backend/src/main/java/com/bokrilog/sync/internal/CashflowConversionMarket.java:70-73`·`:109`의 `forcedMinutes`가 그 시도에서 캐시를 믿지 않을 분이고, 종가가 다르면 공유 증거에 새 revision을 쌓는다. `PlanVerificationTest.aCorrectedCloseBecomesANewRevisionAndAChangedValue`(`:439`)가 제공자 시나리오 `REVISED_PRICE`로 실제 경로를 지나 새 revision, 근거의 `VALUE_CHANGED`, 조정 이력 행을 단정한다 — 원문이 지적한 "SQL로 revision을 꽂는 시험"이 아니다. 이 시험은 `backend-quality`에서 돈다. 일상 동기화는 설계대로 캐시를 쓴다. 항목 86과 같은 시험이 함께 지킨다.

### 73. 환산 기준시각인 Bybit 서버 시간을 DB 시계와 비교하는 상한 없이 쓴다

**부분 해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.6이 append했다. 증분 동기화와 정밀 검증의 `tc`에는 상한이 생겼다 — Story 2.11이 `backend/src/main/java/com/bokrilog/sync/internal/SyncWindow.java:31`의 `TIME_SKEW = Duration.ofSeconds(60)`과 `:50`의 판정을 두었고, `PlanSyncService`가 Bybit 시각을 고정한 뒤 DB 시각과 60초 넘게 어긋나면 `FAILED`/`PROVIDER_TIME_UNTRUSTED`로 끝낸다(`PlanSyncServiceTest`·`SyncWindowTest`, `backend-quality`). 그 시도는 환산까지 가지 않으므로 틀린 시각이 공유 candle 증거로 들어가지 않는다.

**미해소 잔여:** 원문이 지목한 첫 계획 환산 작업은 그대로다 — `SyncJobService.java:602-613`의 `fixReference`가 `sources.marketTime()`의 값을 형식 검사만 거쳐 고정하고, 그 값이 `CashflowConversionMarket`의 candle 종료 판정 기준이 된다. 미래로 틀어진 시각이 오면 진행 중인 분 candle이 종료로 저장되고 사용자 식별자 없는 공유 증거라 다른 사용자의 환산에도 재사용될 수 있다. 같은 60초 상한을 여기에도 걸지는 `BYBIT-PRICE-1.0`의 시간 경계라 SPEC Ask First 대상이었고 아직 어느 SPEC도 정하지 않았다. 주인 없음.

### 74. 만료된 lease의 재claim에 횟수 상한이 없어 죽는 시도가 약 30초마다 영구히 다시 실행된다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.6이 append했다. 만료 lease를 다시 가져가는 술어가 작업 표마다 그대로이고 시도 횟수 열·상한은 없다 — `CashflowConversionRepository.java:282`·`CashflowClassificationRepository.java:120`·`InitialCollectionRepository.java:87`·`SyncJobRepository.java:71`·`PlanPublicationRepository.java:228`이 모두 `status='RUNNING' AND lease_until<now()` 행을 다시 claim한다. PR #71의 `SyncLease.java:22`는 30초 interval 리터럴만 한 곳에 모았고(javadoc "값은 바꾸지 않았다") 재claim 정책은 다루지 않았다. Story 2.12의 호출 예산이 재실행 하나하나의 Bybit 호출 속도는 묶지만 무한 재시도 자체를 끊지는 않는다. 항목 85·96과 같은 뿌리다.

**미해소 잔여:** 시도 횟수 열·상한·상한에 닿았을 때의 종단 code를 작업 표 전체가 함께 정하는 결정(새 마이그레이션 필요). 주인 없음.

### 75. ERD §2.5의 호출 예산·자동 재시도가 없어, 끝난 결과 뒤 새 key로 환산을 다시 접수하면 공개 호출이 요청 수만큼 반복된다

**부분 해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.6이 append했다. 원문이 기다리던 "Bybit 호출 전체를 묶는 별도 라운드"가 Story 2.12로 왔다 — `backend/src/main/java/com/bokrilog/source/internal/BybitCallBudget.java:20-27`이 자격정보별 서명 호출 bucket, 서비스 전체 공개 호출 bucket, 동시 수집 2개, 잔여 헤더 대기, HTTP 403의 10분 회로를 소유하고 `BybitAdapter`가 그 예산을 모든 Bybit 호출에 건다. 수집 page에는 backoff 재시도가 생겼다(admission은 의도적으로 재시도하지 않는다). `BybitCallBudgetTest`가 bucket·회로·대기를 시계 주입으로 단정하고 `backend-quality`에서 돈다. 반복 접수가 공유 reserved IP의 공개 호출을 초당 상한 넘게 쓰는 경로는 닫혔다.

**미해소 잔여:** settled 시도 뒤의 새 접수는 여전히 막히지 않는다 — `SyncJobService.java:347-361`의 `admitCashflowConversion`은 새 멱등 키마다 작업을 만들고 재실행마다 서버 시간·instrument를 다시 부르며, 공개 bucket이 서비스 전체에 하나라 한 사용자의 반복 접수가 다른 사용자의 몫을 먹을 수 있다. 예산이 프로세스마다 하나라는 점은 항목 95가 따로 다룬다. 사용자 단위 접수 제한을 둘지는 주인 없음.

### 76. 수집·판정·환산 세 상태 섹션이 GET이 계속 실패해도 진행 중이면 2초마다 끝없이 폴링한다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.6이 append했다. 폴링은 지금도 연속 실패를 세지 않는다 — `frontend/src/components/InitialInputsCollection.tsx:365-373`(환산)·`:756-764`(수집)·`:766-774`(판정)과 같은 관용의 `PlanPublicationSection.tsx:153`이 그렇고, 실패하면 `refresh`가 오류 문구만 세우고 `job`을 그대로 두므로(`:694-698`) QUEUED·RUNNING이 유지되고 interval이 계속 돈다. `spec-frontend-effect-timing.md`가 이 넷을 layout effect로 옮겼지만(항목 83 해소) 그것은 interval이 걸리고 풀리는 시점만 바꿨다.

**미해소 잔여:** 연속 실패 상한과 그 뒤의 문구를 네 폴링이 함께 정하는 변경. 주인 없음.

### 77. 수집 섹션의 `start()`가 409를 받으면 거절 문구를 세운 직후 `refresh()`가 그 문구를 지운다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.6이 append했다. `frontend/src/components/InitialInputsCollection.tsx:801-805`가 409에서 `setError(copy.collection.ineligible)` 뒤에 `await refresh()`를 부르고, `refresh`(`:686-693`)는 성공하면 `setError(null)`을 한다 — 거절 문구가 곧바로 지워지는 순서가 그대로다. 같은 결함의 환산 섹션 사본은 Story 2.6이 고쳤고 수집 쪽만 남았다.

**미해소 잔여:** 409 문구를 refresh 뒤까지 보존하는 변경(순서 교체 또는 refresh의 오류 지우기 조건화). 주인 없음.

### 78. Windows Git Bash에서 `verify-same-origin.sh`가 한글 확인 문구를 CP949 바이트로 보내 연쇄 실패한다

**부분 해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.6이 append했고 `spec-verify-windows-utf8-body.md`(PR #91, 머지 커밋 `98fe052`)가 원인을 고쳤다. 원인은 mingw `curl`이 argv를 ANSI 코드 페이지로 해석하는 것이어서, JSON 본문을 `work_dir` 안의 파일에 바이트 그대로 쓰고(`deploy/compose/verify-same-origin.sh:367-380`의 `request_body`) `--data-binary "@<파일>"`로만 보낸다(예: `:2389`). 같은 형태가 돌아오지 않도록 `fetch`(`:382-424`)에 값 기준 가드를 두어 argv 본문(인라인 `--data`·`--data-raw`·`--data-urlencode`·`-F` 등)을 요청 전에 거부하고 `record_fail`로 남긴다. 파일 본문 경로는 OS와 무관하므로 Linux CI의 `same-origin-integration`이 PR마다 돈다.

**미해소 잔여:** 결함 자체는 Windows에서만 나므로 CI가 검증하지 않는다 — Windows 실행의 확인은 그 SPEC의 AC(로컬 Git Bash)뿐이고 CI에는 Windows leg가 없다(항목 32 미해소). 회귀를 막는 유일한 그물인 `fetch` 가드에도 행동 시험이 없어, 가드 패턴이 깨지거나 지워져도 실패하는 시험이 없다(항목 130 미해소). 그래서 "시험이 없으면 부분 해소" 기준으로 부분 해소다. 주인은 항목 130·32와 같다(주인 없음).

### 79. 프런트엔드 조회 훅에 요청 시간 상한이 없어, 응답이 끝나지 않으면 다시 불러오기가 영구히 무시된다

**부분 해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.8이 append했다. 원문이 짚은 대시보드 훅에는 시간 상한이 생겼다 — Story 2.11이 `frontend/src/session/useDashboard.ts:1331`에 `DASHBOARD_REQUEST_TIMEOUT_MS = 10_000`과 abort를 두어 끝나지 않는 읽기가 실패로 settle되고 `inFlight`가 풀린다. `useDashboard.test.tsx:359`의 `P9: a read that never answers is aborted after the time limit …`가 단정하고 `frontend-quality`에서 돈다. 계획 종료·후속 계획 기준·Bybit 연결도 각자 상한을 쓴다.

**미해소 잔여:** 원문이 걱정한 "훅마다 동작이 갈린다"는 그대로다 — 참조 조회 엔진 `useReferencedRequest.ts`(근거·조정 이력)와 목록 엔진 `useSequencedRead.ts`는 중단만 있고 시간 상한이 없으며, `InitialInputsCollection.tsx`·`PlanConditionPanel.tsx`의 fetch에는 `signal`도 상한도 없다. 상수도 `DASHBOARD_REQUEST_TIMEOUT_MS`·`SESSION_REQUEST_TIMEOUT_MS`·`CONNECT_REQUEST_TIMEOUT_MS`로 갈라져 있다. 공통 상한과 실패 문구를 정하는 라운드는 주인 없음.

### 80. 대시보드의 state·cause·nextAction 코드와 지표 순서가 계층마다 따로 적혀 있고 대조 검사가 없다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.8이 append했다. 지표 순서는 지금도 세 곳이 각자 가진다 — `DashboardTrust.java:90`의 `METRICS`, `PlanPublicationRepository.java:656`의 `ORDER BY CASE metric …`, `frontend/src/content/planResult.ts:29`의 `METRIC_ORDER`. state·cause·nextAction 코드도 백엔드 문자열과 화면의 닫힌 맵에 따로 있고, 서버가 cause를 바꿔도 화면은 안전한 대체 문구로 접힐 뿐 깨지는 시험이 없다. 두 계층이 같은 표를 읽는 선례는 Story 3.6의 `frontend/src/content/inquiryRules.fixture.json`(`InquiryRulesTest`와 Vitest가 함께 읽는다) 하나다. 항목 33·38·51·61·101과 같은 뿌리다.

**미해소 잔여:** 그 공유 fixture 관용을 대시보드 코드·지표 순서로 넓힐지(항목 33의 결정). 백엔드 안의 두 사본(`METRICS`와 `ORDER BY CASE`)은 계층 안 문제라 더 싸게 먼저 묶을 수 있다. 주인 없음.

### 81. 계획이 없는 랜딩 화면에서 화면 status와 `FirstSnapshotPanel`의 live region이 함께 있어 화면당 live region이 둘이다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.8이 append했다. `frontend/src/screens/SignedInScreen.tsx:174-176`의 화면 status(`role="status"`)와 `frontend/src/components/FirstSnapshotPanel.tsx:57`의 `aria-live="polite"` 영역이 함께 렌더된다 — `SignedInScreen.tsx:214`가 `selected === null && presence !== null`일 때 패널을 그리기 때문이다. `CLAUDE.md`의 계약 "화면당 live region은 하나"(2.9·2.11)에 대한 예외로 남아 있다.

**미해소 잔여:** 첫 공식값 패널의 상태 문장을 화면 status 하나로 옮기는 변경. 주인 없음.

### 82. 로그인 수단 해제의 취소가 다른 탭의 다음 준비와 겹치면, 늦게 끝난 취소가 새 무장과 새 허가를 지워 확인이 403이 된다

**부분 해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.8이 append했다. 허가 쪽 절반은 닫혔다 — PR #71(`spec-sensitive-command-commons.md`)이 명시적 무효화를 참조 단위 하나(`ActionGrantService.java:245-253`의 `invalidateReference`)로 통일해, `UnlinkService.abandon`·`LinkService.abandon`은 그 무장이 낳은 허가 하나만 지운다. 늦게 끝난 취소가 다음 준비의 새 허가를 `(user, command, target)` 단위로 삼키는 경로는 없다. `AuthUnlinkProviderTest.java:598`의 `오래된_탭의_취소는_다른_세션의_허가를_지우지_않는다`(Bybit 쪽 `BybitConnectionTest.java:495`)가 두 실제 세션으로 단정하고 `backend-quality`에서 돈다. 같은 탭의 경합은 Story 2.8이 화면에서 막았다.

**미해소 잔여:** 같은 세션 쿠키를 공유하는 두 탭 사이의 세션 속성 경합은 그대로다 — `abandon`은 요청 시작에 읽은 무장을 버리는데 `SessionArming.java:141-143`의 `discard`는 조건 없는 `removeAttribute`이고 Spring Session은 그 삭제를 요청 끝에 쓴다. 그래서 겹친 준비가 방금 저장한 새 무장이 늦게 끝난 취소에 지워질 수 있다(Spring Session JDBC의 저장 동작에서 추론한 것이며 재현 시험은 없다). 원문이 요구한 "무장 세대 비교"는 없다. 주인 없음.

### 83. `InitialInputsCollection.test.tsx`의 403 시험이 전체 실행 부하 아래 간헐적으로 실패한다

**해소(2026-10-04, frontend-effect-timing).** Story 2.8이 append했다(시험은 Story 2.7이 더했다). 원인은 시험이 아니라 `PlanPublicationSection`의 폴링 interval이 passive effect에서 걸리고 풀리던 것이다. 시험은 `계산·발행 중`을 본 직후 시계를 2초 당기고, 거절 문장을 본 직후 읽기 수를 센다. 부하가 걸리면 interval이 그 당김보다 늦게 걸리거나 거절을 그린 뒤에도 남아 읽기를 계속했다. `spec-frontend-effect-timing.md` `## 측정`의 기준선 1회차가 이 시험의 `reads` 7 ≠ 2였다.

interval을 layout effect로 옮겨 그 상태를 그린 커밋에서 걸리고 풀리게 했다(`frontend/src/components/PlanPublicationSection.tsx:150`). 같은 파일군의 세 폴링도 같게 옮겼다(`frontend/src/components/InitialInputsCollection.tsx:364`·`:758`·`:768`). 시험의 대기와 기대값은 바꾸지 않았고 그 의존을 주석으로 적었다(`frontend/src/components/InitialInputsCollection.test.tsx:1081-1086`).

확인은 항목 50과 같은 두 겹이다. 결정적 가드는 `frontend/src/components/InitialInputsCollection.test.tsx:1130`이다 — `계산·발행 중`이 처음 보이는 순간 interval이 이미 걸려 있는지를 가짜 `setInterval`의 수로 단정한다. 이 effect 하나를 `useEffect`로 되돌리면 그 파일 10회 중 10회 이 가드가 실패했고 복원하면 10회 중 10회 통과했으며, CI `frontend-quality`가 PR마다 돌린다. 로컬 부하 반복은 항목 50의 전체 Vitest 실행과 같고, 이 파일만 `stress-test.sh --runs 3`으로 돈 실행도 통과했다.

### 84. `AdjustmentHistory.module.css`가 44×44 최소 조작 영역 규칙의 다섯 번째 사본이 됐다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.10이 append했다. 구조는 그대로다 — `frontend/src/components/AdjustmentHistory.module.css`의 `.action`(`:217-224`, `min-block-size`·`min-inline-size: var(--space-target-min)`)·`.id`(`:95`)·`.facts`(`:64`)·`.notice`(`:206`)가 `CalculationEvidence.module.css`의 `.action`(`:145-151`)·`.id`(`:93`)·`.facts`(`:66`)·`.notice`(`:134`)와 같은 토큰 조합으로 남아 있다. 지금 `frontend/src` 아래에서 44×44 선언을 가진 CSS 모듈은 `tokens.css`를 빼고 19개다. PR #73(`spec-frontend-dialog-commons.md`)은 대화상자 CSS만 `ConfirmationDialog.module.css` 하나로 모았고(항목 99) 패널·조작 모듈은 범위 밖이었다. 항목 34·52·62·64·88·93·99·102와 같은 뿌리다.

**미해소 잔여:** 공유 규칙의 형태(공유 CSS 모듈·`composes`·전역 유틸리티 클래스)와 그것을 강제할 검사(stylelint 규칙 등). 주인 없음.

### 85. 증분 동기화가 매번 전체를 다시 처리하고 값이 같아도 새 공식 버전을 발행하며, 긴 단계는 lease를 잃을 수 있다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.11이 append했다. 발행은 시도마다 다음 공식 버전을 쓴다 — `backend/src/main/java/com/bokrilog/sync/internal/PlanSyncPublisher.java:23-36`의 쓰기 순서에 "변경이 없으면 발행하지 않음" 분기가 없고, `VersionChanges.java:21`의 `VERIFIED_NO_CHANGE`는 표지일 뿐 발행을 생략하지 않는다. heartbeat는 여전히 `FOR UPDATE SKIP LOCKED`라 다른 트랜잭션이 잡은 행을 건너뛴다(`PlanSyncRepository.java:316-334`). PR #71의 `SyncLease`(`sync/internal/SyncLease.java:22`)는 30초 리터럴을 상수 하나로 모았을 뿐 값·동작을 바꾸지 않았다(같은 파일 `:6-7` 주석). 버전·흐름의 보존 상한도 없다. heartbeat는 항목 96, lease 재claim은 항목 74와 같은 뿌리이고 보존 상한은 56·59·71 묶음이다.

**미해소 잔여:** 무변경 발행 생략(2.9·2.10의 "계산 버전 하나가 한 계보를 인용한다" 불변식과의 정리), 보존 상한, 단계 트랜잭션 안의 lease 연장. 원문의 결정 지점("이력 규모가 보이는 시점")은 조건이라 주인 없음.

### 86. 이어받은 사건의 USD 값·판정이 버전 사이에서 바뀌어도 조정 이력에 표지가 없다

**해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.11이 append했고 지목된 Story 2.12가 SPEC Problem에 이 항목을 적고 닫았다(`spec-2-12-precision-reverification.md:25`). 버전 사이의 연결은 conversion 사슬이 아니라 모든 증분 발행이 같은 트랜잭션에 쓰는 직전 버전과의 흐름 차이(`sync_plan_calculation_change`, `ADDED`·`REMOVED`·`VALUE_CHANGED`)가 맡고(`PlanSyncPublisher.java:32-33`), 조정 이력은 그 차이 가운데 늦은 행에 표지와 이전 값을 붙인다(`AdjustmentHistoryService.java:207-215`, 판정은 `VersionChanges.java:31-33`). `PlanVerificationTest.aCorrectedCloseBecomesANewRevisionAndAChangedValue`(`:439`)가 가격 revision 뒤 조정 이력 행이 `VALUE_CHANGED`이고 이전 값이 `2`임을(`:469-472`), `aSharedPriceCorrectionUnderAnOrdinarySyncIsAChangedValueNotALateEvent`(`:1416`)가 일반 동기화 아래의 공유 candle 정정도 같은 표지를 받음을, `aChangeRowThatIsNotTheFlowDifferenceMakesTheEvidenceAndHistoryUnavailable`(`:1035`)이 차이 행이 실제 흐름 차이와 어긋나면 근거·이력이 닫힘을 단정한다. 셋 다 `backend-quality`에서 돈다.

판정 정책 버전 교체 경로는 정책 버전이 하나뿐이라 재현할 길이 없지만 같은 흐름 차이 기제를 지나므로 따로 남기지 않는다 — 이 부분은 시험이 아니라 구조 추론이다(정책 버전이 오르는 경우는 항목 143과 같은 사정).

### 87. `검토 필요`로 끝난 동기화의 다음 행동이 풀 수 없는 재동기화다

**해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.11이 append했고 지목된 Story 2.12가 SPEC Problem에 이 항목을 적고 닫았다(`spec-2-12-precision-reverification.md:20`). 동기화 검토의 다음 행동은 재동기화가 아니라 정밀 검증이다(`backend/src/main/java/com/bokrilog/sync/internal/DashboardTrust.java:295-298`, 주석 "Syncing again cannot resolve a sync's review"). `PlanVerificationTest.aSyncThatNoLongerSeesAnOfficialEventInItsWindowNeedsReview`(`:275`)가 `nextAction`이 `VERIFY`임을(`:290`), `anUnseenStoredEventIsExcludedAndLaterSyncsTreatItAsExplained`(`:301`)가 원문이 짚은 "발행되지 않은 시도가 저장한 사건이 제공자에서 사라진" 경우를 정밀 검증이 `SUPERSEDED`로 제외하고 이후 동기화가 설명된 것으로 보고 발행함을, `aMissingOfficialEventHoldsTheVersionAndAskingAgainIsNotUseful`(`:339`)이 공식 사건 소실은 버전을 보류하고 재요청을 not useful로 거절함(운영 검증에 넘기는 의도된 fail-closed)을 단정한다. 모두 `backend-quality`에서 돈다.

### 88. `SyncControl.module.css`가 44×44 규칙의 여섯 번째 사본이 됐다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.11이 append했다. `frontend/src/components/SyncControl.module.css:21-29`의 `.button`이 `min-block-size`·`min-inline-size: var(--space-target-min)`을 그대로 든다. 공유 규칙은 아직 없다. 항목 84와 같은 뿌리다.

**미해소 잔여:** 항목 84와 같다. 주인 없음.

### 89. `staleFenceCannotPublishAfterLeaseOwnershipChanges`의 2초 대기가 스텁 지연 2초를 덮지 못한다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** `spec-backend-test-speed.md`가 append했다. 대기는 지금 `backend/src/test/java/com/bokrilog/FirstSnapshotTest.java:1665`의 `Thread.sleep(2000)`이고(원문 뒤 줄이 밀렸다) `SLOW_WALLET` 스텁 지연은 여전히 2000ms다(`backend/src/main/java/com/bokrilog/DevBybitStub.java:477-481`). 이 파일의 이후 변경도 이 대기를 건드리지 않아, 늦은 워커가 스텁에서 돌아오기 전에 단정이 끝날 수 있는 창이 그대로다. 항목 90·91과 같은 시험 구조 묶음이다.

**미해소 잔여:** 대기를 스텁 지연보다 여유 있게 늘릴지, 항목 90의 계수기로 "느린 응답을 보냈다"를 관측한 뒤 단정할지. 주인 없음.

### 90. 느린 스텁 호출 중의 늦은 워커를 기다리는 고정 대기 묶음

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** `spec-backend-test-speed.md`가 append했다. 고정 대기는 `FirstSnapshotTest.java:582`(2500)·`:626`(3000)·`:1172`·`:1194`(2500)·`:1665`(2000ms)에 그대로 있다. 시험 표면 `BybitStubConfiguration`에는 경로별 요청 수 계수기(`:115-134`)만 있고 "느린 응답을 보냈다"를 세는 계수기는 없다. `spec-backend-test-speed.md:50`이 이 묶음을 "유지"로 분류한 상태가 바뀌지 않았다.

**미해소 잔여:** 스텁 시험 표면에 느린 응답 완료 계수기를 더하고 `QUIET_WINDOW`로 바꾸는 일. 주인 없음.

### 91. 세 fence 시험이 DB fence를 확률적으로만 지난다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** `spec-backend-test-speed.md`가 append했다. heartbeat는 여전히 5초 고정이고(`backend/src/main/java/com/bokrilog/sync/internal/FirstSnapshotWorker.java:81`, `@Scheduled(fixedDelay=5000)`) 메모리 guard를 건너뛰고 DB fence(`fencing_generation=:g`)만 결정적으로 보는 시험은 생기지 않았다. DB fence 회귀는 지금도 heartbeat가 창을 놓친 실행에서만 잡힌다.

**미해소 잔여:** 메모리 guard를 우회하는 결정적 DB fence 시험. 주인 없음. 항목 89·90과 함께 보는 편이 싸다.

### 92. 개발·CI compose가 워커 dispatch를 운영 기본 1초로 돌린다

**미판정(2026-10-15, 판정 라운드, 기준 `189c16b`) — 보류: CI chore.** compose dispatch 간격과 통합 job 예산을 다루는 진행 중인 CI 임계 경로 chore(Epic 3 회고 AI-12, `epic-3-retro-item-37`)와 겹쳐, 판정은 그 chore의 done 전환 PR이 더한다(`CLAUDE.md` 표준 흐름 7번).

**미해소(2026-10-15, CI 임계 경로 chore 판정, 기준 `571bb3c`).** 이 chore(`spec-ci-critical-path.md`, PR #104)는 리허설 job 경계와 백업·복원 분류만 바꾸고 개발·CI compose는 건드리지 않았다. dispatch 간격은 운영 기본 그대로다 — `backend/src/main/resources/application.yml:260`의 `dispatch-interval: 1s`를 `deploy/compose/compose.yaml`이 덮지 않는다. 그래서 `same-origin-integration`의 동기화 왕복은 지금도 실제 1초 tick을 기다리고, 회귀 단계 상한 8분(`.github/workflows/ci.yml:446`)도 그대로다(PR #104 두 실행의 통합 job 7분 1초·7분 41초).

**미해소 잔여:** compose의 backend에 짧은 dispatch 간격을 주고 회귀 단계·Playwright `globalTimeout`을 실측으로 다시 맞추는 일. 주인 상실 — 결정 지점으로 지목된 이 chore가 범위(리허설 분기)상 결정하지 않고 지나갔다.

### 93. 44×44 규칙의 일곱·여덟 번째 사본과 `.ranges`·`.facts`·`@keyframes` 사본

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.12가 append했다. 사본은 그대로이거나 늘었다 — 44×44는 `frontend/src/components/SyncControl.module.css:51-58`(`.secondary`)·`VerificationControl.module.css:27-34`(`.button`), `.ranges`는 여전히 세 모듈(`PlanResultPanel.module.css:50`·`VerificationControl.module.css:124`·`VersionChangeNote.module.css:55`), 진행 표지 `@keyframes`는 `SyncControl.module.css:108`·`VerificationControl.module.css:79`(2.13이 `PlanEndControl.module.css:75`를 더했다, 항목 99)이고 `.facts`는 이후 스토리를 거쳐 아홉 모듈로 늘었다. PR #73은 대화상자 모듈만 모았다. 항목 84와 같은 뿌리다.

**미해소 잔여:** 항목 84와 같다. 주인 없음.

### 94. 현재가치 측정 시각과 흐름 확인 상한(`tc`) 사이 간격에 상한이 없다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.12가 append했다. 수집은 여전히 `tc`를 먼저 고정하고 wallet을 끝에 읽으며 둘을 별개 값으로 돌려줄 뿐 간격을 판정하지 않는다(`backend/src/main/java/com/bokrilog/source/internal/BybitAdapter.java:564-566`). sync·portfolio·마이그레이션에서 `measured_at`과 `tc` 간격의 상한·CHECK는 0건이다. 그 사이의 입출금은 그 버전의 `E`에는 들어가고 흐름에는 빠진다 — 다음 동기화 창 `(tc, tc']`이 흐름을 잡으므로 한 버전 동안의 일시적 어긋남이지만 그 버전의 지표는 틀린 채 공식으로 보인다.

**미해소 잔여:** 간격 상한 값과 넘었을 때의 행동(재측정·검토 표지·발행 거절). `ERD-1.0.0` §2.2를 정정하는 새 정책 값이다. 주인 없음 — Bybit 실연동 게이트 전에 정할 후보다.

### 95. Bybit 호출 예산과 IP 403 회로가 프로세스 메모리에만 있다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`) — 전제 악화.** Story 2.12가 append했고 결정 지점으로 Story 3.8을 지목했는데, 3.8은 이 결정 없이 `done`이 됐다(`spec-3-8-blue-green-deploy.md`에서 예산·token bucket은 0건). 오히려 두 프로세스의 공존이 상시가 됐다 — 3.8이 API와 Worker를 다른 프로세스로 나눴고 운영에서 `all`은 기동 거부이며(`BokrilogRole.java:21`), API는 요청 스레드의 연결 검증으로(`BybitConnectionService.java:378`), Worker는 동기화로 Bybit를 부르는데 예산은 어댑터 인스턴스마다 하나다(`source/internal/BybitAdapter.java:226`, `BybitCallBudget.java:19` "The Bybit call budget of this process"). 그래서 Worker가 연 IP 차단 회로를 API는 모른다.

**미해소 잔여:** 공유 방식(DB 기반 bucket·회로 또는 Bybit 호출 소유의 일원화)을 정할지, API 호출량이 작다는 근거로 비공유를 받아들일지. 지목 스토리가 지나가 주인 없음(`## 갱신 규칙` (나) 부류) — Bybit 실연동 게이트 전에 정해야 한다.

### 96. heartbeat의 `SKIP LOCKED` 때문에 긴 page 저장 중 lease 갱신이 건너뛰어진다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.12가 append했다. `PlanSyncRepository.java:316-334`의 heartbeat는 시도 행과 세 자식 표 모두 `FOR UPDATE SKIP LOCKED`로 잡힌 행을 건너뛰고, 단계 트랜잭션 안에서 lease를 연장하는 코드는 없다. 재개 상한은 고정이다 — `PlanVerificationTest.theThirdInterruptionEndsTheAttemptAsInterrupted`(`:595`)가 세 번째 중단에서 시도가 끝남을 고정한다. 항목 85와 같은 원인이고 74와 묶인다.

**미해소 잔여:** 항목 85와 같다. 주인 없음.

### 97. V12 backfill이 마이그레이션 시점 버전만 덮고, 옛 바이너리가 계속 발행하면 503·CHECK 위반이 난다

**부분 해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.12가 append했고 결정 지점으로 Story 3.8을 지목했다. V12 고유의 경우는 전제가 소멸했다 — 운영 배포가 한 번도 없었으므로 2.11 바이너리가 V12 뒤에 발행하는 일은 일어나지 않고, 첫 운영 배포는 빈 DB에 최신 바이너리와 전체 마이그레이션을 함께 올린다. 3.8은 일반 규칙도 정했다 — 운영의 발행 주체는 Worker뿐이고(`BokrilogRole.java:21`), `deploy/production/deploy.sh`는 Worker drain(`:612-640`)을 migrate(`:651`)보다 먼저 하며, rollback 창 안에서는 expand만 받는다(`:41-42`, gate `preflight.sh:232`·`:292-306`). 이 순서는 CI `deploy-rehearsal`(`rehearse-deploy.sh`)이 돈다.

**미해소 잔여:** migration이 expand인지는 release manifest의 선언(`preflight.sh:232`)일 뿐 그 migration이 옛 Worker와 실제로 호환되는지 보는 검사가 없고, rollback 창 안에서 옛 이미지를 새 schema 위에 띄우는 리허설 시나리오도 없다 — 그 리허설은 보류 중인 항목 129의 범위다. 운영 배포는 미검증이다(3.8 출시 게이트, Epic 3 회고 AI-9).

### 98. 계획 중도 종료가 재인증 시각을 지우지 않아 본인 확인 1회가 연쇄 승인한다

**해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.13이 append했다. PR #71(`spec-sensitive-command-commons.md`, 머지 커밋 `1e43ebf`)이 민감 command 실행 성공에 재인증 시각 제거를 묶었다 — `backend/src/main/java/com/bokrilog/SessionRotation.java:50-55`가 `ReauthenticationFlow.clearAuthentication`과 `changeSessionId`를 함께 하고, 계획 종료 확정이 그것을 부른다(`PlanEndCommandService.java:194`). 연결·해제·Bybit 연결·해제도 같은 부품을 쓴다. `PlanEndTest`(`:102-103`)가 종료가 실행된 요청 뒤 회전된 세션에 `AUTHENTICATED_AT_ATTRIBUTE`가 없음을, `BybitConnectionRevocationTest`(`:770-779`)가 해제 직후 같은 세션의 종료 준비가 403 `REAUTHENTICATION_REQUIRED`이고 허가 수가 늘지 않음을 단정하며 `backend-quality`에서 돈다. Epic 1 회고 item-1도 `sprint-status.yaml`에서 닫혔다. 항목 105·106과 묶인다.

### 99. 44×44 규칙 아홉·열 번째 사본과 `.marker`·`@keyframes`·확인 대화상자 규칙 사본

**부분 해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.13이 append했다. 대화상자 축은 닫혔다 — PR #73(`spec-frontend-dialog-commons.md`)이 `PlanEndDialog.module.css`·`UnlinkDialog.module.css`(와 `ConnectionRevokeDialog.module.css`)를 지우고 `frontend/src/components/ConfirmationDialog.module.css` 하나로 모았다. 지금 `components/`의 `*Dialog*.module.css`는 그 하나뿐이고, 확인 대화상자 여덟(`UnlinkDialog`·`PlanEndDialog`·`ConnectionRevokeDialog`·운영자 대화상자 다섯)이 모두 그 모듈을 쓴다.

**미해소 잔여:** 조작 쪽 사본은 그대로다 — `PlanEndControl.module.css:28-35`의 `.button`이 44×44를 들고, 진행 표지 `.marker`·`@keyframes`는 네 모듈에 있다(`ConfirmationDialog.module.css:134`·`:144`, `PlanEndControl.module.css:65`·`:75`, `SyncControl.module.css:98`·`:108`, `VerificationControl.module.css:69`·`:79`). 대화상자 단일 모듈을 지키는 자동 검사도 없다. 항목 84와 같은 뿌리이고 주인 없음.

### 100. `UnlinkDialog`·`PlanEndDialog`에 모달 기본 동작이 손으로 두 벌 있다

**해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.13이 append했다. PR #73(`spec-frontend-dialog-commons.md`, 머지 커밋 `b671bf3`)이 기본 포커스·capture `keydown`의 Tab 가둠·`Esc`·`body` 스크롤 잠금·passive 초점 복귀를 `frontend/src/components/useConfirmationDialog.ts` 하나로 옮겼다. `UnlinkDialog.tsx`·`PlanEndDialog.tsx`·`ConnectionRevokeDialog.tsx`와 운영자 대화상자 다섯이 같은 호출로 위임하고, 두 원래 파일에는 `keydown`·스크롤 코드가 남아 있지 않다. 정본 시험 `useConfirmationDialog.test.tsx`(14건, 가둠·`Esc`·스크롤·`StrictMode`·호출점 복귀)가 `frontend-quality` Vitest에서 돈다. 남은 시험 중복은 항목 107이 소유한다.

### 101. 완료 비교 상태·이유 목록이 세 계층에 따로 적혀 있고 대조 검사가 없다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.14가 append했다. 세 목록이 그대로 따로 있다 — 카피 맵 `frontend/src/content/planCompletion.ts:27-32`(상태 넷)·`:54-65`(이유), 파싱 목록 `frontend/src/session/useDashboard.ts:796-805`(`COMPARISONS`·`UNAVAILABLE_REASONS`·`HELD_REASON`), 백엔드 상수 `PlanPublicationService.java:799-801` 등. 셋을 맞대는 시험은 없다. 계층 간 공유 fixture의 선례는 Story 3.6의 `frontend/src/content/inquiryRules.fixture.json`(Vitest와 `InquiryRulesTest`가 함께 읽는다) 하나이고 이 목록에는 적용되지 않았다. 항목 33·38·48·51·61·63·80과 같은 뿌리다.

**미해소 잔여:** 계층 간 동등성 검사의 형태(3.6의 공유 fixture 방식이 선례다)와 범위. 주인 없음.

### 102. `PlanCompletionResult`·`PostEndValue`의 `.region`이 테두리 토큰 하나만 다른 패널 프레이밍 사본이다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.14가 append했다. `frontend/src/components/PlanCompletionResult.module.css:7-15`와 `PostEndValue.module.css:6-14`의 `.region`은 선언이 같고 `border`의 색 토큰(`--color-border-strong` 대 `--color-border-default`) 하나만 다르다. 항목 84와 같은 뿌리다.

**미해소 잔여:** 항목 84와 같다. 주인 없음.

### 103. 민감 command 확정이 커밋·회전한 뒤 응답을 잃으면 재시도가 회전 전 쿠키로 나가 만료 화면으로 간다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.16이 append했다. 서버의 멱등 재전송은 세션을 지문에서 뺐지만(`spec-1-9-reauthentication-action-grant.md:148`), 응답과 함께 `Set-Cookie`를 잃은 브라우저는 회전 전 세션 id를 보내고 그 id는 `changeSessionId`(`SessionRotation.java:55`) 뒤 어떤 세션도 가리키지 않으므로 재전송이 미인증으로 떨어진다. 화면은 응답 없는 확정을 `uncertain`으로 두고 다음 누름에 같은 키를 보낼 뿐(`frontend/src/session/usePlanEnd.ts:457-462`) 쿠키를 잃은 경우를 따로 다루지 않는다. 운영 command 확정도 같은 회전을 쓴다(`OperatorCommandService.java:347`). PR #71·#73·#97 어느 SPEC도 이 경로를 다루지 않았다.

**미해소 잔여:** 401을 받은 `uncertain` 확정을 만료 이동 대신 재로그인 뒤 결과 재조회로 접을지, 서버가 회전 전 id를 짧게 유예할지. 업무 변경은 이미 커밋됐으므로 데이터 정확성이 아니라 화면 결말의 문제다. 주인 없음.

### 104. Epic 2 회고 AI-8의 프런트엔드 묶음(D-4): 대화상자 3벌·조회 훅 2벌·`isInstant` 2벌

**부분 해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** 2026-10-06 점검(PR #71 라운드)이 append했고 PR #73(`spec-frontend-dialog-commons.md`, 머지 커밋 `b671bf3`)이 구현했다. 대화상자는 셋 모두 `useConfirmationDialog`에 위임하고 CSS는 `ConfirmationDialog.module.css` 하나다(항목 99·100). 조회 훅은 둘 모두 `frontend/src/session/useReferencedRequest.ts`에 위임하고 동시성 정책만 인자로 다르다(`useAdjustmentHistory.ts:400` `replace`, `useCalculationEvidence.ts:716` `ignore`). `isInstant`의 정의는 `frontend/src/session/instant.ts:9` 하나다. 정본 시험 `useConfirmationDialog.test.tsx`·`useReferencedRequest.test.tsx`·`instant.test.ts`가 `frontend-quality` Vitest에서 돈다.

**미해소 잔여:** 기존 사본은 없어졌지만 새 사본을 막는 것은 `CLAUDE.md`의 규칙 문장("새 확인 대화상자·참조 조회·시각 판정은 이 넷을 쓰고 사본을 만들지 않는다")뿐이고 자동 검사가 없다 — 그래서 "시험이 없으면 부분 해소" 기준으로 부분 해소다. 남은 시험 중복(항목 107)과 CSS 축의 사본(항목 99 잔여)도 이 묶음에 걸친다. 검사(예: 대화상자 `keydown`·스크롤 잠금 코드와 `isInstant` 정의가 정해진 파일 밖에 없음을 보는 lint·grep 시험)를 둘지는 주인 없음.

### 105. 연결·해제 성공 직후 다음 준비가 403 재인증 게이트에 떨어지는 화면 동작과 카피

**해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** PR #71 라운드가 append했고 PR #73이 닫았다(`spec-frontend-dialog-commons.md`). 성공 문구 넷이 "다음 민감한 조작 전에는 아래에서 본인 확인을 다시 해야 합니다"를 말하고(`frontend/src/content/auth.ts:491`·`:552`, `connection.ts:268`·`:314`, 2.17 재연결 성공 문구 `connection.ts:276`도 같다), 본인 확인 구역 본문이 "본인 확인 1회는 민감한 조작 1건"을 말한다(`auth.ts:864`). 화면 단정은 `frontend/src/test/reauthGate.ts`의 `expectReauthGateRejection` 하나이고 `SettingsScreen.test.tsx:735`·`SettingsConnection.test.tsx:484`·`SettingsConnectionRevoke.test.tsx:512`·`:533`이 그것을 부르며 `frontend-quality`에서 돈다. 실제 서버를 지나는 단정은 `frontend/e2e/auth-entry.spec.ts:926-936`(연결 성공 → 해제 준비 거절)이고 `same-origin-integration`의 주입 origin Playwright에서 돈다. 계획 종료 성공 문구는 항목 106, Bybit 경로의 e2e는 항목 108이 소유한다.

### 106. 계획 중도 종료의 성공 문구에 재인증 안내가 없다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** PR #73 라운드가 append했다. 종료 문장은 여전히 기록·종료 시각·동기화 중지만 말한다(`frontend/src/content/planEnd.ts:251-253`). 서버 쪽 게이트는 성립하고(항목 98) 설정 화면의 본인 확인 구역 본문이 계획 중도 종료를 포함해 규칙을 말하므로(`auth.ts:864`) 사용자가 이유 없이 거절을 만나는 경로는 좁지만, 원문의 "카피를 따로 정해야 한다"는 그대로다.

**미해소 잔여:** 대시보드 패널에 맞는 문구를 정하는 일(설정 화면의 "아래에서 본인 확인"은 그대로 쓸 수 없다). 주인 없음.

### 107. `UnlinkDialog.test.tsx`·`PlanEndDialog.test.tsx`가 정본과 겹치는 메커니즘 시험을 들고 있다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** PR #73 라운드가 append했다. 두 파일은 그 뒤 바뀌지 않았고 메커니즘 단정이 그대로다 — `UnlinkDialog.test.tsx`의 Tab 가둠(`:142-147`)·`Esc`(`:157`·`:203`)·스크롤 잠금(`:221-228`), `PlanEndDialog.test.tsx`의 가둠(`:129-136`)·`Esc`(`:142`·`:167`)·스크롤(`:316-321`)이 정본 `useConfirmationDialog.test.tsx`와 겹친다. 같은 chore는 `ConnectionRevokeDialog.test.tsx`만 마크업·카피·`restoreFocus` 의미로 좁혔다. 항목 100과 묶인다.

**미해소 잔여:** 두 파일의 메커니즘 단정을 걷어 내는 정리(기대값을 바꾸지 않는 삭제). 주인 없음.

### 108. "Bybit 연결 성공 → 재인증 없이 해제 준비 → 403 안내"의 주입 origin e2e가 없다

**부분 해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** PR #73 라운드가 append했다. 원문이 무겁다고 본 "해제 왕복 자체가 없다"는 Story 2.17(PR #100)이 채웠다 — `frontend/e2e/bybit-reconnect.spec.ts:69-100`이 연결 → 본인 확인 → 해제 대화상자 확정 → 해제 문구를 실제 스택에서 돌며, 주입 origin에서만 돌아(`:66-67`) `same-origin-integration`의 몫이다.

**미해소 잔여:** 그 시험은 해제 전에 본인 확인을 먼저 한다(`:89-90`). 원문이 요구한 "재인증 없이 해제 준비 → 403 안내" 단계는 여전히 Vitest(`SettingsConnectionRevoke.test.tsx:512`)만 고정하며, 이제는 한 단계를 더하는 일이 됐다. 항목 69와 같은 주입 origin 묶음이고 Epic 2 회고 AI-11과 겹친다. 주인 없음.

### 109. 준비만 하고 방치되거나 허가가 만료된 운영 operation은 원장에 결말 행이 없다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.1이 append했다. 원장 결말 값은 그대로이고(`backend/src/main/resources/db/migration/V17__operations_audit.sql:67-68`), 결말이 남는 것은 명시적 취소와 새 준비의 대체뿐이다(`OperatorCommandService.java:85`·`:255`, `CANCELLED SUPERSEDED`). 단순 방치나 5분 만료를 관측해 행을 쓰는 주체(백그라운드나 다음 요청)는 없고(`OperatorController.java:676` 주석 "남은 허가는 5분 뒤 만료된다"), PR #97의 엔진 공통화도 이 경로를 더하지 않았다.

**미해소 잔여:** 만료를 다음 요청·조회가 결말 행으로 쓸지, 조회·export가 "결말 없음 + 5분 경과"를 만료로 파생해 보일지. 주인 없음.

### 110. 감사 actor digest에 키 버전·식별자가 없어 키 교체 전후 행을 묶을 수 없다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.1이 append했다. `operations/internal/ActorDigest.java:25-40`은 여전히 키 하나로 HMAC만 만들고 `operations_audit_event`에 키 버전 열이 없다(`V17__operations_audit.sql`). 지목된 Story 3.8은 키 회전을 범위 밖으로 다시 미뤘고(`spec-3-8-blue-green-deploy.md:17`, 항목 126), Story 3.10은 tombstone 사본 머리에만 digest 키 ID를 실었을 뿐 회전을 지원하지 않는다(항목 139). 키 회전 묶음 126·139와 같은 뿌리다.

**미해소 잔여:** 감사 행의 키 ID 열(새 마이그레이션)과 회전 절차. 3.8이 재차 미뤄 주인 없음.

### 111. 운영 command 사유가 자유 텍스트라 개인정보가 영구 원장에 들어갈 수 있고 비식별 범위가 좁다

**부분 해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.1이 append했다. target 쪽은 뒤 스토리가 닫았다 — 사용자에게 귀속된 restriction target은 원장에 keyed digest로만 남고(`operations/internal/RestrictCommand.java:113`·`:138`, Story 3.2), 문의 command의 target도 reference digest이며(`UpdateInquiryCommand.java:186`, Story 3.6), 계정 삭제는 제한 행의 target도 digest로 바꾼다(`AccountErasure.java:100-101`). 이것은 이 항목을 겨냥한 수정이 아니라 각 스토리가 도입한 규칙이다.

**미해소 잔여:** 사유는 그대로다 — 검증은 길이·제어 문자·이메일 모양만 보고(`OperatorEnvelopeService.java:61`·`:184-186`), 3.6의 민감값 규칙 `InquiryRules`는 공지 메시지에만 쓰이며(`:197`) 사유에는 쓰이지 않는다. `eraseActor`는 `actor_user_id`·`actor_erased_at`만 바꾼다(`OperatorAuditRepository.java:274-279`). 사유 필드의 보존·삭제 분류는 법정 분리 보존 결정(G-5)이 소유한다.

### 112. 운영 제한을 만난 계획 조건 조회가 열린 intent를 되돌릴 수 없게 무효화한다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.2가 append했다. `backend/src/main/java/com/bokrilog/plan/internal/PlanConditionService.java:94-96`의 `state()`는 지금도 제한 판정(`eligibleRestrictions`)이 거짓이면 `repository.invalidateOpen(userId)`를 부른다. 결정 지점으로 지목한 3.4(해제)·3.5(공지)는 둘 다 이 동작을 바꾸지 않고 `done`이 됐다.

**미해소 잔여:** 제한 중 intent를 보존할지, 보존한다면 해제 뒤 그 intent의 신선도를 어떻게 판정할지. 지목 스토리가 지나가 주인 없음(`## 갱신 규칙` (나) 부류).

### 113. 강한 신호 1·8·9번의 감지 지점 연결

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** 3.3 분할 결정(2026-10-09)이 append했다. 판정표에는 세 신호가 있지만(`operations/SignalType.java`의 `WRITE_KEY_STORED`·`SCHEMA_VALIDATION_BURST`·`CREDENTIAL_DECRYPTION_BURST`) 운영 소스에서 이 셋을 올리는 자리는 없다 — 운영 소스가 `SignalType.*`로 올리는 것은 다섯 종(`RESULT_HASH_MISMATCH`·`PORTFOLIO_INVARIANT_VIOLATION`·`SOURCE_ID_CONFLICT`·`UNKNOWN_TRANSACTION_ENUM`·`SECRET_EXPOSED`)뿐이고 `SignalType.java:15-18` javadoc도 "나머지는 감지 지점이 후속 스토리"라 적는다. 원문이 짚은 선행 조건(readOnly 근거, cursor 반복과 섞인 `RESPONSE_FORMAT`, 키 부재와 태그 불일치 구분, 시간 창 집계 저장소)도 그대로다.

**미해소 잔여:** 원인 분리와 시간 창 집계 저장소. 소유가 이름 없는 "3.3 후속"이라 주인 없음. 쓰기 가능 key 저장(1번)은 읽기 전용 원칙의 마지막 감지선이므로 Bybit 실연동 게이트 전에 볼 후보다.

### 114. 강한 신호 2번(교차 사용자 접근·공식 pointer 혼합)은 안전한 감지 지점이 없다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** 3.3 분할 결정이 append했다. `CROSS_USER_ACCESS`(`SignalType.java:24`)를 올리는 운영 코드는 없고, 대시보드 계보 불일치는 여전히 무결성과 무관한 원인과 같은 결말(`Dashboard.Unavailable`)로 접힌다. 오탐 하나가 전역 정지가 되므로 원인을 구분한 단정이 먼저라는 원문의 전제가 그대로다.

**미해소 잔여:** 교차 접근 "성공"을 관측하는 단정 설계와 계보 불일치의 원인 분리. 주인 없음.

### 115. 강한 신호 3번(secret/token 노출)을 telemetry 경로와 함께 연결한다

**부분 해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** 3.3 분할 결정이 append했고 지목된 Story 3.7이 절반을 닫았다. 실제 구성 비밀값(8자 이상)이 로그에 나오면 그 줄을 가리고 `SECRET_EXPOSED`(`ruleId=CONFIGURED_SECRET_IN_LOG`)를 비동기 1회·60초 억제로 올린다(`backend/src/main/java/com/bokrilog/ConfiguredSecrets.java:150-154`, 루트 `LogSecretGuard`). 실경로 근거는 `LogRedactionTest.java:101`의 `구성_비밀값이_나오면_그_줄을_가리고_신호_하나를_올리며_60초_안의_반복은_신호를_더하지_않는다`다 — 실제 로그 한 줄로 가림을 확인하고 `operations_signal`에 `SECRET_EXPOSED` 행(`CALCULATION_DISPLAY`·`NEW_CONNECTION`·`SYNC` 전역)과 열린 `SIGNAL` 제한 셋이 생기는 것까지 DB로 단정하며, `backend-quality`에서 돈다.

**미해소 잔여:** 외부 telemetry·DLP canary 검출은 없다(Grafana Alloy 연결은 3.8이 다시 미뤘다, 항목 121·126). 패턴 탐지는 3.7 계약상 신호가 아니다. 일회성 역할의 가림·신호와 tombstone digest 키의 감지 목록 누락은 Epic 3 회고 AI-3(`sprint-status.yaml`, open)이 소유한다.

### 116. 강한 신호로 제한되면 사용자 API가 자기 수집 상태를 빈 값으로 감춰 무엇이 왜 멈췄는지 알 수 없다

**부분 해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.3 리뷰가 append했고 지목된 Story 3.5가 "무엇이 멈췄는지"를 말하게 했다. `GET /api/notices`는 공지 없는 제한도 `RESTRICTED_WITHOUT_NOTICE` 항목과 영향 기능으로 싣고(`ServiceNoticeController.java:59`·`:176`) 대시보드·설정 상단 영역이 그것을 보인다. 서버 쪽은 `ServiceNoticeTest.java:474` 부근이 공지 없는 제한의 응답 항목(`kind` `RESTRICTED_WITHOUT_NOTICE`, 영향·계속 가능 기능)을 단정하고 `backend-quality`에서, 화면 쪽은 `frontend/src/components/ServiceNotice.test.tsx:124`의 `공지 없는 제한은 운영 제한 표지와 기본 문장이고 연락처가 없으면 링크가 없다`가 그 렌더링을 단정하고 `frontend-quality`에서 돈다.

**미해소 잔여:** 사용자 API는 그대로 빈 값을 낸다(`sync/internal/SyncJobService.java:299-302`의 `initialCollectionJob`, 판정 `:830-833`이 `syncRestricted`를 본다). 공지 영역이 없는 화면 — 첫 스냅샷 패널이 있는 `/onboarding`, 초기 수집이 있는 `/plan-conditions` — 에서 제한을 만나면 설명이 없다(`useServiceNotices` 사용처는 대시보드·설정 둘뿐). 주인 없음.

### 117. Story 3.4 AC-6 훈련 증거(수동 전역 정지 5분·자동 정지 60초·선택 범위 복구)

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** 3.4 분할 결정(2026-10-10)이 append했고 운영 배포 증거(Story 3.8)와 함께 다루기로 했는데, 3.8은 훈련 증거를 다시 범위 밖으로 뺐다(`spec-3-8-blue-green-deploy.md:17`, 항목 126). 운영 소스·`deploy/`에 훈련 기록 경로는 없다. Epic 3 회고가 실제 훈련 증거 0건을 확인했고(`epic-3-retro-2026-10-13.md`) 이 게이트를 기계 판독 자리에 두는 일을 AI-9로 남겼다(`sprint-status.yaml`, open). 운영 훈련 묶음 126·135와 같은 뿌리다.

**미해소 잔여:** 실제 훈련과 리허설을 가르는 기록 형식과 베타 전 첫 훈련. 소유는 출시 게이트(Epic 3 회고 AI-9)다.

### 118. 운영 command 무장(`OperatorCommandFlow.Armed`)의 직렬화 버전이 스토리마다 오른다

**해소(2026-10-13, `spec-operator-command-commons.md`).** 원인은 command마다 `Armed`에 nullable 칸(`restriction`·`lift`·`notice`·`inquiry`)을 하나씩 더하던 패턴이었다. 이제 `Armed`는 그 command의 검증된 요청 하나(`Serializable payload`)만 들고, 모양은 `operations`의 command 전략만 안다 — 새 command가 `Armed`의 칸을 늘리거나 그 `serialVersionUID`를 올리지 않는다. 이 전환에서 한 번 5→6으로 올렸고, 배포 전에 무장한 세션은 지금처럼 재준비를 요구받는다(운영 서비스 전이라 호환 역직렬화를 두지 않는다 — SPEC Boundaries의 결정). 배포 전 무장은 `OperatorArmingVersionTest`가 실제 JDBC 세션 저장소로 재현한다(옛 모양·없는 클래스·깨진 바이트 → 무장 없는 확정과 같은 403, 그 속성만 지움). 확인(리뷰 patch 뒤 최종 실행, 2026-10-13): `backend/gradlew --project-dir backend build` 1465건 통과(`OperatorCommandTest`·`OperatorRecoveryTest`·`ServiceNoticeTest`·`SupportInquiryTest` 기존 기대값 무변경), `verify-same-origin.sh` 1051/1051, 주입 origin Playwright 68건 통과.

**잔여:** blue/green rollback 창에서 옛·새 인스턴스가 서로의 무장을 읽지 못하는 것은 이 한 번의 전환에서 그대로다. 각 payload record(예: 공지 발행의 `Notice`)는 자기 `serialVersionUID`를 가지므로, 그 command의 요청 모양이 바뀌면 그 command의 무장만 재준비를 요구한다.

### 119. 운영자 화면의 운영 command 상태 기계가 사본으로 불어났다

**해소(2026-10-13, `spec-operator-command-commons.md`).** 상태 기계(단계 `idle·preparing·armed·confirming·completed·refused·cancelled`, 멱등 키 생성·재생성, 이중 제출 방지, 응답 없는 확정의 uncertain, 문구 불일치의 무장 유지, 목록 콜백, 취소)는 `frontend/src/session/useOperatorCommand.ts` 하나이고, 다섯 훅(`useOperator{Export,Restriction,Recovery,Notice,Inquiry}`)은 command 설정(이름·본문·멱등 입력·해석기·목록을 바꾸는 code)만 넘긴다. `operatorApi.ts`의 준비·확정·취소 다섯 벌은 command 이름을 받는 한 벌(`prepareCommand`·`confirmCommand`·`cancelCommand`)과 command별 응답 해석기가 됐고, 목록 sequence·중단은 `session/useSequencedRead.ts` 하나다. 백엔드의 같은 사본(확정 prologue 다섯·`instanceof` 분기·컨트롤러 switch)도 command 전략과 등록표로 묶었다(Epic 3 회고 D-1·AI-5). 확인(리뷰 patch 뒤 최종 실행, 2026-10-13): 프런트엔드 typecheck·lint·lint:css·format:check·build·Vitest 1378건(`useOperatorCommand.test.tsx` 12건·`useSequencedRead.test.tsx`·`useOperatorInquiry.test.tsx` 포함, 기존 `OperatorScreen`·`OperatorNotice`·`OperatorInquiry` 시험 기대값 무변경)·Playwright 40건, 주입 origin Playwright 68건(`e2e/operator.spec.ts` 포함)·`verify-same-origin.sh` 1051/1051·`test-ci-scripts.sh` 239/239 통과.

### 120. 운영자의 답변 정정과 사용자의 후속 응답·재개 경로가 없다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.6이 append했다. 전이는 지금도 단조다 — `backend/src/main/java/com/bokrilog/operations/SupportInquiryApi.java:96-100`의 `canMoveTo`가 같은 상태·역행을 거부하고 `UpdateInquiryCommand.java:160`(준비)·`:224`(확정 잠금 뒤)가 그 판정을 부르며, `SupportInquiryTest.java:431-435`가 `ANSWERED → ANSWERED`("다시 답변")를 409 `INQUIRY_TRANSITION_INVALID`로 계약 고정한다. 사용자 표면은 `SupportInquiryController.java:132`(`GET`)·`:150`(`POST` 접수) 둘뿐이라 후속 응답·해결 표시·재개 경로가 없다. `EPIC-IMPLEMENTATION-READINESS.md` §7 `SUPPORT-1.0`도 정정·대화형 스레드를 정하지 않는다.

**미해소 잔여:** 정정(`ANSWERED → ANSWERED`) 허용 여부와 그때의 감사·첫 답변 시각 보존 규칙, 사용자 후속 응답을 새 문의로 둘지 스레드로 둘지. 원문은 "실제 운영 데이터가 쌓인 뒤 판단"이라는 조건만 적고 스토리를 지목하지 않았다 — 주인 없음(`## 갱신 규칙` (가) 부류).

### 121. Story 3.7의 운영 관측 연결(Alloy·Grafana Cloud·synthetic·로컬 보조 신호)

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.7이 범위 분리로 append했다(source_spec 없음). 저장소에는 수집기가 아직 없다 — `deploy/production/preflight.sh:53-54`가 "Grafana Alloy는 아직 compose에 없지만(후속 작업)"이라 적고 256MiB를 예약으로만 계상하며, `deploy/production/compose.yaml:23-24`·`deploy/compose/compose.yaml:5-6`도 같은 사실을 적는다. 경보 규칙(`deploy/observability/alerts.yml:28-47`의 liveness probe·`AlloyHeartbeatMissing`)은 정의돼 있지만 시계열을 낼 수집기·synthetic이 없어 운영에서는 `...Unmeasured`만 울릴 수 있다. 운영 compose의 `x-logging`(`compose.yaml:44-48`, json-file 10m×5)이 로컬 회전 로그 구실을 하지만 그것을 Grafana 장애 시 보조 신호로 쓰는 절차·검사는 없다. 항목 126이 이 묶음을 "3.8 보완 작업"으로 다시 넘겼고 115·132·138과 같은 뿌리다.

**미해소 잔여:** Alloy 서비스·relabel allowlist, Grafana Cloud 전송, 외부 synthetic 5분 검사, heartbeat 실측, 로컬·OCI 보조 신호 절차. 넘겨받은 "3.8 보완 작업"은 `epics.md`·`sprint-status.yaml` 어디에도 없어 주인 없음이고, 외부 계정은 `externalLaunchGates`의 `production-secrets-and-accounts`가 소유한다.

### 122. Web Vitals(LCP·INP·CLS p75) 수집

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.7이 범위 분리로 append했다(source_spec 없음). 브라우저 계측은 없다 — `frontend/package.json:23-26`의 런타임 의존은 `react`·`react-dom` 둘이고 `frontend/src`에서 `PerformanceObserver`·`web-vitals`는 0건이며, `sendBeacon`은 시험이 그것을 막는 `frontend/src/test/setup.ts:610-614`에만 나온다. 동일 출처 beacon 경로·집계 표도 없다. 결정 지점이 이름으로 정해진 적은 없지만 `epics.md` Story 4.4 AC-3(광고가 Web Vitals 목표를 위협하면 성능 kill switch가 광고만 비활성화)이 이 측정을 전제하므로 늦어도 Story 4.4다.

**미해소 잔여:** beacon 경로(`/api` 아래 동일 출처), 측정 방식(직접 구현 또는 런타임 의존 승인), 집계 분모·기간. 명시된 주인이 없으므로 Story 4.4 SPEC이 이 항목을 보거나 그 전에 소유를 붙여야 한다.

**미해소(2026-10-16, Story 4.4 판정, 기준 `ec51014`) — 주인 이전.** 이 항목이 결정 지점으로 지목한 Story 4.4(`spec-4-4-ad-fail-open.md`, PR #113)는 사용자 결정 [S]로 범위를 나눴다. Web Vitals 수집과 성능 kill switch는 4.4에서 떼어 항목 **157**로 append했다. 4.4는 브라우저 계측·beacon·측정 의존을 하나도 더하지 않았다. 사실은 위 판정과 같다.

**미해소 잔여:** 위와 같다. 주인은 항목 157(Web Vitals 수집과 성능 kill switch 후속)이며, 156(광고 이벤트 계측)과 beacon 경로를 함께 설계한다.

**미해소(2026-10-17, Epic 4 회고 AI-4) — 주인 지정.** 주인은 Epic 4 보완 Story 4.5(`epics.md` `### Story 4.5`, 광고 이벤트·Web Vitals 계측과 성능 kill switch)다. 157과 함께 4.5가 수집한다. 코드 사실은 위 판정과 같다.

**해소(2026-10-17, Story 4.5 done 전환, 기준 `ae06d92`).** 인증된 핵심 화면 넷(대시보드·설정·계획 조건·중립 전환 화면)의 LCP·INP·CLS를 `web-vitals` 6.2.3(SPEC 승인 런타임 의존)으로 잰다. 측정값은 동일 출처 `POST /api/engagement/telemetry`로 보낸다. 서버는 `bokrilog.web.vitals{metric,route,rating,ad}`로 세고, 경보 `WebVitalsAboveTarget`·`WebVitalsUnmeasured`가 promtool 시험과 함께 붙었다. 진입·온보딩 화면은 요청 0건 계약 때문에 재지 않는다 — 4.5 SPEC이 정한 범위다. 지키는 검사는 `ClientTelemetryTest`, 프런트 `beacon.test.ts`·`screens.test.tsx`, 주입 origin e2e `auth-entry.spec.ts` "계측 beacon"(204)이고, PR #119의 `ci-required` 전 job이 성공했다. 운영 Grafana 연결은 이 항목이 아니라 3.8 운영 연결 몫(115·121)이다.

### 123. 운영 작업시간·월 비용 장부

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.7이 범위 분리로 append했다(source_spec 없음). 장부는 없고 경보 규칙만 미측정으로 기다린다 — `deploy/observability/alerts.yml:224`가 "운영 장부가 만들기 전에는 미측정이다(분리된 후속 작업)"라 적고 `:233-240`의 `MonthlyCostUnmeasured`가 `absent(bokrilog_monthly_cost_krw{…})`를 info로 알린다. `backend/src/main`에서 `monthly_cost`는 0건이고 작업시간 입력 표·화면도 없다. 항목 8(주 2시간 목표의 측정 기준, 해소)이 정의만 닫았고 측정 수단이 이 항목에 남았다.

**미해소 잔여:** 운영자 입력 장부(작업시간·월 비용)와 4주 관찰 표시, `bokrilog_monthly_cost_krw`로 내보낼 경로. 실제 비용 자료는 운영 호스트(3.8 운영 배포) 뒤에야 생긴다. 주인 없음.

### 124. Bybit API·사건 유형 계약의 정기 재검증

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.7이 범위 분리로 append했다(source_spec 없음). 정기 실행이 없다 — `backend/src/main`의 `@Scheduled`는 동기화 dispatch·heartbeat와 측정 gauge(`FirstSnapshotWorker`·`SyncQueueAgeMetrics`·`BackupMetrics`·`SupportPendingMetrics`)뿐이고 계약 검사 작업은 0건이다. 원문대로 미인식 유형은 강한 신호 7이 막지만(`sync/internal/StrongSignals.java:24`) schema 변화 감지와 실계정·stub 결과의 구분은 없다.

**미해소 잔여:** 재검증 작업의 주기·결과 저장·경보, 실계정과 stub 결과를 가르는 표지. 실계정 결과는 실제 검증 계정과 regional reserved IP가 있어야 생기므로 결정 지점은 Epic 2 실연동 게이트다(별도 출시 게이트 소유, 스토리 이름은 없다).

### 125. 이미지 공급망 파이프라인(ARM64 빌드·SBOM·cosign 서명·GHCR 게시)

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.8이 사용자 결정(2026-10-12)으로 분리해 append했다(source_spec 없음). 소비 쪽은 3.8 핵심에 들어와 있다 — `deploy/production/preflight.sh:116`이 `cosign`을 요구하고 `:165`의 release 키가 `sbom`·`signature`와 그 SHA-256을 받으며 `:277-288`이 `cosign verify`·`verify-attestation --type spdxjson`을 돈다(리허설만 `:265`에서 건너뛴다). 생산 쪽은 없다 — `.github/workflows/ci.yml`에서 `cosign`·`sbom`·`ghcr`·`buildx`·`id-token`은 0건이고 `DEVELOPMENT.md:554`가 "그 파이프라인이 release manifest를 만들 때까지 운영 배포는 미검증"이라 적는다. gate가 fail-closed라 서명 없는 이미지는 올라가지 못하지만, 그 대신 첫 운영 배포 자체가 이 항목에 막힌다.

**미해소 잔여:** release 브랜치·태그 한정 workflow(ARM64 빌드·SBOM·keyless 서명·attestation·GHCR 게시)와 그것이 만드는 release manifest. 별도 chore로 적혔지만 만들어지지 않았다 — 주인 없음.

### 126. Story 3.8에서 넘어온 운영 연결·강화 묶음

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.8이 사용자 결정(2026-10-12)으로 분리해 append했다(source_spec 없음). 다섯이 모두 열려 있다.

- Alloy·Grafana Cloud·synthetic — 항목 121과 같다(`preflight.sh:53`).
- 3.4 AC-6 훈련 증거 — 항목 117·135와 같다(`restore.sh:56` "훈련 증거 원장에 기록되지 않는다").
- production 비밀 공급 — `DEVELOPMENT.md:506`이 "비밀 공급(누가·어떻게 이 파일을 만들고 돌리는가)은 후속 작업"이라 적는다.
- nginx 컨테이너 권한 강화 — 운영 `deploy/production/compose.yaml:313-339`의 nginx에 `mem_limit`만 있고 `user`·`read_only`·`cap_drop`·`security_opt`가 없다. 항목 22(미해소)가 개발 compose에서 본 상태가 운영 구성에도 그대로다.
- 감사 digest 키 회전 — 항목 110과 같다.

**미해소 잔여:** "후속 3.8 보완 작업"은 `epics.md`·`sprint-status.yaml` 어디에도 스토리로 없어 주인 없음이다. 비밀 공급은 `externalLaunchGates`의 `production-secrets-and-accounts`가, 훈련 증거의 게이트 이름 붙이기는 Epic 3 회고 AI-9(`sprint-status.yaml` `epic-3-retro-item-34`, open)가 결정 지점이다.

### 127. production 진입의 TLS 종단(인증서·443·HTTP→HTTPS)

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.8이 append했다. Nginx는 평문만 받는다 — `deploy/nginx/bokrilog.conf:98`이 `listen 80` 하나이고 `ssl`·`443`·`Strict-Transport-Security`는 0건이며, 운영 `compose.yaml:33`이 "TLS 종단은 후속 작업"이라 적고 `:319`가 80만 게시한다. 세션 쿠키가 `application.yml:73`의 `secure: true`라 TLS 없이 운영 도메인에 올리면 로그인 쿠키가 성립하지 않는다 — 보안 쪽으로 열리지는 않지만 서비스가 동작하지 않는다. `DEVELOPMENT.md:559`도 후속 작업으로 적는다.

**미해소 잔여:** 인증서 발급·갱신 방식, 443 listen과 HTTP→HTTPS 이동, HSTS를 `bokrilog.conf` 상위 `add_header` 한 곳에 두는 일(항목 14의 미채택 헤더 표가 이것을 Story 3.8에 넘겼다), 운영 compose의 게시 포트. 원문의 "production 진입 구성 보완"은 스토리가 아니어서 주인 없음.

### 128. 프런트엔드 산출물·Nginx 설정이 release manifest·rollback 밖이다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.8이 append했다. 운영 nginx는 지금도 checkout에서 마운트한다 — `deploy/production/compose.yaml:321-322`가 `frontend/dist`와 `bokrilog.conf`를 마운트하고 `:37-38`이 "release manifest·digest 고정·rollback의 대상이 아니다(후속 작업)"라 적으며, `preflight.sh:165`의 release 키에도 화면 산출물·Nginx 설정이 없다. 원문대로 3.8은 이 한계를 `DEVELOPMENT.md:482`에 명시만 했다.

**미해소 잔여:** 정적 산출물을 이미지나 digest 고정 artifact로 release에 묶고 rollback 대상에 넣는 결정. 항목 125의 공급망 파이프라인과 함께 하는 편이 싸다. 주인 없음.

### 129. 배포 리허설의 실제 호환 창과 실제 JVM 종료 경로

**미판정(2026-10-15, 판정 라운드, 기준 `189c16b`) — 보류: CI chore.** 리허설 구조(migration 집합이 다른 두 release 이미지·실제 Worker 종료 코드)를 바꾸는 일이 진행 중인 CI 임계 경로 chore(Epic 3 회고 AI-12, `epic-3-retro-item-37`) 범위와 겹쳐, 판정은 그 chore의 done 전환 PR이 더한다(`CLAUDE.md` 표준 흐름 7번).

**미해소(2026-10-15, CI 임계 경로 chore 판정, 기준 `571bb3c`).** 이 chore는 배포 리허설 시나리오를 바꾸지 않았다 — SPEC Never가 `deploy/production/**` 변경을 막았고, 바뀐 것은 백업 리허설을 `deploy-rehearsal`에서 떼어 낸 job 경계와 그 job timeout(70 → 30분, `.github/scripts/test-ci-scripts.sh:1770`)뿐이다. 리허설의 두 이미지는 여전히 같은 jar이고 drain 미완료는 결함 주입(`drain-incomplete`)으로만 덮는다.

**미해소 잔여:** migration 집합이 다른 두 release 이미지와 실제 Worker 종료 코드 75·76 경로를 리허설에 더하는 일. 주인 상실 — 운영 배포 smoke를 출시 게이트로 두는 Epic 3 회고 AI-9(`epic-3-retro-item-34`)와 가깝지만 그 액션은 게이트의 자리만 정한다.

### 130. `verify-same-origin.sh`의 `fetch` argv 본문 가드를 행동 시험으로 고정한다

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** `spec-verify-windows-utf8-body.md`가 append했다. 가드는 그대로 있다 — `deploy/compose/verify-same-origin.sh:382-424`의 `fetch()`가 `--data-raw`·`-F`·`@` 밖의 `--data*` 값을 거부하고 상태를 `000`으로 남긴다. 그러나 `.github/scripts/test-ci-scripts.sh`가 이 스크립트에 대해 보는 것은 호출 존재(`:1450-1460`)뿐이고 그 파일에서 `data-raw`·`request_body`는 0건이라, 가드 패턴이 깨지거나 지워져도 실패하는 시험이 없다. 항목 78·32와 같은 뿌리다(Linux CI가 Windows 결함을 보지 못한다).

**미해소 잔여:** curl을 기록 함수로 가린 행동 시험(거부 형태는 실패·미호출, `@<파일>` 형태는 통과)을 `test-ci-scripts.sh`에 더하는 일. 원문이 그 fix의 Ask First 경계 밖이라 미뤘고 주인 없음이다 — CI 스크립트를 만지는 chore가 함께 가져가기 좋은 크기다.

### 131. 백업 리허설의 CI 경로 분기와 `deploy-rehearsal` timeout 보정

**미판정(2026-10-15, 판정 라운드, 기준 `189c16b`) — 보류: CI chore.** 경로별 job 분기와 timeout 보정은 진행 중인 CI 임계 경로 chore(Epic 3 회고 AI-12, `epic-3-retro-item-37`)가 직접 다루므로, 판정은 그 chore의 done 전환 PR이 더한다(`CLAUDE.md` 표준 흐름 7번).

**해소(2026-10-15, CI 임계 경로 chore 판정, 기준 `571bb3c`).** `spec-ci-critical-path.md`(PR #104)가 닫았다. 경로 분기는 원문의 관련 경로 허용 목록 대신 fail-closed 건너뛰기 목록이다 — `.github/scripts/classify-changes.sh:125-148`이 PR의 변경이 전부 `frontend/**`·`_bmad-output/**`·최상위 `*.md`일 때만 `backup-restore=false`를 내고, 목록 밖 경로·비교 기준 없음·빈 diff·push·dispatch는 `true`다. 백업 리허설은 `deploy-rehearsal`에서 떨어진 `backup-rehearsal` job(`.github/workflows/ci.yml:537`)이 되어 `restore-rehearsal`과 같은 게이트(`:542`·`:605`)를 쓰고, `aggregate-results.sh`가 두 job의 기대치를 그 분류로 정한다. timeout은 원문의 "배포 job 25 → 50분" 대신 배포 job 30분·백업 job 50분이다(백업이 빠졌으므로). 지키는 시험은 `test-ci-scripts.sh`의 분류 경계 사례(`:549`·`:555`·`:567-576`, push `:671`), 집계 사례(`:891` 이하 — 프런트엔드 PR의 skipped 통과, 분류·결과 불일치 종료 1, 옛 분류기 빈 값), job timeout 단정(`:1770`·`:1802`)이고 모두 `changes` job에서 돈다(299건). PR #104의 두 CI 실행은 옛 base 분류기의 빈 값 → 실행 경로로 세 리허설을 모두 돌아 green이었고, 건너뛰기 경로는 머지 뒤 첫 프런트엔드-only PR에서 처음 실제로 쓰인다. `backend-quality` 뒤 꼬리(마지막 리허설 job 종료까지)는 13분 14초·10분 16초였다(PR #100은 15분 29초).

### 132. 백업 저장소 사용률의 분모·분자를 계정 단위 실제 사용량으로

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.9가 append했다. 분자는 백업 object bytes, 분모는 운영자가 적은 quota 그대로다 — `backend/src/main/java/com/bokrilog/operations/internal/BackupMetrics.java:124-125`가 `measured.bytes() / measured.quotaBytes()`를 gauge로 내고, 그 값은 `deploy/production/backup.sh:888`·`:983`의 `MEASURED` 행이 `backup.env`의 `quota_bytes_<store>`(`backup.sh:204-207`)로 채운다. 경보는 `deploy/observability/alerts.yml:75-85`의 70%·85%다. 같은 계정의 다른 bucket 사용량은 어디에도 들어오지 않는다.

**미해소 잔여:** OCI·외부 업체의 계정 지표를 읽는 경로. 운영 관측 연결(항목 121·126) 뒤에야 생기며 주인 없음.

### 133. 백업 기록 대기열(`pending/`) 재전송의 행 단위 멱등

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.9가 append했다. 서비스는 `SUCCEEDED` 중복만 거절한다 — `backend/src/main/java/com/bokrilog/operations/internal/BackupRecordService.java:69-71`이 이미 성공한 generation의 `SUCCEEDED`만 예외로 막고 나머지 행은 `:72`가 그대로 append하며, `PURGE_FAILED`·`PURGE_OVERDUE`의 감사 행(`:74-90`)도 같다. `V24__operations_backup_event.sql:73`·`:75`의 색인은 비유일이라 DB도 막지 않는다.

**미해소 잔여:** 행 단위 멱등 키(묶음 ID와 행 순번 등)와 유일 제약. 새 migration이 필요해 SPEC 승인 대상이다. 주인 없음.

### 134. 새 송신 IP 감지(`BYBIT_IP_REBIND_REQUIRED`)

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.10이 사용자 결정(2026-10-13)으로 분리해 append했다(source_spec 없음). `backend/src`·`frontend/src`·`deploy`에서 `REBIND`는 0건이다. 호스트가 바뀌어 IP가 거부되면 지금은 `source/internal/BybitAdapter.java:983`·`:1013`의 HTTP 403 회로가 모든 Bybit 호출을 막고 제공자 장애로 답한다 — fail-closed이긴 하지만 사용자는 "키의 IP를 다시 묶어야 한다"는 원인을 보지 못한다. `DEVELOPMENT.md:643`이 후속으로 적는다.

**미해소 잔여:** 송신 IP 변경의 판정 근거, 대시보드·설정 상태 표시, sync admission 차단. "별도 스토리 보완"은 `epics.md`에 자리가 없어 주인 없음. 항목 136(호스트 전환)이 일어나면 함께 성립한다.

### 135. 운영 훈련 증거 원장(3.10 AC-6 복원 훈련, 3.4·3.8 훈련과 공통)

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.10이 사용자 결정(2026-10-13)으로 분리해 append했다(source_spec 없음). 원장이 없고 스크립트가 그 사실을 스스로 말한다 — `deploy/production/restore.sh:56`·`:545`가 "이 실행은 훈련 증거 원장에 기록되지 않는다(실제 복원 미검증)"를 출력한다. 항목 117(3.4 AC-6)·126과 같은 뿌리이고, Epic 3 회고가 운영 증거 셋이 모두 실제 증거 0건임을 확인했다.

**미해소 잔여:** 실제 훈련과 리허설을 구분하는 증거 원장의 모양(시작·완료·실패·RPO·수동 작업시간·결함)과 기록 경로. 구현 주인은 없고, 게이트 이름 붙이기는 Epic 3 회고 AI-9(`epic-3-retro-item-34`, open)가 결정 지점이다. 같은 원장을 필요로 하는 첫 스토리는 Story 5.4 AC-5(실제 격리 환경 삭제·복원 통합시험 감사)다.

### 136. 호스트 전환 runbook(OCI A1 확보 8시간 실패 시 PAYG·유료 VPS)

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.10이 사용자 결정(2026-10-13)으로 분리해 append했다(source_spec 없음). `DEVELOPMENT.md`와 `deploy/`에서 `PAYG`·`VPS`는 0건이다. runbook은 저장소 코드 변경이 아니고 운영 계정·호스트가 있어야 실행으로 검증된다. 전환이 일어나면 항목 134(새 송신 IP)가 함께 성립한다.

**미해소 잔여:** 전환 판단 시각, 대상 호스트 준비, DNS·reserved IP·Bybit 키 재바인딩 순서를 담은 runbook. "운영 호스트 준비"는 스토리가 아니어서 주인 없음이고, 가장 가까운 게이트는 `production-secrets-and-accounts`다.

### 137. 격리 복원 환경의 실제 OIDC 왕복과 승인 계정의 read-only safe sync

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.10이 append했다. 격리 복원은 외부 호출을 끈다 — `deploy/production/restore.sh:37`이 원칙을 적고 `:358-371`이 `BOKRILOG_PUBLIC_BASE_URL`·`BOKRILOG_OPERATORS`·`BOKRILOG_BYBIT_*`와 세 제공자의 `CLIENT_ID`·`CLIENT_SECRET`을 빈 값으로 덮는다. 확인은 로그인 설정 존재·자격정보 복호화 수·세션 폐기까지다.

**미해소 잔여:** 실제 왕복. 승인된 시험 계정과 reserved IP가 필요하므로 주인은 Epic 1 실연동 게이트(세 제공자 왕복)와 Epic 2 실연동 게이트(Bybit)다. 복원 환경에서 같은 OIDC identity가 삭제 계정을 되살리지 못함은 Story 5.4 AC-2·AC-5가 실제 격리 환경 시험으로 요구한다.

### 138. tombstone 내보내기의 평시 관측(마지막 성공 gauge·정체 경보·`OnFailure`)

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.10이 append했다. 평시 신호가 없다 — `deploy/production/systemd/bokrilog-tombstone-export.service`에 `OnFailure=`가 없고 `alerts.yml`·`TelemetryAllowlist`에서 tombstone은 0건이다. 정체는 복원 당일에야 `restore.sh`의 `TOMBSTONES_STALE`로 드러난다. 그런데 `restore.sh`의 `--allow-stale-tombstones`는 경고만 남기고 계속하며 스스로 "그 사이의 삭제는 이 복원에 없다"고 적는다 — 내보내기가 멈춘 채 이 플래그로 복원하면 그 기간에 삭제된 계정이 되살아날 수 있어 삭제 정확성에 닿는다.

**미해소 잔여:** 마지막 성공 시각 gauge(또는 외부 object 시각 측정)·정체 경보·systemd `OnFailure` 알림. 메트릭은 운영 관측 연결(항목 121·126) 뒤에야 쓸모가 있다. 명시된 주인은 없고, 같은 위험을 다루는 첫 스토리는 Story 5.3(AC-4 기한 임박·초과를 완료로 표시하지 않고 alert·감사)이다.

**부분 해소(2026-11-06, Story 5.3 판정, 기준 `d578cc4`).**
- **5.3이 더한 것.** 내보내기 성공을 기록한다(V32 `operations_tombstone_export`, 업로드·대조·`latest` 갱신 뒤 기록 모드). Worker·`all`의 `BackupDestructionJudge`가 마지막 성공 시각을 gauge `bokrilog_tombstone_export_last_success_timestamp_seconds`로 낸다. 경보는 `TombstoneExportStale`(1시간, critical — 복원 `TOMBSTONES_STALE`과 같은 기준)과 `TombstoneExportUnmeasured`(info)이고 promtool 시험이 고정한다. 노출 이름은 `BackupDestructionJudgeRoleTest`가 Prometheus 출력으로 단정한다.
- **그대로인 것.** `bokrilog-tombstone-export.service`에 systemd `OnFailure=` 알림은 없다. 메트릭도 운영 관측 연결(항목 121·126) 전까지는 실제로 읽히지 않는다.

**부분 해소 잔여:** systemd `OnFailure` 알림과 운영 관측 연결. 주인 없음(운영 연결 묶음과 함께).

### 139. tombstone digest 키의 회전 절차

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.10이 append했다. 회전은 지원하지 않고 불일치를 거부만 한다 — `deploy/production/tombstone-export.sh:232-233`이 파일 머리의 키 ID를 읽고 `:254-256`이 앞선 사본과 다르면 `TOMBSTONE_KEY_CHANGED`로 latest를 바꾸지 않고 멈춘다. digest는 원문 ID 없이 다시 계산할 수 없어 회전에는 살아 있는 계정 전체를 두 키로 대조하는 겹침 절차가 필요하다는 원문 진단이 그대로다. 항목 110(감사 digest 키 회전)·126과 같은 뿌리다.

**미해소 잔여:** 겹침 기간의 두 키 계산, outbox·외부 사본의 key id 전환, 이전 키 폐기 시점. 주인 없음 — 가장 가까운 결정 지점은 Story 5.3(tombstone 수명·권한)이다.

**미해소(2026-11-06, Story 5.3 판정, 기준 `d578cc4`).** 5.3은 tombstone 수명·권한을 넓혔지만(보존 연장, 미완료 행 보존, API 키 금지) 키 회전은 SPEC Never로 두었다. 사본 줄 형식은 여덟 칸이 됐지만 파일 머리의 키 ID 대조(`TOMBSTONE_KEY_CHANGED`)는 그대로다. 미완료 tombstone이 기한 없이 남을 수 있어 회전 절차가 다뤄야 할 행의 수명은 더 길어졌다.

**미해소 잔여:** 위와 같다. 다음 결정 지점이 없어 주인 없음.

### 140. 복구 manifest와 실제 저장소 상태의 정기 대조

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 3.10이 append했다. 검사기는 manifest 자체만 본다 — `deploy/production/check-recovery-manifest.sh:1-30`의 머리 주석이 범위를 필수 키·값 종류·평문 비밀 거부로 한정하고, `:16`이 `backup_key_ids`를 "저장소에 남은 generation이 쓰는 것 전부"라 정의하지만 저장소 목록과 대조하지 않는다. tombstone recipient와 짝 identity의 대조도 없다. 빠진 키는 복원 당일 `restore.sh`의 `KEY_UNAVAILABLE`에서야 드러난다.

**미해소 잔여:** 운영자 환경에서 도는 정기 점검(저장소 generation의 key ID ⊆ manifest `backup_key_ids`, recipient↔identity 짝). 실제 manifest가 저장소 밖에 있어 CI 검사로는 닫히지 않는다. 주인 없음.

### 141. `operations_restriction.reinstated_from` 색인

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** `spec-operator-command-commons.md`(PR #97)가 append했다. 색인은 아직 없다 — `V20__operations_restriction_lift.sql:30`·`:32-33`이 FK·CHECK만 두고 V21~V25에서 `reinstated_from`은 0건이다. 원문 진단을 조금 좁힌다 — 내려가는 `DESCENT`(`operations/internal/OperationalRestrictionRepository.java:183-186`, `ON c.reinstated_from = d.id`)만 이 열로 조인하고, 올라가는 `ASCENT`(`:192-195`)·`LINEAGE`(`:49-51`)는 PK로 조인하므로 색인이 없어도 순차 탐색하지 않는다(정적 판독이며 실행 계획은 확인하지 않았다). 색인만 더하는 migration의 선례는 `V23__sync_queue_age_index.sql`이다.

**미해소 잔여:** 부분 색인을 더하는 새 migration. 원문대로 SPEC Ask First 대상이고, 지금은 행 수가 작아 출시를 막지 않는다. 주인 없음.

### 142. `OperatorArmingVersionTest`에 실제 v5 직렬화 바이트 fixture

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** `spec-operator-command-commons.md`(PR #97)가 append했다. 시험은 지금도 현재 빌드의 stream을 고쳐 옛 모양을 흉내 낸다 — `backend/src/test/java/com/bokrilog/OperatorArmingVersionTest.java:21-38`(칸 이름 변경·없는 클래스)과 `:156`(`serialVersionUID` 8바이트 교체)이 그 방식이고, `backend/src/test/resources` 디렉터리 자체가 없다. 항목 118의 잔여(blue/green rollback 창의 무장 비호환)와 같은 축이다. 운영 서비스 전이라 배포된 v5 무장은 실재하지 않으므로 지금 이 fixture의 실익은 "흉내가 실제 stream과 같다"는 확인에 그친다.

**미해소 잔여:** 이전 커밋(`OperatorCommandApi$Lift` 등이 있던 빌드)에서 뜬 바이트를 fixture로 더하는 일. 첫 운영 배포 뒤에는 그 시점의 무장 모양을 fixture로 남기는 규칙이 더 쓸모 있다. 주인 없음.

### 143. Bybit source 정책 버전이 첫 연결과 재연결 사이에 바뀐 경우

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.17이 append했다. 원문대로 잠복 상태다 — 정책 버전은 `source/internal/BybitAdapter.java:72`의 `bybit-readonly-uta-1.0` 하나뿐이고, 버전이 오르면 `plan/internal/PlanConditionService.java:504`·`:509`(`eligibleSnapshot`)의 정책 버전 일치 조건이 같은 계정 재연결의 기준 snapshot을 비워 `:96`에서 `NOT_ELIGIBLE`이 된다. 2.17 머지 뒤 바뀐 코드는 없다(`git log 0c391cb..189c16b`는 문서-only `e7ff86f` 하나).

**미해소 잔여:** 재기준(rebase) 허용 조건과, 근거에 새 연결 ID와 기준 snapshot 정책 버전을 한 쌍으로 보이는 표시 규칙. 주인을 "정책 버전을 올리는 스토리"라는 조건으로 적었으므로 `## 갱신 규칙`의 (가) 부류다 — 스프린트 경계 재확인 목록(23·27)에 함께 든다.

### 144. 다른 계정 연결 뒤 완료 계획의 비교 보류와 종단 계획 대상의 범위

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** Story 2.17이 append했다. 동작은 2.17 그대로다 — 완료 비교는 `sync/internal/PlanPublicationService.java:798`의 `COMPARISON_PENDING`으로 시작하고, `PlanSyncService.java:327`이 관측이 없거나 보류이면 완료 관측을 계속 기다리며, 다른 계정이 연결돼 있으면 `PlanSyncGuard.java:204-209`가 `CONNECTION_UNAVAILABLE`을 내므로 이전 계정을 다시 관측할 수 없어 보류가 끝나지 않는다. 계획 대상은 `plan/internal/PlanConditionService.java:262-263`·`:276-285`의 `withCurrentConnection`이 상태와 무관하게(ACTIVE 한정 없이) 같은 계정의 지금 연결로 바꾸고, 다른 계정이면 기록된 연결로 되돌아간다.

**미해소 잔여:** 보류를 영구 보류로 둘지 "관측 불가"로 닫을지, 종단 계획의 `planTarget` 대체를 ACTIVE에만 둘지. 값이 틀리게 보이지는 않고(보류로 남을 뿐) 주인 없음.

### 145. `deferred-work.md` 항목 수와 원장 절 수·상태 표 합계를 대조하는 Docker 없는 검사

**미해소(2026-10-15, 판정 라운드, 기준 `189c16b`).** 이 판정 라운드(`spec-deferred-work-verdict-round.md`)의 리뷰가 append했고 같은 PR에서 판정한다(`CLAUDE.md` 표준 흐름 7번). 지금 건수 정합은 사람이 센다 — `.github/scripts/`에 `deferred-work.md`나 이 원장을 읽는 스크립트가 없고 `test-ci-scripts.sh`도 둘을 보지 않는다. 그 결과가 실제로 났다 — 2026-10-13 갱신이 141·142를 세지 않았고 그 다음 done 전환(PR #101)이 번호를 잘못 적었으며, 이 라운드의 갱신 문단이 그것을 손으로 정정했다. 이 chore는 SPEC 경계상 `ci.yml`·스크립트를 바꾸지 않는다. 항목 36(인용 해석 검사)의 세 축 가운데 "원장 절 집합과 `deferred-work.md` 항목의 개수·순서 동등"과 같은 것이고 37(소유자·확인일 표)의 정합 검사와도 이어진다.

**미해소 잔여:** `- source_spec` 수 = `### N.` 절 수(1부터 연속) = 상태 표 네 칸 합을 단정하는 셸 검사와 그 계약 시험. 문서-only 경로도 `changes` job을 지나므로 붙일 자리는 있다. 주인 없음 — CI 쪽 후속(진행 중인 CI 임계 경로 chore 또는 그 뒤 chore)이 가져가기 좋은 크기다.

**미해소(2026-10-15, CI 임계 경로 chore 판정, 기준 `571bb3c`) — 재확인.** 잔여가 "진행 중인 CI 임계 경로 chore"를 가져갈 후보로 짚었지만 그 chore는 범위(리허설 분기)상 가져가지 않았다 — `test-ci-scripts.sh`는 여전히 `deferred-work.md`·이 원장을 읽지 않고, 그 done 전환의 건수(146)도 손으로 셌다.

### 146. CI-PLAN §1·§5에 `backup-rehearsal` job과 `backup-restore` 분류를 as-built로 반영한다

**미해소(2026-10-15, CI 임계 경로 chore 판정, 기준 `571bb3c`).** 이 chore(`spec-ci-critical-path.md`, PR #104)의 리뷰가 append했고 그 done 전환 PR에서 판정한다(`CLAUDE.md` 표준 흐름 7번). CI-PLAN은 바뀌지 않았다 — §1 job 구조와 §5 분류 표(`CI-PLAN.md:186-191`)에 `backup-rehearsal`·`backup-restore`가 없는데, `classify-changes.sh`·`aggregate-results.sh` 머리 주석이 정본으로 인용하는 것은 여전히 그 §1·§5다. 결정 변경은 새 버전 발행이어야 하므로 이 chore 범위 밖이었다.

**미해소 잔여:** CI-PLAN as-built 새 버전에 새 job·분류·집계 기대치와 실측 timeout을 반영하는 일. 주인 — Epic 3 회고 AI-10(`epic-3-retro-item-35`, Architect)이 CI-PLAN 차이를 as-built 새 버전에 반영하는 액션이다.

### 147. 광고 동의 결정 행의 빈도 제한·행 수 상한

**미해소(2026-10-15, Story 4.1 판정, 기준 `176190f`).** Story 4.1(`spec-4-1-ad-consent.md`, PR #107)의 리뷰가 이 항목을 append했고, 같은 스토리의 done 전환에서 판정한다(`CLAUDE.md` 표준 흐름 7번).
- **상한이 없다.** `POST /api/engagement/ad-consent`(`AdConsentController.java:101`)는 상태가 바뀔 때마다 결정 행을 하나씩 더한다(`AdConsentRepository.java:77`). 사용자별 빈도 제한도 행 수 상한도 없다.
- **같은 선택은 행을 더하지 않는다.** 그래서 늘어나는 경로는 `GRANTED`와 `DENIED`를 번갈아 보내는 것뿐이다. 각 요청은 CSRF·`Origin`·세션·멱등 키를 지나야 한다.
- **선례는 있다.** 문의(3.6)에는 `INQUIRY_RATE_LIMITED`라는 선례가 있지만, 4.1 SPEC은 rate limit을 범위에 두지 않았다.

**미해소 잔여:** 사용자당 결정 빈도 제한(시간 창과 거절 code), 또는 결정 행 수 상한과 오래된 행 정리 중 하나를 고르는 일. 결정 지점으로 지목된 곳이 없어 주인이 없다. Epic 4의 남은 스토리(4.2~4.4)는 동의 쓰기 경로를 바꾸지 않는다.

### 148. 광고 동의 결정 이력의 보존 기한

**미해소(2026-10-15, Story 4.1 판정, 기준 `176190f`).** Story 4.1(PR #107)의 리뷰가 이 항목을 append했고, 같은 스토리의 done 전환에서 판정한다.
- **결정 행은 계정 삭제 때까지 남는다.** 결정 행(`engagement_ad_consent_decision`, V26)은 추가 전용이다. 지우는 길은 계정 삭제의 `eraseConsents`(`AccountErasure.java:107` → `AdConsentRepository.java:91`) 하나뿐이다.
- **기한 규칙이 없다.** 문의(3.6)의 "해결 뒤 90일 삭제" 같은 기한 규칙이 없다. 기획 문서(PRD·ERD·`epics.md`)도 동의 증거의 보존 기간을 정하지 않는다.

**미해소 잔여:** 동의 증거로 남길 기간과 최소 보존 사이에서 기한을 정하고, 지난 행을 지우는 경로(조회 트랜잭션이 먼저 지우는 문의 방식 등)를 더하는 일. 주인은 외부 게이트 G-4(개인정보 검토, 광고 동의 문구 확정과 같은 게이트)다.

### 149. 광고 transition 참조의 소비·수명

**미해소(2026-10-15, Story 4.2 판정, 기준 `090efb9`).** Story 4.2(`spec-4-2-ad-eligibility.md`, PR #109)의 리뷰가 이 항목을 append했고, 같은 스토리의 done 전환에서 판정한다(`CLAUDE.md` 표준 흐름 7번).
- **재전송이 같은 참조를 돌려준다.** ELIGIBLE 재전송은 관문만 다시 보고 저장된 `transition_ref`를 그대로 돌려준다(`AdEligibilityService.java:118`, 읽기는 `AdAttemptRepository.java:52`).
- **수명·소비가 없다.** 참조에는 만료 열도 소비 상태도 없다. 4.2에는 광고를 시도하는 표면이 없으므로 아직 노출로 이어지지 않는다.

**미해소 잔여:** 4.3이 광고를 시도할 때 transition을 한 번만 쓰게(소비) 하거나 수명을 두어, 같은 결정의 재전송이 세지지 않는 노출이 되지 않게 하는 일. 주인은 Story 4.3이다.

**해소(2026-10-16, Story 4.3 판정, 기준 `61aac2b`).** Story 4.3(`spec-4-3-neutral-ad-transition.md`, PR #111)이 닫았다. 4.2의 재전송이 같은 참조를 돌려주는 계약은 그대로 두고, 노출 쪽에 소비와 수명을 더했다.
- **소비는 한 번이다.** `POST /api/engagement/ad-transition`이 사용자 잠금 → 쓰기 접근 → 행 `FOR UPDATE` 아래에서 판정하고, SKIP이든 SHOW든 `transition_consumed_at`을 조건부 UPDATE로 쓴다(`AdTransitionService.java:52-73`, `AdAttemptRepository.java:125`·`138`, 열은 `V28`). 두 번째 소비는 `CONSUMED`다.
- **수명은 결정 뒤 120초다.** `AdTransitionApi.CLAIM_WINDOW_SECONDS`(DB 시각)를 넘으면 `EXPIRED`이고 소비를 기록한다.
- **시험이 지킨다.** `AdTransitionTest`의 처음 소비만 SHOW·자격 재전송 뒤 소비도 `CONSUMED`(`:68`), 120초 경계(`:121`), 동시 소비 중 하나만 SHOW(`:252`)가 PR #111 CI(`backend-quality`)에서 통과했다.

### 150. 광고 자격 후보의 신선도

**미해소(2026-10-15, Story 4.2 판정, 기준 `090efb9`).** Story 4.2(PR #109)의 리뷰가 이 항목을 append했다.
- **신선도 조건이 없다.** 후보 술어(`PlanSyncRepository.java:146` `publishedManualSync`)는 purpose·trigger·status·`calculation_id`·소유만 본다. 시각 조건도, "지금 활성 계획의 최신 수동 성공" 같은 조건도 없다.
- **횟수는 묶여 있다.** 빈도 제한(30분·하루 3회)이 횟수를 묶는다. 다만 오래된 수동 성공도 처음 판정이면 reservation을 얻는다.

**미해소 잔여:** 결과 보기 클릭 흐름에 맞는 신선도 조건을 정하는 일. 주인은 Story 4.3이다.

**해소(2026-10-16, Story 4.3 판정, 기준 `61aac2b`) — 결정으로 닫음.** 주인인 Story 4.3이 신선도 조건을 정했다. 판정 술어(`publishedManualSync`)에 시각 조건을 더하지 않고, 노출의 신선도를 결정 뒤 120초의 소비 창으로 묶는다(`spec-4-3-neutral-ad-transition.md` Design Notes "소비가 149를 닫는다").
- **근거.** 오래된 수동 성공을 처음 판정해도 얻는 것은 빈도 cap 한 칸과 120초 안에만 쓸 수 있는 참조다. 대시보드는 자기 화면에서 접수한 MANUAL job의 성공에만 판정을 부른다(`CLAUDE.md` 광고 시도 4.3 계약).
- **시험.** 창 경계는 `AdTransitionTest.java:121`이 PR #111 CI에서 지켰다.
- **남는 성질.** 술어가 그대로이므로, 사용자가 직접 API로 오래된 job을 판정하면 cap을 소비할 수 있다. 이것은 결정된 수용이며 새 잔여로 남기지 않는다.

### 151. `BOKRILOG_AD_PROVIDER_ENABLED`의 배포 연결

**미해소(2026-10-15, Story 4.2 판정, 기준 `090efb9`).** Story 4.2(PR #109)의 리뷰가 이 항목을 append했다.
- **설정 파일에만 있다.** 변수는 `backend/src/main/resources/application.yml:244`에만 있고 기본값은 `false`다.
- **배포 경로에 없다.** `BOKRILOG_SUPPORT_CONTACT`는 개발 compose(`deploy/compose/compose.yaml:184`), 운영 compose(`deploy/production/compose.yaml:245`), preflight 이름 목록(`deploy/production/preflight.sh:495`)에 오른다. 이 변수는 그 셋 어디에도 없다.
- **지금은 안전하다.** 기본값이 꺼짐이라 운영 컨테이너는 광고 자격을 내지 않는다.

**미해소 잔여:** 실제 광고 계정·ad unit 승인과 함께 이 변수를 운영 compose와 preflight에 연결하고, 승인 전 켜짐을 막을지 정하는 일. 주인은 광고 출시 게이트(`AD-GPT-1.0`의 실제 network·ad unit 승인)다.

**미해소(2026-10-16, Story 4.3 판정, 기준 `61aac2b`) — 재확인.** Story 4.3(PR #111)이 같은 성격의 변수 `BOKRILOG_AD_UNIT_PATH`를 더했다(`application.yml:244`·`249`, 기본 빈 값). 이 변수도 개발·운영 compose와 preflight 어디에도 없다. 형식이 맞지 않거나 비어 있으면 공급자가 꺼진 것으로 접히므로(`PROVIDER_DISABLED`) 지금도 안전하다.

**미해소 잔여:** 위 잔여에 `BOKRILOG_AD_UNIT_PATH`를 더한다. 두 변수를 함께 운영 compose와 preflight에 연결한다. 주인은 그대로 광고 출시 게이트다.

### 152. 광고 자격 결정 행의 보존 기한

**미해소(2026-10-15, Story 4.2 판정, 기준 `090efb9`).** Story 4.2(PR #109)의 리뷰가 이 항목을 append했다.
- **지우는 길이 하나뿐이다.** 결정 행(`engagement_ad_attempt`, V27, SKIPPED 포함)은 계정 삭제(`AdAttemptRepository.java:109` `eraseUser`)로만 지워진다.
- **필요한 이력보다 오래 남는다.** 빈도 판정에는 하루 남짓, 재전송에는 짧은 창의 이력만 필요하다.

**미해소 잔여:** 보존 기한과 정리 경로를 정하는 일. 동의 이력(항목 148)과 함께 정한다. 주인은 G-4(개인정보 검토)다.

### 153. `/ad-transition` CSP 후보값의 실측과 SHOW 왕복의 브라우저 검증

**미해소(2026-10-16, Story 4.3 판정, 기준 `61aac2b`).** Story 4.3(`spec-4-3-neutral-ad-transition.md`, PR #111)의 리뷰가 이 항목을 append했다.
- **후보값을 고정했다.** `deploy/nginx/bokrilog.conf:102`의 `/ad-transition` CSP 값은 공급자 문서 기준 후보이고, `verify-same-origin.sh:625`가 그 값을 그대로 단정한다. 실제 GPT 요청으로 잰 값이 아니다.
- **SHOW 경로를 밟지 않는다.** 주입 origin e2e는 공급자 꺼짐 경로만 본다. 실제 광고 계정이 없고 저장소 e2e는 `page.route` 대역을 쓰지 않는다.

**미해소 잔여:** 공급자를 켠 스택에서 실제 GPT 요청으로 CSP 값을 보정하고, 주소 정리 → gpt.js load → interstitial → 결과 왕복을 브라우저 층에서 확인하는 일. 주인은 광고 출시 게이트 G-4다.

### 154. 광고 단위 경로 형식과 GAM MCM 경로

**미해소(2026-10-16, Story 4.3 판정, 기준 `61aac2b`).** Story 4.3(PR #111)의 리뷰가 이 항목을 append했다.
- **형식이 좁을 수 있다.** 경로 형식 `^/[0-9]+(/[A-Za-z0-9_.-]+)+$`는 MCM 경로(`/<parent>,<child>/unit`)를 거부한다. 맞지 않으면 기동 거부 없이 공급자만 꺼진다(`PROVIDER_DISABLED`).
- **사본이 둘이다.** 같은 정규식이 백엔드(`AdProviderState.java:18`)와 프런트엔드(`frontend/src/session/adTransition.ts:20`)에 따로 있다. 이 정규식은 4.3 SPEC의 승인된 계약이라 4.3에서 바꾸지 않았다.

**미해소 잔여:** 실제 계정의 network code 형식을 확인하고, 넓혀야 하면 두 사본을 공유 fixture 하나로 묶어 함께 바꾸는 일. 주인은 광고 계정 승인(G-4)이다.

### 155. 주입 origin e2e의 새 스택 첫 실행 간헐 실패

**미해소(2026-10-16, Story 4.3 판정, 기준 `61aac2b`).** Story 4.3(PR #111)의 검증이 이 항목을 append했다.
- **관측.** `ad-consent.spec.ts:71`이 로그인 직후 `/settings`를 미인증 진입 화면으로 열어 5초 시간초과로 실패했다. 4.3 로컬 검증의 전체 실행 다섯 번 중 두 번이고, 둘 다 스택을 새로 띄운 뒤 첫 실행이었다.
- **같은 모양의 관측이 하나 더 있다.** PR #111의 시험 수정을 로컬에서 확인할 때(2026-10-16)도 새 스택의 첫 실행에서 `ad-transition.spec.ts:185`가 한 번 실패했다. 그 뒤 같은 파일 5회 반복(10건)과 전체 72건, CI는 모두 통과했다. 이때도 결과 파일이 다음 실행에 덮여 오류 내용을 남기지 못했다.
- **원인을 가리지 못했다.** 세션 응답·쿠키·기동 직후 지연 가운데 무엇인지 trace가 없다. 4.3은 설정·인증 경로를 바꾸지 않았다.

**미해소 잔여:** 새 스택 첫 실행에서 실패를 재현해 trace를 보존하고(예: 실패 시 `test-results`를 따로 복사) 원인을 찾는 일. 결정 지점이 정해지지 않았다 — 주인 없음, 다음 주입 origin e2e를 건드리는 작업이 이어받는다.

**해소(2026-10-17, 세션 속성 upsert fix, `spec-session-attribute-upsert.md`).**
- **원인.** PR #123 CI(run 39039915046)의 `ad-transition.spec.ts:137`이 같은 증상으로 실패했고, artifact trace에서 원인이 드러났다. 로그인 직후 회전된 쿠키로 보낸 `GET /api/auth/session`이 500이었다. 같은 세션의 첫 세션 확인 둘이 겹치면 CSRF 토큰 속성을 Spring Session JDBC가 plain `INSERT`로 두 번 저장해 기본 키가 충돌했고, 화면은 fail-closed로 미인증을 그렸다.
- **수정.** 세션 속성 저장을 PostgreSQL upsert(`SessionAttributeConversion.postgreSqlAttributeUpsert`)로 바꿨다. CSRF 토큰은 인증 성공 요청에서 미리 저장(`AuthFlowHandlers.storeCsrfToken`)해 동시 첫 확인이 같은 토큰을 받는다.
- **검사.** `AuthSessionConcurrencyTest`가 지킨다. 이 시험은 고치기 전에 결정적으로 실패했다(11건 중 11건). 주입 origin `ad-consent`·`ad-transition` `--repeat-each 5`는 15건 통과했다. 이 fix PR의 `ci-required` 전 job 성공이 머지 조건이다.

### 156. 광고 이벤트 분리 계측

**미해소(2026-10-16, Story 4.4 판정, 기준 `ec51014`).** Story 4.4가 범위 분할(사용자 결정 [S])로 append했다(source_spec 없음, step-01 분할 형식).
- **판정 단계만 센다.** 4.2의 `AdTelemetry`(`trigger`·`skip{reason}`·`reservation`, `TelemetryAllowlist.java:113-116`)가 서버에서 자격 판정을 센다. 소비 뒤의 요청·실노출·닫기·실패 사유·저장 결과 도달은 브라우저에서만 알 수 있는데, 그것을 서버로 보낼 경로와 allowlist 행이 없다.
- **원천 신호도 아직 밖으로 나오지 않는다.** 근거 문장은 "실노출·닫기는 4.4의 fail-open 이벤트(GPT callback)를 원천으로 쓴다"고 적었다. 그러나 4.4의 adapter(`frontend/src/ads/gptAdapter.ts`)는 화면에 `onFailure`(사유 인자 없음)와 취소만 내준다. 준비·실노출·닫힘 신호와 실패 사유(시간초과·미채움·차단·`null` slot·예외)는 adapter 안에서만 구분된다(4.4 리뷰 blind-hunter).

**미해소 잔여:** beacon 경로·집계·allowlist 행을 정하고, adapter 계약을 넓혀 사유와 이벤트를 내보내는 일. 이 계측이 있어야 4.4 3초 시한의 실제 실패율을 알 수 있다(`spec-4-4-ad-fail-open.md` Design Notes "시한과 느린 망"). 주인 없음 — Epic 4 회고 또는 157과 묶은 후속 작업이 정한다.

**미해소(2026-10-17, Epic 4 회고 AI-4) — 주인 지정.** 주인은 Epic 4 보완 Story 4.5(`epics.md` `### Story 4.5`, 광고 이벤트·Web Vitals 계측과 성능 kill switch)다. 회고 B-2(서버가 아는 소비 결말 카운터)도 4.5 범위에 들었다. 코드 사실은 위 판정과 같다.

**해소(2026-10-17, Story 4.5 done 전환, 기준 `ae06d92`).**
- **브라우저 이벤트.** adapter 계약을 `showInterstitial(adUnitPath, onFailure(reason), onReady)`로 넓혀 사유(TIMEOUT·NO_FILL·BLOCKED·UNSUPPORTED·ERROR)와 준비를 내보낸다. 중립 화면은 REQUESTED·READY·CLOSED·FAILED(창 경과 WINDOW_EXPIRED 포함)·RESULT를 문서당 한 번씩 보낸다. 서버는 `bokrilog.ad.client.event{event,reason}`로 센다.
- **소비 결말.** 서버가 아는 결말(회고 B-2)은 `bokrilog.ad.transition{outcome}`으로 센다.
- **어휘 대조.** 프런트·백 어휘는 공유 fixture `telemetryVocabulary.fixture.json`을 두 시험(`vocabulary.test.ts`·`ClientTelemetryVocabularyTest`)이 함께 대조한다.
- **검사.** `gptAdapter.test.ts`·`AdTransitionScreen.test.tsx`·`ClientTelemetryTest`가 지키고, PR #119의 `ci-required` 전 job이 성공했다.

**남은 것(이 항목 밖).** 실제 공급자 SHOW 경로의 브라우저 왕복은 153·G-4 몫이다.

### 157. Web Vitals 수집과 성능 kill switch

**미해소(2026-10-16, Story 4.4 판정, 기준 `ec51014`).** Story 4.4가 범위 분할로 append했다(source_spec 없음). 항목 **122**가 이 항목으로 주인을 옮겼다.
- **측정과 판정이 없다.** 브라우저 측정·beacon·집계 분모와 기간·경보 규칙이 하나도 없다.
- **광고를 끄는 수단은 있다.** 운영 command `restrict-advertising`(3.2)과 4.2·4.3 관문의 `ADVERTISING` 판독이다.

**미해소 잔여:** Web Vitals 수집(직접 구현 또는 런타임 의존 승인)과, 목표(p75 LCP 2.5초·INP 200ms·CLS 0.1)를 위협할 때 광고만 끄는 판정. 주인 없음 — Epic 4 회고 또는 후속 작업이 정한다.

**미해소(2026-10-17, Epic 4 회고 AI-4) — 주인 지정.** 주인은 Epic 4 보완 Story 4.5(`epics.md` `### Story 4.5`, 광고 이벤트·Web Vitals 계측과 성능 kill switch)다. kill switch는 기존 `ADVERTISING` 제한으로 광고만 끄고 해제는 3.4 수동 복구다. 코드 사실은 위 판정과 같다.

**해소(2026-10-17, Story 4.5 done 전환, 기준 `ae06d92`).**
- **표본.** 광고를 띄운 문서의 Vitals만 SHOW 소비 1건당 metric 하나씩 비식별 표본(`engagement_ad_vital_sample`, V29)으로 저장한다.
- **판정.** Worker·`all`의 `AdPerformanceJudge`가 5분마다 24시간 창을 본다. 표본이 40개 이상이고 `percentile_disc(0.75)`가 NFR-14 임계를 넘으면 `AdvertisingStopApi`가 `PERFORMANCE` 출처의 전역 `ADVERTISING` 제한과 감사 SYSTEM 행(`performance-stop`, `AD-PERF-1.0`)을 쓴다.
- **해제.** 3.4 `lift-restriction`으로만 닫히고, 이미 효력 중인 전역 광고 제한 아래에서는 표본을 비운다.
- **검사.** `ClientTelemetryTest`(임계·표본 부족·기존 제한·해제 뒤 재발동 없음)와 `AdPerformanceJudgeRoleTest`가 지키고, PR #119의 `ci-required` 전 job이 성공했다.

**남은 것(이 항목 밖).** 임계·창·최소 표본(`AD-PERF-1.0`)은 실측 전 시작값이다. 운영 데이터로 보정할지는 운영 관측 연결 뒤 정한다.

### 158. Windows 로컬 Vitest의 운영자 lazy chunk 대기 시간초과

**미해소(2026-10-16, Story 4.4 판정, 기준 `ec51014`).** Story 4.4의 검증이 append했다.
- **어떤 시험이 실패하나.** `App.test.tsx` 운영자 경로·`OperatorScreen`·`OperatorNotice`·`SettingsConnectionRevoke`가 `findBy` 기본 대기 1초를 넘긴다(1030~1054ms).
- **언제 실패하나.** Windows 로컬에서 여러 파일을 함께 돌리면 실패하고, `develop`(`edac317`)에서도 같다. 파일 하나씩 돌리면 모두 통과하고 CI `frontend-quality`(Linux)는 통과한다.
- **4.4는 원인이 아니다.** 4.4는 이 파일들과 운영자 화면을 바꾸지 않았다.

**미해소 잔여:** lazy chunk를 기다리는 시험의 대기 방식(명시적 시한 또는 chunk 선적재)을 고치는 일. 주인 없음 — 다음에 운영자 화면·그 시험을 건드리는 작업이 이어받는다.

**부분 해소(2026-10-18, Epic 4 회고 AI-6 chore done 전환, 기준 `4e248b8`).**
- **한 것.** 158이 적은 네 파일(`App.test.tsx` 운영자 경로·`OperatorScreen`·`OperatorNotice`·`SettingsConnectionRevoke`)의 첫 대기가 이름 있는 시한 `FIRST_MOUNT_TIMEOUT_MS`(3초, `frontend/src/test/firstMount.ts`)를 쓴다.
- **조건.** 전역 `asyncUtilTimeout`은 올리지 않았다. 진짜 lazy chunk는 `App.test.tsx` 하나이고, 나머지 셋은 같은 실행에서 함께 넘은 큰 화면의 첫 마운트다.
- **검사.** macOS에서 `stress-test.sh --runs 3`(네 파일)과 전체 Vitest가 통과했다. PR #125 `frontend-quality`도 통과했다.

**부분 해소인 이유:** 실패가 관측된 Windows 로컬에서는 다시 확인하지 않았다. 3초가 그 환경에서 충분한지가 남았다. 다음에 Windows에서 전체 Vitest를 돌리는 작업이 확인한다.

### 159. 전체 빌드에서만 나는 계획 종단 시험 간헐 실패

**미해소(2026-10-17, Epic 4 회고 AI-2·AI-3 chore done 전환, 기준 `1333c62`).** 이 chore의 검증이 append했다.
- **관측.** 로컬 macOS 전체 `backend/gradlew --project-dir backend build`에서 두 번 났다.
  - 한 번은 `PlanEndTest` 16건이 409 `PLAN_END_UNAVAILABLE`·`RESULT_TOO_RECENT`로 실패했다.
  - 다른 한 번은 `PlanCompletionTest.laterSyncsOnlyMoveThePostEndValue`의 작업이 `FAILED`로 끝났다.
- **재현되지 않는 조건.** 단독 `--rerun`과 같은 코드의 재실행은 통과했다. PR #121의 CI `backend-quality`도 통과했다.
- **이 chore는 원인이 아니다.** 계획 종료·완료 경로를 바꾸지 않았다.

**미해소 잔여:** 원인 확정이 남았다. 부하 아래 결과 시각과 DB 시각의 어긋남, 또는 순차 시험 중 scheduler·Worker의 경합으로 추정한다. 실패 시 시각·작업 상태를 남기는 진단이 먼저다. 주인은 없다 — Epic 4 회고 AI-6(간헐 실패 시험의 주인과 방법, `epic-4-retro-item-45`)이 155·158과 함께 볼 자리다.

**부분 해소(2026-10-18, Epic 4 회고 AI-6 chore done 전환, 기준 `4e248b8`).**
- **진단 장치.** 유력한 원인 가설(fixture가 측정 시각을 JVM 시계로 만들고 판정은 DB(Docker VM) 시각으로 하므로, VM 시계가 뒤처지면 결과가 "미래"가 된다)을 드러내는 장치를 뒀다.
  - `DatabaseClockSkew`가 JVM↔DB 시계 차이를 컨테이너 진입과 시험별 저장소 초기화에서 잰다(5분마다 다시 잰다).
  - 1초를 넘으면 두 시각·방향·대처(Docker Desktop 재시작)를 말하며 실패하고, 통과할 때도 차이를 한 줄 남긴다.
- **검사.** `DatabaseClockSkewTest`(7건 — 밀린 JVM 시계에서 던짐, 결과 캐시, 측정 실패 뒤 재측정)가 지킨다. PR #125 `backend-quality`도 통과했다. 이번 전체 빌드의 차이는 1ms 안팎이었다.

**부분 해소인 이유:** 원인은 확정하지 않았다. 다음 발생 때 시험 보고서의 `[test-clock]` 줄을 읽어 가른다 — 시계 차이가 크면 VM 재동기화로 끝난다. 차이가 작은데도 나면 1초 미만의 어긋남이나 순차 시험 중 Worker 경합이 남는다.

**주인 지정(2026-11-10, Epic 5 회고 AI-4 chore, 기준 `721c3e6`).** `spec-ci-flaky-run-verdicts.md`가 이 항목과 177(같은 간헐 실패 계열)의 주인을 정했다. 상태와 건수는 바꾸지 않는다.
- **주인.** 다음 fix chore(가칭 `spec-lease-loss-test-doors.md`)다. 그 fix는 177의 `slowPageStores`·`expireLease` 시험을 `FinalSweepDoor`식 잠금 문(PR #146의 advisory lock 문)으로 옮기고, 그 대상에 `PlanEndTest`가 든다.
- **재판정 조건.** 그 fix가 `PlanEndTest`를 잠금 문으로 옮긴 뒤 이 항목을 다시 판정한다. 옮긴 뒤에도 전체 빌드에서 같은 실패가 나면 원인은 시간 기반 순서가 아니므로 `[test-clock]` 줄로 시계 차이 가설을 가른다.
- **기록.** 그 사이 CI에서 이 계열의 실패가 나면 재실행 전에 `ci-run-verdicts.md`에 행을 쓰고 후속 칸에 이 번호(159, 시간 기반 lease 상실 시험이면 177)를 단다(`DEVELOPMENT.md` `### 간헐 실패`). 판정은 원인이 확정되기 전에는 `미조사`이고, 시험 구조가 원인으로 확인됐을 때만 `간헐 시험`이다.

**같은 계열의 다른 경로(2026-11-11, 시험 컨테이너 수명 fix, 기준 `df5a9c6`).** `spec-test-container-context-lifecycle.md`가 같은 "전체 빌드에서만" 계열의 다른 경로 하나를 닫았다. 이 경로는 159를 직접 설명하지 않는다 — 이 경로의 실패는 `Connection refused`이고, 159의 관측은 409 `RESULT_TOO_RECENT`·작업 `FAILED`다. 상태(부분 해소)·건수·주인은 바꾸지 않는다.
- **경로.** 시험 JVM의 공유 PostgreSQL 컨테이너가 `@Bean @ServiceConnection`이었다. 그래서 그 bean을 가진 Spring 컨텍스트 하나가 닫히면(로드 실패·컨텍스트 캐시 축출) Spring Boot의 `TestcontainersLifecycleBeanPostProcessor`가 JVM의 공유 컨테이너를 멈췄다.
  - 같은 JVM의 뒤 시험은 `Connection refused`로 실패했다.
  - 그 뒤 공유 컨테이너가 다시 뜨면 새 포트를 받으므로, 캐시에 남은 컨텍스트가 죽은 포트를 계속 쓸 수 있었다(추정 — 관측한 것은 연쇄 `Connection refused`까지다).
  - 컨텍스트가 많이 쌓이고 축출되는 전체 빌드에서만 열리는 경로다.
- **관측.** 2026-11-10 로컬 build의 215건 실패와 2026-11-11 로컬 develop build가 이 모양이다. 후자는 Windows 루프백 `Bad address: listen`으로 `AuthDeletionPathTest` 컨텍스트 로드 실패 → 그 JVM 컨테이너 `kill → die(137) → destroy` → 연쇄 `Connection refused` 순서였다. 둘 다 159의 관측(409 `RESULT_TOO_RECENT`·작업 `FAILED`)과 모양이 다르다.
- **닫은 것.** 컨테이너를 bean으로 노출하지 않고, 컨텍스트는 `JdbcConnectionDetails` bean으로 접속 정보만 받는다. `SharedPostgresLifecycleTest`가 정상 종료·로드 실패 뒤에도 컨테이너가 같은 id로 살아 있고 SQL이 됨을 고정한다. 같은 시험이 옛 설정에서 실패함을 확인했다.
- **159의 남는 후보.** 그대로다 — JVM↔DB 시계 차이(`[test-clock]` 줄로 가른다)와, 시간 기반 순서로 lease 상실을 만드는 시험 구조(177)다. 위 주인(lease 상실 시험 잠금 문 fix)과 재판정 조건도 그대로다.

**부분 해소 — 재판정(2026-11-11, lease 상실·지연 시험의 잠금 문 이전 fix, 기준 `ab6d798`).** `spec-lease-loss-test-doors.md`가 위 "주인 지정"의 재판정 조건대로 `PlanEndTest`를 잠금 문으로 옮긴 뒤 다시 판정했다. 상태는 부분 해소 그대로다. `RESULT_TOO_RECENT` 16건의 후보는 JVM↔DB 시계 차이로 좁혔고, `PlanCompletionTest`의 작업 `FAILED` 한 번은 설명되지 않은 채 남는다.
- **잠금 문 이전이 고친 것.** 177의 시험 구조뿐이다. `PlanEndTest.publicationAndEndAreSerializedByThePlanLockEitherWay`의 늦은 sync는 이제 페이지 저장이 문 앞에 선 것을 본 뒤 종료를 `runBehindDoor`로 끝내고(종료가 페이지 트랜잭션 뒤에 줄 서지 않고 먼저 끝난 갈래임을 단정한다) 문을 연다(지연형) — `pg_sleep` 지연과 단계 대기의 시간 순서가 없다.
- **시간 기반 구조는 159의 관측을 설명하지 못한다.** 조사 결과, 옛 `slowPageStores` 시험은 뒤 시험에 `RESULT_TOO_RECENT`를 남길 경로가 없다.
  - trigger는 그 시험 사용자 한정이고 `finally`에서 지워졌다.
  - 그 시험이 경합에 졌다면(sync가 종료보다 먼저 발행) 종료 판정은 `REFERENCE_STALE`이 먼저 나온다 — `PlanEndRules.judge`는 참조 대조를 `RESULT_TOO_RECENT`보다 앞서 본다.
- **남는 후보.** `RESULT_TOO_RECENT`는 DB 시각(`now`)이 스텁이 JVM 시각으로 만든 결과 `measuredAt`보다 앞설 때 난다. 그래서 16건이 함께 실패한 모양은 JVM↔DB 시계 차이 가설과 맞는다. 다음 발생 때 시험 보고서의 `[test-clock]` 줄(`DatabaseClockSkew`)로 가른다.
- **설명되지 않은 관측.** `PlanCompletionTest.laterSyncsOnlyMoveThePostEndValue`의 작업 `FAILED` 한 번은 위 후보로도 시간 기반 구조로도 설명되지 않은 채 남는다(그 시험은 `slowPageStores`를 쓰지 않았다).
- **부분 해소인 이유.** 원인을 확정하지 않았다. 시계 차이를 실제 실패에서 관측하기 전에는 해소로 적지 않는다.

### 160. 세션 행이 먼저 지워진 뒤 속성 저장의 FK 위반

**미해소(2026-10-17, 세션 속성 upsert fix가 append).** upsert는 같은 속성 첫 INSERT의 기본 키 충돌만 닫는다. 같은 세션의 다른 요청이 동시 로그아웃·만료 정리로 세션 행이 지워진 뒤 속성을 저장하면 `SPRING_SESSION_ATTRIBUTES` FK에서 500이 날 수 있고, 동시 삭제 뒤 UPDATE는 0행이 된다. 재현 시험은 없다.

**미해소 잔여:** 재현 시험을 두고 그 실패를 미인증 응답으로 접을지 정하는 일. 주인 없음 — 다음에 세션 저장·로그아웃 경로를 건드리는 작업이 이어받는다.

### 161. 세션 확인 500을 미인증 문구로 그림

**미해소(2026-10-17, 세션 속성 upsert fix가 append).** PR #123 CI 실패 화면에서 서버 500(일시 오류)이 "로그인 상태가 아닙니다"로 보였다. fail-closed 자체는 맞지만 문구가 원인을 잘못 가리킨다. 재시도로 덮는 것은 금지다.

**미해소 잔여:** 세션 확인 실패(5xx·네트워크)를 미인증과 구분해 안내할지 정하는 일. 주인 없음 — 다음에 진입·세션 게이트 화면을 건드리는 작업이 이어받는다.

### 162. 삭제 커밋 뒤 append되는 운영자 거절·실패 행의 `actor_user_id`

**미해소(2026-11-03, 계정 삭제·감사 원장 잠금 직렬화 fix가 append, 기준 `c6362a7`).**
- **이 fix가 덮은 것.** 계정 삭제의 operations 비식별은 원장 append 잠금 아래에서 돌므로, 삭제보다 먼저 잠금을 쥔 쓰기는 삭제가 기다렸다가 비식별한다. 삭제가 시작된 계정을 겨냥한 늦은 강한 신호는 같은 잠금 아래 계정 상태를 보고 그 트랜잭션에서 비식별한다(`ErasureAuditLockTest`).
- **남은 것.** 운영 command의 거절·실패 행(롤백 뒤 별도 트랜잭션, 예: `ACCESS_REVOKED`)은 삭제 커밋 뒤 잠금을 얻어 append하고, 그 경로에는 계정 상태 판정이 없다. 그래서 삭제된 운영자의 `actor_user_id`가 다시 붙을 수 있다. 지금은 사용자 삭제 경로가 없어 잠재 결함이다.

**미해소 잔여:** 거절·실패 행을 쓸 때 계정 상태를 보고 수행자 귀속을 비울지, 또는 Epic 5 삭제 완료 검사(5.2·5.4)가 잡게 할지 정하는 일. 주인 — Epic 5에서 사용자 삭제 경로를 여는 스토리(5.2·5.4)가 결정 지점이다.

**미해소(2026-11-06, Story 5.2 판정, 기준 `f95ebb5`).**
- **삭제 전에 생긴 행은 잡는다.** 5.2의 `IDENTITY` 단계는 `DELETED` 전환 앞에서 operations 잔여를 다시 센다. 그래서 삭제 실행 중에 붙은 수행자 귀속은 `RESIDUAL_FOUND`로 잡혀 다시 지워진다.
- **삭제 뒤에 생긴 행은 못 잡는다.** `DELETED` 커밋 **뒤** 잠금을 얻어 append되는 운영자 거절·실패 행은 그 검사 밖이다. 그런 행에 계정 상태 판정을 더하지도 않았다.

**미해소 잔여:** 위와 같다. 삭제 완료 뒤 잔여를 통합 검증하는 Story 5.4가 결정 지점이다.

**해소(2026-11-09, Story 5.4 판정, 기준 `f259096`).** 5.4 SPEC Intent가 사용자 결정으로 "쓸 때 계정 상태를 본다"를 골랐다(3.10의 늦은 강한 신호와 같은 규칙).
- **고친 자리.** 운영자 감사 행의 append 하나(`OperatorEnvelopeService.java:598`)가 원장 append 잠금 뒤 `accountUsable`로 수행자 계정을 본다. 삭제가 시작됐으면 `actor_user_id` 없이 `actor_erased_at`을 채워 쓴다. 거절·실패 행과 요청 행이 모두 이 경로를 지난다(2차 리뷰가 선택 인자를 없애 예외 경로를 남기지 않았다).
- **검사.** `DeletionCompletionTest`의 `삭제가_시작된_수행자의_요청_행도_귀속_없이_쓰인다`(`:435`)·`삭제가_시작된_수행자의_거절_행은_귀속_없이_쓰인다`(`:556`)가 PR #145 CI(run 42550320435)에서 통과했다.

### 163. `backend-quality` 시험의 JVM 병렬화(`maxParallelForks`)

**미해소(2026-11-03, CI backend jar 생성 job chore 판정, 기준 `6b16acb`).** 2026-11-03 CI 시간 분석이 append했다(source_spec 없음 — jar 생성 job 분리와 독립적으로 머지 가능해 단일 목표 규칙으로 뗐다).
- **지금 값.** `backend-quality`는 Gradle `build` 한 단계이고 시험은 JVM 하나(`tasks.withType<Test>`에 `maxParallelForks` 없음)에서 순차로 돈다 — Gradle test 14분 25초, 상위 8개 파이프라인 시험 클래스가 12.6분. CI `backend-quality`는 12~16분이다.
- **이 chore가 바꾼 것.** jar 생산자를 뗐을 뿐 시험 시간 자체는 그대로다. 분리 뒤 PR 임계 경로는 `backend-quality` ≈16분 하나이므로, 이 항목이 다음 단축의 자리다.
- **경계.** `spec-backend-test-speed.md` Never와 `spec-ci-critical-path.md` Ask First가 시험 병렬화·suite 배치를 막아 두었다. 이 chore의 Never도 같다.

**미해소 잔여:** `maxParallelForks` 2~3(JVM마다 PostgreSQL 컨테이너 하나, 포트는 무작위)으로 15분을 8~10분으로 줄이는 변경과, 그 앞에 두 SPEC의 병렬화 경계를 새 SPEC Intent에서 다시 여는 결정. 순차 시험 중에도 scheduler·virtual worker가 돌고 DB 초기화가 public 전체 테이블을 NOWAIT로 잠그는 지금 구조(`CLAUDE.md` "시험·CI")가 JVM 여럿에서 안전한지가 그 SPEC의 첫 질문이다. 주인 없음 — 다음 CI 시간 chore가 이어받는다.
→ 아래 2026-11-03 부분 해소 블록.

**부분 해소(2026-11-03, CI 시험 fork 분할 chore, `spec-ci-test-forks.md`, 기준 `66d4938`).**
- **경계를 다시 연 결정.** 그 SPEC의 Intent가 두 SPEC의 "백엔드 시험 병렬화" Never·Ask First를 **JVM 단위 fork 분할에 한해** 다시 열었다. JVM 안 병렬(JUnit `junit.jupiter.execution.parallel.*`)·Testcontainers `reuse`·컨테이너 공유는 여전히 금지다.
- **첫 질문의 답.** JVM(fork)마다 `PostgresTestcontainersConfig`의 static 컨테이너가 하나씩 뜨므로 scheduler·virtual worker는 자기 JVM의 Spring 컨텍스트가 자기 컨테이너에만 닿고, `AuthFlowTestBase.resetStore`의 NOWAIT 전체 잠금도 그 컨테이너 안이라 fork 사이에 경합하지 않는다. 읽기 전용 조사에서 fork 공유 자원은 없었다 — `@SpringBootTest`는 `RANDOM_PORT` 2·`NONE` 8이고 `DEFINED_PORT` 없음, 루프백 서버 셋(`OidcStubConfiguration`·`BybitStubConfiguration`·`BybitAdapterTest`)은 모두 포트 0, 파일은 `RestoreRunnersTest`의 `@TempDir` 하나, `System.setProperty`·고정 포트 바인딩 없음. fork 사이에 남는 공유는 docker 데몬·이미지 캐시·`~/.testcontainers.properties`뿐이고 셋 다 동시 접근에 안전하다.
- **바꾼 것.** `backend/build.gradle.kts`의 `Test`에 `maxParallelForks = 3`과 근거 주석(러너 4 vCPU·16 GB, Gradle의 class 단위 round-robin, 클래스 시간 수치, JVM당 컨테이너 하나), `gradle.properties`의 `org.gradle.parallel=false` 주석에 시험 fork는 별개라는 한 줄. 시험 코드·`ci.yml`·`.github/scripts/*`·timeout 20분은 그대로다. 3을 고른 근거는 2026-11-03 로컬 보고서(136 클래스, 합 20.3분)로 무작위 순서를 모의한 최장 fork p50/p90 — 2 fork 12.1/14.4분, 3 fork 9.3/11.5분, 4 fork 7.9/9.9분 — 과 4 vCPU에서 코어 하나를 남겨 부하 유발 간헐 실패(159)를 키우지 않는 것이다.
- **실측.** 로컬 `clean build`(Windows 11, Temurin 25, Gradle 9.7.1) 19분 42초 → 11분 44초(시험 1,610건·137 클래스, 실패는 알려진 Windows 환경 문제인 `BybitAdapterTest` `Bad address: listen` 1건뿐). `build/test-results`의 `timestamp`·`time`으로 재구성한 타임라인은 클래스 시간 합 22.1분이 11.1분 구간에 들어가고 세 클래스가 동시에 돌던 구간이 4.6분이다(`docker ps`에 `postgres:18.6-alpine` 셋·ryuk 셋). CI `backend-quality`의 분할 뒤 실측(PR #129의 14분 19초 대비, 목표 8~10분)은 이 chore의 PR이 채운다.
- **부분 해소인 이유와 역전 규칙.** 로컬 실측만 있다. PR CI `backend-quality` 실측이 AC 1의 범위(≤ 10분)에 들면 PR에서 해소로 올린다. 10분 초과~14분 19초 미만이면 fork 수 변경(2·4)이나 suite 배치는 그 SPEC Ask First의 결정이고, 14분 19초 이상이면 실패다.

**해소(2026-11-04, CI 시험 fork 분할 chore 첫 CI 실측, PR #133 머지 커밋 `7fad060`).**
- **CI 실측.** run 41752910193: `backend-quality` 14분 19초 → 10분 14초(Gradle `build` 10분 1초). `[test-forks]` 로그는 executor 2개(764건 455초·861건 522초)·전체 1,625건 성공이다. 전체 wall-clock은 `changes` 시작부터 `ci-required` 끝까지 14분 43초 → 12분 19초다.
- **fork가 둘인 이유.** 저장소가 private이라 기본 `ubuntu-24.04` 러너가 4코어가 아니었고, Gradle은 `maxParallelForks`를 `--max-workers`(기본 CPU 수)로 상한한다. SPEC과 첫 주석이 전제한 "러너 4 vCPU·16 GB"는 틀렸고, 기록 PR이 `build.gradle.kts` 주석·`CLAUDE.md`·`DEVELOPMENT.md`를 고쳤다. 설정은 3 그대로다(4코어 이상 로컬은 3, CI는 2).
- **판정.** AC 1의 경계 10분을 14초 넘었지만 원인이 러너 사양이고 2 vCPU에서 fork를 늘릴 이득이 없어 사용자가 수용했다(2026-11-04). 해소다.
- **로컬 재확인.** `develop` `c213b6c` 위 `clean build` 10분 56초, 1,625건 전부 성공(fork 셋 4.9·6.3·10.1분).

### 164. 리허설 셋을 백엔드 변경 PR의 필수 경로에서 빼는 정책

**미해소(2026-11-03, CI backend jar 생성 job chore 판정, 기준 `6b16acb`).** 2026-11-03 CI 시간 분석이 append했다(source_spec 없음).
- **지금 값.** 배포·백업·복원 리허설 셋은 백엔드 변경 PR마다 돈다(`backup-restore` 분류는 프런트엔드·문서-only PR에서만 백업·복원 둘을 끈다). 리허설은 jar 자체를 돌리므로 "백엔드 변경인데 리허설과 무관하다"를 기술적으로 가를 수 없고, 3.9 defer(경로별 job 분기, 항목 131)도 같은 이유로 `deploy/**` 허용 목록을 거부했다.
- **이 chore가 바꾼 것.** 리허설이 `backend-assemble`(≈1.5분)만 기다리므로 백엔드 PR에서도 리허설이 시험과 겹쳐 돌고, 분리 뒤 추정에서 리허설 꼬리는 임계 경로가 아니다(assemble 1.5 + 백업 12 ≈ 13.5 < `backend-quality` ≈16). 리허설 시나리오·`deploy/**`·분류기 규칙은 이 chore의 Never라 손대지 않았다.

**미해소 잔여:** 리허설 셋을 `deploy/**`·Dockerfile 변경 PR과 `develop` push에서만 돌릴지 정하는 정책 결정 — `spec-ci-critical-path.md` Always가 "관련 경로 허용 목록은 새 경로를 조용히 건너뛴다"로 거부한 방식이라 fail-closed 분류로는 정의할 수 없고, CI-PLAN §5 표의 "backend 변경 → backend 품질·관련 통합" 해석과 함께 사람이 정한다. 러너 분(비용) 절약이 주된 동기이고 wall-clock 동기는 이 chore 뒤 약해졌다. 주인 없음 — CI-PLAN 새 버전(항목 146의 as-built 반영)과 함께 볼 자리다.

### 165. 소비 job 넷이 내려받은 jar를 생산자 output과 대조하지 않는다

**미해소(2026-11-03, CI backend jar 생성 job chore 판정, 기준 `6b16acb`).** 이 chore의 1차 리뷰(blind-hunter)가 append했다.
- **지금 값.** `same-origin-integration`·`deploy-rehearsal`·`backup-rehearsal`·`restore-rehearsal`은 `backend-jar-<run_id>-<run_attempt>`를 내려받아 그대로 쓴다. 내려받은 `bokrilog-backend.jar`의 SHA-256을 `needs.backend-assemble.outputs.jar-sha256`과 대조하는 단계가 없다. 이 변경 전에도 같았다(`backend-quality`가 올린 것을 그대로 썼다).
- **이 chore가 지키는 것.** `ci-required`가 생산자 두 쪽(`backend-assemble`·`backend-quality`)의 digest 일치를 사후에 본다. artifact 저장소 왕복(업로드 → 내려받기)이 바이트를 바꾸지 않았다는 것은 CI-PLAN §8의 "통합 job이 SHA와 checksum을 검증한다"가 요구하는 바이지만 여전히 가정이다.

**미해소 잔여:** 네 job의 내려받기 뒤 `sha256sum -c` 한 단계(판정은 `.github/scripts/`의 스크립트로, `test-ci-scripts.sh`가 네 job의 단계 목록 계약을 함께 갱신). 주인 없음 — 다음 CI 계약 chore가 이어받는다.

### 166. 프런트엔드 동일 출처 JSON POST·JSON 판독 helper(Epic 4 회고 AI-8 ②)

**미해소(2026-11-03, 루트 컨트롤러 공통 부품 chore가 append, 기준 `6b16acb`, 판정은 done 전환 — 머지 커밋 `0456622`).** source_spec은 없다. AI-8을 세 목표로 나눈 결정([S])이 ① 백엔드만 이 chore에 두고 이 묶음을 뗐다.
- **지금 값.**
  - 동일 출처 POST(CSRF 헤더·`Origin`·JSON 본문)를 직접 만드는 곳이 17개 파일이다.
  - JSON 판독 사본(`readJson`·`objectOf`·`fieldsOf`)이 여섯 파일에 아홉 개다: `session/adTransition.ts`·`useAdConsent.ts`·`operatorApi.ts`·`useSupportInquiries.ts`·`bybitConnection.ts`·`useServiceNotices.ts`.
- **이 chore가 바꾼 것.** 없다. 백엔드 `ControllerSupport`·`IdempotencyKey.CONFLICT_CODE`만 다뤘고 `frontend/`는 건드리지 않았다.

**미해소 잔여:** POST 한 벌과 판독 한 벌을 공통 부품으로 두고 호출자를 옮기는 일. `CLAUDE.md` 프런트엔드 공통 부품 문단과 사본 재발 방지 시험이 함께 필요하다. 주인은 `epic-4-retro-item-47`(in-progress)의 다음 chore다.
→ 아래 2026-11-04 해소 블록.

**해소(2026-11-04, 프런트엔드 POST·JSON 판독 공통 부품 chore, `spec-frontend-request-commons.md`, PR #135 머지 커밋 `afb08f0`).**
- **바꾼 것.**
  - `frontend/src/session/http.ts` 하나가 `postJson`·`postNoContent`·`readJson`·`objectOf`·`JsonObject`를 정의한다. 요청 init 규칙은 하나다: `POST`·`same-origin`·`no-store`·`Accept`·CSRF 헤더. `Content-Type`은 본문이 있을 때만, `Idempotency-Key`는 받았을 때만 붙는다.
  - 운영 POST 21곳을 옮겼다(session 열 파일, components 네 파일). 204 묶음의 공유 전송 `unlinkProvider.post`도 `postNoContent`로 옮겼다.
  - `readJson`·`jsonOf` 5벌, `objectOf`·`fieldsOf` 5벌, 인라인 판독 3곳, `'X-CSRF-TOKEN'` 리터럴 사본이 사라졌다.
  - 그동안 `Accept`·`cache`가 없던 POST 11곳에는 그 둘이 생겼다. SPEC Always가 승인한 정규화다.
- **재발 방지.** `session/http.test.ts`가 운영 소스를 훑어 다음을 단정한다.
  - `method: 'POST'`(대소문자 무시)는 `http.ts`와 `telemetry/beacon.ts`에만 있다.
  - `new Request(`·인라인 `.json().catch(`·판독 helper 정의가 `http.ts` 밖에 없다.
  - CSRF 헤더 리터럴은 `content/auth.ts`에만 있다.
  - `CSRF_HEADER` import는 `http.ts`·`beacon.ts`에만 있다.
- **바꾸지 않은 것(Never).**
  - `keepalive` 계측 `beacon.ts`와 배열을 받는 `useDashboard.record()`, 그리고 `codeIn`·`codeOf` 변형
  - GET 16곳, 시한 구현
  - 성공 경로의 `response.json()` 직접 호출(사본 helper가 아니다)
- **검증.**
  - 로컬: Vitest 1,570건 통과, 미리보기 Playwright 40건 통과.
  - 로컬 `verify-same-origin.sh`는 Windows 소켓 오류로 끝나지 않았다(PR 본문에 기록).
  - PR #135 CI(run 41781954434): `frontend-quality` 3분 44초, `same-origin-integration` 7분 33초로 주입 origin e2e를 포함해 통과했고 `ci-required`도 통과했다.

### 167. 광고 시험 공용 helper·engagement 바깥 의존 허용 목록(Epic 4 회고 AI-8 ③)

**미해소(2026-11-03, 루트 컨트롤러 공통 부품 chore가 append, 기준 `6b16acb`, 판정은 done 전환 — 머지 커밋 `0456622`).** source_spec은 없다. 회고 A-2의 묶음이다.
- **지금 값.**
  - `AdEligibilityTest`·`AdTransitionTest`의 `admitManual`은 이미 갈라졌다. 차이는 접수 시각을 당기는가이다.
  - `grant`·자격 행 삽입·`assertNotFound`도 광고 시험마다 사본이다.
  - `engagement/package-info.java`가 적은 바깥 의존(sync·operations·identity)을 강제하는 `ModuleBoundaryTest` 규칙이 없다.
- **이 chore가 바꾼 것.** 없다. 운영 소스의 루트 컨트롤러만 다뤘다. engagement 컨트롤러 넷이 새 helper를 쓰게 됐지만 engagement 모듈의 의존은 바뀌지 않았다.

**미해소 잔여:** 시험 helper 한 곳으로 모으기, 그리고 engagement의 허용 의존 목록을 `ModuleBoundaryTest`에 단정하기. 주인은 `epic-4-retro-item-47`(in-progress)의 다음 chore다.
→ 아래 2026-11-04 해소 블록.

**해소(2026-11-04, 광고 시험 공용 helper·engagement 바깥 의존 규칙 chore, `spec-ad-test-commons.md`, PR #137 머지 커밋 `51fd410`).**
- **바꾼 것.**
  - 루트 시험 패키지의 `abstract class AdTestBase extends OperatorTestBase`가 광고 시험 helper의 단일 정의다(`grant`·`admitManual`·`backdateAdmission`·`claim`·`insertConsent`·`insertEligible`·`newTransition`·`assertNotFound`·`@AfterEach providerBackOn`). 새 시험 컨텍스트를 만드는 설정은 두지 않았다.
  - AdConsent·AdEligibility·AdTransition·ClientTelemetry 시험이 이것을 상속한다.
  - 갈라졌던 `admitManual`은 공통부와 Eligibility의 쿨다운 사정(`backdateAdmission`)으로 나눴다. 세 벌이던 `concurrently`는 `OperatorTestBase`로 올렸다.
  - `ModuleBoundaryTest`의 `engagement는_sync_operations_identity에만_의존한다`가 package-info의 산문을 기계 판정으로 바꿨다. 루트 패키지 의존도 막고, 대상이 비면 실패한다.
- **재발 방지.** `AdTestBaseTest`가 그 base의 하위 시험을 훑어, 옮긴 helper 이름의 재정의가 base 밖에 생기지 않게 막는다. 이름을 바꾼 사본과 인라인 SQL은 리뷰 몫이다.
- **바꾸지 않은 것(Never).**
  - `AccountErasureTest`·`RestoreApplyTest`의 인라인 engagement 행, `ErasureAuditLockTest`의 `LOCK_WAITERS`, `TelemetryAllowlistTest`의 `scrape`
  - 한 클래스에만 있는 helper
  - engagement 공개 API의 타입 단위 제한
- **검증.**
  - 로컬 `clean build`: 시험 1,638건 전부 통과(기준 1,625건 + 새 13건). 기존 클래스의 `@Test` 수는 같다.
  - 부정 확인: 루트·plan 타입을 참조하는 임시 클래스에서 새 규칙이 실패했다.
  - PR #137 CI: 아홉 job 모두 통과(`backend-quality` 8분 36초).

### 168. fork 격리 불변식의 소스 스캔 시험

**미해소(2026-11-03, CI 시험 fork 분할 chore 판정, 기준 `66d4938`).** 이 chore의 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap 셋 다)가 append했다.
- **지금 값.** `maxParallelForks = 3` 뒤 시험 클래스 셋이 호스트의 루프백 포트·작업 디렉터리·docker 데몬을 공유하며 동시에 돈다. `CLAUDE.md` "시험·CI"가 고정 포트·`System.setProperty`·공유 파일 경로를 쓰는 시험을 들이지 않는다고 적었고 지금 묶음은 읽기 전용 조사로 그 규칙을 만족하지만(`RANDOM_PORT` 2·`NONE` 8, 루프백 서버 셋 모두 포트 0, 파일은 `@TempDir` 하나), 저장소의 소스 스캔 시험은 모두 `src/main/java`만 본다.
- **이 chore가 지키는 것.** 규칙을 문서(`CLAUDE.md`·`build.gradle.kts` 주석)에 적었을 뿐 검사는 없다. SPEC Always가 시험 코드 변경을 막아 이 chore에서 더하지 않았다.

**미해소 잔여:** `src/test/java`를 훑어 `DEFINED_PORT`·`withFixedExposedPort`·`withReuse`·`System.setProperty`·0이 아닌 리터럴 포트의 `InetSocketAddress(`를 거부하는 시험 하나(`ModuleBoundaryTest`·`OperatorAuditTest`의 소스 스캔 관용). 주인 없음 — 백엔드 시험을 다음에 고치는 작업이 이어받는다.

### 169. CI에서 시험 fork 수의 기계 판정

**미해소(2026-11-03, CI 시험 fork 분할 chore 판정, 기준 `66d4938`).** 이 chore의 1차 리뷰(verification-gap)가 append했다.
- **지금 값.** `org.gradle.workers.max=1`이나 뒤에 오는 `maxParallelForks` 재설정이 fork를 1로 접어도 같은 시험이 통과하고 아홉 job이 green이라 임계 경로가 조용히 분할 전으로 돌아간다. `test-ci-scripts.sh`는 `backend-quality`의 명령 문자열만 고정한다.
- **이 chore가 지키는 것.** `build.gradle.kts`의 `TestListener`가 fork(executor)마다 시험 수·길이와 전체 건수·executor 수를 `[test-forks]` 줄로 CI 로그에 남긴다 — 사람이 볼 수 있는 관측이지 판정이 아니다.

**미해소 잔여:** `backend-quality` 로그의 `[test-forks]` executor 줄을 `.github/scripts/`의 스크립트가 세어 2개 미만이면 실패시키고, `gradle.properties`에 `org.gradle.workers.max`가 없음을 `test-ci-scripts.sh`가 단정하는 것(판정은 스크립트로, `ci.yml` 단계 목록 계약 갱신 포함). 주인 없음 — 다음 CI 계약 chore(165와 같은 자리)가 이어받는다.

### 170. 삭제 진행 화면에 지원(문의) 경로가 없다

**미해소(2026-11-05, Story 5.1 판정, 기준 `822816b`).** Story 5.1의 1차 리뷰(blind-hunter)가 append했다.
- **지금 값.** 삭제 진행 화면(`DeletionProgressScreen`)은 상태·다시 확인·로그아웃만 제공한다.
- **막히는 경로.** 삭제 중 계정의 문의 접수(`POST /api/support/inquiries`)는 3.6의 쓰기 접근 판정으로 `404 NOT_FOUND`다. `AccountDeletionRequestTest`가 이 거부를 고정한다.
- **이 스토리가 지키는 것.** 기존 거부를 바꾸지 않고 시험으로 고정했다. 새 지원 경로는 만들지 않았다(SPEC Never — 새 경로·새 쓰기 금지).

**미해소 잔여:** 삭제 중 계정에 열 지원 경로(무세션 연락처 안내 또는 상태 전용 세션의 문의 허용)를 정하는 일. 실행·부분 실패를 사용자에게 알리는 Story 5.2·5.4가 결정 지점이다.


**미해소(2026-11-06, Story 5.2 판정, 기준 `f95ebb5`).**
- **늘어난 것.** 5.2는 진행 화면에 `재시도 필요`·실패 범위·`삭제 다시 시도`를 더했다.
- **그대로인 것.** 지원(문의) 경로는 더하지 않았다. 삭제 중 계정의 문의 접수는 이제 생명주기 잠금 아래에서도 거부된다(404).
- **영향.** 재시도가 거듭 실패하는 사용자가 운영자에게 닿을 길은 여전히 없다.

**미해소 잔여:** 위와 같다. 완료 안내와 사용자 안내를 정하는 Story 5.4가 결정 지점이다.

**해소(2026-11-09, Story 5.4 판정, 기준 `f259096`).** 두 선택지 가운데 "무세션 연락처 안내" 쪽을 상태 전용 세션에 얹었다. 문의 접수의 거부(404)는 그대로 두고 쓰기 경로를 새로 열지 않았다.
- **고친 자리.** 삭제 조회 응답이 요청이 있거나 완료 조회일 때 지원 연락처 `supportContact`를 싣는다. 값은 3.5 `BOKRILOG_SUPPORT_CONTACT`이고 비면 생략한다(`AccountDeletionController.java:186`). 진행·완료 화면이 그 값을 보여 준다(`DeletionProgressScreen.tsx:430`). 연락처 모양 판정은 `session/supportContact.ts` 하나로 세 화면이 함께 쓴다. 문의할 때 알려 줄 요청 ID도 함께 보인다.
- **검사.** `DeletionCompletionTest.지원_연락처는_요청이_있을_때만_진행_조회에_실린다`(`:424`)와 `DeletionProgressScreen.completion.test.tsx`의 진행 중 연락처 시험(`:306`)이 PR #145 CI에서 통과했다.
- **남는 한계.** 연락처를 구성하지 않은 배포에서는 여전히 닿을 길이 없다. 그것은 배포 구성의 몫이고, 3.5 공지 발행도 같은 값이 비면 422로 막는다.

### 171. 실행 전 복원에서의 삭제 요청 내구성

**미해소(2026-11-05, Story 5.1 판정, 기준 `822816b`).** Story 5.1의 2차 리뷰(blind-hunter)가 append했다.
- **지금 값.** 삭제 요청(`identity_deletion_request`)은 서비스 DB에만 있다. tombstone outbox(`operations_deletion_tombstone`)는 완료된 삭제만 담고, `restore-apply`도 그것만 대조해 재삭제한다(3.10).
- **결과.** 요청 뒤 실행 전에 요청 이전 generation으로 복원하면, 계정이 요청 기록 없이 `ACTIVE`로 돌아온다.
- **이 스토리가 지키는 것.** 복원 불변식(`RestoreInvariants`)이 복원된 DB 안의 모순만 잡는다 — 요청 없는 진행 중 계정, 요청이 있는 ACTIVE 계정. DB 밖에 남은 요청은 볼 수 없다.

**미해소 잔여:** 요청을 외부 기록(tombstone 계열 outbox 또는 별도 내보내기)에 남기고 복원 적용 때 다시 진입시킬지 정하는 일. 실행과 백업 파기를 여는 Story 5.2·5.3이 결정 지점이다.

**미해소(2026-11-06, Story 5.2 판정, 기준 `f95ebb5`).**
- **5.2가 더한 것.** 삭제 **실행** 기록(V31 `identity_deletion_run`·`identity_deletion_step`)과 완료 뒤의 tombstone이다. 실행이 끝나기 전 요청의 외부 기록은 여전히 없다.
- **그대로인 것.** 요청 이전 generation으로 복원하면 요청이 사라지는 것은 그대로다.

**미해소 잔여:** 위와 같다. tombstone 외부 기록과 백업 파기 추적을 넓히는 Story 5.3이 결정 지점이다.

**부분 해소(2026-11-06, Story 5.3 판정, 기준 `d578cc4`).**
- **5.3이 닫은 것.** Worker가 삭제 실행을 처음 집을 때 `CREDENTIALS` 앞 자기 트랜잭션에서 요청 시각(V30)으로 tombstone을 기록한다(`AccountErasureRun.recordTombstone` — 첫 단계가 실패해도, drain 중에도 남는다). 그래서 첫 claim 뒤·운영 완료 전에 복원하면 `restore-apply`가 그 tombstone으로 계정을 다시 지운다(`RestoreApplyTest.운영_완료_전에_복원하면_첫_claim의_tombstone으로_그_계정을_다시_지운다`, `rehearse-restore.sh` 3b — 기대 `matched=2 erased=2`). 열린 집합의 tombstone은 보존 기한과 무관하게 외부 사본에 실린다.
- **남은 창.** 요청 확정과 Worker의 첫 claim 사이(평시엔 다음 dispatch 주기 안, Worker가 멈춰 있으면 그만큼)와 기록 뒤 첫 내보내기 전(≤15분 — 3.10의 완료 tombstone과 같은 창)에 장애가 나 요청 이전 generation으로 복원하면 요청은 여전히 사라진다.

**부분 해소 잔여:** 요청 확정 트랜잭션에서 바로 외부 기록을 남길지(또는 Worker 가용성 경보로 창을 묶을지) 정하는 일. production 삭제를 여는 게이트 G-5가 결정 지점이다.

### 172. 계정 삭제 실행의 운영 관측(메트릭·경보)

**미해소(2026-11-06, Story 5.2 판정, 기준 `f95ebb5`).** Story 5.2의 1차 리뷰(blind-hunter)가 append했다.
- **지금 남는 것.** 실행 로그(단계 code·결과 code·예외 단순 이름)와 heartbeat 실패 경고뿐이다. 사용자·요청 식별자는 남기지 않는다.
- **없는 것.** 새 메트릭·경보가 없다. 그래서 `재시도 필요`로 멈춘 실행 수, 대기 실행의 나이, production·키 없음 때문에 집히지 않는 요청을 운영자가 볼 수 없다.

**미해소 잔여:** `TelemetryAllowlist` 행과 gauge(재시도 필요 수·가장 오래된 대기 나이), 그리고 `deploy/observability/alerts.yml` 경보 규칙. 운영 경보 묶음을 여는 작업이 결정 지점이고, 지명된 스토리는 없다.

### 173. 삭제 실행·단계 기록의 보존 기한

**미해소(2026-11-06, Story 5.2 판정, 기준 `f95ebb5`).** Story 5.2의 1차 리뷰(blind-hunter)가 append했다.
- **지금 값.** V31 두 표는 생명주기 행을 FK로 가리키는 삭제 기록이다. 운영 완료 시각의 근거라서 계정 삭제 뒤에도 영구히 남는다. identity 잔여 수에서도 뺀다.
- **정해지지 않은 것.** 보존 기한과 정리 주체.

**미해소 잔여:** 기록의 보존 기한과 정리 경로. 백업 파기 추적(5.3)과 완료 안내(5.4)가 이 기록을 읽을 수 있으므로 그 결정과 함께 볼 지점이다. 결정 지점은 Story 5.3이다.

**부분 해소(2026-11-06, Story 5.3 판정, 기준 `d578cc4`).**
- **5.3이 정한 것.** 실행 기록은 생명주기 행과 운명을 같이한다 — 백업 파기 판정이 V31 실행의 `operational_erased_at`과 새 `request_id`(V32)로 영향 generation 집합을 닫으므로, 정리 경로를 두지 않고 계정의 생명주기 행(`DELETED`)과 함께 남긴다. 판정 근거가 필요 없어지는 시점(백업 파기 완료 뒤)에 지우는 경로는 만들지 않았다.
- **정해지지 않은 것.** 생명주기 행 자체(삭제된 계정의 `DELETED` 행과 그 FK 기록)의 보존 기한.

**부분 해소 잔여:** 삭제된 계정의 생명주기 행·실행 기록의 최종 보존 기한. G-5 보존 검토(항목 56과 같은 지점)가 결정 지점이다.

**부분 해소(2026-11-09, Story 5.4 판정, 기준 `f259096`) — 같은 지점에 기록이 하나 늘었다.** 5.4는 실행 기록에 전체 삭제 완료 상태·시각(`DELETION_COMPLETED`)을 더했다. 또 재가입 차단 기록 V33 `identity_deleted_identity`를 만들었다. 이 기록은 사용자 열이 없지만 `request_id`가 실행 행(`user_id`)을 거쳐 계정에 닿는다. SPEC Design Notes가 그 보존·해제를 G-5로 미뤘고 해제 경로는 SPEC Never다. 판정은 바꾸지 않는다.

**부분 해소 잔여:** 위와 같고, G-5가 함께 볼 기록에 재가입 차단 digest가 더해졌다.

### 174. 결과 행 없이 끝난 백업 실행의 generation 판정 근거

**미해소(2026-11-06, Story 5.3 판정, 기준 `d578cc4`).** Story 5.3의 1차 리뷰(edge-case-hunter)가 append했다.
- **지금 값.** 백업 파기 판정(`BackupDestructionService`)은 V24 기록만 보고 저장소를 조회하지 않는다(SPEC Ask First). 결과 행 없는 `STARTED`는 그보다 뒤 `STARTED`가 기록될 때까지 완료를 막고, 그 뒤에는 막지 않는다.
- **위험.** SIGKILL·전원 상실로 이동 뒤·기록 전에 죽은 generation은 저장소에 남아 계정을 담을 수 있는데, 판정은 그것을 영향 generation으로 보지 않아 거짓 `DESTROYED`가 날 수 있다.

**미해소 잔여:** 저장소 목록 조회로 그런 generation의 존재·파기를 확인하는 판정 근거. 실저장소 게이트(`epic-3-retro-item-34`)가 결정 지점이다.

### 175. 백업 대기열 기록 실패와 판정의 순서 가정

**미해소(2026-11-06, Story 5.3 판정, 기준 `d578cc4`).** Story 5.3의 2차 리뷰(edge-case-hunter)가 append했다.
- **지금 값.** 판정은 "대기열(`pending/`)이 다음 실행의 `STARTED`보다 먼저 기록된다"는 `backup.sh` 순서에 기대는데, `flush_pending` 실패는 경고뿐이라 결과 행이 남은 채 다음 `STARTED`가 기록될 수 있다.
- **위험.** 그 generation이 저장된 것이면 판정이 막지 않아 거짓 `DESTROYED`가 날 수 있다.

**미해소 잔여:** 대기열이 남으면 새 실행을 시작하지 않거나 판정이 대기열 상태를 알게 하는 규칙. `backup.sh` 단계 순서를 바꾸는 일이라 SPEC 승인 대상이고 주인 없음(항목 133 대기열 멱등과 함께 볼 지점).

### 176. `PURGED` 기록이 끝내 생기지 않는 영향 generation의 운영 해소 경로

**미해소(2026-11-06, Story 5.3 판정, 기준 `d578cc4`).** Story 5.3의 2차 리뷰(blind-hunter)가 append했다.
- **지금 값.** 만료 삭제는 `DESTROYED` 행만 지운다. 손으로 지운 generation이나 복원 뒤 이미 저장소에서 사라진 generation처럼 `PURGED`가 끝내 기록되지 않는 generation이 든 tombstone은 `OVERDUE`로 DB와 외부 사본에 남고 critical 경보가 계속 운다.
- **없는 것.** 운영자가 저장소를 확인한 뒤 저장소별 파기를 표시하는 command나 runbook.

**미해소 잔여:** 운영 command(3.1 표면) 또는 실저장소 게이트의 절차. 주인 없음.

### 177. 시간 기반 순서로 lease 상실을 만드는 백엔드 시험들

**미해소(2026-11-06, 마지막 점검 재획득 시험 경합 fix 판정, 기준 `5e4e61d`).** 이 fix의 1·2차 리뷰(blind-hunter)가 append했다.
- **지금 값.** `PlanVerificationTest`의 수집 중 재획득 시험 셋과 `BybitConnectionRevocationTest`·`BybitReconnectionTest`·`PlanEndTest`의 `slowPageStores` 사용 시험이 `pg_sleep` trigger로 늦춘 사이에 `expireLease`를 건다.
- **위험.** 만료 전에 holder가 그 단계를 벗어나면 같은 모양으로 간헐 실패한다 — `aContinuationReclaimedInItsFinalSweepPublishesOnceAtItsTcFinal`이 바로 이 구조로 CI(run 42197758258 2차 시도)에서 실패했다. 지금까지 이 시험들의 CI 실패 기록은 없다.
- **연결.** `PlanEndTest`는 159의 대상이라 이 구조가 159의 원인 후보다.

**미해소 잔여:** 같은 잠금 문 방식(`FinalSweepDoor`, `DEVELOPMENT.md` `### 간헐 실패`)으로 옮길지. 주인 없음 — 그 시험이 실패하거나 159를 조사할 때가 결정 지점이다.

**주인 지정(2026-11-10, Epic 5 회고 AI-4 chore, 기준 `721c3e6`).** `spec-ci-flaky-run-verdicts.md`가 이 항목과 159의 주인을 정했다. 상태(미해소)와 건수는 바꾸지 않는다.
- **주인.** 다음 fix chore(가칭 `spec-lease-loss-test-doors.md`)다. 위 "지금 값"의 시험들 — `PlanVerificationTest`의 수집 중 재획득 셋과 `BybitConnectionRevocationTest`·`BybitReconnectionTest`·`PlanEndTest`의 `slowPageStores` 사용 시험 — 을 `pg_sleep` + `expireLease`의 시간 기반 순서에서 `FinalSweepDoor`식 잠금 문으로 옮긴다. 이 chore는 SPEC Never에 따라 시험 코드를 바꾸지 않았다.
- **159와의 관계.** 그 fix가 `PlanEndTest`를 옮긴 뒤 159를 다시 판정한다.
- **기록 근거.** 이 계열의 CI 실패(run 42197758258 2차 시도)는 시험 구조가 원인으로 확인돼(fix #146) `ci-run-verdicts.md`에 `간헐 시험` 행으로 소급 기록했다. 앞으로 위 "지금 값"의 시험이 CI에서 실패하면 원인이 확정되기 전에는 `미조사`(후속 177)로 쓰고, 시험 구조가 원인으로 확인됐을 때만 `간헐 시험`으로 쓴다.

**해소(2026-11-11, lease 상실·지연 시험의 잠금 문 이전 fix, 기준 `ab6d798`).** `spec-lease-loss-test-doors.md`가 위 "지금 값"의 시험을 모두 잠금 문으로 옮겼다. 운영 코드는 바꾸지 않았다.
- **공용 문.** PR #146의 `FinalSweepDoor`를 `PlanSyncTestBase`의 `PageDoor`로 일반화했다. 두 정수 advisory 키(`objsubid=2`)에 문마다 `objid`(`PageDoorKey`)를 나누고, holder 안전 시한·획득 `lock_timeout`·한 번 열기·실패해도 trigger·함수를 지우는 정리를 그대로 갖는다.
- **만료형.** `PlanVerificationTest`의 다섯 자리 — 계속 진행 재획득(본 수집 세 번째 페이지), 세 번째 중단(holder마다 첫 페이지), 정밀 검증 재획득(두 번째 페이지), 버려진 창 helper(R1·R2 셋, 첫 TRANSFER_IN 페이지), 마지막 점검(문 앞 막힘을 본 뒤 열기) — 은 지정 페이지 저장 트랜잭션 안에서 trigger가 계획 작업 lease를 만료시킨다. 페이지 수를 기다리지 않는다.
- **지연형.** 네 자리 모두 문 앞에 선 페이지를 본 뒤 명령을 `runBehindDoor`로 보낸다. 그 helper는 명령 전에 문 앞 페이지 트랜잭션 뒤에 줄 선 backend 수(`pg_blocking_pids`를 따라가 행·tuple 대기를 모두 센다)를 기준값으로 재고, 그 수가 커지거나(`QUEUED_BEHIND_PAGE`) 명령이 먼저 끝난 것(`FINISHED_FIRST`)을 본 뒤 문을 연다. `PlanEndTest`는 종료가 작업 행을 건드리지 않으므로 먼저 끝나는 갈래임을 단정한다. `BybitConnectionRevocationTest` 둘과 `BybitReconnectionTest` 하나는 해제가 작업 행을 UPDATE하므로 줄 서는 갈래이고(아래 깨끗한 세 실행의 로그 줄에서 세 자리 모두 `QUEUED_BEHIND_PAGE`였다), 그 갈래는 단정하지 않는다 — 명령이 줄 서지 않고 끝나도 단정은 같다(SPEC I/O 표). 실패 경로(줄 서기를 보지 못함·결과 시간 초과·문 열기 실패)는 문을 열고 명령을 상한까지 기다린 뒤 원래 예외를 던진다. 막힘은 문 대기의 `pg_locks.waitstart`부터 재고, 그 뒤 lease의 절반 안에 열지 않으면 문 holder가 스스로 풀고 시험이 실패한다.
- **회귀 방지.**
  - `src/test` 아래 시험 소스·resources의 `pg_sleep`(대소문자 무관)이 0건이고 `PageDoorSourceTest`가 다시 생기지 않게 막으며, `PageDoorKey`의 objid가 서로 다른지도 본다.
  - `aDoorLeavesNothingBehindWhenItsTestFails`가 닫힌 문과 만료형 trigger만 건 문 둘 다에서, 문 안 실패 뒤 trigger·함수·문 키 잠금이 남지 않고 실패에 정리 예외가 붙지 않음을 고정한다.
  - `aDelayingDoorLetsGoAndFailsPastItsLeaseBound`가 상한 1초로 닫은 지연형 문에 실제 페이지를 세운 뒤 holder가 스스로 문을 풀고 문 열기가 "holder failed"로 실패하며, 그 사이 작업 lease가 끝나지 않았음(`resume_count=0`으로 성공)을 고정한다.
  - 지정 페이지 술어를 일부러 틀린 국소 실험은 결말 단정이 작업 상태와 함께 실패했다(커밋하지 않았다).
- **검사.**
  - SPEC Verification 명령(네 시험 클래스와 `PageDoorSourceTest`, `--rerun`)을 최종 코드로 돌린 실행 가운데 세 번이 모두 통과했다(Windows 11 로컬, 회당 약 4~6분, 시험 120건).
  - 그 사이 다섯 번은 183(Windows 루프백 `SocketException: Bad address: listen`)으로 실패했다. 네 번은 시험 컨텍스트의 Tomcat·관리 포트가, 한 번은 Gradle 시험 worker 연결이 listen하지 못했다. 컨텍스트 하나가 실패하면 같은 JVM의 클래스가 모두 컨텍스트 실패 임계로 실패하므로 실패한 시험은 모두 컨텍스트 적재 실패였고, 시험 본문이 돌아 실패한 것은 없다 — 문과 무관한 183의 관측이다.
  - 전체 build는 이 판정의 근거에 넣지 않았다.
  - **PR CI.** PR #158의 CI(run 42978397509, 커밋 `4cbe2cb`)가 아홉 job 모두 통과했다. `backend-quality`는 13분 1초였고, 옮긴 시험을 포함한 전체 백엔드 시험이 통과했다. 같은 PR의 앞선 실행(run 42978368692)은 뒤 push의 concurrency로 취소돼 판정 원장에 기록하지 않는다. 머지 커밋은 `477dad3`이다.
- **앞으로의 기록 규칙.** 이 시험들(위 만료형·지연형 자리와 문 자가 시험)이 CI에서 실패하면 재실행 전에 `ci-run-verdicts.md`에 판정 `미조사` 행을 쓰고 후속 칸에 159를 단다. 시험 구조가 원인으로 확인됐을 때만 `간헐 시험`으로 쓴다.

### 178. 전체 삭제 완료 판정자의 sweep 상한과 고착 실행의 관측

**미해소(2026-11-09, Story 5.4 판정, 기준 `f259096`).** Story 5.4의 1차 리뷰(blind-hunter·edge-case-hunter)가 append했다.
- **지금 값.** `BackupDestructionJudge`가 판정마다 운영 완료 실행 전부(`awaitingDeletionCompletion`, `BackupDestructionJudge.java:173`)를 상한 없이 읽고 `destroyedAmong`(`:177`)에 `IN (:list)`로 넘긴다.
- **위험.** 파기가 끝나지 않는 generation(176)이나 법정 보존 항목이 있는 동안 목록이 자라 바인드 수 상한에 닿을 수 있다. 판정자가 보존 기한(운영 완료 + 35일)보다 오래 멈추면 `DESTROYED` tombstone이 만료 삭제돼 그 실행은 `OPERATIONAL_DATA_ERASED`에 영구히 남는다. 그 고착을 알리는 메트릭·경보가 없다.

**미해소 잔여:** sweep의 묶음 상한과 고착 실행의 gauge·경보. 삭제 실행 메트릭·경보(172)와 같은 주인이고, 지명된 스토리는 없다.

### 179. 복원 리허설의 삭제 표본 seed가 plan·sync 계산 표를 넣지 않는다

**미해소(2026-11-09, Story 5.4 판정, 기준 `f259096`).** Story 5.4의 1차 리뷰(blind-hunter)가 append했다.
- **지금 값.** `rehearse-restore.sh`의 삭제 표본 seed(`:316` 이하)는 5.4가 source·자격정보·snapshot·sync 작업·plan 확인·문의·동의로 넓혔다. 하지만 plan의 계획 정의·종료·완료 행과 sync의 공식 계산 결과(`sync_plan_calculation` 계열)는 넣지 않는다.
- **위험.** 잔여 세기는 카탈로그로 모듈 표 전부를 보지만, 행이 없던 표는 `residual_plan=0`·sync 잔여 0 단정이 그 표의 재삭제를 증명하지 못한다. 손으로 쓴 열 목록 SQL은 스키마 변경에 약해, 로컬 리허설 없이 넓히면 CI 위험이 크다.

**미해소 잔여:** 실제 흐름(첫 스냅샷 → 발행)으로 표본을 만들지, seed를 그 표들로 넓힐지. `epic-2-retro-item-24`(erase 완전성)와 같은 지점이고, 지명된 스토리는 없다.

### 180. 삭제 왕복 뒤 로그의 tombstone 키 누출 판정

**미해소(2026-11-10, Epic 5 회고 AI-2·AI-3 chore 판정, 기준 `9774e4c`).** 그 chore의 1차 리뷰(verification-gap)가 append했다.
- **지금 값.** `verify-same-origin.sh`는 tombstone digest 키를 감사 키와 따로 만들어 공급하고, 값이 스택 로그에 없는지 본다. 그런데 그 키를 실제로 쓰는 삭제 실행(tombstone 기록·digest 계산)은 스크립트가 끝난 뒤의 주입 origin e2e(`account-deletion.spec.ts`)에서 처음 돈다.
- **빈틈.** `ci.yml`의 e2e 단계 뒤에는 compose 정리·진단 업로드만 있다. 삭제 왕복을 지난 로그를 같은 누출 판정으로 다시 보는 검사가 없다. 앱 쪽 `ConfiguredSecrets`·`LogSecretGuard`가 이 키를 감지하므로 로그 encoder 경로는 덮이지만, encoder를 거치지 않는 출력은 보지 않는다.

**미해소 잔여:** e2e 뒤 compose 로그의 누출 판정을 `.github/scripts`에 둘지, 스크립트 안에 짧은 삭제 왕복을 둘지(한글 문구의 curl은 CP949 문제 246). 지명된 작업은 없다. CI 판정을 바꾸는 일이라 `epic-5-retro-item-51`(CI 시한·간헐 실패)과 함께 볼 수 있다.

**미해소 — 재확인(2026-11-10, Epic 5 회고 AI-4 chore 판정, 기준 `a3dd2ff`).** `epic-5-retro-item-51`을 닫은 `spec-ci-flaky-run-verdicts.md`(PR #153)는 설치 단계 시한과 판정 원장만 다뤘고, e2e 뒤 로그의 누출 판정은 바꾸지 않았다. 지명된 작업은 여전히 없다.

### 181. develop push e2e `first-snapshot` 비-USDT 추정 환산 시험의 실패

**미해소(2026-11-10, Epic 5 회고 AI-4 chore 판정, 기준 `a3dd2ff`).** 그 chore가 `ci-run-verdicts.md`에 소급 기록하며 append했다(판정 `미조사`).
- **관측.** develop push run 42134981077(PR #141 머지 `f95ebb5`)의 `same-origin-integration` → `Story 1.1 회귀 (Nginx origin)`에서 1건이 실패했고 72건은 통과했다. 실패한 것은 `first-snapshot.spec.ts:657`이다 — `connectAndSavePlan`(`support/firstPlan.ts:60`)이 첫 확인 성공 문구를 본 뒤 첫 값 `1,234.56 USD`를 기본 5초 안에 찾지 못했다.
- **재현되지 않는 조건.** 같은 커밋의 PR #141 run과 다음 develop push(42142542381)는 green이었다. 재실행하지 않았다.
- **증거의 수명.** 진단 artifact `same-origin-diagnostics-42134981077-1`(trace·스크린샷·compose 로그)은 2026-11-13T00:23:43Z에 만료된다. 그 뒤에는 job 로그(90일)만 남는다.

**미해소 잔여:** 값 표시가 늦은 것인지(새 스택·직렬 suite의 앞 시험 부하), 다른 사용자의 값이 섞인 것인지 가른다. 그 결과로 대기 방식이나 fixture를 고칠지 정한다. 지명된 작업은 없다. 같은 실패가 다시 나면 `ci-run-verdicts.md`에 새 행(후속 181)을 쓰고 그때 조사한다.

**미해소 — 원인 확정(2026-11-10, 진단 artifact 조사, 기준 `d504d39`).** 원인은 시험이 아니라 제품의 비원자 읽기 결함이다. artifact가 만료되기 전에 trace를 열어 확인했다.
- **trace의 응답.** `POST /api/snapshots/first`가 202 `QUEUED`로 접수됐다(00:22:05.006). 1초 뒤 상태 조회 `GET /api/snapshots/first`(00:22:06.034)는 200을 냈는데, 본문이 `{"connected":true,"restricted":false,"job":{"jobId":"30ce9b5b-…","status":"SUCCEEDED"}}`였다. 작업은 `SUCCEEDED`인데 공식값 필드(`decimal`·`currency`·`measuredAt`·`snapshotId` …)가 하나도 없었다.
- **화면.** 실패 시점의 스크린샷과 접근성 트리 모두 같다. "첫 공식 현재가치를 확인했습니다."만 보이고 금액·측정 시각·세 신뢰 축·계획 조건 링크가 없다. 세 번째 가설(값이 아예 오지 않음)에 해당하고, 늦은 표시나 다른 사용자 값의 혼입이 아니다.
- **서버 원인.** `SyncJobService.state`(`backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java:263-266`)는 트랜잭션 없이 두 번 읽는다 — 먼저 `portfolios.officialValue(userId)`, 다음에 `jobs.latestOf(…)`다. 발행(`publish`, 같은 파일 `:768` 이하)은 `publishFirst`와 작업 `SUCCEEDED`를 한 트랜잭션으로 커밋한다. 그래서 첫 읽기가 커밋 전(공식값 없음)이고 둘째 읽기가 커밋 뒤(`SUCCEEDED`)이면, 커밋된 적 없는 조합이 응답에 실린다.
- **화면이 굳는 이유.** `useFirstSnapshot`은 작업이 `QUEUED`·`RUNNING`일 때만 다시 조회한다(`frontend/src/session/useFirstSnapshot.ts:104`). `SUCCEEDED`를 받으면 멈추므로, 사용자는 새로 고칠 때까지 값 없는 성공 문구에 머물고 계획 조건으로 가는 링크도 받지 못한다(`FirstSnapshotPanel`의 `canPlan`이 값에 기댄다). CI에서는 발행 커밋이 두 읽기 사이에 끼는 드문 창에서만 나서 한 번만 실패했다.

**미해소 잔여:** 제품 fix가 필요하다. `state` 읽기를 읽기 전용 트랜잭션 하나(대시보드처럼 `REPEATABLE READ`)로 묶거나, 작업을 먼저 읽고 공식값을 나중에 읽어 `SUCCEEDED`이면 값이 반드시 보이게 한다. 이 경합을 결정적으로 재현하는 시험도 함께 둔다. 같은 "두 번 읽는" 모양이 같은 메서드의 해제 분기(`:244-246`)와 다른 상태 조회에 있는지도 그 fix가 확인한다. 지명된 작업은 없다. `ci-run-verdicts.md`의 42134981077/1 행은 추가 전용이라 `미조사`로 남고, 판정 변경은 이 절이 소유한다.

**해소(2026-11-10, `spec-first-snapshot-state-atomic-read.md` fix, 기준 `d6c6872`).** 위 잔여의 첫 안을 택했다.
- **제품.** `SyncJobService`에 읽기 전용·`ISOLATION_REPEATABLE_READ` `TransactionTemplate` `stateReads`를 두고(대시보드의 `dashboardReads`와 같은 설정), `state`의 접근 판정부터 반환까지를 그 안에서 읽고, 대시보드처럼 스냅숏을 닫은 뒤 계정 접근을 한 번 더 본다(읽는 도중 커밋된 삭제 요청이 이전 스냅숏 값을 내보내지 않게). 응답은 커밋된 한 시점 — 발행 전(작업 진행 중·값 없음)이거나 발행 뒤(`SUCCEEDED`·값 있음) — 만 담는다. 잠금·쓰기는 들이지 않았고 분기(연결 없음·해제·제한·rebase·일반)의 의미는 그대로다. 해제 분기(`jobs.latest`와 `officialValue`)와 rebase 분기(`accountRebaseAllowed` → `latestOf`)의 두 읽기도 같은 스냅숏 안에 들어왔다.
- **시험.** `FirstSnapshotTest.aPublicationCommittedBetweenTheTwoReadsNeverYieldsSuccessWithoutAValue`. 실제 발행 뒤 포인터 행을 지우고 작업을 `RUNNING`으로 되돌려 발행 전을 만든다. 기존 helper `holdRowLock`의 트랜잭션이 `sync_job`을 `ACCESS EXCLUSIVE`로 쥔 채 포인터 복원과 작업 `SUCCEEDED`를 함께 쓴다(Worker 없는 발행 합성). 조회가 공식값을 읽은 뒤 `latestOf`에서 막힌 것을 `awaitBlockedOn`(`pg_stat_activity`)으로 확인하고 커밋한다. 시간·재시도가 아니라 잠금이 순서를 고정한다. 트랜잭션을 뺀 옛 `state`에서는 `[SUCCEEDED는 반드시 값을 담는다]`로 실패했고 새 코드에서는 통과했다.
- **다른 상태 조회.** `/inputs`·`/cashflow-classification`·`/cashflow-conversion`·`/plan-publication`의 조회는 SPEC 조사에서 이 모양(따로 커밋되는 두 사실을 문장마다 따로 읽어 섞는 것)이 아니었으므로 고치지 않았다.
  - `/inputs`(`initialCollectionState`)는 작업 한 문장만 읽는다.
  - `/cashflow-classification`은 작업 행과 결과 묶음을 한 문장(LEFT JOIN)으로 읽는다.
  - `/cashflow-conversion`은 상태와 결과를 한 JOIN 문장으로 읽고, 뒤따르는 보류 자산 읽기는 이미 커밋된 불변 행이다.
  - `/plan-publication`은 상태를 먼저 읽고, attempt 행이 자기 `plan_id`를 지닌다.

### 182. develop push 백업 리허설 `catalog` 결함 주입 단계의 정지

**미해소(2026-11-10, Epic 5 회고 AI-4 chore 판정, 기준 `a3dd2ff`).** 그 chore가 `ci-run-verdicts.md`에 소급 기록하며 append했다(판정 `미조사`).
- **관측.** develop push run 42549803177(PR #146 머지 `2441364`)의 `backup-rehearsal` → `백업 리허설`이 `3. 단계 실패 catalog(gen_20260102)` 머리를 낸 뒤 37분 동안 출력 없이 멈췄고, 단계 상한 40분에 끊겼다. 앞선 dump·encrypt·tamper-oci 결함 주입은 각 20초 안에 통과했다.
- **재현되지 않는 조건.** 다음 develop push(42563422690)의 같은 job은 green이었다. 재실행하지 않았다.
- **증거의 공백.** 단계가 상한에 끊겨 진단 디렉터리가 비었고, `backup-rehearsal-diagnostics-42549803177-1`은 올라가지 않았다(`No files were found`). 남은 증거는 job 로그(90일, 2027-02-07 무렵까지)뿐이다.

**미해소 잔여:** catalog 결함 주입 경로(`age -d | pg_restore --list` 실패)에서 무엇이 기다리는지 가른다(배경 실행 + `wait`, 파이프 한쪽의 정지). 단계 상한에 끊길 때도 진단이 남게 할지도 정한다. 지명된 작업은 없다. 리허설의 PR 경로 분리(164)와 같은 지점에서 볼 수 있다.

### 183. Windows 로컬 전체 백엔드 build의 루프백 소켓 `Bad address`

**미해소(2026-11-11, 시험 PostgreSQL 컨테이너 수명 fix가 append, 기준 `df5a9c6`).**
- **관측.** 2026-11-10·11 Windows 11 로컬 전체 `backend/gradlew --project-dir backend build`에서 Java 루프백 소켓이 간헐적으로 `java.net.SocketException: Bad address`로 실패했다.
  - `listen` — 내장 Tomcat·관리 포트의 bind에서 컨텍스트 로드가 실패했다. 2026-11-11에는 `AuthDeletionPathTest`였고, 이 fix 검증의 전체 build(1,774건 중 288건 실패)에서는 `ActionGrantTest`·`BybitConnectionTransactionTest`·`OperatorRecoveryTest`였다. 대상 시험만 돌린 실행에서 `PlanEndTest`도 한 번 그랬다.
  - `connect` — 같은 전체 build의 `FirstSnapshotTest` 1건이다.
  - 같은 컨텍스트 구성(`AuthFlowTestBase` 하위)의 뒤 클래스는 Spring 컨텍스트 실패 임계로 함께 실패한다. 그래서 로컬 전체 green을 받을 수 없다.
- **이 PC 밖에서는 나지 않는다.** 같은 develop 코드의 CI는 green이다. 이 PC의 소켓 수·동적 포트 범위는 정상이었다.
- **연쇄는 닫혔다.** 이 실패가 공유 PostgreSQL 컨테이너를 함께 멈춰 같은 JVM의 시험이 `Connection refused`로 무더기 실패하던 경로는 `spec-test-container-context-lifecycle.md`가 닫았다. 이 fix 뒤 전체 build의 `Connection refused`는 0건이다.

**미해소 잔여:** 원인(보안·네트워크 필터 소프트웨어 등)을 가른다. 그동안의 로컬 검증 절차(그 클래스만 다시 돌리기, 전체 green은 CI `ci-required`)는 이 fix가 `DEVELOPMENT.md` `### 간헐 실패`에 적었다. 코드로 우회하거나 재시도하지 않는다. 주인은 없다.

### 184. 운영 코드의 두 정수 advisory 키를 막는 소스 스캔

**미해소(2026-11-11, lease 상실·지연 시험의 잠금 문 이전 fix가 append, 기준 `ab6d798`).** 이 fix의 리뷰(blind-hunter)가 append했다.
- **전제.** 시험의 페이지 저장 문은 두 정수 advisory 키(`pg_locks` `objsubid=2`)를 쓰고, `PlanSyncTestBase`의 계획 잠금 대기 helper(`awaitBlockedOnPlanLock`·`awaitPlanLockReleased`)는 그 키를 빼고 센다. 운영 코드의 advisory 잠금이 모두 bigint 하나(`hashtextextended`, `objsubid=1`)라서 성립한다.
- **빈틈.** 그 전제를 고정하는 검사가 없다. 운영 코드에 두 정수 키가 생기면 계획 잠금 대기 helper가 그 잠금을 조용히 놓친다.
- **이 fix에서 하지 않은 이유.** 운영 소스의 advisory 호출 꼴이 여럿(`xact`·session·`shared`)이라 스캔 규칙을 따로 정해야 하고, 이 fix는 SPEC상 시험 코드만 바꾼다.

**미해소 잔여:** `src/main/java`의 advisory 호출이 모두 단일 bigint 키임을 단정하는 소스 스캔을 둘지 정한다. 주인은 없다.
