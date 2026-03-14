package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.TenantContext;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seeds the default US consent governance data for a newly created tenant.
 *
 * <p>Registers categories, regulations, jurisdictions, purposes, regulation↔jurisdiction links, and
 * purpose↔regulation links (with masking types and lawful bases) via the consent command dispatcher.
 *
 * <p>This actuator is designed to run as a non-fatal step in the organization registration saga. All
 * dispatch calls go through the existing consent {@link CommandDispatcher}, so events are published
 * and projections (admin SQL + Redis graph) are updated automatically.
 *
 * <p>Each registration command is idempotent — if an aggregate already exists, the underlying
 * actuator will reject the duplicate, which this actuator catches and logs as a warning.
 */
public class SeedConsentGovernanceCommandActuator
        implements CommandActuator<
                SeedConsentGovernanceCommandActuator.SeedConsentGovernanceCommand, Void> {

    private static final Logger LOG =
            LoggerFactory.getLogger(SeedConsentGovernanceCommandActuator.class);

    private final EventService<?> eventService;
    private final CommandDispatcher consentDispatcher;

    public SeedConsentGovernanceCommandActuator(
            EventService<?> eventService, CommandDispatcher consentDispatcher) {
        this.eventService = eventService;
        this.consentDispatcher = consentDispatcher;
    }

    public record SeedConsentGovernanceCommand(String tenantId) {}

    @Override
    public Void execute(SeedConsentGovernanceCommand command) {
        Objects.requireNonNull(command.tenantId(), "tenantId must not be null");

        String tenantId = command.tenantId();
        LOG.info("Seeding default US consent governance data for tenant: {}", tenantId);

        TenantContext.runWhere(
                tenantId,
                () -> {
                    seedCategories();
                    seedRegulations();
                    seedJurisdictions();
                    seedPurposes();
                    seedRegulationJurisdictionLinks();
                    seedPurposeRegulationLinks();
                });

        LOG.info("Consent governance seed completed for tenant: {}", tenantId);
        return null;
    }

    // -----------------------------------------------------------------------
    // Categories (17)
    // -----------------------------------------------------------------------

    private void seedCategories() {
        LOG.debug("Seeding categories...");
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

    // -----------------------------------------------------------------------
    // Regulations (27: 8 federal + 19 state)
    // -----------------------------------------------------------------------

    private void seedRegulations() {
        LOG.debug("Seeding regulations...");
        List<String[]> regulations =
                List.of(
                        // Federal
                        new String[] {
                            "HIPAA",
                            "Health Insurance Portability and Accountability Act — protects PHI in"
                                    + " healthcare"
                        },
                        new String[] {
                            "COPPA",
                            "Children's Online Privacy Protection Act — verifiable parental consent"
                                    + " for children under 13"
                        },
                        new String[] {
                            "FERPA",
                            "Family Educational Rights and Privacy Act — protects student education"
                                    + " records"
                        },
                        new String[] {
                            "GLBA",
                            "Gramm-Leach-Bliley Act — protects non-public personal information in"
                                    + " financial institutions"
                        },
                        new String[] {
                            "FCRA",
                            "Fair Credit Reporting Act — regulates consumer credit information and"
                                    + " background checks"
                        },
                        new String[] {
                            "TCPA",
                            "Telephone Consumer Protection Act — consent for telemarketing calls"
                                    + " and texts"
                        },
                        new String[] {
                            "CAN_SPAM",
                            "CAN-SPAM Act — commercial email rules with opt-out requirements"
                        },
                        new String[] {
                            "ECPA",
                            "Electronic Communications Privacy Act — protects electronic"
                                    + " communications from unauthorized access"
                        },
                        // State comprehensive privacy laws
                        new String[] {
                            "CCPA",
                            "California Consumer Privacy Act / CPRA — sale opt-out, sensitive PI"
                                    + " limits, minor protections"
                        },
                        new String[] {
                            "VCDPA",
                            "Virginia Consumer Data Protection Act — opt-out for sale/ads, opt-in"
                                    + " for sensitive data"
                        },
                        new String[] {
                            "CPA",
                            "Colorado Privacy Act — opt-out for sale/ads, universal opt-out signal"
                                    + " required"
                        },
                        new String[] {
                            "CTDPA",
                            "Connecticut Data Privacy Act — opt-out for sale/ads, global opt-out"
                                    + " signal"
                        },
                        new String[] {
                            "UCPA",
                            "Utah Consumer Privacy Act — opt-out for sale/ads, opt-in for sensitive"
                                    + " data"
                        },
                        new String[] {
                            "TDPSA",
                            "Texas Data Privacy and Security Act — opt-out for sale/ads, opt-in for"
                                    + " sensitive data"
                        },
                        new String[] {
                            "FDBR",
                            "Florida Digital Bill of Rights — opt-out for sale/ads, opt-in for"
                                    + " sensitive (large controllers)"
                        },
                        new String[] {
                            "ICDPA",
                            "Indiana Consumer Data Protection Act — opt-out for sale/ads, opt-in"
                                    + " for sensitive data"
                        },
                        new String[] {
                            "IowaCDPA",
                            "Iowa Consumer Data Protection Act — opt-out for sale/ads, narrower"
                                    + " scope"
                        },
                        new String[] {
                            "TIPA",
                            "Tennessee Information Protection Act — opt-out for sale/ads, opt-in"
                                    + " for sensitive data"
                        },
                        new String[] {
                            "MCDPA",
                            "Montana Consumer Data Privacy Act — opt-out for sale/ads, opt-in for"
                                    + " sensitive data"
                        },
                        new String[] {
                            "OCPA",
                            "Oregon Consumer Privacy Act — opt-out for sale/ads, opt-in for"
                                    + " sensitive data"
                        },
                        new String[] {
                            "DPDPA",
                            "Delaware Personal Data Privacy Act — opt-out for sale/ads, opt-in for"
                                    + " sensitive data"
                        },
                        new String[] {
                            "NJDPA",
                            "New Jersey Data Privacy Act — opt-out for sale/ads, opt-in for"
                                    + " sensitive data"
                        },
                        new String[] {
                            "NHPA",
                            "New Hampshire Privacy Act — opt-out for sale/ads, opt-in for sensitive"
                                    + " data"
                        },
                        new String[] {
                            "KCDPA",
                            "Kentucky Consumer Data Protection Act — opt-out for sale/ads, opt-in"
                                    + " for sensitive data"
                        },
                        new String[] {
                            "NDPA",
                            "Nebraska Data Privacy Act — opt-out for sale/ads, opt-in for sensitive"
                                    + " data"
                        },
                        new String[] {
                            "MODPA",
                            "Maryland Online Data Privacy Act — opt-out for sale/ads, stricter data"
                                    + " minimization"
                        },
                        new String[] {
                            "MNCDPA",
                            "Minnesota Consumer Data Privacy Act — opt-out for sale/ads, opt-in for"
                                    + " sensitive data"
                        });

        for (String[] reg : regulations) {
            dispatchSafe(
                    () ->
                            consentDispatcher.dispatch(
                                    new RegisterRegulationCommandActuator
                                            .RegisterRegulationCommand(reg[0], reg[1])),
                    "regulation",
                    reg[0]);
        }
    }

    // -----------------------------------------------------------------------
    // Jurisdictions (20)
    // -----------------------------------------------------------------------

    private void seedJurisdictions() {
        LOG.debug("Seeding jurisdictions...");
        List<String[]> jurisdictions =
                List.of(
                        new String[] {
                            "US_FEDERAL",
                            "United States (Federal) — applies nationwide to all states and"
                                    + " territories"
                        },
                        new String[] {"CALIFORNIA", "State of California — CA residents"},
                        new String[] {"VIRGINIA", "Commonwealth of Virginia — VA residents"},
                        new String[] {"COLORADO", "State of Colorado — CO residents"},
                        new String[] {"CONNECTICUT", "State of Connecticut — CT residents"},
                        new String[] {"UTAH", "State of Utah — UT residents"},
                        new String[] {"TEXAS", "State of Texas — TX residents"},
                        new String[] {"FLORIDA", "State of Florida — FL residents"},
                        new String[] {"INDIANA", "State of Indiana — IN residents"},
                        new String[] {"IOWA", "State of Iowa — IA residents"},
                        new String[] {"TENNESSEE", "State of Tennessee — TN residents"},
                        new String[] {"MONTANA", "State of Montana — MT residents"},
                        new String[] {"OREGON", "State of Oregon — OR residents"},
                        new String[] {"DELAWARE", "State of Delaware — DE residents"},
                        new String[] {"NEW_JERSEY", "State of New Jersey — NJ residents"},
                        new String[] {"NEW_HAMPSHIRE", "State of New Hampshire — NH residents"},
                        new String[] {"KENTUCKY", "Commonwealth of Kentucky — KY residents"},
                        new String[] {"NEBRASKA", "State of Nebraska — NE residents"},
                        new String[] {"MARYLAND", "State of Maryland — MD residents"},
                        new String[] {"MINNESOTA", "State of Minnesota — MN residents"});

        for (String[] jur : jurisdictions) {
            dispatchSafe(
                    () ->
                            consentDispatcher.dispatch(
                                    new RegisterJurisdictionCommandActuator
                                            .RegisterJurisdictionCommand(jur[0], jur[1])),
                    "jurisdiction",
                    jur[0]);
        }
    }

    // -----------------------------------------------------------------------
    // Purposes (16)
    // -----------------------------------------------------------------------

    private void seedPurposes() {
        LOG.debug("Seeding purposes...");
        List<String[]> purposes =
                List.of(
                        new String[] {
                            "SERVICE_DELIVERY",
                            "Core service provision — fulfilling orders, maintaining accounts,"
                                    + " providing core functionality"
                        },
                        new String[] {
                            "MARKETING",
                            "Marketing communications — promotional emails, newsletters, offers,"
                                    + " campaigns, telemarketing"
                        },
                        new String[] {
                            "ANALYTICS",
                            "Usage analytics — aggregated statistics, performance monitoring,"
                                    + " conversion tracking, A/B testing"
                        },
                        new String[] {
                            "PERSONALIZATION",
                            "Content personalization — tailoring experiences, recommendations, UI"
                                    + " customization"
                        },
                        new String[] {
                            "TARGETED_ADVERTISING",
                            "Targeted/behavioral advertising — cross-context behavioral ads,"
                                    + " interest-based targeting"
                        },
                        new String[] {
                            "FRAUD_PREVENTION",
                            "Fraud detection and prevention — security monitoring, anomaly"
                                    + " detection, risk scoring"
                        },
                        new String[] {
                            "CUSTOMER_SUPPORT",
                            "Customer service — resolving inquiries, technical support, complaint"
                                    + " handling"
                        },
                        new String[] {
                            "RESEARCH",
                            "Research and product improvement — market research, feature"
                                    + " development, user studies"
                        },
                        new String[] {
                            "THIRD_PARTY_SHARING",
                            "Data sharing with third parties — vendor sharing, partner data"
                                    + " exchange, data broker transfers"
                        },
                        new String[] {
                            "DATA_SALE",
                            "Sale of personal data — monetizing consumer data through sale or"
                                    + " sharing for cross-context advertising"
                        },
                        new String[] {
                            "PROFILING",
                            "Automated profiling — algorithmic decision-making, credit scoring,"
                                    + " behavioral predictions"
                        },
                        new String[] {
                            "LEGAL_COMPLIANCE",
                            "Legal and regulatory compliance — subpoenas, regulatory audits, tax"
                                    + " obligations, law enforcement"
                        },
                        new String[] {
                            "HEALTHCARE_OPS",
                            "Healthcare operations — treatment, payment, healthcare operations as"
                                    + " defined under HIPAA TPO"
                        },
                        new String[] {
                            "FINANCIAL_SERVICES",
                            "Financial service operations — account management, lending, insurance"
                                    + " underwriting"
                        },
                        new String[] {
                            "EMPLOYMENT",
                            "Employment-related processing — HR management, payroll, background"
                                    + " checks, benefits"
                        },
                        new String[] {
                            "EDUCATION_OPS",
                            "Educational operations — enrollment, grading, student services,"
                                    + " institutional research"
                        });

        for (String[] pur : purposes) {
            dispatchSafe(
                    () ->
                            consentDispatcher.dispatch(
                                    new RegisterPurposeCommandActuator.RegisterPurposeCommand(
                                            pur[0], pur[1])),
                    "purpose",
                    pur[0]);
        }
    }

    // -----------------------------------------------------------------------
    // Regulation ↔ Jurisdiction links
    // -----------------------------------------------------------------------

    private void seedRegulationJurisdictionLinks() {
        LOG.debug("Seeding regulation-jurisdiction links...");

        // Federal regulations → US_FEDERAL
        for (String fedReg :
                List.of("HIPAA", "COPPA", "FERPA", "GLBA", "FCRA", "TCPA", "CAN_SPAM", "ECPA")) {
            linkRegJuris(fedReg, "US_FEDERAL");
        }

        // State regulations → state jurisdictions (1:1)
        String[][] stateLinks = {
            {"CCPA", "CALIFORNIA"},
            {"VCDPA", "VIRGINIA"},
            {"CPA", "COLORADO"},
            {"CTDPA", "CONNECTICUT"},
            {"UCPA", "UTAH"},
            {"TDPSA", "TEXAS"},
            {"FDBR", "FLORIDA"},
            {"ICDPA", "INDIANA"},
            {"IowaCDPA", "IOWA"},
            {"TIPA", "TENNESSEE"},
            {"MCDPA", "MONTANA"},
            {"OCPA", "OREGON"},
            {"DPDPA", "DELAWARE"},
            {"NJDPA", "NEW_JERSEY"},
            {"NHPA", "NEW_HAMPSHIRE"},
            {"KCDPA", "KENTUCKY"},
            {"NDPA", "NEBRASKA"},
            {"MODPA", "MARYLAND"},
            {"MNCDPA", "MINNESOTA"},
        };
        for (String[] link : stateLinks) {
            linkRegJuris(link[0], link[1]);
        }
    }

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

    // -----------------------------------------------------------------------
    // Purpose ↔ Regulation links (with masking types and lawful basis)
    // -----------------------------------------------------------------------

    private void seedPurposeRegulationLinks() {
        LOG.debug("Seeding purpose-regulation links...");

        // -- HIPAA --
        linkPurposeReg("HEALTHCARE_OPS", "HIPAA", "Treatment/Payment/Operations", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("RESEARCH", "HIPAA", "Authorization", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("MARKETING", "HIPAA", "Authorization", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("ANALYTICS", "HIPAA", "De-identification", "PLAINTEXT", "HASH256");
        linkPurposeReg("THIRD_PARTY_SHARING", "HIPAA", "Authorization", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("LEGAL_COMPLIANCE", "HIPAA", "Legal Obligation", "PLAINTEXT", "PLAINTEXT");

        // -- COPPA --
        for (String purpose :
                List.of(
                        "SERVICE_DELIVERY",
                        "MARKETING",
                        "ANALYTICS",
                        "TARGETED_ADVERTISING",
                        "THIRD_PARTY_SHARING",
                        "PERSONALIZATION")) {
            linkPurposeReg(purpose, "COPPA", "Verifiable Parental Consent", "PLAINTEXT", "REDACT_ALL");
        }

        // -- FERPA --
        linkPurposeReg("EDUCATION_OPS", "FERPA", "School Official Exception", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("RESEARCH", "FERPA", "Written Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("THIRD_PARTY_SHARING", "FERPA", "Written Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("LEGAL_COMPLIANCE", "FERPA", "Legal Obligation", "PLAINTEXT", "PLAINTEXT");

        // -- GLBA --
        linkPurposeReg("FINANCIAL_SERVICES", "GLBA", "Contractual Necessity", "PLAINTEXT", "REDACT_UP_TO_LAST_4");
        linkPurposeReg("THIRD_PARTY_SHARING", "GLBA", "Opt-Out", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("MARKETING", "GLBA", "Opt-Out", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("FRAUD_PREVENTION", "GLBA", "Legitimate Interest", "PLAINTEXT", "PLAINTEXT");
        linkPurposeReg("LEGAL_COMPLIANCE", "GLBA", "Legal Obligation", "PLAINTEXT", "PLAINTEXT");

        // -- FCRA --
        linkPurposeReg("EMPLOYMENT", "FCRA", "Written Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("FINANCIAL_SERVICES", "FCRA", "Permissible Purpose", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("FRAUD_PREVENTION", "FCRA", "Legitimate Interest", "PLAINTEXT", "HASH256");

        // -- TCPA --
        linkPurposeReg("MARKETING", "TCPA", "Prior Express Written Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("CUSTOMER_SUPPORT", "TCPA", "Established Business Relationship", "PLAINTEXT", "PLAINTEXT");

        // -- CAN_SPAM --
        linkPurposeReg("MARKETING", "CAN_SPAM", "Opt-Out", "PLAINTEXT", "REDACT_LOCAL");
        linkPurposeReg("SERVICE_DELIVERY", "CAN_SPAM", "Transactional Exception", "PLAINTEXT", "PLAINTEXT");

        // -- ECPA --
        linkPurposeReg("CUSTOMER_SUPPORT", "ECPA", "Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("LEGAL_COMPLIANCE", "ECPA", "Legal Obligation", "PLAINTEXT", "PLAINTEXT");
        linkPurposeReg("FRAUD_PREVENTION", "ECPA", "Provider Exception", "PLAINTEXT", "HASH256");

        // -- CCPA (California — strictest state law) --
        seedStandardStatePurposeLinks("CCPA");
        linkPurposeReg("ANALYTICS", "CCPA", "Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("EMPLOYMENT", "CCPA", "Consent", "PLAINTEXT", "REDACT_ALL");

        // -- Standard state CPLs (16 states with uniform pattern) --
        for (String reg :
                List.of(
                        "VCDPA", "CPA", "CTDPA", "UCPA", "TDPSA", "FDBR", "ICDPA", "TIPA",
                        "MCDPA", "OCPA", "DPDPA", "NJDPA", "NHPA", "KCDPA", "NDPA", "MNCDPA")) {
            seedStandardStatePurposeLinks(reg);
        }

        // -- IowaCDPA (narrower — no general sensitive opt-in) --
        linkPurposeReg("SERVICE_DELIVERY", "IowaCDPA", "Contractual Necessity", "PLAINTEXT", "PLAINTEXT");
        linkPurposeReg("MARKETING", "IowaCDPA", "Legitimate Interest", "PLAINTEXT", "HASH256");
        linkPurposeReg("ANALYTICS", "IowaCDPA", "Legitimate Interest", "PLAINTEXT", "HASH256");
        linkPurposeReg("TARGETED_ADVERTISING", "IowaCDPA", "Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("FRAUD_PREVENTION", "IowaCDPA", "Legitimate Interest", "PLAINTEXT", "PLAINTEXT");
        linkPurposeReg("CUSTOMER_SUPPORT", "IowaCDPA", "Contractual Necessity", "PLAINTEXT", "PLAINTEXT");
        linkPurposeReg("THIRD_PARTY_SHARING", "IowaCDPA", "Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("DATA_SALE", "IowaCDPA", "Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("LEGAL_COMPLIANCE", "IowaCDPA", "Legal Obligation", "PLAINTEXT", "PLAINTEXT");

        // -- MODPA (Maryland — stricter data minimization) --
        linkPurposeReg("SERVICE_DELIVERY", "MODPA", "Contractual Necessity", "PLAINTEXT", "PLAINTEXT");
        linkPurposeReg("MARKETING", "MODPA", "Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("ANALYTICS", "MODPA", "Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("PERSONALIZATION", "MODPA", "Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("TARGETED_ADVERTISING", "MODPA", "Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("FRAUD_PREVENTION", "MODPA", "Legitimate Interest", "PLAINTEXT", "PLAINTEXT");
        linkPurposeReg("CUSTOMER_SUPPORT", "MODPA", "Contractual Necessity", "PLAINTEXT", "PLAINTEXT");
        linkPurposeReg("RESEARCH", "MODPA", "Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("THIRD_PARTY_SHARING", "MODPA", "Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("DATA_SALE", "MODPA", "Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("PROFILING", "MODPA", "Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("LEGAL_COMPLIANCE", "MODPA", "Legal Obligation", "PLAINTEXT", "PLAINTEXT");
    }

    /**
     * Seeds the standard purpose↔regulation links shared by most state comprehensive privacy laws.
     * CCPA overrides ANALYTICS separately (stricter). IowaCDPA and MODPA have distinct patterns.
     */
    private void seedStandardStatePurposeLinks(String regulation) {
        linkPurposeReg("SERVICE_DELIVERY", regulation, "Contractual Necessity", "PLAINTEXT", "PLAINTEXT");
        linkPurposeReg("MARKETING", regulation, "Consent", "PLAINTEXT", "REDACT_ALL");
        // CCPA overrides ANALYTICS after this method
        if (!"CCPA".equals(regulation)) {
            linkPurposeReg("ANALYTICS", regulation, "Legitimate Interest", "PLAINTEXT", "HASH256");
        }
        linkPurposeReg("PERSONALIZATION", regulation, "Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("TARGETED_ADVERTISING", regulation, "Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("FRAUD_PREVENTION", regulation, "Legitimate Interest", "PLAINTEXT", "PLAINTEXT");
        linkPurposeReg("CUSTOMER_SUPPORT", regulation, "Contractual Necessity", "PLAINTEXT", "PLAINTEXT");
        linkPurposeReg("RESEARCH", regulation, "Legitimate Interest", "PLAINTEXT", "HASH256");
        linkPurposeReg("THIRD_PARTY_SHARING", regulation, "Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("DATA_SALE", regulation, "Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("PROFILING", regulation, "Consent", "PLAINTEXT", "REDACT_ALL");
        linkPurposeReg("LEGAL_COMPLIANCE", regulation, "Legal Obligation", "PLAINTEXT", "PLAINTEXT");
    }

    private void linkPurposeReg(
            String purpose,
            String regulation,
            String lawfulBasis,
            String onGranted,
            String onDenied) {
        dispatchSafe(
                () ->
                        consentDispatcher.dispatch(
                                new LinkPurposeToRegulationCommandActuator
                                        .LinkPurposeToRegulationCommand(
                                        purpose, regulation, lawfulBasis, onGranted, onDenied)),
                "purpose-regulation",
                purpose + "→" + regulation);
    }

    // -----------------------------------------------------------------------
    // Safe dispatch helper
    // -----------------------------------------------------------------------

    private void dispatchSafe(Runnable action, String entityType, String name) {
        try {
            action.run();
        } catch (Exception e) {
            LOG.warn(
                    "Seed {} '{}' skipped (may already exist): {}",
                    entityType,
                    name,
                    e.getMessage());
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
}
