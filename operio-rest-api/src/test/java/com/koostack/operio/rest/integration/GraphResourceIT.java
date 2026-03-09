package com.koostack.operio.rest.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

import com.koostack.operio.rest.config.OperioConfig;
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
 * Integration test for {@code GET /api/v1/consent/graph} (GraphResource).
 *
 * <p>Registers catalogue + governance items and grants consent, then verifies the graph query API
 * returns correct nodes, edges, pagination, and supports BFS traversal filtering.
 */
@QuarkusTest
@QuarkusTestResource(OperioTestResourceManager.class)
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class GraphResourceIT {

    private static final String TENANT_ID = "graph_query_it_tenant";
    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";

    private static final String ENTITY_USER = "GqUser";
    private static final String CATEGORY_PII = "GqPII";
    private static final String FIELD_EMAIL = "gq_email";
    private static final String PURPOSE_MARKETING = "gq_marketing";
    private static final String REGULATION_GDPR = "GqGDPR";
    private static final String SUBJECT_ID = "gq-user-1";

    @Inject OperioConfig config;

    private static volatile String apiKey;
    private static volatile String readOnlyKey;
    private static volatile IntegrationTestSupport support;

    // -------------------------------------------------------------------
    // Phase 1 — Setup
    // -------------------------------------------------------------------

    @Nested
    @Order(1)
    @DisplayName("Phase 1 — Setup & Auth")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Setup {

        private String auth() {
            return "Bearer " + apiKey;
        }

        @Test
        @Order(1)
        @DisplayName("Create tenant")
        void createTenant() {
            Response response =
                    given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                            .contentType(ContentType.JSON)
                            .body(
                                    """
                    {
                      "tenantId": "%s",
                      "displayName": "Graph Query IT Tenant",
                      "plan": "pro"
                    }
                    """
                                            .formatted(TENANT_ID))
                            .when()
                            .post("/api/v1/platform/tenants");
            assertThat(response.statusCode())
                    .as("Tenant creation should succeed")
                    .isIn(201, 200, 409);
        }

        @Test
        @Order(2)
        @DisplayName("Create admin API key")
        void createApiKey() {
            Response response =
                    given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                            .contentType(ContentType.JSON)
                            .body(
                                    """
                    {
                      "tenantId": "%s",
                      "keyName": "graph-query-it-key",
                      "environment": "TEST",
                      "roles": ["admin:read", "admin:write", "consent:read", "consent:write"]
                    }
                    """
                                            .formatted(TENANT_ID))
                            .when()
                            .post("/api/v1/platform/api-keys");
            assertThat(response.statusCode()).isEqualTo(201);
            apiKey = response.jsonPath().getString("rawKey");
            assertThat(apiKey).isNotBlank();
        }

        @Test
        @Order(3)
        @DisplayName("Create read-only API key (consent:read only)")
        void createReadOnlyKey() {
            Response response =
                    given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                            .contentType(ContentType.JSON)
                            .body(
                                    """
                    {
                      "tenantId": "%s",
                      "keyName": "graph-query-it-readonly",
                      "environment": "TEST",
                      "roles": ["consent:read"]
                    }
                    """
                                            .formatted(TENANT_ID))
                            .when()
                            .post("/api/v1/platform/api-keys");
            assertThat(response.statusCode()).isEqualTo(201);
            readOnlyKey = response.jsonPath().getString("rawKey");
            assertThat(readOnlyKey).isNotBlank();
        }

        @Test
        @Order(4)
        @DisplayName("Init support")
        void initSupport() {
            support = new IntegrationTestSupport(config, TENANT_ID);
            support.awaitTenantReady(TENANT_ID, Duration.ofSeconds(90));
            assertThat(support).isNotNull();
        }
    }

    // -------------------------------------------------------------------
    // Phase 2 — Data Setup via REST
    // -------------------------------------------------------------------

    @Nested
    @Order(2)
    @DisplayName("Phase 2 — Catalogue + Governance + Consent Setup")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DataSetup {

        private String auth() {
            return "Bearer " + apiKey;
        }

        @Test
        @Order(1)
        @DisplayName("Register entity")
        void registerEntity() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"entityName": "%s", "description": "Graph query test entity"}
                    """
                                    .formatted(ENTITY_USER))
                    .post("/api/v1/catalogue/entities")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(2)
        @DisplayName("Register category")
        void registerCategory() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"categoryName": "%s", "description": "Graph query PII"}
                    """
                                    .formatted(CATEGORY_PII))
                    .post("/api/v1/catalogue/categories")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(3)
        @DisplayName("Register field")
        void registerField() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"fieldName": "%s", "entityName": "%s", "description": "Email"}
                    """
                                    .formatted(FIELD_EMAIL, ENTITY_USER))
                    .post("/api/v1/catalogue/fields")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(4)
        @DisplayName("Assign field to category")
        void assignFieldCategory() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"fieldName": "%s", "entityName": "%s", "categoryName": "%s"}
                    """
                                    .formatted(FIELD_EMAIL, ENTITY_USER, CATEGORY_PII))
                    .post("/api/v1/catalogue/fields/categories")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(5)
        @DisplayName("Register purpose")
        void registerPurpose() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"purposeName": "%s", "description": "Marketing purpose"}
                    """
                                    .formatted(PURPOSE_MARKETING))
                    .post("/api/v1/governance/purposes")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(6)
        @DisplayName("Register regulation")
        void registerRegulation() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"regulationName": "%s", "description": "GDPR regulation"}
                    """
                                    .formatted(REGULATION_GDPR))
                    .post("/api/v1/governance/regulations")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(7)
        @DisplayName("Link purpose to regulation")
        void linkPurposeRegulation() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"purposeName": "%s", "regulationName": "%s",
                     "lawfulBasis": "consent", "onGranted": "allow", "onDenied": "deny"}
                    """
                                    .formatted(PURPOSE_MARKETING, REGULATION_GDPR))
                    .post("/api/v1/governance/purposes/regulations")
                    .then()
                    .statusCode(200);
        }

        @Test
        @Order(8)
        @DisplayName("Add purpose policy")
        void addPurposePolicy() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"purposeName": "%s", "policyType": "field",
                     "fieldName": "%s", "entityName": "%s", "categoryName": "%s"}
                    """
                                    .formatted(
                                            PURPOSE_MARKETING,
                                            FIELD_EMAIL,
                                            ENTITY_USER,
                                            CATEGORY_PII))
                    .post("/api/v1/governance/purposes/policies")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(9)
        @DisplayName("Grant consent")
        void grantConsent() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"subject": "%s",
                     "scope": {"category": "%s", "fieldName": "%s",
                               "entityName": "%s", "purposeName": "%s"}}
                    """
                                    .formatted(
                                            SUBJECT_ID,
                                            CATEGORY_PII,
                                            FIELD_EMAIL,
                                            ENTITY_USER,
                                            PURPOSE_MARKETING))
                    .post("/api/v1/subjects/consent/grant")
                    .then()
                    .statusCode(200);
        }

        @Test
        @Order(10)
        @DisplayName("Wait for graph projection")
        void awaitGraphProjection() {
            support.awaitRedisState(
                    () -> support.getGraphNode(TENANT_ID, "subject", SUBJECT_ID),
                    node -> node != null && !node.isEmpty(),
                    Duration.ofSeconds(15));
        }
    }

    // -------------------------------------------------------------------
    // Phase 3 — Graph Query Tests
    // -------------------------------------------------------------------

    @Nested
    @Order(3)
    @DisplayName("Phase 3 — Graph Query API")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class GraphQuery {

        private String auth() {
            return "Bearer " + apiKey;
        }

        @Test
        @Order(1)
        @DisplayName("Basic graph query returns nodes and edges")
        void basicQuery() {
            var response =
                    given().header("Authorization", auth())
                            .queryParam("limit", 100)
                            .when()
                            .get("/api/v1/consent/graph")
                            .then()
                            .statusCode(200)
                            .body("nodes", notNullValue())
                            .body("edges", notNullValue())
                            .body("pageInfo", notNullValue())
                            .extract()
                            .response();

            List<Map<String, Object>> nodes = response.jsonPath().getList("nodes");
            List<Map<String, Object>> edges = response.jsonPath().getList("edges");

            assertThat(nodes)
                    .as(
                            "Graph should contain nodes for entity, field, category, purpose,"
                                    + " regulation, policy, subject")
                    .hasSizeGreaterThanOrEqualTo(5);

            assertThat(edges).as("Graph should contain edges connecting nodes").isNotEmpty();

            // Verify node types are present
            var nodeTypes = nodes.stream().map(n -> n.get("type").toString()).toList();
            assertThat(nodeTypes).contains("entity", "field", "purpose");
        }

        @Test
        @Order(2)
        @DisplayName("Pagination with small limit returns hasMore=true")
        void pagination() {
            var response =
                    given().header("Authorization", auth())
                            .queryParam("limit", 2)
                            .when()
                            .get("/api/v1/consent/graph")
                            .then()
                            .statusCode(200)
                            .extract()
                            .response();

            List<Map<String, Object>> nodes = response.jsonPath().getList("nodes");
            boolean hasMore = response.jsonPath().getBoolean("pageInfo.hasMore");
            int totalNodes = response.jsonPath().getInt("pageInfo.totalNodes");

            assertThat(nodes.size()).isLessThanOrEqualTo(2);
            if (totalNodes > 2) {
                assertThat(hasMore)
                        .as("hasMore should be true when more nodes exist beyond limit")
                        .isTrue();
                String nextCursor = response.jsonPath().getString("pageInfo.nextCursor");
                assertThat(nextCursor)
                        .as("nextCursor should be present when hasMore=true")
                        .isNotBlank();
            }
        }

        @Test
        @Order(3)
        @DisplayName("Cursor-based pagination returns next page")
        void cursorPagination() {
            // First page
            var page1 =
                    given().header("Authorization", auth())
                            .queryParam("limit", 2)
                            .when()
                            .get("/api/v1/consent/graph")
                            .then()
                            .statusCode(200)
                            .extract()
                            .response();

            String nextCursor = page1.jsonPath().getString("pageInfo.nextCursor");
            if (nextCursor == null) return; // Skip if all nodes fit in one page

            // Second page
            var page2 =
                    given().header("Authorization", auth())
                            .queryParam("limit", 2)
                            .queryParam("cursor", nextCursor)
                            .when()
                            .get("/api/v1/consent/graph")
                            .then()
                            .statusCode(200)
                            .extract()
                            .response();

            List<Map<String, Object>> page1Nodes = page1.jsonPath().getList("nodes");
            List<Map<String, Object>> page2Nodes = page2.jsonPath().getList("nodes");

            // Pages should not overlap
            var page1Names = page1Nodes.stream().map(n -> n.get("name").toString()).toList();
            var page2Names = page2Nodes.stream().map(n -> n.get("name").toString()).toList();
            assertThat(page2Names)
                    .as("Page 2 should not contain page 1 nodes")
                    .doesNotContainAnyElementsOf(page1Names);
        }

        @Test
        @Order(4)
        @DisplayName("Filtered query by rootType and rootName")
        void filteredByRoot() {
            var response =
                    given().header("Authorization", auth())
                            .queryParam("rootType", "purpose")
                            .queryParam("rootName", PURPOSE_MARKETING)
                            .queryParam("depth", 2)
                            .when()
                            .get("/api/v1/consent/graph")
                            .then()
                            .statusCode(200)
                            .extract()
                            .response();

            List<Map<String, Object>> nodes = response.jsonPath().getList("nodes");

            // BFS from purpose node should return the purpose + connected nodes
            assertThat(nodes).isNotEmpty();
            var names = nodes.stream().map(n -> n.get("name").toString()).toList();
            assertThat(names)
                    .as("BFS from purpose should include the purpose node itself")
                    .contains(PURPOSE_MARKETING);
        }

        @Test
        @Order(5)
        @DisplayName("Graph edges have correct source/target types")
        void edgeStructure() {
            var response =
                    given().header("Authorization", auth())
                            .queryParam("limit", 100)
                            .when()
                            .get("/api/v1/consent/graph")
                            .then()
                            .statusCode(200)
                            .extract()
                            .response();

            List<Map<String, Object>> edges = response.jsonPath().getList("edges");
            for (var edge : edges) {
                assertThat(edge.get("sourceType")).as("Edge sourceType").isNotNull();
                assertThat(edge.get("sourceName")).as("Edge sourceName").isNotNull();
                assertThat(edge.get("targetType")).as("Edge targetType").isNotNull();
                assertThat(edge.get("targetName")).as("Edge targetName").isNotNull();
            }
        }

        @Test
        @Order(6)
        @DisplayName("Total node/edge counts in pageInfo are accurate")
        void totalCounts() {
            var response =
                    given().header("Authorization", auth())
                            .queryParam("limit", 1000)
                            .when()
                            .get("/api/v1/consent/graph")
                            .then()
                            .statusCode(200)
                            .extract()
                            .response();

            int totalNodes = response.jsonPath().getInt("pageInfo.totalNodes");
            int totalEdges = response.jsonPath().getInt("pageInfo.totalEdges");
            List<Map<String, Object>> nodes = response.jsonPath().getList("nodes");
            List<Map<String, Object>> edges = response.jsonPath().getList("edges");

            assertThat(totalNodes)
                    .as("totalNodes should match returned node count when limit is large")
                    .isEqualTo(nodes.size());
            assertThat(totalEdges)
                    .as("totalEdges should match returned edge count when limit is large")
                    .isEqualTo(edges.size());
        }
    }

    // -------------------------------------------------------------------
    // Phase 4 — Auth Enforcement
    // -------------------------------------------------------------------

    @Nested
    @Order(4)
    @DisplayName("Phase 4 — Auth Enforcement")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class AuthEnforcement {

        @Test
        @Order(1)
        @DisplayName("Unauthenticated request returns 401")
        void unauthenticated() {
            given().when().get("/api/v1/consent/graph").then().statusCode(401);
        }

        @Test
        @Order(2)
        @DisplayName("Read-only consent key can query graph")
        void readOnlyCanQuery() {
            given().header("Authorization", "Bearer " + readOnlyKey)
                    .when()
                    .get("/api/v1/consent/graph")
                    .then()
                    .statusCode(200);
        }

        @Test
        @Order(3)
        @DisplayName("Invalid token returns 401")
        void invalidToken() {
            given().header("Authorization", "Bearer invalid-token-xyz")
                    .when()
                    .get("/api/v1/consent/graph")
                    .then()
                    .statusCode(401);
        }
    }

    // -------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------

    @AfterAll
    static void cleanup() {
        if (support != null) support.close();
    }
}
