package com.koostack.operio.core.service;

import com.koostack.operio.core.Models.IdentityContext;
import com.koostack.operio.core.spi.Plugin;

/**
 * Active bearer token verification — the auth filter hot path.
 *
 * <p>Implementations actively validate a credential (API key hash lookup,
 * JWT signature verification against a JWKS endpoint, etc.) and return a
 * rich {@link IdentityContext} on success.
 *
 * <p>Unlike {@link IdentityResolver}, which passively reads ambient context
 * (e.g. {@code SecurityContextHolder}), a {@code TokenVerifier} performs
 * real validation work on every request.
 *
 * <h2>Known implementations</h2>
 * <ul>
 *   <li>{@code ApiKeyTokenVerifier} (operio-platform-jooq) — SHA-256 hashes
 *       the token, looks up the {@code api_key} table in the platform schema,
 *       validates status and expiration, returns an {@link IdentityContext}
 *       with subject, roles, and tenantId.</li>
 *   <li>Future IdP plugins (Okta, Auth0, Azure AD, Keycloak) — verify JWTs
 *       against external JWKS endpoints.</li>
 * </ul>
 *
 * @param <T> the plugin configuration type
 * @see IdentityResolver
 */
public interface TokenVerifier<T> extends Plugin<T> {

    /**
     * Actively verify a bearer token and return the associated identity.
     *
     * <p>The API key implementation hashes the token via SHA-256, looks up
     * the {@code api_key} table in the platform schema, validates status
     * and expiration, and returns an {@link IdentityContext} with subject,
     * roles, and tenantId.
     *
     * <p>Future IdP implementations (Okta, Auth0, Azure AD, Keycloak)
     * verify JWTs against external JWKS endpoints.
     *
     * @param token the raw bearer token from the Authorization header
     * @return the verified identity context — never {@code null}
     * @throws com.koostack.operio.core.exception.OperioAuthException
     *         if the token is invalid, expired, or revoked
     */
    IdentityContext verifyToken(String token);
}
