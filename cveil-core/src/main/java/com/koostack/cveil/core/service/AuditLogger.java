package com.koostack.cveil.core.service;

import com.koostack.cveil.core.models.ConsentScope;
import com.koostack.cveil.core.spi.Plugin;

public interface AuditLogger<T>
    extends Plugin<T>
{
    /**
     * Logs an access event for sensitive data.
     * * @param context  The resolved identity of the requester.
     * @param category The sensitive category (e.g., "PII").
     * @param granted  Whether access was granted or masked.
     */
    void logAccessGranted(String user, ConsentScope scope);

    /**
     * Logs an access event for sensitive data.
     * * @param context  The resolved identity of the requester.
     * @param category The sensitive category (e.g., "PII").
     * @param granted  Whether access was granted or masked.
     */
    void logAccessDenied(String user, ConsentScope scope, String reason);

    /**
     * Logs errors or unauthorized attempts to bypass masking.
     */
    default void logAlert(String message, Throwable throwable) {
        // Optional default implementation
    }
}
