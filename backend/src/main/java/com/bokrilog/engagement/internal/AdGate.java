package com.bokrilog.engagement.internal;

import com.bokrilog.engagement.AdConsentApi;
import com.bokrilog.engagement.AdEligibilityApi.SkipReason;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionSubject;
import java.util.EnumSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * 광고 관문 한 벌(Story 4.2에서 꺼냄, Story 4.3이 공유). 자격 판정({@link AdEligibilityService})과 transition 소비
 * ({@link AdTransitionService})가 같은 순서로 다시 본다 — 동의 → 전역 {@code ADVERTISING} 운영 제한 → 공급자 활성, 처음 걸린 것이
 * 사유다. 두 곳이 순서를 따로 가지면 한쪽만 바뀌는 날이 온다.
 *
 * <p><b>판독 실패는 예외다(fail-closed).</b> 동의·운영 제한 판독이 실패하면 예외가 그대로 올라가 호출자의 트랜잭션이 롤백된다 —
 * 읽지 못한 것을 "동의함"·"제한 없음"으로 접지 않는다.
 */
@Component
class AdGate {

    private final AdConsentApi consents;

    private final OperationalRestrictionApi restrictions;

    private final AdProviderState provider;

    AdGate(AdConsentApi consents, OperationalRestrictionApi restrictions, AdProviderState provider) {
        this.consents = consents;
        this.restrictions = restrictions;
        this.provider = provider;
    }

    /** 처음 걸린 관문. 모두 지나면 {@code null}이다. 앞 관문에 걸리면 뒤 관문을 읽지 않는다. */
    @Nullable SkipReason firstClosed(UUID userId) {
        if (!this.consents.current(userId).granted()) {
            return SkipReason.CONSENT_REQUIRED;
        }
        if (this.restrictions.blocks(EnumSet.of(RestrictionFeature.ADVERTISING), RestrictionSubject.none())) {
            return SkipReason.RESTRICTED;
        }
        if (!this.provider.enabled()) {
            return SkipReason.PROVIDER_DISABLED;
        }
        return null;
    }
}
