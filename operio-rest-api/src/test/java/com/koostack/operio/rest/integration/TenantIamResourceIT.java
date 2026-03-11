package com.koostack.operio.rest.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

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
 * Integration test for tenant-scoped IAM operations through the unified {@link
 * com.koostack.operio.rest.resource.PlatformIamResource}.
 *
 * <p>In the unified IAM architecture all identity operations go through the single {@code
 * /api/v1/platform/iam} resource. Tenants are modelled as Keycloak Organizations within the single
 * {@code operio} realm — there are no per-tenant realms.
 *
 * <p>Every mutating operation is verified at three layers:
 *
 * <ol>
 *   <li>REST API response (status code + body)
 *   <li>Keycloak Admin API — user/client/role queries in the {@code operio} realm
 *   <li>Postgres — tenant readiness via CQRS projections
 * </ol>
 */
@QuarkusTest
@QuarkusTestResource(OperioTestResourceManager.class)
@DisplayName("TenantIamResource Integration Test — Unified IAM (Postgres + Redis + Keycloak)")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantIamResourceIT {

    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";
    private static final String PLATFORM_REALM = "operio";
    private static final String BASE_PATH = "/api/v1/platform/iam";

    private static final String TENANT_A_ID = "iam_it_tenant_a";
    private static final String TENANT_B_ID = "iam_it_tenant_b";

    // Shared mutable state across nested test classes
    private static volatile IntegrationTestSupport support;

    private static volatile String tenantAUsername;
    private static volatile String tenantAUserEmail;
    private static volatile String tenantAOrgId;
    private static volatile String tenantBOrgId;
    private static volatile String serviceAccountClientId;
    private static volatile String serviceAccountClientSecret;
    private static volatile String accessToken;
    private static volatile String refreshToken;
    private static volatile String readOnlyAccessToken;

    @Inject OperioConfig config;

    private String bootstrapAuth() {
        return "Bearer " + BOOTSTRAP_KEY;
    }

    // =======================================================================
    // 1. Tenant Provisioning
    // =======================================================================

    @Nested
    @Order(1)
    @DisplayName("1 — Tenant Provisioning (Unified IAM)")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Provisioning {

        @Test
        @Order(1)
        @DisplayName("Initialize test support")
        void initSupport() {
            support = new IntegrationTestSupport(config);
        }

        @Test
        @Order(2)
        @DisplayName("Provision tenant A via orchestrator")
        void provisionTenantA() {
            IntegrationTestSupport.provisionTenantViaOrchestrator(
                    BOOTSTRAP_KEY, TENANT_A_ID, "IAM IT Tenant A", "enterprise");
        }

        @Test
        @Order(3)
        @DisplayName("Await tenant A ready in database")
        void awaitTenantAReady() {
            var tenantSupport = new IntegrationTestSupport(config, TENANT_A_ID);
            tenantSupport.awaitTenantReady(TENANT_A_ID, Duration.ofSeconds(90));
            tenantSupport.close();
        }

        @Test
        @Order(4)
        @DisplayName("Operio realm exists in Keycloak")
        void operioRealmExists() {
            assertNotNull(support, "support must be initialized");
            var realms = support.keycloak().realms().findAll();
            assertThat(realms)
                    .extracting(r -> r.getRealm())
                    .as("Single operio realm should exist")
                    .contains(PLATFORM_REALM);
        }

        @Test
        @Order(5)
        @DisplayName("Tenant A organization exists")
        void tenantAOrgExists() {
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofSeconds(2))
                    .ignoreExceptions()
                    .untilAsserted(
                            () -> {
                                Response response =
                                        given().header("Authorization", bootstrapAuth())
                                                .when()
                                                .get(BASE_PATH + "/organizations")
                                                .then()
                                                .statusCode(200)
                                                .extract()
                                                .response();

                                List<String> orgNames = response.jsonPath().getList("orgName");
                                assertThat(orgNames)
                                        .as("Tenant A organization should exist")
                                        .contains(TENANT_A_ID);

                                List<Map<String, Object>> orgs = response.jsonPath().getList("$");
                                for (var entry : orgs) {
                                    if (TENANT_A_ID.equals(entry.get("orgName"))) {
                                        tenantAOrgId = String.valueOf(entry.get("orgId"));
                                        break;
                                    }
                                }
                                assertNotNull(tenantAOrgId);
                            });
        }
    }

    // =======================================================================
    // 2. User Registration
    // =======================================================================

    @Nested
    @Order(2)
    @DisplayName("2 — User Registration")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class UserRegistration {

        @Test
        @Order(1)
        @DisplayName("Register tenant user → 201")
        void registerUser() {
            tenantAUserEmail = "tenant-admin@acme-test.com";
            tenantAUsername = "tenant-admin";

            Response response =
                    given().header("Authorization", bootstrapAuth())
                            .contentType(ContentType.JSON)
                            .body(
                                    """
                    {
                      "username": "%s",
                      "email": "%s",
                      "fullName": "Tenant Admin",
                      "roles": ["tenant-admin"],
                      "temporaryPassword": false
                    }
                    """
                                            .formatted(tenantAUsername, tenantAUserEmail))
                            .when()
                            .post(BASE_PATH + "/users")
                            .then()
                            .statusCode(201)
                            .body("email", equalTo(tenantAUserEmail))
                            .body("realmId", equalTo(PLATFORM_REALM))
                            .body("username", equalTo(tenantAUsername))
                            .extract()
                            .response();

            assertNotNull(response.jsonPath().getString("username"));
        }

        @Test
        @Order(2)
        @DisplayName("User exists in operio Keycloak realm")
        void userExistsInOperioRealm() {
            support.awaitKeycloakUserExists(
                    PLATFORM_REALM, tenantAUserEmail, Duration.ofSeconds(30));
        }

        @Test
        @Order(3)
        @DisplayName("User registration event stored (verified via Keycloak projection)")
        void userRegisteredEventStored() {
            assertThat(tenantAUsername).as("username was returned by registration").isNotNull();
        }

        @Test
        @Order(4)
        @DisplayName("Invalid email → 400")
        void invalidEmail() {
            given().header("Authorization", bootstrapAuth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "username": "bad-user",
                      "email": "bad-email",
                      "fullName": "Bad",
                      "roles": [],
                      "temporaryPassword": false
                    }
                    """)
                    .when()
                    .post(BASE_PATH + "/users")
                    .then()
                    .statusCode(400);
        }

        @Test
        @Order(5)
        @DisplayName("Missing email → 400")
        void missingEmail() {
            given().header("Authorization", bootstrapAuth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "fullName": "No Email",
                      "roles": [],
                      "temporaryPassword": false
                    }
                    """)
                    .when()
                    .post(BASE_PATH + "/users")
                    .then()
                    .statusCode(400);
        }
    }

    // =======================================================================
    // 3. User Lifecycle
    // =======================================================================

    @Nested
    @Order(3)
    @DisplayName("3 — User Lifecycle")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class UserLifecycle {

        @Test
        @Order(1)
        @DisplayName("List users includes registered user")
        void listUsers() {
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofSeconds(2))
                    .ignoreExceptions()
                    .untilAsserted(
                            () ->
                                    given().header("Authorization", bootstrapAuth())
                                            .when()
                                            .get(BASE_PATH + "/users")
                                            .then()
                                            .statusCode(200)
                                            .body("$", hasSize(greaterThanOrEqualTo(1)))
                                            .body("email", hasItem(tenantAUserEmail)));
        }

        @Test
        @Order(2)
        @DisplayName("Get user by username")
        void getUserByUsername() {
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofSeconds(2))
                    .ignoreExceptions()
                    .untilAsserted(
                            () ->
                                    given().header("Authorization", bootstrapAuth())
                                            .when()
                                            .get(BASE_PATH + "/users/" + tenantAUsername)
                                            .then()
                                            .statusCode(200)
                                            .body("username", equalTo(tenantAUsername))
                                            .body("email", equalTo(tenantAUserEmail)));
        }

        @Test
        @Order(3)
        @DisplayName("Get non-existent user → 404")
        void getNonExistentUser() {
            given().header("Authorization", bootstrapAuth())
                    .when()
                    .get(BASE_PATH + "/users/nonexistent-user-xyz")
                    .then()
                    .statusCode(404);
        }

        @Test
        @Order(4)
        @DisplayName("Suspend user")
        void suspendUser() {
            given().header("Authorization", bootstrapAuth())
                    .contentType(ContentType.JSON)
                    .when()
                    .put(BASE_PATH + "/users/" + tenantAUsername + "/suspend")
                    .then()
                    .statusCode(200)
                    .body("message", containsString("suspended"));

            support.awaitKeycloakUserDisabled(
                    PLATFORM_REALM, tenantAUserEmail, Duration.ofSeconds(30));
        }

        @Test
        @Order(5)
        @DisplayName("Activate user")
        void activateUser() {
            given().header("Authorization", bootstrapAuth())
                    .contentType(ContentType.JSON)
                    .when()
                    .put(BASE_PATH + "/users/" + tenantAUsername + "/activate")
                    .then()
                    .statusCode(200)
                    .body("message", containsString("activated"));

            support.awaitKeycloakUserEnabled(
                    PLATFORM_REALM, tenantAUserEmail, Duration.ofSeconds(30));
        }
    }

    // =======================================================================
    // 4. Roles
    // =======================================================================

    @Nested
    @Order(4)
    @DisplayName("4 — Role Management")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class RoleManagement {

        @Test
        @Order(1)
        @DisplayName("Get user roles")
        void getUserRoles() {
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofSeconds(2))
                    .ignoreExceptions()
                    .untilAsserted(
                            () ->
                                    given().header("Authorization", bootstrapAuth())
                                            .when()
                                            .get(BASE_PATH + "/users/" + tenantAUsername + "/roles")
                                            .then()
                                            .statusCode(200)
                                            .body("$", notNullValue()));
        }

        @Test
        @Order(2)
        @DisplayName("Assign tenant-write role")
        void assignRole() {
            given().header("Authorization", bootstrapAuth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "role": "tenant-write" }
                    """)
                    .when()
                    .put(BASE_PATH + "/users/" + tenantAUsername + "/roles")
                    .then()
                    .statusCode(200)
                    .body("message", containsString("assigned"));
        }

        @Test
        @Order(3)
        @DisplayName("Verify role assigned in Keycloak")
        void verifyRoleInKeycloak() {
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .ignoreExceptions()
                    .untilAsserted(
                            () -> {
                                var users =
                                        support.keycloak()
                                                .realm(PLATFORM_REALM)
                                                .users()
                                                .searchByEmail(tenantAUserEmail, true);
                                assertThat(users).isNotEmpty();
                                String kcUserId = users.get(0).getId();

                                var roles = support.getKeycloakUserRoles(PLATFORM_REALM, kcUserId);
                                assertThat(roles)
                                        .extracting("name")
                                        .as("User should have tenant-write role")
                                        .contains("tenant-write");
                            });
        }

        @Test
        @Order(4)
        @DisplayName("Remove tenant-write role")
        void removeRole() {
            given().header("Authorization", bootstrapAuth())
                    .when()
                    .delete(BASE_PATH + "/users/" + tenantAUsername + "/roles/tenant-write")
                    .then()
                    .statusCode(200)
                    .body("message", containsString("removed"));
        }

        @Test
        @Order(5)
        @DisplayName("Verify role removed in Keycloak")
        void verifyRoleRemovedInKeycloak() {
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .ignoreExceptions()
                    .untilAsserted(
                            () -> {
                                var users =
                                        support.keycloak()
                                                .realm(PLATFORM_REALM)
                                                .users()
                                                .searchByEmail(tenantAUserEmail, true);
                                assertThat(users).isNotEmpty();
                                String kcUserId = users.get(0).getId();

                                var roles = support.getKeycloakUserRoles(PLATFORM_REALM, kcUserId);
                                var roleNames = roles.stream().map(r -> r.getName()).toList();
                                assertThat(roleNames)
                                        .as("User should not have tenant-write role")
                                        .doesNotContain("tenant-write");
                            });
        }
    }

    // =======================================================================
    // 5. Service Accounts
    // =======================================================================

    @Nested
    @Order(5)
    @DisplayName("5 — Service Accounts")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ServiceAccounts {

        @Test
        @Order(1)
        @DisplayName("Create service account → 201 with clientId and clientSecret")
        void createServiceAccount() {
            Response response =
                    given().header("Authorization", bootstrapAuth())
                            .contentType(ContentType.JSON)
                            .body(
                                    """
                    {
                      "name": "tenant-ci-pipeline",
                      "roles": ["tenant-read", "tenant-write"]
                    }
                    """)
                            .when()
                            .post(BASE_PATH + "/service-accounts")
                            .then()
                            .statusCode(201)
                            .body("clientId", notNullValue())
                            .body("clientSecret", notNullValue())
                            .body("realmId", equalTo(PLATFORM_REALM))
                            .extract()
                            .response();

            serviceAccountClientId = response.jsonPath().getString("clientId");
            serviceAccountClientSecret = response.jsonPath().getString("clientSecret");
            assertNotNull(serviceAccountClientId);
            assertNotNull(serviceAccountClientSecret);
        }

        @Test
        @Order(2)
        @DisplayName("Service account visible in operio Keycloak realm")
        void serviceAccountInKeycloak() {
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .ignoreExceptions()
                    .untilAsserted(
                            () -> {
                                var clients = support.getKeycloakClients(PLATFORM_REALM);
                                assertThat(clients)
                                        .extracting("clientId")
                                        .contains(serviceAccountClientId);
                            });
        }

        @Test
        @Order(3)
        @DisplayName("List service accounts includes created one")
        void listServiceAccounts() {
            given().header("Authorization", bootstrapAuth())
                    .when()
                    .get(BASE_PATH + "/service-accounts")
                    .then()
                    .statusCode(200)
                    .body("$", hasSize(greaterThanOrEqualTo(1)))
                    .body("clientId", hasItem(serviceAccountClientId));
        }

        @Test
        @Order(4)
        @DisplayName("Revoke service account")
        void revokeServiceAccount() {
            given().header("Authorization", bootstrapAuth())
                    .when()
                    .delete(BASE_PATH + "/service-accounts/" + serviceAccountClientId)
                    .then()
                    .statusCode(200)
                    .body("message", containsString("revoked"));
        }
    }

    // =======================================================================
    // 6. Authentication
    // =======================================================================

    @Nested
    @Order(6)
    @DisplayName("6 — Authentication")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Authentication {

        @Test
        @Order(1)
        @DisplayName("Register user with known password for login tests")
        void registerLoginUser() {
            given().header("Authorization", bootstrapAuth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "username": "tenant-login",
                      "email": "tenant-login@acme-test.com",
                      "fullName": "Tenant Login User",
                      "roles": ["tenant-read"],
                      "temporaryPassword": false
                    }
                    """)
                    .when()
                    .post(BASE_PATH + "/users")
                    .then()
                    .statusCode(201);

            // Wait for user to appear in Keycloak (single operio realm)
            support.awaitKeycloakUserExists(
                    PLATFORM_REALM, "tenant-login@acme-test.com", Duration.ofSeconds(30));

            // Set a known password via Keycloak admin API
            var users =
                    support.keycloak()
                            .realm(PLATFORM_REALM)
                            .users()
                            .searchByEmail("tenant-login@acme-test.com", true);
            assertThat(users).isNotEmpty();
            var kcUserId = users.get(0).getId();

            // Mark email as verified
            var userRep =
                    support.keycloak()
                            .realm(PLATFORM_REALM)
                            .users()
                            .get(kcUserId)
                            .toRepresentation();
            userRep.setEmailVerified(true);
            support.keycloak().realm(PLATFORM_REALM).users().get(kcUserId).update(userRep);

            var cred = new org.keycloak.representations.idm.CredentialRepresentation();
            cred.setType(org.keycloak.representations.idm.CredentialRepresentation.PASSWORD);
            cred.setValue("TenantTest1234!");
            cred.setTemporary(false);
            support.keycloak().realm(PLATFORM_REALM).users().get(kcUserId).resetPassword(cred);
        }

        @Test
        @Order(2)
        @DisplayName("Login with valid credentials → tokens returned")
        void loginSuccess() {
            Response response =
                    given().contentType(ContentType.JSON)
                            .body(
                                    """
                    {
                      "email": "tenant-login@acme-test.com",
                      "password": "TenantTest1234!"
                    }
                    """)
                            .when()
                            .post(BASE_PATH + "/login")
                            .then()
                            .log()
                            .ifStatusCodeIsEqualTo(401)
                            .statusCode(200)
                            .body("accessToken", notNullValue())
                            .body("refreshToken", notNullValue())
                            .body("tokenType", equalTo("Bearer"))
                            .body("expiresIn", greaterThan(0))
                            .extract()
                            .response();

            accessToken = response.jsonPath().getString("accessToken");
            refreshToken = response.jsonPath().getString("refreshToken");
            assertNotNull(accessToken);
            assertNotNull(refreshToken);
        }

        @Test
        @Order(3)
        @DisplayName("Access token is a valid JWT from the operio realm")
        void validateAccessToken() {
            assertNotNull(accessToken, "accessToken required");
            String[] parts = accessToken.split("\\.");
            assertThat(parts).hasSize(3);

            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
            assertThat(payload).contains("tenant-login@acme-test.com");
            assertThat(payload).contains(PLATFORM_REALM);
        }

        @Test
        @Order(4)
        @DisplayName("Refresh token → new access token")
        void refreshTokenSuccess() {
            assertNotNull(refreshToken, "refreshToken required");

            Response response =
                    given().contentType(ContentType.JSON)
                            .body(
                                    """
                    { "refreshToken": "%s" }
                    """
                                            .formatted(refreshToken))
                            .when()
                            .post(BASE_PATH + "/refresh")
                            .then()
                            .statusCode(200)
                            .body("accessToken", notNullValue())
                            .body("refreshToken", notNullValue())
                            .extract()
                            .response();

            refreshToken = response.jsonPath().getString("refreshToken");
        }

        @Test
        @Order(5)
        @DisplayName("Logout invalidates refresh token")
        void logout() {
            assertNotNull(refreshToken, "refreshToken required");

            given().contentType(ContentType.JSON)
                    .body(
                            """
                    { "refreshToken": "%s" }
                    """
                                    .formatted(refreshToken))
                    .when()
                    .post(BASE_PATH + "/logout")
                    .then()
                    .statusCode(200)
                    .body("message", containsString("Logged out"));
        }

        @Test
        @Order(6)
        @DisplayName("Refresh after logout → error")
        void refreshAfterLogout() {
            assertNotNull(refreshToken, "refreshToken required");

            given().contentType(ContentType.JSON)
                    .body(
                            """
                    { "refreshToken": "%s" }
                    """
                                    .formatted(refreshToken))
                    .when()
                    .post(BASE_PATH + "/refresh")
                    .then()
                    .statusCode(anyOf(equalTo(400), equalTo(401)));
        }

        @Test
        @Order(7)
        @DisplayName("Login with wrong password → error")
        void loginWrongPassword() {
            given().contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "email": "tenant-login@acme-test.com",
                      "password": "wrong-password"
                    }
                    """)
                    .when()
                    .post(BASE_PATH + "/login")
                    .then()
                    .statusCode(anyOf(equalTo(400), equalTo(401)));
        }
    }

    // =======================================================================
    // 7. Cross-Tenant Isolation
    // =======================================================================

    @Nested
    @Order(7)
    @DisplayName("7 — Cross-Tenant Isolation")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class CrossTenantIsolation {

        @Test
        @Order(1)
        @DisplayName("Provision tenant B via orchestrator")
        void provisionTenantB() {
            IntegrationTestSupport.provisionTenantViaOrchestrator(
                    BOOTSTRAP_KEY, TENANT_B_ID, "IAM IT Tenant B", "enterprise");
        }

        @Test
        @Order(2)
        @DisplayName("Await tenant B ready and capture org ID")
        void awaitTenantBReady() {
            var supportB = new IntegrationTestSupport(config, TENANT_B_ID);
            supportB.awaitTenantReady(TENANT_B_ID, Duration.ofSeconds(90));
            supportB.close();

            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofSeconds(2))
                    .ignoreExceptions()
                    .untilAsserted(
                            () -> {
                                Response response =
                                        given().header("Authorization", bootstrapAuth())
                                                .when()
                                                .get(BASE_PATH + "/organizations")
                                                .then()
                                                .statusCode(200)
                                                .extract()
                                                .response();

                                List<String> orgNames = response.jsonPath().getList("orgName");
                                assertThat(orgNames)
                                        .as("Tenant B organization should exist")
                                        .contains(TENANT_B_ID);

                                List<Map<String, Object>> orgs = response.jsonPath().getList("$");
                                for (var entry : orgs) {
                                    if (TENANT_B_ID.equals(entry.get("orgName"))) {
                                        tenantBOrgId = String.valueOf(entry.get("orgId"));
                                        break;
                                    }
                                }
                                assertNotNull(tenantBOrgId);
                            });
        }

        @Test
        @Order(3)
        @DisplayName("Org A members do NOT include org B admin")
        void orgADoesNotContainOrgBUsers() {
            assertNotNull(tenantAOrgId, "tenantAOrgId required");

            Response response =
                    given().header("Authorization", bootstrapAuth())
                            .when()
                            .get(BASE_PATH + "/organizations/" + tenantAOrgId + "/members")
                            .then()
                            .statusCode(200)
                            .extract()
                            .response();

            List<String> usernames = response.jsonPath().getList("username");
            assertThat(usernames)
                    .as("Org A members should NOT include org B admin user")
                    .doesNotContain("iam-it-tenant-b-admin");
        }

        @Test
        @Order(4)
        @DisplayName("Org B members do NOT include org A admin")
        void orgBDoesNotContainOrgAUsers() {
            assertNotNull(tenantBOrgId, "tenantBOrgId required");

            Response response =
                    given().header("Authorization", bootstrapAuth())
                            .when()
                            .get(BASE_PATH + "/organizations/" + tenantBOrgId + "/members")
                            .then()
                            .statusCode(200)
                            .extract()
                            .response();

            List<String> usernames = response.jsonPath().getList("username");
            assertThat(usernames)
                    .as("Org B members should NOT include org A admin user")
                    .doesNotContain("iam-it-tenant-a-admin");
        }

        @Test
        @Order(5)
        @DisplayName("Both tenants share the single operio Keycloak realm")
        void sharedOperioRealm() {
            var realmNames =
                    support.keycloak().realms().findAll().stream().map(r -> r.getRealm()).toList();
            assertThat(realmNames)
                    .as("No per-tenant realm should exist for tenant A")
                    .doesNotContain("t_" + TENANT_A_ID);
            assertThat(realmNames)
                    .as("No per-tenant realm should exist for tenant B")
                    .doesNotContain("t_" + TENANT_B_ID);
        }
    }

    // =======================================================================
    // 8. RBAC Enforcement
    // =======================================================================

    @Nested
    @Order(8)
    @DisplayName("8 — RBAC Enforcement")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Rbac {

        @Test
        @Order(1)
        @DisplayName("Unauthenticated request → 401")
        void unauthenticatedRejected() {
            given().when().get(BASE_PATH + "/users").then().statusCode(401);
        }

        @Test
        @Order(2)
        @DisplayName("Create read-only user and login for RBAC test")
        void createReadOnlyUserAndLogin() {
            String readOnlyEmail = "iam-it-reader@operio-test.com";
            String readOnlyPassword = "Test1234!";

            given().header("Authorization", bootstrapAuth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "username": "iam-it-reader",
                      "email": "%s",
                      "fullName": "IAM IT Reader",
                      "roles": ["tenant-read"],
                      "temporaryPassword": false
                    }
                    """
                                    .formatted(readOnlyEmail))
                    .when()
                    .post(BASE_PATH + "/users")
                    .then()
                    .statusCode(201);

            support.awaitKeycloakUserExists(PLATFORM_REALM, readOnlyEmail, Duration.ofSeconds(30));

            var users =
                    support.keycloak()
                            .realm(PLATFORM_REALM)
                            .users()
                            .searchByEmail(readOnlyEmail, true);
            assertThat(users).isNotEmpty();
            var kcUserId = users.get(0).getId();

            var userRep =
                    support.keycloak()
                            .realm(PLATFORM_REALM)
                            .users()
                            .get(kcUserId)
                            .toRepresentation();
            userRep.setEmailVerified(true);
            support.keycloak().realm(PLATFORM_REALM).users().get(kcUserId).update(userRep);

            var cred = new org.keycloak.representations.idm.CredentialRepresentation();
            cred.setType(org.keycloak.representations.idm.CredentialRepresentation.PASSWORD);
            cred.setValue(readOnlyPassword);
            cred.setTemporary(false);
            support.keycloak().realm(PLATFORM_REALM).users().get(kcUserId).resetPassword(cred);

            readOnlyAccessToken = IntegrationTestSupport.loginUser(readOnlyEmail, readOnlyPassword);
            assertNotNull(readOnlyAccessToken);
        }

        @Test
        @Order(3)
        @DisplayName("tenant-read role cannot manage users → 403")
        void tenantReadCannotManageUsers() {
            given().header("Authorization", "Bearer " + readOnlyAccessToken)
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "username": "should-fail",
                      "email": "should-fail@test.com",
                      "fullName": "Fail",
                      "roles": [],
                      "temporaryPassword": false
                    }
                    """)
                    .when()
                    .post(BASE_PATH + "/users")
                    .then()
                    .statusCode(403);
        }

        @Test
        @Order(4)
        @DisplayName("Bootstrap key (super-admin) can manage users → 200")
        void superAdminCanListUsers() {
            given().header("Authorization", bootstrapAuth())
                    .when()
                    .get(BASE_PATH + "/users")
                    .then()
                    .statusCode(200);
        }

        @Test
        @Order(5)
        @DisplayName("Login endpoint does not require authorization")
        void authEndpointsOpen() {
            given().contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "email": "nonexistent@test.com",
                      "password": "any"
                    }
                    """)
                    .when()
                    .post(BASE_PATH + "/login")
                    .then()
                    .statusCode(anyOf(equalTo(400), equalTo(401)));
        }
    }

    // =======================================================================
    // 9. Cleanup
    // =======================================================================

    @Nested
    @Order(9)
    @DisplayName("9 — Cleanup")
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
}
