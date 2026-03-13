/**
 * @cveil/sdk-js — JavaScript facade for the cveil-sdk-core WASM consent masking engine.
 *
 * @example
 * ```typescript
 * import { CveilClient } from "@cveil/sdk-js";
 *
 * const client = await CveilClient.create({
 *   config: {
 *     apiBaseUrl: "https://api.operio.example.com",
 *     apiToken: "cveil_your_token_here",
 *     defaultJurisdiction: "EU",
 *   },
 * });
 *
 * const result = await client.mask("user-123", "User", { email: "alice@example.com" }, "Marketing");
 * console.log(result.data); // { email: "***" } or plaintext depending on consent
 * ```
 */

export { CveilClient } from "./client.js";
export { WasmBridge, CveilWasmError } from "./wasm-bridge.js";
export type {
  CveilConfig,
  CveilClientOptions,
  MaskResult,
  NeedsFetchEntry,
  EvaluateResult,
  InvalidationEffect,
  SdkStatus,
  DecisionEntry,
  ConsentGraphPage,
  GraphNode,
  GraphEdge,
  GraphPageInfo,
} from "./types.js";
