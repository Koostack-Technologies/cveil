package com.koostack.operio.core;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public interface Models {
    public record IdentityContext(String user, String token) {}

    public record ConsentScope(
        String category,
        String fieldName,
        String entityName,
        String purposeName
    ) {
        /**
         * Backward-compatible factory — creates a scope with no entity
         * context. Suitable when the caller does not know (or does not
         * care about) which entity the field belongs to.
         */
        public static ConsentScope to(
            String category,
            String fieldName,
            String forPurpose
        ) {
            return new ConsentScope(category, fieldName, null, forPurpose);
        }

        /**
         * Full factory including entity name for unambiguous field
         * identification across entities.
         *
         * @param category   the consent category (e.g. {@code "PII"})
         * @param fieldName  the field name (e.g. {@code "email"})
         * @param entityName the owning entity (e.g. {@code "User"}), or
         *                   {@code null} for entity-less fields
         * @param forPurpose the purpose (e.g. {@code "marketing"})
         * @return a fully qualified consent scope
         */
        public static ConsentScope to(
            String category,
            String fieldName,
            String entityName,
            String forPurpose
        ) {
            return new ConsentScope(
                category,
                fieldName,
                entityName,
                forPurpose
            );
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

    // ===================================================================
    // Sensitive Discovery models
    // ===================================================================

    /**
     * A single sensitive field discovered by a {@link
     * com.koostack.operio.core.service.SensitiveDiscovery} plugin.
     *
     * <p>Captures all the metadata needed to register the field in the
     * consent admin schema: the field name, its owning entity (optional),
     * the category it belongs to (optional), a default purpose, and a
     * human-readable description.
     *
     * @param fieldName   the name of the sensitive field (e.g. {@code "email"},
     *                    {@code "home.street"} for nested paths)
     * @param entityName  the logical entity that owns this field (e.g.
     *                    {@code "User"}, {@code "Patient"}), or {@code null}
     *                    for entity-less fields
     * @param category    the category this field belongs to (e.g.
     *                    {@code "PII"}, {@code "HEALTH"}), or {@code null}
     *                    if no category is declared
     * @param purpose     the default purpose associated with this field
     *                    (e.g. {@code "marketing"}), or {@code null} if no
     *                    purpose is declared
     * @param description optional human-readable description of the field,
     *                    or {@code null}
     */
    public record DiscoveredField(
        String fieldName,
        String entityName,
        String category,
        String purpose,
        String description
    ) {
        public DiscoveredField {
            Objects.requireNonNull(fieldName, "fieldName must not be null");
            if (fieldName.isBlank()) {
                throw new IllegalArgumentException(
                    "fieldName must not be blank"
                );
            }
        }

        /**
         * Convenience factory for the common case where only the field name,
         * entity, and category are known.
         */
        public static DiscoveredField of(
            String fieldName,
            String entityName,
            String category
        ) {
            return new DiscoveredField(
                fieldName,
                entityName,
                category,
                null,
                null
            );
        }

        /**
         * Full factory including purpose and description.
         */
        public static DiscoveredField of(
            String fieldName,
            String entityName,
            String category,
            String purpose,
            String description
        ) {
            return new DiscoveredField(
                fieldName,
                entityName,
                category,
                purpose,
                description
            );
        }
    }

    /**
     * The result of a sensitive-field discovery scan.
     *
     * <p>Contains the full set of discovered fields (with their entity and
     * category assignments) and a convenience set of all distinct category
     * names found during the scan.
     *
     * <p>Consumers use this to register the discovered metadata into the
     * consent admin schema via
     * {@link com.koostack.operio.core.service.ConsentAdminService} registration
     * methods.
     *
     * @param fields     every discovered sensitive field, possibly empty
     * @param categories the distinct category names extracted from the
     *                   discovered fields, possibly empty
     */
    public record DiscoveryResult(
        Set<DiscoveredField> fields,
        Set<String> categories
    ) {
        public DiscoveryResult {
            fields =
                fields != null
                    ? Collections.unmodifiableSet(fields)
                    : Collections.emptySet();
            categories =
                categories != null
                    ? Collections.unmodifiableSet(categories)
                    : Collections.emptySet();
        }

        /** Returns an empty discovery result. */
        public static DiscoveryResult empty() {
            return new DiscoveryResult(
                Collections.emptySet(),
                Collections.emptySet()
            );
        }
    }
}
