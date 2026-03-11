package com.koostack.operio.rest.integration;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.JedisPooled;

/**
 * Integration test for Redis TLS/SSL connections.
 *
 * <p>Tests that Redis connections can be established with:
 *
 * <ul>
 *   <li>TLS enabled with certificate verification
 *   <li>TLS enabled without certificate verification
 *   <li>TLS disabled (plaintext fallback)
 * </ul>
 *
 * <p><strong>Note:</strong> This test currently does NOT validate the full TLS handshake with
 * certificate verification because Testcontainers Redis does not support TLS out of the box (TLS
 * support would require a custom Redis Docker image with TLS compiled in). For production
 * validation, use {@code redis-tls} Docker images or cloud-managed Redis instances with TLS.
 *
 * <p>This test does NOT use {@code @QuarkusTest} or {@code @WithTestResource} because it only tests
 * raw Redis connectivity and the {@code RedisClientFactory} TLS configuration — it does not need
 * the Quarkus application. Using a separate {@code QuarkusTestResourceLifecycleManager} would force
 * Quarkus to restart between test classes, recycling the shared Redis container and destroying
 * streams and consumer groups needed by other integration tests.
 */
@Testcontainers
@DisplayName("Redis TLS Integration Tests")
class RedisTlsIntegrationIT {

    private static final Logger LOG = LoggerFactory.getLogger(RedisTlsIntegrationIT.class);

    @SuppressWarnings("resource") // managed by Testcontainers lifecycle
    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Plaintext Redis connection should work (baseline)")
    void testPlaintextRedisConnection() {
        // Given: plaintext Redis connection with no TLS
        String host = REDIS.getHost();
        int port = REDIS.getMappedPort(6379);

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
    @DisplayName("TLS configuration builder should not throw during construction")
    void testTlsConfigurationBuilder() {
        // Given: TLS configuration parameters
        boolean tlsEnabled = true;
        String caCertPath = null; // No cert validation for this test
        boolean verifyPeer = false;

        // When: building a TLS config
        com.koostack.operio.plugin.redis.common.RedisClientFactory.TlsConfig tlsConfig =
                new com.koostack.operio.plugin.redis.common.RedisClientFactory.TlsConfig(
                        tlsEnabled, caCertPath, verifyPeer);

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
        String host = REDIS.getHost();
        int port = REDIS.getMappedPort(6379);
        var tlsConfig =
                new com.koostack.operio.plugin.redis.common.RedisClientFactory.TlsConfig(
                        false, // Disabled for plaintext Redis
                        null, true);

        // When: creating a Jedis client with TLS config
        JedisPooled client =
                com.koostack.operio.plugin.redis.common.RedisClientFactory.createClient(
                        host, port, null, // no password
                        tlsConfig);

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
        String host = REDIS.getHost();
        int port = REDIS.getMappedPort(6379);
        String password = null; // No password on test Redis
        var tlsConfig =
                new com.koostack.operio.plugin.redis.common.RedisClientFactory.TlsConfig(
                        false, null, true);

        // When: creating a Jedis client with password and TLS config
        JedisPooled client =
                com.koostack.operio.plugin.redis.common.RedisClientFactory.createClient(
                        host, port, password, tlsConfig);

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
    @DisplayName("RedisClientFactory should accept ACL username, password, and TLS config")
    void testRedisClientFactoryWithAclAndTls() {
        // Given: plaintext Redis with no ACL setup (test instance)
        String host = REDIS.getHost();
        int port = REDIS.getMappedPort(6379);
        String username = null; // Will fall back to default user
        String password = null;
        var tlsConfig =
                new com.koostack.operio.plugin.redis.common.RedisClientFactory.TlsConfig(
                        false, null, true);

        // When: creating a Jedis client with ACL (null username) and TLS config
        JedisPooled client =
                com.koostack.operio.plugin.redis.common.RedisClientFactory.createClient(
                        host, port, username, password, tlsConfig);

        // Then: plaintext connection should succeed
        try {
            String pong = client.ping();
            assertThat(pong).isEqualTo("PONG");
            LOG.info("✓ RedisClientFactory with ACL (null username) and TLS config works");
        } finally {
            client.close();
        }
    }
}
