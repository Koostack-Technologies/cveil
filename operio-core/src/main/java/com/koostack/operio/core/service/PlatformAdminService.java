package com.koostack.operio.core.service;

import com.koostack.operio.core.models.ApiKeyInfo;
import com.koostack.operio.core.models.IdpConfigInfo;
import com.koostack.operio.core.models.TenantInfo;
import com.koostack.operio.core.spi.Plugin;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tenant lifecycle, API key management, and IdP configuration.
 *
 * <p>This is the administrative control plane for the Operio platform.
 * Only one implementation is expected in v1: {@code JooqPlatformAdminService},
 * which owns the platform schema (tenants, API keys, IdP configs).
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li><strong>Tenant lifecycle</strong> — create/delete/query tenants.
 *       Creating a tenant provisions a dedicated Postgres schema
 *       (e.g. {@code t_acme_corp}) with Flyway-applied consent migrations
 *       and a tenant-scoped Redis ACL user restricted to the tenant's
 *       key prefix.</li>
 *   <li><strong>API key management</strong> — create/revoke/list API keys
 *       for a tenant. Keys are stored as SHA-256 hashes; the raw key is
 *       returned only once at creation time.</li>
 *   <li><strong>IdP configuration</strong> — configure external identity
 *       providers (Okta, Auth0, Azure AD, Keycloak) per tenant for future
 *       JWT verification.</li>
 * </ul>
 *
 * <h2>Design rationale</h2>
 * <p>This interface is deliberately separate from {@link com.koostack.operio.core.sdk.IdentityResolver}
 * (passive, read-only, ambient context) and {@link com.koostack.operio.core.platform.iam.TokenValidationService} (active,
 * hot-path credential validation). A Spring Security plugin implements
 * {@code IdentityResolver} but never touches tenant provisioning; the jOOQ
 * platform layer implements {@code PlatformAdminService} and
 * {@code TokenValidationService} but never reads {@code SecurityContextHolder}.
 *
 * @param <T> the plugin configuration type
 * @see com.koostack.operio.core.platform.iam.TokenValidationService
 * @see com.koostack.operio.core.sdk.IdentityResolver
 */
public interface PlatformAdminService<T> extends Plugin<T> {

    // -------------------------------------------------------------------
    // Tenant lifecycle (schema-per-tenant + Redis ACL)
    // -------------------------------------------------------------------

    /**
     * Create a new tenant.
     *
     * <p>This inserts a row into the platform {@code tenant} table, creates
     * a dedicated Postgres schema (e.g. {@code t_acme_corp}) with
     * Flyway-applied consent migrations, and provisions a tenant-scoped
     * Redis ACL user restricted to the tenant's key prefix.
     *
     * @param tenantId    unique tenant identifier (e.g. {@code "acme_corp"})
     * @param displayName human-readable tenant name
     * @param plan        the subscription plan (e.g. {@code "free"},
     *                    {@code "pro"}, {@code "enterprise"})
     * @throws com.koostack.operio.core.exception.OperioServiceException
     *         if the tenant already exists or provisioning fails
     */
    void createTenant(String tenantId, String displayName, String plan);

    /**
     * Delete a tenant and all associated resources.
     *
     * <p>Removes the tenant row, drops the dedicated Postgres schema, and
     * removes the tenant-scoped Redis ACL user and cached keys.
     *
     * @param tenantId the tenant to delete
     * @throws com.koostack.operio.core.exception.OperioServiceException
     *         if the tenant does not exist or teardown fails
     */
    void deleteTenant(String tenantId);

    /**
     * Retrieve information about a single tenant.
     *
     * @param tenantId the tenant to look up
     * @return the tenant info — never {@code null}
     * @throws com.koostack.operio.core.exception.OperioServiceException
     *         if the tenant does not exist
     */
    TenantInfo getTenant(String tenantId);

    /**
     * List all tenants on the platform.
     *
     * @return an unmodifiable list of all tenants, possibly empty
     */
    List<TenantInfo> listTenants();

    // -------------------------------------------------------------------
    // API key management
    // -------------------------------------------------------------------

    /**
     * Create an API key for a tenant.
     *
     * <p>Returns the raw key (shown only once). The key is stored as a
     * SHA-256 hash in the {@code api_key} table. The first 8 characters
     * of the raw key serve as the {@code keyPrefix} for later revocation
     * and listing.
     *
     * @param tenantId the owning tenant
     * @param subject  the subject (user/service account) this key represents
     * @param roles    the roles/authorities granted to this key
     * @return the raw API key — must be transmitted securely and never stored
     * @throws com.koostack.operio.core.exception.OperioServiceException
     *         if the tenant does not exist
     */
    String createApiKey(String tenantId, String subject, Set<String> roles);

    /**
     * Revoke an API key by its prefix.
     *
     * <p>Sets the key status to {@code REVOKED}. Subsequent calls to
     * {@link com.koostack.operio.core.platform.iam.TokenValidationService#verifyToken} with this key will fail.
     *
     * @param keyPrefix the first 8 characters of the raw key
     * @throws com.koostack.operio.core.exception.OperioServiceException
     *         if no key with the given prefix exists
     */
    void revokeApiKey(String keyPrefix);

    /**
     * List all API keys for a tenant.
     *
     * <p>Returns metadata only — never the raw key or hash.
     *
     * @param tenantId the tenant whose keys to list
     * @return an unmodifiable list of API key metadata, possibly empty
     */
    List<ApiKeyInfo> listApiKeys(String tenantId);

    // -------------------------------------------------------------------
    // IdP configuration (future — Okta, Auth0, Azure AD, Keycloak)
    // -------------------------------------------------------------------

    /**
     * Configure an external identity provider for a tenant.
     *
     * <p>The {@code config} map contains provider-specific settings
     * (e.g. {@code issuerUri}, {@code clientId}, {@code jwksUri}).
     *
     * @param tenantId the owning tenant
     * @param provider the IdP identifier (e.g. {@code "okta"}, {@code "auth0"})
     * @param config   provider-specific configuration key-value pairs
     * @throws com.koostack.operio.core.exception.OperioServiceException
     *         if the tenant does not exist
     */
    void configureIdp(String tenantId, String provider, Map<String, Object> config);

    /**
     * Retrieve the IdP configuration for a specific provider on a tenant.
     *
     * @param tenantId the tenant to query
     * @param provider the IdP identifier
     * @return the IdP configuration info — never {@code null}
     * @throws com.koostack.operio.core.exception.OperioServiceException
     *         if no configuration exists for the given tenant/provider pair
     */
    IdpConfigInfo getIdpConfig(String tenantId, String provider);

    /**
     * List all IdP configurations for a tenant.
     *
     * @param tenantId the tenant whose IdP configs to list
     * @return an unmodifiable list of IdP configuration metadata, possibly empty
     */
    List<IdpConfigInfo> listIdpConfigs(String tenantId);
}
