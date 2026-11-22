package com.bokrilog.engagement.internal;

import com.bokrilog.engagement.AdEligibilityApi;
import com.bokrilog.engagement.AdTelemetry;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.sync.SyncJobApi;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 이전 결과별 광고 판정. HTTP 경로는 폐기됐으며 기존 광고 기록의 계정 삭제·계측 회귀에서 쓰는 기록을 만든다.
 * 운영 제한과 공급자 상태를 공통 관문에서 읽으며 동의나 빈도를 판정하지 않는다.
 */
@Service
class AdEligibilityService implements AdEligibilityApi {

    /**
     * transition 참조의 바이트 수(256비트). base64url 무패딩으로 43자다(스키마 CHECK와 같다). 발급 길이와 모양의 대조는
     * {@code AdSharedValuesTest}가 시험 문({@code AdProviderTestHook})으로 하므로 package-private이다.
     */
    static final int TRANSITION_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdAttemptRepository attempts;

    private final AccountAccessApi accounts;

    private final AdGate gate;

    private final SyncJobApi syncs;

    private final AdMetrics metrics;

    private final TransactionTemplate transactions;

    AdEligibilityService(AdAttemptRepository attempts, AccountAccessApi accounts, AdGate gate, SyncJobApi syncs,
            AdMetrics metrics, PlatformTransactionManager transactionManager) {
        this.attempts = attempts;
        this.accounts = accounts;
        this.gate = gate;
        this.syncs = syncs;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public Decision decide(UUID userId, UUID syncJobId) {
        Decision decided = this.transactions.execute(status -> {
            this.attempts.lockUser(userId);
            if (!this.accounts.lockWriteAccess(userId)) {
                return new Decision.Denied();
            }
            var existing = this.attempts.byResult(userId, syncJobId).orElse(null);
            if (existing != null) {
                // 관문 판독이 실패하면 여기까지 오지 않는다 — 세지 않는다.
                Decision replayed = replay(existing);
                this.metrics.skipped(AdTelemetry.REPLAYED);
                return replayed;
            }
            if (!this.syncs.publishedManualSync(userId, syncJobId)) {
                this.metrics.skipped(AdTelemetry.NOT_CANDIDATE);
                return new Decision.NotCandidate();
            }
            SkipReason reason = this.gate.firstClosed();
            if (reason != null) {
                this.attempts.insertSkipped(userId, syncJobId, reason);
                this.metrics.skipped(reason.name());
                return new Decision.Skipped(reason, false);
            }
            String transition = newTransition();
            this.attempts.insertEligible(userId, syncJobId, transition);
            this.metrics.reserved();
            return new Decision.Eligible(transition, false);
        });
        if (decided == null) {
            throw new IllegalStateException("광고 자격 판정의 결과가 없다.");
        }
        return decided;
    }

    /** 같은 결과의 기존 결정을 돌려준다. 자격 행은 광고 관문을 다시 확인한다. */
    private Decision replay(AdAttemptRepository.Row existing) {
        if (existing.skipReason() != null) {
            return new Decision.Skipped(existing.skipReason(), true);
        }
        String transition = existing.transitionRef();
        if (transition == null) {
            throw new IllegalStateException("자격 행에 transition 참조가 없다.");
        }
        SkipReason closed = this.gate.firstClosed();
        return closed == null ? new Decision.Eligible(transition, true) : new Decision.Skipped(closed, true);
    }

    private static String newTransition() {
        byte[] bytes = new byte[TRANSITION_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
