package com.koostack.operio.rest.integration;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Quarkus test resource manager that starts Postgres and Redis containers via Testcontainers before
 * the Quarkus application boots, and injects their connection details as system properties.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Operio manages its own HikariCP connection pools and Redis clients through {@code
 * OperioProducers} — it does not use Quarkus DevServices or Quarkus-managed datasources. This means
 * Quarkus DevServices cannot auto-provision infrastructure for tests. This resource manager fills
 * that gap by:
 *
 * <ol>
 *   <li>Starting a Postgres 16 container with the {@code operio_test} database.
 *   <li>Starting a Redis 7 container.
 *   <li>Returning a property map that the Quarkus test framework injects as config overrides before
 *       the CDI container initializes.
 * </ol>
 *
 * <h2>Injected properties</h2>
 *
 * <p>The following properties are injected and referenced by the test {@code application.yml} via
 * {@code ${operio.test.*}} placeholders:
 *
 * <ul>
 *   <li>{@code operio.test.db.url} — JDBC URL for the consent database
 *   <li>{@code operio.test.db.username} — Postgres username
 *   <li>{@code operio.test.db.password} — Postgres password
 *   <li>{@code operio.test.platform-db.url} — JDBC URL for the platform database (same Postgres
 *       instance, same database — mirrors typical single-DB deployments)
 *   <li>{@code operio.test.platform-db.username} — Postgres username (platform)
 *   <li>{@code operio.test.platform-db.password} — Postgres password (platform)
 *   <li>{@code operio.test.redis.host} — Redis hostname
 *   <li>{@code operio.test.redis.port} — Redis port
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * @QuarkusTest
 * @QuarkusTestResource(OperioTestResourceManager.class)
 * class MyIntegrationTest { ... }
 * }</pre>
 *
 * <h2>Container lifecycle</h2>
 *
 * <p>Containers are started once per test class (or shared across classes if Quarkus caches the
 * test application). They are stopped in {@link #stop()} when the test resource is torn down.
 * Testcontainers also registers a JVM shutdown hook as a safety net.
 *
 * @see io.quarkus.test.common.QuarkusTestResourceLifecycleManager
 */
public class OperioTestResourceManager implements QuarkusTestResourceLifecycleManager {

    private static final Logger LOG = LoggerFactory.getLogger(OperioTestResourceManager.class);

    // -- Container image tags -------------------------------------------------

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final String REDIS_IMAGE = "redis:7-alpine";

    // -- Database defaults ----------------------------------------------------

    private static final String DB_NAME = "operio_test";
    private static final String DB_USERNAME = "operio_test";
    private static final String DB_PASSWORD = "operio_test_password";

    // -- Redis defaults -------------------------------------------------------

    private static final int REDIS_PORT = 6379;

    // -- Container instances --------------------------------------------------

    private PostgreSQLContainer<?> postgres;

    @SuppressWarnings("resource") // closed in stop()
    private GenericContainer<?> redis;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Starts Postgres and Redis containers and returns a map of config property overrides that the
     * Quarkus test framework injects before the CDI container initializes.
     *
     * @return property overrides keyed by config property name
     */
    @Override
    public Map<String, String> start() {
        LOG.info("Starting Testcontainers infrastructure (Postgres + Redis)...");

        // -- Postgres ---------------------------------------------------------

        postgres =
                new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                        .withDatabaseName(DB_NAME)
                        .withUsername(DB_USERNAME)
                        .withPassword(DB_PASSWORD)
                        .withCommand("postgres", "-c", "max_connections=300")
                        .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("postgres"));

        postgres.start();

        String jdbcUrl = postgres.getJdbcUrl();
        LOG.info("Postgres started: url={}, user={}", jdbcUrl, DB_USERNAME);

        // -- Redis ------------------------------------------------------------

        redis =
                new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                        .withExposedPorts(REDIS_PORT)
                        .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
                        .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("redis"));

        redis.start();

        String redisHost = redis.getHost();
        int redisPort = redis.getMappedPort(REDIS_PORT);
        LOG.info("Redis started: host={}, port={}", redisHost, redisPort);

        // -- Build property map -----------------------------------------------
        // Both consent-db and platform-db point at the same Postgres instance
        // (same as a typical single-database production deployment).

        Map<String, String> props = new HashMap<>();

        // Consent DB
        props.put("operio.test.db.url", jdbcUrl);
        props.put("operio.test.db.username", DB_USERNAME);
        props.put("operio.test.db.password", DB_PASSWORD);

        // Platform DB (same instance)
        props.put("operio.test.platform-db.url", jdbcUrl);
        props.put("operio.test.platform-db.username", DB_USERNAME);
        props.put("operio.test.platform-db.password", DB_PASSWORD);

        // Redis
        props.put("operio.test.redis.host", redisHost);
        props.put("operio.test.redis.port", String.valueOf(redisPort));

        LOG.info(
                "Testcontainers infrastructure ready — {} config properties injected",
                props.size());

        return props;
    }

    /** Stops and removes the Postgres and Redis containers. */
    @Override
    public void stop() {
        LOG.info("Stopping Testcontainers infrastructure...");

        if (redis != null) {
            try {
                redis.stop();
                LOG.info("Redis container stopped");
            } catch (Exception e) {
                LOG.warn("Failed to stop Redis container cleanly", e);
            }
        }

        if (postgres != null) {
            try {
                postgres.stop();
                LOG.info("Postgres container stopped");
            } catch (Exception e) {
                LOG.warn("Failed to stop Postgres container cleanly", e);
            }
        }
    }
}
