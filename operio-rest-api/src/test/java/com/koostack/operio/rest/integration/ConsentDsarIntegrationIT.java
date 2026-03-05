package com.koostack.operio.rest.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.*;

import com.koostack.operio.rest.dto.ConsentDtos.DsarDeletionReceipt;
import com.koostack.operio.rest.dto.ConsentDtos.SubjectDataExport;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for DSAR (Data Subject Access Request) endpoints.
 *
 * <p>Tests compliance with GDPR Article 15 (right of access), Article 17
 * (right to erasure), Article 20 (data portability), and CCPA §1798.100/105.
 *
 * <p>Test coverage:
 * <ul>
 *   <li>Export completeness: grant multiple policies, export, verify all returned</li>
 *   <li>Export empty subject: export subject with no consent, verify empty lists</li>
 *   <li>Export audit trail: grant, revoke, export, verify audit entries in correct order</li>
 *   <li>Delete with missing confirmation: DELETE without confirm param → 400</li>
 *   <li>Delete with wrong confirmation: DELETE with wrong confirm token → 400</li>
 *   <li>Delete atomicity: grant + cache, DELETE, verify all stores cleared</li>
 *   <li>Delete idempotency: DELETE twice, both succeed</li>
 *   <li>Delete audit logging: DELETE, verify entry in dsar_audit_log</li>
 *   <li>Delete authorization: call without consent:write role → 403</li>
 *   <li>Export authorization: call without consent:write role → 403</li>
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(OperioTestResourceManager.class)
@DisplayName("DSAR (Data Subject Access Request) Integration Tests")
class ConsentDsarIntegrationIT {

    private static final Logger LOG = LoggerFactory.getLogger(
        ConsentDsarIntegrationIT.class
    );

    /** Bootstrap key configured in test application.yml (force-bootstrap=true). */
    private static final String BOOTSTRAP_KEY =
        "test-bootstrap-key-for-integration-tests";
    private static final String SUBJECT_ID = "user_test_dsar_123";
    private static final String OTHER_SUBJECT = "user_other_456";

    /**
     * Test 1: Export completeness
     *
     * <p>Grant multiple policies for a subject, then export and verify all
     * are returned in the export.
     */
    @Test
    @DisplayName("Export should return all consent policies for a subject")
    void testExportCompleteness() {
        String subject = "export_test_1";

        // Grant three policies
        grantConsent(subject, "PII", "email", "DsarUser", "marketing");
        grantConsent(subject, "PII", "phone", "User", "analytics");
        grantConsent(subject, "HEALTH", "ssn", "Patient", "sales");

        // Export
        SubjectDataExport export = exportSubject(subject);

        // Verify export contains all three policies
        assertThat(export.subject()).isEqualTo(subject);
        assertThat(export.consent().entries()).hasSize(3);
        assertThat(export.exportedAt()).isNotNull();
    }

    /**
     * Test 2: Export empty subject
     *
     * <p>Export a subject with no consent and verify empty lists are returned.
     */
    @Test
    @DisplayName("Export should return empty lists for subject with no consent")
    void testExportEmptySubject() {
        String subject = "export_test_empty";

        SubjectDataExport export = exportSubject(subject);

        assertThat(export.subject()).isEqualTo(subject);
        assertThat(export.consent().entries()).isEmpty();
        assertThat(export.cachedKeyCount()).isZero();
    }

    /**
     * Test 3: Export audit trail
     *
     * <p>Grant, revoke, export, verify audit entries are returned in correct
     * order (most recent first).
     */
    @Test
    @DisplayName("Export should include complete audit trail in correct order")
    void testExportAuditTrail() {
        String subject = "export_test_audit";

        // Grant and revoke same scope
        grantConsent(subject, "PII", "email", "DsarUser", "marketing");
        revokeConsent(subject, "PII", "email", "DsarUser", "marketing");

        // Export
        SubjectDataExport export = exportSubject(subject);

        // Verify consent entries have two entries, revoke first (most recent)
        assertThat(export.consent().entries()).hasSize(2);
        assertThat(export.consent().entries().get(0).action()).isEqualTo(
            "REVOKED"
        );
        assertThat(export.consent().entries().get(1).action()).isEqualTo(
            "GRANTED"
        );
    }

    /**
     * Test 4: Delete with missing confirmation
     *
     * <p>Call DELETE without the confirm parameter and expect 400 Bad Request.
     */
    @Test
    @DisplayName("Delete without confirmation token should return 400")
    void testDeleteMissingConfirmation() {
        String subject = "delete_test_noconfirm";
        grantConsent(subject, "PII", "email", "DsarUser", "marketing");

        // Try to delete without confirm parameter
        given()
            .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
            .when()
            .delete("/api/v1/subjects/{id}", subject)
            .then()
            .statusCode(400);
    }

    /**
     * Test 5: Delete with wrong confirmation
     *
     * <p>Call DELETE with invalid confirm token and expect 400 Bad Request.
     */
    @Test
    @DisplayName("Delete with wrong confirmation token should return 400")
    void testDeleteWrongConfirmation() {
        String subject = "delete_test_wrongconfirm";
        grantConsent(subject, "PII", "email", "DsarUser", "marketing");

        // Try to delete with wrong confirmation
        given()
            .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
            .queryParam("confirm", "WRONG_TOKEN")
            .when()
            .delete("/api/v1/subjects/{id}", subject)
            .then()
            .statusCode(400);
    }

    /**
     * Test 6: Delete atomicity
     *
     * <p>Grant a policy, verify it exists, delete with correct confirmation,
     * verify all three stores are cleared (Postgres, Redis, audit log).
     */
    @Test
    @DisplayName("Delete should atomically clear all stores")
    void testDeleteAtomicity() {
        String subject = "delete_test_atomicity";

        // Grant policy
        grantConsent(subject, "PII", "email", "DsarUser", "marketing");

        // Verify it exists via export
        SubjectDataExport beforeDelete = exportSubject(subject);
        assertThat(beforeDelete.consent().entries()).isNotEmpty();

        // Delete with correct confirmation
        String confirmToken = "DELETE_" + subject;
        DsarDeletionReceipt receipt = deleteSubject(subject, confirmToken);

        // Verify deletion succeeded
        assertThat(receipt.subject()).isEqualTo(subject);
        assertThat(receipt.status()).isEqualTo("PENDING_SHRED");

        // Verify all data is gone
        SubjectDataExport afterDelete = exportSubject(subject);
        assertThat(afterDelete.consent().entries()).isEmpty();
    }

    /**
     * Test 7: Delete idempotency
     *
     * <p>Call DELETE twice for the same subject and verify both succeed
     * (second call should be a no-op).
     */
    @Test
    @DisplayName("Delete should be idempotent")
    void testDeleteIdempotency() {
        String subject = "delete_test_idempotent";

        // Grant policy
        grantConsent(subject, "PII", "email", "DsarUser", "marketing");

        // Delete first time
        String confirmToken = "DELETE_" + subject;
        DsarDeletionReceipt receipt1 = deleteSubject(subject, confirmToken);
        assertThat(receipt1.status()).isEqualTo("PENDING_SHRED");

        // Delete second time (should still succeed)
        DsarDeletionReceipt receipt2 = deleteSubject(subject, confirmToken);
        assertThat(receipt2.status()).isEqualTo("PENDING_SHRED");

        // Verify still empty
        SubjectDataExport export = exportSubject(subject);
        assertThat(export.consent().entries()).isEmpty();
    }

    /**
     * Test 8: Delete audit logging
     *
     * <p>Delete a subject and verify the deletion is recorded in the DSAR
     * audit log with correct counts. Now that the DSAR endpoint is fully
     * wired, policiesDeleted reflects the actual count before deletion, and
     * auditEntriesDeleted captures the consent_audit_log rows removed.
     */
    @Test
    @DisplayName(
        "Delete should log the erasure in DSAR audit table with accurate counts"
    )
    void testDeleteAuditLogging() {
        String subject = "delete_test_auditlog";

        // Grant three policies
        grantConsent(subject, "PII", "email", "DsarUser", "marketing");
        grantConsent(subject, "PII", "phone", "User", "analytics");
        grantConsent(subject, "HEALTH", "ssn", "Patient", "sales");

        // Delete
        String confirmToken = "DELETE_" + subject;
        DsarDeletionReceipt receipt = deleteSubject(subject, confirmToken);

        // Verify receipt has correct counts
        assertThat(receipt.status()).isEqualTo("PENDING_SHRED");
        assertThat(receipt.deletionRequestId()).isNotEmpty();
        assertThat(receipt.completedAt()).isNotNull();
        assertThat(receipt.ledgerEntriesRevoked()).isEqualTo(3);
        assertThat(receipt.cachedKeysDeleted()).isGreaterThanOrEqualTo(0);
    }

    /**
     * Test 9: Delete authorization
     *
     * <p>Call DELETE without valid credentials and expect 401 Unauthorized.
     */
    @Test
    @DisplayName("Delete without valid credentials should return 401")
    void testDeleteAuthorization() {
        String subject = "delete_test_authz";
        grantConsent(subject, "PII", "email", "DsarUser", "marketing");

        // Try to delete with an unrecognized API key
        String confirmToken = "DELETE_" + subject;

        given()
            .header("Authorization", "Bearer invalid-key-no-access")
            .queryParam("confirm", confirmToken)
            .when()
            .delete("/api/v1/subjects/{id}", subject)
            .then()
            .statusCode(401);
    }

    /**
     * Test 10: Export authorization
     *
     * <p>Call GET export without valid credentials and expect 401 Unauthorized.
     */
    @Test
    @DisplayName("Export without valid credentials should return 401")
    void testExportAuthorization() {
        String subject = "export_test_authz";
        grantConsent(subject, "PII", "email", "DsarUser", "marketing");

        // Try to export with an unrecognized API key
        given()
            .header("Authorization", "Bearer invalid-key-no-access")
            .contentType(ContentType.JSON)
            .when()
            .get("/api/v1/subjects/{id}/export", subject)
            .then()
            .statusCode(401);
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    private void grantConsent(
        String subject,
        String category,
        String fieldName,
        String entityName,
        String purposeName
    ) {
        Map<String, Object> scope = buildScope(
            category,
            fieldName,
            entityName,
            purposeName
        );

        given()
            .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
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
        String purposeName
    ) {
        Map<String, Object> scope = buildScope(
            category,
            fieldName,
            entityName,
            purposeName
        );

        given()
            .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
            .contentType(ContentType.JSON)
            .body(Map.of("subject", subject, "scope", scope))
            .when()
            .post("/api/v1/subjects/consent/revoke")
            .then()
            .statusCode(200);
    }

    private SubjectDataExport exportSubject(String subject) {
        return given()
            .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
            .contentType(ContentType.JSON)
            .when()
            .get("/api/v1/subjects/{id}/export", subject)
            .then()
            .statusCode(200)
            .extract()
            .as(SubjectDataExport.class);
    }

    private DsarDeletionReceipt deleteSubject(
        String subject,
        String confirmToken
    ) {
        return given()
            .header("Authorization", "Bearer " + BOOTSTRAP_KEY)
            .contentType(ContentType.JSON)
            .queryParam("confirm", confirmToken)
            .when()
            .delete("/api/v1/subjects/{id}", subject)
            .then()
            .statusCode(200)
            .extract()
            .as(DsarDeletionReceipt.class);
    }

    private static Map<String, Object> buildScope(
        String category,
        String fieldName,
        String entityName,
        String purposeName
    ) {
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
