package com.koostack.operio.plugin.platform.jooq;

import com.koostack.operio.core.exception.OperioAuthException;
import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.core.platform.iam.AccessTokenGenerator;
import com.koostack.operio.core.platform.iam.AccessTokenInfo;
import com.koostack.operio.core.platform.iam.IamQueryService;
import com.koostack.operio.core.platform.iam.TokenValidationService;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Access token bearer token verifier — the auth filter hot path.
 *
 * <p>Implements {@link TokenValidationService} by SHA-256 hashing the raw bearer token, looking up
 * the hash in the {@code platform_iam.access_token} table via {@link IamQueryService}, and
 * validating the token's status and expiration before building an {@link IdentityContext}.
 *
 * @see TokenValidationService
 */
public class ApiKeyTokenVerifier implements TokenValidationService<Void> {

    private IamQueryService<?> iamQueryService;

    public ApiKeyTokenVerifier() {}

    public ApiKeyTokenVerifier(IamQueryService<?> iamQueryService) {
        this.iamQueryService = iamQueryService;
    }

    public void setIamQueryService(IamQueryService<?> iamQueryService) {
        this.iamQueryService = iamQueryService;
    }

    @Override
    public String getName() {
        return "access-token";
    }

    @Override
    public int getPriority() {
        return 300;
    }

    @Override
    public Class<Void> getConfigurationType() {
        return Void.class;
    }

    @Override
    public void configure(Void config) {
        // No-op: IamQueryService is injected externally.
    }

    @Override
    public boolean isReady() {
        return iamQueryService != null;
    }

    @Override
    public IdentityContext verifyToken(String token) {
        if (iamQueryService == null) {
            throw new OperioAuthException("Token verification service not yet initialized");
        }
        if (token == null || token.isBlank()) {
            throw new OperioAuthException("Bearer token must not be null or blank");
        }
        if (!AccessTokenGenerator.isAccessToken(token)) {
            throw new OperioAuthException("Not a valid access token format");
        }
        if (!AccessTokenGenerator.validateChecksum(token)) {
            throw new OperioAuthException("Access token checksum mismatch");
        }

        String tokenHash = AccessTokenGenerator.sha256Hex(token);
        AccessTokenInfo accessToken = iamQueryService.findAccessTokenByHash(tokenHash);
        if (accessToken == null) {
            throw new OperioAuthException("Invalid access token — no matching token found");
        }

        if (!"active".equalsIgnoreCase(accessToken.status())) {
            throw new OperioAuthException(
                    "Access token is " + accessToken.status()
                            + " (prefix: " + accessToken.tokenPrefix() + ")");
        }

        if (accessToken.expiresAt().isBefore(Instant.now())) {
            throw new OperioAuthException(
                    "Access token has expired (prefix: " + accessToken.tokenPrefix()
                            + ", expired at: " + accessToken.expiresAt() + ")");
        }

        Set<String> roles;
        if (accessToken.roles() != null && !accessToken.roles().isBlank()) {
            roles =
                    Arrays.stream(accessToken.roles().split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toUnmodifiableSet());
        } else {
            roles = Set.of();
        }

        return new IdentityContext(
                accessToken.createdBy(),
                token,
                null,
                roles,
                Map.of("token_prefix", accessToken.tokenPrefix(),
                       "token_name", accessToken.name()),
                accessToken.tenantId(),
                "access-token");
    }
}
