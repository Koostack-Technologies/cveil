package com.koostack.cveil.rest.integration;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
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
 * <p>Cveil manages its own HikariCP connection pools and Redis clients through {@code
 * CveilProducers} — it does not use Quarkus DevServices or Quarkus-managed datasources. This means
 * Quarkus DevServices cannot auto-provision infrastructure for tests. This resource manager fills
 * that gap by:
 *
 * <ol>
 *   <li>Starting a Postgres 16 container with the {@code cveil_test} database.
 *   <li>Starting a Redis 7 container.
 *   <li>Returning a property map that the Quarkus test framework injects as config overrides before
 *       the CDI container initializes.
 * </ol>
 *
 * <h2>Injected properties</h2>
 *
 * <p>The following properties are injected and referenced by the test {@code application.yml} via
 * {@code ${cveil.test.*}} placeholders:
 *
 * <ul>
 *   <li>{@code cveil.test.db.url} — JDBC URL for the consent database
 *   <li>{@code cveil.test.db.username} — Postgres username
 *   <li>{@code cveil.test.db.password} — Postgres password
 *   <li>{@code cveil.test.platform-db.url} — JDBC URL for the platform database (same Postgres
 *       instance, same database — mirrors typical single-DB deployments)
 *   <li>{@code cveil.test.platform-db.username} — Postgres username (platform)
 *   <li>{@code cveil.test.platform-db.password} — Postgres password (platform)
 *   <li>{@code cveil.test.redis.host} — Redis hostname
 *   <li>{@code cveil.test.redis.port} — Redis port
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * @QuarkusTest
 * @QuarkusTestResource(CveilTestResourceManager.class)
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
public class CveilTestResourceManager implements QuarkusTestResourceLifecycleManager {

    private static final Logger LOG = LoggerFactory.getLogger(CveilTestResourceManager.class);

    // -- Container image tags -------------------------------------------------

    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final String REDIS_IMAGE = "redis:7-alpine";
    private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.0.8";

    // -- Database defaults ----------------------------------------------------

    private static final String DB_NAME = "cveil_test";
    private static final String DB_USERNAME = "cveil_test";
    private static final String DB_PASSWORD = "cveil_test_password";

    // -- Redis defaults -------------------------------------------------------

    private static final int REDIS_PORT = 6379;

    // -- Keycloak defaults ----------------------------------------------------

    private static final int KEYCLOAK_PORT = 8080;
    private static final String PLATFORM_REALM = "cveil";
    private static final String CVEIL_CLIENT_ID = "cveil-backend";
    private static final List<String> DEFAULT_ROLES =
            List.of(
                    "consent:read",
                    "consent:write",
                    "admin:read",
                    "admin:write",
                    "tenant-admin",
                    "tenant-write",
                    "tenant-read",
                    "super-admin");

    // -- Container instances --------------------------------------------------

    private Network network;
    private PostgreSQLContainer<?> postgres;

    @SuppressWarnings("resource") // closed in stop()
    private GenericContainer<?> redis;

    @SuppressWarnings("resource") // closed in stop()
    private GenericContainer<?> keycloak;

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
        LOG.info("Starting Testcontainers infrastructure (Postgres + Redis + Keycloak)...");

        // Shared Docker network so Keycloak can reach Postgres by hostname
        network = Network.newNetwork();

        // -- Postgres ---------------------------------------------------------

        postgres =
                new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                        .withNetwork(network)
                        .withNetworkAliases("postgres")
                        .withDatabaseName(DB_NAME)
                        .withUsername(DB_USERNAME)
                        .withPassword(DB_PASSWORD)
                        .withCommand("postgres", "-c", "max_connections=300")
                        .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("postgres"));

        postgres.start();

        String jdbcUrl = postgres.getJdbcUrl();
        LOG.info("Postgres started: url={}, user={}", jdbcUrl, DB_USERNAME);

        // Create the 'keycloak' schema so Keycloak can store its tables there
        try (var conn = java.sql.DriverManager.getConnection(jdbcUrl, DB_USERNAME, DB_PASSWORD);
                var stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS keycloak");
            LOG.info("Created 'keycloak' schema in Postgres");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("Failed to create keycloak schema", e);
        }

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

        // -- Keycloak ---------------------------------------------------------
        // Connects to Postgres via the shared Docker network alias.

        keycloak =
                new GenericContainer<>(DockerImageName.parse(KEYCLOAK_IMAGE))
                        .withNetwork(network)
                        .withExposedPorts(KEYCLOAK_PORT)
                        .withCommand("start-dev")
                        .withEnv("KC_DB", "postgres")
                        .withEnv("KC_DB_URL", "jdbc:postgresql://postgres:5432/" + DB_NAME)
                        .withEnv("KC_DB_SCHEMA", "keycloak")
                        .withEnv("KC_DB_USERNAME", DB_USERNAME)
                        .withEnv("KC_DB_PASSWORD", DB_PASSWORD)
                        .withEnv("KC_HOSTNAME_STRICT", "false")
                        .withEnv("KC_HTTP_ENABLED", "true")
                        .withEnv("KEYCLOAK_ADMIN", "admin")
                        .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
                        .withEnv("KC_HEALTH_ENABLED", "true")
                        .waitingFor(
                                Wait.forLogMessage(".*Keycloak.*started in.*", 1)
                                        .withStartupTimeout(Duration.ofSeconds(120)))
                        .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("keycloak"));

        keycloak.start();

        String keycloakUrl =
                "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(KEYCLOAK_PORT);
        LOG.info("Keycloak started: url={}", keycloakUrl);

        // Bootstrap the cveil realm so the CQRS projection handler can
        // materialise events into a pre-existing realm (the app does not auto-create it).
        String clientSecret = bootstrapPlatformRealm(keycloakUrl);

        // -- Build property map -----------------------------------------------
        // Both consent-db and platform-db point at the same Postgres instance
        // (same as a typical single-database production deployment).

        Map<String, String> props = new HashMap<>();

        // Consent DB
        props.put("cveil.test.db.url", jdbcUrl);
        props.put("cveil.test.db.username", DB_USERNAME);
        props.put("cveil.test.db.password", DB_PASSWORD);

        // Platform DB (same instance)
        props.put("cveil.test.platform-db.url", jdbcUrl);
        props.put("cveil.test.platform-db.username", DB_USERNAME);
        props.put("cveil.test.platform-db.password", DB_PASSWORD);

        // Redis
        props.put("cveil.test.redis.host", redisHost);
        props.put("cveil.test.redis.port", String.valueOf(redisPort));

        // Keycloak
        props.put("cveil.test.keycloak.url", keycloakUrl);
        props.put("cveil.test.keycloak.admin-username", "admin");
        props.put("cveil.test.keycloak.admin-password", "admin");
        props.put("cveil.test.keycloak.client-secret", clientSecret);

        LOG.info(
                "Testcontainers infrastructure ready — {} config properties injected",
                props.size());

        return props;
    }

    /** Stops and removes the Postgres and Redis containers. */
    @Override
    public void stop() {
        LOG.info("Stopping Testcontainers infrastructure...");

        if (keycloak != null) {
            try {
                keycloak.stop();
                LOG.info("Keycloak container stopped");
            } catch (Exception e) {
                LOG.warn("Failed to stop Keycloak container cleanly", e);
            }
        }

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

        if (network != null) {
            try {
                network.close();
                LOG.info("Docker network closed");
            } catch (Exception e) {
                LOG.warn("Failed to close Docker network cleanly", e);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Keycloak platform-realm bootstrap
    // -----------------------------------------------------------------------

    /**
     * Creates the {@code cveil} realm in Keycloak with the {@code cveil-backend} confidential
     * client and all default Cveil roles.
     *
     * @return the auto-generated client secret for {@code cveil-backend}
     */
    private String bootstrapPlatformRealm(String keycloakUrl) {
        LOG.info("Bootstrapping '{}' realm in Keycloak...", PLATFORM_REALM);

        try (Keycloak kc =
                KeycloakBuilder.builder()
                        .serverUrl(keycloakUrl)
                        .realm("master")
                        .clientId("admin-cli")
                        .username("admin")
                        .password("admin")
                        .build()) {

            // Create realm
            RealmRepresentation realm = new RealmRepresentation();
            realm.setRealm(PLATFORM_REALM);
            realm.setDisplayName("Cveil Platform");
            realm.setEnabled(true);
            realm.setAccessTokenLifespan(3600);
            realm.setSsoSessionMaxLifespan(604800);
            realm.setOrganizationsEnabled(true);
            kc.realms().create(realm);

            var realmResource = kc.realm(PLATFORM_REALM);

            // Create cveil-backend confidential client (ROPC + service-account)
            ClientRepresentation client = new ClientRepresentation();
            client.setClientId(CVEIL_CLIENT_ID);
            client.setName("Cveil Backend (" + PLATFORM_REALM + ")");
            client.setEnabled(true);
            client.setProtocol("openid-connect");
            client.setPublicClient(false);
            client.setServiceAccountsEnabled(true);
            client.setDirectAccessGrantsEnabled(true);
            client.setStandardFlowEnabled(false);

            realmResource.clients().create(client);

            // Retrieve auto-generated client secret
            var clients = realmResource.clients().findByClientId(CVEIL_CLIENT_ID);
            String internalId = clients.get(0).getId();
            String secret = realmResource.clients().get(internalId).getSecret().getValue();

            // Promote the 'organization' client scope from optional → default so
            // every token issued for this client contains the "organizations" claim
            // (Keycloak 26 creates this scope automatically when orgs are enabled).
            realmResource.clientScopes().findAll().stream()
                    .filter(s -> "organization".equals(s.getName()))
                    .findFirst()
                    .ifPresent(
                            orgScope -> {
                                // Remove from optional first (Keycloak may reject adding as
                                // default if it's already registered as optional).
                                try {
                                    realmResource
                                            .clients()
                                            .get(internalId)
                                            .removeOptionalClientScope(orgScope.getId());
                                } catch (Exception ignored) {
                                }
                                realmResource
                                        .clients()
                                        .get(internalId)
                                        .addDefaultClientScope(orgScope.getId());
                                LOG.info(
                                        "Added 'organization' as default client scope for '{}'",
                                        CVEIL_CLIENT_ID);
                            });

            // Create default roles
            for (String roleName : DEFAULT_ROLES) {
                RoleRepresentation role = new RoleRepresentation();
                role.setName(roleName);
                role.setDescription("Cveil default role: " + roleName);
                realmResource.roles().create(role);
            }

            LOG.info(
                    "Platform realm '{}' bootstrapped with {} roles, client-secret={}***",
                    PLATFORM_REALM,
                    DEFAULT_ROLES.size(),
                    secret.substring(0, Math.min(4, secret.length())));
            return secret;
        }
    }
}
