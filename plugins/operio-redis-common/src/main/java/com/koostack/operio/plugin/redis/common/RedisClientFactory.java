package com.koostack.operio.plugin.redis.common;

import redis.clients.jedis.JedisPooled;

/**
 * Factory for creating configured {@link JedisPooled} instances
 * for use with Redis-based Operio plugins.
 *
 * <h2>Authentication Modes</h2>
 * <ul>
 *   <li><strong>No auth</strong> — {@link #createClient(String, int)}: for
 *       local development / test Redis instances with no password.</li>
 *   <li><strong>Password-only (legacy AUTH)</strong> —
 *       {@link #createClient(String, int, String)}: authenticates as the
 *       Redis {@code default} user. Suitable for single-tenant deployments
 *       or when Redis ACLs are not in use.</li>
 *   <li><strong>ACL username + password</strong> —
 *       {@link #createClient(String, int, String, String)}: authenticates as
 *       a specific Redis ACL user (e.g. {@code t_acme}). <strong>This is the
 *       recommended mode for multi-tenant deployments</strong> — each tenant
 *       gets a dedicated ACL user restricted to its key pattern, providing
 *       server-side isolation enforced by Redis itself.</li>
 * </ul>
 *
 * <h2>TLS Support</h2>
 * <p>All factory methods have TLS-enabled variants that accept a
 * {@link TlsConfig} parameter. When TLS is enabled, connections are encrypted
 * in transit using TLS (typically on Redis port 6380). Pass {@code null} or an
 * empty {@link TlsConfig} to disable TLS (plaintext TCP).
 *
 * <p>For HIPAA §164.312(a)(2)(ii) and GDPR Article 32 compliance, TLS is
 * strongly recommended for production deployments where Redis credentials
 * or tenant data traverse the network.
 */
public final class RedisClientFactory {

    private RedisClientFactory() {
        // Utility class — not meant to be instantiated
    }

    /**
     * Configuration for TLS connections to Redis.
     *
     * <p>Passed to factory methods to enable TLS encryption and optionally
     * configure certificate verification.
     */
    public static class TlsConfig {

        private final boolean enabled;
        private final String caCertPath;
        private final boolean verifyPeer;

        /**
         * Creates a TLS configuration.
         *
         * @param enabled     whether TLS is enabled
         * @param caCertPath  optional path to a PEM-encoded CA certificate
         * @param verifyPeer  whether to verify peer certificates
         */
        public TlsConfig(
            boolean enabled,
            String caCertPath,
            boolean verifyPeer
        ) {
            this.enabled = enabled;
            this.caCertPath = caCertPath;
            this.verifyPeer = verifyPeer;
        }

        /**
         * Whether TLS is enabled.
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Path to the CA certificate file (PEM-encoded), or null if not configured.
         */
        public String getCaCertPath() {
            return caCertPath;
        }

        /**
         * Whether peer certificate verification is enabled.
         */
        public boolean isVerifyPeer() {
            return verifyPeer;
        }
    }

    /**
     * Creates a new {@link JedisPooled} client connected to the given host and port
     * without authentication or TLS.
     *
     * @param host the Redis server hostname
     * @param port the Redis server port
     * @return a configured {@link JedisPooled} instance
     */
    public static JedisPooled createClient(String host, int port) {
        return createClient(host, port, null, (String) null);
    }

    /**
     * Creates a new {@link JedisPooled} client connected to the given host, port,
     * and authenticated with a password as the {@code default} Redis user.
     *
     * <p><strong>Note:</strong> This authenticates as the {@code default} user
     * (the {@code null} username maps to {@code default} in Redis ACL). In
     * multi-tenant deployments, prefer
     * {@link #createClient(String, int, String, String)} to authenticate as
     * a tenant-specific ACL user with restricted key access.
     *
     * @param host     the Redis server hostname
     * @param port     the Redis server port
     * @param password the Redis server password (for the {@code default} user)
     * @return a configured {@link JedisPooled} instance
     */
    public static JedisPooled createClient(
        String host,
        int port,
        String password
    ) {
        return createClient(host, port, null, password, null);
    }

    /**
     * Creates a new {@link JedisPooled} client connected to the given host, port,
     * and authenticated with a specific Redis ACL username and password.
     *
     * <p>This is the recommended factory method for multi-tenant deployments.
     * Each tenant should have a dedicated Redis ACL user (provisioned by
     * {@code TenantManager}) restricted to its key pattern
     * ({@code {appName}:{tenantId}:*}). Authenticating as that user ensures
     * that even if application-level key-prefix logic has a bug, Redis itself
     * will reject operations on keys outside the tenant's permitted pattern.
     *
     * <p>The Redis {@code AUTH username password} command (Redis 6+) is used
     * under the hood by Jedis when both username and password are non-null.
     *
     * @param host     the Redis server hostname
     * @param port     the Redis server port
     * @param username the Redis ACL username (e.g. {@code "t_acme"})
     * @param password the Redis ACL password for this user
     * @return a configured {@link JedisPooled} instance
     * @throws IllegalArgumentException if {@code username} or {@code password}
     *                                  is {@code null} or blank
     */
    public static JedisPooled createClient(
        String host,
        int port,
        String username,
        String password
    ) {
        return createClient(host, port, username, password, null);
    }

    /**
     * Creates a new {@link JedisPooled} client with optional TLS support.
     *
     * @param host      the Redis server hostname
     * @param port      the Redis server port
     * @param password  optional password for the default user (null for no auth)
     * @param tlsConfig optional TLS configuration (null to disable TLS)
     * @return a configured {@link JedisPooled} instance
     */
    public static JedisPooled createClient(
        String host,
        int port,
        String password,
        TlsConfig tlsConfig
    ) {
        return createClient(host, port, null, password, tlsConfig);
    }

    /**
     * Creates a new {@link JedisPooled} client with optional TLS and ACL support.
     *
     * <p>This is the most flexible factory method, supporting:
     * <ul>
     *   <li>No authentication: pass {@code username} and {@code password} as null</li>
     *   <li>Default-user password auth: pass {@code username} as null, password as non-null</li>
     *   <li>ACL auth: pass both username and password as non-null</li>
     *   <li>TLS: pass a non-null {@link TlsConfig} with appropriate settings</li>
     * </ul>
     *
     * @param host      the Redis server hostname
     * @param port      the Redis server port
     * @param username  optional ACL username (null for no ACL or default user)
     * @param password  optional password
     * @param tlsConfig optional TLS configuration (null to disable TLS)
     * @return a configured {@link JedisPooled} instance
     * @throws IllegalArgumentException if ACL username is provided without a password
     */
    public static JedisPooled createClient(
        String host,
        int port,
        String username,
        String password,
        TlsConfig tlsConfig
    ) {
        // Validate ACL constraints
        if (username != null && !username.isBlank()) {
            if (password == null || password.isBlank()) {
                throw new IllegalArgumentException(
                    "Redis ACL password must not be null or blank for user '" +
                        username +
                        "'"
                );
            }
        }

        // Build JedisPooled with appropriate settings
        redis.clients.jedis.HostAndPort hostAndPort =
            new redis.clients.jedis.HostAndPort(host, port);

        var poolConfig = new redis.clients.jedis.ConnectionPoolConfig();

        // If TLS is enabled, create a socket factory for TLS connections
        if (tlsConfig != null && tlsConfig.isEnabled()) {
            try {
                javax.net.ssl.SSLSocketFactory sslSocketFactory =
                    createTlsSocketFactory(tlsConfig);
                var builder =
                    redis.clients.jedis.DefaultJedisClientConfig.builder()
                        .ssl(true)
                        .sslSocketFactory(sslSocketFactory);

                if (username != null && !username.isBlank()) {
                    builder.user(username);
                }
                if (password != null && !password.isBlank()) {
                    builder.password(password);
                }

                return new JedisPooled(
                    poolConfig,
                    hostAndPort,
                    builder.build()
                );
            } catch (Exception e) {
                throw new IllegalStateException(
                    "Failed to create TLS socket factory for Redis connection",
                    e
                );
            }
        }

        // Non-TLS path — build a JedisClientConfig with auth credentials
        var builder = redis.clients.jedis.DefaultJedisClientConfig.builder();
        if (username != null && !username.isBlank()) {
            builder.user(username);
        }
        if (password != null && !password.isBlank()) {
            builder.password(password);
        }
        return new JedisPooled(hostAndPort, builder.build());
    }

    /**
     * Creates an SSL socket factory for TLS connections based on the TLS config.
     *
     * @param tlsConfig the TLS configuration
     * @return an SSL socket factory
     * @throws Exception if certificate loading or SSL context setup fails
     */
    private static javax.net.ssl.SSLSocketFactory createTlsSocketFactory(
        TlsConfig tlsConfig
    ) throws Exception {
        javax.net.ssl.SSLContext sslContext =
            javax.net.ssl.SSLContext.getInstance("TLS");

        javax.net.ssl.TrustManager[] trustManagers;

        if (
            tlsConfig.isVerifyPeer() &&
            tlsConfig.getCaCertPath() != null &&
            !tlsConfig.getCaCertPath().isBlank()
        ) {
            // Load CA certificate from file and create a trust manager
            trustManagers = loadCaCertificateTrustManagers(
                tlsConfig.getCaCertPath()
            );
        } else {
            // No verification — trust all certificates
            trustManagers = new javax.net.ssl.TrustManager[] {
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }

                    public void checkClientTrusted(
                        java.security.cert.X509Certificate[] certs,
                        String authType
                    ) {}

                    public void checkServerTrusted(
                        java.security.cert.X509Certificate[] certs,
                        String authType
                    ) {}
                },
            };
        }

        sslContext.init(null, trustManagers, new java.security.SecureRandom());
        return sslContext.getSocketFactory();
    }

    /**
     * Loads CA certificates from a PEM file and creates corresponding trust managers.
     *
     * @param caCertPath path to the PEM-encoded CA certificate file
     * @return array of trust managers
     * @throws Exception if the file cannot be read or certificates cannot be parsed
     */
    private static javax.net.ssl.TrustManager[] loadCaCertificateTrustManagers(
        String caCertPath
    ) throws Exception {
        java.nio.file.Path path = java.nio.file.Paths.get(caCertPath);
        byte[] certBytes = java.nio.file.Files.readAllBytes(path);

        java.security.cert.CertificateFactory cf =
            java.security.cert.CertificateFactory.getInstance("X.509");

        // Parse PEM certificates
        java.util.Collection<? extends java.security.cert.Certificate> certs =
            cf.generateCertificates(
                new java.io.ByteArrayInputStream(certBytes)
            );

        // Create a KeyStore with the loaded certificates
        java.security.KeyStore keyStore = java.security.KeyStore.getInstance(
            java.security.KeyStore.getDefaultType()
        );
        keyStore.load(null, null);

        int i = 1;
        for (java.security.cert.Certificate cert : certs) {
            keyStore.setCertificateEntry("cert-" + i, cert);
            i++;
        }

        // Create a TrustManager using the KeyStore
        javax.net.ssl.TrustManagerFactory tmf =
            javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            );
        tmf.init(keyStore);

        return tmf.getTrustManagers();
    }
}
