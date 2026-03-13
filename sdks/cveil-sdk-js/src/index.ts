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
export { CveilApiError } from "./client.js";
export { WasmBridge, CveilWasmError } from "./wasm-bridge.js";
export type {
  // Core types
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
  // Common
  MessageResponse,
  ApiErrorBody,
  // Consent
  ConsentScope,
  GrantRequest,
  RevokeRequest,
  BatchConsentEntry,
  BatchGrantRequest,
  BatchRevokeRequest,
  BatchConsentResponse,
  BatchItemError,
  EvaluateConsentRequest,
  EvaluateConsentResponse,
  DecisionDetail,
  // Subject
  SubjectView,
  RegisterSubjectRequest,
  UpdateSubjectRequest,
  SubjectConsentView,
  ConsentLedgerEntry,
  SubjectDataExport,
  DsarDeletionReceipt,
  // Governance
  PurposeView,
  PurposeRegulationLink,
  PurposePolicyView,
  RegisterPurposeRequest,
  RegulationView,
  RegulationJurisdictionLink,
  RegisterRegulationRequest,
  JurisdictionView,
  RegisterJurisdictionRequest,
  UpdateJurisdictionRequest,
  LinkPurposeRegulationRequest,
  AddPurposePolicyRequest,
  AddJurisdictionToRegulationRequest,
  // Catalogue
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
