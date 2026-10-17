package com.bokrilog.engagement;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * engagement 모듈의 deletion port(Story 4.1·4.2).
 *
 * <p><b>분류.</b> 광고 동의 결정 행(Story 4.1)과 광고 자격 결정 행(Story 4.2 — 빈도 제한·reservation·transition 참조를 함께
 * 담는다)은 전부 "사용자 귀속 데이터"다 — Story 4.5의 SHOW 표지·Vitals metric 보고 표지도 그 행의 열이다. 식별 없는 집계는 계측
 * 카운터({@code bokrilog_ad_*}·{@code bokrilog_web_vitals_*})와 Vitals 표본 표(V29 — 사용자·attempt 열이 없다)이고 삭제
 * 대상이 아니다. 그래서 삭제의 답은 비식별이 아니라 행 삭제다. 멱등 기록은 결정 행 자체이므로 함께 사라진다. 법정 분리 보존 후보 필드는 외부 승인(G-5) 전까지 없다.
 *
 * <p>계정 삭제 orchestrator(루트 {@code AccountErasure})가 삭제 전환과 같은 트랜잭션에서 부른다. 실제 계정 삭제 요청과의
 * 통합은 Epic 5가 한다.
 */
public interface EngagementDeletionApi {

    /**
     * 그 사용자의 광고 동의 결정 행을 지운다. 다른 사용자의 행은 바뀌지 않는다. 멱등이다.
     *
     * @return 이번에 지운 행 수. 이미 지웠으면 0
     */
    int eraseConsents(@Nullable UUID userId);

    /**
     * 그 사용자의 광고 자격 결정 행(자격·건너뜀·transition 참조)을 지운다. 다른 사용자의 행은 바뀌지 않는다. 멱등이다.
     *
     * @return 이번에 지운 행 수. 이미 지웠으면 0
     */
    int eraseAdAttempts(@Nullable UUID userId);

    /**
     * 그 사용자에게 아직 귀속된 이 모듈의 행 수(Story 3.10 복원 검증). {@code engagement_*} 표의 {@code user_id} 열을 카탈로그로
     * 찾아 센다 — 표가 늘어도 빠지지 않는다. 삭제가 끝난 계정이면 0이어야 한다.
     */
    long remainingRowsOf(UUID userId);
}
