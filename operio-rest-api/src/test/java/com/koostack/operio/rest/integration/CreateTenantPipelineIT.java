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
 * <p>Validates the entire flow from HTTP POST through event sourcing, projections, schema
 * provisioning, Redis ACL, and API key generation.
 *
 * <h2>What this test verifies</h2>
 *
 * <ol>
 *   <li>POST /api/v1/platform/tenants returns 201
 *   <li>Tenant row materializes in platform_core.tenant
 *   <li>Billing profile and subscription rows exist
 *   <li>TenantProvisioningStarted event present in event store
 *   <li>Tenant Postgres schema created with migrations applied
 *   <li>3 API keys (DEV, TEST, PROD) in platform_iam.api_key
 *   <li>TenantProvisioned event present in event store
 * </ol>
 *
 * <p>Keycloak is not required — the test runs without an IdpProvider. Authentication uses the
 * bootstrap key configured in test application.yml.
 *
 * @see OperioTestResourceManager
 */
@QuarkusTest
@QuarkusTestResource(OperioTestResourceManager.class)
@DisplayName("Create Tenant Pipeline E2E (Postgres + Redis)")
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
    // 2. Event Store Validation
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
                                "SELECT COUNT(*) FROM t_platform.event "
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
        @DisplayName("TenantProvisioningStarted event exists in event store")
        void provisioningStartedEventExists() throws SQLException {
            try (Connection conn = getDbConnection();
                    Statement stmt = conn.createStatement()) {
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT COUNT(*) FROM t_platform.event "
                                        + "WHERE event_type = 'core.tenant.provisioning-started' "
                                        + "AND payload::text LIKE '%"
                                        + TENANT_ID
                                        + "%'");
                rs.next();
                assertThat(rs.getInt(1))
                        .as("TenantProvisioningStarted event for %s", TENANT_ID)
                        .isGreaterThanOrEqualTo(1);
            }
        }

        @Test
        @Order(3)
        @DisplayName("Billing events exist in event store")
        void billingEventsExist() throws SQLException {
            try (Connection conn = getDbConnection();
                    Statement stmt = conn.createStatement()) {
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT COUNT(*) FROM t_platform.event "
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
                                    // Pro plan: eval_limit=50000, subject_limit=5000,
                                    // burst_limit=100
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
    // 4. Infrastructure Provisioning (async)
    // =======================================================================

    @Nested
    @Order(4)
    @DisplayName("4 — Tenant infrastructure provisioned")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class InfrastructureProvisioning {

        @Test
        @Order(1)
        @DisplayName("Tenant Postgres schema created (t_{tenantId})")
        void tenantSchemaCreated() {
            await().atMost(ASYNC_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(
                            () -> {
                                try (Connection conn = getDbConnection();
                                        Statement stmt = conn.createStatement()) {
                                    ResultSet rs =
                                            stmt.executeQuery(
                                                    "SELECT COUNT(*) FROM"
                                                            + " information_schema.schemata WHERE"
                                                            + " schema_name = 't_"
                                                            + TENANT_ID
                                                            + "'");
                                    rs.next();
                                    assertThat(rs.getInt(1))
                                            .as("Tenant schema t_%s exists", TENANT_ID)
                                            .isEqualTo(1);
                                }
                            });
        }

        @Test
        @Order(2)
        @DisplayName("Flyway migrations applied to tenant schema")
        void flywayMigrationsApplied() {
            await().atMost(ASYNC_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(
                            () -> {
                                try (Connection conn = getDbConnection();
                                        Statement stmt = conn.createStatement()) {
                                    // Check that flyway_schema_history exists in tenant schema
                                    ResultSet rs =
                                            stmt.executeQuery(
                                                    "SELECT COUNT(*) FROM information_schema.tables"
                                                            + " WHERE table_schema = 't_"
                                                            + TENANT_ID
                                                            + "' AND table_name ="
                                                            + " 'flyway_schema_history'");
                                    rs.next();
                                    assertThat(rs.getInt(1))
                                            .as("Flyway history table in t_%s", TENANT_ID)
                                            .isEqualTo(1);
                                }
                            });
        }

        @Test
        @Order(3)
        @DisplayName("Redis ACL credentials populated in tenant row")
        void redisAclProvisioned() {
            // The CQRS projection stores redis_acl_user (from the
            // TenantProvisioned event).  The plaintext password is NOT
            // propagated through events for security reasons.
            await().atMost(ASYNC_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(
                            () -> {
                                try (Connection conn = getDbConnection();
                                        Statement stmt = conn.createStatement()) {
                                    ResultSet rs =
                                            stmt.executeQuery(
                                                    "SELECT redis_acl_user "
                                                            + "FROM platform_core.tenant "
                                                            + "WHERE tenant_id = '"
                                                            + TENANT_ID
                                                            + "'");
                                    assertThat(rs.next()).isTrue();
                                    assertThat(rs.getString("redis_acl_user"))
                                            .as("Redis ACL user")
                                            .isNotNull()
                                            .isNotEmpty();
                                }
                            });
        }

        @Test
        @Order(4)
        @DisplayName("All expected consent tables exist in tenant schema")
        void consentTablesExist() {
            await().atMost(ASYNC_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(
                            () -> {
                                try (Connection conn = getDbConnection();
                                        Statement stmt = conn.createStatement()) {
                                    ResultSet rs =
                                            stmt.executeQuery(
                                                    "SELECT table_name FROM"
                                                            + " information_schema.tables WHERE"
                                                            + " table_schema = 't_"
                                                            + TENANT_ID
                                                            + "' "
                                                            + "AND table_name NOT LIKE 'flyway%' "
                                                            + "ORDER BY table_name");
                                    java.util.List<String> tables = new java.util.ArrayList<>();
                                    while (rs.next()) {
                                        tables.add(rs.getString("table_name"));
                                    }
                                    // Core consent tables provisioned by V1 migration
                                    assertThat(tables)
                                            .as("Tenant schema should contain all consent tables")
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
                            });
        }
    }

    // =======================================================================
    // 5. API Key Provisioning (async)
    // =======================================================================

    @Nested
    @Order(5)
    @DisplayName("5 — API keys provisioned (DEV, TEST, PROD)")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ApiKeyProvisioning {

        @Test
        @Order(1)
        @DisplayName("3 API keys exist in platform_iam.api_key")
        void threeApiKeysProvisioned() {
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
                                "SELECT COUNT(*) FROM t_platform.event "
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
    // 6. TenantProvisioned Event
    // =======================================================================

    @Nested
    @Order(6)
    @DisplayName("6 — TenantProvisioned event completes pipeline")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ProvisioningComplete {

        @Test
        @Order(1)
        @DisplayName("TenantProvisioned event exists in event store")
        void tenantProvisionedEventExists() {
            await().atMost(ASYNC_TIMEOUT)
                    .pollInterval(POLL_INTERVAL)
                    .untilAsserted(
                            () -> {
                                try (Connection conn = getDbConnection();
                                        Statement stmt = conn.createStatement()) {
                                    ResultSet rs =
                                            stmt.executeQuery(
                                                    "SELECT COUNT(*) FROM t_platform.event WHERE"
                                                        + " event_type = 'core.tenant.provisioned'"
                                                        + " AND payload::text LIKE '%"
                                                            + TENANT_ID
                                                            + "%'");
                                    rs.next();
                                    assertThat(rs.getInt(1))
                                            .as("TenantProvisioned event for %s", TENANT_ID)
                                            .isGreaterThanOrEqualTo(1);
                                }
                            });
        }

        @Test
        @Order(2)
        @DisplayName("Tenant status is active after provisioning")
        void tenantStatusActive() {
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
                                    assertThat(rs.getString("status")).isIn("active", "ACTIVE");
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
