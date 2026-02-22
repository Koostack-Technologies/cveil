package com.koostack.operio.core.exception;

/**
 * Abstract base for all Operio exceptions.
 *
 * <p>Extends {@link RuntimeException} (unchecked) to match the existing
 * contract where service interfaces do not declare checked exceptions.
 *
 * <h2>Hierarchy</h2>
 * <ul>
 *   <li>{@link OperioConfigurationException} — fatal configuration / wiring
 *       errors (not retryable)</li>
 *   <li>{@link OperioAuthException} — authentication / authorization failures
 *       (subclass of configuration, not retryable without config change)</li>
 *   <li>{@link OperioServiceException} — transient infrastructure failures
 *       (retryable)</li>
 *   <li>{@link OperioRateLimitException} — rate limiting (subclass of service,
 *       retryable after backoff)</li>
 * </ul>
 */
public abstract class OperioException extends RuntimeException {

    protected OperioException(String message) {
        super(message);
    }

    protected OperioException(String message, Throwable cause) {
        super(message, cause);
    }
}
