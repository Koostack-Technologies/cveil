package com.koostack.operio.plugin.jooq.common;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;

/**
 * Manages schema migration locations and Flyway execution for a single
 * named schema.
 *
 * <p>Can be used directly for inline cases:
 * <pre>{@code
 * SchemaManager sm = new SchemaManager("tenant-event");
 * sm.runMigration(dataSource);
 * }</pre>
 *
 * <p>Or subclassed for per-module singletons:
 * <pre>{@code
 * public final class AuthzSchemaManager extends SchemaManager {
 *     public static final AuthzSchemaManager INSTANCE = new AuthzSchemaManager();
 *     public AuthzSchemaManager() { super("authz"); }
 * }
 * }</pre>
 */
public class SchemaManager {

    private static final String CLASSPATH_PREFIX = "classpath:db/migration/";

    private final String schemaName;
    private final String migrationLocation;
    private final String historyTable;

    public SchemaManager(String schemaName) {
        this.schemaName = schemaName;
        this.migrationLocation = CLASSPATH_PREFIX + schemaName;
        this.historyTable = "flyway_schema_history_" + schemaName;
    }

    /** Returns the schema name (e.g. {@code "authz"}, {@code "platform-billing"}). */
    public String getSchemaName() {
        return schemaName;
    }

    /** Returns the default classpath migration location. */
    public String getMigrationLocation() {
        return migrationLocation;
    }

    /**
     * Returns a dialect-specific classpath migration location.
     * Uses the {@link Dialect} enum for normalisation — {@code "postgres"}
     * becomes {@code "postgresql"}.
     */
    public String getMigrationLocation(String dialect) {
        Dialect d = Dialect.fromString(dialect);
        if (d == null) {
            return migrationLocation;
        }
        String normalised = switch (d) {
            case POSTGRES, POSTGRESQL -> "postgresql";
            default -> d.name().toLowerCase(java.util.Locale.ROOT);
        };
        return migrationLocation + "-" + normalised;
    }

    /** Returns the Flyway schema history table name for this module. */
    public String getHistoryTable() {
        return historyTable;
    }

    /** Runs Flyway migrations against the given datasource using the default location. */
    public void runMigration(DataSource dataSource) {
        runMigration(dataSource, migrationLocation);
    }

    /** Runs Flyway migrations against the given datasource using the specified location. */
    public void runMigration(DataSource dataSource, String location) {
        Flyway.configure()
              .dataSource(dataSource)
              .locations(location)
              .table(historyTable)
              .baselineOnMigrate(true)
              .baselineVersion("0")
              .load()
              .migrate();
    }
}
