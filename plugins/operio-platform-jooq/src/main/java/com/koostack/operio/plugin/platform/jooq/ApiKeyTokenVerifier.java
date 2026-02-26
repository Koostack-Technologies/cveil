package com.koostack.operio.plugin.platform.jooq;

import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.core.exception.OperioAuthException;
import com.koostack.operio.core.service.TokenVerifier;
import com.koostack.operio.plugin.platform.jooq.generated.tables.pojos.ApiKey;

import org.jooq.DSLContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API key bearer token verifier — the auth filter hot path.
 *
 * <p>Implements {@link TokenVerifier} by SHA-256 hashing the raw bearer token,
 * looking up the hash in the platform {@code api_key} table, and validating
 * the key's status and expiration before building an {@link IdentityContext}.
 *
 * <h2>How It Works</h2>
 * <ol>
 *   <li>Receives the raw bearer token from the {@code Authorization} header.</li>
 *   <li>Computes the SHA-256 hex digest of the token.</li>
 *   <li>Looks up the digest in {@link ApiKeyRepository#findByKeyHash}.</li>
 *   <li>Validates: key must exist, status must be {@code "active"}, and
 *       {@code expires_at} must be {@code null} or in the future.</li>
 *   <li>On success, builds an {@link IdentityContext} with the key's
 *       subject, roles, tenant ID, and provider {@code "api-key"}.</li>
 *   <li>On failure, throws {@link OperioAuthException}.</li>
 * </ol>
 *
 * <h2>Shared Infrastructure</h2>
 * <p>This verifier shares the platform {@link DSLContext} and
 * {@link ApiKeyRepository} with {@link JooqPlatformAdminService}. In
 * production, the starter wires them from a single
 * {@link PlatformJooqConfig}:
 * <pre>{@code
 * JooqPlatformAdminService platformAdmin = new JooqPlatformAdminService();
 * platformAdmin.configure(platformConfig);
 * ApiKeyTokenVerifier tokenVerifier = new ApiKeyTokenVerifier(
 *     platformAdmin.platformDslContext(), platformAdmin.apiKeyRepository());
 * }</pre>
 *
 * <p>Alternatively, configure via the plugin SPI:
 * <pre>{@code
 * ApiKeyTokenVerifier verifier = new ApiKeyTokenVerifier();
 * verifier.configure(platformConfig);
 * }</pre>
 *
 * <h2>Performance</h2>
 * <p>Every request pays the cost of a SHA-256 hash (sub-microsecond) and
 * a single indexed database lookup on {@code api_key.key_hash}. A future
 * optimization can add an in-memory LRU cache keyed on the hash with a
 * short TTL (e.g. 30 seconds) to avoid the DB round trip on every request.
 * Not a v1 concern — profile first.
 *
 * @see TokenVerifier
 * @see JooqPlatformAdminService
 */
public class ApiKeyTokenVerifier implements TokenVerifier<PlatformJooqConfig> {

    private DSLContext platformDslContext;
    private ApiKeyRepository apiKeyRepository;

    /**
     * Default constructor for SPI / ServiceLoader discovery.
     * Call {@link #configure(PlatformJooqConfig)} before use, or use the
     * two-argument constructor for programmatic wiring.
     */
    public ApiKeyTokenVerifier() {
    }

    /**
     * Programmatic constructor — shares infrastructure with
     * {@link JooqPlatformAdminService}.
     *
     * @param platformDslContext the platform {@link DSLContext} (non-tenant-aware)
     * @param apiKeyRepository   the API key repository (shared instance)
     */
    public ApiKeyTokenVerifier(DSLContext platformDslContext, ApiKeyRepository apiKeyRepository) {
        this.platformDslContext = platformDslContext;
        this.apiKeyRepository = apiKeyRepository;
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
     * Configures this verifier from a {@link PlatformJooqConfig}.
     *
     * <p>Creates a platform {@link DSLContext} (non-tenant-aware) and an
     * {@link ApiKeyRepository}. Runs Flyway platform migrations to ensure
     * the schema is up to date.
     *
     * @param config the platform configuration
     */
    @Override
    public void configure(PlatformJooqConfig config) {
        this.platformDslContext = com.koostack.operio.plugin.jooq.common.JooqContextFactory.createContext(
            config,
            "operio-api-key-verifier",
            "classpath:db/migration/platform"
        );
        this.apiKeyRepository = new ApiKeyRepository(platformDslContext);
    }

    /**
     * Returns {@code true} if this verifier is configured and the platform
     * database is reachable.
     *
     * @return {@code true} if ready to verify tokens
     */
    @Override
    public boolean isReady() {
        if (platformDslContext == null) {
            return false;
        }
        try {
            platformDslContext.selectOne().fetch();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Token verification
    // -----------------------------------------------------------------------

    /**
     * Actively verify a bearer token by SHA-256 hash lookup.
     *
     * <p>Computes the SHA-256 hex digest of the raw token, looks up the
     * hash in the {@code api_key} table, validates status and expiration,
     * and returns an {@link IdentityContext} on success.
     *
     * @param token the raw bearer token from the Authorization header
     * @return the verified identity context — never {@code null}
     * @throws OperioAuthException if the token is invalid, expired, revoked,
     *                             or not found
     */
    @Override
    public IdentityContext verifyToken(String token) {
        if (token == null || token.isBlank()) {
            throw new OperioAuthException("Bearer token must not be null or blank");
        }

        String keyHash = sha256Hex(token);

        ApiKey apiKey = apiKeyRepository.findByKeyHash(keyHash)
            .orElseThrow(() -> new OperioAuthException(
                "Invalid API key — no matching key found"
            ));

        // Validate status
        if (!"active".equalsIgnoreCase(apiKey.getStatus())) {
            throw new OperioAuthException(
                "API key is " + apiKey.getStatus() +
                    " (prefix: " + apiKey.getKeyPrefix() + ")"
            );
        }

        // Validate expiration
        if (apiKey.getExpiresAt() != null && apiKey.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new OperioAuthException(
                "API key has expired (prefix: " + apiKey.getKeyPrefix() +
                    ", expired at: " + apiKey.getExpiresAt() + ")"
            );
        }

        // Parse roles from comma-separated string
        Set<String> roles;
        if (apiKey.getRoles() != null && !apiKey.getRoles().isBlank()) {
            roles = Arrays.stream(apiKey.getRoles().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        } else {
            roles = Set.of();
        }

        return new IdentityContext(
            apiKey.getSubject(),     // subject
            token,                    // token (the raw bearer token)
            null,                     // email (not stored on API keys)
            roles,                    // roles
            Map.of(),                 // claims (empty for API keys)
            apiKey.getTenantId(),     // tenantId
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
