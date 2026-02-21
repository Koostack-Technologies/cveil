package com.koostack.operio.plugin.jooq.common;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * Factory for creating configured {@link DataSource} instances backed by HikariCP
 * for use with jOOQ-based Operio plugins.
 */
public final class JooqDataSourceFactory {

    private JooqDataSourceFactory() {
        // Utility class — not meant to be instantiated
    }

    /**
     * Creates a new {@link DataSource} using HikariCP with the given JDBC connection
     * string and pool name.
     *
     * @param connectionString the JDBC URL for the database connection
     * @param poolName         a descriptive name for the HikariCP connection pool
     * @return a configured {@link DataSource} instance
     */
    public static DataSource createDataSource(String connectionString, String poolName) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(connectionString);
        hikariConfig.setPoolName(poolName);
        return new HikariDataSource(hikariConfig);
    }
}
