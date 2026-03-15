/**
 * CveilClient — high-level async facade over the cveil-sdk-core WASM module.
 *
 * Handles:
 * - WASM module loading and initialization
 * - Paginated graph ingestion from the cveil REST API
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
  MessageResponse,
  ApiErrorBody,
  ConsentScope,
  GrantRequest,
  RevokeRequest,
  BatchGrantRequest,
  BatchRevokeRequest,
  BatchConsentResponse,
  EvaluateConsentRequest,
  EvaluateConsentResponse,
  SubjectConsentView,
  ConsentLedgerEntry,
  SubjectView,
  RegisterSubjectRequest,
  UpdateSubjectRequest,
  SubjectDataExport,
  DsarDeletionReceipt,
  PurposeView,
  RegisterPurposeRequest,
  AddPurposePolicyRequest,
  LinkPurposeRegulationRequest,
  RegulationView,
  RegisterRegulationRequest,
  JurisdictionView,
  RegisterJurisdictionRequest,
  UpdateJurisdictionRequest,
  AddJurisdictionToRegulationRequest,
  FieldView,
  RegisterFieldRequest,
  EntityView,
  RegisterEntityRequest,
  UpdateEntityRequest,
  CategoryView,
  RegisterCategoryRequest,
  UpdateCategoryRequest,
  AssignFieldCategoryRequest,
} from "./types.js";

const DEFAULT_GRAPH_PAGE_SIZE = 100;
const RECONNECT_BASE_MS = 1000;
const RECONNECT_MAX_MS = 30_000;
const RECONNECT_JITTER_MS = 500;
const SSE_BUFFER_MAX_BYTES = 1_048_576; // 1 MB safety limit

/** Error thrown when an API request fails. */
export class CveilApiError extends Error {
  readonly status: number;
  readonly retryable: boolean;
  readonly requestId: string | null;
  readonly retryAfter: number | null;
  readonly errorCode: string;

  constructor(
    status: number,
    body: ApiErrorBody | null,
    fallbackMessage?: string
  ) {
    const msg = body?.message ?? fallbackMessage ?? `HTTP ${status}`;
    super(msg);
    this.name = "CveilApiError";
    this.status = status;
    this.retryable = body?.retryable ?? status >= 500;
    this.requestId = body?.requestId ?? null;
    this.retryAfter = body?.retryAfter ?? null;
    this.errorCode = body?.error ?? "unknown";
  }
}

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
      "/api/v1/consent/events",
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

        // Safety: abort if buffer grows beyond limit (malformed stream)
        if (buffer.length > SSE_BUFFER_MAX_BYTES) {
          console.error("[cveil] SSE buffer exceeded limit, aborting stream");
          break;
        }

        // Parse SSE events from buffer
        const events = buffer.split("\n\n");
        buffer = events.pop() ?? ""; // Keep incomplete event in buffer

        for (const event of events) {
          this.processSSEEvent(event);
        }
      }

      // Flush any trailing bytes from the TextDecoder
      buffer += decoder.decode();
    } finally {
      reader.releaseLock();
    }

    // Stream ended — treat as disconnect
    this.handleSSEDisconnect();
  }

  private processSSEEvent(raw: string): void {
    let eventType = "";
    const dataLines: string[] = [];

    for (const line of raw.split("\n")) {
      if (line.startsWith("event:")) {
        eventType = line.slice(6).trim();
      } else if (line.startsWith("data:")) {
        dataLines.push(line.slice(5).trimStart());
      } else if (line.startsWith(":")) {
        // Comment line (keep-alive) — skip this line, not the entire event
        continue;
      }
    }

    const data = dataLines.join("\n");

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

    // Prevent stacking timers from rapid disconnects
    if (this.reconnectTimer) return;

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

  // ─── HTTP Helper ──────────────────────────────────────────────

  /**
   * Generic HTTP request helper with auth, JSON serialization, and error mapping.
   */
  private async httpRequest<T>(
    method: string,
    path: string,
    body?: unknown
  ): Promise<T> {
    this.ensureInitialized();
    const url = new URL(path, this.config.apiBaseUrl);

    const headers: Record<string, string> = {
      Authorization: `Bearer ${this.config.apiToken}`,
      Accept: "application/json",
    };

    const init: RequestInit = { method, headers };

    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(body);
    }

    const response = await fetch(url.toString(), init);

    if (!response.ok) {
      let errorBody: ApiErrorBody | null = null;
      try {
        errorBody = (await response.json()) as ApiErrorBody;
      } catch {
        // response body wasn't JSON
      }
      throw new CveilApiError(response.status, errorBody, response.statusText);
    }

    const text = await response.text();
    if (!text) return undefined as T;
    return JSON.parse(text) as T;
  }

  // ─── Consent Operations ───────────────────────────────────────

  /** Grant consent for a single scope. Optimistically updates the local decision cache. */
  async grant(request: GrantRequest): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "POST",
      "/api/v1/subjects/consent/grant",
      request
    );
    this.optimisticIngest(request.subject, request.scope, "GRANT", request.expiresAt);
    return result;
  }

  /** Revoke consent for a single scope. Optimistically updates the local decision cache. */
  async revoke(request: RevokeRequest): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "POST",
      "/api/v1/subjects/consent/revoke",
      request
    );
    this.optimisticIngest(request.subject, request.scope, "REVOKE");
    return result;
  }

  /** Batch grant consent (up to 1000 entries). Optimistically updates cache for succeeded entries. */
  async grantAll(request: BatchGrantRequest): Promise<BatchConsentResponse> {
    const result = await this.httpRequest<BatchConsentResponse>(
      "POST",
      "/api/v1/subjects/consent/grant-all",
      request
    );
    const failedIndices = new Set(result.errors.map((e) => e.index));
    for (let i = 0; i < request.grants.length; i++) {
      if (!failedIndices.has(i)) {
        const entry = request.grants[i];
        this.optimisticIngest(request.subject, entry.scope, "GRANT", entry.expiresAt);
      }
    }
    return result;
  }

  /** Batch revoke consent (up to 1000 entries). Optimistically updates cache for succeeded entries. */
  async revokeAll(request: BatchRevokeRequest): Promise<BatchConsentResponse> {
    const result = await this.httpRequest<BatchConsentResponse>(
      "POST",
      "/api/v1/subjects/consent/revoke-all",
      request
    );
    const failedIndices = new Set(result.errors.map((e) => e.index));
    for (let i = 0; i < request.revokes.length; i++) {
      if (!failedIndices.has(i)) {
        this.optimisticIngest(request.subject, request.revokes[i].scope, "REVOKE");
      }
    }
    return result;
  }

  /** Evaluate consent for a set of scopes against the backend. */
  async evaluateConsent(
    subject: string,
    request: EvaluateConsentRequest
  ): Promise<EvaluateConsentResponse> {
    return this.httpRequest<EvaluateConsentResponse>(
      "POST",
      `/api/v1/subjects/${encodeURIComponent(subject)}/evaluate`,
      request
    );
  }

  /** Get a subject's consent summary. */
  async getSubjectSummary(subject: string): Promise<SubjectConsentView> {
    return this.httpRequest<SubjectConsentView>(
      "GET",
      `/api/v1/subjects/${encodeURIComponent(subject)}/summary`
    );
  }

  /** Get a subject's consent history. */
  async getSubjectHistory(subject: string): Promise<ConsentLedgerEntry[]> {
    return this.httpRequest<ConsentLedgerEntry[]>(
      "GET",
      `/api/v1/subjects/${encodeURIComponent(subject)}/history`
    );
  }

  /**
   * Build a policy name from a consent scope and ingest it into the WASM decision cache.
   * This makes the next mask() call reflect the change immediately.
   */
  private optimisticIngest(
    subject: string,
    scope: ConsentScope,
    action: "GRANT" | "REVOKE",
    expiresAt?: string | null
  ): void {
    const policyName = this.scopeToPolicyName(scope);
    if (!policyName) return;

    const decisions: DecisionEntry[] = [
      {
        policyName,
        action,
        expiresAt: expiresAt ? new Date(expiresAt).getTime() : null,
      },
    ];
    const wasm = this.bridge.exports;
    this.bridge.call2(
      wasm.cveil_ingest_decisions,
      subject,
      JSON.stringify(decisions)
    );
  }

  /** Derive the policy name from a ConsentScope (mirrors backend naming). */
  private scopeToPolicyName(scope: ConsentScope): string | null {
    if (scope.fieldName && scope.entityName && scope.purposeName) {
      return `FIELD:${scope.entityName}/${scope.fieldName}:${scope.purposeName}`;
    }
    if (scope.category && scope.purposeName) {
      return `CAT:${scope.category}:${scope.purposeName}`;
    }
    return null;
  }

  // ─── Subject Operations ───────────────────────────────────────

  /** List all registered subjects. */
  async listSubjects(): Promise<SubjectView[]> {
    return this.httpRequest<SubjectView[]>("GET", "/api/v1/subjects");
  }

  /** Get a single subject by name. */
  async getSubject(name: string): Promise<SubjectView> {
    return this.httpRequest<SubjectView>(
      "GET",
      `/api/v1/subjects/${encodeURIComponent(name)}`
    );
  }

  /** Register a new subject. */
  async registerSubject(
    request: RegisterSubjectRequest
  ): Promise<MessageResponse> {
    return this.httpRequest<MessageResponse>(
      "POST",
      "/api/v1/subjects",
      request
    );
  }

  /** Update an existing subject. */
  async updateSubject(
    name: string,
    request: UpdateSubjectRequest
  ): Promise<MessageResponse> {
    return this.httpRequest<MessageResponse>(
      "PUT",
      `/api/v1/subjects/${encodeURIComponent(name)}`,
      request
    );
  }

  /** Remove a subject. */
  async removeSubject(name: string): Promise<MessageResponse> {
    return this.httpRequest<MessageResponse>(
      "DELETE",
      `/api/v1/subjects/${encodeURIComponent(name)}`
    );
  }

  /** GDPR Article 20 — export all data for a subject. */
  async exportSubjectData(subject: string): Promise<SubjectDataExport> {
    return this.httpRequest<SubjectDataExport>(
      "GET",
      `/api/v1/subjects/${encodeURIComponent(subject)}/export`
    );
  }

  /** GDPR Article 17 — request deletion of all subject data. */
  async deleteSubjectData(subject: string): Promise<DsarDeletionReceipt> {
    return this.httpRequest<DsarDeletionReceipt>(
      "DELETE",
      `/api/v1/subjects/${encodeURIComponent(subject)}/audit?confirm=DELETE_${encodeURIComponent(subject)}`
    );
  }

  /** Shred the DSAR encryption key for a subject (irreversible). */
  async shredDsarKey(name: string): Promise<MessageResponse> {
    return this.httpRequest<MessageResponse>(
      "DELETE",
      `/api/v1/subjects/${encodeURIComponent(name)}/dsar-key`
    );
  }

  // ─── Governance: Purposes ─────────────────────────────────────

  async listPurposes(): Promise<PurposeView[]> {
    return this.httpRequest<PurposeView[]>(
      "GET",
      "/api/v1/governance/purposes"
    );
  }

  async getPurpose(name: string): Promise<PurposeView> {
    return this.httpRequest<PurposeView>(
      "GET",
      `/api/v1/governance/purposes/${encodeURIComponent(name)}`
    );
  }

  async registerPurpose(
    request: RegisterPurposeRequest
  ): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "POST",
      "/api/v1/governance/purposes",
      request
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  async removePurpose(name: string): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "DELETE",
      `/api/v1/governance/purposes/${encodeURIComponent(name)}`
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  // ─── Governance: Purpose Policies ─────────────────────────────

  async addPurposePolicy(
    request: AddPurposePolicyRequest
  ): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "POST",
      "/api/v1/governance/purposes/policies",
      request
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  async removePurposePolicy(
    request: AddPurposePolicyRequest
  ): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "DELETE",
      "/api/v1/governance/purposes/policies",
      request
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  // ─── Governance: Purpose ↔ Regulation Links ───────────────────

  async linkPurposeToRegulation(
    request: LinkPurposeRegulationRequest
  ): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "POST",
      "/api/v1/governance/purposes/regulations",
      request
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  async unlinkPurposeFromRegulation(
    purposeName: string,
    regulationName: string
  ): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "DELETE",
      `/api/v1/governance/purposes/${encodeURIComponent(purposeName)}/regulations/${encodeURIComponent(regulationName)}`
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  // ─── Governance: Regulations ──────────────────────────────────

  async listRegulations(): Promise<RegulationView[]> {
    return this.httpRequest<RegulationView[]>(
      "GET",
      "/api/v1/governance/regulations"
    );
  }

  async getRegulation(name: string): Promise<RegulationView> {
    return this.httpRequest<RegulationView>(
      "GET",
      `/api/v1/governance/regulations/${encodeURIComponent(name)}`
    );
  }

  async registerRegulation(
    request: RegisterRegulationRequest
  ): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "POST",
      "/api/v1/governance/regulations",
      request
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  async removeRegulation(name: string): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "DELETE",
      `/api/v1/governance/regulations/${encodeURIComponent(name)}`
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  // ─── Governance: Jurisdictions ────────────────────────────────

  async listJurisdictions(): Promise<JurisdictionView[]> {
    return this.httpRequest<JurisdictionView[]>(
      "GET",
      "/api/v1/governance/jurisdictions"
    );
  }

  async getJurisdiction(name: string): Promise<JurisdictionView> {
    return this.httpRequest<JurisdictionView>(
      "GET",
      `/api/v1/governance/jurisdictions/${encodeURIComponent(name)}`
    );
  }

  async registerJurisdiction(
    request: RegisterJurisdictionRequest
  ): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "POST",
      "/api/v1/governance/jurisdictions",
      request
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  async updateJurisdiction(
    name: string,
    request: UpdateJurisdictionRequest
  ): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "PUT",
      `/api/v1/governance/jurisdictions/${encodeURIComponent(name)}`,
      request
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  async removeJurisdiction(name: string): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "DELETE",
      `/api/v1/governance/jurisdictions/${encodeURIComponent(name)}`
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  // ─── Governance: Regulation ↔ Jurisdiction Links ──────────────

  async addJurisdictionToRegulation(
    request: AddJurisdictionToRegulationRequest
  ): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "POST",
      "/api/v1/governance/regulations/jurisdictions",
      request
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  async removeJurisdictionFromRegulation(
    regulationName: string,
    jurisdictionName: string
  ): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "DELETE",
      `/api/v1/governance/regulations/${encodeURIComponent(regulationName)}/jurisdictions/${encodeURIComponent(jurisdictionName)}`
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  // ─── Catalogue: Fields ────────────────────────────────────────

  async listFields(): Promise<FieldView[]> {
    return this.httpRequest<FieldView[]>("GET", "/api/v1/catalogue/fields");
  }

  async registerField(
    request: RegisterFieldRequest
  ): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "POST",
      "/api/v1/catalogue/fields",
      request
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  async removeField(
    entityName: string,
    fieldName: string
  ): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "DELETE",
      `/api/v1/catalogue/fields/${encodeURIComponent(entityName)}/${encodeURIComponent(fieldName)}`
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  // ─── Catalogue: Entities ──────────────────────────────────────

  async listEntities(): Promise<EntityView[]> {
    return this.httpRequest<EntityView[]>(
      "GET",
      "/api/v1/catalogue/entities"
    );
  }

  async getEntity(name: string): Promise<EntityView> {
    return this.httpRequest<EntityView>(
      "GET",
      `/api/v1/catalogue/entities/${encodeURIComponent(name)}`
    );
  }

  async registerEntity(
    request: RegisterEntityRequest
  ): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "POST",
      "/api/v1/catalogue/entities",
      request
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  async updateEntity(
    name: string,
    request: UpdateEntityRequest
  ): Promise<MessageResponse> {
    return this.httpRequest<MessageResponse>(
      "PUT",
      `/api/v1/catalogue/entities/${encodeURIComponent(name)}`,
      request
    );
  }

  async removeEntity(name: string): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "DELETE",
      `/api/v1/catalogue/entities/${encodeURIComponent(name)}`
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  // ─── Catalogue: Categories ────────────────────────────────────

  async listCategories(): Promise<CategoryView[]> {
    return this.httpRequest<CategoryView[]>(
      "GET",
      "/api/v1/catalogue/categories"
    );
  }

  async getCategory(name: string): Promise<CategoryView> {
    return this.httpRequest<CategoryView>(
      "GET",
      `/api/v1/catalogue/categories/${encodeURIComponent(name)}`
    );
  }

  async registerCategory(
    request: RegisterCategoryRequest
  ): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "POST",
      "/api/v1/catalogue/categories",
      request
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  async updateCategory(
    name: string,
    request: UpdateCategoryRequest
  ): Promise<MessageResponse> {
    return this.httpRequest<MessageResponse>(
      "PUT",
      `/api/v1/catalogue/categories/${encodeURIComponent(name)}`,
      request
    );
  }

  async removeCategory(name: string): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "DELETE",
      `/api/v1/catalogue/categories/${encodeURIComponent(name)}`
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  // ─── Catalogue: Field ↔ Category Links ────────────────────────

  async assignFieldToCategory(
    request: AssignFieldCategoryRequest
  ): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "POST",
      "/api/v1/catalogue/fields/categories",
      request
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  async unassignFieldFromCategory(
    entityName: string,
    fieldName: string,
    categoryName: string
  ): Promise<MessageResponse> {
    const result = await this.httpRequest<MessageResponse>(
      "DELETE",
      `/api/v1/catalogue/fields/${encodeURIComponent(entityName)}/${encodeURIComponent(fieldName)}/categories/${encodeURIComponent(categoryName)}`
    );
    await this.reloadGraphAfterMutation();
    return result;
  }

  // ─── Graph Reload Helper ──────────────────────────────────────

  /**
   * Reload the full consent graph after a governance/catalogue mutation.
   * Swallows errors to avoid failing the mutation response — the SSE stream
   * will pick up the change eventually.
   */
  private async reloadGraphAfterMutation(): Promise<void> {
    try {
      await this.loadGraph();
    } catch (err) {
      console.error("[cveil] Graph reload after mutation failed:", err);
    }
  }
}
