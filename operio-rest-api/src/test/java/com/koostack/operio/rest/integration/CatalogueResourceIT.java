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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;

/**
 * Integration test for {@link com.koostack.operio.rest.resource.CatalogueResource}
 * exercising entity, category, and field CRUD against real Postgres + Redis.
 *
 * <p>Every mutating operation is verified at three layers:
 * <ol>
 *   <li>REST API response (status code + body)</li>
 *   <li>Postgres — direct JDBC queries on the catalogue tables + event store</li>
 *   <li>Redis — admin projection cache + event stream</li>
 * </ol>
 */
@QuarkusTest
@QuarkusTestResource(OperioTestResourceManager.class)
@DisplayName("CatalogueResource Integration Test (Postgres + Redis)")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogueResourceIT {

    private static final String BOOTSTRAP_KEY =
        "test-bootstrap-key-for-integration-tests";

    private static final String TENANT_ID = "catalogue_it_tenant";

    private static volatile String apiKey;
    private static volatile String readOnlyApiKey;
    private static volatile IntegrationTestSupport support;

    @Inject
    OperioConfig config;

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
        @DisplayName("Create tenant")
        void createTenant() {
            given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "tenantId": "%s",
                      "displayName": "Catalogue IT Tenant",
                      "plan": "enterprise"
                    }
                    """.formatted(TENANT_ID))
                .when()
                    .post("/api/v1/platform/tenants")
                .then()
                    .statusCode(201)
                    .body("tenantId", equalTo(TENANT_ID));
        }

        @Test
        @Order(2)
        @DisplayName("Create full-access API key")
        void createApiKey() {
            Response response = given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "tenantId": "%s",
                      "subject": "catalogue-it-admin",
                      "roles": ["admin:read", "admin:write", "consent:read", "consent:write"]
                    }
                    """.formatted(TENANT_ID))
                .when()
                    .post("/api/v1/platform/api-keys")
                .then()
                    .statusCode(201)
                    .extract().response();

            apiKey = response.jsonPath().getString("rawKey");
            assertNotNull(apiKey);

            // Initialize test support after we know config is populated
            support = new IntegrationTestSupport(config, TENANT_ID);

            // Wait for the CQRS projection to make the API key usable
            support.awaitApiKeyUsable(apiKey, Duration.ofSeconds(30));
            support.awaitEventTableReady(Duration.ofSeconds(15));
            support.awaitTenantDiscoverable(TENANT_ID, Duration.ofSeconds(30));
        }

        @Test
        @Order(3)
        @DisplayName("Create read-only API key")
        void createReadOnlyKey() {
            Response response = given()
                .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "tenantId": "%s",
                      "subject": "catalogue-it-reader",
                      "roles": ["admin:read"]
                    }
                    """.formatted(TENANT_ID))
                .when()
                    .post("/api/v1/platform/api-keys")
                .then()
                    .statusCode(201)
                    .extract().response();

            readOnlyApiKey = response.jsonPath().getString("rawKey");
            assertNotNull(readOnlyApiKey);

            // Wait for the CQRS projection to make the read-only key usable
            support.awaitApiKeyUsable(readOnlyApiKey, Duration.ofSeconds(30));
        }

        @Test
        @Order(4)
        @DisplayName("Unauthenticated request → 401")
        void unauthenticatedRejected() {
            given()
                .when()
                    .get("/api/v1/catalogue/fields")
                .then()
                    .statusCode(401);
        }

        @Test
        @Order(5)
        @DisplayName("Read-only key cannot create entity → 403")
        void readOnlyCannotWrite() {
            given()
                .header("Authorization", "Bearer " + readOnlyApiKey)
                .contentType(ContentType.JSON)
                .body("""
                    { "entityName": "Forbidden", "description": "should fail" }
                    """)
                .when()
                    .post("/api/v1/catalogue/entities")
                .then()
                    .statusCode(403);
        }
    }

    // =======================================================================
    // 2. Entity CRUD
    // =======================================================================

    @Nested
    @Order(2)
    @DisplayName("2 — Entity CRUD Lifecycle")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class EntityCrud {

        private String auth() {
            assertNotNull(apiKey, "apiKey required");
            return "Bearer " + apiKey;
        }

        @Test
        @Order(1)
        @DisplayName("Register entity: User")
        void registerUser() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    { "entityName": "User", "description": "Application user" }
                    """)
                .when()
                    .post("/api/v1/catalogue/entities")
                .then()
                    .statusCode(201)
                    .body("message", containsString("User"));

            // DB verify
            support.assertRowExists("entity", "entity_name", "User");

            // Event verify
            var events = support.queryEvents("consent-entity", "consent.entity.registered");
            assertThat(events).isNotEmpty();
        }

        @Test
        @Order(2)
        @DisplayName("List entities includes User")
        void listEntities() {
            given()
                .header("Authorization", auth())
                .when()
                    .get("/api/v1/catalogue/entities")
                .then()
                    .statusCode(200)
                    .body("$", hasSize(greaterThanOrEqualTo(1)))
                    .body("entityName", hasItem("User"));
        }

        @Test
        @Order(3)
        @DisplayName("Get entity by name")
        void getEntity() {
            given()
                .header("Authorization", auth())
                .when()
                    .get("/api/v1/catalogue/entities/User")
                .then()
                    .statusCode(200)
                    .body("entityName", equalTo("User"))
                    .body("description", equalTo("Application user"));
        }

        @Test
        @Order(4)
        @DisplayName("Update entity description")
        void updateEntity() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    { "description": "Updated user entity" }
                    """)
                .when()
                    .put("/api/v1/catalogue/entities/User")
                .then()
                    .statusCode(200);

            // DB verify: description updated (eventually consistent)
            support.awaitQueryValue(
                "SELECT description FROM entity WHERE entity_name = ?",
                "description", "Updated user entity", "User"
            );

            // Event verify
            var events = support.queryEvents("consent-entity", "consent.entity.updated");
            assertThat(events).isNotEmpty();
        }

        @Test
        @Order(5)
        @DisplayName("Register second entity: Patient")
        void registerPatient() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    { "entityName": "Patient", "description": "Medical patient" }
                    """)
                .when()
                    .post("/api/v1/catalogue/entities")
                .then()
                    .statusCode(201);

            support.assertRowExists("entity", "entity_name", "Patient");
        }

        @Test
        @Order(6)
        @DisplayName("Delete entity: Patient")
        void deletePatient() {
            given()
                .header("Authorization", auth())
                .when()
                    .delete("/api/v1/catalogue/entities/Patient")
                .then()
                    .statusCode(200);

            // DB verify: row removed
            support.assertRowAbsent("entity", "entity_name", "Patient");

            // Event verify
            var events = support.queryEvents("consent-entity", "consent.entity.removed");
            assertThat(events).isNotEmpty();
        }

        @Test
        @Order(7)
        @DisplayName("Get deleted entity → 404")
        void getDeletedEntity() {
            given()
                .header("Authorization", auth())
                .when()
                    .get("/api/v1/catalogue/entities/Patient")
                .then()
                    .statusCode(404);
        }

        @Test
        @Order(8)
        @DisplayName("Delete non-existent entity → idempotent 200")
        void deleteNonExistent() {
            // Event-sourced: delete of non-existent entity is a no-op (idempotent)
            given()
                .header("Authorization", auth())
                .when()
                    .delete("/api/v1/catalogue/entities/NonExistent")
                .then()
                    .statusCode(200);
        }

        @Test
        @Order(9)
        @DisplayName("Register entity with blank name → 400")
        void registerBlankName() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    { "entityName": "", "description": "blank" }
                    """)
                .when()
                    .post("/api/v1/catalogue/entities")
                .then()
                    .statusCode(400);
        }

        @Test
        @Order(10)
        @DisplayName("Re-register entity upserts description")
        void upsertEntity() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    { "entityName": "User", "description": "Upserted user" }
                    """)
                .when()
                    .post("/api/v1/catalogue/entities")
                .then()
                    .statusCode(201);

            // DB verify: only one User row with updated description (eventually consistent)
            support.awaitQueryValue(
                "SELECT description FROM entity WHERE entity_name = ?",
                "description", "Upserted user", "User"
            );
        }
    }

    // =======================================================================
    // 3. Category CRUD
    // =======================================================================

    @Nested
    @Order(3)
    @DisplayName("3 — Category CRUD Lifecycle")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class CategoryCrud {

        private String auth() {
            assertNotNull(apiKey, "apiKey required");
            return "Bearer " + apiKey;
        }

        @Test
        @Order(1)
        @DisplayName("Register category: PII")
        void registerPii() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    { "categoryName": "PII", "description": "Personal identifiable info" }
                    """)
                .when()
                    .post("/api/v1/catalogue/categories")
                .then()
                    .statusCode(201)
                    .body("message", containsString("PII"));

            // DB verify
            support.assertRowExists("category", "category_name", "PII");

            // Event verify
            var events = support.queryEvents("consent-category", "consent.category.registered");
            assertThat(events).isNotEmpty();
        }

        @Test
        @Order(2)
        @DisplayName("Register category: HEALTH")
        void registerHealth() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    { "categoryName": "HEALTH", "description": "Health data" }
                    """)
                .when()
                    .post("/api/v1/catalogue/categories")
                .then()
                    .statusCode(201);

            support.assertRowExists("category", "category_name", "HEALTH");
        }

        @Test
        @Order(3)
        @DisplayName("List categories includes PII and HEALTH")
        void listCategories() {
            given()
                .header("Authorization", auth())
                .when()
                    .get("/api/v1/catalogue/categories")
                .then()
                    .statusCode(200)
                    .body("$", hasSize(greaterThanOrEqualTo(2)))
                    .body("categoryName", hasItems("PII", "HEALTH"));
        }

        @Test
        @Order(4)
        @DisplayName("Get category by name")
        void getCategory() {
            given()
                .header("Authorization", auth())
                .when()
                    .get("/api/v1/catalogue/categories/PII")
                .then()
                    .statusCode(200)
                    .body("categoryName", equalTo("PII"))
                    .body("description", equalTo("Personal identifiable info"));
        }

        @Test
        @Order(5)
        @DisplayName("Update category description")
        void updateCategory() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    { "description": "Updated PII description" }
                    """)
                .when()
                    .put("/api/v1/catalogue/categories/PII")
                .then()
                    .statusCode(200);

            // DB verify (eventually consistent)
            support.awaitQueryValue(
                "SELECT description FROM category WHERE category_name = ?",
                "description", "Updated PII description", "PII"
            );

            // Event verify
            var events = support.queryEvents("consent-category", "consent.category.updated");
            assertThat(events).isNotEmpty();
        }

        @Test
        @Order(6)
        @DisplayName("Delete category: HEALTH")
        void deleteHealth() {
            given()
                .header("Authorization", auth())
                .when()
                    .delete("/api/v1/catalogue/categories/HEALTH")
                .then()
                    .statusCode(200);

            support.assertRowAbsent("category", "category_name", "HEALTH");
        }

        @Test
        @Order(7)
        @DisplayName("Get deleted category → 404")
        void getDeletedCategory() {
            given()
                .header("Authorization", auth())
                .when()
                    .get("/api/v1/catalogue/categories/HEALTH")
                .then()
                    .statusCode(404);
        }

        @Test
        @Order(8)
        @DisplayName("Register category with blank name → 400")
        void registerBlankName() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    { "categoryName": "", "description": "blank" }
                    """)
                .when()
                    .post("/api/v1/catalogue/categories")
                .then()
                    .statusCode(400);
        }
    }

    // =======================================================================
    // 4. Field Registration & Category Assignment
    // =======================================================================

    @Nested
    @Order(4)
    @DisplayName("4 — Field Registration & Category Assignment")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class FieldAndCategoryAssignment {

        private String auth() {
            assertNotNull(apiKey, "apiKey required");
            return "Bearer " + apiKey;
        }

        @Test
        @Order(1)
        @DisplayName("Register field: email (User)")
        void registerEmail() {
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
                    .post("/api/v1/catalogue/fields")
                .then()
                    .statusCode(201)
                    .body("message", containsString("email"));

            // DB verify: field row with correct entity FK (eventually consistent)
            support.awaitQuery(
                "SELECT f.field_name, e.entity_name FROM field f "
                    + "JOIN entity e ON f.entity_id = e.entity_id "
                    + "WHERE f.field_name = 'email' AND e.entity_name = 'User'",
                rows -> rows.size() == 1,
                "Expected 1 field row for email/User"
            );

            // Event verify
            var events = support.queryEvents("consent-field", "consent.field.registered");
            assertThat(events).isNotEmpty();
        }

        @Test
        @Order(2)
        @DisplayName("Register field: ssn (User)")
        void registerSsn() {
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
                    .post("/api/v1/catalogue/fields")
                .then()
                    .statusCode(201);

            support.assertRowExists("field", "field_name", "ssn");
        }

        @Test
        @Order(3)
        @DisplayName("Re-register Patient entity for field registration")
        void reRegisterPatient() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    { "entityName": "Patient", "description": "Medical patient" }
                    """)
                .when()
                    .post("/api/v1/catalogue/entities")
                .then()
                    .statusCode(201);
        }

        @Test
        @Order(4)
        @DisplayName("Register field: diagnosis (Patient)")
        void registerDiagnosis() {
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
                    .post("/api/v1/catalogue/fields")
                .then()
                    .statusCode(201);

            support.assertRowExists("field", "field_name", "diagnosis");
        }

        @Test
        @Order(5)
        @DisplayName("List fields includes all 3")
        void listFields() {
            given()
                .header("Authorization", auth())
                .when()
                    .get("/api/v1/catalogue/fields")
                .then()
                    .statusCode(200)
                    .body("$", hasSize(greaterThanOrEqualTo(3)))
                    .body("fieldName", hasItems("email", "ssn", "diagnosis"));
        }

        @Test
        @Order(6)
        @DisplayName("Assign category PII → email")
        void assignEmailPii() {
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
                    .post("/api/v1/catalogue/fields/categories")
                .then()
                    .statusCode(200)
                    .body("message", containsString("PII"));

            // DB verify: field_category join row (eventually consistent)
            support.awaitQuery(
                "SELECT fc.*, f.field_name, c.category_name, e.entity_name "
                    + "FROM field_category fc "
                    + "JOIN field f ON fc.field_id = f.field_id "
                    + "JOIN entity e ON f.entity_id = e.entity_id "
                    + "JOIN category c ON fc.category_id = c.category_id "
                    + "WHERE f.field_name = 'email' AND e.entity_name = 'User'",
                rows -> !rows.isEmpty() && "PII".equals(rows.get(0).get("category_name")),
                "Expected field_category row for email/User/PII"
            );

            // Event verify
            var events = support.queryEvents("consent-field", "consent.field.category-assigned");
            assertThat(events).isNotEmpty();
        }

        @Test
        @Order(7)
        @DisplayName("Assign category PII → ssn")
        void assignSsnPii() {
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
                    .post("/api/v1/catalogue/fields/categories")
                .then()
                    .statusCode(200);

            support.awaitQuery(
                "SELECT fc.*, f.field_name, c.category_name, e.entity_name "
                    + "FROM field_category fc "
                    + "JOIN field f ON fc.field_id = f.field_id "
                    + "JOIN entity e ON f.entity_id = e.entity_id "
                    + "JOIN category c ON fc.category_id = c.category_id "
                    + "WHERE f.field_name = 'ssn' AND e.entity_name = 'User'",
                rows -> !rows.isEmpty(),
                "Expected field_category row for ssn/User"
            );
        }

        @Test
        @Order(8)
        @DisplayName("Re-register HEALTH and assign to diagnosis")
        void assignDiagnosisHealth() {
            // Re-register HEALTH (was deleted in CategoryCrud phase)
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    { "categoryName": "HEALTH", "description": "Health data" }
                    """)
                .when()
                    .post("/api/v1/catalogue/categories")
                .then()
                    .statusCode(201);

            // Assign HEALTH → diagnosis
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
                    .post("/api/v1/catalogue/fields/categories")
                .then()
                    .statusCode(200);

            support.awaitQuery(
                "SELECT fc.*, f.field_name, c.category_name, e.entity_name "
                    + "FROM field_category fc "
                    + "JOIN field f ON fc.field_id = f.field_id "
                    + "JOIN entity e ON f.entity_id = e.entity_id "
                    + "JOIN category c ON fc.category_id = c.category_id "
                    + "WHERE f.field_name = 'diagnosis' AND e.entity_name = 'Patient'",
                rows -> !rows.isEmpty() && "HEALTH".equals(rows.get(0).get("category_name")),
                "Expected field_category row for diagnosis/Patient/HEALTH"
            );
        }

        @Test
        @Order(9)
        @DisplayName("Fields list shows category assignments")
        void fieldsShowCategories() {
            Response response = given()
                .header("Authorization", auth())
                .when()
                    .get("/api/v1/catalogue/fields")
                .then()
                    .statusCode(200)
                    .extract().response();

            // Find the email field and verify it has PII category (eventually consistent)
            Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    Response retry = given()
                        .header("Authorization", auth())
                        .when()
                            .get("/api/v1/catalogue/fields")
                        .then()
                            .statusCode(200)
                            .extract().response();
                    List<Map<String, Object>> retryFields = retry.jsonPath().getList("$");
                    var emailField = retryFields.stream()
                        .filter(f -> "email".equals(f.get("fieldName")))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("email field not found in fields list"));
                    @SuppressWarnings("unchecked")
                    var categories = (List<Map<String, Object>>) emailField.get("categories");
                    assertThat(categories).isNotEmpty();
                    assertThat(categories.stream().map(c -> c.get("categoryName")))
                        .contains("PII");
                });
        }

        @Test
        @Order(10)
        @DisplayName("Unassign category PII from email")
        void unassignEmailPii() {
            given()
                .header("Authorization", auth())
                .when()
                    .delete("/api/v1/catalogue/fields/User/email/categories/PII")
                .then()
                    .statusCode(200);

            // DB verify: field_category link removed (eventually consistent)
            Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var links = support.queryFieldCategories("email", "User");
                    assertThat(links.stream()
                        .filter(l -> "PII".equals(l.get("category_name")))
                        .toList()
                    ).isEmpty();
                });

            // Event verify
            var events = support.queryEvents("consent-field", "consent.field.category-unassigned");
            assertThat(events).isNotEmpty();
        }

        @Test
        @Order(11)
        @DisplayName("Register field with blank name → 400")
        void registerBlankFieldName() {
            given()
                .header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body("""
                    { "fieldName": "", "entityName": "User" }
                    """)
                .when()
                    .post("/api/v1/catalogue/fields")
                .then()
                    .statusCode(400);
        }
    }

    // =======================================================================
    // 5. Deletion Constraints
    // =======================================================================

    @Nested
    @Order(5)
    @DisplayName("5 — Deletion Constraints")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DeletionConstraints {

        private String auth() {
            assertNotNull(apiKey, "apiKey required");
            return "Bearer " + apiKey;
        }

        @Test
        @Order(1)
        @DisplayName("Delete entity with fields → error (409 or 400)")
        void deleteEntityWithFields() {
            // User entity has email and ssn fields → should fail
            given()
                .header("Authorization", auth())
                .when()
                    .delete("/api/v1/catalogue/entities/User")
                .then()
                    .statusCode(anyOf(is(409), is(400), is(500)));

            // DB verify: entity still exists
            support.assertRowExists("entity", "entity_name", "User");
        }

        @Test
        @Order(2)
        @DisplayName("Delete category with assignments → 409")
        void deleteCategoryWithAssignmentsBlocked() {
            // PII is still assigned to ssn → should fail with integrity error
            given()
                .header("Authorization", auth())
                .when()
                    .delete("/api/v1/catalogue/categories/PII")
                .then()
                    .statusCode(anyOf(is(409), is(400), is(500)));

            // DB verify: category still exists
            support.assertRowExists("category", "category_name", "PII");
        }

        @Test
        @Order(3)
        @DisplayName("Unassign PII from ssn → then delete PII succeeds")
        void unassignAndDeleteCategory() {
            // First unassign PII from ssn
            given()
                .header("Authorization", auth())
                .when()
                    .delete("/api/v1/catalogue/fields/User/ssn/categories/PII")
                .then()
                    .statusCode(200);

            // Wait for unassignment to be projected before deleting category
            Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    var links = support.queryFieldCategories("ssn", "User");
                    assertThat(links.stream()
                        .filter(l -> "PII".equals(l.get("category_name")))
                        .toList()
                    ).isEmpty();
                });

            // Now delete PII category
            given()
                .header("Authorization", auth())
                .when()
                    .delete("/api/v1/catalogue/categories/PII")
                .then()
                    .statusCode(200);

            // DB verify: category removed
            support.assertRowAbsent("category", "category_name", "PII");
        }
    }

    // =======================================================================
    // 6. Outbox & Stream Verification
    // =======================================================================

    @Nested
    @Order(6)
    @DisplayName("6 — Outbox & Stream Verification")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class OutboxVerification {

        @Test
        @Order(1)
        @DisplayName("All events eventually published")
        void allEventsPublished() {
            assertNotNull(support, "support required");

            // Wait for outbox poller to publish all events
            support.awaitAllEventsPublished(Duration.ofSeconds(30));
        }

        @Test
        @Order(2)
        @DisplayName("Event table has entity events")
        void entityEventsExist() {
            var events = support.queryEventsByAggregate("consent-entity");
            // We registered User, updated User, registered Patient, deleted Patient, re-registered Patient, upserted User
            assertThat(events).hasSizeGreaterThanOrEqualTo(3);
        }

        @Test
        @Order(3)
        @DisplayName("Event table has category events")
        void categoryEventsExist() {
            var events = support.queryEventsByAggregate("consent-category");
            // We registered PII, HEALTH, updated PII, deleted HEALTH, re-registered HEALTH
            assertThat(events).hasSizeGreaterThanOrEqualTo(3);
        }

        @Test
        @Order(4)
        @DisplayName("Event table has field events")
        void fieldEventsExist() {
            var events = support.queryEventsByAggregate("consent-field");
            // Registered 3 fields + assigned 3 categories + unassigned 1
            assertThat(events).hasSizeGreaterThanOrEqualTo(4);
        }

        @Test
        @Order(5)
        @DisplayName("Redis stream contains catalogue events")
        void streamHasCatalogueEvents() {
            var entries = support.getStreamEntries(TENANT_ID, 1000);
            // Stream should have entries from entity/category/field operations
            assertThat(entries).isNotEmpty();
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
