package com.koostack.cveil.examples;

import com.koostack.cveil.sdk.CveilClient;
import com.koostack.cveil.sdk.model.*;

import java.util.List;

import static com.koostack.cveil.examples.ExampleConfig.*;

/**
 * Example 02 — Governance Setup
 *
 * Demonstrates how to set up the consent governance topology: purposes
 * (why data is processed), regulations (legal frameworks), jurisdictions
 * (geographic regions), and policies that tie them together.
 */
public class GovernanceSetup {

    public static void main(String[] args) throws Exception {
        try (var client = createClient()) {
            run(client);
        }
    }

    public static void run(CveilClient client) throws Exception {
        printHeader("02 — Governance Setup");

        try {
            setupCatalogueDependencies(client);
            registerPurposes(client);
            registerRegulations(client);
            registerJurisdictions(client);
            createPolicies(client);
            linkTopology(client);
            verifyGovernance(client);
        } finally {
            cleanup(client);
        }
    }

    /** Catalogue resources needed for policies. */
    static void setupCatalogueDependencies(CveilClient client) throws Exception {
        printStep("Setting up catalogue dependencies...");
        client.registerEntity(new RegisterEntityRequest("user", "Application user profile"));
        client.registerField(new RegisterFieldRequest("email", "user", "Email address", null));
        client.registerField(new RegisterFieldRequest("phone", "user", "Phone number", null));
        client.registerCategory(new RegisterCategoryRequest("PII", "Personally Identifiable Information"));
        client.assignFieldToCategory(new AssignFieldCategoryRequest("email", "user", "PII", null));
        client.assignFieldToCategory(new AssignFieldCategoryRequest("phone", "user", "PII", null));
        printResult("Catalogue", "User entity with PII fields ready");
    }

    // [START register_purposes]
    static void registerPurposes(CveilClient client) throws Exception {
        printStep("Registering purposes...");

        client.registerPurpose(new RegisterPurposeRequest("marketing",
                "Email and SMS marketing campaigns"));
        printResult("Purpose", "marketing");

        client.registerPurpose(new RegisterPurposeRequest("analytics",
                "Product usage analytics and reporting"));
        printResult("Purpose", "analytics");
    }
    // [END register_purposes]

    // [START register_regulations]
    static void registerRegulations(CveilClient client) throws Exception {
        printStep("Registering regulations...");

        client.registerRegulation(new RegisterRegulationRequest("GDPR",
                "EU General Data Protection Regulation"));
        printResult("Regulation", "GDPR");

        client.registerRegulation(new RegisterRegulationRequest("CCPA",
                "California Consumer Privacy Act"));
        printResult("Regulation", "CCPA");
    }
    // [END register_regulations]

    // [START register_jurisdictions]
    static void registerJurisdictions(CveilClient client) throws Exception {
        printStep("Registering jurisdictions...");

        client.registerJurisdiction(new RegisterJurisdictionRequest("EU",
                "European Union member states"));
        printResult("Jurisdiction", "EU");

        client.registerJurisdiction(new RegisterJurisdictionRequest("US-CA",
                "State of California, United States"));
        printResult("Jurisdiction", "US-CA");
    }
    // [END register_jurisdictions]

    // [START create_policies]
    /**
     * Policies link purposes to specific fields, entities, or categories.
     * When consent is granted for a purpose, the linked policies determine
     * which fields are unmasked.
     */
    static void createPolicies(CveilClient client) throws Exception {
        printStep("Creating policies...");

        // Category-level policy: Marketing purpose covers all PII fields
        client.addPurposePolicy(new AddPurposePolicyRequest(
                "marketing", "CATEGORY", null, null, "PII"));
        printResult("Policy", "Marketing → PII (category-level)");

        // Field-level policy: Analytics only needs email
        client.addPurposePolicy(new AddPurposePolicyRequest(
                "analytics", "FIELD", "email", "user", null));
        printResult("Policy", "Analytics → User.email (field-level)");
    }
    // [END create_policies]

    // [START link_topology]
    /**
     * Links purposes to regulations and jurisdictions to regulations,
     * completing the consent governance topology:
     *   Purpose → Regulation → Jurisdiction
     */
    static void linkTopology(CveilClient client) throws Exception {
        printStep("Linking governance topology...");

        // Purpose ↔ Regulation links
        client.linkPurposeToRegulation(new LinkPurposeRegulationRequest(
                "marketing", "GDPR", "CONSENT", "UNMASK", "MASK"));
        printResult("Link", "Marketing ↔ GDPR (lawful basis: CONSENT)");

        client.linkPurposeToRegulation(new LinkPurposeRegulationRequest(
                "analytics", "GDPR", "LEGITIMATE_INTEREST", "UNMASK", "MASK"));
        printResult("Link", "Analytics ↔ GDPR (lawful basis: LEGITIMATE_INTEREST)");

        client.linkPurposeToRegulation(new LinkPurposeRegulationRequest(
                "marketing", "CCPA", "CONSENT", "UNMASK", "MASK"));
        printResult("Link", "Marketing ↔ CCPA");

        // Jurisdiction ↔ Regulation links
        client.addJurisdictionToRegulation(new AddJurisdictionToRegulationRequest("GDPR", "EU"));
        printResult("Link", "GDPR ↔ EU");

        client.addJurisdictionToRegulation(new AddJurisdictionToRegulationRequest("CCPA", "US-CA"));
        printResult("Link", "CCPA ↔ US-CA");
    }
    // [END link_topology]

    // [START verify_governance]
    static void verifyGovernance(CveilClient client) throws Exception {
        printStep("Verifying governance topology...");

        PurposeView marketing = client.getPurpose("marketing");
        printResult("Marketing regulations", marketing.regulations().size());
        printResult("Marketing policies", marketing.policies().size());

        RegulationView gdpr = client.getRegulation("GDPR");
        printResult("GDPR jurisdictions", gdpr.activeJurisdictions().size());

        List<JurisdictionView> jurisdictions = client.listJurisdictions();
        printResult("Total jurisdictions", jurisdictions.size());
    }
    // [END verify_governance]

    static void cleanup(CveilClient client) {
        printStep("Cleaning up governance resources...");
        try {
            // Unlink topology (reverse order)
            safeRun(() -> client.removeJurisdictionFromRegulation("CCPA", "US-CA"));
            safeRun(() -> client.removeJurisdictionFromRegulation("GDPR", "EU"));
            safeRun(() -> client.unlinkPurposeFromRegulation("marketing", "CCPA"));
            safeRun(() -> client.unlinkPurposeFromRegulation("analytics", "GDPR"));
            safeRun(() -> client.unlinkPurposeFromRegulation("marketing", "GDPR"));

            // Remove policies
            safeRun(() -> client.removePurposePolicy(new AddPurposePolicyRequest(
                    "analytics", "FIELD", "email", "user", null)));
            safeRun(() -> client.removePurposePolicy(new AddPurposePolicyRequest(
                    "marketing", "CATEGORY", null, null, "PII")));

            // Remove governance resources
            safeRun(() -> client.removeJurisdiction("US-CA"));
            safeRun(() -> client.removeJurisdiction("EU"));
            safeRun(() -> client.removeRegulation("CCPA"));
            safeRun(() -> client.removeRegulation("GDPR"));
            safeRun(() -> client.removePurpose("analytics"));
            safeRun(() -> client.removePurpose("marketing"));

            // Remove catalogue dependencies
            safeRun(() -> client.unassignFieldFromCategory("user", "email", "PII"));
            safeRun(() -> client.unassignFieldFromCategory("user", "phone", "PII"));
            safeRun(() -> client.removeField("user", "email"));
            safeRun(() -> client.removeField("user", "phone"));
            safeRun(() -> client.removeCategory("PII"));
            safeRun(() -> client.removeEntity("user"));

            printCleanup("governance resources removed");
        } catch (Exception e) {
            printCleanup("partial cleanup — some resources may remain: " + e.getMessage());
        }
    }

    private static void safeRun(ThrowingRunnable action) {
        try { action.run(); } catch (Exception ignored) {}
    }

    @FunctionalInterface
    interface ThrowingRunnable { void run() throws Exception; }
}
