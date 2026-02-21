package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.plugin.consent.jooq.generated.tables.daos.EntityDao;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Entity;

import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.ENTITY;

/**
 * Repository for the {@code entity} table — optional logical groupings of
 * fields (e.g. "Patient", "Order").
 *
 * <p>Wraps the generated {@link EntityDao} for standard CRUD and adds
 * domain-specific lookups and an upsert keyed on the unique
 * {@code entity_name} column.
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

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /**
     * Looks up an entity by its unique name.
     *
     * @param entityName the entity name to search for
     * @return the matching entity, or empty if none exists
     */
    public Optional<Entity> findByName(String entityName) {
        return dsl.selectFrom(ENTITY)
                  .where(ENTITY.ENTITY_NAME.eq(entityName))
                  .fetchOptionalInto(Entity.class);
    }

    /**
     * Returns all entities, ordered by name.
     *
     * @return every entity row, possibly empty
     */
    public List<Entity> findAll() {
        return dsl.selectFrom(ENTITY)
                  .orderBy(ENTITY.ENTITY_NAME)
                  .fetchInto(Entity.class);
    }

    // -----------------------------------------------------------------------
    // Upsert
    // -----------------------------------------------------------------------

    /**
     * Inserts a new entity or updates the existing one if a row with the same
     * {@code entity_name} already exists.
     *
     * <p>On conflict the {@code description}, {@code updated_at}, and
     * {@code updated_by} columns are updated; the original
     * {@code created_at}/{@code created_by} values are preserved.
     *
     * @param entityName  the unique entity name
     * @param description optional human-readable description (may be {@code null})
     * @param actor       the identity performing the operation (may be {@code null})
     */
    public void upsertByName(String entityName, String description, String actor) {
        LocalDateTime now = LocalDateTime.now();

        dsl.insertInto(ENTITY)
           .set(ENTITY.ENTITY_NAME, entityName)
           .set(ENTITY.DESCRIPTION, description)
           .set(ENTITY.CREATED_AT, now)
           .set(ENTITY.CREATED_BY, actor)
           .set(ENTITY.UPDATED_AT, now)
           .set(ENTITY.UPDATED_BY, actor)
           .onConflict(ENTITY.ENTITY_NAME)
           .doUpdate()
           .set(ENTITY.DESCRIPTION, description)
           .set(ENTITY.UPDATED_AT, now)
           .set(ENTITY.UPDATED_BY, actor)
           .execute();
    }
}
