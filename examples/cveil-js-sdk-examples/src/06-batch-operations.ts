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
 * Example 06 — Batch Operations
 *
 * Demonstrates batch consent operations (grant/revoke up to 1000 scopes
 * in a single call) and batch masking (mask multiple records at once).
 */

export async function run(client: CveilClient): Promise<void> {
  printHeader("06 — Batch Operations");

  try {
    await setupTopology(client);
    await batchGrant(client);
    await batchMask(client);
    await batchRevoke(client);
  } finally {
    await cleanup(client);
  }
}

async function setupTopology(client: CveilClient): Promise<void> {
  printStep("Setting up topology for batch operations...");

  await client.registerEntity({ entityName: "user", description: "User profile" });
  await client.registerField({ fieldName: "email", entityName: "user", description: "Email address" });
  await client.registerField({ fieldName: "phone", entityName: "user", description: "Phone number" });
  await client.registerField({ fieldName: "name", entityName: "user", description: "Full name" });
  await client.registerCategory({ categoryName: "PII", description: "Personal data" });
  await client.assignFieldToCategory({ fieldName: "email", entityName: "user", categoryName: "PII" });
  await client.assignFieldToCategory({ fieldName: "phone", entityName: "user", categoryName: "PII" });
  await client.assignFieldToCategory({ fieldName: "name", entityName: "user", categoryName: "PII" });

  await client.registerPurpose({ purposeName: "marketing", description: "Marketing campaigns" });
  await client.registerPurpose({ purposeName: "analytics", description: "Product analytics" });
  await client.registerRegulation({ regulationName: "GDPR", description: "EU GDPR" });
  await client.registerJurisdiction({ jurisdictionName: "EU", description: "European Union" });

  await client.addPurposePolicy({ purposeName: "marketing", policyType: "FIELD", fieldName: "email", entityName: "user", categoryName: "PII" });
  await client.addPurposePolicy({ purposeName: "marketing", policyType: "FIELD", fieldName: "phone", entityName: "user", categoryName: "PII" });
  await client.addPurposePolicy({ purposeName: "marketing", policyType: "FIELD", fieldName: "name", entityName: "user", categoryName: "PII" });
  await client.addPurposePolicy({ purposeName: "analytics", policyType: "FIELD", fieldName: "email", entityName: "user", categoryName: "PII" });

  await client.linkPurposeToRegulation({
    purposeName: "marketing", regulationName: "GDPR",
    lawfulBasis: "CONSENT", onGranted: "UNMASK", onDenied: "MASK",
  });
  await client.linkPurposeToRegulation({
    purposeName: "analytics", regulationName: "GDPR",
    lawfulBasis: "LEGITIMATE_INTEREST", onGranted: "UNMASK", onDenied: "MASK",
  });
  await client.addJurisdictionToRegulation({ regulationName: "GDPR", jurisdictionName: "EU" });

  await client.registerSubject({ subjectName: "batch-user", description: "Batch example subject" });

  printResult("Topology", "complete");
}

// [START batch_grant]
/**
 * Grant consent for multiple scopes in a single atomic request.
 * Supports up to 1000 scopes per call.
 */
async function batchGrant(client: CveilClient): Promise<void> {
  printStep("Batch granting consent...");

  const expiresAt = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();

  const response = await retryWithBackoff(() =>
    client.grantAll({
      subject: "batch-user",
      grants: [
        { scope: { category: "PII", fieldName: "email", entityName: "user", purposeName: "marketing" }, expiresAt },
        { scope: { category: "PII", fieldName: "phone", entityName: "user", purposeName: "marketing" }, expiresAt },
        { scope: { category: "PII", fieldName: "name", entityName: "user", purposeName: "marketing" }, expiresAt },
        { scope: { category: "PII", fieldName: "email", entityName: "user", purposeName: "analytics" }, expiresAt },
      ],
    })
  );

  printResult("Succeeded", response.succeeded);
  printResult("Failed", response.failed);
  if (response.errors.length > 0) {
    for (const err of response.errors) {
      printResult(`Error at index ${err.index}`, err.message);
    }
  }
}
// [END batch_grant]

// [START batch_mask]
/**
 * Mask multiple records in a single call. Useful for batch-processing
 * user data (e.g. exporting a list of users).
 */
async function batchMask(client: CveilClient): Promise<void> {
  printStep("Batch masking multiple records...");

  const results = await client.maskBatch("batch-user", "user", [
    { email: "alice@example.com", phone: "555-0001", name: "Alice" },
    { email: "bob@example.com", phone: "555-0002", name: "Bob" },
    { email: "carol@example.com", phone: "555-0003", name: "Carol" },
  ], "marketing");

  printResult("Records masked", results.length);
  for (let i = 0; i < results.length; i++) {
    printResult(`Record ${i}`, JSON.stringify(results[i].data));
  }
}
// [END batch_mask]

// [START batch_revoke]
/**
 * Revoke consent for multiple scopes in a single atomic request.
 */
async function batchRevoke(client: CveilClient): Promise<void> {
  printStep("Batch revoking consent...");

  const response = await client.revokeAll({
    subject: "batch-user",
    revokes: [
      { scope: { category: "PII", fieldName: "email", entityName: "user", purposeName: "marketing" } },
      { scope: { category: "PII", fieldName: "phone", entityName: "user", purposeName: "marketing" } },
      { scope: { category: "PII", fieldName: "name", entityName: "user", purposeName: "marketing" } },
      { scope: { category: "PII", fieldName: "email", entityName: "user", purposeName: "analytics" } },
    ],
  });

  printResult("Revoked", response.succeeded);
  printResult("Failed", response.failed);
}
// [END batch_revoke]

async function cleanup(client: CveilClient): Promise<void> {
  printStep("Cleaning up batch resources...");
  const safe = async (fn: () => Promise<unknown>) => { try { await fn(); } catch {} };

  await safe(() => client.removeSubject("batch-user"));

  await safe(() => client.removeJurisdictionFromRegulation("GDPR", "EU"));
  await safe(() => client.unlinkPurposeFromRegulation("analytics", "GDPR"));
  await safe(() => client.unlinkPurposeFromRegulation("marketing", "GDPR"));
  await safe(() => client.removePurposePolicy({ purposeName: "analytics", policyType: "FIELD", fieldName: "email", entityName: "user", categoryName: "PII" }));
  await safe(() => client.removePurposePolicy({ purposeName: "marketing", policyType: "FIELD", fieldName: "email", entityName: "user", categoryName: "PII" }));
  await safe(() => client.removePurposePolicy({ purposeName: "marketing", policyType: "FIELD", fieldName: "phone", entityName: "user", categoryName: "PII" }));
  await safe(() => client.removePurposePolicy({ purposeName: "marketing", policyType: "FIELD", fieldName: "name", entityName: "user", categoryName: "PII" }));
  await safe(() => client.removeJurisdiction("EU"));
  await safe(() => client.removeRegulation("GDPR"));
  await safe(() => client.removePurpose("analytics"));
  await safe(() => client.removePurpose("marketing"));

  await safe(() => client.unassignFieldFromCategory("user", "email", "PII"));
  await safe(() => client.unassignFieldFromCategory("user", "phone", "PII"));
  await safe(() => client.unassignFieldFromCategory("user", "name", "PII"));
  await safe(() => client.removeField("user", "email"));
  await safe(() => client.removeField("user", "phone"));
  await safe(() => client.removeField("user", "name"));
  await safe(() => client.removeCategory("PII"));
  await safe(() => client.removeEntity("user"));

  printCleanup("batch resources removed");
}

const isMain = process.argv[1]?.endsWith("06-batch-operations.js");
if (isMain) {
  const client = await createClient();
  try {
    await run(client);
  } finally {
    client.shutdown();
  }
}
