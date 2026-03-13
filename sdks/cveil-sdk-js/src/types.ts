/**
 * TypeScript type definitions for the cveil-sdk-js facade.
 */

/** SDK configuration — matches the Rust SdkConfig (camelCase JSON). */
export interface CveilConfig {
  /** Base URL of the operio REST API (e.g., "https://api.operio.example.com"). */
  apiBaseUrl: string;
  /** API token — must start with "cveil_". */
  apiToken: string;
  /** Default jurisdiction for masking resolution (e.g., "EU", "California"). */
  defaultJurisdiction: string;
  /** Behavior when SSE is disconnected beyond threshold. Default: "conservativeMask". */
  staleMode?: "apiFallback" | "conservativeMask";
  /** Milliseconds before stale mode kicks in. Default: 60000. */
  disconnectThresholdMs?: number;
  /** Max cached consent decisions. Default: 10000. */
  decisionCacheMaxSize?: number;
  /** TTL for cached decisions in ms. Default: 300000. */
  decisionCacheTtlMs?: number;
  /** Dedup ring buffer size. Default: 500. */
  dedupWindowSize?: number;
}

/** Result of a mask() call. */
export interface MaskResult {
  /** The masked JSON payload. */
  data: Record<string, unknown>;
  /** Fields that need consent decisions fetched from the API. */
  needsFetch: NeedsFetchEntry[];
}

export interface NeedsFetchEntry {
  fieldPath: string;
  policyNames: string[];
}

/** Result of an evaluate() call — per-field masking resolution. */
export interface EvaluateResult {
  field: string;
  masking_type: string;
  consent_action?: string;
  needs_fetch: boolean;
  policy_names?: string[];
}

/** Invalidation processing result. */
export interface InvalidationEffect {
  effect: "none" | "refresh_full_graph" | "refresh_subgraph" | "flush_and_reload" | "duplicate";
  nodeType?: string;
  nodeName?: string;
}

/** SDK status. */
export interface SdkStatus {
  initialized: boolean;
  connectionState: "Connected" | "Disconnected" | "Stale";
  graphNodes: number;
  cachedDecisions: number;
  decisionCacheCapacity: number;
}

/** Decision entry for ingesting consent results. */
export interface DecisionEntry {
  policyName: string;
  action: "GRANT" | "REVOKE" | "EXPIRE";
  expiresAt?: number | null;
}

/** Consent graph page from the API. */
export interface ConsentGraphPage {
  nodes: GraphNode[];
  edges: GraphEdge[];
  pageInfo: GraphPageInfo;
}

export interface GraphNode {
  type: string;
  name: string;
  properties: Record<string, string>;
}

export interface GraphEdge {
  sourceType: string;
  sourceName: string;
  targetType: string;
  targetName: string;
  properties: Record<string, string>;
}

export interface GraphPageInfo {
  nextCursor: string | null;
  hasMore: boolean;
  totalNodes: number;
  totalEdges: number;
}

/** Options for CveilClient constructor. */
export interface CveilClientOptions {
  config: CveilConfig;
  /** Path to the WASM file. If not provided, looks in standard locations. */
  wasmPath?: string;
  /** Whether to auto-connect SSE on initialization. Default: true. */
  autoConnect?: boolean;
  /** Graph page size for initial load. Default: 100. */
  graphPageSize?: number;
}
