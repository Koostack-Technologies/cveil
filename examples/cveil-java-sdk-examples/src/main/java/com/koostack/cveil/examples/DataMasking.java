package com.koostack.cveil.examples;

import com.koostack.cveil.sdk.CveilClient;
import com.koostack.cveil.sdk.model.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static com.koostack.cveil.examples.ExampleConfig.*;

/**
 * Example 05 — Data Masking
 *
 * Demonstrates real-time field masking based on consent decisions.
 * Shows how masked vs. unmasked data looks when consent is granted
 * or denied for different purposes.
 */
public class DataMasking {

    public static void main(String[] args) throws Exception {
        try (var client = createClient()) {
            run(client);
        }
    }

    public static void run(CveilClient client) throws Exception {
        printHeader("05 — Data Masking");

        try {
            setupTopology(client);
            maskWithoutConsent(client);
            grantConsentAndMask(client);
            evaluateMaskingDecisions(client);
            maskWithJurisdictionOverride(client);
        } finally {
            cleanup(client);
        }
    }

    static void setupTopology(CveilClient client) throws Exception {
        printStep("Setting up topology for masking...");

        // Catalogue
        client.registerEntity(new RegisterEntityRequest("user", "User profile"));
        client.registerField(new RegisterFieldRequest("email", "user", "Email address", null));
        client.registerField(new RegisterFieldRequest("phone", "user", "Phone number", null));
        client.registerField(new RegisterFieldRequest("name", "user", "Full name", null));
        client.registerCategory(new RegisterCategoryRequest("PII", "Personal data"));
        client.assignFieldToCategory(new AssignFieldCategoryRequest("email", "user", "PII", null));
        client.assignFieldToCategory(new AssignFieldCategoryRequest("phone", "user", "PII", null));
        client.assignFieldToCategory(new AssignFieldCategoryRequest("name", "user", "PII", null));

        // Governance
        client.registerPurpose(new RegisterPurposeRequest("marketing", "Marketing campaigns"));
        client.registerRegulation(new RegisterRegulationRequest("GDPR", "EU GDPR"));
        client.registerRegulation(new RegisterRegulationRequest("CCPA", "California privacy"));
        client.registerJurisdiction(new RegisterJurisdictionRequest("EU", "European Union"));
        client.registerJurisdiction(new RegisterJurisdictionRequest("US-CA", "California"));
        client.addPurposePolicy(new AddPurposePolicyRequest(
                "marketing", "FIELD", "email", "user", "PII"));
        client.addPurposePolicy(new AddPurposePolicyRequest(
                "marketing", "FIELD", "phone", "user", "PII"));
        client.addPurposePolicy(new AddPurposePolicyRequest(
                "marketing", "FIELD", "name", "user", "PII"));
        client.linkPurposeToRegulation(new LinkPurposeRegulationRequest(
                "marketing", "GDPR", "CONSENT", "UNMASK", "MASK"));
        client.linkPurposeToRegulation(new LinkPurposeRegulationRequest(
                "marketing", "CCPA", "CONSENT", "UNMASK", "MASK"));
        client.addJurisdictionToRegulation(new AddJurisdictionToRegulationRequest("GDPR", "EU"));
        client.addJurisdictionToRegulation(new AddJurisdictionToRegulationRequest("CCPA", "US-CA"));

        // Subject
        client.registerSubject(new RegisterSubjectRequest("mask-user", "Masking example subject"));

        printResult("Topology", "complete");
    }

    // [START mask_without_consent]
    /**
     * When no consent has been granted, all governed fields are masked.
     */
    static void maskWithoutConsent(CveilClient client) throws Exception {
        printStep("Masking data WITHOUT consent...");

        MaskResult result = client.mask("mask-user", "user", Map.of(
                "email", "alice@example.com",
                "phone", "555-0123",
                "name", "Alice Smith"
        ), "marketing");

        printResult("Masked data", result.data());
        // Expected: all PII fields masked (e.g., "***")
    }
    // [END mask_without_consent]

    // [START mask_with_consent]
    /**
     * After granting consent, governed fields are unmasked.
     */
    static void grantConsentAndMask(CveilClient client) throws Exception {
        printStep("Granting consent and masking again...");

        retry("grant email", () -> client.grant(new GrantRequest(
                "mask-user",
                new ConsentScope("PII", "email", "user", "marketing"),
                Instant.now().plus(1, ChronoUnit.HOURS).toString()
        )));
        retry("grant phone", () -> client.grant(new GrantRequest(
                "mask-user",
                new ConsentScope("PII", "phone", "user", "marketing"),
                Instant.now().plus(1, ChronoUnit.HOURS).toString()
        )));
        retry("grant name", () -> client.grant(new GrantRequest(
                "mask-user",
                new ConsentScope("PII", "name", "user", "marketing"),
                Instant.now().plus(1, ChronoUnit.HOURS).toString()
        )));
        printResult("Consent", "granted for Marketing/PII");

        MaskResult result = client.mask("mask-user", "user", Map.of(
                "email", "alice@example.com",
                "phone", "555-0123",
                "name", "Alice Smith"
        ), "marketing");

        printResult("Unmasked data", result.data());
        // Expected: all PII fields visible
    }
    // [END mask_with_consent]

    // [START evaluate_decisions]
    /**
     * Evaluate masking decisions without actually applying masks.
     * Useful for inspecting what would happen for each field.
     */
    static void evaluateMaskingDecisions(CveilClient client) throws Exception {
        printStep("Evaluating masking decisions...");

        List<EvaluateResult> results = client.evaluate(
                "mask-user", "user",
                List.of("email", "phone", "name"),
                "marketing"
        );

        for (EvaluateResult r : results) {
            printResult(r.field(),
                    "masking=" + r.maskingType() + ", consent=" + r.consentAction());
        }
    }
    // [END evaluate_decisions]

    // [START mask_with_jurisdiction]
    /**
     * Override the default jurisdiction for a specific masking call.
     * Useful when serving users in different regions.
     */
    static void maskWithJurisdictionOverride(CveilClient client) throws Exception {
        printStep("Masking with jurisdiction override (US-CA)...");

        MaskResult result = client.maskWithJurisdiction(
                "mask-user", "user", Map.of(
                        "email", "alice@example.com",
                        "phone", "555-0123",
                        "name", "Alice Smith"
                ), "marketing", "US-CA"
        );

        printResult("Data (US-CA jurisdiction)", result.data());
    }
    // [END mask_with_jurisdiction]

    static void cleanup(CveilClient client) {
        printStep("Cleaning up masking resources...");
        try {
            safeRun(() -> client.removeSubject("mask-user"));

            safeRun(() -> client.removeJurisdictionFromRegulation("CCPA", "US-CA"));
            safeRun(() -> client.removeJurisdictionFromRegulation("GDPR", "EU"));
            safeRun(() -> client.unlinkPurposeFromRegulation("marketing", "CCPA"));
            safeRun(() -> client.unlinkPurposeFromRegulation("marketing", "GDPR"));
            safeRun(() -> client.removePurposePolicy(new AddPurposePolicyRequest(
                    "marketing", "FIELD", "email", "user", "PII")));
            safeRun(() -> client.removePurposePolicy(new AddPurposePolicyRequest(
                    "marketing", "FIELD", "phone", "user", "PII")));
            safeRun(() -> client.removePurposePolicy(new AddPurposePolicyRequest(
                    "marketing", "FIELD", "name", "user", "PII")));
            safeRun(() -> client.removeJurisdiction("US-CA"));
            safeRun(() -> client.removeJurisdiction("EU"));
            safeRun(() -> client.removeRegulation("CCPA"));
            safeRun(() -> client.removeRegulation("GDPR"));
            safeRun(() -> client.removePurpose("marketing"));

            safeRun(() -> client.unassignFieldFromCategory("user", "email", "PII"));
            safeRun(() -> client.unassignFieldFromCategory("user", "phone", "PII"));
            safeRun(() -> client.unassignFieldFromCategory("user", "name", "PII"));
            safeRun(() -> client.removeField("user", "email"));
            safeRun(() -> client.removeField("user", "phone"));
            safeRun(() -> client.removeField("user", "name"));
            safeRun(() -> client.removeCategory("PII"));
            safeRun(() -> client.removeEntity("user"));

            printCleanup("masking resources removed");
        } catch (Exception e) {
            printCleanup("partial cleanup: " + e.getMessage());
        }
    }

    private static void safeRun(ThrowingRunnable action) {
        try { action.run(); } catch (Exception ignored) {}
    }

    @FunctionalInterface
    interface ThrowingRunnable { void run() throws Exception; }
}
