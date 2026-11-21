package com.bokrilog.engagement.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bokrilog.engagement.AdEligibilityApi.Decision;
import com.bokrilog.engagement.AdEligibilityApi.SkipReason;
import com.bokrilog.engagement.AdTelemetry;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.sync.SyncJobApi;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 과거 광고 자격 결정의 재전송, 운영 제한과 공급자 판정, 계측 불변식을 확인한다.
 */
class AdEligibilityServiceTest {

    private final UUID user = UUID.randomUUID();

    private final UUID job = UUID.randomUUID();

    private AdAttemptRepository attempts;

    private AccountAccessApi accounts;

    private OperationalRestrictionApi restrictions;

    private SyncJobApi syncs;

    private AdProviderState provider;

    private SimpleMeterRegistry registry;

    private AdEligibilityService service;

    @BeforeEach
    void wire() {
        this.attempts = mock(AdAttemptRepository.class);
        this.accounts = mock(AccountAccessApi.class);
        this.restrictions = mock(OperationalRestrictionApi.class);
        this.syncs = mock(SyncJobApi.class);
        this.provider = new AdProviderState(new EngagementProperties(true, "/1234567/bokrilog_test"));
        this.registry = new SimpleMeterRegistry();
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        this.service = new AdEligibilityService(this.attempts, this.accounts,
                new AdGate(this.restrictions, this.provider), this.syncs, new AdMetrics(this.registry),
                transactions);

        when(this.accounts.lockWriteAccess(this.user)).thenReturn(true);
        when(this.attempts.byResult(this.user, this.job)).thenReturn(Optional.empty());
        when(this.syncs.publishedManualSync(this.user, this.job)).thenReturn(true);
        when(this.restrictions.blocks(anySet(), any(RestrictionSubject.class))).thenReturn(false);
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
    void 운영_제한이_공급자_꺼짐보다_우선한다() {
        this.provider.reinterpret(false);
        when(this.restrictions.blocks(anySet(), any(RestrictionSubject.class))).thenReturn(true);
        assertThat(this.service.decide(this.user, this.job))
                .isEqualTo(new Decision.Skipped(SkipReason.RESTRICTED, false));

        UUID second = UUID.randomUUID();
        when(this.syncs.publishedManualSync(this.user, second)).thenReturn(true);
        when(this.restrictions.blocks(anySet(), any(RestrictionSubject.class))).thenReturn(false);
        assertThat(this.service.decide(this.user, second))
                .isEqualTo(new Decision.Skipped(SkipReason.PROVIDER_DISABLED, false));
        verify(this.attempts, never()).insertEligible(any(), any(), anyString());
        assertCounts(2, 0);
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
    void 자격_재전송의_관문_판독_실패도_자격으로_접지_않는다() {
        when(this.attempts.byResult(this.user, this.job))
                .thenReturn(Optional.of(new AdAttemptRepository.Row(null, "t".repeat(43))));
        when(this.restrictions.blocks(anySet(), any(RestrictionSubject.class)))
                .thenThrow(new IllegalStateException("restriction read failed"));
        assertThatThrownBy(() -> this.service.decide(this.user, this.job)).hasMessage("restriction read failed");
        assertCounts(0, 0);
    }

    @Test
    void 재전송은_후보를_다시_판정하지_않고_과거_건너뜀을_유지한다() {
        when(this.attempts.byResult(this.user, this.job))
                .thenReturn(Optional.of(new AdAttemptRepository.Row(null, "t".repeat(43))));
        assertThat(this.service.decide(this.user, this.job)).isEqualTo(new Decision.Eligible("t".repeat(43), true));
        when(this.restrictions.blocks(anySet(), any(RestrictionSubject.class))).thenReturn(true);
        assertThat(this.service.decide(this.user, this.job))
                .isEqualTo(new Decision.Skipped(SkipReason.RESTRICTED, true));
        // 건너뜀 재전송은 관문도 다시 보지 않는다 — 처음 판정 그대로다.
        UUID skipped = UUID.randomUUID();
        when(this.attempts.byResult(this.user, skipped))
                .thenReturn(Optional.of(new AdAttemptRepository.Row(SkipReason.DAILY_CAP, null)));
        when(this.restrictions.blocks(anySet(), any(RestrictionSubject.class)))
                .thenThrow(new IllegalStateException("must not be read"));
        assertThat(this.service.decide(this.user, skipped)).isEqualTo(new Decision.Skipped(SkipReason.DAILY_CAP, true));

        verify(this.syncs, never()).publishedManualSync(any(), any());
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
        verify(this.restrictions, never()).blocks(anySet(), any(RestrictionSubject.class));
        verify(this.attempts, never()).insertEligible(any(), any(), anyString());
        verify(this.attempts, never()).insertSkipped(any(), any(), any());
        assertCounts(1, 0);
        assertSkip(AdTelemetry.NOT_CANDIDATE, 1);
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
