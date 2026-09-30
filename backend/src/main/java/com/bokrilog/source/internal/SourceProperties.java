package com.bokrilog.source.internal;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;

/**
 * source 모듈의 외부 구성. 이름만 이 record가 정하고 값은 환경이 준다.
 *
 * <p>{@code AuthProperties}와 같은 배치다 — 값이 없는 구성에서도 기동이 성립하고, 없으면 그 기능만
 * 공통 오류 계약으로 fail-closed다. 자격정보 모양 리터럴을 기본값으로 두지 않는다.
 *
 * @param credential 자격정보 암호화 key ring
 * @param bybit Bybit 어댑터가 보는 값
 */
@ConfigurationProperties("bokrilog.source")
record SourceProperties(Credential credential, Bybit bybit) {

    SourceProperties {
        /*
         * `bokrilog.source.<하위>`가 통째로 없는 구성에서도 배선이 성립해야 한다. 정규화가 여기
         * 하나뿐인 것이 요점이며, 그래서 아래 접근자는 null을 돌려주지 않고 소비하는 쪽에 null
         * 검사가 남지 않는다(`AuthProperties`와 같은 근거).
         */
        if (credential == null) {
            credential = Credential.empty();
        }
        if (bybit == null) {
            bybit = Bybit.empty();
        }
    }

    /**
     * 자격정보 암호화 key ring.
     *
     * @param keys {@code <버전>:<base64 32바이트>}를 쉼표로 이은 목록. 과거 키를 함께 담는다
     * @param activeKeyVersion 새 암호화가 쓸 버전. 그 버전의 키가 목록에 없으면 기능이 닫힌다
     */
    record Credential(@Nullable String keys, @Nullable String activeKeyVersion) {

        static Credential empty() {
            return new Credential(null, null);
        }
    }

    /**
     * Bybit 어댑터 구성.
     *
     * @param baseUrl 제공자 API의 origin. 개발·CI는 스텁을 가리키고 값이 비면 기능이 닫힌다
     * @param reservedIp 운영 reserved IP. 키의 {@code ips} 허용 목록에 이 값이 있어야 통과한다.
     *     Day 90을 조건부 게이트로 다루지 않으므로 신규 연결은 처음부터 이 값을 요구한다
     *     ({@code ERD-1.0.0} §10·AR-21). 비면 기능이 닫힌다
     * @param requestTimeout 외부 호출 하나의 상한. 넘으면 거절이다. 수집 페이지만 {@code budget}의
     *     재시도를 받고 admission 호출은 재시도하지 않는다
     * @param budget 호출 예산(ERD-1.0.0 §2.5, Story 2.12). 비면 ERD의 값 그대로다
     */
    record Bybit(
            @Nullable String baseUrl, @Nullable String reservedIp, @Nullable String requestTimeout,
            @Nullable Budget budget) {

        /** 상한이 구성되지 않은 스택의 바닥. 값이 없다고 무한정 기다리지 않는다. */
        static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

        static Bybit empty() {
            return new Bybit(null, null, null, null);
        }

        /** 호출 예산. 구성이 없거나 읽을 수 없는 값은 ERD의 값으로 접힌다. */
        BybitCallBudget.Settings budgetOrDefault() {
            return this.budget == null ? BybitCallBudget.Settings.defaults() : this.budget.settings();
        }

        /**
         * 외부 호출 하나의 상한.
         *
         * <p><b>{@link Duration}이 아니라 {@link String}으로 받는다.</b> Boot의 {@code ${이름:기본값}}
         * 은 property가 <i>해석되지 않을</i> 때만 기본값을 쓰고, 빈 문자열로 설정된 환경 변수는
         * 해석된다 — 그 빈 값이 {@code Duration} 바인딩에 닿으면 컨텍스트 생성이 실패한다. 이
         * 스토리의 계약은 "값이 없으면 기능만 닫히고 기동은 산다"이므로 구성 하나가 기동을 죽이는
         * 자리를 만들지 않는다. 문자열로 받아 여기서 접으면 읽을 수 없는 값도 기본값으로 수렴한다.
         *
         * @return 구성된 값, 비었거나 읽을 수 없거나 0 이하면 {@link #DEFAULT_REQUEST_TIMEOUT}
         */
        Duration requestTimeoutOrDefault() {
            if (this.requestTimeout == null || this.requestTimeout.isBlank()) {
                return DEFAULT_REQUEST_TIMEOUT;
            }
            Duration parsed;
            try {
                parsed = DurationStyle.detectAndParse(this.requestTimeout.trim());
            } catch (IllegalArgumentException unreadable) {
                // 값을 예외에 담지 않는다. 읽을 수 없는 구성은 없는 것과 같다.
                return DEFAULT_REQUEST_TIMEOUT;
            }
            return parsed.isZero() || parsed.isNegative() ? DEFAULT_REQUEST_TIMEOUT : parsed;
        }
    }

    /**
     * Bybit 호출 예산(ERD-1.0.0 §2.5). 운영 기본값은 ERD 그대로이고 시험만 줄인다.
     *
     * <p>{@code requestTimeout}과 같은 근거로 전부 문자열로 받는다 — 빈 환경 변수 하나가 바인딩을 실패시켜
     * 기동을 죽이지 않게 하고, 읽을 수 없거나 범위 밖의 값은 조용히 기본값으로 접는다(값을 예외·로그에 싣지
     * 않는다).
     *
     * <p>초당 요청 수는 {@link #MIN_RATE}(0.1/s) 밑이면 기본값이다 — 그보다 느린 bucket은 토큰 하나에 10초를 넘게
     * 기다리게 해 수집을 멈춘 것과 구별되지 않는다. 동시 수집 수는 정수만 받는다(소수를 잘라 읽지 않는다).
     *
     * @param authPerSecond 서명 호출의 자격정보(UID)당 초당 요청 수. 기본 5
     * @param publicPerSecond 공개 Market 호출의 서비스 전체 초당 요청 수. 기본 10
     * @param maxConcurrentCollections 동시에 수집하는 연결 수(양의 정수). 기본 2
     * @param retryDelays 수집 페이지 재시도 간격, 쉼표로 이은 Duration 목록. 기본 {@code 2s,10s,30s}
     * @param retryJitter 재시도 간격에 더하는 최대 비율(0~1). 기본 0.2
     * @param circuitOpen HTTP 403(IP 차단) 뒤 모든 호출을 멈추는 시간. 기본 10분
     * @param lowRemainingRatio 이 비율 이하로 잔여가 남으면 다음 페이지를 reset 시각까지 늦춘다. 기본 0.2
     * @param lowRemainingWaitCap 그 대기의 상한. 기본 5초
     */
    record Budget(@Nullable String authPerSecond, @Nullable String publicPerSecond,
            @Nullable String maxConcurrentCollections, @Nullable String retryDelays, @Nullable String retryJitter,
            @Nullable String circuitOpen, @Nullable String lowRemainingRatio, @Nullable String lowRemainingWaitCap) {

        /** The slowest rate a bucket may be configured with. */
        static final double MIN_RATE = 0.1;

        BybitCallBudget.Settings settings() {
            var defaults = BybitCallBudget.Settings.defaults();
            return new BybitCallBudget.Settings(
                    rate(this.authPerSecond, defaults.authPerSecond()),
                    rate(this.publicPerSecond, defaults.publicPerSecond()),
                    positiveInteger(this.maxConcurrentCollections, defaults.maxCollections()),
                    positiveDuration(this.circuitOpen, defaults.circuitOpen()),
                    ratio(this.lowRemainingRatio, defaults.lowRemainingRatio()),
                    positiveDuration(this.lowRemainingWaitCap, defaults.lowRemainingWaitCap()),
                    delays(this.retryDelays, defaults.retryDelays()),
                    ratio(this.retryJitter, defaults.retryJitter()));
        }

        private static double rate(@Nullable String value, double fallback) {
            if (value == null || value.isBlank()) return fallback;
            try {
                double parsed = Double.parseDouble(value.trim());
                return Double.isFinite(parsed) && parsed >= MIN_RATE ? parsed : fallback;
            } catch (NumberFormatException unreadable) {
                return fallback;
            }
        }

        private static int positiveInteger(@Nullable String value, int fallback) {
            if (value == null || !value.trim().matches("[0-9]{1,6}")) return fallback;
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        }

        private static double ratio(@Nullable String value, double fallback) {
            if (value == null || value.isBlank()) return fallback;
            try {
                double parsed = Double.parseDouble(value.trim());
                return Double.isFinite(parsed) && parsed >= 0 && parsed <= 1 ? parsed : fallback;
            } catch (NumberFormatException unreadable) {
                return fallback;
            }
        }

        private static Duration positiveDuration(@Nullable String value, Duration fallback) {
            if (value == null || value.isBlank()) return fallback;
            try {
                Duration parsed = DurationStyle.detectAndParse(value.trim());
                return parsed.isZero() || parsed.isNegative() ? fallback : parsed;
            } catch (IllegalArgumentException unreadable) {
                return fallback;
            }
        }

        private static java.util.List<Duration> delays(@Nullable String value, java.util.List<Duration> fallback) {
            if (value == null || value.isBlank()) return fallback;
            java.util.List<Duration> parsed = new java.util.ArrayList<>();
            for (String part : value.split(",")) {
                Duration delay = positiveDuration(part, Duration.ZERO);
                if (delay.isZero()) return fallback;
                parsed.add(delay);
            }
            return parsed.isEmpty() || parsed.size() > 3 ? fallback : java.util.List.copyOf(parsed);
        }
    }
}
