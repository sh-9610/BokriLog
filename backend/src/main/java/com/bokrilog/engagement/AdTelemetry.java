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
 *
 * <p><b>Story 4.5가 넷을 더했다.</b>
 * <ul>
 *   <li>{@link #TRANSITION} {@code bokrilog_ad_transition_total{outcome}} — transition 소비의 결말(커밋 뒤). 값은
 *       {@link #TRANSITION_OUTCOMES}: {@code SHOW}, {@link AdTransitionApi.SkipReason} 전부, {@link #NOT_FOUND}.
 *   <li>{@link #CLIENT_EVENT} {@code bokrilog_ad_client_event_total{event,reason}} — 중립 화면이 beacon으로 보낸 광고 이벤트.
 *       {@code reason}은 {@code FAILED}에만 사유이고 나머지는 {@link #NONE}이다.
 *   <li>{@link #WEB_VITALS} {@code bokrilog_web_vitals_total{metric,route,rating,ad}} — 네 화면의 Web Vitals. {@code rating}은
 *       NFR-14 임계를 넘으면 {@code POOR}, 아니면 {@code GOOD}이고 {@code ad}는 광고를 시도한 문서의 값인가({@code true}·
 *       {@code false})다. "p75 ≤ 임계"는 "POOR 비율 ≤ 25%"와 같으므로 경보는 이 둘을 임계 비율로 판정한다(Prometheus
 *       {@code increase}는 외삽 근사다 — 정확한 p75는 kill switch가 DB 표본으로 본다).
 *   <li>{@link #VITAL_SAMPLES} {@code bokrilog_ad_vitals_samples{metric}} — kill switch 판정 창(24시간)의 표본 수(gauge, Worker·
 *       {@code all}만).
 * </ul>
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

    /** transition 소비의 결말(Story 4.5). */
    public static final String TRANSITION = "bokrilog.ad.transition";

    /** 광고를 시도한 소비. */
    public static final String SHOW = "SHOW";

    /** 그 사용자의 참조가 아니거나 계정이 쓰기를 받지 않는다. */
    public static final String NOT_FOUND = "NOT_FOUND";

    /** {@code outcome} tag 값: {@link #SHOW}, 소비 건너뜀 사유 전부, {@link #NOT_FOUND}. */
    public static final Set<String> TRANSITION_OUTCOMES = Stream.concat(
                    Stream.concat(Stream.of(SHOW), Arrays.stream(AdTransitionApi.SkipReason.values()).map(Enum::name)),
                    Stream.of(NOT_FOUND))
            .collect(Collectors.toUnmodifiableSet());

    /** 중립 화면의 광고 이벤트(Story 4.5). */
    public static final String CLIENT_EVENT = "bokrilog.ad.client.event";

    /** {@code FAILED} 밖 이벤트의 {@code reason}. */
    public static final String NONE = "NONE";

    /** {@code event} tag 값. */
    public static final Set<String> CLIENT_EVENTS = Arrays.stream(ClientTelemetryApi.AdEvent.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    /** {@code reason} tag 값: 실패 사유 전부와 {@link #NONE}. */
    public static final Set<String> CLIENT_REASONS = Stream.concat(
                    Arrays.stream(ClientTelemetryApi.AdFailure.values()).map(Enum::name), Stream.of(NONE))
            .collect(Collectors.toUnmodifiableSet());

    /** 네 화면의 Web Vitals(Story 4.5). */
    public static final String WEB_VITALS = "bokrilog.web.vitals";

    /** {@code metric} tag 값. */
    public static final Set<String> VITAL_METRICS = Arrays.stream(ClientTelemetryApi.VitalMetric.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    /** {@code route} tag 값 — 화면 경로 넷. */
    public static final Set<String> VITAL_ROUTES = Arrays.stream(ClientTelemetryApi.Route.values())
            .map(ClientTelemetryApi.Route::path)
            .collect(Collectors.toUnmodifiableSet());

    /** 임계 안. */
    public static final String GOOD = "GOOD";

    /** 임계 초과. */
    public static final String POOR = "POOR";

    /** {@code rating} tag 값. */
    public static final Set<String> VITAL_RATINGS = Set.of(GOOD, POOR);

    /** {@code ad} tag 값 — 광고를 시도한 문서의 Vitals인가. */
    public static final Set<String> VITAL_ADS = Set.of("true", "false");

    /** kill switch 판정 창의 표본 수(gauge, Story 4.5). tag는 {@code metric} 하나다. */
    public static final String VITAL_SAMPLES = "bokrilog.ad.vitals.samples";

    private AdTelemetry() {}
}
