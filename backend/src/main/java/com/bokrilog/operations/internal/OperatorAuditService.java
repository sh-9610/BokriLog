package com.bokrilog.operations.internal;

import com.bokrilog.operations.OperatorAuditApi;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 감사 원장의 조회(Story 3.1 AC-5). 읽기만 한다.
 *
 * <p>cursor는 마지막 행의 {@code (recorded_at, sequence)}를 base64url로 감싼 값이다. 뜻을 숨기려는 것이 아니라(값 자체는
 * 감사 행의 시각과 순번일 뿐이다) 클라이언트가 형식에 기대지 않게 하려는 것이며, 읽을 수 없는 값은 빈 결과가 아니라
 * 거절이다 — 조용히 첫 쪽을 돌려주면 "더 보기"가 같은 행을 다시 보여 준다.
 */
@Service
class OperatorAuditService implements OperatorAuditApi {

    /** PostgreSQL {@code timestamptz}가 비교할 수 있는 범위 안의 값만 cursor로 받는다. 밖은 읽을 수 없는 cursor다. */
    private static final Instant EARLIEST_CURSOR = Instant.parse("0001-01-01T00:00:00Z");

    private static final Instant LATEST_CURSOR = Instant.parse("9999-12-31T23:59:59.999999Z");

    private static final Pattern CURSOR = Pattern.compile("(-?\\d{1,19})\\.(\\d{1,9}):(\\d{1,19})");

    private final OperatorAuditRepository repository;

    OperatorAuditService(OperatorAuditRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Page> search(Filter filter, @Nullable String cursor, int limit) {
        Instant beforeAt = null;
        long beforeSequence = 0;
        if (cursor != null) {
            Position position = decode(cursor);
            if (position == null) {
                return Optional.empty();
            }
            beforeAt = position.recordedAt();
            beforeSequence = position.sequence();
        }
        int size = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        var search = new OperatorAuditRepository.Search(
                filter.from() == null ? null : AuditPeriod.startOf(filter.from()),
                filter.to() == null ? null : AuditPeriod.startOf(filter.to().plusDays(1)),
                filter.command(), filter.scope(), filter.outcome() == null ? null : filter.outcome().name(),
                beforeAt, beforeSequence, size + 1);
        List<Entry> rows = this.repository.search(search);
        if (rows.size() <= size) {
            return Optional.of(new Page(rows, null));
        }
        List<Entry> page = rows.subList(0, size);
        Entry last = page.get(size - 1);
        return Optional.of(new Page(List.copyOf(page), encode(last.recordedAt(), last.sequence())));
    }

    @Override
    public List<Entry> operation(UUID actorUserId, String operationId) {
        return this.repository.operation(actorUserId, operationId);
    }

    private record Position(Instant recordedAt, long sequence) {}

    static String encode(Instant recordedAt, long sequence) {
        String raw = recordedAt.getEpochSecond() + "." + recordedAt.getNano() + ":" + sequence;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    private static @Nullable Position decode(String cursor) {
        if (cursor.isEmpty() || cursor.length() > 128) {
            return null;
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
        var matcher = CURSOR.matcher(raw);
        if (!matcher.matches()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(matcher.group(1));
            int nanos = Integer.parseInt(matcher.group(2));
            long sequence = Long.parseLong(matcher.group(3));
            if (sequence < 1) {
                return null;
            }
            Instant at = Instant.ofEpochSecond(seconds, nanos);
            if (at.isBefore(EARLIEST_CURSOR) || at.isAfter(LATEST_CURSOR)) {
                // 데이터베이스가 받지 못하는 시각이다. 500이 아니라 읽을 수 없는 cursor(400)다.
                return null;
            }
            return new Position(at, sequence);
        } catch (RuntimeException outOfRange) {
            // 자릿수 상한 안이어도 Instant 범위를 넘을 수 있다. 읽을 수 없는 cursor와 같다.
            return null;
        }
    }
}
