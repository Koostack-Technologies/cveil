package com.koostack.operio.plugin.jooq.common;

import com.koostack.operio.core.models.TenantContext;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;

/**
 * jOOQ {@link org.jooq.ExecuteListener} that routes each SQL statement to
 * the correct per-tenant Postgres schema by issuing
 * {@code SET search_path TO t_{tenantId}, public} on the JDBC connection
 * before the statement executes.
 *
 * <h2>How It Works</h2>
 * <ol>
 *   <li>At {@link #executeStart(ExecuteContext)}, reads the current tenant
 *       from {@link TenantContext#get()}.</li>
 *   <li>If a tenant is bound, sanitizes the tenant ID (alphanumeric +
 *       underscore only) and executes {@code SET search_path TO t_{id}, public}
 *       on the underlying JDBC {@link java.sql.Connection}.</li>
 *   <li>If no tenant is bound, the listener is a no-op — the connection
 *       retains its default search path (backward compatible with
 *       single-tenant / embedded mode).</li>
 * </ol>
 *
 * <p>The {@code SET search_path} statement is executed via plain JDBC
 * ({@link Statement#execute(String)}), which does <em>not</em> re-enter
 * the jOOQ {@code ExecuteListener} chain — no risk of infinite recursion.
 *
 * <h2>Schema Name Convention</h2>
 * <p>Tenant schemas follow the naming pattern {@code t_{tenantId}}, where
 * the tenant ID is sanitized to contain only {@code [a-zA-Z0-9_]}
 * characters. Any tenant ID containing illegal characters is rejected
 * with an {@link IllegalArgumentException} to prevent SQL injection.
 *
 * <h2>Scope</h2>
 * <p>This listener is intended <strong>only</strong> for the consent-data
 * {@link org.jooq.DSLContext}. The platform {@link org.jooq.DSLContext}
 * (used by {@code ApiKeyTokenVerifier})
 * targets the shared {@code operio_platform} schema and must
 * <strong>never</strong> be tenant-routed.
 *
 * <h2>Performance Note</h2>
 * <p>Every jOOQ operation pays the cost of a {@code SET search_path}
 * round trip, even if the connection was already set to the correct
 * tenant. A future optimization can cache the current search path per
 * connection and skip the {@code SET} when it already matches. Not a v1
 * concern — profile first.
 *
 * @see TenantContext
 * @see JooqContextFactory#createTenantAwareContext
 */
public class TenantAwareExecuteListener implements ExecuteListener {

    /**
     * Pattern that matches valid tenant ID characters: alphanumeric and
     * underscore only. Used to prevent SQL injection in the
     * {@code SET search_path} statement.
     */
    private static final Pattern VALID_TENANT_ID = Pattern.compile(
        "^[a-zA-Z0-9_]+$"
    );

    /**
     * Prefix applied to tenant IDs to form the Postgres schema name.
     * For tenant ID {@code acme}, the schema name is {@code t_acme}.
     */
    public static final String SCHEMA_PREFIX = "t_";

    /**
     * Suffix appended to the tenant schema name to form the per-tenant
     * event store schema. For tenant ID {@code acme}, the event schema
     * is {@code t_acme_event}.
     */
    public static final String EVENT_SCHEMA_SUFFIX = "_event";

    // -----------------------------------------------------------------------
    // ExecuteListener
    // -----------------------------------------------------------------------

    /**
     * Intercepts statement execution to set the Postgres search path when
     * a tenant context is active.
     *
     * <p>Called by jOOQ immediately before the SQL statement is sent to
     * the database. If {@link TenantContext#get()} returns a non-null
     * tenant ID, this method issues {@code SET search_path TO t_{id}, public}
     * on the connection. Otherwise it is a no-op.
     *
     * @param ctx the jOOQ execute context providing access to the
     *            underlying JDBC connection
     * @throws org.jooq.exception.DataAccessException if the
     *         {@code SET search_path} statement fails (wrapped by jOOQ)
     */
    @Override
    public void executeStart(ExecuteContext ctx) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return;
        }

        String sanitized = sanitizeTenantId(tenantId);
        String schemaName = SCHEMA_PREFIX + sanitized;

        // IMPORTANT: Do NOT use try-with-resources here.  Closing the
        // temporary Statement can invalidate jOOQ's already-prepared
        // PreparedStatement on the same JDBC connection (observed with
        // the PostgreSQL JDBC driver + HikariCP).  The Statement is
        // lightweight and will be cleaned up when the connection is
        // returned to the pool.
        try {
            Statement stmt = ctx.connection().createStatement();
            String eventSchema = schemaName + EVENT_SCHEMA_SUFFIX;
            stmt.execute("SET search_path TO " + schemaName + ", " + eventSchema + ", public");
        } catch (SQLException e) {
            throw new org.jooq.exception.DataAccessException(
                "Failed to set tenant search path to schema '" +
                    schemaName +
                    "' for tenant '" +
                    tenantId +
                    "'",
                e
            );
        }
    }

    // -----------------------------------------------------------------------
    // Tenant ID sanitization
    // -----------------------------------------------------------------------

    /**
     * Validates and returns the tenant ID, ensuring it contains only safe
     * characters for use in a SQL {@code SET search_path} statement.
     *
     * <p>Accepted characters: {@code [a-zA-Z0-9_]}. Any other character
     * causes an {@link IllegalArgumentException}. This prevents SQL
     * injection since the schema name is interpolated directly into the
     * SQL string (parameterized statements are not supported for
     * {@code SET search_path}).
     *
     * @param tenantId the raw tenant ID from {@link TenantContext}
     * @return the validated tenant ID (unchanged if valid)
     * @throws IllegalArgumentException if the tenant ID contains illegal
     *                                  characters or is blank
     */
    static String sanitizeTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException(
                "Tenant ID must not be null or blank"
            );
        }
        if (!VALID_TENANT_ID.matcher(tenantId).matches()) {
            throw new IllegalArgumentException(
                "Tenant ID contains illegal characters: '" +
                    tenantId +
                    "'. Only alphanumeric characters and underscores are " +
                    "allowed."
            );
        }
        return tenantId;
    }

    /**
     * Builds the Postgres schema name for the given tenant ID.
     *
     * <p>Convenience method that sanitizes the tenant ID and prepends
     * the {@value #SCHEMA_PREFIX} prefix.
     *
     * @param tenantId the raw tenant ID
     * @return the schema name, e.g. {@code t_acme}
     * @throws IllegalArgumentException if the tenant ID is invalid
     */
    public static String schemaNameFor(String tenantId) {
        return SCHEMA_PREFIX + sanitizeTenantId(tenantId);
    }

    /**
     * Builds the per-tenant event store schema name for the given tenant ID.
     *
     * @param tenantId the raw tenant ID
     * @return the event schema name, e.g. {@code t_acme_event}
     * @throws IllegalArgumentException if the tenant ID is invalid
     */
    public static String eventSchemaNameFor(String tenantId) {
        return schemaNameFor(tenantId) + EVENT_SCHEMA_SUFFIX;
    }
}
