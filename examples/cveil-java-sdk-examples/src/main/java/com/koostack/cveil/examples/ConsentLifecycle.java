package com.koostack.cveil.examples;

import com.koostack.cveil.sdk.CveilClient;
import com.koostack.cveil.sdk.model.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.koostack.cveil.examples.ExampleConfig.*;

/**
 * Example 04 — Consent Lifecycle
 *
 * Demonstrates granting and revoking consent for a subject,
 * evaluating consent decisions, and viewing consent summary/history.
 * Sets up the full governance topology required for consent to work.
 */
public class ConsentLifecycle {

    public static void main(String[] args) throws Exception {
        try (var client = createClient()) {
            run(client);
        }
    }

    public static void run(CveilClient client) throws Exception {
        printHeader("04 — Consent Lifecycle");

        try {
            setupTopology(client);
            grantConsent(client);
            evaluateConsent(client);
            viewConsentSummary(client);
            revokeConsent(client);
            evaluateAfterRevoke(client);
            viewConsentHistory(client);
        } finally {
            cleanup(client);
        }
    }

    /** Sets up catalogue + governance + subject needed for consent operations. */
    static void setupTopology(CveilClient client) throws Exception {
        printStep("Setting up topology for consent...");

        // Catalogue
        client.registerEntity(new RegisterEntityRequest("user", "User profile"));
        client.registerField(new RegisterFieldRequest("email", "user", "Email address", null));
        client.registerField(new RegisterFieldRequest("phone", "user", "Phone number", null));
        client.registerCategory(new RegisterCategoryRequest("PII", "Personal data"));
        client.assignFieldToCategory(new AssignFieldCategoryRequest("email", "user", "PII", null));
        client.assignFieldToCategory(new AssignFieldCategoryRequest("phone", "user", "PII", null));

        // Governance
        client.registerPurpose(new RegisterPurposeRequest("marketing", "Marketing campaigns"));
        client.registerRegulation(new RegisterRegulationRequest("GDPR", "EU GDPR"));
        client.registerJurisdiction(new RegisterJurisdictionRequest("EU", "European Union"));
        client.addPurposePolicy(new AddPurposePolicyRequest(
                "marketing", "FIELD", "email", "user", "PII"));
        client.addPurposePolicy(new AddPurposePolicyRequest(
                "marketing", "FIELD", "phone", "user", "PII"));
        client.linkPurposeToRegulation(new LinkPurposeRegulationRequest(
                "marketing", "GDPR", "CONSENT", "UNMASK", "MASK"));
        client.addJurisdictionToRegulation(new AddJurisdictionToRegulationRequest("GDPR", "EU"));

        // Subject
        client.registerSubject(new RegisterSubjectRequest("consent-user", "Consent example subject"));

        printResult("Topology", "complete (User/PII → Marketing → GDPR → EU)");
    }

    // [START grant_consent]
    /**
     * Grants consent for a subject, purpose, and scope.
     * The scope can target a specific field, entity, category, or use
     * wildcards for broader grants.
     */
    static void grantConsent(CveilClient client) throws Exception {
        printStep("Granting consent...");

        // Grant consent for email under Marketing purpose (retry for governance projection)
        var response = retry("grant email", () -> client.grant(new GrantRequest(
                "consent-user",
                new ConsentScope("PII", "email", "user", "marketing"),
                Instant.now().plus(30, ChronoUnit.DAYS).toString()
        )));
        printResult("Grant", response.message());

        // Grant consent for phone under Marketing purpose
        var response2 = retry("grant phone", () -> client.grant(new GrantRequest(
                "consent-user",
                new ConsentScope("PII", "phone", "user", "marketing"),
                Instant.now().plus(30, ChronoUnit.DAYS).toString()
        )));
        printResult("Grant", response2.message());
    }
    // [END grant_consent]

    // [START evaluate_consent]
    /**
     * Evaluates whether a subject has granted consent for specific scopes.
     * Returns which scopes are granted, denied, and why.
     */
    static void evaluateConsent(CveilClient client) throws Exception {
        printStep("Evaluating consent...");

        // Retry until graph projection reflects the grants
        var response = retry("evaluate consent", () -> {
            var r = client.evaluateConsent("consent-user",
                    new EvaluateConsentRequest(List.of(
                            new ConsentScope("PII", "email", "user", "marketing"),
                            new ConsentScope("PII", "phone", "user", "marketing")
                    )));
            if (r.granted().isEmpty()) throw new RuntimeException("Awaiting graph projection");
            return r;
        });

        printResult("Granted scopes", response.granted().size());
        printResult("Denied scopes", response.denied().size());

        for (var scope : response.granted()) {
            printResult("Granted", scope.fieldName() + " for " + scope.purposeName());
        }
    }
    // [END evaluate_consent]

    // [START consent_summary]
    static void viewConsentSummary(CveilClient client) throws Exception {
        printStep("Viewing consent summary...");

        SubjectConsentView summary = retry("consent summary", () -> {
            var s = client.getSubjectSummary("consent-user");
            if (s.entries().isEmpty()) throw new RuntimeException("Awaiting projection");
            return s;
        });
        printResult("Subject", summary.subjectId());
        printResult("Consent entries", summary.entries().size());
    }
    // [END consent_summary]

    // [START revoke_consent]
    static void revokeConsent(CveilClient client) throws Exception {
        printStep("Revoking consent...");

        var response = client.revoke(new RevokeRequest(
                "consent-user",
                new ConsentScope("PII", "email", "user", "marketing")
        ));
        printResult("Revoke", response.message());
    }
    // [END revoke_consent]

    static void evaluateAfterRevoke(CveilClient client) throws Exception {
        printStep("Evaluating consent after revoke...");

        // Retry until graph projection reflects the revocation
        var response = retry("evaluate after revoke", () -> {
            var r = client.evaluateConsent("consent-user",
                    new EvaluateConsentRequest(List.of(
                            new ConsentScope("PII", "email", "user", "marketing")
                    )));
            if (!r.granted().isEmpty()) throw new RuntimeException("Awaiting graph projection");
            return r;
        });

        printResult("Granted scopes", response.granted().size());
        printResult("Denied scopes", response.denied().size());
    }

    // [START consent_history]
    static void viewConsentHistory(CveilClient client) throws Exception {
        printStep("Viewing consent history (audit trail)...");

        SubjectConsentView historyView = client.getSubjectHistory("consent-user");
        List<ConsentLedgerEntry> entries = historyView.entries();
        printResult("History entries", entries.size());

        for (ConsentLedgerEntry entry : entries) {
            printResult(entry.action(),
                    entry.purposeName() + " / " + entry.fieldName()
                            + " at " + entry.createdAt());
        }
    }
    // [END consent_history]

    static void cleanup(CveilClient client) {
        printStep("Cleaning up consent resources...");
        try {
            // Subject
            safeRun(() -> client.removeSubject("consent-user"));

            // Governance (reverse link order)
            safeRun(() -> client.removeJurisdictionFromRegulation("GDPR", "EU"));
            safeRun(() -> client.unlinkPurposeFromRegulation("marketing", "GDPR"));
            safeRun(() -> client.removePurposePolicy(new AddPurposePolicyRequest(
                    "marketing", "FIELD", "email", "user", "PII")));
            safeRun(() -> client.removePurposePolicy(new AddPurposePolicyRequest(
                    "marketing", "FIELD", "phone", "user", "PII")));
            safeRun(() -> client.removeJurisdiction("EU"));
            safeRun(() -> client.removeRegulation("GDPR"));
            safeRun(() -> client.removePurpose("marketing"));

            // Catalogue
            safeRun(() -> client.unassignFieldFromCategory("user", "email", "PII"));
            safeRun(() -> client.unassignFieldFromCategory("user", "phone", "PII"));
            safeRun(() -> client.removeField("user", "email"));
            safeRun(() -> client.removeField("user", "phone"));
            safeRun(() -> client.removeCategory("PII"));
            safeRun(() -> client.removeEntity("user"));

            printCleanup("consent resources removed");
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
