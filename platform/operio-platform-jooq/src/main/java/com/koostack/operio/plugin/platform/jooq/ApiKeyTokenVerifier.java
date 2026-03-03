package com.koostack.operio.plugin.platform.jooq;

import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.core.exception.OperioAuthException;
import com.koostack.operio.core.platform.iam.IamQueryService;
import com.koostack.operio.core.platform.iam.TokenValidationService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API key bearer token verifier — the auth filter hot path.
 *
 * <p>Implements {@link TokenValidationService} by SHA-256 hashing the raw bearer token,
 * looking up the hash in the {@code platform_iam.api_key} projection table via
 * {@link IamQueryService}, and validating the key's status and expiration before
 * building an {@link IdentityContext}.
 *
 * <h2>How It Works</h2>
 * <ol>
 *   <li>Receives the raw bearer token from the {@code Authorization} header.</li>
 *   <li>Computes the SHA-256 hex digest of the token.</li>
 *   <li>Looks up the digest via {@link IamQueryService#findByKeyHash}.</li>
 *   <li>Validates: key must exist, status must be {@code "active"}, and
 *       {@code expiresAt} must be {@code null} or in the future.</li>
 *   <li>On success, builds an {@link IdentityContext} with the key's
 *       subject, roles, tenant ID, and provider {@code "api-key"}.</li>
 *   <li>On failure, throws {@link OperioAuthException}.</li>
 * </ol>
 *
 * @see TokenValidationService
 */
public class ApiKeyTokenVerifier implements TokenValidationService<PlatformJooqConfig> {

    private IamQueryService<?> iamQueryService;

    /**
     * Default constructor for SPI / ServiceLoader discovery.
     * Call {@link #setIamQueryService(IamQueryService)} before use, or use
     * the one-argument constructor for programmatic wiring.
     */
    public ApiKeyTokenVerifier() {
    }

    /**
     * Programmatic constructor — uses the CQRS {@link IamQueryService}
     * to look up API keys from the {@code platform_iam.api_key} projection.
     *
     * @param iamQueryService the IAM query service instance
     */
    public ApiKeyTokenVerifier(IamQueryService<?> iamQueryService) {
        this.iamQueryService = iamQueryService;
    }

    /**
     * @deprecated Use {@link #ApiKeyTokenVerifier(IamQueryService)} instead.
     *             Retained for test backward compatibility.
     */
    @Deprecated
    public ApiKeyTokenVerifier(org.jooq.DSLContext platformDslContext, ApiKeyRepository apiKeyRepository) {
        this.iamQueryService = new IamQueryService<Void>() {
            @Override public String getName() { return "test-adapter"; }
            @Override public int getPriority() { return 0; }
            @Override public Class<Void> getConfigurationType() { return Void.class; }
            @Override public void configure(Void config) {}
            @Override public boolean isReady() { return true; }

            @Override
            public java.util.List<ApiKeyInfo> listApiKeys(String tenantId) { return java.util.List.of(); }

            @Override
            public ApiKeyInfo getApiKey(String tenantId, String keyId) { return null; }

            @Override
            public ApiKeyInfo findByKeyHash(String keyHash) {
                return apiKeyRepository.findByKeyHash(keyHash)
                    .map(ak -> new ApiKeyInfo(
                        ak.getApiKeyId() != null ? ak.getApiKeyId().toString() : null,
                        ak.getKeyPrefix(), ak.getSubject(),
                        ak.getTenantId(), null, ak.getSubject(), ak.getRoles(),
                        ak.getStatus(),
                        ak.getCreatedAt() != null ? ak.getCreatedAt().toInstant(java.time.ZoneOffset.UTC) : null,
                        ak.getExpiresAt() != null ? ak.getExpiresAt().toInstant(java.time.ZoneOffset.UTC) : null))
                    .orElse(null);
            }

            @Override
            public ApiKeyInfo findByKeyPrefix(String keyPrefix) { return null; }
        };
    }

    /**
     * Sets the IAM query service after construction (e.g. when wired
     * from {@code OperioProducers}).
     */
    public void setIamQueryService(IamQueryService<?> iamQueryService) {
        this.iamQueryService = iamQueryService;
    }

    // -----------------------------------------------------------------------
    // Plugin SPI
    // -----------------------------------------------------------------------

    @Override
    public String getName() {
        return "api-key";
    }

    @Override
    public int getPriority() {
        return 300;
    }

    @Override
    public Class<PlatformJooqConfig> getConfigurationType() {
        return PlatformJooqConfig.class;
    }

    /**
     * Legacy configure method — retained for SPI compatibility.
     * In the CQRS flow, use {@link #setIamQueryService} instead.
     */
    @Override
    public void configure(PlatformJooqConfig config) {
        // No-op: IamQueryService is injected externally in CQRS mode.
        // Legacy callers that relied on configure() creating its own DSLContext
        // should switch to the IamQueryService constructor or setter.
    }

    @Override
    public boolean isReady() {
        return iamQueryService != null;
    }

    // -----------------------------------------------------------------------
    // Token verification
    // -----------------------------------------------------------------------

    /**
     * Actively verify a bearer token by SHA-256 hash lookup.
     *
     * @param token the raw bearer token from the Authorization header
     * @return the verified identity context — never {@code null}
     * @throws OperioAuthException if the token is invalid, expired, revoked,
     *                             or not found
     */
    @Override
    public IdentityContext verifyToken(String token) {
        if (iamQueryService == null) {
            throw new OperioAuthException(
                "Token verification service not yet initialized"
            );
        }
        if (token == null || token.isBlank()) {
            throw new OperioAuthException("Bearer token must not be null or blank");
        }

        String keyHash = sha256Hex(token);

        IamQueryService.ApiKeyInfo apiKey = iamQueryService.findByKeyHash(keyHash);
        if (apiKey == null) {
            throw new OperioAuthException(
                "Invalid API key — no matching key found"
            );
        }

        // Validate status
        if (!"active".equalsIgnoreCase(apiKey.status())) {
            throw new OperioAuthException(
                "API key is " + apiKey.status() +
                    " (prefix: " + apiKey.keyPrefix() + ")"
            );
        }

        // Validate expiration
        if (apiKey.expiresAt() != null && apiKey.expiresAt().isBefore(Instant.now())) {
            throw new OperioAuthException(
                "API key has expired (prefix: " + apiKey.keyPrefix() +
                    ", expired at: " + apiKey.expiresAt() + ")"
            );
        }

        // Parse roles from comma-separated string
        Set<String> roles;
        if (apiKey.roles() != null && !apiKey.roles().isBlank()) {
            roles = Arrays.stream(apiKey.roles().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        } else {
            roles = Set.of();
        }

        return new IdentityContext(
            apiKey.subject(),         // subject
            token,                    // token (the raw bearer token)
            null,                     // email (not stored on API keys)
            roles,                    // roles
            Map.of(),                 // claims (empty for API keys)
            apiKey.tenantId(),        // tenantId
            "api-key"                 // provider
        );
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Computes the SHA-256 hex digest of the given input string.
     *
     * @param input the string to hash
     * @return the lowercase hex-encoded SHA-256 digest
     */
    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available on all JVMs
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
