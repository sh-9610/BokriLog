package com.bokrilog.operations.internal;

import com.bokrilog.operations.AdvertisingStopApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionOrigin;
import com.bokrilog.operations.RestrictionScope;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link AdvertisingStopApi} 구현(Story 4.5). {@code internal}의 package-private 클래스다.
 *
 * <p><b>한 커밋.</b> 본보기는 강한 신호의 자동 정지({@link OperationalSignalService})다 — 호출자의 트랜잭션을 멈추고 자기 트랜잭션
 * ({@code REQUIRES_NEW}) 하나에서 원장 잠금 → 열린 전역 {@code ADVERTISING} 제한({@code activeRow}) → 없을 때만 제한 INSERT
 * ({@code origin='PERFORMANCE'}) → 감사 SYSTEM 행을 쓴다. 이미 열린 제한이 있으면(운영자·신호·앞선 판정 무엇이든) 아무것도 쓰지
 * 않는다 — 판정이 5분마다 반복돼도 원장이 불지 않는다.
 *
 * <p><b>경보.</b> 새 제한을 만들었으면 커밋 뒤 WARN 한 줄을 남긴다. 필드는 정책 버전·metric·표본 수·p75뿐이다 — 사용자·식별자가
 * 없다(표본에 애초에 없다).
 */
@Service
class AdvertisingStopService implements AdvertisingStopApi {

    private static final Logger LOG = LoggerFactory.getLogger(AdvertisingStopService.class);

    private static final String AUDIT_COMPLETED = "COMPLETED";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OperationalRestrictionRepository restrictions;

    private final OperatorAuditRepository audit;

    private final ObjectProvider<ActorDigest> digest;

    private final TransactionTemplate requiresNew;

    AdvertisingStopService(OperationalRestrictionRepository restrictions, OperatorAuditRepository audit,
            ObjectProvider<ActorDigest> digest, PlatformTransactionManager transactionManager) {
        this.restrictions = restrictions;
        this.audit = audit;
        this.digest = digest;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Stop stop(Breach breach) {
        ActorDigest keyed = this.digest.getIfAvailable();
        if (keyed == null) {
            throw new IllegalStateException("감사 digest 키가 이 역할에 없어 광고 정지를 기록할 수 없다.");
        }
        Stop stopped = this.requiresNew.execute(status -> record(breach, keyed));
        if (stopped == null) {
            throw new IllegalStateException("광고 정지를 기록하지 못했다.");
        }
        if (stopped instanceof Stop.Created) {
            LOG.warn("advertising performance stop: policy={} metric={} samples={} p75={}", POLICY_VERSION,
                    breach.metric().name(), breach.samples(), breach.p75());
        }
        return stopped;
    }

    private Stop record(Breach breach, ActorDigest keyed) {
        this.audit.lockAppends();
        var existing = this.restrictions.activeRow(RestrictionFeature.ADVERTISING, RestrictionScope.GLOBAL,
                RestrictionScope.GLOBAL_TARGET).orElse(null);
        if (existing != null) {
            return new Stop.AlreadyRestricted(existing.restrictionId());
        }
        String operationId = OperationIds.next();
        UUID restrictionId = UUID.randomUUID();
        Instant effectiveFrom = this.restrictions.insert(restrictionId, RestrictionFeature.ADVERTISING,
                RestrictionScope.GLOBAL, RestrictionScope.GLOBAL_TARGET, null, operationId,
                RestrictionOrigin.PERFORMANCE);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("restrictionId", restrictionId.toString());
        details.put("metric", breach.metric().name());
        details.put("samples", breach.samples());
        details.put("p75", breach.p75());
        details.put("effectiveFrom", effectiveFrom.toString());
        this.audit.appendSystem(operationId, keyed.ofSystem(AUDIT_COMMAND), AUDIT_COMMAND,
                OperatorEnvelopeService.ledgerScope(RestrictionFeature.ADVERTISING, RestrictionScope.GLOBAL),
                RestrictionScope.GLOBAL_TARGET, REASON_PREFIX + breach.metric().name(), AUDIT_COMPLETED,
                POLICY_VERSION, effectiveFrom, operationId, JSON.writeValueAsString(details));
        return new Stop.Created(restrictionId, effectiveFrom);
    }
}
