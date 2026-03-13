package com.koostack.cveil.core.platform.iam;

import com.koostack.cveil.core.models.IdentityContext;
import com.koostack.cveil.core.spi.Plugin;

/**
 * Active bearer token verification — the auth filter hot path.
 *
 * <p>Implementations actively validate a credential (API key hash lookup, JWT signature
 * verification against a JWKS endpoint, etc.) and return a rich {@link IdentityContext} on success.
 *
 * <p>Unlike {@link com.koostack.cveil.core.sdk.IdentityResolver}, which passively reads ambient
 * context (e.g. {@code SecurityContextHolder}), a {@code TokenValidationService} performs real
 * validation work on every request.
 *
 * <h2>Known implementations</h2>
 *
 * <ul>
 *   <li>{@code ApiKeyTokenVerifier} (cveil-platform-jooq) — SHA-256 hashes the token, looks up the
 *       {@code api_key} table in the platform schema, validates status and expiration, returns an
 *       {@link IdentityContext} with subject, roles, and tenantId.
 *   <li>Future IdP plugins (Okta, Auth0, Azure AD, Keycloak) — verify JWTs against external JWKS
 *       endpoints.
 * </ul>
 *
 * @param <T> the plugin configuration type
 * @see com.koostack.cveil.core.sdk.IdentityResolver
 */
public interface TokenValidationService<T> extends Plugin<T> {

    /**
     * Actively verify a bearer token and return the associated identity.
     *
     * <p>The API key implementation hashes the token via SHA-256, looks up the {@code api_key}
     * table in the platform schema, validates status and expiration, and returns an {@link
     * IdentityContext} with subject, roles, and tenantId.
     *
     * <p>Future IdP implementations (Okta, Auth0, Azure AD, Keycloak) verify JWTs against external
     * JWKS endpoints.
     *
     * @param token the raw bearer token from the Authorization header
     * @return the verified identity context — never {@code null}
     * @throws com.koostack.cveil.core.exception.CveilAuthException if the token is invalid,
     *     expired, or revoked
     */
    IdentityContext verifyToken(String token);
}
