-- 민감 작업의 5분·일회성 재인증 허가(`SECURITY-ACTION-1.0`).
--
-- 허가 하나가 user·session·command·target·canonical confirmation hash 다섯에 귀속하고 정확히 한 번
-- 소비된다. 다섯 중 하나라도 어긋나면 소비되지 않으며, 거부는 호출자에게 하나의 값이다.
--
-- **시각의 권위는 이 데이터베이스 하나다.** 발급이 `expires_at`을 절대 시각으로 못 박고, 만료
-- 판정은 소비 UPDATE의 술어(`now() < expires_at`)가 한다. 애플리케이션 시계로 판정하지 않는
-- 이유는 셋이다 — 인스턴스마다 값이 다르고, 뒤로 갈 수 있으며, 판정과 소비가 두 문장으로 갈리면
-- 그 사이가 곧 동시 소비의 창이다. `issued_at + 5분`을 소비 시점에 다시 계산하지 않는 것도 같은
-- 이유다. 서버 시각이 뒤로 가는 순간 수명이 늘어나는 경로를 아예 만들지 않는다.
--
-- `grant_id`가 브라우저에 나가는 유일한 값이며 뜻을 담지 않는다. 난수 32바이트의 base64url
-- 표현이라 사용자·작업·대상·시각을 그 값에서 복원할 수 없다(`ActionGrant.newReference`).
--
-- 루트 패키지가 처음으로 자기 테이블을 갖는다. 허가는 계정 소유권도 삭제 상태도 아니고
-- **세션과 작업**에 귀속하므로 `identity`에 둘 수 없다 — 그 모듈은 서블릿·HTTP 의존이 금지되어
-- (`ModuleBoundaryTest`) 세션 개념을 가질 수 없고 `AccessDecision`은 두 값뿐이라 표현할 자리도
-- 없다. 대신 아래 외래키 하나로 스키마에 루트 → `identity` 방향 의존을 진다. 그 대가로 삭제
-- 경로가 이 테이블을 알 필요가 없다.
--
-- **그 외래키가 `V1`의 관용을 뒤집는다.** `V1`의 자식 테이블 둘은 `ON DELETE RESTRICT`이고 그
-- 선택의 뜻은 "잊은 자식이 있으면 시끄럽게 실패한다"였다 — 삭제 경로가 지워야 할 것을 손으로 다
-- 적게 만들고, 부분 삭제를 완료로 보고하지 않기 위해서다. 이 테이블은 반대로 `ON DELETE CASCADE`다.
-- 근거는 이 데이터의 성질이다. 허가는 5분짜리 파생 상태이고 `identity`가 보존을 판단할 payload가
-- 아니며, RESTRICT로 두면 `AccountDeletionService.erasePayload`가 이 테이블을 이름으로 알아야 한다
-- (그것은 `identity`가 루트 패키지의 테이블을 아는 방향의 결합이고 모듈 경계가 막으려는 바로 그
-- 모양이다). SPEC의 Ask First가 이 교환을 사람에게 물어 승인받았다. 값은 `V1`과 달라도 규칙은
-- 같다 — 삭제 경로는 여전히 부분 상태를 남기지 않는다.
--
-- expand 우선이다(AD-12). 이 마이그레이션은 만들기만 하고 아무것도 지우거나 좁히지 않는다.
-- `V1`·`V2`는 이미 적용됐으므로 고치지 않는다. 적용은 migrate 역할 하나뿐이고 API 기동은
-- `Flyway::validate`만 한다(`SchemaValidationConfig`).

CREATE TABLE action_grant (
    -- 불투명 참조이자 기본키. 값의 생산자는 `ActionGrant.newReference` 하나다.
    grant_id          text        PRIMARY KEY,
    -- `identity_account`(payload)를 참조한다. `AccountDeletionApi.erasePayload`가 그 행을 지우면
    -- 이 계정의 허가도 함께 사라지고, 삭제 경로는 이 테이블의 존재를 알 필요가 없다. guard
    -- 테이블이 아니라 payload 테이블을 가리키는 이유가 그것이다 — guard 행은 삭제 뒤에도 남는다.
    user_id           uuid        NOT NULL
                                  REFERENCES identity_account (user_id) ON DELETE CASCADE,
    -- 발급 시점 세션의 id. 로그아웃·만료·회전으로 id가 달라지면 그 세션에 귀속된 허가는 소비되지
    -- 않는다. 값은 서버 안에만 있으며 응답·로그 어디에도 나가지 않는다.
    session_id        text        NOT NULL,
    -- command·target의 뜻은 소비자가 소유한다. 이 스키마도 애플리케이션 포트도 문자열을 해석하지
    -- 않으며 닫힌 집합을 여기서 정하지 않는다.
    command           text        NOT NULL,
    target            text        NOT NULL,
    -- 확인 문구의 정규화된 해시. 원문을 저장하지 않는다.
    confirmation_hash text        NOT NULL,
    issued_at         timestamptz NOT NULL DEFAULT now(),
    -- 발급이 못 박는 절대 시각. 아무도 다시 계산하지 않는다.
    expires_at        timestamptz NOT NULL,
    -- 소비 시각. `NULL`이 곧 미소비이며 소비 UPDATE의 술어가 그 값을 본다.
    consumed_at       timestamptz,
    -- 멱등의 권위는 아래 유일 인덱스 하나다. 애플리케이션이 "이미 있나?"를 먼저 조회하는 형태는
    -- 동시 재전송에서 둘 다 통과하므로, 인덱스가 두 번째를 거절하게 두고 그 충돌을 "이미 성공한
    -- 실행"으로 읽는다.
    operation_id      text,
    -- 저장된 결과. 같은 `operation_id` 재전송이 업무 변경을 다시 실행하지 않고 이 값을 돌려준다.
    result            text,

    -- 길이 상한은 `ActionGrant`의 상수와 한 쌍이다. 애플리케이션이 먼저 값으로 거부하고 이 CHECK는
    -- 그 밖에서 행이 들어올 경우의 최종 방어다. 바이트로 센다(`V1`과 같은 관용).
    CONSTRAINT action_grant_grant_id_length_check
        CHECK (octet_length(grant_id) BETWEEN 32 AND 128),
    CONSTRAINT action_grant_session_id_length_check
        CHECK (octet_length(session_id) BETWEEN 1 AND 128),
    CONSTRAINT action_grant_command_length_check
        CHECK (octet_length(command) BETWEEN 1 AND 64),
    CONSTRAINT action_grant_target_length_check
        CHECK (octet_length(target) BETWEEN 1 AND 256),
    -- SHA-256의 16진 표현은 언제나 64바이트다. 생산자가 하나이므로 상한이 아니라 등식이다.
    CONSTRAINT action_grant_confirmation_hash_length_check
        CHECK (octet_length(confirmation_hash) = 64),
    CONSTRAINT action_grant_operation_id_length_check
        CHECK (operation_id IS NULL OR octet_length(operation_id) BETWEEN 1 AND 128),
    CONSTRAINT action_grant_result_length_check
        CHECK (result IS NULL OR octet_length(result) <= 4096),

    -- 소비와 `operation_id`는 한 문장이 함께 쓴다. 한쪽만 있는 행은 계약이 깨진 상태다.
    CONSTRAINT action_grant_consumption_pair_check
        CHECK ((consumed_at IS NULL) = (operation_id IS NULL)),
    -- 결과는 소비된 허가에만 붙는다.
    CONSTRAINT action_grant_result_requires_consumption_check
        CHECK (result IS NULL OR consumed_at IS NOT NULL),
    -- 발급이 수명을 못 박는다는 불변식을 스키마에도 적는다. 이 관계가 깨진 행은 만료 판정이
    -- 무슨 값을 보든 뜻을 잃는다.
    CONSTRAINT action_grant_expiry_after_issue_check
        CHECK (expires_at > issued_at)
);

-- 멱등의 권위.
--
-- **사용자 축이 함께 들어간다.** `operation_id`는 소비자가 정하는 값이고 추측 가능한 모양
-- (`link-naver-1`)일 수 있다. 전역 유일이면 먼저 쓴 사용자가 다른 사용자의 그 값을 영구히 막고,
-- 그 선점은 되돌릴 방법이 없다. 사용자로 좁히면 의미는 그대로다 — 멱등은 "이 사용자의 이 실행"에
-- 대한 것이고, 저장된 결과를 읽는 술어도 이미 사용자로 좁혀 있다
-- (`ActionGrantRepository.findStoredResult`).
--
-- 부분 색인이다. 미소비 행은 `operation_id`가 `NULL`이라 색인에 들어갈 이유가 없다. PostgreSQL의
-- 유일 색인이 여러 `NULL`을 허용하므로 없어도 동작은 같지만, 색인이 자기 술어보다 넓게 만들어지지
-- 않게 한다.
CREATE UNIQUE INDEX action_grant_user_operation_key
    ON action_grant (user_id, operation_id)
    WHERE operation_id IS NOT NULL;

-- 위 외래키의 `ON DELETE CASCADE`는 참조하는 쪽을 훑는다. 색인이 없으면 계정 하나를 지울 때마다
-- 이 테이블 전체를 순차 검색한다. 발급이 같은 귀속의 미소비 허가를 무효화하는 DELETE와 사용자
-- 범위 sweep도 이 색인을 쓴다.
CREATE INDEX action_grant_user_id_idx ON action_grant (user_id);

-- 만료 행 sweep(`ActionGrantRepository.deleteExpiredUnconsumedOf`)의 술어다. 스케줄러도 배치도 두지
-- 않고 발급 경로가 자기 사용자 범위에서 쓸어 내므로, 그 DELETE가 순차 검색이 되지 않게 한다.
-- 소비된 행은 sweep 대상이 아니므로 부분 색인이다.
CREATE INDEX action_grant_unconsumed_expires_at_idx
    ON action_grant (expires_at)
    WHERE consumed_at IS NULL;
