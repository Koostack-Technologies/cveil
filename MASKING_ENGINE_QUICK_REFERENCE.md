# Masking Engine Quick Reference

## 5 Masking Strategies

| Type | Result | Use Case |
|------|--------|----------|
| `Plaintext` | Original value | Granted consent |
| `RedactUpToLast4` | `****5678` | Show last 4 only |
| `RedactLocal` | `****@example.com` | Email local part hidden |
| `Hash256` | `2cf24dba...` (64 chars) | Anonymization |
| `RedactAll` | `***` / `0` / `null` | Denied consent (default) |

**Most restrictive wins**: `Plaintext < RedactUpToLast4 < RedactLocal < Hash256 < RedactAll`

---

## Policy Precedence

```
1. FIELD policy      "FIELD:User/email:Marketing"    ← Wins
2. CATEGORY policy   "CAT:PII:Marketing"             ← Fallback
3. Conservative default (on_denied)                  ← Last resort
```

---

## Masking Resolution Flow

```
Consent Decision (GRANT/REVOKE/EXPIRE)
    ↓
Purpose → Regulation Link (on_granted / on_denied)
    ↓
Filter by Jurisdiction (EU, California, etc.)
    ↓
Select MaskingType (most restrictive if multiple)
    ↓
apply_mask() transforms value
```

**Example**:
- Subject: REVOKE for "Marketing" → use `on_denied`
- Purpose "Marketing" → Regulation "GDPR" (EU) → onDenied = RedactAll
- Result: email fully redacted

---

## Core WASM Functions

| Function | Input | Output | Purpose |
|----------|-------|--------|---------|
| `cveil_mask()` | subject, entity, JSON, purpose | `{data, needsFetch}` | Main masking |
| `cveil_evaluate()` | subject, entity, fields[], purpose | `[{field, type, needs_fetch}]` | Evaluate only |
| `cveil_ingest_graph_page()` | ConsentGraphPage JSON | `{ingested: bool}` | Load graph |
| `cveil_ingest_decisions()` | subject, decisions JSON | `{ingested: count}` | Load decisions |
| `cveil_init()` | config JSON | `{status}` | Initialize SDK |

---

## JS SDK Public API

```typescript
// Initialize
const client = await CveilClient.create({
  config: {
    apiBaseUrl: "https://api.cveil.example.com",
    apiToken: "cveil_token",
    defaultJurisdiction: "EU"
  }
});

// Mask data
const result = await client.mask("user-123", "User", 
  { email: "alice@example.com", name: "Alice" }, 
  "Marketing"
);
// → { data: { email: "***", name: "Alice" }, needsFetch: [] }

// Evaluate without masking
const decisions = client.evaluate("user-123", "User", 
  ["email", "phone"], 
  "Marketing"
);
// → [{ field: "email", masking_type: "REDACT_ALL", needs_fetch: false }, ...]

// Manual decision ingestion
client.ingestDecisions("user-123", [
  { policyName: "FIELD:User/email:Marketing", action: "GRANT" }
]);
```

---

## REST API Key Endpoints

**Evaluate consent (SDK uses this on cache miss)**:
```
POST /api/v1/subjects/{subject}/evaluate
Body: { scopes: [{ fieldName: "email", purposeName: "Marketing" }] }
Response: { granted: [], denied: [scope], details: {} }
```

**Grant/revoke consent**:
```
POST /api/v1/subjects/consent/grant
POST /api/v1/subjects/consent/revoke
```

**Governance**: Create purposes, regulations, jurisdictions, policies
```
POST /api/v1/governance/purposes
POST /api/v1/governance/purposes/{name}/regulations
POST /api/v1/governance/purposes/{name}/policies
```

**Graph**: Load full consent graph (what SDK ingests)
```
GET /api/v1/consent/graph?cursor=&limit=100
```

---

## Configuration Defaults

```typescript
{
  disconnectThresholdMs: 60000,        // 1 minute
  decisionCacheMaxSize: 10000,         // entries
  decisionCacheTtlMs: 300000,          // 5 minutes
  dedupWindowSize: 500,                // events
  staleMode: "conservativeMask"        // or "apiFallback"
}
```

**Stale Mode**:
- `conservativeMask`: Missing decisions → deny-by-default (no API call)
- `apiFallback`: Missing decisions → trigger needsFetch (API call)

---

## NeedsFetch Pattern

When SDK returns `needsFetch`:
1. SDK detected missing consent decisions
2. Lists field paths + policy names needed
3. Application should:
   ```typescript
   if (result.needsFetch.length > 0) {
     // SDK auto-fetches internally, but you can manually:
     const decisions = await client.evaluateConsent(subject, 
       result.needsFetch.map(nf => ({ fieldName: nf.fieldPath }))
     );
     // SDK ingest + retry happens automatically
   }
   ```

---

## Cache Keys

Decision cache key = `hash(subject + ":" + policyName)`

Example:
- Subject: `"user-123"`
- Policy: `"FIELD:User/email:Marketing"`
- Key: Hash of `"user-123:FIELD:User/email:Marketing"`

TTL applied per entry; entries expire at `expiresAt` timestamp.

---

## Graph Node Types

- `entity`: Data model (User, Order, etc.)
- `field`: Specific field (User/email, User/phone)
- `category`: Data classification (PII, Sensitive, etc.)
- `purpose`: Business purpose (Marketing, Analytics)
- `regulation`: Legal framework (GDPR, CCPA)
- `jurisdiction`: Geography (EU, California)
- `policy`: Binding (FIELD:User/email:Marketing)
- `subject`: Data subject (user)

---

## Stale Connection Behavior

```
SSE Connected
    ↓
cache miss → call /evaluate API (sync)
cache hit → use cached decision
    ↓
SSE Disconnected (< threshold)
    ↓
cache miss → NeedsFetch (async, SDK calls /evaluate)
cache hit → use cached decision
    ↓
SSE Disconnected (> threshold) - STALE
    ↓
ConservativeMask mode:
  cache miss → apply onDenied (RedactAll default)
  NO API CALL
    
ApiFallback mode:
  cache miss → NeedsFetch (SDK calls /evaluate)
```

---

## Example: Multi-Jurisdiction Masking

```typescript
// EU: GDPR onDenied = RedactAll
// CA: CCPA onDenied = RedactUpToLast4
// Global fallback: onDenied = RedactAll

// User revokes "Marketing" consent
// EU request: email → RedactAll ("***")
// CA request: email → RedactUpToLast4 ("****5678")

await client.maskWithJurisdiction(
  "user-123", "User",
  { email: "alice@example.com" },
  "Marketing",
  "EU"  // Override default jurisdiction
);
// → { email: "***" }
```

---

## File Locations Reference

| Component | Path |
|-----------|------|
| Masking engine | `crates/cveil-sdk-core/src/masking/engine.rs` |
| Evaluator | `crates/cveil-sdk-core/src/evaluator/consent_evaluator.rs` |
| Graph store | `crates/cveil-sdk-core/src/graph/store.rs` |
| WASM exports | `crates/cveil-sdk-core/src/ffi/exports.rs` |
| JS client | `sdks/cveil-sdk-js/src/client.ts` |
| WASM bridge | `sdks/cveil-sdk-js/src/wasm-bridge.ts` |
| Consent API | `cveil-rest-api/src/main/java/.../ConsentResource.java` |
| UI template | `cveil-rest-api/src/main/resources/templates/consent.html` |
| WASM binary | `sdks/cveil-sdk-js/wasm/cveil_sdk_core.wasm` (433 KB) |

---

## Key Invariants

✓ **Consent cache TTL**: Decisions expire; stale decisions treated as unknown  
✓ **Field > Category**: Field policies always win in precedence  
✓ **Most restrictive wins**: Multiple regulations → max(masking_types)  
✓ **Deny-by-default**: No decision → apply onDenied MaskingType  
✓ **Jurisdiction filtering**: Only regulations active in target jurisdiction apply  
✓ **Type-safe sentinels**: Masking output matches input type (0 for numbers, false for bools, null for objects)

