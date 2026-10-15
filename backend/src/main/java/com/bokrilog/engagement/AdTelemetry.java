package com.bokrilog.engagement;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 광고 자격 계측의 닫힌 어휘(Story 4.2). 메트릭 이름과 tag 값은 여기 있는 것뿐이다.
 *
 * <p>루트 {@code TelemetryAllowlist}가 이 어휘로 허용 표의 광고 행을 만들고, engagement 모듈의 계측이 같은 어휘로 등록한다 — 두
 * 곳이 갈라질 자리가 없다. tag 값은 코드가 정한 enum 문자열뿐이다. 사용자·결과(job)·transition 참조, 금융 값은 tag에도 이름에도
 * 없다.
 *
 * <p><b>정의.</b> 모두 판정 트랜잭션이 커밋된 뒤에 센다.
 * <ul>
 *   <li>{@link #TRIGGER} {@code bokrilog_ad_trigger_total} — 판정에 닿은 요청: 계정 쓰기 잠금을 지나 결정(자격·건너뜀·비후보)을
 *       낸 요청. 본문 모양 오류·계정 거절은 세지 않는다.
 *   <li>{@link #SKIP} {@code bokrilog_ad_skip_total{reason}} — 새 자격이 아닌 판정. 사유는 {@link #SKIP_REASONS} 일곱이다. 같은
 *       결과의 재전송은 결말과 무관하게 {@link #REPLAYED} 하나로 센다 — 재전송은 새 시도가 아니다.
 *   <li>{@link #RESERVATION} {@code bokrilog_ad_reservation_total} — 새 자격(노출 시도 reservation).
 * </ul>
 * 그래서 언제나 {@code trigger = reservation + Σ skip}이다. 기간은 프로세스 수명이고 환경은 공통 tag {@code env}가 가른다.
 */
public final class AdTelemetry {

    /** 판정에 닿은 요청. */
    public static final String TRIGGER = "bokrilog.ad.trigger";

    /** 새 자격이 아닌 판정과 그 사유. */
    public static final String SKIP = "bokrilog.ad.skip";

    /** 새 자격(reservation). */
    public static final String RESERVATION = "bokrilog.ad.reservation";

    /** 후보가 아닌 attempt(남의 것·없는 것 포함). */
    public static final String NOT_CANDIDATE = "NOT_CANDIDATE";

    /** 같은 결과의 재전송. */
    public static final String REPLAYED = "REPLAYED";

    /**
     * {@code reason} tag 값: 건너뜀 사유({@link AdEligibilityApi.SkipReason}의 이름 전부)와 비후보·재전송. 사유 enum이 유일한
     * 원천이다 — 사유를 더하면 이 집합과 허용 표가 함께 는다(스키마 CHECK와의 일치는 시험이 카탈로그로 본다).
     */
    public static final Set<String> SKIP_REASONS = Stream.concat(
                    Arrays.stream(AdEligibilityApi.SkipReason.values()).map(Enum::name),
                    Stream.of(NOT_CANDIDATE, REPLAYED))
            .collect(Collectors.toUnmodifiableSet());

    private AdTelemetry() {}
}
