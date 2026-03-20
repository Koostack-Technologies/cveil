# Consent Engine Architecture

## Overview

The consent engine is the core domain of Cveil. It manages the full lifecycle of consent — from granting and revoking consent to real-time evaluation of consent decisions. The engine spans multiple modules and uses a sophisticated Redis-based consent graph for O(1) decision evaluation.

## Consent Model

Consent in Cveil is defined across six dimensions:

```
ConsentScope {
    subject:      "user-123"          // The data subject (person)
    entity:       "customers"         // The data entity (table/collection)
    field:        "email"             // The data field (column/attribute)
    category:     "contact_info"      // The data category (grouping)
    purpose:      "marketing"         // The processing purpose
    jurisdiction: "eu"                // The legal jurisdiction
}
```

### Precedence Rules

Consent can be granted at two scope levels:
1. **Category-level** — `(category, purpose)` — covers all fields in the category
2. **Field-level** — `(entity, field, purpose)` — overrides category-level grants

**Field-level always takes precedence** over category-level. A field-level revocation overrides a category-level grant.

## Module Breakdown

### Consent Admin Command (`tenant/cveil-consent-admin-command`)

Handles all write operations for the consent domain:
- Grant consent (single and batch)
- Revoke consent (single and batch)
- Register/update/remove entities, fields, categories, purposes, regulations, jurisdictions
- Assign/unassign field-to-category mappings
- Link/unlink purposes to regulations

Each command validates input, hydrates the affected aggregate, produces a semantic domain event, and publishes it via the `EventService`.

### Consent Admin Query (`tenant/cveil-consent-admin-query-jooq`)

Reads consent data from the jOOQ-materialized projection:
- Query consent policies with filtering and pagination
- Query entities, fields, categories, purposes with relationships
- Subject history and audit trail queries

### Consent Admin Projection (`tenant/cveil-consent-admin-projection-jooq`)

Consumes tenant Redis Streams and materializes events into PostgreSQL read models:
- `consent_policy` table — per-scope consent records
- Entity, field, category, purpose, regulation, jurisdiction tables
- Relationship mapping tables (field↔category, purpose↔regulation, etc.)

### Consent Decision Projection (`tenant/cveil-consent-decision-projection-redis`)

Consumes tenant Redis Streams and materializes a high-performance consent graph in Redis for O(1) evaluation.

### Consent Admin jOOQ Common (`tenant/cveil-consent-admin-jooq-common`)

Shared jOOQ configuration, data source setup, and Flyway migration management for the consent schema.

## Consent Decision Graph (Redis)

### Redis Key Schema

The decision graph uses a **Hash-per-subject** Redis schema with tenant-prefixed keys:

```
Key:    {tenantId}:subject:{subjectId}
Type:   HASH

Fields:
  c:{category}:{purpose}             → "GRANTED" | "REVOKED"    (category-level)
  f:{entity}:{field}:{purpose}       → "GRANTED" | "REVOKED"    (field-level)
```

### Reverse Indices

For efficient invalidation (e.g., when a category is deleted):

```
Key:    {tenantId}:idx:cat:{category}
Type:   SET
Members: subject IDs that have grants for this category

Key:    {tenantId}:idx:field:{entity}:{field}
Type:   SET
Members: subject IDs that have grants for this field
```

### Evaluation Algorithm

Consent evaluation is a 2-step Redis `HMGET`:

```
1. HMGET {tenantId}:subject:{subjectId}
     f:{entity}:{field}:{purpose}          → field-level decision
     c:{category}:{purpose}                → category-level decision

2. Resolution:
   - If field-level exists → use field-level (takes precedence)
   - Else if category-level exists → use category-level
   - Else → DENY (no consent on record)
```

This provides **O(1) evaluation** with a single Redis round-trip regardless of the total number of consent records.

### Cache Invalidation Pipeline

When consent state changes, the invalidation pipeline ensures the graph stays consistent:

```
ConsentGrantedEvent / ConsentRevokedEvent
    │
    ▼
ConsentDecisionProjectionHandler
    │
    ├── HSET/HDEL on subject hash
    ├── SADD/SREM on reverse index
    └── Publish InvalidationAction to InvalidationSink

CategoryRemovedEvent / FieldRemovedEvent
    │
    ▼
    SMEMBERS reverse index → fan-out HDEL across affected subjects
```

**InvalidationAction** types:
- `GRANTED` — New consent grant
- `REVOKED` — Consent revoked
- `ALL_REVOKED` — All consent for a subject revoked (DSAR erasure)
- `CATEGORY_CHANGED` — Category metadata changed
- `FIELD_REMOVED` — Field removed from catalogue

## DSAR (Data Subject Access Requests)

The DSAR subsystem (`tenant/cveil-subject-dsar-jooq`) implements GDPR Articles 15/17/20:

### Data Export (Article 15, 20)

```
GET /api/v1/dsar/subjects/{id}/export

Returns:
{
  "subject": "user-123",
  "consentPolicies": [...],     // All consent records
  "auditLogEntries": [...],     // Full audit trail
  "cachedKeyCount": 42,         // Redis cache entries
  "exportedAt": "2026-03-19T..."
}
```

### Data Erasure (Article 17)

```
DELETE /api/v1/dsar/subjects/{id}?confirm=DELETE_user-123

Performs 4-step atomic erasure:
1. DELETE consent policies from PostgreSQL
2. DELETE cached keys from Redis (SCAN + pipeline DEL)
3. DELETE audit log entries
4. INSERT into dsar_audit_log (immutable, never deleted)

Returns DsarDeletionReceipt:
{
  "subject": "user-123",
  "deletionRequestId": "uuid",
  "policiesDeleted": 15,
  "cachedKeysDeleted": 42,
  "auditEntriesDeleted": 30,
  "status": "SUCCESS",
  "completedAt": "2026-03-19T..."
}
```

The confirmation token (`DELETE_{subjectId}`) prevents accidental erasure. The `dsar_audit_log` table is **never deleted** — it serves as regulatory proof of compliance.

### Erasure Status

- `SUCCESS` — All stores cleaned
- `PARTIAL_FAILURE` — Some stores failed (best-effort semantics); the receipt records what was cleaned

## Consent Expiration

The `ExpirationSweeperService` periodically scans for expired consent records and publishes `ConsentExpiredEvent` events, which trigger revocation in both the admin projection and the decision graph.

## Consent Graph Query Service

The `ConsentGraphQueryService` provides rich graph queries beyond simple evaluation:
- Subject consent summary (all active grants)
- Subject consent history (grant/revoke timeline)
- Graph visualization data (for the D3.js dashboard)

## Batch Operations

The consent engine supports batch operations for high-throughput scenarios:

```java
// Batch grant/evaluate up to 1000 entries per request
BatchConsentResult result = consentService.batchGrant(List<BatchConsentEntry> entries);
```

Batch operations use pipeline writes for efficiency (Redis pipeline for cache, jOOQ batch for SQL).
