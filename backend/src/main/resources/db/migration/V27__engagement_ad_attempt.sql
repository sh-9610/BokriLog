-- Story 4.2: 수동 동기화 성공 결과에 광고 자격과 빈도 제한을 적용한다.
--
-- **결과 하나에 결정 하나.** 행 하나는 "그 사용자의 그 수동 동기화 결과에서 광고를 시도해도 되는가"의 서버 판정 하나다. 결과
-- 참조는 수동 동기화 attempt의 `job_id`(`sync_job_id`)이고, `(user_id, sync_job_id)` 유일 제약이 재전송을 새 시도로 만들지 않는
-- 권위다 — 같은 결과의 두 번째 요청은 행을 더하지 않고 빈도 제한도 소비하지 않는다. 다른 모듈 표처럼 sync 소유 표를 외래키로
-- 가리키지 않는다 — engagement는 sync 소유 표에 닿지 않는다(후보 판정은 `SyncJobApi.publishedManualSync`로 본다).
--
-- **ELIGIBLE이 곧 reservation이다.** 자격을 얻은 행은 노출 시도로 세어진다 — 30분 간격과 `Asia/Seoul` 하루 3회는 ELIGIBLE 행만
-- 센다. SKIPPED(비동의·운영 제한·공급자 비활성·간격·하루 상한)도 행으로 남긴다: 같은 결과의 재전송이 처음 판정과 다른 결론을
-- 내지 않게 하려는 것이다(예: 상한에 걸린 결과가 30분 뒤 재전송으로 자격을 얻지 않는다).
--
-- **재전송은 결론을 낮추기만 한다.** ELIGIBLE은 관문(동의·운영 제한·공급자) 재확인으로 SKIPPED가 될 수 있고(행은 그대로다 —
-- 관문이 다시 열리면 같은 transition의 자격이다), SKIPPED는 제한이 풀리거나 공급자가 켜지거나 동의해도 그대로다.
--
-- **transition 참조.** ELIGIBLE 행에만 있는 서버 생성 난수(256비트, base64url 43자)이고 사용자·결과를 식별하지 않는다. Story 4.3의
-- 중립 전환 화면이 결과 대신 이 값만 쓴다.
--
-- **순서와 시각.** 쓰기는 사용자 advisory 잠금 아래의 문장이므로 같은 사용자의 순번(`id`)은 커밋 순서대로 커진다. 최근 자격은
-- 순번으로 고른다. `decided_at`은 INSERT 문장의 DB 시각(`statement_timestamp()`)이고 빈도 제한 판정의 입력이다.
--
-- **분류(데이터 계약).** 이 표의 행은 전부 "사용자 귀속 데이터"다. 식별 없는 집계는 계측 카운터(`bokrilog_ad_*`)뿐이고 이 표에
-- 없다. 계정 삭제는 deletion port(`EngagementDeletionApi.eraseAdAttempts`)가 그 사용자의 행을 지운다(비식별이 아니라 행 삭제).
--
-- 애플리케이션의 SQL은 한 클래스(`AdAttemptRepository`)에 있다. 문장은 INSERT·SELECT와 deletion port의 DELETE뿐이다(행을 고치는
-- 문장이 없다 — 재전송 뒤 철회는 응답만 낮추고 행은 그대로다).
--
-- expand만 한다(AD-12). V1~V26은 고치지 않는다.

CREATE TABLE engagement_ad_attempt (
    id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        uuid        NOT NULL,
    sync_job_id    uuid        NOT NULL,
    outcome        text        NOT NULL,
    -- SKIPPED의 첫 관문·빈도 제한 사유. ELIGIBLE이면 없다.
    skip_reason    text,
    -- ELIGIBLE의 불투명 transition 참조. SKIPPED면 없다.
    transition_ref text,
    decided_at     timestamptz NOT NULL DEFAULT statement_timestamp(),

    CONSTRAINT engagement_ad_attempt_result UNIQUE (user_id, sync_job_id),
    CONSTRAINT engagement_ad_attempt_transition UNIQUE (transition_ref),
    CONSTRAINT engagement_ad_attempt_outcome_check CHECK (outcome IN ('ELIGIBLE', 'SKIPPED')),
    CONSTRAINT engagement_ad_attempt_reason_check CHECK (skip_reason IS NULL OR skip_reason IN ('CONSENT_REQUIRED',
        'RESTRICTED', 'PROVIDER_DISABLED', 'INTERVAL', 'DAILY_CAP')),
    CONSTRAINT engagement_ad_attempt_shape_check CHECK (
        (outcome = 'ELIGIBLE' AND skip_reason IS NULL AND transition_ref IS NOT NULL)
        OR (outcome = 'SKIPPED' AND skip_reason IS NOT NULL AND transition_ref IS NULL)),
    CONSTRAINT engagement_ad_attempt_transition_check
        CHECK (transition_ref IS NULL OR transition_ref ~ '^[A-Za-z0-9_-]{43}$')
);

-- 사용자별 최근 자격(빈도 제한의 입력) — 가장 큰 순번부터.
CREATE INDEX engagement_ad_attempt_recent_eligible_idx
    ON engagement_ad_attempt (user_id, id DESC) WHERE outcome = 'ELIGIBLE';
