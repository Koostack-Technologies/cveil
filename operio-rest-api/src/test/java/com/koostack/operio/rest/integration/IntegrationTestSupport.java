package com.koostack.operio.rest.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.koostack.operio.rest.config.OperioConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.resps.StreamEntry;

/**
 * Shared test utilities for integration tests that need to verify state
 * directly in Postgres and Redis — bypassing the REST API layer.
 *
 * <p>Usage: instantiate once per test class (typically in a {@code @BeforeAll}
 * method) by passing the injected {@link OperioConfig}, then call the helper
 * methods to assert on database rows or Redis keys.
 *
 * <pre>{@code
 * @Inject OperioConfig config;
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

    private final JedisPooled jedis;
    private final String jdbcUrl;
    private final String dbUsername;
    private final String dbPassword;
    private final String redisHost;
    private final int redisPort;
    private final String tenantSchema; // null → no search_path override

    /** Creates support without tenant schema routing (platform queries only). */
    public IntegrationTestSupport(OperioConfig config) {
        this(config, null);
    }

    /**
     * Creates support with tenant schema routing.
     * JDBC queries will execute with {@code SET search_path TO t_{tenantId}, public}.
     */
    public IntegrationTestSupport(OperioConfig config, String tenantId) {
        var redis = config.redis().orElseThrow(
            () -> new IllegalStateException("Redis config is required for integration tests")
        );
        this.jedis = new JedisPooled(redis.host(), redis.port());
        this.redisHost = redis.host();
        this.redisPort = redis.port();

        var db = config.db().orElseThrow(
            () -> new IllegalStateException("DB config is required for integration tests")
        );
        this.jdbcUrl = db.url();
        this.dbUsername = db.username().orElse("operio_test");
        this.dbPassword = db.password().orElse("operio_test_password");
        this.tenantSchema = tenantId != null ? "t_" + tenantId : null;
    }

    @Override
    public void close() {
        if (jedis != null) {
            jedis.close();
        }
    }

    // -----------------------------------------------------------------------
    // JDBC helpers
    // -----------------------------------------------------------------------

    /** Opens a fresh JDBC connection with tenant search_path if configured. */
    public Connection openConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(jdbcUrl, dbUsername, dbPassword);
        if (tenantSchema != null) {
            try (var stmt = conn.createStatement()) {
                String eventSchema = tenantSchema + "_event";
                stmt.execute("SET search_path TO " + tenantSchema + ", " + eventSchema + ", public");
            }
        }
        return conn;
    }

    /**
     * Executes a parameterized query and returns results as a list of maps.
     * Each map key is a lowercase column name.
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

    /** Asserts that at least one row exists, polling until projection catches up. */
    public void assertRowExists(String table, String column, Object value) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() ->
                assertThat(countRows(table, column, value))
                    .as("Expected row in '%s' where %s = '%s'", table, column, value)
                    .isGreaterThan(0)
            );
    }

    /** Asserts that no rows exist, polling until projection catches up. */
    public void assertRowAbsent(String table, String column, Object value) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() ->
                assertThat(countRows(table, column, value))
                    .as("Expected no rows in '%s' where %s = '%s'", table, column, value)
                    .isEqualTo(0)
            );
    }

    /**
     * Polls until the given SQL query returns at least one row where the
     * specified column equals the expected value. Useful for verifying
     * eventually-consistent projections.
     */
    public void awaitQueryValue(String sql, String column, Object expected, Object... params) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                var rows = query(sql, params);
                assertThat(rows)
                    .as("Expected non-empty result for: %s", sql)
                    .isNotEmpty();
                assertThat(rows.get(0).get(column))
                    .as("Expected %s = '%s' in result of: %s", column, expected, sql)
                    .isEqualTo(expected);
            });
    }

    /**
     * Polls until the given SQL query returns results satisfying the
     * predicate. Useful for eventually-consistent assertions.
     */
    public void awaitQuery(String sql, Predicate<List<Map<String, Object>>> condition,
                           String description, Object... params) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                var rows = query(sql, params);
                assertThat(condition.test(rows))
                    .as(description)
                    .isTrue();
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
                + "JOIN entity e ON f.entity_id = e.entity_id "
                + "ORDER BY f.field_name"
        );
    }

    public List<Map<String, Object>> queryFieldCategories(String fieldName, String entityName) {
        return query(
            "SELECT fc.*, f.field_name, c.category_name, e.entity_name "
                + "FROM field_category fc "
                + "JOIN field f ON fc.field_id = f.field_id "
                + "JOIN entity e ON f.entity_id = e.entity_id "
                + "JOIN category c ON fc.category_id = c.category_id "
                + "WHERE f.field_name = ? AND e.entity_name = ?",
            fieldName, entityName
        );
    }

    // -- Consent ledger queries -----------------------------------------------

    public List<Map<String, Object>> queryConsentLedger(String subjectId) {
        return query(
            "SELECT * FROM consent_ledger WHERE subject_id = ? ORDER BY created_at",
            subjectId
        );
    }

    public long countConsentLedgerEntries(String subjectId) {
        return countRows("consent_ledger", "subject_id", subjectId);
    }

    // -- Event store queries --------------------------------------------------

    public List<Map<String, Object>> queryEvents(String aggregateType, String eventType) {
        return query(
            "SELECT * FROM event WHERE aggregate_type = ? AND event_type = ? ORDER BY occurred_at",
            aggregateType, eventType
        );
    }

    public List<Map<String, Object>> queryEventsByAggregate(String aggregateType) {
        return query(
            "SELECT * FROM event WHERE aggregate_type = ? ORDER BY occurred_at",
            aggregateType
        );
    }

    public long countUnpublishedEvents() {
        var results = query("SELECT COUNT(*) AS cnt FROM event WHERE published = FALSE");
        return (long) results.get(0).get("cnt");
    }

    /**
     * Polls until all events in the event table are marked as published.
     * Uses Awaitility for timeout and polling.
     */
    public void awaitAllEventsPublished(Duration timeout) {
        Awaitility.await()
            .atMost(timeout)
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() ->
                assertThat(countUnpublishedEvents())
                    .as("All events should be published")
                    .isEqualTo(0)
            );
    }

    // -- Subject table --------------------------------------------------------

    public List<Map<String, Object>> querySubjects() {
        return query("SELECT * FROM subject ORDER BY subject_name");
    }

    // -- DSAR audit log -------------------------------------------------------

    public List<Map<String, Object>> queryDsarAuditLog(String subjectId) {
        return query(
            "SELECT * FROM dsar_audit_log WHERE subject_id = ? ORDER BY created_at",
            subjectId
        );
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
        String tenantId, String fromType, String fromName,
        String toType, String toName
    ) {
        String key = tenantId + ":ep:" + fromType + ":" + fromName
            + ":" + toType + ":" + toName;
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
        return jedis.zrangeByLex(
            tenantId + ":idx:" + type, "-", "+"
        );
    }

    // -- Redis streams --------------------------------------------------------

    /** Reads the latest N entries from the tenant event stream. */
    public List<StreamEntry> getStreamEntries(String tenantId, int count) {
        String key = "operio:stream:tenant:" + tenantId;
        var entries = jedis.xrange(key, "-", "+", count);
        return entries != null ? entries : List.of();
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
     * Polls until an API key is usable for authentication (CQRS projection lag).
     * Sends a lightweight GET /api/v1/health request with the bearer token
     * and waits until it returns a non-401 status.
     */
    public void awaitApiKeyUsable(String bearerToken, Duration timeout) {
        Awaitility.await()
            .atMost(timeout)
            .pollInterval(Duration.ofMillis(200))
            .until(() -> {
                try {
                    int status = io.restassured.RestAssured.given()
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
     * Waits until the per-tenant event table is accessible via JDBC.
     * The event schema ({@code t_{tenantId}_event}) is created asynchronously
     * during tenant provisioning, so callers must wait before issuing commands
     * that write events.
     */
    public void awaitEventTableReady(Duration timeout) {
        Awaitility.await()
            .atMost(timeout)
            .pollInterval(Duration.ofMillis(200))
            .ignoreExceptions()
            .until(() -> {
                try (var conn = openConnection();
                     var stmt = conn.createStatement();
                     var rs = stmt.executeQuery("SELECT count(*) FROM event")) {
                    return rs.next();
                }
            });
    }

    /**
     * Waits until the tenant row appears in platform_core.tenant, meaning the
     * core projection has processed the registration event and the outbox poller
     * can discover this tenant for event publishing.
     */
    public void awaitTenantDiscoverable(String tenantId, Duration timeout) {
        Awaitility.await()
            .atMost(timeout)
            .pollInterval(Duration.ofMillis(200))
            .ignoreExceptions()
            .until(() -> {
                try (var conn = openConnection();
                     var stmt = conn.createStatement();
                     var rs = stmt.executeQuery(
                         "SELECT 1 FROM platform_core.tenant WHERE tenant_id = '" + tenantId + "'")) {
                    return rs.next();
                }
            });
    }

    /**
     * Polls a supplier until the predicate matches or timeout expires.
     * Useful for eventually-consistent Redis state.
     */
    public <T> T awaitRedisState(
        Supplier<T> supplier,
        Predicate<T> predicate,
        Duration timeout
    ) {
        var ref = new Object() { T value; };
        Awaitility.await()
            .atMost(timeout)
            .pollInterval(Duration.ofMillis(200))
            .until(() -> {
                ref.value = supplier.get();
                return predicate.test(ref.value);
            });
        return ref.value;
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
