package com.koostack.cveil.plugin.consent.jooq;

import static com.koostack.cveil.plugin.consent.jooq.generated.Tables.PURPOSE;

import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.daos.PurposeDao;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.pojos.Purpose;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

/** Read-only repository for the {@code purpose} table. */
public class PurposeRepository {

    private final DSLContext dsl;
    private final PurposeDao dao;

    public PurposeRepository(DSLContext dsl) {
        this.dsl = dsl;
        this.dao = new PurposeDao(dsl.configuration());
    }

    /** Exposes the generated DAO for standard CRUD when needed. */
    public PurposeDao dao() {
        return dao;
    }

    public Optional<Purpose> findByName(String purposeName) {
        return dsl.selectFrom(PURPOSE)
                .where(PURPOSE.PURPOSE_NAME.eq(purposeName))
                .and(PURPOSE.TENANT_ID.eq(TenantContext.require()))
                .fetchOptionalInto(Purpose.class);
    }

    public List<Purpose> findAll() {
        return dsl.selectFrom(PURPOSE)
                .where(PURPOSE.TENANT_ID.eq(TenantContext.require()))
                .orderBy(PURPOSE.PURPOSE_NAME)
                .fetchInto(Purpose.class);
    }
}
