package com.koostack.cveil.plugin.consent.jooq;

import static com.koostack.cveil.plugin.consent.jooq.generated.Tables.*;

import com.koostack.cveil.core.models.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * Repository for the {@code policy} table — the relationship between a purpose and a field or
 * category that defines a consent policy.
 *
 * <p>Used by the query service to resolve ConsentScope → policy_id and to build aggregate views for
 * Purpose.
 */
public class PolicyRepository {

    private final DSLContext dsl;

    public PolicyRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Finds a field-type policy by purpose name, field name, and entity name.
     *
     * @return the policy record, or empty if no matching policy exists
     */
    public Optional<Record> findFieldPolicy(
            String purposeName, String fieldName, String entityName) {
        return dsl.select(POLICY.fields())
                .select(PURPOSE.PURPOSE_NAME)
                .select(FIELD.FIELD_NAME)
                .select(ENTITY.ENTITY_NAME)
                .from(POLICY)
                .join(PURPOSE)
                .on(POLICY.PURPOSE_UUID.eq(PURPOSE.PURPOSE_UUID))
                .join(FIELD)
                .on(POLICY.FIELD_UUID.eq(FIELD.FIELD_UUID))
                .join(ENTITY)
                .on(FIELD.ENTITY_UUID.eq(ENTITY.ENTITY_UUID))
                .where(PURPOSE.PURPOSE_NAME.eq(purposeName))
                .and(FIELD.FIELD_NAME.eq(fieldName))
                .and(ENTITY.ENTITY_NAME.eq(entityName))
                .and(POLICY.TENANT_ID.eq(TenantContext.require()))
                .fetchOptional();
    }

    /**
     * Finds a category-type policy by purpose name and category name.
     *
     * @return the policy record, or empty if no matching policy exists
     */
    public Optional<Record> findCategoryPolicy(String purposeName, String categoryName) {
        return dsl.select(POLICY.fields())
                .select(PURPOSE.PURPOSE_NAME)
                .select(CATEGORY.CATEGORY_NAME)
                .from(POLICY)
                .join(PURPOSE)
                .on(POLICY.PURPOSE_UUID.eq(PURPOSE.PURPOSE_UUID))
                .join(CATEGORY)
                .on(POLICY.CATEGORY_UUID.eq(CATEGORY.CATEGORY_UUID))
                .where(PURPOSE.PURPOSE_NAME.eq(purposeName))
                .and(CATEGORY.CATEGORY_NAME.eq(categoryName))
                .and(POLICY.TENANT_ID.eq(TenantContext.require()))
                .fetchOptional();
    }

    /** Returns all policies for a given purpose, including joined field/category names. */
    public List<Record> findByPurpose(UUID purposeUuid) {
        return dsl.select(POLICY.fields())
                .select(FIELD.FIELD_NAME)
                .select(ENTITY.ENTITY_NAME)
                .select(CATEGORY.CATEGORY_NAME)
                .from(POLICY)
                .leftJoin(FIELD)
                .on(POLICY.FIELD_UUID.eq(FIELD.FIELD_UUID))
                .leftJoin(ENTITY)
                .on(FIELD.ENTITY_UUID.eq(ENTITY.ENTITY_UUID))
                .leftJoin(CATEGORY)
                .on(POLICY.CATEGORY_UUID.eq(CATEGORY.CATEGORY_UUID))
                .where(POLICY.PURPOSE_UUID.eq(purposeUuid))
                .and(POLICY.TENANT_ID.eq(TenantContext.require()))
                .fetch();
    }
}
