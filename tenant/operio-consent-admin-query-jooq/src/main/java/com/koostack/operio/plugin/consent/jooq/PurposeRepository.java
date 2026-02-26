package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.plugin.consent.jooq.generated.tables.daos.PurposeDao;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Purpose;

import org.jooq.DSLContext;

import java.util.List;
import java.util.Optional;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.PURPOSE;

/**
 * Read-only repository for the {@code purpose} table.
 */
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
                  .fetchOptionalInto(Purpose.class);
    }

    public List<Purpose> findAll() {
        return dsl.selectFrom(PURPOSE)
                  .orderBy(PURPOSE.PURPOSE_NAME)
                  .fetchInto(Purpose.class);
    }

    public Optional<Purpose> findById(Long purposeId) {
        return dsl.selectFrom(PURPOSE)
                  .where(PURPOSE.PURPOSE_ID.eq(purposeId))
                  .fetchOptionalInto(Purpose.class);
    }
}
