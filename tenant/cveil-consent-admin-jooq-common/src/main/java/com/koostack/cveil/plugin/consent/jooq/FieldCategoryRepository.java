package com.koostack.cveil.plugin.consent.jooq;

import static com.koostack.cveil.plugin.consent.jooq.generated.Tables.FIELD_CATEGORY;

import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.daos.FieldCategoryDao;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.pojos.FieldCategory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
     * @param fieldUuid the field identifier
     * @return all join rows for that field, possibly empty
     */
    public List<FieldCategory> findByField(UUID fieldUuid) {
        return dsl.selectFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.FIELD_UUID.eq(fieldUuid))
                .and(FIELD_CATEGORY.TENANT_ID.eq(TenantContext.require()))
                .fetchInto(FieldCategory.class);
    }

    /**
     * Returns every field assignment for the given category.
     *
     * @param categoryUuid the category identifier
     * @return all join rows for that category, possibly empty
     */
    public List<FieldCategory> findByCategory(UUID categoryUuid) {
        return dsl.selectFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.CATEGORY_UUID.eq(categoryUuid))
                .and(FIELD_CATEGORY.TENANT_ID.eq(TenantContext.require()))
                .fetchInto(FieldCategory.class);
    }

    /**
     * Looks up the specific link between a field and a category.
     *
     * @param fieldUuid the field identifier
     * @param categoryUuid the category identifier
     * @return the matching join row, or empty if the link does not exist
     */
    public Optional<FieldCategory> findByFieldAndCategory(UUID fieldUuid, UUID categoryUuid) {
        return dsl.selectFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.FIELD_UUID.eq(fieldUuid))
                .and(FIELD_CATEGORY.CATEGORY_UUID.eq(categoryUuid))
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
                .orderBy(FIELD_CATEGORY.FIELD_UUID, FIELD_CATEGORY.CATEGORY_UUID)
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
     * @param fieldUuid the field to link
     * @param categoryUuid the category to link
     * @param actor the identity performing the operation (may be {@code null})
     */
    public void link(UUID fieldUuid, UUID categoryUuid, String actor) {
        LocalDateTime now = LocalDateTime.now();

        dsl.insertInto(FIELD_CATEGORY)
                .set(FIELD_CATEGORY.TENANT_ID, TenantContext.require())
                .set(FIELD_CATEGORY.FIELD_UUID, fieldUuid)
                .set(FIELD_CATEGORY.CATEGORY_UUID, categoryUuid)
                .set(FIELD_CATEGORY.CREATED_AT, now)
                .set(FIELD_CATEGORY.CREATED_BY, actor)
                .set(FIELD_CATEGORY.UPDATED_AT, now)
                .set(FIELD_CATEGORY.UPDATED_BY, actor)
                .onConflict(
                        FIELD_CATEGORY.TENANT_ID,
                        FIELD_CATEGORY.FIELD_UUID,
                        FIELD_CATEGORY.CATEGORY_UUID)
                .doNothing()
                .execute();
    }

    /**
     * Removes the link between a field and a category.
     *
     * <p>If the link does not exist this is a no-op.
     *
     * @param fieldUuid the field to unlink
     * @param categoryUuid the category to unlink
     */
    public void unlink(UUID fieldUuid, UUID categoryUuid) {
        dsl.deleteFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.FIELD_UUID.eq(fieldUuid))
                .and(FIELD_CATEGORY.CATEGORY_UUID.eq(categoryUuid))
                .execute();
    }

    /**
     * Removes all category assignments for the given field.
     *
     * @param fieldUuid the field whose links should be removed
     * @return the number of rows deleted
     */
    public int unlinkAllByField(UUID fieldUuid) {
        return dsl.deleteFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.FIELD_UUID.eq(fieldUuid))
                .execute();
    }

    /**
     * Removes all field assignments for the given category.
     *
     * @param categoryUuid the category whose links should be removed
     * @return the number of rows deleted
     */
    public int unlinkAllByCategory(UUID categoryUuid) {
        return dsl.deleteFrom(FIELD_CATEGORY)
                .where(FIELD_CATEGORY.CATEGORY_UUID.eq(categoryUuid))
                .execute();
    }
}
