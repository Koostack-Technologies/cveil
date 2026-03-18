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
import java.util.UUID;
import org.junit.jupiter.api.*;

/**
 * Integration test for {@link com.koostack.cveil.rest.resource.DsarResource} exercising DSAR
 * request lifecycle — creation, listing/filtering, status transitions, and activity logging —
 * against real Postgres + Redis.
 *
 * <p>Every mutating operation is verified via REST API response (status code + body).
 */
@QuarkusTest
@QuarkusTestResource(CveilTestResourceManager.class)
@DisplayName("DsarResource Integration Test (Postgres + Redis)")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DsarResourceIT {

    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";
    private static final String TENANT_ID = "dsar_it_tenant";

    private static volatile String accessToken;
    private static volatile IntegrationTestSupport support;

    /** UUID of the first DSAR request, extracted after creation. */
    private static volatile String dsarRequestId;

    /** UUID of a second DSAR request (DELETION type) for filtering tests. */
    private static volatile String dsarDeletionRequestId;

    @Inject CveilConfig config;

    // =======================================================================
    // 1. Setup & Auth
    // =======================================================================

    @Nested
    @Order(1)
    @DisplayName("1 — Setup & Auth Enforcement")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SetupAndAuth {

        @Test
        @Order(1)
        @DisplayName("Create tenant and login")
        void createTenantAndLogin() {
            IntegrationTestSupport.AuthInfo auth =
                    IntegrationTestSupport.provisionTenantAndLogin(
                            BOOTSTRAP_KEY, TENANT_ID, "DSAR IT Tenant");
            accessToken = auth.accessToken();
            assertThat(accessToken).isNotBlank();

            support = new IntegrationTestSupport(config, TENANT_ID);
            // Only wait for DB readiness — these endpoints use direct JDBC,
            // not Redis stream consumers, so skip the 30s consumer-group wait.
            support.awaitTenantDbReady(TENANT_ID, Duration.ofSeconds(30));
        }

        @Test
        @Order(2)
        @DisplayName("Unauthenticated request → 401")
        void unauthenticatedReturns401() {
            given().when().get("/api/v1/dsar/requests").then().statusCode(401);
        }

        @Test
        @Order(3)
        @DisplayName("Invalid token → 401")
        void invalidTokenReturns401() {
            given().header("Authorization", "Bearer invalid-token")
                    .when()
                    .get("/api/v1/dsar/requests")
                    .then()
                    .statusCode(401);
        }
    }

    // =======================================================================
    // 2. Create DSAR Requests
    // =======================================================================

    @Nested
    @Order(2)
    @DisplayName("2 — Create DSAR Requests")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class CreateDsarRequests {

        private String auth() {
            assertNotNull(accessToken, "accessToken required");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("Create ACCESS request → 201")
        void createAccessRequest() {
            Response response =
                    given().header("Authorization", auth())
                            .contentType(ContentType.JSON)
                            .body(
                                    """
                            {
                              "requesterEmail": "user@test.com",
                              "requestType": "ACCESS",
                              "subjectId": "sub1",
                              "notes": "Integration test ACCESS request"
                            }
                            """)
                            .when()
                            .post("/api/v1/dsar/requests");

            response.then().statusCode(201).body("message", containsString("DSAR request created:"));

            // Extract UUID from message "DSAR request created: {uuid}"
            String message = response.jsonPath().getString("message");
            dsarRequestId = message.replace("DSAR request created: ", "").trim();
            assertDoesNotThrow(
                    () -> UUID.fromString(dsarRequestId), "Response should contain a valid UUID");
        }

        @Test
        @Order(2)
        @DisplayName("Create DELETION request → 201")
        void createDeletionRequest() {
            Response response =
                    given().header("Authorization", auth())
                            .contentType(ContentType.JSON)
                            .body(
                                    """
                            {
                              "requesterEmail": "delete@test.com",
                              "requestType": "DELETION",
                              "subjectId": "sub2",
                              "notes": "Integration test DELETION request"
                            }
                            """)
                            .when()
                            .post("/api/v1/dsar/requests");

            response.then().statusCode(201).body("message", containsString("DSAR request created:"));

            String message = response.jsonPath().getString("message");
            dsarDeletionRequestId = message.replace("DSAR request created: ", "").trim();
            assertDoesNotThrow(
                    () -> UUID.fromString(dsarDeletionRequestId),
                    "Response should contain a valid UUID");
        }

        @Test
        @Order(3)
        @DisplayName("Invalid request type → 400")
        void invalidRequestType() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "requesterEmail": "bad@test.com",
                      "requestType": "INVALID_TYPE",
                      "subjectId": "sub1"
                    }
                    """)
                    .when()
                    .post("/api/v1/dsar/requests")
                    .then()
                    .statusCode(400);
        }

        @Test
        @Order(4)
        @DisplayName("Duplicate request within 24h → 409")
        void duplicateWithin24h() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "requesterEmail": "user@test.com",
                      "requestType": "ACCESS",
                      "subjectId": "sub1",
                      "notes": "Duplicate attempt"
                    }
                    """)
                    .when()
                    .post("/api/v1/dsar/requests")
                    .then()
                    .statusCode(409)
                    .body("error", equalTo("DUPLICATE_REQUEST"));
        }
    }

    // =======================================================================
    // 3. List & Filter Requests
    // =======================================================================

    @Nested
    @Order(3)
    @DisplayName("3 — List & Filter DSAR Requests")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ListAndFilterRequests {

        private String auth() {
            assertNotNull(accessToken, "accessToken required");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("List all requests returns at least 2")
        void listAll() {
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/dsar/requests")
                    .then()
                    .statusCode(200)
                    .body("total", greaterThanOrEqualTo(2))
                    .body("requests", hasSize(greaterThanOrEqualTo(2)));
        }

        @Test
        @Order(2)
        @DisplayName("Filter by status=PENDING")
        void filterByStatus() {
            given().header("Authorization", auth())
                    .queryParam("status", "PENDING")
                    .when()
                    .get("/api/v1/dsar/requests")
                    .then()
                    .statusCode(200)
                    .body("total", greaterThanOrEqualTo(2))
                    .body("requests.status", everyItem(equalTo("PENDING")));
        }

        @Test
        @Order(3)
        @DisplayName("Filter by type=ACCESS")
        void filterByType() {
            given().header("Authorization", auth())
                    .queryParam("type", "ACCESS")
                    .when()
                    .get("/api/v1/dsar/requests")
                    .then()
                    .statusCode(200)
                    .body("total", greaterThanOrEqualTo(1))
                    .body("requests.requestType", everyItem(equalTo("ACCESS")));
        }

        @Test
        @Order(4)
        @DisplayName("Filter by type=DELETION")
        void filterByTypeDeletion() {
            given().header("Authorization", auth())
                    .queryParam("type", "DELETION")
                    .when()
                    .get("/api/v1/dsar/requests")
                    .then()
                    .statusCode(200)
                    .body("total", greaterThanOrEqualTo(1))
                    .body("requests.requestType", everyItem(equalTo("DELETION")));
        }

        @Test
        @Order(5)
        @DisplayName("Get request by ID")
        void getById() {
            assertNotNull(dsarRequestId, "dsarRequestId required from phase 2");

            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/dsar/requests/" + dsarRequestId)
                    .then()
                    .statusCode(200)
                    .body("id", equalTo(dsarRequestId))
                    .body("requesterEmail", equalTo("user@test.com"))
                    .body("requestType", equalTo("ACCESS"))
                    .body("status", equalTo("PENDING"))
                    .body("subjectId", equalTo("sub1"));
        }

        @Test
        @Order(6)
        @DisplayName("Get non-existent request → 404")
        void getNotFound() {
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/dsar/requests/" + UUID.randomUUID())
                    .then()
                    .statusCode(404)
                    .body("error", equalTo("NOT_FOUND"));
        }
    }

    // =======================================================================
    // 4. Status Transitions
    // =======================================================================

    @Nested
    @Order(4)
    @DisplayName("4 — Status Transitions")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class StatusTransitions {

        private String auth() {
            assertNotNull(accessToken, "accessToken required");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("Invalid transition PENDING → COMPLETED → 409")
        void invalidTransition() {
            assertNotNull(dsarDeletionRequestId, "dsarDeletionRequestId required from phase 2");

            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "status": "COMPLETED", "adminNotes": "skip ahead" }
                    """)
                    .when()
                    .put("/api/v1/dsar/requests/" + dsarDeletionRequestId + "/status")
                    .then()
                    .statusCode(409);
        }

        @Test
        @Order(2)
        @DisplayName("Transition PENDING → IN_REVIEW")
        void pendingToInReview() {
            assertNotNull(dsarRequestId, "dsarRequestId required from phase 2");

            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "status": "IN_REVIEW", "adminNotes": "Reviewing request" }
                    """)
                    .when()
                    .put("/api/v1/dsar/requests/" + dsarRequestId + "/status")
                    .then()
                    .statusCode(200)
                    .body("message", containsString("IN_REVIEW"));

            // Verify status persisted
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/dsar/requests/" + dsarRequestId)
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("IN_REVIEW"));
        }

        @Test
        @Order(3)
        @DisplayName("Transition IN_REVIEW → APPROVED")
        void inReviewToApproved() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "status": "APPROVED", "adminNotes": "Approved by admin" }
                    """)
                    .when()
                    .put("/api/v1/dsar/requests/" + dsarRequestId + "/status")
                    .then()
                    .statusCode(200)
                    .body("message", containsString("APPROVED"));
        }

        @Test
        @Order(4)
        @DisplayName("Transition APPROVED → IN_PROGRESS")
        void approvedToInProgress() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "status": "IN_PROGRESS", "adminNotes": "Processing data export" }
                    """)
                    .when()
                    .put("/api/v1/dsar/requests/" + dsarRequestId + "/status")
                    .then()
                    .statusCode(200)
                    .body("message", containsString("IN_PROGRESS"));
        }

        @Test
        @Order(5)
        @DisplayName("Transition IN_PROGRESS → COMPLETED")
        void inProgressToCompleted() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "status": "COMPLETED", "adminNotes": "Data exported and sent" }
                    """)
                    .when()
                    .put("/api/v1/dsar/requests/" + dsarRequestId + "/status")
                    .then()
                    .statusCode(200)
                    .body("message", containsString("COMPLETED"));

            // Verify terminal status persisted and fulfilledAt is set
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/dsar/requests/" + dsarRequestId)
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("COMPLETED"))
                    .body("fulfilledAt", notNullValue());
        }

        @Test
        @Order(6)
        @DisplayName("Transition from terminal state COMPLETED → 409")
        void completedIsTerminal() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "status": "IN_REVIEW", "adminNotes": "Reopen attempt" }
                    """)
                    .when()
                    .put("/api/v1/dsar/requests/" + dsarRequestId + "/status")
                    .then()
                    .statusCode(409);
        }

        @Test
        @Order(7)
        @DisplayName("Update status for non-existent request → 404")
        void updateStatusNotFound() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "status": "IN_REVIEW", "adminNotes": "Ghost request" }
                    """)
                    .when()
                    .put("/api/v1/dsar/requests/" + UUID.randomUUID() + "/status")
                    .then()
                    .statusCode(404);
        }
    }

    // =======================================================================
    // 5. Activity Log Verification
    // =======================================================================

    @Nested
    @Order(5)
    @DisplayName("5 — Activity Log Verification")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ActivityLogVerification {

        private String auth() {
            assertNotNull(accessToken, "accessToken required");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("Activity log contains CREATED entry")
        void activityLogHasCreated() {
            assertNotNull(dsarRequestId, "dsarRequestId required from phase 2");

            Response response =
                    given().header("Authorization", auth())
                            .when()
                            .get("/api/v1/dsar/requests/" + dsarRequestId + "/activity");

            response.then().statusCode(200);

            // Activity log should contain at least one CREATED entry
            var actions = response.jsonPath().getList("action", String.class);
            assertThat(actions).contains("CREATED");
        }

        @Test
        @Order(2)
        @DisplayName("Activity log contains STATUS_CHANGED entries for full transition chain")
        void activityLogHasStatusChanges() {
            Response response =
                    given().header("Authorization", auth())
                            .when()
                            .get("/api/v1/dsar/requests/" + dsarRequestId + "/activity");

            response.then().statusCode(200);

            // The request went through: CREATED → IN_REVIEW → APPROVED → IN_PROGRESS → COMPLETED
            var actions = response.jsonPath().getList("action", String.class);
            assertThat(actions).contains("CREATED", "STATUS_CHANGED");

            // Should have exactly 4 STATUS_CHANGED entries (one per transition)
            long statusChanges =
                    actions.stream().filter("STATUS_CHANGED"::equals).count();
            assertThat(statusChanges)
                    .as("Expected 4 STATUS_CHANGED entries for 4 transitions")
                    .isEqualTo(4);
        }

        @Test
        @Order(3)
        @DisplayName("Activity log entries have required fields")
        void activityLogEntryStructure() {
            Response response =
                    given().header("Authorization", auth())
                            .when()
                            .get("/api/v1/dsar/requests/" + dsarRequestId + "/activity");

            response.then()
                    .statusCode(200)
                    .body("[0].id", notNullValue())
                    .body("[0].requestId", equalTo(dsarRequestId))
                    .body("[0].action", notNullValue())
                    .body("[0].createdAt", notNullValue());
        }
    }
}
