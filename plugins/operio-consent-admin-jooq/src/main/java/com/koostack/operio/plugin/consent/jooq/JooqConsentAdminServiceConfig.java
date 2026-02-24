package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.plugin.jooq.common.JooqPluginConfig;

/**
 * Configuration record for the jOOQ-backed consent admin service.
 *
 * <h2>Credential Handling</h2>
 * <p>When {@code username} and {@code password} are non-null they are
 * forwarded to HikariCP as explicit connection credentials — the
 * recommended approach for production deployments because it keeps secrets
 * out of JDBC URL strings (which tend to appear in logs and diagnostics).
 *
 * <p>When {@code null} (the default for the two-arg constructor), HikariCP
 * falls back to whatever the JDBC driver extracts from the URL itself
 * (e.g. {@code ?user=foo&password=bar}) or its own defaults — preserving
 * full backward compatibility with existing callers.
 *
 * @param connectionString the JDBC URL for the consent database
 * @param dialect          the jOOQ {@link org.jooq.SQLDialect} name
 *                         (e.g. {@code "POSTGRES"}, {@code "H2"})
 * @param username         the database username, or {@code null} to use
 *                         the driver default
 * @param password         the database password, or {@code null} to use
 *                         the driver default
 */
public record JooqConsentAdminServiceConfig(
    String connectionString,
    String dialect,
    String username,
    String password
) implements JooqPluginConfig {
    /**
     * Backward-compatible constructor — creates a config without explicit
     * credentials. The JDBC driver will use whatever is encoded in the URL
     * or its own defaults.
     *
     * @param connectionString the JDBC URL for the consent database
     * @param dialect          the jOOQ {@link org.jooq.SQLDialect} name
     */
    public JooqConsentAdminServiceConfig(
        String connectionString,
        String dialect
    ) {
        this(connectionString, dialect, null, null);
    }
}
