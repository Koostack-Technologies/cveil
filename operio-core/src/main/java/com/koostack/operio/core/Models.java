package com.koostack.operio.core;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public interface Models {
    /**
     * Identity information resolved from the current request or execution context.
     *
     * @param subject   unique user ID (OIDC terminology, replaces former {@code user})
     * @param token     raw bearer token, nullable
     * @param email     user email, nullable
     * @param roles     authorities/roles from the IdP
     * @param claims    arbitrary IdP claims (OIDC, SAML, etc.)
     * @param tenantId  nullable — for multi-tenancy routing
     * @param provider  which IdP issued this (e.g. {@code "spring-security"}, {@code "okta"})
     */
    public record IdentityContext(
        String subject,
        String token,
        String email,
        Set<String> roles,
        Map<String, Object> claims,
        String tenantId,
        String provider
    ) {
        public static IdentityContext of(String subject, String token) {
            return new IdentityContext(
                subject,
                token,
                null,
                Set.of(),
                Map.of(),
                null,
                null
            );
        }

        public static IdentityContext anonymous() {
            return new IdentityContext(
                "anonymous",
                null,
                null,
                Set.of(),
                Map.of(),
                null,
                null
            );
        }
    }

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

    // ===================================================================
    // Platform Admin DTOs
    // ===================================================================

    /**
     * Immutable snapshot of a tenant's metadata.
     *
     * <p>Returned by {@link com.koostack.operio.core.service.PlatformAdminService}
     * query methods. Contains plan-level limits used for rate limiting and
     * quota enforcement at the REST layer.
     *
     * @param tenantId          unique tenant identifier (e.g. {@code "acme_corp"})
     * @param displayName       human-readable tenant name
     * @param plan              subscription plan ({@code "free"}, {@code "pro"},
     *                          {@code "enterprise"})
     * @param status            tenant status ({@code "ACTIVE"}, {@code "SUSPENDED"},
     *                          {@code "DELETED"})
     * @param evalLimitMonthly  maximum consent evaluations per calendar month
     * @param subjectLimit      maximum distinct data subjects
     * @param burstLimitPerMin  maximum requests per minute (burst cap)
     * @param createdAt         when the tenant was provisioned
     */
    public record TenantInfo(
        String tenantId,
        String displayName,
        String plan,
        String status,
        long evalLimitMonthly,
        long subjectLimit,
        int burstLimitPerMin,
        Instant createdAt
    ) {}

    /**
     * Immutable snapshot of an API key's metadata.
     *
     * <p>Returned by {@link com.koostack.operio.core.service.PlatformAdminService}
     * list/query methods. Never contains the raw key or its hash — only the
     * non-sensitive prefix and associated metadata.
     *
     * @param keyPrefix  the first 8 characters of the raw key (used for
     *                   display and revocation)
     * @param tenantId   the owning tenant
     * @param subject    the subject (user/service account) this key represents
     * @param roles      the roles/authorities granted to this key
     * @param status     key status ({@code "ACTIVE"}, {@code "REVOKED"})
     * @param createdAt  when the key was created
     */
    public record ApiKeyInfo(
        String keyPrefix,
        String tenantId,
        String subject,
        Set<String> roles,
        String status,
        Instant createdAt
    ) {}

    /**
     * Immutable snapshot of an external IdP configuration for a tenant.
     *
     * <p>Returned by {@link com.koostack.operio.core.service.PlatformAdminService}
     * IdP query methods. Contains the provider-specific configuration map
     * (e.g. {@code issuerUri}, {@code clientId}, {@code jwksUri}).
     *
     * @param tenantId  the owning tenant
     * @param provider  the IdP identifier (e.g. {@code "okta"}, {@code "auth0"},
     *                  {@code "azure-ad"}, {@code "keycloak"})
     * @param config    provider-specific configuration key-value pairs
     * @param createdAt when the configuration was created or last updated
     */
    public record IdpConfigInfo(
        String tenantId,
        String provider,
        Map<String, Object> config,
        Instant createdAt
    ) {}

    // ===================================================================
    // Consent Admin Query DTOs
    // ===================================================================

    /**
     * Immutable snapshot of a registered sensitive field.
     *
     * @param fieldName   the field name (e.g. {@code "email"}, {@code "home.street"})
     * @param entityName  the owning entity (e.g. {@code "User"}), or {@code null}
     *                    for entity-less fields
     * @param description optional human-readable description, or {@code null}
     */
    public record FieldInfo(
        String fieldName,
        String entityName,
        String description
    ) {}

    /**
     * Immutable snapshot of a registered consent category.
     *
     * @param categoryName the category name (e.g. {@code "PII"}, {@code "HEALTH"})
     * @param description  optional human-readable description, or {@code null}
     */
    public record CategoryInfo(String categoryName, String description) {}

    /**
     * Immutable snapshot of a registered entity.
     *
     * @param entityName  the entity name (e.g. {@code "User"}, {@code "Patient"})
     * @param description optional human-readable description, or {@code null}
     */
    public record EntityInfo(String entityName, String description) {}

    /**
     * Immutable snapshot of a registered consent purpose.
     *
     * @param purposeName the purpose identifier (e.g. {@code "marketing"},
     *                    {@code "analytics"})
     * @param description optional human-readable description, or {@code null}
     */
    public record PurposeInfo(String purposeName, String description) {}

    /**
     * Immutable snapshot of a registered regulation.
     *
     * @param regulationName short identifier (e.g. {@code "GDPR"}, {@code "CCPA"})
     * @param fullName       full legal name (e.g. {@code "General Data Protection
     *                       Regulation"})
     * @param jurisdiction   geographic jurisdiction (e.g. {@code "EU"}, {@code "US-CA"})
     * @param description    optional human-readable description, or {@code null}
     * @param url            optional URL to the regulation text, or {@code null}
     */
    public record RegulationInfo(
        String regulationName,
        String fullName,
        String jurisdiction,
        String description,
        String url
    ) {}

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

    // ===================================================================
    // Batch Consent DTOs
    // ===================================================================

    /**
     * A single entry in a batch consent grant or revoke request.
     *
     * @param scope     the consent scope to grant or revoke
     * @param expiresAt when the consent expires, or {@code null} for no
     *                  expiration
     */
    public record BatchConsentEntry(ConsentScope scope, Instant expiresAt) {}

    /**
     * The result of a batch consent operation (grant or revoke).
     *
     * <p>Each item in the batch is independently atomic — partial success is
     * possible. Failed items are captured in {@link #errors} with the
     * original index, scope, and error message.
     *
     * @param succeeded the number of items that completed successfully
     * @param failed    the number of items that failed
     * @param errors    details for each failed item, possibly empty
     */
    public record BatchConsentResult(
        int succeeded,
        int failed,
        List<BatchItemError> errors
    ) {
        /**
         * Describes a single failed item within a batch consent operation.
         *
         * @param index   the zero-based index of the failed item in the
         *                original request list
         * @param scope   the consent scope that failed
         * @param message a human-readable error message
         */
        public record BatchItemError(
            int index,
            ConsentScope scope,
            String message
        ) {}
    }
}
