package com.koostack.operio.core.models;

import java.time.Instant;
/**
 * Describes how a single consent decision was resolved.
 *
 * <p>Every evaluated {@link ConsentScope} produces one of these, regardless
 * of whether the outcome was granted or denied. The detail captures which
 * level of the policy hierarchy determined the outcome and any associated
 * expiration timestamp.
 *
 * @param scope          the scope that was evaluated
 * @param effectiveStatus the resolved status: {@code "GRANTED"},
 *                        {@code "REVOKED"}, or {@code null} if no consent
 *                        record exists at any level
 * @param resolvedBy     which level determined the outcome:
 *                        {@code "FIELD"}, {@code "CATEGORY"}, or
 *                        {@code "NONE"} when no record was found
 * @param expiresAt      when the governing consent entry expires, or
 *                        {@code null} if the entry has no expiration
 */
public record DecisionDetail(
    ConsentScope scope,
    String effectiveStatus,
    String resolvedBy,
    Instant expiresAt
) {
    /** Resolution level: the decision was determined by a field-level consent entry. */
    public static final String RESOLVED_BY_FIELD = "FIELD";

    /** Resolution level: the decision was determined by a category-level consent entry. */
    public static final String RESOLVED_BY_CATEGORY = "CATEGORY";

    /** Resolution level: no consent record was found at any level. */
    public static final String RESOLVED_BY_NONE = "NONE";
}
