package com.koostack.operio.plugin.consent.redis;

import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.DecisionDetail;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;

/**
 * Repository for consent policy storage in Redis using a
 * <strong>Hash-per-subject</strong> schema with reverse indices for O(1)
 * invalidation.
 *
 * <h2>Redis Key Schema</h2>
 * <pre>
 *   Primary data:  {tenantId}:consent:{subject}  (Hash)
 *     Hash fields: c:{category}:{purpose}       → "GRANTED" or "REVOKED"
 *                  f:{entity/field}:{purpose}    → "GRANTED" or "REVOKED"
 *
 *   Reverse indices (Sets):
 *     {tenantId}:idx:cat:{category}          → Set of "{subject}\0{purpose}"
 *     {tenantId}:idx:field:{entity/field}    → Set of "{subject}\0{purpose}"
 *     {tenantId}:idx:purpose:{purpose}       → Set of "{subject}\0c:{category}" or "{subject}\0f:{entity/field}"
 * </pre>
 *
 * <h2>Evaluation Algorithm</h2>
 * <p>Evaluation is a single {@code HMGET} call with 2 fields per scope
 * (field-level + category-level). Field-level takes precedence over
 * category-level. One Redis round trip regardless of batch size.
 *
 * <h2>Multi-Tenancy</h2>
 * <p>All keys are prefixed by {@link TenantContext#require()} — the tenant
 * must be set before any operation.
 */
public class ConsentPolicyRedisRepository {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /** Status value stored when consent is granted. */
    public static final String GRANTED = "GRANTED";

    /** Status value stored when consent is revoked. */
    public static final String REVOKED = "REVOKED";

    /** Source tag: the decision was resolved by a field-level entry. */
    public static final String SOURCE_FIELD = "FIELD";

    /** Source tag: the decision was resolved by a category-level entry. */
    public static final String SOURCE_CATEGORY = "CATEGORY";

    /** Sentinel entity name used when a field has no owning entity. */
    public static final String DEFAULT_ENTITY = "_default_";

    /**
     * @deprecated No longer used — appName is dropped from key prefixes.
     */
    @Deprecated
    public static final String DEFAULT_APP_NAME = "operio";

    /** Separator between entity and field name inside a key segment. */
    static final char ENTITY_FIELD_SEP = '/';

    /** Null byte separator used in reverse index set members. */
    static final char IDX_SEP = '\0';

    private final JedisProvider jedisProvider;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    public ConsentPolicyRedisRepository(JedisPooled jedis) {
        this(JedisProvider.shared(jedis));
    }

    public ConsentPolicyRedisRepository(JedisProvider jedisProvider) {
        if (jedisProvider == null) {
            throw new NullPointerException("JedisProvider must not be null");
        }
        this.jedisProvider = jedisProvider;
    }

    /**
     * @deprecated Use {@link #ConsentPolicyRedisRepository(JedisProvider)} —
     *             appName is no longer used in key prefixes.
     */
    @Deprecated
    public ConsentPolicyRedisRepository(JedisPooled jedis, String appName) {
        this(JedisProvider.shared(jedis));
    }

    /**
     * @deprecated Use {@link #ConsentPolicyRedisRepository(JedisProvider)} —
     *             appName is no longer used in key prefixes.
     */
    @Deprecated
    public ConsentPolicyRedisRepository(JedisProvider jedisProvider, String appName) {
        this(jedisProvider);
    }

    public JedisProvider jedisProvider() {
        return jedisProvider;
    }

    /**
     * @deprecated No-op — appName is no longer used in key prefixes.
     */
    @Deprecated
    public void setAppName(String appName) {
        // no-op
    }

    /**
     * @deprecated Returns empty string — appName is no longer used.
     */
    @Deprecated
    public String getAppName() {
        return "";
    }

    // -----------------------------------------------------------------------
    // Qualified field helpers
    // -----------------------------------------------------------------------

    /**
     * Produces a qualified field string: {@code {entity}/{fieldName}}.
     * When {@code entity} is {@code null} or blank, {@value #DEFAULT_ENTITY} is used.
     */
    public static String qualifyField(String entity, String field) {
        String e = (entity != null && !entity.isBlank()
                && !ConsentScope.WILDCARD.equals(entity))
            ? entity
            : DEFAULT_ENTITY;
        return e + ENTITY_FIELD_SEP + field;
    }

    // -----------------------------------------------------------------------
    // Key helpers
    // -----------------------------------------------------------------------

    /** Hash key for a subject's consent data. */
    String consentHashKey(String subject) {
        return TenantContext.require() + ":consent:" + subject;
    }

    /** Reverse index key for category invalidation. */
    String idxCatKey(String category) {
        return TenantContext.require() + ":idx:cat:" + category;
    }

    /** Reverse index key for field invalidation. */
    String idxFieldKey(String qualifiedField) {
        return TenantContext.require() + ":idx:field:" + qualifiedField;
    }

    /** Reverse index key for purpose invalidation. */
    String idxPurposeKey(String purpose) {
        return TenantContext.require() + ":idx:purpose:" + purpose;
    }

    // -----------------------------------------------------------------------
    // AffectedEntry — returned by reverse-index cleanup methods
    // -----------------------------------------------------------------------

    /** Returned by reverse-index cleanup methods to inform callers of what was deleted. */
    public record AffectedEntry(String subject, String purpose) {}

    // -----------------------------------------------------------------------
    // Write — category-level
    // -----------------------------------------------------------------------

    /**
     * Stores a category-level consent decision.
     * Writes one hash field {@code c:{category}:{purpose}} and updates reverse indices.
     */
    public void setCategoryConsent(String subject, String category, String purpose, boolean granted) {
        String hashKey = consentHashKey(subject);
        String hashField = "c:" + category + ":" + purpose;
        String value = granted ? GRANTED : REVOKED;

        try (Pipeline p = jedisProvider.get().pipelined()) {
            p.hset(hashKey, hashField, value);
            p.sadd(idxCatKey(category), subject + IDX_SEP + purpose);
            p.sadd(idxPurposeKey(purpose), subject + IDX_SEP + "c:" + category);
        }
    }

    // -----------------------------------------------------------------------
    // Write — field-level
    // -----------------------------------------------------------------------

    /**
     * Stores a field-level consent decision.
     * Writes one hash field {@code f:{entity/field}:{purpose}} and updates reverse indices.
     */
    public void setFieldConsent(String subject, String entity, String field, String purpose, boolean granted) {
        String hashKey = consentHashKey(subject);
        String qualifiedField = qualifyField(entity, field);
        String hashField = "f:" + qualifiedField + ":" + purpose;
        String value = granted ? GRANTED : REVOKED;

        try (Pipeline p = jedisProvider.get().pipelined()) {
            p.hset(hashKey, hashField, value);
            p.sadd(idxFieldKey(qualifiedField), subject + IDX_SEP + purpose);
            p.sadd(idxPurposeKey(purpose), subject + IDX_SEP + "f:" + qualifiedField);
        }
    }

    // -----------------------------------------------------------------------
    // Read — evaluate (2-step precedence)
    // -----------------------------------------------------------------------

    /**
     * Evaluates consent for a single scope with field-over-category precedence.
     */
    public DecisionDetail evaluate(String subject, String category, String entity, String field, String purpose) {
        String hashKey = consentHashKey(subject);
        String qualifiedField = qualifyField(entity, field);
        String fieldHashField = "f:" + qualifiedField + ":" + purpose;
        String catHashField = "c:" + category + ":" + purpose;

        List<String> values = jedisProvider.get().hmget(hashKey, fieldHashField, catHashField);

        ConsentScope scope = new ConsentScope(category, field, entity, purpose);

        // Field-level takes precedence
        if (values.get(0) != null) {
            return new DecisionDetail(scope, values.get(0), SOURCE_FIELD, null);
        }
        if (values.get(1) != null) {
            return new DecisionDetail(scope, values.get(1), SOURCE_CATEGORY, null);
        }
        return new DecisionDetail(scope, null, DecisionDetail.RESOLVED_BY_NONE, null);
    }

    // -----------------------------------------------------------------------
    // Read — batch evaluate (N scopes, same subject)
    // -----------------------------------------------------------------------

    /**
     * Evaluates consent for a batch of scopes against a subject.
     * Performs a single {@code HMGET} with 2N hash fields (f: + c: for each scope),
     * then resolves precedence per scope.
     */
    public Map<ConsentScope, DecisionDetail> evaluateScopes(
        String subject,
        Set<ConsentScope> accessRequests
    ) {
        if (accessRequests == null || accessRequests.isEmpty()) {
            return Collections.emptyMap();
        }

        String hashKey = consentHashKey(subject);
        List<ConsentScope> scopeList = new ArrayList<>(accessRequests);

        // Build 2N hash fields: f: and c: for each scope
        String[] hashFields = new String[scopeList.size() * 2];
        for (int i = 0; i < scopeList.size(); i++) {
            ConsentScope s = scopeList.get(i);
            String qualifiedField = qualifyField(s.entityName(), s.fieldName());
            hashFields[i * 2] = "f:" + qualifiedField + ":" + s.purposeName();
            hashFields[i * 2 + 1] = "c:" + s.category() + ":" + s.purposeName();
        }

        List<String> values = jedisProvider.get().hmget(hashKey, hashFields);

        Map<ConsentScope, DecisionDetail> result = new LinkedHashMap<>();
        for (int i = 0; i < scopeList.size(); i++) {
            ConsentScope scope = scopeList.get(i);
            String fieldValue = values.get(i * 2);
            String catValue = values.get(i * 2 + 1);

            String effectiveStatus;
            String resolvedBy;

            if (fieldValue != null) {
                effectiveStatus = fieldValue;
                resolvedBy = SOURCE_FIELD;
            } else if (catValue != null) {
                effectiveStatus = catValue;
                resolvedBy = SOURCE_CATEGORY;
            } else {
                effectiveStatus = null;
                resolvedBy = DecisionDetail.RESOLVED_BY_NONE;
            }

            result.put(scope, new DecisionDetail(scope, effectiveStatus, resolvedBy, null));
        }

        return result;
    }

    // -----------------------------------------------------------------------
    // Remove — all consent for subject
    // -----------------------------------------------------------------------

    /**
     * Removes all consent for a subject: reads all hash entries, cleans up
     * reverse indices, then deletes the hash.
     */
    public void removeAllConsent(String subject) {
        String hashKey = consentHashKey(subject);
        JedisPooled jedis = jedisProvider.get();

        Map<String, String> entries = jedis.hgetAll(hashKey);
        if (entries.isEmpty()) {
            return;
        }

        try (Pipeline p = jedis.pipelined()) {
            for (String hashField : entries.keySet()) {
                cleanupReverseIndex(p, subject, hashField);
            }
            p.del(hashKey);
        }
    }

    /**
     * Deletes all cached consent keys for a subject.
     * Alias for {@link #removeAllConsent(String)} returning the count.
     */
    public long deleteAllCachedKeysForSubject(String subject) {
        String hashKey = consentHashKey(subject);
        JedisPooled jedis = jedisProvider.get();

        Map<String, String> entries = jedis.hgetAll(hashKey);
        if (entries.isEmpty()) {
            return 0;
        }

        try (Pipeline p = jedis.pipelined()) {
            for (String hashField : entries.keySet()) {
                cleanupReverseIndex(p, subject, hashField);
            }
            p.del(hashKey);
        }

        return entries.size();
    }

    /**
     * Counts consent entries for a subject.
     */
    public long countCachedKeysForSubject(String subject) {
        return jedisProvider.get().hlen(consentHashKey(subject));
    }

    // -----------------------------------------------------------------------
    // Reverse-index cleanup — by category
    // -----------------------------------------------------------------------

    /**
     * Deletes all category-level consent entries for the given category across all subjects.
     * Returns the list of affected (subject, purpose) pairs.
     */
    public List<AffectedEntry> deleteByCategory(String category) {
        String idxKey = idxCatKey(category);
        JedisPooled jedis = jedisProvider.get();

        Set<String> members = jedis.smembers(idxKey);
        if (members.isEmpty()) {
            return Collections.emptyList();
        }

        List<AffectedEntry> affected = new ArrayList<>();
        try (Pipeline p = jedis.pipelined()) {
            for (String member : members) {
                int sep = member.indexOf(IDX_SEP);
                String subject = member.substring(0, sep);
                String purpose = member.substring(sep + 1);

                String hashField = "c:" + category + ":" + purpose;
                p.hdel(consentHashKey(subject), hashField);
                p.srem(idxPurposeKey(purpose), subject + IDX_SEP + "c:" + category);

                affected.add(new AffectedEntry(subject, purpose));
            }
            p.del(idxKey);
        }

        return affected;
    }

    // -----------------------------------------------------------------------
    // Reverse-index cleanup — by field
    // -----------------------------------------------------------------------

    /**
     * Deletes all field-level consent entries for the given qualified field across all subjects.
     * Returns the list of affected (subject, purpose) pairs.
     */
    public List<AffectedEntry> deleteByField(String qualifiedField) {
        String idxKey = idxFieldKey(qualifiedField);
        JedisPooled jedis = jedisProvider.get();

        Set<String> members = jedis.smembers(idxKey);
        if (members.isEmpty()) {
            return Collections.emptyList();
        }

        List<AffectedEntry> affected = new ArrayList<>();
        try (Pipeline p = jedis.pipelined()) {
            for (String member : members) {
                int sep = member.indexOf(IDX_SEP);
                String subject = member.substring(0, sep);
                String purpose = member.substring(sep + 1);

                String hashField = "f:" + qualifiedField + ":" + purpose;
                p.hdel(consentHashKey(subject), hashField);
                p.srem(idxPurposeKey(purpose), subject + IDX_SEP + "f:" + qualifiedField);

                affected.add(new AffectedEntry(subject, purpose));
            }
            p.del(idxKey);
        }

        return affected;
    }

    // -----------------------------------------------------------------------
    // Reverse-index cleanup — by purpose
    // -----------------------------------------------------------------------

    /**
     * Deletes all consent entries for the given purpose across all subjects.
     * Returns the list of affected (subject, purpose) pairs.
     */
    public List<AffectedEntry> deleteByPurpose(String purpose) {
        String idxKey = idxPurposeKey(purpose);
        JedisPooled jedis = jedisProvider.get();

        Set<String> members = jedis.smembers(idxKey);
        if (members.isEmpty()) {
            return Collections.emptyList();
        }

        List<AffectedEntry> affected = new ArrayList<>();
        try (Pipeline p = jedis.pipelined()) {
            for (String member : members) {
                int sep = member.indexOf(IDX_SEP);
                String subject = member.substring(0, sep);
                String discriminator = member.substring(sep + 1); // "c:{cat}" or "f:{entity/field}"

                String hashField = discriminator + ":" + purpose;
                p.hdel(consentHashKey(subject), hashField);

                // Clean up the type-specific reverse index
                if (discriminator.startsWith("c:")) {
                    String cat = discriminator.substring(2);
                    p.srem(idxCatKey(cat), subject + IDX_SEP + purpose);
                } else if (discriminator.startsWith("f:")) {
                    String qf = discriminator.substring(2);
                    p.srem(idxFieldKey(qf), subject + IDX_SEP + purpose);
                }

                affected.add(new AffectedEntry(subject, purpose));
            }
            p.del(idxKey);
        }

        return affected;
    }

    // -----------------------------------------------------------------------
    // Internal — reverse index cleanup helper
    // -----------------------------------------------------------------------

    /**
     * Parses a hash field and issues SREM commands to clean up reverse indices.
     * Hash field format: {@code {prefix}:{discriminator}:{purpose}} where prefix is "c" or "f".
     */
    private void cleanupReverseIndex(Pipeline p, String subject, String hashField) {
        // Format: {prefix}:{discriminator}:{purpose}
        // The second colon separates discriminator from purpose (purpose may contain colons).
        // c:PII:marketing → ["c", "PII", "marketing"]
        // f:User/email:gdpr:article6 → ["f", "User/email", "gdpr:article6"]
        int firstColon = hashField.indexOf(':');
        if (firstColon < 0) { return; }
        int secondColon = hashField.indexOf(':', firstColon + 1);
        if (secondColon < 0) { return; }

        String prefix = hashField.substring(0, firstColon);
        String discriminator = hashField.substring(firstColon + 1, secondColon);
        String purpose = hashField.substring(secondColon + 1);

        if ("c".equals(prefix)) {
            p.srem(idxCatKey(discriminator), subject + IDX_SEP + purpose);
            p.srem(idxPurposeKey(purpose), subject + IDX_SEP + "c:" + discriminator);
        } else if ("f".equals(prefix)) {
            p.srem(idxFieldKey(discriminator), subject + IDX_SEP + purpose);
            p.srem(idxPurposeKey(purpose), subject + IDX_SEP + "f:" + discriminator);
        }
    }
}
