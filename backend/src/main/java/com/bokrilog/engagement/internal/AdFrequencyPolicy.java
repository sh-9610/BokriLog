package com.bokrilog.engagement.internal;

import com.bokrilog.engagement.AdEligibilityApi.SkipReason;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Optional;

/**
 * 광고 빈도 제한의 순수 판정(Story 4.2). 시계·DB·Spring을 모른다 — 기준 시각과 최근 자격 시각들을 받는다.
 *
 * <ul>
 *   <li>{@link SkipReason#DAILY_CAP}: {@code now}와 {@code Asia/Seoul} 날짜가 같은 자격이 이미 {@link #DAILY_CAP}건이다.
 *   <li>{@link SkipReason#INTERVAL}: 가장 늦은 자격 뒤 {@link #INTERVAL}이 지나지 않았다({@code now < last + 30분}). 정확히
 *       30분이면 허용이다.
 * </ul>
 * 두 사유가 함께면 {@code DAILY_CAP}이다. 날짜 경계는 고정 오프셋이 아니라 {@code Asia/Seoul} 규칙으로 정한다 — UTC 자정·다른
 * 지역의 DST 전환은 날짜를 가르지 않는다. 미래의 자격 시각(DB 시계 역행)은 간격을 채우지 못한 것으로 본다 — 의심스러울 때 광고를
 * 줄이는 쪽이다.
 */
final class AdFrequencyPolicy {

    /** 마지막 자격 뒤의 최소 간격. */
    static final Duration INTERVAL = Duration.ofMinutes(30);

    /** {@code Asia/Seoul} 하루의 자격 상한. */
    static final int DAILY_CAP = 3;

    /** 하루 경계의 시간대. */
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private AdFrequencyPolicy() {}

    /**
     * 자격을 막는 빈도 제한 사유. 없으면 빈 값(자격)이다.
     *
     * @param now 판정의 DB 시각
     * @param recentEligible 그 사용자의 최근 자격 시각들(순서 무관). 같은 날짜의 상한을 판정하려면 적어도 최근
     *     {@link #DAILY_CAP}건이 있어야 한다
     */
    static Optional<SkipReason> judge(Instant now, Collection<Instant> recentEligible) {
        LocalDate today = LocalDate.ofInstant(now, ZONE);
        long sameDay = recentEligible.stream().filter(at -> LocalDate.ofInstant(at, ZONE).equals(today)).count();
        if (sameDay >= DAILY_CAP) {
            return Optional.of(SkipReason.DAILY_CAP);
        }
        Optional<Instant> last = recentEligible.stream().max(Instant::compareTo);
        if (last.isPresent() && now.isBefore(last.get().plus(INTERVAL))) {
            return Optional.of(SkipReason.INTERVAL);
        }
        return Optional.empty();
    }
}
