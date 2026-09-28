package com.bokrilog.calculation.internal;

import com.bokrilog.calculation.PlanCalculator.Period;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code CALENDAR-SEOUL-1.0}의 주기 경계와 {@code periods(a,b)}.
 *
 * <p>경계는 {@code t0}의 {@code Asia/Seoul} 현지 날짜·시각에 고정한다. 일은 +1일, 주는 +7일, 월은
 * +k개월이다. 월 경계는 언제나 <b>{@code t0}에서</b> k개월을 더해 만든다 — 앞 경계에서 한 달씩
 * 더하면 1월 31일 시작이 2월 29일 뒤로 29일에 머문다. 기준 일이 없는 달은 말일이 되고 다음 달에는
 * 원래 일로 돌아온다(CV-09).
 *
 * <p>{@code periods(a,b)}는 {@code [a,b]}가 걸치는 구간마다 {@code 겹친 UTC ms / 그 구간의 실제 UTC
 * 길이 ms}를 정확한 분수로 누적한다. 시간대 오프셋 변화와 윤년은 구간의 실제 길이에 반영된다.
 */
public final class SeoulCalendar {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 경계 생성 상한. 계획은 최대 1년이라 일 주기도 367개면 충분하다. 이 수를 넘기는 입력은 계획
     * 계약 밖이므로 무한히 경계를 만들지 않고 거절한다.
     */
    static final int MAX_BOUNDARIES = 2_000;

    private final LocalDateTime anchor;
    private final Period period;
    private final List<Long> boundaries;

    private SeoulCalendar(Instant startAt, Period period, Instant horizon) {
        this.anchor = LocalDateTime.ofInstant(startAt, ZONE);
        this.period = period;
        List<Long> built = new ArrayList<>();
        built.add(startAt.toEpochMilli());
        long last = startAt.toEpochMilli();
        for (int k = 1; last <= horizon.toEpochMilli(); k++) {
            if (k > MAX_BOUNDARIES) throw new IllegalArgumentException("calendar horizon too far");
            long next = boundary(k).toEpochMilli();
            if (next <= last) throw new IllegalArgumentException("non-increasing calendar boundary");
            built.add(next);
            last = next;
        }
        this.boundaries = Collections.unmodifiableList(built);
    }

    /**
     * {@code startAt}에 고정한 달력. {@code horizon}을 넘는 첫 경계까지 만든다.
     *
     * @throws IllegalArgumentException 경계가 상한을 넘는다
     */
    public static SeoulCalendar anchoredAt(Instant startAt, Period period, Instant horizon) {
        return new SeoulCalendar(startAt, period, horizon);
    }

    /** k번째 경계. 0번째가 {@code t0}이다. */
    public Instant boundary(int k) {
        LocalDateTime local = switch (this.period) {
            case DAILY -> this.anchor.plusDays(k);
            case WEEKLY -> this.anchor.plusWeeks(k);
            case MONTHLY -> this.anchor.plusMonths(k);
        };
        return local.atZone(ZONE).toInstant();
    }

    /**
     * {@code periods(a,b)}의 정확한 값.
     *
     * @throws IllegalArgumentException {@code a < t0}, {@code b < a}, 또는 {@code b}가 만든 경계 밖이다
     */
    public Fraction periods(Instant a, Instant b) {
        long from = a.toEpochMilli();
        long to = b.toEpochMilli();
        if (from < this.boundaries.getFirst() || to < from || to > this.boundaries.getLast())
            throw new IllegalArgumentException("periods outside the calendar");
        Fraction total = Fraction.ZERO;
        for (int k = intervalOf(from); k < this.boundaries.size() - 1; k++) {
            long start = this.boundaries.get(k);
            long end = this.boundaries.get(k + 1);
            if (start >= to) break;
            long overlap = Math.min(end, to) - Math.max(start, from);
            if (overlap > 0) total = total.plus(Fraction.of(overlap, end - start));
        }
        return total;
    }

    /** {@code millis}가 속한 구간의 시작 경계 번호. 구간은 {@code [B_k, B_{k+1})}이다. */
    private int intervalOf(long millis) {
        int index = Collections.binarySearch(this.boundaries, millis);
        return index >= 0 ? index : -index - 2;
    }
}
