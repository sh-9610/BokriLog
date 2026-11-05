-- Story 5.1: 사용자의 계정 삭제 요청(`ACCOUNT-DELETION-1.0`).
--
-- **행 하나가 요청 하나이고 사용자당 하나다.** 사용자가 설정 화면에서 삭제 범위·보존 정책을 확인하고 최근 재인증·일회성 허가·
-- 고정 문구 재입력을 거쳐 요청하면, 루트 `AccountDeletionRequestService`가 허가 소비와 같은 트랜잭션에서 identity 공개 API
-- (`AccountDeletionApi.requestDeletion`)를 부르고 그 안에서 생명주기 행 잠금 → 이 표의 INSERT → `ACTIVE → DELETION_IN_PROGRESS`가
-- 함께 커밋된다. 요청이 생긴 순간부터 그 계정의 새 세션·쓰기·민감 접근이 막힌다(기존 `DELETION_IN_PROGRESS` 거부). 이 스토리는
-- 삭제를 시작만 한다 — 모듈 데이터 삭제·tombstone·백업 파기는 Story 5.2·5.3이 이 행 위에 선다.
--
--   request_id             -- 불투명 요청 ID(서버가 만든다). tombstone outbox(V25)의 요청 ID와 같은 문자 집합이다.
--   user_id                -- 생명주기 guard 행을 가리킨다(payload가 아니라 guard다 — 삭제가 끝나도 남는 행).
--   requested_at           -- 요청 시각(DB `statement_timestamp()`).
--   policy_version         -- 사용자가 확인한 삭제 정책 버전(서버 상수 `AccountDeletionPolicy.VERSION`).
--   notice_hash            -- 사용자가 본 고지(정책 정규 직렬화)의 SHA-256.
--   idempotency_key_digest -- 확정 요청 멱등 키의 SHA-256. 키 원문은 남기지 않는다. 같은 키의 재전송은 이 행을 그대로 받는다.
--
-- **분류: 사용자 귀속 데이터(identity payload와 같은 운명).** 삭제 완료(`erasePayload`)가 payload·인증 매핑·세션과 함께 이 행을
-- 지우고, 복원 검증의 잔여 행 수(`remainingRowsOf`)가 이 행을 센다. 이름·이메일·금융값·자격정보가 없다. 법정 분리 보존 후보는
-- 외부 승인(G-5) 전이라 없다(개발·CI 기본값).
--
-- 애플리케이션의 SQL은 한 클래스(`IdentityAccountRepository`)에 있다. 문장은 INSERT·SELECT와 삭제 완료의 DELETE뿐이다(행을 고치는
-- 문장이 없다 — 상태는 생명주기 행이 소유한다).
--
-- expand만 한다(AD-12). V1~V29는 고치지 않는다.

CREATE TABLE identity_deletion_request (
    request_id             text        PRIMARY KEY,
    user_id                uuid        NOT NULL
                                       REFERENCES identity_account_lifecycle (user_id) ON DELETE RESTRICT,
    requested_at           timestamptz NOT NULL DEFAULT statement_timestamp(),
    policy_version         text        NOT NULL,
    notice_hash            text        NOT NULL,
    idempotency_key_digest text        NOT NULL,

    -- 사용자당 요청 하나다. 동시 요청의 최종 방어이고, 애플리케이션은 생명주기 행 잠금 아래에서 먼저 본다.
    CONSTRAINT identity_deletion_request_user_key UNIQUE (user_id),
    CONSTRAINT identity_deletion_request_id_check CHECK (request_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    CONSTRAINT identity_deletion_request_policy_check
        CHECK (policy_version ~ '^ACCOUNT-DELETION-[0-9]+\.[0-9]+$'),
    CONSTRAINT identity_deletion_request_digest_check
        CHECK (notice_hash ~ '^[0-9a-f]{64}$' AND idempotency_key_digest ~ '^[0-9a-f]{64}$')
);
