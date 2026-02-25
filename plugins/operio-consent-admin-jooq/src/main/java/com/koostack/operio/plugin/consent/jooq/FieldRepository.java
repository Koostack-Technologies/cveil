package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.plugin.consent.jooq.generated.tables.daos.FieldDao;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Field;

import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.FIELD;

/**
 * Repository for the {@code field} table — every piece of data that can be
 * governed by consent.
 *
 * <p>Wraps the generated {@link FieldDao} for standard CRUD and adds
 * domain-specific lookups and an upsert keyed on the unique
 * {@code (field_name, entity_id)} composite constraint.
 */
public class FieldRepository {

    private final DSLContext dsl;
    private final FieldDao dao;

    public FieldRepository(DSLContext dsl) {
        this.dsl = dsl;
        this.dao = new FieldDao(dsl.configuration());
    }

    /** Exposes the generated DAO for standard CRUD when needed. */
    public FieldDao dao() {
        return dao;
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /**
     * Looks up a field by name irrespective of which entity it belongs to.
     *
     * <p>If the same field name exists under multiple entities this returns
     * all of them — use {@link #findByNameAndEntity} for an exact match.
     *
     * @param fieldName the field name to search for
     * @return all fields with the given name, possibly empty
     */
    public List<Field> findByName(String fieldName) {
        return dsl.selectFrom(FIELD)
                  .where(FIELD.FIELD_NAME.eq(fieldName))
                  .fetchInto(Field.class);
    }

    /**
     * Looks up the unique field identified by its name and owning entity.
     *
     * @param fieldName the field name
     * @param entityId  the owning entity's ID, or {@code null} for an
     *                  entity-less field
     * @return the matching field, or empty if none exists
     */
    public Optional<Field> findByNameAndEntity(String fieldName, Long entityId) {
        var condition = FIELD.FIELD_NAME.eq(fieldName);

        if (entityId != null) {
            condition = condition.and(FIELD.ENTITY_ID.eq(entityId));
        } else {
            condition = condition.and(FIELD.ENTITY_ID.isNull());
        }

        return dsl.selectFrom(FIELD)
                  .where(condition)
                  .fetchOptionalInto(Field.class);
    }

    /**
     * Returns every field that belongs to the given entity.
     *
     * @param entityId the owning entity's ID
     * @return all fields under that entity, possibly empty
     */
    public List<Field> findByEntity(Long entityId) {
        return dsl.selectFrom(FIELD)
                  .where(FIELD.ENTITY_ID.eq(entityId))
                  .orderBy(FIELD.FIELD_NAME)
                  .fetchInto(Field.class);
    }

    /**
     * Returns all fields, ordered by name.
     *
     * @return every field row, possibly empty
     */
    public List<Field> findAll() {
        return dsl.selectFrom(FIELD)
                  .orderBy(FIELD.FIELD_NAME)
                  .fetchInto(Field.class);
    }

    // -----------------------------------------------------------------------
    // Upsert
    // -----------------------------------------------------------------------

    /**
     * Inserts a new field or updates the existing one if a row with the same
     * {@code (field_name, entity_id)} pair already exists.
     *
     * <p>On conflict the {@code description}, {@code updated_at}, and
     * {@code updated_by} columns are updated; the original
     * {@code created_at}/{@code created_by} values are preserved.
     *
     * @param fieldName   the field name
     * @param entityId    the owning entity's ID, or {@code null} for an
     *                    entity-less field
     * @param description optional human-readable description (may be {@code null})
     * @param actor       the identity performing the operation (may be {@code null})
     */
    public void upsertByNameAndEntity(String fieldName, Long entityId, String description, String actor) {
        LocalDateTime now = LocalDateTime.now();

        if (entityId == null) {
            // PostgreSQL unique constraints treat NULL != NULL, so
            // ON CONFLICT(field_name, entity_id) never fires when
            // entity_id is NULL. Use UPDATE-or-INSERT instead.
            int updated = dsl.update(FIELD)
               .set(FIELD.DESCRIPTION, description)
               .set(FIELD.UPDATED_AT, now)
               .set(FIELD.UPDATED_BY, actor)
               .where(FIELD.FIELD_NAME.eq(fieldName))
               .and(FIELD.ENTITY_ID.isNull())
               .execute();
            if (updated == 0) {
                dsl.insertInto(FIELD)
                   .set(FIELD.FIELD_NAME, fieldName)
                   .set(FIELD.ENTITY_ID, (Long) null)
                   .set(FIELD.DESCRIPTION, description)
                   .set(FIELD.CREATED_AT, now)
                   .set(FIELD.CREATED_BY, actor)
                   .set(FIELD.UPDATED_AT, now)
                   .set(FIELD.UPDATED_BY, actor)
                   .execute();
            }
        } else {
            dsl.insertInto(FIELD)
               .set(FIELD.FIELD_NAME, fieldName)
               .set(FIELD.ENTITY_ID, entityId)
               .set(FIELD.DESCRIPTION, description)
               .set(FIELD.CREATED_AT, now)
               .set(FIELD.CREATED_BY, actor)
               .set(FIELD.UPDATED_AT, now)
               .set(FIELD.UPDATED_BY, actor)
               .onConflict(FIELD.FIELD_NAME, FIELD.ENTITY_ID)
               .doUpdate()
               .set(FIELD.DESCRIPTION, description)
               .set(FIELD.UPDATED_AT, now)
               .set(FIELD.UPDATED_BY, actor)
               .execute();
        }
    }
}
