package com.koostack.operio.plugin.jooq.common;

import com.koostack.operio.core.spi.PluginConfig;

/**
 * Common configuration interface for all jOOQ-backed Operio plugins.
 * <p>
 * Plugin-specific config records should implement this interface
 * rather than {@link PluginConfig} directly, ensuring a consistent
 * set of connection parameters across all jOOQ plugins.
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
}
