package com.koostack.operio.plugin.consent.caffeine;

import com.koostack.operio.core.TenantContext;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory bidirectional field-to-category mapping repository using
 * concurrent maps.
 *
 * <p>This is the Caffeine/embedded counterpart of
 * {@code FieldCategoryMappingRedisRepository} — it provides the same
 * bidirectional mapping semantics but stores everything in-process rather
 * than in Redis Sets.
 *
 * <h2>Key Schema</h2>
 * <pre>
 *   field2cat:  qualifiedField ({entity}/{fieldName})  → Set of category names
 *   cat2field:  categoryName                           → Set of qualified field names ({entity}/{fieldName})
 * </pre>
 *
 * <p>Field names stored in the sets are <strong>entity-qualified</strong>
 * using the format {@code {entity}/{fieldName}}. When a field has no owning
 * entity the sentinel {@value CaffeineConsentRepository#DEFAULT_ENTITY} is
 * used. This prevents collisions between identically-named fields that
 * belong to different domain entities (e.g. {@code User/email} vs
 * {@code Contact/email}).
 *
 * <p>Both directions are maintained atomically (via synchronized blocks on
 * the individual sets) so that lookups from either side are always
 * consistent.
 *
 * <h2>Thread Safety</h2>
 * <p>All operations are thread-safe. The underlying
 * {@link ConcurrentHashMap}s ensure safe concurrent access, and individual
 * set mutations are performed on concurrent sets returned by
 * {@link ConcurrentHashMap#newKeySet()}.
 *
 * @see CaffeineConsentRepository
 * @see CaffeineConsentDecisionService
 */
public class CaffeineFieldCategoryMappingRepository {

    /**
     * Forward mapping: qualified field → set of category names.
     */
    private final ConcurrentHashMap<String, Set<String>> field2cat =
        new ConcurrentHashMap<>();

    /**
     * Reverse mapping: category name → set of qualified field names.
     */
    private final ConcurrentHashMap<String, Set<String>> cat2field =
        new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Key helpers
    // -----------------------------------------------------------------------

    /**
     * Computes the key prefix for all map keys, incorporating the current
     * tenant context when present.
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
        return tenant != null ? tenant + ":" : "";
    }

    /**
     * Builds the tenant-aware map key for a qualified field.
     *
     * @param qualifiedField the qualified field ({@code entity/fieldName})
     * @return {@code {tenantId}:{qualifiedField}} when tenant is active,
     *         otherwise just {@code {qualifiedField}}
     */
    private String fieldMapKey(String qualifiedField) {
        return keyPrefix() + qualifiedField;
    }

    /**
     * Builds the tenant-aware map key for a category name.
     *
     * @param categoryName the category name
     * @return {@code {tenantId}:{categoryName}} when tenant is active,
     *         otherwise just {@code {categoryName}}
     */
    private String categoryMapKey(String categoryName) {
        return keyPrefix() + categoryName;
    }

    // -----------------------------------------------------------------------
    // Link / Unlink
    // -----------------------------------------------------------------------

    /**
     * Adds a bidirectional mapping between a field and a category.
     *
     * <p>The operation is idempotent — calling it again with the same
     * arguments is a no-op.
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
        String qf = CaffeineConsentRepository.qualifyField(
            entityName,
            fieldName
        );
        String fKey = fieldMapKey(qf);
        String cKey = categoryMapKey(categoryName);
        field2cat
            .computeIfAbsent(fKey, k -> ConcurrentHashMap.newKeySet())
            .add(categoryName);
        cat2field
            .computeIfAbsent(cKey, k -> ConcurrentHashMap.newKeySet())
            .add(qf);
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
        String qf = CaffeineConsentRepository.qualifyField(
            entityName,
            fieldName
        );
        String fKey = fieldMapKey(qf);
        String cKey = categoryMapKey(categoryName);
        Set<String> categories = field2cat.get(fKey);
        if (categories != null) {
            categories.remove(categoryName);
            if (categories.isEmpty()) {
                field2cat.remove(fKey, Collections.emptySet());
            }
        }
        Set<String> fields = cat2field.get(cKey);
        if (fields != null) {
            fields.remove(qf);
            if (fields.isEmpty()) {
                cat2field.remove(cKey, Collections.emptySet());
            }
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
     * @return an unmodifiable view of the category names, possibly empty
     */
    public Set<String> getCategoriesForField(
        String entityName,
        String fieldName
    ) {
        String qf = CaffeineConsentRepository.qualifyField(
            entityName,
            fieldName
        );
        Set<String> result = field2cat.get(fieldMapKey(qf));
        return result != null
            ? Collections.unmodifiableSet(result)
            : Collections.emptySet();
    }

    /**
     * Returns all fields that belong to the given category.
     *
     * <p>The returned strings are <strong>qualified</strong> field names
     * in the format {@code entity/fieldName}. Callers can pass these
     * directly to
     * {@link CaffeineConsentRepository#setCategoryConsentForFields}
     * for category fan-out writes.
     *
     * @param categoryName the category name
     * @return an unmodifiable view of the qualified field names, possibly
     *         empty
     */
    public Set<String> getFieldsForCategory(String categoryName) {
        Set<String> result = cat2field.get(categoryMapKey(categoryName));
        return result != null
            ? Collections.unmodifiableSet(result)
            : Collections.emptySet();
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
        String qf = CaffeineConsentRepository.qualifyField(
            entityName,
            fieldName
        );
        Set<String> categories = field2cat.get(fieldMapKey(qf));
        return categories != null && categories.contains(categoryName);
    }

    // -----------------------------------------------------------------------
    // Bulk removal
    // -----------------------------------------------------------------------

    /**
     * Removes all category mappings for the given field.
     *
     * <p>Removes the field from each category's reverse set, then deletes
     * the field's own forward set.
     *
     * @param entityName the entity name, or {@code null}
     * @param fieldName  the field name
     * @return the number of categories that were unlinked
     */
    public int removeAllMappingsForField(String entityName, String fieldName) {
        String qf = CaffeineConsentRepository.qualifyField(
            entityName,
            fieldName
        );
        String fKey = fieldMapKey(qf);
        Set<String> categories = field2cat.remove(fKey);
        if (categories == null || categories.isEmpty()) {
            return 0;
        }
        for (String category : categories) {
            Set<String> fields = cat2field.get(categoryMapKey(category));
            if (fields != null) {
                fields.remove(qf);
                if (fields.isEmpty()) {
                    cat2field.remove(
                        categoryMapKey(category),
                        Collections.emptySet()
                    );
                }
            }
        }
        return categories.size();
    }

    /**
     * Removes all field mappings for the given category.
     *
     * <p>Removes the category from each field's reverse set, then deletes
     * the category's own forward set.
     *
     * @param categoryName the category whose mappings should be removed
     * @return the number of fields that were unlinked
     */
    public int removeAllMappingsForCategory(String categoryName) {
        String cKey = categoryMapKey(categoryName);
        Set<String> qualifiedFields = cat2field.remove(cKey);
        if (qualifiedFields == null || qualifiedFields.isEmpty()) {
            return 0;
        }
        for (String qf : qualifiedFields) {
            Set<String> categories = field2cat.get(fieldMapKey(qf));
            if (categories != null) {
                categories.remove(categoryName);
                if (categories.isEmpty()) {
                    field2cat.remove(fieldMapKey(qf), Collections.emptySet());
                }
            }
        }
        return qualifiedFields.size();
    }

    // -----------------------------------------------------------------------
    // Housekeeping
    // -----------------------------------------------------------------------

    /**
     * Removes all mappings from both directions.
     */
    public void clear() {
        field2cat.clear();
        cat2field.clear();
    }

    /**
     * Returns the total number of unique qualified fields that have at
     * least one category mapping.
     *
     * @return the number of mapped fields
     */
    public int fieldCount() {
        return field2cat.size();
    }

    /**
     * Returns the total number of unique categories that have at least one
     * field mapping.
     *
     * @return the number of mapped categories
     */
    public int categoryCount() {
        return cat2field.size();
    }
}
