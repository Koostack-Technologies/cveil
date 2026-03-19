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
 * Example 04 — Consent Lifecycle
 *
 * Demonstrates granting and revoking consent for a subject,
 * evaluating consent decisions, and viewing consent summary/history.
 * Sets up the full governance topology required for consent to work.
 */

export async function run(client: CveilClient): Promise<void> {
  printHeader("04 — Consent Lifecycle");

  try {
    await setupTopology(client);
    await grantConsent(client);
    await evaluateConsent(client);
    await viewConsentSummary(client);
    await revokeConsent(client);
    await evaluateAfterRevoke(client);
    await viewConsentHistory(client);
  } finally {
    await cleanup(client);
  }
}

async function setupTopology(client: CveilClient): Promise<void> {
  printStep("Setting up topology for consent...");

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
    purposeName: "marketing",
    regulationName: "GDPR",
    lawfulBasis: "CONSENT",
    onGranted: "UNMASK",
    onDenied: "MASK",
  });
  await client.addJurisdictionToRegulation({ regulationName: "GDPR", jurisdictionName: "EU" });

  await client.registerSubject({ subjectName: "consent-user", description: "Consent example subject" });

  printResult("Topology", "complete (user/PII → marketing → GDPR → EU)");
}

// [START grant_consent]
/**
 * Grants consent for a subject, purpose, and scope.
 * Each field requires a separate grant call with specific scope.
 */
async function grantConsent(client: CveilClient): Promise<void> {
  printStep("Granting consent...");

  const expiresAt = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();

  await retryWithBackoff(() =>
    client.grant({
      subject: "consent-user",
      scope: { category: "PII", fieldName: "email", entityName: "user", purposeName: "marketing" },
      expiresAt,
    })
  );
  await retryWithBackoff(() =>
    client.grant({
      subject: "consent-user",
      scope: { category: "PII", fieldName: "phone", entityName: "user", purposeName: "marketing" },
      expiresAt,
    })
  );
  printResult("Grant", "consent granted for email and phone");
}
// [END grant_consent]

// [START evaluate_consent]
/**
 * Evaluates whether a subject has granted consent for specific scopes.
 * Returns which scopes are granted, denied, and why.
 */
async function evaluateConsent(client: CveilClient): Promise<void> {
  printStep("Evaluating consent...");

  const response = await client.evaluateConsent("consent-user", {
    scopes: [
      { category: "PII", fieldName: "email", entityName: "user", purposeName: "marketing" },
      { category: "PII", fieldName: "phone", entityName: "user", purposeName: "marketing" },
    ],
  });

  printResult("Granted scopes", response.granted.length);
  printResult("Denied scopes", response.denied.length);

  for (const scope of response.granted) {
    printResult("Granted", `${scope.fieldName} for ${scope.purposeName}`);
  }
}
// [END evaluate_consent]

// [START consent_summary]
async function viewConsentSummary(client: CveilClient): Promise<void> {
  printStep("Viewing consent summary...");

  const summary = await client.getSubjectSummary("consent-user");
  printResult("Subject", summary.subjectId);
  printResult("Consent entries", summary.entries.length);
}
// [END consent_summary]

// [START revoke_consent]
async function revokeConsent(client: CveilClient): Promise<void> {
  printStep("Revoking consent...");

  const response = await client.revoke({
    subject: "consent-user",
    scope: { category: "PII", fieldName: "email", entityName: "user", purposeName: "marketing" },
  });
  await client.revoke({
    subject: "consent-user",
    scope: { category: "PII", fieldName: "phone", entityName: "user", purposeName: "marketing" },
  });
  printResult("Revoke", response.message);
}
// [END revoke_consent]

async function evaluateAfterRevoke(client: CveilClient): Promise<void> {
  printStep("Evaluating consent after revoke...");

  const response = await client.evaluateConsent("consent-user", {
    scopes: [
      { category: "PII", fieldName: "email", entityName: "user", purposeName: "marketing" },
    ],
  });

  printResult("Granted scopes", response.granted.length);
  printResult("Denied scopes", response.denied.length);
}

// [START consent_history]
async function viewConsentHistory(client: CveilClient): Promise<void> {
  printStep("Viewing consent history (audit trail)...");

  const historyView = await client.getSubjectHistory("consent-user");
  const entries = historyView.entries;
  printResult("History entries", entries.length);

  for (const entry of entries) {
    printResult(entry.action, `${entry.purposeName} / ${entry.fieldName} at ${entry.createdAt}`);
  }
}
// [END consent_history]

async function cleanup(client: CveilClient): Promise<void> {
  printStep("Cleaning up consent resources...");
  const safe = async (fn: () => Promise<unknown>) => { try { await fn(); } catch {} };

  await safe(() => client.removeSubject("consent-user"));

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

  printCleanup("consent resources removed");
}

const isMain = process.argv[1]?.endsWith("04-consent-lifecycle.js");
if (isMain) {
  const client = await createClient();
  try {
    await run(client);
  } finally {
    client.shutdown();
  }
}
