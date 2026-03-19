import { CveilClient } from "@cveil/sdk-js";
import {
  createClient,
  printHeader,
  printStep,
  printResult,
  printCleanup,
  retryWithBackoff,
} from "./config.js";

/**
 * Example 07 — DSAR Compliance
 *
 * Demonstrates Data Subject Access Request (DSAR) operations for
 * GDPR compliance: exporting all subject data (Article 20),
 * deleting subject data (Article 17), and shredding encryption keys.
 */

export async function run(client: CveilClient): Promise<void> {
  printHeader("07 — DSAR Compliance (GDPR Articles 17 & 20)");

  try {
    await setupTopology(client);
    await grantSomeConsent(client);
    await exportSubjectData(client);
    await deleteSubjectData(client);
    await shredDsarKey(client);
  } finally {
    await cleanup(client);
  }
}

async function setupTopology(client: CveilClient): Promise<void> {
  printStep("Setting up topology for DSAR...");

  await client.registerEntity({ entityName: "user", description: "User profile" });
  await client.registerField({ fieldName: "email", entityName: "user", description: "Email address" });
  await client.registerField({ fieldName: "phone", entityName: "user", description: "Phone number" });
  await client.registerCategory({ categoryName: "PII", description: "Personal data" });
  await client.assignFieldToCategory({ fieldName: "email", entityName: "user", categoryName: "PII" });
  await client.assignFieldToCategory({ fieldName: "phone", entityName: "user", categoryName: "PII" });

  await client.registerPurpose({ purposeName: "marketing", description: "Marketing campaigns" });
  await client.registerRegulation({ regulationName: "GDPR", description: "EU GDPR" });
  await client.registerJurisdiction({ jurisdictionName: "EU", description: "European Union" });
  await client.addPurposePolicy({ purposeName: "marketing", policyType: "FIELD", fieldName: "email", entityName: "user", categoryName: "PII" });
  await client.addPurposePolicy({ purposeName: "marketing", policyType: "FIELD", fieldName: "phone", entityName: "user", categoryName: "PII" });
  await client.linkPurposeToRegulation({
    purposeName: "marketing", regulationName: "GDPR",
    lawfulBasis: "CONSENT", onGranted: "UNMASK", onDenied: "MASK",
  });
  await client.addJurisdictionToRegulation({ regulationName: "GDPR", jurisdictionName: "EU" });

  await client.registerSubject({ subjectName: "dsar-user", description: "DSAR example subject" });

  printResult("Topology", "complete");
}

async function grantSomeConsent(client: CveilClient): Promise<void> {
  printStep("Granting consent to create audit trail...");

  const expiresAt = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();

  await retryWithBackoff(() =>
    client.grant({
      subject: "dsar-user",
      scope: { category: "PII", fieldName: "email", entityName: "user", purposeName: "marketing" },
      expiresAt,
    })
  );
  await retryWithBackoff(() =>
    client.grant({
      subject: "dsar-user",
      scope: { category: "PII", fieldName: "phone", entityName: "user", purposeName: "marketing" },
      expiresAt,
    })
  );
  printResult("Consent", "granted for marketing/PII (email, phone)");
}

// [START export_subject_data]
/**
 * GDPR Article 20 — Data Portability.
 * Exports all consent records and cached decision keys for a subject.
 */
async function exportSubjectData(client: CveilClient): Promise<void> {
  printStep("Exporting subject data (GDPR Article 20)...");

  const data = await client.exportSubjectData("dsar-user");

  printResult("Subject", data.subject);
  printResult("Cached key count", data.cachedKeyCount);
  printResult("Exported at", data.exportedAt);
  printResult("Consent entries", data.consent.entries.length);
}
// [END export_subject_data]

// [START delete_subject_data]
/**
 * GDPR Article 17 — Right to Erasure.
 * Revokes all consent and deletes cached decision keys for a subject.
 * Returns a deletion receipt for audit purposes.
 */
async function deleteSubjectData(client: CveilClient): Promise<void> {
  printStep("Deleting subject data (GDPR Article 17)...");

  const receipt = await client.deleteSubjectData("dsar-user");

  printResult("Deletion request ID", receipt.deletionRequestId);
  printResult("Ledger entries revoked", receipt.ledgerEntriesRevoked);
  printResult("Cached keys deleted", receipt.cachedKeysDeleted);
  printResult("Status", receipt.status);
  printResult("Completed at", receipt.completedAt);
}
// [END delete_subject_data]

// [START shred_dsar_key]
/**
 * Irreversibly destroys the DSAR encryption key for a subject.
 * After shredding, exported data can never be decrypted again.
 * This is the final step in a complete data erasure workflow.
 */
async function shredDsarKey(client: CveilClient): Promise<void> {
  printStep("Shredding DSAR encryption key (irreversible)...");

  const response = await client.shredDsarKey("dsar-user");
  printResult("Shred", response.message);
}
// [END shred_dsar_key]

async function cleanup(client: CveilClient): Promise<void> {
  printStep("Cleaning up DSAR resources...");
  const safe = async (fn: () => Promise<unknown>) => { try { await fn(); } catch {} };

  await safe(() => client.removeSubject("dsar-user"));

  await safe(() => client.removeJurisdictionFromRegulation("GDPR", "EU"));
  await safe(() => client.unlinkPurposeFromRegulation("marketing", "GDPR"));
  await safe(() => client.removePurposePolicy({ purposeName: "marketing", policyType: "FIELD", fieldName: "email", entityName: "user", categoryName: "PII" }));
  await safe(() => client.removePurposePolicy({ purposeName: "marketing", policyType: "FIELD", fieldName: "phone", entityName: "user", categoryName: "PII" }));
  await safe(() => client.removeJurisdiction("EU"));
  await safe(() => client.removeRegulation("GDPR"));
  await safe(() => client.removePurpose("marketing"));

  await safe(() => client.unassignFieldFromCategory("user", "email", "PII"));
  await safe(() => client.unassignFieldFromCategory("user", "phone", "PII"));
  await safe(() => client.removeField("user", "email"));
  await safe(() => client.removeField("user", "phone"));
  await safe(() => client.removeCategory("PII"));
  await safe(() => client.removeEntity("user"));

  printCleanup("DSAR resources removed");
}

const isMain = process.argv[1]?.endsWith("07-dsar-compliance.js");
if (isMain) {
  const client = await createClient();
  try {
    await run(client);
  } finally {
    client.shutdown();
  }
}
