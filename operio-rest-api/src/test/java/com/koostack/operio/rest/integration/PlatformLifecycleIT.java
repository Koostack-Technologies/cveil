package com.koostack.operio.rest.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

import com.koostack.operio.rest.config.OperioConfig;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import org.junit.jupiter.api.*;

/**
 * Integration test for platform lifecycle endpoints:
 *
 * <ul>
 *   <li>{@code PUT /api/v1/platform/tenants/{id}/plan} — updateTenantPlan
 *   <li>{@code DELETE /api/v1/platform/tenants/{id}} — deleteTenant
 * </ul>
 *
 * <p>Tenants are created via the user-registration orchestrator ({@code POST
 * /api/v1/platform/iam/users} with {@code organizationName}), which provisions the user, org, Redis
 * streams, tenant row, billing profile, and free subscription. Auth uses Keycloak JWT.
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
    private static volatile String planTenantAccessToken;
    private static volatile String deleteTenantAccessToken;

    // -------------------------------------------------------------------
    // Phase 1 — Setup
    // -------------------------------------------------------------------

    @Nested
    @Order(1)
    @DisplayName("Phase 1 — Setup")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Setup {

        @Test
        @Order(1)
        @DisplayName("Init support")
        void initSupport() {
            support = new IntegrationTestSupport(config);
            assertThat(support).isNotNull();
        }

        @Test
        @Order(2)
        @DisplayName("Create plan tenant and login")
        void createPlanTenantAndLogin() {
            IntegrationTestSupport.AuthInfo auth =
                    IntegrationTestSupport.provisionTenantAndLogin(
                            BOOTSTRAP_KEY, PLAN_TENANT, "Plan Update IT Tenant");
            planTenantAccessToken = auth.accessToken();
            assertThat(planTenantAccessToken).isNotBlank();
            support.awaitTenantReady(PLAN_TENANT, Duration.ofSeconds(90));
        }

        @Test
        @Order(3)
        @DisplayName("Create delete tenant and login")
        void createDeleteTenantAndLogin() {
            IntegrationTestSupport.AuthInfo auth =
                    IntegrationTestSupport.provisionTenantAndLogin(
                            BOOTSTRAP_KEY, DELETE_TENANT, "Delete IT Tenant");
            deleteTenantAccessToken = auth.accessToken();
            assertThat(deleteTenantAccessToken).isNotBlank();
            support.awaitTenantReady(DELETE_TENANT, Duration.ofSeconds(90));
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

        @Test
        @Order(1)
        @DisplayName("Update plan from free to pro")
        void updateToPro() {
            given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                    .contentType(ContentType.JSON)
                    .body("""
                    {"plan": "pro"}
                    """)
                    .when()
                    .put("/api/v1/platform/tenants/" + PLAN_TENANT + "/plan")
                    .then()
                    .statusCode(200)
                    .body("message", containsString("pro"));
        }

        @Test
        @Order(2)
        @DisplayName("Verify plan change projected")
        void verifyPlanProjected() {
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofSeconds(1))
                    .untilAsserted(
                            () -> {
                                var rows =
                                        support.query(
                                                "SELECT plan FROM platform_core.tenant WHERE"
                                                        + " tenant_id = ?",
                                                PLAN_TENANT);
                                assertThat(rows).isNotEmpty();
                                assertThat(rows.get(0).get("plan").toString())
                                        .as("Plan should be projected as 'pro'")
                                        .isEqualTo("pro");
                            });
        }

        @Test
        @Order(3)
        @DisplayName("Update plan back to free")
        void updateToFree() {
            given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                    .contentType(ContentType.JSON)
                    .body("""
                    {"plan": "free"}
                    """)
                    .when()
                    .put("/api/v1/platform/tenants/" + PLAN_TENANT + "/plan")
                    .then()
                    .statusCode(200)
                    .body("message", containsString("free"));
        }

        @Test
        @Order(4)
        @DisplayName("Update to invalid plan dispatches successfully")
        void invalidPlan() {
            // CQRS: command dispatch succeeds; validation happens asynchronously
            given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"plan": "nonexistent_plan_xyz"}
                    """)
                    .when()
                    .put("/api/v1/platform/tenants/" + PLAN_TENANT + "/plan")
                    .then()
                    .statusCode(200);
        }

        @Test
        @Order(5)
        @DisplayName("Update plan for non-existent tenant dispatches successfully")
        void nonExistentTenant() {
            // CQRS: command dispatch succeeds even for non-existent tenants
            given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                    .contentType(ContentType.JSON)
                    .body("""
                    {"plan": "free"}
                    """)
                    .when()
                    .put("/api/v1/platform/tenants/nonexistent_tenant_xyz/plan")
                    .then()
                    .statusCode(200);
        }


    }

    // -------------------------------------------------------------------
    // Phase 3 — Delete Tenant
    // -------------------------------------------------------------------

    @Nested
    @Order(3)
    @DisplayName("Phase 3 — deleteTenant")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DeleteTenant {

        @Test
        @Order(1)
        @DisplayName("Verify tenant row exists before deletion")
        void tenantRowExistsBefore() {
            var rows =
                    support.query(
                            "SELECT * FROM platform_core.tenant WHERE tenant_id = ?",
                            DELETE_TENANT);
            assertThat(rows).as("Tenant row should exist before deletion").isNotEmpty();
        }

        @Test
        @Order(2)
        @DisplayName("Delete non-existent tenant dispatches successfully")
        void deleteNonExistent() {
            // CQRS: command dispatch succeeds even for non-existent tenants
            given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                    .delete("/api/v1/platform/tenants/nonexistent_tenant_xyz")
                    .then()
                    .statusCode(200);
        }



        @Test
        @Order(4)
        @DisplayName("Delete tenant succeeds")
        void deleteTenant() {
            given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                    .delete("/api/v1/platform/tenants/" + DELETE_TENANT)
                    .then()
                    .statusCode(200);
        }

        @Test
        @Order(5)
        @DisplayName("Verify tenant no longer in list")
        void tenantNotInList() {
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofSeconds(1))
                    .untilAsserted(
                            () -> {
                                var response =
                                        given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                                                .get("/api/v1/platform/tenants/" + DELETE_TENANT);
                                assertThat(response.statusCode()).isIn(404, 200);
                                if (response.statusCode() == 200) {
                                    String status = response.jsonPath().getString("status");
                                    assertThat(status.toLowerCase())
                                            .as("Deleted tenant should not be active")
                                            .doesNotContain("active");
                                }
                            });
        }

        @Test
        @Order(6)
        @DisplayName("Deleted tenant's JWT still authenticates (token not invalidated on deletion)")
        void deletedTenantTokenStillWorks() {
            Assumptions.assumeTrue(
                    deleteTenantAccessToken != null && !deleteTenantAccessToken.isBlank(),
                    "Delete tenant access token must be available");
            // Tenant deletion sets tenant status to 'deleted' but does NOT invalidate JWTs.
            // The JWT is still valid until it expires.
            given().header("Authorization", "Bearer " + deleteTenantAccessToken)
                    .get("/api/v1/catalogue/entities")
                    .then()
                    .statusCode(200);
        }

        @Test
        @Order(7)
        @DisplayName("Deletion event exists in event store")
        void deletionEventExists() {
            var events =
                    support.query(
                            "SELECT * FROM operio_event.event WHERE event_type ="
                                    + " 'core.tenant.deleted' AND payload::text LIKE ?",
                            "%" + DELETE_TENANT + "%");
            assertThat(events).as("core.tenant.deleted event should exist").isNotEmpty();
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
