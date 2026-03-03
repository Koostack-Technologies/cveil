package com.koostack.operio.plugin.jooq.common;

import javax.sql.DataSource;

import com.koostack.operio.core.models.TenantContext;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.RenderNameCase;
import org.jooq.conf.Settings;
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
 * <h2>Identifier Casing</h2>
 * <p>Contexts produced by this factory use dialect-aware identifier
 * rendering. For <strong>PostgreSQL</strong>, identifiers are rendered in
 * lower-case ({@link RenderNameCase#LOWER}) so that the upper-case names
 * from H2-based jOOQ code-generation match the unquoted (lower-case)
 * names created by Flyway migrations. For <strong>H2</strong>, schema
 * rendering is disabled and identifiers are left as-is, because H2's
 * default schema {@code PUBLIC} is upper-case and quoting it as
 * {@code "public"} causes <em>Schema "public" not found</em>.
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
            config,
            poolName
        );

        runMigrations(dataSource, migrationLocations);

        SQLDialect sqlDialect = SQLDialect.valueOf(
            config.dialect().toUpperCase()
        );

        Settings settings = buildSettings(sqlDialect);

        return DSL.using(dataSource, sqlDialect, settings);
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
     * <p>When {@link TenantContext#get()} returns a
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
            config,
            poolName
        );

        runMigrations(dataSource, migrationLocations);

        SQLDialect sqlDialect = SQLDialect.valueOf(
            config.dialect().toUpperCase()
        );

        Settings settings = buildSettings(sqlDialect);

        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.set(dataSource);
        configuration.set(sqlDialect);
        configuration.set(settings);
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
     * Builds jOOQ {@link Settings} appropriate for the given dialect.
     *
     * <ul>
     *   <li><strong>H2</strong> — identifiers are rendered as-is (upper-case,
     *       matching H2's defaults) and schema qualification is disabled.
     *       H2's default schema is {@code PUBLIC} (upper-case); the
     *       lower-case {@code "public"} produced by
     *       {@link RenderNameCase#LOWER} triggers
     *       <em>Schema "public" not found</em>.</li>
     *   <li><strong>All other dialects</strong> (PostgreSQL, etc.) —
     *       identifiers are rendered in lower-case so that the upper-case
     *       names produced by H2-based jOOQ code generation match the
     *       unquoted (lower-case) names created by Flyway migrations in
     *       PostgreSQL.</li>
     * </ul>
     *
     * @param dialect the target SQL dialect
     * @return jOOQ settings configured for the dialect
     */
    private static Settings buildSettings(SQLDialect dialect) {
        if (dialect == SQLDialect.H2) {
            return new Settings().withRenderSchema(false);
        }
        // Disable schema qualification so that SET search_path controls
        // which schema is used (required for multi-tenant routing).
        return new Settings()
            .withRenderNameCase(RenderNameCase.LOWER)
            .withRenderSchema(false);
    }

    /**
     * Runs Flyway migrations if any migration locations are provided.
     *
     * <p>Each migration location set gets its own Flyway schema history
     * table, derived from the last path segment of the first location
     * (e.g. {@code "classpath:db/migration/consent"} →
     * {@code "flyway_schema_history_consent"}). This prevents checksum
     * collisions when multiple modules (consent, platform, etc.) share
     * the same database but have independent migration version sequences.
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
            String historyTable = deriveHistoryTable(migrationLocations[0]);
            Flyway.configure()
                .dataSource(dataSource)
                .locations(migrationLocations)
                .table(historyTable)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
        }
    }

    /**
     * Derives a Flyway schema history table name from a migration location
     * path.
     *
     * <p>Extracts the last path segment and appends it as a suffix:
     * {@code "classpath:db/migration/consent"} →
     * {@code "flyway_schema_history_consent"}.
     *
     * <p>Falls back to the Flyway default ({@code "flyway_schema_history"})
     * if the location cannot be parsed.
     *
     * @param location the first migration location string
     * @return the history table name
     */
    private static String deriveHistoryTable(String location) {
        if (location == null || location.isBlank()) {
            return "flyway_schema_history";
        }
        // Strip trailing slashes and extract the last segment
        String cleaned = location.endsWith("/")
            ? location.substring(0, location.length() - 1)
            : location;
        int lastSlash = cleaned.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < cleaned.length() - 1) {
            String suffix = cleaned.substring(lastSlash + 1);
            return "flyway_schema_history_" + suffix;
        }
        return "flyway_schema_history";
    }
}
