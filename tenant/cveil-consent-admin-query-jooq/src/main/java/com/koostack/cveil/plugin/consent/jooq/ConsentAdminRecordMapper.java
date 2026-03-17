package com.koostack.cveil.plugin.consent.jooq;

import static com.koostack.cveil.plugin.consent.jooq.generated.Tables.*;

import com.koostack.cveil.core.models.*;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.pojos.Field;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.pojos.Purpose;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.pojos.Regulation;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.pojos.Subject;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * Shared mapping utility for the consent admin query actuators.
 *
 * <p>Holds infrastructure references needed by the complex aggregate view builders and provides
 * reusable conversion methods extracted from {@link JooqConsentAdminQueryService}.
 */
public class ConsentAdminRecordMapper {

    private final DSLContext dsl;
    private final EntityRepository entityRepository;
    private final PolicyRepository policyRepository;

    public ConsentAdminRecordMapper(
            DSLContext dsl, EntityRepository entityRepository, PolicyRepository policyRepository) {
        this.dsl = dsl;
        this.entityRepository = entityRepository;
        this.policyRepository = policyRepository;
    }

    // -- Field aggregate -------------------------------------------------------

    public FieldView toFieldView(Field f) {
        FieldView.EntityView entityView = null;
        if (f.getEntityUuid() != null) {
            entityView =
                    dsl.selectFrom(ENTITY)
                            .where(ENTITY.ENTITY_UUID.eq(f.getEntityUuid()))
                            .fetchOptionalInto(
                                    com.koostack.cveil.plugin.consent.jooq.generated.tables.pojos
                                            .Entity.class)
                            .map(
                                    e ->
                                            new FieldView.EntityView(
                                                    e.getEntityUuid(), e.getEntityName()))
                            .orElse(null);
        }

        var fieldCategories =
                dsl.select(CATEGORY.CATEGORY_UUID, CATEGORY.CATEGORY_NAME)
                        .from(FIELD_CATEGORY)
                        .join(CATEGORY)
                        .on(FIELD_CATEGORY.CATEGORY_UUID.eq(CATEGORY.CATEGORY_UUID))
                        .where(FIELD_CATEGORY.FIELD_UUID.eq(f.getFieldUuid()))
                        .and(FIELD_CATEGORY.TENANT_ID.eq(TenantContext.require()))
                        .fetch();

        List<FieldView.CategoryView> categories =
                fieldCategories.stream()
                        .map(
                                r ->
                                        new FieldView.CategoryView(
                                                r.get(CATEGORY.CATEGORY_UUID),
                                                r.get(CATEGORY.CATEGORY_NAME)))
                        .toList();

        return new FieldView(
                f.getFieldUuid(), f.getFieldName(), f.getDescription(), entityView, categories);
    }

    // -- Purpose aggregate -----------------------------------------------------

    public PurposeView toPurposeView(Purpose p) {
        var regulationLinks =
                dsl.select(
                                REGULATION.REGULATION_NAME,
                                PURPOSE_REGULATION.LAWFUL_BASIS,
                                PURPOSE_REGULATION.ON_GRANTED,
                                PURPOSE_REGULATION.ON_DENIED)
                        .from(PURPOSE_REGULATION)
                        .join(REGULATION)
                        .on(PURPOSE_REGULATION.REGULATION_UUID.eq(REGULATION.REGULATION_UUID))
                        .where(PURPOSE_REGULATION.PURPOSE_UUID.eq(p.getPurposeUuid()))
                        .and(PURPOSE_REGULATION.TENANT_ID.eq(TenantContext.require()))
                        .fetch();

        List<PurposeView.RegulationLinkView> regs =
                regulationLinks.stream()
                        .map(
                                r ->
                                        new PurposeView.RegulationLinkView(
                                                r.get(REGULATION.REGULATION_NAME),
                                                r.get(PURPOSE_REGULATION.LAWFUL_BASIS),
                                                r.get(PURPOSE_REGULATION.ON_GRANTED) != null
                                                        ? r.get(PURPOSE_REGULATION.ON_GRANTED)
                                                                .toString()
                                                        : null,
                                                r.get(PURPOSE_REGULATION.ON_DENIED) != null
                                                        ? r.get(PURPOSE_REGULATION.ON_DENIED)
                                                                .toString()
                                                        : null))
                        .toList();

        var policyRecords = policyRepository.findByPurpose(p.getPurposeUuid());
        List<PurposeView.PolicyView> policies =
                policyRecords.stream()
                        .map(
                                r -> {
                                    UUID policyId = r.get(POLICY.POLICY_UUID);
                                    String policyType =
                                            r.get(POLICY.POLICY_TYPE) != null
                                                    ? r.get(POLICY.POLICY_TYPE).toString()
                                                    : null;

                                    PurposeView.CategoryRef catRef = null;
                                    PurposeView.FieldRef fieldRef = null;

                                    if (r.get(CATEGORY.CATEGORY_NAME) != null) {
                                        catRef =
                                                new PurposeView.CategoryRef(
                                                        r.get(POLICY.CATEGORY_UUID),
                                                        r.get(CATEGORY.CATEGORY_NAME));
                                    }
                                    if (r.get(FIELD.FIELD_NAME) != null) {
                                        fieldRef =
                                                new PurposeView.FieldRef(
                                                        r.get(POLICY.FIELD_UUID),
                                                        r.get(FIELD.FIELD_NAME),
                                                        r.get(ENTITY.ENTITY_NAME));
                                    }

                                    return new PurposeView.PolicyView(
                                            policyId, policyType, catRef, fieldRef);
                                })
                        .toList();

        return new PurposeView(
                p.getPurposeUuid(), p.getPurposeName(), p.getDescription(), regs, policies);
    }

    // -- Regulation aggregate --------------------------------------------------

    public RegulationView toRegulationView(Regulation r) {
        var jurisdictions =
                dsl.select(JURISDICTION.JURISDICTION_UUID, JURISDICTION.JURISDICTION_NAME)
                        .from(REGULATION_JURISDICTION)
                        .join(JURISDICTION)
                        .on(
                                REGULATION_JURISDICTION.JURISDICTION_UUID.eq(
                                        JURISDICTION.JURISDICTION_UUID))
                        .where(REGULATION_JURISDICTION.REGULATION_UUID.eq(r.getRegulationUuid()))
                        .and(REGULATION_JURISDICTION.TENANT_ID.eq(TenantContext.require()))
                        .fetch();

        List<RegulationView.JurisdictionView> juris =
                jurisdictions.stream()
                        .map(
                                j ->
                                        new RegulationView.JurisdictionView(
                                                j.get(JURISDICTION.JURISDICTION_UUID),
                                                j.get(JURISDICTION.JURISDICTION_NAME)))
                        .toList();

        return new RegulationView(
                r.getRegulationUuid(), r.getRegulationName(), r.getDescription(), juris);
    }

    // -- Subject ---------------------------------------------------------------

    public SubjectView toSubjectView(Subject s) {
        return new SubjectView(
                s.getSubjectUuid(),
                s.getSubjectName(),
                s.getDescription(),
                toInstant(s.getCreatedAt()),
                s.getCreatedBy(),
                toInstant(s.getUpdatedAt()),
                s.getUpdatedBy());
    }

    // -- Consent ledger entry --------------------------------------------------

    public SubjectConsentView.ConsentLedgerEntryView toLedgerEntryView(Record r, String subjectId) {
        return new SubjectConsentView.ConsentLedgerEntryView(
                r.get(CONSENT_LEDGER.CONSENT_LEDGER_UUID),
                subjectId,
                r.get(CONSENT_LEDGER.POLICY_UUID),
                r.get(PURPOSE.PURPOSE_NAME),
                r.get(POLICY.POLICY_TYPE) != null ? r.get(POLICY.POLICY_TYPE).getLiteral() : null,
                r.get(FIELD.FIELD_NAME),
                r.get(ENTITY.ENTITY_NAME),
                r.get(CATEGORY.CATEGORY_NAME),
                r.get(CONSENT_LEDGER.ACTION) != null
                        ? r.get(CONSENT_LEDGER.ACTION).getLiteral()
                        : null,
                r.get(CONSENT_LEDGER.ACTOR),
                r.get(CONSENT_LEDGER.IP_ADDRESS),
                r.get(CONSENT_LEDGER.USER_AGENT),
                toInstant(r.get(CONSENT_LEDGER.CREATED_AT)),
                toInstant(r.get(CONSENT_LEDGER.EXPIRES_AT)));
    }

    // -- Helpers ---------------------------------------------------------------

    public static Instant toInstant(LocalDateTime ldt) {
        return ldt != null ? ldt.toInstant(ZoneOffset.UTC) : null;
    }
}
