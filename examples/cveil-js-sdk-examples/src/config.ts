import { CveilClient, type CveilClientOptions } from "@cveil/sdk-js";

/**
 * Shared configuration for all cveil SDK examples.
 * Reads connection settings from environment variables so examples
 * can be pointed at any cveil environment.
 *
 * Required environment variables:
 *   CVEIL_API_URL   — Base URL (default: http://localhost:8080)
 *   CVEIL_API_TOKEN — Tenant API token (no default — required)
 *
 * Optional:
 *   CVEIL_JURISDICTION — Default jurisdiction (default: EU)
 */

export const API_URL = process.env.CVEIL_API_URL || "http://localhost:8080";
export const JURISDICTION = process.env.CVEIL_JURISDICTION || "EU";

export const API_TOKEN = (() => {
  const token = process.env.CVEIL_API_TOKEN;
  if (!token) {
    throw new Error(
      "Required environment variable 'CVEIL_API_TOKEN' is not set. " +
        "Export it before running examples: export CVEIL_API_TOKEN=<value>"
    );
  }
  return token;
})();

// [START create_client]
/**
 * Creates a configured CveilClient from environment variables.
 */
export async function createClient(): Promise<CveilClient> {
  return CveilClient.create({
    config: {
      apiBaseUrl: API_URL,
      apiToken: API_TOKEN,
      defaultJurisdiction: JURISDICTION,
    },
  });
}
// [END create_client]

/** Prints a section header for readable console output. */
export function printHeader(title: string): void {
  console.log();
  console.log("═".repeat(60));
  console.log(`  ${title}`);
  console.log("═".repeat(60));
}

/** Prints a step within a section. */
export function printStep(message: string): void {
  console.log(`  → ${message}`);
}

/** Prints a success result. */
export function printResult(label: string, value: unknown): void {
  console.log(`    ✓ ${label}: ${value}`);
}

/** Prints a cleanup action. */
export function printCleanup(message: string): void {
  console.log(`  ✗ cleanup: ${message}`);
}

/**
 * Retries an async operation with exponential backoff.
 * The governance projection is eventually consistent — policies may not
 * be visible immediately after creation.  Wrap grant / grantAll calls
 * with this helper so they survive the propagation window.
 */
export async function retryWithBackoff<T>(
  fn: () => Promise<T>,
  maxAttempts = 10,
  initialDelayMs = 1000,
  maxDelayMs = 5000,
): Promise<T> {
  let delay = initialDelayMs;
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      return await fn();
    } catch (err) {
      if (attempt === maxAttempts) throw err;
      await new Promise((r) => setTimeout(r, delay));
      delay = Math.min(delay * 2, maxDelayMs);
    }
  }
  throw new Error("unreachable");
}
