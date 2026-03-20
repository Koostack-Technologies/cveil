<p align="center">
  <strong>Cveil</strong><br>
  <em>Open-source consent management platform for privacy-first applications</em>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License"></a>
  <a href="https://www.java.com/"><img src="https://img.shields.io/badge/Java-21-orange.svg" alt="Java 21"></a>
  <a href="https://quarkus.io/"><img src="https://img.shields.io/badge/Quarkus-3.22-blueviolet.svg" alt="Quarkus"></a>
  <a href="https://www.rust-lang.org/"><img src="https://img.shields.io/badge/Rust-2024-red.svg" alt="Rust"></a>
</p>

---

# Cveil

**Cveil** is a multi-tenant consent management platform built for organizations that need to comply with **GDPR**, **CCPA**, **HIPAA**, and other data privacy regulations. It provides a complete, API-first solution for managing user consent, processing Data Subject Access Requests (DSARs), governing data catalogues, and enforcing consent decisions at runtime — all within a secure, event-sourced, multi-tenant architecture.

## Key Features

- **Consent Lifecycle Management** — Grant, revoke, evaluate, and audit consent across subjects, entities, fields, categories, purposes, and jurisdictions
- **Consent Graph** — Real-time consent decision graph materialized in Redis with O(1) evaluation and field-level precedence resolution
- **Data Subject Access Requests (DSAR)** — GDPR Article 15/17/20 compliant data export and erasure workflows with cryptographic receipts
- **Data Catalogue & Governance** — Register and manage entities, fields, categories, purposes, regulations, and jurisdictions with full CQRS
- **Multi-Tenant Isolation** — Per-tenant PostgreSQL schemas, Redis ACLs, and Keycloak realms with ScopedValue-based tenant context (Java 21)
- **Event Sourcing & CQRS** — Semantic domain events with stream-based projections across all bounded contexts
- **Platform Management** — Organization onboarding, billing (Stripe), IAM, plan management, feature gates, and usage metrics
- **Client-Side Consent Enforcement** — Rust/WASM-based consent evaluation engine with Java and JavaScript SDKs
- **Embeddable Consent Banner** — Drop-in consent collection widget for any website
- **Webhook Delivery** — Outbound webhook delivery with retry, dead-letter, and management UI
- **DPoP Token Security** — Demonstrating Proof-of-Possession tokens for API authentication
- **TLS Everywhere** — End-to-end encryption for PostgreSQL, Redis, and Keycloak connections
- **GraalVM Native Image** — Optional native compilation for sub-second startup

## Architecture Overview

Cveil follows a **CQRS + Event Sourcing** architecture organized into two planes:

| Plane | Scope | Domains |
|-------|-------|---------|
| **Platform (Control Plane)** | Global operations | Core (tenants/orgs), IAM, Billing, Plans, Metrics, Webhooks |
| **Tenant (Data Plane)** | Per-tenant data | Consent Admin, Consent Decisions, DSAR, Catalogue, Governance, User Management |

```
┌─────────────────────────────────────────────────────────────────────┐
│                        cveil-rest-api (Quarkus)                     │
│  JAX-RS Resources · Filters · DPoP · Rate Limiting · Feature Gates  │
├────────────────┬────────────────────────────────────────────────────┤
│  Platform CQRS │              Tenant CQRS                          │
│  ┌───────────┐ │  ┌──────────────┐  ┌──────────────┐              │
│  │ Core      │ │  │ Consent Admin│  │ Consent Graph│              │
│  │ Billing   │ │  │ (Command)    │  │ (Projection) │              │
│  │ IAM       │ │  │ (Query)      │  │ (Redis)      │              │
│  │ Plans     │ │  │ (Projection) │  └──────────────┘              │
│  │ Metrics   │ │  ├──────────────┤  ┌──────────────┐              │
│  │ Webhooks  │ │  │ DSAR         │  │ Authz        │              │
│  └───────────┘ │  └──────────────┘  │ (Keycloak)   │              │
│                │                    └──────────────┘              │
├────────────────┴────────────────────────────────────────────────────┤
│                     cveil-core (SPI Layer)                          │
│  Framework-agnostic interfaces · Domain events · Aggregates         │
├─────────────────────────────────────────────────────────────────────┤
│              Infrastructure                                         │
│  PostgreSQL 16 (TLS) · Redis 7 (TLS) · Keycloak 26 · Stripe        │
└─────────────────────────────────────────────────────────────────────┘
```

> For detailed architecture documentation, see [`docs/arch/`](docs/arch/).

## Technology Stack

| Layer | Technologies |
|-------|-------------|
| **API Runtime** | Java 21, Quarkus 3.22, JAX-RS (RESTEasy Reactive), Virtual Threads |
| **Core Library** | Framework-agnostic Java SPI, Jackson, ScopedValue |
| **Persistence** | PostgreSQL 16, jOOQ, Flyway |
| **Caching & Streams** | Redis 7, Jedis, Lua scripts, Redis Streams |
| **Identity** | Keycloak 26, OIDC, DPoP (RFC 9449), Nimbus JOSE+JWT |
| **Payments** | Stripe API (subscriptions, invoices, webhooks) |
| **WASM Engine** | Rust 2024, `wasm32-wasip1`, Chicory (Java), Node.js WASI |
| **Web Dashboard** | Vanilla JS, Tailwind CSS 4, Vite, D3.js, SSE |
| **Consent Banner** | Lightweight embeddable widget (Vite) |
| **CLI** | Rust, Clap, Axum, Tokio |
| **Build** | Gradle 8.14, Cargo, npm |
| **Testing** | JUnit 5, Testcontainers, Playwright, AssertJ, Mockito, REST Assured |
| **CI/CD** | GitHub Actions (unit, integration, smoke, E2E, examples) |
| **Containerization** | Docker multi-stage builds, Docker Compose, GraalVM Native Image |
| **Code Quality** | Spotless (Google Java Format), Lombok |

## Prerequisites

- **Java 21** (with preview features enabled)
- **Rust** (stable, 2024 edition) with `wasm32-wasip1` target
- **Node.js 20+** and npm
- **Docker** and Docker Compose v2
- **Gradle 8.14** (wrapper included)

## Quick Start

### 1. Clone the repository

```bash
git clone https://github.com/koostacktech/cveil.git
cd cveil
```

### 2. Generate local TLS certificates

```bash
./certs/local/generate-certs.sh
```

### 3. Start the full stack with Docker Compose

```bash
docker compose up --build
```

This starts PostgreSQL, Redis (TLS), Keycloak, and the Cveil API. The API is available at `http://localhost:8080`.

### 4. Bootstrap a development tenant

```bash
# Create a tenant
curl -X POST http://localhost:8080/api/v1/platform/tenants \
  -H "Authorization: Bearer opr_bootstrap_localdev_do_not_use_in_prod" \
  -H "Content-Type: application/json" \
  -d '{"tenantId": "dev_tenant", "displayName": "Dev Tenant", "plan": "free"}'

# Create an API key
curl -X POST http://localhost:8080/api/v1/platform/api-keys \
  -H "Authorization: Bearer opr_bootstrap_localdev_do_not_use_in_prod" \
  -H "Content-Type: application/json" \
  -d '{"tenantId": "dev_tenant", "subject": "dev-admin", "roles": ["admin", "consent-writer"]}'
```

### 5. Explore the API

- **Swagger UI (Tenant API):** `http://localhost:8080/api/v1/docs`
- **Swagger UI (Platform API):** `http://localhost:8080/api/v1/platform/docs`
- **OpenAPI Spec:** `http://localhost:8080/q/openapi`
- **Health Check:** `http://localhost:8080/q/health/ready`
- **Keycloak Admin Console:** `http://localhost:8180` (admin/admin)

### Running Infrastructure Only (IDE Development)

```bash
docker compose up postgres redis keycloak
```

Then run the Quarkus application from your IDE with `--enable-preview` JVM args.

## Development

### Build the project

```bash
# Full build (Java + WASM + Web)
./gradlew build

# Build only the REST API
./gradlew :cveil-rest-api:quarkusBuild

# Build the WASM SDK
cd crates && cargo build --release --target wasm32-wasip1

# Build the web dashboard
cd cveil-web && npm ci && npm run build
```

### Run tests

```bash
# All unit tests (no Docker required)
./gradlew test -PunitOnly

# Integration tests (requires Docker for Testcontainers)
./gradlew test -PintegrationOnly

# Rust tests
cd crates && cargo test

# End-to-end tests (requires running stack)
cd e2e && npm ci && npx playwright install && npx playwright test
```

### Code formatting

```bash
# Check formatting
./gradlew spotlessCheck

# Auto-fix formatting
./gradlew spotlessApply
```

### Quarkus Dev Mode

```bash
./gradlew :cveil-rest-api:quarkusDev
```

## Project Structure

```
cveil/
├── cveil-core/                  # Framework-agnostic SPI layer (interfaces, models, events)
├── cveil-rest-api/              # Quarkus REST API (JAX-RS, filters, producers)
├── cveil-bundle-core/           # Production plugin bundle (jOOQ + Redis)
├── cveil-web/                   # Dashboard UI (Vanilla JS, Tailwind, Vite)
│
├── common/                      # Shared infrastructure modules
│   ├── cveil-jooq/              #   jOOQ utilities and tenant-aware listeners
│   ├── cveil-redis/             #   Redis client factory with TLS support
│   ├── cveil-keycloak/          #   Keycloak admin client wrapper
│   ├── cveil-metrics-worker/    #   Metrics sweeper and aggregation worker
│   └── cveil-discovery-sensitive-annotation/  # @Sensitive annotation discovery
│
├── tenant/                      # Tenant-scoped (data plane) CQRS modules
│   ├── cveil-consent-admin-command/           # Consent command service
│   ├── cveil-consent-admin-query-jooq/        # Consent query service (jOOQ)
│   ├── cveil-consent-admin-projection-jooq/   # Consent admin projection handler
│   ├── cveil-consent-admin-jooq-common/       # Shared jOOQ config for consent
│   ├── cveil-consent-decision-projection-redis/ # Consent graph (Redis projection)
│   ├── cveil-subject-dsar-jooq/               # DSAR service (export + erasure)
│   └── cveil-authz-keycloak/                  # Tenant authorization (Keycloak)
│
├── platform/                    # Platform-scoped (control plane) CQRS modules
│   ├── cveil-core-{schema,command,query-jooq,projection-jooq,jooq-common}/
│   ├── cveil-billing-{schema,command,query-jooq,projection-jooq,jooq-common}/
│   ├── cveil-iam-{schema,command,query,projection-jooq}/
│   ├── cveil-platform-jooq/                   # Platform jOOQ infrastructure
│   ├── cveil-platform-event-jooq/             # Platform event store
│   ├── cveil-platform-keycloak/               # Platform Keycloak integration
│   ├── cveil-platform-metrics-redis/          # Redis-based metrics publisher
│   ├── cveil-plan-query-jooq/                 # Plan and feature gate queries
│   ├── cveil-payment-proc-stripe/             # Stripe payment processor
│   └── cveil-webhook-outbound/                # Outbound webhook delivery
│
├── sdks/                        # Client SDKs
│   ├── cveil-sdk-java/          #   Java SDK (WASM via Chicory runtime)
│   ├── cveil-sdk-js/            #   JavaScript/Node.js SDK (WASI)
│   └── cveil-consent-banner/    #   Embeddable consent banner widget
│
├── crates/                      # Rust workspace
│   ├── cveil-sdk-core/          #   WASM consent evaluation + masking engine
│   └── cveil-cli/               #   CLI daemon and client
│
├── examples/                    # Usage examples
│   └── cveil-java-sdk-examples/ #   Java SDK example code
│
├── e2e/                         # End-to-end Playwright tests
├── bruno-collection/            # Bruno API collection for manual testing
├── build-logic/                 # Gradle convention plugins
├── docker/                      # Docker infrastructure (Postgres init scripts)
├── certs/                       # TLS certificate generation scripts
├── plans/                       # Architecture and design planning documents
└── .github/workflows/           # CI/CD pipelines
```

## API Endpoints

### Tenant API (`/api/v1/`)

| Endpoint Group | Description |
|---------------|-------------|
| `POST/GET /consent/` | Consent grant, revoke, evaluate, batch operations |
| `GET /consent/graph/` | Consent graph queries and visualization |
| `GET/DELETE /dsar/subjects/{id}` | DSAR export and erasure requests |
| `GET/POST /catalogue/` | Entity, field, category, purpose management |
| `GET/POST /governance/` | Regulation, jurisdiction, policy management |
| `GET /reports/` | Audit logs and compliance reports |
| `GET /analytics/` | Consent analytics and metrics |
| `POST /webhooks/` | Webhook subscription management |

### Platform API (`/api/v1/platform/`)

| Endpoint Group | Description |
|---------------|-------------|
| `POST/GET /tenants` | Tenant provisioning and management |
| `POST/GET /iam/` | User management, invitations, roles |
| `POST/GET /billing/` | Subscriptions, invoices, payment methods |
| `GET /plans/` | Available plans and feature gates |
| `GET /metrics/` | Platform and tenant usage metrics |

### Public API

| Endpoint Group | Description |
|---------------|-------------|
| `POST /public/consent/` | Public consent collection (for banner widget) |
| `POST /public/dsar/` | Public DSAR request submission |
| `GET /banner-config/` | Consent banner configuration |

## SDKs

### Java SDK

The Java SDK embeds the Rust consent evaluation engine via WASM (using the [Chicory](https://github.com/nicholasgasior/chicory) pure-Java WASM runtime). It provides local consent evaluation and field masking without network calls.

```java
// See examples/cveil-java-sdk-examples/ for full usage
CveilClient client = CveilClient.builder()
    .apiUrl("https://your-cveil-instance.com")
    .apiKey("your-api-key")
    .build();
```

### JavaScript SDK

```bash
npm install @cveil/sdk-js
```

The JS SDK uses Node.js WASI or browser WASM for local consent evaluation.

### Consent Banner

```html
<script src="https://your-cveil-instance.com/js/cveil-banner.min.js"></script>
```

### CLI

The Rust CLI provides a consent masking daemon that intercepts and masks data based on consent decisions:

```bash
cveil daemon start --config cveil.toml
cveil consent evaluate --subject user123 --field email
```

## Configuration

Cveil is configured via environment variables or YAML configuration. Key settings:

| Variable | Description | Default |
|----------|-------------|---------|
| `CVEIL_MODE` | Operating mode (`production` or `embedded`) | `production` |
| `CVEIL_DB_URL` | PostgreSQL JDBC URL | — |
| `CVEIL_DB_USERNAME` | Database username | — |
| `CVEIL_DB_PASSWORD` | Database password | — |
| `CVEIL_DB_SSL_MODE` | SSL mode (`disable`, `require`, `verify-ca`, `verify-full`) | `require` |
| `CVEIL_REDIS_HOST` | Redis hostname | — |
| `CVEIL_REDIS_PORT` | Redis port | `6379` |
| `CVEIL_REDIS_TLS_ENABLED` | Enable Redis TLS | `false` |
| `CVEIL_KEYCLOAK_URL` | Keycloak server URL | — |
| `CVEIL_STRIPE_SECRET_KEY` | Stripe secret key for billing | — |
| `CVEIL_CORS_ORIGINS` | Allowed CORS origins | `/.*/` |
| `CVEIL_BOOTSTRAP_KEY` | Initial bootstrap API key | — |
| `QUARKUS_HTTP_PORT` | HTTP server port | `8080` |

## Deployment

### Docker

```bash
# Build production image
docker build -t cveil-rest-api .

# Build native image (GraalVM)
docker build -t cveil-rest-api-native -f Dockerfile.native .
```

### Kubernetes

Cveil is designed for Kubernetes deployment. The Docker image includes:
- Health check endpoints (`/q/health/ready`, `/q/health/live`)
- Non-root user execution (UID 1001)
- Configurable via environment variables
- TLS certificate mounting via Kubernetes Secrets

## Security

- **Authentication:** API keys (SHA-256 hashed), OIDC (Keycloak), DPoP tokens (RFC 9449)
- **Authorization:** RBAC with roles (`admin`, `consent-writer`, `consent-reader`, `viewer`, `super-admin`)
- **Tenant Isolation:** Separate PostgreSQL schemas, Redis ACL users, Keycloak realms per tenant
- **Encryption in Transit:** TLS for all database, cache, and IdP connections
- **Rate Limiting:** Atomic Lua-scripted Redis counters (burst + monthly quotas)
- **Request Tracing:** `X-Request-Id` propagation and correlation in all error responses
- **DSAR Compliance:** Immutable audit log for erasure operations (never deleted)

## Compliance

Cveil is designed to help organizations comply with:

- **GDPR** — Articles 6–9 (lawful basis), 15 (right of access), 17 (right to erasure), 20 (data portability), 32 (security)
- **CCPA** — §1798.100 (right to know), §1798.105 (right to delete), §1798.120 (right to opt-out)
- **HIPAA** — §164.312(a)(2)(ii) (encryption in transit), §164.312(b) (audit controls)

## Contributing

We welcome contributions! Please see our contribution guidelines:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Ensure tests pass (`./gradlew test`)
4. Ensure code is formatted (`./gradlew spotlessApply`)
5. Commit your changes and open a pull request

## License

This project is licensed under the **Apache License 2.0** — see the [LICENSE](LICENSE) file for details.

---

<p align="center">
  Built by <a href="https://github.com/koostacktech">KooStack</a>
</p>
