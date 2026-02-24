package com.koostack.operio.rest.ratelimit;

import java.time.Instant;

/**
 * Immutable result of a rate-limit check performed by {@link RateLimitService}.
 *
 * <p>Encapsulates all the information needed by the {@link RateLimitFilter}
 * to decide whether to allow or reject a request and which HTTP response
 * headers to emit.
 *
 * <h2>Header Mapping</h2>
 * <table>
 *   <tr><th>Field</th><th>HTTP Header</th></tr>
 *   <tr><td>{@link #limit()}</td>
 *       <td>{@code X-RateLimit-Limit}</td></tr>
 *   <tr><td>{@link #remaining()}</td>
 *       <td>{@code X-RateLimit-Remaining}</td></tr>
 *   <tr><td>{@link #resetAt()}</td>
 *       <td>{@code X-RateLimit-Reset} (Unix epoch seconds)</td></tr>
 *   <tr><td>{@link #warning()}</td>
 *       <td>{@code X-RateLimit-Warning: approaching limit}</td></tr>
 *   <tr><td>{@link #burstExceeded()} or {@link #monthlyExceeded()}</td>
 *       <td>HTTP 429 + {@code Retry-After} (seconds until reset)</td></tr>
 * </table>
 *
 * <h2>Sentinel — {@link #UNLIMITED}</h2>
 * <p>A special sentinel instance is provided for situations where rate
 * limiting is disabled, unavailable, or not applicable (e.g. embedded
 * mode, super-admin bootstrap requests, Redis connection failure). The
 * filter checks {@link #isUnlimited()} and skips header emission entirely.
 *
 * @param limit           the maximum number of requests allowed in the
 *                        current window (burst per-minute or monthly cap)
 * @param remaining       how many requests remain before the limit is
 *                        reached (never negative — clamped to 0)
 * @param resetAt         the {@link Instant} at which the rate-limit
 *                        window resets (start of next minute for burst,
 *                        start of next month for monthly)
 * @param burstExceeded   {@code true} if the per-minute burst limit has
 *                        been exceeded — the filter should abort with 429
 * @param warning         {@code true} if monthly usage has reached 90%
 *                        of the limit — the filter should set the
 *                        {@code X-RateLimit-Warning} header
 * @param monthlyExceeded {@code true} if the monthly evaluation limit
 *                        has been exceeded — the filter should abort
 *                        with 429 and include {@code Retry-After}
 * @param subjectLimitExceeded {@code true} if the distinct subject limit
 *                        for the current billing period has been exceeded
 *                        — the filter should abort with 429
 *
 * @see RateLimitService
 * @see RateLimitFilter
 */
public record RateLimitResult(
    long limit,
    long remaining,
    Instant resetAt,
    boolean burstExceeded,
    boolean warning,
    boolean monthlyExceeded,
    boolean subjectLimitExceeded
) {
    /**
     * Sentinel instance representing "no rate limiting applies".
     *
     * <p>Returned by {@link RateLimitService} when:
     * <ul>
     *   <li>Rate limiting is not available (embedded mode, no Redis)</li>
     *   <li>The tenant is not found in the platform database</li>
     *   <li>A Redis error occurred (fail-open strategy)</li>
     * </ul>
     *
     * <p>The filter should check {@link #isUnlimited()} and skip all
     * rate-limit header emission when this sentinel is returned.
     */
    public static final RateLimitResult UNLIMITED = new RateLimitResult(
        /* limit */ -1,
        /* remaining */ -1,
        /* resetAt */ Instant.EPOCH,
        /* burstExceeded */ false,
        /* warning */ false,
        /* monthlyExceeded */ false,
        /* subjectLimitExceeded */ false
    );

    /**
     * Whether this result represents the "unlimited / rate limiting
     * disabled" sentinel.
     *
     * <p>When {@code true}, the filter should not emit any rate-limit
     * headers and should not reject the request on rate-limit grounds.
     *
     * @return {@code true} if this is the {@link #UNLIMITED} sentinel
     */
    public boolean isUnlimited() {
        return limit < 0;
    }

    /**
     * Whether any rate limit has been exceeded (burst, monthly, or subject).
     *
     * <p>Convenience method that combines all exceeded flags. When
     * {@code true}, the filter should abort the request with HTTP 429.
     *
     * @return {@code true} if any limit is exceeded
     */
    public boolean isExceeded() {
        return burstExceeded || monthlyExceeded || subjectLimitExceeded;
    }

    /**
     * Computes the number of seconds until the rate-limit window resets.
     *
     * <p>Used as the value of the {@code Retry-After} header in 429
     * responses. Returns at least 1 second to avoid a zero or negative
     * value when the reset time is imminent.
     *
     * @return seconds until reset, minimum 1
     */
    public long retryAfterSeconds() {
        long seconds = java.time.Duration.between(
            Instant.now(),
            resetAt
        ).toSeconds();
        return Math.max(1, seconds);
    }

    /**
     * Returns the reset time as Unix epoch seconds, suitable for the
     * {@code X-RateLimit-Reset} response header value.
     *
     * @return epoch seconds of the reset instant
     */
    public long resetAtEpochSeconds() {
        return resetAt.getEpochSecond();
    }
}
