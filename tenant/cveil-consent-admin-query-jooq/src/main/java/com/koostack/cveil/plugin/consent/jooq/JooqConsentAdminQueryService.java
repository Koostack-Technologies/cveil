package com.koostack.cveil.plugin.consent.jooq;

import static com.koostack.cveil.plugin.consent.jooq.generated.Tables.*;

import com.koostack.cveil.core.exception.CveilConfigurationException;
import com.koostack.cveil.core.models.*;
import com.koostack.cveil.core.tenant.consent.ConsentAdminQueryService;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.pojos.Entity;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.pojos.Field;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.pojos.Purpose;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.pojos.Regulation;
import com.koostack.cveil.plugin.jooq.common.JooqContextFactory;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * jOOQ-backed {@link ConsentAdminQueryService} implementation.
 *
 * <p>Returns aggregate-shaped view models for all 5 consent aggregates: Field, Purpose, Regulation,
 * SubjectConsent, and DSAR.
 */
public class JooqConsentAdminQueryService
        implements ConsentAdminQueryService<JooqConsentAdminServiceConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqConsentAdminQueryService.class);

    private DSLContext dsl;
    private EntityRepository entityRepository;
    private FieldRepository fieldRepository;
    private CategoryRepository categoryRepository;
    private FieldCategoryRepository fieldCategoryRepository;
    private PurposeRepository purposeRepository;
    private RegulationRepository regulationRepository;
    private JurisdictionRepository jurisdictionRepository;
    private PolicyRepository policyRepository;
    private ConsentLedgerRepository consentLedgerRepository;
    private SubjectRepository subjectRepository;

    private DSLContext getDsl() {
        if (dsl == null) {
            throw new CveilConfigurationException(
                    "JooqConsentAdminQueryService not configured. Call configure() first.");
        }
        return dsl;
    }

    @Override
    public boolean isReady() {
        if (dsl == null) return false;
        try {
            dsl.selectOne().fetch();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Plugin SPI
    // -----------------------------------------------------------------------

    @Override
    public String getName() {
        return "jooq-consent-admin-query";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<JooqConsentAdminServiceConfig> getConfigurationType() {
        return JooqConsentAdminServiceConfig.class;
    }

    @Override
    public void configure(JooqConsentAdminServiceConfig config) {
        this.dsl =
                JooqContextFactory.createTenantAwareContext(
                        config, "cveil-consent-admin-query-jooq");
        this.entityRepository = new EntityRepository(dsl);
        this.fieldRepository = new FieldRepository(dsl);
        this.categoryRepository = new CategoryRepository(dsl);
        this.fieldCategoryRepository = new FieldCategoryRepository(dsl);
        this.purposeRepository = new PurposeRepository(dsl);
        this.regulationRepository = new RegulationRepository(dsl);
        this.jurisdictionRepository = new JurisdictionRepository(dsl);
        this.policyRepository = new PolicyRepository(dsl);
        this.consentLedgerRepository = new ConsentLedgerRepository(dsl);
        this.subjectRepository = new SubjectRepository(dsl);
    }

    // -----------------------------------------------------------------------
    // Entity Queries
    // -----------------------------------------------------------------------

    @Override
    public List<EntityView> listEntities() {
        getDsl();
        return entityRepository.findAll().stream()
                .map(e -> new EntityView(e.getEntityUuid(), e.getEntityName(), e.getDescription()))
                .toList();
    }

    @Override
    public Optional<EntityView> getEntity(String entityName) {
        getDsl();
        return entityRepository
                .findByName(entityName)
                .map(e -> new EntityView(e.getEntityUuid(), e.getEntityName(), e.getDescription()));
    }

    // -----------------------------------------------------------------------
    // Category Queries
    // -----------------------------------------------------------------------

    @Override
    public List<CategoryView> listCategories() {
        getDsl();
        return categoryRepository.findAll().stream()
                .map(
                        c ->
                                new CategoryView(
                                        c.getCategoryUuid(),
                                        c.getCategoryName(),
                                        c.getDescription()))
                .toList();
    }

    @Override
    public Optional<CategoryView> getCategory(String categoryName) {
        getDsl();
        return categoryRepository
                .findByName(categoryName)
                .map(
                        c ->
                                new CategoryView(
                                        c.getCategoryUuid(),
                                        c.getCategoryName(),
                                        c.getDescription()));
    }

    // -----------------------------------------------------------------------
    // Field Aggregate Queries
    // -----------------------------------------------------------------------

    @Override
    public List<FieldView> listFields() {
        getDsl();
        List<Field> fields = fieldRepository.findAll();
        return fields.stream().map(this::toFieldView).toList();
    }

    @Override
    public FieldView getField(String fieldName, String entityName) {
        getDsl();
        Optional<Entity> entity = entityRepository.findByName(entityName);
        if (entity.isEmpty()) return null;
        Optional<Field> field =
                fieldRepository.findByNameAndEntity(fieldName, entity.get().getEntityUuid());
        return field.map(this::toFieldView).orElse(null);
    }

    private FieldView toFieldView(Field f) {
        FieldView.EntityView entityView = null;
        if (f.getEntityUuid() != null) {
            entityView =
                    getDsl().selectFrom(ENTITY)
                            .where(ENTITY.ENTITY_UUID.eq(f.getEntityUuid()))
                            .fetchOptionalInto(Entity.class)
                            .map(
                                    e ->
                                            new FieldView.EntityView(
                                                    e.getEntityUuid(), e.getEntityName()))
                            .orElse(null);
        }

        // Get categories for this field
        var fieldCategories =
                getDsl().select(CATEGORY.CATEGORY_UUID, CATEGORY.CATEGORY_NAME)
                        .from(FIELD_CATEGORY)
                        .join(CATEGORY)
                        .on(FIELD_CATEGORY.CATEGORY_UUID.eq(CATEGORY.CATEGORY_UUID))
                        .where(FIELD_CATEGORY.FIELD_UUID.eq(f.getFieldUuid()))
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

    // -----------------------------------------------------------------------
    // Purpose Aggregate Queries
    // -----------------------------------------------------------------------

    @Override
    public List<PurposeView> listPurposes() {
        getDsl();
        List<Purpose> purposes = purposeRepository.findAll();
        return purposes.stream().map(this::toPurposeView).toList();
    }

    @Override
    public PurposeView getPurpose(String purposeName) {
        getDsl();
        return purposeRepository.findByName(purposeName).map(this::toPurposeView).orElse(null);
    }

    private PurposeView toPurposeView(Purpose p) {
        // Purpose ↔ Regulation links
        var regulationLinks =
                getDsl().select(
                                REGULATION.REGULATION_NAME,
                                PURPOSE_REGULATION.LAWFUL_BASIS,
                                PURPOSE_REGULATION.ON_GRANTED,
                                PURPOSE_REGULATION.ON_DENIED)
                        .from(PURPOSE_REGULATION)
                        .join(REGULATION)
                        .on(PURPOSE_REGULATION.REGULATION_UUID.eq(REGULATION.REGULATION_UUID))
                        .where(PURPOSE_REGULATION.PURPOSE_UUID.eq(p.getPurposeUuid()))
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

        // Policies under this purpose
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

    // -----------------------------------------------------------------------
    // Regulation Aggregate Queries
    // -----------------------------------------------------------------------

    @Override
    public List<RegulationView> listRegulations() {
        getDsl();
        List<Regulation> regulations = regulationRepository.findAll();
        return regulations.stream().map(this::toRegulationView).toList();
    }

    @Override
    public RegulationView getRegulation(String regulationName) {
        getDsl();
        return regulationRepository
                .findByName(regulationName)
                .map(this::toRegulationView)
                .orElse(null);
    }

    private RegulationView toRegulationView(Regulation r) {
        var jurisdictions =
                getDsl().select(JURISDICTION.JURISDICTION_UUID, JURISDICTION.JURISDICTION_NAME)
                        .from(REGULATION_JURISDICTION)
                        .join(JURISDICTION)
                        .on(
                                REGULATION_JURISDICTION.JURISDICTION_UUID.eq(
                                        JURISDICTION.JURISDICTION_UUID))
                        .where(REGULATION_JURISDICTION.REGULATION_UUID.eq(r.getRegulationUuid()))
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

    // -----------------------------------------------------------------------
    // Jurisdiction Queries
    // -----------------------------------------------------------------------

    @Override
    public List<JurisdictionView> listJurisdictions() {
        getDsl();
        return jurisdictionRepository.findAll().stream()
                .map(
                        j ->
                                new JurisdictionView(
                                        j.getJurisdictionUuid(),
                                        j.getJurisdictionName(),
                                        j.getDescription()))
                .toList();
    }

    @Override
    public Optional<JurisdictionView> getJurisdiction(String jurisdictionName) {
        getDsl();
        return jurisdictionRepository
                .findByName(jurisdictionName)
                .map(
                        j ->
                                new JurisdictionView(
                                        j.getJurisdictionUuid(),
                                        j.getJurisdictionName(),
                                        j.getDescription()));
    }

    // -----------------------------------------------------------------------
    // Subject Queries
    // -----------------------------------------------------------------------

    @Override
    public List<SubjectView> listSubjects() {
        getDsl();
        return subjectRepository.findAll().stream()
                .map(
                        s ->
                                new SubjectView(
                                        s.getSubjectUuid(),
                                        s.getSubjectName(),
                                        s.getDescription(),
                                        toInstant(s.getCreatedAt()),
                                        s.getCreatedBy(),
                                        toInstant(s.getUpdatedAt()),
                                        s.getUpdatedBy()))
                .toList();
    }

    @Override
    public Optional<SubjectView> getSubject(String subjectName) {
        getDsl();
        return subjectRepository
                .findByName(subjectName)
                .map(
                        s ->
                                new SubjectView(
                                        s.getSubjectUuid(),
                                        s.getSubjectName(),
                                        s.getDescription(),
                                        toInstant(s.getCreatedAt()),
                                        s.getCreatedBy(),
                                        toInstant(s.getUpdatedAt()),
                                        s.getUpdatedBy()));
    }

    // -----------------------------------------------------------------------
    // SubjectConsent Queries
    // -----------------------------------------------------------------------

    @Override
    public SubjectConsentView getSubjectConsent(String subjectId) {
        getDsl();
        var records = consentLedgerRepository.findBySubject(subjectId);

        List<SubjectConsentView.ConsentLedgerEntryView> entries =
                records.stream()
                        .map(
                                r ->
                                        new SubjectConsentView.ConsentLedgerEntryView(
                                                r.get(CONSENT_LEDGER.CONSENT_LEDGER_UUID),
                                                subjectId,
                                                r.get(CONSENT_LEDGER.POLICY_UUID),
                                                r.get(PURPOSE.PURPOSE_NAME),
                                                r.get(POLICY.POLICY_TYPE) != null
                                                        ? r.get(POLICY.POLICY_TYPE).getLiteral()
                                                        : null,
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
                                                toInstant(r.get(CONSENT_LEDGER.EXPIRES_AT))))
                        .toList();

        return new SubjectConsentView(subjectId, entries);
    }

    @Override
    public int countSubjectConsentEntries(String subjectId) {
        getDsl();
        return getDsl().selectCount()
                .from(CONSENT_LEDGER)
                .where(CONSENT_LEDGER.SUBJECT_ID.eq(subjectId))
                .fetchOneInto(int.class);
    }

    // -----------------------------------------------------------------------
    // Expiration Queries
    // -----------------------------------------------------------------------

    @Override
    public List<SubjectConsentView.ConsentLedgerEntryView> findExpiredGrants(Instant cutoff) {
        getDsl();
        var records =
                consentLedgerRepository.findExpiredGrants(
                        java.time.LocalDateTime.ofInstant(cutoff, java.time.ZoneOffset.UTC));
        return records.stream()
                .map(
                        r ->
                                new SubjectConsentView.ConsentLedgerEntryView(
                                        r.get(CONSENT_LEDGER.CONSENT_LEDGER_UUID),
                                        r.get(CONSENT_LEDGER.SUBJECT_ID),
                                        r.get(CONSENT_LEDGER.POLICY_UUID),
                                        r.get(PURPOSE.PURPOSE_NAME),
                                        r.get(POLICY.POLICY_TYPE) != null
                                                ? r.get(POLICY.POLICY_TYPE).getLiteral()
                                                : null,
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
                                        toInstant(r.get(CONSENT_LEDGER.EXPIRES_AT))))
                .toList();
    }

    // -----------------------------------------------------------------------
    // Policy Lookup (for TenantService enrichment)
    // -----------------------------------------------------------------------

    @Override
    public PurposeView.PolicyView findPolicy(
            String purposeName,
            String policyType,
            String fieldName,
            String entityName,
            String categoryName) {
        getDsl();

        if ("FIELD".equals(policyType)) {
            var opt = policyRepository.findFieldPolicy(purposeName, fieldName, entityName);
            return opt.map(
                            r ->
                                    new PurposeView.PolicyView(
                                            r.get(POLICY.POLICY_UUID),
                                            r.get(POLICY.POLICY_TYPE) != null
                                                    ? r.get(POLICY.POLICY_TYPE).toString()
                                                    : "FIELD",
                                            null,
                                            new PurposeView.FieldRef(
                                                    r.get(POLICY.FIELD_UUID),
                                                    fieldName,
                                                    entityName)))
                    .orElse(null);
        } else if ("CATEGORY".equals(policyType)) {
            var opt = policyRepository.findCategoryPolicy(purposeName, categoryName);
            return opt.map(
                            r ->
                                    new PurposeView.PolicyView(
                                            r.get(POLICY.POLICY_UUID),
                                            r.get(POLICY.POLICY_TYPE) != null
                                                    ? r.get(POLICY.POLICY_TYPE).toString()
                                                    : "CATEGORY",
                                            new PurposeView.CategoryRef(
                                                    r.get(POLICY.CATEGORY_UUID), categoryName),
                                            null))
                    .orElse(null);
        }

        return null;
    }

    // -----------------------------------------------------------------------
    // Repository Accessors
    // -----------------------------------------------------------------------

    public EntityRepository entityRepository() {
        return entityRepository;
    }

    public FieldRepository fieldRepository() {
        return fieldRepository;
    }

    public CategoryRepository categoryRepository() {
        return categoryRepository;
    }

    public FieldCategoryRepository fieldCategoryRepository() {
        return fieldCategoryRepository;
    }

    public PurposeRepository purposeRepository() {
        return purposeRepository;
    }

    public PolicyRepository policyRepository() {
        return policyRepository;
    }

    public ConsentLedgerRepository consentLedgerRepository() {
        return consentLedgerRepository;
    }

    public JurisdictionRepository jurisdictionRepository() {
        return jurisdictionRepository;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt != null ? ldt.toInstant(ZoneOffset.UTC) : null;
    }
}
