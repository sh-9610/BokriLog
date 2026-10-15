package com.bokrilog.engagement.internal;

import com.bokrilog.engagement.AdEligibilityApi.SkipReason;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 광고 자격 결정 표 {@code engagement_ad_attempt}의 유일한 SQL 자리(Story 4.2).
 *
 * <p>문장은 INSERT·SELECT와 deletion port의 DELETE뿐이다 — 결정 행을 고치는 문장이 없다. 결정 시각은 INSERT 문장의 DB
 * 시각(열 기본값 {@code statement_timestamp()})이다.
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

    /** 그 사용자의 결정 행을 모두 지운다(deletion port). 다른 사용자의 행은 그대로다. */
    int eraseUser(UUID userId) {
        return this.jdbc.sql("DELETE FROM engagement_ad_attempt WHERE user_id = :u").param("u", userId).update();
    }
}
