---
title: 'Story 3.10 — 외부 복구 매니페스트·격리 복원·삭제 계정 재활성화 차단'
type: 'feature'
created: '2026-10-13'
status: 'done'
review_loop_iteration: 0
baseline_commit: '3f0e1df2efc07224619f90efdc29c9f141bcde0a'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-9-encrypted-backup.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 백업이 있어도 다시 세울 수 있는지는 증명된 적이 없다. "다시 세우는 법"을 담은 문서도 없다. 백업 뒤에 탈퇴한 계정은 복원하면 되살아난다. 탈퇴 사실을 DB·백업 밖에 남기는 장치(tombstone)도 없다(`epics.md` Story 3.10 AC-1~4·AC-5의 RPO. 새 송신 IP 재바인딩, 훈련 증거 원장, 호스트 전환 runbook은 `deferred-work.md`로 분리).

**Approach:**
- **manifest.** 저장소에는 OCI 밖 복구 manifest의 양식과 검사기만 둔다.
- **tombstone.** 계정 삭제를 한 트랜잭션으로 묶는 루트 orchestrator가 tombstone outbox 행을 함께 쓴다. 호스트 timer가 살아 있는 tombstone 전체를 전용 키로 암호화해 OCI 밖 저장소에 내보낸다.
- **복원.** `restore.sh`가 generation 하나를 같은 호스트의 빈 격리 compose 프로젝트에 복원한다. 일회성 역할이 서비스를 열기 전에 외부 tombstone을 적용하고 세션을 폐기한 뒤 불변식·키를 검증한다. 마지막으로 RPO를 잰다.
- **리허설.** CI 리허설이 전 과정을 고정한다. 실제 별도 환경 복원 전까지 **실제 복원 미검증**이다.

## Boundaries & Constraints

**Always:**
- **manifest(AC-1).** `deploy/production/recovery-manifest.example`(`key=value`)와 검사기 `check-recovery-manifest.sh`(0/1/2)를 둔다.
  - 필수 키:
    - `manifest_version`
    - `image`(`@sha256:` 고정)
    - `terraform`(`manual` 허용)
    - DNS·TLS·OAuth·Grafana·백업 설정 참조
    - 키 버전: backup key ID들, 자격정보 active key version, 감사·tombstone 키 참조
    - `bootstrap_order`
  - 값은 `ref:<위치>`나 `age:<암호문>`, 버전·digest·이름만 허용한다.
  - 평문 비밀 모양(`AGE-SECRET-KEY-`, PEM, 32바이트 이상 base64, tfstate 본문, 알 수 없는 키)이 있으면 거부한다.
  - 실제 manifest는 저장소 밖에 버전을 붙여 둔다.
- **삭제 orchestrator와 outbox(AC-4).**
  - 루트 `AccountErasure.erase(userId, requestId)`는 한 트랜잭션에서 다음을 한다.
    1. `beginDeletion`
    2. tombstone outbox 행 추가
    3. 모듈 erase port들(`PlanEndTest:942-947` 순서 + operations 셋)
    4. `erasePayload`
  - 사용자 경로는 만들지 않는다(Epic 5). 지금 호출자는 검증용 일회성 역할 `deletion-sample`과 복원 적용뿐이다.
  - outbox는 V25 `operations_deletion_tombstone`(operations 소유)이다.
    - 열: `user_digest`(전용 키 `BOKRILOG_TOMBSTONE_DIGEST_KEY`의 HMAC, prefix `tombstone:`)·`request_id`·`requested_at`·`destroy_after`(+35일)
    - 사용자 ID 원문, 금융·자격정보·소셜 식별자는 담지 않는다.
    - 기한이 지난 행은 내보내기 역할이 지운다.
- **내보내기.** 일회성 역할 `tombstone-export`가 살아 있는 행 전체를 마운트된 파일 하나로 쓴다.
  - `deploy/production/tombstone-export.sh`가 그 파일을 tombstone 전용 age recipient로 암호화한다(backup key와 다른 키).
  - 결과는 OCI 밖 저장소 `tombstones/<UTC 시각>.age`에 올리고 `tombstones/latest`를 갱신한다. 35일 지난 object는 지운다.
  - systemd timer는 15분 주기다. 백업과 같은 잠금 관용(소유자 기록)을 따른다.
- **복원(AC-2).** `restore.sh --generation <gen>|latest --failure-at <ISO> [--project bokrilog-restore-<x>]`의 순서는 다음과 같다.
  1. offsite `generations/`에서 선택한 generation의 checksum을 대조한다.
  2. 이름의 key ID로 identity를 골라 복호화한다.
  3. 새 프로젝트의 빈 PostgreSQL에 `pg_restore`한다.
  4. migrate 역할을 돈다.
  5. 최신 tombstone을 받아 복호화한다.
  6. `restore-apply` 역할을 돈다.
  7. 그다음에만 api·worker를 기동한다.
  - 격리 규칙:
    - nginx는 띄우지 않고 공개 포트도 없다.
    - OIDC·Bybit 값은 비운다(fail-closed로 외부 호출 0).
    - 운영 프로젝트·volume과 이름이 겹치면 거부한다.
  - 평문 dump·tombstone은 권한 700 임시 디렉터리에 두고 trap으로 지운다(3.9 관용).
- **적용과 검증(AC-3·AC-4).** `restore-apply`(web none, Flyway validate 켬)의 동작은 다음과 같다.
  - 복원된 모든 `user_id`를 같은 키로 HMAC해 tombstone과 맞춘다. 맞은 계정은 `AccountErasure`로 다시 지워 `DELETED`로 만든다(이미 `DELETED`면 그대로).
  - `spring_session` 전체를 지운다.
  - 불변식을 검사한다.
    - 계획 ACTIVE 유일
    - 공식 포인터 → 계산 → portfolio snapshot 귀속(`PlanPublicationService.bundle`·`belongs` 재사용 또는 같은 술어)
    - portfolio 현재 포인터
    - 삭제 계정의 잔여 행 0
  - source 새 port로 자격정보 표본을 복호화할 수 있는지 센다(평문을 반환하지 않고 외부 호출도 없다).
  - 결과는 수만 담은 보고 한 줄이다. 실패하면 종료 코드가 0이 아니고, `restore.sh`는 api·worker를 기동하지 않는다.
  - 복원 뒤 늦게 뜬 Worker·새 세션은 기존 접근 판정(`DELETED` → DENIED)이 막는다.
- **RPO(AC-5 일부).** RPO = `--failure-at` − generation 생성 시각(store object 시각, 없으면 generation 날짜 00:00 UTC)이다. 24시간 이하면 통과, 넘으면 `RPO_EXCEEDED`로 보고하고 종료 1이다.
- **리허설.** `rehearse-restore.sh`(0/1/2)를 새 CI job `restore-rehearsal`이 돈다(`deploy-rehearsal`과 병렬, `aggregate-results.sh`·`test-ci-scripts.sh` 갱신). 시나리오는 다음과 같다.
  - 백업 → 표본 삭제 → 내보내기 → 백업 이전 generation 복원 → 적용 → 표본 `DELETED`·세션 0, 유지 계정 그대로
  - tombstone 없이 복원하면 적용 전 단계에서 멈춤(서비스 미기동)
  - 키 ID가 다른 generation
  - checksum 변조 거부
  - RPO 경계 24h/24h+1s
  - 운영 프로젝트 이름 거부
  - 누출 검사(평문·key·사용자 ID)
  - 결과에 "실제 복원 미검증"을 출력한다.
  - 불변식 검사는 백엔드 Testcontainers 시험이 실제 계획·발행 데이터로 고정한다(`PlanSyncTestBase` 계열).
- **문서.** `DEVELOPMENT.md` `## 복원` 절에 다음을 적는다. manifest 작성, tombstone 키, 복원 절차·순서, RPO, "실제 복원 미검증"이다. `CLAUDE.md` 계약 한 줄도 더한다. 새 주석은 한국어다.

**Ask First:**
- 사용자 삭제 경로(HTTP·화면)를 만드는 것
- tombstone 키를 감사 digest 키와 합치는 것
- 운영 프로젝트·volume 위에 복원하는 것
- 격리 복원에서 실제 provider를 호출하는 것

**Never:**
- 운영 데이터에 시험 쓰기
- 평문 비밀이 든 manifest
- 서비스를 연 뒤 tombstone을 적용하는 것
- 백업 안의 outbox를 tombstone의 권위로 쓰는 것
- 자동시험·리허설을 실제 복원 성공으로 표시하는 것
- tombstone에 원문 식별자를 넣는 것

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 정상 복원 | 백업 뒤 표본 삭제·내보내기, 그 이전 generation | 빈 격리 프로젝트에 복원, 표본 `DELETED`·잔여 0, 세션 0, 유지 계정 그대로, 불변식 통과, api·worker 기동 | — |
| tombstone 없음 | 외부 `tombstones/latest` 없음 또는 복호화 실패 | 적용 전 중단, api·worker 미기동 | 종료 1 `TOMBSTONES_UNAVAILABLE` |
| 변조 | checksum 불일치 | 복원 안 함 | 종료 1 `CHECKSUM_MISMATCH` |
| 키 | 과거 key ID의 generation | 그 identity로 복호화 | identity 없음 → 종료 1 |
| 불변식 위반 | 포인터 귀속 어긋남 | 보고, 서비스 미기동 | 종료 1 |
| RPO | 생성 + 24h / + 24h 1초 | 통과 / `RPO_EXCEEDED` | 종료 0 / 1 |
| 운영 이름 | `--project bokrilog-production` | 거부 | 종료 2 |
| manifest | 평문 키·tag 이미지·필수 키 누락 | 거부 | 종료 1 |
| 내보내기 | 기한 지난 outbox 행 | 행 삭제, 파일에서 빠짐 | — |
| 늦은 접근 | 적용 뒤 표본으로 세션·발행 시도 | DENIED | — |

</frozen-after-approval>

## Code Map

- **삭제 port(순서):** `src/test/java/com/bokrilog/PlanEndTest.java:942-947`(begin → portfolio → sync → plan → source → payload), `identity/AccountDeletionApi.java:16-46`·`identity/internal/AccountDeletionService.java:28-84`(REQUIRED 트랜잭션, 생명주기 행 잠금, ACTIVE에 `erasePayload`는 NOT_APPLICABLE), `portfolio/PortfolioMembershipApi.java:144`, `sync/SyncJobApi.java:898`, `plan/PlanConditionApi.java:58`, `source/SourceConnectionApi.java:304`, `operations/OperationsDeletionApi.java:28-46`(`eraseRestrictionTargets`는 `ActorDigest` 필요). FK가 RESTRICT라 `erasePayload`가 마지막이다
- **접근 판정:** `identity/internal/AccountAccessService.java:28-54`(DELETED → DENIED, `lockWriteAccess`), `IdentityAccountRepository.java:161`(로그인은 ACTIVE만), `SyncJobService.ineligibility:83`. `snapshotConnection`은 생명주기를 보지 않는다 — source erase가 먼저 돌아야 한다
- **세션:** `V2__spring_session.sql`(속성 cascade), `IdentityAccountRepository.deleteSessionsOf:261`. web none 역할에는 세션 저장소 빈이 없다 → SQL은 identity 저장소 한 곳에 둔다
- **키·digest:** `operations/internal/ActorDigest.java:52-98`(prefix 관용 — tombstone은 **별도 키**라 새 클래스), `OperationsConfiguration.java:26-47`(`AuditWritingRole` — `deletion-sample`·`restore-apply`·`tombstone-export` 추가), `source/internal/CredentialCipher.java:133,180`(`open` → Optional, 과거 키 포함)·`SourceConnectionRepository.java:60-80`(행 읽기 재사용)
- **불변식:** `sync/internal/PlanPublicationService.java:716-745,1491`(`bundle`·`belongs`), V10:48·279(활성 유일·공식 포인터), `portfolio/internal/PortfolioMembershipService.java:75`, 시험 관용 `FirstSnapshotTest.java:4648,4666`
- **일회성 역할:** `BackupRecordRunner.java:27-62`, `application.yml:382-430`(프로필 블록), `BackupRecordTest`. 모듈은 루트 타입을 참조할 수 없다 — orchestrator·runner는 루트(`ModuleBoundaryTest:64,81`)
- **배포 스크립트:** `deploy/production/backup.sh`(이름 `gen_<date>.<key_id>.dump.age`+`.sha256` L763·840, `gen_table` L996, `epoch_of_generation` L391, `key_problem` L280, `guarded` L324, `rclone_run`·`list_files` L350-366, `catalog_stream` L775 — `pg_restore --dbname`로 같은 모양, 신호 L675, 리허설 gating L128-152), `lib.sh`(`lock_acquire` L127 — `kind`가 backup이 아니면 모두 DEPLOY로 읽힌다: restore·tombstone 종류 추가), `install-backup-tools.sh --check`. backup.sh는 최상위 exit 때문에 source할 수 없다 → 공통 함수는 `lib.sh` 또는 새 `backup-lib.sh`로 뽑는다
- **compose 격리:** `compose.yaml:42,235,241,255-259`(`--project-name`이 volume·network를 가른다, nginx만 포트), OIDC 빈 값 → 등록 없음(`AuthSecurityConfig.java:564-568`), Bybit 빈 base URL → 연결 기능 닫힘(`application.yml:185-187`)
- **리허설·fixture:** `rehearse-backup.sh:48-267,316-339`(MinIO·키·rclone 생성·표지 사용자 SQL·가상 시계), `compose.rehearsal-backup.yaml`, `test-backup-scripts.sh:32-290`(fixture 관용)
- **CI:** `ci.yml:456-541`, `test-ci-scripts.sh:1194-1214,1459-1502`, `aggregate-results.sh:26,78,131`(새 job은 `*_RESULT`·`check_job`·`add_row`·`needs` 추가)
- **설계:** `PORTFOLIO-SOLUTION-DESIGN.md` L225(RPO 24h)·L230(manifest)·L232(월별 격리 재구축)·L260(tombstone), `EPIC-IMPLEMENTATION-READINESS.md:166`(tombstone 필드·35일), AD-11·13·19

## Tasks & Acceptance

**Execution:**
- [x] `V25__operations_deletion_tombstone.sql` -- outbox 표·CHECK·분류 주석.
- [x] `operations/DeletionTombstoneApi`·`internal/DeletionTombstoneService`·`internal/TombstoneDigest`·저장소 -- 추가·만료 삭제·내보내기 읽기·digest 맞춤.
- [x] 루트 `AccountErasure` -- 한 트랜잭션 orchestrator(삭제 port 순서 + outbox).
- [x] `source` 새 port(자격정보 복호화 가능 수) · `identity` 전체 세션 폐기 port.
- [x] 루트 `RestoreApplyRunner`·`RestoreInvariants`·`DeletionSampleRunner`·`TombstoneExportRunner` + `application.yml` 프로필 셋 + `OperationsConfiguration` -- 적용·검증·표본·내보내기.
- [x] `deploy/production/restore.sh`·`tombstone-export.sh`·`check-recovery-manifest.sh`·`recovery-manifest.example`·공통 함수 추출·`lib.sh` 잠금 종류·`compose.yaml`(역할 셋)·`systemd/bokrilog-tombstone-export.{service,timer}` -- 절차.
- [x] `deploy/production/rehearse-restore.sh`·`test-restore-scripts.sh`(manifest·인자·이름 거부 fixture)·`ci.yml`(`restore-rehearsal` job)·`aggregate-results.sh`·`test-ci-scripts.sh`.
- [x] 시험 -- `AccountErasureTest`(한 트랜잭션, 실패 시 전부 롤백, outbox 행 모양), `DeletionTombstoneTest`(SQL 한 곳·만료 삭제·digest 키 분리), `RestoreApplyTest`(Testcontainers: 실제 계획·발행 데이터 위 적용 → 표본 DELETED·잔여 0·세션 0·불변식 통과, 포인터 위반 주입 → 실패), `ModuleBoundaryTest`.
- [x] `DEVELOPMENT.md`·`CLAUDE.md`.

**Acceptance Criteria:**
- Given 복원 리허설을 돌리면, when 모든 시나리오가 끝나면, then 표의 기대와 같고 종료 0이며 "실제 복원 미검증"이 출력된다.
- Given 백엔드 build·`test-restore-scripts.sh`·`test-backup-scripts.sh`·`test-preflight.sh`·`test-ci-scripts.sh`·`check-alert-rules.sh`·배포·백업 리허설을 돌리면, then 모두 통과한다.
- Given 저장소를 훑으면, then tombstone·backup identity·평문 dump·평문 비밀이 든 manifest가 Git·compose 환경·로그에 없다.

## Spec Change Log

- 2026-10-13 구현에서 확정한 것(SPEC 본문 변경 없음): (1) **outbox.** `request_id` PK + `UNIQUE(user_digest)`, 기한은 시간대와 무관한 `+840 hours`(CHECK), 요청 시각은 `date_trunc('milliseconds', statement_timestamp())`. 같은 계정의 재시도는 기존 행을 그대로 두고, 같은 요청 ID가 다른 계정에 쓰였으면 삭제 트랜잭션 전체를 되돌린다(tombstone 없는 탈퇴 금지). `record`·`carry`는 호출자 트랜잭션 안에서만 쓴다. 내보내기 파일은 머리 `# bokrilog deletion tombstones DELETION-TOMBSTONE-1.0` + `digest request_id requested_at destroy_after` 줄이다(`DeletionTombstoneApi.render/read`). (2) **키.** digest 키 속성은 `bokrilog.tombstone.digest-key`(`BOKRILOG_TOMBSTONE_DIGEST_KEY`)이고 `TombstoneDigest` 빈은 `deletion-sample`·`restore-apply` 프로필에서 필수, 그 밖에는 값이 있을 때만 생긴다. 감사 digest 키와 같으면 빈 생성이 거부된다. `AuditWritingRole`에는 `deletion-sample`·`restore-apply`만 더했다 — `tombstone-export`는 감사 행도 digest도 쓰지 않아 감사 키를 받지 않는다(Code Map의 "셋 추가"에서 벗어남, 비밀이 필요 없는 프로세스로 퍼지지 않게). tombstone age key는 `/etc/bokrilog/tombstone/{recipient.pub,identity.key}`이고 backup recipient와 같으면 내보내기가 거부한다. (3) **잔여 행.** 모듈마다 `remainingRowsOf(UUID)`를 공개 API에 더했다 — 자기 표 접두사(`portfolio*`·`sync_*`·`plan_*`·`source_*`·`operations_*`·`support_*`)의 사용자 열을 `information_schema`로 찾아 `query_to_xml` 한 문장으로 센다(identity는 payload·매핑·세션을 이름으로, 루트는 `action_grant`). `RestoreApplyTest`가 사용자 열이 있는 모든 표가 어느 접두사에 드는지 고정한다. 불변식 port는 `PlanConditionApi.activePlanViolations`·`PortfolioMembershipApi.currentPointerViolations`(포인터 = 그 portfolio의 최대 순번 snapshot)·`SyncJobApi.officialBundleViolations`(`PlanPublicationService.belongs` + portfolio 포인터 대조, 읽기 전용 REPEATABLE READ)이고, identity는 `accounts`·`deletedAccounts`·`revokeAllSessions`·`remainingRowsOf`, source는 `probeCredentials(limit)`(표본 20, 수만)이다. `ModuleBoundaryTest`가 삭제 전환·payload 삭제·tombstone 기록의 호출자를 `AccountErasure` 하나로, `carry`를 `AccountErasure`·`RestoreApplication`으로, `revokeAllSessions`를 `RestoreApplication`으로 고정한다. (4) **역할.** 적용·내보내기 로직은 프로필 없는 `RestoreApplication`·`DeletionTombstoneApi.export`에 있고 runner는 파일 입출력만 한다(Testcontainers 시험이 같은 로직을 부른다). `deletion-sample`은 `BOKRILOG_ENV=production`이면 기동을 거부한다. 세 역할 모두 web none·Flyway 검증이다. 파일은 compose의 bind mount(`BOKRILOG_ROLE_IO_DIR` → `/run/bokrilog/io`, `create_host_path: false`, 적용은 읽기 전용)로 주고 받으며 스크립트가 `run --user $(id -u):$(id -g)`로 700 디렉터리에 닿는다. (5) **restore.sh.** `--rpo-only`(목록만 보고 RPO만 판정)를 더했다 — 리허설의 24h/24h+1s 경계가 전체 복원 두 번 없이 같은 함수를 쓴다. 장애 시각은 시간대가 붙은 ISO-8601만 받고(`epoch_of_iso`), 생성 시각보다 앞서면 종료 2다. 생성 시각은 `rclone lsjson`의 ModTime이다. 기본 프로젝트는 `bokrilog-restore-<UTC 시각>`이고 접두사 `bokrilog-restore-` 밖·`BOKRILOG_PROJECT`와 같음·컨테이너/volume/망이 있는 프로젝트는 종료 2다. 격리는 셸 환경의 빈 값(공개 base URL·운영자·Bybit·세 제공자)이 env 파일을 덮는 방식이다. 복원·내보내기도 `state_dir/.lock`을 쥐고(kind `restore`·`tombstone`, `lib.sh`가 `RESTORE_IN_PROGRESS`·`TOMBSTONE_IN_PROGRESS`로 가르고 `deploy.sh`·`backup.sh`가 그 사유를 말한다), 공용 함수(`guarded`·권한·`key_problem`·`rclone_run`·`list_files`·시각)는 `backup-lib.sh`로 뽑아 `backup.sh`도 읽는다. `preflight.sh`는 새 역할 셋의 `mem_limit` ≤ Worker를 본다. (6) **manifest 검사기.** 참조는 소문자 경로(`ref:[a-z0-9][a-z0-9._/:@-]*`)만 받고, 긴 base64 판정은 `age:` 본문·이미지 digest를 걷어 낸 뒤 대소문자·숫자가 섞인 43자 이상 연속으로 한다(경로·digest 오탐 방지). (7) **CI.** `restore-rehearsal`은 `needs: [changes, backend-quality]`(jar만 쓴다), job 40분·리허설 단계 30분·스크립트 시험 3분. 로컬 결과(Windows, Docker 29.7.2): 백엔드 `build` 1428건 통과(17분 7초, 경고 0), 복원 리허설 49/49(4분 41초), 백업 리허설 150/150(20분 10초), 배포 리허설 77/77(8분 25초), `test-restore-scripts.sh` 48/48·`test-backup-scripts.sh` 24/24·`test-preflight.sh` 126/126(Windows)과 48/48·26/26·127/127(ubuntu:24.04 컨테이너), `test-ci-scripts.sh` 239/239, `check-alert-rules.sh`(ubuntu 컨테이너 + promtool 3.5.0) 통과.
- 2026-10-13 리뷰 patch 루프(SPEC 본문 변경 없음, 위 줄의 (1)·(2)·(5)를 일부 대체): **tombstone 정합** — 사본 머리가 `… key=<digest 키 ID(HMAC 앞 8바이트)> exported=<시각>`이 되고(내보내기 역할도 digest 키를 받는다), 적용은 아무것도 쓰기 전에 대조한다: 키 ID 불일치 → `TOMBSTONE_KEY_ID_MISMATCH`, 복원된 outbox의 `DELETION` 행 가운데 사본보다 먼저 요청됐고 실릴 만큼 기한이 남았는데 사본에 없는 것·지금 키로 어느 계정과도 맞지 않는 것 → `TOMBSTONE_KEY_MISMATCH`(outbox에 `origin` `DELETION|RESTORE` 열을 V25에 더했다 — 옮긴 행은 계정이 없는 것이 정상이라 대조에서 뺀다). 키 회전은 지원하지 않는다(내보내기가 `TOMBSTONE_KEY_CHANGED`로 멈춘다, 절차는 defer). `carry`는 같은 요청 ID가 다른 digest면 실패한다. 감사·tombstone 키는 앱(MIME 디코더)과 gate(패딩 정규화 뒤 `base64 -d`)가 바이트로 비교하고, gate가 tombstone 키를 필수 이름으로 본다(`TOMBSTONE_KEY_REUSED`). **외부 수명** — 사본 object는 24시간(`EXPORT_MARGIN`)만 남고 내보내기는 기한이 지금 + 24시간보다 뒤인 행만 실어 외부 수명 < 35일, 요청 뒤 34일까지 실리므로 30일 안의 generation 복원은 언제나 그 tombstone을 본다. 내보내기는 앞선 사본을 풀어 기한이 25시간 넘게 남은 digest가 새 파일에 모두 있어야 latest를 바꾼다(`TOMBSTONE_SHRANK`·`PREVIOUS_UNREADABLE`, 그래서 identity도 읽는다). **불변식** — `DELETION_IN_PROGRESS` 계정(tombstone이 끝내지 않은)은 위반이고, 자격정보 표본은 key version마다의 최신 행 + 최근 20행이며 active key가 없으면 실패다(`CredentialProbe.passed`). 보고에 `codes=`가 붙는다. **복원 절차** — `restore-apply`는 격리 표지 `BOKRILOG_RESTORE_ISOLATED_PROJECT`(`bokrilog-restore-*`)가 없으면 거부하고, `deletion-sample`은 리허설 표지 `BOKRILOG_DELETION_SAMPLE_REHEARSAL=true`도 요구하며 거부 사유를 code 한 줄로 남긴다(encoder가 예외 메시지를 싣지 않으므로). `restore.sh`에 `--image`(digest만, 리허설은 로컬 image ID도)·`--tombstone-max-lag`(기본 3600초, `TOMBSTONES_STALE`)·`--allow-stale-tombstones`(경고·마지막 줄에 표시)·`--keep-on-failure`를 더했고, 30일 넘은 generation은 `GENERATION_TOO_OLD`, 3단계 뒤의 실패는 격리 프로젝트를 volume까지 지우며, `ISOLATION_BROKEN`은 API·Worker를 먼저 멈춘다. key 검사는 identity 하나면 되고 recipient가 없으면 경고다. 상태 디렉터리를 만든 뒤 잠금을 쥔다. `latest`의 존재·신선도는 잠금·내려받기보다 앞에서 본다(없으면 아무것도 띄우지 않는다). 격리 API·Worker의 메트릭 env는 새 허용값 `restore`다(`TelemetryAllowlist.ENVIRONMENTS`). **RPO 기준** — `backup.sh`가 checksum 파일 둘째 줄에 dump 시작 시각(`dump_started_at=<epoch>`, 실제 시계)을 싣고 원격 대조도 파일 전체로 하며, 복원은 그 값을 쓰고 없으면 generation 날짜 00:00 UTC다(object ModTime은 쓰지 않는다). **잠금** — 백업은 tombstone 내보내기가 쥔 잠금을 300초까지 기다리고(리허설만 `BOKRILOG_BACKUP_LOCK_WAIT`, 운영에서 거부), 내보내기는 남이 쥐면 종료 0으로 건너뛰며(`LOCK_STALE`은 1), `deploy.sh`는 `bokrilog-restore-*` 스택이 돌면 `RESTORE_STACK_RUNNING`으로 거부한다. `epoch_of_iso`는 달의 길이·윤년·±14:00·분 < 60을 본다. manifest 검사기는 16진 비밀(`HEX_SECRET`)·base64url·주석의 `age:`·가짜 `age:` 값·이름 칸·참조 경로의 토큰(`LONG_TOKEN`)을 잡고, 걷어 내는 것은 비밀 키 값 전체가 진짜 age 암호문인 경우와 `image=` 값 전체가 digest인 경우뿐이다. 시험: `RestoreApplyTest`(키 ID·outbox 불일치·끝나지 않은 삭제·재실행·key version 표본·모듈별 잔여 세기·(표, 열) 단위 범위)·`RestoreRunnersTest`(격리 표지·실패 보고·리허설 표지)·`AccountErasureTest`(24시간 여유·carry 충돌)·`DeletionTombstoneTest`(바이트 비교·키 ID·새 머리)·`ModuleBoundaryTest`(`erase`는 `DeletionSampleRunner`, `reapply`는 `RestoreApplication`만), 복원 리허설 93건(표본 자격정보를 node로 앱과 같은 AES-256-GCM 형식으로 봉한다), 백업 리허설 §6에 복원·내보내기 잠금, test-preflight에 tombstone 키 셋·새 역할 셋의 mem_limit. CI `restore-rehearsal`은 job 40분·단계 30분 그대로. 로컬 결과(Windows, Docker 29.7.2): 백엔드 `build` 1441건 통과(18분 17초), 복원 리허설 93/93(7분 20초), 백업 리허설 155/155(20분 14초), 배포 리허설 77/77(8분 11초), `test-restore-scripts.sh` 67/67·`test-backup-scripts.sh` 25/25·`test-preflight.sh` 133/133(Windows)과 67/67·27/27·134/134(ubuntu:24.04 컨테이너), `test-ci-scripts.sh` 239/239, `check-alert-rules.sh`(ubuntu 컨테이너 + promtool 3.5.0) 통과.

## Design Notes

- **outbox + 전체 스냅샷 내보내기인 이유.** 탈퇴와 기록이 한 트랜잭션이라 기록 없는 탈퇴가 없다. 내보내기는 매번 살아 있는 전체를 쓰므로 "보냈다" 표시나 두 번째 JVM 기동이 필요 없고, 재시도해도 결과가 같다. 남는 틈은 내보내기 주기(15분)다.
- **digest로 맞추는 이유.** tombstone은 원문 ID를 담지 않는다. 복원된 모든 `user_id`를 같은 키로 HMAC해 비교한다. 사용자 수가 작은 MVP에서 비용이 낮고, tombstone이 새어도 계정을 되짚을 수 없다.
- **적용이 서비스 기동보다 앞이어야 하는 이유.** api가 먼저 뜨면 백업 시점의 세션으로 탈퇴자가 들어오거나 Worker가 탈퇴자의 동기화를 집어 갈 수 있다. 적용·세션 폐기·검증이 모두 끝난 DB에서만 앱을 띄운다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 통과, 경고 0.
- `bash deploy/production/test-restore-scripts.sh && bash deploy/production/test-backup-scripts.sh && bash deploy/production/test-preflight.sh && bash .github/scripts/test-ci-scripts.sh` -- expected: 통과.
- `bash .github/scripts/check-alert-rules.sh` (Linux 또는 promtool 컨테이너) -- expected: 통과.
- `bash deploy/production/rehearse-restore.sh` 그리고 변경된 스크립트가 걸리면 `rehearse-backup.sh`·`rehearse-deploy.sh` -- expected: 모두 0.

## Suggested Review Order

**복원 적용(서비스를 열기 전)**

- 진입점: 외부 tombstone 대조 → 다시 지우기 → 세션 폐기 → 불변식, 실패하면 아무것도 쓰지 않는다
  [`RestoreApplication.java:163`](../../backend/src/main/java/com/bokrilog/RestoreApplication.java#L163)
  [`RestoreApplication.java:27`](../../backend/src/main/java/com/bokrilog/RestoreApplication.java#L27)

- 불변식: 활성 계획 유일·공식 포인터 귀속·portfolio 포인터·잔여 행·끝나지 않은 삭제
  [`RestoreInvariants.java:74`](../../backend/src/main/java/com/bokrilog/RestoreInvariants.java#L74)

- 격리 표지 없으면 거부, 실패 보고는 0이 아닌 종료
  [`RestoreApplyRunner.java:25`](../../backend/src/main/java/com/bokrilog/RestoreApplyRunner.java#L25)

**삭제와 tombstone**

- 한 트랜잭션 orchestrator — 삭제 전환·outbox·모듈 erase·payload
  [`AccountErasure.java:78`](../../backend/src/main/java/com/bokrilog/AccountErasure.java#L78)
  [`AccountErasure.java:86`](../../backend/src/main/java/com/bokrilog/AccountErasure.java#L86)

- outbox 표(digest·요청 ID·기한·origin), 원문 식별자 없음
  [`V25__operations_deletion_tombstone.sql:25`](../../backend/src/main/resources/db/migration/V25__operations_deletion_tombstone.sql#L25)

- 기록·옮기기의 충돌 검사와 전용 digest 키(감사 키와 바이트 비교로 분리)
  [`DeletionTombstoneService.java:39`](../../backend/src/main/java/com/bokrilog/operations/internal/DeletionTombstoneService.java#L39)
  [`TombstoneDigest.java:23`](../../backend/src/main/java/com/bokrilog/operations/internal/TombstoneDigest.java#L23)

- 파일 형식·key ID·외부 수명 35일 미만 계산
  [`DeletionTombstoneApi.java:25`](../../backend/src/main/java/com/bokrilog/operations/DeletionTombstoneApi.java#L25)

- 내보내기: 줄어든 사본 거부, 24시간 이력
  [`tombstone-export.sh:269`](../../deploy/production/tombstone-export.sh#L269)

**복원 절차**

- checksum·key ID·generation 나이·tombstone 신선도·RPO(dump 시작 시각 기준)
  [`restore.sh:443`](../../deploy/production/restore.sh#L443)
  [`restore.sh:307`](../../deploy/production/restore.sh#L307)
  [`restore.sh:323`](../../deploy/production/restore.sh#L323)
  [`restore.sh:296`](../../deploy/production/restore.sh#L296)

- 적용 실패면 api·worker 미기동, 실패 뒤 격리 volume 정리
  [`restore.sh:517`](../../deploy/production/restore.sh#L517)
  [`restore.sh:378`](../../deploy/production/restore.sh#L378)

- 복원 스택이 남아 있으면 배포 거부
  [`deploy.sh:178`](../../deploy/production/deploy.sh#L178)

- 자격정보 표본은 key version마다
  [`SourceConnectionRepository.java:278`](../../backend/src/main/java/com/bokrilog/source/internal/SourceConnectionRepository.java#L278)

**manifest**

- 필수 키·참조/age만·평문 비밀(hex·base64url·PEM·age identity·tfstate) 거부
  [`check-recovery-manifest.sh:118`](../../deploy/production/check-recovery-manifest.sh#L118)

**시험·CI·문서**

- 리허설 시나리오(정상·tombstone 없음/오래됨/줄어듦·변조·키·RPO·적용 실패·latest·잠금·누출)
  [`rehearse-restore.sh:18`](../../deploy/production/rehearse-restore.sh#L18)

- 실제 계획·발행 데이터 위 적용, 모듈별 잔여 행, (표,열) 범위
  [`RestoreApplyTest.java:37`](../../backend/src/test/java/com/bokrilog/RestoreApplyTest.java#L37)

- manifest·인자·이름 거부 fixture
  [`test-restore-scripts.sh:7`](../../deploy/production/test-restore-scripts.sh#L7)

- `restore-rehearsal` job
  [`ci.yml:544`](../../.github/workflows/ci.yml#L544)

- 복원 절차·tombstone 키·재실행·실제 복원 미검증
  [`DEVELOPMENT.md:641`](../../DEVELOPMENT.md#L641)
