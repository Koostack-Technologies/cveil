package com.koostack.operio.core;

import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.models.*;
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
    // Field Aggregate operations
    // -----------------------------------------------------------------------

    @Override
    public void registerField(String fieldName, String entityName,
                              String description, String entityDescription) {
        commandService().registerFieldCommand(fieldName, entityName, description, entityDescription);
    }

    @Override
    public void updateField(String fieldName, String entityName, String description) {
        commandService().updateFieldCommand(fieldName, entityName, description);
    }

    @Override
    public void removeField(String fieldName, String entityName) {
        commandService().removeFieldCommand(fieldName, entityName);
    }

    @Override
    public void assignFieldCategory(String fieldName, String entityName,
                                    String categoryName, String categoryDescription) {
        commandService().assignFieldCategoryCommand(
            fieldName, entityName, categoryName, categoryDescription
        );
    }

    @Override
    public void unassignFieldCategory(String fieldName, String entityName,
                                      String categoryName) {
        commandService().unassignFieldCategoryCommand(fieldName, entityName, categoryName);
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
        commandService().updatePurposeCommand(purposeName, description);
    }

    @Override
    public void removePurpose(String purposeName) {
        commandService().removePurposeCommand(purposeName);
    }

    @Override
    public void linkPurposeToRegulation(String purposeName, String regulationName,
                                        String lawfulBasis, String onGranted,
                                        String onDenied) {
        commandService().linkPurposeToRegulationCommand(
            purposeName, regulationName, lawfulBasis, onGranted, onDenied
        );
    }

    @Override
    public void unlinkPurposeFromRegulation(String purposeName, String regulationName) {
        commandService().unlinkPurposeFromRegulationCommand(purposeName, regulationName);
    }

    @Override
    public void addPurposePolicy(String purposeName, String policyType,
                                 String fieldName, String entityName,
                                 String categoryName) {
        commandService().addPurposePolicyCommand(
            purposeName, policyType, fieldName, entityName, categoryName
        );
    }

    @Override
    public void removePurposePolicy(String purposeName, String policyType,
                                    String fieldName, String entityName,
                                    String categoryName) {
        commandService().removePurposePolicyCommand(
            purposeName, policyType, fieldName, entityName, categoryName
        );
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
        commandService().updateRegulationCommand(regulationName, description);
    }

    @Override
    public void removeRegulation(String regulationName) {
        commandService().removeRegulationCommand(regulationName);
    }

    @Override
    public void addRegulationJurisdiction(String regulationName,
                                          String jurisdictionName,
                                          String description) {
        commandService().addRegulationJurisdictionCommand(
            regulationName, jurisdictionName, description
        );
    }

    @Override
    public void removeRegulationJurisdiction(String regulationName,
                                             String jurisdictionName) {
        commandService().removeRegulationJurisdictionCommand(regulationName, jurisdictionName);
    }

    // -----------------------------------------------------------------------
    // Aggregate queries
    // -----------------------------------------------------------------------

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
}
