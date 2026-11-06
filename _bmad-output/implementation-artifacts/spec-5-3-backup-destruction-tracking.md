---
title: 'Story 5.3 외부 tombstone으로 복원을 차단하고 30일 내 백업 파기 추적하기'
type: 'feature'
created: '2026-11-06'
status: 'done'
baseline_commit: '49c2e751c8bd48a9b413276cf83f433edffd327d'
review_loop_iteration: 1
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-2-account-data-erasure.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 삭제 tombstone은 삭제 실행이 끝날 때(5.2 `IDENTITY` 단계)에야 생겨, 그 전에 복원하면 요청이 사라진다(deferred 171). 또 tombstone에는 영향 백업 generation·파기 기한·상태가 없고, 계정이 들어 있을 수 있는 백업이 두 저장소에서 언제 다 파기됐는지 판정·경보하는 곳이 없다.

**Approach:** Worker가 삭제 실행을 처음 집는 단계에서 tombstone(`DELETION-TOMBSTONE-1.0`)을 기록하고, 실행이 끝나면 영향 generation 집합을 닫는다. Worker의 주기 판정이 백업 기록(V24)과 삭제 실행 기록(V31)을 대조해 삭제마다 영향 generation·두 저장소 파기 여부·기한(요청 + 30일)을 기록하고, 두 저장소 파기가 모두 확인돼야 `백업 파기 완료`다. 임박·초과는 완료가 아니라 gauge·경보·감사 SYSTEM 행이다. crypto-erasure는 넣지 않는다.

## Boundaries & Constraints

**Always:**
- tombstone은 Worker 단계 모드의 첫 단계에서(5.2 단계 목록의 `CREDENTIALS` 앞, 같은 fencing 규칙) 기록하고 멱등이다. 담는 값은 비식별 keyed digest·삭제 request ID·요청 시각(V30 `requested_at`)·영향 generation·파기 기한(요청 + 30일)·상태·정책 버전 `DELETION-TOMBSTONE-1.0`뿐이다 — 금융 상세·자격정보·소셜 원 식별자·user_id 금지. 외부 사본은 지금처럼 서비스 DB·백업과 다른 tombstone 전용 age key로 암호화한다.
- tombstone 보존(`destroy_after`)은 요청 시각 + 35일이고, 실행이 끝나면 `max(요청, 운영 완료) + 35일`로 늘린다 — 계정이 든 마지막 generation(운영 완료 전에 dump를 시작한 것)의 29일 파기보다 늘 길다. 줄이지 않는다.
- 영향 generation은 운영 완료 시각 전에 dump를 시작한(V24 `STARTED`) generation 전부이고, 실행이 끝나기 전에는 열린 집합이다. `백업 파기 완료`는 실행이 끝났고 그 집합의 모든 generation에 두 저장소(`oci`·`offsite`) `PURGED` 기록이 있을 때만이다. 한쪽만·기록 없음·`PURGE_FAILED`는 완료가 아니다.
- 기한(요청 + 30일)이 지났는데 완료가 아니면 `OVERDUE`이고 감사 SYSTEM 행을 한 번 남긴다. 기한 48시간 안의 미완료와 초과 수는 Worker gauge로 내고 `alerts.yml` 경보(+ promtool 시험)가 운다. 백업을 앞당겨 파기하지 않는다(재시도는 다음 `backup.sh` 실행의 기존 파기 재시도다).
- tombstone 기록·보존 연장·완료·초과·내보내기·복원 적용·만료 삭제는 수와 code만 담은 감사 SYSTEM 행을 남긴다(digest·request ID·user_id 없음). tombstone 키는 Worker·`tombstone-export`·`restore-apply`·`deletion-sample`에만 두고 API에는 주지 않는다 — 운영 compose의 `worker`에 키를 배선한다.
- 내보내기 성공 시각을 기록하고 Worker gauge·경보(`TombstoneExportStale`)로 멈춘 내보내기를 알린다.
- 판정은 Worker·`all`의 주기 작업(5.2 drain·역할 규칙)이고 production에서도 돈다(판정은 쓰기 삭제가 아니다).

**Ask First:**
- 기존 V24·V25 행의 의미나 `backup.sh`·`restore.sh`의 관측 가능한 단계 순서가 바뀌어야 할 때.
- 판정 근거가 V24 `PURGED` 기록만으로 부족해 저장소를 직접 조회해야 할 때.

**Never:** crypto-erasure·generation별 백업 키, 백업 조기 파기, 계정 단위 파기, 실저장소(OCI·offsite) 실제 파기·버전 보관 해제의 증거를 리허설로 대신 적는 것, 완료 안내 화면(5.4), tombstone 디지스트 키 회전, API 프로세스에 tombstone 키, 새 런타임 의존.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 첫 claim | 요청된 계정을 Worker가 집음 | tombstone 1행(요청 시각·기한·`PENDING`), 감사 행 | 두 번째 claim도 1행 |
| 실행 전 복원 | 첫 claim 뒤·운영 완료 전에 복원 | `restore-apply`가 그 계정을 다시 지움 | N/A |
| 운영 완료 | `IDENTITY` 커밋 | 영향 generation 집합 닫힘, `destroy_after` 연장 | N/A |
| 파기 완료 | 영향 generation 전부 두 저장소 `PURGED` | `DESTROYED`·완료 시각·감사 행 | N/A |
| 한쪽 실패 | offsite `PURGE_FAILED`, oci `PURGED` | 미완료 유지 | 기한 넘으면 `OVERDUE` |
| 기한 초과 | 요청 + 30일, 미완료 | `OVERDUE`·감사 1회·gauge·경보 | 완료로 표시 안 함 |
| 내보내기 정지 | 마지막 성공 > 1시간 | `TombstoneExportStale` | N/A |

</frozen-after-approval>

## Code Map

- `backend/src/main/resources/db/migration/V25__operations_deletion_tombstone.sql` -- 열 `request_id`·`user_digest`·`requested_at`·`destroy_after`(CHECK `= requested_at + 840h`)·`origin`. 새 V32가 열(정책 버전·기한·상태·완료 시각)과 CHECK 완화(`destroy_after >= requested_at + 840h`)를 더한다. V25는 고치지 않는다.
- `operations/DeletionTombstoneApi.java`·`internal/DeletionTombstoneService.java`(`record`·`carry` :275-290)·`DeletionTombstoneRepository.java:25-29`(지금 `requested_at` = `statement_timestamp()` — 요청 시각을 받게 바꾼다) -- 내보내기 형식(머리 `DELETION-TOMBSTONE-1.0`, 줄 `digest request_id requested_at destroy_after` :79-81·181-189)에 기한·상태·영향 generation을 더하고 `restore-apply` 판독(`RestoreApplication.java:171-225`)을 맞춘다.
- `AccountErasureRun.java:188`·`AccountErasure.recordRequestedTombstone` :192-197 -- 지금 `IDENTITY`에서 기록. 첫 단계로 옮기고 `IDENTITY`는 보존 연장·집합 닫기만.
- `identity` V31 `identity_deletion_run` -- `request_id`가 없다. V32가 열을 더하고 claim이 채워 판정이 request ID ↔ 운영 완료 시각을 잇는다(V30 요청 행은 완료 때 지워진다).
- 백업 기록: V24 `operations_backup_event`(`STARTED`·`SUCCEEDED`·`PURGED`(store)·`PURGE_FAILED`·`PURGE_OVERDUE`), `BackupEventRepository`·`BackupRecordService.java:58-92`(SYSTEM 감사 패턴), `BackupMetrics.java:23-29`·`TelemetryAllowlist.java:130-133`, `deploy/observability/alerts.yml:55-112`·`alerts.test.yml`.
- 판정 패턴: `engagement/internal/AdPerformanceJudge.java`(`@Scheduled`, Worker·`all`) -- 같은 모양의 operations 판정자를 둔다. 운영 완료 시각은 identity 공개 API로 받고 판정자는 루트가 잇는다(operations는 identity 쓰기 대상이 아니다).
- 내보내기: `deploy/production/tombstone-export.sh`·`systemd/bokrilog-tombstone-export.timer`, 성공 기록은 새 V32 표 또는 행(수·code), `deploy/production/compose.yaml`(`worker` :285-299에 키 없음 — 배선).
- 리허설: `deploy/production/rehearse-backup.sh`·`rehearse-restore.sh`(MinIO, 가상 시계 `BOKRILOG_BACKUP_NOW`) -- 실행 전 복원 시나리오와 영향 generation 파기 판정을 더하되 실저장소 증거가 아님을 그대로 적는다.
- 미룬 항목: 171(요청 시각 내구성)·138(내보내기 관측)을 닫는다. 173(V31 보존)은 판정이 V31 완료 시각을 근거로 쓰므로 생명주기 행과 같이 보존한다고 정한다. 133(대기열 재전송 멱등)은 완료 판정이 `PURGED` 존재만 보므로 중복 행에 영향받지 않는다. 139(키 회전)·140(manifest 대조)·132·162·170은 지나간다.
- 걸린 열린 회고 액션: `epic-3-retro-item-33`(실OCI 파기 증거 항목)은 실저장소 증거를 출시 게이트로 남기며 일부 다룬다. `epic-3-retro-item-28`(비밀 가림)·`-29`(복원 사본 전역 정지)·`-31`(셸 규칙 검사)·`-32`(일회성 역할 base)·`-34`(실저장소 출시 게이트)·`-35`(as-built 문서)는 건드리지 않고 지나간다.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/resources/db/migration/V32__deletion_backup_destruction.sql` -- tombstone 열·CHECK 완화, 영향 generation 표(request ID·generation·저장소별 파기 확인), V31 `request_id`, 내보내기 성공 기록 -- 상태의 권위.
- [x] `backend/.../operations/**` tombstone API·서비스·저장소 -- 요청 시각 기록, 보존 연장, 영향 집합, 완료·초과 판정, 감사 SYSTEM 행, 내보내기 형식 확장 -- 핵심.
- [x] `backend/.../AccountErasure*.java`·`identity/**` -- 첫 단계 기록, `IDENTITY`의 연장·닫기, run의 `request_id` -- 기록 시점.
- [x] 루트 판정 배선(Worker·`all`) + `BackupMetrics` 또는 새 gauge + `TelemetryAllowlist` 행 -- 경보 근거.
- [x] `RestoreApplication.java`·`deploy/production/tombstone-export.sh`·`compose.yaml` -- 새 형식 판독·내보내기 성공 기록·Worker 키 배선.
- [x] `deploy/observability/alerts.yml`·`alerts.test.yml` -- 기한 임박·초과·내보내기 정지 경보와 promtool 시험.
- [x] `deploy/production/rehearse-*.sh` -- 실행 전 복원 재삭제, 영향 generation 파기 판정 리허설(실저장소 증거 아님 표기).
- [x] 시험: 매트릭스 일곱 행, 최소 필드(금지 값 없음), 두 저장소 중 한쪽만 `PURGED`, 보존 연장이 줄지 않음, API 프로세스에 키 bean 없음, 감사 행에 식별자 없음.

**Acceptance Criteria:**
- Given 삭제가 완료됐고 영향 generation이 두 저장소에서 모두 `PURGED`이면, when 판정이 돌면, then 그 tombstone은 `DESTROYED`이고 운영 완료와 다른 시각으로 기록된다.
- Given 운영 완료 전 복원이면, when `restore-apply`가 돌면, then 그 계정은 다시 지워지고 보고에 수만 남는다.
- Given 리허설이 MinIO 객체 파기를 보였을 때, when 결과를 기록하면, then 실저장소 파기 증거로 적지 않는다.

## Spec Change Log

- 구현에서 확정한 것(2026-11-06) — tombstone 상태 `PENDING|OVERDUE|DESTROYED`(OVERDUE → DESTROYED는 된다, 완료는 끝), 집합 닫힘은 `closed_at`(운영 완료 시각, ms)이고 외부 사본 줄은 여덟 칸 `digest request_id requested_at destroy_after backup_deadline status OPEN|CLOSED generations`(복원은 네 칸 옛 줄도 읽고 그것을 요청 시각에 닫힌 집합으로 본다 — 옮길 때 닫힌 시각은 `destroy_after − 35일`). 백업 파기 기한은 열이 아니라 `requested_at + 720h` 식 하나다(시험·리허설의 직접 INSERT가 깨지지 않고 두 값이 갈라질 수 없다). V32 전 행은 삭제를 끝낸 트랜잭션이 쓴 것이라 `closed_at = requested_at`으로 채웠다. **영향 generation 규칙:** 운영 완료 전에 시작한 `STARTED` 가운데 `SUCCEEDED`가 있는 것(저장된 시도의 시작 시각), 요청 전에 두 저장소에서 이미 `PURGED`된 것 제외, 기록 하한은 가장 이른 요청 − 90일. 파기 code는 `generations/` 규칙인 `AGE|COUNT|BYTES`이고 성공 기록 뒤의 것만 센다(`INCOMING_CLEARED`·`INCOMING_AGE`·로컬 임시본은 아니다). 실패한 generation은 `generations/`에 오르지 않아 영향 generation이 아니고(부분 object는 incoming 정리·30일 규칙·`BackupPurgeOverdue`의 몫), 결과 없는 실행 중 generation(생성일 + 3일 안)은 기록하지 않지만 완료를 막는다 — V24 기록만으로 판정하고 저장소를 조회하지 않았다(Ask First 회피). 첫 단계 기록은 `CREDENTIALS` 앞 자기 트랜잭션(`hold` 뒤 멱등 `record`)이라 첫 단계가 실패해도 남고 drain 중에도 한다. 마지막 단계도 기록을 다시 해 본다(이 스토리 전에 시작한 실행). 판정은 루트 `BackupDestructionJudge`(Worker·`all`, 기본 5분 `bokrilog.backup-destruction.judge.period`, 리허설 override만 5초)가 열린 집합의 요청 ID → identity `operationalCompletions` → operations `judge` 순으로 잇는다. 내보내기 성공은 업로드·대조·`latest` 갱신 뒤 같은 역할의 기록 모드(`BOKRILOG_TOMBSTONE_EXPORT_CONFIRM`)가 V32 `operations_tombstone_export`에 쓰고(35일 지난 기록은 지운다), 그래서 `tombstone-export` 역할이 감사 digest 키를 받는다. 감사 행은 `command=tombstone`·scope `DELETION`·target `deletion-tombstone`·reason `RECORDED|RETENTION_EXTENDED|BACKUP_DESTROYED|BACKUP_OVERDUE(FAILED)|EXPORTED|EXPIRED_PURGED|RESTORE_APPLIED|RESTORE_REFUSED(FAILED)`, details는 수뿐. API 키 금지는 앱(`bokrilog.role=api`면 키가 있어도 bean 없음)과 gate(`TOMBSTONE_KEY_IN_API`) 두 겹이다. 경보 `TombstoneExportStale`은 critical(복원의 `TOMBSTONES_STALE`과 같은 1시간), 기한 임박 warning·초과 critical·미측정 둘 info. 검증: 백엔드 전체 `build` 1,694건 중 2건이 이 PC의 `Bad address` 소켓 결함으로 실패했고 그 두 클래스(`BybitAdapterTest`·`StrongSignalAutoStopTest`) `--rerun`이 통과(단정 실패 0, `-Werror` 경고 없음). promtool 3.5.0(공식 이미지)으로 `check rules`·`test rules` 통과, `test-ci-scripts.sh` 380/380·`test-preflight.sh` 134/134·`test-restore-scripts.sh` 67/67·`test-backup-scripts.sh` 25/25. 리허설 셋(`rehearse-backup.sh`·`rehearse-restore.sh`)은 로컬에서 돌리지 않았다 — PR CI가 판정한다.

- 1차 리뷰 patch 루프(2026-11-06) — 세 리뷰어 지적에서 patch 18건·defer 1건(174 SIGKILL generation 판정 근거)·reject 4건(기한 정의는 frozen Intent, 기존 행 일괄 초과는 production 삭제 전이라 대상 없음, 복원 거부 감사는 복원 보고가 소유, 미지 store는 V24 CHECK가 막음). 반영: 만료 삭제는 DESTROYED 행만·미완료 행은 기한과 무관하게 내보냄(P1), `RECORD_FAILED`도 사본 파기 뒤 종료(P2), 판정 기록 하한 제거(P4), 결과 없는 STARTED는 그 뒤 STARTED가 기록될 때까지 막음(P5 — 대기열이 다음 실행의 STARTED보다 먼저 기록된다), generation 날짜 해석 제거(P6), 감사 append는 커밋 직전(`appendAtCommit`, P10), tombstone 기록 실패 code `TOMBSTONE_FAILED`(P11), 사본 generation 칸 `gen_YYYYMMDD:<oci><offsite>`(P13)·같은 요청 병합(P14)·닫힐 길 없는 열린 집합은 복원 적용 시각으로 닫음(P15), 시험 보강(실패·drain·V31→V32·carry·감사·노출 이름)과 문서 정합(N-1 롤백 주의 포함). 대상 18 클래스·promtool·셸 시험 통과(소켓 결함 클래스는 재실행).

- 2차 리뷰(전체 재리뷰, `review_loop_iteration` 1) patch 루프(2026-11-06) — patch 14건·defer 2건(175 대기열 기록 실패와 순서 가정, 176 `PURGED`가 끝내 없는 generation의 운영 해소 경로)·reject 5건(기한 정의는 frozen Intent, production worker 키는 SPEC Always, 복원 감사의 재실행 중복은 실제 두 번의 적용, `TombstoneExportStale` 1시간은 복원 신선도 기준과 같음, `TOMBSTONE_FAILED`의 화면 범위는 재시도 동작이 같음). 반영: `DESTROYED` 행은 그 상태로 성공한 사본에 실린 뒤에만 지우거나 제외(V32 `destroyed_export`·내보내기 번호 — 영구 `TOMBSTONE_SHRANK` 방지), 영향 generation·완료 판정 경계를 운영 완료 + 1시간(`CLOSURE_SLACK`, 커밋 전에 시작한 dump)으로 넓힘 — frozen Always의 "운영 완료 시각 전"보다 넓은 보수적 상위 집합이며 목적(계정이 든 generation 전부)은 같다, 성공 기록이 있는 generation의 파기 기록은 순서와 무관하게 셈, `DESTROYED` 사본 줄은 모든 generation `PP`만, 판정은 tombstone마다 짧은 트랜잭션(`SKIP LOCKED`·lock 2초·statement 5초), preflight 키 검사 list 꼴, 내보내기 실패 code 병기, 역할 값 시험 고정, 미측정 경보 두 gauge, 시험·문서 정합. 화면 카피 `accountDeletion.ts`의 "삭제 사실 기록 최대 35일"을 "삭제가 끝난 날부터 35일이 지나고 백업 사본 파기가 확인되면 지웁니다"로 고쳤다(서버 정책 hash 입력이 아니다). 대상 시험 클래스·promtool·셸 시험(preflight 137·restore 75·backup 25·ci 380) 통과, 리허설 둘은 PR CI가 판정한다.

## Design Notes

**기한과 회전의 관계.** generation은 dump 날짜 기준 29일에 파기되므로 운영 완료가 요청 뒤 첫 백업(하루) 안에 끝나면 요청 + 30일 안에 모두 사라진다. 실행이 하루 넘게 멈추면 그 사이 generation은 기한을 넘기고, 이것은 사용자 결정(2026-11-06)대로 앞당겨 지우지 않고 `OVERDUE` 경보·감사로 드러낸다.

**보존 연장의 근거.** tombstone이 계정이 든 마지막 백업보다 먼저 사라지면 그 백업 복원이 계정을 되살린다. 마지막 영향 generation은 운영 완료 + 1시간(커밋 여유 `CLOSURE_SLACK`) 전에 dump를 시작했으므로 대략 운영 완료 + 30일 안에 파기되고, 보존 `max(요청, 운영 완료) + 35일`이 그보다 길다. 파기가 확인되지 않은 tombstone은 보존 기한이 지나도 지우지 않고, `DESTROYED` 행도 그 상태로 성공한 사본에 한 번 실린 뒤에만 지운다(2차 리뷰).

**crypto-erasure 제외.** 백업은 한 키(`active.pub`)를 모든 generation이 공유해 계정·generation 단위 키 파기가 불가능하다(사용자 결정 2026-11-06). 저장소 버전 보관·소프트 삭제가 꺼져 있음은 실저장소 출시 게이트가 확인한다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 통과(로컬 소켓 결함이면 대상 클래스 `--rerun`과 PR CI `backend-quality`)
- `bash .github/scripts/check-alert-rules.sh` -- expected: promtool 시험 통과(로컬에 promtool이 없으면 PR CI가 판정)
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 통과

## Suggested Review Order

**백업 파기 판정(설계 진입점)**

- 영향 generation·완료·초과 규칙이 모인 판정 본체 — 여기서 설계 의도를 잡는다
  [`BackupDestructionService.java:150`](../../backend/src/main/java/com/bokrilog/operations/internal/BackupDestructionService.java#L150)

- tombstone마다 짧은 트랜잭션·SKIP LOCKED로 IDENTITY 단계와 잠금이 겹치지 않게
  [`BackupDestructionService.java:74`](../../backend/src/main/java/com/bokrilog/operations/internal/BackupDestructionService.java#L74)

- 커밋 전에 시작한 dump까지 담는 보수적 경계 1시간
  [`BackupDestructionApi.java:46`](../../backend/src/main/java/com/bokrilog/operations/BackupDestructionApi.java#L46)

- V24 기록을 generation별로 접기 — 순서 무관 파기 집계, 마지막 결과 없는 STARTED
  [`BackupDestructionService.java:93`](../../backend/src/main/java/com/bokrilog/operations/internal/BackupDestructionService.java#L93)

- Worker·all 주기 판정과 gauge 셋 — identity 운영 완료 시각을 operations에 잇는다
  [`BackupDestructionJudge.java:100`](../../backend/src/main/java/com/bokrilog/BackupDestructionJudge.java#L100)

**기록 시점(deferred 171 해소)**

- 첫 단계 앞 자기 트랜잭션에서 요청 시각으로 멱등 기록, 실패는 TOMBSTONE_FAILED
  [`AccountErasureRun.java:189`](../../backend/src/main/java/com/bokrilog/AccountErasureRun.java#L189)

- IDENTITY가 집합을 닫고 보존을 max(요청, 운영 완료)+35일로 연장
  [`AccountErasure.java:217`](../../backend/src/main/java/com/bokrilog/AccountErasure.java#L217)

- 감사 append를 커밋 직전으로 미뤄 3.10 잠금 순서를 지킨다
  [`TombstoneAudit.java:75`](../../backend/src/main/java/com/bokrilog/operations/internal/TombstoneAudit.java#L75)

**상태 권위·보존과 만료**

- V32: tombstone 상태 열, 영향 generation 표, 내보내기 기록, 실행 request_id
  [`V32__deletion_backup_destruction.sql:86`](../../backend/src/main/resources/db/migration/V32__deletion_backup_destruction.sql#L86)

- 만료 삭제는 DESTROYED로 성공 사본에 실린 행만 — 영구 SHRANK·조용한 경보 해제 방지
  [`DeletionTombstoneRepository.java:146`](../../backend/src/main/java/com/bokrilog/operations/internal/DeletionTombstoneRepository.java#L146)

- 내보내기 번호를 붙여 "DESTROYED로 한 번 실림"을 판정한다
  [`DeletionTombstoneRepository.java:392`](../../backend/src/main/java/com/bokrilog/operations/internal/DeletionTombstoneRepository.java#L392)

**외부 사본·복원**

- 여덟 칸 줄의 저장소별 파기 확인 표시와 DESTROYED 정합 검사
  [`DeletionTombstoneApi.java:100`](../../backend/src/main/java/com/bokrilog/operations/DeletionTombstoneApi.java#L100)

- 같은 요청은 단조 병합 — 사본이 더 진전된 상태를 잃지 않게
  [`DeletionTombstoneRepository.java:179`](../../backend/src/main/java/com/bokrilog/operations/internal/DeletionTombstoneRepository.java#L179)

- 닫힐 길 없는 열린 집합을 복원 적용 시각으로 닫는다
  [`RestoreApplication.java:203`](../../backend/src/main/java/com/bokrilog/RestoreApplication.java#L203)

- 대조의 "사본에 있어야 하는 행"도 미완료는 기한 무관
  [`RestoreApplication.java:243`](../../backend/src/main/java/com/bokrilog/RestoreApplication.java#L243)

- 기록 실패여도 사본 파기 뒤 실패 code를 함께 보고
  [`tombstone-export.sh:372`](../../deploy/production/tombstone-export.sh#L372)

- 내보내기 성공 기록 모드 — 번호를 검증해 확인한다
  [`TombstoneExportRunner.java:58`](../../backend/src/main/java/com/bokrilog/TombstoneExportRunner.java#L58)

**키 배선·관측**

- API 역할은 키가 있어도 tombstone bean 없음
  [`OperationsConfiguration.java:110`](../../backend/src/main/java/com/bokrilog/operations/internal/OperationsConfiguration.java#L110)

- gate TOMBSTONE_KEY_IN_API — map·list 두 꼴 모두
  [`preflight.sh:462`](../../deploy/production/preflight.sh#L462)

- 운영 Worker에 tombstone 키 배선
  [`compose.yaml:305`](../../deploy/production/compose.yaml#L305)

- 기한 임박·초과·미측정·내보내기 정지 경보
  [`alerts.yml:126`](../../deploy/observability/alerts.yml#L126)

- 삭제 사실 기록 보존 카피를 연장된 보존 규칙에 맞춤
  [`accountDeletion.ts:80`](../../frontend/src/content/accountDeletion.ts#L80)

**시험·리허설**

- 매트릭스 운영 완료·파기 완료·한쪽 실패
  [`BackupDestructionJudgeTest.java:51`](../../backend/src/test/java/com/bokrilog/BackupDestructionJudgeTest.java#L51)

- 매트릭스 첫 claim
  [`AccountErasureRunTest.java:162`](../../backend/src/test/java/com/bokrilog/AccountErasureRunTest.java#L162)

- 매트릭스 실행 전 복원
  [`RestoreApplyTest.java:134`](../../backend/src/test/java/com/bokrilog/RestoreApplyTest.java#L134)

- V31→V32 되채우기
  [`DeletionBackupMigrationTest.java`](../../backend/src/test/java/com/bokrilog/DeletionBackupMigrationTest.java)

- 리허설 10b — MinIO 파기 판정(실저장소 증거 아님)
  [`rehearse-backup.sh:784`](../../deploy/production/rehearse-backup.sh#L784)

- 리허설 — 미완료 행 보존·전이·줄어듦
  [`rehearse-restore.sh:522`](../../deploy/production/rehearse-restore.sh#L522)
