package com.koostack.cveil.plugin.consent.jooq;

import static com.koostack.cveil.plugin.consent.jooq.generated.Tables.PURPOSE_REGULATION;

import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.daos.PurposeRegulationDao;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.pojos.PurposeRegulation;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

/**
 * Repository for the {@code purpose_regulation} join table — the many-to-many assignment of
 * purposes to the regulations that govern them.
 *
 * <p>Wraps the generated {@link PurposeRegulationDao} for standard CRUD and adds domain-specific
 * link/unlink operations keyed on the unique {@code (purpose_id, regulation_id)} composite
 * constraint.
 */
public class PurposeRegulationRepository {

    private final DSLContext dsl;
    private final PurposeRegulationDao dao;

    public PurposeRegulationRepository(DSLContext dsl) {
        this.dsl = dsl;
        this.dao = new PurposeRegulationDao(dsl.configuration());
    }

    /** Exposes the generated DAO for standard CRUD when needed. */
    public PurposeRegulationDao dao() {
        return dao;
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /**
     * Returns every regulation link for the given purpose.
     *
     * @param purposeId the purpose identifier
     * @return all join rows for that purpose, possibly empty
     */
    public List<PurposeRegulation> findByPurpose(Long purposeId) {
        return dsl.selectFrom(PURPOSE_REGULATION)
                .where(PURPOSE_REGULATION.PURPOSE_ID.eq(purposeId))
                .and(PURPOSE_REGULATION.TENANT_ID.eq(TenantContext.require()))
                .fetchInto(PurposeRegulation.class);
    }

    /**
     * Returns every purpose link for the given regulation.
     *
     * @param regulationId the regulation identifier
     * @return all join rows for that regulation, possibly empty
     */
    public List<PurposeRegulation> findByRegulation(Long regulationId) {
        return dsl.selectFrom(PURPOSE_REGULATION)
                .where(PURPOSE_REGULATION.REGULATION_ID.eq(regulationId))
                .and(PURPOSE_REGULATION.TENANT_ID.eq(TenantContext.require()))
                .fetchInto(PurposeRegulation.class);
    }

    /**
     * Looks up the specific link between a purpose and a regulation.
     *
     * @param purposeId the purpose identifier
     * @param regulationId the regulation identifier
     * @return the matching join row, or empty if the link does not exist
     */
    public Optional<PurposeRegulation> findByPurposeAndRegulation(
            Long purposeId, Long regulationId) {
        return dsl.selectFrom(PURPOSE_REGULATION)
                .where(PURPOSE_REGULATION.PURPOSE_ID.eq(purposeId))
                .and(PURPOSE_REGULATION.REGULATION_ID.eq(regulationId))
                .and(PURPOSE_REGULATION.TENANT_ID.eq(TenantContext.require()))
                .fetchOptionalInto(PurposeRegulation.class);
    }

    /**
     * Returns all purpose-regulation links, ordered by purpose then regulation.
     *
     * @return every join row, possibly empty
     */
    public List<PurposeRegulation> findAll() {
        return dsl.selectFrom(PURPOSE_REGULATION)
                .where(PURPOSE_REGULATION.TENANT_ID.eq(TenantContext.require()))
                .orderBy(PURPOSE_REGULATION.PURPOSE_ID, PURPOSE_REGULATION.REGULATION_ID)
                .fetchInto(PurposeRegulation.class);
    }

    // -----------------------------------------------------------------------
    // Link / Unlink
    // -----------------------------------------------------------------------

    /**
     * Creates the link between a purpose and a regulation if it does not already exist. If the link
     * already exists, the columns are updated.
     *
     * <p>Uses an {@code INSERT ... ON CONFLICT DO UPDATE} so the call is safely idempotent.
     *
     * @param purposeId the purpose to link
     * @param regulationId the regulation to link
     * @param lawfulBasis the lawful basis for this purpose under this regulation (e.g. "consent",
     *     "legitimate interest"), or {@code null}
     * @param onGranted the masking type applied when consent is granted, or {@code null} for the
     *     database default (PLAINTEXT)
     * @param onDenied the masking type applied when consent is denied, or {@code null} for the
     *     database default (REDACT_ALL)
     * @param actor the identity performing the operation (may be {@code null})
     */
    public void link(
            Long purposeId,
            Long regulationId,
            String lawfulBasis,
            String onGranted,
            String onDenied,
            String actor) {
        LocalDateTime now = LocalDateTime.now();

        var insert =
                dsl.insertInto(PURPOSE_REGULATION)
                        .set(PURPOSE_REGULATION.PURPOSE_ID, purposeId)
                        .set(PURPOSE_REGULATION.REGULATION_ID, regulationId)
                        .set(PURPOSE_REGULATION.LAWFUL_BASIS, lawfulBasis)
                        .set(PURPOSE_REGULATION.CREATED_AT, now)
                        .set(PURPOSE_REGULATION.CREATED_BY, actor)
                        .set(PURPOSE_REGULATION.UPDATED_AT, now)
                        .set(PURPOSE_REGULATION.UPDATED_BY, actor);

        if (onGranted != null) {
            insert.set(
                    PURPOSE_REGULATION.ON_GRANTED,
                    com.koostack.cveil.plugin.consent.jooq.generated.enums.MaskingType
                            .lookupLiteral(onGranted));
        }
        if (onDenied != null) {
            insert.set(
                    PURPOSE_REGULATION.ON_DENIED,
                    com.koostack.cveil.plugin.consent.jooq.generated.enums.MaskingType
                            .lookupLiteral(onDenied));
        }

        insert.onConflict(PURPOSE_REGULATION.PURPOSE_ID, PURPOSE_REGULATION.REGULATION_ID)
                .doUpdate()
                .set(PURPOSE_REGULATION.LAWFUL_BASIS, lawfulBasis)
                .set(PURPOSE_REGULATION.UPDATED_AT, now)
                .set(PURPOSE_REGULATION.UPDATED_BY, actor)
                .execute();
    }

    /**
     * Removes the link between a purpose and a regulation.
     *
     * <p>If the link does not exist this is a no-op.
     *
     * @param purposeId the purpose to unlink
     * @param regulationId the regulation to unlink
     */
    public void unlink(Long purposeId, Long regulationId) {
        dsl.deleteFrom(PURPOSE_REGULATION)
                .where(PURPOSE_REGULATION.PURPOSE_ID.eq(purposeId))
                .and(PURPOSE_REGULATION.REGULATION_ID.eq(regulationId))
                .execute();
    }

    /**
     * Removes all regulation assignments for the given purpose.
     *
     * @param purposeId the purpose whose links should be removed
     * @return the number of rows deleted
     */
    public int unlinkAllByPurpose(Long purposeId) {
        return dsl.deleteFrom(PURPOSE_REGULATION)
                .where(PURPOSE_REGULATION.PURPOSE_ID.eq(purposeId))
                .execute();
    }

    /**
     * Removes all purpose assignments for the given regulation.
     *
     * @param regulationId the regulation whose links should be removed
     * @return the number of rows deleted
     */
    public int unlinkAllByRegulation(Long regulationId) {
        return dsl.deleteFrom(PURPOSE_REGULATION)
                .where(PURPOSE_REGULATION.REGULATION_ID.eq(regulationId))
                .execute();
    }
}
