package com.koostack.operio.plugin.jooq.common;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListenerProvider;

/**
 * High-level factory that creates a fully initialized {@link DSLContext} for a jOOQ-backed plugin.
 * <p>
 * Handles the full lifecycle in one call:
 * <ol>
 *   <li>Creates a pooled {@link DataSource} via {@link JooqDataSourceFactory}</li>
 *   <li>Runs Flyway migrations (if migration locations are provided)</li>
 *   <li>Returns a configured {@link DSLContext} with the correct dialect</li>
 * </ol>
 *
 * <h2>Tenant-Aware Variants</h2>
 * <p>The {@link #createTenantAwareContext} overloads produce a {@link DSLContext}
 * that automatically routes each SQL statement to the correct per-tenant
 * Postgres schema via {@link TenantAwareExecuteListener}. Use these for the
 * <strong>consent-data</strong> DSLContext only — the platform DSLContext
 * (shared across all tenants) must use the plain {@link #createContext}
 * methods.
 */
public final class JooqContextFactory {

    private JooqContextFactory() {
        // Utility class — not meant to be instantiated
    }

    // -----------------------------------------------------------------------
    // Standard (non-tenant-aware) context creation
    // -----------------------------------------------------------------------

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
    public static DSLContext createContext(
        JooqPluginConfig config,
        String poolName,
        String... migrationLocations
    ) {
        DataSource dataSource = JooqDataSourceFactory.createDataSource(
            config.connectionString(),
            poolName
        );

        runMigrations(dataSource, migrationLocations);

        SQLDialect sqlDialect = SQLDialect.valueOf(
            config.dialect().toUpperCase()
        );
        return DSL.using(dataSource, sqlDialect);
    }

    /**
     * Creates a ready-to-use {@link DSLContext} without running any migrations.
     *
     * @param config   the plugin's jOOQ configuration (connection string, dialect)
     * @param poolName a descriptive name for the HikariCP connection pool
     * @return a fully initialized {@link DSLContext}
     */
    public static DSLContext createContext(
        JooqPluginConfig config,
        String poolName
    ) {
        return createContext(config, poolName, new String[0]);
    }

    // -----------------------------------------------------------------------
    // Tenant-aware context creation
    // -----------------------------------------------------------------------

    /**
     * Creates a tenant-aware {@link DSLContext} that automatically routes
     * each SQL statement to the per-tenant Postgres schema via
     * {@link TenantAwareExecuteListener}.
     *
     * <p>When {@link com.koostack.operio.core.TenantContext#get()} returns a
     * non-null tenant ID, every jOOQ operation executed through this context
     * will first issue {@code SET search_path TO t_{tenantId}, public} on
     * the underlying JDBC connection. When no tenant context is active, the
     * connection retains its default search path (backward compatible).
     *
     * <p>Flyway migrations are run against the <strong>default</strong>
     * schema (no tenant routing) before the context is returned. Per-tenant
     * schema creation and migration is handled by {@code TenantManager} in
     * the platform module.
     *
     * @param config             the plugin's jOOQ configuration (connection string, dialect)
     * @param poolName           a descriptive name for the HikariCP connection pool
     * @param migrationLocations Flyway migration locations (e.g. {@code "classpath:db/migration/consent"}).
     *                           If empty or not provided, no migrations are run.
     * @return a tenant-aware {@link DSLContext}
     */
    public static DSLContext createTenantAwareContext(
        JooqPluginConfig config,
        String poolName,
        String... migrationLocations
    ) {
        DataSource dataSource = JooqDataSourceFactory.createDataSource(
            config.connectionString(),
            poolName
        );

        runMigrations(dataSource, migrationLocations);

        SQLDialect sqlDialect = SQLDialect.valueOf(
            config.dialect().toUpperCase()
        );

        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.set(dataSource);
        configuration.set(sqlDialect);
        configuration.set(
            new DefaultExecuteListenerProvider(new TenantAwareExecuteListener())
        );

        return DSL.using(configuration);
    }

    /**
     * Creates a tenant-aware {@link DSLContext} without running any
     * migrations.
     *
     * @param config   the plugin's jOOQ configuration (connection string, dialect)
     * @param poolName a descriptive name for the HikariCP connection pool
     * @return a tenant-aware {@link DSLContext}
     * @see #createTenantAwareContext(JooqPluginConfig, String, String...)
     */
    public static DSLContext createTenantAwareContext(
        JooqPluginConfig config,
        String poolName
    ) {
        return createTenantAwareContext(config, poolName, new String[0]);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Runs Flyway migrations if any migration locations are provided.
     *
     * @param dataSource         the datasource to migrate against
     * @param migrationLocations the Flyway migration locations; if
     *                           {@code null} or empty, no migrations run
     */
    private static void runMigrations(
        DataSource dataSource,
        String[] migrationLocations
    ) {
        if (migrationLocations != null && migrationLocations.length > 0) {
            Flyway.configure()
                .dataSource(dataSource)
                .locations(migrationLocations)
                .load()
                .migrate();
        }
    }
}
