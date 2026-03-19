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
 * Example 05 — Data Masking
 *
 * Demonstrates real-time field masking based on consent decisions.
 * Shows how masked vs. unmasked data looks when consent is granted
 * or denied for different purposes.
 */

export async function run(client: CveilClient): Promise<void> {
  printHeader("05 — Data Masking");

  try {
    await setupTopology(client);
    await maskWithoutConsent(client);
    await grantConsentAndMask(client);
    await evaluateMaskingDecisions(client);
    await maskWithJurisdictionOverride(client);
  } finally {
    await cleanup(client);
  }
}

async function setupTopology(client: CveilClient): Promise<void> {
  printStep("Setting up topology for masking...");

  await client.registerEntity({ entityName: "user", description: "User profile" });
  await client.registerField({ fieldName: "email", entityName: "user", description: "Email address" });
  await client.registerField({ fieldName: "phone", entityName: "user", description: "Phone number" });
  await client.registerField({ fieldName: "name", entityName: "user", description: "Full name" });
  await client.registerCategory({ categoryName: "PII", description: "Personal data" });
  await client.assignFieldToCategory({ fieldName: "email", entityName: "user", categoryName: "PII" });
  await client.assignFieldToCategory({ fieldName: "phone", entityName: "user", categoryName: "PII" });
  await client.assignFieldToCategory({ fieldName: "name", entityName: "user", categoryName: "PII" });

  await client.registerPurpose({ purposeName: "marketing", description: "Marketing campaigns" });
  await client.registerRegulation({ regulationName: "GDPR", description: "EU GDPR" });
  await client.registerRegulation({ regulationName: "CCPA", description: "California privacy" });
  await client.registerJurisdiction({ jurisdictionName: "EU", description: "European Union" });
  await client.registerJurisdiction({ jurisdictionName: "US-CA", description: "California" });
  await client.addPurposePolicy({ purposeName: "marketing", policyType: "FIELD", fieldName: "email", entityName: "user", categoryName: "PII" });
  await client.addPurposePolicy({ purposeName: "marketing", policyType: "FIELD", fieldName: "phone", entityName: "user", categoryName: "PII" });
  await client.addPurposePolicy({ purposeName: "marketing", policyType: "FIELD", fieldName: "name", entityName: "user", categoryName: "PII" });
  await client.linkPurposeToRegulation({
    purposeName: "marketing", regulationName: "GDPR",
    lawfulBasis: "CONSENT", onGranted: "UNMASK", onDenied: "MASK",
  });
  await client.linkPurposeToRegulation({
    purposeName: "marketing", regulationName: "CCPA",
    lawfulBasis: "CONSENT", onGranted: "UNMASK", onDenied: "MASK",
  });
  await client.addJurisdictionToRegulation({ regulationName: "GDPR", jurisdictionName: "EU" });
  await client.addJurisdictionToRegulation({ regulationName: "CCPA", jurisdictionName: "US-CA" });

  await client.registerSubject({ subjectName: "mask-user", description: "Masking example subject" });

  printResult("Topology", "complete");
}

// [START mask_without_consent]
/**
 * When no consent has been granted, all governed fields are masked.
 */
async function maskWithoutConsent(client: CveilClient): Promise<void> {
  printStep("Masking data WITHOUT consent...");

  const result = await client.mask("mask-user", "user", {
    email: "alice@example.com",
    phone: "555-0123",
    name: "Alice Smith",
  }, "marketing");

  printResult("Masked data", JSON.stringify(result.data));
}
// [END mask_without_consent]

// [START mask_with_consent]
/**
 * After granting consent, governed fields are unmasked.
 */
async function grantConsentAndMask(client: CveilClient): Promise<void> {
  printStep("Granting consent and masking again...");

  const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();

  await retryWithBackoff(() =>
    client.grant({
      subject: "mask-user",
      scope: { category: "PII", fieldName: "email", entityName: "user", purposeName: "marketing" },
      expiresAt,
    })
  );
  await retryWithBackoff(() =>
    client.grant({
      subject: "mask-user",
      scope: { category: "PII", fieldName: "phone", entityName: "user", purposeName: "marketing" },
      expiresAt,
    })
  );
  await retryWithBackoff(() =>
    client.grant({
      subject: "mask-user",
      scope: { category: "PII", fieldName: "name", entityName: "user", purposeName: "marketing" },
      expiresAt,
    })
  );
  printResult("Consent", "granted for marketing/PII (email, phone, name)");

  const result = await client.mask("mask-user", "user", {
    email: "alice@example.com",
    phone: "555-0123",
    name: "Alice Smith",
  }, "marketing");

  printResult("Unmasked data", JSON.stringify(result.data));
}
// [END mask_with_consent]

// [START evaluate_decisions]
/**
 * Evaluate masking decisions without actually applying masks.
 * Useful for inspecting what would happen for each field.
 */
function evaluateMaskingDecisions(client: CveilClient): void {
  printStep("Evaluating masking decisions...");

  const results = client.evaluate("mask-user", "user", ["email", "phone", "name"], "marketing");

  for (const r of results) {
    printResult(r.field, `masking=${r.masking_type}, consent=${r.consent_action}`);
  }
}
// [END evaluate_decisions]

// [START mask_with_jurisdiction]
/**
 * Override the default jurisdiction for a specific masking call.
 * Useful when serving users in different regions.
 */
async function maskWithJurisdictionOverride(client: CveilClient): Promise<void> {
  printStep("Masking with jurisdiction override (US-CA)...");

  const result = await client.maskWithJurisdiction("mask-user", "user", {
    email: "alice@example.com",
    phone: "555-0123",
    name: "Alice Smith",
  }, "marketing", "US-CA");

  printResult("Data (US-CA jurisdiction)", JSON.stringify(result.data));
}
// [END mask_with_jurisdiction]

async function cleanup(client: CveilClient): Promise<void> {
  printStep("Cleaning up masking resources...");
  const safe = async (fn: () => Promise<unknown>) => { try { await fn(); } catch {} };

  await safe(() => client.removeSubject("mask-user"));

  await safe(() => client.removeJurisdictionFromRegulation("CCPA", "US-CA"));
  await safe(() => client.removeJurisdictionFromRegulation("GDPR", "EU"));
  await safe(() => client.unlinkPurposeFromRegulation("marketing", "CCPA"));
  await safe(() => client.unlinkPurposeFromRegulation("marketing", "GDPR"));
  await safe(() => client.removePurposePolicy({ purposeName: "marketing", policyType: "FIELD", fieldName: "email", entityName: "user", categoryName: "PII" }));
  await safe(() => client.removePurposePolicy({ purposeName: "marketing", policyType: "FIELD", fieldName: "phone", entityName: "user", categoryName: "PII" }));
  await safe(() => client.removePurposePolicy({ purposeName: "marketing", policyType: "FIELD", fieldName: "name", entityName: "user", categoryName: "PII" }));
  await safe(() => client.removeJurisdiction("US-CA"));
  await safe(() => client.removeJurisdiction("EU"));
  await safe(() => client.removeRegulation("CCPA"));
  await safe(() => client.removeRegulation("GDPR"));
  await safe(() => client.removePurpose("marketing"));

  await safe(() => client.unassignFieldFromCategory("user", "email", "PII"));
  await safe(() => client.unassignFieldFromCategory("user", "phone", "PII"));
  await safe(() => client.unassignFieldFromCategory("user", "name", "PII"));
  await safe(() => client.removeField("user", "email"));
  await safe(() => client.removeField("user", "phone"));
  await safe(() => client.removeField("user", "name"));
  await safe(() => client.removeCategory("PII"));
  await safe(() => client.removeEntity("user"));

  printCleanup("masking resources removed");
}

const isMain = process.argv[1]?.endsWith("05-data-masking.js");
if (isMain) {
  const client = await createClient();
  try {
    await run(client);
  } finally {
    client.shutdown();
  }
}
