package com.koostack.operio.core.platform.iam;

/**
 * Resolves which {@link IdpProvider} to use for a given tenant.
 *
 * <p>The resolution strategy:
 *
 * <ol>
 *   <li>Check the {@code idp_config} table for a tenant-specific provider override.
 *   <li>If no override is configured, fall back to the platform default (Keycloak).
 * </ol>
 *
 * <p>Implementations should cache resolved providers with a short TTL to avoid database lookups on
 * every request.
 *
 * @see IdpProvider
 */
public interface IdpTenantResolver {

    /**
     * Resolve the {@link IdpProvider} for the given tenant.
     *
     * @param tenantId the tenant identifier
     * @return the IdP provider to use — never {@code null} (falls back to platform default)
     */
    IdpProvider<?> resolve(String tenantId);

    /**
     * Returns the platform default {@link IdpProvider} (Keycloak).
     *
     * <p>Used when no tenant context is available (e.g. pre-login operations where the tenant is
     * provided explicitly in the request body).
     *
     * @return the default IdP provider — never {@code null}
     */
    IdpProvider<?> defaultProvider();
}
