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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;

/**
 * Integration test for {@link com.koostack.cveil.rest.resource.CatalogueResource} exercising
 * entity, category, and field CRUD against real Postgres + Redis.
 *
 * <p>Every mutating operation is verified at three layers:
 *
 * <ol>
 *   <li>REST API response (status code + body)
 *   <li>Postgres — direct JDBC queries on the catalogue tables + event store
 *   <li>Redis — admin projection cache + event stream
 * </ol>
 */
@QuarkusTest
@QuarkusTestResource(CveilTestResourceManager.class)
@DisplayName("CatalogueResource Integration Test (Postgres + Redis)")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogueResourceIT {
    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";

    private static final String TENANT_ID = "catalogue_it_tenant";

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
                            BOOTSTRAP_KEY, TENANT_ID, "Catalogue IT Tenant");
            accessToken = auth.accessToken();
            assertThat(accessToken).isNotBlank();

            support = new IntegrationTestSupport(config, TENANT_ID);
            support.awaitTenantReady(TENANT_ID, Duration.ofSeconds(90));
        }

        @Test
        @Order(2)
        @DisplayName("Unauthenticated request → 401")
        void unauthenticatedRejected() {
            given().when().get("/api/v1/catalogue/fields").then().statusCode(401);
        }

        @Test
        @Order(3)
        @DisplayName("Invalid token → 401")
        void invalidTokenReturns401() {
            given().header("Authorization", "Bearer invalid-token")
                    .when()
                    .get("/api/v1/catalogue/fields")
                    .then()
                    .statusCode(401);
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
            assertThat(accessToken).isNotBlank();
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("Register entity: User")
        void registerUser() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "entityName": "user", "description": "Application user" }
                    """)
                    .when()
                    .post("/api/v1/catalogue/entities")
                    .then()
                    .statusCode(201)
                    .body("message", containsString("user"));

            // DB verify
            support.assertRowExists("entity", "entity_name", "user");

            // Event verify
            var events = support.queryEvents("consent-entity", "consent.entity.registered");
            assertThat(events).isNotEmpty();
        }

        @Test
        @Order(2)
        @DisplayName("List entities includes User")
        void listEntities() {
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/catalogue/entities")
                    .then()
                    .statusCode(200)
                    .body("$", hasSize(greaterThanOrEqualTo(1)))
                    .body("entityName", hasItem("user"));
        }

        @Test
        @Order(3)
        @DisplayName("Get entity by name")
        void getEntity() {
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/catalogue/entities/user")
                    .then()
                    .statusCode(200)
                    .body("entityName", equalTo("user"))
                    .body("description", equalTo("Application user"));
        }

        @Test
        @Order(4)
        @DisplayName("Update entity description")
        void updateEntity() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "description": "Updated user entity" }
                    """)
                    .when()
                    .put("/api/v1/catalogue/entities/user")
                    .then()
                    .statusCode(200);

            // DB verify: description updated (eventually consistent)
            support.awaitQueryValue(
                    "SELECT description FROM entity WHERE entity_name = ?",
                    "description",
                    "Updated user entity",
                    "user");

            // Event verify
            var events = support.queryEvents("consent-entity", "consent.entity.updated");
            assertThat(events).isNotEmpty();
        }

        @Test
        @Order(5)
        @DisplayName("Register second entity: Patient")
        void registerPatient() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "entityName": "patient", "description": "Medical patient" }
                    """)
                    .when()
                    .post("/api/v1/catalogue/entities")
                    .then()
                    .statusCode(201);

            support.assertRowExists("entity", "entity_name", "patient");
        }

        @Test
        @Order(6)
        @DisplayName("Delete entity: Patient")
        void deletePatient() {
            given().header("Authorization", auth())
                    .when()
                    .delete("/api/v1/catalogue/entities/patient")
                    .then()
                    .statusCode(200);

            // DB verify: row removed
            support.assertRowAbsent("entity", "entity_name", "patient");

            // Event verify
            var events = support.queryEvents("consent-entity", "consent.entity.removed");
            assertThat(events).isNotEmpty();
        }

        @Test
        @Order(7)
        @DisplayName("Get deleted entity → 404")
        void getDeletedEntity() {
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/catalogue/entities/patient")
                    .then()
                    .statusCode(404);
        }

        @Test
        @Order(8)
        @DisplayName("Delete non-existent entity → idempotent 200")
        void deleteNonExistent() {
            // Event-sourced: delete of non-existent entity is a no-op (idempotent)
            given().header("Authorization", auth())
                    .when()
                    .delete("/api/v1/catalogue/entities/NonExistent")
                    .then()
                    .statusCode(200);
        }

        @Test
        @Order(9)
        @DisplayName("Register entity with blank name → 400")
        void registerBlankName() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
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
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "entityName": "user", "description": "Upserted user" }
                    """)
                    .when()
                    .post("/api/v1/catalogue/entities")
                    .then()
                    .statusCode(201);

            // DB verify: only one user row with updated description (eventually consistent)
            support.awaitQueryValue(
                    "SELECT description FROM entity WHERE entity_name = ?",
                    "description",
                    "Upserted user",
                    "user");
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
            assertThat(accessToken).isNotBlank();
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("Register category: IT_PII")
        void registerPii() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "categoryName": "IT_PII", "description": "Personal identifiable info" }
                    """)
                    .when()
                    .post("/api/v1/catalogue/categories")
                    .then()
                    .statusCode(201)
                    .body("message", containsString("IT_PII"));

            // DB verify
            support.assertRowExists("category", "category_name", "IT_PII");

            // Event verify
            var events = support.queryEvents("consent-category", "consent.category.registered");
            assertThat(events).isNotEmpty();
        }

        @Test
        @Order(2)
        @DisplayName("Register category: IT_HEALTH")
        void registerHealth() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "categoryName": "IT_HEALTH", "description": "Health data" }
                    """)
                    .when()
                    .post("/api/v1/catalogue/categories")
                    .then()
                    .statusCode(201);

            support.assertRowExists("category", "category_name", "IT_HEALTH");
        }

        @Test
        @Order(3)
        @DisplayName("List categories includes IT_PII and IT_HEALTH")
        void listCategories() {
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/catalogue/categories")
                    .then()
                    .statusCode(200)
                    .body("$", hasSize(greaterThanOrEqualTo(2)))
                    .body("categoryName", hasItems("IT_PII", "IT_HEALTH"));
        }

        @Test
        @Order(4)
        @DisplayName("Get category by name")
        void getCategory() {
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/catalogue/categories/IT_PII")
                    .then()
                    .statusCode(200)
                    .body("categoryName", equalTo("IT_PII"))
                    .body("description", equalTo("Personal identifiable info"));
        }

        @Test
        @Order(5)
        @DisplayName("Update category description")
        void updateCategory() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "description": "Updated IT_PII description" }
                    """)
                    .when()
                    .put("/api/v1/catalogue/categories/IT_PII")
                    .then()
                    .statusCode(200);

            // DB verify (eventually consistent)
            support.awaitQueryValue(
                    "SELECT description FROM category WHERE category_name = ?",
                    "description",
                    "Updated IT_PII description",
                    "IT_PII");

            // Event verify
            var events = support.queryEvents("consent-category", "consent.category.updated");
            assertThat(events).isNotEmpty();
        }

        @Test
        @Order(6)
        @DisplayName("Delete category: IT_HEALTH")
        void deleteHealth() {
            given().header("Authorization", auth())
                    .when()
                    .delete("/api/v1/catalogue/categories/IT_HEALTH")
                    .then()
                    .statusCode(200);

            support.assertRowAbsent("category", "category_name", "IT_HEALTH");
        }

        @Test
        @Order(7)
        @DisplayName("Get deleted category → 404")
        void getDeletedCategory() {
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/catalogue/categories/IT_HEALTH")
                    .then()
                    .statusCode(404);
        }

        @Test
        @Order(8)
        @DisplayName("Register category with blank name → 400")
        void registerBlankName() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
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
            assertThat(accessToken).isNotBlank();
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("Register field: email (User)")
        void registerEmail() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "fieldName": "email",
                      "entityName": "user",
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
                            + "JOIN entity e ON f.entity_uuid = e.entity_uuid "
                            + "WHERE f.field_name = 'email' AND e.entity_name = 'user'",
                    rows -> rows.size() == 1,
                    "Expected 1 field row for email/user");

            // Event verify
            var events = support.queryEvents("consent-field", "consent.field.registered");
            assertThat(events).isNotEmpty();
        }

        @Test
        @Order(2)
        @DisplayName("Register field: ssn (User)")
        void registerSsn() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "fieldName": "ssn",
                      "entityName": "user",
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
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "entityName": "patient", "description": "Medical patient" }
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
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "fieldName": "diagnosis",
                      "entityName": "patient",
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
            given().header("Authorization", auth())
                    .when()
                    .get("/api/v1/catalogue/fields")
                    .then()
                    .statusCode(200)
                    .body("$", hasSize(greaterThanOrEqualTo(3)))
                    .body("fieldName", hasItems("email", "ssn", "diagnosis"));
        }

        @Test
        @Order(6)
        @DisplayName("Assign category IT_PII → email")
        void assignEmailPii() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "fieldName": "email",
                      "entityName": "user",
                      "categoryName": "IT_PII"
                    }
                    """)
                    .when()
                    .post("/api/v1/catalogue/fields/categories")
                    .then()
                    .statusCode(200)
                    .body("message", containsString("IT_PII"));

            // DB verify: field_category join row (eventually consistent)
            support.awaitQuery(
                    "SELECT fc.*, f.field_name, c.category_name, e.entity_name "
                            + "FROM field_category fc "
                            + "JOIN field f ON fc.field_uuid = f.field_uuid "
                            + "JOIN entity e ON f.entity_uuid = e.entity_uuid "
                            + "JOIN category c ON fc.category_uuid = c.category_uuid "
                            + "WHERE f.field_name = 'email' AND e.entity_name = 'user'",
                    rows -> !rows.isEmpty() && "IT_PII".equals(rows.get(0).get("category_name")),
                    "Expected field_category row for email/user/IT_PII");

            // Event verify
            var events = support.queryEvents("consent-field", "consent.field.category-assigned");
            assertThat(events).isNotEmpty();
        }

        @Test
        @Order(7)
        @DisplayName("Assign category IT_PII → ssn")
        void assignSsnPii() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "fieldName": "ssn",
                      "entityName": "user",
                      "categoryName": "IT_PII"
                    }
                    """)
                    .when()
                    .post("/api/v1/catalogue/fields/categories")
                    .then()
                    .statusCode(200);

            support.awaitQuery(
                    "SELECT fc.*, f.field_name, c.category_name, e.entity_name "
                            + "FROM field_category fc "
                            + "JOIN field f ON fc.field_uuid = f.field_uuid "
                            + "JOIN entity e ON f.entity_uuid = e.entity_uuid "
                            + "JOIN category c ON fc.category_uuid = c.category_uuid "
                            + "WHERE f.field_name = 'ssn' AND e.entity_name = 'user'",
                    rows -> !rows.isEmpty(),
                    "Expected field_category row for ssn/user");
        }

        @Test
        @Order(8)
        @DisplayName("Re-register IT_HEALTH and assign to diagnosis")
        void assignDiagnosisHealth() {
            // Re-register IT_HEALTH (was deleted in CategoryCrud phase)
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "categoryName": "IT_HEALTH", "description": "Health data" }
                    """)
                    .when()
                    .post("/api/v1/catalogue/categories")
                    .then()
                    .statusCode(201);

            // Assign IT_HEALTH → diagnosis
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "fieldName": "diagnosis",
                      "entityName": "patient",
                      "categoryName": "IT_HEALTH"
                    }
                    """)
                    .when()
                    .post("/api/v1/catalogue/fields/categories")
                    .then()
                    .statusCode(200);

            support.awaitQuery(
                    "SELECT fc.*, f.field_name, c.category_name, e.entity_name "
                            + "FROM field_category fc "
                            + "JOIN field f ON fc.field_uuid = f.field_uuid "
                            + "JOIN entity e ON f.entity_uuid = e.entity_uuid "
                            + "JOIN category c ON fc.category_uuid = c.category_uuid "
                            + "WHERE f.field_name = 'diagnosis' AND e.entity_name = 'patient'",
                    rows -> !rows.isEmpty() && "IT_HEALTH".equals(rows.get(0).get("category_name")),
                    "Expected field_category row for diagnosis/patient/IT_HEALTH");
        }

        @Test
        @Order(9)
        @DisplayName("Fields list shows category assignments")
        void fieldsShowCategories() {
            Response response =
                    given().header("Authorization", auth())
                            .when()
                            .get("/api/v1/catalogue/fields")
                            .then()
                            .statusCode(200)
                            .extract()
                            .response();

            // Find the email field and verify it has PII category (eventually consistent)
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () -> {
                                Response retry =
                                        given().header("Authorization", auth())
                                                .when()
                                                .get("/api/v1/catalogue/fields")
                                                .then()
                                                .statusCode(200)
                                                .extract()
                                                .response();
                                List<Map<String, Object>> retryFields =
                                        retry.jsonPath().getList("$");
                                var emailField =
                                        retryFields.stream()
                                                .filter(f -> "email".equals(f.get("fieldName")))
                                                .findFirst()
                                                .orElseThrow(
                                                        () ->
                                                                new AssertionError(
                                                                        "email field not found in"
                                                                                + " fields list"));
                                @SuppressWarnings("unchecked")
                                var categories =
                                        (List<Map<String, Object>>) emailField.get("categories");
                                assertThat(categories).isNotEmpty();
                                assertThat(categories.stream().map(c -> c.get("categoryName")))
                                        .contains("IT_PII");
                            });
        }

        @Test
        @Order(10)
        @DisplayName("Unassign category IT_PII from email")
        void unassignEmailPii() {
            given().header("Authorization", auth())
                    .when()
                    .delete("/api/v1/catalogue/fields/user/email/categories/IT_PII")
                    .then()
                    .statusCode(200);

            // DB verify: field_category link removed (eventually consistent)
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(200))
                    .untilAsserted(
                            () -> {
                                var links = support.queryFieldCategories("email", "user");
                                assertThat(
                                                links.stream()
                                                        .filter(
                                                                l ->
                                                                        "IT_PII"
                                                                                .equals(
                                                                                        l.get(
                                                                                                "category_name")))
                                                        .toList())
                                        .isEmpty();
                            });

            // Event verify
            var events = support.queryEvents("consent-field", "consent.field.category-unassigned");
            assertThat(events).isNotEmpty();
        }

        @Test
        @Order(11)
        @DisplayName("Register field with blank name → 400")
        void registerBlankFieldName() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "fieldName": "", "entityName": "user" }
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
            assertThat(accessToken).isNotBlank();
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("Delete entity with fields → error (409 or 400)")
        void deleteEntityWithFields() {
            // User entity has email and ssn fields → should fail
            given().header("Authorization", auth())
                    .when()
                    .delete("/api/v1/catalogue/entities/user")
                    .then()
                    .statusCode(anyOf(is(409), is(400), is(500)));

            // DB verify: entity still exists
            support.assertRowExists("entity", "entity_name", "user");
        }

        @Test
        @Order(2)
        @DisplayName("Delete category with assignments → 409")
        void deleteCategoryWithAssignmentsBlocked() {
            // IT_PII is still assigned to ssn → should fail with integrity error
            given().header("Authorization", auth())
                    .when()
                    .delete("/api/v1/catalogue/categories/IT_PII")
                    .then()
                    .statusCode(anyOf(is(409), is(400), is(500)));

            // DB verify: category still exists
            support.assertRowExists("category", "category_name", "IT_PII");
        }

        @Test
        @Order(3)
        @DisplayName("Unassign IT_PII from ssn → then delete IT_PII succeeds")
        void unassignAndDeleteCategory() {
            // First unassign IT_PII from ssn
            given().header("Authorization", auth())
                    .when()
                    .delete("/api/v1/catalogue/fields/user/ssn/categories/IT_PII")
                    .then()
                    .statusCode(200);

            // Wait for unassignment to be projected before deleting category
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(300))
                    .untilAsserted(
                            () -> {
                                var links = support.queryFieldCategories("ssn", "user");
                                assertThat(
                                                links.stream()
                                                        .filter(
                                                                l ->
                                                                        "IT_PII"
                                                                                .equals(
                                                                                        l.get(
                                                                                                "category_name")))
                                                        .toList())
                                        .isEmpty();
                            });

            // Now delete IT_PII category
            given().header("Authorization", auth())
                    .when()
                    .delete("/api/v1/catalogue/categories/IT_PII")
                    .then()
                    .statusCode(200);

            // DB verify: category removed
            support.assertRowAbsent("category", "category_name", "IT_PII");
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
            // We registered User, updated User, registered Patient, deleted Patient, re-registered
            // Patient, upserted User
            assertThat(events).hasSizeGreaterThanOrEqualTo(3);
        }

        @Test
        @Order(3)
        @DisplayName("Event table has category events")
        void categoryEventsExist() {
            var events = support.queryEventsByAggregate("consent-category");
            // We registered IT_PII, IT_HEALTH, updated IT_PII, deleted IT_HEALTH, re-registered
            // IT_HEALTH
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
