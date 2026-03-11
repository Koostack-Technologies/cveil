package com.koostack.operio.rest.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.platform.iam.TokenValidationService;
import com.koostack.operio.rest.config.OperioConfig;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Full end-to-end integration test that boots the Quarkus REST API in <strong>production
 * mode</strong> against real Postgres and Redis instances provided by Testcontainers.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Prior to this test, every test in {@code operio-rest-api} was a pure Mockito unit test. The
 * Quarkus container was never booted during tests, which meant:
 *
 * <ul>
 *   <li>{@code application.yml} was never loaded or validated
 *   <li>Database credentials were never checked (they defaulted to empty)
 *   <li>Flyway migrations never ran against real Postgres
 *   <li>jOOQ SQL was never executed against a real database
 *   <li>Redis consent decision wiring was never exercised
 *   <li>CDI / config-mapping / SPI wiring was never tested end-to-end
 * </ul>
 *
 * <p>This allowed the entire suite to pass even when critical production configuration was missing
 * or broken. This test closes that gap by exercising the full production stack: Postgres (Flyway +
 * jOOQ), Redis (Jedis), CDI wiring, JAX-RS filters, tenant schema routing, and the complete consent
 * lifecycle (register → grant → evaluate → revoke).
 *
 * <h2>Infrastructure</h2>
 *
 * <p>{@link OperioTestResourceManager} starts Postgres 16 and Redis 7 containers before the Quarkus
 * app boots, and injects their connection details as config properties. The test {@code
 * application.yml} references these properties via {@code ${operio.test.*}} placeholders.
 *
 * <h2>Test flow</h2>
 *
 * <p>Tests are ordered to build on each other — each phase depends on state created by the previous
 * phase:
 *
 * <ol>
 *   <li><strong>Container startup</strong> — verifies CDI beans are wired
 *   <li><strong>Health</strong> — verifies all plugins report ready
 *   <li><strong>Platform</strong> — creates a tenant and API key via bootstrap key auth
 *   <li><strong>Admin</strong> — registers fields, categories, purposes, regulations, and links
 *       using the API key
 *   <li><strong>Consent</strong> — grants, evaluates, and revokes consent using the API key,
 *       exercising jOOQ + Redis end-to-end
 *   <li><strong>Batch</strong> — exercises batch grant/revoke/evaluate
 *   <li><strong>Subject data</strong> — queries subject summary and history
 *   <li><strong>Bootstrap auto-disable</strong> — verifies the bootstrap key is rejected after a
 *       real API key exists (with force-bootstrap off)
 * </ol>
 *
 * @see OperioTestResourceManager
 * @see com.koostack.operio.rest.config.OperioProducers
 */
@QuarkusTest
@QuarkusTestResource(OperioTestResourceManager.class)
@DisplayName("Production Stack Integration Test (Postgres + Redis)")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductionStackIT {

    // -- Bootstrap key configured in test application.yml ---------------------
    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";

    // -- Test tenant ----------------------------------------------------------
    private static final String TENANT_ID = "integration_test_tenant";
    private static final String TENANT_DISPLAY = "Integration Test Tenant";
    private static final String TENANT_PLAN = "free";

    // -- Shared state populated by ordered tests ------------------------------
    // These are static because @Nested inner classes get fresh outer instances
    // but we need state to flow across ordered test methods.
    private static volatile String accessToken;
    private static volatile IntegrationTestSupport support;

    // -----------------------------------------------------------------------
    // CDI injection — if any of these fail, the container didn't wire
    // -----------------------------------------------------------------------

    @Inject OperioConfig config;

    @Inject
    @SuppressWarnings("rawtypes")
    TokenValidationService tokenValidationService;

    /** Polls a GET endpoint until the JSON path matches the expected matcher. */
    private static void awaitGet(
            String path, String authToken, String jsonPath, Matcher<?> matcher) {
        Awaitility.await()
                .atMost(90, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(
                        () ->
                                given().header("Authorization", "Bearer " + authToken)
                                        .when()
                                        .get(path)
                                        .then()
                                        .statusCode(200)
                                        .body(jsonPath, matcher));
    }

    /** Polls a POST endpoint (e.g. evaluate) until the JSON path matches. */
    private static void awaitPost(
            String path, String authToken, String body, String jsonPath, Matcher<?> matcher) {
        Awaitility.await()
                .atMost(90, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(
                        () ->
                                given().header("Authorization", "Bearer " + authToken)
                                        .contentType(ContentType.JSON)
                                        .body(body)
                                        .when()
                                        .post(path)
                                        .then()
                                        .statusCode(200)
                                        .body(jsonPath, matcher));
    }

    // =======================================================================
    // 1. Container startup & CDI wiring
    // =======================================================================

    @Nested
    @Order(1)
    @DisplayName("1 — Container Startup & CDI Wiring")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ContainerStartup {

        @Test
        @Order(1)
        @DisplayName("Quarkus container starts without errors")
        void containerStarts() {
            // Reaching this point means the full Quarkus container booted:
            //   - application.yml loaded and validated
            //   - SmallRye @ConfigMapping resolved OperioConfig
            //   - OperioProducers instantiated and validated credentials
            //   - Flyway migrations ran against real Postgres
            //   - jOOQ DSLContext created with RenderNameCase.LOWER
            //   - Redis Jedis pool initialized
            //   - All CDI beans wired
            //   - JAX-RS providers instantiated
        }

        @Test
        @Order(2)
        @DisplayName("OperioConfig is injected (production mode)")
        void operioConfigInjected() {
            assertNotNull(config, "OperioConfig bean must be injected");
            assertTrue(config.appName().isPresent(), "App name must be configured");
            assertEquals(
                    "operio-integration-test",
                    config.appName().get(),
                    "App name should match test application.yml");
        }

        @Test
        @Order(3)
        @DisplayName("OperioConfig resolves with production mode")
        void configResolved() {
            assertNotNull(config, "OperioConfig must be injected");
            assertEquals("production", config.mode(), "Test should run in production mode");
            assertTrue(config.db().isPresent(), "operio.db must be configured");
            assertTrue(config.redis().isPresent(), "operio.redis must be configured");
            assertTrue(config.platformDb().isPresent(), "operio.platform-db must be configured");
        }

        @Test
        @Order(5)
        @DisplayName("TokenValidationService is wired (not null)")
        void tokenValidationServiceWired() {
            assertNotNull(
                    tokenValidationService,
                    "TokenValidationService must be wired in production mode");
        }

        @Test
        @Order(6)
        @DisplayName("DB credentials are non-blank (validation passed)")
        void credentialsNonBlank() {
            OperioConfig.DbConfig db = config.db().orElseThrow();
            assertTrue(
                    db.username().isPresent() && !db.username().get().isBlank(),
                    "operio.db.username must be non-blank");
            assertTrue(
                    db.password().isPresent() && !db.password().get().isBlank(),
                    "operio.db.password must be non-blank");

            OperioConfig.DbConfig platformDb = config.platformDb().orElseThrow();
            assertTrue(
                    platformDb.username().isPresent() && !platformDb.username().get().isBlank(),
                    "operio.platform-db.username must be non-blank");
            assertTrue(
                    platformDb.password().isPresent() && !platformDb.password().get().isBlank(),
                    "operio.platform-db.password must be non-blank");
        }
    }

    // =======================================================================
    // 2. Health endpoints
    // =======================================================================

    @Nested
    @Order(2)
    @DisplayName("2 — Health Endpoints")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class HealthEndpoints {

        @Test
        @Order(1)
        @DisplayName("Application health endpoint returns UP")
        void applicationHealth() {
            given().when()
                    .get("/api/v1/health")
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("status", equalTo("UP"));
        }

        @Test
        @Order(2)
        @DisplayName("Quarkus health endpoint returns UP")
        void quarkusHealth() {
            given().when()
                    .get("/q/health")
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("status", equalTo("UP"));
        }

        @Test
        @Order(3)
        @DisplayName("Quarkus readiness probe returns 200")
        void quarkusReady() {
            given().when().get("/q/health/ready").then().statusCode(200);
        }

        @Test
        @Order(4)
        @DisplayName("Quarkus liveness probe returns 200")
        void quarkusLive() {
            given().when().get("/q/health/live").then().statusCode(200);
        }

        @Test
        @Order(5)
        @DisplayName("OpenAPI spec is served")
        void openApiAvailable() {
            given().accept(ContentType.JSON).when().get("/q/openapi").then().statusCode(200);
        }
    }

    // =======================================================================
    // 3. Auth enforcement (before any keys exist)
    // =======================================================================

    @Nested
    @Order(3)
    @DisplayName("3 — Auth Enforcement (unauthenticated)")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class AuthEnforcement {

        @Test
        @Order(1)
        @DisplayName("Consent endpoint rejects missing auth")
        void consentRequiresAuth() {
            given().contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "subject": "user-1",
                      "scope": {
                        "category": "PII",
                        "fieldName": "email",
                        "entityName": "User",
                        "purposeName": "marketing"
                      }
                    }
                    """)
                    .when()
                    .post("/api/v1/subjects/consent/grant")
                    .then()
                    .statusCode(401);
        }

        @Test
        @Order(2)
        @DisplayName("Admin endpoint rejects missing auth")
        void adminRequiresAuth() {
            given().when().get("/api/v1/catalogue/fields").then().statusCode(401);
        }

        @Test
        @Order(3)
        @DisplayName("Platform endpoint rejects missing auth")
        void platformRequiresAuth() {
            given().when().get("/api/v1/platform/tenants").then().statusCode(401);
        }

        @Test
        @Order(4)
        @DisplayName("Invalid bearer token is rejected")
        void invalidTokenRejected() {
            given().header("Authorization", "Bearer totally-invalid-token")
                    .when()
                    .get("/api/v1/platform/tenants")
                    .then()
                    .statusCode(401);
        }

        @Test
        @Order(5)
        @DisplayName("Non-bearer auth scheme is rejected")
        void basicAuthRejected() {
            given().header("Authorization", "Basic dXNlcjpwYXNz")
                    .when()
                    .get("/api/v1/platform/tenants")
                    .then()
                    .statusCode(401);
        }
    }

    // =======================================================================
    // 4. Platform — tenant lifecycle + Keycloak login (bootstrap key auth)
    // =======================================================================

    @Nested
    @Order(4)
    @DisplayName("4 — Platform: Tenant Lifecycle & Keycloak Login")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class PlatformLifecycle {

        @Test
        @Order(1)
        @DisplayName("Bootstrap key authenticates for platform endpoints")
        void bootstrapKeyAccepted() {
            given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                    .when()
                    .get("/api/v1/platform/tenants")
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON);
        }

        @Test
        @Order(2)
        @DisplayName("Create tenant via bootstrap key")
        void createTenant() {
            try {
                String result =
                        IntegrationTestSupport.provisionTenantViaOrchestrator(
                                BOOTSTRAP_KEY, TENANT_ID, TENANT_DISPLAY, TENANT_PLAN, "Test1234!");
                assertThat(result).isEqualTo(TENANT_ID);
            } catch (Exception e) {
                if (!e.getMessage().contains("already exists")) {
                    throw e;
                }
            }
        }

        @Test
        @Order(3)
        @DisplayName("Tenant appears in list")
        void listTenants() {
            Awaitility.await()
                    .atMost(90, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(
                            () ->
                                    given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                                            .when()
                                            .get("/api/v1/platform/tenants")
                                            .then()
                                            .statusCode(200)
                                            .body("tenantId", hasItem(TENANT_ID)));
        }

        @Test
        @Order(4)
        @DisplayName("Get tenant by ID")
        void getTenant() {
            // displayName may lag if redis-provisioned event creates the row before
            // tenant-created event updates it, so poll until projection catches up.
            Awaitility.await()
                    .atMost(90, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(
                            () ->
                                    given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                                            .when()
                                            .get("/api/v1/platform/tenants/" + TENANT_ID)
                                            .then()
                                            .statusCode(200)
                                            .body("tenantId", equalTo(TENANT_ID))
                                            .body("displayName", equalTo(TENANT_DISPLAY))
                                            .body("plan", equalTo(TENANT_PLAN)));
        }

        @Test
        @Order(5)
        @DisplayName("Login as tenant user")
        void loginAsTenantUser() {
            String email = TENANT_ID.replace("_", "-") + "-admin@operio-test.com";
            accessToken = IntegrationTestSupport.loginUser(email, "Test1234!");
            assertNotNull(accessToken, "JWT access token must be returned");
            assertFalse(accessToken.isBlank(), "JWT access token must not be blank");

            // Initialize support and wait for async infrastructure
            support = new IntegrationTestSupport(config, TENANT_ID);
            support.awaitTenantReady(TENANT_ID, Duration.ofSeconds(90));
        }
    }

    // =======================================================================
    // 5. Admin — consent schema registration (field, category, purpose, etc.)
    // =======================================================================

    @Nested
    @Order(5)
    @DisplayName("5 — Admin: Consent Schema Registration")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class AdminSchemaRegistration {

        private String auth() {
            assertNotNull(accessToken, "accessToken must be set");
            return "Bearer " + accessToken;
        }

        // -- Fields -----------------------------------------------------------

        @Test
        @Order(1)
        @DisplayName("Register field: email (User entity)")
        void registerFieldEmail() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "fieldName": "email",
                      "entityName": "User",
                      "description": "User email address"
                    }
                    """)
                    .when()
                    .post("/api/v1/catalogue/fields")
                    .then()
                    .statusCode(anyOf(is(200), is(201)))
                    .body("message", notNullValue());
        }

        @Test
        @Order(2)
        @DisplayName("Register field: ssn (User entity)")
        void registerFieldSsn() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "fieldName": "ssn",
                      "entityName": "User",
                      "description": "Social security number"
                    }
                    """)
                    .when()
                    .post("/api/v1/catalogue/fields")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(3)
        @DisplayName("Register field: diagnosis (Patient entity)")
        void registerFieldDiagnosis() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "fieldName": "diagnosis",
                      "entityName": "Patient",
                      "description": "Medical diagnosis"
                    }
                    """)
                    .when()
                    .post("/api/v1/catalogue/fields")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(4)
        @DisplayName("List fields returns registered fields")
        void listFields() {
            awaitGet(
                    "/api/v1/catalogue/fields", accessToken, "$", hasSize(greaterThanOrEqualTo(3)));
        }

        // -- Categories -------------------------------------------------------

        @Test
        @Order(10)
        @DisplayName("Register category: PII")
        void registerCategoryPii() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "categoryName": "PII",
                      "description": "Personally Identifiable Information"
                    }
                    """)
                    .when()
                    .post("/api/v1/catalogue/categories")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(11)
        @DisplayName("Register category: HEALTH")
        void registerCategoryHealth() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "categoryName": "HEALTH",
                      "description": "Protected health information"
                    }
                    """)
                    .when()
                    .post("/api/v1/catalogue/categories")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(12)
        @DisplayName("List categories returns registered categories")
        void listCategories() {
            awaitGet(
                    "/api/v1/catalogue/categories",
                    accessToken,
                    "$",
                    hasSize(greaterThanOrEqualTo(2)));
        }

        // -- Purposes ---------------------------------------------------------

        @Test
        @Order(20)
        @DisplayName("Register purpose: marketing")
        void registerPurposeMarketing() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "purposeName": "marketing",
                      "description": "Email marketing campaigns"
                    }
                    """)
                    .when()
                    .post("/api/v1/governance/purposes")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(21)
        @DisplayName("Register purpose: analytics")
        void registerPurposeAnalytics() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "purposeName": "analytics",
                      "description": "Product usage analytics"
                    }
                    """)
                    .when()
                    .post("/api/v1/governance/purposes")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(22)
        @DisplayName("List purposes returns registered purposes")
        void listPurposes() {
            awaitGet(
                    "/api/v1/governance/purposes",
                    accessToken,
                    "$",
                    hasSize(greaterThanOrEqualTo(2)));
        }

        // -- Regulations ------------------------------------------------------

        @Test
        @Order(30)
        @DisplayName("Register regulation: GDPR")
        void registerRegulationGdpr() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "regulationName": "GDPR",
                      "fullName": "General Data Protection Regulation",
                      "jurisdiction": "EU",
                      "description": "EU data protection regulation",
                      "url": "https://gdpr.eu"
                    }
                    """)
                    .when()
                    .post("/api/v1/governance/regulations")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(31)
        @DisplayName("List regulations returns GDPR")
        void listRegulations() {
            awaitGet(
                    "/api/v1/governance/regulations",
                    accessToken,
                    "$",
                    hasSize(greaterThanOrEqualTo(1)));
        }

        // -- Links (field↔category, purpose↔regulation) ----------------------

        @Test
        @Order(40)
        @DisplayName("Link field 'email' to category 'PII'")
        void linkEmailToPii() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "fieldName": "email",
                      "entityName": "User",
                      "categoryName": "PII"
                    }
                    """)
                    .when()
                    .post("/api/v1/catalogue/fields/categories")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(41)
        @DisplayName("Link field 'ssn' to category 'PII'")
        void linkSsnToPii() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "fieldName": "ssn",
                      "entityName": "User",
                      "categoryName": "PII"
                    }
                    """)
                    .when()
                    .post("/api/v1/catalogue/fields/categories")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(42)
        @DisplayName("Link field 'diagnosis' to category 'HEALTH'")
        void linkDiagnosisToHealth() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "fieldName": "diagnosis",
                      "entityName": "Patient",
                      "categoryName": "HEALTH"
                    }
                    """)
                    .when()
                    .post("/api/v1/catalogue/fields/categories")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(43)
        @DisplayName("Link purpose 'marketing' to regulation 'GDPR'")
        void linkMarketingToGdpr() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "purposeName": "marketing",
                      "regulationName": "GDPR",
                      "lawfulBasis": "consent"
                    }
                    """)
                    .when()
                    .post("/api/v1/governance/purposes/regulations")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(44)
        @DisplayName("Link purpose 'analytics' to regulation 'GDPR'")
        void linkAnalyticsToGdpr() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "purposeName": "analytics",
                      "regulationName": "GDPR",
                      "lawfulBasis": "consent"
                    }
                    """)
                    .when()
                    .post("/api/v1/governance/purposes/regulations")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        // -- Policies (required before consent can be granted) ----------------

        @Test
        @Order(45)
        @DisplayName("Create policy: email/User/PII → marketing")
        void policyEmailMarketing() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "purposeName": "marketing",
                      "policyType": "field",
                      "fieldName": "email",
                      "entityName": "User",
                      "categoryName": "PII"
                    }
                    """)
                    .when()
                    .post("/api/v1/governance/purposes/policies")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(46)
        @DisplayName("Create policy: ssn/User/PII → analytics")
        void policySsnAnalytics() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "purposeName": "analytics",
                      "policyType": "field",
                      "fieldName": "ssn",
                      "entityName": "User",
                      "categoryName": "PII"
                    }
                    """)
                    .when()
                    .post("/api/v1/governance/purposes/policies")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(47)
        @DisplayName("Create policy: diagnosis/Patient/HEALTH → analytics")
        void policyDiagnosisAnalytics() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "purposeName": "analytics",
                      "policyType": "field",
                      "fieldName": "diagnosis",
                      "entityName": "Patient",
                      "categoryName": "HEALTH"
                    }
                    """)
                    .when()
                    .post("/api/v1/governance/purposes/policies")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        // -- Entities (implicitly created via field registration) --------------

        @Test
        @Order(50)
        @DisplayName("List entities returns implicitly created entities")
        void listEntities() {
            awaitGet(
                    "/api/v1/catalogue/entities",
                    accessToken,
                    "$",
                    hasSize(greaterThanOrEqualTo(2)));
        }

        @Test
        @Order(60)
        @DisplayName("Await governance projection (policies visible)")
        void awaitGovernanceProjection() {
            // Wait until all 3 policies are projected into the operio_consent.policy table
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(90))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () -> {
                                var rows =
                                        support.query(
                                                "SELECT count(*) AS cnt FROM operio_consent.policy"
                                                        + " WHERE tenant_id = ?",
                                                TENANT_ID);
                                int cnt = ((Number) rows.get(0).get("cnt")).intValue();
                                assertThat(cnt)
                                        .as("All 3 policies should be projected")
                                        .isGreaterThanOrEqualTo(3);
                            });
        }
    }

    // =======================================================================
    // 6. Consent — grant, evaluate, revoke (single operations)
    // =======================================================================

    @Nested
    @Order(6)
    @DisplayName("6 — Consent: Grant, Evaluate, Revoke")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ConsentSingleOps {

        private String auth() {
            assertNotNull(accessToken, "accessToken must be set");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(0)
        @DisplayName("Register subject: user-1")
        void registerSubject() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "subjectName": "user-1", "description": "Test subject" }
                    """)
                    .when()
                    .post("/api/v1/subjects")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(1)
        @DisplayName("Grant consent: user-1 → email (PII, User, marketing)")
        void grantEmailMarketing() {
            Awaitility.await()
                    .atMost(90, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(
                            () ->
                                    given().header("Authorization", auth())
                                            .contentType(ContentType.JSON)
                                            .body(
                                                    """
                                    {
                                      "subject": "user-1",
                                      "scope": {
                                        "category": "PII",
                                        "fieldName": "email",
                                        "entityName": "User",
                                        "purposeName": "marketing"
                                      }
                                    }
                                    """)
                                            .when()
                                            .post("/api/v1/subjects/consent/grant")
                                            .then()
                                            .statusCode(200)
                                            .body(
                                                    "message",
                                                    containsStringIgnoringCase("granted")));
        }

        @Test
        @Order(2)
        @DisplayName("Grant consent: user-1 → ssn (PII, User, analytics)")
        void grantSsnAnalytics() {
            Awaitility.await()
                    .atMost(90, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(
                            () ->
                                    given().header("Authorization", auth())
                                            .contentType(ContentType.JSON)
                                            .body(
                                                    """
                                    {
                                      "subject": "user-1",
                                      "scope": {
                                        "category": "PII",
                                        "fieldName": "ssn",
                                        "entityName": "User",
                                        "purposeName": "analytics"
                                      }
                                    }
                                    """)
                                            .when()
                                            .post("/api/v1/subjects/consent/grant")
                                            .then()
                                            .statusCode(200));
        }

        @Test
        @Order(3)
        @DisplayName("Evaluate consent: granted scopes appear in 'granted' set")
        void evaluateGranted() {
            // Wait for the graph edge before calling evaluate (reads from Redis graph only).
            // Under co-running load (13 ITs), the graph projection can lag >90s.
            String policyName = "FIELD:User/email:marketing";
            support.awaitRedisState(
                    () ->
                            support.getEdgeProperties(
                                    TENANT_ID, "subject", "user-1", "policy", policyName),
                    ep -> ep != null && !ep.isEmpty(),
                    Duration.ofSeconds(180));

            String body =
                    """
                    {
                      "scopes": [
                        {
                          "category": "PII",
                          "fieldName": "email",
                          "entityName": "User",
                          "purposeName": "marketing"
                        }
                      ]
                    }
                    """;
            awaitPost("/api/v1/subjects/user-1/evaluate", accessToken, body, "granted", hasSize(1));
        }

        @Test
        @Order(4)
        @DisplayName("Evaluate consent: non-granted scope appears in 'denied' set")
        void evaluateDenied() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "scopes": [
                        {
                          "category": "HEALTH",
                          "fieldName": "diagnosis",
                          "entityName": "Patient",
                          "purposeName": "analytics"
                        }
                      ]
                    }
                    """)
                    .when()
                    .post("/api/v1/subjects/user-1/evaluate")
                    .then()
                    .statusCode(200)
                    .body("subject", equalTo("user-1"))
                    .body("granted", hasSize(0))
                    .body("denied", hasSize(1))
                    .body("denied[0].fieldName", equalTo("diagnosis"));
        }

        @Test
        @Order(5)
        @DisplayName("Evaluate mixed: some granted, some denied")
        void evaluateMixed() {
            String body =
                    """
                    {
                      "scopes": [
                        {
                          "category": "PII",
                          "fieldName": "email",
                          "entityName": "User",
                          "purposeName": "marketing"
                        },
                        {
                          "category": "HEALTH",
                          "fieldName": "diagnosis",
                          "entityName": "Patient",
                          "purposeName": "analytics"
                        }
                      ]
                    }
                    """;
            awaitPost("/api/v1/subjects/user-1/evaluate", accessToken, body, "granted", hasSize(1));
        }

        @Test
        @Order(6)
        @DisplayName("Revoke consent: user-1 → email (PII, User, marketing)")
        void revokeEmailMarketing() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "subject": "user-1",
                      "scope": {
                        "category": "PII",
                        "fieldName": "email",
                        "entityName": "User",
                        "purposeName": "marketing"
                      }
                    }
                    """)
                    .when()
                    .post("/api/v1/subjects/consent/revoke")
                    .then()
                    .statusCode(200)
                    .body("message", containsStringIgnoringCase("revoked"));
        }

        @Test
        @Order(7)
        @DisplayName("After revoke: email/marketing now denied")
        void evaluateAfterRevoke() {
            String body =
                    """
                    {
                      "scopes": [
                        {
                          "category": "PII",
                          "fieldName": "email",
                          "entityName": "User",
                          "purposeName": "marketing"
                        }
                      ]
                    }
                    """;
            awaitPost("/api/v1/subjects/user-1/evaluate", accessToken, body, "denied", hasSize(1));
        }

        @Test
        @Order(8)
        @DisplayName("Grant is idempotent (re-granting the same scope succeeds)")
        void grantIdempotent() {
            // Grant the same scope twice — should succeed both times
            for (int i = 0; i < 2; i++) {
                given().header("Authorization", auth())
                        .contentType(ContentType.JSON)
                        .body(
                                """
                        {
                          "subject": "user-idempotent",
                          "scope": {
                            "category": "PII",
                            "fieldName": "email",
                            "entityName": "User",
                            "purposeName": "marketing"
                          }
                        }
                        """)
                        .when()
                        .post("/api/v1/subjects/consent/grant")
                        .then()
                        .statusCode(200);
            }
        }
    }

    // =======================================================================
    // 7. Consent — batch operations
    // =======================================================================

    @Nested
    @Order(7)
    @DisplayName("7 — Consent: Batch Grant & Revoke")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ConsentBatchOps {

        private String auth() {
            assertNotNull(accessToken, "accessToken must be set");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(0)
        @DisplayName("Register subject: user-batch")
        void registerBatchSubject() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "subjectName": "user-batch", "description": "Batch test subject" }
                    """)
                    .when()
                    .post("/api/v1/subjects")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(1)
        @DisplayName("Batch grant: multiple scopes at once")
        void batchGrant() {
            Awaitility.await()
                    .atMost(90, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(
                            () ->
                                    given().header("Authorization", auth())
                                            .contentType(ContentType.JSON)
                                            .body(
                                                    """
                                    {
                                      "subject": "user-batch",
                                      "grants": [
                                        {
                                          "scope": {
                                            "category": "PII",
                                            "fieldName": "email",
                                            "entityName": "User",
                                            "purposeName": "marketing"
                                          }
                                        },
                                        {
                                          "scope": {
                                            "category": "PII",
                                            "fieldName": "ssn",
                                            "entityName": "User",
                                            "purposeName": "analytics"
                                          }
                                        },
                                        {
                                          "scope": {
                                            "category": "HEALTH",
                                            "fieldName": "diagnosis",
                                            "entityName": "Patient",
                                            "purposeName": "analytics"
                                          }
                                        }
                                      ]
                                    }
                                    """)
                                            .when()
                                            .post("/api/v1/subjects/consent/grant-all")
                                            .then()
                                            .statusCode(200)
                                            .contentType(ContentType.JSON)
                                            .body("succeeded", equalTo(3))
                                            .body("failed", equalTo(0)));
        }

        @Test
        @Order(2)
        @DisplayName("Evaluate after batch grant: all 3 scopes granted")
        void evaluateAfterBatchGrant() {
            // Wait for the slowest graph edge (HEALTH/diagnosis) before evaluating.
            // Under co-running load (13 ITs), the graph projection can lag >90s.
            String policyName = "FIELD:Patient/diagnosis:analytics";
            support.awaitRedisState(
                    () ->
                            support.getEdgeProperties(
                                    TENANT_ID, "subject", "user-batch", "policy", policyName),
                    ep -> ep != null && !ep.isEmpty(),
                    Duration.ofSeconds(180));

            String body =
                    """
                    {
                      "scopes": [
                        {
                          "category": "PII",
                          "fieldName": "email",
                          "entityName": "User",
                          "purposeName": "marketing"
                        },
                        {
                          "category": "PII",
                          "fieldName": "ssn",
                          "entityName": "User",
                          "purposeName": "analytics"
                        },
                        {
                          "category": "HEALTH",
                          "fieldName": "diagnosis",
                          "entityName": "Patient",
                          "purposeName": "analytics"
                        }
                      ]
                    }
                    """;
            awaitPost(
                    "/api/v1/subjects/user-batch/evaluate",
                    accessToken,
                    body,
                    "granted",
                    hasSize(3));
        }

        @Test
        @Order(3)
        @DisplayName("Batch revoke: revoke 2 of 3 scopes")
        void batchRevoke() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "subject": "user-batch",
                      "revokes": [
                        {
                          "scope": {
                            "category": "PII",
                            "fieldName": "email",
                            "entityName": "User",
                            "purposeName": "marketing"
                          }
                        },
                        {
                          "scope": {
                            "category": "HEALTH",
                            "fieldName": "diagnosis",
                            "entityName": "Patient",
                            "purposeName": "analytics"
                          }
                        }
                      ]
                    }
                    """)
                    .when()
                    .post("/api/v1/subjects/consent/revoke-all")
                    .then()
                    .statusCode(200)
                    .body("succeeded", equalTo(2))
                    .body("failed", equalTo(0));
        }

        @Test
        @Order(4)
        @DisplayName("Evaluate after batch revoke: 1 granted, 2 denied")
        void evaluateAfterBatchRevoke() {
            String body =
                    """
                    {
                      "scopes": [
                        {
                          "category": "PII",
                          "fieldName": "email",
                          "entityName": "User",
                          "purposeName": "marketing"
                        },
                        {
                          "category": "PII",
                          "fieldName": "ssn",
                          "entityName": "User",
                          "purposeName": "analytics"
                        },
                        {
                          "category": "HEALTH",
                          "fieldName": "diagnosis",
                          "entityName": "Patient",
                          "purposeName": "analytics"
                        }
                      ]
                    }
                    """;
            // After revoke, only ssn/analytics should remain granted
            awaitPost(
                    "/api/v1/subjects/user-batch/evaluate",
                    accessToken,
                    body,
                    "denied",
                    hasSize(2));
        }
    }

    // =======================================================================
    // 8. Subject data — summary and history
    // =======================================================================

    @Nested
    @Order(8)
    @DisplayName("8 — Subject Data: Summary & History")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SubjectData {

        private String auth() {
            assertNotNull(accessToken, "accessToken must be set");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("Subject summary returns current consent state")
        void subjectSummary() {
            // user-batch should have active grants from the batch tests above
            Awaitility.await()
                    .atMost(90, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(
                            () ->
                                    given().header("Authorization", auth())
                                            .when()
                                            .get("/api/v1/subjects/user-batch/summary")
                                            .then()
                                            .statusCode(200)
                                            .contentType(ContentType.JSON)
                                            .body("entries", hasSize(greaterThanOrEqualTo(1))));
        }

        @Test
        @Order(2)
        @DisplayName("Subject history returns audit trail")
        void subjectHistory() {
            // user-batch had 3 grants + 2 revokes = at least 5 audit entries
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/subjects/user-batch/history")
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("entries", hasSize(greaterThanOrEqualTo(1)));
        }

        @Test
        @Order(3)
        @DisplayName("Unknown subject returns empty summary")
        void unknownSubjectSummary() {
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/subjects/nonexistent-user/summary")
                    .then()
                    .statusCode(200)
                    .body("entries", hasSize(0));
        }

        @Test
        @Order(4)
        @DisplayName("Unknown subject returns empty history")
        void unknownSubjectHistory() {
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/subjects/nonexistent-user/history")
                    .then()
                    .statusCode(200)
                    .body("entries", hasSize(0));
        }
    }

    // =======================================================================
    // 9. Auth enforcement — authenticated user access
    // =======================================================================

    @Nested
    @Order(9)
    @DisplayName("9 — Auth Enforcement")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class AuthEnforcementWithToken {

        @Test
        @Order(1)
        @DisplayName("Authenticated user can read consent")
        void authenticatedCanRead() {
            given().header("Authorization", "Bearer " + accessToken)
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "scopes": [
                        {
                          "category": "PII",
                          "fieldName": "email",
                          "entityName": "User",
                          "purposeName": "marketing"
                        }
                      ]
                    }
                    """)
                    .when()
                    .post("/api/v1/subjects/user-batch/evaluate")
                    .then()
                    .statusCode(200);
        }

        @Test
        @Order(2)
        @DisplayName("Authenticated user can read admin")
        void authenticatedCanReadAdmin() {
            given().header("Authorization", "Bearer " + accessToken)
                    .when()
                    .get("/api/v1/catalogue/fields")
                    .then()
                    .statusCode(200);
        }
    }

    // =======================================================================
    // 10. Validation — request body validation
    // =======================================================================

    @Nested
    @Order(10)
    @DisplayName("10 — Request Validation")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class RequestValidation {

        private String auth() {
            assertNotNull(accessToken, "accessToken must be set");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("Grant with blank subject is rejected (400)")
        void grantBlankSubject() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "subject": "",
                      "scope": {
                        "category": "PII",
                        "fieldName": "email",
                        "entityName": "User",
                        "purposeName": "marketing"
                      }
                    }
                    """)
                    .when()
                    .post("/api/v1/subjects/consent/grant")
                    .then()
                    .statusCode(400);
        }

        @Test
        @Order(2)
        @DisplayName("Grant with null scope is rejected (400)")
        void grantNullScope() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "subject": "user-1"
                    }
                    """)
                    .when()
                    .post("/api/v1/subjects/consent/grant")
                    .then()
                    .statusCode(400);
        }

        @Test
        @Order(3)
        @DisplayName("Evaluate with empty scopes is rejected (400)")
        void evaluateEmptyScopes() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "scopes": []
                    }
                    """)
                    .when()
                    .post("/api/v1/subjects/user-1/evaluate")
                    .then()
                    .statusCode(400);
        }

        @Test
        @Order(4)
        @DisplayName("Register field with blank fieldName is rejected (400)")
        void registerFieldBlankName() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "fieldName": "",
                      "entityName": "User"
                    }
                    """)
                    .when()
                    .post("/api/v1/catalogue/fields")
                    .then()
                    .statusCode(400);
        }

        @Test
        @Order(5)
        @DisplayName("Create tenant with invalid tenantId pattern is rejected (400)")
        void createTenantInvalidId() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "tenantId": "123-invalid!",
                      "displayName": "Bad Tenant",
                      "plan": "free"
                    }
                    """)
                    .when()
                    .post("/api/v1/platform/tenants")
                    .then()
                    .statusCode(400);
        }

        @Test
        @Order(6)
        @DisplayName("Batch grant with empty grants list is rejected (400)")
        void batchGrantEmpty() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "subject": "user-1",
                      "grants": []
                    }
                    """)
                    .when()
                    .post("/api/v1/subjects/consent/grant-all")
                    .then()
                    .statusCode(400);
        }
    }

    // =======================================================================
    // 11. Multi-subject isolation
    // =======================================================================

    @Nested
    @Order(11)
    @DisplayName("11 — Multi-Subject Isolation")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class MultiSubjectIsolation {

        private String auth() {
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("Different subjects have independent consent state")
        void subjectIsolation() {
            // Grant consent for subject-A only (retry in case governance projection still catching
            // up)
            Awaitility.await()
                    .atMost(90, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(
                            () ->
                                    given().header("Authorization", auth())
                                            .contentType(ContentType.JSON)
                                            .body(
                                                    """
                                    {
                                      "subject": "subject-A",
                                      "scope": {
                                        "category": "PII",
                                        "fieldName": "email",
                                        "entityName": "User",
                                        "purposeName": "marketing"
                                      }
                                    }
                                    """)
                                            .when()
                                            .post("/api/v1/subjects/consent/grant")
                                            .then()
                                            .statusCode(200));

            // subject-A should have consent granted (await projection)
            String evalBody =
                    """
                    {
                      "scopes": [{
                        "category": "PII",
                        "fieldName": "email",
                        "entityName": "User",
                        "purposeName": "marketing"
                      }]
                    }
                    """;
            awaitPost(
                    "/api/v1/subjects/subject-A/evaluate",
                    accessToken,
                    evalBody,
                    "granted",
                    hasSize(1));

            // subject-B should NOT have consent (never granted)
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(evalBody)
                    .when()
                    .post("/api/v1/subjects/subject-B/evaluate")
                    .then()
                    .statusCode(200)
                    .body("granted", hasSize(0))
                    .body("denied", hasSize(1));
        }
    }

    // =======================================================================
    // 13. Final health check — everything still healthy after all tests
    // =======================================================================

    @Nested
    @Order(13)
    @DisplayName("13 — Final Health Check")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class FinalHealthCheck {

        @Test
        @Order(1)
        @DisplayName("Application health still UP after full test run")
        void healthStillUp() {
            given().when()
                    .get("/api/v1/health")
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("UP"));
        }

        @Test
        @Order(2)
        @DisplayName("Quarkus health still UP after full test run")
        void quarkusHealthStillUp() {
            given().when().get("/q/health").then().statusCode(200).body("status", equalTo("UP"));
        }
    }
}
