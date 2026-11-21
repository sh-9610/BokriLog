package com.bokrilog.engagement.internal;

import com.bokrilog.engagement.AdEligibilityApi.SkipReason;
import com.bokrilog.engagement.DashboardAdApi;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionSubject;
import java.util.EnumSet;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** 광고의 전역 운영 제한과 공급자 활성 설정을 판독한다. */
@Component
class AdGate implements DashboardAdApi {

    private final OperationalRestrictionApi restrictions;
    private final AdProviderState provider;

    AdGate(OperationalRestrictionApi restrictions, AdProviderState provider) {
        this.restrictions = restrictions;
        this.provider = provider;
    }

    @Override
    public @Nullable String adUnitPath() {
        return firstClosed() == null ? this.provider.adUnitPath() : null;
    }

    @Nullable SkipReason firstClosed() {
        if (this.restrictions.blocks(EnumSet.of(RestrictionFeature.ADVERTISING), RestrictionSubject.none())) {
            return SkipReason.RESTRICTED;
        }
        if (!this.provider.enabled()) {
            return SkipReason.PROVIDER_DISABLED;
        }
        return null;
    }
}
