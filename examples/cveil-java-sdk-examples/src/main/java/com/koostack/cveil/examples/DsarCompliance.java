package com.koostack.cveil.examples;

import com.koostack.cveil.sdk.CveilClient;
import com.koostack.cveil.sdk.model.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.koostack.cveil.examples.ExampleConfig.*;

/**
 * Example 07 — DSAR Compliance
 *
 * Demonstrates Data Subject Access Request (DSAR) operations for
 * GDPR compliance: exporting all subject data (Article 20),
 * deleting subject data (Article 17), and shredding encryption keys.
 */
public class DsarCompliance {

    public static void main(String[] args) throws Exception {
        try (var client = createClient()) {
            run(client);
        }
    }

    public static void run(CveilClient client) throws Exception {
        printHeader("07 — DSAR Compliance (GDPR Articles 17 & 20)");

        try {
            setupTopology(client);
            grantSomeConsent(client);
            exportSubjectData(client);
            deleteSubjectData(client);
            shredDsarKey(client);
        } finally {
            cleanup(client);
        }
    }

    static void setupTopology(CveilClient client) throws Exception {
        printStep("Setting up topology for DSAR...");

        client.registerEntity(new RegisterEntityRequest("user", "User profile"));
        client.registerField(new RegisterFieldRequest("email", "user", "Email address", null));
        client.registerField(new RegisterFieldRequest("phone", "user", "Phone number", null));
        client.registerCategory(new RegisterCategoryRequest("PII", "Personal data"));
        client.assignFieldToCategory(new AssignFieldCategoryRequest("email", "user", "PII", null));
        client.assignFieldToCategory(new AssignFieldCategoryRequest("phone", "user", "PII", null));

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

        client.registerSubject(new RegisterSubjectRequest("dsar-user", "DSAR example subject"));

        printResult("Topology", "complete");
    }

    static void grantSomeConsent(CveilClient client) throws Exception {
        printStep("Granting consent to create audit trail...");

        retry("grant email", () -> client.grant(new GrantRequest(
                "dsar-user",
                new ConsentScope("PII", "email", "user", "marketing"),
                Instant.now().plus(30, ChronoUnit.DAYS).toString()
        )));
        retry("grant phone", () -> client.grant(new GrantRequest(
                "dsar-user",
                new ConsentScope("PII", "phone", "user", "marketing"),
                Instant.now().plus(30, ChronoUnit.DAYS).toString()
        )));
        printResult("Consent", "granted for Marketing/PII");
    }

    // [START export_subject_data]
    /**
     * GDPR Article 20 — Data Portability.
     * Exports all consent records and cached decision keys for a subject.
     */
    static void exportSubjectData(CveilClient client) throws Exception {
        printStep("Exporting subject data (GDPR Article 20)...");

        SubjectDataExport export = client.exportSubjectData("dsar-user");

        printResult("Subject", export.subject());
        printResult("Cached key count", export.cachedKeyCount());
        printResult("Exported at", export.exportedAt());
        printResult("Consent entries", export.consent().entries().size());
    }
    // [END export_subject_data]

    // [START delete_subject_data]
    /**
     * GDPR Article 17 — Right to Erasure.
     * Revokes all consent and deletes cached decision keys for a subject.
     * Returns a deletion receipt for audit purposes.
     */
    static void deleteSubjectData(CveilClient client) throws Exception {
        printStep("Deleting subject data (GDPR Article 17)...");

        DsarDeletionReceipt receipt = client.deleteSubjectData("dsar-user");

        printResult("Deletion request ID", receipt.deletionRequestId());
        printResult("Ledger entries revoked", receipt.ledgerEntriesRevoked());
        printResult("Cached keys deleted", receipt.cachedKeysDeleted());
        printResult("Status", receipt.status());
        printResult("Completed at", receipt.completedAt());
    }
    // [END delete_subject_data]

    // [START shred_dsar_key]
    /**
     * Irreversibly destroys the DSAR encryption key for a subject.
     * After shredding, exported data can never be decrypted again.
     * This is the final step in a complete data erasure workflow.
     */
    static void shredDsarKey(CveilClient client) throws Exception {
        printStep("Shredding DSAR encryption key (irreversible)...");

        MessageResponse response = client.shredDsarKey("dsar-user");
        printResult("Shred", response.message());
    }
    // [END shred_dsar_key]

    static void cleanup(CveilClient client) {
        printStep("Cleaning up DSAR resources...");
        try {
            safeRun(() -> client.removeSubject("dsar-user"));

            safeRun(() -> client.removeJurisdictionFromRegulation("GDPR", "EU"));
            safeRun(() -> client.unlinkPurposeFromRegulation("marketing", "GDPR"));
            safeRun(() -> client.removePurposePolicy(new AddPurposePolicyRequest(
                    "marketing", "FIELD", "email", "user", "PII")));
            safeRun(() -> client.removePurposePolicy(new AddPurposePolicyRequest(
                    "marketing", "FIELD", "phone", "user", "PII")));
            safeRun(() -> client.removeJurisdiction("EU"));
            safeRun(() -> client.removeRegulation("GDPR"));
            safeRun(() -> client.removePurpose("marketing"));

            safeRun(() -> client.unassignFieldFromCategory("user", "email", "PII"));
            safeRun(() -> client.unassignFieldFromCategory("user", "phone", "PII"));
            safeRun(() -> client.removeField("user", "email"));
            safeRun(() -> client.removeField("user", "phone"));
            safeRun(() -> client.removeCategory("PII"));
            safeRun(() -> client.removeEntity("user"));

            printCleanup("DSAR resources removed");
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
