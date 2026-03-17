package com.koostack.cveil.rest.onboarding;

import java.util.*;

/**
 * Industry presets for the onboarding wizard.
 *
 * <p>Each preset defines a <b>complete, functional consent graph</b> that is ready for SDK {@code
 * evaluate} calls the moment seeding finishes. This includes:
 *
 * <ul>
 *   <li>A <b>full ideal jurisdiction set</b> (the total the industry needs)
 *   <li>A <b>free-tier default subset</b> (pre-checked within the free limit)
 *   <li>Relevant <b>purposes</b> with <b>purpose–regulation links</b>
 *   <li>Recommended <b>entities</b> (data subjects)
 * </ul>
 *
 * <p>Reference data (categories, fields, field–category assignments, purpose–category policies) is
 * always seeded in full regardless of preset.
 */
public enum IndustryPreset {
    HEALTHCARE(
            "Healthcare",
            "For healthcare organizations handling PHI under HIPAA and state privacy laws.",
            "\uD83C\uDFE5", // 🏥
            List.of("US_FEDERAL", "CALIFORNIA", "TEXAS", "FLORIDA", "COLORADO"),
            List.of("US_FEDERAL", "CALIFORNIA", "TEXAS"),
            List.of(
                    "HEALTHCARE_OPS",
                    "SERVICE_DELIVERY",
                    "RESEARCH",
                    "LEGAL_COMPLIANCE",
                    "THIRD_PARTY_SHARING",
                    "MARKETING",
                    "ANALYTICS",
                    "FRAUD_PREVENTION",
                    "CUSTOMER_SUPPORT"),
            List.of("patient", "customer", "employee")),

    ECOMMERCE(
            "E-commerce",
            "For online retailers and marketplaces handling consumer data under state privacy"
                    + " laws.",
            "\uD83D\uDED2", // 🛒
            List.of("CALIFORNIA", "VIRGINIA", "TEXAS", "COLORADO", "CONNECTICUT"),
            List.of("CALIFORNIA", "VIRGINIA", "TEXAS"),
            List.of(
                    "MARKETING",
                    "TARGETED_ADVERTISING",
                    "ANALYTICS",
                    "SERVICE_DELIVERY",
                    "PERSONALIZATION",
                    "THIRD_PARTY_SHARING",
                    "DATA_SALE",
                    "FRAUD_PREVENTION",
                    "CUSTOMER_SUPPORT",
                    "PROFILING"),
            List.of("customer", "user", "prospect")),

    FINANCE(
            "Finance",
            "For financial institutions handling NPI under GLBA, FCRA, and state privacy laws.",
            "\uD83D\uDCBC", // 💼
            List.of("US_FEDERAL", "CALIFORNIA", "TEXAS", "COLORADO", "VIRGINIA"),
            List.of("US_FEDERAL", "CALIFORNIA", "TEXAS"),
            List.of(
                    "FINANCIAL_SERVICES",
                    "FRAUD_PREVENTION",
                    "MARKETING",
                    "LEGAL_COMPLIANCE",
                    "THIRD_PARTY_SHARING",
                    "SERVICE_DELIVERY",
                    "CUSTOMER_SUPPORT",
                    "PROFILING",
                    "EMPLOYMENT"),
            List.of("customer", "employee", "prospect")),

    GENERAL(
            "General",
            "Pick your own jurisdictions — we'll derive the applicable regulations.",
            "⚙\uFE0F", // ⚙️
            List.of(), // all 20 shown as available; nothing pre-selected
            List.of(),
            List.of(
                    "SERVICE_DELIVERY",
                    "MARKETING",
                    "ANALYTICS",
                    "PERSONALIZATION",
                    "TARGETED_ADVERTISING",
                    "FRAUD_PREVENTION",
                    "CUSTOMER_SUPPORT",
                    "RESEARCH",
                    "THIRD_PARTY_SHARING",
                    "DATA_SALE",
                    "PROFILING",
                    "LEGAL_COMPLIANCE",
                    "HEALTHCARE_OPS",
                    "FINANCIAL_SERVICES",
                    "EMPLOYMENT",
                    "EDUCATION_OPS"),
            List.of("customer", "employee", "user", "patient", "prospect", "vendor"));

    // ------------------------------------------------------------------
    // Static data: jurisdiction → regulation mapping (1:1 state + federal)
    // ------------------------------------------------------------------

    /** Maps each jurisdiction ID to its primary regulation(s). */
    private static final Map<String, List<String>> JURISDICTION_REGULATIONS;

    static {
        var m = new LinkedHashMap<String, List<String>>();
        m.put(
                "US_FEDERAL",
                List.of("HIPAA", "COPPA", "FERPA", "GLBA", "FCRA", "TCPA", "CAN_SPAM", "ECPA"));
        m.put("CALIFORNIA", List.of("CCPA"));
        m.put("VIRGINIA", List.of("VCDPA"));
        m.put("COLORADO", List.of("CPA"));
        m.put("CONNECTICUT", List.of("CTDPA"));
        m.put("UTAH", List.of("UCPA"));
        m.put("TEXAS", List.of("TDPSA"));
        m.put("FLORIDA", List.of("FDBR"));
        m.put("INDIANA", List.of("ICDPA"));
        m.put("IOWA", List.of("IowaCDPA"));
        m.put("TENNESSEE", List.of("TIPA"));
        m.put("MONTANA", List.of("MCDPA"));
        m.put("OREGON", List.of("OCPA"));
        m.put("DELAWARE", List.of("DPDPA"));
        m.put("NEW_JERSEY", List.of("NJDPA"));
        m.put("NEW_HAMPSHIRE", List.of("NHPA"));
        m.put("KENTUCKY", List.of("KCDPA"));
        m.put("NEBRASKA", List.of("NDPA"));
        m.put("MARYLAND", List.of("MODPA"));
        m.put("MINNESOTA", List.of("MNCDPA"));
        JURISDICTION_REGULATIONS = Collections.unmodifiableMap(m);
    }

    // ------------------------------------------------------------------
    // Instance fields
    // ------------------------------------------------------------------

    private final String displayName;
    private final String description;
    private final String icon;
    private final List<String> fullJurisdictionSet;
    private final List<String> freeTierDefaults;
    private final List<String> purposes;
    private final List<String> entities;

    IndustryPreset(
            String displayName,
            String description,
            String icon,
            List<String> fullJurisdictionSet,
            List<String> freeTierDefaults,
            List<String> purposes,
            List<String> entities) {
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
        this.fullJurisdictionSet = List.copyOf(fullJurisdictionSet);
        this.freeTierDefaults = List.copyOf(freeTierDefaults);
        this.purposes = List.copyOf(purposes);
        this.entities = List.copyOf(entities);
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public String icon() {
        return icon;
    }

    /** The full set of jurisdictions this industry ideally needs. */
    public List<String> fullJurisdictionSet() {
        return fullJurisdictionSet;
    }

    /** The subset of jurisdictions pre-checked for free-tier users. */
    public List<String> freeTierDefaults() {
        return freeTierDefaults;
    }

    /** Purposes to seed for this industry preset. */
    public List<String> purposes() {
        return purposes;
    }

    /** Recommended data-subject entities. */
    public List<String> entities() {
        return entities;
    }

    // ------------------------------------------------------------------
    // Derivation helpers
    // ------------------------------------------------------------------

    /**
     * Returns all available jurisdictions (for the "General" preset or for showing locked items).
     */
    public static List<String> allJurisdictionIds() {
        return List.copyOf(JURISDICTION_REGULATIONS.keySet());
    }

    /**
     * Given a set of selected jurisdiction IDs, derives the regulation IDs that apply.
     *
     * @param jurisdictionIds selected jurisdictions
     * @return derived regulation IDs
     */
    public static Set<String> deriveRegulations(Collection<String> jurisdictionIds) {
        var regulations = new LinkedHashSet<String>();
        for (String jur : jurisdictionIds) {
            List<String> regs = JURISDICTION_REGULATIONS.get(jur);
            if (regs != null) {
                regulations.addAll(regs);
            }
        }
        return Collections.unmodifiableSet(regulations);
    }

    /**
     * Returns the jurisdictions that should be pre-checked given a tier limit.
     *
     * <p>If the tier limit is >= the full set size, returns the full set. Otherwise returns the
     * free-tier defaults (capped at the limit).
     *
     * @param tierLimit max jurisdictions allowed on the plan (-1 = unlimited)
     * @return pre-checked jurisdiction IDs
     */
    public List<String> preCheckedJurisdictions(int tierLimit) {
        if (tierLimit < 0 || tierLimit >= fullJurisdictionSet.size()) {
            return fullJurisdictionSet;
        }
        // Return the free-tier defaults, capped at the limit
        return freeTierDefaults.subList(0, Math.min(freeTierDefaults.size(), tierLimit));
    }

    /**
     * Returns the jurisdictions from the full set that are beyond the tier limit (should be shown
     * with a lock icon).
     *
     * @param tierLimit max jurisdictions allowed on the plan (-1 = unlimited)
     * @return locked jurisdiction IDs
     */
    public List<String> lockedJurisdictions(int tierLimit) {
        if (tierLimit < 0 || tierLimit >= fullJurisdictionSet.size()) {
            return List.of();
        }
        var preChecked = new HashSet<>(preCheckedJurisdictions(tierLimit));
        return fullJurisdictionSet.stream().filter(j -> !preChecked.contains(j)).toList();
    }

    // ------------------------------------------------------------------
    // Display helpers
    // ------------------------------------------------------------------

    /** Human-friendly display name for a jurisdiction ID. */
    public static String jurisdictionDisplayName(String jurisdictionId) {
        return switch (jurisdictionId) {
            case "US_FEDERAL" -> "United States (Federal)";
            case "CALIFORNIA" -> "California";
            case "VIRGINIA" -> "Virginia";
            case "COLORADO" -> "Colorado";
            case "CONNECTICUT" -> "Connecticut";
            case "UTAH" -> "Utah";
            case "TEXAS" -> "Texas";
            case "FLORIDA" -> "Florida";
            case "INDIANA" -> "Indiana";
            case "IOWA" -> "Iowa";
            case "TENNESSEE" -> "Tennessee";
            case "MONTANA" -> "Montana";
            case "OREGON" -> "Oregon";
            case "DELAWARE" -> "Delaware";
            case "NEW_JERSEY" -> "New Jersey";
            case "NEW_HAMPSHIRE" -> "New Hampshire";
            case "KENTUCKY" -> "Kentucky";
            case "NEBRASKA" -> "Nebraska";
            case "MARYLAND" -> "Maryland";
            case "MINNESOTA" -> "Minnesota";
            default -> jurisdictionId.replace('_', ' ');
        };
    }

    /** Short description (primary regulation names) for a jurisdiction. */
    public static String jurisdictionDescription(String jurisdictionId) {
        List<String> regs = JURISDICTION_REGULATIONS.get(jurisdictionId);
        return regs != null ? String.join(", ", regs) : "";
    }
}
