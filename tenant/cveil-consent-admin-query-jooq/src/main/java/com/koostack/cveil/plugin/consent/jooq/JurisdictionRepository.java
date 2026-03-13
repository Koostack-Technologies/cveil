package com.koostack.cveil.plugin.consent.jooq;

import static com.koostack.cveil.plugin.consent.jooq.generated.Tables.JURISDICTION;

import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.daos.JurisdictionDao;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.pojos.Jurisdiction;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

/** Read-only repository for the {@code jurisdiction} table. */
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
                .and(JURISDICTION.TENANT_ID.eq(TenantContext.require()))
                .fetchOptionalInto(Jurisdiction.class);
    }

    public List<Jurisdiction> findAll() {
        return dsl.selectFrom(JURISDICTION)
                .where(JURISDICTION.TENANT_ID.eq(TenantContext.require()))
                .orderBy(JURISDICTION.JURISDICTION_NAME)
                .fetchInto(Jurisdiction.class);
    }
}
