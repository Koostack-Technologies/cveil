# Cveil Integration Test Infrastructure Guide

## Overview

This project uses **Quarkus** with **Testcontainers** to run production-grade integration tests against real instances of:
- **PostgreSQL 16** (via Testcontainers)
- **Redis 7** (via Testcontainers)
- **Keycloak 26** (via Testcontainers, optional)

The test infrastructure runs in **PRODUCTION MODE** (not embedded mode) to catch real bugs that only appear with actual databases, such as:
- Flyway migration failures on real Postgres
- jOOQ identifier case-sensitivity issues
- Redis consent decision wiring
- Tenant schema routing
- HikariCP credential handling

---

## Architecture Overview

### Key Components

1. **CveilTestResourceManager** — QuarkusTestResourceLifecycleManager
   - Starts Postgres, Redis, and Keycloak containers before tests run
   - Injects connection details as system properties
   - Bootstraps the platform realm in Keycloak
   - Handles container lifecycle (start/stop)

2. **IntegrationTestSupport** — Shared test utilities
   - JDBC query helpers (direct database access)
   - Redis helpers (graph model, streams, cache)
   - Keycloak Admin API helpers
   - Polling/awaiting helpers for eventually-consistent state
   - Tenant provisioning via orchestrator
   - JWT authentication helpers

3. **application.yml** — Test configuration
   - Located at `cveil-rest-api/src/test/resources/application.yml`
   - Runs in production mode with real Postgres + Redis
   - Uses ${cveil.test.*} placeholders resolved by CveilTestResourceManager
   - Disables Quarkus DevServices
   - Configures logging for test visibility

---

## Build System

### Root build.gradle.kts
- Gradle 8.14 (see gradle.properties / gradle-wrapper.properties)
- Simple IDE housekeeping tasks (no actual build steps at root)

### cveil-rest-api/build.gradle.kts
- **Plugins:** `buildlogic.java-application-conventions` + `io.quarkus:3.22.3`
- **Test dependencies:**
  ```gradle
  testImplementation("io.quarkus:quarkus-junit5")
  testImplementation("io.rest-assured:rest-assured")
  testImplementation("org.assertj:assertj-core:3.27.3")
  testImplementation("org.awaitility:awaitility:4.2.2")
  testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.3"))
  testImplementation("org.testcontainers:testcontainers")
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:postgresql")
  ```

### Running Tests
```bash
# Run all integration tests
gradle -p cveil-rest-api test

# Run a specific test class
gradle -p cveil-rest-api test --tests ConsentResourceIT

# Run a specific test method
gradle -p cveil-rest-api test --tests ConsentResourceIT.PrerequisitesSetup.provisionTenantAndLogin
```

---

## Test Configuration

### application.yml (Test Profile)

**Location:** `cveil-rest-api/src/test/resources/application.yml`

**Key settings:**
```yaml
quarkus:
  http:
    port: 0                    # Random port — avoids conflicts in CI
    test-port: 0              # RestAssured discovers actual port automatically
  devservices:
    enabled: false            # Manage containers manually via Testcontainers
  arc:
    unremovable-types: com.koostack.cveil.core.platform.**

cveil:
  mode: production            # Real Postgres + Redis (not embedded H2)
  app-name: cveil-integration-test
  
  db:
    url: ${cveil.test.db.url:jdbc:postgresql://localhost:5432/cveil_test}
    dialect: POSTGRES
    username: ${cveil.test.db.username:cveil_test}
    password: ${cveil.test.db.password:cveil_test}
    
  redis:
    host: ${cveil.test.redis.host:localhost}
    port: ${cveil.test.redis.port:6379}
    tls-enabled: ${cveil.test.redis.tls-enabled:false}
    
  super-admin:
    bootstrap-key: test-bootstrap-key-for-integration-tests
    force-bootstrap: true
    
  multi-tenant:
    enabled: false            # Override per-test via @QuarkusTestResource
    
  keycloak:
    url: ${cveil.test.keycloak.url:}
    admin-username: ${cveil.test.keycloak.admin-username:admin}
    admin-password: ${cveil.test.keycloak.admin-password:admin}
```

**Note:** All `${cveil.test.*}` placeholders are injected by CveilTestResourceManager at test startup.

---

## CveilTestResourceManager Deep Dive

### Container Images
- **Postgres:** `postgres:16-alpine`
- **Redis:** `redis:7-alpine`
- **Keycloak:** `quay.io/keycloak/keycloak:26.0.8`

### Startup Flow

1. **Create shared Docker network** — allows Keycloak to reach Postgres by hostname
2. **Start Postgres container**
   - Database name: `cveil_test`
   - Username/Password: `cveil_test` / `cveil_test_password`
   - Command: `postgres -c max_connections=300`
3. **Create `keycloak` schema** — required by Keycloak tables
4. **Start Redis container**
   - Default port: 6379
   - Waits for "Ready to accept connections" log message
5. **Start Keycloak container**
   - Connects to Postgres via network alias `postgres`
   - Uses `keycloak` schema for its tables
   - Runs in dev mode (`start-dev`)
6. **Bootstrap platform realm** — creates `cveil` realm with:
   - `cveil-backend` confidential client (ROPC + service-account enabled)
   - Default Cveil roles (consent:read, consent:write, admin:*, tenant:*, super-admin)
7. **Return property map** — injected by Quarkus test framework before CDI bootstrap

### Injected Properties
```
cveil.test.db.url              → JDBC URL
cveil.test.db.username         → Postgres username
cveil.test.db.password         → Postgres password
cveil.test.redis.host          → Redis hostname
cveil.test.redis.port          → Redis mapped port
cveil.test.keycloak.url        → Keycloak base URL
cveil.test.keycloak.admin-username
cveil.test.keycloak.admin-password
cveil.test.keycloak.client-secret → auto-generated
```

### Usage Pattern
```java
@QuarkusTest
@QuarkusTestResource(CveilTestResourceManager.class)
class MyIntegrationTest { ... }
```

---

## IntegrationTestSupport API

### Initialization
```java
@Inject CveilConfig config;
static IntegrationTestSupport support;

@BeforeAll
static void initSupport() {
    support = new IntegrationTestSupport(config);          // Platform mode (no tenant)
    // OR
    support = new IntegrationTestSupport(config, tenantId); // Tenant mode
}

@AfterAll
static void closeSupport() {
    support.close();  // Closes Redis and Keycloak clients
}
```

### Keycloak Helpers
```java
support.keycloak()                                    // Lazy-initialized Keycloak admin client

support.awaitKeycloakRealmExists(realmName, timeout)
support.awaitKeycloakUserExists(realmName, email, timeout)
support.awaitKeycloakUserDisabled(realmName, email, timeout)
support.awaitKeycloakUserEnabled(realmName, email, timeout)

support.getKeycloakUsers(realmName)                  // List<UserRepresentation>
support.getKeycloakClients(realmName)                // List<ClientRepresentation>
support.getKeycloakUserRoles(realmName, userId)      // List<RoleRepresentation>

support.bootstrapKeycloakRealm(realmName)            // Idempotent realm setup
```

### JDBC Query Helpers
```java
// Direct JDBC access
List<Map<String, Object>> rows = support.query(
    "SELECT * FROM entity WHERE entity_name = ?",
    "user"
);

// Count rows
long count = support.countRows("entity", "entity_name", "user");
long count = support.countRowsWithTenant("entity", "entity_name", "user");

// Polling assertions (for eventual consistency)
support.assertRowExists("entity", "entity_name", "user");
support.assertRowAbsent("entity", "entity_name", "user");

support.awaitQueryValue(
    "SELECT status FROM user WHERE user_id = ?",
    "status",
    "active",
    userId
);

support.awaitQuery(
    "SELECT * FROM user WHERE tenant_id = ?",
    rows -> rows.size() >= 5,
    "Should have at least 5 users",
    tenantId
);
```

### Catalogue Queries
```java
support.queryEntities()                              // All entities
support.queryCategories()                            // All categories
support.queryFields()                                // All fields with entity names
support.queryFieldCategories(fieldName, entityName)  // Field-category mappings
```

### Consent & Event Queries
```java
support.queryConsentLedger(subjectId)                // Consent events for subject
support.countConsentLedgerEntries(subjectId)

support.queryEvents(aggregateType, eventType)        // All events of a type
support.queryEventsByAggregate(aggregateType)        // All events for aggregate
support.countUnpublishedEvents()
support.awaitAllEventsPublished(timeout)             // Wait for async publishing
```

### Redis Graph Helpers
```java
// Graph nodes (hash fields)
Map<String, String> node = support.getGraphNode(tenantId, "field", "email");

// Graph edges (set members)
Set<String> outEdges = support.getOutgoingEdges(tenantId, "field", "email");
Set<String> inEdges = support.getIncomingEdges(tenantId, "field", "email");

// Edge properties (hash fields)
Map<String, String> edgeProps = support.getEdgeProperties(
    tenantId,
    "field", "email",
    "category", "PII"
);

// Caches
String jsonCache = support.getAdminConsentCache(tenantId, subjectId);
String jsonFieldCache = support.getAdminFieldCache(tenantId, "user", "email");

// Type indexes (sorted sets)
List<String> allFields = support.getTypeIndex(tenantId, "field");

// Redis streams
List<StreamEntry> entries = support.getStreamEntries(tenantId, count);
List<StreamEntry> filtered = support.getStreamEntriesByType(tenantId, eventType);

// Pattern scanning (test-only)
Set<String> keys = support.scanKeys("cveil:*");
```

### Polling Helpers
```java
// Wait for tenant to be fully provisioned
support.awaitTenantReady(tenantId, timeout);

// Wait for API key to be usable (CQRS projection lag)
support.awaitApiKeyUsable(bearerToken, timeout);

// Generic polling
Object state = support.awaitRedisState(
    () -> support.getGraphNode(tenantId, "field", "email"),
    node -> node != null && !node.isEmpty(),
    timeout
);
```

### Tenant Provisioning & Auth
```java
// Provision tenant via orchestrator
String tenantId = IntegrationTestSupport.provisionTenantViaOrchestrator(
    bootstrapKey,
    "my_tenant",
    "My Tenant Display Name",
    "free"
);

// With password (for Keycloak login)
String tenantId = IntegrationTestSupport.provisionTenantViaOrchestrator(
    bootstrapKey,
    "my_tenant",
    "My Tenant Display Name",
    "free",
    "MyPassword123!"
);

// Login user
String accessToken = IntegrationTestSupport.loginUser(email, password);

// Combined: provision + login
IntegrationTestSupport.AuthInfo auth = IntegrationTestSupport.provisionTenantAndLogin(
    bootstrapKey,
    "my_tenant",
    "My Tenant Display Name"
);
// → AuthInfo(accessToken, refreshToken, tenantId, email)
```

---

## Writing Integration Tests

### Minimal Template

```java
package com.koostack.cveil.rest.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.koostack.cveil.rest.config.CveilConfig;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

@QuarkusTest
@QuarkusTestResource(CveilTestResourceManager.class)
@DisplayName("My Feature Integration Test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MyFeatureIT {

    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";
    private static final String TENANT_ID = "my_feature_it";

    private static IntegrationTestSupport support;
    private static String accessToken;

    @Inject
    CveilConfig config;

    @BeforeAll
    static void setup() {
        // Note: Cannot use @Inject here; must init in first @Test
    }

    @Test
    @Order(1)
    @DisplayName("Initialize support and provision tenant")
    void initSupport(CveilConfig config) {
        // Inject via method parameter (TestMethodOrder determines order)
        support = new IntegrationTestSupport(config, TENANT_ID);
        
        IntegrationTestSupport.AuthInfo auth = IntegrationTestSupport.provisionTenantAndLogin(
            BOOTSTRAP_KEY,
            TENANT_ID,
            "My Feature Tenant"
        );
        accessToken = auth.accessToken();
    }

    @Test
    @Order(2)
    @DisplayName("Create resource via REST API")
    void createResource() {
        String response = given()
            .contentType("application/json")
            .header("Authorization", "Bearer " + accessToken)
            .body("{\"name\": \"test-resource\"}")
            .when()
            .post("/api/v1/resources")
            .then()
            .statusCode(201)
            .extract()
            .asString();

        assertThat(response).contains("test-resource");
    }

    @Test
    @Order(3)
    @DisplayName("Verify database state")
    void verifyDatabaseState() {
        // Wait for projection to catch up
        support.awaitRowExists("resource", "resource_name", "test-resource");

        // Query directly
        var rows = support.query(
            "SELECT * FROM resource WHERE resource_name = ?",
            "test-resource"
        );

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("status")).isEqualTo("active");
    }

    @AfterAll
    static void cleanup() {
        if (support != null) {
            support.close();
        }
    }
}
```

### Using Nested Test Classes (Recommended Pattern)

See **ConsentResourceIT.java** and **PlatformIamResourceIT.java** for the full pattern:

```java
@QuarkusTest
@QuarkusTestResource(CveilTestResourceManager.class)
@DisplayName("MyFeature Integration Test")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class MyFeatureIT {

    private static IntegrationTestSupport support;

    // =======================================================================
    // 1. Setup
    // =======================================================================

    @Nested
    @Order(1)
    @DisplayName("1 — Setup")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Setup {
        @Test
        @Order(1)
        void initSupport(CveilConfig config) {
            support = new IntegrationTestSupport(config, TENANT_ID);
        }

        @Test
        @Order(2)
        void provisionTenant() {
            // ... tenant provisioning ...
        }
    }

    // =======================================================================
    // 2. Feature Tests
    // =======================================================================

    @Nested
    @Order(2)
    @DisplayName("2 — Feature Tests")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class FeatureTests {
        @Test
        @Order(1)
        void testFeatureA() {
            // ... test code ...
        }

        @Test
        @Order(2)
        void testFeatureB() {
            // ... test code ...
        }
    }

    // =======================================================================
    // 3. Cleanup
    // =======================================================================

    @AfterAll
    static void cleanup() {
        if (support != null) {
            support.close();
        }
    }
}
```

### Best Practices

1. **Use @Order annotations** — ensures predictable test execution
2. **Nest tests by theme** — use @Nested classes to organize logically related tests
3. **Poll for eventually-consistent state** — use support.awaitRowExists(), support.awaitQuery(), etc.
4. **Verify at three layers:**
   - REST API response (status, body)
   - Database (JDBC queries)
   - Redis cache/graph (if relevant)
5. **Use tenant-scoped IntegrationTestSupport** — pass tenantId to constructor for tenant-aware queries
6. **Enable Keycloak for IAM tests** — bootstrapKeycloakRealm() before testing user management
7. **Call support.close() in @AfterAll** — releases database and Redis connections

### Typical Test Structure

**Phase 1: Prerequisites**
```java
// Provision tenant, create catalogue items, setup governance
support.bootstrapKeycloakRealm(realmName);
IntegrationTestSupport.provisionTenantAndLogin(...);
// POST catalogue endpoints to create entities, categories, fields, etc.
```

**Phase 2: Feature Tests**
```java
// Test the actual feature (CRUD, queries, business logic)
given().post("/api/v1/...").then().statusCode(200);
support.assertRowExists(...);
support.awaitQuery(...);
```

**Phase 3: Cleanup** (optional)
```java
// Some tests clean up data between test phases to avoid interference
```

---

## Running Tests in CI/CD

### GitHub Actions
Ensure Docker is available (standard in GitHub-hosted runners).

```yaml
- name: Run integration tests
  run: ./gradlew -p cveil-rest-api test
```

### Local Development
Ensure Docker is running (Docker Desktop on Mac/Windows, or docker daemon on Linux).

```bash
# Run all tests
./gradlew -p cveil-rest-api test

# Run with debug output
./gradlew -p cveil-rest-api test --info

# Run single test class
./gradlew -p cveil-rest-api test --tests ConsentResourceIT

# Run single test method
./gradlew -p cveil-rest-api test --tests ConsentResourceIT.FeatureTests.testSomething
```

### Troubleshooting

**Docker not running:**
```
ERROR: Cannot connect to Docker daemon
```
→ Start Docker Desktop or docker daemon

**Port conflicts:**
```
ERROR: Address already in use
```
→ Tests use dynamic ports (0) to avoid conflicts, but if Docker itself is blocked, restart it

**Slow tests:**
- First test class startup: ~30-60s (container startup, Flyway migrations)
- Subsequent tests: shared containers (much faster)

**Test timeout:**
- Increase via `timeout` property in test or via system property
- Some tests intentionally wait for eventual consistency (default 30s)

---

## Existing Integration Tests

Located in `cveil-rest-api/src/test/java/com/koostack/cveil/rest/integration/`:

- **ConsentResourceIT.java** — Consent grant/revoke/evaluate/batch operations
- **PlatformIamResourceIT.java** — User/service-account management, RBAC
- **TenantIamResourceIT.java** — Tenant-scoped IAM operations
- **CatalogueResourceIT.java** — Entity/category/field registration
- **SubjectLifecycleIT.java** — Subject creation/update/deletion
- **ConsentDsarIntegrationIT.java** — Data Subject Access Request
- **CreateTenantPipelineIT.java** — End-to-end tenant provisioning
- **ConsentGraphProjectionIT.java** — Redis graph model projection
- **CacheInvalidationIT.java** — Cache invalidation on state changes
- **RedisTenantAclIsolationIT.java** — Multi-tenant Redis isolation
- **RedisTlsIntegrationIT.java** — Redis TLS configuration
- **PostgresSslIntegrationIT.java** — PostgreSQL SSL/TLS
- **PlatformLifecycleIT.java** — Platform-wide lifecycle events
- **ProductionStackIT.java** — Full production-like integration
- **GovernanceResourceIT.java** — Purpose/regulation management
- **DsarTenantIsolationIT.java** — Tenant isolation in DSAR
- **GraphResourceIT.java** — Graph query/traversal
- **CacheInvalidationIT.java** — Cache coherency

---

## Key Takeaways

| Aspect | Details |
|--------|---------|
| **Test Framework** | Quarkus JUnit5 + Testcontainers + RestAssured |
| **Databases** | Real Postgres 16 + Redis 7 (not embedded H2) |
| **Keycloak** | Optional, bootstrapped in test resource manager |
| **Configuration** | `application.yml` with ${cveil.test.*} placeholders |
| **Test Support** | IntegrationTestSupport (JDBC, Redis, Keycloak, polling) |
| **Resource Manager** | CveilTestResourceManager (container lifecycle) |
| **Typical Runtime** | First class ~30-60s, subsequent classes ~5-10s each |
| **Port Strategy** | Dynamic (port 0) to avoid CI conflicts |
| **State Verification** | Three layers: REST API, Database, Redis |
| **Eventually-Consistent** | Use polling helpers (awaitRowExists, awaitQuery, etc.) |
| **Test Isolation** | Tests share containers but have isolated schemas/tenants |

