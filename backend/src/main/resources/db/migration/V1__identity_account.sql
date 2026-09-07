-- identity 모듈의 계정 생명주기 스키마.
--
-- 세 테이블로 나눈 이유는 삭제 때문이다. guard(`identity_account_lifecycle`)는 payload가 사라진
-- 뒤에도 남아 신규 세션·매핑 생성을 계속 거부해야 하고, payload(`identity_account`)와
-- 매핑(`identity_social_link`)은 사라져야 한다. 한 테이블에 상태 컬럼으로 섞으면 "payload를
-- 지웠다"와 "행을 지웠다"가 같은 연산이 되어 삭제 완료 판정이 모호해진다.
--
-- 이메일·표시명·제공자 토큰 컬럼을 두지 않는다. 이메일은 식별키가 아니며 이메일 일치로 계정을
-- 생성·병합·전환하지 않는다는 Epic 1의 불변식이 스키마 수준에서 지켜져야 한다.
--
-- expand 우선이다(AD-12). 이 마이그레이션은 만들기만 하고 아무것도 지우거나 좁히지 않는다.
-- 실행은 별도 migration 역할이 하고 API 기동은 schema 검증만 한다.

-- 계정 생명주기 guard. payload 삭제 뒤에도 남는 최소 행이다.
CREATE TABLE identity_account_lifecycle (
    user_id          uuid        PRIMARY KEY,
    -- ACTIVE -> DELETION_IN_PROGRESS -> DELETED 단조 전진뿐이다. 되돌아가는 전이는 없다.
    -- 애플리케이션은 UPDATE 술어로 전진을 강제하고 이 CHECK는 값 집합만 고정한다.
    state            text        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    state_changed_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT identity_account_lifecycle_state_check
        CHECK (state IN ('ACTIVE', 'DELETION_IN_PROGRESS', 'DELETED'))
);

-- 계정 payload. 삭제 경로가 이 행을 지운다. 행의 존재 자체가 "payload가 남아 있다"는 사실이다.
CREATE TABLE identity_account (
    user_id    uuid        PRIMARY KEY
                           REFERENCES identity_account_lifecycle (user_id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- 소셜 인증 매핑. 삭제 경로가 함께 지운다.
CREATE TABLE identity_social_link (
    link_id   uuid        PRIMARY KEY,
    user_id   uuid        NOT NULL
                          REFERENCES identity_account (user_id) ON DELETE RESTRICT,
    provider  text        NOT NULL,
    issuer    text        NOT NULL,
    subject   text        NOT NULL,
    linked_at timestamptz NOT NULL DEFAULT now(),
    -- 길이 상한이 없으면 `(issuer, subject)` 유일 인덱스가 btree 항목 크기 한계(약 2.7KB)에 걸려
    -- SQLSTATE 54000으로 죽는다. 그 오류는 거부 값이 아니라 트랜잭션을 abort시키는 예외라
    -- 호출자의 작업까지 함께 사라진다. 한계보다 훨씬 이른 지점에 벽을 세워 그 경로를 열지 않는다.
    --
    -- 바이트로 센다. btree 한계가 바이트 단위이고 한글·이모지가 섞이면 문자 수와 크게 갈린다.
    -- 두 상한을 합쳐도 1KB가 되지 않아 인덱스 한계와 여유가 크다.
    --
    -- 하한 1이 빈 문자열을 함께 막는다. 공백만인 값은 애플리케이션이 거부하며
    -- (`AccountLifecycleService.isAcceptable`), 이 CHECK는 그 밖에서 행이 들어올 경우의 최종
    -- 방어다. 상한 값은 그쪽 상수와 한 쌍이라 한쪽만 바꾸지 않는다.
    CONSTRAINT identity_social_link_provider_length_check
        CHECK (octet_length(provider) BETWEEN 1 AND 64),
    CONSTRAINT identity_social_link_issuer_length_check
        CHECK (octet_length(issuer) BETWEEN 1 AND 512),
    CONSTRAINT identity_social_link_subject_length_check
        CHECK (octet_length(subject) BETWEEN 1 AND 512)
);

-- `(issuer, subject)`는 전역 유일이다. 이미 다른 계정에 연결된 식별자는 거부하며, 어느 계정인지
-- 밝히지 않는다. 애플리케이션이 먼저 막지만 최종 방어는 이 인덱스다.
CREATE UNIQUE INDEX identity_social_link_issuer_subject_key
    ON identity_social_link (issuer, subject);

-- 한 계정이 같은 제공자를 두 번 연결하지 않는다.
CREATE UNIQUE INDEX identity_social_link_user_provider_key
    ON identity_social_link (user_id, provider);
