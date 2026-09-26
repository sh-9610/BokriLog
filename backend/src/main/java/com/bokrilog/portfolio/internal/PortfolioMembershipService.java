package com.bokrilog.portfolio.internal;

import com.bokrilog.portfolio.PortfolioMembershipApi;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@link PortfolioMembershipApi} 구현. {@code internal}의 package-private 클래스다.
 *
 * <p>트랜잭션을 열지 않는다. 호출자의 경계에 참여하며 그 경계의 주인은 허가 포트다
 * ({@code SourceConnectionService}와 같은 근거).
 */
@Service
class PortfolioMembershipService implements PortfolioMembershipApi {

    private final PortfolioRepository repository;

    PortfolioMembershipService(PortfolioRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<ActiveMembership> eligibleMembership(UUID userId, UUID connectionId,
            UUID providerAccountId, Instant measuredAt) {
        return this.repository.eligible(userId, connectionId, providerAccountId, measuredAt)
            .map(row -> new ActiveMembership(row.portfolioId(),row.membershipId()));
    }

    @Override
    public boolean publishFirst(FirstComponent component) {
        if (!this.repository.lockEligible(component.userId(),component.portfolioId(),
                component.membershipId(),component.connectionId(),component.providerAccountId(),
                component.measuredAt()) || this.repository.hasOfficial(component.portfolioId())) return false;
        UUID snapshotId=UUID.randomUUID();
        this.repository.insertSnapshot(snapshotId,component.portfolioId(),component.userId(),
                component.decimal(),component.measuredAt());
        this.repository.insertComponent(snapshotId,component.membershipId(),
                component.sourceSnapshotId(),component.portfolioId(),component.userId(),
                component.connectionId(),component.providerAccountId(),component.decimal(),
                component.policyVersion());
        return this.repository.pointFirst(component.portfolioId(),snapshotId)==1;
    }

    @Override
    public Optional<OfficialValue> officialValue(@Nullable UUID userId) {
        return userId==null ? Optional.empty() : this.repository.official(userId);
    }

    @Override
    public Optional<FirstOfficialSnapshot> firstOfficialSnapshot(@Nullable UUID userId) {
        return userId==null ? Optional.empty() : this.repository.firstOfficial(userId);
    }

    @Override
    public Optional<ActiveSource> activeSource(@Nullable UUID userId) {
        return userId==null ? Optional.empty() : this.repository.activeSource(userId);
    }

    @Override
    public ActiveMembership attachActiveSource(
            UUID userId, UUID connectionId, UUID providerAccountId, Instant startedAt) {
        /*
         * 기본 portfolio를 재사용하거나 만든다.
         *
         * 조회와 INSERT 사이는 유일 색인이 닫는다. 동시 요청 둘이 여기 함께 들어오면 하나가
         * `DuplicateKeyException`으로 끝나고 그 예외가 호출자의 트랜잭션을 통째로 되돌린다 —
         * 값으로 접으면 "먼저 세고 나중에 넣는" 형태가 된다(Story 1.11의 관용).
         */
        UUID portfolioId =
                this.repository
                        .findDefaultPortfolio(userId, BASE_CURRENCY_USD)
                        .orElseGet(
                                () -> {
                                    UUID created = UUID.randomUUID();
                                    this.repository.insertDefaultPortfolio(
                                            created, userId, BASE_CURRENCY_USD);
                                    return created;
                                });

        UUID membershipId = UUID.randomUUID();
        this.repository.insertActiveMembership(
                membershipId, portfolioId, connectionId, providerAccountId, startedAt);
        return new ActiveMembership(portfolioId, membershipId);
    }

    @Override
    public Optional<UUID> findDefaultPortfolio(@Nullable UUID userId) {
        return userId == null
                ? Optional.empty()
                : this.repository.findDefaultPortfolio(userId, BASE_CURRENCY_USD);
    }

    @Override
    public void erasePortfolioData(@Nullable UUID userId) {
        if (userId == null) {
            return;
        }
        this.repository.eraseOf(userId);
    }
}
