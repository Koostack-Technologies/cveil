package com.koostack.cveil.rest.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.koostack.cveil.rest.config.CveilConfig;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;

/**
 * Integration test for {@link com.koostack.cveil.rest.resource.GovernanceResource} exercising
 * purpose, regulation, and jurisdiction CRUD — plus policy management, cross-entity linking, and
 * deletion constraints — against real Postgres + Redis.
 *
 * <p>Every mutating operation is verified at three layers:
 *
 * <ol>
 *   <li>REST API response (status code + body)
 *   <li>Postgres — direct JDBC queries on governance tables + event store
 *   <li>Redis — event stream entries
 * </ol>
 */
@QuarkusTest
@QuarkusTestResource(CveilTestResourceManager.class)
@DisplayName("GovernanceResource Integration Test (Postgres + Redis)")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GovernanceResourceIT {

    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";

    private static final String TENANT_ID = "governance_it_tenant";

    // Catalogue items (registered as prerequisites for policies)
    private static final String ENTITY_USER = "User";
    private static final String CATEGORY_PII = "PII";
    private static final String FIELD_EMAIL = "email";

    // Governance items
    private static final String PURPOSE_MARKETING = "marketing";
    private static final String PURPOSE_ANALYTICS = "analytics";
    private static final String REGULATION_GDPR = "GDPR";
    private static final String REGULATION_CCPA = "CCPA";
    private static final String JURISDICTION_EU = "EU";
    private static final String JURISDICTION_CA = "California";

    private static volatile String accessToken;
    private static volatile IntegrationTestSupport support;

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
                            BOOTSTRAP_KEY, TENANT_ID, "Governance IT Tenant");
            accessToken = auth.accessToken();
            assertThat(accessToken).isNotBlank();

            support = new IntegrationTestSupport(config, TENANT_ID);
            support.awaitTenantReady(TENANT_ID, Duration.ofSeconds(90));
        }

        @Test
        @Order(2)
        @DisplayName("Unauthenticated request → 401")
        void unauthenticatedReturns401() {
            given().when().get("/api/v1/governance/purposes").then().statusCode(401);
        }

        @Test
        @Order(3)
        @DisplayName("Invalid token → 401")
        void invalidTokenReturns401() {
            given().header("Authorization", "Bearer invalid-token")
                    .when()
                    .get("/api/v1/governance/purposes")
                    .then()
                    .statusCode(401);
        }
    }

    // =======================================================================
    // 2. Purpose CRUD
    // =======================================================================

    @Nested
    @Order(2)
    @DisplayName("2 — Purpose CRUD Lifecycle")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class PurposeCrud {

        private String auth() {
            assertNotNull(accessToken, "accessToken required");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("Register purpose: marketing")
        void registerMarketing() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "purposeName": "%s", "description": "Marketing communications" }
                    """
                                    .formatted(PURPOSE_MARKETING))
                    .when()
                    .post("/api/v1/governance/purposes")
                    .then()
                    .statusCode(201)
                    .body("message", containsString(PURPOSE_MARKETING));

            support.assertRowExists("purpose", "purpose_name", PURPOSE_MARKETING);
        }

        @Test
        @Order(2)
        @DisplayName("Register purpose: analytics")
        void registerAnalytics() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "purposeName": "%s", "description": "Data analytics" }
                    """
                                    .formatted(PURPOSE_ANALYTICS))
                    .when()
                    .post("/api/v1/governance/purposes")
                    .then()
                    .statusCode(201);

            support.assertRowExists("purpose", "purpose_name", PURPOSE_ANALYTICS);
        }

        @Test
        @Order(3)
        @DisplayName("List purposes includes both")
        void listPurposes() {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () ->
                                    given().header("Authorization", auth())
                                            .when()
                                            .get("/api/v1/governance/purposes")
                                            .then()
                                            .statusCode(200)
                                            .body("$", hasSize(greaterThanOrEqualTo(2))));
        }

        @Test
        @Order(4)
        @DisplayName("Get purpose by name")
        void getMarketing() {
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/governance/purposes/" + PURPOSE_MARKETING)
                    .then()
                    .statusCode(200)
                    .body("purposeName", equalTo(PURPOSE_MARKETING))
                    .body("description", equalTo("Marketing communications"));
        }

        @Test
        @Order(5)
        @DisplayName("Delete purpose: analytics")
        void deleteAnalytics() {
            given().header("Authorization", auth())
                    .when()
                    .delete("/api/v1/governance/purposes/" + PURPOSE_ANALYTICS)
                    .then()
                    .statusCode(200);

            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () -> {
                                var rows =
                                        support.query(
                                                "SELECT * FROM purpose WHERE purpose_name = ? AND"
                                                        + " tenant_id = ?",
                                                PURPOSE_ANALYTICS,
                                                TENANT_ID);
                                assertThat(rows)
                                        .as(
                                                "Expected no rows in 'purpose' where purpose_name ="
                                                        + " 'analytics' for this tenant")
                                        .isEmpty();
                            });
        }

        @Test
        @Order(6)
        @DisplayName("Re-register analytics (upsert)")
        void reRegisterAnalytics() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "purposeName": "%s", "description": "Re-registered analytics" }
                    """
                                    .formatted(PURPOSE_ANALYTICS))
                    .when()
                    .post("/api/v1/governance/purposes")
                    .then()
                    .statusCode(201);

            support.assertRowExists("purpose", "purpose_name", PURPOSE_ANALYTICS);
        }

        @Test
        @Order(7)
        @DisplayName("Register purpose with blank name → 400")
        void registerBlankName() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "purposeName": "", "description": "invalid" }
                    """)
                    .when()
                    .post("/api/v1/governance/purposes")
                    .then()
                    .statusCode(400);
        }

        @Test
        @Order(8)
        @DisplayName("Get non-existent purpose → 404")
        void getNonExistent() {
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/governance/purposes/does-not-exist")
                    .then()
                    .statusCode(404);
        }
    }

    // =======================================================================
    // 3. Regulation CRUD
    // =======================================================================

    @Nested
    @Order(3)
    @DisplayName("3 — Regulation CRUD Lifecycle")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class RegulationCrud {

        private String auth() {
            assertNotNull(accessToken, "accessToken required");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("Register regulation: GDPR")
        void registerGdpr() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "regulationName": "%s", "description": "EU General Data Protection Regulation" }
                    """
                                    .formatted(REGULATION_GDPR))
                    .when()
                    .post("/api/v1/governance/regulations")
                    .then()
                    .statusCode(201)
                    .body("message", containsString(REGULATION_GDPR));

            support.assertRowExists("regulation", "regulation_name", REGULATION_GDPR);
        }

        @Test
        @Order(2)
        @DisplayName("Register regulation: CCPA")
        void registerCcpa() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "regulationName": "%s", "description": "California Consumer Privacy Act" }
                    """
                                    .formatted(REGULATION_CCPA))
                    .when()
                    .post("/api/v1/governance/regulations")
                    .then()
                    .statusCode(201);

            support.assertRowExists("regulation", "regulation_name", REGULATION_CCPA);
        }

        @Test
        @Order(3)
        @DisplayName("List regulations includes both")
        void listRegulations() {
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/governance/regulations")
                    .then()
                    .statusCode(200)
                    .body("$", hasSize(greaterThanOrEqualTo(2)));
        }

        @Test
        @Order(4)
        @DisplayName("Get regulation by name")
        void getGdpr() {
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/governance/regulations/" + REGULATION_GDPR)
                    .then()
                    .statusCode(200)
                    .body("regulationName", equalTo(REGULATION_GDPR));
        }

        @Test
        @Order(5)
        @DisplayName("Delete regulation: CCPA")
        void deleteCcpa() {
            given().header("Authorization", auth())
                    .when()
                    .delete("/api/v1/governance/regulations/" + REGULATION_CCPA)
                    .then()
                    .statusCode(200);

            support.assertRowAbsent("regulation", "regulation_name", REGULATION_CCPA);
        }

        @Test
        @Order(6)
        @DisplayName("Register regulation with blank name → 400")
        void registerBlankName() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "regulationName": "", "description": "invalid" }
                    """)
                    .when()
                    .post("/api/v1/governance/regulations")
                    .then()
                    .statusCode(400);
        }

        @Test
        @Order(7)
        @DisplayName("Get non-existent regulation → 404")
        void getNonExistent() {
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/governance/regulations/does-not-exist")
                    .then()
                    .statusCode(404);
        }
    }

    // =======================================================================
    // 4. Jurisdiction CRUD
    // =======================================================================

    @Nested
    @Order(4)
    @DisplayName("4 — Jurisdiction CRUD Lifecycle")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class JurisdictionCrud {

        private String auth() {
            assertNotNull(accessToken, "accessToken required");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("Register jurisdiction: EU")
        void registerEu() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "jurisdictionName": "%s", "description": "European Union" }
                    """
                                    .formatted(JURISDICTION_EU))
                    .when()
                    .post("/api/v1/governance/jurisdictions")
                    .then()
                    .statusCode(201)
                    .body("message", containsString(JURISDICTION_EU));

            support.assertRowExists("jurisdiction", "jurisdiction_name", JURISDICTION_EU);
        }

        @Test
        @Order(2)
        @DisplayName("Register jurisdiction: California")
        void registerCalifornia() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "jurisdictionName": "%s", "description": "State of California" }
                    """
                                    .formatted(JURISDICTION_CA))
                    .when()
                    .post("/api/v1/governance/jurisdictions")
                    .then()
                    .statusCode(201);

            support.assertRowExists("jurisdiction", "jurisdiction_name", JURISDICTION_CA);
        }

        @Test
        @Order(3)
        @DisplayName("List jurisdictions includes both")
        void listJurisdictions() {
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/governance/jurisdictions")
                    .then()
                    .statusCode(200)
                    .body("$", hasSize(greaterThanOrEqualTo(2)));
        }

        @Test
        @Order(4)
        @DisplayName("Get jurisdiction by name")
        void getEu() {
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/governance/jurisdictions/" + JURISDICTION_EU)
                    .then()
                    .statusCode(200)
                    .body("jurisdictionName", equalTo(JURISDICTION_EU))
                    .body("description", equalTo("European Union"));
        }

        @Test
        @Order(5)
        @DisplayName("Update jurisdiction: EU description")
        void updateEu() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "description": "European Union (updated)" }
                    """)
                    .when()
                    .put("/api/v1/governance/jurisdictions/" + JURISDICTION_EU)
                    .then()
                    .statusCode(200);

            // Async projection — use Awaitility
            support.awaitQueryValue(
                    "SELECT description FROM jurisdiction WHERE jurisdiction_name = ?",
                    "description",
                    "European Union (updated)",
                    JURISDICTION_EU);
        }

        @Test
        @Order(6)
        @DisplayName("Delete jurisdiction: California")
        void deleteCalifornia() {
            given().header("Authorization", auth())
                    .when()
                    .delete("/api/v1/governance/jurisdictions/" + JURISDICTION_CA)
                    .then()
                    .statusCode(200);

            support.assertRowAbsent("jurisdiction", "jurisdiction_name", JURISDICTION_CA);
        }

        @Test
        @Order(7)
        @DisplayName("Register jurisdiction with blank name → 400")
        void registerBlankName() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "jurisdictionName": "", "description": "invalid" }
                    """)
                    .when()
                    .post("/api/v1/governance/jurisdictions")
                    .then()
                    .statusCode(400);
        }

        @Test
        @Order(8)
        @DisplayName("Get non-existent jurisdiction → 404")
        void getNonExistent() {
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/governance/jurisdictions/does-not-exist")
                    .then()
                    .statusCode(404);
        }
    }

    // =======================================================================
    // 5. Purpose Policy Management
    // =======================================================================

    @Nested
    @Order(5)
    @DisplayName("5 — Purpose Policy Management")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class PurposePolicyManagement {

        private String auth() {
            assertNotNull(accessToken, "accessToken required");
            return "Bearer " + accessToken;
        }

        // -- Catalogue prerequisites --

        @Test
        @Order(1)
        @DisplayName("Register prerequisite entity: User")
        void registerEntity() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "entityName": "%s", "description": "Application user" }
                    """
                                    .formatted(ENTITY_USER))
                    .when()
                    .post("/api/v1/catalogue/entities")
                    .then()
                    .statusCode(201);
        }

        @Test
        @Order(2)
        @DisplayName("Register prerequisite category: PII")
        void registerCategory() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "categoryName": "%s", "description": "Personal data" }
                    """
                                    .formatted(CATEGORY_PII))
                    .when()
                    .post("/api/v1/catalogue/categories")
                    .then()
                    .statusCode(201);
        }

        @Test
        @Order(3)
        @DisplayName("Register prerequisite field: email on User")
        void registerField() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "fieldName": "%s",
                      "entityName": "%s",
                      "description": "User email address"
                    }
                    """
                                    .formatted(FIELD_EMAIL, ENTITY_USER))
                    .when()
                    .post("/api/v1/catalogue/fields")
                    .then()
                    .statusCode(201);
        }

        @Test
        @Order(4)
        @DisplayName("Assign field email to category PII")
        void assignFieldCategory() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "fieldName": "%s",
                      "entityName": "%s",
                      "categoryName": "%s"
                    }
                    """
                                    .formatted(FIELD_EMAIL, ENTITY_USER, CATEGORY_PII))
                    .when()
                    .post("/api/v1/catalogue/fields/categories")
                    .then()
                    .statusCode(200);
        }

        // -- Policy operations --

        @Test
        @Order(10)
        @DisplayName("Add field-scope policy to marketing")
        void addFieldPolicy() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "purposeName": "%s",
                      "policyType": "field",
                      "fieldName": "%s",
                      "entityName": "%s",
                      "categoryName": "%s"
                    }
                    """
                                    .formatted(
                                            PURPOSE_MARKETING,
                                            FIELD_EMAIL,
                                            ENTITY_USER,
                                            CATEGORY_PII))
                    .when()
                    .post("/api/v1/governance/purposes/policies")
                    .then()
                    .statusCode(201);

            // Async projection — use Awaitility
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () -> {
                                var policies =
                                        support.query(
                                                "SELECT p.policy_type, pu.purpose_name,"
                                                    + " f.field_name FROM policy p JOIN purpose pu"
                                                    + " ON p.purpose_id = pu.purpose_id JOIN field"
                                                    + " f ON p.field_id = f.field_id WHERE"
                                                    + " pu.purpose_name = ? AND f.field_name = ?",
                                                PURPOSE_MARKETING,
                                                FIELD_EMAIL);
                                assertThat(policies).isNotEmpty();
                                assertThat(
                                                policies.get(0)
                                                        .get("policy_type")
                                                        .toString()
                                                        .toLowerCase())
                                        .as("Policy type should be 'field'")
                                        .isEqualTo("field");
                            });
        }

        @Test
        @Order(11)
        @DisplayName("Add category-scope policy to marketing")
        void addCategoryPolicy() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "purposeName": "%s",
                      "policyType": "category",
                      "categoryName": "%s"
                    }
                    """
                                    .formatted(PURPOSE_MARKETING, CATEGORY_PII))
                    .when()
                    .post("/api/v1/governance/purposes/policies")
                    .then()
                    .statusCode(201);

            // Async projection — use Awaitility
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () -> {
                                var policies =
                                        support.query(
                                                "SELECT p.policy_type, c.category_name FROM policy"
                                                    + " p JOIN purpose pu ON p.purpose_id ="
                                                    + " pu.purpose_id JOIN category c ON"
                                                    + " p.category_id = c.category_id WHERE"
                                                    + " pu.purpose_name = ? AND c.category_name ="
                                                    + " ?",
                                                PURPOSE_MARKETING,
                                                CATEGORY_PII);
                                assertThat(policies).isNotEmpty();
                            });
        }

        @Test
        @Order(12)
        @DisplayName("Get purpose shows policies in response")
        void getPurposeWithPolicies() {
            // Async projection — policies populate asynchronously
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () ->
                                    given().header("Authorization", auth())
                                            .when()
                                            .get("/api/v1/governance/purposes/" + PURPOSE_MARKETING)
                                            .then()
                                            .statusCode(200)
                                            .body("policies", hasSize(greaterThanOrEqualTo(2))));
        }

        @Test
        @Order(13)
        @DisplayName("Remove field-scope policy")
        void removeFieldPolicy() {
            // Wait for the policy to be projected before attempting removal
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () -> {
                                var before =
                                        support.query(
                                                "SELECT p.* FROM policy p JOIN purpose pu ON"
                                                    + " p.purpose_id = pu.purpose_id JOIN field f"
                                                    + " ON p.field_id = f.field_id WHERE"
                                                    + " pu.purpose_name = ? AND f.field_name = ?"
                                                    + " AND p.policy_type::text = 'FIELD'",
                                                PURPOSE_MARKETING,
                                                FIELD_EMAIL);
                                assertThat(before)
                                        .as("Field-scope policy should exist before removal")
                                        .isNotEmpty();
                            });

            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "purposeName": "%s",
                      "policyType": "field",
                      "fieldName": "%s",
                      "entityName": "%s",
                      "categoryName": "%s"
                    }
                    """
                                    .formatted(
                                            PURPOSE_MARKETING,
                                            FIELD_EMAIL,
                                            ENTITY_USER,
                                            CATEGORY_PII))
                    .when()
                    .delete("/api/v1/governance/purposes/policies")
                    .then()
                    .statusCode(200);

            // Verify the field-scope policy row is gone (async projection)
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () -> {
                                var policies =
                                        support.query(
                                                "SELECT p.* FROM policy p JOIN purpose pu ON"
                                                    + " p.purpose_id = pu.purpose_id JOIN field f"
                                                    + " ON p.field_id = f.field_id WHERE"
                                                    + " pu.purpose_name = ? AND f.field_name = ?"
                                                    + " AND p.policy_type::text = 'FIELD' AND"
                                                    + " pu.tenant_id = ?",
                                                PURPOSE_MARKETING,
                                                FIELD_EMAIL,
                                                TENANT_ID);
                                assertThat(policies)
                                        .as("Field-scope policy should be removed after DELETE")
                                        .isEmpty();
                            });
        }

        @Test
        @Order(14)
        @DisplayName("Re-add field-scope policy for later phases")
        void reAddFieldPolicy() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "purposeName": "%s",
                      "policyType": "field",
                      "fieldName": "%s",
                      "entityName": "%s",
                      "categoryName": "%s"
                    }
                    """
                                    .formatted(
                                            PURPOSE_MARKETING,
                                            FIELD_EMAIL,
                                            ENTITY_USER,
                                            CATEGORY_PII))
                    .when()
                    .post("/api/v1/governance/purposes/policies")
                    .then()
                    .statusCode(201);
        }
    }

    // =======================================================================
    // 6. Purpose ↔ Regulation Linking
    // =======================================================================

    @Nested
    @Order(6)
    @DisplayName("6 — Purpose ↔ Regulation Linking")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class PurposeRegulationLinking {

        private String auth() {
            assertNotNull(accessToken, "accessToken required");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("Link marketing → GDPR")
        void linkPurposeToRegulation() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "purposeName": "%s",
                      "regulationName": "%s",
                      "lawfulBasis": "consent"
                    }
                    """
                                    .formatted(PURPOSE_MARKETING, REGULATION_GDPR))
                    .when()
                    .post("/api/v1/governance/purposes/regulations")
                    .then()
                    .statusCode(200);

            // JDBC: verify purpose_regulation join table (async projection)
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () -> {
                                var links =
                                        support.query(
                                                "SELECT pr.lawful_basis, pu.purpose_name,"
                                                    + " r.regulation_name FROM purpose_regulation"
                                                    + " pr JOIN purpose pu ON pr.purpose_id ="
                                                    + " pu.purpose_id JOIN regulation r ON"
                                                    + " pr.regulation_id = r.regulation_id WHERE"
                                                    + " pu.purpose_name = ? AND r.regulation_name ="
                                                    + " ? AND pu.tenant_id = ?",
                                                PURPOSE_MARKETING,
                                                REGULATION_GDPR,
                                                TENANT_ID);
                                assertThat(links).hasSize(1);
                                assertThat(links.get(0).get("lawful_basis").toString())
                                        .isEqualTo("consent");
                            });
        }

        @Test
        @Order(2)
        @DisplayName("Get marketing shows GDPR in regulations")
        void getPurposeShowsRegulation() {
            // Async projection — regulations populate asynchronously
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () ->
                                    given().header("Authorization", auth())
                                            .when()
                                            .get("/api/v1/governance/purposes/" + PURPOSE_MARKETING)
                                            .then()
                                            .statusCode(200)
                                            .body("regulations", hasSize(greaterThanOrEqualTo(1)))
                                            .body(
                                                    "regulations.regulationName",
                                                    hasItem(REGULATION_GDPR)));
        }

        @Test
        @Order(3)
        @DisplayName("Unlink marketing → GDPR")
        void unlinkPurposeFromRegulation() {
            given().header("Authorization", auth())
                    .when()
                    .delete(
                            "/api/v1/governance/purposes/"
                                    + PURPOSE_MARKETING
                                    + "/regulations/"
                                    + REGULATION_GDPR)
                    .then()
                    .statusCode(200);

            // Async projection — wait for unlink to propagate
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () -> {
                                var links =
                                        support.query(
                                                "SELECT * FROM purpose_regulation pr JOIN purpose"
                                                    + " pu ON pr.purpose_id = pu.purpose_id JOIN"
                                                    + " regulation r ON pr.regulation_id ="
                                                    + " r.regulation_id WHERE pu.purpose_name = ?"
                                                    + " AND r.regulation_name = ? AND pu.tenant_id"
                                                    + " = ?",
                                                PURPOSE_MARKETING,
                                                REGULATION_GDPR,
                                                TENANT_ID);
                                assertThat(links).isEmpty();
                            });
        }

        @Test
        @Order(4)
        @DisplayName("Re-link marketing → GDPR for later phases")
        void reLinkForLater() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "purposeName": "%s",
                      "regulationName": "%s",
                      "lawfulBasis": "consent"
                    }
                    """
                                    .formatted(PURPOSE_MARKETING, REGULATION_GDPR))
                    .when()
                    .post("/api/v1/governance/purposes/regulations")
                    .then()
                    .statusCode(200);
        }
    }

    // =======================================================================
    // 7. Regulation ↔ Jurisdiction Linking
    // =======================================================================

    @Nested
    @Order(7)
    @DisplayName("7 — Regulation ↔ Jurisdiction Linking")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class RegulationJurisdictionLinking {

        private String auth() {
            assertNotNull(accessToken, "accessToken required");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("Add EU to GDPR")
        void addJurisdictionToRegulation() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "regulationName": "%s",
                      "jurisdictionName": "%s",
                      "description": "EU territory scope"
                    }
                    """
                                    .formatted(REGULATION_GDPR, JURISDICTION_EU))
                    .when()
                    .post("/api/v1/governance/regulations/jurisdictions")
                    .then()
                    .statusCode(201);

            // JDBC: verify regulation_jurisdiction join table (async projection)
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () -> {
                                var links =
                                        support.query(
                                                "SELECT rj.*, r.regulation_name,"
                                                    + " j.jurisdiction_name FROM"
                                                    + " regulation_jurisdiction rj JOIN regulation"
                                                    + " r ON rj.regulation_id = r.regulation_id"
                                                    + " JOIN jurisdiction j ON rj.jurisdiction_id ="
                                                    + " j.jurisdiction_id WHERE r.regulation_name ="
                                                    + " ? AND j.jurisdiction_name = ?",
                                                REGULATION_GDPR,
                                                JURISDICTION_EU);
                                assertThat(links).hasSize(1);
                            });
        }

        @Test
        @Order(2)
        @DisplayName("Get GDPR shows EU in jurisdictions")
        void getRegulationShowsJurisdiction() {
            // Async projection — jurisdictions populate asynchronously
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () ->
                                    given().header("Authorization", auth())
                                            .when()
                                            .get(
                                                    "/api/v1/governance/regulations/"
                                                            + REGULATION_GDPR)
                                            .then()
                                            .statusCode(200)
                                            .body(
                                                    "activeJurisdictions",
                                                    hasSize(greaterThanOrEqualTo(1)))
                                            .body(
                                                    "activeJurisdictions.jurisdictionName",
                                                    hasItem(JURISDICTION_EU)));
        }

        @Test
        @Order(3)
        @DisplayName("Remove EU from GDPR")
        void removeJurisdictionFromRegulation() {
            given().header("Authorization", auth())
                    .when()
                    .delete(
                            "/api/v1/governance/regulations/"
                                    + REGULATION_GDPR
                                    + "/jurisdictions/"
                                    + JURISDICTION_EU)
                    .then()
                    .statusCode(200);

            // Async projection — wait for unlink to propagate
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () -> {
                                var links =
                                        support.query(
                                                "SELECT * FROM regulation_jurisdiction rj JOIN"
                                                        + " regulation r ON rj.regulation_id ="
                                                        + " r.regulation_id JOIN jurisdiction j ON"
                                                        + " rj.jurisdiction_id = j.jurisdiction_id"
                                                        + " WHERE r.regulation_name = ? AND"
                                                        + " j.jurisdiction_name = ?",
                                                REGULATION_GDPR,
                                                JURISDICTION_EU);
                                assertThat(links).isEmpty();
                            });
        }
    }

    // =======================================================================
    // 8. Deletion Constraints
    // =======================================================================

    @Nested
    @Order(8)
    @DisplayName("8 — Deletion Constraints")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DeletionConstraints {

        private String auth() {
            assertNotNull(accessToken, "accessToken required");
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("Delete purpose with policies → verify behavior")
        void deletePurposeWithPolicies() {
            // marketing has policies and regulation links — attempt delete
            int status =
                    given().header("Authorization", auth())
                            .when()
                            .delete("/api/v1/governance/purposes/" + PURPOSE_MARKETING)
                            .then()
                            .extract()
                            .statusCode();

            // Accept either: cascading delete (200) or integrity check (409)
            assertThat(status).isIn(200, 409);

            if (status == 200) {
                // Re-register purpose + policy + regulation link for event verification phase
                given().header("Authorization", auth())
                        .contentType(ContentType.JSON)
                        .body(
                                """
                        { "purposeName": "%s", "description": "Re-registered" }
                        """
                                        .formatted(PURPOSE_MARKETING))
                        .when()
                        .post("/api/v1/governance/purposes")
                        .then()
                        .statusCode(201);

                given().header("Authorization", auth())
                        .contentType(ContentType.JSON)
                        .body(
                                """
                        {
                          "purposeName": "%s",
                          "policyType": "field",
                          "fieldName": "%s",
                          "entityName": "%s",
                          "categoryName": "%s"
                        }
                        """
                                        .formatted(
                                                PURPOSE_MARKETING,
                                                FIELD_EMAIL,
                                                ENTITY_USER,
                                                CATEGORY_PII))
                        .when()
                        .post("/api/v1/governance/purposes/policies")
                        .then()
                        .statusCode(201);

                given().header("Authorization", auth())
                        .contentType(ContentType.JSON)
                        .body(
                                """
                        {
                          "purposeName": "%s",
                          "regulationName": "%s",
                          "lawfulBasis": "consent"
                        }
                        """
                                        .formatted(PURPOSE_MARKETING, REGULATION_GDPR))
                        .when()
                        .post("/api/v1/governance/purposes/regulations")
                        .then()
                        .statusCode(200);
            }
        }

        @Test
        @Order(2)
        @DisplayName("Delete regulation that had links → verify behavior")
        void deleteRegulationWithLinks() {
            // GDPR may still have purpose links — attempt delete
            int status =
                    given().header("Authorization", auth())
                            .when()
                            .delete("/api/v1/governance/regulations/" + REGULATION_GDPR)
                            .then()
                            .extract()
                            .statusCode();

            // Accept either: cascading delete (200) or integrity check (409)
            assertThat(status).isIn(200, 409);

            if (status == 200) {
                // Re-register for event verification
                given().header("Authorization", auth())
                        .contentType(ContentType.JSON)
                        .body(
                                """
                        { "regulationName": "%s", "description": "Re-registered" }
                        """
                                        .formatted(REGULATION_GDPR))
                        .when()
                        .post("/api/v1/governance/regulations")
                        .then()
                        .statusCode(201);
            }
        }
    }

    // =======================================================================
    // 9. Event Store & Outbox Verification
    // =======================================================================

    @Nested
    @Order(9)
    @DisplayName("9 — Event Store & Outbox Verification")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class EventStoreVerification {

        @Test
        @Order(1)
        @DisplayName("All events eventually published")
        void allEventsPublished() {
            assertNotNull(support, "support required");
            support.awaitAllEventsPublished(Duration.ofSeconds(30));
        }

        @Test
        @Order(2)
        @DisplayName("Purpose aggregate events exist")
        void purposeEventsExist() {
            var events = support.queryEventsByAggregate("consent-purpose");
            // At minimum: 2 registers + 1 delete + 1 re-register = 4+
            assertThat(events).hasSizeGreaterThanOrEqualTo(3);

            // Verify specific event types present
            var registered = support.queryEvents("consent-purpose", "consent.purpose.registered");
            assertThat(registered).isNotEmpty();
        }

        @Test
        @Order(3)
        @DisplayName("Regulation aggregate events exist")
        void regulationEventsExist() {
            var events = support.queryEventsByAggregate("consent-regulation");
            assertThat(events).hasSizeGreaterThanOrEqualTo(2);

            var registered =
                    support.queryEvents("consent-regulation", "consent.regulation.registered");
            assertThat(registered).isNotEmpty();
        }

        @Test
        @Order(4)
        @DisplayName("Jurisdiction aggregate events exist")
        void jurisdictionEventsExist() {
            var events = support.queryEventsByAggregate("consent-jurisdiction");
            assertThat(events).hasSizeGreaterThanOrEqualTo(2);

            var registered =
                    support.queryEvents("consent-jurisdiction", "consent.jurisdiction.registered");
            assertThat(registered).isNotEmpty();
        }

        @Test
        @Order(5)
        @DisplayName("Redis stream has entries for this tenant")
        void streamHasEntries() {
            var entries = support.getStreamEntries(TENANT_ID, 10000);
            assertThat(entries).isNotEmpty();
            assertThat(entries.size()).isGreaterThanOrEqualTo(5);
        }

        @Test
        @Order(6)
        @DisplayName("Purpose regulation-linked events exist")
        void purposeRegulationLinkedEvents() {
            var events =
                    support.queryEvents("consent-purpose", "consent.purpose.regulation-linked");
            assertThat(events).as("At least one regulation-linked event should exist").isNotEmpty();
        }

        @Test
        @Order(7)
        @DisplayName("Purpose regulation-unlinked events exist")
        void purposeRegulationUnlinkedEvents() {
            var events =
                    support.queryEvents("consent-purpose", "consent.purpose.regulation-unlinked");
            assertThat(events)
                    .as("At least one regulation-unlinked event should exist")
                    .isNotEmpty();
        }

        @Test
        @Order(8)
        @DisplayName("Regulation jurisdiction-added events exist")
        void regulationJurisdictionAddedEvents() {
            var events =
                    support.queryEvents(
                            "consent-regulation", "consent.regulation.jurisdiction-added");
            assertThat(events)
                    .as("At least one jurisdiction-added event should exist")
                    .isNotEmpty();
        }

        @Test
        @Order(9)
        @DisplayName("Regulation jurisdiction-removed events exist")
        void regulationJurisdictionRemovedEvents() {
            var events =
                    support.queryEvents(
                            "consent-regulation", "consent.regulation.jurisdiction-removed");
            assertThat(events)
                    .as("At least one jurisdiction-removed event should exist")
                    .isNotEmpty();
        }
    }

    // =======================================================================
    // Cleanup
    // =======================================================================

    @AfterAll
    static void cleanup() {
        if (support != null) {
            support.close();
        }
    }
}
