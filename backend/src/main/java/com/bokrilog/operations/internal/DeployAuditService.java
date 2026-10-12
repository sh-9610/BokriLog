package com.bokrilog.operations.internal;

import com.bokrilog.operations.DeployAuditApi;
import com.bokrilog.operations.OperationalRestrictionApi;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link DeployAuditApi} 구현(Story 3.8 AC-5). {@code internal}의 package-private 클래스다.
 *
 * <p>트랜잭션 하나에서 원장 추가 잠금 → 활성 제한 수 읽기 → SYSTEM 행 INSERT를 한다. SQL은 원장 repository 한 곳에 있다
 * ({@link OperatorAuditRepository}). 제한 표는 읽기만 한다({@link OperationalRestrictionApi#active()}).
 *
 * <p>모든 역할에서 배선하지만 실제로 부르는 것은 일회성 {@code deploy-audit} 역할뿐이다. 감사 digest 키가 없는 역할(migrate)에서
 * 부르면 실패한다 — 수행자 digest 없이 행을 쓰지 않는다.
 */
@Service
class DeployAuditService implements DeployAuditApi {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OperatorAuditRepository audit;

    private final OperationalRestrictionApi restrictions;

    private final ObjectProvider<ActorDigest> digest;

    private final TransactionTemplate transaction;

    DeployAuditService(OperatorAuditRepository audit, OperationalRestrictionApi restrictions,
            ObjectProvider<ActorDigest> digest, PlatformTransactionManager transactionManager) {
        this.audit = audit;
        this.restrictions = restrictions;
        this.digest = digest;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public Recorded record(Event event) {
        ActorDigest keyed = this.digest.getIfAvailable();
        if (keyed == null) {
            throw new IllegalStateException("감사 digest 키가 이 역할에 없어 배포 감사를 기록할 수 없다.");
        }
        require(event.operationId(), OPERATION_ID, "operation ID");
        require(event.actor(), ACTOR, "수행자");
        if (event.outcome() == Outcome.REQUESTED && event.step() != Step.GATE) {
            throw new IllegalArgumentException("REQUESTED는 GATE 단계에만 쓴다.");
        }
        boolean gateRefused = event.step() == Step.GATE && event.outcome() == Outcome.FAILED;
        String given = event.target() == null || event.target().isBlank() ? null : event.target();
        if (given == null && !gateRefused) {
            // 형식이 틀려 대상을 남길 수 없는 경우는 gate 거부 하나다. 나머지 단계는 언제나 검증된 digest를 가진다.
            throw new IllegalArgumentException("대상이 비었다(GATE FAILED에서만 비울 수 있다).");
        }
        String target = given == null ? INVALID_TARGET : require(given, IMAGE_REFERENCE, "대상");
        String code = event.code() == null ? null : require(event.code(), CODE, "code");
        String previous = event.previous() == null ? null : require(event.previous(), IMAGE_REFERENCE, "이전 대상");
        String actorDigest = keyed.ofDeployer(event.actor());
        Recorded recorded = this.transaction.execute(status -> {
            this.audit.lockAppends();
            int active = this.restrictions.active().size();
            Map<String, Object> details = new LinkedHashMap<>();
            if (code != null) {
                details.put("code", code);
            }
            if (event.slot() != null) {
                details.put("slot", event.slot().name().toLowerCase(Locale.ROOT));
            }
            if (previous != null) {
                details.put("previous", previous);
            }
            if (event.rehearsal()) {
                details.put("rehearsal", true);
            }
            details.put("activeRestrictions", active);
            this.audit.append(new OperatorAuditRepository.NewRow(event.operationId(), null, actorDigest,
                    OperatorAuditRepository.ACTOR_SYSTEM, COMMAND, SCOPE, target, event.step().name(),
                    event.outcome().name(), POLICY_VERSION, null, event.outcome() != Outcome.REQUESTED,
                    event.operationId(), JSON.writeValueAsString(details)));
            return new Recorded(this.audit.maxSequence(), active);
        });
        if (recorded == null) {
            throw new IllegalStateException("배포 감사를 기록하지 못했다.");
        }
        return recorded;
    }

    private static String require(@Nullable String value, Pattern shape, String name) {
        if (value == null || !shape.matcher(value).matches()) {
            throw new IllegalArgumentException(name + "의 모양이 맞지 않는다.");
        }
        return value;
    }
}
