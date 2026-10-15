-- Story 4.1: 문맥형 광고 동의를 선택하고 철회한다(`AD-CONSENT-1.0`).
--
-- **결정 행 하나가 결정 하나다.** 사용자의 동의·비동의·철회를 최신 하나로 덮어쓰지 않고 추가 전용으로 쌓는다 — "언제 어떤
-- 정책 버전에 무엇을 골랐나"가 남는다. 지금 상태는 그 사용자의 최신 행에서 읽는다. 최신 행이 없거나 그 정책 버전이 지금
-- 버전(서버 상수 `AdConsentApi.POLICY_VERSION`)과 다르면 `UNDECIDED`다 — 정책이 바뀌면 다시 묻고 자동 재동의가 없다. 철회는
-- `DENIED` 행 하나를 더하는 것이고 다음 판독부터 적용된다.
--
-- **멱등.** 권위는 `(user_id, idempotency_key_digest)` 유일 색인이다. 멱등 기록은 결정 행 자체이고 따로 두지 않는다(Story 3.6
-- 선례). 키 원문은 남기지 않는다(digest만). 같은 키의 다른 입력은 `request_digest`가 가른다. 지금 상태와 같은 선택은 행을
-- 만들지 않는다.
--
-- **순서와 시각.** 최신 행은 `id`(INSERT 때 매기는 identity 순번)가 가장 큰 행이다. 쓰기는 사용자 advisory 잠금을 쥔 뒤의
-- 문장이므로 같은 사용자의 순번은 커밋 순서대로 커진다. 시각으로 정렬하지 않는다 — DB 시계가 뒤로 가면 시각 순서가 커밋
-- 순서와 갈라져 최신 판독이 뒤집힌다. `decided_at`은 INSERT 문장의 DB 시각(`statement_timestamp()`)이고 기록·표시용이다.
--
-- **분류(데이터 계약).** 이 표의 행은 전부 "사용자 귀속 데이터"다. 식별 없는 집계는 없다. 계정 삭제는 deletion port
-- (`EngagementDeletionApi.eraseConsents`)가 그 사용자의 행을 지운다(비식별이 아니라 행 삭제). 법정 분리 보존 후보는 외부
-- 승인(G-5) 전이라 없다(개발·CI 기본값). 다른 모듈 표처럼 계정 표를 외래키로 가리키지 않는다 — engagement는 identity 소유
-- 표에 닿지 않는다(삭제 상태는 `AccountAccessApi.lockWriteAccess`로 본다).
--
-- 애플리케이션의 SQL은 한 클래스(`AdConsentRepository`)에 있다. 문장은 INSERT·SELECT와 deletion port의 DELETE뿐이다(행을
-- 고치는 문장이 없다).
--
-- expand만 한다(AD-12). V1~V25는 고치지 않는다.

CREATE TABLE engagement_ad_consent_decision (
    id                     bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id                uuid        NOT NULL,
    policy_version         text        NOT NULL,
    choice                 text        NOT NULL,
    decided_at             timestamptz NOT NULL DEFAULT statement_timestamp(),
    idempotency_key_digest text        NOT NULL,
    -- 같은 키의 다른 입력을 가르는 입력 전체(선택·정책 버전)의 SHA-256.
    request_digest         text        NOT NULL,

    CONSTRAINT engagement_ad_consent_decision_idempotency_key UNIQUE (user_id, idempotency_key_digest),
    CONSTRAINT engagement_ad_consent_decision_choice_check CHECK (choice IN ('GRANTED', 'DENIED')),
    CONSTRAINT engagement_ad_consent_decision_policy_check
        CHECK (policy_version ~ '^AD-CONSENT-[0-9]+\.[0-9]+$'),
    CONSTRAINT engagement_ad_consent_decision_digest_check
        CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$' AND request_digest ~ '^[0-9a-f]{64}$')
);

-- 지금 상태(최신 행 = 가장 큰 순번) 판독.
CREATE INDEX engagement_ad_consent_decision_latest_idx
    ON engagement_ad_consent_decision (user_id, id DESC);
