package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.cqrs.CommandDispatcher;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.tenant.consent.ConsentAdminCommandService;
import com.koostack.operio.core.tenant.consent.events.FieldSnapshot;
import com.koostack.operio.core.tenant.consent.events.PurposeSnapshot;
import com.koostack.operio.core.tenant.consent.events.RegulationSnapshot;
import java.time.Instant;
import java.util.Map;
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

    // -- Actuator instances (created once, registered in configure) ------------

    private final RegisterEntityCommandActuator registerEntityActuator =
            new RegisterEntityCommandActuator();
    private final UpdateEntityCommandActuator updateEntityActuator =
            new UpdateEntityCommandActuator();
    private final RemoveEntityCommandActuator removeEntityActuator =
            new RemoveEntityCommandActuator();
    private final RegisterCategoryCommandActuator registerCategoryActuator =
            new RegisterCategoryCommandActuator();
    private final UpdateCategoryCommandActuator updateCategoryActuator =
            new UpdateCategoryCommandActuator();
    private final RemoveCategoryCommandActuator removeCategoryActuator =
            new RemoveCategoryCommandActuator();
    private final RegisterFieldCommandActuator registerFieldActuator =
            new RegisterFieldCommandActuator();
    private final UpdateFieldCommandActuator updateFieldActuator = new UpdateFieldCommandActuator();
    private final RemoveFieldCommandActuator removeFieldActuator = new RemoveFieldCommandActuator();
    private final AssignFieldCategoryCommandActuator assignFieldCategoryActuator =
            new AssignFieldCategoryCommandActuator();
    private final UnassignFieldCategoryCommandActuator unassignFieldCategoryActuator =
            new UnassignFieldCategoryCommandActuator();
    private final RegisterPurposeCommandActuator registerPurposeActuator =
            new RegisterPurposeCommandActuator();
    private final UpdatePurposeCommandActuator updatePurposeActuator =
            new UpdatePurposeCommandActuator();
    private final RemovePurposeCommandActuator removePurposeActuator =
            new RemovePurposeCommandActuator();
    private final LinkPurposeToRegulationCommandActuator linkPurposeToRegulationActuator =
            new LinkPurposeToRegulationCommandActuator();
    private final UnlinkPurposeFromRegulationCommandActuator unlinkPurposeFromRegulationActuator =
            new UnlinkPurposeFromRegulationCommandActuator();
    private final AddPurposePolicyCommandActuator addPurposePolicyActuator =
            new AddPurposePolicyCommandActuator();
    private final RemovePurposePolicyCommandActuator removePurposePolicyActuator =
            new RemovePurposePolicyCommandActuator();
    private final RegisterRegulationCommandActuator registerRegulationActuator =
            new RegisterRegulationCommandActuator();
    private final UpdateRegulationCommandActuator updateRegulationActuator =
            new UpdateRegulationCommandActuator();
    private final RemoveRegulationCommandActuator removeRegulationActuator =
            new RemoveRegulationCommandActuator();
    private final AddRegulationJurisdictionCommandActuator addRegulationJurisdictionActuator =
            new AddRegulationJurisdictionCommandActuator();
    private final RemoveRegulationJurisdictionCommandActuator removeRegulationJurisdictionActuator =
            new RemoveRegulationJurisdictionCommandActuator();
    private final RegisterJurisdictionCommandActuator registerJurisdictionActuator =
            new RegisterJurisdictionCommandActuator();
    private final UpdateJurisdictionCommandActuator updateJurisdictionActuator =
            new UpdateJurisdictionCommandActuator();
    private final RemoveJurisdictionCommandActuator removeJurisdictionActuator =
            new RemoveJurisdictionCommandActuator();
    private final RegisterSubjectCommandActuator registerSubjectActuator =
            new RegisterSubjectCommandActuator();
    private final UpdateSubjectCommandActuator updateSubjectActuator =
            new UpdateSubjectCommandActuator();
    private final RemoveSubjectCommandActuator removeSubjectActuator =
            new RemoveSubjectCommandActuator();
    private final GrantConsentCommandActuator grantConsentActuator =
            new GrantConsentCommandActuator();
    private final RevokeConsentCommandActuator revokeConsentActuator =
            new RevokeConsentCommandActuator();
    private final ExpireConsentCommandActuator expireConsentActuator =
            new ExpireConsentCommandActuator();
    private final RevokeAllConsentCommandActuator revokeAllConsentActuator =
            new RevokeAllConsentCommandActuator();
    private final DeleteSubjectAuditCommandActuator deleteSubjectAuditActuator =
            new DeleteSubjectAuditCommandActuator();

    // -- CommandDispatcher SPI -------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O dispatch(I input) {
        Actuator<I, O> actuator = (Actuator<I, O>) actuators.get(input.getClass());
        if (actuator == null) {
            throw new IllegalArgumentException(
                    "No actuator registered for command type: " + input.getClass().getName());
        }
        return actuator.execute(eventService, input);
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
                eventService,
                new RegisterEntityCommandActuator.RegisterEntityCommand(entityName, description));
    }

    @Override
    public void updateEntityCommand(String entityName, String description) {
        updateEntityActuator.execute(
                eventService,
                new UpdateEntityCommandActuator.UpdateEntityCommand(entityName, description));
    }

    @Override
    public void removeEntityCommand(String entityName) {
        removeEntityActuator.execute(
                eventService, new RemoveEntityCommandActuator.RemoveEntityCommand(entityName));
    }

    // -----------------------------------------------------------------------
    // Category Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void registerCategoryCommand(String categoryName, String description) {
        registerCategoryActuator.execute(
                eventService,
                new RegisterCategoryCommandActuator.RegisterCategoryCommand(
                        categoryName, description));
    }

    @Override
    public void updateCategoryCommand(String categoryName, String description) {
        updateCategoryActuator.execute(
                eventService,
                new UpdateCategoryCommandActuator.UpdateCategoryCommand(categoryName, description));
    }

    @Override
    public void removeCategoryCommand(String categoryName) {
        removeCategoryActuator.execute(
                eventService,
                new RemoveCategoryCommandActuator.RemoveCategoryCommand(categoryName));
    }

    // -----------------------------------------------------------------------
    // Field Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void registerFieldCommand(
            String fieldName, String entityName, String description, String entityDescription) {
        registerFieldActuator.execute(
                eventService,
                new RegisterFieldCommandActuator.RegisterFieldCommand(
                        fieldName, entityName, description, entityDescription));
    }

    @Override
    public void updateFieldCommand(
            String fieldName, String entityName, String description, FieldSnapshot snapshot) {
        updateFieldActuator.execute(
                eventService,
                new UpdateFieldCommandActuator.UpdateFieldCommand(
                        fieldName, entityName, description));
    }

    @Override
    public void removeFieldCommand(String fieldName, String entityName) {
        removeFieldActuator.execute(
                eventService,
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
                eventService,
                new AssignFieldCategoryCommandActuator.AssignFieldCategoryCommand(
                        fieldName, entityName, categoryName, categoryDescription));
    }

    @Override
    public void unassignFieldCategoryCommand(
            String fieldName, String entityName, String categoryName, FieldSnapshot snapshot) {
        unassignFieldCategoryActuator.execute(
                eventService,
                new UnassignFieldCategoryCommandActuator.UnassignFieldCategoryCommand(
                        fieldName, entityName, categoryName));
    }

    // -----------------------------------------------------------------------
    // Purpose Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void registerPurposeCommand(String purposeName, String description) {
        registerPurposeActuator.execute(
                eventService,
                new RegisterPurposeCommandActuator.RegisterPurposeCommand(
                        purposeName, description));
    }

    @Override
    public void updatePurposeCommand(
            String purposeName, String description, PurposeSnapshot snapshot) {
        updatePurposeActuator.execute(
                eventService,
                new UpdatePurposeCommandActuator.UpdatePurposeCommand(purposeName, description));
    }

    @Override
    public void removePurposeCommand(String purposeName) {
        removePurposeActuator.execute(
                eventService, new RemovePurposeCommandActuator.RemovePurposeCommand(purposeName));
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
                eventService,
                new LinkPurposeToRegulationCommandActuator.LinkPurposeToRegulationCommand(
                        purposeName, regulationName, lawfulBasis, onGranted, onDenied));
    }

    @Override
    public void unlinkPurposeFromRegulationCommand(
            String purposeName, String regulationName, PurposeSnapshot snapshot) {
        unlinkPurposeFromRegulationActuator.execute(
                eventService,
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
                eventService,
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
                eventService,
                new RemovePurposePolicyCommandActuator.RemovePurposePolicyCommand(
                        purposeName, policyType, fieldName, entityName, categoryName));
    }

    // -----------------------------------------------------------------------
    // Regulation Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void registerRegulationCommand(String regulationName, String description) {
        registerRegulationActuator.execute(
                eventService,
                new RegisterRegulationCommandActuator.RegisterRegulationCommand(
                        regulationName, description));
    }

    @Override
    public void updateRegulationCommand(
            String regulationName, String description, RegulationSnapshot snapshot) {
        updateRegulationActuator.execute(
                eventService,
                new UpdateRegulationCommandActuator.UpdateRegulationCommand(
                        regulationName, description));
    }

    @Override
    public void removeRegulationCommand(String regulationName) {
        removeRegulationActuator.execute(
                eventService,
                new RemoveRegulationCommandActuator.RemoveRegulationCommand(regulationName));
    }

    @Override
    public void addRegulationJurisdictionCommand(
            String regulationName,
            String jurisdictionName,
            String description,
            RegulationSnapshot snapshot) {
        addRegulationJurisdictionActuator.execute(
                eventService,
                new AddRegulationJurisdictionCommandActuator.AddRegulationJurisdictionCommand(
                        regulationName, jurisdictionName, description));
    }

    @Override
    public void removeRegulationJurisdictionCommand(
            String regulationName, String jurisdictionName, RegulationSnapshot snapshot) {
        removeRegulationJurisdictionActuator.execute(
                eventService,
                new RemoveRegulationJurisdictionCommandActuator.RemoveRegulationJurisdictionCommand(
                        regulationName, jurisdictionName));
    }

    // -----------------------------------------------------------------------
    // Jurisdiction Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void registerJurisdictionCommand(String jurisdictionName, String description) {
        registerJurisdictionActuator.execute(
                eventService,
                new RegisterJurisdictionCommandActuator.RegisterJurisdictionCommand(
                        jurisdictionName, description));
    }

    @Override
    public void updateJurisdictionCommand(String jurisdictionName, String description) {
        updateJurisdictionActuator.execute(
                eventService,
                new UpdateJurisdictionCommandActuator.UpdateJurisdictionCommand(
                        jurisdictionName, description));
    }

    @Override
    public void removeJurisdictionCommand(String jurisdictionName) {
        removeJurisdictionActuator.execute(
                eventService,
                new RemoveJurisdictionCommandActuator.RemoveJurisdictionCommand(jurisdictionName));
    }

    // -----------------------------------------------------------------------
    // Subject Aggregate (entity lifecycle)
    // -----------------------------------------------------------------------

    @Override
    public void registerSubjectCommand(String subjectName, String description) {
        registerSubjectActuator.execute(
                eventService,
                new RegisterSubjectCommandActuator.RegisterSubjectCommand(
                        subjectName, description));
    }

    @Override
    public void updateSubjectCommand(String subjectName, String description) {
        updateSubjectActuator.execute(
                eventService,
                new UpdateSubjectCommandActuator.UpdateSubjectCommand(subjectName, description));
    }

    @Override
    public void removeSubjectCommand(String subjectName) {
        removeSubjectActuator.execute(
                eventService, new RemoveSubjectCommandActuator.RemoveSubjectCommand(subjectName));
    }

    // -----------------------------------------------------------------------
    // SubjectConsent Aggregate
    // -----------------------------------------------------------------------

    @Override
    public void grantConsentCommand(
            String subjectId,
            long policyId,
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
                eventService,
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
            long policyId,
            String purposeName,
            String policyType,
            String fieldName,
            String entityName,
            String categoryName,
            String actor,
            String ipAddress,
            String userAgent) {
        revokeConsentActuator.execute(
                eventService,
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
            long policyId,
            String purposeName,
            String policyType,
            String fieldName,
            String entityName,
            String categoryName) {
        expireConsentActuator.execute(
                eventService,
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
                eventService,
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
                eventService,
                new DeleteSubjectAuditCommandActuator.DeleteSubjectAuditCommand(
                        subjectId, deletionRequestId, actor, ipAddress, userAgent));
    }
}
