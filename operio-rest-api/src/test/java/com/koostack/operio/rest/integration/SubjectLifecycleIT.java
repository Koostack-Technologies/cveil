package com.koostack.operio.rest.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

import com.koostack.operio.rest.config.OperioConfig;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;

/**
 * Integration test for subject lifecycle endpoints:
 *
 * <ul>
 *   <li>{@code DELETE /api/v1/subjects/{name}} — removeSubject
 *   <li>{@code DELETE /api/v1/subjects/{name}/dsar-key} — shredSubject
 * </ul>
 *
 * <p>Also provides deep DSAR verification with direct JDBC/Redis assertions that go beyond
 * REST-response-only checking.
 */
@QuarkusTest
@QuarkusTestResource(OperioTestResourceManager.class)
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SubjectLifecycleIT {

    private static final String TENANT_ID = "subject_lifecycle_it_tenant";
    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";

    private static final String ENTITY = "SlEntity";
    private static final String CATEGORY = "SlCategory";
    private static final String FIELD = "sl_field";
    private static final String PURPOSE = "sl_purpose";
    private static final String REGULATION = "SlRegulation";

    private static final String SUBJECT_REMOVE = "sl-remove-subject";
    private static final String SUBJECT_SHRED = "sl-shred-subject";
    private static final String SUBJECT_DSAR = "sl-dsar-deep-subject";

    @Inject OperioConfig config;

    private static volatile String accessToken;
    private static volatile IntegrationTestSupport support;

    // -------------------------------------------------------------------
    // Phase 1 — Setup
    // -------------------------------------------------------------------

    @Nested
    @Order(1)
    @DisplayName("Phase 1 — Setup")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Setup {

        @Test
        @Order(1)
        @DisplayName("Create tenant")
        void createTenant() {
            String result =
                    IntegrationTestSupport.provisionTenantViaOrchestrator(
                            BOOTSTRAP_KEY, TENANT_ID, "Subject Lifecycle IT", "pro", "Test1234!");
            assertThat(result).isEqualTo(TENANT_ID);
        }

        @Test
        @Order(2)
        @DisplayName("Login as tenant admin")
        void loginAsTenantAdmin() {
            String email = TENANT_ID.replace("_", "-") + "-admin@operio-test.com";
            accessToken = IntegrationTestSupport.loginUser(email, "Test1234!");
            assertThat(accessToken).isNotBlank();
        }

        @Test
        @Order(3)
        @DisplayName("Init support")
        void initSupport() {
            support = new IntegrationTestSupport(config, TENANT_ID);
            support.awaitTenantReady(TENANT_ID, Duration.ofSeconds(90));
            assertThat(support).isNotNull();
        }
    }

    // -------------------------------------------------------------------
    // Phase 2 — Catalogue + Governance Prerequisites
    // -------------------------------------------------------------------

    @Nested
    @Order(2)
    @DisplayName("Phase 2 — Prerequisites")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Prerequisites {

        private String auth() {
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        void registerEntity() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"entityName": "%s", "description": "d"}
                    """
                                    .formatted(ENTITY))
                    .post("/api/v1/catalogue/entities")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(2)
        void registerCategory() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"categoryName": "%s", "description": "d"}
                    """
                                    .formatted(CATEGORY))
                    .post("/api/v1/catalogue/categories")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(3)
        void registerField() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"fieldName": "%s", "entityName": "%s", "description": "d"}
                    """
                                    .formatted(FIELD, ENTITY))
                    .post("/api/v1/catalogue/fields")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(4)
        void assignFieldCategory() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"fieldName": "%s", "entityName": "%s", "categoryName": "%s"}
                    """
                                    .formatted(FIELD, ENTITY, CATEGORY))
                    .post("/api/v1/catalogue/fields/categories")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(5)
        void registerPurpose() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"purposeName": "%s", "description": "d"}
                    """
                                    .formatted(PURPOSE))
                    .post("/api/v1/governance/purposes")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(6)
        void registerRegulation() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"regulationName": "%s", "description": "d"}
                    """
                                    .formatted(REGULATION))
                    .post("/api/v1/governance/regulations")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(7)
        void linkPurposeRegulation() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"purposeName": "%s", "regulationName": "%s",
                    "lawfulBasis": "consent", "onGranted": "PLAINTEXT", "onDenied": "REDACT_ALL"}
                    """
                                    .formatted(PURPOSE, REGULATION))
                    .post("/api/v1/governance/purposes/regulations")
                    .then()
                    .statusCode(200);
        }

        @Test
        @Order(8)
        void addPolicy() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"purposeName": "%s", "policyType": "field",
                    "fieldName": "%s", "entityName": "%s", "categoryName": "%s"}
                    """
                                    .formatted(PURPOSE, FIELD, ENTITY, CATEGORY))
                    .post("/api/v1/governance/purposes/policies")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(9)
        @DisplayName("Await governance projection (policy visible in DB)")
        void awaitGovernanceProjected() {
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () -> {
                                var rows = support.query("SELECT count(*) AS cnt FROM policy");
                                int cnt = ((Number) rows.get(0).get("cnt")).intValue();
                                assertThat(cnt)
                                        .as("Expected at least 1 policy row projected")
                                        .isGreaterThanOrEqualTo(1);
                            });
        }
    }

    // -------------------------------------------------------------------
    // Phase 3 — removeSubject Tests
    // -------------------------------------------------------------------

    @Nested
    @Order(3)
    @DisplayName("Phase 3 — removeSubject")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class RemoveSubject {

        private String auth() {
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("Register subject for removal")
        void registerSubject() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"subjectName": "%s"}
                    """
                                    .formatted(SUBJECT_REMOVE))
                    .post("/api/v1/subjects")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(2)
        @DisplayName("Grant consent to subject")
        void grantConsent() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"subject": "%s",
                     "scope": {"category": "%s", "fieldName": "%s",
                               "entityName": "%s", "purposeName": "%s"}}
                    """
                                    .formatted(SUBJECT_REMOVE, CATEGORY, FIELD, ENTITY, PURPOSE))
                    .post("/api/v1/subjects/consent/grant")
                    .then()
                    .statusCode(200);
        }

        @Test
        @Order(3)
        @DisplayName("Verify subject exists in DB before removal")
        void subjectExistsInDb() {
            support.assertRowExists("subject", "subject_name", SUBJECT_REMOVE);
        }

        @Test
        @Order(4)
        @DisplayName("Verify subject has consent ledger entries")
        void ledgerEntriesExist() {
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () ->
                                    assertThat(support.countConsentLedgerEntries(SUBJECT_REMOVE))
                                            .as(
                                                    "Subject should have consent entries before"
                                                            + " removal")
                                            .isGreaterThan(0));
        }

        @Test
        @Order(5)
        @DisplayName("removeSubject on non-existent subject returns 404 or 200")
        void removeNonExistent() {
            int status =
                    given().header("Authorization", auth())
                            .delete("/api/v1/subjects/non-existent-subject-xyz")
                            .statusCode();
            // May return 404 (not found) or 200 (idempotent no-op)
            assertThat(status).isIn(200, 404);
        }

        @Test
        @Order(6)
        @DisplayName("removeSubject with active consent — verify behavior")
        void removeWithActiveConsent() {
            int status =
                    given().header("Authorization", auth())
                            .delete("/api/v1/subjects/" + SUBJECT_REMOVE)
                            .statusCode();
            // May return 409 (has active consent) or 200 (cascades)
            if (status == 409) {
                // Revoke consent first, then retry
                given().header("Authorization", auth())
                        .contentType(ContentType.JSON)
                        .body(
                                """
                        {"subject": "%s",
                         "scope": {"category": "%s", "fieldName": "%s",
                                   "entityName": "%s", "purposeName": "%s"}}
                        """
                                        .formatted(
                                                SUBJECT_REMOVE, CATEGORY, FIELD, ENTITY, PURPOSE))
                        .post("/api/v1/subjects/consent/revoke")
                        .then()
                        .statusCode(200);

                given().header("Authorization", auth())
                        .delete("/api/v1/subjects/" + SUBJECT_REMOVE)
                        .then()
                        .statusCode(200);
            }
        }

        @Test
        @Order(7)
        @DisplayName("Verify subject row removed from DB")
        void subjectRemovedFromDb() {
            support.awaitRedisState(
                    () -> support.countRows("subject", "subject_name", SUBJECT_REMOVE),
                    count -> count == 0,
                    Duration.ofSeconds(10));
            support.assertRowAbsent("subject", "subject_name", SUBJECT_REMOVE);
        }

        @Test
        @Order(8)
        @DisplayName("Verify subject node removed from graph")
        void subjectRemovedFromGraph() {
            support.awaitRedisState(
                    () -> support.getGraphNode(TENANT_ID, "subject", SUBJECT_REMOVE),
                    node -> node == null || node.isEmpty(),
                    Duration.ofSeconds(10));
        }

        @Test
        @Order(9)
        @DisplayName("Verify subject edges cleaned from graph")
        void subjectEdgesCleaned() {
            Set<String> outEdges = support.getOutgoingEdges(TENANT_ID, "subject", SUBJECT_REMOVE);
            assertThat(outEdges)
                    .as("Subject outgoing edges should be empty after removal")
                    .isEmpty();
        }
    }

    // -------------------------------------------------------------------
    // Phase 4 — shredDsarKey Tests
    // -------------------------------------------------------------------

    @Nested
    @Order(4)
    @DisplayName("Phase 4 — shredDsarKey")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ShredDsarKey {

        private String auth() {
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("Register subject for shred testing")
        void registerSubject() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"subjectName": "%s"}
                    """
                                    .formatted(SUBJECT_SHRED))
                    .post("/api/v1/subjects")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        @Test
        @Order(2)
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
                                    .formatted(SUBJECT_SHRED, CATEGORY, FIELD, ENTITY, PURPOSE))
                    .post("/api/v1/subjects/consent/grant")
                    .then()
                    .statusCode(200);
        }

        @Test
        @Order(3)
        @DisplayName("Verify subject has dsar_key in subject_dsar table")
        void dsarKeyExists() {
            // dsar_id is deterministic: UUID.nameUUIDFromBytes("dsar:" + subjectName)
            UUID dsarId =
                    UUID.nameUUIDFromBytes(
                            ("dsar:" + SUBJECT_SHRED).getBytes(StandardCharsets.UTF_8));
            var rows = support.query("SELECT dsar_key FROM subject_dsar WHERE dsar_id = ?", dsarId);
            assertThat(rows).as("subject_dsar row should exist for subject").isNotEmpty();
            assertThat(rows.get(0).get("dsar_key")).as("dsar_key should be non-null").isNotNull();
        }

        @Test
        @Order(4)
        @DisplayName("Erase subject via DSAR endpoint")
        void eraseSubject() {
            given().header("Authorization", auth())
                    .queryParam("confirm", "DELETE_" + SUBJECT_SHRED)
                    .delete("/api/v1/subjects/" + SUBJECT_SHRED + "/audit")
                    .then()
                    .statusCode(200);
        }

        @Test
        @Order(5)
        @DisplayName("Wait for all events to be published")
        void awaitEventsPublished() {
            support.awaitAllEventsPublished(Duration.ofSeconds(30));
        }

        @Test
        @Order(6)
        @DisplayName("shredDsarKey succeeds after events published")
        void shredDsarKey() {
            int status =
                    given().header("Authorization", auth())
                            .delete("/api/v1/subjects/" + SUBJECT_SHRED + "/dsar-key")
                            .statusCode();
            // 200 = shredded, 409 = events still pending (retry), 404 = subject already gone
            assertThat(status)
                    .as("shredDsarKey should succeed or report subject gone")
                    .isIn(200, 404);
        }

        @Test
        @Order(7)
        @DisplayName("shredDsarKey is idempotent")
        void shredIdempotent() {
            int status =
                    given().header("Authorization", auth())
                            .delete("/api/v1/subjects/" + SUBJECT_SHRED + "/dsar-key")
                            .statusCode();
            assertThat(status).as("Second shred should succeed or report gone").isIn(200, 404);
        }

        @Test
        @Order(8)
        @DisplayName("Verify subject_dsar row deleted after shred")
        void dsarKeyGone() {
            UUID dsarId =
                    UUID.nameUUIDFromBytes(
                            ("dsar:" + SUBJECT_SHRED).getBytes(StandardCharsets.UTF_8));
            var rows = support.query("SELECT dsar_key FROM subject_dsar WHERE dsar_id = ?", dsarId);
            assertThat(rows).as("subject_dsar row should be gone after shred").isEmpty();
        }
    }

    // -------------------------------------------------------------------
    // Phase 5 — Deep DSAR Verification (JDBC + Redis)
    // -------------------------------------------------------------------

    @Nested
    @Order(5)
    @DisplayName("Phase 5 — DSAR Deep Verification")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DsarDeepVerification {

        private String auth() {
            return "Bearer " + accessToken;
        }

        @Test
        @Order(1)
        @DisplayName("Register and grant consent to DSAR test subject")
        void setupDsarSubject() {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"subjectName": "%s"}
                    """
                                    .formatted(SUBJECT_DSAR))
                    .post("/api/v1/subjects")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));

            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {"subject": "%s",
                     "scope": {"category": "%s", "fieldName": "%s",
                               "entityName": "%s", "purposeName": "%s"}}
                    """
                                    .formatted(SUBJECT_DSAR, CATEGORY, FIELD, ENTITY, PURPOSE))
                    .post("/api/v1/subjects/consent/grant")
                    .then()
                    .statusCode(200);
        }

        @Test
        @Order(2)
        @DisplayName("Verify consent ledger entries exist BEFORE delete")
        void ledgerExistsBefore() {
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () ->
                                    assertThat(support.countConsentLedgerEntries(SUBJECT_DSAR))
                                            .as(
                                                    "Consent ledger should have entries before DSAR"
                                                            + " delete")
                                            .isGreaterThan(0));
        }

        @Test
        @Order(3)
        @DisplayName("Verify subject row exists BEFORE delete")
        void subjectExistsBefore() {
            support.assertRowExists("subject", "subject_name", SUBJECT_DSAR);
        }

        @Test
        @Order(4)
        @DisplayName("Execute DSAR deletion")
        void deleteDsar() {
            var response =
                    given().header("Authorization", auth())
                            .queryParam("confirm", "DELETE_" + SUBJECT_DSAR)
                            .delete("/api/v1/subjects/" + SUBJECT_DSAR + "/audit")
                            .then()
                            .statusCode(200)
                            .extract()
                            .response();

            String status = response.jsonPath().getString("status");
            assertThat(status)
                    .as("DSAR deletion status")
                    .isIn("PENDING_SHRED", "PARTIAL_FAILURE", "SUCCESS");
        }

        @Test
        @Order(5)
        @DisplayName("Await event propagation")
        void awaitEvents() {
            support.awaitAllEventsPublished(Duration.ofSeconds(30));
        }

        @Test
        @Order(6)
        @DisplayName("Verify consent ledger entries GONE after delete")
        void ledgerGoneAfter() {
            // The DSAR delete should have revoked all consent and removed ledger entries
            support.awaitRedisState(
                    () -> support.countConsentLedgerEntries(SUBJECT_DSAR),
                    count -> count == 0,
                    Duration.ofSeconds(10));
        }

        @Test
        @Order(7)
        @DisplayName("Verify dsar_audit_log row inserted")
        void dsarAuditLogInserted() {
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(
                            () -> {
                                var auditRows = support.queryDsarAuditLog(SUBJECT_DSAR);
                                assertThat(auditRows)
                                        .as(
                                                "DSAR audit log should have at least one entry for"
                                                        + " subject")
                                        .isNotEmpty();
                                assertThat(auditRows.get(0).get("subject_id").toString())
                                        .isEqualTo(SUBJECT_DSAR);
                            });
        }

        @Test
        @Order(8)
        @DisplayName("Verify Redis consent cache cleared for subject")
        void redisCacheCleared() {
            String cache = support.getAdminConsentCache(TENANT_ID, SUBJECT_DSAR);
            assertThat(cache)
                    .as("Admin consent cache should be null/empty after DSAR delete")
                    .isNullOrEmpty();
        }

        @Test
        @Order(9)
        @DisplayName("Verify graph subject node removed")
        void graphNodeRemoved() {
            support.awaitRedisState(
                    () -> support.getGraphNode(TENANT_ID, "subject", SUBJECT_DSAR),
                    node -> node == null || node.isEmpty(),
                    Duration.ofSeconds(10));
        }

        @Test
        @Order(10)
        @DisplayName("Verify graph subject index cleaned")
        void graphIndexCleaned() {
            List<String> subjectIndex = support.getTypeIndex(TENANT_ID, "subject");
            assertThat(subjectIndex)
                    .as("Subject type index should not contain deleted subject")
                    .doesNotContain(SUBJECT_DSAR);
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
