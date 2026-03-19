import { CveilClient } from "@cveil/sdk-js";
import {
  createClient,
  printHeader,
  printStep,
  API_URL,
  JURISDICTION,
} from "./config.js";

import { run as catalogueSetup } from "./01-catalogue-setup.js";
import { run as governanceSetup } from "./02-governance-setup.js";
import { run as subjectManagement } from "./03-subject-management.js";
import { run as consentLifecycle } from "./04-consent-lifecycle.js";
import { run as dataMasking } from "./05-data-masking.js";
import { run as batchOperations } from "./06-batch-operations.js";
import { run as dsarCompliance } from "./07-dsar-compliance.js";

/**
 * Runs all cveil SDK examples in sequence against the configured environment.
 *
 * Usage:
 *   export CVEIL_API_URL=https://consentveil.koostack.com
 *   export CVEIL_API_TOKEN=cveil_...
 *   export CVEIL_JURISDICTION=EU
 *   npm start
 */

const examples: { name: string; fn: (client: CveilClient) => Promise<void> }[] = [
  { name: "01-CatalogueSetup", fn: catalogueSetup },
  { name: "02-GovernanceSetup", fn: governanceSetup },
  { name: "03-SubjectManagement", fn: subjectManagement },
  { name: "04-ConsentLifecycle", fn: consentLifecycle },
  { name: "05-DataMasking", fn: dataMasking },
  { name: "06-BatchOperations", fn: batchOperations },
  { name: "07-DsarCompliance", fn: dsarCompliance },
];

printHeader("cveil JS/TypeScript SDK Examples");
console.log(`  API URL:      ${API_URL}`);
console.log(`  Jurisdiction: ${JURISDICTION}`);
console.log();

let passed = 0;
let failed = 0;

const client = await createClient();

try {
  for (const example of examples) {
    try {
      await example.fn(client);
      passed++;
    } catch (err) {
      failed++;
      console.error(`  ✗ FAILED: ${example.name} — ${err}`);
    }
  }
} finally {
  client.shutdown();
}

printHeader("Results");
console.log(`  Passed: ${passed}`);
console.log(`  Failed: ${failed}`);

if (failed > 0) {
  process.exit(1);
}
