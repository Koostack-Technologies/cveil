package com.koostack.cveil.examples;

import com.koostack.cveil.sdk.CveilClient;
import com.koostack.cveil.sdk.model.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static com.koostack.cveil.examples.ExampleConfig.*;

/**
 * Example 06 — Batch Operations
 *
 * Demonstrates batch consent operations (grant/revoke up to 1000 scopes
 * in a single call) and batch masking (mask multiple records at once).
 */
public class BatchOperations {

    public static void main(String[] args) throws Exception {
        try (var client = createClient()) {
            run(client);
        }
    }

    public static void run(CveilClient client) throws Exception {
        printHeader("06 — Batch Operations");

        try {
            setupTopology(client);
            batchGrant(client);
            batchMask(client);
            batchRevoke(client);
        } finally {
            cleanup(client);
        }
    }

    static void setupTopology(CveilClient client) throws Exception {
        printStep("Setting up topology for batch operations...");

        client.registerEntity(new RegisterEntityRequest("user", "User profile"));
        client.registerField(new RegisterFieldRequest("email", "user", "Email address", null));
        client.registerField(new RegisterFieldRequest("phone", "user", "Phone number", null));
        client.registerField(new RegisterFieldRequest("name", "user", "Full name", null));
        client.registerCategory(new RegisterCategoryRequest("PII", "Personal data"));
        client.assignFieldToCategory(new AssignFieldCategoryRequest("email", "user", "PII", null));
        client.assignFieldToCategory(new AssignFieldCategoryRequest("phone", "user", "PII", null));
        client.assignFieldToCategory(new AssignFieldCategoryRequest("name", "user", "PII", null));

        client.registerPurpose(new RegisterPurposeRequest("marketing", "Marketing campaigns"));
        client.registerPurpose(new RegisterPurposeRequest("analytics", "Product analytics"));
        client.registerRegulation(new RegisterRegulationRequest("GDPR", "EU GDPR"));
        client.registerJurisdiction(new RegisterJurisdictionRequest("EU", "European Union"));

        client.addPurposePolicy(new AddPurposePolicyRequest(
                "marketing", "FIELD", "email", "user", "PII"));
        client.addPurposePolicy(new AddPurposePolicyRequest(
                "marketing", "FIELD", "phone", "user", "PII"));
        client.addPurposePolicy(new AddPurposePolicyRequest(
                "marketing", "FIELD", "name", "user", "PII"));
        client.addPurposePolicy(new AddPurposePolicyRequest(
                "analytics", "FIELD", "email", "user", "PII"));

        client.linkPurposeToRegulation(new LinkPurposeRegulationRequest(
                "marketing", "GDPR", "CONSENT", "UNMASK", "MASK"));
        client.linkPurposeToRegulation(new LinkPurposeRegulationRequest(
                "analytics", "GDPR", "LEGITIMATE_INTEREST", "UNMASK", "MASK"));
        client.addJurisdictionToRegulation(new AddJurisdictionToRegulationRequest("GDPR", "EU"));

        client.registerSubject(new RegisterSubjectRequest("batch-user", "Batch example subject"));

        printResult("Topology", "complete");
    }

    // [START batch_grant]
    /**
     * Grant consent for multiple scopes in a single atomic request.
     * Supports up to 1000 scopes per call.
     */
    static void batchGrant(CveilClient client) throws Exception {
        printStep("Batch granting consent...");

        String expiresAt = Instant.now().plus(30, ChronoUnit.DAYS).toString();

        BatchConsentResponse response = retry("batch grant", () -> {
            var r = client.grantAll(new BatchGrantRequest(
                    "batch-user",
                    List.of(
                            new BatchConsentEntry(
                                    new ConsentScope("PII", "email", "user", "marketing"), expiresAt),
                            new BatchConsentEntry(
                                    new ConsentScope("PII", "phone", "user", "marketing"), expiresAt),
                            new BatchConsentEntry(
                                    new ConsentScope("PII", "name", "user", "marketing"), expiresAt),
                            new BatchConsentEntry(
                                    new ConsentScope("PII", "email", "user", "analytics"), expiresAt)
                    )
            ));
            if (r.failed() > 0) throw new RuntimeException("Batch grant had failures: " + r.errors());
            return r;
        });

        printResult("Succeeded", response.succeeded());
        printResult("Failed", response.failed());
        if (!response.errors().isEmpty()) {
            for (BatchItemError err : response.errors()) {
                printResult("Error at index " + err.index(), err.message());
            }
        }
    }
    // [END batch_grant]

    // [START batch_mask]
    /**
     * Mask multiple records in a single call. Useful for batch-processing
     * user data (e.g. exporting a list of users).
     */
    static void batchMask(CveilClient client) throws Exception {
        printStep("Batch masking multiple records...");

        List<MaskResult> results = client.maskBatch("batch-user", "user",
                List.of(
                        Map.of("email", "alice@example.com", "phone", "555-0001", "name", "Alice"),
                        Map.of("email", "bob@example.com", "phone", "555-0002", "name", "Bob"),
                        Map.of("email", "carol@example.com", "phone", "555-0003", "name", "Carol")
                ),
                "marketing"
        );

        printResult("Records masked", results.size());
        for (int i = 0; i < results.size(); i++) {
            printResult("Record " + i, results.get(i).data());
        }
    }
    // [END batch_mask]

    // [START batch_revoke]
    /**
     * Revoke consent for multiple scopes in a single atomic request.
     */
    static void batchRevoke(CveilClient client) throws Exception {
        printStep("Batch revoking consent...");

        BatchConsentResponse response = client.revokeAll(new BatchRevokeRequest(
                "batch-user",
                List.of(
                        new BatchConsentEntry(
                                new ConsentScope("PII", "email", "user", "marketing"), null),
                        new BatchConsentEntry(
                                new ConsentScope("PII", "phone", "user", "marketing"), null),
                        new BatchConsentEntry(
                                new ConsentScope("PII", "name", "user", "marketing"), null),
                        new BatchConsentEntry(
                                new ConsentScope("PII", "email", "user", "analytics"), null)
                )
        ));

        printResult("Revoked", response.succeeded());
        printResult("Failed", response.failed());
    }
    // [END batch_revoke]

    static void cleanup(CveilClient client) {
        printStep("Cleaning up batch resources...");
        try {
            safeRun(() -> client.removeSubject("batch-user"));

            safeRun(() -> client.removeJurisdictionFromRegulation("GDPR", "EU"));
            safeRun(() -> client.unlinkPurposeFromRegulation("analytics", "GDPR"));
            safeRun(() -> client.unlinkPurposeFromRegulation("marketing", "GDPR"));
            safeRun(() -> client.removePurposePolicy(new AddPurposePolicyRequest(
                    "analytics", "FIELD", "email", "user", "PII")));
            safeRun(() -> client.removePurposePolicy(new AddPurposePolicyRequest(
                    "marketing", "FIELD", "name", "user", "PII")));
            safeRun(() -> client.removePurposePolicy(new AddPurposePolicyRequest(
                    "marketing", "FIELD", "phone", "user", "PII")));
            safeRun(() -> client.removePurposePolicy(new AddPurposePolicyRequest(
                    "marketing", "FIELD", "email", "user", "PII")));
            safeRun(() -> client.removeJurisdiction("EU"));
            safeRun(() -> client.removeRegulation("GDPR"));
            safeRun(() -> client.removePurpose("analytics"));
            safeRun(() -> client.removePurpose("marketing"));

            safeRun(() -> client.unassignFieldFromCategory("user", "email", "PII"));
            safeRun(() -> client.unassignFieldFromCategory("user", "phone", "PII"));
            safeRun(() -> client.unassignFieldFromCategory("user", "name", "PII"));
            safeRun(() -> client.removeField("user", "email"));
            safeRun(() -> client.removeField("user", "phone"));
            safeRun(() -> client.removeField("user", "name"));
            safeRun(() -> client.removeCategory("PII"));
            safeRun(() -> client.removeEntity("user"));

            printCleanup("batch resources removed");
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
