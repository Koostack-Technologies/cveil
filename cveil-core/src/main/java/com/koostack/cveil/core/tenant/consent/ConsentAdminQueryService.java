package com.koostack.cveil.core.tenant.consent;

import com.koostack.cveil.core.models.*;
import com.koostack.cveil.core.spi.Plugin;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ConsentAdminQueryService<T> extends Plugin<T> {

    // -- Entity Queries -------------------------------------------------------

    List<EntityView> listEntities();

    Optional<EntityView> getEntity(String entityName);

    // -- Category Queries -----------------------------------------------------

    List<CategoryView> listCategories();

    Optional<CategoryView> getCategory(String categoryName);

    // -- Field Aggregate Queries ----------------------------------------------

    List<FieldView> listFields();

    FieldView getField(String fieldName, String entityName);

    // -- Purpose Aggregate Queries --------------------------------------------

    List<PurposeView> listPurposes();

    PurposeView getPurpose(String purposeName);

    // -- Regulation Aggregate Queries -----------------------------------------

    List<RegulationView> listRegulations();

    RegulationView getRegulation(String regulationName);

    // -- Jurisdiction Queries -------------------------------------------------

    List<JurisdictionView> listJurisdictions();

    Optional<JurisdictionView> getJurisdiction(String jurisdictionName);

    // -- Subject Queries ------------------------------------------------------

    List<SubjectView> listSubjects();

    Optional<SubjectView> getSubject(String subjectName);

    // -- SubjectConsent Queries -----------------------------------------------

    SubjectConsentView getSubjectConsent(String subjectId);

    int countSubjectConsentEntries(String subjectId);

    // -- Expiration Queries ---------------------------------------------------

    default List<SubjectConsentView.ConsentLedgerEntryView> findExpiredGrants(Instant cutoff) {
        return List.of();
    }

    // -- Policy Lookup (for TenantService enrichment) -------------------------

    PurposeView.PolicyView findPolicy(
            String purposeName,
            String policyType,
            String fieldName,
            String entityName,
            String categoryName);
}
