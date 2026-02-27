package com.koostack.operio.plugin.platform.jooq;

import com.koostack.operio.plugin.platform.jooq.generated.tables.daos.IdpConfigDao;
import com.koostack.operio.plugin.platform.jooq.generated.tables.pojos.IdpConfig;

import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.koostack.operio.plugin.platform.jooq.generated.Tables.IDP_CONFIG;

/**
 * Repository for the {@code idp_config} table — per-tenant external identity
 * provider configuration.
 *
 * <p>Wraps the generated {@link IdpConfigDao} for standard CRUD and adds
 * domain-specific lookups and an upsert keyed on the unique
 * {@code (tenant_id, provider)} pair.
 *
 * <p>This repository targets the <strong>shared platform schema</strong>
 * (never tenant-routed). The {@link org.jooq.DSLContext} passed in must
 * be created with {@link com.koostack.operio.plugin.jooq.common.JooqContextFactory#createContext}
 * (plain, non-tenant-aware).
 *
 * <h2>Provider Identifiers</h2>
 * <p>Common provider values include {@code "okta"}, {@code "auth0"},
 * {@code "azure-ad"}, and {@code "keycloak"}. The {@code provider} column
 * is free-text (VARCHAR(50)) so new providers can be added without schema
 * changes.
 */
public class IdpConfigRepository {

    private final DSLContext dsl;
    private final IdpConfigDao dao;

    public IdpConfigRepository(DSLContext dsl) {
        this.dsl = dsl;
        this.dao = new IdpConfigDao(dsl.configuration());
    }

    /** Exposes the generated DAO for standard CRUD when needed. */
    public IdpConfigDao dao() {
        return dao;
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /**
     * Looks up an IdP configuration by tenant and provider.
     *
     * @param tenantId the owning tenant
     * @param provider the IdP identifier (e.g. {@code "okta"})
     * @return the matching IdP config, or empty if none exists
     */
    public Optional<IdpConfig> findByTenantAndProvider(String tenantId, String provider) {
        return dsl.selectFrom(IDP_CONFIG)
                  .where(IDP_CONFIG.TENANT_ID.eq(tenantId))
                  .and(IDP_CONFIG.PROVIDER.eq(provider))
                  .fetchOptionalInto(IdpConfig.class);
    }

    /**
     * Returns all IdP configurations for a tenant, ordered by provider name.
     *
     * @param tenantId the owning tenant
     * @return list of IdP config rows for the tenant, possibly empty
     */
    public List<IdpConfig> findByTenant(String tenantId) {
        return dsl.selectFrom(IDP_CONFIG)
                  .where(IDP_CONFIG.TENANT_ID.eq(tenantId))
                  .orderBy(IDP_CONFIG.PROVIDER)
                  .fetchInto(IdpConfig.class);
    }

    // -----------------------------------------------------------------------
    // Upsert
    // -----------------------------------------------------------------------

    /**
     * Inserts a new IdP configuration or updates the existing one if a row
     * with the same {@code (tenant_id, provider)} already exists.
     *
     * <p>On conflict the connection parameters ({@code issuer_url},
     * {@code client_id}, {@code jwks_uri}, {@code config_json}),
     * {@code status}, {@code updated_at}, and {@code updated_by} columns
     * are updated; the original {@code created_at}/{@code created_by}
     * values are preserved.
     *
     * @param tenantId   the owning tenant
     * @param provider   the IdP identifier (e.g. {@code "okta"}, {@code "auth0"})
     * @param issuerUrl  the issuer URL / discovery endpoint (may be {@code null})
     * @param clientId   the OAuth client ID (may be {@code null})
     * @param jwksUri    the JWKS URI for JWT verification (may be {@code null})
     * @param configJson provider-specific configuration as a JSON string
     *                   (may be {@code null})
     * @param actor      the identity performing the operation (may be {@code null})
     */
    public void upsert(
        String tenantId,
        String provider,
        String issuerUrl,
        String clientId,
        String jwksUri,
        String configJson,
        String actor
    ) {
        LocalDateTime now = LocalDateTime.now();

        dsl.insertInto(IDP_CONFIG)
           .set(IDP_CONFIG.TENANT_ID, tenantId)
           .set(IDP_CONFIG.PROVIDER, provider)
           .set(IDP_CONFIG.ISSUER_URL, issuerUrl)
           .set(IDP_CONFIG.CLIENT_ID, clientId)
           .set(IDP_CONFIG.JWKS_URI, jwksUri)
           .set(IDP_CONFIG.CONFIG_JSON, configJson)
           .set(IDP_CONFIG.STATUS, "active")
           .set(IDP_CONFIG.CREATED_AT, now)
           .set(IDP_CONFIG.CREATED_BY, actor)
           .set(IDP_CONFIG.UPDATED_AT, now)
           .set(IDP_CONFIG.UPDATED_BY, actor)
           .onConflict(IDP_CONFIG.TENANT_ID, IDP_CONFIG.PROVIDER)
           .doUpdate()
           .set(IDP_CONFIG.ISSUER_URL, issuerUrl)
           .set(IDP_CONFIG.CLIENT_ID, clientId)
           .set(IDP_CONFIG.JWKS_URI, jwksUri)
           .set(IDP_CONFIG.CONFIG_JSON, configJson)
           .set(IDP_CONFIG.STATUS, "active")
           .set(IDP_CONFIG.UPDATED_AT, now)
           .set(IDP_CONFIG.UPDATED_BY, actor)
           .execute();
    }

    // -----------------------------------------------------------------------
    // Delete
    // -----------------------------------------------------------------------

    /**
     * Deletes a specific IdP configuration for a tenant and provider.
     *
     * @param tenantId the owning tenant
     * @param provider the IdP identifier to remove
     * @return the number of rows deleted (0 or 1)
     */
    public int delete(String tenantId, String provider) {
        return dsl.deleteFrom(IDP_CONFIG)
                  .where(IDP_CONFIG.TENANT_ID.eq(tenantId))
                  .and(IDP_CONFIG.PROVIDER.eq(provider))
                  .execute();
    }

    /**
     * Bulk-deletes all IdP configurations belonging to a tenant. Called
     * during tenant teardown by {@link JooqPlatformAdminService#deleteTenant}.
     *
     * @param tenantId the tenant whose IdP configs to delete
     * @return the number of rows deleted
     */
    public int deleteByTenant(String tenantId) {
        return dsl.deleteFrom(IDP_CONFIG)
                  .where(IDP_CONFIG.TENANT_ID.eq(tenantId))
                  .execute();
    }
}
