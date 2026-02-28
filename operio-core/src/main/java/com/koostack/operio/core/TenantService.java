package com.koostack.operio.core;

import com.koostack.operio.core.models.BatchConsentEntry;
import com.koostack.operio.core.models.BatchConsentResult;
import com.koostack.operio.core.models.CategoryInfo;
import com.koostack.operio.core.models.ConsentAuditEntry;
import com.koostack.operio.core.models.ConsentResponse;
import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.models.DiscoveryResult;
import com.koostack.operio.core.models.EntityInfo;
import com.koostack.operio.core.models.FieldInfo;
import com.koostack.operio.core.models.PolicySummaryEntry;
import com.koostack.operio.core.models.PurposeInfo;
import com.koostack.operio.core.models.RegulationInfo;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Tenant-scoped application service that composes command and query services
 * into cohesive business operations.
 *
 * <p>This is the primary interface consumed by REST resources and other
 * entry points. It owns all orchestration logic — enrichment, auto-creation
 * of referenced entities, and routing to the appropriate command or query
 * service. Command services beneath this layer are pure event publishers
 * with light validation only.
 *
 * <p>All methods require an active {@link com.koostack.operio.core.models.TenantContext}.
 */
public interface TenantService extends OperioService {

    // -----------------------------------------------------------------------
    // Consent write operations
    // -----------------------------------------------------------------------

    void grantConsent(String subject, ConsentScope scope);

    void grantConsent(String subject, ConsentScope scope, Instant expiresAt);

    void revokeConsent(String subject, ConsentScope scope);

    void revokeConsent(String subject, ConsentScope scope, Instant expiresAt);

    void revokeAllConsent(String subject);

    BatchConsentResult grantConsentBatch(
        String subject, List<BatchConsentEntry> entries);

    BatchConsentResult revokeConsentBatch(
        String subject, List<BatchConsentEntry> entries);

    // -----------------------------------------------------------------------
    // Schema registration
    // -----------------------------------------------------------------------

    void registerField(String fieldName, String entityName, String description);

    void registerCategory(String categoryName, String description);

    void linkFieldToCategory(String fieldName, String entityName, String categoryName);

    void registerPurpose(String purposeName, String description);

    void registerRegulation(
        String regulationName, String fullName, String jurisdiction,
        String description, String url);

    void linkPurposeToRegulation(
        String purposeName, String regulationName, String legalBasis);

    // -----------------------------------------------------------------------
    // Schema queries
    // -----------------------------------------------------------------------

    List<FieldInfo> listFields();

    List<CategoryInfo> listCategories();

    List<EntityInfo> listEntities();

    List<PurposeInfo> listPurposes();

    List<RegulationInfo> listRegulations();

    // -----------------------------------------------------------------------
    // Consent queries
    // -----------------------------------------------------------------------

    List<PolicySummaryEntry> findPoliciesBySubject(String subjectId);

    int countPoliciesBySubject(String subjectId);

    List<ConsentAuditEntry> getConsentHistory(String subjectId);

    List<ConsentAuditEntry> getConsentHistory(String subjectId, ConsentScope scope);

    // -----------------------------------------------------------------------
    // Consent evaluation
    // -----------------------------------------------------------------------

    ConsentResponse evaluateConsent(String subject, Set<ConsentScope> scopes);

    // -----------------------------------------------------------------------
    // Read path — consent evaluation + data masking
    // -----------------------------------------------------------------------

    Object access(Object rawData);

    // -----------------------------------------------------------------------
    // Discovery pipeline
    // -----------------------------------------------------------------------

    DiscoveryResult syncDiscoveredFields();

    // -----------------------------------------------------------------------
    // DSAR
    // -----------------------------------------------------------------------

    int deleteAuditLogBySubject(String subject);

    // -----------------------------------------------------------------------
    // IAM — Role Management (delegated to the tenant's IdP)
    // -----------------------------------------------------------------------

    /**
     * Assign a role to a user in the current tenant's identity provider.
     *
     * @param userId the user's IdP identifier
     * @param role   the role name to assign
     */
    default void assignUserRole(String userId, String role) {
        throw new UnsupportedOperationException("IAM role management not available");
    }

    /**
     * Remove a role from a user in the current tenant's identity provider.
     *
     * @param userId the user's IdP identifier
     * @param role   the role name to remove
     */
    default void removeUserRole(String userId, String role) {
        throw new UnsupportedOperationException("IAM role management not available");
    }

    /**
     * List all roles assigned to a user in the current tenant's identity provider.
     *
     * @param userId the user's IdP identifier
     * @return the user's roles, possibly empty
     */
    default Set<String> listUserRoles(String userId) {
        throw new UnsupportedOperationException("IAM role management not available");
    }
}
