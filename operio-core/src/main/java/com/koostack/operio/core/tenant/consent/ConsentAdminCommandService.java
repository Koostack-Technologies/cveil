package com.koostack.operio.core.tenant.consent;

import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.spi.Plugin;
import com.koostack.operio.core.tenant.consent.events.FieldSnapshot;
import com.koostack.operio.core.tenant.consent.events.PurposeSnapshot;
import com.koostack.operio.core.tenant.consent.events.RegulationSnapshot;
import java.time.Instant;

public interface ConsentAdminCommandService<T> extends Plugin<T> {

    // -- Field Aggregate ------------------------------------------------------

    void registerFieldCommand(String fieldName, String entityName,
                              String description, String entityDescription);

    void updateFieldCommand(String fieldName, String entityName,
                            String description, FieldSnapshot snapshot);

    void removeFieldCommand(String fieldName, String entityName);

    void assignFieldCategoryCommand(String fieldName, String entityName,
                                    String categoryName, String categoryDescription,
                                    FieldSnapshot snapshot);

    void unassignFieldCategoryCommand(String fieldName, String entityName,
                                      String categoryName, FieldSnapshot snapshot);

    // -- Purpose Aggregate ----------------------------------------------------

    void registerPurposeCommand(String purposeName, String description);

    void updatePurposeCommand(String purposeName, String description,
                              PurposeSnapshot snapshot);

    void removePurposeCommand(String purposeName);

    void linkPurposeToRegulationCommand(String purposeName, String regulationName,
                                        String lawfulBasis, String onGranted,
                                        String onDenied, PurposeSnapshot snapshot);

    void unlinkPurposeFromRegulationCommand(String purposeName, String regulationName,
                                            PurposeSnapshot snapshot);

    void addPurposePolicyCommand(String purposeName, String policyType,
                                 String fieldName, String entityName,
                                 String categoryName, PurposeSnapshot snapshot);

    void removePurposePolicyCommand(String purposeName, String policyType,
                                    String fieldName, String entityName,
                                    String categoryName, PurposeSnapshot snapshot);

    // -- Regulation Aggregate -------------------------------------------------

    void registerRegulationCommand(String regulationName, String description);

    void updateRegulationCommand(String regulationName, String description,
                                 RegulationSnapshot snapshot);

    void removeRegulationCommand(String regulationName);

    void addRegulationJurisdictionCommand(String regulationName,
                                          String jurisdictionName,
                                          String description,
                                          RegulationSnapshot snapshot);

    void removeRegulationJurisdictionCommand(String regulationName,
                                             String jurisdictionName,
                                             RegulationSnapshot snapshot);

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
