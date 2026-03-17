package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.Actuator;
import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.tenant.consent.ConsentAdminCommandService;
import com.koostack.cveil.core.tenant.consent.events.FieldSnapshot;
import com.koostack.cveil.core.tenant.consent.events.PurposeSnapshot;
import com.koostack.cveil.core.tenant.consent.events.RegulationSnapshot;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Event-sourced {@link ConsentAdminCommandService} implementation.
 *
 * <p>All write operations delegate to {@link Actuator} instances that publish semantic domain
 * events via {@link EventService}. Separate projections materialize the event stream into the
 * consent read model (admin DB) and Redis decision cache.
 *
 * <p>Also implements {@link CommandDispatcher} for direct command-record dispatch from REST
 * resources.
 */
public class JooqConsentAdminCommandService
        implements ConsentAdminCommandService<ConsentAdminCommandConfig>, CommandDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(JooqConsentAdminCommandService.class);

    private final Map<Class<?>, Actuator<?, ?>> actuators = new ConcurrentHashMap<>();
    private EventService<?> eventService;

    // -- Actuator instances (created in configure with eventService) ------------

    private RegisterEntityCommandActuator registerEntityActuator;
    private UpdateEntityCommandActuator updateEntityActuator;
    private RemoveEntityCommandActuator removeEntityActuator;
    private RegisterCategoryCommandActuator registerCategoryActuator;
    private UpdateCategoryCommandActuator updateCategoryActuator;
    private RemoveCategoryCommandActuator removeCategoryActuator;
    private RegisterFieldCommandActuator registerFieldActuator;
    private UpdateFieldCommandActuator updateFieldActuator;
    private RemoveFieldCommandActuator removeFieldActuator;
    private AssignFieldCategoryCommandActuator assignFieldCategoryActuator;
    private UnassignFieldCategoryCommandActuator unassignFieldCategoryActuator;
    private RegisterPurposeCommandActuator registerPurposeActuator;
    private UpdatePurposeCommandActuator updatePurposeActuator;
    private RemovePurposeCommandActuator removePurposeActuator;
    private LinkPurposeToRegulationCommandActuator linkPurposeToRegulationActuator;
    private UnlinkPurposeFromRegulationCommandActuator unlinkPurposeFromRegulationActuator;
    private AddPurposePolicyCommandActuator addPurposePolicyActuator;
    private RemovePurposePolicyCommandActuator removePurposePolicyActuator;
    private RegisterRegulationCommandActuator registerRegulationActuator;
    private UpdateRegulationCommandActuator updateRegulationActuator;
    private RemoveRegulationCommandActuator removeRegulationActuator;
    private AddRegulationJurisdictionCommandActuator addRegulationJurisdictionActuator;
    private RemoveRegulationJurisdictionCommandActuator removeRegulationJurisdictionActuator;
    private RegisterJurisdictionCommandActuator registerJurisdictionActuator;
    private UpdateJurisdictionCommandActuator updateJurisdictionActuator;
    private RemoveJurisdictionCommandActuator removeJurisdictionActuator;
    private RegisterSubjectCommandActuator registerSubjectActuator;
    private UpdateSubjectCommandActuator updateSubjectActuator;
    private RemoveSubjectCommandActuator removeSubjectActuator;
    private GrantConsentCommandActuator grantConsentActuator;
    private RevokeConsentCommandActuator revokeConsentActuator;
    private ExpireConsentCommandActuator expireConsentActuator;
    private RevokeAllConsentCommandActuator revokeAllConsentActuator;
    private DeleteSubjectAuditCommandActuator deleteSubjectAuditActuator;
    private ShredSubjectCommandActuator shredSubjectActuator;
    private SeedConsentGovernanceCommandActuator seedConsentGovernanceActuator;

    // -- CommandDispatcher SPI -------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O dispatch(I input) {
        Actuator<I, O> actuator = (Actuator<I, O>) actuators.get(input.getClass());
        if (actuator == null) {
            throw new IllegalArgumentException(
                    "No actuator registered for command type: " + input.getClass().getName());
        }
        return actuator.execute(input);
    }

    // -----------------------------------------------------------------------
    // Plugin SPI
    // -----------------------------------------------------------------------

    @Override
    public String getName() {
        return "jooq-consent-admin-command";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<ConsentAdminCommandConfig> getConfigurationType() {
        return ConsentAdminCommandConfig.class;
    }

    @Override
    public void configure(ConsentAdminCommandConfig config) {
        this.eventService = config.eventService();

        // Create actuator instances with eventService
        registerEntityActuator = new RegisterEntityCommandActuator(eventService);
        updateEntityActuator = new UpdateEntityCommandActuator(eventService);
        removeEntityActuator = new RemoveEntityCommandActuator(eventService);
        registerCategoryActuator = new RegisterCategoryCommandActuator(eventService);
        updateCategoryActuator = new UpdateCategoryCommandActuator(eventService);
        removeCategoryActuator = new RemoveCategoryCommandActuator(eventService);
        registerFieldActuator = new RegisterFieldCommandActuator(eventService);
        updateFieldActuator = new UpdateFieldCommandActuator(eventService);
        removeFieldActuator = new RemoveFieldCommandActuator(eventService);
        assignFieldCategoryActuator = new AssignFieldCategoryCommandActuator(eventService);
        unassignFieldCategoryActuator = new UnassignFieldCategoryCommandActuator(eventService);
        registerPurposeActuator = new RegisterPurposeCommandActuator(eventService);
        updatePurposeActuator = new UpdatePurposeCommandActuator(eventService);
        removePurposeActuator = new RemovePurposeCommandActuator(eventService);
        linkPurposeToRegulationActuator = new LinkPurposeToRegulationCommandActuator(eventService);
        unlinkPurposeFromRegulationActuator =
                new UnlinkPurposeFromRegulationCommandActuator(eventService);
        addPurposePolicyActuator = new AddPurposePolicyCommandActuator(eventService);
        removePurposePolicyActuator = new RemovePurposePolicyCommandActuator(eventService);
        registerRegulationActuator = new RegisterRegulationCommandActuator(eventService);
        updateRegulationActuator = new UpdateRegulationCommandActuator(eventService);
        removeRegulationActuator = new RemoveRegulationCommandActuator(eventService);
        addRegulationJurisdictionActuator =
                new AddRegulationJurisdictionCommandActuator(eventService);
        removeRegulationJurisdictionActuator =
                new RemoveRegulationJurisdictionCommandActuator(eventService);
        registerJurisdictionActuator = new RegisterJurisdictionCommandActuator(eventService);
        updateJurisdictionActuator = new UpdateJurisdictionCommandActuator(eventService);
        removeJurisdictionActuator = new RemoveJurisdictionCommandActuator(eventService);
        registerSubjectActuator =
                new RegisterSubjectCommandActuator(
                        eventService, config.dsarService(), config.keyEncryptor());
        updateSubjectActuator = new UpdateSubjectCommandActuator(eventService);
        removeSubjectActuator = new RemoveSubjectCommandActuator(eventService);
        grantConsentActuator = new GrantConsentCommandActuator(eventService);
        revokeConsentActuator = new RevokeConsentCommandActuator(eventService);
        expireConsentActuator = new ExpireConsentCommandActuator(eventService);
        revokeAllConsentActuator = new RevokeAllConsentCommandActuator(eventService);
        deleteSubjectAuditActuator = new DeleteSubjectAuditCommandActuator(eventService);

        // DSAR actuators require SubjectDsarService (null-safe: only registered when available)
        if (config.dsarService() != null) {
            shredSubjectActuator =
                    new ShredSubjectCommandActuator(eventService, config.dsarService());
        }

        // Entity
        actuators.put(
                RegisterEntityCommandActuator.RegisterEntityCommand.class, registerEntityActuator);
        actuators.put(UpdateEntityCommandActuator.UpdateEntityCommand.class, updateEntityActuator);
        actuators.put(RemoveEntityCommandActuator.RemoveEntityCommand.class, removeEntityActuator);
        // Category
        actuators.put(
                RegisterCategoryCommandActuator.RegisterCategoryCommand.class,
                registerCategoryActuator);
        actuators.put(
                UpdateCategoryCommandActuator.UpdateCategoryCommand.class, updateCategoryActuator);
        actuators.put(
                RemoveCategoryCommandActuator.RemoveCategoryCommand.class, removeCategoryActuator);
        // Field
        actuators.put(
                RegisterFieldCommandActuator.RegisterFieldCommand.class, registerFieldActuator);
        actuators.put(UpdateFieldCommandActuator.UpdateFieldCommand.class, updateFieldActuator);
        actuators.put(RemoveFieldCommandActuator.RemoveFieldCommand.class, removeFieldActuator);
        actuators.put(
                AssignFieldCategoryCommandActuator.AssignFieldCategoryCommand.class,
                assignFieldCategoryActuator);
        actuators.put(
                UnassignFieldCategoryCommandActuator.UnassignFieldCategoryCommand.class,
                unassignFieldCategoryActuator);
        // Purpose
        actuators.put(
                RegisterPurposeCommandActuator.RegisterPurposeCommand.class,
                registerPurposeActuator);
        actuators.put(
                UpdatePurposeCommandActuator.UpdatePurposeCommand.class, updatePurposeActuator);
        actuators.put(
                RemovePurposeCommandActuator.RemovePurposeCommand.class, removePurposeActuator);
        actuators.put(
                LinkPurposeToRegulationCommandActuator.LinkPurposeToRegulationCommand.class,
                linkPurposeToRegulationActuator);
        actuators.put(
                UnlinkPurposeFromRegulationCommandActuator.UnlinkPurposeFromRegulationCommand.class,
                unlinkPurposeFromRegulationActuator);
        actuators.put(
                AddPurposePolicyCommandActuator.AddPurposePolicyCommand.class,
                addPurposePolicyActuator);
        actuators.put(
                RemovePurposePolicyCommandActuator.RemovePurposePolicyCommand.class,
                removePurposePolicyActuator);
        // Regulation
        actuators.put(
                RegisterRegulationCommandActuator.RegisterRegulationCommand.class,
                registerRegulationActuator);
        actuators.put(
                UpdateRegulationCommandActuator.UpdateRegulationCommand.class,
                updateRegulationActuator);
        actuators.put(
                RemoveRegulationCommandActuator.RemoveRegulationCommand.class,
                removeRegulationActuator);
        actuators.put(
                AddRegulationJurisdictionCommandActuator.AddRegulationJurisdictionCommand.class,
                addRegulationJurisdictionActuator);
        actuators.put(
                RemoveRegulationJurisdictionCommandActuator.RemoveRegulationJurisdictionCommand
                        .class,
                removeRegulationJurisdictionActuator);
        // Jurisdiction
        actuators.put(
                RegisterJurisdictionCommandActuator.RegisterJurisdictionCommand.class,
                registerJurisdictionActuator);
        actuators.put(
                UpdateJurisdictionCommandActuator.UpdateJurisdictionCommand.class,
                updateJurisdictionActuator);
        actuators.put(
                RemoveJurisdictionCommandActuator.RemoveJurisdictionCommand.class,
                removeJurisdictionActuator);
        // Subject
        actuators.put(
                RegisterSubjectCommandActuator.RegisterSubjectCommand.class,
                registerSubjectActuator);
        actuators.put(
                UpdateSubjectCommandActuator.UpdateSubjectCommand.class, updateSubjectActuator);
        actuators.put(
                RemoveSubjectCommandActuator.RemoveSubjectCommand.class, removeSubjectActuator);
        // SubjectConsent
        actuators.put(GrantConsentCommandActuator.GrantConsentCommand.class, grantConsentActuator);
        actuators.put(
                RevokeConsentCommandActuator.RevokeConsentCommand.class, revokeConsentActuator);
        actuators.put(
                ExpireConsentCommandActuator.ExpireConsentCommand.class, expireConsentActuator);
        actuators.put(
                RevokeAllConsentCommandActuator.RevokeAllConsentCommand.class,
                revokeAllConsentActuator);
        // DSAR
        actuators.put(
                DeleteSubjectAuditCommandActuator.DeleteSubjectAuditCommand.class,
                deleteSubjectAuditActuator);
        if (shredSubjectActuator != null) {
            actuators.put(
                    ShredSubjectCommandActuator.ShredSubjectCommand.class, shredSubjectActuator);
        }

        // Seed governance actuator — dispatches through this service (self-reference)
        seedConsentGovernanceActuator =
                new SeedConsentGovernanceCommandActuator(eventService, this);
        actuators.put(
                SeedConsentGovernanceCommandActuator.SeedConsentGovernanceCommand.class,
                seedConsentGovernanceActuator);

        // Parameterized seed actuator — seeds only user-selected governance data
        var seedParameterizedActuator =
                new SeedParameterizedGovernanceCommandActuator(eventService, this);
        actuators.put(
                SeedParameterizedGovernanceCommandActuator.SeedParameterizedGovernanceCommand.class,
                seedParameterizedActuator);

        LOG.info(
                "JooqConsentAdminCommandService configured (event-sourced, {} actuators)",
                actuators.size());
    }

    @Override
    public boolean isReady() {
        return eventService != null && eventService.isReady();
    }

    // -----------------------------------------------------------------------
    // Entity Aggregate — delegation to actuators
    // -----------------------------------------------------------------------

    @Override
    public void registerEntityCommand(String entityName, String description) {
        registerEntityActuator.execute(
                new RegisterEntityCommandActuator.RegisterEntityCommand(entityName, description));
    }

    @Override
    public void updateEntityCommand(String entityName, String description) {
        updateEntityActuator.execute(
                new UpdateEntityCommandActuator.UpdateEntityCommand(entityName, description));
    }

    @Override
    public void removeEntityCommand(String entityName) {
        removeEntityActuator.execute(
                new RemoveEntityCommandActuator.RemoveEntityCommand(entityName));
    }

    // -----------------------------------------------------------------------
    // Category Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void registerCategoryCommand(String categoryName, String description) {
        registerCategoryActuator.execute(
                new RegisterCategoryCommandActuator.RegisterCategoryCommand(
                        categoryName, description));
    }

    @Override
    public void updateCategoryCommand(String categoryName, String description) {
        updateCategoryActuator.execute(
                new UpdateCategoryCommandActuator.UpdateCategoryCommand(categoryName, description));
    }

    @Override
    public void removeCategoryCommand(String categoryName) {
        removeCategoryActuator.execute(
                new RemoveCategoryCommandActuator.RemoveCategoryCommand(categoryName));
    }

    // -----------------------------------------------------------------------
    // Field Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void registerFieldCommand(
            String fieldName, String entityName, String description, String entityDescription) {
        registerFieldActuator.execute(
                new RegisterFieldCommandActuator.RegisterFieldCommand(
                        fieldName, entityName, description, entityDescription));
    }

    @Override
    public void updateFieldCommand(
            String fieldName, String entityName, String description, FieldSnapshot snapshot) {
        updateFieldActuator.execute(
                new UpdateFieldCommandActuator.UpdateFieldCommand(
                        fieldName, entityName, description));
    }

    @Override
    public void removeFieldCommand(String fieldName, String entityName) {
        removeFieldActuator.execute(
                new RemoveFieldCommandActuator.RemoveFieldCommand(fieldName, entityName));
    }

    @Override
    public void assignFieldCategoryCommand(
            String fieldName,
            String entityName,
            String categoryName,
            String categoryDescription,
            FieldSnapshot snapshot) {
        assignFieldCategoryActuator.execute(
                new AssignFieldCategoryCommandActuator.AssignFieldCategoryCommand(
                        fieldName, entityName, categoryName, categoryDescription));
    }

    @Override
    public void unassignFieldCategoryCommand(
            String fieldName, String entityName, String categoryName, FieldSnapshot snapshot) {
        unassignFieldCategoryActuator.execute(
                new UnassignFieldCategoryCommandActuator.UnassignFieldCategoryCommand(
                        fieldName, entityName, categoryName));
    }

    // -----------------------------------------------------------------------
    // Purpose Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void registerPurposeCommand(String purposeName, String description) {
        registerPurposeActuator.execute(
                new RegisterPurposeCommandActuator.RegisterPurposeCommand(
                        purposeName, description));
    }

    @Override
    public void updatePurposeCommand(
            String purposeName, String description, PurposeSnapshot snapshot) {
        updatePurposeActuator.execute(
                new UpdatePurposeCommandActuator.UpdatePurposeCommand(purposeName, description));
    }

    @Override
    public void removePurposeCommand(String purposeName) {
        removePurposeActuator.execute(
                new RemovePurposeCommandActuator.RemovePurposeCommand(purposeName));
    }

    @Override
    public void linkPurposeToRegulationCommand(
            String purposeName,
            String regulationName,
            String lawfulBasis,
            String onGranted,
            String onDenied,
            PurposeSnapshot snapshot) {
        linkPurposeToRegulationActuator.execute(
                new LinkPurposeToRegulationCommandActuator.LinkPurposeToRegulationCommand(
                        purposeName, regulationName, lawfulBasis, onGranted, onDenied));
    }

    @Override
    public void unlinkPurposeFromRegulationCommand(
            String purposeName, String regulationName, PurposeSnapshot snapshot) {
        unlinkPurposeFromRegulationActuator.execute(
                new UnlinkPurposeFromRegulationCommandActuator.UnlinkPurposeFromRegulationCommand(
                        purposeName, regulationName));
    }

    @Override
    public void addPurposePolicyCommand(
            String purposeName,
            String policyType,
            String fieldName,
            String entityName,
            String categoryName,
            PurposeSnapshot snapshot) {
        addPurposePolicyActuator.execute(
                new AddPurposePolicyCommandActuator.AddPurposePolicyCommand(
                        purposeName, policyType, fieldName, entityName, categoryName));
    }

    @Override
    public void removePurposePolicyCommand(
            String purposeName,
            String policyType,
            String fieldName,
            String entityName,
            String categoryName,
            PurposeSnapshot snapshot) {
        removePurposePolicyActuator.execute(
                new RemovePurposePolicyCommandActuator.RemovePurposePolicyCommand(
                        purposeName, policyType, fieldName, entityName, categoryName));
    }

    // -----------------------------------------------------------------------
    // Regulation Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void registerRegulationCommand(String regulationName, String description) {
        registerRegulationActuator.execute(
                new RegisterRegulationCommandActuator.RegisterRegulationCommand(
                        regulationName, description));
    }

    @Override
    public void updateRegulationCommand(
            String regulationName, String description, RegulationSnapshot snapshot) {
        updateRegulationActuator.execute(
                new UpdateRegulationCommandActuator.UpdateRegulationCommand(
                        regulationName, description));
    }

    @Override
    public void removeRegulationCommand(String regulationName) {
        removeRegulationActuator.execute(
                new RemoveRegulationCommandActuator.RemoveRegulationCommand(regulationName));
    }

    @Override
    public void addRegulationJurisdictionCommand(
            String regulationName,
            String jurisdictionName,
            String description,
            RegulationSnapshot snapshot) {
        addRegulationJurisdictionActuator.execute(
                new AddRegulationJurisdictionCommandActuator.AddRegulationJurisdictionCommand(
                        regulationName, jurisdictionName, description));
    }

    @Override
    public void removeRegulationJurisdictionCommand(
            String regulationName, String jurisdictionName, RegulationSnapshot snapshot) {
        removeRegulationJurisdictionActuator.execute(
                new RemoveRegulationJurisdictionCommandActuator.RemoveRegulationJurisdictionCommand(
                        regulationName, jurisdictionName));
    }

    // -----------------------------------------------------------------------
    // Jurisdiction Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void registerJurisdictionCommand(String jurisdictionName, String description) {
        registerJurisdictionActuator.execute(
                new RegisterJurisdictionCommandActuator.RegisterJurisdictionCommand(
                        jurisdictionName, description));
    }

    @Override
    public void updateJurisdictionCommand(String jurisdictionName, String description) {
        updateJurisdictionActuator.execute(
                new UpdateJurisdictionCommandActuator.UpdateJurisdictionCommand(
                        jurisdictionName, description));
    }

    @Override
    public void removeJurisdictionCommand(String jurisdictionName) {
        removeJurisdictionActuator.execute(
                new RemoveJurisdictionCommandActuator.RemoveJurisdictionCommand(jurisdictionName));
    }

    // -----------------------------------------------------------------------
    // Subject Aggregate (entity lifecycle)
    // -----------------------------------------------------------------------

    @Override
    public void registerSubjectCommand(String subjectName, String description) {
        registerSubjectActuator.execute(
                new RegisterSubjectCommandActuator.RegisterSubjectCommand(
                        subjectName, description));
    }

    @Override
    public void updateSubjectCommand(String subjectName, String description) {
        updateSubjectActuator.execute(
                new UpdateSubjectCommandActuator.UpdateSubjectCommand(subjectName, description));
    }

    @Override
    public void removeSubjectCommand(String subjectName) {
        removeSubjectActuator.execute(
                new RemoveSubjectCommandActuator.RemoveSubjectCommand(subjectName));
    }

    // -----------------------------------------------------------------------
    // SubjectConsent Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void grantConsentCommand(
            String subjectId,
            UUID policyId,
            String purposeName,
            String policyType,
            String fieldName,
            String entityName,
            String categoryName,
            String actor,
            String ipAddress,
            String userAgent,
            Instant expiresAt) {
        grantConsentActuator.execute(
                new GrantConsentCommandActuator.GrantConsentCommand(
                        subjectId,
                        policyId,
                        purposeName,
                        policyType,
                        fieldName,
                        entityName,
                        categoryName,
                        actor,
                        ipAddress,
                        userAgent,
                        expiresAt));
    }

    @Override
    public void revokeConsentCommand(
            String subjectId,
            UUID policyId,
            String purposeName,
            String policyType,
            String fieldName,
            String entityName,
            String categoryName,
            String actor,
            String ipAddress,
            String userAgent) {
        revokeConsentActuator.execute(
                new RevokeConsentCommandActuator.RevokeConsentCommand(
                        subjectId,
                        policyId,
                        purposeName,
                        policyType,
                        fieldName,
                        entityName,
                        categoryName,
                        actor,
                        ipAddress,
                        userAgent));
    }

    @Override
    public void expireConsentCommand(
            String subjectId,
            UUID policyId,
            String purposeName,
            String policyType,
            String fieldName,
            String entityName,
            String categoryName) {
        expireConsentActuator.execute(
                new ExpireConsentCommandActuator.ExpireConsentCommand(
                        subjectId,
                        policyId,
                        purposeName,
                        policyType,
                        fieldName,
                        entityName,
                        categoryName));
    }

    @Override
    public void revokeAllConsentCommand(
            String subjectId, String actor, String ipAddress, String userAgent) {
        revokeAllConsentActuator.execute(
                new RevokeAllConsentCommandActuator.RevokeAllConsentCommand(
                        subjectId, actor, ipAddress, userAgent));
    }

    // -----------------------------------------------------------------------
    // DSAR Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void deleteSubjectAuditCommand(
            String subjectId,
            String deletionRequestId,
            String actor,
            String ipAddress,
            String userAgent) {
        deleteSubjectAuditActuator.execute(
                new DeleteSubjectAuditCommandActuator.DeleteSubjectAuditCommand(
                        subjectId, deletionRequestId, actor, ipAddress, userAgent));
    }
}
