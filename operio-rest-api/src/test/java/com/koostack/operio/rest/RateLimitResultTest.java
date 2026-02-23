package com.koostack.operio.rest;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.rest.ratelimit.RateLimitResult;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RateLimitResult} — the immutable rate-limit check
 * result record used by {@code RateLimitFilter} to decide whether to allow
 * or reject requests and which HTTP response headers to emit.
 *
 * <p>Covers:
 * <ul>
 *   <li>UNLIMITED sentinel — isUnlimited, not exceeded, not warning</li>
 *   <li>Normal (under limit) result — remaining > 0, not exceeded</li>
 *   <li>Burst exceeded — isExceeded true, burstExceeded true</li>
 *   <li>Monthly exceeded — isExceeded true, monthlyExceeded true</li>
 *   <li>Warning state — warning true, not exceeded</li>
 *   <li>retryAfterSeconds — minimum 1, correct calculation</li>
 *   <li>resetAtEpochSeconds — correct epoch conversion</li>
 * </ul>
 */
@DisplayName("RateLimitResult")
class RateLimitResultTest {

    // -----------------------------------------------------------------------
    // UNLIMITED sentinel
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("UNLIMITED sentinel")
    class UnlimitedSentinel {

        @Test
        @DisplayName("isUnlimited() returns true for UNLIMITED sentinel")
        void isUnlimited() {
            assertTrue(RateLimitResult.UNLIMITED.isUnlimited());
        }

        @Test
        @DisplayName("limit is negative for UNLIMITED sentinel")
        void limitIsNegative() {
            assertTrue(RateLimitResult.UNLIMITED.limit() < 0);
        }

        @Test
        @DisplayName("remaining is negative for UNLIMITED sentinel")
        void remainingIsNegative() {
            assertTrue(RateLimitResult.UNLIMITED.remaining() < 0);
        }

        @Test
        @DisplayName("isExceeded() returns false for UNLIMITED sentinel")
        void isNotExceeded() {
            assertFalse(RateLimitResult.UNLIMITED.isExceeded());
        }

        @Test
        @DisplayName("burstExceeded() returns false for UNLIMITED sentinel")
        void burstNotExceeded() {
            assertFalse(RateLimitResult.UNLIMITED.burstExceeded());
        }

        @Test
        @DisplayName("monthlyExceeded() returns false for UNLIMITED sentinel")
        void monthlyNotExceeded() {
            assertFalse(RateLimitResult.UNLIMITED.monthlyExceeded());
        }

        @Test
        @DisplayName("warning() returns false for UNLIMITED sentinel")
        void noWarning() {
            assertFalse(RateLimitResult.UNLIMITED.warning());
        }

        @Test
        @DisplayName("resetAt is Instant.EPOCH for UNLIMITED sentinel")
        void resetAtIsEpoch() {
            assertEquals(Instant.EPOCH, RateLimitResult.UNLIMITED.resetAt());
        }
    }

    // -----------------------------------------------------------------------
    // Normal (under limit)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Normal result (under limit)")
    class NormalResult {

        private final Instant resetAt = Instant.now().plus(45, ChronoUnit.SECONDS);
        private final RateLimitResult result = new RateLimitResult(
            100L,   // limit
            75L,    // remaining
            resetAt,
            false,  // burstExceeded
            false,  // warning
            false   // monthlyExceeded
        );

        @Test
        @DisplayName("isUnlimited() returns false for normal result")
        void isNotUnlimited() {
            assertFalse(result.isUnlimited());
        }

        @Test
        @DisplayName("limit returns the configured limit")
        void limitValue() {
            assertEquals(100L, result.limit());
        }

        @Test
        @DisplayName("remaining returns the remaining count")
        void remainingValue() {
            assertEquals(75L, result.remaining());
        }

        @Test
        @DisplayName("isExceeded() returns false when under limit")
        void isNotExceeded() {
            assertFalse(result.isExceeded());
        }

        @Test
        @DisplayName("burstExceeded() returns false when under limit")
        void burstNotExceeded() {
            assertFalse(result.burstExceeded());
        }

        @Test
        @DisplayName("monthlyExceeded() returns false when under limit")
        void monthlyNotExceeded() {
            assertFalse(result.monthlyExceeded());
        }

        @Test
        @DisplayName("warning() returns false when not approaching limit")
        void noWarning() {
            assertFalse(result.warning());
        }

        @Test
        @DisplayName("resetAt returns the configured reset instant")
        void resetAtValue() {
            assertEquals(resetAt, result.resetAt());
        }
    }

    // -----------------------------------------------------------------------
    // Burst exceeded
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Burst exceeded")
    class BurstExceeded {

        private final Instant resetAt = Instant.now().plus(30, ChronoUnit.SECONDS);
        private final RateLimitResult result = new RateLimitResult(
            100L,   // limit
            0L,     // remaining
            resetAt,
            true,   // burstExceeded
            false,  // warning
            false   // monthlyExceeded
        );

        @Test
        @DisplayName("isExceeded() returns true when burst exceeded")
        void isExceeded() {
            assertTrue(result.isExceeded());
        }

        @Test
        @DisplayName("burstExceeded() returns true")
        void burstExceededFlag() {
            assertTrue(result.burstExceeded());
        }

        @Test
        @DisplayName("monthlyExceeded() returns false (only burst)")
        void monthlyNotExceeded() {
            assertFalse(result.monthlyExceeded());
        }

        @Test
        @DisplayName("remaining is zero when exceeded")
        void remainingIsZero() {
            assertEquals(0L, result.remaining());
        }

        @Test
        @DisplayName("isUnlimited() returns false")
        void notUnlimited() {
            assertFalse(result.isUnlimited());
        }
    }

    // -----------------------------------------------------------------------
    // Monthly exceeded
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Monthly exceeded")
    class MonthlyExceeded {

        private final Instant resetAt = Instant.now().plus(15, ChronoUnit.DAYS);
        private final RateLimitResult result = new RateLimitResult(
            10_000L, // limit
            0L,      // remaining
            resetAt,
            false,   // burstExceeded
            true,    // warning
            true     // monthlyExceeded
        );

        @Test
        @DisplayName("isExceeded() returns true when monthly exceeded")
        void isExceeded() {
            assertTrue(result.isExceeded());
        }

        @Test
        @DisplayName("monthlyExceeded() returns true")
        void monthlyExceededFlag() {
            assertTrue(result.monthlyExceeded());
        }

        @Test
        @DisplayName("burstExceeded() returns false (only monthly)")
        void burstNotExceeded() {
            assertFalse(result.burstExceeded());
        }

        @Test
        @DisplayName("warning() returns true when at limit")
        void warningIsTrue() {
            assertTrue(result.warning());
        }
    }

    // -----------------------------------------------------------------------
    // Both burst and monthly exceeded
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Both burst and monthly exceeded")
    class BothExceeded {

        private final RateLimitResult result = new RateLimitResult(
            100L,
            0L,
            Instant.now().plus(1, ChronoUnit.MINUTES),
            true,  // burstExceeded
            true,  // warning
            true   // monthlyExceeded
        );

        @Test
        @DisplayName("isExceeded() returns true when both exceeded")
        void isExceeded() {
            assertTrue(result.isExceeded());
        }

        @Test
        @DisplayName("burstExceeded() returns true")
        void burstExceededFlag() {
            assertTrue(result.burstExceeded());
        }

        @Test
        @DisplayName("monthlyExceeded() returns true")
        void monthlyExceededFlag() {
            assertTrue(result.monthlyExceeded());
        }
    }

    // -----------------------------------------------------------------------
    // Warning (approaching limit, not exceeded)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Warning state (approaching limit)")
    class WarningState {

        private final RateLimitResult result = new RateLimitResult(
            10_000L, // limit
            500L,    // remaining (5% left → 95% used → above 90% threshold)
            Instant.now().plus(5, ChronoUnit.DAYS),
            false,   // burstExceeded
            true,    // warning
            false    // monthlyExceeded
        );

        @Test
        @DisplayName("warning() returns true when approaching limit")
        void warningIsTrue() {
            assertTrue(result.warning());
        }

        @Test
        @DisplayName("isExceeded() returns false (still under limit)")
        void isNotExceeded() {
            assertFalse(result.isExceeded());
        }

        @Test
        @DisplayName("remaining reflects actual remaining count")
        void remainingValue() {
            assertEquals(500L, result.remaining());
        }
    }

    // -----------------------------------------------------------------------
    // retryAfterSeconds
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("retryAfterSeconds()")
    class RetryAfterSeconds {

        @Test
        @DisplayName("returns correct seconds until reset (future)")
        void futureReset() {
            Instant futureReset = Instant.now().plus(42, ChronoUnit.SECONDS);
            RateLimitResult result = new RateLimitResult(
                100L, 0L, futureReset, true, false, false
            );
            long retryAfter = result.retryAfterSeconds();
            // Allow 2-second tolerance for test execution time
            assertTrue(retryAfter >= 40 && retryAfter <= 43,
                "Expected ~42 but got " + retryAfter);
        }

        @Test
        @DisplayName("returns minimum 1 second when reset is imminent")
        void imminentReset() {
            // Reset time is NOW — should still return at least 1
            RateLimitResult result = new RateLimitResult(
                100L, 0L, Instant.now(), true, false, false
            );
            assertTrue(result.retryAfterSeconds() >= 1,
                "retryAfterSeconds should be at least 1");
        }

        @Test
        @DisplayName("returns minimum 1 second when reset is in the past")
        void pastReset() {
            Instant pastReset = Instant.now().minus(10, ChronoUnit.SECONDS);
            RateLimitResult result = new RateLimitResult(
                100L, 0L, pastReset, true, false, false
            );
            assertEquals(1L, result.retryAfterSeconds(),
                "retryAfterSeconds should clamp to 1 for past resets");
        }

        @Test
        @DisplayName("returns correct seconds for large future reset")
        void largeFutureReset() {
            Instant farFuture = Instant.now().plus(3600, ChronoUnit.SECONDS);
            RateLimitResult result = new RateLimitResult(
                100L, 0L, farFuture, false, false, true
            );
            long retryAfter = result.retryAfterSeconds();
            // Should be approximately 3600, allow 2s tolerance
            assertTrue(retryAfter >= 3598 && retryAfter <= 3601,
                "Expected ~3600 but got " + retryAfter);
        }
    }

    // -----------------------------------------------------------------------
    // resetAtEpochSeconds
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("resetAtEpochSeconds()")
    class ResetAtEpochSeconds {

        @Test
        @DisplayName("returns correct epoch seconds from resetAt instant")
        void correctEpochSeconds() {
            Instant resetAt = Instant.ofEpochSecond(1_700_000_000L);
            RateLimitResult result = new RateLimitResult(
                100L, 50L, resetAt, false, false, false
            );
            assertEquals(1_700_000_000L, result.resetAtEpochSeconds());
        }

        @Test
        @DisplayName("returns 0 for Instant.EPOCH (UNLIMITED sentinel)")
        void epochForUnlimited() {
            assertEquals(0L, RateLimitResult.UNLIMITED.resetAtEpochSeconds());
        }

        @Test
        @DisplayName("handles sub-second precision by truncating to seconds")
        void subSecondTruncation() {
            // Instant with nanos — resetAtEpochSeconds should only return seconds
            Instant resetAt = Instant.ofEpochSecond(1_700_000_000L, 999_999_999L);
            RateLimitResult result = new RateLimitResult(
                100L, 50L, resetAt, false, false, false
            );
            assertEquals(1_700_000_000L, result.resetAtEpochSeconds());
        }
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("limit of 0 is not considered unlimited")
        void zeroLimitIsNotUnlimited() {
            RateLimitResult result = new RateLimitResult(
                0L, 0L, Instant.now(), false, false, false
            );
            assertFalse(result.isUnlimited(),
                "limit=0 should not be treated as unlimited");
        }

        @Test
        @DisplayName("limit of 1 with remaining 0 is not exceeded without flags")
        void limitOneRemainingZero() {
            RateLimitResult result = new RateLimitResult(
                1L, 0L, Instant.now(), false, false, false
            );
            assertFalse(result.isExceeded(),
                "isExceeded should be based on flags, not remaining count");
        }

        @Test
        @DisplayName("record equality works correctly")
        void recordEquality() {
            Instant resetAt = Instant.ofEpochSecond(1_700_000_000L);
            RateLimitResult a = new RateLimitResult(100L, 50L, resetAt, false, false, false);
            RateLimitResult b = new RateLimitResult(100L, 50L, resetAt, false, false, false);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("record inequality for different values")
        void recordInequality() {
            Instant resetAt = Instant.ofEpochSecond(1_700_000_000L);
            RateLimitResult a = new RateLimitResult(100L, 50L, resetAt, false, false, false);
            RateLimitResult b = new RateLimitResult(100L, 49L, resetAt, false, false, false);
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("toString is not null")
        void toStringNotNull() {
            assertNotNull(RateLimitResult.UNLIMITED.toString());
        }
    }
}
