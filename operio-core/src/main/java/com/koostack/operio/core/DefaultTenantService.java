package com.koostack.operio.core;

import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.models.*;
import com.koostack.operio.core.models.ConsentGraphModels.ConsentGraph;
import com.koostack.operio.core.tenant.authz.AuthzProvider;
import com.koostack.operio.core.tenant.consent.ConsentAdminCommandService;
import com.koostack.operio.core.tenant.consent.ConsentAdminQueryService;
import com.koostack.operio.core.tenant.consent.ConsentGraphQueryService;
import com.koostack.operio.core.tenant.consent.SubjectDsarService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Default {@link TenantService} implementation that composes command and query services into
 * cohesive business operations.
 *
 * <p>Extends {@link Operio} to inherit the builder and plugin-wiring infrastructure. All
 * orchestration logic lives here — validation (ensuring referenced policies exist), enrichment
 * (resolving ConsentScope to denormalized event payloads), and routing to the appropriate command
 * or query service.
 *
 * <p>Command services beneath this layer are pure event publishers.
 */
public class DefaultTenantService extends Operio implements TenantService {

    private static final Logger LOG = Logger.getLogger(DefaultTenantService.class.getName());

    private final ConsentAdminCommandService<?> commandService;
    private final ConsentAdminQueryService<?> queryService;
    private com.koostack.operio.core.metrics.MetricPublisherService<?> platformMetricPublisher;
    private com.koostack.operio.core.metrics.MetricPublisherService<?> tenantMetricPublisher;
    private SubjectDsarService<?> subjectDsarService;

    public DefaultTenantService(
            Builder builder,
            ConsentAdminCommandService<?> commandService,
            ConsentAdminQueryService<?> queryService) {
        super(builder);
        this.commandService = commandService;
        this.queryService = queryService;
    }

    public void setPlatformMetricPublisher(
            com.koostack.operio.core.metrics.MetricPublisherService<?> publisher) {
        this.platformMetricPublisher = publisher;
    }

    public void setTenantMetricPublisher(
            com.koostack.operio.core.metrics.MetricPublisherService<?> publisher) {
        this.tenantMetricPublisher = publisher;
    }

    public void setSubjectDsarService(SubjectDsarService<?> subjectDsarService) {
        this.subjectDsarService = subjectDsarService;
    }

    // -----------------------------------------------------------------------
    // Subject Aggregate operations — key generation + delegation
    // -----------------------------------------------------------------------

    @Override
    public void registerSubject(String subjectName, String description) {
        // DSAR key generation is handled by RegisterSubjectCommandActuator
        commandService().registerSubjectCommand(subjectName, description);
    }

    @Override
    public void updateSubject(String subjectName, String description) {
        commandService().updateSubjectCommand(subjectName, description);
    }

    @Override
    public void removeSubject(String subjectName) {
        commandService().removeSubjectCommand(subjectName);
    }

    @Override
    public void shredSubject(String subjectName) {
        requireDsarService();
        java.util.UUID dsarId = SubjectDsarService.deriveDsarId(subjectName);
        subjectDsarService.deleteKey(dsarId);
    }

    @Override
    public boolean allEventsPublishedForSubject(String subjectName) {
        requireDsarService();
        java.util.UUID dsarId = SubjectDsarService.deriveDsarId(subjectName);
        return subjectDsarService.allEventsPublished(dsarId);
    }

    @Override
    public List<SubjectView> listSubjects() {
        return queryService().listSubjects();
    }

    @Override
    public Optional<SubjectView> getSubject(String subjectName) {
        return queryService().getSubject(subjectName);
    }

    private void requireDsarService() {
        if (subjectDsarService == null) {
            throw new OperioConfigurationException(
                    "SubjectDsarService is not configured — cannot perform DSAR key operations. "
                            + "Ensure a SubjectDsarService plugin is registered.");
        }
    }

    // -----------------------------------------------------------------------
    // Consent write operations — validation, enrichment + delegation
    // -----------------------------------------------------------------------

    @Override
    public void grantConsent(String subject, ConsentScope scope, Instant expiresAt) {
        var policy = resolvePolicy(scope);
        commandService()
                .grantConsentCommand(
                        subject,
                        policy.policyId(),
                        scope.purposeName(),
                        policyTypeStr(policy),
                        fieldNameOrNull(policy),
                        entityNameOrNull(policy),
                        categoryNameOrNull(policy),
                        RequestContext.actor(),
                        RequestContext.ipAddress(),
                        RequestContext.userAgent(),
                        expiresAt);
    }

    @Override
    public void revokeConsent(String subject, ConsentScope scope) {
        var policy = resolvePolicy(scope);
        commandService()
                .revokeConsentCommand(
                        subject,
                        policy.policyId(),
                        scope.purposeName(),
                        policyTypeStr(policy),
                        fieldNameOrNull(policy),
                        entityNameOrNull(policy),
                        categoryNameOrNull(policy),
                        RequestContext.actor(),
                        RequestContext.ipAddress(),
                        RequestContext.userAgent());
    }

    @Override
    public void revokeAllConsent(String subject) {
        commandService()
                .revokeAllConsentCommand(
                        subject,
                        RequestContext.actor(),
                        RequestContext.ipAddress(),
                        RequestContext.userAgent());
    }

    @Override
    public BatchConsentResult grantAllConsent(String subject, List<BatchConsentEntry> entries) {
        int succeeded = 0;
        List<BatchConsentResult.BatchItemError> errors = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            BatchConsentEntry entry = entries.get(i);
            if (entry == null) {
                errors.add(new BatchConsentResult.BatchItemError(i, null, "Null entry in batch"));
                continue;
            }
            try {
                grantConsent(subject, entry.scope(), entry.expiresAt());
                succeeded++;
            } catch (Exception e) {
                errors.add(new BatchConsentResult.BatchItemError(i, entry.scope(), e.getMessage()));
            }
        }
        return new BatchConsentResult(succeeded, errors.size(), List.copyOf(errors));
    }

    @Override
    public BatchConsentResult revokeAllConsent(String subject, List<BatchConsentEntry> entries) {
        int succeeded = 0;
        List<BatchConsentResult.BatchItemError> errors = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            BatchConsentEntry entry = entries.get(i);
            if (entry == null) {
                errors.add(new BatchConsentResult.BatchItemError(i, null, "Null entry in batch"));
                continue;
            }
            try {
                revokeConsent(subject, entry.scope());
                succeeded++;
            } catch (Exception e) {
                errors.add(new BatchConsentResult.BatchItemError(i, entry.scope(), e.getMessage()));
            }
        }
        return new BatchConsentResult(succeeded, errors.size(), List.copyOf(errors));
    }

    // -----------------------------------------------------------------------
    // Entity Aggregate operations
    // -----------------------------------------------------------------------

    @Override
    public void registerEntity(String entityName, String description) {
        commandService().registerEntityCommand(entityName, description);
    }

    @Override
    public void updateEntity(String entityName, String description) {
        commandService().updateEntityCommand(entityName, description);
    }

    @Override
    public void removeEntity(String entityName) {
        boolean hasFields =
                listFields().stream()
                        .anyMatch(
                                f ->
                                        f.entity() != null
                                                && entityName.equals(f.entity().entityName()));
        if (hasFields) {
            throw new IllegalStateException(
                    "Cannot remove entity '"
                            + entityName
                            + "' — it still has fields assigned. Remove them first.");
        }
        commandService().removeEntityCommand(entityName);
    }

    // -----------------------------------------------------------------------
    // Category Aggregate operations
    // -----------------------------------------------------------------------

    @Override
    public void registerCategory(String categoryName, String description) {
        commandService().registerCategoryCommand(categoryName, description);
    }

    @Override
    public void updateCategory(String categoryName, String description) {
        commandService().updateCategoryCommand(categoryName, description);
    }

    @Override
    public void removeCategory(String categoryName) {
        boolean hasAssignments =
                listFields().stream()
                        .anyMatch(
                                f ->
                                        f.categories().stream()
                                                .anyMatch(
                                                        c ->
                                                                categoryName.equals(
                                                                        c.categoryName())));
        if (hasAssignments) {
            throw new IllegalStateException(
                    "Cannot remove category '"
                            + categoryName
                            + "' — it is still assigned to fields. Unassign it first.");
        }
        commandService().removeCategoryCommand(categoryName);
    }

    // -----------------------------------------------------------------------
    // Field Aggregate operations
    // -----------------------------------------------------------------------

    @Override
    public void registerField(
            String fieldName, String entityName, String description, String entityDescription) {
        commandService()
                .registerFieldCommand(fieldName, entityName, description, entityDescription);
    }

    @Override
    public void updateField(String fieldName, String entityName, String description) {
        commandService().updateFieldCommand(fieldName, entityName, description, null);
    }

    @Override
    public void removeField(String fieldName, String entityName) {
        commandService().removeFieldCommand(fieldName, entityName);
    }

    @Override
    public void assignFieldCategory(
            String fieldName, String entityName, String categoryName, String categoryDescription) {
        commandService()
                .assignFieldCategoryCommand(
                        fieldName, entityName, categoryName, categoryDescription, null);
    }

    @Override
    public void unassignFieldCategory(String fieldName, String entityName, String categoryName) {
        commandService().unassignFieldCategoryCommand(fieldName, entityName, categoryName, null);
    }

    // -----------------------------------------------------------------------
    // Purpose Aggregate operations
    // -----------------------------------------------------------------------

    @Override
    public void registerPurpose(String purposeName, String description) {
        commandService().registerPurposeCommand(purposeName, description);
    }

    @Override
    public void updatePurpose(String purposeName, String description) {
        commandService().updatePurposeCommand(purposeName, description, null);
    }

    @Override
    public void removePurpose(String purposeName) {
        commandService().removePurposeCommand(purposeName);
    }

    @Override
    public void linkPurposeToRegulation(
            String purposeName,
            String regulationName,
            String lawfulBasis,
            String onGranted,
            String onDenied) {
        commandService()
                .linkPurposeToRegulationCommand(
                        purposeName, regulationName, lawfulBasis, onGranted, onDenied, null);
    }

    @Override
    public void unlinkPurposeFromRegulation(String purposeName, String regulationName) {
        commandService().unlinkPurposeFromRegulationCommand(purposeName, regulationName, null);
    }

    @Override
    public void addPurposePolicy(
            String purposeName,
            String policyType,
            String fieldName,
            String entityName,
            String categoryName) {
        commandService()
                .addPurposePolicyCommand(
                        purposeName, policyType, fieldName, entityName, categoryName, null);
    }

    @Override
    public void removePurposePolicy(
            String purposeName,
            String policyType,
            String fieldName,
            String entityName,
            String categoryName) {
        commandService()
                .removePurposePolicyCommand(
                        purposeName, policyType, fieldName, entityName, categoryName, null);
    }

    // -----------------------------------------------------------------------
    // Jurisdiction Aggregate operations
    // -----------------------------------------------------------------------

    @Override
    public void registerJurisdiction(String jurisdictionName, String description) {
        commandService().registerJurisdictionCommand(jurisdictionName, description);
    }

    @Override
    public void updateJurisdiction(String jurisdictionName, String description) {
        commandService().updateJurisdictionCommand(jurisdictionName, description);
    }

    @Override
    public void removeJurisdiction(String jurisdictionName) {
        boolean hasAssignments =
                listRegulations().stream()
                        .anyMatch(
                                r ->
                                        r.activeJurisdictions().stream()
                                                .anyMatch(
                                                        j ->
                                                                jurisdictionName.equals(
                                                                        j.jurisdictionName())));
        if (hasAssignments) {
            throw new IllegalStateException(
                    "Cannot remove jurisdiction '"
                            + jurisdictionName
                            + "' — it is still assigned to regulations. Remove it first.");
        }
        commandService().removeJurisdictionCommand(jurisdictionName);
    }

    // -----------------------------------------------------------------------
    // Regulation Aggregate operations
    // -----------------------------------------------------------------------

    @Override
    public void registerRegulation(String regulationName, String description) {
        commandService().registerRegulationCommand(regulationName, description);
    }

    @Override
    public void updateRegulation(String regulationName, String description) {
        commandService().updateRegulationCommand(regulationName, description, null);
    }

    @Override
    public void removeRegulation(String regulationName) {
        commandService().removeRegulationCommand(regulationName);
    }

    @Override
    public void addRegulationJurisdiction(
            String regulationName, String jurisdictionName, String description) {
        commandService()
                .addRegulationJurisdictionCommand(
                        regulationName, jurisdictionName, description, null);
    }

    @Override
    public void removeRegulationJurisdiction(String regulationName, String jurisdictionName) {
        commandService()
                .removeRegulationJurisdictionCommand(regulationName, jurisdictionName, null);
    }

    // -----------------------------------------------------------------------
    // Aggregate queries
    // -----------------------------------------------------------------------

    @Override
    public List<EntityView> listEntities() {
        return queryService().listEntities();
    }

    @Override
    public Optional<EntityView> getEntity(String entityName) {
        return queryService().getEntity(entityName);
    }

    @Override
    public List<CategoryView> listCategories() {
        return queryService().listCategories();
    }

    @Override
    public Optional<CategoryView> getCategory(String categoryName) {
        return queryService().getCategory(categoryName);
    }

    @Override
    public List<FieldView> listFields() {
        return queryService().listFields();
    }

    @Override
    public FieldView getField(String fieldName, String entityName) {
        return queryService().getField(fieldName, entityName);
    }

    @Override
    public List<PurposeView> listPurposes() {
        return queryService().listPurposes();
    }

    @Override
    public PurposeView getPurpose(String purposeName) {
        return queryService().getPurpose(purposeName);
    }

    @Override
    public List<RegulationView> listRegulations() {
        return queryService().listRegulations();
    }

    @Override
    public RegulationView getRegulation(String regulationName) {
        return queryService().getRegulation(regulationName);
    }

    @Override
    public List<JurisdictionView> listJurisdictions() {
        return queryService().listJurisdictions();
    }

    @Override
    public Optional<JurisdictionView> getJurisdiction(String jurisdictionName) {
        return queryService().getJurisdiction(jurisdictionName);
    }

    @Override
    public SubjectConsentView getSubjectConsent(String subjectId) {
        return queryService().getSubjectConsent(subjectId);
    }

    @Override
    public int countSubjectConsentEntries(String subjectId) {
        return queryService().countSubjectConsentEntries(subjectId);
    }

    // -----------------------------------------------------------------------
    // Consent evaluation
    // -----------------------------------------------------------------------

    @Override
    public ConsentResponse evaluateConsent(String subject, Set<ConsentScope> scopes) {
        return consentService().evaluateConsent(subject, scopes);
    }

    @Override
    public ConsentGraph getConsentGraph(
            String cursor, int limit, String rootType, String rootName, Integer depth) {
        ConsentGraphQueryService<?> graphService = consentGraphService();
        if (graphService == null) {
            throw new UnsupportedOperationException("Consent graph not available");
        }
        return graphService.getConsentGraph(cursor, limit, rootType, rootName, depth);
    }

    // -----------------------------------------------------------------------
    // Read path — consent evaluation + data masking
    // -----------------------------------------------------------------------

    @Override
    public Object access(Object rawData) {
        var identity = identityResolver().resolve();
        var scopes = sensitiveFieldResolver().resolveScopes(rawData);
        var response = consentService().evaluateConsent(identity.subject(), scopes);
        for (ConsentScope scope : response.granted()) {
            auditLogger().logAccessGranted(identity.subject(), scope);
        }
        for (ConsentScope scope : response.denied()) {
            auditLogger()
                    .logAccessDenied(
                            identity.subject(), scope, response.reasons().get(scope.fieldName()));
        }
        return maskingStrategy().mask(rawData, response.denied());
    }

    // -----------------------------------------------------------------------
    // Discovery pipeline
    // -----------------------------------------------------------------------

    @Override
    public DiscoveryResult syncDiscoveredFields() {
        var discovery = sensitiveDiscovery();
        if (discovery == null) {
            throw new OperioConfigurationException("No SensitiveDiscovery plugin configured.");
        }

        DiscoveryResult result = discovery.discover();
        LOG.info(
                () ->
                        "SensitiveDiscovery found "
                                + result.fields().size()
                                + " field(s) and "
                                + result.categories().size()
                                + " category/ies");

        for (DiscoveredField field : result.fields()) {
            registerField(field.fieldName(), field.entityName(), field.description(), null);
        }
        for (DiscoveredField field : result.fields()) {
            if (field.category() != null && !field.category().isBlank()) {
                assignFieldCategory(field.fieldName(), field.entityName(), field.category(), null);
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // DSAR
    // -----------------------------------------------------------------------

    @Override
    public void deleteSubjectAudit(String subject, String deletionRequestId) {
        commandService()
                .deleteSubjectAuditCommand(
                        subject,
                        deletionRequestId,
                        RequestContext.actor(),
                        RequestContext.ipAddress(),
                        RequestContext.userAgent());
    }

    @Override
    public ErasureReceipt eraseSubject(String subjectId, String requestId) {
        long ledgerEntriesRevoked = 0;
        long cachedKeysDeleted = 0;
        String status = "PENDING_SHRED";

        // Step 1: Count consent entries and bulk-revoke
        try {
            ledgerEntriesRevoked = countSubjectConsentEntries(subjectId);
            revokeAllConsent(subjectId);
        } catch (Exception e) {
            LOG.log(
                    java.util.logging.Level.SEVERE,
                    "eraseSubject: failed to revoke consent for " + subjectId,
                    e);
            status = "PARTIAL_FAILURE";
        }

        // Step 2: Delete cached consent-decision keys
        try {
            cachedKeysDeleted = deleteCachedKeysForSubject(subjectId);
        } catch (Exception e) {
            LOG.log(
                    java.util.logging.Level.SEVERE,
                    "eraseSubject: failed to delete cached keys for " + subjectId,
                    e);
            status = "PARTIAL_FAILURE";
        }

        // Step 3: Remove subject entity
        try {
            removeSubject(subjectId);
        } catch (Exception e) {
            LOG.log(
                    java.util.logging.Level.SEVERE,
                    "eraseSubject: failed to remove subject " + subjectId,
                    e);
            status = "PARTIAL_FAILURE";
        }

        // Step 4: Log DSAR audit event (deletes ledger + writes audit log)
        try {
            deleteSubjectAudit(subjectId, requestId);
        } catch (Exception e) {
            LOG.log(
                    java.util.logging.Level.SEVERE,
                    "eraseSubject: failed to log DSAR audit for " + subjectId,
                    e);
            status = "PARTIAL_FAILURE";
        }

        return new ErasureReceipt(
                subjectId,
                requestId,
                ledgerEntriesRevoked,
                cachedKeysDeleted,
                status,
                Instant.now());
    }

    @Override
    public long countCachedKeysForSubject(String subject) {
        var decisionService = consentService();
        return decisionService != null ? decisionService.countCachedKeysForSubject(subject) : 0;
    }

    @Override
    public long deleteCachedKeysForSubject(String subject) {
        var decisionService = consentService();
        return decisionService != null ? decisionService.deleteCachedKeysForSubject(subject) : 0;
    }

    // -----------------------------------------------------------------------
    // IAM — Role Management
    // -----------------------------------------------------------------------

    @Override
    public void assignUserRole(String userId, String role) {
        AuthzProvider<?> authz = authzProvider();
        if (authz == null) {
            throw new UnsupportedOperationException(
                    "IAM role management not available — no AuthzProvider configured");
        }
        authz.assignRole(TenantContext.require(), userId, role);
    }

    @Override
    public void removeUserRole(String userId, String role) {
        AuthzProvider<?> authz = authzProvider();
        if (authz == null) {
            throw new UnsupportedOperationException(
                    "IAM role management not available — no AuthzProvider configured");
        }
        authz.removeRole(TenantContext.require(), userId, role);
    }

    @Override
    public Set<String> listUserRoles(String userId) {
        AuthzProvider<?> authz = authzProvider();
        if (authz == null) {
            throw new UnsupportedOperationException(
                    "IAM role management not available — no AuthzProvider configured");
        }
        return authz.getUserRoles(TenantContext.require(), userId);
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public ConsentAdminCommandService<?> commandService() {
        if (commandService == null) {
            throw new OperioConfigurationException(
                    "ConsentAdminCommandService is not configured — "
                            + "write operations require a command service implementation");
        }
        return commandService;
    }

    public ConsentAdminQueryService<?> queryService() {
        if (queryService == null) {
            throw new OperioConfigurationException(
                    "ConsentAdminQueryService is not configured — "
                            + "query operations require a query service implementation");
        }
        return queryService;
    }

    // -----------------------------------------------------------------------
    // Policy resolution — ConsentScope → policy_id + denormalized names
    // -----------------------------------------------------------------------

    private PurposeView.PolicyView resolvePolicy(ConsentScope scope) {
        String policyType = !isWildcardOrBlank(scope.fieldName()) ? "FIELD" : "CATEGORY";
        PurposeView.PolicyView policy =
                queryService()
                        .findPolicy(
                                scope.purposeName(),
                                policyType,
                                isWildcardOrBlank(scope.fieldName()) ? null : scope.fieldName(),
                                isWildcardOrBlank(scope.entityName()) ? null : scope.entityName(),
                                isWildcardOrBlank(scope.category()) ? null : scope.category());
        if (policy == null) {
            throw new IllegalArgumentException(
                    "No matching policy found for scope: "
                            + scope
                            + " — policies must be created before consent can be granted or"
                            + " revoked");
        }
        return policy;
    }

    private static String policyTypeStr(PurposeView.PolicyView policy) {
        return policy.policyType();
    }

    private static String fieldNameOrNull(PurposeView.PolicyView policy) {
        return policy.field() != null ? policy.field().fieldName() : null;
    }

    private static String entityNameOrNull(PurposeView.PolicyView policy) {
        return policy.field() != null ? policy.field().entityName() : null;
    }

    private static String categoryNameOrNull(PurposeView.PolicyView policy) {
        return policy.category() != null ? policy.category().categoryName() : null;
    }

    private static boolean isWildcardOrBlank(String value) {
        return value == null || value.isBlank() || ConsentScope.WILDCARD.equals(value);
    }
}
