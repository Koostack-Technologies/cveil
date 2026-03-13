package com.koostack.cveil.plugin.consent.jooq;

import static com.koostack.cveil.plugin.consent.jooq.generated.Tables.FIELD_CATEGORY;

import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.daos.FieldCategoryDao;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.pojos.FieldCategory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

/**
 * Repository for the {@code field_category} join table — the many-to-many assignment of fields into
 * categories.
 *
 * <p>Wraps the generated {@link FieldCategoryDao} for standard CRUD and adds domain-specific
 * link/unlink operations keyed on the unique {@code (field_id, category_id)} composite constraint.
 */
public class FieldCategoryRepository {

    private final DSLContext dsl;
    private final FieldCategoryDao dao;

    public FieldCategoryRepository(DSLContext dsl) {
        this.dsl = dsl;
        this.dao = new FieldCategoryDao(dsl.configuration());
    }

    /** Exposes the generated DAO for standard CRUD when needed. */
    public FieldCategoryDao dao() {
        return dao;
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /**
     * Returns every category assignment for the given field.
     *
     * @param fieldId the field identifier
     * @return all join rows for that field, possibly empty
     */
    public List<FieldCategory> findByField(Long fieldId) {
        return dsl.selectFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.FIELD_ID.eq(fieldId))
                .and(FIELD_CATEGORY.TENANT_ID.eq(TenantContext.require()))
                .fetchInto(FieldCategory.class);
    }

    /**
     * Returns every field assignment for the given category.
     *
     * @param categoryId the category identifier
     * @return all join rows for that category, possibly empty
     */
    public List<FieldCategory> findByCategory(Long categoryId) {
        return dsl.selectFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.CATEGORY_ID.eq(categoryId))
                .and(FIELD_CATEGORY.TENANT_ID.eq(TenantContext.require()))
                .fetchInto(FieldCategory.class);
    }

    /**
     * Looks up the specific link between a field and a category.
     *
     * @param fieldId the field identifier
     * @param categoryId the category identifier
     * @return the matching join row, or empty if the link does not exist
     */
    public Optional<FieldCategory> findByFieldAndCategory(Long fieldId, Long categoryId) {
        return dsl.selectFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.FIELD_ID.eq(fieldId))
                .and(FIELD_CATEGORY.CATEGORY_ID.eq(categoryId))
                .and(FIELD_CATEGORY.TENANT_ID.eq(TenantContext.require()))
                .fetchOptionalInto(FieldCategory.class);
    }

    /**
     * Returns all field-category links, ordered by field then category.
     *
     * @return every join row, possibly empty
     */
    public List<FieldCategory> findAll() {
        return dsl.selectFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.TENANT_ID.eq(TenantContext.require()))
                .orderBy(FIELD_CATEGORY.FIELD_ID, FIELD_CATEGORY.CATEGORY_ID)
                .fetchInto(FieldCategory.class);
    }

    // -----------------------------------------------------------------------
    // Link / Unlink
    // -----------------------------------------------------------------------

    /**
     * Creates the link between a field and a category if it does not already exist.
     *
     * <p>Uses an {@code INSERT ... ON CONFLICT DO NOTHING} so the call is safely idempotent.
     *
     * @param fieldId the field to link
     * @param categoryId the category to link
     * @param actor the identity performing the operation (may be {@code null})
     */
    public void link(Long fieldId, Long categoryId, String actor) {
        LocalDateTime now = LocalDateTime.now();

        dsl.insertInto(FIELD_CATEGORY)
                .set(FIELD_CATEGORY.FIELD_ID, fieldId)
                .set(FIELD_CATEGORY.CATEGORY_ID, categoryId)
                .set(FIELD_CATEGORY.CREATED_AT, now)
                .set(FIELD_CATEGORY.CREATED_BY, actor)
                .set(FIELD_CATEGORY.UPDATED_AT, now)
                .set(FIELD_CATEGORY.UPDATED_BY, actor)
                .onConflict(FIELD_CATEGORY.FIELD_ID, FIELD_CATEGORY.CATEGORY_ID)
                .doNothing()
                .execute();
    }

    /**
     * Removes the link between a field and a category.
     *
     * <p>If the link does not exist this is a no-op.
     *
     * @param fieldId the field to unlink
     * @param categoryId the category to unlink
     */
    public void unlink(Long fieldId, Long categoryId) {
        dsl.deleteFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.FIELD_ID.eq(fieldId))
                .and(FIELD_CATEGORY.CATEGORY_ID.eq(categoryId))
                .execute();
    }

    /**
     * Removes all category assignments for the given field.
     *
     * @param fieldId the field whose links should be removed
     * @return the number of rows deleted
     */
    public int unlinkAllByField(Long fieldId) {
        return dsl.deleteFrom(FIELD_CATEGORY).where(FIELD_CATEGORY.FIELD_ID.eq(fieldId)).execute();
    }

    /**
     * Removes all field assignments for the given category.
     *
     * @param categoryId the category whose links should be removed
     * @return the number of rows deleted
     */
    public int unlinkAllByCategory(Long categoryId) {
        return dsl.deleteFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.CATEGORY_ID.eq(categoryId))
                .execute();
    }
}
