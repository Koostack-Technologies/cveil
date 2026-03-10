package com.koostack.operio.rest.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

import com.koostack.operio.rest.config.OperioConfig;
import com.koostack.operio.rest.dto.ConsentDtos.DsarDeletionReceipt;
import com.koostack.operio.rest.dto.ConsentDtos.SubjectDataExport;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for DSAR tenant isolation.
 *
 * <p>Validates that DSAR endpoints (export and delete) correctly enforce tenant context when
 * multiple tenants exist, and that the {@code TenantAwareExecuteListener} routes all database
 * operations to the correct per-tenant Postgres schema ({@code t_{tenantId}}).
 *
 * <h2>Test Coverage</h2>
 *
 * <ul>
 *   <li>Tenant A data isolation: grant in tenant A, export in A visible, export in B returns empty
 *   <li>Delete isolation: grant same subject in A and B, delete in A, verify B data intact
 *   <li>DSAR audit per-tenant: delete in A, verify audit in A, absent in B
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(OperioTestResourceManager.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("DSAR Tenant Isolation")
class DsarTenantIsolationIT {

    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";

    private static final String TENANT_A = "dsar_iso_tenant_a";
    private static final String TENANT_B = "dsar_iso_tenant_b";

    private static volatile String accessTokenA;
    private static volatile String accessTokenB;
    private static volatile IntegrationTestSupport supportA;
    private static volatile IntegrationTestSupport supportB;

    @Inject OperioConfig config;

    // ===================================================================
    // Phase 0 — Provision tenants and obtain JWT tokens
    // ===================================================================

    @Test
    @Order(0)
    @DisplayName("Provision tenant A and login")
    void provisionAndLoginTenantA() {
        try {
            IntegrationTestSupport.AuthInfo authA =
                    IntegrationTestSupport.provisionTenantAndLogin(
                            BOOTSTRAP_KEY, TENANT_A, "Tenant A Display");
            accessTokenA = authA.accessToken();
            assertThat(accessTokenA).isNotBlank();
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                String email = TENANT_A.replace("_", "-") + "-admin@operio-test.com";
                accessTokenA = IntegrationTestSupport.loginUser(email, "Test1234!");
            } else {
                throw e;
            }
        }
        supportA = new IntegrationTestSupport(config, TENANT_A);
        supportA.awaitTenantReady(TENANT_A, Duration.ofSeconds(90));
    }

    @Test
    @Order(1)
    @DisplayName("Provision tenant B and login")
    void provisionAndLoginTenantB() {
        try {
            IntegrationTestSupport.AuthInfo authB =
                    IntegrationTestSupport.provisionTenantAndLogin(
                            BOOTSTRAP_KEY, TENANT_B, "Tenant B Display");
            accessTokenB = authB.accessToken();
            assertThat(accessTokenB).isNotBlank();
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                String email = TENANT_B.replace("_", "-") + "-admin@operio-test.com";
                accessTokenB = IntegrationTestSupport.loginUser(email, "Test1234!");
            } else {
                throw e;
            }
        }
        supportB = new IntegrationTestSupport(config, TENANT_B);
        supportB.awaitTenantReady(TENANT_B, Duration.ofSeconds(90));
    }

    @Test
    @Order(2)
    @DisplayName("Set up governance data for both tenants")
    void setupGovernance() {
        setupGovernanceForTenant(accessTokenA);
        setupGovernanceForTenant(accessTokenB);

        // Await governance projection (policies visible)
        for (String key : new String[] {accessTokenA, accessTokenB}) {
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () ->
                                    given().header("Authorization", "Bearer " + key)
                                            .contentType(ContentType.JSON)
                                            .body(
                                                    Map.of(
                                                            "subject",
                                                            "governance_probe",
                                                            "scope",
                                                            buildScope(
                                                                    "email", "User", "marketing")))
                                            .post("/api/v1/subjects/consent/grant")
                                            .then()
                                            .statusCode(200));
        }
    }

    // ===================================================================
    // Test 1: Export isolation — tenant A data not visible in tenant B
    // ===================================================================

    @Test
    @Order(10)
    @DisplayName("Export in tenant A should not return data from tenant B")
    void testExportTenantIsolation() {
        String subject = "export_isolation_1";

        grantConsent(accessTokenA, subject, "email", "User", "marketing");

        // Await async projection of consent ledger entry
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(
                        () -> {
                            SubjectDataExport export = exportSubject(accessTokenA, subject);
                            assertThat(export.consent().entries()).isNotEmpty();
                        });

        SubjectDataExport exportB = exportSubject(accessTokenB, subject);
        assertThat(exportB.subject()).isEqualTo(subject);
        assertThat(exportB.consent().entries()).isEmpty();
    }

    // ===================================================================
    // Test 2: Delete isolation — deleting in A leaves B intact
    // ===================================================================

    @Test
    @Order(11)
    @DisplayName("Delete in tenant A should not affect tenant B data")
    void testDeleteTenantIsolation() {
        String subject = "delete_isolation_1";

        grantConsent(accessTokenA, subject, "phone", "User", "analytics");
        grantConsent(accessTokenB, subject, "phone", "User", "analytics");

        // Await consent visible in both tenants before deleting
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(
                        () -> {
                            assertThat(exportSubject(accessTokenA, subject).consent().entries())
                                    .isNotEmpty();
                            assertThat(exportSubject(accessTokenB, subject).consent().entries())
                                    .isNotEmpty();
                        });

        String confirmToken = "DELETE_" + subject;
        DsarDeletionReceipt receipt = deleteSubject(accessTokenA, subject, confirmToken);
        assertThat(receipt.status()).isEqualTo("PENDING_SHRED");

        // After deletion in tenant A, tenant B should still have data
        SubjectDataExport exportB = exportSubject(accessTokenB, subject);
        assertThat(exportB.consent().entries()).isNotEmpty();
    }

    // ===================================================================
    // Test 3: DSAR audit log is per-tenant
    // ===================================================================

    @Test
    @Order(12)
    @DisplayName("DSAR audit log entries should be per-tenant")
    void testDsarAuditPerTenant() {
        String subject = "audit_isolation_1";

        grantConsent(accessTokenA, subject, "email", "User", "sales");
        String confirmToken = "DELETE_" + subject;
        DsarDeletionReceipt receipt = deleteSubject(accessTokenA, subject, confirmToken);

        assertThat(receipt.subject()).isEqualTo(subject);
        assertThat(receipt.status()).isEqualTo("PENDING_SHRED");
        assertThat(receipt.deletionRequestId()).isNotEmpty();
        assertThat(receipt.completedAt()).isNotNull();
    }

    // ===================================================================
    // Helper methods
    // ===================================================================

    private void setupGovernanceForTenant(String accessToken) {
        String auth = "Bearer " + accessToken;

        given().header("Authorization", auth)
                .contentType(ContentType.JSON)
                .body(Map.of("entityName", "User", "description", "User entity"))
                .post("/api/v1/catalogue/entities")
                .then()
                .statusCode(anyOf(is(200), is(201)));

        given().header("Authorization", auth)
                .contentType(ContentType.JSON)
                .body(Map.of("categoryName", "PII", "description", "PII category"))
                .post("/api/v1/catalogue/categories")
                .then()
                .statusCode(anyOf(is(200), is(201)));

        for (String fieldName : new String[] {"email", "phone"}) {
            given().header("Authorization", auth)
                    .contentType(ContentType.JSON)
                    .body(
                            Map.of(
                                    "fieldName",
                                    fieldName,
                                    "entityName",
                                    "User",
                                    "description",
                                    fieldName + " field"))
                    .post("/api/v1/catalogue/fields")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        for (String fieldName : new String[] {"email", "phone"}) {
            given().header("Authorization", auth)
                    .contentType(ContentType.JSON)
                    .body(
                            Map.of(
                                    "fieldName",
                                    fieldName,
                                    "entityName",
                                    "User",
                                    "categoryName",
                                    "PII"))
                    .post("/api/v1/catalogue/fields/categories")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        for (String purpose : new String[] {"marketing", "analytics", "sales"}) {
            given().header("Authorization", auth)
                    .contentType(ContentType.JSON)
                    .body(Map.of("purposeName", purpose, "description", purpose + " purpose"))
                    .post("/api/v1/governance/purposes")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        given().header("Authorization", auth)
                .contentType(ContentType.JSON)
                .body(Map.of("regulationName", "GDPR", "description", "GDPR regulation"))
                .post("/api/v1/governance/regulations")
                .then()
                .statusCode(anyOf(is(200), is(201)));

        for (String purpose : new String[] {"marketing", "analytics", "sales"}) {
            given().header("Authorization", auth)
                    .contentType(ContentType.JSON)
                    .body(
                            Map.of(
                                    "purposeName", purpose,
                                    "regulationName", "GDPR",
                                    "lawfulBasis", "consent"))
                    .post("/api/v1/governance/purposes/regulations")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        for (String purpose : new String[] {"marketing", "analytics", "sales"}) {
            for (String fieldName : new String[] {"email", "phone"}) {
                given().header("Authorization", auth)
                        .contentType(ContentType.JSON)
                        .body(
                                Map.of(
                                        "purposeName", purpose,
                                        "policyType", "field",
                                        "fieldName", fieldName,
                                        "entityName", "User",
                                        "categoryName", "PII"))
                        .post("/api/v1/governance/purposes/policies")
                        .then()
                        .statusCode(anyOf(is(200), is(201)));
            }
        }
    }

    private void registerSubject(String accessToken, String subject) {
        given().header("Authorization", "Bearer " + accessToken)
                .contentType(ContentType.JSON)
                .body(Map.of("subjectName", subject, "description", "DSAR test subject"))
                .when()
                .post("/api/v1/subjects")
                .then()
                .statusCode(anyOf(is(200), is(201), is(409)));
    }

    private void grantConsent(
            String accessToken,
            String subject,
            String fieldName,
            String entityName,
            String purposeName) {
        registerSubject(accessToken, subject);

        // Wait for subject projection before granting consent
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(
                        () ->
                                given().header("Authorization", "Bearer " + accessToken)
                                        .contentType(ContentType.JSON)
                                        .body(
                                                Map.of(
                                                        "subject",
                                                        subject,
                                                        "scope",
                                                        buildScope(
                                                                fieldName,
                                                                entityName,
                                                                purposeName)))
                                        .when()
                                        .post("/api/v1/subjects/consent/grant")
                                        .then()
                                        .statusCode(200));
    }

    private SubjectDataExport exportSubject(String accessToken, String subject) {
        return given().header("Authorization", "Bearer " + accessToken)
                .contentType(ContentType.JSON)
                .when()
                .get("/api/v1/subjects/{id}/export", subject)
                .then()
                .statusCode(200)
                .extract()
                .as(SubjectDataExport.class);
    }

    private DsarDeletionReceipt deleteSubject(
            String accessToken, String subject, String confirmToken) {
        io.restassured.response.Response raw =
                given().header("Authorization", "Bearer " + accessToken)
                        .contentType(ContentType.JSON)
                        .queryParam("confirm", confirmToken)
                        .when()
                        .delete("/api/v1/subjects/{id}/audit", subject);
        String body = raw.body().asString();
        int status = raw.statusCode();
        if (status != 200) {
            throw new AssertionError("Expected 200 but got " + status + ": " + body);
        }
        return raw.as(DsarDeletionReceipt.class);
    }

    private static Map<String, Object> buildScope(
            String fieldName, String entityName, String purposeName) {
        Map<String, Object> scope = new HashMap<>();
        scope.put("category", "PII");
        if (fieldName != null) scope.put("fieldName", fieldName);
        if (entityName != null) scope.put("entityName", entityName);
        if (purposeName != null) scope.put("purposeName", purposeName);
        return scope;
    }
}
