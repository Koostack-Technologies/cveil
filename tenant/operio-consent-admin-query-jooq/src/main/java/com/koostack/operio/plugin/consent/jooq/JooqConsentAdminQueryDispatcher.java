package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.cqrs.QueryDispatcher;
import com.koostack.operio.core.models.*;
import com.koostack.operio.core.tenant.consent.ConsentAdminQueryService;
import com.koostack.operio.plugin.jooq.common.JooqContextFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consent admin query dispatcher.
 *
 * <p>Implements both the generic {@link QueryDispatcher} SPI (actuator-based routing) and the
 * legacy {@link ConsentAdminQueryService} interface (thin delegation layer for backward
 * compatibility).
 */
public class JooqConsentAdminQueryDispatcher
        implements QueryDispatcher, ConsentAdminQueryService<JooqConsentAdminServiceConfig> {

    private static final Logger LOG =
            LoggerFactory.getLogger(JooqConsentAdminQueryDispatcher.class);

    private final Map<Class<?>, Actuator<?, ?>> actuators = new ConcurrentHashMap<>();

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

    // -- QueryDispatcher SPI --------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O dispatch(I input) {
        Actuator<I, O> actuator = (Actuator<I, O>) actuators.get(input.getClass());
        if (actuator == null) {
            throw new IllegalArgumentException(
                    "No actuator registered for query type: " + input.getClass().getName());
        }
        return actuator.execute(input);
    }

    /**
     * Registers an additional actuator after initial configuration. Useful for actuators that
     * require dependencies not available during {@link #configure}, such as Redis-backed services.
     */
    public <I, O> void registerActuator(Class<I> inputType, Actuator<I, O> actuator) {
        actuators.put(inputType, actuator);
    }

    // -- Plugin SPI -----------------------------------------------------------

    @Override
    public String getName() {
        return "consent-admin-query-dispatcher";
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
                        config, "operio-consent-admin-query-jooq");

        // Create repositories
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

        // Shared mapper for aggregate view assembly
        ConsentAdminRecordMapper mapper =
                new ConsentAdminRecordMapper(dsl, entityRepository, policyRepository);

        // Register all 18 actuators
        actuators.put(
                ListEntitiesQueryActuator.ListEntitiesQuery.class,
                new ListEntitiesQueryActuator(entityRepository));
        actuators.put(
                GetEntityQueryActuator.GetEntityQuery.class,
                new GetEntityQueryActuator(entityRepository));
        actuators.put(
                ListCategoriesQueryActuator.ListCategoriesQuery.class,
                new ListCategoriesQueryActuator(categoryRepository));
        actuators.put(
                GetCategoryQueryActuator.GetCategoryQuery.class,
                new GetCategoryQueryActuator(categoryRepository));
        actuators.put(
                ListFieldsQueryActuator.ListFieldsQuery.class,
                new ListFieldsQueryActuator(fieldRepository, mapper));
        actuators.put(
                GetFieldQueryActuator.GetFieldQuery.class,
                new GetFieldQueryActuator(entityRepository, fieldRepository, mapper));
        actuators.put(
                ListPurposesQueryActuator.ListPurposesQuery.class,
                new ListPurposesQueryActuator(purposeRepository, mapper));
        actuators.put(
                GetPurposeQueryActuator.GetPurposeQuery.class,
                new GetPurposeQueryActuator(purposeRepository, mapper));
        actuators.put(
                ListRegulationsQueryActuator.ListRegulationsQuery.class,
                new ListRegulationsQueryActuator(regulationRepository, mapper));
        actuators.put(
                GetRegulationQueryActuator.GetRegulationQuery.class,
                new GetRegulationQueryActuator(regulationRepository, mapper));
        actuators.put(
                ListJurisdictionsQueryActuator.ListJurisdictionsQuery.class,
                new ListJurisdictionsQueryActuator(jurisdictionRepository));
        actuators.put(
                GetJurisdictionQueryActuator.GetJurisdictionQuery.class,
                new GetJurisdictionQueryActuator(jurisdictionRepository));
        actuators.put(
                ListSubjectsQueryActuator.ListSubjectsQuery.class,
                new ListSubjectsQueryActuator(subjectRepository, mapper));
        actuators.put(
                GetSubjectQueryActuator.GetSubjectQuery.class,
                new GetSubjectQueryActuator(subjectRepository, mapper));
        actuators.put(
                GetSubjectConsentQueryActuator.GetSubjectConsentQuery.class,
                new GetSubjectConsentQueryActuator(consentLedgerRepository, mapper));
        actuators.put(
                CountSubjectConsentEntriesQueryActuator.CountSubjectConsentEntriesQuery.class,
                new CountSubjectConsentEntriesQueryActuator(dsl));
        actuators.put(
                FindExpiredGrantsQueryActuator.FindExpiredGrantsQuery.class,
                new FindExpiredGrantsQueryActuator(consentLedgerRepository, mapper));
        actuators.put(
                FindPolicyQueryActuator.FindPolicyQuery.class,
                new FindPolicyQueryActuator(policyRepository));

        LOG.info("JooqConsentAdminQueryDispatcher configured with {} actuators", actuators.size());
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

    // -- ConsentAdminQueryService delegation (backward compatibility) ----------

    @Override
    public List<EntityView> listEntities() {
        return dispatch(new ListEntitiesQueryActuator.ListEntitiesQuery());
    }

    @Override
    public Optional<EntityView> getEntity(String entityName) {
        return dispatch(new GetEntityQueryActuator.GetEntityQuery(entityName));
    }

    @Override
    public List<CategoryView> listCategories() {
        return dispatch(new ListCategoriesQueryActuator.ListCategoriesQuery());
    }

    @Override
    public Optional<CategoryView> getCategory(String categoryName) {
        return dispatch(new GetCategoryQueryActuator.GetCategoryQuery(categoryName));
    }

    @Override
    public List<FieldView> listFields() {
        return dispatch(new ListFieldsQueryActuator.ListFieldsQuery());
    }

    @Override
    public FieldView getField(String fieldName, String entityName) {
        return dispatch(new GetFieldQueryActuator.GetFieldQuery(fieldName, entityName));
    }

    @Override
    public List<PurposeView> listPurposes() {
        return dispatch(new ListPurposesQueryActuator.ListPurposesQuery());
    }

    @Override
    public PurposeView getPurpose(String purposeName) {
        return dispatch(new GetPurposeQueryActuator.GetPurposeQuery(purposeName));
    }

    @Override
    public List<RegulationView> listRegulations() {
        return dispatch(new ListRegulationsQueryActuator.ListRegulationsQuery());
    }

    @Override
    public RegulationView getRegulation(String regulationName) {
        return dispatch(new GetRegulationQueryActuator.GetRegulationQuery(regulationName));
    }

    @Override
    public List<JurisdictionView> listJurisdictions() {
        return dispatch(new ListJurisdictionsQueryActuator.ListJurisdictionsQuery());
    }

    @Override
    public Optional<JurisdictionView> getJurisdiction(String jurisdictionName) {
        return dispatch(new GetJurisdictionQueryActuator.GetJurisdictionQuery(jurisdictionName));
    }

    @Override
    public List<SubjectView> listSubjects() {
        return dispatch(new ListSubjectsQueryActuator.ListSubjectsQuery());
    }

    @Override
    public Optional<SubjectView> getSubject(String subjectName) {
        return dispatch(new GetSubjectQueryActuator.GetSubjectQuery(subjectName));
    }

    @Override
    public SubjectConsentView getSubjectConsent(String subjectId) {
        return dispatch(new GetSubjectConsentQueryActuator.GetSubjectConsentQuery(subjectId));
    }

    @Override
    public int countSubjectConsentEntries(String subjectId) {
        return dispatch(
                new CountSubjectConsentEntriesQueryActuator.CountSubjectConsentEntriesQuery(
                        subjectId));
    }

    @Override
    public List<SubjectConsentView.ConsentLedgerEntryView> findExpiredGrants(Instant cutoff) {
        return dispatch(new FindExpiredGrantsQueryActuator.FindExpiredGrantsQuery(cutoff));
    }

    @Override
    public PurposeView.PolicyView findPolicy(
            String purposeName,
            String policyType,
            String fieldName,
            String entityName,
            String categoryName) {
        return dispatch(
                new FindPolicyQueryActuator.FindPolicyQuery(
                        purposeName, policyType, fieldName, entityName, categoryName));
    }

    // -- Repository accessors (backward compatibility) ------------------------

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
}
