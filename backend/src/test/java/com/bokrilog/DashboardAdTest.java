package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.engagement.internal.AdProviderTestHook;
import com.bokrilog.operations.RestrictionFeature;
import org.junit.jupiter.api.Test;

class DashboardAdTest extends AdTestBase {

    private static final String PATH = DashboardAdController.DASHBOARD_AD_PATH;

    @Test
    void 로그인한_사용자는_동의나_동기화_결과_없이_광고_설정을_받는다() throws Exception {
        var signed = signedIn("dashboard-ad");
        for (int read = 0; read < 5; read++) {
            var response = get(PATH, signed.cookie());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(
                    value -> assertThat(value).contains("no-store"));
            assertThat(topLevelFieldsOf(response.body())).containsOnlyKeys("adUnitPath")
                    .containsEntry("adUnitPath", AD_UNIT_PATH);
        }
        assertThat(countOf("engagement_ad_consent_decision", signed.user())).isZero();
        assertThat(countOf("engagement_ad_attempt", signed.user())).isZero();
    }

    @Test
    void 미인증은_거부하고_운영_제한이나_공급자_꺼짐에는_설정을_제공하지_않는다() throws Exception {
        assertThat(get(PATH, null).statusCode()).isEqualTo(401);
        var signed = signedIn("dashboard-ad-stop");

        var restriction = restrictGlobally(RestrictionFeature.ADVERTISING);
        assertThat(get(PATH, signed.cookie()).statusCode()).isEqualTo(204);
        lift(restriction);
        assertThat(get(PATH, signed.cookie()).statusCode()).isEqualTo(200);

        AdProviderTestHook.providerEnabled(this.context, false);
        assertThat(get(PATH, signed.cookie()).statusCode()).isEqualTo(204);
    }
}
