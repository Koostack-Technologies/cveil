package com.koostack.operio.core.platform.iam;

import com.koostack.operio.core.spi.Plugin;

import java.util.List;

/**
 * Query service for IAM key management — reads API key metadata from
 * the projection table.
 *
 * @param <T> the configuration type for the backing implementation
 */
public interface IamQueryService<T> extends Plugin<T> {

    /**
     * Projected API key metadata (never exposes the key hash).
     */
    record ApiKeyInfo(
        String keyId,
        String keyPrefix,
        String name,
        String tenantId,
        String environment,
        String status,
        java.time.Instant createdAt,
        java.time.Instant expiresAt
    ) {}

    /**
     * List all API keys for the given tenant.
     *
     * @param tenantId the tenant identifier
     * @return list of API key metadata
     */
    List<ApiKeyInfo> listApiKeys(String tenantId);

    /**
     * Get a single API key by ID.
     *
     * @param tenantId the tenant identifier
     * @param keyId    the key identifier
     * @return the API key metadata, or {@code null} if not found
     */
    ApiKeyInfo getApiKey(String tenantId, String keyId);
}
