package com.koostack.operio.plugin.jooq.common;

import com.koostack.operio.core.models.TenantContext;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;

/**
 * jOOQ {@link ExecuteListener} that sets the Postgres session variable {@code app.tenant_id} before
 * each SQL statement executes, enabling Row-Level Security (RLS) policies to filter rows by tenant.
 *
 * <h2>How It Works</h2>
 *
 * <ol>
 *   <li>At {@link #executeStart(ExecuteContext)}, reads the current tenant from {@link
 *       TenantContext#get()}.
 *   <li>If a tenant is bound, sanitizes the tenant ID and executes {@code SET app.tenant_id =
 *       '{id}'} on the JDBC connection.
 *   <li>If no tenant is bound, the listener is a no-op.
 * </ol>
 *
 * <p>RLS policies on each table use {@code current_setting('app.tenant_id', true)} to filter rows
 * automatically. The application never needs explicit {@code WHERE tenant_id = ?} clauses.
 *
 * @see TenantContext
 */
public class TenantAwareExecuteListener implements ExecuteListener {

    /** Alphanumeric + underscore + hyphen — prevents SQL injection in SET statements. */
    private static final Pattern VALID_TENANT_ID = Pattern.compile("^[a-zA-Z0-9_\\-]+$");

    @Override
    public void executeStart(ExecuteContext ctx) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return;
        }

        String sanitized = sanitizeTenantId(tenantId);

        // SET (session-scoped) rather than SET LOCAL (transaction-scoped) so it
        // works regardless of whether an explicit transaction is open. The value
        // is overwritten on every jOOQ operation via this listener.
        try {
            Statement stmt = ctx.connection().createStatement();
            stmt.execute("SET app.tenant_id = '" + sanitized + "'");
        } catch (SQLException e) {
            throw new org.jooq.exception.DataAccessException(
                    "Failed to set app.tenant_id for tenant '" + tenantId + "'", e);
        }
    }

    /**
     * Validates the tenant ID contains only safe characters for use in a SQL SET statement.
     *
     * @param tenantId the raw tenant ID from {@link TenantContext}
     * @return the validated tenant ID (unchanged if valid)
     * @throws IllegalArgumentException if the tenant ID contains illegal characters or is blank
     */
    static String sanitizeTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Tenant ID must not be null or blank");
        }
        if (!VALID_TENANT_ID.matcher(tenantId).matches()) {
            throw new IllegalArgumentException(
                    "Tenant ID contains illegal characters: '"
                            + tenantId
                            + "'. Only alphanumeric characters, underscores, and hyphens are"
                            + " allowed.");
        }
        return tenantId;
    }
}
