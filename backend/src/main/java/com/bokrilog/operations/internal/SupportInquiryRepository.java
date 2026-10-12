package com.bokrilog.operations.internal;

import com.bokrilog.operations.SupportInquiryApi.Category;
import com.bokrilog.operations.SupportInquiryApi.Contact;
import com.bokrilog.operations.SupportInquiryApi.Status;
import com.bokrilog.operations.SupportInquiryApi.TargetState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 문의 표 {@code support_inquiry}와 영업일 달력 seed({@code support_calendar_year}·{@code support_business_calendar})의
 * 유일한 SQL 자리(Story 3.6).
 *
 * <p>문의 행의 변경은 넷뿐이다: 접수 INSERT, 운영 command의 상태 전이({@link #transition}, 조건부 UPDATE 한 문장), 미정
 * 목표 채우기({@link #fillTarget}), 그리고 보존·삭제 port의 DELETE({@link #purgeExpired}·{@link #eraseUser}). 달력 표는
 * 읽기만 한다(seed는 마이그레이션이 쓴다).
 *
 * <p>시각의 권위는 PostgreSQL이다. 접수 시각은 트랜잭션 시각({@code now()})이고, 전이 시각은 그 문장의
 * {@code statement_timestamp()}다.
 */
@Repository
class SupportInquiryRepository {

    /** 한 사용자의 접수를 직렬화하는 advisory 잠금 이름의 접두사. 원장 잠금과 겹치지 않는 이름이다. */
    private static final String USER_LOCK_PREFIX = "bokrilog.support_inquiry.user:";

    /** reference 유일 제약의 이름. 난수 충돌만 이 이름으로 가른다. */
    static final String REFERENCE_KEY = "support_inquiry_reference_key";

    private static final String SUMMARY_COLUMNS = "reference, category, title, status, received_at, target_at, "
            + "target_state, first_answered_at, target_met, contact, reply_email, resolved_at";

    private final JdbcClient jdbc;

    SupportInquiryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 호출자 트랜잭션 끝까지 그 사용자의 접수를 직렬화한다. 같은 키의 동시 접수·상한 경합을 줄 세운다. */
    void lockUser(UUID userId) {
        this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:name, 0))")
                .param("name", USER_LOCK_PREFIX + userId)
                .query((rs, row) -> 1)
                .single();
    }

    /** 트랜잭션 시각({@code now()}). 한 트랜잭션 안에서 같은 값이다. */
    Instant transactionTime() {
        return this.jdbc.sql("SELECT now()").query((rs, row) -> instant(rs, 1)).single();
    }

    // ------------------------------------------------------------------ 달력

    /** 그 버전의 달력. 버전 행이 없으면 담은 연도가 없는 달력이다(모든 목표가 미정이다). */
    BusinessDayCalendar calendar(String version) {
        Set<Integer> years = new HashSet<>(this.jdbc.sql(
                        "SELECT year FROM support_calendar_year WHERE calendar_version = :v")
                .param("v", version)
                .query(Integer.class)
                .list());
        Set<LocalDate> holidays = new HashSet<>(this.jdbc.sql(
                        "SELECT holiday_date FROM support_business_calendar WHERE calendar_version = :v")
                .param("v", version)
                .query((rs, row) -> rs.getObject(1, LocalDate.class))
                .list());
        return new BusinessDayCalendar(version, years, holidays);
    }

    // ------------------------------------------------------------------ 접수

    /** 같은 사용자·같은 키의 앞선 접수. */
    record Existing(String requestDigest, String reference, Instant receivedAt, @Nullable Instant targetAt,
            TargetState targetState, Status status) {}

    Optional<Existing> byKey(UUID userId, String keyDigest) {
        return this.jdbc.sql("SELECT request_digest, reference, received_at, target_at, target_state, status "
                        + "FROM support_inquiry WHERE user_id = :u AND idempotency_key_digest = :k")
                .param("u", userId)
                .param("k", keyDigest)
                .query((rs, row) -> new Existing(rs.getString("request_digest"), rs.getString("reference"),
                        instant(rs, "received_at"), nullableInstant(rs, "target_at"),
                        TargetState.valueOf(rs.getString("target_state")), Status.valueOf(rs.getString("status"))))
                .optional();
    }

    /** {@code since} 뒤에 접수한 그 사용자의 문의 수. */
    int countSince(UUID userId, Instant since) {
        Integer count = this.jdbc.sql("SELECT count(*) FROM support_inquiry WHERE user_id = :u AND received_at > :s")
                .param("u", userId)
                .param("s", utc(since))
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    /** 접수 한 행. 모든 값이 한 INSERT다. */
    record NewInquiry(UUID inquiryId, UUID userId, String reference, Category category, String title,
            String description, Contact contact, @Nullable String replyEmail, String keyDigest, String requestDigest,
            Instant receivedAt, @Nullable Instant targetAt, TargetState targetState, String calendarVersion,
            String timezone) {}

    void insert(NewInquiry inquiry) {
        this.jdbc.sql("INSERT INTO support_inquiry (inquiry_id, user_id, reference, category, title, description, "
                        + "contact, reply_email, idempotency_key_digest, request_digest, received_at, target_at, "
                        + "target_state, calendar_version, timezone, status) VALUES (:id, :u, :ref, :category, "
                        + ":title, :description, :contact, :email, :keyDigest, :requestDigest, :receivedAt, "
                        + "CAST(:targetAt AS timestamptz), :targetState, :version, :timezone, 'RECEIVED')")
                .param("id", inquiry.inquiryId())
                .param("u", inquiry.userId())
                .param("ref", inquiry.reference())
                .param("category", inquiry.category().name())
                .param("title", inquiry.title())
                .param("description", inquiry.description())
                .param("contact", inquiry.contact().name())
                .param("email", inquiry.replyEmail())
                .param("keyDigest", inquiry.keyDigest())
                .param("requestDigest", inquiry.requestDigest())
                .param("receivedAt", utc(inquiry.receivedAt()))
                .param("targetAt", inquiry.targetAt() == null ? null : utc(inquiry.targetAt()))
                .param("targetState", inquiry.targetState().name())
                .param("version", inquiry.calendarVersion())
                .param("timezone", inquiry.timezone())
                .update();
    }

    // ------------------------------------------------------------------ 조회

    /** 한 조회가 지우거나 채우는 행의 상한. 남은 행은 다음 조회가 한다. */
    static final int REFRESH_BATCH = 200;

    /**
     * 해결 뒤 보존 기간이 지난 문의를 지운다. 조회 트랜잭션의 시작에서 부른다(백그라운드 없음). 판정 시각은 트랜잭션 시각이다.
     *
     * <p>다른 트랜잭션이 잡고 있는 행은 건너뛴다({@code SKIP LOCKED}) — 모든 GET이 같은 행을 두고 줄을 서지 않는다. 한 번에
     * {@value #REFRESH_BATCH}행까지이고 남은 행은 다음 조회가 지운다.
     *
     * @return 지운 행 수
     */
    int purgeExpired(long retentionSeconds) {
        return this.jdbc.sql("DELETE FROM support_inquiry WHERE ctid IN (SELECT ctid FROM support_inquiry "
                        + "WHERE status = 'RESOLVED' AND resolved_at < now() - make_interval(secs => :s) "
                        + "LIMIT :limit FOR UPDATE SKIP LOCKED)")
                .param("s", retentionSeconds)
                .param("limit", REFRESH_BATCH)
                .update();
    }

    /** 목표가 미정인 문의 하나. */
    record Pending(UUID inquiryId, Instant receivedAt) {}

    /** 목표가 미정인 문의(오래된 순). 다른 트랜잭션이 잡고 있는 행은 건너뛴다. */
    List<Pending> pendingTargets() {
        return this.jdbc.sql("SELECT inquiry_id, received_at FROM support_inquiry "
                        + "WHERE target_state = 'CALENDAR_PENDING' ORDER BY received_at LIMIT :limit "
                        + "FOR UPDATE SKIP LOCKED")
                .param("limit", REFRESH_BATCH)
                .query((rs, row) -> new Pending(rs.getObject("inquiry_id", UUID.class), instant(rs, "received_at")))
                .list();
    }

    /** {@link #fillTarget}의 reference판. 운영자 확정이 행 잠금 아래에서 답변 직전에 부른다(Story 3.7). */
    int fillTargetOf(String reference, Instant targetAt, String calendarVersion) {
        return this.jdbc.sql("UPDATE support_inquiry SET target_at = :t, calendar_version = :v, target_state = 'SET', "
                        + "target_met = CASE WHEN first_answered_at IS NULL THEN NULL ELSE first_answered_at <= :t END "
                        + "WHERE reference = :r AND target_state = 'CALENDAR_PENDING'")
                .param("t", utc(targetAt))
                .param("v", calendarVersion)
                .param("r", reference)
                .update();
    }

    /**
     * 미정 목표를 채운다 — 지금 달력이 그 연도들을 담게 됐다. 이미 정해진 목표는 고르지 않는다(소급 재계산 없음). 이미 답한
     * 문의는 목표 달성 여부를 같은 식으로 함께 쓴다.
     */
    int fillTarget(UUID inquiryId, Instant targetAt, String calendarVersion) {
        return this.jdbc.sql("UPDATE support_inquiry SET target_at = :t, calendar_version = :v, target_state = 'SET', "
                        + "target_met = CASE WHEN first_answered_at IS NULL THEN NULL ELSE first_answered_at <= :t END "
                        + "WHERE inquiry_id = :id AND target_state = 'CALENDAR_PENDING'")
                .param("t", utc(targetAt))
                .param("v", calendarVersion)
                .param("id", inquiryId)
                .update();
    }

    /** 사용자 목록의 행. */
    record UserRow(String reference, Category category, String title, String description, Instant receivedAt,
            @Nullable Instant targetAt, TargetState targetState, Status status, @Nullable String answer,
            @Nullable Instant answeredAt) {}

    List<UserRow> ofUser(UUID userId, int limit) {
        return this.jdbc.sql("SELECT reference, category, title, description, received_at, target_at, target_state, "
                        + "status, answer, first_answered_at FROM support_inquiry WHERE user_id = :u "
                        + "ORDER BY received_at DESC, reference LIMIT :limit")
                .param("u", userId)
                .param("limit", limit)
                .query((rs, row) -> new UserRow(rs.getString("reference"), Category.valueOf(rs.getString("category")),
                        rs.getString("title"), rs.getString("description"), instant(rs, "received_at"),
                        nullableInstant(rs, "target_at"),
                        TargetState.valueOf(rs.getString("target_state")), Status.valueOf(rs.getString("status")),
                        rs.getString("answer"), nullableInstant(rs, "first_answered_at")))
                .list();
    }

    /** 운영자 목록·상세의 요약 행. 회신 이메일은 원문이다 — 마스킹은 서비스가 한다. */
    record SummaryRow(String reference, Category category, String title, Status status, Instant receivedAt,
            @Nullable Instant targetAt, TargetState targetState, @Nullable Instant firstAnsweredAt,
            @Nullable Boolean targetMet, Contact contact, @Nullable String replyEmail, @Nullable Instant resolvedAt) {}

    /**
     * 운영자 목록: 미해결({@code RECEIVED}·{@code IN_PROGRESS}·{@code ANSWERED})이 먼저이고 그 안에서 최근 접수 순이다.
     * 상태를 주면 그 상태만이다.
     */
    List<SummaryRow> recent(@Nullable Status status, int limit) {
        return this.jdbc.sql("SELECT " + SUMMARY_COLUMNS + " FROM support_inquiry "
                        + "WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text)) "
                        + "ORDER BY (status = 'RESOLVED'), received_at DESC, reference LIMIT :limit")
                .param("status", status == null ? null : status.name())
                .param("limit", limit)
                .query(SupportInquiryRepository::summary)
                .list();
    }

    /** 상세의 행: 요약에 본문·답변을 더한다. */
    record DetailRow(SummaryRow summary, String description, @Nullable String answer) {}

    Optional<DetailRow> detail(String reference) {
        return this.jdbc.sql("SELECT " + SUMMARY_COLUMNS + ", description, answer FROM support_inquiry "
                        + "WHERE reference = :r")
                .param("r", reference)
                .query((rs, row) -> new DetailRow(summary(rs, row), rs.getString("description"),
                        rs.getString("answer")))
                .optional();
    }

    // ------------------------------------------------------------------ 운영 처리

    /**
     * 준비·확정이 보는 문의의 지금 상태.
     *
     * @param ownerUserId 문의를 접수한 사용자. 확정이 그 계정의 상태를 본다
     */
    record Current(UUID ownerUserId, Status status, @Nullable Instant targetAt, Instant receivedAt) {}

    Optional<Current> current(String reference) {
        return this.jdbc.sql("SELECT user_id, status, target_at, received_at FROM support_inquiry WHERE reference = :r")
                .param("r", reference)
                .query(SupportInquiryRepository::current)
                .optional();
    }

    /** 확정 트랜잭션의 행 잠금. 그 사이 다른 전이·보존 삭제·계정 삭제가 끼어들지 못한다. */
    Optional<Current> lockForTransition(String reference) {
        return this.jdbc.sql("SELECT user_id, status, target_at, received_at FROM support_inquiry WHERE reference = :r FOR UPDATE")
                .param("r", reference)
                .query(SupportInquiryRepository::current)
                .optional();
    }

    private static Current current(ResultSet rs, int row) throws SQLException {
        return new Current(rs.getObject("user_id", UUID.class), Status.valueOf(rs.getString("status")),
                nullableInstant(rs, "target_at"), instant(rs, "received_at"));
    }

    /**
     * 상태 전이 — 조건부 UPDATE 한 문장. 지금 상태가 {@code from}일 때만 {@code to}로 옮긴다. 첫 {@code ANSWERED}가 답변·첫
     * 답변 시각·목표 달성 여부(목표 미정이면 NULL)를, {@code RESOLVED}가 해결 시각을 쓴다. 모두 이 문장의 DB 시각이다.
     *
     * @return 전이 시각. 지금 상태가 {@code from}이 아니면 빈 값
     */
    Optional<Instant> transition(String reference, Status from, Status to, @Nullable String answer,
            String operationId) {
        return this.jdbc.sql("UPDATE support_inquiry SET status = :to, "
                        + "answer = CASE WHEN :to = 'ANSWERED' THEN CAST(:answer AS text) ELSE answer END, "
                        + "first_answered_at = CASE WHEN :to = 'ANSWERED' THEN statement_timestamp() "
                        + "ELSE first_answered_at END, "
                        + "target_met = CASE WHEN :to = 'ANSWERED' AND target_at IS NOT NULL "
                        + "THEN statement_timestamp() <= target_at ELSE target_met END, "
                        + "resolved_at = CASE WHEN :to = 'RESOLVED' THEN statement_timestamp() ELSE resolved_at END, "
                        + "last_operation_id = :op WHERE reference = :r AND status = :from "
                        + "RETURNING statement_timestamp() AS transitioned_at")
                .param("to", to.name())
                .param("answer", answer)
                .param("op", operationId)
                .param("r", reference)
                .param("from", from.name())
                .query((rs, row) -> instant(rs, "transitioned_at"))
                .optional();
    }

    // ------------------------------------------------------------------ 집계

    /** 이 트랜잭션의 문장 시간 상한({@code SET LOCAL statement_timeout}). 관측 측정이 DB를 오래 붙들지 않게 한다. */
    void limitStatementTime(String timeout) {
        if (!timeout.matches("[0-9]{1,5}(ms|s)")) {
            throw new IllegalArgumentException("statement_timeout 모양이 아니다.");
        }
        this.jdbc.sql("SET LOCAL statement_timeout = '" + timeout + "'").update();
    }

    /** 첫 답변을 기다리는 문의 수(접수·처리 중). 관측 gauge(Story 3.7)의 값이다 — 사용자·문의 식별자 없이 수만 읽는다. */
    long awaitingFirstAnswer() {
        return this.jdbc.sql("SELECT count(*) FROM support_inquiry WHERE status IN ('RECEIVED', 'IN_PROGRESS')")
                .query(Long.class)
                .single();
    }

    /** 집계의 행: 목표가 정해진 문의의 접수·목표·첫 답변 시각과 그 목표를 정한 달력 버전. */
    record DueRow(Instant receivedAt, Instant targetAt, @Nullable Instant firstAnsweredAt, String calendarVersion) {}

    /** 목표 시각이 기간 안이고 {@code asOf} 이전(지남)인 문의. */
    List<DueRow> due(Instant from, Instant until, Instant asOf) {
        return this.jdbc.sql("SELECT received_at, target_at, first_answered_at, calendar_version FROM support_inquiry "
                        + "WHERE target_at >= :from AND target_at < :until AND target_at <= :asOf")
                .param("from", utc(from))
                .param("until", utc(until))
                .param("asOf", utc(asOf))
                .query((rs, row) -> new DueRow(instant(rs, "received_at"), instant(rs, "target_at"),
                        nullableInstant(rs, "first_answered_at"), rs.getString("calendar_version")))
                .list();
    }

    /** 접수 시각이 기간 안이고 목표가 미정인 문의 수. */
    long calendarPending(Instant from, Instant until) {
        Long count = this.jdbc.sql("SELECT count(*) FROM support_inquiry WHERE target_state = 'CALENDAR_PENDING' "
                        + "AND received_at >= :from AND received_at < :until")
                .param("from", utc(from))
                .param("until", utc(until))
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    // ------------------------------------------------------------------ 삭제 port

    /**
     * 그 사용자의 문의(멱등 기록이 같은 행이다)를 지운다. 다른 사용자의 행은 그대로다. 호출자가 접수와 같은 사용자 잠금
     * ({@link #lockUser})을 쥔 트랜잭션에서 부른다.
     */
    int eraseUser(UUID userId) {
        return this.jdbc.sql("DELETE FROM support_inquiry WHERE user_id = :u").param("u", userId).update();
    }

    private static SummaryRow summary(ResultSet rs, int row) throws SQLException {
        Object met = rs.getObject("target_met");
        return new SummaryRow(rs.getString("reference"), Category.valueOf(rs.getString("category")),
                rs.getString("title"), Status.valueOf(rs.getString("status")), instant(rs, "received_at"),
                nullableInstant(rs, "target_at"), TargetState.valueOf(rs.getString("target_state")),
                nullableInstant(rs, "first_answered_at"), met == null ? null : (Boolean) met,
                Contact.valueOf(rs.getString("contact")), rs.getString("reply_email"),
                nullableInstant(rs, "resolved_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new SQLException("문의 행의 필수 시각이 비어 있다.");
        }
        return value.toInstant();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new SQLException("DB 시각이 비어 있다.");
        }
        return value.toInstant();
    }

    private static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
