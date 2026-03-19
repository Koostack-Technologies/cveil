/**
 * Tests for the CveilClient API methods (consent, subject, governance, catalogue).
 *
 * Uses a real WASM module for the bridge but intercepts all HTTP via a mock
 * fetch that records requests and returns canned responses.
 */

import { describe, it, before, after, beforeEach, mock } from "node:test";
import assert from "node:assert/strict";
import { WasmBridge } from "../wasm-bridge.js";
import { CveilClient, CveilApiError } from "../client.js";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const __dirname = dirname(fileURLToPath(import.meta.url));
const WASM_PATH = join(__dirname, "..", "..", "wasm", "cveil_sdk_core.wasm");

// ─── Mock fetch infrastructure ──────────────────────────────────

interface CapturedRequest {
  method: string;
  url: string;
  headers: Record<string, string>;
  body: unknown;
}

let capturedRequests: CapturedRequest[] = [];
let mockResponses: Map<string, { status: number; body: unknown }> = new Map();
const originalFetch = globalThis.fetch;

function installMockFetch() {
  (globalThis as any).fetch = async (
    url: string | URL | Request,
    init?: RequestInit
  ): Promise<Response> => {
    const urlStr = typeof url === "string" ? url : url.toString();

    // Let the real fetch handle graph loading during init
    const parsedUrl = new URL(urlStr);
    if (parsedUrl.pathname === "/api/v1/consent/graph") {
      // Return empty graph page to satisfy init
      return new Response(
        JSON.stringify({
          nodes: [],
          edges: [],
          pageInfo: { nextCursor: null, hasMore: false, totalNodes: 0, totalEdges: 0 },
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }

    // Capture for test assertions
    let body: unknown = undefined;
    if (init?.body) {
      body = JSON.parse(init.body as string);
    }
    capturedRequests.push({
      method: init?.method ?? "GET",
      url: urlStr,
      headers: (init?.headers ?? {}) as Record<string, string>,
      body,
    });

    // Find matching mock response by path
    const key = `${init?.method ?? "GET"} ${parsedUrl.pathname}${parsedUrl.search}`;
    const mockResp = mockResponses.get(key);

    if (mockResp) {
      if (mockResp.status >= 400) {
        return new Response(JSON.stringify(mockResp.body), {
          status: mockResp.status,
          headers: { "Content-Type": "application/json" },
        });
      }
      return new Response(JSON.stringify(mockResp.body), {
        status: mockResp.status,
        headers: { "Content-Type": "application/json" },
      });
    }

    // Default: 200 with MessageResponse
    return new Response(
      JSON.stringify({ message: "ok" }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  };
}

function uninstallMockFetch() {
  globalThis.fetch = originalFetch;
}

// ─── Test suite ─────────────────────────────────────────────────

describe("CveilClient API Methods", () => {
  let client: CveilClient;

  before(async () => {
    installMockFetch();
    client = await CveilClient.create({
      config: {
        apiBaseUrl: "http://localhost:8080",
        apiToken: "cveil_test_token_12345",
        defaultJurisdiction: "EU",
      },
      wasmPath: WASM_PATH,
      autoConnect: false,
    });
  });

  after(() => {
    client.shutdown();
    uninstallMockFetch();
  });

  beforeEach(() => {
    capturedRequests = [];
    mockResponses = new Map();
  });

  // ─── HTTP Helper / Error Handling ───────────────────────────

  describe("error handling", () => {
    it("throws CveilApiError on 400 with structured body", async () => {
      mockResponses.set("POST /api/v1/subjects", {
        status: 400,
        body: {
          requestId: "req-123",
          error: "validation_error",
          message: "subjectName is required",
          retryable: false,
          retryAfter: null,
        },
      });

      const err = await assert.rejects(
        () => client.registerSubject({ subjectName: "" }),
        (e: unknown) => {
          assert.ok(e instanceof CveilApiError);
          assert.equal(e.status, 400);
          assert.equal(e.message, "subjectName is required");
          assert.equal(e.retryable, false);
          assert.equal(e.requestId, "req-123");
          assert.equal(e.errorCode, "validation_error");
          return true;
        }
      );
    });

    it("throws CveilApiError on 500 with retryable flag", async () => {
      mockResponses.set("GET /api/v1/subjects", {
        status: 503,
        body: {
          error: "service_unavailable",
          message: "Database is down",
          retryable: true,
          retryAfter: 5,
        },
      });

      await assert.rejects(
        () => client.listSubjects(),
        (e: unknown) => {
          assert.ok(e instanceof CveilApiError);
          assert.equal(e.status, 503);
          assert.equal(e.retryable, true);
          assert.equal(e.retryAfter, 5);
          return true;
        }
      );
    });
  });

  // ─── Consent Operations ─────────────────────────────────────

  describe("consent operations", () => {
    it("grant() sends correct request and updates cache", async () => {
      const result = await client.grant({
        subject: "user-1",
        scope: { purposeName: "Marketing", fieldName: "email", entityName: "User" },
      });
      assert.equal(result.message, "ok");
      const req = capturedRequests.find((r) => r.url.includes("/consent/grant"));
      assert.ok(req, "grant request was sent");
      assert.equal(req.method, "POST");
      assert.deepEqual((req.body as any).scope.purposeName, "Marketing");
    });

    it("revoke() sends correct request", async () => {
      const result = await client.revoke({
        subject: "user-1",
        scope: { purposeName: "Marketing", fieldName: "email", entityName: "User" },
      });
      assert.equal(result.message, "ok");
      const req = capturedRequests.find((r) => r.url.includes("/consent/revoke") && !r.url.includes("revoke-all"));
      assert.ok(req);
      assert.equal(req.method, "POST");
    });

    it("grantAll() sends batch and handles partial failures", async () => {
      mockResponses.set("POST /api/v1/subjects/consent/grant-all", {
        status: 200,
        body: {
          succeeded: 1,
          failed: 1,
          errors: [{ index: 1, scope: { purposeName: "Analytics" }, message: "purpose not found" }],
        },
      });

      const result = await client.grantAll({
        subject: "user-2",
        grants: [
          { scope: { purposeName: "Marketing", fieldName: "email", entityName: "User" } },
          { scope: { purposeName: "Analytics", fieldName: "phone", entityName: "User" } },
        ],
      });
      assert.equal(result.succeeded, 1);
      assert.equal(result.failed, 1);
      assert.equal(result.errors.length, 1);
      assert.equal(result.errors[0].index, 1);
    });

    it("revokeAll() sends batch request", async () => {
      mockResponses.set("POST /api/v1/subjects/consent/revoke-all", {
        status: 200,
        body: { succeeded: 2, failed: 0, errors: [] },
      });

      const result = await client.revokeAll({
        subject: "user-3",
        revokes: [
          { scope: { purposeName: "Marketing", fieldName: "email", entityName: "User" } },
          { scope: { purposeName: "Marketing", fieldName: "phone", entityName: "User" } },
        ],
      });
      assert.equal(result.succeeded, 2);
      assert.equal(result.failed, 0);
    });

    it("evaluateConsent() sends scopes to backend", async () => {
      mockResponses.set("POST /api/v1/subjects/user-5/evaluate", {
        status: 200,
        body: {
          subject: "user-5",
          granted: [{ purposeName: "Marketing", fieldName: "email", entityName: "User" }],
          denied: [],
          reasons: {},
          details: {},
        },
      });

      const result = await client.evaluateConsent("user-5", {
        scopes: [{ purposeName: "Marketing", fieldName: "email", entityName: "User" }],
      });
      assert.equal(result.subject, "user-5");
      assert.equal(result.granted.length, 1);
    });

    it("getSubjectSummary() returns consent view", async () => {
      mockResponses.set("GET /api/v1/subjects/user-6/summary", {
        status: 200,
        body: { subjectId: "user-6", entries: [] },
      });

      const result = await client.getSubjectSummary("user-6");
      assert.equal(result.subjectId, "user-6");
    });

    it("getSubjectHistory() returns ledger entries", async () => {
      mockResponses.set("GET /api/v1/subjects/user-7/history", {
        status: 200,
        body: {
          subjectId: "user-7",
          entries: [
            {
              consentLedgerId: 1,
              subjectId: "user-7",
              policyId: 10,
              purposeName: "Marketing",
              policyType: "FIELD",
              fieldName: "email",
              entityName: "User",
              categoryName: null,
              action: "GRANT",
              actor: "user-7",
              ipAddress: null,
              userAgent: null,
              createdAt: "2026-01-01T00:00:00Z",
              expiresAt: null,
            },
          ],
        },
      });

      const result = await client.getSubjectHistory("user-7");
      assert.equal(result.entries.length, 1);
      assert.equal(result.entries[0].action, "GRANT");
    });
  });

  // ─── Subject Operations ─────────────────────────────────────

  describe("subject operations", () => {
    it("listSubjects()", async () => {
      mockResponses.set("GET /api/v1/subjects", {
        status: 200,
        body: [
          { subjectId: 1, subjectName: "alice", description: null, createdAt: "2026-01-01T00:00:00Z", createdBy: "admin", updatedAt: "2026-01-01T00:00:00Z", updatedBy: "admin" },
        ],
      });

      const result = await client.listSubjects();
      assert.equal(result.length, 1);
      assert.equal(result[0].subjectName, "alice");
    });

    it("getSubject()", async () => {
      mockResponses.set("GET /api/v1/subjects/alice", {
        status: 200,
        body: { subjectId: 1, subjectName: "alice", description: "Test user", createdAt: "2026-01-01T00:00:00Z", createdBy: "admin", updatedAt: "2026-01-01T00:00:00Z", updatedBy: "admin" },
      });

      const result = await client.getSubject("alice");
      assert.equal(result.subjectName, "alice");
      assert.equal(result.description, "Test user");
    });

    it("registerSubject()", async () => {
      const result = await client.registerSubject({
        subjectName: "bob",
        description: "Another user",
      });
      assert.equal(result.message, "ok");
      const req = capturedRequests.find((r) => r.url.includes("/subjects") && r.method === "POST");
      assert.ok(req);
      assert.deepEqual((req.body as any).subjectName, "bob");
    });

    it("updateSubject()", async () => {
      const result = await client.updateSubject("bob", {
        description: "Updated description",
      });
      assert.equal(result.message, "ok");
      const req = capturedRequests.find((r) => r.method === "PUT");
      assert.ok(req);
      assert.ok(req.url.includes("/subjects/bob"));
    });

    it("removeSubject()", async () => {
      const result = await client.removeSubject("bob");
      assert.equal(result.message, "ok");
      const req = capturedRequests.find((r) => r.method === "DELETE" && r.url.includes("/subjects/bob"));
      assert.ok(req);
    });

    it("exportSubjectData() — GDPR Article 20", async () => {
      mockResponses.set("GET /api/v1/subjects/alice/export", {
        status: 200,
        body: {
          subject: "alice",
          consent: { subjectId: "alice", entries: [] },
          cachedKeyCount: 3,
          exportedAt: "2026-01-01T00:00:00Z",
        },
      });

      const result = await client.exportSubjectData("alice");
      assert.equal(result.subject, "alice");
      assert.equal(result.cachedKeyCount, 3);
    });

    it("deleteSubjectData() — GDPR Article 17", async () => {
      mockResponses.set("DELETE /api/v1/subjects/alice/audit?confirm=DELETE_alice", {
        status: 200,
        body: {
          subject: "alice",
          deletionRequestId: "del-001",
          ledgerEntriesRevoked: 5,
          cachedKeysDeleted: 3,
          status: "PENDING_SHRED",
          completedAt: "2026-01-01T00:00:00Z",
        },
      });

      const result = await client.deleteSubjectData("alice");
      assert.equal(result.deletionRequestId, "del-001");
      assert.equal(result.status, "PENDING_SHRED");
    });

    it("shredDsarKey()", async () => {
      const result = await client.shredDsarKey("alice");
      assert.equal(result.message, "ok");
      const req = capturedRequests.find((r) => r.url.includes("/dsar-key"));
      assert.ok(req);
      assert.equal(req.method, "DELETE");
    });
  });

  // ─── Governance Operations ──────────────────────────────────

  describe("governance operations", () => {
    it("listPurposes()", async () => {
      mockResponses.set("GET /api/v1/governance/purposes", {
        status: 200,
        body: [{ purposeId: 1, purposeName: "Marketing", description: null, regulations: [], policies: [] }],
      });
      const result = await client.listPurposes();
      assert.equal(result.length, 1);
      assert.equal(result[0].purposeName, "Marketing");
    });

    it("getPurpose()", async () => {
      mockResponses.set("GET /api/v1/governance/purposes/Marketing", {
        status: 200,
        body: { purposeId: 1, purposeName: "Marketing", description: "Email marketing", regulations: [], policies: [] },
      });
      const result = await client.getPurpose("Marketing");
      assert.equal(result.purposeName, "Marketing");
    });

    it("registerPurpose() reloads graph", async () => {
      const result = await client.registerPurpose({
        purposeName: "Analytics",
        description: "Data analytics",
      });
      assert.equal(result.message, "ok");
      const req = capturedRequests.find((r) => r.method === "POST" && r.url.includes("/governance/purposes"));
      assert.ok(req);
    });

    it("removePurpose()", async () => {
      const result = await client.removePurpose("Analytics");
      assert.equal(result.message, "ok");
    });

    it("addPurposePolicy()", async () => {
      const result = await client.addPurposePolicy({
        purposeName: "Marketing",
        policyType: "FIELD",
        fieldName: "email",
        entityName: "User",
      });
      assert.equal(result.message, "ok");
    });

    it("removePurposePolicy()", async () => {
      const result = await client.removePurposePolicy({
        purposeName: "Marketing",
        policyType: "FIELD",
        fieldName: "email",
        entityName: "User",
      });
      assert.equal(result.message, "ok");
    });

    it("linkPurposeToRegulation()", async () => {
      const result = await client.linkPurposeToRegulation({
        purposeName: "Marketing",
        regulationName: "GDPR",
        lawfulBasis: "consent",
        onGranted: "Plaintext",
        onDenied: "RedactAll",
      });
      assert.equal(result.message, "ok");
    });

    it("unlinkPurposeFromRegulation()", async () => {
      const result = await client.unlinkPurposeFromRegulation("Marketing", "GDPR");
      assert.equal(result.message, "ok");
      const req = capturedRequests.find((r) => r.method === "DELETE" && r.url.includes("/purposes/Marketing/regulations/GDPR"));
      assert.ok(req);
    });

    it("listRegulations()", async () => {
      mockResponses.set("GET /api/v1/governance/regulations", {
        status: 200,
        body: [{ regulationId: 1, regulationName: "GDPR", description: null, activeJurisdictions: [] }],
      });
      const result = await client.listRegulations();
      assert.equal(result[0].regulationName, "GDPR");
    });

    it("registerRegulation()", async () => {
      const result = await client.registerRegulation({
        regulationName: "CCPA",
        description: "California Consumer Privacy Act",
      });
      assert.equal(result.message, "ok");
    });

    it("removeRegulation()", async () => {
      const result = await client.removeRegulation("CCPA");
      assert.equal(result.message, "ok");
    });

    it("listJurisdictions()", async () => {
      mockResponses.set("GET /api/v1/governance/jurisdictions", {
        status: 200,
        body: [{ jurisdictionId: 1, jurisdictionName: "EU", description: null }],
      });
      const result = await client.listJurisdictions();
      assert.equal(result[0].jurisdictionName, "EU");
    });

    it("registerJurisdiction()", async () => {
      const result = await client.registerJurisdiction({
        jurisdictionName: "California",
        description: "US - California",
      });
      assert.equal(result.message, "ok");
    });

    it("updateJurisdiction()", async () => {
      const result = await client.updateJurisdiction("California", {
        description: "Updated",
      });
      assert.equal(result.message, "ok");
    });

    it("removeJurisdiction()", async () => {
      const result = await client.removeJurisdiction("California");
      assert.equal(result.message, "ok");
    });

    it("addJurisdictionToRegulation()", async () => {
      const result = await client.addJurisdictionToRegulation({
        regulationName: "GDPR",
        jurisdictionName: "EU",
      });
      assert.equal(result.message, "ok");
    });

    it("removeJurisdictionFromRegulation()", async () => {
      const result = await client.removeJurisdictionFromRegulation("GDPR", "EU");
      assert.equal(result.message, "ok");
      const req = capturedRequests.find((r) => r.url.includes("/regulations/GDPR/jurisdictions/EU"));
      assert.ok(req);
    });
  });

  // ─── Catalogue Operations ───────────────────────────────────

  describe("catalogue operations", () => {
    it("listFields()", async () => {
      mockResponses.set("GET /api/v1/catalogue/fields", {
        status: 200,
        body: [{ fieldId: 1, fieldName: "email", description: null, entity: { entityId: 1, entityName: "User" }, categories: [] }],
      });
      const result = await client.listFields();
      assert.equal(result[0].fieldName, "email");
    });

    it("registerField() reloads graph", async () => {
      const result = await client.registerField({
        fieldName: "ssn",
        entityName: "User",
        description: "Social Security Number",
      });
      assert.equal(result.message, "ok");
    });

    it("removeField()", async () => {
      const result = await client.removeField("User", "ssn");
      assert.equal(result.message, "ok");
      const req = capturedRequests.find((r) => r.url.includes("/catalogue/fields/User/ssn"));
      assert.ok(req);
    });

    it("listEntities()", async () => {
      mockResponses.set("GET /api/v1/catalogue/entities", {
        status: 200,
        body: [{ entityId: 1, entityName: "User", description: null }],
      });
      const result = await client.listEntities();
      assert.equal(result[0].entityName, "User");
    });

    it("getEntity()", async () => {
      mockResponses.set("GET /api/v1/catalogue/entities/User", {
        status: 200,
        body: { entityId: 1, entityName: "User", description: "A user" },
      });
      const result = await client.getEntity("User");
      assert.equal(result.entityName, "User");
    });

    it("registerEntity()", async () => {
      const result = await client.registerEntity({
        entityName: "Patient",
        description: "Healthcare patient",
      });
      assert.equal(result.message, "ok");
    });

    it("updateEntity()", async () => {
      const result = await client.updateEntity("Patient", { description: "Updated" });
      assert.equal(result.message, "ok");
    });

    it("removeEntity()", async () => {
      const result = await client.removeEntity("Patient");
      assert.equal(result.message, "ok");
    });

    it("listCategories()", async () => {
      mockResponses.set("GET /api/v1/catalogue/categories", {
        status: 200,
        body: [{ categoryId: 1, categoryName: "PII", description: null }],
      });
      const result = await client.listCategories();
      assert.equal(result[0].categoryName, "PII");
    });

    it("getCategory()", async () => {
      mockResponses.set("GET /api/v1/catalogue/categories/PII", {
        status: 200,
        body: { categoryId: 1, categoryName: "PII", description: "Personal info" },
      });
      const result = await client.getCategory("PII");
      assert.equal(result.categoryName, "PII");
    });

    it("registerCategory()", async () => {
      const result = await client.registerCategory({
        categoryName: "HEALTH",
        description: "Health data",
      });
      assert.equal(result.message, "ok");
    });

    it("updateCategory()", async () => {
      const result = await client.updateCategory("HEALTH", { description: "Updated" });
      assert.equal(result.message, "ok");
    });

    it("removeCategory()", async () => {
      const result = await client.removeCategory("HEALTH");
      assert.equal(result.message, "ok");
    });

    it("assignFieldToCategory()", async () => {
      const result = await client.assignFieldToCategory({
        fieldName: "email",
        entityName: "User",
        categoryName: "PII",
      });
      assert.equal(result.message, "ok");
    });

    it("unassignFieldFromCategory()", async () => {
      const result = await client.unassignFieldFromCategory("User", "email", "PII");
      assert.equal(result.message, "ok");
      const req = capturedRequests.find((r) => r.url.includes("/fields/User/email/categories/PII"));
      assert.ok(req);
    });
  });

  // ─── Auth Header ────────────────────────────────────────────

  describe("auth headers", () => {
    it("sends Bearer token on every request", async () => {
      await client.listSubjects();
      const req = capturedRequests[0];
      assert.equal(req.headers["Authorization"], "Bearer cveil_test_token_12345");
    });
  });
});
