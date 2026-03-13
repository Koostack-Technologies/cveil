package com.koostack.cveil.core.models;

/**
 * Result of an authentication operation (login or token refresh).
 *
 * <p>Wraps the tokens issued by the underlying IdP (Keycloak, Auth0, etc.) along with the resolved
 * {@link IdentityContext} for the authenticated user.
 *
 * @param accessToken the short-lived access token (JWT)
 * @param refreshToken the long-lived refresh token (opaque or JWT, depending on IdP)
 * @param expiresIn access token TTL in seconds
 * @param tokenType token type, typically {@code "Bearer"}
 * @param identity the resolved identity context for the authenticated user
 */
public record AuthResult(
        String accessToken,
        String refreshToken,
        long expiresIn,
        String tokenType,
        IdentityContext identity) {
    public static AuthResult bearer(
            String accessToken, String refreshToken, long expiresIn, IdentityContext identity) {
        return new AuthResult(accessToken, refreshToken, expiresIn, "Bearer", identity);
    }
}
