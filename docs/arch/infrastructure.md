# Infrastructure Architecture

## Overview

Cveil's infrastructure stack consists of PostgreSQL (relational storage and event store), Redis (consent decision cache, rate limiting, metrics, and stream transport), Keycloak (identity provider), and Stripe (payment processing). All connections support TLS encryption for HIPAA and GDPR compliance.

## Infrastructure Components

### PostgreSQL 16

**Role:** Primary relational data store and event store.

**Schema Organization:**
```
Database: cveil
├── platform        — Platform-scoped tables (tenants, billing, IAM, plans)
├── keycloak        — Keycloak internal schema (managed by Keycloak)
├── t_{tenantId}    — Per-tenant schema (consent, catalogue, governance, events)
│   ├── event                    — Tenant event store
│   ├── consent_policy           — Materialized consent records
│   ├── entity                   — Data catalogue entities
│   ├── field                    — Data catalogue fields
│   ├── category                 — Data categories
│   ├── purpose                  — Processing purposes
│   ├── regulation               — Privacy regulations
│   ├── jurisdiction             — Legal jurisdictions
│   ├── field_category_mapping   — Field ↔ Category relationships
│   ├── purpose_regulation       — Purpose ↔ Regulation relationships
│   ├── regulation_jurisdiction  — Regulation ↔ Jurisdiction relationships
│   ├── subject                  — Data subjects
│   ├── audit_log                — Consent audit trail
│   └── dsar_audit_log           — Immutable DSAR proof (never deleted)
└── public          — Shared functions and extensions
```

**Connection Configuration:**
- Connection pooling via HikariCP
- TLS support with configurable SSL modes: `disable`, `require`, `verify-ca`, `verify-full`
- PEM CA certificate and client key mounting for mTLS
- Data checksums enabled (`--data-checksums`)

**Schema Routing:**
The `TenantAwareExecuteListener` (jOOQ `ExecuteListener`) intercepts every SQL statement and issues `SET search_path TO t_{tenantId}, public` when a `TenantContext` is active. Tenant IDs are sanitized with `[a-zA-Z0-9_]+` regex to prevent SQL injection.

### Redis 7

**Role:** Consent decision cache, rate limiting, usage metrics, and event stream transport.

**Key Namespaces:**
```
{tenantId}:subject:{subjectId}        — Consent decision hash (HASH)
{tenantId}:idx:cat:{category}         — Category reverse index (SET)
{tenantId}:idx:field:{entity}:{field} — Field reverse index (SET)
cveil:stream:tenant:{tenantId}        — Tenant event stream (STREAM)
cveil:stream:platform                 — Platform event stream (STREAM)
cveil:ratelimit:burst:{tenantId}:*    — Burst rate limit counters (STRING)
cveil:ratelimit:monthly:{tenantId}:*  — Monthly rate limit counters (STRING)
cveil:metrics:{tenantId}:*            — Usage metric counters (HASH)
```

**Configuration:**
- TLS with PEM CA certificate support
- Per-tenant ACL users with restricted key patterns (`{tenantId}:*`)
- `maxmemory 128mb` with `allkeys-lru` eviction policy
- Persistence disabled for cache workloads (`save ""`, `appendonly no`)
- Client: Jedis (`JedisPooled`) with connection pooling

**Lua Scripts:**
Atomic operations use server-side Lua scripts:
- Rate limiting: `INCR` + conditional `EXPIRE` in a single round-trip
- Consent evaluation: multi-key `HMGET` for precedence resolution

### Keycloak 26

**Role:** Identity provider for platform and tenant users.

**Configuration:**
- Backend database: PostgreSQL (schema `keycloak` in the same database)
- Platform realm: manages platform-level admin users
- Per-tenant realms: provisioned automatically during tenant onboarding
- Features enabled: `token-exchange`, `admin-fine-grained-authz`, `organization`
- SMTP integration for invitation emails
- Client: `cveil-backend` with OIDC + DPoP support

**Realm Structure per Tenant:**
```
Realm: {tenantId}
├── Client: cveil-backend
│   ├── Protocol: openid-connect
│   ├── Access Type: confidential
│   └── Standard Flow + Direct Grant enabled
├── Roles: admin, consent-writer, consent-reader, viewer
└── Users: tenant members with assigned roles
```

### Stripe

**Role:** Payment processing for SaaS billing.

**Integration Points:**
- `cveil-payment-proc-stripe` module implements `PaymentProcessorService` SPI
- Subscription lifecycle management (create, update, cancel)
- Invoice retrieval and line item details
- Payment method management (card attach/detach)
- Setup intents for secure card collection
- Inbound webhook processing (`WebhookResource`)
- Product/price synchronization (`StripePlanSyncTask`)
- State reconciliation (`StripeReconciliationTask`)

## Docker Configuration

### Docker Compose (Local Development)

```yaml
services:
  postgres:     # PostgreSQL 16 Alpine with TLS
  redis:        # Redis 7 Alpine with TLS
  keycloak:     # Keycloak 26 (dev mode)
  cveil-api:    # Quarkus REST API (built from source)
```

**Startup Order:** `postgres` → `keycloak` (depends on postgres) → `redis` → `cveil-api` (depends on all)

### Production Dockerfile

Multi-stage build with two strategies:

```
Stage 1a: Frontend Build (Node.js 20)
  └── npm ci && npm run build → cveil-web.iife.js

Stage 1b: Java Build (Eclipse Temurin 21 JDK)
  ├── Gradle dependency resolution (cached layer)
  ├── Full source copy
  ├── Copy frontend bundle from Stage 1a
  └── Build: quarkusBuild (preferred) or installDist (fallback)

Stage 2: Runtime (Eclipse Temurin 21 JRE)
  ├── Non-root user (cveil:1001)
  ├── entrypoint.sh detects build type and launches
  ├── Health check: /q/health/ready
  └── ENV defaults: ZGC, virtual threads, production mode
```

### Native Image (Dockerfile.native)

GraalVM-based native compilation for sub-second startup:
- Build: `quarkus-micro-image:2.0` base
- Startup time: ~50ms (vs ~2s for JVM)
- Memory footprint: ~50MB RSS (vs ~256MB for JVM)

## TLS Configuration

All production connections use TLS:

| Connection | SSL Mode | Configuration |
|-----------|----------|---------------|
| PostgreSQL | `verify-full` (recommended) | `CVEIL_DB_SSL_MODE`, `CVEIL_DB_SSL_CERT_PATH` |
| Redis | TLS with CA cert | `CVEIL_REDIS_TLS_ENABLED`, `CVEIL_REDIS_TLS_CA_CERT_PATH` |
| Keycloak | HTTPS (reverse proxy) | Standard HTTPS termination |

Local development uses generated self-signed certificates (`./certs/local/generate-certs.sh`).

## CI/CD Pipelines

GitHub Actions workflows in `.github/workflows/`:

| Workflow | Trigger | Description |
|----------|---------|-------------|
| `ci-unit-tests.yml` | Manual dispatch | Java + Rust unit tests (no Docker) |
| `ci-integration-tests.yml` | Manual dispatch | Testcontainers-based integration tests |
| `ci-smoke.yml` | Manual dispatch | Docker Compose smoke tests |
| `ci-e2e.yml` | Manual dispatch | Playwright end-to-end tests |
| `ci-examples.yml` | Manual dispatch | SDK example compilation and execution |

**Test Filtering:**
- `-PunitOnly` — Excludes `*IT.class` (integration tests)
- `-PintegrationOnly` — Includes only `*IT.class`

## Database Initialization

PostgreSQL is initialized via `docker/postgres/init-keycloak-schema.sql`, which creates the `keycloak` schema used by Keycloak. All other schemas are created at runtime:

- Platform schema: Created by Flyway migrations at API startup
- Tenant schemas: Created by `TenantManager` during tenant provisioning
- Tenant migrations: Applied per-tenant by `TenantManager.migrateAllTenants()` at deploy time

## Build System

Gradle 8.14 with custom convention plugins in `build-logic/`:

| Convention | Purpose |
|-----------|---------|
| `buildlogic.java-common-conventions` | Java 21, JUnit 5, Spotless, Lombok, preview features, Testcontainers Docker API version |
| `buildlogic.java-library-conventions` | Extends common for library modules |
| `buildlogic.java-application-conventions` | Extends common for application modules |
| `buildlogic.maven-publish` | Maven Central publication configuration |

**Code Quality:**
- Spotless with Google Java Format (AOSP, 4 spaces)
- Ratcheted formatting (only formats files changed since `origin/main`)
- Unused import removal, annotation formatting
