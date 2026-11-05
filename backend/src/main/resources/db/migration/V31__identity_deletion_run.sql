-- Story 5.2: 계정 삭제 실행과 단계 상태(부분 실패의 권위 기록).
--
-- **실행 하나가 계정 하나다.** 사용자의 삭제 요청(V30)이 있는 `DELETION_IN_PROGRESS` 계정을 Worker가 lease로 집으면 첫 claim이 이
-- 표의 행과 단계 행 여덟을 만든다(5.1 확정 경로는 이 표를 모른다). 단계는 고정 순서(`DeletionStep` — CREDENTIALS → PORTFOLIO → SYNC →
-- PLAN → SOURCE → OPERATIONS → ENGAGEMENT → IDENTITY)이고 단계마다 따로 커밋된다. 실패한 단계는 `RETRY_NEEDED`로 남고 실행은 거기서
-- 멈춘다 — 자동 재시도가 없고, 요청 세션의 `다시 시도`(`POST /api/account/deletion/retry`)가 실행을 `QUEUED`로 되돌릴 때만 Worker가
-- 다시 집는다. 마지막 단계(`IDENTITY`)가 tombstone 기록·`DELETED` 전환과 같은 트랜잭션에서 `operational_erased_at`을 쓴다 — 그것은
-- "운영 데이터 삭제 완료"이고 백업 파기 완료가 아니다(Story 5.3).
--
-- identity_deletion_run
--   user_id               -- 생명주기 guard 행을 가리킨다(삭제가 끝나도 남는 행). 계정당 실행 하나다.
--   status                -- QUEUED(집기를 기다린다) · RUNNING(lease 아래 실행 중) · RETRY_NEEDED(한 단계가 실패해 멈췄다 — 다시 시도를
--                            받기 전까지 집지 않는다) · OPERATIONAL_DATA_ERASED(모든 단계 성공, 운영 데이터 삭제 완료).
--   fencing_generation    -- claim마다 하나씩 오른다. 단계 커밋은 자기 세대와 살아 있는 lease를 잠금 아래에서 다시 본다(늦은 Worker 거절).
--   lease_until           -- RUNNING일 때만 있다. 수명은 작업 lease(`SyncLease`)와 같은 30초이고 heartbeat가 민다.
--   heartbeat_at          -- 마지막 claim·heartbeat 시각.
--   created_at            -- 첫 claim 시각.
--   operational_erased_at -- 운영 데이터 삭제 완료 시각. OPERATIONAL_DATA_ERASED일 때만 있다.
--   retry_key_digest      -- 마지막으로 받은 다시 시도의 멱등 키 SHA-256. 키 원문은 남기지 않는다. 같은 키의 재전송은 같은 202다.
--   retry_requested_at    -- 마지막 다시 시도 접수 시각.
--
-- identity_deletion_step
--   step          -- 단계 code(`DeletionStep`). 순서는 `position`이다.
--   status        -- PENDING(아직 성공하지 않았다) · SUCCEEDED · RETRY_NEEDED(실패 — failure_code가 있다).
--   erased_rows   -- 그 단계가 지운(또는 비식별한) 행 수. 성공한 단계에만 있다.
--   failure_code  -- 닫힌 code: STEP_FAILED(단계가 예외로 끝났다) · RESIDUAL_FOUND(단계 뒤 또는 마지막 단계의 재검사에서 그 모듈의
--                    잔여 행이 있었다). 예외 메시지·금융 상세·원 식별자는 어디에도 남기지 않는다.
--   attempts      -- 그 단계가 실행된 횟수(성공·실패 모두).
--   finished_at   -- 마지막 결과 시각.
--
-- **분류: 삭제 기록(생명주기 행과 같은 운명).** 이 두 표에는 이름·이메일·금융값·자격정보·소셜 식별자가 없고, 계정 삭제가 끝나도
-- 남는다(완료 시각의 근거다). 그래서 identity 잔여 행 수(`remainingRowsOf`)는 이 두 표를 세지 않는다.
--
-- 애플리케이션의 SQL은 한 클래스(`IdentityAccountRepository`)에 있다. expand만 한다(AD-12). V1~V30은 고치지 않는다.

CREATE TABLE identity_deletion_run (
    user_id               uuid        PRIMARY KEY
                                      REFERENCES identity_account_lifecycle (user_id) ON DELETE RESTRICT,
    status                text        NOT NULL,
    fencing_generation    bigint      NOT NULL DEFAULT 0,
    lease_until           timestamptz,
    heartbeat_at          timestamptz,
    created_at            timestamptz NOT NULL DEFAULT statement_timestamp(),
    operational_erased_at timestamptz,
    retry_key_digest      text,
    retry_requested_at    timestamptz,

    CONSTRAINT identity_deletion_run_status_check
        CHECK (status IN ('QUEUED', 'RUNNING', 'RETRY_NEEDED', 'OPERATIONAL_DATA_ERASED')),
    CONSTRAINT identity_deletion_run_fencing_check CHECK (fencing_generation >= 0),
    -- lease는 실행 중에만 있다. 끝난(멈춘) 실행은 lease를 쥐지 않는다.
    CONSTRAINT identity_deletion_run_lease_check CHECK ((status = 'RUNNING') = (lease_until IS NOT NULL)),
    -- 운영 데이터 삭제 완료 시각은 그 상태와 한 쌍이다. 완료는 되돌리지 않는다(애플리케이션 술어가 지킨다).
    CONSTRAINT identity_deletion_run_erased_check
        CHECK ((status = 'OPERATIONAL_DATA_ERASED') = (operational_erased_at IS NOT NULL)),
    CONSTRAINT identity_deletion_run_retry_check
        CHECK ((retry_key_digest IS NULL) = (retry_requested_at IS NULL)
            AND (retry_key_digest IS NULL OR retry_key_digest ~ '^[0-9a-f]{64}$'))
);

CREATE TABLE identity_deletion_step (
    user_id      uuid        NOT NULL REFERENCES identity_deletion_run (user_id) ON DELETE RESTRICT,
    step         text        NOT NULL,
    position     smallint    NOT NULL,
    status       text        NOT NULL DEFAULT 'PENDING',
    erased_rows  bigint,
    failure_code text,
    attempts     integer     NOT NULL DEFAULT 0,
    finished_at  timestamptz,

    PRIMARY KEY (user_id, step),
    CONSTRAINT identity_deletion_step_position_key UNIQUE (user_id, position),
    CONSTRAINT identity_deletion_step_code_check CHECK (step IN ('CREDENTIALS', 'PORTFOLIO', 'SYNC', 'PLAN', 'SOURCE',
        'OPERATIONS', 'ENGAGEMENT', 'IDENTITY')),
    CONSTRAINT identity_deletion_step_position_check CHECK (position BETWEEN 1 AND 8),
    CONSTRAINT identity_deletion_step_status_check CHECK (status IN ('PENDING', 'SUCCEEDED', 'RETRY_NEEDED')),
    CONSTRAINT identity_deletion_step_attempts_check CHECK (attempts >= 0),
    -- 실패 code는 재시도 필요에만, 건수는 성공에만 있다.
    CONSTRAINT identity_deletion_step_failure_check
        CHECK ((status = 'RETRY_NEEDED') = (failure_code IS NOT NULL)
            AND (failure_code IS NULL OR failure_code IN ('STEP_FAILED', 'RESIDUAL_FOUND'))),
    CONSTRAINT identity_deletion_step_rows_check
        CHECK ((status = 'SUCCEEDED') = (erased_rows IS NOT NULL) AND (erased_rows IS NULL OR erased_rows >= 0))
);

-- 집기(claim)의 후보는 끝나지 않은 실행(QUEUED·RUNNING)뿐이다. 끝난 실행(완료)과 멈춘 실행(재시도 필요)은 색인에 들지 않아 쌓여도 집기를
-- 느리게 하지 않는다. 순서는 집기 질의의 `ORDER BY created_at, user_id`와 같다.
CREATE INDEX identity_deletion_run_claim_idx ON identity_deletion_run (created_at, user_id)
    WHERE status IN ('QUEUED', 'RUNNING');
