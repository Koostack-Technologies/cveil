import { CveilClient } from "@cveil/sdk-js";
import {
  createClient,
  printHeader,
  printStep,
  printResult,
  printCleanup,
} from "./config.js";

/**
 * Example 02 — Governance Setup
 *
 * Demonstrates how to set up the consent governance topology: purposes
 * (why data is processed), regulations (legal frameworks), jurisdictions
 * (geographic regions), and policies that tie them together.
 */

export async function run(client: CveilClient): Promise<void> {
  printHeader("02 — Governance Setup");

  try {
    await setupCatalogueDependencies(client);
    await registerPurposes(client);
    await registerRegulations(client);
    await registerJurisdictions(client);
    await createPolicies(client);
    await linkTopology(client);
    await verifyGovernance(client);
  } finally {
    await cleanup(client);
  }
}

async function setupCatalogueDependencies(client: CveilClient): Promise<void> {
  printStep("Setting up catalogue dependencies...");
  await client.registerEntity({ entityName: "user", description: "Application user profile" });
  await client.registerField({ fieldName: "email", entityName: "user", description: "Email address" });
  await client.registerField({ fieldName: "phone", entityName: "user", description: "Phone number" });
  await client.registerCategory({ categoryName: "PII", description: "Personally Identifiable Information" });
  await client.assignFieldToCategory({ fieldName: "email", entityName: "user", categoryName: "PII" });
  await client.assignFieldToCategory({ fieldName: "phone", entityName: "user", categoryName: "PII" });
  printResult("Catalogue", "user entity with PII fields ready");
}

// [START register_purposes]
async function registerPurposes(client: CveilClient): Promise<void> {
  printStep("Registering purposes...");

  await client.registerPurpose({ purposeName: "marketing", description: "Email and SMS marketing campaigns" });
  printResult("Purpose", "marketing");

  await client.registerPurpose({ purposeName: "analytics", description: "Product usage analytics and reporting" });
  printResult("Purpose", "analytics");
}
// [END register_purposes]

// [START register_regulations]
async function registerRegulations(client: CveilClient): Promise<void> {
  printStep("Registering regulations...");

  await client.registerRegulation({ regulationName: "GDPR", description: "EU General Data Protection Regulation" });
  printResult("Regulation", "GDPR");

  await client.registerRegulation({ regulationName: "CCPA", description: "California Consumer Privacy Act" });
  printResult("Regulation", "CCPA");
}
// [END register_regulations]

// [START register_jurisdictions]
async function registerJurisdictions(client: CveilClient): Promise<void> {
  printStep("Registering jurisdictions...");

  await client.registerJurisdiction({ jurisdictionName: "EU", description: "European Union member states" });
  printResult("Jurisdiction", "EU");

  await client.registerJurisdiction({ jurisdictionName: "US-CA", description: "State of California, United States" });
  printResult("Jurisdiction", "US-CA");
}
// [END register_jurisdictions]

// [START create_policies]
/**
 * Policies link purposes to specific fields, entities, or categories.
 * When consent is granted for a purpose, the linked policies determine
 * which fields are unmasked.
 */
async function createPolicies(client: CveilClient): Promise<void> {
  printStep("Creating policies...");

  await client.addPurposePolicy({
    purposeName: "marketing",
    policyType: "FIELD",
    fieldName: "email",
    entityName: "user",
    categoryName: "PII",
  });
  await client.addPurposePolicy({
    purposeName: "marketing",
    policyType: "FIELD",
    fieldName: "phone",
    entityName: "user",
    categoryName: "PII",
  });
  printResult("Policy", "marketing → email, phone (field-level, PII)");

  await client.addPurposePolicy({
    purposeName: "analytics",
    policyType: "FIELD",
    fieldName: "email",
    entityName: "user",
    categoryName: "PII",
  });
  printResult("Policy", "analytics → user.email (field-level, PII)");
}
// [END create_policies]

// [START link_topology]
/**
 * Links purposes to regulations and jurisdictions to regulations,
 * completing the consent governance topology:
 *   Purpose → Regulation → Jurisdiction
 */
async function linkTopology(client: CveilClient): Promise<void> {
  printStep("Linking governance topology...");

  await client.linkPurposeToRegulation({
    purposeName: "marketing",
    regulationName: "GDPR",
    lawfulBasis: "CONSENT",
    onGranted: "UNMASK",
    onDenied: "MASK",
  });
  printResult("Link", "marketing ↔ GDPR (lawful basis: CONSENT)");

  await client.linkPurposeToRegulation({
    purposeName: "analytics",
    regulationName: "GDPR",
    lawfulBasis: "LEGITIMATE_INTEREST",
    onGranted: "UNMASK",
    onDenied: "MASK",
  });
  printResult("Link", "analytics ↔ GDPR (lawful basis: LEGITIMATE_INTEREST)");

  await client.linkPurposeToRegulation({
    purposeName: "marketing",
    regulationName: "CCPA",
    lawfulBasis: "CONSENT",
    onGranted: "UNMASK",
    onDenied: "MASK",
  });
  printResult("Link", "marketing ↔ CCPA");

  await client.addJurisdictionToRegulation({ regulationName: "GDPR", jurisdictionName: "EU" });
  printResult("Link", "GDPR ↔ EU");

  await client.addJurisdictionToRegulation({ regulationName: "CCPA", jurisdictionName: "US-CA" });
  printResult("Link", "CCPA ↔ US-CA");
}
// [END link_topology]

// [START verify_governance]
async function verifyGovernance(client: CveilClient): Promise<void> {
  printStep("Verifying governance topology...");

  const marketing = await client.getPurpose("marketing");
  printResult("marketing regulations", marketing.regulations.length);
  printResult("marketing policies", marketing.policies.length);

  const gdpr = await client.getRegulation("GDPR");
  printResult("GDPR jurisdictions", gdpr.activeJurisdictions.length);

  const jurisdictions = await client.listJurisdictions();
  printResult("Total jurisdictions", jurisdictions.length);
}
// [END verify_governance]

async function cleanup(client: CveilClient): Promise<void> {
  printStep("Cleaning up governance resources...");
  const safe = async (fn: () => Promise<unknown>) => { try { await fn(); } catch {} };

  await safe(() => client.removeJurisdictionFromRegulation("CCPA", "US-CA"));
  await safe(() => client.removeJurisdictionFromRegulation("GDPR", "EU"));
  await safe(() => client.unlinkPurposeFromRegulation("marketing", "CCPA"));
  await safe(() => client.unlinkPurposeFromRegulation("analytics", "GDPR"));
  await safe(() => client.unlinkPurposeFromRegulation("marketing", "GDPR"));

  await safe(() => client.removePurposePolicy({ purposeName: "analytics", policyType: "FIELD", fieldName: "email", entityName: "user", categoryName: "PII" }));
  await safe(() => client.removePurposePolicy({ purposeName: "marketing", policyType: "FIELD", fieldName: "email", entityName: "user", categoryName: "PII" }));
  await safe(() => client.removePurposePolicy({ purposeName: "marketing", policyType: "FIELD", fieldName: "phone", entityName: "user", categoryName: "PII" }));

  await safe(() => client.removeJurisdiction("US-CA"));
  await safe(() => client.removeJurisdiction("EU"));
  await safe(() => client.removeRegulation("CCPA"));
  await safe(() => client.removeRegulation("GDPR"));
  await safe(() => client.removePurpose("analytics"));
  await safe(() => client.removePurpose("marketing"));

  await safe(() => client.unassignFieldFromCategory("user", "email", "PII"));
  await safe(() => client.unassignFieldFromCategory("user", "phone", "PII"));
  await safe(() => client.removeField("user", "email"));
  await safe(() => client.removeField("user", "phone"));
  await safe(() => client.removeCategory("PII"));
  await safe(() => client.removeEntity("user"));

  printCleanup("governance resources removed");
}

const isMain = process.argv[1]?.endsWith("02-governance-setup.js");
if (isMain) {
  const client = await createClient();
  try {
    await run(client);
  } finally {
    client.shutdown();
  }
}
