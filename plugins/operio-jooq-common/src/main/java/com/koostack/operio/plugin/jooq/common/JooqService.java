package com.koostack.operio.plugin.jooq.common;

import java.util.Locale;

/**
 * Base class for jOOQ-backed services that use Flyway migrations.
 *
 * <p>Subclasses provide a schema name via {@link #getSchemaName()}. The
 * migration classpath is derived by convention:
 * <ul>
 *   <li>Default (H2 / unknown): {@code db/migration/<schemaName>}</li>
 *   <li>Dialect-specific: {@code db/migration/<schemaName>-<dialect>}</li>
 * </ul>
 *
 * <p>The dialect {@code "postgres"} is normalised to {@code "postgresql"} so
 * that a single directory name covers both variants.
 */
public abstract class JooqService {

    private static final String CLASSPATH_PREFIX = "classpath:db/migration/";

    /**
     * Returns the schema name used to locate migration directories.
     * For example {@code "platform-event"}.
     */
    protected abstract String getSchemaName();

    /**
     * Resolves the Flyway migration classpath location for the given dialect
     * string. The dialect is matched case-insensitively; {@code "postgres"}
     * is normalised to {@code "postgresql"}.
     *
     * @param dialect the database dialect (e.g. {@code "POSTGRESQL"},
     *                {@code "sqlite"}, {@code "h2"}) or {@code null}
     * @return a classpath location such as
     *         {@code "classpath:db/migration/platform-event-postgresql"}
     */
    protected String resolveMigrationLocation(String dialect) {
        Dialect d = Dialect.fromString(dialect);
        if (d == null) {
            return CLASSPATH_PREFIX + getSchemaName();
        }
        String normalised = switch (d) {
            case POSTGRES, POSTGRESQL -> "postgresql";
            default -> d.name().toLowerCase(Locale.ROOT);
        };
        return CLASSPATH_PREFIX + getSchemaName() + "-" + normalised;
    }
}
