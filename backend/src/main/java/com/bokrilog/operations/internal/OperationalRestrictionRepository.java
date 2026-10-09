package com.bokrilog.operations.internal;

import com.bokrilog.operations.OperationalRestrictionApi.ActiveRestriction;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionOrigin;
import com.bokrilog.operations.RestrictionScope;
import com.bokrilog.operations.RestrictionSubject;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code operations_restriction}에 닿는 유일한 자리(Story 3.2).
 *
 * <p>유효 구간 판정은 언제나 술어 안의 {@code statement_timestamp()}다. 행을 읽어 자바가 시각을 비교하면 판정과 조회 사이에
 * 창이 생기고 그 창의 넓이는 인스턴스 시계의 차이만큼이다. "지금"이 {@code now()}가 아닌 이유: {@code now()}는 트랜잭션이
 * 시작된 시각이라, 잠금을 기다리며 오래 열려 있던 트랜잭션(Story 2.7의 발행)은 그 뒤에 커밋된 제한의
 * {@code effective_from}보다 이른 시각으로 판정해 제한을 보지 못한다.
 *
 * <p>V4의 {@code command} 열은 읽지도 쓰지도 않는다(V18이 되채웠다).
 */
@Repository
class OperationalRestrictionRepository {

    /** 효력 구간 술어. */
    private static final String EFFECTIVE = "statement_timestamp() >= r.effective_from "
            + "AND (r.effective_until IS NULL OR statement_timestamp() < r.effective_until)";

    /** 구체성 순서. {@link RestrictionScope#specificity()}에서 만든다 — 두 벌이 갈라지지 않는다. */
    private static final String SPECIFICITY = "CASE r.scope "
            + java.util.Arrays.stream(RestrictionScope.values())
                    .map(scope -> "WHEN '" + scope.name() + "' THEN " + scope.specificity())
                    .collect(Collectors.joining(" "))
            + " END";

    /** 보고 순서: 구체성 → 먼저 시작한 것 → 제한 ID. 판독과 목록이 같은 규칙을 쓴다. */
    private static final String ORDER = SPECIFICITY + " DESC, r.effective_from, r.restriction_id";

    /**
     * 제한 행. 그것을 만든 운영 command의 사유·수행자 digest는 원장의 사실이라 여기서 읽지 않는다 — 원장 표를 문자열로 아는
     * 자리는 {@link OperatorAuditRepository} 하나다(Story 3.1). 서비스가 {@code operation_id}로 거기서 채운다.
     */
    private static final String SELECT = "SELECT r.restriction_id, r.feature, r.scope, r.target, r.target_erased_at, "
            + "r.effective_from, r.operation_id, r.origin, "
            + "(SELECT count(*) FROM operations_signal s WHERE s.restriction_id = r.restriction_id) AS linked_signals "
            + "FROM operations_restriction r ";

    /** 제한 행 하나와 그것을 만든 operation. 사유·수행자는 아직 비어 있다. */
    record Row(ActiveRestriction restriction, @Nullable String operationId) {}

    private final JdbcClient jdbc;

    OperationalRestrictionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 그 기능들의 효력 중인 제한 가운데 그 대상에 일치하는 가장 구체적인 행. 문장 하나라 그 자체로 원자적이다.
     *
     * <p>넘기지 않은 차원({@code null})의 구체 제한은 일치하지 않는다. 비식별된 target은 원식별자와 같을 수 없다.
     * {@code excluding}의 제한은 고르지 않는다(Story 3.3 — 신호를 낸 작업의 자기 제한).
     */
    Optional<Row> effective(Set<RestrictionFeature> features, RestrictionSubject subject, Set<UUID> excluding) {
        return this.jdbc.sql(SELECT + "WHERE r.feature IN (:features) AND " + EFFECTIVE
                        + " AND NOT (r.restriction_id = ANY(CAST(:excluded AS uuid[]))) AND (r.scope = 'GLOBAL' "
                        + "OR (r.target_erased_at IS NULL AND ("
                        + "(r.scope = 'PLAN' AND r.target = CAST(:plan AS text)) "
                        + "OR (r.scope = 'PORTFOLIO' AND r.target = CAST(:portfolio AS text)) "
                        + "OR (r.scope = 'PROVIDER_CONNECTION' AND r.target = CAST(:connection AS text)) "
                        + "OR (r.scope = 'CALCULATION_POLICY_VERSION' AND r.target = CAST(:policy AS text))))) "
                        + "ORDER BY " + ORDER + " LIMIT 1")
                .param("features", features.stream().map(Enum::name).toList())
                .param("plan", text(subject.planId()))
                .param("portfolio", text(subject.portfolioId()))
                .param("connection", text(subject.connectionId()))
                .param("policy", subject.calculationPolicy())
                .param("excluded", uuidArray(excluding))
                .query(OperationalRestrictionRepository::row)
                .optional();
    }

    /** 지금 효력 중인 모든 제한. 순서는 {@link #ORDER} 하나다 — {@link #effective}가 고르는 행이 목록의 첫 행과 같다. */
    List<Row> active() {
        return this.jdbc.sql(SELECT + "WHERE " + EFFECTIVE + " ORDER BY " + ORDER)
                .query(OperationalRestrictionRepository::row)
                .list();
    }

    /** 같은 기능·scope·target의 효력 중인 행이 있는가. 운영 command가 만들지 않은 옛 행도 센다. */
    boolean isActive(RestrictionFeature feature, RestrictionScope scope, String target) {
        Boolean exists = this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM operations_restriction r WHERE r.feature = :feature "
                        + "AND r.scope = :scope AND r.target = :target AND " + EFFECTIVE + ")")
                .param("feature", feature.name())
                .param("scope", scope.name())
                .param("target", target)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 같은 기능·scope·target의 <b>끝이 정해지지 않은</b> 효력 중인 행 하나(Story 3.3) — 먼저 시작한 것. 신호가 새 제한 대신
     * 연결할 행이다. 끝이 예정된 행에는 연결하지 않는다 — 그 끝과 함께 신호의 차단이 사라진다. 운영 command가 만들지 않은
     * 옛 행도 고른다.
     */
    Optional<Existing> activeRow(RestrictionFeature feature, RestrictionScope scope, String target) {
        return this.jdbc.sql("SELECT r.restriction_id, r.effective_from FROM operations_restriction r "
                        + "WHERE r.feature = :feature AND r.scope = :scope AND r.target = :target "
                        + "AND r.effective_until IS NULL AND statement_timestamp() >= r.effective_from "
                        + "ORDER BY r.effective_from, r.restriction_id LIMIT 1")
                .param("feature", feature.name())
                .param("scope", scope.name())
                .param("target", target)
                .query((rs, row) -> new Existing(rs.getObject("restriction_id", UUID.class),
                        instant(rs, "effective_from")))
                .optional();
    }

    /** 효력 중인 행의 ID와 효력 시작. */
    record Existing(UUID restrictionId, Instant effectiveFrom) {}

    /**
     * 제한 행 하나를 더한다. 효력은 이 문장의 DB 시각부터다. 호출자의 트랜잭션에 참여한다.
     *
     * @param origin 운영자 command인가 강한 신호인가(Story 3.3)
     * @return 효력 시작 시각
     */
    Instant insert(UUID restrictionId, RestrictionFeature feature, RestrictionScope scope, String target,
            @Nullable UUID targetUserId, String operationId, RestrictionOrigin origin) {
        return this.jdbc.sql("INSERT INTO operations_restriction (restriction_id, feature, scope, target, "
                        + "target_user_id, operation_id, origin, effective_from) VALUES (:id, :feature, :scope, "
                        + ":target, :targetUser, :operationId, :origin, statement_timestamp()) RETURNING effective_from")
                .param("id", restrictionId)
                .param("feature", feature.name())
                .param("scope", scope.name())
                .param("target", target)
                .param("targetUser", targetUserId)
                .param("operationId", operationId)
                .param("origin", origin.name())
                .query((rs, row) -> instant(rs, "effective_from"))
                .single();
    }

    /** 그 사용자에게 귀속된, 아직 비식별하지 않은 제한 행. */
    record Attributed(UUID restrictionId, String scope, String target) {}

    List<Attributed> attributedTo(UUID userId) {
        return this.jdbc.sql("SELECT restriction_id, scope, target FROM operations_restriction "
                        + "WHERE target_user_id = :user ORDER BY restriction_id")
                .param("user", userId)
                .query((rs, row) -> new Attributed(rs.getObject("restriction_id", UUID.class),
                        rs.getString("scope"), rs.getString("target")))
                .list();
    }

    /** 행 하나의 target을 digest로 바꾸고 귀속을 비운다. 이미 비운 행은 술어가 고르지 않는다. */
    int eraseTarget(UUID restrictionId, UUID userId, String digest) {
        return this.jdbc.sql("UPDATE operations_restriction SET target = :digest, target_user_id = NULL, "
                        + "target_erased_at = statement_timestamp() WHERE restriction_id = :id AND target_user_id = :user")
                .param("digest", digest)
                .param("id", restrictionId)
                .param("user", userId)
                .update();
    }

    private static Row row(ResultSet rs, int index) throws SQLException {
        boolean erased = rs.getObject("target_erased_at", OffsetDateTime.class) != null;
        return new Row(new ActiveRestriction(rs.getObject("restriction_id", UUID.class),
                RestrictionFeature.valueOf(rs.getString("feature")), RestrictionScope.valueOf(rs.getString("scope")),
                erased ? null : rs.getString("target"), erased, instant(rs, "effective_from"), null, null,
                RestrictionOrigin.valueOf(rs.getString("origin")), rs.getInt("linked_signals")),
                rs.getString("operation_id"));
    }

    /** PostgreSQL {@code uuid[]} 리터럴 하나. UUID 글자에는 이스케이프할 문자가 없다. */
    private static String uuidArray(Set<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }

    private static @Nullable String text(@Nullable UUID id) {
        return id == null ? null : id.toString();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new SQLException("제한 행의 필수 시각이 비어 있다.");
        }
        return value.toInstant();
    }
}
