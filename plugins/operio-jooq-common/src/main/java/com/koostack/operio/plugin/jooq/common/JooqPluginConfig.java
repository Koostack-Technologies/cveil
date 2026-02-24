package com.koostack.operio.plugin.jooq.common;

import com.koostack.operio.core.spi.PluginConfig;

/**
 * Common configuration interface for all jOOQ-backed Operio plugins.
 * <p>
 * Plugin-specific config records should implement this interface
 * rather than {@link PluginConfig} directly, ensuring a consistent
 * set of connection parameters across all jOOQ plugins.
 *
 * <h2>Credentials</h2>
 * <p>{@link #username()} and {@link #password()} default to {@code null},
 * which means HikariCP will rely on whatever credentials are embedded in
 * the JDBC URL (e.g. {@code ?user=foo&password=bar}) or the driver's own
 * defaults. When non-null values are provided they are set explicitly on
 * the HikariCP configuration, which is the recommended approach for
 * production deployments because it keeps secrets out of URL strings
 * (which tend to appear in logs and diagnostics).
 */
public interface JooqPluginConfig extends PluginConfig {
    /**
     * @return the JDBC URL for the database connection
     */
    String connectionString();

    /**
     * @return the jOOQ {@link org.jooq.SQLDialect} name (e.g. "POSTGRES", "H2", "MYSQL")
     */
    String dialect();

    /**
     * Optional database username.
     *
     * <p>When {@code null} (the default), HikariCP will not set an explicit
     * username on the connection — the driver will use whatever is encoded
     * in the JDBC URL or its own default.
     *
     * @return the database username, or {@code null} to use the driver default
     */
    default String username() {
        return null;
    }

    /**
     * Optional database password.
     *
     * <p>When {@code null} (the default), HikariCP will not set an explicit
     * password on the connection — the driver will use whatever is encoded
     * in the JDBC URL or its own default.
     *
     * @return the database password, or {@code null} to use the driver default
     */
    default String password() {
        return null;
    }
}
