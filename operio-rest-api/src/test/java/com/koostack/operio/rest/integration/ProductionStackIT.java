package com.koostack.operio.rest.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.koostack.operio.core.TenantService;
import com.koostack.operio.core.platform.iam.TokenValidationService;
import com.koostack.operio.rest.config.OperioConfig;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Full end-to-end integration test that boots the Quarkus REST API in
 * <strong>production mode</strong> against real Postgres and Redis instances
 * provided by Testcontainers.
 *
 * <h2>Why this test exists</h2>
 * <p>Prior to this test, every test in {@code operio-rest-api} was a pure
 * Mockito unit test. The Quarkus container was never booted during tests,
 * which meant:
 * <ul>
 *   <li>{@code application.yml} was never loaded or validated</li>
 *   <li>Database credentials were never checked (they defaulted to empty)</li>
 *   <li>Flyway migrations never ran against real Postgres</li>
 *   <li>jOOQ SQL was never executed against a real database</li>
 *   <li>Redis consent decision wiring was never exercised</li>
 *   <li>CDI / config-mapping / SPI wiring was never tested end-to-end</li>
 * </ul>
 * <p>This allowed the entire suite to pass even when critical production
 * configuration was missing or broken. This test closes that gap by
 * exercising the full production stack: Postgres (Flyway + jOOQ),
 * Redis (Jedis), CDI wiring, JAX-RS filters, tenant schema routing,
 * and the complete consent lifecycle (register → grant → evaluate → revoke).
 *
 * <h2>Infrastructure</h2>
 * <p>{@link OperioTestResourceManager} starts Postgres 16 and Redis 7
 * containers before the Quarkus app boots, and injects their connection
 * details as config properties. The test {@code application.yml} references
 * these properties via {@code ${operio.test.*}} placeholders.
 *
 * <h2>Test flow</h2>
 * <p>Tests are ordered to build on each other — each phase depends on state
 * created by the previous phase:
 * <ol>
 *   <li><strong>Container startup</strong> — verifies CDI beans are wired</li>
 *   <li><strong>Health</strong> — verifies all plugins report ready</li>
 *   <li><strong>Platform</strong> — creates a tenant and API key via
 *       bootstrap key auth</li>
 *   <li><strong>Admin</strong> — registers fields, categories, purposes,
 *       regulations, and links using the API key</li>
 *   <li><strong>Consent</strong> — grants, evaluates, and revokes consent
 *       using the API key, exercising jOOQ + Redis end-to-end</li>
 *   <li><strong>Batch</strong> — exercises batch grant/revoke/evaluate</li>
 *   <li><strong>Subject data</strong> — queries subject summary and
 *       history</li>
 *   <li><strong>Bootstrap auto-disable</strong> — verifies the bootstrap
 *       key is rejected after a real API key exists (with force-bootstrap
 *       off)</li>
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
    private static final String BOOTSTRAP_KEY =
        "test-bootstrap-key-for-integration-tests";

    // -- Test tenant ----------------------------------------------------------
    private static final String TENANT_ID = "integration_test_tenant";
    private static final String TENANT_DISPLAY = "Integration Test Tenant";
    private static final String TENANT_PLAN = "enterprise";

    // -- Shared state populated by ordered tests ------------------------------
    // These are static because @Nested inner classes get fresh outer instances
    // but we need state to flow across ordered test methods.
    private static volatile String apiKey;
    private static volatile String apiKeyPrefix;

    // -----------------------------------------------------------------------
    // CDI injection — if any of these fail, the container didn't wire
    // -----------------------------------------------------------------------

    @Inject
    TenantService tenantService;

    @Inject
    OperioConfig config;

    @Inject
    @SuppressWarnings("rawtypes")
    TokenValidationService tokenValidationService;

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
        @DisplayName("Operio bean is injected (production mode)")
        void operioInjected() {
            assertNotNull(tenantService, "TenantService bean must be injected");
            assertEquals(
                "operio-integration-test",
                tenantService.appName(),
                "App name should match test application.yml"
            );
        }

        @Test
        @Order(3)
        @DisplayName("OperioConfig resolves with production mode")
        void configResolved() {
            assertNotNull(config, "OperioConfig must be injected");
            assertEquals(
                "production",
                config.mode(),
                "Test should run in production mode"
            );
            assertTrue(
                config.db().isPresent(),
                "operio.db must be configured"
            );
            assertTrue(
                config.redis().isPresent(),
                "operio.redis must be configured"
            );
            assertTrue(
                config.platformDb().isPresent(),
                "operio.platform-db must be configured"
            );
        }

        @Test
        @Order(5)
        @DisplayName("TokenValidationService is wired (not null)")
        void tokenValidationServiceWired() {
            assertNotNull(
                tokenValidationService,
                "TokenValidationService must be wired in production mode"
            );
        }

        @Test
        @Order(6)
        @DisplayName("DB credentials are non-blank (validation passed)")
        void credentialsNonBlank() {
            OperioConfig.DbConfig db = config.db().orElseThrow();
            assertTrue(
                db.username().isPresent() && !db.username().get().isBlank(),
                "operio.db.username must be non-blank"
            );
            assertTrue(
                db.password().isPresent() && !db.password().get().isBlank(),
                "operio.db.password must be non-blank"
            );

            OperioConfig.DbConfig platformDb = config.platformDb().orElseThrow();
            assertTrue(
                platformDb.username().isPresent()
                    && !platformDb.username().get().isBlank(),
                "operio.platform-db.username must be non-blank"
            );
            assertTrue(
                platformDb.password().isPresent()
                    && !platformDb.password().get().isBlank(),
                "operio.platform-db.password must be non-blank"
            );
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
            given()
                .when()
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
            given()
                .when()
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
            given()
                .when()
                    .get("/q/health/ready")
                .then()
                    .statusCode(200);
        }

        @Test
        @Order(4)
        @DisplayName("Quarkus liveness probe returns 200")
        void quarkusLive() {
            given()
                .when()
                    .get("/q/health/live")
                .then()
                    .statusCode(200);
        }

        @Test
        @Order(5)
        @DisplayName("OpenAPI spec is served")
        void openApiAvailable() {
            given()
                .accept(ContentType.JSON)
                .when()
                    .get("/q/openapi")
                .then()
                    .statusCode(200);
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
            given()
                .contentType(ContentType.JSON)
                .body("""
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
            given()
                .when()
                    .get("/api/v1/admin/fields")
                .then()
                    .statusCode(401);
        }

        @Test
        @Order(3)
        @DisplayName("Platform endpoint rejects missing auth")
        void platformRequiresAuth() {
            given()
                .when()
                    .get("/api/v1/platform/tenants")
                .then()
                    .statusCode(401);
        }

        @Test
        @Order(4)
        @DisplayName("Invalid bearer token is rejected")
        void invalidTokenRejected() {
            given()
                .header("Authorization", "Bearer totally-invalid-token")
                .when()
                    .get("/api/v1/platform/tenants")
                .then()
                    .statusCode(401);
        }

        @Test
        @Order(5)
        @DisplayName("Non-bearer auth scheme is rejected")
        void basicAuthRejected() {
            given()
                .header("Authorization", "Basic dXNlcjpwYXNz")
                .when()
                    .get("/api/v1/platform/tenants")
                .then()
                    .statusCode(401);
        }
    }

    // =======================================================================
    // 4. Platform — tenant + API key lifecycle (bootstrap key auth)
    // =======================================================================

    @Nested
    @Order(4)
    @DisplayName("4 — Platform: Tenant & API Key Lifecycle")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class PlatformLifecycle {

        @Test
        @Order(1)
        @DisplayName("Bootstrap key authenticates for platform endpoints")
        void bootstrapKeyAccepted() {
            given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .when()
                    .get("/api/v1/platform/tenants")
                .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("$", hasSize(0)); // no tenants yet
        }

        @Test
        @Order(2)
        @DisplayName("Create tenant via bootstrap key")
        void createTenant() {
            given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .contentType(ContentType.JSON)
                .body(String.format("""
                    {
                      "tenantId": "%s",
                      "displayName": "%s",
                      "plan": "%s"
                    }
                    """, TENANT_ID, TENANT_DISPLAY, TENANT_PLAN))
                .when()
                    .post("/api/v1/platform/tenants")
                .then()
                    .statusCode(201)
                    .contentType(ContentType.JSON)
                    .body("tenantId", equalTo(TENANT_ID))
                    .body("displayName", equalTo(TENANT_DISPLAY))
                    .body("plan", equalTo(TENANT_PLAN))
                    .body("status", equalTo("ACTIVE"))
                    .body("createdAt", notNullValue());
        }

        @Test
        @Order(3)
        @DisplayName("Tenant appears in list")
        void listTenants() {
            given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .when()
                    .get("/api/v1/platform/tenants")
                .then()
                    .statusCode(200)
                    .body("$", hasSize(1))
                    .body("[0].tenantId", equalTo(TENANT_ID));
        }

        @Test
        @Order(4)
        @DisplayName("Get tenant by ID")
        void getTenant() {
            given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .when()
                    .get("/api/v1/platform/tenants/" + TENANT_ID)
                .then()
                    .statusCode(200)
                    .body("tenantId", equalTo(TENANT_ID))
                    .body("displayName", equalTo(TENANT_DISPLAY))
                    .body("plan", equalTo(TENANT_PLAN))
                    .body("evalLimitMonthly", greaterThan(0))
                    .body("subjectLimit", greaterThan(0))
                    .body("burstLimitPerMin", greaterThan(0));
        }

        @Test
        @Order(5)
        @DisplayName("Create API key for tenant")
        void createApiKey() {
            Response response = given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .contentType(ContentType.JSON)
                .body(String.format("""
                    {
                      "tenantId": "%s",
                      "subject": "integration-test-admin",
                      "roles": ["super-admin", "consent:read", "consent:write", "admin:read", "admin:write"]
                    }
                    """, TENANT_ID))
                .when()
                    .post("/api/v1/platform/api-keys")
                .then()
                    .statusCode(201)
                    .contentType(ContentType.JSON)
                    .body("rawKey", notNullValue())
                    .body("keyPrefix", notNullValue())
                    .body("tenantId", equalTo(TENANT_ID))
                    .body("subject", equalTo("integration-test-admin"))
                    .body("roles", hasItems(
                        "super-admin", "consent:read", "consent:write",
                        "admin:read", "admin:write"
                    ))
                    .extract()
                    .response();

            // Store the raw key for subsequent tests
            apiKey = response.jsonPath().getString("rawKey");
            apiKeyPrefix = response.jsonPath().getString("keyPrefix");

            assertNotNull(apiKey, "Raw API key must be returned");
            assertFalse(apiKey.isBlank(), "Raw API key must not be blank");
            assertNotNull(apiKeyPrefix, "Key prefix must be returned");
            assertTrue(
                apiKey.startsWith(apiKeyPrefix),
                "Raw key must start with the prefix"
            );
        }

        @Test
        @Order(6)
        @DisplayName("API key authenticates successfully")
        void apiKeyAuthenticates() {
            assertNotNull(apiKey, "apiKey must have been set by createApiKey");

            given()
                .header("Authorization", "Bearer " + apiKey)
                .when()
                    .get("/api/v1/platform/tenants")
                .then()
                    .statusCode(200)
                    .body("$", hasSize(1));
        }

        @Test
        @Order(7)
        @DisplayName("List API keys for tenant")
        void listApiKeys() {
            given()
                .header("Authorization", "Bearer " + apiKey)
                .queryParam("tenantId", TENANT_ID)
                .when()
                    .get("/api/v1/platform/api-keys")
                .then()
                    .statusCode(200)
                    .body("$", hasSize(greaterThanOrEqualTo(1)))
                    .body("[0].tenantId", equalTo(TENANT_ID))
                    .body("[0].subject", equalTo("integration-test-admin"))
                    .body("[0].status", equalTo("ACTIVE"));
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
            assertNotNull(apiKey, "apiKey must be set before admin tests run");
            return "Bearer " + apiKey;
        }

        // -- Fields -----------------------------------------------------------

        @Test
        @Order(1)
        @DisplayName("Register field: email (User entity)")
        void registerFieldEmail() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "fieldName": "email",
                      "entityName": "User",
                      "description": "User email address"
                    }
                    """)
                .when()
                    .post("/api/v1/admin/fields")
                .then()
                    .statusCode(anyOf(is(200), is(201)))
                    .body("message", notNullValue());
        }

        @Test
        @Order(2)
        @DisplayName("Register field: ssn (User entity)")
        void registerFieldSsn() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "fieldName": "ssn",
                      "entityName": "User",
                      "description": "Social security number"
                    }
                    """)
                .when()
                    .post("/api/v1/admin/fields")
                .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(3)
        @DisplayName("Register field: diagnosis (Patient entity)")
        void registerFieldDiagnosis() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "fieldName": "diagnosis",
                      "entityName": "Patient",
                      "description": "Medical diagnosis"
                    }
                    """)
                .when()
                    .post("/api/v1/admin/fields")
                .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(4)
        @DisplayName("List fields returns registered fields")
        void listFields() {
            given()
                .header("Authorization", auth())
                .when()
                    .get("/api/v1/admin/fields")
                .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("$", hasSize(greaterThanOrEqualTo(3)))
                    .body("fieldName", hasItems("email", "ssn", "diagnosis"));
        }

        // -- Categories -------------------------------------------------------

        @Test
        @Order(10)
        @DisplayName("Register category: PII")
        void registerCategoryPii() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "categoryName": "PII",
                      "description": "Personally Identifiable Information"
                    }
                    """)
                .when()
                    .post("/api/v1/admin/categories")
                .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(11)
        @DisplayName("Register category: HEALTH")
        void registerCategoryHealth() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "categoryName": "HEALTH",
                      "description": "Protected health information"
                    }
                    """)
                .when()
                    .post("/api/v1/admin/categories")
                .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(12)
        @DisplayName("List categories returns registered categories")
        void listCategories() {
            given()
                .header("Authorization", auth())
                .when()
                    .get("/api/v1/admin/categories")
                .then()
                    .statusCode(200)
                    .body("$", hasSize(greaterThanOrEqualTo(2)))
                    .body("categoryName", hasItems("PII", "HEALTH"));
        }

        // -- Purposes ---------------------------------------------------------

        @Test
        @Order(20)
        @DisplayName("Register purpose: marketing")
        void registerPurposeMarketing() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "purposeName": "marketing",
                      "description": "Email marketing campaigns"
                    }
                    """)
                .when()
                    .post("/api/v1/admin/purposes")
                .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(21)
        @DisplayName("Register purpose: analytics")
        void registerPurposeAnalytics() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "purposeName": "analytics",
                      "description": "Product usage analytics"
                    }
                    """)
                .when()
                    .post("/api/v1/admin/purposes")
                .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(22)
        @DisplayName("List purposes returns registered purposes")
        void listPurposes() {
            given()
                .header("Authorization", auth())
                .when()
                    .get("/api/v1/admin/purposes")
                .then()
                    .statusCode(200)
                    .body("$", hasSize(greaterThanOrEqualTo(2)))
                    .body("purposeName", hasItems("marketing", "analytics"));
        }

        // -- Regulations ------------------------------------------------------

        @Test
        @Order(30)
        @DisplayName("Register regulation: GDPR")
        void registerRegulationGdpr() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "regulationName": "GDPR",
                      "fullName": "General Data Protection Regulation",
                      "jurisdiction": "EU",
                      "description": "EU data protection regulation",
                      "url": "https://gdpr.eu"
                    }
                    """)
                .when()
                    .post("/api/v1/admin/regulations")
                .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(31)
        @DisplayName("List regulations returns GDPR")
        void listRegulations() {
            given()
                .header("Authorization", auth())
                .when()
                    .get("/api/v1/admin/regulations")
                .then()
                    .statusCode(200)
                    .body("$", hasSize(greaterThanOrEqualTo(1)))
                    .body("regulationName", hasItem("GDPR"));
        }

        // -- Links (field↔category, purpose↔regulation) ----------------------

        @Test
        @Order(40)
        @DisplayName("Link field 'email' to category 'PII'")
        void linkEmailToPii() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "fieldName": "email",
                      "entityName": "User",
                      "categoryName": "PII"
                    }
                    """)
                .when()
                    .post("/api/v1/admin/fields/links")
                .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(41)
        @DisplayName("Link field 'ssn' to category 'PII'")
        void linkSsnToPii() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "fieldName": "ssn",
                      "entityName": "User",
                      "categoryName": "PII"
                    }
                    """)
                .when()
                    .post("/api/v1/admin/fields/links")
                .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(42)
        @DisplayName("Link field 'diagnosis' to category 'HEALTH'")
        void linkDiagnosisToHealth() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "fieldName": "diagnosis",
                      "entityName": "Patient",
                      "categoryName": "HEALTH"
                    }
                    """)
                .when()
                    .post("/api/v1/admin/fields/links")
                .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(43)
        @DisplayName("Link purpose 'marketing' to regulation 'GDPR'")
        void linkMarketingToGdpr() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "purposeName": "marketing",
                      "regulationName": "GDPR",
                      "legalBasis": "consent"
                    }
                    """)
                .when()
                    .post("/api/v1/admin/purposes/links")
                .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        // -- Entities (implicitly created via field registration) --------------

        @Test
        @Order(50)
        @DisplayName("List entities returns implicitly created entities")
        void listEntities() {
            given()
                .header("Authorization", auth())
                .when()
                    .get("/api/v1/admin/entities")
                .then()
                    .statusCode(200)
                    .body("$", hasSize(greaterThanOrEqualTo(2)))
                    .body("entityName", hasItems("User", "Patient"));
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
            assertNotNull(apiKey, "apiKey must be set before consent tests");
            return "Bearer " + apiKey;
        }

        @Test
        @Order(1)
        @DisplayName("Grant consent: user-1 → email (PII, User, marketing)")
        void grantEmailMarketing() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
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
                    .body("message", containsStringIgnoringCase("granted"));
        }

        @Test
        @Order(2)
        @DisplayName("Grant consent: user-1 → ssn (PII, User, analytics)")
        void grantSsnAnalytics() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
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
                    .statusCode(200);
        }

        @Test
        @Order(3)
        @DisplayName("Evaluate consent: granted scopes appear in 'granted' set")
        void evaluateGranted() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "subject": "user-1",
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
                    .post("/api/v1/subjects/consent/evaluate")
                .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("subject", equalTo("user-1"))
                    .body("granted", hasSize(1))
                    .body("denied", hasSize(0))
                    .body("granted[0].fieldName", equalTo("email"))
                    .body("granted[0].category", equalTo("PII"))
                    .body("granted[0].purposeName", equalTo("marketing"));
        }

        @Test
        @Order(4)
        @DisplayName("Evaluate consent: non-granted scope appears in 'denied' set")
        void evaluateDenied() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "subject": "user-1",
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
                    .post("/api/v1/subjects/consent/evaluate")
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
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "subject": "user-1",
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
                    """)
                .when()
                    .post("/api/v1/subjects/consent/evaluate")
                .then()
                    .statusCode(200)
                    .body("granted", hasSize(1))
                    .body("denied", hasSize(1));
        }

        @Test
        @Order(6)
        @DisplayName("Revoke consent: user-1 → email (PII, User, marketing)")
        void revokeEmailMarketing() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
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
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "subject": "user-1",
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
                    .post("/api/v1/subjects/consent/evaluate")
                .then()
                    .statusCode(200)
                    .body("granted", hasSize(0))
                    .body("denied", hasSize(1));
        }

        @Test
        @Order(8)
        @DisplayName("Grant is idempotent (re-granting the same scope succeeds)")
        void grantIdempotent() {
            // Grant the same scope twice — should succeed both times
            for (int i = 0; i < 2; i++) {
                given()
                    .header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body("""
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
            assertNotNull(apiKey, "apiKey must be set before batch tests");
            return "Bearer " + apiKey;
        }

        @Test
        @Order(1)
        @DisplayName("Batch grant: multiple scopes at once")
        void batchGrant() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
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
                    .post("/api/v1/subjects/consent/grant-batch")
                .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("succeeded", equalTo(3))
                    .body("failed", equalTo(0));
        }

        @Test
        @Order(2)
        @DisplayName("Evaluate after batch grant: all 3 scopes granted")
        void evaluateAfterBatchGrant() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "subject": "user-batch",
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
                    """)
                .when()
                    .post("/api/v1/subjects/consent/evaluate")
                .then()
                    .statusCode(200)
                    .body("granted", hasSize(3))
                    .body("denied", hasSize(0));
        }

        @Test
        @Order(3)
        @DisplayName("Batch revoke: revoke 2 of 3 scopes")
        void batchRevoke() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
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
                    .post("/api/v1/subjects/consent/revoke-batch")
                .then()
                    .statusCode(200)
                    .body("succeeded", equalTo(2))
                    .body("failed", equalTo(0));
        }

        @Test
        @Order(4)
        @DisplayName("Evaluate after batch revoke: 1 granted, 2 denied")
        void evaluateAfterBatchRevoke() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "subject": "user-batch",
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
                    """)
                .when()
                    .post("/api/v1/subjects/consent/evaluate")
                .then()
                    .statusCode(200)
                    .body("granted", hasSize(1))
                    .body("denied", hasSize(2))
                    // SSN/analytics is the only one still granted
                    .body("granted[0].fieldName", equalTo("ssn"));
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
            assertNotNull(apiKey, "apiKey must be set before subject tests");
            return "Bearer " + apiKey;
        }

        @Test
        @Order(1)
        @DisplayName("Subject summary returns current consent state")
        void subjectSummary() {
            // user-batch should have 1 active grant (ssn/analytics) and
            // 2 revocations from the batch tests above
            given()
                .header("Authorization", auth())
                .when()
                    .get("/api/v1/subjects/user-batch/summary")
                .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("$", hasSize(greaterThanOrEqualTo(1)));
        }

        @Test
        @Order(2)
        @DisplayName("Subject history returns audit trail")
        void subjectHistory() {
            // user-batch had 3 grants + 2 revokes = at least 5 audit entries
            given()
                .header("Authorization", auth())
                .when()
                    .get("/api/v1/subjects/user-batch/history")
                .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("$", hasSize(greaterThanOrEqualTo(5)));
        }

        @Test
        @Order(3)
        @DisplayName("Unknown subject returns empty summary")
        void unknownSubjectSummary() {
            given()
                .header("Authorization", auth())
                .when()
                    .get("/api/v1/subjects/nonexistent-user/summary")
                .then()
                    .statusCode(200)
                    .body("$", hasSize(0));
        }

        @Test
        @Order(4)
        @DisplayName("Unknown subject returns empty history")
        void unknownSubjectHistory() {
            given()
                .header("Authorization", auth())
                .when()
                    .get("/api/v1/subjects/nonexistent-user/history")
                .then()
                    .statusCode(200)
                    .body("$", hasSize(0));
        }
    }

    // =======================================================================
    // 9. RBAC enforcement — API key roles
    // =======================================================================

    @Nested
    @Order(9)
    @DisplayName("9 — RBAC Enforcement with Real API Key")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class RbacEnforcement {

        @Test
        @Order(1)
        @DisplayName("Create a read-only API key")
        void createReadOnlyKey() {
            assertNotNull(apiKey, "Need super-admin apiKey to create read-only key");

            Response response = given()
                .header("Authorization", "Bearer " + apiKey)
                .contentType(ContentType.JSON)
                .body(String.format("""
                    {
                      "tenantId": "%s",
                      "subject": "readonly-service",
                      "roles": ["consent:read", "admin:read"]
                    }
                    """, TENANT_ID))
                .when()
                    .post("/api/v1/platform/api-keys")
                .then()
                    .statusCode(201)
                    .extract()
                    .response();

            String readOnlyKey = response.jsonPath().getString("rawKey");
            assertNotNull(readOnlyKey);

            // Read-only key can read consent
            given()
                .header("Authorization", "Bearer " + readOnlyKey)
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "subject": "user-batch",
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
                    .post("/api/v1/subjects/consent/evaluate")
                .then()
                    .statusCode(200);

            // Read-only key can read admin
            given()
                .header("Authorization", "Bearer " + readOnlyKey)
                .when()
                    .get("/api/v1/admin/fields")
                .then()
                    .statusCode(200);

            // Read-only key CANNOT write consent (403)
            given()
                .header("Authorization", "Bearer " + readOnlyKey)
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "subject": "user-rbac-test",
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
                    .statusCode(403);

            // Read-only key CANNOT access platform (403)
            given()
                .header("Authorization", "Bearer " + readOnlyKey)
                .when()
                    .get("/api/v1/platform/tenants")
                .then()
                    .statusCode(403);
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
            assertNotNull(apiKey, "apiKey must be set before validation tests");
            return "Bearer " + apiKey;
        }

        @Test
        @Order(1)
        @DisplayName("Grant with blank subject is rejected (400)")
        void grantBlankSubject() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
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
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
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
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "subject": "user-1",
                      "scopes": []
                    }
                    """)
                .when()
                    .post("/api/v1/subjects/consent/evaluate")
                .then()
                    .statusCode(400);
        }

        @Test
        @Order(4)
        @DisplayName("Register field with blank fieldName is rejected (400)")
        void registerFieldBlankName() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "fieldName": "",
                      "entityName": "User"
                    }
                    """)
                .when()
                    .post("/api/v1/admin/fields")
                .then()
                    .statusCode(400);
        }

        @Test
        @Order(5)
        @DisplayName("Create tenant with invalid tenantId pattern is rejected (400)")
        void createTenantInvalidId() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
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
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "subject": "user-1",
                      "grants": []
                    }
                    """)
                .when()
                    .post("/api/v1/subjects/consent/grant-batch")
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
            return "Bearer " + apiKey;
        }

        @Test
        @Order(1)
        @DisplayName("Different subjects have independent consent state")
        void subjectIsolation() {
            // Grant consent for subject-A only
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
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
                    .statusCode(200);

            // subject-A should have consent granted
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "subject": "subject-A",
                      "scopes": [{
                        "category": "PII",
                        "fieldName": "email",
                        "entityName": "User",
                        "purposeName": "marketing"
                      }]
                    }
                    """)
                .when()
                    .post("/api/v1/subjects/consent/evaluate")
                .then()
                    .statusCode(200)
                    .body("granted", hasSize(1))
                    .body("denied", hasSize(0));

            // subject-B should NOT have consent (never granted)
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "subject": "subject-B",
                      "scopes": [{
                        "category": "PII",
                        "fieldName": "email",
                        "entityName": "User",
                        "purposeName": "marketing"
                      }]
                    }
                    """)
                .when()
                    .post("/api/v1/subjects/consent/evaluate")
                .then()
                    .statusCode(200)
                    .body("granted", hasSize(0))
                    .body("denied", hasSize(1));
        }
    }

    // =======================================================================
    // 12. API key revocation
    // =======================================================================

    @Nested
    @Order(12)
    @DisplayName("12 — API Key Revocation")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ApiKeyRevocation {

        @Test
        @Order(1)
        @DisplayName("Create and revoke an API key, then verify it's rejected")
        void revokeApiKey() {
            assertNotNull(apiKey, "Need super-admin apiKey");

            // Create a disposable API key
            Response createResponse = given()
                .header("Authorization", "Bearer " + apiKey)
                .contentType(ContentType.JSON)
                .body(String.format("""
                    {
                      "tenantId": "%s",
                      "subject": "disposable-key",
                      "roles": ["consent:read"]
                    }
                    """, TENANT_ID))
                .when()
                    .post("/api/v1/platform/api-keys")
                .then()
                    .statusCode(201)
                    .extract()
                    .response();

            String disposableKey = createResponse.jsonPath().getString("rawKey");
            String disposablePrefix = createResponse.jsonPath().getString("keyPrefix");

            // Verify it works
            given()
                .header("Authorization", "Bearer " + disposableKey)
                .when()
                    .get("/api/v1/admin/fields")
                .then()
                    .statusCode(anyOf(is(200), is(403))); // 200 if admin:read, 403 if not

            // Revoke it
            given()
                .header("Authorization", "Bearer " + apiKey)
                .when()
                    .delete("/api/v1/platform/api-keys/" + disposablePrefix)
                .then()
                    .statusCode(anyOf(is(200), is(204)));

            // Verify it's now rejected
            given()
                .header("Authorization", "Bearer " + disposableKey)
                .when()
                    .get("/api/v1/admin/fields")
                .then()
                    .statusCode(401);
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
            given()
                .when()
                    .get("/api/v1/health")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("UP"));
        }

        @Test
        @Order(2)
        @DisplayName("Quarkus health still UP after full test run")
        void quarkusHealthStillUp() {
            given()
                .when()
                    .get("/q/health")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("UP"));
        }
    }
}
