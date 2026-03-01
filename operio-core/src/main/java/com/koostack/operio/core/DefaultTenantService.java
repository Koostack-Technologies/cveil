package com.koostack.operio.core;

import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.models.BatchConsentEntry;
import com.koostack.operio.core.models.BatchConsentResult;
import com.koostack.operio.core.models.BatchConsentResult.BatchItemError;
import com.koostack.operio.core.models.CategoryInfo;
import com.koostack.operio.core.models.ConsentAuditEntry;
import com.koostack.operio.core.models.ConsentResponse;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.DiscoveredField;
import com.koostack.operio.core.models.DiscoveryResult;
import com.koostack.operio.core.models.EntityInfo;
import com.koostack.operio.core.models.FieldInfo;
import com.koostack.operio.core.models.PolicySummaryEntry;
import com.koostack.operio.core.models.PurposeInfo;
import com.koostack.operio.core.models.RegulationInfo;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.authz.AuthzProvider;
import com.koostack.operio.core.tenant.consent.ConsentAdminCommandService;
import com.koostack.operio.core.tenant.consent.ConsentAdminQueryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Default {@link TenantService} implementation that composes command and
 * query services into cohesive business operations.
 *
 * <p>Extends {@link Operio} to inherit the builder and plugin-wiring
 * infrastructure. All orchestration logic lives here — enrichment
 * (auto-creating referenced entities via the query service), routing to
 * the appropriate command, and delegating queries.
 *
 * <p>Command services beneath this layer are pure event publishers with
 * light validation only.
 */
public class DefaultTenantService extends Operio implements TenantService {

    private static final Logger LOG = Logger.getLogger(
        DefaultTenantService.class.getName()
    );

    private final ConsentAdminCommandService<?> commandService;
    private final ConsentAdminQueryService<?> queryService;

    public DefaultTenantService(
        Builder builder,
        ConsentAdminCommandService<?> commandService,
        ConsentAdminQueryService<?> queryService
    ) {
        super(builder);
        this.commandService = commandService;
        this.queryService = queryService;
    }

    // -----------------------------------------------------------------------
    // Consent write operations — enrichment + delegation
    // -----------------------------------------------------------------------

    @Override
    public void grantConsent(String subject, ConsentScope scope) {
        grantConsent(subject, scope, null);
    }

    @Override
    public void grantConsent(String subject, ConsentScope scope, Instant expiresAt) {
        ensureReferencedEntitiesExist(scope);
        commandService().grantConsentCommand(subject, scope, expiresAt);
    }

    @Override
    public void revokeConsent(String subject, ConsentScope scope) {
        revokeConsent(subject, scope, null);
    }

    @Override
    public void revokeConsent(String subject, ConsentScope scope, Instant expiresAt) {
        ensureReferencedEntitiesExist(scope);
        commandService().revokeConsentCommand(subject, scope, expiresAt);
    }

    @Override
    public void revokeAllConsent(String subject) {
        commandService().revokeAllConsentCommand(subject);
    }

    @Override
    public BatchConsentResult grantConsentBatch(
        String subject, List<BatchConsentEntry> entries
    ) {
        int succeeded = 0;
        List<BatchItemError> errors = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            BatchConsentEntry entry = entries.get(i);
            if (entry == null) {
                errors.add(new BatchItemError(i, null, "Null entry in batch"));
                continue;
            }
            try {
                grantConsent(subject, entry.scope(), entry.expiresAt());
                succeeded++;
            } catch (Exception e) {
                errors.add(new BatchItemError(i, entry.scope(), e.getMessage()));
            }
        }
        return new BatchConsentResult(succeeded, errors.size(), List.copyOf(errors));
    }

    @Override
    public BatchConsentResult revokeConsentBatch(
        String subject, List<BatchConsentEntry> entries
    ) {
        int succeeded = 0;
        List<BatchItemError> errors = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            BatchConsentEntry entry = entries.get(i);
            if (entry == null) {
                errors.add(new BatchItemError(i, null, "Null entry in batch"));
                continue;
            }
            try {
                revokeConsent(subject, entry.scope(), entry.expiresAt());
                succeeded++;
            } catch (Exception e) {
                errors.add(new BatchItemError(i, entry.scope(), e.getMessage()));
            }
        }
        return new BatchConsentResult(succeeded, errors.size(), List.copyOf(errors));
    }

    // -----------------------------------------------------------------------
    // Schema registration — delegation to command service
    // -----------------------------------------------------------------------

    @Override
    public void registerField(String fieldName, String entityName, String description) {
        commandService().registerFieldCommand(fieldName, entityName, description);
    }

    @Override
    public void registerCategory(String categoryName, String description) {
        commandService().registerCategoryCommand(categoryName, description);
    }

    @Override
    public void linkFieldToCategory(
        String fieldName, String entityName, String categoryName
    ) {
        commandService().linkFieldToCategoryCommand(fieldName, entityName, categoryName);
    }

    @Override
    public void registerPurpose(String purposeName, String description) {
        commandService().registerPurposeCommand(purposeName, description);
    }

    @Override
    public void registerRegulation(
        String regulationName, String fullName, String jurisdiction,
        String description, String url
    ) {
        commandService().registerRegulationCommand(
            regulationName, fullName, jurisdiction, description, url
        );
    }

    @Override
    public void linkPurposeToRegulation(
        String purposeName, String regulationName, String legalBasis
    ) {
        commandService().linkPurposeToRegulationCommand(
            purposeName, regulationName, legalBasis
        );
    }

    // -----------------------------------------------------------------------
    // Schema queries — delegation to query service
    // -----------------------------------------------------------------------

    @Override
    public List<FieldInfo> listFields() {
        return queryService().listFields();
    }

    @Override
    public List<CategoryInfo> listCategories() {
        return queryService().listCategories();
    }

    @Override
    public List<EntityInfo> listEntities() {
        return queryService().listEntities();
    }

    @Override
    public List<PurposeInfo> listPurposes() {
        return queryService().listPurposes();
    }

    @Override
    public List<RegulationInfo> listRegulations() {
        return queryService().listRegulations();
    }

    // -----------------------------------------------------------------------
    // Consent queries — delegation to query service
    // -----------------------------------------------------------------------

    @Override
    public List<PolicySummaryEntry> findPoliciesBySubject(String subjectId) {
        return queryService().findPoliciesBySubject(subjectId);
    }

    @Override
    public int countPoliciesBySubject(String subjectId) {
        return queryService().countPoliciesBySubject(subjectId);
    }

    @Override
    public List<ConsentAuditEntry> getConsentHistory(String subjectId) {
        return queryService().getConsentHistory(subjectId);
    }

    @Override
    public List<ConsentAuditEntry> getConsentHistory(
        String subjectId, ConsentScope scope
    ) {
        return queryService().getConsentHistory(subjectId, scope);
    }

    // -----------------------------------------------------------------------
    // Consent evaluation
    // -----------------------------------------------------------------------

    @Override
    public ConsentResponse evaluateConsent(String subject, Set<ConsentScope> scopes) {
        return consentService().evaluateConsent(subject, scopes);
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

        for (String category : result.categories()) {
            registerCategory(category, null);
        }
        for (DiscoveredField field : result.fields()) {
            registerField(field.fieldName(), field.entityName(), field.description());
        }
        for (DiscoveredField field : result.fields()) {
            if (field.category() != null && !field.category().isBlank()) {
                linkFieldToCategory(
                    field.fieldName(), field.entityName(), field.category()
                );
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // DSAR
    // -----------------------------------------------------------------------

    @Override
    public int deleteAuditLogBySubject(String subject) {
        return commandService().deleteAuditLogBySubjectCommand(subject);
    }

    // -----------------------------------------------------------------------
    // IAM — Role Management (delegated to AuthzProvider via TenantContext)
    // -----------------------------------------------------------------------

    @Override
    public void assignUserRole(String userId, String role) {
        AuthzProvider<?> authz = authzProvider();
        if (authz == null) {
            throw new UnsupportedOperationException("IAM role management not available — no AuthzProvider configured");
        }
        authz.assignRole(TenantContext.require(), userId, role);
    }

    @Override
    public void removeUserRole(String userId, String role) {
        AuthzProvider<?> authz = authzProvider();
        if (authz == null) {
            throw new UnsupportedOperationException("IAM role management not available — no AuthzProvider configured");
        }
        authz.removeRole(TenantContext.require(), userId, role);
    }

    @Override
    public Set<String> listUserRoles(String userId) {
        AuthzProvider<?> authz = authzProvider();
        if (authz == null) {
            throw new UnsupportedOperationException("IAM role management not available — no AuthzProvider configured");
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
    // Enrichment — auto-create referenced entities via query service
    // -----------------------------------------------------------------------

    private void ensureReferencedEntitiesExist(ConsentScope scope) {
        ensurePurposeExists(scope.purposeName());
        if (!isWildcardOrBlank(scope.category())) {
            ensureCategoryExists(scope.category());
        }
        if (!isWildcardOrBlank(scope.fieldName())) {
            ensureEntityAndFieldExist(scope.entityName(), scope.fieldName());
        }
    }

    private static boolean isWildcardOrBlank(String value) {
        return value == null || value.isBlank() || ConsentScope.WILDCARD.equals(value);
    }

    private void ensurePurposeExists(String purposeName) {
        if (isWildcardOrBlank(purposeName)) return;
        boolean exists = queryService().listPurposes().stream()
            .anyMatch(p -> purposeName.equals(p.purposeName()));
        if (!exists) {
            LOG.info(() -> "Auto-creating purpose '" + purposeName
                + "' referenced by consent grant/revoke");
            registerPurpose(purposeName, null);
        }
    }

    private void ensureCategoryExists(String categoryName) {
        if (isWildcardOrBlank(categoryName)) return;
        boolean exists = queryService().listCategories().stream()
            .anyMatch(c -> categoryName.equals(c.categoryName()));
        if (!exists) {
            LOG.info(() -> "Auto-creating category '" + categoryName
                + "' referenced by consent grant/revoke");
            registerCategory(categoryName, null);
        }
    }

    private void ensureEntityAndFieldExist(String entityName, String fieldName) {
        if (isWildcardOrBlank(fieldName)) return;
        if (isWildcardOrBlank(entityName)) return;
        boolean fieldExists = queryService().listFields().stream()
            .anyMatch(f -> fieldName.equals(f.fieldName())
                && entityName.equals(f.entityName()));
        if (!fieldExists) {
            LOG.info(() -> "Auto-creating field '" + fieldName + "' on entity '"
                + entityName + "' referenced by consent grant/revoke");
            registerField(fieldName, entityName, null);
        }
    }
}
