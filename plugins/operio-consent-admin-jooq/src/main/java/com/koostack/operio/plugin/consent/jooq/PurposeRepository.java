package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.plugin.consent.jooq.generated.tables.daos.PurposeDao;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Purpose;

import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.PURPOSE;

/**
 * Repository for the {@code purpose} table — first-class consent purposes
 * (e.g. "marketing", "analytics", "treatment", "operations").
 *
 * <p>Wraps the generated {@link PurposeDao} for standard CRUD and adds
 * domain-specific lookups and an upsert keyed on the unique
 * {@code purpose_name} column.
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

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /**
     * Looks up a purpose by its unique name.
     *
     * @param purposeName the purpose name to search for
     * @return the matching purpose, or empty if none exists
     */
    public Optional<Purpose> findByName(String purposeName) {
        return dsl.selectFrom(PURPOSE)
                  .where(PURPOSE.PURPOSE_NAME.eq(purposeName))
                  .fetchOptionalInto(Purpose.class);
    }

    /**
     * Returns all purposes, ordered by name.
     *
     * @return every purpose row, possibly empty
     */
    public List<Purpose> findAll() {
        return dsl.selectFrom(PURPOSE)
                  .orderBy(PURPOSE.PURPOSE_NAME)
                  .fetchInto(Purpose.class);
    }

    /**
     * Looks up a purpose by its database ID.
     *
     * @param purposeId the purpose identifier
     * @return the matching purpose, or empty if none exists
     */
    public Optional<Purpose> findById(Long purposeId) {
        return dsl.selectFrom(PURPOSE)
                  .where(PURPOSE.PURPOSE_ID.eq(purposeId))
                  .fetchOptionalInto(Purpose.class);
    }

    // -----------------------------------------------------------------------
    // Upsert
    // -----------------------------------------------------------------------

    /**
     * Inserts a new purpose or updates the existing one if a row with the
     * same {@code purpose_name} already exists.
     *
     * <p>On conflict the {@code description}, {@code updated_at}, and
     * {@code updated_by} columns are updated; the original
     * {@code created_at}/{@code created_by} values are preserved.
     *
     * @param purposeName the unique purpose name
     * @param description optional human-readable description (may be {@code null})
     * @param actor       the identity performing the operation (may be {@code null})
     */
    public void upsertByName(String purposeName, String description, String actor) {
        LocalDateTime now = LocalDateTime.now();

        dsl.insertInto(PURPOSE)
           .set(PURPOSE.PURPOSE_NAME, purposeName)
           .set(PURPOSE.DESCRIPTION, description)
           .set(PURPOSE.CREATED_AT, now)
           .set(PURPOSE.CREATED_BY, actor)
           .set(PURPOSE.UPDATED_AT, now)
           .set(PURPOSE.UPDATED_BY, actor)
           .onConflict(PURPOSE.PURPOSE_NAME)
           .doUpdate()
           .set(PURPOSE.DESCRIPTION, description)
           .set(PURPOSE.UPDATED_AT, now)
           .set(PURPOSE.UPDATED_BY, actor)
           .execute();
    }
}
