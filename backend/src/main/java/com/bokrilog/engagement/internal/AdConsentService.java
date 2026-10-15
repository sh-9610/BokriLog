package com.bokrilog.engagement.internal;

import com.bokrilog.engagement.AdConsentApi;
import com.bokrilog.engagement.EngagementDeletionApi;
import com.bokrilog.identity.AccountAccessApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link AdConsentApi}와 {@link EngagementDeletionApi}의 구현(Story 4.1). {@code internal}의 package-private 클래스다.
 *
 * <p><b>결정은 트랜잭션 하나다.</b> 사용자 advisory 잠금 → 계정 생명주기 잠금({@code lockWriteAccess}, 거짓이면 쓰지 않는다)
 * → 같은 키의 앞선 결정(같은 입력이면 재전송, 다른 입력이면 충돌) → 지금 상태와 같은 선택이면 쓰지 않음 → INSERT 한 문장.
 * 잠금 순서(사용자 잠금 → 생명주기)는 계획 조건 확정({@code PlanConditionService})과 같다.
 *
 * <p><b>삭제는 사용자 잠금을 쥐지 않는다.</b> 계정 삭제 orchestrator는 생명주기 행을 먼저 잠근 트랜잭션에서 부르므로, 여기서
 * 사용자 잠금을 다시 잡으면 "사용자 잠금 → 생명주기"를 기다리는 결정 트랜잭션과 서로를 기다린다. 잠그지 않아도 행이 다시 생기지
 * 않는다 — 결정 트랜잭션은 생명주기 잠금을 얻은 뒤에야 쓰고, 그때는 삭제 전환이 커밋돼 쓰기가 막혀 있다. 반대로 결정이 먼저
 * 생명주기를 쥐었으면 삭제가 그 커밋을 기다린 뒤 그 행까지 지운다.
 *
 * <p>로그를 남기지 않는다 — 사용자·선택·키가 로그로 갈 길을 만들지 않는다. 결과 code만 호출자가 남긴다.
 */
@Service
class AdConsentService implements AdConsentApi, EngagementDeletionApi {

    private final AdConsentRepository decisions;

    private final AccountAccessApi accounts;

    private final TransactionTemplate transactions;

    private final TransactionTemplate readOnly;

    AdConsentService(AdConsentRepository decisions, AccountAccessApi accounts,
            PlatformTransactionManager transactionManager) {
        this.decisions = decisions;
        this.accounts = accounts;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    // ------------------------------------------------------------------ 판독

    @Override
    public View current(UUID userId) {
        View view = this.readOnly.execute(status -> currentOf(userId));
        if (view == null) {
            // 판독 실패를 미결정으로 숨기지 않는다 — 미결정은 "결정이 없다"는 사실이지 "읽지 못했다"가 아니다.
            throw new IllegalStateException("동의 상태를 읽지 못했다.");
        }
        return view;
    }

    /** 최신 행이 지금 상태다. 행이 없거나 옛 정책 버전이면 미결정이다(자동 재동의 없음). */
    private View currentOf(UUID userId) {
        return this.decisions.latest(userId).filter(row -> POLICY_VERSION.equals(row.policyVersion()))
                .map(row -> new View(stateOf(row.choice()), POLICY_VERSION, row.decidedAt()))
                .orElseGet(View::undecided);
    }

    private static State stateOf(Choice choice) {
        return switch (choice) {
            case GRANTED -> State.GRANTED;
            case DENIED -> State.DENIED;
        };
    }

    // ------------------------------------------------------------------ 결정

    @Override
    public Decision decide(UUID userId, String idempotencyKey, Choice choice, String policyVersion) {
        // 사용자가 본 설명과 저장되는 정책이 어긋나면 받지 않는다. 쓰기 전에 거절하므로 잠금도 필요 없다.
        if (!POLICY_VERSION.equals(policyVersion)) {
            return new Decision.PolicyChanged();
        }
        String keyDigest = sha256("idempotency-key\n" + idempotencyKey);
        String requestDigest = sha256("ad-consent\n" + choice.name() + "\n" + policyVersion);
        Decision decided = this.transactions.execute(status -> {
            this.decisions.lockUser(userId);
            if (!this.accounts.lockWriteAccess(userId)) {
                return new Decision.Denied();
            }
            var existing = this.decisions.byKey(userId, keyDigest).orElse(null);
            if (existing != null) {
                if (!existing.requestDigest().equals(requestDigest)) {
                    return new Decision.Conflict();
                }
                // 그 키의 결정은 이미 반영됐다. 본문은 지금 상태다 — 그 사이 다른 결정이 있었으면 그것이 보인다.
                return new Decision.Saved(currentOf(userId), true);
            }
            View current = currentOf(userId);
            if (current.state() == stateOf(choice)) {
                // 같은 선택이다. 행을 더하지 않는다(키도 남지 않는다 — 효과가 없었던 요청이다).
                return new Decision.Saved(current, false);
            }
            var inserted = this.decisions.insert(userId, POLICY_VERSION, choice, keyDigest, requestDigest);
            return new Decision.Saved(new View(stateOf(inserted.choice()), POLICY_VERSION, inserted.decidedAt()),
                    false);
        });
        if (decided == null) {
            throw new IllegalStateException("동의 결정의 결과가 없다.");
        }
        return decided;
    }

    // ------------------------------------------------------------------ 삭제 port

    @Override
    public int eraseConsents(@Nullable UUID userId) {
        if (userId == null) {
            return 0;
        }
        Integer erased = this.transactions.execute(status -> this.decisions.eraseUser(userId));
        return erased == null ? 0 : erased;
    }

    @Override
    public long remainingRowsOf(UUID userId) {
        return this.decisions.remainingRowsOf(userId);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다.", impossible);
        }
    }
}
