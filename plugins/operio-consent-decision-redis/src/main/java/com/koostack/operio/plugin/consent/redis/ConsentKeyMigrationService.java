package com.koostack.operio.plugin.consent.redis;

import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

/**
 * One-time migration utility that converts old flat consent keys to the new
 * Hash-per-subject schema introduced in Phase 1.
 *
 * <h2>Old Key Schema (flat)</h2>
 * <pre>
 *   Key:   {appName}:{tenantId}:consent:{subject}:{entity/field}:{purpose}
 *   Value: STATUS|SOURCE  (e.g., "GRANTED|FIELD", "REVOKED|CATEGORY")
 * </pre>
 *
 * <h2>New Key Schema (Hash)</h2>
 * <pre>
 *   Key:   {tenantId}:consent:{subject}
 *   Field: f:{entity/field}:{purpose} → "GRANTED" or "REVOKED"
 * </pre>
 *
 * <h2>Migration Strategy (Option B)</h2>
 * <p>Only {@code SOURCE_FIELD} entries are migrated. {@code SOURCE_CATEGORY}
 * entries are skipped because the old schema materialized per-field keys for
 * category grants, and the new schema uses a single {@code c:{category}:{purpose}}
 * entry instead. Category-level consent must be rebuilt from the event log.
 *
 * <p>Run per-tenant. Call {@link #migrate(String)} for a single tenant or
 * {@link #migrateAll(List)} for all tenants.
 */
public class ConsentKeyMigrationService {

    private static final Logger LOG = LoggerFactory.getLogger(ConsentKeyMigrationService.class);

    /** Separator between status and source in old flat values. */
    private static final String VALUE_SEPARATOR = "|";

    private static final int SCAN_COUNT = 200;

    private final JedisProvider jedisProvider;
    private final ConsentPolicyRedisRepository repository;
    private final String oldAppName;

    /**
     * @param jedisProvider Redis connection provider
     * @param repository    the new Hash-based repository (used for write helpers)
     * @param oldAppName    the appName prefix used in old flat keys (e.g., "operio")
     */
    public ConsentKeyMigrationService(
        JedisProvider jedisProvider,
        ConsentPolicyRedisRepository repository,
        String oldAppName
    ) {
        this.jedisProvider = jedisProvider;
        this.repository = repository;
        this.oldAppName = oldAppName != null ? oldAppName : "operio";
    }

    /**
     * Migrates old flat consent keys for all provided tenants.
     *
     * @param tenantIds list of tenant identifiers
     * @return total number of keys migrated across all tenants
     */
    public int migrateAll(List<String> tenantIds) {
        int total = 0;
        for (String tenantId : tenantIds) {
            try {
                int count = TenantContext.callWhere(tenantId, () -> migrate(tenantId));
                total += count;
                LOG.info("Migrated {} flat consent keys for tenant '{}'", count, tenantId);
            } catch (Exception e) {
                LOG.error("Migration failed for tenant '{}': {}", tenantId, e.getMessage(), e);
            }
        }
        LOG.info("Migration complete. Total keys migrated: {}", total);
        return total;
    }

    /**
     * Migrates old flat consent keys for a single tenant.
     * Must be called within a {@link TenantContext} scope for the given tenant.
     *
     * @param tenantId the tenant whose keys to migrate
     * @return number of keys migrated
     */
    public int migrate(String tenantId) {
        JedisPooled jedis = jedisProvider.get();

        // Old key pattern: {appName}:{tenantId}:consent:*
        String scanPattern = oldAppName + ":" + tenantId + ":consent:*";

        // New key pattern prefix: {tenantId}:consent: — must exclude new-format keys
        String newKeyPrefix = tenantId + ":consent:";

        int migrated = 0;
        int skippedCategory = 0;
        int skippedMalformed = 0;
        int deleted = 0;

        String cursor = "0";
        ScanParams params = new ScanParams().match(scanPattern).count(SCAN_COUNT);

        do {
            ScanResult<String> scanResult = jedis.scan(cursor, params);
            List<String> keys = scanResult.getResult();
            cursor = scanResult.getCursor();

            for (String oldKey : keys) {
                // Skip keys that are already in new format (Hash keys won't match
                // the old pattern since they lack appName prefix, but guard anyway)
                if (!oldKey.startsWith(oldAppName + ":")) {
                    continue;
                }

                try {
                    String value = jedis.get(oldKey);
                    if (value == null) {
                        continue;
                    }

                    ParsedOldKey parsed = parseOldKey(oldKey, tenantId);
                    if (parsed == null) {
                        skippedMalformed++;
                        LOG.warn("Skipping malformed old key: {}", oldKey);
                        continue;
                    }

                    ParsedOldValue parsedValue = parseOldValue(value);
                    if (parsedValue == null) {
                        skippedMalformed++;
                        LOG.warn("Skipping malformed value for key {}: '{}'", oldKey, value);
                        continue;
                    }

                    // Skip CATEGORY source entries per migration strategy (Option B)
                    if ("CATEGORY".equals(parsedValue.source)) {
                        skippedCategory++;
                        jedis.del(oldKey);
                        deleted++;
                        continue;
                    }

                    // Migrate FIELD source entry to new Hash format
                    boolean granted = "GRANTED".equals(parsedValue.status);
                    String[] entityField = splitQualifiedField(parsed.qualifiedField);
                    repository.setFieldConsent(
                        parsed.subject,
                        entityField[0],
                        entityField[1],
                        parsed.purpose,
                        granted
                    );

                    jedis.del(oldKey);
                    deleted++;
                    migrated++;
                } catch (Exception e) {
                    LOG.error("Error migrating key '{}': {}", oldKey, e.getMessage(), e);
                }
            }
        } while (!"0".equals(cursor));

        LOG.info(
            "Tenant '{}' migration: migrated={}, skippedCategory={}, skippedMalformed={}, deletedOldKeys={}",
            tenantId, migrated, skippedCategory, skippedMalformed, deleted
        );
        return migrated;
    }

    // -----------------------------------------------------------------------
    // Parsing helpers
    // -----------------------------------------------------------------------

    /**
     * Parses an old flat key into its constituent parts.
     *
     * <p>Old format: {@code {appName}:{tenantId}:consent:{subject}:{entity/field}:{purpose}}
     *
     * <p>The entity/field segment is the qualified field (e.g., {@code _default_/ssn}
     * or {@code User/email}). Since it contains a {@code /}, we can't simply
     * split on {@code :} — we parse from the known prefix and suffix positions.
     *
     * @return parsed parts, or null if malformed
     */
    ParsedOldKey parseOldKey(String key, String tenantId) {
        // Expected prefix: "{appName}:{tenantId}:consent:"
        String prefix = oldAppName + ":" + tenantId + ":consent:";
        if (!key.startsWith(prefix)) {
            return null;
        }

        // Remainder: {subject}:{entity/field}:{purpose}
        String remainder = key.substring(prefix.length());

        // Find the first colon to separate subject
        int firstColon = remainder.indexOf(':');
        if (firstColon <= 0) {
            return null;
        }
        String subject = remainder.substring(0, firstColon);

        // Find the last colon to separate purpose
        String afterSubject = remainder.substring(firstColon + 1);
        int lastColon = afterSubject.lastIndexOf(':');
        if (lastColon <= 0) {
            return null;
        }

        String qualifiedField = afterSubject.substring(0, lastColon);
        String purpose = afterSubject.substring(lastColon + 1);

        if (subject.isEmpty() || qualifiedField.isEmpty() || purpose.isEmpty()) {
            return null;
        }

        return new ParsedOldKey(subject, qualifiedField, purpose);
    }

    /**
     * Parses an old-format value ({@code "STATUS|SOURCE"}) into status and source.
     *
     * @return parsed value, or null if malformed
     */
    ParsedOldValue parseOldValue(String value) {
        int sep = value.indexOf(VALUE_SEPARATOR);
        if (sep <= 0 || sep >= value.length() - 1) {
            return null;
        }
        String status = value.substring(0, sep);
        String source = value.substring(sep + 1);
        return new ParsedOldValue(status, source);
    }

    // -----------------------------------------------------------------------
    // Internal records
    // -----------------------------------------------------------------------

    record ParsedOldKey(String subject, String qualifiedField, String purpose) {}

    record ParsedOldValue(String status, String source) {}

    /**
     * Splits a qualified field string ({@code entity/field}) into its parts.
     *
     * @return array of [entity, field], or [null, qualifiedField] if no separator found
     */
    static String[] splitQualifiedField(String qualifiedField) {
        int sep = qualifiedField.indexOf(ConsentPolicyRedisRepository.ENTITY_FIELD_SEP);
        if (sep <= 0) {
            return new String[] { null, qualifiedField };
        }
        return new String[] {
            qualifiedField.substring(0, sep),
            qualifiedField.substring(sep + 1)
        };
    }
}
