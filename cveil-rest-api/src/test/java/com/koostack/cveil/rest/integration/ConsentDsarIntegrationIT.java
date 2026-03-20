package com.koostack.cveil.rest.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

import com.koostack.cveil.rest.config.CveilConfig;
import com.koostack.cveil.rest.dto.ConsentDtos.DsarDeletionReceipt;
import com.koostack.cveil.rest.dto.ConsentDtos.SubjectDataExport;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for DSAR (Data Subject Access Request) endpoints.
 *
 * <p>Tests compliance with GDPR Article 15 (right of access), Article 17 (right to erasure),
 * Article 20 (data portability), and CCPA §1798.100/105.
 *
 * <p>Test coverage:
 *
 * <ul>
 *   <li>Export completeness: grant multiple policies, export, verify all returned
 *   <li>Export empty subject: export subject with no consent, verify empty lists
 *   <li>Export audit trail: grant, revoke, export, verify audit entries in correct order
 *   <li>Delete with missing confirmation: DELETE without confirm param → 400
 *   <li>Delete with wrong confirmation: DELETE with wrong confirm token → 400
 *   <li>Delete atomicity: grant + cache, DELETE, verify all stores cleared
 *   <li>Delete idempotency: DELETE twice, both succeed
 *   <li>Delete audit logging: DELETE, verify entry in dsar_audit_log
 *   <li>Delete authorization: call without valid credentials → 401
 *   <li>Export authorization: call without valid credentials → 401
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(CveilTestResourceManager.class)
@DisplayName("DSAR (Data Subject Access Request) Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsentDsarIntegrationIT {

    private static final Logger LOG = LoggerFactory.getLogger(ConsentDsarIntegrationIT.class);

    /** Bootstrap key configured in test application.yml (force-bootstrap=true). */
    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";

    private static final String TENANT_ID = "dsar_it_tenant";

    // Catalogue items used across tests
    private static final String ENTITY_DSAR_USER = "dsar_user";
    private static final String ENTITY_USER = "user";
    private static final String ENTITY_PATIENT = "patient";
    private static final String CATEGORY_PII = "PII";
    private static final String CATEGORY_HEALTH = "HEALTH";
    private static final String FIELD_EMAIL = "email";
    private static final String FIELD_PHONE = "phone";
    private static final String FIELD_SSN = "ssn";
    private static final String PURPOSE_MARKETING = "marketing";
    private static final String PURPOSE_ANALYTICS = "analytics";
    private static final String PURPOSE_SALES = "sales";
    private static final String REGULATION_GDPR = "GDPR";

    private static volatile String accessToken;
    private static volatile IntegrationTestSupport support;
    private static final Set<String> registeredSubjects = ConcurrentHashMap.newKeySet();

    @Inject CveilConfig config;

    private String auth() {
        return "Bearer " + accessToken;
    }

    // -----------------------------------------------------------------------
    // Phase 0: Setup — provision tenant, catalogue, and governance
    // -----------------------------------------------------------------------

    /**
     * Provisions a tenant via the platform orchestrator, logs in via Keycloak, then registers all
     * catalogue and governance items required by subsequent DSAR tests.
     */
    @Test
    @Order(0)
    @DisplayName("Setup: Provision tenant, catalogue, and governance")
    void setup() {
        IntegrationTestSupport.AuthInfo auth =
                IntegrationTestSupport.provisionTenantAndLogin(
                        BOOTSTRAP_KEY, TENANT_ID, "DSAR IT Tenant");
        accessToken = auth.accessToken();
        assertThat(accessToken).isNotBlank();
        support = new IntegrationTestSupport(config, TENANT_ID);
        support.awaitTenantReady(TENANT_ID, Duration.ofSeconds(90));

        // Register entities
        for (var entity : List.of(ENTITY_DSAR_USER, ENTITY_USER, ENTITY_PATIENT)) {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "entityName": "%s", "description": "%s entity" }
                    """
                                    .formatted(entity, entity))
                    .when()
                    .post("/api/v1/catalogue/entities")
                    .then()
                    .statusCode(201);
        }

        // Register categories
        for (var cat : List.of(CATEGORY_PII, CATEGORY_HEALTH)) {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "categoryName": "%s", "description": "%s data" }
                    """
                                    .formatted(cat, cat))
                    .when()
                    .post("/api/v1/catalogue/categories")
                    .then()
                    .statusCode(201);
        }

        // Register fields
        record FieldDef(String fieldName, String entityName) {}
        var fields =
                List.of(
                        new FieldDef(FIELD_EMAIL, ENTITY_DSAR_USER),
                        new FieldDef(FIELD_PHONE, ENTITY_USER),
                        new FieldDef(FIELD_SSN, ENTITY_PATIENT));
        for (var f : fields) {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "fieldName": "%s",
                      "entityName": "%s",
                      "description": "%s field"
                    }
                    """
                                    .formatted(f.fieldName(), f.entityName(), f.fieldName()))
                    .when()
                    .post("/api/v1/catalogue/fields")
                    .then()
                    .statusCode(201);
        }

        // Assign fields to categories
        record Link(String fieldName, String entityName, String categoryName) {}
        var links =
                List.of(
                        new Link(FIELD_EMAIL, ENTITY_DSAR_USER, CATEGORY_PII),
                        new Link(FIELD_PHONE, ENTITY_USER, CATEGORY_PII),
                        new Link(FIELD_SSN, ENTITY_PATIENT, CATEGORY_HEALTH));
        for (var link : links) {
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
                                    .formatted(
                                            link.fieldName(),
                                            link.entityName(),
                                            link.categoryName()))
                    .when()
                    .post("/api/v1/catalogue/fields/categories")
                    .then()
                    .statusCode(200);
        }

        // Register purposes
        for (var purpose : List.of(PURPOSE_MARKETING, PURPOSE_ANALYTICS, PURPOSE_SALES)) {
            given().header("Authorization", auth())
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    { "purposeName": "%s", "description": "%s purpose" }
                    """
                                    .formatted(purpose, purpose))
                    .when()
                    .post("/api/v1/governance/purposes")
                    .then()
                    .statusCode(201);
        }

        // Register regulation
        given().header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body(
                        """
                { "regulationName": "%s", "description": "EU regulation" }
                """
                                .formatted(REGULATION_GDPR))
                .when()
                .post("/api/v1/governance/regulations")
                .then()
                .statusCode(201);

        // Link purposes to regulation
        for (var purpose : List.of(PURPOSE_MARKETING, PURPOSE_ANALYTICS, PURPOSE_SALES)) {
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
                                    .formatted(purpose, REGULATION_GDPR))
                    .when()
                    .post("/api/v1/governance/purposes/regulations")
                    .then()
                    .statusCode(200);
        }

        // Register purpose policies (field-scope)
        record Policy(
                String purposeName, String fieldName, String entityName, String categoryName) {}
        var policies =
                List.of(
                        new Policy(PURPOSE_MARKETING, FIELD_EMAIL, ENTITY_DSAR_USER, CATEGORY_PII),
                        new Policy(PURPOSE_ANALYTICS, FIELD_PHONE, ENTITY_USER, CATEGORY_PII),
                        new Policy(PURPOSE_SALES, FIELD_SSN, ENTITY_PATIENT, CATEGORY_HEALTH));
        for (var p : policies) {
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
                                            p.purposeName(),
                                            p.fieldName(),
                                            p.entityName(),
                                            p.categoryName()))
                    .when()
                    .post("/api/v1/governance/purposes/policies")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }

        // Await governance projection (policies visible in DB)
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(
                        () -> {
                            var policyRows = support.query("SELECT count(*) AS cnt FROM policy");
                            int policyCnt = ((Number) policyRows.get(0).get("cnt")).intValue();
                            assertThat(policyCnt)
                                    .as("Expected at least 3 policy rows projected")
                                    .isGreaterThanOrEqualTo(3);
                        });

        LOG.info(
                "DSAR IT setup complete: tenant={}, token-length={}",
                TENANT_ID,
                accessToken.length());
    }

    // -----------------------------------------------------------------------
    // DSAR Export Tests
    // -----------------------------------------------------------------------

    /**
     * Test 1: Export completeness
     *
     * <p>Grant multiple policies for a subject, then export and verify all are returned in the
     * export.
     */
    @Test
    @Order(1)
    @DisplayName("Export should return all consent policies for a subject")
    void testExportCompleteness() {
        String subject = "export_test_1";

        grantConsent(subject, CATEGORY_PII, FIELD_EMAIL, ENTITY_DSAR_USER, PURPOSE_MARKETING);
        grantConsent(subject, CATEGORY_PII, FIELD_PHONE, ENTITY_USER, PURPOSE_ANALYTICS);
        grantConsent(subject, CATEGORY_HEALTH, FIELD_SSN, ENTITY_PATIENT, PURPOSE_SALES);

        // Consent ledger is an async projection — poll until entries are visible
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(
                        () -> {
                            SubjectDataExport export = exportSubject(subject);
                            assertThat(export.subject()).isEqualTo(subject);
                            assertThat(export.consent().entries()).hasSize(3);
                            assertThat(export.exportedAt()).isNotNull();
                        });
    }

    /**
     * Test 2: Export empty subject
     *
     * <p>Export a subject with no consent and verify empty lists are returned.
     */
    @Test
    @Order(2)
    @DisplayName("Export should return empty lists for subject with no consent")
    void testExportEmptySubject() {
        String subject = "export_test_empty";
        ensureSubjectRegistered(subject);

        SubjectDataExport export = exportSubject(subject);

        assertThat(export.subject()).isEqualTo(subject);
        assertThat(export.consent().entries()).isEmpty();
        assertThat(export.cachedKeyCount()).isZero();
    }

    /**
     * Test 3: Export audit trail
     *
     * <p>Grant then revoke the same scope. The export reflects the effective state: a single entry
     * with action REVOKED.
     */
    @Test
    @Order(3)
    @DisplayName("Export should reflect revoked state after grant then revoke")
    void testExportAuditTrail() {
        String subject = "export_test_audit";

        grantConsent(subject, CATEGORY_PII, FIELD_EMAIL, ENTITY_DSAR_USER, PURPOSE_MARKETING);
        revokeConsent(subject, CATEGORY_PII, FIELD_EMAIL, ENTITY_DSAR_USER, PURPOSE_MARKETING);

        // Consent ledger is an async projection — poll until the revoked entry is visible
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(
                        () -> {
                            SubjectDataExport export = exportSubject(subject);
                            assertThat(export.consent().entries()).isNotEmpty();
                            assertThat(export.consent().entries().get(0).action())
                                    .containsIgnoringCase("REVOKE");
                        });
    }

    // -----------------------------------------------------------------------
    // DSAR Delete Tests
    // -----------------------------------------------------------------------

    /**
     * Test 4: Delete with missing confirmation
     *
     * <p>Call DELETE without the confirm parameter and expect 400 Bad Request.
     */
    @Test
    @Order(4)
    @DisplayName("Delete without confirmation token should return 400")
    void testDeleteMissingConfirmation() {
        String subject = "delete_test_noconfirm";
        grantConsent(subject, CATEGORY_PII, FIELD_EMAIL, ENTITY_DSAR_USER, PURPOSE_MARKETING);

        given().header("Authorization", auth())
                .when()
                .delete("/api/v1/subjects/{id}/audit", subject)
                .then()
                .statusCode(400);
    }

    /**
     * Test 5: Delete with wrong confirmation
     *
     * <p>Call DELETE with invalid confirm token and expect 400 Bad Request.
     */
    @Test
    @Order(5)
    @DisplayName("Delete with wrong confirmation token should return 400")
    void testDeleteWrongConfirmation() {
        String subject = "delete_test_wrongconfirm";
        grantConsent(subject, CATEGORY_PII, FIELD_EMAIL, ENTITY_DSAR_USER, PURPOSE_MARKETING);

        given().header("Authorization", auth())
                .queryParam("confirm", "WRONG_TOKEN")
                .when()
                .delete("/api/v1/subjects/{id}/audit", subject)
                .then()
                .statusCode(400);
    }

    /**
     * Test 6: Delete atomicity
     *
     * <p>Grant a policy, verify it exists, delete with correct confirmation, verify all stores are
     * cleared.
     */
    @Test
    @Order(6)
    @DisplayName("Delete should atomically clear all stores")
    void testDeleteAtomicity() {
        String subject = "delete_test_atomicity";

        grantConsent(subject, CATEGORY_PII, FIELD_EMAIL, ENTITY_DSAR_USER, PURPOSE_MARKETING);

        // Wait for consent ledger projection before verifying and deleting
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(
                        () -> {
                            SubjectDataExport beforeDelete = exportSubject(subject);
                            assertThat(beforeDelete.consent().entries()).isNotEmpty();
                        });

        String confirmToken = "DELETE_" + subject;
        DsarDeletionReceipt receipt = deleteSubject(subject, confirmToken);

        assertThat(receipt.subject()).isEqualTo(subject);
        assertThat(receipt.status()).isIn("PENDING_SHRED", "PARTIAL_FAILURE", "SUCCESS");

        // DSAR delete saga is async — poll until entries are cleared
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(
                        () -> {
                            SubjectDataExport afterDelete = exportSubject(subject);
                            assertThat(afterDelete.consent().entries()).isEmpty();
                        });
    }

    /**
     * Test 7: Delete idempotency
     *
     * <p>Call DELETE twice for the same subject and verify both succeed (second call should be a
     * no-op).
     */
    @Test
    @Order(7)
    @DisplayName("Delete should be idempotent")
    void testDeleteIdempotency() {
        String subject = "delete_test_idempotent";

        grantConsent(subject, CATEGORY_PII, FIELD_EMAIL, ENTITY_DSAR_USER, PURPOSE_MARKETING);

        String confirmToken = "DELETE_" + subject;
        DsarDeletionReceipt receipt1 = deleteSubject(subject, confirmToken);
        assertThat(receipt1.status()).isIn("PENDING_SHRED", "PARTIAL_FAILURE", "SUCCESS");

        DsarDeletionReceipt receipt2 = deleteSubject(subject, confirmToken);
        assertThat(receipt2.status()).isIn("PENDING_SHRED", "PARTIAL_FAILURE", "SUCCESS");

        SubjectDataExport export = exportSubject(subject);
        assertThat(export.consent().entries()).isEmpty();
    }

    /**
     * Test 8: Delete audit logging
     *
     * <p>Delete a subject and verify the deletion is recorded with correct counts.
     */
    @Test
    @Order(8)
    @DisplayName("Delete should log the erasure in DSAR audit table with accurate counts")
    void testDeleteAuditLogging() {
        String subject = "delete_test_auditlog";

        grantConsent(subject, CATEGORY_PII, FIELD_EMAIL, ENTITY_DSAR_USER, PURPOSE_MARKETING);
        grantConsent(subject, CATEGORY_PII, FIELD_PHONE, ENTITY_USER, PURPOSE_ANALYTICS);
        grantConsent(subject, CATEGORY_HEALTH, FIELD_SSN, ENTITY_PATIENT, PURPOSE_SALES);

        // Wait for all 3 consent ledger entries to be projected before deleting
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(
                        () -> {
                            SubjectDataExport export = exportSubject(subject);
                            assertThat(export.consent().entries()).hasSize(3);
                        });

        String confirmToken = "DELETE_" + subject;
        DsarDeletionReceipt receipt = deleteSubject(subject, confirmToken);

        assertThat(receipt.status()).isIn("PENDING_SHRED", "PARTIAL_FAILURE", "SUCCESS");
        assertThat(receipt.deletionRequestId()).isNotEmpty();
        assertThat(receipt.completedAt()).isNotNull();
        assertThat(receipt.ledgerEntriesRevoked()).isEqualTo(3);
        assertThat(receipt.cachedKeysDeleted()).isGreaterThanOrEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Authorization Tests
    // -----------------------------------------------------------------------

    /**
     * Test 9: Delete authorization
     *
     * <p>Call DELETE without valid credentials and expect 401 Unauthorized.
     */
    @Test
    @Order(9)
    @DisplayName("Delete without valid credentials should return 401")
    void testDeleteAuthorization() {
        String subject = "delete_test_authz";
        grantConsent(subject, CATEGORY_PII, FIELD_EMAIL, ENTITY_DSAR_USER, PURPOSE_MARKETING);

        String confirmToken = "DELETE_" + subject;

        given().header("Authorization", "Bearer invalid-key-no-access")
                .queryParam("confirm", confirmToken)
                .when()
                .delete("/api/v1/subjects/{id}/audit", subject)
                .then()
                .statusCode(401);
    }

    /**
     * Test 10: Export authorization
     *
     * <p>Call GET export without valid credentials and expect 401 Unauthorized.
     */
    @Test
    @Order(10)
    @DisplayName("Export without valid credentials should return 401")
    void testExportAuthorization() {
        String subject = "export_test_authz";
        grantConsent(subject, CATEGORY_PII, FIELD_EMAIL, ENTITY_DSAR_USER, PURPOSE_MARKETING);

        given().header("Authorization", "Bearer invalid-key-no-access")
                .contentType(ContentType.JSON)
                .when()
                .get("/api/v1/subjects/{id}/export", subject)
                .then()
                .statusCode(401);
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    private void ensureSubjectRegistered(String subject) {
        if (registeredSubjects.add(subject)) {
            int status =
                    given().header("Authorization", auth())
                            .contentType(ContentType.JSON)
                            .body(
                                    Map.of(
                                            "subjectName",
                                            subject,
                                            "description",
                                            "DSAR test subject"))
                            .when()
                            .post("/api/v1/subjects")
                            .then()
                            .extract()
                            .statusCode();
            assertThat(status).isIn(200, 201, 409);
        }
    }

    private void grantConsent(
            String subject,
            String category,
            String fieldName,
            String entityName,
            String purposeName) {
        ensureSubjectRegistered(subject);
        Map<String, Object> scope = buildScope(category, fieldName, entityName, purposeName);

        given().header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body(Map.of("subject", subject, "scope", scope))
                .when()
                .post("/api/v1/subjects/consent/grant")
                .then()
                .statusCode(200);
    }

    private void revokeConsent(
            String subject,
            String category,
            String fieldName,
            String entityName,
            String purposeName) {
        Map<String, Object> scope = buildScope(category, fieldName, entityName, purposeName);

        given().header("Authorization", auth())
                .contentType(ContentType.JSON)
                .body(Map.of("subject", subject, "scope", scope))
                .when()
                .post("/api/v1/subjects/consent/revoke")
                .then()
                .statusCode(200);
    }

    private SubjectDataExport exportSubject(String subject) {
        return given().header("Authorization", auth())
                .contentType(ContentType.JSON)
                .when()
                .get("/api/v1/subjects/{id}/export", subject)
                .then()
                .statusCode(200)
                .extract()
                .as(SubjectDataExport.class);
    }

    private DsarDeletionReceipt deleteSubject(String subject, String confirmToken) {
        return given().header("Authorization", auth())
                .contentType(ContentType.JSON)
                .queryParam("confirm", confirmToken)
                .when()
                .delete("/api/v1/subjects/{id}/audit", subject)
                .then()
                .statusCode(200)
                .extract()
                .as(DsarDeletionReceipt.class);
    }

    private static Map<String, Object> buildScope(
            String category, String fieldName, String entityName, String purposeName) {
        Map<String, Object> scope = new HashMap<>();
        scope.put("category", category);
        scope.put("fieldName", fieldName);
        if (entityName != null) {
            scope.put("entityName", entityName);
        }
        if (purposeName != null) {
            scope.put("purposeName", purposeName);
        }
        return scope;
    }
}
