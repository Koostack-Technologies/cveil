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
import java.util.Map;
import org.junit.jupiter.api.*;

/**
 * Integration test for {@link com.koostack.cveil.rest.resource.PublicDsarResource} exercising the
 * unauthenticated DSAR self-service submission endpoint against real Postgres + Redis.
 *
 * <p>The public endpoint always returns 202 Accepted (except for invalid request types) to prevent
 * tenant enumeration. Tenant resolution currently uses {@code cveilbilling.tenant} which does not
 * exist in the current schema — submissions return 202 but tenant resolution silently fails (a
 * known issue). These tests verify the HTTP-level contract and error handling.
 */
@QuarkusTest
@QuarkusTestResource(CveilTestResourceManager.class)
@DisplayName("PublicDsarResource Integration Test")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PublicDsarResourceIT {

    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";
    private static final String TENANT_ID = "pub_dsar_it_tenant";

    private static volatile IntegrationTestSupport support;

    @Inject CveilConfig config;

    // =======================================================================
    // 1. Setup
    // =======================================================================

    @Nested
    @Order(1)
    @DisplayName("1 — Setup")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Setup {

        @Test
        @Order(1)
        @DisplayName("Provision tenant and wait for readiness")
        void provisionTenant() {
            IntegrationTestSupport.AuthInfo auth =
                    IntegrationTestSupport.provisionTenantAndLogin(
                            BOOTSTRAP_KEY, TENANT_ID, "Public DSAR IT Tenant");
            assertThat(auth.accessToken()).isNotBlank();

            support = new IntegrationTestSupport(config, TENANT_ID);
            support.awaitTenantDbReady(TENANT_ID, Duration.ofSeconds(30));
        }
    }

    // =======================================================================
    // 2. Valid Submission
    // =======================================================================

    @Nested
    @Order(2)
    @DisplayName("2 — Valid Submission")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ValidSubmission {

        @Test
        @Order(1)
        @DisplayName("Submit valid DSAR request → 202 with confirmation message")
        void submitValidRequest() {
            assertNotNull(support, "support required from setup phase");

            String email = "valid-submit@pub-dsar-test.com";

            Response response =
                    given().contentType(ContentType.JSON)
                            .body(
                                    """
                            {
                              "tenantSlug": "%s",
                              "requesterEmail": "%s",
                              "requestType": "ACCESS",
                              "requesterName": "Test User",
                              "subjectId": "subject_001",
                              "notes": "Integration test valid submission"
                            }
                            """
                                            .formatted(TENANT_ID, email))
                            .when()
                            .post("/api/v1/public/dsar/submit");

            response.then()
                    .statusCode(202)
                    .body("message", containsString("Your request has been received"));
        }

        @Test
        @Order(2)
        @DisplayName("Submit with all DSAR request types → 202")
        void submitAllRequestTypes() {
            for (String type : List.of("ACCESS", "DELETION", "RECTIFICATION", "PORTABILITY")) {
                given().contentType(ContentType.JSON)
                        .body(
                                """
                        {
                          "tenantSlug": "%s",
                          "requesterEmail": "type-test-%s@pub-dsar-test.com",
                          "requestType": "%s",
                          "requesterName": "Type Test User",
                          "subjectId": "subject_types"
                        }
                        """
                                        .formatted(TENANT_ID, type.toLowerCase(), type))
                        .when()
                        .post("/api/v1/public/dsar/submit")
                        .then()
                        .statusCode(202)
                        .body("message", containsString("Your request has been received"));
            }
        }
    }

    // =======================================================================
    // 3. Invalid Request Type
    // =======================================================================

    @Nested
    @Order(3)
    @DisplayName("3 — Invalid Request Type")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class InvalidRequestType {

        @Test
        @Order(1)
        @DisplayName("Submit with invalid request type → 400")
        void invalidRequestTypeReturns400() {
            given().contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "tenantSlug": "%s",
                      "requesterEmail": "invalid-type@pub-dsar-test.com",
                      "requestType": "INVALID_TYPE",
                      "requesterName": "Bad Type User",
                      "subjectId": "subject_002"
                    }
                    """
                                    .formatted(TENANT_ID))
                    .when()
                    .post("/api/v1/public/dsar/submit")
                    .then()
                    .statusCode(400);
        }

        @Test
        @Order(2)
        @DisplayName("Submit with empty request type → 400")
        void emptyRequestTypeReturns400() {
            given().contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "tenantSlug": "%s",
                      "requesterEmail": "empty-type@pub-dsar-test.com",
                      "requestType": "",
                      "requesterName": "Empty Type User",
                      "subjectId": "subject_empty"
                    }
                    """
                                    .formatted(TENANT_ID))
                    .when()
                    .post("/api/v1/public/dsar/submit")
                    .then()
                    .statusCode(400);
        }
    }

    // =======================================================================
    // 4. Unknown Tenant
    // =======================================================================

    @Nested
    @Order(4)
    @DisplayName("4 — Unknown Tenant")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class UnknownTenant {

        @Test
        @Order(1)
        @DisplayName("Submit with unknown tenant slug → 202 (silent rejection)")
        void unknownTenantReturns202() {
            String email = "unknown-tenant@pub-dsar-test.com";

            given().contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "tenantSlug": "nonexistent_tenant_xyz",
                      "requesterEmail": "%s",
                      "requestType": "ACCESS",
                      "requesterName": "Unknown Tenant User",
                      "subjectId": "subject_003"
                    }
                    """
                                    .formatted(email))
                    .when()
                    .post("/api/v1/public/dsar/submit")
                    .then()
                    .statusCode(202)
                    .body("message", containsString("Your request has been received"));
        }

        @Test
        @Order(2)
        @DisplayName("Verify no DB record created for unknown tenant")
        void verifyNoRecordForUnknownTenant() {
            assertNotNull(support, "support required from setup phase");

            String email = "unknown-tenant@pub-dsar-test.com";

            List<Map<String, Object>> rows =
                    support.query(
                            "SELECT * FROM cveilconsent.dsar_request"
                                    + " WHERE requester_email = ?",
                            email);

            assertThat(rows).isEmpty();
        }
    }

    // =======================================================================
    // 5. Duplicate Submission
    // =======================================================================

    @Nested
    @Order(5)
    @DisplayName("5 — Duplicate Submission")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DuplicateSubmission {

        @Test
        @Order(1)
        @DisplayName("Resubmit same (tenant, email, type) → 202 (same response)")
        void duplicateSubmissionReturns202() {
            String email = "valid-submit@pub-dsar-test.com";

            given().contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "tenantSlug": "%s",
                      "requesterEmail": "%s",
                      "requestType": "ACCESS",
                      "requesterName": "Test User Duplicate",
                      "subjectId": "subject_001",
                      "notes": "Duplicate submission attempt"
                    }
                    """
                                    .formatted(TENANT_ID, email))
                    .when()
                    .post("/api/v1/public/dsar/submit")
                    .then()
                    .statusCode(202)
                    .body("message", containsString("Your request has been received"));
        }
    }
}
