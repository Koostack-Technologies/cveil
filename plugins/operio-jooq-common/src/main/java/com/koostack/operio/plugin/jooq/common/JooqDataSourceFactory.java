package com.koostack.operio.plugin.jooq.common;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

/**
 * Factory for creating configured {@link DataSource} instances backed by HikariCP
 * for use with jOOQ-based Operio plugins.
 *
 * <h2>Credential Handling</h2>
 * <p>When {@link JooqPluginConfig#username()} and/or
 * {@link JooqPluginConfig#password()} return non-null values, they are set
 * explicitly on the HikariCP configuration. This is the recommended approach
 * for production deployments because it keeps secrets out of JDBC URL strings
 * (which tend to appear in logs and diagnostics).
 *
 * <p>When credentials are {@code null}, HikariCP falls back to whatever the
 * JDBC driver extracts from the URL itself (e.g.
 * {@code ?user=foo&password=bar}) or its own defaults — preserving full
 * backward compatibility with existing callers that embed credentials in the
 * connection string.
 */
public final class JooqDataSourceFactory {

    private JooqDataSourceFactory() {
        // Utility class — not meant to be instantiated
    }

    /**
     * Creates a new {@link DataSource} using HikariCP from the given plugin
     * config and pool name.
     *
     * <p>This is the preferred overload — it reads the JDBC URL, dialect,
     * username, and password from the config record so callers don't need
     * to destructure it themselves.
     *
     * @param config   the plugin's jOOQ configuration (connection string,
     *                 dialect, optional username/password)
     * @param poolName a descriptive name for the HikariCP connection pool
     * @return a configured {@link DataSource} instance
     */
    public static DataSource createDataSource(
        JooqPluginConfig config,
        String poolName
    ) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.connectionString());
        hikariConfig.setPoolName(poolName);

        if (config.username() != null) {
            hikariConfig.setUsername(config.username());
        }
        if (config.password() != null) {
            hikariConfig.setPassword(config.password());
        }

        return new HikariDataSource(hikariConfig);
    }

    /**
     * Creates a new {@link DataSource} using HikariCP with the given JDBC
     * connection string and pool name.
     *
     * <p><strong>Backward-compatibility overload.</strong> No explicit
     * username or password is set — the driver will use whatever credentials
     * are embedded in the JDBC URL or its own defaults.
     *
     * @param connectionString the JDBC URL for the database connection
     * @param poolName         a descriptive name for the HikariCP connection pool
     * @return a configured {@link DataSource} instance
     * @deprecated Prefer {@link #createDataSource(JooqPluginConfig, String)}
     *             which also forwards username and password from the config.
     */
    @Deprecated(since = "0.1.0", forRemoval = true)
    public static DataSource createDataSource(
        String connectionString,
        String poolName
    ) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(connectionString);
        hikariConfig.setPoolName(poolName);
        return new HikariDataSource(hikariConfig);
    }
}
