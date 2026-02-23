package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.plugin.consent.jooq.generated.tables.daos.RegulationDao;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Regulation;

import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.REGULATION;

/**
 * Repository for the {@code regulation} table — legal frameworks that govern
 * consent purposes (e.g. GDPR, CCPA, HIPAA, LGPD).
 *
 * <p>Wraps the generated {@link RegulationDao} for standard CRUD and adds
 * domain-specific lookups and an upsert keyed on the unique
 * {@code regulation_name} column.
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

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /**
     * Looks up a regulation by its unique name.
     *
     * @param regulationName the regulation name to search for
     * @return the matching regulation, or empty if none exists
     */
    public Optional<Regulation> findByName(String regulationName) {
        return dsl.selectFrom(REGULATION)
                  .where(REGULATION.REGULATION_NAME.eq(regulationName))
                  .fetchOptionalInto(Regulation.class);
    }

    /**
     * Returns all regulations, ordered by name.
     *
     * @return every regulation row, possibly empty
     */
    public List<Regulation> findAll() {
        return dsl.selectFrom(REGULATION)
                  .orderBy(REGULATION.REGULATION_NAME)
                  .fetchInto(Regulation.class);
    }

    /**
     * Looks up a regulation by its database ID.
     *
     * @param regulationId the regulation identifier
     * @return the matching regulation, or empty if none exists
     */
    public Optional<Regulation> findById(Long regulationId) {
        return dsl.selectFrom(REGULATION)
                  .where(REGULATION.REGULATION_ID.eq(regulationId))
                  .fetchOptionalInto(Regulation.class);
    }

    // -----------------------------------------------------------------------
    // Upsert
    // -----------------------------------------------------------------------

    /**
     * Inserts a new regulation or updates the existing one if a row with the
     * same {@code regulation_name} already exists.
     *
     * <p>On conflict the {@code full_name}, {@code jurisdiction},
     * {@code description}, {@code url}, {@code updated_at}, and
     * {@code updated_by} columns are updated; the original
     * {@code created_at}/{@code created_by} values are preserved.
     *
     * @param regulationName the unique regulation name (e.g. "GDPR")
     * @param fullName       the full legal name (e.g. "General Data Protection Regulation"),
     *                       or {@code null}
     * @param jurisdiction   the geographic jurisdiction (e.g. "EU"), or {@code null}
     * @param description    optional human-readable description (may be {@code null})
     * @param url            a reference URL to the regulation text, or {@code null}
     * @param actor          the identity performing the operation (may be {@code null})
     */
    public void upsertByName(
        String regulationName,
        String fullName,
        String jurisdiction,
        String description,
        String url,
        String actor
    ) {
        LocalDateTime now = LocalDateTime.now();

        dsl.insertInto(REGULATION)
           .set(REGULATION.REGULATION_NAME, regulationName)
           .set(REGULATION.FULL_NAME, fullName)
           .set(REGULATION.JURISDICTION, jurisdiction)
           .set(REGULATION.DESCRIPTION, description)
           .set(REGULATION.URL, url)
           .set(REGULATION.CREATED_AT, now)
           .set(REGULATION.CREATED_BY, actor)
           .set(REGULATION.UPDATED_AT, now)
           .set(REGULATION.UPDATED_BY, actor)
           .onConflict(REGULATION.REGULATION_NAME)
           .doUpdate()
           .set(REGULATION.FULL_NAME, fullName)
           .set(REGULATION.JURISDICTION, jurisdiction)
           .set(REGULATION.DESCRIPTION, description)
           .set(REGULATION.URL, url)
           .set(REGULATION.UPDATED_AT, now)
           .set(REGULATION.UPDATED_BY, actor)
           .execute();
    }
}
