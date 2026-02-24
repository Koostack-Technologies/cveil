package com.koostack.operio.core.models;

import java.time.Instant;
/**
 * Summary of a single consent policy row for a given subject.
 *
 * <p>Returned by
 * {@link com.koostack.operio.core.service.ConsentAdminService#findPoliciesBySubject}.
 * Provides a flat view of the policy without joining into the full
 * field/category/entity graph.
 *
 * @param subject      the data subject identifier
 * @param fieldName    the field name this policy applies to
 * @param entityName   the entity name, or {@code null}
 * @param categoryName the category name, or {@code null}
 * @param purposeName  the purpose name, or {@code null}
 * @param status       the consent status ({@code "GRANTED"} or {@code "REVOKED"})
 * @param expiresAt    when the consent expires, or {@code null} for no expiration
 * @param createdAt    when the policy row was created
 */
public record PolicySummaryEntry(
    String subject,
    String fieldName,
    String entityName,
    String categoryName,
    String purposeName,
    String status,
    Instant expiresAt,
    Instant createdAt
) {}
