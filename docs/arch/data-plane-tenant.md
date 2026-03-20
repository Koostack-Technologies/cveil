# Data Plane (Tenant) Architecture

## Overview

The data plane encompasses all tenant-scoped modules — the per-tenant services that manage consent, data catalogues, governance policies, subject data, and DSAR workflows. Each tenant operates in a fully isolated environment with its own PostgreSQL schema, Redis key namespace, and Keycloak realm.

## Tenant Isolation

```
Tenant "acme-corp"
├── PostgreSQL: schema "t_acme_corp"
│   ├── event (tenant event store)
│   ├── consent_policy
│   ├── entity, field, category, purpose
│   ├── regulation, jurisdiction
│   ├── subject, subject_consent
│   ├── dsar_audit_log
│   └── audit_log
│
├── Redis: key prefix "acme-corp:"
│   ├── acme-corp:subject:{subjectId}     (consent decision hash)
│   ├── acme-corp:idx:cat:{category}      (reverse index)
│   ├── acme-corp:idx:field:{entity}:{field} (reverse index)
│   └── ACL user "acme-corp" (restricted to key pattern "acme-corp:*")
│
└── Keycloak: realm "acme-corp"
    ├── Client: cveil-backend
    ├── Roles: admin, consent-writer, consent-reader, viewer
    └── Users: tenant members
```

## Module Details

### Consent Admin Command (`cveil-consent-admin-command`)

The write-side service for all consent and catalogue operations.

**Responsibilities:**
- Consent lifecycle: grant, revoke, batch grant/revoke
- Data catalogue management: register/update/remove entities, fields, categories
- Governance management: register/update/remove purposes, regulations, jurisdictions
- Relationship management: field↔category assignments, purpose↔regulation links, regulation↔jurisdiction associations
- Subject management: register, update, remove subjects

**Event Production:**
Each command produces one or more semantic domain events:
```
grantConsent(scope) → ConsentGrantedEvent
registerField(name, entity) → FieldRegisteredEvent
assignFieldCategory(field, category) → FieldCategoryAssignedEvent
```

### Consent Admin Query (`cveil-consent-admin-query-jooq`)

The read-side service for querying the consent administration model.

**Query Capabilities:**
- List consent policies with filtering by subject, entity, field, category, purpose
- Get entity details with associated fields and categories
- Get field details with category assignments
- Get category details with assigned fields
- Get purpose details with linked regulations and policies
- Get regulation details with linked jurisdictions and purposes
- Get jurisdiction details with linked regulations
- Subject consent summary and detailed history
- Audit log queries with date range and actor filtering

### Consent Admin Projection (`cveil-consent-admin-projection-jooq`)

Consumes events from the tenant Redis Stream and materializes them into PostgreSQL read-model tables using jOOQ.

**Projection Logic:**
```
ConsentGrantedEvent    → INSERT INTO consent_policy
ConsentRevokedEvent    → UPDATE consent_policy SET status = 'REVOKED'
EntityRegisteredEvent  → INSERT INTO entity
FieldRegisteredEvent   → INSERT INTO field
FieldCategoryAssignedEvent → INSERT INTO field_category_mapping
...
```

### Consent Decision Projection (`cveil-consent-decision-projection-redis`)

Consumes events from the tenant Redis Stream and materializes the consent decision graph in Redis for O(1) evaluation. See [Consent Engine Architecture](./consent-engine.md) for the Redis key schema and evaluation algorithm.

### Consent Admin jOOQ Common (`cveil-consent-admin-jooq-common`)

Shared infrastructure for all tenant jOOQ modules:
- Flyway migration management for the consent schema
- jOOQ code generation configuration
- Tenant-aware `DSLContext` creation (via `TenantAwareExecuteListener`)
- Common repository base classes

**Flyway Migrations:**
| Version | Description |
|---------|-------------|
| V1 | Core consent tables (entity, field, category, purpose, consent_policy) |
| V2 | Subject management tables |
| V3 | Audit log table |
| V4 | Governance tables (regulation, jurisdiction, relationship mappings) |
| V5 | Field-category mapping table |
| V6 | DSAR audit log table (immutable) |

### Subject DSAR (`cveil-subject-dsar-jooq`)

Implements Data Subject Access Request workflows. See [Consent Engine — DSAR](./consent-engine.md#dsar-data-subject-access-requests) for details.

**Key Components:**
- `JooqSubjectDsarService` — Orchestrates export and erasure operations
- `DsarAuditLogRepository` — CRUD for the immutable DSAR audit log
- Integration with `ConsentAdminCommandService` for bulk revocation
- Integration with Redis for cached key cleanup

### Tenant Authorization (`cveil-authz-keycloak`)

Bridges Keycloak realm tokens to Cveil's authorization model.

**Authorization Flow:**
```
1. OIDC token arrives in Authorization header
2. DpopVerifier validates DPoP proof (if present)
3. Token decoded → Keycloak realm + subject + roles
4. AuthzProvider resolves to AuthorizationContext:
   {
     tenantId: "acme-corp",
     userId: "user-123",
     roles: ["admin", "consent-writer"],
     permissions: ["consent:read", "consent:write", "admin:read", "admin:write"]
   }
5. Resource method checks permissions via @RolesAllowed or manual check
```

**Role → Permission Mapping:**

| Role | Permissions |
|------|------------|
| `viewer` | `consent:read`, `admin:read` |
| `consent-reader` | `consent:read` |
| `consent-writer` | `consent:read`, `consent:write` |
| `admin` | `consent:read`, `consent:write`, `admin:read`, `admin:write` |
| `super-admin` | All permissions |

## Tenant Event Stream

Each tenant has a dedicated Redis Stream:

```
Stream:  cveil:stream:tenant:{tenantId}
Fields:
  eventId:    "12345"
  eventType:  "CONSENT_GRANTED"
  aggregateType: "SubjectConsent"
  aggregateId:   "user-123"
  payload:    "{\"subject\":\"user-123\",\"entity\":\"customers\",\"field\":\"email\",...}"
  version:    "7"
  timestamp:  "1711045632000"
```

Multiple consumer groups process the same stream:
- `consent-admin-projection` — Materializes to PostgreSQL
- `consent-decision-projection` — Materializes to Redis graph

## Data Catalogue Model

The catalogue defines the structure of data that consent applies to:

```
Regulation ←──── Jurisdiction (many-to-many)
    │
    ▼ (linked)
Purpose ←──── Policy
    │
    ▼ (applied to)
Category ←──── Field (many-to-many via field_category_mapping)
    │              │
    │              ▼
    │           Entity (field belongs to an entity)
    │
    ▼
Subject Consent (scope: category/purpose OR entity/field/purpose)
```

This hierarchical model allows organizations to define their data landscape once and then manage consent at the appropriate granularity.
