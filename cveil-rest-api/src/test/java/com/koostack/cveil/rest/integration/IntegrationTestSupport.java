package com.koostack.cveil.rest.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.koostack.cveil.plugin.event.jooq.RedisEventPublisher;
import com.koostack.cveil.rest.config.CveilConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.resps.StreamEntry;

/**
 * Shared test utilities for integration tests that need to verify state directly in Postgres and
 * Redis — bypassing the REST API layer.
 *
 * <p>Usage: instantiate once per test class (typically in a {@code @BeforeAll} method) by passing
 * the injected {@link CveilConfig}, then call the helper methods to assert on database rows or
 * Redis keys.
 *
 * <pre>{@code
 * @Inject CveilConfig config;
 * static IntegrationTestSupport support;
 *
 * @BeforeAll
 * static void initSupport() {
 *     support = new IntegrationTestSupport(config);
 * }
 *
 * @AfterAll
 * static void closeSupport() {
 *     support.close();
 * }
 * }</pre>
 */
public class IntegrationTestSupport implements AutoCloseable {

    /**
     * In the new architecture, tenants are created synchronously via the orchestrator — no async
     * registry is needed. This field is retained as a placeholder for Phase 11 integration test
     * rewrite.
     */
    @SuppressWarnings("unused")
    private static final Object REGISTRY_LOCK = new Object();

    private final JedisPooled jedis;
    private final String jdbcUrl;
    private final String dbUsername;
    private final String dbPassword;
    private final String redisHost;
    private final int redisPort;
    private final String tenantSchema; // consent/tenant schema name for search_path
    private final String tenantId; // business tenant ID for tenant-aware queries
    private final String keycloakUrl; // null → Keycloak helpers disabled
    private Keycloak keycloakAdmin; // lazy-initialized

    /** Creates support without tenant schema routing (platform queries only). */
    public IntegrationTestSupport(CveilConfig config) {
        this(config, null);
    }

    /**
     * Creates support with tenant schema routing. JDBC queries will execute with {@code SET
     * search_path TO cveilconsent, cveilevent, cveilcore, public}.
     */
    public IntegrationTestSupport(CveilConfig config, String tenantId) {
        var redis =
                config.redis()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Redis config is required for integration tests"));
        this.jedis = new JedisPooled(redis.host(), redis.port());
        this.redisHost = redis.host();
        this.redisPort = redis.port();

        var db =
                config.db()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "DB config is required for integration tests"));
        this.jdbcUrl = db.url();
        this.dbUsername = db.username().orElse("cveil_test");
        this.dbPassword = db.password().orElse("cveil_test_password");
        this.tenantSchema = tenantId != null ? "cveilconsent" : null;
        this.tenantId = tenantId;

        this.keycloakUrl =
                config.keycloak().flatMap(kc -> kc.url()).filter(u -> !u.isBlank()).orElse(null);
    }

    @Override
    public void close() {
        if (keycloakAdmin != null) {
            keycloakAdmin.close();
        }
        if (jedis != null) {
            jedis.close();
        }
    }

    // -----------------------------------------------------------------------
    // Keycloak helpers
    // -----------------------------------------------------------------------

    /** Returns a lazily-initialized Keycloak admin client connected to the master realm. */
    public Keycloak keycloak() {
        if (keycloakAdmin == null) {
            if (keycloakUrl == null) {
                throw new IllegalStateException(
                        "Keycloak URL not configured — cannot create admin client");
            }
            keycloakAdmin =
                    KeycloakBuilder.builder()
                            .serverUrl(keycloakUrl)
                            .realm("master")
                            .clientId("admin-cli")
                            .username("admin")
                            .password("admin")
                            .build();
        }
        return keycloakAdmin;
    }

    /** Polls until the given Keycloak realm exists. */
    public void awaitKeycloakRealmExists(String realmName, Duration timeout) {
        Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .untilAsserted(
                        () ->
                                assertThat(
                                                keycloak().realms().findAll().stream()
                                                        .anyMatch(
                                                                r ->
                                                                        r.getRealm()
                                                                                .equals(realmName)))
                                        .as("Realm '%s' should exist in Keycloak", realmName)
                                        .isTrue());
    }

    /** Polls until a user with the given email exists in the specified Keycloak realm. */
    public void awaitKeycloakUserExists(String realmName, String email, Duration timeout) {
        Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .untilAsserted(
                        () -> {
                            var users =
                                    keycloak().realm(realmName).users().searchByEmail(email, true);
                            assertThat(users)
                                    .as("User '%s' should exist in realm '%s'", email, realmName)
                                    .isNotEmpty();
                        });
    }

    /** Polls until a user with the given email is disabled (suspended) in Keycloak. */
    public void awaitKeycloakUserDisabled(String realmName, String email, Duration timeout) {
        Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .untilAsserted(
                        () -> {
                            var users =
                                    keycloak().realm(realmName).users().searchByEmail(email, true);
                            assertThat(users).isNotEmpty();
                            assertThat(users.get(0).isEnabled())
                                    .as("User '%s' should be disabled", email)
                                    .isFalse();
                        });
    }

    /** Polls until a user with the given email is enabled (active) in Keycloak. */
    public void awaitKeycloakUserEnabled(String realmName, String email, Duration timeout) {
        Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .untilAsserted(
                        () -> {
                            var users =
                                    keycloak().realm(realmName).users().searchByEmail(email, true);
                            assertThat(users).isNotEmpty();
                            assertThat(users.get(0).isEnabled())
                                    .as("User '%s' should be enabled", email)
                                    .isTrue();
                        });
    }

    /** Returns all users in the given Keycloak realm. */
    public List<UserRepresentation> getKeycloakUsers(String realmName) {
        return keycloak().realm(realmName).users().list();
    }

    /** Returns all clients in the given Keycloak realm. */
    public List<ClientRepresentation> getKeycloakClients(String realmName) {
        return keycloak().realm(realmName).clients().findAll();
    }

    /** Returns the realm-level roles assigned to a user. */
    public List<RoleRepresentation> getKeycloakUserRoles(String realmName, String userId) {
        return keycloak().realm(realmName).users().get(userId).roles().realmLevel().listEffective();
    }

    /** Returns the Keycloak URL, or null if not configured. */
    public String getKeycloakUrl() {
        return keycloakUrl;
    }

    /**
     * Bootstraps a Keycloak realm with the {@code cveil-backend} confidential client and default
     * Cveil roles. Idempotent — skips creation if the realm already exists.
     *
     * <p>This mirrors the production {@code KeycloakRealmProvisioner.provisionRealm()} and should
     * be called once at the start of IAM integration tests so the CQRS projection handler can
     * materialise events into a pre-existing realm.
     */
    public void bootstrapKeycloakRealm(String realmName) {
        var kc = keycloak();

        // Idempotent check
        boolean exists =
                kc.realms().findAll().stream().anyMatch(r -> r.getRealm().equals(realmName));
        if (exists) {
            return;
        }

        // Create realm
        var realm = new org.keycloak.representations.idm.RealmRepresentation();
        realm.setRealm(realmName);
        realm.setDisplayName(realmName);
        realm.setEnabled(true);
        realm.setAccessTokenLifespan(3600);
        realm.setSsoSessionMaxLifespan(604800);
        kc.realms().create(realm);

        var realmResource = kc.realm(realmName);

        // Create cveil-backend confidential client (ROPC + service account)
        var client = new org.keycloak.representations.idm.ClientRepresentation();
        client.setClientId("cveil-backend");
        client.setName("Cveil Backend (" + realmName + ")");
        client.setEnabled(true);
        client.setProtocol("openid-connect");
        client.setPublicClient(false);
        client.setServiceAccountsEnabled(true);
        client.setDirectAccessGrantsEnabled(true);
        client.setStandardFlowEnabled(false);
        realmResource.clients().create(client);

        // Create default roles
        for (String roleName :
                List.of(
                        "consent:read",
                        "consent:write",
                        "admin:read",
                        "admin:write",
                        "tenant-admin",
                        "tenant-write",
                        "tenant-read",
                        "super-admin")) {
            var role = new org.keycloak.representations.idm.RoleRepresentation();
            role.setName(roleName);
            role.setDescription("Cveil default role: " + roleName);
            realmResource.roles().create(role);
        }
    }

    // -----------------------------------------------------------------------
    // JDBC helpers
    // -----------------------------------------------------------------------

    /** Opens a fresh JDBC connection with unified schema search_path if configured. */
    public Connection openConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(jdbcUrl, dbUsername, dbPassword);
        if (tenantSchema != null) {
            try (var stmt = conn.createStatement()) {
                stmt.execute(
                        "SET search_path TO cveilconsent, cveilevent, cveilcore,"
                                + " cveiliam, cveilbilling, public");
            }
        }
        return conn;
    }

    /**
     * Executes a parameterized query and returns results as a list of maps. Each map key is a
     * lowercase column name.
     */
    public List<Map<String, Object>> query(String sql, Object... params) {
        try (var conn = openConnection();
                var ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (var rs = ps.executeQuery()) {
                return toMapList(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Query failed: " + sql, e);
        }
    }

    /** Counts rows matching a simple WHERE clause. */
    public long countRows(String table, String whereColumn, Object whereValue) {
        var sql = "SELECT COUNT(*) FROM " + table + " WHERE " + whereColumn + " = ?";
        try (var conn = openConnection();
                var ps = conn.prepareStatement(sql)) {
            ps.setObject(1, whereValue);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Count failed: " + sql, e);
        }
    }

    /** Counts rows with both a column filter and tenant_id isolation. */
    public long countRowsWithTenant(String table, String whereColumn, Object whereValue) {
        var sql =
                "SELECT COUNT(*) FROM "
                        + table
                        + " WHERE "
                        + whereColumn
                        + " = ? AND tenant_id = ?";
        try (var conn = openConnection();
                var ps = conn.prepareStatement(sql)) {
            ps.setObject(1, whereValue);
            ps.setObject(2, tenantId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Count failed: " + sql, e);
        }
    }

    /** Asserts that at least one row exists, polling until projection catches up. */
    public void assertRowExists(String table, String column, Object value) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(
                        () ->
                                assertThat(countRows(table, column, value))
                                        .as(
                                                "Expected row in '%s' where %s = '%s'",
                                                table, column, value)
                                        .isGreaterThan(0));
    }

    /** Asserts that no rows exist, polling until projection catches up. */
    public void assertRowAbsent(String table, String column, Object value) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(
                        () ->
                                assertThat(countRows(table, column, value))
                                        .as(
                                                "Expected no rows in '%s' where %s = '%s'",
                                                table, column, value)
                                        .isEqualTo(0));
    }

    /**
     * Polls until the given SQL query returns at least one row where the specified column equals
     * the expected value. Useful for verifying eventually-consistent projections.
     */
    public void awaitQueryValue(String sql, String column, Object expected, Object... params) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(
                        () -> {
                            var rows = query(sql, params);
                            assertThat(rows)
                                    .as("Expected non-empty result for: %s", sql)
                                    .isNotEmpty();
                            assertThat(rows.get(0).get(column))
                                    .as(
                                            "Expected %s = '%s' in result of: %s",
                                            column, expected, sql)
                                    .isEqualTo(expected);
                        });
    }

    /**
     * Polls until the given SQL query returns results satisfying the predicate. Useful for
     * eventually-consistent assertions.
     */
    public void awaitQuery(
            String sql,
            Predicate<List<Map<String, Object>>> condition,
            String description,
            Object... params) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(
                        () -> {
                            var rows = query(sql, params);
                            assertThat(condition.test(rows)).as(description).isTrue();
                        });
    }

    // -- Catalogue table queries ----------------------------------------------

    public List<Map<String, Object>> queryEntities() {
        return query("SELECT * FROM entity ORDER BY entity_name");
    }

    public List<Map<String, Object>> queryCategories() {
        return query("SELECT * FROM category ORDER BY category_name");
    }

    public List<Map<String, Object>> queryFields() {
        return query(
                "SELECT f.*, e.entity_name FROM field f "
                        + "JOIN entity e ON f.entity_uuid = e.entity_uuid "
                        + "ORDER BY f.field_name");
    }

    public List<Map<String, Object>> queryFieldCategories(String fieldName, String entityName) {
        return query(
                "SELECT fc.*, f.field_name, c.category_name, e.entity_name "
                        + "FROM field_category fc "
                        + "JOIN field f ON fc.field_uuid = f.field_uuid "
                        + "JOIN entity e ON f.entity_uuid = e.entity_uuid "
                        + "JOIN category c ON fc.category_uuid = c.category_uuid "
                        + "WHERE f.field_name = ? AND e.entity_name = ?",
                fieldName,
                entityName);
    }

    // -- Consent ledger queries -----------------------------------------------

    public List<Map<String, Object>> queryConsentLedger(String subjectId) {
        return query(
                "SELECT * FROM consent_ledger WHERE subject_id = ? ORDER BY created_at", subjectId);
    }

    public long countConsentLedgerEntries(String subjectId) {
        return countRows("consent_ledger", "subject_id", subjectId);
    }

    // -- Event store queries --------------------------------------------------

    public List<Map<String, Object>> queryEvents(String aggregateType, String eventType) {
        return query(
                "SELECT * FROM event WHERE aggregate_type = ? AND event_type = ? ORDER BY"
                        + " occurred_at",
                aggregateType,
                eventType);
    }

    public List<Map<String, Object>> queryEventsByAggregate(String aggregateType) {
        return query(
                "SELECT * FROM event WHERE aggregate_type = ? ORDER BY occurred_at", aggregateType);
    }

    public long countUnpublishedEvents() {
        var results = query("SELECT COUNT(*) AS cnt FROM event WHERE published = FALSE");
        return (long) results.get(0).get("cnt");
    }

    /**
     * Polls until all events in the event table are marked as published. Uses Awaitility for
     * timeout and polling.
     */
    public void awaitAllEventsPublished(Duration timeout) {
        Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(
                        () ->
                                assertThat(countUnpublishedEvents())
                                        .as("All events should be published")
                                        .isEqualTo(0));
    }

    // -- Subject table --------------------------------------------------------

    public List<Map<String, Object>> querySubjects() {
        return query("SELECT * FROM subject ORDER BY subject_name");
    }

    // -- DSAR audit log -------------------------------------------------------

    public List<Map<String, Object>> queryDsarAuditLog(String subjectId) {
        return query(
                "SELECT * FROM dsar_audit_log WHERE subject_id = ? ORDER BY created_at", subjectId);
    }

    // -----------------------------------------------------------------------
    // Redis helpers (graph model)
    // -----------------------------------------------------------------------

    /** Returns the raw Jedis client for custom operations. */
    public JedisPooled jedis() {
        return jedis;
    }

    /** Creates a new blocking Jedis connection (caller must close). Needed for Pub/Sub. */
    public Jedis newJedisConnection() {
        return new Jedis(redisHost, redisPort);
    }

    /** Reads all fields from a graph node hash. */
    public Map<String, String> getGraphNode(String tenantId, String type, String name) {
        return jedis.hgetAll(tenantId + ":node:" + type + ":" + name);
    }

    /** Reads outgoing edge members from a graph node. */
    public Set<String> getOutgoingEdges(String tenantId, String type, String name) {
        return jedis.smembers(tenantId + ":out:" + type + ":" + name);
    }

    /** Reads incoming edge members for a graph node. */
    public Set<String> getIncomingEdges(String tenantId, String type, String name) {
        return jedis.smembers(tenantId + ":in:" + type + ":" + name);
    }

    /** Reads edge properties between two nodes. */
    public Map<String, String> getEdgeProperties(
            String tenantId, String fromType, String fromName, String toType, String toName) {
        String key = tenantId + ":ep:" + fromType + ":" + fromName + ":" + toType + ":" + toName;
        return jedis.hgetAll(key);
    }

    // -- Admin projection cache -----------------------------------------------

    /** Reads the admin consent cache for a subject (JSON string). */
    public String getAdminConsentCache(String tenantId, String subjectId) {
        return jedis.get(tenantId + ":admin:consent:" + subjectId);
    }

    /** Reads the admin field cache entry (JSON string). */
    public String getAdminFieldCache(String tenantId, String entityName, String fieldName) {
        return jedis.get(tenantId + ":admin:field:" + entityName + ":" + fieldName);
    }

    /** Reads all members from a type index sorted set. */
    public List<String> getTypeIndex(String tenantId, String type) {
        return jedis.zrangeByLex(tenantId + ":idx:" + type, "-", "+");
    }

    // -- Redis streams --------------------------------------------------------

    /** Reads the latest N entries from the shard stream containing this tenant. */
    public List<StreamEntry> getStreamEntries(String tenantId, int count) {
        String key = shardStreamKey(tenantId);
        var entries = jedis.xrange(key, "-", "+", count);
        return entries != null ? entries : List.of();
    }

    /** Computes the shard stream key for a tenant (must match RedisEventPublisher routing). */
    private String shardStreamKey(String tenantId) {
        int shard = Math.floorMod(tenantId.hashCode(), RedisEventPublisher.DEFAULT_SHARD_COUNT);
        return "cveil:stream:shard:" + shard;
    }

    /** Reads all stream entries and filters by event_type field. */
    public List<StreamEntry> getStreamEntriesByType(String tenantId, String eventType) {
        return getStreamEntries(tenantId, Integer.MAX_VALUE).stream()
                .filter(e -> eventType.equals(e.getFields().get("eventType")))
                .collect(Collectors.toList());
    }

    /** Returns all keys matching a pattern (use sparingly in tests). */
    public Set<String> scanKeys(String pattern) {
        // jedis.keys is acceptable in test context with small datasets
        return jedis.keys(pattern);
    }

    // -- Polling helpers ------------------------------------------------------

    /**
     * Waits until a tenant is ready by checking that the production stream consumer has created its
     * consumer groups on the tenant's Redis stream. In the new architecture, tenant creation is
     * synchronous (via the orchestrator), so we only need to wait for the projection pipeline.
     *
     * @param tenantId the tenant to wait for
     * @param timeout maximum wait time
     */
    public void awaitTenantReady(String tenantId, Duration timeout) {
        // Wait for the tenant row with Redis ACL credentials populated
        Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(200))
                .until(
                        () -> {
                            try (Connection conn =
                                    DriverManager.getConnection(jdbcUrl, dbUsername, dbPassword)) {
                                var rs =
                                        conn.createStatement()
                                                .executeQuery(
                                                        "SELECT 1 FROM cveiliam.tenant WHERE"
                                                                + " tenant_id = '"
                                                                + tenantId
                                                                + "' AND status IN ('created',"
                                                                + " 'active', 'ACTIVE')"
                                                                + " AND redis_acl_user IS NOT NULL"
                                                                + " AND redis_acl_password IS NOT"
                                                                + " NULL");
                                return rs.next();
                            } catch (Exception e) {
                                return false;
                            }
                        });

        // Wait for the sharded stream consumer to create its consumer groups on the shard
        String streamKey = shardStreamKey(tenantId);
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .until(
                        () -> {
                            try {
                                var groups = jedis.xinfoGroups(streamKey);
                                return groups.stream()
                                        .anyMatch(g -> "admin-projection".equals(g.getName()));
                            } catch (Exception e) {
                                return false;
                            }
                        });
    }

    /**
     * Waits only for the tenant DB row to be ready (status + Redis ACL credentials populated).
     * Unlike {@link #awaitTenantReady}, this skips the Redis stream consumer-group check, making it
     * much faster for tests whose endpoints use direct JDBC and don't depend on stream consumers.
     */
    public void awaitTenantDbReady(String tenantId, Duration timeout) {
        Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(200))
                .until(
                        () -> {
                            try (Connection conn =
                                    DriverManager.getConnection(jdbcUrl, dbUsername, dbPassword)) {
                                var rs =
                                        conn.createStatement()
                                                .executeQuery(
                                                        "SELECT 1 FROM cveiliam.tenant WHERE"
                                                                + " tenant_id = '"
                                                                + tenantId
                                                                + "' AND status IN ('created',"
                                                                + " 'active', 'ACTIVE')"
                                                                + " AND redis_acl_user IS NOT NULL"
                                                                + " AND redis_acl_password IS NOT"
                                                                + " NULL");
                                return rs.next();
                            } catch (Exception e) {
                                return false;
                            }
                        });
    }

    /**
     * Polls until an API key is usable for authentication (CQRS projection lag). Sends a
     * lightweight GET /api/v1/health request with the bearer token and waits until it returns a
     * non-401 status.
     */
    public void awaitApiKeyUsable(String bearerToken, Duration timeout) {
        Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(200))
                .until(
                        () -> {
                            try {
                                int status =
                                        io.restassured.RestAssured.given()
                                                .header("Authorization", "Bearer " + bearerToken)
                                                .get("/api/v1/catalogue/fields")
                                                .statusCode();
                                return status != 401;
                            } catch (Exception e) {
                                return false;
                            }
                        });
    }

    /**
     * Polls a supplier until the predicate matches or timeout expires. Useful for
     * eventually-consistent Redis state.
     */
    public <T> T awaitRedisState(Supplier<T> supplier, Predicate<T> predicate, Duration timeout) {
        var ref =
                new Object() {
                    T value;
                };
        Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(200))
                .until(
                        () -> {
                            ref.value = supplier.get();
                            return predicate.test(ref.value);
                        });
        return ref.value;
    }

    // -----------------------------------------------------------------------
    // Tenant provisioning via orchestrator
    // -----------------------------------------------------------------------

    /**
     * Creates a tenant by registering a user with an organization through the unified orchestrator
     * flow ({@code POST /api/v1/platform/iam/users}). This replaces the old {@code POST
     * /api/v1/platform/tenants} endpoint which is now deprecated (501).
     *
     * <p>The orchestrator creates the user in Keycloak, creates a Keycloak organization, adds the
     * user as a member, provisions Redis, creates the tenant aggregate, billing profile, and
     * subscription — all in a single saga.
     *
     * @param bootstrapKey the super-admin bootstrap key for auth
     * @param tenantId used as the organization name (= tenant ID in new architecture)
     * @param displayName display name for the organization
     * @param plan ignored (orchestrator defaults to "free")
     * @return the tenant ID from the orchestrator response
     */
    public static String provisionTenantViaOrchestrator(
            String bootstrapKey, String tenantId, String displayName, String plan) {
        return provisionTenantViaOrchestrator(bootstrapKey, tenantId, displayName, plan, null);
    }

    /**
     * Creates a tenant by registering a user with an organization and optionally sets a password so
     * the user can log in via {@code POST /api/v1/platform/iam/login}.
     *
     * @param bootstrapKey the super-admin bootstrap key for auth
     * @param tenantId used as the organization name (= tenant ID in new architecture)
     * @param displayName display name for the organization
     * @param plan ignored (orchestrator defaults to "free")
     * @param password if non-null, the user's password for Keycloak login
     * @return the tenant ID from the orchestrator response
     */
    public static String provisionTenantViaOrchestrator(
            String bootstrapKey,
            String tenantId,
            String displayName,
            String plan,
            String password) {
        String username = tenantId.replace("_", "-");
        String email = username + "@cveil-test.com";

        String passwordField = "";
        if (password != null) {
            passwordField =
                    """
                    , "password": "%s"
                    """
                            .formatted(password);
        }

        io.restassured.response.Response response =
                io.restassured.RestAssured.given()
                        .contentType("application/json")
                        .body(
                                """
                        {
                          "username": "%s",
                          "email": "%s",
                          "fullName": "%s Admin",
                          "displayName": "%s",
                          "roles": ["super-admin"],
                          "temporaryPassword": false%s
                        }
                        """
                                        .formatted(
                                                username,
                                                email,
                                                displayName,
                                                displayName,
                                                passwordField))
                        .when()
                        .post("/api/v1/platform/iam/users");

        if (response.statusCode() == 201) {
            return response.jsonPath().getString("tenantId");
        }

        // Idempotent: if tenant/user already exists (409/conflict or similar),
        // treat as success so re-runs don't fail.
        String body = response.body().asString();
        if (response.statusCode() == 409
                || (body != null && body.toLowerCase().contains("already exists"))
                || (body != null && body.toLowerCase().contains("conflict"))) {
            System.out.println(
                    "[IntegrationTestSupport] Tenant '"
                            + tenantId
                            + "' already exists — "
                            + "treating as success (HTTP "
                            + response.statusCode()
                            + ")");
            return tenantId;
        }

        throw new AssertionError(
                "Tenant provisioning failed: HTTP " + response.statusCode() + " — " + body);
    }

    // -----------------------------------------------------------------------
    // Keycloak JWT authentication helpers
    // -----------------------------------------------------------------------

    /** Auth info returned by login/provision helpers. */
    public record AuthInfo(
            String accessToken, String refreshToken, String tenantId, String email) {}

    /**
     * Logs in a user via {@code POST /api/v1/platform/iam/login} and returns the JWT access token.
     *
     * @param email the user's email
     * @param password the user's password
     * @return the raw JWT access token
     */
    public static String loginUser(String email, String password) {
        io.restassured.response.Response response =
                io.restassured.RestAssured.given()
                        .contentType("application/json")
                        .body(
                                """
                        {"email": "%s", "password": "%s"}
                        """
                                        .formatted(email, password))
                        .when()
                        .post("/api/v1/platform/iam/login");

        if (response.statusCode() == 200) {
            return response.jsonPath().getString("accessToken");
        }
        throw new AssertionError(
                "Login failed for "
                        + email
                        + ": HTTP "
                        + response.statusCode()
                        + " — "
                        + response.body().asString());
    }

    /**
     * Provisions a tenant via the orchestrator and logs in the created user, returning both the JWT
     * and tenant metadata. This is the primary helper for tests that need a tenant-scoped JWT.
     *
     * @param bootstrapKey the super-admin bootstrap key
     * @param tenantId organization name / tenant ID
     * @param displayName human-readable display name
     * @return AuthInfo with access token, refresh token, tenant ID, and email
     */
    public static AuthInfo provisionTenantAndLogin(
            String bootstrapKey, String tenantId, String displayName) {
        String password = "Test1234!";
        String username = tenantId.replace("_", "-");
        String email = username + "@cveil-test.com";

        try {
            provisionTenantViaOrchestrator(bootstrapKey, tenantId, displayName, "free", password);
        } catch (AssertionError e) {
            // Tenant/user may already exist from a prior run — continue to login
            System.out.println(
                    "[IntegrationTestSupport] Tenant provisioning returned non-201 (likely already"
                            + " exists): "
                            + e.getMessage());
        }

        String accessToken = loginUser(email, password);
        return new AuthInfo(accessToken, null, tenantId, email);
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private List<Map<String, Object>> toMapList(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new HashMap<>();
            for (int i = 1; i <= cols; i++) {
                row.put(meta.getColumnLabel(i).toLowerCase(), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }
}
