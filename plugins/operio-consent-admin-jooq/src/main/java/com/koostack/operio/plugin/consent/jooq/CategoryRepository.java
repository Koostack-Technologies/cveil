package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.plugin.consent.jooq.generated.tables.daos.CategoryDao;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Category;

import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.CATEGORY;

/**
 * Repository for the {@code category} table — named buckets that fields can
 * be assigned to so consent can be granted or revoked at the bucket level
 * rather than per-field (e.g. "PII", "Financial").
 *
 * <p>Wraps the generated {@link CategoryDao} for standard CRUD and adds
 * domain-specific lookups and an upsert keyed on the unique
 * {@code category_name} column.
 */
public class CategoryRepository {

    private final DSLContext dsl;
    private final CategoryDao dao;

    public CategoryRepository(DSLContext dsl) {
        this.dsl = dsl;
        this.dao = new CategoryDao(dsl.configuration());
    }

    /** Exposes the generated DAO for standard CRUD when needed. */
    public CategoryDao dao() {
        return dao;
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /**
     * Looks up a category by its unique name.
     *
     * @param categoryName the category name to search for
     * @return the matching category, or empty if none exists
     */
    public Optional<Category> findByName(String categoryName) {
        return dsl.selectFrom(CATEGORY)
                  .where(CATEGORY.CATEGORY_NAME.eq(categoryName))
                  .fetchOptionalInto(Category.class);
    }

    /**
     * Returns all categories, ordered by name.
     *
     * @return every category row, possibly empty
     */
    public List<Category> findAll() {
        return dsl.selectFrom(CATEGORY)
                  .orderBy(CATEGORY.CATEGORY_NAME)
                  .fetchInto(Category.class);
    }

    // -----------------------------------------------------------------------
    // Upsert
    // -----------------------------------------------------------------------

    /**
     * Inserts a new category or updates the existing one if a row with the
     * same {@code category_name} already exists.
     *
     * <p>On conflict the {@code description}, {@code updated_at}, and
     * {@code updated_by} columns are updated; the original
     * {@code created_at}/{@code created_by} values are preserved.
     *
     * @param categoryName the unique category name
     * @param description  optional human-readable description (may be {@code null})
     * @param actor        the identity performing the operation (may be {@code null})
     */
    public void upsertByName(String categoryName, String description, String actor) {
        LocalDateTime now = LocalDateTime.now();

        dsl.insertInto(CATEGORY)
           .set(CATEGORY.CATEGORY_NAME, categoryName)
           .set(CATEGORY.DESCRIPTION, description)
           .set(CATEGORY.CREATED_AT, now)
           .set(CATEGORY.CREATED_BY, actor)
           .set(CATEGORY.UPDATED_AT, now)
           .set(CATEGORY.UPDATED_BY, actor)
           .onConflict(CATEGORY.CATEGORY_NAME)
           .doUpdate()
           .set(CATEGORY.DESCRIPTION, description)
           .set(CATEGORY.UPDATED_AT, now)
           .set(CATEGORY.UPDATED_BY, actor)
           .execute();
    }
}
