-- Story 4.5: 광고를 띄운 문서의 Web Vitals를 비식별 표본으로 남기고, 성능 목표를 위협하면 광고만 끈다.
--
-- **SHOW 표지와 metric별 보고 표지.** transition 소비(Story 4.3)가 광고를 시도한(SHOW) 행에만 `transition_shown`을 같은 소비
-- 문장에서 참으로 쓴다. 중립 화면이 보낸 Vitals는 그 사용자의 SHOW 행 가운데 소비 뒤 10분 안인 행에서 그 metric이 아직 보고되지
-- 않았을 때만 표본이 되고, 그 행의 `vitals_reported_metrics`에 그 metric을 더한다 — SHOW 소비 1건당 metric마다 표본 하나다. 한
-- 문서의 Vitals는 여러 묶음으로 나뉘어 온다(결과 보기 flush에 LCP, 문서 hidden에 CLS·INP). 같은 metric의 두 번째 보고는 카운터만
-- 올린다. CHECK가 순서를 고정한다: 보고 표지 → SHOW → 소비.
--
-- **표본 표에는 사용자가 없다.** `engagement_ad_vital_sample`은 metric·값·기록 시각뿐이고 사용자·attempt·transition 열이 없다
-- (데이터 계약: 식별 없는 집계). 그래서 계정 삭제가 지울 것이 없고 잔여 행 검사(`user_id` 열을 카탈로그로 센다)에도 잡히지 않는다.
-- 수명은 판정 창(24시간)이다 — Worker의 판정이 매번 24시간 지난 표본을 지우고, kill switch가 발동했거나 전역 `ADVERTISING`
-- 제한이 이미 열려 있으면 그 시각까지의 표본을 지운다(해제 뒤 묵은 표본으로 다시 발동하지 않는다).
--
-- **kill switch 출처.** 성능 판정이 만든 전역 `ADVERTISING` 제한은 출처 `PERFORMANCE`다. 자동으로 풀리지 않고 운영 command
-- `lift-restriction`(Story 3.4)으로만 닫힌다.
--
-- 애플리케이션의 SQL은 engagement 표가 `AdAttemptRepository`·`AdVitalSampleRepository`, 제한 표가 `OperationalRestrictionRepository`
-- 한 곳씩이다.
--
-- expand만 한다(AD-12). V1~V28은 고치지 않는다(제한 출처 CHECK는 이름이 같은 새 CHECK로 바꾼다).

ALTER TABLE engagement_ad_attempt
    ADD COLUMN transition_shown boolean NOT NULL DEFAULT false;

-- 이 SHOW에서 이미 표본이 된 metric(LCP·INP·CLS 중 일부). 비어 있으면 아직 아무것도 보고되지 않았다.
ALTER TABLE engagement_ad_attempt
    ADD COLUMN vitals_reported_metrics text[] NOT NULL DEFAULT '{}';

ALTER TABLE engagement_ad_attempt
    ADD CONSTRAINT engagement_ad_attempt_shown_check
        CHECK (NOT transition_shown OR transition_consumed_at IS NOT NULL);

ALTER TABLE engagement_ad_attempt
    ADD CONSTRAINT engagement_ad_attempt_reported_check
        CHECK (cardinality(vitals_reported_metrics) = 0 OR transition_shown);

ALTER TABLE engagement_ad_attempt
    ADD CONSTRAINT engagement_ad_attempt_reported_metrics_check
        CHECK (vitals_reported_metrics <@ ARRAY['LCP', 'INP', 'CLS']::text[]);

-- 보고할 수 있는 SHOW 행(사용자별 최근 순번부터). 사용자당 하루 3행 이하다.
CREATE INDEX engagement_ad_attempt_shown_idx
    ON engagement_ad_attempt (user_id, id DESC) WHERE transition_shown;

CREATE TABLE engagement_ad_vital_sample (
    id          bigserial PRIMARY KEY,
    recorded_at timestamptz      NOT NULL DEFAULT statement_timestamp(),
    metric      text             NOT NULL,
    value       double precision NOT NULL,

    CONSTRAINT engagement_ad_vital_sample_metric_check CHECK (metric IN ('LCP', 'INP', 'CLS')),
    -- 0 이상 유한수. PostgreSQL에서 NaN은 Infinity보다 크므로 이 술어가 NaN도 거른다.
    CONSTRAINT engagement_ad_vital_sample_value_check CHECK (value >= 0 AND value < 'Infinity'::double precision)
);

-- 판정 창 읽기(창 안 전체를 metric별로 묶는다)와 창 밖·발동 시각까지의 삭제가 모두 기록 시각 범위로 고른다.
CREATE INDEX engagement_ad_vital_sample_recorded_idx ON engagement_ad_vital_sample (recorded_at);

ALTER TABLE operations_restriction DROP CONSTRAINT operations_restriction_origin_check;
ALTER TABLE operations_restriction ADD CONSTRAINT operations_restriction_origin_check
    CHECK (origin IN ('OPERATOR', 'SIGNAL', 'PERFORMANCE'));
