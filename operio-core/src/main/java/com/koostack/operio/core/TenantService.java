package com.koostack.operio.core;

import com.koostack.operio.core.models.*;
import java.time.Instant;
import java.util.List;
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

    List<FieldView> listFields();

    FieldView getField(String fieldName, String entityName);

    List<PurposeView> listPurposes();

    PurposeView getPurpose(String purposeName);

    List<RegulationView> listRegulations();

    RegulationView getRegulation(String regulationName);

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
