package com.koostack.cveil.site.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.koostack.cveil.site.config.CveilConfig;
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
 * Integration test for cross-tenant isolation in the unified IAM architecture.
 *
 * <p>Tenants are modelled as Keycloak Organizations within the single {@code cveil} realm. This
 * test provisions two separate users (each with a personal tenant), then each user creates their
 * own organization. The cross-tenant isolation tests verify that organization membership is
 * properly isolated: org A members should not appear in org B and vice versa.
 */
@QuarkusTest
@QuarkusTestResource(CveilTestResourceManager.class)
@DisplayName("TenantIamResource Integration Test — Cross-Tenant Isolation")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantIamResourceIT {

    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";
    private static final String PLATFORM_REALM = "cveil";
    private static final String BASE_PATH = "/api/v1/platform/iam";
    private static final String PASSWORD = "Test1234!";

    // Personal tenant IDs (from user registration)
    private static final String USER_A_TENANT = "iam_it_user_a";
    private static final String USER_B_TENANT = "iam_it_user_b";

    // Organization names (distinct from personal tenant IDs)
    private static final String ORG_A_NAME = "iam_it_org_a";
    private static final String ORG_B_NAME = "iam_it_org_b";

    // Usernames derived from personal tenant IDs (underscores → hyphens)
    private static final String USER_A_NAME = "iam-it-user-a";
    private static final String USER_B_NAME = "iam-it-user-b";

    private static volatile IntegrationTestSupport support;
    private static volatile String userAToken;
    private static volatile String userBToken;
    private static volatile String orgAId;
    private static volatile String orgBId;

    @Inject CveilConfig config;

    private String bootstrapAuth() {
        return "Bearer " + BOOTSTRAP_KEY;
    }

    // =======================================================================
    // 1. Setup — provision two users and two organizations
    // =======================================================================

    @Nested
    @Order(1)
    @DisplayName("1 — Setup")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Setup {

        @Test
        @Order(1)
        @DisplayName("Initialize test support")
        void initSupport() {
            support = new IntegrationTestSupport(config);
        }

        @Test
        @Order(2)
        @DisplayName("Provision user A and login")
        void provisionUserA() {
            IntegrationTestSupport.AuthInfo auth =
                    IntegrationTestSupport.provisionTenantAndLogin(
                            BOOTSTRAP_KEY, USER_A_TENANT, "User A");
            userAToken = auth.accessToken();
            assertNotNull(userAToken, "User A JWT required");
        }

        @Test
        @Order(3)
        @DisplayName("Provision user B and login")
        void provisionUserB() {
            IntegrationTestSupport.AuthInfo auth =
                    IntegrationTestSupport.provisionTenantAndLogin(
                            BOOTSTRAP_KEY, USER_B_TENANT, "User B");
            userBToken = auth.accessToken();
            assertNotNull(userBToken, "User B JWT required");
        }

        @Test
        @Order(4)
        @DisplayName("User A creates organization A")
        void createOrgA() {
            Response createResponse =
                    given().header("Authorization", "Bearer " + userAToken)
                            .contentType(ContentType.JSON)
                            .body(
                                    """
                            {"name": "%s", "displayName": "Org A"}
                            """
                                            .formatted(ORG_A_NAME))
                            .when()
                            .post(BASE_PATH + "/organizations");

            assertThat(createResponse.statusCode())
                    .as("Create org A should succeed or already exist")
                    .isIn(201, 409);

            // Wait for the org to appear in the platform list
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofSeconds(2))
                    .ignoreExceptions()
                    .untilAsserted(
                            () -> {
                                orgAId = findOrgId(ORG_A_NAME);
                                assertNotNull(orgAId, "Org A should be visible");
                            });
        }

        @Test
        @Order(5)
        @DisplayName("User B creates organization B")
        void createOrgB() {
            Response createResponse =
                    given().header("Authorization", "Bearer " + userBToken)
                            .contentType(ContentType.JSON)
                            .body(
                                    """
                            {"name": "%s", "displayName": "Org B"}
                            """
                                            .formatted(ORG_B_NAME))
                            .when()
                            .post(BASE_PATH + "/organizations");

            assertThat(createResponse.statusCode())
                    .as("Create org B should succeed or already exist")
                    .isIn(201, 409);

            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofSeconds(2))
                    .ignoreExceptions()
                    .untilAsserted(
                            () -> {
                                orgBId = findOrgId(ORG_B_NAME);
                                assertNotNull(orgBId, "Org B should be visible");
                            });
        }

        @Test
        @Order(6)
        @DisplayName("Cveil realm exists in Keycloak")
        void cveilRealmExists() {
            assertNotNull(support, "support must be initialized");
            var realms = support.keycloak().realms().findAll();
            assertThat(realms)
                    .extracting(r -> r.getRealm())
                    .as("Single cveil realm should exist")
                    .contains(PLATFORM_REALM);
        }
    }

    // =======================================================================
    // 2. Cross-Tenant Isolation
    // =======================================================================

    @Nested
    @Order(2)
    @DisplayName("2 — Cross-Tenant Isolation")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class CrossTenantIsolation {

        @Test
        @Order(1)
        @DisplayName("Org A members do NOT include user B")
        void orgADoesNotContainUserB() {
            assertNotNull(orgAId, "orgAId required");

            // User A is a member of org A, so they can list members
            Response response =
                    given().header("Authorization", "Bearer " + userAToken)
                            .when()
                            .get(BASE_PATH + "/organizations/" + orgAId + "/members")
                            .then()
                            .statusCode(200)
                            .extract()
                            .response();

            List<String> usernames = response.jsonPath().getList("username");
            assertThat(usernames).as("Org A members should include user A").contains(USER_A_NAME);
            assertThat(usernames)
                    .as("Org A members should NOT include user B")
                    .doesNotContain(USER_B_NAME);
        }

        @Test
        @Order(2)
        @DisplayName("Org B members do NOT include user A")
        void orgBDoesNotContainUserA() {
            assertNotNull(orgBId, "orgBId required");

            // User B is a member of org B, so they can list members
            Response response =
                    given().header("Authorization", "Bearer " + userBToken)
                            .when()
                            .get(BASE_PATH + "/organizations/" + orgBId + "/members")
                            .then()
                            .statusCode(200)
                            .extract()
                            .response();

            List<String> usernames = response.jsonPath().getList("username");
            assertThat(usernames).as("Org B members should include user B").contains(USER_B_NAME);
            assertThat(usernames)
                    .as("Org B members should NOT include user A")
                    .doesNotContain(USER_A_NAME);
        }

        @Test
        @Order(3)
        @DisplayName("Both tenants share the single cveil Keycloak realm")
        void sharedCveilRealm() {
            var realmNames =
                    support.keycloak().realms().findAll().stream().map(r -> r.getRealm()).toList();
            assertThat(realmNames)
                    .as("No per-tenant realm should exist for org A")
                    .doesNotContain("t_" + ORG_A_NAME);
            assertThat(realmNames)
                    .as("No per-tenant realm should exist for org B")
                    .doesNotContain("t_" + ORG_B_NAME);
        }
    }

    // =======================================================================
    // 3. Cleanup
    // =======================================================================

    @Nested
    @Order(3)
    @DisplayName("3 — Cleanup")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Cleanup {

        @Test
        @Order(1)
        @DisplayName("Close test support")
        void closeSupport() {
            if (support != null) {
                support.close();
            }
        }
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    /**
     * Looks up an organization by name via the platform list endpoint (bootstrap-key auth) and
     * returns its orgId, or null if not found.
     */
    private String findOrgId(String orgName) {
        Response response =
                given().header("Authorization", bootstrapAuth())
                        .when()
                        .get(BASE_PATH + "/organizations")
                        .then()
                        .statusCode(200)
                        .extract()
                        .response();

        List<Map<String, Object>> orgs = response.jsonPath().getList("$");
        for (var entry : orgs) {
            if (orgName.equals(entry.get("orgName"))) {
                return String.valueOf(entry.get("orgId"));
            }
        }
        return null;
    }
}
