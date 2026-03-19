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
import org.junit.jupiter.api.*;

/**
 * Integration test for {@link com.koostack.cveil.rest.resource.BillingResource} exercising billing
 * endpoints at {@code /api/v1/billing} against real Postgres + Keycloak.
 *
 * <p>Since Stripe is <b>not</b> available in the test environment, this test validates:
 *
 * <ol>
 *   <li>All endpoints are reachable and require authentication
 *   <li>Query-only endpoints (plans, subscription, invoices, payment-methods) return correct data
 *       from local DB
 *   <li>Stripe-dependent endpoints return structured 503 errors (not 500)
 *   <li>Error response bodies follow the standard {@code ErrorResponse} shape
 * </ol>
 */
@QuarkusTest
@QuarkusTestResource(CveilTestResourceManager.class)
@DisplayName("BillingResource Integration Test")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BillingResourceIT {
    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";
    private static final String TENANT_ID = "billing_it_tenant";

    private static volatile String accessToken;
    private static volatile IntegrationTestSupport support;

    @Inject CveilConfig config;

    private String auth() {
        assertThat(accessToken).as("accessToken must be set before calling auth()").isNotBlank();
        return "Bearer " + accessToken;
    }

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
                            BOOTSTRAP_KEY, TENANT_ID, "Billing IT Tenant");
            accessToken = auth.accessToken();
            assertThat(accessToken).isNotBlank();

            support = new IntegrationTestSupport(config, TENANT_ID);
            support.awaitTenantDbReady(TENANT_ID, Duration.ofSeconds(30));
        }

        @Test
        @Order(2)
        @DisplayName("Unauthenticated request → 401")
        void unauthenticatedRejected() {
            given().when().get("/api/v1/billing/plans").then().statusCode(401);
        }

        @Test
        @Order(3)
        @DisplayName("Invalid token → 401")
        void invalidTokenReturns401() {
            given().header("Authorization", "Bearer invalid-token")
                    .when()
                    .get("/api/v1/billing/plans")
                    .then()
                    .statusCode(401);
        }
    }

    // =======================================================================
    // 2. Stripe Config (Service Unavailable)
    // =======================================================================

    @Nested
    @Order(2)
    @DisplayName("2 — Stripe Config (No Stripe)")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class StripeConfig {

        @Test
        @Order(1)
        @DisplayName("GET /stripe-config → 503 when Stripe not configured")
        void stripeConfigReturns503() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .when()
                    .get("/api/v1/billing/stripe-config")
                    .then()
                    .statusCode(503)
                    .body("error", equalTo("service_unavailable"))
                    .body("message", containsString("Payment processor not configured"));
        }

        @Test
        @Order(2)
        @DisplayName("GET /stripe-config without auth → 401")
        void stripeConfigRequiresAuth() {
            given().when().get("/api/v1/billing/stripe-config").then().statusCode(401);
        }
    }

    // =======================================================================
    // 3. Setup Intent (Service Unavailable)
    // =======================================================================

    @Nested
    @Order(3)
    @DisplayName("3 — Setup Intent (No Stripe)")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SetupIntent {

        @Test
        @Order(1)
        @DisplayName("POST /setup-intent → 503 when Stripe not configured")
        void setupIntentReturns503() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .when()
                    .post("/api/v1/billing/setup-intent")
                    .then()
                    .statusCode(503)
                    .body("error", equalTo("service_unavailable"))
                    .body("message", containsString("Payment processor not configured"));
        }
    }

    // =======================================================================
    // 4. Plans (Local DB — works without Stripe)
    // =======================================================================

    @Nested
    @Order(4)
    @DisplayName("4 — Plans Query")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Plans {

        @Test
        @Order(1)
        @DisplayName("GET /plans → 200 with seeded plans")
        void listPlansReturnsData() {
            Response response =
                    given().header("Authorization", auth())
                            .contentType(ContentType.JSON)
                            .when()
                            .get("/api/v1/billing/plans");

            assertThat(response.statusCode()).isEqualTo(200);

            // Plans are seeded by migrations — at minimum "free" should exist
            String body = response.body().asString();
            assertThat(body).isNotBlank();
            assertThat(response.jsonPath().getList("$")).isNotEmpty();
            assertThat(response.jsonPath().getList("tier", String.class)).contains("free");
        }

        @Test
        @Order(2)
        @DisplayName("GET /plans response contains expected plan fields")
        void plansContainExpectedFields() {
            Response response =
                    given().header("Authorization", auth())
                            .contentType(ContentType.JSON)
                            .when()
                            .get("/api/v1/billing/plans");

            assertThat(response.statusCode()).isEqualTo(200);

            // Verify each plan has the required structure
            var plans = response.jsonPath().getList("$");
            for (int i = 0; i < plans.size(); i++) {
                assertThat(response.jsonPath().getString("[" + i + "].tier")).isNotBlank();
                assertThat(response.jsonPath().getString("[" + i + "].displayName")).isNotBlank();
            }
        }
    }

    // =======================================================================
    // 5. Subscription Query (Local DB)
    // =======================================================================

    @Nested
    @Order(5)
    @DisplayName("5 — Subscription Query")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SubscriptionQuery {

        @Test
        @Order(1)
        @DisplayName("GET /subscription → 200 with free/none default")
        void getSubscriptionReturnsDefault() {
            Response response =
                    given().header("Authorization", auth())
                            .contentType(ContentType.JSON)
                            .when()
                            .get("/api/v1/billing/subscription");

            // Should return 200 — either a real subscription or the default free/none
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.jsonPath().getString("planTier")).isNotBlank();
        }

        @Test
        @Order(2)
        @DisplayName("POST /subscription → 503 when Stripe not configured")
        void createSubscriptionReturns503() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                            { "planTier": "pro" }
                            """)
                    .when()
                    .post("/api/v1/billing/subscription")
                    .then()
                    .statusCode(503)
                    .body("error", equalTo("service_unavailable"));
        }

        @Test
        @Order(3)
        @DisplayName("PUT /subscription → 503 when Stripe not configured")
        void changeSubscriptionReturns503() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                            { "planTier": "business" }
                            """)
                    .when()
                    .put("/api/v1/billing/subscription")
                    .then()
                    .statusCode(503)
                    .body("error", equalTo("service_unavailable"));
        }

        @Test
        @Order(4)
        @DisplayName("DELETE /subscription → 503 when Stripe not configured")
        void cancelSubscriptionReturns503() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .when()
                    .delete("/api/v1/billing/subscription")
                    .then()
                    .statusCode(503)
                    .body("error", equalTo("service_unavailable"));
        }
    }

    // =======================================================================
    // 6. Payment Methods (Local DB query + Stripe-dependent mutations)
    // =======================================================================

    @Nested
    @Order(6)
    @DisplayName("6 — Payment Methods")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class PaymentMethods {

        @Test
        @Order(1)
        @DisplayName("GET /payment-methods → 200 with empty list (no payment methods)")
        void listPaymentMethodsReturnsEmpty() {
            Response response =
                    given().header("Authorization", auth())
                            .contentType(ContentType.JSON)
                            .when()
                            .get("/api/v1/billing/payment-methods");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.jsonPath().getList("$")).isEmpty();
        }

        @Test
        @Order(2)
        @DisplayName("POST /payment-methods/{id}/attach → 503 when Stripe not configured")
        void attachPaymentMethodReturns503() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .when()
                    .post("/api/v1/billing/payment-methods/pm_test_fake/attach")
                    .then()
                    .statusCode(503)
                    .body("error", equalTo("service_unavailable"));
        }

        @Test
        @Order(3)
        @DisplayName("DELETE /payment-methods/{id} → 503 when Stripe not configured")
        void detachPaymentMethodReturns503() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .when()
                    .delete("/api/v1/billing/payment-methods/pm_test_fake")
                    .then()
                    .statusCode(503)
                    .body("error", equalTo("service_unavailable"));
        }

        @Test
        @Order(4)
        @DisplayName("PUT /payment-methods/{id}/default → 503 when Stripe not configured")
        void setDefaultPaymentMethodReturns503() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .when()
                    .put("/api/v1/billing/payment-methods/pm_test_fake/default")
                    .then()
                    .statusCode(503)
                    .body("error", equalTo("service_unavailable"));
        }
    }

    // =======================================================================
    // 7. Invoices (Local DB)
    // =======================================================================

    @Nested
    @Order(7)
    @DisplayName("7 — Invoices")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Invoices {

        @Test
        @Order(1)
        @DisplayName("GET /invoices → 200 with empty list (new tenant)")
        void listInvoicesReturnsEmpty() {
            Response response =
                    given().header("Authorization", auth())
                            .contentType(ContentType.JSON)
                            .when()
                            .get("/api/v1/billing/invoices");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.jsonPath().getList("$")).isEmpty();
        }

        @Test
        @Order(2)
        @DisplayName("GET /invoices/{id} → 400 for invalid invoice ID format")
        void getInvoiceReturns400ForInvalidId() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .when()
                    .get("/api/v1/billing/invoices/inv_nonexistent")
                    .then()
                    .statusCode(400);
        }

        @Test
        @Order(3)
        @DisplayName("GET /invoices/{id} → 404 for non-existent UUID invoice")
        void getInvoiceReturns404ForValidUuid() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .when()
                    .get("/api/v1/billing/invoices/00000000-0000-0000-0000-000000000000")
                    .then()
                    .statusCode(404)
                    .body("error", equalTo("not_found"))
                    .body("message", containsString("Invoice not found"));
        }
    }
}
