package com.bokrilog.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.operations.SupportInquiryApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Story 3.6 AC-3: 영업일 달력과 답변 목표 계산의 고정 fixture 시험. 데이터베이스도 시계도 쓰지 않는다 — 접수 시각을 그대로
 * 넣고 목표 시각을 단정한다.
 *
 * <p>fixture는 SPEC Code Map의 공휴일 목록(2026·2027)이고, 마이그레이션 seed가 같은 날짜 집합인지도 여기서 단정한다 —
 * seed를 고치면 fixture도 고쳐야 한다(이 시험이 그 짝을 강제한다).
 */
class BusinessDayCalendarTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** {@code KR-HOLIDAY-2026.1}: 2026은 법령 개정·인사혁신처 공지, 2027은 우주항공청 월력요항. */
    static final Set<LocalDate> HOLIDAYS = Stream.of(
                    "2026-01-01", "2026-02-16", "2026-02-17", "2026-02-18", "2026-03-01", "2026-03-02", "2026-05-01",
                    "2026-05-05", "2026-05-24", "2026-05-25", "2026-06-03", "2026-06-06", "2026-07-17", "2026-08-15",
                    "2026-08-17", "2026-09-24", "2026-09-25", "2026-09-26", "2026-10-03", "2026-10-05", "2026-10-09",
                    "2026-12-25",
                    "2027-01-01", "2027-02-06", "2027-02-07", "2027-02-08", "2027-02-09", "2027-03-01", "2027-05-01",
                    "2027-05-03", "2027-05-05", "2027-05-13", "2027-06-06", "2027-07-17", "2027-07-19", "2027-08-15",
                    "2027-08-16", "2027-09-14", "2027-09-15", "2027-09-16", "2027-10-03", "2027-10-04", "2027-10-09",
                    "2027-10-11", "2027-12-25", "2027-12-27")
            .map(LocalDate::parse)
            .collect(Collectors.toUnmodifiableSet());

    static final BusinessDayCalendar CALENDAR =
            new BusinessDayCalendar(SupportInquiryApi.CALENDAR_VERSION, Set.of(2026, 2027), HOLIDAYS);

    private static Instant kst(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SEOUL).toInstant();
    }

    private static Instant target(String receivedKst) {
        return CALENDAR.targetFor(kst(receivedKst)).orElseThrow();
    }

    // ------------------------------------------------------------------ SPEC 매트릭스의 날짜 넷과 달력 없음

    @Test
    void 매트릭스의_날짜_넷이_그_목표_시각이다() {
        // 접수 2026-10-12(월) 10:00 → 10/13·10/14·10/15 → 10/15 18:00 KST.
        assertThat(target("2026-10-12T10:00")).isEqualTo(kst("2026-10-15T18:00"));
        // 2026-10-08(목) 20:00 → 10/9 한글날·주말 → 10/12·10/13·10/14 → 10/14 18:00.
        assertThat(target("2026-10-08T20:00")).isEqualTo(kst("2026-10-14T18:00"));
        // 2026-12-30(수) → 12/31·(1/1 신정·주말)·1/4·1/5 → 2027-01-05 18:00.
        assertThat(target("2026-12-30T11:00")).isEqualTo(kst("2027-01-05T18:00"));
        // 2027-02-05(금) → 설 연휴 2/6~2/8·대체 2/9 → 2/10·2/11·2/12 → 2/12 18:00.
        assertThat(target("2027-02-05T09:30")).isEqualTo(kst("2027-02-12T18:00"));
    }

    @Test
    void 달력이_담지_않은_연도를_지나야_하면_목표를_정하지_않는다() {
        // 2027-12-30(목) → 12/31(금)은 영업일, 그다음 2028-01-01은 달력에 없다.
        assertThat(CALENDAR.targetFor(kst("2027-12-30T10:00"))).isEmpty();
        // 2027-12-27(월, 대체공휴일) 접수 → 12/28·12/29·12/30 — 2028을 지나지 않으므로 정해진다.
        assertThat(target("2027-12-27T10:00")).isEqualTo(kst("2027-12-30T18:00"));
        // 접수 날짜 자체가 달력 밖이면(2025) 다음 날부터 세므로 그 해의 날을 만나는 즉시 미정이다.
        assertThat(CALENDAR.targetFor(kst("2025-12-30T10:00"))).isEmpty();
        // 담은 연도가 없는 달력(버전 행 없음)은 모든 목표가 미정이다.
        assertThat(new BusinessDayCalendar("KR-HOLIDAY-2099.1", Set.of(), Set.of())
                .targetFor(kst("2026-10-12T10:00"))).isEmpty();
    }

    // ------------------------------------------------------------------ 경계 시각

    @Test
    void 금요일_17시59분_접수는_다음_주_수요일이다() {
        // 2026-10-16(금) 17:59 → 그날은 세지 않는다 → 10/19·10/20·10/21 → 10/21 18:00.
        assertThat(target("2026-10-16T17:59")).isEqualTo(kst("2026-10-21T18:00"));
        assertThat(target("2026-10-16T18:01")).isEqualTo(kst("2026-10-21T18:00"));
    }

    @Test
    void 토요일_접수는_월화수를_센다() {
        assertThat(target("2026-10-17T12:00")).isEqualTo(kst("2026-10-21T18:00"));
        assertThat(target("2026-10-18T23:59:59")).isEqualTo(kst("2026-10-21T18:00"));
    }

    @Test
    void 공휴일_전날_23시59분과_공휴일_당일_접수() {
        // 2026-10-08(목) 23:59 → 10/9(금, 한글날) 건너뜀 → 10/12·10/13·10/14.
        assertThat(target("2026-10-08T23:59")).isEqualTo(kst("2026-10-14T18:00"));
        // 한글날 당일 00:00 접수 → 10/12·10/13·10/14.
        assertThat(target("2026-10-09T00:00")).isEqualTo(kst("2026-10-14T18:00"));
        // 추석 전날 2026-09-23(수) 23:59 → 9/24~9/26 연휴 → 9/28·9/29·9/30.
        assertThat(target("2026-09-23T23:59")).isEqualTo(kst("2026-09-30T18:00"));
    }

    @Test
    void 연말_경계() {
        // 2026-12-31(목) 23:59 → 1/1 신정·주말 → 1/4·1/5·1/6.
        assertThat(target("2026-12-31T23:59")).isEqualTo(kst("2027-01-06T18:00"));
        // 성탄 전날 2026-12-24(목) → 12/25 성탄 → 12/28·12/29·12/30.
        assertThat(target("2026-12-24T15:00")).isEqualTo(kst("2026-12-30T18:00"));
    }

    @Test
    void UTC_자정_경계는_KST_날짜로_가른다() {
        // 2026-10-12T14:59:59Z = 10/12(월) 23:59:59 KST → D=10/12 → 10/15 18:00.
        assertThat(CALENDAR.targetFor(Instant.parse("2026-10-12T14:59:59Z"))).hasValue(kst("2026-10-15T18:00"));
        // 2026-10-12T15:00:00Z = 10/13(화) 00:00 KST → D=10/13 → 10/14·10/15·10/16 → 10/16 18:00.
        assertThat(CALENDAR.targetFor(Instant.parse("2026-10-12T15:00:00Z"))).hasValue(kst("2026-10-16T18:00"));
        // UTC 날짜로는 10/12 00:00이지만 KST로는 10/12 09:00이다 — 같은 D.
        assertThat(CALENDAR.targetFor(Instant.parse("2026-10-12T00:00:00Z"))).hasValue(kst("2026-10-15T18:00"));
        // 목표 시각 자체는 09:00Z(18:00 KST)다.
        assertThat(target("2026-10-12T10:00")).isEqualTo(Instant.parse("2026-10-15T09:00:00Z"));
    }

    // ------------------------------------------------------------------ 영업시간

    @Test
    void 영업시간은_영업일의_9시부터_18시만_센다() {
        // 같은 영업일 10:00 → 15:30: 5시간 30분.
        assertThat(CALENDAR.businessSeconds(kst("2026-10-12T10:00"), kst("2026-10-12T15:30")))
                .hasValue(5 * 3600 + 1800);
        // 영업시간 밖의 끝은 18:00에서 자른다: 월 17:00 → 화 10:00 = 1시간 + 1시간.
        assertThat(CALENDAR.businessSeconds(kst("2026-10-12T17:00"), kst("2026-10-13T10:00"))).hasValue(2 * 3600);
        // 금 17:00 → 다음 주 월 09:30, 그 사이 주말 0시간: 1시간 + 30분.
        assertThat(CALENDAR.businessSeconds(kst("2026-10-16T17:00"), kst("2026-10-19T09:30"))).hasValue(5400);
        // 공휴일(한글날)은 0시간: 목 20:00 → 월 11:00 = 2시간.
        assertThat(CALENDAR.businessSeconds(kst("2026-10-08T20:00"), kst("2026-10-12T11:00"))).hasValue(2 * 3600);
        // 거꾸로이거나 같은 시각은 0.
        assertThat(CALENDAR.businessSeconds(kst("2026-10-12T11:00"), kst("2026-10-12T10:00"))).hasValue(0);
        // 달력 밖의 날을 지나면 세지 않는다.
        assertThat(CALENDAR.businessSeconds(kst("2027-12-30T10:00"), kst("2028-01-03T10:00"))).isEmpty();
    }

    // ------------------------------------------------------------------ 집계(순수)

    @Test
    void 집계는_목표가_지난_넷_가운데_셋이_목표_안이면_75퍼센트이고_미정은_따로_센다() {
        Instant asOf = kst("2026-10-30T12:00");
        var due = List.of(
                // 목표 안: 접수 월 10:00 → 화 11:00 답변(영업 10시간: 월 8 + 화 2).
                new SupportInquiryRepository.DueRow(kst("2026-10-12T10:00"), kst("2026-10-15T18:00"),
                        kst("2026-10-13T11:00"), SupportInquiryApi.CALENDAR_VERSION),
                // 목표 안: 목표 시각 정각 답변(포함). 영업시간 = 월 10:00~18:00 + 화·수 9시간씩 + 목 9시간 = 35시간.
                new SupportInquiryRepository.DueRow(kst("2026-10-12T10:00"), kst("2026-10-15T18:00"),
                        kst("2026-10-15T18:00"), SupportInquiryApi.CALENDAR_VERSION),
                // 목표 안: 같은 날 30분 뒤.
                new SupportInquiryRepository.DueRow(kst("2026-10-19T10:00"), kst("2026-10-22T18:00"),
                        kst("2026-10-19T10:30"), SupportInquiryApi.CALENDAR_VERSION),
                // 목표 넘김: 목표 1분 뒤. 영업시간에는 들지 않는 18:01까지 = 35시간.
                new SupportInquiryRepository.DueRow(kst("2026-10-12T10:00"), kst("2026-10-15T18:00"),
                        kst("2026-10-15T18:01"), SupportInquiryApi.CALENDAR_VERSION));
        var metrics = SupportInquiryService.compute(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"),
                asOf, CALENDAR, version -> CALENDAR, due, 1, null);
        assertThat(metrics.due()).isEqualTo(4);
        assertThat(metrics.metTarget()).isEqualTo(3);
        assertThat(metrics.ratePercent()).isEqualByComparingTo(new BigDecimal("75.0"));
        assertThat(metrics.calendarPending()).isEqualTo(1);
        assertThat(metrics.measured()).isEqualTo(4);
        // 분: 600, 2100, 30, 2100 → 합 4830, 정렬 30·600·2100·2100 → 중앙값 (600+2100)/2 = 1350.0.
        assertThat(metrics.totalBusinessMinutes()).isEqualTo(4830);
        assertThat(metrics.medianBusinessMinutes()).isEqualByComparingTo(new BigDecimal("1350.0"));
        assertThat(metrics.calendarVersion()).isEqualTo(SupportInquiryApi.CALENDAR_VERSION);
    }

    @Test
    void 답변이_없는_문의는_분모에만_들고_분모가_0이면_달성률이_없다() {
        Instant asOf = kst("2026-10-30T12:00");
        var unanswered = List.of(new SupportInquiryRepository.DueRow(kst("2026-10-12T10:00"),
                kst("2026-10-15T18:00"), null, SupportInquiryApi.CALENDAR_VERSION));
        var one = SupportInquiryService.compute(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), asOf,
                CALENDAR, version -> CALENDAR, unanswered, 0, null);
        assertThat(one.due()).isEqualTo(1);
        assertThat(one.metTarget()).isZero();
        assertThat(one.ratePercent()).isEqualByComparingTo(new BigDecimal("0.0"));
        assertThat(one.measured()).isZero();
        assertThat(one.medianBusinessMinutes()).isNull();

        var none = SupportInquiryService.compute(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), asOf,
                CALENDAR, version -> CALENDAR, List.of(), 2, null);
        assertThat(none.due()).isZero();
        assertThat(none.ratePercent()).isNull();
        assertThat(none.calendarPending()).isEqualTo(2);
    }

    @Test
    void 영업시간은_그_문의의_목표를_정한_달력_버전으로_센다() {
        // 옛 버전 달력은 10/12(월)을 공휴일로 두었다고 하자. 그 버전으로 정한 문의는 그날을 영업시간에 넣지 않는다.
        var older = new BusinessDayCalendar("KR-HOLIDAY-2025.9", Set.of(2026, 2027),
                Set.of(LocalDate.parse("2026-10-12")));
        var row = new SupportInquiryRepository.DueRow(kst("2026-10-12T10:00"), kst("2026-10-15T18:00"),
                kst("2026-10-13T11:00"), "KR-HOLIDAY-2025.9");
        var metrics = SupportInquiryService.compute(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"),
                kst("2026-10-30T12:00"), CALENDAR, version -> version.equals(older.version()) ? older : CALENDAR,
                List.of(row), 0, kst("2026-08-01T00:00"));
        // 화 09:00~11:00 = 2시간(지금 달력이면 10시간).
        assertThat(metrics.totalBusinessMinutes()).isEqualTo(120);
        assertThat(metrics.calendarVersion()).isEqualTo(SupportInquiryApi.CALENDAR_VERSION);
        assertThat(metrics.purgeHorizon()).isEqualTo(kst("2026-08-01T00:00"));
    }

    // ------------------------------------------------------------------ seed = fixture

    /**
     * 지금 달력 버전({@link SupportInquiryApi#CALENDAR_VERSION})의 seed는 이 fixture와 같은 날짜다. 파일을 고정하지 않는다 —
     * 그 버전을 넣은 마이그레이션 가운데 가장 최신 것을 찾아 그 버전의 연도·공휴일 행을 읽는다. 새 버전이 오면 이 fixture도
     * 바꿔야 이 시험이 지난다.
     */
    @Test
    void 지금_버전의_마이그레이션_seed는_이_fixture와_같은_날짜이고_머리말에_출처와_확인일이_있다() throws Exception {
        String version = SupportInquiryApi.CALENDAR_VERSION;
        Pattern migrationName = Pattern.compile("V(\\d+)__.*\\.sql");
        Path latest = null;
        int latestNumber = -1;
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path file : files.toList()) {
                Matcher name = migrationName.matcher(file.getFileName().toString());
                if (!name.matches()
                        || !Files.readString(file, StandardCharsets.UTF_8).contains("'" + version + "'")) {
                    continue;
                }
                int number = Integer.parseInt(name.group(1));
                if (number > latestNumber) {
                    latestNumber = number;
                    latest = file;
                }
            }
        }
        assertThat(latest).as("지금 달력 버전을 넣은 마이그레이션").isNotNull();
        String sql = Files.readString(latest, StandardCharsets.UTF_8);
        String quoted = Pattern.quote(version);
        Matcher dates = Pattern.compile("\\('" + quoted + "',\\s*(\\d{4}),\\s*DATE '(\\d{4}-\\d{2}-\\d{2})'")
                .matcher(sql);
        Set<LocalDate> seeded = new TreeSet<>();
        while (dates.find()) {
            seeded.add(LocalDate.parse(dates.group(2)));
        }
        Matcher years = Pattern.compile("\\('" + quoted + "',\\s*(\\d{4})\\)").matcher(sql);
        Set<Integer> seededYears = new TreeSet<>();
        while (years.find()) {
            seededYears.add(Integer.parseInt(years.group(1)));
        }
        assertThat(seeded).isEqualTo(new TreeSet<>(HOLIDAYS));
        assertThat(seededYears).isEqualTo(new TreeSet<>(CALENDAR.years()));
        assertThat(sql).contains("확인일").contains("월력요항");
    }
}
