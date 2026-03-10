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
 * End-to-end integration test for the full create-tenant pipeline via user registration.
 *
 * <p>Validates the entire flow from {@code POST /api/v1/platform/iam/users} (with {@code
 * organizationName}) through the 7-step orchestrator saga, event sourcing, and projections under
 * the unified schema architecture (RLS-based isolation, single event store).
 *
 * <h2>What this test verifies</h2>
 *
 * <ol>
 *   <li>POST /api/v1/platform/iam/users with organizationName returns 201 with tenantId
 *   <li>Duplicate registration with same username/email is rejected
 *   <li>core.tenant.created and billing events present in unified event store
 *   <li>Tenant row materializes in platform_core.tenant
 *   <li>Billing profile and subscription rows exist
 *   <li>Unified consent tables and event table exist
 *   <li>API keys can be generated via POST /api/v1/platform/api-keys
 *   <li>Tenant status reflects creation
 *   <li>Pre-seeded plans exist in plan table
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
    // 1. User Registration (triggers 7-step saga including tenant creation)
    // =======================================================================

    @Nested
    @Order(1)
    @DisplayName("1 — POST /api/v1/platform/iam/users returns 201")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class UserRegistrationHttp {

        @Test
        @Order(1)
        @DisplayName("Register user with organization returns 201 with tenantId")
        void registerUserReturns201() {
            given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                    .contentType(ContentType.JSON)
                    .body(
                            String.format(
                                    """
                    {
                      "username": "pipeline-test-admin",
                      "email": "pipeline-test@operio-test.com",
                      "fullName": "Pipeline Test Admin",
                      "roles": ["super-admin"],
                      "temporaryPassword": false,
                      "organizationName": "%s",
                      "organizationDisplayName": "%s"
                    }
                    """,
                                    TENANT_ID, TENANT_DISPLAY))
                    .when()
                    .post("/api/v1/platform/iam/users")
                    .then()
                    .statusCode(201)
                    .contentType(ContentType.JSON)
                    .body("tenantId", equalTo(TENANT_ID));
        }

        @Test
        @Order(2)
        @DisplayName("Duplicate registration with same username/email is rejected")
        void duplicateRegistrationRejected() {
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
                      "username": "pipeline-test-admin",
                      "email": "pipeline-test@operio-test.com",
                      "fullName": "Duplicate User",
                      "roles": ["super-admin"],
                      "temporaryPassword": false,
                      "organizationName": "%s",
                      "organizationDisplayName": "Duplicate Org"
                    }
                    """,
                                    TENANT_ID))
                    .when()
                    .post("/api/v1/platform/iam/users")
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
                                    assertThat(rs.getString("plan")).isEqualTo("free");
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
                                                    "SELECT tenant_id FROM"
                                                        + " platform_billing.billing_profile WHERE"
                                                        + " tenant_id = '"
                                                            + TENANT_ID
                                                            + "'");
                                    assertThat(rs.next())
                                            .as("Billing profile exists for %s", TENANT_ID)
                                            .isTrue();
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
                                    assertThat(rs.getString("plan_tier")).isEqualTo("free");
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
                                    assertThat(rs.getString("plan")).isEqualTo("free");
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
    // 5. API Key Generation (on-demand via IAM endpoint)
    // =======================================================================

    @Nested
    @Order(5)
    @DisplayName("5 — API key can be generated via POST /api/v1/platform/api-keys")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ApiKeyGeneration {

        @Test
        @Order(1)
        @DisplayName("API key can be created for the registered tenant")
        void apiKeyCanBeCreated() {
            given().header("Authorization", "Bearer " + BOOTSTRAP_KEY)
                    .contentType(ContentType.JSON)
                    .body(
                            String.format(
                                    """
                    {
                      "tenantId": "%s",
                      "subject": "pipeline-test-admin",
                      "roles": ["super-admin"]
                    }
                    """,
                                    TENANT_ID))
                    .when()
                    .post("/api/v1/platform/api-keys")
                    .then()
                    .statusCode(201)
                    .contentType(ContentType.JSON)
                    .body("tenantId", equalTo(TENANT_ID))
                    .body("rawKey", notNullValue())
                    .body("keyPrefix", notNullValue());
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
