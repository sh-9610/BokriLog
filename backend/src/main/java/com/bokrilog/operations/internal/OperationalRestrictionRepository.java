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

    /**
     * 열린 행 술어(Story 3.4): 지금 효력 중이고 끝이 정해지지 않았다. 신호의 연결 대상 판정({@link #activeRow}), 해제
     * 대상의 열림({@link #liftFacts}), 해제 UPDATE({@link #lift})가 이 문자열 하나를 쓴다 — 미리 본 "열림"과 실제로 닫는
     * 문장의 경계가 갈라지지 않는다.
     */
    private static final String OPEN = "r.effective_until IS NULL AND statement_timestamp() >= r.effective_from";

    /**
     * 제한 하나와 그 되돌림 계보(Story 3.4)를 잇는 재귀 CTE. 행 {@code r}에서 {@code reinstated_from}을 따라 원 제한까지
     * 올라간다. 되돌림 행은 언제나 더 오래된 행을 가리키므로 사슬은 끝난다({@code UNION}이 중복도 거른다).
     */
    private static final String LINEAGE = "WITH RECURSIVE lineage(id) AS (SELECT r.restriction_id UNION "
            + "SELECT p.reinstated_from FROM operations_restriction p JOIN lineage l ON p.restriction_id = l.id "
            + "WHERE p.reinstated_from IS NOT NULL) ";

    /** 그 제한과 계보에 연결된 강한 신호 수. */
    private static final String LINKED_SIGNALS = "(" + LINEAGE + "SELECT count(*) FROM operations_signal s "
            + "WHERE s.restriction_id IN (SELECT id FROM lineage)) AS linked_signals";

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
            + "r.effective_from, r.operation_id, r.origin, r.reinstated_from, " + LINKED_SIGNALS + " "
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
        return matchingQuery(features, subject, excluding, " LIMIT 1").optional();
    }

    /**
     * {@link #effective}가 고르는 후보 전체(Story 3.4) — 같은 술어·같은 순서이고 {@code LIMIT}만 없다. 해제 미리보기와
     * smoke가 "해제 뒤에도 남는 제한"을 evaluator와 같은 규칙으로 센다.
     */
    List<Row> matching(Set<RestrictionFeature> features, RestrictionSubject subject, Set<UUID> excluding) {
        return matchingQuery(features, subject, excluding, "").list();
    }

    private JdbcClient.MappedQuerySpec<Row> matchingQuery(Set<RestrictionFeature> features, RestrictionSubject subject,
            Set<UUID> excluding, String limit) {
        return this.jdbc.sql(SELECT + "WHERE r.feature IN (:features) AND " + EFFECTIVE
                        + " AND NOT (r.restriction_id = ANY(CAST(:excluded AS uuid[]))) AND " + SUBJECT
                        + " ORDER BY " + ORDER + limit)
                .param("features", features.stream().map(Enum::name).toList())
                .param("plan", text(subject.planId()))
                .param("portfolio", text(subject.portfolioId()))
                .param("connection", text(subject.connectionId()))
                .param("policy", subject.calculationPolicy())
                .param("excluded", uuidArray(excluding))
                .query(OperationalRestrictionRepository::row);
    }

    /**
     * 대상 일치 술어. 넘기지 않은 차원({@code null})의 구체 제한은 일치하지 않고, 비식별된 target은 원식별자와 같을 수
     * 없다. 전역은 어떤 대상에도 일치한다. 판독({@link #matching})과 공지의 복구 대상 판정({@link #matchingAmong}, Story
     * 3.5)이 이 술어 하나를 쓴다({@link #subject(String)}).
     */
    private static final String SUBJECT = subject("");

    /** 대상 일치 술어. 매개변수 이름 끝에 {@code suffix}를 붙인다 — 한 문장에 차원 여럿을 OR로 묶을 때 쓴다. */
    private static String subject(String suffix) {
        return "(r.scope = 'GLOBAL' OR (r.target_erased_at IS NULL AND ("
                + "(r.scope = 'PLAN' AND r.target = CAST(:plan" + suffix + " AS text)) "
                + "OR (r.scope = 'PORTFOLIO' AND r.target = CAST(:portfolio" + suffix + " AS text)) "
                + "OR (r.scope = 'PROVIDER_CONNECTION' AND r.target = CAST(:connection" + suffix + " AS text)) "
                + "OR (r.scope = 'CALCULATION_POLICY_VERSION' AND r.target = CAST(:policy" + suffix + " AS text)))))";
    }

    /**
     * 그 제한들 가운데 그 대상 차원들 중 하나에라도 일치하는 것(Story 3.5). 문장 하나다. 효력 구간을 보지 않는다 — 복구
     * 공지가 덮는 제한은 이미 닫혔고, 판정은 "그 사건의 제한에 이 사용자가 영향을 받았는가"다. 대상 술어는 판독과 같다.
     */
    Set<UUID> matchingAmong(java.util.Collection<UUID> restrictionIds, List<RestrictionSubject> subjects) {
        if (restrictionIds.isEmpty() || subjects.isEmpty()) {
            return Set.of();
        }
        List<String> predicates = new java.util.ArrayList<>();
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("ids", uuidArray(Set.copyOf(restrictionIds)));
        for (int index = 0; index < subjects.size(); index++) {
            RestrictionSubject subject = subjects.get(index);
            String suffix = "_" + index;
            predicates.add(subject(suffix));
            params.put("plan" + suffix, text(subject.planId()));
            params.put("portfolio" + suffix, text(subject.portfolioId()));
            params.put("connection" + suffix, text(subject.connectionId()));
            params.put("policy" + suffix, subject.calculationPolicy());
        }
        return Set.copyOf(this.jdbc.sql("SELECT r.restriction_id FROM operations_restriction r "
                        + "WHERE r.restriction_id = ANY(CAST(:ids AS uuid[])) AND (" + String.join(" OR ", predicates)
                        + ")")
                .params(params)
                .query((rs, row) -> rs.getObject("restriction_id", UUID.class))
                .list());
    }

    /**
     * 공지가 덮을 제한 하나의 사실(Story 3.5). {@code open}은 {@link #OPEN} 술어다 — 진행 중인 공지는 지금 효력 중이고
     * 끝이 정해지지 않은 제한만 덮는다.
     */
    record CoverFacts(UUID restrictionId, RestrictionFeature feature, boolean open) {}

    /** 그 제한들의 사실. 없는 ID는 빠진다. 잠그지 않는다 — 확정은 원장 추가 잠금 아래에서 다시 읽는다. */
    List<CoverFacts> coverFacts(java.util.Collection<UUID> restrictionIds) {
        if (restrictionIds.isEmpty()) {
            return List.of();
        }
        return this.jdbc.sql("SELECT r.restriction_id, r.feature, (" + OPEN + ") AS open "
                        + "FROM operations_restriction r WHERE r.restriction_id = ANY(CAST(:ids AS uuid[])) "
                        + "ORDER BY r.restriction_id")
                .param("ids", uuidArray(Set.copyOf(restrictionIds)))
                .query((rs, row) -> new CoverFacts(rs.getObject("restriction_id", UUID.class),
                        RestrictionFeature.valueOf(rs.getString("feature")), rs.getBoolean("open")))
                .list();
    }

    /** 그 제한이 지금 효력 중이면 그 행(Story 3.4 smoke — 남는 제한이 그사이 정상적으로 닫혔는지 본다). */
    Optional<Row> current(UUID restrictionId) {
        return this.jdbc.sql(SELECT + "WHERE r.restriction_id = :id AND " + EFFECTIVE)
                .param("id", restrictionId)
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
                        + "WHERE r.feature = :feature AND r.scope = :scope AND r.target = :target AND " + OPEN
                        + " ORDER BY r.effective_from, r.restriction_id LIMIT 1")
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

    /**
     * 해제 대상 제한 하나의 사실(Story 3.4). {@code open}은 {@link #OPEN} 술어다. 연결 신호 수와 최신 감지 시각은 되돌림 계보
     * ({@link #LINEAGE})까지 센 값이고, 준비가 본 계보와 확정 순간의 계보를 비교하는 데 쓴다 — 그 사이 새 신호가 연결됐으면
     * 해제하지 않는다.
     *
     * @param target 원 target. 비식별됐으면 digest이고 {@code targetErased}가 참이다
     */
    record LiftFacts(UUID restrictionId, RestrictionFeature feature, RestrictionScope scope, String target,
            boolean targetErased, RestrictionOrigin origin, boolean open, int linkedSignals,
            @Nullable Instant latestSignalAt) {}

    private static final String LIFT_FACTS = "SELECT r.restriction_id, r.feature, r.scope, r.target, "
            + "r.target_erased_at, r.origin, (" + OPEN + ") AS open, " + LINKED_SIGNALS + ", (" + LINEAGE
            + "SELECT max(s.detected_at) FROM operations_signal s WHERE s.restriction_id IN (SELECT id FROM lineage)) "
            + "AS latest_signal_at FROM operations_restriction r WHERE r.restriction_id = :id";

    /** 해제 대상의 사실. 잠그지 않는다 — 준비의 미리보기다. */
    Optional<LiftFacts> liftFacts(UUID restrictionId) {
        return this.jdbc.sql(LIFT_FACTS).param("id", restrictionId).query(OperationalRestrictionRepository::liftFacts)
                .optional();
    }

    /**
     * 해제 대상의 사실을 행 잠금 아래에서 읽는다. 호출자의 트랜잭션이 끝날 때까지 그 행을 쥔다 — 같은 제한의 동시 해제는
     * 여기서 줄을 서고, 뒤의 것은 앞의 것이 닫은 행을 본다.
     */
    Optional<LiftFacts> lockForLift(UUID restrictionId) {
        return this.jdbc.sql(LIFT_FACTS + " FOR UPDATE OF r").param("id", restrictionId)
                .query(OperationalRestrictionRepository::liftFacts).optional();
    }

    /**
     * 제한 하나를 닫는다(Story 3.4 AC-3). <b>{@code effective_until}을 쓰는 운영 SQL은 이 문장 하나뿐이다</b> — 시간 경과·
     * 신호·재시작으로 닫히는 경로가 없다는 것을 {@code OperatorRecoveryTest}가 운영 소스를 훑어 강제한다. 술어는
     * {@link #OPEN} 그대로라 이미 닫힌 행·효력 시작 전인 행은 0행이다. 호출자의 트랜잭션에 참여한다.
     *
     * @return 닫은 시각(DB). 0행이면 빈 값
     */
    Optional<Instant> lift(UUID restrictionId, String operationId) {
        return this.jdbc.sql("UPDATE operations_restriction r SET effective_until = statement_timestamp(), "
                        + "lifted_by_operation_id = :op WHERE r.restriction_id = :id AND " + OPEN
                        + " RETURNING r.effective_until")
                .param("op", operationId)
                .param("id", restrictionId)
                .query((rs, row) -> instant(rs, "effective_until"))
                .optional();
    }

    /**
     * 해제한 제한과 같은 기능·scope·target의 새 제한 한 행(Story 3.4 smoke 되돌림). 귀속·비식별 상태와 출처를 그 행에서
     * 그대로 옮기고({@code SIGNAL}이면 {@code SIGNAL}) {@code reinstated_from}으로 그 행을 가리킨다 — 원식별자를 다시 들이지
     * 않고, 목록과 다음 해제가 원 제한의 신호 계보를 본다. operation은 해제 operation이다. 호출자의 트랜잭션에 참여한다.
     *
     * @return 새 제한의 효력 시작. 원 행이 없으면 빈 값
     */
    Optional<Instant> reinstate(UUID restrictionId, UUID liftedRestrictionId, String operationId) {
        return this.jdbc.sql("INSERT INTO operations_restriction (restriction_id, feature, scope, target, target_user_id, "
                        + "target_erased_at, operation_id, origin, reinstated_from, effective_from) SELECT :id, feature, "
                        + "scope, target, target_user_id, target_erased_at, :op, origin, restriction_id, "
                        + "statement_timestamp() FROM operations_restriction WHERE restriction_id = :lifted "
                        + "RETURNING effective_from")
                .param("id", restrictionId)
                .param("op", operationId)
                .param("lifted", liftedRestrictionId)
                .query((rs, row) -> instant(rs, "effective_from"))
                .optional();
    }

    private static LiftFacts liftFacts(ResultSet rs, int index) throws SQLException {
        OffsetDateTime latest = rs.getObject("latest_signal_at", OffsetDateTime.class);
        return new LiftFacts(rs.getObject("restriction_id", UUID.class),
                RestrictionFeature.valueOf(rs.getString("feature")), RestrictionScope.valueOf(rs.getString("scope")),
                rs.getString("target"), rs.getObject("target_erased_at", OffsetDateTime.class) != null,
                RestrictionOrigin.valueOf(rs.getString("origin")), rs.getBoolean("open"), rs.getInt("linked_signals"),
                latest == null ? null : latest.toInstant());
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
                RestrictionOrigin.valueOf(rs.getString("origin")), rs.getInt("linked_signals"),
                rs.getObject("reinstated_from", UUID.class)), rs.getString("operation_id"));
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
