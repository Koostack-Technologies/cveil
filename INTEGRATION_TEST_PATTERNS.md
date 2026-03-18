# Integration Test Patterns Analysis

## Summary of Findings

This document captures the exact patterns, structure, and best practices used in the cveil-rest-api integration tests.

---

## 1. Test Files Overview

### Found Integration Test Files (17 total *IT.java files)
```
✓ ConsentDsarIntegrationIT.java      (DSAR export/delete tests)
✓ DsarTenantIsolationIT.java          (Tenant isolation for DSAR)
✓ ConsentResourceIT.java               (Main consent integration test)
✓ CacheInvalidationIT.java
✓ CatalogueResourceIT.java
✓ ConsentGraphProjectionIT.java
✓ CreateTenantPipelineIT.java
✓ GovernanceResourceIT.java
✓ GraphResourceIT.java
✓ PlatformIamResourceIT.java
✓ PlatformLifecycleIT.java
✓ PostgresSslIntegrationIT.java
✓ ProductionStackIT.java
✓ RedisTenantAclIsolationIT.java
✓ RedisTlsIntegrationIT.java
✓ SubjectLifecycleIT.java
✓ TenantIamResourceIT.java
```

---

## 2. Test Infrastructure Patterns

### A. CveilTestResourceManager.java
**Purpose**: Quarkus test resource lifecycle manager that spins up Testcontainers

**Key Pattern: Testcontainers Setup**
```java
@Override
public Map<String, String> start() {
    // Starts 3 containers in sequence:
    // 1. PostgreSQL 16 (cveil_test database)
    // 2. Redis 7
    // 3. Keycloak 26.0.8 (connected via Docker network)
    
    // Returns property map:
    Map<String, String> props = new HashMap<>();
    props.put("cveil.test.db.url", jdbcUrl);
    props.put("cveil.test.db.username", DB_USERNAME);
    props.put("cveil.test.db.password", DB_PASSWORD);
    props.put("cveil.test.redis.host", redisHost);
    props.put("cveil.test.redis.port", String.valueOf(redisPort));
    props.put("cveil.test.keycloak.url", keycloakUrl);
    // ... more properties
    return props;
}
```

**Key Pattern: Keycloak Platform Realm Bootstrap**
- Automatically creates "cveil" realm with default roles:
  - consent:read, consent:write
  - admin:read, admin:write
  - tenant-admin, tenant-write, tenant-read
  - super-admin
- Creates cveil-backend confidential client with ROPC + service account
- Configures organization scope as default client scope

**Key Pattern: Container Lifecycle**
```java
@Override
public void stop() {
    // Gracefully stops Keycloak, Redis, Postgres
    // Handles exceptions to prevent cascading failures
    // Closes Docker network
}
```

### B. IntegrationTestSupport.java
**Purpose**: Shared test utilities for querying and verifying state at 3 layers

**Key Pattern: Multi-Layer Verification**
- Layer 1: REST API responses (via RestAssured)
- Layer 2: Postgres queries (direct JDBC)
- Layer 3: Redis queries (Jedis client)

**Key Pattern: Tenant-Aware Support Initialization**
```java
// Platform-wide (no tenant isolation)
public IntegrationTestSupport(CveilConfig config) { ... }

// Tenant-scoped (routes JDBC to cveilconsent schema)
public IntegrationTestSupport(CveilConfig config, String tenantId) {
    // Sets search_path to: cveilconsent, cveilevent, cveilcore, cveiliam, cveilbilling, public
}
```

**Key Pattern: Async Projection Polling with Awaitility**
```java
public void assertRowExists(String table, String column, Object value) {
    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(() ->
            assertThat(countRows(table, column, value))
                .isGreaterThan(0));
}
```

**Key Pattern: JDBC Query Methods**
```java
// Parameterized queries with list-of-maps return
public List<Map<String, Object>> query(String sql, Object... params)

// Simple existence checks
public long countRows(String table, String whereColumn, Object whereValue)

// Tenant-isolated counts
public long countRowsWithTenant(String table, String whereColumn, Object whereValue)
```

**Key Pattern: Redis Helpers**
```java
// Graph model navigation
public Map<String, String> getGraphNode(String tenantId, String type, String name)
public Set<String> getOutgoingEdges(String tenantId, String type, String name)
public Map<String, String> getEdgeProperties(String tenantId, String fromType, String fromName, String toType, String toName)

// Stream operations
public List<StreamEntry> getStreamEntries(String tenantId, int count)
public List<StreamEntry> getStreamEntriesByType(String tenantId, String eventType)
```

**Key Pattern: Keycloak Integration**
```java
// Lazy-initialized admin client
public Keycloak keycloak()

// Polling for async provisioning
public void awaitKeycloakUserExists(String realmName, String email, Duration timeout)
public void awaitKeycloakUserEnabled(String realmName, String email, Duration timeout)
public void awaitKeycloakUserDisabled(String realmName, String email, Duration timeout)
```

**Key Pattern: Tenant Provisioning via Orchestrator**
```java
public static String provisionTenantViaOrchestrator(
        String bootstrapKey, String tenantId, String displayName, String plan) {
    // POST /api/v1/platform/iam/users with org details
    // Returns tenant ID or treats 409 (conflict) as idempotent success
}

public static AuthInfo provisionTenantAndLogin(
        String bootstrapKey, String tenantId, String displayName) {
    // Combines provisioning + login, returns JWT token
}
```

---

## 3. EXACT Integration Test Patterns

### Pattern A: Class-Level Setup with Nested Test Classes

**Example from ConsentResourceIT.java:**
```java
@QuarkusTest
@QuarkusTestResource(CveilTestResourceManager.class)
@DisplayName("ConsentResource Integration Test (Postgres + Redis)")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsentResourceIT {

    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";
    private static final String TENANT_ID = "consent_it_tenant";
    
    private static volatile String accessToken;
    private static volatile IntegrationTestSupport support;
    
    @Inject CveilConfig config;

    @Nested
    @Order(1)
    @DisplayName("1 — Prerequisites Setup")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class PrerequisitesSetup {
        @Test @Order(1) void registerEntities() { ... }
        @Test @Order(2) void registerCategories() { ... }
        // ... more @Nested methods
    }
    
    @Nested
    @Order(2)
    @DisplayName("2 — Subject Management")
    class SubjectManagement { ... }
}
```

**Key Points:**
- Nested `@Nested` classes for logical grouping (Arrange, Act, Assert phases)
- `@TestClassOrder` + `@Order` annotations for sequential execution across nested classes
- `@TestMethodOrder` for method ordering within nested classes
- `@DisplayName` for human-readable test descriptions
- Static volatile fields for test state sharing across nested classes

### Pattern B: Multi-Step Setup with Idempotency

**From DsarTenantIsolationIT.java:**
```java
@Test @Order(0) @DisplayName("Provision tenant A and login")
void provisionAndLoginTenantA() {
    try {
        IntegrationTestSupport.AuthInfo authA =
            IntegrationTestSupport.provisionTenantAndLogin(
                BOOTSTRAP_KEY, TENANT_A, "Tenant A Display");
        accessTokenA = authA.accessToken();
    } catch (Exception e) {
        if (e.getMessage() != null && e.getMessage().contains("already exists")) {
            // Idempotent fallback: login with existing user
            String email = TENANT_A.replace("_", "-") + "@cveil-test.com";
            accessTokenA = IntegrationTestSupport.loginUser(email, "Test1234!");
        } else {
            throw e;
        }
    }
    supportA = new IntegrationTestSupport(config, TENANT_A);
    supportA.awaitTenantReady(TENANT_A, Duration.ofSeconds(90));
}
```

**Key Points:**
- Try/catch with idempotency handling for 409 conflicts
- Fallback to login if tenant already exists
- `awaitTenantReady()` waits for Redis ACL credentials + stream consumer groups

### Pattern C: Three-Layer Verification

**From ConsentResourceIT.java - Single Consent Grant:**
```java
@Test @Order(1) @DisplayName("Grant email/PII/User/marketing to subject-alpha")
void grantEmailMarketing() {
    // LAYER 1: REST API Call
    given().header("Authorization", auth())
        .contentType(ContentType.JSON)
        .body("""
            {
              "subject": "%s",
              "scope": %s
            }
            """.formatted(SUBJECT_ALPHA, scope(...)))
        .when()
        .post("/api/v1/subjects/consent/grant")
        .then()
        .statusCode(200);

    // LAYER 2: Event Store (Postgres)
    var events = support.queryEvents("consent-subject", "consent.subject.granted");
    assertThat(events).isNotEmpty();

    // LAYER 3: Consent Ledger (Postgres async projection)
    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(() -> {
            var ledger = support.queryConsentLedger(SUBJECT_ALPHA);
            assertThat(ledger).isNotEmpty();
            var lastEntry = ledger.get(ledger.size() - 1);
            assertThat(lastEntry.get("action").toString())
                .containsIgnoringCase("GRANT");
        });
}
```

**Key Points:**
- Synchronous REST API assertion (happens in transaction)
- Synchronous event store check (same transaction)
- Async projection polling with Awaitility (separate transaction, 500ms intervals)

### Pattern D: Graph Projection Waiting (for Redis)

**From ConsentResourceIT.java:**
```java
@Test @Order(2) @DisplayName("Evaluate: email/PII/User/marketing → granted")
void evaluateGranted() {
    // Wait for graph edge to appear before evaluate (reads Redis exclusively)
    String policyName = "FIELD:" + ENTITY_USER + "/" + FIELD_EMAIL + ":" + PURPOSE_MARKETING;
    support.awaitRedisState(
        () -> support.getEdgeProperties(
            TENANT_ID, "subject", SUBJECT_ALPHA, "policy", policyName),
        ep -> ep != null && !ep.isEmpty(),
        EVALUATE_TIMEOUT);

    // Now safe to call evaluate endpoint
    given().header("Authorization", auth())
        .contentType(ContentType.JSON)
        .body("""
            {
              "scopes": [%s]
            }
            """.formatted(scope(...)))
        .when()
        .post("/api/v1/subjects/" + SUBJECT_ALPHA + "/evaluate")
        .then()
        .statusCode(200)
        .body("granted", hasSize(1))
        .body("denied", hasSize(0));
}
```

**Key Pattern: awaitRedisState**
```java
public <T> T awaitRedisState(
        Supplier<T> supplier,
        Predicate<T> predicate,
        Duration timeout) {
    // Polls supplier every 200ms until predicate matches or timeout
    // Returns the final value
}
```

### Pattern E: Tenant Isolation Testing

**From DsarTenantIsolationIT.java:**
```java
@Test @Order(10) @DisplayName("Export in tenant A should not return data from tenant B")
void testExportTenantIsolation() {
    String subject = "export_isolation_1";
    
    // Grant in tenant A only
    grantConsent(accessTokenA, subject, "email", "user", "marketing");

    // Wait for projection in A
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(() -> {
            SubjectDataExport export = exportSubject(accessTokenA, subject);
            assertThat(export.consent().entries()).isNotEmpty();
        });

    // Export in B should be empty (proves isolation)
    SubjectDataExport exportB = exportSubject(accessTokenB, subject);
    assertThat(exportB.subject()).isEqualTo(subject);
    assertThat(exportB.consent().entries()).isEmpty();
}
```

**Key Points:**
- Use separate JWT tokens for each tenant (`accessTokenA`, `accessTokenB`)
- Use separate `IntegrationTestSupport` instances with tenant-scoped JDBC
- Verify that operations in one tenant don't leak to another

### Pattern F: Authorization Testing

**From ConsentDsarIntegrationIT.java:**
```java
@Test @DisplayName("Delete without valid credentials should return 401")
void testDeleteAuthorization() {
    String subject = "delete_test_authz";
    grantConsent(subject, "PII", "email", "dsar_user", "marketing");

    // Try with invalid key
    given().header("Authorization", "Bearer invalid-key-no-access")
        .queryParam("confirm", "DELETE_" + subject)
        .when()
        .delete("/api/v1/subjects/{id}", subject)
        .then()
        .statusCode(401);
}
```

---

## 4. DSAR-Specific Integration Test Patterns

### A. ConsentDsarIntegrationIT.java (10 Tests)

**Test 1: Export Completeness**
```java
@Test @DisplayName("Export should return all consent policies for a subject")
void testExportCompleteness() {
    String subject = "export_test_1";
    grantConsent(subject, "PII", "email", "dsar_user", "marketing");
    grantConsent(subject, "PII", "phone", "user", "analytics");
    grantConsent(subject, "HEALTH", "ssn", "patient", "sales");

    SubjectDataExport export = exportSubject(subject);
    assertThat(export.subject()).isEqualTo(subject);
    assertThat(export.consent().entries()).hasSize(3);
    assertThat(export.exportedAt()).isNotNull();
}
```

**Test 2: Export Empty Subject**
```java
@Test @DisplayName("Export should return empty lists for subject with no consent")
void testExportEmptySubject() {
    SubjectDataExport export = exportSubject("export_test_empty");
    assertThat(export.consent().entries()).isEmpty();
    assertThat(export.cachedKeyCount()).isZero();
}
```

**Test 3: Export Audit Trail**
```java
@Test @DisplayName("Export should include complete audit trail in correct order")
void testExportAuditTrail() {
    String subject = "export_test_audit";
    grantConsent(subject, "PII", "email", "dsar_user", "marketing");
    revokeConsent(subject, "PII", "email", "dsar_user", "marketing");

    SubjectDataExport export = exportSubject(subject);
    assertThat(export.consent().entries()).hasSize(2);
    assertThat(export.consent().entries().get(0).action()).isEqualTo("REVOKED");
    assertThat(export.consent().entries().get(1).action()).isEqualTo("GRANTED");
}
```

**Test 4-5: Delete Confirmation Validation**
```java
@Test @DisplayName("Delete without confirmation token should return 400")
void testDeleteMissingConfirmation() {
    given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
        .when()
        .delete("/api/v1/subjects/{id}", subject)
        .then()
        .statusCode(400);
}

@Test @DisplayName("Delete with wrong confirmation token should return 400")
void testDeleteWrongConfirmation() {
    given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
        .queryParam("confirm", "WRONG_TOKEN")
        .when()
        .delete("/api/v1/subjects/{id}", subject)
        .then()
        .statusCode(400);
}
```

**Test 6: Delete Atomicity**
```java
@Test @DisplayName("Delete should atomically clear all stores")
void testDeleteAtomicity() {
    String subject = "delete_test_atomicity";
    
    // Grant & verify
    grantConsent(subject, "PII", "email", "dsar_user", "marketing");
    SubjectDataExport beforeDelete = exportSubject(subject);
    assertThat(beforeDelete.consent().entries()).isNotEmpty();

    // Delete with correct confirmation
    String confirmToken = "DELETE_" + subject;
    DsarDeletionReceipt receipt = deleteSubject(subject, confirmToken);
    assertThat(receipt.status()).isEqualTo("PENDING_SHRED");

    // Verify all data gone
    SubjectDataExport afterDelete = exportSubject(subject);
    assertThat(afterDelete.consent().entries()).isEmpty();
}
```

**Test 7: Delete Idempotency**
```java
@Test @DisplayName("Delete should be idempotent")
void testDeleteIdempotency() {
    String subject = "delete_test_idempotent";
    grantConsent(subject, "PII", "email", "dsar_user", "marketing");

    String confirmToken = "DELETE_" + subject;
    DsarDeletionReceipt receipt1 = deleteSubject(subject, confirmToken);
    DsarDeletionReceipt receipt2 = deleteSubject(subject, confirmToken);

    assertThat(receipt1.status()).isEqualTo("PENDING_SHRED");
    assertThat(receipt2.status()).isEqualTo("PENDING_SHRED");
}
```

**Test 8: Delete Audit Logging**
```java
@Test @DisplayName("Delete should log the erasure in DSAR audit table with accurate counts")
void testDeleteAuditLogging() {
    String subject = "delete_test_auditlog";
    grantConsent(subject, "PII", "email", "dsar_user", "marketing");
    grantConsent(subject, "PII", "phone", "user", "analytics");
    grantConsent(subject, "HEALTH", "ssn", "patient", "sales");

    String confirmToken = "DELETE_" + subject;
    DsarDeletionReceipt receipt = deleteSubject(subject, confirmToken);

    assertThat(receipt.status()).isEqualTo("PENDING_SHRED");
    assertThat(receipt.ledgerEntriesRevoked()).isEqualTo(3);
    assertThat(receipt.cachedKeysDeleted()).isGreaterThanOrEqualTo(0);
}
```

**Tests 9-10: Authorization**
```java
@Test @DisplayName("Delete without valid credentials should return 401")
void testDeleteAuthorization() {
    given().header("Authorization", "Bearer invalid-key-no-access")
        .queryParam("confirm", "DELETE_" + subject)
        .when()
        .delete("/api/v1/subjects/{id}", subject)
        .then()
        .statusCode(401);
}

@Test @DisplayName("Export without valid credentials should return 401")
void testExportAuthorization() {
    given().header("Authorization", "Bearer invalid-key-no-access")
        .when()
        .get("/api/v1/subjects/{id}/export", subject)
        .then()
        .statusCode(401);
}
```

### B. Helper Methods Pattern

**Consent Operations:**
```java
private void grantConsent(
        String subject,
        String category,
        String fieldName,
        String entityName,
        String purposeName) {
    Map<String, Object> scope = buildScope(category, fieldName, entityName, purposeName);

    given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
        .contentType(ContentType.JSON)
        .body(Map.of("subject", subject, "scope", scope))
        .when()
        .post("/api/v1/subjects/consent/grant")
        .then()
        .statusCode(200);
}

private void revokeConsent(
        String subject,
        String category,
        String fieldName,
        String entityName,
        String purposeName) {
    Map<String, Object> scope = buildScope(category, fieldName, entityName, purposeName);

    given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
        .contentType(ContentType.JSON)
        .body(Map.of("subject", subject, "scope", scope))
        .when()
        .post("/api/v1/subjects/consent/revoke")
        .then()
        .statusCode(200);
}
```

**DSAR Operations:**
```java
private SubjectDataExport exportSubject(String subject) {
    return given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
        .contentType(ContentType.JSON)
        .when()
        .get("/api/v1/subjects/{id}/export", subject)
        .then()
        .statusCode(200)
        .extract()
        .as(SubjectDataExport.class);
}

private DsarDeletionReceipt deleteSubject(String subject, String confirmToken) {
    return given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
        .contentType(ContentType.JSON)
        .queryParam("confirm", confirmToken)
        .when()
        .delete("/api/v1/subjects/{id}", subject)
        .then()
        .statusCode(200)
        .extract()
        .as(DsarDeletionReceipt.class);
}

private static Map<String, Object> buildScope(
        String category, String fieldName, String entityName, String purposeName) {
    Map<String, Object> scope = new HashMap<>();
    scope.put("category", category);
    scope.put("fieldName", fieldName);
    if (entityName != null) scope.put("entityName", entityName);
    if (purposeName != null) scope.put("purposeName", purposeName);
    return scope;
}
```

---

## 5. PublicDsarResource.java (Self-Service DSAR)

**Endpoint**: `POST /api/v1/public/dsar/submit`

**Request DTO:**
```java
public record PublicDsarSubmission(
    @NotBlank String tenantSlug,
    @NotBlank @Email @Size(max = 512) String requesterEmail,
    @Size(max = 256) String requesterName,
    @NotBlank String requestType,
    @Size(max = 256) String subjectId,
    String notes)
```

**Valid Request Types:**
- ACCESS
- DELETION
- RECTIFICATION
- PORTABILITY
- RESTRICTION
- OBJECTION

**Key Patterns in PublicDsarResource:**

1. **Tenant Resolution from Slug**
```java
private String resolveTenantFromSlug(DataSource ds, String slug) throws Exception {
    // Try slug lookup first, fallback to tenant_id directly
    // Returns null if not found
}
```

2. **Rate Limiting**
```java
// Max 5 DSAR submissions per email per 24 hours
SELECT COUNT(*) FROM cveilconsent.dsar_request
WHERE requester_email = ? AND created_at > CURRENT_TIMESTAMP - INTERVAL '24 hours'
```

3. **Duplicate Detection**
```java
// Same (tenant, email, type) within 24h
SELECT 1 FROM cveilconsent.dsar_request
WHERE tenant_id = ? AND requester_email = ? AND request_type = ?
AND created_at > CURRENT_TIMESTAMP - INTERVAL '24 hours'
AND status NOT IN ('REJECTED', 'CANCELLED')
```

4. **Data Insertion**
```java
INSERT INTO cveilconsent.dsar_request (
    dsar_request_uuid, tenant_id, subject_id, requester_email,
    requester_name, request_type, status, notes, due_date)
VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
```

5. **Activity Logging**
```java
INSERT INTO cveilconsent.dsar_activity_log (
    dsar_activity_uuid, tenant_id, request_uuid, action, performed_by, details)
VALUES (?, ?, ?, 'SUBMITTED', ?, 'Self-service submission')
```

6. **Enumeration Prevention**
- Always returns HTTP 202 (ACCEPTED) for both:
  - Valid submissions
  - Unknown tenant slugs
  - Rate limit exceeded
  - Duplicate request
- Prevents attackers from inferring tenant existence

---

## 6. DSAR DTOs (ConsentDtos.java)

### SubjectDataExport
```java
public record SubjectDataExport(
    String subject,
    SubjectConsentView consent,
    long cachedKeyCount,
    Instant exportedAt)
```

### DsarDeletionReceipt
```java
public record DsarDeletionReceipt(
    String subject,
    String deletionRequestId,
    long ledgerEntriesRevoked,
    long cachedKeysDeleted,
    String status,  // "PENDING_SHRED" or "PARTIAL_FAILURE"
    Instant completedAt)
```

### DsarAuditEntry
```java
public record DsarAuditEntry(
    String deletionRequestId,
    int policiesDeleted,
    int cachedKeysDeleted,
    int auditEntriesDeleted,
    String status,  // "SUCCESS", "PENDING_SHRED", "PARTIAL_FAILURE"
    String actor,
    String ipAddress,
    String userAgent,
    Instant createdAt)
```

### DsarAuditLogResponse
```java
public record DsarAuditLogResponse(
    String subjectId,
    List<DsarAuditEntry> entries)
```

---

## 7. Key Testing Patterns Summary

| Pattern | Purpose | Example |
|---------|---------|---------|
| **Nested @Nested Classes** | Group related tests into phases | Prerequisites, Subject Management, Consent Grant/Revoke, etc. |
| **@Order Annotations** | Ensure sequential execution | @Order(1), @Order(2), etc. |
| **Static Volatile Fields** | Share state across nested classes | `private static volatile String accessToken` |
| **awaitXxx() Helpers** | Poll async projections with timeout | `awaitRowExists()`, `awaitQueryValue()`, `awaitRedisState()` |
| **Three-Layer Verification** | Check REST → Events → Projections | Grant test: API call → event store → consent ledger |
| **Idempotency in Setup** | Handle repeated test runs | Try provision, catch 409, fallback to login |
| **Tenant Isolation** | Use separate JWT + IntegrationTestSupport per tenant | testExportTenantIsolation() |
| **Helper Methods** | Reusable test utilities | grantConsent(), revokeConsent(), exportSubject() |
| **Rate Limit Testing** | Verify limits on public endpoints | Public DSAR: 5 per email per 24h |
| **Duplicate Detection** | Prevent abuse on public endpoints | Same (tenant, email, type) within 24h |

---

## 8. Running Integration Tests

**Full Suite:**
```bash
mvn clean verify -Dgroups=integration
```

**Single Test Class:**
```bash
mvn test -Dtest=ConsentResourceIT
```

**Single Test Method:**
```bash
mvn test -Dtest=ConsentResourceIT#testExportCompleteness
```

**With Docker Cleanup:**
```bash
mvn clean verify
# Containers automatically cleaned up by Testcontainers JVM shutdown hook
```

---

## 9. Critical Configuration

### Test Application Configuration (application.yml)
```yaml
cveil:
  test:
    db:
      url: ${cveil.test.db.url}
      username: ${cveil.test.db.username}
      password: ${cveil.test.db.password}
    redis:
      host: ${cveil.test.redis.host}
      port: ${cveil.test.redis.port}
    keycloak:
      url: ${cveil.test.keycloak.url}
      admin-username: ${cveil.test.keycloak.admin-username}
      admin-password: ${cveil.test.keycloak.admin-password}
      client-secret: ${cveil.test.keycloak.client-secret}
```

### Bootstrap Key
```
test-bootstrap-key-for-integration-tests
(hardcoded in tests, matched in application.yml)
```

### Test Tenant IDs
- Platform tests: `consent_it_tenant`
- DSAR isolation: `dsar_iso_tenant_a`, `dsar_iso_tenant_b`
- Named after test class with underscores

---

## 10. Best Practices Observed

1. **Use Awaitility for Async Operations**
   - Poll every 500ms (or custom interval)
   - Timeout 30 seconds (or custom)
   - Use untilAsserted() with assertions

2. **Separate Concerns**
   - CveilTestResourceManager: container lifecycle
   - IntegrationTestSupport: query utilities
   - *IT.java tests: business logic assertions

3. **Readable Test Names**
   - Use @DisplayName with emoji/symbols: "Export should return..."
   - Use nested classes with @Order for phases
   - Each test method tests ONE thing

4. **Idempotent Setup**
   - Catch 409 conflicts gracefully
   - Fallback to alternative operations
   - Allow re-running same tests multiple times

5. **Multi-Layer Verification**
   - Assert HTTP status code
   - Check event store (synchronous)
   - Poll database projection (asynchronous)
   - Query Redis cache (eventual consistency)

6. **Tenant Isolation**
   - Use separate JWT tokens
   - Use separate IntegrationTestSupport with tenantId
   - Verify cross-tenant leaks are impossible

7. **Security Testing**
   - Test with invalid credentials (401)
   - Test authorization boundaries (403)
   - Test rate limiting on public endpoints

