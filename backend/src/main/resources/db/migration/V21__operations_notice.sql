-- Story 3.5: 제한·장애·복구 상태를 서비스 내부 공지로 안내한다.
--
-- **incident와 그 공지 버전은 추가 전용이다(AC-3).** `operations_notice_incident` 행 하나가 사건 하나이고 시작 시각은
-- 그 첫 버전을 쓴 문장의 DB 시각이다. `operations_notice_version` 행 하나가 공지 버전 하나다 — 범위 변경과 복구는 앞
-- 버전을 고치지 않고 다음 번호의 행을 더한다. 애플리케이션의 이 두 표 SQL은 INSERT·SELECT뿐이며
-- (`ServiceNoticeRepository`) 시험(`ServiceNoticeTest`)이 운영 소스를 훑어 그 사실을 강제한다.
--
-- **버전 연속성.** 첫 버전은 1이고 `INVESTIGATING`이며 이전 버전이 없다. 그 뒤 버전은 바로 앞 번호를 이전 버전으로
-- 가리키고(같은 표의 복합 외래키) `SCOPE_CHANGED`이거나 `RECOVERED`다. `RECOVERED`는 사건마다 하나다(부분 유일 색인).
-- 복구 뒤의 새 버전은 애플리케이션이 원장 추가 잠금 아래에서 최신 버전을 보고 거절한다(`INCIDENT_CLOSED`). 같은 번호의
-- 동시 발행은 기본키가 거절한다(`NOTICE_VERSION_CONFLICT`).
--
-- **영향 기능은 서버가 계산한다.** `affected_features`는 그 버전이 덮는 제한(`covers`)의 기능이고
-- `continuing_features`는 닫힌 기능 넷 가운데 나머지다. 운영자가 쓰는 것은 원인 수준의 한 줄(`message`)뿐이다.
--
-- **사용자 귀속이 없다.** `covers`는 제한 행 ID(난수)뿐이고 사용자 식별자·금융값·제한 target 원문을 담지 않는다. 그래서
-- 계정 삭제의 deletion port가 이 표를 건드리지 않는다. 제한 표를 외래키로 가리키지 않는다 — 배열 원소는 외래키가 될 수
-- 없고, 제한 행은 지워지지 않으므로(닫힐 뿐이다) 가리킨 ID는 사라지지 않는다.
--
-- **지원 연락처는 버전마다 고정한다(AC-1).** 발행 확정 시점의 배포 설정(`BOKRILOG_SUPPORT_CONTACT`)을 그 버전 행에 남기고
-- 조회는 그 값을 싣는다 — 재배포로 이미 발행한 공지의 연락처가 소급해 바뀌지 않는다. 연락처는 사용자가 아니라 서비스의
-- 문의 주소다.
--
-- expand만 한다(AD-12). V1~V20은 고치지 않는다.

CREATE TABLE operations_notice_incident (
    incident_id  uuid        PRIMARY KEY,
    -- 첫 버전을 쓴 문장의 DB 시각. 공지의 `startedAt`이다.
    started_at   timestamptz NOT NULL DEFAULT statement_timestamp(),
    -- 사건을 연 운영 command(원장의 operation).
    operation_id text        NOT NULL,

    CONSTRAINT operations_notice_incident_operation_id_length_check
        CHECK (octet_length(operation_id) BETWEEN 1 AND 64)
);

CREATE TABLE operations_notice_version (
    incident_id         uuid        NOT NULL REFERENCES operations_notice_incident (incident_id),
    version             integer     NOT NULL,
    previous_version    integer,
    status              text        NOT NULL,
    -- 운영자 한 줄. 이메일·숫자 6자리 이상 연속·URL 모양은 애플리케이션이 거절한다(금융값·식별자·내부 경로 차단).
    message             text        NOT NULL,
    affected_features   text[]      NOT NULL,
    continuing_features text[]      NOT NULL,
    -- 진행 중인 버전의 다음 안내 시각. 복구 버전에는 없다.
    next_update_at      timestamptz,
    -- 복구 버전의 복구 시각(DB 시각). 진행 중인 버전에는 없다.
    recovered_at        timestamptz,
    covers              uuid[]      NOT NULL,
    -- 이 버전을 발행한 운영 command(원장의 operation).
    operation_id        text        NOT NULL,
    -- 발행 확정 시점의 지원 연락처 이메일. 비어 있으면 발행 자체가 거절되므로 언제나 있다.
    support_contact     text        NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT statement_timestamp(),

    -- 이름을 고정한다. 같은 번호의 동시 발행을 애플리케이션이 이 제약 이름으로 가른다(`NOTICE_VERSION_CONFLICT`).
    CONSTRAINT operations_notice_version_pkey PRIMARY KEY (incident_id, version),
    CONSTRAINT operations_notice_version_previous_fk
        FOREIGN KEY (incident_id, previous_version) REFERENCES operations_notice_version (incident_id, version),
    CONSTRAINT operations_notice_version_number_check CHECK (version >= 1),
    -- 첫 버전만 이전 버전이 없고, 그 밖은 바로 앞 번호를 가리킨다.
    CONSTRAINT operations_notice_version_previous_check
        CHECK ((version = 1 AND previous_version IS NULL) OR (version > 1 AND previous_version = version - 1)),
    CONSTRAINT operations_notice_version_status_check
        CHECK (status IN ('INVESTIGATING', 'SCOPE_CHANGED', 'RECOVERED')),
    -- 새 사건은 조사 중으로 시작하고, 그 뒤 버전은 범위 변경이거나 복구다.
    CONSTRAINT operations_notice_version_first_status_check
        CHECK ((version = 1) = (status = 'INVESTIGATING')),
    -- 상태별 다음 시각: 진행 중이면 다음 안내 시각만, 복구면 복구 시각만.
    CONSTRAINT operations_notice_version_schedule_check
        CHECK ((status = 'RECOVERED' AND recovered_at IS NOT NULL AND next_update_at IS NULL)
            OR (status <> 'RECOVERED' AND next_update_at IS NOT NULL AND recovered_at IS NULL)),
    CONSTRAINT operations_notice_version_message_check
        CHECK (char_length(message) BETWEEN 1 AND 200),
    CONSTRAINT operations_notice_version_affected_check
        CHECK (cardinality(affected_features) BETWEEN 1 AND 4
            AND affected_features <@ ARRAY['NEW_CONNECTION', 'SYNC', 'CALCULATION_DISPLAY', 'ADVERTISING']::text[]),
    CONSTRAINT operations_notice_version_continuing_check
        CHECK (cardinality(continuing_features) <= 3
            AND continuing_features <@ ARRAY['NEW_CONNECTION', 'SYNC', 'CALCULATION_DISPLAY', 'ADVERTISING']::text[]
            AND NOT (continuing_features && affected_features)),
    CONSTRAINT operations_notice_version_covers_check
        CHECK (cardinality(covers) BETWEEN 1 AND 50),
    CONSTRAINT operations_notice_version_operation_id_length_check
        CHECK (octet_length(operation_id) BETWEEN 1 AND 64),
    CONSTRAINT operations_notice_version_support_contact_check
        CHECK (char_length(support_contact) BETWEEN 3 AND 254
            AND support_contact ~ '^[^[:space:]@<>"]+@[^[:space:]@<>"]+$')
);

-- 사건마다 복구 버전은 하나다.
CREATE UNIQUE INDEX operations_notice_version_recovered_key
    ON operations_notice_version (incident_id) WHERE status = 'RECOVERED';

-- 사용자 조회: 효력 중인 제한을 덮는 공지(배열 겹침)와 72시간 안의 복구 공지.
CREATE INDEX operations_notice_version_covers_idx ON operations_notice_version USING gin (covers);
CREATE INDEX operations_notice_version_recovered_idx
    ON operations_notice_version (recovered_at) WHERE status = 'RECOVERED';
