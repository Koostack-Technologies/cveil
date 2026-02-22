package com.koostack.operio.plugin.consent.redis;

import java.util.Collections;
import java.util.Set;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;

/**
 * Repository for field-to-category metadata in Redis, stored as bidirectional
 * Sets for O(1) membership checks and O(N) enumeration.
 *
 * <h2>Redis Key Schema</h2>
 * <pre>
 *   {appName}:meta:field2cat:{entity}/{fieldName}    → Set of category names
 *   {appName}:meta:cat2field:{categoryName}          → Set of qualified field names ({entity}/{fieldName})
 * </pre>
 *
 * <p>Field names stored in the sets are <strong>entity-qualified</strong>
 * using the format {@code {entity}/{fieldName}}. When a field has no owning
 * entity the sentinel {@value ConsentPolicyRedisRepository#DEFAULT_ENTITY} is
 * used. This prevents collisions between identically-named fields that belong
 * to different domain entities (e.g. {@code User/email} vs
 * {@code Contact/email}).
 *
 * <p>The {@code appName} prefix isolates different Operio instances that
 * share the same Redis server. It defaults to
 * {@value ConsentPolicyRedisRepository#DEFAULT_APP_NAME} and can be changed
 * at any time via {@link #setAppName(String)}.
 *
 * <p>Both directions are maintained atomically so that lookups from either
 * side are always consistent.
 *
 * <p>This mapping is used during consent evaluation to understand which
 * categories a field belongs to, and during administration to understand
 * which fields a category covers. It mirrors the {@code field_category}
 * join table from the relational consent admin schema.
 */
public class FieldCategoryMappingRedisRepository {

    static final String FIELD_TO_CAT_INFIX = ":meta:field2cat:";
    static final String CAT_TO_FIELD_INFIX = ":meta:cat2field:";

    private final JedisPooled jedis;
    private String appName;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Creates a repository with the default application name
     * ({@value ConsentPolicyRedisRepository#DEFAULT_APP_NAME}).
     *
     * @param jedis the Jedis client
     */
    public FieldCategoryMappingRedisRepository(JedisPooled jedis) {
        this(jedis, ConsentPolicyRedisRepository.DEFAULT_APP_NAME);
    }

    /**
     * Creates a repository with the given application name used as a Redis
     * key prefix.
     *
     * @param jedis   the Jedis client
     * @param appName the application name prefix, or {@code null} to use
     *                the default
     */
    public FieldCategoryMappingRedisRepository(
        JedisPooled jedis,
        String appName
    ) {
        this.jedis = jedis;
        this.appName =
            appName != null
                ? appName
                : ConsentPolicyRedisRepository.DEFAULT_APP_NAME;
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
        this.appName =
            appName != null
                ? appName
                : ConsentPolicyRedisRepository.DEFAULT_APP_NAME;
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
    // Link / Unlink
    // -----------------------------------------------------------------------

    /**
     * Adds a bidirectional mapping between a field and a category.
     *
     * <p>Uses a pipeline so both {@code SADD} commands are sent in a single
     * round trip. The operation is idempotent — calling it again with the
     * same arguments is a no-op.
     *
     * @param entityName   the entity name, or {@code null} for entity-less
     *                     fields
     * @param fieldName    the field name
     * @param categoryName the category name
     */
    public void addFieldToCategory(
        String entityName,
        String fieldName,
        String categoryName
    ) {
        String qf = ConsentPolicyRedisRepository.qualifyField(
            entityName,
            fieldName
        );
        try (Pipeline pipeline = jedis.pipelined()) {
            pipeline.sadd(fieldToCatKey(qf), categoryName);
            pipeline.sadd(catToFieldKey(categoryName), qf);
            pipeline.sync();
        }
    }

    /**
     * Removes the bidirectional mapping between a field and a category.
     *
     * <p>If the mapping does not exist this is a no-op.
     *
     * @param entityName   the entity name, or {@code null}
     * @param fieldName    the field name
     * @param categoryName the category name
     */
    public void removeFieldFromCategory(
        String entityName,
        String fieldName,
        String categoryName
    ) {
        String qf = ConsentPolicyRedisRepository.qualifyField(
            entityName,
            fieldName
        );
        try (Pipeline pipeline = jedis.pipelined()) {
            pipeline.srem(fieldToCatKey(qf), categoryName);
            pipeline.srem(catToFieldKey(categoryName), qf);
            pipeline.sync();
        }
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /**
     * Returns all categories that the given field belongs to.
     *
     * @param entityName the entity name, or {@code null}
     * @param fieldName  the field name
     * @return the set of category names, possibly empty
     */
    public Set<String> getCategoriesForField(
        String entityName,
        String fieldName
    ) {
        String qf = ConsentPolicyRedisRepository.qualifyField(
            entityName,
            fieldName
        );
        Set<String> result = jedis.smembers(fieldToCatKey(qf));
        return result != null ? result : Collections.emptySet();
    }

    /**
     * Returns all fields that belong to the given category.
     *
     * <p>The returned strings are <strong>qualified</strong> field names
     * in the format {@code entity/fieldName}. Callers can pass these
     * directly to
     * {@link ConsentPolicyRedisRepository#setCategoryConsentForFields}
     * for category fan-out writes.
     *
     * @param categoryName the category name
     * @return the set of qualified field names, possibly empty
     */
    public Set<String> getFieldsForCategory(String categoryName) {
        Set<String> result = jedis.smembers(catToFieldKey(categoryName));
        return result != null ? result : Collections.emptySet();
    }

    /**
     * Checks whether a specific field-to-category mapping exists.
     *
     * @param entityName   the entity name, or {@code null}
     * @param fieldName    the field name
     * @param categoryName the category name
     * @return {@code true} if the mapping exists
     */
    public boolean isMapped(
        String entityName,
        String fieldName,
        String categoryName
    ) {
        String qf = ConsentPolicyRedisRepository.qualifyField(
            entityName,
            fieldName
        );
        return jedis.sismember(fieldToCatKey(qf), categoryName);
    }

    // -----------------------------------------------------------------------
    // Bulk removal
    // -----------------------------------------------------------------------

    /**
     * Removes all category mappings for the given field.
     *
     * <p>First reads the current category set, then removes the field from
     * each category's reverse set, and finally deletes the field's own set.
     *
     * @param entityName the entity name, or {@code null}
     * @param fieldName  the field name
     * @return the number of categories that were unlinked
     */
    public int removeAllMappingsForField(String entityName, String fieldName) {
        String qf = ConsentPolicyRedisRepository.qualifyField(
            entityName,
            fieldName
        );
        Set<String> categories = jedis.smembers(fieldToCatKey(qf));
        if (categories == null || categories.isEmpty()) {
            return 0;
        }
        try (Pipeline pipeline = jedis.pipelined()) {
            for (String category : categories) {
                pipeline.srem(catToFieldKey(category), qf);
            }
            pipeline.del(fieldToCatKey(qf));
            pipeline.sync();
        }
        return categories.size();
    }

    /**
     * Removes all field mappings for the given category.
     *
     * <p>First reads the current field set (qualified names), then removes
     * the category from each field's reverse set, and finally deletes the
     * category's own set.
     *
     * @param categoryName the category whose mappings should be removed
     * @return the number of fields that were unlinked
     */
    public int removeAllMappingsForCategory(String categoryName) {
        Set<String> qualifiedFields = jedis.smembers(
            catToFieldKey(categoryName)
        );
        if (qualifiedFields == null || qualifiedFields.isEmpty()) {
            return 0;
        }
        try (Pipeline pipeline = jedis.pipelined()) {
            for (String qf : qualifiedFields) {
                pipeline.srem(fieldToCatKey(qf), categoryName);
            }
            pipeline.del(catToFieldKey(categoryName));
            pipeline.sync();
        }
        return qualifiedFields.size();
    }

    // -----------------------------------------------------------------------
    // Key helpers
    // -----------------------------------------------------------------------

    /**
     * Builds the forward-direction key for a qualified field.
     *
     * @param qualifiedField the qualified field string
     *                       ({@code entity/fieldName})
     * @return {@code {appName}:meta:field2cat:{qualifiedField}}
     */
    String fieldToCatKey(String qualifiedField) {
        return appName + FIELD_TO_CAT_INFIX + qualifiedField;
    }

    /**
     * Builds the reverse-direction key for a category.
     *
     * @param categoryName the category name
     * @return {@code {appName}:meta:cat2field:{categoryName}}
     */
    String catToFieldKey(String categoryName) {
        return appName + CAT_TO_FIELD_INFIX + categoryName;
    }
}
