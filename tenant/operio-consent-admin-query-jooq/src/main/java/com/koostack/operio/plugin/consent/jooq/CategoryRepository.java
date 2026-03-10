package com.koostack.operio.plugin.consent.jooq;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.CATEGORY;

import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.plugin.consent.jooq.generated.tables.daos.CategoryDao;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Category;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

/** Read-only repository for the {@code category} table. */
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

    public Optional<Category> findByName(String categoryName) {
        return dsl.selectFrom(CATEGORY)
                .where(CATEGORY.CATEGORY_NAME.eq(categoryName))
                .and(CATEGORY.TENANT_ID.eq(TenantContext.require()))
                .fetchOptionalInto(Category.class);
    }

    public List<Category> findAll() {
        return dsl.selectFrom(CATEGORY)
                .where(CATEGORY.TENANT_ID.eq(TenantContext.require()))
                .orderBy(CATEGORY.CATEGORY_NAME)
                .fetchInto(Category.class);
    }
}
