package com.bokrilog.operations.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 감사 기간 — {@code Asia/Seoul}의 달력 날짜 두 개, 양 끝 포함.
 *
 * <p>추출 command의 target({@code 2026-10-01~2026-10-07})과 조회 필터({@code from}·{@code to})가 같은 해석을 쓴다.
 * 운영 대응 시간이 대한민국 영업일이라 사람이 읽는 날짜도 KST다. 원장의 {@code recorded_at}과는 그 날의 00:00 KST부터
 * 다음 날 00:00 KST 전까지로 비교한다.
 *
 * @param from 첫 날
 * @param to 마지막 날(포함)
 */
record AuditPeriod(LocalDate from, LocalDate to) {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final Pattern TARGET = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})~(\\d{4}-\\d{2}-\\d{2})");

    /** 기간의 시작 시각(포함). */
    Instant start() {
        return startOf(this.from);
    }

    /** 기간의 끝 시각(제외) — 마지막 날 다음 날의 00:00 KST. */
    Instant end() {
        return startOf(this.to.plusDays(1));
    }

    /** target 표기. 파싱의 역이다. */
    String target() {
        return this.from + "~" + this.to;
    }

    long days() {
        return ChronoUnit.DAYS.between(this.from, this.to) + 1;
    }

    static Instant startOf(LocalDate day) {
        return day.atStartOfDay(SEOUL).toInstant();
    }

    /** {@code YYYY-MM-DD~YYYY-MM-DD}. 형식이 아니거나 거꾸로면 {@code null}이다. */
    static @Nullable AuditPeriod parseTarget(@Nullable String text) {
        if (text == null) {
            return null;
        }
        var matcher = TARGET.matcher(text.strip());
        if (!matcher.matches()) {
            return null;
        }
        LocalDate from = parseDate(matcher.group(1));
        LocalDate to = parseDate(matcher.group(2));
        if (from == null || to == null || to.isBefore(from)) {
            return null;
        }
        return new AuditPeriod(from, to);
    }

    /** {@code YYYY-MM-DD} 하나. 형식이 아니거나 없는 날이면 {@code null}이다. */
    static @Nullable LocalDate parseDate(@Nullable String text) {
        if (text == null || !DATE.matcher(text).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException impossible) {
            return null;
        }
    }
}
