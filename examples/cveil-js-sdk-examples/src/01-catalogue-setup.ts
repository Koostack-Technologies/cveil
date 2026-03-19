import { CveilClient } from "@cveil/sdk-js";
import {
  createClient,
  printHeader,
  printStep,
  printResult,
  printCleanup,
} from "./config.js";

/**
 * Example 01 — Catalogue Setup
 *
 * Demonstrates how to define your data model in cveil by registering
 * entities (e.g. User, Order), fields (e.g. email, phone), and
 * categories (e.g. PII, Financial), then linking fields to categories.
 */

export async function run(client: CveilClient): Promise<void> {
  printHeader("01 — Catalogue Setup");

  try {
    await registerEntities(client);
    await registerFields(client);
    await registerCategories(client);
    await linkFieldsToCategories(client);
    await verifyCatalogue(client);
  } finally {
    await cleanup(client);
  }
}

// [START register_entities]
async function registerEntities(client: CveilClient): Promise<void> {
  printStep("Registering entities...");

  await client.registerEntity({ entityName: "user", description: "Application user profile" });
  printResult("Entity", "user");

  await client.registerEntity({ entityName: "order", description: "Purchase order record" });
  printResult("Entity", "order");
}
// [END register_entities]

// [START register_fields]
async function registerFields(client: CveilClient): Promise<void> {
  printStep("Registering fields...");

  await client.registerField({ fieldName: "email", entityName: "user", description: "Email address" });
  await client.registerField({ fieldName: "phone", entityName: "user", description: "Phone number" });
  await client.registerField({ fieldName: "name", entityName: "user", description: "Full name" });
  await client.registerField({ fieldName: "address", entityName: "user", description: "Mailing address" });
  await client.registerField({ fieldName: "total", entityName: "order", description: "Order total amount" });
  await client.registerField({ fieldName: "items", entityName: "order", description: "Purchased items" });
  printResult("Fields registered", 6);
}
// [END register_fields]

// [START register_categories]
async function registerCategories(client: CveilClient): Promise<void> {
  printStep("Registering categories...");

  await client.registerCategory({ categoryName: "PII", description: "Personally Identifiable Information" });
  printResult("Category", "PII");

  await client.registerCategory({ categoryName: "Financial", description: "Financial data" });
  printResult("Category", "Financial");
}
// [END register_categories]

// [START link_fields_to_categories]
async function linkFieldsToCategories(client: CveilClient): Promise<void> {
  printStep("Linking fields to categories...");

  await client.assignFieldToCategory({ fieldName: "email", entityName: "user", categoryName: "PII" });
  await client.assignFieldToCategory({ fieldName: "phone", entityName: "user", categoryName: "PII" });
  await client.assignFieldToCategory({ fieldName: "name", entityName: "user", categoryName: "PII" });
  await client.assignFieldToCategory({ fieldName: "address", entityName: "user", categoryName: "PII" });
  await client.assignFieldToCategory({ fieldName: "total", entityName: "order", categoryName: "Financial" });
  printResult("Links created", 5);
}
// [END link_fields_to_categories]

// [START verify_catalogue]
async function verifyCatalogue(client: CveilClient): Promise<void> {
  printStep("Verifying catalogue...");

  const entities = await client.listEntities();
  printResult("Entities found", entities.length);

  const fields = await client.listFields();
  printResult("Fields found", fields.length);

  const categories = await client.listCategories();
  printResult("Categories found", categories.length);

  for (const field of fields) {
    if (field.categories.length > 0) {
      printResult(
        `${field.entity.entityName}.${field.fieldName}`,
        `categories=[${field.categories.map((c) => c.categoryName).join(", ")}]`
      );
    }
  }
}
// [END verify_catalogue]

async function cleanup(client: CveilClient): Promise<void> {
  printStep("Cleaning up catalogue resources...");
  const safe = async (fn: () => Promise<unknown>) => { try { await fn(); } catch {} };

  await safe(() => client.unassignFieldFromCategory("user", "email", "PII"));
  await safe(() => client.unassignFieldFromCategory("user", "phone", "PII"));
  await safe(() => client.unassignFieldFromCategory("user", "name", "PII"));
  await safe(() => client.unassignFieldFromCategory("user", "address", "PII"));
  await safe(() => client.unassignFieldFromCategory("order", "total", "Financial"));

  await safe(() => client.removeField("user", "email"));
  await safe(() => client.removeField("user", "phone"));
  await safe(() => client.removeField("user", "name"));
  await safe(() => client.removeField("user", "address"));
  await safe(() => client.removeField("order", "total"));
  await safe(() => client.removeField("order", "items"));

  await safe(() => client.removeCategory("PII"));
  await safe(() => client.removeCategory("Financial"));

  await safe(() => client.removeEntity("user"));
  await safe(() => client.removeEntity("order"));

  printCleanup("catalogue resources removed");
}

// Run standalone
const isMain = process.argv[1]?.endsWith("01-catalogue-setup.js");
if (isMain) {
  const client = await createClient();
  try {
    await run(client);
  } finally {
    client.shutdown();
  }
}
