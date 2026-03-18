package com.koostack.cveil.rest.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.koostack.cveil.rest.config.CveilConfig;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;

/**
 * Integration test for {@link com.koostack.cveil.rest.resource.WebhookManagementResource}
 * exercising endpoint CRUD, secret rotation, test-ping delivery, and validation — against real
 * Postgres + Redis + Keycloak.
 */
@QuarkusTest
@QuarkusTestResource(CveilTestResourceManager.class)
@DisplayName("WebhookManagementResource Integration Test")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookManagementResourceIT {

    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";
    private static final String TENANT_ID = "webhook_it_tenant";
    private static final String BASE_PATH = "/api/v1/webhooks/endpoints";
    private static final String WEBHOOK_URL = "https://httpbin.org/post";

    private static volatile String accessToken;
    private static volatile IntegrationTestSupport support;
    private static volatile String endpointId;

    @Inject CveilConfig config;

    // -----------------------------------------------------------------------
    // Phase 1 — Setup
    // -----------------------------------------------------------------------

    @Nested
    @Order(1)
    @DisplayName("Phase 1 — Setup")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Setup {

        @Test
        @Order(1)
        @DisplayName("Create tenant and login")
        void createTenantAndLogin() {
            try {
                IntegrationTestSupport.AuthInfo auth =
                        IntegrationTestSupport.provisionTenantAndLogin(
                                BOOTSTRAP_KEY, TENANT_ID, "Webhook IT Tenant");
                accessToken = auth.accessToken();
                assertThat(accessToken).isNotBlank();
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                    String email = TENANT_ID.replace("_", "-") + "@cveil-test.com";
                    accessToken = IntegrationTestSupport.loginUser(email, "Test1234!");
                } else {
                    throw e;
                }
            }
        }

        @Test
        @Order(2)
        @DisplayName("Init support and await tenant ready")
        void initSupport() {
            support = new IntegrationTestSupport(config, TENANT_ID);
            support.awaitTenantDbReady(TENANT_ID, Duration.ofSeconds(30));
        }
    }

    // -----------------------------------------------------------------------
    // Phase 2 — Validation
    // -----------------------------------------------------------------------

    @Nested
    @Order(2)
    @DisplayName("Phase 2 — Validation")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Validation {

        @Test
        @Order(1)
        @DisplayName("POST with missing url → 400")
        void missingUrl() {
            given().header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .body(
                            """
                        {"events":["consent.subject.granted"],"description":"no url"}
                        """)
                    .when()
                    .post(BASE_PATH)
                    .then()
                    .statusCode(400);
        }

        @Test
        @Order(2)
        @DisplayName("POST with empty events → 400")
        void emptyEvents() {
            given().header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .body(
                            """
                        {"url":"https://example.com/hook","events":[],"description":"empty events"}
                        """)
                    .when()
                    .post(BASE_PATH)
                    .then()
                    .statusCode(400);
        }

        @Test
        @Order(3)
        @DisplayName("POST with invalid event type → 400")
        void invalidEventType() {
            given().header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .body(
                            """
                        {"url":"https://example.com/hook","events":["not.a.valid.event"],"description":"bad event"}
                        """)
                    .when()
                    .post(BASE_PATH)
                    .then()
                    .statusCode(400);
        }

        @Test
        @Order(4)
        @DisplayName("POST with localhost URL → 400 (SSRF protection)")
        void ssrfLocalhost() {
            given().header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .body(
                            """
                        {"url":"http://localhost:8080/hook","events":["test.ping"],"description":"ssrf"}
                        """)
                    .when()
                    .post(BASE_PATH)
                    .then()
                    .statusCode(400);
        }

        @Test
        @Order(5)
        @DisplayName("POST with 127.0.0.1 URL → 400 (SSRF protection)")
        void ssrfLoopback() {
            given().header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .body(
                            """
                        {"url":"https://127.0.0.1/hook","events":["test.ping"],"description":"ssrf"}
                        """)
                    .when()
                    .post(BASE_PATH)
                    .then()
                    .statusCode(400);
        }

        @Test
        @Order(6)
        @DisplayName("POST with 10.x private IP URL → 400 (SSRF protection)")
        void ssrfPrivateNetwork10() {
            given().header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .body(
                            """
                        {"url":"https://10.0.0.1/hook","events":["test.ping"],"description":"ssrf"}
                        """)
                    .when()
                    .post(BASE_PATH)
                    .then()
                    .statusCode(400);
        }

        @Test
        @Order(7)
        @DisplayName("POST with 192.168.x URL → 400 (SSRF protection)")
        void ssrfPrivateNetwork192() {
            given().header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .body(
                            """
                        {"url":"https://192.168.1.1/hook","events":["test.ping"],"description":"ssrf"}
                        """)
                    .when()
                    .post(BASE_PATH)
                    .then()
                    .statusCode(400);
        }

        @Test
        @Order(8)
        @DisplayName("POST with 169.254.x metadata URL → 400 (SSRF protection)")
        void ssrfLinkLocal() {
            given().header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .body(
                            """
                        {"url":"https://169.254.169.254/latest/meta-data","events":["test.ping"],"description":"ssrf"}
                        """)
                    .when()
                    .post(BASE_PATH)
                    .then()
                    .statusCode(400);
        }
    }

    // -----------------------------------------------------------------------
    // Phase 3 — Create Endpoint
    // -----------------------------------------------------------------------

    @Nested
    @Order(3)
    @DisplayName("Phase 3 — Create Endpoint")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class CreateEndpoint {

        @Test
        @Order(1)
        @DisplayName("POST valid webhook endpoint → 201 with secret")
        void createValidEndpoint() {
            Response response =
                    given().header("Authorization", "Bearer " + accessToken)
                            .contentType(ContentType.JSON)
                            .body(
                                    """
                        {"url":"%s","events":["consent.subject.granted","consent.subject.revoked"],"description":"Test endpoint"}
                        """
                                            .formatted(WEBHOOK_URL))
                            .when()
                            .post(BASE_PATH);

            response.then().statusCode(201);

            String id = response.jsonPath().getString("id");
            String url = response.jsonPath().getString("url");
            String secret = response.jsonPath().getString("secret");
            List<String> events = response.jsonPath().getList("events", String.class);
            Boolean enabled = response.jsonPath().getBoolean("enabled");
            String description = response.jsonPath().getString("description");

            assertThat(id).isNotBlank();
            assertThat(url).isEqualTo(WEBHOOK_URL);
            assertThat(secret).startsWith("whsec_");
            assertThat(events)
                    .containsExactlyInAnyOrder(
                            "consent.subject.granted", "consent.subject.revoked");
            assertThat(enabled).isTrue();
            assertThat(description).isEqualTo("Test endpoint");

            // Store for subsequent phases
            endpointId = id;
        }
    }

    // -----------------------------------------------------------------------
    // Phase 4 — List Endpoints
    // -----------------------------------------------------------------------

    @Nested
    @Order(4)
    @DisplayName("Phase 4 — List Endpoints")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ListEndpoints {

        @Test
        @Order(1)
        @DisplayName("GET list contains the created endpoint")
        void listContainsCreatedEndpoint() {
            assertThat(endpointId).as("endpointId must be set from Phase 3").isNotNull();

            Response response =
                    given().header("Authorization", "Bearer " + accessToken)
                            .contentType(ContentType.JSON)
                            .when()
                            .get(BASE_PATH);

            response.then().statusCode(200);

            List<String> ids = response.jsonPath().getList("id", String.class);
            assertThat(ids).contains(endpointId);

            // Find our endpoint in the list and verify fields
            List<Object> endpoints = response.jsonPath().getList("$");
            assertThat(endpoints).isNotEmpty();

            String url =
                    response.jsonPath()
                            .getString("find { it.id == '%s' }.url".formatted(endpointId));
            assertThat(url).isEqualTo(WEBHOOK_URL);

            List<String> events =
                    response.jsonPath()
                            .getList(
                                    "find { it.id == '%s' }.events".formatted(endpointId),
                                    String.class);
            assertThat(events)
                    .containsExactlyInAnyOrder(
                            "consent.subject.granted", "consent.subject.revoked");
        }
    }

    // -----------------------------------------------------------------------
    // Phase 5 — Update Endpoint
    // -----------------------------------------------------------------------

    @Nested
    @Order(5)
    @DisplayName("Phase 5 — Update Endpoint")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class UpdateEndpoint {

        @Test
        @Order(1)
        @DisplayName("PUT update events and description → 200")
        void updateEndpoint() {
            assertThat(endpointId).as("endpointId must be set from Phase 3").isNotNull();

            given().header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .body(
                            """
                        {"url":"%s","events":["*"],"enabled":true,"description":"Updated endpoint"}
                        """
                                    .formatted(WEBHOOK_URL))
                    .when()
                    .put(BASE_PATH + "/" + endpointId)
                    .then()
                    .statusCode(200)
                    .body("updated", is(true));
        }

        @Test
        @Order(2)
        @DisplayName("Verify update reflected in list")
        void verifyUpdateReflected() {
            Response response =
                    given().header("Authorization", "Bearer " + accessToken)
                            .contentType(ContentType.JSON)
                            .when()
                            .get(BASE_PATH);

            response.then().statusCode(200);

            String description =
                    response.jsonPath()
                            .getString("find { it.id == '%s' }.description".formatted(endpointId));
            assertThat(description).isEqualTo("Updated endpoint");

            List<String> events =
                    response.jsonPath()
                            .getList(
                                    "find { it.id == '%s' }.events".formatted(endpointId),
                                    String.class);
            assertThat(events).containsExactly("*");
        }
    }

    // -----------------------------------------------------------------------
    // Phase 6 — Delivery History
    // -----------------------------------------------------------------------

    @Nested
    @Order(6)
    @DisplayName("Phase 6 — Delivery History")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DeliveryHistory {

        @Test
        @Order(1)
        @DisplayName("GET deliveries → 200 (may be empty)")
        void getDeliveryHistory() {
            assertThat(endpointId).as("endpointId must be set from Phase 3").isNotNull();

            Response response =
                    given().header("Authorization", "Bearer " + accessToken)
                            .contentType(ContentType.JSON)
                            .queryParam("limit", 10)
                            .when()
                            .get(BASE_PATH + "/" + endpointId + "/deliveries");

            response.then().statusCode(200);

            List<Object> deliveries = response.jsonPath().getList("$");
            assertThat(deliveries).isNotNull();
        }
    }

    // -----------------------------------------------------------------------
    // Phase 7 — Rotate Secret
    // -----------------------------------------------------------------------

    @Nested
    @Order(7)
    @DisplayName("Phase 7 — Rotate Secret")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class RotateSecret {

        @Test
        @Order(1)
        @DisplayName("POST rotate-secret → 200 with new whsec_ secret")
        void rotateSecret() {
            assertThat(endpointId).as("endpointId must be set from Phase 3").isNotNull();

            Response response =
                    given().header("Authorization", "Bearer " + accessToken)
                            .contentType(ContentType.JSON)
                            .when()
                            .post(BASE_PATH + "/" + endpointId + "/rotate-secret");

            response.then().statusCode(200);

            String newSecret = response.jsonPath().getString("secret");
            String previousExpiry = response.jsonPath().getString("previousSecretExpiresAt");

            assertThat(newSecret).startsWith("whsec_");
            assertThat(previousExpiry).isNotBlank();
        }
    }

    // -----------------------------------------------------------------------
    // Phase 8 — Test Ping
    // -----------------------------------------------------------------------

    @Nested
    @Order(8)
    @DisplayName("Phase 8 — Test Ping")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class TestPing {

        @Test
        @Order(1)
        @DisplayName("POST test ping → 200 (delivered may be false)")
        void testPing() {
            assertThat(endpointId).as("endpointId must be set from Phase 3").isNotNull();

            Response response =
                    given().header("Authorization", "Bearer " + accessToken)
                            .contentType(ContentType.JSON)
                            .when()
                            .post(BASE_PATH + "/" + endpointId + "/test");

            response.then().statusCode(200);

            // The response should contain a "delivered" field — may be false if
            // the webhook URL is unreachable in the test environment.
            assertThat(response.jsonPath().getObject("delivered", Boolean.class)).isNotNull();
        }
    }

    // -----------------------------------------------------------------------
    // Phase 9 — Delete Endpoint
    // -----------------------------------------------------------------------

    @Nested
    @Order(9)
    @DisplayName("Phase 9 — Delete Endpoint")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DeleteEndpoint {

        @Test
        @Order(1)
        @DisplayName("DELETE endpoint → 204")
        void deleteEndpoint() {
            assertThat(endpointId).as("endpointId must be set from Phase 3").isNotNull();

            given().header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .when()
                    .delete(BASE_PATH + "/" + endpointId)
                    .then()
                    .statusCode(204);
        }

        @Test
        @Order(2)
        @DisplayName("DELETE same endpoint again → 404 (idempotent safety)")
        void deleteAgainReturns404() {
            given().header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .when()
                    .delete(BASE_PATH + "/" + endpointId)
                    .then()
                    .statusCode(404);
        }
    }

    // -----------------------------------------------------------------------
    // Phase 10 — Not Found Cases
    // -----------------------------------------------------------------------

    @Nested
    @Order(10)
    @DisplayName("Phase 10 — Not Found Cases")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class NotFoundCases {

        private final String randomId = UUID.randomUUID().toString();

        @Test
        @Order(1)
        @DisplayName("GET deliveries for random UUID → handles gracefully")
        void deliveriesNotFound() {
            Response response =
                    given().header("Authorization", "Bearer " + accessToken)
                            .contentType(ContentType.JSON)
                            .when()
                            .get(BASE_PATH + "/" + randomId + "/deliveries");

            // Should return 200 with empty list or 404 — either is acceptable
            int status = response.statusCode();
            assertTrue(
                    status == 200 || status == 404,
                    "Expected 200 (empty) or 404 but got " + status);
        }

        @Test
        @Order(2)
        @DisplayName("PUT update for random UUID → 404")
        void updateNotFound() {
            given().header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .body(
                            """
                        {"url":"https://example.com/hook","events":["*"],"enabled":false,"description":"ghost"}
                        """)
                    .when()
                    .put(BASE_PATH + "/" + randomId)
                    .then()
                    .statusCode(404);
        }

        @Test
        @Order(3)
        @DisplayName("DELETE random UUID → 404")
        void deleteNotFound() {
            given().header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .when()
                    .delete(BASE_PATH + "/" + randomId)
                    .then()
                    .statusCode(404);
        }

        @Test
        @Order(4)
        @DisplayName("POST rotate-secret for random UUID → 404")
        void rotateSecretNotFound() {
            given().header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .when()
                    .post(BASE_PATH + "/" + randomId + "/rotate-secret")
                    .then()
                    .statusCode(404);
        }
    }

    // -----------------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------------

    @AfterAll
    static void cleanup() {
        if (support != null) {
            support.close();
        }
    }
}
