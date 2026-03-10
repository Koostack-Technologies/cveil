package com.koostack.operio.rest.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * End-to-end integration test for the full create-tenant pipeline.
 *
 * <p>Validates the entire flow from HTTP POST through event sourcing, projections, and API key
 * generation under the unified schema architecture (RLS-based isolation, single event store).
 *
 * <h2>What this test verifies</h2>
 *
 * <ol>
 *   <li>POST /api/v1/platform/tenants returns 201
 *   <li>Tenant row materializes in platform_core.tenant
 *   <li>Billing profile and subscription rows exist
 *   <li>TenantCreated event present in unified event store (operio_event.event)
 *   <li>Billing events present in unified event store
 *   <li>3 API keys (DEV, TEST, PROD) in platform_iam.api_key
 *   <li>Tenant status reflects creation
 * </ol>
 *
 * <p>Keycloak is not required — the test runs without an IdpProvider. Authentication uses the
 * bootstrap key configured in test application.yml.
 *
 * @see OperioTestResourceManager
 */
@QuarkusTest
@QuarkusTestResource(OperioTestResourceManager.class)
@DisplayName("Create Tenant Pipeline E2E (Unified Schema + RLS)")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CreateTenantPipelineIT {

    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";

    private static final String TENANT_ID = "pipeline_test_tenant";
    private static final String TENANT_DISPLAY = "Pipeline Test Tenant";
    private static final String TENANT_PLAN = "pro";
    private static final String BILLING_EMAIL = "billing@pipeline-test.com";

    // Async operations timeout
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    // -----------------------------------------------------------------------
    // DB helper — direct JDBC to verify projections and event store
    // -----------------------------------------------------------------------

    private static Connection getDbConnection() throws SQLException {
        var config = org.eclipse.microprofile.config.ConfigProvider.getConfig();
        String url = config.getValue("operio.test.platform-db.url", String.class);
        String user = config.getValue("operio.test.platform-db.username", String.class);
        String pass = config.getValue("operio.test.platform-db.password", String.class);
        return DriverManager.getConnection(url, user, pass);
    }

    // =======================================================================
    // 1. Create Tenant via REST API
    // =======================================================================

    @Nested
    @Order(1)
    @DisplayName("1 — POST /api/v1/platform/tenants returns 201")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class CreateTenantHttp {

        @Test
        @Order(1)
        @DisplayName("Create tenant returns 201 with correct body")
        void createTenantReturns201() {
            given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                    .contentType(ContentType.JSON)
                    .body(
                            String.format(
                                    """
                    {
                      "tenantId": "%s",
                      "displayName": "%s",
                      "plan": "%s",
                      "billingEmail": "%s"
                    }
                    """,
                                    TENANT_ID, TENANT_DISPLAY, TENANT_PLAN, BILLING_EMAIL))
                    .when()
                    .post("/api/v1/platform/tenants")
                    .then()
                    .statusCode(201)
                    .contentType(ContentType.JSON)
                    .body("tenantId", equalTo(TENANT_ID))
                    .body("displayName", equalTo(TENANT_DISPLAY))
                    .body("plan", equalTo(TENANT_PLAN));
        }

        @Test
        @Order(2)
        @DisplayName("Duplicate tenant creation is rejected")
        void duplicateTenantRejected() {
            // Wait for the projection to catch up before attempting duplicate
            await().atMost(ASYNC_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(
                            () -> {
                                try (Connection conn = getDbConnection();
                                        Statement st = conn.createStatement()) {
                                    ResultSet rs =
                                            st.executeQuery(
                                                    "SELECT 1 FROM platform_core.tenant WHERE"
                                                            + " tenant_id = '"
                                                            + TENANT_ID
                                                            + "'");
                                    assertThat(rs.next())
                                            .as("Projection must have the tenant row")
                                            .isTrue();
                                }
                            });

            given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                    .contentType(ContentType.JSON)
                    .body(
                            String.format(
                                    """
                    {
                      "tenantId": "%s",
                      "displayName": "Dup",
                      "plan": "%s",
                      "billingEmail": "dup@test.com"
                    }
                    """,
                                    TENANT_ID, TENANT_PLAN))
                    .when()
                    .post("/api/v1/platform/tenants")
                    .then()
                    .statusCode(anyOf(is(409), is(400), is(500), is(503)));
        }
    }

    // =======================================================================
    // 2. Event Store Validation (unified operio_event.event table)
    // =======================================================================

    @Nested
    @Order(2)
    @DisplayName("2 — Event Store contains expected events")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class EventStoreValidation {

        @Test
        @Order(1)
        @DisplayName("TenantCreated event exists in event store")
        void tenantCreatedEventExists() throws SQLException {
            try (Connection conn = getDbConnection();
                    Statement stmt = conn.createStatement()) {
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT COUNT(*) FROM operio_event.event "
                                        + "WHERE event_type = 'core.tenant.created' "
                                        + "AND payload::text LIKE '%"
                                        + TENANT_ID
                                        + "%'");
                rs.next();
                assertThat(rs.getInt(1))
                        .as("TenantCreated event for %s", TENANT_ID)
                        .isGreaterThanOrEqualTo(1);
            }
        }

        @Test
        @Order(2)
        @DisplayName("Billing events exist in event store")
        void billingEventsExist() throws SQLException {
            try (Connection conn = getDbConnection();
                    Statement stmt = conn.createStatement()) {
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT COUNT(*) FROM operio_event.event "
                                        + "WHERE event_type LIKE 'billing.%' "
                                        + "AND payload::text LIKE '%"
                                        + TENANT_ID
                                        + "%'");
                rs.next();
                assertThat(rs.getInt(1))
                        .as("Billing events for %s", TENANT_ID)
                        .isGreaterThanOrEqualTo(2); // profile + subscription
            }
        }
    }

    // =======================================================================
    // 3. Projection Validation (async — requires polling)
    // =======================================================================

    @Nested
    @Order(3)
    @DisplayName("3 — Core & Billing projections materialize")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ProjectionValidation {

        @Test
        @Order(1)
        @DisplayName("Tenant row materializes in platform_core.tenant")
        void tenantRowMaterializes() {
            await().atMost(ASYNC_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(
                            () -> {
                                try (Connection conn = getDbConnection();
                                        Statement stmt = conn.createStatement()) {
                                    ResultSet rs =
                                            stmt.executeQuery(
                                                    "SELECT tenant_id, plan, status FROM"
                                                        + " platform_core.tenant WHERE tenant_id ="
                                                        + " '"
                                                            + TENANT_ID
                                                            + "'");
                                    assertThat(rs.next())
                                            .as("Tenant row exists for %s", TENANT_ID)
                                            .isTrue();
                                    assertThat(rs.getString("plan")).isEqualTo(TENANT_PLAN);
                                }
                            });
        }

        @Test
        @Order(2)
        @DisplayName("Billing profile row exists")
        void billingProfileExists() {
            await().atMost(ASYNC_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(
                            () -> {
                                try (Connection conn = getDbConnection();
                                        Statement stmt = conn.createStatement()) {
                                    ResultSet rs =
                                            stmt.executeQuery(
                                                    "SELECT tenant_id, billing_email FROM"
                                                        + " platform_billing.billing_profile WHERE"
                                                        + " tenant_id = '"
                                                            + TENANT_ID
                                                            + "'");
                                    assertThat(rs.next())
                                            .as("Billing profile exists for %s", TENANT_ID)
                                            .isTrue();
                                    assertThat(rs.getString("billing_email"))
                                            .isEqualTo(BILLING_EMAIL);
                                }
                            });
        }

        @Test
        @Order(3)
        @DisplayName("Subscription row exists with plan limits")
        void subscriptionExists() {
            await().atMost(ASYNC_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(
                            () -> {
                                try (Connection conn = getDbConnection();
                                        Statement stmt = conn.createStatement()) {
                                    ResultSet rs =
                                            stmt.executeQuery(
                                                    "SELECT plan_tier, eval_limit_monthly,"
                                                        + " subject_limit, burst_limit_per_min FROM"
                                                        + " platform_billing.subscription WHERE"
                                                        + " tenant_id = '"
                                                            + TENANT_ID
                                                            + "'");
                                    assertThat(rs.next())
                                            .as("Subscription exists for %s", TENANT_ID)
                                            .isTrue();
                                    assertThat(rs.getString("plan_tier")).isEqualTo(TENANT_PLAN);
                                    assertThat(rs.getLong("eval_limit_monthly")).isGreaterThan(0);
                                    assertThat(rs.getLong("subject_limit")).isGreaterThan(0);
                                    assertThat(rs.getInt("burst_limit_per_min")).isGreaterThan(0);
                                }
                            });
        }

        @Test
        @Order(4)
        @DisplayName("Tenant data consistent across projection tables")
        void tenantDataConsistent() {
            await().atMost(ASYNC_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(
                            () -> {
                                try (Connection conn = getDbConnection();
                                        Statement stmt = conn.createStatement()) {
                                    ResultSet rs =
                                            stmt.executeQuery(
                                                    "SELECT t.tenant_id, t.plan, b.tenant_id AS"
                                                        + " billing_tid FROM platform_core.tenant t"
                                                        + " JOIN platform_billing.billing_profile b"
                                                        + " ON t.tenant_id = b.tenant_id WHERE"
                                                        + " t.tenant_id = '"
                                                            + TENANT_ID
                                                            + "'");
                                    assertThat(rs.next()).as("Tenant + billing join").isTrue();
                                    assertThat(rs.getString("plan")).isEqualTo(TENANT_PLAN);
                                }
                            });
        }
    }

    // =======================================================================
    // 4. Unified Schema Validation (RLS-based, no per-tenant schemas)
    // =======================================================================

    @Nested
    @Order(4)
    @DisplayName("4 — Unified consent tables exist in operio_consent schema")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class UnifiedSchemaValidation {

        @Test
        @Order(1)
        @DisplayName("All consent tables exist in operio_consent schema")
        void consentTablesExist() throws SQLException {
            try (Connection conn = getDbConnection();
                    Statement stmt = conn.createStatement()) {
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT table_name FROM information_schema.tables "
                                        + "WHERE table_schema = 'operio_consent' "
                                        + "AND table_name NOT LIKE 'flyway%' "
                                        + "ORDER BY table_name");
                List<String> tables = new ArrayList<>();
                while (rs.next()) {
                    tables.add(rs.getString("table_name"));
                }
                assertThat(tables)
                        .as("Unified consent schema should contain all consent tables")
                        .contains(
                                "category",
                                "consent_ledger",
                                "entity",
                                "field",
                                "field_category",
                                "jurisdiction",
                                "policy",
                                "purpose",
                                "purpose_regulation",
                                "regulation",
                                "regulation_jurisdiction",
                                "subject");
            }
        }

        @Test
        @Order(2)
        @DisplayName("Unified event table exists in operio_event schema")
        void unifiedEventTableExists() throws SQLException {
            try (Connection conn = getDbConnection();
                    Statement stmt = conn.createStatement()) {
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT COUNT(*) FROM information_schema.tables "
                                        + "WHERE table_schema = 'operio_event' "
                                        + "AND table_name = 'event'");
                rs.next();
                assertThat(rs.getInt(1)).as("operio_event.event table exists").isEqualTo(1);
            }
        }
    }

    // =======================================================================
    // 5. API Key Generation (async)
    // =======================================================================

    @Nested
    @Order(5)
    @DisplayName("5 — API keys generated (DEV, TEST, PROD)")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ApiKeyGeneration {

        @Test
        @Order(1)
        @DisplayName("3 API keys exist in platform_iam.api_key")
        void threeApiKeysGenerated() {
            await().atMost(ASYNC_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(
                            () -> {
                                try (Connection conn = getDbConnection();
                                        Statement stmt = conn.createStatement()) {
                                    ResultSet rs =
                                            stmt.executeQuery(
                                                    "SELECT COUNT(*) FROM platform_iam.api_key "
                                                            + "WHERE tenant_id = '"
                                                            + TENANT_ID
                                                            + "'");
                                    rs.next();
                                    assertThat(rs.getInt(1))
                                            .as("API key count for %s", TENANT_ID)
                                            .isEqualTo(3);
                                }
                            });
        }

        @Test
        @Order(2)
        @DisplayName("API keys cover all environments (DEV, TEST, PROD)")
        void apiKeysHaveCorrectEnvironments() {
            await().atMost(ASYNC_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(
                            () -> {
                                try (Connection conn = getDbConnection();
                                        Statement stmt = conn.createStatement()) {
                                    ResultSet rs =
                                            stmt.executeQuery(
                                                    "SELECT environment FROM platform_iam.api_key "
                                                            + "WHERE tenant_id = '"
                                                            + TENANT_ID
                                                            + "' "
                                                            + "ORDER BY environment");
                                    List<String> envs = new ArrayList<>();
                                    while (rs.next()) {
                                        envs.add(rs.getString("environment"));
                                    }
                                    assertThat(envs)
                                            .containsExactlyInAnyOrder("DEV", "TEST", "PROD");
                                }
                            });
        }

        @Test
        @Order(3)
        @DisplayName("API keys have key_hash and key_prefix populated")
        void apiKeysHaveHashAndPrefix() throws SQLException {
            try (Connection conn = getDbConnection();
                    Statement stmt = conn.createStatement()) {
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT key_hash, key_prefix, status FROM platform_iam.api_key "
                                        + "WHERE tenant_id = '"
                                        + TENANT_ID
                                        + "'");
                int count = 0;
                while (rs.next()) {
                    count++;
                    assertThat(rs.getString("key_hash"))
                            .as("key_hash for API key %d", count)
                            .isNotNull()
                            .isNotEmpty();
                    assertThat(rs.getString("key_prefix"))
                            .as("key_prefix for API key %d", count)
                            .isNotNull()
                            .startsWith("opr_");
                    assertThat(rs.getString("status"))
                            .as("status for API key %d", count)
                            .isEqualTo("active");
                }
                assertThat(count).isEqualTo(3);
            }
        }

        @Test
        @Order(4)
        @DisplayName("ApiKeyGenerated events exist in event store")
        void apiKeyEventsExist() throws SQLException {
            try (Connection conn = getDbConnection();
                    Statement stmt = conn.createStatement()) {
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT COUNT(*) FROM operio_event.event "
                                        + "WHERE event_type = 'iam.api-key.generated' "
                                        + "AND payload::text LIKE '%"
                                        + TENANT_ID
                                        + "%'");
                rs.next();
                assertThat(rs.getInt(1))
                        .as("ApiKeyGenerated events for %s", TENANT_ID)
                        .isEqualTo(3);
            }
        }
    }

    // =======================================================================
    // 6. Tenant Status Validation
    // =======================================================================

    @Nested
    @Order(6)
    @DisplayName("6 — Tenant status reflects creation")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class TenantStatusValidation {

        @Test
        @Order(1)
        @DisplayName("Tenant status is 'created' (activation happens via separate command)")
        void tenantStatusCreated() {
            await().atMost(ASYNC_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(
                            () -> {
                                try (Connection conn = getDbConnection();
                                        Statement stmt = conn.createStatement()) {
                                    ResultSet rs =
                                            stmt.executeQuery(
                                                    "SELECT status FROM platform_core.tenant "
                                                            + "WHERE tenant_id = '"
                                                            + TENANT_ID
                                                            + "'");
                                    assertThat(rs.next()).isTrue();
                                    assertThat(rs.getString("status"))
                                            .as("Tenant status should be 'created'")
                                            .isEqualTo("created");
                                }
                            });
        }
    }

    // =======================================================================
    // 7. Plan Validation
    // =======================================================================

    @Nested
    @Order(7)
    @DisplayName("7 — Plan validation")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class PlanValidation {

        @Test
        @Order(1)
        @DisplayName("Invalid plan is rejected")
        void invalidPlanRejected() {
            given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                    .contentType(ContentType.JSON)
                    .body(
                            """
                    {
                      "tenantId": "plan_test_tenant",
                      "displayName": "Plan Test",
                      "plan": "nonexistent_plan",
                      "billingEmail": "test@test.com"
                    }
                    """)
                    .when()
                    .post("/api/v1/platform/tenants")
                    .then()
                    .statusCode(anyOf(is(400), is(500), is(503)));
        }

        @Test
        @Order(2)
        @DisplayName("Pre-seeded plans exist in plan table")
        void preSeededPlansExist() throws SQLException {
            try (Connection conn = getDbConnection();
                    Statement stmt = conn.createStatement()) {
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT plan_id, is_active FROM platform_core.plan ORDER BY"
                                        + " sort_order");
                List<String> planIds = new ArrayList<>();
                while (rs.next()) {
                    planIds.add(rs.getString("plan_id"));
                    assertThat(rs.getBoolean("is_active")).isTrue();
                }
                assertThat(planIds).containsExactlyInAnyOrder("free", "pro", "enterprise");
            }
        }
    }
}
