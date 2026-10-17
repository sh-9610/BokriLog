package com.bokrilog.engagement;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 브라우저 계측 beacon의 기록(Story 4.5) — Web Vitals와 광고 이벤트를 한 묶음씩 받는다.
 *
 * <p><b>어휘는 닫혀 있다.</b> metric·화면·광고 이벤트·실패 사유는 아래 enum뿐이고, 본문 모양(1~16건, 8 KiB 이하, 값은 0 이상
 * 유한수)은 호출자(루트 컨트롤러)가 먼저 본다 — 하나라도 어휘 밖이면 이 API를 부르지 않는다(400, 기록 없음).
 *
 * <p><b>기록은 둘이다.</b> 모든 이벤트는 닫힌 어휘의 카운터로 센다(커밋 뒤, {@link AdTelemetry}). 그리고 광고를 띄운 문서의 Vitals
 * ({@link Route#AD_TRANSITION}·{@code ad=true})는 그 사용자의 attempt 가운데 SHOW로 소비됐고 10분 안이며 그 metric이 아직 보고되지
 * 않은 행이 있을 때만 metric마다 하나씩 비식별 표본으로 남기고 그 행에 그 metric의 보고를 표시한다 — SHOW 소비 1건당 metric마다 한
 * 번이다(한 문서의 Vitals는 여러 묶음으로 나뉘어 온다). 표본에는 사용자·attempt·transition이 없다. Worker의 판정이 그 표본의 p75로 광고 성능 kill switch를 연다({@code operations.AdvertisingStopApi}).
 *
 * <p><b>트랜잭션 하나다.</b> 사용자 advisory 잠금(자격 판정·소비와 같은 이름) → {@code AccountAccessApi.lockWriteAccess}(거짓이면
 * {@link Result#DENIED}, 아무것도 기록하지 않는다 — 카운터도 세지 않는다) → SHOW 행 {@code FOR UPDATE} → 표본 → metric 보고 표지.
 *
 * <p>이 모듈은 HTTP·세션을 모른다. 호출자(루트)가 인증·CSRF·계정 접근·본문 모양을 먼저 본다.
 */
public interface ClientTelemetryApi {

    /** 묶음 하나의 최대 이벤트 수. */
    int MAX_EVENTS = 16;

    /** 묶음 본문의 최대 바이트 수(UTF-8). */
    int MAX_BODY_BYTES = 8 * 1024;

    /** 광고를 띄운 문서의 Vitals가 표본이 될 수 있는 SHOW 소비 뒤 시간(분). */
    long SAMPLE_WINDOW_MINUTES = 10;

    /** Web Vitals metric과 NFR-14 임계. 값이 임계를 <b>넘으면</b> {@code POOR}다(같으면 {@code GOOD}). */
    enum VitalMetric {
        /** Largest Contentful Paint(ms). */
        LCP(2500),
        /** Interaction to Next Paint(ms). */
        INP(200),
        /** Cumulative Layout Shift(단위 없음). */
        CLS(0.1);

        private final double threshold;

        VitalMetric(double threshold) {
            this.threshold = threshold;
        }

        /** NFR-14 임계. LCP·INP는 ms다. */
        public double threshold() {
            return this.threshold;
        }

        /** 그 값이 임계를 넘는가. */
        public boolean poor(double value) {
            return value > this.threshold;
        }
    }

    /** 측정하는 화면 넷. 진입·온보딩·운영자 화면은 측정하지 않는다. */
    enum Route {
        DASHBOARD("/dashboard"),
        SETTINGS("/settings"),
        PLAN_CONDITIONS("/plan-conditions"),
        AD_TRANSITION("/ad-transition");

        private final String path;

        Route(String path) {
            this.path = path;
        }

        /** 화면 경로. beacon 본문과 카운터 tag의 값이다. */
        public String path() {
            return this.path;
        }

        /** 그 경로의 화면. 모르면 {@code null}이다. */
        public static @Nullable Route ofPath(String path) {
            for (Route route : values()) {
                if (route.path.equals(path)) {
                    return route;
                }
            }
            return null;
        }
    }

    /** 중립 화면의 광고 이벤트. */
    enum AdEvent {
        /** SHOW 소비 뒤 adapter를 불렀다. */
        REQUESTED,
        /** adapter가 준비를 알렸다. */
        READY,
        /** {@code 광고 닫기}를 눌렀다. */
        CLOSED,
        /** 광고를 쓸 수 없었다({@link AdFailure} 사유와 함께). */
        FAILED,
        /** 중립 화면이 결과로 떠났다. */
        RESULT
    }

    /** {@link AdEvent#FAILED}의 사유. */
    enum AdFailure {
        TIMEOUT,
        NO_FILL,
        BLOCKED,
        UNSUPPORTED,
        ERROR,
        /** 소비 창(120초)이 지나 화면이 광고를 취소했다. */
        WINDOW_EXPIRED
    }

    /** beacon 이벤트 하나. */
    sealed interface Event {

        /**
         * Web Vitals 하나.
         *
         * @param value 0 이상 유한수. LCP·INP는 ms다
         * @param ad 이 문서가 광고를 시도했다(SHOW 뒤 {@code REQUESTED})
         */
        record Vital(VitalMetric metric, Route route, double value, boolean ad) implements Event {

            public Vital {
                if (!Double.isFinite(value) || value < 0) {
                    throw new IllegalArgumentException("Vitals 값은 0 이상 유한수다.");
                }
            }
        }

        /**
         * 광고 이벤트 하나.
         *
         * @param reason {@link AdEvent#FAILED}에만 있고 거기서는 필수다
         */
        record Ad(AdEvent event, @Nullable AdFailure reason) implements Event {

            public Ad {
                if ((event == AdEvent.FAILED) != (reason != null)) {
                    throw new IllegalArgumentException("사유는 FAILED에만 있고 거기서는 필수다.");
                }
            }
        }
    }

    /** 기록의 결말. */
    enum Result {
        /** 기록했다(카운터는 커밋 뒤에 센다). */
        RECORDED,
        /** 쓰기 잠금 아래에서 계정이 쓰기를 받지 않는다(삭제 시작 등). 아무것도 기록하지 않았다. */
        DENIED
    }

    /**
     * 묶음 하나를 기록한다.
     *
     * @param events 1~{@value #MAX_EVENTS}건
     */
    Result record(UUID userId, List<Event> events);
}
