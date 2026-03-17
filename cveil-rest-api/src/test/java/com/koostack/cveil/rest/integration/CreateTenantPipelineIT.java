package com.koostack.cveil.rest.integration;

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
 * <p>Validates the entire flow from {@code POST /api/v1/platform/iam/users} (public self-service)
 * through the 4-step orchestrator saga, event sourcing, and projections under the unified schema
 * architecture (RLS-based isolation, single event store).
 *
 * <h2>What this test verifies</h2>
 *
 * <ol>
 *   <li>POST /api/v1/platform/iam/users returns 201 with tenantId (derived from username)
 *   <li>Duplicate registration with same username/email is rejected
 *   <li>core.tenant.created and billing events present in unified event store
 *   <li>Tenant row materializes in cveiliam.tenant
 *   <li>Billing profile and subscription rows exist
 *   <li>Unified consent tables and event table exist
 *   <li>Keycloak login succeeds for the tenant admin user
 *   <li>Tenant status reflects creation
 *   <li>Pre-seeded plans exist in plan table
 * </ol>
 *
 * <p>Keycloak is required — the test provisions a tenant via user registration and then logs in via
 * {@code POST /api/v1/platform/iam/login} to obtain a JWT access token.
 *
 * @see CveilTestResourceManager
 */
@QuarkusTest
@QuarkusTestResource(CveilTestResourceManager.class)
@DisplayName("Create Tenant Pipeline E2E (Unified Schema + RLS)")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CreateTenantPipelineIT {

    private static final String BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests";

    private static final String TENANT_ID = "pipeline_test_tenant_admin";
    private static final String TENANT_DISPLAY = "Pipeline Test Tenant";
    private static final String ADMIN_EMAIL = TENANT_ID.replace("_", "-") + "-admin@cveil-test.com";

    private static volatile String accessToken;

    // Async operations timeout
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    // -----------------------------------------------------------------------
    // DB helper — direct JDBC to verify projections and event store
    // -----------------------------------------------------------------------

    private static Connection getDbConnection() throws SQLException {
        var config = org.eclipse.microprofile.config.ConfigProvider.getConfig();
        String url = config.getValue("cveil.test.db.url", String.class);
        String user = config.getValue("cveil.test.db.username", String.class);
        String pass = config.getValue("cveil.test.db.password", String.class);
        return DriverManager.getConnection(url, user, pass);
    }

    // =======================================================================
    // 1. User Registration (triggers 4-step saga: register-user, create-tenant, billing,
    // subscription)
    // =======================================================================

    @Nested
    @Order(1)
    @DisplayName("1 — POST /api/v1/platform/iam/users returns 201")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class UserRegistrationHttp {

        @Test
        @Order(1)
        @DisplayName("Register user returns 201 with tenantId derived from username")
        void registerUserReturns201() {
            given().contentType(ContentType.JSON)
                    .body(
                            String.format(
                                    """
                    {
                      "username": "pipeline-test-tenant-admin",
                      "email": "%s",
                      "fullName": "Pipeline Test Admin",
                      "password": "Test1234!",
                      "roles": ["super-admin"],
                      "temporaryPassword": false
                    }
                    """,
                                    ADMIN_EMAIL))
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
                                                    "SELECT 1 FROM cveiliam.tenant WHERE"
                                                            + " tenant_id = '"
                                                            + TENANT_ID
                                                            + "'");
                                    assertThat(rs.next())
                                            .as("Projection must have the tenant row")
                                            .isTrue();
                                }
                            });

            given().contentType(ContentType.JSON)
                    .body(
                            String.format(
                                    """
                    {
                      "username": "pipeline-test-tenant-admin",
                      "email": "%s",
                      "fullName": "Duplicate User",
                      "roles": ["super-admin"],
                      "temporaryPassword": false
                    }
                    """,
                                    ADMIN_EMAIL))
                    .when()
                    .post("/api/v1/platform/iam/users")
                    .then()
                    .statusCode(anyOf(is(409), is(400), is(500), is(503)));
        }
    }

    // =======================================================================
    // 2. Event Store Validation (unified cveilevent.event table)
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
                                "SELECT COUNT(*) FROM cveilevent.event "
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
                                "SELECT COUNT(*) FROM cveilevent.event "
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
        @DisplayName("Tenant row materializes in cveiliam.tenant")
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
                                                            + " cveiliam.tenant WHERE tenant_id ="
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
                                                            + " cveilbilling.billing_profile WHERE"
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
                                                        + " cveilbilling.subscription WHERE"
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
                                                            + " billing_tid FROM cveiliam.tenant t"
                                                            + " JOIN cveilbilling.billing_profile b"
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
    @DisplayName("4 — Unified consent tables exist in cveilconsent schema")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class UnifiedSchemaValidation {

        @Test
        @Order(1)
        @DisplayName("All consent tables exist in cveilconsent schema")
        void consentTablesExist() throws SQLException {
            try (Connection conn = getDbConnection();
                    Statement stmt = conn.createStatement()) {
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT table_name FROM information_schema.tables "
                                        + "WHERE table_schema = 'cveilconsent' "
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
        @DisplayName("Unified event table exists in cveilevent schema")
        void unifiedEventTableExists() throws SQLException {
            try (Connection conn = getDbConnection();
                    Statement stmt = conn.createStatement()) {
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT COUNT(*) FROM information_schema.tables "
                                        + "WHERE table_schema = 'cveilevent' "
                                        + "AND table_name = 'event'");
                rs.next();
                assertThat(rs.getInt(1)).as("cveilevent.event table exists").isEqualTo(1);
            }
        }
    }

    // =======================================================================
    // 5. Keycloak Login (JWT auth replaces API key generation)
    // =======================================================================

    @Nested
    @Order(5)
    @DisplayName("5 — Keycloak login succeeds for tenant admin")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class KeycloakLogin {

        @Test
        @Order(1)
        @DisplayName("Tenant admin can log in and obtain a JWT access token")
        void tenantAdminCanLogin() {
            accessToken = IntegrationTestSupport.loginUser(ADMIN_EMAIL, "Test1234!");
            assertThat(accessToken).as("JWT access token").isNotNull().isNotBlank();
            // Valid JWT has three dot-separated parts
            assertThat(accessToken.split("\\.")).as("JWT structure").hasSize(3);
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
                                                    "SELECT status FROM cveiliam.tenant "
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
                                "SELECT plan_id, is_active FROM cveilcore.plan ORDER BY"
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
