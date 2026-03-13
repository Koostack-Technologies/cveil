package com.koostack.cveil.core.exception;

/**
 * Fatal configuration errors — bad credentials, unknown plugin name, malformed
 * connection strings. Not retryable.
 *
 * <p>Thrown by the core framework ({@code Cveil}, {@code PluginRegistry}) for
 * wiring errors, and by plugin implementations for fatal config/auth errors.
 *
 * @see CveilAuthException
 */
public class CveilConfigurationException extends CveilException {

    public CveilConfigurationException(String message) {
        super(message);
    }

    public CveilConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
