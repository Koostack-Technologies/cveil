package com.koostack.operio.plugin.consent.caffeine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.DecisionDetail;
import com.koostack.operio.core.models.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory consent entry storage backed by a Caffeine {@link Cache} with
 * per-entry TTL support.
 *
 * <p>This is the Caffeine counterpart of
 * {@code ConsentPolicyRedisRepository} — it provides the same write and
 * read semantics but stores everything in-process rather than in Redis.
 *
 * <h2>Key Schema</h2>
 * <p>Each consent entry is keyed by a composite string:
 * {@code {subject}:{entity/field}:{purpose}}. The entity component uses
 * the sentinel {@value #DEFAULT_ENTITY} when no entity is specified, mirroring
 * the Redis repository convention.
 *
 * <h2>Value Encoding</h2>
 * <p>Values are stored as {@link ConsentEntry} records that capture the
 * status ({@value #GRANTED} or {@value #REVOKED}), the source tag
 * ({@value #SOURCE_FIELD} or {@value #SOURCE_CATEGORY}), and an optional
 * expiration timestamp. Caffeine's variable expiration policy automatically
 * evicts entries when their {@code expiresAt} is reached.
 *
 * <h2>Thread Safety</h2>
 * <p>All operations are thread-safe — Caffeine caches are concurrent by
 * design.
 *
 * @see CaffeineConsentDecisionService
 * @see CaffeineFieldCategoryMappingRepository
 */
public class CaffeineConsentRepository {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /** Status value stored when consent is granted. */
    public static final String GRANTED = "GRANTED";

    /** Status value stored when consent is revoked. */
    public static final String REVOKED = "REVOKED";

    /** Source tag: the entry was set by a direct field-level policy. */
    public static final String SOURCE_FIELD = "FIELD";

    /** Source tag: the entry was materialized from a category-level policy. */
    public static final String SOURCE_CATEGORY = "CATEGORY";

    /** Sentinel entity name used when a field has no owning entity. */
    public static final String DEFAULT_ENTITY = "_default_";

    /** Separator between entity and field name inside a key segment. */
    static final char ENTITY_FIELD_SEP = '/';

    /** Separator between key segments. */
    static final char KEY_SEP = ':';

    // -----------------------------------------------------------------------
    // Entry record
    // -----------------------------------------------------------------------

    /**
     * A single consent entry stored in the cache.
     *
     * @param status    {@value #GRANTED} or {@value #REVOKED}
     * @param source    {@value #SOURCE_FIELD} or {@value #SOURCE_CATEGORY}
     * @param expiresAt when this entry expires, or {@code null} for no
     *                  expiration
     */
    public record ConsentEntry(
        String status,
        String source,
        Instant expiresAt
    ) {}

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final Cache<String, ConsentEntry> cache;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Creates a repository with the given maximum cache size.
     *
     * <p>Per-entry expiration is handled by a custom {@link Expiry} that
     * reads the {@link ConsentEntry#expiresAt()} timestamp. Entries with
     * no expiration ({@code null}) are retained indefinitely (subject to
     * size-based eviction).
     *
     * @param maxSize the maximum number of entries in the cache; must be
     *                positive
     */
    public CaffeineConsentRepository(long maxSize) {
        this.cache = Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfter(new ConsentEntryExpiry())
            .build();
    }

    // -----------------------------------------------------------------------
    // Qualified field helpers
    // -----------------------------------------------------------------------

    /**
     * Produces a qualified field string that uniquely identifies a field
     * across entities: {@code {entity}/{fieldName}}.
     *
     * <p>When {@code entity} is {@code null} or blank the sentinel
     * {@value #DEFAULT_ENTITY} is used.
     *
     * @param entity the entity name, or {@code null}
     * @param field  the field name
     * @return the qualified field string
     */
    public static String qualifyField(String entity, String field) {
        String e = (entity != null && !entity.isBlank())
            ? entity
            : DEFAULT_ENTITY;
        return e + ENTITY_FIELD_SEP + field;
    }

    // -----------------------------------------------------------------------
    // Key helpers
    // -----------------------------------------------------------------------

    /**
     * Builds the cache key for a consent entry using a pre-qualified field
     * string.
     *
     * @param subject        the subject identifier
     * @param qualifiedField the qualified field ({@code entity/field})
     * @param purpose        the purpose name
     * @return {@code {subject}:{qualifiedField}:{purpose}}
     */
    /**
     * Computes the key prefix for all cache keys, incorporating the
     * current tenant context when present.
     *
     * <p>When {@link TenantContext#get()} returns a non-null tenant ID
     * the prefix is {@code {tenantId}:}. When no tenant context is active
     * the prefix is empty — backward compatible with existing
     * single-tenant keys.
     *
     * @return the key prefix (empty string when no tenant, otherwise
     *         {@code {tenantId}:})
     */
    String keyPrefix() {
        String tenant = TenantContext.get();
        return tenant != null ? tenant + KEY_SEP : "";
    }

    String consentKey(String subject, String qualifiedField, String purpose) {
        return (
            keyPrefix() + subject + KEY_SEP + qualifiedField + KEY_SEP + purpose
        );
    }

    /**
     * Builds the cache key for a consent entry.
     *
     * @param subject the subject identifier
     * @param entity  the entity name, or {@code null} for the default entity
     * @param field   the field name
     * @param purpose the purpose name
     * @return {@code {subject}:{entity/field}:{purpose}}
     */
    String consentKey(
        String subject,
        String entity,
        String field,
        String purpose
    ) {
        return consentKey(subject, qualifyField(entity, field), purpose);
    }

    // -----------------------------------------------------------------------
    // Write — generic
    // -----------------------------------------------------------------------

    /**
     * Stores a consent decision with no expiration.
     *
     * @param subject the subject identifier
     * @param entity  the entity name, or {@code null}
     * @param field   the field name
     * @param purpose the purpose name
     * @param granted {@code true} to grant, {@code false} to revoke
     * @param source  {@value #SOURCE_FIELD} or {@value #SOURCE_CATEGORY}
     */
    public void setConsent(
        String subject,
        String entity,
        String field,
        String purpose,
        boolean granted,
        String source
    ) {
        setConsent(subject, entity, field, purpose, granted, source, null);
    }

    /**
     * Stores a consent decision with an optional expiration.
     *
     * <p>If the computed time-to-live is zero or negative the write is
     * silently skipped (the entry is already expired).
     *
     * @param subject   the subject identifier
     * @param entity    the entity name, or {@code null}
     * @param field     the field name
     * @param purpose   the purpose name
     * @param granted   {@code true} to grant, {@code false} to revoke
     * @param source    {@value #SOURCE_FIELD} or {@value #SOURCE_CATEGORY}
     * @param expiresAt when this consent expires, or {@code null} for no
     *                  expiry
     */
    public void setConsent(
        String subject,
        String entity,
        String field,
        String purpose,
        boolean granted,
        String source,
        Instant expiresAt
    ) {
        if (expiresAt != null) {
            long ttlSeconds = Duration.between(
                Instant.now(),
                expiresAt
            ).getSeconds();
            if (ttlSeconds <= 0) {
                return; // already expired — nothing to write
            }
        }
        cache.put(
            consentKey(subject, entity, field, purpose),
            new ConsentEntry(granted ? GRANTED : REVOKED, source, expiresAt)
        );
    }

    // -----------------------------------------------------------------------
    // Write — field-level convenience
    // -----------------------------------------------------------------------

    /**
     * Stores a field-level consent decision with no expiration.
     *
     * <p>Convenience wrapper that sets source to {@value #SOURCE_FIELD}.
     *
     * @param subject the subject identifier
     * @param entity  the entity name, or {@code null}
     * @param field   the field name
     * @param purpose the purpose name
     * @param granted {@code true} to grant, {@code false} to revoke
     */
    public void setFieldConsent(
        String subject,
        String entity,
        String field,
        String purpose,
        boolean granted
    ) {
        setConsent(subject, entity, field, purpose, granted, SOURCE_FIELD);
    }

    /**
     * Stores a field-level consent decision with an optional expiration.
     *
     * <p>Convenience wrapper that sets source to {@value #SOURCE_FIELD}.
     *
     * @param subject   the subject identifier
     * @param entity    the entity name, or {@code null}
     * @param field     the field name
     * @param purpose   the purpose name
     * @param granted   {@code true} to grant, {@code false} to revoke
     * @param expiresAt when this consent expires, or {@code null} for no
     *                  expiry
     */
    public void setFieldConsent(
        String subject,
        String entity,
        String field,
        String purpose,
        boolean granted,
        Instant expiresAt
    ) {
        setConsent(
            subject,
            entity,
            field,
            purpose,
            granted,
            SOURCE_FIELD,
            expiresAt
        );
    }

    // -----------------------------------------------------------------------
    // Write — category fan-out convenience
    // -----------------------------------------------------------------------

    /**
     * Materializes a category-level consent decision across a set of
     * <strong>qualified</strong> field names with no expiration.
     *
     * <p>For each qualified field in {@code qualifiedFields}, an entry is
     * written with source {@value #SOURCE_CATEGORY}. This is the write-time
     * fan-out that pre-materializes category policies into individual field
     * entries so that the read path ({@link #evaluateScopes}) needs only
     * simple lookups.
     *
     * <p><strong>Field-level precedence:</strong> fields that already have
     * an explicit {@value #SOURCE_FIELD} entry are skipped — a category
     * fan-out never overwrites a direct field-level policy.
     *
     * @param subject         the subject identifier
     * @param purpose         the purpose name
     * @param granted         {@code true} to grant, {@code false} to revoke
     * @param qualifiedFields the set of qualified field names
     *                        ({@code entity/field}) to write entries for
     */
    public void setCategoryConsentForFields(
        String subject,
        String purpose,
        boolean granted,
        Set<String> qualifiedFields
    ) {
        setCategoryConsentForFields(
            subject,
            purpose,
            granted,
            null,
            qualifiedFields
        );
    }

    /**
     * Materializes a category-level consent decision across a set of
     * qualified fields with an optional expiration.
     *
     * <p><strong>Field-level precedence:</strong> fields that already have
     * an explicit {@value #SOURCE_FIELD} entry are skipped — see
     * {@link #setCategoryConsentForFields(String, String, boolean, Set)}
     * for details.
     *
     * <p>If the computed TTL is zero or negative, no writes are performed.
     *
     * @param subject         the subject identifier
     * @param purpose         the purpose name
     * @param granted         {@code true} to grant, {@code false} to revoke
     * @param expiresAt       when these entries expire, or {@code null} for
     *                        no expiry
     * @param qualifiedFields the set of qualified field names
     *                        ({@code entity/field})
     */
    public void setCategoryConsentForFields(
        String subject,
        String purpose,
        boolean granted,
        Instant expiresAt,
        Set<String> qualifiedFields
    ) {
        if (qualifiedFields == null || qualifiedFields.isEmpty()) {
            return;
        }
        if (expiresAt != null) {
            long ttlSeconds = Duration.between(
                Instant.now(),
                expiresAt
            ).getSeconds();
            if (ttlSeconds <= 0) {
                return; // already expired
            }
        }

        Set<String> writeable = filterFieldSourcedEntries(
            subject,
            purpose,
            qualifiedFields
        );

        String status = granted ? GRANTED : REVOKED;
        for (String qf : writeable) {
            cache.put(
                consentKey(subject, qf, purpose),
                new ConsentEntry(status, SOURCE_CATEGORY, expiresAt)
            );
        }
    }

    // -----------------------------------------------------------------------
    // Read — single entry
    // -----------------------------------------------------------------------

    /**
     * Reads the consent status for a single field (raw status only).
     *
     * @param subject the subject identifier
     * @param entity  the entity name, or {@code null}
     * @param field   the field name
     * @param purpose the purpose name
     * @return the status ({@value #GRANTED} or {@value #REVOKED}), or
     *         empty if no entry exists
     */
    public Optional<String> getConsent(
        String subject,
        String entity,
        String field,
        String purpose
    ) {
        ConsentEntry entry = cache.getIfPresent(
            consentKey(subject, entity, field, purpose)
        );
        return entry != null ? Optional.of(entry.status()) : Optional.empty();
    }

    /**
     * Reads the full consent entry for a single field (status + source +
     * expiration).
     *
     * @param subject the subject identifier
     * @param entity  the entity name, or {@code null}
     * @param field   the field name
     * @param purpose the purpose name
     * @return the entry, or empty if no entry exists
     */
    public Optional<ConsentEntry> getConsentEntry(
        String subject,
        String entity,
        String field,
        String purpose
    ) {
        return Optional.ofNullable(
            cache.getIfPresent(consentKey(subject, entity, field, purpose))
        );
    }

    // -----------------------------------------------------------------------
    // Remove
    // -----------------------------------------------------------------------

    /**
     * Removes a consent entry for a specific field.
     *
     * @param subject the subject identifier
     * @param entity  the entity name, or {@code null}
     * @param field   the field name
     * @param purpose the purpose name
     */
    public void removeConsent(
        String subject,
        String entity,
        String field,
        String purpose
    ) {
        cache.invalidate(consentKey(subject, entity, field, purpose));
    }

    // -----------------------------------------------------------------------
    // Batch evaluation — the hot path
    // -----------------------------------------------------------------------

    /**
     * Evaluates consent for a batch of scopes against a subject.
     *
     * <p>This is the primary entry point for consent evaluation. It looks
     * up each scope in the cache and maps the result into a
     * {@link DecisionDetail}. Unlike the Redis implementation, there is no
     * network round trip — each lookup is an O(1) in-memory operation.
     *
     * @param subject        the subject identifier
     * @param accessRequests the set of scopes to evaluate
     * @return a map from each requested scope to its {@link DecisionDetail}
     */
    public Map<ConsentScope, DecisionDetail> evaluateScopes(
        String subject,
        Set<ConsentScope> accessRequests
    ) {
        if (accessRequests == null || accessRequests.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<ConsentScope, DecisionDetail> result = new LinkedHashMap<>();

        for (ConsentScope scope : accessRequests) {
            String key = consentKey(
                subject,
                scope.entityName(),
                scope.fieldName(),
                scope.purposeName()
            );
            ConsentEntry entry = cache.getIfPresent(key);

            String effectiveStatus;
            String resolvedBy;
            Instant expiresAt;

            if (entry != null) {
                effectiveStatus = entry.status();
                resolvedBy = entry.source();
                expiresAt = entry.expiresAt();
            } else {
                effectiveStatus = null;
                resolvedBy = DecisionDetail.RESOLVED_BY_NONE;
                expiresAt = null;
            }

            result.put(
                scope,
                new DecisionDetail(
                    scope,
                    effectiveStatus,
                    resolvedBy,
                    expiresAt
                )
            );
        }

        return result;
    }

    // -----------------------------------------------------------------------
    // Housekeeping
    // -----------------------------------------------------------------------

    /**
     * Checks whether a consent entry exists for the given field.
     *
     * @param subject the subject identifier
     * @param entity  the entity name, or {@code null}
     * @param field   the field name
     * @param purpose the purpose name
     * @return {@code true} if an entry exists
     */
    public boolean exists(
        String subject,
        String entity,
        String field,
        String purpose
    ) {
        return (
            cache.getIfPresent(consentKey(subject, entity, field, purpose)) !=
            null
        );
    }

    /**
     * Removes all consent entries from the cache matching the given subject.
     *
     * <p>Since Caffeine does not support pattern-based key scanning, this
     * method iterates over all keys in the cache and invalidates those that
     * start with the subject prefix. This is intended for administrative /
     * test cleanup — not for the hot evaluation path.
     *
     * @param subject the subject identifier
     * @return the number of entries removed
     */
    public long removeAllConsent(String subject) {
        String prefix = keyPrefix() + subject + KEY_SEP;
        List<String> toRemove = new ArrayList<>();
        for (String key : cache.asMap().keySet()) {
            if (key.startsWith(prefix)) {
                toRemove.add(key);
            }
        }
        cache.invalidateAll(toRemove);
        return toRemove.size();
    }

    /**
     * Returns the estimated number of entries in the cache.
     *
     * @return the approximate entry count
     */
    public long estimatedSize() {
        return cache.estimatedSize();
    }

    /**
     * Removes all entries from the cache.
     */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    /**
     * Forces any pending evictions to run immediately.
     *
     * <p>Useful in tests to ensure expired entries are cleaned up before
     * assertions.
     */
    public void cleanUp() {
        cache.cleanUp();
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Filters out qualified fields that already have an explicit
     * {@value #SOURCE_FIELD} entry in the cache.
     *
     * <p>Returns only those fields whose current entry is either absent or
     * has {@value #SOURCE_CATEGORY} source — i.e. the fields that are safe
     * for a category fan-out write.
     *
     * @param subject         the subject identifier
     * @param purpose         the purpose name
     * @param qualifiedFields the candidate qualified field names
     * @return the subset of {@code qualifiedFields} that do <em>not</em>
     *         have a {@value #SOURCE_FIELD} entry (may be empty, never
     *         {@code null})
     */
    private Set<String> filterFieldSourcedEntries(
        String subject,
        String purpose,
        Set<String> qualifiedFields
    ) {
        Set<String> writeable = new LinkedHashSet<>();
        for (String qf : qualifiedFields) {
            ConsentEntry existing = cache.getIfPresent(
                consentKey(subject, qf, purpose)
            );
            if (existing == null || !SOURCE_FIELD.equals(existing.source())) {
                writeable.add(qf);
            }
        }
        return writeable;
    }

    // -----------------------------------------------------------------------
    // Expiry policy
    // -----------------------------------------------------------------------

    /**
     * Caffeine {@link Expiry} implementation that derives per-entry
     * expiration from the {@link ConsentEntry#expiresAt()} timestamp.
     *
     * <p>Entries with no expiration ({@code expiresAt == null}) are retained
     * indefinitely ({@link Long#MAX_VALUE} nanoseconds). Entries whose
     * expiration is in the past are evicted immediately (duration = 0).
     */
    private static final class ConsentEntryExpiry
        implements Expiry<String, ConsentEntry>
    {

        @Override
        public long expireAfterCreate(
            String key,
            ConsentEntry value,
            long currentTime
        ) {
            return computeNanos(value);
        }

        @Override
        public long expireAfterUpdate(
            String key,
            ConsentEntry value,
            long currentTime,
            long currentDuration
        ) {
            return computeNanos(value);
        }

        @Override
        public long expireAfterRead(
            String key,
            ConsentEntry value,
            long currentTime,
            long currentDuration
        ) {
            // Do not change expiration on read — consent TTL is absolute,
            // not sliding.
            return currentDuration;
        }

        private static long computeNanos(ConsentEntry value) {
            if (value.expiresAt() == null) {
                return Long.MAX_VALUE;
            }
            long nanos = Duration.between(
                Instant.now(),
                value.expiresAt()
            ).toNanos();
            return Math.max(nanos, 0);
        }
    }
}
