-- Story 4.3: 금융정보 없는 중립 화면에서 transition 참조를 한 번만 소비한다.
--
-- **소비 시각 하나.** Story 4.2의 자격 행(ELIGIBLE)이 발급한 transition 참조는 중립 전환 화면이 서버에 한 번 소비시킨다. 소비는
-- 판정 결과(광고 시도·건너뜀)와 무관하게 이 열에 그 문장의 DB 시각(`statement_timestamp()`)을 쓰는 것이고, 이미 소비된 행은 그대로
-- 둔다 — 같은 참조의 두 번째 소비는 `CONSUMED`로 건너뛴다(Story 4.2의 ELIGIBLE 재전송이 같은 참조를 돌려줘도 노출이 다시 일어나지
-- 않는다, deferred-work 149). 소비 창(결정 뒤 120초)은 `decided_at`과 DB 시각으로 판정하며 저장하지 않는다.
--
-- **소비는 ELIGIBLE 행에만 있다.** SKIPPED 행에는 transition 참조가 없으므로 소비도 없다(CHECK).
--
-- **분류(데이터 계약).** 이 열도 V27의 행과 같이 "사용자 귀속 데이터"다. 계정 삭제는 기존 deletion port
-- (`EngagementDeletionApi.eraseAdAttempts`)가 행째 지운다.
--
-- 애플리케이션의 SQL은 여전히 한 클래스(`AdAttemptRepository`)에 있다. 이 열을 바꾸는 문장은 소비의 조건부 UPDATE 하나다
-- (`transition_consumed_at IS NULL`인 행만 바꾼다). 결정(outcome·사유·참조·결정 시각)을 고치는 문장은 여전히 없다.
--
-- expand만 한다(AD-12). V1~V27은 고치지 않는다.

ALTER TABLE engagement_ad_attempt
    ADD COLUMN transition_consumed_at timestamptz;

ALTER TABLE engagement_ad_attempt
    ADD CONSTRAINT engagement_ad_attempt_consumed_check
        CHECK (transition_consumed_at IS NULL OR outcome = 'ELIGIBLE');
