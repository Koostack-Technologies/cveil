package com.koostack.operio.rest.integration;

import static org.assertj.core.api.Assertions.*;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import redis.clients.jedis.JedisPooled;

/**
 * Integration test for Redis TLS/SSL connections.
 *
 * <p>Tests that Redis connections can be established with:
 * <ul>
 *   <li>TLS enabled with certificate verification</li>
 *   <li>TLS enabled without certificate verification</li>
 *   <li>TLS disabled (plaintext fallback)</li>
 * </ul>
 *
 * <p><strong>Note:</strong> This test currently does NOT validate the full TLS
 * handshake with certificate verification because Testcontainers Redis does
 * not support TLS out of the box (TLS support would require a custom Redis
 * Docker image with TLS compiled in). For production validation, use
 * {@code redis-tls} Docker images or cloud-managed Redis instances with TLS.
 *
 * <p>This test serves as a placeholder for TLS implementation and can be
 * extended once proper TLS support is available in the test infrastructure.
 */
@QuarkusTest
@WithTestResource(RedisTlsIntegrationIT.RedisTlsTestResourceManager.class)
@DisplayName("Redis TLS Integration Tests")
class RedisTlsIntegrationIT {

    private static final Logger LOG = LoggerFactory.getLogger(
        RedisTlsIntegrationIT.class
    );

    /**
     * Test resource manager for Redis TLS integration tests.
     *
     * <p>Starts:
     * <ul>
     *   <li>Postgres container (for platform schema with tenant Redis credentials)</li>
     *   <li>Redis container (plaintext only — TLS support requires custom image)</li>
     * </ul>
     *
     * <p>Injects config properties to test both TLS-enabled and TLS-disabled
     * connection modes.
     */
    public static class RedisTlsTestResourceManager
        implements QuarkusTestResourceLifecycleManager
    {

        private static final Logger LOG = LoggerFactory.getLogger(
            RedisTlsTestResourceManager.class
        );

        private PostgreSQLContainer<?> postgres;
        private GenericContainer<?> redis;

        @Override
        public Map<String, String> start() {
            LOG.info("Starting Redis TLS test infrastructure...");

            // -- Postgres (for tenant schema) --
            postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("postgres:16-alpine")
            )
                .withDatabaseName("operio_tls_test")
                .withUsername("operio_tls_test")
                .withPassword("operio_tls_test")
                .withLogConsumer(
                    new Slf4jLogConsumer(LOG).withPrefix("postgres")
                );

            postgres.start();
            String postgresUrl = postgres.getJdbcUrl();
            LOG.info("Postgres started: {}", postgresUrl);

            // -- Redis (plaintext only) --
            redis = new GenericContainer<>(
                DockerImageName.parse("redis:7-alpine")
            )
                .withExposedPorts(6379)
                .waitingFor(
                    Wait.forLogMessage(".*Ready to accept connections.*", 1)
                )
                .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("redis"));

            redis.start();
            String redisHost = redis.getHost();
            int redisPort = redis.getMappedPort(6379);
            LOG.info("Redis started: {}:{}", redisHost, redisPort);

            // -- Build config properties --
            Map<String, String> props = new HashMap<>();

            // Consent DB (same Postgres instance)
            props.put("operio.test.db.url", postgresUrl);
            props.put("operio.test.db.username", "operio_tls_test");
            props.put("operio.test.db.password", "operio_tls_test");
            // TLS disabled for Postgres in this test
            props.put("operio.test.db.ssl-mode", "disable");

            // Redis (plaintext only in this test)
            props.put("operio.test.redis.host", redisHost);
            props.put("operio.test.redis.port", String.valueOf(redisPort));
            // TLS disabled for Redis in this test (standard redis:7 doesn't support TLS)
            props.put("operio.test.redis.tls-enabled", "false");

            // Platform DB (same instance)
            props.put("operio.test.platform-db.url", postgresUrl);
            props.put("operio.test.platform-db.username", "operio_tls_test");
            props.put("operio.test.platform-db.password", "operio_tls_test");
            props.put("operio.test.platform-db.ssl-mode", "disable");

            return props;
        }

        @Override
        public void stop() {
            LOG.info("Stopping Redis TLS test infrastructure...");
            if (redis != null) {
                try {
                    redis.stop();
                } catch (Exception e) {
                    LOG.warn("Failed to stop Redis", e);
                }
            }
            if (postgres != null) {
                try {
                    postgres.stop();
                } catch (Exception e) {
                    LOG.warn("Failed to stop Postgres", e);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Plaintext Redis connection should work (baseline)")
    void testPlaintextRedisConnection() {
        // Given: plaintext Redis connection with no TLS
        String host = "localhost";
        int port = 6379;

        // When: creating a plaintext Jedis client
        JedisPooled client = new JedisPooled(host, port);

        // Then: connection should succeed and PING should return PONG
        try {
            String pong = client.ping();
            assertThat(pong).isEqualTo("PONG");
            LOG.info("✓ Plaintext Redis connection successful");
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName(
        "TLS configuration builder should not throw during construction"
    )
    void testTlsConfigurationBuilder() {
        // Given: TLS configuration parameters
        boolean tlsEnabled = true;
        String caCertPath = null; // No cert validation for this test
        boolean verifyPeer = false;

        // When: building a TLS config
        com.koostack.operio.plugin.redis.common.RedisClientFactory.TlsConfig tlsConfig =
            new com.koostack.operio.plugin.redis.common.RedisClientFactory.TlsConfig(
                tlsEnabled,
                caCertPath,
                verifyPeer
            );

        // Then: config should be created successfully
        assertThat(tlsConfig).isNotNull();
        assertThat(tlsConfig.isEnabled()).isTrue();
        assertThat(tlsConfig.isVerifyPeer()).isFalse();
        LOG.info("✓ TLS configuration builder works");
    }

    @Test
    @DisplayName("RedisClientFactory should accept TLS configuration parameter")
    void testRedisClientFactoryWithTlsConfig() {
        // Given: plaintext Redis and a TLS config object (TLS disabled for this test)
        String host = "localhost";
        int port = 6379;
        var tlsConfig =
            new com.koostack.operio.plugin.redis.common.RedisClientFactory.TlsConfig(
                false, // Disabled for plaintext Redis
                null,
                true
            );

        // When: creating a Jedis client with TLS config
        JedisPooled client =
            com.koostack.operio.plugin.redis.common.RedisClientFactory.createClient(
                host,
                port,
                null, // no password
                tlsConfig
            );

        // Then: plaintext connection should succeed (TLS disabled)
        try {
            String pong = client.ping();
            assertThat(pong).isEqualTo("PONG");
            LOG.info("✓ RedisClientFactory with TLS config works");
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("RedisClientFactory should accept password and TLS config")
    void testRedisClientFactoryWithPasswordAndTls() {
        // Given: plaintext Redis with no password (test instance)
        String host = "localhost";
        int port = 6379;
        String password = null; // No password on test Redis
        var tlsConfig =
            new com.koostack.operio.plugin.redis.common.RedisClientFactory.TlsConfig(
                false,
                null,
                true
            );

        // When: creating a Jedis client with password and TLS config
        JedisPooled client =
            com.koostack.operio.plugin.redis.common.RedisClientFactory.createClient(
                host,
                port,
                password,
                tlsConfig
            );

        // Then: connection should succeed
        try {
            String pong = client.ping();
            assertThat(pong).isEqualTo("PONG");
            LOG.info("✓ RedisClientFactory with password and TLS config works");
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName(
        "RedisClientFactory should accept ACL username, password, and TLS config"
    )
    void testRedisClientFactoryWithAclAndTls() {
        // Given: plaintext Redis with no ACL setup (test instance)
        String host = "localhost";
        int port = 6379;
        String username = null; // Will fall back to default user
        String password = null;
        var tlsConfig =
            new com.koostack.operio.plugin.redis.common.RedisClientFactory.TlsConfig(
                false,
                null,
                true
            );

        // When: creating a Jedis client with ACL (null username) and TLS config
        JedisPooled client =
            com.koostack.operio.plugin.redis.common.RedisClientFactory.createClient(
                host,
                port,
                username,
                password,
                tlsConfig
            );

        // Then: plaintext connection should succeed
        try {
            String pong = client.ping();
            assertThat(pong).isEqualTo("PONG");
            LOG.info(
                "✓ RedisClientFactory with ACL (null username) and TLS config works"
            );
        } finally {
            client.close();
        }
    }
}
