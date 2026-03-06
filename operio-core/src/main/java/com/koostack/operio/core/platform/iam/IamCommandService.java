package com.koostack.operio.core.platform.iam;

import com.koostack.operio.core.spi.Plugin;

/**
 * Command service for IAM key management — generates and revokes platform API keys.
 *
 * <p>Each command publishes a domain event to the platform event store. The implementation is
 * responsible for secure key generation and hashing.
 *
 * @param <T> the configuration type for the backing implementation
 */
public interface IamCommandService<T> extends Plugin<T> {

    /**
     * Result of creating an API token.
     *
     * @param keyId the unique key identifier
     * @param keyPrefix the first 8 characters of the key (for display)
     * @param plaintextKey the full plaintext key (returned once, never persisted)
     */
    record CreateApiTokenResult(String keyId, String keyPrefix, String plaintextKey) {}

    /**
     * Generate a new API token for the given tenant and environment. Publishes an {@code
     * ApiKeyGeneratedEvent}.
     *
     * @param tenantId the tenant the key belongs to
     * @param name human-readable name / subject for the key
     * @param environment one of DEV, TEST, PROD
     * @param roles comma-separated roles granted to this key, may be {@code null}
     * @return the result containing the one-time plaintext key
     */
    CreateApiTokenResult createApiToken(
            String tenantId, String name, String environment, String roles);

    /**
     * Revoke an existing API token. Publishes an {@code ApiKeyRevokedEvent}.
     *
     * @param tenantId the tenant the key belongs to
     * @param keyId the key identifier to revoke
     */
    void revokeApiToken(String tenantId, String keyId);
}
