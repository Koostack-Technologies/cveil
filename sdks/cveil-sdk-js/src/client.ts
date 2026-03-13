/**
 * CveilClient — high-level async facade over the cveil-sdk-core WASM module.
 *
 * Handles:
 * - WASM module loading and initialization
 * - Paginated graph ingestion from the operio REST API
 * - SSE connection for real-time cache invalidation
 * - Automatic reconnection with exponential backoff
 * - Consent decision fetching on cache miss
 */

import { WasmBridge } from "./wasm-bridge.js";
import type {
  CveilConfig,
  CveilClientOptions,
  MaskResult,
  EvaluateResult,
  InvalidationEffect,
  SdkStatus,
  DecisionEntry,
  ConsentGraphPage,
} from "./types.js";

const DEFAULT_GRAPH_PAGE_SIZE = 100;
const RECONNECT_BASE_MS = 1000;
const RECONNECT_MAX_MS = 30_000;
const RECONNECT_JITTER_MS = 500;

export class CveilClient {
  private bridge!: WasmBridge;
  private config: CveilConfig;
  private options: CveilClientOptions;
  private abortController: AbortController | null = null;
  private reconnectDelay = RECONNECT_BASE_MS;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private _initialized = false;

  private constructor(options: CveilClientOptions) {
    this.config = options.config;
    this.options = options;
  }

  /**
   * Create and initialize a CveilClient.
   *
   * 1. Loads the WASM module
   * 2. Initializes the SDK with config
   * 3. Fetches the full consent graph (paginated)
   * 4. Connects to SSE for invalidation events (if autoConnect)
   */
  static async create(options: CveilClientOptions): Promise<CveilClient> {
    const client = new CveilClient(options);
    await client.initialize();
    return client;
  }

  private async initialize(): Promise<void> {
    const wasmPath = this.resolveWasmPath();
    this.bridge = await WasmBridge.load(wasmPath);

    // Initialize WASM core
    const wasm = this.bridge.exports;
    this.bridge.call1(wasm.cveil_init, JSON.stringify(this.config));
    this._initialized = true;

    // Load full graph
    await this.loadGraph();

    // Connect SSE
    if (this.options.autoConnect !== false) {
      this.connectSSE();
    }
  }

  private resolveWasmPath(): string {
    if (this.options.wasmPath) return this.options.wasmPath;
    // Default: look relative to the package
    const { fileURLToPath } = await_import("node:url");
    const { dirname, join } = await_import("node:path");
    // Fallback to common locations
    return new URL("../wasm/cveil_sdk_core.wasm", import.meta.url).pathname;
  }

  // ─── Graph Loading ─────────────────────────────────────────────

  /** Fetch and ingest the full consent graph via paginated API calls. */
  async loadGraph(): Promise<void> {
    const pageSize = this.options.graphPageSize ?? DEFAULT_GRAPH_PAGE_SIZE;
    let cursor: string | null = null;
    let hasMore = true;

    while (hasMore) {
      const url = new URL(
        "/api/v1/consent/graph",
        this.config.apiBaseUrl
      );
      url.searchParams.set("limit", String(pageSize));
      if (cursor) url.searchParams.set("cursor", cursor);

      const response = await fetch(url.toString(), {
        headers: {
          Authorization: `Bearer ${this.config.apiToken}`,
          Accept: "application/json",
        },
      });

      if (!response.ok) {
        throw new Error(
          `Failed to fetch consent graph: ${response.status} ${response.statusText}`
        );
      }

      const page: ConsentGraphPage = await response.json() as ConsentGraphPage;
      const wasm = this.bridge.exports;
      const result = JSON.parse(
        this.bridge.call1(wasm.cveil_ingest_graph_page, JSON.stringify(page))
      );

      hasMore = result.hasMore;
      cursor = page.pageInfo.nextCursor;
    }
  }

  // ─── Core Operations ──────────────────────────────────────────

  /**
   * Mask a JSON object based on consent graph evaluation.
   *
   * @param subject - The data subject identifier (e.g., user ID)
   * @param entity - The entity type (e.g., "User")
   * @param data - The JSON object to mask
   * @param purpose - The processing purpose (e.g., "Marketing")
   * @returns The masked data and any fields that need API fetching
   */
  async mask(
    subject: string,
    entity: string,
    data: Record<string, unknown>,
    purpose: string
  ): Promise<MaskResult> {
    this.ensureInitialized();
    const wasm = this.bridge.exports;
    const resultJson = this.bridge.call4(
      wasm.cveil_mask,
      subject,
      entity,
      JSON.stringify(data),
      purpose
    );
    const result: MaskResult = JSON.parse(resultJson);

    // Auto-fetch missing decisions and re-mask
    if (result.needsFetch.length > 0) {
      await this.fetchAndIngestDecisions(subject, result.needsFetch);
      const retryJson = this.bridge.call4(
        wasm.cveil_mask,
        subject,
        entity,
        JSON.stringify(data),
        purpose
      );
      return JSON.parse(retryJson);
    }

    return result;
  }

  /**
   * Mask with an explicit jurisdiction override.
   */
  async maskWithJurisdiction(
    subject: string,
    entity: string,
    data: Record<string, unknown>,
    purpose: string,
    jurisdiction: string
  ): Promise<MaskResult> {
    this.ensureInitialized();
    const wasm = this.bridge.exports;
    const resultJson = this.bridge.call5(
      wasm.cveil_mask_with_jurisdiction,
      subject,
      entity,
      JSON.stringify(data),
      purpose,
      jurisdiction
    );
    const result: MaskResult = JSON.parse(resultJson);

    if (result.needsFetch.length > 0) {
      await this.fetchAndIngestDecisions(subject, result.needsFetch);
      const retryJson = this.bridge.call5(
        wasm.cveil_mask_with_jurisdiction,
        subject,
        entity,
        JSON.stringify(data),
        purpose,
        jurisdiction
      );
      return JSON.parse(retryJson);
    }

    return result;
  }

  /**
   * Mask an array of objects (same subject/entity/purpose).
   */
  async maskBatch(
    subject: string,
    entity: string,
    data: Record<string, unknown>[],
    purpose: string
  ): Promise<MaskResult[]> {
    this.ensureInitialized();
    const wasm = this.bridge.exports;
    const resultJson = this.bridge.call4(
      wasm.cveil_mask_batch,
      subject,
      entity,
      JSON.stringify(data),
      purpose
    );
    return JSON.parse(resultJson);
  }

  /**
   * Evaluate consent for specific fields without masking.
   */
  evaluate(
    subject: string,
    entity: string,
    fields: string[],
    purpose: string
  ): EvaluateResult[] {
    this.ensureInitialized();
    const wasm = this.bridge.exports;
    const resultJson = this.bridge.call4(
      wasm.cveil_evaluate,
      subject,
      entity,
      JSON.stringify(fields),
      purpose
    );
    return JSON.parse(resultJson);
  }

  /**
   * Manually ingest consent decisions (e.g., from your own API call).
   */
  ingestDecisions(subject: string, decisions: DecisionEntry[]): void {
    this.ensureInitialized();
    const wasm = this.bridge.exports;
    this.bridge.call2(
      wasm.cveil_ingest_decisions,
      subject,
      JSON.stringify(decisions)
    );
  }

  /** Get SDK status. */
  status(): SdkStatus {
    this.ensureInitialized();
    const wasm = this.bridge.exports;
    return JSON.parse(this.bridge.call0(wasm.cveil_status));
  }

  /** Graceful shutdown — flush caches and disconnect SSE. */
  shutdown(): void {
    this.disconnectSSE();
    if (this._initialized) {
      const wasm = this.bridge.exports;
      this.bridge.call0(wasm.cveil_shutdown);
      this._initialized = false;
    }
  }

  get initialized(): boolean {
    return this._initialized;
  }

  // ─── Decision Fetching ────────────────────────────────────────

  private async fetchAndIngestDecisions(
    subject: string,
    needsFetch: { fieldPath: string; policyNames: string[] }[]
  ): Promise<void> {
    // Collect all unique policy names
    const allPolicies = new Set<string>();
    for (const entry of needsFetch) {
      for (const pn of entry.policyNames) {
        allPolicies.add(pn);
      }
    }

    // Call the evaluate API
    const url = new URL(
      `/api/v1/subjects/${encodeURIComponent(subject)}/evaluate`,
      this.config.apiBaseUrl
    );

    const scopes = Array.from(allPolicies).map((policyName) => ({
      policyName,
    }));

    const response = await fetch(url.toString(), {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.apiToken}`,
        "Content-Type": "application/json",
        Accept: "application/json",
      },
      body: JSON.stringify({ scopes }),
    });

    if (!response.ok) {
      console.error(
        `[cveil] Failed to evaluate consent: ${response.status} ${response.statusText}`
      );
      return;
    }

    const decisions: DecisionEntry[] = await response.json() as DecisionEntry[];
    const wasm = this.bridge.exports;
    this.bridge.call2(
      wasm.cveil_ingest_decisions,
      subject,
      JSON.stringify(decisions)
    );
  }

  // ─── SSE Connection ───────────────────────────────────────────

  /** Connect to the SSE invalidation stream. */
  connectSSE(): void {
    this.disconnectSSE();
    this.abortController = new AbortController();
    this.reconnectDelay = RECONNECT_BASE_MS;
    this.doConnectSSE();
  }

  /** Disconnect from the SSE stream. */
  disconnectSSE(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.abortController) {
      this.abortController.abort();
      this.abortController = null;
    }
  }

  private async doConnectSSE(): Promise<void> {
    if (!this.abortController) return;

    const url = new URL(
      "/api/v1/events/invalidations",
      this.config.apiBaseUrl
    );

    try {
      const response = await fetch(url.toString(), {
        headers: {
          Authorization: `Bearer ${this.config.apiToken}`,
          Accept: "text/event-stream",
        },
        signal: this.abortController.signal,
      });

      if (!response.ok) {
        throw new Error(`SSE connect failed: ${response.status}`);
      }

      if (!response.body) {
        throw new Error("SSE response has no body");
      }

      // Connected
      const wasm = this.bridge.exports;
      this.bridge.call0(wasm.cveil_sse_connected);
      this.reconnectDelay = RECONNECT_BASE_MS;

      // Parse SSE stream
      await this.consumeSSEStream(response.body);
    } catch (err: unknown) {
      if (err instanceof Error && err.name === "AbortError") {
        return; // Intentional disconnect
      }
      console.error("[cveil] SSE connection error:", err);
      this.handleSSEDisconnect();
    }
  }

  private async consumeSSEStream(
    body: ReadableStream<Uint8Array>
  ): Promise<void> {
    const decoder = new TextDecoder();
    const reader = body.getReader();
    let buffer = "";

    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });

        // Parse SSE events from buffer
        const events = buffer.split("\n\n");
        buffer = events.pop() ?? ""; // Keep incomplete event in buffer

        for (const event of events) {
          this.processSSEEvent(event);
        }
      }
    } finally {
      reader.releaseLock();
    }

    // Stream ended — treat as disconnect
    this.handleSSEDisconnect();
  }

  private processSSEEvent(raw: string): void {
    let eventType = "";
    let data = "";

    for (const line of raw.split("\n")) {
      if (line.startsWith("event:")) {
        eventType = line.slice(6).trim();
      } else if (line.startsWith("data:")) {
        data += line.slice(5).trim();
      } else if (line.startsWith(":")) {
        // Comment (keep-alive), ignore
        return;
      }
    }

    if (eventType === "cache-invalidation" && data) {
      try {
        const wasm = this.bridge.exports;
        const resultJson = this.bridge.call1(
          wasm.cveil_process_invalidation,
          data
        );
        const effect: InvalidationEffect = JSON.parse(resultJson);

        if (
          effect.effect === "refresh_full_graph" ||
          effect.effect === "flush_and_reload"
        ) {
          // Reload graph in background
          this.loadGraph().catch((err) => {
            console.error("[cveil] Failed to reload graph:", err);
          });
        } else if (
          effect.effect === "refresh_subgraph" &&
          effect.nodeType &&
          effect.nodeName
        ) {
          // Partial reload
          this.loadSubgraph(effect.nodeType, effect.nodeName).catch((err) => {
            console.error("[cveil] Failed to reload subgraph:", err);
          });
        }
      } catch (err) {
        console.error("[cveil] Failed to process invalidation:", err);
      }
    }
  }

  private async loadSubgraph(
    rootType: string,
    rootName: string
  ): Promise<void> {
    const url = new URL(
      "/api/v1/consent/graph",
      this.config.apiBaseUrl
    );
    url.searchParams.set("rootType", rootType);
    url.searchParams.set("rootName", rootName);
    url.searchParams.set("depth", "3");
    url.searchParams.set("limit", "200");

    const response = await fetch(url.toString(), {
      headers: {
        Authorization: `Bearer ${this.config.apiToken}`,
        Accept: "application/json",
      },
    });

    if (!response.ok) {
      throw new Error(`Subgraph fetch failed: ${response.status}`);
    }

    const page: ConsentGraphPage = await response.json() as ConsentGraphPage;
    const wasm = this.bridge.exports;
    this.bridge.call1(wasm.cveil_ingest_graph_page, JSON.stringify(page));
  }

  private handleSSEDisconnect(): void {
    if (!this.abortController) return; // Already shutting down

    const wasm = this.bridge.exports;
    this.bridge.call0(wasm.cveil_sse_disconnected);

    // Exponential backoff with jitter
    const jitter = Math.random() * RECONNECT_JITTER_MS;
    const delay = this.reconnectDelay + jitter;

    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      // Signal reconnect (flushes cache)
      this.bridge.call0(wasm.cveil_sse_reconnected);
      // Reload graph then reconnect SSE
      this.loadGraph()
        .then(() => this.doConnectSSE())
        .catch((err) => {
          console.error("[cveil] Reconnect graph load failed:", err);
          this.handleSSEDisconnect(); // Try again with increased backoff
        });
    }, delay);

    this.reconnectDelay = Math.min(
      this.reconnectDelay * 2,
      RECONNECT_MAX_MS
    );
  }

  private ensureInitialized(): void {
    if (!this._initialized) {
      throw new Error("CveilClient not initialized — call CveilClient.create()");
    }
  }
}

/** Helper to avoid top-level await for dynamic imports. */
function await_import(mod: string): any {
  // These are sync requires that work in Node.js ESM
  switch (mod) {
    case "node:url":
      return { fileURLToPath: (u: string) => new URL(u).pathname };
    case "node:path":
      return {
        dirname: (p: string) => p.split("/").slice(0, -1).join("/"),
        join: (...parts: string[]) => parts.join("/"),
      };
    default:
      throw new Error(`Unknown module: ${mod}`);
  }
}
