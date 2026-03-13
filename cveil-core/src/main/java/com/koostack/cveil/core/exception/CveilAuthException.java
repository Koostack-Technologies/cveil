package com.koostack.cveil.core.exception;

/**
 * Authentication or authorization failure from the managed consent service.
 *
 * <p>Maps to HTTP 401 (Unauthorized) and 403 (Forbidden) responses. This is
 * <strong>not retryable</strong> without a configuration change (e.g. rotating
 * the API key, adjusting permissions).
 *
 * <p>Extends {@link CveilConfigurationException} because an auth failure is
 * fundamentally a configuration problem — the credentials or permissions are
 * wrong and no amount of retrying will fix it.
 */
public class CveilAuthException extends CveilConfigurationException {

    public CveilAuthException(String message) {
        super(message);
    }

    public CveilAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
