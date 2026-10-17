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
 * {@link AdEligibilityApi}의 구현(Story 4.2). {@code internal}의 package-private 클래스다.
 *
 * <p><b>판정은 트랜잭션 하나다.</b> 사용자 advisory 잠금 → 계정 생명주기 잠금({@code lockWriteAccess}, 거짓이면 쓰지 않는다) →
 * 같은 결과의 앞선 결정(있으면 재전송) → 후보({@link SyncJobApi#publishedManualSync}) → 관문(동의 → 전역 {@code ADVERTISING}
 * 운영 제한 → 공급자 활성, 처음 걸린 것이 사유 — {@link AdGate}가 transition 소비와 공유한다) → 빈도 제한
 * ({@link AdFrequencyPolicy}, 기준은 DB 시각) → 결정 행 INSERT. 잠금 순서(사용자 잠금 → 생명주기)는 동의 결정과 같다. 같은
 * 사용자의 판정이 잠금으로 줄 서므로 동시 탭 가운데 하나만 자격을 얻고, 나중 탭은 잠금을 얻은 뒤의 문장에서 앞선 자격을 본다.
 *
 * <p><b>판독 실패는 예외다(fail-closed).</b> 동의·운영 제한 판독이 실패하면 트랜잭션 전체가 롤백되고 호출자가 오류로 답한다 — 읽지
 * 못한 것을 "제한 없음"·"동의함"으로 접지 않는다.
 *
 * <p><b>삭제는 사용자 잠금을 쥐지 않는다.</b> 동의 결정과 같은 이유다({@link EngagementDeletionService}) — 판정은 생명주기 잠금을
 * 얻은 뒤에야 쓰고, 그때 삭제가 커밋돼 있으면 쓰기가 막힌다.
 *
 * <p>로그를 남기지 않는다 — 사용자·결과·transition이 로그로 갈 길을 만들지 않는다. 결과 code만 호출자가 남긴다. 계측은 커밋 뒤에
 * 센다({@link AdMetrics}).
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
                Decision replayed = replay(userId, existing);
                this.metrics.skipped(AdTelemetry.REPLAYED);
                return replayed;
            }
            if (!this.syncs.publishedManualSync(userId, syncJobId)) {
                this.metrics.skipped(AdTelemetry.NOT_CANDIDATE);
                return new Decision.NotCandidate();
            }
            SkipReason reason = this.gate.firstClosed(userId);
            if (reason == null) {
                reason = AdFrequencyPolicy.judge(this.attempts.now(),
                        this.attempts.recentEligible(userId, AdFrequencyPolicy.DAILY_CAP)).orElse(null);
            }
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

    /**
     * 같은 결과의 앞선 결정. 건너뜀은 처음 판정 그대로다. 자격은 관문만 다시 본다 — 걸리면 그 사유의 건너뜀이고 행은 그대로다(동의
     * 철회·운영 정지가 다음 요청부터 바로 적용된다). 빈도 제한은 다시 보지 않는다 — 그 자격이 이미 그 결과의 노출 시도다.
     */
    private Decision replay(UUID userId, AdAttemptRepository.Row existing) {
        if (existing.skipReason() != null) {
            return new Decision.Skipped(existing.skipReason(), true);
        }
        String transition = existing.transitionRef();
        if (transition == null) {
            throw new IllegalStateException("자격 행에 transition 참조가 없다.");
        }
        SkipReason closed = this.gate.firstClosed(userId);
        return closed == null ? new Decision.Eligible(transition, true) : new Decision.Skipped(closed, true);
    }

    private static String newTransition() {
        byte[] bytes = new byte[TRANSITION_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
