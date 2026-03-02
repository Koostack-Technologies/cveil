package com.koostack.operio.core.tenant.consent;

import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.spi.Plugin;
import java.time.Instant;

public interface ConsentAdminCommandService<T> extends Plugin<T> {

    // -- Field Aggregate ------------------------------------------------------

    void registerFieldCommand(String fieldName, String entityName,
                              String description, String entityDescription);

    void updateFieldCommand(String fieldName, String entityName, String description);

    void removeFieldCommand(String fieldName, String entityName);

    void assignFieldCategoryCommand(String fieldName, String entityName,
                                    String categoryName, String categoryDescription);

    void unassignFieldCategoryCommand(String fieldName, String entityName,
                                      String categoryName);

    // -- Purpose Aggregate ----------------------------------------------------

    void registerPurposeCommand(String purposeName, String description);

    void updatePurposeCommand(String purposeName, String description);

    void removePurposeCommand(String purposeName);

    void linkPurposeToRegulationCommand(String purposeName, String regulationName,
                                        String lawfulBasis, String onGranted,
                                        String onDenied);

    void unlinkPurposeFromRegulationCommand(String purposeName, String regulationName);

    void addPurposePolicyCommand(String purposeName, String policyType,
                                 String fieldName, String entityName,
                                 String categoryName);

    void removePurposePolicyCommand(String purposeName, String policyType,
                                    String fieldName, String entityName,
                                    String categoryName);

    // -- Regulation Aggregate -------------------------------------------------

    void registerRegulationCommand(String regulationName, String description);

    void updateRegulationCommand(String regulationName, String description);

    void removeRegulationCommand(String regulationName);

    void addRegulationJurisdictionCommand(String regulationName,
                                          String jurisdictionName,
                                          String description);

    void removeRegulationJurisdictionCommand(String regulationName,
                                             String jurisdictionName);

    // -- SubjectConsent Aggregate ---------------------------------------------

    void grantConsentCommand(String subjectId, long policyId,
                             String purposeName, String policyType,
                             String fieldName, String entityName,
                             String categoryName, String actor,
                             String ipAddress, String userAgent,
                             Instant expiresAt);

    void revokeConsentCommand(String subjectId, long policyId,
                              String purposeName, String policyType,
                              String fieldName, String entityName,
                              String categoryName, String actor,
                              String ipAddress, String userAgent);

    void expireConsentCommand(String subjectId, long policyId,
                              String purposeName, String policyType,
                              String fieldName, String entityName,
                              String categoryName);

    void revokeAllConsentCommand(String subjectId, String actor,
                                 String ipAddress, String userAgent);

    // -- DSAR Aggregate -------------------------------------------------------

    void deleteSubjectAuditCommand(String subjectId, String deletionRequestId,
                                   String actor, String ipAddress,
                                   String userAgent);
}
