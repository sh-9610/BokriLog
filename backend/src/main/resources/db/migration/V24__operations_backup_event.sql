-- Story 3.9: 일일 암호화 백업의 실행 기록(`BACKUP-1.0`).
--
-- **이 표는 기록이다. 파기 판단의 권위는 저장소 목록이다.** 운영 호스트의 `deploy/production/backup.sh`가 하루 한 번
-- dump → age 암호화 → SHA-256 → 복호화 stream의 `pg_restore --list` → 두 S3 호환 저장소(OCI·OCI 밖) 업로드 → generation
-- 기록 → 30일·수량·용량 파기를 돌고, 그 결과를 일회성 `backup-record` 역할이 이 표에 추가한다. Worker는 이 표에서 마지막 성공
-- 시각과 저장소 사용률 gauge를 낸다(`BackupMetrics`). 기록하지 못한 실행은 성공이 아니다 — DB가 망가지면 성공 행이 생기지 않아
-- `BackupAgeExceeded`가 울린다(fail-closed).
--
-- 행 하나가 사건 하나다.
--   STARTED       -- 그 generation의 실행이 시작됐다.
--   SUCCEEDED     -- 다섯 완료 조건(dump·암호화 종료 코드, 암호문 SHA-256, 복호화 stream catalog, 두 저장소 원격 SHA-256 대조,
--                    `generations/` 이동)이 모두 성공했다. bytes = 암호문 크기, sha256 = 암호문 SHA-256, key_id = 암호화 key.
--   FAILED        -- 단계 하나가 실패했다. code = 단계 code(`DUMP`·`ENCRYPT`·`CATALOG`·`UPLOAD_OFFSITE`·`DEPLOY_IN_PROGRESS` …).
--   PURGED        -- generation 하나(또는 incoming·로컬 임시본 묶음)를 지웠다. store가 비면 로컬 임시본이다.
--   PURGE_FAILED  -- 지워야 할 것을 지우지 못했다(감사 원장에도 SYSTEM 행이 남는다).
--   PURGE_OVERDUE -- 생성 후 30일을 넘긴 generation이 파기 뒤에도 남아 있다(감사 원장에도 SYSTEM 행이 남는다).
--   MEASURED      -- 파기 뒤 저장소 하나의 사용량(bytes·generation 수·quota). code `PURGE_OVERDUE`는 그 저장소에 기한을 넘긴
--                    generation이 남았다는 표지다.
--
-- **분류: 순수 운영 데이터.** 사용자 식별자·이메일·금융값·key 값이 없다 — generation ID(`gen_<UTC YYYYMMDD>`)·단계 code·크기·
-- 암호문 checksum·key ID뿐이다. 그래서 계정 삭제의 deletion port가 다룰 행이 없고, 법정 분리 보존 후보도 없다(G-5 전, 개발·CI
-- 기본값 "없음").
--
-- **추가 전용이다.** 애플리케이션의 SQL은 INSERT·SELECT뿐이고 한 클래스(`BackupEventRepository`)에 있으며 시험이 그 소스를
-- 훑어 강제한다. 시각은 DB의 `statement_timestamp()`다.
--
-- expand만 한다(AD-12). V1~V23은 고치지 않는다.

CREATE TABLE operations_backup_event (
    -- 추가 순서. 기록은 advisory 잠금 하나로 직렬화되므로(`BackupEventRepository`) 한 실행의 행이 이어진다.
    sequence      bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    generation_id text        NOT NULL,
    event         text        NOT NULL,
    store         text,
    code          text,
    bytes         bigint,
    count         integer,
    quota_bytes   bigint,
    sha256        text,
    key_id        text,
    recorded_at   timestamptz NOT NULL DEFAULT statement_timestamp(),

    CONSTRAINT operations_backup_event_generation_check CHECK (generation_id ~ '^gen_[0-9]{8}$'),
    CONSTRAINT operations_backup_event_event_check
        CHECK (event IN ('STARTED', 'SUCCEEDED', 'FAILED', 'PURGED', 'PURGE_FAILED', 'PURGE_OVERDUE', 'MEASURED')),
    CONSTRAINT operations_backup_event_store_check CHECK (store IS NULL OR store IN ('oci', 'offsite')),
    CONSTRAINT operations_backup_event_code_check CHECK (code IS NULL OR code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT operations_backup_event_bytes_check CHECK (bytes IS NULL OR bytes >= 0),
    CONSTRAINT operations_backup_event_count_check CHECK (count IS NULL OR count >= 0),
    CONSTRAINT operations_backup_event_quota_check CHECK (quota_bytes IS NULL OR quota_bytes > 0),
    CONSTRAINT operations_backup_event_sha256_check CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT operations_backup_event_key_id_check CHECK (key_id IS NULL OR key_id ~ '^[a-z0-9][a-z0-9_-]{0,31}$'),
    -- 시작 행은 사실을 담지 않는다.
    CONSTRAINT operations_backup_event_started_check CHECK (event <> 'STARTED' OR (store IS NULL AND code IS NULL
        AND bytes IS NULL AND count IS NULL AND quota_bytes IS NULL AND sha256 IS NULL AND key_id IS NULL)),
    -- 성공은 암호문 하나의 크기·checksum·key를 담는다. 저장소 하나의 성공은 없다(두 저장소 모두여야 성공이다).
    CONSTRAINT operations_backup_event_succeeded_check CHECK (event <> 'SUCCEEDED' OR (store IS NULL AND code IS NULL
        AND bytes > 0 AND sha256 IS NOT NULL AND key_id IS NOT NULL AND count IS NULL AND quota_bytes IS NULL)),
    -- 실패·파기·파기 실패는 사유 code를 가진다.
    CONSTRAINT operations_backup_event_code_required_check
        CHECK (event NOT IN ('FAILED', 'PURGED', 'PURGE_FAILED') OR code IS NOT NULL),
    -- 기한 초과는 어느 저장소인지가 사실이다.
    CONSTRAINT operations_backup_event_overdue_check CHECK (event <> 'PURGE_OVERDUE' OR store IS NOT NULL),
    -- 측정은 저장소 하나의 bytes·generation 수·quota다.
    CONSTRAINT operations_backup_event_measured_check CHECK (event <> 'MEASURED' OR (store IS NOT NULL
        AND bytes IS NOT NULL AND count IS NOT NULL AND quota_bytes IS NOT NULL AND sha256 IS NULL
        AND (code IS NULL OR code = 'PURGE_OVERDUE'))),
    -- 측정 밖의 사건은 quota를 담지 않는다.
    CONSTRAINT operations_backup_event_quota_scope_check CHECK (event = 'MEASURED' OR quota_bytes IS NULL)
);

-- 같은 날 재실행의 no-op 판정과 마지막 성공 시각.
CREATE INDEX operations_backup_event_event_idx ON operations_backup_event (event, generation_id);
-- 저장소별 최신 측정.
CREATE INDEX operations_backup_event_measured_idx ON operations_backup_event (store, sequence) WHERE event = 'MEASURED';
