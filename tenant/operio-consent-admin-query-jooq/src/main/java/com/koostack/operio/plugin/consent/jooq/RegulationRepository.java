package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.plugin.consent.jooq.generated.tables.daos.RegulationDao;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Regulation;

import org.jooq.DSLContext;

import java.util.List;
import java.util.Optional;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.REGULATION;

/**
 * Read-only repository for the {@code regulation} table.
 */
public class RegulationRepository {

    private final DSLContext dsl;
    private final RegulationDao dao;

    public RegulationRepository(DSLContext dsl) {
        this.dsl = dsl;
        this.dao = new RegulationDao(dsl.configuration());
    }

    /** Exposes the generated DAO for standard CRUD when needed. */
    public RegulationDao dao() {
        return dao;
    }

    public Optional<Regulation> findByName(String regulationName) {
        return dsl.selectFrom(REGULATION)
                  .where(REGULATION.REGULATION_NAME.eq(regulationName))
                  .and(REGULATION.TENANT_ID.eq(TenantContext.require()))
                  .fetchOptionalInto(Regulation.class);
    }

    public List<Regulation> findAll() {
        return dsl.selectFrom(REGULATION)
                  .where(REGULATION.TENANT_ID.eq(TenantContext.require()))
                  .orderBy(REGULATION.REGULATION_NAME)
                  .fetchInto(Regulation.class);
    }

    public Optional<Regulation> findById(Long regulationId) {
        return dsl.selectFrom(REGULATION)
                  .where(REGULATION.REGULATION_ID.eq(regulationId))
                  .and(REGULATION.TENANT_ID.eq(TenantContext.require()))
                  .fetchOptionalInto(Regulation.class);
    }
}
