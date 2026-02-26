package com.koostack.operio.core.models;

import java.time.Instant;
import java.util.Map;
/**
 * A single entry in the append-only consent audit log.
 *
 * <p>Each entry represents one grant or revoke event, capturing the full
 * context required by regulators: who performed the action, when, from
 * where, and any additional request metadata.
 *
 * <p>Returned by
 * {@link com.koostack.operio.core.service.ConsentAdminService#getConsentHistory}.
 *
 * @param subject      the data subject identifier
 * @param fieldName    the field this event applies to
 * @param entityName   the entity name, or {@code null}
 * @param categoryName the category name, or {@code null}
 * @param purposeName  the purpose name, or {@code null}
 * @param action       the action performed ({@code "GRANTED"} or
 *                     {@code "REVOKED"})
 * @param expiresAt    when the consent expires, or {@code null} for no
 *                     expiration
 * @param actor        the identity that performed the action (e.g. the
 *                     admin user or the subject themselves)
 * @param ipAddress    the originating IP address, or {@code null}
 * @param userAgent    the originating User-Agent header, or {@code null}
 * @param metadata     additional request metadata key-value pairs,
 *                     possibly empty
 * @param timestamp    when the event occurred
 */
public record ConsentAuditEntry(
    String subject,
    String fieldName,
    String entityName,
    String categoryName,
    String purposeName,
    String action,
    Instant expiresAt,
    String actor,
    String ipAddress,
    String userAgent,
    Map<String, String> metadata,
    Instant timestamp
) {}
