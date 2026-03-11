package com.koostack.operio.rest.util;

import jakarta.ws.rs.core.NewCookie;
import java.util.Base64;

/**
 * Utility for managing HTTP-only authentication cookies.
 *
 * <p>Stores the JWT access token and refresh token as separate {@code HttpOnly} cookies so the
 * browser sends them automatically on every request to server-rendered pages (e.g. {@code
 * /dashboard}). JavaScript cannot read these cookies, mitigating XSS-based token theft.
 */
public final class AuthCookieHelper {

    public static final String ACCESS_COOKIE = "operio_access";
    public static final String REFRESH_COOKIE = "operio_refresh";
    private static final String COOKIE_PATH = "/";

    private AuthCookieHelper() {}

    /**
     * Creates {@code Set-Cookie} headers that store the access and refresh tokens.
     *
     * @param accessToken JWT access token from Keycloak
     * @param refreshToken opaque refresh token from Keycloak
     * @param accessExpiresIn access token TTL in seconds
     * @return array of two {@link NewCookie} instances (access, refresh)
     */
    public static NewCookie[] setAuthCookies(
            String accessToken, String refreshToken, long accessExpiresIn) {
        // Refresh token cookie lives longer than the access token (30 days)
        int refreshMaxAge = 30 * 24 * 60 * 60;
        return new NewCookie[] {
            new NewCookie.Builder(ACCESS_COOKIE)
                    .value(accessToken)
                    .path(COOKIE_PATH)
                    .maxAge((int) accessExpiresIn)
                    .httpOnly(true)
                    .sameSite(NewCookie.SameSite.LAX)
                    .build(),
            new NewCookie.Builder(REFRESH_COOKIE)
                    .value(refreshToken)
                    .path(COOKIE_PATH)
                    .maxAge(refreshMaxAge)
                    .httpOnly(true)
                    .sameSite(NewCookie.SameSite.LAX)
                    .build()
        };
    }

    /** Creates expired cookies to clear both auth tokens from the browser. */
    public static NewCookie[] clearAuthCookies() {
        return new NewCookie[] {
            new NewCookie.Builder(ACCESS_COOKIE)
                    .value("")
                    .path(COOKIE_PATH)
                    .maxAge(0)
                    .httpOnly(true)
                    .sameSite(NewCookie.SameSite.LAX)
                    .build(),
            new NewCookie.Builder(REFRESH_COOKIE)
                    .value("")
                    .path(COOKIE_PATH)
                    .maxAge(0)
                    .httpOnly(true)
                    .sameSite(NewCookie.SameSite.LAX)
                    .build()
        };
    }

    /**
     * Extracts a named cookie value from a raw {@code Cookie} header string.
     *
     * @param cookieHeader the raw Cookie header (e.g. "operio_access=abc; operio_refresh=xyz")
     * @param name the cookie name to look up
     * @return the cookie value, or {@code null} if not present
     */
    public static String readCookie(String cookieHeader, String name) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return null;
        }
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) {
                String value = trimmed.substring(name.length() + 1);
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    /**
     * Decodes the payload section of a JWT (without verification) to extract claims for display
     * purposes. This is safe for server-rendered pages where the cookie was set by this server.
     *
     * @param jwt the raw JWT string
     * @return the decoded JSON payload, or {@code null} if the token is malformed
     */
    public static String decodeJwtPayload(String jwt) {
        if (jwt == null) return null;
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) return null;
        try {
            return new String(Base64.getUrlDecoder().decode(parts[1]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
