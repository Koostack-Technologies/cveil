package com.koostack.operio.plugin.consent.jooq;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.*;

import java.util.List;
import java.util.Optional;
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
                .on(POLICY.PURPOSE_ID.eq(PURPOSE.PURPOSE_ID))
                .join(FIELD)
                .on(POLICY.FIELD_ID.eq(FIELD.FIELD_ID))
                .join(ENTITY)
                .on(FIELD.ENTITY_ID.eq(ENTITY.ENTITY_ID))
                .where(PURPOSE.PURPOSE_NAME.eq(purposeName))
                .and(FIELD.FIELD_NAME.eq(fieldName))
                .and(ENTITY.ENTITY_NAME.eq(entityName))
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
                .on(POLICY.PURPOSE_ID.eq(PURPOSE.PURPOSE_ID))
                .join(CATEGORY)
                .on(POLICY.CATEGORY_ID.eq(CATEGORY.CATEGORY_ID))
                .where(PURPOSE.PURPOSE_NAME.eq(purposeName))
                .and(CATEGORY.CATEGORY_NAME.eq(categoryName))
                .fetchOptional();
    }

    /** Returns all policies for a given purpose, including joined field/category names. */
    public List<Record> findByPurpose(Long purposeId) {
        return dsl.select(POLICY.fields())
                .select(FIELD.FIELD_NAME)
                .select(ENTITY.ENTITY_NAME)
                .select(CATEGORY.CATEGORY_NAME)
                .from(POLICY)
                .leftJoin(FIELD)
                .on(POLICY.FIELD_ID.eq(FIELD.FIELD_ID))
                .leftJoin(ENTITY)
                .on(FIELD.ENTITY_ID.eq(ENTITY.ENTITY_ID))
                .leftJoin(CATEGORY)
                .on(POLICY.CATEGORY_ID.eq(CATEGORY.CATEGORY_ID))
                .where(POLICY.PURPOSE_ID.eq(purposeId))
                .fetch();
    }
}
