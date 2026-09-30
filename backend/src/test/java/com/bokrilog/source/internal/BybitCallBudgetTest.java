package com.bokrilog.source.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The Bybit call budget on a fake clock (ERD-1.0.0 §2.5, Story 2.12): the per-credential and public token buckets,
 * the two concurrent collections, the low-remaining header wait and its cap, the retry delays with their jitter and
 * the reset time of a {@code 10006}, the ten-minute circuit, and every wait giving up as soon as its lease does.
 */
class BybitCallBudgetTest {

    /** A clock that only moves when the code under test sleeps. */
    private static final class FakeTicker implements BybitCallBudget.Ticker {
        final AtomicLong nanos = new AtomicLong(1_000_000_000L);
        final AtomicLong slept = new AtomicLong();

        @Override
        public long nanos() {
            return this.nanos.get();
        }

        @Override
        public long epochMillis() {
            return TimeUnit.NANOSECONDS.toMillis(this.nanos.get());
        }

        @Override
        public void sleep(long nanos) {
            this.nanos.addAndGet(nanos);
            this.slept.addAndGet(nanos);
        }

        void advance(Duration duration) {
            this.nanos.addAndGet(duration.toNanos());
        }
    }

    private final FakeTicker clock = new FakeTicker();

    private BybitCallBudget budget(double jitterDraw) {
        return new BybitCallBudget(BybitCallBudget.Settings.defaults(), this.clock, () -> jitterDraw);
    }

    @Test
    void aCredentialGetsFiveSignedCallsASecondAndAnotherCredentialItsOwnFive() {
        var budget = budget(0);
        for (int call = 0; call < 5; call++) assertThat(budget.acquireSigned("key-a", () -> true)).isTrue();
        assertThat(this.clock.slept.get()).as("the burst of one second is free").isZero();
        assertThat(budget.acquireSigned("key-a", () -> true)).isTrue();
        assertThat(this.clock.slept.get()).as("the sixth waits a fifth of a second")
                .isBetween(TimeUnit.MILLISECONDS.toNanos(199), TimeUnit.MILLISECONDS.toNanos(201));
        long before = this.clock.slept.get();
        for (int call = 0; call < 5; call++) assertThat(budget.acquireSigned("key-b", () -> true)).isTrue();
        assertThat(this.clock.slept.get()).as("another UID does not wait for the first").isEqualTo(before);
    }

    @Test
    void thePublicCallsShareTenASecondAcrossTheService() {
        var budget = budget(0);
        for (int call = 0; call < 10; call++) assertThat(budget.acquirePublic(() -> true)).isTrue();
        assertThat(this.clock.slept.get()).isZero();
        assertThat(budget.acquirePublic(() -> true)).isTrue();
        assertThat(this.clock.slept.get()).isBetween(TimeUnit.MILLISECONDS.toNanos(99), TimeUnit.MILLISECONDS.toNanos(101));
    }

    @Test
    void aWaitGivesUpAsSoonAsItsLeaseDoes() {
        var budget = budget(0);
        for (int call = 0; call < 5; call++) budget.acquireSigned("key-a", () -> true);
        assertThat(budget.acquireSigned("key-a", () -> false)).isFalse();
        assertThat(budget.pause(Duration.ofSeconds(30), () -> false)).isFalse();
        AtomicBoolean alive = new AtomicBoolean(true);
        assertThat(budget.pause(Duration.ofSeconds(1), () -> {
            if (this.clock.slept.get() > TimeUnit.MILLISECONDS.toNanos(250)) alive.set(false);
            return alive.get();
        })).as("checked at least every 100 ms").isFalse();
        assertThat(this.clock.slept.get()).isLessThan(TimeUnit.MILLISECONDS.toNanos(400));
    }

    @Test
    void atMostTwoCollectionsRunAtOnce() {
        var budget = budget(0);
        var first = budget.acquireCollection(() -> true);
        var second = budget.acquireCollection(() -> true);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        AtomicLong asked = new AtomicLong();
        assertThat(budget.acquireCollection(() -> asked.incrementAndGet() < 3)).as("the third waits, then gives up")
                .isNull();
        first.close();
        var third = budget.acquireCollection(() -> true);
        assertThat(third).isNotNull();
        second.close();
        third.close();
    }

    @Test
    void aLowRemainingBudgetHoldsTheNextPageUntilItsResetButNoLongerThanTheCap() {
        var budget = budget(0);
        long now = this.clock.epochMillis();
        budget.observe("key-a", 10L, 2L, now + 1_500);
        assertThat(budget.awaitPageSlot("key-a", () -> true)).isTrue();
        assertThat(this.clock.slept.get()).as("20 % is low: until the reset").isEqualTo(TimeUnit.MILLISECONDS.toNanos(1_500));
        // Held only once: the next page goes at once.
        long slept = this.clock.slept.get();
        assertThat(budget.awaitPageSlot("key-a", () -> true)).isTrue();
        assertThat(this.clock.slept.get()).isEqualTo(slept);
        budget.observe("key-a", 10L, 3L, this.clock.epochMillis() + 60_000);
        assertThat(budget.awaitPageSlot("key-a", () -> true)).isTrue();
        assertThat(this.clock.slept.get()).as("30 % is not low").isEqualTo(slept);
        budget.observe("key-a", 10L, 0L, this.clock.epochMillis() + 60_000);
        assertThat(budget.awaitPageSlot("key-a", () -> true)).isTrue();
        assertThat(this.clock.slept.get() - slept).as("capped at five seconds").isEqualTo(TimeUnit.SECONDS.toNanos(5));
        // Missing or unreadable headers change nothing.
        budget.observe("key-b", null, 0L, null);
        budget.observe("key-b", 0L, 0L, null);
        long before = this.clock.slept.get();
        assertThat(budget.awaitPageSlot("key-b", () -> true)).isTrue();
        assertThat(this.clock.slept.get()).isEqualTo(before);
    }

    @Test
    void theRetryDelaysAreTwoTenAndThirtySecondsWithUpToTwentyPercentJitter() {
        assertThat(budget(0).maxRetries()).isEqualTo(3);
        assertThat(List.of(budget(0).retryDelay(0, null), budget(0).retryDelay(1, null), budget(0).retryDelay(2, null)))
                .containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(30));
        assertThat(budget(1).retryDelay(0, null)).isEqualTo(Duration.ofMillis(2_400));
        assertThat(budget(1).retryDelay(2, null)).isEqualTo(Duration.ofSeconds(36));
        assertThat(budget(0.5).retryDelay(1, null)).isEqualTo(Duration.ofSeconds(11));
        // A 10006's reset time comes first when it is later than the backoff.
        long now = this.clock.epochMillis();
        assertThat(budget(0).retryDelay(0, now + 7_000)).isEqualTo(Duration.ofSeconds(7));
        assertThat(budget(0).retryDelay(0, now + 500)).isEqualTo(Duration.ofSeconds(2));
    }

    /**
     * Interpretation C4: a provider reset time counts for at most the longest backoff (30 seconds), whether it is a
     * minute ahead, a day ahead or an 18-digit value no clock reaches — and nothing overflows on the way.
     */
    @Test
    void aFarFutureOrAbsurdResetTimeWaitsNoLongerThanTheLongestBackoff() {
        long now = this.clock.epochMillis();
        assertThat(budget(0).retryDelay(0, now + 60_000)).isEqualTo(Duration.ofSeconds(30));
        assertThat(budget(0).retryDelay(0, now + Duration.ofDays(1).toMillis())).isEqualTo(Duration.ofSeconds(30));
        assertThat(budget(0).retryDelay(0, 999_999_999_999_999_999L)).isEqualTo(Duration.ofSeconds(30));
        assertThat(budget(1).retryDelay(0, Long.MAX_VALUE)).as("plus the jitter").isEqualTo(Duration.ofSeconds(36));
        assertThat(budget(0).retryDelay(1, Long.MIN_VALUE)).as("a reset in the past is the backoff")
                .isEqualTo(Duration.ofSeconds(10));
        // The low-remaining wait keeps its own cap for the same absurd values, and never waits on a past reset.
        var budget = budget(0);
        budget.observe("key-a", 10L, 1L, 999_999_999_999_999_999L);
        assertThat(budget.awaitPageSlot("key-a", () -> true)).isTrue();
        assertThat(this.clock.slept.get()).isEqualTo(TimeUnit.SECONDS.toNanos(5));
        budget.observe("key-a", 10L, 1L, Long.MIN_VALUE);
        long before = this.clock.slept.get();
        assertThat(budget.awaitPageSlot("key-a", () -> true)).isTrue();
        assertThat(this.clock.slept.get()).isEqualTo(before);
    }

    /** A credential's bucket and limit headers are dropped after ten idle minutes; a busy one is kept. */
    @Test
    void theStateOfAnIdleCredentialIsDropped() {
        var budget = budget(0);
        budget.acquireSigned("key-a", () -> true);
        budget.observe("key-b", 10L, 9L, null);
        assertThat(budget.trackedCredentials()).isEqualTo(2);
        this.clock.advance(Duration.ofMinutes(5));
        budget.acquireSigned("key-c", () -> true);
        assertThat(budget.trackedCredentials()).as("five minutes is not idle").isEqualTo(3);
        this.clock.advance(Duration.ofMinutes(6));
        budget.acquireSigned("key-c", () -> true);
        assertThat(budget.trackedCredentials()).as("a and b idle eleven minutes, c used").isEqualTo(1);
    }

    @Test
    void anIpBanOpensATenMinuteCircuit() {
        var budget = budget(0);
        assertThat(budget.circuitOpen()).isFalse();
        budget.openCircuit();
        assertThat(budget.circuitOpen()).isTrue();
        this.clock.advance(Duration.ofMinutes(10).minusMillis(1));
        assertThat(budget.circuitOpen()).isTrue();
        this.clock.advance(Duration.ofMillis(1));
        assertThat(budget.circuitOpen()).isFalse();
    }

    @Test
    void unreadableSettingsFoldToTheErdValues() {
        var budget = new SourceProperties.Budget("", "-1", "abc", "2s,x", "7", "0s", "2", "later").settings();
        assertThat(budget).isEqualTo(BybitCallBudget.Settings.defaults());
        var tests = new SourceProperties.Budget("1000", "1000", "2", "20ms,40ms,60ms", "0", "3s", "0.2", "1s").settings();
        assertThat(tests.retryDelays()).containsExactly(Duration.ofMillis(20), Duration.ofMillis(40),
                Duration.ofMillis(60));
        assertThat(tests.circuitOpen()).isEqualTo(Duration.ofSeconds(3));
        assertThat(tests.retryJitter()).isZero();
        assertThat(new SourceProperties.Budget(null, null, null, "1s,2s,3s,4s", null, null, null, null).settings()
                .retryDelays()).as("never more than three retries").hasSize(3).first().isEqualTo(Duration.ofSeconds(2));
        // A rate slower than 0.1/s is not a budget but a stop, and a count is a whole number.
        var slow = new SourceProperties.Budget("0.05", "0.09", "2.5", null, null, null, null, null).settings();
        assertThat(slow.authPerSecond()).isEqualTo(5);
        assertThat(slow.publicPerSecond()).isEqualTo(10);
        assertThat(slow.maxCollections()).isEqualTo(2);
        var floor = new SourceProperties.Budget("0.1", "0.5", "3", null, null, null, null, null).settings();
        assertThat(floor.authPerSecond()).isEqualTo(0.1);
        assertThat(floor.publicPerSecond()).isEqualTo(0.5);
        assertThat(floor.maxCollections()).isEqualTo(3);
    }
}
