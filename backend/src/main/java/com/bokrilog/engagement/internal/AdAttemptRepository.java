package com.bokrilog.engagement.internal;

import com.bokrilog.engagement.AdEligibilityApi.SkipReason;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 광고 자격 결정 표 {@code engagement_ad_attempt}의 유일한 SQL 자리(Story 4.2·4.3·4.5).
 *
 * <p>문장은 INSERT·SELECT, transition 소비의 조건부 UPDATE 하나(Story 4.3 — 소비 시각과 SHOW 표지만 쓰고 이미 소비된 행은 바꾸지
 * 않는다), Vitals metric별 보고 표지의 UPDATE 하나(Story 4.5 — SHOW 행만)와 deletion port의 DELETE뿐이다 — 결정
 * (결말·사유·참조·결정 시각)을 고치는 문장이 없다. 결정 시각은 INSERT 문장의 DB 시각(열 기본값 {@code statement_timestamp()})이다.
 */
@Repository
class AdAttemptRepository {

    /**
     * 한 사용자의 자격 판정을 직렬화하는 advisory 잠금 이름의 접두사. 동의 결정의 잠금({@code AdConsentRepository})과 다른 이름이다
     * — 동의 변경과 자격 판정이 서로를 기다리지 않는다(판정은 커밋된 최신 동의를 읽는다).
     */
    static final String USER_LOCK_PREFIX = "bokrilog.engagement.ad_eligibility.user:";

    private final JdbcClient jdbc;

    AdAttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 호출자 트랜잭션 끝까지 그 사용자의 판정을 직렬화한다. 동시 탭·같은 결과의 동시 재전송을 줄 세운다. */
    void lockUser(UUID userId) {
        this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:name, 0))")
                .param("name", USER_LOCK_PREFIX + userId)
                .query((rs, row) -> 1)
                .single();
    }

    /**
     * 결정 행 하나.
     *
     * @param skipReason 건너뜀이면 그 사유, 자격이면 {@code null}
     * @param transitionRef 자격이면 그 참조, 건너뜀이면 {@code null}
     */
    record Row(@Nullable SkipReason skipReason, @Nullable String transitionRef) {}

    /** 같은 사용자·같은 결과의 앞선 결정. */
    Optional<Row> byResult(UUID userId, UUID syncJobId) {
        return this.jdbc.sql("SELECT skip_reason, transition_ref FROM engagement_ad_attempt "
                        + "WHERE user_id = :u AND sync_job_id = :j")
                .param("u", userId)
                .param("j", syncJobId)
                .query((rs, row) -> {
                    String reason = rs.getString("skip_reason");
                    return new Row(reason == null ? null : SkipReason.valueOf(reason), rs.getString("transition_ref"));
                })
                .optional();
    }

    /** 이 문장의 DB 시각 — 빈도 제한 판정의 기준 시각. */
    Instant now() {
        OffsetDateTime now = this.jdbc.sql("SELECT statement_timestamp()").query(OffsetDateTime.class).single();
        if (now == null) {
            throw new IllegalStateException("DB 시각을 읽지 못했다.");
        }
        return now.toInstant();
    }

    /**
     * 그 사용자의 최근 자격 시각들 — 순번(identity)이 큰 것부터 {@code limit}건. 판정은 사용자 잠금 아래에서만 쓰므로 순번 순서가
     * 커밋 순서다. 시각으로 정렬하지 않는다(DB 시계가 뒤로 가면 뒤집힌다).
     */
    List<Instant> recentEligible(UUID userId, int limit) {
        return this.jdbc.sql("SELECT decided_at FROM engagement_ad_attempt WHERE user_id = :u AND outcome = 'ELIGIBLE' "
                        + "ORDER BY id DESC LIMIT :n")
                .param("u", userId)
                .param("n", limit)
                .query(OffsetDateTime.class)
                .list()
                .stream()
                .map(OffsetDateTime::toInstant)
                .toList();
    }

    /** 자격(reservation) 한 행을 쓴다. */
    void insertEligible(UUID userId, UUID syncJobId, String transitionRef) {
        this.jdbc.sql("INSERT INTO engagement_ad_attempt (user_id, sync_job_id, outcome, transition_ref) "
                        + "VALUES (:u, :j, 'ELIGIBLE', :t)")
                .param("u", userId)
                .param("j", syncJobId)
                .param("t", transitionRef)
                .update();
    }

    /** 건너뜀 한 행을 쓴다. */
    void insertSkipped(UUID userId, UUID syncJobId, SkipReason reason) {
        this.jdbc.sql("INSERT INTO engagement_ad_attempt (user_id, sync_job_id, outcome, skip_reason) "
                        + "VALUES (:u, :j, 'SKIPPED', :r)")
                .param("u", userId)
                .param("j", syncJobId)
                .param("r", reason.name())
                .update();
    }

    /**
     * 소비 판정의 재료가 되는 자격 행 하나(Story 4.3).
     *
     * @param id 행 순번
     * @param consumed 이미 소비됐다
     * @param expired 결정 뒤 소비 창을 넘었다(이 문장의 DB 시각 기준)
     */
    record Transition(long id, boolean consumed, boolean expired) {}

    /**
     * 그 사용자의 그 transition 참조를 가진 자격 행을 잠근다({@code FOR UPDATE}) — 같은 참조의 동시 소비를 줄 세운다. 남의 참조·없는
     * 참조는 비어 있다(구분하지 않는다). 만료는 결정 시각에 창을 더한 값을 이 문장의 DB 시각이 넘었는가다(정확히 창 끝은 만료가
     * 아니다).
     */
    Optional<Transition> transitionForUpdate(UUID userId, String transitionRef, Duration window) {
        return this.jdbc.sql("SELECT id, transition_consumed_at IS NOT NULL AS consumed, statement_timestamp() > "
                        + "decided_at + interval '1 second' * :w AS expired "
                        + "FROM engagement_ad_attempt "
                        + "WHERE user_id = :u AND transition_ref = :t AND outcome = 'ELIGIBLE' FOR UPDATE")
                .param("u", userId)
                .param("t", transitionRef)
                .param("w", window.toSeconds())
                .query((rs, row) -> new Transition(rs.getLong("id"), rs.getBoolean("consumed"),
                        rs.getBoolean("expired")))
                .optional();
    }

    /**
     * 그 자격 행의 transition을 소비한다 — 이 문장의 DB 시각을 쓴다. 광고를 시도하는 소비(SHOW)면 같은 문장에서 SHOW 표지를 세운다
     * (Story 4.5 — 그 문서의 Vitals만 표본이 될 수 있다). 이미 소비된 행은 바꾸지 않는다.
     */
    void consume(long id, boolean shown) {
        this.jdbc.sql("UPDATE engagement_ad_attempt SET transition_consumed_at = statement_timestamp(), "
                        + "transition_shown = :shown WHERE id = :id AND transition_consumed_at IS NULL")
                .param("id", id)
                .param("shown", shown)
                .update();
    }

    /**
     * Vitals를 보고할 수 있는 SHOW 행 하나(Story 4.5).
     *
     * @param id 행 순번
     * @param reported 그 SHOW에서 이미 표본이 된 metric 이름들
     */
    record Reportable(long id, Set<String> reported) {}

    /**
     * Vitals를 보고할 수 있는 그 사용자의 SHOW 행 하나를 잠근다(Story 4.5) — SHOW로 소비됐고 소비 뒤 {@code window} 안인(이 문장의
     * DB 시각 기준) 행 가운데 순번이 가장 큰 것. 이미 보고된 metric은 {@link Reportable#reported}에 있다. 없으면 비어 있다.
     */
    Optional<Reportable> reportableShownForUpdate(UUID userId, Duration window) {
        return this.jdbc.sql("SELECT id, array_to_string(vitals_reported_metrics, ',') AS reported "
                        + "FROM engagement_ad_attempt WHERE user_id = :u AND transition_shown "
                        + "AND transition_consumed_at >= statement_timestamp() - interval '1 second' * :w "
                        + "ORDER BY id DESC LIMIT 1 FOR UPDATE")
                .param("u", userId)
                .param("w", window.toSeconds())
                .query((rs, row) -> {
                    String reported = rs.getString("reported");
                    return new Reportable(rs.getLong("id"), reported == null || reported.isEmpty() ? Set.of()
                            : Set.of(reported.split(",")));
                })
                .optional();
    }

    /**
     * 그 SHOW 행에 metric들의 보고를 표시한다. 이미 표시된 metric은 다시 더하지 않는다(SHOW 행만 바꾼다).
     *
     * @param metrics 닫힌 metric 이름들(LCP·INP·CLS)
     */
    void markVitalsReported(long id, Set<String> metrics) {
        this.jdbc.sql("UPDATE engagement_ad_attempt SET vitals_reported_metrics = ARRAY(SELECT DISTINCT m FROM "
                        + "unnest(vitals_reported_metrics || string_to_array(:m, ',')) AS m ORDER BY m) "
                        + "WHERE id = :id AND transition_shown")
                .param("id", id)
                .param("m", String.join(",", metrics))
                .update();
    }

    /** 그 사용자의 결정 행을 모두 지운다(deletion port). 다른 사용자의 행은 그대로다. */
    int eraseUser(UUID userId) {
        return this.jdbc.sql("DELETE FROM engagement_ad_attempt WHERE user_id = :u").param("u", userId).update();
    }
}
