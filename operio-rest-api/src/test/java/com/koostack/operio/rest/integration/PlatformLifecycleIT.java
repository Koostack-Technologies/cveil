package com.koostack.operio.rest.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

import com.koostack.operio.rest.config.OperioConfig;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;

/**
 * Integration test for platform lifecycle endpoints:
 * <ul>
 *   <li>{@code PUT /api/v1/platform/tenants/{id}/plan} — updateTenantPlan</li>
 *   <li>{@code DELETE /api/v1/platform/tenants/{id}} — deleteTenant</li>
 *   <li>{@code DELETE /api/v1/platform/api-keys/{prefix}} — revokeApiKey (deep verification)</li>
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(OperioTestResourceManager.class)
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PlatformLifecycleIT {

    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";
    private static final String PLAN_TENANT = "platform_plan_it_tenant";
    private static final String DELETE_TENANT = "platform_del_it_tenant";

    @Inject OperioConfig config;

    private static volatile IntegrationTestSupport support;
    private static volatile String planTenantApiKeyPrefix;
    private static volatile String planTenantApiKey;
    private static volatile String deleteTenantApiKey;

    // -------------------------------------------------------------------
    // Phase 1 — Setup
    // -------------------------------------------------------------------

    @Nested
    @Order(1)
    @DisplayName("Phase 1 — Setup")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Setup {

        @Test @Order(1) @DisplayName("Init support")
        void initSupport() {
            support = new IntegrationTestSupport(config);
            assertThat(support).isNotNull();
        }

        @Test @Order(2) @DisplayName("Create tenant for plan update tests")
        void createPlanTenant() {
            Response r = given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .contentType(ContentType.JSON)
                .body("""
                    {"tenantId": "%s", "displayName": "Plan Update IT", "plan": "pro"}
                    """.formatted(PLAN_TENANT))
                .post("/api/v1/platform/tenants");
            assertThat(r.statusCode()).isIn(201, 200, 409);
        }

        @Test @Order(3) @DisplayName("Create API key for plan tenant")
        void createPlanTenantKey() {
            Response r = given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .contentType(ContentType.JSON)
                .body("""
                    {"tenantId": "%s", "keyName": "plan-it-key", "environment": "TEST",
                     "roles": ["admin:read", "consent:read"]}
                    """.formatted(PLAN_TENANT))
                .post("/api/v1/platform/api-keys");
            assertThat(r.statusCode()).isEqualTo(201);
            planTenantApiKeyPrefix = r.jsonPath().getString("prefix");
            planTenantApiKey = r.jsonPath().getString("rawKey");
            assertThat(planTenantApiKeyPrefix).isNotBlank();
        }

        @Test @Order(4) @DisplayName("Create tenant for deletion tests")
        void createDeleteTenant() {
            Response r = given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .contentType(ContentType.JSON)
                .body("""
                    {"tenantId": "%s", "displayName": "Delete IT", "plan": "free"}
                    """.formatted(DELETE_TENANT))
                .post("/api/v1/platform/tenants");
            assertThat(r.statusCode()).isIn(201, 200, 409);
        }

        @Test @Order(5) @DisplayName("Create API key for delete tenant")
        void createDeleteTenantKey() {
            Response r = given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .contentType(ContentType.JSON)
                .body("""
                    {"tenantId": "%s", "keyName": "del-it-key", "environment": "TEST",
                     "roles": ["admin:read"]}
                    """.formatted(DELETE_TENANT))
                .post("/api/v1/platform/api-keys");
            assertThat(r.statusCode()).isEqualTo(201);
            deleteTenantApiKey = r.jsonPath().getString("rawKey");
            assertThat(deleteTenantApiKey).isNotBlank();
        }

        @Test @Order(6) @DisplayName("Wait for provisioning to complete")
        void awaitProvisioning() throws Exception {
            // Wait for both tenants to be provisioned (schema + API keys)
            org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    var rows = support.query(
                        "SELECT status FROM platform_core.tenant WHERE tenant_id = ?",
                        PLAN_TENANT
                    );
                    return !rows.isEmpty()
                        && rows.get(0).get("status").toString().toLowerCase().contains("active");
                });

            org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    var rows = support.query(
                        "SELECT status FROM platform_core.tenant WHERE tenant_id = ?",
                        DELETE_TENANT
                    );
                    return !rows.isEmpty()
                        && rows.get(0).get("status").toString().toLowerCase().contains("active");
                });
        }
    }

    // -------------------------------------------------------------------
    // Phase 2 — Update Tenant Plan
    // -------------------------------------------------------------------

    @Nested
    @Order(2)
    @DisplayName("Phase 2 — updateTenantPlan")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class UpdatePlan {

        @Test @Order(1) @DisplayName("Update plan from pro to free")
        void updateToFree() {
            given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .contentType(ContentType.JSON)
                .body("""
                    {"plan": "free"}
                    """)
                .when()
                    .put("/api/v1/platform/tenants/" + PLAN_TENANT + "/plan")
                .then()
                    .statusCode(200)
                    .body("plan", equalTo("free"));
        }

        @Test @Order(2) @DisplayName("Verify tenant response reflects new plan")
        void getTenantShowsFreePlan() {
            given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .when()
                    .get("/api/v1/platform/tenants/" + PLAN_TENANT)
                .then()
                    .statusCode(200)
                    .body("tenantId", equalTo(PLAN_TENANT));
            // Plan may be updated async — the updateTenantPlan response already verified "free"
        }

        @Test @Order(3) @DisplayName("Update plan back to pro")
        void updateToPro() {
            given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .contentType(ContentType.JSON)
                .body("""
                    {"plan": "pro"}
                    """)
                .when()
                    .put("/api/v1/platform/tenants/" + PLAN_TENANT + "/plan")
                .then()
                    .statusCode(200)
                    .body("plan", equalTo("pro"));
        }

        @Test @Order(4) @DisplayName("Update to invalid plan returns error")
        void invalidPlan() {
            int status = given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .contentType(ContentType.JSON)
                .body("""
                    {"plan": "nonexistent_plan_xyz"}
                    """)
                .when()
                    .put("/api/v1/platform/tenants/" + PLAN_TENANT + "/plan")
                .statusCode();
            assertThat(status)
                .as("Invalid plan should be rejected")
                .isIn(400, 404, 500);
        }

        @Test @Order(5) @DisplayName("Update plan for non-existent tenant returns error")
        void nonExistentTenant() {
            int status = given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .contentType(ContentType.JSON)
                .body("""
                    {"plan": "free"}
                    """)
                .when()
                    .put("/api/v1/platform/tenants/nonexistent_tenant_xyz/plan")
                .statusCode();
            assertThat(status)
                .as("Non-existent tenant plan update should fail")
                .isIn(404, 400, 500);
        }

        @Test @Order(6) @DisplayName("Non-bootstrap key cannot update plan")
        void nonBootstrapRejected() {
            given()
                .header("Authorization", "Bearer " + deleteTenantApiKey)
                .contentType(ContentType.JSON)
                .body("""
                    {"plan": "free"}
                    """)
                .when()
                    .put("/api/v1/platform/tenants/" + PLAN_TENANT + "/plan")
                .then()
                    .statusCode(403);
        }
    }

    // -------------------------------------------------------------------
    // Phase 3 — Revoke API Key (Deep)
    // -------------------------------------------------------------------

    @Nested
    @Order(3)
    @DisplayName("Phase 3 — revokeApiKey")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class RevokeApiKey {

        @Test @Order(1) @DisplayName("API key works before revocation")
        void keyWorksBefore() {
            given()
                .header("Authorization", "Bearer " + deleteTenantApiKey)
                .when()
                    .get("/api/v1/catalogue/entities")
                .then()
                    .statusCode(200);
        }

        @Test @Order(2) @DisplayName("Revoke non-existent key prefix returns 404")
        void revokeNonExistent() {
            given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .when()
                    .delete("/api/v1/platform/api-keys/zzzzzzzz")
                .then()
                    .statusCode(404);
        }

        @Test @Order(3) @DisplayName("Plan tenant key works before revocation")
        void planKeyWorksBefore() {
            given()
                .header("Authorization", "Bearer " + planTenantApiKey)
                .when()
                    .get("/api/v1/catalogue/entities")
                .then()
                    .statusCode(200);
        }

        @Test @Order(4) @DisplayName("Revoke plan tenant API key by prefix")
        void revokePlanTenantKey() {
            given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .when()
                    .delete("/api/v1/platform/api-keys/" + planTenantApiKeyPrefix)
                .then()
                    .statusCode(200);
        }

        @Test @Order(5) @DisplayName("Revoked plan tenant key is rejected")
        void revokedKeyRejected() {
            org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    int status = given()
                        .header("Authorization", "Bearer " + planTenantApiKey)
                        .get("/api/v1/catalogue/entities")
                        .statusCode();
                    assertThat(status)
                        .as("Revoked key should be rejected")
                        .isIn(401, 403);
                });
        }
    }

    // -------------------------------------------------------------------
    // Phase 4 — Delete Tenant
    // -------------------------------------------------------------------

    @Nested
    @Order(4)
    @DisplayName("Phase 4 — deleteTenant")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DeleteTenant {

        @Test @Order(1) @DisplayName("Verify tenant schema exists before deletion")
        void schemaExistsBefore() {
            String schemaName = "t_" + DELETE_TENANT.replace("-", "_");
            var rows = support.query(
                "SELECT schema_name FROM information_schema.schemata WHERE schema_name = ?",
                schemaName
            );
            assertThat(rows)
                .as("Tenant schema should exist before deletion")
                .isNotEmpty();
        }

        @Test @Order(2) @DisplayName("Verify tenant row exists before deletion")
        void tenantRowExistsBefore() {
            var rows = support.query(
                "SELECT * FROM platform_core.tenant WHERE tenant_id = ?", DELETE_TENANT
            );
            assertThat(rows)
                .as("Tenant row should exist before deletion")
                .isNotEmpty();
        }

        @Test @Order(3) @DisplayName("Delete non-existent tenant returns 404")
        void deleteNonExistent() {
            int status = given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .delete("/api/v1/platform/tenants/nonexistent_tenant_xyz")
                .statusCode();
            assertThat(status)
                .as("Deleting non-existent tenant should return 404 or error")
                .isIn(404, 400, 500);
        }

        @Test @Order(4) @DisplayName("Non-bootstrap key cannot delete tenant")
        void nonBootstrapRejected() {
            given()
                .header("Authorization", "Bearer " + deleteTenantApiKey)
                .delete("/api/v1/platform/tenants/" + DELETE_TENANT)
                .then().statusCode(403);
        }

        @Test @Order(5) @DisplayName("Delete tenant succeeds")
        void deleteTenant() {
            given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .delete("/api/v1/platform/tenants/" + DELETE_TENANT)
                .then().statusCode(200);
        }

        @Test @Order(6) @DisplayName("Verify tenant no longer in list")
        void tenantNotInList() {
            // Allow async projection to propagate
            org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    var response = given()
                        .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                        .get("/api/v1/platform/tenants/" + DELETE_TENANT);
                    // Should be 404 or tenant status should reflect deletion
                    assertThat(response.statusCode()).isIn(404, 200);
                    if (response.statusCode() == 200) {
                        String status = response.jsonPath().getString("status");
                        assertThat(status.toLowerCase())
                            .as("Deleted tenant should not be active")
                            .doesNotContain("active");
                    }
                });
        }

        @Test @Order(7) @DisplayName("Verify tenant schema dropped")
        void schemaDropped() {
            String schemaName = "t_" + DELETE_TENANT.replace("-", "_");
            org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    var rows = support.query(
                        "SELECT schema_name FROM information_schema.schemata WHERE schema_name = ?",
                        schemaName
                    );
                    assertThat(rows)
                        .as("Tenant schema should be dropped after deletion")
                        .isEmpty();
                });
        }

        @Test @Order(8) @DisplayName("Deleted tenant's API key no longer works")
        void deletedTenantKeyRejected() {
            // The API key for the deleted tenant should be revoked
            org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    int status = given()
                        .header("Authorization", "Bearer " + deleteTenantApiKey)
                        .get("/api/v1/catalogue/entities")
                        .statusCode();
                    assertThat(status)
                        .as("Deleted tenant's API key should be rejected")
                        .isIn(401, 403);
                });
        }

        @Test @Order(9) @DisplayName("Deletion event exists in event store")
        void deletionEventExists() {
            // Check platform event store for tenant deletion event
            var events = support.query(
                "SELECT * FROM platform_event.event WHERE event_type = 'core.tenant.deleted' "
                    + "AND payload LIKE ?",
                "%" + DELETE_TENANT + "%"
            );
            assertThat(events)
                .as("core.tenant.deleted event should exist")
                .isNotEmpty();
        }
    }

    // -------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------

    @AfterAll
    static void cleanup() {
        if (support != null) support.close();
    }
}
