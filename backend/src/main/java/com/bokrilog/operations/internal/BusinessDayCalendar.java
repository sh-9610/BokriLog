package com.bokrilog.operations.internal;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 대한민국 영업일 달력 한 버전과 그 위의 순수 계산(Story 3.6 AC-3, {@code SUPPORT-1.0}).
 *
 * <p><b>순수 함수다.</b> 데이터베이스·시계·Spring을 모른다 — 달력 값(버전·담은 연도·공휴일)은 호출자가 seed 표에서 읽어
 * 넘기고, 기준 시각도 호출자(DB 시각)가 준다. 그래서 고정 fixture로 경계 시각을 그대로 시험한다({@code
 * BusinessDayCalendarTest}).
 *
 * <p>영업일은 {@code Asia/Seoul} 월~금 가운데 이 달력의 공휴일이 아닌 날이다. 이 달력이 담지 않은 연도의 날은 영업일인지
 * 모른다 — 공휴일이 없는 해로 추측하지 않고 계산 전체를 "정하지 못함"으로 답한다.
 *
 * @param version 달력 버전(예: {@code KR-HOLIDAY-2026.1})
 * @param years 이 버전이 담은 연도
 * @param holidays 그 연도들의 공휴일
 */
record BusinessDayCalendar(String version, Set<Integer> years, Set<LocalDate> holidays) {

    /** 영업일 판정의 시간대. 문의 행의 {@code timezone} 열과 같은 값이다. */
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 답변 목표는 접수 다음 영업일부터 세어 이 번째 영업일이다. */
    static final int TARGET_BUSINESS_DAYS = 3;

    /** 답변 목표 시각과 영업시간의 끝(KST). */
    static final LocalTime BUSINESS_END = LocalTime.of(18, 0);

    /** 영업시간의 시작(KST). */
    static final LocalTime BUSINESS_START = LocalTime.of(9, 0);

    /** 영업시간 계산이 훑는 날 수의 상한. 그보다 긴 구간은 계산하지 않는다(보존 90일·목표 3영업일보다 넉넉하다). */
    static final int MAX_SCANNED_DAYS = 800;

    BusinessDayCalendar {
        years = Set.copyOf(years);
        holidays = Set.copyOf(holidays);
    }

    /** 이 달력이 그 날을 담는가. */
    boolean covers(LocalDate day) {
        return this.years.contains(day.getYear());
    }

    /**
     * 영업일인가. 이 달력이 담지 않은 날이면 빈 값이다 — 영업일이라고도 아니라고도 말하지 않는다.
     */
    Optional<Boolean> isBusinessDay(LocalDate day) {
        if (!covers(day)) {
            return Optional.empty();
        }
        DayOfWeek weekday = day.getDayOfWeek();
        boolean weekend = weekday == DayOfWeek.SATURDAY || weekday == DayOfWeek.SUNDAY;
        return Optional.of(!weekend && !this.holidays.contains(day));
    }

    /**
     * 답변 목표 시각: 접수 시각의 KST 날짜 D 다음 날부터 센 영업일 가운데 세 번째 영업일의 18:00 KST.
     *
     * <p>D 자신은 세지 않는다 — 평일 오전에 접수해도 그날은 첫 영업일이 아니다. 세는 도중 이 달력이 담지 않은 연도의 날을
     * 만나면 빈 값이다({@code CALENDAR_PENDING}).
     */
    Optional<Instant> targetFor(Instant receivedAt) {
        LocalDate day = receivedAt.atZone(SEOUL).toLocalDate();
        int counted = 0;
        for (int scanned = 0; scanned < MAX_SCANNED_DAYS; scanned++) {
            day = day.plusDays(1);
            Optional<Boolean> business = isBusinessDay(day);
            if (business.isEmpty()) {
                return Optional.empty();
            }
            if (business.get() && ++counted == TARGET_BUSINESS_DAYS) {
                return Optional.of(ZonedDateTime.of(day, BUSINESS_END, SEOUL).toInstant());
            }
        }
        return Optional.empty();
    }

    /**
     * 두 시각 사이의 영업시간(초): 영업일의 09:00~18:00 KST와 겹치는 길이의 합. {@code to}가 {@code from}보다 앞이면 0이다.
     * 구간이 이 달력이 담지 않은 날을 지나거나 {@value #MAX_SCANNED_DAYS}일보다 길면 빈 값이다 — 모르는 날을 0시간으로도
     * 9시간으로도 세지 않는다.
     */
    OptionalLong businessSeconds(Instant from, Instant to) {
        if (!to.isAfter(from)) {
            return OptionalLong.of(0);
        }
        LocalDate day = from.atZone(SEOUL).toLocalDate();
        LocalDate last = to.atZone(SEOUL).toLocalDate();
        long total = 0;
        for (int scanned = 0; !day.isAfter(last); scanned++, day = day.plusDays(1)) {
            if (scanned >= MAX_SCANNED_DAYS) {
                return OptionalLong.empty();
            }
            Optional<Boolean> business = isBusinessDay(day);
            if (business.isEmpty()) {
                return OptionalLong.empty();
            }
            if (!business.get()) {
                continue;
            }
            Instant open = ZonedDateTime.of(day, BUSINESS_START, SEOUL).toInstant();
            Instant close = ZonedDateTime.of(day, BUSINESS_END, SEOUL).toInstant();
            Instant start = from.isAfter(open) ? from : open;
            Instant end = to.isBefore(close) ? to : close;
            if (end.isAfter(start)) {
                total += Duration.between(start, end).toSeconds();
            }
        }
        return OptionalLong.of(total);
    }
}
