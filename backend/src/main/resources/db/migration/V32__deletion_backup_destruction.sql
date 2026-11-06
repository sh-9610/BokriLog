-- Story 5.3: 외부 tombstone으로 복원을 막고 30일 안의 백업 파기를 추적한다(`DELETION-TOMBSTONE-1.0`).
--
-- **tombstone은 삭제 실행의 첫 단계에 생긴다.** Story 5.2까지 tombstone(V25)은 마지막 단계(`IDENTITY`)에서야 기록돼, 그 전에 복원하면
-- 삭제 요청이 사라졌다(deferred 171). 이제 Worker가 실행을 처음 집는 단계(`CREDENTIALS` 앞, 같은 fencing)에서 요청 시각(V30
-- `requested_at`)으로 기록하고, 마지막 단계가 영향 generation 집합을 닫고 보존 기한을 늘린다. Worker의 주기 판정
-- (`BackupDestructionJudge`)이 백업 기록(V24)과 삭제 실행 기록(V31)을 대조해 그 삭제가 든 generation과 두 저장소의 파기를 이 마이그레이션의
-- 표에 남기고, 두 저장소 파기가 모두 확인돼야 `백업 파기 완료`(`DESTROYED`)다. 기한(요청 + 30일)이 지났는데 완료가 아니면 `OVERDUE`다.
--
-- expand만 한다(AD-12). V1~V31은 고치지 않는다 — V25의 기한 CHECK(`= requested_at + 840h`)는 이 파일이 지우고 완화한 CHECK로 바꾼다.

-- ---------------------------------------------------------------------------------------------------------------- tombstone 열

-- 더하는 열
--   policy_version  -- 정책 버전(`DELETION-TOMBSTONE-1.0`). 외부 사본 머리에도 실린다.
--   status          -- PENDING(파기 미완료) · OVERDUE(기한이 지났는데 미완료 — 감사 SYSTEM 행 한 번, 경보) · DESTROYED(백업 파기 완료).
--                      OVERDUE는 뒤에 DESTROYED가 될 수 있다. DESTROYED는 끝이다.
--   closed_at       -- 영향 generation 집합이 닫힌 시각 = 운영 데이터 삭제 완료 시각(ms). 닫히기 전에는 비어 있다(열린 집합).
--   overdue_at      -- OVERDUE가 된 시각. 감사 행이 한 번뿐인 근거다(PENDING → OVERDUE 전환은 한 번이다).
--   destroyed_at    -- 백업 파기 완료를 판정한 시각. 운영 완료(`closed_at`)와 다른 시각이다.
--   destroyed_export -- DESTROYED 상태로 처음 실린 내보내기(`operations_tombstone_export.sequence`). 그 번호 이상의 내보내기가 성공으로
--                       기록돼야(외부 사본이 DESTROYED를 보았다) 이 행이 보존 기한으로 지워지거나 내보내기에서 빠진다 — 앞선 사본에
--                       미완료로 실린 행이 다음 사본에서 바로 빠져 줄어듦 검사(`TOMBSTONE_SHRANK`)가 영원히 막히는 일이 없게.
--
-- 백업 파기 기한(요청 + 30일)은 열이 아니라 `requested_at + interval '720 hours'`(시간대와 무관하게 시간으로 적는다) 한 식이다 — 요청 시각에서
-- 늘 정해지므로 따로 저장하면 두 값이 갈라질 수 있을 뿐이다. 이 시각까지 영향 generation이 두 저장소에서 모두 파기돼야 하고, 외부 사본 줄에는
-- 그 값이 실린다.
--
-- 보존(`destroy_after`)은 요청 + 35일이고, 집합이 닫히면 `max(요청, 운영 완료) + 35일`로 늘린다(줄이지 않는다) — 계정이 든 마지막
-- generation은 운영 완료(+ 커밋 여유 1시간) 전에 dump를 시작했으므로 운영 완료 + 30일 남짓 안에 파기되고, tombstone은 그보다 오래 남는다.
-- 보존 기한으로 지우는 것은 백업 파기가 완료(`DESTROYED`)됐고 그 상태로 성공한 내보내기에 실린(`destroyed_export`) 행뿐이다 — 완료가 아닌
-- 행은 기한이 지나도 남고 외부 사본에도 실린다(애플리케이션 술어, `DeletionTombstoneRepository`).
--
-- 이 마이그레이션 전의 행은 삭제를 끝낸 트랜잭션이 쓴 것이다(단일 모드·5.2 마지막 단계·그 사본을 옮긴 복원 적용) — 그래서 그 행의 집합은
-- 요청 시각에 닫혀 있다(`closed_at = requested_at`). 주의: 5.2 단계 모드가 쓴 행의 `requested_at`은 실제 요청 시각(V30)이 아니라 그
-- 실행의 운영 완료 시각이었다 — 그 행의 백업 파기 기한(`requested_at + 30일`)·보존 기한은 정책 기준(요청 + 30일·35일)보다 늦을 수 있다.
-- 그런 행은 production 삭제가 열리기 전(G-5)이라 개발·CI DB에만 있다.
ALTER TABLE operations_deletion_tombstone
    ADD COLUMN policy_version text        NOT NULL DEFAULT 'DELETION-TOMBSTONE-1.0',
    ADD COLUMN status         text        NOT NULL DEFAULT 'PENDING',
    ADD COLUMN closed_at      timestamptz,
    ADD COLUMN overdue_at     timestamptz,
    ADD COLUMN destroyed_at   timestamptz,
    ADD COLUMN destroyed_export bigint;

UPDATE operations_deletion_tombstone SET closed_at = requested_at;

ALTER TABLE operations_deletion_tombstone
    DROP CONSTRAINT operations_deletion_tombstone_deadline_check,
    -- 보존은 요청 + 35일보다 짧지 않다(닫히면 늘어난다).
    ADD CONSTRAINT operations_deletion_tombstone_retention_check
        CHECK (destroy_after >= requested_at + interval '840 hours'),
    ADD CONSTRAINT operations_deletion_tombstone_policy_check
        CHECK (policy_version ~ '^DELETION-TOMBSTONE-[0-9]+\.[0-9]+$'),
    ADD CONSTRAINT operations_deletion_tombstone_status_check
        CHECK (status IN ('PENDING', 'OVERDUE', 'DESTROYED')),
    -- 닫힌 집합의 보존은 닫힌 시각 + 35일보다 짧지 않다.
    ADD CONSTRAINT operations_deletion_tombstone_closed_check
        CHECK (closed_at IS NULL OR (closed_at >= requested_at AND destroy_after >= closed_at + interval '840 hours')),
    -- 파기 완료는 닫힌 집합에만 있고 완료 시각과 한 쌍이다.
    ADD CONSTRAINT operations_deletion_tombstone_destroyed_check
        CHECK ((status = 'DESTROYED') = (destroyed_at IS NOT NULL) AND (destroyed_at IS NULL OR closed_at IS NOT NULL)),
    ADD CONSTRAINT operations_deletion_tombstone_overdue_check
        CHECK (status <> 'OVERDUE' OR overdue_at IS NOT NULL),
    ADD CONSTRAINT operations_deletion_tombstone_destroyed_export_check
        CHECK (destroyed_export IS NULL OR status = 'DESTROYED');

-- 판정의 후보(파기 완료가 아닌 행).
CREATE INDEX operations_deletion_tombstone_judge_idx ON operations_deletion_tombstone (requested_at)
    WHERE status <> 'DESTROYED';

-- ---------------------------------------------------------------------------------------------------------------- 영향 generation

-- 삭제 하나(tombstone 하나)가 들어 있을 수 있는 백업 generation과 저장소별 파기 확인.
--   request_id        -- tombstone. tombstone이 보존 기한으로 지워지면 함께 지워진다(이 표만 남지 않는다).
--   generation_id     -- 운영 완료 + 1시간 전에 dump를 시작한(V24 `STARTED`) 저장된(`SUCCEEDED`) generation(1시간은 운영 완료 문장과 그
--                        커밋 사이에 시작한 dump가 삭제 전 snapshot을 담을 수 있어서다). 요청 전에 두 저장소에서 이미 파기된
--                        generation은 넣지 않는다(이 삭제가 지울 것이 아니다).
--   oci_purged_at     -- 그 generation의 OCI 파기 기록(V24 `PURGED`, 29일·수량·용량 규칙)의 시각. 없으면 아직 확인되지 않았다.
--   offsite_purged_at -- OCI 밖 저장소의 같은 것.
--   added_at          -- 판정이 이 행을 더한 시각.
--
-- 집합은 실행이 끝나기 전(tombstone `closed_at`이 비었을 때)에는 열려 있어 판정마다 늘 수 있고, 닫히면 운영 완료 + 1시간 전에 시작한 generation만
-- 남는다. 행을 빼지 않는다. 파기 확인 시각은 한 번 채워지면 바꾸지 않는다.
--
-- **분류: 삭제 증거(순수 운영 데이터).** 사용자 식별자·digest·금융값이 없다 — 요청 ID·generation ID·시각뿐이다.
CREATE TABLE operations_deletion_backup_generation (
    request_id        text        NOT NULL
                                  REFERENCES operations_deletion_tombstone (request_id) ON DELETE CASCADE,
    generation_id     text        NOT NULL,
    oci_purged_at     timestamptz,
    offsite_purged_at timestamptz,
    added_at          timestamptz NOT NULL DEFAULT statement_timestamp(),

    PRIMARY KEY (request_id, generation_id),
    CONSTRAINT operations_deletion_backup_generation_id_check CHECK (generation_id ~ '^gen_[0-9]{8}$')
);

-- ---------------------------------------------------------------------------------------------------------------- 내보내기 성공

-- tombstone 외부 사본 하나. 내보내기 역할이 파일을 쓸 때 행 하나를 만들고(`prepared_at`, 그때 DESTROYED인 tombstone에 이 번호를 붙인다),
-- 운영 호스트의 `tombstone-export.sh`가 사본을 올리고 원격 SHA-256을 대조하고 `latest`를 바꾼 **뒤에만** 같은 역할(기록 모드)이 그 행을
-- 성공으로 채운다(`exported_at`·실린 수). Worker가 마지막 성공 시각을 gauge로 내고 경보 `TombstoneExportStale`이 1시간 넘은 정지를 알린다
-- (deferred 138). 35일보다 오래된 행은 내보내기가 지운다.
CREATE TABLE operations_tombstone_export (
    sequence    bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    prepared_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    exported_at timestamptz,
    tombstones  integer,

    CONSTRAINT operations_tombstone_export_count_check CHECK (tombstones IS NULL OR tombstones >= 0),
    CONSTRAINT operations_tombstone_export_confirmed_check
        CHECK ((exported_at IS NULL) = (tombstones IS NULL) AND (exported_at IS NULL OR exported_at >= prepared_at))
);

CREATE INDEX operations_tombstone_export_at_idx ON operations_tombstone_export (exported_at);

-- ---------------------------------------------------------------------------------------------------------------- 삭제 실행의 요청 ID

-- 삭제 실행(V31)이 어느 삭제 요청의 것인가. 요청 행(V30)은 운영 완료 때 지워지므로 판정이 요청 ID ↔ 운영 완료 시각을 이으려면 실행 행이
-- 요청 ID를 들고 있어야 한다. claim이 채운다. 이 마이그레이션 전의 실행은 요청 행이 남아 있을 때만 채운다(끝난 실행의 요청은 이미 없다 —
-- 그 tombstone은 위에서 요청 시각에 닫혔다).
ALTER TABLE identity_deletion_run
    ADD COLUMN request_id text,
    ADD CONSTRAINT identity_deletion_run_request_check
        CHECK (request_id IS NULL OR request_id ~ '^[A-Za-z0-9_-]{1,64}$');

UPDATE identity_deletion_run AS run
SET request_id = request.request_id
FROM identity_deletion_request AS request
WHERE request.user_id = run.user_id;

CREATE UNIQUE INDEX identity_deletion_run_request_idx ON identity_deletion_run (request_id) WHERE request_id IS NOT NULL;

-- 삭제 단계의 실패 code에 `TOMBSTONE_FAILED`(첫 단계 앞의 tombstone 기록이 실패했다)를 더한다. 아직 성공하지 않은 첫 단계에 붙어 그 단계
-- 자체의 실패(`STEP_FAILED`)와 구분된다. 사용자 응답의 `failedSteps`(단계 code)는 그대로다.
ALTER TABLE identity_deletion_step
    DROP CONSTRAINT identity_deletion_step_failure_check,
    ADD CONSTRAINT identity_deletion_step_failure_check
        CHECK ((status = 'RETRY_NEEDED') = (failure_code IS NOT NULL)
            AND (failure_code IS NULL OR failure_code IN ('STEP_FAILED', 'RESIDUAL_FOUND', 'TOMBSTONE_FAILED')));
