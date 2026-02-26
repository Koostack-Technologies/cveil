package com.koostack.operio.spring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot configuration properties for the Operio consent SDK.
 *
 * <p>Binds to the {@code operio.*} namespace in {@code application.yml} /
 * {@code application.properties}. Example:
 *
 * <pre>{@code
 * operio:
 *   mode: embedded             # "embedded" (H2 + Caffeine) or "production" (Postgres + Redis)
 *   app-name: my-consent-app
 *   db:                        # only needed when mode=production
 *     url: jdbc:postgresql://localhost:5432/myapp
 *     dialect: POSTGRES
 *   redis:                     # only needed when mode=production
 *     host: localhost
 *     port: 6379
 *     password:                # optional
 * }</pre>
 *
 * <p>The {@code mode} property controls which consent bundle is auto-wired:
 * <ul>
 *   <li>{@code embedded} (default) — {@code EmbeddedConsentBundle} (H2 + Caffeine,
 *       zero external infra, ideal for development and testing)</li>
 *   <li>{@code production} — {@code CoreConsentBundle} (Postgres + Redis,
 *       server-grade infrastructure for production deployments)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "operio")
public class OperioProperties {

    /**
     * The bundle mode: {@code "embedded"} (default) or {@code "production"}.
     */
    private String mode = "embedded";

    /**
     * Application name used to namespace Redis/Caffeine keys, preventing
     * collisions when multiple Operio instances share the same backing stores.
     */
    private String appName;

    /**
     * Relational database configuration — required when {@code mode=production}.
     */
    private DbProperties db;

    /**
     * Redis configuration — required when {@code mode=production}.
     */
    private RedisProperties redis;

    // -- Getters / Setters ---------------------------------------------------

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public DbProperties getDb() {
        return db;
    }

    public void setDb(DbProperties db) {
        this.db = db;
    }

    public RedisProperties getRedis() {
        return redis;
    }

    public void setRedis(RedisProperties redis) {
        this.redis = redis;
    }

    // -- Nested configuration classes ----------------------------------------

    /**
     * Relational database settings for the jOOQ-backed consent admin layer.
     */
    public static class DbProperties {

        /**
         * JDBC connection URL (e.g. {@code jdbc:postgresql://localhost:5432/myapp}).
         */
        private String url;

        /**
         * jOOQ SQL dialect name (e.g. {@code "POSTGRES"}, {@code "H2"}).
         * Defaults to {@code "POSTGRES"} for production mode.
         */
        private String dialect = "POSTGRES";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getDialect() {
            return dialect;
        }

        public void setDialect(String dialect) {
            this.dialect = dialect;
        }
    }

    /**
     * Redis connection settings for the Redis-backed consent decision layer.
     */
    public static class RedisProperties {

        /**
         * Redis server hostname.
         */
        private String host = "localhost";

        /**
         * Redis server port.
         */
        private int port = 6379;

        /**
         * Redis password (optional — {@code null} for unauthenticated connections).
         */
        private String password;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
