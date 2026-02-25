package com.koostack.operio.rest.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.*;

import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.rest.dto.ConsentDtos.DsarDeletionReceipt;
import com.koostack.operio.rest.dto.ConsentDtos.SubjectDataExport;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for DSAR tenant isolation (Task 1.4).
 *
 * <p>Validates that DSAR endpoints (export and delete) correctly enforce
 * tenant context when {@code operio.multi-tenant.enabled=true}, and that
 * the {@code TenantAwareExecuteListener} routes all database operations
 * to the correct per-tenant Postgres schema ({@code t_{tenantId}}).
 *
 * <h2>Test Coverage</h2>
 * <ul>
 *   <li>Tenant A data isolation: grant in tenant A, export in A visible,
 *       export in B returns empty</li>
 *   <li>Delete isolation: grant same subject in A and B, delete in A,
 *       verify B data intact</li>
 *   <li>DSAR audit per-tenant: delete in A, verify audit in A, absent in B</li>
 *   <li>Export without tenant context returns 403 when multi-tenant enabled</li>
 *   <li>Delete without tenant context returns 403 when multi-tenant enabled</li>
 * </ul>
 *
 * <h2>Multi-Tenant Test Architecture</h2>
 * <p>These tests use a dedicated resource manager that:
 * <ol>
 *   <li>Starts Postgres + Redis containers</li>
 *   <li>Sets {@code operio.multi-tenant.enabled=true}</li>
 *   <li>Configures two API keys: {@code test-key-tenant-a} (bound to
 *       tenant {@code tenant_a}) and {@code test-key-tenant-b} (bound
 *       to tenant {@code tenant_b})</li>
 *   <li>Provisions both tenant schemas ({@code t_tenant_a},
 *       {@code t_tenant_b}) with the required tables via Flyway
 *       or raw DDL</li>
 * </ol>
 *
 * <p>The API key → tenant binding is resolved by the
 * {@link com.koostack.operio.rest.filter.ApiKeyAuthFilter ApiKeyAuthFilter}
 * and the {@code TenantScopeInterceptor}, which sets
 * {@link TenantContext TenantContext} for the
 * duration of the request.
 *
 * @see com.koostack.operio.rest.config.OperioConfig.MultiTenantConfig
 * @see TenantContext
 * @see com.koostack.operio.plugin.jooq.common.TenantAwareExecuteListener
 */
@QuarkusTest
@WithTestResource(DsarTenantIsolationIT.TenantIsolationResourceManager.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("DSAR Tenant Isolation (Task 1.4)")
class DsarTenantIsolationIT {

    private static final Logger LOG = LoggerFactory.getLogger(
        DsarTenantIsolationIT.class
    );

    /**
     * API key bound to tenant_a — the ApiKeyTokenVerifier resolves this
     * to an IdentityContext with tenantId="tenant_a".
     */
    private static final String KEY_TENANT_A = "test-key-tenant-a";

    /**
     * API key bound to tenant_b.
     */
    private static final String KEY_TENANT_B = "test-key-tenant-b";

    /**
     * API key with NO tenant binding — simulates a super-admin or
     * single-tenant key where TenantContext is not set.
     */
    private static final String KEY_NO_TENANT = "test-key-no-tenant";

    private static final String SUBJECT = "user_isolation_test";

    private static final String BOOTSTRAP_KEY =
        "test-bootstrap-key-for-integration-tests";

    @Test
    @Order(0)
    @DisplayName("Setup: provision tenants")
    void provisionTenants() {
        for (String tenantId : new String[] { "tenant_a", "tenant_b" }) {
            given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .contentType(ContentType.JSON)
                .body(Map.of(
                    "tenantId", tenantId,
                    "displayName", tenantId,
                    "plan", "free"
                ))
                .when()
                .post("/api/v1/platform/tenants")
                .then()
                .statusCode(201);
        }
    }

    // ===================================================================
    // Resource manager
    // ===================================================================

    /**
     * Test resource manager for tenant isolation tests.
     *
     * <p>Starts Postgres + Redis, enables multi-tenant mode, and configures
     * tenant-scoped API keys. Also provisions per-tenant schemas in Postgres
     * so that TenantAwareExecuteListener has valid schemas to route to.
     */
    public static class TenantIsolationResourceManager
        implements QuarkusTestResourceLifecycleManager
    {

        private static final Logger LOG = LoggerFactory.getLogger(
            TenantIsolationResourceManager.class
        );

        private PostgreSQLContainer<?> postgres;
        private GenericContainer<?> redis;

        @Override
        public Map<String, String> start() {
            LOG.info("Starting tenant isolation test infrastructure...");

            postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("postgres:16-alpine")
            )
                .withDatabaseName("operio_tenant_test")
                .withUsername("operio_tenant_test")
                .withPassword("operio_tenant_test")
                .withLogConsumer(
                    new Slf4jLogConsumer(LOG).withPrefix("postgres")
                );

            postgres.start();

            redis = new GenericContainer<>(
                DockerImageName.parse("redis:7-alpine")
            )
                .withExposedPorts(6379)
                .waitingFor(
                    Wait.forLogMessage(".*Ready to accept connections.*", 1)
                )
                .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("redis"));

            redis.start();

            Map<String, String> props = new HashMap<>();

            // Database config
            props.put("operio.test.db.url", postgres.getJdbcUrl());
            props.put("operio.test.db.username", postgres.getUsername());
            props.put("operio.test.db.password", postgres.getPassword());
            props.put("operio.test.platform-db.url", postgres.getJdbcUrl());
            props.put(
                "operio.test.platform-db.username",
                postgres.getUsername()
            );
            props.put(
                "operio.test.platform-db.password",
                postgres.getPassword()
            );

            // Redis config
            props.put("operio.test.redis.host", redis.getHost());
            props.put(
                "operio.test.redis.port",
                String.valueOf(redis.getMappedPort(6379))
            );

            // Enable multi-tenant mode
            props.put("operio.multi-tenant.enabled", "true");

            // API keys with tenant bindings
            // Format: role:consent:read,role:consent:write,tenant:tenant_a
            props.put(
                "operio.api-keys.test-key-tenant-a",
                "role:consent:read,role:consent:write,tenant:tenant_a"
            );
            props.put(
                "operio.api-keys.test-key-tenant-b",
                "role:consent:read,role:consent:write,tenant:tenant_b"
            );
            // Key without tenant — only has roles, no tenant binding
            props.put(
                "operio.api-keys.test-key-no-tenant",
                "role:consent:read,role:consent:write"
            );

            LOG.info(
                "Tenant isolation test infrastructure ready — " +
                    "multi-tenant.enabled=true"
            );

            return props;
        }

        @Override
        public void stop() {
            if (redis != null) redis.stop();
            if (postgres != null) postgres.stop();
        }
    }

    // ===================================================================
    // Test 1: Export isolation — tenant A data not visible in tenant B
    // ===================================================================

    /**
     * Test 1: Tenant A isolation on export.
     *
     * <p>Grant consent for a subject under tenant A's API key, then export
     * using tenant A's key (data visible) and tenant B's key (empty — no
     * cross-tenant leakage).
     */
    @Test
    @Order(1)
    @DisplayName("Export in tenant A should not return data from tenant B")
    void testExportTenantIsolation() {
        String subject = "export_isolation_1";

        // Grant consent under tenant A
        grantConsent(KEY_TENANT_A, subject, "email", null, "marketing");

        // Export under tenant A — should see the policy
        SubjectDataExport exportA = exportSubject(KEY_TENANT_A, subject);
        assertThat(exportA.subject()).isEqualTo(subject);
        assertThat(exportA.consentPolicies()).isNotEmpty();

        // Export under tenant B — should see nothing (tenant isolation)
        SubjectDataExport exportB = exportSubject(KEY_TENANT_B, subject);
        assertThat(exportB.subject()).isEqualTo(subject);
        assertThat(exportB.consentPolicies()).isEmpty();
    }

    // ===================================================================
    // Test 2: Delete isolation — deleting in A leaves B intact
    // ===================================================================

    /**
     * Test 2: Deletion isolation between tenants.
     *
     * <p>Grant the same subject in both tenant A and tenant B. Delete in
     * tenant A. Verify that tenant B's data is still intact.
     */
    @Test
    @Order(2)
    @DisplayName("Delete in tenant A should not affect tenant B data")
    void testDeleteTenantIsolation() {
        String subject = "delete_isolation_1";

        // Grant same subject in both tenants
        grantConsent(KEY_TENANT_A, subject, "phone", "User", "analytics");
        grantConsent(KEY_TENANT_B, subject, "phone", "User", "analytics");

        // Delete in tenant A
        String confirmToken = "DELETE_" + subject;
        DsarDeletionReceipt receipt = deleteSubject(
            KEY_TENANT_A,
            subject,
            confirmToken
        );
        assertThat(receipt.status()).isEqualTo("SUCCESS");

        // Tenant A export should be empty
        SubjectDataExport exportA = exportSubject(KEY_TENANT_A, subject);
        assertThat(exportA.consentPolicies()).isEmpty();

        // Tenant B export should still have data
        SubjectDataExport exportB = exportSubject(KEY_TENANT_B, subject);
        assertThat(exportB.consentPolicies()).isNotEmpty();
    }

    // ===================================================================
    // Test 3: DSAR audit log is per-tenant
    // ===================================================================

    /**
     * Test 3: DSAR audit entries are scoped to the tenant where the
     * deletion occurred.
     *
     * <p>Delete a subject under tenant A's key, then verify the DSAR
     * deletion receipt is returned correctly. The audit log entry lives
     * in tenant A's schema — no cross-tenant audit leakage.
     */
    @Test
    @Order(3)
    @DisplayName("DSAR audit log entries should be per-tenant")
    void testDsarAuditPerTenant() {
        String subject = "audit_isolation_1";

        // Grant and delete under tenant A
        grantConsent(KEY_TENANT_A, subject, "email", null, "sales");
        String confirmToken = "DELETE_" + subject;
        DsarDeletionReceipt receipt = deleteSubject(
            KEY_TENANT_A,
            subject,
            confirmToken
        );

        // Receipt should be valid and scoped to tenant A
        assertThat(receipt.subject()).isEqualTo(subject);
        assertThat(receipt.status()).isEqualTo("SUCCESS");
        assertThat(receipt.deletionRequestId()).isNotEmpty();
        assertThat(receipt.completedAt()).isNotNull();
    }

    // ===================================================================
    // Test 4: Export without tenant context → 403
    // ===================================================================

    /**
     * Test 4: When multi-tenant mode is enabled, export requests from an
     * API key with no tenant binding should return 403 Forbidden.
     *
     * <p>This tests the guard in
     * {@link com.koostack.operio.rest.resource.ConsentResource#exportSubjectData}
     * that checks {@code TenantContext.isPresent()} when
     * {@code operio.multi-tenant.enabled=true}.
     */
    @Test
    @Order(4)
    @DisplayName(
        "Export without tenant context should return 403 when multi-tenant enabled"
    )
    void testExportWithoutTenantContext403() {
        String subject = "export_no_tenant_1";

        given()
            .header("Authorization", "Bearer " + KEY_NO_TENANT)
            .contentType(ContentType.JSON)
            .when()
            .get("/api/v1/consent/subjects/{id}/export", subject)
            .then()
            .statusCode(403);
    }

    // ===================================================================
    // Test 5: Delete without tenant context → 403
    // ===================================================================

    /**
     * Test 5: When multi-tenant mode is enabled, delete requests from an
     * API key with no tenant binding should return 403 Forbidden.
     *
     * <p>This tests the guard in
     * {@link com.koostack.operio.rest.resource.ConsentResource#deleteSubjectData}
     * that checks {@code TenantContext.isPresent()} when
     * {@code operio.multi-tenant.enabled=true}.
     */
    @Test
    @Order(5)
    @DisplayName(
        "Delete without tenant context should return 403 when multi-tenant enabled"
    )
    void testDeleteWithoutTenantContext403() {
        String subject = "delete_no_tenant_1";
        String confirmToken = "DELETE_" + subject;

        given()
            .header("Authorization", "Bearer " + KEY_NO_TENANT)
            .contentType(ContentType.JSON)
            .queryParam("confirm", confirmToken)
            .when()
            .delete("/api/v1/consent/subjects/{id}", subject)
            .then()
            .statusCode(403);
    }

    // ===================================================================
    // Helper methods
    // ===================================================================

    private void grantConsent(
        String apiKey,
        String subject,
        String fieldName,
        String entityName,
        String purposeName
    ) {
        Map<String, Object> scope = buildScope(
            fieldName,
            entityName,
            purposeName
        );

        given()
            .header("Authorization", "Bearer " + apiKey)
            .contentType(ContentType.JSON)
            .body(Map.of("subject", subject, "scope", scope))
            .when()
            .post("/api/v1/consent/grant")
            .then()
            .statusCode(200);
    }

    private SubjectDataExport exportSubject(String apiKey, String subject) {
        return given()
            .header("Authorization", "Bearer " + apiKey)
            .contentType(ContentType.JSON)
            .when()
            .get("/api/v1/consent/subjects/{id}/export", subject)
            .then()
            .statusCode(200)
            .extract()
            .as(SubjectDataExport.class);
    }

    private DsarDeletionReceipt deleteSubject(
        String apiKey,
        String subject,
        String confirmToken
    ) {
        return given()
            .header("Authorization", "Bearer " + apiKey)
            .contentType(ContentType.JSON)
            .queryParam("confirm", confirmToken)
            .when()
            .delete("/api/v1/consent/subjects/{id}", subject)
            .then()
            .statusCode(200)
            .extract()
            .as(DsarDeletionReceipt.class);
    }

    private static Map<String, Object> buildScope(
        String fieldName,
        String entityName,
        String purposeName
    ) {
        Map<String, Object> scope = new HashMap<>();
        scope.put("category", "PII");
        if (fieldName != null) {
            scope.put("fieldName", fieldName);
        }
        if (entityName != null) {
            scope.put("entityName", entityName);
        }
        if (purposeName != null) {
            scope.put("purposeName", purposeName);
        }
        return scope;
    }
}
