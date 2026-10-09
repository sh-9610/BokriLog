package com.bokrilog.operations.internal;

import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionScope;
import com.bokrilog.operations.RestrictionSubject;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * {@link OperationalRestrictionApi} 구현. {@code internal}의 package-private 클래스다.
 *
 * <p>트랜잭션을 열지 않는다. 판독은 문장 하나라 그 자체로 원자적이고, 검사 지점은 이 판정을 외부 호출 <b>앞에서</b> 한 번
 * 그리고 최종 저장 트랜잭션 안에서 한 번 더 부른다. 모든 역할에서 배선한다 — 판독은 digest 키가 필요 없다.
 */
@Service
class OperationalRestrictionService implements OperationalRestrictionApi {

    private final OperationalRestrictionRepository repository;

    /** 제한을 만든 operation의 사유·수행자 digest. 원장 표를 아는 자리는 그 repository 하나다. */
    private final OperatorAuditRepository audit;

    OperationalRestrictionService(OperationalRestrictionRepository repository, OperatorAuditRepository audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Override
    public Optional<ActiveRestriction> evaluate(Set<RestrictionFeature> features, RestrictionSubject subject) {
        if (features.isEmpty()) {
            // 아무 기능도 묻지 않는 판독은 배선 실수다. "제한 없음"으로 접지 않는다(fail-closed).
            throw new IllegalArgumentException("막을 기능이 없다.");
        }
        return this.repository.effective(features, subject).map(row -> enrich(List.of(row)).getFirst());
    }

    @Override
    public List<ActiveRestriction> active() {
        return enrich(this.repository.active());
    }

    /** 원장의 완료 행에서 사유·수행자 digest를 채운다. 운영 command가 만들지 않은 옛 행은 비어 있다. */
    private List<ActiveRestriction> enrich(List<OperationalRestrictionRepository.Row> rows) {
        Map<String, OperatorAuditRepository.CompletedFacts> facts = this.audit.completedFacts(
                rows.stream().map(OperationalRestrictionRepository.Row::operationId).filter(Objects::nonNull)
                        .distinct().toList());
        return rows.stream().map(row -> {
            ActiveRestriction base = row.restriction();
            var fact = row.operationId() == null ? null : facts.get(row.operationId());
            return fact == null ? base : new ActiveRestriction(base.restrictionId(), base.feature(), base.scope(),
                    base.target(), base.targetErased(), base.effectiveFrom(), fact.reason(), fact.actorDigest());
        }).toList();
    }

    @Override
    public boolean isActive(RestrictionFeature feature, RestrictionScope scope, String target) {
        return this.repository.isActive(feature, scope, target);
    }
}
