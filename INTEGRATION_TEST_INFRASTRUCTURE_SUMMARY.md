# Integration Test Infrastructure — Summary

## Documentation Created

I've created two comprehensive guides for building and running integration tests:

### 1. **INTEGRATION_TEST_GUIDE.md** (Full Reference)
Comprehensive guide covering:
- Complete architecture overview of the test infrastructure
- Detailed explanation of CveilTestResourceManager (container lifecycle)
- Complete IntegrationTestSupport API reference (100+ helper methods)
- Test configuration (application.yml)
- Step-by-step instructions for writing new integration tests
- Best practices and patterns
- Troubleshooting guide
- 17 existing integration tests overview

### 2. **INTEGRATION_TEST_QUICK_REFERENCE.md** (Cheat Sheet)
Quick reference with:
- Commands to run tests
- Test class skeleton template
- Compact method reference (init, REST, JDBC, Keycloak, Redis, etc.)
- RestAssured and AssertJ shortcuts
- Common patterns with code snippets
- Debugging tips

---

## Key Findings

### Build System
- **Root:** `build.gradle.kts` + `settings.gradle.kts` (multi-project setup)
- **Test module:** `cveil-rest-api/build.gradle.kts`
- **Gradle version:** 8.14 (see gradle-wrapper.properties)
- **Quarkus plugin:** 3.22.3

### Test Framework Stack
- **Test runner:** Quarkus JUnit5 + Testcontainers
- **HTTP client:** RestAssured
- **Assertions:** AssertJ 3.27.3
- **Polling/Awaiting:** Awaitility 4.2.2
- **Containers:**
  - Postgres 16-alpine
  - Redis 7-alpine
  - Keycloak 26.0.8

### Container Infrastructure
**CveilTestResourceManager** (src/test/java/...):
- Implements QuarkusTestResourceLifecycleManager
- Creates shared Docker network
- Starts 3 containers in sequence (Postgres → Redis → Keycloak)
- Bootstraps "cveil" Keycloak realm with cveil-backend client and 8 default roles
- Injects 10 config properties before Quarkus CDI bootstrap
- Containers persist across test classes (one per test run)

### Test Configuration
**application.yml** (src/test/resources/):
- Runs in PRODUCTION MODE (not embedded H2)
- Uses ${cveil.test.*} placeholders for container URLs/credentials
- Disables Quarkus DevServices (manual management)
- Default ports: dynamic (0), test logging: WARN (cveil=INFO)
- Keycloak optional but auto-started

### Test Support Library
**IntegrationTestSupport** (883 lines, comprehensive):

**JDBC/Database:**
- Direct query(sql, params) → List<Map>
- countRows(), countRowsWithTenant()
- assertRowExists()/assertRowAbsent() with polling
- awaitQueryValue(), awaitQuery() with custom predicates
- Catalogue queries (entities, categories, fields)
- Consent ledger queries
- Event store queries
- DSAR audit log queries

**Redis/Graph Model:**
- getGraphNode(tenantId, type, name) → Map of fields
- getOutgoingEdges/getIncomingEdges() for graph traversal
- getEdgeProperties() for edge data
- Admin projection caches (JSON strings)
- Type indexes (sorted sets)
- Redis streams with shard routing
- Raw jedis() for custom operations

**Keycloak:**
- keycloak() lazy-initialized admin client
- awaitKeycloakRealm/UserExists/UserEnabled/UserDisabled()
- bootstrapKeycloakRealm() idempotent setup
- Get users, clients, roles via Admin API

**Authentication & Provisioning:**
- provisionTenantViaOrchestrator() — creates user + org + tenant
- loginUser(email, password) → JWT access token
- provisionTenantAndLogin() — combined one-liner
- AuthInfo record (accessToken, refreshToken, tenantId, email)

**Polling:**
- awaitTenantReady() — wait for stream consumer groups
- awaitApiKeyUsable() — wait for JWT usable (CQRS lag)
- awaitRedisState() — generic polling with supplier + predicate

### Test Patterns
**Nested structure with @Order (recommended):**
```
@QuarkusTest
@QuarkusTestResource(CveilTestResourceManager.class)
class MyFeatureIT {
  @Nested @Order(1) class Setup { ... }
  @Nested @Order(2) class FeatureTests { ... }
  @AfterAll cleanup() { ... }
}
```

**3-layer verification pattern:**
1. REST API response (status, body)
2. Database verification (JDBC queries with polling)
3. Cache/Graph verification (Redis state)

**Eventually-consistent pattern:**
- Use support.awaitRowExists(table, column, value)
- Use support.awaitQuery(sql, predicate, desc, params)
- Default timeout: 30 seconds, polling interval: 500ms

### Existing Integration Tests (17 total)
- ConsentResourceIT — grant/revoke/evaluate/batch
- PlatformIamResourceIT — user/service-account management
- TenantIamResourceIT — tenant-scoped operations
- CatalogueResourceIT — entity/category/field registration
- SubjectLifecycleIT — subject CRUD
- ConsentDsarIntegrationIT — DSAR operations
- CreateTenantPipelineIT — end-to-end provisioning
- ConsentGraphProjectionIT — Redis graph projection
- CacheInvalidationIT — cache coherency
- RedisTenantAclIsolationIT — multi-tenant Redis isolation
- RedisTlsIntegrationIT — Redis TLS configuration
- PostgresSslIntegrationIT — Postgres SSL/TLS
- PlatformLifecycleIT — platform-wide events
- ProductionStackIT — full integration
- GovernanceResourceIT — purpose/regulation management
- DsarTenantIsolationIT — tenant isolation in DSAR
- GraphResourceIT — graph queries/traversal

---

## How to Write New Integration Tests

### Minimal Example
```java
@QuarkusTest
@QuarkusTestResource(CveilTestResourceManager.class)
@DisplayName("Feature Name")
class MyFeatureIT {
    private static IntegrationTestSupport support;
    private static String accessToken;

    @Test
    void initSupport(CveilConfig config) {
        support = new IntegrationTestSupport(config, "tenant_id");
        var auth = IntegrationTestSupport.provisionTenantAndLogin(
            "test-bootstrap-key-for-integration-tests",
            "tenant_id", "Display Name"
        );
        accessToken = auth.accessToken();
    }

    @Test
    void testFeature() {
        given()
            .header("Authorization", "Bearer " + accessToken)
            .post("/api/v1/endpoint")
            .then()
            .statusCode(200);
        
        support.awaitRowExists("table", "column", "value");
    }

    @AfterAll
    static void cleanup() {
        if (support != null) support.close();
    }
}
```

### Key Steps
1. Annotate class with @QuarkusTest + @QuarkusTestResource(CveilTestResourceManager.class)
2. Init IntegrationTestSupport with CveilConfig (injected via method parameter)
3. Call provisionTenantAndLogin() to create tenant + get JWT
4. Use support helpers to:
   - Call REST APIs with Authorization bearer token
   - Verify database state with JDBC queries + polling
   - Verify Redis caches/graphs
   - Poll Keycloak for user/realm creation
5. Call support.close() in @AfterAll

### Best Practices
- Use @Order/@Nested for logical grouping and deterministic execution
- Use eventual-consistency polling (awaitRowExists, awaitQuery, etc.)
- Verify at 3 layers: REST + DB + Cache
- Pass tenantId to IntegrationTestSupport constructor for tenant-scoped queries
- Bootstrap Keycloak realm before IAM tests (support.bootstrapKeycloakRealm())
- Use static mutable state for shared tenant ID/token across nested classes

---

## Running Tests

```bash
# All tests
./gradlew -p cveil-rest-api test

# Single test class
./gradlew -p cveil-rest-api test --tests ConsentResourceIT

# Single test method
./gradlew -p cveil-rest-api test --tests ConsentResourceIT.PrerequisitesSetup.testMethod

# With verbose output
./gradlew -p cveil-rest-api test --info

# Requires Docker to be running
docker run -d --name some-postgres ...  # Not needed — Testcontainers handles this
```

---

## Important Notes

1. **Production Mode, Not Embedded** — Tests run against real Postgres + Redis to catch real bugs (schema routing, case sensitivity, credential handling, etc.)

2. **Containers Are Shared** — Started once per test run; subsequent test classes reuse them (fast)

3. **Eventual Consistency** — CQRS projections are async; always use polling helpers (awaitRowExists, awaitQuery, etc.)

4. **@Order Is Required** — Tests run in order; use @Order, @Nested, @TestMethodOrder annotations

5. **Must Call support.close()** — Releases Redis and Keycloak connections in @AfterAll

6. **Config Injection** — Use method parameter injection (CveilConfig config) not @Inject for @BeforeAll/@Test

7. **Keycloak Optional** — Only needed for IAM tests; auto-started but can be skipped for other features

8. **Idempotent Provisioning** — provisionTenantAndLogin() safely handles re-runs (409 Conflict treated as success)

---

## Files to Study

1. **CveilTestResourceManager.java** (370 lines)
   - Container lifecycle management
   - Keycloak realm bootstrap
   - Property injection

2. **IntegrationTestSupport.java** (883 lines)
   - Complete API reference
   - JDBC, Redis, Keycloak helpers
   - Provisioning + auth helpers
   - Polling utilities

3. **application.yml** (106 lines)
   - Test configuration
   - Placeholder resolution
   - Logging setup

4. **ConsentResourceIT.java** & **PlatformIamResourceIT.java**
   - Nested @Order pattern
   - 3-layer verification
   - Real-world examples

---

## Recommended Reading Order

1. **INTEGRATION_TEST_QUICK_REFERENCE.md** (this file) — 5 min overview
2. **INTEGRATION_TEST_GUIDE.md** — Deep dive (30 min)
3. **Study ConsentResourceIT.java** — Real example (20 min)
4. **Read IntegrationTestSupport.java** — Complete API (30 min)
5. **Write your first test** — Copy template, adapt to your feature

---

## Troubleshooting

**Docker not running?**
- Start Docker Desktop (Mac/Windows) or `sudo systemctl start docker` (Linux)

**Port conflicts?**
- Tests use dynamic port 0 to avoid conflicts
- If Quarkus fails to start, check if port 8080 (Keycloak dev mode) is blocked

**Tests timeout?**
- Increase Duration parameters in test
- Check container health: `docker ps`
- First test class: 30-60s (container startup + Flyway migrations)
- Subsequent classes: 5-10s each

**DB/Redis not reachable?**
- Check Docker network: `docker network ls`
- Containers use network aliases: `postgres`, so JDBC URL must resolve correctly

**Keycloak bootstrap fails?**
- Check `keycloak` schema exists: CREATE SCHEMA IF NOT EXISTS keycloak
- Check network connectivity: Keycloak → Postgres
- Increase startup timeout (Duration.ofSeconds(120))
