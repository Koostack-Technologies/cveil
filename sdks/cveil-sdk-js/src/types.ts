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

// ─── Common Response ────────────────────────────────────────────

/** Generic message response returned by most mutation endpoints. */
export interface MessageResponse {
  message: string;
}

/** Structured error response from the operio API. */
export interface ApiErrorBody {
  requestId?: string | null;
  error: string;
  message: string;
  retryable: boolean;
  retryAfter?: number | null;
}

// ─── Consent DTOs ───────────────────────────────────────────────

/** A consent scope targeting a specific field, category, or wildcard. */
export interface ConsentScope {
  category?: string;
  fieldName?: string;
  entityName?: string;
  purposeName?: string;
}

/** Grant consent for a single scope. */
export interface GrantRequest {
  subject: string;
  scope: ConsentScope;
  expiresAt?: string | null;
}

/** Revoke consent for a single scope. */
export interface RevokeRequest {
  subject: string;
  scope: ConsentScope;
}

/** Entry in a batch grant/revoke request. */
export interface BatchConsentEntry {
  scope: ConsentScope;
  expiresAt?: string | null;
}

/** Batch grant consent (up to 1000 entries). */
export interface BatchGrantRequest {
  subject: string;
  grants: BatchConsentEntry[];
}

/** Batch revoke consent (up to 1000 entries). */
export interface BatchRevokeRequest {
  subject: string;
  revokes: BatchConsentEntry[];
}

/** Result of a batch consent operation. */
export interface BatchConsentResponse {
  succeeded: number;
  failed: number;
  errors: BatchItemError[];
}

export interface BatchItemError {
  index: number;
  scope: ConsentScope;
  message: string;
}

/** Evaluate consent for a set of scopes (max 100). */
export interface EvaluateConsentRequest {
  scopes: ConsentScope[];
}

/** Detailed evaluation result per scope. */
export interface DecisionDetail {
  effectiveStatus: "GRANTED" | "REVOKED" | null;
  resolvedBy: "FIELD" | "CATEGORY" | "NONE";
  expiresAt?: string | null;
}

/** Full evaluation response. */
export interface EvaluateConsentResponse {
  subject: string;
  granted: ConsentScope[];
  denied: ConsentScope[];
  reasons: Record<string, string>;
  details: Record<string, DecisionDetail>;
}

// ─── Subject DTOs ───────────────────────────────────────────────

export interface SubjectView {
  subjectId: number;
  subjectName: string;
  description: string | null;
  createdAt: string;
  createdBy: string;
  updatedAt: string;
  updatedBy: string;
}

export interface RegisterSubjectRequest {
  subjectName: string;
  description?: string;
}

export interface UpdateSubjectRequest {
  description: string;
}

export interface ConsentLedgerEntry {
  consentLedgerId: number;
  subjectId: string;
  policyId: number;
  purposeName: string;
  policyType: string;
  fieldName: string | null;
  entityName: string | null;
  categoryName: string | null;
  action: "GRANT" | "REVOKE" | "EXPIRE";
  actor: string;
  ipAddress: string | null;
  userAgent: string | null;
  createdAt: string;
  expiresAt: string | null;
}

export interface SubjectConsentView {
  subjectId: string;
  entries: ConsentLedgerEntry[];
}

export interface SubjectDataExport {
  subject: string;
  consent: SubjectConsentView;
  cachedKeyCount: number;
  exportedAt: string;
}

export interface DsarDeletionReceipt {
  subject: string;
  deletionRequestId: string;
  ledgerEntriesRevoked: number;
  cachedKeysDeleted: number;
  status: "PENDING_SHRED" | "PARTIAL_FAILURE";
  completedAt: string;
}

// ─── Governance DTOs ────────────────────────────────────────────

export interface RegisterPurposeRequest {
  purposeName: string;
  description?: string;
}

export interface PurposeRegulationLink {
  regulationName: string;
  lawfulBasis: string | null;
  onGranted: string | null;
  onDenied: string | null;
}

export interface PurposePolicyView {
  policyId: number;
  policyType: string;
  category: { categoryId: number; categoryName: string } | null;
  field: { fieldId: number; fieldName: string; entityName: string } | null;
}

export interface PurposeView {
  purposeId: number;
  purposeName: string;
  description: string | null;
  regulations: PurposeRegulationLink[];
  policies: PurposePolicyView[];
}

export interface RegisterRegulationRequest {
  regulationName: string;
  description?: string;
}

export interface RegulationJurisdictionLink {
  jurisdictionId: number;
  jurisdictionName: string;
}

export interface RegulationView {
  regulationId: number;
  regulationName: string;
  description: string | null;
  activeJurisdictions: RegulationJurisdictionLink[];
}

export interface RegisterJurisdictionRequest {
  jurisdictionName: string;
  description?: string;
}

export interface UpdateJurisdictionRequest {
  description: string;
}

export interface JurisdictionView {
  jurisdictionId: number;
  jurisdictionName: string;
  description: string | null;
}

export interface LinkPurposeRegulationRequest {
  purposeName: string;
  regulationName: string;
  lawfulBasis?: string;
  onGranted?: string;
  onDenied?: string;
}

export interface AddPurposePolicyRequest {
  purposeName: string;
  policyType: string;
  fieldName?: string;
  entityName?: string;
  categoryName?: string;
}

export interface AddJurisdictionToRegulationRequest {
  regulationName: string;
  jurisdictionName: string;
}

// ─── Catalogue DTOs ─────────────────────────────────────────────

export interface FieldView {
  fieldId: number;
  fieldName: string;
  description: string | null;
  entity: { entityId: number; entityName: string };
  categories: { categoryId: number; categoryName: string }[];
}

export interface RegisterFieldRequest {
  fieldName: string;
  entityName: string;
  description?: string;
  entityDescription?: string;
}

export interface EntityView {
  entityId: number;
  entityName: string;
  description: string | null;
}

export interface RegisterEntityRequest {
  entityName: string;
  description?: string;
}

export interface UpdateEntityRequest {
  description: string;
}

export interface CategoryView {
  categoryId: number;
  categoryName: string;
  description: string | null;
}

export interface RegisterCategoryRequest {
  categoryName: string;
  description?: string;
}

export interface UpdateCategoryRequest {
  description: string;
}

export interface AssignFieldCategoryRequest {
  fieldName: string;
  entityName: string;
  categoryName: string;
  categoryDescription?: string;
}
