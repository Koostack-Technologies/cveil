# Integration Test Quick Reference Cheat Sheet

## Run Tests

```bash
# All tests
./gradlew -p cveil-rest-api test

# Single test class
./gradlew -p cveil-rest-api test --tests ConsentResourceIT

# Single test method
./gradlew -p cveil-rest-api test --tests ConsentResourceIT.PrerequisitesSetup.testMethod
```

## Test Class Skeleton

```java
@QuarkusTest
@QuarkusTestResource(CveilTestResourceManager.class)
@DisplayName("Feature Name")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class MyFeatureIT {

    private static IntegrationTestSupport support;
    private static String accessToken;

    @Inject CveilConfig config;

    // === Phase 1: Setup ===
    @Nested
    @Order(1)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Setup {
        @Test @Order(1)
        void initSupport(CveilConfig cfg) {
            support = new IntegrationTestSupport(cfg, "tenant_id");
        }

        @Test @Order(2)
        void provisioning() {
            IntegrationTestSupport.AuthInfo auth = 
                IntegrationTestSupport.provisionTenantAndLogin(
                    "test-bootstrap-key-for-integration-tests",
                    "tenant_id",
                    "Display Name"
                );
            accessToken = auth.accessToken();
        }
    }

    // === Phase 2: Tests ===
    @Nested
    @Order(2)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Tests {
        @Test @Order(1)
        void testFeature() {
            given()
                .header("Authorization", "Bearer " + accessToken)
                .post("/api/v1/endpoint")
                .then()
                .statusCode(200);
            
            support.awaitRowExists("table", "column", "value");
        }
    }

    @AfterAll
    static void cleanup() {
        if (support != null) support.close();
    }
}
```

## IntegrationTestSupport — Common Methods

### Init & Cleanup
```java
new IntegrationTestSupport(config)                    // Platform mode
new IntegrationTestSupport(config, tenantId)          // Tenant mode
support.close()                                        // Release resources
```

### REST + Provisioning
```java
IntegrationTestSupport.provisionTenantAndLogin(key, tenantId, displayName)
  → AuthInfo(accessToken, refreshToken, tenantId, email)

IntegrationTestSupport.loginUser(email, password) → String (access token)

IntegrationTestSupport.provisionTenantViaOrchestrator(key, tenantId, displayName, plan)
  → String tenantId
```

### JDBC Queries
```java
support.query(sql, params...)                          // List<Map<String, Object>>
support.countRows(table, column, value)                // long
support.countRowsWithTenant(table, column, value)      // long (with tenant_id)

support.assertRowExists(table, column, value)          // Poll + assert
support.assertRowAbsent(table, column, value)          // Poll + assert

support.awaitQueryValue(sql, column, expected, params) // Poll until column = value
support.awaitQuery(sql, predicate, desc, params)       // Poll until predicate true
```

### Catalogue Shortcuts
```java
support.queryEntities()                                // List<Map>
support.queryCategories()                              // List<Map>
support.queryFields()                                  // List<Map>
support.queryFieldCategories(fieldName, entityName)    // List<Map>
```

### Consent & Events
```java
support.queryConsentLedger(subjectId)                  // List<Map>
support.queryEvents(aggregateType, eventType)          // List<Map>
support.queryEventsByAggregate(aggregateType)          // List<Map>
support.awaitAllEventsPublished(timeout)               // Poll until all published
```

### Redis Graph (for Consent Graph Projection)
```java
support.getGraphNode(tenantId, type, name)             // Map<String, String>
support.getOutgoingEdges(tenantId, type, name)         // Set<String>
support.getIncomingEdges(tenantId, type, name)         // Set<String>
support.getEdgeProperties(tenantId, fromType, fromName, toType, toName)

support.getAdminConsentCache(tenantId, subjectId)      // String (JSON)
support.getAdminFieldCache(tenantId, entityName, fieldName) // String (JSON)
support.getTypeIndex(tenantId, type)                   // List<String>
```

### Redis Streams
```java
support.getStreamEntries(tenantId, count)              // List<StreamEntry>
support.getStreamEntriesByType(tenantId, eventType)    // List<StreamEntry>
support.scanKeys(pattern)                              // Set<String>
support.jedis()                                        // JedisPooled (raw access)
```

### Keycloak
```java
support.keycloak()                                     // Keycloak admin client
support.bootstrapKeycloakRealm(realmName)              // Idempotent setup

support.awaitKeycloakRealmExists(realmName, timeout)
support.awaitKeycloakUserExists(realmName, email, timeout)
support.awaitKeycloakUserEnabled(realmName, email, timeout)
support.awaitKeycloakUserDisabled(realmName, email, timeout)

support.getKeycloakUsers(realmName)                    // List<UserRepresentation>
support.getKeycloakClients(realmName)                  // List<ClientRepresentation>
support.getKeycloakUserRoles(realmName, userId)        // List<RoleRepresentation>
```

### Polling & Waiting
```java
support.awaitTenantReady(tenantId, timeout)            // Wait for tenant streams
support.awaitApiKeyUsable(bearerToken, timeout)        // Wait for API key usable
support.awaitRedisState(supplier, predicate, timeout)  // Generic polling
```

## RestAssured HTTP Shortcuts

```java
// GET
given()
  .header("Authorization", "Bearer " + token)
  .get("/api/v1/endpoint")
  .then()
  .statusCode(200);

// POST with JSON
given()
  .contentType("application/json")
  .header("Authorization", "Bearer " + token)
  .body("{\"field\": \"value\"}")
  .when()
  .post("/api/v1/endpoint")
  .then()
  .statusCode(201)
  .extract()
  .asString();  // .body(), .jsonPath(), etc.

// PUT/DELETE/PATCH — same pattern
```

## AssertJ Shortcuts

```java
assertThat(value).isEqualTo(expected);
assertThat(value).isNotNull();
assertThat(value).isTrue();
assertThat(collection).hasSize(5);
assertThat(collection).contains(item1, item2);
assertThat(collection).extracting("field").contains("value");
```

## Configuration

**Test DB/Redis:** `application.yml` resolves `${cveil.test.*}` from CveilTestResourceManager

**Bootstrap Key:** `"test-bootstrap-key-for-integration-tests"` (configured in application.yml)

**Keycloak Credentials:** admin / admin (hardcoded in CveilTestResourceManager)

**Postgres Creds:** cveil_test / cveil_test_password (hardcoded)

## Common Patterns

### Verify REST → DB → Cache (3-layer check)
```java
// 1. Call REST API
String response = given()
  .header("Authorization", "Bearer " + token)
  .post("/api/v1/items")
  .then()
  .statusCode(201)
  .extract()
  .asString();

// 2. Verify database projection catches up
support.awaitRowExists("item", "item_id", "123");
Map<String, Object> dbRow = support.query(
  "SELECT * FROM item WHERE item_id = ?", "123"
).get(0);
assertThat(dbRow.get("status")).isEqualTo("active");

// 3. Verify Redis cache (if using graph projection)
Map<String, String> cacheEntry = support.getAdminConsentCache(tenantId, subjectId);
assertThat(cacheEntry).isNotEmpty();
```

### Wait for Eventually-Consistent State
```java
// Option A: Built-in helpers
support.awaitRowExists("table", "column", "value");

// Option B: Custom query poll
support.awaitQuery(
  "SELECT COUNT(*) AS cnt FROM items WHERE tenant_id = ?",
  rows -> (long) rows.get(0).get("cnt") >= 5,
  "Should have at least 5 items",
  tenantId
);

// Option C: Redis state poll
Map<String, String> node = support.awaitRedisState(
  () -> support.getGraphNode(tenantId, "field", "email"),
  n -> n != null && !n.isEmpty(),
  Duration.ofSeconds(30)
);
```

### Tenant Provisioning + Login
```java
// One-liner
IntegrationTestSupport.AuthInfo auth = IntegrationTestSupport.provisionTenantAndLogin(
    "test-bootstrap-key-for-integration-tests",
    "my_tenant_id",
    "My Tenant Display Name"
);
String accessToken = auth.accessToken();
String tenantId = auth.tenantId();
String email = auth.email();
```

### Bootstrap Keycloak Realm (for IAM tests)
```java
support.bootstrapKeycloakRealm("my-realm");
// Now can create users/clients in that realm
// Idempotent — safe to call multiple times
```

## Important Notes

1. **Tests are ordered** — use @Order, @Nested, @TestMethodOrder
2. **Containers persist** — shared across test classes (fast)
3. **Schema isolation** — use tenantId in IntegrationTestSupport constructor
4. **Polling is required** — CQRS projections are async; use awaitXxx helpers
5. **Call support.close()** — in @AfterAll to release connections
6. **CveilTestResourceManager runs once** — containers persist for all tests
7. **Dynamic ports** — tests use port 0 to avoid conflicts in CI

## Debugging

```bash
# Verbose test output
./gradlew -p cveil-rest-api test --info

# Run single test with logging
./gradlew -p cveil-rest-api test --tests MyTestIT -i

# View container logs (Docker)
docker logs <container-id>
```

**Test fails on startup?** → Check Docker is running and ports aren't blocked

**Tests timeout?** → Check network/container health; increase Duration parameters

**CI failures that don't repro locally?** → Check environment differences; CI may be slower
