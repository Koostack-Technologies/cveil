package com.koostack.operio.core.exception;

/**
 * Fatal configuration errors — bad credentials, unknown plugin name, malformed
 * connection strings. Not retryable.
 *
 * <p>Thrown by the core framework ({@code Operio}, {@code PluginRegistry}) for
 * wiring errors, and by plugin implementations for fatal config/auth errors.
 *
 * @see OperioAuthException
 */
public class OperioConfigurationException extends OperioException {

    public OperioConfigurationException(String message) {
        super(message);
    }

    public OperioConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
