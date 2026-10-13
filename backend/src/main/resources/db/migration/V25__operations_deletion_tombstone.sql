-- Story 3.10: 계정 삭제의 복원 방지 tombstone outbox(`DELETION-TOMBSTONE-1.0`).
--
-- 계정 삭제는 루트 `AccountErasure` 한 트랜잭션이 한다 — 삭제 전환 → 이 표에 행 하나 → 모듈 erase port → payload 삭제. 그래서 기록
-- 없는 탈퇴가 없다. 운영 호스트의 timer(`deploy/production/tombstone-export.sh`, 15분 주기)가 일회성 `tombstone-export` 역할로 살아 있는
-- 행 전체를 파일 하나로 받아 tombstone 전용 age key로 암호화하고 OCI 밖 저장소(`tombstones/`)에 올린다. 복원(`restore.sh`)은 그 외부
-- 사본을 서비스를 열기 전에 적용한다 — **백업 안에 든 이 표는 tombstone의 권위가 아니다**(백업은 그 뒤의 삭제를 모른다).
--
-- 행 하나가 삭제 요청 하나다.
--   request_id    -- 삭제 요청 ID(호출자가 준다). 재시도는 같은 ID다.
--   user_digest   -- 내부 user_id의 keyed digest(HMAC-SHA256, 전용 키 `BOKRILOG_TOMBSTONE_DIGEST_KEY`, 입력 접두사 `tombstone:`).
--                    감사 digest 키와 다른 키다. 키 없이는 계정을 되짚을 수 없다.
--   requested_at  -- 요청 시각(DB 시각, ms).
--   destroy_after -- 파기 기한 = 요청 + 35일(백업 최대 수명 30일보다 길다). 기한이 지난 행은 내보내기 역할이 지운다.
--   origin        -- `DELETION`(이 DB 계보의 삭제가 쓴 행) 또는 `RESTORE`(복원 적용이 외부 사본에서 옮긴 행). 복원의 대조는 `DELETION`
--                    행만 계정과 맞춰 본다 — 옮긴 행은 백업 뒤에 생기고 지워진 계정의 것이라 맞는 계정이 없는 것이 정상이다.
--
-- **분류: 삭제 증거(순수 운영 데이터).** 사용자 ID 원문·이메일·소셜 식별자·금융값·자격정보가 없다 — keyed digest와 요청 ID·시각뿐이다.
-- 그래서 deletion port가 다룰 행이 없고 법정 분리 보존 후보도 없다(G-5 전, 개발·CI 기본값 "없음"). 35일 뒤 지운다.
--
-- 애플리케이션의 SQL은 한 클래스(`DeletionTombstoneRepository`)에 있고 시험이 그 소스를 훑어 강제한다. 문장은 INSERT·SELECT와
-- 기한 지난 행의 DELETE 하나다(행을 고치는 문장이 없다).
--
-- expand만 한다(AD-12). V1~V24는 고치지 않는다.

CREATE TABLE operations_deletion_tombstone (
    request_id    text        PRIMARY KEY,
    user_digest   text        NOT NULL,
    requested_at  timestamptz NOT NULL,
    destroy_after timestamptz NOT NULL,
    origin        text        NOT NULL,

    CONSTRAINT operations_deletion_tombstone_request_check CHECK (request_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    CONSTRAINT operations_deletion_tombstone_digest_check CHECK (user_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT operations_deletion_tombstone_origin_check CHECK (origin IN ('DELETION', 'RESTORE')),
    -- 35일은 시간대와 무관한 840시간으로 적는다('35 days'는 세션 시간대의 일광 절약 전환에 따라 길이가 달라진다).
    CONSTRAINT operations_deletion_tombstone_deadline_check
        CHECK (destroy_after = requested_at + interval '840 hours'),
    -- 계정 하나에 살아 있는 tombstone 하나다. 같은 계정의 재시도·복원 적용은 이미 있는 행을 그대로 둔다.
    CONSTRAINT operations_deletion_tombstone_user_key UNIQUE (user_digest)
);

CREATE INDEX operations_deletion_tombstone_destroy_idx ON operations_deletion_tombstone (destroy_after);
