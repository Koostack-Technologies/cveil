
# Consent Decision Projection — Refactoring Plan

## Agent Prompt

> Read this plan and continue with the next incomplete task. This is a multi-phase refactoring
> of the consent decision projection. It uses a **Hash-per-subject** Redis schema with
> **reverse indices** for O(1) invalidation. Keys are prefixed by `{tenantId}` only (no `appName`).
> The command side uses hydration repositories for entity validation and an ExpirationSweeperService.
> Read the full plan, check the after-action table for completed tasks, and pick up where the
> last session left off.
>
> **Key code facts to remember:**
> - `InvalidationAction` enum has: GRANTED, REVOKED, ALL_REVOKED, CATEGORY_CHANGED, FIELD_REMOVED.
>   There is NO `CATEGORY_DELETED` value — use `CATEGORY_CHANGED` or add new values as needed.
> - `ConsentPolicyRedisRepository` is a standalone class (no interface). `DecisionDetail` comes
>   from `com.koostack.operio.core.models.DecisionDetail`.
> - `TenantContext` uses Java 21 `ScopedValue`. `runWhere()` binds tenantId for scope of operation.
> - `Pipeline` from Jedis implements `Closeable` — `close()` auto-calls `sync()`. Don't double-sync.
> - The handler is wired in `CoreConsentBundle` (not Spring `@Bean`). Check there for constructor changes.
> - `StreamEventHandler` is `@FunctionalInterface` with single method: `onEvent(streamKey, messageId, fields)`.

---

## Overview

### Why This Refactoring Exists

During implementation of a cache invalidation pipeline (Phases 1-3 in
`CONSENT_CACHE_INVALIDATION_PIPELINE.md`), a code review identified that the decision projection
lacked consent key cleanup on `CATEGORY_DELETED` and `ENTITY_FIELD_REMOVED` events. A plan was
drafted to add a "category consent index" to the projection's Redis store to enable retroactive
fan-out and cleanup (`RETROACTIVE_CATEGORY_CONSENT_FANOUT.md`).

During design review, **two** fundamental architectural issues surfaced:

1. **The projection was materializing category grants into per-field keys** — maintaining
   `field2cat`/`cat2field` Redis sets and querying them at projection time. This is wrong.
   Category→field metadata belongs to the admin projection (JOOQ/DB), not the decision cache.

2. **The Redis key schema used flat string keys with an `appName` prefix** — requiring O(N)
   SCAN for subject cleanup and no native support for category-level consent queries.

Through iterative design discussion, the following architectural corrections were established:

- **Consent is always granted/revoked with all 4 dimensions:** `(category, fieldName, entityName, purposeName)`
- **Consent can be scoped at two levels:** `(category, purpose)` or `(entity, field, purpose)`
- **Field-level takes precedence** over category-level — a field revoke overrides a category grant
- **No materialization needed** — store category grants as `c:` entries and field overrides as `f:` entries; the evaluate path resolves precedence at read time with a 2-step HMGET

### Correct Architecture

```
┌─────────────────────────────────┐
│     ConsentAdminCommand         │
│  (validates, emits)             │
│                                 │
│  On grant/revoke:               │
│  1. Validate referenced         │
│     entities exist              │
│  2. Auto-create if missing      │
│  3. Emit POLICY_GRANTED or      │
│     POLICY_REVOKED event        │
└──────────────┬──────────────────┘
               │
               ▼
┌─────────────────────────────────┐
│   ConsentDecisionProjection     │
│   (Hash-per-subject store)      │
│                                 │
│  On POLICY_GRANTED/REVOKED:     │
│  1. HSET consent:{subject}      │
│     c:{cat}:{purpose} or        │
│     f:{entity/field}:{purpose}  │
│  2. SADD reverse indices        │
│  3. Publish invalidation msg    │
│                                 │
│  On CATEGORY_DELETED:           │
│  1. SMEMBERS idx:cat:{cat}      │
│  2. HDEL matching entries       │
│  3. Publish invalidation msgs   │
│  4. DEL reverse index           │
│                                 │
│  On ENTITY_FIELD_REMOVED:       │
│  1. SMEMBERS idx:field:{e/f}    │
│  2. HDEL matching entries       │
│  3. Publish invalidation msgs   │
│  4. DEL reverse index           │
└─────────────────────────────────┘
```

**Key principles:**
- The decision projection is the read model for the ConsentPolicy aggregate ONLY
- Category, Entity, and Field metadata are managed by the ConsentAdminProjection (JOOQ/DB)
- **ConsentScope uses `*` wildcard sentinel** for dimensions not targeted:
  - Category-level: `(category, *, *, purpose)` — field and entity are `*`
  - Field-level: `(*, fieldName, entityName, purpose)` — category is `*`
  - These are the ONLY two valid input patterns (no partial wildcards)
- **No materialization** — category grants stored as ONE hash entry, not N per-field entries
- **Precedence resolved at read time** — field-level checked first, then category-level
- **Reverse indices** enable O(1) bulk invalidation on category/field/purpose deletes
- **`appName` dropped** — key prefix is just `{tenantId}`, not `{appName}:{tenantId}`
- **Expiry handled by ExpirationSweeperService** — no Redis TTL on hash fields

### What's Wrong Today

1. **`FieldCategoryMappingRedisRepository`** maintains `field2cat`/`cat2field` Redis sets
   inside the decision cache — this is admin-side data that doesn't belong here

2. **Category fan-out** materializes category grants into N per-field keys at projection time
   by querying `mappingRepo.getFieldsForCategory()` — unnecessary with Hash schema

3. **Flat string keys** (`{appName}:{tenantId}:consent:{subject}:{entity/field}:{purpose}`)
   make subject cleanup O(N) via SCAN and don't support category-level queries

4. **`appName` prefix** is unnecessary — `tenantId` alone provides namespace isolation

5. **Duplicate metadata state** — the admin projection ALREADY maintains field↔category metadata
   correctly. The decision projection redundantly maintains a parallel copy

---

## Redis Key Schema (NEW)

### ConsentScope → Hash Field Mapping

A `ConsentScope` always has 4 fields: `(category, fieldName, entityName, purposeName)`.
The `*` wildcard sentinel indicates "all" for that dimension. Only two patterns are valid:

| Scope Level | ConsentScope Pattern | Hash Field | Example |
|-------------|---------------------|------------|---------|
| **Category** | `(PII, *, *, marketing)` | `c:PII:marketing` | Grant/revoke PII for marketing |
| **Field** | `(*, email, User, marketing)` | `f:User/email:marketing` | Grant/revoke User/email for marketing |

**Projection write rule:** If `fieldName == "*"` → write `c:{category}:{purpose}`. If `category == "*"` → write `f:{entity/field}:{purpose}`.

**Evaluate read rule:** The caller provides a **concrete** scope (no wildcards — all 4 are specific values). The read path checks `f:` first (precedence), then `c:`.

> All unspecified dimensions use `"*"` (not null) in `ConsentScope`, `PolicyGrantedEvent`,
> and `PolicyRevokedEvent`. All branching uses wildcard checks (`if (!"*".equals(fieldName))`).

### Primary Data — Hash per Subject

```
Key:   {tenantId}:consent:{subject}
Type:  Hash

Hash fields:
  c:{category}:{purpose}       → "GRANTED" or "REVOKED"    (category-level consent)
  f:{entity/field}:{purpose}   → "GRANTED" or "REVOKED"    (field-level consent/override)
```

**Examples:**
```
tenant-123:consent:alice → Hash {
  c:PII:marketing         → "GRANTED"     ← from scope (PII, *, *, marketing)
  c:FINANCIAL:analytics   → "GRANTED"     ← from scope (FINANCIAL, *, *, analytics)
  f:User/email:marketing  → "REVOKED"     ← from scope (*, email, User, marketing) — overrides PII
  f:User/ssn:compliance   → "GRANTED"     ← from scope (*, ssn, User, compliance)
}
```

### Reverse Indices for Invalidation

```
{tenantId}:idx:cat:{category}          → Set<"{subject}\0{purpose}">
{tenantId}:idx:field:{entity/field}    → Set<"{subject}\0{purpose}">
{tenantId}:idx:purpose:{purpose}       → Set<"{subject}\0c:{category}" or "{subject}\0f:{entity/field}">
```

Members use null byte (`\0`) as separator. This enables O(1) lookup of affected
subjects on CATEGORY_DELETED, ENTITY_FIELD_REMOVED, etc. — no SCAN needed.

> **⚠️ Null byte in set members:** Jedis handles binary strings fine, but log output will
> show garbled characters. For debug logging of reverse index members, replace `\0` with a
> visible separator like `|` before logging. Never use `\0` in log statements directly.

**Example:** After Alice's hash above, reverse indices contain:
```
tenant-123:idx:cat:PII              → {"alice\0marketing"}
tenant-123:idx:cat:FINANCIAL        → {"alice\0analytics"}
tenant-123:idx:field:User/email     → {"alice\0marketing"}
tenant-123:idx:field:User/ssn       → {"alice\0compliance"}
tenant-123:idx:purpose:marketing    → {"alice\0c:PII", "alice\0f:User/email"}
tenant-123:idx:purpose:analytics    → {"alice\0c:FINANCIAL"}
tenant-123:idx:purpose:compliance   → {"alice\0f:User/ssn"}
```

### Evaluate Path

The caller provides a **concrete** scope with all 4 dimensions filled (no wildcards):
`evaluate(subject, category=PII, field=email, entity=User, purpose=marketing)`

The evaluate path checks **field-level first** (takes precedence), then **category-level**:

```
HMGET {tenantId}:consent:{subject}  f:{entity/field}:{purpose}  c:{category}:{purpose}

→ If f: exists → use its value (GRANTED or REVOKED) — field-level takes precedence
→ Else if c: exists → use its value (GRANTED or REVOKED)
→ Else → NO CONSENT
```

**Example:** `evaluate(alice, PII, email, User, marketing)`:
```
HMGET tenant-123:consent:alice  f:User/email:marketing  c:PII:marketing
→ f:User/email:marketing = "REVOKED"  ← field-level exists, use it
→ Result: REVOKED (despite c:PII:marketing being GRANTED)
```

**Batch evaluation** (N scopes, same subject):
```
HMGET {tenantId}:consent:{subject}
  f:User/email:marketing  c:PII:marketing
  f:User/name:marketing   c:PII:marketing
  f:User/ssn:analytics    c:FINANCIAL:analytics
```
→ 2N hash fields, 1 HMGET call, **O(1) round trip**.

Duplicate `c:` lookups (e.g., `c:PII:marketing` repeated for multiple fields in same category)
can be deduplicated client-side for efficiency.

### Subject Cleanup

```
DEL {tenantId}:consent:{subject}
```
→ **O(1)** — deletes entire hash. Must also clean up reverse indices (HGETALL first to find
all entries, then SREM from each reverse index set).

**Reverse index cleanup parsing:** Each hash field has a prefix that determines which index to SREM from:
- `c:PII:marketing` → SREM from `idx:cat:PII` member `"{subject}\0marketing"` AND `idx:purpose:marketing` member `"{subject}\0c:PII"`
- `f:User/email:marketing` → SREM from `idx:field:User/email` member `"{subject}\0marketing"` AND `idx:purpose:marketing` member `"{subject}\0f:User/email"`

Parse each hash field by splitting on `:` — first segment is the prefix (`c` or `f`), middle is the discriminator (category name or qualified field), last is the purpose.

### Key Prefix Change

**Before:** `{appName}:{tenantId}:consent:{subject}:{entity/field}:{purpose}` (flat string)
**After:** `{tenantId}:consent:{subject}` (Hash)

The `appName` prefix (default "operio") is dropped. `tenantId` provides namespace isolation.
The `AppNameAware` interface and `setAppName()` calls become no-ops or are removed from
the consent decision code path.

### Invalidation on Structural Events

**CATEGORY_DELETED(PII):**
```
SMEMBERS tenant-123:idx:cat:PII → ["alice\0marketing", "bob\0analytics"]

Pipeline:
  HDEL tenant-123:consent:alice c:PII:marketing
  HDEL tenant-123:consent:bob   c:PII:analytics
  SREM tenant-123:idx:purpose:marketing "alice\0c:PII"
  SREM tenant-123:idx:purpose:analytics "bob\0c:PII"
DEL tenant-123:idx:cat:PII

Publish invalidation messages for each deleted entry.
```

**ENTITY_FIELD_REMOVED(User/email):**
```
SMEMBERS tenant-123:idx:field:User/email → ["alice\0marketing"]

Pipeline:
  HDEL tenant-123:consent:alice f:User/email:marketing
  SREM tenant-123:idx:purpose:marketing "alice\0f:User/email"
DEL tenant-123:idx:field:User/email

Publish invalidation messages for each deleted entry.
```

---

## Codebase Context

### Decision Projection Handler (primary refactoring target)

**File:** `tenant/operio-consent-decision-projection-redis/src/main/java/com/koostack/operio/tenant/consent/projection/decision/ConsentDecisionProjectionHandler.java`

**Current dispatch (7 event types — keep all 7, refactor all + add 2 new):**

```java
switch (eventType) {
    case POLICY_GRANTED       → handlePolicyGrantedOrRevoked(tenantId, node);  // REFACTOR: HSET + reverse idx
    case POLICY_REVOKED       → handlePolicyGrantedOrRevoked(tenantId, node);  // REFACTOR: HSET + reverse idx
    case POLICY_ALL_REVOKED   → handlePolicyAllRevoked(tenantId, node);        // REFACTOR: DEL hash + clean indices

    case ENTITY_FIELD_CATEGORY_LINKED   → handleFieldCategoryLinked(...)    // SIMPLIFY: publish-only
    case ENTITY_FIELD_CATEGORY_UNLINKED → handleFieldCategoryUnlinked(...)  // SIMPLIFY: publish-only
    case CATEGORY_DELETED               → handleCategoryDeleted(...)        // REFACTOR: reverse idx cleanup + publish
    case ENTITY_FIELD_REMOVED           → handleEntityFieldRemoved(...)     // REFACTOR: reverse idx cleanup + publish

    // ── NEW handlers needed ──
    case ENTITY_DELETED                 → handleEntityDeleted(...)          // NEW: cleanup ALL fields in entity
    case PURPOSE_DELETED                → handlePurposeDeleted(...)         // NEW: cleanup ALL entries for purpose
}
```

> **⚠️ Missing handlers identified during plan review:**
> - `ENTITY_DELETED` (`consent.entity.deleted`) — currently falls to `default` (ignored). When
>   an entire entity is deleted, ALL field-level consent entries for that entity's fields must
>   be cleaned up. This requires either (a) querying the admin projection for the entity's fields
>   and calling `deleteByField()` for each, or (b) adding a new `idx:entity:{entityName}` reverse
>   index. Option (a) is simpler and avoids another index. See Task 1.6b below.
> - `PURPOSE_DELETED` (`consent.purpose.deleted`) — currently falls to `default` (ignored). The
>   `idx:purpose:{purpose}` reverse index IS populated on every write, but nothing cleans it up
>   on purpose deletion. See Task 1.6c below.

**Current dependencies (remove mappingRepo, rewrite consentRepo):**
- `ConsentPolicyRedisRepository` — ✅ REWRITE for Hash-based storage
- `FieldCategoryMappingRedisRepository` — ❌ REMOVE entirely
- `InvalidationPublisher` — ✅ KEEP (publishes cache invalidation messages)
- `ObjectMapper` — ✅ KEEP (deserializes event payloads)

### ConsentPolicyRedisRepository (major rewrite)

**File:** `plugins/operio-consent-decision-redis/src/main/java/com/koostack/operio/plugin/consent/redis/ConsentPolicyRedisRepository.java`

This is the Redis access layer for consent decisions. Major changes:

| Method | Before | After |
|--------|--------|-------|
| `setFieldConsent()` | `SET` flat key | `HSET` + `SADD` reverse indices |
| `setCategoryConsentForFields()` | `MGET` filter + pipelined `SET` N keys | `HSET` single `c:` entry + `SADD` |
| `getConsent()` | `GET` flat key | `HMGET` 2 hash fields (f: + c:) with precedence |
| `evaluateScopes()` | `MGET` N flat keys | `HMGET` 2N hash fields, resolve precedence |
| `removeAllConsent()` | `SCAN` + pipelined `DEL` | `HGETALL` → `SREM` indices → `DEL` hash |
| `consentKey()` | `{appName}:{tenantId}:consent:...` | `{tenantId}:consent:{subject}` |
| `keyPrefix()` | `appName + ":" + tenantId` | `tenantId` only |
| N/A (NEW) | — | `deleteByCategory()`, `deleteByField()` using reverse indices |

### Admin Projection (no changes needed — already correct)

**File:** `tenant/operio-consent-admin-projection-jooq/src/main/java/com/koostack/operio/tenant/consent/projection/admin/ConsentAdminProjectionHandler.java`

Already handles all metadata events:
- `ENTITY_FIELD_CATEGORY_LINKED` → inserts into `field_category` join table
- `ENTITY_FIELD_CATEGORY_UNLINKED` → deletes from `field_category`
- `CATEGORY_DELETED` → cascading cleanup of category + mappings
- `ENTITY_FIELD_REMOVED` → cascading cleanup of field + mappings

### FieldCategoryMappingRedisRepository (removal candidate)

**File:** `plugins/operio-consent-decision-redis/src/main/java/com/koostack/operio/plugin/consent/redis/FieldCategoryMappingRedisRepository.java`

Maintains two Redis set families:
```
{appName}:{tenantId}:meta:field2cat:{entity}/{field} → Set<category>
{appName}:{tenantId}:meta:cat2field:{category}       → Set<entity/field>
```

After this refactoring, no handler calls any methods on this class. The reverse indices
replace its function. Whether to delete the class entirely or keep it for
`RedisConsentDecisionService` consumers is a Phase 3 decision.

### Redis key patterns (what stays, what changes, what goes)

| Pattern | Type | Disposition | Reason |
|---------|------|-------------|--------|
| `{tenantId}:consent:{subject}` | **Hash** | ✅ **PRIMARY** | Primary consent data |
| `{tenantId}:idx:cat:{category}` | **Set** | ✅ **NEW** | Reverse index for category invalidation |
| `{tenantId}:idx:field:{entity/field}` | **Set** | ✅ **NEW** | Reverse index for field invalidation |
| `{tenantId}:idx:purpose:{purpose}` | **Set** | ✅ **NEW** | Reverse index for purpose invalidation |
| `{prefix}:meta:field2cat:{entity/field}` | Set | ❌ **REMOVE** | Admin-side metadata |
| `{prefix}:meta:cat2field:{category}` | Set | ❌ **REMOVE** | Admin-side metadata |
| `operio:consent-cache-invalidation:{tenantId}` | Pub/Sub | ✅ KEEP | Invalidation pipeline |
| `operio:stream:domain:{0..15}` | Stream | ✅ KEEP | Event transport |

### Key files

| File | Role | Changes |
|------|------|---------|
| `plugins/operio-consent-decision-redis/.../ConsentPolicyRedisRepository.java` | Redis access layer | **Major rewrite**: Hash ops, reverse indices, drop appName |
| `tenant/operio-consent-decision-projection-redis/.../ConsentDecisionProjectionHandler.java` | Projection handler | Refactor all handlers for Hash schema + reverse indices |
| `tenant/operio-consent-decision-projection-redis/.../ConsentDecisionProjectionHandlerTest.java` | Tests | Rewrite for Hash-based assertions |
| `plugins/operio-consent-decision-redis/.../FieldCategoryMappingRedisRepository.java` | Removal candidate | No longer called after refactoring |
| `tenant/operio-consent-admin-command-jooq/.../JooqConsentAdminCommandService.java` | Command service | Add validation + auto-create on grant/revoke |
| `operio-core/.../consent/hydration/` (NEW) | Hydration interfaces | For command-side entity validation |
| `operio-core/.../consent/ExpirationSweeperService.java` (NEW) | Sweeper SPI | Periodic expiry check + revocation |
| `tenant/operio-consent-admin-command-jooq/.../hydration/` (NEW) | JOOQ hydration impls | JooqEntityHydrationRepository, etc. |
| `tenant/operio-tenant-event-jooq/.../EventRepository.java` | Event store | Add `listAggregateIds(type)` method |

---

## Design Decisions

| # | Decision | Choice | Rationale |
|---|----------|--------|-----------|
| 1 | Redis data structure | **Hash per subject** — `{tenantId}:consent:{subject}` | O(1) subject cleanup, natural grouping, efficient HMGET |
| 2 | Consent storage granularity | **Two levels: `c:{cat}:{purpose}` + `f:{e/f}:{purpose}`** | Matches consent model (category or field scope) |
| 3 | Field-level precedence | **Resolved at read time** — check `f:` first, then `c:` | No materialization needed; 2-field HMGET per scope |
| 4 | Key prefix | **`{tenantId}` only** — drop `appName` | Simpler, tenantId provides sufficient isolation |
| 5 | Reverse indices for invalidation | **Redis Sets** — `idx:cat:`, `idx:field:`, `idx:purpose:` | O(1) lookup of affected subjects for bulk cleanup |
| 6 | Reverse index member format | **`{subject}\0{purpose}`** null-byte separator | Enables direct HDEL construction without HSCAN |
| 7 | Category→field materialization | **Eliminated** — category grants stored as single `c:` entry | Read path resolves; no CategoryFieldResolver needed in projection |
| 8 | Consent expiry | **ExpirationSweeperService** — periodic sweep + POLICY_REVOKED | No per-hash-field TTL; sweeper reads from admin query service |
| 9 | Command-side validation | **Hydration repositories** — verify entities exist, auto-create if missing | Prevents dangling references in consent events |
| 10 | FieldCategoryMappingRedisRepository | **Remove from handler, defer full deletion** | Reverse indices replace its function; audit consumers in Phase 3 |
| 11 | Metadata event handlers | **Keep in projection** — publish-only for LINKED/UNLINKED, cleanup + publish for DELETED/REMOVED | Clients need invalidation notifications |
| 12 | Wildcard sentinel | **`"*"` replaces null** for unspecified ConsentScope dimensions | Explicit sentinel; all 4 fields always present |
| 13 | Valid scope patterns | **`(cat, *, *, purpose)` or `(*, field, entity, purpose)` only** | No partial wildcards; clean category vs field branching |
| 14 | Entity/purpose deletion handlers | **Add `ENTITY_DELETED` and `PURPOSE_DELETED`** to projection dispatch | Without these, deleting an entity/purpose leaves orphaned consent entries in Redis |
| 15 | Repo return types for cleanup | **`List<AffectedEntry>`** not `List<CacheInvalidationMessage>` | Separation of concerns — repo layer returns data, handler constructs messages |
| 16 | Hash field key validation | **No `:` or `\0` in names** | Prevents ambiguous parsing of hash field format `prefix:discriminator:purpose` |
| 17 | Pipeline vs Lua for atomicity | **Pipeline (no Lua)** — accept stale index risk | Simpler; stale index members cause harmless extra HDEL; avoid Lua complexity |

---

## Tasks

### Phase 1: Redis Schema + Projection Refactoring

#### Task 1.0 — Update ConsentScope and event records to `*` wildcard sentinel

**Files:**
- `operio-core/.../ConsentScope.java`
- `operio-core/.../events/PolicyGrantedEvent.java`
- `operio-core/.../events/PolicyRevokedEvent.java`
- `tenant/operio-consent-admin-command-jooq/.../JooqConsentAdminCommandService.java`

`ConsentScope` and event records use `"*"` wildcard sentinel for unspecified dimensions:

```java
public record ConsentScope(
    String category,     // specific category name, or "*" for field-scoped
    String fieldName,    // specific field name, or "*" for category-scoped
    String entityName,   // specific entity name, or "*" for category-scoped
    String purposeName   // always specific — never wildcarded
) {
    public static final String WILDCARD = "*";

    /** Category-scoped: (category, *, *, purpose) */
    public static ConsentScope forCategory(String category, String purpose) {
        return new ConsentScope(category, WILDCARD, WILDCARD, purpose);
    }

    /** Field-scoped: (*, field, entity, purpose) */
    public static ConsentScope forField(String field, String entity, String purpose) {
        return new ConsentScope(WILDCARD, field, entity, purpose);
    }

    public boolean isCategoryScoped() { return WILDCARD.equals(fieldName); }
    public boolean isFieldScoped() { return WILDCARD.equals(category); }
}
```

`PolicyGrantedEvent` and `PolicyRevokedEvent` always include all 4 fields (using `"*"`
instead of null). Factory methods: `forCategory()`/`forField()`.

`JooqConsentAdminCommandService.grantConsent()` and `revokeConsent()` construct events
with `"*"` for unspecified dimensions.

#### Task 1.1 — Rewrite `ConsentPolicyRedisRepository` for Hash-based storage

**File:** `plugins/operio-consent-decision-redis/.../ConsentPolicyRedisRepository.java`

This is the largest single change. The repository switches from flat string keys to
Hash-per-subject with reverse indices.

**Key prefix change:**
```java
// BEFORE
String keyPrefix() {
    String tenant = TenantContext.get();
    return tenant != null ? appName + ":" + tenant : appName;
}

// AFTER
String consentHashKey(String subject) {
    return TenantContext.require() + ":consent:" + subject;
}
```

**New methods to implement:**

```java
// --- Category-level grant/revoke ---
void setCategoryConsent(String subject, String category, String purpose, boolean granted) {
    String hashKey = consentHashKey(subject);
    String hashField = "c:" + category + ":" + purpose;
    String value = granted ? GRANTED : REVOKED;

    try (Pipeline p = jedisProvider.get().pipelined()) {
        p.hset(hashKey, hashField, value);
        p.sadd(idxCatKey(category), subject + "\0" + purpose);
        p.sadd(idxPurposeKey(purpose), subject + "\0c:" + category);
        // Pipeline.close() auto-calls sync() — do NOT call p.sync() explicitly
    }
}

// --- Field-level grant/revoke ---
void setFieldConsent(String subject, String entity, String field, String purpose, boolean granted) {
    String hashKey = consentHashKey(subject);
    String qualifiedField = qualifyField(entity, field);
    String hashField = "f:" + qualifiedField + ":" + purpose;
    String value = granted ? GRANTED : REVOKED;

    try (Pipeline p = jedisProvider.get().pipelined()) {
        p.hset(hashKey, hashField, value);
        p.sadd(idxFieldKey(qualifiedField), subject + "\0" + purpose);
        p.sadd(idxPurposeKey(purpose), subject + "\0f:" + qualifiedField);
    }
}

// --- Evaluate (2-step precedence) ---
DecisionDetail evaluate(String subject, String category, String entity, String field, String purpose) {
    String hashKey = consentHashKey(subject);
    String qualifiedField = qualifyField(entity, field);
    String fieldHashField = "f:" + qualifiedField + ":" + purpose;
    String catHashField = "c:" + category + ":" + purpose;

    List<String> values = jedisProvider.get().hmget(hashKey, fieldHashField, catHashField);

    // Field-level takes precedence
    if (values.get(0) != null) {
        return new DecisionDetail(scope, values.get(0), SOURCE_FIELD, null);
    }
    if (values.get(1) != null) {
        return new DecisionDetail(scope, values.get(1), SOURCE_CATEGORY, null);
    }
    return new DecisionDetail(scope, null, RESOLVED_BY_NONE, null);
}

// --- Batch evaluate (N scopes, same subject) ---
Map<ConsentScope, DecisionDetail> evaluateScopes(String subject, Set<ConsentScope> scopes) {
    String hashKey = consentHashKey(subject);
    // Build 2N hash fields (f: and c: for each scope)
    // HMGET all at once
    // Resolve precedence per scope
}

// --- Delete all consent for subject ---
void removeAllConsent(String subject) {
    String hashKey = consentHashKey(subject);
    // HGETALL → parse entries → SREM from all reverse indices → DEL hash
}

// --- Reverse-index cleanup helpers ---
void deleteByCategory(String category) {
    // SMEMBERS idx:cat:{category} → parse → pipeline HDEL + SREM purpose idx → DEL cat idx
    // Returns list of (subject, purpose) pairs that were deleted — caller constructs invalidation messages
}

void deleteByField(String qualifiedField) {
    // SMEMBERS idx:field:{qualifiedField} → parse → pipeline HDEL + SREM purpose idx → DEL field idx
    // Returns list of (subject, purpose) pairs that were deleted — caller constructs invalidation messages
}
```

> **⚠️ Separation of concerns:** `deleteByCategory()` and `deleteByField()` should return
> `List<AffectedEntry>` (a simple record of `subject` + `purpose`) — NOT `CacheInvalidationMessage`.
> The repository layer must not import handler-layer types. The handler constructs messages
> from the returned `AffectedEntry` list.
>
> ```java
> /** Returned by reverse-index cleanup methods to inform callers of what was deleted. */
> public record AffectedEntry(String subject, String purpose) {}
> ```

**Remove/deprecate:**
- `consentKey()` — replaced by `consentHashKey()`
- `setCategoryConsentForFields()` — replaced by `setCategoryConsent()`
- `filterFieldSourcedEntries()` — no longer needed (no materialization)
- `appName` field and `setAppName()` — replaced by `TenantContext.require()`
- All flat-key `SET`/`GET`/`MGET` patterns → Hash ops

**Keep:**
- `qualifyField()` — still used for `f:` hash field construction
- `GRANTED`/`REVOKED` constants
- `SOURCE_FIELD`/`SOURCE_CATEGORY` constants

#### Task 1.2 — Drop `appName` from key prefix

**Files:**
- `ConsentPolicyRedisRepository.java` — remove `appName` field, `setAppName()`, `AppNameAware` impl
- `FieldCategoryMappingRedisRepository.java` — same (if still referenced)
- `ConsentDecisionProjectionHandler.java` — remove `setAppName()` delegation
- Any config classes that pass `appName` into repositories

The key prefix becomes simply `TenantContext.require()`. No more `appName + ":" + tenant`.

#### Task 1.3 — Remove FieldCategoryMappingRedisRepository dependency from handler

**File:** `ConsentDecisionProjectionHandler.java`

- Remove the `mappingRepo` field
- Remove `mappingRepo` from all constructor overloads
- Remove `mappingRepo.setAppName()` from `setAppName()`
- Update constructors:
  ```java
  // After (without mappingRepo)
  public ConsentDecisionProjectionHandler(ConsentPolicyRedisRepository consentRepo)
  public ConsentDecisionProjectionHandler(ConsentPolicyRedisRepository consentRepo, InvalidationPublisher publisher)
  public ConsentDecisionProjectionHandler(ConsentPolicyRedisRepository consentRepo, InvalidationPublisher publisher, ObjectMapper objectMapper)
  ```

**⚠️ Breaking change** — all callers constructing this handler must be updated.

#### Task 1.4 — Refactor `handlePolicyGrantedOrRevoked()` for Hash schema

**File:** `ConsentDecisionProjectionHandler.java`

Replace the current field/category branching logic. The `*` wildcard sentinel determines
which hash field prefix to use:

```java
// BEFORE: field branch writes flat key, category branch materializes via mappingRepo
// AFTER: branch on wildcard sentinel — write c: or f: hash entry

private static final String WILDCARD = "*";

private void handlePolicyGrantedOrRevoked(String tenantId, JsonNode node) {
    String subjectId = requiredText(node, "subjectId");
    String purposeName = requiredText(node, "purposeName");
    boolean granted = requiredBoolean(node, "granted");

    String category = requiredText(node, "category");
    String fieldName = requiredText(node, "fieldName");
    String entityName = requiredText(node, "entityName");

    if (!WILDCARD.equals(fieldName) && !WILDCARD.equals(entityName)) {
        // Field-level: (*, field, entity, purpose)
        consentRepo.setFieldConsent(subjectId, entityName, fieldName, purposeName, granted);
        publishInvalidation(new CacheInvalidationMessage(
            UUID.randomUUID().toString(), tenantId, subjectId,
            entityName, fieldName, null, purposeName,
            granted ? InvalidationAction.GRANTED : InvalidationAction.REVOKED
        ));
    } else if (!WILDCARD.equals(category)) {
        // Category-level: (category, *, *, purpose)
        consentRepo.setCategoryConsent(subjectId, category, purposeName, granted);
        publishInvalidation(new CacheInvalidationMessage(
            UUID.randomUUID().toString(), tenantId, subjectId,
            null, null, category, purposeName,
            granted ? InvalidationAction.GRANTED : InvalidationAction.REVOKED
        ));
    } else {
        log.warn("Policy event has wildcards in all dimensions — skipping");
    }
}
```

**Event payload always includes all 4 fields.** No null checks — `requiredText()` enforces
presence. The `*` sentinel is a real string value, not null.

#### Task 1.5 — Refactor `handlePolicyAllRevoked()` for Hash schema

**File:** `ConsentDecisionProjectionHandler.java`

```java
private void handlePolicyAllRevoked(String tenantId, JsonNode node) {
    String subjectId = requiredText(node, "subjectId");
    consentRepo.removeAllConsent(subjectId);  // DEL hash + clean reverse indices
    publishInvalidation(new CacheInvalidationMessage(
        UUID.randomUUID().toString(), tenantId, subjectId,
        null, null, null, null, InvalidationAction.ALL_REVOKED
    ));
}
```

#### Task 1.6 — Refactor `handleCategoryDeleted()` for reverse-index cleanup

**File:** `ConsentDecisionProjectionHandler.java`

Instead of just publishing invalidation, use the reverse index to delete affected entries:

```java
private void handleCategoryDeleted(String tenantId, JsonNode node) {
    String categoryName = requiredText(node, "categoryName");

    // Delete all c:{category}:* entries across all subjects
    List<AffectedEntry> affected = consentRepo.deleteByCategory(categoryName);

    // Publish invalidation for each deleted entry
    for (AffectedEntry entry : affected) {
        publishInvalidation(new CacheInvalidationMessage(
            UUID.randomUUID().toString(), tenantId, entry.subject(),
            null, null, categoryName, entry.purpose(),
            InvalidationAction.REVOKED  // individual consent was removed
        ));
    }

    // Also publish a broad category-changed notification
    publishInvalidation(new CacheInvalidationMessage(
        UUID.randomUUID().toString(), tenantId, null,
        null, null, categoryName, null, InvalidationAction.CATEGORY_CHANGED
    ));
}
```

> **⚠️ InvalidationAction note:** There is NO `CATEGORY_DELETED` value in the
> `InvalidationAction` enum. The current code uses `CATEGORY_CHANGED` for this event.
> If a more specific action is needed, add `CATEGORY_DELETED` to the enum as part of
> this task. Otherwise, reuse `CATEGORY_CHANGED` (which is what the code does today).
>
> **Decision:** Per-entry invalidations use `REVOKED` (concrete consent removed).
> The broad notification uses `CATEGORY_CHANGED` (metadata event for subscribers to re-evaluate).

**Note:** `consentRepo.deleteByCategory()` returns a list of `AffectedEntry(subject, purpose)`
for each deleted hash entry. The handler constructs `CacheInvalidationMessage` from these.

#### Task 1.7 — Refactor `handleEntityFieldRemoved()` for reverse-index cleanup

**File:** `ConsentDecisionProjectionHandler.java`

Same pattern as Task 1.6 but for field reverse index:

```java
private void handleEntityFieldRemoved(String tenantId, JsonNode node) {
    String entityName = requiredText(node, "entityName");
    String fieldName = requiredText(node, "fieldName");
    String qualifiedField = ConsentPolicyRedisRepository.qualifyField(entityName, fieldName);

    List<AffectedEntry> affected = consentRepo.deleteByField(qualifiedField);

    for (AffectedEntry entry : affected) {
        publishInvalidation(new CacheInvalidationMessage(
            UUID.randomUUID().toString(), tenantId, entry.subject(),
            entityName, fieldName, null, entry.purpose(),
            InvalidationAction.REVOKED
        ));
    }

    publishInvalidation(new CacheInvalidationMessage(
        UUID.randomUUID().toString(), tenantId, null,
        entityName, fieldName, null, null, InvalidationAction.FIELD_REMOVED
    ));
}
```

#### Task 1.8 — Simplify LINKED/UNLINKED handlers to publish-only

**File:** `ConsentDecisionProjectionHandler.java`

These handlers no longer write metadata. With the Hash schema, field-category relationships
don't need to be tracked in Redis — the evaluate path doesn't need them. These handlers
just publish invalidation so subscribers can re-evaluate.

```java
private void handleFieldCategoryLinked(String tenantId, JsonNode node) {
    String entityName = requiredText(node, "entityName");
    String fieldName = requiredText(node, "fieldName");
    String categoryName = requiredText(node, "categoryName");
    publishInvalidation(new CacheInvalidationMessage(
        UUID.randomUUID().toString(), tenantId, null,
        entityName, fieldName, categoryName, null, InvalidationAction.CATEGORY_CHANGED
    ));
}

private void handleFieldCategoryUnlinked(String tenantId, JsonNode node) {
    // Same pattern — publish-only
}
```

#### Task 1.8b — Add `handleEntityDeleted()` for whole-entity cleanup

**File:** `ConsentDecisionProjectionHandler.java`

Currently `ENTITY_DELETED` falls to the `default` case and is silently ignored. When an entire
entity is deleted, all its fields' consent entries must be cleaned up.

**Approach:** The entity deletion event payload contains the entity name but NOT its field list
(that state lived in the aggregate). Two options:

1. **Query the admin projection** for the entity's fields (via `ConsentAdminQueryService.listFields()`)
   and call `consentRepo.deleteByField()` for each. Adds a DB dependency to the projection handler.
2. **Add `idx:entity:{entityName}` reverse index** to track all qualified fields per entity.
   Populated on field-level consent writes. Avoids DB dependency but adds another index.

**Recommended: Option 1** — the entity is being deleted, so the admin projection still has its
fields until the admin handler processes the same event. The decision handler processes events
from the SAME stream, so ordering is not guaranteed. However, the admin projection's DB is
eventually consistent and fields will still be present when this handler runs (the admin handler
processes the event separately).

> **⚠️ Alternative (safer):** If the `EntityAggregate` aggregate is available, hydrate it from
> the event log to get its field list. This avoids a cross-projection dependency.
> Use `TenantEventService.getAggregate()` — but note the projection handler currently doesn't
> have access to `TenantEventService`. This would require adding it as a dependency.
>
> **Simplest option:** The `ENTITY_DELETED` event's aggregate (EntityAggregate) includes the
> field list in its state. Check if the event payload itself includes the fields. If not,
> consider enriching the event at command time (the entity aggregate has `List<FieldEntry>`).

```java
private void handleEntityDeleted(String tenantId, JsonNode node) {
    String entityName = requiredText(node, "entityName");
    // Option depends on design decision — see note above
    // For each field in the entity:
    //   String qualifiedField = qualifyField(entityName, fieldName);
    //   List<AffectedEntry> affected = consentRepo.deleteByField(qualifiedField);
    //   ... publish invalidation for each affected entry ...
}
```

#### Task 1.8c — Add `handlePurposeDeleted()` for purpose cleanup

**File:** `ConsentDecisionProjectionHandler.java`

The `idx:purpose:{purpose}` reverse index IS populated on every consent write (by Tasks 1.1
setCategoryConsent/setFieldConsent). But there's no handler to clean up when a purpose is
deleted. Without this, deleted purposes leave orphaned entries in consent hashes.

```java
private void handlePurposeDeleted(String tenantId, JsonNode node) {
    String purposeName = requiredText(node, "purposeName");

    List<AffectedEntry> affected = consentRepo.deleteByPurpose(purposeName);

    for (AffectedEntry entry : affected) {
        publishInvalidation(new CacheInvalidationMessage(
            UUID.randomUUID().toString(), tenantId, entry.subject(),
            null, null, null, purposeName,
            InvalidationAction.REVOKED
        ));
    }
}
```

**Requires:** Add `deleteByPurpose(String purpose)` to `ConsentPolicyRedisRepository`:
```java
List<AffectedEntry> deleteByPurpose(String purpose) {
    String idxKey = idxPurposeKey(purpose);
    Set<String> members = jedisProvider.get().smembers(idxKey);
    // Parse members: "{subject}\0c:{category}" or "{subject}\0f:{entity/field}"
    // For each: HDEL {tenantId}:consent:{subject} {c:|f:}:{discriminator}:{purpose}
    // Also: SREM from idx:cat:{category} or idx:field:{entity/field}
    // Finally: DEL idxKey
    return affectedEntries;
}
```

#### Task 1.9 — Update `RedisConsentDecisionService`

**File:** `plugins/operio-consent-decision-redis/.../RedisConsentDecisionService.java`

This is the read-side entry point. `evaluateConsent()` currently delegates to
`consentPolicyRepository().evaluateScopes()` which returns `Map<ConsentScope, DecisionDetail>`.

**Changes:**
- `evaluateConsent()` still delegates to `evaluateScopes()` — but the repo's implementation
  now does 2-step Hash-based precedence (field first, then category). The return type
  (`Map<ConsentScope, DecisionDetail>`) is unchanged. The `ConsentResponse` construction
  remains the same. **No changes to the method body.**
- Remove `fieldCategoryMappingRepository()` getter — grep for callers first (Task 3.1 audit).
  If callers exist, deprecate rather than delete.
- Remove `AppNameAware` implementation — `setAppName()` becomes a no-op or is removed.
  The repo no longer uses appName in key prefix.
- Update `configure(JedisProvider)` — no longer instantiates `FieldCategoryMappingRedisRepository`.
  Currently the configure method creates both repos; after this change it only creates `ConsentPolicyRedisRepository`.

**Wiring change:** The service is instantiated in `CoreConsentBundle` (not Spring @Bean):
```java
// CoreConsentBundle (current):
RedisConsentDecisionService decisionService = new RedisConsentDecisionService();
decisionService.configure(jedisProvider);
builder.withConsentProvider(decisionService);
```
No change needed in the bundle unless `configure()` signature changes.

> **⚠️ TenantContext must be set by callers:** The new repo's `consentHashKey()` calls
> `TenantContext.require()` (not `get()`). All callers of `evaluateConsent()` must ensure
> TenantContext is bound. The REST API layer should already do this. Verify.

#### Task 1.10 — Update tests

**File:** `ConsentDecisionProjectionHandlerTest.java`

**Current test structure** (Mockito-based, nested `@Nested` classes):
- Handler constructed as: `new ConsentDecisionProjectionHandler(consentRepo, mappingRepo, publisher)`
- Redis operations mocked via `ConsentPolicyRedisRepository` mock
- Events created as raw JSON strings in `Map<String, String>` via `eventFields(eventType, payload)`
- Uses `ArgumentCaptor` to verify `CacheInvalidationMessage` fields

**Changes:**
- **Update** `setUp()` — remove `mappingRepo` mock, construct handler with 3-arg constructor (no mappingRepo)
- **Remove** all `mappingRepo` mock setup and `verify(mappingRepo.*)` calls
- **Rewrite** `PolicyGranted` tests:
  - Field-level: verify `consentRepo.setFieldConsent(subject, entity, field, purpose, true)` — args now use `"*"` for category
  - Category-level: verify `consentRepo.setCategoryConsent(subject, category, purpose, true)` — args use `"*"` for field/entity
  - Ensure event payloads use `"*"` instead of null for wildcarded dimensions
- **Rewrite** `PolicyRevoked` tests — same pattern as granted
- **Add** `@Nested class CategoryDeleted` — verify `consentRepo.deleteByCategory(categoryName)` returns affected entries, then verify per-entry + broad invalidation messages published
- **Add** `@Nested class EntityFieldRemoved` — verify `consentRepo.deleteByField(qualifiedField)` + invalidation messages
- **Add** `@Nested class EntityDeleted` — verify field cleanup for all fields in entity (design-dependent — see Task 1.8b)
- **Add** `@Nested class PurposeDeleted` — verify `consentRepo.deleteByPurpose(purposeName)` + invalidation messages
- **Add** `@Nested class PolicyAllRevoked` — verify `consentRepo.removeAllConsent(subject)` + invalidation
- **Update** error handling tests — event payloads with `"*"` wildcard values instead of null

**File:** `ConsentPolicyRedisRepositoryTest.java` (existing — uses jedis-mock embedded Redis)

**Add** new test classes for Hash-based operations:
- `@Nested class HashConsent` — setCategoryConsent/setFieldConsent → verify HGET returns correct value
- `@Nested class Precedence` — write category GRANTED + field REVOKED → evaluate returns REVOKED
- `@Nested class BatchEvaluate` — multiple scopes, verify 2N HMGET, deduplicated category lookups
- `@Nested class ReverseIndex` — verify SADD on write, SREM on delete; verify index members match
- `@Nested class DeleteByCategory` — write consent → deleteByCategory → verify HDEL + SREM + DEL index
- `@Nested class DeleteByField` — same pattern for field cleanup
- `@Nested class DeleteByPurpose` — same pattern for purpose cleanup
- `@Nested class RemoveAllConsent` — write multiple entries → removeAllConsent → verify all indices cleaned
- `@Nested class SubjectCleanup` — HGETALL parse test: verify c: and f: prefix parsing for reverse index SREM

### Phase 2: Command Side — Validation + Expiration Sweeper

> **Note:** This phase modifies the command side — `JooqConsentAdminCommandService` and adds
> new SPI interfaces to `operio-core`. It can be implemented independently of Phase 1.

#### Phase 1 context relevant to Phase 2

> **⚠️ `isCategoryScoped()`/`isFieldScoped()` and deprecated `to()` callers:**
> Phase 2 Task 2.4 uses `accessRequest.isCategoryScoped()` and `accessRequest.isFieldScoped()`
> to decide which validations to run. These predicates check for `"*"` (WILDCARD) in
> the respective fields.
>
> **⚠️ `PolicyGrantedEvent` constructor takes individual fields**, not a `ConsentScope` object:
> `PolicyGrantedEvent(subjectId, purposeName, fieldName, entityName, category, granted, expiresAt)`.
> The plan's Task 2.4 code sample shows `new PolicyGrantedEvent(subject, accessRequest, true, expiresAt)`
> — this is pseudocode and doesn't match the actual signature.
>
> **⚠️ `qualifyField("*", field)` now returns `"_default_/{field}"`** — Phase 1 hardening
> fixed `qualifyField()` to treat `"*"` as missing entity. The plan's Gotcha #9 says not to
> call `qualifyField()` when `entityName == "*"`, but the actual implementation handles it safely.
>
> **⚠️ Handler field-level condition changed from plan:** The plan's Task 1.4 code sample checks
> `!WILDCARD.equals(fieldName) && !WILDCARD.equals(entityName)`. Phase 1 hardening relaxed this
> to only `!isWildcard(fieldName)` — a concrete fieldName alone triggers field-level storage,
> even if entity is null/wildcard. `setFieldConsent()` → `qualifyField()` defaults to `_default_`.
>
> **⚠️ `ConsentAdminQueryService.findExpiredPolicies()` does NOT exist yet.** The interface exists
> with `findPoliciesBySubject()`, `listFields()`, `listCategories()`, etc., but no expiration
> query. Task 2.6 must add this method to the interface and implement it in
> `JooqConsentAdminQueryService`.
>
> **⚠️ `EventRepository.listAggregateIds()` does NOT exist yet.** Must be added per Task 2.3.
>
> **⚠️ Hydration repo injection:** `JooqConsentAdminCommandService` receives dependencies via
> `configure(ConsentAdminCommandConfig)`. Hydration repos should be added to the
> `ConsentAdminCommandConfig` record and injected through the same configure path.
>
> **⚠️ `cleanupReverseIndex` uses second-colon parsing** (not the split-with-limit-3 approach
> mentioned in Gotcha #8). This handles purposes with colons correctly but will break if
> category or entity names contain colons. Consider adding name validation (reject `:` and `\0`
> in names) as part of the Task 2.4 command-side validation.

#### Existing infrastructure (no changes needed)

The event sourcing infrastructure already supports aggregate hydration:
- `TenantEventService.getAggregate(AggregateKey, Class<T>)` — hydrates an aggregate from event log
- `AggregateEventApplier.replay(events, type)` — replays events to rebuild state
- `EventRepository.fetchChangesForAggregate(type, id)` — fetches all events for an aggregate
- Snapshot optimization at depth ≥ 20 events (via `SnapshotWorker`)
- Each aggregate has a static `deriveId(naturalKey)` for deterministic UUID generation

#### Aggregate state available for hydration

| Aggregate | Natural Key | State Relevant to Validation |
|-----------|------------|------------------------------|
| `EntityAggregate` | entity name | `List<FieldEntry>` — each has `fieldName`, `Set<String> categories` |
| `CategoryAggregate` | category name | `categoryName`, `description` |
| `ConsentPolicyAggregate` | subject ID | `List<PolicyEntry>` — each has fields for consent state |
| `PurposeAggregate` | purpose name | `purposeName`, `description`, `List<RegulationLink>` |
| `RegulationAggregate` | regulation name | `regulationName`, `fullName`, `jurisdiction` |

#### Task 2.1 — Create hydration repository interfaces in `operio-core`

**Location:** `operio-core/src/main/java/com/koostack/operio/core/data/consent/hydration/`

Create thin repository interfaces for aggregate hydration by natural key. Used by the command
service to validate that referenced entities exist before emitting consent events.

```java
public interface EntityHydrationRepository {
    EntityAggregate findByName(String entityName);
    List<UUID> listEntityAggregateIds();
}

public interface CategoryHydrationRepository {
    CategoryAggregate findByName(String categoryName);
}

public interface ConsentPolicyHydrationRepository {
    ConsentPolicyAggregate findBySubject(String subjectId);
}

public interface PurposeHydrationRepository {
    PurposeAggregate findByName(String purposeName);
}

public interface RegulationHydrationRepository {
    RegulationAggregate findByName(String regulationName);
}
```

Returns `null` when aggregate doesn't exist (no events found).

#### Task 2.2 — Implement JOOQ hydration repositories

**Location:** `tenant/operio-consent-admin-command-jooq/.../hydration/`

Implement each interface using `TenantEventService`:

```java
public class JooqEntityHydrationRepository implements EntityHydrationRepository {
    private final TenantEventService<?> eventService;

    @Override
    public EntityAggregate findByName(String entityName) {
        UUID id = EntityAggregate.deriveId(EntityAggregate.normalizeEntityName(entityName));
        AggregateKey key = new AggregateKey(id, EntityAggregate.AGGREGATE_TYPE, TenantContext.require());
        return eventService.getAggregate(key, EntityAggregate.class);
    }

    @Override
    public List<UUID> listEntityAggregateIds() {
        return eventRepository.listAggregateIds(EntityAggregate.AGGREGATE_TYPE);
    }
}
```

Similar for Category, ConsentPolicy, Purpose, Regulation.

#### Task 2.3 — Add `listAggregateIds` to EventRepository

**File:** `tenant/operio-tenant-event-jooq/.../EventRepository.java`

```java
public List<UUID> listAggregateIds(String aggregateType) {
    return dslContext.selectDistinct(EVENT.AGGREGATE_ID)
        .from(EVENT)
        .where(EVENT.AGGREGATE_TYPE.eq(aggregateType))
        .and(EVENT.TENANT_ID.eq(TenantContext.require()))
        .fetchInto(UUID.class);
}
```

#### Task 2.4 — Add validation to `grantConsent()` and `revokeConsent()`

**File:** `tenant/operio-consent-admin-command-jooq/.../JooqConsentAdminCommandService.java`

Before emitting a `POLICY_GRANTED` or `POLICY_REVOKED` event, validate that all referenced
entities exist. If any don't, auto-create them:

```java
public void grantConsent(String subject, ConsentScope accessRequest, Instant expiresAt) {
    String tenantId = TenantContext.require();

    // Validate + auto-create referenced entities
    ensurePurposeExists(accessRequest.purposeName());
    if (accessRequest.isCategoryScoped()) {
        ensureCategoryExists(accessRequest.category());
    }
    if (accessRequest.isFieldScoped()) {
        ensureEntityAndFieldExist(accessRequest.entityName(), accessRequest.fieldName());
    }

    // Emit event (unchanged payload — no field enrichment needed)
    UUID aggregateId = ConsentPolicyAggregate.deriveId(subject);
    var payload = new PolicyGrantedEvent(subject, accessRequest, true, expiresAt);
    publishEvent(tenantId, ConsentEventTypes.POLICY_GRANTED,
        ConsentPolicyAggregate.AGGREGATE_TYPE, aggregateId, payload);
}

private void ensurePurposeExists(String purposeName) {
    PurposeAggregate existing = purposeHydrationRepo.findByName(purposeName);
    if (existing == null) {
        createPurpose(purposeName);  // emit PURPOSE_CREATED event
    }
}
// Similar for category, entity, field
```

Apply same validation to `revokeConsent()`.

#### Task 2.5 — Create `ExpirationSweeperService` SPI

**Location:** `operio-core/src/main/java/com/koostack/operio/core/data/consent/ExpirationSweeperService.java`

```java
package com.koostack.operio.core.data.consent;

/**
 * Periodic service that sweeps for expired consent policies and issues
 * POLICY_REVOKED commands for each expired policy.
 *
 * Implementation reads from ConsentAdminQueryService to find policies
 * past their expiresAt, then calls ConsentAdminService.revokeConsent()
 * for each expired policy. This causes a POLICY_REVOKED event to flow
 * to the decision projection, which deletes the Redis entry.
 */
public interface ExpirationSweeperService {

    /** Sweep for expired policies in the current tenant and revoke them. */
    void sweep();

    /** Sweep for expired policies across all tenants. */
    void sweepAll();
}
```

#### Task 2.6 — Implement `ExpirationSweeperService`

**Location:** `tenant/operio-consent-expiration-sweeper/` (new module) or alongside existing
tenant command module.

```java
public class JooqExpirationSweeperService implements ExpirationSweeperService {

    private final ConsentAdminQueryService queryService;
    private final ConsentAdminService adminService;

    @Override
    public void sweep() {
        List<ExpiredPolicy> expired = queryService.findExpiredPolicies(Instant.now());
        for (ExpiredPolicy policy : expired) {
            adminService.revokeConsent(
                policy.subjectId(),
                policy.scope()
            );
        }
    }

    @Override
    public void sweepAll() {
        // Iterate all tenants, run sweep() in each tenant context
    }
}
```

> **⚠️ Method signature:** `revokeConsent(String subject, ConsentScope scope)` — the current
> `ConsentAdminService` interface does NOT accept a reason parameter. If audit trail for
> expiration-driven revocations is needed, either:
> (a) Add a `revokeConsent(subject, scope, reason)` overload, or
> (b) Log the reason at the sweeper level before calling revoke.
>
> **⚠️ `findExpiredPolicies()` does not exist yet** on `ConsentAdminQueryService`. This method
> must be added to the interface and implemented in the JOOQ query service. It should query the
> `consent_policy` table for rows where `expires_at < now()` and `status = 'GRANTED'`.

**Scheduling:** The sweeper needs a periodic trigger (e.g., `@Scheduled`, cron, or
event-driven). Implementation details depend on the runtime (Spring Boot, Quarkus, etc.).

#### Task 2.7 — Unit tests for command-side validation and sweeper

- **Test:** `grantConsent()` with non-existent purpose → auto-creates purpose, then emits grant
- **Test:** `grantConsent()` with existing entities → no auto-create, just emits
- **Test:** `revokeConsent()` with non-existent field → auto-creates, then emits revoke
- **Test:** `ExpirationSweeperService.sweep()` — mock queryService to return expired policies,
  verify `revokeConsent()` called for each
- **Test:** `JooqEntityHydrationRepository.findByName()` — verify key derivation + delegation

### Phase 3: Cleanup

#### Task 3.1 — Audit `RedisConsentDecisionService` usage of `FieldCategoryMappingRedisRepository`

`RedisConsentDecisionService` exposes `fieldCategoryMappingRepository()` as a getter. Check if
any downstream consumers (REST API, Quarkus endpoints, other services) call this getter and
use the mapping repo. If none do, the getter can be removed.

#### Task 3.2 — Decide on `FieldCategoryMappingRedisRepository` retention

Based on Task 3.1 findings:
- **If no consumers:** Delete the class entirely. Remove from `operio-consent-decision-redis` plugin.
  Clean up `CaffeineFieldCategoryMappingRepository` analog. Remove integration tests.
- **If consumers exist:** Keep the class but move it to an admin-side module. Document that it
  should not be used by the decision projection.

#### Task 3.3 — Update CONSENT_CACHE_INVALIDATION_PIPELINE.md

Update references to reflect the Hash-based schema, reverse indices, and `{tenantId}` key prefix.
Update any documentation that implies flat string keys or category→field materialization.

---

## Risks

### Reverse index consistency

Reverse indices must be kept in sync with the primary hash data. If a crash occurs between
HSET and SADD (or HDEL and SREM), indices become inconsistent.

**Mitigation:** Use Redis pipelines (atomic within a single connection). For cross-key operations,
accept eventual consistency — a stale reverse index member just means an extra (harmless) HDEL
attempt on a non-existent hash field.

### No per-hash-field TTL

Hash fields don't support individual TTL. Consent expiry is handled by the ExpirationSweeperService.
If the sweeper fails or lags, expired consents remain visible.

**Mitigation:** The sweeper should run frequently (e.g., every 1-5 minutes). The evaluate path
can optionally check `expiresAt` from the admin projection as a safety net. This is a tradeoff
for the Hash schema's other benefits.

### `HSCAN` usage in `removeAllConsent()`

`HGETALL` on a subject's hash to clean up reverse indices is O(N) in hash size. For subjects
with many consent entries, this could be slow.

**Mitigation:** In practice, subjects have < 100 consent entries (limited number of
category+purpose combinations). HGETALL on small hashes is fast.

### Deployment ordering (Phase 1 vs Phase 2)

Phase 1 and Phase 2 are independent. Phase 1 (schema + projection) changes how data is stored.
Phase 2 (validation + sweeper) changes command behavior. They can be deployed in any order.

---

## Pitfalls & Gotchas

### 1. `InvalidationAction` enum gaps

The enum currently has: `GRANTED`, `REVOKED`, `ALL_REVOKED`, `CATEGORY_CHANGED`, `FIELD_REMOVED`.
There is **no** `CATEGORY_DELETED`, `PURPOSE_DELETED`, or `ENTITY_DELETED` value. The current
code reuses `CATEGORY_CHANGED` for category deletion. Decide during implementation whether to
add new values or reuse existing ones. Per-entry cleanup invalidations should use `REVOKED`
(consent was actually removed). Broad notifications use the metadata action.

### 2. `POLICY_AUDIT_DELETED` event — unchanged

The handler currently dispatches `POLICY_AUDIT_DELETED` (for GDPR Article 17 right to erasure).
This handler is **not touched** by this refactoring — it operates on the admin-side audit log,
not the decision cache. Leave it as-is.

### 3. `expiresAt` NOT stored in Redis hash

The Redis hash stores only `"GRANTED"` or
`"REVOKED"` — no expiration timestamp. This is intentional (the sweeper handles expiry). But
it means:
- A race exists: policy expires → sweeper hasn't run yet → evaluate returns stale GRANTED.
- **Optional safety net:** The evaluate path could check `expiresAt` from the admin projection
  DB as a secondary filter. This adds a DB round-trip and defeats the Redis-only performance goal.
  Recommended: accept the staleness window and tune sweeper frequency.

### 4. Concurrent writes to same subject Hash

Two `POLICY_GRANTED` events for the same subject processed by different stream shard readers.
HSET on different hash fields is atomic per field. SADD is set-idempotent. **No race condition.**
But if two events target the SAME hash field (e.g., grant then revoke same scope rapidly),
the last-write-wins. Stream ordering within a single shard guarantees causality for the same
aggregate (consent events are keyed by subject → same shard). **No issue** as long as the
stream consumer doesn't parallelize events within a shard.

### 5. Reverse index memory growth

Each consent write adds members to 2-3 reverse index sets. These sets are never TTL'd. Cleanup
only happens on:
- `deleteByCategory()` / `deleteByField()` / `deleteByPurpose()` — structural deletion events
- `removeAllConsent()` — subject erasure

If the system accumulates many subjects without cleanup, reverse index sets grow proportionally
to `subjects × purposes`. For a system with 1M subjects and 10 purposes, each category index
set could have 10M members. Monitor memory and consider periodic compaction if this becomes
an issue.

### 6. `TenantContext.get()` vs `TenantContext.require()`

The current repos use `TenantContext.get()` (returns null if unset). The new code uses
`TenantContext.require()` (throws if unset). Verify ALL callers of `evaluateConsent()`,
`setFieldConsent()`, `setCategoryConsent()` etc. have TenantContext bound. Known callers:
- `ConsentDecisionProjectionHandler.onEvent()` — calls `TenantContext.runWhere(tenantId, ...)` ✅
- `RedisConsentDecisionService.evaluateConsent()` — called from REST API layer. Verify REST
  interceptor sets TenantContext.
- `CoreConsentBundle` wiring — verify tenant context is set before any repo method call.

### 8. Hash field key format parsing

Hash fields have format `{prefix}:{discriminator}:{purpose}` where the discriminator itself
may contain `:` (if category/entity names contain colons). For safe parsing:
- Split on `:` with limit 3 (prefix, discriminator, purpose)
- OR use a different separator for hash fields (e.g., `|`) — but this differs from the reverse
  index format. Keep consistent.
- **Recommended:** Validate that category names, entity names, field names, and purpose names
  do NOT contain `:` or `\0`. Add validation in `ConsentScope` factory methods or at the
  command service level.

### 9. `qualifyField()` DEFAULT_ENTITY fallback

The existing `qualifyField()` method has a fallback: if entity is null or blank, it uses
`DEFAULT_ENTITY` (likely `"_default"`). With the new wildcard convention, `entityName` is
`"*"` for category-scoped consents. The `qualifyField()` method should NOT be called for
category-scoped consents (only for field-scoped). Ensure the branching in the handler and
repo correctly avoids calling `qualifyField()` when `entityName == "*"`.

### 10. `CoreConsentBundle` handler construction

The handler is constructed in `CoreConsentBundle`, not via Spring `@Bean`. When removing
`mappingRepo` from the constructor (Task 1.3), update `CoreConsentBundle` to match the new
constructor signature. Search for the exact wiring code and update it.

### 11. Pipeline atomicity scope

Redis pipelines batch commands into a single network round trip but are **NOT atomic** across
multiple keys. If the process crashes between HSET (primary data) and SADD (reverse index),
the index becomes inconsistent. This is acceptable because:
- Stale index members cause harmless extra HDEL attempts
- Missing index members mean cleanup on structural delete is incomplete (tolerable)
- For higher consistency, use Lua scripts (EVAL) to make multi-key operations atomic.
  **Not recommended** for initial implementation — adds complexity.

---

## After-Action Report

| Task | Status | Notes |
|------|--------|-------|
| **Phase 1: Redis Schema + Projection** | ✅ Complete | |
| Task 1.0 — Update ConsentScope/events to `*` wildcard | ✅ Done | Added WILDCARD, forCategory/forField factories; deprecated old to() methods |
| Task 1.1 — Rewrite ConsentPolicyRedisRepository for Hash | ✅ Done | Hash-per-subject + reverse indices (cat, field, purpose); Pipeline-based writes |
| Task 1.2 — Drop appName from key prefix | ✅ Done | Merged with 1.1 |
| Task 1.3 — Remove FieldCategoryMappingRedisRepository dependency | ✅ Done | Removed from handler + service; class deleted in Phase 3 |
| Task 1.4 — Refactor handlePolicyGrantedOrRevoked for Hash | ✅ Done | Wildcard-based branching; isWildcard() helper |
| Task 1.5 — Refactor handlePolicyAllRevoked for Hash | ✅ Done | Uses removeAllConsent() |
| Task 1.6 — Refactor handleCategoryDeleted with reverse-index cleanup | ✅ Done | deleteByCategory() + per-entry REVOKED + broad CATEGORY_CHANGED |
| Task 1.7 — Refactor handleEntityFieldRemoved with reverse-index cleanup | ✅ Done | deleteByField() + per-entry REVOKED + broad FIELD_REMOVED |
| Task 1.8 — Simplify LINKED/UNLINKED handlers to publish-only | ✅ Done | No more mappingRepo writes |
| Task 1.8b — Add handleEntityDeleted for whole-entity cleanup | ✅ Done | Publishes broad FIELD_REMOVED; cleanup deferred to per-field events |
| Task 1.8c — Add handlePurposeDeleted for purpose cleanup | ✅ Done | deleteByPurpose() + per-entry REVOKED |
| Task 1.9 — Update RedisConsentDecisionService | ✅ Done | Removed AppNameAware; deprecated fieldCategoryMappingRepository() |
| Task 1.10 — Update tests | ✅ Done | Rewrote handler + repo + service tests for new Hash API |
| **Phase 1 Post-Commit Hardening** | ✅ Complete | |
| Fix: handler field routing | ✅ Done | Condition required both fieldName AND entityName non-wildcard; null/`"*"` entity misrouted to category. Relaxed to only check fieldName. |
| Fix: qualifyField wildcard | ✅ Done | `"*"` entity produced `"*/field"` instead of `"_default_/field"`. Now treats `"*"` same as null/blank. |
| Fix: cleanupReverseIndex parsing | ✅ Done | `lastColon` parsing broke for purposes with colons. Changed to `secondColon` approach. |
| Fix: dead code + stale docs | ✅ Done | Removed unused `instantOrNull`; fixed stale javadoc in service test. |
| **Phase 2: Command Side — Validation + Sweeper** | ✅ Complete | |
| Task 2.1 — Create hydration repository interfaces | ✅ Done | 5 interfaces in operio-core hydration package (Entity, Category, Purpose, Regulation, ConsentPolicy) |
| Task 2.2 — Implement JOOQ hydration repositories | ✅ Done | 5 JOOQ implementations using TenantEventService.getAggregate(); promoted operio-tenant-event-jooq to impl dep |
| Task 2.3 — Add listAggregateIds to EventRepository | ✅ Done | Parameterized with aggregateType + tenantId; returns List<UUID> |
| Task 2.4 — Add validation to grantConsent/revokeConsent | ✅ Done | Auto-create missing purpose/category/entity+field; all hydration repos required |
| Task 2.5 — Create ExpirationSweeperService SPI | ✅ Done | sweep() + sweepAll() in operio-core |
| Task 2.6 — Implement ExpirationSweeperService | ✅ Done | JooqExpirationSweeperService; findExpiredPolicies added as default method on ConsentAdminQueryService |
| Task 2.7 — Unit tests for validation and sweeper | ✅ Done | 3 Mockito test classes: validation, sweeper, hydration repos |
| **Phase 3: Cleanup** | ✅ Complete | |
| Task 3.1 — Audit RedisConsentDecisionService consumers | ✅ Done | Redis version: zero runtime consumers (getter was @Deprecated, handler removed in Phase 1). Caffeine version: kept — used by embedded sync module. |
| Task 3.2 — Decide FieldCategoryMappingRedisRepository retention | ✅ Done | Deleted FieldCategoryMappingRedisRepository class + test + deprecated getter. Updated DEFAULT_APP_NAME javadoc. Removed mapping repo tests from RedisConsentDecisionServiceTest. Kept CaffeineFieldCategoryMappingRepository (embedded sync depends on it). |
| Task 3.3 — Update CONSENT_CACHE_INVALIDATION_PIPELINE.md | ✅ Done | Updated Redis Key Namespace Reference (Hash + reverse indices), ConsentPolicyRedisRepository description, handler constructor, ACL pattern. |
