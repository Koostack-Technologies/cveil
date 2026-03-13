package com.koostack.cveil.core.exception;

/**
 * Abstract base for all Cveil exceptions.
 *
 * <p>Extends {@link RuntimeException} (unchecked) to match the existing
 * contract where service interfaces do not declare checked exceptions.
 *
 * <h2>Hierarchy</h2>
 * <ul>
 *   <li>{@link CveilConfigurationException} — fatal configuration / wiring
 *       errors (not retryable)</li>
 *   <li>{@link CveilAuthException} — authentication / authorization failures
 *       (subclass of configuration, not retryable without config change)</li>
 *   <li>{@link CveilServiceException} — transient infrastructure failures
 *       (retryable)</li>
 *   <li>{@link CveilRateLimitException} — rate limiting (subclass of service,
 *       retryable after backoff)</li>
 * </ul>
 */
public abstract class CveilException extends RuntimeException {

    protected CveilException(String message) {
        super(message);
    }

    protected CveilException(String message, Throwable cause) {
        super(message, cause);
    }
}
