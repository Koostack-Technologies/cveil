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
import java.time.Instant;
import org.junit.jupiter.api.*;

@QuarkusTest
@QuarkusTestResource(CveilTestResourceManager.class)
@DisplayName("TenantMetricsResource Integration Test")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantMetricsResourceIT {

    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";
    private static final String TENANT_ID = "metrics_it_tenant";
    private static final String BASE_PATH = "/api/v1/metrics";

    private static volatile String accessToken;
    private static volatile IntegrationTestSupport support;

    @Inject CveilConfig config;

    @AfterAll
    static void cleanup() {
        if (support != null) {
            support.close();
        }
    }

    // ── Phase 1: Setup ───────────────────────────────────────────────────

    @Nested
    @Order(1)
    @DisplayName("Phase 1 – Setup")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SetupAndAuth {

        @Test
        @Order(1)
        @DisplayName("Provision tenant and login")
        void createTenantAndLogin() {
            IntegrationTestSupport.AuthInfo auth =
                    IntegrationTestSupport.provisionTenantAndLogin(
                            BOOTSTRAP_KEY, TENANT_ID, "Metrics IT Tenant");
            accessToken = auth.accessToken();
            assertThat(accessToken).isNotBlank();

            support = new IntegrationTestSupport(config, TENANT_ID);
            support.awaitTenantDbReady(TENANT_ID, Duration.ofSeconds(30));
        }

        @Test
        @Order(2)
        @DisplayName("Unauthenticated request returns 401")
        void unauthenticatedReturns401() {
            given()
                    .contentType(ContentType.JSON)
                    .when()
                    .get(BASE_PATH)
                    .then()
                    .statusCode(401);
        }
    }

    // ── Phase 2: Query Metrics ───────────────────────────────────────────

    @Nested
    @Order(2)
    @DisplayName("Phase 2 – Query Metrics")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class QueryMetrics {

        @Test
        @Order(1)
        @DisplayName("GET /metrics returns 200 with correct structure")
        void metricsReturns200WithCorrectStructure() {
            Instant now = Instant.now();
            String from = now.minus(Duration.ofDays(1)).toString();
            String to = now.toString();

            Response response =
                    given()
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(ContentType.JSON)
                            .queryParam("from", from)
                            .queryParam("to", to)
                            .when()
                            .get(BASE_PATH);

            response.then().statusCode(200);

            // Metrics may already exist from API activity during setup
            assertThat(response.jsonPath().getList("data")).isNotNull();
            assertThat(response.jsonPath().getLong("total")).isGreaterThanOrEqualTo(0);
            assertThat(response.jsonPath().getInt("page")).isEqualTo(1);
            assertThat(response.jsonPath().getInt("pageSize")).isEqualTo(100);
        }
    }

    // ── Phase 3: Summary ────────────────────────────────────────────────

    @Nested
    @Order(3)
    @DisplayName("Phase 3 – Summary")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Summary {

        @Test
        @Order(1)
        @DisplayName("GET /metrics/summary returns 200 with valid structure")
        void summaryReturns200() {
            Instant now = Instant.now();
            String from = now.minus(Duration.ofDays(1)).toString();
            String to = now.toString();

            Response response =
                    given()
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(ContentType.JSON)
                            .queryParam("from", from)
                            .queryParam("to", to)
                            .when()
                            .get(BASE_PATH + "/summary");

            response.then().statusCode(200);

            // Summaries may already contain entries from API activity during setup
            assertThat(response.jsonPath().getList("summaries")).isNotNull();
        }
    }

    // ── Phase 4: Date Validation ─────────────────────────────────────────

    @Nested
    @Order(4)
    @DisplayName("Phase 4 – Date Validation")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DateValidation {

        @Test
        @Order(1)
        @DisplayName("from after to returns 400")
        void fromAfterToReturns400() {
            Instant now = Instant.now();
            String from = now.toString();
            String to = now.minus(Duration.ofDays(1)).toString();

            given()
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .queryParam("from", from)
                    .queryParam("to", to)
                    .when()
                    .get(BASE_PATH)
                    .then()
                    .statusCode(400)
                    .body("message", containsString("'from' must not be after 'to'"));
        }

        @Test
        @Order(2)
        @DisplayName("Date range exceeding 90 days returns 400")
        void rangeExceeding90DaysReturns400() {
            Instant now = Instant.now();
            String from = now.minus(Duration.ofDays(91)).toString();
            String to = now.toString();

            given()
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .queryParam("from", from)
                    .queryParam("to", to)
                    .when()
                    .get(BASE_PATH)
                    .then()
                    .statusCode(400)
                    .body("message", containsString("Date range must not exceed 90 days"));
        }

        @Test
        @Order(3)
        @DisplayName("Invalid date format returns 400")
        void invalidDateFormatReturns400() {
            given()
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .queryParam("from", "not-a-date")
                    .queryParam("to", Instant.now().toString())
                    .when()
                    .get(BASE_PATH)
                    .then()
                    .statusCode(400)
                    .body("message", containsString("Invalid date format. Use ISO-8601"));
        }

        @Test
        @Order(4)
        @DisplayName("Summary endpoint: from after to returns 400")
        void summaryFromAfterToReturns400() {
            Instant now = Instant.now();
            String from = now.toString();
            String to = now.minus(Duration.ofDays(1)).toString();

            given()
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .queryParam("from", from)
                    .queryParam("to", to)
                    .when()
                    .get(BASE_PATH + "/summary")
                    .then()
                    .statusCode(400)
                    .body("message", containsString("'from' must not be after 'to'"));
        }
    }

    // ── Phase 5: Pagination ──────────────────────────────────────────────

    @Nested
    @Order(5)
    @DisplayName("Phase 5 – Pagination")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Pagination {

        @Test
        @Order(1)
        @DisplayName("Custom page and pageSize are reflected in response")
        void paginationParamsReflected() {
            Instant now = Instant.now();
            String from = now.minus(Duration.ofDays(1)).toString();
            String to = now.toString();

            Response response =
                    given()
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(ContentType.JSON)
                            .queryParam("from", from)
                            .queryParam("to", to)
                            .queryParam("page", 1)
                            .queryParam("pageSize", 10)
                            .when()
                            .get(BASE_PATH);

            response.then().statusCode(200);

            assertThat(response.jsonPath().getInt("page")).isEqualTo(1);
            assertThat(response.jsonPath().getInt("pageSize")).isEqualTo(10);
            assertThat(response.jsonPath().getLong("total")).isGreaterThanOrEqualTo(0);
            assertThat(response.jsonPath().getList("data")).isNotNull();
        }
    }
}
