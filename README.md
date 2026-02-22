# Operio

**Consent management for Java — pluggable, embeddable, open source.**

Operio is a library that gives your application a complete consent management system: grant and revoke consent per user, evaluate consent decisions at runtime, mask denied fields automatically, and maintain a full audit trail. Everything is built on a plugin architecture so you can swap storage backends, identity providers, and deployment topologies without changing application code.

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)

---

## Why Operio?

- **Library, not a service** — runs inside your JVM. No sidecar, no network hop for consent evaluation.
- **Plugin architecture** — every capability (storage, caching, identity, auditing, masking, discovery) is an SPI you can implement or swap.
- **Bundles for common topologies** — one-liner setup for embedded (H2 + Caffeine) or self-hosted (Postgres + Redis) deployments.
- **Entity-aware scoping** — `User.email` and `Contact.email` are distinct consent targets, not a naming collision.
- **Field-level precedence** — explicit field-level consent always wins over category-level policies.
- **Expiration & TTL** — consent grants and revocations can carry an `expiresAt` timestamp, enforced natively in Redis (TTL) and Caffeine (variable expiry).
- **Dual-write with reconciliation** — relational DB is the source of truth; cache is kept in sync with best-effort writes and periodic reconciliation.

---

## Architecture

```text
┌─────────────────────────────────────────────────────────────────┐
│                          Operio                                 │
│                     (entry point / builder)                     │
├────────────┬──────────────┬──────────────┬──────────────────────┤
│ Write Path │  Read Path   │  Discovery   │     Metadata         │
│            │              │              │                      │
│ SyncService│ DecisionSvc  │ Sensitive    │ appName              │
│ AdminSvc   │ IdentityRes  │ Discovery    │ health checks        │
│            │ AuditLogger  │              │                      │
│            │ MaskingSvc   │              │                      │
│            │ FieldResolver│              │                      │
└─────┬──────┴──────┬───────┴──────┬───────┴──────────────────────┘
      │             │              │
      ▼             ▼              ▼
┌───────────┐ ┌──────────┐ ┌─────────────┐
│ jOOQ      │ │ Redis    │ │ Annotation  │
│ (Postgres,│ │ Caffeine │ │ Scanner     │
│  H2, etc.)│ │          │ │             │
└───────────┘ └──────────┘ └─────────────┘
```

**Write path:** `Operio.grantConsent()` / `revokeConsent()` → `ConsentSyncService` (dual-write to DB + cache) or `ConsentAdminService` (DB-only).

**Read path:** `Operio.access(data)` → resolve identity → discover sensitive fields → evaluate consent → audit → mask denied fields → return.

**Discovery:** `Operio.syncDiscoveredFields()` → scan `@Sensitive` annotations → register fields/categories/links in the admin schema.

---

## Quick Start

### Embedded (zero infrastructure)

Everything runs in-process: H2 for the relational store, Caffeine for the decision cache. No Docker, no Redis, no external database.

```java
// 1. Build an Operio instance with the embedded bundle
Operio operio = Operio.builder()
    .withBundle(new EmbeddedConsentBundle()
        .withReconcileOnStartup())
    .build();

// 2. Grant consent
operio.grantConsent("user-123",
    ConsentScope.to("PII", "email", "User", "marketing"));

// 3. Evaluate consent
ConsentResponse response = operio.consentService()
    .evaluateConsent("user-123", Set.of(
        ConsentScope.to("PII", "email", "User", "marketing")
    ));

// response.granted() contains the email scope
// response.details() contains per-scope DecisionDetail with resolution info
```

**Dependencies** (Gradle):

```groovy
implementation 'com.koostack:operio-bundle-embedded:<version>'
```

### Self-Hosted (Postgres + Redis)

Production topology: Postgres as the relational source of truth, Redis for sub-millisecond consent evaluation via single-`MGET` lookups.

```java
var jooqConfig = new JooqConsentAdminServiceConfig(
    "jdbc:postgresql://localhost:5432/operio", "POSTGRES");
var redisConfig = new RedisConsentDecisionServiceConfig(
    "localhost", 6379);
var discoveryConfig = AnnotationDiscoveryConfig.of(
    User.class, Patient.class);

Operio operio = Operio.builder()
    .withAppName("my-healthcare-app")
    .withBundle(new SelfHostedConsentBundle(
        jooqConfig, redisConfig, discoveryConfig))
    .verifyOnBuild()   // fail-fast if DB or Redis is unreachable
    .build();

// Scan @Sensitive annotations → register in the DB
DiscoveryResult result = operio.syncDiscoveredFields();

// Grant consent with 1-year expiration
operio.grantConsent("patient-42",
    ConsentScope.to("HEALTH", "diagnosis", "Patient", "treatment"),
    Instant.now().plus(Duration.ofDays(365)));

// Revoke consent
operio.revokeConsent("patient-42",
    ConsentScope.to("PII", "email", "Patient", "marketing"));
```

**Dependencies** (Gradle):

```groovy
implementation 'com.koostack:operio-bundle-self-hosted:<version>'
runtimeOnly    'org.postgresql:postgresql:42.7.3'
```

### Granular (pick your own plugins)

Skip bundles and wire individual plugins for full control:

```java
Operio operio = Operio.builder()
    .withAppName("my-app")
    .withConsentProvider("redis", redisConfig)
    .withConsentSyncProvider("consent-sync", syncConfig)
    .withConsentAdminProvider("jooq", jooqConfig)
    .withIdentityProvider("spring-security")
    .withAuditProvider("slf4j")
    .withMaskingProvider("jackson")
    .withSensitiveFieldResolver("annotation")
    .withSensitiveDiscovery("sensitive-annotation-discovery", discoveryConfig)
    .verifyOnBuild()
    .build();
```

---

## Full Read-Path Example

The read path ties together identity resolution, field discovery, consent evaluation, auditing, and data masking in a single call:

```java
// Annotate your domain model
public class Patient {
    @Sensitive(category = "PII", purpose = "contact")
    private String email;

    @Sensitive(category = "HEALTH", purpose = "treatment")
    private String diagnosis;

    private String name; // not sensitive — always visible
}

// Wire the full read path
Operio operio = Operio.builder()
    .withBundle(new EmbeddedConsentBundle())
    .withIdentityProvider("spring-security")
    .withAuditProvider("slf4j")
    .withMaskingProvider("jackson")
    .withSensitiveFieldResolver("annotation",
        new ResolverSensitiveAnnotationConfig(Patient.class))
    .build();

// Single call: resolve identity → discover fields → evaluate → audit → mask
Object masked = operio.access(patient);
// If consent for "email" is denied, masked.email == "***"
// If consent for "diagnosis" is denied, masked.diagnosis == "***"
```

---

## Core Concepts

### ConsentScope

A consent decision is always scoped to four dimensions:

| Field | Description | Example |
|-------|-------------|---------|
| `category` | Logical bucket | `"PII"`, `"HEALTH"`, `"Financial"` |
| `fieldName` | The data field | `"email"`, `"home.street"` |
| `entityName` | Owning domain entity (nullable) | `"User"`, `"Patient"` |
| `purposeName` | Why access is needed | `"marketing"`, `"treatment"` |

```java
// Without entity (backward-compatible)
ConsentScope.to("PII", "email", "marketing");

// With entity (prevents cross-entity collisions)
ConsentScope.to("PII", "email", "User", "marketing");
```

### DecisionDetail

Every evaluated scope produces a `DecisionDetail` describing how the decision was resolved:

- `effectiveStatus` — `"GRANTED"`, `"REVOKED"`, or `null` (no record)
- `resolvedBy` — `"FIELD"` (explicit field-level policy), `"CATEGORY"` (materialized from category), or `"NONE"`
- `expiresAt` — when the governing consent entry expires

### Dual-Write Flow

When you call `grantConsent` or `revokeConsent` through a sync service:

1. **Auto-register** — upsert entity, field, category, and field↔category links (best-effort, skippable)
2. **DB write** — upsert `consent_policy` row (source of truth)
3. **Cache write** — write to Redis (with TTL) or Caffeine (with variable expiry)

If the cache write fails, the DB row stands. The cache is eventually consistent via reconciliation.

### Field-Level Precedence

A direct field-level policy (`SOURCE_FIELD`) always wins over a category-materialized policy (`SOURCE_CATEGORY`). When a category-level grant fans out to individual field keys, any field that already has an explicit field-level entry is skipped.

---

## Plugin Catalog

### Bundles

| Plugin | Name | Description |
|--------|------|-------------|
| `operio-bundle-embedded` | `"embedded"` | Zero-config: H2 + Caffeine, no external infrastructure |
| `operio-bundle-self-hosted` | `"self-hosted"` | Production: jOOQ (Postgres/H2/etc.) + Redis |

### Consent Admin (Write Path)

| Plugin | Name | Description |
|--------|------|-------------|
| `operio-consent-admin-jooq` | `"jooq"` | jOOQ-backed relational admin with Flyway migrations |

### Consent Decision (Read Path)

| Plugin | Name | Priority | Description |
|--------|------|----------|-------------|
| `operio-consent-decision-redis` | `"redis"` | 200 | Single-`MGET` evaluation, native TTL |
| `operio-consent-decision-caffeine` | `"caffeine"` | 100 | In-process cache, variable expiry, zero infrastructure |

### Consent Sync (Dual-Write)

| Plugin | Name | Description |
|--------|------|-------------|
| `operio-consent-sync` | `"consent-sync"` | jOOQ → Redis dual-write with auto-registration |
| `operio-consent-sync-embedded` | `"embedded-sync"` | jOOQ → Caffeine dual-write with reconciliation |

### Identity

| Plugin | Name | Description |
|--------|------|-------------|
| `operio-identity-http` | `"http-header"` | Resolves identity from HTTP headers (`X-User-Id`, `Authorization`) |
| `operio-identity-spring-security` | `"spring-security"` | Resolves identity from Spring Security context |

### Audit

| Plugin | Name | Description |
|--------|------|-------------|
| `operio-audit-slf4j` | `"slf4j"` | Logs access granted/denied/alert events via SLF4J |

### Masking

| Plugin | Name | Description |
|--------|------|-------------|
| `operio-masking-jackson` | `"jackson"` | Path-aware Jackson tree masking with type-safe replacements |

### Discovery & Resolution

| Plugin | Name | Description |
|--------|------|-------------|
| `operio-discovery-sensitive-annotation` | `"sensitive-annotation-discovery"` | Scans `@Sensitive` annotations, produces `DiscoveryResult` |
| `operio-resolver-sensitive-annotation` | `"annotation"` | Runtime field resolver for `@Sensitive`-annotated objects |

### Shared Infrastructure

| Plugin | Description |
|--------|-------------|
| `operio-jooq-common` | `JooqContextFactory`, `JooqDataSourceFactory`, `JooqCodegenRunner`, Flyway integration |
| `operio-redis-common` | `RedisContextFactory`, `RedisClientFactory`, Jedis pooling |

---

## The `@Sensitive` Annotation

Mark fields or getters to declare them as consent-governed:

```java
@Sensitive(category = "PII", purpose = "marketing")
private String email;

@Sensitive(category = "HEALTH", purpose = "treatment")
private String diagnosis;

@Sensitive  // category and purpose default to empty (registered as-is)
private String phone;
```

Both the annotation-based discovery plugin and the annotation-based field resolver use this annotation. Discovery scans classes at startup to populate the admin schema; the resolver inspects objects at runtime to determine which fields need consent evaluation.

---

## Health Checks

Every plugin has an `isReady()` method. Call `Operio.verifyReady()` to check all configured plugins, or use `.verifyOnBuild()` on the builder for fail-fast startup:

```java
Operio operio = Operio.builder()
    .withBundle(new SelfHostedConsentBundle(jooqConfig, redisConfig))
    .verifyOnBuild()  // throws OperioConfigurationException if unhealthy
    .build();
```

Plugin health check implementations:
- **jOOQ admin** — executes `SELECT 1` against the database
- **Redis decision** — sends `PING`, expects `PONG`
- **Caffeine decision** — returns `true` once configured (no external infra)
- **Sync services** — delegates to the admin service's health check

---

## DB → Cache Reconciliation

The embedded bundle supports automatic cache reconciliation from the database:

```java
// Automatic at startup
Operio operio = Operio.builder()
    .withBundle(new EmbeddedConsentBundle()
        .withReconcileOnStartup(ReconciliationStrategy.FULL_REBUILD))
    .build();

// Manual / on-demand
EmbeddedConsentSyncServiceImpl sync =
    (EmbeddedConsentSyncServiceImpl) operio.consentSyncService();
ReconciliationResult result = sync.reconcileFromDb();
System.out.println(result.summary());
// → "Reconciliation[FULL_REBUILD] completed in 12ms — mappings=5, ..."

// Subject-scoped
sync.reconcileSubjectFromDb("user-123");
```

**Strategies:**
- `FULL_REBUILD` — clears cache, repopulates from DB. Exact mirror, brief read gap.
- `MERGE` — writes DB state over cache without clearing. No read gap, stale entries may linger.

---

## Exception Hierarchy

All Operio exceptions are unchecked and extend `OperioException`:

```text
OperioException (abstract)
├── OperioConfigurationException — fatal config/wiring errors (not retryable)
│   └── OperioAuthException — authentication/authorization failures
└── OperioServiceException — transient infrastructure failures (retryable)
    └── OperioRateLimitException — rate limiting (retryable after backoff)
```

---

## Project Structure

```text
operio/
├── operio-core                          # Core SPIs, models, Operio builder
├── operio-spring-boot-starter           # Spring Boot auto-configuration
├── operio-bundle-embedded               # Embedded bundle (H2 + Caffeine)
├── operio-bundle-self-hosted            # Self-hosted bundle (DB + Redis)
└── plugins/
    ├── operio-jooq-common               # Shared jOOQ/Flyway/HikariCP infra
    ├── operio-redis-common              # Shared Jedis infra
    ├── operio-consent-admin-jooq        # jOOQ admin service + schema
    ├── operio-consent-decision-redis    # Redis decision service
    ├── operio-consent-decision-caffeine # Caffeine decision service
    ├── operio-consent-sync              # Redis dual-write sync
    ├── operio-consent-sync-embedded     # Caffeine dual-write sync
    ├── operio-audit-slf4j               # SLF4J audit logger
    ├── operio-identity-http             # HTTP header identity resolver
    ├── operio-identity-spring-security  # Spring Security identity resolver
    ├── operio-masking-jackson           # Jackson-based data masking
    ├── operio-resolver-sensitive-annotation  # @Sensitive field resolver
    └── operio-discovery-sensitive-annotation # @Sensitive class scanner
```

---

## Building

Requires **Java 25** and **Gradle 9.3.1** (wrapper included).

```bash
# Build everything
./gradlew build

# Run all tests
./gradlew test

# Run tests for a specific plugin
./gradlew :plugins:operio-consent-decision-caffeine:test

# Regenerate jOOQ sources from the Flyway schema
./gradlew :plugins:operio-consent-admin-jooq:generateJooq
```

---

## Writing a Plugin

Every service interface extends `Plugin<T>`, which combines `Configurable<T>`, `NamedPlugin`, and `Prioritized`. To create a custom plugin:

1. **Implement the interface:**

```java
public class MyAuditLogger implements AuditLogger<MyAuditConfig> {

    @Override
    public String getName() { return "my-audit"; }

    @Override
    public int getPriority() { return 100; }

    @Override
    public Class<MyAuditConfig> getConfigurationType() {
        return MyAuditConfig.class;
    }

    @Override
    public void configure(MyAuditConfig config) {
        // initialize with config
    }

    @Override
    public void logAccessGranted(String user, ConsentScope scope) {
        // your implementation
    }

    @Override
    public void logAccessDenied(String user, ConsentScope scope, String reason) {
        // your implementation
    }
}
```

2. **Register via ServiceLoader:**

Create `META-INF/services/com.koostack.operio.core.service.AuditLogger` containing:
```text
com.example.MyAuditLogger
```

3. **Use it:**

```java
Operio operio = Operio.builder()
    .withAuditProvider("my-audit", new MyAuditConfig(...))
    .build();
```

---

## Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-plugin`)
3. Write tests — the project maintains comprehensive test coverage
4. Follow existing package conventions (`com.koostack.operio.plugin.<domain>.<tech>`)
5. Run `./gradlew build` to verify everything compiles and tests pass
6. Open a pull request

### Code Conventions

- Java 25 toolchain, Lombok for boilerplate reduction
- JUnit Jupiter 5.12.1 with `@TestInstance(PER_CLASS)` and nested test classes
- jOOQ 3.19.5, Jedis 5.2.0, Caffeine 3.1.8, Jackson 2.17.0, Flyway 11.3.0
- Plugin implementations throw `OperioServiceException` for transient failures and `OperioConfigurationException` for fatal config errors
- Upsert semantics on all admin schema writes (idempotent, safe to call repeatedly)

---

## License

Licensed under the [Apache License 2.0](LICENSE).