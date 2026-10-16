package com.bokrilog.engagement.internal;

import com.bokrilog.engagement.AdEligibilityApi;
import com.bokrilog.engagement.AdTransitionApi;
import com.bokrilog.identity.AccountAccessApi;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link AdTransitionApi}의 구현(Story 4.3). {@code internal}의 package-private 클래스다.
 *
 * <p>잠금 순서는 자격 판정과 같다 — 사용자 advisory 잠금(같은 이름) → 계정 생명주기 잠금 → 자격 행 {@code FOR UPDATE}. 같은
 * 사용자의 판정과 소비가 한 줄로 서므로, 같은 참조를 동시에 소비하면 하나만 처음 소비이고 다른 하나는 {@code CONSUMED}다. 관문은
 * {@link AdGate} 한 벌을 자격 판정과 공유한다.
 *
 * <p>로그를 남기지 않는다 — 사용자·transition이 로그로 갈 길을 만들지 않는다. 결과 code만 호출자가 남긴다.
 */
@Service
class AdTransitionService implements AdTransitionApi {

    private static final Duration WINDOW = Duration.ofSeconds(CLAIM_WINDOW_SECONDS);

    private final AdAttemptRepository attempts;

    private final AccountAccessApi accounts;

    private final AdGate gate;

    private final AdProviderState provider;

    private final TransactionTemplate transactions;

    AdTransitionService(AdAttemptRepository attempts, AccountAccessApi accounts, AdGate gate,
            AdProviderState provider, PlatformTransactionManager transactionManager) {
        this.attempts = attempts;
        this.accounts = accounts;
        this.gate = gate;
        this.provider = provider;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public Claim claim(UUID userId, String transition) {
        Claim claimed = this.transactions.execute(status -> {
            this.attempts.lockUser(userId);
            if (!this.accounts.lockWriteAccess(userId)) {
                return new Claim.NotFound();
            }
            var row = this.attempts.transitionForUpdate(userId, transition, WINDOW).orElse(null);
            if (row == null) {
                return new Claim.NotFound();
            }
            if (row.consumed()) {
                // 이미 소비된 행은 그대로 둔다 — 첫 소비 시각이 남는다.
                return new Claim.Skip(SkipReason.CONSUMED);
            }
            Claim decided = judge(userId, row);
            this.attempts.consume(row.id());
            return decided;
        });
        if (claimed == null) {
            throw new IllegalStateException("transition 소비의 결과가 없다.");
        }
        return claimed;
    }

    /** 처음 소비의 판정. 만료가 관문보다 앞선다. 관문 판독 실패는 예외로 올라간다(소비도 롤백된다). */
    private Claim judge(UUID userId, AdAttemptRepository.Transition row) {
        if (row.expired()) {
            return new Claim.Skip(SkipReason.EXPIRED);
        }
        AdEligibilityApi.SkipReason closed = this.gate.firstClosed(userId);
        if (closed != null) {
            return new Claim.Skip(switch (closed) {
                case CONSENT_REQUIRED -> SkipReason.CONSENT_REQUIRED;
                case RESTRICTED -> SkipReason.RESTRICTED;
                case PROVIDER_DISABLED -> SkipReason.PROVIDER_DISABLED;
                // 관문은 빈도 제한 사유를 내지 않는다.
                case INTERVAL, DAILY_CAP -> throw new IllegalStateException("관문이 빈도 제한 사유를 냈다.");
            });
        }
        String adUnitPath = this.provider.adUnitPath();
        // 관문을 지난 직후 공급자가 꺼졌다(시험 훅만 바꾼다). 광고를 시도하지 않는다.
        return adUnitPath == null ? new Claim.Skip(SkipReason.PROVIDER_DISABLED) : new Claim.Show(adUnitPath);
    }
}
