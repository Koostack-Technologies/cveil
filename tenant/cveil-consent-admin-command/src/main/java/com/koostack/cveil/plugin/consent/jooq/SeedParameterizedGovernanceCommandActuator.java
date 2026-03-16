package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.TenantContext;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seeds a <b>parameterized</b> consent governance graph for a tenant based on the user's onboarding
 * selections.
 *
 * <p>Unlike {@link SeedConsentGovernanceCommandActuator} which seeds <i>all</i> US governance data,
 * this actuator seeds only:
 *
 * <ul>
 *   <li>The <b>selected jurisdictions</b> (tier-enforced)
 *   <li>Regulations <b>derived</b> from those jurisdictions
 *   <li>The <b>specified purposes</b> (from the industry preset)
 *   <li>Purpose–regulation links for derived regulations × specified purposes
 *   <li>The <b>specified entities</b> (data subjects)
 * </ul>
 *
 * <p>Reference data (categories, fields, field–category assignments, purpose–category policies) is
 * always seeded in full — these are universal and not tier-gated.
 *
 * <p>This actuator delegates to the same consent {@link CommandDispatcher} as the original seeder,
 * so events are published and projections updated automatically.
 */
public class SeedParameterizedGovernanceCommandActuator
        implements CommandActuator<
                SeedParameterizedGovernanceCommandActuator.SeedParameterizedGovernanceCommand,
                Void> {

    private static final Logger LOG =
            LoggerFactory.getLogger(SeedParameterizedGovernanceCommandActuator.class);

    /**
     * Command input for parameterized governance seeding.
     *
     * @param tenantId the tenant to seed data for
     * @param jurisdictionIds selected jurisdiction IDs (e.g., "CALIFORNIA", "US_FEDERAL")
     * @param regulationIds derived regulation IDs (e.g., "CCPA", "HIPAA")
     * @param purposeIds purpose IDs to seed (from industry preset)
     * @param entityNames entity names to seed (e.g., "customer", "patient")
     */
    public record SeedParameterizedGovernanceCommand(
            String tenantId,
            Set<String> jurisdictionIds,
            Set<String> regulationIds,
            Set<String> purposeIds,
            Set<String> entityNames) {}

    private final EventService<?> eventService;
    private final CommandDispatcher consentDispatcher;

    public SeedParameterizedGovernanceCommandActuator(
            EventService<?> eventService, CommandDispatcher consentDispatcher) {
        this.eventService = eventService;
        this.consentDispatcher = consentDispatcher;
    }

    @Override
    public Void execute(SeedParameterizedGovernanceCommand command) {
        Objects.requireNonNull(command.tenantId(), "tenantId must not be null");

        String tenantId = command.tenantId();
        Set<String> jurisdictions =
                command.jurisdictionIds() != null ? command.jurisdictionIds() : Set.of();
        Set<String> regulations =
                command.regulationIds() != null ? command.regulationIds() : Set.of();
        Set<String> purposes = command.purposeIds() != null ? command.purposeIds() : Set.of();
        Set<String> entities = command.entityNames() != null ? command.entityNames() : Set.of();

        LOG.info(
                "Seeding parameterized governance for tenant {}: {} jurisdictions, {} regulations,"
                        + " {} purposes, {} entities",
                tenantId,
                jurisdictions.size(),
                regulations.size(),
                purposes.size(),
                entities.size());

        TenantContext.runWhere(
                tenantId,
                () -> {
                    LOG.info("Seeding reference entities…");
                    seedAllCategories();
                    seedRegulations(regulations);
                    seedJurisdictions(jurisdictions);
                    seedPurposes(purposes);
                    seedEntities(entities);

                    LOG.info("Seeding links and fields…");
                    seedRegulationJurisdictionLinks(jurisdictions, regulations);
                    seedPurposeRegulationLinks(purposes, regulations);
                    seedAllFields();

                    LOG.info("Seeding assignments and policies…");
                    seedAllFieldCategoryAssignments();
                    seedAllPurposeCategoryPolicies();
                });

        LOG.info("Parameterized governance seed completed for tenant: {}", tenantId);
        return null;
    }

    // -----------------------------------------------------------------------
    // Phase 1: Standalone entities
    // -----------------------------------------------------------------------

    /** Seed ALL categories — universal reference data. */
    private void seedAllCategories() {
        LOG.debug("Seeding all categories...");
        List<String[]> categories =
                List.of(
                        new String[] {
                            "PII",
                            "Personally Identifiable Information — name, email, phone, address,"
                                    + " date of birth"
                        },
                        new String[] {
                            "SENSITIVE_PII",
                            "Government-issued identifiers — SSN, driver's license number, passport"
                                    + " number, tax ID"
                        },
                        new String[] {
                            "HEALTH",
                            "Protected Health Information — diagnosis, treatment records,"
                                    + " prescriptions, medical conditions"
                        },
                        new String[] {
                            "FINANCIAL",
                            "Financial account data — bank accounts, credit/debit cards, income,"
                                    + " loan data, transactions"
                        },
                        new String[] {
                            "BIOMETRIC",
                            "Biometric identifiers — fingerprints, facial geometry, iris scans,"
                                    + " voiceprints"
                        },
                        new String[] {
                            "GENETIC",
                            "Genetic data — DNA sequences, genetic test results, ancestry data,"
                                    + " hereditary markers"
                        },
                        new String[] {
                            "GEOLOCATION",
                            "Precise geolocation — GPS coordinates, real-time location within 1,750"
                                    + " feet"
                        },
                        new String[] {
                            "CHILDREN",
                            "Children's personal data — data from individuals under 13 (COPPA) or"
                                    + " under 16 (state laws)"
                        },
                        new String[] {
                            "EDUCATION",
                            "Education records — grades, transcripts, student IDs, enrollment data,"
                                    + " disciplinary records"
                        },
                        new String[] {"RACIAL_ETHNIC", "Racial or ethnic origin data"},
                        new String[] {"RELIGIOUS", "Religious or philosophical beliefs"},
                        new String[] {"SEXUAL_ORIENTATION", "Sexual orientation or sex life data"},
                        new String[] {"POLITICAL", "Political opinions or affiliations"},
                        new String[] {
                            "COMMUNICATIONS",
                            "Electronic communications content — emails, messages, call recordings"
                        },
                        new String[] {
                            "BEHAVIORAL",
                            "Online activity — browsing history, search history, interaction"
                                    + " tracking, ad engagement"
                        },
                        new String[] {
                            "PROFESSIONAL",
                            "Employment and professional data — job title, employer, work history,"
                                    + " salary"
                        },
                        new String[] {
                            "CREDIT",
                            "Consumer credit information — credit scores, credit reports, payment"
                                    + " history"
                        });

        for (String[] cat : categories) {
            dispatchSafe(
                    () ->
                            consentDispatcher.dispatch(
                                    new RegisterCategoryCommandActuator.RegisterCategoryCommand(
                                            cat[0], cat[1])),
                    "category",
                    cat[0]);
        }
    }

    /** Seed only the derived regulations. */
    private void seedRegulations(Set<String> regulationIds) {
        LOG.debug("Seeding {} regulations...", regulationIds.size());
        Map<String, String> allRegulations = regulationCatalog();
        for (String regId : regulationIds) {
            String desc = allRegulations.get(regId);
            if (desc != null) {
                dispatchSafe(
                        () ->
                                consentDispatcher.dispatch(
                                        new RegisterRegulationCommandActuator
                                                .RegisterRegulationCommand(regId, desc)),
                        "regulation",
                        regId);
            }
        }
    }

    /** Seed only the selected jurisdictions. */
    private void seedJurisdictions(Set<String> jurisdictionIds) {
        LOG.debug("Seeding {} jurisdictions...", jurisdictionIds.size());
        Map<String, String> allJurisdictions = jurisdictionCatalog();
        for (String jurId : jurisdictionIds) {
            String desc = allJurisdictions.get(jurId);
            if (desc != null) {
                dispatchSafe(
                        () ->
                                consentDispatcher.dispatch(
                                        new RegisterJurisdictionCommandActuator
                                                .RegisterJurisdictionCommand(jurId, desc)),
                        "jurisdiction",
                        jurId);
            }
        }
    }

    /** Seed only the preset's purposes. */
    private void seedPurposes(Set<String> purposeIds) {
        LOG.debug("Seeding {} purposes...", purposeIds.size());
        Map<String, String> allPurposes = purposeCatalog();
        for (String purId : purposeIds) {
            String desc = allPurposes.get(purId);
            if (desc != null) {
                dispatchSafe(
                        () ->
                                consentDispatcher.dispatch(
                                        new RegisterPurposeCommandActuator.RegisterPurposeCommand(
                                                purId, desc)),
                        "purpose",
                        purId);
            }
        }
    }

    /** Seed only the preset's entities. */
    private void seedEntities(Set<String> entityNames) {
        LOG.debug("Seeding {} entities...", entityNames.size());
        Map<String, String> allEntities = entityCatalog();
        for (String name : entityNames) {
            String desc = allEntities.get(name);
            if (desc != null) {
                dispatchSafe(
                        () ->
                                consentDispatcher.dispatch(
                                        new RegisterEntityCommandActuator.RegisterEntityCommand(
                                                name, desc)),
                        "entity",
                        name);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Phase 2: First-order links
    // -----------------------------------------------------------------------

    /** Link only selected jurisdictions to their derived regulations. */
    private void seedRegulationJurisdictionLinks(
            Set<String> jurisdictions, Set<String> regulations) {
        LOG.debug("Seeding regulation-jurisdiction links...");

        // Federal regulations → US_FEDERAL (if US_FEDERAL is selected)
        if (jurisdictions.contains("US_FEDERAL")) {
            for (String fedReg :
                    List.of(
                            "HIPAA",
                            "COPPA",
                            "FERPA",
                            "GLBA",
                            "FCRA",
                            "TCPA",
                            "CAN_SPAM",
                            "ECPA")) {
                if (regulations.contains(fedReg)) {
                    linkRegJuris(fedReg, "US_FEDERAL");
                }
            }
        }

        // State regulations → state jurisdictions
        String[][] stateLinks = {
            {"CCPA", "CALIFORNIA"}, {"VCDPA", "VIRGINIA"}, {"CPA", "COLORADO"},
            {"CTDPA", "CONNECTICUT"}, {"UCPA", "UTAH"}, {"TDPSA", "TEXAS"},
            {"FDBR", "FLORIDA"}, {"ICDPA", "INDIANA"}, {"IowaCDPA", "IOWA"},
            {"TIPA", "TENNESSEE"}, {"MCDPA", "MONTANA"}, {"OCPA", "OREGON"},
            {"DPDPA", "DELAWARE"}, {"NJDPA", "NEW_JERSEY"}, {"NHPA", "NEW_HAMPSHIRE"},
            {"KCDPA", "KENTUCKY"}, {"NDPA", "NEBRASKA"}, {"MODPA", "MARYLAND"},
            {"MNCDPA", "MINNESOTA"},
        };
        for (String[] link : stateLinks) {
            if (jurisdictions.contains(link[1]) && regulations.contains(link[0])) {
                linkRegJuris(link[0], link[1]);
            }
        }
    }

    /** Seed purpose-regulation links only for derived regulations × specified purposes. */
    private void seedPurposeRegulationLinks(Set<String> purposes, Set<String> regulations) {
        LOG.debug("Seeding purpose-regulation links...");

        // Federal regulations
        if (regulations.contains("HIPAA")) {
            linkIfPresent(
                    purposes,
                    "HEALTHCARE_OPS",
                    "HIPAA",
                    "Treatment/Payment/Operations",
                    "PLAINTEXT",
                    "REDACT_ALL");
            linkIfPresent(
                    purposes, "RESEARCH", "HIPAA", "Authorization", "PLAINTEXT", "REDACT_ALL");
            linkIfPresent(
                    purposes, "MARKETING", "HIPAA", "Authorization", "PLAINTEXT", "REDACT_ALL");
            linkIfPresent(
                    purposes, "ANALYTICS", "HIPAA", "De-identification", "PLAINTEXT", "HASH256");
            linkIfPresent(
                    purposes,
                    "THIRD_PARTY_SHARING",
                    "HIPAA",
                    "Authorization",
                    "PLAINTEXT",
                    "REDACT_ALL");
            linkIfPresent(
                    purposes,
                    "LEGAL_COMPLIANCE",
                    "HIPAA",
                    "Legal Obligation",
                    "PLAINTEXT",
                    "PLAINTEXT");
        }

        if (regulations.contains("COPPA")) {
            for (String purpose :
                    List.of(
                            "SERVICE_DELIVERY",
                            "MARKETING",
                            "ANALYTICS",
                            "TARGETED_ADVERTISING",
                            "THIRD_PARTY_SHARING",
                            "PERSONALIZATION")) {
                linkIfPresent(
                        purposes,
                        purpose,
                        "COPPA",
                        "Verifiable Parental Consent",
                        "PLAINTEXT",
                        "REDACT_ALL");
            }
        }

        if (regulations.contains("FERPA")) {
            linkIfPresent(
                    purposes,
                    "EDUCATION_OPS",
                    "FERPA",
                    "School Official Exception",
                    "PLAINTEXT",
                    "REDACT_ALL");
            linkIfPresent(
                    purposes, "RESEARCH", "FERPA", "Written Consent", "PLAINTEXT", "REDACT_ALL");
            linkIfPresent(
                    purposes,
                    "THIRD_PARTY_SHARING",
                    "FERPA",
                    "Written Consent",
                    "PLAINTEXT",
                    "REDACT_ALL");
            linkIfPresent(
                    purposes,
                    "LEGAL_COMPLIANCE",
                    "FERPA",
                    "Legal Obligation",
                    "PLAINTEXT",
                    "PLAINTEXT");
        }

        if (regulations.contains("GLBA")) {
            linkIfPresent(
                    purposes,
                    "FINANCIAL_SERVICES",
                    "GLBA",
                    "Contractual Necessity",
                    "PLAINTEXT",
                    "REDACT_UP_TO_LAST_4");
            linkIfPresent(
                    purposes, "THIRD_PARTY_SHARING", "GLBA", "Opt-Out", "PLAINTEXT", "REDACT_ALL");
            linkIfPresent(purposes, "MARKETING", "GLBA", "Opt-Out", "PLAINTEXT", "REDACT_ALL");
            linkIfPresent(
                    purposes,
                    "FRAUD_PREVENTION",
                    "GLBA",
                    "Legitimate Interest",
                    "PLAINTEXT",
                    "PLAINTEXT");
            linkIfPresent(
                    purposes,
                    "LEGAL_COMPLIANCE",
                    "GLBA",
                    "Legal Obligation",
                    "PLAINTEXT",
                    "PLAINTEXT");
        }

        if (regulations.contains("FCRA")) {
            linkIfPresent(
                    purposes, "EMPLOYMENT", "FCRA", "Written Consent", "PLAINTEXT", "REDACT_ALL");
            linkIfPresent(
                    purposes,
                    "FINANCIAL_SERVICES",
                    "FCRA",
                    "Permissible Purpose",
                    "PLAINTEXT",
                    "REDACT_ALL");
            linkIfPresent(
                    purposes,
                    "FRAUD_PREVENTION",
                    "FCRA",
                    "Legitimate Interest",
                    "PLAINTEXT",
                    "HASH256");
        }

        if (regulations.contains("TCPA")) {
            linkIfPresent(
                    purposes,
                    "MARKETING",
                    "TCPA",
                    "Prior Express Written Consent",
                    "PLAINTEXT",
                    "REDACT_ALL");
            linkIfPresent(
                    purposes,
                    "CUSTOMER_SUPPORT",
                    "TCPA",
                    "Established Business Relationship",
                    "PLAINTEXT",
                    "PLAINTEXT");
        }

        if (regulations.contains("CAN_SPAM")) {
            linkIfPresent(
                    purposes, "MARKETING", "CAN_SPAM", "Opt-Out", "PLAINTEXT", "REDACT_LOCAL");
            linkIfPresent(
                    purposes,
                    "SERVICE_DELIVERY",
                    "CAN_SPAM",
                    "Transactional Exception",
                    "PLAINTEXT",
                    "PLAINTEXT");
        }

        if (regulations.contains("ECPA")) {
            linkIfPresent(
                    purposes, "CUSTOMER_SUPPORT", "ECPA", "Consent", "PLAINTEXT", "REDACT_ALL");
            linkIfPresent(
                    purposes,
                    "LEGAL_COMPLIANCE",
                    "ECPA",
                    "Legal Obligation",
                    "PLAINTEXT",
                    "PLAINTEXT");
            linkIfPresent(
                    purposes,
                    "FRAUD_PREVENTION",
                    "ECPA",
                    "Provider Exception",
                    "PLAINTEXT",
                    "HASH256");
        }

        // State comprehensive privacy laws
        if (regulations.contains("CCPA")) {
            seedStandardStatePurposeLinks("CCPA", purposes);
            linkIfPresent(purposes, "ANALYTICS", "CCPA", "Consent", "PLAINTEXT", "REDACT_ALL");
            linkIfPresent(purposes, "EMPLOYMENT", "CCPA", "Consent", "PLAINTEXT", "REDACT_ALL");
        }

        for (String reg :
                List.of(
                        "VCDPA", "CPA", "CTDPA", "UCPA", "TDPSA", "FDBR", "ICDPA", "TIPA", "MCDPA",
                        "OCPA", "DPDPA", "NJDPA", "NHPA", "KCDPA", "NDPA", "MNCDPA")) {
            if (regulations.contains(reg)) {
                seedStandardStatePurposeLinks(reg, purposes);
            }
        }

        if (regulations.contains("IowaCDPA")) {
            linkIfPresent(
                    purposes,
                    "SERVICE_DELIVERY",
                    "IowaCDPA",
                    "Contractual Necessity",
                    "PLAINTEXT",
                    "PLAINTEXT");
            linkIfPresent(
                    purposes,
                    "MARKETING",
                    "IowaCDPA",
                    "Legitimate Interest",
                    "PLAINTEXT",
                    "HASH256");
            linkIfPresent(
                    purposes,
                    "ANALYTICS",
                    "IowaCDPA",
                    "Legitimate Interest",
                    "PLAINTEXT",
                    "HASH256");
            linkIfPresent(
                    purposes,
                    "TARGETED_ADVERTISING",
                    "IowaCDPA",
                    "Consent",
                    "PLAINTEXT",
                    "REDACT_ALL");
            linkIfPresent(
                    purposes,
                    "FRAUD_PREVENTION",
                    "IowaCDPA",
                    "Legitimate Interest",
                    "PLAINTEXT",
                    "PLAINTEXT");
            linkIfPresent(
                    purposes,
                    "CUSTOMER_SUPPORT",
                    "IowaCDPA",
                    "Contractual Necessity",
                    "PLAINTEXT",
                    "PLAINTEXT");
            linkIfPresent(
                    purposes,
                    "THIRD_PARTY_SHARING",
                    "IowaCDPA",
                    "Consent",
                    "PLAINTEXT",
                    "REDACT_ALL");
            linkIfPresent(purposes, "DATA_SALE", "IowaCDPA", "Consent", "PLAINTEXT", "REDACT_ALL");
            linkIfPresent(
                    purposes,
                    "LEGAL_COMPLIANCE",
                    "IowaCDPA",
                    "Legal Obligation",
                    "PLAINTEXT",
                    "PLAINTEXT");
        }

        if (regulations.contains("MODPA")) {
            linkIfPresent(
                    purposes,
                    "SERVICE_DELIVERY",
                    "MODPA",
                    "Contractual Necessity",
                    "PLAINTEXT",
                    "PLAINTEXT");
            linkIfPresent(purposes, "MARKETING", "MODPA", "Consent", "PLAINTEXT", "REDACT_ALL");
            linkIfPresent(purposes, "ANALYTICS", "MODPA", "Consent", "PLAINTEXT", "REDACT_ALL");
            linkIfPresent(
                    purposes, "PERSONALIZATION", "MODPA", "Consent", "PLAINTEXT", "REDACT_ALL");
            linkIfPresent(
                    purposes,
                    "TARGETED_ADVERTISING",
                    "MODPA",
                    "Consent",
                    "PLAINTEXT",
                    "REDACT_ALL");
            linkIfPresent(
                    purposes,
                    "FRAUD_PREVENTION",
                    "MODPA",
                    "Legitimate Interest",
                    "PLAINTEXT",
                    "PLAINTEXT");
            linkIfPresent(
                    purposes,
                    "CUSTOMER_SUPPORT",
                    "MODPA",
                    "Contractual Necessity",
                    "PLAINTEXT",
                    "PLAINTEXT");
            linkIfPresent(purposes, "RESEARCH", "MODPA", "Consent", "PLAINTEXT", "REDACT_ALL");
            linkIfPresent(
                    purposes, "THIRD_PARTY_SHARING", "MODPA", "Consent", "PLAINTEXT", "REDACT_ALL");
            linkIfPresent(purposes, "DATA_SALE", "MODPA", "Consent", "PLAINTEXT", "REDACT_ALL");
            linkIfPresent(purposes, "PROFILING", "MODPA", "Consent", "PLAINTEXT", "REDACT_ALL");
            linkIfPresent(
                    purposes,
                    "LEGAL_COMPLIANCE",
                    "MODPA",
                    "Legal Obligation",
                    "PLAINTEXT",
                    "PLAINTEXT");
        }
    }

    private void seedStandardStatePurposeLinks(String regulation, Set<String> purposes) {
        linkIfPresent(
                purposes,
                "SERVICE_DELIVERY",
                regulation,
                "Contractual Necessity",
                "PLAINTEXT",
                "PLAINTEXT");
        linkIfPresent(purposes, "MARKETING", regulation, "Consent", "PLAINTEXT", "REDACT_ALL");
        if (!"CCPA".equals(regulation)) {
            linkIfPresent(
                    purposes,
                    "ANALYTICS",
                    regulation,
                    "Legitimate Interest",
                    "PLAINTEXT",
                    "HASH256");
        }
        linkIfPresent(
                purposes, "PERSONALIZATION", regulation, "Consent", "PLAINTEXT", "REDACT_ALL");
        linkIfPresent(
                purposes, "TARGETED_ADVERTISING", regulation, "Consent", "PLAINTEXT", "REDACT_ALL");
        linkIfPresent(
                purposes,
                "FRAUD_PREVENTION",
                regulation,
                "Legitimate Interest",
                "PLAINTEXT",
                "PLAINTEXT");
        linkIfPresent(
                purposes,
                "CUSTOMER_SUPPORT",
                regulation,
                "Contractual Necessity",
                "PLAINTEXT",
                "PLAINTEXT");
        linkIfPresent(
                purposes, "RESEARCH", regulation, "Legitimate Interest", "PLAINTEXT", "HASH256");
        linkIfPresent(
                purposes, "THIRD_PARTY_SHARING", regulation, "Consent", "PLAINTEXT", "REDACT_ALL");
        linkIfPresent(purposes, "DATA_SALE", regulation, "Consent", "PLAINTEXT", "REDACT_ALL");
        linkIfPresent(purposes, "PROFILING", regulation, "Consent", "PLAINTEXT", "REDACT_ALL");
        linkIfPresent(
                purposes,
                "LEGAL_COMPLIANCE",
                regulation,
                "Legal Obligation",
                "PLAINTEXT",
                "PLAINTEXT");
    }

    /** Seed ALL fields — universal reference data. */
    private void seedAllFields() {
        LOG.debug("Seeding all fields...");
        List<String[]> fields =
                List.of(
                        new String[] {"email", "Email address"},
                        new String[] {"phone_number", "Phone or mobile number"},
                        new String[] {"full_name", "Full legal name"},
                        new String[] {"first_name", "First / given name"},
                        new String[] {"last_name", "Last / family name"},
                        new String[] {"date_of_birth", "Date of birth"},
                        new String[] {"mailing_address", "Mailing or home address"},
                        new String[] {"ssn", "Social Security Number"},
                        new String[] {"passport_number", "Passport number"},
                        new String[] {"drivers_license", "Driver's license number"},
                        new String[] {"tax_id", "Tax identification number (EIN/TIN)"},
                        new String[] {"credit_card_number", "Credit or debit card number"},
                        new String[] {"bank_account_number", "Bank account number"},
                        new String[] {"routing_number", "Bank routing number"},
                        new String[] {"ip_address", "IP address (v4 or v6)"},
                        new String[] {"device_id", "Device identifier (IDFA/GAID/UUID)"},
                        new String[] {"cookie_id", "Browser cookie or tracking identifier"},
                        new String[] {"browser_fingerprint", "Browser fingerprint hash"},
                        new String[] {"diagnosis", "Medical diagnosis or condition"},
                        new String[] {"medication", "Prescribed medication"},
                        new String[] {"treatment_record", "Medical treatment record"},
                        new String[] {"insurance_id", "Health insurance plan ID"},
                        new String[] {"race", "Race or racial origin"},
                        new String[] {"ethnicity", "Ethnicity or ethnic origin"},
                        new String[] {"religion", "Religious belief or affiliation"},
                        new String[] {"sexual_orientation", "Sexual orientation"},
                        new String[] {"salary", "Salary or compensation amount"},
                        new String[] {"job_title", "Job title or position"},
                        new String[] {"employee_id", "Internal employee identifier"},
                        new String[] {"geolocation", "GPS coordinates or precise location"},
                        new String[] {"zip_code", "ZIP or postal code"},
                        new String[] {"biometric_template", "Biometric template (general)"},
                        new String[] {"facial_scan", "Facial recognition scan data"},
                        new String[] {"fingerprint", "Fingerprint scan data"},
                        new String[] {"student_id", "Student identification number"},
                        new String[] {"gpa", "Grade point average"},
                        new String[] {"transcript", "Academic transcript"});

        for (String[] field : fields) {
            dispatchSafe(
                    () ->
                            consentDispatcher.dispatch(
                                    new RegisterFieldCommandActuator.RegisterFieldCommand(
                                            field[0], null, field[1], null)),
                    "field",
                    field[0]);
        }
    }

    // -----------------------------------------------------------------------
    // Phase 3: Second-order links (all universal)
    // -----------------------------------------------------------------------

    /** Seed ALL field→category assignments — universal reference data. */
    private void seedAllFieldCategoryAssignments() {
        LOG.debug("Seeding field→category assignments...");
        List<String[]> assignments =
                List.of(
                        new String[] {"email", "PII"},
                        new String[] {"phone_number", "PII"},
                        new String[] {"full_name", "PII"},
                        new String[] {"first_name", "PII"},
                        new String[] {"last_name", "PII"},
                        new String[] {"date_of_birth", "PII"},
                        new String[] {"mailing_address", "PII"},
                        new String[] {"ssn", "SENSITIVE_PII"},
                        new String[] {"passport_number", "SENSITIVE_PII"},
                        new String[] {"drivers_license", "SENSITIVE_PII"},
                        new String[] {"tax_id", "SENSITIVE_PII"},
                        new String[] {"credit_card_number", "FINANCIAL"},
                        new String[] {"credit_card_number", "SENSITIVE_PII"},
                        new String[] {"bank_account_number", "FINANCIAL"},
                        new String[] {"bank_account_number", "SENSITIVE_PII"},
                        new String[] {"routing_number", "FINANCIAL"},
                        new String[] {"diagnosis", "HEALTH"},
                        new String[] {"medication", "HEALTH"},
                        new String[] {"treatment_record", "HEALTH"},
                        new String[] {"insurance_id", "HEALTH"},
                        new String[] {"race", "SENSITIVE_PII"},
                        new String[] {"race", "RACIAL_ETHNIC"},
                        new String[] {"ethnicity", "SENSITIVE_PII"},
                        new String[] {"ethnicity", "RACIAL_ETHNIC"},
                        new String[] {"religion", "SENSITIVE_PII"},
                        new String[] {"religion", "RELIGIOUS"},
                        new String[] {"sexual_orientation", "SENSITIVE_PII"},
                        new String[] {"sexual_orientation", "SEXUAL_ORIENTATION"},
                        new String[] {"salary", "PROFESSIONAL"},
                        new String[] {"salary", "FINANCIAL"},
                        new String[] {"job_title", "PROFESSIONAL"},
                        new String[] {"employee_id", "PROFESSIONAL"},
                        new String[] {"employee_id", "PII"},
                        new String[] {"geolocation", "GEOLOCATION"},
                        new String[] {"zip_code", "GEOLOCATION"},
                        new String[] {"zip_code", "PII"},
                        new String[] {"biometric_template", "BIOMETRIC"},
                        new String[] {"facial_scan", "BIOMETRIC"},
                        new String[] {"fingerprint", "BIOMETRIC"},
                        new String[] {"student_id", "EDUCATION"},
                        new String[] {"student_id", "PII"},
                        new String[] {"gpa", "EDUCATION"},
                        new String[] {"transcript", "EDUCATION"},
                        new String[] {"ip_address", "BEHAVIORAL"},
                        new String[] {"device_id", "BEHAVIORAL"},
                        new String[] {"cookie_id", "BEHAVIORAL"},
                        new String[] {"browser_fingerprint", "BEHAVIORAL"});

        for (String[] a : assignments) {
            dispatchSafe(
                    () ->
                            consentDispatcher.dispatch(
                                    new AssignFieldCategoryCommandActuator
                                            .AssignFieldCategoryCommand(a[0], null, a[1], null)),
                    "field-category",
                    a[0] + "→" + a[1]);
        }
    }

    /** Seed ALL purpose→category policies — universal reference data. */
    private void seedAllPurposeCategoryPolicies() {
        LOG.debug("Seeding purpose→category policies...");
        List<String[]> policies =
                List.of(
                        new String[] {"MARKETING", "PII"},
                        new String[] {"MARKETING", "BEHAVIORAL"},
                        new String[] {"MARKETING", "GEOLOCATION"},
                        new String[] {"MARKETING", "COMMUNICATIONS"},
                        new String[] {"ANALYTICS", "BEHAVIORAL"},
                        new String[] {"ANALYTICS", "GEOLOCATION"},
                        new String[] {"PERSONALIZATION", "PII"},
                        new String[] {"PERSONALIZATION", "BEHAVIORAL"},
                        new String[] {"SERVICE_DELIVERY", "PII"},
                        new String[] {"SERVICE_DELIVERY", "FINANCIAL"},
                        new String[] {"SERVICE_DELIVERY", "COMMUNICATIONS"},
                        new String[] {"TARGETED_ADVERTISING", "PII"},
                        new String[] {"TARGETED_ADVERTISING", "BEHAVIORAL"},
                        new String[] {"TARGETED_ADVERTISING", "GEOLOCATION"},
                        new String[] {"FRAUD_PREVENTION", "PII"},
                        new String[] {"FRAUD_PREVENTION", "FINANCIAL"},
                        new String[] {"FRAUD_PREVENTION", "GEOLOCATION"},
                        new String[] {"FRAUD_PREVENTION", "BIOMETRIC"},
                        new String[] {"CUSTOMER_SUPPORT", "PII"},
                        new String[] {"CUSTOMER_SUPPORT", "FINANCIAL"},
                        new String[] {"CUSTOMER_SUPPORT", "COMMUNICATIONS"},
                        new String[] {"LEGAL_COMPLIANCE", "PII"},
                        new String[] {"LEGAL_COMPLIANCE", "SENSITIVE_PII"},
                        new String[] {"LEGAL_COMPLIANCE", "FINANCIAL"},
                        new String[] {"LEGAL_COMPLIANCE", "HEALTH"},
                        new String[] {"LEGAL_COMPLIANCE", "EDUCATION"},
                        new String[] {"LEGAL_COMPLIANCE", "BIOMETRIC"},
                        new String[] {"LEGAL_COMPLIANCE", "GENETIC"},
                        new String[] {"LEGAL_COMPLIANCE", "GEOLOCATION"},
                        new String[] {"LEGAL_COMPLIANCE", "BEHAVIORAL"},
                        new String[] {"LEGAL_COMPLIANCE", "COMMUNICATIONS"},
                        new String[] {"LEGAL_COMPLIANCE", "CHILDREN"},
                        new String[] {"LEGAL_COMPLIANCE", "CREDIT"},
                        new String[] {"LEGAL_COMPLIANCE", "PROFESSIONAL"},
                        new String[] {"RESEARCH", "PII"},
                        new String[] {"RESEARCH", "HEALTH"},
                        new String[] {"RESEARCH", "BEHAVIORAL"},
                        new String[] {"RESEARCH", "EDUCATION"},
                        new String[] {"THIRD_PARTY_SHARING", "PII"},
                        new String[] {"THIRD_PARTY_SHARING", "BEHAVIORAL"},
                        new String[] {"THIRD_PARTY_SHARING", "GEOLOCATION"},
                        new String[] {"DATA_SALE", "PII"},
                        new String[] {"DATA_SALE", "BEHAVIORAL"},
                        new String[] {"DATA_SALE", "GEOLOCATION"},
                        new String[] {"PROFILING", "PII"},
                        new String[] {"PROFILING", "FINANCIAL"},
                        new String[] {"PROFILING", "BEHAVIORAL"},
                        new String[] {"PROFILING", "CREDIT"},
                        new String[] {"HEALTHCARE_OPS", "PII"},
                        new String[] {"HEALTHCARE_OPS", "HEALTH"},
                        new String[] {"HEALTHCARE_OPS", "SENSITIVE_PII"},
                        new String[] {"HEALTHCARE_OPS", "BIOMETRIC"},
                        new String[] {"HEALTHCARE_OPS", "FINANCIAL"},
                        new String[] {"FINANCIAL_SERVICES", "PII"},
                        new String[] {"FINANCIAL_SERVICES", "FINANCIAL"},
                        new String[] {"FINANCIAL_SERVICES", "CREDIT"},
                        new String[] {"FINANCIAL_SERVICES", "SENSITIVE_PII"},
                        new String[] {"EMPLOYMENT", "PII"},
                        new String[] {"EMPLOYMENT", "PROFESSIONAL"},
                        new String[] {"EMPLOYMENT", "FINANCIAL"},
                        new String[] {"EMPLOYMENT", "SENSITIVE_PII"},
                        new String[] {"EDUCATION_OPS", "PII"},
                        new String[] {"EDUCATION_OPS", "EDUCATION"},
                        new String[] {"EDUCATION_OPS", "CHILDREN"});

        for (String[] p : policies) {
            dispatchSafe(
                    () ->
                            consentDispatcher.dispatch(
                                    new AddPurposePolicyCommandActuator.AddPurposePolicyCommand(
                                            p[0], "CATEGORY", null, null, p[1])),
                    "purpose-category-policy",
                    p[0] + "→" + p[1]);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void linkRegJuris(String regulation, String jurisdiction) {
        dispatchSafe(
                () ->
                        consentDispatcher.dispatch(
                                new AddRegulationJurisdictionCommandActuator
                                        .AddRegulationJurisdictionCommand(
                                        regulation, jurisdiction, null)),
                "regulation-jurisdiction",
                regulation + "→" + jurisdiction);
    }

    private void linkIfPresent(
            Set<String> purposes,
            String purpose,
            String regulation,
            String lawfulBasis,
            String onGranted,
            String onDenied) {
        if (purposes.contains(purpose)) {
            dispatchSafe(
                    () ->
                            consentDispatcher.dispatch(
                                    new LinkPurposeToRegulationCommandActuator
                                            .LinkPurposeToRegulationCommand(
                                            purpose, regulation, lawfulBasis, onGranted, onDenied)),
                    "purpose-regulation",
                    purpose + "→" + regulation);
        }
    }

    private void dispatchSafe(Runnable action, String entityType, String name) {
        try {
            action.run();
            LOG.debug("Seed {} '{}' OK", entityType, name);
        } catch (Exception e) {
            String msg = e.getMessage();
            // "already exists" collisions are expected (idempotent re-runs)
            if (msg != null && msg.contains("already exists")) {
                LOG.debug("Seed {} '{}' skipped (already exists)", entityType, name);
            } else {
                LOG.warn("Seed {} '{}' FAILED: {}", entityType, name, msg);
                LOG.debug("Seed {} '{}' root cause:", entityType, name, e);
            }
        }
    }

    @Override
    public EventService<?> eventPublisher() {
        return eventService;
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }

    // -----------------------------------------------------------------------
    // Reference catalogs — maps ID → description for selective seeding
    // -----------------------------------------------------------------------

    private static Map<String, String> regulationCatalog() {
        var m = new LinkedHashMap<String, String>();
        m.put(
                "HIPAA",
                "Health Insurance Portability and Accountability Act — protects PHI in healthcare");
        m.put(
                "COPPA",
                "Children's Online Privacy Protection Act — verifiable parental consent for"
                        + " children under 13");
        m.put(
                "FERPA",
                "Family Educational Rights and Privacy Act — protects student education records");
        m.put(
                "GLBA",
                "Gramm-Leach-Bliley Act — protects non-public personal information in financial"
                        + " institutions");
        m.put(
                "FCRA",
                "Fair Credit Reporting Act — regulates consumer credit information and background"
                        + " checks");
        m.put(
                "TCPA",
                "Telephone Consumer Protection Act — consent for telemarketing calls and texts");
        m.put("CAN_SPAM", "CAN-SPAM Act — commercial email rules with opt-out requirements");
        m.put(
                "ECPA",
                "Electronic Communications Privacy Act — protects electronic communications from"
                        + " unauthorized access");
        m.put(
                "CCPA",
                "California Consumer Privacy Act / CPRA — sale opt-out, sensitive PI limits, minor"
                        + " protections");
        m.put(
                "VCDPA",
                "Virginia Consumer Data Protection Act — opt-out for sale/ads, opt-in for sensitive"
                        + " data");
        m.put(
                "CPA",
                "Colorado Privacy Act — opt-out for sale/ads, universal opt-out signal required");
        m.put(
                "CTDPA",
                "Connecticut Data Privacy Act — opt-out for sale/ads, global opt-out signal");
        m.put(
                "UCPA",
                "Utah Consumer Privacy Act — opt-out for sale/ads, opt-in for sensitive data");
        m.put(
                "TDPSA",
                "Texas Data Privacy and Security Act — opt-out for sale/ads, opt-in for sensitive"
                        + " data");
        m.put(
                "FDBR",
                "Florida Digital Bill of Rights — opt-out for sale/ads, opt-in for sensitive (large"
                        + " controllers)");
        m.put(
                "ICDPA",
                "Indiana Consumer Data Protection Act — opt-out for sale/ads, opt-in for sensitive"
                        + " data");
        m.put(
                "IowaCDPA",
                "Iowa Consumer Data Protection Act — opt-out for sale/ads, narrower scope");
        m.put(
                "TIPA",
                "Tennessee Information Protection Act — opt-out for sale/ads, opt-in for sensitive"
                        + " data");
        m.put(
                "MCDPA",
                "Montana Consumer Data Privacy Act — opt-out for sale/ads, opt-in for sensitive"
                        + " data");
        m.put(
                "OCPA",
                "Oregon Consumer Privacy Act — opt-out for sale/ads, opt-in for sensitive data");
        m.put(
                "DPDPA",
                "Delaware Personal Data Privacy Act — opt-out for sale/ads, opt-in for sensitive"
                        + " data");
        m.put(
                "NJDPA",
                "New Jersey Data Privacy Act — opt-out for sale/ads, opt-in for sensitive data");
        m.put(
                "NHPA",
                "New Hampshire Privacy Act — opt-out for sale/ads, opt-in for sensitive data");
        m.put(
                "KCDPA",
                "Kentucky Consumer Data Protection Act — opt-out for sale/ads, opt-in for sensitive"
                        + " data");
        m.put(
                "NDPA",
                "Nebraska Data Privacy Act — opt-out for sale/ads, opt-in for sensitive data");
        m.put(
                "MODPA",
                "Maryland Online Data Privacy Act — opt-out for sale/ads, stricter data"
                        + " minimization");
        m.put(
                "MNCDPA",
                "Minnesota Consumer Data Privacy Act — opt-out for sale/ads, opt-in for sensitive"
                        + " data");
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, String> jurisdictionCatalog() {
        var m = new LinkedHashMap<String, String>();
        m.put(
                "US_FEDERAL",
                "United States (Federal) — applies nationwide to all states and territories");
        m.put("CALIFORNIA", "State of California — CA residents");
        m.put("VIRGINIA", "Commonwealth of Virginia — VA residents");
        m.put("COLORADO", "State of Colorado — CO residents");
        m.put("CONNECTICUT", "State of Connecticut — CT residents");
        m.put("UTAH", "State of Utah — UT residents");
        m.put("TEXAS", "State of Texas — TX residents");
        m.put("FLORIDA", "State of Florida — FL residents");
        m.put("INDIANA", "State of Indiana — IN residents");
        m.put("IOWA", "State of Iowa — IA residents");
        m.put("TENNESSEE", "State of Tennessee — TN residents");
        m.put("MONTANA", "State of Montana — MT residents");
        m.put("OREGON", "State of Oregon — OR residents");
        m.put("DELAWARE", "State of Delaware — DE residents");
        m.put("NEW_JERSEY", "State of New Jersey — NJ residents");
        m.put("NEW_HAMPSHIRE", "State of New Hampshire — NH residents");
        m.put("KENTUCKY", "Commonwealth of Kentucky — KY residents");
        m.put("NEBRASKA", "State of Nebraska — NE residents");
        m.put("MARYLAND", "State of Maryland — MD residents");
        m.put("MINNESOTA", "State of Minnesota — MN residents");
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, String> purposeCatalog() {
        var m = new LinkedHashMap<String, String>();
        m.put(
                "SERVICE_DELIVERY",
                "Core service provision — fulfilling orders, maintaining accounts, providing core"
                        + " functionality");
        m.put(
                "MARKETING",
                "Marketing communications — promotional emails, newsletters, offers, campaigns,"
                        + " telemarketing");
        m.put(
                "ANALYTICS",
                "Usage analytics — aggregated statistics, performance monitoring, conversion"
                        + " tracking, A/B testing");
        m.put(
                "PERSONALIZATION",
                "Content personalization — tailoring experiences, recommendations, UI"
                        + " customization");
        m.put(
                "TARGETED_ADVERTISING",
                "Targeted/behavioral advertising — cross-context behavioral ads, interest-based"
                        + " targeting");
        m.put(
                "FRAUD_PREVENTION",
                "Fraud detection and prevention — security monitoring, anomaly detection, risk"
                        + " scoring");
        m.put(
                "CUSTOMER_SUPPORT",
                "Customer service — resolving inquiries, technical support, complaint handling");
        m.put(
                "RESEARCH",
                "Research and product improvement — market research, feature development, user"
                        + " studies");
        m.put(
                "THIRD_PARTY_SHARING",
                "Data sharing with third parties — vendor sharing, partner data exchange, data"
                        + " broker transfers");
        m.put(
                "DATA_SALE",
                "Sale of personal data — monetizing consumer data through sale or sharing for"
                        + " cross-context advertising");
        m.put(
                "PROFILING",
                "Automated profiling — algorithmic decision-making, credit scoring, behavioral"
                        + " predictions");
        m.put(
                "LEGAL_COMPLIANCE",
                "Legal and regulatory compliance — subpoenas, regulatory audits, tax obligations,"
                        + " law enforcement");
        m.put(
                "HEALTHCARE_OPS",
                "Healthcare operations — treatment, payment, healthcare operations as defined under"
                        + " HIPAA TPO");
        m.put(
                "FINANCIAL_SERVICES",
                "Financial service operations — account management, lending, insurance"
                        + " underwriting");
        m.put(
                "EMPLOYMENT",
                "Employment-related processing — HR management, payroll, background checks,"
                        + " benefits");
        m.put(
                "EDUCATION_OPS",
                "Educational operations — enrollment, grading, student services, institutional"
                        + " research");
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, String> entityCatalog() {
        var m = new LinkedHashMap<String, String>();
        m.put("customer", "A customer or client of the organization");
        m.put("employee", "An employee or staff member");
        m.put("user", "An application or platform user");
        m.put("patient", "A healthcare patient (HIPAA-relevant)");
        m.put("prospect", "A prospective customer or lead");
        m.put("vendor", "A third-party vendor or supplier");
        return Collections.unmodifiableMap(m);
    }
}
