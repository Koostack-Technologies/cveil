# Masking Engine - Comprehensive Exploration Summary

## Overview
This exploration provides a complete understanding of the masking engine in the Operio project, covering all 5 requested areas with detailed file paths, function signatures, data structures, and interconnections.

---

## 📋 FINDINGS BY AREA

### 1. ✅ RUST/WASM SDK CORE (`/crates/cveil-sdk-core/`)

**What it does**:
- High-performance consent-driven data masking engine
- Compiles to WASM for execution in Node.js via WASI
- Manages in-memory consent graph + decision cache
- Evaluates consent policies and resolves masking strategies
- Applies type-safe masking to JSON payloads

**Location**: `/Users/mariahgordon/koostacktech/operio/crates/cveil-sdk-core/src/`

**Key Files**:
- `masking/engine.rs` (97 lines)
  - `apply_mask(value: Value, type: MaskingType) → Value`
  - `mask_json_tree(value, resolutions: HashMap<path, MaskingType>)`
  
- `evaluator/consent_evaluator.rs` (387 lines)
  - `ConsentEvaluator::evaluate_field()` → EvalResult
  - `evaluate_field(subject, entity, field_path, purpose, jurisdiction, now_ms)`
  - Implements field > category precedence
  
- `graph/store.rs` (480+ lines)
  - `GraphStore` (in-memory graph cache)
  - `resolve_masking_for_field(purpose, jurisdiction, consent_granted) → MaskingResolution`
  - Manages nodes (entity, field, category, purpose, regulation, jurisdiction, policy, subject)
  
- `ffi/exports.rs` (350+ lines) 
  - `cveil_init(config_json)` → Initialize SDK
  - `cveil_mask(subject, entity, payload, purpose)` → Core masking
  - `cveil_mask_with_jurisdiction(..., jurisdiction)` → Override jurisdiction
  - `cveil_mask_batch(subject, entity, array, purpose)` → Batch masking
  - `cveil_evaluate(subject, entity, fields, purpose)` → Evaluate only
  - `cveil_ingest_graph_page(page_json)` → Load graph
  - `cveil_ingest_decisions(subject, decisions_json)` → Load decisions
  - `cveil_process_invalidation(event_json)` → Handle SSE events
  
- `models/types.rs`
  - `MaskingType` enum (5 variants: Plaintext, RedactUpToLast4, RedactLocal, Hash256, RedactAll)
  - `PolicyType` enum (Field, Category)
  - `ConsentAction` enum (Grant, Revoke, Expire)
  - `PurposeRegulationLink` struct (KEY: defines on_granted/on_denied)
  - `FieldMaskingResolution` struct
  
- `cache/decision_cache.rs`
  - `DecisionCache` (LRU with TTL)
  - `cache_key(subject, policy_name) → hash`

**Masking Strategies**:
1. **Plaintext** (0): Original value unchanged
2. **RedactUpToLast4** (1): Mask all but last 4 chars ("****5678")
3. **RedactLocal** (2): Mask email local part ("****@example.com")
4. **Hash256** (3): SHA-256 hex digest
5. **RedactAll** (4): Full redaction ("***", 0, false, null)

**Parameters Accepted**:
- Input: subject, entity, JSON payload, purpose, [jurisdiction], [fields]
- Config: apiBaseUrl, apiToken, defaultJurisdiction, staleMode, cache settings
- Cache key: hash(subject:policy_name)

**Input/Output Format**:
- Input: JSON strings (via FFI marshalling)
- Output: 
  - mask(): `{data: Value, needsFetch: [{fieldPath, policyNames}]}`
  - evaluate(): `[{field, masking_type, needs_fetch, policy_names}]`
  - ingest_*(): `{status: string, ingested: count}`

---

### 2. ✅ JS SDK WRAPPER (`/sdks/cveil-sdk-js/`)

**How it wraps WASM**:
- WasmBridge class marshals strings to/from WASM memory
- String encoding (UTF-8) + WASM memory allocation (cveil_alloc/dealloc)
- Pointer-based function calls (call0/call1/call2/call4/call5)
- Error handling (CveilWasmError on rc ≠ 0)

**Location**: `/Users/mariahgordon/koostacktech/operio/sdks/cveil-sdk-js/src/`

**Public API (CveilClient)**:
```typescript
// Initialization
CveilClient.create(options: CveilClientOptions): Promise<CveilClient>

// Masking (main methods)
mask(subject, entity, data, purpose): Promise<MaskResult>
maskWithJurisdiction(..., jurisdiction): Promise<MaskResult>
maskBatch(subject, entity, data[], purpose): Promise<MaskResult[]>

// Evaluation
evaluate(subject, entity, fields[], purpose): EvaluateResult[]

// Decision Management
ingestDecisions(subject, decisions: DecisionEntry[]): void
evaluateConsent(subject, scopes: ConsentScope[]): Promise<...>

// Governance (domain management)
listPurposes(), registerPurpose(name, desc)
listRegulations(), registerRegulation(name, desc)
linkPurposeRegulation(purpose, reg, lawfulBasis, onGranted, onDenied)
addPurposePolicy(purpose, policyType, fieldName?, categoryName?)

// Graph Management
loadGraph(): Promise<void>
connectSSE(): void

// Status
status(): SdkStatus
```

**Types/Interfaces Exposed**:
- `CveilConfig`: API URL, token, jurisdiction, cache settings
- `MaskResult`: {data, needsFetch}
- `EvaluateResult`: {field, masking_type, consent_action, needs_fetch}
- `NeedsFetchEntry`: {fieldPath, policyNames}
- `DecisionEntry`: {policyName, action, expiresAt}
- `SdkStatus`: {initialized, connectionState, graphNodes, cachedDecisions}
- `ConsentScope`: {category?, fieldName?, entityName?, purposeName?}
- Plus 40+ other types for governance, catalogue, subjects

**Implementation Details**:
- WasmBridge: `call4(fn, a1, a2, a3, a4) → string`
  - Writes 4 strings to WASM memory
  - Invokes WASM function
  - Reads result from memory
  - Deallocates + returns result JSON
  
- CveilClient:
  - Graph loading: Paginated API calls (default 100 per page)
  - Auto-fetch: Detects needsFetch, calls `/evaluate` API, retries
  - SSE connection: Establishes `/api/v1/events/subscribe`, handles invalidation
  - Reconnection: Exponential backoff (1s → 30s max)

**WASM Binary**: 
- Location: `/sdks/cveil-sdk-js/wasm/cveil_sdk_core.wasm`
- Size: 433 KB

---

### 3. ✅ REST API ENDPOINTS (`/cveil-rest-api/src/main/java/com/koostack/cveil/rest/`)

**Consent Resource** (`/api/v1/subjects`):
- `POST /consent/grant` → Grant single scope
- `POST /consent/revoke` → Revoke single scope
- `POST /consent/grant-batch` → Batch grant (1000 max)
- `POST /consent/revoke-batch` → Batch revoke
- **`POST /{subject}/evaluate`** ← KEY: SDK uses this on cache miss
  - Input: `{scopes: [{fieldName?, categoryName?, purposeName?}]}`
  - Output: `{subject, granted[], denied[], details{}}`
- `GET /{subject}` → Get subject info
- `GET /{subject}/consent` → Get ledger
- `POST /{subject}` → Register subject
- `PUT /{subject}` → Update subject
- `DELETE /{subject}` → Delete subject

**Governance Resource** (`/api/v1/governance`):
- `GET /purposes` → List purposes
- `POST /purposes` → Register purpose
- `POST /purposes/{name}/regulations` → Link to regulation
- `POST /purposes/{name}/policies` → Add FIELD|CATEGORY policy
- `GET /regulations` → List regulations
- `POST /regulations` → Register regulation
- `POST /regulations/{name}/jurisdictions` → Add jurisdiction
- `GET /jurisdictions` → List jurisdictions
- `POST /jurisdictions` → Register jurisdiction

**Graph Resource** (`/api/v1/consent`):
- `GET /graph?cursor=&limit=100&rootType=purpose&rootName=Marketing&depth=2`
  - Returns: `{nodes: [], edges: [], pageInfo: {nextCursor, hasMore, totalNodes, totalEdges}}`

**Governance DTOs** (from types.ts):
- `RegisterPurposeRequest`: {purposeName, description?}
- `PurposeRegulationLink`: {regulationName, lawfulBasis?, onGranted?, onDenied?}
- `AddPurposePolicyRequest`: {purposeName, policyType, fieldName?, categoryName?}

---

### 4. ✅ CONSENT PAGE TEMPLATE (`/cveil-rest-api/src/main/resources/templates/consent.html`)

**3 Main Tabs**:

1. **Graph Explorer** (active by default)
   - D3.js force graph visualization
   - Tree view option
   - BFS search (by type: purpose, regulation, jurisdiction, field, subject; by name)
   - Legend: Purpose (teal), Regulation (blue), Jurisdiction (amber), Field (purple), Subject (pink), Other (gray)
   - Node detail tooltip on hover
   - Pagination + Load More button
   - Controls: Mode toggle (Force/Tree), search input, root type dropdown, depth limit, Search/Reset buttons

2. **Ledger Feed**
   - Real-time SSE stream of consent events
   - Event types: GRANTED, REVOKED, ALL_REVOKED, CONSENT_ENTRY_CHANGED
   - Filters: action dropdown, subject text input, purpose text input
   - Pause/resume auto-scroll button
   - "Waiting for consent events..." placeholder

3. **Subjects**
   - Subject registry table: name, description, created, actions
   - Add Subject button (slide-out panel)
   - Search bar (filters table in real-time)
   - Detail panel: Summary + History tabs
   - DSAR Export button per subject

**UI Patterns**:
- Tailwind CSS styling (dark theme with cv-500 accent color)
- Inline SVG icons (hamburger, search, X, plus, pause)
- Slide-out right panel for subject CRUD
- Modal dialog for delete confirmation
- Badge styling for action types

**JavaScript Initialization**:
```html
<script src="https://d3js.org/d3.v7.min.js"></script>
<script src="/js/cveil-web.iife.js"></script>
<script>CveilWeb.consent.init();</script>
```
Loads D3 + consent module IIFE, initializes page.

---

### 5. ✅ MASKING CONFIGURATION & GOVERNANCE POLICIES

**Policy Type Structure**:

**FIELD Policy**:
- Pattern: `"FIELD:{entity}/{field}:{purpose}"`
- Example: `"FIELD:User/email:Marketing"`
- Scope: Exact field + purpose binding
- Precedence: WINS (evaluated first)

**CATEGORY Policy**:
- Pattern: `"CAT:{category}:{purpose}"`
- Example: `"CAT:PII:Marketing"`
- Scope: All fields tagged with category + purpose
- Precedence: FALLBACK (evaluated if no field decision)

**Evaluation Order** (ConsentEvaluator:54-84):
```
1. Check cache for FIELD:{entity}/{field}:{purpose}
   ├─ Found → use decision
   └─ Not found → step 2
2. Check cache for all CAT:{category}:{purpose} (for field's categories)
   ├─ Found (any) → use decision
   └─ Not found → NeedsFetch or conservative default
```

**Masking Behavior via PurposeRegulationLink**:
```rust
pub struct PurposeRegulationLink {
    pub on_granted: MaskingType,    // Applied if action = GRANT
    pub on_denied: MaskingType,     // Applied if action = REVOKE/missing
}
```

**Example Configuration**:
```
Purpose: "Marketing"
├─ Regulation: "GDPR" (active in EU)
│  ├─ onGranted: Plaintext
│  └─ onDenied: RedactAll
├─ Regulation: "CCPA" (active in California)
│  ├─ onGranted: Plaintext
│  └─ onDenied: RedactUpToLast4
└─ Regulation: "PDPA" (active in Singapore)
   ├─ onGranted: Plaintext
   └─ onDenied: Hash256

User "alice" revokes Marketing consent:
├─ EU request  → RedactAll ("***")
├─ CA request  → RedactUpToLast4 ("****5678")
└─ SG request  → Hash256 ("2cf24dba...")
```

**Governance Graph Nodes**:
- `entity`: Data model (User, Order, Product)
- `field`: Specific field (User/email, User/phone)
- `category`: Data classification (PII, Sensitive, Financial)
- `purpose`: Business purpose (Marketing, Analytics, Fraud Detection)
- `regulation`: Legal framework (GDPR, CCPA, PDPA)
- `jurisdiction`: Geography (EU, California, Singapore, Global)
- `policy`: Binding (FIELD:User/email:Marketing, CAT:PII:Marketing)
- `subject`: Data subject (user ID)

**Cache Behavior**:
- Decision TTL: configurable (default 5 minutes)
- On expiration: treated as cache miss → NeedsFetch or conservative default
- Stale mode:
  - `conservativeMask`: Missing decisions → apply onDenied (deny-by-default)
  - `apiFallback`: Missing decisions → trigger NeedsFetch (fetch from API)

---

## 🔗 HOW EVERYTHING CONNECTS

```
Application
    ↓
CveilClient.mask(subject="alice", entity="User", data={...}, purpose="Marketing")
    ↓
WasmBridge marshals strings → WASM memory
    ↓
cveil_mask(subject, entity, payload, purpose)
    ├─ GraphStore.fields_for_entity("User") → ["email", "phone"]
    ├─ ConsentEvaluator.evaluate_fields() → for each field:
    │  ├─ Build policy names: ["FIELD:User/email:Marketing", "CAT:PII:Marketing", ...]
    │  ├─ DecisionCache.get(subject:policy) → ConsentAction
    │  ├─ GraphStore.resolve_masking_for_field("Marketing", "EU", consent_granted)
    │  │  ├─ purpose→regulations: ["GDPR", "CCPA", ...]
    │  │  ├─ Filter by jurisdiction: ["GDPR"] (active in EU)
    │  │  ├─ Extract on_granted/on_denied: RedactAll
    │  │  └─ Return: {masking_type: RedactAll, regulation_name: "GDPR"}
    │  └─ Return: Resolved(FieldMaskingResolution)
    ├─ Collect resolutions: {"email": RedactAll, "phone": RedactAll, ...}
    ├─ Collect NeedsFetch: [] (if cache hit)
    └─ mask_json_tree(data, resolutions) → apply_mask() per field
    ↓
Return: {data: {email: "***", phone: "***"}, needsFetch: []}
    ↓
If needsFetch.length > 0:
  ├─ SDK calls: POST /subjects/alice/evaluate
  ├─ Response: {granted: [], denied: [{fieldName: "email"}], ...}
  ├─ SDK ingests: cveil_ingest_decisions()
  └─ SDK retries: cveil_mask() → return masked data

─────────────────────────────────────────────────────

SSE Invalidation (background):
├─ Redis pub/sub event: subject=alice, action=REVOKED
├─ REST API → SSE: /api/v1/events/subscribe
├─ CveilClient listeners → cveil_process_invalidation()
├─ Effect: GRANTED → flush subject decisions cache
└─ Next mask() call uses fresh decisions
```

---

## 📊 DATA STRUCTURE RELATIONSHIPS

```
ConsentAction (GRANT|REVOKE|EXPIRE)
    ↓
CachedDecision {action, expires_at}
    ↓ (key: subject:policy_name)
DecisionCache
    ↓
evaluate_field() returns Resolved | NeedsFetch
    ↓
resolve_masking_for_field() returns MaskingResolution
    ↓
FieldMaskingResolution {masking_type, regulation_name, lawful_basis}
    ↓
apply_mask() returns masked Value
    ↓
mask_json_tree() applies to entire JSON
    ↓
MaskResult {data, needsFetch}
    ↓ (if needsFetch)
REST API /subjects/{subject}/evaluate
    ↓
cveil_ingest_decisions()
    ↓
Retry cveil_mask()
```

---

## 📁 DOCUMENTATION GENERATED

All findings documented in project root:

1. **README_MASKING_ENGINE.md** (308 lines)
   - Index + quick start guide
   - File locations reference
   - Core concepts summary
   - Common use cases
   - Configuration
   - Debugging tips

2. **MASKING_ENGINE_QUICK_REFERENCE.md** (256 lines)
   - 5 masking strategies table
   - Policy precedence
   - Core WASM functions
   - JS SDK API examples
   - REST endpoints
   - Configuration defaults
   - Cache keys, graph types
   - Key invariants

3. **MASKING_ENGINE_ARCHITECTURE.md** (502 lines)
   - Detailed architecture diagram
   - Component breakdown
   - Masking resolution algorithm (step-by-step)
   - Consent flow examples with walkthroughs
   - Multi-jurisdiction masking
   - Cache invalidation
   - Integration checklist
   - Performance characteristics
   - Troubleshooting

4. **MASKING_ENGINE_ANALYSIS.txt** (423 lines)
   - Comprehensive technical analysis
   - All Rust/WASM structures
   - Evaluator logic
   - FFI exports
   - JS SDK detailed types
   - REST API endpoints
   - UI template tabs
   - Data flow interactions

---

## ✅ CHECKLIST: ALL REQUIREMENTS MET

- ✅ **Rust/WASM SDK Core**: What it does, masking strategies, parameters, input/output format
  - Located: `/crates/cveil-sdk-core/src/`
  - Functions: apply_mask(), mask_json_tree(), evaluate_field(), resolve_masking_for_field(), cveil_mask(), cveil_evaluate()
  - Strategies: 5 MaskingTypes (Plaintext, RedactUpToLast4, RedactLocal, Hash256, RedactAll)

- ✅ **JS SDK Wrapper**: WASM wrapping, public API, types/interfaces
  - Located: `/sdks/cveil-sdk-js/src/`
  - WASM Bridge: Memory marshalling (call0-call5)
  - Public API: mask(), maskWithJurisdiction(), maskBatch(), evaluate(), ingestDecisions()
  - Types: 40+ interfaces in types.ts

- ✅ **REST API Endpoints**: Masking-related endpoints with full details
  - Located: `/cveil-rest-api/src/main/java/.../resource/`
  - Endpoints: POST /subjects/{subject}/evaluate (KEY), /consent/*, /governance/*, /consent/graph
  - Contracts: Input/output DTOs specified

- ✅ **Consent Page Template**: Tabs, structure, UI patterns
  - Located: `/cveil-rest-api/src/main/resources/templates/consent.html`
  - Tabs: Graph Explorer (D3.js), Ledger Feed (SSE), Subjects (CRUD)
  - Pattern: Tailwind CSS, slide-out panels, real-time updates

- ✅ **Governance Policies**: FIELD vs CATEGORY, masking behavior relationships
  - FIELD policy: Specific field (e.g., User/email)
  - CATEGORY policy: Data classification (e.g., PII)
  - Precedence: FIELD > CATEGORY
  - Masking: PurposeRegulationLink.on_granted / on_denied based on consent
  - Multi-jurisdiction: Most restrictive wins

---

## 🎯 KEY TAKEAWAYS

1. **Masking is consent-driven**: Graph traversal to resolve masking based on purpose→regulation→jurisdiction
2. **Field policies override category**: Precedence built into evaluator
3. **Most restrictive wins**: Across multiple regulations for same purpose
4. **Cache-first with fallback**: Decision cache + TTL + NeedsFetch signal
5. **Type-safe masking**: Output matches input type (0 for numbers, null for objects)
6. **Real-time invalidation**: SSE from Redis → graph/cache updates
7. **Stale mode protection**: Conservative deny-by-default or API fallback

---

**Files Created**: 4 comprehensive documents (1489 lines total)  
**Time to Read**: Quick ref (5 min), Architecture (15 min), Full analysis (30 min)  
**Ready for**: Integration, contribution, documentation
