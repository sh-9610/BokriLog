---
title: 'Story 3.9 — 일일 암호화 백업의 이중 보관과 30일 파기'
type: 'feature'
created: '2026-10-12'
status: 'done'
review_loop_iteration: 0
baseline_commit: '718afdc49291c5032eae795eaa1ef14a9e64cfd7'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-8-blue-green-deploy.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 백업이 없다. 서버나 OCI 계정 장애가 나면 계정·계획 기록의 복구 원본이 없다. 3.7의 경보(`BackupAgeExceeded`·`BackupAgeUnmeasured`)가 기다리는 `bokrilog_backup_last_success_timestamp_seconds`를 아무도 내지 않는다(`epics.md` Story 3.9 AC-1~5. 복원 실행은 Story 3.10, 실제 OCI·외부 저장소 연결은 운영 호스트가 생길 때).

**Approach:**
- **호스트 스크립트.** 운영 서버 systemd timer(하루 1회)가 `deploy/production/backup.sh`를 돈다. 순서는 다음과 같다.
  1. `pg_dump --format=custom`
  2. age 암호화(앱 키와 분리된 backup key)
  3. SHA-256
  4. 복호화 stream의 `pg_restore --list`
  5. rclone으로 S3 호환 저장소 둘(OCI·OCI 밖)에 업로드
  6. generation 기록
  7. 30일·수량·용량 파기
- **결과 기록.** 3.8 `deploy-audit`과 같은 모양의 일회성 역할 `backup-record`가 결과를 새 표에 추가 기록하고, 파기 실패·기한 초과를 감사 원장에 남긴다.
- **메트릭.** Worker가 그 표에서 성공 시각·저장소 사용률 gauge를 낸다.
- **리허설.** CI가 MinIO 두 개로 전 과정을 돈다. 실제 저장소 파기 증거 전까지 **실저장소 미검증**이다.

## Boundaries & Constraints

**Always:**
- **도구.** age·rclone은 운영 호스트에 고정 버전을 SHA-256 표로 받는다(`deploy/production/install-backup-tools.sh`, `check-alert-rules.sh`의 다운로드 관용). `pg_dump`·`pg_restore`는 운영 postgres 컨테이너의 것을 `compose exec -T`(로컬 소켓)로 쓴다. 운영 서버에서 이미지를 빌드하지 않는다.
- **생성·암호화(AC-1).**
  - dump는 권한 700 임시 디렉터리에 쓴다. 종료 코드 0을 확인한 뒤에만 `age -R <active recipient>`로 암호화한다.
  - 평문은 암호화 직후, 그리고 어느 실패·신호에서든 trap으로 지운다.
  - 시작할 때 앞선 실행이 남긴 임시본도 지운다.
  - backup key(age identity)·rclone 설정은 저장소 밖(`/etc/bokrilog/backup/`, 권한 600)에만 둔다. DB·Git·로그·compose 환경·앱 프로세스에는 넣지 않는다.
- **완료 검증(AC-2).** generation이 `SUCCEEDED`가 되는 것은 다섯이 모두 성공한 경우뿐이다.
  - dump·암호화 종료 코드
  - 암호문 SHA-256 기록
  - `age -d … | pg_restore --list`의 성공과 비어 있지 않은 목록
  - 두 저장소 각각 `incoming/`에 올린 뒤 원격 SHA-256 대조
  - `generations/`로의 이동
- **실패·재시도(AC-3).**
  - generation ID는 `gen_<UTC YYYYMMDD>`이다. 같은 날 재실행은 같은 ID로 덮어쓰고 이미 `SUCCEEDED`면 아무것도 하지 않는다.
  - 단계 실패는 `FAILED`(단계 code)만 기록하고 성공 메트릭을 바꾸지 않는다.
  - `incoming/`의 부분 object는 다음 실행이 정리한다.
  - 배포 잠금(`state_dir/.lock`)을 함께 쓴다. 배포 중이면 시작하지 않고 실패로 끝낸다. timer가 다음에 다시 돈다.
- **파기(AC-4).**
  - 매 실행 끝에 두 저장소의 `generations/`·`incoming/`과 로컬 임시본에서 생성 후 29일을 넘은 generation을 지운다(30일 안 제거). 수량 상한(`max_generations`)·총 bytes 상한(`max_bytes`, 저장소별)을 넘으면 오래된 것부터 지운다.
  - 수량·용량 상한으로는 마지막 `SUCCEEDED` 하나를 지우지 않는다. 30일 규칙에는 예외가 없다 — 새 성공이 없어 그것마저 29일을 넘기면 지운다. 그 전에 26시간 경보가 이미 울린다.
  - 30일을 넘긴 generation이 남아 있거나 지우기에 실패하면 감사 SYSTEM 행(`command=backup`, `scope=BACKUP`, reason `PURGE_FAILED`·`PURGE_OVERDUE`)과 표 행을 남긴다.
  - 시각은 실제 시계이고, 리허설만 `BOKRILOG_BACKUP_NOW`로 덮어쓴다(운영에서 거부).
- **키(AC-5).**
  - recipient는 `active.pub` 하나, identity는 `identities/<key_id>.key`이다. generation 기록과 object 이름에 `key_id`가 실린다.
  - `backup.sh keys`는 저장소에 남은 generation이 쓰는 key_id의 identity가 모두 있는지 확인한다. 없으면 실패한다.
  - 보존이 끝나기 전에는 과거 identity를 지우지 말라고 문서로 정한다.
  - 앱 key ring·감사 digest 키와 섞지 않는다.
- **표·역할.**
  - V24 `operations_backup_event`(추가 전용, `operations` 소유, 순수 운영 데이터)를 둔다.
    - 열: generation ID·event(`STARTED`·`SUCCEEDED`·`FAILED`·`PURGED`·`PURGE_FAILED`·`PURGE_OVERDUE`·`MEASURED`)·store(`oci`|`offsite`|null)·code·bytes·count·quota_bytes·sha256·key_id·시각(`statement_timestamp()`)
    - CHECK 제약을 건다. 사용자 식별자는 없다.
  - SQL은 저장소 클래스 한 곳에만 있다(`OperatorAuditTest` 관용의 시험).
  - `backup-record` 프로필은 web none·Flyway 검사 없음이고 `ActorDigest`를 가진다.
- **메트릭.** Worker·`all` 역할만 낸다.
  - `bokrilog.backup.last.success.timestamp`(초)
  - `bokrilog.backup.store.usage.ratio{store}`(최신 `MEASURED`의 bytes/quota)
  - 성공 행이 없으면 gauge를 등록하지 않는다. NaN을 내면 `absent()`·나이 비교가 함께 침묵하기 때문이다.
  - `TelemetryAllowlist`에 행을 더한다.
  - `alerts.yml`에 저장소 사용률 70% warning·85% critical, 파기 기한 초과를 더하고 `alerts.test.yml`이 경계를 고정한다.
- **실행.** `deploy/production/systemd/bokrilog-backup.{service,timer}`(매일, `Persistent=true`)을 둔다. 설치 절차는 `DEVELOPMENT.md`에 적는다.
- **리허설.**
  - `rehearse-backup.sh`(0/1/2)가 운영 compose와 리허설 override에 MinIO 두 개(digest 고정)를 더해 돈다. 시나리오는 다음과 같다.
    - 정상
    - 같은 날 재실행 no-op
    - 단계별 실패(dump·암호화·변조된 checksum·catalog·한쪽 업로드)
    - 부분 object 정리
    - 가상 시계 29/30일 경계 파기
    - 수량·용량 상한
    - 키 회전 뒤 과거 identity 확인
    - 로그·object 이름·메트릭 누출 검사
  - 결과에 "실저장소 미검증"을 출력한다.
  - CI `deploy-rehearsal` job에 단계 하나로 넣고 `test-ci-scripts.sh`를 갱신한다. bash 3.2, `| grep -q` 금지.
- **문서.** `DEVELOPMENT.md` 백업 절, `CLAUDE.md` 계약 한 줄. 새 주석은 한국어다.

**Ask First:**
- 외부 저장소 업체를 정하는 것
- backup key를 앱·DB·compose 환경에 넣는 것
- 경보 의미를 바꾸는 것
- 복원 동작을 더하는 것

**Never:**
- 복원 실행
- 평문 dump를 임시 디렉터리 밖에 두는 것
- 앱 키와 backup 키를 통합하는 것
- 가상 시계 결과로 실제 파기 완료를 선언하는 것
- object 이름·로그·메트릭의 사용자·금융 식별자
- 한쪽 업로드만으로 성공을 기록하는 것

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 정상 | 데이터 있는 DB, 두 저장소 정상 | 두 저장소 `generations/gen_…`에 암호문·checksum, `SUCCEEDED`, gauge 갱신, 평문 0 | — |
| 같은 날 재실행 | 이미 `SUCCEEDED` | no-op, 행·object 불변 | 종료 0 |
| 단계 실패 | dump·암호화·catalog 실패 또는 checksum 변조 | `FAILED <code>`, gauge 불변, 평문 0, `generations/` 불변 | 종료 1 |
| 한쪽 업로드 실패 | offsite 실패 | `FAILED UPLOAD_OFFSITE`, 성공 없음, 다음 실행이 `incoming/` 정리 후 재시도 성공 | 종료 1 |
| 파기 경계 | 가상 시계로 29일·30일 지난 generation | 29일 초과는 지워짐(두 저장소·임시본, 마지막 성공도 예외 없음), `PURGED` 행 | 30일 초과가 남으면 `PURGE_OVERDUE`·감사 |
| 상한 | 수량·bytes 초과 | 오래된 것부터 삭제, `MEASURED` 사용률 | 85% 경보 |
| 키 회전 | 새 active key 뒤 과거 identity 삭제 | `backup.sh keys` 실패(필요 key_id 보고) | 종료 1 |
| 배포 중 | 배포 잠금 보유 | 시작 안 함 | 종료 1, `FAILED DEPLOY_IN_PROGRESS` |
| 누출 | 실행 로그·object 이름·메트릭 | 사용자 ID·이메일·key 값·평문 0 | — |
| 성공 기록 없음 | 표에 `SUCCEEDED` 없음 | gauge 미등록 → `BackupAgeUnmeasured` | — |

</frozen-after-approval>

## Code Map

- **일회성 역할 선례(그대로 복제):** `DeployAuditRunner.java:21-97`(`@Profile`·`ApplicationRunner`·닫힌 enum 파싱), `operations/DeployAuditApi.java`(상수·Pattern·record), `operations/internal/DeployAuditService.java:211-288`(`lockAppends` → `append(NewRow(ACTOR_SYSTEM))`), `OperationsConfiguration.java:133-144`(`AuditWritingRole` — 새 프로필 추가 필요), `ActorDigest.java:87-89`(prefix — `backup:` 추가), `application.yml:361-397`(`deploy-audit` 프로필 — web none·Flyway off), 시험 `DeployAuditTest`·`DeployAuditPendingMigrationTest:34-88`·`OperationsConfigurationTest:58-62`
- **DB gauge 선례:** `operations/internal/SupportPendingMetrics.java:24-75`(servlet + `@ConditionalOnExpression("'${bokrilog.role:all}' != 'api'")`, 가상 스레드 주기 측정, `statement_timeout` 2초), 역할 시험 `SupportPendingMetricsRoleTest`. 차이: 성공 행이 없으면 등록하지 않는다(NaN 금지)
- **allowlist:** `TelemetryAllowlist.java:92-125`(이름 → tag 표, 무tag gauge는 `Map.of()`), `TelemetryAllowlistTest.java:96-103,257-268`(접미사 규칙)
- **경보:** `deploy/observability/alerts.yml:55-74`(backup group), `:137-180`(disk·Grafana 70/85 관용), `alerts.test.yml:49-84,129-152`
- **migration:** 최신 V23. 머리 주석 관용(스토리·분류·expand only)은 V17·V22. 추가 전용은 시험으로 강제(`OperatorAuditTest.java:24-73` 복제)
- **배포 스크립트 관용:** `deploy/production/lib.sh`(`kv_get` L13, `sha256_of` L50, `matches` L64, `random_hex` L112), `deploy.sh:138-151`(원자 상태 쓰기), `:159-165`(`.lock`·operation ID), `:217-260`(`compose` 래퍼·`audit()`의 `run --rm --no-deps -T -e`), `compose.yaml:48-105`(postgres 로컬 소켓, 일회성 역할 모양, `--env-file` 이름별 보간), `preflight.sh:379-385,487-514`(`check_limit`·필수 비밀·denylist)
- **다운로드 고정 관용:** `.github/scripts/check-alert-rules.sh:31,66-158`(버전 상수·OS/arch별 SHA-256 표·`.partial` 후 원자 이동·캐시 재대조)
- **리허설·CI:** `rehearse-deploy.sh:44-152,187-259`(도우미·이미지 빌드·정리 trap·진단 보존), `compose.rehearsal.yaml`, `ci.yml:456-519`(`deploy-rehearsal` job), `test-ci-scripts.sh:1194-1201,1457-1491`(단계 목록·한 줄 `run`·timeout·스크립트 lint 루프 — `backup.sh` 추가)
- **설계 근거:** `PORTFOLIO-SOLUTION-DESIGN.md` L222-230(순서·두 업로드·OCI 20GB 70%), `ARCHITECTURE-SPINE.md` AD-11 L118·AD-13 L130

## Tasks & Acceptance

**Execution:**
- [x] `backend/.../db/migration/V24__operations_backup_event.sql` -- 추가 전용 표·CHECK·분류 주석.
- [x] `operations/BackupRecordApi`·`internal/BackupRecordService`·`internal/BackupEventRepository`·루트 `BackupRecordRunner`·`application.yml`(`backup-record` 프로필)·`OperationsConfiguration`·`ActorDigest` -- 행 추가, 파기 실패·기한 초과 감사.
- [x] `operations/internal/BackupMetrics`·`TelemetryAllowlist` -- Worker·`all`의 두 gauge, 성공 없으면 미등록.
- [x] `deploy/observability/alerts.yml`·`alerts.test.yml` -- 사용률 70/85·기한 초과 규칙과 경계 시험.
- [x] `deploy/production/backup.sh`·`install-backup-tools.sh`·`compose.yaml`(`backup-record` 서비스)·`preflight.sh`(`check_limit`)·`systemd/bokrilog-backup.{service,timer}`·`backup.env.example` -- 절차·키·파기.
- [x] `deploy/production/rehearse-backup.sh`·`compose.rehearsal-backup.yaml`·`ci.yml`·`test-ci-scripts.sh` -- MinIO 리허설과 CI 단계.
- [x] 시험 -- `BackupRecordTest`(행 모양·Flyway 검사 없음·감사 행), `BackupEventRepositoryTest`(SQL 한 곳·수정 없음), `BackupMetricsRoleTest`(api 없음·성공 없을 때 미등록·값), `TelemetryAllowlistTest`, `ModuleBoundaryTest`.
- [x] `DEVELOPMENT.md`·`CLAUDE.md` -- 절차·키 수명·설치·실저장소 미검증.

**Acceptance Criteria:**
- Given 백업 리허설을 돌리면, when 모든 시나리오가 끝나면, then 표의 기대와 같고 종료 0이며 "실저장소 미검증"이 출력된다.
- Given 백엔드 build·`check-alert-rules.sh`·`test-preflight.sh`·`test-ci-scripts.sh`·배포 리허설을 돌리면, then 모두 통과한다.
- Given 저장소를 훑으면, then age identity·rclone 설정·평문 dump가 Git·compose 환경·로그 어디에도 없다.

## Spec Change Log

- 2026-10-12 구현에서 확정한 것(SPEC 본문 변경 없음): (1) **파기 기한 초과 경보의 시계열.** 경보 `BackupPurgeOverdue`가 읽을 시계열이 Boundaries의 gauge 둘에는 없어 셋째 gauge `bokrilog.backup.purge.overdue{store}`(저장소별 최신 `MEASURED`가 code `PURGE_OVERDUE`면 1, 아니면 0)를 더했다 — 같은 규칙(Worker·`all`만, 기록 없으면 미등록, `TelemetryAllowlist` 행). 사용률 두 단계는 `for: 5m`(Grafana 사용량과 같은 관용), 기한 초과는 `for: 0m` critical이고 셋 다 `group: backup`이다. (2) **MinIO 이미지.** 공식 `minio/minio`는 Docker Hub에서 받을 수 없어(2026-10 확인 — repository 없음) 같은 소스의 공개 빌드 `pgsty/minio:RELEASE.2026-08-04T00-00-00Z@sha256:<index>`를 리허설 override에만 고정했다. (3) **도구.** age v1.3.2(GitHub 자산 digest)·rclone v1.75.1(공식 `SHA256SUMS`)이고, 운영 linux-arm64·CI linux-amd64 밖에 개발 리허설용 darwin·windows-amd64 행을 표에 두었다. `backup.sh`는 `install-backup-tools.sh --check`(버전의 유일한 정의)가 통과해야 돈다. (4) **기록.** 실행마다 `backup-record`를 두 번 띄운다 — 시작(`STARTED`)과 끝(결과·파기·측정을 한 트랜잭션, 행은 `;`·`,` 표기의 환경 값 하나). 같은 generation의 두 번째 `SUCCEEDED`는 서비스가 거절한다. 기록 역할의 이미지는 `deploy.state`의 활성 이미지이고 compose 변수는 감사 역할과 같은 `BOKRILOG_AUDIT_IMAGE`다. 코드는 `FAILED`: `DUMP`·`ENCRYPT`·`CHECKSUM`·`CATALOG`·`UPLOAD_<STORE>`·`CHECKSUM_<STORE>`·`MOVE_<STORE>`·`DEPLOY_IN_PROGRESS`·`INTERRUPTED`, `PURGED`: `AGE`·`COUNT`·`BYTES`·`INCOMING_CLEARED`·`INCOMING_AGE`·`LOCAL_TEMP`, `PURGE_FAILED`: `LIST_FAILED`·`DELETE_FAILED`, `PURGE_OVERDUE`: `GENERATIONS`·`INCOMING`. (5) **생성 시각.** 29/30일은 generation ID 날짜의 00:00 UTC부터 센다 — 실제 생성 시각은 그 뒤라 보수적이고, 가상 시계가 저장소 mtime을 옮길 수 없어서다. 경계는 "29일 초과면 지움", "30일 이상 남으면 기한 초과". (6) **종료 코드.** 백업이 성공해도 파기 실패·기한 초과가 있으면 1(systemd에서 실패로 보인다). no-op 판정(표의 `SUCCEEDED`)은 잠금보다 앞이다. (7) **gate.** 운영 비밀 파일에 age identity 모양의 값이 있으면 `BACKUP_KEY_IN_SECRETS`로 거부한다(Ask First "backup key를 compose 환경에 넣는 것"의 집행). `deploy.sh`의 잠금 메시지가 배포·백업을 함께 말한다. (8) **CI.** `deploy-rehearsal` job timeout 25 → 50분, `백업 리허설` 단계 25분(Windows 로컬 실측 16분 30초 — 대부분 실행마다 두 번의 JVM 기동). (9) **시험 풀.** `BackupRecordTest` 컨텍스트가 시험 JVM 캐시에 남아 공유 PostgreSQL 연결 한도를 넘겨 `WorkerSingletonTest`가 "too many clients"로 실패해서, 그 컨텍스트의 Hikari 풀을 2(최소 유휴 0)로 줄였다. 로컬(Windows, Docker 29.7.2) 결과: 백엔드 `build` 1406건 통과, 백업 리허설 114/114(990초), `test-preflight.sh` 123/123, `test-ci-scripts.sh` 208/208, `check-alert-rules.sh`(promtool 3.5.0 컨테이너) 통과, 배포 리허설 77/77(741초).
- 2026-10-12 리뷰 patch 루프(SPEC 본문 변경 없음, 위 줄의 (4)·(6)·(8)을 대체): 긴 단계를 배경 실행 + `wait`로 돌려(상한·1초 감시자·TERM 뒤 KILL) SIGTERM이 바로 정리·`FAILED INTERRUPTED`(90초 상한, 못 하면 대기열)·잠금 해제로 가고 service에 `TimeoutStopSec=180`. 잠금 안에 `owner`(kind·pid·operation·시작 시각, `lib.sh`의 `lock_acquire`를 `deploy.sh`도 쓴다)를 두고 `DEPLOY_IN_PROGRESS`·`BACKUP_IN_PROGRESS`·`LOCK_STALE`(지우지 않음)를 가른다 — 잠금을 못 쥔 실패 행과 기록하지 못한 모든 행은 JVM 없이 `pending/` 대기열에 두고 잠금을 쥔 다음 실행이 먼저 기록한다. DB에 닿지 못해도(PostgreSQL 정지·V24 없음·시작 기록 실패) dump 없이 파기·측정을 돌고(`FAILED DB_UNAVAILABLE`·`RECORD_TABLE_MISSING`·`RECORD_UNAVAILABLE`), 마지막 성공을 모르면 수량·bytes 상한을 건너뛰고 `PURGE_FAILED LIST_FAILED`. 잠금 뒤 같은 날 성공을 다시 본다. 결과 행과 파기·측정 행을 따로(200행 이하 묶음) 기록하고 표의 행 수를 psql로 확인한다(`backup-record`는 `SPRING_MAIN_WEB_APPLICATION_TYPE=none` — 3.9 이전 이미지는 아무것도 쓰지 않는다). catalog는 pg_restore가 끝난 뒤 남은 stream을 끝까지 읽고 age·pg_restore 종료 코드를 따로 본다. 한쪽 이동 실패는 옮긴 쪽을 `incoming/`으로 되돌린다(같은 날 generation 선삭제 실패도 `MOVE_<STORE>`). 두 remote는 다른 rclone remote여야 하고 끝의 `/`·`//`를 거부한다. `backup.sh purge`(파기·측정만)를 더했다. service에 `Restart=on-failure`·`RestartSec=30min`·12시간 6회 상한. `keys`가 `recipients/<id>.pub`과 `age-keygen -y` 짝·권한(디렉터리 700·파일 600·active.pub 공유 쓰기 금지)을 보고, `run`도 active key의 짝을 준비 단계에서 본다. `install-backup-tools.sh`가 꺼낸 바이너리의 SHA-256을 설치 manifest에 남기고 `--check`가 다시 대조한다. 넷째 gauge `bokrilog.backup.store.measured.timestamp{store}`와 경보 `BackupStoreMeasurementStale`(26시간, warning)을 더했고 사용률 warning은 위쪽 경계 없이 `>= 0.70`이다. quota 견본은 10진 20 GB이고 사용률 분자는 백업 object뿐이다(계정 단위 보정은 `deferred-work.md`). 새 시험: `test-backup-scripts.sh`(설치 무결성·운영 모드 거부·준비 거부·`keys`, CI `백업 스크립트 시험` 단계), `BackupEventRepositoryDbTest`(읽기 SQL을 실제 DB에서), 리허설에 한쪽 이동 실패·dump 중 SIGTERM·세 잠금·DB 정지 중 29일 파기·`purge`·`keys`의 짝 불일치와 저장소 정지·큰 dump(리허설 전용 scratch 표 약 9.6MB 원본)·Worker 컨테이너 로그 누출 검사. CI `deploy-rehearsal` job 70분·`백업 리허설` 단계 40분(Windows 로컬 실측 28분 49초). 로컬 결과: 백엔드 `build` 1410건 통과, 백업 리허설 150/150(1729초), 배포 리허설 77/77(750초), `test-preflight.sh` 126/126(Windows)·127/127(ubuntu:24.04 컨테이너, POSIX 권한 경로 포함), `test-backup-scripts.sh` 24/24(Windows)·26/26(ubuntu 컨테이너), `test-ci-scripts.sh` 211/211, `check-alert-rules.sh` 통과.

## Design Notes

- **도구를 호스트에 두는 이유.** 운영 서버는 이미지를 빌드하지 않는다. age·rclone이 든 공식 이미지를 따로 들이면 digest·공급망 검사 대상이 하나 늘어난다. 고정 바이너리 둘은 promtool과 같은 관용으로 SHA-256 표 하나가 지킨다. dump 도구는 같은 PostgreSQL 18.6 컨테이너의 것을 써야 서버와 버전이 어긋나지 않는다.
- **`incoming/` → `generations/` 두 단계인 이유.** 업로드 중 끊긴 object가 완성본처럼 보이지 않게 하고, 정리 대상을 이름공간으로 가른다. 원격 checksum을 맞춘 뒤에만 옮긴다.
- **gauge를 NaN이 아니라 미등록으로 두는 이유.** `time() - max(NaN)`은 NaN이라 `BackupAgeExceeded`가 거짓이고, series가 있으니 `absent()`도 거짓이다. 둘 다 침묵하게 된다(3.8에서 API 슬롯 NaN을 고친 것과 같은 문제).
- **표가 DB에 있는 이유.** 기록 실패도 성공으로 보지 않는다. DB가 망가지면 성공 행이 안 생기므로 경보가 울린다(fail-closed). 파기 판단의 권위는 저장소 목록이고 표는 기록이다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 통과, 경고 0.
- `bash .github/scripts/test-ci-scripts.sh && bash deploy/production/test-preflight.sh` -- expected: 통과.
- `bash .github/scripts/check-alert-rules.sh` (Linux 또는 ubuntu 컨테이너) -- expected: 통과.
- `bash deploy/production/rehearse-deploy.sh && bash deploy/production/rehearse-backup.sh` -- expected: 모두 0.

## Suggested Review Order

**백업 절차의 골격**

- 진입점: dump → 암호화 → checksum → catalog → 두 저장소 incoming→generations → 기록의 본체
  [`backup.sh:800`](../../deploy/production/backup.sh#L800)

- 복호화 stream을 끝까지 읽어 age 인증과 `pg_restore --list`를 따로 판정
  [`backup.sh:775`](../../deploy/production/backup.sh#L775)

- 한쪽 이동이 실패하면 옮긴 쪽을 incoming으로 되돌린다
  [`backup.sh:785`](../../deploy/production/backup.sh#L785)

- 신호를 받으면 단계 프로세스 그룹 종료·임시본 삭제·`FAILED INTERRUPTED`
  [`backup.sh:675`](../../deploy/production/backup.sh#L675)

- 단계별 시간 상한과 백그라운드 `wait`(trap이 바로 돈다)
  [`backup.sh:324`](../../deploy/production/backup.sh#L324)

**파기와 기록**

- 29일·수량·용량 파기 — DB 없이도 저장소 목록으로 돈다, 마지막 성공 미상이면 상한 파기 생략
  [`backup.sh:960`](../../deploy/production/backup.sh#L960)

- 기록 실패·잠금 경합은 JVM 없이 대기열로, 잠금을 쥔 다음 실행이 먼저 비운다
  [`backup.sh:579`](../../deploy/production/backup.sh#L579)
  [`backup.sh:587`](../../deploy/production/backup.sh#L587)

- 배포와 공유하는 잠금 — 주인 기록·경합 사유·죽은 주인은 `LOCK_STALE`
  [`lib.sh:127`](../../deploy/production/lib.sh#L127)

**키와 도구**

- `keys`: identity↔recipient 대조, 권한, 저장소에 남은 key ID 전부
  [`backup.sh:408`](../../deploy/production/backup.sh#L408)
  [`backup.sh:280`](../../deploy/production/backup.sh#L280)

- 고정 SHA-256 다운로드와 설치 바이너리 MANIFEST 재대조
  [`install-backup-tools.sh:8`](../../deploy/production/install-backup-tools.sh#L8)

**앱 쪽 기록·메트릭**

- 추가 전용 사건 표와 CHECK, 사용자 식별자 없음
  [`V24__operations_backup_event.sql:29`](../../backend/src/main/resources/db/migration/V24__operations_backup_event.sql#L29)

- 행 추가·`SUCCEEDED` 중복 거절·파기 실패/기한 초과 감사
  [`BackupRecordService.java:48`](../../backend/src/main/java/com/bokrilog/operations/internal/BackupRecordService.java#L48)
  [`BackupRecordApi.java:53`](../../backend/src/main/java/com/bokrilog/operations/BackupRecordApi.java#L53)

- Flyway 검사 없는 일회성 기록 역할
  [`BackupRecordRunner.java:29`](../../backend/src/main/java/com/bokrilog/BackupRecordRunner.java#L29)

- Worker·all만, 성공 없으면 미등록(NaN 금지)
  [`BackupMetrics.java:12`](../../backend/src/main/java/com/bokrilog/operations/internal/BackupMetrics.java#L12)
  [`BackupEventRepository.java:89`](../../backend/src/main/java/com/bokrilog/operations/internal/BackupEventRepository.java#L89)

**운영 구성·경보**

- 사용률 70/85(warning 상한 없음)·측정 정체·파기 기한 초과
  [`alerts.yml:78`](../../deploy/observability/alerts.yml#L78)
  [`alerts.yml:106`](../../deploy/observability/alerts.yml#L106)

- 실패한 날의 재시도와 상한
  [`bokrilog-backup.service:29`](../../deploy/production/systemd/bokrilog-backup.service#L29)

- 기록 역할 서비스(web none 강제)
  [`compose.yaml:114`](../../deploy/production/compose.yaml#L114)

**시험·CI·문서**

- 리허설 시나리오(정상·재실행·단계 실패·이동 실패·신호·배포 중·DB 없이 파기·경계·상한·키·큰 dump·누출)
  [`rehearse-backup.sh:16`](../../deploy/production/rehearse-backup.sh#L16)

- 운영 모드 거부·도구 변조 fixture
  [`test-backup-scripts.sh:10`](../../deploy/production/test-backup-scripts.sh#L10)

- CI 단계
  [`ci.yml:465`](../../.github/workflows/ci.yml#L465)

- 절차·키 수명·오프라인 identity 사본·실저장소 미검증
  [`DEVELOPMENT.md:559`](../../DEVELOPMENT.md#L559)
