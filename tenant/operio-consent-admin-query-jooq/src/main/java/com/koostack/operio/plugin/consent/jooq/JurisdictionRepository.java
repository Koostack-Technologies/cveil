package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.plugin.consent.jooq.generated.tables.daos.JurisdictionDao;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Jurisdiction;

import org.jooq.DSLContext;

import java.util.List;
import java.util.Optional;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.JURISDICTION;

/**
 * Read-only repository for the {@code jurisdiction} table.
 */
public class JurisdictionRepository {

    private final DSLContext dsl;
    private final JurisdictionDao dao;

    public JurisdictionRepository(DSLContext dsl) {
        this.dsl = dsl;
        this.dao = new JurisdictionDao(dsl.configuration());
    }

    /** Exposes the generated DAO for standard CRUD when needed. */
    public JurisdictionDao dao() {
        return dao;
    }

    public Optional<Jurisdiction> findByName(String jurisdictionName) {
        return dsl.selectFrom(JURISDICTION)
                  .where(JURISDICTION.JURISDICTION_NAME.eq(jurisdictionName))
                  .fetchOptionalInto(Jurisdiction.class);
    }

    public List<Jurisdiction> findAll() {
        return dsl.selectFrom(JURISDICTION)
                  .orderBy(JURISDICTION.JURISDICTION_NAME)
                  .fetchInto(Jurisdiction.class);
    }
}
