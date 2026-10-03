package com.bokrilog.source.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The Bybit call budget of this process (ERD-1.0.0 §2.5, Story 2.12).
 *
 * <p>Five things live here, and nothing else: a token bucket per credential for the signed Account calls (5 per
 * second by default — a credential is one main UID, and the admission's calls go out before the UID is known), one
 * service-wide bucket for the public Market calls (10 per second), at most two collections running at once, the
 * wait a page takes when the provider's own limit headers say the remaining budget is at or below 20 % (until the
 * reset time, at most five seconds), and the ten-minute circuit an IP ban (HTTP 403) opens over every Bybit call.
 * The retry delays of a collection page (2, 10 and 30 seconds, plus a jitter of up to 20 %) are read from here too,
 * but the retry itself is the adapter's: it retries a page, never an admission call.
 *
 * <p>Every wait checks {@code alive} at least every 100 ms and gives up as soon as it turns false: a collection
 * waits and retries only while its lease still holds. The defaults are the ERD's; only tests shorten them.
 *
 * <p>A wait the provider's reset time decides is never longer than the longest backoff (30 seconds by default): a
 * header or {@code 10006} with a far-future or absurd time neither parks a collection nor overflows a duration. The
 * per-credential state (a bucket, the last limit headers) is dropped after ten idle minutes, so a process that sees
 * many credentials over its life does not keep them all.
 *
 * <p>The time source is injectable through a package-private constructor so the unit test can drive the buckets,
 * the circuit and the waits without sleeping. The adapter keeps its own {@code Clock} for what it stores.
 */
final class BybitCallBudget {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BybitCallBudget.class);

    /** How often a wait looks at {@code alive} again. */
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    /** How long a credential's bucket and limit headers are kept without use. */
    static final long IDLE_NANOS = TimeUnit.MINUTES.toNanos(10);

    /** How often the idle per-credential state is swept (at most). */
    private static final long SWEEP_NANOS = TimeUnit.MINUTES.toNanos(1);

    /** The settings, already folded to their defaults. */
    record Settings(double authPerSecond, double publicPerSecond, int maxCollections, Duration circuitOpen,
            double lowRemainingRatio, Duration lowRemainingWaitCap, List<Duration> retryDelays, double retryJitter) {

        /** ERD-1.0.0 §2.5 as written. */
        static Settings defaults() {
            return new Settings(5, 10, 2, Duration.ofMinutes(10), 0.2, Duration.ofSeconds(5),
                    List.of(Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(30)), 0.2);
        }
    }

    /** The time source. The system one reads {@link System#nanoTime} and sleeps the thread. */
    interface Ticker {
        long nanos();

        long epochMillis();

        void sleep(long nanos) throws InterruptedException;
    }

    private static final Ticker SYSTEM = new Ticker() {
        @Override
        public long nanos() {
            return System.nanoTime();
        }

        @Override
        public long epochMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public void sleep(long nanos) throws InterruptedException {
            TimeUnit.NANOSECONDS.sleep(nanos);
        }
    };

    private final Settings settings;
    private final Ticker ticker;
    private final DoubleSupplier random;
    private final Map<String, Bucket> authBuckets = new ConcurrentHashMap<>();
    private final Bucket publicBucket;
    private final Semaphore collections;
    private final Map<String, Remaining> remaining = new ConcurrentHashMap<>();
    /** {@link Ticker#nanos} until which the circuit stays open; {@code Long.MIN_VALUE} when closed. */
    private volatile long circuitOpenUntil = Long.MIN_VALUE;
    private volatile boolean circuitEverOpened;
    private volatile long lastSweep;

    BybitCallBudget(Settings settings) {
        this(settings, SYSTEM, () -> ThreadLocalRandom.current().nextDouble());
    }

    BybitCallBudget(Settings settings, Ticker ticker, DoubleSupplier random) {
        this.settings = settings;
        this.ticker = ticker;
        this.random = random;
        this.publicBucket = new Bucket(settings.publicPerSecond(), ticker.nanos());
        this.collections = new Semaphore(Math.max(1, settings.maxCollections()), true);
        this.lastSweep = ticker.nanos();
    }

    Settings settings() {
        return this.settings;
    }

    // ---------------------------------------------------------------- the IP circuit

    /** Whether an IP ban holds every Bybit call back now. */
    boolean circuitOpen() {
        return circuitEverOpened && this.ticker.nanos() - this.circuitOpenUntil < 0;
    }

    /** Opens the circuit for its whole duration from now (a later ban extends it), with one warning line. */
    void openCircuit() {
        this.circuitOpenUntil = this.ticker.nanos() + this.settings.circuitOpen().toNanos();
        this.circuitEverOpened = true;
        // The duration only: no credential, request or provider text.
        LOG.warn("bybit ip circuit opened: seconds={}", this.settings.circuitOpen().toSeconds());
    }

    // ---------------------------------------------------------------- token buckets

    /** One signed-call token of this credential, waiting while {@code alive}. False when it gave up. */
    boolean acquireSigned(String apiKey, BooleanSupplier alive) {
        sweepIdle();
        Bucket bucket = this.authBuckets.computeIfAbsent(fingerprint(apiKey),
                ignored -> new Bucket(this.settings.authPerSecond(), this.ticker.nanos()));
        return acquire(bucket, alive);
    }

    /**
     * Drops the buckets and limit headers of credentials idle for {@link #IDLE_NANOS}, at most once a minute. An idle
     * bucket is full again anyway, so dropping it changes no answer.
     */
    private void sweepIdle() {
        long now = this.ticker.nanos();
        if (now - this.lastSweep < SWEEP_NANOS) return;
        this.lastSweep = now;
        this.authBuckets.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                return now - entry.getValue().updated >= IDLE_NANOS;
            }
        });
        this.remaining.entrySet().removeIf(entry -> now - entry.getValue().observedAt() >= IDLE_NANOS);
    }

    /** How many credentials hold per-credential state now (for the test of the sweep). */
    int trackedCredentials() {
        java.util.Set<String> keys = new java.util.HashSet<>(this.authBuckets.keySet());
        keys.addAll(this.remaining.keySet());
        return keys.size();
    }

    /** One public-call token, waiting while {@code alive}. False when it gave up. */
    boolean acquirePublic(BooleanSupplier alive) {
        return acquire(this.publicBucket, alive);
    }

    private boolean acquire(Bucket bucket, BooleanSupplier alive) {
        while (true) {
            long waitNanos;
            synchronized (bucket) {
                waitNanos = bucket.take(this.ticker.nanos());
            }
            if (waitNanos <= 0) return true;
            if (!pause(waitNanos, alive)) return false;
        }
    }

    // ---------------------------------------------------------------- concurrent collections

    /** A held collection slot. Closing it lets the next collection start. */
    interface Slot extends AutoCloseable {
        @Override
        void close();
    }

    /** A collection slot, waiting while {@code alive}; {@code null} when it gave up. */
    @Nullable Slot acquireCollection(BooleanSupplier alive) {
        while (true) {
            if (!alive.getAsBoolean()) return null;
            try {
                if (this.collections.tryAcquire(POLL_NANOS, TimeUnit.NANOSECONDS)) return this.collections::release;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    // ---------------------------------------------------------------- the provider's limit headers

    /**
     * Records the limit headers of a signed answer. Missing or unreadable headers change nothing.
     *
     * @param limit {@code X-Bapi-Limit}
     * @param status {@code X-Bapi-Limit-Status}: what is left
     * @param resetAtMillis {@code X-Bapi-Limit-Reset-Timestamp}, epoch milliseconds
     */
    void observe(String apiKey, @Nullable Long limit, @Nullable Long status, @Nullable Long resetAtMillis) {
        if (limit == null || status == null || limit <= 0) return;
        sweepIdle();
        this.remaining.put(fingerprint(apiKey), new Remaining(limit, status, resetAtMillis, this.ticker.nanos()));
    }

    /**
     * Waits before the next page while the last answer said the remaining budget is at or below the threshold:
     * until its reset time, at most the cap. False when it gave up.
     */
    boolean awaitPageSlot(String apiKey, BooleanSupplier alive) {
        Remaining last = this.remaining.get(fingerprint(apiKey));
        if (last == null || last.status() > last.limit() * this.settings.lowRemainingRatio()) return true;
        this.remaining.remove(fingerprint(apiKey), last);
        long waitMillis = untilReset(last.resetAtMillis(), this.settings.lowRemainingWaitCap().toMillis());
        return waitMillis <= 0 || pause(TimeUnit.MILLISECONDS.toNanos(waitMillis), alive);
    }

    /**
     * The milliseconds from now until a provider's reset time, never negative and never more than {@code capMillis}:
     * a time in the past waits nothing, and a far-future or absurd one (an 18-digit header) waits the cap, with no
     * arithmetic overflow on the way.
     */
    private long untilReset(@Nullable Long resetAtMillis, long capMillis) {
        if (resetAtMillis == null) return 0;
        long now = this.ticker.epochMillis();
        if (resetAtMillis <= now) return 0;
        // Both positive here (now is an epoch time), so the difference cannot overflow.
        return Math.min(resetAtMillis - now, capMillis);
    }

    /**
     * The delay before retry {@code attempt} (0-based) of a page: its backoff plus a jitter of up to the configured
     * share, and — after a {@code 10006} or 429 with a reset time — never before that reset (plus the same jitter).
     * The reset time counts for at most the longest backoff (interpretation C4): a far-future reset waits that long,
     * not until then.
     */
    Duration retryDelay(int attempt, @Nullable Long resetAtMillis) {
        List<Duration> delays = this.settings.retryDelays();
        long base = delays.get(Math.min(attempt, delays.size() - 1)).toMillis();
        long longest = delays.stream().mapToLong(Duration::toMillis).max().orElse(base);
        base = Math.max(base, untilReset(resetAtMillis, longest));
        long jitter = Math.round(base * this.settings.retryJitter() * clamp(this.random.getAsDouble()));
        return Duration.ofMillis(Math.max(0, base + jitter));
    }

    /** How many retries a page gets. */
    int maxRetries() {
        return this.settings.retryDelays().size();
    }

    /** Waits the given time while {@code alive}. False when it gave up. */
    boolean pause(Duration duration, BooleanSupplier alive) {
        return pause(duration.toNanos(), alive);
    }

    private boolean pause(long nanos, BooleanSupplier alive) {
        long deadline = this.ticker.nanos() + nanos;
        while (true) {
            if (!alive.getAsBoolean()) return false;
            long left = deadline - this.ticker.nanos();
            if (left <= 0) return true;
            try {
                this.ticker.sleep(Math.min(left, POLL_NANOS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static double clamp(double value) {
        return value < 0 ? 0 : value > 1 ? 1 : value;
    }

    /** The map key of a credential: its digest, so no raw key is held here. */
    private static String fingerprint(String apiKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Remaining(long limit, long status, @Nullable Long resetAtMillis, long observedAt) {}

    /** A token bucket holding at most one second's worth (at least one token), refilled continuously. */
    private static final class Bucket {
        private final double perNano;
        private final double capacity;
        private double tokens;
        /** The last refill, guarded by this bucket's monitor. */
        long updated;

        Bucket(double perSecond, long now) {
            double rate = perSecond > 0 ? perSecond : 1;
            this.perNano = rate / TimeUnit.SECONDS.toNanos(1);
            this.capacity = Math.max(1, rate);
            this.tokens = this.capacity;
            this.updated = now;
        }

        /** Takes a token and returns 0, or returns how long until one is there. */
        long take(long now) {
            this.tokens = Math.min(this.capacity, this.tokens + (now - this.updated) * this.perNano);
            this.updated = now;
            if (this.tokens >= 1) {
                this.tokens -= 1;
                return 0;
            }
            return Math.max(1, (long) Math.ceil((1 - this.tokens) / this.perNano));
        }
    }
}
