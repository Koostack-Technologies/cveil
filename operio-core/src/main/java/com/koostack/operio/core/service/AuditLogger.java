package com.koostack.operio.core.service;

import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.spi.NamedPlugin;
import com.koostack.operio.core.spi.Prioritized;

public interface AuditLogger extends NamedPlugin, Prioritized  {
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
