-- Story 3.3: 승인된 강한 신호의 판정표와 신호 기록, 신호가 만든 제한의 출처.
--
-- **판정표는 버전 seed다(AC-1).** `operations_signal_policy`가 `OPS-SIGNAL-1.0`(`EPIC-IMPLEMENTATION-READINESS.md` §6.1)의
-- 9행을 담는다. 각 행은 신호 종류·threshold(창 안 전체 관측 수의 하한)·대상당 관측 수의 하한·window·서로 다른 대상 수·
-- 필수 증거 키·scope 규칙이다. 사건 하나가 곧 신호인 행은 1·1·0·1이다. 신호 8 "5분 안에 서로 다른 provider account 2개
-- 이상에서 각 3회"는 threshold 6·대상당 3·창 300초·대상 2다. 판정을 바꾸면 이 행을
-- 고치지 않고 새 버전의 행을 더한다. 코드의 신호 enum(`SignalType`)·현행 버전 상수와 이 seed가 어긋나면 시험
-- (`SignalPolicyTest`)이 실패한다.
--
-- **scope 규칙의 문법.** `|`로 나뉜 대안을 앞에서부터 보고, 그 대안이 요구하는 대상 차원(계획·portfolio·연결·계산 정책)을
-- 신호가 모두 가졌을 때 그 대안을 쓴다. 대안 하나는 `+`로 이은 `<기능>:<scope>` 항목들이다. 마지막 대안은 전역뿐이라
-- 언제나 성립한다 — 범위를 특정할 수 없으면 전역으로 접는다(AC-2).
--
-- **신호 기록은 추가 전용이다.** `operations_signal` 행 하나는 신호 하나가 판정한 `<기능>:<scope>` 하나다. 같은
-- 기능·scope·target의 활성 제한이 이미 있으면 새 제한 없이 그 제한에 연결하고(`LINKED`), 없으면 제한을 만든다
-- (`CREATED`). 신호는 제한을 지우거나 줄이지 못한다.
--
-- **증거는 내부 참조뿐이다(AC-6).** `evidence`는 작업·계산·관측 행 ID 같은 내부 참조와 집계 수만 담는다. 금융값·자격정보·
-- 이메일·원 hash를 넣지 않는다. `fingerprint`는 사용자 귀속 target을 keyed digest로 바꾼 뒤의 SHA-256이라 원식별자가
-- 없다. 사용자 귀속은 `target_user_id` 하나로 분리되고 계정 삭제의 deletion port
-- (`OperationsDeletionApi.eraseRestrictionTargets`)가 그 값을 NULL로 비식별한다. identity를 외래키로 가리키지 않는다
-- (V17·V18과 같은 이유 — 행이 계정보다 오래 산다).
--
-- **시각의 권위는 데이터베이스다.** `detected_at`은 감지 지점이 읽은 DB 시각(없으면 기록 트랜잭션의 첫 DB 시각),
-- `effective_at`은 그 제한의 효력 시작이다. SLI `sli_ms`는 그 차이고 이미 효력 중인 제한에 연결했으면 0이다.
--
-- **sync 표의 색인 하나.** 끝의 `sync_plan_calculation_input_idx`는 sync 모듈이 소유한 표의 색인이다. 신호 4(같은 입력·
-- 정책의 다른 result hash)의 감지 술어(`PlanPublicationRepository.insertCalculation`)가 계산 발행마다 그 표를
-- `(input_hash, calculation_policy)`로 읽으므로 이 스토리의 마이그레이션에 둔다. `CONCURRENTLY` 없이 만든다 — 지금은 행이
-- 적지만, 운영 데이터가 쌓인 뒤 같은 모양의 색인을 더할 때는 생성 동안 그 표의 쓰기가 잠기므로 별도 migration과 창이
-- 필요하다(Story 3.8의 배포 절차).
--
-- expand만 한다(AD-12). V1~V18은 고치지 않는다.

CREATE TABLE operations_signal_policy (
    policy_version    text    NOT NULL,
    signal_type       text    NOT NULL,
    -- 창 안 전체 관측 수의 하한. 1이면 사건 하나가 곧 신호다.
    threshold         integer NOT NULL,
    -- 대상 하나당 관측 수의 하한(신호 8의 "각 3회").
    per_subject_threshold integer NOT NULL,
    -- 관측을 모으는 창(초). 0이면 창이 없다(사건 하나).
    window_seconds    integer NOT NULL,
    -- 창 안에서 서로 다른 대상(계정·사용자)이 몇이어야 하는가.
    distinct_subjects integer NOT NULL,
    -- 신호가 반드시 가져야 하는 증거 키. 빠지면 승격하지 않고 거절한다.
    required_evidence text[]  NOT NULL,
    scope_rule        text    NOT NULL,
    PRIMARY KEY (policy_version, signal_type),
    CONSTRAINT operations_signal_policy_threshold_check CHECK (threshold >= 1),
    CONSTRAINT operations_signal_policy_window_check CHECK (window_seconds >= 0),
    CONSTRAINT operations_signal_policy_distinct_check CHECK (distinct_subjects >= 1),
    CONSTRAINT operations_signal_policy_per_subject_check
        CHECK (per_subject_threshold >= 1 AND threshold >= per_subject_threshold * distinct_subjects),
    -- 둘 이상 관측해야 하는 행은 창이 있어야 한다(창 없는 집계는 판정할 수 없다).
    CONSTRAINT operations_signal_policy_window_needed_check
        CHECK (window_seconds > 0 OR (threshold = 1 AND distinct_subjects = 1)),
    CONSTRAINT operations_signal_policy_evidence_check CHECK (cardinality(required_evidence) >= 1),
    CONSTRAINT operations_signal_policy_scope_rule_check
        CHECK (scope_rule ~ '^[A-Z_]+:[A-Z_]+(\+[A-Z_]+:[A-Z_]+)*(\|[A-Z_]+:[A-Z_]+(\+[A-Z_]+:[A-Z_]+)*)*$')
);

INSERT INTO operations_signal_policy
    (policy_version, signal_type, threshold, per_subject_threshold, window_seconds, distinct_subjects,
     required_evidence, scope_rule)
VALUES
    -- 1. 쓰기 가능 Bybit key가 저장 단계에 도달: 신규 연결+동기화 전역.
    ('OPS-SIGNAL-1.0', 'WRITE_KEY_STORED', 1, 1, 0, 1, ARRAY['connectionId'],
        'NEW_CONNECTION:GLOBAL+SYNC:GLOBAL'),
    -- 2. 교차 사용자 데이터 접근 성공·공식 pointer 혼합: 금융 read(계산 표시)·신규 연결·동기화 전역.
    ('OPS-SIGNAL-1.0', 'CROSS_USER_ACCESS', 1, 1, 0, 1, ARRAY['assertionId'],
        'NEW_CONNECTION:GLOBAL+SYNC:GLOBAL+CALCULATION_DISPLAY:GLOBAL'),
    -- 3. secret·token이 redaction canary나 외부 telemetry에서 검출: 신규 연결·동기화·민감 화면(계산 표시) 전역.
    ('OPS-SIGNAL-1.0', 'SECRET_EXPOSED', 1, 1, 0, 1, ARRAY['ruleId'],
        'NEW_CONNECTION:GLOBAL+SYNC:GLOBAL+CALCULATION_DISPLAY:GLOBAL'),
    -- 4. 같은 canonical input·policy의 다른 result hash: 그 계산 정책의 표시·발행. 정책을 모르면 동기화+계산 전역.
    ('OPS-SIGNAL-1.0', 'RESULT_HASH_MISMATCH', 1, 1, 0, 1, ARRAY['calculationId'],
        'CALCULATION_DISPLAY:CALCULATION_POLICY_VERSION|SYNC:GLOBAL+CALCULATION_DISPLAY:GLOBAL'),
    -- 5. portfolio 행 잠금 아래 pointer CAS 불변식 위반: 그 portfolio. 모르면 동기화+계산 전역.
    ('OPS-SIGNAL-1.0', 'PORTFOLIO_INVARIANT_VIOLATION', 1, 1, 0, 1, ARRAY['jobId'],
        'SYNC:PORTFOLIO+CALCULATION_DISPLAY:PORTFOLIO|SYNC:GLOBAL+CALCULATION_DISPLAY:GLOBAL'),
    -- 6. 같은 source ID의 semantic 충돌: 그 계획의 발행, 계획이 없으면 그 연결. 전역 정지가 아니다.
    ('OPS-SIGNAL-1.0', 'SOURCE_ID_CONFLICT', 1, 1, 0, 1, ARRAY['jobId', 'observationId'],
        'SYNC:PLAN|SYNC:PROVIDER_CONNECTION|NEW_CONNECTION:GLOBAL+SYNC:GLOBAL'),
    -- 7. 미인식 Transaction Log enum: 그 연결(provider account)의 발행. 전역 정지가 아니다.
    ('OPS-SIGNAL-1.0', 'UNKNOWN_TRANSACTION_ENUM', 1, 1, 0, 1, ARRAY['jobId', 'observationId'],
        'SYNC:PROVIDER_CONNECTION|NEW_CONNECTION:GLOBAL+SYNC:GLOBAL'),
    -- 8. schema 검증 실패가 5분 안에 서로 다른 provider account 2개 이상에서 각 3회: 신규 연결+동기화 전역.
    ('OPS-SIGNAL-1.0', 'SCHEMA_VALIDATION_BURST', 6, 3, 300, 2,
        ARRAY['detectorRunId', 'observedCount', 'distinctSubjects', 'minPerSubject', 'windowSeconds'],
        'NEW_CONNECTION:GLOBAL+SYNC:GLOBAL'),
    -- 9. credential 복호화 실패가 5분 안에 서로 다른 사용자 2명 이상: 신규 연결+동기화 전역.
    ('OPS-SIGNAL-1.0', 'CREDENTIAL_DECRYPTION_BURST', 2, 1, 300, 2,
        ARRAY['detectorRunId', 'observedCount', 'distinctSubjects', 'minPerSubject', 'windowSeconds'],
        'NEW_CONNECTION:GLOBAL+SYNC:GLOBAL');

-- Story 3.2의 제한 행이 어디서 왔는가. 운영자 command(`OPERATOR`)이거나 강한 신호(`SIGNAL`)다. 신호가 만든 행의
-- `operation_id`는 그 신호의 operation ID라 3.2의 활성 중복 부분 유일 색인이 그대로 적용된다.
ALTER TABLE operations_restriction ADD COLUMN origin text NOT NULL DEFAULT 'OPERATOR';
ALTER TABLE operations_restriction ADD CONSTRAINT operations_restriction_origin_check
    CHECK (origin IN ('OPERATOR', 'SIGNAL'));

CREATE TABLE operations_signal (
    signal_id         uuid        PRIMARY KEY,
    -- 신호 하나(raise 한 번)의 operation ID. 그 신호가 판정한 여러 `<기능>:<scope>` 행과 감사 SYSTEM 행이 이 값으로 묶인다.
    operation_id      text        NOT NULL,
    signal_type       text        NOT NULL,
    policy_version    text        NOT NULL,
    -- SHA-256(신호 종류 | 정책 버전 | `<기능>:<scope>` | target). 사용자 귀속 target은 keyed digest로 바꾼 뒤에 넣는다.
    fingerprint       text        NOT NULL,
    -- 판정한 `<기능>:<scope>`.
    restriction_scope text        NOT NULL,
    -- 그 scope를 고른 이유: 판정표의 대안 하나(`POLICY`), 좁은 대안의 대상 차원을 몰라 마지막 전역 대안으로 접음
    -- (`SCOPE_UNKNOWN`), 판정표를 읽지 못해 신규 연결+동기화 전역(`POLICY_UNAVAILABLE`).
    scope_decision    text        NOT NULL,
    detected_at       timestamptz NOT NULL DEFAULT statement_timestamp(),
    effective_at      timestamptz NOT NULL,
    evidence          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    -- 사용자 귀속. 계정 삭제 시 NULL로 비식별된다(deletion port).
    target_user_id    uuid,
    restriction_id    uuid        NOT NULL REFERENCES operations_restriction (restriction_id),
    outcome           text        NOT NULL,
    sli_ms            bigint      NOT NULL,
    recorded_at       timestamptz NOT NULL DEFAULT statement_timestamp(),

    CONSTRAINT operations_signal_operation_id_length_check CHECK (octet_length(operation_id) BETWEEN 1 AND 64),
    CONSTRAINT operations_signal_type_check CHECK (signal_type ~ '^[A-Z][A-Z_]{0,63}$'),
    CONSTRAINT operations_signal_policy_version_length_check CHECK (octet_length(policy_version) BETWEEN 1 AND 64),
    CONSTRAINT operations_signal_fingerprint_check CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT operations_signal_scope_check CHECK (restriction_scope ~ '^[A-Z_]+:[A-Z_]+$'),
    CONSTRAINT operations_signal_decision_check
        CHECK (scope_decision IN ('POLICY', 'SCOPE_UNKNOWN', 'POLICY_UNAVAILABLE')),
    CONSTRAINT operations_signal_evidence_object_check CHECK (jsonb_typeof(evidence) = 'object'),
    CONSTRAINT operations_signal_outcome_check CHECK (outcome IN ('CREATED', 'LINKED')),
    CONSTRAINT operations_signal_sli_check CHECK (sli_ms >= 0)
);

-- 같은 fingerprint의 반복 조회.
CREATE INDEX operations_signal_fingerprint_idx ON operations_signal (fingerprint, detected_at);
-- 제한에서 그것을 만든·연결된 신호로.
CREATE INDEX operations_signal_restriction_idx ON operations_signal (restriction_id);
-- operation ID 조회.
CREATE INDEX operations_signal_operation_idx ON operations_signal (operation_id);
-- 삭제 port의 비식별.
CREATE INDEX operations_signal_target_user_idx ON operations_signal (target_user_id);
-- 신호를 낸 작업의 자기 제한 조회(`restrictionsRaisedBy`): 수집 작업(`jobId`)과 계획 동기화 시도(`attemptId`).
CREATE INDEX operations_signal_job_idx ON operations_signal ((evidence->>'jobId'));
CREATE INDEX operations_signal_attempt_idx ON operations_signal ((evidence->>'attemptId'));

-- 신호 4(같은 입력·정책의 다른 result hash)의 감지 술어가 계산 발행마다 읽는다. 순차 검색이 되지 않게 한다.
CREATE INDEX sync_plan_calculation_input_idx ON sync_plan_calculation (input_hash, calculation_policy);
