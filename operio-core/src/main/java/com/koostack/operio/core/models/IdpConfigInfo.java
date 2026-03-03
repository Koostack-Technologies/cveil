package com.koostack.operio.core.models;

import java.time.Instant;
import java.util.Map;
/**
 * Immutable snapshot of an external IdP configuration for a tenant.
 *
 * <p>Returned by platform query services.
 * Contains the provider-specific configuration map
 * (e.g. {@code issuerUri}, {@code clientId}, {@code jwksUri}).
 *
 * @param tenantId  the owning tenant
 * @param provider  the IdP identifier (e.g. {@code "okta"}, {@code "auth0"},
 *                  {@code "azure-ad"}, {@code "keycloak"})
 * @param config    provider-specific configuration key-value pairs
 * @param createdAt when the configuration was created or last updated
 */
public record IdpConfigInfo(
    String tenantId,
    String provider,
    Map<String, Object> config,
    Instant createdAt
) {}
