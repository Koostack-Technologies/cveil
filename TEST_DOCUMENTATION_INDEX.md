# Test Documentation Index

This directory contains comprehensive documentation for the Cveil integration test infrastructure.

## Quick Start

1. **New to testing?** Start here → [INTEGRATION_TEST_QUICK_REFERENCE.md](./INTEGRATION_TEST_QUICK_REFERENCE.md)
2. **Want details?** Read → [INTEGRATION_TEST_GUIDE.md](./INTEGRATION_TEST_GUIDE.md)
3. **Summary overview?** See → [INTEGRATION_TEST_INFRASTRUCTURE_SUMMARY.md](./INTEGRATION_TEST_INFRASTRUCTURE_SUMMARY.md)

## Files Included

### Documentation Files

| File | Purpose | Length | Time |
|------|---------|--------|------|
| **INTEGRATION_TEST_QUICK_REFERENCE.md** | Cheat sheet with commands, code snippets, common patterns | ~300 lines | 5 min |
| **INTEGRATION_TEST_GUIDE.md** | Comprehensive reference with architecture, API, examples, troubleshooting | ~800 lines | 30 min |
| **INTEGRATION_TEST_INFRASTRUCTURE_SUMMARY.md** | This file - key findings, architecture overview, running tests | ~200 lines | 10 min |
| **TEST_DOCUMENTATION_INDEX.md** | Navigation guide (this file) | ~50 lines | 2 min |

### Source Files (Project)

| Path | Purpose | Lines |
|------|---------|-------|
| `build.gradle.kts` | Root Gradle config (settings only) | 20 |
| `settings.gradle.kts` | Project includes, build-logic | 75 |
| `cveil-rest-api/build.gradle.kts` | REST API module: Quarkus 3.22.3, Testcontainers 2.0.3 | 122 |
| `cveil-rest-api/src/test/resources/application.yml` | Test configuration: production mode, placeholder resolution | 106 |
| `cveil-rest-api/src/test/java/.../CveilTestResourceManager.java` | Container lifecycle + Keycloak bootstrap | 370 |
| `cveil-rest-api/src/test/java/.../IntegrationTestSupport.java` | Test utilities: JDBC, Redis, Keycloak, polling | 883 |
| `cveil-rest-api/src/test/java/.../ConsentResourceIT.java` | Example: consent operations (nested @Order pattern) | ~500 |
| `cveil-rest-api/src/test/java/.../PlatformIamResourceIT.java` | Example: user/role management (nested @Order pattern) | ~400 |

## Architecture at a Glance

```
┌─────────────────────────────────────────────────────────────┐
│                    @QuarkusTest Classes                     │
│  (ConsentResourceIT, PlatformIamResourceIT, etc.)           │
└────────┬────────────────────────────────────────────────────┘
         │
         │ uses: RestAssured + Awaitility + AssertJ
         │
┌────────v────────────────────────────────────────────────────┐
│         IntegrationTestSupport (883 lines)                  │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ JDBC Helpers:  query(), countRows(), assertRow*()   │   │
│  │ Redis Helpers: getGraphNode(), getEdges(), streams  │   │
│  │ KC Helpers:    keycloak(), bootstrapRealm()         │   │
│  │ Auth Helpers:  provisionTenantAndLogin(), loginUser │   │
│  │ Polling:       awaitRowExists(), awaitQuery(), etc  │   │
│  └──────────────────────────────────────────────────────┘   │
└────────┬─────────────────────┬──────────────────┬───────────┘
         │                     │                  │
    JDBC/Postgres           Redis              Keycloak
    (HikariCP)            (Jedis)          (Admin Client)
         │                     │                  │
┌────────v─────────┬──────────v────────┬────────v────────────┐
│ Testcontainers  │                    │                    │
├─────────────────┴────────────────────┴────────────────────┤
│  CveilTestResourceManager (QuarkusTestResourceManager)     │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ Start Postgres 16-alpine                             │  │
│  │ Start Redis 7-alpine                                 │  │
│  │ Start Keycloak 26.0.8                                │  │
│  │ Create 'keycloak' schema                             │  │
│  │ Bootstrap 'cveil' realm with cveil-backend client    │  │
│  │ Inject 10 config properties before CDI bootstrap     │  │
│  └──────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
         │                  │                  │
         v                  v                  v
   postgres:5432       redis:6379       keycloak:8080
```

## Key Concepts

### Test Execution Model
1. **Once per test run:** CveilTestResourceManager starts containers
2. **Shared across test classes:** Containers persist (fast subsequent tests)
3. **Isolated schemas:** Test-specific tenants use separate schemas (cveilconsent)
4. **Eventual consistency:** CQRS projections are async; use polling helpers

### Container Stack
- **Postgres 16** with cveil_test database, max_connections=300
- **Redis 7** for consent graph, caches, streams
- **Keycloak 26** for IAM (optional but auto-started)
- All connected via shared Docker network

### Test Configuration
- **Mode:** Production (not embedded H2)
- **Property resolution:** ${cveil.test.*} from CveilTestResourceManager
- **Logging:** WARN default, INFO for com.koostack.cveil + org.flywaydb
- **Ports:** Dynamic (0) to avoid conflicts in CI

## Common Operations

### Provision Tenant + Get JWT
```java
IntegrationTestSupport.AuthInfo auth = IntegrationTestSupport.provisionTenantAndLogin(
    "test-bootstrap-key-for-integration-tests",
    "tenant_id",
    "Display Name"
);
String accessToken = auth.accessToken();
```

### Verify REST → DB → Cache
```java
// 1. Call API
given().header("Authorization", "Bearer " + token)
  .post("/api/v1/endpoint").then().statusCode(201);

// 2. Verify DB (with polling for CQRS lag)
support.awaitRowExists("table", "column", "value");
var row = support.query("SELECT * FROM table WHERE id = ?", id).get(0);

// 3. Verify cache (if using graph projection)
var cached = support.getAdminConsentCache(tenantId, subjectId);
```

### Poll for Eventually-Consistent State
```java
// Wait for row to appear (default timeout: 30s, poll interval: 500ms)
support.awaitRowExists("entity", "entity_name", "user");

// Custom query polling
support.awaitQuery(
  "SELECT COUNT(*) AS cnt FROM items WHERE tenant_id = ?",
  rows -> (long) rows.get(0).get("cnt") >= 5,
  "Should have at least 5 items",
  tenantId
);
```

## Running Tests

```bash
# All tests
./gradlew -p cveil-rest-api test

# Single test class
./gradlew -p cveil-rest-api test --tests ConsentResourceIT

# Single test method
./gradlew -p cveil-rest-api test --tests ConsentResourceIT.FeatureTests.testName

# Verbose output
./gradlew -p cveil-rest-api test --info
```

**Requirements:** Docker must be running (starts Postgres, Redis, Keycloak)

**First run:** 30-60s (container startup + Flyway migrations)

**Subsequent runs:** 5-10s per test class (containers persist)

## 17 Existing Integration Tests

All located in `cveil-rest-api/src/test/java/com/koostack/cveil/rest/integration/`:

**Consent Management:**
- ConsentResourceIT.java — grant/revoke/evaluate/batch operations
- ConsentGraphProjectionIT.java — Redis graph projection
- ConsentDsarIntegrationIT.java — DSAR operations

**IAM & Users:**
- PlatformIamResourceIT.java — user/service-account management, RBAC
- TenantIamResourceIT.java — tenant-scoped operations

**Catalogue & Governance:**
- CatalogueResourceIT.java — entity/category/field registration
- GovernanceResourceIT.java — purpose/regulation management

**Lifecycle & Provisioning:**
- CreateTenantPipelineIT.java — end-to-end tenant creation
- SubjectLifecycleIT.java — subject CRUD
- PlatformLifecycleIT.java — platform-wide lifecycle events

**Infrastructure & Isolation:**
- CacheInvalidationIT.java — cache coherency verification
- RedisTenantAclIsolationIT.java — multi-tenant Redis ACL isolation
- RedisTlsIntegrationIT.java — Redis TLS configuration
- PostgresSslIntegrationIT.java — Postgres SSL/TLS
- DsarTenantIsolationIT.java — DSAR tenant isolation

**End-to-End:**
- ProductionStackIT.java — full production-like integration
- GraphResourceIT.java — graph query/traversal

## Test Infrastructure Summary

| Component | Details |
|-----------|---------|
| **Framework** | Quarkus JUnit5 + Testcontainers |
| **Build Tool** | Gradle 8.14 with Quarkus plugin 3.22.3 |
| **Databases** | Real Postgres 16 + Redis 7 (not mocked) |
| **HTTP Client** | RestAssured |
| **Assertions** | AssertJ 3.27.3 |
| **Polling** | Awaitility 4.2.2 with default 30s timeout |
| **Configuration** | application.yml with ${cveil.test.*} placeholders |
| **Container Mgmt** | CveilTestResourceManager (QuarkusTestResourceManager) |
| **Test Utils** | IntegrationTestSupport (JDBC, Redis, KC, auth, polling) |

## Recommended Reading Path

1. **[INTEGRATION_TEST_QUICK_REFERENCE.md](./INTEGRATION_TEST_QUICK_REFERENCE.md)** ← Start here (5 min)
   - Commands to run tests
   - Code templates
   - Common method signatures

2. **[INTEGRATION_TEST_GUIDE.md](./INTEGRATION_TEST_GUIDE.md)** ← For details (30 min)
   - Architecture deep dive
   - Complete API reference
   - Writing new tests section

3. **Study existing tests**
   - ConsentResourceIT.java — nested @Order pattern
   - PlatformIamResourceIT.java — Keycloak integration

4. **Write your first test** using the template from quick reference

5. **Refer to this index** for file locations and key concepts

## Questions?

- **"How do I run tests?"** → See "Running Tests" section above
- **"How do I write a new test?"** → INTEGRATION_TEST_GUIDE.md → "Writing Integration Tests"
- **"What methods can I use?"** → INTEGRATION_TEST_QUICK_REFERENCE.md → "IntegrationTestSupport — Common Methods"
- **"Why is my test failing?"** → INTEGRATION_TEST_GUIDE.md → "Troubleshooting"
- **"What's the architecture?"** → INTEGRATION_TEST_INFRASTRUCTURE_SUMMARY.md → "Architecture at a Glance"
- **"Where are the source files?"** → This file → "Source Files (Project)" table

---

Last updated: 2024 (automated documentation generation)
