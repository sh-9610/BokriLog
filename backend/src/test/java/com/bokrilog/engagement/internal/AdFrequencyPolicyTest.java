package com.bokrilog.engagement.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.engagement.AdEligibilityApi.SkipReason;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Story 4.2: 광고 빈도 제한의 순수 판정. 데이터베이스도 시계도 쓰지 않는다 — 기준 시각과 최근 자격 시각들을 그대로 넣는다(가상
 * 시계). 30분 간격의 경계, {@code Asia/Seoul} 자정 전후, UTC 자정이 KST 날짜를 가르지 않음, 다른 지역(미국)의 DST 전환일과 무관함,
 * 하루 3회 상한과 두 사유가 겹칠 때의 우선을 본다.
 */
class AdFrequencyPolicyTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    void 기록이_없으면_자격이다() {
        assertThat(AdFrequencyPolicy.judge(kst("2026-10-15T12:00:00"), List.of())).isEmpty();
    }

    @Test
    void 마지막_자격_뒤_30분_미만은_간격이고_정확히_30분이면_허용이다() {
        Instant last = kst("2026-10-15T12:00:00");
        assertThat(judge(last.plus(Duration.ofMinutes(30)).minusSeconds(1), last)).contains(SkipReason.INTERVAL);
        assertThat(judge(last.plus(Duration.ofMinutes(30)).minusMillis(1), last)).contains(SkipReason.INTERVAL);
        assertThat(judge(last.plus(Duration.ofMinutes(30)), last)).isEmpty();
        assertThat(judge(last.plus(Duration.ofMinutes(31)), last)).isEmpty();
        // 간격은 가장 늦은 자격에서 잰다 — 목록 순서와 무관하다.
        assertThat(AdFrequencyPolicy.judge(last.plus(Duration.ofMinutes(20)),
                List.of(last, last.minus(Duration.ofHours(2))))).contains(SkipReason.INTERVAL);
        assertThat(AdFrequencyPolicy.judge(last.plus(Duration.ofMinutes(20)),
                List.of(last.minus(Duration.ofHours(2)), last))).contains(SkipReason.INTERVAL);
    }

    @Test
    void 미래의_자격_시각은_간격을_채우지_못한_것으로_본다() {
        Instant now = kst("2026-10-15T12:00:00");
        assertThat(judge(now, now.plusSeconds(5))).contains(SkipReason.INTERVAL);
    }

    @Test
    void KST_같은_날짜의_자격이_3건이면_하루_상한이고_2건이면_아니다() {
        Instant now = kst("2026-10-15T18:00:00");
        List<Instant> three = List.of(kst("2026-10-15T09:00:00"), kst("2026-10-15T11:00:00"),
                kst("2026-10-15T13:00:00"));
        assertThat(AdFrequencyPolicy.judge(now, three)).contains(SkipReason.DAILY_CAP);
        assertThat(AdFrequencyPolicy.judge(now, three.subList(1, 3))).isEmpty();
        // 상한은 3이다.
        assertThat(AdFrequencyPolicy.DAILY_CAP).isEqualTo(3);
    }

    @Test
    void 두_사유가_함께면_하루_상한이다() {
        Instant now = kst("2026-10-15T13:10:00");
        List<Instant> three = List.of(kst("2026-10-15T09:00:00"), kst("2026-10-15T11:00:00"),
                kst("2026-10-15T13:00:00"));
        assertThat(AdFrequencyPolicy.judge(now, three)).contains(SkipReason.DAILY_CAP);
    }

    @Test
    void KST_자정을_넘으면_전날의_3건은_세지_않는다() {
        // 3건이 전날 23:59 KST대. 자정 뒤 30분이 지나면 자격이다.
        List<Instant> yesterday = List.of(kst("2026-10-14T23:59:00"), kst("2026-10-14T23:59:20"),
                kst("2026-10-14T23:59:40"));
        assertThat(AdFrequencyPolicy.judge(kst("2026-10-15T00:29:40"), yesterday)).isEmpty();
        // 자정 직후는 날짜가 바뀌었어도 간격에 걸린다(상한은 아니다).
        assertThat(AdFrequencyPolicy.judge(kst("2026-10-15T00:00:01"), yesterday)).contains(SkipReason.INTERVAL);
        // 자정 직전은 같은 날짜라 상한이다.
        assertThat(AdFrequencyPolicy.judge(kst("2026-10-14T23:59:59"), yesterday)).contains(SkipReason.DAILY_CAP);
        // 자정 정각은 새 날짜다.
        List<Instant> earlier = List.of(kst("2026-10-14T09:00:00"), kst("2026-10-14T12:00:00"),
                kst("2026-10-14T15:00:00"));
        assertThat(AdFrequencyPolicy.judge(kst("2026-10-15T00:00:00"), earlier)).isEmpty();
        assertThat(AdFrequencyPolicy.judge(kst("2026-10-15T00:00:00").minusMillis(1), earlier))
                .contains(SkipReason.DAILY_CAP);
    }

    @Test
    void UTC_자정은_KST_날짜를_가르지_않는다() {
        // KST 08:00·08:30·08:50은 UTC로 전날 23시대이고, KST 09:30은 UTC로 다음 날 00:30이다. KST로는 모두 같은 날이다.
        List<Instant> morning = List.of(kst("2026-10-15T08:00:00"), kst("2026-10-15T08:30:00"),
                kst("2026-10-15T08:50:00"));
        Instant now = kst("2026-10-15T09:30:00");
        assertThat(LocalDateTime.ofInstant(morning.getFirst(), ZoneId.of("UTC")).toLocalDate())
                .isNotEqualTo(LocalDateTime.ofInstant(now, ZoneId.of("UTC")).toLocalDate());
        assertThat(AdFrequencyPolicy.judge(now, morning)).contains(SkipReason.DAILY_CAP);
    }

    @Test
    void 미국_DST_전환일과_무관하다() {
        // 2026-03-08(미국 DST 시작)과 2026-11-01(종료). 서울에는 DST가 없으므로 KST 하루는 24시간 그대로다.
        for (String day : List.of("2026-03-08", "2026-11-01")) {
            List<Instant> three = List.of(kst(day + "T00:10:00"), kst(day + "T08:00:00"), kst(day + "T16:00:00"));
            assertThat(AdFrequencyPolicy.judge(kst(day + "T23:59:59"), three)).as(day).contains(SkipReason.DAILY_CAP);
            Instant nextDay = kst(day + "T00:00:00").plus(Duration.ofHours(24));
            assertThat(AdFrequencyPolicy.judge(nextDay, three)).as(day).isEmpty();
            // 30분은 실제 경과 시간이다 — 미국 시계가 한 시간 뛰는 순간에도 같다.
            Instant last = Instant.parse(day + "T07:00:00Z");
            assertThat(judge(last.plus(Duration.ofMinutes(30)), last)).as(day).isEmpty();
            assertThat(judge(last.plus(Duration.ofMinutes(30)).minusSeconds(1), last)).as(day)
                    .contains(SkipReason.INTERVAL);
        }
        assertThat(AdFrequencyPolicy.ZONE).isEqualTo(SEOUL);
    }

    private static Optional<SkipReason> judge(Instant now, Instant last) {
        return AdFrequencyPolicy.judge(now, List.of(last));
    }

    private static Instant kst(String local) {
        return LocalDateTime.parse(local).atZone(SEOUL).toInstant();
    }
}
