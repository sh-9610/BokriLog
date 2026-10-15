package com.bokrilog.engagement.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bokrilog.engagement.AdConsentApi;
import com.bokrilog.engagement.AdEligibilityApi.Decision;
import com.bokrilog.engagement.AdEligibilityApi.SkipReason;
import com.bokrilog.engagement.AdTelemetry;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.sync.SyncJobApi;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Story 4.2: 광고 자격 판정의 순서와 fail-closed를 협력자 대역으로 본다. 데이터베이스가 없다 — 실제 저장·잠금·동시성은
 * {@code AdEligibilityTest}가 본다.
 *
 * <ul>
 *   <li>관문은 동의 → 운영 제한 → 공급자 순서이고 처음 걸린 것이 사유다. 앞 관문에 걸리면 뒤 관문을 읽지 않는다.
 *   <li>동의·운영 제한 판독이 실패하면 예외가 그대로 올라가고(fail-closed) 결정 행도 계측도 없다 — 재전송이어도 같다.
 *   <li>계정 거절·비후보는 관문도 빈도 제한도 읽지 않고 쓰지 않는다.
 *   <li>언제나 {@code trigger = reservation + Σ skip}이다.
 * </ul>
 */
class AdEligibilityServiceTest {

    private final UUID user = UUID.randomUUID();

    private final UUID job = UUID.randomUUID();

    private AdAttemptRepository attempts;

    private AccountAccessApi accounts;

    private AdConsentApi consents;

    private OperationalRestrictionApi restrictions;

    private SyncJobApi syncs;

    private AdProviderState provider;

    private SimpleMeterRegistry registry;

    private AdEligibilityService service;

    @BeforeEach
    void wire() {
        this.attempts = mock(AdAttemptRepository.class);
        this.accounts = mock(AccountAccessApi.class);
        this.consents = mock(AdConsentApi.class);
        this.restrictions = mock(OperationalRestrictionApi.class);
        this.syncs = mock(SyncJobApi.class);
        this.provider = new AdProviderState(new EngagementProperties(true));
        this.registry = new SimpleMeterRegistry();
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        this.service = new AdEligibilityService(this.attempts, this.accounts, this.consents, this.restrictions,
                this.syncs, this.provider, new AdMetrics(this.registry), transactions);

        when(this.accounts.lockWriteAccess(this.user)).thenReturn(true);
        when(this.attempts.byResult(this.user, this.job)).thenReturn(Optional.empty());
        when(this.syncs.publishedManualSync(this.user, this.job)).thenReturn(true);
        when(this.consents.current(this.user)).thenReturn(granted());
        when(this.restrictions.blocks(anySet(), any(RestrictionSubject.class))).thenReturn(false);
        when(this.attempts.now()).thenReturn(Instant.parse("2026-10-15T03:00:00Z"));
        when(this.attempts.recentEligible(this.user, AdFrequencyPolicy.DAILY_CAP)).thenReturn(List.of());
    }

    @Test
    void 관문을_모두_지나면_자격이고_reservation을_센다() {
        Decision decision = this.service.decide(this.user, this.job);
        assertThat(decision).isInstanceOf(Decision.Eligible.class);
        String transition = ((Decision.Eligible) decision).transition();
        assertThat(transition).matches("[A-Za-z0-9_-]{43}").doesNotContain(this.user.toString())
                .doesNotContain(this.job.toString());
        verify(this.attempts).insertEligible(this.user, this.job, transition);
        assertCounts(1, 1);
    }

    @Test
    void 관문은_동의_운영_제한_공급자_순서이고_앞에서_걸리면_뒤를_읽지_않는다() {
        this.provider.reinterpret(false);
        when(this.restrictions.blocks(anySet(), any(RestrictionSubject.class))).thenReturn(true);
        when(this.consents.current(this.user)).thenReturn(AdConsentApi.View.undecided());
        assertThat(this.service.decide(this.user, this.job))
                .isEqualTo(new Decision.Skipped(SkipReason.CONSENT_REQUIRED, false));
        verify(this.restrictions, never()).blocks(anySet(), any(RestrictionSubject.class));
        verify(this.attempts, never()).now();
        verify(this.attempts).insertSkipped(this.user, this.job, SkipReason.CONSENT_REQUIRED);

        UUID second = UUID.randomUUID();
        when(this.attempts.byResult(this.user, second)).thenReturn(Optional.empty());
        when(this.syncs.publishedManualSync(this.user, second)).thenReturn(true);
        when(this.consents.current(this.user)).thenReturn(granted());
        assertThat(this.service.decide(this.user, second)).isEqualTo(new Decision.Skipped(SkipReason.RESTRICTED, false));

        UUID third = UUID.randomUUID();
        when(this.attempts.byResult(this.user, third)).thenReturn(Optional.empty());
        when(this.syncs.publishedManualSync(this.user, third)).thenReturn(true);
        when(this.restrictions.blocks(anySet(), any(RestrictionSubject.class))).thenReturn(false);
        assertThat(this.service.decide(this.user, third))
                .isEqualTo(new Decision.Skipped(SkipReason.PROVIDER_DISABLED, false));
        // 관문에 걸린 판정은 빈도 제한을 읽지 않는다(cap을 소비하지 않는다).
        verify(this.attempts, never()).now();
        verify(this.attempts, never()).insertEligible(any(), any(), anyString());
        assertCounts(3, 0);
        assertSkip("CONSENT_REQUIRED", 1);
        assertSkip("RESTRICTED", 1);
        assertSkip("PROVIDER_DISABLED", 1);
    }

    @Test
    void 운영_제한_판독_실패는_예외로_올라가고_아무것도_쓰지_않는다() {
        when(this.restrictions.blocks(anySet(), any(RestrictionSubject.class)))
                .thenThrow(new IllegalStateException("restriction read failed"));
        assertThatThrownBy(() -> this.service.decide(this.user, this.job)).hasMessage("restriction read failed");
        verify(this.attempts, never()).insertEligible(any(), any(), anyString());
        verify(this.attempts, never()).insertSkipped(any(), any(), any());
        assertCounts(0, 0);
    }

    @Test
    void 동의_판독_실패도_예외로_올라가고_아무것도_쓰지_않는다() {
        when(this.consents.current(this.user)).thenThrow(new IllegalStateException("consent read failed"));
        assertThatThrownBy(() -> this.service.decide(this.user, this.job)).hasMessage("consent read failed");
        verify(this.attempts, never()).insertEligible(any(), any(), anyString());
        verify(this.attempts, never()).insertSkipped(any(), any(), any());
        assertCounts(0, 0);
    }

    @Test
    void 자격_재전송의_관문_판독_실패도_자격으로_접지_않는다() {
        when(this.attempts.byResult(this.user, this.job))
                .thenReturn(Optional.of(new AdAttemptRepository.Row(null, "t".repeat(43))));
        when(this.restrictions.blocks(anySet(), any(RestrictionSubject.class)))
                .thenThrow(new IllegalStateException("restriction read failed"));
        assertThatThrownBy(() -> this.service.decide(this.user, this.job)).hasMessage("restriction read failed");
        assertCounts(0, 0);
    }

    @Test
    void 재전송은_관문만_다시_보고_빈도_제한과_후보를_다시_보지_않는다() {
        when(this.attempts.byResult(this.user, this.job))
                .thenReturn(Optional.of(new AdAttemptRepository.Row(null, "t".repeat(43))));
        assertThat(this.service.decide(this.user, this.job)).isEqualTo(new Decision.Eligible("t".repeat(43), true));
        when(this.consents.current(this.user)).thenReturn(AdConsentApi.View.undecided());
        assertThat(this.service.decide(this.user, this.job))
                .isEqualTo(new Decision.Skipped(SkipReason.CONSENT_REQUIRED, true));
        // 건너뜀 재전송은 관문도 다시 보지 않는다 — 처음 판정 그대로다.
        UUID skipped = UUID.randomUUID();
        when(this.attempts.byResult(this.user, skipped))
                .thenReturn(Optional.of(new AdAttemptRepository.Row(SkipReason.DAILY_CAP, null)));
        when(this.consents.current(this.user)).thenThrow(new IllegalStateException("must not be read"));
        assertThat(this.service.decide(this.user, skipped)).isEqualTo(new Decision.Skipped(SkipReason.DAILY_CAP, true));

        verify(this.syncs, never()).publishedManualSync(any(), any());
        verify(this.attempts, never()).now();
        verify(this.attempts, never()).recentEligible(any(), anyInt());
        verify(this.attempts, never()).insertEligible(any(), any(), anyString());
        verify(this.attempts, never()).insertSkipped(any(), any(), any());
        assertCounts(3, 0);
        assertSkip(AdTelemetry.REPLAYED, 3);
    }

    @Test
    void 계정_거절과_비후보는_관문을_읽지_않고_쓰지_않는다() {
        when(this.accounts.lockWriteAccess(this.user)).thenReturn(false);
        assertThat(this.service.decide(this.user, this.job)).isInstanceOf(Decision.Denied.class);
        verify(this.attempts, never()).byResult(any(), any());
        assertCounts(0, 0);

        when(this.accounts.lockWriteAccess(this.user)).thenReturn(true);
        when(this.syncs.publishedManualSync(this.user, this.job)).thenReturn(false);
        assertThat(this.service.decide(this.user, this.job)).isInstanceOf(Decision.NotCandidate.class);
        verify(this.consents, never()).current(any());
        verify(this.restrictions, never()).blocks(anySet(), any(RestrictionSubject.class));
        verify(this.attempts, never()).insertEligible(any(), any(), anyString());
        verify(this.attempts, never()).insertSkipped(any(), any(), any());
        assertCounts(1, 0);
        assertSkip(AdTelemetry.NOT_CANDIDATE, 1);
    }

    @Test
    void 빈도_제한은_관문_뒤의_DB_시각과_최근_자격으로_판정한다() {
        Instant now = Instant.parse("2026-10-15T03:00:00Z");
        when(this.attempts.recentEligible(this.user, AdFrequencyPolicy.DAILY_CAP))
                .thenReturn(List.of(now.minusSeconds(60)));
        assertThat(this.service.decide(this.user, this.job)).isEqualTo(new Decision.Skipped(SkipReason.INTERVAL, false));
        verify(this.attempts).insertSkipped(this.user, this.job, SkipReason.INTERVAL);
        assertCounts(1, 0);
        assertSkip("INTERVAL", 1);
    }

    private static AdConsentApi.View granted() {
        return new AdConsentApi.View(AdConsentApi.State.GRANTED, AdConsentApi.POLICY_VERSION,
                Instant.parse("2026-10-15T00:00:00Z"));
    }

    /** trigger와 reservation의 값, 그리고 {@code trigger = reservation + Σ skip}. */
    private void assertCounts(double triggers, double reservations) {
        assertThat(this.registry.counter(AdTelemetry.TRIGGER).count()).isEqualTo(triggers);
        assertThat(this.registry.counter(AdTelemetry.RESERVATION).count()).isEqualTo(reservations);
        double skips = 0;
        for (String reason : AdTelemetry.SKIP_REASONS) {
            skips += this.registry.counter(AdTelemetry.SKIP, "reason", reason).count();
        }
        assertThat(reservations + skips).isEqualTo(triggers);
    }

    private void assertSkip(String reason, double count) {
        assertThat(this.registry.counter(AdTelemetry.SKIP, "reason", reason).count()).as(reason).isEqualTo(count);
    }
}
