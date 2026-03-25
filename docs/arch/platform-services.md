# Platform Services Architecture

## Overview

The platform (control plane) manages all global operations that span tenants: organization lifecycle, identity and access management, billing, plan enforcement, usage metrics, and webhook delivery. Each domain follows the same CQRS pattern with command service, query service, and projection handler.

## Domains

### Platform Core (`platform/cveil-core-*`)

Manages tenant/organization lifecycle.

**Modules:**
- `cveil-core-schema` — Flyway migrations for the platform core schema
- `cveil-core-jooq-common` — Shared jOOQ configuration
- `cveil-core-command` — Command service for tenant operations
- `cveil-core-query-jooq` — Query service for tenant read models
- `cveil-core-projection-jooq` — Projection handler for tenant events

**Key Operations:**
- Register new organization (provisions PostgreSQL schema, Redis ACL, Keycloak realm)
- Update tenant configuration
- Suspend/reactivate tenants
- Delete tenant (cascading cleanup across all infrastructure)
- Generate and manage API keys (SHA-256 hashed, `opr_live_` prefix format)

**Provisioning Flow:**
```
RegisterOrganizationOrchestrator
    │
    ├── 1. CoreCommandService.registerTenant()
    │      → Publishes TenantRegisteredEvent
    │
    ├── 2. TenantManager.provisionInfrastructure()
    │      ├── CREATE SCHEMA t_{tenantId}
    │      ├── Run Flyway consent migrations in new schema
    │      ├── Create Redis ACL user with key pattern restrictions
    │      └── Provision Keycloak realm with client + roles
    │
    ├── 3. IamCommandService.createRootUser()
    │      → Creates admin user in Keycloak realm
    │
    └── 4. BillingCommandService.createSubscription()
           → Creates Stripe subscription for tenant
```

### Platform IAM (`platform/cveil-iam-*`)

Manages users, roles, and authentication across the platform.

**Modules:**
- `cveil-iam-schema` — Flyway migrations for IAM tables
- `cveil-iam-command` — Dispatches commands to Keycloak + event store
- `cveil-iam-query` — Queries user/role read models (jOOQ)
- `cveil-iam-projection-jooq` — Projects IAM events to SQL read models

**Key Operations:**
- User invitation (email-based with Keycloak SMTP integration)
- User registration and profile management
- Role assignment and removal
- Access token generation (OIDC via Keycloak)
- DPoP token validation
- Permission resolution per tenant + role

**Keycloak Integration (`platform/cveil-platform-keycloak`):**
- Per-tenant realm creation and configuration
- Client registration with OIDC settings
- User federation and group management
- SMTP configuration for invitation emails
- Token exchange support

### Platform Billing (`platform/cveil-billing-*`)

Manages subscriptions, invoices, and payment processing via Stripe.

**Modules:**
- `cveil-billing-schema` — Flyway migrations for billing tables
- `cveil-billing-jooq-common` — Shared jOOQ configuration
- `cveil-billing-command` — Command service for billing operations
- `cveil-billing-query-jooq` — Query service for billing read models
- `cveil-billing-projection-jooq` — Projects billing events to SQL read models

**Stripe Integration (`platform/cveil-payment-proc-stripe`):**
- Implements `PaymentProcessorService` SPI from `cveil-core`
- Subscription creation and management
- Invoice generation and retrieval
- Payment method (card) attachment/detachment
- Setup intent creation for secure card collection
- Webhook event processing (subscription updates, payment success/failure)
- Product and price mapping synchronization

**Key Operations:**
- Create, update, cancel subscriptions
- List invoices with line items
- Manage payment methods
- Process Stripe webhooks
- Plan sync (`StripePlanSyncTask`) — syncs Stripe products/prices to local plan records
- Reconciliation (`StripeReconciliationTask`) — periodically reconciles local state with Stripe

### Plans & Feature Gates (`platform/cveil-plan-query-jooq`)

Manages plan tiers and feature gating.

**Plan Features:**
- Each plan defines available features, rate limits, and quotas
- `FeatureGateService` evaluates whether a tenant's plan allows a specific feature
- `FeatureGateFilter` enforces gates at the API layer (returns 403 for gated features)
- Plan display details include pricing tiers for the billing UI

### Platform Metrics (`platform/cveil-platform-metrics-redis`)

Collects and serves usage metrics per tenant.

**Architecture:**
```
API Request
    │
    ▼
BillableMetricFilter           → Records API call to Redis
    │
    ▼
PlatformRedisMetricPublisher   → HINCRBY on metric hashes
    │
    ▼
MetricsSweeperScheduler        → Periodic aggregation (@Scheduled)
    │
    ▼
DefaultMetricsSweeper           → Flushes Redis metrics to SQL
    (common/cveil-metrics-worker)
```

**Metric Types:**
- API calls (total, per-endpoint)
- Consent evaluations
- DSAR requests
- Active subjects
- Storage usage

### Webhook Outbound (`platform/cveil-webhook-outbound`)

Manages outbound webhook delivery for tenant event subscriptions.

**Key Features:**
- Webhook subscription management (URL, events, secret)
- Event delivery with HMAC signature verification
- Exponential backoff retry with configurable limits
- Dead-letter queue for permanently failed deliveries
- `WebhookRetryTask` — scheduled retry of failed webhook deliveries
- Management UI in the dashboard for subscription CRUD and delivery status

## Platform Event Store

The `platform/cveil-platform-event-jooq` module provides the event store for all platform-scoped events. It uses the same `EventService` SPI as tenant events but writes to the `platform` schema's `event` table.

**Stream routing:** All platform events are published to `cveil:stream:platform`.

## Common Modules

### `common/cveil-jooq`
- `TenantAwareExecuteListener` — jOOQ `ExecuteListener` that issues `SET search_path TO t_{tenantId}, public` before each query when `TenantContext` is active
- `JooqDataSourceFactory` — Creates `HikariCP` data sources with TLS support
- Tenant ID sanitization (`[a-zA-Z0-9_]+` regex to prevent SQL injection)

### `common/cveil-redis`
- `RedisClientFactory` — Creates `JedisPooled` instances with optional TLS
- TLS support: PEM CA certificate loading, peer verification, custom `SSLContext`
- Factory methods for plaintext, password, username+password, and TLS configurations

### `common/cveil-keycloak`
- Keycloak admin client wrapper for realm, client, user, and role management
- Shared across platform IAM and tenant authorization modules

### `common/cveil-metrics-worker`
- `DefaultMetricsSweeper` — Reads metric counters from Redis, aggregates them, and persists to PostgreSQL
- Runs on a configurable schedule via `MetricsSweeperScheduler`

### `common/cveil-discovery-sensitive-annotation`
- Scans the classpath for `@Sensitive` annotations on Java record/class fields
- Produces `DiscoveredField` instances for automatic data catalogue population

### `common/cveil-crypto`
- `FieldEncryptor` — AES-256-GCM field-level encryption for sensitive data at rest
- Provides encrypt/decrypt operations for individual field values
