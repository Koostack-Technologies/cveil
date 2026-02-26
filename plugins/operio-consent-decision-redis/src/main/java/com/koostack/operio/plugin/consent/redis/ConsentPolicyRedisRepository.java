package com.koostack.operio.plugin.consent.redis;

import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.DecisionDetail;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.plugin.redis.common.JedisProvider;
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
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;

/**
 * Repository for consent policy storage in Redis, optimized for batch
 * evaluation of consent decisions.
 *
 * <h2>Redis Key Schema</h2>
 * <p>Each consent decision is stored as a standalone Redis string key:
 * <pre>
 *   Single-tenant: {appName}:consent:{subject}:{entity}/{fieldName}:{purposeName}
 *   Multi-tenant:  {appName}:{tenantId}:consent:{subject}:{entity}/{fieldName}:{purposeName}
 *   Value:         GRANTED|FIELD  |  REVOKED|FIELD  |  GRANTED|CATEGORY  |  REVOKED|CATEGORY
 * </pre>
 *
 * <p>The {@code entity} component prevents field-name collisions across
 * different domain entities (e.g. {@code User/email} vs
 * {@code Contact/email}). When no entity is associated with a field the
 * sentinel {@value #DEFAULT_ENTITY} is used to keep the key structure
 * uniform.
 *
 * <p>The {@code appName} prefix isolates different Operio instances that
 * share the same Redis server. It defaults to {@value #DEFAULT_APP_NAME}
 * and can be changed at any time via {@link #setAppName(String)}.
 *
 * <h2>Multi-Tenancy</h2>
 * <p>When a {@link TenantContext} is active (i.e.
 * {@code TenantContext.get()} returns a non-null tenant ID), all keys
 * include a {@code {tenantId}} segment immediately after the
 * {@code {appName}} prefix. This provides namespace isolation between
 * tenants sharing the same Redis instance. When no tenant context is
 * active the segment is omitted — backward compatible with existing
 * single-tenant deployments.
 *
 * <p>Values are pipe-delimited: the first segment is the consent status
 * ({@value #GRANTED} or {@value #REVOKED}), the second is the source that
 * produced the entry ({@value #SOURCE_FIELD} for a direct field-level policy,
 * {@value #SOURCE_CATEGORY} for a policy materialized from a category).
 *
 * <h2>Expiration</h2>
 * <p>Expiration is handled by <strong>native Redis TTL</strong> — keys are
 * written with {@code SET ... EX} when an {@link Instant expiresAt} is
 * provided. Once expired, Redis removes the key automatically. There is no
 * epoch-seconds encoding in the value and no read-time expiry check.
 *
 * <p>The relational {@code consent_policy.expires_at} column remains the
 * source of truth for expiration metadata (e.g. for audit or future
 * non-Redis backends). The Redis TTL is kept in sync with that column at
 * write time.
 *
 * <h2>Evaluation Algorithm</h2>
 * <p>Evaluation is a single {@code MGET} call:
 * <ol>
 *   <li>Build one key per requested {@link ConsentScope}:
 *       {@code {appName}:consent:{subject}:{entity}/{fieldName}:{purposeName}}</li>
 *   <li>{@code MGET} all keys in one round trip</li>
 *   <li>Map each response positionally: non-null → parse status and source;
 *       {@code null} → no consent record (denied)</li>
 * </ol>
 * <p>Regardless of batch size, evaluation always costs exactly
 * <strong>one Redis round trip</strong>.
 */
public class ConsentPolicyRedisRepository {

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

    /** Default application name used as the Redis key prefix. */
    public static final String DEFAULT_APP_NAME = "operio";

    /** Sentinel entity name used when a field has no owning entity. */
    public static final String DEFAULT_ENTITY = "_default_";

    /** Separator between entity and field name inside a key segment. */
    static final char ENTITY_FIELD_SEP = '/';

    /** Delimiter between status and source in stored values. */
    static final char DELIMITER = '|';

    private final JedisProvider jedisProvider;
    private String appName;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Creates a repository with the default application name
     * ({@value #DEFAULT_APP_NAME}) and a shared {@link JedisPooled} client.
     *
     * <p><strong>Note:</strong> This constructor wraps the given client in
     * {@link JedisProvider#shared(JedisPooled)}, meaning all tenants share
     * the same connection (application-level key-prefix isolation only).
     * For server-side Redis ACL isolation in multi-tenant deployments, use
     * {@link #ConsentPolicyRedisRepository(JedisProvider)} or
     * {@link #ConsentPolicyRedisRepository(JedisProvider, String)} instead.
     *
     * @param jedis the Jedis client
     */
    public ConsentPolicyRedisRepository(JedisPooled jedis) {
        this(JedisProvider.shared(jedis), DEFAULT_APP_NAME);
    }

    /**
     * Creates a repository with the given application name and a shared
     * {@link JedisPooled} client.
     *
     * <p><strong>Note:</strong> This constructor wraps the given client in
     * {@link JedisProvider#shared(JedisPooled)}. See
     * {@link #ConsentPolicyRedisRepository(JedisPooled)} for details.
     *
     * @param jedis   the Jedis client
     * @param appName the application name prefix, or {@code null} to use
     *                the default
     */
    public ConsentPolicyRedisRepository(JedisPooled jedis, String appName) {
        this(JedisProvider.shared(jedis), appName);
    }

    /**
     * Creates a repository with the default application name and a
     * {@link JedisProvider} for per-tenant Redis connection resolution.
     *
     * <p>This is the recommended constructor for multi-tenant deployments.
     * Pass a {@link com.koostack.operio.plugin.redis.common.TenantAwareJedisProvider}
     * to enforce Redis-level ACL isolation per tenant.
     *
     * @param jedisProvider the provider that resolves the correct
     *                      {@link JedisPooled} for the current tenant context
     */
    public ConsentPolicyRedisRepository(JedisProvider jedisProvider) {
        this(jedisProvider, DEFAULT_APP_NAME);
    }

    /**
     * Creates a repository with the given application name and a
     * {@link JedisProvider} for per-tenant Redis connection resolution.
     *
     * @param jedisProvider the provider that resolves the correct
     *                      {@link JedisPooled} for the current tenant context
     * @param appName       the application name prefix, or {@code null} to
     *                      use the default
     */
    public ConsentPolicyRedisRepository(
        JedisProvider jedisProvider,
        String appName
    ) {
        if (jedisProvider == null) {
            throw new NullPointerException("JedisProvider must not be null");
        }
        this.jedisProvider = jedisProvider;
        this.appName = appName != null ? appName : DEFAULT_APP_NAME;
    }

    /**
     * Returns the {@link JedisProvider} used by this repository.
     *
     * <p>Primarily useful for wiring — e.g. passing the same provider to
     * {@link FieldCategoryMappingRedisRepository} so both repositories
     * share the same per-tenant connection cache.
     *
     * @return the Jedis provider, never {@code null}
     */
    public JedisProvider jedisProvider() {
        return jedisProvider;
    }

    // -----------------------------------------------------------------------
    // App name
    // -----------------------------------------------------------------------

    /**
     * Changes the application name prefix used in all Redis keys generated
     * by this repository.
     *
     * @param appName the new application name, or {@code null} to revert to
     *                the default
     */
    public void setAppName(String appName) {
        this.appName = appName != null ? appName : DEFAULT_APP_NAME;
    }

    /**
     * Returns the current application name prefix.
     *
     * @return the application name, never {@code null}
     */
    public String getAppName() {
        return appName;
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
    // Value encoding / decoding
    // -----------------------------------------------------------------------

    /**
     * A parsed consent value from Redis — the status string plus the source
     * tag indicating how the entry was created.
     *
     * @param status {@value #GRANTED} or {@value #REVOKED}
     * @param source {@value #SOURCE_FIELD} or {@value #SOURCE_CATEGORY}
     */
    public record ConsentValue(String status, String source) {}

    /**
     * Parses a raw Redis value into a {@link ConsentValue}.
     *
     * <p>Accepted formats:
     * <ul>
     *   <li>{@code "GRANTED|FIELD"}</li>
     *   <li>{@code "REVOKED|FIELD"}</li>
     *   <li>{@code "GRANTED|CATEGORY"}</li>
     *   <li>{@code "REVOKED|CATEGORY"}</li>
     * </ul>
     *
     * @param raw the stored value, may be {@code null}
     * @return the parsed value, or {@code null} if {@code raw} is {@code null}
     */
    static ConsentValue parseValue(String raw) {
        if (raw == null) {
            return null;
        }
        int pipe = raw.indexOf(DELIMITER);
        if (pipe < 0) {
            // Defensive: bare status without source tag — treat as FIELD
            return new ConsentValue(raw, SOURCE_FIELD);
        }
        return new ConsentValue(
            raw.substring(0, pipe),
            raw.substring(pipe + 1)
        );
    }

    /**
     * Encodes a status and source into the stored value format.
     *
     * @param status {@value #GRANTED} or {@value #REVOKED}
     * @param source {@value #SOURCE_FIELD} or {@value #SOURCE_CATEGORY}
     * @return the encoded value string (e.g. {@code "GRANTED|FIELD"})
     */
    static String encodeValue(String status, String source) {
        return status + DELIMITER + source;
    }

    // -----------------------------------------------------------------------
    // Key helpers
    // -----------------------------------------------------------------------

    /**
     * Computes the key prefix for all Redis keys, incorporating the
     * current tenant context when present.
     *
     * <p>When {@link TenantContext#get()} returns a non-null tenant ID
     * the prefix is {@code {appName}:{tenantId}}. When no tenant context
     * is active the prefix is just {@code {appName}} — backward compatible
     * with existing single-tenant keys.
     *
     * @return the key prefix, never {@code null}
     */
    String keyPrefix() {
        String tenant = TenantContext.get();
        return tenant != null ? appName + ":" + tenant : appName;
    }

    /**
     * Builds the Redis key for a specific consent entry using a
     * pre-qualified field string.
     *
     * @param subject        the subject identifier
     * @param qualifiedField the qualified field ({@code entity/field})
     * @param purpose        the purpose name
     * @return {@code {prefix}:consent:{subject}:{qualifiedField}:{purpose}}
     *         where {@code {prefix}} is {@link #keyPrefix()}
     */
    String consentKey(String subject, String qualifiedField, String purpose) {
        return (
            keyPrefix() +
            ":consent:" +
            subject +
            ":" +
            qualifiedField +
            ":" +
            purpose
        );
    }

    /**
     * Builds the Redis key for a specific consent entry.
     *
     * @param subject the subject identifier
     * @param entity  the entity name, or {@code null} for the default entity
     * @param field   the field name
     * @param purpose the purpose name
     * @return {@code {prefix}:consent:{subject}:{entity}/{field}:{purpose}}
     *         where {@code {prefix}} is {@link #keyPrefix()}
     */
    String consentKey(
        String subject,
        String entity,
        String field,
        String purpose
    ) {
        return consentKey(subject, qualifyField(entity, field), purpose);
    }

    /**
     * Returns the SCAN pattern that matches all consent keys for a subject
     * within the current tenant context.
     *
     * @param subject the subject identifier
     * @return the glob pattern
     */
    private String subjectPattern(String subject) {
        return keyPrefix() + ":consent:" + subject + ":*";
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
        jedisProvider
            .get()
            .set(
                consentKey(subject, entity, field, purpose),
                encodeValue(granted ? GRANTED : REVOKED, source)
            );
    }

    /**
     * Stores a consent decision with an expiration.
     *
     * <p>The TTL is computed as the number of seconds between {@code now}
     * and {@code expiresAt}. If the computed TTL is zero or negative the
     * write is silently skipped (the entry is already expired).
     *
     * @param subject   the subject identifier
     * @param entity    the entity name, or {@code null}
     * @param field     the field name
     * @param purpose   the purpose name
     * @param granted   {@code true} to grant, {@code false} to revoke
     * @param source    {@value #SOURCE_FIELD} or {@value #SOURCE_CATEGORY}
     * @param expiresAt when this consent expires, or {@code null} for no
     *                  expiry (delegates to the no-TTL overload)
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
        if (expiresAt == null) {
            setConsent(subject, entity, field, purpose, granted, source);
            return;
        }
        long ttlSeconds = Duration.between(
            Instant.now(),
            expiresAt
        ).getSeconds();
        if (ttlSeconds <= 0) {
            return; // already expired — nothing to write
        }
        jedisProvider
            .get()
            .setex(
                consentKey(subject, entity, field, purpose),
                ttlSeconds,
                encodeValue(granted ? GRANTED : REVOKED, source)
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
     * Stores a field-level consent decision with an expiration.
     *
     * <p>Convenience wrapper that sets source to {@value #SOURCE_FIELD}.
     *
     * @param subject   the subject identifier
     * @param entity    the entity name, or {@code null}
     * @param field     the field name
     * @param purpose   the purpose name
     * @param granted   {@code true} to grant, {@code false} to revoke
     * @param expiresAt when this consent expires, or {@code null} for no expiry
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
     * <strong>qualified</strong> field names.
     *
     * <p>For each qualified field in {@code qualifiedFields}, a key is
     * written with source {@value #SOURCE_CATEGORY}. This is the write-time
     * fan-out that pre-materializes category policies into individual field
     * keys so that the read path ({@link #evaluateScopes}) needs only a flat
     * {@code MGET}.
     *
     * <p><strong>Field-level precedence:</strong> fields that already have
     * an explicit {@value #SOURCE_FIELD} entry are skipped — a category
     * fan-out never overwrites a direct field-level policy. Fields with
     * no existing entry or with an existing {@value #SOURCE_CATEGORY}
     * entry are written normally.
     *
     * <p>The implementation performs one {@code MGET} to check existing
     * entries, then a pipelined {@code SET} for each writeable field —
     * two Redis round trips total.
     *
     * @param subject         the subject identifier
     * @param purpose         the purpose name
     * @param granted         {@code true} to grant, {@code false} to revoke
     * @param qualifiedFields the set of qualified field names
     *                        ({@code entity/field}) to write entries for —
     *                        typically obtained from
     *                        {@link FieldCategoryMappingRedisRepository#getFieldsForCategory}
     */
    public void setCategoryConsentForFields(
        String subject,
        String purpose,
        boolean granted,
        Set<String> qualifiedFields
    ) {
        if (qualifiedFields == null || qualifiedFields.isEmpty()) {
            return;
        }
        Set<String> writeable = filterFieldSourcedEntries(
            subject,
            purpose,
            qualifiedFields
        );
        if (writeable.isEmpty()) {
            return;
        }
        String value = encodeValue(
            granted ? GRANTED : REVOKED,
            SOURCE_CATEGORY
        );
        try (Pipeline pipeline = jedisProvider.get().pipelined()) {
            for (String qf : writeable) {
                pipeline.set(consentKey(subject, qf, purpose), value);
            }
            pipeline.sync();
        }
    }

    /**
     * Materializes a category-level consent decision across a set of
     * qualified fields with an expiration.
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
     * @param expiresAt       when these entries expire, or {@code null} for no
     *                        expiry (delegates to the no-TTL overload)
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
        if (expiresAt == null) {
            setCategoryConsentForFields(
                subject,
                purpose,
                granted,
                qualifiedFields
            );
            return;
        }
        long ttlSeconds = Duration.between(
            Instant.now(),
            expiresAt
        ).getSeconds();
        if (ttlSeconds <= 0) {
            return; // already expired
        }
        Set<String> writeable = filterFieldSourcedEntries(
            subject,
            purpose,
            qualifiedFields
        );
        if (writeable.isEmpty()) {
            return;
        }
        String value = encodeValue(
            granted ? GRANTED : REVOKED,
            SOURCE_CATEGORY
        );
        try (Pipeline pipeline = jedisProvider.get().pipelined()) {
            for (String qf : writeable) {
                pipeline.setex(
                    consentKey(subject, qf, purpose),
                    ttlSeconds,
                    value
                );
            }
            pipeline.sync();
        }
    }

    // -----------------------------------------------------------------------
    // Read
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
        ConsentValue cv = parseValue(
            jedisProvider.get().get(consentKey(subject, entity, field, purpose))
        );
        return cv != null ? Optional.of(cv.status()) : Optional.empty();
    }

    /**
     * Reads the full consent entry for a single field (status + source).
     *
     * @param subject the subject identifier
     * @param entity  the entity name, or {@code null}
     * @param field   the field name
     * @param purpose the purpose name
     * @return the parsed entry, or empty if no key exists
     */
    public Optional<ConsentValue> getConsentEntry(
        String subject,
        String entity,
        String field,
        String purpose
    ) {
        return Optional.ofNullable(
            parseValue(
                jedisProvider
                    .get()
                    .get(consentKey(subject, entity, field, purpose))
            )
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
        jedisProvider.get().del(consentKey(subject, entity, field, purpose));
    }

    // -----------------------------------------------------------------------
    // Batch evaluation — the hot path
    // -----------------------------------------------------------------------

    /**
     * Evaluates consent for a batch of scopes against a subject.
     *
     * <p>This is the primary entry point for consent evaluation. It builds
     * one Redis key per requested scope (including the scope's
     * {@link ConsentScope#entityName()}) and performs a single {@code MGET}
     * to fetch all values in one round trip. Each response is mapped
     * positionally back to its scope.
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

        // Build keys in deterministic order
        List<ConsentScope> scopeList = new ArrayList<>(accessRequests);
        String[] keys = new String[scopeList.size()];
        for (int i = 0; i < scopeList.size(); i++) {
            ConsentScope s = scopeList.get(i);
            keys[i] = consentKey(
                subject,
                s.entityName(),
                s.fieldName(),
                s.purposeName()
            );
        }

        // --- single Redis round trip ----------------------------------------
        List<String> values = jedisProvider.get().mget(keys);

        // --- pure mapping from here on --------------------------------------
        Map<ConsentScope, DecisionDetail> result = new LinkedHashMap<>();
        for (int i = 0; i < scopeList.size(); i++) {
            ConsentScope scope = scopeList.get(i);
            ConsentValue cv = parseValue(values.get(i));

            String effectiveStatus;
            String resolvedBy;

            if (cv != null) {
                effectiveStatus = cv.status();
                resolvedBy = cv.source();
            } else {
                effectiveStatus = null;
                resolvedBy = DecisionDetail.RESOLVED_BY_NONE;
            }

            result.put(
                scope,
                new DecisionDetail(scope, effectiveStatus, resolvedBy, null)
            );
        }

        return result;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Filters out qualified fields that already have an explicit
     * {@value #SOURCE_FIELD} entry in Redis.
     *
     * <p>Performs a single {@code MGET} on all candidate keys and returns
     * only those fields whose current entry is either absent or has
     * {@value #SOURCE_CATEGORY} source — i.e. the fields that are safe
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
        List<String> fieldList = new ArrayList<>(qualifiedFields);
        String[] keys = new String[fieldList.size()];
        for (int i = 0; i < fieldList.size(); i++) {
            keys[i] = consentKey(subject, fieldList.get(i), purpose);
        }

        List<String> values = jedisProvider.get().mget(keys);

        Set<String> writeable = new LinkedHashSet<>();
        for (int i = 0; i < fieldList.size(); i++) {
            ConsentValue cv = parseValue(values.get(i));
            if (cv == null || !SOURCE_FIELD.equals(cv.source())) {
                writeable.add(fieldList.get(i));
            }
        }
        return writeable;
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
        return jedisProvider
            .get()
            .exists(consentKey(subject, entity, field, purpose));
    }

    /**
     * Removes all consent keys for a subject by scanning for the
     * {@code {appName}:consent:{subject}:*} pattern.
     *
     * <p>Uses {@code SCAN} to avoid blocking the server on large keyspaces,
     * then pipelines the resulting {@code DEL} commands.
     *
     * <p><strong>Note:</strong> this is intended for administrative /
     * housekeeping use and test cleanup — not for the hot evaluation path.
     *
     * @param subject the subject identifier
     * @return the number of keys removed
     */
    public long removeAllConsent(String subject) {
        String pattern = subjectPattern(subject);
        String cursor = "0";
        long removed = 0;
        var scanParams = new redis.clients.jedis.params.ScanParams()
            .match(pattern)
            .count(100);

        JedisPooled jedis = jedisProvider.get();
        do {
            var scanResult = jedis.scan(cursor, scanParams);
            List<String> keys = scanResult.getResult();
            if (!keys.isEmpty()) {
                try (Pipeline pipeline = jedis.pipelined()) {
                    for (String key : keys) {
                        pipeline.del(key);
                    }
                    pipeline.sync();
                }
                removed += keys.size();
            }
            cursor = scanResult.getCursor();
        } while (!"0".equals(cursor));

        return removed;
    }

    /**
     * Deletes all cached consent keys for a subject (GDPR Article 17 erasure).
     *
     * <p>Discovers all Redis keys matching the pattern
     * {@code {keyPrefix}:consent:{subject}:*} and deletes them atomically.
     * This is an idempotent operation — calling it multiple times for the
     * same subject is safe (subsequent calls are no-ops if no keys exist).
     *
     * @param subject the data subject identifier
     * @return the total number of keys deleted across all purposes/fields
     */
    public long deleteAllCachedKeysForSubject(String subject) {
        // Build the pattern to match all consent keys for this subject
        // Pattern: {keyPrefix}:consent:{subject}:*
        String pattern = keyPrefix() + ":consent:" + subject + ":*";

        long removed = 0;
        String cursor = "0";
        var scanParams = new redis.clients.jedis.params.ScanParams()
            .match(pattern)
            .count(100);

        JedisPooled jedis = jedisProvider.get();
        do {
            var scanResult = jedis.scan(cursor, scanParams);
            List<String> keys = scanResult.getResult();
            if (!keys.isEmpty()) {
                try (Pipeline pipeline = jedis.pipelined()) {
                    for (String key : keys) {
                        pipeline.del(key);
                    }
                    pipeline.sync();
                }
                removed += keys.size();
            }
            cursor = scanResult.getCursor();
        } while (!"0".equals(cursor));

        return removed;
    }

    /**
     * Counts the number of cached consent keys for a subject without deleting them.
     *
     * <p>Discovers all Redis keys matching the pattern
     * {@code {keyPrefix}:consent:{subject}:*} and returns the count.
     * This is useful for DSAR data export (Article 20 — data portability).
     *
     * @param subject the data subject identifier
     * @return the total number of cached keys for this subject
     */
    public long countCachedKeysForSubject(String subject) {
        // Build the pattern to match all consent keys for this subject
        String pattern = keyPrefix() + ":consent:" + subject + ":*";

        long count = 0;
        String cursor = "0";
        var scanParams = new redis.clients.jedis.params.ScanParams()
            .match(pattern)
            .count(100);

        JedisPooled jedis = jedisProvider.get();
        do {
            var scanResult = jedis.scan(cursor, scanParams);
            List<String> keys = scanResult.getResult();
            count += keys.size();
            cursor = scanResult.getCursor();
        } while (!"0".equals(cursor));

        return count;
    }

    /**
     * Returns the remaining TTL in seconds for a consent entry, or
     * {@code -1} if the key has no TTL, or {@code -2} if the key does
     * not exist.
     *
     * @param subject the subject identifier
     * @param entity  the entity name, or {@code null}
     * @param field   the field name
     * @param purpose the purpose name
     * @return TTL in seconds, {@code -1} for no expiry, {@code -2} for missing
     */
    public long ttl(
        String subject,
        String entity,
        String field,
        String purpose
    ) {
        return jedisProvider
            .get()
            .ttl(consentKey(subject, entity, field, purpose));
    }
}
