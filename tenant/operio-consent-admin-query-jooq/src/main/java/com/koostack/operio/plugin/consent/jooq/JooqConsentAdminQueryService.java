package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.tenant.consent.ConsentAdminQueryService;
import com.koostack.operio.core.models.*;
import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Category;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Entity;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Field;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.FieldCategory;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Purpose;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.Regulation;
import com.koostack.operio.plugin.jooq.common.JooqContextFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.*;

/**
 * jOOQ-backed {@link ConsentAdminQueryService} implementation.
 *
 * <p>Returns aggregate-shaped view models for all 5 consent aggregates:
 * Field, Purpose, Regulation, SubjectConsent, and DSAR.
 */
public class JooqConsentAdminQueryService
    implements ConsentAdminQueryService<JooqConsentAdminServiceConfig>
{

    private static final Logger LOG = LoggerFactory.getLogger(
        JooqConsentAdminQueryService.class
    );

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
            throw new OperioConfigurationException(
                "JooqConsentAdminQueryService not configured. Call configure() first."
            );
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
        this.dsl = JooqContextFactory.createTenantAwareContext(
            config,
            "operio-consent-admin-query-jooq",
            "classpath:db/migration/consent"
        );
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
            .map(e -> new EntityView(e.getEntityId(), e.getEntityName(), e.getDescription()))
            .toList();
    }

    @Override
    public Optional<EntityView> getEntity(String entityName) {
        getDsl();
        return entityRepository.findByName(entityName)
            .map(e -> new EntityView(e.getEntityId(), e.getEntityName(), e.getDescription()));
    }

    // -----------------------------------------------------------------------
    // Category Queries
    // -----------------------------------------------------------------------

    @Override
    public List<CategoryView> listCategories() {
        getDsl();
        return categoryRepository.findAll().stream()
            .map(c -> new CategoryView(c.getCategoryId(), c.getCategoryName(), c.getDescription()))
            .toList();
    }

    @Override
    public Optional<CategoryView> getCategory(String categoryName) {
        getDsl();
        return categoryRepository.findByName(categoryName)
            .map(c -> new CategoryView(c.getCategoryId(), c.getCategoryName(), c.getDescription()));
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
        Optional<Field> field = fieldRepository.findByNameAndEntity(
            fieldName, entity.get().getEntityId());
        return field.map(this::toFieldView).orElse(null);
    }

    private FieldView toFieldView(Field f) {
        FieldView.EntityView entityView = null;
        if (f.getEntityId() != null) {
            entityView = entityRepository.dao()
                .findOptionalById(f.getEntityId())
                .map(e -> new FieldView.EntityView(e.getEntityId(), e.getEntityName()))
                .orElse(null);
        }

        // Get categories for this field
        var fieldCategories = getDsl().select(CATEGORY.CATEGORY_ID, CATEGORY.CATEGORY_NAME)
            .from(FIELD_CATEGORY)
            .join(CATEGORY).on(FIELD_CATEGORY.CATEGORY_ID.eq(CATEGORY.CATEGORY_ID))
            .where(FIELD_CATEGORY.FIELD_ID.eq(f.getFieldId()))
            .fetch();

        List<FieldView.CategoryView> categories = fieldCategories.stream()
            .map(r -> new FieldView.CategoryView(
                r.get(CATEGORY.CATEGORY_ID),
                r.get(CATEGORY.CATEGORY_NAME)
            ))
            .toList();

        return new FieldView(
            f.getFieldId(),
            f.getFieldName(),
            f.getDescription(),
            entityView,
            categories
        );
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
        return purposeRepository.findByName(purposeName)
            .map(this::toPurposeView)
            .orElse(null);
    }

    private PurposeView toPurposeView(Purpose p) {
        // Purpose ↔ Regulation links
        var regulationLinks = getDsl()
            .select(REGULATION.REGULATION_NAME,
                    PURPOSE_REGULATION.LAWFUL_BASIS,
                    PURPOSE_REGULATION.ON_GRANTED,
                    PURPOSE_REGULATION.ON_DENIED)
            .from(PURPOSE_REGULATION)
            .join(REGULATION).on(PURPOSE_REGULATION.REGULATION_ID.eq(REGULATION.REGULATION_ID))
            .where(PURPOSE_REGULATION.PURPOSE_ID.eq(p.getPurposeId()))
            .fetch();

        List<PurposeView.RegulationLinkView> regs = regulationLinks.stream()
            .map(r -> new PurposeView.RegulationLinkView(
                r.get(REGULATION.REGULATION_NAME),
                r.get(PURPOSE_REGULATION.LAWFUL_BASIS),
                r.get(PURPOSE_REGULATION.ON_GRANTED) != null ? r.get(PURPOSE_REGULATION.ON_GRANTED).toString() : null,
                r.get(PURPOSE_REGULATION.ON_DENIED) != null ? r.get(PURPOSE_REGULATION.ON_DENIED).toString() : null
            ))
            .toList();

        // Policies under this purpose
        var policyRecords = policyRepository.findByPurpose(p.getPurposeId());
        List<PurposeView.PolicyView> policies = policyRecords.stream()
            .map(r -> {
                long policyId = r.get(POLICY.POLICY_ID);
                String policyType = r.get(POLICY.POLICY_TYPE) != null
                    ? r.get(POLICY.POLICY_TYPE).toString() : null;

                PurposeView.CategoryRef catRef = null;
                PurposeView.FieldRef fieldRef = null;

                if (r.get(CATEGORY.CATEGORY_NAME) != null) {
                    catRef = new PurposeView.CategoryRef(
                        r.get(POLICY.CATEGORY_ID),
                        r.get(CATEGORY.CATEGORY_NAME)
                    );
                }
                if (r.get(FIELD.FIELD_NAME) != null) {
                    fieldRef = new PurposeView.FieldRef(
                        r.get(POLICY.FIELD_ID),
                        r.get(FIELD.FIELD_NAME),
                        r.get(ENTITY.ENTITY_NAME)
                    );
                }

                return new PurposeView.PolicyView(policyId, policyType, catRef, fieldRef);
            })
            .toList();

        return new PurposeView(
            p.getPurposeId(),
            p.getPurposeName(),
            p.getDescription(),
            regs,
            policies
        );
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
        return regulationRepository.findByName(regulationName)
            .map(this::toRegulationView)
            .orElse(null);
    }

    private RegulationView toRegulationView(Regulation r) {
        var jurisdictions = getDsl()
            .select(JURISDICTION.JURISDICTION_ID, JURISDICTION.JURISDICTION_NAME)
            .from(REGULATION_JURISDICTION)
            .join(JURISDICTION).on(REGULATION_JURISDICTION.JURISDICTION_ID.eq(JURISDICTION.JURISDICTION_ID))
            .where(REGULATION_JURISDICTION.REGULATION_ID.eq(r.getRegulationId()))
            .fetch();

        List<RegulationView.JurisdictionView> juris = jurisdictions.stream()
            .map(j -> new RegulationView.JurisdictionView(
                j.get(JURISDICTION.JURISDICTION_ID),
                j.get(JURISDICTION.JURISDICTION_NAME)
            ))
            .toList();

        return new RegulationView(
            r.getRegulationId(),
            r.getRegulationName(),
            r.getDescription(),
            juris
        );
    }

    // -----------------------------------------------------------------------
    // Jurisdiction Queries
    // -----------------------------------------------------------------------

    @Override
    public List<JurisdictionView> listJurisdictions() {
        getDsl();
        return jurisdictionRepository.findAll().stream()
            .map(j -> new JurisdictionView(
                j.getJurisdictionId(), j.getJurisdictionName(), j.getDescription()))
            .toList();
    }

    @Override
    public Optional<JurisdictionView> getJurisdiction(String jurisdictionName) {
        getDsl();
        return jurisdictionRepository.findByName(jurisdictionName)
            .map(j -> new JurisdictionView(
                j.getJurisdictionId(), j.getJurisdictionName(), j.getDescription()));
    }

    // -----------------------------------------------------------------------
    // Subject Queries
    // -----------------------------------------------------------------------

    @Override
    public List<SubjectView> listSubjects() {
        getDsl();
        return subjectRepository.findAll().stream()
            .map(s -> new SubjectView(
                s.getSubjectId(), s.getSubjectName(), s.getDescription(),
                toInstant(s.getCreatedAt()), s.getCreatedBy(),
                toInstant(s.getUpdatedAt()), s.getUpdatedBy()))
            .toList();
    }

    @Override
    public Optional<SubjectView> getSubject(String subjectName) {
        getDsl();
        return subjectRepository.findByName(subjectName)
            .map(s -> new SubjectView(
                s.getSubjectId(), s.getSubjectName(), s.getDescription(),
                toInstant(s.getCreatedAt()), s.getCreatedBy(),
                toInstant(s.getUpdatedAt()), s.getUpdatedBy()));
    }

    // -----------------------------------------------------------------------
    // SubjectConsent Queries
    // -----------------------------------------------------------------------

    @Override
    public SubjectConsentView getSubjectConsent(String subjectId) {
        getDsl();
        var records = consentLedgerRepository.findBySubject(subjectId);

        List<SubjectConsentView.ConsentLedgerEntryView> entries = records.stream()
            .map(r -> new SubjectConsentView.ConsentLedgerEntryView(
                r.get(CONSENT_LEDGER.CONSENT_LEDGER_ID),
                subjectId,
                r.get(CONSENT_LEDGER.POLICY_ID),
                r.get(PURPOSE.PURPOSE_NAME),
                r.get(POLICY.POLICY_TYPE) != null ? r.get(POLICY.POLICY_TYPE).getLiteral() : null,
                r.get(FIELD.FIELD_NAME),
                r.get(ENTITY.ENTITY_NAME),
                r.get(CATEGORY.CATEGORY_NAME),
                r.get(CONSENT_LEDGER.ACTION) != null ? r.get(CONSENT_LEDGER.ACTION).getLiteral() : null,
                r.get(CONSENT_LEDGER.ACTOR),
                r.get(CONSENT_LEDGER.IP_ADDRESS),
                r.get(CONSENT_LEDGER.USER_AGENT),
                toInstant(r.get(CONSENT_LEDGER.CREATED_AT)),
                toInstant(r.get(CONSENT_LEDGER.EXPIRES_AT))
            ))
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
        var records = consentLedgerRepository.findExpiredGrants(
            java.time.LocalDateTime.ofInstant(cutoff, java.time.ZoneOffset.UTC)
        );
        return records.stream()
            .map(r -> new SubjectConsentView.ConsentLedgerEntryView(
                r.get(CONSENT_LEDGER.CONSENT_LEDGER_ID),
                r.get(CONSENT_LEDGER.SUBJECT_ID),
                r.get(CONSENT_LEDGER.POLICY_ID),
                r.get(PURPOSE.PURPOSE_NAME),
                r.get(POLICY.POLICY_TYPE) != null ? r.get(POLICY.POLICY_TYPE).getLiteral() : null,
                r.get(FIELD.FIELD_NAME),
                r.get(ENTITY.ENTITY_NAME),
                r.get(CATEGORY.CATEGORY_NAME),
                r.get(CONSENT_LEDGER.ACTION) != null ? r.get(CONSENT_LEDGER.ACTION).getLiteral() : null,
                r.get(CONSENT_LEDGER.ACTOR),
                r.get(CONSENT_LEDGER.IP_ADDRESS),
                r.get(CONSENT_LEDGER.USER_AGENT),
                toInstant(r.get(CONSENT_LEDGER.CREATED_AT)),
                toInstant(r.get(CONSENT_LEDGER.EXPIRES_AT))
            ))
            .toList();
    }

    // -----------------------------------------------------------------------
    // Policy Lookup (for TenantService enrichment)
    // -----------------------------------------------------------------------

    @Override
    public PurposeView.PolicyView findPolicy(
        String purposeName, String policyType,
        String fieldName, String entityName,
        String categoryName
    ) {
        getDsl();

        if ("FIELD".equals(policyType)) {
            var opt = policyRepository.findFieldPolicy(purposeName, fieldName, entityName);
            return opt.map(r -> new PurposeView.PolicyView(
                r.get(POLICY.POLICY_ID),
                r.get(POLICY.POLICY_TYPE) != null ? r.get(POLICY.POLICY_TYPE).toString() : "FIELD",
                null,
                new PurposeView.FieldRef(r.get(POLICY.FIELD_ID), fieldName, entityName)
            )).orElse(null);
        } else if ("CATEGORY".equals(policyType)) {
            var opt = policyRepository.findCategoryPolicy(purposeName, categoryName);
            return opt.map(r -> new PurposeView.PolicyView(
                r.get(POLICY.POLICY_ID),
                r.get(POLICY.POLICY_TYPE) != null ? r.get(POLICY.POLICY_TYPE).toString() : "CATEGORY",
                new PurposeView.CategoryRef(r.get(POLICY.CATEGORY_ID), categoryName),
                null
            )).orElse(null);
        }

        return null;
    }

    // -----------------------------------------------------------------------
    // Repository Accessors
    // -----------------------------------------------------------------------

    public EntityRepository entityRepository() { return entityRepository; }
    public FieldRepository fieldRepository() { return fieldRepository; }
    public CategoryRepository categoryRepository() { return categoryRepository; }
    public FieldCategoryRepository fieldCategoryRepository() { return fieldCategoryRepository; }
    public PurposeRepository purposeRepository() { return purposeRepository; }
    public PolicyRepository policyRepository() { return policyRepository; }
    public ConsentLedgerRepository consentLedgerRepository() { return consentLedgerRepository; }
    public JurisdictionRepository jurisdictionRepository() { return jurisdictionRepository; }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt != null ? ldt.toInstant(ZoneOffset.UTC) : null;
    }
}
