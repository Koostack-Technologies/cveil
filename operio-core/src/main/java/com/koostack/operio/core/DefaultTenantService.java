package com.koostack.operio.core;

import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.models.*;
import com.koostack.operio.core.models.ConsentGraphModels.ConsentGraph;
import com.koostack.operio.core.tenant.authz.AuthzProvider;
import com.koostack.operio.core.tenant.consent.ConsentAdminCommandService;
import com.koostack.operio.core.tenant.consent.ConsentAdminQueryService;
import com.koostack.operio.core.tenant.consent.ConsentGraphQueryService;
import com.koostack.operio.core.tenant.consent.SubjectDsarService;
import com.koostack.operio.core.tenant.consent.events.FieldSnapshot;
import com.koostack.operio.core.tenant.consent.events.PurposeSnapshot;
import com.koostack.operio.core.tenant.consent.events.RegulationSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;
import javax.crypto.KeyGenerator;

/**
 * Default {@link TenantService} implementation that composes command and
 * query services into cohesive business operations.
 *
 * <p>Extends {@link Operio} to inherit the builder and plugin-wiring
 * infrastructure. All orchestration logic lives here — validation
 * (ensuring referenced policies exist), enrichment (resolving
 * ConsentScope to denormalized event payloads), and routing to the
 * appropriate command or query service.
 *
 * <p>Command services beneath this layer are pure event publishers.
 */
public class DefaultTenantService extends Operio implements TenantService {

    private static final Logger LOG = Logger.getLogger(
        DefaultTenantService.class.getName()
    );

    private final ConsentAdminCommandService<?> commandService;
    private final ConsentAdminQueryService<?> queryService;
    private com.koostack.operio.core.metrics.MetricPublisherService<?> platformMetricPublisher;
    private com.koostack.operio.core.metrics.MetricPublisherService<?> tenantMetricPublisher;
    private UnaryOperator<String> keyEncryptor = UnaryOperator.identity();
    private SubjectDsarService<?> subjectDsarService;

    public DefaultTenantService(
        Builder builder,
        ConsentAdminCommandService<?> commandService,
        ConsentAdminQueryService<?> queryService
    ) {
        super(builder);
        this.commandService = commandService;
        this.queryService = queryService;
    }

    public void setPlatformMetricPublisher(com.koostack.operio.core.metrics.MetricPublisherService<?> publisher) {
        this.platformMetricPublisher = publisher;
    }

    public void setTenantMetricPublisher(com.koostack.operio.core.metrics.MetricPublisherService<?> publisher) {
        this.tenantMetricPublisher = publisher;
    }

    public void setKeyEncryptor(UnaryOperator<String> keyEncryptor) {
        this.keyEncryptor = keyEncryptor != null ? keyEncryptor : UnaryOperator.identity();
    }

    public void setSubjectDsarService(SubjectDsarService<?> subjectDsarService) {
        this.subjectDsarService = subjectDsarService;
    }

    // -----------------------------------------------------------------------
    // Subject Aggregate operations — key generation + delegation
    // -----------------------------------------------------------------------

    @Override
    public void registerSubject(String subjectName, String description) {
        requireDsarService();
        // Generate per-subject DSAR key and persist to subject_dsar synchronously
        // BEFORE publishing the event — the EventService needs this key to encrypt
        // the shreddable payload.
        String rawKey = generateDsarKey();
        String encryptedKey = keyEncryptor.apply(rawKey);
        java.util.UUID dsarId = SubjectDsarService.deriveDsarId(subjectName);
        subjectDsarService.saveKey(dsarId, encryptedKey);
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

    private String generateDsarKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256);
            byte[] keyBytes = keyGen.generateKey().getEncoded();
            return Base64.getEncoder().encodeToString(keyBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate AES-256 DSAR key", e);
        }
    }

    private void requireDsarService() {
        if (subjectDsarService == null) {
            throw new OperioConfigurationException(
                "SubjectDsarService is not configured — cannot perform DSAR key operations. "
                + "Ensure a SubjectDsarService plugin is registered."
            );
        }
    }

    // -----------------------------------------------------------------------
    // Consent write operations — validation, enrichment + delegation
    // -----------------------------------------------------------------------

    @Override
    public void grantConsent(String subject, ConsentScope scope, Instant expiresAt) {
        var policy = resolvePolicy(scope);
        commandService().grantConsentCommand(
            subject, policy.policyId(),
            scope.purposeName(), policyTypeStr(policy),
            fieldNameOrNull(policy), entityNameOrNull(policy),
            categoryNameOrNull(policy),
            RequestContext.actor(), RequestContext.ipAddress(),
            RequestContext.userAgent(), expiresAt
        );
    }

    @Override
    public void revokeConsent(String subject, ConsentScope scope) {
        var policy = resolvePolicy(scope);
        commandService().revokeConsentCommand(
            subject, policy.policyId(),
            scope.purposeName(), policyTypeStr(policy),
            fieldNameOrNull(policy), entityNameOrNull(policy),
            categoryNameOrNull(policy),
            RequestContext.actor(), RequestContext.ipAddress(),
            RequestContext.userAgent()
        );
    }

    @Override
    public void revokeAllConsent(String subject) {
        commandService().revokeAllConsentCommand(
            subject, RequestContext.actor(),
            RequestContext.ipAddress(), RequestContext.userAgent()
        );
    }

    @Override
    public BatchConsentResult grantAllConsent(
        String subject, List<BatchConsentEntry> entries
    ) {
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
    public BatchConsentResult revokeAllConsent(
        String subject, List<BatchConsentEntry> entries
    ) {
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
        boolean hasFields = listFields().stream()
            .anyMatch(f -> f.entity() != null
                && entityName.equals(f.entity().entityName()));
        if (hasFields) {
            throw new IllegalStateException(
                "Cannot remove entity '" + entityName
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
        boolean hasAssignments = listFields().stream()
            .anyMatch(f -> f.categories().stream()
                .anyMatch(c -> categoryName.equals(c.categoryName())));
        if (hasAssignments) {
            throw new IllegalStateException(
                "Cannot remove category '" + categoryName
                + "' — it is still assigned to fields. Unassign it first.");
        }
        commandService().removeCategoryCommand(categoryName);
    }

    // -----------------------------------------------------------------------
    // Field Aggregate operations
    // -----------------------------------------------------------------------

    @Override
    public void registerField(String fieldName, String entityName,
                              String description, String entityDescription) {
        commandService().registerFieldCommand(fieldName, entityName, description, entityDescription);
    }

    @Override
    public void updateField(String fieldName, String entityName, String description) {
        FieldSnapshot snapshot = buildFieldSnapshot(fieldName, entityName, description);
        commandService().updateFieldCommand(fieldName, entityName, description, snapshot);
    }

    @Override
    public void removeField(String fieldName, String entityName) {
        commandService().removeFieldCommand(fieldName, entityName);
    }

    @Override
    public void assignFieldCategory(String fieldName, String entityName,
                                    String categoryName, String categoryDescription) {
        FieldView current = queryService().getField(fieldName, entityName);
        List<String> newCategories = new ArrayList<>();
        if (current != null) {
            current.categories().forEach(c -> newCategories.add(c.categoryName()));
        }
        if (!newCategories.contains(categoryName)) {
            newCategories.add(categoryName);
        }
        var snapshot = new FieldSnapshot(
                fieldName,
                entityName,
                current != null ? current.description() : null,
                List.copyOf(newCategories));
        commandService().assignFieldCategoryCommand(
            fieldName, entityName, categoryName, categoryDescription, snapshot
        );
    }

    @Override
    public void unassignFieldCategory(String fieldName, String entityName,
                                      String categoryName) {
        FieldView current = queryService().getField(fieldName, entityName);
        List<String> newCategories = new ArrayList<>();
        if (current != null) {
            current.categories().stream()
                .map(FieldView.CategoryView::categoryName)
                .filter(name -> !name.equals(categoryName))
                .forEach(newCategories::add);
        }
        var snapshot = new FieldSnapshot(
                fieldName,
                entityName,
                current != null ? current.description() : null,
                List.copyOf(newCategories));
        commandService().unassignFieldCategoryCommand(fieldName, entityName, categoryName, snapshot);
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
        PurposeSnapshot snapshot = buildPurposeSnapshot(purposeName, description);
        commandService().updatePurposeCommand(purposeName, description, snapshot);
    }

    @Override
    public void removePurpose(String purposeName) {
        commandService().removePurposeCommand(purposeName);
    }

    @Override
    public void linkPurposeToRegulation(String purposeName, String regulationName,
                                        String lawfulBasis, String onGranted,
                                        String onDenied) {
        PurposeView current = queryService().getPurpose(purposeName);
        List<PurposeSnapshot.RegulationLinkSnapshot> regs = new ArrayList<>();
        List<PurposeSnapshot.PolicySnapshot> policies = List.of();
        if (current != null) {
            current.regulations().stream()
                .filter(r -> !r.regulationName().equals(regulationName))
                .map(r -> new PurposeSnapshot.RegulationLinkSnapshot(
                    r.regulationName(), r.lawfulBasis(), r.onGranted(), r.onDenied()))
                .forEach(regs::add);
            policies = toPolicySnapshots(current);
        }
        regs.add(new PurposeSnapshot.RegulationLinkSnapshot(
            regulationName, lawfulBasis, onGranted, onDenied));
        var snapshot = new PurposeSnapshot(
                purposeName,
                current != null ? current.description() : null,
                List.copyOf(regs), policies);
        commandService().linkPurposeToRegulationCommand(
            purposeName, regulationName, lawfulBasis, onGranted, onDenied, snapshot
        );
    }

    @Override
    public void unlinkPurposeFromRegulation(String purposeName, String regulationName) {
        PurposeView current = queryService().getPurpose(purposeName);
        List<PurposeSnapshot.RegulationLinkSnapshot> regs = new ArrayList<>();
        List<PurposeSnapshot.PolicySnapshot> policies = List.of();
        if (current != null) {
            current.regulations().stream()
                .filter(r -> !r.regulationName().equals(regulationName))
                .map(r -> new PurposeSnapshot.RegulationLinkSnapshot(
                    r.regulationName(), r.lawfulBasis(), r.onGranted(), r.onDenied()))
                .forEach(regs::add);
            policies = toPolicySnapshots(current);
        }
        var snapshot = new PurposeSnapshot(
                purposeName,
                current != null ? current.description() : null,
                List.copyOf(regs), policies);
        commandService().unlinkPurposeFromRegulationCommand(purposeName, regulationName, snapshot);
    }

    @Override
    public void addPurposePolicy(String purposeName, String policyType,
                                 String fieldName, String entityName,
                                 String categoryName) {
        PurposeView current = queryService().getPurpose(purposeName);
        List<PurposeSnapshot.RegulationLinkSnapshot> regs = List.of();
        List<PurposeSnapshot.PolicySnapshot> policies = new ArrayList<>();
        if (current != null) {
            regs = toRegulationLinkSnapshots(current);
            toPolicySnapshots(current).forEach(policies::add);
        }
        policies.add(new PurposeSnapshot.PolicySnapshot(
            policyType, fieldName, entityName, categoryName));
        var snapshot = new PurposeSnapshot(
                purposeName,
                current != null ? current.description() : null,
                regs, List.copyOf(policies));
        commandService().addPurposePolicyCommand(
            purposeName, policyType, fieldName, entityName, categoryName, snapshot
        );
    }

    @Override
    public void removePurposePolicy(String purposeName, String policyType,
                                    String fieldName, String entityName,
                                    String categoryName) {
        PurposeView current = queryService().getPurpose(purposeName);
        List<PurposeSnapshot.RegulationLinkSnapshot> regs = List.of();
        List<PurposeSnapshot.PolicySnapshot> policies = new ArrayList<>();
        if (current != null) {
            regs = toRegulationLinkSnapshots(current);
            current.policies().stream()
                .filter(p -> !(p.policyType().equals(policyType)
                    && equalsNullable(fieldNameOrNull(p), fieldName)
                    && equalsNullable(entityNameOrNull(p), entityName)
                    && equalsNullable(categoryNameOrNull(p), categoryName)))
                .map(p -> new PurposeSnapshot.PolicySnapshot(
                    p.policyType(), fieldNameOrNull(p),
                    entityNameOrNull(p), categoryNameOrNull(p)))
                .forEach(policies::add);
        }
        var snapshot = new PurposeSnapshot(
                purposeName,
                current != null ? current.description() : null,
                regs, List.copyOf(policies));
        commandService().removePurposePolicyCommand(
            purposeName, policyType, fieldName, entityName, categoryName, snapshot
        );
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
        boolean hasAssignments = listRegulations().stream()
            .anyMatch(r -> r.activeJurisdictions().stream()
                .anyMatch(j -> jurisdictionName.equals(j.jurisdictionName())));
        if (hasAssignments) {
            throw new IllegalStateException(
                "Cannot remove jurisdiction '" + jurisdictionName
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
        RegulationSnapshot snapshot = buildRegulationSnapshot(regulationName, description);
        commandService().updateRegulationCommand(regulationName, description, snapshot);
    }

    @Override
    public void removeRegulation(String regulationName) {
        commandService().removeRegulationCommand(regulationName);
    }

    @Override
    public void addRegulationJurisdiction(String regulationName,
                                          String jurisdictionName,
                                          String description) {
        RegulationView current = queryService().getRegulation(regulationName);
        List<String> jurisdictions = new ArrayList<>();
        String regDescription = null;
        if (current != null) {
            current.activeJurisdictions().forEach(
                j -> jurisdictions.add(j.jurisdictionName()));
            regDescription = current.description();
        }
        if (!jurisdictions.contains(jurisdictionName)) {
            jurisdictions.add(jurisdictionName);
        }
        var snapshot = new RegulationSnapshot(
                regulationName, regDescription, List.copyOf(jurisdictions));
        commandService().addRegulationJurisdictionCommand(
            regulationName, jurisdictionName, description, snapshot
        );
    }

    @Override
    public void removeRegulationJurisdiction(String regulationName,
                                             String jurisdictionName) {
        RegulationView current = queryService().getRegulation(regulationName);
        List<String> jurisdictions = new ArrayList<>();
        String regDescription = null;
        if (current != null) {
            current.activeJurisdictions().stream()
                .map(RegulationView.JurisdictionView::jurisdictionName)
                .filter(name -> !name.equals(jurisdictionName))
                .forEach(jurisdictions::add);
            regDescription = current.description();
        }
        var snapshot = new RegulationSnapshot(
                regulationName, regDescription, List.copyOf(jurisdictions));
        commandService().removeRegulationJurisdictionCommand(
            regulationName, jurisdictionName, snapshot);
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
    public ConsentGraph getConsentGraph(String cursor, int limit,
                                        String rootType, String rootName,
                                        Integer depth) {
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
        var response = consentService().evaluateConsent(
            identity.subject(), scopes
        );
        for (ConsentScope scope : response.granted()) {
            auditLogger().logAccessGranted(identity.subject(), scope);
        }
        for (ConsentScope scope : response.denied()) {
            auditLogger().logAccessDenied(
                identity.subject(), scope,
                response.reasons().get(scope.fieldName())
            );
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
            throw new OperioConfigurationException(
                "No SensitiveDiscovery plugin configured."
            );
        }

        DiscoveryResult result = discovery.discover();
        LOG.info(() -> "SensitiveDiscovery found " + result.fields().size()
            + " field(s) and " + result.categories().size() + " category/ies");

        for (DiscoveredField field : result.fields()) {
            registerField(field.fieldName(), field.entityName(), field.description(), null);
        }
        for (DiscoveredField field : result.fields()) {
            if (field.category() != null && !field.category().isBlank()) {
                assignFieldCategory(
                    field.fieldName(), field.entityName(), field.category(), null
                );
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // DSAR
    // -----------------------------------------------------------------------

    @Override
    public void deleteSubjectAudit(String subject, String deletionRequestId) {
        commandService().deleteSubjectAuditCommand(
            subject, deletionRequestId,
            RequestContext.actor(), RequestContext.ipAddress(),
            RequestContext.userAgent()
        );
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
            LOG.log(java.util.logging.Level.SEVERE,
                "eraseSubject: failed to revoke consent for " + subjectId, e);
            status = "PARTIAL_FAILURE";
        }

        // Step 2: Delete cached consent-decision keys
        try {
            cachedKeysDeleted = deleteCachedKeysForSubject(subjectId);
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.SEVERE,
                "eraseSubject: failed to delete cached keys for " + subjectId, e);
            status = "PARTIAL_FAILURE";
        }

        // Step 3: Remove subject entity
        try {
            removeSubject(subjectId);
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.SEVERE,
                "eraseSubject: failed to remove subject " + subjectId, e);
            status = "PARTIAL_FAILURE";
        }

        // Step 4: Log DSAR audit event (deletes ledger + writes audit log)
        try {
            deleteSubjectAudit(subjectId, requestId);
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.SEVERE,
                "eraseSubject: failed to log DSAR audit for " + subjectId, e);
            status = "PARTIAL_FAILURE";
        }

        return new ErasureReceipt(
            subjectId, requestId, ledgerEntriesRevoked, cachedKeysDeleted,
            status, Instant.now()
        );
    }

    @Override
    public long countCachedKeysForSubject(String subject) {
        var decisionService = consentService();
        try {
            var method = decisionService.getClass()
                .getMethod("countCachedKeysForSubject", String.class);
            return (long) method.invoke(decisionService, subject);
        } catch (NoSuchMethodException e) {
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public long deleteCachedKeysForSubject(String subject) {
        var decisionService = consentService();
        try {
            var method = decisionService.getClass()
                .getMethod("deleteAllCachedKeysForSubject", String.class);
            return (long) method.invoke(decisionService, subject);
        } catch (NoSuchMethodException e) {
            return 0;
        } catch (Exception e) {
            return 0;
        }
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
                "ConsentAdminCommandService is not configured — " +
                "write operations require a command service implementation");
        }
        return commandService;
    }

    public ConsentAdminQueryService<?> queryService() {
        if (queryService == null) {
            throw new OperioConfigurationException(
                "ConsentAdminQueryService is not configured — " +
                "query operations require a query service implementation");
        }
        return queryService;
    }

    // -----------------------------------------------------------------------
    // Policy resolution — ConsentScope → policy_id + denormalized names
    // -----------------------------------------------------------------------

    private PurposeView.PolicyView resolvePolicy(ConsentScope scope) {
        String policyType = !isWildcardOrBlank(scope.fieldName()) ? "FIELD" : "CATEGORY";
        PurposeView.PolicyView policy = queryService().findPolicy(
            scope.purposeName(), policyType,
            isWildcardOrBlank(scope.fieldName()) ? null : scope.fieldName(),
            isWildcardOrBlank(scope.entityName()) ? null : scope.entityName(),
            isWildcardOrBlank(scope.category()) ? null : scope.category()
        );
        if (policy == null) {
            throw new IllegalArgumentException(
                "No matching policy found for scope: " + scope
                + " — policies must be created before consent can be granted or revoked"
            );
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

    // -----------------------------------------------------------------------
    // Snapshot builders — compute post-mutation aggregate state for enrichment
    // -----------------------------------------------------------------------

    private FieldSnapshot buildFieldSnapshot(String fieldName, String entityName,
                                             String newDescription) {
        FieldView current = queryService().getField(fieldName, entityName);
        List<String> categoryNames = current != null
            ? current.categories().stream()
                .map(FieldView.CategoryView::categoryName).toList()
            : List.of();
        return new FieldSnapshot(fieldName, entityName, newDescription, categoryNames);
    }

    private PurposeSnapshot buildPurposeSnapshot(String purposeName, String newDescription) {
        PurposeView current = queryService().getPurpose(purposeName);
        List<PurposeSnapshot.RegulationLinkSnapshot> regs = current != null
            ? toRegulationLinkSnapshots(current) : List.of();
        List<PurposeSnapshot.PolicySnapshot> policies = current != null
            ? toPolicySnapshots(current) : List.of();
        return new PurposeSnapshot(purposeName, newDescription, regs, policies);
    }

    private RegulationSnapshot buildRegulationSnapshot(String regulationName,
                                                       String newDescription) {
        RegulationView current = queryService().getRegulation(regulationName);
        List<String> jurisdictions = current != null
            ? current.activeJurisdictions().stream()
                .map(RegulationView.JurisdictionView::jurisdictionName).toList()
            : List.of();
        return new RegulationSnapshot(regulationName, newDescription, jurisdictions);
    }

    private static List<PurposeSnapshot.RegulationLinkSnapshot> toRegulationLinkSnapshots(
            PurposeView view) {
        return view.regulations().stream()
            .map(r -> new PurposeSnapshot.RegulationLinkSnapshot(
                r.regulationName(), r.lawfulBasis(), r.onGranted(), r.onDenied()))
            .toList();
    }

    private static List<PurposeSnapshot.PolicySnapshot> toPolicySnapshots(PurposeView view) {
        return view.policies().stream()
            .map(p -> new PurposeSnapshot.PolicySnapshot(
                p.policyType(),
                fieldNameOrNull(p),
                entityNameOrNull(p),
                categoryNameOrNull(p)))
            .toList();
    }

    private static boolean equalsNullable(String a, String b) {
        return java.util.Objects.equals(a, b);
    }
}
