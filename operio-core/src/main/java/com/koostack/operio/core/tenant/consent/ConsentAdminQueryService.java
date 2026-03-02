package com.koostack.operio.core.tenant.consent;

import com.koostack.operio.core.models.*;
import com.koostack.operio.core.spi.Plugin;
import java.time.Instant;
import java.util.List;

public interface ConsentAdminQueryService<T> extends Plugin<T> {

    // -- Field Aggregate Queries ----------------------------------------------

    List<FieldView> listFields();

    FieldView getField(String fieldName, String entityName);

    // -- Purpose Aggregate Queries --------------------------------------------

    List<PurposeView> listPurposes();

    PurposeView getPurpose(String purposeName);

    // -- Regulation Aggregate Queries -----------------------------------------

    List<RegulationView> listRegulations();

    RegulationView getRegulation(String regulationName);

    // -- SubjectConsent Queries -----------------------------------------------

    SubjectConsentView getSubjectConsent(String subjectId);

    int countSubjectConsentEntries(String subjectId);

    // -- Expiration Queries ---------------------------------------------------

    default List<SubjectConsentView.ConsentLedgerEntryView> findExpiredGrants(Instant cutoff) {
        return List.of();
    }

    // -- Policy Lookup (for TenantService enrichment) -------------------------

    PurposeView.PolicyView findPolicy(String purposeName, String policyType,
                                      String fieldName, String entityName,
                                      String categoryName);
}
