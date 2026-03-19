import { CveilClient } from "@cveil/sdk-js";
import {
  createClient,
  printHeader,
  printStep,
  printResult,
  printCleanup,
} from "./config.js";

/**
 * Example 03 — Subject Management
 *
 * Demonstrates the full lifecycle of data subjects: registration,
 * listing, retrieval, update, and removal.
 */

export async function run(client: CveilClient): Promise<void> {
  printHeader("03 — Subject Management");

  try {
    await registerSubjects(client);
    await listSubjects(client);
    await getSubject(client);
    await updateSubject(client);
  } finally {
    await cleanup(client);
  }
}

// [START register_subjects]
async function registerSubjects(client: CveilClient): Promise<void> {
  printStep("Registering subjects...");

  await client.registerSubject({ subjectName: "alice-example", description: "Alice — test subject for examples" });
  printResult("Subject", "alice-example");

  await client.registerSubject({ subjectName: "bob-example", description: "Bob — test subject for examples" });
  printResult("Subject", "bob-example");

  // Allow eventual consistency to propagate before listing/getting
  await new Promise((r) => setTimeout(r, 500));
}
// [END register_subjects]

// [START list_subjects]
async function listSubjects(client: CveilClient): Promise<void> {
  printStep("Listing all subjects...");

  const subjects = await client.listSubjects();
  printResult("Total subjects", subjects.length);
  for (const subject of subjects) {
    printResult(subject.subjectName, `created=${subject.createdAt}`);
  }
}
// [END list_subjects]

// [START get_subject]
async function getSubject(client: CveilClient): Promise<void> {
  printStep("Getting subject details...");

  const alice = await client.getSubject("alice-example");
  printResult("Name", alice.subjectName);
  printResult("Description", alice.description);
  printResult("Created by", alice.createdBy);
}
// [END get_subject]

// [START update_subject]
async function updateSubject(client: CveilClient): Promise<void> {
  printStep("Updating subject description...");

  await client.updateSubject("alice-example", { description: "Alice — updated description" });

  const updated = await client.getSubject("alice-example");
  printResult("Updated description", updated.description);
}
// [END update_subject]

async function cleanup(client: CveilClient): Promise<void> {
  printStep("Cleaning up subjects...");
  const safe = async (fn: () => Promise<unknown>) => { try { await fn(); } catch {} };

  await safe(() => client.removeSubject("alice-example"));
  await safe(() => client.removeSubject("bob-example"));

  printCleanup("subjects removed");
}

const isMain = process.argv[1]?.endsWith("03-subject-management.js");
if (isMain) {
  const client = await createClient();
  try {
    await run(client);
  } finally {
    client.shutdown();
  }
}
