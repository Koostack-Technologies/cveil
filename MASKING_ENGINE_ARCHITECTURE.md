# Masking Engine - Architecture & Integration Guide

## Overview

The masking engine is a **consent-driven data redaction system** that:
1. Evaluates user consent for specific data fields against a governance graph
2. Resolves appropriate masking strategies based on regulatory context
3. Applies type-safe transformations to JSON data
4. Handles distributed caching with TTL-based invalidation

## System Components

```
┌─────────────────────────────────────────────────────────────────┐
│                   Application Layer                              │
│  (e.g., Node.js REST API handling GET /api/users/:id)          │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ↓
┌─────────────────────────────────────────────────────────────────┐
│                  JS SDK Facade Layer                             │
│                    (CveilClient)                                 │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ - Load & manage WASM module                                │ │
│  │ - Paginated graph ingestion from REST API                  │ │
│  │ - SSE connection for real-time invalidation                │ │
│  │ - Auto-fetch missing decisions + retry on cache miss       │ │
│  │ - Public API: mask(), maskWithJurisdiction(), evaluate()   │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  mask(subject, entity, data, purpose)                            │
│    ↓                                                              │
│    Call WasmBridge.call4(cveil_mask, ...)                       │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ↓
┌─────────────────────────────────────────────────────────────────┐
│                  WASM Bridge Layer                               │
│               (Node.js WASI Marshalling)                         │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ - String encoding (UTF-8 to bytes)                         │ │
│  │ - WASM memory allocation (cveil_alloc)                     │ │
│  │ - String writes to linear memory                           │ │
│  │ - Function invocation                                      │ │
│  │ - Result reads from memory                                 │ │
│  │ - Cleanup (cveil_dealloc)                                  │ │
│  │ - Error handling (CveilWasmError)                          │ │
│  └────────────────────────────────────────────────────────────┘ │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ↓
┌─────────────────────────────────────────────────────────────────┐
│              Rust WASM Core Engine Layer                         │
│             (cveil-sdk-core compiled to WASM)                   │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ 1. GRAPH STORE (in-memory graph cache)                   │  │
│  │    - Nodes: entity, field, category, purpose,            │  │
│  │             regulation, jurisdiction, policy, subject    │  │
│  │    - Edges: field↔category, entity→field,                │  │
│  │             purpose→regulation, purpose→policy            │  │
│  │    - Metadata: on_granted/on_denied MaskingTypes         │  │
│  │                on purpose→regulation edges                │  │
│  │    - Methods: fields_for_entity(), categories_for_field()│  │
│  │               regulations_for_purpose(),                  │  │
│  │               resolve_masking_for_field()                 │  │
│  └──────────────────────────────────────────────────────────┘  │
│                          ↑                                      │
│                          │ ingested from API                    │
│                          │ (cveil_ingest_graph_page)            │
│                          │                                      │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ 2. DECISION CACHE (LRU cache with TTL)                  │  │
│  │    - Key: hash(subject + ":" + policy_name)             │  │
│  │    - Value: ConsentAction + expiresAt timestamp         │  │
│  │    - Capacity: configurable (default 10k entries)       │  │
│  │    - TTL: configurable (default 5 minutes)              │  │
│  │    - Methods: put(), get(key, now_ms)                   │  │
│  │               is_expired() check on retrieval            │  │
│  └──────────────────────────────────────────────────────────┘  │
│                          ↑                                      │
│                          │ ingested from API                    │
│                          │ (cveil_ingest_decisions)             │
│                          │                                      │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ 3. EVALUATOR (consent → masking resolution)             │  │
│  │    ConsentEvaluator::evaluate_field()                    │  │
│  │                                                           │  │
│  │    Input: subject, entity, field_path, purpose,          │  │
│  │            jurisdiction, now_ms                          │  │
│  │                                                           │  │
│  │    Process:                                              │  │
│  │    a) Build policy names:                               │  │
│  │       - "FIELD:{entity}/{field}:{purpose}"              │  │
│  │       - "CAT:{category}:{purpose}" (all categories)      │  │
│  │                                                           │  │
│  │    b) Check decision cache (field → categories order)    │  │
│  │       - Lookup: cache.get(cache_key(subject, policy))    │  │
│  │       - If found & not expired: use decision             │  │
│  │       - If not found: signal NeedsFetch (unless          │  │
│  │         conservative_fallback=true)                      │  │
│  │                                                           │  │
│  │    c) Resolve masking type:                              │  │
│  │       - Call resolve_masking_for_field()                 │  │
│  │       - on_granted vs on_denied based on ConsentAction   │  │
│  │                                                           │  │
│  │    Output: EvalResult::Resolved(FieldMaskingResolution)  │  │
│  │             OR EvalResult::NeedsFetch                    │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ 4. MASKING ENGINE (JSON tree walker + mask application) │  │
│  │    mask_json_tree(value, resolutions: HashMap)          │  │
│  │                                                           │  │
│  │    Process:                                              │  │
│  │    - DFS traversal of JSON objects/arrays                │  │
│  │    - For each path:                                      │  │
│  │      * Exact match in resolutions → apply mask,          │  │
│  │        don't recurse                                     │  │
│  │      * No match → recurse into children                  │  │
│  │    - Call apply_mask(value, MaskingType) per leaf        │  │
│  │                                                           │  │
│  │    apply_mask implementation (type-safe):                │  │
│  │    ┌─ Plaintext     → value (unchanged)                  │  │
│  │    ├─ RedactAll     → "***" | 0 | false | null          │  │
│  │    ├─ RedactUpToLast4 → mask all but last 4 chars        │  │
│  │    ├─ RedactLocal   → mask email local part              │  │
│  │    └─ Hash256       → SHA256 hex digest                  │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ 5. FFI EXPORTS (public WASM functions)                  │  │
│  │    - cveil_init(config)                                  │  │
│  │    - cveil_ingest_graph_page(page)                       │  │
│  │    - cveil_ingest_decisions(subject, decisions)          │  │
│  │    - cveil_mask(subject, entity, payload, purpose)       │  │
│  │    - cveil_mask_with_jurisdiction(..., jurisdiction)     │  │
│  │    - cveil_mask_batch(subject, entity, array, purpose)   │  │
│  │    - cveil_evaluate(subject, entity, fields, purpose)    │  │
│  │    - cveil_process_invalidation(event)                   │  │
│  │    - cveil_status()                                      │  │
│  │    - cveil_shutdown()                                    │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                           ↑
                           │
        (needs missing decisions)
                           │
          (auto-fetch in SDK)
                           │
┌──────────────────────────────────────────────────────────────────┐
│                  REST API Layer (Java/Kotlin)                    │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ POST /api/v1/subjects/{subject}/evaluate                  │ │
│  │   Input: { scopes: [{ fieldName, categoryName, purpose }] │ │
│  │   Output: { granted, denied, details }                    │ │
│  │   Usage: SDK calls this when cache miss occurs            │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ POST /api/v1/subjects/consent/grant                       │ │
│  │ POST /api/v1/subjects/consent/revoke                      │ │
│  │ POST /api/v1/subjects/consent/{grant,revoke}-batch        │ │
│  │   Updates consent decisions in database                   │ │
│  │   Triggers SSE invalidation events                        │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ GET  /api/v1/consent/graph (paginated)                    │ │
│  │ POST /api/v1/governance/purposes/{name}/regulations       │ │
│  │ POST /api/v1/governance/purposes/{name}/policies          │ │
│  │   Manage governance model                                 │ │
│  │   Update triggers full/subgraph refresh in SDK            │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ SSE /api/v1/events/subscribe                              │ │
│  │   Real-time invalidation events:                          │ │
│  │   - GRANTED, REVOKED, ALL_REVOKED                         │ │
│  │   - FIELD_CHANGED, PURPOSE_CHANGED, etc.                  │ │
│  │   SDK processes: cveil_process_invalidation()             │ │
│  └────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

---

## Key Data Structures

### MaskingType Enum
```rust
Plaintext          // Return unchanged
RedactUpToLast4    // "****5678"
RedactLocal        // "****@example.com"
Hash256            // "2cf24dba..."
RedactAll          // "***" | 0 | null
```
**Ordering**: Plaintext < RedactAll (most restrictive wins)

### PolicyType Enum
```rust
Field       // FIELD:User/email:Marketing (specific field)
Category    // CAT:PII:Marketing (all fields in category)
```
**Precedence**: Field > Category

### PurposeRegulationLink (CRITICAL)
```rust
pub struct PurposeRegulationLink {
    purpose_name: String,
    regulation_name: String,
    lawful_basis: Option<String>,
    on_granted: MaskingType,    // Applied if consent = GRANT
    on_denied: MaskingType,     // Applied if consent = REVOKE/missing
}
```

---

## Masking Resolution Algorithm

**Input**: subject, entity, field_path, purpose, jurisdiction

**Steps**:

1. **Discover policies** (ConsentEvaluator::evaluate_field)
   - Field-level: `FIELD:{entity}/{field}:{purpose}`
   - Category-level: `CAT:{category}:{purpose}` (all categories of field)

2. **Check decision cache** (with field > category precedence)
   - Query: `cache.get(cache_key(subject, policy_name), now_ms)`
   - If found & not expired: `consent_action = decision.action`
   - If not found:
     - If `conservative_fallback=true`: treat as `REVOKE`
     - Else: signal `NeedsFetch` (no local decision)

3. **Resolve masking from graph** (GraphStore::resolve_masking_for_field)
   - Get all regulations linked to purpose
   - Filter by jurisdiction (keep only active regulations)
   - For each regulation:
     - Extract `link.on_granted` or `link.on_denied` based on `consent_action`
     - Track most restrictive `MaskingType` (using Ord comparison)
   - If no regulations applicable: default to `RedactAll`

4. **Apply masking to JSON** (Masking Engine)
   - Tree walk JSON using field path → MaskingType map
   - Call `apply_mask(value, masking_type)` per leaf
   - Return masked value

**Output**: `FieldMaskingResolution { field_path, masking_type, ... }`

---

## Consent Flow Example

```
User: alice@example.com
Field: User/email
Purpose: Marketing

1. CONSENT RECORD
   ├─ Policy: FIELD:User/email:Marketing
   ├─ Subject: alice
   ├─ Action: GRANT
   └─ ExpiresAt: null

2. GRAPH TRAVERSAL
   ├─ Purpose: Marketing
   ├─ Regulations: GDPR (EU), CCPA (CA), CCPA (Canada)
   ├─ Jurisdiction: EU
   ├─ Filter: GDPR (active in EU)
   └─ on_granted: Plaintext

3. DECISION
   ├─ Consent: GRANT
   ├─ MaskingType: Plaintext
   └─ Result: alice@example.com (unchanged)

---

User: bob@example.com
Field: User/email
Purpose: Marketing

1. CONSENT RECORD
   ├─ Policy: FIELD:User/email:Marketing
   ├─ Subject: bob
   ├─ Action: REVOKE
   └─ ExpiresAt: null

2. GRAPH TRAVERSAL
   ├─ Purpose: Marketing
   ├─ Regulations: GDPR (EU)
   ├─ Jurisdiction: EU
   └─ on_denied: RedactAll

3. DECISION
   ├─ Consent: REVOKE
   ├─ MaskingType: RedactAll
   └─ Result: *** (fully redacted)

---

User: charlie (no consent record)
Field: User/email
Purpose: Marketing

1. CONSENT RECORD
   └─ Not found (cache miss)

2. IF conservative_fallback=true (stale mode):
   └─ Treat as REVOKE → apply on_denied

3. IF conservative_fallback=false (normal mode):
   └─ Return NeedsFetch → SDK calls /evaluate API

4. SDK FETCHES:
   ├─ POST /subjects/charlie/evaluate
   ├─ Response: { denied: [{ fieldName: "email", purpose: "Marketing" }] }
   ├─ Ingest decision
   └─ Retry mask → apply on_denied
```

---

## Field vs Category Policy Precedence

```
Scenario 1: FIELD POLICY DECISION EXISTS
├─ FIELD:User/email:Marketing → GRANT (cached)
├─ CAT:PII:Marketing → REVOKE
└─ → Use FIELD decision (GRANT) → Plaintext

Scenario 2: CATEGORY POLICY DECISION EXISTS
├─ FIELD:User/email:Marketing → Not found
├─ CAT:PII:Marketing → GRANT (cached)
└─ → Use CATEGORY decision (GRANT) → Plaintext

Scenario 3: NO DECISION (CACHE MISS)
├─ FIELD:User/email:Marketing → Not found
├─ CAT:PII:Marketing → Not found
├─ conservative_fallback=true → apply on_denied (RedactAll)
└─ conservative_fallback=false → NeedsFetch
```

---

## Multi-Jurisdiction Masking

```
Purpose: Marketing
├─ Regulation: GDPR (EU)
│  ├─ on_granted: Plaintext
│  └─ on_denied: RedactAll
├─ Regulation: CCPA (California)
│  ├─ on_granted: Plaintext
│  └─ on_denied: RedactUpToLast4
└─ Regulation: PDPA (Singapore)
   ├─ on_granted: Plaintext
   └─ on_denied: Hash256

User revokes Marketing consent

EU Request:         → RedactAll ("***")
California Request: → RedactUpToLast4 ("****5678")
Singapore Request:  → Hash256 ("2cf24dba...")
Global Request:     → Uses defaultJurisdiction

// Code
await client.maskWithJurisdiction(
  "user-123", "User",
  { email: "alice@example.com" },
  "Marketing",
  "EU"  // Override default jurisdiction
);
```

---

## Cache Invalidation Flow

**SSE Event** (from Redis pub/sub):
```json
{
  "messageId": "evt-123",
  "tenantId": "tenant-1",
  "subject": "alice",
  "field": "email",
  "purpose": "Marketing",
  "action": "GRANTED"
}
```

**SDK Processing** (cveil_process_invalidation):
1. Parse InvalidationMessage
2. Determine effect:
   - Subject decision changed → flush subject decisions + retry mask
   - Field changed → refresh subgraph (field + related policies)
   - Purpose/Regulation/Jurisdiction changed → refresh full graph
   - Duplicate message → ignore (dedup window)
3. Execute effect:
   - `refresh_full_graph`: reload entire consent graph
   - `refresh_subgraph`: reload specific node type/name
   - `flush_and_reload`: full reset

---

## Stale Mode Behavior

**ConservativeMask** (default, safest):
```
SSE Connected:
  cache hit   → use decision
  cache miss  → call API (NeedsFetch)

SSE Disconnected < threshold:
  cache hit   → use decision
  cache miss  → call API (NeedsFetch)

SSE Disconnected > threshold (STALE):
  cache hit   → use decision
  cache miss  → apply onDenied (deny-by-default, no API call)
               → safest (prevents over-exposure on network failure)
```

**ApiFallback**:
```
SSE Connected:
  cache hit   → use decision
  cache miss  → call API

SSE Disconnected < threshold:
  cache hit   → use decision
  cache miss  → call API

SSE Disconnected > threshold (STALE):
  cache hit   → use decision
  cache miss  → call API (may fail gracefully)
               → more lax (tries to fetch even when stale)
```

---

## Integration Checklist

- [ ] Initialize SDK with CveilConfig (apiBaseUrl, apiToken, defaultJurisdiction)
- [ ] Ensure WASM file is accessible at configured path
- [ ] Implement graph loading (SDK auto-loads on init)
- [ ] Handle `needsFetch` in mask() result (SDK auto-handles)
- [ ] Configure staleMode (ConservativeMask recommended)
- [ ] Set appropriate cache TTL (default 5 min, adjust to consent update frequency)
- [ ] Subscribe to SSE for real-time invalidation
- [ ] Handle CveilApiError (retryable, requestId, retryAfter)
- [ ] Monitor SdkStatus for connection state
- [ ] Test multi-jurisdiction scenarios
- [ ] Validate masking output with test cases

---

## Performance Characteristics

| Operation | Time | Memory |
|-----------|------|--------|
| cveil_mask (cached) | ~1ms | 0 (no alloc) |
| cveil_mask (fetch) | 100-500ms | ↑ (API call) |
| cveil_evaluate (fields) | 1-5ms | O(fields) |
| Graph ingestion (page) | ~10ms | O(nodes + edges) |
| Decision cache lookup | <1ms | O(1) hashing |
| Decision cache eviction | ~5ms | O(1) LRU |
| Full graph reload | 1-5s | O(all nodes + edges) |

**Cache Sizing** (default config):
- Decision cache: 10,000 entries
- TTL: 5 minutes
- At 1000 users: ~10 decisions per user (cached)
- Memory: ~5-10MB (Rust efficient)

---

## Troubleshooting

**Issue**: needsFetch always non-empty
- → Check decision cache TTL (may be too low)
- → Check API endpoint connectivity
- → Check consent records exist in database

**Issue**: Masking returns different results per request
- → Check SSE connection status
- → Check cache invalidation events
- → Check staleMode setting

**Issue**: WASM module fails to load
- → Check wasmPath configuration
- → Ensure WASM binary is accessible
- → Check Node.js version (WASI support)

**Issue**: Multi-jurisdiction masking returns same result
- → Check jurisdiction configured in regulation links
- → Check purpose → regulation edges exist
- → Verify on_granted / on_denied differ per regulation

