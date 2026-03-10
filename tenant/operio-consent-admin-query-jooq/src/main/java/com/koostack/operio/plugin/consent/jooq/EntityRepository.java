package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.plugin.consent.jooq.generated.tables.daos.EntityDao;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Entity;

import org.jooq.DSLContext;

import java.util.List;
import java.util.Optional;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.ENTITY;

/**
 * Read-only repository for the {@code entity} table.
 */
public class EntityRepository {

    private final DSLContext dsl;
    private final EntityDao dao;

    public EntityRepository(DSLContext dsl) {
        this.dsl = dsl;
        this.dao = new EntityDao(dsl.configuration());
    }

    /** Exposes the generated DAO for standard CRUD when needed. */
    public EntityDao dao() {
        return dao;
    }

    public Optional<Entity> findByName(String entityName) {
        return dsl.selectFrom(ENTITY)
                  .where(ENTITY.ENTITY_NAME.eq(entityName))
                  .and(ENTITY.TENANT_ID.eq(TenantContext.require()))
                  .fetchOptionalInto(Entity.class);
    }

    public List<Entity> findAll() {
        return dsl.selectFrom(ENTITY)
                  .where(ENTITY.TENANT_ID.eq(TenantContext.require()))
                  .orderBy(ENTITY.ENTITY_NAME)
                  .fetchInto(Entity.class);
    }
}
