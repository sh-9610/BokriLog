package com.bokrilog.engagement.internal;

import org.springframework.stereotype.Component;

/**
 * 광고 공급자의 활성 상태(Story 4.2). 자격 판정의 세 번째 관문({@code PROVIDER_DISABLED})이 읽는다. 공급자 adapter(Story 4.3)가
 * 생기면 그 adapter의 상태가 이 자리를 잇는다.
 */
@Component
class AdProviderState {

    /** 지금 활성 여부. 운영에서는 기동 시 설정 그대로이고 바뀌지 않는다(바꾸는 것은 재배포다). */
    private volatile boolean enabled;

    AdProviderState(EngagementProperties properties) {
        this.enabled = properties.adProviderEnabled();
    }

    boolean enabled() {
        return this.enabled;
    }

    /**
     * 활성 여부를 바꾼다 — <b>시험 훅</b>이다({@code SupportContact.reinterpret}와 같은 관용). 시험 컨텍스트 하나가 공급자 켜짐과
     * 꺼짐을 함께 보게 한다. 제품 경로에서 부르는 곳이 없다(HTTP 표면도 없다).
     */
    void reinterpret(boolean enabled) {
        this.enabled = enabled;
    }
}
