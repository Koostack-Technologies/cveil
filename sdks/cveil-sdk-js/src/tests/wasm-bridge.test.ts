/**
 * Integration tests for the WASM bridge — loads the real WASM module
 * and exercises init, graph ingestion, masking, evaluation, invalidation,
 * and SSE lifecycle through the low-level bridge API.
 */

import { describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import { WasmBridge, CveilWasmError } from "../wasm-bridge.js";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const __dirname = dirname(fileURLToPath(import.meta.url));
const WASM_PATH = join(__dirname, "..", "..", "wasm", "cveil_sdk_core.wasm");

const TEST_CONFIG = JSON.stringify({
  apiBaseUrl: "http://localhost:8080",
  apiToken: "cveil_test_token_12345",
  defaultJurisdiction: "EU",
  staleMode: "conservativeMask",
  disconnectThresholdMs: 60000,
  decisionCacheMaxSize: 100,
  decisionCacheTtlMs: 300000,
  dedupWindowSize: 500,
});

const TEST_GRAPH_PAGE = JSON.stringify({
  nodes: [
    { type: "entity", name: "User", properties: {} },
    { type: "field", name: "User/email", properties: {} },
    { type: "field", name: "User/phone", properties: {} },
    { type: "category", name: "PII", properties: {} },
    { type: "purpose", name: "Marketing", properties: {} },
    { type: "regulation", name: "GDPR", properties: {} },
    { type: "jurisdiction", name: "EU", properties: {} },
    {
      type: "policy",
      name: "FIELD:User/email:Marketing",
      properties: { policyType: "FIELD" },
    },
    {
      type: "policy",
      name: "CAT:PII:Marketing",
      properties: { policyType: "CATEGORY" },
    },
  ],
  edges: [
    {
      sourceType: "field",
      sourceName: "User/email",
      targetType: "category",
      targetName: "PII",
      properties: {},
    },
    {
      sourceType: "field",
      sourceName: "User/phone",
      targetType: "category",
      targetName: "PII",
      properties: {},
    },
    {
      sourceType: "purpose",
      sourceName: "Marketing",
      targetType: "regulation",
      targetName: "GDPR",
      properties: {
        lawfulBasis: "consent",
        onGranted: "PLAINTEXT",
        onDenied: "REDACT_ALL",
      },
    },
    {
      sourceType: "regulation",
      sourceName: "GDPR",
      targetType: "jurisdiction",
      targetName: "EU",
      properties: {},
    },
    {
      sourceType: "purpose",
      sourceName: "Marketing",
      targetType: "policy",
      targetName: "FIELD:User/email:Marketing",
      properties: {},
    },
    {
      sourceType: "purpose",
      sourceName: "Marketing",
      targetType: "policy",
      targetName: "CAT:PII:Marketing",
      properties: {},
    },
  ],
  pageInfo: {
    nextCursor: null,
    hasMore: false,
    totalNodes: 9,
    totalEdges: 6,
  },
});

describe("WasmBridge", () => {
  let bridge: WasmBridge;

  before(async () => {
    bridge = await WasmBridge.load(WASM_PATH);
  });

  after(() => {
    try {
      bridge.call0(bridge.exports.cveil_shutdown);
    } catch {
      // ignore if not initialized
    }
  });

  it("loads WASM module successfully", () => {
    assert.ok(bridge);
    assert.ok(bridge.exports.cveil_init);
    assert.ok(bridge.exports.cveil_mask);
    assert.ok(bridge.exports.cveil_status);
  });

  it("initializes with valid config", () => {
    const result = JSON.parse(
      bridge.call1(bridge.exports.cveil_init, TEST_CONFIG)
    );
    assert.equal(result.status, "initialized");
  });

  it("rejects invalid config", () => {
    // Shutdown first to allow re-init
    bridge.call0(bridge.exports.cveil_shutdown);
    assert.throws(
      () => bridge.call1(bridge.exports.cveil_init, '{"apiBaseUrl":""}'),
      CveilWasmError
    );
    // Re-init for remaining tests
    bridge.call1(bridge.exports.cveil_init, TEST_CONFIG);
  });

  it("ingests graph page", () => {
    const result = JSON.parse(
      bridge.call1(bridge.exports.cveil_ingest_graph_page, TEST_GRAPH_PAGE)
    );
    assert.equal(result.ingested, true);
    assert.equal(result.hasMore, false);
    assert.ok(result.totalNodes > 0);
  });

  it("returns status", () => {
    const status = JSON.parse(bridge.call0(bridge.exports.cveil_status));
    assert.equal(status.initialized, true);
    assert.ok(status.graphNodes > 0);
  });

  it("masks without consent (deny-by-default)", () => {
    const payload = JSON.stringify({
      email: "alice@example.com",
      name: "Alice",
    });
    const result = JSON.parse(
      bridge.call4(
        bridge.exports.cveil_mask,
        "user-1",
        "User",
        payload,
        "Marketing"
      )
    );

    // email is registered → should be masked
    assert.notEqual(result.data.email, "alice@example.com");
    // name is NOT registered → pass through
    assert.equal(result.data.name, "Alice");
  });

  it("masks with granted consent (plaintext)", () => {
    // Ingest a GRANT decision
    const decisions = JSON.stringify([
      {
        policyName: "FIELD:User/email:Marketing",
        action: "GRANT",
        expiresAt: null,
      },
    ]);
    bridge.call2(
      bridge.exports.cveil_ingest_decisions,
      "user-2",
      decisions
    );

    const payload = JSON.stringify({
      email: "bob@example.com",
      phone: "555-1234",
    });
    const result = JSON.parse(
      bridge.call4(
        bridge.exports.cveil_mask,
        "user-2",
        "User",
        payload,
        "Marketing"
      )
    );

    // email: GRANTED → PLAINTEXT
    assert.equal(result.data.email, "bob@example.com");
    // phone: no consent → REDACT_ALL
    assert.equal(result.data.phone, "***");
  });

  it("evaluates field consent", () => {
    const fields = JSON.stringify(["email", "phone"]);
    const result = JSON.parse(
      bridge.call4(
        bridge.exports.cveil_evaluate,
        "user-2",
        "User",
        fields,
        "Marketing"
      )
    );
    assert.equal(result.length, 2);
    assert.ok(result.some((r: any) => r.field === "email"));
    assert.ok(result.some((r: any) => r.field === "phone"));
  });

  it("processes invalidation events", () => {
    const event = JSON.stringify({
      messageId: "evt-1",
      tenantId: "t1",
      action: "PURPOSE_CHANGED",
      purpose: "Marketing",
    });
    const result = JSON.parse(
      bridge.call1(bridge.exports.cveil_process_invalidation, event)
    );
    assert.equal(result.effect, "refresh_subgraph");
    assert.equal(result.nodeType, "purpose");
    assert.equal(result.nodeName, "Marketing");
  });

  it("deduplicates invalidation events", () => {
    const event = JSON.stringify({
      messageId: "evt-1",
      tenantId: "t1",
      action: "PURPOSE_CHANGED",
      purpose: "Marketing",
    });
    const result = JSON.parse(
      bridge.call1(bridge.exports.cveil_process_invalidation, event)
    );
    assert.equal(result.effect, "duplicate");
  });

  it("manages SSE lifecycle", () => {
    const connected = JSON.parse(
      bridge.call0(bridge.exports.cveil_sse_connected)
    );
    assert.equal(connected.connected, true);

    const status1 = JSON.parse(bridge.call0(bridge.exports.cveil_status));
    assert.equal(status1.connectionState, "Connected");

    const disconnected = JSON.parse(
      bridge.call0(bridge.exports.cveil_sse_disconnected)
    );
    assert.equal(disconnected.connected, false);
  });

  it("handles reconnect with flush", () => {
    bridge.call0(bridge.exports.cveil_sse_connected);
    const result = JSON.parse(
      bridge.call0(bridge.exports.cveil_sse_reconnected)
    );
    assert.equal(result.effect, "flush_and_reload");

    const status = JSON.parse(bridge.call0(bridge.exports.cveil_status));
    assert.equal(status.graphNodes, 0); // Flushed
    assert.equal(status.connectionState, "Connected");
  });

  it("shuts down cleanly", () => {
    const result = JSON.parse(
      bridge.call0(bridge.exports.cveil_shutdown)
    );
    assert.equal(result.status, "shutdown");

    // Status should fail after shutdown
    assert.throws(
      () => bridge.call0(bridge.exports.cveil_status),
      CveilWasmError
    );
  });
});
