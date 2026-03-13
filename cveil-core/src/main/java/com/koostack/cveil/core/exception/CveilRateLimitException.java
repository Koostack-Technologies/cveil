package com.koostack.cveil.core.exception;

import java.time.Duration;

/**
 * Rate limiting — 429. Retryable after backoff. Carries optional {@code retryAfter}.
 *
 * <p>Managed plugins MUST throw this when the remote API responds with
 * HTTP 429. The {@link #retryAfter()} hint, when present, reflects the
 * server's {@code Retry-After} header so callers can back off appropriately.
 */
public class CveilRateLimitException extends CveilServiceException {

    private final Duration retryAfter;

    public CveilRateLimitException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public CveilRateLimitException(String message, Throwable cause, Duration retryAfter) {
        super(message, cause);
        this.retryAfter = retryAfter;
    }

    /**
     * Suggested backoff, or {@code null} if the server didn't specify one.
     */
    public Duration retryAfter() {
        return retryAfter;
    }
}
