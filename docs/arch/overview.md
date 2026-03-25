# System Architecture Overview

## Introduction

Cveil is a multi-tenant consent management platform that enables organizations to collect, store, evaluate, and audit user consent across data subjects, entities, fields, categories, purposes, jurisdictions, and regulations. It is built as a **modular monolith** using **CQRS + Event Sourcing** patterns, deployed as a single Quarkus application backed by PostgreSQL, Redis, and Keycloak.

## Design Philosophy

### Two-Plane Architecture

The system is organized into two logical planes:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Control Plane (Platform)                        │
│                                                                        │
│  Manages global concerns: tenant lifecycle, IAM, billing, plans,       │
│  usage metrics, and webhook delivery.                                  │
│  Single PostgreSQL schema: "platform"                                  │
│  Single event store: platform_event                                    │
├────────────────────────────────────────────────────────────────────────┤
│                        Data Plane (Tenant)                             │
│                                                                        │
│  Manages per-tenant data: consent records, data catalogues,            │
│  governance policies, subjects, and DSAR workflows.                    │
│  Per-tenant PostgreSQL schema: "t_{tenantId}"                          │
│  Per-tenant event store within schema                                  │
│  Per-tenant Redis key prefix: "{tenantId}:"                            │
└────────────────────────────────────────────────────────────────────────┘
```

### Bounded Context Map

```
                    ┌─────────────────────────────────┐
                    │        Platform (Control)        │
                    │                                  │
                    │  ┌─────────┐  ┌──────────────┐  │
                    │  │  Core   │  │   Billing    │  │
                    │  │ (Tenant │  │ (Stripe,     │  │
                    │  │  Mgmt)  │  │  Invoices)   │  │
                    │  └────┬────┘  └──────┬───────┘  │
                    │       │              │          │
                    │  ┌────┴────┐  ┌──────┴───────┐  │
                    │  │  IAM    │  │   Plans &    │  │
                    │  │ (Users, │  │   Feature    │  │
                    │  │  Roles) │  │   Gates      │  │
                    │  └─────────┘  └──────────────┘  │
                    │                                  │
                    │  ┌─────────┐  ┌──────────────┐  │
                    │  │Metrics  │  │  Webhooks    │  │
                    │  └─────────┘  └──────────────┘  │
                    └───────────────┬──────────────────┘
                                    │ provisions
                    ┌───────────────▼──────────────────┐
                    │         Tenant (Data)             │
                    │                                   │
                    │  ┌────────────────────────────┐   │
                    │  │     Consent Admin          │   │
                    │  │  (Command + Query +        │   │
                    │  │   Projection)              │   │
                    │  └─────────────┬──────────────┘   │
                    │                │ events            │
                    │  ┌─────────────▼──────────────┐   │
                    │  │  Consent Decision Graph    │   │
                    │  │  (Redis Projection)        │   │
                    │  └────────────────────────────┘   │
                    │                                   │
                    │  ┌──────────┐  ┌──────────────┐   │
                    │  │  DSAR    │  │  Catalogue   │   │
                    │  │ (Export, │  │  & Governance │   │
                    │  │  Erase)  │  │              │   │
                    │  └──────────┘  └──────────────┘   │
                    │                                   │
                    │  ┌──────────┐  ┌──────────────┐   │
                    │  │  Authz   │  │    User      │   │
                    │  │(Keycloak)│  │  Management  │   │
                    │  └──────────┘  └──────────────┘   │
                    └───────────────────────────────────┘
```

## Module Dependency Graph

```
cveil-rest-api (Quarkus application — deployment unit)
├── cveil-core (SPI interfaces, domain models, events)
├── cveil-bundle-core (wires production plugin implementations)
│
├── tenant/
│   ├── cveil-consent-admin-command
│   ├── cveil-consent-admin-query-jooq
│   ├── cveil-consent-admin-projection-jooq
│   ├── cveil-consent-decision-projection-redis
│   ├── cveil-consent-admin-jooq-common
│   └── cveil-subject-dsar-jooq
│
├── platform/
│   ├── cveil-core-{schema,command,query-jooq,projection-jooq,jooq-common}
│   ├── cveil-billing-{schema,command,query-jooq,projection-jooq,jooq-common}
│   ├── cveil-iam-{schema,command,query,projection-jooq}
│   ├── cveil-platform-{event-jooq,keycloak,metrics-redis}
│   ├── cveil-plan-query-jooq
│   ├── cveil-payment-proc-stripe
│   └── cveil-webhook-outbound
│
├── common/
│   ├── cveil-jooq (tenant-aware execute listener, data source factory)
│   ├── cveil-redis (TLS-capable Redis client factory)
│   ├── cveil-keycloak (Keycloak admin client utilities)
│   ├── cveil-metrics-worker (metrics sweeper)
│   ├── cveil-discovery-sensitive-annotation (@Sensitive annotation scanner)
│   └── cveil-crypto (AES-256-GCM field encryption)
│
└── sdks/ (external consumers — not part of the server deployment)
    ├── cveil-sdk-java (WASM via Chicory)
    ├── cveil-sdk-js (WASM via Node.js WASI)
    └── cveil-consent-banner (embeddable widget)
```

## Request Lifecycle

A typical authenticated API request follows this path:

```
Client Request
    │
    ▼
RequestTracingFilter           ← Assigns X-Request-Id, sets MDC
    │
    ▼
ApiKeyAuthFilter / DPoP        ← Authenticates: API key (SHA-256) or OIDC+DPoP
    │
    ▼
TenantScopeInterceptor         ← Binds TenantContext via ScopedValue.runWhere()
    │
    ▼
FeatureGateFilter              ← Checks plan-based feature access
    │
    ▼
RateLimitFilter                ← Atomic Lua-scripted Redis INCR+EXPIRE
    │
    ▼
BillableMetricFilter           ← Records billable API usage
    │
    ▼
JAX-RS Resource Method         ← Business logic via CQRS dispatchers
    │
    ▼
CommandActuator / QueryActuator ← Routes to domain service
    │
    ▼
EventService.publishEvent()    ← Persists semantic domain event
    │
    ▼
Outbox Poller → Redis Stream   ← Async projection via stream consumer
    │
    ▼
Projection Handlers            ← Materialize read models (jOOQ/Redis)
```

## Multi-Tenant Isolation Model

| Layer | Isolation Mechanism |
|-------|-------------------|
| **API** | `TenantScopeInterceptor` binds `TenantContext` via Java 21 `ScopedValue` |
| **PostgreSQL** | Per-tenant schema (`t_{tenantId}`), `TenantAwareExecuteListener` sets `search_path` |
| **Redis** | Key prefix `{tenantId}:`, per-tenant ACL users with restricted key patterns |
| **Keycloak** | Per-tenant realm with dedicated client, roles, and user federation |
| **Events** | Tenant-scoped event store within the tenant's PostgreSQL schema |
| **Flyway** | `TenantManager.migrateAllTenants()` runs schema migrations per tenant at deploy time |

## Data Flow: Consent Grant Example

```
1. POST /api/v1/consent/grant
   Body: { subject, entity, field, category, purpose, jurisdiction }

2. ConsentAdminCommandService.grantConsent()
   → Validates scope via hydration repositories
   → Publishes ConsentGrantedEvent via EventService

3. EventService.publishEvent()
   → INSERT into tenant event table (t_{tenantId}.event)
   → Outbox row flagged for stream delivery

4. OutboxPoller (scheduled)
   → Reads undelivered events
   → XADD to Redis stream: cveil:stream:tenant:{tenantId}

5. ConsentAdminProjectionHandler (stream consumer)
   → Materializes consent_policy row in PostgreSQL

6. ConsentDecisionProjectionHandler (stream consumer)
   → HSET in Redis hash: {tenantId}:subject:{subjectId}
   → Updates consent graph for O(1) evaluation

7. Client calls GET /api/v1/consent/evaluate
   → ConsentGraphQueryService reads Redis hash
   → Returns ALLOW/DENY with decision detail
```

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Quarkus over Spring Boot** | Fast startup, low memory, native image support, virtual threads |
| **jOOQ over JPA/Hibernate** | Type-safe SQL, better control over tenant schema routing, no ORM impedance mismatch |
| **Redis Streams over Kafka** | Simpler operations for a modular monolith; co-located with consent cache |
| **ScopedValue over ThreadLocal** | Java 21 structured concurrency; no leaking across virtual threads |
| **Semantic events over JSON Patch** | Downstream projections can make targeted writes without aggregate state |
| **WASM consent engine** | Runs identical logic in Java (Chicory), JS (WASI), and browser (wasm32) |
| **Modular monolith over microservices** | All bounded contexts deploy together; split later if needed |
| **DPoP over plain Bearer** | Sender-constrained tokens prevent token theft/replay attacks |
