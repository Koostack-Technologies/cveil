package com.koostack.operio.core.query;

import java.util.Collection;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lightweight retry helper for CQRS query services.
 *
 * <p>After a command publishes an event, the projection may not have materialised by the time a
 * subsequent query runs. This utility retries the query with exponential backoff when the result
 * looks "empty" (null, empty collection), allowing the projection a brief window to catch up.
 *
 * <p>Only retries on null/empty results — exceptions propagate immediately.
 */
public final class RetryableQuery {

    private static final Logger LOG = Logger.getLogger(RetryableQuery.class.getName());

    private RetryableQuery() {}

    /**
     * Configurable retry parameters.
     *
     * @param initialDelayMs delay before the first retry (ms)
     * @param maxDelayMs upper bound on the backoff delay (ms)
     * @param maxAttempts total number of attempts (including the first)
     * @param backoffMultiplier multiplier applied to the delay after each retry
     */
    public record RetryConfig(
            long initialDelayMs, long maxDelayMs, int maxAttempts, double backoffMultiplier) {
        public RetryConfig {
            if (initialDelayMs < 0)
                throw new IllegalArgumentException("initialDelayMs must be >= 0");
            if (maxDelayMs < initialDelayMs)
                throw new IllegalArgumentException("maxDelayMs must be >= initialDelayMs");
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
            if (backoffMultiplier < 1.0)
                throw new IllegalArgumentException("backoffMultiplier must be >= 1.0");
        }
    }

    /** Sensible defaults for short eventual-consistency windows. */
    public static final RetryConfig DEFAULT = new RetryConfig(15, 150, 4, 2.0);

    /**
     * Executes a query, retrying with backoff while the result satisfies the {@code isEmpty}
     * predicate.
     *
     * @param query the query to execute
     * @param isEmpty returns {@code true} when the result should trigger a retry
     * @param config retry parameters
     * @param label descriptive label for DEBUG log messages
     * @param <T> result type
     * @return the query result (may still be "empty" if all retries are exhausted)
     */
    public static <T> T withRetry(
            Supplier<T> query, Predicate<T> isEmpty, RetryConfig config, String label) {
        T result = query.get();
        if (!isEmpty.test(result)) {
            return result;
        }

        long delay = config.initialDelayMs();
        for (int attempt = 2; attempt <= config.maxAttempts(); attempt++) {
            LOG.log(
                    Level.FINE,
                    "RetryableQuery [{0}]: result empty, retry {1}/{2} after {3}ms",
                    new Object[] {label, attempt, config.maxAttempts(), delay});
            if (!sleep(delay)) {
                // Thread was interrupted — return immediately instead of
                // burning through remaining retries.
                return result;
            }
            result = query.get();
            if (!isEmpty.test(result)) {
                return result;
            }
            delay = Math.min((long) (delay * config.backoffMultiplier()), config.maxDelayMs());
        }

        LOG.log(
                Level.FINE,
                "RetryableQuery [{0}]: all {1} attempts exhausted, returning last result",
                new Object[] {label, config.maxAttempts()});
        return result;
    }

    /** Convenience overload using {@link #DEFAULT} config. */
    public static <T> T withRetry(Supplier<T> query, Predicate<T> isEmpty, String label) {
        return withRetry(query, isEmpty, DEFAULT, label);
    }

    /**
     * Retries a query that returns a {@link Collection}, treating {@code null} or empty as "not yet
     * projected".
     */
    public static <T, C extends Collection<T>> C withRetryCollection(
            Supplier<C> query, RetryConfig config, String label) {
        return withRetry(query, c -> c == null || c.isEmpty(), config, label);
    }

    /** Convenience overload using {@link #DEFAULT} config. */
    public static <T, C extends Collection<T>> C withRetryCollection(
            Supplier<C> query, String label) {
        return withRetryCollection(query, DEFAULT, label);
    }

    /**
     * @return {@code true} if the sleep completed, {@code false} if interrupted
     */
    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
