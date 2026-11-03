package com.bokrilog.operations.internal;

import com.bokrilog.operations.AccountErasureState;
import com.bokrilog.operations.OperationalSignalApi;
import com.bokrilog.operations.RestrictionOrigin;
import com.bokrilog.operations.RestrictionScope;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.operations.Signal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link OperationalSignalApi} 구현(Story 3.3). {@code internal}의 package-private 클래스다.
 *
 * <p><b>한 커밋.</b> {@link #raise}는 호출자의 트랜잭션을 멈추고 자기 트랜잭션({@code REQUIRES_NEW}) 하나에서 원장 잠금 →
 * 판정표 읽기 → 중복 판정 → 제한 INSERT → 신호 행 → 감사 SYSTEM 행을 쓴다. 본보기는 3.2의 제한 생성 확정({@code
 * RestrictCommand.complete}, 엔진 템플릿의 잠금 뒤 재검사 → INSERT → {@code COMPLETED})이다. 원장 잠금이 운영 command와
 * 신호를 함께 직렬화하므로 같은 기능·scope·target의 동시 신호는 활성 제한 1행으로 모인다.
 *
 * <p><b>삭제가 시작된 사용자(Epic 3 회고 B-2).</b> 계정 삭제의 operations 비식별도 같은 원장 잠금 아래에서 돈다. 그래서 잠금을
 * 기다린 신호가 깨어났을 때 대상 사용자의 삭제가 이미 커밋됐으면 그 비식별은 지나간 뒤다 — 신호는 정지 기록(제한·신호·감사
 * SYSTEM 행)을 그대로 남기되, 같은 트랜잭션에서 자기가 쓴 귀속을 삭제 port와 같은 본문({@link
 * OperationsDeletionService#eraseAttributed})으로 즉시 비식별한다. 계정 상태는 루트가 구현하는 port({@link
 * AccountErasureState})로 잠금 없이 묻고, 귀속 사용자가 없는 신호는 묻지 않는다. 삭제가 이미 비식별한 같은 대상의 열린 제한은
 * target이 digest이므로 그 digest로도 찾아 잇는다 — 새로 만들면 같은 digest의 열린 제한이 둘이 되어 활성 유일 색인에 걸린다.
 *
 * <p><b>판정표 읽기 실패.</b> 판정표 읽기는 savepoint({@code NESTED}) 안에서 한다 — 그 문장이 실패해도 트랜잭션 전체가
 * 깨지지 않고 신규 연결+동기화 전역 제한으로 이어 쓴다({@code POLICY_UNAVAILABLE}). 행이 없을 때도 같다.
 *
 * <p><b>경보.</b> 커밋 뒤 판정마다 WARN 한 줄을 남긴다. 필드는 신호 종류·정책 버전·{@code <기능>:<scope>}·결과·SLI뿐이다
 * — 식별자·금융값·hash 원문은 없다. metric은 더하지 않는다(Actuator 없음).
 *
 * <p>모든 역할에서 배선한다 — 감지 지점(sync 모듈)이 어느 프로세스에서 돌든 이 port가 있어야 한다. 감사 digest 키는 API
 * 역할에만 있다. 키가 없는 역할에서 부르면 실패한다(조용히 건너뛰면 신호가 사라진다).
 */
@Service
class OperationalSignalService implements OperationalSignalApi {

    private static final Logger LOG = LoggerFactory.getLogger(OperationalSignalService.class);

    /** 결정 행의 감사 결과. 신호의 판정은 하나의 사실로 끝난다. */
    private static final String AUDIT_COMPLETED = "COMPLETED";

    /**
     * 판정 이유. {@code V19} CHECK와 같은 닫힌 집합이다. 판정표의 대안 중 하나({@code POLICY}), 좁은 대안의 대상 차원을 몰라
     * 마지막 전역 대안으로 접음({@code SCOPE_UNKNOWN}), 판정표를 읽지 못함({@code POLICY_UNAVAILABLE}).
     */
    static final String DECISION_POLICY = "POLICY";

    static final String DECISION_SCOPE_UNKNOWN = "SCOPE_UNKNOWN";

    static final String DECISION_POLICY_UNAVAILABLE = OperationalSignalApi.POLICY_UNAVAILABLE;

    /** 계산 정책 버전 target의 모양(예: {@code CALC-1.0}). 아니면 그 차원을 모르는 것으로 본다. */
    private static final Pattern POLICY_VERSION_TEXT = Pattern.compile("[A-Z][A-Z0-9]{0,15}-[0-9]{1,3}\\.[0-9]{1,3}");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SignalRepository signals;

    private final OperationalRestrictionRepository restrictions;

    private final OperatorAuditRepository audit;

    private final ObjectProvider<ActorDigest> digest;

    /** 대상 사용자의 삭제 시작 여부(루트 구현). */
    private final AccountErasureState accounts;

    /** 삭제가 시작된 사용자의 귀속 비식별. 계정 삭제 port와 같은 본문이다. */
    private final OperationsDeletionService deletion;

    private final TransactionTemplate requiresNew;

    private final TransactionTemplate savepoint;

    OperationalSignalService(SignalRepository signals, OperationalRestrictionRepository restrictions,
            OperatorAuditRepository audit, ObjectProvider<ActorDigest> digest, AccountErasureState accounts,
            OperationsDeletionService deletion, PlatformTransactionManager transactionManager) {
        this.signals = signals;
        this.restrictions = restrictions;
        this.audit = audit;
        this.digest = digest;
        this.accounts = accounts;
        this.deletion = deletion;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Override
    public Raised raise(Signal signal) {
        ActorDigest keyed = this.digest.getIfAvailable();
        if (keyed == null) {
            throw new IllegalStateException("감사 digest 키가 이 역할에 없어 신호를 기록할 수 없다.");
        }
        Raised raised = this.requiresNew.execute(status -> record(signal, keyed));
        if (raised == null) {
            throw new IllegalStateException("신호를 기록하지 못했다.");
        }
        // 커밋 뒤의 경보. 허용 필드 다섯만 — 식별자·금융값·hash 원문은 없다(AC-4·AC-6).
        for (Decision decision : raised.decisions()) {
            LOG.warn("strong signal auto-stop: signal={} policy={} restriction={}:{} outcome={} sliMs={}",
                    signal.type().name(), POLICY_VERSION, decision.feature().name(), decision.scope().name(),
                    decision.outcome().name(), decision.sliMs());
        }
        return raised;
    }

    /** 판정된 범위와 그 이유. 감사 사유는 언제나 신호 종류 code다(AC-4) — 판정 이유는 {@code decision}에 있다. */
    private record Choice(List<SignalPolicy.Item> items, String decision) {}

    private Raised record(Signal signal, ActorDigest keyed) {
        // 첫 문장: 이 기록이 시작된 DB 시각. 감지 지점이 감지 시각을 넘기지 않았으면 이것이 감지 시각이다.
        Instant started = this.signals.databaseNow();
        Instant detectedAt = signal.detectedAt() != null && signal.detectedAt().isBefore(started)
                ? signal.detectedAt() : started;
        this.audit.lockAppends();
        Choice choice = choose(signal);
        // 원장 잠금 뒤의 계정 상태. 삭제의 비식별도 이 잠금을 쥐고 커밋하므로, 여기서 보이지 않는 삭제는 이 트랜잭션이
        // 끝난 뒤에야 비식별에 이르고 그때 이 트랜잭션이 쓴 행을 본다. 귀속 사용자가 없는 신호는 묻지 않는다.
        @Nullable UUID targetUser = signal.targetUserId();
        @Nullable UUID erasedOwner = targetUser != null && this.accounts.erasureStarted(targetUser) ? targetUser : null;
        String operationId = OperationIds.next();
        String systemDigest = keyed.ofSystem(AUDIT_COMMAND);
        String evidenceJson = JSON.writeValueAsString(signal.evidence());
        List<Decision> decisions = new ArrayList<>();
        for (SignalPolicy.Item item : choice.items()) {
            String target = targetOf(item.scope(), signal.subject());
            if (target == null) {
                // choose()가 대상 차원을 가진 대안만 고른다. 여기 오면 배선 실수다.
                throw new IllegalStateException("판정된 scope의 대상이 없다.");
            }
            UUID owner = item.scope().userAttributed() ? signal.targetUserId() : null;
            String ledgerTarget = item.scope().userAttributed() ? keyed.ofTarget(item.scope().name(), target) : target;
            var existing = this.restrictions.activeRow(item.feature(), item.scope(), target).orElse(null);
            if (existing == null && erasedOwner != null && item.scope().userAttributed()) {
                // 삭제가 이미 비식별한 같은 대상의 열린 제한(target이 그 digest)에 잇는다.
                existing = this.restrictions.activeRow(item.feature(), item.scope(), ledgerTarget).orElse(null);
            }
            UUID restrictionId;
            Instant effectiveAt;
            Outcome outcome;
            if (existing != null) {
                // 같은 기능·scope·target이 이미 막혀 있다. 새 제한 없이 연결한다. SLI는 그 제한의 효력 시작 기준이다 —
                // 감지보다 먼저 효력 중이었으면 0, 같은 잠금을 기다린 동시 신호가 먼저 만들었으면 그 차다.
                restrictionId = existing.restrictionId();
                effectiveAt = existing.effectiveFrom();
                outcome = Outcome.LINKED;
            } else {
                restrictionId = UUID.randomUUID();
                effectiveAt = this.restrictions.insert(restrictionId, item.feature(), item.scope(), target, owner,
                        operationId, RestrictionOrigin.SIGNAL);
                outcome = Outcome.CREATED;
            }
            long sliMs = Math.max(0, Duration.between(detectedAt, effectiveAt).toMillis());
            UUID signalId = UUID.randomUUID();
            this.signals.insert(new SignalRepository.NewSignal(signalId, operationId, signal.type().name(),
                    POLICY_VERSION, fingerprint(signal, item, ledgerTarget), item.text(), choice.decision(),
                    detectedAt, effectiveAt, evidenceJson, signal.targetUserId(), restrictionId, outcome.name(),
                    sliMs));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("signalId", signalId.toString());
            details.put("policyVersion", POLICY_VERSION);
            details.put("sliMs", sliMs);
            details.put("outcome", outcome.name());
            details.put("scopeDecision", choice.decision());
            this.audit.appendSystem(operationId, systemDigest, AUDIT_COMMAND, item.text(), ledgerTarget,
                    signal.type().name(), AUDIT_COMPLETED, POLICY_VERSION, detectedAt, operationId,
                    JSON.writeValueAsString(details));
            decisions.add(new Decision(signalId, item.feature(), item.scope(), restrictionId, outcome, sliMs));
        }
        if (erasedOwner != null) {
            // 삭제가 먼저 커밋됐다. 정지 기록은 남기고 이 트랜잭션이 쓴 귀속만 같은 커밋에서 비식별한다.
            this.deletion.eraseAttributed(erasedOwner);
        }
        return new Raised(operationId, List.copyOf(decisions));
    }

    /**
     * 판정표가 정한 범위. 행을 읽지 못했거나 없거나 규칙이 깨져 있으면 신규 연결+동기화 전역이다. 증거가 판정표를 채우지
     * 못하면 거절한다 — 이 트랜잭션은 아무것도 쓰지 않았다.
     */
    private Choice choose(Signal signal) {
        // 코드가 아는 최소 필수 증거는 판정표보다 먼저 본다 — 빈 신호가 판정표 장애를 타고 전역 정지로 승격하지 않게.
        if (!signal.evidence().keySet().containsAll(signal.type().requiredEvidence())) {
            throw new IllegalArgumentException("필수 증거가 빠졌다.");
        }
        SignalPolicy policy = readPolicy(signal);
        if (policy == null || policy.alternatives().isEmpty()) {
            return new Choice(SignalPolicy.UNAVAILABLE_FALLBACK, DECISION_POLICY_UNAVAILABLE);
        }
        String refusal = policy.refusal(signal.evidence());
        if (refusal != null) {
            throw new IllegalArgumentException(refusal);
        }
        List<List<SignalPolicy.Item>> alternatives = policy.alternatives();
        for (int index = 0; index < alternatives.size(); index++) {
            List<SignalPolicy.Item> alternative = alternatives.get(index);
            if (alternative.stream().allMatch(item -> usable(item.scope(), signal))) {
                // 좁은 대안이 있었는데 마지막(전역)까지 왔으면 범위를 특정하지 못한 것이다(AC-2).
                boolean unknown = index == alternatives.size() - 1 && alternatives.size() > 1;
                return new Choice(alternative, unknown ? DECISION_SCOPE_UNKNOWN : DECISION_POLICY);
            }
        }
        // parse()가 마지막 대안을 전역으로 고정했으므로 오지 않는다.
        throw new IllegalStateException("판정표의 마지막 대안이 성립하지 않는다.");
    }

    @Override
    public java.util.Set<UUID> restrictionsRaisedBy(UUID jobId) {
        return this.signals.restrictionsRaisedBy(jobId);
    }

    /** savepoint 안의 판정표 읽기. 읽기가 실패하면 {@code null} — 바깥 트랜잭션은 이어서 전역 제한을 쓴다. */
    private @Nullable SignalPolicy readPolicy(Signal signal) {
        try {
            return this.savepoint.execute(status -> this.signals.policy(POLICY_VERSION, signal.type().name())
                    .orElse(null));
        } catch (DataAccessException unreadable) {
            // 메시지·원인은 남기지 않는다 — SQL이 실린다. 종류만.
            LOG.atWarn().addKeyValue("exception", unreadable.getClass().getSimpleName())
                    .log("signal policy unreadable: signal={}", signal.type().name());
            return null;
        }
    }

    /** 신호가 그 scope의 대상을 가졌는가. 사용자 귀속 차원은 소유자까지 있어야 쓸 수 있다. */
    private static boolean usable(RestrictionScope scope, Signal signal) {
        if (targetOf(scope, signal.subject()) == null) {
            return false;
        }
        return !scope.userAttributed() || signal.targetUserId() != null;
    }

    /** 그 scope의 target. 차원이 없으면 {@code null}이다. */
    private static @Nullable String targetOf(RestrictionScope scope, RestrictionSubject subject) {
        return switch (scope) {
            case GLOBAL -> RestrictionScope.GLOBAL_TARGET;
            case PLAN -> text(subject.planId());
            case PORTFOLIO -> text(subject.portfolioId());
            case PROVIDER_CONNECTION -> text(subject.connectionId());
            case CALCULATION_POLICY_VERSION -> subject.calculationPolicy() != null
                    && POLICY_VERSION_TEXT.matcher(subject.calculationPolicy()).matches()
                            ? subject.calculationPolicy() : null;
        };
    }

    /** fingerprint: 신호 종류·정책 버전·{@code <기능>:<scope>}·target(사용자 귀속이면 keyed digest)의 SHA-256. */
    private static String fingerprint(Signal signal, SignalPolicy.Item item, String ledgerTarget) {
        return sha256(signal.type().name() + "|" + POLICY_VERSION + "|" + item.text() + "|" + ledgerTarget);
    }

    private static @Nullable String text(@Nullable UUID id) {
        return id == null ? null : id.toString();
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
