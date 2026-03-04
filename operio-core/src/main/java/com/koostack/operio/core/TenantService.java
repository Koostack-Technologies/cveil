package com.koostack.operio.core;

import com.koostack.operio.core.models.*;
import com.koostack.operio.core.models.ConsentGraphModels.ConsentGraph;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Tenant-scoped application service that composes command and query services
 * into cohesive business operations.
 *
 * <p>This is the primary interface consumed by REST resources and other
 * entry points. It owns all orchestration logic — validation, enrichment
 * (resolving ConsentScope to policy_id), and routing to the appropriate
 * command or query service.
 *
 * <p>All methods require an active {@link TenantContext}.
 */
public interface TenantService extends OperioService {

    // -----------------------------------------------------------------------
    // Consent write operations
    // -----------------------------------------------------------------------

    void grantConsent(String subject, ConsentScope scope, Instant expiresAt);

    void revokeConsent(String subject, ConsentScope scope);

    void revokeAllConsent(String subject);

    BatchConsentResult grantAllConsent(
        String subject, List<BatchConsentEntry> entries);

    BatchConsentResult revokeAllConsent(
        String subject, List<BatchConsentEntry> entries);

    // -----------------------------------------------------------------------
    // Entity Aggregate operations
    // -----------------------------------------------------------------------

    void registerEntity(String entityName, String description);

    void updateEntity(String entityName, String description);

    void removeEntity(String entityName);

    // -----------------------------------------------------------------------
    // Category Aggregate operations
    // -----------------------------------------------------------------------

    void registerCategory(String categoryName, String description);

    void updateCategory(String categoryName, String description);

    void removeCategory(String categoryName);

    // -----------------------------------------------------------------------
    // Field Aggregate operations
    // -----------------------------------------------------------------------

    void registerField(String fieldName, String entityName,
                       String description, String entityDescription);

    void updateField(String fieldName, String entityName, String description);

    void removeField(String fieldName, String entityName);

    void assignFieldCategory(String fieldName, String entityName,
                             String categoryName, String categoryDescription);

    void unassignFieldCategory(String fieldName, String entityName,
                               String categoryName);

    // -----------------------------------------------------------------------
    // Purpose Aggregate operations
    // -----------------------------------------------------------------------

    void registerPurpose(String purposeName, String description);

    void updatePurpose(String purposeName, String description);

    void removePurpose(String purposeName);

    void linkPurposeToRegulation(String purposeName, String regulationName,
                                 String lawfulBasis, String onGranted,
                                 String onDenied);

    void unlinkPurposeFromRegulation(String purposeName, String regulationName);

    void addPurposePolicy(String purposeName, String policyType,
                          String fieldName, String entityName,
                          String categoryName);

    void removePurposePolicy(String purposeName, String policyType,
                             String fieldName, String entityName,
                             String categoryName);

    // -----------------------------------------------------------------------
    // Jurisdiction Aggregate operations
    // -----------------------------------------------------------------------

    void registerJurisdiction(String jurisdictionName, String description);

    void updateJurisdiction(String jurisdictionName, String description);

    void removeJurisdiction(String jurisdictionName);

    // -----------------------------------------------------------------------
    // Regulation Aggregate operations
    // -----------------------------------------------------------------------

    void registerRegulation(String regulationName, String description);

    void updateRegulation(String regulationName, String description);

    void removeRegulation(String regulationName);

    void addRegulationJurisdiction(String regulationName,
                                   String jurisdictionName,
                                   String description);

    void removeRegulationJurisdiction(String regulationName,
                                      String jurisdictionName);

    // -----------------------------------------------------------------------
    // Aggregate queries
    // -----------------------------------------------------------------------

    List<EntityView> listEntities();

    Optional<EntityView> getEntity(String entityName);

    List<CategoryView> listCategories();

    Optional<CategoryView> getCategory(String categoryName);

    List<FieldView> listFields();

    FieldView getField(String fieldName, String entityName);

    List<PurposeView> listPurposes();

    PurposeView getPurpose(String purposeName);

    List<RegulationView> listRegulations();

    RegulationView getRegulation(String regulationName);

    List<JurisdictionView> listJurisdictions();

    Optional<JurisdictionView> getJurisdiction(String jurisdictionName);

    SubjectConsentView getSubjectConsent(String subjectId);

    int countSubjectConsentEntries(String subjectId);

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

    void deleteSubjectAudit(String subject, String deletionRequestId);

    /**
     * Returns the number of cached consent-decision keys for the given subject.
     * Implementations backed by a cache (e.g. Redis) should override this;
     * the default returns {@code 0} (no cache).
     */
    default long countCachedKeysForSubject(String subject) {
        return 0;
    }

    /**
     * Deletes all cached consent-decision keys for the given subject.
     * Implementations backed by a cache (e.g. Redis) should override this;
     * the default is a no-op returning {@code 0}.
     */
    default long deleteCachedKeysForSubject(String subject) {
        return 0;
    }

    // -----------------------------------------------------------------------
    // Consent graph
    // -----------------------------------------------------------------------

    /**
     * Returns a paginated view of the consent graph. Supports full-graph
     * pagination and BFS subgraph queries. Requires a graph-capable backend
     * (Redis graph projection).
     *
     * @param cursor   opaque cursor for pagination, or {@code null} for first page
     * @param limit    max nodes per page
     * @param rootType optional root node type for subgraph BFS
     * @param rootName optional root node name for subgraph BFS
     * @param depth    optional BFS depth limit (ignored if no root specified)
     * @return the consent graph page
     * @throws UnsupportedOperationException if the backend does not support graph queries
     */
    default ConsentGraph getConsentGraph(String cursor, int limit,
                                         String rootType, String rootName,
                                         Integer depth) {
        throw new UnsupportedOperationException("Consent graph not available");
    }

    // -----------------------------------------------------------------------
    // IAM — Role Management
    // -----------------------------------------------------------------------

    default void assignUserRole(String userId, String role) {
        throw new UnsupportedOperationException("IAM role management not available");
    }

    default void removeUserRole(String userId, String role) {
        throw new UnsupportedOperationException("IAM role management not available");
    }

    default Set<String> listUserRoles(String userId) {
        throw new UnsupportedOperationException("IAM role management not available");
    }
}
