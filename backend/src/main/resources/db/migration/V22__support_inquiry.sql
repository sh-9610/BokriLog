-- Story 3.6: 사용자 문의를 접수하고 영업일 3일 답변 목표를 추적한다(`SUPPORT-1.0`).
--
-- **영업일 달력은 버전 seed다(AC-3).** `support_calendar_year`가 한 달력 버전이 담은 연도를, `support_business_calendar`가
-- 그 연도의 공휴일을 담는다. 영업일은 `Asia/Seoul` 월~금에서 그 달력의 공휴일을 뺀 날이다. 답변 목표 계산이 담기지 않은
-- 연도의 날을 지나야 하면 목표를 정하지 않는다(`CALENDAR_PENDING`) — 공휴일이 없는 해로 추측하지 않는다. 지금 달력이 그
-- 연도를 담게 되면 다음 조회 트랜잭션이 그 미정 목표를 채운다(이미 정해진 목표는 소급 재계산하지 않는다). 다음 해 달력은
-- 그 해 월력요항이 발표된 뒤 **새 버전의 새 마이그레이션**으로 더한다(이 파일은 고치지 않는다, `DEVELOPMENT.md`).
--
-- 버전 `KR-HOLIDAY-2026.1`, 확인일 2026-10-10.
--   - 2026: 「관공서의 공휴일에 관한 규정」 2026-04 개정(노동절 신설·제헌절 재지정, 국가법령정보센터)과 인사혁신처 공지.
--     6/3 제9회 전국동시지방선거일을 포함한다.
--   - 2027: 우주항공청 2027년 월력요항. 설날은 한국 표준시 기준 음력으로 2/7(일)이고 연휴 2/6~2/8, 대체공휴일 2/9다
--     (중국 기준으로 2/6을 설날로 계산한 자료와 다르다).
--
-- **문의 행 하나가 접수 하나다(AC-1·AC-2).** 접수 시각(DB 시각)·불투명 reference·답변 목표 시각·달력 버전·시간대·상태를
-- 같은 INSERT로 쓴다. 멱등의 권위는 `(user_id, idempotency_key_digest)` 유일 색인이다 — 멱등 기록은 문의 행 자체이고 따로
-- 두지 않는다. 키 원문은 남기지 않는다(digest만).
--
-- **운영 처리(AC-5).** 상태는 `RECEIVED → IN_PROGRESS → ANSWERED → RESOLVED` 단조이고 `ANSWERED`를 건너뛸 수 없다 — 그
-- 판정은 운영 command(`update-inquiry`)가 원장 잠금 아래에서 하고, 아래 CHECK가 상태와 답변·시각의 짝을 강제한다. 첫
-- 답변 시각과 목표 달성 여부(`first_answered_at <= target_at`, 목표 미정이면 NULL)도 CHECK가 같은 식으로 묶는다.
--
-- **분류와 보존(AC-6).** 문의 행은 "사용자 귀속 데이터"다(제목·본문·회신 이메일·답변). 해결 뒤 90일이 지난 행은 사용자·운영자
-- 조회 트랜잭션의 시작에서 지운다(백그라운드 없음, DB 시각). 계정 삭제는 deletion port(`OperationsDeletionApi.eraseInquiries`)
-- 가 그 사용자의 행을 지운다. 법정 분리 보존은 외부 승인(G-5) 전이라 없다(개발·CI 기본값). 다른 운영 표처럼 계정 표를
-- 외래키로 가리키지 않는다 — operations는 identity 소유 표에 닿지 않는다.
--
-- expand만 한다(AD-12). V1~V21은 고치지 않는다.

CREATE TABLE support_calendar_year (
    calendar_version text    NOT NULL,
    year             integer NOT NULL,

    CONSTRAINT support_calendar_year_pkey PRIMARY KEY (calendar_version, year),
    CONSTRAINT support_calendar_year_version_check CHECK (calendar_version ~ '^[A-Z]{2}-HOLIDAY-[0-9]{4}\.[0-9]+$'),
    CONSTRAINT support_calendar_year_year_check CHECK (year BETWEEN 2000 AND 2100)
);

CREATE TABLE support_business_calendar (
    calendar_version text    NOT NULL,
    year             integer NOT NULL,
    holiday_date     date    NOT NULL,
    name             text    NOT NULL,

    CONSTRAINT support_business_calendar_pkey PRIMARY KEY (calendar_version, holiday_date),
    CONSTRAINT support_business_calendar_year_fk
        FOREIGN KEY (calendar_version, year) REFERENCES support_calendar_year (calendar_version, year),
    CONSTRAINT support_business_calendar_year_check CHECK (extract(year FROM holiday_date) = year),
    CONSTRAINT support_business_calendar_name_check CHECK (char_length(name) BETWEEN 1 AND 40)
);

INSERT INTO support_calendar_year (calendar_version, year) VALUES
    ('KR-HOLIDAY-2026.1', 2026),
    ('KR-HOLIDAY-2026.1', 2027);

INSERT INTO support_business_calendar (calendar_version, year, holiday_date, name) VALUES
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-01-01', '신정'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-02-16', '설날 연휴'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-02-17', '설날'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-02-18', '설날 연휴'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-03-01', '삼일절'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-03-02', '대체공휴일(삼일절)'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-05-01', '노동절'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-05-05', '어린이날'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-05-24', '부처님오신날'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-05-25', '대체공휴일(부처님오신날)'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-06-03', '전국동시지방선거일'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-06-06', '현충일'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-07-17', '제헌절'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-08-15', '광복절'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-08-17', '대체공휴일(광복절)'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-09-24', '추석 연휴'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-09-25', '추석'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-09-26', '추석 연휴'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-10-03', '개천절'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-10-05', '대체공휴일(개천절)'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-10-09', '한글날'),
    ('KR-HOLIDAY-2026.1', 2026, DATE '2026-12-25', '성탄절'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-01-01', '신정'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-02-06', '설날 연휴'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-02-07', '설날'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-02-08', '설날 연휴'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-02-09', '대체공휴일(설날)'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-03-01', '삼일절'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-05-01', '노동절'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-05-03', '대체공휴일(노동절)'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-05-05', '어린이날'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-05-13', '부처님오신날'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-06-06', '현충일'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-07-17', '제헌절'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-07-19', '대체공휴일(제헌절)'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-08-15', '광복절'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-08-16', '대체공휴일(광복절)'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-09-14', '추석 연휴'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-09-15', '추석'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-09-16', '추석 연휴'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-10-03', '개천절'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-10-04', '대체공휴일(개천절)'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-10-09', '한글날'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-10-11', '대체공휴일(한글날)'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-12-25', '성탄절'),
    ('KR-HOLIDAY-2026.1', 2027, DATE '2027-12-27', '대체공휴일(성탄절)');

CREATE TABLE support_inquiry (
    inquiry_id             uuid        PRIMARY KEY,
    user_id                uuid        NOT NULL,
    -- 사람이 읽는 불투명 참조. `Q-` + base32 난수 10자이고 사용자·시각·순번을 복원할 수 없다.
    reference              text        NOT NULL,
    category               text        NOT NULL,
    title                  text        NOT NULL,
    description            text        NOT NULL,
    contact                text        NOT NULL,
    -- 사용자가 이메일 회신을 고른 때만 있다. 실제 발송은 없다(Ask First).
    reply_email            text,
    idempotency_key_digest text        NOT NULL,
    -- 같은 키의 다른 입력을 가르는 입력 전체의 SHA-256.
    request_digest         text        NOT NULL,
    received_at            timestamptz NOT NULL,
    -- 접수 다음 영업일부터 세 번째 영업일 18:00 KST. 달력이 그 연도를 담지 않았으면 NULL이다.
    target_at              timestamptz,
    target_state           text        NOT NULL,
    calendar_version       text        NOT NULL,
    timezone               text        NOT NULL,
    status                 text        NOT NULL,
    answer                 text,
    first_answered_at      timestamptz,
    target_met             boolean,
    resolved_at            timestamptz,
    -- 마지막 운영 처리의 operation(원장). 접수 뒤 처리 전에는 NULL이다.
    last_operation_id      text,

    CONSTRAINT support_inquiry_reference_key UNIQUE (reference),
    CONSTRAINT support_inquiry_idempotency_key UNIQUE (user_id, idempotency_key_digest),
    CONSTRAINT support_inquiry_reference_check CHECK (reference ~ '^Q-[A-Z2-7]{10}$'),
    CONSTRAINT support_inquiry_category_check
        CHECK (category IN ('ACCOUNT', 'CONNECTION', 'SYNC_PLAN', 'SERVICE_NOTICE', 'OTHER')),
    CONSTRAINT support_inquiry_title_check CHECK (char_length(title) BETWEEN 1 AND 80),
    CONSTRAINT support_inquiry_description_check CHECK (char_length(description) BETWEEN 1 AND 2000),
    CONSTRAINT support_inquiry_contact_check CHECK (contact IN ('IN_APP', 'EMAIL')),
    CONSTRAINT support_inquiry_reply_email_check
        CHECK ((contact = 'EMAIL') = (reply_email IS NOT NULL)
            AND (reply_email IS NULL OR (char_length(reply_email) BETWEEN 3 AND 254
                AND reply_email ~ '^[^[:space:]@<>"]+@[^[:space:]@<>"]+$'))),
    CONSTRAINT support_inquiry_digest_check
        CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$' AND request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT support_inquiry_target_check
        CHECK ((target_state = 'SET' AND target_at IS NOT NULL AND target_at > received_at)
            OR (target_state = 'CALENDAR_PENDING' AND target_at IS NULL)),
    CONSTRAINT support_inquiry_calendar_version_check
        CHECK (calendar_version ~ '^[A-Z]{2}-HOLIDAY-[0-9]{4}\.[0-9]+$'),
    CONSTRAINT support_inquiry_timezone_check CHECK (timezone = 'Asia/Seoul'),
    CONSTRAINT support_inquiry_status_check CHECK (status IN ('RECEIVED', 'IN_PROGRESS', 'ANSWERED', 'RESOLVED')),
    -- 답변과 첫 답변 시각은 `ANSWERED`부터 있고 그 전에는 없다.
    CONSTRAINT support_inquiry_answer_check
        CHECK ((status IN ('ANSWERED', 'RESOLVED')) = (answer IS NOT NULL AND first_answered_at IS NOT NULL)
            AND (answer IS NULL OR char_length(answer) BETWEEN 1 AND 1000)
            AND (first_answered_at IS NULL OR first_answered_at >= received_at)),
    -- 목표 달성 여부는 첫 답변 시각과 목표 시각의 비교 그 자체다. 목표 미정이거나 답변 전이면 NULL이다.
    CONSTRAINT support_inquiry_target_met_check
        CHECK ((first_answered_at IS NULL OR target_at IS NULL) = (target_met IS NULL)
            AND (target_met IS NULL OR target_met = (first_answered_at <= target_at))),
    CONSTRAINT support_inquiry_resolved_check
        CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL)
            AND (resolved_at IS NULL OR resolved_at >= first_answered_at)),
    CONSTRAINT support_inquiry_last_operation_check
        CHECK (last_operation_id IS NULL OR octet_length(last_operation_id) BETWEEN 1 AND 64)
);

-- 사용자 목록과 24시간 접수 상한(DB 시각).
CREATE INDEX support_inquiry_user_received_idx ON support_inquiry (user_id, received_at DESC);
-- 운영자 목록(최근 순)과 기간 집계.
CREATE INDEX support_inquiry_received_idx ON support_inquiry (received_at DESC);
CREATE INDEX support_inquiry_target_idx ON support_inquiry (target_at) WHERE target_at IS NOT NULL;
-- 달력이 새 연도를 담게 됐을 때 채울 미정 목표(조회 트랜잭션이 채운다).
CREATE INDEX support_inquiry_pending_idx ON support_inquiry (received_at) WHERE target_state = 'CALENDAR_PENDING';
-- 해결 뒤 90일 보존 삭제.
CREATE INDEX support_inquiry_resolved_idx ON support_inquiry (resolved_at) WHERE status = 'RESOLVED';
