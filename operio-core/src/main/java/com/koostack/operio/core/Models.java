package com.koostack.operio.core;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public interface Models {
    public record IdentityContext(String user, String token) {}

    public record ConsentScope(
        String category,
        String fieldName,
        String purposeName
    ) {
        /**
         * Factory method for cleaner syntax in the Aspect logic.
         */
        public static ConsentScope to(
            String category,
            String fieldName,
            String forPurpose
        ) {
            return new ConsentScope(category, fieldName, forPurpose);
        }
    }

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

    /**
     * The result of evaluating consent for a subject against a set of
     * {@link ConsentScope}s.
     *
     * @param subject the subject whose consent was evaluated
     * @param granted the set of scopes that were granted
     * @param denied  the set of scopes that were denied
     * @param reasons human-readable denial reasons keyed by field name
     *                (backward-compatible; prefer {@link #details} for
     *                structured audit data)
     * @param details per-scope structured audit information describing how
     *                each decision was resolved — includes the resolution
     *                level ({@code FIELD}, {@code CATEGORY}, {@code NONE})
     *                and any expiration timestamp
     */
    public record ConsentResponse(
        String subject,
        Set<ConsentScope> granted,
        Set<ConsentScope> denied,
        Map<String, String> reasons,
        Map<ConsentScope, DecisionDetail> details
    ) {
        /**
         * Full factory method including structured decision details.
         */
        public static ConsentResponse to(
            String subject,
            Set<ConsentScope> granted,
            Set<ConsentScope> denied,
            Map<String, String> reasons,
            Map<ConsentScope, DecisionDetail> details
        ) {
            return new ConsentResponse(
                subject,
                granted,
                denied,
                reasons,
                details
            );
        }

        /**
         * Backward-compatible factory method — creates a response with an
         * empty {@code details} map. Existing callers that do not yet
         * populate {@link DecisionDetail}s continue to work unchanged.
         */
        public static ConsentResponse to(
            String subject,
            Set<ConsentScope> granted,
            Set<ConsentScope> denied,
            Map<String, String> reasons
        ) {
            return new ConsentResponse(
                subject,
                granted,
                denied,
                reasons,
                Collections.emptyMap()
            );
        }
    }
}
