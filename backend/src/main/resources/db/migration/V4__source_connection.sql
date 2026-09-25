-- 읽기 전용 Bybit 연결과 그것이 올라서는 portfolio·운영 제한 스키마(Story 2.1).
--
-- 여섯 테이블이 세 모듈로 갈린다. `source_*`는 제공자 계정·연결·암호화된 자격정보를,
-- `portfolio`·`portfolio_source_membership`은 기준통화 묶음과 활성 source 구간을,
-- `operations_restriction`은 운영 제한의 durable 판독 기반을 소유한다. 접두사가 곧 소유 모듈이며
-- (`V1`의 `identity_` 관용) 다른 모듈은 그 테이블에 직접 닿지 않는다(`ModuleBoundaryTest`).
--
-- **한 개 규칙을 애플리케이션이 세지 않는다.** 사용자당 활성 Bybit 연결 하나, portfolio당 활성
-- source 하나, 사용자당 `USD` 기본 portfolio 하나는 모두 아래 조건부 유일 색인이 강제한다.
-- "먼저 세고 나중에 넣는" 형태는 두 문장 사이에 다른 요청이 들어오면 둘 다 통과하고, 그 순간
-- 한 사용자에게 활성 연결이 둘 생긴다(`V3` 머리말과 Story 1.11이 같은 근거를 적었다).
--
-- **`ON DELETE RESTRICT`로 되돌아간다.** `V3`의 `action_grant`는 CASCADE였고 근거는 그 데이터가
-- "5분짜리 파생 상태이고 `identity`가 보존을 판단할 payload가 아니다"였다. 여기 있는 것은 반대다 —
-- 자격정보·연결·portfolio는 사용자의 payload이고, 삭제 경로가 그것을 지웠는지를 조용한 부수 효과가
-- 아니라 소리 나는 실패로 확인해야 한다(`V1`의 선택과 같다). 그래서 `identity_account`를 가리키는
-- 세 외래키가 RESTRICT이고, 계정 payload 삭제 앞에 `source`·`portfolio`의 삭제 경로가 반드시 돈다.
-- 잊으면 `erasePayload`가 외래키 위반으로 실패하며 부분 삭제가 완료로 보고되지 않는다.
--
-- 모듈 **안**의 자식은 CASCADE다. `source_credential`은 `source_connection` 없이는 뜻이 없고
-- `portfolio_source_membership`은 두 부모 없이는 뜻이 없다. 그 정리까지 손으로 적게 만들면 삭제
-- 경로가 자기 모듈의 내부 구조를 문장으로 다시 나열하는 형태가 된다.
--
-- 지갑 응답 원문·평문 자격정보·마스킹 값을 담을 컬럼이 없다. 받지 않으므로 저장할 수도 없다는
-- 것이 `V1`이 이메일 컬럼을 두지 않은 것과 같은 규율이다.
--
-- expand 우선이다(AD-12). 이 마이그레이션은 만들기만 하고 아무것도 지우거나 좁히지 않는다.
-- `V1`~`V3`은 이미 적용됐으므로 고치지 않는다. 적용은 migrate 역할 하나뿐이고 API 기동은
-- `Flyway::validate`만 한다(`SchemaValidationConfig`).

-- 제공자 계정. **안정 식별자**이며 자격정보·connection 교체에도 재사용한다.
--
-- `provider_account_ref`는 검증된 메인 UID다. connection ID는 수집 provenance일 뿐 사건 고유키가
-- 아니므로(epic-2-context), 이후 에픽의 원천 사건은 이 행을 가리킨다.
CREATE TABLE source_provider_account (
    provider_account_id  uuid        PRIMARY KEY,
    user_id              uuid        NOT NULL
                                     REFERENCES identity_account (user_id) ON DELETE RESTRICT,
    -- 닫힌 집합이다. 모르는 값은 거부한다(`AccountState.parse`와 같은 규율).
    provider             text        NOT NULL,
    -- 제공자가 준 메인 계정 식별자. Bybit에서는 검증된 main UID다.
    provider_account_ref text        NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT source_provider_account_provider_check
        CHECK (provider IN ('BYBIT')),
    -- 길이 상한은 애플리케이션 상수와 한 쌍이다. 바이트로 센다(`V1`과 같은 관용).
    CONSTRAINT source_provider_account_ref_length_check
        CHECK (octet_length(provider_account_ref) BETWEEN 1 AND 128)
);

-- 같은 제공자의 같은 계정은 전역에 하나다.
--
-- 사용자 축이 **없는** 것이 요점이다. 이미 다른 BokriLog 계정에 붙은 메인 UID를 두 번째 계정이
-- 연결하면 두 계정이 같은 자산을 각자의 계획 기준으로 삼는다. 애플리케이션이 먼저 조회해 거절하지만
-- 최종 방어는 이 인덱스이고, 거부 응답은 상대 계정도 제공자도 드러내지 않는다.
CREATE UNIQUE INDEX source_provider_account_provider_ref_key
    ON source_provider_account (provider, provider_account_ref);

CREATE INDEX source_provider_account_user_id_idx ON source_provider_account (user_id);

-- 연결 하나. 검증을 전부 통과한 자격정보 묶음의 수명을 든다.
CREATE TABLE source_connection (
    connection_id        uuid        PRIMARY KEY,
    user_id              uuid        NOT NULL
                                     REFERENCES identity_account (user_id) ON DELETE RESTRICT,
    provider_account_id  uuid        NOT NULL
                                     REFERENCES source_provider_account (provider_account_id)
                                     ON DELETE RESTRICT,
    provider             text        NOT NULL,
    -- ACTIVE -> REVOKED 단조 전진뿐이다. 해제는 Story 2.16이 소유하며 이 스토리는 ACTIVE만 만든다.
    status               text        NOT NULL,
    -- admission이 통과한 시각. 화면이 "검증 시각"으로 보여 준다.
    verified_at          timestamptz NOT NULL,
    -- 그때 적용된 연결 정책의 버전. 정책이 바뀌면 이미 저장된 연결이 어느 규칙을 지났는지 남는다.
    policy_version       text        NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT source_connection_provider_check
        CHECK (provider IN ('BYBIT')),
    CONSTRAINT source_connection_status_check
        CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT source_connection_policy_version_length_check
        CHECK (octet_length(policy_version) BETWEEN 1 AND 64)
);

-- 사용자당 활성 연결은 하나다. 부분 색인이라 해제된 연결은 몇 개든 남을 수 있다.
CREATE UNIQUE INDEX source_connection_active_user_key
    ON source_connection (user_id)
    WHERE status = 'ACTIVE';

CREATE INDEX source_connection_user_id_idx ON source_connection (user_id);

CREATE INDEX source_connection_provider_account_id_idx
    ON source_connection (provider_account_id);

-- 암호화된 자격정보. **평문 컬럼이 없다.**
--
-- `nonce`는 매 암호화마다 새로 만든 96비트 CSPRNG 값이고, AAD는
-- `user_id/connection_id/credential_id/key_version/algorithm_version` 다섯을 잇는다. 그 다섯이
-- 암호문에 묶여 있으므로 다른 연결의 암호문을 이 행에 옮겨 붙여도 복호가 성립하지 않는다.
--
-- 키는 DB·Git·로그 밖에서 주입한다. 여기 있는 것은 어느 버전으로 암호화됐는지뿐이며 그 값만으로는
-- 복호할 수 없다(`CredentialCipher`).
CREATE TABLE source_credential (
    credential_id      uuid        PRIMARY KEY,
    connection_id      uuid        NOT NULL
                                   REFERENCES source_connection (connection_id) ON DELETE CASCADE,
    -- active key version. 과거 키는 decrypt-only로 남아 회전이 재암호화를 강제하지 않는다.
    key_version        text        NOT NULL,
    -- 암호 스킴의 버전. 알고리즘이 바뀌면 AAD가 달라지므로 이 값도 AAD에 든다.
    algorithm_version  text        NOT NULL,
    nonce              bytea       NOT NULL,
    ciphertext         bytea       NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    -- 96비트다. GCM의 권장 nonce 길이이며 그보다 길면 구현이 내부에서 해시해 충돌 확률이 달라진다.
    CONSTRAINT source_credential_nonce_length_check
        CHECK (octet_length(nonce) = 12),
    CONSTRAINT source_credential_ciphertext_length_check
        CHECK (octet_length(ciphertext) BETWEEN 1 AND 8192),
    CONSTRAINT source_credential_key_version_length_check
        CHECK (octet_length(key_version) BETWEEN 1 AND 64),
    CONSTRAINT source_credential_algorithm_version_length_check
        CHECK (octet_length(algorithm_version) BETWEEN 1 AND 64)
);

-- 연결 하나에 자격정보 하나다. 교체는 새 연결이며 반쪽 상태를 남기지 않는다.
CREATE UNIQUE INDEX source_credential_connection_key ON source_credential (connection_id);

-- 기준통화 묶음. MVP는 사용자당 자동 생성 `USD` 기본 portfolio 하나뿐이다.
CREATE TABLE portfolio (
    portfolio_id   uuid        PRIMARY KEY,
    user_id        uuid        NOT NULL
                               REFERENCES identity_account (user_id) ON DELETE RESTRICT,
    -- 기준통화 변경은 별도 승인 전까지 없다. 닫힌 집합이 그것을 스키마에 적는다.
    base_currency  text        NOT NULL,
    is_default     boolean     NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT portfolio_base_currency_check CHECK (base_currency IN ('USD'))
);

-- 사용자당 기준통화별 기본 portfolio는 하나다. 재연결이 두 번째를 만들지 않는다.
CREATE UNIQUE INDEX portfolio_user_default_currency_key
    ON portfolio (user_id, base_currency)
    WHERE is_default;

CREATE INDEX portfolio_user_id_idx ON portfolio (user_id);

-- portfolio에 붙은 source 구간. UTC 반개구간이라 겹칠 수 없다(epic-2-context).
CREATE TABLE portfolio_source_membership (
    membership_id        uuid        PRIMARY KEY,
    portfolio_id         uuid        NOT NULL
                                     REFERENCES portfolio (portfolio_id) ON DELETE CASCADE,
    connection_id        uuid        NOT NULL
                                     REFERENCES source_connection (connection_id) ON DELETE CASCADE,
    -- 사건의 귀속은 connection이 아니라 provider account다. 그래서 이 열이 여기 있다 —
    -- 자격정보를 교체해 connection이 바뀌어도 같은 값을 가리킨다.
    provider_account_id  uuid        NOT NULL
                                     REFERENCES source_provider_account (provider_account_id)
                                     ON DELETE CASCADE,
    started_at           timestamptz NOT NULL,
    -- `NULL`이 곧 활성이다. 구간을 닫는 것은 Story 2.16이다.
    ended_at             timestamptz,
    CONSTRAINT portfolio_source_membership_interval_check
        CHECK (ended_at IS NULL OR ended_at > started_at)
);

-- portfolio당 활성 source는 하나다. 다중 source 합산은 별도 승인 전까지 없다.
CREATE UNIQUE INDEX portfolio_source_membership_active_key
    ON portfolio_source_membership (portfolio_id)
    WHERE ended_at IS NULL;

CREATE INDEX portfolio_source_membership_connection_id_idx
    ON portfolio_source_membership (connection_id);

CREATE INDEX portfolio_source_membership_provider_account_id_idx
    ON portfolio_source_membership (provider_account_id);

-- 운영 제한의 durable 판독 기반.
--
-- **이 스토리는 읽기만 한다.** 제한을 만들고 푸는 운영자 표면은 Epic 3이 얹는다(Story 3.1·3.2).
-- 그럼에도 테이블이 지금 생기는 이유는 Epic 2의 독립 완료 조건이 "검사 지점을 뒤로 미루지 않는
-- 것"이기 때문이다 — admission이 fail-closed로 이 행을 읽고, 없으면 제한이 없는 것이다.
--
-- 사용자 축이 없다. MVP의 제한은 기능 전체를 멈추는 운영 조치이며, 사용자별 제한은 그것을
-- 요구하는 스토리가 열을 더해 연다(expand 우선).
CREATE TABLE operations_restriction (
    restriction_id   uuid        PRIMARY KEY,
    -- 막을 command의 이름. 허가의 `command`와 같은 문자열을 쓴다.
    command          text        NOT NULL,
    effective_from   timestamptz NOT NULL DEFAULT now(),
    -- `NULL`이면 해제될 때까지 유효하다.
    effective_until  timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT operations_restriction_command_length_check
        CHECK (octet_length(command) BETWEEN 1 AND 64),
    CONSTRAINT operations_restriction_interval_check
        CHECK (effective_until IS NULL OR effective_until > effective_from)
);

-- admission의 판독 술어다. 순차 검색이 되지 않게 한다.
CREATE INDEX operations_restriction_command_idx ON operations_restriction (command);
