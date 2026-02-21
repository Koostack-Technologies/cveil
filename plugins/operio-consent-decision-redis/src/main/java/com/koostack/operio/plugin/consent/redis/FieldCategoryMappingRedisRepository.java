package com.koostack.operio.plugin.consent.redis;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;

import java.util.Collections;
import java.util.Set;

/**
 * Repository for field-to-category metadata in Redis, stored as bidirectional
 * Sets for O(1) membership checks and O(N) enumeration.
 *
 * <h2>Redis Key Schema</h2>
 * <pre>
 *   operio:meta:field2cat:{fieldName}    → Set of category names
 *   operio:meta:cat2field:{categoryName} → Set of field names
 * </pre>
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

    static final String FIELD_TO_CAT_PREFIX = "operio:meta:field2cat:";
    static final String CAT_TO_FIELD_PREFIX = "operio:meta:cat2field:";

    private final JedisPooled jedis;

    public FieldCategoryMappingRedisRepository(JedisPooled jedis) {
        this.jedis = jedis;
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
     * @param fieldName    the field name
     * @param categoryName the category name
     */
    public void addFieldToCategory(String fieldName, String categoryName) {
        try (Pipeline pipeline = jedis.pipelined()) {
            pipeline.sadd(fieldToCatKey(fieldName), categoryName);
            pipeline.sadd(catToFieldKey(categoryName), fieldName);
            pipeline.sync();
        }
    }

    /**
     * Removes the bidirectional mapping between a field and a category.
     *
     * <p>If the mapping does not exist this is a no-op.
     *
     * @param fieldName    the field name
     * @param categoryName the category name
     */
    public void removeFieldFromCategory(String fieldName, String categoryName) {
        try (Pipeline pipeline = jedis.pipelined()) {
            pipeline.srem(fieldToCatKey(fieldName), categoryName);
            pipeline.srem(catToFieldKey(categoryName), fieldName);
            pipeline.sync();
        }
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /**
     * Returns all categories that the given field belongs to.
     *
     * @param fieldName the field name
     * @return the set of category names, possibly empty
     */
    public Set<String> getCategoriesForField(String fieldName) {
        Set<String> result = jedis.smembers(fieldToCatKey(fieldName));
        return result != null ? result : Collections.emptySet();
    }

    /**
     * Returns all fields that belong to the given category.
     *
     * @param categoryName the category name
     * @return the set of field names, possibly empty
     */
    public Set<String> getFieldsForCategory(String categoryName) {
        Set<String> result = jedis.smembers(catToFieldKey(categoryName));
        return result != null ? result : Collections.emptySet();
    }

    /**
     * Checks whether a specific field-to-category mapping exists.
     *
     * @param fieldName    the field name
     * @param categoryName the category name
     * @return {@code true} if the mapping exists
     */
    public boolean isMapped(String fieldName, String categoryName) {
        return jedis.sismember(fieldToCatKey(fieldName), categoryName);
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
     * @param fieldName the field whose mappings should be removed
     * @return the number of categories that were unlinked
     */
    public int removeAllMappingsForField(String fieldName) {
        Set<String> categories = getCategoriesForField(fieldName);
        if (categories.isEmpty()) {
            return 0;
        }
        try (Pipeline pipeline = jedis.pipelined()) {
            for (String category : categories) {
                pipeline.srem(catToFieldKey(category), fieldName);
            }
            pipeline.del(fieldToCatKey(fieldName));
            pipeline.sync();
        }
        return categories.size();
    }

    /**
     * Removes all field mappings for the given category.
     *
     * <p>First reads the current field set, then removes the category from
     * each field's reverse set, and finally deletes the category's own set.
     *
     * @param categoryName the category whose mappings should be removed
     * @return the number of fields that were unlinked
     */
    public int removeAllMappingsForCategory(String categoryName) {
        Set<String> fields = getFieldsForCategory(categoryName);
        if (fields.isEmpty()) {
            return 0;
        }
        try (Pipeline pipeline = jedis.pipelined()) {
            for (String field : fields) {
                pipeline.srem(fieldToCatKey(field), categoryName);
            }
            pipeline.del(catToFieldKey(categoryName));
            pipeline.sync();
        }
        return fields.size();
    }

    // -----------------------------------------------------------------------
    // Key helpers
    // -----------------------------------------------------------------------

    static String fieldToCatKey(String fieldName) {
        return FIELD_TO_CAT_PREFIX + fieldName;
    }

    static String catToFieldKey(String categoryName) {
        return CAT_TO_FIELD_PREFIX + categoryName;
    }
}
