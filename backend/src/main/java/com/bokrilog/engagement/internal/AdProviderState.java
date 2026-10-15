package com.bokrilog.engagement.internal;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * 광고 공급자의 활성 상태(Story 4.2·4.3). 자격 판정과 transition 소비의 세 번째 관문({@code PROVIDER_DISABLED})이 읽는다.
 *
 * <p><b>활성은 두 조건이 함께일 때다(Story 4.3).</b> 플래그 {@code ad-provider-enabled}가 켜졌고, GPT ad unit 경로
 * {@code ad-unit-path}가 {@link #AD_UNIT_PATH} 형식이어야 한다. 단위 경로가 없거나 틀리면 플래그가 켜져도 꺼짐이다 — 광고를 띄울
 * slot이 없는데 자격을 주면 중립 화면이 매번 실패 경로로 간다. 형식 오류는 기동을 막지 않는다(SPEC).
 */
@Component
class AdProviderState {

    /** GPT ad unit 경로: {@code /<network code>/<unit>[/<unit>…]}. */
    static final Pattern AD_UNIT_PATH = Pattern.compile("^/[0-9]+(/[A-Za-z0-9_.-]+)+$");

    /** 플래그. 운영에서는 기동 시 설정 그대로이고 바뀌지 않는다(바꾸는 것은 재배포다). */
    private volatile boolean flag;

    /** 형식에 맞는 단위 경로. 없거나 틀리면 {@code null}이다. */
    private final @Nullable String adUnitPath;

    AdProviderState(EngagementProperties properties) {
        this.flag = properties.adProviderEnabled();
        String path = properties.adUnitPath();
        this.adUnitPath = path != null && AD_UNIT_PATH.matcher(path).matches() ? path : null;
    }

    /** 지금 활성인가 — 플래그가 켜졌고 단위 경로가 형식에 맞다. */
    boolean enabled() {
        return this.flag && this.adUnitPath != null;
    }

    /** 활성일 때의 단위 경로. 활성이 아니면 {@code null}이다. */
    @Nullable String adUnitPath() {
        return this.flag ? this.adUnitPath : null;
    }

    /**
     * 플래그를 바꾼다 — <b>시험 훅</b>이다({@code SupportContact.reinterpret}와 같은 관용). 시험 컨텍스트 하나가 공급자 켜짐과
     * 꺼짐을 함께 보게 한다. 제품 경로에서 부르는 곳이 없다(HTTP 표면도 없다).
     */
    void reinterpret(boolean enabled) {
        this.flag = enabled;
    }
}
