package com.bokrilog.operations.internal;

import com.bokrilog.operations.SupportInquiryApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link SupportInquiryApi} 구현(Story 3.6). {@code internal}의 package-private 클래스다.
 *
 * <p><b>접수는 트랜잭션 하나다.</b> 사용자 advisory 잠금 → 같은 키의 앞선 접수(같은 입력이면 그 결과, 다른 입력이면 충돌) →
 * 최근 24시간 상한 → 달력으로 목표 계산 → INSERT 한 문장. 잠금이 같은 사용자의 동시 접수를 줄 세우므로 같은 키 둘이 행
 * 둘을 만들지 않고 상한도 넘지 않는다. 접수 시각은 그 트랜잭션의 DB 시각이다. reference 난수가 드물게 겹치면 새 난수로
 * 트랜잭션을 다시 한다.
 *
 * <p><b>조회는 보존 삭제가 먼저다.</b> 모든 조회 트랜잭션이 해결 뒤 90일이 지난 문의를 먼저 지운다 — 만료된 문의는 누구에게도
 * 보이지 않는다. 행이 물리적으로 남는 시간은 다음 조회까지다. 같은 자리에서 지금 달력이 담게 된 미정 목표를 채운다. 둘 다
 * 잠긴 행을 건너뛰고 한 번에 상한까지만 한다 — GET마다 같은 행을 두고 줄을 서지 않는다.
 *
 * <p>로그를 남기지 않는다 — 제목·본문·회신 이메일·답변이 로그로 갈 길을 만들지 않는다. 결과 code만 호출자가 남긴다.
 */
@Service
class SupportInquiryService implements SupportInquiryApi {

    /** reference의 알파벳(RFC 4648 base32 대문자). */
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private static final int REFERENCE_CHARS = 10;

    /** reference 난수가 겹쳤을 때 다시 하는 횟수. 50비트 난수라 실제로는 오지 않는다. */
    private static final int REFERENCE_ATTEMPTS = 3;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final SupportInquiryRepository inquiries;

    private final TransactionTemplate transactions;

    private final SecureRandom random = new SecureRandom();

    SupportInquiryService(SupportInquiryRepository inquiries, PlatformTransactionManager transactionManager) {
        this.inquiries = inquiries;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ 접수

    @Override
    public Submission submit(UUID userId, String idempotencyKey, Draft draft) {
        Category category = enumOf(Category.class, draft.category());
        if (category == null) {
            return new Submission.Invalid(Field.CATEGORY);
        }
        String title = InquiryRules.title(draft.title());
        if (title == null) {
            return new Submission.Invalid(Field.TITLE);
        }
        String description = InquiryRules.description(draft.description());
        if (description == null) {
            return new Submission.Invalid(Field.DESCRIPTION);
        }
        Contact contact = enumOf(Contact.class, draft.contact());
        if (contact == null) {
            return new Submission.Invalid(Field.CONTACT);
        }
        String replyEmail = contact == Contact.EMAIL ? InquiryRules.replyEmail(draft.replyEmail()) : null;
        if (contact == Contact.EMAIL) {
            if (replyEmail == null) {
                return new Submission.Invalid(Field.REPLY_EMAIL);
            }
        } else if (draft.replyEmail() != null && !draft.replyEmail().isBlank()) {
            // 서비스 안 회신에 이메일을 함께 보냈다. 받지 않는다 — 쓰지 않을 개인정보를 남기지 않는다.
            return new Submission.Invalid(Field.REPLY_EMAIL);
        }
        // 서버 판정이 권위다. 어느 필드의 어느 규칙인지만 돌려주고 맞은 조각은 돌려주지 않는다.
        Optional<SensitiveRule> titleRule = InquiryRules.sensitive(title);
        if (titleRule.isPresent()) {
            return new Submission.Sensitive(Field.TITLE, titleRule.get());
        }
        Optional<SensitiveRule> descriptionRule = InquiryRules.sensitive(description);
        if (descriptionRule.isPresent()) {
            return new Submission.Sensitive(Field.DESCRIPTION, descriptionRule.get());
        }

        String keyDigest = sha256("idempotency-key\n" + idempotencyKey);
        String requestDigest = sha256(category.name() + "\n" + title + "\n" + description + "\n" + contact.name()
                + "\n" + (replyEmail == null ? "-" : replyEmail));
        for (int attempt = 1; ; attempt++) {
            try {
                Submission submitted = this.transactions.execute(status -> submitLocked(userId, category, title,
                        description, contact, replyEmail, keyDigest, requestDigest));
                if (submitted == null) {
                    throw new IllegalStateException("접수 결과가 없다.");
                }
                return submitted;
            } catch (DuplicateKeyException duplicate) {
                if (attempt >= REFERENCE_ATTEMPTS || !referenceCollision(duplicate)) {
                    throw duplicate;
                }
            }
        }
    }

    private Submission submitLocked(UUID userId, Category category, String title, String description,
            Contact contact, @Nullable String replyEmail, String keyDigest, String requestDigest) {
        this.inquiries.lockUser(userId);
        Instant now = this.inquiries.transactionTime();
        var existing = this.inquiries.byKey(userId, keyDigest).orElse(null);
        if (existing != null) {
            if (!existing.requestDigest().equals(requestDigest)) {
                return new Submission.Conflict();
            }
            return new Submission.Received(new Receipt(existing.reference(), existing.receivedAt(),
                    existing.targetAt(), existing.targetState(), existing.status()), true);
        }
        if (this.inquiries.countSince(userId, now.minus(DAILY_WINDOW)) >= DAILY_LIMIT) {
            return new Submission.RateLimited();
        }
        BusinessDayCalendar calendar = this.inquiries.calendar(CALENDAR_VERSION);
        Instant targetAt = calendar.targetFor(now).orElse(null);
        TargetState targetState = targetAt == null ? TargetState.CALENDAR_PENDING : TargetState.SET;
        String reference = newReference();
        this.inquiries.insert(new SupportInquiryRepository.NewInquiry(UUID.randomUUID(), userId, reference, category,
                title, description, contact, replyEmail, keyDigest, requestDigest, now, targetAt, targetState,
                CALENDAR_VERSION, TIMEZONE));
        return new Submission.Received(new Receipt(reference, now, targetAt, targetState, Status.RECEIVED), false);
    }

    /** reference 유일 제약의 위반인가. 원인 사슬에서 제약 이름을 찾는다. 멱등 유일 색인은 잠금이 막으므로 오지 않는다. */
    private static boolean referenceCollision(DuplicateKeyException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("\"" + SupportInquiryRepository.REFERENCE_KEY + "\"")) {
                return true;
            }
        }
        return false;
    }

    /** 새 reference: {@code Q-} + base32 난수 10자(50비트). 사용자·시각·순번을 담지 않는다. */
    String newReference() {
        StringBuilder reference = new StringBuilder("Q-");
        for (int index = 0; index < REFERENCE_CHARS; index++) {
            reference.append(BASE32[this.random.nextInt(BASE32.length)]);
        }
        return reference.toString();
    }

    // ------------------------------------------------------------------ 조회

    @Override
    public List<UserInquiry> mine(UUID userId) {
        List<UserInquiry> found = this.transactions.execute(status -> {
            purge();
            return this.inquiries.ofUser(userId, MAX_LISTED).stream()
                    .map(row -> new UserInquiry(row.reference(), row.category(), row.title(), row.description(),
                            row.receivedAt(), row.targetAt(), row.targetState(), row.status(), row.answer(),
                            row.answeredAt()))
                    .toList();
        });
        return found == null ? List.of() : found;
    }

    @Override
    public List<OperatorInquiry> recent(@Nullable Status filter) {
        List<OperatorInquiry> found = this.transactions.execute(status -> {
            purge();
            return this.inquiries.recent(filter, MAX_OPERATOR_LISTED).stream().map(SupportInquiryService::summary)
                    .toList();
        });
        return found == null ? List.of() : found;
    }

    @Override
    public Optional<OperatorInquiryDetail> detail(String reference) {
        if (!InquiryRules.REFERENCE.matcher(reference).matches()) {
            return Optional.empty();
        }
        Optional<OperatorInquiryDetail> found = this.transactions.execute(status -> {
            purge();
            return this.inquiries.detail(reference)
                    .map(row -> new OperatorInquiryDetail(summary(row.summary()), row.description(), row.answer(),
                            row.summary().replyEmail()));
        });
        return found == null ? Optional.empty() : found;
    }

    /** 운영자 화면의 요약. 회신 이메일은 마스킹한다. */
    private static OperatorInquiry summary(SupportInquiryRepository.SummaryRow row) {
        return new OperatorInquiry(row.reference(), row.category(), row.title(), row.status(), row.receivedAt(),
                row.targetAt(), row.targetState(), row.firstAnsweredAt(), row.targetMet(), row.contact(),
                row.replyEmail() == null ? null : InquiryRules.maskEmail(row.replyEmail()), row.resolvedAt());
    }

    /**
     * 조회 트랜잭션의 시작: 보존 기간이 지난 해결 문의를 지우고, 지금 달력이 그 연도들을 담게 된 미정 목표를 채운다(목표는 접수
     * 시각으로 다시 계산한다 — 이미 정해진 목표는 고르지 않는다). 미정 행이 없으면 달력을 읽지 않는다.
     */
    private void purge() {
        this.inquiries.purgeExpired(RETENTION_AFTER_RESOLVED.toSeconds());
        var pending = this.inquiries.pendingTargets();
        if (pending.isEmpty()) {
            return;
        }
        BusinessDayCalendar calendar = this.inquiries.calendar(CALENDAR_VERSION);
        for (var row : pending) {
            calendar.targetFor(row.receivedAt()).ifPresent(
                    target -> this.inquiries.fillTarget(row.inquiryId(), target, calendar.version()));
        }
    }

    // ------------------------------------------------------------------ 집계

    @Override
    public Metrics metrics(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_METRICS_DAYS) {
            throw new IllegalArgumentException("집계 기간이 거꾸로이거나 너무 길다.");
        }
        Metrics computed = this.transactions.execute(status -> {
            purge();
            Instant asOf = this.inquiries.transactionTime();
            Instant start = from.atStartOfDay(BusinessDayCalendar.SEOUL).toInstant();
            Instant until = to.plusDays(1).atStartOfDay(BusinessDayCalendar.SEOUL).toInstant();
            Map<String, BusinessDayCalendar> calendars = new HashMap<>();
            Instant horizon = asOf.minus(RETENTION_AFTER_RESOLVED);
            return compute(from, to, asOf, calendars.computeIfAbsent(CALENDAR_VERSION, this.inquiries::calendar),
                    version -> calendars.computeIfAbsent(version, this.inquiries::calendar),
                    this.inquiries.due(start, until, asOf), this.inquiries.calendarPending(start, until),
                    start.isBefore(horizon) ? horizon : null);
        });
        if (computed == null) {
            throw new IllegalStateException("집계를 읽지 못했다.");
        }
        return computed;
    }

    /**
     * 집계 계산(순수). 분자는 첫 답변이 목표 시각 이전인 것, 처리 영업시간은 답변한 문의마다 접수 → 첫 답변 사이 영업시간이고 그
     * 문의의 목표를 정한 달력 버전({@code calendarOf})으로 센다. 달력이 담지 않은 날을 지나는 문의는 영업시간에서 뺀다. 달성
     * "선언"은 없다 — 수만 돌려준다.
     *
     * @param current 지금 달력. 응답의 달력 버전이다
     * @param purgeHorizon 기간이 보존 경계보다 앞서면 그 경계. 아니면 {@code null}
     */
    static Metrics compute(LocalDate from, LocalDate to, Instant asOf, BusinessDayCalendar current,
            Function<String, BusinessDayCalendar> calendarOf, List<SupportInquiryRepository.DueRow> due,
            long calendarPending, @Nullable Instant purgeHorizon) {
        long met = 0;
        List<Long> minutes = new ArrayList<>();
        for (var row : due) {
            Instant answered = row.firstAnsweredAt();
            if (answered == null) {
                continue;
            }
            if (!answered.isAfter(row.targetAt())) {
                met++;
            }
            OptionalLong seconds = calendarOf.apply(row.calendarVersion()).businessSeconds(row.receivedAt(), answered);
            if (seconds.isPresent()) {
                minutes.add(seconds.getAsLong() / 60);
            }
        }
        BigDecimal rate = due.isEmpty() ? null
                : BigDecimal.valueOf(met).multiply(HUNDRED).divide(BigDecimal.valueOf(due.size()), 1,
                        RoundingMode.HALF_UP);
        minutes.sort(Long::compare);
        long total = minutes.stream().mapToLong(Long::longValue).sum();
        BigDecimal median = null;
        int count = minutes.size();
        if (count > 0) {
            median = count % 2 == 1 ? BigDecimal.valueOf(minutes.get(count / 2)).setScale(1, RoundingMode.UNNECESSARY)
                    : BigDecimal.valueOf(minutes.get(count / 2 - 1) + minutes.get(count / 2))
                            .divide(TWO, 1, RoundingMode.UNNECESSARY);
        }
        return new Metrics(from, to, asOf, current.version(), due.size(), met, rate, calendarPending, count, total,
                median, purgeHorizon);
    }

    // ------------------------------------------------------------------ 도구

    private static <E extends Enum<E>> @Nullable E enumOf(Class<E> type, @Nullable String text) {
        if (text == null) {
            return null;
        }
        for (E known : type.getEnumConstants()) {
            if (known.name().equals(text.strip())) {
                return known;
            }
        }
        return null;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다.", impossible);
        }
    }
}
