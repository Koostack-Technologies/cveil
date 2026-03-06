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
import java.util.Set;
import org.junit.jupiter.api.*;

/**
 * Integration test for the Redis consent graph projection ({@link
 * com.koostack.operio.tenant.consent.projection.decision.ConsentGraphProjectionHandler}).
 *
 * <p>Registers catalogue items, governance items, and grants/revokes consent via REST API, then
 * verifies the Redis graph model was correctly built:
 *
 * <ul>
 *   <li>Graph nodes: {@code {tenantId}:node:{type}:{name}} (Hash)
 *   <li>Outgoing edges: {@code {tenantId}:out:{type}:{name}} (Set)
 *   <li>Incoming edges: {@code {tenantId}:in:{type}:{name}} (Set)
 *   <li>Edge properties: {@code {tenantId}:ep:{fromType}:{fromName}:{toType}:{toName}} (Hash)
 *   <li>Type indexes: {@code {tenantId}:idx:{type}} (Sorted Set)
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(OperioTestResourceManager.class)
@DisplayName("ConsentGraphProjection Integration Test (Postgres + Redis)")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsentGraphProjectionIT {

    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";

    private static final String TENANT_ID = "graph_it_tenant";

    // Catalogue items
    private static final String ENTITY_CUSTOMER = "Customer";
    private static final String CATEGORY_PERSONAL = "Personal";
    private static final String FIELD_EMAIL = "email";
    private static final String QUALIFIED_FIELD = ENTITY_CUSTOMER + "/" + FIELD_EMAIL;

    // Governance items
    private static final String PURPOSE_MARKETING = "marketing";
    private static final String REGULATION_GDPR = "GDPR";

    // Subject
    private static final String SUBJECT_ID = "user-42";

    // Derived policy node name: FIELD:{entity}/{field}:{purpose}
    private static final String POLICY_NODE_NAME =
            "FIELD:" + ENTITY_CUSTOMER + "/" + FIELD_EMAIL + ":" + PURPOSE_MARKETING;

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(30);

    private static volatile String apiKey;
    private static volatile IntegrationTestSupport support;

    @Inject OperioConfig config;

    // =======================================================================
    // 1. Setup
    // =======================================================================

    @Nested
    @Order(1)
    @DisplayName("1 — Setup (tenant + API key)")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Setup {

        @Test
        @Order(1)
        @DisplayName("Create tenant")
        void createTenant() {
            given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "tenantId": "%s",
                      "displayName": "Graph Projection IT Tenant",
                      "plan": "enterprise"
                    }
                    """
                                    .formatted(TENANT_ID))
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
            Response response =
                    given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                            .contentType(ContentType.JSON)
                            .body(
                                    """
                    {
                      "tenantId": "%s",
                      "subject": "graph-it-admin",
                      "roles": ["admin:read", "admin:write", "consent:read", "consent:write"]
                    }
                    """
                                            .formatted(TENANT_ID))
                            .when()
                            .post("/api/v1/platform/api-keys")
                            .then()
                            .statusCode(201)
                            .extract()
                            .response();

            apiKey = response.jsonPath().getString("rawKey");
            assertNotNull(apiKey);
            support = new IntegrationTestSupport(config, TENANT_ID);
            support.awaitApiKeyUsable(apiKey, Duration.ofSeconds(30));
            support.awaitEventTableReady(Duration.ofSeconds(15));
            support.awaitTenantDiscoverable(TENANT_ID, Duration.ofSeconds(30));
        }
    }

    // =======================================================================
    // 2. Catalogue + Governance Setup via REST
    // =======================================================================

    @Nested
    @Order(2)
    @DisplayName("2 — Catalogue & Governance Setup via REST")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DataSetup {

        private String auth() {
            assertNotNull(apiKey, "apiKey required");
            return "Bearer " + apiKey;
        }

        @Test
        @Order(1)
        @DisplayName("Register entity: Customer")
        void registerEntity() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "entityName": "%s", "description": "Customer entity" }
                    """
                                    .formatted(ENTITY_CUSTOMER))
                    .when()
                    .post("/api/v1/catalogue/entities")
                    .then()
                    .statusCode(201);
        }

        @Test
        @Order(2)
        @DisplayName("Register category: Personal")
        void registerCategory() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "categoryName": "%s", "description": "Personal data" }
                    """
                                    .formatted(CATEGORY_PERSONAL))
                    .when()
                    .post("/api/v1/catalogue/categories")
                    .then()
                    .statusCode(201);
        }

        @Test
        @Order(3)
        @DisplayName("Register field: email on Customer")
        void registerField() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "fieldName": "%s",
                      "entityName": "%s",
                      "description": "Customer email"
                    }
                    """
                                    .formatted(FIELD_EMAIL, ENTITY_CUSTOMER))
                    .when()
                    .post("/api/v1/catalogue/fields")
                    .then()
                    .statusCode(201);
        }

        @Test
        @Order(4)
        @DisplayName("Assign field email to category Personal")
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
                                    .formatted(FIELD_EMAIL, ENTITY_CUSTOMER, CATEGORY_PERSONAL))
                    .when()
                    .post("/api/v1/catalogue/fields/categories")
                    .then()
                    .statusCode(200);
        }

        @Test
        @Order(10)
        @DisplayName("Register purpose: marketing")
        void registerPurpose() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "purposeName": "%s", "description": "Marketing purpose" }
                    """
                                    .formatted(PURPOSE_MARKETING))
                    .when()
                    .post("/api/v1/governance/purposes")
                    .then()
                    .statusCode(201);
        }

        @Test
        @Order(11)
        @DisplayName("Register regulation: GDPR")
        void registerRegulation() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "regulationName": "%s", "description": "EU GDPR" }
                    """
                                    .formatted(REGULATION_GDPR))
                    .when()
                    .post("/api/v1/governance/regulations")
                    .then()
                    .statusCode(201);
        }

        @Test
        @Order(12)
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
        }

        @Test
        @Order(13)
        @DisplayName("Add field-scope policy: marketing/email/Customer")
        void addPurposePolicy() {
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
                                            ENTITY_CUSTOMER,
                                            CATEGORY_PERSONAL))
                    .when()
                    .post("/api/v1/governance/purposes/policies")
                    .then()
                    .statusCode(201);
        }

        @Test
        @Order(20)
        @DisplayName("Register subject: user-42")
        void registerSubject() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "subjectName": "%s", "description": "Graph test subject" }
                    """
                                    .formatted(SUBJECT_ID))
                    .when()
                    .post("/api/v1/subjects")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }
    }

    // =======================================================================
    // 3. Verify Catalogue Graph Nodes
    // =======================================================================

    @Nested
    @Order(3)
    @DisplayName("3 — Catalogue Graph Nodes")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class CatalogueGraphNodes {

        @Test
        @Order(1)
        @DisplayName("Entity node exists: Customer")
        void entityNodeExists() {
            Map<String, String> node =
                    support.awaitRedisState(
                            () -> support.getGraphNode(TENANT_ID, "entity", ENTITY_CUSTOMER),
                            map -> map != null && !map.isEmpty(),
                            AWAIT_TIMEOUT);
            assertThat(node).containsKey("name");
            assertThat(node.get("name")).isEqualTo(ENTITY_CUSTOMER);
        }

        @Test
        @Order(2)
        @DisplayName("Field node exists: Customer/email")
        void fieldNodeExists() {
            Map<String, String> node =
                    support.awaitRedisState(
                            () -> support.getGraphNode(TENANT_ID, "field", QUALIFIED_FIELD),
                            map -> map != null && !map.isEmpty(),
                            AWAIT_TIMEOUT);
            assertThat(node).isNotEmpty();
        }

        @Test
        @Order(3)
        @DisplayName("Category node exists: Personal")
        void categoryNodeExists() {
            Map<String, String> node =
                    support.awaitRedisState(
                            () -> support.getGraphNode(TENANT_ID, "category", CATEGORY_PERSONAL),
                            map -> map != null && !map.isEmpty(),
                            AWAIT_TIMEOUT);
            assertThat(node).containsKey("name");
        }

        @Test
        @Order(4)
        @DisplayName("Entity type index contains Customer")
        void entityTypeIndex() {
            List<String> index =
                    support.awaitRedisState(
                            () -> support.getTypeIndex(TENANT_ID, "entity"),
                            list -> list != null && list.contains(ENTITY_CUSTOMER),
                            AWAIT_TIMEOUT);
            assertThat(index).contains(ENTITY_CUSTOMER);
        }

        @Test
        @Order(5)
        @DisplayName("Field type index contains Customer/email")
        void fieldTypeIndex() {
            List<String> index =
                    support.awaitRedisState(
                            () -> support.getTypeIndex(TENANT_ID, "field"),
                            list -> list != null && list.contains(QUALIFIED_FIELD),
                            AWAIT_TIMEOUT);
            assertThat(index).contains(QUALIFIED_FIELD);
        }

        @Test
        @Order(6)
        @DisplayName("Field → Entity outgoing edge")
        void fieldToEntityEdge() {
            Set<String> edges =
                    support.awaitRedisState(
                            () -> support.getOutgoingEdges(TENANT_ID, "field", QUALIFIED_FIELD),
                            set -> set != null && set.contains("entity:" + ENTITY_CUSTOMER),
                            AWAIT_TIMEOUT);
            assertThat(edges).contains("entity:" + ENTITY_CUSTOMER);
        }

        @Test
        @Order(7)
        @DisplayName("Entity ← Field incoming edge")
        void entityFromFieldEdge() {
            Set<String> edges =
                    support.awaitRedisState(
                            () -> support.getIncomingEdges(TENANT_ID, "entity", ENTITY_CUSTOMER),
                            set -> set != null && set.contains("field:" + QUALIFIED_FIELD),
                            AWAIT_TIMEOUT);
            assertThat(edges).contains("field:" + QUALIFIED_FIELD);
        }

        @Test
        @Order(8)
        @DisplayName("Field → Category edge (after assignment)")
        void fieldToCategoryEdge() {
            Set<String> edges =
                    support.awaitRedisState(
                            () -> support.getOutgoingEdges(TENANT_ID, "field", QUALIFIED_FIELD),
                            set -> set != null && set.contains("category:" + CATEGORY_PERSONAL),
                            AWAIT_TIMEOUT);
            assertThat(edges).contains("category:" + CATEGORY_PERSONAL);
        }
    }

    // =======================================================================
    // 4. Verify Governance Graph Nodes
    // =======================================================================

    @Nested
    @Order(4)
    @DisplayName("4 — Governance Graph Nodes")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class GovernanceGraphNodes {

        @Test
        @Order(1)
        @DisplayName("Purpose node exists: marketing")
        void purposeNodeExists() {
            Map<String, String> node =
                    support.awaitRedisState(
                            () -> support.getGraphNode(TENANT_ID, "purpose", PURPOSE_MARKETING),
                            map -> map != null && !map.isEmpty(),
                            AWAIT_TIMEOUT);
            assertThat(node).containsKey("name");
            assertThat(node.get("name")).isEqualTo(PURPOSE_MARKETING);
        }

        @Test
        @Order(2)
        @DisplayName("Regulation node exists: GDPR")
        void regulationNodeExists() {
            Map<String, String> node =
                    support.awaitRedisState(
                            () -> support.getGraphNode(TENANT_ID, "regulation", REGULATION_GDPR),
                            map -> map != null && !map.isEmpty(),
                            AWAIT_TIMEOUT);
            assertThat(node).containsKey("name");
        }

        @Test
        @Order(3)
        @DisplayName("Purpose → Regulation outgoing edge")
        void purposeToRegulationEdge() {
            Set<String> edges =
                    support.awaitRedisState(
                            () -> support.getOutgoingEdges(TENANT_ID, "purpose", PURPOSE_MARKETING),
                            set -> set != null && set.contains("regulation:" + REGULATION_GDPR),
                            AWAIT_TIMEOUT);
            assertThat(edges).contains("regulation:" + REGULATION_GDPR);
        }

        @Test
        @Order(4)
        @DisplayName("Policy node exists: FIELD:Customer/email:marketing")
        void policyNodeExists() {
            Map<String, String> node =
                    support.awaitRedisState(
                            () -> support.getGraphNode(TENANT_ID, "policy", POLICY_NODE_NAME),
                            map -> map != null && !map.isEmpty(),
                            AWAIT_TIMEOUT);
            assertThat(node).isNotEmpty();
        }

        @Test
        @Order(5)
        @DisplayName("Purpose → Policy outgoing edge")
        void purposeToPolicyEdge() {
            Set<String> edges =
                    support.awaitRedisState(
                            () -> support.getOutgoingEdges(TENANT_ID, "purpose", PURPOSE_MARKETING),
                            set -> set != null && set.contains("policy:" + POLICY_NODE_NAME),
                            AWAIT_TIMEOUT);
            assertThat(edges).contains("policy:" + POLICY_NODE_NAME);
        }

        @Test
        @Order(6)
        @DisplayName("Policy → Field outgoing edge")
        void policyToFieldEdge() {
            Set<String> edges =
                    support.awaitRedisState(
                            () -> support.getOutgoingEdges(TENANT_ID, "policy", POLICY_NODE_NAME),
                            set -> set != null && set.contains("field:" + QUALIFIED_FIELD),
                            AWAIT_TIMEOUT);
            assertThat(edges).contains("field:" + QUALIFIED_FIELD);
        }

        @Test
        @Order(7)
        @DisplayName("Purpose type index contains marketing")
        void purposeTypeIndex() {
            List<String> index =
                    support.awaitRedisState(
                            () -> support.getTypeIndex(TENANT_ID, "purpose"),
                            list -> list != null && list.contains(PURPOSE_MARKETING),
                            AWAIT_TIMEOUT);
            assertThat(index).contains(PURPOSE_MARKETING);
        }

        @Test
        @Order(8)
        @DisplayName("Policy type index contains policy node")
        void policyTypeIndex() {
            List<String> index =
                    support.awaitRedisState(
                            () -> support.getTypeIndex(TENANT_ID, "policy"),
                            list -> list != null && list.contains(POLICY_NODE_NAME),
                            AWAIT_TIMEOUT);
            assertThat(index).contains(POLICY_NODE_NAME);
        }
    }

    // =======================================================================
    // 5. Grant Consent & Verify Graph
    // =======================================================================

    @Nested
    @Order(5)
    @DisplayName("5 — Grant Consent & Verify Graph")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class GrantConsentGraph {

        private String auth() {
            assertNotNull(apiKey, "apiKey required");
            return "Bearer " + apiKey;
        }

        @Test
        @Order(1)
        @DisplayName("Grant consent: user-42 → marketing/email/Customer")
        void grantConsent() {
            // Wait for the policy to be projected before granting
            support.awaitQuery(
                    "SELECT p.policy_type FROM policy p "
                            + "JOIN purpose pu ON p.purpose_id = pu.purpose_id "
                            + "JOIN field f ON p.field_id = f.field_id "
                            + "WHERE pu.purpose_name = ? AND f.field_name = ?",
                    rows -> !rows.isEmpty(),
                    "Policy for marketing/email must be projected before consent grant",
                    PURPOSE_MARKETING,
                    FIELD_EMAIL);

            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "subject": "%s",
                      "scope": {
                        "category": "%s",
                        "fieldName": "%s",
                        "entityName": "%s",
                        "purposeName": "%s"
                      }
                    }
                    """
                                    .formatted(
                                            SUBJECT_ID,
                                            CATEGORY_PERSONAL,
                                            FIELD_EMAIL,
                                            ENTITY_CUSTOMER,
                                            PURPOSE_MARKETING))
                    .when()
                    .post("/api/v1/subjects/consent/grant")
                    .then()
                    .statusCode(200);
        }

        @Test
        @Order(2)
        @DisplayName("Subject node created in graph")
        void subjectNodeExists() {
            Map<String, String> node =
                    support.awaitRedisState(
                            () -> support.getGraphNode(TENANT_ID, "subject", SUBJECT_ID),
                            map -> map != null && !map.isEmpty(),
                            AWAIT_TIMEOUT);
            assertThat(node).isNotEmpty();
        }

        @Test
        @Order(3)
        @DisplayName("Subject → Policy outgoing edge exists")
        void subjectToPolicyEdge() {
            Set<String> edges =
                    support.awaitRedisState(
                            () -> support.getOutgoingEdges(TENANT_ID, "subject", SUBJECT_ID),
                            set -> set != null && set.contains("policy:" + POLICY_NODE_NAME),
                            AWAIT_TIMEOUT);
            assertThat(edges).contains("policy:" + POLICY_NODE_NAME);
        }

        @Test
        @Order(4)
        @DisplayName("Policy ← Subject incoming edge exists")
        void policyFromSubjectEdge() {
            Set<String> edges =
                    support.awaitRedisState(
                            () -> support.getIncomingEdges(TENANT_ID, "policy", POLICY_NODE_NAME),
                            set -> set != null && set.contains("subject:" + SUBJECT_ID),
                            AWAIT_TIMEOUT);
            assertThat(edges).contains("subject:" + SUBJECT_ID);
        }

        @Test
        @Order(5)
        @DisplayName("Edge properties: action=GRANT, updatedAt present")
        void edgePropertiesGrant() {
            Map<String, String> props =
                    support.awaitRedisState(
                            () ->
                                    support.getEdgeProperties(
                                            TENANT_ID,
                                            "subject",
                                            SUBJECT_ID,
                                            "policy",
                                            POLICY_NODE_NAME),
                            map -> map != null && "GRANT".equals(map.get("action")),
                            AWAIT_TIMEOUT);
            assertThat(props.get("action")).isEqualTo("GRANT");
            assertThat(props).containsKey("updatedAt");
        }

        @Test
        @Order(6)
        @DisplayName("Subject type index contains user-42")
        void subjectTypeIndex() {
            List<String> index =
                    support.awaitRedisState(
                            () -> support.getTypeIndex(TENANT_ID, "subject"),
                            list -> list != null && list.contains(SUBJECT_ID),
                            AWAIT_TIMEOUT);
            assertThat(index).contains(SUBJECT_ID);
        }
    }

    // =======================================================================
    // 6. Revoke & Verify Graph Update
    // =======================================================================

    @Nested
    @Order(6)
    @DisplayName("6 — Revoke & Verify Graph Update")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class RevokeConsentGraph {

        private String auth() {
            assertNotNull(apiKey, "apiKey required");
            return "Bearer " + apiKey;
        }

        @Test
        @Order(1)
        @DisplayName("Revoke consent: user-42 → marketing/email/Customer")
        void revokeConsent() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "subject": "%s",
                      "scope": {
                        "category": "%s",
                        "fieldName": "%s",
                        "entityName": "%s",
                        "purposeName": "%s"
                      }
                    }
                    """
                                    .formatted(
                                            SUBJECT_ID,
                                            CATEGORY_PERSONAL,
                                            FIELD_EMAIL,
                                            ENTITY_CUSTOMER,
                                            PURPOSE_MARKETING))
                    .when()
                    .post("/api/v1/subjects/consent/revoke")
                    .then()
                    .statusCode(200);
        }

        @Test
        @Order(2)
        @DisplayName("Edge properties updated: action=REVOKE")
        void edgePropertiesRevoked() {
            Map<String, String> props =
                    support.awaitRedisState(
                            () ->
                                    support.getEdgeProperties(
                                            TENANT_ID,
                                            "subject",
                                            SUBJECT_ID,
                                            "policy",
                                            POLICY_NODE_NAME),
                            map -> map != null && "REVOKE".equals(map.get("action")),
                            AWAIT_TIMEOUT);
            assertThat(props.get("action")).isEqualTo("REVOKE");
        }

        @Test
        @Order(3)
        @DisplayName("Subject node still exists after revoke")
        void subjectNodeStillExists() {
            Map<String, String> node = support.getGraphNode(TENANT_ID, "subject", SUBJECT_ID);
            assertThat(node).isNotEmpty();
        }

        @Test
        @Order(4)
        @DisplayName("Subject → Policy edge still exists after revoke")
        void edgeStillExists() {
            Set<String> edges = support.getOutgoingEdges(TENANT_ID, "subject", SUBJECT_ID);
            assertThat(edges).contains("policy:" + POLICY_NODE_NAME);
        }
    }

    // =======================================================================
    // 7. Subject Erasure & Graph Cleanup
    // =======================================================================

    @Nested
    @Order(7)
    @DisplayName("7 — Subject Erasure & Graph Cleanup")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SubjectErasureGraph {

        private String auth() {
            assertNotNull(apiKey, "apiKey required");
            return "Bearer " + apiKey;
        }

        @Test
        @Order(1)
        @DisplayName("Wait for all events to be published before erasure")
        void awaitEventsPublished() {
            support.awaitAllEventsPublished(Duration.ofSeconds(30));
        }

        @Test
        @Order(2)
        @DisplayName("Erase subject: user-42")
        void eraseSubject() {
            given().header("Authorization", auth())
                    .when()
                    .delete(
                            "/api/v1/subjects/"
                                    + SUBJECT_ID
                                    + "/audit?confirm=DELETE_"
                                    + SUBJECT_ID)
                    .then()
                    .statusCode(200);
        }

        @Test
        @Order(3)
        @DisplayName("Subject node removed from graph")
        void subjectNodeRemoved() {
            Map<String, String> node =
                    support.awaitRedisState(
                            () -> support.getGraphNode(TENANT_ID, "subject", SUBJECT_ID),
                            map -> map == null || map.isEmpty(),
                            AWAIT_TIMEOUT);
            assertThat(node == null || node.isEmpty()).isTrue();
        }

        @Test
        @Order(4)
        @DisplayName("Subject outgoing edges removed")
        void subjectEdgesRemoved() {
            Set<String> edges =
                    support.awaitRedisState(
                            () -> support.getOutgoingEdges(TENANT_ID, "subject", SUBJECT_ID),
                            set -> set == null || set.isEmpty(),
                            AWAIT_TIMEOUT);
            assertThat(edges == null || edges.isEmpty()).isTrue();
        }

        @Test
        @Order(5)
        @DisplayName("Policy no longer has incoming edge from subject")
        void policyIncomingEdgeCleaned() {
            Set<String> edges =
                    support.awaitRedisState(
                            () -> support.getIncomingEdges(TENANT_ID, "policy", POLICY_NODE_NAME),
                            set -> set == null || !set.contains("subject:" + SUBJECT_ID),
                            AWAIT_TIMEOUT);
            assertThat(edges == null || !edges.contains("subject:" + SUBJECT_ID)).isTrue();
        }

        @Test
        @Order(6)
        @DisplayName("Subject removed from type index")
        void subjectRemovedFromIndex() {
            List<String> index =
                    support.awaitRedisState(
                            () -> support.getTypeIndex(TENANT_ID, "subject"),
                            list -> list == null || !list.contains(SUBJECT_ID),
                            AWAIT_TIMEOUT);
            assertThat(index == null || !index.contains(SUBJECT_ID)).isTrue();
        }

        @Test
        @Order(7)
        @DisplayName("Edge properties removed for subject → policy")
        void edgePropertiesRemoved() {
            Map<String, String> props =
                    support.awaitRedisState(
                            () ->
                                    support.getEdgeProperties(
                                            TENANT_ID,
                                            "subject",
                                            SUBJECT_ID,
                                            "policy",
                                            POLICY_NODE_NAME),
                            map -> map == null || map.isEmpty(),
                            AWAIT_TIMEOUT);
            assertThat(props == null || props.isEmpty()).isTrue();
        }

        @Test
        @Order(8)
        @DisplayName("Non-subject graph nodes remain intact")
        void otherNodesIntact() {
            // Purpose, regulation, policy, entity, field, category should still exist
            Map<String, String> purposeNode =
                    support.getGraphNode(TENANT_ID, "purpose", PURPOSE_MARKETING);
            assertThat(purposeNode).isNotEmpty();

            Map<String, String> policyNode =
                    support.getGraphNode(TENANT_ID, "policy", POLICY_NODE_NAME);
            assertThat(policyNode).isNotEmpty();

            Map<String, String> entityNode =
                    support.getGraphNode(TENANT_ID, "entity", ENTITY_CUSTOMER);
            assertThat(entityNode).isNotEmpty();
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
