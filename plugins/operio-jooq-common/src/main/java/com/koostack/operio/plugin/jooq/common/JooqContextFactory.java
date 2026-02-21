package com.koostack.operio.plugin.jooq.common;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/**
 * High-level factory that creates a fully initialized {@link DSLContext} for a jOOQ-backed plugin.
 * <p>
 * Handles the full lifecycle in one call:
 * <ol>
 *   <li>Creates a pooled {@link DataSource} via {@link JooqDataSourceFactory}</li>
 *   <li>Runs Flyway migrations (if migration locations are provided)</li>
 *   <li>Returns a configured {@link DSLContext} with the correct dialect</li>
 * </ol>
 */
public final class JooqContextFactory {

    private JooqContextFactory() {
        // Utility class — not meant to be instantiated
    }

    /**
     * Creates a ready-to-use {@link DSLContext} from the given plugin config,
     * optionally running Flyway migrations before returning.
     *
     * @param config             the plugin's jOOQ configuration (connection string, dialect)
     * @param poolName           a descriptive name for the HikariCP connection pool
     * @param migrationLocations Flyway migration locations (e.g. {@code "classpath:db/migration/consent"}).
     *                           If empty or not provided, no migrations are run.
     * @return a fully initialized {@link DSLContext}
     */
    public static DSLContext createContext(JooqPluginConfig config, String poolName, String... migrationLocations) {
        DataSource dataSource = JooqDataSourceFactory.createDataSource(config.connectionString(), poolName);

        if (migrationLocations != null && migrationLocations.length > 0) {
            Flyway.configure()
                .dataSource(dataSource)
                .locations(migrationLocations)
                .load()
                .migrate();
        }

        SQLDialect sqlDialect = SQLDialect.valueOf(config.dialect().toUpperCase());
        return DSL.using(dataSource, sqlDialect);
    }

    /**
     * Creates a ready-to-use {@link DSLContext} without running any migrations.
     *
     * @param config   the plugin's jOOQ configuration (connection string, dialect)
     * @param poolName a descriptive name for the HikariCP connection pool
     * @return a fully initialized {@link DSLContext}
     */
    public static DSLContext createContext(JooqPluginConfig config, String poolName) {
        return createContext(config, poolName, new String[0]);
    }
}
