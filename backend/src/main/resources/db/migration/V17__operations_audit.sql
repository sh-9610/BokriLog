-- Story 3.1: 보호된 운영자 표면의 추가 전용 감사 원장.
--
-- **이 표가 운영 command의 권위 원본이다.** 원격 로그가 유실되어도 요청·승인·거절·실패·완료가 이 행들로 재현된다.
-- 행은 추가만 한다. 애플리케이션의 원장 SQL은 INSERT·SELECT와 삭제 port(`OperationsDeletionApi.eraseActor`)의 비식별
-- 문장 하나뿐이며(`OperatorAuditRepository`) 시험이 그 소스를 훑어 강제한다. DB 트리거로 수정·삭제를 막는 것은 그 비식별
-- 문장과 충돌하므로 이번에는 두지 않는다(SPEC Ask First).
--
-- **사용자 귀속 데이터의 분류.** OPERATOR 행은 "사용자 귀속 운영 데이터"다 — `actor_user_id`가 운영자 계정을 가리키고,
-- 계정 삭제 시 삭제 port가 그 값을 NULL로 바꾸고 `actor_erased_at`을 남긴다. `actor_digest`(설정 키의 HMAC-SHA256)와
-- 사실(무엇을·언제·결과)은 남는다. SYSTEM 행은 "순수 운영 데이터"이며 사용자 귀속이 없다. 법정 분리 보존 후보 필드는
-- 외부 승인(G-5) 전까지 없다(개발·CI 기본값 "없음") — 이 표의 어떤 열도 영구 보존으로 간주하지 않는다.
--
-- **identity를 외래키로 가리키지 않는다.** 가리키면 계정 payload 삭제의 연쇄가 감사 행을 지우거나(CASCADE) 삭제를
-- 막는다(RESTRICT). 감사 행은 계정보다 오래 살아야 하고 귀속은 삭제 port가 비식별한다. 모듈 경계도 같은 방향이다 —
-- operations는 identity의 표를 모른다.
--
-- 행에 API 비밀·세션 id·금융 상세·이메일·원식별자(issuer·subject)를 넣지 않는다. actor는 내부 `user_id`와 keyed
-- digest뿐이다. 확인 문구는 원문이 아니라 허가와 같은 정규 hash로만 `details`에 남는다.
--
-- expand 우선이다(AD-12). 만들기만 한다. V1~V16은 고치지 않는다.

CREATE TABLE operations_audit_event (
    audit_id        uuid        PRIMARY KEY,
    -- 추가 순서. 원장 append는 advisory 잠금 하나로 직렬화되므로(`OperatorAuditRepository`) 이 값과 `recorded_at`이
    -- 같은 방향으로 늘고, `(recorded_at, sequence)` keyset cursor가 늦게 커밋되는 행을 건너뛰지 않는다.
    sequence        bigint      GENERATED ALWAYS AS IDENTITY UNIQUE,
    -- 서버가 만든 불투명 operation ID. 한 operation의 요청과 결과가 이 값으로 묶인다.
    operation_id    text        NOT NULL,
    -- 사용자 귀속. 계정 삭제 시 NULL로 비식별된다(삭제 port).
    actor_user_id   uuid,
    -- 설정 키의 HMAC-SHA256 16진. 비식별 뒤에도 같은 수행자의 행을 묶는다.
    actor_digest    text        NOT NULL,
    actor_kind      text        NOT NULL,
    command         text        NOT NULL,
    scope           text        NOT NULL,
    target          text        NOT NULL,
    reason          text        NOT NULL,
    outcome         text        NOT NULL,
    policy_version  text        NOT NULL,
    requested_at    timestamptz NOT NULL,
    decided_at      timestamptz,
    correlation_id  text        NOT NULL,
    -- 닫힌 소량의 부가 사실(거절 code·추출 상한·요청 digest). 금융값·식별자 원문을 담지 않는다.
    details         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    recorded_at     timestamptz NOT NULL DEFAULT statement_timestamp(),
    actor_erased_at timestamptz,

    CONSTRAINT operations_audit_event_operation_id_length_check
        CHECK (octet_length(operation_id) BETWEEN 1 AND 64),
    CONSTRAINT operations_audit_event_actor_digest_check
        CHECK (actor_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT operations_audit_event_actor_kind_check
        CHECK (actor_kind IN ('OPERATOR', 'SYSTEM')),
    -- SYSTEM 행은 사용자 귀속이 없다. 비식별된 행은 귀속이 없다.
    CONSTRAINT operations_audit_event_system_actor_check
        CHECK (actor_kind = 'OPERATOR' OR actor_user_id IS NULL),
    CONSTRAINT operations_audit_event_erased_check
        CHECK (actor_erased_at IS NULL OR actor_user_id IS NULL),
    CONSTRAINT operations_audit_event_command_length_check
        CHECK (octet_length(command) BETWEEN 1 AND 64),
    CONSTRAINT operations_audit_event_scope_length_check
        CHECK (octet_length(scope) BETWEEN 1 AND 64),
    CONSTRAINT operations_audit_event_target_length_check
        CHECK (octet_length(target) BETWEEN 1 AND 256),
    CONSTRAINT operations_audit_event_reason_length_check
        CHECK (octet_length(reason) BETWEEN 1 AND 2000),
    CONSTRAINT operations_audit_event_outcome_check
        CHECK (outcome IN ('REQUESTED', 'APPROVED', 'DENIED', 'FAILED', 'CANCELLED', 'COMPLETED')),
    CONSTRAINT operations_audit_event_policy_version_length_check
        CHECK (octet_length(policy_version) BETWEEN 1 AND 64),
    CONSTRAINT operations_audit_event_correlation_id_length_check
        CHECK (octet_length(correlation_id) BETWEEN 1 AND 64),
    CONSTRAINT operations_audit_event_details_object_check
        CHECK (jsonb_typeof(details) = 'object'),
    CONSTRAINT operations_audit_event_decided_check
        CHECK (decided_at IS NULL OR decided_at >= requested_at)
);

-- 감사 조회의 keyset cursor(최신순).
CREATE INDEX operations_audit_event_recorded_idx ON operations_audit_event (recorded_at, sequence);
-- operation ID 조회.
CREATE INDEX operations_audit_event_operation_idx ON operations_audit_event (operation_id);
-- 삭제 port의 비식별과 수행자 범위 조회.
CREATE INDEX operations_audit_event_actor_idx ON operations_audit_event (actor_user_id);
