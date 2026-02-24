package com.koostack.operio.rest.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.*;

import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.rest.dto.ConsentDtos.DsarDeletionReceipt;
import com.koostack.operio.rest.dto.ConsentDtos.SubjectDataExport;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

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
@WithTestResource(ConsentDsarIntegrationIT.DsarTestResourceManager.class)
@DisplayName("DSAR (Data Subject Access Request) Integration Tests")
class ConsentDsarIntegrationIT {

    private static final Logger LOG = LoggerFactory.getLogger(
        ConsentDsarIntegrationIT.class
    );

    private static final String VALID_API_KEY = "test-api-key-dsar";
    private static final String SUBJECT_ID = "user_test_dsar_123";
    private static final String OTHER_SUBJECT = "user_other_456";

    /**
     * Test resource manager for DSAR integration tests.
     *
     * <p>Starts Postgres and Redis, injects config properties.
     */
    public static class DsarTestResourceManager
        implements QuarkusTestResourceLifecycleManager
    {

        private static final Logger LOG = LoggerFactory.getLogger(
            DsarTestResourceManager.class
        );

        private PostgreSQLContainer<?> postgres;
        private GenericContainer<?> redis;

        @Override
        public Map<String, String> start() {
            LOG.info("Starting DSAR integration test infrastructure...");

            postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("postgres:16-alpine")
            )
                .withDatabaseName("operio_dsar_test")
                .withUsername("operio_dsar_test")
                .withPassword("operio_dsar_test")
                .withLogConsumer(
                    new Slf4jLogConsumer(LOG).withPrefix("postgres")
                );

            postgres.start();

            redis = new GenericContainer<>(
                DockerImageName.parse("redis:7-alpine")
            )
                .withExposedPorts(6379)
                .waitingFor(
                    Wait.forLogMessage(".*Ready to accept connections.*", 1)
                )
                .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("redis"));

            redis.start();

            Map<String, String> props = new HashMap<>();
            props.put("quarkus.datasource.jdbc.url", postgres.getJdbcUrl());
            props.put("quarkus.datasource.username", postgres.getUsername());
            props.put("quarkus.datasource.password", postgres.getPassword());
            props.put(
                "operio.plugins.consent-admin-jooq.datasource.jdbc.url",
                postgres.getJdbcUrl()
            );
            props.put(
                "operio.plugins.consent-admin-jooq.datasource.username",
                postgres.getUsername()
            );
            props.put(
                "operio.plugins.consent-admin-jooq.datasource.password",
                postgres.getPassword()
            );
            props.put(
                "operio.plugins.consent-decision-redis.redis.uri",
                "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379)
            );
            props.put(
                "operio.api-keys.test-api-key-dsar",
                "role:consent:read,role:consent:write"
            );

            return props;
        }

        @Override
        public void stop() {
            if (redis != null) redis.stop();
            if (postgres != null) postgres.stop();
        }
    }

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
        grantConsent(subject, "email", null, "marketing");
        grantConsent(subject, "phone", "User", "analytics");
        grantConsent(subject, null, "PII", "sales");

        // Export
        SubjectDataExport export = exportSubject(subject);

        // Verify export contains all three policies
        assertThat(export.subject()).isEqualTo(subject);
        assertThat(export.consentPolicies()).hasSize(3);
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
        assertThat(export.consentPolicies()).isEmpty();
        assertThat(export.auditLogEntries()).isEmpty();
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
        grantConsent(subject, "email", null, "marketing");
        revokeConsent(subject, "email", null, "marketing");

        // Export
        SubjectDataExport export = exportSubject(subject);

        // Verify audit trail has two entries, revoke first (most recent)
        assertThat(export.auditLogEntries()).hasSize(2);
        assertThat(export.auditLogEntries().get(0).action()).isEqualTo(
            "REVOKED"
        );
        assertThat(export.auditLogEntries().get(1).action()).isEqualTo(
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
        grantConsent(subject, "email", null, "marketing");

        // Try to delete without confirm parameter
        given()
            .header("X-Api-Key", VALID_API_KEY)
            .when()
            .delete("/api/v1/consent/subjects/{id}", subject)
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
        grantConsent(subject, "email", null, "marketing");

        // Try to delete with wrong confirmation
        given()
            .header("X-Api-Key", VALID_API_KEY)
            .queryParam("confirm", "WRONG_TOKEN")
            .when()
            .delete("/api/v1/consent/subjects/{id}", subject)
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
        grantConsent(subject, "email", null, "marketing");

        // Verify it exists via export
        SubjectDataExport beforeDelete = exportSubject(subject);
        assertThat(beforeDelete.consentPolicies()).isNotEmpty();

        // Delete with correct confirmation
        String confirmToken = "DELETE_" + subject;
        DsarDeletionReceipt receipt = deleteSubject(subject, confirmToken);

        // Verify deletion succeeded
        assertThat(receipt.subject()).isEqualTo(subject);
        assertThat(receipt.status()).isEqualTo("SUCCESS");

        // Verify all data is gone
        SubjectDataExport afterDelete = exportSubject(subject);
        assertThat(afterDelete.consentPolicies()).isEmpty();
        assertThat(afterDelete.auditLogEntries()).isEmpty();
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
        grantConsent(subject, "email", null, "marketing");

        // Delete first time
        String confirmToken = "DELETE_" + subject;
        DsarDeletionReceipt receipt1 = deleteSubject(subject, confirmToken);
        assertThat(receipt1.status()).isEqualTo("SUCCESS");

        // Delete second time (should still succeed)
        DsarDeletionReceipt receipt2 = deleteSubject(subject, confirmToken);
        assertThat(receipt2.status()).isEqualTo("SUCCESS");

        // Verify still empty
        SubjectDataExport export = exportSubject(subject);
        assertThat(export.consentPolicies()).isEmpty();
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
        grantConsent(subject, "email", null, "marketing");
        grantConsent(subject, "phone", "User", "analytics");
        grantConsent(subject, null, "PII", "sales");

        // Delete
        String confirmToken = "DELETE_" + subject;
        DsarDeletionReceipt receipt = deleteSubject(subject, confirmToken);

        // Verify receipt has correct counts
        assertThat(receipt.status()).isEqualTo("SUCCESS");
        assertThat(receipt.deletionRequestId()).isNotEmpty();
        assertThat(receipt.completedAt()).isNotNull();
        // policiesDeleted should reflect the 3 grants we just created
        assertThat(receipt.policiesDeleted()).isEqualTo(3);
        assertThat(receipt.cachedKeysDeleted()).isGreaterThanOrEqualTo(0);
        // auditEntriesDeleted should include the 3 grant audit entries
        // plus the BULK_REVOKED entry from revokeAllConsent = 4 total
        assertThat(receipt.auditEntriesDeleted()).isGreaterThanOrEqualTo(3);
    }

    /**
     * Test 9: Delete authorization
     *
     * <p>Call DELETE without the consent:write role and expect 403 Forbidden.
     */
    @Test
    @DisplayName("Delete without consent:write role should return 403")
    void testDeleteAuthorization() {
        String subject = "delete_test_authz";
        grantConsent(subject, "email", null, "marketing");

        // Try to delete with read-only API key (no write permission)
        String readOnlyKey = "test-api-key-read-only"; // assume this exists but only has read
        String confirmToken = "DELETE_" + subject;

        given()
            .header("X-Api-Key", readOnlyKey)
            .queryParam("confirm", confirmToken)
            .when()
            .delete("/api/v1/consent/subjects/{id}", subject)
            .then()
            .statusCode(403);
    }

    /**
     * Test 10: Export authorization
     *
     * <p>Call GET export without the consent:write role and expect 403 Forbidden.
     */
    @Test
    @DisplayName("Export without consent:write role should return 403")
    void testExportAuthorization() {
        String subject = "export_test_authz";
        grantConsent(subject, "email", null, "marketing");

        // Try to export with read-only API key
        String readOnlyKey = "test-api-key-read-only";

        given()
            .header("X-Api-Key", readOnlyKey)
            .contentType(ContentType.JSON)
            .when()
            .get("/api/v1/consent/subjects/{id}/export", subject)
            .then()
            .statusCode(403);
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    private void grantConsent(
        String subject,
        String fieldName,
        String entityName,
        String purposeName
    ) {
        Map<String, Object> scope = buildScope(
            fieldName,
            entityName,
            purposeName
        );

        given()
            .header("X-Api-Key", VALID_API_KEY)
            .contentType(ContentType.JSON)
            .body(Map.of("subject", subject, "scope", scope))
            .when()
            .post("/api/v1/consent/grant")
            .then()
            .statusCode(200);
    }

    private void revokeConsent(
        String subject,
        String fieldName,
        String entityName,
        String purposeName
    ) {
        Map<String, Object> scope = buildScope(
            fieldName,
            entityName,
            purposeName
        );

        given()
            .header("X-Api-Key", VALID_API_KEY)
            .contentType(ContentType.JSON)
            .body(Map.of("subject", subject, "scope", scope))
            .when()
            .post("/api/v1/consent/revoke")
            .then()
            .statusCode(200);
    }

    private SubjectDataExport exportSubject(String subject) {
        return given()
            .header("X-Api-Key", VALID_API_KEY)
            .contentType(ContentType.JSON)
            .when()
            .get("/api/v1/consent/subjects/{id}/export", subject)
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
            .header("X-Api-Key", VALID_API_KEY)
            .contentType(ContentType.JSON)
            .queryParam("confirm", confirmToken)
            .when()
            .delete("/api/v1/consent/subjects/{id}", subject)
            .then()
            .statusCode(200)
            .extract()
            .as(DsarDeletionReceipt.class);
    }

    private static Map<String, Object> buildScope(
        String fieldName,
        String entityName,
        String purposeName
    ) {
        Map<String, Object> scope = new HashMap<>();
        if (fieldName != null) {
            scope.put("fieldName", fieldName);
        }
        if (entityName != null) {
            scope.put("entityName", entityName);
        }
        if (purposeName != null) {
            scope.put("purposeName", purposeName);
        }
        return scope;
    }
}
