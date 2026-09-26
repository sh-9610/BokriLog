package com.bokrilog.portfolio;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 기본 portfolio와 활성 source membership.
 *
 * <p><b>트랜잭션을 열지 않는다.</b> {@link #attachActiveSource}는 호출자의 경계에 참여하며, 그
 * 경계의 주인은 허가 포트다({@code ActionGrantService}). 연결 저장과 membership 생성이 한
 * 트랜잭션이라는 계약은 그 배치로만 성립한다 — 여기서 새 경계를 열면 "반쪽 상태를 남기지 않는다"가
 * 깨진다.
 */
public interface PortfolioMembershipApi {

    /** MVP의 유일한 기준통화. 변경은 별도 승인이 필요하다. */
    String BASE_CURRENCY_USD = "USD";

    /** 활성 membership 하나의 요약. */
    record ActiveMembership(UUID portfolioId, UUID membershipId) {}

    /** 발행 후보의 한 구성 요소. */
    record FirstComponent(UUID userId, UUID portfolioId, UUID membershipId,
            UUID connectionId, UUID providerAccountId, UUID sourceSnapshotId,
            String decimal, Instant measuredAt, String policyVersion) {}

    /** 공식 portfolio 값만 화면과 후속 계산에 제공한다. */
    record OfficialValue(String decimal, String currency, Instant measuredAt,
            Instant composedAt, Instant publishedAt, UUID snapshotId) {}

    /** The immutable first officially published snapshot and its source provenance. */
    record FirstOfficialSnapshot(UUID portfolioId, UUID snapshotId, String decimal,
            Instant measuredAt, Instant composedAt, Instant publishedAt, UUID membershipId,
            UUID connectionId, UUID providerAccountId, UUID sourceSnapshotId,
            String sourcePolicyVersion, String portfolioPolicyVersion) {}

    /** 주어진 시각에 정확히 하나의 활성 USD membership이 있는가. */
    Optional<ActiveMembership> eligibleMembership(UUID userId, UUID connectionId,
            UUID providerAccountId, Instant measuredAt);

    /** sync의 outer REQUIRED 트랜잭션에 참여한다. 첫 공식값만 CAS로 발행한다. */
    boolean publishFirst(FirstComponent component);

    Optional<OfficialValue> officialValue(@Nullable UUID userId);

    /** First published USD snapshot, independent of the current official pointer. */
    Optional<FirstOfficialSnapshot> firstOfficialSnapshot(@Nullable UUID userId);

    /** The user's currently active source membership, if any. */
    Optional<ActiveSource> activeSource(@Nullable UUID userId);

    record ActiveSource(UUID portfolioId, UUID membershipId, UUID connectionId,
            UUID providerAccountId) {}

    /**
     * 기본 {@code USD} portfolio를 만들거나 재사용하고 그 안에 활성 source 구간을 연다.
     *
     * <p>재사용이 요점이다. 같은 메인 UID로 다시 연결하면 portfolio가 하나 더 생기면 안 되고, 그
     * 규칙의 권위는 애플리케이션의 조회가 아니라 부분 유일 색인이다
     * ({@code portfolio_user_default_currency_key}).
     *
     * <p>구간은 UTC 반개구간이라 겹칠 수 없다. portfolio당 활성 source가 하나라는 규칙도 부분 유일
     * 색인이 강제하므로({@code portfolio_source_membership_active_key}) 동시 요청은 하나만 선다.
     *
     * @param startedAt 구간의 시작. 연결의 검증 시각과 같은 값을 쓴다
     */
    ActiveMembership attachActiveSource(
            UUID userId, UUID connectionId, UUID providerAccountId, Instant startedAt);

    /** 그 사용자의 기본 portfolio. 없으면 빈 값이며 미존재·삭제 계정도 같은 값이다. */
    Optional<UUID> findDefaultPortfolio(@Nullable UUID userId);

    /**
     * 이 사용자의 portfolio 데이터를 지운다. <b>다른 사용자 것은 남는다.</b>
     *
     * <p>{@code identity}의 payload 삭제 <b>앞에</b> 돌아야 한다. 소유 테이블이
     * {@code identity_account}를 {@code ON DELETE RESTRICT}로 가리키므로 순서가 뒤집히면 외래키
     * 위반으로 소리 나게 실패한다({@code V4__source_connection.sql}).
     *
     * <p>멱등하다. 지울 것이 없는 계정에 다시 요청해도 실패가 아니다.
     */
    void erasePortfolioData(@Nullable UUID userId);
}
