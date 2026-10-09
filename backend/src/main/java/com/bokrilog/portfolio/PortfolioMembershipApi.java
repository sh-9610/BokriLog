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

    /**
     * 공식 portfolio 값만 화면과 후속 계산에 제공한다.
     *
     * @param publicationSequence 이 값을 가리키는 현재 포인터의 순번. 다음 발행이 CAS 기대값으로 쓴다
     */
    record OfficialValue(String decimal, String currency, Instant measuredAt,
            Instant composedAt, Instant publishedAt, UUID snapshotId, long publicationSequence) {}

    /** 새 공식 snapshot의 구성 요소 하나. MVP는 정확히 하나다. */
    record CandidateComponent(UUID userId, UUID portfolioId, UUID membershipId,
            UUID connectionId, UUID providerAccountId, UUID sourceSnapshotId,
            String decimal, Instant measuredAt, String policyVersion) {}

    /**
     * {@link #publishNext}의 결과. 거절 둘은 원인이 달라 호출자가 다른 이유 코드로 끝낸다 — membership
     * 구성이 바뀐 것과, 그사이 포인터가 다른 값으로 움직인 것은 사용자에게 다른 다음 행동이다.
     */
    sealed interface NextPublication
            permits PublishedSnapshot, NextPublication.MembershipChanged, NextPublication.PointerMoved {
        /** membership이 그 측정 시각을 덮는 활성 USD 구간이 아니다. 아무것도 쓰지 않았다. */
        record MembershipChanged() implements NextPublication {}
        /** 포인터가 기대한 snapshot·순번이 아니다. 아무것도 쓰지 않았다. */
        record PointerMoved() implements NextPublication {}
    }

    /** 발행된 새 공식 snapshot. */
    record PublishedSnapshot(UUID snapshotId, long publicationSequence) implements NextPublication {}

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

    /**
     * 후보 component로 새 공식 snapshot을 만들고 portfolio 포인터를 한 칸 전진시킨다(Story 2.7).
     *
     * <p><b>호출자 트랜잭션 전용이다.</b> 실제 트랜잭션이 없으면 {@link IllegalStateException}을
     * 던진다 — 스스로 경계를 열면 "계획·계산·포인터가 한 묶음으로만 발행된다"가 이 모듈에서 깨진다.
     *
     * <p>포인터는 {@code publication_sequence = expectedSequence + 1} CAS로만 움직인다. membership이 그
     * 측정 시각을 {@code [started_at, ended_at)}로 덮는 활성 USD 구간이 아니면
     * {@link NextPublication.MembershipChanged}, 기대한 snapshot과 순번이 현재가 아니면
     * {@link NextPublication.PointerMoved}이고 둘 다 아무것도 쓰지 않는다. 거절을 받은 호출자는
     * 트랜잭션을 되돌린다.
     */
    NextPublication publishNext(CandidateComponent component, UUID expectedSnapshotId, long expectedSequence);

    Optional<OfficialValue> officialValue(@Nullable UUID userId);

    /** First published USD snapshot, independent of the current official pointer. */
    Optional<FirstOfficialSnapshot> firstOfficialSnapshot(@Nullable UUID userId);

    /** 특정 소유 snapshot의 불변 구성 provenance. 현재 포인터와 독립이다. */
    Optional<FirstOfficialSnapshot> ownedSnapshot(UUID userId, UUID snapshotId);

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

    /**
     * 그 연결의 활성 source 구간을 {@code endedAt}으로 닫는다(Story 2.16). <b>호출자 트랜잭션 전용이다.</b>
     *
     * <p>portfolio 자체와 이미 닫힌 과거 구간은 건드리지 않는다. snapshot 구성이 가리키는 membership 행도
     * 지우지 않으므로 기존 공식 결과의 provenance는 그대로 남는다.
     *
     * @return 닫은 구간 수. 그 연결에 활성 구간이 없었으면 0이다
     */
    int endSourceMembership(UUID userId, UUID connectionId, Instant endedAt);

    /**
     * 그 연결의 활성 source 구간이 시작된 시각(Story 2.16). 없으면 빈 값이다. 해제 시각이 구간 시작보다 늦도록
     * ({@code ended_at > started_at}) 호출자가 하한으로 쓴다.
     */
    Optional<Instant> activeMembershipStart(UUID userId, UUID connectionId);

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

    // ------------------------------------------------------------------ Story 3.2

    /**
     * 운영 제한의 대상 확인에 쓰는 portfolio 하나(Story 3.2). 사용자 없이 id로 찾는다 — 운영자 표면이 부르며, 응답·로그에
     * 원식별자를 내지 않는 것은 호출자 책임이다.
     *
     * @param userId 소유자. 제한 행의 귀속이다
     * @param hasActiveSource 지금 활성 source membership이 있는가
     */
    record PortfolioTarget(UUID portfolioId, UUID userId, boolean hasActiveSource) {}

    /** 그 portfolio. 없으면 빈 값이다. */
    Optional<PortfolioTarget> portfolioTarget(UUID portfolioId);
}
