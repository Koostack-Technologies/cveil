package com.koostack.operio.plugin.consent.redis;

import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.Models.DecisionDetail;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import redis.clients.jedis.JedisPooled;

/**
 * Repository for consent policy storage in Redis, optimized for batch
 * evaluation of consent decisions.
 *
 * <h2>Redis Key Schema</h2>
 * <p>All consent for a single subject lives in <strong>one Redis hash</strong>:
 * <pre>
 *   Key:   operio:consent:{subject}
 *   Fields:
 *     f:{fieldName}:{purposeName}    → GRANTED | REVOKED | GRANTED|{epochSec} | REVOKED|{epochSec}
 *     c:{categoryName}:{purposeName} → GRANTED | REVOKED | GRANTED|{epochSec} | REVOKED|{epochSec}
 * </pre>
 *
 * <p>Field-level entries use the {@code f:} prefix; category-level entries
 * use the {@code c:} prefix. Values may optionally include a pipe-delimited
 * expiration timestamp as epoch seconds (e.g. {@code GRANTED|1735689599}).
 * Values without a pipe have no expiration.
 *
 * <h2>Evaluation Algorithm</h2>
 * <p>Evaluation is a two-phase operation:
 * <ol>
 *   <li><strong>Read</strong> — a single {@code HGETALL} fetches the entire
 *       subject hash into a local {@code Map}. This is the only network call.</li>
 *   <li><strong>Resolve</strong> — pure in-memory computation using
 *       <strong>most-specific-wins</strong> precedence. For each
 *       {@link ConsentScope}:
 *       <ol>
 *         <li>Check field-level: {@code f:{fieldName}:{purposeName}}.
 *             If a non-expired field-level entry exists ({@code GRANTED} or
 *             {@code REVOKED}), it is the effective status — done.
 *             Field-level is the most specific and always takes
 *             precedence over category-level.</li>
 *         <li>Otherwise fall back to category-level:
 *             {@code c:{category}:{purposeName}}. If a non-expired
 *             category-level entry exists, it is the effective status.</li>
 *         <li>If neither level has a non-expired record, the scope is denied
 *             (effective status is {@code null}).</li>
 *       </ol>
 *   </li>
 * </ol>
 * <p>Expired entries are treated as non-existent — they are silently skipped
 * during resolution. Regardless of how many scopes are requested, evaluation
 * always costs exactly <strong>one Redis round trip</strong>.
 */
public class ConsentPolicyRedisRepository {

    /** Status value stored when consent is granted. */
    public static final String GRANTED = "GRANTED";

    /** Status value stored when consent is revoked. */
    public static final String REVOKED = "REVOKED";

    /** Delimiter between status and epoch-seconds expiry in stored values. */
    static final char EXPIRY_DELIMITER = '|';

    static final String KEY_PREFIX = "operio:consent:";
    static final String FIELD_PREFIX = "f:";
    static final String CATEGORY_PREFIX = "c:";

    private final JedisPooled jedis;

    public ConsentPolicyRedisRepository(JedisPooled jedis) {
        this.jedis = jedis;
    }

    // -----------------------------------------------------------------------
    // Value encoding / decoding
    // -----------------------------------------------------------------------

    /**
     * A parsed consent value from Redis — the raw status string plus an
     * optional expiration instant.
     *
     * @param status    {@value #GRANTED} or {@value #REVOKED}
     * @param expiresAt when this entry expires, or {@code null} for no expiry
     */
    public record ConsentEntry(String status, Instant expiresAt) {
        /** Returns {@code true} if this entry has expired relative to {@code now}. */
        public boolean isExpired(Instant now) {
            return expiresAt != null && now.isAfter(expiresAt);
        }
    }

    /**
     * Parses a raw Redis value into a {@link ConsentEntry}.
     *
     * <p>Accepted formats:
     * <ul>
     *   <li>{@code "GRANTED"} — no expiry</li>
     *   <li>{@code "REVOKED"} — no expiry</li>
     *   <li>{@code "GRANTED|1735689599"} — expires at the given epoch second</li>
     *   <li>{@code "REVOKED|1735689599"} — expires at the given epoch second</li>
     * </ul>
     *
     * @param raw the stored value, may be {@code null}
     * @return the parsed entry, or {@code null} if {@code raw} is {@code null}
     */
    static ConsentEntry parseValue(String raw) {
        if (raw == null) {
            return null;
        }
        int pipe = raw.indexOf(EXPIRY_DELIMITER);
        if (pipe < 0) {
            return new ConsentEntry(raw, null);
        }
        String status = raw.substring(0, pipe);
        long epochSec = Long.parseLong(raw.substring(pipe + 1));
        return new ConsentEntry(status, Instant.ofEpochSecond(epochSec));
    }

    /**
     * Encodes a status and optional expiry into the stored value format.
     *
     * @param status    {@value #GRANTED} or {@value #REVOKED}
     * @param expiresAt optional expiration instant, or {@code null} for no expiry
     * @return the encoded value string
     */
    static String encodeValue(String status, Instant expiresAt) {
        if (expiresAt == null) {
            return status;
        }
        return status + EXPIRY_DELIMITER + expiresAt.getEpochSecond();
    }

    // -----------------------------------------------------------------------
    // Write — field-level
    // -----------------------------------------------------------------------

    /**
     * Stores a field-level consent decision with no expiration.
     *
     * @param subject     the subject identifier
     * @param fieldName   the field name
     * @param purposeName the purpose name
     * @param granted     {@code true} to grant, {@code false} to revoke
     */
    public void setFieldConsent(
        String subject,
        String fieldName,
        String purposeName,
        boolean granted
    ) {
        setFieldConsent(subject, fieldName, purposeName, granted, null);
    }

    /**
     * Stores a field-level consent decision with an optional expiration.
     *
     * @param subject     the subject identifier
     * @param fieldName   the field name
     * @param purposeName the purpose name
     * @param granted     {@code true} to grant, {@code false} to revoke
     * @param expiresAt   when this consent expires, or {@code null} for no expiry
     */
    public void setFieldConsent(
        String subject,
        String fieldName,
        String purposeName,
        boolean granted,
        Instant expiresAt
    ) {
        jedis.hset(
            subjectKey(subject),
            fieldHashKey(fieldName, purposeName),
            encodeValue(granted ? GRANTED : REVOKED, expiresAt)
        );
    }

    /**
     * Removes a field-level consent entry entirely.
     *
     * @param subject     the subject identifier
     * @param fieldName   the field name
     * @param purposeName the purpose name
     */
    public void removeFieldConsent(
        String subject,
        String fieldName,
        String purposeName
    ) {
        jedis.hdel(subjectKey(subject), fieldHashKey(fieldName, purposeName));
    }

    /**
     * Reads a single field-level consent status (raw value).
     *
     * @param subject     the subject identifier
     * @param fieldName   the field name
     * @param purposeName the purpose name
     * @return the raw value string, or empty if no entry exists
     */
    public Optional<String> getFieldConsent(
        String subject,
        String fieldName,
        String purposeName
    ) {
        return Optional.ofNullable(
            jedis.hget(
                subjectKey(subject),
                fieldHashKey(fieldName, purposeName)
            )
        );
    }

    /**
     * Reads a single field-level consent entry (parsed).
     *
     * @param subject     the subject identifier
     * @param fieldName   the field name
     * @param purposeName the purpose name
     * @return the parsed entry, or empty if no entry exists
     */
    public Optional<ConsentEntry> getFieldConsentEntry(
        String subject,
        String fieldName,
        String purposeName
    ) {
        return getFieldConsent(subject, fieldName, purposeName).map(
            ConsentPolicyRedisRepository::parseValue
        );
    }

    // -----------------------------------------------------------------------
    // Write — category-level
    // -----------------------------------------------------------------------

    /**
     * Stores a category-level consent decision with no expiration.
     *
     * @param subject      the subject identifier
     * @param categoryName the category name
     * @param purposeName  the purpose name
     * @param granted      {@code true} to grant, {@code false} to revoke
     */
    public void setCategoryConsent(
        String subject,
        String categoryName,
        String purposeName,
        boolean granted
    ) {
        setCategoryConsent(subject, categoryName, purposeName, granted, null);
    }

    /**
     * Stores a category-level consent decision with an optional expiration.
     *
     * @param subject      the subject identifier
     * @param categoryName the category name
     * @param purposeName  the purpose name
     * @param granted      {@code true} to grant, {@code false} to revoke
     * @param expiresAt    when this consent expires, or {@code null} for no expiry
     */
    public void setCategoryConsent(
        String subject,
        String categoryName,
        String purposeName,
        boolean granted,
        Instant expiresAt
    ) {
        jedis.hset(
            subjectKey(subject),
            categoryHashKey(categoryName, purposeName),
            encodeValue(granted ? GRANTED : REVOKED, expiresAt)
        );
    }

    /**
     * Removes a category-level consent entry entirely.
     *
     * @param subject      the subject identifier
     * @param categoryName the category name
     * @param purposeName  the purpose name
     */
    public void removeCategoryConsent(
        String subject,
        String categoryName,
        String purposeName
    ) {
        jedis.hdel(
            subjectKey(subject),
            categoryHashKey(categoryName, purposeName)
        );
    }

    /**
     * Reads a single category-level consent status (raw value).
     *
     * @param subject      the subject identifier
     * @param categoryName the category name
     * @param purposeName  the purpose name
     * @return the raw value string, or empty if no entry exists
     */
    public Optional<String> getCategoryConsent(
        String subject,
        String categoryName,
        String purposeName
    ) {
        return Optional.ofNullable(
            jedis.hget(
                subjectKey(subject),
                categoryHashKey(categoryName, purposeName)
            )
        );
    }

    /**
     * Reads a single category-level consent entry (parsed).
     *
     * @param subject      the subject identifier
     * @param categoryName the category name
     * @param purposeName  the purpose name
     * @return the parsed entry, or empty if no entry exists
     */
    public Optional<ConsentEntry> getCategoryConsentEntry(
        String subject,
        String categoryName,
        String purposeName
    ) {
        return getCategoryConsent(subject, categoryName, purposeName).map(
            ConsentPolicyRedisRepository::parseValue
        );
    }

    // -----------------------------------------------------------------------
    // Batch write
    // -----------------------------------------------------------------------

    /**
     * Stores multiple field-level consent decisions in a single round trip.
     * Values should be pre-encoded via {@link #encodeValue(String, Instant)}
     * or plain {@value #GRANTED}/{@value #REVOKED} strings.
     *
     * @param subject the subject identifier
     * @param entries map of {@code fieldName:purposeName} → granted boolean
     */
    public void setFieldConsentBatch(
        String subject,
        Map<String, Boolean> entries
    ) {
        String key = subjectKey(subject);
        Map<String, String> hash = new HashMap<>();
        for (var entry : entries.entrySet()) {
            hash.put(
                FIELD_PREFIX + entry.getKey(),
                entry.getValue() ? GRANTED : REVOKED
            );
        }
        if (!hash.isEmpty()) {
            jedis.hset(key, hash);
        }
    }

    /**
     * Stores multiple category-level consent decisions in a single round trip.
     *
     * @param subject the subject identifier
     * @param entries map of {@code categoryName:purposeName} → granted boolean
     */
    public void setCategoryConsentBatch(
        String subject,
        Map<String, Boolean> entries
    ) {
        String key = subjectKey(subject);
        Map<String, String> hash = new HashMap<>();
        for (var entry : entries.entrySet()) {
            hash.put(
                CATEGORY_PREFIX + entry.getKey(),
                entry.getValue() ? GRANTED : REVOKED
            );
        }
        if (!hash.isEmpty()) {
            jedis.hset(key, hash);
        }
    }

    // -----------------------------------------------------------------------
    // Batch evaluation — the hot path
    // -----------------------------------------------------------------------

    /**
     * Evaluates consent for a batch of scopes against a subject.
     *
     * <p>This is the primary entry point for consent evaluation. It performs
     * exactly <strong>one Redis read</strong> ({@code HGETALL}) to fetch the
     * subject's entire consent hash, then delegates to
     * {@link #resolveScopes(Map, Set, Instant)} for pure in-memory resolution.
     *
     * @param subject        the subject identifier
     * @param accessRequests the set of scopes to evaluate
     * @return a map from each requested scope to its {@link DecisionDetail}
     * @see #resolveScopes(Map, Set, Instant)
     */
    public Map<ConsentScope, DecisionDetail> evaluateScopes(
        String subject,
        Set<ConsentScope> accessRequests
    ) {
        if (accessRequests == null || accessRequests.isEmpty()) {
            return Collections.emptyMap();
        }

        // --- single Redis round trip -----------------------------------------
        Map<String, String> allConsent = jedis.hgetAll(subjectKey(subject));

        // --- pure computation from here on -----------------------------------
        return resolveScopes(allConsent, accessRequests, Instant.now());
    }

    /**
     * Resolves a set of consent scopes against a pre-fetched consent map.
     *
     * <p>This method performs <strong>zero I/O</strong>. It is a pure
     * function over the data already read from Redis, making it easy to
     * test in isolation and safe to call from any context.
     *
     * <p><strong>Most-specific-wins precedence:</strong>
     * <ol>
     *   <li>Look up the field-level entry
     *       ({@code f:{fieldName}:{purposeName}}) in the map and parse it.</li>
     *   <li>If a non-expired field-level value exists ({@value #GRANTED} or
     *       {@value #REVOKED}), it is the effective status — done.
     *       Field-level is the most specific and always takes
     *       precedence over category-level.</li>
     *   <li>Otherwise look up the category-level entry
     *       ({@code c:{category}:{purposeName}}) and parse it.</li>
     *   <li>If a non-expired category-level value exists, it is the effective
     *       status.</li>
     *   <li>Otherwise there is no consent record and the effective
     *       status is {@code null}.</li>
     * </ol>
     *
     * <p>Expired entries are treated as non-existent and silently skipped.
     *
     * @param allConsent     the full hash contents for a subject, as returned
     *                       by {@link #getAllConsent(String)} or
     *                       {@code HGETALL}. Keys are prefixed hash fields
     *                       (e.g. {@code f:ssn:marketing},
     *                       {@code c:PII:marketing}). Values may include
     *                       optional pipe-delimited expiry timestamps.
     * @param accessRequests the set of scopes to resolve
     * @param now            the current instant used for expiry checks
     * @return a map from each requested scope to its {@link DecisionDetail}
     */
    public static Map<ConsentScope, DecisionDetail> resolveScopes(
        Map<String, String> allConsent,
        Set<ConsentScope> accessRequests,
        Instant now
    ) {
        if (accessRequests == null || accessRequests.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<ConsentScope, DecisionDetail> resolved = new LinkedHashMap<>();

        for (ConsentScope scope : accessRequests) {
            String fieldKey = fieldHashKey(
                scope.fieldName(),
                scope.purposeName()
            );
            String categoryKey = categoryHashKey(
                scope.category(),
                scope.purposeName()
            );

            ConsentEntry fieldEntry = parseValue(allConsent.get(fieldKey));
            ConsentEntry categoryEntry = parseValue(
                allConsent.get(categoryKey)
            );

            // Treat expired entries as non-existent
            if (fieldEntry != null && fieldEntry.isExpired(now)) {
                fieldEntry = null;
            }
            if (categoryEntry != null && categoryEntry.isExpired(now)) {
                categoryEntry = null;
            }

            // Most-specific-wins: field-level always takes precedence
            // over category-level, regardless of GRANTED/REVOKED.
            String effectiveStatus;
            String resolvedBy;
            Instant expiresAt;

            if (fieldEntry != null) {
                effectiveStatus = fieldEntry.status();
                resolvedBy = DecisionDetail.RESOLVED_BY_FIELD;
                expiresAt = fieldEntry.expiresAt();
            } else if (categoryEntry != null) {
                effectiveStatus = categoryEntry.status();
                resolvedBy = DecisionDetail.RESOLVED_BY_CATEGORY;
                expiresAt = categoryEntry.expiresAt();
            } else {
                effectiveStatus = null;
                resolvedBy = DecisionDetail.RESOLVED_BY_NONE;
                expiresAt = null;
            }

            resolved.put(
                scope,
                new DecisionDetail(
                    scope,
                    effectiveStatus,
                    resolvedBy,
                    expiresAt
                )
            );
        }

        return resolved;
    }

    // -----------------------------------------------------------------------
    // Housekeeping
    // -----------------------------------------------------------------------

    /**
     * Returns the entire consent hash for a subject.
     *
     * @param subject the subject identifier
     * @return all hash field → value entries, possibly empty
     */
    public Map<String, String> getAllConsent(String subject) {
        return jedis.hgetAll(subjectKey(subject));
    }

    /**
     * Deletes all consent data for a subject.
     *
     * @param subject the subject identifier
     * @return {@code true} if the key existed and was removed
     */
    public boolean removeAllConsent(String subject) {
        return jedis.del(subjectKey(subject)) > 0;
    }

    /**
     * Checks whether any consent data exists for a subject.
     *
     * @param subject the subject identifier
     * @return {@code true} if the subject's consent hash exists
     */
    public boolean exists(String subject) {
        return jedis.exists(subjectKey(subject));
    }

    // -----------------------------------------------------------------------
    // Key helpers
    // -----------------------------------------------------------------------

    static String subjectKey(String subject) {
        return KEY_PREFIX + subject;
    }

    static String fieldHashKey(String fieldName, String purposeName) {
        return FIELD_PREFIX + fieldName + ":" + purposeName;
    }

    static String categoryHashKey(String categoryName, String purposeName) {
        return CATEGORY_PREFIX + categoryName + ":" + purposeName;
    }
}
