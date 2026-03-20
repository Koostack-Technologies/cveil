# Cveil Architecture Documentation

This directory contains detailed architecture documentation for the Cveil consent management platform. Each document covers a specific subsystem or cross-cutting concern.

## Documents

| Document | Description |
|----------|-------------|
| [Overview](./overview.md) | High-level system architecture, design principles, and bounded context map |
| [Core SPI](./core-spi.md) | Framework-agnostic core library: SPI interfaces, plugin system, domain models |
| [REST API](./rest-api.md) | Quarkus REST API layer: resources, filters, authentication, CDI producers |
| [CQRS & Event Sourcing](./cqrs-event-sourcing.md) | Command/Query separation, semantic domain events, stream projections |
| [Consent Engine](./consent-engine.md) | Consent lifecycle, graph projection, decision evaluation, cache invalidation |
| [Platform Services](./platform-services.md) | Multi-tenant provisioning, IAM, billing (Stripe), plans, metrics, webhooks |
| [Data Plane (Tenant)](./data-plane-tenant.md) | Tenant-scoped modules: consent, DSAR, catalogue, governance, authorization |
| [Infrastructure](./infrastructure.md) | PostgreSQL, Redis, Keycloak, TLS, Docker, CI/CD |
| [Client SDKs & WASM](./client-sdks-wasm.md) | Rust WASM engine, Java SDK (Chicory), JS SDK, consent banner, CLI |
| [Web Dashboard](./web-dashboard.md) | Frontend architecture: modules, DPoP auth, SSE, WASM bridge |

## Architecture Principles

1. **SPI-First Design** — All domain logic is defined as framework-agnostic Java interfaces in `cveil-core`. Runtime adapters (Quarkus, jOOQ, Redis, Keycloak) are plugged in via the SPI layer.
2. **CQRS + Event Sourcing** — Commands produce semantic domain events; projections materialize read models asynchronously via Redis Streams.
3. **Multi-Tenant Isolation** — Tenants are isolated at every layer: PostgreSQL schemas, Redis key prefixes and ACL users, Keycloak realms.
4. **Compliance by Design** — GDPR, CCPA, and HIPAA requirements are embedded in the architecture (immutable audit logs, TLS everywhere, cryptographic DSAR receipts).
5. **Modular Monolith** — Each bounded context is a separate Gradle module with clean dependency boundaries, deployable as a single Quarkus application.
