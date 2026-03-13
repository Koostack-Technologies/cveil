package com.koostack.operio.plugin.consent.jooq;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.FIELD;

import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.plugin.consent.jooq.generated.tables.daos.FieldDao;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Field;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

/** Read-only repository for the {@code field} table. */
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

    public List<Field> findByName(String fieldName) {
        return dsl.selectFrom(FIELD)
                .where(FIELD.FIELD_NAME.eq(fieldName))
                .and(FIELD.TENANT_ID.eq(TenantContext.require()))
                .fetchInto(Field.class);
    }

    public Optional<Field> findByNameAndEntity(String fieldName, Long entityId) {
        var condition = FIELD.FIELD_NAME.eq(fieldName);

        if (entityId != null) {
            condition = condition.and(FIELD.ENTITY_ID.eq(entityId));
        } else {
            condition = condition.and(FIELD.ENTITY_ID.isNull());
        }

        condition = condition.and(FIELD.TENANT_ID.eq(TenantContext.require()));

        return dsl.selectFrom(FIELD).where(condition).fetchOptionalInto(Field.class);
    }

    public List<Field> findByEntity(Long entityId) {
        return dsl.selectFrom(FIELD)
                .where(FIELD.ENTITY_ID.eq(entityId))
                .and(FIELD.TENANT_ID.eq(TenantContext.require()))
                .orderBy(FIELD.FIELD_NAME)
                .fetchInto(Field.class);
    }

    public List<Field> findAll() {
        return dsl.selectFrom(FIELD)
                .where(FIELD.TENANT_ID.eq(TenantContext.require()))
                .orderBy(FIELD.FIELD_NAME)
                .fetchInto(Field.class);
    }
}
