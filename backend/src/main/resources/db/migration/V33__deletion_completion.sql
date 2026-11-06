-- Story 5.4: 삭제 계정이 복원 후 재활성화되지 않음을 검증하고 완료를 안내한다.
--
-- 세 가지를 더한다. 모두 identity 소유이고 사용자 열이 없다(`identity_deletion_run`의 기존 열만 사용자 행을 가리킨다).
--
-- 1. **재가입 차단 digest**(`identity_deleted_identity`). 삭제 실행이 소셜 로그인 연결을 지우는 트랜잭션(`CREDENTIALS` 단계 — 단계 모드·
--    단일 모드·복원 재삭제 모두)이 지우기 전에 그 `(issuer, subject)`의 keyed digest를 남긴다. 키는 API가 이미 가진 감사 digest 키에 고유
--    접두사(`deleted-identity:`)를 붙인 HMAC-SHA256이고, identity는 키를 갖지 않는다(루트가 구현하는 port가 계산한다). 원 issuer·subject는
--    남기지 않는다. 그 digest의 로그인은 계정을 만들지 않고(표지 `deleted`) 다른 계정의 연결도 막힌다(매핑 INSERT 술어). 해제 경로는
--    없다 — 신규 가입 허용 여부는 G-5가 정한다. production 삭제가 G-5 전에는 열리지 않으므로 이 표는 지금 개발·CI에만 행이 생긴다.
--
--      identity_digest -- 소문자 16진 64자. 계정·사용자 ID를 가리키지 않는다.
--      request_id      -- 그 매핑을 지운 단계 모드 삭제 실행의 요청 ID(V31·V32 `identity_deletion_run.request_id`). 로그인 표지
--                         `deleted`는 그 실행의 계정이 `DELETED`일 때만 나간다 — `CREDENTIALS`가 기록한 뒤 뒤 단계가 재시도 필요로 멈춘
--                         진행 중 계정은 `failed`다. 단일 모드(리허설 표본·복원 재삭제 — 한 트랜잭션이라 커밋되면 `DELETED`다)와 실행
--                         없는 삭제는 비어 있다. 사용자 ID가 아니다(삭제 요청의 불투명 ID — tombstone·완료 세션과 같은 값).
--      recorded_at     -- 처음 기록한 시각.
--
--    **분류: 삭제 기록(생명주기 행과 같은 운명).** 계정 삭제가 끝나도 남는 것이 계약이다. 사용자 열이 없으므로 잔여 행 세기
--    (`remainingRowsOf`)에 들지 않는다.
--
-- 2. **완료 확인 전용 세션**(`identity_deletion_completion_session`). 마지막 단계(`IDENTITY`)가 요청 세션(삭제를 확정한 세션 — 이
--    마이그레이션이 V30 요청 행에 더하는 `session_primary_id`가 그 세션의 `spring_session.primary_id`다)을 지우지 않고 같은 커밋에서
--    `principal_name`과 속성(인증 문맥·흐름 무장 — CSRF 토큰과 진행 중인 OAuth2 인가 요청만 남긴다)을 걷어낸 뒤 이 표에 삭제 요청 ID
--    하나로 잇는다. 그 뒤 어떤 `spring_session` 행도 그 사용자 ID를 담지 않는다(잔여 0). 그 세션은 완료 조회와 로그아웃만 하고 나머지는 미인증과
--    같다. 수명은 그 세션 행의 수명(유휴 30분·절대 12시간)이고 세션 행이 지워지면 함께 지워진다(cascade).
--
--      session_primary_id -- `spring_session.primary_id`(세션 ID 회전에도 바뀌지 않는 키).
--      request_id         -- 삭제 요청 ID. 완료 조회가 이 값으로 실행 행(운영 완료·전체 완료)과 tombstone(백업 파기)을 읽는다.
--
-- 3. **전체 삭제 완료**(`identity_deletion_run.status = 'DELETION_COMPLETED'`·`deletion_completed_at`). 운영 데이터 삭제 완료
--    (`OPERATIONAL_DATA_ERASED`)에 그 요청의 백업 파기 완료(tombstone `DESTROYED`)와 법정 분리 보존 처리(목록이 비면 충족)가 더해졌을 때만
--    루트 판정자(`BackupDestructionJudge`)가 기록한다. 단조이고 멱등이다. 운영 완료 시각(`operational_erased_at`)은 그대로 남는다.
--
-- 그리고 요청 행(V30)에 요청 세션의 `spring_session.primary_id`(`session_primary_id`)를 더한다 — 확정이 같은 트랜잭션에서 그 세션 행을
-- 찾아 쓴다. primary_id는 Spring Session의 내부 행 키이고 쿠키에 실리는 세션 ID(`session_id`, bearer)가 아니다 — 그 값으로는 아무 요청도
-- 인증할 수 없어 자격정보가 아니다. 세션 ID 회전(`changeSessionId`)은 `session_id`만 바꾸고 primary_id는 그대로라 회전에 강하다. 외래키는
-- 두지 않는다(로그아웃·만료로 세션 행이 먼저 사라져도 요청 행은 남는다 — 그때 마지막 단계는 바꿀 세션이 없다). 이 마이그레이션 전의 요청은
-- 비어 있고, 그 계정의 마지막 단계는 지금처럼 세션을 모두 지운다.
--
-- 애플리케이션의 SQL은 한 클래스(`IdentityAccountRepository`)에 있다. expand만 한다(AD-12). V1~V32는 고치지 않는다.

CREATE TABLE identity_deleted_identity (
    identity_digest text        PRIMARY KEY,
    request_id      text,
    recorded_at     timestamptz NOT NULL DEFAULT statement_timestamp(),

    CONSTRAINT identity_deleted_identity_digest_check CHECK (identity_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT identity_deleted_identity_request_check
        CHECK (request_id IS NULL OR request_id ~ '^[A-Za-z0-9_-]{1,64}$')
);

CREATE TABLE identity_deletion_completion_session (
    session_primary_id char(36) PRIMARY KEY
                                REFERENCES spring_session (primary_id) ON DELETE CASCADE,
    request_id         text     NOT NULL,

    CONSTRAINT identity_deletion_completion_session_request_check CHECK (request_id ~ '^[A-Za-z0-9_-]{1,64}$')
);

ALTER TABLE identity_deletion_request
    ADD COLUMN session_primary_id text,
    ADD CONSTRAINT identity_deletion_request_session_check
        CHECK (session_primary_id IS NULL OR session_primary_id ~ '^[0-9a-f-]{36}$');

ALTER TABLE identity_deletion_run
    ADD COLUMN deletion_completed_at timestamptz,
    DROP CONSTRAINT identity_deletion_run_status_check,
    ADD CONSTRAINT identity_deletion_run_status_check
        CHECK (status IN ('QUEUED', 'RUNNING', 'RETRY_NEEDED', 'OPERATIONAL_DATA_ERASED', 'DELETION_COMPLETED')),
    DROP CONSTRAINT identity_deletion_run_erased_check,
    -- 운영 완료 시각은 운영 완료와 전체 완료 둘 다에 있다(전체 완료는 운영 완료 위에 선다).
    ADD CONSTRAINT identity_deletion_run_erased_check
        CHECK ((status IN ('OPERATIONAL_DATA_ERASED', 'DELETION_COMPLETED')) = (operational_erased_at IS NOT NULL)),
    -- 전체 완료 시각은 그 상태와 한 쌍이고 운영 완료보다 늦다.
    ADD CONSTRAINT identity_deletion_run_completed_check
        CHECK ((status = 'DELETION_COMPLETED') = (deletion_completed_at IS NOT NULL)
            AND (deletion_completed_at IS NULL OR deletion_completed_at >= operational_erased_at));
